"""Explicit HTTP adapters for Ollama embeddings and optional DeepSeek generation."""

import logging
from collections.abc import Callable
from typing import Any, cast

import httpx
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from app.settings import get_settings

LOGGER = logging.getLogger(__name__)


class ProviderFailure(RuntimeError):
    """Provider failure with a stable transient/non-transient classification."""

    def __init__(self, message: str, transient: bool) -> None:
        super().__init__(message)
        self.transient = transient


def _retry_provider(function: Callable[..., Any]) -> Callable[..., Any]:
    """Retry only failures marked transient, with a small bounded exponential delay."""

    return retry(
        retry=retry_if_exception_type(TransientProviderFailure),
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=0.1, max=1.0),
        reraise=True,
    )(function)


class TransientProviderFailure(ProviderFailure):
    """Retryable network, rate-limit, or upstream service failure."""

    def __init__(self, message: str) -> None:
        super().__init__(message, transient=True)


class PermanentProviderFailure(ProviderFailure):
    """Non-retryable configuration, authentication, or request failure."""

    def __init__(self, message: str) -> None:
        super().__init__(message, transient=False)


class OllamaEmbeddingProvider:
    """Generate document/query vectors through Ollama's native embedding API."""

    def __init__(self) -> None:
        settings = get_settings()
        self.base_url = settings.ollama_base_url.rstrip("/")
        self.model = settings.embedding_model
        self.dimensions = settings.embedding_dimensions

    @_retry_provider
    def embed(self, texts: list[str]) -> list[list[float]]:
        """Return one validated fixed-dimension embedding per input text."""

        try:
            response = httpx.post(
                f"{self.base_url}/api/embed",
                json={"model": self.model, "input": texts},
                timeout=httpx.Timeout(connect=3.0, read=45.0, write=10.0, pool=3.0),
            )
        except httpx.TransportError as error:
            raise TransientProviderFailure("Embedding provider unavailable") from error
        if response.status_code in {408, 429, 500, 502, 503, 504}:
            raise TransientProviderFailure("Embedding provider temporarily unavailable")
        if response.is_error:
            raise PermanentProviderFailure("Embedding provider rejected the request")
        vectors = cast(list[list[float]], response.json().get("embeddings", []))
        if len(vectors) != len(texts) or any(len(vector) != self.dimensions for vector in vectors):
            raise PermanentProviderFailure("Embedding provider returned invalid vector dimensions")
        return vectors


class DisabledChatProvider:
    """Represent configured retrieval-only mode without calling an external model."""

    enabled = False
    provider = "none"
    model = "none"

    def generate(self, _system: str, _user: str) -> str:
        """Reject calls because retrieval-only mode must not pretend to generate prose."""

        raise RuntimeError("Chat generation is disabled")


class TestChatProvider:
    """Deterministic injected provider used by tests without paid credentials."""

    __test__ = False

    enabled = True
    provider = "test"
    model = "deterministic-test"

    def __init__(self, answer: str) -> None:
        self.answer = answer

    def generate(self, _system: str, _user: str) -> str:
        """Return the configured stable answer fixture."""

        return self.answer


class DeepSeekChatProvider:
    """Call the OpenAI-compatible DeepSeek endpoint with bounded timeout and retries."""

    enabled = True
    provider = "deepseek"

    def __init__(self) -> None:
        settings = get_settings()
        if not settings.deepseek_api_key:
            raise ValueError("DEEPSEEK_API_KEY is required when DeepSeek generation is enabled")
        self.api_key = settings.deepseek_api_key
        self.model = settings.deepseek_model
        self.timeout = httpx.Timeout(
            connect=5.0, read=settings.generation_timeout_seconds, write=10.0, pool=5.0
        )

    @_retry_provider
    def generate(self, system: str, user: str) -> str:
        """Generate an answer; never log request headers, prompt, or provider response body."""

        try:
            response = httpx.post(
                "https://api.deepseek.com/chat/completions",
                headers={"Authorization": f"Bearer {self.api_key}"},
                json={
                    "model": self.model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                },
                timeout=self.timeout,
            )
        except httpx.TransportError as error:
            raise TransientProviderFailure("Chat provider unavailable") from error
        if response.status_code in {408, 429, 500, 502, 503, 504}:
            raise TransientProviderFailure("Chat provider temporarily unavailable")
        if response.status_code in {400, 401, 403, 404, 422}:
            raise PermanentProviderFailure("Chat provider rejected the request")
        if response.is_error:
            raise PermanentProviderFailure("Chat provider request failed")
        try:
            return str(response.json()["choices"][0]["message"]["content"])
        except (KeyError, IndexError, TypeError) as error:
            LOGGER.warning("Chat provider returned an invalid response shape")
            raise PermanentProviderFailure("Chat provider returned an invalid response") from error


def chat_provider() -> DisabledChatProvider | DeepSeekChatProvider:
    """Select the configured production provider without silently falling back."""

    provider = get_settings().chat_model_provider.lower()
    if provider == "none":
        return DisabledChatProvider()
    if provider == "deepseek":
        return DeepSeekChatProvider()
    raise ValueError(f"Unsupported CHAT_MODEL_PROVIDER: {provider}")
