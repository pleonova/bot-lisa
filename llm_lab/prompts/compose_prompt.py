"""
Stitches together: task template + persona + language examples + case
to produce a final (system, user) prompt pair. Task-agnostic — works for any
task under prompts/ (what_else, how_to_respond, ...) as long as its files
follow the layout below, whatever case fields that task's user_template
references (what_else uses {activity}, how_to_respond uses {input_kind}).

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

    good_examples = "\n".join(f'- "{g}"' for g in examples["good_examples"])

    # Optional persona-specific extra rules (e.g. caregiver_infant allows
    # reassurance-only steps). Rendered as extra bullet lines appended after
    # the task's own rules; empty for personas that don't define any, and
    # simply ignored by str.format() for task templates with no
    # {persona_rules_clause} slot.
    persona_rules = persona.get("extra_rules") or []
    persona_rules_clause = "".join(f"\n- {rule}" for rule in persona_rules)

    # Merge in this order so a case can't accidentally clobber the pieces
    # that make the prompt make sense (speaker/hint_clause/examples), but
    # still supplies whatever task-specific slot the template needs
    # ({activity}, {input_kind}, ...) via **case.
    fields = {
        **case,
        "speaker": persona["default_speaker"],
        "hint_clause": hint_clause,
        "bad_example": examples["bad_example"],
        "good_examples": good_examples,
        "persona_rules_clause": persona_rules_clause,
    }
    # user_template is stored as a list of lines (not one long escaped
    # string) so prompts/<task>.json stays readable/diffable. A line that's
    # itself a list is a single long line word-wrapped across several JSON
    # array entries for editability; join its fragments with a space to
    # get back the one logical line before joining lines with "\n".
    def _line(entry):
        return " ".join(entry) if isinstance(entry, list) else entry

    user_template = "\n".join(_line(entry) for entry in template["user_template"])
    user = user_template.format(**fields)
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
