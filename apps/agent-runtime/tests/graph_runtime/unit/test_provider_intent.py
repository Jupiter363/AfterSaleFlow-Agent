from __future__ import annotations

import asyncio
from dataclasses import dataclass, replace
from typing import Any

import pytest

from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.provider_intent import GatewayProviderCallIntentRecorder
from app.llm import ProviderCallIntent


TRACEPARENT = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"


@dataclass(frozen=True)
class _Attempt:
    provider_call_count: int = 0


@dataclass(frozen=True)
class _Command:
    traceparent: str = TRACEPARENT


@dataclass(frozen=True)
class _Admission:
    command: _Command = _Command()


def _execution() -> GatewayExecution:
    return GatewayExecution(
        admission=_Admission(),  # type: ignore[arg-type]
        attempt=_Attempt(),  # type: ignore[arg-type]
        lease=object(),  # type: ignore[arg-type]
        fence=object(),  # type: ignore[arg-type]
    )


class _Gateway:
    def __init__(self) -> None:
        self.calls: list[Any] = []

    async def record_provider_call(self, execution: Any) -> Any:
        self.calls.append(execution)
        await asyncio.sleep(0)
        return replace(
            execution,
            attempt=replace(
                execution.attempt,
                provider_call_count=execution.attempt.provider_call_count + 1,
            ),
        )


def _intent(**overrides: Any) -> ProviderCallIntent:
    values = {
        "node_name": "hearing_judge_v2",
        "provider": "litellm",
        "model": "qwen3.7-plus",
        "traceparent": TRACEPARENT,
    }
    values.update(overrides)
    return ProviderCallIntent(**values)


@pytest.mark.asyncio
async def test_async_provider_intents_are_serialized_into_durable_attempt_count() -> None:
    gateway = _Gateway()
    recorder = GatewayProviderCallIntentRecorder(
        gateway=gateway,
        execution=_execution(),
        provider="litellm",
        model="qwen3.7-plus",
        allowed_nodes=frozenset({"hearing_judge_v2", "hearing_jury_review"}),
    )

    await asyncio.gather(
        recorder.arecord_provider_call(_intent()),
        recorder.arecord_provider_call(_intent(node_name="hearing_jury_review")),
    )

    assert len(gateway.calls) == 2
    assert gateway.calls[0].attempt.provider_call_count == 0
    assert gateway.calls[1].attempt.provider_call_count == 1
    assert recorder.execution.attempt.provider_call_count == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "intent",
    [
        _intent(provider="other"),
        _intent(model="other"),
        _intent(node_name="unknown_node"),
        _intent(traceparent="00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"),
    ],
)
async def test_provider_intent_binding_conflicts_fail_before_ledger_mutation(
    intent: ProviderCallIntent,
) -> None:
    gateway = _Gateway()
    recorder = GatewayProviderCallIntentRecorder(
        gateway=gateway,
        execution=_execution(),
        provider="litellm",
        model="qwen3.7-plus",
        allowed_nodes=frozenset({"hearing_judge_v2"}),
    )

    with pytest.raises(GraphContractError, match="conflicts"):
        await recorder.arecord_provider_call(intent)

    assert gateway.calls == []


def test_sync_provider_path_is_forbidden_for_graph_execution() -> None:
    recorder = GatewayProviderCallIntentRecorder(
        gateway=_Gateway(),
        execution=_execution(),
        provider="litellm",
        model="qwen3.7-plus",
        allowed_nodes=frozenset({"hearing_judge_v2"}),
    )

    with pytest.raises(GraphContractError, match="native async"):
        recorder.record_provider_call(_intent())
