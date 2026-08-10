from pathlib import Path

from app.arxiv.taxonomy import parse_list_sets


def test_normalizes_official_group_archive_category_sets() -> None:
    fixture = Path(__file__).parents[1] / "fixtures" / "arxiv" / "list-sets.xml"

    categories, token = parse_list_sets(fixture.read_bytes())

    assert token is None
    assert categories[0].group_id == "cs"
    assert categories[0].archive_id == "cs"
    assert categories[0].category_id == "cs.AI"
    assert categories[2].category_id == "astro-ph.GA"
    assert categories[3].group_id == "physics"
    assert categories[3].archive_id == "hep-th"
    assert categories[3].category_id == "hep-th"
