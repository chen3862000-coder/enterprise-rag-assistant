"""FastAPI application, route wiring, and lifespan hooks for the Python backend."""

import logging
import re
from contextlib import asynccontextmanager
from hashlib import sha256
from time import monotonic
from typing import Any, Literal, cast
from uuid import UUID, uuid4

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.responses import Response
from prometheus_client import Counter
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import text

from app.database import database_ready, engine
from app.documents import chunk_document, normalize_upload, parse_document
from app.errors import ApiError, install_error_handlers
from app.evaluation import get_run, recent_runs
from app.evaluation import run_evaluation as evaluate_offline
from app.generation import (
    answer_with_provider,
    build_context,
    public_citation,
    reauthorize,
    validate_citations,
)
from app.providers import (
    DeepSeekChatProvider,
    DisabledChatProvider,
    OllamaEmbeddingProvider,
    PermanentProviderFailure,
    TransientProviderFailure,
    chat_provider,
)
from app.retrieval import HybridRetriever
from app.security import csrf_token, current_identity, login, logout, require_csrf, set_session_cookie
from app.seeding import seed_corpus
from app.settings import get_settings
from app.storage import Storage

LOGGER = logging.getLogger(__name__)
storage = Storage()
embeddings = OllamaEmbeddingProvider()
retriever = HybridRetriever(storage, embeddings)
API_REQUESTS = Counter(
    "python_rag_api_requests_total", "API requests by route and status", ["route", "status"]
)


@asynccontextmanager
async def lifespan(_app: FastAPI) -> Any:
    """Check storage and run bounded expiry cleanup before accepting requests."""

    if not database_ready():
        raise RuntimeError("PostgreSQL is unavailable")
    with engine.begin() as connection:
        connection.execute(text("DELETE FROM python_sessions WHERE expires_at < now()"))
        connection.execute(
            text("DELETE FROM rag_traces WHERE created_at < now() - (:days * interval '1 day')"),
            {"days": get_settings().trace_retention_days},
        )
    if get_settings().seed_synthetic_docs:
        seed_corpus(storage, embeddings)
    yield


app = FastAPI(title="Enterprise RAG Assistant Python Backend", lifespan=lifespan)
install_error_handlers(app)


@app.middleware("http")
async def attach_new_session_cookie(request: Request, call_next: Any) -> Response:
    """Attach a cookie when the public CSRF endpoint creates an anonymous session."""

    response = cast(Response, await call_next(request))
    API_REQUESTS.labels(route=_metric_route(request.url.path), status=str(response.status_code)).inc()
    session_id = getattr(request.state, "new_session_id", None)
    if session_id:
        response.set_cookie(
            "PYTHON_RAG_SESSION",
            session_id,
            httponly=True,
            secure=False,
            samesite="lax",
            path="/",
            max_age=get_settings().session_ttl_seconds,
        )
    return response


def _metric_route(path: str) -> str:
    """Normalize dynamic route segments so IDs are never emitted as metric labels."""

    segments = path.strip("/").split("/")
    if path == "/api/documents":
        return path
    if len(segments) >= 3 and segments[:2] == ["api", "documents"]:
        segments[2] = "{id}"
        if len(segments) >= 4 and segments[3] in {"versions", "reindex", "disable", "restore"}:
            return "/" + "/".join(segments[:4])
    elif len(segments) >= 3 and segments[0] == "api" and segments[1] in {"traces", "sources", "evaluations"}:
        segments[2] = "{id}"
    return "/" + "/".join(segments)


class LoginBody(BaseModel):
    """Login accepts only an identity selector; roles and capabilities are server-derived."""

    username: str = Field(min_length=1)


class ChatBody(BaseModel):
    """Frontend chat input, constrained to Java's nonblank 2,000-character contract."""

    message: str = Field(min_length=1, max_length=2000)

    @field_validator("message")
    @classmethod
    def message_must_not_be_blank(cls, value: str) -> str:
        """Reject whitespace-only messages consistently with Java bean validation."""

        if not value.strip():
            raise ValueError("Message must not be blank")
        return value


@app.get("/actuator/health")
def health() -> dict[str, str]:
    """Expose readiness only when PostgreSQL accepts a query."""

    if not database_ready():
        raise ApiError(503, "STORAGE_UNAVAILABLE", "Document storage is temporarily unavailable")
    return {"status": "UP"}


@app.get("/actuator/info")
def actuator_info() -> dict[str, object]:
    """Expose the same non-sensitive empty info object by default as the Java actuator."""

    return {}


