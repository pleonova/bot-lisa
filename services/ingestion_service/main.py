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

AUTH NOTE:
This is the one service exposed outside the cluster (see infra/k8s/ingestion-service.yaml,
type: LoadBalancer), so it's the entry point that needs to reject strangers. Auth is a
simple shared-secret header check, not full user auth -- fine for a single-caregiver
device talking to its own backend, not meant to scale to multiple end users.
"""
from __future__ import annotations

import hmac
import logging

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from services.common.config import INGESTION_API_KEY, ORCHESTRATION_SERVICE_URL
from services.common.events import PerceptionEvent

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ingestion_service")

app = FastAPI(title="ingestion-service")


def require_api_key(x_api_key: str | None = Header(default=None)) -> None:
    """Guards /event/* endpoints. No-op if INGESTION_API_KEY isn't set (local dev
    default) -- so the README quickstart keeps working unchanged. Once
    INGESTION_API_KEY is set (e.g. via the bot-lisa-secrets k8s Secret), callers
    must send a matching X-API-Key header or get a 401.
    """
    if not INGESTION_API_KEY:
        return
    if not x_api_key or not hmac.compare_digest(x_api_key, INGESTION_API_KEY):
        raise HTTPException(status_code=401, detail="missing or invalid X-API-Key")


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


@app.post("/event/voice", dependencies=[Depends(require_api_key)])
def voice_event(req: VoiceEventRequest) -> dict:
    event = PerceptionEvent.new(
        event_type="voice",
        payload={"transcript": req.transcript},
        context={"routine_hint": req.routine_hint},
    )
    logger.info("voice event_id=%s transcript=%r", event.event_id, req.transcript)
    return {"event_id": event.event_id, "response": _forward_to_orchestration(event)}


@app.post("/event/vision", dependencies=[Depends(require_api_key)])
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
