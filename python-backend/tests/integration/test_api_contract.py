"""HTTP contract integration tests using server-side sessions and real PostgreSQL state."""

from fastapi.testclient import TestClient

import app.main as main
from app.documents import Chunk
from app.storage import Storage


class FixedEmbeddings:
    """Deterministic 768-dimensional provider fixture for chat and upload HTTP tests."""

    model = "test-embedding"

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [[1.0] + [0.0] * 767 for _ in texts]


def test_auth_document_lifecycle_source_and_trace_error_shapes(
    clean_postgres, synthetic_data, monkeypatch
) -> None:
    """Cookie/CSRF auth and lifecycle APIs preserve identity, role filtering, and status contracts."""

    storage = Storage(clean_postgres)
    monkeypatch.setattr(main, "storage", storage)
    monkeypatch.setattr(main, "embeddings", FixedEmbeddings())
    monkeypatch.setattr(main, "retriever", object())
    with TestClient(main.app) as client:
        csrf = client.get("/api/auth/csrf")
        assert csrf.status_code == 200
        token = csrf.json()["token"]
        assert csrf.json()["headerName"] == "X-CSRF-TOKEN"
        assert (
            client.post(
                "/api/auth/login", json={"username": "alice"}, headers={"X-CSRF-TOKEN": token}
            ).status_code
            == 200
        )
        assert client.get("/api/auth/me").json() == {
            "username": "alice",
            "roles": ["ENGINEERING", "PUBLIC"],
            "documentAdmin": False,
        }
        assert client.post("/api/auth/logout").status_code == 403
        new_csrf = client.get("/api/auth/csrf").json()["token"]
        assert client.post("/api/auth/logout", headers={"X-CSRF-TOKEN": new_csrf}).status_code == 204
        assert client.get("/api/auth/me").status_code == 401
        assert client.post("/api/chat", json={"message": "   "}).status_code == 400
        assert client.post("/api/chat", json={}).json() == {
            "code": "INVALID_REQUEST",
            "message": "Request validation failed",
        }
        new_csrf = client.get("/api/auth/csrf").json()["token"]
        assert (
            client.post(
                "/api/auth/login", json={"username": "mallory"}, headers={"X-CSRF-TOKEN": new_csrf}
            ).status_code
            == 401
        )

        admin_csrf = client.get("/api/auth/csrf").json()["token"]
        assert (
            client.post(
                "/api/auth/login", json={"username": "carol"}, headers={"X-CSRF-TOKEN": admin_csrf}
            ).status_code
            == 200
        )
        data = {**synthetic_data("doc-api-contract"), "roles": {"PUBLIC"}}
        storage.ingest(
            data, lambda: ([Chunk(0, "contract source text", "Policy", None)], FixedEmbeddings().embed(["x"]))
        )
        assert client.get("/api/documents").json()[0]["id"] == "doc-api-contract"
        assert client.get("/api/documents/doc-api-contract/versions").json()[0]["version"] == 1
        source_id = "doc-api-contract-v1-c0"
        assert client.get(f"/api/sources/{source_id}").json()["text"] == "contract source text"
        assert client.get("/api/sources/not.valid").status_code == 404
        token = client.get("/api/auth/csrf").json()["token"]
        assert (
            client.post(
                "/api/documents/doc-api-contract/disable", headers={"X-CSRF-TOKEN": token}
            ).status_code
            == 204
        )
        assert client.get(f"/api/sources/{source_id}").status_code == 404


def test_upload_contract_and_chat_retrieval_only_are_wired(clean_postgres, monkeypatch) -> None:
    """Multipart upload validates and indexes; weak chat is a successful citation-free abstention."""

    storage = Storage(clean_postgres)
    embeddings = FixedEmbeddings()
    monkeypatch.setattr(main, "storage", storage)
    monkeypatch.setattr(main, "embeddings", embeddings)
    from app.retrieval import HybridRetriever

    monkeypatch.setattr(main, "retriever", HybridRetriever(storage, embeddings))
    monkeypatch.setattr(
        main,
        "chat_provider",
        lambda: __import__("app.providers", fromlist=["DisabledChatProvider"]).DisabledChatProvider(),
    )
    with TestClient(main.app) as client:
        token = client.get("/api/auth/csrf").json()["token"]
        client.post("/api/auth/login", json={"username": "carol"}, headers={"X-CSRF-TOKEN": token})
        token = client.get("/api/auth/csrf").json()["token"]
        upload = client.post(
            "/api/documents",
            data={
                "documentId": "doc-http-upload",
                "title": "HTTP guide",
                "department": "ENGINEERING",
                "classification": "INTERNAL",
                "allowedRoles": "ENGINEERING",
            },
            files={"file": ("guide.txt", b"HTTP 429 clients retry after backoff", "text/plain")},
            headers={"X-CSRF-TOKEN": token},
        )
        assert upload.status_code == 200
        assert upload.json()["version"] == 1
        chat_token = client.get("/api/auth/csrf").json()["token"]
        response = client.post(
            "/api/chat",
            json={"message": "a sufficiently unsupported question"},
            headers={"X-CSRF-TOKEN": chat_token},
        )
        assert response.status_code == 200
        assert response.json()["groundingStatus"] == "INSUFFICIENT_EVIDENCE"
        assert response.json()["citations"] == []
