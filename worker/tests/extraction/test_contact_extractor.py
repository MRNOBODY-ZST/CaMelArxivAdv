from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from app.extraction.contact_extractor import extract_contacts
from app.extraction.models import Confidence, ExtractionDocument, TexCorpus, TexFile
from app.extraction.tex_discovery import discover_tex


def extract(
    tmp_path: Path,
    source: str,
    metadata_authors: tuple[str, ...] = (),
) -> ExtractionDocument:
    (tmp_path / "main.tex").write_text(source, encoding="utf-8")
    return extract_contacts(
        discover_tex(tmp_path, maximum_include_depth=8, maximum_files=20),
        metadata_authors,
    )


def test_single_author_direct_email_is_high_confidence_and_evidence_is_masked(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice Example\thanks{Corresponding author: alice@example.edu}}
\begin{document}\maketitle\begin{abstract}x\end{abstract}
""",
    )

    assert [author.name for author in result.authors] == ["Alice Example"]
    assert result.authors[0].corresponding is True
    assert len(result.contacts) == 1
    contact = result.contacts[0]
    assert contact.normalized_email == "alice@example.edu"
    assert contact.author_order == 1
    assert contact.confidence is Confidence.HIGH
    assert contact.corresponding is True
    assert "alice@example.edu" not in contact.evidence[0].masked_context
    assert "[email redacted]" in contact.evidence[0].masked_context
    assert "@" not in contact.evidence[0].masked_context


def test_evidence_redacts_every_email_like_token_on_the_source_line(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r'''\documentclass{article}
\author{Alice Example}
\thanks{Contact alice@example.edu, josé@example.org, "bob"@example.net, 用户@example.cn}
\begin{document}\maketitle
''',
    )

    context = result.contacts[0].evidence[0].masked_context
    assert "@" not in context
    assert "alice" not in context
    assert "josé" not in context
    assert "bob" not in context
    assert "用户" not in context


def test_affiliations_drop_ascii_unicode_and_quoted_email_like_tokens(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r'''\documentclass{article}
\author{Alice Example}
\affil{Example University;alice@example.edu;josé@example.org;"bob"@example.net}
\begin{document}\maketitle
''',
    )

    assert result.authors[0].affiliations == ("Example University",)
    assert "@" not in result.authors[0].affiliations[0]


def test_email_like_author_name_is_rejected_instead_of_becoming_public_data(
    tmp_path: Path,
) -> None:
    with pytest.raises(ValidationError, match="author name"):
        extract(
            tmp_path,
            r'''\documentclass{article}
\author{用户@example.org}
\begin{document}\maketitle
''',
        )


def test_email_like_source_filename_is_irreversibly_redacted(tmp_path: Path) -> None:
    corpus = TexCorpus(
        root_path="/bounded/source",
        document_class="article",
        files=(
            TexFile(
                relative_path="authors/alice@example.edu.tex",
                text=(
                    "\\author{Alice Example}\\email{alice@example.edu}"
                    "\\begin{document}\\maketitle"
                ),
            ),
        ),
    )

    evidence = extract_contacts(corpus).contacts[0].evidence[0]

    assert evidence.source_relative_path == "authors/redacted"
    assert "@" not in evidence.source_relative_path


def test_equal_author_and_email_lists_map_positionally_at_medium_confidence(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice One \and Bob Two}
\email{alice@uni.edu}
\email{bob@lab.org}
\begin{document}\maketitle\begin{abstract}x\end{abstract}
""",
    )

    assert [author.name for author in result.authors] == ["Alice One", "Bob Two"]
    assert [(item.author_order, item.confidence) for item in result.contacts] == [
        (1, Confidence.MEDIUM),
        (2, Confidence.MEDIUM),
    ]


