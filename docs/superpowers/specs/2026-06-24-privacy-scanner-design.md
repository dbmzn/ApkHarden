# 隐私合规静态扫描器 — 设计文档

- 日期：2026-06-24
- 状态：已评审，待实现
- 关联：ApkHarden 工具箱第二个模块

## 1. 背景与定位

ApkHarden 目前是纯 JVM、操作 APK 文件、不依赖 Android SDK 的桌面工具，现仅有「基础加固」一个功能。本设计新增「隐私合规扫描器」，并把首页重构成可扩展的工具箱外壳，承接后续更多工具。

### 能力边界（关键认知，贯穿全文）

中国「隐私合规」监管的核心是**运行时行为**（如「用户同意前就采集 IMEI」「超范围采集」「私自频繁调用」）。这类**动态时序**只有真机/模拟器 + Hook 才能抓到。

本工具是**纯静态、只读 APK**，因此它检测的是**「能力/嫌疑」**而非**「行为/合规判定」**：

- 能精确查到：申请了哪些敏感权限、集成了哪些第三方 SDK、代码里是否引用了敏感 API。
- 查不到：这些调用在什么时机发生、是否在用户同意前执行。

**定位 = 上架前静态自查清单**：输出「风险嫌疑项 + 整改建议」，**不声称判定合规**。所有面向用户的措辞必须守住这一边界（用「检测到调用点，请核实调用时机」，不写「违规」）。

### 范围（已确认）

- 路线 A：静态自查清单（非动态合规判定）。
- 覆盖全部四类检测：敏感权限、第三方 SDK 清单、敏感 API 调用点、隐私政策/合规文件检查。
- 形态：ApkHarden 新增标签页 + 首页重构为工具箱外壳。
- 输出：GUI 内分类清单 + 导出 HTML/Markdown 报告。
- 规则库：外部 JSON 资源文件。

### 不做（YAGNI 边界）

动态/时序分析；JSON 导出；外部规则覆盖；CLI 入口；调用图可达性/数据流分析。均留待以后按需加。

## 2. 架构方案

核心分歧在「dex 怎么扫」，决定准确度。**采用方案 B：正经解析 dex（dexlib2）+ ARSCLib 解 manifest。**

理由：四类检测中「敏感 API 调用点」的全部价值在于把「真调用」和「碰巧出现的字符串」区分开。子串匹配（现有 HardenLinter 的做法）误报极高、报告失去可信度；dexlib2 解析 dex 的 method_ids 表，命中即「被引用」，误报骤降，且 dexlib2 是纯 JVM 库，契合「装机即用」定位。

被否决的方案：

- 方案 A（字符串子串匹配）：零依赖最快，但误报高、分不清声明与调用、无法定位，不适合给审核看的报告。
- 方案 C（数据流/时序分析）：即第 1 节排除的动态路线，纯静态做不可靠且巨复杂。

## 3. 模块划分与包结构

加固与扫描共用基建（ApkReader、FilePicker），各自核心逻辑独立成模块。

```
packager/
├─ core/                    # 现有加固核心（不动）
├─ scanner/                 # 新增：隐私扫描核心（纯逻辑，无 UI 依赖）
│  ├─ PrivacyScanner.kt     # 编排：输入 APK → 跑四类检测器 → 汇总 ScanReport
│  ├─ RuleSet.kt            # 从 JSON 资源加载规则库（懒加载 + 缓存）
│  ├─ model/                # Finding / ScanReport / Severity 等数据类
│  ├─ detector/
│  │  ├─ Detector.kt             # 接口
│  │  ├─ PermissionDetector.kt   # ARSCLib 解 manifest
│  │  ├─ SdkInventoryDetector.kt # dexlib2 type_ids
│  │  ├─ SensitiveApiDetector.kt # dexlib2 method_ids
│  │  └─ ComplianceFileDetector.kt # zip 条目 + manifest 属性
│  └─ report/
│     └─ ReportExporter.kt  # ScanReport → HTML / Markdown
├─ dex/
│  └─ DexIndex.kt           # dexlib2 封装：一次解析，给出 types/methods/strings 表
└─ ui/
   ├─ App.kt                # 新：工具箱外壳（左侧导航 + 内容区）
   ├─ tool/Tool.kt          # 工具注册接口（id/标题/Composable）
   ├─ harden/HardenScreen.kt    # 现有界面迁入（逻辑不变）
   ├─ scan/ScanScreen.kt        # 新：隐私扫描界面
   └─ common/FilePicker.kt      # 抽出现内嵌在 HardenScreen 的 NFD pick()
```

