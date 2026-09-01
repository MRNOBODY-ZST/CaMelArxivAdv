from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, replace

from app.extraction.models import (
    Confidence,
    ExtractedAuthor,
    ExtractedContact,
    ExtractionDocument,
    ExtractionEvidence,
    TexCorpus,
)

_COMMAND_START = re.compile(r"\\([A-Za-z@]+)\*?\s*(?:\[[^\]]*\]\s*)?\{")
_EMAIL = re.compile(
    r"(?<![A-Za-z0-9.!#$%&'*+/=?^_`|~-])"
    r"([A-Za-z0-9.!#$%&'*+/=?^_`|~-]+@[^\s{}\\<>@,;:]+)",
    re.IGNORECASE,
)
_AUTHOR_NAMES = {"author", "authors"}
_EMAIL_COMMANDS = {"email", "ead"}
_AFFILIATION_COMMANDS = {"affiliation", "affil", "address", "institute"}
_AUTHOR_METADATA_COMMANDS = _EMAIL_COMMANDS | _AFFILIATION_COMMANDS | {
    "thanks",
    "corref",
}
_STOP_MARKERS = (
    "\\begin{abstract}",
    "\\section",
    "\\begin{thebibliography}",
    "\\bibliography",
)


@dataclass(frozen=True, slots=True)
class _Command:
    name: str
    argument: str
    start: int
    end: int


@dataclass(frozen=True, slots=True)
class _AuthorSpan:
    orders: tuple[int, ...]
    start: int
    end: int


@dataclass(frozen=True, slots=True)
class _Candidate:
    normalized: str
    display: str
    domain: str
    example: bool
    path: str
    position: int
    line: int
    context: str
    command: str | None
    author_order: int | None
    direct: bool
    corresponding: bool


def extract_contacts(corpus: TexCorpus) -> ExtractionDocument:
    authors: list[ExtractedAuthor] = []
    candidates: list[_Candidate] = []
    for tex_file in corpus.files:
        front = _front_matter(tex_file.text)
        commands = _commands(front)
        spans: list[_AuthorSpan] = []
        for command in commands:
            if command.name.lower() not in _AUTHOR_NAMES:
                continue
            ieee_name_blocks = [
                item
                for item in commands
                if item.name.lower() == "ieeeauthorblockn"
                and command.start < item.start
                and item.end <= command.end
            ]
            if ieee_name_blocks:
                for index, block in enumerate(ieee_name_blocks):
                    segment_end = (
                        ieee_name_blocks[index + 1].start
                        if index + 1 < len(ieee_name_blocks)
                        else command.end
                    )
                    block_affiliations = _ieee_affiliations(
                        commands, block.end, segment_end
                    )
                    for raw_name in _author_names(block.argument):
                        name = re.sub(
                            r"^\s*\d+(?:st|nd|rd|th)\s+",
                            "",
                            raw_name,
                            flags=re.I,
                        )
                        if not name:
                            continue
                        order = len(authors) + 1
                        authors.append(
                            ExtractedAuthor(
                                order=order,
                                name=name,
                                affiliations=block_affiliations,
                                corresponding=bool(
                                    re.search(
                                        r"correspond|corref", command.argument, re.I
                                    )
                                ),
                            )
                        )
                        spans.append(_AuthorSpan((order,), block.start, segment_end))
                continue
            names = _author_names(command.argument)
            orders: list[int] = []
            for name in names:
                order = len(authors) + 1
                affiliations = _nearby_affiliations(command, commands)
                corresponding = bool(re.search(r"correspond|corref", command.argument, re.I))
                authors.append(
                    ExtractedAuthor(
                        order=order,
                        name=name,
                        affiliations=affiliations,
                        corresponding=corresponding,
                    )
                )
                orders.append(order)
            if orders:
                spans.append(_AuthorSpan(tuple(orders), command.start, command.end))
        normalized_front = _tex_unescape(front)
        for match in _EMAIL.finditer(normalized_front):
            normalized = _normalize_email(match.group(1))
            if normalized is None:
                continue
            email, domain, example = normalized
            container_command = next(
                (
                    item
                    for item in commands
                    if item.start <= match.start() <= item.end
                    and item.name.lower() in (_EMAIL_COMMANDS | {"thanks"})
                ),
                None,
            )
            span = next((item for item in spans if item.start <= match.start() <= item.end), None)
            author_order = span.orders[0] if span is not None and len(span.orders) == 1 else None
            surrounding = _line_context(normalized_front, match.start(), match.end())
            corresponding = bool(
                (container_command is not None and container_command.name.lower() == "thanks")
                or re.search(r"correspond|corref", surrounding, re.I)
            )
            candidates.append(
                _Candidate(
                    normalized=email,
                    display=email,
                    domain=domain,
                    example=example,
                    path=tex_file.relative_path,
                    position=match.start(),
                    line=normalized_front.count("\n", 0, match.start()) + 1,
                    context=surrounding,
                    command=container_command.name.lower() if container_command else None,
                    author_order=author_order,
                    direct=span is not None or container_command is not None,
                    corresponding=corresponding,
                )
            )
    authors, canonical_orders = _canonicalize_authors(authors)
    candidates = [
        replace(
            candidate,
            author_order=canonical_orders.get(candidate.author_order),
        )
        if candidate.author_order is not None
        else candidate
        for candidate in candidates
    ]
    unique = _deduplicate(candidates)
    contacts: list[ExtractedContact] = []
    for index, candidate in enumerate(unique):
        author_order, confidence, rule = _mapping(candidate, index, unique, authors)
        corresponding = candidate.corresponding
        if author_order is not None and corresponding:
            authors[author_order - 1] = authors[author_order - 1].model_copy(
                update={"corresponding": True}
            )
        contacts.append(
            ExtractedContact(
                normalized_email=candidate.normalized,
                display_email=candidate.display,
                domain=candidate.domain,
                syntax_valid=True,
                example_address=candidate.example,
                author_order=author_order,
                confidence=confidence,
                corresponding=corresponding,
                evidence=(
                    ExtractionEvidence(
                        source_relative_path=candidate.path,
                        rule_name=rule,
                        line_number=candidate.line,
                        logical_location="AUTHOR_FRONT_MATTER",
                        masked_context=_mask_context(candidate.context),
                    ),
                ),
            )
        )
    return ExtractionDocument(
        document_class=corpus.document_class,
        files_inspected=len(corpus.files),
        authors=tuple(authors),
        contacts=tuple(contacts),
    )


