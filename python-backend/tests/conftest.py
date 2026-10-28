"""Shared real PostgreSQL/pgvector fixtures for integration tests."""

from collections.abc import Iterator
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy import Engine, create_engine
from testcontainers.postgres import PostgresContainer

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"


@pytest.fixture(scope="session")
def postgres_engine() -> Iterator[Engine]:
    """Start pgvector and apply the checked-in Flyway SQL against an empty database."""

    with PostgresContainer(
        "pgvector/pgvector:pg17", username="rag", password="rag", dbname="rag"
    ) as postgres:
        url = postgres.get_connection_url().replace("postgresql+psycopg2", "postgresql+psycopg")
        engine = create_engine(url)
        raw = engine.raw_connection()
        try:
            cursor = raw.driver_connection.cursor()
            cursor.execute("CREATE EXTENSION IF NOT EXISTS vector")
            for path in sorted(
                MIGRATIONS.glob("V*.sql"), key=lambda item: int(item.name.split("__", 1)[0][1:])
            ):
                cursor.execute(path.read_text(encoding="utf-8"))
            raw.commit()
        finally:
            raw.close()
        yield engine
        engine.dispose()


@pytest.fixture
def clean_postgres(postgres_engine: Engine) -> Iterator[Engine]:
    """Isolate each integration test in a transactionally truncated corpus."""

    with postgres_engine.begin() as connection:
        connection.exec_driver_sql(
            "TRUNCATE evaluation_scenario_results,evaluation_runs,rag_traces,python_sessions,"
            "ingestion_jobs,document_chunks,document_permissions,document_versions,documents CASCADE"
        )
    yield postgres_engine


@pytest.fixture(autouse=True)
def configured_app_database(postgres_engine: Engine, monkeypatch) -> None:
    """Point app/security database globals at the session's isolated Testcontainer."""

    import app.database as database
    import app.main as main
    import app.security as security
    from app.storage import Storage

    monkeypatch.setattr(main, "engine", postgres_engine)
    monkeypatch.setattr(main, "database_ready", lambda: True)
    monkeypatch.setattr(main, "storage", Storage(postgres_engine))
    monkeypatch.setattr(database, "engine", postgres_engine)
    monkeypatch.setattr(database, "get_engine", lambda: postgres_engine)
    monkeypatch.setattr(security, "get_engine", lambda: postgres_engine)
    monkeypatch.setattr(database, "engine", postgres_engine)


@pytest.fixture
def synthetic_data():
    """Return normalized synthetic-only document metadata for database tests."""

    from app.documents import normalize_upload

    def make(document_id: str | None = None, body: bytes = b"HTTP 429 clients retry after backoff"):
        return normalize_upload(
            document_id or f"doc-{uuid4().hex[:12]}",
            "Test policy",
            "ENGINEERING",
            "INTERNAL",
            "ENGINEERING",
            "policy.txt",
            body,
        )

    return make
