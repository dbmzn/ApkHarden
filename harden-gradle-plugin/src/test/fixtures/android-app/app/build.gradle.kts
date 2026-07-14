plugins {
    id("com.android.application")
    id("com.apkharden.production")
}

val smokeStoreFile = providers.gradleProperty("apkharden.storeFile").orNull
val smokeStorePassword = providers.gradleProperty("apkharden.storePassword").orNull
val smokeKeyAlias = providers.gradleProperty("apkharden.keyAlias").orNull
val smokeKeyPassword = providers.gradleProperty("apkharden.keyPassword").orNull

android {
    namespace = "com.example.fixture"
    compileSdk = 34

    signingConfigs {
        if (listOf(smokeStoreFile, smokeStorePassword, smokeKeyAlias, smokeKeyPassword).all { it != null }) {
            create("smoke") {
                storeFile = file(requireNotNull(smokeStoreFile))
                storePassword = smokeStorePassword
                keyAlias = smokeKeyAlias
                keyPassword = smokeKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.fixture"
        minSdk = 23
        targetSdk = 34
        versionCode = 100
        versionName = "1.0"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("product_32") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("product_64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("product_all") {
            dimension = "abi"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        create("develop") {
            initWith(getByName("debug"))
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("smoke")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

apkHarden {
    certificateSha256.set(
        providers.gradleProperty("apkharden.certificateSha256")
            .getOrElse("ab".repeat(32)),
    )
    excludedStrings.add("fixture-explicit-contract")
}
