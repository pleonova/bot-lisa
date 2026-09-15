# Bot Lisa — Codebase Overview (Beginner's Guide)

A guided tour of this repo for someone reading it for the first time —
especially useful if you're also new to Android development. This
complements [README.md](README.md) (which has the authoritative status
tables and quickstart) rather than replacing it: this document explains
*how the pieces fit together and why*, with beginner-friendly detours into
Android/ML concepts as they come up. Where something is genuinely
open-ended or "as of writing," it says so explicitly rather than
presenting it as fixed fact — see [README.md](README.md) for the tables
that are kept current.

---

## 1. What this app actually does

One caregiver-facing Android screen with a text box. Type or say something:

- **English in** → a translation into your chosen target language (Russian
  by default), spoken aloud.
- **Russian in** (Russian is the only language with curated content) → a
  list of related phrases from a hand-picked library, so you can expand
  your own vocabulary around what you just said.

There's also a hands-free "Lisa Assistant" mode: tap once, then keep
talking — the app listens continuously, recognizes a few spoken voice
commands ("how do you say...?", "what does that mean?", "what else?"), and
speaks results back through your earbuds.

Everything else in this repo — the Python backend, the on-device
translation/LLM pieces, the experiment folders — exists to make that one
screen work well.

---

## 2. The big picture

```
                          ┌─────────────────────────────┐
                          │      Android app             │
                          │  (Kotlin + Jetpack Compose)  │
                          │                               │
   mic / keyboard  ─────► │  MainActivity.kt (the screen) │
                          │        │           │           │
                          │        │           └──► on-device
                          │        │                ML Kit translate
                          │        │                (OnDeviceTranslator.kt)
                          │        │
                          │        └──► on-device LLM "what else?"
                          │             (OnDeviceLlm.kt → onDeviceLlm module
                          │              → llama.cpp via JNI)
                          │        │
                          └────────┼───────────────────────┘
                                   │  HTTP (POST /assist)
                                   ▼
                    ┌───────────────────────────────┐
                    │   orchestration-service :8002   │   "the brain"
                    │   (Python, FastAPI)              │   English vs Russian?
                    │   curated-lookup → Claude fallback│  curated vs LLM?
                    └───────────────┬───────────────────┘
                                    │ HTTP (POST /search)
                                    ▼
                    ┌───────────────────────────────┐
                    │   retrieval-service :8001        │   "the memory"
                    │   BM25 + embeddings + reranker    │   ranks phrase_library
                    │   over phrase_library/phrases.json│   candidates
                    └───────────────────────────────┘

   (ingestion-service :8003 also exists — a separate, currently-unused
    entry point for a paused feature. See §5.3.)
```

Two translation mechanisms exist side by side — this trips people up, so
it's worth stating plainly up front. **As currently implemented** (see
`MainActivity.kt`'s `onSend()`, and §11's note on doc drift below), English
input in *any* target language tries on-device Google ML Kit first and only
falls back to the backend if that throws:

| Input | Path | Tone |
|---|---|---|
| English → **any target language, including Russian** | On-device Google ML Kit first (fast, offline); backend (curated lookup → Claude fallback) only if on-device translation fails/isn't downloaded yet | On-device: plain/textbook phrasing. Backend fallback: warm "baby-register" Russian, when it's the path actually taken |
| **Russian** → related phrases ("expand mode") | Curated library only, via the backend | N/A (retrieval, not translation) |

Only Russian gets the "expand mode" related-phrase feature — that part
really is a deliberate, unchanged scope decision (curated content is
Russian-only). The *translation* priority between on-device and
backend/curated, however, was flipped by a later commit than the one that
first documented it — see §11.

---

## 3. Repo map, annotated by status

