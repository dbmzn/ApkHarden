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
