"""
Retrieval microservice.

Owns: phrase library, BM25 index, hybrid scoring, LTR reranking.
Exposes: POST /search -> ranked phrase candidates for a query + context.

Run: uvicorn services.retrieval_service.main:app --port 8001 --reload
"""
from __future__ import annotations

import logging
import time

from fastapi import FastAPI
from pydantic import BaseModel

from services.retrieval_service.bm25_index import BM25Index
from services.retrieval_service.hybrid import HybridRetriever
from services.retrieval_service.reranker import Reranker

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("retrieval_service")

app = FastAPI(title="retrieval-service")

bm25_index = BM25Index()
retriever = HybridRetriever(bm25_index)
reranker = Reranker()
try:
    reranker.train(retriever)
    logger.info("LTR reranker trained on startup.")
except ValueError:
    logger.warning("Not enough labeled data to train LTR reranker; falling back to hybrid order.")


class SearchRequest(BaseModel):
    query: str
    routine: str | None = None
    top_k: int = 5
    use_reranker: bool = True


class SearchResponse(BaseModel):
    query: str
    results: list[dict]
    latency_ms: float


@app.post("/search", response_model=SearchResponse)
def search(req: SearchRequest) -> SearchResponse:
    start = time.perf_counter()
    candidates = retriever.search(req.query, top_k=max(req.top_k, 10))
    if req.use_reranker:
        candidates = reranker.rerank(candidates, {"routine": req.routine})
    results = candidates[: req.top_k]
    latency_ms = (time.perf_counter() - start) * 1000

    # basic observability -- swap for structured logging / metrics export later
    logger.info("search query=%r routine=%r latency_ms=%.2f", req.query, req.routine, latency_ms)

    return SearchResponse(query=req.query, results=results, latency_ms=latency_ms)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "phrase_count": len(bm25_index.phrases)}
