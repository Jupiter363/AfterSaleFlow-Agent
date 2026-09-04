from __future__ import annotations

from collections.abc import Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass
import json
import re
from typing import Any, Literal, cast

from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import (
    Runnable,
    RunnableConfig,
    RunnableLambda,
    RunnablePassthrough,
    RunnableSequence,
)
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.contracts.v1.codec import canonical_sha256
from app.graphs.evidence.contracts import EvidenceGraphContractError, JsonObject


_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_FACT_ID = re.compile(r"^FACT_[A-Za-z0-9_:-]{1,123}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_TARGET_SCOPE = "PRODUCTION"


class EvidenceCandidateFactLink(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    fact_id: str
    source_refs: list[str] = Field(min_length=1, max_length=16)

    @model_validator(mode="after")
    def validate_wire_values(self) -> EvidenceCandidateFactLink:
        if _FACT_ID.fullmatch(self.fact_id) is None:
            raise ValueError("fact_id is invalid")
        _require_sorted_identifiers(self.source_refs, "source_refs")
        return self


class EvidenceAssessmentDraft(BaseModel):
    """Model-owned cognition only; authority and identity are projected by code."""

    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    assessment_status: Literal["COMPLETED", "NEEDS_REVIEW"]
    authenticity_score: float = Field(ge=0, le=1)
    authenticity_reason_codes: list[str] = Field(max_length=16)
    relevance_score: float = Field(ge=0, le=1)
    relevance_reason_codes: list[str] = Field(max_length=16)
    completeness_score: float = Field(ge=0, le=1)
    confidence: float = Field(ge=0, le=1)
    candidate_fact_links: list[EvidenceCandidateFactLink] = Field(max_length=64)
    limitations: list[str] = Field(max_length=16)
    review_reasons: list[str] = Field(max_length=16)

    @field_validator(
        "authenticity_reason_codes",
        "relevance_reason_codes",
        "limitations",
        "review_reasons",
    )
    @classmethod
    def validate_identifiers(cls, values: list[str]) -> list[str]:
        _require_sorted_identifiers(values, "reason codes")
        return values

    @model_validator(mode="after")
    def validate_review_and_links(self) -> EvidenceAssessmentDraft:
        fact_ids = [link.fact_id for link in self.candidate_fact_links]
        if fact_ids != sorted(set(fact_ids)):
            raise ValueError("candidate fact links must be uniquely ordered")
        if self.assessment_status == "NEEDS_REVIEW" and not self.review_reasons:
            raise ValueError("NEEDS_REVIEW requires review reasons")
        if self.assessment_status == "COMPLETED" and self.review_reasons:
            raise ValueError("COMPLETED cannot carry review reasons")
        return self


@dataclass(frozen=True, slots=True)
class TargetEvidenceAsset:
    content: str
    source_refs: tuple[str, ...]
    inspected_modalities: tuple[str, ...]
    receipt_ref: str
    receipt_hash: str

    def __post_init__(self) -> None:
        if not self.content or len(self.content.encode("utf-8")) > 131_072:
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_CONTENT_INVALID")
        _require_sorted_identifiers(self.source_refs, "source_refs")
        if (
            not self.inspected_modalities
            or self.inspected_modalities != tuple(sorted(set(self.inspected_modalities)))
            or not set(self.inspected_modalities)
            <= {"TEXT", "IMAGE_PIXELS", "PDF_METADATA", "OCR"}
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_MODALITIES_INVALID")
        if (
            _IDENTIFIER.fullmatch(self.receipt_ref) is None
            or _SHA256.fullmatch(self.receipt_hash) is None
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_RECEIPT_INVALID")


class TargetEvidenceAssetLoader:
    async def load(self, item: Mapping[str, Any]) -> TargetEvidenceAsset:
        raise NotImplementedError


@dataclass(frozen=True, slots=True)
class TargetEvidenceAssessmentLCEL:
    prompt: ChatPromptTemplate
    model: Runnable[Any, Any]
    parser: PydanticOutputParser[EvidenceAssessmentDraft]
    runnable: RunnableSequence

    async def ainvoke(
        self,
        work_item: JsonObject,
        config: RunnableConfig | None = None,
    ) -> Mapping[str, Any]:
        result = await self.runnable.ainvoke(work_item, config=config)
        if not isinstance(result, Mapping):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_LCEL_OUTPUT_INVALID")
        return result


def build_target_evidence_assessment_lcel(
    *,
    model: Runnable[Any, Any],
    asset_loader: TargetEvidenceAssetLoader,
) -> TargetEvidenceAssessmentLCEL:
    if not callable(getattr(model, "ainvoke", None)):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_MODEL_RUNNABLE_REQUIRED")
    if not callable(getattr(asset_loader, "load", None)):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_LOADER_REQUIRED")

    prompt = ChatPromptTemplate.from_messages(
        [
            (
                "system",
                "You assess one isolated preproduction evidence item. Return only the requested "
                "JSON cognition. Never claim a formal write, case transition, or external effect.\n"
                "{format_instructions}",
            ),
            (
                "human",
                "Evidence metadata:\n{item_json}\n\nVerified read-only content:\n{asset_content}\n"
                "Allowed source references: {source_refs}\n"
                "Inspected modalities: {inspected_modalities}",
            ),
        ]
    )
    parser = PydanticOutputParser(pydantic_object=EvidenceAssessmentDraft)

    async def prepare(work_item: JsonObject) -> dict[str, Any]:
        normalized = _validate_target_work_item(work_item)
        item = cast(JsonObject, normalized["item"])
        loaded = await asset_loader.load(item)
        if not isinstance(loaded, TargetEvidenceAsset):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_INVALID")
        permitted = item.get("permitted_modalities")
        if not isinstance(permitted, list) or not set(loaded.inspected_modalities) <= set(permitted):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_CAPABILITY_EXCEEDED")
        return {"work_item": normalized, "asset": loaded}

    def prompt_values(value: Mapping[str, Any]) -> dict[str, Any]:
        work_item = cast(JsonObject, value["work_item"])
        asset = cast(TargetEvidenceAsset, value["asset"])
        return {
            "format_instructions": parser.get_format_instructions(),
            "item_json": json.dumps(
                work_item["item"],
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
            "asset_content": asset.content,
            "source_refs": json.dumps(asset.source_refs, separators=(",", ":")),
            "inspected_modalities": json.dumps(
                asset.inspected_modalities,
                separators=(",", ":"),
            ),
        }

    model_flow = RunnableLambda(prompt_values) | prompt | model | parser
    pipeline = cast(
        RunnableSequence,
        RunnableLambda(prepare)
        | RunnablePassthrough.assign(draft=model_flow)
        | RunnableLambda(_project_assessment),
    )
    return TargetEvidenceAssessmentLCEL(
        prompt=prompt,
        model=model,
        parser=parser,
        runnable=pipeline,
    )


def _project_assessment(value: Mapping[str, Any]) -> JsonObject:
    work_item = cast(JsonObject, value["work_item"])
    asset = cast(TargetEvidenceAsset, value["asset"])
    draft = value["draft"]
    if not isinstance(draft, EvidenceAssessmentDraft):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_MODEL_OUTPUT_INVALID")
    allowed_sources = set(asset.source_refs)
    if any(
        not set(link.source_refs) <= allowed_sources for link in draft.candidate_fact_links
    ):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_SOURCE_REF_FORGED")

    binding = cast(JsonObject, work_item["command_binding"])
    item = cast(JsonObject, work_item["item"])
    assessment: JsonObject = {
        "schema_version": "evidence-item-assessment.v1",
        "execution_scope": _TARGET_SCOPE,
        "formal_sink_eligible": False,
        "command_id": cast(str, binding["command_id"]),
        "logical_run_id": cast(str, binding["logical_run_id"]),
        "attempt_id": cast(str, binding["attempt_id"]),
        "thread_id": cast(str, work_item["thread_id"]),
        "manifest_id": cast(str, work_item["manifest_id"]),
        "manifest_hash": cast(str, work_item["manifest_hash"]),
        "evidence_id": cast(str, item["evidence_id"]),
        "item_hash": cast(str, item["item_hash"]),
        "formal_evidence_revision": cast(int, item["formal_evidence_revision"]),
        "actor_scope_hash": cast(str, work_item["actor_scope_hash"]),
        "profile_versions": deepcopy(cast(JsonObject, work_item["profile_versions"])),
        "assessment_status": draft.assessment_status,
        "authenticity_score": draft.authenticity_score,
        "authenticity_reason_codes": list(draft.authenticity_reason_codes),
        "relevance_score": draft.relevance_score,
        "relevance_reason_codes": list(draft.relevance_reason_codes),
        "completeness_score": draft.completeness_score,
        "confidence": draft.confidence,
        "candidate_fact_links": [
            {
                "fact_id": link.fact_id,
                "source_refs": list(link.source_refs),
            }
            for link in draft.candidate_fact_links
        ],
        "source_refs": list(asset.source_refs),
        "inspected_modalities": list(asset.inspected_modalities),
        "asset_load_status": "LOADED",
        "asset_load_receipt_ref": asset.receipt_ref,
        "asset_load_receipt_hash": asset.receipt_hash,
        "limitations": list(draft.limitations),
        "review_reasons": list(draft.review_reasons),
    }
    assessment["assessment_hash"] = canonical_sha256(assessment)
    return assessment


def _validate_target_work_item(work_item: Mapping[str, Any]) -> JsonObject:
    if not isinstance(work_item, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_WORK_ITEM_INVALID")
    normalized = cast(JsonObject, deepcopy(dict(work_item)))
    if (
        normalized.get("schema_version") != "evidence-assessment-work-item.v1"
        or normalized.get("execution_scope") != _TARGET_SCOPE
        or not isinstance(normalized.get("command_binding"), dict)
        or not isinstance(normalized.get("item"), dict)
        or not isinstance(normalized.get("profile_versions"), dict)
    ):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_WORK_ITEM_INVALID")
    return normalized


def _require_sorted_identifiers(values: Sequence[str], field: str) -> None:
    if (
        list(values) != sorted(set(values))
        or any(_IDENTIFIER.fullmatch(value) is None for value in values)
    ):
        raise ValueError(f"{field} must contain sorted bounded identifiers")


__all__ = [
    "EvidenceAssessmentDraft",
    "EvidenceCandidateFactLink",
    "TargetEvidenceAssessmentLCEL",
    "TargetEvidenceAsset",
    "TargetEvidenceAssetLoader",
    "build_target_evidence_assessment_lcel",
]