@app.get("/actuator/prometheus")
def prometheus_metrics() -> Response:
    """Expose low-cardinality process metrics without identity, document, query, or trace labels."""

    from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/api/auth/csrf")
def get_csrf(request: Request) -> dict[str, str]:
    """Return the active server-side CSRF token and required header name."""

    return csrf_token(request)


@app.post("/api/auth/login")
def auth_login(request: Request, response: Response, body: LoginBody) -> dict[str, object]:
    """Authenticate a fixed demo identity and rotate its server-side session."""

    identity = login(request, response, body.username)
    session_id = request.state.rotated_session_id
    set_session_cookie(response, session_id)
    return identity.as_json()


@app.get("/api/auth/me")
def auth_me(request: Request) -> dict[str, object]:
    """Return the trusted identity attached to the authenticated session."""

    return current_identity(request).as_json()


@app.post("/api/auth/logout", status_code=204)
def auth_logout(request: Request, response: Response) -> Response:
    """Invalidate the active session after CSRF validation."""

    logout(request, response)
    response.delete_cookie("PYTHON_RAG_SESSION", httponly=True, samesite="lax", path="/")
    response.status_code = 204
    return response


@app.post("/api/chat")
def chat(request: Request, body: ChatBody) -> dict[str, object]:
    """Run secure retrieval, evidence gating, generation, and citation reauthorization."""

    identity = current_identity(request)
    require_csrf(request)
    started = monotonic()
    trace_id = uuid4()
    retrieval_ms = generation_ms = 0
    result = None
    context_entries: list[dict[str, Any]] = []
    provider: DisabledChatProvider | DeepSeekChatProvider = DisabledChatProvider()
    try:
        provider = chat_provider()
        retrieval_started = monotonic()
        result = retriever.retrieve(body.message, identity.roles)
        retrieval_ms = int((monotonic() - retrieval_started) * 1000)
        if not result.sufficient:
            answer = "I could not find sufficiently relevant authorized evidence to answer that question."
            citations: list[dict[str, Any]] = []
            grounding = "INSUFFICIENT_EVIDENCE"
        else:
            prompt_context, context_entries = build_context(result.candidates)
            if not provider.enabled:
                citations = reauthorize(storage, context_entries, identity.roles)
                if len(citations) != len(context_entries):
                    answer, citations, grounding = (
                        "Authorized source evidence changed during retrieval. Please retry.",
                        [],
                        "INSUFFICIENT_EVIDENCE",
                    )
                else:
                    answer = (
                        "Retrieval-only mode: answer generation is disabled. Review these authorized sources."
                    )
                    grounding = "RETRIEVAL_ONLY"
            else:
                generation_started = monotonic()
                try:
                    generated = answer_with_provider(provider, body.message, prompt_context)
                finally:
                    generation_ms = int((monotonic() - generation_started) * 1000)
                answer, cited, _invalid = validate_citations(generated, context_entries)
                citations = reauthorize(storage, cited, identity.roles)
                if not citations or len(citations) != len(cited):
                    answer, citations, grounding = (
                        "Citations identify supplied sources only; no answer claim was validated.",
                        [],
                        "INSUFFICIENT_EVIDENCE",
                    )
                else:
                    grounding = "CITATIONS_PRESENT"
        public = [public_citation(entry) for entry in citations]
        response: dict[str, object] = {
            "answer": answer,
            "citations": public,
            "traceId": str(trace_id),
            "groundingStatus": grounding,
        }
        _persist_chat_trace(
            trace_id,
            identity.username,
            body.message,
            retrieval_ms,
            generation_ms,
            int((monotonic() - started) * 1000),
            result,
            len(context_entries),
            len(public),
            grounding,
            "SUCCEEDED",
            None,
            provider,
        )
        return response
    except ValueError as error:
        raise ApiError(400, "INVALID_REQUEST", "The chat request is invalid") from error
    except PermanentProviderFailure as error:
        _persist_chat_trace(
            trace_id,
            identity.username,
            body.message,
            retrieval_ms,
            generation_ms,
            int((monotonic() - started) * 1000),
            result,
            len(context_entries),
            0,
            "REQUEST_FAILED",
            "FAILED",
            "PROVIDER_REQUEST_REJECTED",
            provider,
        )
        raise ApiError(
            502, "CHAT_PROVIDER_REJECTED", "The answer provider could not process the request"
        ) from error
    except Exception as error:
        _persist_chat_trace(
            trace_id,
            identity.username,
            body.message,
            retrieval_ms,
            generation_ms,
            int((monotonic() - started) * 1000),
            result,
            len(context_entries),
            0,
            "REQUEST_FAILED",
            "FAILED",
            "PROVIDER_UNAVAILABLE"
            if isinstance(error, TransientProviderFailure)
            else "INTERNAL_REQUEST_ERROR",
            provider,
        )
        if isinstance(error, TransientProviderFailure):
            code, status, message = (
                "CHAT_PROVIDER_UNAVAILABLE",
                503,
                "The answer provider is temporarily unavailable",
            )
        elif isinstance(error, ApiError):
            raise error
        else:
            code, status, message = "CHAT_FAILED", 500, "The chat request could not be completed"
        raise ApiError(status, code, message) from error


