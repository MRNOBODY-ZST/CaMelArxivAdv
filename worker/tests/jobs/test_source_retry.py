"""Literal v2 contracts: coercion, missing defaults, and unsafe data must fail closed."""

import asyncio
import importlib
import json
import traceback
from pathlib import Path
from typing import Any, cast
from uuid import uuid4

import pytest
from test_source_admission import connect_owned
from test_source_admission import isolated_redis_url as owned_redis_fixture

from app.jobs.job_control import AsyncRedisStore, RedisJobStore

isolated_redis_url = owned_redis_fixture

FIXTURE = Path(__file__).resolve().parents[3] / "contracts/fixtures/source-admission-state-v2.json"
VECTORS = json.loads(FIXTURE.read_text())


def implementation() -> Any:
    from app.jobs import job_control

    assert hasattr(job_control.RedisJobStore, "load_or_bind_source")
    return importlib.import_module("app.jobs.source_retry")


@pytest.mark.parametrize("vector", VECTORS["states"])
def test_literal_state_bytes_and_digest(vector: dict[str, str]) -> None:
    state = implementation().SourceState.from_json(vector["raw"])
    assert state.to_json() == vector["raw"]
    assert state.digest == vector["sha256"]


@pytest.mark.parametrize("vector", VECTORS["evidence_casefold_states"] + VECTORS["contact_states"])
def test_literal_privacy_contract(vector: dict[str, Any]) -> None:
    module = implementation()
    if vector["accepted"]:
        assert module.SourceState.from_json(vector["raw"]).digest == vector["sha256"]
    else:
        with pytest.raises(module.SourceStateError, match=r"^SOURCE_STATE_INVALID$"):
            module.SourceState.from_json(vector["raw"])


@pytest.mark.parametrize("vector", VECTORS["invalid_states"])
def test_literal_invalid_states(vector: dict[str, str]) -> None:
    module = implementation()
    with pytest.raises(module.SourceStateError, match=r"^SOURCE_STATE_INVALID$"):
        module.SourceState.from_json(vector["raw"])


@pytest.mark.parametrize("replacement", [True, 1.0, -1, 2**53, "1", None])
def test_generation_never_coerces(replacement: object) -> None:
    module = implementation()
    tree = json.loads(VECTORS["states"][0]["raw"])
    tree["generation"] = replacement
    with pytest.raises(module.SourceStateError):
        module.SourceState.from_json(json.dumps(tree, sort_keys=True, separators=(",", ":")))


async def test_stored_reserved_or_restored_owner_cannot_grant(isolated_redis_url: str) -> None:
    module = implementation()
    redis = await connect_owned(isolated_redis_url)
    try:
        state = module.SourceState.from_json(VECTORS["states"][2]["raw"])
        await redis.set(state.scope.state_key, state.to_json())
        store = RedisJobStore(cast(AsyncRedisStore, redis))
        assert not await store.take_source_invocation(state.scope, state)
        assert not await store.take_source_invocation(
            state.scope, module.SourceState.from_json(state.to_json())
        )
        assert await redis.get(state.scope.state_key) == state.to_json().encode()
    finally:
        await redis.aclose()


async def test_retirement_preserves_prefix_and_survives_generic_marker_expiry(
    isolated_redis_url: str,
) -> None:
    module = implementation()
    redis = await connect_owned(isolated_redis_url)
    try:
        tree = json.loads(VECTORS["states"][0]["raw"])
        tree.update(phase="TERMINAL_PENDING", generation=1, terminal_status="CANCELED")
        state = module.SourceState.from_json(
            json.dumps(tree, sort_keys=True, separators=(",", ":"))
        )
        await redis.set(state.scope.state_key, state.to_json())
        store = RedisJobStore(cast(AsyncRedisStore, redis))
        assert await store.get_source_retirement(state.scope) == state
        terminal = await store.retire_source_terminal(state.scope, 1)
        assert terminal.phase == "TERMINAL" and terminal.progress.next_index == 0
        assert terminal.authority == state.authority
        assert await redis.ttl(state.scope.state_key) == -1
        assert not await store.is_processed(state.scope.idempotency_key)
        assert (
            await store.retire_source_terminal(state.scope, terminal.generation)
        ).phase == "TERMINAL"
    finally:
        await redis.aclose()


