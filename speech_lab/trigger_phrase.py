"""
Deterministic "trigger phrase" detection, as an alternative to statistical
language identification for deciding when the caregiver wants a word
translated.

Motivation: live mic testing of the SpeechBrain VoxLingua107 approach
(lang_id.py) showed unreliable, near-chance confidence on short real-world
clips -- both on silence (fixed separately, see audio_utils.py) and on
actual speech (en/ru scores both near-zero, the "winner" often arbitrary).
Rather than keep trying to fix language *identification* on hard, short
audio, this sidesteps the problem: the caregiver explicitly signals intent
with a fixed phrase -- "как сказать" ("how do you say") -- said right
before the word they want translated. Everything else defaults to the
caregiver's selected language (Russian today; keyed the same way
LanguageConfig.kt's SupportedLanguages is, so this stays in step if that
changes or a language like Hindi gets added later).

This needs no classifier at all: the default-language transcript either
matches a known, fixed phrase or it doesn't -- a much easier problem than
distinguishing arbitrary short words across two languages, which is
exactly what the caregiver's own intentional command phrase should be.
"""
from __future__ import annotations

import difflib

# One entry per selectable default/target language -- add a new key here
# (e.g. Hindi's equivalent phrase) once that language is actually
# supported end to end. Not needed yet -- see the "not a concern right
# now" decision on Hindi.
TRIGGER_PHRASES = {
    "ru": "как сказать",
}


def normalize(text: str) -> str:
    return "".join(ch for ch in text.lower().strip() if ch.isalnum() or ch.isspace())


def matches_trigger_phrase(transcript: str, language: str, threshold: float = 0.75) -> bool:
    """
    True if `transcript` (from default-language ASR) is the trigger phrase
    for `language`, tolerant of minor ASR mistakes -- e.g. "как сказать"
    coming back as "как сказат" or with a trailing word stuck to it.
    Returns False if `language` has no configured trigger phrase yet.
    """
    phrase = TRIGGER_PHRASES.get(language)
    if phrase is None:
        return False

    normalized_transcript = normalize(transcript)
    normalized_phrase = normalize(phrase)

    # Cheap exact-substring check covers the common case (trigger phrase
    # transcribed correctly, possibly with something else attached); fall
    # back to a similarity ratio for near-misses on the whole transcript.
    if normalized_phrase in normalized_transcript:
        return True

    ratio = difflib.SequenceMatcher(None, normalized_transcript, normalized_phrase).ratio()
    return ratio >= threshold
