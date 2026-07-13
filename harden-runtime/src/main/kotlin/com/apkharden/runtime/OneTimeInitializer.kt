package com.apkharden.runtime

import java.util.concurrent.atomic.AtomicBoolean

internal class OneTimeInitializer {
    private val completed = AtomicBoolean(false)

    fun runOnce(action: () -> Unit) {
        if (completed.get()) return
        synchronized(this) {
            if (completed.get()) return
            action()
            completed.set(true)
        }
    }
}
