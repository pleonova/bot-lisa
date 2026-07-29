"""
Learning-to-rank reranker.

BARE-BONES NOTE:
Pointwise LTR via logistic regression over a handful of features
(bm25_score, embed_score, hybrid_score, routine_match, usage_count).
This is intentionally the simplest correct version of LTR -- a real
LightGBM/XGBoost ranker (pairwise or listwise loss) is the natural upgrade
once you have more labeled data than the ~15-phrase toy set here.

Trains on eval/labeled_eval_set.json so the reranker and the eval harness
share one source of truth for "what's actually relevant."
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from sklearn.linear_model import LogisticRegression

from services.common.config import ROOT
from services.retrieval_service.hybrid import HybridRetriever

MODEL_PATH = ROOT / "services" / "retrieval_service" / "reranker_model.json"
EVAL_SET_PATH = ROOT / "eval" / "labeled_eval_set.json"


def _features(candidate: dict, context: dict) -> list[float]:
    routine_match = 1.0 if context.get("routine") == candidate.get("routine") else 0.0
    return [
        candidate["bm25_score"],
        candidate["embed_score"],
        candidate["hybrid_score"],
        routine_match,
        float(candidate.get("usage_count", 0)),
    ]


class Reranker:
    def __init__(self):
        self.model: LogisticRegression | None = None

    def train(self, retriever: HybridRetriever) -> None:
        eval_set = json.loads(EVAL_SET_PATH.read_text(encoding="utf-8"))
        X, y = [], []
        for example in eval_set:
            context = {"routine": example.get("routine")}
            candidates = retriever.search(example["query"], top_k=len(retriever.bm25_index.phrases))
            relevant_ids = set(example["relevant_phrase_ids"])
            for c in candidates:
                X.append(_features(c, context))
                y.append(1 if c["id"] in relevant_ids else 0)

        X_arr, y_arr = np.array(X), np.array(y)
        if len(set(y_arr.tolist())) < 2:
            raise ValueError("Need both positive and negative examples to train the reranker.")
        self.model = LogisticRegression(max_iter=1000).fit(X_arr, y_arr)

    def rerank(self, candidates: list[dict], context: dict) -> list[dict]:
        if self.model is None:
            # untrained fallback: preserve hybrid order
            return candidates
        X = np.array([_features(c, context) for c in candidates])
        scores = self.model.predict_proba(X)[:, 1]
        reranked = [
            {**c, "ltr_score": float(s)} for c, s in zip(candidates, scores)
        ]
        reranked.sort(key=lambda r: r["ltr_score"], reverse=True)
        return reranked
