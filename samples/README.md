# 端到端验证清单（需真机 / 模拟器）

壳的反射接管（`ProxyApplication`）只能在 Android 设备上验证，无法用 JVM 单测覆盖。
打包链路（加密 / 改 manifest / 重打包 / 签名）已由 JUnit + 真实 APK 冒烟测试验证通过
（真实样例：17 个 dex 多分包、`resources.arsc` 保持 STORED、apksig 校验通过）。

## 准备样例

用 **`src/test/resources/test.jks`** 这把 key 签名构建两个最小 App（这样运行时签名校验才会通过）：

1. `sample-plain` —— 默认 `Application`，一个显示 "Hello" 的 Activity。
2. `sample-customapp` —— 自定义 `class MyApp : Application()`，在 `onCreate` 打日志，一个 Activity。

把两个 APK 拷到本目录。keystore 信息：alias=`test`，storePass=`123456`，keyPass=`123456`。

> 用 test.jks 签名样例（Android Studio 配置 signingConfig，或命令行）：
> `apksigner sign --ks src/test/resources/test.jks --ks-key-alias test --ks-pass pass:123456 --key-pass pass:123456 sample-plain.apk`

## 加固

`./gradlew run` 打开 GUI，分别加固两个样例（keystore 选 test.jks，alias/密码同上），
得到 `*-hardened.apk`。

## 验证（在 API 26 / 30 / 34 各跑一遍）

| 检查项 | 命令 | 期望 | 26 | 30 | 34 |
|---|---|---|---|---|---|
| 正常启动（plain） | `adb install -r sample-plain-hardened.apk` 后启动 | UI 正常显示，logcat 无 `ClassNotFoundException` | ☐ | ☐ | ☐ |
| 接管原 App（customapp） | 启动 sample-customapp-hardened | `MyApp.onCreate` 日志出现 | ☐ | ☐ | ☐ |
| 防二次打包 | 用**别的 key** 重签名后安装启动 | 进程立即退出，Activity 不显示 | ☐ | ☐ | ☐ |
| 反调试 | `adb shell am start -D -n <pkg>/.MainActivity` | 检测到等待调试，进程退出 | ☐ | ☐ | ☐ |

查看日志：`adb logcat -s ApkHarden AndroidRuntime`

防二次打包构造篡改包：
```
apksigner sign --ks <other.jks> --ks-key-alias <a> --ks-pass pass:<p> --key-pass pass:<p> \
  --out tampered.apk sample-plain-hardened.apk
adb install -r tampered.apk
adb shell am start -n <pkg>/.MainActivity
```

## 已知局限（对齐 360 免费基础版）
- 纯 Java 层反调试 / 防篡改可被 Frida/Xposed hook 绕过。
- AES 密钥内嵌在 shell.dex 中（XOR 混淆），逆向可提取。
- 反射字段名（`LoadedApk.mClassLoader`、`ActivityThread.mInitialApplication` 等）在个别
  OEM ROM 上可能不同；若某机型启动失败，对照该 ROM 的 AOSP 源调整字段名。
- 不支持 AAB / split APK、`android:appComponentFactory`、SO/资源加固。
