"""
Phase 1 — local quality check (Mac, no RAM/CPU constraints).

Runs both candidate model sizes (2B and 4B) against the what-else and
how-to-respond prompt sets, and writes results to eval/outputs/ for the
Russian-speaking collaborator to review for register and contextual relevance.

Both prompt sets (what_else, how_to_respond) use the same fully-decoupled
layout: prompts/<task>.json carries only the task rules (persona- and
language-agnostic); voice comes from prompts/personas/<persona_id>.json; the
contrastive bad/good example pair comes from
prompts/examples/<task>.<lang>.<persona_id>.json; and the cases themselves
live in eval/cases/<task>_<persona_id>.json, each case self-describing its
own persona + language. prompts/compose_prompt.py stitches all four into the
final prompt. `--persona` selects which case file to run; `--generic` drops
each case's own examples hint to test whether persona + rules + bad/good
contrast alone generalize.

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
    python3 llm_lab/eval/run_eval.py                       # everything
    python3 llm_lab/eval/run_eval.py --model 4b --set what_else --print
    python3 llm_lab/eval/run_eval.py --set what_else --case bedtime_1 --dry-run

Flags (see --help): --model {2b,4b,all}  --set {what_else,how_to_respond,all}
    --case ID (repeatable)  --persona NAME  --generic  --print  --dry-run

Model files are looked for, in order, under:
    $LLM_LAB_MODELS  ->  ~/models  ->  llm_lab/models
Overrides: $LLAMA_SERVER (binary path), $LLM_LAB_PORT (8080),
           $LLM_LAB_PERSONA (persona id, default "caregiver_infant";
           --persona wins over the env var).
"""

import os
import re
import sys
import json
import shutil
import argparse
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from datetime import datetime

ROOT = Path(__file__).parent.parent  # llm_lab/
sys.path.insert(0, str(ROOT))

from prompts import compose_prompt  # noqa: E402

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

DEFAULT_PERSONA = os.environ.get("LLM_LAB_PERSONA", "caregiver_infant")

TASKS = ["what_else", "how_to_respond"]

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


def load_cases(task: str, persona_name: str, case_ids: list[str] | None) -> list[dict]:
    path = ROOT / "eval" / "cases" / f"{task}_{persona_name}.json"
    if not path.exists():
        available = sorted(
            p.stem.removeprefix(f"{task}_")
            for p in (ROOT / "eval" / "cases").glob(f"{task}_*.json")
        )
        raise SystemExit(f"no {task} cases for persona '{persona_name}' at {path}. "
                          f"Available: {available}")
    cases = json.loads(path.read_text(encoding="utf-8"))["cases"]
    if case_ids:
        cases = [c for c in cases if c["id"] in case_ids]
    return cases


def run_task(task: str, persona_name: str, case_ids: list[str] | None,
             do_print: bool, generic: bool, dry_run: bool) -> list[dict]:
    cases = load_cases(task, persona_name, case_ids)
    results = []
    for case in cases:
        system, user = compose_prompt.compose_prompt(case, task=task, generic=generic)
        if dry_run:
            print(f"\n=== {task} / {case['id']} ===")
            print(f"SYSTEM: {system}")
            print(f"USER  : {user}")
            continue
        output = chat(system, user)
        results.append({"id": case["id"], "input": case["utterance"], "output": output})
        if do_print:
            print(f"\n  [{case['id']}] {case['utterance']}\n{_indent(output)}\n")
    return results


def _indent(text: str) -> str:
    return "\n".join("    " + line for line in text.splitlines())


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Phase 1 prompt eval against local Qwen3.5 GGUFs via llama-server.")
    p.add_argument("--model", choices=["2b", "4b", "all"], default="all",
                   help="model size to run (default: all)")
    p.add_argument("--set", dest="prompt_set", choices=[*TASKS, "all"], default="all",
                   help="prompt set to run (default: all)")
    p.add_argument("--case", action="append", metavar="ID", dest="cases",
                   help="only this case id; repeatable (default: all cases in the set)")
    p.add_argument("--persona", default=DEFAULT_PERSONA,
                   help=f"persona id (default: {DEFAULT_PERSONA}) — also selects which "
                        f"eval/cases/<task>_<persona>.json case file runs")
    p.add_argument("--generic", action="store_true",
                   help="drop each case's own examples hint — tests whether persona + "
                        "rules + bad/good contrast generalize alone")
    p.add_argument("--print", dest="do_print", action="store_true",
                   help="echo id + output to the terminal as results come back")
    p.add_argument("--dry-run", action="store_true",
                   help="print the assembled system + user prompts and exit (no model call)")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    set_names = list(TASKS) if args.prompt_set == "all" else [args.prompt_set]

    print(f"Persona: {args.persona}")

    def run_set(set_name: str, dry_run: bool) -> list[dict]:
        return run_task(set_name, args.persona, args.cases, args.do_print, args.generic, dry_run)

    if args.dry_run:
        for set_name in set_names:
            run_set(set_name, dry_run=True)
        return

    if not LLAMA_SERVER:
        raise SystemExit("llama-server not found. Run `brew install llama.cpp`, "
                         "or point $LLAMA_SERVER at the binary.")

    outputs_dir = ROOT / "eval" / "outputs"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    model_keys = ([f"qwen3.5-{args.model}"] if args.model != "all"
                  else list(MODEL_CANDIDATES))

    ran_any = False
    for model_key in model_keys:
        model_path = resolve_model(model_key)
        if model_path is None:
            searched = [str(d) for d in MODEL_DIRS if d is not None]
            print(f"Skipping {model_key}: no GGUF found under {searched}. "
                  f"Download it first (see README 'Setup').")
            continue

        ran_any = True
        print(f"Loading {model_key} ({model_path.name}) into llama-server ...")
        proc = start_server(model_path)
        try:
            for set_name in set_names:
                results = run_set(set_name, dry_run=False)
                if not results:
                    print(f"  {set_name}: no matching cases, skipped")
                    continue
                print(f"  {set_name} ({len(results)} case(s)) ...")
                variant = f"{args.persona}_generic" if args.generic else args.persona
                out_file = (outputs_dir /
                            f"{model_key}_{set_name}_{variant}_{timestamp}.json")
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
