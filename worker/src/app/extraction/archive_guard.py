from __future__ import annotations

import gzip
import shutil
import stat
import tarfile
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


class ArchiveSecurityError(Exception):
    """An archive violates a configured extraction boundary."""


@dataclass(frozen=True, slots=True)
class ArchiveLimits:
    maximum_extracted_bytes: int
    maximum_single_file_bytes: int
    maximum_file_count: int
    maximum_directory_depth: int
    maximum_compression_ratio: float

    def __post_init__(self) -> None:
        if (
            self.maximum_extracted_bytes < 1
            or self.maximum_single_file_bytes < 1
            or self.maximum_file_count < 1
            or self.maximum_directory_depth < 1
            or self.maximum_compression_ratio < 1
        ):
            raise ValueError("archive limits must be positive")


@dataclass(frozen=True, slots=True)
class ArchiveReport:
    source_format: str
    extracted_bytes: int
    file_count: int


def extract_source(source: Path, destination: Path, limits: ArchiveLimits) -> ArchiveReport:
    if not source.is_file():
        raise ArchiveSecurityError("Source archive does not exist")
    try:
        if zipfile.is_zipfile(source):
            report = _extract_zip(source, destination, limits)
        elif tarfile.is_tarfile(source):
            report = _extract_tar(source, destination, limits)
        elif source.read_bytes()[:2] == b"\x1f\x8b":
            report = _extract_gzip_tex(source, destination, limits)
        else:
            report = _extract_plain_tex(source, destination, limits)
        return report
    except Exception:
        shutil.rmtree(destination, ignore_errors=True)
        raise


def _extract_tar(source: Path, destination: Path, limits: ArchiveLimits) -> ArchiveReport:
    with tarfile.open(source, "r:*") as archive:
        members = archive.getmembers()
        files: list[tuple[tarfile.TarInfo, PurePosixPath]] = []
        total = 0
        for member in members:
            path = _safe_relative(member.name, limits.maximum_directory_depth)
            if member.issym() or member.islnk():
                raise ArchiveSecurityError("archive links are not allowed")
            if member.isdir():
                continue
            if not member.isreg():
                raise ArchiveSecurityError("archive contains a non-regular file")
            total = _bounded_totals(len(files), total, member.size, limits)
            files.append((member, path))
        _check_ratio(total, source.stat().st_size, limits)
        destination.mkdir(parents=True, exist_ok=True)
        for member, relative in files:
            target = _inside(destination, relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            extracted = archive.extractfile(member)
            if extracted is None:
                raise ArchiveSecurityError("archive member could not be read")
            with extracted, target.open("wb") as output:
                _copy_bounded(extracted, output, member.size)
        kind = "TAR_GZIP" if source.read_bytes()[:2] == b"\x1f\x8b" else "TAR"
        return ArchiveReport(kind, total, len(files))


def _extract_zip(source: Path, destination: Path, limits: ArchiveLimits) -> ArchiveReport:
    with zipfile.ZipFile(source) as archive:
        files: list[tuple[zipfile.ZipInfo, PurePosixPath]] = []
        total = 0
        for item in archive.infolist():
            relative = _safe_relative(item.filename, limits.maximum_directory_depth)
            mode = item.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise ArchiveSecurityError("archive links are not allowed")
            if item.is_dir():
                continue
            total = _bounded_totals(len(files), total, item.file_size, limits)
            if item.file_size and item.compress_size == 0:
                raise ArchiveSecurityError("archive compression ratio is invalid")
            if (
                item.compress_size
                and item.file_size / item.compress_size > limits.maximum_compression_ratio
            ):
                raise ArchiveSecurityError("archive compression ratio exceeds the configured limit")
            files.append((item, relative))
        _check_ratio(total, source.stat().st_size, limits)
        destination.mkdir(parents=True, exist_ok=True)
        for item, relative in files:
            target = _inside(destination, relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(item) as extracted, target.open("wb") as output:
                _copy_bounded(extracted, output, item.file_size)
        return ArchiveReport("ZIP", total, len(files))


def _extract_gzip_tex(source: Path, destination: Path, limits: ArchiveLimits) -> ArchiveReport:
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / "main.tex"
    total = 0
    with gzip.open(source, "rb") as extracted, target.open("wb") as output:
        while chunk := extracted.read(64 * 1024):
            total += len(chunk)
            if total > min(limits.maximum_single_file_bytes, limits.maximum_extracted_bytes):
                raise ArchiveSecurityError("archive single-file size exceeds the configured limit")
            output.write(chunk)
    _check_ratio(total, source.stat().st_size, limits)
    if not _looks_like_tex(target):
        raise ArchiveSecurityError("gzip payload is not a TeX source")
    return ArchiveReport("GZIP_TEX", total, 1)


def _extract_plain_tex(source: Path, destination: Path, limits: ArchiveLimits) -> ArchiveReport:
    size = source.stat().st_size
    if size > limits.maximum_single_file_bytes or size > limits.maximum_extracted_bytes:
        raise ArchiveSecurityError("Source single-file size exceeds the configured limit")
    if not _looks_like_tex(source):
        raise ArchiveSecurityError("Source format is unsupported")
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination / "main.tex")
    return ArchiveReport("TEX", size, 1)


def _safe_relative(name: str, maximum_depth: int) -> PurePosixPath:
    if not name or "\\" in name or "\x00" in name or any(ord(char) < 32 for char in name):
        raise ArchiveSecurityError("archive path contains an illegal filename")
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ArchiveSecurityError("archive path traversal is not allowed")
    if len(path.parts) - 1 > maximum_depth or any(len(part) > 255 for part in path.parts):
        raise ArchiveSecurityError("archive path exceeds the configured depth or name limit")
    return path


def _inside(root: Path, relative: PurePosixPath) -> Path:
    root_path = root.resolve()
    target = root.joinpath(*relative.parts).resolve()
    if not target.is_relative_to(root_path):
        raise ArchiveSecurityError("archive path escaped the extraction directory")
    return target


def _bounded_totals(
    file_count: int, total: int, size: int, limits: ArchiveLimits
) -> int:
    if size < 0 or size > limits.maximum_single_file_bytes:
        raise ArchiveSecurityError("archive single-file size exceeds the configured limit")
    if file_count + 1 > limits.maximum_file_count:
        raise ArchiveSecurityError("archive file count exceeds the configured limit")
    total += size
    if total > limits.maximum_extracted_bytes:
        raise ArchiveSecurityError("archive expanded size exceeds the configured limit")
    return total


def _check_ratio(total: int, compressed: int, limits: ArchiveLimits) -> None:
    if compressed < 1 or total / compressed > limits.maximum_compression_ratio:
        raise ArchiveSecurityError("archive compression ratio exceeds the configured limit")


def _copy_bounded(source: object, output: object, expected_size: int) -> None:
    written = 0
    while chunk := source.read(64 * 1024):  # type: ignore[attr-defined]
        written += len(chunk)
        if written > expected_size:
            raise ArchiveSecurityError("archive member exceeded its declared size")
        output.write(chunk)  # type: ignore[attr-defined]
    if written != expected_size:
        raise ArchiveSecurityError("archive member size did not match its declaration")


def _looks_like_tex(path: Path) -> bool:
    sample = path.read_bytes()[:4096]
    return b"\\documentclass" in sample or b"\\begin{document}" in sample
