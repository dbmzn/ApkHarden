import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.android.tools.build:apksig:8.3.2")
    implementation("io.github.reandroid:ARSCLib:1.3.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
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

compose.desktop {
    application {
        mainClass = "com.apkharden.packager.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "ApkHarden"
            packageVersion = "1.0.0"
        }
    }
}
