# ApkHarden

一个自包含的 Android APK 基础加固工具（Compose Desktop GUI），对齐「360 免费加固」基础套餐：

- **DEX 整体加壳**：deflate + AES 加密原始 `classes*.dex`，运行时解密到 app 私有目录（按 `versionCode` 隔离），用 `DexClassLoader` 加载——ART 可生成并复用 oat，性能接近原包
- **防二次打包**：运行时校验签名 SHA-256，不符即退出
- **基础反调试**：检测调试器 / `TracerPid` / `FLAG_DEBUGGABLE`

minSdk 23（Android 6.0）+。打包全程纯 JVM 库（[apksig](https://android.googlesource.com/platform/tools/apksig/) 签名 + [ARSCLib](https://github.com/REAndroid/ARSCLib) 改 manifest），用户无需安装 Android SDK。

> **加载方式**：早期用 `InMemoryDexClassLoader`（仅 API 26+、无 AOT、运行慢），现改为「解密落盘 + `DexClassLoader`」——兼容下限降到 Android 6.0，且能用 AOT。代价：**首次启动**（或 app 升级后首启）需一次性解密 + `dex2oat`（大包数秒），之后冷启动复用 oat。明文 dex 持久驻留在 app 私有目录（沙箱级保护）。

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
界面里选输入 APK、输出路径、keystore（.jks）+ 别名 + 密码，点「开始加固」。

## 开发

```
./gradlew test           # 运行单元 + 集成测试
$env:ANDROID_HOME='C:\AndroidSdk'; pwsh scripts/build-shell.ps1   # 改了 shell/ 后重建 shell.dex
```

壳的反射接管需在真机/模拟器验证，见 [samples/README.md](samples/README.md)。

## 局限

纯 Java 层保护可被 Frida/Xposed 绕过，密钥内嵌可逆向提取 —— 与「免费基础版」同档；
更强需上 Native（ptrace 自附加、抽取壳/VMP），不在本工具范围。设计与计划见 `docs/superpowers/`。
