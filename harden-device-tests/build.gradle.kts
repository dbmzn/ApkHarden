plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("com.apkharden.device.RuntimeSmokeRunnerKt")
}