def _persist_chat_trace(
    trace_id: UUID,
    username: str,
    message: str,
    retrieval_ms: int,
    generation_ms: int,
    total_ms: int,
    result: Any,
    context_count: int,
    citation_count: int,
    grounding: str,
    status: str,
    error: str | None,
    provider: Any,
) -> None:
    """Persist hashed query and bounded operational fields best-effort."""

    try:
        storage.save_trace(
            {
                "id": trace_id,
                "user": username,
                "hash": sha256(message.encode("utf-8")).hexdigest(),
                "retrieval": max(0, retrieval_ms),
                "generation": max(0, generation_ms),
                "total": max(0, total_ms),
                "vector_count": result.vector_count if result else 0,
                "keyword_count": result.keyword_count if result else 0,
                "exact_count": result.exact_count if result else 0,
                "context_count": context_count,
                "citations": citation_count,
                "sufficient": bool(result and result.sufficient),
                "reason": result.reason if result else "REQUEST_FAILED",
                "provider": provider.provider,
                "model": provider.model,
                "grounding": grounding,
                "status": status,
                "error": error,
                "mode": "GENERATED" if provider.enabled else "RETRIEVAL_ONLY",
            }
        )
    except Exception:
        LOGGER.warning("Unable to persist RAG trace %s", trace_id)


@app.get("/api/sources/{chunk_id}")
def source(request: Request, chunk_id: str) -> dict[str, object]:
    """Resolve an active source only after applying current SQL role/version authorization."""

    identity = current_identity(request)
    if not identity.roles:
        raise ApiError(403, "FORBIDDEN", "Access is denied")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", chunk_id):
        raise ApiError(404, "NOT_FOUND", "")
    result = storage.source(chunk_id, identity.roles)
    if result is None:
        raise ApiError(404, "NOT_FOUND", "")
    return result


@app.get("/api/documents")
def documents(request: Request) -> list[dict[str, object]]:
    """List visible document metadata under role/admin scope."""

    identity = current_identity(request)
    return storage.list_documents(identity.roles, identity.document_admin)


@app.get("/api/documents/{document_id}/versions")
def document_versions(request: Request, document_id: str) -> list[dict[str, object]]:
    """List version metadata when the caller can access the document."""

    identity = current_identity(request)
    if not identity.document_admin:
        with engine.connect() as connection:
            allowed = connection.execute(
                text("""SELECT EXISTS(SELECT 1 FROM document_permissions WHERE document_id=:id
                    AND allowed_role = ANY(:roles))"""),
                {"id": document_id, "roles": list(identity.roles)},
            ).scalar_one()
        if not allowed:
            return []
    return storage.list_versions(document_id, identity.roles, identity.document_admin)


def require_admin(request: Request) -> None:
    """Require the explicit demo document-management capability."""

    if not current_identity(request).document_admin:
        raise ApiError(403, "FORBIDDEN", "Access is denied")


@app.post("/api/documents", status_code=200)
async def upload_document(
    request: Request,
    file: UploadFile = File(...),
    form_document_id: str = Form(..., alias="documentId"),
    title: str = Form(...),
    department: str = Form(...),
    classification: str = Form(...),
    allowed_roles: str = Form(..., alias="allowedRoles"),
) -> dict[str, object]:
    """Validate, parse, embed, and atomically activate a new document version."""

    return await _index_upload(request, file, "", title, department, classification, allowed_roles)


@app.post("/api/documents/{path_document_id}/reindex", status_code=200)
async def reindex_document(
    request: Request,
    path_document_id: str,
    file: UploadFile = File(...),
    title: str = Form(...),
    department: str = Form(...),
    classification: str = Form(...),
    allowed_roles: str = Form(..., alias="allowedRoles"),
) -> dict[str, object]:
    """Create a new staged version of the path-selected logical document."""

    form_document_id = (await request.form()).get("documentId")
    if form_document_id is not None and form_document_id != path_document_id:
        raise ApiError(400, "INVALID_REQUEST", "Path document ID does not match multipart document ID")
    return await _index_upload(
        request, file, path_document_id, title, department, classification, allowed_roles
    )


