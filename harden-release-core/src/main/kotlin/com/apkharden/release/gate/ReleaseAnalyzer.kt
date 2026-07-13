package com.apkharden.release.gate

import com.apkharden.release.apk.ApkIdentityReader
import com.apkharden.release.apk.ElfAlignmentInspector
import com.apkharden.release.apk.ZipAlignmentInspector
import com.apkharden.release.crypto.KeystoreReader
import com.apkharden.release.metadata.HardenMetadataReader
import com.apkharden.release.model.FindingLevel
import com.apkharden.release.model.ReleaseAssessment
import com.apkharden.release.model.ReleaseFinding
import com.apkharden.release.model.ReleaseRequest

object ReleaseAnalyzer {
    fun analyze(request: ReleaseRequest): ReleaseAssessment {
        val online = ApkIdentityReader.read(request.onlineApk)
        val candidate = ApkIdentityReader.read(request.candidateApk)
        val metadata = HardenMetadataReader.read(request.metadataFile)
        val keystore = KeystoreReader.load(request.keystore)
        val findings = ReleaseGateEvaluator.evaluate(
            online,
            candidate,
            metadata,
            keystore.identity,
        ).toMutableList()

        val nativeZipEntries = ZipAlignmentInspector.inspect(request.candidateApk)
        nativeZipEntries.filterNot { it.aligned16k }.forEach { entry ->
            findings += ReleaseFinding(
                code = "NATIVE_ZIP_NOT_16K_ALIGNED",
                level = FindingLevel.BLOCKER,
                message = "Uncompressed native library is not 16KB aligned",
                details = mapOf(
                    "entry" to entry.name,
                    "offset" to entry.dataOffset.toString(),
                ),
            )
        }
        if (candidate.extractNativeLibs == false) {
            nativeZipEntries.filter { it.compressionMethod != 0 }.forEach { entry ->
                findings += ReleaseFinding(
                    code = "NATIVE_LIB_COMPRESSED_WITHOUT_EXTRACTION",
                    level = FindingLevel.BLOCKER,
                    message = "Native library is compressed while extractNativeLibs is false",
                    details = mapOf("entry" to entry.name),
                )
            }
        }

        if (candidate.abis.isNotEmpty()) {
            try {
                ElfAlignmentInspector.inspectApk(request.candidateApk)
                    .filterValues { !it.compatible16k }
                    .forEach { (name, result) ->
                        findings += ReleaseFinding(
                            code = "ELF_NOT_16K_COMPATIBLE",
                            level = FindingLevel.BLOCKER,
                            message = "Native library LOAD segments are not 16KB compatible",
                            details = mapOf(
                                "entry" to name,
                                "alignments" to result.loadAlignments.joinToString(),
                            ),
                        )
                    }
            } catch (error: Exception) {
                findings += ReleaseFinding(
                    code = "ELF_INSPECTION_FAILED",
                    level = FindingLevel.BLOCKER,
                    message = error.message ?: "ELF inspection failed",
                )
            }
        }

        return ReleaseAssessment(
            findings = findings.sortedWith(
                compareBy<ReleaseFinding>({ it.level.ordinal }, { it.code })
            ),
            approvedFindingCodes = request.approvedFindingCodes,
            online = online,
            candidate = candidate,
            keystore = keystore.identity,
        )
    }
}
