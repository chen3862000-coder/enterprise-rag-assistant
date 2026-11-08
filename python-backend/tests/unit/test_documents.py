"""Deterministic tests for upload safety, source parsing, hashing, and chunk boundaries."""

from pathlib import Path

import pytest

from app.documents import (
    Chunk,
    ParsedSection,
    chunk_document,
    content_hash,
    iter_text_chunks,
    normalize_upload,
    parse_document,
    validate_upload,
)
from app.errors import ApiError


def test_hash_matches_sha256_and_text_parsing_tracks_heading_path() -> None:
    """Content identity uses raw uploaded bytes while Markdown parsing preserves hierarchy."""

    assert content_hash(b"same") == "0967115f2813a3541eaef77de9d9d5773f1c0c04314b0bbfe4ff3b3b1c55b5d5"
    sections = parse_document("policy.md", b"# Access\r\nText\r\n## Tokens\r\nRotate.")
    assert [(section.heading_path, section.text) for section in sections] == [
        ("Access", "Text"),
        ("Access > Tokens", "Rotate."),
    ]


def test_upload_rejects_extension_mismatch_invalid_utf8_and_controls() -> None:
    """Filename extension, signature, UTF-8, and text control bytes are all validated."""

    with pytest.raises(ApiError, match="content does not match"):
        validate_upload("spoof.pdf", b"plain text")
    with pytest.raises(ApiError, match="valid UTF-8"):
        validate_upload("broken.txt", b"\xff")
    with pytest.raises(ApiError, match="control characters"):
        validate_upload("binary.txt", b"abc\x00def")


def test_normalized_metadata_and_chunk_overlap_are_stable() -> None:
    """Equivalent role casing is normalized and split chunks preserve overlap deterministically."""

    normalized = normalize_upload(
        " doc-a ", " Title ", " HR ", "confidential", "hr, PUBLIC", "folder\\policy.txt", b"Policy body"
    )
    assert normalized["document_id"] == "doc-a"
    assert normalized["roles"] == {"HR", "PUBLIC"}
    assert normalized["filename"] == "policy.txt"
    chunks = chunk_document([ParsedSection("Rules", "one two three four five six")], chunk_size=12, overlap=0)
    assert [chunk.text for chunk in chunks] == ["one two", "three four", "five six"]
    assert all(chunk.heading_path == "Rules" for chunk in chunks)


def test_upload_rejects_unsupported_roles_classification_and_path_filename() -> None:
    """Metadata and filename traversal fail with the deterministic ingestion category."""

    values = ("doc-a", "Title", "HR", "CONFIDENTIAL", "HR", "policy.txt", b"valid")
    for changed in (
        ("doc-a", "Title", "HR", "TOP_SECRET", "HR", "policy.txt", b"valid"),
        ("doc-a", "Title", "HR", "CONFIDENTIAL", "ROOT", "policy.txt", b"valid"),
        ("doc-a", "Title", "HR", "CONFIDENTIAL", "HR", "..policy.txt", b"valid"),
    ):
        with pytest.raises(ApiError) as error:
            normalize_upload(*changed)
        assert error.value.code == "INGESTION_INVALID_INPUT"
    assert normalize_upload(*values)["title"] == "Title"


def test_streamed_text_chunking_preserves_headings_overlap_and_indexes(tmp_path: Path) -> None:
    """Large text is read incrementally and emits stable global chunk indexes."""

    source = tmp_path / "guide.md"
    source.write_text("# Alpha\n" + ("alpha " * 500) + "\n## Beta\nBeta details", encoding="utf-8")
    chunks = list(iter_text_chunks(source, chunk_size=100, overlap=20))
    assert len(chunks) > 10
    assert [chunk.index for chunk in chunks] == list(range(len(chunks)))
    assert chunks[0].heading_path == "Alpha"
    assert chunks[-1].heading_path == "Alpha > Beta"
    assert chunks[0].text[-20:] in chunks[1].text


def test_chm_signature_and_metadata_validation() -> None:
    """CHM uploads require the ITSF signature and recognized extension."""

    assert validate_upload("manual.chm", b"ITSF" + b"\x00" * 32) == "chm"
    with pytest.raises(ApiError, match="does not match"):
        validate_upload("manual.txt", b"ITSF" + b"\x00" * 32)


def test_chunk_indexes_mixed_script_identifiers_and_cjk_lexical_terms() -> None:
    """Chunk records retain API symbols and normalized multilingual terms for retrieval."""

    chunk = Chunk(0, "序列函数：EQErr csd(...) RDEXPEND 版本Ａ", "功能性函数", None)
    assert {"csd", "rdexpend"}.issubset(set(chunk.technical_identifiers))
    assert "python" not in chunk.lexical_terms
    assert {"序列", "列函", "函数", "功能", "性函", "数eqerr", "rdexpend"}.intersection(
        set(chunk.lexical_terms)
    )
    assert "版本" in chunk.lexical_terms
