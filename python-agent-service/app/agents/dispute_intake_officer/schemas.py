from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


AdmissionRecommendation = Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
KnowledgeAnswerMode = Literal["NONE", "STUB"]


class IntakeCaseDetailLlmOutput(BaseModel):
    """Structured output produced by the intake officer LLM node."""

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

