"""
Stitches together: task template + persona + language examples + case
to produce a final (system, user) prompt pair.

Layout this expects (relative to llm_lab/):
  prompts/<task>.json                                  -- language & persona agnostic
  prompts/personas/<persona_id>.json                    -- tone/register, persona-only
  prompts/examples/<task>.<lang>.<persona_id>.json      -- good/bad pair for this combo

A "case" is one entry from an eval set file, e.g.:
  {
    "id": "bedtime_1",
    "utterance": "спокойной ночи",
    "activity": "bedtime routine",
    "persona": "caregiver_infant",
    "language": "ru",
    "examples": ["brushing teeth", "turning off the light", "a goodnight hug"]
  }

Set generic=True (or omit "examples" from the case) to test whether the
persona + rules + good/bad pair alone generalize as well as the
phrase-specific hints do.
"""

import json
from pathlib import Path

PROMPTS_DIR = Path(__file__).parent


def _load(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def compose_prompt(case: dict, task: str = "what_else", generic: bool = False) -> tuple[str, str]:
    """Returns (system_prompt, user_prompt) for a given case."""
    template = _load(PROMPTS_DIR / f"{task}.json")
    persona = _load(PROMPTS_DIR / "personas" / f"{case['persona']}.json")
    examples = _load(
        PROMPTS_DIR / "examples" / f"{task}.{case['language']}.{case['persona']}.json"
    )

    system = persona["system_template"].format(language=examples["language_display"])

    hint_examples = None if generic else case.get("examples")
    hint_clause = f" (e.g. {', '.join(hint_examples)})" if hint_examples else ""

    user = template["user_template"].format(
        speaker=persona["default_speaker"],
        activity=case["activity"],
        hint_clause=hint_clause,
        bad_example=examples["bad_example"],
        good_example=examples["good_example"],
        utterance_label=template["utterance_label"],
        utterance=case["utterance"],
    )
    return system, user


if __name__ == "__main__":
    # quick smoke test
    demo_case = {
        "id": "bedtime_1",
        "utterance": "спокойной ночи",
        "activity": "bedtime routine",
        "persona": "caregiver_infant",
        "language": "ru",
        "examples": ["brushing teeth", "turning off the light", "a goodnight hug"],
    }
    sys_p, usr_p = compose_prompt(demo_case)
    print("=== SYSTEM ===\n", sys_p)
    print("\n=== USER (hinted) ===\n", usr_p)
    sys_p, usr_p = compose_prompt(demo_case, generic=True)
    print("\n=== USER (generic) ===\n", usr_p)
