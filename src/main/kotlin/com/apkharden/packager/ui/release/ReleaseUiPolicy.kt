package com.apkharden.packager.ui.release

import com.apkharden.release.model.ReleaseStatus

internal data class ReleaseFormValues(
    val onlineApk: String = "",
    val candidateApk: String = "",
    val keystore: String = "",
    val outputDirectory: String = "",
    val alias: String = "",
    val storePassword: String = "",
    val keyPassword: String = "",
) {
    fun canAnalyze(running: Boolean): Boolean =
        !running && commonInputsPresent() && passwordsPresent()

    fun canExport(status: ReleaseStatus?, running: Boolean): Boolean =
        !running &&
            commonInputsPresent() &&
            passwordsPresent() &&
            outputDirectory.isNotBlank() &&
            status != null &&
            status.ordinal >= ReleaseStatus.STATIC_VERIFIED.ordinal

    private fun commonInputsPresent(): Boolean =
        onlineApk.isNotBlank() &&
            candidateApk.isNotBlank() &&
            keystore.isNotBlank() &&
            alias.isNotBlank()

    private fun passwordsPresent(): Boolean =
        storePassword.isNotEmpty() && keyPassword.isNotEmpty()
}