@pytest.mark.parametrize(
    "document_class, contact_command",
    [
        ("acmart", "email"),
        ("revtex4-2", "email"),
        ("elsarticle", "ead"),
    ],
)
def test_supported_single_author_sibling_contacts_are_direct_high_confidence(
    tmp_path: Path, document_class: str, contact_command: str,
) -> None:
    result = extract(
        tmp_path,
        f"""\\documentclass{{{document_class}}}
\\author{{Alice One}}
\\orcid{{0000-0000-0000-0001}}
\\affiliation{{\\institution{{Lab One}}}}
\\{contact_command}{{alice@example.org}}
\\author{{Bob Two}}
\\authornote{{Corresponding author.}}
\\affiliation{{\\institution{{Lab Two}}}}
\\{contact_command}{{bob@example.org}}
\\begin{{document}}\\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [contact.author_order for contact in result.contacts] == [1, 2]
    assert [contact.confidence for contact in result.contacts] == [Confidence.HIGH] * 2
    assert [contact.evidence[0].rule_name for contact in result.contacts] == [
        "DIRECT_AUTHOR_EMAIL",
        "DIRECT_AUTHOR_EMAIL",
    ]
    contexts = [contact.evidence[0].masked_context for contact in result.contacts]
    assert "\\author{Alice One}" in contexts[0]
    assert "\\author{Bob Two}" in contexts[1]
    assert all("[email redacted]" in context for context in contexts)
    assert all("@" not in context for context in contexts)
    assert all("Lab One" not in context and "Lab Two" not in context for context in contexts)


def test_acmart_shared_author_block_keeps_equal_email_list_positional(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{acmart}
\author{Alice One \and Bob Two}
\email{alice@example.org}
\email{bob@example.org}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [contact.author_order for contact in result.contacts] == [1, 2]
    assert [contact.confidence for contact in result.contacts] == [Confidence.MEDIUM] * 2
    assert [contact.evidence[0].rule_name for contact in result.contacts] == [
        "POSITIONAL_AUTHOR_EMAIL",
        "POSITIONAL_AUTHOR_EMAIL",
    ]


def test_elsarticle_url_ead_does_not_hide_direct_sibling_email(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{elsarticle}
\author{Alice One}
\ead{alice@example.org}
\ead[url]{https://example.org/alice}
\author{Bob Two}
\ead{bob@example.org}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [contact.author_order for contact in result.contacts] == [1, 2]
    assert [contact.confidence for contact in result.contacts] == [Confidence.HIGH] * 2


def test_acmart_consecutive_authors_before_one_email_stay_unmapped(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{acmart}
\author{Alice One}
\author{Bob Two}
\email{shared@example.org}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW


def test_acmart_consecutive_authors_before_equal_email_list_stays_positional(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{acmart}
\author{Alice One}
\author{Bob Two}
\email{alice@example.org}
\email{bob@example.org}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [contact.confidence for contact in result.contacts] == [Confidence.MEDIUM] * 2
    assert all(
        contact.evidence[0].rule_name == "POSITIONAL_AUTHOR_EMAIL"
        for contact in result.contacts
    )


def test_acmart_multi_address_email_command_is_not_direct_author_evidence(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{acmart}
\author{Alice One}
\email{alice@example.org, alternate@example.org}
\begin{document}\maketitle
""",
        ("Alice One",),
    )

    assert all(contact.author_order is None for contact in result.contacts)
    assert all(contact.confidence is Confidence.LOW for contact in result.contacts)


@pytest.mark.parametrize("wrapper", ["footnote", "thanks"])
def test_acmart_nested_unrelated_email_is_not_direct_author_evidence(
    tmp_path: Path, wrapper: str,
) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{acmart}\n"
        "\\author{Alice One}\n"
        "\\email{alice@example.org}\n"
        f"\\{wrapper}{{Artifact maintainer: \\email{{maintainer@example.org}}}}\n"
        "\\author{Bob Two}\n"
        "\\email{bob@example.org}\n"
        "\\begin{document}\\maketitle\n",
        ("Alice One", "Bob Two"),
    )

    assert result.contacts[0].author_order == 1
    assert result.contacts[0].confidence is Confidence.HIGH
    assert result.contacts[1].confidence is not Confidence.HIGH
    assert result.contacts[2].author_order == 2
    assert result.contacts[2].confidence is Confidence.HIGH


def test_acmart_unverified_author_identity_cannot_claim_a_sibling_email(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{acmart}
\author{Unknown Person}
\email{unknown@example.org}
\begin{document}\maketitle
""",
        ("Known Person",),
    )

    assert result.authors == ()
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW


def test_dropped_author_declaration_disables_single_author_email_fallback(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{acmart}
\author{Alice One}
\author{Unknown Person}
\email{unknown@example.org}
\begin{document}\maketitle
""",
        ("Alice One",),
    )

    assert [author.name for author in result.authors] == ["Alice One"]
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW
    assert result.contacts[0].evidence[0].rule_name == "INCOMPLETE_AUTHOR_IDENTITY"


def test_dropped_author_declaration_disables_positional_email_fallback(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice One}
\email{alice@example.org}
\author{Bob Two}
\email{bob@example.org}
\author{Unknown Person}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [author.name for author in result.authors] == ["Alice One", "Bob Two"]
    assert all(contact.author_order is None for contact in result.contacts)
    assert all(contact.confidence is Confidence.LOW for contact in result.contacts)
    assert all(
        contact.evidence[0].rule_name == "INCOMPLETE_AUTHOR_IDENTITY"
        for contact in result.contacts
    )


