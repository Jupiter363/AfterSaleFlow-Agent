from __future__ import annotations

from typing import Any

import pytest
from langchain_core.callbacks import BaseCallbackHandler
from typing_extensions import TypedDict

from app.graph_runtime.state_lens import StateLens, StateLensError


class PromptInput(TypedDict):
    case_id: str
    summary: str


class CapturingHandler(BaseCallbackHandler):
    def __init__(self) -> None:
        self.started: list[str] = []
        self.ended: list[object] = []

    def on_chain_start(self, serialized, inputs, **kwargs: Any) -> None:
        self.started.append(serialized["name"])

    def on_chain_end(self, outputs, **kwargs: Any) -> None:
        self.ended.append(outputs)


def lens() -> StateLens[dict[str, Any], PromptInput]:
    return StateLens(
        name="intake_summary_lens",
        source_fields=("bindings", "memory_summary"),
        selector=lambda scoped: {
            "case_id": scoped["bindings"]["case_id"],
            "summary": scoped["memory_summary"],
        },
        output_type=PromptInput,
    )


def state() -> dict[str, Any]:
    return {
        "bindings": {"case_id": "case-001"},
        "memory_summary": "bounded summary",
        "private_state": "must not be visible",
    }


def test_lens_is_a_traced_runnable_and_only_receives_declared_fields() -> None:
    handler = CapturingHandler()

    output = lens().invoke(state(), config={"callbacks": [handler]})

    assert output == {"case_id": "case-001", "summary": "bounded summary"}
    assert handler.started == ["intake_summary_lens"]
    assert handler.ended == [output]


@pytest.mark.asyncio
async def test_lens_has_native_async_object_flow() -> None:
    handler = CapturingHandler()

    output = await lens().ainvoke(state(), config={"callbacks": [handler]})

    assert output["case_id"] == "case-001"
    assert handler.started == ["intake_summary_lens"]


def test_selector_cannot_reach_undeclared_state_or_mutate_source() -> None:
    source = state()
    nested = source["bindings"]

    mutating_lens = StateLens(
        name="mutation_test",
        source_fields=("bindings",),
        selector=lambda scoped: _mutate_copy(scoped),
        output_type=PromptInput,
    )
    output = mutating_lens.invoke(source)

    assert output["case_id"] == "changed-copy"
    assert nested == {"case_id": "case-001"}

    forbidden = StateLens(
        name="forbidden_access",
        source_fields=("bindings",),
        selector=lambda scoped: {
            "case_id": scoped["bindings"]["case_id"],
            "summary": scoped["private_state"],
        },
        output_type=PromptInput,
    )
    with pytest.raises(StateLensError, match="output failed validation"):
        forbidden.invoke(source)


def test_lens_rejects_missing_sources_invalid_output_and_runtime_overrides() -> None:
    with pytest.raises(StateLensError, match="source field is missing"):
        lens().invoke({"bindings": {"case_id": "case-001"}})

    invalid = StateLens(
        name="invalid_output",
        source_fields=("bindings",),
        selector=lambda scoped: {"case_id": 123, "summary": "summary"},
        output_type=PromptInput,
    )
    with pytest.raises(StateLensError, match="output failed validation"):
        invalid.invoke(state())

    with pytest.raises(StateLensError, match="does not accept invocation overrides"):
        lens().invoke(state(), override="unsafe")


def _mutate_copy(scoped) -> dict[str, str]:
    scoped["bindings"]["case_id"] = "changed-copy"
    return {"case_id": scoped["bindings"]["case_id"], "summary": "summary"}
