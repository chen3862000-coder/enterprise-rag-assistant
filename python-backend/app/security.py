"""Server-resolved demo identities, PostgreSQL sessions, and CSRF enforcement."""

import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from fastapi import Request
from fastapi.responses import Response
from sqlalchemy import text

from app.database import get_engine, transaction
from app.errors import ApiError
from app.settings import get_settings

SESSION_COOKIE = "PYTHON_RAG_SESSION"
IDENTITIES: dict[str, tuple[frozenset[str], bool]] = {
    "alice": (frozenset({"PUBLIC", "ENGINEERING"}), False),
    "bob": (frozenset({"PUBLIC", "HR"}), False),
    "carol": (frozenset({"PUBLIC", "SECURITY_ADMIN"}), True),
}


@dataclass(frozen=True)
class Identity:
    """Trusted identity loaded from the server session, never from request roles."""

    username: str
    roles: frozenset[str]
    document_admin: bool

    def as_json(self) -> dict[str, object]:
        """Serialize the identity using the existing Java/frontend field names."""

        return {"username": self.username, "roles": sorted(self.roles), "documentAdmin": self.document_admin}


def known_identity(username: str) -> Identity:
    """Resolve one of the fixed demo accounts or reject it without disclosing details."""

    normalized = username.strip().lower()
    configured = IDENTITIES.get(normalized)
    if not normalized or configured is None:
        raise ApiError(401, "UNAUTHORIZED", "")
    return Identity(normalized, configured[0], configured[1])


def current_identity(request: Request) -> Identity:
    """Require a valid, unexpired server-side identity session."""

    session_id = request.cookies.get(SESSION_COOKIE)
    if not session_id:
        raise ApiError(401, "UNAUTHORIZED", "")
    with get_engine().connect() as connection:
        row = (
            connection.execute(
                text("SELECT username, expires_at FROM python_sessions WHERE session_id = :id"),
                {"id": session_id},
            )
            .mappings()
            .first()
        )
    if row is None or row["username"] is None or row["expires_at"] <= datetime.now(UTC):
        raise ApiError(401, "UNAUTHORIZED", "")
    return known_identity(row["username"])


def csrf_token(request: Request) -> dict[str, str]:
    """Get or create an anonymous server-side session carrying the CSRF token."""

    session_id = request.cookies.get(SESSION_COOKIE)
    if session_id:
        with get_engine().connect() as connection:
            row = (
                connection.execute(
                    text("SELECT csrf_token, expires_at FROM python_sessions WHERE session_id = :id"),
                    {"id": session_id},
                )
                .mappings()
                .first()
            )
        if row is not None and row["expires_at"] > datetime.now(UTC):
            return {"token": row["csrf_token"], "headerName": "X-CSRF-TOKEN", "parameterName": "_csrf"}
    session_id = create_session(None)
    request.state.new_session_id = session_id
    with get_engine().connect() as connection:
        token = connection.execute(
            text("SELECT csrf_token FROM python_sessions WHERE session_id = :id"), {"id": session_id}
        ).scalar_one()
    return {"token": token, "headerName": "X-CSRF-TOKEN", "parameterName": "_csrf"}


def create_session(username: str | None) -> str:
    """Persist a new session and random CSRF token with a bounded lifetime."""

    session_id = secrets.token_urlsafe(32)
    expiry = datetime.now(UTC) + timedelta(seconds=get_settings().session_ttl_seconds)
    with transaction() as connection:
        connection.execute(
            text("""INSERT INTO python_sessions (session_id, username, csrf_token, expires_at)
                    VALUES (:id, :username, :csrf, :expiry)"""),
            {"id": session_id, "username": username, "csrf": secrets.token_urlsafe(32), "expiry": expiry},
        )
    return session_id


def login(request: Request, response: Response, username: str) -> Identity:
    """Rotate any anonymous session and issue a new server-authenticated session."""

    supplied = request.headers.get("X-CSRF-TOKEN", "")
    prior_id = request.cookies.get(SESSION_COOKIE)
    with get_engine().connect() as connection:
        row = (
            connection.execute(
                text("SELECT csrf_token FROM python_sessions WHERE session_id = :id AND expires_at > now()"),
                {"id": prior_id},
            ).first()
            if prior_id
            else None
        )
        if row is None or not secrets.compare_digest(row[0], supplied):
            raise ApiError(403, "FORBIDDEN", "Access is denied")
    identity = known_identity(username)
    with transaction() as connection:
        connection.execute(text("DELETE FROM python_sessions WHERE session_id = :id"), {"id": prior_id})
    new_id = create_session(identity.username)
    request.state.rotated_session_id = new_id
    return identity


def logout(request: Request, response: Response) -> None:
    """Invalidate the active session after validating its CSRF token."""

    session_id = request.cookies.get(SESSION_COOKIE)
    token = request.headers.get("X-CSRF-TOKEN", "")
    with transaction() as connection:
        row = (
            connection.execute(
                text("SELECT csrf_token FROM python_sessions WHERE session_id = :id FOR UPDATE"),
                {"id": session_id},
            ).first()
            if session_id
            else None
        )
        if row is None or not secrets.compare_digest(row[0], token):
            raise ApiError(403, "FORBIDDEN", "Access is denied")
        connection.execute(text("DELETE FROM python_sessions WHERE session_id = :id"), {"id": session_id})
    response.delete_cookie(SESSION_COOKIE, httponly=True, samesite="lax")


def require_csrf(request: Request) -> None:
    """Reject unsafe requests unless their token matches the server-side session."""

    session_id = request.cookies.get(SESSION_COOKIE)
    token = request.headers.get("X-CSRF-TOKEN", "")
    with get_engine().connect() as connection:
        stored = (
            connection.execute(
                text("SELECT csrf_token FROM python_sessions WHERE session_id = :id AND expires_at > now()"),
                {"id": session_id},
            ).scalar_one_or_none()
            if session_id
            else None
        )
    if stored is None or not secrets.compare_digest(stored, token):
        raise ApiError(403, "FORBIDDEN", "Access is denied")


def set_session_cookie(response: Response, session_id: str) -> None:
    """Set the opaque server-side session identifier as an HTTP-only cookie."""

    response.set_cookie(
        SESSION_COOKIE,
        session_id,
        httponly=True,
        secure=False,
        samesite="lax",
        path="/",
        max_age=get_settings().session_ttl_seconds,
    )
