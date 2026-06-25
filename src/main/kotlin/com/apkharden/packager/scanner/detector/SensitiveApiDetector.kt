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
