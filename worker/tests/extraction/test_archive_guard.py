from __future__ import annotations

import io
import tarfile
import zipfile
from pathlib import Path

import pytest

from app.extraction.archive_guard import ArchiveLimits, ArchiveSecurityError, extract_source


def limits(**overrides: int | float) -> ArchiveLimits:
    values: dict[str, int | float] = {
        "maximum_extracted_bytes": 4096,
        "maximum_single_file_bytes": 2048,
        "maximum_file_count": 10,
        "maximum_directory_depth": 4,
        "maximum_compression_ratio": 20.0,
    }
    values.update(overrides)
    return ArchiveLimits(**values)  # type: ignore[arg-type]


def tar_archive(path: Path, entries: list[tuple[tarfile.TarInfo, bytes]]) -> None:
    with tarfile.open(path, "w:gz") as archive:
        for member, content in entries:
            archive.addfile(member, io.BytesIO(content) if member.isreg() else None)


def regular(name: str, content: bytes) -> tuple[tarfile.TarInfo, bytes]:
    member = tarfile.TarInfo(name)
    member.size = len(content)
    return member, content


def test_extracts_regular_tar_files_inside_destination(tmp_path: Path) -> None:
    source = tmp_path / "source.bin"
    tar_archive(
        source,
        [regular("paper/main.tex", b"\\documentclass{article}"), regular("paper/part.tex", b"x")],
    )
    destination = tmp_path / "out"

    report = extract_source(source, destination, limits())

    assert report.source_format == "TAR_GZIP"
    assert report.file_count == 2
    assert report.extracted_bytes == 24
    assert (destination / "paper/main.tex").read_bytes() == b"\\documentclass{article}"


@pytest.mark.parametrize("name", ["../escape.tex", "/absolute.tex", "safe/../../escape.tex"])
def test_rejects_tar_path_traversal(tmp_path: Path, name: str) -> None:
    source = tmp_path / "bad.tar.gz"
    tar_archive(source, [regular(name, b"unsafe")])

    with pytest.raises(ArchiveSecurityError, match="path"):
        extract_source(source, tmp_path / "out", limits())

    assert not (tmp_path / "escape.tex").exists()


@pytest.mark.parametrize("link_type", [tarfile.SYMTYPE, tarfile.LNKTYPE])
def test_rejects_symbolic_and_hard_links(tmp_path: Path, link_type: bytes) -> None:
    source = tmp_path / "links.tar.gz"
    member = tarfile.TarInfo("paper/link.tex")
    member.type = link_type
    member.linkname = "../../outside"
    tar_archive(source, [(member, b"")])

    with pytest.raises(ArchiveSecurityError, match="link"):
        extract_source(source, tmp_path / "out", limits())


def test_rejects_zip_slip_and_leaves_no_outside_file(tmp_path: Path) -> None:
    source = tmp_path / "bad.zip"
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("../escape.tex", "unsafe")

    with pytest.raises(ArchiveSecurityError, match="path"):
        extract_source(source, tmp_path / "out", limits())

    assert not (tmp_path / "escape.tex").exists()


def test_rejects_expansion_ratio_bomb_before_writing_payload(tmp_path: Path) -> None:
    source = tmp_path / "bomb.zip"
    with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("main.tex", "A" * 2000)

    with pytest.raises(ArchiveSecurityError, match="compression ratio"):
        extract_source(source, tmp_path / "out", limits(maximum_compression_ratio=2.0))


def test_rejects_oversized_single_file(tmp_path: Path) -> None:
    source = tmp_path / "large.tar.gz"
    tar_archive(source, [regular("main.tex", b"x" * 2049)])

    with pytest.raises(ArchiveSecurityError, match="single-file"):
        extract_source(source, tmp_path / "out", limits())


def test_accepts_plain_tex_without_treating_it_as_an_executable(tmp_path: Path) -> None:
    source = tmp_path / "source.bin"
    source.write_bytes(b"\\documentclass{article}\n\\author{Alice}\n")

    report = extract_source(source, tmp_path / "out", limits())

    assert report.source_format == "TEX"
    assert (tmp_path / "out/main.tex").is_file()
