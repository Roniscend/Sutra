from __future__ import annotations

import secrets
import time
from typing import Any

import httpx
from urllib.parse import quote
from agora_agent import Agent, Area, AsyncAgora, DeepgramSTT, MiniMaxTTS, OpenAI
from agora_agent.agentkit import generate_convo_ai_token
from agora_agent.core.api_error import ApiError

from .config import Settings

DEFAULT_SYSTEM_PROMPT = """You are Sutra, the voice of an emergency control room's relay desk.

Field teams are on links too weak to carry a phone call. Their speech arrives here as text that was compressed on their phone and translated for you. You are the officer's ears and mouth for those teams.

How to behave:
- When the officer asks anything about the situation - how many people are trapped, what a team said, which locations have reported - call getFieldReports and answer only from what comes back. Quote the field's own words. Never invent a report, a number or a name.
- When the officer gives an instruction meant for a field team, call relayToField with that instruction, then confirm in one short sentence what you sent. Set urgent true only when the instruction is an emergency.
- If asked something the reports do not cover, say plainly that the field has not reported it.
- Keep every answer short enough to say out loud on a radio. No lists, no preamble, no repeating the question.
- Numbers, names and places are the point: repeat them exactly as the field gave them."""

class AgoraUpstreamError(RuntimeError):
    pass

class AgoraTimeoutError(TimeoutError):
    pass

AREA_BY_NAME = {
    "NORTH_AMERICA": Area.US,
    "US": Area.US,
    "EUROPE": Area.EU,
    "EU": Area.EU,
    "ASIA_PACIFIC": Area.AP,
    "AP": Area.AP,
    "CHINA": Area.CN,
    "CN": Area.CN,
}

