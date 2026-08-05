from __future__ import annotations

from dataclasses import dataclass

from app.arxiv.xml import optional_text, required_text, secure_root

OAI = "{http://www.openarchives.org/OAI/2.0/}"


@dataclass(frozen=True, slots=True)
class TaxonomyCategory:
    set_spec: str
    group_id: str
    archive_id: str
    category_id: str
    category_name: str


def parse_list_sets(body: bytes) -> tuple[tuple[TaxonomyCategory, ...], str | None]:
    root = secure_root(body)
    if root.tag != f"{OAI}OAI-PMH":
        raise ValueError("OAI ListSets root is invalid")
    error = root.find(f"{OAI}error")
    if error is not None:
        raise ValueError(f"OAI ListSets failed with {error.attrib.get('code', 'unknown')}")
    container = root.find(f"{OAI}ListSets")
    if container is None:
        raise ValueError("OAI response does not contain ListSets")
    categories: list[TaxonomyCategory] = []
    for item in container.findall(f"{OAI}set"):
        set_spec = required_text(item.find(f"{OAI}setSpec"), "set spec")
        parts = set_spec.split(":")
        if len(parts) != 3 or not all(parts):
            continue
        group_id, archive_id, leaf = parts
        category_id = f"{archive_id}.{leaf}"
        categories.append(
            TaxonomyCategory(
                set_spec=set_spec,
                group_id=group_id,
                archive_id=archive_id,
                category_id=category_id,
                category_name=required_text(item.find(f"{OAI}setName"), "set name"),
            )
        )
    token = optional_text(container.find(f"{OAI}resumptionToken"))
    return tuple(categories), token
