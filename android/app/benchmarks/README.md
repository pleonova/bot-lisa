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

**Stale as of the on-device English gloss addition** (`generateWhatElse` now
also runs 3 sequential `OnDeviceTranslator.translateToEnglish` calls per
request) -- this table predates that and needs a re-run to include it. Not
re-run yet here because a full 20-call pass reliably hits the memory issue
below.

## Memory: the LLM alone already runs at the device's ceiling

`checkMemoryFootprint` in the same test class measures PSS (proportional
set size) around one `generateWhatElse` call. On the same Pixel 11:

| Point | PSS |
|---|---|
| Idle | 147MB |
| Right after LLM generation (before any gloss translation) | **3004MB** |
| After translating all 3 glosses | 3091MB |

The LLM *by itself* already sits at ~3004MB -- essentially at this device's
`memory.high` cgroup ceiling (`memHigh=3221225472` bytes = 3072MB, read from
`MemoryLimiter` logcat lines). Gloss translation adds ~90MB on top, which
isn't fatal for one request (`memory.high` is a soft/throttling signal,
not an instant kill) but is exactly the kind of margin that turns into one:
running the 20-phrase `benchmarkGenerateWhatElse` benchmark with gloss
translation included got the app process **killed outright** by the OS
(`MemoryLimiter: killing process ...`) after `anon+swap` stayed over the
3GB line for about 90 seconds straight.

This is a pre-existing fragility in the LLM's own footprint (Qwen3.5-4B at
this quantization + its 2048-token context, per `llama_bridge.cpp`), not
something the gloss translation invented -- it just spends down margin that
was already almost gone. `OnDeviceLlmConfig.PrefetchMode.ON_DEMAND` reduces
how often this window gets hit in real usage (generation only on an actual
"what else?" ask, not after every phrase); it doesn't remove the ceiling.
Reducing the LLM's own footprint (shorter context, a smaller model) is the
real fix if this proves fatal in practice -- see `checkMemoryFootprint`'s
doc comment and `OnDeviceLlm.generateWhatElse`'s own "MEMORY" comment.

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
