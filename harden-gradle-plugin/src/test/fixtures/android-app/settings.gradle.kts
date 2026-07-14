pluginManagement {
    (
        System.getenv("APK_HARDEN_PLUGIN_BUILD")
            ?: gradle.startParameter.projectProperties["apkharden.pluginBuild"]
    )?.let { pluginBuild ->
        includeBuild(pluginBuild)
    }
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
        maven {
            url = uri(
                System.getenv("APK_HARDEN_RUNTIME_REPO")
                    ?: providers.gradleProperty("apkharden.runtimeRepo").getOrElse("repo"),
            )
        }
    }
}

rootProject.name = "apk-harden-functional-fixture"
include(":app")
if (
    System.getenv("APK_HARDEN_DEVICE_FIXTURES") == "true" ||
    providers.gradleProperty("apkharden.deviceFixtures").orNull == "true"
) {
    include(":defaultApp")
}