def test_unknown_document_class_keeps_sibling_email_mapping_positional(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{customarticle}
\author{Alice One}
\email{alice@example.org}
\author{Bob Two}
\email{bob@example.org}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [contact.confidence for contact in result.contacts] == [Confidence.MEDIUM] * 2


def test_unsupported_authors_macro_keeps_sibling_email_mapping_positional(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{acmart}
\authors{Alice One}
\email{alice@example.org}
\authors{Bob Two}
\email{bob@example.org}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [contact.confidence for contact in result.contacts] == [Confidence.MEDIUM] * 2


@pytest.mark.parametrize(
    "source",
    [
        r"\documentclass{revtex4-2}\author{Alice}\email{alice@uni.edu}\begin{document}",
        r"\documentclass{IEEEtran}\author{Alice\thanks{alice@uni.edu}}\begin{document}",
        r"\documentclass{elsarticle}\author{Alice}\ead{alice@uni.edu}\begin{document}",
        r"\documentclass{article}\usepackage{authblk}\author{Alice}\affil{Lab}\email{alice@uni.edu}\begin{document}",
    ],
)
def test_common_document_families_keep_explicit_addresses(tmp_path: Path, source: str) -> None:
    result = extract(tmp_path, source)

    assert result.document_class in {"revtex4-2", "IEEEtran", "elsarticle", "article"}
    assert result.contacts[0].normalized_email == "alice@uni.edu"


def test_ieee_author_blocks_do_not_promote_affiliations_or_emails_to_authors(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{1st Alice Example}
\IEEEauthorblockA{Example University and Example Laboratory\\
Example City, Test Region\\ alice@laboratory.test}
\and
\IEEEauthorblockN{2nd Bob Example}
\IEEEauthorblockA{Example University\\
Example City, Test Region\\ bob@laboratory.test}}
\begin{document}\maketitle
""",
    )

    assert [author.name for author in result.authors] == [
        "Alice Example",
        "Bob Example",
    ]
    assert [contact.normalized_email for contact in result.contacts] == [
        "alice@laboratory.test",
        "bob@laboratory.test",
    ]
    assert [contact.author_order for contact in result.contacts] == [1, 2]
    assert all(contact.confidence is Confidence.HIGH for contact in result.contacts)


@pytest.mark.parametrize("email_command", [None, "email", "ead"])
def test_shared_ieee_name_block_keeps_all_emails_unmapped_even_with_equal_lists(
    tmp_path: Path, email_command: str | None
) -> None:
    emails = ("alpha@example.edu", "beta@example.edu")
    email_lines = "\n".join(
        f"\\{email_command}{{{email}}}" if email_command else email for email in emails
    )
    result = extract(
        tmp_path,
        "\\documentclass{IEEEtran}\n"
        "\\author{\n\\IEEEauthorblockN{Alpha One \\and Beta Two}\n"
        f"\\IEEEauthorblockA{{Example University\\\\\n{email_lines}}}}}\n"
        "\\begin{document}\\maketitle\n",
    )

    assert [author.name for author in result.authors] == ["Alpha One", "Beta Two"]
    assert len(result.contacts) == 2
    assert all(contact.author_order is None for contact in result.contacts)
    assert all(contact.confidence is Confidence.LOW for contact in result.contacts)
    assert all(
        contact.evidence[0].rule_name == "AMBIGUOUS_AUTHOR_EMAIL"
        for contact in result.contacts
    )


def test_shared_ieee_metadata_verified_comma_names_do_not_create_overlapping_single_spans(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{Alpha One, and Beta Two}
\IEEEauthorblockA{Example University\\
alpha@example.edu, beta@example.edu}}
\begin{document}\maketitle
""",
        ("Alpha One", "Beta Two"),
    )

    assert [author.name for author in result.authors] == ["Alpha One", "Beta Two"]
    assert len(result.contacts) == 2
    assert all(contact.author_order is None for contact in result.contacts)
    assert all(contact.confidence is Confidence.LOW for contact in result.contacts)


def test_gitskills_shaped_comma_newline_author_block_is_a_verified_group_not_one_author(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{Alice Example\IEEEauthorrefmark{1},
Bob Example\IEEEauthorrefmark{2},
Carol Example\IEEEauthorrefmark{2},
Dana Example\IEEEauthorrefmark{3}}
\IEEEauthorblockA{\IEEEauthorrefmark{1}University One
\quad first@example.edu}
\IEEEauthorblockA{\IEEEauthorrefmark{2}University Two}
\IEEEauthorblockA{\IEEEauthorrefmark{3}University Three
\quad fourth@example.edu}}
\begin{document}\maketitle
""",
        ("Alice Example", "Bob Example", "Carol Example", "Dana Example"),
    )

    assert [author.name for author in result.authors] == [
        "Alice Example", "Bob Example", "Carol Example", "Dana Example"
    ]
    assert len(result.contacts) == 2
    assert all(contact.author_order is None for contact in result.contacts)
    assert all(contact.confidence is Confidence.LOW for contact in result.contacts)
    assert all(
        contact.evidence[0].rule_name == "AMBIGUOUS_AUTHOR_EMAIL"
        for contact in result.contacts
    )


@pytest.mark.parametrize("name", ["Smith, Alice", "Alice Smith, Jr."])
def test_single_author_comma_name_requires_an_exact_metadata_match(
    tmp_path: Path, name: str
) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{IEEEtran}\n"
        f"\\author{{\\IEEEauthorblockN{{{name}}}\n"
        "\\IEEEauthorblockA{Example University\\\\alice@example.edu}}\n"
        "\\begin{document}\\maketitle\n",
        (name,),
    )

    assert [author.name for author in result.authors] == [name]
    assert result.contacts[0].author_order == 1
    assert result.contacts[0].confidence is Confidence.HIGH


@pytest.mark.parametrize(
    "name,metadata",
    [
        ("Smith, Alice", ()),
        ("Alice Smith, Jr.", ()),
        ("Alpha One, Beta Two", ()),
        ("Alpha One, Beta Two", ("Alpha One", "Different Person", "Known Other")),
        ("Alpha One, Beta Two", ("Beta Two", "Alpha One", "Known Other")),
    ],
)
def test_unproven_comma_block_does_not_claim_the_only_remaining_author(
    tmp_path: Path, name: str, metadata: tuple[str, ...]
) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{IEEEtran}\n"
        f"\\author{{\\IEEEauthorblockN{{{name}}}\n"
        "\\IEEEauthorblockA{Shared University\\\\shared@example.edu}\n"
        "\\and\\IEEEauthorblockN{Known Other}\n"
        "\\IEEEauthorblockA{Other University}}\n"
        "\\begin{document}\\maketitle\n",
        metadata,
    )

    assert [author.name for author in result.authors] == ["Known Other"]
    assert len(result.contacts) == 1
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW
    assert result.contacts[0].evidence[0].rule_name == "AMBIGUOUS_AUTHOR_EMAIL"


def test_unproven_non_ieee_comma_block_cannot_use_single_author_fallback(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Unknown One, Unknown Two\thanks{shared@example.edu}}
\author{Known Other}
\begin{document}\maketitle
""",
    )

    assert [author.name for author in result.authors] == ["Known Other"]
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW


