"""Idempotent synthetic corpus seeding with bounded repair of transient first-index failures."""

import logging
import time
from hashlib import sha256
from pathlib import Path
from typing import Any

from app.documents import chunk_document, normalize_upload, parse_document
from app.providers import OllamaEmbeddingProvider, TransientProviderFailure
from app.settings import get_settings
from app.storage import Storage

LOGGER = logging.getLogger(__name__)
ACCESS = {
    "public": ("PUBLIC", "PUBLIC", {"PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN"}),
    "engineering": ("ENGINEERING", "INTERNAL", {"ENGINEERING"}),
    "hr": ("HR", "CONFIDENTIAL", {"HR"}),
    "security": ("SECURITY", "RESTRICTED", {"SECURITY_ADMIN"}),
}


def seed_corpus(storage: Storage, embeddings: OllamaEmbeddingProvider) -> None:
    """Index checked-in synthetic fixtures in stable order; only provider transients are retried."""

    root = Path(get_settings().seed_path)
    if not root.is_dir():
        raise RuntimeError(f"Synthetic corpus directory does not exist: {root}")
    files = sorted(path for path in root.rglob("*") if path.is_file())
    for path in files:
        relative = path.relative_to(root)
        area = relative.parts[0]
        if area not in ACCESS:
            raise RuntimeError(f"Unexpected synthetic corpus directory: {area}")
        stem = path.stem
        document_id = _document_id(area, stem)
        if _archived_is_already_current(storage, embeddings, area, stem, path, document_id):
            continue
        department, classification, roles = ACCESS[area]
        title = stem.replace("-", " ").replace("_", " ")
        data = normalize_upload(
            document_id, title, department, classification, ",".join(roles), path.name, path.read_bytes()
        )

        def process(upload_data: dict[str, Any] = data) -> tuple[list[Any], list[list[float]]]:
            chunks = chunk_document(parse_document(upload_data["filename"], upload_data["content"]))
            return chunks, embeddings.embed([chunk.text for chunk in chunks])

        for attempt in range(2):
            try:
                storage.ingest(data, process, seed=True)
                break
            except TransientProviderFailure:
                LOGGER.warning(
                    "Transient embedding failure while seeding %s (attempt %s/2)", document_id, attempt + 1
                )
                if attempt == 1:
                    raise
                time.sleep(0.25)


def _document_id(area: str, stem: str) -> str:
    """Keep the established logical ID for the archived/current rate-limit fixture pair."""

    if area == "engineering" and stem == "rate-limit-configuration-v1":
        return "doc-engineering-rate-limit-configuration"
    return f"doc-{area}-{stem}"


def _archived_is_already_current(
    storage: Storage,
    embeddings: OllamaEmbeddingProvider,
    area: str,
    stem: str,
    archive: Path,
    document_id: str,
) -> bool:
    """Avoid replacing the live policy with its archived v1 fixture on each seed pass."""

    if area != "engineering" or stem != "rate-limit-configuration-v1":
        return False
    current = archive.with_name("rate-limit-configuration.md")
    if not current.is_file():
        return False
    content = current.read_bytes()
    data = {"document_id": document_id, "content_hash": sha256(content).hexdigest()}
    return storage.is_active_content(data["document_id"], data["content_hash"])
