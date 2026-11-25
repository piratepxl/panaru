# kotlinx.serialization: mantiene i serializer generati per i modelli Gokapi.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.panaru.gokapi.** {
    *** Companion;
}
-keepclasseswithmembers class app.panaru.gokapi.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.panaru.gokapi.**$$serializer { *; }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
