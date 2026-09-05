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
- Prefer a llama.cpp release tag already verified 16KB-page-size-safe (a forward-looking Play Store requirement), so the pinned tag doesn't need re-vendoring later for that alone.

**Checkpoint**: on the physical Pixel 11, a hand-composed prompt round-trips through JNI; log wall-clock latency and peak RSS (`adb shell dumpsys meminfo`).

## Phase 3 — Kotlin port of `compose_prompt.py` (parallel, no device needed)

**Files**: `android/app/src/main/assets/llm_prompts/{what_else.json, personas/caregiver_infant.json, examples/what_else.ru.caregiver_infant.json}` — manual copies of the `llm_lab/prompts/...` files (flag as hand-synced, not build-linked — acceptable known drift risk for now). `android/app/src/main/java/com/botlisa/app/PromptComposer.kt` — port of `compose_prompt.py`'s actual algorithm: reassemble `system_template`/`user_template` from their list-of-lines/word-wrap-fragments JSON shape, render `few_shot_examples` into the `"Heard: \"...\"\n...\n\n..."` block, merge persona+examples+case fields (case fields win last, with `language`/`speaker`/`hint_clause` explicitly overridden), then a small `{key}`-token substitution over the merged map (Kotlin has no `str.format(**dict)` equivalent, but the templates only use simple named placeholders — no need for anything fancier). Read the three JSON assets via `context.assets.open(...)` + `org.json` (already available, no new dependency).

**Scope note**: only `{utterance}` is needed — the finalized `what_else.json` dropped `{activity}` already, which conveniently matches production reality (no routine classifier exists). Don't port `compose_prompt.py`'s full generality (the `bad_example`/`good_examples` branch, the `generic` flag, case-file loading) — none of it is exercised by `what_else` + `caregiver_infant`.

**Checkpoint**: for a sample utterance, the Kotlin-composed `(system, user)` pair matches `python3 llm_lab/prompts/compose_prompt.py`'s printed output exactly, including whitespace/line joins. A JVM unit test (`android/app/src/test/java/...`) is the cleanest way to pin this down if the project doesn't already avoid a `src/test/` setup — check first.

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

## Deferred, tracked explicitly

- **`как ответить` on-device support** — blocked on generalizing `how_to_respond.json`'s `user_template` to drop its `{input_kind}` dependency (question/comment/greeting classification the app can't currently produce), mirroring the `{activity}` fix already done for `what_else`. Note this in `TriggerPhraseConfig.kt`'s doc comment near `ANSWER_KEY_PREFIX` so it isn't forgotten.
- Empirical re-tuning of `n_ctx`/thread count based on real hands-free-active testing, once Phase 2 data exists.
- `android/` build docs (README/deploy notes) should get the new NDK/CMake toolchain requirements once Phase 1 lands, so a fresh contributor machine can actually build this.

## Verification summary

Each phase has its own checkpoint above (build succeeds → JNI round-trips → prompt output matches Python reference exactly → composed suggestions return → settings toggle persists and gates correctly → download resumes/respects WiFi-only → end-to-end "что ещё" produces labeled on-device suggestions with a byte-for-byte-unchanged fallback path). No phase should be considered done without its stated checkpoint passing on the physical Pixel 11 (Phases 2, 4, 7) or in a clean build/JVM test (Phases 1, 3, 5, 6's non-device parts).
