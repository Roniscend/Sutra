from __future__ import annotations

import random
import secrets
import time
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, status

from .agora_client import AgoraClient, AgoraTimeoutError, AgoraUpstreamError
from .config import Settings
from .schemas import (
    ActionResponse,
    AgentActionRequest,
    BootstrapRequest,
    BootstrapResponse,
    FieldReportRequest,
    FieldReportResponse,
    HealthResponse,
    JoinRequest,
    JoinResponse,
    OutboundMessagesResponse,
    RefreshRequest,
    RefreshResponse,
    RelayRequest,
    SpeakRequest,
    ThinkRequest,
)
from .sutra import SutraStore, TranslationError, Translator, summarize_for_agent
from .security import build_rate_limiter
from .session_store import SessionRecord, SessionStore

def create_router(
    settings: Settings,
    store: SessionStore,
    agora: AgoraClient,
    sutra: SutraStore | None = None,
    translator: Translator | None = None,
) -> APIRouter:
    router = APIRouter()
    rate_limit = build_rate_limiter(settings)
    throttled = [Depends(rate_limit)]
    sutra_store = sutra or SutraStore()
    sutra_translator = translator or Translator(
        groq_api_key=settings.groq_api_key,
        gemini_api_key=settings.gemini_api_key,
        groq_model=settings.groq_model,
        gemini_model=settings.gemini_model,
    )

    async def translate_or_passthrough(text: str, source: str, target: str) -> str:
        if source == target or not sutra_translator.is_configured:
            return text
        try:
            return await sutra_translator.translate(text, source, target)
        except TranslationError:
            return text

    @router.post("/v1/sutra/report", response_model=FieldReportResponse, dependencies=throttled)
    async def sutra_report(body: FieldReportRequest) -> FieldReportResponse:
        translated = await translate_or_passthrough(body.text, body.language, body.target_language)
        report = sutra_store.add_report(
            {
                "channel_name": body.channel_name,
                "from_uid": body.from_uid,
                "language": body.language,
                "text": body.text,
                "translated": translated,
                "urgency": body.urgency,
            }
        )
        return FieldReportResponse(
            id=report.id,
            translated=translated,
            urgency=report.urgency,
            vendor=sutra_translator.last_vendor,
            latency_ms=sutra_translator.last_latency_ms,
        )

    @router.get("/v1/sutra/reports", dependencies=throttled)
    async def sutra_reports(channel_name: str, limit: int = 50) -> dict[str, Any]:
        reports = sutra_store.list_reports(channel_name, limit=min(limit, 200))
        return {"channel_name": channel_name, "reports": [r.to_json() for r in reports]}

    @router.post("/v1/sutra/relay", response_model=FieldReportResponse, dependencies=throttled)
    async def sutra_relay(body: RelayRequest) -> FieldReportResponse:
        translated = await translate_or_passthrough(body.text, body.source_language, body.language)
        message = sutra_store.queue_outbound(
            channel_name=body.channel_name,
            language=body.language,
            text=translated,
            source_text=body.text,
            urgent=body.urgent,
        )
        return FieldReportResponse(
            id=message.id,
            translated=translated,
            urgency=7 if body.urgent else 0,
            vendor=sutra_translator.last_vendor,
            latency_ms=sutra_translator.last_latency_ms,
        )

    @router.get("/v1/sutra/outbound", response_model=OutboundMessagesResponse, dependencies=throttled)
    async def sutra_outbound(channel_name: str, after_id: int = 0) -> OutboundMessagesResponse:
        pending = sutra_store.pending_outbound(channel_name, after_id)
        return OutboundMessagesResponse(messages=[m.to_json() for m in pending])

    @router.get("/v1/tools/field_reports", dependencies=throttled)
    async def tool_field_reports(channel: str) -> dict[str, Any]:
        return summarize_for_agent(sutra_store.list_reports(channel, limit=50))

    @router.get("/v1/tools/relay", dependencies=throttled)
    async def tool_relay(channel: str, message: str, urgent: bool = False) -> dict[str, Any]:
        translated = await translate_or_passthrough(
            message, settings.control_language, settings.field_language
        )
        queued = sutra_store.queue_outbound(
            channel_name=channel,
            language=settings.field_language,
            text=translated,
            source_text=message,
            urgent=urgent,
        )
        return {
            "status": "queued",
            "id": queued.id,
            "sent_text": translated,
            "language": settings.field_language,
        }

    @router.get("/v1/tools/guidance", dependencies=throttled)
    def guidance(topic: Literal["setup", "troubleshooting"]):

        path = Path(__file__).resolve().parents[2] / "docs" / f"{topic}.md"
        if not path.is_file():
            raise HTTPException(status_code=503, detail="Project guidance is unavailable.")
        return {"topic": topic, "source": f"docs/{topic}.md", "content": path.read_text(encoding="utf-8")}

    @router.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(
            status="ok",
            version=settings.build_version,
            agora_configured=bool(settings.agora_app_id and settings.agora_app_certificate),
            active_sessions=await store.count(),
        )

    @router.post(
        "/v1/conversation/bootstrap",
        response_model=BootstrapResponse,
        dependencies=throttled,
    )
    async def bootstrap(body: BootstrapRequest) -> BootstrapResponse:
        rtc_uid = body.requester_rtc_uid or random.randint(100_000, 899_999)
        rtm_user_id = body.requester_rtm_user_id or str(rtc_uid)
        if rtm_user_id != str(rtc_uid):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="requester_rtm_user_id must match requester_rtc_uid for the combined RTC/RTM token.",
            )
        channel = body.channel_name or f"android-convoai-{int(time.time())}-{secrets.randbelow(900000) + 100000}"
        rtc_token, rtm_token, expires_at = agora.create_user_tokens(channel, rtc_uid)
        await store.put(
            SessionRecord(
                channel_name=channel,
                requester_rtc_uid=rtc_uid,
                requester_rtm_user_id=rtm_user_id,
                token_expires_at_unix=expires_at,
                created_at_unix=int(time.time()),
            )
        )
        return BootstrapResponse(
            app_id=settings.agora_app_id,
            agent_rtc_uid=settings.agent_uid,
            channel_name=channel,
            rtc_token=rtc_token,
            rtm_token=rtm_token,
            requester_rtc_uid=rtc_uid,
            requester_rtm_user_id=rtm_user_id,
            expires_at_unix=expires_at,
        )

    @router.post(
        "/v1/conversation/join",
        response_model=JoinResponse,
        dependencies=throttled,
    )
    async def join(body: JoinRequest) -> JoinResponse:
        async with store.join_guard(body.channel_name):
            record = await require_session(store, body.channel_name)
            if record.requester_rtc_uid != body.requester_rtc_uid:
                raise HTTPException(status_code=400, detail="Requester RTC UID does not match bootstrap.")
            if record.agent_id:
                return JoinResponse(
                    agent_id=record.agent_id,
                    created_at_unix=record.created_at_unix,
                    status=record.agent_state,
                )
            result = await call_agora(
                agora.join_agent(
                    channel_name=body.channel_name,
                    requester_rtc_uid=body.requester_rtc_uid,
                    agent_profile=body.agent_profile,
                    system_prompt=body.system_prompt,
                )
            )
            agent_id = str(result.get("agent_id", "")).strip()
            if not agent_id:
                raise HTTPException(status_code=502, detail="Agora response did not include agent_id.")
            created_at = int(result.get("create_ts") or time.time())
            agent_state = str(result.get("status") or "started")
            await store.set_agent(body.channel_name, agent_id, agent_state)
            return JoinResponse(
                agent_id=agent_id,
                created_at_unix=created_at,
                status=agent_state,
            )

    @router.post(
        "/v1/conversation/interrupt",
        response_model=ActionResponse,
        dependencies=throttled,
    )
    async def interrupt(body: AgentActionRequest) -> ActionResponse:
        await require_agent(store, body)
        await call_agora(agora.interrupt_agent(body.agent_id, body.channel_name))
        return ActionResponse(success=True, message="Agent interrupted.")

    @router.post(
        "/v1/conversation/leave",
        response_model=ActionResponse,
        dependencies=throttled,
    )
    async def leave(body: AgentActionRequest) -> ActionResponse:
        await require_agent(store, body)
        await call_agora(agora.leave_agent(body.agent_id, body.channel_name))
        await store.remove(body.channel_name)
        return ActionResponse(success=True, message="Agent left the channel.")

    @router.post("/v1/conversation/speak", response_model=ActionResponse, dependencies=throttled)
    async def speak(body: SpeakRequest) -> ActionResponse:
        await require_agent(store, body)
        await call_agora(agora.speak(**body.model_dump()))
        return ActionResponse(success=True, message="Speech request accepted.")

    @router.post("/v1/conversation/think", response_model=ActionResponse, dependencies=throttled)
    async def think(body: ThinkRequest) -> ActionResponse:
        await require_agent(store, body)
        await call_agora(agora.think(**body.model_dump()))
        return ActionResponse(success=True, message="Instruction accepted.")

    @router.post(
        "/v1/conversation/refresh",
        response_model=RefreshResponse,
        dependencies=throttled,
    )
    async def refresh(body: RefreshRequest) -> RefreshResponse:
        record = await require_session(store, body.channel_name)
        if (
            record.requester_rtc_uid != body.requester_rtc_uid
            or record.requester_rtm_user_id != body.requester_rtm_user_id
        ):
            raise HTTPException(status_code=400, detail="Refresh identity does not match bootstrap.")
        rtc_token, rtm_token, expires_at = agora.create_user_tokens(
            body.channel_name,
            body.requester_rtc_uid,
        )
        record.token_expires_at_unix = expires_at
        await store.put(record)
        return RefreshResponse(
            rtc_token=rtc_token,
            rtm_token=rtm_token,
            expires_at_unix=expires_at,
        )

    return router

async def require_session(store: SessionStore, channel_name: str) -> SessionRecord:
    record = await store.get(channel_name)
    if record is None:
        raise HTTPException(status_code=404, detail="Conversation session was not found or expired.")
    return record

async def require_agent(store: SessionStore, body: AgentActionRequest) -> SessionRecord:
    record = await require_session(store, body.channel_name)
    if record.agent_id != body.agent_id:
        raise HTTPException(status_code=400, detail="Agent ID does not match the conversation session.")
    return record

async def call_agora(awaitable: Any) -> Any:
    try:
        return await awaitable
    except AgoraTimeoutError as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except AgoraUpstreamError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
