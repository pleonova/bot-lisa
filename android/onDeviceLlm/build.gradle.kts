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
    //
    // NDK 27 (not upstream's 29) -- Phase 1 already proved 27 builds fine
    // against this project's existing AGP/Kotlin versions; only bump if a
    // concrete build failure traces back to it.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                // Mirrors ggml-org/llama.cpp examples/llama.android's own
                // lib/build.gradle.kts flags (tag v0.4.0) as closely as
                // possible -- these are what upstream's JNI bridge was
                // actually tested against, not our own guess at what
                // llama.cpp needs.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"

                // 16 KB page size support. Passed as -D command-line
                // defines (pre-seed CMakeCache.txt) rather than only
                // set(...CACHE...FORCE) inside CMakeLists.txt -- the NDK's
                // Android toolchain file runs its own linker-flag setup
                // during project(), before any of our own CMakeLists.txt
                // code executes, so a same-named cache variable set there
                // could otherwise win. Backward compatible with 4KB-page
                // devices; not a tradeoff between the Pixel 6 (emulator)
                // and Pixel 11. See ON_DEVICE_LLM_PLAN.md Phase 2/4.
                //
                // Both SHARED and MODULE variables are needed: with
                // GGML_BACKEND_DL=ON, ggml's ggml_add_backend_library()
                // builds each per-CPU-microarchitecture variant as a CMake
                // MODULE library (add_library(... MODULE ...), meant for
                // dlopen), not SHARED -- CMAKE_SHARED_LINKER_FLAGS doesn't
                // apply to MODULE targets at all, they read their own
                // separate variable. Confirmed via llvm-readelf: without
                // this, every libggml-cpu-*.so stayed 4KB-aligned while
                // everything else picked up 16KB from the SHARED flag alone.
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                arguments += "-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // llama.cpp's own CMakeLists.txt only requires 3.14-3.28;
            // upstream's example pins 3.31.6 but that's their toolchain
            // choice, not a real requirement, and it isn't an SDK-packaged
            // version we can auto-install. 3.22.1 is already proven to
            // auto-install (Phase 1).
            version = "3.22.1"
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

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
