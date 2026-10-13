"""Mailbox codec vectors; live PG/Redis bridge tests are explicitly selected by Java."""

import asyncio
import importlib
import json
import os
import subprocess
import sys
import time
from collections.abc import Awaitable, Callable, Iterator
from datetime import timedelta
from pathlib import Path
from typing import Any, cast
from uuid import UUID, uuid4

import pytest
from redis.asyncio import Redis
from redis.exceptions import ConnectionError as RedisConnectionError

from app.arxiv.source_errors import SourceUpstreamReason
from app.jobs.job_control import AsyncRedisStore, RedisJobStore
from app.jobs.source_admission import SourceAdmissionDenied, now_millis
from app.jobs.source_retry import SourceHoldReason, SourcePhase, SourceScope, SourceState
from app.messaging.contracts import MessageType, SourceExtractionResult

FIXTURE = Path(__file__).resolve().parents[3] / "contracts/fixtures/source-admission-state-v2.json"
VECTORS = json.loads(FIXTURE.read_text())


@pytest.fixture(scope="module")
def isolated_redis_url() -> Iterator[str]:
    """Own only this randomly named, ephemeral synthetic container; never default Redis."""
    name = "camel-r2-python-" + uuid4().hex
    subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "-d",
            "--name",
            name,
            "-p",
            "127.0.0.1::6379",
            "redis:8.0.2-alpine",
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=60,
    )
    try:
        port = (
            subprocess.run(
                ["docker", "port", name, "6379/tcp"],
                check=True,
                capture_output=True,
                text=True,
                timeout=10,
            )
            .stdout.strip()
            .split(":")[-1]
        )
        yield f"redis://127.0.0.1:{int(port)}/0"
    finally:
        subprocess.run(
            ["docker", "stop", "--time", "1", name], check=True, capture_output=True, timeout=10
        )


async def connect_owned(url: str) -> Redis:
    redis = Redis.from_url(url)
    end = time.monotonic() + 10
    ready = False
    try:
        while time.monotonic() < end:
            try:
                if await redis.ping():
                    ready = True
                    return cast(Redis, redis)
            except RedisConnectionError:
                pass
            await asyncio.sleep(0.025)
        raise AssertionError("SYNTHETIC_REDIS_NOT_READY")
    finally:
        if not ready:
            await redis.aclose()


@pytest.mark.parametrize("outcome", ["ready", "timeout", "unexpected"])
async def test_owned_redis_readiness_retries_library_error_and_closes_failure(
    monkeypatch: pytest.MonkeyPatch, outcome: str,
) -> None:
    from types import SimpleNamespace

    class Client:
        attempts = 0
        closed = False

        async def ping(self) -> bool:
            self.attempts += 1
            if outcome == "unexpected":
                raise ValueError("synthetic readiness failure")
            if self.attempts == 1:
                raise RedisConnectionError("synthetic starting server")
            return True

        async def aclose(self) -> None:
            self.closed = True

    client = Client()
    ticks = iter([0.0, 0.0, 11.0] if outcome == "timeout" else [0.0, 0.0, 0.0])
    monkeypatch.setattr(
        sys.modules[__name__], "time", SimpleNamespace(monotonic=lambda: next(ticks))
    )
    monkeypatch.setattr(Redis, "from_url", lambda *_args, **_kwargs: client)
    if outcome == "ready":
        assert await connect_owned("redis://synthetic.invalid") is cast(Redis, client)
        assert client.attempts == 2 and not client.closed
    else:
        with pytest.raises(AssertionError if outcome == "timeout" else ValueError):
            await connect_owned("redis://synthetic.invalid")
        assert client.closed


