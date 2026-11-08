"""Deterministic CHM listing validation and HTML text extraction tests."""

import subprocess
import sys
from pathlib import Path

import pytest

from app.chm import (
    _html_text,
    _list_members,
    _page_sections,
    _parse_listing,
    _safe_member,
    iter_chm_sections,
)
from app.errors import ApiError


def test_listing_ignores_archive_header_and_parses_chinese_page_paths() -> None:
    """7-Zip header metadata is not mistaken for members and Unicode names survive."""

    listing = """Listing archive: manual.chm
Path = manual.chm
Type = Chm
----------
Path = 指标手册/接口.html
Size = 128
Path = topic.txt
Size = 42
"""
    assert _parse_listing(listing) == [("指标手册/接口.html", 128), ("topic.txt", 42)]


def test_listing_entry_limit_is_enforced(tmp_path: Path, monkeypatch) -> None:
    """Archives over the configured entry count are rejected before extraction."""

    from app.settings import Settings

    monkeypatch.setattr("app.chm.get_settings", lambda: Settings(maximum_chm_entries=1))
    monkeypatch.setattr("app.chm._list_members", lambda _archive: [("a.html", 1), ("b.html", 1)])
    archive = tmp_path / "manual.chm"
    archive.write_bytes(b"ITSF")
    with pytest.raises(ApiError, match="too many entries"):
        list(iter_chm_sections(archive, tmp_path / "out"))


def test_listing_size_limit_is_enforced(tmp_path: Path, monkeypatch) -> None:
    """Archives whose listed member sizes exceed the expansion budget are rejected."""

    from app.settings import Settings

    monkeypatch.setattr("app.chm.get_settings", lambda: Settings(maximum_chm_expanded_bytes=10))
    monkeypatch.setattr("app.chm._list_members", lambda _archive: [("a.html", 11)])
    archive = tmp_path / "manual.chm"
    archive.write_bytes(b"ITSF")
    with pytest.raises(ApiError, match="expands beyond"):
        list(iter_chm_sections(archive, tmp_path / "out"))


def test_listing_timeout_is_reported(tmp_path: Path, monkeypatch) -> None:
    """An archive metadata process that does not exit within its deadline is terminated."""

    from app.settings import Settings

    class HangingProcess:
        returncode = None

        def __init__(self) -> None:
            self.killed = False

        def poll(self) -> None:
            return self.returncode

        def kill(self) -> None:
            self.killed = True
            self.returncode = -9

        def wait(self) -> int:
            return -9

    process = HangingProcess()
    monkeypatch.setattr("app.chm.get_settings", lambda: Settings())
    monkeypatch.setattr("app.chm.subprocess.Popen", lambda *args, **kwargs: process)
    times = iter((0.0, 61.0))
    monkeypatch.setattr("app.chm.time.monotonic", lambda: next(times, 11.0))
    monkeypatch.setattr("app.chm.time.sleep", lambda _delay: None)
    with pytest.raises(ApiError, match="inspection exceeded"):
        _list_members(tmp_path / "manual.chm")
    assert process.killed


def test_extract_timeout_is_reported(tmp_path: Path, monkeypatch) -> None:
    """An extractor that exceeds its wall-clock budget fails with a safe ingestion error."""

    from app.settings import Settings

    monkeypatch.setattr("app.chm.get_settings", lambda: Settings())
    monkeypatch.setattr("app.chm._list_members", lambda _archive: [("a.html", 1)])
    monkeypatch.setattr(
        "app.chm.subprocess.run",
        lambda *args, **kwargs: (_ for _ in ()).throw(subprocess.TimeoutExpired(args[0], kwargs["timeout"])),
    )
    archive = tmp_path / "manual.chm"
    archive.write_bytes(b"ITSF")
    with pytest.raises(ApiError, match="Unable to safely extract"):
        list(iter_chm_sections(archive, tmp_path / "out"))


def test_chm_page_size_limit_is_enforced(tmp_path: Path, monkeypatch) -> None:
    """Oversized individual HTML entries are rejected before running extraction."""

    from app.settings import Settings

    monkeypatch.setattr("app.chm.get_settings", lambda: Settings(maximum_chm_page_bytes=10))
    monkeypatch.setattr("app.chm._list_members", lambda _archive: [("a.html", 11)])
    archive = tmp_path / "manual.chm"
    archive.write_bytes(b"ITSF")
    with pytest.raises(ApiError, match="page exceeds"):
        list(iter_chm_sections(archive, tmp_path / "out"))


def test_archive_path_traversal_is_rejected() -> None:
    """Absolute and parent-traversal member paths cannot escape extraction root."""

    for path in ("../outside.html", "/etc/passwd", "C:\\outside.html"):
        with pytest.raises(ApiError, match="unsafe path"):
            _safe_member(path)


def test_listing_metadata_limit_is_enforced(tmp_path: Path, monkeypatch) -> None:
    """The external listing is bounded before parsing to avoid memory exhaustion."""

    from app.settings import Settings

    settings = Settings(maximum_chm_listing_bytes=1)
    monkeypatch.setattr("app.chm.get_settings", lambda: settings)
    archive = tmp_path / "not-used.chm"
    archive.write_bytes(b"ITSF")
    real_popen = subprocess.Popen
    monkeypatch.setattr(
        "app.chm.subprocess.Popen",
        lambda _args, **kwargs: real_popen(
            [sys.executable, "-c", "print('x' * 100)"],
            stdout=kwargs["stdout"],
            stderr=subprocess.DEVNULL,
        ),
    )
    with pytest.raises(ApiError, match="metadata exceeds"):
        _list_members(archive)


def test_html_extraction_uses_declared_chinese_encoding_and_skips_scripts() -> None:
    """Visible Chinese text and headings survive HTML cleanup, executable text does not."""

    source = '<meta charset="gb18030"><h1>指标手册</h1><table><tr><td>收益指标</td><td>描述</td></tr></table><script>secret()</script>'
    encoded = source.encode("gb18030")
    text = _html_text(encoded)
    assert "指标手册" in text
    assert "收益指标" in text
    assert " | " in text
    assert "secret" not in text


def test_html_extraction_uses_gb18030_fallback() -> None:
    """Legacy pages without charset metadata decode using the documented Chinese fallback."""

    assert "指标" in _html_text("<p>指标</p>".encode("gb18030"))


@pytest.mark.parametrize("content", [b"", b"<script>only hidden</script>"])
def test_empty_html_has_no_indexable_text(content: bytes) -> None:
    """Empty or non-visible HTML content produces no source text."""

    assert _html_text(content).strip() == ""


def test_frame_fallback_is_not_indexed_as_evidence() -> None:
    """Localized browser fallback pages are filtered from CHM document sections."""

    from app.chm import _is_frame_fallback

    assert _is_frame_fallback("This page uses frames, but your browser doesn't support them.")
    assert _is_frame_fallback("此页面使用了框架，而您的浏览器不支持框架。")


def test_chm_structure_splits_table_rows_and_propagates_section_identifiers() -> None:
    """API signatures and table rows remain coherent across bounded chunks with source headings."""

    sections = list(
        _page_sections(
            "序列函数.html",
            "序列函数 csd\nEQErr csd(const char* codes);\n参数 codes 股票代码\n参数 indicators 指标简称",
            limit=44,
        )
    )
    assert len(sections) > 1
    assert all("序列函数.html > section" in (section.heading_path or "") for section in sections)
    assert any("csd" in section.structured_terms for section in sections)
    assert any("csd" not in section.structured_terms for section in sections)
    assert any("EQErr csd" in section.text for section in sections)
