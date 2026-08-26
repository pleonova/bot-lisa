# speech_lab — English/Russian language-ID + transcription prototype

A standalone, no-Android, no-backend-service prototype replicating
https://huggingface.co/spaces/tan-z-tan/speech_language_detection's
"record → identify language → transcribe" flow, adapted from Japanese/
English to **English/Russian** for bot-lisa. This is deliberately separate
from `services/` (the deployed microservices) and `android/` (the app) --
it exists to answer one question before either of those gets touched:
*does SpeechBrain's VoxLingua107 classifier + Whisper actually work well
enough on the short, often single-word things a caregiver says to their
kid?* See `bot-lisa-roadmap.md`'s "Research: language-ID options for Phase
3" section (in the attached Claude project) for the fuller writeup this
came out of -- short version: every published accuracy number for these
models was measured on multi-second clips, not single words, so this is
the harness to find out for real.

## How it works

1. **`lang_id.py`** — loads SpeechBrain's `speechbrain/lang-id-voxlingua107-ecapa`
   model (Apache 2.0) once, then `identify_language(audio)` scores the clip
   against all 107 languages the model knows and returns whichever of
   English/Russian scored higher, plus both probabilities and a top-3 list
   across all 107 for sanity-checking (e.g. "clearly neither" cases).
2. **`transcribe.py`** — loads `openai/whisper-base` via Hugging Face
   `transformers`, then `transcribe(audio, language)` transcribes using
   *whatever language `lang_id.py` already decided* — Whisper is never
   asked to guess the language itself, since its own built-in detection is
   documented to need several seconds of audio to be reliable (the exact
   thing we don't have with single words).
3. **`run_on_file.py`** — feed it one or more `.wav` files, get printed
   results, and each result gets appended to `usage_log.jsonl`.
4. **`record_and_identify.py`** — same pipeline, but records straight from
   your laptop's mic in a loop (Ctrl+C to stop) instead of reading files.
   This is the fastest way to test real single words.

## Setup

```bash
cd speech_lab
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

First run of either script downloads the SpeechBrain model (~small) and
Whisper-base (~140MB) from Hugging Face and caches them locally --
needs internet once, works offline after that.

## Try it

```bash
# From your mic, 3-second clips by default:
python record_and_identify.py

# Closer to a real single-word test:
python record_and_identify.py --seconds 1.5

# Or against pre-recorded files:
python run_on_file.py clip1.wav clip2.wav
```

Say a plain English word ("food"), then a Russian word or phrase, a few
times each, at the `--seconds` length you'd actually use. Watch whether
`detected_language`/`en_prob`/`ru_prob` get it right, and how close the
two probabilities are on the ones it gets wrong -- that closeness is what
a future "low-confidence, ask again" threshold would key off of (not
implemented yet -- `identify_language()` always commits to one of the two
today, see its docstring).

## What's logged (the "store this info" starter)

Every clip processed -- via either script -- gets one line appended to
`usage_log.jsonl`: duration, detected language, both probabilities, the
transcript, and timing. That's the seed for two things down the line:

- A **labeled eval set**: run this against a batch of real recordings,
  hand-correct any wrong `detected_language`/`text` fields, and it becomes
  ground truth to benchmark this approach (or compare it against
  alternatives) — the same role `eval/labeled_eval_set.json` already plays
  for retrieval quality.
- The **usage-logging piece of Phase 3 / Phase 4** in the roadmap (real
  persistent storage, a dashboard, the "did they actually use the
  suggested phrase" feedback loop) — this JSONL file is a stand-in for
  that, not the real thing; wiring it into SQLite/Postgres and a dashboard
  is separate, larger work already tracked there.

## What this deliberately doesn't do yet

- No voice-activity detection / utterance segmentation — you decide the
  clip length yourself (`--seconds`), there's no automatic "wait for a
  pause" cutting. Silero VAD (see the roadmap research note) is the
  natural next piece once this is validated.
- No confidence threshold / "neither language" path — `identify_language()`
  always picks English or Russian, even when both probabilities are low
  and close together. Worth adding once real testing shows how often that
  actually happens.
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