### UI 工具箱重构

- `Main.kt` 改渲染 `App()` 取代直接渲染 `HardenScreen()`。
- `App()` = 左侧 NavigationRail（列出所有工具）+ 右侧内容区；`selectedTool` 状态在外壳。
- 工具用 `Tool` 列表注册：`listOf(HardenTool, PrivacyScanTool, …)`。加新工具 = 往列表加一项 + 写个 Screen，不碰外壳。
- `FilePicker`：把现内嵌于 HardenScreen 的 `pick()`（含 NFD_Init/Quit 管理）抽成 `common/FilePicker.kt` 共享函数。

### 关键边界

- `scanner/` 纯逻辑、不依赖 Compose，可被单测直接调，也为以后 CLI 留口。
- `DexIndex` 把 dexlib2 封在一处，检测器只面对「types/methods/strings 三张表」，不直接碰 dexlib2 API。

## 4. JSON 规则库 schema

规则打包进 `src/main/resources/rules/`，`RuleSet` 启动加载，用 kotlinx.serialization 解析。**所有面向用户的文案都在 JSON 里**，core 不写死中文——加 SDK / 改建议无需动代码重编。预置一份能用的初始规则（主流国产 SDK 二三十个 + 工信部口径的敏感权限/API），非空壳。

字段约定：`severity ∈ {HIGH, MEDIUM, LOW, INFO}`；`category` 自由字符串，报告按它分组。

### rules/sdk.json — 第三方 SDK 特征库

```json
{
  "version": "2026-06-24",
  "sdks": [
    {
      "id": "umeng-analytics",
      "name": "友盟统计",
      "vendor": "友盟+",
      "category": "统计分析",
      "packages": ["com/umeng/", "com/uc/crashsdk/"],
      "privacy": "https://www.umeng.com/page/policy",
      "note": "采集设备标识、地理位置；须在隐私政策中列明并取得同意"
    }
  ]
}
```

- `packages`：dex `type_ids` 里的类前缀，命中即判定集成该 SDK。
- `category`：报告分组（统计/广告/推送/支付/地图/社交/风控…）。
- `privacy`/`note`：进报告，供整改。

### rules/sensitive_api.json — 敏感 API 清单

```json
{
  "apis": [
    {
      "id": "imei",
      "title": "读取 IMEI / 设备标识",
      "category": "设备标识",
      "severity": "HIGH",
      "methods": [
        "Landroid/telephony/TelephonyManager;->getDeviceId",
        "Landroid/telephony/TelephonyManager;->getImei"
      ],
      "advice": "Android 10+ 普通应用无法获取 IMEI；改用 OAID/AndroidID，且须同意后调用"
    },
    {
      "id": "installed-packages",
      "title": "获取已安装应用列表",
      "category": "应用列表",
      "severity": "HIGH",
      "methods": ["Landroid/content/pm/PackageManager;->getInstalledPackages"],
      "advice": "属敏感行为，需单独声明用途；多数场景应避免"
    }
  ]
}
```

- `methods` 匹配 dex `method_ids`，格式 `L类描述符;->方法名`，**不含参数签名**（容忍重载）。命中即「被引用」。

### rules/permissions.json — 敏感权限释义

```json
{
  "permissions": [
    {
      "name": "android.permission.READ_PHONE_STATE",
      "title": "读取电话状态",
      "category": "设备标识",
      "severity": "HIGH",
      "advice": "常被用于取 IMEI；若仅做统计，无必要申请"
    },
    {
      "name": "android.permission.ACCESS_FINE_LOCATION",
      "title": "精确位置",
      "category": "位置",
      "severity": "HIGH",
      "advice": "对照《必要个人信息范围规定》，非地图/出行类一般非必要"
    }
  ]
}
```

