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
│   ├── personas/
│   │   ├── caregiver_infant.json  # default: speaker / addressee / language / register
│   │   └── adults.json            # example alt persona (two adults)
│   ├── what_else.json        # "что ещё" trigger — cases + follow-up examples
│   └── how_to_respond.json   # "как ответить" trigger — cases + reply examples
├── eval/
│   ├── run_eval.py         # runs model sizes × prompt sets; --model/--set/--case to narrow
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

## Iterating on prompts
Everything worth tweaking is plain text under `prompts/`:

| To change… | Edit |
|---|---|
| task wording / the utterance label | `instruction`, `utterance_label` in `what_else.json` / `how_to_respond.json` |
| a test case or its examples | the `cases[]` entry — `utterance`, `activity` / `input_kind`, `examples`, optional `context` |
| voice: who speaks, register, language | `prompts/personas/<name>.json` |
| sampling (temp, seed, max tokens) | `GEN_PARAMS` near the top of `eval/run_eval.py` |

Then re-run a narrow slice instead of the full sweep — a one-case run is ~7s:

```bash
# show the exact prompt a change produces — no model call
python3 llm_lab/eval/run_eval.py --set what_else --case bedtime_1 --dry-run

# run one size / set / case and print results inline
python3 llm_lab/eval/run_eval.py --model 4b --set what_else --case bedtime_1 --print

# try another audience
python3 llm_lab/eval/run_eval.py --persona adults --set how_to_respond --print

# full sweep once you're happy (both sizes, both sets, all cases)
python3 llm_lab/eval/run_eval.py
```

`--case` is repeatable. Every non-dry run also writes
`eval/outputs/<model>_<set>_<persona>_<timestamp>.json`. `--help` lists all flags.

## Personas
The prompt sets carry the *task* (what to suggest, with concrete examples) but
not the *voice*. Who is speaking, to whom, in what language and register lives in
`prompts/personas/<name>.json` and is substituted into the system prompt and the
`{speaker}` slot of each instruction. `run_eval.py` uses `caregiver_infant` by
default; pick another with `--persona <name>` (or `$LLM_LAB_PERSONA`):

```bash
python3 llm_lab/eval/run_eval.py --persona adults
```

Output filenames include the persona name (`<model>_<set>_<persona>_<ts>.json`),
so caregiver and adult runs don't collide. Add a new `personas/*.json` to test
another audience — no change to the prompt sets or the script.

Review `eval/outputs/` with the collaborator before deciding whether to proceed to Phase 3.
