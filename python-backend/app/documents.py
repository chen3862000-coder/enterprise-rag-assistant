"""Document upload validation, parsing, deterministic chunking, and metadata types."""

import re
import unicodedata
from collections.abc import Iterator
from dataclasses import dataclass
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from typing import Any

from pypdf import PdfReader

from app.errors import ApiError
from app.settings import get_settings

HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
CLASSIFICATIONS = {"PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED"}
ROLES = {"PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN"}
TECHNICAL_IDENTIFIER = re.compile(
    r"(?:(?:EQErr|def|function|[A-Z][A-Za-z0-9_:*<>]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\()|"
    r"\b([A-Z][A-Z0-9_]{5,})\b|\b([A-Z]{2,}[A-Z0-9]*-[0-9]{2,})\b|"
    r"\b(X-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+)\b",
)
CJK_TEXT = re.compile(r"[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af]+")
LATIN_TERM = re.compile(r"[a-z0-9]+")


@dataclass(frozen=True)
class ParsedSection:
    """Parsed source section and optional PDF page provenance."""

    heading_path: str | None
    text: str
    page_number: int | None = None
    structured_terms: tuple[str, ...] = ()


@dataclass(frozen=True)
class Chunk:
    """A deterministic indexed source segment retaining its source location."""

    index: int
    text: str
    heading_path: str | None
    page_number: int | None
    structured_terms: tuple[str, ...] = ()

    @property
    def technical_identifiers(self) -> tuple[str, ...]:
        """Return normalized API signatures and distinctive symbolic codes in source text."""

        extracted = tuple(
            dict.fromkeys(
                value.lower()
                for match in TECHNICAL_IDENTIFIER.finditer(self.text)
                for value in match.groups()
                if value
            )
        )
        status_codes = (
            match.group(1).lower()
            for match in re.finditer(r"\b(?:HTTP|status|error|code)\s*[-:]?\s*([0-9]{3})\b", self.text, re.I)
        )
        return tuple(
            dict.fromkeys([*extracted, *status_codes, *(term.lower() for term in self.structured_terms)])
        )

    @property
    def lexical_terms(self) -> tuple[str, ...]:
        """Return Latin tokens and CJK bigrams without inheriting ancestor path terms."""

        heading_leaf = (self.heading_path or "").split(" > ")[-1].rsplit("/", maxsplit=1)[-1]
        heading_leaf = re.sub(r"\.(?:files|html?|htc|js|css)$", "", heading_leaf, flags=re.I)
        heading_leaf = re.sub(r"^(?:tabstrip|index|contents)$", "", heading_leaf, flags=re.I)
        heading_leaf = re.sub(
            r"\.(?:files|html?|htc|js|css)/(?:tabstrip|index|contents)$", "", heading_leaf, flags=re.I
        )
        if re.fullmatch(r"(?:tabstrip|index|contents)(?:\.(?:files|html?|htc|js|css))?", heading_leaf, re.I):
            heading_leaf = ""
        if re.fullmatch(r"(?:tabstrip|index|contents)\.(?:files|html?|htc|js|css)", heading_leaf, re.I):
            heading_leaf = ""
        text = unicodedata.normalize("NFKC", f"{heading_leaf} {self.text}").lower()
        terms = {term for term in LATIN_TERM.findall(text) if len(term) > 2}
        for run in CJK_TEXT.findall(text):
            terms.update(run[index : index + 2] for index in range(len(run) - 1))
            if len(run) == 1:
                terms.add(run)
        terms.update(self.structured_terms)
        return tuple(sorted(terms))


def content_hash(content: bytes) -> str:
    """Return the Java-compatible lowercase SHA-256 digest of uploaded bytes."""

    return sha256(content).hexdigest()


def validate_upload(filename: str, content: bytes) -> str:
    """Validate byte size, supported extension, PDF signature, and UTF-8 text safety."""

    if not content or len(content) > get_settings().maximum_upload_bytes:
        raise ApiError(400, "INGESTION_INVALID_INPUT", "File is empty or exceeds the configured size limit")
    suffix = filename.lower().rsplit(".", maxsplit=1)[-1] if "." in filename else ""
    if suffix not in {"md", "markdown", "txt", "pdf", "chm"}:
        raise ApiError(
            400,
            "INGESTION_INVALID_INPUT",
            "Only Markdown, text, extractable PDF, and CHM files are supported",
        )
    is_pdf = b"%PDF-" in content[:1024]
    is_chm = content.startswith(b"ITSF")
    if (suffix == "pdf") != is_pdf or (suffix == "chm") != is_chm:
        raise ApiError(
            400, "INGESTION_INVALID_INPUT", "Uploaded file content does not match its filename extension"
        )
    if not is_pdf and not is_chm:
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

    return list(iter_section_chunks(iter(sections), chunk_size, overlap))


