"""PostgreSQL/pgvector integration coverage for ACL retrieval and atomic document lifecycle."""

from uuid import uuid4

from sqlalchemy import text

from app.documents import Chunk
from app.errors import ApiError
from app.storage import Storage


def process(body: str, vector: list[float] | None = None):
    """Build deterministic synthetic chunks and 768-dimensional vectors."""

    coordinates = vector or [1.0] + [0.0] * 767
    return [Chunk(0, body, "Policy", None)], [coordinates]


def test_staged_activation_source_reauthorization_and_immediate_disable(
    clean_postgres, synthetic_data
) -> None:
    """Chunks become current atomically and source SQL stops resolving after disable."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-acl-active")
    first = storage.ingest(data, lambda: process("HTTP 429 clients retry after backoff"))
    source_id = f"{data['document_id']}-v1-c0"
    assert storage.source(source_id, frozenset({"ENGINEERING"})) is not None
    assert storage.source(source_id, frozenset({"HR"})) is None
    assert storage.list_documents(frozenset({"HR"}), False) == []
    assert storage.mutate_document(data["document_id"], "disable")
    assert storage.source(source_id, frozenset({"ENGINEERING"})) is None
    assert first["version"] == 1


def test_failed_reindex_keeps_healthy_version_and_duplicate_acl_update_skips_version(
    clean_postgres, synthetic_data
) -> None:
    """A provider failure cannot swap active pointers; identical bytes update only metadata/ACL."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-version-safe")
    first = storage.ingest(data, lambda: process("old active policy"))
    changed = synthetic_data(data["document_id"], b"different bytes")

    def fail():
        raise RuntimeError("synthetic provider outage")

    try:
        storage.ingest(changed, fail)
    except ApiError as error:
        assert error.status == 422
    else:
        raise AssertionError("failed indexing unexpectedly succeeded")
    with clean_postgres.connect() as connection:
        active = connection.execute(
            text("SELECT current_version_id FROM documents WHERE id=:id"), {"id": data["document_id"]}
        ).scalar_one()
        assert str(active) == first["versionId"]
        assert (
            connection.execute(
                text("SELECT index_status FROM document_versions WHERE id=:id"), {"id": active}
            ).scalar_one()
            == "ACTIVE"
        )

    updated = {**data, "title": "Updated policy", "roles": {"HR"}}
    duplicate = storage.ingest(updated, lambda: (_ for _ in ()).throw(AssertionError("must not embed")))
    assert duplicate["duplicate"] is True
    assert duplicate["metadataUpdated"] is True
    assert storage.source(f"{data['document_id']}-v1-c0", frozenset({"ENGINEERING"})) is None
    assert storage.source(f"{data['document_id']}-v1-c0", frozenset({"HR"})) is not None

    seed_data = {**synthetic_data("doc-seed-provider-retry"), "roles": {"PUBLIC"}}

    def provider_unavailable():
        from app.providers import TransientProviderFailure

        raise TransientProviderFailure("synthetic transient outage")

    try:
        storage.ingest(seed_data, provider_unavailable, seed=True)
    except Exception:
        pass
    with clean_postgres.connect() as connection:
        repairable = connection.execute(
            text("SELECT seed_repairable FROM documents WHERE id=:id"),
            {"id": seed_data["document_id"]},
        ).scalar_one()
        assert repairable is True
    assert storage.ingest(seed_data, lambda: process("recovered seed"), seed=True)["version"] == 1

    parse_failure_data = {**synthetic_data("doc-seed-parse-failure"), "roles": {"PUBLIC"}}
    try:
        storage.ingest(
            parse_failure_data,
            lambda: (_ for _ in ()).throw(ApiError(422, "INGESTION_UNPROCESSABLE", "bad parse")),
            seed=True,
        )
    except ApiError:
        pass
    with clean_postgres.connect() as connection:
        parse_repairable = connection.execute(
            text("SELECT seed_repairable FROM documents WHERE id=:id"),
            {"id": parse_failure_data["document_id"]},
        ).scalar_one()
        assert parse_repairable is False