| Folder | Status | What it's for |
|---|---|---|
| `android/` | **Active** | The Kotlin/Compose app — what the user actually installs |
| `android/onDeviceLlm/` | **Active** | A separate Android library module wrapping `llama.cpp` (C++) so the app can run a small local LLM |
| `services/` | **Active (partially paused)** | Three Python FastAPI microservices — the backend |
| `phrase_library/phrases.json` | **Active, intentionally tiny** | The curated Russian phrase data everything else is built around (15 entries as of writing) |
| `llm_lab/` | **Prototype / research** | Standalone harness for testing on-device LLM prompts *before* touching the app — not imported by the app or backend |
| `speech_lab/` | **Prototype / research** | Standalone experiments in telling English/Russian speech apart — informed the app's trigger-phrase design, isn't imported by it |
| `eval/` (root) | **Active** | Offline retrieval-quality evaluation (NDCG/MRR/precision@k) for `services/retrieval_service` |
| `infra/` | **Scaffold** | Docker Compose (real, runs locally), Kubernetes manifests + Terraform (documented shape, not battle-tested / not wired to a real cloud provider) |
| `tests/` | **Active** | Python tests for the retrieval service |
| `notes/` | **Personal scratch** | Two loose text files, not referenced by any other doc — see §11 |

**Beginner note:** "microservices" just means the backend is split into
several small, independently-runnable programs (three FastAPI apps here)
that talk to each other over plain HTTP requests, instead of one big
program doing everything. Each one can be started, stopped, and understood
on its own — see §5.

---

## 4. The Android app, piece by piece

### 4.0 If you've never built an Android app before

A few concepts that come up constantly in this codebase:

- **`Context`** — an Android object that gives code access to
  app-wide/system resources (files, preferences, system services, string
  resources). Almost every Android API you call needs one passed in. Think
  of it as "a handle to the running app."
- **Jetpack Compose** — the UI toolkit this app uses. Instead of drawing
  layouts in XML, you write Kotlin functions annotated `@Composable` that
  describe *what the screen should look like given the current state*.
  When state changes (e.g. the user types a letter), Compose re-runs
  (**"recomposes"**) just the parts of the UI that depend on that state —
  you never manually update a widget on screen yourself.
- **`remember` / `rememberSaveable` / `mutableStateOf`** — how Compose
  tracks state that should survive a recomposition. `remember` alone loses
  its value if the screen is torn down and rebuilt (e.g. rotating the
  phone); `rememberSaveable` survives that. This app leans on this
  distinction a lot — see the extensive comments in `MainActivity.kt`
  around `rememberSaveable` for a real bug this caused and fixed.
- **Coroutines (`suspend fun`, `scope.launch`, `Flow`)** — Kotlin's way of
  doing work that takes time (a network call, a model load) without
  freezing the screen. `launch` starts a background task; `suspend fun` is
  a function that can pause and resume without blocking a thread.
- **`SharedPreferences`** — Android's built-in, simple key-value storage on
  disk (like a tiny settings file). This app uses it for things like the
  server URL and selected language, and `EncryptedSharedPreferences`
  (a hardened variant, backed by the Android Keystore) for the API key,
  since that's a credential rather than a UI preference.
- **A Foreground Service** — a special kind of background process Android
  lets keep running (and keep the microphone) even when the app isn't on
  screen, *as long as* it shows a persistent notification saying so. Since
  Android 9, background code cannot touch the mic at all without one — this
  is why hands-free mode needs `ListeningForegroundService.kt`.

### 4.1 The screen and its state — `MainActivity.kt`

This is the single largest file in the app (~1,500 lines) and is already
very heavily commented — if you read one file end to end to understand the
UI, read this one's comments, not just the code. It owns almost all of the
screen's state (the text field, the current result, which language is
selected, whether hands-free is running, etc.) and wires together every
other file below. The core logic split is:

- `onSend()` — decides whether to translate on-device (fast, offline, for
  English input) or call the backend (for Russian "expand mode", or as a
  fallback), and updates the screen with whatever comes back.
- A cluster of `LaunchedEffect` blocks — Compose's mechanism for running
  side effects (network calls, timers) in response to state changes rather
  than on every recomposition.
- The Lisa Assistant hookup — starts/stops `SpeechAssistant` and
  `ListeningForegroundService` together, and routes its callbacks
  (utterance heard, trigger phrase matched) back into the same `onSend()`
  logic used by typing.

### 4.2 Networking — `ApiClient.kt`, `ServerConfig.kt`

- `ApiClient.kt` — makes the actual `POST /assist` HTTP call (via OkHttp)
  and parses the JSON response.
