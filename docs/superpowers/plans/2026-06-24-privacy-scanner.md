# 隐私合规静态扫描器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 ApkHarden 增加一个纯静态的「隐私合规扫描」工具——读 APK，输出权限/第三方SDK/敏感API调用点/合规配置四类风险清单，并把首页重构成可扩展的工具箱外壳。

**Architecture:** core 纯逻辑层（`scanner/`）不依赖 UI：`PrivacyScanner` 编排四个独立 `Detector`，dex 用 dexlib2 解析（`DexIndex`），manifest 用 ARSCLib 解析（`ManifestReader`），规则全部外置为 JSON 资源（`RuleSet`）。UI 层把单屏 `HardenScreen` 重构为 `App()` 外壳 + 工具注册表 + 共享 `FilePicker`，加固和扫描各占一个 Tab。

**Tech Stack:** Kotlin/JVM, Compose Desktop, dexlib2 (com.android.tools.smali), ARSCLib, kotlinx-serialization-json, JUnit 5。

参考设计：`docs/superpowers/specs/2026-06-24-privacy-scanner-design.md`

---

## File Structure

新增（core，无 UI 依赖）：
- `src/main/kotlin/com/apkharden/packager/scanner/model/Models.kt` — Severity / Finding / ScanReport / Located
- `src/main/kotlin/com/apkharden/packager/scanner/RuleModels.kt` — @Serializable 规则数据类
- `src/main/kotlin/com/apkharden/packager/scanner/RuleSet.kt` — 加载内置/自定义 JSON 规则
- `src/main/kotlin/com/apkharden/packager/dex/DexIndex.kt` — dexlib2 封装：types / methodRefs
- `src/main/kotlin/com/apkharden/packager/scanner/ManifestReader.kt` — ARSCLib 封装：ManifestInfo
- `src/main/kotlin/com/apkharden/packager/scanner/detector/Detector.kt` — Detector 接口 + ScanContext
- `src/main/kotlin/com/apkharden/packager/scanner/detector/PermissionDetector.kt`
- `src/main/kotlin/com/apkharden/packager/scanner/detector/SdkInventoryDetector.kt`
- `src/main/kotlin/com/apkharden/packager/scanner/detector/SensitiveApiDetector.kt`
- `src/main/kotlin/com/apkharden/packager/scanner/detector/ComplianceFileDetector.kt`
- `src/main/kotlin/com/apkharden/packager/scanner/PrivacyScanner.kt` — 编排 + 错误隔离
- `src/main/kotlin/com/apkharden/packager/scanner/report/ReportExporter.kt` — Markdown / HTML

新增资源：
- `src/main/resources/rules/sdk.json`
- `src/main/resources/rules/sensitive_api.json`
- `src/main/resources/rules/permissions.json`
- `src/main/resources/rules/compliance.json`

新增 UI：
- `src/main/kotlin/com/apkharden/packager/ui/common/FilePicker.kt`
- `src/main/kotlin/com/apkharden/packager/ui/tool/Tool.kt`
- `src/main/kotlin/com/apkharden/packager/ui/App.kt`
- `src/main/kotlin/com/apkharden/packager/ui/scan/ScanScreen.kt`

移动 / 修改：
- `src/main/kotlin/com/apkharden/packager/ui/HardenScreen.kt` → `ui/harden/HardenScreen.kt`（逻辑不变，去掉内嵌 `pick()`）
- `src/main/kotlin/com/apkharden/packager/Main.kt` — 渲染 `App()`
- `build.gradle.kts` — 新增依赖与插件

新增测试：
- `src/test/kotlin/com/apkharden/packager/scanner/RuleSetTest.kt`
- `src/test/kotlin/com/apkharden/packager/dex/DexIndexTest.kt`
- `src/test/kotlin/com/apkharden/packager/scanner/ManifestReaderTest.kt`
- `src/test/kotlin/com/apkharden/packager/scanner/detector/*Test.kt`（四个）
- `src/test/kotlin/com/apkharden/packager/scanner/PrivacyScannerTest.kt`
- `src/test/kotlin/com/apkharden/packager/scanner/report/ReportExporterTest.kt`
- `src/test/resources/sample.dex`（夹具，Task 4 生成并提交）

---

## Task 1: 新增依赖与 serialization 插件

**Files:**
- Modify: `build.gradle.kts:3-7`（plugins）, `build.gradle.kts:26-36`（dependencies）

- [ ] **Step 1: 加 serialization 编译器插件**

`build.gradle.kts` 的 `plugins { }` 块改为：

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}
```

- [ ] **Step 2: 加运行期依赖**

在 `dependencies { }` 块里，`ARSCLib` 那行后面加两行：

```kotlin
    implementation("io.github.reandroid:ARSCLib:1.3.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.5")
```

- [ ] **Step 3: 验证依赖解析与编译**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL（仅验证依赖能下载、插件生效；若 `smali-dexlib2:3.0.5` 不存在，用 `./gradlew dependencies --configuration runtimeClasspath` 查最新 3.0.x 版本并替换）。

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add dexlib2 + kotlinx-serialization for privacy scanner"
```

---

## Task 2: 扫描结果数据模型

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/model/Models.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/model/ModelsTest.kt`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/scanner/model/ModelsTest.kt`:

```kotlin
package com.apkharden.packager.scanner.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelsTest {
    @Test
    fun `summary counts findings by severity`() {
        val findings = listOf(
            Finding("设备标识", Severity.HIGH, "a", "d", null, "x", "r1"),
            Finding("位置", Severity.HIGH, "b", "d", "classes.dex", "x", "r2"),
            Finding("其它", Severity.LOW, "c", "d", null, "x", "r3"),
        )
        val report = ScanReport.of("app.apk", "com.x", "1.0", findings)
        assertEquals(2, report.summary[Severity.HIGH])
        assertEquals(1, report.summary[Severity.LOW])
        assertNull(report.summary[Severity.INFO])
        // 排序：HIGH 在 LOW 之前
        assertEquals(Severity.HIGH, report.findings.first().severity)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.model.ModelsTest"`
Expected: FAIL（编译错误，`Finding`/`ScanReport`/`Severity` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/model/Models.kt`:

```kotlin
package com.apkharden.packager.scanner.model

/** 报告里的严重度，序数即排序权重（HIGH 最靠前）。 */
enum class Severity { HIGH, MEDIUM, LOW, INFO }

/** 携带「值 + 来源 dex 名」，让 Finding 能定位到具体 dex。 */
data class Located<out T>(val value: T, val dex: String)

/** 一条风险项。所有面向用户的文案（title/detail/advice）都来自规则 JSON。 */
data class Finding(
    val category: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val location: String?,
    val advice: String,
    val sourceRuleId: String,
)

