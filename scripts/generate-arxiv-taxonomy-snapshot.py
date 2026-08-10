#!/usr/bin/env python3
"""Generate the checked-in arXiv taxonomy fallback from two official responses.

This is a maintainer tool, not a runtime scraper. Fetch the taxonomy HTML and OAI
ListSets response deliberately, at least three seconds apart, then pass both files
to this script. Runtime synchronization uses OAI-PMH ListSets only.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


GROUP_PATTERN = re.compile(
    r'<h2 class="accordion-head" id="accordion-head-grp_([^\"]+)">(.*?)(?=<h2 class="accordion-head"|</main>)',
    re.DOTALL,
)
TOKEN_PATTERN = re.compile(r"<h3>(.*?)</h3>|<h4>(.*?)</h4>", re.DOTALL)
NAME_PATTERN = re.compile(
    r"^\s*([^<]+?)\s*(?:<br\s*/?>\s*)?<span>\((.*?)\)</span>\s*$", re.DOTALL
)
ALIAS_PATTERN = re.compile(r"\bis an alias for\s+([A-Za-z0-9.-]+)", re.IGNORECASE)


def clean_markup(value: str) -> str:
    without_tags = re.sub(r"<[^>]+>", " ", value)
    return " ".join(html.unescape(without_tags).split())


def parse_oai_response_date(path: Path) -> str:
    root = ET.parse(path).getroot()
    value = root.findtext("{http://www.openarchives.org/OAI/2.0/}responseDate")
    if not value:
        raise ValueError("OAI ListSets response has no responseDate")
    return value


def parse_categories(path: Path) -> list[dict[str, Any]]:
    document = path.read_text(encoding="utf-8")
    categories: list[dict[str, Any]] = []
    for group_match in GROUP_PATTERN.finditer(document):
        group_id, body = group_match.groups()
        button = re.search(r"<button[^>]*>(.*?)</button>", body, re.DOTALL)
        if button is None:
            raise ValueError(f"group {group_id} has no name")
        group_name = clean_markup(button.group(1))
        archive_id = group_id
        archive_name = group_name
        tokens = list(TOKEN_PATTERN.finditer(body))
        for index, token in enumerate(tokens):
            archive_markup, category_markup = token.groups()
            if archive_markup is not None:
                archive_match = NAME_PATTERN.match(archive_markup)
                if archive_match is None:
                    raise ValueError(f"invalid archive heading: {archive_markup}")
                archive_name = clean_markup(archive_match.group(1))
                archive_id = clean_markup(archive_match.group(2))
                continue

            category_match = NAME_PATTERN.match(category_markup or "")
            if category_match is None:
                raise ValueError(f"invalid category heading: {category_markup}")
            category_id = clean_markup(category_match.group(1))
            category_name = clean_markup(category_match.group(2))
            end = tokens[index + 1].start() if index + 1 < len(tokens) else len(body)
            category_body = body[token.end() : end]
            paragraph = re.search(r"<p>(.*?)</p>", category_body, re.DOTALL)
            description = clean_markup(paragraph.group(1)) if paragraph else ""
            alias_match = ALIAS_PATTERN.search(description)
            categories.append(
                {
                    "alias": alias_match is not None,
                    "aliasTarget": alias_match.group(1).rstrip(".") if alias_match else None,
                    "archiveId": archive_id,
                    "archiveName": archive_name,
                    "categoryId": category_id,
                    "categoryName": category_name,
                    "description": description,
                    "groupId": group_id,
                    "groupName": group_name,
                }
            )
    return sorted(categories, key=lambda item: (item["groupId"], item["archiveId"], item["categoryId"]))


def canonical_hash(categories: list[dict[str, Any]]) -> str:
    payload = json.dumps(
        categories, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--taxonomy-html", type=Path, required=True)
    parser.add_argument("--listsets-xml", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--generated-at", required=True)
    args = parser.parse_args()

    categories = parse_categories(args.taxonomy_html)
    if len(categories) < 150:
        raise ValueError(f"snapshot unexpectedly contains only {len(categories)} categories")
    category_ids = {item["categoryId"] for item in categories}
    if len(category_ids) != len(categories):
        raise ValueError("snapshot contains duplicate category IDs")
    dangling = [
        item for item in categories if item["alias"] and item["aliasTarget"] not in category_ids
    ]
    if dangling:
        raise ValueError(f"snapshot contains dangling aliases: {dangling}")

    snapshot = {
        "categories": categories,
        "generatedAt": args.generated_at,
        "payloadSha256": canonical_hash(categories),
        "snapshotVersion": args.version,
        "sourceType": "OFFLINE_SNAPSHOT",
        "sourceUpdatedAt": parse_oai_response_date(args.listsets_xml),
        "sourceUrls": [
            "https://arxiv.org/category_taxonomy",
            "https://oaipmh.arxiv.org/oai?verb=ListSets",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
