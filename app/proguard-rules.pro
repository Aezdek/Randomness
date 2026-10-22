# kotlinx.serialization: keep the generated serializers for the stored data and backup file.
-keepclassmembers @kotlinx.serialization.Serializable class xyz.aezdek.salah.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class xyz.aezdek.salah.**$$serializer { *; }
