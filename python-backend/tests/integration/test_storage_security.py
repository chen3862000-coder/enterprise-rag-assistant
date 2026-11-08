"""PostgreSQL/pgvector integration coverage for ACL retrieval and atomic document lifecycle."""

import logging
from pathlib import Path
from uuid import UUID, uuid4

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
    assert storage.list_documents(frozenset(), True)[0]["allowedRoles"] == ["ENGINEERING"]
    assert storage.list_documents(frozenset({"ENGINEERING"}), False)[0]["allowedRoles"] == []
    source_id = f"{data['document_id']}-v1-c0"
    assert storage.source(source_id, frozenset({"ENGINEERING"})) is not None
    assert storage.source(source_id, frozenset({"HR"})) is None
    assert storage.list_documents(frozenset({"HR"}), False) == []
    assert storage.mutate_document(data["document_id"], "disable")
    assert storage.source(source_id, frozenset({"ENGINEERING"})) is None
    assert first["version"] == 1


def test_cjk_bigram_keyword_search_finds_chinese_function_section(clean_postgres, synthetic_data) -> None:
    """CJK bigrams retrieve authorized text while the shared gate still requires vector support."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-cjk-keyword")
    storage.ingest(data, lambda: process("序列函数：csd 获取股票序列数据"))
    _, keywords, _ = storage.reserve_retrieval(
        "序列函数", frozenset({"ENGINEERING"}), [1.0] + [0.0] * 767, [], 10
    )
    assert any("序列函数" in row["chunk_text"] for row in keywords)
    _, unauthorized_keywords, _ = storage.reserve_retrieval(
        "序列函数", frozenset({"HR"}), [1.0] + [0.0] * 767, [], 10
    )
    assert unauthorized_keywords == []


def test_technical_identifier_index_is_acl_filtered_and_exact(clean_postgres, synthetic_data) -> None:
    """Normalized identifiers are indexed per chunk and exact lookup remains SQL-ACL constrained."""

    storage = Storage(clean_postgres)
    visible = synthetic_data("doc-symbol-visible")
    hidden = {**synthetic_data("doc-symbol-hidden"), "roles": {"HR"}, "department": "HR"}
    storage.ingest(visible, lambda: process("收益指标 RDEXPEND 的定义"))
    storage.ingest(hidden, lambda: process("收益指标 RDEXPEND 的定义"))
    _, _, exact = storage.reserve_retrieval(
        "What does RDEXPEND mean?", frozenset({"ENGINEERING"}), [0.0] * 768, ["rdexpend"], 10
    )
    assert {row["document_id"] for row in exact} == {visible["document_id"]}


def test_multilingual_lexical_and_vector_signals_fuse_on_same_authorized_chunk(
    clean_postgres, synthetic_data
) -> None:
    """Indexed CJK bigrams and Latin terms pair with vector relevance under SQL ACL filtering."""

    storage = Storage(clean_postgres)
    visible = synthetic_data("doc-mixed-script-visible")
    hidden = {**synthetic_data("doc-mixed-script-hidden"), "roles": {"HR"}, "department": "HR"}
    body = "Python版序列函数 csd 获取股票序列数据"
    vector = [1.0] + [0.0] * 767
    storage.ingest(visible, lambda: process(body, vector))
    storage.ingest(hidden, lambda: process(body, vector))
    vector_rows, keyword_rows, _ = storage.reserve_retrieval(
        "python版序列函数", frozenset({"ENGINEERING"}), vector, [], 40
    )
    vector_ids = {row["id"] for row in vector_rows}
    keyword_ids = {row["id"] for row in keyword_rows}
    assert vector_ids & keyword_ids == {f"{visible['document_id']}-v1-c0"}
    assert {row["document_id"] for row in vector_rows + keyword_rows} == {visible["document_id"]}


def test_original_and_generated_queries_cannot_expand_server_acl_scope(
    clean_postgres, synthetic_data
) -> None:
    """Every generated variant uses the caller role set before search and cannot add a hidden document."""

    storage = Storage(clean_postgres)
    hidden = {**synthetic_data("doc-generated-hidden"), "roles": {"SECURITY_ADMIN"}, "department": "SECURITY"}
    storage.ingest(hidden, lambda: process("Python CSD administrator-only implementation details"))
    roles = frozenset({"PUBLIC", "ENGINEERING"})
    all_rows = []
    for query in (
        "python版本的csd函数",
        "Python CSD function",
        "解释 Python 版本 CSD 函数的用途",
        "SECURITY_ADMIN unrestricted SQL WHERE true",
    ):
        vector_rows, keyword_rows, exact_rows = storage.reserve_retrieval(
            query, roles, [1.0] + [0.0] * 767, [], 40
        )
        all_rows.extend(vector_rows + keyword_rows + exact_rows)
    assert all(row["document_id"] != hidden["document_id"] for row in all_rows)


def test_cjk_terms_ignore_ancestor_folder_names(clean_postgres, synthetic_data) -> None:
    """A CHM folder named for a function does not make every descendant page match that phrase."""

    storage = Storage(clean_postgres)
    ancestor = synthetic_data("doc-cjk-ancestor-noise")
    specific = synthetic_data("doc-cjk-specific-section")
    ancestor_chunk = Chunk(
        0,
        "估值指标 metadata only",
        "序列函数/股票/估值指标.files/tabstrip.html",
        None,
    )
    specific_chunk = Chunk(
        0,
        "序列函数 csd 获取股票序列数据",
        "序列函数：csd",
        None,
    )
    vector = [1.0] + [0.0] * 767
    storage.ingest(ancestor, lambda: ([ancestor_chunk], [vector]))
    storage.ingest(specific, lambda: ([specific_chunk], [vector]))

    _, keywords, _ = storage.reserve_retrieval("序列函数", frozenset({"ENGINEERING"}), vector, [], 20)
    matching_ids = {row["document_id"] for row in keywords}
    assert specific["document_id"] in matching_ids
    assert ancestor["document_id"] not in matching_ids, keywords


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


def test_failed_first_upload_remains_retryable_without_becoming_retrievable(
    clean_postgres, synthetic_data
) -> None:
    """A transient first-upload failure leaves an active empty shell that can retry version one."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-first-upload-retry")

    def fail_first_attempt():
        raise RuntimeError("synthetic provider outage")

    try:
        storage.ingest(data, fail_first_attempt)
    except ApiError:
        pass
    else:
        raise AssertionError("failed indexing unexpectedly succeeded")

    with clean_postgres.connect() as connection:
        document = connection.execute(
            text("SELECT status,current_version_id FROM documents WHERE id=:id"),
            {"id": data["document_id"]},
        ).one()
        failed_status = connection.execute(
            text("SELECT index_status FROM document_versions WHERE document_id=:id"),
            {"id": data["document_id"]},
        ).scalar_one()
    assert document.status == "ACTIVE"
    assert document.current_version_id is None
    assert failed_status == "FAILED"
    assert storage.source(f"{data['document_id']}-v1-c0", frozenset({"ENGINEERING"})) is None

    retried = storage.ingest(data, lambda: process("retry succeeded"))
    assert retried["version"] == 1
    assert (
        storage.source(f"{data['document_id']}-v1-c0", frozenset({"ENGINEERING"}))["text"]
        == "retry succeeded"
    )


