"""
Perception event abstraction.

Both voice input and (future) camera/vision input get normalized into a single
PerceptionEvent and published onto a bus. Every downstream consumer (retrieval,
orchestration, logging) subscribes to the bus instead of being called directly.

BARE-BONES NOTE:
This ships with an in-memory asyncio bus so the whole system runs with zero
infra. It implements the same publish/subscribe interface a real broker would,
so swapping in Kafka or Redis Streams later means writing a new class that
satisfies EventBus and changing one line of wiring in main.py -- no changes
needed in the services that publish/consume events.
"""
from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Literal

EventType = Literal["voice", "vision"]


@dataclass
class PerceptionEvent:
    event_id: str
    event_type: EventType
    payload: dict[str, Any]  # e.g. {"transcript": "..."} or {"image_ref": "..."}
    context: dict[str, Any]  # e.g. {"routine_hint": "mealtime", "time_of_day": "morning"}
    created_at: float = field(default_factory=time.time)

    @classmethod
    def new(cls, event_type: EventType, payload: dict, context: dict | None = None) -> "PerceptionEvent":
        return cls(
            event_id=str(uuid.uuid4()),
            event_type=event_type,
            payload=payload,
            context=context or {},
        )


class EventBus:
    """Minimal interface any broker backend (in-memory, Kafka, Redis Streams) must satisfy."""

    async def publish(self, topic: str, event: PerceptionEvent) -> None:
        raise NotImplementedError

    def subscribe(self, topic: str, handler: Callable[[PerceptionEvent], Awaitable[None]]) -> None:
        raise NotImplementedError


class InMemoryEventBus(EventBus):
    """
    Zero-dependency stand-in for Kafka/Redis Streams. Same publish/subscribe
    shape as a real broker so the swap later is mechanical, not a rewrite.
    """

    def __init__(self) -> None:
        self._subscribers: dict[str, list[Callable[[PerceptionEvent], Awaitable[None]]]] = {}

    def subscribe(self, topic: str, handler: Callable[[PerceptionEvent], Awaitable[None]]) -> None:
        self._subscribers.setdefault(topic, []).append(handler)

    async def publish(self, topic: str, event: PerceptionEvent) -> None:
        for handler in self._subscribers.get(topic, []):
            # fire-and-forget, matching pub/sub semantics of a real broker
            asyncio.create_task(handler(event))


# Process-wide singleton bus for the bare-bones single-process demo.
# In the split-services version, each service instead publishes over HTTP/queue
# to the next service -- see services/*/main.py.
bus = InMemoryEventBus()