- `ServerConfig.kt` — persists the backend's base URL and API key so you
  can point the app at a local dev server, your phone's LAN address, or the
  deployed cluster without rebuilding. The URL lives in plain
  `SharedPreferences`; the API key lives in `EncryptedSharedPreferences`
  since it's a credential.

### 4.3 Translation

- `OnDeviceTranslator.kt` — wraps Google ML Kit's on-device translation
  models. Used for every non-Russian target language, and as a fallback for
  English→Russian when the backend has no curated match. Downloads a small
  (~30MB) model per language on first use, then works fully offline.
- The backend path (curated lookup → Claude) is described in §5.2.

### 4.4 Hands-free voice ("Lisa Assistant")

- `SpeechAssistant.kt` — a small state machine (`IDLE` →
  `LISTENING_DEFAULT` → `LISTENING_FOR_WORD` → back) built on Android's
  built-in `SpeechRecognizer`. It's the production Kotlin port of the
  Python prototype in `speech_lab/trigger_flow.py`.
- `TriggerPhraseDetector.kt` — fuzzy string matching (Levenshtein
  edit-distance ratio) so a mis-transcribed trigger phrase ("как сказат"
  instead of "как сказать") still counts as a match. Ported from
  `speech_lab/trigger_phrase.py`'s reasoning, not its exact algorithm
  (Python's `difflib` has no Kotlin equivalent).
- `TriggerPhraseConfig.kt` — stores the (per-language, user-editable)
  trigger phrases for each of the four voice commands.
- `ListeningForegroundService.kt` — the foreground service described in
  §4.0, plus a partial wake lock so the CPU stays available for speech
  recognition even with the screen off.
- `TranslationSpeaker.kt` — wraps Android's built-in `TextToSpeech` to read
  results aloud.

**Why a fixed trigger phrase instead of automatically detecting the
language?** Android's `SpeechRecognizer` can only listen in one fixed
language per session — there's no built-in way to auto-detect "was that
English or Russian?" mid-stream. `speech_lab/lang_id.py`'s statistical
approach (a 107-language classifier) was tried and found unreliable on
short clips in real testing. The trigger-phrase approach sidesteps the
problem entirely: the caregiver explicitly says a fixed phrase to signal
"the next thing I say is in the other language," so no guessing is needed.
See `speech_lab/README.md` for the full comparison.

### 4.5 The on-device LLM feature ("what else?")

- `OnDeviceLlm.kt` — the app-level integration point: checks device
  eligibility (Android 13+, ≥6GB RAM), lazily loads the model, unloads it
  after 5 minutes idle, and exposes `generateWhatElse()`. Also very heavily
  commented — worth reading for real "found via device testing" bugs (e.g.
  thermal throttling, a race between `canGenerate()` and a background
  warm-up).
- `OnDeviceLlmConfig.kt` — persists user-facing settings: whether "what
  else?" uses the curated library, the AI, or both (`WhatElseSource`), and
  whether generation runs eagerly after every utterance or only on demand
  (`PrefetchMode` — eager is faster but measurably heats the device, per
  `android/app/benchmarks/README.md`).
- `PromptComposer.kt` — assembles the actual system/user prompt sent to the
  model, by combining a persona (tone/register), per-language example
  phrases, and a task template. This is a Kotlin port of
  `llm_lab/prompts/compose_prompt.py` — see §6 for why that split exists.
- `ModelDownloadWorker.kt` — uses Android's `WorkManager` (the system for
  background work that survives app restarts/reboots) to download the
  ~2.8GB model file.
- `FewShotExamplesConfig.kt`, `GenderConfig.kt` — user-editable overrides
  for the example phrases and the `{gender}` placeholder in prompts.

### 4.6 The `onDeviceLlm` module — running an LLM without the cloud

This is a **separate Android library module** (its own `build.gradle.kts`,
own source tree) rather than just more files in the app, because it
contains native C++ code, which needs a different build toolchain (the
Android NDK + CMake) than the Kotlin code in the rest of the app.