def test_shared_ieee_named_correspondence_never_promotes_the_first_author(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{Alpha One \and Beta Two}
\IEEEauthorblockA{Example University\\
alpha@example.edu, beta@example.edu}
\thanks{Corresponding author: Beta Two (beta@example.edu)}}
\begin{document}\maketitle
""",
    )

    assert len(result.contacts) == 2
    assert all(contact.author_order is None for contact in result.contacts)
    assert all(contact.confidence is Confidence.LOW for contact in result.contacts)
    assert not any(author.corresponding for author in result.authors)
    assert result.contacts[1].corresponding is True
    assert "Beta Two" in result.contacts[1].evidence[0].masked_context


def test_ieee_correspondence_is_scoped_to_the_single_author_block(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{Alpha One}
\IEEEauthorblockA{Lab One\\alpha@example.edu}
\and
\IEEEauthorblockN{Beta Two\thanks{Corresponding author}}
\IEEEauthorblockA{Lab Two\\beta@example.edu}}
\begin{document}\maketitle
""",
    )

    assert [contact.author_order for contact in result.contacts] == [1, 2]
    assert all(contact.confidence is Confidence.HIGH for contact in result.contacts)
    assert [author.corresponding for author in result.authors] == [False, True]
    assert [contact.corresponding for contact in result.contacts] == [False, True]