async def test_reconstructed_and_canceled_request_cannot_reopen(isolated_redis_url: str) -> None:
    module = implementation()
    from app.jobs.source_retry import SourceState

    redis = await connect_owned(isolated_redis_url)
    try:
        client = module.SourceAdmissionClient(redis)
        scope = SourceState.from_json(VECTORS["states"][0]["raw"]).scope
        original = client.prepare(scope)
        reconstructed = module.AdmissionRequest.from_json(original.to_json())
        assert (await client.request(reconstructed)).status == "HELD"
        assert (await client.request(original)).status == "HELD"
        pending = client.prepare(scope)
        task = asyncio.create_task(client.request(pending))
        while await redis.get(client.slot_key(pending.nonce)) is None:
            await asyncio.sleep(0.005)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert await redis.get(client.slot_key(pending.nonce)) is None
        assert (await client.request(pending)).status == "HELD"
        assert (await client.request(pending.readback())).status == "HELD"
    finally:
        await redis.aclose()


async def test_cached_reply_is_never_an_original_permission(isolated_redis_url: str) -> None:
    module = implementation()
    from app.jobs.source_retry import SourceState

    redis = await connect_owned(isolated_redis_url)
    try:
        client = module.SourceAdmissionClient(redis)
        request = client.prepare(SourceState.from_json(VECTORS["states"][0]["raw"]).scope)
        await redis.set(client.reply_key(request.nonce), VECTORS["replies"][2]["raw"])
        assert (await client.request(request)).status == "HELD"
        assert await redis.get(client.slot_key(request.nonce)) is None
    finally:
        await redis.aclose()


@pytest.mark.parametrize("expiry", ["wall", "monotonic", "both", "live", "backward"])
async def test_abandoned_original_receipts_expire_without_reconstructing_or_pruning_active(
    isolated_redis_url: str, monkeypatch: pytest.MonkeyPatch, expiry: str,
) -> None:
    from dataclasses import replace
    from types import SimpleNamespace

    from app.jobs import source_admission as module
    from app.jobs.source_retry import SourceAuthorityReceipt, SourceInvocation

    wall = [now_millis()]
    monotonic = [100.0]
    monkeypatch.setattr(module, "now_millis", lambda: wall[0])
    monkeypatch.setattr(module, "time", SimpleNamespace(monotonic=lambda: monotonic[0]))
    redis = await connect_owned(isolated_redis_url)
    try:
        store = module.SourceStateStore(cast(AsyncRedisStore, redis))
        ready = SourceState.from_json(VECTORS["states"][0]["raw"])

        async def synthetic_backend(request: module.AdmissionRequest) -> module.AdmissionReply:
            assert request.token is not None and request.deadline is not None
            confirmed = replace(
                ready, scope=request.scope, generation=2, phase=SourcePhase.RESERVED,
                invocation=SourceInvocation(
                    request.token, 0, 1, request.owner_id, request.deadline
                ),
                authority=SourceAuthorityReceipt(
                    2, request.nonce, request.owner_id, request.token, 1, 1
                ),
            )
            await redis.set(request.scope.state_key, confirmed.to_json())
            return module.AdmissionReply(
                "RESERVED", "OK", confirmed.digest, confirmed.authority, request.deadline
            )

        monkeypatch.setattr(store.admission, "request", synthetic_backend)

        async def reserve_new() -> SourceState:
            scope = replace(
                ready.scope, idempotency_key="synthetic:abandoned:" + uuid4().hex, job_id=uuid4()
            )
            await redis.set(scope.state_key, replace(ready, scope=scope).to_json())
            return await store.reserve(
                scope, 0, 0, store.admission.owner_id, wall[0] + timedelta(seconds=120)
            )

        first = await reserve_new()
        assert first.invocation is not None
        original_bytes = await redis.get(first.scope.state_key)
        if expiry == "backward":
            wall[0] -= timedelta(seconds=1)
            assert not await store.take(first.scope, first)
            wall[0] += timedelta(seconds=2)
            assert not await store.take(first.scope, first)
            return
        if expiry in {"wall", "both"}:
            wall[0] += timedelta(seconds=11)
        if expiry in {"monotonic", "both"}:
            monotonic[0] += 11
        second = await reserve_new()
        assert len(store._grants) == (2 if expiry == "live" else 1)
        assert await redis.get(first.scope.state_key) == original_bytes
        assert not await store.take(second.scope, SourceState.from_json(second.to_json()))
        assert await store.take(second.scope, second)
        assert not await store.take(second.scope, second)
        assert await store.take(first.scope, first) is (expiry == "live")
        assert second.invocation is not None
        active_token = second.invocation.token
        wall[0] += timedelta(seconds=11)
        monotonic[0] += 11
        third = await reserve_new()
        assert active_token in store._active
        assert await store.take(third.scope, third)
    finally:
        await redis.aclose()


