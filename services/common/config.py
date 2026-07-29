import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHRASE_LIBRARY_PATH = ROOT / "phrase_library" / "phrases.json"

RETRIEVAL_SERVICE_URL = os.environ.get("RETRIEVAL_SERVICE_URL", "http://localhost:8001")
ORCHESTRATION_SERVICE_URL = os.environ.get("ORCHESTRATION_SERVICE_URL", "http://localhost:8002")
INGESTION_SERVICE_URL = os.environ.get("INGESTION_SERVICE_URL", "http://localhost:8003")

ANTHROPIC_API_KEY = os.environ.get("ANTHROPIC_API_KEY")  # if unset, orchestration runs in mock mode

# Hybrid retrieval weights -- tune once you have eval numbers, see eval/run_eval.py
BM25_WEIGHT = float(os.environ.get("BM25_WEIGHT", "0.5"))
EMBED_WEIGHT = float(os.environ.get("EMBED_WEIGHT", "0.5"))
