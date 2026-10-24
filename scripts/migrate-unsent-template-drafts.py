#!/usr/bin/env python3
"""Private, explicit draft-migration coordinator. Default: preview. No send workflow.

Manifest v1 (private 0600 file, parent directory 0700):
  version, baseUrl (origin only), actorId, targetTemplateId, targetTemplateVersionId,
  expectedTemplateLockVersion, partialSourcesTotal (exactly 137), sources.
Each source: sourceCampaignId, expectedLockVersion, recipientIds (exact UUID set),
  expectedMembershipDigest, partialSource (boolean). Partial sources total 137.
The original server preview membership digest binds the private immutable template
record, source, sender and ownership evidence. Never hash a public template DTO as
a substitute. Private raw template/history evidence must be separately frozen and
verified before this tool is used. This script does not certify that release gate.

Journal v1 binds the canonical entire manifest and each original actor/source/full
command. requestDigest is a LOCAL canonical JSON SHA256, not the server-private
membership digest. A durable PENDING item precedes every single apply POST; all
unknown outcomes use its original-key GET only. No new key, POST retry, login,
refresh, review, start, SMTP, partner or content URL request exists here.
Token is read through getpass into process memory; never pass it as an argument.
Reconcile requires the existing original journal; unexecuted selections remain
explicitly unfinished. PREVIEW_FAILED is a local read-only failure, not an apply
outcome. Its fixed code survives until the entire frozen remaining batch verifies.
"""
import argparse
from contextlib import contextmanager
import fcntl
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import warnings

UUID_RE = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
KEY_RE = r"[A-Za-z0-9_-]{1,128}"
MAX_BYTES = 4 * 1024 * 1024
PREFLIGHT_CODES = frozenset(("PREVIEW_UNAVAILABLE", "PREVIEW_NOT_READY", "PREVIEW_MALFORMED",
                             "PREVIEW_IDENTITY_MISMATCH", "PREVIEW_ACTOR_MISMATCH", "PREVIEW_COUNTS_MISMATCH"))


class ProtocolError(Exception):
    """Only fixed safe codes are ever emitted by the CLI."""


class ApiFailure(Exception):
    pass


class PreflightFailure(ProtocolError):
    def __init__(self, source, code):
        self.disposition = {"sourceCampaignId": source, "state": "PREVIEW_FAILED", "failureCode": code}
        super().__init__(code)


def require(condition, code="INVALID_PROTOCOL"):
    if not condition:
        raise ProtocolError(code)


def exact(value, fields):
    require(type(value) is dict and set(value) == set(fields), "INVALID_SCHEMA")


def identifier(value):
    require(type(value) is str and re.fullmatch(UUID_RE, value) is not None, "INVALID_ID")


