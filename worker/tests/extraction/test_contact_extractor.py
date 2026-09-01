from __future__ import annotations

from pathlib import Path

import pytest

from app.extraction.contact_extractor import extract_contacts
from app.extraction.models import Confidence
from app.extraction.tex_discovery import discover_tex


def extract(tmp_path: Path, source: str):  # type: ignore[no-untyped-def]
    (tmp_path / "main.tex").write_text(source, encoding="utf-8")
    return extract_contacts(discover_tex(tmp_path, maximum_include_depth=8, maximum_files=20))


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
    assert "al***@example.edu" in contact.evidence[0].masked_context


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
\IEEEauthorblockN{1st Kacper Kenji Lesniak}
\IEEEauthorblockA{University of Copenhagen and Dynasty Studios\\
Copenhagen, Denmark\\ kkl@di.ku.dk}
\and
\IEEEauthorblockN{2nd Maria Maistro}
\IEEEauthorblockA{University of Copenhagen\\
Copenhagen, Denmark\\ mm@di.ku.dk}}
\begin{document}\maketitle
""",
    )

    assert [author.name for author in result.authors] == [
        "Kacper Kenji Lesniak",
        "Maria Maistro",
    ]
    assert [contact.normalized_email for contact in result.contacts] == [
        "kkl@di.ku.dk",
        "mm@di.ku.dk",
    ]
    assert [contact.author_order for contact in result.contacts] == [1, 2]


def test_nested_thanks_does_not_turn_ieee_affiliations_into_an_author(
    tmp_path: Path,
) -> None:
    result = extract(
        tmp_path,
        r"""\documentclass{IEEEtran}
\author{Xinlei Wang, Mingtian Tan, Jing Qiu, Junhua Zhao, and Jinjin Gu\thanks{
This work was supported by a bounded research grant.}
\IEEEcompsocitemizethanks{
\IEEEcompsocthanksitem Xinlei Wang and Jing Qiu are with the \textit{University
of Sydney, Australia}. Mingtian Tan is with the \textit{Hong Kong University
of Science and Technology, Hong Kong, China}.
\IEEEcompsocthanksitem Junhua Zhao is with the \textit{Chinese University of Hong
Kong, Shenzhen, and the Shenzhen Institute of Artificial Intelligence and
Robotics for Society, Shenzhen, China}. Jinjin Gu is with the \textit{Institute
for Computer Science, Artificial Intelligence and Technology, Sofia, Bulgaria}.
Contact: \texttt{jinjin.gu@insait.ai}.}}
\begin{document}\maketitle
""",
    )

    assert [author.name for author in result.authors] == [
        "Xinlei Wang",
        "Mingtian Tan",
        "Jing Qiu",
        "Junhua Zhao",
        "Jinjin Gu",
    ]
    assert all("University" not in author.name for author in result.authors)
    assert [contact.normalized_email for contact in result.contacts] == [
        "jinjin.gu@insait.ai"
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
