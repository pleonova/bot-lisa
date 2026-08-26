# speech_lab — English/Russian language-ID + transcription prototype

A standalone, no-Android, no-backend-service prototype exploring how to
tell whether a caregiver said something in English or Russian, for
bot-lisa's hands-free "Lisa Assistant" mode. Deliberately separate from
`services/` (the deployed microservices) and `android/` (the app) -- it
exists to answer that question before either of those gets touched.

**Status: two approaches tried, the second is the one to build on.**

1. **Statistical language ID** (`lang_id.py`, `run_on_file.py`,
   `record_and_identify.py`) — replicates
   https://huggingface.co/spaces/tan-z-tan/speech_language_detection's
   "record → identify language → transcribe" flow (originally Japanese/
   English) using SpeechBrain's VoxLingua107 classifier. **Real mic testing
   showed this doesn't work well enough**: on short clips, English/Russian
   confidence scores both came back near-zero (essentially chance level for
   a 107-way classifier) more often than not, so the "winner" between the
   two was frequently arbitrary. Kept in the repo as a reference/comparison
   point, not as the path forward. See `bot-lisa-roadmap.md`'s "Research:
   language-ID options for Phase 3" section (in the attached Claude
   project) for the fuller background.
2. **Trigger-phrase detection** (`trigger_phrase.py`, `trigger_flow.py`) —
   **the current recommended approach.** Rather than statistically guess
   the language of an ambiguous short clip, the caregiver explicitly
   signals intent: say the trigger phrase ("как сказать" / "how do you
   say"), pause, then say the word to translate. No classifier needed —
   just a fixed phrase match — which sidesteps the accuracy problem above
   entirely instead of trying to fix it.

## How the recommended approach works

1. **`trigger_phrase.py`** — `matches_trigger_phrase(transcript, language)`
   fuzzy-matches a transcript against the configured trigger phrase for
   that language (`TRIGGER_PHRASES = {"ru": "как сказать"}` today; add a
   Hindi entry etc. here later, not needed yet). Uses `difflib` (stdlib, no
   new dependency) so minor ASR mistakes ("как сказат", a trailing word
   stuck on) still match. Verified against both clean and slightly-off
   transcriptions, and against real non-trigger transcripts from earlier
   testing ("Спать", "Хорошо", "Я хочу пойти") to confirm those correctly
   *don't* trigger.
2. **`transcribe.py`** — unchanged from before: `transcribe(audio,
   language)` transcribes using whatever language the caller specifies.
3. **`trigger_flow.py`** — a small state machine over the mic:
   - **Default state**: record + transcribe in the default language
     (Russian). If the transcript matches the trigger phrase, don't treat
     it as a normal utterance — switch to "waiting for word" instead of
     going to the related-phrases pipeline.
   - **Waiting-for-word state**: record + transcribe the *next* clip in
     English. That's the word to translate. Report it, then go back to
     default state.

   This calls `transcribe()` directly with whichever language the state
   machine already knows is correct — `lang_id.py`'s classifier is never
   invoked anywhere in this flow. No accuracy problem to work around,
   because there's no statistical guess being made.

## Reference: the statistical approach (kept for comparison)

1. **`lang_id.py`** — loads SpeechBrain's `speechbrain/lang-id-voxlingua107-ecapa`
   model (Apache 2.0) once, then `identify_language(audio)` scores the clip
   against all 107 languages the model knows and returns whichever of
   English/Russian scored higher, plus both probabilities.
2. **`run_on_file.py`** — feed it one or more `.wav` files, get printed
   results, and each result gets appended to `usage_log.jsonl`.
3. **`record_and_identify.py`** — same pipeline, but records straight from
   your laptop's mic in a loop (Ctrl+C to stop) instead of reading files.

## Setup

```bash
cd speech_lab
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

First run of any script downloads the SpeechBrain model (`lang_id.py` path
only) and/or Whisper-base (~140MB, all paths) from Hugging Face and caches
them locally -- needs internet once, works offline after that.

## Try it

```bash
# Recommended: the trigger-phrase flow
python trigger_flow.py
# Say a normal Russian phrase (goes to "default" state, un-triggered).
# Say "как сказать", pause, then say an English word -- watch it switch
# states and transcribe the second clip in English.

# Reference: the statistical classifier, for comparison
python record_and_identify.py --seconds 1.5
python run_on_file.py clip1.wav clip2.wav
```

For the trigger-phrase flow, try both a plain Russian phrase (should stay
in "default" state) and the full "как сказать" → pause → English word
sequence, a few times, to get a feel for how forgiving the fuzzy phrase
match is and where it should be tightened or loosened
(`matches_trigger_phrase`'s `threshold` parameter).

## What's logged (the "store this info" starter)

- `trigger_flow.py` appends to `trigger_flow_log.jsonl`: state, language,
  transcript, whether the trigger fired, timing.
- `run_on_file.py`/`record_and_identify.py` (the reference approach) append
  to `usage_log.jsonl`: duration, detected language, both probabilities,
  transcript, timing.

Either is a seed for two things down the line:

- A **labeled eval set**: run this against a batch of real recordings,
  hand-correct any wrong fields, and it becomes ground truth to benchmark
  against — the same role `eval/labeled_eval_set.json` already plays for
  retrieval quality.
- The **usage-logging piece of Phase 3 / Phase 4** in the roadmap (real
  persistent storage, a dashboard, the "did they actually use the
  suggested phrase" feedback loop) — these JSONL files are a stand-in for
  that, not the real thing; wiring it into SQLite/Postgres and a dashboard
  is separate, larger work already tracked there.

## What this deliberately doesn't do yet

- `trigger_flow.py` doesn't call the real translate/related-phrases
  pipeline (`/assist` on `orchestration-service`) — it prints what it
  would do instead. Wiring that in is the natural next step once the
  trigger-detection + language hand-off itself is validated on real
  speech.
- No automatic pause detection — you're recording fixed-length clips
  (`--seconds`), so you have to actually stop talking and let the clip
  end before the next one starts, rather than the system detecting your
  pause for you. Silero VAD (see the roadmap research note) is the
  natural upgrade once this flow is validated.
- The trigger phrase and the target word must be two separate clips (see
  the module docstring in `trigger_phrase.py` for why): saying both in one
  breath as a single clip will mangle whichever half doesn't match that
  clip's transcription language, the same failure mode as the original bug
  report this whole investigation started from.
- Not wired into the Android app or any backend service — this is a local
  script for answering the accuracy question first. Turning it into a
  FastAPI endpoint the app calls (mirroring how `retrieval_service`/
  `orchestration_service` already work) is the natural next step once
  the results here look good enough to build on.

## A note on where this was built

This was drafted and syntax/dependency-checked in a sandboxed environment
that could install `torch`/`speechbrain`/`transformers` from PyPI fine,
but couldn't reach `huggingface.co` to actually download the model
weights (network policy in that environment, not a code issue) — so the
package versions, method signatures, and the `transcribe.py` call
(`generate(language=..., task="transcribe")` instead of the older
`forced_decoder_ids=` pattern, which is deprecated as of `transformers`
5.15.1) were all verified against the real installed libraries, but a full
live audio run hasn't happened yet. Your machine has normal internet
access, so the first real end-to-end run — models downloading, actual
speech going in — will happen when you run it. If anything doesn't match
what's described here, that's the first thing to check.
