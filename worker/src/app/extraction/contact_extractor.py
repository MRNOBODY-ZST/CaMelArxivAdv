from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, replace

from app.email_validation import has_public_dns_name_syntax
from app.extraction.models import (
    Confidence,
    ExtractedAuthor,
    ExtractedContact,
    ExtractionDocument,
    ExtractionEvidence,
    TexCorpus,
)
from app.source_safety import (
    contains_email_like_text,
    redact_email_like_tokens,
    truncate_utf16,
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
_NON_ROOT_CONTACT_COMMANDS = _EMAIL_COMMANDS | _AFFILIATION_COMMANDS | {"thanks"}
_CORRESPONDENCE_LABEL = re.compile(
    r"\b(?:corresponding\s+author|correspondence)\s*(?::|\uFF1A|[-\u2014])\s*$",
    re.IGNORECASE,
)
_AUTHOR_METADATA_COMMANDS = _EMAIL_COMMANDS | _AFFILIATION_COMMANDS | {
    "thanks",
    "corref",
    "ieeeauthorrefmark",
    "ieeemembership",
    "ieeecompsocitemizethanks",
}
# These classes define a top-level contact command as metadata for the preceding author.
# Unknown class/package combinations keep using the conservative paper-level mapping rules.
_SIBLING_CONTACT_COMMANDS = {
    "acmart": frozenset({"email"}),
    "elsarticle": frozenset({"ead"}),
    "revtex4": frozenset({"email"}),
    "revtex4-1": frozenset({"email"}),
    "revtex4-2": frozenset({"email"}),
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
class _SiblingAuthor:
    order: int
    command: _Command


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
    ambiguous_author_mapping: bool


def extract_contacts(
    corpus: TexCorpus, metadata_authors: tuple[str, ...] = ()
) -> ExtractionDocument:
    authors: list[ExtractedAuthor] = []
    candidates: list[_Candidate] = []
    has_unproven_author_declaration = False
    for tex_file in corpus.files:
        front = _front_matter(tex_file.text)
        normalized_front = _front_matter_matching_view(front)
        commands = _commands(normalized_front)
        spans: list[_AuthorSpan] = []
        author_declarations: list[tuple[_Command, tuple[int, ...]]] = []
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
                    names = _author_names(block.argument, metadata_authors)
                    if not names:
                        has_unproven_author_declaration = True
                    orders: list[int] = []
                    for raw_name in names:
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
                                corresponding=len(names) == 1 and bool(
                                    re.search(
                                        r"correspond|corref", block.argument, re.I
                                    )
                                ),
                            )
                        )
                        orders.append(order)
                    # An empty span keeps unproven names from claiming an email through fallback.
                    spans.append(_AuthorSpan(tuple(orders), block.start, segment_end))
                continue
            names = _author_names(command.argument, metadata_authors)
            if not names:
                has_unproven_author_declaration = True
            orders = []
            for name in names:
                order = len(authors) + 1
                affiliations = _nearby_affiliations(command, commands)
                corresponding = len(names) == 1 and bool(
                    re.search(r"correspond|corref", command.argument, re.I)
                )
                authors.append(
                    ExtractedAuthor(
                        order=order,
                        name=name,
                        affiliations=affiliations,
                        corresponding=corresponding,
                    )
                )
                orders.append(order)
            spans.append(_AuthorSpan(tuple(orders), command.start, command.end))
            if command.name.casefold() == "author":
                author_declarations.append((command, tuple(orders)))
        sibling_authors = _sibling_authors(
            corpus.document_class, commands, author_declarations
        )
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
                    and item.name.lower() in _NON_ROOT_CONTACT_COMMANDS
                ),
                None,
            )
            covering_spans = [item for item in spans if item.start <= match.start() < item.end]
            span_orders = {order for span in covering_spans for order in span.orders}
            sibling_author = (
                sibling_authors.get(container_command.start)
                if container_command is not None
                else None
            )
            if sibling_author is not None:
                span_orders.add(sibling_author.order)
            explicit_correspondence = _has_explicit_correspondence_label(
                normalized_front, match.start()
            )
            if (
                tex_file.relative_path != corpus.root_path
                and container_command is None
                and not covering_spans
                and not explicit_correspondence
            ):
                continue
            author_order = next(iter(span_orders)) if len(span_orders) == 1 else None
            surrounding = (
                _sibling_context(
                    normalized_front,
                    sibling_author.command,
                    container_command,
                )
                if sibling_author is not None and container_command is not None
                else _line_context(normalized_front, match.start(), match.end())
            )
            corresponding = bool(
                re.search(r"correspond|corref", surrounding, re.I)
                or (author_order is not None and authors[author_order - 1].corresponding)
            )
            candidates.append(
                _Candidate(
                    normalized=email,
                    display=email,
                    domain=domain,
                    example=example,
                    path=_redact_source_path(tex_file.relative_path),
                    position=match.start(),
                    line=normalized_front.count("\n", 0, match.start()) + 1,
                    context=surrounding,
                    command=container_command.name.lower() if container_command else None,
                    author_order=author_order,
                    direct=(
                        bool(covering_spans)
                        or sibling_author is not None
                        or container_command is not None
                    ),
                    corresponding=corresponding,
                    ambiguous_author_mapping=len(span_orders) > 1 or any(
                        len(span.orders) != 1 for span in covering_spans
                    ),
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
    complete_author_list = not has_unproven_author_declaration and (
        not metadata_authors
        or sorted(_canonical_text(author.name) for author in authors)
        == sorted(_canonical_text(name) for name in metadata_authors)
    )
    contacts: list[ExtractedContact] = []
    for index, candidate in enumerate(unique):
        author_order, confidence, rule = _mapping(
            candidate, index, unique, authors, complete_author_list
        )
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


def _sibling_authors(
    document_class: str | None,
    commands: tuple[_Command, ...],
    author_declarations: list[tuple[_Command, tuple[int, ...]]],
) -> dict[int, _SiblingAuthor]:
    if document_class is None:
        return {}
    supported = _SIBLING_CONTACT_COMMANDS.get(document_class.casefold())
    if supported is None:
        return {}
    declarations = [
        item
        for item in author_declarations
        if not _has_command_parent(item[0], commands)
    ]
    segments: list[tuple[_Command, tuple[int, ...], tuple[_Command, ...]]] = []
    final_command_end = commands[-1].end if commands else 0
    for index, (author, orders) in enumerate(declarations):
        end = (
            declarations[index + 1][0].start
            if index + 1 < len(declarations)
            else final_command_end
        )
        contact_commands = tuple(
            command
            for command in commands
            if command.name.casefold() in supported
            and author.end <= command.start < end
            and not _has_command_parent(command, commands)
        )
        contacts = tuple(
            command for command in contact_commands if _email_count(command.argument) > 0
        )
        segments.append((author, orders, contacts))

    mapped: dict[int, _SiblingAuthor] = {}
    for index, (author, orders, contacts) in enumerate(segments):
        if (
            len(orders) != 1
            or not contacts
            # A run of authors followed by contacts can be a shared author group.
            or (index > 0 and not segments[index - 1][2])
            # Grouped addresses inside one command do not prove individual ownership.
            or any(_email_count(command.argument) != 1 for command in contacts)
        ):
            continue
        for command in contacts:
            mapped[command.start] = _SiblingAuthor(orders[0], author)
    return mapped


def _has_command_parent(command: _Command, commands: tuple[_Command, ...]) -> bool:
    return any(
        parent.start < command.start and command.end <= parent.end
        for parent in commands
    )


def _email_count(value: str) -> int:
    return sum(
        _normalize_email(match.group(1)) is not None
        for match in _EMAIL.finditer(value)
    )


def _author_names(
    argument: str, metadata_authors: tuple[str, ...] = ()
) -> tuple[str, ...]:
    cleaned = _without_commands(argument, _AUTHOR_METADATA_COMMANDS)
    pieces = re.split(r"\\and\b|\\\\|;", cleaned)
    names: list[str] = []
    for piece in pieces:
        name = _plain_tex(piece)
        if not name:
            continue
        comma_names = _comma_author_names(name, metadata_authors)
        if comma_names is not None:
            if not comma_names:
                # Reject the whole block; layout/affiliation lines cannot replace its people.
                return ()
            names.extend(comma_names)
        else:
            names.append(name)
    if metadata_authors:
        expected = tuple(_canonical_text(name) for name in metadata_authors)
        names = [re.sub(r"^\s*\d+(?:st|nd|rd|th)\s+", "", name, flags=re.I) for name in names]
        # A partial match must not turn a shared author block into one proven person.
        if any(expected.count(_canonical_text(name)) != 1 for name in names):
            return ()
    return tuple(names)


def _comma_author_names(
    value: str, metadata_authors: tuple[str, ...]
) -> tuple[str, ...] | None:
    if "," not in value:
        return None
    expected = tuple(_canonical_text(name) for name in metadata_authors)
    # Commas can be a surname/suffix or a list: only the complete metadata resolves that ambiguity.
    if len(expected) == 1 and _canonical_text(value) == expected[0]:
        return (value,)
    names = tuple(
        item
        for raw in re.split(r"\s*,\s*(?:and\s+)?", value, flags=re.I)
        if (item := raw.strip(" ,"))
    )
    actual = tuple(_canonical_text(name) for name in names)
    return names if expected and actual == expected else ()


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
    values = tuple(
        value
        for item in following[:5]
        if (value := _safe_affiliation(item.argument))
    )
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
        without_refmarks = _without_commands(command.argument, {"ieeeauthorrefmark"})
        value = _safe_affiliation(without_refmarks)
        if value:
            values.append(value)
    return tuple(dict.fromkeys(values))


def _tex_unescape(value: str) -> str:
    return re.sub(r"\\([_#$%&{}])", r"\1", unicodedata.normalize("NFKC", value))


def _front_matter_matching_view(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value)
    return re.sub(r"\\([_#$%&])", r"\1", normalized)


def _safe_affiliation(value: str) -> str:
    scrubbed = redact_email_like_tokens(_tex_unescape(value), "")
    return _plain_tex(scrubbed).strip(" ,;:")


def _redact_source_path(value: str) -> str:
    return "/".join(
        "redacted" if contains_email_like_text(part) else part
        for part in value.split("/")
    )


def _normalize_email(raw: str) -> tuple[str, str, bool] | None:
    value = _tex_unescape(raw).strip(" \t\r\n.,;:()[]<>\"'")
    while len(value) > 2 and value.startswith("$") and value.endswith("$"):
        value = value[1:-1].strip()
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
    if not has_public_dns_name_syntax(domain):
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
    complete_author_list: bool,
) -> tuple[int | None, Confidence, str]:
    if candidate.ambiguous_author_mapping:
        # Equal list lengths cannot resolve an explicitly shared or conflicting association.
        return None, Confidence.LOW, "AMBIGUOUS_AUTHOR_EMAIL"
    if candidate.author_order is not None:
        return candidate.author_order, Confidence.HIGH, "DIRECT_AUTHOR_EMAIL"
    if not complete_author_list:
        return None, Confidence.LOW, "INCOMPLETE_AUTHOR_IDENTITY"
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
            canonical.append(
                ExtractedAuthor(
                    order=canonical_index + 1,
                    name=author.name,
                    affiliations=author.affiliations,
                    corresponding=author.corresponding,
                )
            )
        else:
            current = canonical[canonical_index]
            affiliations = _merge_text_values(current.affiliations, author.affiliations)
            canonical[canonical_index] = ExtractedAuthor(
                order=current.order,
                name=current.name,
                affiliations=affiliations,
                corresponding=current.corresponding or author.corresponding,
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
        if current is None:
            unique[candidate.normalized] = candidate
            continue
        preferred = (
            candidate
            if (candidate.direct, candidate.corresponding)
            > (current.direct, current.corresponding)
            else current
        )
        conflicting_authors = (
            current.author_order is not None
            and candidate.author_order is not None
            and current.author_order != candidate.author_order
        )
        if (
            current.ambiguous_author_mapping
            or candidate.ambiguous_author_mapping
            or conflicting_authors
        ):
            # Keep the conflict even if a later occurrence repeats one of the proposed authors.
            preferred = replace(preferred, author_order=None, ambiguous_author_mapping=True)
        unique[candidate.normalized] = preferred
    return tuple(unique.values())


def _line_context(text: str, start: int, end: int) -> str:
    line_start = text.rfind("\n", 0, start) + 1
    line_end = text.find("\n", end)
    if line_end < 0:
        line_end = len(text)
    return re.sub(r"\s+", " ", text[line_start:line_end]).strip()[:500]


def _sibling_context(text: str, author: _Command, contact: _Command) -> str:
    author_context = re.sub(r"\s+", " ", text[author.start : author.end]).strip()
    email_context = re.sub(r"\s+", " ", text[contact.start : contact.end]).strip()
    return (
        truncate_utf16(author_context, 280)
        + " | "
        + truncate_utf16(email_context, 200)
    )


def _has_explicit_correspondence_label(text: str, email_start: int) -> bool:
    line_start = text.rfind("\n", 0, email_start) + 1
    prefix = _plain_tex(text[line_start:email_start][-160:])
    return _CORRESPONDENCE_LABEL.fullmatch(prefix) is not None


def _mask_context(context: str) -> str:
    redacted = redact_email_like_tokens(_tex_unescape(context), "[email redacted]")
    return truncate_utf16(redacted, 600)
