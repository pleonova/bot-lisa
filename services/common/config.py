import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHRASE_LIBRARY_PATH = ROOT / "phrase_library" / "phrases.json"

RETRIEVAL_SERVICE_URL = os.environ.get("RETRIEVAL_SERVICE_URL", "http://localhost:8001")
ORCHESTRATION_SERVICE_URL = os.environ.get("ORCHESTRATION_SERVICE_URL", "http://localhost:8002")
INGESTION_SERVICE_URL = os.environ.get("INGESTION_SERVICE_URL", "http://localhost:8003")

ANTHROPIC_API_KEY = os.environ.get("ANTHROPIC_API_KEY")  # if unset, orchestration runs in mock mode

# If unset, ingestion-service accepts requests with no auth (local dev default).
# If set, callers must send a matching X-API-Key header on /event/* endpoints.
INGESTION_API_KEY = os.environ.get("INGESTION_API_KEY")

# Same pattern for orchestration-service, which is also exposed publicly (the
# Android app's /assist calls hit it directly, not through ingestion-service).
ORCHESTRATION_API_KEY = os.environ.get("ORCHESTRATION_API_KEY")

# Hybrid retrieval weights -- tune once you have eval numbers, see eval/run_eval.py
BM25_WEIGHT = float(os.environ.get("BM25_WEIGHT", "0.5"))
EMBED_WEIGHT = float(os.environ.get("EMBED_WEIGHT", "0.5"))

# Raw cosine-similarity floor (0..1) for a candidate to count as a real
# semantic match -- see hybrid.py's search(). A candidate below this AND
# with zero BM25 lexical overlap gets dropped instead of always filling out
# top_k regardless of relevance. Starting value, not a tuned one; revisit
# against eval/labeled_eval_set.json as the phrase library grows.
MIN_EMBED_SIMILARITY = float(os.environ.get("MIN_EMBED_SIMILARITY", "0.35"))
