import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}
repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// LWJGL's NFD binding wraps the OS-native file dialog (IFileOpenDialog on Windows), giving the
// real resizable Explorer dialog with the Quick Access sidebar instead of Swing/AWT's tiny ones.
val lwjglVersion = "3.3.3"
val lwjglNatives = System.getProperty("os.name").lowercase().let { os ->
    when {
        os.contains("win") -> "natives-windows"
        os.contains("mac") -> "natives-macos"
        else -> "natives-linux"
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.android.tools.build:apksig:8.3.2")
    implementation("io.github.reandroid:ARSCLib:1.3.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("net.java.dev.jna:jna:5.6.0")
    implementation("net.java.dev.jna:jna-platform:5.6.0")
    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-nfd:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-nfd:$lwjglVersion:$lwjglNatives")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }

// Headless harden for CI / scripting:
//   ./gradlew harden --args="--input in.apk --output out.apk --keystore k.jks \
//       --storePass p --alias a --keyPass p"
tasks.register<JavaExec>("harden") {
    group = "apkharden"
    description = "Run the harden pipeline headlessly (see CliKt)."
    mainClass.set("com.apkharden.packager.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// One-shot: rebuild the native app image and mirror it to the deployed copy the desktop
// shortcut points at (~/ApkHarden). Sync deletes stale files so the deploy stays clean.
//   ./gradlew deployToDesktop      (close the running app first, or the .exe stays locked)
tasks.register<Sync>("deployToDesktop") {
    group = "apkharden"
    description = "Build the distributable and mirror it to the deployed ~/ApkHarden copy."
    dependsOn("createDistributable")
    val deployDir = File(System.getProperty("user.home"), "ApkHarden")
    // jpackage marks the launcher .exe read-only; clear it so the mirror can overwrite in place.
    doFirst { deployDir.walkTopDown().forEach { it.setWritable(true) } }
    from(layout.buildDirectory.dir("compose/binaries/main/app/ApkHarden"))
    into(deployDir)
}

compose.desktop {
    application {
        mainClass = "com.apkharden.packager.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "ApkHarden"
            packageVersion = "1.0.0"
            // LWJGL (NFD) needs sun.misc.Unsafe from jdk.unsupported; jlink drops it by default,
            // which crashes the native file dialog in the packaged app (works under `gradlew run`
            // because that uses the full JDK).
            modules("jdk.unsupported")
        }
    }
}
