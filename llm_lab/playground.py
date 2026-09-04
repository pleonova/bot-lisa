"""
Scratch playground: edit SYSTEM / USER below, run, see the model's reply.

No cases, no personas, no templates — just a raw prompt pair sent straight to
qwen3.5-4b, for fast manual back-and-forth outside the eval harness. Reuses
eval/run_eval.py's server lifecycle + chat() call so behavior (sampling
params, <think> stripping, etc.) matches the real eval exactly.

Usage:
    python3 llm_lab/playground.py

Then just edit SYSTEM/USER below and re-run — each run loads the model fresh
(~a few seconds), sends the one exchange, and exits.
"""

import importlib.util
from pathlib import Path

ROOT = Path(__file__).parent

# Load eval/run_eval.py by file path rather than `from eval.run_eval import
# ...` — the main repo has its own top-level eval/ package (a regular
# package, __init__.py and all) for an unrelated script, and that always
# wins name resolution over llm_lab/eval's namespace package, silently
# importing the wrong run_eval.py.
_spec = importlib.util.spec_from_file_location("_llm_lab_run_eval", ROOT / "eval" / "run_eval.py")
_run_eval = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_run_eval)

LLAMA_SERVER = _run_eval.LLAMA_SERVER
resolve_model = _run_eval.resolve_model
start_server = _run_eval.start_server
stop_server = _run_eval.stop_server
chat = _run_eval.chat

MODEL_KEY = "qwen3.5-4b"  # or "qwen3.5-2b"

# ---- Edit these, then re-run ----
SYSTEM = (
    "You are helping the caregiver who is talking to a small child. "
    "Everything you produce is in Russian. "
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble."
)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "still part of the same bedtime routine.\n\n"
    "Heard: \"спокойной ночи\""
)
# ----------------------------------


def main() -> None:
    if not LLAMA_SERVER:
        raise SystemExit("llama-server not found. Run `brew install llama.cpp`, "
                          "or point $LLAMA_SERVER at the binary.")
    model_path = resolve_model(MODEL_KEY)
    if model_path is None:
        raise SystemExit(f"{MODEL_KEY} GGUF not found. See README 'Setup'.")

    print(f"Loading {model_path.name} ...")
    proc = start_server(model_path)
    try:
        print("\n=== SYSTEM ===")
        print(SYSTEM)
        print("\n=== USER ===")
        print(USER)
        output = chat(SYSTEM, USER)
        print("\n=== RESPONSE ===")
        print(output)
    finally:
        stop_server(proc)


if __name__ == "__main__":
    main()
