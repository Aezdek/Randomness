-keepclassmembers @kotlinx.serialization.Serializable class xyz.aezdek.aezprint.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class xyz.aezdek.aezprint.**$$serializer { *; }