def implementation() -> Any:
    from app.jobs import job_control

    assert hasattr(job_control.RedisJobStore, "reserve_source_invocation")
    return importlib.import_module("app.jobs.source_admission")


@pytest.mark.parametrize("vector", VECTORS["requests"])
def test_literal_request_codec(vector: dict[str, str]) -> None:
    request = implementation().AdmissionRequest.from_json(vector["raw"])
    assert request.to_json() == vector["raw"]


@pytest.mark.parametrize("vector", VECTORS["replies"])
def test_literal_reply_codec(vector: dict[str, str]) -> None:
    reply = implementation().AdmissionReply.from_json(vector["raw"])
    assert reply.to_json() == vector["raw"]


async def bridge_step(token: str) -> None:
    print("R2:" + token, flush=True)
    answer = await asyncio.wait_for(asyncio.to_thread(sys.stdin.readline), timeout=20)
    assert answer == "GO\n", "BRIDGE_CONTROL_FAILED"


async def coordinated(work: Awaitable[Any], token: str = "POLL") -> Any:
    task = asyncio.ensure_future(work)
    try:
        await bridge_step(token)
        return await task
    finally:
        if not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)


class DelayedRedis:
    """Real transport with one test-controlled network delay; never creates a reply."""

    def __init__(self, redis: Any) -> None:
        self.redis = redis
        self.delay_next_eval = 0.0
        self.state_reads = 0
        self.on_state_read: Callable[[int], None] | None = None
        self.after_cas: Callable[[], None] | None = None
        self.sample_key: object | None = None

    async def get(self, key: str) -> bytes | str | None:
        value = await self.redis.get(key)
        if key.startswith("camel:worker:source-state:v2:"):
            self.state_reads += 1
            if self.on_state_read:
                self.on_state_read(self.state_reads)
        return cast(bytes | str | None, value)

    async def set(self, key: str, value: str, *, ex: int) -> object:
        return await self.redis.set(key, value, ex=ex)

    async def delete(self, key: str) -> object:
        return await self.redis.delete(key)

    async def eval(self, script: str, numkeys: int, *values: object) -> object:
        from app.jobs.source_admission import _SERVER_TIME, _STATE_CAS

        if script == _SERVER_TIME:
            assert numkeys == 1 and len(values) == 1
            self.sample_key = values[0]
        if script == _STATE_CAS and values[3] != 0:
            assert numkeys == 1 and values[0] == self.sample_key

        delay = self.delay_next_eval if script == _STATE_CAS else 0.0
        if delay:
            self.delay_next_eval = 0.0
        if delay:
            # Model a request already in transit: caller cancellation cannot retract
            # the actual server-side Lua execution. Its lease guard must reject it.
            async def delayed_write() -> object:
                await asyncio.sleep(delay)
                return await self.redis.eval(script, numkeys, *values)

            task = asyncio.create_task(delayed_write())
            try:
                return await asyncio.shield(task)
            except asyncio.CancelledError:
                await task
                raise
        value = await self.redis.eval(script, numkeys, *values)
        if script == _STATE_CAS and self.after_cas:
            self.after_cas()
        return value


