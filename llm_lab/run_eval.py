"""
Runs both candidate model sizes (2B and 4B) against the routine-relations and
response-suggestion prompt sets, and writes results to eval/outputs/ for the
Russian-speaking collaborator to review.

This is a scratch evaluation script (speech_lab-style), not production code.
Nothing here is wired into the app or backend.

Usage:
    pip install llama-cpp-python --break-system-packages
    python run_eval.py
"""

import json
from pathlib import Path
from datetime import datetime

from llama_cpp import Llama

ROOT = Path(__file__).parent.parent
MODELS = {
    "qwen3.5-2b": ROOT / "models" / "qwen3.5-2b-q4_k_m.gguf",
    "qwen3.5-4b": ROOT / "models" / "qwen3.5-4b-q4_k_m.gguf",
}

SYSTEM_PROMPT = (
    "You are generating short, warm Russian phrases for a toddler's caregiver. "
    "Use diminutives, simple vocabulary, sentences under 8 words."
)


def build_prompt(instruction: str) -> str:
    return (
        f"<|im_start|>system\n{SYSTEM_PROMPT}<|im_end|>\n"
        f"<|im_start|>user\n{instruction}<|im_end|>\n"
        f"<|im_start|>assistant\n"
    )


def run_prompt_set(llm: Llama, prompt_set_path: Path, context_key: str) -> list[dict]:
    cases = json.loads(prompt_set_path.read_text(encoding="utf-8"))
    results = []
    for case in cases:
        subject = case.get("trigger_phrase") or case.get("heard_utterance")
        full_prompt = build_prompt(f"{case['instruction']} Context: \"{subject}\"")
        output = llm(full_prompt, max_tokens=200, temperature=0.6, stop=["<|im_end|>"])
        results.append({
            "id": case["id"],
            "input": subject,
            "output": output["choices"][0]["text"].strip(),
        })
    return results


def main():
    outputs_dir = ROOT / "eval" / "outputs"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    prompt_sets = {
        "routine_relations": ROOT / "prompts" / "routine_relations.json",
        "response_suggestions": ROOT / "prompts" / "response_suggestions.json",
    }

    for model_name, model_path in MODELS.items():
        if not model_path.exists():
            print(f"Skipping {model_name}: {model_path} not found. Download it into models/ first.")
            continue

        print(f"Loading {model_name}...")
        llm = Llama(model_path=str(model_path), n_ctx=4096, verbose=False)

        for set_name, set_path in prompt_sets.items():
            print(f"  Running {set_name}...")
            results = run_prompt_set(llm, set_path, set_name)
            out_file = outputs_dir / f"{model_name}_{set_name}_{timestamp}.json"
            out_file.write_text(
                json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            print(f"  Wrote {out_file}")

    print("\nDone. Review outputs in eval/outputs/ with your collaborator before proceeding.")


if __name__ == "__main__":
    main()
