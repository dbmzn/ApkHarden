# ApkHarden

一个自包含的 Android APK 加固工具（Compose Desktop GUI）。无需修改业务工程：首次保存正式签名后，只需选择 APK 和输出路径即可生成已加固、已签名的 APK。

- **业务 DEX 加密**：压缩后使用每包独立 AES-256-GCM 密钥加密，APK 中不再保留明文业务 `classes*.dex`
- **Native 解密**：每个输出 APK 的随机密钥注入对应 ABI 的 `libapkharden.so`，密文被修改即拒绝加载
- **现代内存加载**：Android 10（API 29）以上通过公开 `AppComponentFactory` + `InMemoryDexClassLoader` 在内存加载
- **Native 库兼容**：内存 ClassLoader 同时继承安装目录和 `apk!/lib/<abi>` 搜索路径，兼容 `extractNativeLibs=false` 下的 MMKV 等业务库
- **旧系统兼容**：Android 6～9 使用应用私有、版本隔离且加载前设为只读的 DEX 缓存
- **防二次打包**：运行时校验签名 SHA-256，不符即退出
- **基础反调试**：检测调试器 / `TracerPid` / `FLAG_DEBUGGABLE`
- **多进程覆盖**：为主进程和 Manifest 中显式声明的业务进程注入非导出 Guard Provider
- **16KB 对齐**：重打包时保持未压缩 native 库的 16KB ZIP 对齐
- **签名**：输出包以 V1+V2+V3 方案签名并校验
- **加固报告**：输出 APK 的同时生成包含摘要、证书、进程和保护项的 JSON 报告

minSdk 23（Android 6.0）+。打包全程纯 JVM 库（[apksig](https://android.googlesource.com/platform/tools/apksig/) 签名 + [ARSCLib](https://github.com/REAndroid/ARSCLib) 改 manifest），用户无需安装 Android SDK。

## 结构

| 部分 | 说明 |
|---|---|
| `src/main/kotlin/.../packager/` | 桌面端、APK 检查、DEX 加密壳注入、重打包与签名 |
| `guard/` | Guard Provider、签名校验与反调试源码 |
| `shell/` | Proxy Application、AppComponentFactory 与多版本 ClassLoader 接入 |
| `native/` | Native 密钥槽、AES-GCM 调用和 DEX 解压 |
| `src/main/resources/shell.dex` | 加固 APK 中唯一公开的壳 DEX |
| `src/main/resources/shell-libs/` | arm64-v8a、armeabi-v7a、x86_64、x86 的 16KB 壳库 |
| `scripts/build-shell.ps1` | 使用 Android SDK/NDK 重建壳 DEX 和 Native 库 |

## 使用

```
./gradlew run
```
首次使用时进入“签名工具”，选择 keystore（`.jks` / `.p12`），填写别名和密码，点击“验证并保存”。签名密码通过 Windows DPAPI 加密，仅当前 Windows 用户可以解密。

之后进入“APK 加固”，只需选择输入 APK 和输出路径，已保存的正式签名会被自动复用。点击“开始加固并签名”后，输出目录会得到：

```text
app-hardened.apk
app-hardened-report.json
```

命令行也可以复用桌面端已保存的 DPAPI 签名配置，避免把密码写入命令历史：

```powershell
./gradlew harden --args="--input app.apk --output app-hardened.apk --savedProfile true"
```

## 开发

```
./gradlew test              # 运行单元 + 集成测试
./gradlew deployToDesktop   # 打包发行版并镜像到 ~/ApkHarden（桌面快捷方式指向处），先关掉运行中的实例
$env:ANDROID_HOME='C:\AndroidSdk'; pwsh scripts/build-shell.ps1   # 改了 guard/shell/native 后重建壳资源
```

> 打包发行版会显式带上 `jdk.unsupported` 模块：LWJGL 初始化依赖 `sun.misc.Unsafe`，jlink 默认会裁掉它，导致打包后（而非 `gradlew run`）文件对话框崩溃。

### 已验证兼容性

当前加密壳已完成以下安装与冷启动验证：

| 环境 | 验证内容 | 结果 |
|---|---|---|
| API 26 / x86 模拟器 | Provider、原 Application、Activity、多 DEX | 通过 |
| API 28 / ARMv7 转译模拟器 | 真实 `product_32` 业务 APK | 通过 |
| API 29 / 华为 arm64 真机 / 4KB | 真实 `product_64` 业务 APK、MMKV native 加载 | 通过 |
| API 36 / 小米 arm64 真机 / 4KB | 真实 `product_64` 业务 APK | 通过 |
| API 36 / x86_64 模拟器 / 16KB | 真实 `product_64` 业务 APK、ARM64 转译 | 通过 |

这组验证覆盖 API 24～27 的旧版 ClassLoader 分支、API 28 兼容分支和 API 29+ 内存加载分支。正式上线仍建议先灰度并监控启动崩溃；模拟器不能替代所有厂商 ROM 和业务功能回归。

## 局限

业务 DEX 在 APK 中不再能被 JADX 直接反编译，但运行时仍必须解密执行；具备 root、Hook 或内存 Dump 能力的攻击者仍可能脱壳。当前属于基础 DEX 壳，不包含 VMP、DEX2C、SO 加壳或高强度 Frida/Xposed 对抗。