@pytest.mark.parametrize(
    "field,value",
    [
        ("job_id", "99999999-9999-9999-9999-999999999999"),
        ("command_message_id", "99999999-9999-9999-9999-999999999999"),
        ("command_type", "ARXIV_REEXTRACT_CONTACTS"),
        ("payload_fingerprint", "b" * 64),
    ],
)
async def test_conflicting_scope_never_mutates_original(
    isolated_redis_url: str,
    field: str,
    value: str,
) -> None:
    module = implementation()
    from app.jobs.source_admission import SourceAdmissionDenied

    redis = await connect_owned(isolated_redis_url)
    try:
        state = module.SourceState.from_json(VECTORS["states"][0]["raw"])
        await redis.set(state.scope.state_key, state.to_json())
        different = state.scope.to_dict()
        different[field] = value
        incoming = module.SourceScope.from_dict(different)
        store = RedisJobStore(cast(AsyncRedisStore, redis))
        with pytest.raises(SourceAdmissionDenied) as denied:
            await store.hold_source(incoming, 0, module.SourceHoldReason.SCOPE_CONFLICT)
        assert denied.value.code == "SCOPE_CONFLICT"
        assert await redis.get(state.scope.state_key) == state.to_json().encode()
    finally:
        await redis.aclose()


async def test_real_lua_same_generation_only_one_writer(isolated_redis_url: str) -> None:
    module = implementation()
    from app.jobs.source_admission import SourceAdmissionDenied

    redis = await connect_owned(isolated_redis_url)
    try:
        state = module.SourceState.from_json(VECTORS["states"][0]["raw"])
        await redis.set(state.scope.state_key, state.to_json())
        stores = [RedisJobStore(cast(AsyncRedisStore, redis)) for _ in range(16)]
        results = await asyncio.gather(
            *[
                store.hold_source(state.scope, 0, module.SourceHoldReason.INVOCATION_UNCERTAIN)
                for store in stores
            ],
            return_exceptions=True,
        )
        assert sum(isinstance(item, module.SourceState) for item in results) == 1
        assert sum(isinstance(item, SourceAdmissionDenied) for item in results) == 15
        raw = await redis.get(state.scope.state_key)
        held = module.SourceState.from_json(raw)
        assert held.generation == 1 and held.authority == state.authority
        assert held.progress.next_index == 0
        assert await redis.ttl(state.scope.state_key) == -1
    finally:
        await redis.aclose()


@pytest.mark.parametrize("change", ["missing", "float", "bool", "uppercase", "surrogate", "size"])
def test_strict_result_shape_and_diagnostics_do_not_leak(change: str) -> None:
    module = implementation()
    tree = json.loads(VECTORS["states"][4]["raw"])
    result = tree["result"]
    marker = "PRIVATE_SYNTHETIC_MARKER"
    if change == "missing":
        result.pop("error_summary")
    elif change == "float":
        result["duration_ms"] = 1.0
    elif change == "bool":
        result["duration_ms"] = True
    elif change == "uppercase":
        result["paper_id"] = "ABCDEFAB-1234-1234-1234-ABCDEFABCDEF"
    elif change == "surrogate":
        result["document_class"] = "\ud800"
    else:
        result["document_class"] = marker * 100000
    raw = json.dumps(tree, sort_keys=True, separators=(",", ":"))
    with pytest.raises(module.SourceStateError) as caught:
        module.SourceState.from_json(raw)
    assert caught.value.__context__ is None and caught.value.__cause__ is None
    assert marker not in "".join(traceback.format_exception(caught.value))