def iter_section_chunks(
    sections: Iterator[ParsedSection], chunk_size: int = 1800, overlap: int = 240
) -> Iterator[Chunk]:
    """Yield chunks from page/section streams without materializing a section's full chunk list."""

    if chunk_size <= 0 or overlap < 0 or overlap >= chunk_size:
        raise ValueError("Chunk settings are invalid")
    index = 0
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
                yield Chunk(index, value, section.heading_path, section.page_number, section.structured_terms)
                index += 1
            if end == len(source):
                break
            start = max(start + 1, end - overlap)


def iter_text_chunks(path: Path, chunk_size: int = 1800, overlap: int = 240) -> Iterator[Chunk]:
    """Yield UTF-8 text chunks incrementally while preserving Markdown heading paths."""

    if chunk_size <= 0 or overlap < 0 or overlap >= chunk_size:
        raise ValueError("Chunk settings are invalid")
    headings: list[str] = []
    buffer = ""
    pending = ""
    chunk_index = 0

    def append_body(value: str) -> Iterator[Chunk]:
        nonlocal buffer, chunk_index
        buffer += value
        while len(buffer) >= chunk_size + overlap:
            end = chunk_size
            boundary = buffer.rfind(" ", 0, end)
            if boundary > chunk_size // 2:
                end = boundary
            piece = buffer[:end].strip()
            if piece:
                yield Chunk(chunk_index, piece, " > ".join(headings) or None, None)
                chunk_index += 1
            buffer = buffer[max(0, end - overlap) :]

    def process_line(line: str) -> Iterator[Chunk]:
        nonlocal buffer
        normalized = line.rstrip("\r")
        match = HEADING.fullmatch(normalized.strip())
        if match:
            yield from flush(buffer, " > ".join(headings) or None)
            buffer = ""
            level = len(match.group(1))
            del headings[level - 1 :]
            headings.append(match.group(2).strip())
        else:
            yield from append_body(normalized + "\n")

    def flush(value: str, heading: str | None) -> Iterator[Chunk]:
        nonlocal chunk_index
        while value.strip():
            end = min(chunk_size, len(value))
            if end < len(value):
                boundary = value.rfind(" ", 0, end)
                if boundary > chunk_size // 2:
                    end = boundary
            piece = value[:end].strip()
            if piece:
                yield Chunk(chunk_index, piece, heading, None)
                chunk_index += 1
            if end == len(value):
                break
            value = value[max(0, end - overlap) :]

    with path.open("r", encoding="utf-8", errors="strict", newline="") as source:
        while block := source.read(64 * 1024):
            pending += block
            while "\n" in pending:
                line, pending = pending.split("\n", maxsplit=1)
                yield from process_line(line)
            if len(pending) > 64 * 1024:
                yield from append_body(pending[: 64 * 1024])
                pending = pending[64 * 1024 :]
    if pending:
        yield from process_line(pending)
    yield from flush(buffer, " > ".join(headings) or None)


def iter_pdf_sections(path: Path) -> Iterator[ParsedSection]:
    """Extract one PDF page at a time so document size does not dictate parse memory."""

    has_text = False
    try:
        with path.open("rb") as source:
            for index, page in enumerate(PdfReader(source).pages, start=1):
                value = (page.extract_text() or "").replace("\x00", " ").strip()
                if value:
                    has_text = True
                    yield ParsedSection(None, value, index)
    except Exception as error:
        raise ApiError(422, "INGESTION_UNPROCESSABLE", "Unable to parse PDF document") from error
    if not has_text:
        raise ApiError(
            422,
            "INGESTION_UNPROCESSABLE",
            "PDF contains no extractable text; scanned PDFs are unsupported",
        )


def normalize_upload_metadata(
    document_id: str,
    title: str,
    department: str,
    classification: str,
    roles: str,
    filename: str,
    digest: str,
) -> dict[str, Any]:
    """Validate multipart metadata for an already size-checked, hashed temporary upload."""

    if not document_id or not document_id.strip():
        raise ApiError(400, "INGESTION_INVALID_INPUT", "A stable logical document ID is required")
    safe_filename = filename.replace("\\", "/").split("/")[-1].strip()
    if not safe_filename or len(safe_filename) > 255 or ".." in safe_filename:
        raise ApiError(400, "INGESTION_INVALID_INPUT", "Invalid source filename")
    suffix = safe_filename.lower().rsplit(".", maxsplit=1)[-1] if "." in safe_filename else ""
    if suffix not in {"md", "markdown", "txt", "pdf", "chm"}:
        raise ApiError(400, "INGESTION_INVALID_INPUT", "Unsupported document file type")
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
        "content_hash": digest,
    }


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
