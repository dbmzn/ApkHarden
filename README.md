# ApkHarden

一个以 APK 加固为核心的 Android 发布百宝箱（Compose Desktop GUI）。无需修改业务工程：首次保存正式签名后，只需选择 APK 和输出路径即可生成已加固、已签名的 APK；同时提供发布前体检和新旧 APK 对比。

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
- **APK 体检**：汇总签名、版本、SDK、ABI、DEX、Native 与包体积构成，并标记阻塞项和发布提醒
- **APK 对比**：对照新旧 APK 的包名、签名、版本、SDK、ABI 和包内文件增删改
- **签名百宝箱**：安全保存正式签名，查看证书主体、有效期、MD5/SHA-1/SHA-256，并比较两个 APK 的签名证书
- **设备安装验证**：自动发现 ADB 真机/模拟器，执行覆盖安装、冷启动、前台 Activity 和闪退日志门禁
- **Manifest 查看器**：图形化查看权限、Activity/Service/Receiver/Provider、进程、启动 Activity 与 Deep Link，支持搜索和导出风险标记
- **APK 文件浏览器**：按 DEX、Native、资源、Assets 等分类浏览包内文件，展示目录大小占比和大文件排行
- **包体积分析**：比较两个 APK 的模块增量和具体文件增减，直接定位 SO、DEX、资源等体积来源
- **ADB 工具箱**：提供日志过滤、截图预览/复制/保存、录屏、性能快照、Intent / Deep Link 调试、崩溃与 ANR 采集包、权限授予、应用数据清理、进程和页面栈查看；截图与录屏自动使用时间命名，缺少系统 `screenrecord` 的设备会自动切换到随桌面版部署的 scrcpy 高帧率录制，连续截图编码仅作为最后保底，诊断包会把设备、应用、CPU、内存、页面栈、Crash buffer、last ANR 与 DropBox 信息打包到 `Downloads`，清数据操作会二次确认

加固产物支持 minSdk 23（Android 6.0）+。APK 加固、签名和静态分析全程使用纯 JVM 库（[apksig](https://android.googlesource.com/platform/tools/apksig/) 签名 + [ARSCLib](https://github.com/REAndroid/ARSCLib) 修改 Manifest），不依赖业务工程，也不要求安装 Android SDK；设备安装验证和 ADB 工具箱需要本机能够执行 `adb`。

## 功能导航

| 分区 | 功能 | 主要用途 |
|---|---|---|
| 核心功能 | APK 加固 | DEX 加密、壳注入、16KB ZIP 对齐、正式签名和加固报告 |
| APK 工具 | APK 体检 | 检查签名、版本、SDK、ABI、DEX、Native、包体积和发布风险 |
| APK 工具 | Manifest 查看器 | 搜索权限、四大组件、进程、启动 Activity、Deep Link 和风险项 |
| APK 工具 | APK 文件浏览器 | 按 DEX、SO、资源和 assets 查看目录占比及大文件排行，无需完整反编译 |
| APK 工具 | APK 对比 | 对比两个 APK 的身份信息、签名、版本、ABI 和包内文件变化 |
| APK 工具 | 包体积分析 | 按模块和文件定位两版 APK 的体积增量来源 |
| 设备工具 | 设备安装验证 | 覆盖安装并执行冷启动、进程、前台 Activity 和闪退日志门禁 |
| 设备工具 | ADB 工具箱 | 日志、截图、录屏、性能、Intent、故障采集、权限、进程和页面栈操作 |
| 配置 | 签名百宝箱 | 保存正式签名配置、查看证书指纹并比较两个 APK 的签名证书 |

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

桌面端以“APK 加固”为突出主入口，APK 工具区包含体检、Manifest、文件浏览、版本对比和包体积分析，设备工具区包含安装验证与 ADB 工具箱，配置区提供签名百宝箱。进入“APK 加固”后只需选择输入 APK 和输出路径，已保存的正式签名会被自动复用。点击“开始加固并签名”后，输出目录会得到：

```text
app-hardened.apk
app-hardened-report.json
```

命令行也可以复用桌面端已保存的 DPAPI 签名配置，避免把密码写入命令历史：

```powershell
./gradlew harden --args="--input app.apk --output app-hardened.apk --savedProfile true"
```

### ADB 工具箱

连接真机或模拟器并开启 USB 调试后，进入“设备工具 → ADB 工具箱”，选择在线设备即可使用：

| 工具 | 操作与结果 |
|---|---|
| 日志过滤 | 可按包名限定当前进程，并按关键字过滤最近 800 行日志 |
| 设备截图 | 截图后在当前应用中预览；可复制到剪贴板或保存到 `Downloads` |
| 设备录屏 | 默认录制 15 秒并自动保存到 `Downloads` |
| 性能快照 | 输入包名后一次采集目标进程 PID、CPU、内存和界面渲染数据 |
| Intent / Deep Link | 配置 Action、URI、包名、组件和 Category，通过 `am start -W` 返回启动状态与耗时 |
| 崩溃与 ANR | 输入包名后生成包含设备、应用、进程、页面栈、CPU、内存、日志、Crash buffer、last ANR 和 DropBox 的 ZIP |
| 应用操作 | 授予运行时权限；清除应用数据前会再次确认 |
| 进程与页面栈 | 按包名查看相关进程和 Activity/Task 状态 |

自动生成的文件使用时间戳命名：

```text
Downloads/ApkHarden-screenshot-yyyyMMdd-HHmmss.png
Downloads/ApkHarden-screenrecord-yyyyMMdd-HHmmss.mp4
Downloads/ApkHarden-diagnostics-yyyyMMdd-HHmmss.zip
```

普通非 root 设备通常不能直接读取 `/data/anr/traces.txt`，因此诊断包使用系统允许访问的 `dumpsys activity lastanr`、DropBox、Crash log buffer 和其他运行状态作为替代证据。

## 开发

```
./gradlew test              # 运行单元 + 集成测试
./gradlew deployToDesktop   # 打包发行版并镜像到 ~/ApkHarden（桌面快捷方式指向处），先关掉运行中的实例
$env:ANDROID_HOME='C:\AndroidSdk'; pwsh scripts/build-shell.ps1   # 改了 guard/shell/native 后重建壳资源
pwsh scripts/verify-device-launch.ps1 -Apk app-hardened.apk -PackageName com.example.app -Serial <设备序列号>  # 安装、冷启动和崩溃门禁
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

运行时按单个 DEX 依次解密并立即清零临时数组，避免同时保留全部明文 DEX；旧系统缓存会按 APK 内签名保护的长度和 SHA-256 元数据复验。壳初始化失败时可在 logcat 中检索 `APH-E` 诊断码。

## 局限

业务 DEX 在 APK 中不再能被 JADX 直接反编译，但运行时仍必须解密执行；具备 root、Hook 或内存 Dump 能力的攻击者仍可能脱壳。当前属于基础 DEX 壳，不包含 VMP、DEX2C、SO 加壳或高强度 Frida/Xposed 对抗。
