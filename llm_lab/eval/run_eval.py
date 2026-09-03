"""
Phase 1 — local quality check (Mac, no RAM/CPU constraints).

Runs both candidate model sizes (2B and 4B) against the routine-relations and
response-suggestion prompt sets, and writes results to eval/outputs/ for the
Russian-speaking collaborator to review for register (infant-directed vocabulary,
diminutives, sentence length) and contextual relevance.

This is a scratch evaluation script (speech_lab-style), not production code.
Nothing here is wired into the app or backend.

Usage:
    pip install llama-cpp-python --break-system-packages
    # download the GGUFs (see README "Setup") into ~/models/ or llm_lab/models/
    python eval/run_eval.py

Model files are looked for, in order, under:
    $LLM_LAB_MODELS  ->  ~/models  ->  llm_lab/models
Each is checked for several known filenames (unsloth / bartowski / lowercase),
so whichever repo you pulled from should just work.
"""

import os
import re
import json
from pathlib import Path
from datetime import datetime

from llama_cpp import Llama

ROOT = Path(__file__).parent.parent  # llm_lab/

# Candidate filenames per size, in preference order.
MODEL_CANDIDATES = {
    "qwen3.5-2b": [
        "Qwen3.5-2B-Q4_K_M.gguf",        # unsloth/Qwen3.5-2B-GGUF
        "Qwen_Qwen3.5-2B-Q4_K_M.gguf",   # bartowski/Qwen_Qwen3.5-2B-GGUF
        "qwen3.5-2b-q4_k_m.gguf",
    ],
    "qwen3.5-4b": [
        "Qwen3.5-4B-Q4_K_M.gguf",        # unsloth/Qwen3.5-4B-GGUF
        "Qwen_Qwen3.5-4B-Q4_K_M.gguf",   # bartowski/Qwen_Qwen3.5-4B-GGUF
        "qwen3.5-4b-q4_k_m.gguf",
    ],
}

MODEL_DIRS = [
    Path(os.environ["LLM_LAB_MODELS"]) if os.environ.get("LLM_LAB_MODELS") else None,
    Path.home() / "models",
    ROOT / "models",
]

SYSTEM_PROMPT = (
    "You are generating short, warm Russian phrases for a toddler's caregiver. "
    "Use diminutives, simple vocabulary, sentences under 8 words. "
    "Reply in Russian only. /no_think"
)

# Qwen3.x emits <think>…</think> before the answer; strip it before review.
THINK_RE = re.compile(r"<think>.*?</think>\s*", re.DOTALL)


def resolve_model(key: str) -> Path | None:
    for d in MODEL_DIRS:
        if d is None:
            continue
        for name in MODEL_CANDIDATES[key]:
            candidate = d / name
            if candidate.exists():
                return candidate
    return None


def run_prompt_set(llm: Llama, prompt_set_path: Path) -> list[dict]:
    cases = json.loads(prompt_set_path.read_text(encoding="utf-8"))
    results = []
    for case in cases:
        subject = case.get("trigger_phrase") or case.get("heard_utterance")
        user_msg = f'{case["instruction"]} Context: "{subject}"'
        resp = llm.create_chat_completion(
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_msg},
            ],
            max_tokens=256,
            temperature=0.6,
        )
        text = resp["choices"][0]["message"]["content"].strip()
        text = THINK_RE.sub("", text).strip()
        results.append({"id": case["id"], "input": subject, "output": text})
    return results


def main():
    outputs_dir = ROOT / "eval" / "outputs"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    prompt_sets = {
        "routine_relations": ROOT / "prompts" / "routine_relations.json",
        "response_suggestions": ROOT / "prompts" / "response_suggestions.json",
    }

    ran_any = False
    for model_name in MODEL_CANDIDATES:
        model_path = resolve_model(model_name)
        if model_path is None:
            searched = [str(d) for d in MODEL_DIRS if d is not None]
            print(f"Skipping {model_name}: no GGUF found under {searched}. "
                  f"Download it first (see README 'Setup').")
            continue

        ran_any = True
        print(f"Loading {model_name} from {model_path} ...")
        llm = Llama(model_path=str(model_path), n_ctx=4096, verbose=False)

        for set_name, set_path in prompt_sets.items():
            print(f"  Running {set_name} ...")
            results = run_prompt_set(llm, set_path)
            out_file = outputs_dir / f"{model_name}_{set_name}_{timestamp}.json"
            out_file.write_text(
                json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            print(f"  Wrote {out_file}")

    if not ran_any:
        print("\nNo models found — nothing ran. See README 'Setup' for download steps.")
        return

    print("\nDone. Review outputs in eval/outputs/ with your collaborator "
          "before deciding whether to proceed to Phase 2.")


if __name__ == "__main__":
    main()
