# 开发与验证

[返回 README](../README.md) · [加固说明](hardening.md)

## 常规构建

```sh
./gradlew compileKotlin
./gradlew test
./gradlew createDistributable
```

Windows 使用 `.\gradlew.bat`。JDK、平台和安装包要求见 [README](../README.md#编译与运行)。测试报告输出到 `build/reports/tests/test/index.html`。

常规测试中有平台条件及显式启用条件，跳过项需要结合实际环境解释。不要把一次常规测试通过表述成所有设备功能已经验收。

## 可选集成验证

以下开关由测试源码读取。未配置时对应检查不会主动运行：

| 环境变量 | 值 | 实际行为 |
| --- | --- | --- |
| `APK_HARDEN_DEVICE_TEST` | 设备序列号 | 使用 `com.apkharden.verification` 测试包进行加固、安装、冷启动、截图、录屏与诊断，包含授予测试权限及清除该测试包数据 |
| `APK_HARDEN_KEYCHAIN_TEST` | `true` | macOS 使用独立随机测试项验证钥匙串加解密，结束后删除测试项 |
| `APK_HARDEN_TEST_DEVICE` | 设备序列号 | 独立真机录屏检查；与上面的综合测试开关不同 |
| `APK_HARDEN_EXPECTED_RECORDING_MODE` | `DEVICE` / `SCRCPY` / `SCREENSHOT_COMPATIBILITY` | 可选：断言独立录屏测试实际使用的模式 |
| `APK_HARDEN_CLIPBOARD_TEST` | 设备序列号 | 读取现有剪贴板文字，需事先在手机复制测试文字；不改写手机或电脑剪贴板 |
| `APK_HARDEN_FILE_TEST` | 设备序列号 | 在手机下载目录和电脑临时目录中执行文件往返，验证字节与同名保护，最后清理本次随机测试目录 |
| `APK_HARDEN_WIFI_TEST` | USB 设备序列号 | 启用旧版无线调试、连接、截图和读取测试剪贴板，最后尝试恢复 USB 模式；会改变设备连接状态 |
| `APK_HARDEN_SCRCPY` | scrcpy 可执行文件路径 | 指定应用 / 测试使用的 scrcpy，不是单独的测试开关 |

选择专用测试设备，逐项开启需要验证的检查。无线连接、文件传输和清数据检查有真实副作用，避免在不明确的设备上批量启用全部开关。

### macOS 综合验证示例

先安装 Python 3、JDK 17、Android SDK **Platform 36** 与 **Build-Tools 36.0.0**。当前测试包生成脚本明确使用这些 SDK 路径，不会自动切换其他版本。

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew prepareScrcpyResources
python3 scripts/build-verification-fixture.py
adb devices -l
```

将示例中的 `TEST_DEVICE_SERIAL` 替换为选定测试设备的序列号：

```sh
APK_HARDEN_DEVICE_TEST="TEST_DEVICE_SERIAL" \
APK_HARDEN_KEYCHAIN_TEST=true \
APK_HARDEN_SCRCPY="$PWD/build/app-resources/macos/scrcpy/scrcpy" \
./gradlew test createDistributable
```

测试包源码位于 `src/test/fixtures/android/`，使用仓库的**公开测试专用** keystore `src/test/resources/test.jks`。该签名不得用作正式发布证书。测试产物位于 `build/verification/`。

独立录屏或需要现场重新执行的验证可以使用 `--rerun-tasks`，避免将历史测试缓存当成当前设备结果。完整历史记录见 [macOS 验证报告](macos-verification-2026-09-05.md)。

## 重建壳资源

普通桌面构建会使用已有的 `src/main/resources/shell.dex` 和 `src/main/resources/shell-libs/`。修改以下任意部分后，需要重建资源：

- `guard/`：签名校验、反调试和 Guard Provider。
- `shell/`：Application、AppComponentFactory 和 DEX 加载链。
- `native/`：Native 密钥槽、解密与解压。

现有 [build-shell.ps1](../scripts/build-shell.ps1) 调用 `d8.bat`、`cmake.exe` 和 `ninja.exe`，应在 **Windows** 环境运行；仅安装跨平台 PowerShell 不会使这些工具变成 macOS / Linux 版本。

需要完整 JDK、Android SDK Platform、Build-Tools、NDK、CMake / Ninja。脚本从 SDK 安装目录选择可用工具，并构建 arm64-v8a、armeabi-v7a、x86_64、x86 四种 ABI。

```powershell
$env:ANDROID_HOME = 'C:\AndroidSdk'
pwsh scripts/build-shell.ps1
.\gradlew.bat test
```

生成的 DEX 和 SO 会写回资源目录，应与对应源代码改动一起检查。之后继续构建桌面应用，并在目标 Android 环境验证安装、冷启动、组件初始化和 Native 加载。

## 单独验证安装与启动

仓库提供 [verify-device-launch.ps1](../scripts/verify-device-launch.ps1)，需要 PowerShell 与可执行的 ADB：

```powershell
pwsh scripts/verify-device-launch.ps1 `
  -Apk app-hardened.apk `
  -PackageName com.example.app `
  -Serial TEST_DEVICE_SERIAL
```

脚本会覆盖安装 APK、清空设备 logcat、强制停止并重新启动指定包，再检查进程、前台 Activity 和崩溃信息。`-SkipInstall` 可跳过安装；`-WaitSeconds` 可调整等待时间。请在明确的测试设备和测试包上使用。

## 界面与截图维护

皮肤定义位于 `ui/theme/SkinPalettes.kt`，默认配置和持久化在 `AppearanceStore.kt`，选择面板在 `AppearanceDialog.kt`。

修改主题时检查浅色、深色、自动记忆、默认回退及页面状态保留；不要只验证空白导入页。文档截图统一放在 [screenshots](screenshots)，采集说明见[截图说明](screenshots/README.md)。
