"""
Whisper transcription, forced into whichever language lang_id.py already
decided on -- rather than letting Whisper guess the language itself.

This is the same trick the reference example uses (openai/whisper-base via
HF transformers, `forced_decoder_ids`): Whisper's own built-in language
auto-detection needs several seconds of audio to be reliable and is
documented to struggle below that, which is exactly bot-lisa's problem case
(single words). Forcing the language -- decided instead by the dedicated
SpeechBrain classifier in lang_id.py -- sidesteps that weakness entirely:
Whisper is only ever asked to transcribe, never to also guess the language.
"""
from __future__ import annotations

import numpy as np
import torch
from transformers import WhisperForConditionalGeneration, WhisperProcessor

SAMPLING_RATE = 16000

_MODEL_NAME = "openai/whisper-base"
_processor = WhisperProcessor.from_pretrained(_MODEL_NAME)
_model = WhisperForConditionalGeneration.from_pretrained(_MODEL_NAME)

_device = "cuda" if torch.cuda.is_available() else "cpu"
_model.to(_device)


def transcribe(chunk: np.ndarray, language: str) -> str:
    """
    language: "en" or "ru" (or any Whisper-supported language code) --
    normally whatever lang_id.identify_language() just decided.
    """
    if chunk.dtype != np.float32:
        chunk = chunk.astype(np.float32)

    input_features = _processor(
        chunk, sampling_rate=SAMPLING_RATE, return_tensors="pt"
    ).input_features.to(_device)

    # NOTE: the reference example builds `forced_decoder_ids` via
    # processor.tokenizer.get_decoder_prompt_ids(...) and passes that to
    # generate(). Checked against the transformers version this was tested
    # with (5.15.1): that path still works but is flagged internally as
    # deprecated in favor of passing language= and task= straight to
    # generate(), which is what current transformers actually recommends --
    # used here instead so this doesn't inherit a deprecation warning (or a
    # future removal) from copying the older pattern verbatim.
    predicted_ids = _model.generate(input_features, language=language, task="transcribe")
    transcription = _processor.batch_decode(predicted_ids, skip_special_tokens=True)[0]
    return transcription.strip()
