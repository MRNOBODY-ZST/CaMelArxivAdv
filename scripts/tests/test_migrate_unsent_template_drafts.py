"""Synthetic coordinator protocol: never uses accounts or network services."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import uuid
import contextlib
import io
import threading
import warnings
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT = Path(__file__).resolve().parents[1] / "migrate-unsent-template-drafts.py"
spec = importlib.util.spec_from_file_location("migration_driver", SCRIPT)
driver = importlib.util.module_from_spec(spec)
if SCRIPT.exists():
    spec.loader.exec_module(driver)


def uid(n):
    return str(uuid.UUID(int=n))


def manifest():
    return {"version": 1, "baseUrl": "https://synthetic.invalid", "actorId": uid(1),
            "targetTemplateId": uid(2), "targetTemplateVersionId": uid(3),
            "expectedTemplateLockVersion": 7, "partialSourcesTotal": 137,
            "sources": [{"sourceCampaignId": uid(10 + i), "expectedLockVersion": i,
                         "recipientIds": [uid(1000 + i * 120 + j) for j in range(size)],
                         "expectedMembershipDigest": str(i + 1) * 64, "partialSource": True}
                        for i, size in enumerate((40, 70, 27))]}


class FakeApi:
    def __init__(self, data):
        self.data = data
        self.calls = []
        self.results = {}
        self.disconnect = False
        self.unknown = False
        self.change = None

    def request(self, method, path, payload=None):
        self.calls.append((method, path, copy.deepcopy(payload)))
        if path == "/api/v1/auth/me":
            return {"id": self.data["actorId"]}
        source = next(s for s in self.data["sources"] if s["sourceCampaignId"] in path)
        if path.endswith("/preview"):
            result = {"sourceCampaignId": source["sourceCampaignId"],
                      "expectedLockVersion": source["expectedLockVersion"],
                      "targetTemplateId": self.data["targetTemplateId"],
                      "targetTemplateVersionId": self.data["targetTemplateVersionId"],
                      "expectedTemplateLockVersion": self.data["expectedTemplateLockVersion"],
                      "expectedMembershipDigest": source["expectedMembershipDigest"],
                      "recipientIds": source["recipientIds"], "ready": True, "reasons": [],
                      "counts": {"historicalSelected": len(source["recipientIds"]),
                                 "actionableQueued": len(source["recipientIds"]), "transferred": 0,
                                 "migrationBlocked": 0}}
            if self.change:
                self.change(result)
            return result
        if method == "GET":
            if self.unknown:
                raise driver.ApiFailure("READBACK_UNAVAILABLE")
            key = path.split("?key=")[1]
            return copy.deepcopy(self.results[key])
        key = payload["idempotencyKey"]
        if key in self.results:
            raise AssertionError("Coordinator must never repeat a POST")
        self.results[key] = {"id": uid(200 + len(self.results)), "sourceCampaignId": source["sourceCampaignId"],
                             "targetCampaignId": uid(300 + len(self.results)),
                             "targetTemplateId": self.data["targetTemplateId"],
                             "targetTemplateVersionId": self.data["targetTemplateVersionId"],
                             "selectedCount": len(source["recipientIds"]),
                             "migratedCount": len(source["recipientIds"]), "status": "MIGRATED", "reasons": []}
        if self.disconnect:
            self.disconnect = False
            raise driver.ApiFailure("RESPONSE_UNKNOWN")
        return copy.deepcopy(self.results[key])


class CoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name).resolve() / "journal.json"
        self.data = manifest()
        self.api = FakeApi(self.data)

    def run_driver(self, mode="preview"):
        return driver.coordinate(self.data, self.path, self.api, mode)

    def test_default_preview_preflights_all_without_apply(self):
        self.assertTrue(hasattr(driver, "coordinate"), "Generic private migration coordinator is missing")
        result = self.run_driver()
        self.assertEqual({key: result[key] for key in ("complete", "previewed", "pending", "blocked", "unfinished")},
                         {"complete": 0, "previewed": 3, "pending": 0, "blocked": 0, "unfinished": 3})
        self.assertEqual(len([c for c in self.api.calls if c[1].endswith("/preview")]), 3)
        self.assertEqual(self.api.results, {})
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)

    def test_40_70_27_all_preflight_before_first_apply_and_stable_completion(self):
        result = self.run_driver("apply")
        self.assertEqual(result["complete"], 3)
        writes = [i for i, c in enumerate(self.api.calls) if c[0] == "POST" and not c[1].endswith("/preview")]
        previews = [i for i, c in enumerate(self.api.calls) if c[1].endswith("/preview")]
        self.assertLess(max(previews), min(writes))
        self.assertEqual(sum(r["migratedCount"] for r in self.api.results.values()), 137)
        before = self.path.read_text()
        self.run_driver("apply")
        self.assertEqual(self.path.read_text(), before)
        self.assertEqual(len(self.api.results), 3)

    def test_invalid_total_duplicate_or_changed_manifest_stops_before_network(self):
        for size in (26, 28):
            with self.subTest(size=size):
                self.data = manifest()
                self.data["sources"][2]["recipientIds"] = [uid(7000 + i) for i in range(size)]
                with self.assertRaises(driver.ProtocolError):
                    self.run_driver("apply")
                self.assertEqual(self.api.calls, [])
        self.data = manifest()
        self.run_driver()
        self.data["sources"][0]["expectedLockVersion"] += 1
        before = len(self.api.calls)
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")
        self.assertEqual(len(self.api.calls), before)

    def test_changed_preview_proofs_versions_ids_and_identity_abort_whole_batch(self):
        for field, value in (("expectedMembershipDigest", "f" * 64), ("expectedLockVersion", 900),
                             ("targetTemplateVersionId", uid(999)), ("targetTemplateId", uid(998)),
                             ("expectedTemplateLockVersion", 9), ("recipientIds", []),
                             ("sourceCampaignId", uid(997)), ("ready", False)):
            with self.subTest(field=field):
                self.api.change = lambda result: result.update({field: value})
                with self.assertRaises(driver.ProtocolError):
                    self.run_driver("apply")
                self.assertEqual(self.api.results, {})

    def test_disconnect_uses_only_original_key_then_continues_remaining(self):
        self.api.disconnect = True
        self.api.unknown = True
        result = self.run_driver("apply")
        self.assertEqual(result["pending"], 1)
        self.assertEqual(len(self.api.results), 1)
        journal = json.loads(self.path.read_text())
        key = journal["items"][0]["command"]["idempotencyKey"]
        self.api.unknown = False
        result = self.run_driver("apply")
        self.assertEqual(result["complete"], 3)
        self.assertEqual(len(self.api.results), 3)
        self.assertTrue(any(c[1].endswith("by-key?key=" + key) for c in self.api.calls))

    def test_reconcile_never_applies_and_mismatched_readback_stays_pending(self):
        self.api.disconnect = True
        self.api.unknown = True
        self.run_driver("apply")
        key = next(iter(self.api.results))
        self.api.results[key]["targetTemplateVersionId"] = uid(987)
        self.api.unknown = False
        result = self.run_driver("reconcile")
        self.assertEqual(result["complete"], 0)
        self.assertEqual(result["pending"], 1)
        self.assertEqual(len(self.api.results), 1)
        self.assertEqual(json.loads(self.path.read_text())["items"][0]["failureCode"], "READBACK_MISMATCH")

    def test_known_post_identity_survives_restart_after_conflicting_readback(self):
        original = self.api.request
        def changed_readback(method, path, payload=None):
            result = original(method, path, payload)
            if "/by-key?" in path:
                result.update(id=uid(9990), targetCampaignId=uid(9991))
            return result
        self.api.request = changed_readback
        self.assertEqual(self.run_driver("apply")["pending"], 1)
        result = self.run_driver("apply")
        self.assertEqual(result["pending"], 1)
        self.assertEqual(result["complete"], 0)
        self.assertEqual(len(self.api.results), 1)

    def test_journal_identity_tampering_or_symlink_is_fail_closed(self):
        self.run_driver()
        data = json.loads(self.path.read_text())
        data["items"][0]["command"]["recipientIds"] = []
        self.path.write_text(json.dumps(data))
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")
        self.path.unlink()
        self.path.symlink_to(Path(self.tmp.name) / "elsewhere")
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")

    def test_transport_rejects_urls_redirects_and_non_migration_paths_without_leaking_token(self):
        for base in ("https://user:secret@example.org", "http://example.org", "https://example.org/path", "https://example.org?token=x"):
            with self.subTest(base=base), self.assertRaises(driver.ProtocolError):
                driver.MigrationApi(base, "synthetic-token")
        api = driver.MigrationApi("https://synthetic.invalid", "synthetic-token")
        for method, path in (("POST", "/api/v1/auth/refresh"), ("GET", "/t/capability"),
                             ("POST", "/api/v1/campaigns/" + uid(10) + "/start"),
                             ("GET", "https://evil.invalid")):
            with self.subTest(path=path), self.assertRaises(driver.ProtocolError):
                api.request(method, path)
        with self.assertRaises(driver.ApiFailure) as caught:
            driver.NoRedirect().redirect_request(None, None, 302, "secret-body", {}, "https://evil.invalid")
        self.assertNotIn("secret", str(caught.exception))

    def test_actor_change_stops_before_any_preview_or_write(self):
        original = self.api.request
        self.api.request = lambda method, path, payload=None: {"id": uid(900)} if path == "/api/v1/auth/me" else original(method, path, payload)
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")
        self.assertEqual(self.api.calls, [])
        self.assertEqual(self.api.results, {})

    def test_real_http_redirect_does_not_forward_authorization(self):
        received = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append((self.path, self.headers.get("Authorization")))
                self.send_response(302)
                self.send_header("Location", "http://127.0.0.1:%d/forbidden" % self.server.server_port)
                self.end_headers()
            def log_message(self, *args):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            api = driver.MigrationApi("http://127.0.0.1:%d" % server.server_port, "synthetic-secret")
            with self.assertRaises(driver.ApiFailure):
                api.request("GET", "/api/v1/auth/me")
            self.assertEqual(received, [("/api/v1/auth/me", "Bearer synthetic-secret")])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_preview_boolean_counts_are_not_numeric_proof(self):
        self.api.change = lambda result: result["counts"].update(transferred=False)
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")
        self.assertEqual(self.api.results, {})

    def test_malformed_provenance_equivalent_journal_state_cannot_claim_completion(self):
        self.run_driver("apply")
        data = json.loads(self.path.read_text())
        data["items"][0]["result"].update(status="BLOCKED", targetCampaignId=None, migratedCount=0)
        self.path.write_text(json.dumps(data))
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")

    def test_cli_default_preview_and_redacted_failure_never_persist_or_print_credentials(self):
        manifest_path = self.path.parent / "manifest.json"
        manifest_path.write_text(json.dumps(self.data))
        manifest_path.chmod(0o600)
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(driver.getpass, "getpass", return_value="synthetic-private-token"), \
             patch.object(driver, "MigrationApi", return_value=self.api), \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(driver.main(["--manifest", str(manifest_path), "--journal", str(self.path)]), 0)
            self.api.request = lambda *args: (_ for _ in ()).throw(driver.ApiFailure("credential/body/contacts MUST NOT PRINT"))
            self.assertEqual(driver.main(["--manifest", str(manifest_path), "--journal", str(self.path)]), 2)
        self.assertEqual(self.api.results, {})
        for secret in ("synthetic-private-token", "credential/body/contacts"):
            self.assertNotIn(secret, stdout.getvalue() + stderr.getvalue() + self.path.read_text())

    def test_cli_refuses_password_prompt_without_noecho_support(self):
        manifest_path = self.path.parent / "manifest.json"
        manifest_path.write_text(json.dumps(self.data))
        manifest_path.chmod(0o600)
        def unsafe_prompt(*args):
            warnings.warn("Cannot control echo on the terminal", driver.getpass.GetPassWarning)
            raise AssertionError("Credential input must stop before echo fallback")
        with patch.object(driver.getpass, "getpass", side_effect=unsafe_prompt), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(driver.main(["--manifest", str(manifest_path), "--journal", str(self.path)]), 2)
        self.assertFalse(self.path.exists())

    def test_missing_reconcile_never_creates_keys_journal_or_requests(self):
        with patch.object(driver.uuid, "uuid4", side_effect=AssertionError("No replacement key")):
            with self.assertRaises((driver.ProtocolError, OSError)):
                self.run_driver("reconcile")
        self.assertFalse(self.path.exists())
        self.assertEqual(self.api.calls, [])

        manifest_path = self.path.parent / "manifest.json"
        manifest_path.write_text(json.dumps(self.data))
        manifest_path.chmod(0o600)
        with patch.object(driver.getpass, "getpass", return_value="synthetic-token"), \
             patch.object(driver, "MigrationApi", return_value=self.api), \
             patch.object(driver.uuid, "uuid4", side_effect=AssertionError("No replacement key")), \
             contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(driver.main(["--manifest", str(manifest_path), "--journal", str(self.path), "--mode", "reconcile"]), 2)
        self.assertFalse(self.path.exists())
        self.assertEqual(self.api.calls, [])

    def test_disappearing_reconcile_journal_never_falls_back_to_creation(self):
        self.run_driver()
        self.api.calls.clear()
        original = driver.private_read
        def disappeared(path):
            path.unlink()
            return original(path)
        with patch.object(driver, "private_read", side_effect=disappeared), \
             patch.object(driver.uuid, "uuid4", side_effect=AssertionError("No replacement key")):
            with self.assertRaises((driver.ProtocolError, OSError)):
                self.run_driver("reconcile")
        self.assertFalse(self.path.exists())
        self.assertEqual(self.api.calls, [])

    def test_reconcile_accounts_for_unexecuted_original_items_and_cli_is_nonzero(self):
        self.run_driver()
        journal = json.loads(self.path.read_text())
        journal["items"][0]["state"] = "NEW"
        self.path.write_text(json.dumps(journal))
        before = self.path.read_bytes()
        self.api.calls.clear()
        result = self.run_driver("reconcile")
        self.assertEqual(result.get("unfinished"), 3)
        self.assertEqual([i["state"] for i in result["dispositions"]], ["NEW", "PREVIEWED", "PREVIEWED"])
        self.assertEqual(self.path.read_bytes(), before)
        self.assertTrue(all(c[0] == "GET" for c in self.api.calls))
        manifest_path = self.path.parent / "manifest.json"
        manifest_path.write_text(json.dumps(self.data))
        manifest_path.chmod(0o600)
        with patch.object(driver.getpass, "getpass", return_value="synthetic-token"), \
             patch.object(driver, "MigrationApi", return_value=self.api), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(driver.main(["--manifest", str(manifest_path), "--journal", str(self.path), "--mode", "reconcile"]), 2)

    def test_later_source_preflight_failures_survive_restart_without_erasing_completion(self):
        cases = (("ready", False, "PREVIEW_NOT_READY"), ("counts", {}, "PREVIEW_MALFORMED"),
                 ("counts", {"historicalSelected": 70, "actionableQueued": 69, "transferred": 0, "migrationBlocked": 1}, "PREVIEW_COUNTS_MISMATCH"),
                 ("expectedMembershipDigest", "f" * 64, "PREVIEW_IDENTITY_MISMATCH"),
                 ("transport", None, "PREVIEW_UNAVAILABLE"), ("malformed", None, "PREVIEW_MALFORMED"),
                 ("actor", None, "PREVIEW_ACTOR_MISMATCH"))
        for index, (field, value, code) in enumerate(cases):
            with self.subTest(field=field, code=code):
                self.path = self.path.parent / ("case-%d.json" % index)
                self.api = FakeApi(self.data)
                self.api.disconnect = self.api.unknown = True
                self.run_driver("apply")
                self.api.unknown = False
                self.run_driver("reconcile")
                original_journal = json.loads(self.path.read_text())
                original_request = self.api.request
                failed_source = self.data["sources"][1]["sourceCampaignId"]
                def failure(method, path, payload=None):
                    if field == "actor" and path == "/api/v1/auth/me":
                        return {"id": uid(900)}
                    if failed_source in path and path.endswith("/preview"):
                        if field == "transport":
                            raise driver.ApiFailure("secret/body/credential")
                        if field == "malformed":
                            return None
                        result = original_request(method, path, payload)
                        result[field] = value
                        return result
                    return original_request(method, path, payload)
                self.api.request = failure
                with self.assertRaises((driver.ProtocolError, driver.ApiFailure)):
                    self.run_driver("apply")
                failed = json.loads(self.path.read_text())
                self.assertEqual(failed["items"][0], original_journal["items"][0])
                self.assertEqual(failed["items"][1]["state"], "PREVIEW_FAILED")
                self.assertEqual(failed["items"][1]["failureCode"], code)
                self.assertIsNone(failed["items"][1]["result"])
                self.assertIsNone(failed["items"][1]["observedResult"])
                self.assertNotEqual(failed["items"][2]["state"], "PREVIEWED")
                for before, after in zip(original_journal["items"], failed["items"]):
                    for key in ("command", "requestDigest", "readbackPath"):
                        self.assertEqual(before[key], after[key])
                self.assertEqual(len(self.api.results), 1)
                self.api.request = original_request
                restarted = self.run_driver("reconcile")
                self.assertEqual(restarted["unfinished"], 2)
                self.assertEqual(json.loads(self.path.read_text())["items"][1]["failureCode"], code)
                self.assertNotIn("secret", self.path.read_text())
                self.assertEqual(self.run_driver("apply")["complete"], 3)
                self.assertIsNone(json.loads(self.path.read_text())["items"][1]["failureCode"])

    def test_cli_preflight_failure_reports_only_safe_source_disposition(self):
        manifest_path = self.path.parent / "manifest.json"
        manifest_path.write_text(json.dumps(self.data))
        manifest_path.chmod(0o600)
        self.api.change = lambda result: result.update(ready=False, reasons=[{"code": "secret/body", "count": 1}])
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(driver.getpass, "getpass", return_value="synthetic-secret"), \
             patch.object(driver, "MigrationApi", return_value=self.api), \
             contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(driver.main(["--manifest", str(manifest_path), "--journal", str(self.path), "--mode", "apply"]), 2)
        output = stdout.getvalue() + stderr.getvalue()
        self.assertIn(self.data["sources"][0]["sourceCampaignId"], output)
        self.assertIn("PREVIEW_NOT_READY", output)
        self.assertIn("PREVIEW_FAILED", output)
        self.assertNotIn("secret", output + self.path.read_text())

    def test_reconcile_rejects_corrupt_foreign_or_lost_during_readback_journals(self):
        self.run_driver()
        original = self.path.read_bytes()
        for invalid in (b"not-json", original.replace(self.data["actorId"].encode(), uid(999).encode())):
            self.path.write_bytes(invalid)
            self.api.calls.clear()
            with patch.object(driver.uuid, "uuid4", side_effect=AssertionError("No replacement key")):
                with self.assertRaises(driver.ProtocolError):
                    self.run_driver("reconcile")
            self.assertEqual(self.api.calls, [])
            self.assertEqual(self.path.read_bytes(), invalid)
        self.path.write_bytes(original)
        self.api.disconnect = self.api.unknown = True
        self.run_driver("apply")
        self.api.unknown = False
        original_request = self.api.request
        def lost(method, path, payload=None):
            result = original_request(method, path, payload)
            if "/by-key?" in path:
                self.path.unlink()
            return result
        self.api.request = lost
        with self.assertRaises((driver.ProtocolError, OSError)):
            self.run_driver("reconcile")
        self.assertFalse(self.path.exists())
        self.assertEqual(len(self.api.results), 1)

    def test_failure_is_cleared_only_after_entire_remaining_batch_verifies(self):
        self.api.change = lambda result: result.update(ready=False)
        with self.assertRaises(driver.ProtocolError):
            self.run_driver()
        first = json.loads(self.path.read_text())["items"][0]
        second_source = self.data["sources"][1]["sourceCampaignId"]
        self.api.change = lambda result: result.update(ready=False) if result["sourceCampaignId"] == second_source else None
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")
        journal = json.loads(self.path.read_text())
        self.assertEqual(journal["items"][0], first)
        self.assertEqual(journal["items"][1]["state"], "PREVIEW_FAILED")
        self.assertEqual(self.api.results, {})
        self.api.change = None
        self.assertEqual(self.run_driver()["previewed"], 3)
        self.assertTrue(all(i["failureCode"] is None for i in json.loads(self.path.read_text())["items"]))

    def test_actor_change_before_later_apply_preserves_prior_complete_and_invalidates_preview(self):
        original_request = self.api.request
        checks_after_first_apply = 0
        def changed(method, path, payload=None):
            nonlocal checks_after_first_apply
            if path == "/api/v1/auth/me" and len(self.api.results) == 1:
                checks_after_first_apply += 1
                if checks_after_first_apply == 2:  # First check is original-key readback.
                    return {"id": uid(900)}
            return original_request(method, path, payload)
        self.api.request = changed
        with self.assertRaises(driver.ProtocolError):
            self.run_driver("apply")
        journal = json.loads(self.path.read_text())
        self.assertEqual(journal["items"][0]["state"], "COMPLETE")
        self.assertEqual(journal["items"][1]["state"], "PREVIEW_FAILED")
        self.assertEqual(journal["items"][1]["failureCode"], "PREVIEW_ACTOR_MISMATCH")
        self.assertEqual(journal["items"][2]["state"], "NEW")
        self.assertEqual(len(self.api.results), 1)


if __name__ == "__main__":
    unittest.main()
