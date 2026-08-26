"""
Prototype of the trigger-phrase flow (see trigger_phrase.py for the full
reasoning behind replacing per-utterance language classification with
this). Two states:

  1. DEFAULT -- record and transcribe in the default/selected language
     (Russian today). If the transcript matches the trigger phrase ("как
     сказать"), don't treat it as a normal utterance -- switch to state 2
     and wait for the next clip instead.
  2. WAITING FOR WORD -- record and transcribe the *next* clip in English
     instead. That's the word/phrase the caregiver wants translated.
     Report it, then go back to state 1.

This deliberately does NOT call lang_id.py at all -- no classifier is
needed anywhere in this flow, which is the whole point. It also doesn't
yet call the real translate/related-phrases pipeline (that's `/assist` on
orchestration-service, a separate integration) -- this script's job is
only to validate that trigger detection and the language hand-off work on
real speech, end to end.

Reuses the same silence gate as record_and_identify.py: a silent/near-
silent clip is skipped and doesn't change state either way -- so pausing
between the trigger phrase and the target word (which you need to do
anyway, so each is its own clip) doesn't accidentally reset anything.

Usage:
    python trigger_flow.py                              # Russian default, 2.5s clips
    python trigger_flow.py --seconds 2.0 --default-language ru
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import sounddevice as sd

from audio_utils import DEFAULT_SILENCE_THRESHOLD_DB, measure_dbfs
from transcribe import transcribe
from trigger_phrase import TRIGGER_PHRASES, matches_trigger_phrase

LOG_PATH = Path(__file__).parent / "trigger_flow_log.jsonl"

# Deliberately NOT importing this from lang_id.py -- that module loads the
# SpeechBrain classifier at import time, and this flow doesn't use it at
# all (that's the whole point; see the module docstring). Duplicated here
# and in lang_id.py/transcribe.py rather than introducing a shared import
# that would drag the classifier back in as a side effect.
SAMPLING_RATE = 16000

# The language a "how do you say ___" word is assumed to be in once the
# trigger phrase fires. Always English today, since that's the only other
# language this app knows about -- would need to become a real choice
# once a third language is in play (which of the *other* languages did
# they mean?). Not needed yet.
TRANSLATE_TARGET_LANGUAGE = "en"


def record_clip(seconds: float) -> tuple[np.ndarray, float]:
    audio = sd.rec(int(seconds * SAMPLING_RATE), samplerate=SAMPLING_RATE, channels=1, dtype="float32")
    sd.wait()
    audio = audio.flatten()

    dbfs = measure_dbfs(audio)
    peak = np.max(np.abs(audio))
    if peak > 0:
        audio = audio / peak

    return audio, dbfs


def main(seconds: float, default_language: str, silence_threshold_db: float) -> None:
    trigger_phrase = TRIGGER_PHRASES.get(default_language)
    if trigger_phrase is None:
        raise SystemExit(
            f"No trigger phrase configured for language {default_language!r} "
            f"(known: {list(TRIGGER_PHRASES)}) -- add one to trigger_phrase.py's "
            f"TRIGGER_PHRASES first."
        )

    print(f"Default language: {default_language}  |  Trigger phrase: {trigger_phrase!r}")
    print("Say things normally in the default language. Say the trigger phrase, pause, "
          "then say an English word/phrase to translate it.")
    print("Press Ctrl+C to stop.\n")

    waiting_for_word = False

    with LOG_PATH.open("a") as log_file:
        while True:
            try:
                state = "waiting_for_word" if waiting_for_word else "default"
                print(f"[{state}] Speak now ({seconds}s)...")
                audio, dbfs = record_clip(seconds)
                print(f"  level: {dbfs:.1f} dBFS")

                if dbfs < silence_threshold_db:
                    print("  (silence -- skipping, state unchanged)\n")
                    continue

                if waiting_for_word:
                    t0 = time.time()
                    text = transcribe(audio, language=TRANSLATE_TARGET_LANGUAGE)
                    transcribe_time = time.time() - t0

                    print(f"  word to translate ({TRANSLATE_TARGET_LANGUAGE}): {text!r}")
                    print("  -> [translate pipeline would run here -- not wired up in this prototype]\n")

                    record = {
                        "state": "waiting_for_word",
                        "language": TRANSLATE_TARGET_LANGUAGE,
                        "dbfs": round(dbfs, 1),
                        "text": text,
                        "transcribe_time_s": round(transcribe_time, 3),
                    }
                    waiting_for_word = False
                else:
                    t0 = time.time()
                    text = transcribe(audio, language=default_language)
                    transcribe_time = time.time() - t0

                    triggered = matches_trigger_phrase(text, default_language)
                    print(f"  transcript ({default_language}): {text!r}")

                    if triggered:
                        print("  -> trigger phrase detected. Say the word to translate next.\n")
                        waiting_for_word = True
                    else:
                        print("  -> [related-phrases pipeline would run here -- not wired up in this prototype]\n")

                    record = {
                        "state": "default",
                        "language": default_language,
                        "dbfs": round(dbfs, 1),
                        "text": text,
                        "triggered": triggered,
                        "transcribe_time_s": round(transcribe_time, 3),
                    }

                log_file.write(json.dumps(record, ensure_ascii=False) + "\n")
                log_file.flush()
            except KeyboardInterrupt:
                print(f"\nStopped. Logged to {LOG_PATH}")
                break


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float, default=2.5, help="Clip length in seconds (default: 2.5)")
    parser.add_argument("--default-language", default="ru", help="Default/target language code (default: ru)")
    parser.add_argument(
        "--silence-threshold-db", type=float, default=DEFAULT_SILENCE_THRESHOLD_DB,
        help=f"Clips quieter than this (dBFS) are skipped (default: {DEFAULT_SILENCE_THRESHOLD_DB})",
    )
    args = parser.parse_args()
    main(args.seconds, args.default_language, args.silence_threshold_db)
