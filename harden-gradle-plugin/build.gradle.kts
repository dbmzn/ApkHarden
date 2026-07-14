plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

val functionalTestPluginClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.5.1")
    functionalTestPluginClasspath("com.android.tools.build:gradle:8.5.1")
    functionalTestPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    functionalTestPluginClasspath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.0")
    functionalTestPluginClasspath("org.jetbrains.kotlin:kotlin-serialization:2.1.0")
    implementation(project(":harden-release-core"))
    implementation(project(":harden-string-crypto"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-tree:9.6")
    testImplementation(gradleTestKit())
    testImplementation("com.android.tools.build:gradle:8.5.1")
    testImplementation("org.ow2.asm:asm-tree:9.6")
    testImplementation("org.ow2.asm:asm-util:9.6")
    testImplementation("com.android.tools.smali:smali-dexlib2:3.0.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(17) }
tasks.test {
    useJUnitPlatform()
    dependsOn(":harden-runtime:bundleReleaseAar", ":harden-string-crypto:jar")
    systemProperty(
        "apkharden.runtime.aar",
        project(":harden-runtime").layout.buildDirectory
            .file("outputs/aar/harden-runtime-release.aar")
            .get()
            .asFile
            .absolutePath,
    )
    systemProperty(
        "apkharden.string.crypto.jar",
        project(":harden-string-crypto").tasks.named("jar")
            .flatMap { task -> (task as org.gradle.jvm.tasks.Jar).archiveFile }
            .get()
            .asFile
            .absolutePath,
    )
}
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalTestPluginClasspath)
}

gradlePlugin {
    plugins {
        create("apkHardenProduction") {
            id = "com.apkharden.production"
            implementationClass = "com.apkharden.gradle.ApkHardenPlugin"
        }
    }
}