def _front_matter(text: str) -> str:
    limit = min(len(text), 50_000)
    for marker in _STOP_MARKERS:
        position = text.find(marker)
        if position >= 0:
            limit = min(limit, position)
    maketitle = text.find("\\maketitle")
    if maketitle >= 0:
        limit = min(limit, maketitle + len("\\maketitle"))
    return text[:limit]


def _commands(text: str) -> tuple[_Command, ...]:
    commands: list[_Command] = []
    for match in _COMMAND_START.finditer(text):
        argument_start = match.end()
        depth = 1
        cursor = argument_start
        while cursor < len(text) and depth:
            character = text[cursor]
            escaped = cursor > 0 and text[cursor - 1] == "\\"
            if character == "{" and not escaped:
                depth += 1
            elif character == "}" and not escaped:
                depth -= 1
            cursor += 1
        if depth == 0:
            commands.append(
                _Command(match.group(1), text[argument_start : cursor - 1], match.start(), cursor)
            )
    return tuple(commands)


def _author_names(argument: str) -> tuple[str, ...]:
    cleaned = _without_commands(argument, _AUTHOR_METADATA_COMMANDS)
    pieces = re.split(r"\\and\b|\\\\|;", cleaned)
    names: list[str] = []
    for piece in pieces:
        name = _plain_tex(piece)
        if not name:
            continue
        if name.count(",") >= 2 and re.search(r",\s*and\s+", name, re.I):
            names.extend(
                item
                for raw in re.split(r"\s*,\s*(?:and\s+)?", name, flags=re.I)
                if (item := raw.strip(" ,"))
            )
        else:
            names.append(name)
    return tuple(names)


def _without_commands(value: str, names: set[str]) -> str:
    spans: list[tuple[int, int]] = []
    for command in _commands(value):
        if command.name.lower() not in names:
            continue
        if spans and command.start >= spans[-1][0] and command.end <= spans[-1][1]:
            continue
        spans.append((command.start, command.end))
    for start, end in reversed(spans):
        value = value[:start] + value[end:]
    return value


def _plain_tex(value: str) -> str:
    previous = ""
    while previous != value:
        previous = value
        value = re.sub(r"\\[A-Za-z@]+\*?(?:\[[^\]]*\])?\{([^{}]*)\}", r"\1", value)
    value = re.sub(r"\\[A-Za-z@]+\*?", " ", value)
    value = re.sub(r"[{}$^_*~]", " ", value)
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value)).strip(" ,")


def _nearby_affiliations(
    author: _Command, commands: tuple[_Command, ...]
) -> tuple[str, ...]:
    following = [
        item
        for item in commands
        if item.start >= author.end
        and item.start - author.end < 1000
        and item.name.lower() in _AFFILIATION_COMMANDS
    ]
    values = tuple(value for item in following[:5] if (value := _plain_tex(item.argument)))
    return tuple(dict.fromkeys(values))


def _ieee_affiliations(
    commands: tuple[_Command, ...], start: int, end: int
) -> tuple[str, ...]:
    values: list[str] = []
    for command in commands:
        if (
            command.name.lower() != "ieeeauthorblocka"
            or command.start < start
            or command.end > end
        ):
            continue
        without_email = _EMAIL.sub("", _tex_unescape(command.argument))
        value = _plain_tex(without_email)
        if value:
            values.append(value)
    return tuple(dict.fromkeys(values))


