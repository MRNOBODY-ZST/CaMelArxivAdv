from __future__ import annotations

from pathlib import Path

import pytest

from app.extraction.tex_discovery import TexDiscoveryError, discover_tex


def test_discovers_main_file_document_class_and_nested_includes(tmp_path: Path) -> None:
    (tmp_path / "sections").mkdir()
    (tmp_path / "main.tex").write_text(
        r"""\documentclass[11pt]{revtex4-2}
\input{authors}
\begin{document}
\include{sections/intro}
\end{document}
""",
        encoding="utf-8",
    )
    (tmp_path / "authors.tex").write_text(r"\author{Alice}", encoding="utf-8")
    (tmp_path / "sections/intro.tex").write_text("Introduction", encoding="utf-8")

    corpus = discover_tex(tmp_path, maximum_include_depth=4, maximum_files=10)

    assert corpus.document_class == "revtex4-2"
    assert corpus.root_path == "main.tex"
    assert [item.relative_path for item in corpus.files] == [
        "main.tex",
        "authors.tex",
        "sections/intro.tex",
    ]


def test_include_cycle_is_bounded_and_each_file_is_read_once(tmp_path: Path) -> None:
    (tmp_path / "main.tex").write_text(
        r"\documentclass{article}\input{a}", encoding="utf-8"
    )
    (tmp_path / "a.tex").write_text(r"\input{main}", encoding="utf-8")

    corpus = discover_tex(tmp_path, maximum_include_depth=4, maximum_files=10)

    assert [item.relative_path for item in corpus.files] == ["main.tex", "a.tex"]


def test_excessive_include_depth_is_rejected(tmp_path: Path) -> None:
    (tmp_path / "main.tex").write_text(
        r"\documentclass{article}\input{one}", encoding="utf-8"
    )
    (tmp_path / "one.tex").write_text(r"\input{two}", encoding="utf-8")
    (tmp_path / "two.tex").write_text("end", encoding="utf-8")

    with pytest.raises(TexDiscoveryError, match="include depth"):
        discover_tex(tmp_path, maximum_include_depth=1, maximum_files=10)


def test_escaped_percent_is_not_treated_as_comment(tmp_path: Path) -> None:
    (tmp_path / "main.tex").write_text(
        "\\documentclass{article}\nvalue \\% visible % hidden \\input{bad}\n",
        encoding="utf-8",
    )

    corpus = discover_tex(tmp_path, maximum_include_depth=2, maximum_files=10)

    assert "visible" in corpus.files[0].text
    assert "hidden" not in corpus.files[0].text
