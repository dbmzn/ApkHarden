pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "ApkHarden"
include(":harden-release-core")
include(":harden-device-tests")
