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
import time

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from services.common.config import RETRIEVAL_SERVICE_URL
from services.orchestration_service.llm_client import generate

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


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}
