"""
Shared silence-gating helper for run_on_file.py / record_and_identify.py.

Added after real mic testing showed the pipeline confidently "detecting" a
language and transcribing text on clips where nothing was said. Two things
were combining to cause that:

1. Whisper doesn't reliably recognize "no speech here" -- fed silence or
   room noise, it hallucinates plausible-looking filler text instead of
   returning nothing (a widely reported behavior of this model family, not
   specific to this code).
2. The scripts were normalizing every clip to full peak amplitude *before*
   checking whether it was silence -- so quiet room noise got stretched up
   to look "full volume" to the models by the time they saw it, and
   identify_language() always commits to English or Russian regardless of
   how close its scores are to random chance (for a 107-language model,
   chance is ~1/107 ≈ 0.009 per language -- the bogus detections measured
   right around there).

The fix: measure loudness on the RAW clip, before normalization, and skip
identification/transcription entirely below a threshold. This is a blunt
instrument compared to real voice-activity detection (a loud non-speech
sound like a door slam still passes it; a very soft whisper might not) --
Silero VAD remains the more robust fix tracked in the project roadmap.
This is a zero-dependency stopgap to stop obviously-silent clips from
producing fake-looking output while testing.
"""
from __future__ import annotations

import numpy as np

# -40 dBFS is a common rule-of-thumb cutoff between "quiet room" and "someone
# actually talking" on a typical laptop/phone mic, but mic sensitivity and
# room noise vary a lot -- treat this as a starting point to calibrate
# against your own setup (both scripts print the measured level every time,
# silence or not, so you can see where your real speech and your room's
# silence actually land and adjust with --silence-threshold-db).
DEFAULT_SILENCE_THRESHOLD_DB = -40.0


def measure_dbfs(audio: np.ndarray) -> float:
    """RMS loudness in dBFS (0 dB = a full-scale sine wave). Silence/room
    noise is very negative (-50, -60...); normal speech is usually well
    above -40."""
    rms = np.sqrt(np.mean(audio.astype(np.float64) ** 2))
    return 20 * np.log10(rms + 1e-9)


def is_silent(audio: np.ndarray, threshold_db: float = DEFAULT_SILENCE_THRESHOLD_DB) -> bool:
    return measure_dbfs(audio) < threshold_db
