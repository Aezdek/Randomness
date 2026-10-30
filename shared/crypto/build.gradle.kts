plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Declared by its multiplatform coordinates so an Android consumer resolves the AAR (with the
    // per-ABI libsodium.so) and a JVM consumer resolves the jar that bundles desktop/server natives.
    api(libs.libsodium.bindings)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    systemProperty("hush.vectorsDir", rootProject.file("crypto-vectors").absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
