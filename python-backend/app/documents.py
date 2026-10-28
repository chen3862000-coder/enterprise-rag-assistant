"""Document upload validation, parsing, deterministic chunking, and metadata types."""

import re
from dataclasses import dataclass
from hashlib import sha256
from io import BytesIO
from typing import Any

from pypdf import PdfReader

from app.errors import ApiError
from app.settings import get_settings

HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
CLASSIFICATIONS = {"PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED"}
ROLES = {"PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN"}


@dataclass(frozen=True)
class ParsedSection:
    """Parsed source section and optional PDF page provenance."""

    heading_path: str | None
    text: str
    page_number: int | None = None


@dataclass(frozen=True)
class Chunk:
    """A deterministic indexed source segment retaining its source location."""

    index: int
    text: str
    heading_path: str | None
    page_number: int | None


def content_hash(content: bytes) -> str:
    """Return the Java-compatible lowercase SHA-256 digest of uploaded bytes."""

    return sha256(content).hexdigest()


def validate_upload(filename: str, content: bytes) -> str:
    """Validate byte size, supported extension, PDF signature, and UTF-8 text safety."""

    if not content or len(content) > get_settings().maximum_upload_bytes:
        raise ApiError(400, "INGESTION_INVALID_INPUT", "File is empty or exceeds the configured size limit")
    suffix = filename.lower().rsplit(".", maxsplit=1)[-1] if "." in filename else ""
    if suffix not in {"md", "markdown", "txt", "pdf"}:
        raise ApiError(
            400, "INGESTION_INVALID_INPUT", "Only Markdown, plain text, and PDF files are supported"
        )
    is_pdf = b"%PDF-" in content[:1024]
    if (suffix == "pdf") != is_pdf:
        raise ApiError(
            400, "INGESTION_INVALID_INPUT", "Uploaded file content does not match its filename extension"
        )
    if not is_pdf:
        try:
            decoded = content.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise ApiError(400, "INGESTION_INVALID_INPUT", "Text upload must be valid UTF-8") from error
        if any(ord(char) < 32 and char not in "\n\r\t" for char in decoded):
            raise ApiError(400, "INGESTION_INVALID_INPUT", "Text upload contains binary control characters")
    return suffix


def parse_document(filename: str, content: bytes) -> list[ParsedSection]:
    """Extract Markdown/TXT heading sections or per-page extractable PDF text."""

    suffix = filename.lower().rsplit(".", maxsplit=1)[-1]
    if suffix == "pdf":
        return _parse_pdf(content)
    return _parse_text(content.decode("utf-8"))


def _parse_text(source: str) -> list[ParsedSection]:
    normalized = source.replace("\r\n", "\n").replace("\r", "\n")
    headings: list[str] = []
    sections: list[ParsedSection] = []
    body: list[str] = []

    def flush() -> None:
        text = "\n".join(body).strip()
        if text:
            sections.append(ParsedSection(" > ".join(headings) or None, text))
        body.clear()

    for line in normalized.split("\n"):
        match = HEADING.fullmatch(line.strip())
        if match:
            flush()
            level = len(match.group(1))
            del headings[level - 1 :]
            headings.append(match.group(2).strip())
        else:
            body.append(line)
    flush()
    return sections or [ParsedSection(None, "")]


def _parse_pdf(content: bytes) -> list[ParsedSection]:
    try:
        pages = PdfReader(BytesIO(content)).pages
        sections = [
            ParsedSection(None, (page.extract_text() or "").replace("\x00", " ").strip(), index)
            for index, page in enumerate(pages, start=1)
        ]
    except Exception as error:
        raise ApiError(422, "INGESTION_UNPROCESSABLE", "Unable to parse PDF document") from error
    sections = [section for section in sections if section.text]
    if not sections:
        raise ApiError(
            422, "INGESTION_UNPROCESSABLE", "PDF contains no extractable text; scanned PDFs are unsupported"
        )
    return sections


def chunk_document(sections: list[ParsedSection], chunk_size: int = 1800, overlap: int = 240) -> list[Chunk]:
    """Match the Java deterministic character chunking and preserve heading/page metadata."""

    if chunk_size <= 0 or overlap < 0 or overlap >= chunk_size:
        raise ValueError("Chunk settings are invalid")
    chunks: list[Chunk] = []
    for section in sections:
        source = section.text.strip()
        start = 0
        while start < len(source):
            end = min(start + chunk_size, len(source))
            if end < len(source):
                boundary = source.rfind(" ", start, end)
                if boundary > start + chunk_size // 2:
                    end = boundary
            value = source[start:end].strip()
            if value:
                chunks.append(Chunk(len(chunks), value, section.heading_path, section.page_number))
            if end == len(source):
                break
            start = max(start + 1, end - overlap)
    return chunks


def normalize_upload(
    document_id: str,
    title: str,
    department: str,
    classification: str,
    roles: str,
    filename: str,
    content: bytes,
) -> dict[str, Any]:
    """Validate and normalize multipart fields using the Java ingestion constraints."""

    if not document_id or not document_id.strip():
        raise ApiError(400, "INGESTION_INVALID_INPUT", "A stable logical document ID is required")
    safe_filename = filename.replace("\\", "/").split("/")[-1].strip()
    if not safe_filename or len(safe_filename) > 255 or ".." in safe_filename:
        raise ApiError(400, "INGESTION_INVALID_INPUT", "Invalid source filename")
    validate_upload(safe_filename, content)
    if not title.strip():
        raise ApiError(400, "INGESTION_INVALID_INPUT", "Document title is required")
    if not department.strip():
        raise ApiError(400, "INGESTION_INVALID_INPUT", "Document department is required")
    normalized_classification = classification.strip().upper()
    if normalized_classification not in CLASSIFICATIONS:
        raise ApiError(400, "INGESTION_INVALID_INPUT", "Unsupported document classification")
    normalized_roles = {role.strip().upper() for role in roles.split(",") if role.strip()}
    if not normalized_roles or not normalized_roles <= ROLES:
        raise ApiError(400, "INGESTION_INVALID_INPUT", "At least one valid allowed role is required")
    return {
        "document_id": document_id.strip(),
        "title": title.strip(),
        "department": department.strip(),
        "classification": normalized_classification,
        "roles": normalized_roles,
        "filename": safe_filename,
        "content": content,
        "content_hash": content_hash(content),
    }
