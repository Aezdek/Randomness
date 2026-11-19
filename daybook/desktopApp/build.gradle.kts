import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.ktor.client.okhttp)
}

val appVersion = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

compose.desktop {
    application {
        mainClass = "xyz.aezdek.daybook.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Daybook"
            packageVersion = appVersion
            description = "Daily A Level study planner that syncs with your phone"
            vendor = "Aezdek"
            licenseFile.set(rootProject.file("licenses/Inter-OFL.txt"))
            modules("java.sql", "jdk.unsupported")
            linux {
                packageName = "daybook"
                iconFile.set(project.file("icons/daybook.png"))
                shortcut = true
                menuGroup = "Education"
                appCategory = "Education"
                debMaintainer = "daybook@aezdek.xyz"
                appRelease = "1"
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// Renders the real UI offscreen to PNGs, for checking the design without a display.
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    description = "Renders Daybook screens to build/screenshots"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("xyz.aezdek.daybook.desktop.ScreenshotsKt")
    maxHeapSize = "768m"
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
