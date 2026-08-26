"""
English/Russian spoken-language identification using SpeechBrain's
VoxLingua107 ECAPA-TDNN model (Apache 2.0), restricted to the two
languages bot-lisa cares about.

Adapted from the reference example at
https://huggingface.co/spaces/tan-z-tan/speech_language_detection
(originally Japanese/English) -- same model, same call shape, just a
different two-language subset and English/Russian ISO codes instead of
display names, since that's more convenient for downstream routing.
"""
from __future__ import annotations

import math

import numpy as np
import torch
from speechbrain.inference.classifiers import EncoderClassifier

SAMPLING_RATE = 16000

# Full VoxLingua107 label set the model was trained on -- needed to decode
# classify_batch's raw output tensor (index -> language). Order matters,
# copied from the reference example / the model's label encoder.
INDEX_TO_LANG = {
    0: "Abkhazian", 1: "Afrikaans", 2: "Amharic", 3: "Arabic", 4: "Assamese",
    5: "Azerbaijani", 6: "Bashkir", 7: "Belarusian", 8: "Bulgarian", 9: "Bengali",
    10: "Tibetan", 11: "Breton", 12: "Bosnian", 13: "Catalan", 14: "Cebuano",
    15: "Czech", 16: "Welsh", 17: "Danish", 18: "German", 19: "Greek",
    20: "English", 21: "Esperanto", 22: "Spanish", 23: "Estonian", 24: "Basque",
    25: "Persian", 26: "Finnish", 27: "Faroese", 28: "French", 29: "Galician",
    30: "Guarani", 31: "Gujarati", 32: "Manx", 33: "Hausa", 34: "Hawaiian",
    35: "Hindi", 36: "Croatian", 37: "Haitian", 38: "Hungarian", 39: "Armenian",
    40: "Interlingua", 41: "Indonesian", 42: "Icelandic", 43: "Italian", 44: "Hebrew",
    45: "Japanese", 46: "Javanese", 47: "Georgian", 48: "Kazakh", 49: "Central Khmer",
    50: "Kannada", 51: "Korean", 52: "Latin", 53: "Luxembourgish", 54: "Lingala",
    55: "Lao", 56: "Lithuanian", 57: "Latvian", 58: "Malagasy", 59: "Maori",
    60: "Macedonian", 61: "Malayalam", 62: "Mongolian", 63: "Marathi", 64: "Malay",
    65: "Maltese", 66: "Burmese", 67: "Nepali", 68: "Dutch", 69: "Norwegian Nynorsk",
    70: "Norwegian", 71: "Occitan", 72: "Panjabi", 73: "Polish", 74: "Pushto",
    75: "Portuguese", 76: "Romanian", 77: "Russian", 78: "Sanskrit", 79: "Scots",
    80: "Sindhi", 81: "Sinhala", 82: "Slovak", 83: "Slovenian", 84: "Shona",
    85: "Somali", 86: "Albanian", 87: "Serbian", 88: "Sundanese", 89: "Swedish",
    90: "Swahili", 91: "Tamil", 92: "Telugu", 93: "Tajik", 94: "Thai",
    95: "Turkmen", 96: "Tagalog", 97: "Turkish", 98: "Tatar", 99: "Ukrainian",
    100: "Urdu", 101: "Uzbek", 102: "Vietnamese", 103: "Waray", 104: "Yiddish",
    105: "Yoruba", 106: "Chinese",
}

# bot-lisa only ever needs to decide between these two. LANG_TO_CODE maps
# the model's display name to the short code the rest of the app should
# use (matches SupportedLanguages.RUSSIAN.code's "ru-RU" style prefix,
# trimmed to the bare ISO 639-1 code -- see LanguageConfig.kt).
CANDIDATE_LANGUAGES = ["English", "Russian"]
LANG_TO_CODE = {"English": "en", "Russian": "ru"}

# Loaded once at import time (matches the reference example) -- SpeechBrain
# downloads and caches the model under ~/.cache the first time this runs.
_classifier = EncoderClassifier.from_hparams(
    source="speechbrain/lang-id-voxlingua107-ecapa",
    savedir="pretrained_models/lang-id-voxlingua107-ecapa",
)


def identify_language(chunk: np.ndarray) -> dict:
    """
    Classify a mono, 16kHz, float32 audio chunk as English or Russian.

    Returns a dict:
        {
            "language": "en" | "ru",       # whichever of the two scored higher
            "confidence": float,            # that language's probability, 0-1
            "en_prob": float,
            "ru_prob": float,
            "top3_all_languages": [(name, prob), ...],  # sanity-check/debug aid
        }

    NOTE: this always picks one of the two candidates -- it does not have an
    "neither" / low-confidence path yet. Given the open question logged in
    the project roadmap about single-word accuracy, check `confidence` and
    `en_prob`/`ru_prob`'s closeness before trusting a borderline call; a
    real "reject and ask again" threshold should get tuned once there's a
    labeled eval set of real single-word recordings to tune it against.
    """
    if chunk.dtype != np.float32:
        chunk = chunk.astype(np.float32)

    # classify_batch returns (log-probabilities, score, index, label) for
    # each item in the batch -- one item here, so index [0] throughout.
    log_probs, _, _, _ = _classifier.classify_batch(torch.from_numpy(chunk).unsqueeze(0))

    # SpeechBrain returns natural-log probabilities; convert to a 0-1 scale
    # per language (matches the reference example's `100 * exp(score)`,
    # scaled to 0-1 instead of 0-100 since that's the more usual convention
    # for a "confidence" field in an API response).
    all_scores = {INDEX_TO_LANG[i]: math.exp(score) for i, score in enumerate(log_probs[0])}

    en_prob = all_scores["English"]
    ru_prob = all_scores["Russian"]
    winner = "en" if en_prob >= ru_prob else "ru"
    confidence = en_prob if winner == "en" else ru_prob

    top3 = sorted(all_scores.items(), key=lambda kv: kv[1], reverse=True)[:3]

    return {
        "language": winner,
        "confidence": float(confidence),
        "en_prob": float(en_prob),
        "ru_prob": float(ru_prob),
        "top3_all_languages": top3,
    }
