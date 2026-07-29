from fastapi.testclient import TestClient

from services.retrieval_service.bm25_index import BM25Index
from services.retrieval_service.hybrid import HybridRetriever
from services.retrieval_service.main import app


def test_bm25_index_loads_phrase_library():
    index = BM25Index()
    assert len(index.phrases) > 0
    assert all("ru" in p and "id" in p for p in index.phrases)


def test_bm25_search_returns_relevant_top_result():
    index = BM25Index()
    results = index.search("спать", top_k=3)
    top_phrase, _score = results[0]
    assert top_phrase["routine"] == "sleep"


def test_hybrid_search_returns_ranked_candidates():
    index = BM25Index()
    retriever = HybridRetriever(index)
    results = retriever.search("кушать", top_k=3)
    assert len(results) == 3
    scores = [r["hybrid_score"] for r in results]
    assert scores == sorted(scores, reverse=True)


def test_retrieval_service_health_endpoint():
    client = TestClient(app)
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_retrieval_service_search_endpoint():
    client = TestClient(app)
    resp = client.post("/search", json={"query": "спать", "routine": "sleep", "top_k": 2})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["results"]) == 2
    assert body["latency_ms"] >= 0
