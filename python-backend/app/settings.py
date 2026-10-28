"""Typed environment configuration shared by the Python service modules."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration; secrets are loaded only from environment injection."""

    model_config = SettingsConfigDict(env_file=None, case_sensitive=False)

    database_url: str = "postgresql+psycopg://enterprise_rag:local-rag-password@localhost:5432/enterprise_rag"
    ollama_base_url: str = "http://localhost:11434"
    embedding_model: str = "nomic-embed-text"
    embedding_dimensions: int = 768
    deepseek_api_key: str = ""
    deepseek_model: str = "deepseek-chat"
    chat_model_provider: str = "none"
    seed_synthetic_docs: bool = False
    seed_path: str = "../synthetic-docs"
    trace_retention_days: int = 30
    session_ttl_seconds: int = 28_800
    maximum_upload_bytes: int = 10 * 1024 * 1024
    candidate_depth: int = 10
    fused_depth: int = 8
    rrf_constant: int = 60
    # Keep these aligned with backend application.yml; gate parity depends on them.
    minimum_vector_similarity: float = 0.35
    minimum_keyword_score: float = 0.3
    generation_timeout_seconds: float = 30.0


@lru_cache
def get_settings() -> Settings:
    """Return the process-wide immutable settings snapshot."""

    return Settings()
