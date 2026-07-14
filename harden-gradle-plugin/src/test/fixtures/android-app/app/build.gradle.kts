plugins {
    id("com.android.application")
    id("com.apkharden.production")
}

android {
    namespace = "com.example.fixture"
    compileSdk = 34

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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

apkHarden {
    certificateSha256.set("ab".repeat(32))
}
