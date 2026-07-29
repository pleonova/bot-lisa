# Infant Russian Language Bot — Bare-Bones Scaffold

A runnable skeleton covering all six build layers. Everything here works with
**zero external dependencies** (no API keys, no cloud account, no Docker
required to try the core logic) — each piece is a real, working minimal
version meant to be expanded, not a mockup.

## What's real vs. a placeholder

| Piece | Status |
|---|---|
| Phrase library (15 seed phrases) | Real, but tiny — expand this first |
| BM25 lexical retrieval | Real (`rank_bm25`) |
| Dense embeddings | **Placeholder** — deterministic hash-based vector, not semantically meaningful. See `services/retrieval_service/embeddings.py` for the swap-in path (Anthropic/OpenAI/Voyage embeddings API, or your fine-tuned SBERT model) |
| Hybrid BM25+embedding scoring | Real, working, tunable via `BM25_WEIGHT`/`EMBED_WEIGHT` env vars |
| Eval harness (NDCG, MRR, precision@k) | Real, run it: `python -m eval.run_eval` |
| LTR reranker | Real (logistic regression) — upgrade path to LightGBM noted in `reranker.py` |
| LLM ask layer | Real, runs in **mock mode** (no API key) by default; set `ANTHROPIC_API_KEY` for live generation |
| Perception event bus | Real in-memory pub/sub interface; services currently talk over HTTP directly — see `services/common/events.py` for the Kafka/Redis Streams swap-in point |
| Microservices split | Real — three independently runnable FastAPI services |
| Docker / docker-compose | Real, runs locally |
| Kubernetes manifests | **Scaffold** — correct shape, untested against a real cluster (try k3d/minikube first) |
| Terraform | **Scaffold** — documents intended resources, no provider wired up yet |
| CI (GitHub Actions) | Real workflow, runs tests + eval on every push |

## Quickstart

```bash
pip install -r requirements.txt

# 1. Run the eval harness (no servers needed)
PYTHONPATH=. python -m eval.run_eval

# 2. Run tests
PYTHONPATH=. pytest tests/ -v

# 3. Run all three services locally
PYTHONPATH=. uvicorn services.retrieval_service.main:app --port 8001 &
PYTHONPATH=. uvicorn services.orchestration_service.main:app --port 8002 &
PYTHONPATH=. uvicorn services.ingestion_service.main:app --port 8003 &

curl -X POST http://localhost:8003/event/voice \
  -H "Content-Type: application/json" \
  -d '{"transcript": "малыш хочет спать", "routine_hint": "sleep"}'

# 4. Or via Docker Compose
docker compose -f infra/docker-compose.yml up --build
```

## Directory map

```
phrase_library/phrases.json       # seed content — expand this with your collaborator first
services/
  common/
    config.py                     # shared env/config
    events.py                     # PerceptionEvent schema + in-memory bus (Kafka/Redis swap point)
  retrieval_service/               # owns BM25 + hybrid + LTR retrieval
    bm25_index.py
    embeddings.py                 # <- swap in real embeddings here
    hybrid.py
    reranker.py
    main.py                       # FastAPI app, port 8001
  orchestration_service/
    llm_client.py                 # mock mode / live Claude API
    main.py                       # FastAPI app, port 8002
  ingestion_service/
    main.py                       # FastAPI app, port 8003 — voice/vision event entry point
eval/
  labeled_eval_set.json           # shared ground truth for reranker training + eval
  run_eval.py                     # NDCG/MRR/precision@k across bm25_only / hybrid / ltr_reranked
infra/
  Dockerfile
  docker-compose.yml
  k8s/                            # scaffold manifests, untested against a real cluster
  terraform/                      # scaffold, no provider configured
tests/
  test_retrieval.py
.github/workflows/ci.yml
```

## Suggested expansion order

1. **Grow the phrase library** (15 → 75+ phrases per the original plan) with your collaborator.
2. **Swap in real embeddings** in `embeddings.py` — this is the highest-value single change, since the hybrid/LTR/eval code around it doesn't need to change at all.
3. **Grow the labeled eval set** past 8 examples — the LTR reranker and eval numbers both get more trustworthy with more data.
4. **Wire up live LLM mode** — set `ANTHROPIC_API_KEY` and sanity-check `services/orchestration_service/llm_client.py`'s system prompt against real generations.
5. **Add a Java (or Scala) retrieval hot-path service** once you want to speak directly to the JD's "proficient in Java/Scala/C++" line — the retrieval service's `/search` endpoint is the natural candidate, since it's the latency-sensitive piece.
6. **Swap the HTTP calls between services for a real queue** (Kafka or Redis Streams) using the `EventBus` interface in `events.py`.
7. **Try the k8s manifests against k3d/minikube**, then fill in `terraform/main.tf` once you pick a cloud.
