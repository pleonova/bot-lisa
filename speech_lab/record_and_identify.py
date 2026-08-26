"""
Records short clips from your laptop's microphone and runs them through
the same lang_id + transcribe pipeline as run_on_file.py -- the quickest
way to test real speech (including single words) against SpeechBrain's
VoxLingua107 classifier + Whisper before touching the Android app at all.

Usage:
    python record_and_identify.py                # 3-second clips, repeats until Ctrl+C
    python record_and_identify.py --seconds 1.5   # shorter clips, closer to a single word

Each recording gets printed immediately and appended to usage_log.jsonl
(same file run_on_file.py writes to) -- that log is the seed for the
labeled eval set the project roadmap calls for: run this on the actual
single words you plan to say to your kid, then hand-correct any wrong
"detected_language"/"text" fields to turn it into ground truth.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import sounddevice as sd

from lang_id import SAMPLING_RATE, identify_language
from transcribe import transcribe

LOG_PATH = Path(__file__).parent / "usage_log.jsonl"


def record_clip(seconds: float) -> np.ndarray:
    print(f"\nSpeak now ({seconds}s)...")
    audio = sd.rec(int(seconds * SAMPLING_RATE), samplerate=SAMPLING_RATE, channels=1, dtype="float32")
    sd.wait()
    audio = audio.flatten()

    peak = np.max(np.abs(audio))
    if peak < 1e-4:
        print("  (heard silence -- check your mic input device/level)")
    elif peak > 0:
        audio = audio / peak  # normalize, matches lang_id/transcribe's training-time convention

    return audio


def main(seconds: float) -> None:
    print("Press Ctrl+C to stop.")
    with LOG_PATH.open("a") as log_file:
        while True:
            try:
                audio = record_clip(seconds)

                t0 = time.time()
                lid_result = identify_language(audio)
                lid_time = time.time() - t0

                t0 = time.time()
                text = transcribe(audio, language=lid_result["language"])
                transcribe_time = time.time() - t0

                print(f"  detected:   {lid_result['language']}  "
                      f"(en={lid_result['en_prob']:.3f}, ru={lid_result['ru_prob']:.3f})")
                print(f"  transcript: {text!r}")

                record = {
                    "source": "mic",
                    "duration_s": seconds,
                    "detected_language": lid_result["language"],
                    "confidence": round(lid_result["confidence"], 3),
                    "en_prob": round(lid_result["en_prob"], 3),
                    "ru_prob": round(lid_result["ru_prob"], 3),
                    "text": text,
                    "lang_id_time_s": round(lid_time, 3),
                    "transcribe_time_s": round(transcribe_time, 3),
                }
                log_file.write(json.dumps(record, ensure_ascii=False) + "\n")
                log_file.flush()
            except KeyboardInterrupt:
                print(f"\nStopped. Recordings logged to {LOG_PATH}")
                break


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=float, default=3.0, help="Clip length in seconds (default: 3.0)")
    args = parser.parse_args()
    main(args.seconds)
