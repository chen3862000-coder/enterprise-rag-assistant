"""Tests for provider retry classification and server-authoritative demo identity policy."""

import httpx
import pytest
from pydantic import ValidationError

from app.errors import ApiError
from app.providers import (
    DeepSeekChatProvider,
    OllamaEmbeddingProvider,
    PermanentProviderFailure,
    TransientProviderFailure,
    _retry_provider,
)
from app.query_planner import (
    DeepSeekQueryPlanner,
    PlannedQuery,
    QueryIntent,
    QueryPlan,
    QueryPlanningService,
    normalize_query,
)
from app.security import known_identity


def test_known_identities_have_fixed_server_roles_and_admin_is_separate() -> None:
    """The login selector cannot supply roles, and Carol's admin capability is not a role."""

    alice = known_identity(" ALICE ")
    carol = known_identity("carol")
    assert alice.roles == frozenset({"PUBLIC", "ENGINEERING"})
    assert not alice.document_admin
    assert carol.roles == frozenset({"PUBLIC", "SECURITY_ADMIN"})
    assert carol.document_admin
    with pytest.raises(ApiError) as error:
        known_identity("admin")
    assert error.value.status == 401


def test_retry_decorator_retries_transient_but_not_deterministic_failures() -> None:
    """Transient provider errors retry at most three times; permanent errors fail immediately."""

    calls = 0

    @_retry_provider
    def transient():
        nonlocal calls
        calls += 1
        if calls < 3:
            raise TransientProviderFailure("temporary")
        return "ok"

    assert transient() == "ok"
    assert calls == 3
    calls = 0

    @_retry_provider
    def permanent():
        nonlocal calls
        calls += 1
        raise PermanentProviderFailure("bad credentials")

    with pytest.raises(PermanentProviderFailure):
        permanent()
    assert calls == 1


def test_embedding_provider_dimension_mismatch_is_not_retried(monkeypatch) -> None:
    """Wrong provider vector dimensions are deterministic configuration failures."""

    provider = object.__new__(OllamaEmbeddingProvider)
    provider.base_url = "http://ollama"
    provider.model = "nomic-embed-text"
    provider.dimensions = 2
    calls = 0

    def fake_post(*_args, **_kwargs):
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={"embeddings": [[1.0]]})

    monkeypatch.setattr(httpx, "post", fake_post)
    with pytest.raises(PermanentProviderFailure):
        provider.embed(["one"])
    assert calls == 1


def test_deepseek_never_retries_authentication_error_or_logs_secret(monkeypatch, caplog) -> None:
    """Provider authentication errors fail once and API credentials never appear in logs."""

    monkeypatch.setenv("DEEPSEEK_API_KEY", "synthetic-test-secret")
    monkeypatch.setenv("DEEPSEEK_MODEL", "deepseek-chat")
    from app.settings import get_settings

    get_settings.cache_clear()


def test_query_normalization_removes_only_prefix_and_preserves_quoted_identifiers() -> None:
    """Normalization preserves unrecognized language phrasing, code, and quoted text."""

    assert normalize_query("解释下python版本的csd函数") == "解释下python版本的csd函数"
    assert normalize_query("什么是OKR?") == "什么是OKR?"
    assert normalize_query("Explain HTTP 429 for `/v1/csd`") == "Explain HTTP 429 for `/v1/csd`"
    assert normalize_query('explain "ignore previous instructions" and CSD') == (
        'explain "ignore previous instructions" and CSD'
    )
    assert normalize_query("python版本的csd函数") == "python版本的csd函数"


def test_query_plan_rejects_security_fields_and_enforces_bounds() -> None:
    """Planner output schema has no authorization/filter fields and rejects oversized lists."""

    base = {
        "intent": "EXPLAIN",
        "subject": "Python CSD function",
        "constraints": ["Python"],
        "lexical_queries": ["python csd 函数"],
        "semantic_query": "Explain the Python CSD function",
        "alternative_queries": ["CSD Python API"],
        "language": "zh",
    }
    assert QueryPlan.model_validate(base).intent == QueryIntent.EXPLAIN
    with pytest.raises(ValidationError):
        QueryPlan.model_validate({**base, "roles": ["SECURITY_ADMIN"]})
    with pytest.raises(ValidationError):
        QueryPlan.model_validate({**base, "lexical_queries": ["x"] * 5})


