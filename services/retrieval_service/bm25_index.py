"""
BM25 lexical retrieval over the phrase library.

Real BM25 via rank_bm25, tokenizing on the Russian gloss + tags + English gloss
so a query like "sleep" or "спать" pulls the right phrases by exact/keyword
match -- the piece that's often better than pure embedding similarity for
short, fixed infant-register phrases.

Swap-in path: once phrase count grows past a few hundred, move this index into
Elasticsearch (you already have ES experience) and keep this same `search()`
interface so hybrid.py doesn't need to change.
"""
from __future__ import annotations

import json
from pathlib import Path

from rank_bm25 import BM25Okapi

from services.common.config import PHRASE_LIBRARY_PATH


def _tokenize(text: str) -> list[str]:
    # Strip punctuation and lowercase so "sleep?" and "Sleep" hash to the same
    # token -- BM25 matches on exact tokens, so normalization happens here.
    for ch in "!?.,;:\"'":
        text = text.replace(ch, "")
    return text.lower().split()


class BM25Index:
    def __init__(self, phrase_library_path: Path = PHRASE_LIBRARY_PATH):
        self.phrases: list[dict] = json.loads(Path(phrase_library_path).read_text(encoding="utf-8"))
        # Index Russian gloss + English gloss + tags together per phrase, so a
        # query in either language (or a tag word) can match the same entry.
        self._corpus_tokens = [
            _tokenize(f"{p['ru']} {p['gloss_en']} {' '.join(p['tags'])}") for p in self.phrases
        ]
        self._bm25 = BM25Okapi(self._corpus_tokens)

    def search(self, query: str, top_k: int = 10) -> list[tuple[dict, float]]:
        # One BM25 score per phrase (0 if no token overlap at all), paired
        # back up with its phrase dict and sorted best-first.
        scores = self._bm25.get_scores(_tokenize(query))
        ranked = sorted(zip(self.phrases, scores), key=lambda x: x[1], reverse=True)
        return ranked[:top_k]

    def reload(self) -> None:
        # Re-reads phrase_library/phrases.json from disk and rebuilds the
        # index, so edits to the library take effect without restarting the
        # service.
        self.__init__()