def _tex_unescape(value: str) -> str:
    return re.sub(r"\\([_#$%&{}])", r"\1", unicodedata.normalize("NFKC", value))


def _normalize_email(raw: str) -> tuple[str, str, bool] | None:
    value = _tex_unescape(raw).strip(" \t\r\n.,;:()[]<>\"'")
    if len(value) > 320 or value.count("@") != 1:
        return None
    local, raw_domain = value.rsplit("@", 1)
    if (
        not local
        or len(local) > 64
        or local.startswith(".")
        or local.endswith(".")
        or ".." in local
    ):
        return None
    try:
        domain = raw_domain.rstrip(".").encode("idna").decode("ascii").lower()
    except UnicodeError:
        return None
    if len(domain) > 255 or "." not in domain or any(
        not label or len(label) > 63 or label.startswith("-") or label.endswith("-")
        for label in domain.split(".")
    ):
        return None
    if re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`|~-]+", local) is None:
        return None
    normalized = f"{local.lower()}@{domain}"
    example = (
        domain in {"example.com", "example.org", "example.net"}
        or domain.endswith(".example")
        or domain.endswith(".invalid")
        or local.lower() in {"test", "noreply", "no-reply"}
    )
    return normalized, domain, example


def _mapping(
    candidate: _Candidate,
    index: int,
    candidates: tuple[_Candidate, ...],
    authors: list[ExtractedAuthor],
) -> tuple[int | None, Confidence, str]:
    if candidate.author_order is not None:
        return candidate.author_order, Confidence.HIGH, "DIRECT_AUTHOR_EMAIL"
    if len(authors) == 1 and len(candidates) == 1:
        return 1, Confidence.HIGH, "SINGLE_AUTHOR_SINGLE_EMAIL"
    if (
        len(authors) == len(candidates)
        and len(authors) > 1
        and all(item.command in _EMAIL_COMMANDS for item in candidates)
    ):
        return index + 1, Confidence.MEDIUM, "POSITIONAL_AUTHOR_EMAIL"
    return None, Confidence.LOW, "PAPER_LEVEL_FRONT_MATTER_EMAIL"


def _canonicalize_authors(
    authors: list[ExtractedAuthor],
) -> tuple[list[ExtractedAuthor], dict[int, int]]:
    canonical: list[ExtractedAuthor] = []
    index_by_name: dict[str, int] = {}
    canonical_orders: dict[int, int] = {}
    for author in authors:
        name_key = _canonical_text(author.name)
        canonical_index = index_by_name.get(name_key)
        if canonical_index is None:
            canonical_index = len(canonical)
            index_by_name[name_key] = canonical_index
            canonical.append(author.model_copy(update={"order": canonical_index + 1}))
        else:
            current = canonical[canonical_index]
            affiliations = _merge_text_values(current.affiliations, author.affiliations)
            canonical[canonical_index] = current.model_copy(
                update={
                    "affiliations": affiliations,
                    "corresponding": current.corresponding or author.corresponding,
                }
            )
        canonical_orders[author.order] = canonical_index + 1
    return canonical, canonical_orders


def _canonical_text(value: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", value)).strip().casefold()


def _merge_text_values(left: tuple[str, ...], right: tuple[str, ...]) -> tuple[str, ...]:
    merged: list[str] = []
    seen: set[str] = set()
    for value in (*left, *right):
        key = _canonical_text(value)
        if key not in seen:
            seen.add(key)
            merged.append(value)
    return tuple(merged)


def _deduplicate(candidates: list[_Candidate]) -> tuple[_Candidate, ...]:
    unique: dict[str, _Candidate] = {}
    for candidate in candidates:
        current = unique.get(candidate.normalized)
        if current is None or (candidate.direct, candidate.corresponding) > (
            current.direct,
            current.corresponding,
        ):
            unique[candidate.normalized] = candidate
    return tuple(unique.values())


def _line_context(text: str, start: int, end: int) -> str:
    line_start = text.rfind("\n", 0, start) + 1
    line_end = text.find("\n", end)
    if line_end < 0:
        line_end = len(text)
    return re.sub(r"\s+", " ", text[line_start:line_end]).strip()[:500]


def _mask_context(context: str) -> str:
    def replace(match: re.Match[str]) -> str:
        normalized = _normalize_email(match.group(1))
        if normalized is None:
            return "***"
        email, domain, _ = normalized
        local = email.rsplit("@", 1)[0]
        prefix = local[:2] if len(local) > 1 else local[:1]
        return f"{prefix}***@{domain}"

    return _EMAIL.sub(replace, _tex_unescape(context))[:600]
