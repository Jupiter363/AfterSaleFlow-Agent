from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from types import MappingProxyType
from typing import Mapping


class HearingOperation(StrEnum):
    INTAKE_QUESTIONS = "intake_questions"
    INTAKE_SYNTHESIS = "intake_synthesis"
    EVIDENCE_REQUESTS = "evidence_requests"
    EVIDENCE_SYNTHESIS = "evidence_synthesis"
    JUDGE_V1 = "judge_v1"
    JUDGE_V2 = "judge_v2"
    JURY_REVIEW = "jury_review"


@dataclass(frozen=True, slots=True)
class HearingGraphIdentity:
    identity: str
    graph_key: str
    graph_version: str
    state_schema_version: str
    checkpoint_schema_version: str
    prompt_version: str
    model_profile_id: str
    output_schema_version: str
    policy_version: str
    guardrail_version: str
    tool_policy_version: str
    operations: tuple[HearingOperation, ...]


HEARING_GRAPH_IDENTITIES: Mapping[str, HearingGraphIdentity] = MappingProxyType(
    {
        "hearing.intake.v1": HearingGraphIdentity(
            identity="hearing.intake.v1",
            graph_key="hearing.intake",
            graph_version="hearing.intake.v1",
            state_schema_version="hearing.graph-state.v1",
            checkpoint_schema_version="hearing.checkpoint.v1",
            prompt_version="hearing.prompt-bundle.v1",
            model_profile_id="hearing.model-profile.v1",
            output_schema_version="hearing.intake-proposal.v1",
            policy_version="hearing.proposal-only.v1",
            guardrail_version="hearing.guardrails.v1",
            tool_policy_version="hearing.no-tools.v1",
            operations=(
                HearingOperation.INTAKE_QUESTIONS,
                HearingOperation.INTAKE_SYNTHESIS,
            ),
        ),
        "hearing.evidence.v1": HearingGraphIdentity(
            identity="hearing.evidence.v1",
            graph_key="hearing.evidence",
            graph_version="hearing.evidence.v1",
            state_schema_version="hearing.graph-state.v1",
            checkpoint_schema_version="hearing.checkpoint.v1",
            prompt_version="hearing.prompt-bundle.v1",
            model_profile_id="hearing.model-profile.v1",
            output_schema_version="hearing.evidence-proposal.v1",
            policy_version="hearing.proposal-only.v1",
            guardrail_version="hearing.guardrails.v1",
            tool_policy_version="hearing.no-tools.v1",
            operations=(
                HearingOperation.EVIDENCE_REQUESTS,
                HearingOperation.EVIDENCE_SYNTHESIS,
            ),
        ),
        "hearing.judge.v1": HearingGraphIdentity(
            identity="hearing.judge.v1",
            graph_key="hearing.judge",
            graph_version="hearing.judge.v1",
            state_schema_version="hearing.graph-state.v1",
            checkpoint_schema_version="hearing.checkpoint.v1",
            prompt_version="hearing.prompt-bundle.v1",
            model_profile_id="hearing.model-profile.v1",
            output_schema_version="hearing.judge-proposal.v1",
            policy_version="hearing.proposal-only.v1",
            guardrail_version="hearing.guardrails.v1",
            tool_policy_version="hearing.no-tools.v1",
            operations=(HearingOperation.JUDGE_V1, HearingOperation.JUDGE_V2),
        ),
        "hearing.jury.v1": HearingGraphIdentity(
            identity="hearing.jury.v1",
            graph_key="hearing.jury",
            graph_version="hearing.jury.v1",
            state_schema_version="hearing.graph-state.v1",
            checkpoint_schema_version="hearing.checkpoint.v1",
            prompt_version="hearing.prompt-bundle.v1",
            model_profile_id="hearing.model-profile.v1",
            output_schema_version="hearing.jury-proposal.v1",
            policy_version="hearing.proposal-only.v1",
            guardrail_version="hearing.guardrails.v1",
            tool_policy_version="hearing.no-tools.v1",
            operations=(HearingOperation.JURY_REVIEW,),
        ),
    }
)


HEARING_OPERATION_IDENTITIES: Mapping[HearingOperation, HearingGraphIdentity] = MappingProxyType(
    {
        operation: identity
        for identity in HEARING_GRAPH_IDENTITIES.values()
        for operation in identity.operations
    }
)


if len(HEARING_GRAPH_IDENTITIES) != 4 or len(HEARING_OPERATION_IDENTITIES) != 7:
    raise RuntimeError("Hearing graph registry candidate must contain four families and seven operations")


HEARING_MODEL_NODE_PROMPTS: Mapping[str, str] = MappingProxyType(
    {
        "hearing_intake_questions": (
            "dispute_intake_officer/hearing_intake_questions.md"
        ),
        "hearing_intake_synthesis": (
            "dispute_intake_officer/hearing_intake_synthesis.md"
        ),
        "hearing_evidence_requests": "evidence_clerk/hearing_evidence_requests.md",
        "hearing_evidence_file_assessment": (
            "evidence_clerk/hearing_evidence_file_assessment.md"
        ),
        "hearing_evidence_synthesis": "evidence_clerk/hearing_evidence_synthesis.md",
        "hearing_judge_v1": "presiding_judge/hearing_judge_v1.md",
        "hearing_jury_review": "deliberation_panel/hearing_jury_review.md",
        "hearing_judge_v2": "presiding_judge/hearing_judge_v2.md",
    }
)


if len(HEARING_MODEL_NODE_PROMPTS) != 8:
    raise RuntimeError("Hearing prompt bundle must cover all eight model nodes")


# Hearing graphs are proposal-only. There is no formal/domain tool in any model path.
EMPTY_HEARING_TOOL_POLICY: tuple[()] = ()
