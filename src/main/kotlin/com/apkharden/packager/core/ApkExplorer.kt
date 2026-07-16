package com.apkharden.packager.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.File
import java.util.zip.ZipFile

internal enum class ManifestRisk { HIGH, REVIEW, NORMAL }

internal data class ManifestComponent(
    val type: String,
    val name: String,
    val process: String,
    val exported: Boolean,
    val permission: String,
    val authorities: String,
    val deepLinks: List<String>,
    val risk: ManifestRisk,
    val riskReason: String,
)

internal data class ManifestReport(
    val packageName: String,
    val application: String,
    val mainActivity: String,
    val permissions: List<String>,
    val components: List<ManifestComponent>,
) {
    val processes: Set<String> get() = components.map { it.process.ifBlank { "主进程" } }.toSortedSet()
    val deepLinks: List<String> get() = components.flatMap { it.deepLinks }.distinct().sorted()
    val riskCount: Int get() = components.count { it.risk != ManifestRisk.NORMAL }
}

internal object ManifestExplorer {
    private const val ID_EXPORTED = 0x01010010
    private const val ID_PROCESS = 0x01010011
    private const val ID_PERMISSION = 0x01010006
    private const val ID_AUTHORITIES = 0x01010018
    private const val ID_SCHEME = 0x01010027
    private const val ID_HOST = 0x01010028
    private const val ID_PORT = 0x01010029
    private const val ID_PATH = 0x0101002a
    private const val ID_PATH_PREFIX = 0x0101002b
    private const val ID_PATH_PATTERN = 0x0101002c
    private val tags = listOf("activity", "activity-alias", "service", "receiver", "provider")

    fun analyze(apk: File): ManifestReport = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml")
            ?: throw IllegalArgumentException("APK 中不存在 AndroidManifest.xml")
        val manifest = zip.getInputStream(entry).use(AndroidManifestBlock::load)
        val components = tags.flatMap { tag ->
            manifest.listApplicationElementsByTag(tag).map { element -> component(tag, element) }
        }
        ManifestReport(
            packageName = manifest.packageName.orEmpty(),
            application = manifest.applicationClassName.orEmpty(),
            mainActivity = manifest.mainActivityClassName.orEmpty(),
            permissions = manifest.usesPermissions.sorted(),
            components = components.sortedWith(compareBy<ManifestComponent> { it.type }.thenBy { it.name }),
        )
    }

    private fun component(type: String, element: ResXmlElement): ManifestComponent {
        val name = AndroidManifestBlock.getAndroidNameValue(element).orEmpty()
        val exported = element.searchAttributeByResourceId(ID_EXPORTED)?.valueAsBoolean == true
        val permission = element.searchAttributeByResourceId(ID_PERMISSION)?.valueAsString.orEmpty()
        val authorities = element.searchAttributeByResourceId(ID_AUTHORITIES)?.valueAsString.orEmpty()
        val deepLinks = element.getElements("intent-filter").asSequence().flatMap { filter ->
            filter.getElements("data").asSequence().mapNotNull(::deepLink)
        }.distinct().toList()
        val (risk, reason) = when {
            type == "provider" && exported && permission.isBlank() -> ManifestRisk.HIGH to "Provider 对外暴露且未声明访问权限"
            exported && permission.isBlank() -> ManifestRisk.REVIEW to "组件对外暴露，需确认 Intent 输入校验"
            deepLinks.any { it.startsWith("http://") } -> ManifestRisk.REVIEW to "包含 HTTP 明文 Deep Link"
            else -> ManifestRisk.NORMAL to ""
        }
        return ManifestComponent(
            type = type,
            name = name,
            process = element.searchAttributeByResourceId(ID_PROCESS)?.valueAsString.orEmpty(),
            exported = exported,
            permission = permission,
            authorities = authorities,
            deepLinks = deepLinks,
            risk = risk,
            riskReason = reason,
        )
    }

    private fun deepLink(data: ResXmlElement): String? {
        val scheme = data.searchAttributeByResourceId(ID_SCHEME)?.valueAsString.orEmpty()
        val host = data.searchAttributeByResourceId(ID_HOST)?.valueAsString.orEmpty()
        val port = data.searchAttributeByResourceId(ID_PORT)?.valueAsString.orEmpty()
        val path = data.searchAttributeByResourceId(ID_PATH)?.valueAsString
            ?: data.searchAttributeByResourceId(ID_PATH_PREFIX)?.valueAsString
            ?: data.searchAttributeByResourceId(ID_PATH_PATTERN)?.valueAsString
            ?: ""
        if (scheme.isBlank() && host.isBlank() && path.isBlank()) return null
        return buildString {
            append(if (scheme.isBlank()) "*" else scheme).append("://")
            append(if (host.isBlank()) "*" else host)
            if (port.isNotBlank()) append(':').append(port)
            append(path)
        }
    }
}

