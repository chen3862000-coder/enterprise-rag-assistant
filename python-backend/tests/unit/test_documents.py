"""Deterministic tests for upload safety, source parsing, hashing, and chunk boundaries."""

import pytest

from app.documents import (
    ParsedSection,
    chunk_document,
    content_hash,
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