data class ScanReport(
    val apkName: String,
    val packageName: String?,
    val versionName: String?,
    val findings: List<Finding>,
    val summary: Map<Severity, Int>,
) {
    companion object {
        fun of(apkName: String, pkg: String?, versionName: String?, findings: List<Finding>): ScanReport {
            val sorted = findings.sortedWith(compareBy({ it.severity.ordinal }, { it.category }))
            val summary = findings.groupingBy { it.severity }.eachCount()
            return ScanReport(apkName, pkg, versionName, sorted, summary)
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.model.ModelsTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/model/Models.kt src/test/kotlin/com/apkharden/packager/scanner/model/ModelsTest.kt
git commit -m "feat(scanner): finding + scan report data model"
```

---

## Task 3: 规则数据类、RuleSet 加载器、内置规则 JSON

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/RuleModels.kt`
- Create: `src/main/kotlin/com/apkharden/packager/scanner/RuleSet.kt`
- Create: `src/main/resources/rules/sdk.json`, `sensitive_api.json`, `permissions.json`, `compliance.json`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/RuleSetTest.kt`

- [ ] **Step 1: 写规则数据类**

`src/main/kotlin/com/apkharden/packager/scanner/RuleModels.kt`:

```kotlin
package com.apkharden.packager.scanner

import com.apkharden.packager.scanner.model.Severity
import kotlinx.serialization.Serializable

@Serializable
data class SdkRule(
    val id: String,
    val name: String,
    val vendor: String = "",
    val category: String,
    val packages: List<String>,
    val privacy: String? = null,
    val note: String? = null,
)

@Serializable
data class ApiRule(
    val id: String,
    val title: String,
    val category: String,
    val severity: Severity,
    val methods: List<String>,
    val advice: String,
)

@Serializable
data class PermissionRule(
    val name: String,
    val title: String,
    val category: String,
    val severity: Severity,
    val advice: String,
)

/** type 取值：TARGET_SDK_MIN（看 value）/ DEBUGGABLE_FALSE / ALLOW_BACKUP_FALSE。 */
@Serializable
data class ComplianceCheck(
    val id: String,
    val type: String,
    val value: Int? = null,
    val severity: Severity,
    val advice: String,
)

@Serializable private data class SdkRules(val version: String = "", val sdks: List<SdkRule>)
@Serializable private data class ApiRules(val apis: List<ApiRule>)
@Serializable private data class PermissionRules(val permissions: List<PermissionRule>)
@Serializable private data class ComplianceRules(val checks: List<ComplianceCheck>)

internal object RuleJson {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun sdks(s: String) = json.decodeFromString<SdkRules>(s).sdks
    fun apis(s: String) = json.decodeFromString<ApiRules>(s).apis
    fun permissions(s: String) = json.decodeFromString<PermissionRules>(s).permissions
    fun checks(s: String) = json.decodeFromString<ComplianceRules>(s).checks
}
```

- [ ] **Step 2: 写 RuleSet 加载器**

`src/main/kotlin/com/apkharden/packager/scanner/RuleSet.kt`:

```kotlin
package com.apkharden.packager.scanner

class RuleSet(
    val sdks: List<SdkRule>,
    val apis: List<ApiRule>,
    val permissions: List<PermissionRule>,
    val checks: List<ComplianceCheck>,
) {
    companion object {
        /** 从打包进资源的 /rules/*.json 加载。规则缺失/格式错即 fail-fast。 */
        fun bundled(): RuleSet = RuleSet(
            sdks = RuleJson.sdks(res("/rules/sdk.json")),
            apis = RuleJson.apis(res("/rules/sensitive_api.json")),
            permissions = RuleJson.permissions(res("/rules/permissions.json")),
            checks = RuleJson.checks(res("/rules/compliance.json")),
        )

        /** 给测试用：直接喂四段 JSON 字符串。 */
        fun fromJson(sdk: String, api: String, perm: String, compliance: String): RuleSet = RuleSet(
            sdks = RuleJson.sdks(sdk),
            apis = RuleJson.apis(api),
            permissions = RuleJson.permissions(perm),
            checks = RuleJson.checks(compliance),
        )

        private fun res(path: String): String =
            (RuleSet::class.java.getResourceAsStream(path)
                ?: error("规则资源缺失：$path")).bufferedReader().use { it.readText() }
    }
}
```

- [ ] **Step 3: 写内置规则 JSON（起步集，后续可扩）**

`src/main/resources/rules/sdk.json`:

```json
{
  "version": "2026-06-24",
  "sdks": [
    { "id": "umeng", "name": "友盟统计", "vendor": "友盟+", "category": "统计分析",
      "packages": ["com/umeng/", "com/uc/crashsdk/"],
      "privacy": "https://www.umeng.com/page/policy",
      "note": "采集设备标识与位置；须在隐私政策列明并取得同意" },
    { "id": "pangle", "name": "穿山甲广告", "vendor": "巨量引擎", "category": "广告",
      "packages": ["com/bytedance/sdk/openadsdk/", "com/bytedance/pangle/"],
      "note": "广告 SDK，采集设备标识用于广告归因" },
    { "id": "jpush", "name": "极光推送", "vendor": "极光", "category": "推送",
      "packages": ["cn/jpush/", "cn/jiguang/"],
      "note": "推送 SDK，采集设备标识；须声明并同意后初始化" },
    { "id": "wechat", "name": "微信开放SDK", "vendor": "腾讯", "category": "社交/支付",
      "packages": ["com/tencent/mm/opensdk/"], "note": "分享/登录/支付" },
    { "id": "alipay", "name": "支付宝SDK", "vendor": "蚂蚁", "category": "支付",
      "packages": ["com/alipay/sdk/"], "note": "支付" },
    { "id": "amap", "name": "高德地图", "vendor": "高德", "category": "地图/定位",
      "packages": ["com/amap/api/", "com/loc/"], "note": "定位与地图，采集精确位置" },
    { "id": "bugly", "name": "Bugly 崩溃上报", "vendor": "腾讯", "category": "统计分析",
      "packages": ["com/tencent/bugly/"], "note": "崩溃与设备信息上报" }
  ]
}
```

`src/main/resources/rules/sensitive_api.json`:

```json
{
  "apis": [
    { "id": "imei", "title": "读取 IMEI / 设备标识", "category": "设备标识", "severity": "HIGH",
      "methods": ["Landroid/telephony/TelephonyManager;->getDeviceId",
                  "Landroid/telephony/TelephonyManager;->getImei",
                  "Landroid/telephony/TelephonyManager;->getMeid"],
      "advice": "Android 10+ 普通应用已无法获取 IMEI；改用 OAID/AndroidID，且须用户同意后调用" },
    { "id": "android-id", "title": "读取 Android ID", "category": "设备标识", "severity": "MEDIUM",
      "methods": ["Landroid/provider/Settings$Secure;->getString"],
      "advice": "若取 ANDROID_ID 作标识，须在隐私政策声明，同意后调用" },
    { "id": "installed-packages", "title": "获取已安装应用列表", "category": "应用列表", "severity": "HIGH",
      "methods": ["Landroid/content/pm/PackageManager;->getInstalledPackages",
                  "Landroid/content/pm/PackageManager;->getInstalledApplications"],
      "advice": "属敏感行为，需单独声明用途；多数场景应避免" },
    { "id": "location", "title": "读取地理位置", "category": "位置", "severity": "HIGH",
      "methods": ["Landroid/location/LocationManager;->getLastKnownLocation",
                  "Landroid/location/LocationManager;->requestLocationUpdates"],
      "advice": "须用户授予位置权限并同意后调用；非必要业务避免精确定位" },
    { "id": "clipboard", "title": "读取剪贴板", "category": "剪贴板", "severity": "MEDIUM",
      "methods": ["Landroid/content/ClipboardManager;->getPrimaryClip",
                  "Landroid/content/ClipboardManager;->getText"],
      "advice": "避免在前台无感时读剪贴板；仅在用户主动粘贴时调用" },
    { "id": "mac", "title": "读取 MAC / 网络标识", "category": "设备标识", "severity": "MEDIUM",
      "methods": ["Landroid/net/wifi/WifiInfo;->getMacAddress"],
      "advice": "高版本已返回固定占位值；勿作设备唯一标识" }
  ]
}
```

`src/main/resources/rules/permissions.json`:

```json
{
  "permissions": [
    { "name": "android.permission.READ_PHONE_STATE", "title": "读取电话状态", "category": "设备标识",
      "severity": "HIGH", "advice": "常被用于取 IMEI；仅做统计则无必要申请" },
    { "name": "android.permission.ACCESS_FINE_LOCATION", "title": "精确位置", "category": "位置",
      "severity": "HIGH", "advice": "对照《必要个人信息范围规定》，非地图/出行类一般非必要" },
    { "name": "android.permission.ACCESS_COARSE_LOCATION", "title": "粗略位置", "category": "位置",
      "severity": "MEDIUM", "advice": "确认业务确需位置；否则移除" },
    { "name": "android.permission.READ_CONTACTS", "title": "读取通讯录", "category": "通讯录",
      "severity": "HIGH", "advice": "敏感个人信息，须明确告知用途并单独同意" },
    { "name": "android.permission.READ_EXTERNAL_STORAGE", "title": "读取外部存储", "category": "存储",
      "severity": "MEDIUM", "advice": "优先用分区存储/SAF，减少全盘读取权限" },
    { "name": "android.permission.CAMERA", "title": "相机", "category": "相机",
      "severity": "MEDIUM", "advice": "须在使用场景前申请，避免启动即申请" },
    { "name": "android.permission.RECORD_AUDIO", "title": "录音", "category": "麦克风",
      "severity": "HIGH", "advice": "敏感权限，须场景化申请并明确告知" },
    { "name": "android.permission.QUERY_ALL_PACKAGES", "title": "查询全部应用", "category": "应用列表",
      "severity": "HIGH", "advice": "Google/应用市场严格管控；非必要必须移除，改用 <queries>" }
  ]
}
```

`src/main/resources/rules/compliance.json`:

```json
{
  "checks": [
    { "id": "min-target-sdk", "type": "TARGET_SDK_MIN", "value": 31, "severity": "MEDIUM",
      "advice": "应用市场普遍要求 targetSdk≥31，过低可能被拒/下架" },
    { "id": "debuggable", "type": "DEBUGGABLE_FALSE", "severity": "HIGH",
      "advice": "发布包不应 android:debuggable=true，会暴露调试入口" },
    { "id": "allow-backup", "type": "ALLOW_BACKUP_FALSE", "severity": "LOW",
      "advice": "android:allowBackup=true 可能经 adb 备份泄露应用数据，建议关闭" }
  ]
}
```

- [ ] **Step 4: 写测试**

`src/test/kotlin/com/apkharden/packager/scanner/RuleSetTest.kt`:

```kotlin
package com.apkharden.packager.scanner

import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuleSetTest {
    @Test
    fun `bundled rules load and are non-empty`() {
        val rs = RuleSet.bundled()
        assertTrue(rs.sdks.isNotEmpty())
        assertTrue(rs.apis.isNotEmpty())
        assertTrue(rs.permissions.isNotEmpty())
        assertTrue(rs.checks.isNotEmpty())
        // severity 枚举正确反序列化
        assertTrue(rs.apis.any { it.severity == Severity.HIGH })
        // 关键字段齐全
        assertTrue(rs.sdks.all { it.packages.isNotEmpty() && it.name.isNotBlank() })
    }

    @Test
    fun `fromJson parses custom rules`() {
        val rs = RuleSet.fromJson(
            sdk = """{"sdks":[{"id":"x","name":"X","category":"测试","packages":["com/x/"]}]}""",
            api = """{"apis":[{"id":"a","title":"A","category":"测试","severity":"LOW","methods":["Lx;->y"],"advice":"z"}]}""",
            perm = """{"permissions":[]}""",
            compliance = """{"checks":[]}""",
        )
        assertEquals("X", rs.sdks.single().name)
        assertEquals(Severity.LOW, rs.apis.single().severity)
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.RuleSetTest"`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/RuleModels.kt src/main/kotlin/com/apkharden/packager/scanner/RuleSet.kt src/main/resources/rules src/test/kotlin/com/apkharden/packager/scanner/RuleSetTest.kt
git commit -m "feat(scanner): external JSON ruleset + bundled starter rules"
```

---

## Task 4: DexIndex（dexlib2 封装）

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/dex/DexIndex.kt`
- Create: `src/test/resources/sample.dex`（夹具，本任务生成并提交）
- Test: `src/test/kotlin/com/apkharden/packager/dex/DexIndexTest.kt`

- [ ] **Step 0: 生成测试夹具 sample.dex（一次性，提交进仓库）**

新建临时文件 `TrackingSdk.java`（纯 Java、无 Android 依赖，可直接 javac）：

```java
package com.example.sdkdemo;
public class TrackingSdk {
    public void track() { helper(); }
    void helper() {}
}
```

用仓库现成的工具链（同 `scripts/build-shell.ps1`，需 `C:\AndroidSdk`）编译并 dex 化：

```powershell
$env:ANDROID_HOME = "C:\AndroidSdk"
$tmp = New-Item -ItemType Directory -Force -Path "$env:TEMP\dexfix"
# 把上面的 TrackingSdk.java 放到 $tmp\com\example\sdkdemo\TrackingSdk.java
javac -d $tmp (Join-Path $tmp "com\example\sdkdemo\TrackingSdk.java")
$d8 = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Recurse -Filter d8.bat | Select-Object -First 1
& $d8.FullName --output $tmp (Join-Path $tmp "com\example\sdkdemo\TrackingSdk.class")
Copy-Item (Join-Path $tmp "classes.dex") "src\test\resources\sample.dex" -Force
```

确认 `src/test/resources/sample.dex` 存在（约 ~600B）。它定义了类 `Lcom/example/sdkdemo/TrackingSdk;`，且 `track()` 体内有对 `helper()` 的方法引用。

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/dex/DexIndexTest.kt`:

```kotlin
package com.apkharden.packager.dex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class DexIndexTest {
    private fun sampleDex(): ByteArray = File("src/test/resources/sample.dex").readBytes()

    @Test
    fun `extracts defined type descriptors with dex location`() {
        val idx = DexIndex(listOf("classes.dex" to sampleDex()))
        val types = idx.typeDescriptors().toList()
        val sdk = types.firstOrNull { it.value == "Lcom/example/sdkdemo/TrackingSdk;" }
        assertNotNull(sdk, "应解析出定义的类型")
        assertEquals("classes.dex", sdk!!.dex)
    }

    @Test
    fun `extracts referenced method refs as class arrow name`() {
        val idx = DexIndex(listOf("classes2.dex" to sampleDex()))
        val refs = idx.methodRefs().map { it.value }.toSet()
        assertTrue(refs.contains("Lcom/example/sdkdemo/TrackingSdk;->helper"),
            "应包含被调用的方法引用，实际：$refs")
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.dex.DexIndexTest"`
Expected: FAIL（`DexIndex` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/dex/DexIndex.kt`:

```kotlin
package com.apkharden.packager.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.apkharden.packager.scanner.model.Located
import java.io.BufferedInputStream

/**
 * dexlib2 封装。解析一组 (entryName, bytes)，对外只暴露两张「表」：
 *  - typeDescriptors(): 每个 dex 里【定义】的类型（"Lpkg/Cls;"），用于判定 SDK 是否被打进包。
 *  - methodRefs(): 指令里【引用】的方法（"Lcls;->name"，不含参数签名），用于判定敏感 API 是否被调用。
 * 检测器只面对这两张表，不直接依赖 dexlib2 类型。
 */
class DexIndex(private val dexes: List<Pair<String, ByteArray>>) {

    private fun parse(bytes: ByteArray): DexBackedDexFile =
        DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(bytes.inputStream()))

    fun typeDescriptors(): Sequence<Located<String>> = sequence {
        for ((name, bytes) in dexes) {
            val dex = runCatching { parse(bytes) }.getOrNull() ?: continue
            for (cls in dex.classes) yield(Located(cls.type, name))
        }
    }

    fun methodRefs(): Sequence<Located<String>> = sequence {
        for ((name, bytes) in dexes) {
            val dex = runCatching { parse(bytes) }.getOrNull() ?: continue
            for (cls in dex.classes) {
                for (method in cls.methods) {
                    val impl = method.implementation ?: continue
                    for (insn in impl.instructions) {
                        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        yield(Located("${ref.definingClass}->${ref.name}", name))
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.dex.DexIndexTest"`
Expected: PASS。
（若编译报某 dexlib2 类路径不符，按报错把 import 对到 `com.android.tools.smali.dexlib2.*` 下的同名类；`fromInputStream`/`classes`/`methods`/`implementation`/`instructions` 是 dexlib2 稳定 API。）

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/dex/DexIndex.kt src/test/resources/sample.dex src/test/kotlin/com/apkharden/packager/dex/DexIndexTest.kt
git commit -m "feat(scanner): DexIndex over dexlib2 (types + method refs)"
```

---

## Task 5: ManifestReader（ARSCLib 封装）

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/ManifestReader.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/ManifestReaderTest.kt`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/scanner/ManifestReaderTest.kt`:

```kotlin
package com.apkharden.packager.scanner

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManifestReaderTest {
    private fun manifest(): ByteArray = AndroidManifestBlock().apply {
        packageName = "com.example.demo"
        versionName = "2.3.1"
        addUsesPermission("android.permission.READ_PHONE_STATE")
        addUsesPermission("android.permission.INTERNET")
        refreshFull()
    }.bytes

    @Test
    fun `reads package, version and permissions`() {
        val info = ManifestReader.parse(manifest())
        assertEquals("com.example.demo", info.packageName)
        assertEquals("2.3.1", info.versionName)
        assertTrue(info.permissions.contains("android.permission.READ_PHONE_STATE"))
        assertTrue(info.permissions.contains("android.permission.INTERNET"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.ManifestReaderTest"`
Expected: FAIL（`ManifestReader` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/ManifestReader.kt`:

```kotlin
package com.apkharden.packager.scanner

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock

data class ManifestInfo(
    val packageName: String?,
    val versionName: String?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val debuggable: Boolean?,
    val allowBackup: Boolean?,
)

object ManifestReader {
    private const val ID_debuggable = 0x0101000f
    private const val ID_allowBackup = 0x01010280

    fun parse(bytes: ByteArray): ManifestInfo {
        val m = AndroidManifestBlock.load(bytes.inputStream())
        val app = m.applicationElement
        fun boolAttr(id: Int): Boolean? = app?.searchAttributeByResourceId(id)?.valueAsBoolean
        return ManifestInfo(
            packageName = m.packageName,
            versionName = m.versionName,
            targetSdk = m.targetSdkVersion,          // ARSCLib: Integer? getTargetSdkVersion()
            permissions = m.usesPermissions.toList(), // ARSCLib: List<String> getUsesPermissions()
            debuggable = boolAttr(ID_debuggable),
            allowBackup = boolAttr(ID_allowBackup),
        )
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.ManifestReaderTest"`
Expected: PASS。
（若 `targetSdkVersion`/`usesPermissions` 在 ARSCLib 1.3.8 命名不同，编译错误会指出；对到 `getTargetSdkVersion()` / `getUsesPermissions()` 即可。）

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/ManifestReader.kt src/test/kotlin/com/apkharden/packager/scanner/ManifestReaderTest.kt
git commit -m "feat(scanner): ManifestReader over ARSCLib"
```

---

## Task 6: Detector 接口 + ScanContext

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/detector/Detector.kt`

- [ ] **Step 1: 写实现（无独立测试，由各 Detector 任务覆盖）**

`src/main/kotlin/com/apkharden/packager/scanner/detector/Detector.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import com.apkharden.packager.scanner.model.Finding

/** 一次扫描的全部输入，传给每个检测器。 */
class ScanContext(
    val apkName: String,
    val manifest: ManifestInfo,
    val dexIndex: DexIndex,
    val entryNames: List<String>,
    val rules: RuleSet,
)

/** 每个检测器有单一职责，独立可测；抛异常由 PrivacyScanner 隔离。 */
interface Detector {
    val name: String
    fun detect(ctx: ScanContext): List<Finding>
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/detector/Detector.kt
git commit -m "feat(scanner): Detector interface + ScanContext"
```

---

## Task 7: PermissionDetector

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/detector/PermissionDetector.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/detector/PermissionDetectorTest.kt`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/scanner/detector/PermissionDetectorTest.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PermissionDetectorTest {
    private fun ctx(perms: List<String>): ScanContext {
        val rules = RuleSet.fromJson(
            sdk = """{"sdks":[]}""",
            api = """{"apis":[]}""",
            perm = """{"permissions":[
                {"name":"android.permission.READ_PHONE_STATE","title":"读取电话状态","category":"设备标识","severity":"HIGH","advice":"a"}
            ]}""",
            compliance = """{"checks":[]}""",
        )
        val info = ManifestInfo("com.x", "1.0", 31, perms, false, false)
        return ScanContext("x.apk", info, DexIndex(emptyList()), emptyList(), rules)
    }

    @Test
    fun `flags known sensitive permission with rule severity`() {
        val f = PermissionDetector().detect(ctx(listOf("android.permission.READ_PHONE_STATE")))
        val hit = f.single { it.detail.contains("READ_PHONE_STATE") }
        assertEquals(Severity.HIGH, hit.severity)
        assertEquals("设备标识", hit.category)
    }

    @Test
    fun `unknown permission surfaces as INFO, not dropped`() {
        val f = PermissionDetector().detect(ctx(listOf("android.permission.FOO_BAR")))
        val hit = f.single { it.detail.contains("FOO_BAR") }
        assertEquals(Severity.INFO, hit.severity)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.PermissionDetectorTest"`
Expected: FAIL（`PermissionDetector` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/detector/PermissionDetector.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.Severity

/** 逐个申请的权限对照 permissions.json；命中出 Finding，未知权限出 INFO（不漏报）。 */
class PermissionDetector : Detector {
    override val name = "权限检测"

    override fun detect(ctx: ScanContext): List<Finding> {
        val byName = ctx.rules.permissions.associateBy { it.name }
        return ctx.manifest.permissions.map { perm ->
            val rule = byName[perm]
            if (rule != null) {
                Finding(rule.category, rule.severity, rule.title,
                    "申请权限 $perm", "AndroidManifest.xml", rule.advice, "perm:${rule.name}")
            } else {
                Finding("其它权限", Severity.INFO, "申请了未分类权限",
                    "申请权限 $perm", "AndroidManifest.xml",
                    "确认该权限确有业务必要；非必要应移除。", "perm:unknown")
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.PermissionDetectorTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/detector/PermissionDetector.kt src/test/kotlin/com/apkharden/packager/scanner/detector/PermissionDetectorTest.kt
git commit -m "feat(scanner): permission detector"
```

---

## Task 8: SdkInventoryDetector

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/detector/SdkInventoryDetector.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/detector/SdkInventoryDetectorTest.kt`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/scanner/detector/SdkInventoryDetectorTest.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SdkInventoryDetectorTest {
    private val rules = RuleSet.fromJson(
        sdk = """{"sdks":[
            {"id":"demo","name":"演示SDK","vendor":"v","category":"统计分析","packages":["com/example/sdkdemo/"],"note":"n"}
        ]}""",
        api = """{"apis":[]}""", perm = """{"permissions":[]}""", compliance = """{"checks":[]}""",
    )

    private fun ctx(): ScanContext {
        val dex = File("src/test/resources/sample.dex").readBytes()
        val info = ManifestInfo("com.x", "1.0", 31, emptyList(), false, false)
        return ScanContext("x.apk", info, DexIndex(listOf("classes.dex" to dex)), emptyList(), rules)
    }

    @Test
    fun `detects bundled sdk once by package prefix`() {
        val f = SdkInventoryDetector().detect(ctx())
        val hits = f.filter { it.sourceRuleId == "sdk:demo" }
        assertEquals(1, hits.size, "同一 SDK 只出一条")
        assertEquals("演示SDK", hits.single().title)
        assertEquals("classes.dex", hits.single().location)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.SdkInventoryDetectorTest"`
Expected: FAIL（`SdkInventoryDetector` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/detector/SdkInventoryDetector.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.Severity

/**
 * 按 type_ids（定义的类型）的包前缀判定集成了哪些 SDK。同一 SDK 只出一条，
 * 附首次命中的 dex 名。SDK 清单本身是 INFO（提示「须在隐私政策声明」），不是违规。
 */
class SdkInventoryDetector : Detector {
    override val name = "第三方SDK清单"

    override fun detect(ctx: ScanContext): List<Finding> {
        // 收集所有定义类型一次，避免对每个 SDK 重扫序列。
        val types = ctx.dexIndex.typeDescriptors().toList()
        val out = ArrayList<Finding>()
        for (sdk in ctx.rules.sdks) {
            val hit = types.firstOrNull { located ->
                sdk.packages.any { pkg -> located.value.startsWith("L$pkg") }
            } ?: continue
            val note = sdk.note?.let { "；$it" } ?: ""
            val privacy = sdk.privacy?.let { "\n隐私政策：$it" } ?: ""
            out += Finding(
                category = "第三方SDK · ${sdk.category}",
                severity = Severity.INFO,
                title = sdk.name,
                detail = "检测到集成 ${sdk.name}（${sdk.vendor}）$note",
                location = hit.dex,
                advice = "确认已在应用隐私政策中列明该 SDK 的收集行为与用途$privacy",
                sourceRuleId = "sdk:${sdk.id}",
            )
        }
        return out
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.SdkInventoryDetectorTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/detector/SdkInventoryDetector.kt src/test/kotlin/com/apkharden/packager/scanner/detector/SdkInventoryDetectorTest.kt
git commit -m "feat(scanner): third-party SDK inventory detector"
```

---

## Task 9: SensitiveApiDetector

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/detector/SensitiveApiDetector.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/detector/SensitiveApiDetectorTest.kt`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/scanner/detector/SensitiveApiDetectorTest.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SensitiveApiDetectorTest {
    // sample.dex 的 track() 调用了 helper()，故含方法引用 Lcom/example/sdkdemo/TrackingSdk;->helper
    private val rules = RuleSet.fromJson(
        sdk = """{"sdks":[]}""",
        api = """{"apis":[
            {"id":"demo-api","title":"演示敏感调用","category":"设备标识","severity":"HIGH",
             "methods":["Lcom/example/sdkdemo/TrackingSdk;->helper"],"advice":"核实调用时机"}
        ]}""",
        perm = """{"permissions":[]}""", compliance = """{"checks":[]}""",
    )

    private fun ctx(): ScanContext {
        val dex = File("src/test/resources/sample.dex").readBytes()
        val info = ManifestInfo("com.x", "1.0", 31, emptyList(), false, false)
        return ScanContext("x.apk", info, DexIndex(listOf("classes2.dex" to dex)), emptyList(), rules)
    }

    @Test
    fun `detects referenced sensitive method once, located to dex`() {
        val f = SensitiveApiDetector().detect(ctx())
        val hit = f.single { it.sourceRuleId == "api:demo-api" }
        assertEquals(Severity.HIGH, hit.severity)
        assertEquals("classes2.dex", hit.location)
        assertTrue(hit.detail.contains("helper"))
    }

    @Test
    fun `no false positive when method not referenced`() {
        val r = RuleSet.fromJson(
            sdk = """{"sdks":[]}""",
            api = """{"apis":[{"id":"x","title":"t","category":"c","severity":"HIGH","methods":["Landroid/foo/Bar;->baz"],"advice":"a"}]}""",
            perm = """{"permissions":[]}""", compliance = """{"checks":[]}""",
        )
        val dex = File("src/test/resources/sample.dex").readBytes()
        val info = ManifestInfo("com.x", "1.0", 31, emptyList(), false, false)
        val c = ScanContext("x.apk", info, DexIndex(listOf("classes.dex" to dex)), emptyList(), r)
        assertTrue(SensitiveApiDetector().detect(c).isEmpty())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.SensitiveApiDetectorTest"`
Expected: FAIL（`SensitiveApiDetector` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/detector/SensitiveApiDetector.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.scanner.model.Finding

/**
 * 在 method_ids（被引用的方法）里匹配敏感 API。命中只证明「代码引用了该方法」，不证明运行时
 * 一定执行 / 在同意前执行——故措辞为「检测到调用点，请核实调用时机」，不写「违规」。
 * 同一规则去重，记录首次命中的 dex。
 */
class SensitiveApiDetector : Detector {
    override val name = "敏感API调用点"

    override fun detect(ctx: ScanContext): List<Finding> {
        // 把规则方法签名建索引：签名 -> 规则。一次遍历 method refs 命中即可。
        val ruleByMethod = HashMap<String, com.apkharden.packager.scanner.ApiRule>()
        for (api in ctx.rules.apis) for (mth in api.methods) ruleByMethod[mth] = api

        val firstHitDex = HashMap<String, String>()   // ruleId -> dex
        for (ref in ctx.dexIndex.methodRefs()) {
            val rule = ruleByMethod[ref.value] ?: continue
            firstHitDex.putIfAbsent(rule.id, ref.dex)
        }

        return ctx.rules.apis.filter { firstHitDex.containsKey(it.id) }.map { api ->
            Finding(
                category = api.category,
                severity = api.severity,
                title = api.title,
                detail = "检测到调用点 ${api.methods.first()}（请核实调用时机，确认在用户同意后）",
                location = firstHitDex[api.id],
                advice = api.advice,
                sourceRuleId = "api:${api.id}",
            )
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.SensitiveApiDetectorTest"`
Expected: PASS（两个用例：命中 + 无误报）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/detector/SensitiveApiDetector.kt src/test/kotlin/com/apkharden/packager/scanner/detector/SensitiveApiDetectorTest.kt
git commit -m "feat(scanner): sensitive API call-site detector"
```

---

## Task 10: ComplianceFileDetector

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/detector/ComplianceFileDetector.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/detector/ComplianceFileDetectorTest.kt`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/scanner/detector/ComplianceFileDetectorTest.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.ManifestInfo
import com.apkharden.packager.scanner.RuleSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComplianceFileDetectorTest {
    private val rules = RuleSet.fromJson(
        sdk = """{"sdks":[]}""", api = """{"apis":[]}""", perm = """{"permissions":[]}""",
        compliance = """{"checks":[
            {"id":"sdk31","type":"TARGET_SDK_MIN","value":31,"severity":"MEDIUM","advice":"升级 targetSdk"},
            {"id":"dbg","type":"DEBUGGABLE_FALSE","severity":"HIGH","advice":"关闭 debuggable"},
            {"id":"bak","type":"ALLOW_BACKUP_FALSE","severity":"LOW","advice":"关闭备份"}
        ]}""",
    )

    private fun ctx(targetSdk: Int?, debuggable: Boolean?, allowBackup: Boolean?): ScanContext {
        val info = ManifestInfo("com.x", "1.0", targetSdk, emptyList(), debuggable, allowBackup)
        return ScanContext("x.apk", info, DexIndex(emptyList()), emptyList(), rules)
    }

    @Test
    fun `flags low targetSdk, debuggable and allowBackup`() {
        val f = ComplianceFileDetector().detect(ctx(targetSdk = 28, debuggable = true, allowBackup = true))
        assertTrue(f.any { it.sourceRuleId == "compliance:sdk31" })
        assertTrue(f.any { it.sourceRuleId == "compliance:dbg" })
        assertTrue(f.any { it.sourceRuleId == "compliance:bak" })
    }

    @Test
    fun `clean manifest yields no compliance findings`() {
        val f = ComplianceFileDetector().detect(ctx(targetSdk = 34, debuggable = false, allowBackup = false))
        assertTrue(f.isEmpty())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.ComplianceFileDetectorTest"`
Expected: FAIL（`ComplianceFileDetector` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/detector/ComplianceFileDetector.kt`:

```kotlin
package com.apkharden.packager.scanner.detector

import com.apkharden.packager.scanner.model.Finding

/** manifest 安全位/配置体检：targetSdk 下限、debuggable、allowBackup。 */
class ComplianceFileDetector : Detector {
    override val name = "合规配置检查"

    override fun detect(ctx: ScanContext): List<Finding> {
        val info = ctx.manifest
        val out = ArrayList<Finding>()
        for (c in ctx.rules.checks) {
            val triggered: Pair<String, String>? = when (c.type) {
                "TARGET_SDK_MIN" -> {
                    val min = c.value ?: continue
                    val t = info.targetSdk
                    if (t != null && t < min) "targetSdk=$t（要求≥$min）" to "AndroidManifest.xml" else null
                }
                "DEBUGGABLE_FALSE" ->
                    if (info.debuggable == true) "android:debuggable=true" to "AndroidManifest.xml" else null
                "ALLOW_BACKUP_FALSE" ->
                    if (info.allowBackup == true) "android:allowBackup=true" to "AndroidManifest.xml" else null
                else -> null
            }
            if (triggered != null) {
                out += Finding("合规配置", c.severity, c.id.replaceFirstChar { it.uppercase() },
                    triggered.first, triggered.second, c.advice, "compliance:${c.id}")
            }
        }
        return out
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.detector.ComplianceFileDetectorTest"`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/detector/ComplianceFileDetector.kt src/test/kotlin/com/apkharden/packager/scanner/detector/ComplianceFileDetectorTest.kt
git commit -m "feat(scanner): manifest compliance config detector"
```

---

## Task 11: PrivacyScanner 编排 + 错误隔离

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/PrivacyScanner.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/PrivacyScannerTest.kt`

- [ ] **Step 1: 写失败测试（端到端 + 错误隔离 + 干净基线）**

`src/test/kotlin/com/apkharden/packager/scanner/PrivacyScannerTest.kt`:

```kotlin
package com.apkharden.packager.scanner

import com.apkharden.packager.scanner.model.Severity
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PrivacyScannerTest {
    @TempDir lateinit var tmp: File

    private fun apk(perms: List<String>, debuggable: Boolean): File {
        val manifest = AndroidManifestBlock().apply {
            packageName = "com.example.demo"; versionName = "1.0"
            perms.forEach { addUsesPermission(it) }
            if (debuggable) applicationElement?.getOrCreateAndroidAttribute("debuggable", 0x0101000f)
                ?.valueAsBoolean = true
            refreshFull()
        }.bytes
        val dex = File("src/test/resources/sample.dex").readBytes()
        val f = File(tmp, "app.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(dex); z.closeEntry()
        }
        return f
    }

    private val testRules = RuleSet.fromJson(
        sdk = """{"sdks":[{"id":"demo","name":"演示SDK","category":"统计分析","packages":["com/example/sdkdemo/"]}]}""",
        api = """{"apis":[{"id":"demo-api","title":"演示调用","category":"设备标识","severity":"HIGH","methods":["Lcom/example/sdkdemo/TrackingSdk;->helper"],"advice":"a"}]}""",
        perm = """{"permissions":[{"name":"android.permission.READ_PHONE_STATE","title":"读取电话状态","category":"设备标识","severity":"HIGH","advice":"a"}]}""",
        compliance = """{"checks":[{"id":"dbg","type":"DEBUGGABLE_FALSE","severity":"HIGH","advice":"a"}]}""",
    )

    @Test
    fun `scans across all four detectors`() {
        val logs = mutableListOf<String>()
        val report = PrivacyScanner.scan(apk(listOf("android.permission.READ_PHONE_STATE"), debuggable = true),
            rules = testRules, log = { logs.add(it) })
        assertEquals("com.example.demo", report.packageName)
        assertTrue(report.findings.any { it.sourceRuleId == "perm:android.permission.READ_PHONE_STATE" })
        assertTrue(report.findings.any { it.sourceRuleId == "sdk:demo" })
        assertTrue(report.findings.any { it.sourceRuleId == "api:demo-api" })
        assertTrue(report.findings.any { it.sourceRuleId == "compliance:dbg" })
        assertTrue((report.summary[Severity.HIGH] ?: 0) >= 3)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `a throwing detector is isolated, others still produce findings`() {
        val boom = object : com.apkharden.packager.scanner.detector.Detector {
            override val name = "炸弹"
            override fun detect(ctx: com.apkharden.packager.scanner.detector.ScanContext) = throw RuntimeException("boom")
        }
        val report = PrivacyScanner.scanWith(
            apk(listOf("android.permission.READ_PHONE_STATE"), debuggable = false),
            rules = testRules,
            detectors = listOf(boom, com.apkharden.packager.scanner.detector.PermissionDetector()),
            log = {},
        )
        // 炸弹被隔离成一条 INFO，权限检测照常出结果
        assertTrue(report.findings.any { it.severity == Severity.INFO && it.title.contains("未完成") })
        assertTrue(report.findings.any { it.sourceRuleId == "perm:android.permission.READ_PHONE_STATE" })
    }

    @Test
    fun `bundled rules do not false-positive on a trivial apk`() {
        // 用真实内置规则扫一个只含 demo 包的简单 APK：不应报任何 HIGH
        val report = PrivacyScanner.scan(apk(emptyList(), debuggable = false),
            rules = RuleSet.bundled(), log = {})
        assertEquals(0, report.summary[Severity.HIGH] ?: 0, "干净包不应有高危误报")
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.PrivacyScannerTest"`
Expected: FAIL（`PrivacyScanner` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/PrivacyScanner.kt`:

```kotlin
package com.apkharden.packager.scanner

import com.apkharden.packager.core.ApkReader
import com.apkharden.packager.dex.DexIndex
import com.apkharden.packager.scanner.detector.*
import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import java.io.File

object PrivacyScanner {

    private fun defaultDetectors(): List<Detector> = listOf(
        PermissionDetector(), SdkInventoryDetector(), SensitiveApiDetector(), ComplianceFileDetector(),
    )

    fun scan(apk: File, rules: RuleSet = RuleSet.bundled(), log: (String) -> Unit = {}): ScanReport =
        scanWith(apk, rules, defaultDetectors(), log)

    /** 只读分析：单个检测器抛异常被隔离成一条 INFO，绝不让整体扫描失败。 */
    fun scanWith(apk: File, rules: RuleSet, detectors: List<Detector>, log: (String) -> Unit): ScanReport {
        require(apk.exists()) { "APK 不存在：$apk" }
        log("读取 APK…")
        val (manifestBytes, dexes, entryNames) = ApkReader(apk).use { r ->
            val names = r.dexNames()
            require(names.isNotEmpty()) { "APK 中没有 classes.dex" }
            Triple(r.manifestBytes(), names.map { it to r.read(it) }, r.entryNames())
        }

        log("解析 manifest…")
        val manifest = ManifestReader.parse(manifestBytes)
        log("索引 ${dexes.size} 个 dex…")
        val ctx = ScanContext(apk.name, manifest, DexIndex(dexes), entryNames, rules)

        val findings = ArrayList<Finding>()
        for (d in detectors) {
            log("检测：${d.name}…")
            try {
                findings += d.detect(ctx)
            } catch (t: Throwable) {
                findings += Finding("扫描诊断", Severity.INFO, "${d.name} 未完成",
                    "该检测器执行出错：${t.message}", null,
                    "其余结果不受影响；如需排查可反馈此包。", "diag:${d.name}")
            }
        }
        log("汇总 ${findings.size} 项…")
        return ScanReport.of(apk.name, manifest.packageName, manifest.versionName, findings)
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.PrivacyScannerTest"`
Expected: PASS（三个用例：四检测器、错误隔离、干净基线）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/PrivacyScanner.kt src/test/kotlin/com/apkharden/packager/scanner/PrivacyScannerTest.kt
git commit -m "feat(scanner): PrivacyScanner orchestration with per-detector error isolation"
```

---

## Task 12: ReportExporter（Markdown + HTML）

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/scanner/report/ReportExporter.kt`
- Test: `src/test/kotlin/com/apkharden/packager/scanner/report/ReportExporterTest.kt`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/apkharden/packager/scanner/report/ReportExporterTest.kt`:

```kotlin
package com.apkharden.packager.scanner.report

import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReportExporterTest {
    private fun report() = ScanReport.of(
        "app.apk", "com.example.demo", "1.0",
        listOf(
            Finding("设备标识", Severity.HIGH, "读取 IMEI", "检测到调用点 getImei", "classes.dex", "改用 OAID", "api:imei"),
            Finding("第三方SDK · 统计分析", Severity.INFO, "友盟统计", "检测到集成 友盟统计", "classes.dex", "声明", "sdk:umeng"),
        ),
    )

    @Test
    fun `markdown contains title, finding and disclaimer`() {
        val md = ReportExporter.toMarkdown(report())
        assertTrue(md.contains("com.example.demo"))
        assertTrue(md.contains("读取 IMEI"))
        assertTrue(md.contains("改用 OAID"))
        assertTrue(md.contains("静态自查"))   // 免责声明
    }

    @Test
    fun `html is self-contained with no external links`() {
        val html = ReportExporter.toHtml(report())
        assertTrue(html.trimStart().startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("读取 IMEI"))
        assertTrue(html.contains("<style>"))            // 内联 CSS
        assertFalse(html.contains("http://"))           // 无外链
        assertFalse(html.contains("https://"))
        assertFalse(html.contains("<script"))           // 无脚本
    }

    @Test
    fun `html escapes special characters`() {
        val r = ScanReport.of("a.apk", "p", "1",
            listOf(Finding("c", Severity.LOW, "<b>x</b>", "a & b", null, "y", "r")))
        val html = ReportExporter.toHtml(r)
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"))
        assertTrue(html.contains("a &amp; b"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.report.ReportExporterTest"`
Expected: FAIL（`ReportExporter` 未定义）。

- [ ] **Step 3: 写实现**

`src/main/kotlin/com/apkharden/packager/scanner/report/ReportExporter.kt`:

```kotlin
package com.apkharden.packager.scanner.report

import com.apkharden.packager.scanner.model.Finding
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity

object ReportExporter {

    private const val DISCLAIMER =
        "本报告为静态自查，标注的是风险嫌疑项而非合规判定；调用时机/实际行为需结合动态验证。"

    private fun label(s: Severity) = when (s) {
        Severity.HIGH -> "高"; Severity.MEDIUM -> "中"; Severity.LOW -> "低"; Severity.INFO -> "提示"
    }

    fun toMarkdown(r: ScanReport): String = buildString {
        appendLine("# 隐私合规静态扫描报告")
        appendLine()
        appendLine("- APK：${r.apkName}")
        appendLine("- 包名：${r.packageName ?: "未知"}　版本：${r.versionName ?: "未知"}")
        append("- 概览：")
        appendLine(Severity.entries.mapNotNull { s -> r.summary[s]?.let { "${label(s)} $it" } }.joinToString("　"))
        appendLine()
        for ((category, items) in r.findings.groupBy { it.category }) {
            appendLine("## $category")
            appendLine()
            for (f in items) {
                appendLine("- **[${label(f.severity)}] ${f.title}**${f.location?.let { "　@$it" } ?: ""}")
                appendLine("  - ${f.detail}")
                appendLine("  - 建议：${f.advice}")
            }
            appendLine()
        }
        appendLine("---")
        appendLine("> $DISCLAIMER")
    }

    fun toHtml(r: ScanReport): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val color = mapOf(
            Severity.HIGH to "#d32f2f", Severity.MEDIUM to "#f57c00",
            Severity.LOW to "#fbc02d", Severity.INFO to "#607d8b",
        )
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>隐私合规扫描报告</title>")
            appendLine("<style>")
            appendLine("body{font-family:system-ui,'Microsoft YaHei',sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#222}")
            appendLine("h1{font-size:20px}h2{font-size:16px;border-bottom:1px solid #eee;padding-bottom:4px;margin-top:28px}")
            appendLine(".badge{display:inline-block;color:#fff;border-radius:4px;padding:1px 8px;font-size:12px;margin-right:6px}")
            appendLine(".item{margin:10px 0;padding:8px 12px;background:#fafafa;border-left:3px solid #ccc;border-radius:4px}")
            appendLine(".loc{color:#888;font-size:12px}.advice{color:#33691e;font-size:13px}.foot{color:#888;font-size:12px;margin-top:28px;border-top:1px solid #eee;padding-top:8px}")
            appendLine("</style></head><body>")
            appendLine("<h1>隐私合规静态扫描报告</h1>")
            appendLine("<p>APK：${esc(r.apkName)}<br>包名：${esc(r.packageName ?: "未知")}　版本：${esc(r.versionName ?: "未知")}</p>")
            append("<p>")
            for (s in Severity.entries) r.summary[s]?.let {
                append("<span class=\"badge\" style=\"background:${color[s]}\">${label(s)} $it</span>")
            }
            appendLine("</p>")
            for ((category, items) in r.findings.groupBy { it.category }) {
                appendLine("<h2>${esc(category)}</h2>")
                for (f in items) {
                    appendLine("<div class=\"item\">")
                    appendLine("<span class=\"badge\" style=\"background:${color[f.severity]}\">${label(f.severity)}</span>")
                    append("<b>${esc(f.title)}</b>")
                    f.location?.let { append(" <span class=\"loc\">@${esc(it)}</span>") }
                    appendLine("<div>${esc(f.detail)}</div>")
                    appendLine("<div class=\"advice\">建议：${esc(f.advice)}</div>")
                    appendLine("</div>")
                }
            }
            appendLine("<p class=\"foot\">${esc(DISCLAIMER)}</p>")
            appendLine("</body></html>")
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew test --tests "com.apkharden.packager.scanner.report.ReportExporterTest"`
Expected: PASS（注意：HTML 测试断言无 `http://`，故 CSS/文案里不得出现 URL；DISCLAIMER 与样式均无外链，满足）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/scanner/report/ReportExporter.kt src/test/kotlin/com/apkharden/packager/scanner/report/ReportExporterTest.kt
git commit -m "feat(scanner): markdown + self-contained html report exporter"
```

---

## Task 13: 抽出共享 FilePicker

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/ui/common/FilePicker.kt`
- Modify: `src/main/kotlin/com/apkharden/packager/ui/HardenScreen.kt`（删掉内嵌 `pick()`，改调共享版）

- [ ] **Step 1: 写共享 FilePicker（把 HardenScreen 里的 `pick()` 原样搬出来）**

`src/main/kotlin/com/apkharden/packager/ui/common/FilePicker.kt`:

```kotlin
package com.apkharden.packager.ui.common

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.nfd.NFDFilterItem
import org.lwjgl.util.nfd.NativeFileDialog.NFD_FreePath
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Init
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OpenDialog
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit
import org.lwjgl.util.nfd.NativeFileDialog.NFD_SaveDialog

/**
 * OS 原生文件对话框（Windows 上是现代 IFileOpenDialog，含快速访问栏），LWJGL NFD 驱动。
 * 加固与隐私扫描共用。返回所选路径，取消则 null。
 */
fun pickFile(
    filterName: String? = null,
    save: Boolean = false,
    extensions: List<String> = emptyList(),
): String? {
    NFD_Init()
    try {
        MemoryStack.stackPush().use { stack ->
            val outPath = stack.mallocPointer(1)
            val filters = if (extensions.isNotEmpty()) {
                val items = NFDFilterItem.malloc(1, stack)
                items[0].name(stack.UTF8(filterName ?: "支持的文件"))
                    .spec(stack.UTF8(extensions.joinToString(",")))
                items
            } else null
            val result = if (save)
                NFD_SaveDialog(outPath, filters, null as CharSequence?, null as CharSequence?)
            else
                NFD_OpenDialog(outPath, filters, null as CharSequence?)
            if (result != NFD_OKAY) return null
            val path = outPath.getStringUTF8(0)
            NFD_FreePath(outPath.get(0))
            return path
        }
    } finally {
        NFD_Quit()
    }
}
```

- [ ] **Step 2: 在 HardenScreen 里删掉内嵌 `pick()`，改用共享版**

修改 `src/main/kotlin/com/apkharden/packager/ui/HardenScreen.kt`：
1. 删掉文件内第 40-68 行的 `fun pick(...) { ... }` 局部函数定义。
2. 删掉随之不再使用的 LWJGL import（第 15-23 行那批 `org.lwjgl.*`）。
3. 顶部加：`import com.apkharden.packager.ui.common.pickFile`
4. 把 body 里三处调用 `pick(` 改成 `pickFile(`（共 4 处：输入 APK、输出 APK、Keystore，参数完全一致）。

- [ ] **Step 3: 验证编译**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL（无未解析引用、无未使用 import 报错）。

- [ ] **Step 4: 手动验证文件对话框仍工作**

Run: `./gradlew run`
手动：点「浏览」选输入 APK，确认弹出的是系统原生 Explorer 对话框、选完路径回填正确。关闭。
Expected: 文件选择行为与重构前一致。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/ui/common/FilePicker.kt src/main/kotlin/com/apkharden/packager/ui/HardenScreen.kt
git commit -m "refactor(ui): extract shared NFD file picker"
```

---

## Task 14: 工具注册表 + App 外壳 + HardenScreen 迁入 harden/

**Files:**
- Create: `src/main/kotlin/com/apkharden/packager/ui/tool/Tool.kt`
- Create: `src/main/kotlin/com/apkharden/packager/ui/App.kt`
- Move: `ui/HardenScreen.kt` → `ui/harden/HardenScreen.kt`（改包名）

- [ ] **Step 1: 迁移 HardenScreen 到 harden 子包**

把 `src/main/kotlin/com/apkharden/packager/ui/HardenScreen.kt` 移动到
`src/main/kotlin/com/apkharden/packager/ui/harden/HardenScreen.kt`，并把文件首行包声明改为：

```kotlin
package com.apkharden.packager.ui.harden
```

（`pickFile` 的 import 已是全路径 `com.apkharden.packager.ui.common.pickFile`，不受影响。）

- [ ] **Step 2: 写工具注册接口**

`src/main/kotlin/com/apkharden/packager/ui/tool/Tool.kt`:

```kotlin
package com.apkharden.packager.ui.tool

import androidx.compose.runtime.Composable

/** 一个工具箱条目。加新工具 = 实现一个 Tool 并加进 App 的列表，不碰外壳。 */
interface Tool {
    val id: String
    val title: String
    @Composable fun Content()
}
```

- [ ] **Step 3: 写 App 外壳（NavigationRail + 内容区）**

`src/main/kotlin/com/apkharden/packager/ui/App.kt`:

```kotlin
package com.apkharden.packager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkharden.packager.ui.harden.HardenScreen
import com.apkharden.packager.ui.scan.ScanScreen
import com.apkharden.packager.ui.tool.Tool

private val tools: List<Tool> = listOf(
    object : Tool {
        override val id = "harden"; override val title = "基础加固"
        @Composable override fun Content() = HardenScreen()
    },
    object : Tool {
        override val id = "scan"; override val title = "隐私扫描"
        @Composable override fun Content() = ScanScreen()
    },
)

@Composable
fun App() {
    var selected by remember { mutableStateOf(tools.first().id) }
    Row(Modifier.fillMaxSize()) {
        NavigationRail {
            Spacer(Modifier.height(8.dp))
            for (t in tools) {
                NavigationRailItem(
                    selected = selected == t.id,
                    onClick = { selected = t.id },
                    icon = {},
                    label = { Text(t.title) },
                    alwaysShowLabel = true,
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            tools.first { it.id == selected }.Content()
        }
    }
}
```

- [ ] **Step 4: 临时占位 ScanScreen 以便编译（Task 15 填真实现）**

为让本任务能独立编译/提交，先建最小占位
`src/main/kotlin/com/apkharden/packager/ui/scan/ScanScreen.kt`:

```kotlin
package com.apkharden.packager.ui.scan

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ScanScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) { Text("隐私扫描（建设中）") }
}
```

- [ ] **Step 5: 验证编译**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/ui
git rm src/main/kotlin/com/apkharden/packager/ui/HardenScreen.kt 2>/dev/null; true
git commit -m "feat(ui): toolbox shell with NavigationRail + tool registry"
```

---

## Task 15: ScanScreen（真实现）

**Files:**
- Modify: `src/main/kotlin/com/apkharden/packager/ui/scan/ScanScreen.kt`（替换占位）

- [ ] **Step 1: 写 ScanScreen**

整体替换 `src/main/kotlin/com/apkharden/packager/ui/scan/ScanScreen.kt`:

```kotlin
package com.apkharden.packager.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.apkharden.packager.scanner.PrivacyScanner
import com.apkharden.packager.scanner.model.ScanReport
import com.apkharden.packager.scanner.model.Severity
import com.apkharden.packager.scanner.report.ReportExporter
import com.apkharden.packager.ui.common.pickFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun sevLabel(s: Severity) = when (s) {
    Severity.HIGH -> "高"; Severity.MEDIUM -> "中"; Severity.LOW -> "低"; Severity.INFO -> "提示"
}
private fun sevColor(s: Severity) = when (s) {
    Severity.HIGH -> Color(0xFFD32F2F); Severity.MEDIUM -> Color(0xFFF57C00)
    Severity.LOW -> Color(0xFFFBC02D); Severity.INFO -> Color(0xFF607D8B)
}

@Composable
fun ScanScreen() {
    var inputApk by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ScanReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<Severity?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("隐私合规扫描", style = MaterialTheme.typography.h6, modifier = Modifier.padding(bottom = 8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(inputApk, { inputApk = it }, label = { Text("输入 APK") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { pickFile("APK 文件", extensions = listOf("apk"))?.let { inputApk = it } }) { Text("浏览") }
        }

        Button(
            enabled = !running && inputApk.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = {
                running = true; report = null; error = null; filter = null
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { PrivacyScanner.scan(File(inputApk)) }
                        report = r
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    } finally {
                        running = false
                    }
                }
            },
        ) { Text(if (running) "扫描中…" else "开始扫描") }

        error?.let {
            Text("❌ $it", color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 8.dp))
        }

        val r = report
        if (r != null) {
            Divider(Modifier.padding(vertical = 8.dp))
            // 概览计数条：点击按严重度筛选
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (s in Severity.entries) {
                    val c = r.summary[s] ?: continue
                    val on = filter == s
                    Surface(color = if (on) sevColor(s) else sevColor(s).copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable { filter = if (on) null else s }) {
                        Text("${sevLabel(s)} $c", color = if (on) Color.White else sevColor(s),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Text("${r.packageName ?: "未知"}　${r.versionName ?: ""}",
                style = MaterialTheme.typography.caption, modifier = Modifier.padding(top = 4.dp))

            val shown = r.findings.filter { filter == null || it.severity == filter }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                if (shown.isEmpty()) {
                    Text("未发现明显风险项。")
                } else {
                    for ((category, items) in shown.groupBy { it.category }) {
                        Text(category, style = MaterialTheme.typography.subtitle2,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        for (f in items) {
                            Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(color = sevColor(f.severity), shape = MaterialTheme.shapes.small) {
                                    Text(sevLabel(f.severity), color = Color.White,
                                        style = MaterialTheme.typography.caption,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                Column {
                                    Text(f.title + (f.location?.let { "　@$it" } ?: ""),
                                        style = MaterialTheme.typography.body2)
                                    Text(f.detail, style = MaterialTheme.typography.caption)
                                    Text("→ ${f.advice}", style = MaterialTheme.typography.caption,
                                        color = Color(0xFF33691E))
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    pickFile("Markdown", save = true, extensions = listOf("md"))?.let {
                        File(it).writeText(ReportExporter.toMarkdown(r))
                    }
                }) { Text("导出 Markdown") }
                Button(onClick = {
                    pickFile("HTML", save = true, extensions = listOf("html"))?.let {
                        File(it).writeText(ReportExporter.toHtml(r))
                    }
                }) { Text("导出 HTML") }
            }
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 手动验证扫描全流程**

Run: `./gradlew run`
手动：切到「隐私扫描」Tab → 选一个真实 APK → 点「开始扫描」→ 确认出分类清单、概览计数条可点筛选 → 点「导出 HTML」存盘，双击打开确认报告正常显示。
Expected: 扫描出结果、筛选生效、导出的 HTML 能在浏览器打开。

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/ui/scan/ScanScreen.kt
git commit -m "feat(ui): privacy scan screen with grouped findings + report export"
```

---

## Task 16: 接线 Main → App，全量验证

**Files:**
- Modify: `src/main/kotlin/com/apkharden/packager/Main.kt:8,20`

- [ ] **Step 1: 改 Main 渲染 App()**

修改 `src/main/kotlin/com/apkharden/packager/Main.kt`：
1. import 改为：`import com.apkharden.packager.ui.App`（删掉 `HardenScreen` import）。
2. 窗口体内 `MaterialTheme { HardenScreen() }` 改为 `MaterialTheme { App() }`。
3. 窗口默认尺寸略放宽以容纳导航栏：`rememberWindowState(width = 820.dp, height = 660.dp)`。

- [ ] **Step 2: 跑全部测试**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL，所有测试通过（含原加固测试 + 新扫描测试）。

- [ ] **Step 3: 手动冒烟两个工具**

Run: `./gradlew run`
手动：
- 「基础加固」Tab：选输入/输出/keystore，确认 UI 与重构前一致（不必真跑加固）。
- 「隐私扫描」Tab：扫一个 APK，确认出报告。
Expected: 两个工具都能正常进入与操作，导航切换正常。

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/apkharden/packager/Main.kt
git commit -m "feat(ui): mount toolbox App shell as window root"
```

- [ ] **Step 5: 更新 README（工具箱 + 隐私扫描一节）**

在 `README.md` 顶部功能列表后补一节，说明新增「隐私合规扫描」工具：纯静态、查权限/SDK/敏感API/合规配置、导出 HTML/Markdown、定位为上架前自查（非合规判定）。提交：

```bash
git add README.md
git commit -m "docs: document privacy compliance scanner tool"
```

---

## 收尾说明

- 全部完成后，建议照仓库习惯更新内存：把「ApkHarden 已含隐私扫描工具箱模块」记入项目记忆，并链接本计划与 spec。
- 规则库（`src/main/resources/rules/*.json`）是起步集，后续按需扩充 SDK / 权限 / API 条目即可，无需改代码。
- 已知后续可扩点（本期不做）：JSON 导出、外部规则覆盖、CLI 入口、usesCleartextTraffic 检查（需确认其 framework 资源 ID）、`<queries>` 与隐私政策页线索检测。
