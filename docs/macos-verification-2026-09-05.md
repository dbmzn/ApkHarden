# ApkHarden macOS 功能验证记录

日期：2026-09-05。环境：Apple Silicon Mac、JBR 17.0.14、Gradle 8.14.4；真机为 HONOR HEY4-W09，Android 16 / API 36、ARM64、4KB 页面。

使用独立测试包 `com.apkharden.verification` 和仓库公开测试签名。测试包包含两个 DEX、原 Application、AppComponentFactory、Provider、Activity、Deep Link 和通知权限。没有使用业务签名，也没有清除业务应用的数据。

## 结果

最终执行 `test createDistributable` 成功：56 项测试，53 项通过，3 项跳过，0 失败。
跳过项为 Windows DPAPI、Windows Explorer，以及另一个需要不同环境变量开启的独立录屏测试；本次新增的真机综合测试已经实际覆盖 5 秒录屏。

| 功能 | 结果与证据 |
| --- | --- |
| macOS 应用启动 | `.app` 内置 ARM64 Java 运行时，多次启动、退出和重启正常 |
| 文件选择 | macOS 原生打开/保存对话框正常打开，取消操作正常返回 |
| APK 加固与签名 | 后端测试和桌面界面均成功处理双 DEX 测试包，生成 APK 和 JSON 报告；加密后不保留业务 classes2.dex |
| 加固产物签名 | Android 官方 apksigner 校验 V1、V2、V3 全部有效，包名、版本、签名身份保持一致 |
| 加固产物运行 | 原始包及加固包在真机安装并冷启动成功，前台 Activity 和 PID 正常，无崩溃日志，界面显示 `APK HARDEN DEX2_OK` |
| APK 体检 | 界面显示签名、SDK、ABI、DEX、Native、权限、体积；测试包 92 分、0 阻塞、1 个导出组件提醒 |
| Manifest 查看与搜索 | Application、启动 Activity、Provider、权限和 Deep Link 解析正确；权限搜索过滤正确 |
| APK 文件浏览 | 文件、目录占比和分类正常；Native 筛选显示四个 ABI 的壳库 |
| APK 对比 | 包名和签名一致，双 DEX 加固为一个公开壳 DEX；新增、删除、修改清单正常 |
| 包体积分析 | 模块增量和具体文件排行正常，原始包与加固包的增量与文件记录对应 |
| 签名保存、读取与清除 | 界面使用测试 keystore 成功保存；重启后读回，主体、有效期、MD5/SHA-1/SHA-256 正确显示；验证完已清除测试配置 |
| 签名安全存储 | 钥匙串独立测试项加解密往返成功；不同实例能解密；随机 nonce 产生不同密文；篡改密文被拒绝 |
| APK 签名比较 | 两个有效 APK 在界面显示使用同一签名证书 |
| 设备发现与冷启动页面 | 桌面启动时无需 shell PATH 配置即可发现平板；界面冷启动等待 15 秒后验证通过 |
| 截图 | 真机 PNG 解码成功，桌面预览显示 3000×1872，保存到 Downloads 成功，界面复制图片返回“图片已复制到剪贴板” |
| 截图标注 | 箭头/矩形图像合成单元测试通过；自动化拖动未能可靠触发画布，实际鼠标拖动尚需人工确认 |
| 录屏 | 原生 screenrecord 在本机失败时自动转用 scrcpy；按 5 秒设置生成 MP4，文件头及 mdat/moov 结构校验通过（最后一次文件时长约 4.59 秒） |
| 实时镜像 | 最终桌面版本进入运行状态，解码到 1920×1200 视频，窗口按设备比例配置；切换 ADB 标签保持连接，离开 ADB 区后进程退出；鼠标/键盘操作需人工确认 |
| 日志过滤、性能、进程、页面栈 | 真机综合测试通过，ACTIVITY_OK 日志、内存信息、PID、Activity 可读取 |
| Intent / Deep Link | 真机启动测试 Deep Link 返回 Status: ok |
| 崩溃与 ANR 采集 | 实际生成 12 项内容的诊断 ZIP，包含目标包、内存、CPU、Activity、logcat、last ANR、DropBox 等 |
| 权限授予、清除数据 | 仅对独立测试包执行通知权限授予、pm clear；清除后加固包再次冷启动成功 |

