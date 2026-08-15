plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.linuch.ducatitelemetry"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.linuch.ducatitelemetry"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}
