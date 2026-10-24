from __future__ import annotations

import asyncio
import hashlib
import json
import os
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Any, cast
from uuid import UUID, uuid4

import httpx
import pytest
import redis.asyncio as redis_async
from aiokafka import TopicPartition
from test_kafka import FakeConsumer, FakeProducer, FakeRecord

from app import main
from app.arxiv.source_errors import SourceUpstreamReason, SourceUpstreamTransientError
from app.config import Settings
from app.jobs.arxiv_consumer import ArxivCommandProcessor, CommandOutcome
from app.jobs.job_control import RedisJobStore
from app.jobs.source_admission import SourceAdmissionDenied
from app.jobs.source_retry import SourceRetryDecision, SourceScope
from app.messaging.contracts import (
    MessageEnvelope,
    MessageType,
    SourceExtractionResult,
    SourceTarget,
)
from app.messaging.kafka import KafkaConsumer, KafkaResultPublisher, run_with_consumer_polling
from app.observability.logging import get_logger


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "kind,payload",
    [
        (
            MessageType.ARXIV_IMPORT_METADATA,
            {"mode": "SELECTED", "arxivIds": ["2608.00001"], "maxPapers": 1},
        ),
        (MessageType.ARXIV_SYNC_OAI, {"setSpec": "cs:cs:AI"}),
        (MessageType.ARXIV_SYNC_TAXONOMY, {"requestedDate": "2026-08-05"}),
    ],
)
@pytest.mark.parametrize(
    "failure", ["http", "unexpected", "typed-outside-source", "redis", "kafka"]
)
async def test_actual_generic_processor_keeps_initial_plus_five_budget(
    kind: MessageType,
    payload: dict[str, object],
    failure: str,
) -> None:
    calls = 0
    error = (
        httpx.HTTPError("SYNTHETIC_PRIVATE")
        if failure == "http"
        else SourceUpstreamTransientError(SourceUpstreamReason.NETWORK)
        if failure == "typed-outside-source"
        else RuntimeError("SYNTHETIC_PRIVATE")
    )

    class Api:
        async def fetch_ids(self, *args: Any, **kwargs: Any) -> Any:
            nonlocal calls
            calls += 1
            raise error

        async def fetch_taxonomy(self) -> Any:
            return await self.fetch_ids()

        async def iter_record_pages(self, *args: Any, **kwargs: Any) -> AsyncIterator[Any]:
            await self.fetch_ids()
            yield None

    class GenericStore:
        marked = False

        async def is_processed(self, key: str) -> bool:
            nonlocal calls
            if failure == "redis":
                calls += 1
                raise ConnectionError("SYNTHETIC_PRIVATE")
            return self.marked

        async def control_for(self, job: UUID) -> str:
            return "RUN"

        async def cursor_for(self, key: str) -> None:
            return None

        async def mark_processed(self, key: str) -> None:
            self.marked = True

    events: list[str] = []

    class Producer(FakeProducer):
        async def send_and_wait(self, topic: str, **kwargs: Any) -> None:
            nonlocal calls
            if failure == "kafka" and json.loads(kwargs["value"])["type"] == "ARXIV_JOB_STARTED":
                calls += 1
                raise ConnectionError("SYNTHETIC_PRIVATE")
            await super().send_and_wait(topic, **kwargs)

    producer, consumer, store = Producer(events), FakeConsumer(events), GenericStore()
    processor = ArxivCommandProcessor(
        cast(Any, Api()),
        cast(Any, Api()),
        KafkaResultPublisher(cast(Any, producer), "results"),
        cast(Any, store),
        batch_size=1,
    )
    body = (
        MessageEnvelope[dict[str, object]](
            message_id=uuid4(),
            type=kind,
            job_id=uuid4(),
            idempotency_key="synthetic:generic",
            trace_id="0123456789abcdef",
            payload=payload,
        )
        .model_dump_json(by_alias=True)
        .encode()
    )
    settings = Settings(
        _env_file=None, results_topic="results", retry_topic="retry", dead_letter_topic="dlt"
    )
    for retry in range(6):
        await main._handle_command_record(
            cast(
                Any,
                FakeRecord(
                    value=body,
                    headers=[("camelRetryCount", str(retry).encode()), ("sourceScope", b"forged")],
                ),
            ),
            processor,
            cast(Any, producer),
            cast(Any, consumer),
            settings,
            main.WorkerRuntimeState(),
            get_logger(),
        )
    assert calls == 6 and len(consumer.offsets) == 6 and store.marked
    assert sum(call["topic"] == "retry" for call in producer.calls) == 5
    assert sum(call["topic"] == "dlt" for call in producer.calls) == 1
    terminal = json.loads(producer.calls[-1]["value"])
    assert terminal["payload"]["errorCode"] == "WORKER_RETRY_EXHAUSTED"