def integer(value):
    require(type(value) is int and 0 <= value <= 2**53 - 1, "INVALID_VERSION")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def decode(data):
    require(len(data) <= MAX_BYTES, "FILE_TOO_LARGE")
    try:
        return json.loads(data, object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        raise ProtocolError("INVALID_JSON") from None


def safe_origin(base):
    require(type(base) is str and base.isascii() and not any(ord(c) <= 32 for c in base), "UNSAFE_ORIGIN")
    parsed = urllib.parse.urlsplit(base)
    require(parsed.scheme in ("https", "http") and parsed.hostname and not parsed.username and not parsed.password
            and parsed.path in ("", "/") and not parsed.query and not parsed.fragment, "UNSAFE_ORIGIN")
    require(parsed.scheme == "https" or parsed.hostname in ("127.0.0.1", "::1"), "UNSAFE_ORIGIN")
    try:
        require(parsed.port is None or 1 <= parsed.port <= 65535, "UNSAFE_ORIGIN")
    except ValueError:
        raise ProtocolError("UNSAFE_ORIGIN") from None
    return base.rstrip("/")


def validate_manifest(data):
    exact(data, ("version", "baseUrl", "actorId", "targetTemplateId", "targetTemplateVersionId",
                 "expectedTemplateLockVersion", "partialSourcesTotal", "sources"))
    require(type(data["version"]) is int and data["version"] == 1)
    safe_origin(data["baseUrl"])
    for field in ("actorId", "targetTemplateId", "targetTemplateVersionId"):
        identifier(data[field])
    integer(data["expectedTemplateLockVersion"])
    require(type(data["partialSourcesTotal"]) is int and data["partialSourcesTotal"] == 137, "PARTIAL_TOTAL_MISMATCH")
    require(type(data["sources"]) is list and 1 <= len(data["sources"]) <= 1000)
    seen_sources, seen_ids, partial = set(), set(), 0
    for source in data["sources"]:
        exact(source, ("sourceCampaignId", "expectedLockVersion", "recipientIds", "expectedMembershipDigest", "partialSource"))
        identifier(source["sourceCampaignId"])
        require(source["sourceCampaignId"] not in seen_sources, "DUPLICATE_SOURCE")
        seen_sources.add(source["sourceCampaignId"])
        integer(source["expectedLockVersion"])
        require(type(source["expectedMembershipDigest"]) is str and re.fullmatch(r"[0-9a-f]{64}", source["expectedMembershipDigest"]) is not None)
        require(type(source["partialSource"]) is bool)
        ids = source["recipientIds"]
        require(type(ids) is list and len(ids) <= 120)
        for item in ids:
            identifier(item)
            require(item not in seen_ids, "DUPLICATE_RECIPIENT")
            seen_ids.add(item)
        if source["partialSource"]:
            partial += len(ids)
    require(partial == 137, "PARTIAL_TOTAL_MISMATCH")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ApiFailure("REDIRECT_REJECTED")


class MigrationApi:
    def __init__(self, base, token):
        self.base = safe_origin(base)
        require(type(token) is str and token and not any(ord(c) < 32 for c in token), "INVALID_TOKEN")
        self._token = token
        # No ambient proxy settings or cross-origin authorization forwarding.
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, payload=None):
        prefix = r"/api/v1/campaigns/" + UUID_RE + r"/template-migrations"
        allowed = ((method == "GET" and path == "/api/v1/auth/me" and payload is None)
                   or (method == "GET" and re.fullmatch(prefix + r"/by-key\?key=" + KEY_RE, path) and payload is None)
                   or (method == "POST" and re.fullmatch(prefix + r"(?:/preview)?", path) and type(payload) is dict))
        require(allowed, "API_PATH_REJECTED")
        request = urllib.request.Request(self.base + path, data=None if payload is None else canonical(payload),
                                         headers={"Authorization": "Bearer " + self._token,
                                                  "Content-Type": "application/json", "Accept": "application/json"}, method=method)
        try:
            with self._opener.open(request, timeout=30) as response:
                if response.status != 200:
                    raise ApiFailure("HTTP_STATUS_REJECTED")
                require(response.geturl() == self.base + path, "RESPONSE_ORIGIN_MISMATCH")
                return decode(response.read(MAX_BYTES + 1))
        except urllib.error.HTTPError as error:
            # Never read or print error bodies, headers, URLs, credentials or profile.
            raise ApiFailure("HTTP_" + str(error.code)) from None
        except (urllib.error.URLError, OSError, TimeoutError):
            raise ApiFailure("RESPONSE_UNKNOWN") from None


def private_parent(path):
    parent = path.parent
    info = parent.stat()
    require(parent.resolve() == parent.absolute() and stat.S_ISDIR(info.st_mode)
            and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) & 0o077 == 0, "PRIVATE_DIRECTORY_REQUIRED")


def private_read(path):
    private_parent(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) & 0o077 == 0,
                "PRIVATE_FILE_REQUIRED")
        return decode(stream.read(MAX_BYTES + 1))


def save(path, data, existing=True):
    private_parent(path)
    require(not path.is_symlink(), "JOURNAL_SYMLINK_REJECTED")
    if existing:
        private_read(path)  # Never reconstruct a journal lost during network I/O.
    fd, temporary = tempfile.mkstemp(prefix=".migration-journal-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(canonical(data) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        if existing:
            private_read(path)
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextmanager
def journal_lock(path):
    private_parent(path)
    require(not path.is_symlink(), "JOURNAL_SYMLINK_REJECTED")
    fd = os.open(str(path) + ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) & 0o077 == 0,
                "PRIVATE_FILE_REQUIRED")
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(fd)


