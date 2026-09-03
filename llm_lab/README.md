# llm_lab — On-Device Model Evaluation

Evaluation harness for the on-device LLM that will power two new features:
- **"что ещё" routine relations** — contextually related phrases (e.g. "спокойной ночи" → brush teeth, lights off), beyond what embedding similarity alone can find.
- **"как ответить" (how to respond)** — new trigger phrase, suggests common responses to the last heard question/comment.

Follows the same "prototype in a scratch folder before touching production code" pattern as `speech_lab/` — nothing here is wired into the Android app or backend yet. This folder exists purely to answer one question first: **is a small on-device model's Russian output good enough to build on?**

## Candidate model
**Qwen3.5**, Q4_K_M quantization, two sizes tested side by side:
- `qwen3.5-4b-q4_k_m.gguf` (~2.8GB) — primary candidate
- `qwen3.5-2b-q4_k_m.gguf` (~1.5GB) — fallback if 4B is too slow on-device, or if quality difference turns out negligible

**Why Qwen, not the alternatives considered:**
- Gemini Nano/AICore — ruled out for the Pixel 6 (needs Pixel 8+ NPU), viable on the Pixel 11 (Tensor G6) but closed weights, against the project's standing "prefer open source" preference
- Gemma 3n via LiteRT-LM — open, good Google tooling, but Qwen3.5's small series benchmarks better for its size class
- Kimi (Moonshot) — ruled out entirely; smallest model is Kimi Linear at 48B total params, no on-device-class release exists
- Running server-side on the DOKS cluster — ruled out; the node is `s-1vcpu-2gb`, already tight with three existing services, no room for a model process

## Phases (run in order — stop early if a phase fails)

### Phase 1 — Local quality check (Mac, Homebrew `llama.cpp`)
No RAM/CPU constraints to muddy the read. Run both model sizes against the real prompt set in `prompts/`, review output with the Russian-speaking collaborator for register (infant-directed vocabulary, diminutives, sentence length) and contextual relevance. **This is the actual go/no-go gate** — if quality fails here, it fails everywhere, and an alternate approach (bigger model via cloud API, retrieval-only fallback, etc.) needs consideration before any further build work.

### Phase 2 — Local speed benchmark (Mac)
Only run once Phase 1 passes. `llama-bench` on the same machine gives a rough tokens/sec ceiling — informative, but Apple Silicon numbers won't transfer directly to Android hardware.

### Phase 3 — Termux on-device check (Pixel 11)
Same GGUF files, same prompts, run via Termux (F-Droid build) directly on the Pixel 11. Confirms quality survives the ARM/Android crossing and gives a real speed number on the actual target chip (Tensor G6) — without writing any Kotlin/JNI code yet.

### Phase 4 — Android JNI integration
Only start this once Phases 1–3 all pass. Wraps `llama.cpp`'s `examples/llama.android` scaffold into `OnDeviceLlm.kt`, wired into `SpeechAssistant.kt`'s new "как ответить" trigger path. Out of scope for this folder — tracked separately once reached.

## Folder structure
```
llm_lab/
├── README.md
├── .gitignore              # keeps large GGUF files and raw outputs out of git
├── prompts/
│   ├── routine_relations.json     # test cases for "что ещё"
│   └── response_suggestions.json  # test cases for "как ответить"
├── eval/
│   ├── run_eval.py         # runs both model sizes against both prompt sets
│   └── outputs/            # generated outputs land here for review (gitignored)
└── models/                 # place downloaded .gguf files here (gitignored)
```

## Setup
```bash
brew install llama.cpp   # provides llama-server (Phase 1) and llama-bench (Phase 2)

# Same Q4_K_M GGUFs we'd run on-device. Unsloth repos; bartowski's
# (bartowski/Qwen_Qwen3.5-{4B,2B}-GGUF) are interchangeable.
mkdir -p ~/models && cd ~/models
curl -L -O https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf
curl -L -O https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf

cd -                                   # back to repo
python3 llm_lab/eval/run_eval.py       # stdlib only — no venv, no pip
```
`run_eval.py` drives `llama-server` over its HTTP API (one model load per size).
It finds the GGUFs under `$LLM_LAB_MODELS`, then `~/models`, then `llm_lab/models/`.
Overrides: `$LLAMA_SERVER` (binary path), `$LLM_LAB_PORT` (default 8080).

> Not using `llama-cpp-python`: PyPI ships it source-only, and the prebuilt
> wheels on abetlen's index currently fail a CRC check on extract. The
> `llama-server` route needs no Python build and reuses the same brew install
> as Phase 2.

Review `eval/outputs/` with the collaborator before deciding whether to proceed to Phase 3.
