pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// The JVM half: shared protocol + crypto and the server. Needs no Android SDK, so it builds and tests anywhere.
// The Android app is its own build in android/, which includes this one to reuse the shared modules.
rootProject.name = "AezGram"

include(
    ":shared:protocol",
    ":shared:crypto",
    ":server",
)
