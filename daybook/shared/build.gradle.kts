import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "xyz.aezdek.daybook.shared"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        androidResources { enable = true }
    }
    jvm("desktop")
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    compilerOptions {
        optIn.addAll(
            "kotlin.time.ExperimentalTime",
            "kotlin.uuid.ExperimentalUuidApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.animation)
            implementation(libs.compose.resources)
            implementation(libs.haze)
            implementation(libs.haze.blur)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        // Android and Linux keep the local store in SQLite; the web build keeps it in browser storage.
        val nonWebMain = create("nonWebMain") {
            dependsOn(commonMain.get())
            dependencies { api(libs.androidx.sqlite) }
        }
        androidMain { dependsOn(nonWebMain) }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.sqlite.framework)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("desktopMain") {
            dependsOn(nonWebMain)
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":server"))
                implementation(libs.ktor.server.test.host)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "xyz.aezdek.daybook.resources"
    publicResClass = false
}
