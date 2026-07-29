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
