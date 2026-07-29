"""
Ingestion microservice.

Owns: normalizing raw voice/vision input into a PerceptionEvent and handing it
off to orchestration-service.

BARE-BONES NOTE:
This calls orchestration-service directly over HTTP for simplicity. The
publish/subscribe seam is exactly where a real message queue (Kafka topic
"perception-events", or Redis Streams) belongs once you want durability,
replay, and multiple consumers (e.g. a separate logging/analytics consumer
alongside orchestration). See services/common/events.py -- swap
`_forward_to_orchestration()` below for `bus.publish("perception-events", event)`
and add a Kafka-backed EventBus implementation there; no other service needs
to change.

Run: uvicorn services.ingestion_service.main:app --port 8003 --reload
Requires orchestration-service running on ORCHESTRATION_SERVICE_URL (default :8002).
"""
from __future__ import annotations

import logging

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from services.common.config import ORCHESTRATION_SERVICE_URL
from services.common.events import PerceptionEvent

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ingestion_service")

app = FastAPI(title="ingestion-service")


class VoiceEventRequest(BaseModel):
    transcript: str
    routine_hint: str | None = None


class VisionEventRequest(BaseModel):
    image_ref: str  # placeholder: path/URL/base64 ref once camera pipeline exists
    routine_hint: str | None = None


def _forward_to_orchestration(event: PerceptionEvent) -> dict:
    transcript = event.payload.get("transcript") or f"[{event.event_type} event, no transcript yet]"
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.post(
                f"{ORCHESTRATION_SERVICE_URL}/ask",
                json={"transcript": transcript, "routine": event.context.get("routine_hint")},
            )
            resp.raise_for_status()
            return resp.json()
    except httpx.HTTPError as e:
        logger.error("orchestration-service call failed: %s", e)
        raise HTTPException(status_code=502, detail="orchestration-service unavailable") from e


@app.post("/event/voice")
def voice_event(req: VoiceEventRequest) -> dict:
    event = PerceptionEvent.new(
        event_type="voice",
        payload={"transcript": req.transcript},
        context={"routine_hint": req.routine_hint},
    )
    logger.info("voice event_id=%s transcript=%r", event.event_id, req.transcript)
    return {"event_id": event.event_id, "response": _forward_to_orchestration(event)}


@app.post("/event/vision")
def vision_event(req: VisionEventRequest) -> dict:
    # Vision pipeline (Gemma 4 image understanding) is future scope -- this
    # endpoint exists now so the perception-event shape is already unified
    # across modalities, per the architecture decision.
    event = PerceptionEvent.new(
        event_type="vision",
        payload={"image_ref": req.image_ref},
        context={"routine_hint": req.routine_hint},
    )
    logger.info("vision event_id=%s image_ref=%r (stub -- no vision model wired up yet)", event.event_id, req.image_ref)
    return {"event_id": event.event_id, "response": _forward_to_orchestration(event)}


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}