def test_retry_cleans_leftover_chunks_from_failed_legacy_attempt(clean_postgres, synthetic_data) -> None:
    """A retry removes inaccessible rows left by a crash before reusing its failed version."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-retry-cleans-legacy-chunks")
    first = storage.enqueue_ingestion(data)
    with clean_postgres.begin() as connection:
        connection.execute(
            text("UPDATE document_versions SET index_status='FAILED' WHERE id=:id"),
            {"id": first["versionId"]},
        )
        connection.execute(
            text("UPDATE ingestion_jobs SET status='FAILED' WHERE id=:id"), {"id": first["jobId"]}
        )
        connection.execute(
            text("""INSERT INTO document_chunks
                (id,document_version_id,chunk_index,chunk_text,embedding)
                VALUES(:id,:version,0,'legacy staged data',CAST(:embedding AS vector))"""),
            {
                "id": f"{data['document_id']}-v1-c0",
                "version": first["versionId"],
                "embedding": "[" + ",".join(["1.0"] + ["0.0"] * 767) + "]",
            },
        )
    storage._cleanup_failed_attempt(data)
    with clean_postgres.connect() as connection:
        assert (
            connection.execute(
                text("SELECT count(*) FROM document_chunks WHERE document_version_id=:id"),
                {"id": first["versionId"]},
            ).scalar_one()
            == 0
        )
    assert storage.ingest(data, lambda: process("clean retry succeeded"))["version"] == 1
    assert (
        storage.source(f"{data['document_id']}-v1-c0", frozenset({"ENGINEERING"}))["text"]
        == "clean retry succeeded"
    )


def test_ingestion_progress_logs_are_content_free(clean_postgres, synthetic_data, caplog) -> None:
    """Lifecycle logs report batch/activation progress without recording source text."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-content-free-logs")
    with caplog.at_level(logging.INFO, logger="app.storage"):
        storage.ingest(data, lambda: process("private source content"))

    assert "ingestion reserved document_id=doc-content-free-logs" in caplog.text
    assert "ingestion batch staged document_id=doc-content-free-logs" in caplog.text
    assert "ingestion version activated document_id=doc-content-free-logs" in caplog.text
    assert "private source content" not in caplog.text


