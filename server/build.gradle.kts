plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("xyz.aezdek.aezgram.server.MainKt")
    // Resolute shares 6.7 GB with Immich, Jellyfin, Ollama and openGym: keep the heap small and predictable.
    applicationDefaultJvmArgs = listOf("-Xmx384m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=128m", "-Xss512k")
}

dependencies {
    implementation(project(":shared:protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":shared:crypto"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
