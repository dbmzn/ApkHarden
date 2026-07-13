package com.apkharden.runtime

class HardenConfig(
    val applicationId: String,
    val variantName: String,
    val buildId: String,
    certificateSha256: String,
) {
    val certificateSha256: String

    init {
        require(applicationId.isNotBlank()) { "applicationId is blank" }
        require(variantName.isNotBlank()) { "variantName is blank" }
        require(buildId.isNotBlank()) { "buildId is blank" }
        require(certificateSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "certificateSha256 must contain 64 hexadecimal characters"
        }
        this.certificateSha256 = certificateSha256.lowercase()
    }
}
