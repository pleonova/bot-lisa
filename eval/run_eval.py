"""
Offline retrieval eval harness.

Computes NDCG@k, MRR, and precision@k for three retrieval strategies:
  - bm25_only    (lexical baseline)
  - hybrid       (BM25 + embedding, see services/retrieval_service/hybrid.py)
  - ltr          (hybrid candidates reranked by the trained LTR model)

This is the piece that turns "the phrase library constrains the LLM" from a
gut feeling into a measured, named-metric result -- run this after any change
to retrieval/reranking to see whether it actually helped.

Usage:
    python -m eval.run_eval
"""
from __future__ import annotations

import json
import math
from pathlib import Path

from services.common.config import ROOT
from services.retrieval_service.bm25_index import BM25Index
from services.retrieval_service.hybrid import HybridRetriever
from services.retrieval_service.reranker import Reranker

EVAL_SET_PATH = ROOT / "eval" / "labeled_eval_set.json"
K = 5


def dcg(relevances: list[int]) -> float:
    return sum(rel / math.log2(i + 2) for i, rel in enumerate(relevances))


def ndcg_at_k(ranked_ids: list[str], relevant_ids: set[str], k: int) -> float:
    relevances = [1 if pid in relevant_ids else 0 for pid in ranked_ids[:k]]
    ideal = sorted(relevances, reverse=True)
    idcg = dcg(ideal)
    return dcg(relevances) / idcg if idcg > 0 else 0.0


def mrr(ranked_ids: list[str], relevant_ids: set[str]) -> float:
    for i, pid in enumerate(ranked_ids):
        if pid in relevant_ids:
            return 1.0 / (i + 1)
    return 0.0


def precision_at_k(ranked_ids: list[str], relevant_ids: set[str], k: int) -> float:
    top_k = ranked_ids[:k]
    if not top_k:
        return 0.0
    return sum(1 for pid in top_k if pid in relevant_ids) / len(top_k)


def evaluate(strategy_name: str, get_ranked_ids, eval_set: list[dict]) -> dict:
    ndcgs, mrrs, precisions = [], [], []
    for example in eval_set:
        relevant = set(example["relevant_phrase_ids"])
        ranked_ids = get_ranked_ids(example)
        ndcgs.append(ndcg_at_k(ranked_ids, relevant, K))
        mrrs.append(mrr(ranked_ids, relevant))
        precisions.append(precision_at_k(ranked_ids, relevant, K))
    n = len(eval_set)
    return {
        "strategy": strategy_name,
        f"ndcg@{K}": round(sum(ndcgs) / n, 3),
        "mrr": round(sum(mrrs) / n, 3),
        f"precision@{K}": round(sum(precisions) / n, 3),
    }


def main():
    eval_set = json.loads(EVAL_SET_PATH.read_text(encoding="utf-8"))

    bm25_index = BM25Index()
    hybrid_retriever = HybridRetriever(bm25_index)

    reranker = Reranker()
    reranker.train(hybrid_retriever)

    def bm25_ranked_ids(example):
        results = bm25_index.search(example["query"], top_k=10)
        return [phrase["id"] for phrase, _score in results]

    def hybrid_ranked_ids(example):
        results = hybrid_retriever.search(example["query"], top_k=10)
        return [r["id"] for r in results]

    def ltr_ranked_ids(example):
        candidates = hybrid_retriever.search(example["query"], top_k=10)
        reranked = reranker.rerank(candidates, {"routine": example.get("routine")})
        return [r["id"] for r in reranked]

    results = [
        evaluate("bm25_only", bm25_ranked_ids, eval_set),
        evaluate("hybrid", hybrid_ranked_ids, eval_set),
        evaluate("ltr_reranked", ltr_ranked_ids, eval_set),
    ]

    print(f"{'strategy':<14}{'ndcg@'+str(K):<10}{'mrr':<8}{'precision@'+str(K):<12}")
    for r in results:
        print(f"{r['strategy']:<14}{r[f'ndcg@{K}']:<10}{r['mrr']:<8}{r[f'precision@{K}']:<12}")


if __name__ == "__main__":
    main()
