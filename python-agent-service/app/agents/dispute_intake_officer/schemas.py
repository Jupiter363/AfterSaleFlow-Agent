# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


AdmissionRecommendation = Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
KnowledgeAnswerMode = Literal["NONE", "STUB"]


class IntakeCaseDetailLlmOutput(BaseModel):
    """争议接待官大模型节点生成的结构化输出。"""

    model_config = ConfigDict(extra="forbid")

    room_utterance: str = Field(min_length=1, max_length=20_000)
    case_detail: dict[str, Any] | None = None
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
        return self
