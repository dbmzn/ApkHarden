package com.apkharden.release.report

import com.apkharden.release.model.ReleaseAssessment
import java.io.File
import kotlinx.serialization.json.Json

object ReleaseReportReader {
    private val json = Json { ignoreUnknownKeys = false }

    fun read(file: File): ReleaseAssessment =
        json.decodeFromString(file.readText())
}
