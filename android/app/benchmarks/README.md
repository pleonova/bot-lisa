# "What else?" latency benchmarks

Baseline measurements from [`WhatElseBenchmarkTest`](../src/androidTest/java/com/botlisa/app/WhatElseBenchmarkTest.kt),
which times `OnDeviceLlm.generateWhatElse` over 20 real caregiver_infant
phrases (drawn from `phrase_library/phrases.json` and the `llm_lab/`
eval/example files) on a real device, after a one-time model warm-up.

## Run it

```
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class com.botlisa.app.WhatElseBenchmarkTest \
  com.botlisa.app.test/androidx.test.runner.AndroidJUnitRunner
```

(or `./gradlew :app:connectedDebugAndroidTest` if exactly one device/emulator
is connected). Needs the on-device model already downloaded on the target
device -- the test skips itself otherwise. Prints a summary to logcat
(`WhatElseBenchmark` tag) and writes a full per-phrase JSON report to
`<app-external-files-dir>/benchmarks/`; `adb pull` that file here to keep a
dated copy, e.g.:

```
adb pull /storage/emulated/0/Android/data/com.botlisa.app/files/benchmarks/<file>.json \
  android/app/benchmarks/what_else_benchmark_<device>_<date>.json
```

## Thermal throttling makes this easy to measure wrong

The test records `PowerManager.currentThermalStatus` per sample and refuses
to start if the device isn't already at `NONE`, because of what running it
twice back-to-back on the same Pixel 11 showed:

| Run | Gap between calls | Thermal status | Mean | Std dev | Min | Max |
|---|---|---|---|---|---|---|
| 1st (cool start) | none | NONE throughout | 3.90s | 0.49s | 3.22s | 5.49s |
| 2nd (immediately after) | none | **NONE -> LIGHT** (soc_therm 41.7C -> 46.3C) | **9.65s** | 2.96s | 4.70s | 15.77s |

Same 20 phrases, same device, run back to back -- **2.5x slower** purely
because the first run's 20 back-to-back inferences heated the SoC into
throttling. Twenty heavy LLM calls in ~100s with zero gap is a sustained-
load stress test, not what hands-free "what else?" usage looks like (one
request, then talking/listening for a while) -- so that number was
overstating real single-request latency, exactly the gap a quick manual
in-app test would notice. Current version adds a 4s gap between calls and
skips the run entirely if the device is already warm at start, so a report
you're comparing against a baseline is honest about whether the device was
actually cool throughout.

## Baselines

| Date | Device | Model | Thermal | Mean | Std dev | Min | Max | n |
|---|---|---|---|---|---|---|---|---|
| 2026-09-11 | Pixel 11 | Qwen3.5-4B-Q4_K_M | NONE throughout | 4.54s | 1.33s | 2.94s | 7.73s | 20 |

Per-call cost after warm-up (model load + system-prompt processing, a
separate ~10s one-time cost handled by `OnDeviceLlm.warmUp`, excluded from
this table -- see its doc comment). All 20 calls returned exactly 3
phrases, 0 failures. The spread here (2.9s-7.7s) is real call-to-call
variance even on a cool, unthrottled device -- some phrases legitimately
produce more tokens than others -- not measurement noise to explain away.

Levers worth trying against this baseline before assuming a change helped:
- Shorten the persona/few-shot system prompt (`personas/caregiver_infant.json`,
  `examples/few_shot_examples.caregiver_infant.by_language.json`) -- less to
  process doesn't change *this* number (system-prompt processing happens
  once at warm-up, not per call) but would speed up warm-up itself.
- Lower `PREDICT_LENGTH` in `OnDeviceLlm.kt` (currently 256) -- three short
  phrases rarely need it, but a too-low cap risks truncating a longer reply.
- Native thread tuning in `llama_bridge.cpp` (`N_THREADS_MAX`/
  `N_THREADS_HEADROOM`) -- untested here, needs its own before/after run.
- A smaller/faster GGUF than the current 4B model -- the biggest likely win,
  and the biggest change (new download, redo the "does it still answer
  well" check from `llm_lab/README.md`).
