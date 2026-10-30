plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Coordinates the android/ build substitutes these projects by (xyz.aezdek.aezgram:crypto, …).
subprojects {
    group = "xyz.aezdek.aezgram"
    version = "1.0"
}
