plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "xyz.aezdek.aria.watch"
    compileSdk = 35

    defaultConfig {
        // The same id as the phone app: the Wear OS data link only connects apps with matching ids and keys.
        applicationId = "xyz.aezdek.aria"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
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
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.input)
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.lifecycle.runtime)
    implementation(libs.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.work.runtime)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
