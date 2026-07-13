plugins {
    id("com.android.library")
    kotlin("android")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.apkharden.runtime"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
