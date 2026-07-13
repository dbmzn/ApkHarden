package com.apkharden.release.report

import com.apkharden.release.model.ReleaseAssessment
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ReleaseReportWriter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun write(assessment: ReleaseAssessment, output: File) {
        val destination = output.absoluteFile
        val parent = destination.parentFile
        require(parent.exists() || parent.mkdirs()) {
            "Unable to create report directory: $parent"
        }
        val temporary = File.createTempFile("release-report-", ".json", parent)
        try {
            temporary.writeText(json.encodeToString(assessment))
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    REPLACE_EXISTING,
                    ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }
}