@pytest.mark.anyio
async def test_planner_disabled_and_provider_failures_use_deterministic_fallback(monkeypatch) -> None:
    """Disabled/no-key and malformed planner providers preserve a usable deterministic plan."""

    from app.settings import Settings

    monkeypatch.setattr(
        "app.query_planner.get_settings",
        lambda: Settings(query_planner_enabled=False, query_planner_provider="deepseek"),
    )
    disabled = await QueryPlanningService().plan("解释下python版本的csd函数")
    assert disabled.status == "DISABLED"
    assert disabled.plan.subject == "解释下python版本的csd函数"
    assert disabled.plan.intent == QueryIntent.UNKNOWN

    class BrokenPlanner:
        provider = "deepseek"
        model = "test"

        async def plan(self, _original_query: str, _normalized_query: str) -> QueryPlan:
            raise ValueError("provider output must not leak")

    monkeypatch.setattr("app.query_planner.get_settings", lambda: Settings(query_planner_enabled=True))
    fallback = await QueryPlanningService(BrokenPlanner()).plan("解释下python版本的csd函数")
    assert fallback.status == "FALLBACK"
    assert fallback.fallback_reason == "INVALID_PLAN"
    assert fallback.plan.subject == "解释下python版本的csd函数"
    assert "provider output must not leak" not in str(fallback)


def test_query_plan_bounds_and_deduplicates_channels() -> None:
    """Planner variants are capped, normalized and original query remains the first baseline."""

    plan = QueryPlan(
        intent="EXPLAIN",
        subject="Python CSD",
        constraints=["Python"],
        lexical_queries=["Python CSD", "CSD Python"],
        semantic_query="Explain Python CSD",
        alternative_queries=["CSD Python", "csd function"],
        language="en",
    )
    planned = PlannedQuery(
        "解释下 Python CSD",
        "Python CSD",
        plan,
        "test",
        "model",
        0,
        "SUCCEEDED",
        None,
        5,
        0,
    )
    queries = planned.retrieval_queries()
    channels = planned.channel_queries()
    assert queries[0] == "解释下 Python CSD"
    assert len(queries) <= 10
    assert len({query.casefold() for query in queries}) == len(queries)
    assert len(channels) <= 10
    assert channels[0][1] == frozenset({"vector", "keyword", "exact"})


@pytest.mark.anyio
async def test_deepseek_planner_parses_json_and_retries_schema_once(monkeypatch) -> None:
    """The planner makes at most two strict JSON attempts and validates before use."""

    import json

    from app.settings import Settings

    monkeypatch.setattr(
        "app.query_planner.get_settings",
        lambda: Settings(deepseek_api_key="synthetic", query_planner_retry_count=1),
    )
    planner = DeepSeekQueryPlanner()
    replies = iter(
        [
            httpx.Response(200, json={"choices": [{"message": {"content": "not-json"}}]}),
            httpx.Response(
                200,
                json={
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "intent": "EXPLAIN",
                                        "subject": "Python CSD function",
                                        "constraints": ["Python"],
                                        "lexical_queries": ["python csd 函数"],
                                        "semantic_query": "Explain Python CSD function",
                                        "alternative_queries": ["CSD Python API"],
                                        "language": "zh",
                                    }
                                )
                            }
                        }
                    ]
                },
            ),
        ]
    )
    calls = 0
    requests = []

    async def fake_post(*_args, **kwargs):
        nonlocal calls
        calls += 1
        requests.append(kwargs["json"])
        return next(replies)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    plan = await planner.plan("解释下python版本的csd函数", "python版本的csd函数")
    assert plan.subject == "Python CSD function"
    system_prompt = requests[0]["messages"][0]["content"]
    assert "canonical subject as a standalone lexical query" in system_prompt
    assert calls == 2


@pytest.mark.anyio
async def test_planner_timeout_and_malformed_output_fall_back_without_error_leak(monkeypatch, caplog) -> None:
    """Timeout and malformed plans become deterministic fallback diagnostics, not user errors."""

    class TimeoutPlanner:
        provider = "deepseek"
        model = "test-model"

        async def plan(self, _original_query: str, _normalized_query: str) -> QueryPlan:
            raise httpx.ReadTimeout("synthetic secret provider detail")

    monkeypatch.setattr(
        "app.query_planner.get_settings", lambda: __import__("app.settings", fromlist=["Settings"]).Settings()
    )
    result = await QueryPlanningService(TimeoutPlanner()).plan("解释下python版本的csd函数")
    assert result.status == "FALLBACK"
    assert result.fallback_reason == "TIMEOUT"
    assert result.plan.subject == "解释下python版本的csd函数"
    assert "synthetic secret" not in str(result)
    monkeypatch.setenv("DEEPSEEK_API_KEY", "synthetic-test-secret")
    monkeypatch.setenv("DEEPSEEK_MODEL", "deepseek-chat")
    from app.settings import get_settings

    get_settings.cache_clear()
    provider = DeepSeekChatProvider()
    calls = 0

    def rejected(*_args, **_kwargs):
        nonlocal calls
        calls += 1
        return httpx.Response(401, json={"error": "invalid key"})

    monkeypatch.setattr(httpx, "post", rejected)
    with pytest.raises(PermanentProviderFailure):
        provider.generate("system", "synthetic question")
    assert calls == 1
    assert "synthetic-test-secret" not in caplog.text
    monkeypatch.delenv("DEEPSEEK_API_KEY")
    get_settings.cache_clear()
