# ApkHarden 生产级协作式加固平台设计

**日期：** 2026-07-13
**状态：** 已完成方案讨论，待书面审阅
**目标：** 将现有基于整包 DEX 抽取和隐藏 API 类加载的实验性 APK 加固工具，升级为适用于国内应用市场、企业内部发布和官网直发单体 APK 的生产级构建期保护与发布审计平台。

## 1. 背景

现有实现会移除原始 `classes*.dex`，在启动时解密 DEX，并通过反射修改 Android `PathClassLoader`、`DexPathList`、`ActivityThread` 和 `LoadedApk`。审核和设备验证已经确认：

- Android 14+ 会拒绝加载可写动态 DEX；
- Android 16 / API 36 / 16KB 环境中，加固 APK可安装但启动后进程立即退出；
- 当前重打包器会把原本 16KB 对齐的未压缩 native 库重新按 4KB 对齐；
- 隐藏 API 绕过在 API 36 上已被系统拒绝；
- 当前流程不验证线上旧包、候选包和正式 keystore 的证书一致性；
- Application、ContentProvider 和 AppComponentFactory 的代理方式存在业务兼容风险。

因此正式模式不再继续使用整包 DEX 抽取架构。

## 2. 已确认的产品边界

### 2.1 支持范围

- 国内应用市场原签名分发；
- 企业内部 APK分发；
- 官网直接下载 APK；
- Android 6.0 / API 23 到 Android 16 / API 36；
- 4KB 和 16KB 页大小设备；
- 完整、可独立安装的单体 APK；
- 业务方拥有源码、正式签名和当前线上旧 APK；
- 业务工程可以接入 Gradle 插件，但不要求修改业务代码；
- R8 开启和关闭均为正式支持路径；
- 默认处理 Android 工程实际生成的全部 application variants。

### 2.2 不支持范围

- Google Play App Signing；
- 渠道重新签名；
- AAB、APKS 和 split APK；
- 未知第三方 APK的强制加固；
- 签名轮换和多 signer；
- 通过隐藏 API 替换 Android ClassLoader；
- 绝对不可破解、不可 Hook、不可内存 dump 的安全承诺。

### 2.3 发布原则

生产平台采用严格阻断策略。无法证明可覆盖、可安装、可启动或满足支持范围时，不允许输出“可上线”结论。

## 3. 总体方案

正式架构由构建期保护和发布期审计组成：

```text
业务源码
  -> ApkHarden Gradle 插件
  -> ASM 构建期插桩
  -> 可选 R8
  -> D8 / AGP 标准 APK打包
  -> ApkHarden 桌面发布平台
  -> 线上覆盖、签名、ABI、16KB 和设备测试
  -> 正式发布 APK与审核报告
```

正式模式不再：

- 抽取原始 DEX；
- 动态解密和加载 DEX；
- 使用 ProxyApplication 替换业务 Application；
- 修改 `ActivityThread`、`LoadedApk`、`DexPathList`；
- 删除 `android:appComponentFactory`；
- 在桌面工具中重新打包 Manifest、DEX、资源或 native 库。

## 4. 项目模块

项目调整为多模块结构：

```text
ApkHarden/
├─ harden-gradle-plugin/
├─ harden-runtime/
├─ harden-release-core/
├─ harden-desktop/
├─ harden-test-apps/
└─ harden-device-tests/
```

### 4.1 harden-gradle-plugin

职责：

- 发现全部 Android application variants；
- 为每个 variant 注册独立插桩和元数据任务；
- 分析最终合并 Manifest；
- 注入 runtime 初始化；
- 执行字符串加密；
- 生成变体配置和构建报告；
- 验证插桩后的 JVM 字节码；
- 保持 R8 和 ABI 配置不变。

仅使用 AGP 公开的 Android Components、Variant、Artifact 和 ASM Instrumentation API，不访问 AGP internal API，不依赖 AGP 内部任务名称。

### 4.2 harden-runtime

纯 Java/Kotlin Android runtime，不携带 native 库，职责：

- 正式签名证书校验；
- `FLAG_DEBUGGABLE` 校验；
- debugger 和 `TracerPid` 检测；
- 运行时 key 派生；
- AES-GCM 字符串解密和缓存；
- 记录不包含用户数据的私有诊断错误码。

runtime 禁止引用 Android 隐藏 API。

### 4.3 harden-release-core

纯 JVM 发布核心，职责：

- 解析 APK Manifest、签名、ABI、ZIP 和 ELF；
- 对比线上旧包、候选包和正式 keystore；
- 检查 versionCode、debuggable、testOnly、uses-feature；
- 检查单 APK完整性；
- 检查 16KB ZIP 和 ELF 对齐；
- 使用 apksig 签名和验证；
- 生成 JSON/HTML 发布报告。

