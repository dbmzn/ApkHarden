pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("repo") }
    }
}

rootProject.name = "apk-harden-functional-fixture"
include(":app")
