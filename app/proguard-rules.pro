# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
# Supabase / Ktor
-dontwarn io.ktor.**
-dontwarn io.github.jan-tennert.supabase.**
# ML Kit
-keep class com.google.mlkit.** { *; }
# Osmdroid
-dontwarn org.osmdroid.**