@pytest.mark.parametrize("reversed_blocks", [False, True])
def test_repeated_email_with_conflicting_authors_stays_ambiguous_after_later_repeats(
    tmp_path: Path, reversed_blocks: bool
) -> None:
    names = (
        ["Beta Two", "Alpha One", "Beta Two"]
        if reversed_blocks
        else ["Alpha One", "Beta Two", "Alpha One"]
    )
    blocks = "\n\\and\n".join(
        f"\\IEEEauthorblockN{{{name}}}\n"
        "\\IEEEauthorblockA{Example University\\\\shared@example.edu}"
        for name in names
    )
    result = extract(
        tmp_path,
        f"\\documentclass{{IEEEtran}}\n\\author{{{blocks}}}\n"
        "\\begin{document}\\maketitle\n",
    )

    assert len(result.authors) == 2
    assert len(result.contacts) == 1
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW
    assert result.contacts[0].evidence[0].rule_name == "AMBIGUOUS_AUTHOR_EMAIL"


def test_repeated_same_author_email_remains_direct_after_canonicalization(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{Alpha One}
\IEEEauthorblockA{Lab One\\alpha@example.edu}
\and
\IEEEauthorblockN{ALPHA ONE}
\IEEEauthorblockA{Lab Two\\alpha@example.edu}}
\begin{document}\maketitle
""",
    )

    assert len(result.authors) == 1
    assert len(result.contacts) == 1
    assert result.contacts[0].author_order == 1
    assert result.contacts[0].confidence is Confidence.HIGH


def test_shared_non_ieee_block_does_not_propagate_correspondence_to_every_author(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alpha One \and Beta Two\thanks{Corresponding author: beta@example.edu}}
\begin{document}\maketitle
""",
    )

    assert not any(author.corresponding for author in result.authors)
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW
    assert result.contacts[0].corresponding is True


def test_ordinary_thanks_with_email_does_not_invent_corresponding_status(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alpha One\thanks{Research support: alpha@example.edu}}
\begin{document}\maketitle
""",
    )

    assert result.contacts[0].author_order == 1
    assert result.contacts[0].confidence is Confidence.HIGH
    assert result.contacts[0].corresponding is False
    assert result.authors[0].corresponding is False


def test_nested_thanks_does_not_turn_ieee_affiliations_into_an_author(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{Alice Example, Bob Example, Carol Example, Dana Example, and Evan Example\thanks{
This work was supported by a bounded research grant.}
\IEEEcompsocitemizethanks{
\IEEEcompsocthanksitem Alice Example and Bob Example are with the \textit{Example
University, Test Region}. Carol Example is with the \textit{Fictional University
of Science and Technology, Example City, Test Region}.
\IEEEcompsocthanksitem Dana Example is with the \textit{Example University of
Computing, Sample City, and the Fictional Institute of Artificial Intelligence and
Robotics for Research, Sample City, Test Region}. Evan Example is with the \textit{Institute
for Example Science, Fictional Information and Technology, Test City, Test Region}.
Contact: \texttt{evan@institute.test}.}}
\begin{document}\maketitle
""",
        (
            "Alice Example",
            "Bob Example",
            "Carol Example",
            "Dana Example",
            "Evan Example",
        ),
    )

    assert [author.name for author in result.authors] == [
        "Alice Example",
        "Bob Example",
        "Carol Example",
        "Dana Example",
        "Evan Example",
    ]
    assert all("University" not in author.name for author in result.authors)
    assert [contact.normalized_email for contact in result.contacts] == [
        "evan@institute.test"
    ]


def test_nested_mailto_thanks_is_removed_without_losing_the_contact(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice Example\thanks{\href{mailto:alice@uni.edu}{Corresponding author}}}
\begin{document}\maketitle
""",
    )

    assert [author.name for author in result.authors] == ["Alice Example"]
    assert [contact.normalized_email for contact in result.contacts] == ["alice@uni.edu"]


def test_author_formatting_commands_remain_after_metadata_removal(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{\textbf{Alice Example} \and Bob Example}
\begin{document}\maketitle
""",
    )

    assert [author.name for author in result.authors] == ["Alice Example", "Bob Example"]


def test_math_wrapped_email_drops_tex_delimiters_before_domain_validation(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice Example}
\thanks{Contact: \texttt{$alice@laboratory.test$}}
\begin{document}\maketitle
""",
    )

    assert [contact.normalized_email for contact in result.contacts] == [
        "alice@laboratory.test"
    ]
    assert result.contacts[0].domain == "laboratory.test"
    assert "$" not in result.contacts[0].evidence[0].masked_context


def test_production_shaped_math_wrapped_contacts_are_backend_compatible(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice Example}
\thanks{$<$alice@research.test$>$,
$<$bob@research.test$>$,
$<$carol@research.test$>$, dana@research.test}
\begin{document}\maketitle
""",
    )

    assert [contact.normalized_email for contact in result.contacts] == [
        "alice@research.test",
        "bob@research.test",
        "carol@research.test",
        "dana@research.test",
    ]
    assert all("$" not in contact.domain for contact in result.contacts)


