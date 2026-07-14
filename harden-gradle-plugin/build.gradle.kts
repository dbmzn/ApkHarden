plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.5.1")
    implementation(project(":harden-release-core"))
    testImplementation(gradleTestKit())
    testImplementation("com.android.tools.build:gradle:8.5.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }

gradlePlugin {
    plugins {
        create("apkHardenProduction") {
            id = "com.apkharden.production"
            implementationClass = "com.apkharden.gradle.ApkHardenPlugin"
        }
    }
}
