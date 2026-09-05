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

# ---- Edit these, then re-run ----
SYSTEM = (
    "You are helping the caregiver who is talking to a small child. "
    "Everything you produce is in Russian. "
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble."
)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "still part of the same routine where the following said to a an infant ...\n\n"
    "\"спокойной ночи\""
)
# ----------------------------------

# Try a different phrase
# ---- Edit these, then re-run ----
SYSTEM = (
    "You are helping the caregiver who is talking to a small child. "
    "Everything you produce is in Russian. "
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble."
)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "still part of the same routine where the following said to a an infant ...\n\n"
    "\"Давай кушать\""
)
# ----------------------------------

# Try removing the Russian requirement
# ---- Edit these, then re-run ----
SYSTEM = (
    "You are helping the caregiver who is talking to a small child. "
    "Everything you produce is in English. "
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble."
)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "still part of the same routine where the following said to a an infant ...\n\n"
    "\"Давай кушать\""
)
# ----------------------------------

# add back the routine phrasing
# ---- Edit these, then re-run ----
SYSTEM = (
    "You are helping the caregiver who is talking to a small child. "
    "Everything you produce is in English."
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble."
)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    # "still part of the same mealtime routine.\n\n"
    # "Heard: \"Давай кушать\""
    
    # "still part of the same bedtime routine.\n\n"
    # "Heard: \"спокойной ночи\""

    # "still part of the same bathtime routine.\n\n"
    # "Heard: \"Залезай в ванночку.\""  

    "still part of the same routine.\n\n"
    "Heard: \"Залезай в ванночку.\""  )
# ----------------------------------


# ---- Edit these, then re-run ----
SYSTEM = (
    "You are helping the caregiver who is talking to a small child. "
    "Translate into grammatically correct Russian."
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble."
)

USER = (
"Time for bath time, my little one."
"Let's get your clean towel ready now."
"Shall we splash some water together?"

)
# ----------------------------------
'''
=== RESPONSE ===
Пришло время для купания, мой маленький.
Давай подготовим чистую мочалку прямо сейчас.
Давай вместе помоем руки под струей воды?
'''


# add back the routine phrasing
# ---- Edit these, then re-run ----
SYSTEM = (
    "You are helping the caregiver who is talking to a small boy. " 
    "Everything you produce is in Russian and must be grammatically correct. "
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble. "
)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    # "still part of the same mealtime routine.\n\n"
    # "Heard: \"Давай кушать\""
    
    # "still part of the same bedtime routine.\n\n"
    # "Heard: \"спокойной ночи\""

    # "still part of the same bathtime routine.\n\n"
    # "Heard: \"Залезай в ванночку.\""  

    "still part of the same routine.\n\n"
    "Heard: \"Залезай в ванночку.\""  
    )
# ----------------------------------


LANGUAGE = "Russian"
PHRASE = "Залезай в ванночку."
SYSTEM = (
    "You are helping the caregiver who is talking to a small boy. "
    "Everything you produce is in {LANGUAGE} and must be grammatically correct. "
    "Keep it warm and infant-directed: use diminutives/softened forms naturally, "
    "simple vocabulary, sentences under 8 words. "
    "Use 'your', not 'my', when referring to the child's things. "
    "Reply with the phrases only — one per line, no numbering, no preamble."
).format(LANGUAGE=LANGUAGE)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "same routine — mix of speech and at least one physical action. "
    "Stay closely tied to what was just heard; don't add unrelated objects.\n\n"
    "Heard: \"{PHRASE}\""
).format(PHRASE=PHRASE)



LANGUAGE = "Russian"
PHRASE = "Залезай в ванночку." 
# PHRASE = "спокойной ночи"
# PHRASE = "Давай кушать"
SYSTEM = (
    "You are helping the caregiver who is talking to a small boy. " 
    "Everything you produce is in {LANGUAGE} and must be grammatically correct. "
    "Keep it warm and infant-directed: diminutives, simple vocabulary, sentences under 8 words. "
    "Reply with the phrases only — one per line, no numbering, no preamble. "
).format(LANGUAGE=LANGUAGE)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "still part of the same routine.\n\n"
    "Heard: \"{PHRASE}\""  
    ).format(PHRASE=PHRASE)


LANGUAGE = "Russian"
PHRASE = "Залезай в ванночку."
# PHRASE = "спокойной ночи"
# PHRASE = "Давай кушать"
FEW_SHOT_EXAMPLES = """
Heard: "Давай наденем твою пижамку."
подними ручки.
Вот твоя мягкая кроватка.
Теперь застегнем пуговки.

Heard: "Давай почитаем твою любимую книжку."
Какой книжкой ты хочешь сегодня читать.
Какая интересная сказка.
Посмотри на картинку.
"""

SYSTEM = (
    "You are helping the caregiver who is talking to a small boy. "
    "Everything you produce is in {LANGUAGE} and must be grammatically correct. "
    "Keep it warm and infant-directed: use diminutives/softened forms naturally, "
    "simple vocabulary, sentences under 8 words. "
    # "Use 'your', not 'my', when referring to the child's things. "
    # "Stay closely tied to what was just heard; don't add unrelated objects. "
    # "Mix speech and at least one physical action. "
    "Here are examples of the style and format expected:\n"
    "{EXAMPLES}\n"
    "Reply with the phrases only — one per line, no numbering, no preamble."
).format(LANGUAGE=LANGUAGE, EXAMPLES=FEW_SHOT_EXAMPLES)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "same routine. "
    # "same routine — mix of speech and at least one physical action. "
    # "Stay closely tied to what was just heard; don't add unrelated objects.\n\n"
    "Heard: \"{PHRASE}\""
).format(PHRASE=PHRASE)



LANGUAGE = "Russian"
# PHRASE = "Залезай в ванночку."
PHRASE = "спокойной ночи"
# PHRASE = "Давай кушать"

FEW_SHOT_EXAMPLES = """
Heard: "Давай наденем твою пижамку."
Подними ручки.
Вот твоя тёплая пижамка.
Теперь застегнем пуговки.

Heard: "Давай почитаем твою любимую книжку."
Какую книжку ты хочешь почитать?
Какая интересная сказка!
Посмотри на картинку.
"""

SYSTEM = (
    "You are helping the caregiver who is talking to a small boy. "
    "Everything you produce is in {LANGUAGE} and must be grammatically correct. "
    "Keep it warm and infant-directed: use diminutives/softened forms naturally, "
    "simple vocabulary, sentences under 8 words. "
    "Use 'your', not 'my', when referring to the child's things. "
    "Stay closely tied to what was just heard; don't add unrelated objects. "
    "Mix speech and at least one physical action. "
    "Here are examples of the style and format expected:\n"
    "{EXAMPLES}\n"
    "Reply with the phrases only — one per line, no numbering, no preamble."
).format(LANGUAGE=LANGUAGE, EXAMPLES=FEW_SHOT_EXAMPLES)

USER = (
    "Suggest 3 short things the caregiver might say or do right after this, "
    "same routine."
    # "same routine — mix of speech and at least one physical action. "
    # "Stay closely tied to what was just heard; don't add unrelated objects.\n\n"
    "Heard: \"{PHRASE}\""
).format(PHRASE=PHRASE)



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
