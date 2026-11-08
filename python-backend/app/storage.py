"""PostgreSQL repositories for lifecycle, ACL-filtered retrieval, citations, traces, and evaluations."""

import logging
import re
import unicodedata
from collections.abc import Callable, Iterable
from datetime import UTC, datetime
from pathlib import Path
from time import monotonic
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import Engine, text
from sqlalchemy.engine import Connection

from app.database import engine, transaction
from app.documents import Chunk
from app.errors import ApiError
from app.providers import TransientProviderFailure
from app.settings import get_settings

LOGGER = logging.getLogger(__name__)


class Storage:
    """Explicit SQL repository; each retrieval/source query enforces ACLs in PostgreSQL."""

    def __init__(self, database: Engine = engine) -> None:
        self.database = database

    def list_documents(self, roles: frozenset[str], admin: bool) -> list[dict[str, Any]]:
        """List all admin-visible documents or rows permitted to the caller's roles."""

        sql = """SELECT DISTINCT d.id, d.title, d.department, d.classification, d.status,
                 current_version.version_number,
                 COALESCE(current_version.index_status, latest_version.index_status) AS index_status,
                 CASE WHEN :admin THEN ARRAY(SELECT allowed_role FROM document_permissions p
                     WHERE p.document_id=d.id ORDER BY allowed_role) ELSE ARRAY[]::text[] END AS allowed_roles,
                 active_job.id AS ingestion_job_id,active_job.status AS ingestion_job_status,
                  active_job.progress_chunks,
                  active_job.progress_total_chunks,
                 d.updated_at
                 FROM documents d
                 LEFT JOIN document_versions current_version ON current_version.id=d.current_version_id
                 LEFT JOIN LATERAL (SELECT index_status FROM document_versions
                     WHERE document_id=d.id ORDER BY version_number DESC LIMIT 1) latest_version ON true
                  LEFT JOIN LATERAL (SELECT id,status,progress_chunks,progress_total_chunks FROM ingestion_jobs
                     WHERE document_id=d.id AND status='INDEXING' ORDER BY queued_at LIMIT 1) active_job ON true"""
        parameters: dict[str, Any] = {"admin": admin}
        if not admin:
            sql += " WHERE EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))"
            parameters["roles"] = list(roles)
        sql += " ORDER BY d.title,d.id"
        with self.database.connect() as connection:
            rows = connection.execute(text(sql), parameters).mappings().all()
        return [
            {
                "id": row["id"],
                "title": row["title"],
                "department": row["department"],
                "classification": row["classification"],
                "status": row["status"],
                "currentVersion": row["version_number"],
                "indexStatus": row["index_status"],
                "allowedRoles": row["allowed_roles"],
                "ingestionJobId": str(row["ingestion_job_id"]) if row["ingestion_job_id"] else None,
                "ingestionJobStatus": row["ingestion_job_status"],
                "progressChunks": row["progress_chunks"] or 0,
                "totalChunks": row["progress_total_chunks"],
                "updatedAt": _iso(row["updated_at"]),
            }
            for row in rows
        ]

    def list_versions(self, document_id: str, roles: frozenset[str], admin: bool) -> list[dict[str, Any]]:
        """List versions only when the caller can access the document, unless admin."""

        sql = """SELECT v.id,v.version_number,v.content_hash,v.source_filename,v.effective_at,v.index_status,
                 v.embedding_model_id,v.embedding_dimensions,v.created_at,(d.current_version_id=v.id) AS active
                 FROM document_versions v JOIN documents d ON d.id=v.document_id WHERE d.id=:id"""
        parameters: dict[str, Any] = {"id": document_id}
        if not admin:
            sql += " AND EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))"
            parameters["roles"] = list(roles)
        with self.database.connect() as connection:
            rows = (
                connection.execute(text(sql + " ORDER BY v.version_number DESC"), parameters).mappings().all()
            )
        return [
            {
                "id": str(row["id"]),
                "version": row["version_number"],
                "contentHash": row["content_hash"].strip(),
                "sourceFilename": row["source_filename"],
                "effectiveAt": _iso(row["effective_at"]),
                "indexStatus": row["index_status"],
                "embeddingModelId": row["embedding_model_id"],
                "embeddingDimensions": row["embedding_dimensions"],
                "createdAt": _iso(row["created_at"]),
                "active": row["active"],
            }
            for row in rows
        ]

    def source(self, chunk_id: str, roles: frozenset[str]) -> dict[str, Any] | None:
        """Resolve only an active-version chunk authorized for at least one caller role."""

        sql = text("""SELECT c.id AS chunk_id,d.id AS document_id,d.title,v.version_number,c.heading_path,c.chunk_text
          FROM document_chunks c JOIN document_versions v ON v.id=c.document_version_id
          JOIN documents d ON d.id=v.document_id WHERE c.id=:chunk_id AND d.status='ACTIVE'
          AND d.current_version_id=v.id AND v.index_status='ACTIVE'
          AND EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))""")
        with self.database.connect() as connection:
            row = connection.execute(sql, {"chunk_id": chunk_id, "roles": list(roles)}).mappings().first()
        if row is None:
            return None
        return {
            "chunkId": row["chunk_id"],
            "documentId": row["document_id"],
            "documentTitle": row["title"],
            "version": row["version_number"],
            "section": row["heading_path"],
            "text": row["chunk_text"],
        }

    def is_active_content(self, document_id: str, digest: str) -> bool:
        """Check whether a logical document's active version already has the provided hash."""

        with self.database.connect() as connection:
            result = connection.execute(
                text("""SELECT EXISTS(SELECT 1 FROM documents d JOIN document_versions v
                    ON v.id=d.current_version_id WHERE d.id=:id AND d.status='ACTIVE' AND v.content_hash=:hash)"""),
                {"id": document_id, "hash": digest},
            ).scalar_one()
        return bool(result)

    def mutate_document(self, document_id: str, action: str) -> bool:
        """Apply one explicit admin lifecycle transition and return whether it changed a row."""

        statements = {
            "disable": "UPDATE documents SET status='DISABLED',seed_repairable=false,updated_at=now() WHERE id=:id AND status='ACTIVE'",
            "delete": "UPDATE documents SET status='DELETED',seed_repairable=false,updated_at=now() WHERE id=:id AND status<>'DELETED'",
            "restore": "UPDATE documents SET status='ACTIVE',seed_repairable=false,updated_at=now() WHERE id=:id AND status IN ('DISABLED','DELETED')",
        }
        with transaction(self.database) as connection:
            result = connection.execute(text(statements[action]), {"id": document_id})
        return result.rowcount == 1

    def ingest(
        self,
        data: dict[str, Any],
        process: Callable[
            [],
            Iterable[tuple[list[Chunk], list[list[float]]]] | tuple[list[Chunk], list[list[float]]],
        ],
        seed: bool = False,
        queue_only: bool = False,
    ) -> dict[str, Any]:
        """Reserve, persist, and atomically activate a version under a PostgreSQL advisory lock."""

        document_id = data["document_id"]
        self.recover_stale_ingestions()
        self._cleanup_failed_attempt(data)
        version_id = uuid4()
        job_id = data.get("job_id") or uuid4()
        settings = get_settings()
        reserved = False
        try:
            with transaction(self.database) as connection:
                connection.execute(
                    text("SELECT pg_advisory_xact_lock(hashtextextended(:id,0))"), {"id": document_id}
                )
                document = (
                    connection.execute(
                        text(
                            "SELECT status,current_version_id,seed_managed,seed_repairable FROM documents WHERE id=:id FOR UPDATE"
                        ),
                        {"id": document_id},
                    )
                    .mappings()
                    .first()
                )
                indexing_job = connection.execute(
                    text(
                        "SELECT EXISTS(SELECT 1 FROM ingestion_jobs WHERE document_id=:id AND status='INDEXING')"
                    ),
                    {"id": document_id},
                ).scalar_one()
                if indexing_job and (document is None or document["status"] == "ACTIVE"):
                    raise ApiError(
                        409,
                        "INGESTION_IN_PROGRESS",
                        "A document version is currently being indexed; wait for it to finish before retrying",
                    )
                if document and document["status"] != "ACTIVE":
                    retryable_seed = bool(
                        document["status"] == "DISABLED"
                        and seed
                        and document["seed_managed"]
                        and document["seed_repairable"]
                        and document["current_version_id"] is None
                        and connection.execute(
                            text("""SELECT EXISTS(SELECT 1 FROM document_versions v
                                WHERE v.document_id=:id AND v.index_status='FAILED')"""),
                            {"id": document_id},
                        ).scalar_one()
                    )
                    if not retryable_seed:
                        raise ApiError(
                            409,
                            "DOCUMENT_INACTIVE",
                            "Restore the inactive document before uploading or reindexing it",
                        )
                    connection.execute(
                        text("UPDATE documents SET status='ACTIVE',seed_repairable=false WHERE id=:id"),
                        {"id": document_id},
                    )
                if indexing_job:
                    raise ApiError(
                        409,
                        "INGESTION_IN_PROGRESS",
                        "A document version is currently being indexed; wait for it to finish before retrying",
                    )
                indexing_job = connection.execute(
                    text(
                        "SELECT EXISTS(SELECT 1 FROM ingestion_jobs WHERE document_id=:id AND status='INDEXING')"
                    ),
                    {"id": document_id},
                ).scalar_one()
                if indexing_job:
                    raise ApiError(
                        409,
                        "INGESTION_IN_PROGRESS",
                        "A document version is currently being indexed; wait for it to finish before retrying",
                    )
                document_status_active = bool(document)
                if document_status_active and seed:
                    connection.execute(
                        text("UPDATE documents SET seed_managed=true,seed_repairable=false WHERE id=:id"),
                        {"id": document_id},
                    )
                existing = None
                reused_failed_version = False
                if document:
                    existing = (
                        connection.execute(
                            text("""SELECT v.id,v.version_number,v.index_status,d.current_version_id
                        FROM document_versions v JOIN documents d ON d.id=v.document_id
                        WHERE v.document_id=:id AND v.content_hash=:hash
                        AND (v.index_status IN ('INDEXING','FAILED') OR d.current_version_id=v.id)
                        ORDER BY v.version_number DESC LIMIT 1"""),
                            {"id": document_id, "hash": data["content_hash"]},
                        )
                        .mappings()
                        .first()
                    )
                    if existing and existing["index_status"] == "ACTIVE":
                        changed = self._update_metadata(connection, data, seed=seed)
                        return {
                            "documentId": document_id,
                            "versionId": str(existing["id"]),
                            "jobId": None,
                            "version": existing["version_number"],
                            "chunkCount": connection.execute(
                                text("SELECT count(*) FROM document_chunks WHERE document_version_id=:id"),
                                {"id": existing["id"]},
                            ).scalar_one(),
                            "duplicate": True,
                            "metadataUpdated": changed,
                        }
                    if queue_only and existing and existing["index_status"] == "INDEXING":
                        current_job = connection.execute(
                            text("""SELECT id FROM ingestion_jobs WHERE document_version_id=:id
                                AND status='INDEXING' ORDER BY queued_at DESC LIMIT 1"""),
                            {"id": existing["id"]},
                        ).scalar_one_or_none()
                        if current_job:
                            return {
                                "documentId": document_id,
                                "versionId": str(existing["id"]),
                                "jobId": str(current_job),
                                "version": existing["version_number"],
                                "chunkCount": 0,
                                "duplicate": False,
                                "metadataUpdated": False,
                                "queued": True,
                            }
                    if existing and existing["index_status"] == "FAILED":
                        version_id = existing["id"]
                        previous_job = (
                            connection.execute(
                                text("""SELECT source_path,source_filename,source_size_bytes,progress_chunks,attempt_count
                                FROM ingestion_jobs WHERE document_version_id=:id AND source_path IS NOT NULL
                                ORDER BY queued_at DESC LIMIT 1"""),
                                {"id": version_id},
                            )
                            .mappings()
                            .first()
                        )
                        can_resume = bool(
                            previous_job
                            and previous_job["source_path"]
                            and Path(previous_job["source_path"]).is_file()
                            and previous_job["progress_chunks"] > 0
                        )
                        if can_resume and previous_job is not None:
                            data["resume_chunks"] = previous_job["progress_chunks"]
                            data["source_path"] = previous_job["source_path"]
                            data["filename"] = previous_job["source_filename"]
                            data["source_size_bytes"] = previous_job["source_size_bytes"]
                            data["attempt_count"] = 0
                            connection.execute(
                                text("UPDATE document_versions SET index_status='SUPERSEDED' WHERE id=:id"),
                                {"id": version_id},
                            )
                            version = connection.execute(
                                text(
                                    "SELECT coalesce(max(version_number),0)+1 FROM document_versions WHERE document_id=:id"
                                ),
                                {"id": document_id},
                            ).scalar_one()
                            version_id = uuid4()
                            reused_failed_version = False
                        else:
                            leftovers = connection.execute(
                                text(
                                    "SELECT EXISTS(SELECT 1 FROM document_chunks WHERE document_version_id=:id)"
                                ),
                                {"id": version_id},
                            ).scalar_one()
                            if leftovers:
                                raise ApiError(
                                    409,
                                    "INGESTION_CONFLICT",
                                    "Failed version cleanup is still in progress; retry shortly",
                                )
                            reused_failed_version = True
                            version = existing["version_number"]
                            connection.execute(
                                text("UPDATE document_versions SET index_status='INDEXING' WHERE id=:id"),
                                {"id": version_id},
                            )
                            connection.execute(
                                text("""UPDATE ingestion_jobs SET status='FAILED',completed_at=now(),worker_id=NULL
                                    WHERE document_version_id=:id AND status='INDEXING'"""),
                                {"id": version_id},
                            )
                    else:
                        version = connection.execute(
                            text(
                                "SELECT coalesce(max(version_number),0)+1 FROM document_versions WHERE document_id=:id"
                            ),
                            {"id": document_id},
                        ).scalar_one()
                else:
                    version = 1
                    connection.execute(
                        text("""INSERT INTO documents(id,title,department,classification,seed_managed)
                        VALUES(:id,:title,:department,:classification,:seed)"""),
                        {**data, "id": document_id, "seed": seed},
                    )
                    for role in data["roles"]:
                        connection.execute(
                            text(
                                "INSERT INTO document_permissions(document_id,allowed_role) VALUES(:id,:role)"
                            ),
                            {"id": document_id, "role": role},
                        )
                if not (document and reused_failed_version):
                    connection.execute(
                        text("""INSERT INTO document_versions
                        (id,document_id,version_number,content_hash,source_filename,effective_at,index_status,embedding_model_id,embedding_dimensions)
                        VALUES(:id,:document,:version,:hash,:filename,now(),'INDEXING',:model,:dimensions)"""),
                        {
                            "id": version_id,
                            "document": document_id,
                            "version": version,
                            "hash": data["content_hash"],
                            "filename": data["filename"],
                            "model": settings.embedding_model,
                            "dimensions": settings.embedding_dimensions,
                        },
                    )
                connection.execute(
                    text(
                        """INSERT INTO ingestion_jobs
                        (id,document_id,document_version_id,status,source_path,source_filename,source_size_bytes,
                         progress_chunks,next_attempt_at)
                        VALUES(:job,:doc,:version,'INDEXING',:source_path,:source_filename,:source_size,:progress,
                            now()+make_interval(secs=>CASE WHEN :attempt_count=0 THEN 0
                                ELSE LEAST(300,power(2,LEAST(:attempt_count,8))::integer) END))"""
                    ),
                    {
                        "job": job_id,
                        "doc": document_id,
                        "version": version_id,
                        "source_path": data.get("source_path"),
                        "source_filename": data["filename"],
                        "source_size": data.get("source_size_bytes"),
                        "progress": int(data.get("resume_chunks", 0)),
                        "attempt_count": int(data.get("attempt_count", 0)),
                    },
                )
            reserved = True
            chunk_count = 0
            LOGGER.info(
                "ingestion reserved document_id=%s version=%d job_id=%s reused_failed_version=%s",
                document_id,
                version,
                job_id,
                reused_failed_version,
            )
            if queue_only:
                return {
                    "documentId": document_id,
                    "versionId": str(version_id),
                    "jobId": str(job_id),
                    "version": version,
                    "chunkCount": 0,
                    "duplicate": False,
                    "metadataUpdated": False,
                    "queued": True,
                    "resumedFromChunks": int(data.get("resume_chunks", 0)),
                }
            try:
                produced = process()
                batches = [produced] if isinstance(produced, tuple) else produced
                for chunks, vectors in batches:
                    if len(chunks) != len(vectors) or not chunks:
                        raise ApiError(
                            422, "INGESTION_UNPROCESSABLE", "Document contains no extractable text"
                        )
                    if any(len(vector) != settings.embedding_dimensions for vector in vectors):
                        raise ApiError(
                            422,
                            "INGESTION_UNPROCESSABLE",
                            "Embedding provider returned invalid vector dimensions",
                        )
                    self._stage_batch(data["document_id"], version_id, job_id, version, chunks, vectors)
                    chunk_count += len(chunks)
            except TransientProviderFailure:
                if seed:
                    self._fail_seed_provider(document_id, version_id, job_id)
                raise
            if not chunk_count:
                raise ApiError(422, "INGESTION_UNPROCESSABLE", "Document contains no extractable text")
            self._activate(data, version_id, job_id, chunk_count)
        except ApiError:
            if reserved:
                self._fail(document_id, version_id, job_id, "Indexing failed")
            raise
        except TransientProviderFailure:
            if reserved and not seed:
                self._fail(document_id, version_id, job_id, "Provider temporarily unavailable")
            raise
        except Exception as error:
            if reserved:
                self._fail(document_id, version_id, job_id, str(error))
            raise ApiError(422, "INGESTION_UNPROCESSABLE", "Document indexing failed") from error
        return {
            "documentId": document_id,
            "versionId": str(version_id),
            "jobId": str(job_id),
            "version": version,
            "chunkCount": chunk_count,
            "duplicate": False,
            "metadataUpdated": False,
        }

    def enqueue_ingestion(self, data: dict[str, Any]) -> dict[str, Any]:
        """Reserve an indexing version and durable job without blocking on parsing or embeddings."""

        return self.ingest(data, lambda: iter(()), queue_only=True)

    def run_claimed_ingestion(
        self,
        job: dict[str, Any],
        process: Callable[[int], Iterable[tuple[list[Chunk], list[list[float]]]]],
    ) -> int:
        """Resume a claimed job after its durable chunk checkpoint and atomically activate on completion."""

        job_id = job["id"]
        document_id = job["document_id"]
        version_id = job["document_version_id"]
        version = job["version_number"]
        data = {
            "document_id": document_id,
            "title": job["title"],
            "department": job["department"],
            "classification": job["classification"],
            "roles": set(job["allowed_roles"]),
            "filename": job["source_filename"],
            "content_hash": job["content_hash"],
        }
        with self.database.connect() as connection:
            staged = int(
                connection.execute(
                    text("SELECT count(*) FROM document_chunks WHERE document_version_id=:id"),
                    {"id": version_id},
                ).scalar_one()
            )
        try:
            for chunks, vectors in process(staged):
                if len(chunks) != len(vectors) or not chunks:
                    raise ApiError(422, "INGESTION_UNPROCESSABLE", "Document contains no extractable text")
                if any(len(vector) != get_settings().embedding_dimensions for vector in vectors):
                    raise ApiError(
                        422,
                        "INGESTION_UNPROCESSABLE",
                        "Embedding provider returned invalid vector dimensions",
                    )
                self._stage_batch(document_id, version_id, job_id, version, chunks, vectors, job["worker_id"])
                staged += len(chunks)
            if not staged:
                raise ApiError(422, "INGESTION_UNPROCESSABLE", "Document contains no extractable text")
            self._activate(data, version_id, job_id, staged, job["worker_id"])
            return staged
        except TransientProviderFailure:
            if int(job["attempt_count"]) >= get_settings().ingestion_provider_max_attempts:
                self._fail(document_id, version_id, job_id, "Embedding provider retry limit exceeded")
            else:
                self.defer_ingestion_job(job_id, job["worker_id"])
            raise
        except Exception as error:
            if isinstance(error, ApiError) and error.code == "INGESTION_CONFLICT":
                self.release_ingestion_job(job_id, job["worker_id"])
            else:
                self._fail(document_id, version_id, job_id, str(error))
            raise

    def _record_progress(self, job_id: UUID, chunks: int) -> None:
        """Persist completed batch count for status polling and restart recovery."""

        with transaction(self.database) as connection:
            connection.execute(
                text("""UPDATE ingestion_jobs SET progress_chunks=:chunks,heartbeat_at=now()
                    WHERE id=:id AND status='INDEXING'"""),
                {"id": job_id, "chunks": chunks},
            )

    def update_total_chunks(self, job: dict[str, Any], total: int) -> None:
        """Set source chunk count after extraction while verifying this worker still owns the lease."""

        with transaction(self.database) as connection:
            connection.execute(
                text("""UPDATE ingestion_jobs SET progress_total_chunks=:total
                    WHERE id=:id AND status='INDEXING' AND worker_id=:worker"""),
                {"id": job["id"], "worker": job["worker_id"], "total": total},
            )

    def set_ingestion_total(self, job_id: UUID, total: int) -> None:
        """Record deterministic total chunk count so clients can render percentage progress."""

        with transaction(self.database) as connection:
            connection.execute(
                text(
                    "UPDATE ingestion_jobs SET progress_total_chunks=:total WHERE id=:id AND status='INDEXING'"
                ),
                {"id": job_id, "total": total},
            )

    def claim_ingestion_job(self, worker_id: str) -> dict[str, Any] | None:
        """Claim the oldest queued durable source job without locking other workers out of status reads."""

        with transaction(self.database) as connection:
            row = (
                connection.execute(
                    text("""WITH candidate AS (
                        SELECT id FROM ingestion_jobs WHERE status='INDEXING' AND source_path IS NOT NULL
                          AND worker_id IS NULL AND next_attempt_at<=now() AND attempt_count<:max_attempts
                        ORDER BY queued_at FOR UPDATE SKIP LOCKED LIMIT 1
                        ) UPDATE ingestion_jobs j SET worker_id=:worker,heartbeat_at=now(),
                          progress_chunks=(SELECT count(*) FROM document_chunks c WHERE c.document_version_id=j.document_version_id)
                        FROM candidate c,documents d,document_versions v
                        WHERE j.id=c.id AND d.id=j.document_id AND v.id=j.document_version_id
                        RETURNING j.id,j.document_id,j.document_version_id,j.source_path,j.source_filename,
                                  j.source_size_bytes,j.progress_chunks,j.progress_total_chunks,j.worker_id,
                                  d.title,d.department,d.classification,v.content_hash,v.version_number,
                                  ARRAY(SELECT allowed_role FROM document_permissions p WHERE p.document_id=d.id) AS allowed_roles,
                                  j.attempt_count"""),
                    {"worker": worker_id, "max_attempts": get_settings().ingestion_provider_max_attempts},
                )
                .mappings()
                .first()
            )
            if row is None:
                return None
            result = dict(row)
            result["progress_chunks"] = connection.execute(
                text("SELECT count(*) FROM document_chunks WHERE document_version_id=:id"),
                {"id": result["document_version_id"]},
            ).scalar_one()
            chunk_status = connection.execute(
                text("SELECT index_status FROM document_versions WHERE id=:id"),
                {"id": result["document_version_id"]},
            ).scalar_one_or_none()
            if chunk_status != "INDEXING":
                connection.execute(
                    text(
                        "UPDATE ingestion_jobs SET status='FAILED',error_message='Version is not indexable',completed_at=now(),worker_id=NULL WHERE id=:id"
                    ),
                    {"id": result["id"]},
                )
                return None
            return result

    def acquire_ingestion_worker_lock(self) -> Connection | None:
        """Hold a PostgreSQL session advisory lock so only one app instance embeds at a time."""

        connection = self.database.connect()
        acquired = connection.execute(text("SELECT pg_try_advisory_lock(731942610204)")).scalar_one()
        if not acquired:
            connection.close()
            return None
        return connection

    def release_ingestion_worker_lock(self, connection: Connection) -> None:
        """Release and close the dedicated single-worker PostgreSQL connection."""

        try:
            connection.execute(text("SELECT pg_advisory_unlock(731942610204)"))
        finally:
            connection.close()

    def ingestion_job_state(self, job_id: UUID) -> str | None:
        """Return internal lifecycle status for worker cleanup decisions."""

        with self.database.connect() as connection:
            return connection.execute(
                text("SELECT status FROM ingestion_jobs WHERE id=:id"), {"id": job_id}
            ).scalar_one_or_none()

    def fail_claimed_ingestion(self, job: dict[str, Any], error: str) -> None:
        """Fail a claimed job with no usable source and retain its last active document version."""

        with transaction(self.database) as connection:
            connection.execute(
                text(
                    "UPDATE ingestion_jobs SET status='FAILED',error_message=:error,completed_at=now(),worker_id=NULL WHERE id=:id"
                ),
                {"id": job["id"], "error": error[:500]},
            )
            connection.execute(
                text(
                    "UPDATE document_versions SET index_status='FAILED' WHERE id=:id AND index_status='INDEXING'"
                ),
                {"id": job["document_version_id"]},
            )

    def requeue_interrupted_ingestions(self) -> int:
        """Release persisted worker leases on startup; committed chunk counts remain resumable."""

        with transaction(self.database) as connection:
            result = connection.execute(
                text("""UPDATE ingestion_jobs SET worker_id=NULL,heartbeat_at=now(),next_attempt_at=now()
                    WHERE status='INDEXING' AND source_path IS NOT NULL""")
            )
            queued_versions = list(
                connection.execute(
                    text(
                        "SELECT DISTINCT document_version_id FROM ingestion_jobs WHERE status='INDEXING' AND source_path IS NOT NULL"
                    )
                ).scalars()
            )
            for version_id in queued_versions:
                connection.execute(
                    text("""UPDATE ingestion_jobs SET status='FAILED',completed_at=now(),worker_id=NULL,
                        error_message='Superseded by a newer queued attempt'
                        WHERE document_version_id=:id AND status='INDEXING' AND id<>(
                            SELECT id FROM ingestion_jobs WHERE document_version_id=:id AND status='INDEXING'
                            ORDER BY queued_at DESC LIMIT 1)"""),
                    {"id": version_id},
                )
                connection.execute(
                    text("""UPDATE document_versions SET index_status='FAILED' WHERE id=:id
                        AND index_status='INDEXING' AND NOT EXISTS(SELECT 1 FROM ingestion_jobs
                            WHERE document_version_id=:id AND status='INDEXING')"""),
                    {"id": version_id},
                )
                connection.execute(
                    text(
                        "UPDATE document_versions SET index_status='FAILED' WHERE id=:id AND index_status='INDEXING' AND NOT EXISTS(SELECT 1 FROM ingestion_jobs WHERE document_version_id=:id AND status='INDEXING')"
                    ),
                    {"id": version_id},
                )
        return result.rowcount

    def defer_ingestion_job(self, job_id: UUID, worker_id: str) -> None:
        """Back off a transient provider failure without discarding durable staged chunks."""

        with transaction(self.database) as connection:
            connection.execute(
                text("""UPDATE ingestion_jobs SET worker_id=NULL,
                    next_attempt_at=now()+make_interval(secs=>LEAST(300,power(2,LEAST(attempt_count,8))::integer)),
                    heartbeat_at=now(),error_message='Embedding provider temporarily unavailable',
                    attempt_count=attempt_count+1
                    WHERE id=:id AND worker_id=:worker AND status='INDEXING'"""),
                {"id": job_id, "worker": worker_id},
            )

    def release_ingestion_job(self, job_id: UUID, worker_id: str) -> None:
        """Release an interrupted job lease so another worker process can resume it."""

        with transaction(self.database) as connection:
            connection.execute(
                text("""UPDATE ingestion_jobs SET worker_id=NULL,heartbeat_at=now()
                    WHERE id=:id AND worker_id=:worker AND status='INDEXING'"""),
                {"id": job_id, "worker": worker_id},
            )

    def ingestion_job(self, job_id: UUID, roles: frozenset[str], admin: bool) -> dict[str, Any] | None:
        """Read job status/progress only for an admin or a caller authorized for its document."""

        with self.database.connect() as connection:
            row = (
                connection.execute(
                    text("""SELECT j.id,j.document_id,j.document_version_id,j.status,j.error_message,
                        j.started_at,j.completed_at,j.progress_chunks,j.progress_total_chunks,
                        v.index_status,d.current_version_id,d.title
                        FROM ingestion_jobs j JOIN document_versions v ON v.id=j.document_version_id
                        JOIN documents d ON d.id=j.document_id
                        WHERE j.id=:id AND (:admin OR EXISTS(SELECT 1 FROM document_permissions p
                            WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles)))"""),
                    {"id": job_id, "admin": admin, "roles": list(roles)},
                )
                .mappings()
                .first()
            )
            if row is None:
                return None
            result = dict(row)
            result["progress_chunks"] = connection.execute(
                text("SELECT count(*) FROM document_chunks WHERE document_version_id=:id"),
                {"id": row["document_version_id"]},
            ).scalar_one()
            return result

    def ingestion_jobs_for_document(
        self, document_id: str, roles: frozenset[str], admin: bool
    ) -> list[dict[str, Any]]:
        """List a document's ingestion history only under its normal admin/ACL scope."""

        with self.database.connect() as connection:
            rows = connection.execute(
                text("""SELECT j.id,j.document_id,j.document_version_id,j.status,j.error_message,
                    j.started_at,j.completed_at,j.progress_chunks,j.progress_total_chunks,
                    v.index_status,d.current_version_id,d.title
                    FROM ingestion_jobs j JOIN document_versions v ON v.id=j.document_version_id
                    JOIN documents d ON d.id=j.document_id WHERE d.id=:document_id
                    AND (:admin OR EXISTS(SELECT 1 FROM document_permissions p WHERE p.document_id=d.id
                        AND p.allowed_role=ANY(:roles))) ORDER BY j.queued_at DESC LIMIT 10"""),
                {"document_id": document_id, "admin": admin, "roles": list(roles)},
            ).mappings()
            results: list[dict[str, Any]] = []
            for row in rows:
                item = dict(row)
                item["progress_chunks"] = connection.execute(
                    text("SELECT count(*) FROM document_chunks WHERE document_version_id=:id"),
                    {"id": row["document_version_id"]},
                ).scalar_one()
                results.append(item)
            return results

    def _update_metadata(self, connection: Any, data: dict[str, Any], seed: bool = False) -> bool:
        """Apply duplicate-content metadata and permission changes without embedding again."""

        document_id = data["document_id"]
        previous = (
            connection.execute(
                text("SELECT title,department,classification FROM documents WHERE id=:id"),
                {"id": document_id},
            )
            .mappings()
            .one()
        )
        old_roles = set(
            connection.execute(
                text("SELECT allowed_role FROM document_permissions WHERE document_id=:id"),
                {"id": document_id},
            ).scalars()
        )
        changed = (previous["title"], previous["department"], previous["classification"]) != (
            data["title"],
            data["department"],
            data["classification"],
        ) or old_roles != data["roles"]
        connection.execute(
            text("""UPDATE documents SET title=:title,department=:department,classification=:classification,
                seed_managed=seed_managed OR :seed,seed_repairable=false,updated_at=now() WHERE id=:id"""),
            {**data, "id": document_id, "seed": seed},
        )
        connection.execute(
            text("DELETE FROM document_permissions WHERE document_id=:id"), {"id": document_id}
        )
        for role in data["roles"]:
            connection.execute(
                text("INSERT INTO document_permissions(document_id,allowed_role) VALUES(:id,:role)"),
                {"id": document_id, "role": role},
            )
        return changed

    def _stage_batch(
        self,
        document_id: str,
        version_id: UUID,
        job_id: UUID,
        version: int,
        chunks: list[Chunk],
        vectors: list[list[float]],
        worker_id: str | None = None,
    ) -> None:
        """Commit a bounded batch to an inactive version that retrieval cannot expose."""

        started = monotonic()
        with transaction(self.database) as connection:
            current = (
                connection.execute(
                    text("SELECT status,worker_id FROM ingestion_jobs WHERE id=:id FOR UPDATE"),
                    {"id": job_id},
                )
                .mappings()
                .first()
            )
            if current is None or current["status"] != "INDEXING":
                raise ApiError(409, "INGESTION_CONFLICT", "Ingestion job is no longer indexable")
            if worker_id is not None and current["worker_id"] != worker_id:
                raise ApiError(409, "INGESTION_CONFLICT", "Ingestion worker lease has changed")
            version_status = connection.execute(
                text("SELECT index_status FROM document_versions WHERE id=:id FOR UPDATE"),
                {"id": version_id},
            ).scalar_one_or_none()
            if version_status != "INDEXING":
                raise ApiError(409, "INGESTION_CONFLICT", "Staged document version is no longer indexable")
            for chunk, vector in zip(chunks, vectors, strict=True):
                connection.execute(
                    text("""INSERT INTO document_chunks
                    (id,document_version_id,chunk_index,chunk_text,heading_path,page_number,embedding,
                     technical_identifiers,lexical_terms)
                    VALUES(:chunk,:version,:idx,:body,:heading,:page,CAST(:embedding AS vector),:identifiers,:terms)"""),
                    {
                        "chunk": f"{document_id}-v{version}-c{chunk.index}",
                        "version": version_id,
                        "idx": chunk.index,
                        "body": chunk.text,
                        "heading": chunk.heading_path,
                        "page": chunk.page_number,
                        "embedding": _vector(vector),
                        "identifiers": list(chunk.technical_identifiers),
                        "terms": list(chunk.lexical_terms),
                    },
                )
            connection.execute(
                text("""UPDATE ingestion_jobs SET heartbeat_at=now(),
                    progress_chunks=(SELECT count(*) FROM document_chunks WHERE document_version_id=:version)
                    WHERE id=:id AND status='INDEXING'"""),
                {"id": job_id, "version": version_id},
            )
        LOGGER.info(
            "ingestion batch staged document_id=%s version=%d job_id=%s chunks=%d elapsed_seconds=%.2f",
            document_id,
            version,
            job_id,
            len(chunks),
            monotonic() - started,
        )

    def _activate(
        self,
        data: dict[str, Any],
        version_id: UUID,
        job_id: UUID,
        expected_count: int,
        worker_id: str | None = None,
    ) -> None:
        """Validate staged rows and atomically publish their version and current pointer."""

        started = monotonic()
        with transaction(self.database) as connection:
            document = (
                connection.execute(
                    text("SELECT status FROM documents WHERE id=:id FOR UPDATE"),
                    {"id": data["document_id"]},
                )
                .mappings()
                .first()
            )
            if document is None or document["status"] != "ACTIVE":
                raise ApiError(
                    409,
                    "INGESTION_CONFLICT",
                    "Restore the inactive document before activating a version",
                )
            actual_count = connection.execute(
                text("SELECT count(*) FROM document_chunks WHERE document_version_id=:id"),
                {"id": version_id},
            ).scalar_one()
            if actual_count != expected_count or actual_count == 0:
                raise ApiError(422, "INGESTION_UNPROCESSABLE", "Staged document chunks failed validation")
            job_row = (
                connection.execute(
                    text("SELECT status,worker_id FROM ingestion_jobs WHERE id=:id FOR UPDATE"),
                    {"id": job_id},
                )
                .mappings()
                .first()
            )
            version_status = connection.execute(
                text("SELECT index_status FROM document_versions WHERE id=:id FOR UPDATE"),
                {"id": version_id},
            ).scalar_one_or_none()
            if (
                job_row is None
                or job_row["status"] != "INDEXING"
                or (worker_id is not None and job_row["worker_id"] != worker_id)
                or version_status != "INDEXING"
            ):
                raise ApiError(409, "INGESTION_CONFLICT", "Ingestion job is no longer active")
            connection.execute(
                text(
                    "UPDATE document_versions SET index_status='SUPERSEDED' WHERE document_id=:id AND index_status='ACTIVE'"
                ),
                {"id": data["document_id"]},
            )
            connection.execute(
                text(
                    "UPDATE document_versions SET index_status='ACTIVE' WHERE id=:id AND index_status='INDEXING'"
                ),
                {"id": version_id},
            )
            connection.execute(
                text("""UPDATE documents SET title=:title,department=:department,classification=:classification,
                current_version_id=:version,seed_repairable=false,updated_at=now() WHERE id=:id AND status='ACTIVE'"""),
                {**data, "version": version_id, "id": data["document_id"]},
            )
            connection.execute(
                text("DELETE FROM document_permissions WHERE document_id=:id"), {"id": data["document_id"]}
            )
            for role in data["roles"]:
                connection.execute(
                    text("INSERT INTO document_permissions(document_id,allowed_role) VALUES(:id,:role)"),
                    {"id": data["document_id"], "role": role},
                )
            connection.execute(
                text("UPDATE ingestion_jobs SET status='SUCCEEDED',completed_at=now() WHERE id=:id"),
                {"id": job_id},
            )
        LOGGER.info(
            "ingestion version activated document_id=%s version_id=%s job_id=%s chunks=%d elapsed_seconds=%.2f",
            data["document_id"],
            version_id,
            job_id,
            expected_count,
            monotonic() - started,
        )

    def recover_stale_ingestions(self) -> int:
        """Fail abandoned indexing jobs after a process crash and delete their chunks in bounded batches."""

        with transaction(self.database) as connection:
            stale_ids = list(
                connection.execute(
                    text("""WITH stale AS (
                         SELECT id FROM ingestion_jobs WHERE status='INDEXING'
                         AND heartbeat_at < now() - (:seconds * interval '1 second')
                         ORDER BY heartbeat_at LIMIT 100 FOR UPDATE SKIP LOCKED
                         ) UPDATE ingestion_jobs SET status='FAILED',error_message='Indexing lease expired',
                         completed_at=now() WHERE id IN (SELECT id FROM stale)
                         RETURNING document_version_id"""),
                    {"seconds": get_settings().stale_ingestion_seconds},
                ).scalars()
            )
            if stale_ids:
                connection.execute(
                    text(
                        "UPDATE document_versions SET index_status='FAILED' WHERE id=ANY(:ids) AND index_status='INDEXING'"
                    ),
                    {"ids": stale_ids},
                )
            connection.execute(
                text("""WITH exhausted AS (
                    SELECT id,document_version_id FROM ingestion_jobs WHERE status='INDEXING'
                    AND source_path IS NOT NULL AND attempt_count>=:max_attempts
                    ORDER BY queued_at LIMIT 100 FOR UPDATE SKIP LOCKED
                    ) UPDATE ingestion_jobs j SET status='FAILED',error_message='Provider retry limit exceeded',
                      completed_at=now(),worker_id=NULL FROM exhausted e WHERE j.id=e.id"""),
                {"max_attempts": get_settings().ingestion_provider_max_attempts},
            )
            connection.execute(
                text("""UPDATE document_versions v SET index_status='FAILED'
                    WHERE v.index_status='INDEXING' AND NOT EXISTS(
                        SELECT 1 FROM ingestion_jobs j WHERE j.document_version_id=v.id AND j.status='INDEXING')
                    AND EXISTS(SELECT 1 FROM ingestion_jobs j WHERE j.document_version_id=v.id
                        AND j.error_message='Provider retry limit exceeded')""")
            )
        for version_id in stale_ids:
            self._delete_version_chunks(version_id)
        if stale_ids:
            LOGGER.warning("stale ingestion jobs recovered count=%d", len(stale_ids))
        return len(stale_ids)

    def _delete_version_chunks(self, version_id: UUID) -> None:
        """Delete failed-version chunks only when they remain inactive and have no live job."""

        with transaction(self.database) as connection:
            connection.execute(
                text("""DELETE FROM document_chunks c USING document_versions v,documents d
                    WHERE c.document_version_id=:id AND v.id=c.document_version_id AND d.id=v.document_id
                    AND v.index_status='FAILED' AND d.current_version_id IS DISTINCT FROM v.id
                    AND NOT EXISTS(SELECT 1 FROM ingestion_jobs j WHERE j.document_id=d.id AND j.status='INDEXING')"""),
                {"id": version_id},
            )

    def _cleanup_failed_attempt(self, data: dict[str, Any]) -> None:
        """Remove inaccessible batches left by a failed same-content attempt before retry reservation."""

        with self.database.connect() as connection:
            failed_version = connection.execute(
                text("""SELECT v.id FROM document_versions v JOIN documents d ON d.id=v.document_id
                    WHERE v.document_id=:document_id AND v.content_hash=:content_hash
                    AND v.index_status='FAILED' AND d.current_version_id IS DISTINCT FROM v.id
                    AND NOT EXISTS(SELECT 1 FROM ingestion_jobs j WHERE j.document_id=d.id AND j.status='INDEXING')
                    ORDER BY v.version_number DESC LIMIT 1"""),
                {"document_id": data["document_id"], "content_hash": data["content_hash"]},
            ).scalar_one_or_none()
        if failed_version is not None:
            self._delete_version_chunks(failed_version)

    def _fail(self, document_id: str, version_id: UUID, job_id: UUID, detail: str) -> None:
        """Mark the staged version/job failed without changing an existing active version."""

        with self.database.begin() as connection:
            connection.execute(
                text(
                    "UPDATE document_versions SET index_status='FAILED' WHERE id=:id AND index_status='INDEXING'"
                ),
                {"id": version_id},
            )
            connection.execute(
                text(
                    "UPDATE ingestion_jobs SET status='FAILED',error_message=:error,completed_at=now() WHERE id=:id"
                ),
                {"id": job_id, "error": detail[:500]},
            )
        self._delete_version_chunks(version_id)
        LOGGER.warning(
            "ingestion version failed document_id=%s version_id=%s job_id=%s failure_category=%s",
            document_id,
            version_id,
            job_id,
            detail[:80],
        )

    def _fail_seed_provider(self, document_id: str, version_id: UUID, job_id: UUID) -> None:
        """Record only transient seed-provider failures as eligible for a later bounded repair."""

        with transaction(self.database) as connection:
            connection.execute(
                text(
                    "UPDATE document_versions SET index_status='FAILED' WHERE id=:id AND index_status='INDEXING'"
                ),
                {"id": version_id},
            )
            connection.execute(
                text("""UPDATE ingestion_jobs SET status='FAILED',error_message=:error,completed_at=now()
                    WHERE id=:id"""),
                {"id": job_id, "error": "Provider temporarily unavailable"},
            )
            connection.execute(
                text("""UPDATE documents SET status='DISABLED',seed_repairable=true WHERE id=:id
                    AND status='ACTIVE' AND seed_managed=true AND current_version_id IS NULL"""),
                {"id": document_id},
            )
        self._delete_version_chunks(version_id)

    def reserve_retrieval(
        self,
        query: str,
        roles: frozenset[str],
        embedding: list[float] | None,
        identifiers: list[str],
        depth: int,
        *,
        include_vector: bool = True,
        include_keyword: bool = True,
        include_exact: bool = True,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        """Run three independently ACL-filtered candidate queries before application fusion."""

        params = {"roles": list(roles), "depth": depth}
        normalized_query = unicodedata.normalize("NFKC", query)
        vector_sql = text("""SELECT c.id,v.id AS version_id,d.id AS document_id,d.title,v.version_number,c.heading_path,c.chunk_text,
          1-(c.embedding <=> CAST(:embedding AS vector)) AS score FROM document_chunks c
          JOIN document_versions v ON v.id=c.document_version_id JOIN documents d ON d.id=v.document_id
          WHERE d.status='ACTIVE' AND d.current_version_id=v.id AND v.index_status='ACTIVE'
          AND EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))
          ORDER BY c.embedding <=> CAST(:embedding AS vector),c.id LIMIT :depth""")
        keyword_sql = text("""SELECT c.id,v.id AS version_id,d.id AS document_id,d.title,v.version_number,c.heading_path,c.chunk_text,
          lexical.term_count,lexical.matched_terms,
          ts_rank_cd(c.search_vector,to_tsquery('english',nullif(array_to_string(
            tsvector_to_array(to_tsvector('english',:query)),' | '),''))) AS score FROM document_chunks c
          CROSS JOIN LATERAL (SELECT tsvector_to_array(to_tsvector('english',:query)) AS query_terms,
            cardinality(tsvector_to_array(to_tsvector('english',:query))) AS term_count,
            (SELECT count(*) FROM unnest(tsvector_to_array(to_tsvector('english',:query))) query_term
              WHERE c.search_vector @@ plainto_tsquery('english',query_term)) AS matched_terms) lexical
          JOIN document_versions v ON v.id=c.document_version_id JOIN documents d ON d.id=v.document_id
          WHERE d.status='ACTIVE' AND d.current_version_id=v.id AND v.index_status='ACTIVE'
          AND c.search_vector @@ to_tsquery('english',nullif(array_to_string(
            tsvector_to_array(to_tsvector('english',:query)),' | '),''))
          AND EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))
          ORDER BY score DESC,c.id LIMIT :depth""")
        exact_sql = text("""SELECT c.id,v.id AS version_id,d.id AS document_id,d.title,v.version_number,
          c.heading_path,c.chunk_text,1.0 AS score,true AS exact,
          coalesce((SELECT lower(identifier_match.identifier) FROM unnest(CAST(:identifiers AS text[])) identifier_match(identifier)
              WHERE lower(identifier_match.identifier)=ANY(c.technical_identifiers) LIMIT 1),'') AS matched_identifier
          FROM document_chunks c
          JOIN document_versions v ON v.id=c.document_version_id JOIN documents d ON d.id=v.document_id
          WHERE d.status='ACTIVE' AND d.current_version_id=v.id AND v.index_status='ACTIVE'
          AND EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))
          AND EXISTS (SELECT 1 FROM unnest(CAST(:identifiers AS text[])) identifier_match(identifier)
              WHERE lower(identifier_match.identifier)=ANY(c.technical_identifiers))
            ORDER BY c.id LIMIT :depth""")
        multilingual_sql = text("""WITH requested AS (
                SELECT term FROM unnest(CAST(:terms AS text[])) AS requested_terms(term)
            ), authorized_chunks AS (
                SELECT indexed.lexical_terms
                FROM document_chunks indexed
                LEFT JOIN document_versions indexed_version ON indexed_version.id=indexed.document_version_id
                LEFT JOIN documents indexed_document ON indexed_document.id=indexed_version.document_id
                WHERE indexed.lexical_terms && CAST(:terms AS text[])
                    AND indexed_document.status='ACTIVE'
                    AND indexed_document.current_version_id=indexed_version.id
                    AND indexed_version.index_status='ACTIVE'
                    AND EXISTS(SELECT 1 FROM document_permissions permission
                        WHERE permission.document_id=indexed_document.id AND permission.allowed_role=ANY(:roles))
            ), term_frequency AS (
                SELECT term,count(*)::float AS document_frequency
                FROM authorized_chunks CROSS JOIN LATERAL unnest(lexical_terms) term
                WHERE term=ANY(CAST(:terms AS text[]))
                GROUP BY term
            ), authorized_term_frequency AS (
                SELECT requested.term,coalesce(term_frequency.document_frequency,0) AS document_frequency
                FROM requested LEFT JOIN term_frequency USING(term)
            ), weighted_query_terms AS (
                SELECT term,1.0/ln(2.0+document_frequency) AS weight
                FROM authorized_term_frequency
            )
            SELECT c.id,v.id AS version_id,d.id AS document_id,d.title,v.version_number,
            c.heading_path,c.chunk_text,
            lexical.matched_weight/NULLIF(lexical.total_weight,0) AS score,
             CASE WHEN :has_embedding THEN 1-(c.embedding <=> CAST(:embedding AS vector))
                  ELSE 0.0 END AS vector_score,
            lexical.term_count,lexical.matched_terms
            FROM document_chunks c
            CROSS JOIN LATERAL (
                SELECT count(*)::float AS term_count,
                    count(*) FILTER(WHERE term=ANY(c.lexical_terms))::float AS matched_terms,
                    sum(weight) AS total_weight,
                    sum(weight) FILTER(WHERE term=ANY(c.lexical_terms)) AS matched_weight
                FROM weighted_query_terms
            ) lexical
            JOIN document_versions v ON v.id=c.document_version_id JOIN documents d ON d.id=v.document_id
            WHERE d.status='ACTIVE' AND d.current_version_id=v.id AND v.index_status='ACTIVE'
            AND lexical.matched_terms>0
            AND EXISTS(SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))
            ORDER BY lexical.matched_terms DESC,score DESC,c.id LIMIT :depth""")
        with self.database.connect() as connection:
            vector_rows_initial = (
                connection.execute(
                    vector_sql,
                    {**params, "embedding": _vector(embedding)},
                )
                .mappings()
                .all()
                if include_vector and embedding is not None
                else []
            )
            if not include_vector:
                vector_rows_initial = []
            keyword_query = re.sub(
                r"\broll(?:ed)?\s+back\b", "rollback", normalized_query, flags=re.IGNORECASE
            )
            has_latin_terms = bool(re.search(r"[a-z0-9]{3,}", normalized_query, re.I))
            keywords = (
                [
                    dict(row)
                    for row in connection.execute(keyword_sql, {**params, "query": keyword_query})
                    .mappings()
                    .all()
                ]
                if include_keyword and has_latin_terms
                else []
            )
            if not include_keyword:
                keywords = []
            for row in keywords:
                row["coverage"] = (
                    float(row["matched_terms"]) / float(row["term_count"]) if row["term_count"] else 0.0
                )
            terms = [term for term in re.split(r"[^a-z0-9]+", normalized_query.lower()) if len(term) > 2]
            cjk_runs = re.findall(
                r"[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af]+", normalized_query
            )
            terms.extend(run[index : index + 2] for run in cjk_runs for index in range(len(run) - 1))
            terms.extend(run for run in cjk_runs if len(run) == 1)
            terms = sorted(set(terms))
            if include_keyword and terms:
                multilingual_rows = [
                    dict(row)
                    for row in connection.execute(
                        multilingual_sql,
                        {
                            **params,
                            "terms": terms,
                            "embedding": _vector(embedding) if embedding is not None else None,
                            "has_embedding": embedding is not None,
                        },
                    ).mappings()
                ]
                multilingual_vectors: list[dict[str, Any]] = []
                for row in multilingual_rows:
                    row["coverage"] = float(row["matched_terms"]) / len(terms) if terms else 0.0
                    if embedding is not None:
                        multilingual_vectors.append({**row, "score": float(row["vector_score"])})
                merged_keywords = {row["id"]: row for row in keywords}
                for row in multilingual_rows:
                    existing = merged_keywords.get(row["id"])
                    if existing:
                        existing["coverage"] = max(existing["coverage"], row["coverage"])
                        existing["score"] = max(existing["score"], row["score"])
                        existing["matched_terms"] = max(existing["matched_terms"], row["matched_terms"])
                    else:
                        merged_keywords[row["id"]] = row
                keywords = sorted(
                    merged_keywords.values(), key=lambda row: (-float(row["score"]), row["id"])
                )[:depth]
                vector_rows: dict[str, dict[str, Any]] = {row["id"]: dict(row) for row in vector_rows_initial}
                for row in multilingual_vectors:
                    existing_vector = vector_rows.get(row["id"])
                    if existing_vector:
                        existing_vector["score"] = max(existing_vector["score"], row["score"])
                    else:
                        vector_rows[row["id"]] = dict(row)
                merged_vectors = sorted(
                    vector_rows.values(), key=lambda row: (-float(row["score"]), row["id"])
                )[:depth]
                vector_rows_final = merged_vectors
                for position, row in enumerate(multilingual_rows):
                    vector_candidate = vector_rows.get(row["id"])
                    if vector_candidate is not None:
                        vector_candidate["score"] = float(row["vector_score"])
                        vector_candidate["semantic_rank"] = position + 1
                vector_rows_final = sorted(
                    vector_rows.values(),
                    key=lambda row: (
                        0 if row.get("semantic_rank", depth + 1) <= depth else 1,
                        row.get("semantic_rank", depth + 1),
                        -float(row["score"]),
                        row["id"],
                    ),
                )[:depth]
            elif not include_vector:
                vector_rows_final = []
            else:
                vector_rows_final = [dict(row) for row in vector_rows_initial]
            exact = list(
                connection.execute(exact_sql, {**params, "identifiers": identifiers}).mappings().all()
                if include_exact and identifiers
                else []
            )
        return (
            vector_rows_final if terms else [dict(row) for row in vector_rows_initial],
            keywords,
            [dict(row) for row in exact],
        )

    def save_trace(self, trace: dict[str, Any]) -> None:
        """Persist bounded trace metadata and a query digest; raw user text is never stored."""

        with self.database.begin() as connection:
            connection.execute(
                text("""INSERT INTO rag_traces
              (id,user_id,query_hash,retrieval_duration_ms,generation_duration_ms,total_duration_ms,
               vector_candidate_count,keyword_candidate_count,exact_candidate_count,context_chunk_count,citation_count,
               evidence_sufficient,evidence_gate_reason,model_provider,model_id,grounding_status,status,error_category,response_mode)
              VALUES(:id,:user,:hash,:retrieval,:generation,:total,:vector_count,:keyword_count,:exact_count,:context_count,
               :citations,:sufficient,:reason,:provider,:model,:grounding,:status,:error,:mode)"""),
                trace,
            )

    def update_query_planner_diagnostics(self, trace_id: UUID, diagnostics: dict[str, Any]) -> None:
        """Persist planner details after the base trace insert, tolerating pre-V20 test schemas."""

        with self.database.begin() as connection:
            connection.execute(
                text("""UPDATE rag_traces SET query_planner_provider=:provider,query_planner_model=:model,
                    query_planner_latency_ms=:latency,query_planner_status=:status,
                    query_planner_fallback_reason=:fallback,query_planner_intent=:intent,
                    query_planner_generated_count=:generated,query_planner_deduplicated_count=:deduplicated,
                    query_planner_channel_counts=CAST(:channels AS jsonb),query_planner_candidate_count=:candidates,
                    query_planner_retrieval_counts=CAST(:retrieval_counts AS jsonb) WHERE id=:id"""),
                {**diagnostics, "id": trace_id},
            )

    def trace_query_hash(self, trace_id: UUID) -> str | None:
        """Return the non-public query digest for authorized internal diagnostics only."""

        with self.database.connect() as connection:
            value = connection.execute(
                text("SELECT query_hash FROM rag_traces WHERE id=:id"), {"id": trace_id}
            ).scalar_one_or_none()
        return value.strip() if value is not None else None

    def save_evaluation(self, run: dict[str, Any]) -> None:
        """Persist a run and each detailed scenario atomically in the existing schema."""

        from app.evaluation import save_run

        save_run(self, run)

    def trace(self, trace_id: UUID, identity: str, admin: bool) -> dict[str, Any] | None:
        """Return trace details only to the owner or explicit document admin."""

        sql = "SELECT * FROM rag_traces WHERE id=:id" + ("" if admin else " AND user_id=:user")
        with self.database.connect() as connection:
            row = connection.execute(text(sql), {"id": trace_id, "user": identity}).mappings().first()
        if row is None:
            return None
        fields = {
            "id": str(row["id"]),
            "userId": row["user_id"],
            "retrievalDurationMs": row["retrieval_duration_ms"],
            "generationDurationMs": row["generation_duration_ms"],
            "totalDurationMs": row["total_duration_ms"],
            "vectorCandidateCount": row["vector_candidate_count"],
            "keywordCandidateCount": row["keyword_candidate_count"],
            "exactCandidateCount": row["exact_candidate_count"],
            "contextChunkCount": row["context_chunk_count"],
            "citationCount": row["citation_count"],
            "evidenceSufficient": row["evidence_sufficient"],
            "evidenceGateReason": row["evidence_gate_reason"],
            "modelProvider": row["model_provider"],
            "modelId": row["model_id"],
            "groundingStatus": row["grounding_status"],
            "status": row["status"],
            "errorCategory": row["error_category"],
            "responseMode": row["response_mode"],
            "queryPlanner": {
                "provider": row["query_planner_provider"],
                "model": row["query_planner_model"],
                "latencyMs": row["query_planner_latency_ms"],
                "status": row["query_planner_status"],
                "fallbackReason": row["query_planner_fallback_reason"],
                "intent": row["query_planner_intent"],
                "generatedQueryCount": row["query_planner_generated_count"],
                "deduplicatedQueryCount": row["query_planner_deduplicated_count"],
                "channelCandidateCounts": row["query_planner_channel_counts"],
                "fusedCandidateCount": row["query_planner_candidate_count"],
                "retrievalCandidateCounts": row["query_planner_retrieval_counts"],
            },
            "createdAt": _iso(row["created_at"]),
        }
        return fields


def _vector(values: list[float]) -> str:
    """Format validated numeric coordinates for PostgreSQL pgvector input."""

    return "[" + ",".join(str(float(value)) for value in values) + "]"


def _iso(value: datetime | None) -> str | None:
    """Serialize timestamps in the Java Instant-compatible UTC representation."""

    if value is None:
        return None
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
