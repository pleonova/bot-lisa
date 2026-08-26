"""
Minimal CLI test harness: run lang_id + transcribe on a .wav file and
print the result, and append it to a local JSONL log.

Usage:
    python run_on_file.py path/to/clip.wav [path/to/another.wav ...]

This is deliberately the smallest possible "does the pipeline work"
harness -- no mic recording, no streaming, no chunking -- so it's easy to
sanity-check lang_id.py/transcribe.py against real recordings before
wiring either into anything bigger (the Android app, a FastAPI endpoint,
continuous listening). Once this looks right on real speech, the next
step is a mic-recording version (record_and_identify.py) for live testing
on a laptop, and eventually a FastAPI endpoint the Android app calls.

The JSONL log (usage_log.jsonl) is the "I want to store this info" seed:
one line per clip processed, with what was detected. This is exactly the
shape needed for the labeled eval set the project roadmap calls for
(bot-lisa-roadmap.md's "Research: language-ID options for Phase 3" note) --
run this over a folder of real recordings, then hand-correct the "language"
and "text" fields to turn the log into ground truth.
"""
from __future__ import annotations

import json
import sys
import time
import wave
from pathlib import Path

import numpy as np

from lang_id import SAMPLING_RATE, identify_language
from transcribe import transcribe

LOG_PATH = Path(__file__).parent / "usage_log.jsonl"


def load_wav_as_float32(path: str) -> np.ndarray:
    """Reads a mono or stereo PCM wav file and returns float32 samples in
    [-1, 1] at whatever sample rate the file has. Resampling to 16kHz (if
    needed) is intentionally NOT done here -- keep this harness dependency-
    light (stdlib `wave` only); real integration should resample properly
    (see the reference example's torchaudio-based `resample_audio`), and
    the espeak-ng test clips this was validated against are already 16kHz
    mono so no resampling was needed for that smoke test.
    """
    with wave.open(path, "rb") as wf:
        sr = wf.getframerate()
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        raw = wf.readframes(wf.getnframes())

    if sampwidth != 2:
        raise ValueError(f"{path}: expected 16-bit PCM wav, got sampwidth={sampwidth}")

    audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if n_channels > 1:
        audio = audio.reshape(-1, n_channels).mean(axis=1)

    if sr != SAMPLING_RATE:
        print(f"  WARNING: {path} is {sr}Hz, not {SAMPLING_RATE}Hz -- results may be degraded "
              f"(this harness doesn't resample; see docstring).")

    # Match the reference example: normalize peak amplitude to 1.0.
    peak = np.max(np.abs(audio))
    if peak > 0:
        audio = audio / peak

    return audio


def process_file(path: str) -> dict:
    audio = load_wav_as_float32(path)
    duration_s = len(audio) / SAMPLING_RATE

    t0 = time.time()
    lid_result = identify_language(audio)
    lid_time = time.time() - t0

    t0 = time.time()
    text = transcribe(audio, language=lid_result["language"])
    transcribe_time = time.time() - t0

    record = {
        "file": path,
        "duration_s": round(duration_s, 2),
        "detected_language": lid_result["language"],
        "confidence": round(lid_result["confidence"], 3),
        "en_prob": round(lid_result["en_prob"], 3),
        "ru_prob": round(lid_result["ru_prob"], 3),
        "top3_all_languages": [(name, round(p, 3)) for name, p in lid_result["top3_all_languages"]],
        "text": text,
        "lang_id_time_s": round(lid_time, 3),
        "transcribe_time_s": round(transcribe_time, 3),
    }
    return record


def main(paths: list[str]) -> None:
    with LOG_PATH.open("a") as log_file:
        for path in paths:
            print(f"\n--- {path} ---")
            record = process_file(path)
            print(f"  duration:     {record['duration_s']}s")
            print(f"  detected:     {record['detected_language']}  "
                  f"(en={record['en_prob']}, ru={record['ru_prob']})")
            print(f"  top3 (all 107 langs): {record['top3_all_languages']}")
            print(f"  transcript:   {record['text']!r}")
            print(f"  timing:       lang_id={record['lang_id_time_s']}s, "
                  f"transcribe={record['transcribe_time_s']}s")

            log_file.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(f"\nAppended {len(paths)} record(s) to {LOG_PATH}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(f"Usage: python {sys.argv[0]} clip1.wav [clip2.wav ...]")
        sys.exit(1)
    main(sys.argv[1:])