## 本次修复

- Gradle 启动脚本改为 Unix 换行并增加执行权限。
- LWJGL 依赖按 macOS ARM64 / Intel 架构选择；JNA 更新到支持当前 Mac 的版本。
- macOS 文件对话框使用 AWT 进行主线程调度，消除 Cocoa 窗口线程崩溃。
- 签名密码在 macOS 使用 AES-GCM，加密密钥存储于当前用户钥匙串；加密失败前不改写已有配置。
- ADB 查找支持 Android Studio 默认 SDK、环境变量和 PATH；安装关闭增量模式，避免附带 idsig 触发增量安装等待。
- macOS 随包部署经 SHA-256 验证的 scrcpy，恢复 jpackage 丢失的可执行权限。
- 镜像去除 Windows 专用窗口查找依赖；适配 Retina 窗口尺寸；禁用 macOS 管道输出缓冲以正确检测首帧；启动失败清理进程。
- 录屏识别编码器失败并使用 scrcpy / 连续截图后备路径；scrcpy 视频最大边限制为 1920。
- 二进制 ADB 输出在独立线程读取，避免在等待超时前阻塞读取。
- `deployToDesktop` 在 macOS 正确部署为 `~/Desktop/ApkHarden.app`。

## 范围与未覆盖项

本次验证覆盖当前 Mac 和一台 Android 16 / ARM64 / 4KB 真机，不代表其他 Android 版本、厂商、16KB 页面设备或业务 APK 全部兼容。没有制造真实应用崩溃或 ANR；验证的是诊断采集流程和文件内容。

README 中原有“Manifest 导出风险标记”的描述没有对应的现有界面入口，因此不能列为已通过功能。鼠标拖动标注和镜像键鼠控制需进一步人工确认。

## 产物与复现

- 桌面应用：`~/Desktop/ApkHarden.app`
- JUnit 报告：`build/reports/tests/test/index.html`
- 加固与安装证据：`build/verification/verification-hardened.apk`、`verification-hardened-report.json`、`launch.txt`、`fixture-log.txt`
- GUI 产物：`build/verification/gui-hardened.apk`、`gui-hardened-report.json`
- 设备产物：`build/verification/device-screenshot.png`、`device-recording.mp4`、`diagnostics.zip`、`performance.txt`、`intent.txt`
- 测试构建脚本：`scripts/build-verification-fixture.py`
- 真实设备和钥匙串测试需要主动设置 README 中的环境变量；普通 `./gradlew test` 不会安装 APK、清数据或操作钥匙串。

依赖依据：[scrcpy 官方 v3.3.4 发行页](https://github.com/Genymobile/scrcpy/releases/tag/v3.3.4)、[JNA 官方变更记录](https://github.com/java-native-access/jna/blob/master/CHANGES.md)。

## 剪贴板格式回归修复

后续实际粘贴发现：旧版 Java AWT `imageFlavor` 在 macOS 生成 TIFF 数据，粘贴后文件却以 `.png` 命名，导致浏览器缩略图加载失败。之前只检查“复制成功”的状态提示，不能证明目标程序可以解码。

已改为直接以原生 PNG 类型输出编码后的 PNG 字节，新增三项回归测试（重复读取、其他平台兼容、JDK 原生 PNG 格式转换），全部通过。最终桌面应用实际截图并复制后，在本机 Chrome 页面粘贴验证：`image/png`，文件头 `89 50 4e 47 0d 0a 1a 0a`，3000×1872 图片预览成功。保留原有用户签名配置；构建副本已清理，仅保留桌面应用。