### 4.4 harden-desktop

Compose Desktop 界面，职责：

- 选择当前线上 APK；
- 选择当前候选 APK和 metadata；
- 选择正式 keystore；
- 展示阻断项、警告项和人工确认项；
- 调用设备验证；
- 只在达到 `RELEASE_QUALIFIED` 时导出正式产物。

### 4.5 harden-test-apps

包含真实 Android 测试工程，覆盖 Application、Provider、多进程、AppComponentFactory、Compose、Java/Kotlin、反射、序列化、native 库和升级迁移。

### 4.6 harden-device-tests

通过 adb 和模拟器/真机执行安装、覆盖升级、启动、反调试、错误签名和多进程测试。

## 5. Variant 设计

插件默认遍历全部 application variants：

```kotlin
androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    // 为实际存在的 variant 注册任务
}
```

不得硬编码 `debug`、`release` 或任何 flavor 名称。

正式支持包括但不限于：

- `product_32`；
- `product_64`；
- `product_all`；
- debug、develop、release；
- 多维度 flavor 组合；
- 任意业务自定义 build type。

每个 variant 独立生成：

```text
build/outputs/apk-harden/<variant>/candidate.apk
build/outputs/apk-harden/<variant>/harden-metadata.json
build/outputs/apk-harden/<variant>/harden-build-report.html
```

可配置排除部分 variants，但默认不限制：

```kotlin
apkHarden {
    excludedVariants.set(emptySet())
}
```

Gradle 插件允许构建和加固 debuggable variant；只有进入桌面正式发布流程时，`debuggable=true` 才作为发布阻断项。

## 6. R8 策略

R8 完全可选。插件不得修改业务工程的：

- `minifyEnabled`；
- `shrinkResources`；
- ProGuard/R8 业务配置；
- mapping 输出策略。

### 6.1 R8 关闭

```text
Java/Kotlin class
  -> ApkHarden ASM 插桩
  -> D8
  -> APK
```

字符串加密、签名防篡改、反调试和 metadata 必须全部正常工作。

### 6.2 R8 开启

```text
Java/Kotlin class
  -> ApkHarden ASM 插桩
  -> R8
  -> D8
  -> APK
```

插件只添加 runtime 所需的最小 keep 规则。

默认策略：

```kotlin
apkHarden {
    r8Policy.set(R8Policy.AUTO)
}
```

`AUTO` 只检测并适配当前状态，不强制开启 R8。

## 7. Application 初始化注入

### 7.1 自定义 Application

插件读取最终合并 Manifest，定位真实 Application class。

如果已经重写 `attachBaseContext()`，在每个正常 `RETURN` 前注入：

```kotlin
HardenRuntime.install(this)
```

如果没有该方法，生成：

```kotlin
override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    HardenRuntime.install(this)
}
```

`super` 必须指向 class 的真实父类。

### 7.2 默认 Application

如果 Manifest 没有自定义 Application，插件为该 variant 生成普通 Application 子类，并通过 variant 专属 Manifest overlay 设置 `android:name`。

### 7.3 约束

- 不替换已有 Application 对象；
- 不改变 Application 类型；
- 不改变 ContentProvider 标准初始化顺序；
- 每个应用进程都通过自己的 Application 执行 runtime 初始化；
- 插桩和 runtime 均保证每进程只初始化一次；
- 不删除或替换 AppComponentFactory。

## 8. 签名防篡改和反调试

### 8.1 签名校验

每个 variant 生成配置：

```text
schemaVersion
variantName
applicationId
versionCode
certificateSha256
buildId
runtimeVersion
```

正式模式要求：

```text
线上旧 APK证书
= 候选 APK证书（若已签名）
= 正式 keystore 证书
= 插件配置 certificateSha256
```

Android 9+ 使用当前 APK signer；Android 6～8 使用旧签名 API。正式模式只支持单 signer，不使用历史 signer 放宽判断。

### 8.2 key 派生

字符串 runtime key 由以下内容派生：

```text
构建期随机 key 分片
+ 当前运行证书 SHA-256
+ applicationId
+ buildId
```

重新签名会同时触发签名校验失败和字符串解密 key 不匹配。

### 8.3 反调试

正式 runtime 固定检查：

- `FLAG_DEBUGGABLE`；
- `Debug.isDebuggerConnected()`；
- `Debug.waitingForDebugger()`；
- `/proc/self/status` 中的 `TracerPid`。

检测失败时写入应用私有错误码、清除 key 引用并结束当前进程。错误记录不得包含用户信息、设备标识和业务参数。

