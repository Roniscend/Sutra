from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

class BootstrapRequest(BaseModel):
    requester_rtc_uid: int | None = Field(default=None, ge=1, le=2_147_483_647)
    requester_rtm_user_id: str | None = Field(default=None, min_length=1, max_length=64)

    channel_name: str | None = Field(default=None, min_length=3, max_length=64, pattern=r"^[A-Za-z0-9 !#$%&()+\-:;<=.>?@\[\]^_{|}~,]+$")

class BootstrapResponse(BaseModel):
    app_id: str
    agent_rtc_uid: int
    channel_name: str
    rtc_token: str
    rtm_token: str
    requester_rtc_uid: int
    requester_rtm_user_id: str
    expires_at_unix: int

class JoinRequest(BaseModel):
    channel_name: str = Field(min_length=1, max_length=64)
    requester_rtc_uid: int = Field(ge=1, le=2_147_483_647)
    agent_profile: str | None = Field(default=None, max_length=256)
    system_prompt: str | None = Field(default=None, max_length=8_000)

class JoinResponse(BaseModel):
    agent_id: str
    created_at_unix: int
    status: str

class AgentActionRequest(BaseModel):
    agent_id: str = Field(min_length=1, max_length=128)
    channel_name: str = Field(min_length=1, max_length=64)

class ActionResponse(BaseModel):
    success: bool
    message: str

class TextActionRequest(AgentActionRequest):
    model_config = {"str_strip_whitespace": True}
    text: str = Field(min_length=1, max_length=2000)
    interruptable: bool = True

class SpeakRequest(TextActionRequest):
    priority: Literal["INTERRUPT", "APPEND", "IGNORE"] = "APPEND"

class ThinkRequest(TextActionRequest):
    on_listening_action: Literal["interrupt", "inject", "ignore", "append"] = "append"
    on_thinking_action: Literal["interrupt", "ignore", "append"] = "append"
    on_speaking_action: Literal["interrupt", "ignore", "append"] = "append"

class RefreshRequest(BaseModel):
    channel_name: str = Field(min_length=1, max_length=64)
    requester_rtc_uid: int = Field(ge=1, le=2_147_483_647)
    requester_rtm_user_id: str = Field(min_length=1, max_length=64)

class RefreshResponse(BaseModel):
    rtc_token: str
    rtm_token: str
    expires_at_unix: int

class HealthResponse(BaseModel):
    status: str
    version: str
    agora_configured: bool
    active_sessions: int

class ErrorDetail(BaseModel):
    code: str
    message: str
    request_id: str | None = None
    context: dict[str, Any] | None = None

class FieldReportRequest(BaseModel):

    channel_name: str = Field(min_length=1, max_length=64)
    from_uid: int = Field(ge=0, le=2_147_483_647)
    language: str = Field(min_length=2, max_length=8)
    text: str = Field(min_length=1, max_length=2000)
    urgency: int = Field(default=0, ge=0, le=7)
    target_language: str = Field(default="en", min_length=2, max_length=8)

class FieldReportResponse(BaseModel):
    id: int
    translated: str
    urgency: int
    vendor: str | None = None
    latency_ms: int | None = None

class OutboundMessagesResponse(BaseModel):
    messages: list[dict[str, Any]]

class RelayRequest(BaseModel):
    channel_name: str = Field(min_length=1, max_length=64)
    text: str = Field(min_length=1, max_length=2000)
    language: str = Field(default="hi", min_length=2, max_length=8)
    source_language: str = Field(default="en", min_length=2, max_length=8)
    urgent: bool = False
