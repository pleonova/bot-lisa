"""
LLM ask layer.

Retrieval-augmented generation: the phrase candidates returned by the
retrieval service are injected as few-shot examples that CONSTRAIN the LLM's
output register, per the project's core learning (LLM alone -> textbook
Russian; grounded in curated phrases -> authentic baby register).

BARE-BONES NOTE:
Runs in mock mode (returns the top retrieved phrase verbatim, no API call) by
default so the whole stack works with zero API key / zero cost. Set
ANTHROPIC_API_KEY to switch to real generation.
"""
from __future__ import annotations

import os

SYSTEM_PROMPT_TEMPLATE = """You are generating a single short spoken phrase in Russian for a very young \
child (infant/toddler). Use authentic baby-register Russian: diminutives, short sentences, warm tone. \
Do not use textbook-formal Russian. Only output the phrase itself, nothing else.

Examples of the target register for this context:
{examples}
"""


def build_system_prompt(few_shot_phrases: list[dict]) -> str:
    examples = "\n".join(f"- {p['ru']}  ({p['gloss_en']})" for p in few_shot_phrases)
    return SYSTEM_PROMPT_TEMPLATE.format(examples=examples)


def generate(user_context: str, few_shot_phrases: list[dict]) -> dict:
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    system_prompt = build_system_prompt(few_shot_phrases)

    if not api_key:
        # Mock mode: no network call, deterministic, good for local dev / CI.
        top = few_shot_phrases[0] if few_shot_phrases else {"ru": "(нет данных)", "gloss_en": "(no data)"}
        return {
            "mode": "mock",
            "ru": top["ru"],
            "note": "ANTHROPIC_API_KEY not set -- returned top retrieved phrase verbatim.",
        }

    import anthropic  # imported lazily so mock mode has zero extra deps

    client = anthropic.Anthropic(api_key=api_key)
    response = client.messages.create(
        model="claude-sonnet-4-6",
        max_tokens=100,
        system=system_prompt,
        messages=[{"role": "user", "content": user_context}],
    )
    text = "".join(block.text for block in response.content if block.type == "text")
    return {"mode": "live", "ru": text.strip()}


TRANSLATE_SYSTEM_PROMPT_TEMPLATE = """You are helping a caregiver who is raising a bilingual infant/toddler \
find the right Russian phrase for something they want to say. Translate the caregiver's English word or \
phrase into short, authentic baby-register Russian: diminutives, warm tone, the way a Russian-speaking \
parent actually talks to a very young child -- not textbook-formal Russian. Output ONLY the Russian \
translation, nothing else (no quotes, no explanation).

Phrases already in the caregiver's curated library, for register/style reference (not necessarily related \
in meaning -- just match this tone):
{examples}
"""


def build_translate_system_prompt(style_examples: list[dict]) -> str:
    examples = "\n".join(f"- {p['ru']}  ({p['gloss_en']})" for p in style_examples) or "(library is empty)"
    return TRANSLATE_SYSTEM_PROMPT_TEMPLATE.format(examples=examples)


def translate(english_text: str, style_examples: list[dict]) -> dict:
    """
    Translate a caregiver's English word/phrase into baby-register Russian.

    Caller (main.py's /assist endpoint) is responsible for checking the
    curated phrase library FIRST and only calling this as a fallback --
    per the project's core principle, hand-vetted phrases should win over
    fresh LLM generations whenever a good match already exists.
    """
    api_key = os.environ.get("ANTHROPIC_API_KEY")

    if not api_key:
        return {
            "mode": "mock",
            "ru": f"[перевод: {english_text}]",
            "note": "ANTHROPIC_API_KEY not set -- mock translation placeholder, not a real translation.",
        }

    import anthropic  # imported lazily so mock mode has zero extra deps

    client = anthropic.Anthropic(api_key=api_key)
    response = client.messages.create(
        model="claude-sonnet-4-6",
        max_tokens=60,
        system=build_translate_system_prompt(style_examples),
        messages=[{"role": "user", "content": english_text}],
    )
    text = "".join(block.text for block in response.content if block.type == "text")
    return {"mode": "live", "ru": text.strip()}
