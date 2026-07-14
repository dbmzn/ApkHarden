package com.apkharden.runtime

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

open class RuntimeProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val application = requireNotNull(context?.applicationContext as? Application)
        repeat(3) {
            installer.install(
                application,
                HardenConfig(
                    applicationId = application.packageName,
                    variantName = "androidTest",
                    buildId = "multi-process",
                    certificateSha256 = "0".repeat(64),
                ),
            )
        }
        return true
    }

    override fun call(method: String, argument: String?, extras: Bundle?): Bundle = Bundle().apply {
        putInt("installCount", installChecks.get())
        putString("process", Application.getProcessName())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        val installChecks = AtomicInteger()
        val installer = RuntimeInstaller(
            antiDebugCheck = {
                installChecks.incrementAndGet()
                null
            },
            certificateCheck = { _, _ -> true },
            failureRecorder = FailureRecorder(),
            terminator = ProcessTerminator { error("probe must not terminate") },
        )
    }
}

class WorkerRuntimeProbeProvider : RuntimeProbeProvider()
