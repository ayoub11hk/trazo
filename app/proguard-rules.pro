# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.trazo.**$$serializer { *; }
-keepclassmembers class app.trazo.** { *** Companion; }
-keepclasseswithmembers class app.trazo.** { kotlinx.serialization.KSerializer serializer(...); }
# Supabase / Ktor
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
# MapLibre
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
