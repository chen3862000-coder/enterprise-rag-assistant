"""Database engine and explicit SQL helpers for the shared Flyway-owned schema."""

import json
from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy import Engine, create_engine, text
from sqlalchemy.engine import Connection

from app.settings import get_settings


def make_engine(url: str | None = None) -> Engine:
    """Create a SQLAlchemy engine configured for PostgreSQL and bounded connection waits."""

    return create_engine(url or get_settings().database_url, pool_pre_ping=True, pool_timeout=10)


def get_engine() -> Engine:
    """Return the active engine, allowing deterministic test injection without process locks."""

    return engine


engine = make_engine()


@contextmanager
def transaction(connection_engine: Engine | None = None) -> Iterator[Connection]:
    """Yield a connection in a transaction and commit or roll back atomically."""

    with (connection_engine or get_engine()).begin() as connection:
        yield connection


def database_ready(connection_engine: Engine = engine) -> bool:
    """Return whether the configured PostgreSQL database accepts a simple query."""

    with connection_engine.connect() as connection:
        return bool(connection.execute(text("SELECT true")).scalar_one())


def json_value(value: object) -> str:
    """Serialize JSONB bind values using deterministic compact JSON."""

    return json.dumps(value, separators=(",", ":"), sort_keys=True)