def test_explicit_restore_and_seed_repair_state_are_distinct(clean_postgres, synthetic_data) -> None:
    """Admin disable/delete never auto-restore, while only explicitly repairable seed failures retry."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-admin-inactive")
    storage.ingest(data, lambda: process("stable policy"))
    assert storage.mutate_document(data["document_id"], "delete")
    with __import__("pytest").raises(ApiError):
        storage.ingest(data, lambda: process("changed policy"), seed=True)
    assert storage.mutate_document(data["document_id"], "restore")
    assert storage.source(f"{data['document_id']}-v1-c0", frozenset({"ENGINEERING"})) is not None

    seed_data = synthetic_data("doc-seed-repair")

    def failed_process():
        raise RuntimeError("first seed attempt")

    try:
        storage.ingest(seed_data, failed_process, seed=True)
    except ApiError:
        pass
    with clean_postgres.begin() as connection:
        connection.execute(
            text("UPDATE documents SET seed_repairable=true WHERE id=:id"), {"id": seed_data["document_id"]}
        )
    repaired = storage.ingest(seed_data, lambda: process("repaired seed"), seed=True)
    assert repaired["version"] == 1
    assert storage.source(f"{seed_data['document_id']}-v1-c0", frozenset({"ENGINEERING"})) is not None


def test_retrieval_candidate_sql_filters_acl_before_vector_keyword_and_exact(
    clean_postgres, synthetic_data
) -> None:
    """Vector, FTS, and exact SQL paths return only role-authorized active-version candidates."""

    storage = Storage(clean_postgres)
    allowed = synthetic_data("doc-eng-visible")
    denied = {**synthetic_data("doc-hr-hidden"), "roles": {"HR"}, "department": "HR"}
    body = "GW-204 gateway returned HTTP 429; clients retry with exponential backoff"
    storage.ingest(allowed, lambda: process(body))
    storage.ingest(denied, lambda: process(body))
    vector, keyword, exact = storage.reserve_retrieval(
        "What does GW-204 mean after HTTP 429?",
        frozenset({"ENGINEERING"}),
        [1.0] + [0.0] * 767,
        ["gw-204", "429"],
        10,
    )
    assert {row["document_id"] for rows in (vector, keyword, exact) for row in rows} == {"doc-eng-visible"}


def test_fts_coverage_uses_english_stemming_stopwords_and_rollback_normalization(
    clean_postgres, synthetic_data
) -> None:
    """Keyword coverage matches Java's PostgreSQL English tsvector term calculation."""

    storage = Storage(clean_postgres)
    data = synthetic_data(
        "doc-deployment-rollback",
        b"# Deployment rollback\nThe deployment procedure requires rollback after failed readiness checks.",
    )
    storage.ingest(
        data, lambda: process("Deployment procedure requires rollback after failed readiness checks")
    )
    _, keyword, _ = storage.reserve_retrieval(
        "When should a deployment be rolled back?",
        frozenset({"ENGINEERING"}),
        [1.0] + [0.0] * 767,
        [],
        10,
    )
    assert keyword
    assert keyword[0]["coverage"] == 1.0


def test_evaluation_run_persists_and_reads_complete_diagnostic_contract(clean_postgres) -> None:
    """The versioned evaluation tables round-trip summary metrics and scenario diagnostics."""

    from datetime import UTC, datetime

    from app.evaluation import get_run, recent_runs, save_run

    storage = Storage(clean_postgres)
    now = datetime.now(UTC)
    scenario = {
        "scenarioId": "synthetic-it-001",
        "category": "FACT_LOOKUP",
        "expectedDocumentIds": ["doc-synthetic"],
        "retrievedDocumentIds": ["doc-synthetic"],
        "missingDocumentIds": [],
        "forbiddenDocumentIds": [],
        "expectedVersion": 1,
        "retrievedVersions": {"doc-synthetic": 1},
        "reciprocalRank": 1.0,
        "recallAt5": 1.0,
        "expectedEvidenceGate": True,
        "expectedRelevantCandidate": True,
        "aclLeakage": False,
        "freshnessCorrect": True,
        "citationIdValidationCorrect": True,
        "evidenceGateCorrect": True,
        "gateReason": "LEXICAL_AND_RELEVANCE_MATCH",
        "gateDiagnostics": {"candidateCount": 1},
        "passed": True,
    }
    metrics = {
        "recallAt5": 1.0,
        "meanReciprocalRank": 1.0,
        "aclLeakageCount": 0,
        "freshnessAccuracy": 1.0,
        "citationIdValidationAccuracy": 1.0,
        "evidenceGateAccuracy": 1.0,
        "evidenceGateFalseAbstentionRate": 0.0,
        "evidenceGateFalseAdmissionRate": 0.0,
        "releaseQualityPassed": True,
        "gateAblationMetrics": {"VECTOR_ONLY": {"accuracy": 1.0}},
        "prefixShadowMetrics": {
            "available": False,
            "recallAt5": 0.0,
            "meanReciprocalRank": 0.0,
            "vectorGateAccuracy": 0.0,
            "falseAbstentionCount": 0,
            "falseAdmissionCount": 0,
        },
    }
    run = {
        "runId": uuid4(),
        "requestedBy": "carol",
        "datasetVersion": "synthetic-it-v1",
        "method": "OFFLINE_RETRIEVAL",
        "embeddingModelId": "test-model",
        "retrievalConfiguration": {},
        "scenarioCount": 1,
        "metrics": metrics,
        "status": "SUCCEEDED",
        "startedAt": now,
        "completedAt": now,
        "scenarios": [scenario],
    }

    save_run(storage, run)
    loaded = get_run(storage, run["runId"], "carol")
    assert loaded is not None
    assert loaded["metrics"]["releaseQualityPassed"] is True
    assert loaded["scenarios"][0]["gateDiagnostics"] == {"candidateCount": 1}
    assert recent_runs(storage, "carol", 1)[0]["runId"] == run["runId"]
