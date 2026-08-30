"""
Orchestration microservice.

Owns: calling retrieval-service for grounding phrases, then the LLM ask layer.
This is the service a real "perception event -> spoken response" request flows
through last, so it's also where end-to-end request latency is measured.

Run: uvicorn services.orchestration_service.main:app --port 8002 --reload
Requires retrieval-service running on RETRIEVAL_SERVICE_URL (default :8001).

AUTH NOTE:
This is exposed publicly too (see infra/k8s/orchestration-service.yaml, type:
LoadBalancer) since the Android app's /assist calls hit it directly, bypassing
ingestion-service. Same shared-secret X-API-Key pattern as ingestion-service,
via a separate ORCHESTRATION_API_KEY.
"""
from __future__ import annotations

import hmac
import logging
import re
import time

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address

from services.common.config import MIN_EMBED_SIMILARITY, ORCHESTRATION_API_KEY, RETRIEVAL_SERVICE_URL
from services.orchestration_service.llm_client import generate, translate

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("orchestration_service")

if not ORCHESTRATION_API_KEY:
    # Not fatal -- local dev intentionally runs with no key set. But this
    # service is also reachable from the public internet (LoadBalancer), so
    # silently running unauthenticated there would be easy to miss. Make it
    # loud instead: if you see this line in the deployed cluster's logs, the
    # bot-lisa-secrets Secret is missing/misnamed and /ask + /assist are
    # currently open to anyone.
    logger.warning(
        "ORCHESTRATION_API_KEY is not set -- /ask and /assist are running "
        "WITHOUT authentication. Expected for local dev; if this is the "
        "deployed cluster, fix the bot-lisa-secrets Secret immediately."
    )

app = FastAPI(title="orchestration-service")

# Per-IP rate limit on /ask and /assist. Two reasons: this service is
# reachable from the public internet (LoadBalancer), so it needs some limit
# on plain request volume; and it also slows down anyone trying to brute-
# force ORCHESTRATION_API_KEY by guessing, since each guess now costs them
# wait time instead of being free.
#
# NOTE: get_remote_address keys off the TCP peer IP. Behind the DigitalOcean
# LoadBalancer that's currently the real client IP (no reverse proxy in
# front rewriting it), but if a proxy/ingress is ever added upstream of
# this service, switch the key_func to read X-Forwarded-For instead, or
# every caller will collapse onto one shared limit.
limiter = Limiter(key_func=get_remote_address)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)


def require_api_key(x_api_key: str | None = Header(default=None)) -> None:
    """Guards /ask and /assist. No-op if ORCHESTRATION_API_KEY isn't set (local
    dev default). Once set (e.g. via the bot-lisa-secrets k8s Secret), callers
    must send a matching X-API-Key header or get a 401.
    """
    if not ORCHESTRATION_API_KEY:
        return
    if not x_api_key or not hmac.compare_digest(x_api_key, ORCHESTRATION_API_KEY):
        raise HTTPException(status_code=401, detail="missing or invalid X-API-Key")


class AskRequest(BaseModel):
    transcript: str  # what the caregiver/child said, or a context description
    routine: str | None = None


class AskResponse(BaseModel):
    ru: str
    mode: str
    grounding_phrases: list[str]
    latency_ms: float


@app.post("/ask", response_model=AskResponse, dependencies=[Depends(require_api_key)])
@limiter.limit("30/minute")
def ask(request: Request, req: AskRequest) -> AskResponse:
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


def _round_score(x: float | None) -> float | None:
    """Trim retrieval scores to 3 decimal places for the API response --
    full float precision (e.g. 0.5995388792128106) is noise for a human
    reading the response, not meaningfully more informative than 0.6."""
    return round(x, 3) if x is not None else None


def _content_words(text: str) -> set[str]:
    return {w for w in _WORD_RE.findall(text.lower()) if w not in _STOPWORDS}


# A curated phrase only overrides a live translation when the caregiver's
# English input essentially *is* that phrase -- not merely shares one word
# with it. Measured as the Jaccard overlap of the two content-word sets:
# "good night" vs "Good night." scores 1.0 (snap to curated), but "time"
# vs "Time to sleep, little one." scores 0.33 and must be translated live.
CURATED_MATCH_MIN_OVERLAP = 0.6


def _curated_overlap(text: str, gloss: str) -> float:
    a = _content_words(text)
    b = _content_words(gloss)
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


class Phrase(BaseModel):
    ru: str
    gloss_en: str
    # Debug aid, not used by the app UI today: the raw retrieval scores this
    # candidate had, so a "why did I get this suggestion" question can be
    # answered from the API response directly instead of guessing. None
    # when not applicable (e.g. an LLM-generated translation, which never
    # went through retrieval scoring at all).
    embed_score: float | None = None
    hybrid_score: float | None = None


class AssistRequest(BaseModel):
    text: str  # English word/phrase to translate, or a Russian phrase to expand on


class AssistResponse(BaseModel):
    mode: str  # "translate" (input was English) or "expand" (input was Russian)
    source: str | None = None  # translate mode only: "curated" | "live" | "mock"
    input: str
    translation: Phrase | None = None
    related: list[Phrase]
    latency_ms: float
    # The MIN_EMBED_SIMILARITY value actually applied to this request's
    # retrieval -- included so "was this the old threshold or the new one"
    # is answerable from the response itself, not by cross-checking which
    # image is deployed.
    similarity_threshold: float = MIN_EMBED_SIMILARITY


@app.post("/assist", response_model=AssistResponse, dependencies=[Depends(require_api_key)])
@limiter.limit("30/minute")
def assist(request: Request, req: AssistRequest) -> AssistResponse:
    """
    Caregiver-facing helper (distinct from /ask, which is the child-directed
    perception-event flow). One input box, auto-detected:

    - English in -> "translate" mode. Always a live/mock LLM translation of
      the input, unless the input essentially *is* a curated phrase (high
      content-word overlap with its gloss_en, see CURATED_MATCH_MIN_OVERLAP),
      in which case the hand-vetted phrase wins. A single incidental shared
      word ("time" -> "Time to sleep, little one.") no longer counts.
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
        related = [
            Phrase(ru=c["ru"], gloss_en=c["gloss_en"], embed_score=_round_score(c.get("embed_score")), hybrid_score=_round_score(c.get("hybrid_score")))
            for c in candidates
        ]
        latency_ms = round((time.perf_counter() - start) * 1000, 2)
        logger.info("assist mode=expand input=%r latency_ms=%.2f", text, latency_ms)
        return AssistResponse(mode=mode, input=text, related=related, latency_ms=latency_ms)

    # translate mode
    top = candidates[0] if candidates else None
    is_curated_match = bool(
        top and _curated_overlap(text, top["gloss_en"]) >= CURATED_MATCH_MIN_OVERLAP
    )

    if is_curated_match:
        translation = Phrase(ru=top["ru"], gloss_en=top["gloss_en"], embed_score=_round_score(top.get("embed_score")), hybrid_score=_round_score(top.get("hybrid_score")))
        source = "curated"
    else:
        generation = translate(text, candidates)
        translation = Phrase(ru=generation["ru"], gloss_en=text)
        source = generation["mode"]

    related = [
        Phrase(ru=c["ru"], gloss_en=c["gloss_en"], embed_score=_round_score(c.get("embed_score")), hybrid_score=_round_score(c.get("hybrid_score")))
        for c in candidates if c is not top
    ][:4]
    latency_ms = round((time.perf_counter() - start) * 1000, 2)
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