class AgoraClient:
    def __init__(self, settings: Settings, http_client: httpx.AsyncClient | None = None) -> None:
        self.settings = settings
        self._http = http_client or httpx.AsyncClient(timeout=httpx.Timeout(60.0))
        self._owns_http = http_client is None
        area_name = settings.agora_area.strip().upper()
        if area_name not in AREA_BY_NAME:
            supported = ", ".join(sorted(AREA_BY_NAME))
            raise ValueError(f"Unsupported AGORA_AREA '{settings.agora_area}'. Use one of: {supported}.")
        self._client = AsyncAgora(
            area=AREA_BY_NAME[area_name],
            app_id=settings.agora_app_id,
            app_certificate=settings.agora_app_certificate,
            httpx_client=self._http,
        )
        self._sessions: dict[str, tuple[str, Any]] = {}

    def create_user_tokens(self, channel_name: str, rtc_uid: int) -> tuple[str, str, int]:
        token = generate_convo_ai_token(
            app_id=self.settings.agora_app_id,
            app_certificate=self.settings.agora_app_certificate,
            channel_name=channel_name,
            uid=rtc_uid,
            token_expire=self.settings.token_expiry_seconds,
        )
        expires_at = int(time.time()) + self.settings.token_expiry_seconds
        return token, token, expires_at

    def _build_agent(self, system_prompt: str | None = None, channel_name: str | None = None) -> Agent:
        tools = []
        base = self.settings.public_base_url.rstrip("/") if self.settings.public_base_url else ""
        if base and channel_name:
            channel = quote(channel_name, safe="")
            tools.append({
                "type": "function",
                "function": {
                    "name": "getFieldReports",
                    "description": "Read every message field teams have sent in this session, with urgency and how long ago each arrived. Use before answering any question about the situation on the ground.",
                    "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
                },
                "execution": {"mode": "sync"},
                "server": {
                    "method": "GET",
                    "url": f"{base}/v1/tools/field_reports?channel={channel}",
                    "timeout_ms": 5000,
                },
            })
            tools.append({
                "type": "function",
                "function": {
                    "name": "relayToField",
                    "description": "Send an instruction to the field teams. It is translated into their language and delivered over the low-bandwidth link. Use whenever the officer is speaking to the field rather than to you.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "message": {"type": "string", "description": "The instruction, in the officer's own words."},
                            "urgent": {"type": "boolean", "description": "True only for emergency instructions that must interrupt."},
                        },
                        "required": ["message"],
                        "additionalProperties": False,
                    },
                },
                "execution": {"mode": "sync"},
                "server": {
                    "method": "GET",
                    "url": f"{base}/v1/tools/relay?channel={channel}&message={{{{args.message}}}}&urgent={{{{args.urgent}}}}",
                    "timeout_ms": 8000,
                },
            })
        return (
            Agent(
                client=self._client,
                turn_detection={
                    "config": {
                        "speech_threshold": 0.5,
                        "start_of_speech": {
                            "mode": "vad",
                            "vad_config": {
                                "interrupt_duration_ms": 160,
                                "prefix_padding_ms": 800,
                            },
                        },
                        "end_of_speech": {
                            "mode": "vad",
                            "vad_config": {"silence_duration_ms": 640},
                        },
                    },
                },
                interruption={"enable": True, "mode": "start_of_speech"},
                filler_words={
                    "enable": True,
                    "trigger": {
                        "mode": "fixed_time",
                        "fixed_time_config": {"response_wait_ms": 1500},
                    },
                    "content": {
                        "mode": "generated",
                        "static_config": {
                            "phrases": [
                                "Let me think that through.",
                                "One moment, please.",
                                "Give me a moment.",
                                "I'm working on that.",
                            ],
                            "selection_rule": "shuffle",
                        },
                        "generated_config": {
                            "prompt": "Briefly acknowledge that you are processing the request. Do not answer it or claim to have performed any action.",
                            "fallback_strategy": "static",
                        },
                    },
                },
                advanced_features={"enable_rtm": True, "enable_tools": bool(tools)},
                parameters={
                    "audio_scenario": "chorus",
                    "data_channel": "rtm",
                    "enable_error_message": True,
                    "enable_metrics": True,
                },
            )
            .with_stt(DeepgramSTT(model=self.settings.asr_model, language=self.settings.asr_language))
            .with_llm(
                OpenAI(
                    model=self.settings.llm_model,
                    system_messages=[
                        {
                            "role": "system",
                            "content": (system_prompt or DEFAULT_SYSTEM_PROMPT)
                            + (
                                ""
                                if tools
                                else "\n\nNo tools are available in this session, so you cannot read field reports or relay instructions. Say so if asked."
                            ),
                        }
                    ],
                    greeting_message="Relay desk online. I will read out field reports as they arrive.",
                    failure_message="Please wait a moment.",
                    max_history=15,
                    max_tokens=1024,
                    temperature=0.7,
                    top_p=0.95,
                    tools=tools or None,
                )
            )
            .with_tts(
                MiniMaxTTS(
                    model=self.settings.tts_model,
                    voice_id=self.settings.tts_voice_id,
                )
            )
        )

    async def join_agent(
        self,
        channel_name: str,
        requester_rtc_uid: int,
        agent_profile: str | None = None,
        system_prompt: str | None = None,
    ) -> dict[str, Any]:
        session = self._build_agent(system_prompt, channel_name=channel_name).create_async_session(
            channel=channel_name,
            agent_uid=str(self.settings.agent_uid),
            remote_uids=[str(requester_rtc_uid)],
            name=f"android-server-agent-{int(time.time())}-{secrets.randbelow(9000) + 1000}",
            idle_timeout=30,
            preset=agent_profile,
            expires_in=self.settings.token_expiry_seconds,
            debug=False,
        )
        try:
            agent_id = await session.start()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError, ValueError) as exc:
            raise AgoraUpstreamError(f"Agora Conversational AI start failed: {exc}") from exc
        if not agent_id:
            raise AgoraUpstreamError("Agora response did not include agent_id.")
        self._sessions[agent_id] = (channel_name, session)
        return {
            "agent_id": agent_id,
            "create_ts": int(time.time()),
            "status": "started",
        }

    async def interrupt_agent(self, agent_id: str, channel_name: str) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.interrupt()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora interrupt request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError) as exc:
            raise AgoraUpstreamError(f"Agora agent interrupt failed: {exc}") from exc

    async def speak(self, agent_id: str, channel_name: str, text: str, priority: str, interruptable: bool) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.say(text, priority=priority, interruptable=interruptable)
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora speech request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError, ValueError) as exc:
            raise AgoraUpstreamError(f"Agora speech request failed: {exc}") from exc

    async def think(self, agent_id: str, channel_name: str, text: str, on_listening_action: str, on_thinking_action: str, on_speaking_action: str, interruptable: bool) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.think(
                text,
                on_listening_action=on_listening_action,
                on_thinking_action=on_thinking_action,
                on_speaking_action=on_speaking_action,
                interruptable=interruptable,
            )
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora instruction request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError, ValueError) as exc:
            raise AgoraUpstreamError(f"Agora instruction request failed: {exc}") from exc

    async def leave_agent(self, agent_id: str, channel_name: str) -> None:
        session = self._require_session(agent_id, channel_name)
        try:
            await session.stop()
        except httpx.TimeoutException as exc:
            raise AgoraTimeoutError("Agora stop request timed out.") from exc
        except (ApiError, httpx.HTTPError, RuntimeError) as exc:
            raise AgoraUpstreamError(f"Agora agent stop failed: {exc}") from exc
        self._sessions.pop(agent_id, None)

    def _require_session(self, agent_id: str, channel_name: str) -> Any:
        active = self._sessions.get(agent_id)
        if active is None or active[0] != channel_name:
            raise AgoraUpstreamError("The Agora agent session is not active in this server process.")
        return active[1]

    async def close(self) -> None:
        if self._owns_http:
            await self._http.aclose()
