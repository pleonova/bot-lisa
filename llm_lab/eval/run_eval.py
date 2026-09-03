"""
Phase 1 — local quality check (Mac, no RAM/CPU constraints).

Runs both candidate model sizes (2B and 4B) against the routine-relations and
response-suggestion prompt sets, and writes results to eval/outputs/ for the
Russian-speaking collaborator to review for register (infant-directed vocabulary,
diminutives, sentence length) and contextual relevance.

This is a scratch evaluation script (speech_lab-style), not production code.
Nothing here is wired into the app or backend.

Runtime: Homebrew llama.cpp (`brew install llama.cpp`). We drive `llama-server`
over its OpenAI-compatible HTTP API — one model load per size, clean JSON out.
(llama-cpp-python was the original plan but the prebuilt wheels currently fail
CRC on extract, and a source build isn't worth it for a throwaway eval. Bonus:
this leaves `llama-bench` ready for Phase 2.)

Usage:
    brew install llama.cpp
    # download the GGUFs (see README "Setup") into ~/models/ or llm_lab/models/
    python llm_lab/eval/run_eval.py

Model files are looked for, in order, under:
    $LLM_LAB_MODELS  ->  ~/models  ->  llm_lab/models
Overrides: $LLAMA_SERVER (binary path), $LLM_LAB_PORT (default 8080).
"""

import os
import re
import json
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from datetime import datetime

ROOT = Path(__file__).parent.parent  # llm_lab/

LLAMA_SERVER = os.environ.get("LLAMA_SERVER") or shutil.which("llama-server")
HOST = "127.0.0.1"
PORT = int(os.environ.get("LLM_LAB_PORT", "8080"))
BASE_URL = f"http://{HOST}:{PORT}"

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
    "Use diminutives, simple vocabulary, sentences under 8 words. Reply in Russian only."
)

# Belt-and-suspenders: the server runs with --reasoning off, but strip any
# stray <think>…</think> just in case the template emits it anyway.
THINK_RE = re.compile(r"<think>.*?</think>\s*", re.DOTALL)

GEN_PARAMS = {"temperature": 0.6, "top_p": 0.9, "seed": 42, "max_tokens": 256}


def resolve_model(key: str) -> Path | None:
    for d in MODEL_DIRS:
        if d is None:
            continue
        for name in MODEL_CANDIDATES[key]:
            candidate = d / name
            if candidate.exists():
                return candidate
    return None


def start_server(model_path: Path) -> subprocess.Popen:
    proc = subprocess.Popen(
        [
            LLAMA_SERVER,
            "-m", str(model_path),
            "--host", HOST,
            "--port", str(PORT),
            "-ngl", "99",          # all layers on the Metal GPU
            "-c", "4096",
            "--jinja",             # chat template from the GGUF
            "-rea", "off",         # disable Qwen thinking
            "--no-webui",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for _ in range(180):
        if proc.poll() is not None:
            raise RuntimeError(f"llama-server exited early (code {proc.returncode})")
        try:
            with urllib.request.urlopen(f"{BASE_URL}/health", timeout=2) as r:
                if r.status == 200:
                    return proc
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            time.sleep(1)
    proc.terminate()
    raise RuntimeError("llama-server never became healthy")


def stop_server(proc: subprocess.Popen) -> None:
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()


def chat(system: str, user: str) -> str:
    body = json.dumps(
        {"messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ], **GEN_PARAMS}
    ).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/v1/chat/completions",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=600) as r:
        data = json.load(r)
    text = data["choices"][0]["message"]["content"].strip()
    return THINK_RE.sub("", text).strip()


def run_prompt_set(prompt_set_path: Path) -> list[dict]:
    cases = json.loads(prompt_set_path.read_text(encoding="utf-8"))
    results = []
    for case in cases:
        subject = case.get("trigger_phrase") or case.get("heard_utterance")
        user_msg = f'{case["instruction"]} Context: "{subject}"'
        results.append({
            "id": case["id"],
            "input": subject,
            "output": chat(SYSTEM_PROMPT, user_msg),
        })
    return results


def main():
    if not LLAMA_SERVER:
        raise SystemExit(
            "llama-server not found. Run `brew install llama.cpp`, or point "
            "$LLAMA_SERVER at the binary."
        )

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
        print(f"Loading {model_name} ({model_path.name}) into llama-server ...")
        proc = start_server(model_path)
        try:
            for set_name, set_path in prompt_sets.items():
                print(f"  {set_name} ...")
                results = run_prompt_set(set_path)
                out_file = outputs_dir / f"{model_name}_{set_name}_{timestamp}.json"
                out_file.write_text(
                    json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
                )
                print(f"  wrote {out_file}")
        finally:
            stop_server(proc)

    if not ran_any:
        print("\nNo models found — nothing ran. See README 'Setup' for download steps.")
        return

    print("\nDone. Review outputs in eval/outputs/ with your collaborator "
          "before deciding whether to proceed to Phase 2.")


if __name__ == "__main__":
    main()
