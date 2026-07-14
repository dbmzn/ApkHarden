package com.apkharden.gradle

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

enum class R8Policy {
    AUTO,
    IGNORE,
}

open class ApkHardenExtension @Inject constructor(objects: ObjectFactory) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val excludedVariants: SetProperty<String> =
        objects.setProperty(String::class.java).convention(emptySet())
    val r8Policy: Property<R8Policy> = objects.property(R8Policy::class.java).convention(R8Policy.AUTO)
    val protectedPackages: SetProperty<String> =
        objects.setProperty(String::class.java).convention(emptySet())
    val certificateSha256: Property<String> = objects.property(String::class.java)
}
