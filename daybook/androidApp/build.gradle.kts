import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from CI secrets (or Gradle properties locally) so every build is signed with the
// same key and installs as an update over the previous one.
fun signing(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: providers.gradleProperty(prop).orNull
// Named so they can't be confused with the signing config's own properties inside create("release").
val signingStoreFile = signing("DAYBOOK_KEYSTORE_PATH", "daybook.keystore")
val signingStorePassword = signing("DAYBOOK_KEYSTORE_PASSWORD", "daybook.storePassword")
val signingKeyAlias = signing("DAYBOOK_KEY_ALIAS", "daybook.keyAlias")
val signingKeyPassword = signing("DAYBOOK_KEY_PASSWORD", "daybook.keyPassword")
val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "xyz.aezdek.daybook"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.aezdek.daybook"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Shrinking stays off: Ktor and kotlinx.serialization need keep rules that can only be proven
            // on a device, and the size saving is small for an app this size.
            isMinifyEnabled = false
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
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
