# ApkHarden

一个自包含的 Android APK 基础加固工具（Compose Desktop GUI），对齐「360 免费加固」基础套餐：

- **DEX 整体加壳**：deflate + AES 加密原始 `classes*.dex`，运行时解密到 app 私有目录（按 `versionCode` 隔离），把解密 dex **合并进宿主 `PathClassLoader`**（而非子 DexClassLoader，保证壳 / 明文 / 解密 dex 三方类引用双向可解析）——ART 可生成并复用 oat，性能接近原包
- **防二次打包**：运行时校验签名 SHA-256，不符即退出
- **基础反调试**：检测调试器 / `TracerPid` / `FLAG_DEBUGGABLE`
- **加固前静态检查**：扫描输入 APK，对加固后易静默失效的写法（TheRouter/ARouter 扫 dex、blankj `getTopActivity`、AndroidX Startup、多进程等）给出警告提示（仅警告、不阻断）
- **签名**：输出包以 V1+V2+V3 方案签名并校验

minSdk 23（Android 6.0）+。打包全程纯 JVM 库（[apksig](https://android.googlesource.com/platform/tools/apksig/) 签名 + [ARSCLib](https://github.com/REAndroid/ARSCLib) 改 manifest），用户无需安装 Android SDK。

> **加载方式**：早期用 `InMemoryDexClassLoader`（仅 API 26+、无 AOT、运行慢），现改为「解密落盘 + 合并进宿主 `PathClassLoader`」——兼容下限降到 Android 6.0，且能用 AOT。代价：**首次启动**（或 app 升级后首启）需一次性解密 + `dex2oat`（大包数秒），之后冷启动复用 oat。明文 dex 持久驻留在 app 私有目录（沙箱级保护）。多进程冷启动用跨进程文件锁串行化解密，避免并发写出损坏的 dex。

---

ApkHarden 现已升级为**工具箱**，左侧导航栏可切换两个工具：

- **基础加固**（原有功能，见上）
- **隐私合规扫描**（见下）

## 隐私合规扫描

纯静态、只读扫描，不修改任何文件，适合在发版前对本包做自查。扫描维度：

| 维度 | 数据来源 | 说明 |
|---|---|---|
| 权限声明 | `AndroidManifest.xml` | 列出所有 `<uses-permission>`，标注敏感级别 |
| 三方 SDK 识别 | dex 类型表（dexlib2 解析） | 按包名前缀匹配已知 SDK，识别广告/推送/统计等 |
| 敏感 API 调用点 | dex method refs | 定位调用 `TelephonyManager`、`Location`、剪贴板等高风险 API 的方法 |
| Manifest 合规项 | `AndroidManifest.xml` | 检查 `targetSdk`、`android:debuggable`、`android:allowBackup` |

**检测规则**位于 `src/main/resources/rules/`（JSON 格式），无需改代码即可增删规则。

**输出**：应用内分组展示扫描结果，可导出 HTML 或 Markdown 报告。

> **定位说明**：本工具输出的是风险*疑点*，供上线前自检参考，**不等于合规结论**。静态扫描无法判断某个敏感 API 调用是否在用户同意前发生（需动态分析）；最终合规判定以监管机构/第三方检测意见为准。

## 结构

| 部分 | 说明 |
|---|---|
| `src/main/kotlin/.../packager/` | 打包器：读 APK → 加密 dex → 换壳 → 改 manifest → 重打包 → 签名 |
| `shell/` | Java 写的壳运行时（`ProxyApplication` 等），编译为 `shell.dex` |
| `src/main/resources/shell.dex` | 预编译并内嵌的壳 |
| `scripts/build-shell.ps1` | 重建 shell.dex（需 Android SDK：`javac` + `d8`） |

## 使用

```
./gradlew run
```
界面里选输入 APK、输出路径、keystore（.jks）+ 别名 + 密码，点「开始加固」。文件选择走系统原生对话框（Windows 上为现代资源管理器，含快速访问栏），由 LWJGL NFD 驱动。

## 开发

```
./gradlew test              # 运行单元 + 集成测试
./gradlew deployToDesktop   # 打包发行版并镜像到 ~/ApkHarden（桌面快捷方式指向处），先关掉运行中的实例
$env:ANDROID_HOME='C:\AndroidSdk'; pwsh scripts/build-shell.ps1   # 改了 shell/ 后重建 shell.dex
```

> 打包发行版会显式带上 `jdk.unsupported` 模块：LWJGL 初始化依赖 `sun.misc.Unsafe`，jlink 默认会裁掉它，导致打包后（而非 `gradlew run`）文件对话框崩溃。

壳的反射接管需在真机/模拟器验证，见 [samples/README.md](samples/README.md)。

## 局限

纯 Java 层保护可被 Frida/Xposed 绕过，密钥内嵌可逆向提取 —— 与「免费基础版」同档；
更强需上 Native（ptrace 自附加、抽取壳/VMP），不在本工具范围。设计与计划见 `docs/superpowers/`。
