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

# ── §7 ANTI-MOD hardening (2026-10-16) ───────────────────────────────────────
# aggressive repackaging of classes under a single obfuscated package
-repackageclasses 'sys'
-allowaccessmodification
# AntiTamper must survive but stay nameless
-keep class com.thesystem.app.core.security.AntiTamper { *; }
# BuildConfig fields (EXPECTED_CERT_SHA256 & backend keys) must not be stripped
-keepclassmembers class com.thesystem.app.BuildConfig { public static final java.lang.String *; }
# Hilt entry points used reflectively
-keep class com.thesystem.app.TheSystemApplication { *; }
-keep class com.thesystem.app.MainActivity { *; }