@pytest.mark.asyncio
@pytest.mark.parametrize("source", [False, True])
@pytest.mark.parametrize("callback_failure", [False, True])
async def test_handler_binds_actual_decision_and_resets_runtime(
    source: bool,
    callback_failure: bool,
) -> None:
    decision = SourceRetryDecision(
        SourceScope(
            "synthetic", uuid4(), uuid4(), MessageType.ARXIV_FETCH_AND_PARSE_SOURCE, "a" * 64
        ),
        18,
        0,
        6,
        SourceUpstreamReason.TIMEOUT,
        True,
    )
    observed: list[SourceRetryDecision | None] = []

    class Processor:
        async def process(self, body: bytes) -> CommandOutcome | SourceRetryDecision:
            return decision if source else CommandOutcome.RETRY

        async def publish_retry_exhausted_failure(
            self,
            body: bytes,
            source_decision: SourceRetryDecision | None = None,
        ) -> None:
            observed.append(source_decision)
            if callback_failure:
                raise RuntimeError("CONTROLLED_CALLBACK")

        async def publish_permanent_failure(self, body: bytes) -> None:
            raise AssertionError("wrong callback")

    events: list[str] = []
    consumer = FakeConsumer(events)
    state = main.WorkerRuntimeState()
    operation = main._handle_command_record(
        FakeRecord(headers=[("camelRetryCount", b"5")]),  # type: ignore[arg-type]
        cast(Any, Processor()),
        cast(Any, FakeProducer(events)),
        cast(Any, consumer),
        Settings(_env_file=None),
        state,
        get_logger(),
    )
    if callback_failure:
        with pytest.raises(RuntimeError, match="CONTROLLED_CALLBACK"):
            await operation
        assert not consumer.offsets
    else:
        await operation
        assert len(consumer.offsets) == 1
    assert observed == [decision if source else None]
    assert state.status == "IDLE" and state.current_job_id is None


