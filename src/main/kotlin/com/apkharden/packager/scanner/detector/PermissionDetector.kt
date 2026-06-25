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
                    "未在规则库分类。若涉及个人信息，确认已声明用途并按最小必要原则申请。", "perm:unknown")
            }
        }
    }
}