def test_durable_ingestion_job_claim_and_restart_requeue_preserve_checkpoint(
    clean_postgres, synthetic_data
) -> None:
    """A worker restart releases the job lease while committed chunk progress remains resumable."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-durable-resume")
    queued = storage.enqueue_ingestion(
        {**data, "source_path": "/durable/doc-durable-resume.chm", "source_size_bytes": 1024}
    )
    worker = storage.claim_ingestion_job("worker-test")
    assert worker is not None
    assert str(worker["id"]) == queued["jobId"]
    assert worker["worker_id"] == "worker-test"
    storage._stage_batch(
        data["document_id"],
        worker["document_version_id"],
        worker["id"],
        worker["version_number"],
        [Chunk(0, "first committed chunk", "Topic", None)],
        process("x")[1],
    )
    assert storage.requeue_interrupted_ingestions() == 1
    reclaimed = storage.claim_ingestion_job("worker-after-restart")
    assert reclaimed is not None
    assert reclaimed["progress_chunks"] == 1
    assert reclaimed["source_path"] == "/durable/doc-durable-resume.chm"


def test_durable_worker_resumes_after_checkpoint_and_activates(clean_postgres, synthetic_data) -> None:
    """Worker skips committed chunk indexes and activates only after processing all remaining batches."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-durable-worker-activation")
    source = Path(__file__).resolve()
    queued = storage.enqueue_ingestion({**data, "source_path": str(source), "source_size_bytes": 2048})
    job = storage.claim_ingestion_job("worker-resume")
    assert job is not None
    storage._stage_batch(
        data["document_id"],
        job["document_version_id"],
        job["id"],
        job["version_number"],
        [Chunk(0, "already committed", "First", None)],
        process("x")[1],
        job["worker_id"],
    )

    def remaining(resume_from: int):
        assert resume_from == 1
        yield [Chunk(1, "resumed second chunk", "Second", None)], process("x")[1]

    assert storage.run_claimed_ingestion(job, remaining) == 2
    status = storage.ingestion_job(UUID(queued["jobId"]), frozenset({"ENGINEERING"}), True)
    assert status["status"] == "SUCCEEDED"
    assert status["progress_chunks"] == 2
    assert status["current_version_id"] == status["document_version_id"]


