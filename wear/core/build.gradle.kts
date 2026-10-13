plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "xyz.aezdek.aria.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(libs.okhttp)
    api(libs.coroutines.android)
    implementation(libs.core.ktx)
    api(libs.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