def test_unpaired_math_delimiter_is_rejected_but_legal_dollar_local_is_kept(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice Example}
\email{bob@uni.edu$}
\email{$tag@uni.edu}
\begin{document}\maketitle
""",
    )

    assert [contact.normalized_email for contact in result.contacts] == ["$tag@uni.edu"]


@pytest.mark.parametrize(
    "domain",
    [
        "bad_domain.edu",
        "bad$domain.edu",
        "bad/domain.edu",
        "bad+domain.edu",
        "bad=domain.edu",
        "bad!domain.edu",
    ],
)
def test_non_ldh_domain_characters_are_rejected(tmp_path: Path, domain: str) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{article}\n"
        f"\\email{{alice@{domain}}}\n"
        "\\begin{document}\\maketitle\n",
    )

    assert result.contacts == ()


def test_numeric_only_top_level_domain_is_rejected(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\email{agent@2.1.7}
\begin{document}\maketitle
""",
    )

    assert result.contacts == ()


def test_single_character_top_level_domain_is_rejected(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\email{agent@example.c}
\begin{document}\maketitle
""",
    )

    assert result.contacts == ()


@pytest.mark.parametrize("domain", ["example.xn--a", "example.xn--abc", "example.xn--0"])
def test_invalid_punycode_alabel_is_rejected(tmp_path: Path, domain: str) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{article}\n"
        f"\\email{{agent@{domain}}}\n"
        "\\begin{document}\\maketitle\n",
    )

    assert result.contacts == ()


def test_plain_version_tokens_in_an_included_appendix_are_not_contacts(
    tmp_path: Path,
) -> None:
    (tmp_path / "main.tex").write_text(
        r"""\documentclass{article}
\author{Alice Example}
\input{appendices/agent_adaptation}
\begin{document}\maketitle
""",
        encoding="utf-8",
    )
    appendix = tmp_path / "appendices"
    appendix.mkdir()
    (appendix / "agent_adaptation.tex").write_text(
        r"""Pin the CLI version via \texttt{@agent@2.1.7}.
The manifest contains JSON: "email": package@release.dev.
The manifest note is JSON: {"note": "Correspondence: package2@release.dev"}.
\section{Adaptation}
""",
        encoding="utf-8",
    )

    result = extract_contacts(
        discover_tex(tmp_path, maximum_include_depth=8, maximum_files=20)
    )

    assert result.contacts == ()


def test_explicit_email_in_an_included_author_file_accepts_a_numeric_subdomain(
    tmp_path: Path,
) -> None:
    (tmp_path / "main.tex").write_text(
        r"""\documentclass{article}
\author{Alice Example}
\input{authors}
\begin{document}\maketitle
""",
        encoding="utf-8",
    )
    (tmp_path / "authors.tex").write_text(
        r"""\affil{Lab\_One\_Two\_Three\_Four\_Five\_Six\_Seven\_Eight}
\email{alice@2026.example.edu}
""",
        encoding="utf-8",
    )

    result = extract_contacts(
        discover_tex(tmp_path, maximum_include_depth=8, maximum_files=20)
    )

    assert [contact.normalized_email for contact in result.contacts] == [
        "alice@2026.example.edu"
    ]


@pytest.mark.parametrize("command", ["affil", "address"])
def test_included_author_metadata_commands_keep_explicit_contacts(
    tmp_path: Path,
    command: str,
) -> None:
    (tmp_path / "main.tex").write_text(
        r"""\documentclass{article}
\author{Alice Example}
\input{authors}
\begin{document}\maketitle
""",
        encoding="utf-8",
    )
    (tmp_path / "authors.tex").write_text(
        f"\\{command}{{Example Lab; alice@2026.example.edu}}\n",
        encoding="utf-8",
    )

    result = extract_contacts(
        discover_tex(tmp_path, maximum_include_depth=8, maximum_files=20)
    )

    assert [contact.normalized_email for contact in result.contacts] == [
        "alice@2026.example.edu"
    ]


def test_plain_corresponding_author_line_in_an_included_author_file_is_kept(
    tmp_path: Path,
) -> None:
    (tmp_path / "main.tex").write_text(
        r"""\documentclass{article}
\author{Alice Example}
\input{authors}
\begin{document}\maketitle
""",
        encoding="utf-8",
    )
    (tmp_path / "authors.tex").write_text(
        "Corresponding author: alice@2026.example.edu\n",
        encoding="utf-8",
    )

    result = extract_contacts(
        discover_tex(tmp_path, maximum_include_depth=8, maximum_files=20)
    )

    assert [contact.normalized_email for contact in result.contacts] == [
        "alice@2026.example.edu"
    ]
    assert result.contacts[0].corresponding is True


@pytest.mark.parametrize("domain", ["example.xn--p1ai", "example.xn--80ak6aa92e"])
def test_valid_punycode_top_level_domain_is_accepted(
    tmp_path: Path, domain: str
) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{article}\n"
        f"\\email{{alice@{domain}}}\n"
        "\\begin{document}\\maketitle\n",
    )

    assert [contact.normalized_email for contact in result.contacts] == [
        f"alice@{domain}"
    ]


@pytest.mark.parametrize(
    "author_text",
    [
        "Alice Smith, Jr., and Bob Jones",
        "Center for AI, University of Example, and Research Institute",
        (
            "Applied Intelligence Initiative, Trusted Systems Initiative, "
            "and Responsible Computing Initiative"
        ),
    ],
)
def test_ambiguous_comma_author_text_is_not_persisted_as_false_people(
    tmp_path: Path,
    author_text: str,
) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{article}\n"
        f"\\author{{{author_text}}}\n"
        "\\begin{document}\\maketitle\n",
    )

    assert result.authors == ()


def test_ieee_membership_metadata_is_removed_before_splitting_named_people(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{Alice Smith, \IEEEmembership{Senior Member, IEEE}, and Bob Jones}
\begin{document}\maketitle
""",
        ("Alice Smith", "Bob Jones"),
    )

    assert [author.name for author in result.authors] == ["Alice Smith", "Bob Jones"]


