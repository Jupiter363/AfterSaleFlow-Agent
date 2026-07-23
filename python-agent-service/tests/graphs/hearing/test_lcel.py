from __future__ import annotations

from types import SimpleNamespace

from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import RunnableSequence
from pydantic import BaseModel, ConfigDict
import pytest

from app.graphs.hearing.contracts import EMPTY_HEARING_TOOL_POLICY
from app.graphs.hearing.errors import HearingLcelContractError
from app.graphs.hearing.lcel import (
    GovernedHearingModelAdapter,
    build_hearing_lcel,
    invoke_hearing_lcel,
)


class _StrictOutput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    value: str


class _LooseOutput(BaseModel):
    value: str


class _Runner:
    def __init__(self, value: object) -> None:
        self.value = value
        self.calls: list[dict[str, object]] = []

    def invoke_structured(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(value=self.value, model="test-model")


def test_lcel_is_prompt_pipe_model_pipe_parser_object_flow() -> None:
    runner = _Runner({"value": "typed proposal"})

    flow = build_hearing_lcel(
        model_runner=runner,
        node_name="hearing_judge_v1",
        output_type=_StrictOutput,
    )

    assert isinstance(flow.runnable, RunnableSequence)
    assert isinstance(flow.prompt, ChatPromptTemplate)
    assert isinstance(flow.model, GovernedHearingModelAdapter)
    assert isinstance(flow.parser, PydanticOutputParser)
    assert flow.runnable.first is flow.prompt
    assert flow.runnable.middle == [flow.model]
    assert flow.runnable.last is flow.parser
    assert flow.model.tool_policy == EMPTY_HEARING_TOOL_POLICY == ()


def test_lcel_preserves_prompt_message_and_typed_parser_boundaries() -> None:
    runner = _Runner({"value": "typed proposal"})
    case_data = {"request": {"case_id": "CASE_hearing", "stage_sequence": 1}}

    result = invoke_hearing_lcel(
        model_runner=runner,
        node_name="hearing_intake_questions",
        case_data=case_data,
        output_type=_StrictOutput,
    )

    assert result == _StrictOutput(value="typed proposal")
    assert runner.calls == [
        {
            "node_name": "hearing_intake_questions",
            "case_data": case_data,
            "output_type": _StrictOutput,
        }
    ]


def test_lcel_rejects_non_strict_output_schema() -> None:
    with pytest.raises(HearingLcelContractError, match="HEARING_OUTPUT_SCHEMA_NOT_STRICT"):
        GovernedHearingModelAdapter(
            model_runner=_Runner({"value": "x"}),
            node_name="hearing_judge_v1",
            output_type=_LooseOutput,
        )


def test_lcel_rejects_any_formal_tool_policy() -> None:
    with pytest.raises(HearingLcelContractError, match="HEARING_FORMAL_TOOL_POLICY_FORBIDDEN"):
        GovernedHearingModelAdapter(
            model_runner=_Runner({"value": "x"}),
            node_name="hearing_judge_v1",
            output_type=_StrictOutput,
            tool_policy=("domain.write",),  # type: ignore[arg-type]
        )


def test_lcel_strict_parser_rejects_unknown_fields() -> None:
    runner = _Runner({"value": "typed proposal", "formal_effect": True})

    with pytest.raises(ValueError, match="formal_effect"):
        invoke_hearing_lcel(
            model_runner=runner,
            node_name="hearing_jury_review",
            case_data={"request": {"case_id": "CASE_hearing"}},
            output_type=_StrictOutput,
        )