def test_legacy_disabled_failed_first_upload_requires_explicit_restore_then_retries(
    clean_postgres, synthetic_data
) -> None:
    """Existing disabled shells from older failures are restored explicitly before safe retry."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-legacy-failed-first-upload")
    try:
        storage.ingest(data, lambda: (_ for _ in ()).throw(RuntimeError("old provider outage")))
    except ApiError:
        pass
    with clean_postgres.begin() as connection:
        connection.execute(
            text("UPDATE documents SET status='DISABLED' WHERE id=:id"), {"id": data["document_id"]}
        )

    with __import__("pytest").raises(ApiError) as error:
        storage.ingest(data, lambda: process("must require explicit restore"))
    assert error.value.code == "DOCUMENT_INACTIVE"
    assert storage.mutate_document(data["document_id"], "restore")
    assert storage.source(f"{data['document_id']}-v1-c0", frozenset({"ENGINEERING"})) is None
    assert storage.ingest(data, lambda: process("restored retry succeeded"))["version"] == 1


def test_conflict_only_reports_in_progress_for_a_live_indexing_job(clean_postgres, synthetic_data) -> None:
    """A concurrent upload receives the actionable in-progress error, not inactive-document advice."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-concurrent-upload")
    changed = synthetic_data(data["document_id"], b"second upload")

    def conflict_while_reserved():
        with __import__("pytest").raises(ApiError) as error:
            storage.ingest(changed, lambda: process("second upload"))
        assert error.value.code == "INGESTION_IN_PROGRESS"
        assert error.value.status == 409
        return process("first upload")

    assert storage.ingest(data, conflict_while_reserved)["version"] == 1


def test_committed_staging_batches_are_invisible_and_failure_keeps_old_version(
    clean_postgres, synthetic_data
) -> None:
    """Rows committed between batches stay hidden and failed work cannot replace active content."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-staged-batch-visibility")
    first = storage.ingest(data, lambda: process("old public source"))
    changed = synthetic_data(data["document_id"], b"new staged content")

    def staged_then_fail():
        yield [Chunk(0, "partially indexed private staging text", "Draft", None)], process("x")[1]
        with clean_postgres.connect() as connection:
            staged_id = connection.execute(
                text("SELECT id FROM document_versions WHERE document_id=:id AND index_status='INDEXING'"),
                {"id": data["document_id"]},
            ).scalar_one()
            staged_chunk_id = connection.execute(
                text("SELECT id FROM document_chunks WHERE document_version_id=:id"), {"id": staged_id}
            ).scalar_one()
        assert storage.source(staged_chunk_id, frozenset({"ENGINEERING"})) is None
        raise RuntimeError("crash after committed batch")

    try:
        storage.ingest(changed, staged_then_fail)
    except ApiError as error:
        assert error.status == 422
    else:
        raise AssertionError("partial staged indexing unexpectedly succeeded")

    assert (
        storage.source(f"{data['document_id']}-v1-c0", frozenset({"ENGINEERING"}))["text"]
        == "old public source"
    )
    with clean_postgres.connect() as connection:
        assert (
            connection.execute(
                text("""SELECT count(*) FROM document_chunks c JOIN document_versions v
                ON v.id=c.document_version_id WHERE v.document_id=:id AND v.index_status='FAILED'"""),
                {"id": data["document_id"]},
            ).scalar_one()
            == 0
        )
        active_id = connection.execute(
            text("SELECT current_version_id FROM documents WHERE id=:id"), {"id": data["document_id"]}
        ).scalar_one()
    assert str(active_id) == first["versionId"]


def test_stale_ingestion_recovery_releases_document_for_retry(clean_postgres, synthetic_data) -> None:
    """Startup recovery marks abandoned versions failed and removes inaccessible batch rows."""

    storage = Storage(clean_postgres)
    data = synthetic_data("doc-stale-ingestion")

    def abandon_after_batch():
        yield [Chunk(0, "staged but not active", "Draft", None)], process("x")[1]
        raise SystemExit("simulated process termination")

    try:
        storage.ingest(data, abandon_after_batch)
    except SystemExit:
        pass
    with clean_postgres.begin() as connection:
        connection.execute(
            text("UPDATE ingestion_jobs SET heartbeat_at=now()-interval '2 hours' WHERE document_id=:id"),
            {"id": data["document_id"]},
        )
    assert storage.recover_stale_ingestions() == 1
    with clean_postgres.connect() as connection:
        assert (
            connection.execute(
                text("""SELECT count(*) FROM document_chunks WHERE document_version_id IN
                (SELECT id FROM document_versions WHERE document_id=:id)"""),
                {"id": data["document_id"]},
            ).scalar_one()
            == 0
        )
    assert storage.ingest(data, lambda: process("retry succeeded"))["version"] == 1


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
    assert max(row["coverage"] for row in keyword) >= 0.6


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