def test_ieee_author_refmarks_are_removed_from_and_names_and_affiliations(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{Alice Smith\IEEEauthorrefmark{1} \and
Bob Jones\IEEEauthorrefmark{2}}
\IEEEauthorblockA{\IEEEauthorrefmark{1} Lab One}
\IEEEauthorblockA{\IEEEauthorrefmark{2} Lab Two}}
\begin{document}\maketitle
""",
    )

    assert [author.name for author in result.authors] == ["Alice Smith", "Bob Jones"]
    assert result.authors[0].affiliations == ("Lab One", "Lab Two")
    assert result.authors[1].affiliations == ("Lab One", "Lab Two")


def test_ieee_author_refmarks_do_not_break_metadata_verified_comma_names(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{Alice Smith\IEEEauthorrefmark{1}, and Bob Jones\IEEEauthorrefmark{2}}
\begin{document}\maketitle
""",
        ("Alice Smith", "Bob Jones"),
    )

    assert [author.name for author in result.authors] == ["Alice Smith", "Bob Jones"]


@pytest.mark.parametrize(
    "author_block",
    [
        r"Alice One$^{1}$ Bob Two$^{2}$\\Example Institute\\\email{bob@example.org}",
        r"Alice One Bob Two\thanks{Corresponding author: bob@example.org}",
        r"Alice One$^{*}$ Bob Two$^{\dagger}$\thanks{"
        r"$^{*}$Equal contribution. $^{\dagger}$Correspondence: bob@example.org}",
    ],
)
def test_unrecognized_source_name_cannot_claim_a_metadata_author_by_ordinal(
    tmp_path: Path, author_block: str,
) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{article}\n"
        f"\\author{{{author_block}}}\n"
        "\\begin{document}\\maketitle\n",
        ("Alice One", "Bob Two"),
    )

    assert result.authors == ()
    assert len(result.contacts) == 1
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW


def test_metadata_identity_guard_preserves_explicit_ieee_single_author_blocks(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{
\IEEEauthorblockN{1st Alice One}
\IEEEauthorblockA{Lab One\\alice@example.org}
\and
\IEEEauthorblockN{2nd Bob Two}
\IEEEauthorblockA{Lab Two\\bob@example.org}}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [author.name for author in result.authors] == ["Alice One", "Bob Two"]
    assert [contact.author_order for contact in result.contacts] == [1, 2]
    assert [contact.confidence for contact in result.contacts] == [Confidence.HIGH] * 2


def test_partial_name_match_does_not_make_a_shared_block_a_single_author(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice One \and Bob Two$^{2}$\thanks{Correspondence: bob@example.org}}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert result.authors == ()
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW


def test_duplicate_metadata_names_cannot_prove_a_unique_author_identity(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alex Smith\thanks{alex@example.org}}
\begin{document}\maketitle
""",
        ("Alex Smith", "Alex Smith"),
    )

    assert result.authors == ()
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW


def test_incomplete_metadata_author_parse_cannot_use_single_author_fallback(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice One}
\author{Bob Two$^{2}$}
\email{bob@example.org}
\begin{document}\maketitle
""",
        ("Alice One", "Bob Two"),
    )

    assert [author.name for author in result.authors] == ["Alice One"]
    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW


