# kotlinx.serialization: keep generated serializers for the journal / ZIP manifest models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.pockivo.nowmark.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pockivo.nowmark.**$$serializer { *; }
