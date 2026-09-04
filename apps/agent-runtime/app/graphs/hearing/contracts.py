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


@dataclass(frozen=True, slots=True)
class HearingProductionOperationBinding:
    operation: HearingOperation
    command_stage_code: str
    request_stage_code: str
    result_schema_version: str
    model_nodes: tuple[str, ...]


HEARING_GRAPH_IDENTITIES: Mapping[str, HearingGraphIdentity] = MappingProxyType(
    {
        "hearing.intake.v4": HearingGraphIdentity(
            identity="hearing.intake.v4",
            graph_key="hearing.intake",
            graph_version="hearing.intake.v4",
            state_schema_version="hearing.graph-state.v4",
            checkpoint_schema_version="hearing.checkpoint.v4",
            prompt_version="hearing.prompt-bundle.v4",
            model_profile_id="hearing.model-profile.v4",
            output_schema_version="hearing.intake-proposal.v4",
            policy_version="hearing.proposal-only.v4",
            guardrail_version="hearing.guardrails.v4",
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
        "hearing.judge.v2": HearingGraphIdentity(
            identity="hearing.judge.v2",
            graph_key="hearing.judge",
            graph_version="hearing.judge.v2",
            state_schema_version="hearing.graph-state.v2",
            checkpoint_schema_version="hearing.checkpoint.v2",
            prompt_version="hearing.prompt-bundle.v2",
            model_profile_id="hearing.model-profile.v2",
            output_schema_version="hearing.judge-proposal.v2",
            policy_version="hearing.proposal-only.v2",
            guardrail_version="hearing.guardrails.v2",
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
            "dispute_intake_officer/hearing_intake_question_generation_v5.md"
        ),
        "hearing_intake_synthesis": (
            "dispute_intake_officer/hearing_intake_answer_synthesis_v5.md"
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


HEARING_WORKFLOW_STAGE_CODES: tuple[str, ...] = (
    "COURT_PREPARING",
    "CASE_INTRODUCTION",
    "EVIDENCE_INTRODUCTION",
    "INTAKE_QUESTIONS_GENERATING",
    "PARTY_ANSWERS_OPEN",
    "INTAKE_SYNTHESIZING",
    "EVIDENCE_REQUESTS_GENERATING",
    "PARTY_EVIDENCE_OPEN",
    "EVIDENCE_SYNTHESIZING",
    "DOSSIER_FREEZING",
    "JUDGE_V1_GENERATING",
    "JURY_REVIEWING",
    "JUDGE_V2_GENERATING",
    "HUMAN_REVIEW_OPEN",
    "CLOSED",
)


HEARING_PRODUCTION_RUNTIME_OPERATION_BINDINGS: Mapping[
    HearingOperation, HearingProductionOperationBinding
] = MappingProxyType(
    {
        HearingOperation.INTAKE_QUESTIONS: HearingProductionOperationBinding(
            operation=HearingOperation.INTAKE_QUESTIONS,
            command_stage_code="INTAKE_QUESTIONS_GENERATING",
            request_stage_code="INTAKE_QUESTIONS",
            result_schema_version="hearing_intake_questions.v5",
            model_nodes=("hearing_intake_questions",),
        ),
        HearingOperation.INTAKE_SYNTHESIS: HearingProductionOperationBinding(
            operation=HearingOperation.INTAKE_SYNTHESIS,
            command_stage_code="INTAKE_SYNTHESIZING",
            request_stage_code="INTAKE_SYNTHESIS",
            result_schema_version="hearing_intake_synthesis.v5",
            model_nodes=("hearing_intake_synthesis",),
        ),
        HearingOperation.EVIDENCE_REQUESTS: HearingProductionOperationBinding(
            operation=HearingOperation.EVIDENCE_REQUESTS,
            command_stage_code="EVIDENCE_REQUESTS_GENERATING",
            request_stage_code="EVIDENCE_REQUESTS",
            result_schema_version="hearing_evidence_requests.v1",
            model_nodes=("hearing_evidence_requests",),
        ),
        HearingOperation.EVIDENCE_SYNTHESIS: HearingProductionOperationBinding(
            operation=HearingOperation.EVIDENCE_SYNTHESIS,
            command_stage_code="EVIDENCE_SYNTHESIZING",
            request_stage_code="EVIDENCE_SYNTHESIS",
            result_schema_version="hearing_evidence_synthesis.v1",
            model_nodes=(
                "hearing_evidence_file_assessment",
                "hearing_evidence_synthesis",
            ),
        ),
        HearingOperation.JUDGE_V1: HearingProductionOperationBinding(
            operation=HearingOperation.JUDGE_V1,
            command_stage_code="JUDGE_V1_GENERATING",
            request_stage_code="JUDGE_V1",
            result_schema_version="hearing_judge_v1.v2",
            model_nodes=("hearing_judge_v1",),
        ),
        HearingOperation.JURY_REVIEW: HearingProductionOperationBinding(
            operation=HearingOperation.JURY_REVIEW,
            command_stage_code="JURY_REVIEWING",
            request_stage_code="JURY_REVIEW",
            result_schema_version="hearing_jury_review.v1",
            model_nodes=("hearing_jury_review",),
        ),
        HearingOperation.JUDGE_V2: HearingProductionOperationBinding(
            operation=HearingOperation.JUDGE_V2,
            command_stage_code="JUDGE_V2_GENERATING",
            request_stage_code="JUDGE_V2",
            result_schema_version="hearing_judge_v2.v2",
            model_nodes=("hearing_judge_v2",),
        ),
    }
)


if (
    len(HEARING_WORKFLOW_STAGE_CODES) != 15
    or len(set(HEARING_WORKFLOW_STAGE_CODES)) != 15
    or set(HEARING_PRODUCTION_RUNTIME_OPERATION_BINDINGS) != set(HearingOperation)
    or {
        node
        for binding in HEARING_PRODUCTION_RUNTIME_OPERATION_BINDINGS.values()
        for node in binding.model_nodes
    }
    != set(HEARING_MODEL_NODE_PROMPTS)
    or any(
        binding.command_stage_code not in HEARING_WORKFLOW_STAGE_CODES
        for binding in HEARING_PRODUCTION_RUNTIME_OPERATION_BINDINGS.values()
    )
):
    raise RuntimeError("Hearing production-runtime operation registry is incomplete")


# Hearing graphs are proposal-only. There is no formal/domain tool in any model path.
EMPTY_HEARING_TOOL_POLICY: tuple[()] = ()
