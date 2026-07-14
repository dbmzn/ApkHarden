plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.apkharden.production")
}

val smokeStoreFile = providers.gradleProperty("apkharden.storeFile").orNull
val smokeStorePassword = providers.gradleProperty("apkharden.storePassword").orNull
val smokeKeyAlias = providers.gradleProperty("apkharden.keyAlias").orNull
val smokeKeyPassword = providers.gradleProperty("apkharden.keyPassword").orNull

android {
    namespace = "com.example.fixture"
    compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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

dependencies {
    implementation("androidx.compose.runtime:runtime:1.7.6")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

apkHarden {
    certificateSha256.set(
        providers.gradleProperty("apkharden.certificateSha256")
            .getOrElse("ab".repeat(32)),
    )
    excludedStrings.add("fixture-explicit-contract")
}
