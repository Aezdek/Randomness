pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

// The Android app. The shared protocol, crypto and client logic come from the JVM build one level up, where they
// are tested against the real server; Gradle substitutes xyz.aezdek.hush:* with those projects.
rootProject.name = "HushAndroid"

includeBuild("..")
include(":app")