- **What's a GGUF file?** A quantized model-weights file format used by
  `llama.cpp`. **Quantization** means storing the model's numbers at lower
  precision — trading a little accuracy for a much smaller file and much
  faster inference, which is what makes running an LLM on a phone feasible
  at all.
- **What's JNI?** Kotlin/Java code can't call C++ functions directly. The
  Java Native Interface (JNI) is the bridge: `llama_bridge.cpp` exposes
  specially-named C++ functions that the JVM knows how to call, and that
  bridge code in turn calls into the vendored `llama.cpp` library to
  actually load the GGUF file and run inference.
- `InferenceEngine.kt` — the public Kotlin interface (a state machine:
  `Uninitialized` → `Initializing` → `ModelReady` → `Generating`, etc.).
  Adapted almost verbatim from `llama.cpp`'s own official Android example
  (`examples/llama.android`), just repackaged under this project's package
  name.
- `InferenceEngineImpl.kt` — the real implementation, holding the JNI calls
  and the model's lifecycle.
- `llama_bridge.cpp` — the actual bridge code. One specific piece of logic
  worth knowing about here: Qwen3.5 (the bundled model) is a *reasoning*
  model that, left to its own devices, spends its whole output budget
  inside an internal `<think>...</think>` block and never gets to an
  answer. The bridge works around this by pre-filling an empty
  `<think></think>` onto the model's turn before generation starts — the
  same effect a chat template's `enable_thinking=false` flag has elsewhere
  — verified against a real device.

---

## 5. The Python backend, piece by piece

Three independently runnable FastAPI apps under `services/`, each with its
own `main.py` and port. Beginner note: **FastAPI** is a Python web
framework; a **`pydantic.BaseModel`** subclass (seen throughout) declares
the shape of a request/response body so FastAPI can validate incoming JSON
and generate docs automatically — if a field is missing or the wrong type,
the request is rejected before your function body even runs.

### 5.1 `retrieval_service` (port 8001) — "the memory"

Given a text query, returns the best-matching phrases from
`phrase_library/phrases.json`, ranked. Three layers stacked on top of each
other:

1. **`bm25_index.py` — BM25 (lexical/keyword search).** Finds phrases that
   share actual words with the query. Good at exact/near-exact keyword
   matches, bad at "means the same thing but uses different words."
2. **`embeddings.py` + `hybrid.py` — dense embeddings (semantic search).**
   `embeddings.py` turns text into a fixed-length vector (384 numbers,
   via the open-source `paraphrase-multilingual-MiniLM-L12-v2` model,
   running locally through `fastembed`/ONNX — no cloud call). Two texts
   with similar *meaning* end up with similar vectors even if they share
   no words. `hybrid.py` combines BM25's score and the embedding
   similarity score (min-max normalized first, since they're on different
   scales) into one ranked list — catching both kinds of matches that a
   single method alone would miss.
3. **`reranker.py` — learning-to-rank (LTR).** A final re-sort step: a
   small logistic-regression model, trained on `eval/labeled_eval_set.json`
   (hand-labeled "for this query, these phrases are the right answer"
   examples), that learns to combine the raw scores plus a couple of extra
   signals (does the phrase's routine match the request context? how often
   has it been used before?) into a better final ranking than either score
   alone. Deliberately the simplest version of this idea — the code notes
   a real gradient-boosted model (LightGBM/XGBoost) as the natural upgrade
   once there's more labeled data than the current ~15-phrase toy set.

`main.py` wires these three together behind `POST /search`.

**Why do all this instead of just returning exact string matches?** Because
the caregiver won't always type/say the exact wording in the library —
BM25 catches minor rewordings, embeddings catch different-words-same-idea,
and the reranker learns which of those signals to trust most for a given
context.

### 5.2 `orchestration_service` (port 8002) — "the brain"

The service the Android app actually talks to (`POST /assist`). Owns the
decision logic:

- **English vs. Russian detection** — a simple regex check for Cyrillic
  characters (`_has_cyrillic()`), not a language model. English → translate
  mode, Russian → expand mode.