未在库中的权限仍列出，标 `UNKNOWN/INFO`，不漏报。

### rules/compliance.json — 合规文件/配置检查

```json
{
  "checks": [
    { "id": "min-target-sdk", "type": "TARGET_SDK_MIN", "value": 31,
      "severity": "MEDIUM", "advice": "应用市场要求 targetSdk≥31，否则可能下架" },
    { "id": "cleartext", "type": "MANIFEST_ATTR",
      "attr": "usesCleartextTraffic", "expect": "false",
      "severity": "LOW", "advice": "允许明文流量，建议关闭" }
  ]
}
```

## 5. 扫描器核心与数据流

### 数据模型（scanner/model/）

```kotlin
enum class Severity { HIGH, MEDIUM, LOW, INFO }   // 报告排序权重

data class Finding(
    val category: String,        // 报告按它分组
    val severity: Severity,
    val title: String,
    val detail: String,          // 命中了什么：权限名 / SDK 名 / 方法签名
    val location: String?,       // "AndroidManifest.xml" / "classes2.dex" / 条目名
    val advice: String,          // 来自 JSON
    val sourceRuleId: String,    // 溯源到规则
)

data class ScanReport(
    val apkName: String,
    val packageName: String?,
    val versionName: String?,
    val findings: List<Finding>,
    val summary: Map<Severity, Int>,   // 各级别计数，给 GUI 概览
)
```

### DexIndex（dexlib2 封装，解析一次喂所有检测器）

```kotlin
class DexIndex(dexBytes: List<Pair<String, ByteArray>>) {  // (entryName, bytes)
    fun typeDescriptors(): Sequence<Located<String>>    // "Lcom/umeng/foo;" + 所在 dex
    fun methodRefs(): Sequence<Located<String>>         // "Lx;->getImei" + 所在 dex
}
```

- 检测器只看这两张表，不碰 dexlib2 API（边界封死在这里）。
- `Located<T>` 携带值 + 来源 dex 名，让 Finding 能定位到 `classes2.dex`。
- 多 dex 一次性建索引，避免每个检测器各解析一遍。

### 四个检测器（各实现 `Detector { fun detect(ctx): List<Finding> }`）

| 检测器 | 输入 | 逻辑 |
|---|---|---|
| PermissionDetector | manifest (ARSCLib) | 取所有 `uses-permission`，逐个查 permissions.json；命中出 Finding，未知权限出 INFO |
| SdkInventoryDetector | DexIndex.typeDescriptors() | 对每个 SDK 的 packages 前缀做命中判定；同一 SDK 只出一条（去重，附首次命中 dex） |
| SensitiveApiDetector | DexIndex.methodRefs() | 把 method ref 归一成 `L类;->方法名`（去参数签名），匹配 sensitive_api.json；同一 api 去重计数 |
| ComplianceFileDetector | zip 条目 + manifest 属性 | targetSdk 下限、cleartext、allowBackup、隐私政策页线索等 compliance.json 规则 |

### 编排（PrivacyScanner.scan）数据流

```
APK File
  └─ ApkReader（复用现有）→ manifestBytes, dexNames+bytes, entryNames
        ├─ ManifestInfo = ARSCLib 解出 package/versionName/targetSdk/permissions/属性
        ├─ DexIndex = dexlib2 解析全部 dex（一次）
        └─ 跑四个 detector（互相独立）
              → List<Finding> 汇总
              → 按 (severity, category) 排序、统计 summary
              → ScanReport
```

进度回调：和 HardenPipeline 一样收 `log: (String)->Unit`，逐步推「解析 manifest… / 索引 N 个 dex… / 匹配 SDK…」。

准确度细节：method ref 命中只证明「代码里引用了该方法」，不证明运行时执行 / 同意前执行。这类 Finding 措辞为「检测到调用点，请核实调用时机」，不写「违规」。

## 6. 报告呈现

### GUI（ui/scan/ScanScreen.kt）

与 HardenScreen 同气质，结果区是结构化清单而非纯日志。