## 9. 字符串加密

### 9.1 范围

默认处理业务工程 class。`protectedPackages` 为空时使用 applicationId 包前缀。

默认排除：

- `R`、`R$*`、BuildConfig；
- DataBinding 和其他生成类；
- harden runtime 和插件生成类；
- Application 构造器和静态初始化器；
- AppComponentFactory；
- 测试框架生成类；
- 用户配置的 class/string 白名单。

### 9.2 方法字符串

将方法体中的：

```text
LDC "plain-text"
```

转换为：

```text
LDC <stringId>
INVOKESTATIC HardenStrings.decode(I)Ljava/lang/String;
```

### 9.3 常量字段

对可安全转换的 Java `static final String` 和 Kotlin `const val`：

- 清除 ConstantValue；
- 在 `<clinit>` 中动态赋值；
- 同时处理已经内联到参与插桩 class 中的字符串；
- 在报告中列出公开常量语义变化；
- 无法证明安全时自动排除而不是强制改写。

### 9.4 框架字符串

默认不处理注解值、Manifest、Retrofit/Room/序列化/JNI/反射/ServiceLoader/资源名称等可能由框架静态读取的字符串。插件对已知调用点进行分析并报告自动排除原因。

### 9.5 密文表

密文表以生成 class 的形式进入标准 DEX，不依赖 Context、AssetManager 或运行时外置文件。每条记录使用 AES-GCM，并包含独立 IV 和认证 tag。

运行时按需解密并使用 `AtomicReferenceArray<String>` 缓存，不在启动时解密全部字符串，不持久化明文。

## 10. 发布工作流

正式发布必须提供：

```text
当前线上旧 APK
+ 当前 variant 候选 APK
+ harden-metadata.json
+ 正式 keystore
```

每个 variant 独立校验，不允许用另一 ABI/渠道 variant 的线上包作为基准。

流程：

```text
读取线上旧包
  -> 读取候选包和 metadata
  -> 读取正式 keystore
  -> 身份与升级条件检查
  -> Manifest / ABI / native / 16KB 检查
  -> 插件保护配置检查
  -> 正式签名或验证已有签名
  -> 签名后复检
  -> 覆盖安装与启动测试
  -> 发布报告和最终 APK
```

桌面发布平台不得重新写入候选 APK中的 Manifest、DEX、resources.arsc、assets 或 `.so`。未签名候选 APK只允许通过 apksig 添加正式签名。

## 11. 正式发布阻断规则

以下任一情况必须阻断：

- 包名不一致；
- 线上旧包、候选包、metadata 和 keystore 证书不一致；
- 多 signer 或签名轮换；
- `candidate.versionCode <= online.versionCode`；
- `debuggable=true`；
- `testOnly=true`；
- `minSdk < 23`；
- split/base/ABI/density/language/dynamic-feature APK；
- runtime 或 metadata 缺失/版本不一致；
- protected string 扫描失败；
- 字节码验证失败；
- 16KB ZIP 或 ELF 校验失败；
- 签名验证失败；
- 最低设备矩阵未通过；
- 出现 crash、ANR、VerifyError、ClassNotFoundException、隐藏 API拒绝或 writable dex 日志。

以下变化默认阻断并允许有审计记录的人工确认：

- 提高 minSdk；
- 减少线上旧包支持的 ABI；
- 新增 required hardware feature；
- APK体积超过阈值。

## 12. ABI 和 16KB

插件不修改业务 ABI 配置。

发布核心比较线上旧包和候选包的：

- ABI 集合；
- `.so` 列表；
- `.so` SHA-256；
- ZIP 压缩方式；
- ZIP data offset；
- ELF class、machine 和 LOAD segment alignment；
- `extractNativeLibs`。

未压缩 `.so` 必须满足 16KB ZIP 对齐。所有 native 库必须通过 ELF 16KB 兼容检查。

平台不得尝试重新 zipalign 不合规候选包；必须返回业务 Gradle 构建修复。

`product_32`、`product_64` 和 `product_all` 的实际 ABI 从 variant 和 APK读取，不根据 variant 名称猜测。

## 13. 测试工程

真实 Android 测试工程覆盖：

- 无自定义 Application；
- 多种自定义 Application；
- 多进程；
- 自定义 AppComponentFactory；
- ContentProvider、AndroidX Startup、WorkManager；
- Compose；
- Java/Kotlin/suspend/lambda/const val；
- Gson、Moshi、Kotlin Serialization、Retrofit、Room；
- Class.forName、ServiceLoader、JNI 和资源名称；
- 32 位、64 位和全 ABI native 库；
- 覆盖安装与数据迁移。