internal enum class ApkEntryCategory(val label: String) {
    DEX("DEX"), NATIVE("Native SO"), RESOURCE("资源"), ASSET("Assets"), MANIFEST("Manifest"), SIGNATURE("签名"), OTHER("其他")
}

internal data class ApkArchiveEntry(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val category: ApkEntryCategory,
)

internal data class ApkArchiveReport(
    val file: File,
    val entries: List<ApkArchiveEntry>,
) {
    val totalSize: Long get() = entries.sumOf { it.size }
    val categorySizes: Map<ApkEntryCategory, Long> get() = ApkEntryCategory.entries.associateWith { category ->
        entries.filter { it.category == category }.sumOf { it.size }
    }.filterValues { it > 0 }
    val directorySizes: Map<String, Long> get() = entries.groupBy { entry ->
        val parts = entry.name.split('/')
        when {
            parts.size == 1 -> "根目录"
            parts[0] == "lib" && parts.size > 2 -> "lib/${parts[1]}"
            parts[0] == "res" && parts.size > 2 -> "res/${parts[1]}"
            else -> parts[0]
        }
    }.mapValues { (_, values) -> values.sumOf { it.size } }.toList()
        .sortedByDescending { it.second }.toMap()
}

internal data class ApkSizeChange(
    val name: String,
    val category: ApkEntryCategory,
    val oldSize: Long?,
    val newSize: Long?,
) {
    val delta: Long get() = (newSize ?: 0) - (oldSize ?: 0)
}

internal data class ApkSizeComparison(
    val oldReport: ApkArchiveReport,
    val newReport: ApkArchiveReport,
    val changes: List<ApkSizeChange>,
) {
    val totalDelta: Long get() = newReport.file.length() - oldReport.file.length()
    val categoryDelta: Map<ApkEntryCategory, Long> get() = ApkEntryCategory.entries.associateWith { category ->
        (newReport.categorySizes[category] ?: 0) - (oldReport.categorySizes[category] ?: 0)
    }.filterValues { it != 0L }
}

internal object ApkArchiveExplorer {
    fun analyze(apk: File): ApkArchiveReport {
        require(apk.isFile) { "APK 文件不存在" }
        val entries = ZipFile(apk).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.map { entry ->
                ApkArchiveEntry(entry.name, entry.size.coerceAtLeast(0), entry.compressedSize.coerceAtLeast(0), category(entry.name))
            }.sortedByDescending { it.size }.toList()
        }
        return ApkArchiveReport(apk, entries)
    }

    fun compare(oldApk: File, newApk: File): ApkSizeComparison {
        val oldReport = analyze(oldApk)
        val newReport = analyze(newApk)
        val old = oldReport.entries.associateBy { it.name }
        val new = newReport.entries.associateBy { it.name }
        val changes = (old.keys + new.keys).distinct().mapNotNull { name ->
            val before = old[name]
            val after = new[name]
            if (before?.size == after?.size) null else ApkSizeChange(
                name, after?.category ?: before!!.category, before?.size, after?.size,
            )
        }.sortedByDescending { kotlin.math.abs(it.delta) }
        return ApkSizeComparison(oldReport, newReport, changes)
    }

    private fun category(name: String): ApkEntryCategory = when {
        Regex("classes\\d*\\.dex").matches(name) -> ApkEntryCategory.DEX
        name.startsWith("lib/") && name.endsWith(".so") -> ApkEntryCategory.NATIVE
        name == "AndroidManifest.xml" -> ApkEntryCategory.MANIFEST
        name.startsWith("META-INF/") -> ApkEntryCategory.SIGNATURE
        name.startsWith("assets/") -> ApkEntryCategory.ASSET
        name.startsWith("res/") || name == "resources.arsc" -> ApkEntryCategory.RESOURCE
        else -> ApkEntryCategory.OTHER
    }
}