```
[输入 APK ............] [浏览]        ← 复用 common/FilePicker
        [ 开始扫描 ]                  ← 钉在表单下（同 HardenScreen pinned button）
────────────────────────────────────
概览:  🔴 高 5  🟠 中 3  🟡 低 2  ℹ️ 4   ← summary 计数条，点击筛选
com.example.app  v2.3.1  targetSdk 30
────────────────────────────────────
▾ 设备标识 (2)                         ← 按 category 分组、可折叠、Severity 排序
   🔴 读取 IMEI / 设备标识
      命中 TelephonyManager->getImei @classes2
      → Android 10+ 改用 OAID，同意后调用
   🔴 READ_PHONE_STATE 权限
▸ 第三方SDK · 统计分析 (3)
▸ 位置 (1)
────────────────────────────────────
        [ 导出报告 ▾ ]  HTML / Markdown  ← 扫描完成才启用
```

- 概览计数条点击按 Severity 过滤（纯前端 state，不重扫）。
- 清单按 category 分组、Severity 排序（HIGH 在上），每组可折叠；空结果显示「未发现明显风险项」。
- 扫描在 Dispatchers.IO，running 态禁用按钮，进度走 log——与加固一致。
- 导出按钮扫描完成才启用，弹 NFD 保存对话框选路径。

### 导出（scanner/report/ReportExporter.kt）

- `toMarkdown(report): String`：标题 + 概览表 + 按分类小节，每条 `**[高] 标题** — detail / 建议`。纯字符串拼装，零依赖。
- `toHtml(report): String`：自包含单文件 HTML（内联 CSS、无外链），带颜色徽章和分组，双击浏览器可开，可直接发产品/法务/审核。
- 两者从同一 ScanReport 渲染，core 不依赖 UI，可单测。
- 报告页脚固定免责声明：「本报告为静态自查，标注的是风险嫌疑项而非合规判定，调用时机/实际行为需结合动态验证。」
- 导出内容 = GUI 所见（同一份 ScanReport），不另搞一套。

## 7. 错误处理

扫描器为只读分析，绝不能因单点失败整体崩，部分结果好过没结果。

- 单个检测器抛异常 → 捕获，记一条 INFO Finding（「X 检测未完成: 原因」），其余照常出结果。
- dexlib2 解析某 dex 失败（畸形 dex）→ 跳过该 dex 并告警，不中断其余。
- manifest 解析失败 → 权限/合规类降级为「无法解析 manifest」告警，dex 类仍跑。
- 规则 JSON 缺失/格式错 → 启动 fail-fast 明确报错（打包进资源的程序员错误，不静默）。
- 输入非有效 APK / 无 dex → 友好提示，不抛栈给用户。

## 8. 测试策略

沿用现有 `./gradlew test`（单测 + 集成）。

- 每个检测器单测：构造最小输入（假 manifest bytes / 含已知方法引用的小 dex）+ 测试规则 JSON → 断言 Finding。
- DexIndex 单测：真实小 APK 的 dex，断言能取到已知类型/方法。
- RuleSet 单测：解析内置 JSON 不抛、字段齐全。
- ReportExporter 单测：固定 ScanReport → 断言 Markdown/HTML 含关键字段、HTML 自包含无外链。
- 端到端 PrivacyScannerTest：拿 samples/ 样例 APK 跑全流程，断言报告非空、summary 计数正确。
- 误报基线：用一个「干净」小 APK 断言高危项为 0，防止规则改动引入噪音。

## 9. 依赖与现有代码改动

### 新增依赖（均纯 JVM，符合「装机即用」）

- `com.android.tools.smali:smali-dexlib2`（解析 dex）—— 当前未引入，需新增。
- `org.jetbrains.kotlinx:kotlinx-serialization-json` + `org.jetbrains.kotlin.plugin.serialization` 编译器插件 —— 当前未引入（项目只有 kotlinx-coroutines），需一并新增。
- apksig、ARSCLib、kotlinx-coroutines、LWJGL：已有，不新增。

### 对现有代码的改动

- `Main.kt`：渲染 `App()` 取代 `HardenScreen()`（约几行）。
- `HardenScreen.kt`：内嵌 `pick()` 抽到 `common/FilePicker.kt`；其余逻辑原样迁入 `ui/harden/`，行为不变。
- 不动 core/ 加固逻辑、不动 shell/。加固功能零回归风险。