async def _index_upload(
    request: Request,
    file: UploadFile,
    path_or_form_document_id: str,
    title: str,
    department: str,
    classification: str,
    allowed_roles: str,
) -> dict[str, object]:
    """Share validated ingestion work between the create and path-based reindex routes."""

    require_csrf(request)
    require_admin(request)
    form = await request.form()
    form_document_id = form.get("documentId")
    document_id = path_or_form_document_id or str(form_document_id or "")
    payload = file.file.read(get_settings().maximum_upload_bytes + 1)
    data = normalize_upload(
        document_id, title, department, classification, allowed_roles, file.filename or "", payload
    )

    def process() -> tuple[list[Any], list[list[float]]]:
        chunks = chunk_document(parse_document(data["filename"], data["content"]))
        return chunks, embeddings.embed([chunk.text for chunk in chunks])

    try:
        return storage.ingest(data, process)
    except ApiError:
        raise
    except TransientProviderFailure as error:
        raise ApiError(
            503, "INGESTION_PROVIDER_UNAVAILABLE", "Embedding provider is temporarily unavailable"
        ) from error
    except Exception as error:
        raise ApiError(422, "INGESTION_UNPROCESSABLE", "Document indexing failed") from error


@app.post("/api/documents/{document_id}/disable", status_code=204)
def disable_document(request: Request, document_id: str) -> Response:
    """Disable retrieval for an active document after admin and CSRF checks."""

    require_csrf(request)
    require_admin(request)
    if not storage.mutate_document(document_id, "disable"):
        raise ApiError(404, "NOT_FOUND", "")
    return Response(status_code=204)


@app.delete("/api/documents/{document_id}", status_code=204)
def delete_document(request: Request, document_id: str) -> Response:
    """Soft-delete a document after admin and CSRF checks."""

    require_csrf(request)
    require_admin(request)
    if not storage.mutate_document(document_id, "delete"):
        raise ApiError(404, "NOT_FOUND", "")
    return Response(status_code=204)


@app.post("/api/documents/{document_id}/restore", status_code=204)
def restore_document(request: Request, document_id: str) -> Response:
    """Explicitly restore an inactive document after admin and CSRF checks."""

    require_csrf(request)
    require_admin(request)
    if not storage.mutate_document(document_id, "restore"):
        raise ApiError(404, "NOT_FOUND", "")
    return Response(status_code=204)


@app.get("/api/traces/{trace_id}")
def get_trace(request: Request, trace_id: UUID) -> dict[str, object]:
    """Return trace details scoped to the owning identity or explicit document admin."""

    identity = current_identity(request)
    result = storage.trace(trace_id, identity.username, identity.document_admin)
    if result is None:
        raise ApiError(404, "NOT_FOUND", "")
    return result


@app.post("/api/evaluations/run")
def run_evaluation(request: Request, split: Literal["all", "development"] = "all") -> dict[str, object]:
    """Run development-only diagnostics or the combined development/held-out evaluation."""

    require_csrf(request)
    require_admin(request)
    if split not in {"all", "development"}:
        raise ApiError(400, "INVALID_REQUEST", "Evaluation split must be all or development")
    try:
        return evaluate_offline(
            current_identity(request).username,
            storage=storage,
            embeddings=embeddings,
            include_heldout=split == "all",
        )
    except ValueError as error:
        raise ApiError(400, "INVALID_REQUEST", "The deterministic evaluation request is invalid") from error
    except Exception as error:
        raise ApiError(503, "EVALUATION_UNAVAILABLE", "Offline evaluation could not be completed") from error


@app.get("/api/evaluations")
def evaluations(request: Request, limit: int = 10) -> list[dict[str, object]]:
    """List recent evaluation summaries after validating the existing 1-100 limit."""

    identity = current_identity(request)
    if not identity.document_admin:
        raise ApiError(403, "FORBIDDEN", "Access is denied")
    if not 1 <= limit <= 100:
        raise ApiError(400, "INVALID_REQUEST", "Evaluation limit must be between 1 and 100")
    return recent_runs(storage, identity.username, limit)


@app.get("/api/evaluations/{run_id}")
def evaluation(request: Request, run_id: UUID) -> dict[str, object]:
    """Return persisted evaluation details including the full diagnostic payload."""

    identity = current_identity(request)
    if not identity.document_admin:
        raise ApiError(403, "FORBIDDEN", "Access is denied")
    result = get_run(storage, run_id, identity.username)
    if result is None:
        raise ApiError(404, "NOT_FOUND", "")
    return result
