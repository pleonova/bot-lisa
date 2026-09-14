"""
Stitches together: persona + language examples + case + task template to
produce a final (system, user) prompt pair. Task-agnostic and schema-flexible
— a persona's system_template and a task's user_template can each reference
whatever placeholders their own text needs ({gender}, {activity},
{input_kind}, {few_shot_examples}, {bad_example}, ...); this module doesn't
hardcode field names, it just merges persona + examples + case into one dict
and lets str.format() pick out what each template actually references.

Layout this expects (relative to llm_lab/):
  prompts/<task>.json                                  -- language & persona agnostic
  prompts/personas/<persona_id>.json                    -- tone/register, persona-only
  prompts/examples/<task>.<lang>.<persona_id>.json      -- example data for this combo

A "case" is one entry from an eval set file, e.g.:
  {
    "id": "bedtime_1",
    "utterance": "спокойной ночи",
    "activity": "bedtime routine",
    "persona": "caregiver_infant",
    "language": "ru",
    "examples": ["brushing teeth", "turning off the light", "a goodnight hug"]
  }

Two example-file shapes are supported, chosen per persona/task by whichever
placeholders that persona's system_template or the task's user_template
reference:
  - "bad_example" (str) + "good_examples" (list[str]) — a contrastive pair,
    rendered as a "- \"...\"" bullet block.
  - "few_shot_examples" (list of {"heard": str, "responses": [str, str, str]})
    — full demonstrations, rendered as repeated "Give me three follows to
    this: \"...\"" blocks.
A single examples file can define both if two different templates need them.

Set generic=True (or omit "examples" from the case) to test whether the
persona + rules alone generalize as well as the phrase-specific hints do.
"""

import json
from pathlib import Path

PROMPTS_DIR = Path(__file__).parent


def _load(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _line(entry) -> str:
    """Reassemble one logical line that may be word-wrapped in the JSON
    source as a list of fragments (for editability); join with a space to
    get back the original line. A plain string passes through unchanged."""
    return " ".join(entry) if isinstance(entry, list) else entry


def _render(template_lines) -> str:
    """Reassemble a template field stored as a list of lines (JSON-friendly,
    so prompts/<task>.json and personas/<id>.json stay readable/diffable)
    back into one string, lines joined by "\n". Each line may itself be a
    list of word-wrap fragments (see _line). A plain string passes through
    unchanged, so a persona that wants one continuous paragraph can wrap its
    fragments in a single extra list level."""
    if isinstance(template_lines, str):
        return template_lines
    return "\n".join(_line(line) for line in template_lines)


def _format_good_examples(items: list[str]) -> str:
    return "\n".join(f'- "{g}"' for g in items)


def _format_few_shot_examples(demos: list[dict]) -> str:
    blocks = []
    for demo in demos:
        lines = [f'Give me three follows to this: "{demo["heard"]}"', *demo["responses"]]
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def compose_prompt(case: dict, task: str = "what_else", generic: bool = False) -> tuple[str, str]:
    """Returns (system_prompt, user_prompt) for a given case."""
    template = _load(PROMPTS_DIR / f"{task}.json")
    persona = _load(PROMPTS_DIR / "personas" / f"{case['persona']}.json")
    examples = _load(
        PROMPTS_DIR / "examples" / f"{task}.{case['language']}.{case['persona']}.json"
    )

    hint_examples = None if generic else case.get("examples")
    hint_clause = f" (e.g. {', '.join(hint_examples)})" if hint_examples else ""

    # Pre-render whichever structured example data this file provides, so
    # it's ready to drop straight into a template as a string.
    formatted_examples = dict(examples)
    if "good_examples" in formatted_examples:
        formatted_examples["good_examples"] = _format_good_examples(examples["good_examples"])
    if "few_shot_examples" in formatted_examples:
        formatted_examples["few_shot_examples"] = _format_few_shot_examples(examples["few_shot_examples"])

    # Merge in this order so a case can't accidentally clobber the pieces
    # that make the prompt make sense (speaker/hint_clause/language), while
    # still supplying whatever placeholder each template needs — {gender}
    # from the persona, {activity}/{input_kind} from the case,
    # {few_shot_examples}/{bad_example}/{good_examples} from the examples file.
    fields = {
        **persona,
        **formatted_examples,
        **case,
        "language": examples["language_display"],
        "speaker": persona["default_speaker"],
        "hint_clause": hint_clause,
    }

    system = _render(persona["system_template"]).format(**fields)
    user = _render(template["user_template"]).format(**fields)
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
