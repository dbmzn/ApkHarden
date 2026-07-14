package com.apkharden.runtime

import android.app.Application
import android.content.Context

class HardenApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        HardenRuntime.install(
            this,
            GeneratedConfigLoader.load(
                classLoader = requireNotNull(javaClass.classLoader) {
                    "HardenApplication class loader is unavailable"
                },
            ),
        )
    }
}

internal object GeneratedConfigLoader {
    fun load(
        className: String = GENERATED_CONFIG_CLASS,
        classLoader: ClassLoader,
    ): HardenConfig {
        val configClass = Class.forName(className, true, classLoader)
        val instance = configClass.getField("INSTANCE").get(null)
        return instance as? HardenConfig
            ?: error("$className.INSTANCE is not a HardenConfig")
    }

    private const val GENERATED_CONFIG_CLASS = "com.apkharden.generated.HardenVariantConfig"
}
