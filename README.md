# ApkHarden

一个自包含的 Android APK 加固工具（Compose Desktop GUI）。无需修改业务工程：首次保存正式签名后，只需选择 APK 和输出路径即可生成已加固、已签名的 APK。

- **静态守卫注入**：保留业务 `classes*.dex`，追加由系统正常加载的 `guard.dex`，不释放可写 DEX、不替换业务 `Application`、不调用隐藏 API
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
| `src/main/kotlin/.../packager/` | 桌面端、APK 检查、静态守卫注入、重打包与签名 |
| `guard/` | 静态 Guard Provider、签名校验与反调试源码 |
| `src/main/resources/guard.dex` | 生产上传式流程使用的最小守卫 DEX |
| `scripts/build-guard.ps1` | 重建 `guard.dex`（需 Android SDK） |

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

## 开发

```
./gradlew test              # 运行单元 + 集成测试
./gradlew deployToDesktop   # 打包发行版并镜像到 ~/ApkHarden（桌面快捷方式指向处），先关掉运行中的实例
$env:ANDROID_HOME='C:\AndroidSdk'; pwsh scripts/build-guard.ps1   # 改了 guard/ 后重建 guard.dex
```

> 打包发行版会显式带上 `jdk.unsupported` 模块：LWJGL 初始化依赖 `sun.misc.Unsafe`，jlink 默认会裁掉它，导致打包后（而非 `gradlew run`）文件对话框崩溃。

生成包正式上线前仍应自行完成覆盖安装与兼容性验证，尤其关注 Android 16、16KB page size 和多进程入口。

## 局限

静态守卫不会加密全部业务 DEX，纯 Java 层保护也可被 Frida/Xposed 绕过。更强的代码隐藏需要 Native 壳、VMP 或服务端商业加固能力，不在当前安全兼容基线内。
