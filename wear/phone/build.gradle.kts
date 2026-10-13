plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "xyz.aezdek.aria.phone"
    compileSdk = 35

    defaultConfig {
        // The same id as the watch app: the Wear OS data link only connects apps with matching ids and keys.
        applicationId = "xyz.aezdek.aria"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // yt-dlp ships a Python runtime per CPU type. Phones are ARM.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("aria-sideload.keystore")
            storePassword = "aria-sideload"
            keyAlias = "aria"
            keyPassword = "aria-sideload"
        }
    }

    buildTypes {
        release {
            // yt-dlp's Android wrapper relies on reflection, so the app isn't shrunk.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    // The bundled Python must be unpacked to disk to run.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.core.ktx)
    implementation(libs.work.runtime)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.youtubedl)
}