def test_scope_binds_order_parser_and_validated_payload_without_headers() -> None:
    module = implementation()
    from app.messaging.contracts import MessageType

    job, message = uuid4(), uuid4()
    first = {"paperId": "77777777-7777-7777-7777-777777777777", "arxivId": "2608.00001"}
    second = {"paperId": "88888888-8888-8888-8888-888888888888", "arxivId": "2608.00002"}
    payload = {"parserVersion": "0.1.0", "targets": [first, second]}

    def scope(value: object) -> Any:
        return module.SourceScope.from_command(
            "synthetic:scope", job, message, MessageType.ARXIV_FETCH_AND_PARSE_SOURCE, value
        )

    bound = scope(payload)
    assert bound != scope({**payload, "targets": [second, first]})
    assert bound != scope({**payload, "parserVersion": "0.2.0"})
    for invalid in (
        {**payload, "headers": {}},
        {**payload, "targets": [1]},
        {**payload, "parserVersion": 1},
    ):
        with pytest.raises(module.SourceStateError):
            scope(invalid)


@pytest.mark.parametrize(
    "raw", [b"\xff", b"[]", b"{} {}", b"NaN", b'{"version":2,"x":"\xed\xa0\x80"}']
)
def test_invalid_raw_bytes_are_safe(raw: bytes) -> None:
    module = implementation()
    with pytest.raises(module.SourceStateError) as caught:
        module.SourceState.from_json(raw)
    assert str(caught.value) == "SOURCE_STATE_INVALID"
    assert caught.value.__context__ is None


def test_mutated_result_serialization_has_fixed_safe_error() -> None:
    module = implementation()
    state = module.SourceState.from_json(VECTORS["states"][4]["raw"])
    state.result.source_format = "PRIVATE_SYNTHETIC_MARKER\ud800"
    with pytest.raises(module.SourceStateError) as caught:
        state.to_json()
    assert caught.value.__context__ is None
    assert "PRIVATE_SYNTHETIC_MARKER" not in str(caught.value)


async def test_redis_transport_failure_stays_unavailable_not_corrupt_hold(
    isolated_redis_url: str,
) -> None:
    from redis.exceptions import ConnectionError as RedisConnectionError
    from test_source_admission import DelayedRedis

    from app.jobs.source_admission import SourceAdmissionDenied

    class LostRead(DelayedRedis):
        async def get(self, key: str) -> bytes | str | None:
            raise RedisConnectionError("PRIVATE_SYNTHETIC_MARKER")

    module = implementation()
    redis = await connect_owned(isolated_redis_url)
    try:
        store = RedisJobStore(LostRead(redis))
        scope = module.SourceState.from_json(VECTORS["states"][0]["raw"]).scope
        with pytest.raises(SourceAdmissionDenied) as caught:
            await store.get_source_retirement(scope)
        assert caught.value.status == "UNAVAILABLE"
        assert caught.value.code == "COORDINATION_FAILED"
        assert caught.value.__context__ is None
        assert "PRIVATE_SYNTHETIC_MARKER" not in "".join(traceback.format_exception(caught.value))
    finally:
        await redis.aclose()


async def test_clock_loss_closes_runtime_reads_even_after_wall_recovery(
    isolated_redis_url: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from datetime import timedelta

    from app.jobs import source_admission

    module = implementation()
    redis = await connect_owned(isolated_redis_url)
    try:
        store = RedisJobStore(cast(AsyncRedisStore, redis))
        scope = module.SourceState.from_json(VECTORS["states"][0]["raw"]).scope
        real_now = source_admission.now_millis
        with monkeypatch.context() as patch:
            patch.setattr(source_admission, "now_millis", lambda: real_now() - timedelta(seconds=5))
            with pytest.raises(source_admission.SourceAdmissionDenied):
                await store.load_or_bind_source(scope)
        with pytest.raises(source_admission.SourceAdmissionDenied):
            await store.load_or_bind_source(scope)
        assert not await redis.keys("camel:worker:source-admission:*")
    finally:
        await redis.aclose()
