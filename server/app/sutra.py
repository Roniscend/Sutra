from __future__ import annotations

import asyncio
import itertools
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

LANGUAGE_NAMES = {
    "hi": "Hindi",
    "bn": "Bengali",
    "gu": "Gujarati",
    "or": "Odia",
    "mr": "Marathi",
    "ta": "Tamil",
    "te": "Telugu",
    "kn": "Kannada",
    "ml": "Malayalam",
    "en": "English",
}

TRANSLATE_SYSTEM = (
    "You translate short radio messages between {source} and {target} for an emergency "
    "control room. Keep every number, name and place exactly as given. Do not add, remove "
    "or soften anything. Do not explain. Output only the translation."
)

@dataclass
class FieldReport:
    id: int
    channel_name: str
    from_uid: int
    language: str
    text: str
    translated: str
    urgency: int
    received_at: float

    def to_json(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "from_uid": self.from_uid,
            "language": self.language,
            "text": self.text,
            "translated": self.translated,
            "urgency": self.urgency,
            "received_at": self.received_at,
            "age_seconds": round(time.time() - self.received_at, 1),
        }

@dataclass
class OutboundMessage:
    id: int
    channel_name: str
    language: str
    text: str
    source_text: str
    urgent: bool
    created_at: float

    def to_json(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "language": self.language,
            "text": self.text,
            "source_text": self.source_text,
            "urgent": self.urgent,
            "created_at": self.created_at,
        }

@dataclass
class SutraStore:

    max_reports: int = 200
    reports: dict[str, list[FieldReport]] = field(default_factory=dict)
    outbound: dict[str, list[OutboundMessage]] = field(default_factory=dict)
    _ids: itertools.count = field(default_factory=lambda: itertools.count(1))

    def add_report(self, report_fields: dict[str, Any]) -> FieldReport:
        report = FieldReport(id=next(self._ids), received_at=time.time(), **report_fields)
        bucket = self.reports.setdefault(report.channel_name, [])
        bucket.append(report)
        if len(bucket) > self.max_reports:
            del bucket[: len(bucket) - self.max_reports]
        return report

    def list_reports(self, channel_name: str, limit: int = 50) -> list[FieldReport]:
        return self.reports.get(channel_name, [])[-limit:]

    def queue_outbound(self, channel_name: str, language: str, text: str, source_text: str, urgent: bool) -> OutboundMessage:
        message = OutboundMessage(
            id=next(self._ids),
            channel_name=channel_name,
            language=language,
            text=text,
            source_text=source_text,
            urgent=urgent,
            created_at=time.time(),
        )
        self.outbound.setdefault(channel_name, []).append(message)
        return message

    def pending_outbound(self, channel_name: str, after_id: int = 0) -> list[OutboundMessage]:
        return [m for m in self.outbound.get(channel_name, []) if m.id > after_id]

    def forget(self, channel_name: str) -> None:
        self.reports.pop(channel_name, None)
        self.outbound.pop(channel_name, None)

class TranslationError(RuntimeError):
    pass

class Translator:

    def __init__(
        self,
        groq_api_key: str = "",
        gemini_api_key: str = "",
        groq_model: str = "qwen/qwen3.8-27b",
        gemini_model: str = "gemini-3.1-flash-lite",
        http_client: httpx.AsyncClient | None = None,
        timeout_seconds: float = 8.0,
    ) -> None:
        self.groq_api_key = groq_api_key
        self.gemini_api_key = gemini_api_key
        self.groq_model = groq_model
        self.gemini_model = gemini_model
        self.timeout_seconds = timeout_seconds
        self._http = http_client or httpx.AsyncClient(timeout=httpx.Timeout(timeout_seconds))
        self._owns_http = http_client is None
        self._cache: dict[tuple[str, str, str], str] = {}
        self.last_vendor: str | None = None
        self.last_latency_ms: int | None = None

    @property
    def is_configured(self) -> bool:
        return bool(self.groq_api_key or self.gemini_api_key)

    async def translate(self, text: str, source: str, target: str) -> str:
        text = text.strip()
        if not text or source == target:
            return text
        key = (text, source, target)
        if key in self._cache:
            self.last_vendor = "cache"
            self.last_latency_ms = 0
            return self._cache[key]

        prompt = TRANSLATE_SYSTEM.format(
            source=LANGUAGE_NAMES.get(source, source),
            target=LANGUAGE_NAMES.get(target, target),
        )
        errors: list[str] = []
        for vendor, call in (("groq", self._groq), ("gemini", self._gemini)):
            started = time.perf_counter()
            try:
                result = await call(text, prompt)
            except Exception as exc:
                errors.append(f"{vendor}: {exc}")
                continue
            if result:
                self.last_vendor = vendor
                self.last_latency_ms = int((time.perf_counter() - started) * 1000)
                self._cache[key] = result
                return result
        raise TranslationError("; ".join(errors) or "no translation vendor configured")

    async def _groq(self, text: str, system_prompt: str) -> str:
        if not self.groq_api_key:
            raise TranslationError("GROQ_API_KEY is not set")
        response = await self._http.post(
            GROQ_URL,
            headers={"Authorization": f"Bearer {self.groq_api_key}"},
            json={
                "model": self.groq_model,
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": text},
                ],
                "temperature": 0,
                "max_tokens": 400,
            },
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"].strip()

    async def _gemini(self, text: str, system_prompt: str) -> str:
        if not self.gemini_api_key:
            raise TranslationError("GEMINI_API_KEY is not set")
        response = await self._http.post(
            GEMINI_URL.format(model=self.gemini_model),
            params={"key": self.gemini_api_key},
            json={
                "systemInstruction": {"parts": [{"text": system_prompt}]},
                "contents": [{"parts": [{"text": text}]}],
                "generationConfig": {"temperature": 0},
            },
        )
        response.raise_for_status()
        return response.json()["candidates"][0]["content"]["parts"][0]["text"].strip()

    async def close(self) -> None:
        if self._owns_http:
            await self._http.aclose()

def summarize_for_agent(reports: list[FieldReport]) -> dict[str, Any]:
    urgent = [r for r in reports if r.urgency >= 5]
    return {
        "total_reports": len(reports),
        "urgent_reports": len(urgent),
        "reports": [
            {
                "id": r.id,
                "from_uid": r.from_uid,
                "said": r.translated,
                "original": r.text,
                "language": r.language,
                "urgency": r.urgency,
                "minutes_ago": round((time.time() - r.received_at) / 60, 1),
            }
            for r in reports
        ],
    }

async def gather_translations(translator: Translator, text: str, source: str, targets: list[str]) -> dict[str, str]:
    unique = [t for t in dict.fromkeys(targets)]
    results = await asyncio.gather(
        *(translator.translate(text, source, target) for target in unique),
        return_exceptions=True,
    )
    out: dict[str, str] = {}
    for target, result in zip(unique, results):
        out[target] = text if isinstance(result, Exception) else result
    return out