def command(data, source, key):
    return {"expectedLockVersion": source["expectedLockVersion"], "targetTemplateId": data["targetTemplateId"],
            "targetTemplateVersionId": data["targetTemplateVersionId"],
            "expectedTemplateLockVersion": data["expectedTemplateLockVersion"],
            "recipientIds": sorted(source["recipientIds"]), "expectedMembershipDigest": source["expectedMembershipDigest"],
            "idempotencyKey": key}


def prefix(source):
    return "/api/v1/campaigns/" + source + "/template-migrations"


def request_digest(actor, source, payload):
    return digest({"actorId": actor, "sourceCampaignId": source, "command": payload})


def result_matches(result, data, item):
    exact(result, ("id", "sourceCampaignId", "targetCampaignId", "targetTemplateId", "targetTemplateVersionId",
                   "selectedCount", "migratedCount", "status", "reasons"))
    identifier(result["id"])
    require(result["sourceCampaignId"] == item["sourceCampaignId"]
            and result["targetTemplateId"] == data["targetTemplateId"]
            and result["targetTemplateVersionId"] == data["targetTemplateVersionId"])
    count = len(item["command"]["recipientIds"])
    integer(result["selectedCount"])
    integer(result["migratedCount"])
    require(result["selectedCount"] == count)
    require(type(result["reasons"]) is list and len(result["reasons"]) <= 120)
    for reason in result["reasons"]:
        exact(reason, ("code", "count"))
        require(type(reason["code"]) is str and re.fullmatch(r"[A-Z_]{1,80}", reason["code"]))
        integer(reason["count"])
    if result["status"] == "MIGRATED":
        identifier(result["targetCampaignId"])
        require(count > 0 and result["migratedCount"] == count and result["targetCampaignId"] not in
                {source["sourceCampaignId"] for source in data["sources"]} and not result["reasons"])
    else:
        require(result["status"] in ("CONFIG_UPDATED", "NO_MAIL", "BLOCKED")
                and result["targetCampaignId"] is None and result["migratedCount"] == 0)
        if result["status"] in ("CONFIG_UPDATED", "NO_MAIL"):
            require(count == 0)


def load_journal(data, path, create=True):
    # Existing-only reads never fall back to creation, even if the file disappears.
    if not create or path.exists():
        journal = private_read(path)
        exact(journal, ("version", "manifestDigest", "actorId", "items"))
        require(journal["version"] == 1 and journal["manifestDigest"] == digest(data)
                and journal["actorId"] == data["actorId"], "JOURNAL_IDENTITY_MISMATCH")
        require(type(journal["items"]) is list and len(journal["items"]) == len(data["sources"]))
        keys, targets = set(), set()
        for item, source in zip(journal["items"], data["sources"]):
            exact(item, ("sourceCampaignId", "command", "requestDigest", "readbackPath", "state", "failureCode", "observedResult", "result"))
            key = item["command"].get("idempotencyKey") if type(item["command"]) is dict else None
            require(type(key) is str and re.fullmatch(KEY_RE, key) and key not in keys)
            keys.add(key)
            require(item["sourceCampaignId"] == source["sourceCampaignId"]
                    and item["command"] == command(data, source, key)
                    and item["requestDigest"] == request_digest(data["actorId"], source["sourceCampaignId"], item["command"])
                    and item["readbackPath"] == prefix(source["sourceCampaignId"]) + "/by-key?key=" + key,
                    "JOURNAL_IDENTITY_MISMATCH")
            require(item["state"] in ("NEW", "PREVIEWED", "PREVIEW_FAILED", "PENDING", "COMPLETE", "BLOCKED"))
            require(item["failureCode"] is None or type(item["failureCode"]) is str)
            require(item["failureCode"] in (None, "RESPONSE_UNKNOWN", "READBACK_UNAVAILABLE", "READBACK_MISMATCH", "RESULT_BLOCKED")
                    or item["failureCode"] in PREFLIGHT_CODES)
            require((item["state"] == "PREVIEW_FAILED") == (item["failureCode"] in PREFLIGHT_CODES))
            if item["observedResult"] is not None:
                result_matches(item["observedResult"], data, item)
                require(item["state"] in ("PENDING", "COMPLETE", "BLOCKED"))
            if item["result"] is not None:
                result_matches(item["result"], data, item)
                require(item["observedResult"] is None or item["result"] == item["observedResult"], "JOURNAL_RESULT_MISMATCH")
                target = item["result"]["targetCampaignId"]
                require(target is None or target not in targets, "DUPLICATE_TARGET")
                targets.add(target)
            require((item["state"] in ("COMPLETE", "BLOCKED")) == (item["result"] is not None))
            if item["result"] is not None:
                require((item["state"] == "BLOCKED") == (item["result"]["status"] == "BLOCKED"), "JOURNAL_STATE_MISMATCH")
        return journal
    journal = {"version": 1, "manifestDigest": digest(data), "actorId": data["actorId"], "items": []}
    for source in data["sources"]:
        payload = command(data, source, "migration_" + uuid.uuid4().hex)
        journal["items"].append({"sourceCampaignId": source["sourceCampaignId"], "command": payload,
                                 "requestDigest": request_digest(data["actorId"], source["sourceCampaignId"], payload),
                                 "readbackPath": prefix(source["sourceCampaignId"]) + "/by-key?key=" + payload["idempotencyKey"],
                                 "state": "NEW", "failureCode": None, "observedResult": None, "result": None})
    save(path, journal, existing=False)
    return journal


