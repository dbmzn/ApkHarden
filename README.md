# 百工WorkKit

**把设备调试、日常开发和 APK 处理，放进一个桌面工具箱。**

百工（英文名 `WorkKit`）由 ApkHarden 更名而来。应用窗口、安装包和新导出文件使用新名称，原有外观与签名配置继续使用兼容的存储标识。

面向 Android、Web 和后端开发者的本地桌面工具箱。连接手机即可截图、录屏、镜像和互传文件；无需连接设备，也能处理 JSON、比较文本、转换编码、计算时间，以及压缩、转换、缩放和裁剪图片。Android 发布环节还提供 APK 分析、版本对比、加固与签名管理。

[快速上手](#快速上手) · [通用开发工具](#通用开发工具) · [图片工具](#图片工具) · [界面预览](#界面预览) · [编译与运行](#编译与运行) · [常见问题](docs/FAQ.md) · [加固说明](docs/hardening.md)

- **截图、录屏与实时镜像**：USB / Wi-Fi 连接，截图预览与标注、短视频录制，以及独立镜像窗口中的查看和操控。
- **日常调试**：手机剪贴板、双向文件互传、日志过滤与故障诊断。
- **通用开发**：JSON 格式化与查询、文本 / JSON 对比、编码解码、时间戳与 Cron 预览，均在本机处理。
- **图片处理**：PNG / JPEG / WebP 批量压缩与互转，等比或指定尺寸缩放，可视化裁剪，前后对比后安全另存。
- **发布检查**：APK 体检、Manifest 查看、文件浏览、版本对比和包体增量分析。
- **加固与签名**：业务 DEX 加密、壳注入、16 KB ZIP 对齐、V1 + V2 + V3 签名和 JSON 报告。
- **可切换外观**：默认「经典绿青」，另有 3 套明亮皮肤；支持浅色、深色、跟随系统及自动记忆。

日常桌面使用以 **macOS、Windows** 为主。项目配置了 Linux 构建目标，但 Linux 尚未完整验证，且目前不支持安全保存签名密码。平台差异见[环境要求](#环境要求)。

## 主要功能

### 设备与日常工具

| 入口 | 可以做什么 | 使用条件 |
| --- | --- | --- |
| 截图与录屏 | 截图预览、矩形 / 箭头标注、复制图片或保存 PNG；录制 5 / 10 / 15 秒视频并保存 MP4 | 在线 ADB 设备；默认启动页 |
| 实时镜像 | 在独立窗口查看和操控手机，按设备比例适配桌面可用区域 | 在线 ADB 设备、scrcpy |
| 手机剪贴板 | 每秒接收手机复制的文字或链接，选中或一键复制到电脑 | 在线设备；部分系统会限制读取 |
| 文件互传 | 发送本地文件，浏览手机目录并接收文件，以聊天式记录展示传输结果 | 在线设备及可访问目录 |
| 二维码与条形码 | 生成 QR、Code 128、EAN-13、EAN-8、Code 39、ITF；从图片或剪贴板识别码图 | 本地使用，无需连接设备 |
| 更多调试 | 日志过滤、性能快照、Intent / Deep Link、崩溃与 ANR 采集、权限授予、清除数据、进程及页面栈 | 在线 ADB 设备；部分操作需要包名 |
| 设备验证 | 覆盖安装、冷启动，检查进程、前台 Activity 与崩溃日志 | 在线设备、待验证 APK 和包名 |

设备工具右上角提供「无线连接」：支持 Android 11+ 二维码配对、配对码、设备发现和手动连接；旧版设备可先通过 USB 开启 TCP 调试。详见[设备工具说明](#设备工具说明)。

### 通用开发工具

点击侧栏「通用工具」，或按 **⌘K / Ctrl+K** 打开工具搜索。无需连接 Android 设备。

| 工具 | 可以做什么 |
| --- | --- |
| JSON 工具 | 格式化、压缩、校验、错误位置提示，树形展开与字段 / 值搜索，JSONPath 查询；输出可直接送入对比工具 |
| 文本对比 | 左右逐行差异、忽略空白、交换内容；JSON 模式按字段比较，忽略对象键顺序，区分缺失字段与 null |
| 编码解码 | URL 组件、表单 URL、Base64 / Base64URL、Unicode、常用 HTML 实体、JSON 字符串转义及 JWT 内容 / 过期时间解析 |
| 时间工具 | 秒 / 毫秒时间戳转换、IANA 时区转换、时间差计算、Unix 5 位与 Spring 6 位 Cron 的未来执行时间 |
| 图片工具 | PNG / JPEG / WebP 压缩与互转、等比 / 指定宽高 / 百分比缩放、拖框与像素裁剪、透明背景合成、批量预览另存 |

文本类工具支持显式粘贴、打开 UTF-8 文件，以及复制 / 保存处理结果。切换工具或离开通用工具页后，输入和结果仍保留到应用关闭；不会自动读取剪贴板或持久保存输入历史。JWT 仅解析、不验签；Cron 仅预览、不执行任务。具体用法与输入限制见[通用工具说明](docs/developer-tools.md)。

### 图片工具

入口：**通用工具 → 图片工具**。支持静态 PNG、JPEG、WebP，全程在本机处理，无需账号或 API Key。

| 功能 | 使用方式 |
| --- | --- |
| 智能压缩 | 体积优先、均衡、画质优先三档；允许像素变化，适合减小图片体积 |
| 无损优化 | 优化现有图像编码；仅压缩时保留尺寸、格式和原元数据 |
| 格式互转 | PNG、JPEG、WebP 三种格式互转；PNG / WebP 保留透明度，JPEG 可选白底、黑底或自定义背景 |
| 调整尺寸 | 等比适配、指定宽高拉伸、百分比缩放；等比模式默认不放大，可显式开启 |
| 可视化裁剪 | 拖框选择、框内移动、角点调节，或精确填写 X、Y、宽度、高度；按校正方向后的画面操作 |
| 批量与预览 | 格式和尺寸对整批生效，裁剪逐张设置；查看实际输出尺寸、格式、体积增减，支持同步放大对比 |

**添加图片 → 转换 / 尺寸 / 裁剪 → 应用设置 → 开始处理 → 批量另存到文件夹。** 只需压缩时，可直接选择压缩模式并开始。

- 原图不覆盖，导出重名自动编号；每次从导入时的快照处理，不累积上次编码的损失。
- 纯压缩结果更大时保留原始文件；实际转换、缩放或裁剪后，即使文件变大，也保留所选编辑结果。
- 切换工具会保留队列和未应用的编辑草稿。设置改变后，旧结果需重新处理才可导出；取消任务保留已完成结果。
- 实际编辑统一输出为 8 位 sRGB，并移除旧 EXIF 等元数据；JPEG 编辑后需要有损重编码。
- 单张输入最多 32 MB / 2400 万像素，每批最多 100 张 / 256 MB；暂不支持动画、GIF、AVIF 和 CMYK JPEG。

macOS 分发包已内置图片引擎，Apple Silicon 已验证；Windows / Linux 需额外配置，尚未完成图片功能验证。完整限制、构建方式及压缩样本数据见[图片工具说明](docs/image-tools.md)。

### APK 与签名工具

| 入口 | 可以做什么 |
| --- | --- |
| APK 体检 | 汇总签名、版本、SDK、ABI、DEX、Native 和包体构成，展示检查分、阻塞项与提醒 |
| Manifest | 查看权限、Activity / Service / Receiver / Provider、进程、启动 Activity、Deep Link 和导出组件风险；支持搜索 |
| 文件浏览 | 按 DEX、Native、资源、Assets 等分类查看目录占比与文件大小，支持路径搜索，无需完整反编译 |
| APK 对比 | 对照两版 APK 的包名、签名、版本、SDK、ABI，以及包内文件的新增、删除和修改 |
| 包体分析 | 对比两版 APK 的模块增量与文件增减，定位 DEX、SO、资源等体积来源 |
| APK 加固 | 加密业务 DEX，注入运行时壳和守卫，完成对齐、正式签名与输出验证 |
| 签名百宝箱 | 验证并保存正式签名配置，查看证书主体、有效期、算法与指纹，检查或比较 APK 签名 |

APK 静态分析、签名与加固使用本机 JVM 库，不需要连接手机，也不依赖业务工程。普通桌面构建使用仓库内的预编译壳资源；**只有重建壳资源或构造真机测试 APK 时才需要额外的 Android 构建工具**。

## 快速上手

以下功能可按需使用，通用开发和图片工具无需先连接手机。

1. **启动应用**：打开本机打包生成的应用，或按[编译与运行](#编译与运行)准备环境后从源码启动。
2. **连接设备**：开启手机 USB 调试、连接数据线并允许授权，点击「刷新设备」；也可以使用右上角「无线连接」。选择在线设备后开始截图、录屏或调试。
3. **检查 APK**：进入「APK 体检」，点击「浏览」选择文件，再点击「开始体检」。需要查看具体内容时，进入 Manifest 或文件浏览；对比工具需分别选择旧包、新包。
4. **生成码图**：进入「二维码与条形码」，选择格式、填写内容、生成预览，随后复制图片或保存 PNG。二维码支持可选中心图片、替换 / 移除及三档占比。
5. **加固签名**：先在「签名百宝箱」填写 keystore、别名和密码，点击「验证并保存」；再进入「APK 加固」，选择输入与输出文件，点击「开始加固并签名」。
6. **处理日常开发数据**：按 ⌘K / Ctrl+K 搜索 JSON、文本对比、编码解码或时间工具；可先使用内置示例查看效果。
7. **处理图片**：打开「通用工具 → 图片工具」，多选图片；需要编辑时进入「转换 / 尺寸 / 裁剪」，应用设置后处理，检查预览并批量另存。
8. **切换外观**：点击左下角「换肤」，选中皮肤和外观模式即可生效；「恢复默认」回到经典绿青与浅色模式。

侧栏使用图标导航：当前工具显示名称，悬停可查看其他工具的名称与用途。文件输入框支持浏览选择或手动填写路径。

加固成功后，输出目录中会生成：

```text
app-hardened.apk
app-hardened-report.json
```

输入和输出必须是不同文件。已签名的输入 APK 应使用原发布证书；具体准入条件与能力范围见[加固说明](docs/hardening.md)。

## 界面预览

以下展示实际桌面界面及 Compose 自动化渲染的图片工具页面，默认使用「经典绿青」。APK 结果使用独立测试包，签名配置为空；图片工具使用透明测试图形。部分设备、APK 与通用工具截图采集于更名前，窗口中仍显示 ApkHarden；图片工具演示已同步新名称。截图来源见[说明](docs/screenshots/README.md)，点击图片可查看原图。

### 截图与录屏

连接设备后，一处完成截图与录屏：截图可预览、标注、复制或保存；录屏支持 5 / 10 / 15 秒，并自动保存 MP4。

![截图与录屏主界面：设备选择、截图入口和录制时长](docs/screenshots/capture-recording.jpg)

### 实时镜像

在独立窗口中查看和操控设备，按屏幕比例适配桌面可用区域。下图展示镜像已运行时的工具页面、启停控制与快捷操作说明，**不包含独立镜像窗口中的设备画面**。

![实时镜像工具页：连接状态、停止镜像按钮和操作说明，不含设备画面](docs/screenshots/live-mirror.jpg)

### 图片转换、尺寸与裁剪

下图由实际 Compose 页面组件自动渲染，展示透明 PNG 的裁剪、等比缩放和 WebP 输出。格式与尺寸统一设置，裁剪框按图片分别保存。

| 格式、尺寸与裁剪设置 | 处理结果与前后对比 |
| :---: | :---: |
| ![图片转换、尺寸与裁剪设置](docs/screenshots/image-edit-settings.png) | ![裁剪缩放后转为 WebP 的结果](docs/screenshots/image-edit-result.png) |

<details>
<summary>APK 导入与深浅主题</summary>

| APK 导入 · 浅色 | APK 导入 · 深色 |
| :---: | :---: |
| ![经典绿青浅色下的 APK 导入页](docs/screenshots/apk-import-light.jpg) | ![经典绿青深色下的 APK 导入页](docs/screenshots/apk-import-dark.jpg) |

</details>

<details>
<summary>APK 分析：体检、Manifest 与包内文件</summary>

| 体检结果 | 权限与组件 |
| :---: | :---: |
| ![测试 APK 的体检结果](docs/screenshots/apk-inspection.jpg) | ![测试 APK 的 Manifest 组件信息](docs/screenshots/manifest.jpg) |

![APK 文件分类、目录占比与文件清单](docs/screenshots/archive.jpg)

</details>

<details>
<summary>版本对比与包体增量</summary>

| APK 对比 | 包体分析 |
| :---: | :---: |
| ![原始测试包与加固测试包的对比](docs/screenshots/apk-comparison.jpg) | ![测试包体积变化与模块增量](docs/screenshots/size-analysis.jpg) |

</details>

<details>
<summary>加固与签名管理</summary>

| APK 加固 | 签名百宝箱 |
| :---: | :---: |
| ![APK 加固的文件与签名表单](docs/screenshots/hardening.jpg) | ![签名配置与证书信息入口](docs/screenshots/signing.jpg) |

</details>

<details>
<summary>二维码生成与预览</summary>

![生成二维码、调整规格并预览图片](docs/screenshots/barcode.jpg)

</details>

<details>
<summary>通用开发：JSON、对比、编码、时间与图片工具</summary>

| JSON 格式化与查询 | 文本 / JSON 对比 |
| :---: | :---: |
| ![本地 JSON 格式化与查询工具](docs/screenshots/developer-json.jpg) | ![按 JSON 字段展示左右差异](docs/screenshots/developer-diff.jpg) |

| 编码解码 | 时间与 Cron |
| :---: | :---: |
| ![中文和 Emoji 的 URL 编码转换](docs/screenshots/developer-codec.jpg) | ![指定时区的 Cron 后续执行时间](docs/screenshots/developer-time.jpg) |

图片工具的实际 Compose 页面渲染（测试图片）：

| 本地图片压缩 · 浅色 | 本地图片压缩 · 深色 |
| :---: | :---: |
| ![图片压缩队列和前后对比](docs/screenshots/image-compression-light.png) | ![深色外观下的图片压缩](docs/screenshots/image-compression-dark.png) |

</details>

<details>
<summary>皮肤库与深浅外观</summary>

<img src="docs/screenshots/skin-library.jpg" alt="四套皮肤及浅色、深色、跟随系统选项" width="660" />

</details>

## 皮肤与外观

左下角 **换肤 → 皮肤与外观** 提供 4 套主题：

| 皮肤 | 配色 | 默认行为 |
| --- | --- | --- |
| 经典绿青 | 浅绿主卡片、浅青辅助卡片、橄榄绿按钮 | 首次启动使用此皮肤与浅色模式 |
| 晴空柠檬 | 柠檬黄、晴空蓝与蓝色按钮 | 在皮肤库中选择 |
| 薄荷葡萄 | 薄荷绿、葡萄紫与紫色按钮 | 在皮肤库中选择 |
| 蜜桃奶油 | 蜜桃粉、奶油黄与莓红按钮 | 在皮肤库中选择 |

- 每套均有浅色和深色版本，也可选择跟随系统。
- 选中后当前页面立即换肤，皮肤与外观模式自动保存；重新打开应用时恢复上次选择。
- 外观配置与签名配置分别存储，换肤不修改签名资料。
- 「恢复默认」重置为经典绿青与浅色模式；配置损坏或皮肤标识失效时有默认回退。

## 设备工具说明

### USB 与无线连接

USB 使用前确认 `adb devices -l` 能列出目标设备且状态为 `device`。应用会查找 `ANDROID_HOME` / `ANDROID_SDK_ROOT`、常见 SDK 安装位置和 `PATH` 中的 ADB。

无线连接需要电脑与手机处于可互通的网络：

- **Android 11+**：在手机「开发者选项 → 无线调试 → 使用二维码配对」扫描工具中的二维码；也可以使用配对码或手动连接地址。配对二维码有效期为 2 分钟，可刷新。
- **配对码与手动连接**：配对端口和日常连接端口可能不同，应分别填写手机对应页面的地址。
- **旧版设备**：先通过 USB 启用 TCP 5555，再连接设备地址；使用后可在工具中关闭旧版无线调试。

连接成功后刷新并选择设备。无线能力仍取决于 Android 版本、Platform-Tools 版本和网络条件。

### 画面、剪贴板与文件

| 功能 | 行为说明 |
| --- | --- |
| 截图 | 先预览，可添加矩形和箭头，再复制图片或保存到下载目录 |
| 录屏 | 默认 10 秒，可选 5 / 10 / 15 秒；优先设备原生录制，失败时尝试 scrcpy，连续截图编码作为最后兼容路径 |
| 镜像 | 在独立 scrcpy 窗口操作；常用快捷键包括右键返回、Ctrl+H 回到桌面、Ctrl+S 最近任务。高分辨率设备启动时将视频最大边限制为 1920 |
| 手机剪贴板 | 仅在此页面接收并临时展示文字或链接；离开页面停止接收，不保存历史；不支持图片或文件剪贴板 |
| 文件互传 | 每次传输一个文件，支持取消；同名文件不覆盖；传输记录按设备隔离，离开文件互传页会取消正在进行的传输 |

截图、镜像、剪贴板、文件互传与「更多调试」共用 ADB 页面会话；在这些入口间切换可保留设备选择和镜像连接。切换到二维码、设备验证、APK 工具等其他页面会离开该会话，清理镜像及临时传输记录。

### 日志与诊断

「更多调试」提供以下子工具：

| 工具 | 输入与输出 |
| --- | --- |
| 日志过滤 | 可按包名限定当前进程，按关键字过滤最近 800 行日志 |
| 性能快照 | 输入包名，读取 PID、CPU、内存与界面渲染信息 |
| Intent / Deep Link | 设置 Action、URI、包名、组件和 Category，查看启动结果及耗时 |
| 崩溃与 ANR | 生成包含设备、应用、进程、页面栈、CPU、内存、日志、Crash buffer、last ANR 和 DropBox 的 ZIP |
| 应用操作 | 授予运行时权限；清除应用数据前再次确认 |
| 进程与页面栈 | 查看进程与 Activity / Task 状态，可按包名限定 |

自动保存的文件使用时间戳命名：

```text
~/Downloads/WorkKit-screenshot-yyyyMMdd-HHmmss.png
~/Downloads/WorkKit-screenrecord-yyyyMMdd-HHmmss.mp4
~/Downloads/WorkKit-diagnostics-yyyyMMdd-HHmmss.zip
```

普通非 root 设备对系统日志和文件的访问有权限限制；诊断包会收集系统允许读取的信息，不能保证得到所有 ANR 堆栈。常见排障见 [FAQ](docs/FAQ.md)。

## 编译与运行

### 环境要求

| 项目 | 要求与说明 |
| --- | --- |
| JDK | 项目配置 **JDK 17 toolchain**；先确保本机安装 JDK 17。打包需要含 `jpackage` / `jlink` 的完整 JDK |
| Gradle | 使用仓库 Wrapper，当前为 **8.14.4**，无需另外安装 Gradle |
| Kotlin / Compose | 当前为 **Kotlin 2.1.0 / Compose 1.7.3**，由 Gradle 解析依赖 |
| Android SDK / NDK | 普通桌面构建无需；设备工具需要 ADB；重建壳或生成真机测试包另有要求 |
| scrcpy | macOS / Windows 打包自动准备 **3.3.4**，下载后校验 SHA-256；首次需要可访问 GitHub |
| 图片引擎（macOS） | 需要 Xcode 命令行工具、Python 3；构建脚本校验并准备 pngquant、OxiPNG、MozJPEG、libwebp。找不到 CMake 时在项目 `build/` 内安装固定版本，首次需要 Python 的 pip 可用 |
| 网络 | 首次构建需要访问 Gradle、Google Maven、Maven Central 等依赖源 |

版本与平台配置以 [build.gradle.kts](build.gradle.kts) 和 [Gradle Wrapper](gradle/wrapper/gradle-wrapper.properties) 为准。

| 平台 | 当前情况 |
| --- | --- |
| macOS | 按 Apple Silicon / Intel 选择原生依赖和 scrcpy，随包准备图片引擎；使用系统文件对话框和钥匙串。图片功能已在 Apple Silicon 验证；设备功能历史记录见[验证报告](docs/macos-verification-2026-09-05.md) |
| Windows | 支持文件对话框、DPAPI 保护签名配置，随包准备 Windows x64 scrcpy；图片引擎需额外配置，MSI 打包另需 WiX Toolset 3.x |
| Linux | 配置了 DEB 目标，但未完整验证；scrcpy 与图片引擎需自行准备，签名密码的安全保存暂不支持；DEB 构建需 `dpkg` / `fakeroot` |

### 获取源码并启动

```sh
git clone https://github.com/dbmzn/ApkHarden.git WorkKit
cd WorkKit
```

macOS / Linux：

```sh
java -version
./gradlew --version
./gradlew run
```

Windows PowerShell：

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat run
```

IDE 中使用 Gradle 导入项目，并配置可用的 JDK 17 toolchain。首次运行会下载构建依赖；macOS / Windows 的 `run` 与打包任务会自动准备 scrcpy 资源。macOS 的运行、测试和打包还会准备图片引擎，请先满足上面的 Xcode 命令行工具与 Python 要求。也可以单独提前准备：

```sh
./gradlew prepareScrcpyResources
./gradlew prepareImageTools     # macOS：下载校验并构建图片引擎
```

Windows / Linux 使用图片功能前，需将 `APK_HARDEN_IMAGE_TOOLS` 设置为本机图片引擎目录，并准备对应运行依赖；配置方法见[图片引擎说明](docs/image-tools.md#构建与引擎)。打包后的 macOS 应用已包含这些引擎，最终使用者无需另装 Python、CMake 或 Xcode。

需要覆盖默认 scrcpy 查找结果时，macOS 开发运行可显式指定：

```sh
APK_HARDEN_SCRCPY="$PWD/build/app-resources/macos/scrcpy/scrcpy" ./gradlew run
```

也可以使用 `PATH` 中已安装的 scrcpy。Windows 对应目录为 `build/app-resources/windows/scrcpy/`。ADB 仍需可被应用发现；仅有打包资源中的 adb 文件不等于 SDK / PATH 已配置完成。

### 编译、测试与打包

以下命令在项目根目录运行；Windows 将 `./gradlew` 换为 `.\gradlew.bat`。

```sh
./gradlew compileKotlin        # 编译桌面源码
./gradlew test                 # 常规测试；设备及钥匙串测试需显式启用
./gradlew createDistributable  # 生成自带运行时的本机应用目录
```

按宿主系统生成安装包：

```sh
./gradlew packageDmg           # 在 macOS 上运行
./gradlew packageMsi           # 在 Windows 上运行
./gradlew packageDeb           # 在 Linux 上运行，尚未完整验证
```

| 产物 | 默认输出位置 |
| --- | --- |
| macOS 应用 | `build/compose/binaries/main/app/WorkKit.app` |
| Windows 应用目录 | `build/compose/binaries/main/app/WorkKit/` |
| 安装包 | `build/compose/binaries/main/dmg/`、`msi/` 或 `deb/` |
| 测试报告 | `build/reports/tests/test/index.html` |

安装包应在目标操作系统上构建，按当前构建环境生成本机架构产物。自带运行时的应用不要求最终使用者另装 JDK。打包脚本显式保留 `jdk.unsupported` 模块，供原生文件选择依赖使用。

本机部署：

```sh
./gradlew deployToDesktop
```

先退出已部署的应用。该任务会同步并替换部署目录：macOS 为 `~/Desktop/WorkKit.app`，Windows 为 `~/WorkKit`；开发过程中仅预览界面可使用 `run`。

## 命令行加固

先在桌面「签名百宝箱」保存配置，再复用当前用户的签名信息，避免把密码直接写入命令历史：

```sh
./gradlew harden --args="--input app.apk --output app-hardened.apk --savedProfile true"
```

Windows PowerShell：

```powershell
.\gradlew.bat harden --args="--input app.apk --output app-hardened.apk --savedProfile true"
```

CLI 也接受 `--keystore`、`--storePass`、`--alias`、`--keyPass` 显式参数。完整条件、输出说明和失败原因见[加固说明](docs/hardening.md)；参数解析以 [Cli.kt](src/main/kotlin/com/apkharden/packager/Cli.kt) 为准。

## 测试与兼容性

常规测试覆盖 APK 解析、重打包、加固与签名、码图生成识别、设备命令构造、文件传输规则、剪贴板编码，以及皮肤配置读写与文字对比度；也覆盖 JSON 精度与查询、文本比较、编码往返、时区与 Cron 边界。

图片测试包含 PNG / JPEG / WebP 六个转换方向、无损像素一致性、透明度、JPEG 背景色、ICC 色彩处理、方向校正后裁剪、缩放边界、取消与安全导出。Compose 桌面自动化验证裁剪框选择 / 移动 / 角点调整、精确输入、批量处理、编辑草稿恢复及旧结果导出拦截；系统文件选择器的人工操作不属于该自动化覆盖。具体证据与样本数据见[图片验证记录](docs/image-tools.md#验证记录2026-10-28)。

只验证通用工具或图片工具时可运行：

```sh
./gradlew test --tests 'com.apkharden.packager.core.developer.*'
./gradlew test --tests 'com.apkharden.packager.core.image.*'
```

**普通 `./gradlew test` 不等于真机验收。** 连接设备、真实录屏、无线连接、文件互传和 macOS 钥匙串等检查通过环境变量显式开启。启用条件与测试副作用见[开发验证说明](docs/development.md)。

- [macOS 功能验证记录（2026-09-05）](docs/macos-verification-2026-09-05.md)：包含已验证功能、复现方式及未覆盖项。
- [加固机制与兼容性](docs/hardening.md)：说明 Android 版本分支、输入限制和历史验证范围。

不同系统、厂商 ROM、CPU 架构、页面大小和业务启动逻辑仍需在目标环境回归。

## 项目结构

当前为 **Kotlin/JVM 单模块桌面工程**，Android 壳源码作为独立资源维护。

| 路径 | 职责 |
| --- | --- |
| `src/main/kotlin/com/apkharden/packager/Main.kt` | 桌面窗口与应用启动 |
| `src/main/kotlin/com/apkharden/packager/Cli.kt` | 命令行加固入口 |
| `src/main/kotlin/com/apkharden/packager/ui/` | 工具页面、公共控件、主题与皮肤设置 |
| `src/main/kotlin/com/apkharden/packager/core/` | APK 分析、Manifest 处理、DEX 加密、重打包与签名 |
| `src/main/kotlin/com/apkharden/packager/core/developer/`、`ui/developer/` | 通用工具的本地处理逻辑、界面和会话状态 |
| `src/main/kotlin/com/apkharden/packager/core/image/`、`ui/image/` | 图片压缩 / 转换 / 缩放 / 裁剪、批量队列、预览与导出 |
| `scripts/prepare-image-tools.py` | macOS 图片引擎构建、校验与许可证 / 源码打包 |
| `src/main/kotlin/com/apkharden/packager/device/` | ADB、无线连接、文件传输与剪贴板会话 |
| `src/main/kotlin/com/apkharden/packager/signing/` | 签名配置、Windows DPAPI 与 macOS 钥匙串保护 |
| `guard/`、`shell/`、`native/` | APK 内运行的守卫、加载器与 Native 解密实现 |
| `src/main/resources/shell.dex`、`src/main/resources/shell-libs/` | 预编译壳 DEX 和四种 ABI 的壳库 |
| `src/test/` | 单元测试、可选集成测试和测试包源码 |
| `scripts/` | 壳资源重建、测试 APK 构建及设备验证脚本 |
| `docs/` | FAQ、开发验证、加固说明与界面截图 |

```mermaid
flowchart LR
    UI[Compose Desktop 工具页面] --> APK[APK 分析与加固]
    UI --> ADB[设备服务]
    UI --> DATA[JSON / 文本 / 编码 / 时间]
    UI --> IMAGE[图片压缩 / 转换 / 缩放 / 裁剪]
    UI --> SETTINGS[外观与签名配置]
    CLI[命令行入口] --> APK
    APK --> LIB[ARSCLib / apksig]
    APK --> SHELL[预编译壳 DEX / Native 库]
    ADB --> DEVICE[ADB / scrcpy / Android 设备]
    IMAGE --> ENGINES[pngquant / OxiPNG / MozJPEG / libwebp]
    SETTINGS --> PREFS[Preferences / 系统密钥保护]
```

主要依赖：Compose Desktop、Kotlin Coroutines、Kotlinx Serialization、apksig、ARSCLib、ZXing、JCodec、JNA、LWJGL NFD、JsonPath、cron-utils 和 java-diff-utils。具体版本见 [Gradle 依赖配置](build.gradle.kts)。

图片处理使用独立本地引擎 pngquant、OxiPNG、MozJPEG 和 libwebp；源码版本与下载校验值见 [prepare-image-tools.py](scripts/prepare-image-tools.py)。

修改 `guard/`、`shell/` 或 `native/` 后需要重建壳资源，再测试桌面加固与设备启动，不能仅编译桌面源码。现有脚本依赖 Windows Android 构建工具，详见[开发验证说明](docs/development.md#重建壳资源)。

## 更名与升级

- 中文名称为 **百工**，英文名称为 **`WorkKit`**；macOS 正式应用为 `WorkKit.app`，Windows 程序使用 `WorkKit` 名称。
- 已有外观设置、签名配置与 macOS 钥匙串服务沿用原存储标识；源代码包名、Android 壳标识和 `APK_HARDEN_*` 环境变量保留兼容性。
- GitHub 仓库当前仍位于 `dbmzn/ApkHarden`，本页下载、源码与问题反馈链接继续指向该仓库。
- 手动升级时先退出旧版，将新应用安装到正式位置，确认可启动后移除旧的 `ApkHarden.app` 或旧程序目录，避免保留两个入口；已导出的文件无需改名。
- 历史验证文档保留当时的应用名与路径，不代表当前分发包仍使用旧名。

## 反馈与贡献

欢迎通过 [Issues](https://github.com/dbmzn/ApkHarden/issues) 提交问题或建议。请附上操作系统与架构、构建版本、复现步骤、错误信息；涉及设备时补充 Android 版本、ABI 和连接方式。优先使用可公开的最小测试 APK，勿附带正式 keystore、密码或业务敏感数据。

提交改动前请运行适用的测试；涉及界面时补充对应截图，涉及设备或壳时注明实际验证的环境和未覆盖范围。

## 致谢与许可

界面布局与初始绿青配色参考 [AndroidToolKit](https://github.com/LazyIonEs/AndroidToolKit)。百工保留原有的设备工具、APK 分析和加固流程，并提供独立皮肤库。

本项目使用 [Apache License 2.0](LICENSE)。第三方库及随包工具遵循各自许可证；scrcpy 的上游项目为 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)。macOS 图片工具目录保留各引擎的许可证、版权说明及对应源码归档，详见[图片引擎许可说明](docs/image-tools.md#构建与引擎)。
