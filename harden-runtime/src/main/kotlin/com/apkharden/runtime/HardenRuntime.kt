package com.apkharden.runtime

import android.app.Application
import android.os.Process
import kotlin.system.exitProcess

object HardenRuntime {
    private val installer = RuntimeInstaller()

    @JvmStatic
    fun install(application: Application, config: HardenConfig) {
        installer.install(application, config)
    }
}

internal class RuntimeInstaller(
    private val initializer: OneTimeInitializer = OneTimeInitializer(),
    private val antiDebugCheck: (Application) -> HardenFailure? = AntiDebug::detect,
    private val certificateCheck: (Application, HardenConfig) -> Boolean = { application, config ->
        runCatching { CertificateVerifier.verify(application, config) }.getOrDefault(false)
    },
    private val stringTableLoader: (Application, HardenConfig) -> HardenStringTable? = { application, _ ->
        GeneratedStringTableLoader.loadOrNull(
            classLoader = requireNotNull(application.javaClass.classLoader) {
                "Application class loader is unavailable"
            },
        )
    },
    private val failureRecorder: FailureRecorder = FailureRecorder(),
    private val terminator: ProcessTerminator = ProcessTerminator {
        Process.killProcess(Process.myPid())
        exitProcess(0)
    },
) {
    fun install(application: Application, config: HardenConfig) {
        initializer.runOnce {
            var failure = antiDebugCheck(application)
                ?: if (certificateCheck(application, config)) null
                else HardenFailure.CERTIFICATE_MISMATCH

            if (failure == null) {
                failure = runCatching {
                    stringTableLoader(application, config)?.let { table ->
                        HardenStrings.install(config, table)
                    }
                }.fold(
                    onSuccess = { null },
                    onFailure = { HardenFailure.STRING_TABLE_INVALID },
                )
            }

            if (failure != null) {
                failureRecorder.write(application, failure)
                HardenStrings.clear()
                terminator.terminate()
            }
        }
    }
}

internal fun interface ProcessTerminator {
    fun terminate(): Nothing
}
