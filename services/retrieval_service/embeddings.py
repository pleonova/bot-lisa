"""
Dense embedding layer for hybrid retrieval.

Real embeddings via fastembed (https://github.com/qdrant/fastembed,
Apache-2.0), using the open sentence-transformers/paraphrase-multilingual-
MiniLM-L12-v2 model -- handles English, Russian, Hindi, Spanish, French,
German and ~45 other languages, which is what hybrid.py needs (comparing an
English query against phrase text in whichever target language is
configured). fastembed runs on ONNX Runtime instead of PyTorch, which
matters on this project's small droplet: no multi-gigabyte PyTorch install,
just the ONNX runtime plus a ~220MB model file, loaded once at process
startup rather than per request.

Note: the original plan here was intfloat/multilingual-e5-small, but that
model isn't in the list fastembed's installed version actually supports
(confirmed by running TextEmbedding.list_supported_models() against the
pinned fastembed>=0.4 -- it raises ValueError at import time otherwise).
paraphrase-multilingual-MiniLM-L12-v2 is the closest available match: same
384-dim output, similar size, broader language coverage than e5-small
needed. Unlike e5, it's a symmetric sentence-transformers model, so it does
not use "query: "/"passage: " prefixes -- raw text goes in directly.

Chosen over a hosted embeddings API (OpenAI, Voyage) because open source is
the preferred default for this project -- see the project roadmap's
decisions log. Everything downstream (hybrid.py, reranker.py) only depends
on embed() returning a fixed-length vector, same as the placeholder this
replaced, so nothing else needed to change.
"""
from __future__ import annotations

import math

from fastembed import TextEmbedding

VECTOR_DIM = 384  # paraphrase-multilingual-MiniLM-L12-v2's output dimension

# Loaded once per process -- retrieval-service is a single long-running
# FastAPI app, so this is a one-time ~220MB model load at startup, not
# something that happens per request.
_model = TextEmbedding(model_name="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")


def embed(text: str, is_query: bool = False) -> list[float]:
    """Real sentence embedding via paraphrase-multilingual-MiniLM-L12-v2.

    is_query is kept in the signature so callers (hybrid.py) don't need to
    change if a future model swap needs the distinction again, but this
    model is symmetric and doesn't treat queries and passages differently.
    """
    # fastembed's embed() yields a generator over a batch; we always pass
    # exactly one string, so take the single result.
    vector = next(_model.embed([text]))
    return vector.tolist()


# Cosine similarity: how closely two vectors point in the same direction,
# ignoring their magnitude. The standard way to compare dense embeddings,
# since embedding magnitude doesn't carry meaning here, only direction does.
def cosine_sim(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a)) or 1.0
    norm_b = math.sqrt(sum(y * y for y in b)) or 1.0
    return dot / (norm_a * norm_b)
