import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun backendProp(key: String, fallback: String): String =
    providers.gradleProperty(key).orElse(providers.environmentVariable(key)).getOrElse(fallback)

android {
    namespace = "com.thesystem.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thesystem.app"
        minSdk = 26 // low-end / older Android first
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.1"

        buildConfigField("String", "SUPABASE_URL", "\"${backendProp("SUPABASE_URL", "https://YOUR_PROJECT_REF.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${backendProp("SUPABASE_ANON_KEY", "YOUR_PUBLIC_ANON_KEY")}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${backendProp("GOOGLE_SERVER_CLIENT_ID", "YOUR_WEB_OAUTH_CLIENT_ID")}\"")
        buildConfigField("String", "OFFERWALL_URL", "\"${backendProp("OFFERWALL_URL", "https://YOUR_CPA_OFFERWALL_URL")}\"")
        buildConfigField("String", "MAPTILER_API_KEY", "\"${backendProp("MAPTILER_API_KEY", "YOUR_MAPTILER_KEY")}\"")
    }

    // ── Signing: committed debug keystore keeps SHA-1 identical on ANY machine
    // (no-PC cloud builds included) so Google OAuth works everywhere.
    // Release/upload keystore lives OUTSIDE git (gitignored) — creds via
    // gradle.properties or CI secrets.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val relStore = rootProject.file("keystore/thesystem-release.keystore")
            if (relStore.exists()) {
                storeFile = relStore
                storePassword = backendProp("RELEASE_STORE_PASSWORD", "")
                keyAlias = backendProp("RELEASE_KEY_ALIAS", "thesystem")
                keyPassword = backendProp("RELEASE_KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fall back to debug signing if release keystore is absent (CI safety)
            signingConfig = if (rootProject.file("keystore/thesystem-release.keystore").exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("/META-INF/DEPENDENCIES", "/META-INF/LICENSE", "/META-INF/LICENSE.txt", "/META-INF/NOTICE", "/META-INF/*.kotlin_module") } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.activity)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.datastore)
    implementation(libs.play.services.location)
    implementation(libs.osmdroid)
    implementation(libs.mlkit.pose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    debugImplementation(libs.compose.ui.tooling)
}
