# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from typing_extensions import NotRequired, Required, TypedDict

from app.schemas.case_fact_matrix import CaseFactMatrixDeltaV2
from app.schemas.intake_case_matrix import UnilateralCaseMatrixDraftV1


AdmissionRecommendation = Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
KnowledgeAnswerMode = Literal["NONE", "STUB"]


class IntakeCaseStoryPatch(TypedDict, total=False):
    """Model-authored case-story fields that may change in one intake turn."""

    title: NotRequired[str]
    one_sentence_summary: Required[
        Annotated[str, Field(min_length=1, max_length=20_000)]
    ]
    event_timeline: NotRequired[list[dict[str, Any]]]


class IntakeCaseDetailPatch(TypedDict, total=False):
    """Incremental case-detail branches accepted from the intake model."""

    schema_version: NotRequired[Literal["intake_case_detail.v1"]]
    case_story: Required[IntakeCaseStoryPatch]
    references: NotRequired[dict[str, Any]]
    party_positions: NotRequired[dict[str, Any]]
    dispute_focus: NotRequired[dict[str, Any]]
    requested_resolution: NotRequired[dict[str, Any]]
    claim_resolution: NotRequired[dict[str, Any]]
    respondent_attitude: NotRequired[dict[str, Any]]
    dispute_core_state: NotRequired[dict[str, Any]]
    risk_assessment: NotRequired[dict[str, Any]]
    missing_information: NotRequired[dict[str, Any]]
    intake_quality: NotRequired[dict[str, Any]]
    admission: NotRequired[dict[str, Any]]
    handoff_notes: NotRequired[dict[str, Any]]
    case_fact_matrix: NotRequired[dict[str, Any]]
    unilateral_case_matrix: NotRequired[dict[str, Any]]


class IntakeCaseDetailLlmOutput(BaseModel):
    """争议接待官大模型节点生成的结构化输出。"""

    model_config = ConfigDict(extra="forbid")

    room_utterance: str = Field(min_length=1, max_length=20_000)
    case_detail: IntakeCaseDetailPatch
    case_matrix_delta: CaseFactMatrixDeltaV2 | None = None
    unilateral_case_matrix: UnilateralCaseMatrixDraftV1 | None = None
    dossier_patch: dict[str, Any] | None = None
    scroll_snapshot: dict[str, Any] | None = None
    canvas_operations: list[dict[str, Any]] = Field(default_factory=list, max_length=100)
    admission_recommendation: AdmissionRecommendation = "NEED_MORE_INFO"
    missing_fields: list[str] = Field(default_factory=list, max_length=30)
    knowledge_query_intent: bool = False
    knowledge_answer_mode: KnowledgeAnswerMode = "NONE"
    confidence: float = Field(default=0.0, ge=0, le=1)

    @model_validator(mode="after")
    def require_complete_case_summary(self) -> "IntakeCaseDetailLlmOutput":
        """每轮模型输出都必须生成新的累计事件摘要。"""

        if not isinstance(self.case_detail, dict):
            raise ValueError("case_detail is required")
        story = self.case_detail.get("case_story")
        summary = (
            str(story.get("one_sentence_summary") or "").strip()
            if isinstance(story, dict)
            else ""
        )
        if not summary:
            raise ValueError(
                "case_detail.case_story.one_sentence_summary is required"
            )
        if self.case_matrix_delta is None and self.unilateral_case_matrix is None:
            raise ValueError("case_matrix_delta is required")
        if self.case_matrix_delta is not None and self.unilateral_case_matrix is not None:
            raise ValueError("provide only case_matrix_delta")
        return self