def actor(api, expected):
    profile = api.request("GET", "/api/v1/auth/me")
    require(type(profile) is dict and profile.get("id") == expected, "ACTOR_MISMATCH")


def readback(api, data, journal, item, path):
    try:
        actor(api, data["actorId"])
        result = api.request("GET", item["readbackPath"])
        result_matches(result, data, item)
        require(item["observedResult"] is None or result == item["observedResult"])
        require(not any(other is not item and other["result"] and result["targetCampaignId"] is not None
                        and other["result"]["targetCampaignId"] == result["targetCampaignId"] for other in journal["items"]))
    except ApiFailure:
        item["failureCode"] = "READBACK_UNAVAILABLE"
    except ProtocolError:
        item["failureCode"] = "READBACK_MISMATCH"
    else:
        item["result"] = result
        item["state"] = "BLOCKED" if result["status"] == "BLOCKED" else "COMPLETE"
        item["failureCode"] = "RESULT_BLOCKED" if item["state"] == "BLOCKED" else None
    save(path, journal)


def coordinate(data, journal_path, api, mode="preview"):
    validate_manifest(data)
    require(mode in ("preview", "apply", "reconcile"))
    path = Path(journal_path).absolute()
    with journal_lock(path):
        journal = load_journal(data, path, create=mode != "reconcile")
        if mode == "reconcile" or all(item["state"] == "COMPLETE" for item in journal["items"]):
            actor(api, data["actorId"])
        if mode in ("apply", "reconcile"):
            for item in journal["items"]:
                if item["state"] == "PENDING":
                    readback(api, data, journal, item, path)
        if mode != "reconcile" and not any(item["state"] in ("PENDING", "BLOCKED") for item in journal["items"]):
            remaining = [item for item in journal["items"] if item["state"] != "COMPLETE"]
            # Invalidate old previews before I/O; retain prior failure codes until
            # ALL remaining frozen items verify. No stale preview is current proof.
            for item in remaining:
                if item["state"] == "PREVIEWED":
                    item["state"] = "NEW"
            if remaining:
                save(path, journal)
            # The entire remaining batch is verified before any apply; never replace the frozen digest.
            for item in remaining:
                try:
                    verify_preview(api, data, item)
                except (ApiFailure, ProtocolError) as error:
                    fail_preflight(error, journal, item, path)
            for item in remaining:
                item["state"] = "PREVIEWED"
                item["failureCode"] = None
            if remaining:
                save(path, journal)
            if mode == "apply":
                for item in remaining:
                    try:
                        actor(api, data["actorId"])
                    except (ApiFailure, ProtocolError) as error:
                        fail_preflight(error, journal, item, path)
                    item["state"] = "PENDING"
                    save(path, journal)  # fsync before network; crash recovery is original-key GET only.
                    try:
                        observed = api.request("POST", prefix(item["sourceCampaignId"]), item["command"])
                        result_matches(observed, data, item)
                        item["observedResult"] = observed
                        save(path, journal)
                    except (ApiFailure, ProtocolError):
                        item["failureCode"] = "RESPONSE_UNKNOWN"
                        save(path, journal)
                    readback(api, data, journal, item, path)
                    if item["state"] != "COMPLETE":
                        break
        return {"complete": sum(i["state"] == "COMPLETE" for i in journal["items"]),
                "previewed": sum(i["state"] == "PREVIEWED" for i in journal["items"]),
                "pending": sum(i["state"] == "PENDING" for i in journal["items"]),
                "blocked": sum(i["state"] == "BLOCKED" for i in journal["items"]),
                "unfinished": sum(i["state"] in ("NEW", "PREVIEWED", "PREVIEW_FAILED") for i in journal["items"]),
                "dispositions": [{key: item[key] for key in ("sourceCampaignId", "state", "failureCode")}
                                 for item in journal["items"] if item["state"] != "COMPLETE"]}


