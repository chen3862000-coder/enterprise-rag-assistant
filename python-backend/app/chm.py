"""Bounded CHM archive extraction and HTML-to-text iteration."""

import logging
import re
import subprocess
import tempfile
import time
import unicodedata
from collections.abc import Iterator
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath

from app.documents import TECHNICAL_IDENTIFIER, ParsedSection
from app.errors import ApiError
from app.settings import get_settings

META_CHARSET = re.compile(rb"charset\s*=\s*['\"]?([\w.-]+)", re.IGNORECASE)
IGNORED_TAGS = {"script", "style", "noscript", "head"}
LOGGER = logging.getLogger(__name__)


class _TextExtractor(HTMLParser):
    """Collect visible text while excluding page chrome and executable content."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.ignored_depth = 0

    def handle_starttag(self, tag: str, _attrs: list[tuple[str, str | None]]) -> None:
        if tag in IGNORED_TAGS:
            self.ignored_depth += 1
        elif not self.ignored_depth and tag in {"br", "p", "div", "tr", "li", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")
        elif not self.ignored_depth and tag in {"td", "th"}:
            self.parts.append(" | ")

    def handle_endtag(self, tag: str) -> None:
        if tag in IGNORED_TAGS and self.ignored_depth:
            self.ignored_depth -= 1
        elif not self.ignored_depth and tag in {"p", "div", "tr", "li", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.ignored_depth:
            self.parts.append(data)


def iter_chm_sections(archive: Path, destination: Path) -> Iterator[ParsedSection]:
    """Extract eligible HTML pages under configured archive limits and yield page text."""

    settings = get_settings()
    try:
        LOGGER.info("chm inspection started")
        members = _list_members(archive)
        if not members:
            raise ApiError(422, "INGESTION_UNPROCESSABLE", "CHM archive contains no readable entries")
        if len(members) > settings.maximum_chm_entries:
            raise ApiError(413, "INGESTION_INVALID_INPUT", "CHM archive contains too many entries")
        expanded = sum(size for _, size in members)
        if expanded > settings.maximum_chm_expanded_bytes:
            raise ApiError(413, "INGESTION_INVALID_INPUT", "CHM archive expands beyond the configured limit")
        pages = [
            (path, size) for path, size in members if PurePosixPath(path).suffix.lower() in {".htm", ".html"}
        ]
        LOGGER.info(
            "chm inspection completed entries=%d html_pages=%d expanded_bytes=%d",
            len(members),
            len(pages),
            expanded,
        )
        if not pages:
            raise ApiError(422, "INGESTION_UNPROCESSABLE", "CHM archive contains no HTML pages")
        if any(size > settings.maximum_chm_page_bytes for _, size in pages):
            raise ApiError(413, "INGESTION_INVALID_INPUT", "CHM page exceeds the configured size limit")
        destination.mkdir(mode=0o700, parents=True, exist_ok=True)
        subprocess.run(
            [
                settings.chm_extractor,
                "x",
                "-y",
                "-sccUTF-8",
                f"-o{destination}",
                str(archive),
                "-ir!*.htm",
                "-ir!*.html",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=300,
        )
        extracted_bytes = 0
        yielded_pages = 0
        for relative, _ in pages:
            page = destination.joinpath(*PurePosixPath(relative).parts)
            if not page.is_file() or page.is_symlink():
                continue
            content = page.read_bytes()
            extracted_bytes += len(content)
            if extracted_bytes > settings.maximum_chm_expanded_bytes:
                raise ApiError(
                    413, "INGESTION_INVALID_INPUT", "Extracted CHM pages exceed the configured limit"
                )
            if len(content) > settings.maximum_chm_page_bytes:
                raise ApiError(
                    413, "INGESTION_INVALID_INPUT", "Extracted CHM page exceeds the configured limit"
                )
            text = _html_text(content)
            if text and not _is_frame_fallback(text):
                yielded_pages += 1
                if yielded_pages % 50 == 0:
                    LOGGER.info(
                        "chm pages parsed pages=%d extracted_html_bytes=%d", yielded_pages, extracted_bytes
                    )
                heading, _page_identifiers = _page_structure(text)
                yield from _page_sections(heading or relative, text)
        LOGGER.info("chm page extraction completed indexable_pages=%d", yielded_pages)
    except ApiError:
        raise
    except (OSError, subprocess.SubprocessError, UnicodeError) as error:
        raise ApiError(422, "INGESTION_UNPROCESSABLE", "Unable to safely extract CHM document") from error


def _list_members(archive: Path) -> list[tuple[str, int]]:
    """Read 7-Zip metadata into a bounded temporary file with a subprocess deadline."""

    settings = get_settings()
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(
            [settings.chm_extractor, "l", "-slt", "-sccUTF-8", str(archive)],
            stdout=output,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.monotonic() + 60
        last_reported_bytes = 0
        while process.poll() is None:
            current_bytes = output.tell()
            if current_bytes - last_reported_bytes >= 1024 * 1024:
                LOGGER.info("chm inspection output bytes=%d", current_bytes)
                last_reported_bytes = current_bytes
            if current_bytes > settings.maximum_chm_listing_bytes:
                process.kill()
                process.wait()
                raise ApiError(413, "INGESTION_INVALID_INPUT", "CHM metadata exceeds the configured limit")
            if time.monotonic() > deadline:
                process.kill()
                process.wait()
                raise ApiError(422, "INGESTION_UNPROCESSABLE", "CHM inspection exceeded its time limit")
            time.sleep(0.05)
        if process.returncode != 0:
            raise ApiError(422, "INGESTION_UNPROCESSABLE", "Unable to inspect CHM archive")
        if output.tell() > settings.maximum_chm_listing_bytes:
            raise ApiError(413, "INGESTION_INVALID_INPUT", "CHM metadata exceeds the configured limit")
        output.seek(0)
        return _parse_listing(output.read().decode("utf-8"))


def _parse_listing(output: str) -> list[tuple[str, int]]:
    """Validate 7-Zip technical listing paths and return file members and sizes."""

    members: list[tuple[str, int]] = []
    path: str | None = None
    size = 0
    in_members = False
    for line in output.splitlines():
        if line == "----------":
            in_members = True
            path = None
            continue
        if not in_members:
            continue
        if line.startswith("Path = "):
            if path is not None:
                members.append((_safe_member(path), size))
            path, size = line[7:], 0
        elif line.startswith("Size = ") and path is not None:
            try:
                size = int(line[7:])
            except ValueError as error:
                raise ApiError(
                    422, "INGESTION_UNPROCESSABLE", "CHM archive has invalid member metadata"
                ) from error
    if path is not None:
        members.append((_safe_member(path), size))
    return members


def _safe_member(name: str) -> str:
    """Reject absolute, traversal, and platform-specific archive paths before extraction."""

    normalized = name.replace("\\", "/")
    path = PurePosixPath(normalized)
    if (
        not path.parts
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
        or ":" in path.parts[0]
    ):
        raise ApiError(422, "INGESTION_UNPROCESSABLE", "CHM archive contains an unsafe path")
    return normalized


def _html_text(content: bytes) -> str:
    """Decode declared HTML charset, with GB18030 fallback for legacy Chinese CHM pages."""

    match = META_CHARSET.search(content[:8192])
    encodings = [match.group(1).decode("ascii", errors="ignore") if match else "utf-8"]
    encodings.extend(["utf-8", "gb18030"])
    decoded = ""
    for encoding in dict.fromkeys(encodings):
        try:
            decoded = content.decode(encoding)
            break
        except (LookupError, UnicodeDecodeError):
            continue
    if not decoded:
        decoded = content.decode("gb18030", errors="replace")
    parser = _TextExtractor()
    parser.feed(decoded)
    value = "".join(parser.parts).replace("\r", "")
    value = unicodedata.normalize("NFKC", value)
    value = re.sub(r"[ \t]+", " ", value)
    value = re.sub(r" *\n *", "\n", value)
    return re.sub(r"\n{3,}", "\n\n", value).strip()


def _is_frame_fallback(text: str) -> bool:
    """Exclude browser fallback boilerplate instead of indexing it as document evidence."""

    lowered = text.lower()
    return "此页面使用了框架，而您的浏览器不支持框架" in text or "this page uses frames" in lowered


def _page_structure(text: str) -> tuple[str | None, tuple[str, ...]]:
    """Preserve visible headings and technical symbols as metadata for focused retrieval."""

    lines = [re.sub(r"\s+", " ", line).strip(" |\t") for line in text.splitlines()]
    useful = [line for line in lines if line and len(line) <= 180]
    signature = TECHNICAL_IDENTIFIER.search(text)
    headings = [line for line in useful if line.endswith(("函数", "接口", "方法", "API"))]
    if signature:
        function_name = next((value for value in signature.groups() if value), None)
        label = next(
            (
                line
                for line in useful
                if function_name and function_name.lower() in line.lower() and len(line) <= 100
            ),
            None,
        )
        if label and label not in headings:
            headings.insert(0, label)
    identifiers = tuple(
        dict.fromkeys(
            value.lower()
            for match in TECHNICAL_IDENTIFIER.finditer(text)
            for value in match.groups()
            if value
        )
    )
    return (" > ".join(headings[:3]) or None, identifiers)


def _page_sections(source_path: str, text: str, limit: int = 1800) -> Iterator[ParsedSection]:
    """Keep table rows and nearby heading/signature text in bounded, coherent source sections."""

    rows = [re.sub(r"\s+", " ", row).strip() for row in text.splitlines() if row.strip()]
    section: list[str] = []
    size = 0
    section_number = 0
    for row in rows:
        if size and size + len(row) + 1 > limit:
            section_number += 1
            heading = f"{source_path} > section {section_number}"
            section_text = "\n".join(section)
            yield ParsedSection(heading, section_text, structured_terms=_page_structure(section_text)[1])
            section = []
            size = 0
        section.append(row)
        size += len(row) + 1
    if section:
        section_number += 1
        heading = f"{source_path} > section {section_number}"
        section_text = "\n".join(section)
        yield ParsedSection(heading, section_text, structured_terms=_page_structure(section_text)[1])
