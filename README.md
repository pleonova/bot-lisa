# Assistant Language Bot (Version 1: Russian)

## Why am I building this app

Before I moved to the US, the first English word I learned in school was fox (don't ask me why). Now that I have a baby, I want to teach them Russian, but my vocabulary isn't big enough to do it on my own, so I'm building an app to help. I named it Lisa (лиса), which means fox in Russian. Also, my baby's nursery theme just so happens to be foxes.

A little bit more about me: I grew up speaking Russian at home, but I never formally studied it, so somewhere along the way it turned into a comfortable hybrid I'd call Runglish. I understand conversational Russian just fine, I just don't always have the word I need on hand when I need it. Lisa is my way of closing that gap. When a word or phrase won't come to me, it translates from English to Russian. And based on either the Russian or English phrase, it recommends related phrases so I can keep expanding my vocabulary and hopefully my child's too.

## The technical part

Under the hood: it routes English text to translate-mode and Russian text to expand-mode.

A runnable skeleton covering all six build layers. Everything here works with
**zero external dependencies** (no API keys, no cloud account, no Docker
required to try the core logic) — each piece is a real, working minimal
version meant to be expanded, not a mockup.

## What's real vs. a placeholder

| Piece | Status |
|---|---|
| Phrase library (15 seed phrases) | Real, but tiny — expand this first |
| BM25 lexical retrieval | Real (`rank_bm25`) |
| Dense embeddings | Real (`fastembed`, open-source, ONNX-based, running `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` locally) with a minimum-similarity threshold in `hybrid.py`. See `services/retrieval_service/embeddings.py` |
| Hybrid BM25+embedding scoring | Real, working, tunable via `BM25_WEIGHT`/`EMBED_WEIGHT` env vars |
| Eval harness (NDCG, MRR, precision@k) | Real, run it: `python -m eval.run_eval` |
| LTR reranker | Real (logistic regression) — upgrade path to LightGBM noted in `reranker.py` |
| LLM ask layer | Real, runs in **mock mode** (no API key) by default; set `ANTHROPIC_API_KEY` for live generation |
| Perception event bus | Real in-memory pub/sub interface; services currently talk over HTTP directly — see `services/common/events.py` for the Kafka/Redis Streams swap-in point |
| Microservices split | Real — three independently runnable FastAPI services |
| Docker / docker-compose | Real, runs locally |
| Kubernetes manifests | **Scaffold** — correct shape, untested against a real cluster (try k3d/minikube first) |
| Terraform | **Scaffold** — documents intended resources, no provider wired up yet |
| CI (GitHub Actions) | Real workflow, runs tests + eval on every push |

## Language models

| Model | Role | Status |
|---|---|---|
| Claude (`claude-sonnet-4-6` via the Anthropic API) | Generates the "как ответить"-style grounded phrase and translates English → baby-register Russian in `services/orchestration_service/llm_client.py` | **In the app.** Runs in mock mode (no API call, returns the top retrieved phrase verbatim) unless `ANTHROPIC_API_KEY` is set — see the status table above |
| `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` (via `fastembed`, ONNX, local) | Dense embeddings for hybrid BM25+embedding retrieval | **In the app** — `services/retrieval_service/embeddings.py` |
| Qwen3.5, Q4_K_M (4B and 2B GGUF) | On-device generation for "что ещё" (routine relations); "как ответить" (response suggestions) still curated-library only | **In the app** for "что ещё" — runs via `OnDeviceLlm` behind a Settings toggle (`WhatElseSource`) + a model download, with curated-library fallback. See `llm_lab/README.md` and `android/ON_DEVICE_LLM_PLAN.md` |
| Gemini Nano/AICore, Gemma 3n via LiteRT-LM, Kimi (Moonshot) | Alternatives considered for the on-device slot above | **Ruled out** — Gemini Nano needs a Pixel 8+ NPU and is closed-weight (against the project's open-source preference); Gemma 3n benchmarks worse than Qwen3.5 for its size class; Kimi's smallest release (48B) has no on-device-class model. Full reasoning in `llm_lab/README.md`'s "Candidate model" section |
| A bigger model via cloud API / retrieval-only fallback | Fallback plan if Qwen3.5 fails llm_lab's Phase 1 quality gate | **Slated, contingent** — only pursued if the on-device quality check fails |
| Self-hosted ASR with per-segment language ID (e.g. Whisper/faster-whisper) vs. a cloud multi-language STT API | Needed for the hands-free "Lisa Assistant" mode's continuous listening (`RecognizerIntent` has no built-in language auto-detect) | **Slated, undecided** — see [ROADMAP.md](ROADMAP.md)'s item #2; the open-source-vs-cloud tradeoff needs a decision before build starts |

## Supported languages & translation

The Android app supports multiple target languages, each with its own locale
code and (mostly) its own default trigger phrases (`LanguageConfig.kt`,
`TriggerPhraseConfig.kt`) — see those files for the current list, since it
changes as languages are added.

**Only one real translation direction exists: English → target language.**
There's no target-language → target-language or target-language → English
path, and the "expand mode" (related-phrase suggestions) only works for
Russian, since it's tied to the Russian curated phrase library and the
backend's mode-detection (`_has_cyrillic()` in
`services/orchestration_service/main.py`) is Cyrillic-specific — it doesn't
key off the app's selected target language at all.

**Two separate translation mechanisms, split by language:**
- **Russian** goes through the backend: a curated-phrase lookup first, then
  Claude (`llm_client.py`) as a fallback, producing warm "baby-register"
  Russian rather than textbook phrasing.
- **Every other target language** is translated entirely on-device via
  Google ML Kit (`OnDeviceTranslator.kt`) — plain/textbook phrasing, no
  baby-register tuning, downloads a ~30MB model per language on first use
  (offline after that). The backend has no parameter for "translate to
  French/Hindi/etc." — it only distinguishes English vs. Russian.

STT (speech-to-text) listens in whatever target language is selected
(`SpeechAssistant.kt`'s `getDefaultLanguageCode()`), so dictation isn't
Russian-only — but only Russian has a localized "как ответить"-style answer
trigger; every other language falls back to the English-worded phrase for
that trigger by default.

## Quickstart

```bash
pip install -r requirements.txt

# 1. Run the eval harness (no servers needed)
PYTHONPATH=. python -m eval.run_eval

# 2. Run tests
PYTHONPATH=. pytest tests/ -v

# 3. Run all three services locally
PYTHONPATH=. uvicorn services.retrieval_service.main:app --port 8001 &
PYTHONPATH=. uvicorn services.orchestration_service.main:app --port 8002 &
PYTHONPATH=. uvicorn services.ingestion_service.main:app --port 8003 &

curl -X POST http://localhost:8003/event/voice \
  -H "Content-Type: application/json" \
  -d '{"transcript": "малыш хочет спать", "routine_hint": "sleep"}'

# 4. Or via Docker Compose
docker compose -f infra/docker-compose.yml up --build
```

## Directory map

```
phrase_library/phrases.json       # 15 seed phrases — Russian curated library (expand this)
services/                         # Python backend — three independently runnable FastAPI services
  common/
    config.py                     # shared env/config
    events.py                     # PerceptionEvent schema + in-memory bus (Kafka/Redis swap point)
  retrieval_service/              # BM25 + hybrid + LTR retrieval, port 8001
    bm25_index.py
    embeddings.py                 # fastembed / ONNX dense embeddings (already real)
    hybrid.py
    reranker.py
    main.py
  orchestration_service/         # /assist (app) + /ask (paused), port 8002
    llm_client.py                 # mock mode / live Claude API
    main.py
  ingestion_service/             # voice/vision event entry point, port 8003
    main.py
eval/
  labeled_eval_set.json           # 8 labeled queries — reranker training + retrieval eval ground truth
  run_eval.py                     # NDCG/MRR/precision@k across bm25_only / hybrid / ltr_reranked
llm_lab/                          # prompt-engineering lab for the on-device "what else" / "how to answer" features
  prompts/
    compose_prompt.py             # persona + per-language examples + task template -> (system, user)
    what_else.json, how_to_respond.json     # task templates
    personas/                     # caregiver_infant, adult_adult — tone/register + {gender}
    examples/                     # per-(task, lang, persona) few-shot data; *.by_language.json seeds every language
  eval/                           # its own eval harness + cases, separate from eval/ above
  playground.py                   # quick manual system/user prompt testing
speech_lab/                       # offline STT + per-utterance language-ID experiments (Whisper/faster-whisper)
  lang_id.py  transcribe.py  trigger_flow.py  trigger_phrase.py
infra/
  Dockerfile
  docker-compose.yml
  k8s/                            # orchestration-service + retrieval-service: live on DOKS. paused/: not applied
  terraform/main.tf              # scaffold, no provider configured
tests/test_retrieval.py
android/                          # Kotlin/Compose app — see android/README.md
  app/                            # MainActivity + Compose UI, SpeechAssistant, TriggerPhraseConfig, PromptComposer, …
    src/main/assets/llm_prompts/  # prompt JSON hand-copied from llm_lab/ (drift risk, noted in PromptComposer.kt)
  onDeviceLlm/                    # vendored llama.cpp JNI module
  ON_DEVICE_LLM_PLAN.md  PHONE_DEPLOY.md  UI_REDESIGN_PLAN.md
ROADMAP.md                        # bigger multi-layer feature bets (hands-free Lisa Assistant)
```

## Hosting

The backend runs on a single-node DigitalOcean Kubernetes (DOKS) cluster
(`bot-lisa-cluster`, see `infra/terraform/main.tf` and `infra/k8s/`).
`android/PHONE_DEPLOY.md` has the full deploy/update runbook.

**Currently live:** `orchestration-service` (exposed via a DigitalOcean
LoadBalancer — the phone app talks to its external IP directly, see
`infra/k8s/orchestration-service.yaml`) and `retrieval-service` (internal
only, `type: ClusterIP`, no public IP or LoadBalancer charge — called only by
`orchestration-service`, never by the phone directly).

**Not currently deployed:** `ingestion-service` — it served the paused
child-perception voice pipeline (see "Paused features" below) and nothing
calls it today. Its manifest lived in `infra/k8s/` with `type: LoadBalancer`
for 31 days, unused, billing for a public IP nothing needed. Torn down
2026-09-17 and moved to `infra/k8s/paused/ingestion-service.yaml` — apply
that file again if the feature is revisited.

### Why the DigitalOcean bill moves the way it does

DigitalOcean's Kubernetes charges are **fixed monthly costs for what's
provisioned, not for how much the app is actually used** — a LoadBalancer
and a node cost the same whether zero people or a thousand people hit the
app that day. That's why "traffic" is never the right first suspect for a
cost spike here; an extra `type: LoadBalancer` Service (like the
`ingestion-service` one above) or a bigger/extra node is. DigitalOcean's
Spend Alerts (Billing → Spend alerts) can warn you past a threshold, but
they're notifications only — DigitalOcean has no hard spending cap that
auto-shuts-down resources.

### What needs the server vs. what runs on-device (Android app)

| Feature | On-device today? | Needs the server? |
|---|---|---|
| **Translate** (English → target language) | Yes — on-device ML Kit is tried first (`OnDeviceTranslator.kt`, see `MainActivity.kt`'s `onSend()`) | Only as a backup if on-device translation itself fails |
| **"Что ещё" (what-else suggestions)** | Yes — on-device Qwen (`OnDeviceLlm.generateWhatElse`) is tried first once the ~2.7GB model is downloaded | Only if the on-device model isn't ready/available — falls back to whatever the last server call already returned, not a fresh call |
| **Typed/spoken Russian phrase → "Look up" (expand mode)** | No on-device path exists | **Yes, every time** — hits `orchestration-service`'s `/assist`, which queries `retrieval-service`'s curated phrase library |
| **"Как ответить" (how to respond)** | Not built yet | **Yes, always** |

The two "yes, always" rows above are the reason the cluster can't be deleted
outright today.

## Suggested expansion order

Active work is on the Android app, the on-device LLM (`llm_lab/` →
`android/onDeviceLlm/`), and speech/language-ID (`speech_lab/`) — see
**Ideas** below and [ROADMAP.md](ROADMAP.md). The steps here are
backend/infra scaffold-completion, largely independent of that:

1. **Grow the phrase library** — still 15; the original plan was 75+.
2. **Grow the labeled eval set** — still 8. The similarity threshold, the
   LTR reranker, and the eval numbers all get more trustworthy with more
   data. (Real embeddings are already in — see the status table.)
3. **Wire up live LLM mode** — set `ANTHROPIC_API_KEY` and sanity-check
   `services/orchestration_service/llm_client.py`'s system prompt against
   real generations.
4. **Add a Java (or Scala) retrieval hot-path service** — the retrieval
   service's `/search` endpoint is the latency-sensitive piece.
5. **Swap the inter-service HTTP calls for a real queue** (Kafka or Redis
   Streams) via the `EventBus` interface in `events.py`.
6. **Try the k8s manifests against k3d/minikube**, then fill in
   `terraform/main.tf` once you pick a cloud.

## Done so far

High-level, in rough build order — details live in the status tables above,
`android/README.md`, and the git log.

- **Caregiver-facing assist flow** over `POST /assist`: translate mode
  (English → target language) and Russian "expand" mode (say a Russian
  phrase, get related ones from the curated library).
- **Two translation paths:** Russian goes through the backend (curated
  lookup → Claude fallback, warm baby-register); every other language
  translates on-device via Google ML Kit.
- **Hands-free "Lisa Assistant" listening mode** — foreground service for
  background mic, fuzzy trigger-phrase matching (`SpeechAssistant.kt`,
  `TriggerPhraseDetector.kt`).
- **Four voice commands**, editable in Settings (`TriggerPhraseConfig.kt`):
  "how to say?", "what does that mean?", and "what else?" localized for
  every language ("what else?" needs a device that can run the on-device
  model); "how to answer?" is Russian only (curated library).
- **11 target languages** selectable in Settings, including Spanish,
  Mandarin, and Romanian (`SupportedLanguages.ALL`).
- **Translation under the transcript** — small-print gloss with a play
  button; command-reminder chips auto-hide when you type or use a command
  and reappear on new input, and tapping a chip speaks it aloud for
  pronunciation.
- **Prompt pipeline** (`llm_lab/prompts/compose_prompt.py`: persona +
  per-language examples + task template) ported to Kotlin `PromptComposer`,
  with a JVM test checked against the Python reference.
- **On-device LLM** — vendored llama.cpp JNI bridge running Qwen for the
  "what else" feature, wired into the app (`OnDeviceLlm`) behind a
  Settings toggle (`WhatElseSource`) and a model download, with
  curated-library fallback. Verified on-device.
- **"What else?" in any target language** — `PromptComposer` reads the
  Settings language dropdown and loads the matching few-shot block from
  `assets/llm_prompts/examples/few_shot_examples.caregiver_infant.by_language.json`
  (a seed for all 11 languages, same two scenarios as Russian; only the
  Russian block is native-tuned, the rest are machine translations users
  can improve). `OnDeviceLlm` reloads the model when the language changes;
  a per-utterance card shows the AI suggestions (and a generating / empty
  state) since only Russian gets a backend result card. The Russian
  curated phrase library and "how to answer?" stay Russian-only.
  Reasoning suppression: the bundled Qwen3.5-4B would otherwise spend the
  whole token budget in a `<think>` block, so `llama_bridge.cpp` prefills
  an empty `<think></think>` onto the assistant turn (what a Jinja
  `enable_thinking=false` does) — verified on a Pixel 11. `OnDeviceLlm.warmUp`
  pre-loads the model + system prompt when hands-free starts, so that ~10s
  one-time cost lands before the caregiver's first utterance instead of
  after it.
- **Suggestion timing setting** — generating on-device suggestions for
  *every* hands-free phrase (not just when "what else?" is actually asked)
  measurably heats the device and costs battery (see the benchmark below).
  Settings → "Suggestion timing" lets the caregiver pick **Eager**
  (default: instant answers, but runs after every phrase and auto-backs-off
  once `OnDeviceLlm.isThermallyElevated`) or **On-demand** (only generates
  when asked, at the cost of a short pause). `OnDeviceLlmConfig.PrefetchMode`.
- **English glosses on AI suggestions** — `generateWhatElse` translates each
  on-device suggestion to English via the same on-device ML Kit path as the
  transcript gloss (`OnDeviceTranslator`), so AI phrases show a gloss the
  same way curated-library ones already did. Degrades to no gloss on
  failure rather than failing the whole request.
- **"What else?" latency + memory benchmarks** — `WhatElseBenchmarkTest`
  (`android/app/src/androidTest/`) times `generateWhatElse` over 20 real
  phrases on-device and reports mean/std/min/max, plus a `checkMemoryFootprint`
  test; baselines and a real finding — the LLM alone already runs at this
  device's ~3GB memory ceiling, and sustained eager usage can get the app
  killed by the OS — live in `android/app/benchmarks/README.md`.

## Ideas

Not started (or only half-wired). Bigger multi-layer bets — e.g. hands-free
Lisa Assistant's remaining pieces — live in [ROADMAP.md](ROADMAP.md).

**Prompt inputs, editable from Settings.** Nothing below is in the UI yet;
`PromptComposer.kt` still hardcodes the persona and `{gender}` (the
few-shot examples now follow the selected language — see "Done so far").

- **Gender** — `{gender}` already defaults to **boy** in
  `personas/caregiver_infant.json`; add a Settings toggle for **girl**.
- **Edit `FEW_SHOT_EXAMPLES` in Settings** — override the per-language
  seed in
  `assets/llm_prompts/examples/few_shot_examples.caregiver_infant.by_language.json`:
  edit both demos (each demo's "heard" phrase and its three responses),
  persisted per language. Lets users hand-tune the machine-translated
  blocks the seed ships with.
- **Persona** — pick baby / child (by age) / adult, choosing which
  `personas/*.json` the prompt uses (`caregiver_infant`, `adult_adult`
  exist).

**Settings UI.** Today it's one flat inline panel (`showServerSettings` in
`MainActivity.kt`).

- **Dedicated Settings screen**, with related controls grouped into
  sections (Appearance, Voice commands, Server, …) instead of one list.
- **Appearance** — keep dark mode as a simple toggle, grouped here.
- **Voice commands section** — all trigger phrases together, with defaults
  that are proper, grammatical, capitalized translations ("How to say?",
  "Как сказать?" — the current defaults are lowercase).
- **"What Else" prompt section/page** — a separate screen where the
  per-language few-shot examples and `{gender}` can be overridden (the
  "Edit `FEW_SHOT_EXAMPLES`" and "Gender" items above live here).
- **Slow down spoken responses** — a speech-rate toggle in Settings to
  slow down TTS playback, for easier listening/learning.

**Usage history & reports.** Builds on the logging/dashboard work already
sketched for hands-free mode in [ROADMAP.md](ROADMAP.md) (#4–#6).

- **Turn on history** — an opt-in toggle that stores every phrase said or
  typed (with timestamp, language, and mode). Nothing is persisted today.
- **Usage charts / tables** — over that history: frequency of individual
  spoken terms, the proportion of each language the speaker uses, how
  often each voice command is used, and vocabulary variety (distinct
  words / type-token ratio over time).
- **Speaker recognition** — tell speakers apart (voice-print or a
  manual "who's talking" switch) and break the reports down per speaker.

**Cost-aware generation cascade.** Today generation is on-device only:
"what else" runs Qwen via `OnDeviceLlm` (default `WhatElseSource` is
`BOTH` — AI once the model is downloaded, otherwise the curated Russian
library); translate mode is a curated lookup or on-device ML Kit. The
backend Claude path (`llm_client.py`) only runs if `ANTHROPIC_API_KEY` is
set, which it isn't.

- **Add a provider-API tier on top** — call a large hosted model for best
  quality up to a configurable monthly spend cap; once it's hit, fall
  back to on-device (Qwen / ML Kit), then to the curated library.
- **Cache LLM results** — key on the input phrase (+ language + mode) so
  repeats are free, and promote frequently-hit generations into the
  curated library.

**Other.**

- **Swap primary ↔ secondary language** — flip the "from" and "to"; today
  it's always English → target.
- **Generalize expand mode past Russian** — related-phrase suggestions are
  Russian-only (curated library + `_has_cyrillic()` in
  `services/orchestration_service/main.py`); needs curated content for a
  second language and a script-aware detector. See ROADMAP.
- **Distinct listening notification icon** — the ongoing foreground
  notification while hands-free is running (`ListeningForegroundService.kt`)
  currently uses the app launcher icon as its status-bar small icon. Give
  it a dedicated **orange speech-bubble** icon so "Lisa is listening" is
  recognizable at a glance in the Android status bar.

## Paused features

Things that are built, tested, and working, but not currently wired up to
anything actively used — kept here so they don't get lost or accidentally
re-discovered as "is this broken?" later.

### Child-perception voice pipeline

**What it is:** the original flow — `ingestion-service`'s `POST /event/voice`
receives a transcript (something the child/caregiver said near the device),
wraps it in a `PerceptionEvent`, and forwards it to `orchestration-service`'s
`POST /ask`, which returns a single grounded Russian phrase to speak back.

**Status:** code is untouched and still works — covered by `tests/`, runs
fine standalone via `uvicorn services.ingestion_service.main:app --port 8003`.

**Why paused:** the Android app was repurposed to be caregiver-facing instead
(translate English → Russian, or expand on a Russian phrase with related
ones from the library, via the new `POST /assist` endpoint on
`orchestration-service`). The app no longer calls `/event/voice` or `/ask`.

**Revisit when:** the caregiver-assist flow is solid and there's appetite to
build the child-directed side back in — natural to pair with finishing the
still-stubbed `POST /event/vision` endpoint (camera/book recognition), since
both are pieces of the same "perception event" concept.

## Known issues

### `ORCHESTRATION_API_KEY` still travels over plain HTTP

`/ask` and `/assist` are exposed via a DigitalOcean LoadBalancer (see
`infra/k8s/orchestration-service.yaml`) and guarded by an `X-API-Key` header
(see `require_api_key` in `services/orchestration_service/main.py`), but the
Android app talks to it over plain `http://`, not `https://` — so the key
itself travels in the clear and could be sniffed on an untrusted network.

**Why it's not fixed yet:** a real TLS certificate (e.g. DigitalOcean's free
Let's Encrypt integration on the LoadBalancer) needs a domain name pointed
at the LoadBalancer's IP — Let's Encrypt can't issue a cert for a bare IP,
and this project doesn't have a domain/DNS set up yet.

**TODO:** decide on a domain + DNS setup, add a DigitalOcean-managed cert to
the `orchestration-service` LoadBalancer, and switch the Android app's
server URL to `https://`. Once that's done, also scope
`android:usesCleartextTraffic="true"` in
`android/app/src/main/AndroidManifest.xml` to debug builds only (or drop
it) — shipping it lets the app be tricked into sending data over plain HTTP
to any host, not just the dev machine.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Open an issue before starting a large
feature, and keep PRs small and single-purpose.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free to use, modify, and share for
personal and non-commercial purposes. © 2026 Paula Leonova.
