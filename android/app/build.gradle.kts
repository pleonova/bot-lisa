plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.botlisa.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.botlisa.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // On-device LLM (Qwen3.5 via llama.cpp/JNI), powering "что ещё"
    // suggestions on capable hardware. See ON_DEVICE_LLM_PLAN.md.
    implementation(project(":onDeviceLlm"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // EncryptedSharedPreferences, used by ServerConfig to store the API key
    // (AES256-GCM, key held in the Android Keystore) instead of plaintext.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // On-device English->Russian fallback translation (OnDeviceTranslator.kt),
    // used only when /assist reports no curated-library match. Runs fully
    // on-device after a one-time model download -- no API key, no per-call
    // cost. See OnDeviceTranslator.kt for why this is a fallback, not the
    // primary translation path.
    implementation("com.google.mlkit:translate:17.0.3")
}
