# ApkHarden 设计文档（一代壳 / 基础加固）

- 日期：2026-06-15
- 目标：实现一个 Android APK 加固工具，对齐「360 免费加固」基础套餐水准
- 范围：**1) DEX 整体加壳 + 2) 防二次打包 + 3) 基础反调试**
- 形态：**Compose Desktop GUI** 桌面打包器，语言统一 **Kotlin**
- 目标 APK：**minSdk 26+**
- 实现风格：**纯 Kotlin/Java，完全自包含**（不要求用户安装 Android SDK）

---

## 1. 总体方案

采用 **一代壳（DEX 整体加密 + 运行时内存加载）**，即 360 免费基础版的核心机制：
把原 APK 的 `classes*.dex` 加密藏进 assets，换上一段「壳」`classes.dex`，
App 启动时壳先做校验，再解密原始 dex 用 `InMemoryDexClassLoader` 在内存加载，
反射替换系统 ClassLoader 并移交给原始 Application。最后重打包并重新签名。

未采用的方案：
- **B（apktool 反编译/回编译注入 smali）**：依赖重、回编译易失败、慢 —— 不符合自包含/简单定位。
- **C（二代抽取壳 / VMP）**：需改 dex 字节码、运行时回填方法体，复杂度远超「基础」范围。

---

## 2. 架构

```
ApkHarden/
├── packager/   ← 电脑上运行的 Compose Desktop GUI（打包器，Kotlin/JVM）
└── shell/      ← Android 模块，编译产出 shell.dex（壳运行时，被内嵌进 packager 资源）
```

- **shell**：编译期一次性产出通用 `shell.dex`，作为资源内嵌进 packager。
  每次加固的差异数据（原始 Application 类名、期望签名 hash、dex 数量）由 packager
  写进 manifest 的 `meta-data`，壳运行时读取；AES 密钥内嵌在壳代码里（轻量混淆）。
- **packager**：拆 APK → 加密 dex → 换壳 → 改 manifest → 重打包 → 签名。

**离线打包数据流**：
`原APK → [拆/读manifest] → [加密dex + 换shell.dex + 改manifest + 写meta-data] → [zip / 对齐 / 签名] → 加固APK`

**运行时数据流**：
`壳启动 → 反调试 + 防篡改校验 → 解密原dex → InMemoryDexClassLoader 内存加载 → 反射换ClassLoader → 移交原Application`

---

## 3. shell 运行时（最关键部分）

`shell.dex` 是**预编译的通用壳**，只用 Android 框架 API（不依赖 androidx），保证单 dex。

### 3.1 `ProxyApplication` 接管流程（minSdk 26+）

`attachBaseContext(base)`（最先执行）：
1. 跑**反调试 + 防篡改**校验，任一失败 → `Process.killProcess` / 闪退。
2. 从 `assets` 读出加密的原始 dex 们，AES 解密成 `ByteBuffer[]`。
3. `InMemoryDexClassLoader(buffers, parentClassLoader)` 内存加载，**不落地磁盘**。
4. 反射拿到本包 `LoadedApk`（`ContextImpl.mPackageInfo`），把其 `mClassLoader`
   替换为新 ClassLoader —— 这样框架后续实例化 Activity/Service/ContentProvider 都能解析原始类。
5. `super.attachBaseContext(base)`。

`onCreate()`：
6. 读 meta-data 的原始 Application 类名；若原 App 无自定义 Application，跳过 7–9。
7. 用新 ClassLoader 加载并实例化原始 Application。
8. 反射调用其 `attachBaseContext(base)`，再把 `ActivityThread.mInitialApplication`、
   `mAllApplications`、`LoadedApk.mApplication` 都替换为该真实实例
   （保证 `getApplication()` 拿到原 App）。
9. 调用真实 App 的 `onCreate()`。

> ContentProvider 在 `attachBaseContext` 之后、`onCreate` 之前被框架创建；第 4 步已换好
> ClassLoader，故 Provider 能正常解析原始类。

### 3.2 反调试（纯 Kotlin，启动时跑一遍）
- `Debug.isDebuggerConnected()` / `Debug.waitingForDebugger()`
- 读 `/proc/self/status` 检查 `TracerPid != 0`（被 ptrace 附加）
- 检查 `ApplicationInfo.FLAG_DEBUGGABLE` 是否被打开

### 3.3 防二次打包（纯 Kotlin）
- 运行时取自身签名证书（API 28+ 用 `GET_SIGNING_CERTIFICATES`），算 SHA-256。
- 与 meta-data 里 packager 写入的**期望 hash**（用户 keystore 证书的 hash）比对，不符 → 退出。

### 3.4 已知局限（对齐「免费基础版」）
- 纯 Java 层检查可被 Frida/Xposed hook 绕过。
- AES 密钥内嵌 dex 中，逆向可提取。
- 更强保护需 Native（ptrace 自附加、native 校验），超出本次范围。

---

## 4. packager 打包器流水线

