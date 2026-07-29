"""
Dense embedding layer for hybrid retrieval.

BARE-BONES NOTE:
This uses a deterministic character-ngram hashing vector instead of a real
sentence embedding model, so the whole stack runs offline with no model
download and no API key. It is NOT semantically meaningful -- it's a stand-in
that satisfies the same interface (text -> fixed-size vector, cosine similarity)
so the hybrid scoring and reranker code around it doesn't need to change later.

Swap-in path when you're ready:
  - Anthropic/OpenAI/Voyage embeddings API (network call, real semantics), or
  - a local sentence-transformers model once you have hardware, or
  - the SBERT model you already fine-tuned at Discovery Education.
Just replace `embed()` below; everything downstream (hybrid.py, reranker.py)
only depends on it returning a fixed-length vector.
"""
from __future__ import annotations

import hashlib
import math

VECTOR_DIM = 128


def _char_ngrams(text: str, n: int = 3) -> list[str]:
    text = text.lower().strip()
    if len(text) < n:
        return [text]
    return [text[i : i + n] for i in range(len(text) - n + 1)]


def embed(text: str) -> list[float]:
    """Deterministic pseudo-embedding. Replace with a real model/API call."""
    vec = [0.0] * VECTOR_DIM
    for gram in _char_ngrams(text):
        h = int(hashlib.md5(gram.encode("utf-8")).hexdigest(), 16)
        idx = h % VECTOR_DIM
        sign = 1.0 if (h // VECTOR_DIM) % 2 == 0 else -1.0
        vec[idx] += sign
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def cosine_sim(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))
