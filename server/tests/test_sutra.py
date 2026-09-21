import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.session_store import SessionStore
from app.sutra import SutraStore, TranslationError, Translator, summarize_for_agent

class StubTranslator(Translator):

    def __init__(self, fail: bool = False) -> None:
        super().__init__(groq_api_key="stub-key")
        self.calls: list[tuple[str, str, str]] = []
        self.fail = fail

    @property
    def is_configured(self) -> bool:
        return True

    async def translate(self, text: str, source: str, target: str) -> str:
        self.calls.append((text, source, target))
        if self.fail:
            raise TranslationError("both vendors down")
        self.last_vendor = "stub"
        self.last_latency_ms = 7
        return f"[{target}] {text}"

def build_client(translator: Translator | None = None) -> tuple[TestClient, SutraStore, Translator]:
    settings = Settings(
        agora_app_id="0" * 32,
        agora_app_certificate="1" * 32,
        public_base_url="https://example.test",
    )
    store = SutraStore()
    used = translator or StubTranslator()

    from app.routes import create_router

    app = create_app(settings=settings, agora_client=_NoAgora())

    app.router.routes = [r for r in app.router.routes if getattr(r, "path", "").startswith(("/openapi", "/docs", "/redoc"))]
    app.include_router(create_router(settings, SessionStore(60), _NoAgora(), sutra=store, translator=used))
    return TestClient(app), store, used

class _NoAgora:
    def create_user_tokens(self, channel_name: str, rtc_uid: int):
        return "rtc", "rtm", 2_000_000_000

    async def join_agent(self, **kwargs):
        return {"agent_id": "agent-1", "create_ts": 0, "status": "started"}

    async def close(self):
        return None

def test_a_field_report_is_translated_and_kept_for_the_agent():
    client, store, translator = build_client()
    response = client.post(
        "/v1/sutra/report",
        json={
            "channel_name": "ch1",
            "from_uid": 42,
            "language": "hi",
            "text": "दो बच्चे छत पर हैं",
            "urgency": 6,
            "target_language": "en",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["translated"] == "[en] दो बच्चे छत पर हैं"
    assert body["vendor"] == "stub"
    assert translator.calls == [("दो बच्चे छत पर हैं", "hi", "en")]

    tool = client.get("/v1/tools/field_reports", params={"channel": "ch1"}).json()
    assert tool["total_reports"] == 1
    assert tool["urgent_reports"] == 1
    assert tool["reports"][0]["said"] == "[en] दो बच्चे छत पर हैं"

    assert tool["reports"][0]["original"] == "दो बच्चे छत पर हैं"

def test_reports_are_scoped_to_their_channel():
    client, _, _ = build_client()
    for channel in ("ch1", "ch2"):
        client.post(
            "/v1/sutra/report",
            json={"channel_name": channel, "from_uid": 1, "language": "hi", "text": f"from {channel}", "urgency": 0},
        )
    first = client.get("/v1/tools/field_reports", params={"channel": "ch1"}).json()
    assert first["total_reports"] == 1
    assert "ch1" in first["reports"][0]["original"]

def test_relay_tool_queues_an_instruction_the_control_room_can_collect():
    client, _, _ = build_client()
    queued = client.get(
        "/v1/tools/relay",
        params={"channel": "ch1", "message": "Keep the children on the roof", "urgent": "true"},
    ).json()
    assert queued["status"] == "queued"
    assert queued["language"] == "hi"
    assert queued["sent_text"] == "[hi] Keep the children on the roof"

    pending = client.get("/v1/sutra/outbound", params={"channel_name": "ch1", "after_id": 0}).json()["messages"]
    assert len(pending) == 1
    assert pending[0]["urgent"] is True
    assert pending[0]["source_text"] == "Keep the children on the roof"

    assert client.get(
        "/v1/sutra/outbound", params={"channel_name": "ch1", "after_id": pending[0]["id"]}
    ).json()["messages"] == []

def test_a_translation_failure_passes_the_original_through_instead_of_erroring():
    client, _, _ = build_client(StubTranslator(fail=True))
    response = client.post(
        "/v1/sutra/report",
        json={"channel_name": "ch1", "from_uid": 1, "language": "hi", "text": "पानी आ गया", "urgency": 3},
    )
    assert response.status_code == 200

    assert response.json()["translated"] == "पानी आ गया"

def test_same_language_never_calls_a_vendor():
    client, _, translator = build_client()
    client.post(
        "/v1/sutra/report",
        json={
            "channel_name": "ch1",
            "from_uid": 1,
            "language": "en",
            "text": "five people trapped",
            "urgency": 2,
            "target_language": "en",
        },
    )
    assert translator.calls == []

def test_report_validation_rejects_nonsense():
    client, _, _ = build_client()
    bad = client.post(
        "/v1/sutra/report",
        json={"channel_name": "", "from_uid": 1, "language": "hi", "text": "x", "urgency": 99},
    )
    assert bad.status_code == 422

def test_summary_counts_urgency_by_the_same_threshold_the_app_uses():
    store = SutraStore()
    for urgency in (0, 4, 5, 7):
        store.add_report(
            {
                "channel_name": "c",
                "from_uid": 1,
                "language": "hi",
                "text": "t",
                "translated": "t",
                "urgency": urgency,
            }
        )
    summary = summarize_for_agent(store.list_reports("c"))
    assert summary["total_reports"] == 4
    assert summary["urgent_reports"] == 2

def test_the_log_is_bounded_so_a_long_operation_cannot_grow_without_limit():
    store = SutraStore(max_reports=5)
    for i in range(20):
        store.add_report(
            {"channel_name": "c", "from_uid": 1, "language": "hi", "text": str(i), "translated": str(i), "urgency": 0}
        )
    kept = store.list_reports("c")
    assert len(kept) == 5
    assert [r.text for r in kept] == ["15", "16", "17", "18", "19"]

@pytest.mark.asyncio
async def test_translator_falls_back_to_the_second_vendor():
    translator = Translator(groq_api_key="bad", gemini_api_key="also-bad")

    async def groq_fails(text, prompt):
        raise RuntimeError("groq is down")

    async def gemini_works(text, prompt):
        return "translated by gemini"

    translator._groq = groq_fails
    translator._gemini = gemini_works
    assert await translator.translate("नमस्ते", "hi", "en") == "translated by gemini"
    assert translator.last_vendor == "gemini"
    await translator.close()

@pytest.mark.asyncio
async def test_a_second_device_joining_the_channel_does_not_erase_the_agent():
    from app.session_store import SessionRecord, SessionStore

    import time

    now = int(time.time())
    store = SessionStore(ttl_seconds=600)
    await store.put(SessionRecord("ch", 1, "1", now + 3600, now))
    await store.set_agent("ch", "agent-1", "started")

    await store.put(SessionRecord("ch", 2, "2", now + 3600, now))

    record = await store.get("ch")
    assert record.agent_id == "agent-1"
    assert record.requester_rtc_uid == 2
