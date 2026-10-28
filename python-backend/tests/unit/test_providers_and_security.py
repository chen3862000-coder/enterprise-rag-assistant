"""Tests for provider retry classification and server-authoritative demo identity policy."""

import httpx
import pytest

from app.errors import ApiError
from app.providers import (
    DeepSeekChatProvider,
    OllamaEmbeddingProvider,
    PermanentProviderFailure,
    TransientProviderFailure,
    _retry_provider,
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
