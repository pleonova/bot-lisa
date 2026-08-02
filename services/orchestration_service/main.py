"""
Orchestration microservice.

Owns: calling retrieval-service for grounding phrases, then the LLM ask layer.
This is the service a real "perception event -> spoken response" request flows
through last, so it's also where end-to-end request latency is measured.

Run: uvicorn services.orchestration_service.main:app --port 8002 --reload
Requires retrieval-service running on RETRIEVAL_SERVICE_URL (default :8001).
"""
from __future__ import annotations

import logging
import re
import time

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from services.common.config import RETRIEVAL_SERVICE_URL
from services.orchestration_service.llm_client import generate, translate

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("orchestration_service")

app = FastAPI(title="orchestration-service")


class AskRequest(BaseModel):
    transcript: str  # what the caregiver/child said, or a context description
    routine: str | None = None


class AskResponse(BaseModel):
    ru: str
    mode: str
    grounding_phrases: list[str]
    latency_ms: float


@app.post("/ask", response_model=AskResponse)
def ask(req: AskRequest) -> AskResponse:
    start = time.perf_counter()

    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.post(
                f"{RETRIEVAL_SERVICE_URL}/search",
                json={"query": req.transcript, "routine": req.routine, "top_k": 3},
            )
            resp.raise_for_status()
            candidates = resp.json()["results"]
    except httpx.HTTPError as e:
        logger.error("retrieval-service call failed: %s", e)
        raise HTTPException(status_code=502, detail="retrieval-service unavailable") from e

    generation = generate(req.transcript, candidates)
    latency_ms = (time.perf_counter() - start) * 1000

    logger.info(
        "ask transcript=%r routine=%r mode=%s latency_ms=%.2f",
        req.transcript, req.routine, generation["mode"], latency_ms,
    )

    return AskResponse(
        ru=generation["ru"],
        mode=generation["mode"],
        grounding_phrases=[c["ru"] for c in candidates],
        latency_ms=latency_ms,
    )


_CYRILLIC_RE = re.compile(r"[а-яА-ЯёЁ]")
_WORD_RE = re.compile(r"[a-zA-Zа-яА-ЯёЁ]+")
_STOPWORDS = {
    "a", "an", "the", "to", "is", "are", "do", "does", "you", "your", "i",
    "it", "in", "on", "at", "of", "for", "my", "me", "little",
}


def _has_cyrillic(text: str) -> bool:
    return bool(_CYRILLIC_RE.search(text))


def _content_words(text: str) -> set[str]:
    return {w for w in _WORD_RE.findall(text.lower()) if w not in _STOPWORDS}


class Phrase(BaseModel):
    ru: str
    gloss_en: str


class AssistRequest(BaseModel):
    text: str  # English word/phrase to translate, or a Russian phrase to expand on


class AssistResponse(BaseModel):
    mode: str  # "translate" (input was English) or "expand" (input was Russian)
    source: str | None = None  # translate mode only: "curated" | "live" | "mock"
    input: str
    translation: Phrase | None = None
    related: list[Phrase]
    latency_ms: float


@app.post("/assist", response_model=AssistResponse)
def assist(req: AssistRequest) -> AssistResponse:
    """
    Caregiver-facing helper (distinct from /ask, which is the child-directed
    perception-event flow). One input box, auto-detected:

    - English in -> "translate" mode. Checks the curated phrase library first
      (word-overlap against gloss_en); only falls back to a live/mock LLM
      translation when nothing in the library is a good match. This mirrors
      the project's core principle that hand-vetted phrases should win over
      fresh generations.
    - Russian in -> "expand" mode. Returns nearby phrases from the library so
      the caregiver can grow their own active vocabulary around what they
      just said, rather than getting a single next-line suggestion.
    """
    start = time.perf_counter()
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="text must not be empty")

    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.post(
                f"{RETRIEVAL_SERVICE_URL}/search",
                json={"query": text, "top_k": 5},
            )
            resp.raise_for_status()
            candidates = resp.json()["results"]
    except httpx.HTTPError as e:
        logger.error("retrieval-service call failed: %s", e)
        raise HTTPException(status_code=502, detail="retrieval-service unavailable") from e

    mode = "expand" if _has_cyrillic(text) else "translate"

    if mode == "expand":
        related = [Phrase(ru=c["ru"], gloss_en=c["gloss_en"]) for c in candidates]
        latency_ms = (time.perf_counter() - start) * 1000
        logger.info("assist mode=expand input=%r latency_ms=%.2f", text, latency_ms)
        return AssistResponse(mode=mode, input=text, related=related, latency_ms=latency_ms)

    # translate mode
    top = candidates[0] if candidates else None
    is_curated_match = bool(top and _content_words(text) & _content_words(top["gloss_en"]))

    if is_curated_match:
        translation = Phrase(ru=top["ru"], gloss_en=top["gloss_en"])
        source = "curated"
    else:
        generation = translate(text, candidates)
        translation = Phrase(ru=generation["ru"], gloss_en=text)
        source = generation["mode"]

    related = [Phrase(ru=c["ru"], gloss_en=c["gloss_en"]) for c in candidates if c is not top][:4]
    latency_ms = (time.perf_counter() - start) * 1000
    logger.info(
        "assist mode=translate input=%r source=%s latency_ms=%.2f",
        text, source, latency_ms,
    )
    return AssistResponse(
        mode=mode, source=source, input=text, translation=translation, related=related, latency_ms=latency_ms,
    )


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}
