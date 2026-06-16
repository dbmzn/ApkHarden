# 落盘 DexClassLoader 改造 — 设计文档

日期: 2026-06-16
状态: 待评审

## 背景与目标

当前壳(一代)用 `InMemoryDexClassLoader` 在内存中加载解密后的原始 dex。两个问题:

1. **性能**: 内存加载的 dex 拿不到 AOT(无 oat 文件),ART 只能解释执行 + JIT,冷启动与冷方法明显变慢。用户明确表示不可接受。
2. **兼容性**: `InMemoryDexClassLoader` 是 API 26(Android 8.0)起才有,8.0 以下安装后必崩。用户要求兼容到 Android 6.0(API 23)。

**目标**: 改为「解密落盘 + `DexClassLoader` 加载」,让 ART 能对落盘 dex 生成并复用 oat,恢复接近原包的性能,同时把兼容下限降到 Android 6.0。

**非目标**: 不做二代抽取壳 / 三代 VMP(已与用户确认: 那是独立的「防逆向强度」方向,工程量=商业级,且与 6.0 兼容目标相冲突;如需高强度建议用商业壳)。

## 关键决策(已与用户确认)

| 决策 | 选择 |
|---|---|
| 加载机制 | 方案 A: `DexClassLoader` + 反射换 `LoadedApk.mClassLoader`(复用现有已验证逻辑,改动最小) |
| 缓存策略 | 持久缓存,性能优先;按 `versionCode` 隔离目录 |
| 兼容下限 | Android 6.0 / API 23 |
| 6.0 验证 | 暂不下载 API 23 镜像;自动验证用现有 API 26/28 模拟器 + 用户 API 29 真机;6.0~7.x 标「理论支持、未实测」 |
| 安全定位 | 明文 dex 持久驻留在 app 私有目录(沙箱级,非 root 不可读)——落盘方案的固有取舍,已接受 |

## 架构改动

### 打包端 — 不变
- `DexEncryptor`(deflate + AES)、`ApkRepackager`(STORED 对齐 + 0xd935)、加密 dex 存 `assets/d/*`、`ManifestPatcher`、签名/校验 —— 全部保留,无改动。
- 加密格式不变: `[16-byte IV] + AES/CBC/PKCS5( deflate(dex) )`。

### 壳端 `ProxyApplication.attachBaseContext` — 核心改动

```
1. 读 meta: SIG_HASH / APP_NAME / DEX_COUNT            (不变)
2. 安全校验: AntiDebug + AntiTamper(fail-closed)        (不变)
3. 准备缓存目录:
     cacheDir = base.getDir("apkharden_" + versionCode, MODE_PRIVATE)
     versionCode 取自 PackageInfo(用于 app 升级后失效旧缓存)
4. 解密落盘(仅首次或缓存缺失/损坏时):
     for i in 0 until DEX_COUNT:
        out = cacheDir/("c" + i + ".dex")
        if (!out.exists() || !valid(out)):
            bytes = readAsset("assets/d/" + i)
            plain = inflate(AES_decrypt(bytes))     // 复用现有 DexDecryptor
            atomicWrite(out, plain)                  // 先写 .tmp 再 rename,避免半写
        dexPaths.add(out.absolutePath)
5. oatDir = cacheDir/"oat"  (mkdirs)
6. nativeLibDir = base.getApplicationInfo().nativeLibraryDir
7. dexLoader = new DexClassLoader(
        join(dexPaths, File.pathSeparator), oatDir.absolutePath, nativeLibDir, parent)
8. 反射换 LoadedApk.mClassLoader → dexLoader          (复用现有 replaceLoadedApkClassLoader)
9. super.attachBaseContext(base)
```

`onCreate`(反射实例化真实 Application 并接管)—— **不变**。

### 移除 / 调整
- 移除 `InMemoryDexClassLoader` 用法。
- 移除 `copyNativeLibraryPaths`(改由 `DexClassLoader` 构造参数 `librarySearchPath` 直接提供 native 库路径)。
- `scripts/build-shell.ps1` 的 `d8 --min-api 26` 改为 `--min-api 23`。
- 审查 `AntiDebug` / `AntiTamper` / `ProxyApplication` 是否用到 > API 23 的方法;若有则降级或加版本判断。

## 关键设计点 / 风险处理

1. **缓存按 versionCode 隔离**: 目录名含 `versionCode`。app 升级后新 versionCode → 命中不到旧缓存 → 自动重新解密。旧版本目录可顺手清理(可选)。**不处理会导致升级后加载到旧代码**——必须做。
2. **原子写**: 解密写缓存先写临时文件再 rename,避免进程被杀导致半写的损坏 dex 被下次加载。`valid()` 至少校验文件存在且非空 + dex 魔数 `dex\n035`;可选加长度/CRC。
3. **首次启动成本**: 第一次冷启动需解密落盘 + 系统 dex2oat(一次性,大包可能数秒)。之后冷启动复用 oat → 接近原包。这是持久缓存换性能的必然代价,需在文档/README 注明。
4. **多 dex**: `DexClassLoader` 的 dexPath 用 `File.pathSeparator` 拼接多个 dex 文件,ART 一次加载全部。
5. **多进程**: 若 app 有多进程,每个进程都会跑 `attachBaseContext`;缓存文件可被多进程并发读/写。原子写 + 「存在即复用」可避免互相破坏(并发首次解密最坏是重复写同一内容,rename 幂等)。
6. **min-api 23 的影响**: `DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent)` 四参构造从 API 1 即有(API 26 起 `optimizedDirectory` 被忽略,但传了不报错,向后兼容)。反射的 `LoadedApk.mClassLoader`、`ActivityThread.mInitialApplication`/`mAllApplications`、`Application.attach(Context)` 在 API 23 均存在。

## 测试计划

### 单元测试(Kotlin,打包端)
- 打包端基本无改动,现有测试(压缩、对齐、加密往返、size guard)继续通过即可。
- 无需新增打包端单测(壳端逻辑无法在本工程 JVM 单测覆盖)。

### 壳端 / 端到端(真机 + 模拟器)
复用现有「装→启动→`pidof`→logcat 查 FATAL→截图」流程,对真实 app `com.qekj.merchant`:
1. **API 29 真机(华为 P30)**: 必过。重点确认: 无崩溃、能进登录页、首次启动后第二次冷启动更快(性能改善的直接证据)。
2. **API 28 模拟器**: 自动跑装→启→logcat。
3. **API 26 模拟器**: 自动跑装→启→logcat。
4. **性能对比(可选但推荐)**: `adb shell am start -W` 测加固包冷启动 `TotalTime`,对比改造前(内存加载)与改造后(落盘),给出毫秒数。
5. Android 6.0~7.x: 标「理论支持、未实测」(本次不下载 API 23 镜像)。

### 验收标准
- 三个环境(API 26/28 模拟器 + API 29 真机)均: 安装成功、启动无 FATAL、进入真实业务首屏。
- 改造后 API 29 冷启动(第二次起)较改造前有可测量的改善。
- 现有 Kotlin 单测全绿。

## 回滚

改动集中在 `ProxyApplication.java` + `shell.dex` + `build-shell.ps1`。如落盘方案在某环境出问题,可 git 回退到内存加载版本(commit `4a8cde8` 之后的壳)。
