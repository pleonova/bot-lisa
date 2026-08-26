# Assistant Language Bot (Version 1: Russian)

## Why am I building this app

Before I moved to the US, the first English word I learned in school was fox (don't ask me why). Now that I have a baby, I want to teach them Russian, but my vocabulary isn't big enough to do it on my own, so I'm building an app to help. I named it Lisa (лиса), which means fox in Russian. Also, my baby's nursery theme just so happens to be foxes.

A little bit more about me: I grew up speaking Russian at home, but I never formally studied it, so somewhere along the way it turned into a comfortable hybrid I'd call Runglish. I understand conversational Russian just fine, I just don't always have the word I need on hand when I need it. Lisa is my way of closing that gap. When a word or phrase won't come to me, it translates from English to Russian. And based on either the Russian or English phrase, it recommends related phrases so I can keep expanding my vocabulary and hopefully my child's too.

## The technical part

Under the hood: it routes English text to translate-mode and Russian text to expand-mode.

A runnable skeleton covering all six build layers. Everything here works with
**zero external dependencies** (no API keys, no cloud account, no Docker
required to try the core logic) — each piece is a real, working minimal
version meant to be expanded, not a mockup.

## What's real vs. a placeholder

| Piece | Status |
|---|---|
| Phrase library (15 seed phrases) | Real, but tiny — expand this first |
| BM25 lexical retrieval | Real (`rank_bm25`) |
| Dense embeddings | Real (`fastembed`, open-source, ONNX-based, running `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` locally) with a minimum-similarity threshold in `hybrid.py`. See `services/retrieval_service/embeddings.py` |
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
android/                          # minimal Kotlin/Compose front end, see android/README.md
.github/workflows/ci.yml
```

## Suggested expansion order

1. **Grow the phrase library** (15 → 75+ phrases per the original plan) with your collaborator.
2. ~~Swap in real embeddings in `embeddings.py`~~ — done, see the status table above. Next highest-value step here: grow `eval/labeled_eval_set.json` past 8 examples so the similarity threshold and reranker have more to be tuned against.
3. **Grow the labeled eval set** past 8 examples — the LTR reranker and eval numbers both get more trustworthy with more data.
4. **Wire up live LLM mode** — set `ANTHROPIC_API_KEY` and sanity-check `services/orchestration_service/llm_client.py`'s system prompt against real generations.
5. **Add a Java (or Scala) retrieval hot-path service** — the retrieval service's `/search` endpoint is the natural candidate, since it's the latency-sensitive piece.
6. **Swap the HTTP calls between services for a real queue** (Kafka or Redis Streams) using the `EventBus` interface in `events.py`.
7. **Try the k8s manifests against k3d/minikube**, then fill in `terraform/main.tf` once you pick a cloud.

For bigger, standalone feature bets beyond this scaffold-filling list — like
the hands-free "Lisa Assistant" listening mode — see
[ROADMAP.md](ROADMAP.md).

## Paused features

Things that are built, tested, and working, but not currently wired up to
anything actively used — kept here so they don't get lost or accidentally
re-discovered as "is this broken?" later.

### Child-perception voice pipeline

**What it is:** the original flow — `ingestion-service`'s `POST /event/voice`
receives a transcript (something the child/caregiver said near the device),
wraps it in a `PerceptionEvent`, and forwards it to `orchestration-service`'s
`POST /ask`, which returns a single grounded Russian phrase to speak back.

**Status:** code is untouched and still works — covered by `tests/`, runs
fine standalone via `uvicorn services.ingestion_service.main:app --port 8003`.

**Why paused:** the Android app was repurposed to be caregiver-facing instead
(translate English → Russian, or expand on a Russian phrase with related
ones from the library, via the new `POST /assist` endpoint on
`orchestration-service`). The app no longer calls `/event/voice` or `/ask`.

**Revisit when:** the caregiver-assist flow is solid and there's appetite to
build the child-directed side back in — natural to pair with finishing the
still-stubbed `POST /event/vision` endpoint (camera/book recognition), since
both are pieces of the same "perception event" concept.

## Known issues

### `ORCHESTRATION_API_KEY` still travels over plain HTTP

`/ask` and `/assist` are exposed via a DigitalOcean LoadBalancer (see
`infra/k8s/orchestration-service.yaml`) and guarded by an `X-API-Key` header
(see `require_api_key` in `services/orchestration_service/main.py`), but the
Android app talks to it over plain `http://`, not `https://` — so the key
itself travels in the clear and could be sniffed on an untrusted network.

**Why it's not fixed yet:** a real TLS certificate (e.g. DigitalOcean's free
Let's Encrypt integration on the LoadBalancer) needs a domain name pointed
at the LoadBalancer's IP — Let's Encrypt can't issue a cert for a bare IP,
and this project doesn't have a domain/DNS set up yet.

**TODO:** decide on a domain + DNS setup, add a DigitalOcean-managed cert to
the `orchestration-service` LoadBalancer, and switch the Android app's
server URL to `https://`.