同一测试工程必须运行 R8 关闭和 R8 开启两套配置。R8 关闭为一等正式支持路径。

## 14. AGP 验证矩阵

初版认证 AGP 8.5.1 至 9.2 的代表性和边界版本。每个版本验证：

- 插件应用；
- 全 variants 发现；
- ASM 插桩；
- Manifest overlay；
- R8 开启/关闭；
- clean/incremental/parallel build；
- configuration cache；
- metadata 和 APK输出。

若某 AGP 小版本无法通过公开 API兼容测试，则从正式支持清单中移除并明确阻断，而不是使用 internal API兜底。

## 15. Android 设备矩阵

runtime/plugin 发布前完整认证：API 23 至 API 36，并包含 API 36 4KB 和 16KB。

每个业务 APK正式发布最低矩阵：

- API 23 / 4KB；
- API 26 / 4KB；
- API 28 / 4KB；
- API 34 / 4KB；
- API 36 / 4KB；
- API 36 / 16KB。

包含 native 库时增加 32 位、64 位和 16KB arm64 真机或设备云验证。

runtime 大版本还需覆盖华为/荣耀、小米/Redmi、OPPO/OnePlus、vivo/iQOO、三星和 Pixel/AOSP 代表设备。

## 16. 设备测试内容

每台设备执行：

- 干净安装和启动；
- 线上旧包到新包的覆盖安装；
- SharedPreferences、文件、SQLite/Room 数据保留；
- 首次冷启动、强杀冷启动、连续十次启动、设备重启后启动；
- 主进程和独立进程组件启动；
- 错误签名包运行阻断；
- `am start -D` 和调试器附加阻断；
- crash、ANR、隐藏 API和 classloading 日志扫描。

## 17. 性能门槛

`HardenRuntime.install()` 目标：

- P50 不高于 15ms；
- P95 不高于 30ms。

APK体积默认增量不得超过：

```text
max(原 APK 的 3%, 2MB)
```

runtime 基础内存目标不高于 2MB。字符串按需解密，不在启动时解密整表。

性能阈值必须在代表性低端和高端设备上测量。若业务明确接受超过阈值，必须在发布报告中记录人工确认。

## 18. 发布状态

```text
BUILT
-> STATIC_VERIFIED
-> DEVICE_VERIFIED
-> RELEASE_QUALIFIED
```

只有以下全部通过才能达到 `RELEASE_QUALIFIED`：

- 构建和字节码验证；
- R8 状态保持业务配置；
- 包名、证书和 versionCode；
- debuggable/testOnly；
- 单 APK和 ABI；
- 16KB ZIP/ELF；
- V1/V2/V3 签名；
- 覆盖安装；
- 最低设备矩阵；
- crash/ANR/隐藏 API检查；
- 签名防篡改和反调试；
- protected string 扫描；
- 完整发布报告。

任何必要结果缺失时只能输出 `NOT_RELEASE_QUALIFIED`。

## 19. 正式产物

```text
release/
├─ app-<variant>-v<versionCode>-hardened.apk
├─ release-report.html
├─ release-report.json
├─ checksums.sha256
├─ signer-certificate.pem
└─ device-test-results.json
```

报告必须记录线上旧包和新包摘要、variant、包名、版本、证书、ABI、SDK、R8 状态、保护能力、16KB、签名方案、设备结果和所有人工确认事项。

## 20. 迁移策略

现有整包 DEX 加固功能保留为实验模式，并明确标记：

```text
实验模式，不允许用于正式发布
```

生产模式只接受 Gradle 插件生成且通过 metadata 验证的候选 APK。

迁移顺序：

1. 拆分 release-core；
2. 建立真实测试 App 和设备基线；
3. 实现 runtime 和 Application 注入；
4. 实现签名/发布预检；
5. 实现字符串加密；
6. 实现 16KB/ELF 检查；
7. 实现桌面生产发布流程；
8. 完成完整设备和 AGP 认证；
9. 将旧整包模式降级为实验入口。

## 21. 成功标准

本项目的“生产可上线”定义为：

- 在已声明支持范围内不依赖 Android 或 AGP 隐藏 API；
- 不改变标准 Application、Provider、AppComponentFactory 和 ClassLoader 生命周期；
- 所有实际 variants 和 R8 开关均可验证；
- 线上覆盖条件可通过旧包、新包和 keystore 自动证明；
- 4KB/16KB、ABI 和签名结果可重复验证；
- 最低设备矩阵和业务升级冒烟测试通过；
- 无法证明安全上线时严格阻断；
- 不对未认证设备和未知第三方 APK作绝对兼容承诺。