async def live_bridge() -> None:
    # Explicit Java-owned isolated endpoint and synthetic command only, never Settings/.env/main.
    scenario = os.environ["R2_SCENARIO"]
    endpoint = os.environ["R2_REDIS"]
    assert endpoint.startswith("redis://127.0.0.1:") or endpoint.startswith("redis://localhost:")
    payload = {
        "parserVersion": "0.1.0",
        "targets": [
            {"paperId": "77777777-7777-7777-7777-777777777777", "arxivId": "2608.00001"},
            {"paperId": "88888888-8888-8888-8888-888888888888", "arxivId": "2608.00002"},
        ],
    }
    scope = SourceScope.from_command(
        os.environ["R2_KEY"],
        UUID(os.environ["R2_JOB"]),
        UUID(os.environ["R2_MESSAGE"]),
        MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
        payload,
    )
    assert scope.payload_fingerprint == os.environ["R2_FINGERPRINT"]
    redis = await connect_owned(endpoint)
    transport = DelayedRedis(redis)
    store = RedisJobStore(transport)
    clock_patch = pytest.MonkeyPatch()
    from app.jobs import source_admission as admission_module

    real_now = admission_module.now_millis
    count_key = "r2:runner:" + str(scope.job_id)
    await redis.set(count_key, "0")

    async def observe_request_completion(work: Awaitable[Any]) -> Any:
        try:
            return await work
        finally:
            await redis.set("r2:request-done:" + str(scope.job_id), "1")

    try:
        if scenario in {
            "terminal",
            "cancel_first",
            "paused",
            "unknown",
            "legacy",
            "scope_conflict",
        }:
            with pytest.raises(SourceAdmissionDenied) as denied:
                await coordinated(store.load_or_bind_source(scope), "BIND")
            assert denied.value.status == {
                "terminal": "TERMINAL",
                "cancel_first": "TERMINAL",
                "paused": "PAUSED",
            }.get(scenario, "HELD")
            assert await redis.get(count_key) == b"0"
            reference_key = scope.state_key + ":legacy-sha256"
            if scenario == "legacy":
                import hashlib

                legacy_key = "camel:worker:source-progress:" + scope.idempotency_key
                original = b"synthetic-legacy-byte-preservation"
                assert await redis.get(legacy_key) == original
                expected = hashlib.sha256(original).hexdigest().encode()
                assert await redis.get(reference_key) == expected
                assert await redis.ttl(reference_key) == -1
                await redis.set(legacy_key, b"\x00\xffchanged-synthetic")
                with pytest.raises(SourceAdmissionDenied):
                    await coordinated(store.get_source(scope))
                assert await redis.get(reference_key) == expected
                assert await redis.get(legacy_key) == b"\x00\xffchanged-synthetic"
            else:
                assert await redis.get(reference_key) is None
            await bridge_step("DONE")
            return
        ready = await coordinated(store.load_or_bind_source(scope), "BIND")
        assert ready.phase == SourcePhase.READY and ready.generation == 0
        assert ready.authority is not None and ready.authority.sequence == 1
        assert ready.progress.next_index == 0
        assert await redis.get(count_key) == b"0"
        if scenario == "clock_reserve_rollback":
            transport.state_reads = 0

            def rollback_after_final_read(reads: int) -> None:
                if reads == 3:
                    clock_patch.setattr(
                        admission_module, "now_millis", lambda: real_now() - timedelta(seconds=5)
                    )

            transport.on_state_read = rollback_after_final_read
            with pytest.raises(SourceAdmissionDenied):
                await coordinated(
                    store.reserve_source_invocation(
                        scope, 0, 0, store.source_owner_id, real_now() + timedelta(seconds=110)
                    ),
                    "RESERVE",
                )
            await bridge_step("DONE")
            return
        if scenario == "missing_bound":
            await bridge_step("REMOVE")
            with pytest.raises(SourceAdmissionDenied) as lost:
                await coordinated(store.get_source(scope))
            assert lost.value.code == "STATE_LOST"
            await bridge_step("DONE")
            return
        deadline = now_millis() + timedelta(
            seconds=2 if scenario in {"lease_expiry", "late_stage", "result_recovery"} else 110
        )
        if scenario == "concurrent":
            other = RedisJobStore(cast(AsyncRedisStore, redis))
            await bridge_step("START")
            results = await asyncio.gather(
                *[
                    candidate.reserve_source_invocation(
                        scope, 0, 0, candidate.source_owner_id, deadline
                    )
                    for candidate in (store, other)
                ],
                return_exceptions=True,
            )
            assert sum(isinstance(item, SourceAdmissionDenied) for item in results) == 1
            for candidate, item in zip((store, other), results, strict=True):
                if not isinstance(item, BaseException):
                    assert item.invocation is not None
                    assert await candidate.take_source_invocation(scope, item)
                    await redis.incr(count_key)
                    assert not await candidate.take_source_invocation(scope, item)
            await bridge_step("STOP")
            assert await redis.get(count_key) == b"1"
            await bridge_step("DONE")
            return
        if scenario == "original_repeat":
            from app.jobs.source_admission import AdmissionReply, SourceAdmissionClient

            client = SourceAdmissionClient(cast(AsyncRedisStore, redis))
            request = client.prepare(scope, state=ready, index=0, deadline=deadline)
            reply = await coordinated(
                observe_request_completion(client.request(request)), "RESERVE"
            )
            assert reply.status == "RESERVED"
            assert (await client.request(request)).status == "HELD"
            assert (await client.request(request.readback())).status == "HELD"
            assert request.token is not None
            assert not await store.take_source_invocation(
                scope, SourceState.from_json(await redis.get(scope.state_key))
            )
            # Replay through the actual backend as a negative fixture, not through an
            # invented new pending request. Clear only this owned immutable cache key.
            for frame in (request.to_json(), request.readback().to_json()):
                await redis.delete(client.reply_key(request.nonce))
                await redis.set(client.slot_key(request.nonce), frame, nx=True, ex=10)
                await bridge_step("POLL")
                repeated = AdmissionReply.from_json(
                    await redis.get(client.reply_key(request.nonce))
                )
                assert repeated.status == "HELD"
            await redis.delete(scope.state_key)
            with pytest.raises(SourceAdmissionDenied) as missing_issue_state:
                await coordinated(store.get_source(scope))
            assert missing_issue_state.value.code == "STATE_LOST"
            await bridge_step("DONE")
            return
        reserve = observe_request_completion(
            store.reserve_source_invocation(scope, 0, 0, store.source_owner_id, deadline)
        )
        if scenario == "client_cancel":
            task = asyncio.create_task(reserve)

            async def cancel_after_burn() -> None:
                async with asyncio.timeout(5):
                    while await redis.get("r2:burned:" + str(scope.job_id)) is None:
                        await asyncio.sleep(0.005)
                task.cancel()

            watcher = asyncio.create_task(cancel_after_burn())
            try:
                with pytest.raises(asyncio.CancelledError):
                    await coordinated(task, "RESERVE")
                await watcher
                recovered = await coordinated(store.get_source(scope))
                assert recovered.phase == SourcePhase.RESERVED
                assert not await store.take_source_invocation(scope, recovered)
                assert await redis.get(count_key) == b"0"
                await bridge_step("DONE")
                return
            finally:
                watcher.cancel()
                await asyncio.gather(watcher, return_exceptions=True)
        if scenario in {
            "rollback",
            "lost_cas",
            "lost_commit",
            "cancel_between",
            "lock_expiry",
            "no_reply",
            "lock_second_expiry",
        }:
            with pytest.raises(SourceAdmissionDenied):
                await coordinated(reserve, "RESERVE")
            assert await redis.get(count_key) == b"0"
            await bridge_step("RESTORE")
            with pytest.raises(SourceAdmissionDenied):
                await coordinated(store.get_source(scope))
            with pytest.raises(SourceAdmissionDenied):
                await coordinated(
                    store.reserve_source_invocation(
                        scope, 0, 0, store.source_owner_id, now_millis() + timedelta(seconds=110)
                    )
                )
            await bridge_step("DONE")
            return
        reserved = await coordinated(reserve, "RESERVE")
        assert reserved.generation == 2 and reserved.authority is not None
        assert reserved.authority.reserved_generation == 1 and reserved.authority.sequence == 2
        assert reserved.invocation is not None
        token = reserved.invocation.token
        assert await redis.get(count_key) == b"0"
        if scenario == "same_runtime_duplicate":
            duplicate = await coordinated(store.get_source(scope))
            assert duplicate == reserved and duplicate is not reserved
            assert not await store.take_source_invocation(scope, duplicate)
            clone = SourceState.from_json(reserved.to_json())
            assert clone == reserved and clone is not reserved
            assert not await store.take_source_invocation(scope, clone)
            first, second = await asyncio.gather(
                store.take_source_invocation(scope, reserved),
                store.take_source_invocation(scope, reserved),
            )
            assert first + second == 1
            await redis.incr(count_key)
            await bridge_step("DONE")
            return
        other = RedisJobStore(cast(AsyncRedisStore, redis))
        loaded = await coordinated(other.get_source(scope))
        assert loaded.phase == SourcePhase.RESERVED
        assert not await other.take_source_invocation(scope, loaded)
        if scenario == "pending_expiry":
            await asyncio.sleep(10.1)
            assert not await store.take_source_invocation(scope, reserved)
            await bridge_step("DONE")
            return
        if scenario == "restored_issued":
            await bridge_step("RESTORE_ISSUED")
            assert not await store.take_source_invocation(scope, reserved)
            with pytest.raises(SourceAdmissionDenied):
                await coordinated(other.get_source(scope))
            await bridge_step("DONE")
            return
        if scenario == "lease_expiry":
            await asyncio.sleep(2.1)
            assert not await store.take_source_invocation(scope, reserved)
            assert not await store.renew_source_invocation(
                scope, token, now_millis() + timedelta(seconds=110)
            )
            held = await store.hold_source(scope, 2, SourceHoldReason.INVOCATION_UNCERTAIN)
            assert held.invocation is not None and held.invocation.attempt_number == 1
            await bridge_step("DONE")
            return
        assert await store.take_source_invocation(scope, reserved)
        await redis.incr(count_key)
        assert not await store.take_source_invocation(scope, reserved)
        # Stored/cached originals are never runner tickets, even in a fresh runtime.
        assert not await other.take_source_invocation(scope, reserved)
        if scenario == "clock_renew_rollback":
            transport.after_cas = lambda: clock_patch.setattr(
                admission_module, "now_millis", lambda: real_now() - timedelta(seconds=5)
            )
            assert not await store.renew_source_invocation(
                scope, token, real_now() + timedelta(seconds=120)
            )
            clock_patch.undo()
            assert not await store.renew_source_invocation(
                scope, token, real_now() + timedelta(seconds=120)
            )
            await bridge_step("DONE")
            return
        if scenario == "clock_offset":
            clock_patch.setattr(
                admission_module, "now_millis", lambda: real_now() + timedelta(seconds=30)
            )
            assert await store.renew_source_invocation(
                scope, token, admission_module.now_millis() + timedelta(seconds=100)
            )
            staged = await store.stage_source_result(
                scope,
                token,
                SourceExtractionResult(
                    paper_id=UUID("77777777-7777-7777-7777-777777777777"),
                    arxiv_id="2608.00001",
                    parser_version="0.1.0",
                    status="SUCCEEDED",
                    cleanup_confirmed=True,
                    source_format="tex",
                    files_inspected=1,
                ),
            )
            assert staged.phase == SourcePhase.RESULT_READY
            await bridge_step("DONE")
            return
        if scenario == "late_stage":
            result = SourceExtractionResult(
                paper_id=UUID("77777777-7777-7777-7777-777777777777"),
                arxiv_id="2608.00001",
                parser_version="0.1.0",
                status="SUCCEEDED",
                cleanup_confirmed=True,
                source_format="tex",
                files_inspected=1,
            )
            transport.delay_next_eval = 2.1
            with pytest.raises(SourceAdmissionDenied):
                await store.stage_source_result(scope, token, result)
            assert json.loads(await redis.get(scope.state_key))["phase"] == "RESERVED"
            assert not await store.renew_source_invocation(
                scope, token, now_millis() + timedelta(seconds=110)
            )
            await bridge_step("DONE")
            return
        if scenario == "result_recovery":
            result = SourceExtractionResult(
                paper_id=UUID("77777777-7777-7777-7777-777777777777"),
                arxiv_id="2608.00001",
                parser_version="0.1.0",
                status="SUCCEEDED",
                cleanup_confirmed=True,
                source_format="tex",
                files_inspected=1,
            )
            staged = await store.stage_source_result(scope, token, result)
            await asyncio.sleep(2.1)
            recovered = await coordinated(other.get_source(scope))
            assert recovered == staged and recovered.result == result
            assert not await other.take_source_invocation(scope, recovered)
            await bridge_step("TERMINAL")
            with pytest.raises(SourceAdmissionDenied) as denied:
                await coordinated(other.get_source(scope))
            assert denied.value.status == "TERMINAL" and denied.value.terminal_status == "FAILED"
            assert await other.get_source_retirement(scope) is None
            with pytest.raises(SourceAdmissionDenied):
                await other.record_source_terminal(scope, staged.generation, "FAILED")
            assert await redis.get(scope.state_key) == staged.to_json().encode()
            assert staged.progress.next_index == 0
            await bridge_step("DONE")
            return
        if scenario == "happy":
            seconds, micros = await redis.time()
            print(
                "SYNTHETIC_REDIS_CLOCK_DELTA_MS="
                + str(int(now_millis().timestamp() * 1000) - (seconds * 1000 + micros // 1000)),
                flush=True,
            )
            assert await store.renew_source_invocation(
                scope, token, now_millis() + timedelta(seconds=120)
            )
            result = SourceExtractionResult(
                paper_id=UUID("77777777-7777-7777-7777-777777777777"),
                arxiv_id="2608.00001",
                parser_version="0.1.0",
                status="SUCCEEDED",
                cleanup_confirmed=True,
                source_format="tex",
                files_inspected=1,
            )
            staged = await store.stage_source_result(scope, token, result)
            assert staged.phase == SourcePhase.RESULT_READY and staged.generation == 4
            with pytest.raises(SourceAdmissionDenied):
                await store.stage_source_result(scope, token, result)
            recovered = await coordinated(other.get_source(scope))
            assert recovered.result == result and recovered.progress.next_index == 0
            assert not await other.take_source_invocation(scope, recovered)
            # This fixture confirms publication locally; it never publishes to Kafka.
            advanced = await coordinated(other.advance_source(scope, recovered.generation, 0))
            assert advanced.generation == 5 and advanced.progress.next_index == 1
            assert advanced.progress.success == 1 and advanced.result is None
            for attempt in range(1, 7):
                issued = await coordinated(
                    store.reserve_source_invocation(
                        scope,
                        advanced.generation,
                        1,
                        store.source_owner_id,
                        now_millis() + timedelta(seconds=110),
                    )
                )
                assert issued.invocation is not None
                assert issued.invocation.attempt_number == attempt
                assert await store.take_source_invocation(scope, issued)
                await redis.incr(count_key)
                advanced = await store.record_source_transient(
                    scope,
                    issued.invocation.token,
                    SourceUpstreamReason.NETWORK,
                )
                assert advanced.progress.next_index == 1
            assert advanced.phase == SourcePhase.EXHAUSTED
            with pytest.raises(SourceAdmissionDenied):
                await store.reserve_source_invocation(
                    scope,
                    advanced.generation,
                    1,
                    store.source_owner_id,
                    now_millis() + timedelta(seconds=110),
                )
            terminal = await store.record_source_terminal(scope, advanced.generation, "FAILED")
            await bridge_step("TERMINAL")
            with pytest.raises(SourceAdmissionDenied) as denied_terminal:
                await coordinated(other.get_source(scope))
            assert denied_terminal.value.status == "TERMINAL"
            retired = await other.retire_source_terminal(scope, terminal.generation)
            assert retired.phase == SourcePhase.TERMINAL and retired.progress.next_index == 1
            assert retired.progress.success == 1 and retired.progress.failed == 0
            assert await redis.ttl(scope.state_key) == -1
            assert await redis.get(count_key) == b"7"
        await bridge_step("DONE")
    finally:
        clock_patch.undo()
        await redis.aclose()


# No skip or fake pass in normal worker runs. The Java test explicitly selects this
# additional cross-process test; absent/invalid required fixture input fails that run.
if os.environ.get("R2_LIVE_REQUIRED") == "1":

    async def test_live_bridge() -> None:
        await live_bridge()
