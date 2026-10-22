import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release builds are signed with the one Salah release key, never a throwaway debug key, so every new APK
// installs as an update over the last one. CI passes it in through environment variables (see the README);
// for a local signed build put salah.keystore and salah.keystorePassword in ~/.gradle/gradle.properties.
val signingKeystore: String? = System.getenv("SALAH_KEYSTORE_PATH") ?: providers.gradleProperty("salah.keystore").orNull
val signingStorePassword: String? = System.getenv("SALAH_KEYSTORE_PASSWORD") ?: providers.gradleProperty("salah.keystorePassword").orNull
val signingKeyAlias: String = System.getenv("SALAH_KEY_ALIAS")?.ifBlank { null } ?: providers.gradleProperty("salah.keyAlias").orNull ?: "salah"
val signingKeyPassword: String? = System.getenv("SALAH_KEY_PASSWORD")?.ifBlank { null } ?: providers.gradleProperty("salah.keyPassword").orNull ?: signingStorePassword
val hasReleaseSigning = !signingKeystore.isNullOrBlank() && !signingStorePassword.isNullOrBlank()

android {
    namespace = "xyz.aezdek.salah"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.aezdek.salah"
        minSdk = 26
        targetSdk = 36
        // CI numbers each build, so a newer APK always has a higher version code.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingKeystore!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