@pytest.mark.parametrize(
    "author_block, expected_contacts",
    [
        (
            r"""\textbf{Alice One}$^{1,*}$, \textbf{Bob Two}$^{1,*}$,
\textbf{Carol Three}$^{1,\dagger}$\\
$^1$Example University\\
$^*$Equal contribution, $^\dagger$Correspondence: \texttt{carol@example.org}""",
            1,
        ),
        (
            r"""\textbf{Alice One$^{1,*}$}, \textbf{Bob Two$^{2,\dagger}$},
\textbf{Carol Three$^{1,\dagger}$}\\
\vspace{-2mm}\\
$^{1}$Example University, $^{2}$Example Company,\\
$^{*}$Equal contribution.\\
$^{\dagger}$Corresponding authors: \texttt{bob@example.org}, \texttt{carol@example.org}""",
            2,
        ),
    ],
)
@pytest.mark.parametrize("metadata", [(), ("Alice One", "Bob Two", "Carol Three")])
def test_layout_and_institution_lines_are_not_author_identities(
    tmp_path: Path, author_block: str, expected_contacts: int, metadata: tuple[str, ...],
) -> None:
    result = extract(
        tmp_path,
        "\\documentclass{article}\n"
        f"\\author{{{author_block}}}\n"
        "\\begin{document}\\maketitle\n",
        metadata,
    )

    assert result.authors == ()
    assert len(result.contacts) == expected_contacts
    assert all(contact.author_order is None for contact in result.contacts)
    assert all(contact.confidence is Confidence.LOW for contact in result.contacts)
    assert all("@" not in contact.evidence[0].masked_context for contact in result.contacts)


def test_body_and_bibliography_addresses_are_not_promoted_to_contacts(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice Example}
\begin{document}\maketitle
\begin{abstract}x\end{abstract}
Contact the dataset owner at unrelated@elsewhere.example in the body.
\begin{thebibliography}{1} ref@example.org \end{thebibliography}
""",
    )

    assert result.contacts == ()


def test_normalizes_tex_escape_idn_and_deduplicates_addresses(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice Example}
\email{Alice\_Lab@BÜCHER.example}
\thanks{alice\_lab@bücher.example}
\begin{document}
""",
    )

    assert len(result.contacts) == 1
    assert result.contacts[0].normalized_email == "alice_lab@xn--bcher-kva.example"
    assert result.contacts[0].example_address is True


def test_unmapped_front_matter_email_is_low_and_never_guessed(tmp_path: Path) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{article}
\author{Alice One \and Bob Two}
\title{Questions: paper-contact@uni.edu}
\begin{document}\maketitle
""",
    )

    assert result.contacts[0].author_order is None
    assert result.contacts[0].confidence is Confidence.LOW
    assert all(item.normalized_email != "alice.one@uni.edu" for item in result.contacts)


def test_canonicalizes_repeated_authors_across_tex_files_and_remaps_contacts(
    tmp_path: Path,
) -> None:
    (tmp_path / "main.tex").write_text(
        r"""\documentclass{article}
\author{Alice Example \and Bob Researcher}
\email{alice@uni.edu}
\email{bob@lab.org}
\input{authors}
\begin{document}\maketitle
""",
        encoding="utf-8",
    )
    (tmp_path / "authors.tex").write_text(
        r"""\author{  ALICE   EXAMPLE  \and Bob Researcher}
\affil{Reliability Lab}
""",
        encoding="utf-8",
    )

    result = extract_contacts(
        discover_tex(tmp_path, maximum_include_depth=8, maximum_files=20)
    )

    assert [(item.order, item.name) for item in result.authors] == [
        (1, "Alice Example"),
        (2, "Bob Researcher"),
    ]
    assert result.authors[0].affiliations == ("Reliability Lab",)
    assert result.authors[1].affiliations == ("Reliability Lab",)
    assert [item.author_order for item in result.contacts] == [1, 2]


def test_canonical_author_merge_revalidates_affiliation_count() -> None:
    corpus = TexCorpus(
        root_path="/bounded/source",
        document_class="article",
        files=tuple(
            TexFile(
                relative_path=f"author-{index}.tex",
                text=(
                    "\\author{Alice Example}\n"
                    f"\\affil{{Reliability Laboratory {index}}}\n"
                    "\\begin{document}\n"
                ),
            )
            for index in range(101)
        ),
    )

    with pytest.raises(ValidationError, match="affiliations"):
        extract_contacts(corpus)