- **Translate mode: curated vs. LLM.** Calls `retrieval_service` first; if
  the caregiver's input has high word-overlap with a candidate's English
  gloss (a `Jaccard overlap ≥ 0.6` threshold — see `CURATED_MATCH_MIN_OVERLAP`
  in `main.py`), the hand-vetted curated phrase wins. Otherwise it falls
  through to `llm_client.py`.
- **`llm_client.py` — the LLM ask layer.** Calls Claude (Anthropic's API)
  to translate/generate, with the retrieved curated phrases injected as
  few-shot examples so the output matches the library's warm "baby-register"
  tone instead of defaulting to textbook-formal Russian. **Runs in a mock
  mode with zero API calls and zero cost if `ANTHROPIC_API_KEY` isn't set**
  — it just returns the top retrieved phrase (or a placeholder) verbatim.
  This is why the whole stack works out of the box with no account
  sign-up.
- Also still exposes the *original* `POST /ask` endpoint — see §5.3, this
  one is currently unused by the app.

### 5.3 `ingestion_service` (port 8003) — a paused feature's front door

This service, and orchestration's `/ask` endpoint it calls, implement the
project's *original* concept: a "child-perception" pipeline where the app
listens for something the child said and speaks back a single grounded
response, rather than today's caregiver-driven "type or ask a question"
flow. **The app doesn't call this anymore** — it was repurposed to be
caregiver-facing (the `/assist` flow in §5.2) — but the code is untouched,
tested, and still runs standalone. It's kept deliberately (not dead code to
delete) as a foundation to revisit once the caregiver-assist flow is solid.
See README's "Paused features" section for the full reasoning.

### 5.4 `services/common/` — shared plumbing

- `config.py` — all environment-variable-driven configuration in one place
  (service URLs, the API keys, retrieval score weights/thresholds). Already
  well-commented, including the *history* behind some numbers (e.g. why
  `MIN_EMBED_SIMILARITY` was raised from 0.35 to 0.7 after a real
  production bug).
- `events.py` — a `PerceptionEvent` data shape + an `EventBus`
  publish/subscribe interface, built for the paused pipeline above.
  Currently backed only by an in-memory implementation (nothing survives a
  process restart) — but any real message broker (Kafka, Redis Streams)
  could be swapped in later by writing one new class that satisfies the
  same interface, without touching the services that use it. This is the
  named "swap-in seam" the roadmap refers to for future usage-logging work.

---

## 6. The experiment folders — `llm_lab/` and `speech_lab/`

Both follow the same pattern: **"prototype in a scratch folder before
touching production code."** Neither is imported by the Android app or the
Python backend — they're where a feature gets de-risked first.

- **`speech_lab/`** answered: "can we reliably tell English and Russian
  apart from a short spoken clip?" (Answer: not statistically, on short
  clips — see §4.4's trigger-phrase explanation for what was built instead
  based on this finding.) It's a standalone Python script collection with
  its own `requirements.txt` and virtualenv.
- **`llm_lab/`** answers: "is a small on-device model's Russian output good
  enough to build the 'what else?' feature on?" It runs candidate models
  (Qwen3.5, 4B and 2B) against a fixed set of prompts/cases and lets a
  Russian-speaking collaborator judge the output quality before any Kotlin
  gets written. `prompts/compose_prompt.py` here is the *original* Python
  version of the prompt-assembly logic that `PromptComposer.kt` (§4.5)
  later ported to Kotlin — this is why both exist and why they're
  structured almost identically (template + persona + few-shot examples).
  `playground.py` (the file open in your editor) is a lighter-weight
  scratchpad for one-off manual prompt testing outside the full eval
  sweep — see §11 for a note on its current state.

