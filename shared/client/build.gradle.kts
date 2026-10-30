plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Everything the Android app does that isn't UI or platform glue: the API, the sync engine, composing and sealing
// objects, sessions, the WebSocket and encrypted media. Plain Kotlin, so it is tested here against the real server.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":shared:protocol"))
    api(project(":shared:crypto"))
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    testImplementation(project(":server"))
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The main code targets Java 17 for Android; the tests embed the server, which is Java 21.
configurations.matching { it.name.startsWith("test") }.configureEach {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}