@pytest.mark.asyncio
async def test_startup_supplies_same_real_store_and_configured_source_lease(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Transport:
        def __init__(self, *args: object, **kwargs: object) -> None:
            pass

        async def start(self) -> None:
            pass

        async def stop(self) -> None:
            pass

    def construct(*args: Any, **kwargs: Any) -> None:
        assert kwargs.get("source_store") is args[3]
        assert isinstance(args[3], RedisJobStore)
        assert kwargs.get("source_invocation_lease_seconds") == 90
        assert kwargs.get("source_invocation_renew_seconds") == 20
        raise RuntimeError("CONSTRUCTION_VERIFIED_NO_ACTIVATION")

    monkeypatch.setattr(main, "AIOKafkaProducer", Transport)
    monkeypatch.setattr(main, "AIOKafkaConsumer", Transport)
    monkeypatch.setattr(main, "ArxivCommandProcessor", construct)
    monkeypatch.setattr(asyncio.get_running_loop(), "add_signal_handler", lambda *args: None)
    with pytest.raises(RuntimeError, match="CONSTRUCTION_VERIFIED_NO_ACTIVATION"):
        await main.run(
            Settings(
                _env_file=None,
                source_invocation_lease_seconds=90,
                source_invocation_renew_seconds=20,
            )
        )


class FakePollingConsumer:
    def __init__(self) -> None:
        self.partition = TopicPartition("jobs", 0)
        self.paused: set[TopicPartition] = set()
        self.polls = 0
        self.resumed = False

    def assignment(self) -> set[TopicPartition]:
        return {self.partition}

    def pause(self, *partitions: TopicPartition) -> None:
        self.paused.update(partitions)

    def resume(self, *partitions: TopicPartition) -> None:
        self.paused.difference_update(partitions)
        self.resumed = True

    async def getmany(
        self, *partitions: TopicPartition, timeout_ms: int = 0, max_records: int = 1
    ) -> dict[TopicPartition, list[object]]:
        assert self.paused == self.assignment()
        self.polls += 1
        return {}


@pytest.mark.asyncio
async def test_long_operation_keeps_polling_without_draining_assigned_records() -> None:
    consumer = FakePollingConsumer()

    async def operation() -> str:
        while consumer.polls < 2:
            await asyncio.sleep(0.001)
        return "completed"

    result = await run_with_consumer_polling(
        cast(KafkaConsumer, consumer),
        operation(),
        interval_seconds=0.001,
    )

    assert result == "completed"
    assert consumer.polls >= 2
    assert consumer.resumed
    assert consumer.paused == set()


class FailingPollingConsumer(FakePollingConsumer):
    async def getmany(
        self, *partitions: TopicPartition, timeout_ms: int = 0, max_records: int = 1
    ) -> dict[TopicPartition, list[object]]:
        raise RuntimeError("poll heartbeat failed")


@pytest.mark.asyncio
async def test_poll_failure_cancels_the_unsettled_operation() -> None:
    consumer = FailingPollingConsumer()
    canceled = asyncio.Event()

    async def operation() -> None:
        try:
            await asyncio.Future()
        finally:
            canceled.set()

    with pytest.raises(RuntimeError, match="poll heartbeat failed"):
        await run_with_consumer_polling(
            cast(KafkaConsumer, consumer),
            operation(),
            interval_seconds=0.001,
        )

    assert canceled.is_set()
    assert consumer.resumed


async def live_handler_bridge() -> None:
    """Required Java-owned grants; transports below are controlled, NOT a Kafka broker."""
    scenario, restart = os.environ["R4_SCENARIO"], int(os.environ["R4_RESTART"])
    redis = redis_async.Redis.from_url(os.environ["R4_REDIS"])
    job, message = UUID(os.environ["R4_JOB"]), UUID(os.environ["R4_MESSAGE"])
    key = os.environ["R4_KEY"]
    prefix = f"r4:{job}:"
    payload: dict[str, object] = {
        "parserVersion": "0.1.0",
        "targets": [
            {"paperId": "77777777-7777-7777-7777-777777777777", "arxivId": "2608.00001"},
            {"paperId": "88888888-8888-8888-8888-888888888888", "arxivId": "2608.00002"},
        ],
    }
    scope = SourceScope.from_command(
        key, job, message, MessageType.ARXIV_FETCH_AND_PARSE_SOURCE, payload
    )
    assert scope.payload_fingerprint == os.environ["R4_FINGERPRINT"]
    envelope = MessageEnvelope[dict[str, object]](
        message_id=message,
        type=MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
        job_id=job,
        idempotency_key=key,
        trace_id="0123456789abcdef",
        occurred_at=datetime.now(UTC),
        payload=payload,
    )
    settings = Settings(_env_file=None, worker_id="synthetic-r4")
    store = RedisJobStore(redis)
    state_key = (
        "camel:worker:source-state:v2:{source-admission}:"
        + hashlib.sha256(key.encode()).hexdigest()
    )

    async def observe(event: str) -> None:
        await redis.rpush(prefix + "observations", event)

    async def crash(boundary: str) -> None:
        await observe("crash:" + boundary)
        # Deliberate real child exit: no finally, no retained owner/tasks/continuation.
        os._exit(73)

    fault_methods = {
        "reserve_after": ("reserve_source_invocation", "after"),
        "stage_before": ("stage_source_result", "before"),
        "stage_after": ("stage_source_result", "after"),
        "advance_before": ("advance_source", "before"),
        "advance_after": ("advance_source", "after"),
        "transient_after": ("record_source_transient", "after"),
        "pending_before": ("record_source_terminal", "before"),
        "published_db_terminal": ("record_source_terminal", "before"),
        "pending_after": ("record_source_terminal", "after"),
        "pending_db_terminal": ("record_source_terminal", "after"),
        "marker_before": ("mark_processed", "before"),
        "marker_after": ("mark_processed", "after"),
        "tombstone_before": ("retire_source_terminal", "before"),
        "tombstone_after": ("retire_source_terminal", "after"),
        "marker_expiry": ("retire_source_terminal", "after"),
        "marker_only": ("stage_source_result", "after"),
        "missing_bound": ("stage_source_result", "after"),
        "corrupt": ("stage_source_result", "after"),
        "staged_db_terminal": ("stage_source_result", "after"),
        "redis_restart": ("stage_source_result", "after"),
    }

    def instrument_store(method: str) -> None:
        original = getattr(store, method)
        faulted = False

        async def interrupted(*args: Any, **kwargs: Any) -> Any:
            nonlocal faulted
            await observe("store-before:" + method)
            selected = restart == 0 and fault_methods.get(scenario, (None, None))[0] == method
            if selected and fault_methods[scenario][1] == "before":
                await crash(scenario)
            result = await original(*args, **kwargs)
            await observe("store-confirmed:" + method)
            if selected:
                await crash(scenario)
            if not faulted and (
                (scenario == "stage_ack_lost" and method == "stage_source_result")
                or (scenario == "advance_ack_lost" and method == "advance_source")
            ):
                faulted = True
                await observe("store-ack-discarded:" + method)
                raise ConnectionError("CONTROLLED_STORE_ACK_LOST")
            return result

        setattr(store, method, interrupted)

    for method in {
        "reserve_source_invocation",
        "stage_source_result",
        "advance_source",
        "record_source_transient",
        "record_source_terminal",
        "mark_processed",
        "retire_source_terminal",
    }:
        instrument_store(method)

    class Runner:
        async def run(self, target: SourceTarget) -> SourceExtractionResult:
            paper_id, arxiv_id = target.paper_id, target.arxiv_id
            # Only called by the real processor immediately after its original grant take.
            await redis.incr(prefix + "runner")
            count = await redis.hincrby(prefix + "targets", arxiv_id, 1)
            await observe("runner:" + arxiv_id + ":" + str(count))
            if scenario in {
                "exhaust",
                "retry_reject",
                "retry_unknown",
                "retry_commit",
                "transient_after",
            } or (scenario == "fail_once" and count == 1):
                raise SourceUpstreamTransientError(SourceUpstreamReason.UNAVAILABLE)
            return SourceExtractionResult(
                paper_id=paper_id,
                arxiv_id=arxiv_id,
                parser_version="0.1.0",
                status="SUCCEEDED",
                cleanup_confirmed=True,
                source_format="TAR_GZIP",
                files_inspected=1,
            )

    class Producer:
        async def send_and_wait(self, topic: str, **kwargs: Any) -> None:
            body = json.loads(kwargs["value"])
            kind = (
                body["type"]
                if topic == settings.results_topic
                else ("retry" if topic == settings.retry_topic else "dlt")
            )
            stage = body["payload"].get("stage", "")
            boundary = (
                "result"
                if kind == "ARXIV_EXTRACTION_RESULT"
                else "progress"
                if stage == "EXTRACTING_CONTACTS"
                else "terminal"
                if kind in {"ARXIV_JOB_COMPLETED", "ARXIV_JOB_FAILED"}
                else kind
            )
            await observe("pre-send:" + boundary)
            fault = restart == 0 and scenario in {boundary + "_reject", boundary + "_unknown"}
            drain_fault = (
                scenario in {"generic_drain", "generic_drain_reject"}
                and boundary == "result"
                and restart == 0
            )
            if fault and scenario.endswith("_reject"):
                await crash("rejected:" + boundary)
            if drain_fault:
                failures = await redis.incr(prefix + "drain-failures")
                if failures <= (2 if scenario == "generic_drain_reject" else 1):
                    await observe("rejected:" + boundary)
                    raise RuntimeError("CONTROLLED_SEND_REJECTED")
            await redis.rpush(prefix + "accepted", json.dumps({"topic": topic, "body": body}))
            await observe("accepted:" + boundary)
            if fault:
                await crash("unknown:" + boundary)
            await observe("confirmed:" + boundary)

    class Consumer:
        async def commit(self, offsets: Any) -> None:
            await observe("offset-request")
            if restart == 0 and scenario in {"offset", "retry_commit"}:
                await crash("offset")
            await redis.incr(prefix + "offsets")
            await observe("offset-confirmed")

    if restart and scenario == "generic_drain_reject":
        # Fail the first actual read, then allow the actual callback to BIND/drain.
        actual_retirement = store.get_source_retirement
        failed = False

        async def unavailable_once(requested: SourceScope) -> Any:
            nonlocal failed
            if not failed:
                failed = True
                raise RuntimeError("CONTROLLED_READ_UNAVAILABLE")
            return await actual_retirement(requested)

        store.get_source_retirement = unavailable_once  # type: ignore[method-assign,assignment]

    class ObservedProcessor(ArxivCommandProcessor):
        async def publish_retry_exhausted_failure(
            self,
            body: bytes,
            source_decision: SourceRetryDecision | None = None,
        ) -> None:
            await observe("callback-start:" + ("source" if source_decision else "generic"))
            await super().publish_retry_exhausted_failure(body, source_decision)
            await observe("callback-confirmed")

    processor = ObservedProcessor(
        cast(Any, object()),
        cast(Any, object()),
        KafkaResultPublisher(cast(Any, Producer()), settings.results_topic),
        store,
        batch_size=1,
        source_store=store,
        source_runner=Runner(),
        source_invocation_lease_seconds=6,
        source_invocation_renew_seconds=1,
    )
    runtime = main.WorkerRuntimeState()
    try:
        for delivery in range(9):
            header = [None, b"bad", b"5", b"0"][delivery % 4]
            if scenario in {"generic_drain", "generic_drain_reject"}:
                header = b"5"
            if restart and scenario == "advance_after":
                header = b"5"
            if restart and scenario == "advance_before":
                header = b"malformed"
            record = FakeRecord(
                value=envelope.model_dump_json(by_alias=True).encode(),
                headers=[]
                if header is None
                else [
                    ("camelRetryCount", header),
                    ("sourceAttempt", b"0"),
                    ("sourceScope", b"untrusted"),
                ],
            )
            try:
                await main._handle_command_record(
                    cast(Any, record),
                    processor,
                    cast(Any, Producer()),
                    cast(Any, Consumer()),
                    settings,
                    runtime,
                    get_logger(),
                )
            except RuntimeError:
                assert scenario == "generic_drain_reject" and restart == 0
                assert await redis.get(prefix + "offsets") is None
                assert runtime.status == "IDLE" and runtime.current_job_id is None
                await crash("callback-unresolved")
            assert runtime.status == "IDLE" and runtime.current_job_id is None
            raw = await redis.get(state_key)
            phase = (
                "CORRUPT"
                if scenario == "corrupt"
                else json.loads(raw)["phase"]
                if raw
                else "ABSENT"
            )
            if phase in {"TERMINAL", "HELD", "ABSENT"} or scenario in {
                "reserve_after",
                "stage_before",
                "terminal_deny",
                "scope_conflict",
                "marker_only",
                "missing_bound",
                "published_db_terminal",
                "staged_db_terminal",
                "corrupt",
            }:
                break
        else:
            raise AssertionError("LIVE_HANDLER_DID_NOT_SETTLE")
        accepted = [json.loads(v) for v in await redis.lrange(prefix + "accepted", 0, -1)]
        if phase == "TERMINAL":
            before_runner = await redis.get(prefix + "runner")
            await main._handle_command_record(
                cast(Any, record),
                processor,
                cast(Any, Producer()),
                cast(Any, Consumer()),
                settings,
                runtime,
                get_logger(),
            )
            assert await redis.get(prefix + "runner") == before_runner
            assert await redis.llen(prefix + "accepted") == len(accepted)
        result_keys = [
            v["body"]["idempotencyKey"]
            for v in accepted
            if v["body"]["type"] == "ARXIV_EXTRACTION_RESULT"
        ]
        # Accepted duplicate sends retain stable keys; backend receipt is tested separately.
        if scenario in {"result_unknown", "progress_reject", "progress_unknown", "advance_before"}:
            assert len(result_keys) == 3 and len(set(result_keys)) == 2
        if scenario in {"generic_drain", "generic_drain_reject"}:
            assert await redis.get(prefix + "runner") == b"1"
            failed_events = [v["body"] for v in accepted if v["body"]["type"] == "ARXIV_JOB_FAILED"]
            assert failed_events[-1]["payload"]["errorCode"] == "WORKER_RETRY_EXHAUSTED"
            assert failed_events[-1]["payload"]["processedCount"] == 1
        if scenario in {
            "exhaust",
            "retry_reject",
            "retry_unknown",
            "retry_commit",
            "transient_after",
        }:
            assert await redis.get(prefix + "runner") == b"6"
            failed_events = [v["body"] for v in accepted if v["body"]["type"] == "ARXIV_JOB_FAILED"]
            assert failed_events[-1]["payload"]["errorCode"] == "SOURCE_UPSTREAM_RETRY_EXHAUSTED"
            assert failed_events[-1]["payload"]["processedCount"] == 0
        if scenario in {
            "reserve_after",
            "stage_before",
            "marker_only",
            "missing_bound",
            "unknown",
            "corrupt",
            "redis_restart",
        }:
            holds = [
                v["body"]
                for v in accepted
                if v["body"]["payload"].get("stage") == "SOURCE_RECONCILIATION_REQUIRED"
            ]
            assert holds and holds[-1]["payload"]["status"] == "PAUSED"
            assert holds[-1]["idempotencyKey"] == key + ":result:ARXIV_JOB_PROGRESS:999996"
            checkpoint = holds[-1]["payload"]["checkpoint"]
            if scenario in {
                "unknown",
                "missing_bound",
                "reserve_after",
                "stage_before",
                "corrupt",
                "redis_restart",
            }:
                assert checkpoint == {}
            else:
                assert checkpoint == {"nextIndex": 0, "success": 0, "skipped": 0, "failed": 0}
            assert holds[-1]["payload"]["processedCount"] == 0
        if scenario in {"terminal_deny", "scope_conflict"}:
            assert not accepted
        terminals = [
            v["body"]
            for v in accepted
            if v["body"]["type"] in {"ARXIV_JOB_COMPLETED", "ARXIV_JOB_FAILED"}
        ]
        if scenario in {
            "pending_after",
            "pending_db_terminal",
            "marker_before",
            "marker_after",
            "tombstone_before",
            "tombstone_after",
            "offset",
            "marker_expiry",
            "published_db_terminal",
        }:
            assert len(terminals) == 1
        if scenario in {"pending_before", "terminal_unknown"}:
            assert len(terminals) == 2
            assert len({v["idempotencyKey"] for v in terminals}) == 1
        if scenario == "staged_db_terminal":
            assert not result_keys and not terminals
        if scenario == "redis_restart":
            assert await redis.get(prefix + "runner") is None
            assert not result_keys and not terminals
            with pytest.raises(SourceAdmissionDenied) as denied:
                await store.load_or_bind_source(scope)
            assert denied.value.code == "STATE_LOST"
        observations = [v.decode() for v in await redis.lrange(prefix + "observations", 0, -1)]
        if "callback-confirmed" in observations:
            assert observations.index("confirmed:dlt") < observations.index("callback-confirmed")
            # Earlier source retries may already have committed their distinct delivery.
            assert (
                "offset-confirmed" in observations[observations.index("callback-confirmed") + 1 :]
            )
        await observe("verified:" + scenario)
        print("R4_VERIFIED " + scenario + " restart=" + str(restart), flush=True)
    finally:
        await redis.aclose()


if os.environ.get("R4_LIVE_REQUIRED") == "1":

    @pytest.mark.asyncio
    async def test_required_live_handler_bridge() -> None:
        await live_handler_bridge()
