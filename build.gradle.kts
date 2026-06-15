plugins {
    kotlin("jvm") version "2.1.0"
    application
    // Compose Desktop plugin is added in Task 9.
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation("com.android.tools.build:apksig:8.3.2")
    implementation("io.github.reandroid:ARSCLib:1.3.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }

application {
    mainClass.set("com.apkharden.packager.MainKt")
}
