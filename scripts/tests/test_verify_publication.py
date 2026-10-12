"""Behavioral publication gates exercised only in disposable synthetic repositories."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "verify-publication.py"
PIN = "CAMEL_PUBLICATION_APPROVED_INVENTORY_SHA256"
# Literal minimum dependency inventory, independent of scanner implementation.
ANCHORS = """README.md LICENSE .gitignore .dockerignore .env.example
docker-compose.yml docker-compose.dev.yml infra/nginx/default.conf
infra/postgres/init/.gitkeep backend/.gitattributes backend/.gitignore
backend/Dockerfile backend/compose.yaml backend/build.gradle backend/settings.gradle
backend/gradlew backend/gradlew.bat backend/gradle/wrapper/gradle-wrapper.properties
backend/gradle/wrapper/gradle-wrapper.jar
backend/src/main/resources/application.yaml
backend/src/main/resources/application-mail-worker.yaml
backend/src/main/resources/arxiv/taxonomy-2026-08.json
backend/src/main/resources/unicode/CaseFolding-15.0.0.txt
backend/src/main/resources/unicode/LICENSE.txt
backend/src/test/resources/arxiv/legacy-preview.xml
frontend/Dockerfile frontend/index.html frontend/package.json frontend/package-lock.json
frontend/tsconfig.json frontend/tsconfig.app.json frontend/tsconfig.node.json
frontend/vite.config.ts frontend/vitest.config.ts frontend/playwright.config.ts
frontend/eslint.config.js frontend/src/env.d.ts frontend/src/test/setup.ts
worker/Dockerfile worker/pyproject.toml worker/uv.lock
worker/tests/fixtures/arxiv/legacy-page.xml worker/tests/fixtures/arxiv/list-sets.xml
worker/tests/fixtures/arxiv/oai-page-1.xml worker/tests/fixtures/arxiv/oai-page-2.xml
contracts/fixtures/source-command-identity-v1.json
contracts/fixtures/source-admission-state-v2.json
docs/email/camel-hub-auto-outreach-template.json docs/email/preparation-diagnostics.md
docs/THIRD_PARTY_NOTICES.md scripts/generate-arxiv-taxonomy-snapshot.py
scripts/verify-compose.sh scripts/verify-container-images.sh
scripts/migrate-unsent-template-drafts.py scripts/tests/test_migrate_unsent_template_drafts.py
scripts/verify-publication.py scripts/tests/test_verify_publication.py
backend/src/main/java/Synthetic.java backend/src/test/java/SyntheticTest.java
backend/src/main/resources/db/migration/V1__synthetic.sql
frontend/src/Synthetic.vue frontend/src/__tests__/synthetic.spec.ts
frontend/e2e/synthetic.spec.ts worker/src/app/synthetic.py worker/tests/test_synthetic.py
""".split()


def digest(data):
    return hashlib.sha256(data).hexdigest()


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "publication verifier implementation is missing")
        spec = importlib.util.spec_from_file_location("publication_verifier", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = self.module
        spec.loader.exec_module(self.module)
        self.tmp = tempfile.TemporaryDirectory(prefix="publication-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.repo = self.root / "candidate"
        self.repo.mkdir()
        self.git("init", "-q", "-b", "public")
        self.git("config", "user.name", "Synthetic Reviewer")
        self.git("config", "user.email", "reviewer@example.invalid")
        for name in ANCHORS:
            self.write(name, "synthetic fixture\n")
        self.git("add", "--all")
        self.commit("synthetic public baseline")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.git("checkout", "-q", "-b", "private")
        self.write("private-note.md", "private synthetic note\n")
        self.git("add", "private-note.md")
        self.commit("synthetic private source")
        self.source = self.git("rev-parse", "HEAD").strip()
        self.source_root = self.root / "source"
        self.git("checkout", "-q", "public")
        self.git("worktree", "add", "-q", str(self.source_root), "private")
        self.inventory = self.root / "review.json"
        self.files = {}
        self.refresh()

    def git(self, *args, cwd=None):
        return subprocess.check_output(["git", "-C", str(cwd or self.repo), *args],
                                       stderr=subprocess.PIPE, text=True)

    def commit(self, message):
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", message)

    def write(self, name, content):
        p = self.repo / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content.encode() if isinstance(content, str) else content)

    def refresh(self, exceptions=None, binary=None):
        self.files = {}
        for p in sorted(self.repo.rglob("*")):
            if not p.is_file() or ".git" in p.relative_to(self.repo).parts:
                continue
            name = p.relative_to(self.repo).as_posix()
            self.files[name] = {"sha256": digest(p.read_bytes()), "mode": "100644",
                                "review": "Synthetic test input", "exceptions": []}
        for name, items in (exceptions or {}).items():
            self.files[name]["exceptions"] = items
        for name, provenance in (binary or {}).items():
            self.files[name]["binary_provenance"] = provenance
        source_files = {}
        for name in self.git("ls-files", "-z", cwd=self.source_root).split("\0"):
            if name:
                source_files[name] = {"sha256": digest((self.source_root / name).read_bytes()),
                                      "mode": "100644", "decision": ("include" if self.files[name]["sha256"] == digest((self.source_root / name).read_bytes()) else "transform") if name in self.files else "exclude",
                                      "reason": "Synthetic coverage fixture"}
        self.manifest = {"version": 1, "base": self.base, "source_head": self.source,
                         "candidate": {"root": str(self.repo), "branch": "public",
                                       "git_common_dir": str(self.repo / ".git")},
                         "source": {"root": str(self.source_root), "head": self.source,
                                    "paths": source_files},
                         "original": {"root": str(self.source_root), "head": self.source,
                                      "paths": source_files},
                         "files": self.files, "history_files": {},
                         "absent_source_locks": ["backend/gradle.lockfile"]}
        self.save()

    def save(self):
        self.inventory.write_text(json.dumps(self.manifest, sort_keys=True))
        self.pin = digest(self.inventory.read_bytes())

    def inspect(self):
        with patch.dict(os.environ, {PIN: self.pin}):
            return self.module.inspect_publication(self.base, self.repo, self.inventory, self.source)

    def rules(self):
        return {f.rule_id for f in self.inspect()}

    def cli(self, *extra):
        return subprocess.run([sys.executable, str(SCRIPT), "--base", self.base,
                               "--tree", str(self.repo), "--inventory", str(self.inventory),
                               "--source-head", self.source, *extra], capture_output=True,
                              text=True, env={**os.environ, PIN: self.pin})

    def test_cleanStagedBaseAndPublicCommitAccepted(self):
        self.assertEqual([], self.inspect())
        self.write("README.md", "reviewed synthetic change\n")
        self.refresh()
        self.git("add", "README.md")
        self.assertEqual([], self.inspect())
        self.commit("complete curated candidate")
        self.assertEqual([], self.inspect())
        self.assertEqual(0, self.cli().returncode)

    def test_newHistorySecretSurvivesDeletion(self):
        self.write("leak.txt", "reader@private-synthetic.invalid\n")
        self.git("add", "leak.txt")
        self.commit("temporary synthetic contact")
        self.git("rm", "-q", "leak.txt")
        self.commit("remove synthetic contact")
        self.assertIn("EMAIL", self.rules())

    def commit_mode_roundtrip(self):
        self.git("update-index", "--chmod=+x", "README.md")
        self.commit("synthetic intermediate executable mode")
        self.assertTrue(self.git("ls-tree", "HEAD", "README.md").startswith("100755 "))
        self.git("update-index", "--chmod=-x", "README.md")
        self.commit("restore synthetic reviewed mode")
        self.assertTrue(self.git("ls-tree", "HEAD", "README.md").startswith("100644 "))

    def test_historicalModeCurrentHashDoesNotApproveWrongMode(self):
        self.commit_mode_roundtrip()
        self.assertEqual([("README.md", "HISTORY_UNREVIEWED")],
                         [(f.path, f.rule_id) for f in self.inspect()])
        result = self.cli()
        self.assertEqual(1, result.returncode)
        self.assertEqual("README.md\tHISTORY_UNREVIEWED\nfindings\t1\n", result.stdout)
        self.assertEqual("", result.stderr)

    def test_historicalModeFallbackRequiresExactIdentity(self):
        old = dict(self.files["README.md"])
        self.commit_mode_roundtrip()
        self.write("README.md", "synthetic current revision\n")
        self.git("add", "README.md")
        self.commit("synthetic current content")
        self.refresh()
        for field, wrong in (("mode", "100644"), ("sha256", "0" * 64)):
            with self.subTest(field=field):
                reviewed = {**old, "mode": "100755", field: wrong}
                self.manifest["history_files"] = {"README.md": {old["sha256"]: reviewed}}
                self.save()
                self.assertIn("HISTORY_UNREVIEWED", self.rules())

    def test_historicalModeExplicitSameHashIdentityAccepted(self):
        self.commit_mode_roundtrip()
        old = {**self.files["README.md"], "mode": "100755"}
        self.manifest["history_files"] = {"README.md": {old["sha256"]: old}}
        self.save()
        self.assertEqual([], self.inspect())

    def test_historicalModeExplicitDifferentHashIdentityAccepted(self):
        old = dict(self.files["README.md"])
        self.git("update-index", "--chmod=+x", "README.md")
        self.commit("synthetic reviewed historical executable")
        self.git("update-index", "--chmod=-x", "README.md")
        self.write("README.md", "synthetic current revision\n")
        self.git("add", "README.md")
        self.commit("synthetic current content and mode")
        self.refresh()
        old["mode"] = "100755"
        self.manifest["history_files"] = {"README.md": {old["sha256"]: old}}
        self.save()
        self.assertEqual([], self.inspect())

    def test_privateAncestorMergeRejected(self):
        self.git("merge", "--no-ff", "--no-commit", "private")
        self.git("rm", "-q", "-f", "private-note.md")
        self.commit("clean tip with private parent")
        self.assertIn("LINEAGE", self.rules())

    def test_untrackedForbiddenArtifactRejected(self):
        self.write(".env", "synthetic artifact")
        self.assertIn("FORBIDDEN_PATH", self.rules())

    def test_ignoredArtifactRejected(self):
        self.write(".gitignore", "build/\n")
        self.refresh()
        self.git("add", ".gitignore")
        self.write("build/cache.txt", "synthetic artifact")
        self.assertIn("FORBIDDEN_PATH", self.rules())

    def test_unexpectedBinaryRejected(self):
        self.write("opaque.dat", b"\x00\xffsynthetic")
        self.assertIn("BINARY", self.rules())

    def test_fixtureExceptionIsPathSpecific(self):
        value = "fixture@example.invalid"
        self.write("README.md", value)
        self.refresh({"README.md": [{"rule_id": "EMAIL", "value": value}]})
        self.git("add", "README.md")
        self.assertEqual([], self.inspect())
        self.write("LICENSE", value)
        self.assertIn("EMAIL", self.rules())

    def test_secondMatchInApprovedFileRejected(self):
        self.write("README.md", "fixture@example.invalid other@example.invalid")
        self.refresh({"README.md": [{"rule_id": "EMAIL", "value": "fixture@example.invalid"}]})
        self.git("add", "README.md")
        self.assertIn("EMAIL", self.rules())

    def test_exceptionCannotAuthorizeOldBlob(self):
        self.write("README.md", "fixture@example.invalid old version")
        self.git("add", "README.md")
        self.commit("old fixture")
        self.write("README.md", "fixture@example.invalid current version")
        self.refresh({"README.md": [{"rule_id": "EMAIL", "value": "fixture@example.invalid"}]})
        self.git("add", "README.md")
        self.assertIn("EMAIL", self.rules())

    def test_scannerNeverPrintsMatchedValue(self):
        sentinel = "confidential-marker@private-synthetic.invalid"
        self.write("unreviewed-" + sentinel, sentinel)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            findings = self.inspect()
        self.assertNotIn(sentinel, repr(findings) + output.getvalue())
        self.assertTrue(all(set(vars(f)) == {"path", "rule_id"} for f in findings))
        result = self.cli()
        self.assertEqual(1, result.returncode)
        self.assertNotIn(sentinel, result.stdout + result.stderr)
        self.assertEqual("", result.stderr)
        self.assertNotIn(str(self.root), result.stdout)

    def test_stagedSecretCleanWorktreeRejected(self):
        self.write("README.md", "stage@example.invalid")
        self.git("add", "README.md")
        self.write("README.md", "synthetic fixture\n")
        self.assertIn("EMAIL", self.rules())

    def test_cleanIndexSecretWorktreeRejected(self):
        self.write("README.md", "working@example.invalid")
        self.assertIn("EMAIL", self.rules())

    def test_unstagedCuratedTreeRejected(self):
        self.write("README.md", "clean new contents")
        self.refresh()
        self.assertIn("INDEX_MISMATCH", self.rules())

    def test_unverifiedInventoryRejected(self):
        with patch.dict(os.environ, {PIN: ""}):
            self.assertEqual("INVENTORY_UNAPPROVED", self.module.inspect_publication(
                self.base, self.repo, self.inventory, self.source)[0].rule_id)
        self.inventory.write_text(self.inventory.read_text() + " ")
        self.assertIn("INVENTORY_UNAPPROVED", self.rules())

    def test_wrongBaseSourceAndRootRejected(self):
        for field in ("base", "source_head"):
            with self.subTest(field=field):
                saved = self.manifest[field]
                self.manifest[field] = "0" * 40
                self.save()
                self.assertIn("IDENTITY", self.rules())
                self.manifest[field] = saved
        self.manifest["candidate"]["root"] = str(self.root)
        self.save()
        self.assertIn("IDENTITY", self.rules())

    def test_missingTestsAndMandatoryLocksRejected(self):
        for name in ("frontend/package-lock.json", "worker/uv.lock",
                     "backend/src/test/java/SyntheticTest.java"):
            with self.subTest(name=name):
                saved = self.manifest["files"].pop(name)
                self.save()
                self.assertIn("COVERAGE", self.rules())
                self.manifest["files"][name] = saved

    def test_sourceSnapshotMutationRejected(self):
        (self.source_root / "README.md").write_text("changed source snapshot")
        self.assertIn("SOURCE_SNAPSHOT", self.rules())

    def test_sourceCoverageOmissionRejected(self):
        del self.manifest["source"]["paths"]["README.md"]
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())

    def test_symlinkOpaqueAndSpecialInputsRejected(self):
        self.repo.joinpath("outside-link").symlink_to(self.inventory)
        self.assertIn("UNSAFE_TYPE", self.rules())
        self.write("opaque.txt", "synthetic".encode("utf-16"))
        self.assertIn("BINARY", self.rules())
        os.mkfifo(self.repo / "pipe")
        self.assertIn("UNSAFE_TYPE", self.rules())

    def test_replacementGraphRejected(self):
        self.git("replace", self.base, self.source)
        self.assertIn("GRAPH", self.rules())

    def test_unrelatedRootRejected(self):
        self.git("checkout", "--orphan", "unrelated")
        self.commit("different root")
        self.manifest["candidate"]["branch"] = "unrelated"
        self.save()
        self.assertIn("LINEAGE", self.rules())

    def test_binaryExceptionBoundToPathHashAndMode(self):
        name = "backend/gradle/wrapper/gradle-wrapper.jar"
        raw = b"PK\x00synthetic-wrapper"
        self.write(name, raw)
        self.refresh(binary={name: {"url": "https://gradle.org/release-checksums/",
                                   "sha256": digest(raw), "review": "synthetic primary provenance"}})
        self.git("add", name)
        self.assertEqual([], self.inspect())
        self.write("renamed.jar", raw)
        self.assertIn("BINARY", self.rules())
        self.write(name, raw + b"changed")
        self.assertIn("BINARY", self.rules())

    def test_usageAndMalformedInventoryFailSafely(self):
        result = self.cli("--unknown-confidential-argument")
        self.assertEqual(1, result.returncode)
        self.assertNotIn("unknown-confidential", result.stdout + result.stderr)
        self.inventory.write_text('{"bad": "synthetic-private-marker"')
        self.pin = digest(self.inventory.read_bytes())
        result = self.cli()
        self.assertEqual(1, result.returncode)
        self.assertNotIn("synthetic-private-marker", result.stdout + result.stderr)

    def test_inventorySwapDuringScanRejected(self):
        original = self.module._scan_blob
        swapped = False
        def swapping(*args, **kwargs):
            nonlocal swapped
            if not swapped:
                swapped = True
                self.inventory.write_text(self.inventory.read_text() + " ")
            return original(*args, **kwargs)
        with patch.object(self.module, "_scan_blob", swapping):
            self.assertIn("INPUT_CHANGED", self.rules())

    def test_worktreeMutationDuringScanRejected(self):
        original = self.module._scan_blob
        count = 0
        def mutating(*args, **kwargs):
            nonlocal count
            count += 1
            if count == 1:
                self.write("README.md", "concurrent changed bytes")
            return original(*args, **kwargs)
        with patch.object(self.module, "_scan_blob", mutating):
            self.assertIn("INPUT_CHANGED", self.rules())

    def test_unreviewedIntermediateCleanBytesRejected(self):
        self.write("README.md", "unreviewed intermediate contents")
        self.git("add", "README.md")
        self.commit("unreviewed intermediate")
        self.write("README.md", "synthetic fixture\n")
        self.git("add", "README.md")
        self.assertIn("HISTORY_UNREVIEWED", self.rules())

    def test_includeDecisionMustProveSourceByteEquivalence(self):
        self.write("README.md", "different candidate bytes")
        self.refresh()
        self.manifest["source"]["paths"]["README.md"]["decision"] = "include"
        self.save()
        self.git("add", "README.md")
        self.assertIn("COVERAGE", self.rules())

    def test_excludedSourcePathCannotBePublished(self):
        self.manifest["source"]["paths"]["README.md"]["decision"] = "exclude"
        self.save()
        self.assertIn("COVERAGE", self.rules())

    def test_sensitiveBaselineMustBeCuratedInFirstNewCommit(self):
        self.write("inherited.txt", "inherited@example.invalid")
        self.git("add", "inherited.txt")
        self.commit("old public exposure")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.git("commit", "--allow-empty", "-qm", "first new tree inherits exposure")
        self.git("rm", "-q", "inherited.txt")
        self.commit("later sanitization is too late")
        self.refresh()
        self.assertIn("EMAIL", self.rules())

    def test_indexConflictAndUnreadableFileFailClosed(self):
        oid = self.git("rev-parse", "HEAD:README.md").strip()
        subprocess.run(["git", "-C", str(self.repo), "update-index", "--index-info"],
                       input=f"0 {'0' * 40}\tREADME.md\n100644 {oid} 1\tREADME.md\n",
                       text=True, check=True, capture_output=True)
        self.assertIn("INCOMPLETE_SCAN", self.rules())
        self.git("reset", "-q", "HEAD", "--", "README.md")
        (self.repo / "README.md").chmod(0)
        self.addCleanup((self.repo / "README.md").chmod, 0o644)
        self.assertIn("INCOMPLETE_SCAN", self.rules())

    def test_credentialAndCapabilityRulesRetainExactExceptions(self):
        self.write("README.md", "api_key=synthetic-value-only\nhttps://example.invalid/?token=synthetic-only")
        self.assertTrue({"CREDENTIAL", "CAPABILITY"} <= self.rules())

    def test_pinnedAdversarialPathStillCannotLeak(self):
        name = "confidential@example.invalid"
        self.write(name, "synthetic")
        self.refresh()
        result = self.cli()
        self.assertEqual(1, result.returncode)
        self.assertNotIn(name, result.stdout + result.stderr)

    def test_renamedWorktreeIdentityRejected(self):
        alias = self.root / "alias"
        alias.symlink_to(self.repo, target_is_directory=True)
        with patch.dict(os.environ, {PIN: self.pin}):
            self.assertTrue(self.module.inspect_publication(self.base, alias, self.inventory, self.source))

    def test_firstIncompleteCuratedTreeRejected(self):
        self.git("rm", "-q", "frontend/package-lock.json")
        self.commit("incomplete first new tree")
        self.write("frontend/package-lock.json", "synthetic fixture\n")
        self.git("add", "frontend/package-lock.json")
        self.assertIn("HISTORY_COVERAGE", self.rules())

    def test_graphMutationDuringScanRejected(self):
        original = self.module._scan_blob
        changed = False
        def changing(*args, **kwargs):
            nonlocal changed
            if not changed:
                changed = True
                self.git("replace", self.base, self.source)
            return original(*args, **kwargs)
        with patch.object(self.module, "_scan_blob", changing):
            self.assertIn("INPUT_CHANGED", self.rules())

    def test_readonlyGitDoesNotRunConfiguredFsmonitor(self):
        marker = self.root / "hook-executed"
        hook = self.root / "fsmonitor.sh"
        hook.write_text("#!/bin/sh\n: > " + shlex.quote(str(marker)) + "\nprintf 'synthetic-token\\0'\n")
        hook.chmod(0o755)
        self.git("config", "core.fsmonitor", str(hook))
        self.inspect()
        self.assertFalse(marker.exists(), "read-only verification must never execute repository hooks")

    def test_reviewedWrapperCrlfAndBlobLfBothBound(self):
        attributes = "*.bat text eol=crlf\n"
        self.write("backend/.gitattributes", attributes)
        (self.source_root / "backend/.gitattributes").write_text(attributes)
        raw = b"synthetic wrapper\r\nsecond line\r\n"
        self.write("backend/gradlew.bat", raw)
        self.refresh()
        entry = self.files["backend/gradlew.bat"]
        entry["worktree_sha256"] = digest(raw)
        entry["sha256"] = digest(raw.replace(b"\r\n", b"\n"))
        entry["transform"] = "git-crlf-to-lf"
        self.save()
        self.git("add", "backend/.gitattributes", "backend/gradlew.bat")
        self.assertEqual([], self.inspect())

    def test_dualHashCannotAuthorizeOtherPaths(self):
        self.files["README.md"]["worktree_sha256"] = self.files["README.md"]["sha256"]
        self.files["README.md"]["transform"] = "git-crlf-to-lf"
        self.save()
        self.assertTrue(self.inspect())

    def test_unknownTransformNeverSilentlyIgnored(self):
        self.files["README.md"]["transform"] = "trust-any-normalization"
        self.save()
        self.assertTrue(self.inspect())

    def original_fixture(self, name, data, redacted=False, generated=False):
        original = self.root / "original"
        original.mkdir()
        self.git("init", "-q", "-b", "original", cwd=original)
        self.git("-c", "user.name=Synthetic Reviewer", "-c", "user.email=reviewer@example.invalid",
                 "commit", "--allow-empty", "-qm", "synthetic original", cwd=original)
        target = original / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        entry = {"sha256": digest(data), "mode": "100644", "size": len(data),
                 "decision": "exclude", "reason": "Excluded original private artifact"}
        if generated:
            entry.update(snapshot_kind="generated", reason="Excluded original generated artifact")
        self.manifest["original"] = {"root": str(original),
                                     "head": self.git("rev-parse", "HEAD", cwd=original).strip(),
                                     "paths": {} if redacted else {name: entry},
                                     "redacted_paths": {digest(name.encode()): entry} if redacted else {}}
        self.save()
        return target, entry

    def test_originalRedactedPathIsExhaustiveAndNeverPrinted(self):
        name = "docs/private/person@example.invalid.txt"
        self.original_fixture(name, b"synthetic private evidence", redacted=True)
        self.assertEqual([], self.inspect())
        self.assertNotIn(name, self.inventory.read_text())
        result = self.cli()
        self.assertEqual(0, result.returncode)
        self.assertNotIn(name, result.stdout + result.stderr)

    def test_originalRedactedMissingExtraOverlapAndNonExcludeRejected(self):
        name = "docs/private/person@example.invalid.txt"
        self.original_fixture(name, b"synthetic", redacted=True)
        spec = self.manifest["original"]
        key = digest(name.encode())
        entry = spec["redacted_paths"].pop(key)
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())
        spec["redacted_paths"][key] = entry
        spec["redacted_paths"]["0" * 64] = entry
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())
        del spec["redacted_paths"]["0" * 64]
        spec["paths"][name] = entry
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())
        del spec["paths"][name]
        entry["decision"] = "include"
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())

    def test_originalRedactionCannotHideFunctionalOrSourcePaths(self):
        self.original_fixture("worker/src/app/hidden.py", b"synthetic", redacted=True)
        self.assertIn("SOURCE_SNAPSHOT", self.rules())
        self.manifest["source"]["redacted_paths"] = {"0" * 64: {}}
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())

    def test_originalRedactedSymlinkAndMutationRejected(self):
        target, entry = self.original_fixture("docs/private/synthetic.txt", b"synthetic", redacted=True)
        target.unlink()
        target.symlink_to(self.repo / "README.md")
        self.assertIn("SOURCE_SNAPSHOT", self.rules())
        target.unlink()
        target.write_bytes(b"synthetic")
        original = self.module._scan_blob
        def changing(*args, **kwargs):
            target.write_bytes(b"changed")
            return original(*args, **kwargs)
        with patch.object(self.module, "_scan_blob", changing):
            self.assertTrue(self.inspect())

    def test_originalGeneratedOversizeSnapshotOnly(self):
        target, entry = self.original_fixture("backend/build-synthetic/archive.jar",
                                              b"x" * (32 * 1024 * 1024 + 1), generated=True)
        self.assertEqual([], self.inspect())
        entry["size"] -= 1
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())
        entry["size"] += 1
        entry.pop("snapshot_kind")
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())

    def test_originalSnapshotCannotIgnoreReviewedSize(self):
        target, entry = self.original_fixture("docs/private/synthetic.txt", b"synthetic")
        entry["size"] += 1
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())

    def test_sourceCannotUseOriginalRedactedExtension(self):
        self.manifest["source"]["redacted_paths"] = {"0" * 64: {}}
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())

    def test_originalSnapshotSameBytesReplacementRejected(self):
        target, entry = self.original_fixture("docs/private/synthetic.txt", b"synthetic", redacted=True)
        scan = self.module._scan_blob
        replaced = False
        def changing(*args, **kwargs):
            nonlocal replaced
            if not replaced:
                replacement = target.with_suffix(".replacement")
                replacement.write_bytes(b"synthetic")
                replacement.replace(target)
                replaced = True
            return scan(*args, **kwargs)
        with patch.object(self.module, "_scan_blob", changing):
            self.assertIn("INPUT_CHANGED", self.rules())

    def test_originalRedactedMalformedDigestAndDuplicateJSONRejected(self):
        name = "docs/private/synthetic.txt"
        target, entry = self.original_fixture(name, b"synthetic", redacted=True)
        key = digest(name.encode())
        self.manifest["original"]["redacted_paths"] = {"malformed": entry}
        self.save()
        self.assertIn("SOURCE_SNAPSHOT", self.rules())
        self.manifest["original"]["redacted_paths"] = {key: entry}
        self.save()
        marker = json.dumps(key) + ": " + json.dumps(entry, sort_keys=True)
        raw = self.inventory.read_text()
        self.assertIn(marker, raw)
        self.inventory.write_text(raw.replace(marker, marker + ", " + marker))
        self.pin = digest(self.inventory.read_bytes())
        self.assertTrue(self.inspect())

    def test_candidateOversizeStillFailsClosed(self):
        self.write("README.md", b"x" * (32 * 1024 * 1024 + 1))
        self.assertTrue(self.inspect())


if __name__ == "__main__":
    unittest.main()
