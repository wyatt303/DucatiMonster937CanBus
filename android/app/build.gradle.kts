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
        versionCode = 5
        versionName = "0.0.5"
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
