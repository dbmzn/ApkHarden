package com.apkharden.release.report

import com.apkharden.release.model.ReleaseAssessment
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ReleaseReportWriter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun write(assessment: ReleaseAssessment, output: File) {
        writeTextAtomically(output, json.encodeToString(assessment) + "\n")
    }
}
