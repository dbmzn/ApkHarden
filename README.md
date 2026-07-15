# ApkHarden

一个自包含的 Android APK 加固与发布校验工具（Compose Desktop GUI）。无需修改业务工程：选择 APK、正式 keystore 和输出路径即可生成已加固、已签名的 APK。

- **静态守卫注入**：保留业务 `classes*.dex`，追加由系统正常加载的 `guard.dex`，不释放可写 DEX、不替换业务 `Application`、不调用隐藏 API
- **防二次打包**：运行时校验签名 SHA-256，不符即退出
- **基础反调试**：检测调试器 / `TracerPid` / `FLAG_DEBUGGABLE`
- **多进程覆盖**：为主进程和 Manifest 中显式声明的业务进程注入非导出 Guard Provider
- **16KB 对齐**：重打包时保持未压缩 native 库的 16KB ZIP 对齐
- **签名**：输出包以 V1+V2+V3 方案签名并校验
- **加固报告**：输出 APK 的同时生成包含摘要、证书、进程和保护项的 JSON 报告

minSdk 23（Android 6.0）+。打包全程纯 JVM 库（[apksig](https://android.googlesource.com/platform/tools/apksig/) 签名 + [ARSCLib](https://github.com/REAndroid/ARSCLib) 改 manifest），用户无需安装 Android SDK。

---

ApkHarden 现已升级为**工具箱**，左侧导航栏可切换四个工具：

- **APK 加固**：输入 APK + 正式 keystore，直接生成加固签名包和 JSON 报告
- **生产校验**：对比线上旧包、候选包和正式签名
- **真机验证**：执行安装、覆盖升级、启动及运行时安全验证
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
| `src/main/kotlin/.../packager/` | 桌面端、APK 静态守卫注入、重打包与签名 |
| `harden-release-core/` | 线上覆盖、签名、ABI、ZIP/ELF 16KB 与发布报告 |
| `guard/` | 静态 Guard Provider、签名校验与反调试源码 |
| `src/main/resources/guard.dex` | 生产上传式流程使用的最小守卫 DEX |
| `scripts/build-guard.ps1` | 重建 `guard.dex`（需 Android SDK） |

## 使用

```
./gradlew run
```
进入“APK加固”，选择输入 APK、输出路径、keystore（.jks）+ 别名 + 密码，点击“开始加固并签名”。输出目录会得到：

```text
app-hardened.apk
app-hardened-report.json
```

如需上线前覆盖安装和兼容性门禁，再进入“生产校验”选择线上旧 APK。

## 开发

```
./gradlew test              # 运行单元 + 集成测试
./gradlew deployToDesktop   # 打包发行版并镜像到 ~/ApkHarden（桌面快捷方式指向处），先关掉运行中的实例
$env:ANDROID_HOME='C:\AndroidSdk'; pwsh scripts/build-guard.ps1   # 改了 guard/ 后重建 guard.dex
```

> 打包发行版会显式带上 `jdk.unsupported` 模块：LWJGL 初始化依赖 `sun.misc.Unsafe`，jlink 默认会裁掉它，导致打包后（而非 `gradlew run`）文件对话框崩溃。

正式上线前仍需完成覆盖安装和真机验证，尤其是 Android 16、16KB page size 和多进程入口。

## 局限

静态守卫不会加密全部业务 DEX，纯 Java 层保护也可被 Frida/Xposed 绕过。更强的代码隐藏需要 Native 壳、VMP 或服务端商业加固能力，不在当前安全兼容基线内。
