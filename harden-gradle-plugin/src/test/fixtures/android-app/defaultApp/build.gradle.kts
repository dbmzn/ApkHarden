plugins {
    id("com.android.application")
    id("com.apkharden.production")
}

val smokeStoreFile = providers.gradleProperty("apkharden.storeFile").orNull
val smokeStorePassword = providers.gradleProperty("apkharden.storePassword").orNull
val smokeKeyAlias = providers.gradleProperty("apkharden.keyAlias").orNull
val smokeKeyPassword = providers.gradleProperty("apkharden.keyPassword").orNull

android {
    namespace = "com.example.fixture.defaultapp"
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
        applicationId = "com.example.fixture.defaultapp"
        minSdk = 23
        targetSdk = 34
        versionCode = 100
        versionName = "1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("smoke")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

apkHarden {
    certificateSha256.set(
        providers.gradleProperty("apkharden.certificateSha256")
            .getOrElse("ab".repeat(32)),
    )
}
