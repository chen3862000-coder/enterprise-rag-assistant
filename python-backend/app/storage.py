"""PostgreSQL repositories for lifecycle, ACL-filtered retrieval, citations, traces, and evaluations."""

import logging
import re
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import Engine, text

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
                 v.version_number, v.index_status, d.updated_at
                 FROM documents d LEFT JOIN document_versions v ON v.id = d.current_version_id"""
        parameters: dict[str, Any] = {}
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
        process: Callable[[], tuple[list[Chunk], list[list[float]]]],
        seed: bool = False,
    ) -> dict[str, Any]:
        """Reserve, persist, and atomically activate a version under a PostgreSQL advisory lock."""

        document_id = data["document_id"]
        version_id, job_id = uuid4(), uuid4()
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
                            "INGESTION_CONFLICT",
                            "Restore the inactive document before uploading or reindexing it",
                        )
                    connection.execute(
                        text("UPDATE documents SET status='ACTIVE',seed_repairable=false WHERE id=:id"),
                        {"id": document_id},
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
                    if existing and existing["index_status"] == "INDEXING":
                        raise ApiError(
                            409,
                            "INGESTION_CONFLICT",
                            "An identical document version is already being indexed",
                        )
                    if existing and existing["index_status"] == "FAILED":
                        reused_failed_version = True
                        version_id = existing["id"]
                        version = existing["version_number"]
                        connection.execute(
                            text("UPDATE document_versions SET index_status='INDEXING' WHERE id=:id"),
                            {"id": version_id},
                        )
                        connection.execute(
                            text("""UPDATE ingestion_jobs SET status='FAILED',completed_at=now()
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
                        "INSERT INTO ingestion_jobs(id,document_id,document_version_id,status) VALUES(:job,:doc,:version,'INDEXING')"
                    ),
                    {"job": job_id, "doc": document_id, "version": version_id},
                )
            reserved = True
            try:
                chunks, vectors = process()
            except TransientProviderFailure:
                if seed:
                    self._fail_seed_provider(document_id, version_id, job_id)
                raise
            if len(chunks) != len(vectors) or not chunks:
                raise ApiError(422, "INGESTION_UNPROCESSABLE", "Document contains no extractable text")
            self._activate(data, chunks, vectors, version_id, job_id, version)
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
            "chunkCount": len(chunks),
            "duplicate": False,
            "metadataUpdated": False,
        }

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

    def _activate(
        self,
        data: dict[str, Any],
        chunks: list[Chunk],
        vectors: list[list[float]],
        version_id: UUID,
        job_id: UUID,
        version: int,
    ) -> None:
        """Persist all staged chunks and switch active pointers in one database transaction."""

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
            connection.execute(
                text("DELETE FROM document_chunks WHERE document_version_id=:id"), {"id": version_id}
            )
            for chunk, vector in zip(chunks, vectors, strict=True):
                vector_literal = "[" + ",".join(str(float(value)) for value in vector) + "]"
                connection.execute(
                    text("""INSERT INTO document_chunks
                    (id,document_version_id,chunk_index,chunk_text,heading_path,page_number,embedding)
                    VALUES(:chunk,:version,:idx,:body,:heading,:page,CAST(:embedding AS vector))"""),
                    {
                        "chunk": f"{data['document_id']}-v{version}-c{chunk.index}",
                        "version": version_id,
                        "idx": chunk.index,
                        "body": chunk.text,
                        "heading": chunk.heading_path,
                        "page": chunk.page_number,
                        "embedding": vector_literal,
                    },
                )
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
            connection.execute(
                text("""UPDATE documents SET status='DISABLED',seed_repairable=false
                    WHERE id=:id AND status='ACTIVE' AND current_version_id IS NULL"""),
                {"id": document_id},
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

    def reserve_retrieval(
        self, query: str, roles: frozenset[str], embedding: list[float], identifiers: list[str], depth: int
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        """Run three independently ACL-filtered candidate queries before application fusion."""

        params = {"roles": list(roles), "depth": depth}
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
        exact_sql = text("""SELECT c.id,v.id AS version_id,d.id AS document_id,d.title,v.version_number,c.heading_path,c.chunk_text,1.0 AS score
          FROM document_chunks c JOIN document_versions v ON v.id=c.document_version_id JOIN documents d ON d.id=v.document_id
          WHERE d.status='ACTIVE' AND d.current_version_id=v.id AND v.index_status='ACTIVE'
          AND EXISTS (SELECT 1 FROM document_permissions p WHERE p.document_id=d.id AND p.allowed_role=ANY(:roles))
          AND EXISTS (SELECT 1 FROM unnest(CAST(:identifiers AS text[])) needle WHERE lower(needle)=ANY(ARRAY(
            SELECT rtrim(token,'.') FROM unnest(regexp_split_to_array(lower(c.chunk_text),'[^[:alnum:]_./:-]+')) token)))
          ORDER BY c.id LIMIT :depth""")
        with self.database.connect() as connection:
            vectors = (
                connection.execute(
                    vector_sql,
                    {**params, "embedding": _vector(embedding)},
                )
                .mappings()
                .all()
            )
            keyword_query = re.sub(r"\broll(?:ed)?\s+back\b", "rollback", query, flags=re.IGNORECASE)
            keywords = [
                dict(row)
                for row in connection.execute(keyword_sql, {**params, "query": keyword_query})
                .mappings()
                .all()
            ]
            for row in keywords:
                row["coverage"] = (
                    float(row["matched_terms"]) / float(row["term_count"]) if row["term_count"] else 0.0
                )
            exact = (
                connection.execute(exact_sql, {**params, "identifiers": identifiers}).mappings().all()
                if identifiers
                else []
            )
        return [dict(row) for row in vectors], keywords, [dict(row) for row in exact]

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
