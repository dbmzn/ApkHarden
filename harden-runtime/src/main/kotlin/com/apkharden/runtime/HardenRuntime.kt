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
    private val failureRecorder: FailureRecorder = FailureRecorder(),
    private val terminator: ProcessTerminator = ProcessTerminator {
        Process.killProcess(Process.myPid())
        exitProcess(0)
    },
) {
    fun install(application: Application, config: HardenConfig) {
        initializer.runOnce {
            val failure = antiDebugCheck(application)
                ?: if (certificateCheck(application, config)) null
                else HardenFailure.CERTIFICATE_MISMATCH

            if (failure != null) {
                failureRecorder.write(application, failure)
                terminator.terminate()
            }
        }
    }
}

internal fun interface ProcessTerminator {
    fun terminate(): Nothing
}
