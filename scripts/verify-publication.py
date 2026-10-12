#!/usr/bin/env python3
"""Read-only, fail-closed publication gate; independent human review is required.

The private inventory is trusted only through an external controller-pinned SHA256.
It binds exact candidate/source identities, reviewed bytes, exclusions, and narrow
exceptions. This heuristic gate cannot prove absence of all confidential content.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys


PIN = "CAMEL_PUBLICATION_APPROVED_INVENTORY_SHA256"
MAX_BYTES = 32 * 1024 * 1024
HEX = re.compile(r"[0-9a-f]{64}\Z")
OID = re.compile(r"[0-9a-f]{40}\Z")
SAFE_PATH = re.compile(r"[A-Za-z0-9_. /+-]+\Z")
FORBIDDEN_PART = re.compile(
    r"(?:\.superpowers|\.git|\.worktrees|\.idea|\.vscode|\.gradle|build(?:[-_].*)?|"
    r"target|dist|node_modules|__pycache__|\.pytest_cache|\.mypy_cache|\.ruff_cache|"
    r"\.venv|venv|coverage|playwright-report|test-results|data|.*-data|backups?|screenshots)", re.I)
RULES = {
    "EMAIL": re.compile(r"[\w.!#$%&'*+/=?^`{|}~\-]+@[\w\-]+(?:\.[\w\-]+)+", re.UNICODE),
    "CREDENTIAL": re.compile(r"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|"
                             r"\b(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|"
                             r"sk-[A-Za-z0-9_-]{20,})\b|"
                             r"(?i:\b(?:password|passwd|secret|api[_-]?key|access[_-]?token)"
                             r"\s*[:=]\s*[\"']?[^\s\"'${}<>,;]{8,})"),
    "CAPABILITY": re.compile(r"https?://[^\s\"'<>]+(?:[?&](?:token|key|secret|code|signature)="
                             r"[^\s\"'<>]+|/(?:invite|activation|reset-password)/[^\s\"'<>]+)", re.I),
    "SERVER": re.compile(r"(?:\b(?:ssh|scp)\s+[^\s]+|/Users/[^\s\"'<>]+|/home/[^\s\"'<>]+|"
                         r"\b(?:\d{1,3}\.){3}\d{1,3}\b)"),
}
ANCHORS = frozenset("""README.md LICENSE .gitignore .dockerignore .env.example
docker-compose.yml docker-compose.dev.yml infra/nginx/default.conf
infra/postgres/init/.gitkeep backend/.gitattributes backend/.gitignore
backend/Dockerfile backend/compose.yaml backend/build.gradle backend/settings.gradle
backend/gradlew backend/gradlew.bat backend/gradle/wrapper/gradle-wrapper.properties
backend/gradle/wrapper/gradle-wrapper.jar
backend/src/main/resources/application.yaml backend/src/main/resources/application-mail-worker.yaml
backend/src/main/resources/arxiv/taxonomy-2026-08.json
backend/src/main/resources/unicode/CaseFolding-15.0.0.txt
backend/src/main/resources/unicode/LICENSE.txt backend/src/test/resources/arxiv/legacy-preview.xml
frontend/Dockerfile frontend/index.html frontend/package.json frontend/package-lock.json
frontend/tsconfig.json frontend/tsconfig.app.json frontend/tsconfig.node.json
frontend/vite.config.ts frontend/vitest.config.ts frontend/playwright.config.ts
frontend/eslint.config.js frontend/src/env.d.ts frontend/src/test/setup.ts
worker/Dockerfile worker/pyproject.toml worker/uv.lock
worker/tests/fixtures/arxiv/legacy-page.xml worker/tests/fixtures/arxiv/list-sets.xml
worker/tests/fixtures/arxiv/oai-page-1.xml worker/tests/fixtures/arxiv/oai-page-2.xml
contracts/fixtures/source-command-identity-v1.json contracts/fixtures/source-admission-state-v2.json
docs/email/camel-hub-auto-outreach-template.json docs/email/preparation-diagnostics.md
docs/THIRD_PARTY_NOTICES.md scripts/generate-arxiv-taxonomy-snapshot.py
scripts/verify-compose.sh scripts/verify-container-images.sh
scripts/migrate-unsent-template-drafts.py scripts/tests/test_migrate_unsent_template_drafts.py
scripts/verify-publication.py scripts/tests/test_verify_publication.py""".split())
CATEGORIES = (
    r"backend/src/main/java/.+\.java", r"backend/src/test/java/.+\.java",
    r"backend/src/main/resources/db/migration/.+\.sql", r"frontend/src/.+\.vue",
    r"frontend/src/.+\.spec\.ts", r"frontend/e2e/.+\.spec\.ts",
    r"worker/src/app/.+\.py", r"worker/tests/.+\.py",
)


@dataclass(frozen=True, order=True)
class Finding:
    path: str
    rule_id: str


class ScanError(Exception):
    """Internal fixed exception; underlying diagnostics never leave this module."""


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _normal_path(name):
    return (isinstance(name, str) and bool(SAFE_PATH.fullmatch(name))
            and not name.startswith(("/", "-")) and "\\" not in name
            and all(p not in ("", ".", "..") for p in name.split("/"))
            and str(PurePosixPath(name)) == name
            and not any(regex.search(name) for regex in RULES.values()))


def _forbidden(name):
    parts = name.split("/")
    return (any(FORBIDDEN_PART.fullmatch(p) for p in parts)
            or name.startswith(("docs/superpowers/", "docs/email/production-", "docs/email/salmon-"))
            or parts[-1] in ("IMPLEMENTATION_PLAN.md", "TASKS.md", ".DS_Store")
            or (parts[-1].startswith(".env") and name != ".env.example")
            or bool(re.search(r"\.(?:pyc|pyo|class|log|png|jpe?g|gif|webp|zip|tar|gz|sqlite|db|pem|key)$", name, re.I)))


def _git(root, *args):
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "LC_ALL": "C",
           "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
           "GIT_NO_REPLACE_OBJECTS": "1", "GIT_NO_LAZY_FETCH": "1",
           "GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}
    result = subprocess.run(["git", "-C", str(root), "--no-pager", "-c", "core.fsmonitor=false",
                             "-c", "core.untrackedCache=false", "-c", "core.hooksPath=" + os.devnull,
                             "-c", "protocol.allow=never", *args],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            env=env, timeout=30, check=False)
    if result.returncode or len(result.stdout) > MAX_BYTES:
        raise ScanError()
    return result.stdout


def _text(root, *args):
    return _git(root, *args).decode("utf-8", "strict").strip()


def _read(path):
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or not before.st_mode & 0o444 or before.st_size > MAX_BYTES:
        raise ScanError()
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        opened = os.fstat(stream.fileno())
        if (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
            raise ScanError()
        data = stream.read(MAX_BYTES + 1)
        after = os.fstat(stream.fileno())
    if len(data) > MAX_BYTES or (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != (
            after.st_size, after.st_mtime_ns, after.st_ctime_ns):
        raise ScanError()
    return ("100755" if before.st_mode & 0o111 else "100644", data)


def _disk(root):
    result = {}
    stack = [root]
    while stack:
        directory = stack.pop()
        if directory.resolve() != directory:
            raise ScanError()
        with os.scandir(directory) as entries:
            for item in entries:
                path = Path(item.path)
                name = path.relative_to(root).as_posix()
                if name == ".git":
                    continue  # Only the previously validated repository administration entry.
                if item.is_dir(follow_symlinks=False):
                    if _forbidden(name):
                        result[name] = ("directory", b"")
                    stack.append(path)
                elif item.is_file(follow_symlinks=False):
                    result[name] = _read(path)
                else:
                    result[name] = ("unsafe", b"")
    return result


def _index(root):
    result = {}
    for record in _git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if not record:
            continue
        metadata, name = record.split(b"\t", 1)
        mode, oid, stage = metadata.decode("ascii").split()
        if stage != "0":
            raise ScanError()
        path = name.decode("utf-8", "surrogateescape")
        if path in result:
            raise ScanError()
        result[path] = (mode, _git(root, "cat-file", "blob", oid) if mode in ("100644", "100755") else b"")
    return result


def _commit_tree(root, commit):
    result = {}
    for record in _git(root, "ls-tree", "-r", "-z", "--full-tree", commit).split(b"\0"):
        if not record:
            continue
        metadata, name = record.split(b"\t", 1)
        mode, kind, oid = metadata.decode("ascii").split()
        result[name.decode("utf-8", "surrogateescape")] = (
            mode, _git(root, "cat-file", "blob", oid) if kind == "blob" else b"")
    return result


def _scan_blob(name, mode, data, entry):
    rules = set()
    if _forbidden(name):
        rules.add("FORBIDDEN_PATH")
    if mode not in ("100644", "100755"):
        return rules | {"UNSAFE_TYPE"}
    data_hash = _sha(data)
    approved = entry if entry and data_hash in (entry["sha256"], entry.get("worktree_sha256")) and entry["mode"] == mode else {}
    if approved.get("transform") == "git-crlf-to-lf" and data_hash == approved["worktree_sha256"]:
        if _sha(data.replace(b"\r\n", b"\n")) != approved["sha256"]:
            rules.add("BYTE_TRANSFORM")
    try:
        text = data.decode("utf-8", "strict")
        if any(ord(c) < 32 and c not in "\r\n\t\f" for c in text):
            raise ValueError()
    except (UnicodeError, ValueError):
        provenance = approved.get("binary_provenance", {})
        if not (name == "backend/gradle/wrapper/gradle-wrapper.jar" and mode == "100644"
                and provenance.get("sha256") == _sha(data) and provenance.get("review")
                and provenance.get("url", "").startswith("https://")):
            rules.add("BINARY")
        return rules
    exceptions = {(e["rule_id"], e["value"]) for e in approved.get("exceptions", [])}
    for rule, regex in RULES.items():
        if any((rule, match.group()) not in exceptions for match in regex.finditer(text)):
            rules.add(rule)
    return rules


def _snapshot_bytes(path, expected, stream=False):
    before = path.lstat()
    if (not stat.S_ISREG(before.st_mode) or not before.st_mode & 0o444
            or (before.st_size > MAX_BYTES and not stream)):
        raise ScanError()
    size = expected.get("size", before.st_size)
    if type(size) is not int or size < 0 or size != before.st_size:
        raise ScanError()
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    checksum, count = hashlib.sha256(), 0
    def identity(value):
        return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns,
                value.st_ctime_ns, value.st_mode)
    with os.fdopen(descriptor, "rb") as reader:
        if identity(os.fstat(reader.fileno())) != identity(before):
            raise ScanError()
        while chunk := reader.read(min(1024 * 1024, size - count + 1)):
            count += len(chunk)
            if count > size:
                raise ScanError()
            checksum.update(chunk)
        if identity(os.fstat(reader.fileno())) != identity(before):
            raise ScanError()
    if identity(path.lstat()) != identity(before) or count != size:
        raise ScanError()
    mode = "100755" if before.st_mode & 0o111 else "100644"
    return (mode, checksum.hexdigest(), count, identity(before))


def _snapshot(spec, original=False, protected_paths=()):
    root = Path(spec["root"])
    if not root.is_absolute() or root.resolve() != root or _text(root, "rev-parse", "HEAD") != spec["head"]:
        raise ScanError()
    names = set(_git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").decode(
        "utf-8", "strict").split("\0")) - {""}
    redacted = spec.get("redacted_paths", {})
    if not isinstance(redacted, dict) or (redacted and not original):
        raise ScanError()
    for key, entry in redacted.items():
        if (not isinstance(key, str) or not HEX.fullmatch(key) or not isinstance(entry, dict)
                or set(entry) != {"sha256", "mode", "size", "decision", "reason"}
                or entry["decision"] != "exclude" or entry["reason"] != "Excluded original private artifact"):
            raise ScanError()
    if any(not isinstance(name, str) for name in spec["paths"]):
        raise ScanError()
    remaining_named, remaining_redacted = set(spec["paths"]), set(redacted)
    hashes = {}
    for name in sorted(names):
        if (not isinstance(name, str) or Path(name).is_absolute() or ".." in Path(name).parts
                or str(PurePosixPath(name)) != name):
            raise ScanError()
        path_key = _sha(name.encode("utf-8", "strict"))
        named, hidden = name in spec["paths"], path_key in redacted
        if named == hidden:
            raise ScanError()
        if hidden:
            if not original or not name.startswith("docs/") or name in protected_paths:
                raise ScanError()
            remaining_redacted.remove(path_key)
            expected = redacted[path_key]
        else:
            remaining_named.remove(name)
            expected = spec["paths"][name]
        path = root / name
        if path.resolve() != path:
            raise ScanError()
        stream = (original and not hidden and expected.get("snapshot_kind") == "generated"
                  and expected["decision"] == "exclude"
                  and expected["reason"] == "Excluded original generated artifact"
                  and bool(re.fullmatch(r"backend/build[-_][^/]+/.+", name))
                  and name not in protected_paths and "size" in expected)
        if "snapshot_kind" in expected and not stream:
            raise ScanError()
        value = _snapshot_bytes(path, expected, stream)
        hashes[("digest:" + path_key) if hidden else name] = value
        if value[:2] != (expected["mode"], expected["sha256"]):
            raise ScanError()
        if expected["decision"] not in ("include", "transform", "exclude") or not expected["reason"]:
            raise ScanError()
    if remaining_named or remaining_redacted:
        raise ScanError()
    return hashes


def _validate_entries(files):
    if not isinstance(files, dict) or not files:
        raise ScanError()
    for name, entry in files.items():
        if not _normal_path(name) or not isinstance(entry, dict):
            raise ScanError()
        if not HEX.fullmatch(entry["sha256"]) or entry["mode"] not in ("100644", "100755") or not entry["review"]:
            raise ScanError()
        if "worktree_sha256" in entry or "transform" in entry:
            if (name != "backend/gradlew.bat" or entry.get("transform") != "git-crlf-to-lf"
                    or not HEX.fullmatch(entry.get("worktree_sha256", "")) or entry["mode"] != "100644"):
                raise ScanError()
        for exception in entry["exceptions"]:
            if set(exception) != {"rule_id", "value"} or exception["rule_id"] not in RULES:
                raise ScanError()
            if not isinstance(exception["value"], str) or not exception["value"]:
                raise ScanError()


def inspect_publication(base: str, tree: Path, inventory: Path, source_head: str) -> list[Finding]:
    """Inspect exact worktree, index, and every new tree without printing or writing."""
    findings = set()
    safe = set()
    def add(path, rule):
        rendered = path if path in safe and _normal_path(path) else "<redacted-path>"
        if path in ("<input>", "<inventory>", "<repository>"):
            rendered = path
        findings.add(Finding(rendered, rule))
    try:
        tree, inventory = Path(tree), Path(inventory)
        raw_inventory = _read(inventory)[1]
        pin = os.environ.get(PIN, "")
        if not HEX.fullmatch(pin) or _sha(raw_inventory) != pin:
            return [Finding("<inventory>", "INVENTORY_UNAPPROVED")]
        manifest = json.loads(raw_inventory, object_pairs_hook=_unique_object)
        if manifest["version"] != 1 or not OID.fullmatch(base) or not OID.fullmatch(source_head):
            raise ScanError()
        files = manifest["files"]
        _validate_entries(files)
        safe = set(files)
        if inventory.resolve().is_relative_to(tree.resolve()):
            add("<inventory>", "IDENTITY")
        if (not tree.is_absolute() or tree.resolve() != tree
                or _text(tree, "rev-parse", "--show-toplevel") != str(tree)
                or manifest["candidate"]["root"] != str(tree)
                or _text(tree, "symbolic-ref", "--short", "HEAD") != manifest["candidate"]["branch"]
                or Path(_text(tree, "rev-parse", "--path-format=absolute", "--git-common-dir")).resolve()
                != Path(manifest["candidate"]["git_common_dir"])
                or manifest["base"] != base or manifest["source_head"] != source_head
                or manifest["source"]["head"] != source_head):
            add("<repository>", "IDENTITY")
        if (_text(tree, "rev-parse", base + "^{commit}") != base
                or _text(tree, "rev-parse", source_head + "^{commit}") != source_head):
            add("<repository>", "IDENTITY")
        gitdir = Path(_text(tree, "rev-parse", "--absolute-git-dir"))
        common = Path(_text(tree, "rev-parse", "--path-format=absolute", "--git-common-dir")).resolve()
        def graph_identity():
            return (_text(tree, "rev-parse", "--is-shallow-repository"),
                    _git(tree, "for-each-ref", "--format=%(refname) %(objectname)", "refs/replace/"),
                    tuple(p.exists() for p in (common / "info/grafts", gitdir / "shallow",
                                               common / "objects/info/alternates")))
        graph_before = graph_identity()
        if graph_before != ("false", b"", (False, False, False)):
            add("<repository>", "GRAPH")
        if not ANCHORS <= safe or any(not any(re.fullmatch(pattern, p) for p in safe) for pattern in CATEGORIES):
            add("<inventory>", "COVERAGE")
        for lock in ("frontend/package-lock.json", "worker/uv.lock"):
            if lock not in manifest["source"]["paths"]:
                add("<inventory>", "COVERAGE")
        locks = {p for p in manifest["source"]["paths"] if p.startswith("backend/")
                 and (p.endswith(".lockfile") or "/dependency-locks/" in p)}
        if not locks <= safe or (not locks and manifest["absent_source_locks"] != ["backend/gradle.lockfile"]):
            add("<inventory>", "COVERAGE")
        for name, source_entry in manifest["source"]["paths"].items():
            decision = source_entry["decision"]
            candidate_entry = files.get(name)
            if ((decision == "exclude" and candidate_entry)
                    or (decision in ("include", "transform") and not candidate_entry)
                    or (decision == "include" and candidate_entry
                        and (source_entry["sha256"], source_entry["mode"])
                        != (candidate_entry.get("worktree_sha256", candidate_entry["sha256"]), candidate_entry["mode"]))):
                add("<inventory>", "COVERAGE")
        source_snapshots = []
        protected_paths = safe | set(manifest["source"]["paths"])
        for key in ("source", "original"):
            try:
                source_snapshots.append(_snapshot(manifest[key], key == "original", protected_paths))
            except (OSError, ValueError, KeyError, TypeError, ScanError):
                add("<inventory>", "SOURCE_SNAPSHOT")
                source_snapshots.append(None)
        head = _text(tree, "rev-parse", "HEAD")
        before_disk, before_index = _disk(tree), _index(tree)
        if "worktree_sha256" in files["backend/gradlew.bat"]:
            attributes = before_disk.get("backend/.gitattributes", ("", b""))[1]
            source_attributes = manifest["source"]["paths"].get("backend/.gitattributes", {})
            if (b"*.bat text eol=crlf" not in attributes.splitlines()
                    or source_attributes.get("sha256") != _sha(attributes)):
                add("backend/gradlew.bat", "BYTE_TRANSFORM")
        def scan(layer, rule):
            if set(layer) != safe:
                add("<repository>", rule)
            for name, (mode, data) in layer.items():
                entry = files.get(name)
                expected = entry.get("worktree_sha256", entry["sha256"]) if entry and rule == "WORKTREE_MISMATCH" else entry["sha256"] if entry else None
                if not entry or (mode, _sha(data)) != (entry["mode"], expected):
                    add(name, rule)
                for found in _scan_blob(name, mode, data, entry):
                    add(name, found)
        scan(before_disk, "WORKTREE_MISMATCH")
        scan(before_index, "INDEX_MISMATCH")
        private = set(_text(tree, "rev-list", source_head, "^" + base).splitlines())
        chain = _text(tree, "rev-list", "--parents", head, "^" + base).splitlines()
        cursor = head
        for line in chain:
            ids = line.split()
            if len(ids) != 2 or ids[0] != cursor or ids[0] in private:
                add("<repository>", "LINEAGE")
            cursor = ids[1] if len(ids) == 2 else ""
            historical_tree = _commit_tree(tree, ids[0])
            if set(historical_tree) != safe:
                add("<repository>", "HISTORY_COVERAGE")
            for name, (mode, data) in historical_tree.items():
                entry = files.get(name)
                identity = (mode, _sha(data))
                if not entry or (entry["mode"], entry["sha256"]) != identity:
                    entry = manifest["history_files"].get(name, {}).get(identity[1])
                    if entry:
                        _validate_entries({name: entry})
                    if not entry or (entry["mode"], entry["sha256"]) != identity:
                        add(name, "HISTORY_UNREVIEWED")
                if name not in safe:
                    add(name, "HISTORY_PATH")
                for found in _scan_blob(name, mode, data, entry):
                    add(name, found)
        if cursor != base:
            add("<repository>", "LINEAGE")
        # Re-read identities and bytes, not just mtimes, after all history is inspected.
        if (_read(inventory)[1] != raw_inventory or os.environ.get(PIN) != pin
                or _text(tree, "rev-parse", "HEAD") != head or _disk(tree) != before_disk
                or _index(tree) != before_index
                or graph_identity() != graph_before
                or _text(tree, "symbolic-ref", "--short", "HEAD") != manifest["candidate"]["branch"]):
            add("<input>", "INPUT_CHANGED")
        for position, key in enumerate(("source", "original")):
            if (source_snapshots[position] is not None
                    and _snapshot(manifest[key], key == "original", protected_paths) != source_snapshots[position]):
                add("<input>", "INPUT_CHANGED")
    except (Exception, KeyboardInterrupt):
        add("<input>", "INCOMPLETE_SCAN")
    return sorted(findings)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ScanError()
        result[key] = value
    return result


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        raise ScanError()


def main(argv=None):
    try:
        parser = _Parser(prog="verify-publication", description=__doc__)
        parser.add_argument("--base", required=True)
        parser.add_argument("--tree", type=Path, required=True)
        parser.add_argument("--inventory", type=Path, required=True)
        parser.add_argument("--source-head", required=True)
        args = parser.parse_args(argv)
        # CLI '.' is a convenience; the API itself demands the canonical bound root.
        findings = inspect_publication(args.base, args.tree.absolute(), args.inventory.absolute(), args.source_head)
    except (Exception, KeyboardInterrupt):
        findings = [Finding("<input>", "INVALID_ARGUMENTS")]
    for finding in findings:
        print(f"{finding.path}\t{finding.rule_id}")
    print(f"findings\t{len(findings)}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
