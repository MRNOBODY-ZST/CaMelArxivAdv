from __future__ import annotations

import re
from pathlib import Path

from app.extraction.models import TexCorpus, TexFile

_INCLUDE = re.compile(r"\\(?:input|include)\s*\{([^{}]+)\}")
_DOCUMENT_CLASS = re.compile(r"\\documentclass(?:\[[^\]]*\])?\s*\{([A-Za-z0-9_.-]+)\}")


class TexDiscoveryError(Exception):
    pass


def discover_tex(
    root: Path, *, maximum_include_depth: int, maximum_files: int
) -> TexCorpus:
    if maximum_include_depth < 1 or maximum_files < 1:
        raise ValueError("TeX discovery limits must be positive")
    candidates = sorted(
        path
        for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in {".tex", ".ltx"}
    )
    if not candidates:
        raise TexDiscoveryError("no TeX files were found")
    if len(candidates) > maximum_files:
        raise TexDiscoveryError("TeX file count exceeds the configured limit")
    texts = {path.resolve(): _read_tex(path) for path in candidates}
    included = {
        dependency
        for path, text in texts.items()
        for dependency in _dependencies(path, text, root, texts)
    }
    roots = [path for path in candidates if path.resolve() not in included]
    preferred = [
        path for path in roots or candidates if "\\documentclass" in texts[path.resolve()]
    ]
    root_file = sorted(preferred or roots or candidates)[0]
    ordered: list[TexFile] = []
    visited: set[Path] = set()

    def visit(path: Path, depth: int) -> None:
        resolved = path.resolve()
        if resolved in visited:
            return
        if depth > maximum_include_depth:
            raise TexDiscoveryError("TeX include depth exceeds the configured limit")
        visited.add(resolved)
        text = texts[resolved]
        ordered.append(TexFile(relative_path=path.relative_to(root).as_posix(), text=text))
        if len(ordered) > maximum_files:
            raise TexDiscoveryError("TeX dependency count exceeds the configured limit")
        for dependency in _dependencies(path, text, root, texts):
            visit(dependency, depth + 1)

    visit(root_file, 0)
    document = _DOCUMENT_CLASS.search(texts[root_file.resolve()])
    return TexCorpus(
        root_path=root_file.relative_to(root).as_posix(),
        document_class=document.group(1) if document else None,
        files=tuple(ordered),
    )


def strip_tex_comments(text: str) -> str:
    cleaned: list[str] = []
    for line in text.replace("\r\n", "\n").replace("\r", "\n").splitlines():
        cutoff = len(line)
        for index, character in enumerate(line):
            if character != "%":
                continue
            escapes = 0
            cursor = index - 1
            while cursor >= 0 and line[cursor] == "\\":
                escapes += 1
                cursor -= 1
            if escapes % 2 == 0:
                cutoff = index
                break
        cleaned.append(line[:cutoff])
    return "\n".join(cleaned)


def _read_tex(path: Path) -> str:
    raw = path.read_bytes()
    if b"\x00" in raw:
        raise TexDiscoveryError("TeX file contains binary content")
    for encoding in ("utf-8-sig", "latin-1"):
        try:
            return strip_tex_comments(raw.decode(encoding))
        except UnicodeDecodeError:
            continue
    raise TexDiscoveryError("TeX file encoding is unsupported")


def _dependencies(
    source: Path, text: str, root: Path, texts: dict[Path, str]
) -> tuple[Path, ...]:
    dependencies: list[Path] = []
    root_resolved = root.resolve()
    for match in _INCLUDE.finditer(text):
        value = match.group(1).strip()
        if not value or "\\" in value:
            continue
        candidate = source.parent / value
        if candidate.suffix.lower() not in {".tex", ".ltx"}:
            candidate = candidate.with_suffix(".tex")
        resolved = candidate.resolve()
        if resolved.is_relative_to(root_resolved) and resolved in texts:
            dependencies.append(candidate)
    return tuple(dependencies)
