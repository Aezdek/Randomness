plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "xyz.aezdek.aria.phone"
    compileSdk = 35

    defaultConfig {
        // The same id as the watch app: the Wear OS data link only connects apps with matching ids and keys.
        applicationId = "xyz.aezdek.aria"
        minSdk = 26
        targetSdk = 35
        // CI numbers each build (-PariaBuild=<run number>) so the apps can tell which is newer.
        val build = (project.findProperty("ariaBuild") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.1.$build"
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
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.core.ktx)
    implementation(libs.work.runtime)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.youtubedl)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
