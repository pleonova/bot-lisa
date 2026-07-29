"""
Hybrid retrieval: combine BM25 (lexical) + embedding cosine similarity (dense)
into one ranked candidate list. This is the "vector search / dense-sparse /
hybrid retrieval" piece of the JD.

Both raw score scales differ (BM25 is unbounded, cosine is [-1, 1]), so scores
are min-max normalized within each result set before blending -- a standard
hybrid-search pattern, worth naming explicitly if this comes up in interview.
"""
from __future__ import annotations

from services.common.config import BM25_WEIGHT, EMBED_WEIGHT
from services.retrieval_service.bm25_index import BM25Index, _tokenize
from services.retrieval_service.embeddings import cosine_sim, embed


def _normalize(scores: list[float]) -> list[float]:
    if not scores:
        return scores
    lo, hi = min(scores), max(scores)
    if hi - lo < 1e-9:
        return [0.0 for _ in scores]
    return [(s - lo) / (hi - lo) for s in scores]


class HybridRetriever:
    def __init__(self, bm25_index: BM25Index):
        self.bm25_index = bm25_index
        # precompute phrase embeddings once; recompute on reload()
        self._phrase_embeddings = [
            embed(f"{p['ru']} {p['gloss_en']}") for p in self.bm25_index.phrases
        ]

    def reload(self) -> None:
        self.bm25_index.reload()
        self._phrase_embeddings = [
            embed(f"{p['ru']} {p['gloss_en']}") for p in self.bm25_index.phrases
        ]

    def search(self, query: str, top_k: int = 5) -> list[dict]:
        phrases = self.bm25_index.phrases

        bm25_scores = self.bm25_index._bm25.get_scores(_tokenize(query))
        query_vec = embed(query)
        embed_scores = [cosine_sim(query_vec, pe) for pe in self._phrase_embeddings]

        bm25_norm = _normalize(list(bm25_scores))
        embed_norm = _normalize(embed_scores)

        results = []
        for i, phrase in enumerate(phrases):
            hybrid_score = BM25_WEIGHT * bm25_norm[i] + EMBED_WEIGHT * embed_norm[i]
            results.append(
                {
                    **phrase,
                    "bm25_score": bm25_scores[i],
                    "embed_score": embed_scores[i],
                    "hybrid_score": hybrid_score,
                }
            )
        results.sort(key=lambda r: r["hybrid_score"], reverse=True)
        return results[:top_k]
