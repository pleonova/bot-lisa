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
        // For src/androidTest -- on-device instrumented tests (e.g.
        // WhatElseBenchmarkTest) that need the real app process, Context,
        // and native libs, not a JVM unit test's stubs.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // The GGML_BACKEND_DL runtime dispatch in onDeviceLlm scans
    // ApplicationInfo.nativeLibraryDir for backend .so files at startup
    // (ggml_backend_load_all_from_path). Modern Android's default packaging
    // never extracts .so files to that directory -- it mmaps them straight
    // out of the APK instead, leaving the directory empty. Force legacy
    // (extracted) packaging so that directory actually exists on disk.
    // See ON_DEVICE_LLM_PLAN.md Phase 2.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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

    // Background, resumable model download (ModelDownloadWorker) -- survives
    // process death/app-close, unlike a plain coroutine tied to an Activity.
    // See ON_DEVICE_LLM_PLAN.md Phase 6.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // EncryptedSharedPreferences, used by ServerConfig to store the API key
    // (AES256-GCM, key held in the Android Keystore) instead of plaintext.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // On-device English->Russian fallback translation (OnDeviceTranslator.kt),
    // used only when /assist reports no curated-library match. Runs fully
    // on-device after a one-time model download -- no API key, no per-call
    // cost. See OnDeviceTranslator.kt for why this is a fallback, not the
    // primary translation path.
    implementation("com.google.mlkit:translate:17.0.3")

    // JUnit for PromptComposerTest. org.json:json specifically -- Android's
    // built-in org.json classes are stubs in plain JVM unit tests (they
    // throw at runtime); this standalone artifact provides real
    // implementations of the same package so PromptComposer's JSON-parsing
    // logic is testable without pulling in Robolectric.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")

    // Instrumented tests (src/androidTest) -- e.g. WhatElseBenchmarkTest,
    // which needs the real on-device model/native libs a JVM unit test
    // can't provide.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