def verify_preview(api, data, item):
    actor(api, data["actorId"])
    payload = item["command"]
    preview = api.request("POST", prefix(item["sourceCampaignId"]) + "/preview",
                          {key: payload[key] for key in ("targetTemplateId", "targetTemplateVersionId", "recipientIds")})
    exact(preview, ("sourceCampaignId", "expectedLockVersion", "targetTemplateId", "targetTemplateVersionId",
                    "expectedTemplateLockVersion", "expectedMembershipDigest", "recipientIds", "counts", "ready", "reasons"))
    require(preview["sourceCampaignId"] == item["sourceCampaignId"], "PREVIEW_IDENTITY_MISMATCH")
    require(type(preview["ready"]) is bool and type(preview["reasons"]) is list, "PREVIEW_MALFORMED")
    require(preview["ready"] is True and preview["reasons"] == [], "PREVIEW_NOT_READY")
    for key in ("expectedLockVersion", "targetTemplateId", "targetTemplateVersionId", "expectedTemplateLockVersion", "expectedMembershipDigest", "recipientIds"):
        require(type(preview[key]) is type(payload[key]) and preview[key] == payload[key], "PREVIEW_IDENTITY_MISMATCH")
    exact(preview["counts"], ("historicalSelected", "actionableQueued", "transferred", "migrationBlocked"))
    for value in preview["counts"].values():
        integer(value)
    require(preview["counts"] == {"historicalSelected": len(payload["recipientIds"]), "actionableQueued": len(payload["recipientIds"]), "transferred": 0, "migrationBlocked": 0}, "PREVIEW_COUNTS_MISMATCH")


def fail_preflight(error, journal, item, path):
    # Never persist arbitrary exception text, API reason/body, or HTTP codes.
    code = "PREVIEW_UNAVAILABLE" if isinstance(error, ApiFailure) else {
        "ACTOR_MISMATCH": "PREVIEW_ACTOR_MISMATCH", "PREVIEW_NOT_READY": "PREVIEW_NOT_READY",
        "PREVIEW_IDENTITY_MISMATCH": "PREVIEW_IDENTITY_MISMATCH",
        "PREVIEW_COUNTS_MISMATCH": "PREVIEW_COUNTS_MISMATCH",
    }.get(str(error), "PREVIEW_MALFORMED")
    for other in journal["items"]:
        if other["state"] == "PREVIEWED":
            other["state"] = "NEW"
    item["state"], item["failureCode"] = "PREVIEW_FAILED", code
    save(path, journal)
    raise PreflightFailure(item["sourceCampaignId"], code) from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--journal", required=True)
    parser.add_argument("--mode", choices=("preview", "apply", "reconcile"), default="preview")
    args = parser.parse_args(argv)
    try:
        manifest_path, journal_path = Path(args.manifest).absolute(), Path(args.journal).absolute()
        require(manifest_path != journal_path, "DISTINCT_PRIVATE_FILES_REQUIRED")
        data = private_read(manifest_path)
        validate_manifest(data)
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            token = getpass.getpass("Migration API token (process only): ")
        summary = coordinate(data, journal_path, MigrationApi(data["baseUrl"], token), args.mode)
        print(json.dumps(summary, sort_keys=True))
        return 2 if summary["pending"] or summary["blocked"] or (args.mode != "preview" and summary["unfinished"]) else 0
    except PreflightFailure as error:
        print(json.dumps(error.disposition, sort_keys=True), file=sys.stderr)
        return 2
    except (ProtocolError, ApiFailure, OSError, ValueError, EOFError, getpass.GetPassWarning):
        print("Migration stopped; original private journal retained. No automatic retry.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
