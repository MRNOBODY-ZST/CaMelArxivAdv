from __future__ import annotations

import pytest
from pydantic import SecretStr

from app.config import PersonalizationSettings
from app.personalization.contracts import GeneratedEmail
from app.personalization.ray_executor import RayPersonalizationExecutor
from personalization.helpers import command


class FakeRemote:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def remote(self, command_json: str, target_json: str) -> tuple[str, str]:
        self.calls.append((command_json, target_json))
        return command_json, target_json


@pytest.mark.asyncio
async def test_fans_out_in_bounded_batches_and_preserves_partial_results() -> None:
    remote = FakeRemote()
    initialized: list[str] = []
    active = command(3)

    def get_result(reference: object) -> GeneratedEmail:
        assert isinstance(reference, tuple)
        if "Author 1" in reference[1]:
            raise RuntimeError("provider task exhausted retries")
        return GeneratedEmail(
            subject="Paper-aware subject",
            html='<p>Hello</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
            text="Hello {{unsubscribe_url}}",
            rationale="Grounded in the paper.",
        )

    executor = RayPersonalizationExecutor(
        PersonalizationSettings(
            enabled=True,
            api_key=SecretStr("sk-test"),
            model="gpt-test",
            ray_address="ray://ray-head:10001",
            maximum_concurrency=2,
        ),
        remote_function=remote,
        result_getter=get_result,
        initializer=lambda address: initialized.append(address),
        shutdown=lambda: None,
    )

    results = await executor.execute(active)

    assert initialized == ["ray://ray-head:10001"]
    assert len(remote.calls) == 3
    assert [result.payload.status for result in results] == ["GENERATED", "FAILED", "GENERATED"]
    assert results[1].payload.error_code == "GENERATION_FAILED"
    assert {result.recipient_id for result in results} == {
        target.recipient_id for target in active.payload.targets
    }
    assert "email" not in "".join(call[0] + call[1] for call in remote.calls).casefold()


@pytest.mark.asyncio
async def test_disabled_provider_returns_failures_without_starting_ray() -> None:
    remote = FakeRemote()
    initialized: list[str] = []
    executor = RayPersonalizationExecutor(
        PersonalizationSettings(enabled=False),
        remote_function=remote,
        result_getter=lambda reference: reference,
        initializer=lambda address: initialized.append(address),
        shutdown=lambda: None,
    )

    results = await executor.execute(command(2))

    assert initialized == []
    assert remote.calls == []
    assert {result.payload.error_code for result in results} == {"PROVIDER_DISABLED"}
