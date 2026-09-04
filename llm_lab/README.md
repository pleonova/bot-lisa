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
│   │   ├── caregiver_infant.json  # id / description / default_speaker / system_template
│   │   └── adult_adult.json       # alt persona (two adults, peer register)
│   ├── examples/            # bad/good contrastive pair, per <task>.<lang>.<persona>
│   │   ├── what_else.ru.caregiver_infant.json
│   │   ├── what_else.ru.adult_adult.json
│   │   ├── how_to_respond.ru.caregiver_infant.json
│   │   └── how_to_respond.ru.adult_adult.json
│   ├── compose_prompt.py     # stitches template + persona + example + case for any task
│   ├── what_else.json        # "что ещё" trigger — task rules only (user_template), no cases
│   └── how_to_respond.json   # "как ответить" trigger — task rules only (user_template), no cases
├── eval/
│   ├── run_eval.py         # runs model sizes × prompt sets; --model/--set/--case to narrow
│   ├── cases/               # eval cases, per <task>_<persona>, each self-describing persona+language
│   │   ├── what_else_caregiver_infant.json       # bedtime/mealtime/bathtime + _generic twins
│   │   ├── what_else_adult_adult.json            # meeting wrap-up + _generic twin
│   │   ├── how_to_respond_caregiver_infant.json  # question/comment/greeting + _generic twins
│   │   └── how_to_respond_adult_adult.json       # deadline question + _generic twin
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
Everything worth tweaking is plain text under `prompts/` and `eval/cases/`.
Both tasks (`what_else`, `how_to_respond`) use the same split: template /
persona / example / case, stitched together by `prompts/compose_prompt.py`.

| To change… | Edit |
|---|---|
| task wording / rules | `user_template`, `utterance_label` in `prompts/<task>.json` |
| a case (utterance, `activity`/`input_kind`, hint examples) | `eval/cases/<task>_<persona>.json` — each case carries its own `persona` + `language` |
| the contrastive bad/good example | `prompts/examples/<task>.<lang>.<persona>.json` |
| voice: who speaks, register | `prompts/personas/<persona_id>.json` (`system_template`, `default_speaker`) |
| sampling (temp, seed, max tokens) | `GEN_PARAMS` near the top of `eval/run_eval.py` |

Then re-run a narrow slice instead of the full sweep — a one-case run is ~7s:

```bash
# show the exact prompt a change produces — no model call
python3 llm_lab/eval/run_eval.py --set what_else --case bedtime_1 --dry-run

# run one size / set / case and print results inline
python3 llm_lab/eval/run_eval.py --model 4b --set what_else --case bedtime_1 --print

# try another audience
python3 llm_lab/eval/run_eval.py --persona adult_adult --set how_to_respond --print

# generic-vs-hinted A/B: does the rule + bad/good contrast alone generalize,
# without the case's own examples hint? (works for either task)
python3 llm_lab/eval/run_eval.py --set what_else --case bedtime_1 --print
python3 llm_lab/eval/run_eval.py --set what_else --case bedtime_1_generic --generic --print
python3 llm_lab/eval/run_eval.py --set how_to_respond --case question_1 --print
python3 llm_lab/eval/run_eval.py --set how_to_respond --case question_1_generic --generic --print

# full sweep once you're happy (both sizes, both sets, all cases)
python3 llm_lab/eval/run_eval.py
```

Every case has a `_generic` twin (same utterance/activity/input_kind, no
`examples` hint) — pass `--generic` when running it so the per-case hint is
actually dropped rather than just missing from that one case's JSON.

`--case` is repeatable. Every non-dry run also writes
`eval/outputs/<model>_<set>_<persona>_<timestamp>.json`. `--help` lists all flags.

## Personas
The prompt sets carry the *task* (what to suggest, with concrete rules) but not
the *voice*. Who is speaking and in what register lives in
`prompts/personas/<persona_id>.json` (`system_template`, `default_speaker`).
`run_eval.py` uses `caregiver_infant` by default; pick another with
`--persona <persona_id>` (or `$LLM_LAB_PERSONA`):

```bash
python3 llm_lab/eval/run_eval.py --persona adult_adult
```

`--persona` does double duty: for both tasks it also selects which case file
runs, `eval/cases/<task>_<persona_id>.json` — so each persona gets its own
realistic scenario instead of forcing one persona's cases onto another's
voice (bedtime/mealtime/bathtime + a "Хочешь ещё?"-style Q&A for
`caregiver_infant`; a work-meeting wrap-up + a deadline question for
`adult_adult`).

Adding a new persona means: a `personas/<id>.json`, an
`examples/<task>.<lang>.<id>.json` bad/good pair per task, and an
`eval/cases/<task>_<id>.json` per task you want it to cover — no edit to
`prompts/what_else.json`, `prompts/how_to_respond.json`, or the other
personas' files.

Output filenames include the persona (`<model>_<set>_<persona>_<ts>.json`,
or `..._<persona>_generic_<ts>.json` under `--generic`), so runs don't
collide.

Review `eval/outputs/` with the collaborator before deciding whether to proceed to Phase 3.
