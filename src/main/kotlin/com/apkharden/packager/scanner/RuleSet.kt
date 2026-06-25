package com.apkharden.packager.scanner

class RuleSet(
    val sdks: List<SdkRule>,
    val apis: List<ApiRule>,
    val permissions: List<PermissionRule>,
    val checks: List<ComplianceCheck>,
) {
    companion object {
        /** 从打包进资源的 /rules/ 下各 .json 加载。规则缺失/格式错即 fail-fast。 */
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
