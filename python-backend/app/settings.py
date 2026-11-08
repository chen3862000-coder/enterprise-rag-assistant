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
    query_planner_enabled: bool = True
    query_planner_provider: str = "deterministic"
    query_planner_model: str = ""
    query_planner_timeout_ms: int = 1_500
    query_planner_max_output_tokens: int = 300
    query_planner_retry_count: int = 1
    query_planner_max_lexical_queries: int = 4
    query_planner_max_alternative_queries: int = 3
    seed_synthetic_docs: bool = False
    seed_path: str = "../synthetic-docs"
    trace_retention_days: int = 30
    session_ttl_seconds: int = 28_800
    maximum_upload_bytes: int = 50 * 1024 * 1024
    maximum_chm_entries: int = 20_000
    maximum_chm_expanded_bytes: int = 256 * 1024 * 1024
    maximum_chm_page_bytes: int = 2 * 1024 * 1024
    maximum_chm_listing_bytes: int = 16 * 1024 * 1024
    chm_extractor: str = "7z"
    ingestion_batch_size: int = 32
    stale_ingestion_seconds: int = 3_600
    ingestion_source_dir: str = "/var/lib/enterprise-rag/ingestion"
    ingestion_worker_poll_seconds: float = 2.0
    ingestion_source_retention_days: int = 7
    ingestion_provider_max_attempts: int = 8
    ingestion_worker_enabled: bool = False
    candidate_depth: int = 160
    fused_depth: int = 32
    rrf_constant: int = 60
    # Keep these aligned with backend application.yml; gate parity depends on them.
    minimum_vector_similarity: float = 0.35
    minimum_keyword_score: float = 0.3
    generation_timeout_seconds: float = 30.0


@lru_cache
def get_settings() -> Settings:
    """Return the process-wide immutable settings snapshot."""

    return Settings()