**Why keep prototypes as separate, never-imported folders instead of
branches or throwaway scripts?** So the reasoning and the actual
input/output evidence behind a design decision (e.g. "why a trigger phrase
and not a classifier") stays in the repo and reviewable, rather than living
only in someone's memory or a deleted branch.

---

## 7. Eval and tests — what's actually checked

- **`tests/test_retrieval.py`** — standard Python unit/integration tests
  (via `pytest`) for `retrieval_service`: does the phrase library load, do
  searches return sensible results, does the `/search` HTTP endpoint work.
- **`eval/` (root)** — not pass/fail tests, but *quality measurement*:
  computes NDCG@k, MRR, and precision@k (standard information-retrieval
  metrics — see `eval/run_eval.py`'s docstring) for three retrieval
  strategies (BM25-only, hybrid, LTR-reranked) against
  `eval/labeled_eval_set.json`'s hand-labeled ground truth. Run this after
  changing retrieval logic to see whether it actually helped, numerically.
- **`llm_lab/eval/`** — a *different* eval harness, for a *different*
  purpose: judging on-device LLM output quality (not retrieval ranking).
  See §11 for a naming/import collision this causes.
- **`android/app/src/androidTest/` (`WhatElseBenchmarkTest.kt`)** and
  **`android/app/src/test/` (`PromptComposerTest.kt`)** — on-device
  latency/memory benchmarking, and a JVM unit test checking the Kotlin
  `PromptComposer` port against the Python reference implementation.

---

## 8. Infra — what's real vs. aspirational

- **`infra/Dockerfile` + `docker-compose.yml`** — real, runs the three
  Python services locally with one command.
- **`infra/k8s/*.yaml`** — Kubernetes manifests with the correct shape for
  deploying each service, but not yet run against a real cluster (the
  README suggests trying `k3d`/`minikube` first). `PHONE_DEPLOY.md`
  describes how the actual deployed backend is set up today, which may
  differ from these manifests until they're verified.
- **`infra/terraform/main.tf`** — documents the intended cloud resources;
  no cloud provider is actually wired up yet.

Treat these as a documented plan, not as a currently-automated deployment
pipeline.

---

## 9. End-to-end walkthroughs

**Typing an English word (e.g. "sleepy"), Russian target language:**
1. `MainActivity.onSend()` sees Latin script → tries `OnDeviceTranslator`
   first, unconditionally (fast, offline, but plain/textbook tone — see
   §11 for why this "on-device first" behavior is easy to miss from the
   docs alone). If it succeeds, that's the result shown and spoken —
   the backend is never called for this request.
2. Only if on-device translation *fails* (e.g. the Russian model hasn't
   finished downloading yet) does `ApiClient` call `POST /assist` on
   `orchestration_service` as a fallback.
3. `orchestration_service` detects no Cyrillic → translate mode. Calls
   `retrieval_service`'s `/search` for candidates from the phrase library.
4. If "sleepy" strongly overlaps a curated phrase's English gloss, that
   phrase wins outright. Otherwise, `llm_client.translate()` is called
   (mock mode by default → a placeholder string; live mode → a real Claude
   translation, style-anchored by the retrieved curated phrases).
5. Either way, the result flows back to the app, gets spoken aloud by
   `TranslationSpeaker`, and shown on screen.

**Saying a Russian phrase while Lisa Assistant is listening:**
1. `SpeechAssistant` is listening in Russian (`LISTENING_DEFAULT`).
   Android's `SpeechRecognizer` transcribes the utterance.
2. `TriggerPhraseDetector` checks it against the four configured trigger
   phrases — no match → it's an ordinary utterance, handed to
   `MainActivity`'s `onUtterance` callback.
3. Same `/assist` call as above, but this time Cyrillic is detected →
   expand mode. `retrieval_service` returns related phrases from the
   library; nothing is auto-spoken (expand mode doesn't speak until you ask
   "what else?").

**Asking "what else?" (spoken trigger or on-screen button):**
1. If the target language is Russian and library suggestions already exist
   from the last lookup, those can be read straight away.
2. Otherwise (or for any other target language), `OnDeviceLlm.generateWhatElse()`
   runs: `PromptComposer` builds a prompt from the persona + per-language
   examples + the last heard utterance, `InferenceEngineImpl` sends it
   across the JNI bridge to `llama.cpp`, and the generated suggestions come
   back to be shown and spoken.

---

## 10. Glossary

| Term | Plain-language meaning |
|---|---|
| Jetpack Compose | Android's UI toolkit — describe *what the screen looks like* for a given state, and the system figures out what to redraw |
| Recomposition | Compose re-running a UI function because the state it reads changed |
| `Context` | An Android object that gives your code access to app/system resources |
| Foreground Service | A background process Android lets keep running (and, notably, use the mic) while showing a persistent notification |
| Coroutine / `suspend fun` | Kotlin's way of writing async code (waits for a slow thing) without blocking the screen |
| `SharedPreferences` | Android's simple built-in on-device key-value settings storage |
| JNI | The bridge that lets Kotlin/Java code call into compiled C/C++ code |
| GGUF | A file format for quantized (compressed) LLM weights, used by `llama.cpp` |
| Quantization | Storing a model's numbers at lower precision to make it smaller and faster, at a small accuracy cost |
| BM25 | A classic keyword/lexical search-ranking algorithm |
| Embedding | A list of numbers representing a piece of text's *meaning*, so similar-meaning texts end up numerically close |
| Hybrid retrieval | Combining keyword search and embedding search so each covers the other's blind spots |
| Reranker / LTR | A second-pass model that re-sorts initial search results using extra signals, trained on labeled examples |
| RAG (retrieval-augmented generation) | Feeding an LLM real retrieved data as context/examples, instead of relying purely on what it memorized |
| Mock mode | A code path that skips a real (paid/networked) API call and returns a canned/placeholder result instead |
| Microservice | One small, independently runnable program that's part of a larger backend, talking to the others over the network |

---

## 11. Code health review — what's cruft, what's intentional

This section is the answer to "does this repo have anything that
shouldn't be here." Most of the codebase is deliberate and already
well-explained in its own comments/READMEs — genuinely unnecessary code
was hard to find. What follows is everything worth a second look, sorted
by how confident the recommendation is.

### Already cleaned up
- **A stray, untracked directory literally named `{phrase_library,services...}`**
  sat at the repo root — the artifact of a shell brace-expansion command
  (something like `mkdir -p {phrase_library,services/...}`) that didn't
  expand as intended and created a nested tree of empty directories named
  after the literal, un-expanded text. It was never committed to git (pure
  local clutter) and has been deleted as part of writing this document.

### Worth cleaning up (low risk, your call)
- **`.pytest_cache/` is tracked in git** (`CACHEDIR.TAG`, `README.md`,
  `v/cache/lastfailed`, `v/cache/nodeids` — 4 files), despite `.gitignore`
  listing `.pytest_cache/`. It was committed in the very first "Initial
  scaffold" commit, before (or without) the ignore rule catching it.
  These files regenerate automatically every time `pytest` runs and serve
  no purpose being committed. Fix: `git rm -r --cached .pytest_cache` and
  commit.
- **`notes/step-by-step-explanation.txt` is a tracked, completely empty
  file (0 bytes).** Its companion, `notes/important.txt`, holds a real
  note (explaining `android:usesCleartextTraffic="true"`) — but that same
  explanation already lives in README's "Known issues" section. Neither
  file is linked from README, ROADMAP, or CONTRIBUTING. Worth either
  deleting both (the content's covered elsewhere) or keeping `important.txt`
  if it's useful as a personal quick-reference — but the empty file has no
  reason to exist.
- **`android/design/fox-logo-v1.png` (1.1MB) appears unreferenced.**
  `fox-logo.png` and `wireframe.png` in the same folder are both linked
  from `UI_REDESIGN_PLAN.md`; `fox-logo-v1.png` isn't referenced from any
  `.md`/`.kt`/`.xml` file in the repo. Looks like an earlier draft
  superseded by `fox-logo.png` (and the app's actual launcher asset,
  `res/drawable-nodpi/lisa_fox.png`). If it's not needed as a historical
  reference, it's 1.1MB of dead weight.
- **`llm_lab/playground.py` (the file you had open) is a working
  scratchpad by design** — its own docstring says "edit SYSTEM/USER below
  and re-run." But it currently contains roughly seven stacked, superseded
  `SYSTEM =` / `USER =` reassignments from past manual experiments, where
  only the *last* one before `def main()` actually executes — the rest are
  inert history. Since git already preserves every prior version, trimming
  the file down to just the currently-active block (or a couple of
  clearly-labeled saved examples) would make it much faster to read for
  someone new, without losing anything — the history is one `git log -p`
  away.
- **Two directories are both named `eval/`**: the root `eval/` (retrieval
  quality metrics) and `llm_lab/eval/` (on-device LLM prompt evaluation) —
  different purposes, same name, both real Python packages/namespaces on
  the same `sys.path` when run from the repo root. This isn't hypothetical
  — `llm_lab/playground.py` already has to work around it, loading
  `llm_lab/eval/run_eval.py` by explicit file path (`importlib.util`)
  specifically because a plain `from eval.run_eval import ...` would
  silently resolve to the *other* `eval/`'s module instead, per that
  file's own comment. Consider renaming one (e.g. `llm_lab/eval` →
  `llm_lab/model_eval`) to remove the footgun for the next script that
  needs to import from either one and doesn't know about this trap.

### Worth a second look (stale docs, not stale code)
- **README's "Supported languages & translation" section and
  `OnDeviceTranslator.kt`'s own doc comment both describe on-device
  translation as "the fallback" — used only when the backend has no
  curated match, or for non-Russian languages.** That was true when those
  comments were written, but a later commit
  (`3eb5c39`, *"android: translate English input on-device first, skip the
  server round-trip (only Cyrillic expand mode still needs it)"*) flipped
  the priority for **every** target language, Russian included:
  `MainActivity.onSend()` now tries `OnDeviceTranslator` first,
  unconditionally, for any Latin-script input, and only calls the backend
  (curated library → Claude) if that throws (e.g. the model hasn't
  downloaded yet). In practice, once a language's on-device model is
  cached, the curated "baby-register" library and the LLM fallback are
  reached far less often for Russian than the README table currently
  implies. This isn't a bug — it was a deliberate, working change — but the
  README table and `OnDeviceTranslator.kt`'s docstring are now describing
  the *old* priority order. Worth a doc pass (or confirming with whoever
  made that commit whether the README should instead be updated to match
  today's intended behavior) so a new reader isn't misled the way this
  review initially was.

### Not cruft — kept deliberately (don't delete)
- **`services/ingestion_service/`, `orchestration_service`'s `POST /ask`
  endpoint, and `services/common/events.py`'s `EventBus`** implement the
  original "child-perception" pipeline. The app no longer calls any of
  this, but it's tested, working, and explicitly documented in README's
  "Paused features" section as a foundation to revisit later (natural
  pairing with a future camera/vision feature). This is intentionally
  preserved scope, not abandoned code.
- **`infra/k8s/` and `infra/terraform/main.tf`** are labeled scaffolding
  in README itself — correct shape, not yet proven against a real cluster
  or cloud account. That's a documented, expected state, not a defect.
- **The heavy inline comments throughout `MainActivity.kt`,
  `OnDeviceLlm.kt`, `SpeechAssistant.kt`, etc.** — these look dense at
  first glance, but nearly all of them explain *why* a specific line exists
  (often "found via a real device bug"), not *what* the code does. That's
  valuable institutional memory, not noise — this document deliberately
  didn't try to compress or duplicate it.

### A known, already-tracked security note
README's "Known issues" section already documents that the deployed
backend's API key travels over plain HTTP (`usesCleartextTraffic="true"`)
because there's no TLS certificate set up yet. Not re-litigated here beyond
flagging that it's the reason both `notes/` files above talk about that
same manifest flag — see README for the actual fix plan (needs a domain +
DNS before a cert can be issued).

---

## 12. Where to go next

- [README.md](README.md) — authoritative status tables, quickstart, and
  the "Suggested expansion order" for what to build next.
- [ROADMAP.md](ROADMAP.md) — the bigger, multi-phase "Lisa Assistant"
  hands-free vision and what's still genuinely undesigned about it
  (real per-utterance language ID, in particular).
- [android/README.md](android/README.md) — Android-specific run
  instructions and known limitations.
- [android/ON_DEVICE_LLM_PLAN.md](android/ON_DEVICE_LLM_PLAN.md) — the
  phased build log for the on-device LLM feature described in §4.5–4.6.
- [llm_lab/README.md](llm_lab/README.md) / [speech_lab/README.md](speech_lab/README.md)
  — deeper detail on the two experiment folders in §6.