### 4.1 库选型（纯 JVM，零外部依赖）
| 用途 | 选型 |
| --- | --- |
| 改二进制 `AndroidManifest.xml` | `com.reandroid:ARSCLib` |
| 签名（V1/V2/V3，自带 4 字节对齐） | `com.android.tools.build:apksig` |
| zip 读写 | `java.util.zip`（必要时 `commons-compress`） |
| 加密 | JDK `javax.crypto`（AES） |
| GUI | Compose Multiplatform Desktop |

### 4.2 步骤
1. **读 APK**：当 zip 打开，枚举条目。
2. **解析 manifest**：ARSCLib 读 `<application android:name>`（`.Xxx` 相对名→补全包名）；无自定义 Application 则标记。
3. **加密 dex**：取所有 `classes*.dex` 逐个 AES 加密 → 写成 assets（如 `assets/d/0`,`1`…）；从 keystore 证书算**期望签名 SHA-256**。
4. **改 manifest**：`application:name` → `ProxyApplication` 全名；加 `meta-data`（原始 App 类名、期望签名 hash、dex 数量）；回编码二进制。
5. **组装新 zip**：复制原条目，但 ①去掉 `classes*.dex` ②替换 manifest ③丢弃旧 `META-INF/` 签名；写入内嵌 `shell.dex` 作为新 `classes.dex`；写入加密 dex 资产。保留其他条目原压缩方式。
6. **签名**：apksig 用界面所选 keystore 出 V1+V2+V3 → 输出加固 APK。

### 4.3 GUI
- 选择输入 APK、输出路径
- 选择 keystore（.jks/.keystore）+ 别名 + 密码
- 「开始加固」按钮 + 日志输出区 + 进度

### 4.4 边界与错误处理
- **无自定义 Application**：壳照常换 ClassLoader 加载原 dex，只跳过「接管原 App」。
- **多 dex**：`classes2.dex…` 全部加密、全部内存加载。
- **相对类名** `.MyApp`/`MyApp`：用包名补全。
- 输入校验：非法 APK、缺 manifest、keystore 密码错、API26+ 限制等都给明确报错。
- **明确不支持**（超范围）：AAB/split APK、`android:appComponentFactory`、SO/资源加固。

---

## 5. 工程结构

```
ApkHarden/
├── settings.gradle.kts
├── build.gradle.kts
├── shell/                                  # Android 模块 → shell.dex
│   ├── build.gradle.kts
│   └── src/main/java/com/apkharden/shell/
│       ├── ProxyApplication.kt
│       ├── DexDecryptor.kt
│       ├── AntiDebug.kt
│       └── AntiTamper.kt
├── packager/                               # Compose Desktop（Kotlin/JVM）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── kotlin/com/apkharden/packager/
│       │   ├── Main.kt                      # Compose 入口
│       │   ├── ui/HardenScreen.kt
│       │   └── core/
│       │       ├── ApkReader.kt
│       │       ├── ManifestPatcher.kt       # ARSCLib
│       │       ├── DexEncryptor.kt
│       │       ├── ApkRepackager.kt
│       │       ├── ApkSignerWrapper.kt      # apksig
│       │       ├── KeystoreUtil.kt
│       │       └── HardenPipeline.kt        # 编排
│       └── resources/shell.dex             # 预编译内嵌
├── scripts/build-shell.(ps1|sh)            # 编译 shell → d8 → 拷到 packager 资源
└── samples/                                # 测试用 demo app（含/不含自定义 Application）
```

### shell.dex 构建
shell 模块用 Android Gradle Plugin 编译，从产物提取 `classes.dex`，或对编译输出跑 `d8`
生成单 `shell.dex`，由 `scripts/build-shell` 拷到 `packager/src/main/resources/shell.dex`。
壳仅用框架 API，确保单 dex。

---

## 6. 测试方案

**单元测试（JVM，packager）**
- `DexEncryptor`：加密/解密往返一致。
- `ManifestPatcher`：设置 application name、加 meta-data 后能读回。
- `KeystoreUtil`：从 keystore 算证书 SHA-256 正确。
- `ApkRepackager`：输出 zip 含 shell.dex、加密资产、改后 manifest，且不含旧签名。

**集成测试（JVM）**
- 对样例 debug APK 跑完整流水线 → 校验：`classes.dex == shell.dex`、manifest 入口为
  `ProxyApplication`、meta-data 齐全、加密 dex 存在、`apksig verify` 通过。

**端到端（模拟器/真机，API 26/30/34）—— 手动 + 可选 instrumentation**
- 加固 `samples/` 两个 demo（含自定义 Application / 不含），安装运行 → 原 Activity 正常显示。
- 篡改：用不同 key 重签名 → App 启动即退出（防二次打包生效）。
- 反调试：附加调试器 → App 退出。

**风险**：反射字段名（`LoadedApk.mClassLoader`、`ActivityThread.mInitialApplication` 等）
随 Android 版本/OEM ROM 可能差异 —— 在 API 26/30/34 上分别验证。
