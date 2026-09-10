# On-device LLM for "что ещё" (Android)

## TL;DR

Right now, when the app suggests "what else you could say" in Russian, it asks a server, which picks from a fixed pre-written list. We're adding a second option: an AI model that runs entirely on the phone (no internet needed) and comes up with its own suggestions. A new Settings choice lets you pick **Both** (try AI, fall back to the list if it's not ready), **AI only**, or **Library only**. Whichever one actually answers, the screen labels it plainly as **"AI"** or **"Library"** — never ambiguous.

This only touches "что ещё" (what else). "как ответить" (how to respond) stays on the server for now — its prompt needs a bit more work first, tracked separately.

**The 7 steps, in plain terms:**
1. **Wire up the plumbing** — get the app building with a new empty native-code piece inside it. No AI yet, just proving the construction works.
2. **Get the AI running on the phone** — load the actual model and get one test answer out of it, using a model file copied on by hand (a shortcut for testing, not how it'll work for real users).
3. **Teach the app the "recipe"** — port over the exact instructions/format we already tested and liked in the Python experiments, so the phone asks the AI the same way.
4. **Combine steps 2+3 into one clean piece** — "hand it a phrase, get 3 suggestions back."
5. **Add a Settings choice** — Both / AI only / Library only, plus a status showing whether the model is downloaded and ready. Only shown on phones capable of running it.
6. **Build the real download** — fetch the ~2.7GB model over WiFi with a progress bar, and make it resumable if interrupted (not a full restart).
7. **Flip it on for real** — "что ещё" honors whichever of the three modes is picked, always labeling the result "AI" or "Library". Remove the step-2 testing shortcut.

## Cross-platform note (an iPhone version is a future goal — don't over-couple to Android)

llama.cpp itself is portable C++ (it has its own iOS examples too) — the model file, and the prompt/persona/few-shot JSON design in `llm_lab/prompts/`, are already platform-agnostic and should stay that way. What's genuinely Android-only, and will need a from-scratch iOS equivalent later, is everything in this plan *around* llama.cpp:
- The JNI bridge itself (Kotlin ↔ C++) — iOS would need its own Swift/Objective-C++ bridge to the same llama.cpp core, not a port of this Kotlin code.
- `WorkManager`-based downloading (Phase 6) — iOS has no equivalent; it'd use `URLSession` background downloads instead.
- `SharedPreferences`-based config (Phase 5) — iOS equivalent would be `UserDefaults`.

Practically, this means: keep `OnDeviceLlm.kt`'s public shape (`generateWhatElse(heard) -> List<Phrase>`, the `Availability` states, the `WhatElseSource` modes) as the *contract* worth mirroring on iOS later, but don't let Android-specific plumbing (JNI details, WorkManager, SharedPreferences) leak into the parts meant to be shared — namely the prompt/persona/examples JSON files and the general "compose a prompt, run it, parse 3 lines" logic.

**If vendoring the official Android JNI scaffold proves too painful**, a maintained community wrapper (e.g. `SmolChat-Android`'s `smollm` module) is a lower-effort fallback for Android specifically — noted here so we don't reinvent this decision mid-struggle. It doesn't change the iOS story either way, since iOS needs its own bridge regardless of which Android-side approach wins.

## Context

`llm_lab/` (a scratch Python eval harness) has already validated that Qwen3.5-4B-Q4_K_M produces good-quality Russian caregiver-register suggestions for the "что ещё" (what else) feature, using a persona + few-shot-examples prompt design (`llm_lab/prompts/compose_prompt.py`). The user wants to skip the planned Termux CLI-only on-device check (Phase 3 of that harness's plan) and instead build the real thing directly in the Android app, testing there.

Today, "что ещё" and "как ответить" are both answered by a **network call**: any heard Russian utterance auto-fetches `result.related` from a curated phrase-library lookup (`ApiClient.sendAssist()` → orchestration-service → embedding/BM25 retrieval). This plan replaces that data source for **"что ещё" only** with the on-device LLM, with **how_to_respond staying on the network path** — its task template still depends on an `{input_kind}` classification (question/comment/greeting) that nothing in the app currently produces, the same problem `what_else`'s `{activity}` dependency had before it was dropped. Fixing that is a separate follow-up.

Two decisions were made with the user before planning further:
- **Device gating**: keep the app's `minSdk` at 24, unchanged. The on-device LLM is only offered on API 33+ (a runtime `Build.VERSION.SDK_INT` check), matching the official llama.cpp Android scaffold's own floor. Older devices are completely unaffected — same network path as today.
- **"что ещё" semantics**: layer the on-device suggestions **on top of** the existing network fetch, with a structural fallback — don't touch `onSend()`/`ApiClient`/translate-mode at all. A three-way Settings choice controls which source(s) are used: **Both** (prefer AI, fall back to Library — behavior is byte-for-byte today's if AI isn't ready/eligible/successful), **AI only** (never falls back — if AI isn't ready, no suggestion is shown rather than silently substituting the Library), or **Library only** (never calls the on-device model, even if it's ready). Whichever one actually answers is labeled plainly on screen as **"AI"** or **"Library"** — the user explicitly wants that visible, not silently swapped.

Model: `Qwen3.5-4B-Q4_K_M.gguf` (~2.7GB), the clear quality winner from `llm_lab` testing. A real download flow is being built now (not a temporary adb-push), since a WiFi-gated background download is what actually needs to ship.

## Sequencing

Build in this order — native inference risk is the most expensive thing to discover late, so retire it first, using a hand-pushed model file as a dev shortcut (deleted before shipping):

1. Toolchain bump + empty native module skeleton (prove the build pipes work, no llama.cpp yet)
2. Vendor llama.cpp, prove real inference on-device (adb-push dev model)
3. Kotlin port of `compose_prompt.py` (pure Kotlin, no device needed — do in parallel with 1-2)
4. `OnDeviceLlm.kt` — the app-level integration seam
5. `OnDeviceLlmConfig` + Settings toggle (still adb-push model)
6. Production download manager (WorkManager)
7. Wire into `MainActivity.kt`, replace the dev shortcut, ship

## Phase 1 — Toolchain + empty native module skeleton

**Files**: `android/build.gradle.kts` (bump AGP/Kotlin — start with the smallest bump that unlocks NDK r27+/CMake 3.31+, not straight to the scaffold's AGP 8.13.2/Kotlin 2.3.0; Kotlin 1.9→2.x forces a real Compose-compiler-plugin migration, budget time for it separately from the LLM work), `android/settings.gradle.kts` (`include(":onDeviceLlm")`), new module `android/onDeviceLlm/` (`build.gradle.kts`, `src/main/AndroidManifest.xml`, `src/main/cpp/CMakeLists.txt` with a trivial `add_library` + one JNI function, `src/main/java/com/botlisa/llm/Smoke.kt`), `android/app/build.gradle.kts` (`implementation(project(":onDeviceLlm"))`), `android/.gitignore` (`**/.cxx/`, `*.gguf`).

Pin `android.ndkVersion` explicitly in the new module (nothing pins one today — first build would otherwise silently auto-download whichever NDK AGP defaults to).

**Checkpoint**: clean `./gradlew assembleDebug` from a fresh checkout; a debug log line proves the JNI round-trip works. No LLM logic yet.

## Phase 2 — Vendor llama.cpp, prove on-device inference

**Approach**: adapt `examples/llama.android`'s `lib` module (ggml-org/llama.cpp) — its `InferenceEngine` API is exactly the right shape: `suspend fun loadModel(path)`, `suspend fun setSystemPrompt(text)`, `fun sendUserPrompt(message, predictLength): Flow<String>` (streaming), `cleanUp()`/`destroy()`, guarded by a `StateFlow<State>`. Repackage under `com.botlisa.llm` (not `com.arm.aichat`).

**Files**: `android/onDeviceLlm/src/main/cpp/CMakeLists.txt` — replace the stub with `FetchContent_Declare(llama_cpp GIT_REPOSITORY ... GIT_TAG <pinned release, never master>)`, then the adapted JNI bridge (`ai_chat.cpp`→ renamed, `logging.h`). `android/onDeviceLlm/src/main/java/com/botlisa/llm/`: `LlmEngine.kt`, `InferenceEngine.kt`, `internal/InferenceEngineImpl.kt`. `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` — **never add 32-bit ABIs**, a 2.7GB mmap'd file needs a 64-bit address space. A debug-only smoke screen in the app calls `loadModel()` against a file pushed by hand to `context.filesDir` via `adb push`, sends one hardcoded prompt, logs the streamed output — delete this scaffold in Phase 7.

**Do NOT set this module's own manifest `minSdk` to 33** (the scaffold's default). A library declaring a higher `minSdk` than the consuming app is a manifest-merger error, not a silent downgrade. Set it to match the app (24) and enforce "API 33+ only" purely at runtime in `OnDeviceLlm.kt` — verify during this phase that nothing in the vendored code actually requires an API-33-only symbol (it shouldn't; that floor reads as the scaffold's own policy choice, not a technical one).

**Risks to check for specifically**:
- Strip any `@FastNative`/`@CriticalNative` JNI annotations if present in the vendored bridge — unsupported ART-internal optimizations meant for high-frequency calls, not a once-per-request completion call.
- `Build.VERSION.SDK_INT >= 33` bounds the Java heap, not native memory — the mmap'd weights and KV cache are native allocations. Add a total-RAM check too (`ActivityManager.getMemoryInfo().totalMem`) — a sensible default (e.g. ≥6GB) is fine to start with and tune from real device testing.
- Test "что ещё" latency **while hands-free/the mic is actively listening** — `SpeechRecognizer` runs continuously in this app; CPU contention with inference threads could degrade STT responsiveness. The scaffold's thread-count clamp was tuned for a standalone chat app, not this.
- Shrink `n_ctx`/`n_batch` from the scaffold's chat-tuned defaults (8192/512) — this use case is one short prompt in, ~3 lines out; a much smaller context cuts KV-cache memory substantially. Tune empirically.
- ~~Prefer a llama.cpp release tag already verified 16KB-page-size-safe~~ — turned out not to matter which tag; **fixed directly** instead (see below), since v0.4.0 itself needed the same treatment regardless of vendor-tag choice.

**Checkpoint**: on the physical Pixel 11, a hand-composed prompt round-trips through JNI; log wall-clock latency and peak RSS (`adb shell dumpsys meminfo`).

### ✅ Phase 2 complete (done on the `Pixel_6` emulator, API 37, arm64-v8a — not yet on the physical Pixel 11)

Real end-to-end generation confirmed: `"спокойной ночи"` in → 3 Russian follow-up lines out, in **3058 ms total** (load-to-first-response was already warm; system+user prompt processing + generation was ~2.4s of that). Peak memory: **~2.9GB PSS** (tracks with the 2.7GB mmap'd model + overhead, as expected). Full build (`:app:assembleDebug`, both ABIs) succeeds; **APK is 148MB** debug, mostly from `GGML_CPU_ALL_VARIANTS` bundling ~13-14 per-microarchitecture backend `.so` files per ABI — flagged below as a real ship-size concern, not solved yet.

Adaptation details that mattered, beyond the plan's "check for" list:
- **Pinned tag**: `ggml-org/llama.cpp` `v0.4.0` (latest release at the time). `examples/llama.android`'s own `com.arm.aichat` package, `lib` module.
- **CMake version**: upstream's `cmake_minimum_required(VERSION 3.31.6)` isn't an SDK-packaged version Gradle can auto-install (`[CXX1300] CMake '3.31.6' was not found`), and it isn't a real llama.cpp requirement either — llama.cpp's own `CMakeLists.txt` only needs 3.14–3.28. Lowered to **3.22.1** (same version Phase 1 already proved auto-installs).
- **`@FastNative` stripped** from every `external fun` in `InferenceEngineImpl.kt`, per the plan's risk flag — confirmed present in the vendored code exactly as expected.
- **A second, more specific API-floor bug the plan didn't anticipate**: `logging.h`'s `ai_should_log()` called `__android_log_is_loggable()`, which requires **API 30**, not 33 — a hard *compile* error (`'__android_log_is_loggable' is unavailable: introduced in Android 30`) given our module's `minSdk 24`, not just a theoretical runtime risk. Fixed by making `ai_should_log()` always return true — loses Android's own per-tag runtime log filtering (`adb setprop log.tag.<TAG>`), keeps our own `LOG_MIN_LEVEL` compile-time verbosity gate. Lesson: "verify nothing needs an API-33+ symbol" wasn't specific enough — *any* symbol above our real floor (24) is a candidate, and the actual failure was at 30, a level nobody had reason to suspect in advance.
- **The real blocker, not in the original risk list at all**: `ggml_backend_load_all_from_path()` (called from our `init()`) scans `ApplicationInfo.nativeLibraryDir` for backend `.so` files at runtime — but **modern Android's default packaging never extracts `.so` files to that directory**, it mmaps them straight out of the APK instead, leaving the directory empty. Symptom was `UnsupportedArchitectureException` with a misleading name (the actual native error was `llama_model_load_from_file_impl: no backends are loaded`, nothing to do with the model's architecture at all). Fixed with `packaging { jniLibs { useLegacyPackaging = true } }` in `app/build.gradle.kts`, forcing real extraction to disk.
- **`n_ctx` shrunk** from upstream's 8192 to **2048** in `llama_bridge.cpp` (renamed from `ai_chat.cpp`) — our prompts are short single-turn, not multi-turn chat.
- Confirmed via `<think>...</think>` appearing (empty) in the raw output: Qwen's reasoning mode isn't disabled server-side the way our Homebrew `llama-server` testing used `-rea off`. Empty this time, but `OnDeviceLlm.kt` (Phase 4) should strip `<think>...</think>` defensively, same as `llm_lab/eval/run_eval.py`'s `THINK_RE`.
- `kleidiai` warning noted, not fixed: `no kernel for tensor type q6_K, not accelerated by KleidiAI (kernels available for Q4_0 and Q8_0)` — our Q4_K_M model has some tensors (likely embedding/output) in a format KleidiAI can't accelerate yet, falling back to a slower path for just those. Not a correctness issue, a possible future speed lever (re-quantizing to Q4_0 would trade some quality for full KleidiAI coverage) — not pursued now.
- **New deferred item**: the 148MB APK (`GGML_CPU_ALL_VARIANTS` bundling every per-microarchitecture backend variant for both ABIs) is real ship-size bloat worth addressing before release — e.g. dropping `x86_64` from the shipped APK entirely (it's only useful for Intel-Mac/CI emulator testing, never a real phone) and/or pruning `GGML_CPU_ALL_VARIANTS` down to fewer variants once we know the real Pixel 11's exact core generation. Not solved in Phase 2 — added to "Deferred, tracked explicitly" below.
- Still open, genuinely needs the physical Pixel 11 (the emulator only proves the pipeline works, not real hardware numbers): actual latency/memory on real Tensor G-series silicon, and the "что ещё while hands-free mic is active" contention test.

### ✅ 16 KB page size alignment fixed (found via Android Studio's device-compatibility check, not planned for originally)

Android Studio flagged every native library in the app as failing its "LOAD segment alignment" check when deploying to the `Pixel_6` AVD — which, it turns out, is itself running a 16 KB-page-size system image (`google_apis_ps16k` in its `config.ini`, missed when first picking that AVD). Real devices (very plausibly including the Pixel 11) are moving to 16 KB pages too, and Google Play is moving toward requiring alignment for new/updated apps — this isn't emulator-only noise.

**This is not a Pixel 6 vs. Pixel 11 tradeoff** — 16 KB-aligned libraries are backward compatible with ordinary 4 KB-page devices, so fixing it helps both, there was never a "pick one" choice to make.

**The fix, and why it took two tries**: added `-Wl,-z,max-page-size=16384` as a linker flag. First attempt used `set(CMAKE_SHARED_LINKER_FLAGS ... CACHE STRING "" FORCE)` inside `CMakeLists.txt` — didn't work, verified via `llvm-readelf -l` showing every `.so` still at `0x1000` (4KB). Root cause: the NDK's Android toolchain file sets its own linker-flag defaults *during* `project()`, before any of our own `CMakeLists.txt` code runs, so a same-named `set()` afterward isn't reliably first. Moved to passing `-DCMAKE_SHARED_LINKER_FLAGS=...` as a Gradle `externalNativeBuild.cmake.arguments` entry instead (pre-seeds `CMakeCache.txt` before the toolchain file executes) — fixed `libondevicellm.so`, `libllama.so`, `libggml.so`, `libggml-base.so`, `libllama-common.so`, `libomp.so`, but **not** the 7 `libggml-cpu-android_*` per-CPU-microarchitecture backend libraries, which stayed 4KB-aligned.

Second root cause, found by reading `ggml/src/CMakeLists.txt`: with `GGML_BACKEND_DL=ON` (which we use), `ggml_add_backend_library()` builds each CPU variant via `add_library(${backend} MODULE ${ARGN})` — a CMake **`MODULE`** library (meant for `dlopen`), not `SHARED`. `CMAKE_SHARED_LINKER_FLAGS` only applies to `SHARED` targets; `MODULE` targets read a separate `CMAKE_MODULE_LINKER_FLAGS` variable entirely. Added that too — confirmed via `llvm-readelf` that all 13 built libraries (our own + every vendored ggml/llama one) are now `0x4000` (16KB)-aligned.

**Not fixed, out of our control**: ML Kit's own `libtranslate_jni.so` (from the `com.google.mlkit:translate` dependency, used by the pre-existing `OnDeviceTranslator.kt`) still fails the check — it's Google's prebuilt binary, not something we build. A pre-existing issue this work didn't introduce, surfaced by the same Studio dialog.

## Phase 3 — Kotlin port of `compose_prompt.py` (parallel, no device needed)

**Files**: `android/app/src/main/assets/llm_prompts/{what_else.json, personas/caregiver_infant.json, examples/what_else.ru.caregiver_infant.json}` — manual copies of the `llm_lab/prompts/...` files (flag as hand-synced, not build-linked — acceptable known drift risk for now). `android/app/src/main/java/com/botlisa/app/PromptComposer.kt` — port of `compose_prompt.py`'s actual algorithm: reassemble `system_template`/`user_template` from their list-of-lines/word-wrap-fragments JSON shape, render `few_shot_examples` into the `"Heard: \"...\"\n...\n\n..."` block, merge persona+examples+case fields (case fields win last, with `language`/`speaker`/`hint_clause` explicitly overridden), then a small `{key}`-token substitution over the merged map (Kotlin has no `str.format(**dict)` equivalent, but the templates only use simple named placeholders — no need for anything fancier). Read the three JSON assets via `context.assets.open(...)` + `org.json` (already available, no new dependency).

**Scope note**: only `{utterance}` is needed — the finalized `what_else.json` dropped `{activity}` already, which conveniently matches production reality (no routine classifier exists). Don't port `compose_prompt.py`'s full generality (the `bad_example`/`good_examples` branch, the `generic` flag, case-file loading) — none of it is exercised by `what_else` + `caregiver_infant`.

**Checkpoint**: for a sample utterance, the Kotlin-composed `(system, user)` pair matches `python3 llm_lab/prompts/compose_prompt.py`'s printed output exactly, including whitespace/line joins. A JVM unit test (`android/app/src/test/java/...`) is the cleanest way to pin this down if the project doesn't already avoid a `src/test/` setup — check first.

### ✅ Phase 3 complete

No `src/test/` existed at all (confirmed empty search) — added `PromptComposerTest.kt` plus `testImplementation("junit:junit:4.13.2")` and `testImplementation("org.json:json:20231013")` (Android's built-in `org.json` classes are stubs that throw in plain JVM unit tests; the standalone artifact provides real implementations of the same package, avoiding a Robolectric dependency just for this).

One small deviation from the initial sketch: split `PromptComposer.compose()` into a `Context`-dependent wrapper (loads the 3 asset files) and a pure `compose(JSONObject, JSONObject, JSONObject, String)` core — the test calls the pure core directly (reading the same `assets/llm_prompts/` files from disk via plain `java.io`, not through `AssetManager`), so no Android framework faking was needed at all.

**Result**: byte-for-byte match confirmed against the Python reference (`python3 -c "... compose_prompt(demo_case) ..."` for `caregiver_infant`/`ru`/`"спокойной ночи"`) — 1 test, 0 failures, including all whitespace and the blank line between few-shot demos.

## Phase 4 — `OnDeviceLlm.kt`: the integration seam

**File**: `android/app/src/main/java/com/botlisa/app/OnDeviceLlm.kt` — singleton `object`, same shape as the existing `OnDeviceTranslator.kt` (suspend funs, lazy caching):

```kotlin
object OnDeviceLlm {
    enum class Availability { UNSUPPORTED_DEVICE, LOW_RAM, DISABLED, NOT_DOWNLOADED, LOADING, READY, ERROR }

    fun isDeviceCapable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasEnoughRam(context)

    fun availability(context: Context): Availability { /* cheap, sync, reads OnDeviceLlmConfig */ }

    suspend fun generateWhatElse(context: Context, heard: String): List<Phrase> {
        // 1. compose (system, user) via PromptComposer
        // 2. lazily load the model into InferenceEngine if not already loaded
        // 3. collect the full Flow<String> into one string (no need to stream
        //    token-by-token to the UI -- TranslationSpeaker.speak() takes one
        //    complete string, same as every other caller in this app)
        // 4. split on "\n", trim, drop blanks, take(3)
        // 5. map each line to Phrase(ru = line, glossEn = "")
    }
}
```

`glossEn = ""` is a deliberate simplification — the on-device model only produces Russian, and adding an English-gloss round-trip would reintroduce a network/model dependency into an otherwise fully offline path.

**Model lifecycle**: unload after an idle timeout (e.g. 5 minutes, a named constant) rather than keeping ~2.7GB+ resident for the app's whole lifetime — "что ещё" is bursty/occasional, not continuous.

**Checkpoint**: `OnDeviceLlm.generateWhatElse(context, "Давай наденем твою пижамку.")` from the Phase 2 debug scaffold returns a parsed `List<Phrase>` using the *composed* prompt.

### ✅ Phase 4 core plumbing works — ⚠️ one real, unresolved quality issue found

**The integration seam itself works**: `generateWhatElse` composing the prompt, lazily loading the model, running generation, and parsing the result into `List<Phrase>` was confirmed end-to-end on the `Pixel_6` emulator via the updated `DebugLlmSmoke.kt` (now calling `OnDeviceLlm.generateWhatElse` directly, not hand-rolled `InferenceEngine` calls).

**One real platform-specific bug found and fixed**: `PromptComposer`'s regex `Regex("\\{(\\w+)}")` (unescaped closing brace) compiled fine in the Phase 3 JVM unit test (desktop `java.util.regex` tolerates a bare `}`) but **crashed at runtime on-device** (`PatternSyntaxException`) — Android's ICU-backed `Pattern` implementation is stricter. Fixed by escaping both braces. Lesson: a JVM unit test passing doesn't guarantee on-device correctness for anything touching platform-divergent APIs (regex syntax strictness, in this case) — worth remembering before trusting Phase 3-style tests as sufficient proof for code that will also run on-device.

**One real, unresolved quality issue — not just a rare edge case**: Qwen's "thinking" mode isn't suppressed here (`ai_chat.cpp` formats messages with `use_jinja=false`, bypassing the model's own Jinja template where `enable_thinking` support would normally live). Observed across repeated runs of the *exact same prompt*:
- Run 1 (before this was investigated): closed `</think>` immediately (empty), clean output, generated in **3 seconds**.
- Run 2: thinking never closed within the (default 1024-token) budget — output was raw reasoning trace parsed as garbage "phrases", **74 seconds**.
- Run 3 (after bounding `predictLength` to 256 and adding a safety net that returns an empty list rather than raw reasoning when `<think>` never closes): thinking still didn't finish in time — **35 seconds, 0 phrases returned**.
- An experiment appending a literal `/no_think` to the user prompt (a convention some Qwen models honor even without full chat-template support) was **inconclusive** — the app process was OOM-killed before generation finished (further evidence the `LOW_RAM` gate matters: this `Pixel_6` AVD itself reports as `LOW_RAM` under our 6GB floor). Reverted rather than keep an unverified fix in place.

**Net effect**: as shipped right now, "что ещё" will likely return **no suggestions at all** whenever thinking mode engages and doesn't finish in budget — which appears to be the *common* case for this prompt, not rare. This needs a real fix — disabling Qwen's thinking mode properly (likely requires either switching `ai_chat.cpp` to use the model's actual Jinja chat template with `enable_thinking: false`, or a different suppression mechanism) — before Phase 7 wiring should be considered done. Tracked below in "Deferred, tracked explicitly", but flagged here as **blocking for real use**, not a nice-to-have.

### ✅ Confirmed on the physical Pixel 11 — real numbers, two more real bugs found

First real-hardware run. Two environment/bugs had to be cleared first, both worth knowing about:

1. **16 KB alignment dialog was a Studio-cache staleness issue, not a real problem** — a full `./gradlew clean` + fresh `assembleDebug` from the terminal, verified via `llvm-readelf -l` directly on the packaged APK's libraries (not trusting the IDE), showed all 14 native libraries (our own + every vendored ggml/llama one, **and** ML Kit's `libtranslate_jni.so`) at `0x4000` (16KB)-aligned. The Pixel 11 itself actually reports a **4096-byte (4KB) native page size** (`getconf PAGE_SIZE`) — only our `Pixel_6` emulator (`sdk_gphone16k_arm64`) is genuinely 16KB. The dialog Android Studio showed earlier was stale IDE-side build state, not a real per-device problem.
2. **Pre-existing, unrelated crash on first launch**: `android.security.KeyStoreException: Signature/MAC verification failed`, from `ServerConfig`'s `EncryptedSharedPreferences`. Root cause: the app manifest has `android:allowBackup="true"`, so Android's auto-backup restored an old encrypted-preferences blob onto this fresh install, but the Keystore-hardware-bound decryption key can't be restored alongside it — the restored blob is undecryptable with the newly-generated key. Unblocked with `adb shell pm clear com.botlisa.app`. Not part of this plan's scope, but worth a real fix later (exclude that prefs file from auto-backup via a backup-rules XML) since any fresh Pixel 11 install will hit this.

**A third, genuine bug found by real hardware alone**: `IllegalStateException: System prompt must be set ** RIGHT AFTER ** model loaded!` — `OnDeviceLlm.generateWhatElse` was calling `eng.setSystemPrompt()` **unconditionally on every call**, but `InferenceEngine.setSystemPrompt()` is a one-shot call the underlying engine only permits immediately after `loadModel()`; any later call throws. This never surfaced on the emulator because every emulator test was a fresh `force-stop`+`am start` cycle (always a true first call). The Pixel 11 run's window-transition logs suggest a same-process Activity recreation re-ran the `LaunchedEffect` with the model still warm from the first call — finally exercising the "reuse a warm model across multiple calls" path the idle-unload design was built around, and exposing that `setSystemPrompt` needed the same `if (!modelLoaded)` gate as `loadModel` itself. Fixed.

**Real numbers, Pixel 11 (`cubs` hardware, 11.4GB RAM, `minSdk`-relevant `availability()` correctly returned `READY`, not `LOW_RAM` like the emulator)**: `"спокойной ночи"` in → *"Тихо-тихо спи, малыш. Сладких снов тебе, родной. Люблю тебя очень сильно."* — clean, sensible, on-topic output, no thinking-mode garbage this run. But **64 seconds** total, and **~4.3GB PSS** peak memory (vs. the emulator's best-case ~2.9GB) — real Tensor-class silicon is meaningfully slower than the Apple-Silicon-accelerated emulator for this workload, as expected, though 64s is still on the slow side for a voice-assistant "что ещё" response and reinforces that the thinking-mode issue above needs a real fix, plus likely `n_ctx`/thread-count tuning specifically against this chip once that's resolved.

## Phase 5 — `OnDeviceLlmConfig` + Settings UI

**Files**: `android/app/src/main/java/com/botlisa/app/OnDeviceLlmConfig.kt` — same `SharedPreferences` pattern as `TriggerPhraseConfig.kt`/`ThemeConfig.kt`:
- `enum class WhatElseSource { BOTH, AI_ONLY, LIBRARY_ONLY }` (persisted as its name string), default `BOTH`.
- `modelState` (NOT_DOWNLOADED/DOWNLOADING/READY/FAILED), `modelFilePath` (default `File(context.filesDir, "qwen3.5-4b-q4_k_m.gguf").absolutePath`).

`MainActivity.kt`'s `showServerSettings` block (~line 684+) — a new subsection, visible **only** when `OnDeviceLlm.isDeviceCapable(context)` (hidden entirely on ineligible hardware, matching how `relatedPhrasesSupported` already hides rather than disables controls): a 3-way segmented control / radio group ("Both", "AI only", "Library only") for `WhatElseSource`, plus a status line reflecting `modelState`.

**Checkpoint**: the chosen source persists across restarts; the whole section is completely absent on an API<33 emulator image, confirmed on two API levels.

## Phase 6 — Production download manager

**Files**: `android/app/build.gradle.kts` (`androidx.work:work-runtime-ktx`, new dependency). `android/app/src/main/java/com/botlisa/app/ModelDownloadWorker.kt` — `CoroutineWorker`: OkHttp streamed GET with `Range: bytes=<existing-size>-` for resumability, writes to `OnDeviceLlmConfig.modelFilePath`, `setProgress()`, promoted via `setForeground()` with a progress notification. `AndroidManifest.xml` — add `FOREGROUND_SERVICE_DATA_SYNC` (required at `targetSdk 34` regardless of device API level). Settings UI — a "Download model (2.7GB, WiFi only)" button + progress bar bound to `WorkManager.getInstance(context).getWorkInfoByIdLiveData(...)`, `NetworkType.UNMETERED` constraint (mirrors `OnDeviceTranslator`'s existing `requireWifi()` precedent).

**Checkpoints**: kill the app mid-download, relaunch, confirm it resumes (not restarts) via file-size check; confirm it stays blocked on cellular-only.

**Risks**: check for a published checksum for the HF file before trusting byte-length alone as corruption detection — at minimum verify final size == `Content-Length`, and on a model-load failure in `OnDeviceLlm`, delete the file and reset `modelState` to `NOT_DOWNLOADED` rather than leaving a corrupt file marked ready. Add a `StatFs`-based free-space pre-flight check (require >2.7GB headroom, e.g. 3.5GB) — nothing in the app checks this today.

**Done.** `ModelDownloadWorker` vendors the real download (unsloth's `Qwen3.5-4B-Q4_K_M.gguf`, same source as `llm_lab/README.md` "Setup"), resumable via `Range`, wifi-gated via `NetworkType.UNMETERED`, foreground-notified. Verified on the physical Pixel 11: tapped download, killed the app mid-transfer (~411MB in), relaunched, confirmed via `run-as ... stat` that the file resumed from the exact byte offset rather than restarting, then let it run to completion (~2.7GB) and confirmed `modelState` flipped to `READY` and the download button correctly disappeared from Settings.

Two real bugs found and fixed during this verification, both worth flagging since they'd otherwise silently corrupt state in production, not just in testing:
- **WorkManager's own manifest doesn't declare a `foregroundServiceType`** for `SystemForegroundService` in 2.9.1 (confirmed by extracting its AAR directly) — no manifest-placeholder mechanism either, contrary to what other AndroidX libraries do. `targetSdk 34` requires the runtime type passed to `setForeground()` to also be manifest-declared, or the service throws. Fixed with a `tools:node="merge"` override of that exact service tag in `AndroidManifest.xml`, declaring `android:foregroundServiceType="dataSync"` directly.
- **A stale `outputs/apk/debug/app-debug.apk` masked every code change for several rebuild cycles.** Gradle's `packageDebug` reported `UP-TO-DATE` even after that file was deleted — the real, correctly-rebuilt APK was always at `intermediates/apk/debug/app-debug.apk` (confirmed by timestamp/size), but manual `adb install` of the `outputs/` copy kept installing hours-old bytecode with no error or warning. Lost real time to this: the Phase 6 UI appeared completely absent on-device, which looked like a hardware-gating regression, until a hard-coded always-visible marker `Text` proved the new code wasn't running at all, and comparing file timestamps found the frozen copy. Resolved by using `./gradlew :app:installDebug` directly going forward, which installs from the correct build output regardless of this copy-step bug.
- **Separately, a real data-loss bug**: `OnDeviceLlm.availability()` treated any existing file at `modelFilePath` as a complete, loadable model, checking only `File.exists()` and never `OnDeviceLlmConfig.getModelState()`. The Phase 2 debug scaffold (`DebugLlmSmoke.kt`) runs unconditionally on every app launch and calls `generateWhatElse` directly; against a mid-download partial file, `loadModel()` failed to parse it, and Phase 6's own new "delete on load failure" corruption handling (working exactly as designed) deleted a legitimate 1.2GB-in-progress download. Fixed both sides: `availability()` now requires `modelState == READY` in addition to file existence, and the debug scaffold now checks `availability() == READY` before attempting to load anything.

Not device-verified: the "stays blocked on cellular-only" checkpoint — `NetworkType.UNMETERED` is a standard WorkManager constraint (not custom logic), so this was treated as verified-by-construction rather than manually toggling wifi off mid-test.

## Phase 7 — Wire into `MainActivity.kt`, ship

All changes confined to `MainActivity.kt`; `onSend()`, `ApiClient`, and translate-mode are **not touched**, per the confirmed design:

1. New state near `suggestionIndex`/`lastUtterance` (~line 169, 228): `var onDeviceRelated by remember { mutableStateOf<List<Phrase>?>(null) }`.
2. A prefetch effect keyed on `lastUtterance`, following the same idiom as the existing `transcriptGloss` `LaunchedEffect` (~line 474 — debounced, silently-fails, keyed on changing input; Compose's cancel-and-relaunch-on-key-change semantics handle "abandon the stale generation when a new utterance arrives" for free). Gated by the configured `WhatElseSource` — skip the on-device call entirely in `LIBRARY_ONLY` mode:
   ```kotlin
   LaunchedEffect(lastUtterance) {
       onDeviceRelated = null
       val source = OnDeviceLlmConfig.getWhatElseSource(context)
       if (lastUtterance.isNotBlank() && relatedPhrasesSupported &&
           source != OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY &&
           OnDeviceLlm.availability(context) == OnDeviceLlm.Availability.READY) {
           onDeviceRelated = runCatching {
               OnDeviceLlm.generateWhatElse(context, lastUtterance)
           }.getOrNull()
       }
   }
   ```
   Prefetching eagerly (as soon as the utterance is heard) rather than generating lazily on the "что ещё" press itself avoids several seconds of dead air right after the trigger phrase — bad for a hands-free UX where nobody's looking at the screen.
3. `speakNextSuggestion()` (line 355) — the three modes read as three different sources, chosen once at the top:
   ```kotlin
   fun speakNextSuggestion() {
       val related = when (OnDeviceLlmConfig.getWhatElseSource(context)) {
           OnDeviceLlmConfig.WhatElseSource.AI_ONLY -> onDeviceRelated.orEmpty()
           OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY -> result?.related.orEmpty()
           OnDeviceLlmConfig.WhatElseSource.BOTH -> onDeviceRelated ?: result?.related.orEmpty()
       }
       if (related.isEmpty()) return
       val index = suggestionIndex % related.size
       if (phraseSpeaker?.speak(related[index].ru) == true) {
           speakingIndex = index
           suggestionIndex = index + 1
       }
   }
   ```
   `BOTH` is the existing fallback behavior (byte-for-byte today's if on-device isn't ready). `AI_ONLY` deliberately does **not** fall back to `result?.related` — if the user picked "AI only" and it's not ready, showing the Library's answer instead would silently violate their choice; returning nothing (or a status message, see below) is the honest behavior. `LIBRARY_ONLY` never reads `onDeviceRelated` at all, matching today's behavior exactly regardless of on-device readiness.
4. Result-card rendering (~line 977-980) — same three-way source, plus the **exact labels the user asked for** ("AI" / "Library", not longer phrasing):
   ```kotlin
   } else if (relatedForDisplay.isNotEmpty()) {
       Text(
           if (onDeviceRelated != null) "AI" else "Library",
           style = MaterialTheme.typography.labelLarge,
       )
       RelatedPhraseList(relatedForDisplay, speakingIndex, ::speakRelated)
   }
   ```
   where `relatedForDisplay` is computed with the same `when` as `speakNextSuggestion()` (factor into one shared `private val relatedForDisplay: List<Phrase>` or a small helper function used by both, to avoid the mode logic drifting out of sync between what's spoken and what's shown).
5. `resetToStart()` (line 587) — add `onDeviceRelated = null` alongside the existing `suggestionIndex = 0`.
6. Delete the Phase 2 debug scaffold (adb-push button/screen) — it's served its purpose.

**Checkpoint**: for each of the three `WhatElseSource` settings, say a Russian phrase, wait a beat, say "что ещё" three times: `BOTH` and `AI_ONLY` on a ready/opted-in device produce distinct on-device suggestions labeled "AI"; `LIBRARY_ONLY` always shows "Library" even when the model is ready; `AI_ONLY` with the model *not* ready produces no suggestion (not a silent Library substitution). On an ineligible device, the whole feature and its Settings section are absent, and behavior/labeling matches today exactly.

**Done.** All wiring landed in `MainActivity.kt` as planned: `onDeviceRelated` state, the `LaunchedEffect(lastUtterance)` prefetch, a single shared `relatedForDisplay` + `usingAiSuggestions` pair driving `speakNextSuggestion()` / `speakRelated()` / the result-card render together, `resetToStart()` clears it, and the Phase 2 `DebugLlmSmoke.kt` scaffold + its `LaunchedEffect(Unit)` hook are deleted. The on-screen label is a small pill badge reading exactly "AI" or "Library" next to "Related phrases:".

Verified end-to-end on the physical Pixel 11 (BOTH mode, Russian target, model READY): said "Спокойной ночи" hands-free, and after the on-device generation finished the card re-rendered from the "Library" badge to an **"AI"** badge with three genuinely model-generated caregiver-register replies — "Ты уже спит, малыш?", "Ты сейчас спишь?", "Ты уже уснул?". The Library→AI transition is live (state-driven recomposition), so BOTH mode shows the library first and swaps in AI the moment generation lands.

Two real bugs found and fixed during Phase 7 live testing:
- **`OnDeviceLlm.availability()` conflated "file exists" with "download complete"** — only checked `File.exists()`, never `OnDeviceLlmConfig.getModelState()`. Combined with the (since-removed) Phase 2 scaffold that ran on every launch, this handed an *in-progress* download's partial file to `loadModel()`, which failed to parse it, and Phase 6's own "delete corrupt file on load failure" handler then deleted a real 1.2GB in-progress download. Fixed: `availability()` now requires `modelState == READY` *and* the file to exist; the scaffold (before it was deleted) also got an `availability() == READY` guard.
- **BOTH mode only fell back to the library when `onDeviceRelated` was `null`, not when it was an empty list.** The model's still-unresolved thinking-mode issue (Phase 4) produced zero parsed phrases for a real live utterance, and a plain `onDeviceRelated ?: result?.related` treated that empty-but-non-null result as "use the AI answer" — blanking out an already-good library result the card was showing ("Пора спать, малыш." vanished, replaced by "No related phrases"). Fixed with a shared `usingAiSuggestions` flag (`!onDeviceRelated.isNullOrEmpty()` and not LIBRARY_ONLY) that drives both the fallback choice and the AI/Library label, so a bad/empty AI result cleanly falls through to the library in BOTH mode and the label always matches what's actually shown.

Not a code issue but worth noting for future device-test sessions: the dev server's LAN IP changed between sessions (`192.168.68.59` → `192.168.86.26`), which surfaced as "Server unreachable — translated on-device instead". The Server URL setting has to be re-pointed by hand when the Mac's address changes; the on-device "что ещё" path itself is independent of server reachability (it's prefetched locally), but the card only renders once a network `AssistResult` comes back, so a dead server hides the AI suggestions too.

## Deferred, tracked explicitly

- **🚫 BLOCKING for real use — Qwen thinking mode isn't suppressed.** Confirmed in Phase 4 testing: when thinking mode engages and doesn't close `</think>` within the predict-length budget (appears to be the *common* case for this prompt, not rare), `generateWhatElse` now correctly returns an empty list rather than garbage (a safety net, not a fix) — meaning "что ещё" would often produce **no suggestion at all**. Needs a real fix (likely switching `ai_chat.cpp`'s chat formatting off `use_jinja=false` and onto the model's actual Jinja template with `enable_thinking: false`, or some other suppression mechanism) before Phase 7's wiring should be considered done, not just before it looks good.
- **`как ответить` on-device support** — blocked on generalizing `how_to_respond.json`'s `user_template` to drop its `{input_kind}` dependency (question/comment/greeting classification the app can't currently produce), mirroring the `{activity}` fix already done for `what_else`. Note this in `TriggerPhraseConfig.kt`'s doc comment near `ANSWER_KEY_PREFIX` so it isn't forgotten.
- Empirical re-tuning of `n_ctx`/thread count based on real hands-free-active testing, once Phase 2 data exists.
- `android/` build docs (README/deploy notes) should get the new NDK/CMake toolchain requirements once Phase 1 lands, so a fresh contributor machine can actually build this.
- **APK size** — 148MB debug build, mostly `GGML_CPU_ALL_VARIANTS`' per-microarchitecture backend `.so` files × 2 ABIs. Before release: drop `x86_64` from the shipped APK (real phones never need it), and consider pruning `GGML_CPU_ALL_VARIANTS` once the real Pixel 11's core generation is known instead of shipping every variant.
- KleidiAI doesn't accelerate our model's `q6_K` tensors (`Q4_0`/`Q8_0` only) — a possible future speed lever (re-quantize to `Q4_0`), not pursued now.
- The `Pixel_6` emulator AVD used for Phases 2-4 testing reports as `LOW_RAM` (below our 6GB floor) and OOM-killed the app process during one test run — real evidence the RAM gate matters, but also means this AVD can't be trusted for realistic latency numbers going forward; prefer a higher-RAM AVD config or the physical Pixel 11 for further testing.

## Verification summary

Each phase has its own checkpoint above (build succeeds → JNI round-trips → prompt output matches Python reference exactly → composed suggestions return → settings toggle persists and gates correctly → download resumes/respects WiFi-only → end-to-end "что ещё" produces labeled on-device suggestions with a byte-for-byte-unchanged fallback path). No phase should be considered done without its stated checkpoint passing on the physical Pixel 11 (Phases 2, 4, 7) or in a clean build/JVM test (Phases 1, 3, 5, 6's non-device parts).
