import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.Sync

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

val scrcpyVersion = "3.3.4"
val scrcpyArchiveName = "scrcpy-win64-v$scrcpyVersion.zip"
val scrcpyDownloadUrl = "https://github.com/Genymobile/scrcpy/releases/download/v$scrcpyVersion/$scrcpyArchiveName"
val scrcpyArchiveSha256 = "D8A155B7C180B7CA4CDADD40712B8750B63F3AAB48CB5B8A2A39AC2D0D4C5D38"
val scrcpyArchive = providers.provider {
    File(gradle.gradleUserHomeDir, "caches/apkharden/$scrcpyArchiveName")
}
val scrcpyAppResourcesRoot = layout.buildDirectory.dir("app-resources")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02X".format(it) }
}

val downloadScrcpy by tasks.registering {
    onlyIf { System.getProperty("os.name").contains("Windows", ignoreCase = true) }
    doLast {
        val destination = scrcpyArchive.get()
        if (destination.isFile && sha256(destination) == scrcpyArchiveSha256) return@doLast
        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        temporary.delete()
        try {
            val connection = URI(scrcpyDownloadUrl).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                setRequestProperty("User-Agent", "ApkHarden-build")
            }
            connection.getInputStream().buffered().use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            }
            check(sha256(temporary) == scrcpyArchiveSha256) {
                "scrcpy $scrcpyVersion 下载文件的 SHA-256 校验失败"
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }
}

val prepareScrcpyResources by tasks.registering(Sync::class) {
    onlyIf { System.getProperty("os.name").contains("Windows", ignoreCase = true) }
    dependsOn(downloadScrcpy)
    from({ zipTree(scrcpyArchive.get()) }) {
        eachFile {
            relativePath = RelativePath(!isDirectory, *relativePath.segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    into(scrcpyAppResourcesRoot.map { it.dir("windows/scrcpy") })
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
    implementation("org.jcodec:jcodec-javase:0.2.5")
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
            appResourcesRootDir.set(scrcpyAppResourcesRoot)
            // LWJGL (NFD) needs sun.misc.Unsafe from jdk.unsupported; jlink drops it by default,
            // which crashes the native file dialog in the packaged app (works under `gradlew run`
            // because that uses the full JDK).
            modules("jdk.unsupported")
        }
    }
}

tasks.configureEach {
    if (name == "prepareAppResources") {
        dependsOn(prepareScrcpyResources)
    }
}
