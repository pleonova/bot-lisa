plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.botlisa.llm"
    compileSdk = 34
    // Deliberately NOT 33+ here, even though the app only offers this
    // feature at runtime on API 33+ (Build.VERSION.SDK_INT check in
    // OnDeviceLlm.kt). A library declaring a higher minSdk than the
    // consuming :app module is a manifest-merger error, not a silent
    // per-feature floor -- so this stays at parity with :app's minSdk.
    // See ON_DEVICE_LLM_PLAN.md, Phase 2.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
