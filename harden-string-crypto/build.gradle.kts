plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
