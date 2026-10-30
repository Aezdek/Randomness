# kotlinx.serialization: keep generated serializers (payloads, DTOs, stored session).
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# libsodium is reached through JNA, which binds by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.ionspin.kotlin.crypto.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# WebRTC calls back into Java from native code.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
