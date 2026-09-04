from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from app.agents.evidence_clerk.v2_contracts import (
    EvidenceFrameObjectV2,
    EvidenceMaterialReviewNoObservationStreamV2,
    EvidenceMaterialReviewStreamV2,
    EvidenceRoomOpeningStreamV2,
    leading_evidence_frame_header_v2,
)
from app.agents.evidence_clerk.v2_policy import EvidenceV2PublicOutputPolicy
from app.agents.evidence_clerk.v2_workflow import (
    EvidenceTurnWorkflowV2,
    _authority_bound_output_type,
    _bind_v2_assessment_observation_slots,
    _bind_v2_room_readiness_fact_ids,
    _bind_v2_transport_sequences,
    _output_type_for_mode,
    _validate_v2_authority,
)
from app.harness.evidence_asset_loader import (
    EvidenceAssetLoader,
    validated_evidence_asset_manifest,
)
from app.harness.evidence_room_context_v2 import (
    assemble_evidence_room_context_v2,
    finalize_evidence_room_sources_v2,
)
from app.harness.model_runner import (
    HarnessGeneration,
    HarnessStreamCompleted,
    HarnessStreamDelta,
)
from app.contracts.v1.models import InvocationContext, RetryBudget
from app.graph_runtime.evidence_turn_executor import (
    CompiledEvidenceTurnExecutor,
    _EvidencePreviewBridge,
)
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.executor import ProductionSpecializedRoomProviderFactory
from app.graph_runtime.production_bindings import _build_production_runtime_evidence_workflow
from app.schemas import EvidenceTurnRequest
from app.streaming import AgentStreamObserver, bind_stream_observer

sys.path.insert(0, str(Path(__file__).parent))
from test_evidence_clerk_turn import (  # noqa: E402
    _freeze_bound_evidence_turn_payload,
    _java_evidence_opening_command_payload,
    _java_evidence_turn_command_payload,
)


def _provider_binding_execution(prompt_profile_id: str) -> SimpleNamespace:
    invocation = InvocationContext(
        agent_profile_id="all-rooms-agent.production-runtime.v1",
        prompt_profile_id=prompt_profile_id,
        model_profile_id="production-runtime.formal-evidence",
        output_schema_version="production-runtime-room-proposal-source.v2",
        policy_version="production-runtime.proposal-only.v1",
        guardrail_version="evidence-guardrail.v1",
        tool_capabilities=(),
        envelope_key_id="JAVA_GRAPH_COMMAND_ES256_TEST",
        envelope_nonce="NONCE_EVIDENCE_PROMPT_BINDING_TEST",
    )
    command = SimpleNamespace(
        invocation_context=invocation,
        retry_budget=RetryBudget(
            provider_attempts_remaining=1,
            activity_attempts_remaining=1,
            repairs_remaining=0,
        ),
        deadline_at=datetime(2026, 8, 19, tzinfo=timezone.utc),
        traceparent="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    )
    return SimpleNamespace(admission=SimpleNamespace(command=command))


def test_target_evidence_provider_prompt_requires_single_command_authority() -> None:
    prompt_version = "all-rooms-prompt.production-runtime.v2"
    execution = _provider_binding_execution(prompt_version)
    legacy_document = _java_evidence_opening_command_payload()
    legacy_request = EvidenceTurnRequest.model_validate(legacy_document)
    assert legacy_request.agent_context.prompt_profile_id == "EVIDENCE_CLERK:USER:v1"

    with pytest.raises(
        GraphContractError,
        match="EVIDENCE_TURN_MODEL_INVOCATION_BINDING_INVALID",
    ):
        CompiledEvidenceTurnExecutor._provider_governed_request(
            execution,
            legacy_request,
        )

    bound_document = _java_evidence_opening_command_payload()
    bound_document["agent_context"]["prompt_profile_id"] = prompt_version
    bound_document["context_envelope"]["actor_snapshot"]["prompt_profile_id"] = prompt_version
    bound_request = EvidenceTurnRequest.model_validate(bound_document)
    provider_request = CompiledEvidenceTurnExecutor._provider_governed_request(
        execution,
        bound_request,
    )

    assert provider_request.agent_context.prompt_profile_id == prompt_version
    assert bound_request.agent_context.model_profile_id is None
    assert provider_request.agent_context.model_profile_id == "production-runtime.formal-evidence"


def _opening_frames(request: EvidenceTurnRequest) -> list[dict[str, object]]:
    facts = [
        item["fact_id"]
        for item in assemble_evidence_room_context_v2(request).base.working_set.allowed_fact_targets
    ]
    headers = [
        {
            "frame_sequence": 2,
            "frame_type": "OPENING_ORIENTATION",
            "focus_fact_ids": [facts[0]],
        },
        {
            "frame_sequence": 3,
            "frame_type": "EVIDENCE_REQUEST",
            "request_slot": "REQ_1",
            "target_fact_ids": [facts[1]],
            "requested_material_kind": "ORDER_RECORD",
            "priority": "HIGH",
            "reason": "核验订单记录",
        },
        {
            "frame_sequence": 4,
            "frame_type": "EVIDENCE_REQUEST",
            "request_slot": "REQ_2",
            "target_fact_ids": [facts[2]],
            "requested_material_kind": "LOGISTICS_RECORD",
            "priority": "HIGH",
            "reason": "核验物流记录",
        },
        {
            "frame_sequence": 5,
            "frame_type": "ROOM_READINESS",
            "core_fact_coverage": "NONE",
            "source_chain_coverage": "NONE",
            "time_integrity_coverage": "UNKNOWN",
            "overall_readiness": "NOT_READY",
            "remaining_core_fact_ids": facts,
        },
    ]
    return [
        {
            "header": header,
            "public_text": f"本案第{header['frame_sequence']}帧。",
        }
        for header in headers
    ]


def test_v2_provider_schema_discriminates_frame_headers_before_streaming() -> None:
    schema = EvidenceRoomOpeningStreamV2.model_json_schema()
    assert list(schema["properties"]) == [
        "schema_version",
        "lead_public_text",
        "frames",
    ]
    assert schema["properties"]["lead_public_text"] == {
        "maxLength": 100_000,
        "minLength": 1,
        "title": "Lead Public Text",
        "type": "string",
    }
    frame_schema = schema["$defs"]["EvidenceRoomOpeningFrameObjectV2"]
    assert list(frame_schema["properties"]) == ["header", "public_text"]
    assert frame_schema["additionalProperties"] is False
    assert set(frame_schema["required"]) == {"header", "public_text"}
    header_schema = frame_schema["properties"]["header"]

    assert header_schema["discriminator"]["propertyName"] == "frame_type"
    mapping = header_schema["discriminator"]["mapping"]
    assert set(mapping) == {
        "OPENING_ORIENTATION",
        "EVIDENCE_REQUEST",
        "ROOM_READINESS",
    }
    assert frame_schema["properties"]["public_text"] == {
        "maxLength": 100_000,
        "minLength": 1,
        "title": "Public Text",
        "type": "string",
    }
    assert "prefixItems" not in json.dumps(schema, sort_keys=True)

    material_schema = EvidenceMaterialReviewStreamV2.model_json_schema()
    public_branch = material_schema["$defs"][
        "EvidenceMaterialReviewPublicFrameObjectV2"
    ]
    assert list(public_branch["properties"]) == ["header", "public_text"]
    assert set(public_branch["properties"]["header"]["discriminator"]["mapping"]) == {
        "EVIDENCE_OBSERVATION",
        "EVIDENCE_ASSESSMENT",
        "EVIDENCE_REQUEST",
        "ROOM_READINESS",
    }
    serialized_material_schema = json.dumps(material_schema, sort_keys=True)
    assert "HUMAN_REVIEW_TASK" not in serialized_material_schema
    assert "human_review_status" not in serialized_material_schema
    assessment_properties = material_schema["$defs"][
        "EvidenceAssessmentFrameHeaderV2"
    ]["properties"]
    assert "authenticity_score" in assessment_properties
    assert "authenticity_score_explanation" in assessment_properties
    assert assessment_properties["risk_level"]["enum"] == ["LOW", "MEDIUM", "HIGH"]
    request_schema = material_schema["$defs"]["EvidenceRequestFrameHeaderV2"]
    assert "target_fact_ids" in request_schema["required"]
    assert request_schema["properties"]["target_fact_ids"]["minItems"] == 1

    orientation = schema["$defs"][mapping["OPENING_ORIENTATION"].rsplit("/", 1)[-1]]
    assert orientation["additionalProperties"] is False
    assert set(orientation["properties"]) == {
        "frame_sequence",
        "frame_type",
        "focus_fact_ids",
    }
    assert set(orientation["required"]) == {
        "frame_sequence",
        "frame_type",
        "focus_fact_ids",
    }


def test_v2_provider_schema_binds_frozen_invocation_authority_ids() -> None:
    opening_request = EvidenceTurnRequest.model_validate(
        _java_evidence_opening_command_payload()
    )
    opening = assemble_evidence_room_context_v2(opening_request)
    opening_fact_ids = [
        item["fact_id"] for item in opening.base.working_set.allowed_fact_targets
    ]
    opening_schema = _authority_bound_output_type(
        EvidenceRoomOpeningStreamV2,
        opening,
    ).model_json_schema()

    opening_defs = opening_schema["$defs"]
    assert opening_defs["EvidenceOpeningOrientationFrameHeaderV2"]["properties"][
        "focus_fact_ids"
    ]["items"]["enum"] == opening_fact_ids
    assert opening_defs["EvidenceRequestFrameHeaderV2"]["properties"][
        "target_fact_ids"
    ]["items"]["enum"] == opening_fact_ids
    assert opening_defs["EvidenceRoomReadinessFrameHeaderV2"]["properties"][
        "remaining_core_fact_ids"
    ]["items"]["enum"] == opening_fact_ids
    assert "enum" not in (
        EvidenceRoomOpeningStreamV2.model_json_schema()["$defs"]
        ["EvidenceOpeningOrientationFrameHeaderV2"]["properties"]
        ["focus_fact_ids"]["items"]
    )

    material_document = _java_evidence_turn_command_payload()
    visible = material_document["context_envelope"]["visible_evidence"][0]
    parsed_text = "物流记录显示该包裹已于约定时间交付。"
    file_sha256 = "a" * 64
    parsed_content_sha256 = hashlib.sha256(parsed_text.encode("utf-8")).hexdigest()
    visible.update(
        {
            "content_type": "text/markdown",
            "file_hash": file_sha256,
            "parsed_text": parsed_text,
            "parse_status": "SUCCEEDED",
        }
    )
    material_document["context_envelope"]["evidence_content_authorities"] = [
        {
            "schema_version": "evidence_content_authority.v1",
            "case_id": material_document["context_envelope"]["case_snapshot"][
                "case_id"
            ],
            "evidence_id": visible["evidence_id"],
            "file_sha256": file_sha256,
            "content_type": "text/markdown",
            "parser_version": "PARSER_SCHEMA_BINDING_TEST_V1",
            "parsed_content_sha256": parsed_content_sha256,
            "parsed_text": parsed_text,
            "parsed_byte_length": len(parsed_text.encode("utf-8")),
            "completed_at": "2026-08-19T10:00:00+08:00",
            "status": "SUCCEEDED",
        }
    ]
    material_request = EvidenceTurnRequest.model_validate(material_document)
    material = assemble_evidence_room_context_v2(material_request)
    material_fact_ids = [
        item["fact_id"] for item in material.base.working_set.allowed_fact_targets
    ]
    attachment_ids = list(material.base.raw_envelope.current_event.attachment_refs)
    source_unit_ids = [item["source_unit_id"] for item in material.source_units]
    assert source_unit_ids

    material_schema = _authority_bound_output_type(
        EvidenceMaterialReviewStreamV2,
        material,
    ).model_json_schema()
    material_defs = material_schema["$defs"]
    assert material_defs["EvidenceObservationFrameHeaderV2"]["properties"][
        "source_unit_id"
    ]["enum"] == source_unit_ids
    assert material_defs["EvidenceObservationFrameHeaderV2"]["properties"][
        "candidate_fact_ids"
    ]["items"]["enum"] == material_fact_ids
    assert material_defs["EvidenceFactBindingV2"]["properties"]["fact_id"][
        "enum"
    ] == material_fact_ids
    assert material_defs["EvidenceAssessmentFrameHeaderV2"]["properties"][
        "evidence_id"
    ]["enum"] == attachment_ids
    assert material_defs["EvidenceRequestFrameHeaderV2"]["properties"][
        "target_fact_ids"
    ]["items"]["enum"] == material_fact_ids
    assert material_defs["EvidenceRoomReadinessFrameHeaderV2"]["properties"][
        "remaining_core_fact_ids"
    ]["items"]["enum"] == material_fact_ids


def _authorized_image_material_fixture() -> tuple[EvidenceTurnRequest, bytes]:
    image = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    document = _java_evidence_turn_command_payload()
    visible = document["context_envelope"]["visible_evidence"][0]
    visible.update(
        {
            "content_type": "image/png",
            "file_size": len(image),
            "file_hash": hashlib.sha256(image).hexdigest(),
            "parsed_text": None,
            "parse_status": "PENDING",
            "desensitized": True,
        }
    )
    document["context_envelope"]["evidence_content_authorities"] = []
    return EvidenceTurnRequest.model_validate(document), image


def _loaded_image_assets(request: EvidenceTurnRequest, image: bytes):
    return EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, content=image)
        ),
    ).load(request.context_envelope)


def test_v3_loaded_image_pixels_become_bound_observation_source_authority() -> None:
    request, image = _authorized_image_material_fixture()
    base = assemble_evidence_room_context_v2(request)
    assert base.source_units == ()

    loaded = _loaded_image_assets(request, image)
    assembled = finalize_evidence_room_sources_v2(
        base,
        validated_evidence_asset_manifest(loaded),
    )

    assert len(assembled.source_units) == 1
    source = assembled.source_units[0]
    evidence_id = request.context_envelope.current_event.attachment_refs[0]
    assert source["evidence_id"] == evidence_id
    assert source["basis"] == "IMAGE_PIXELS"
    assert source["authority"]["content_sha256"] == hashlib.sha256(image).hexdigest()
    assert assembled.payload["turn_contract"]["allow_observation"] is True
    assert assembled.payload["current_evidence_batch"][0]["visual_input_status"] == "LOADED"

    output_type = _authority_bound_output_type(
        _output_type_for_mode("MATERIAL_REVIEW", allow_observation=True),
        assembled,
    )
    schema = output_type.model_json_schema()
    source_schema = schema["$defs"]["EvidenceObservationFrameHeaderV2"][
        "properties"
    ]["source_unit_id"]
    assert source_schema["enum"] == [source["source_unit_id"]]

    policy = EvidenceV2PublicOutputPolicy()
    policy.configure(assembled)
    projected = policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="lead_public_text",
        delta="已收到图片材料，正在核验画面内容。",
    )
    assert projected
    fact_id = assembled.base.working_set.allowed_fact_targets[0]["fact_id"]
    projected = policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
        delta=_event(
            "frame_start",
            2,
            header={
                "frame_sequence": 2,
                "frame_type": "EVIDENCE_OBSERVATION",
                "observation_slot": "OBS_IMAGE_1",
                "source_unit_id": source["source_unit_id"],
                "binding_status": "BOUND",
                "fact_bindings": [
                    {
                        "fact_id": fact_id,
                        "relation": "CONTENT_SUPPORTS",
                        "reason": "图片可见内容与该冻结事实相关。",
                    }
                ],
                "candidate_fact_ids": [],
                "binding_reason": "图片像素直接呈现该项材料内容。",
                "observation_kind": "IMAGE_PIXELS",
                "epistemic_status": "PROVISIONAL",
            },
        ),
    )
    assert any(field == "frame.2.header" for field, _ in projected)


def test_v3_material_without_source_authority_makes_observation_provider_inaccessible() -> None:
    request, _ = _authorized_image_material_fixture()
    assembled = finalize_evidence_room_sources_v2(
        assemble_evidence_room_context_v2(request),
        None,
    )

    assert assembled.source_units == ()
    assert assembled.payload["turn_contract"]["allow_observation"] is False
    assert "EVIDENCE_OBSERVATION" not in assembled.payload["turn_contract"][
        "allowed_frame_types"
    ]
    output_type = _output_type_for_mode(
        "MATERIAL_REVIEW",
        allow_observation=False,
    )
    assert output_type is EvidenceMaterialReviewNoObservationStreamV2
    assert "EVIDENCE_OBSERVATION" not in json.dumps(
        _authority_bound_output_type(output_type, assembled).model_json_schema(),
        sort_keys=True,
    )


def _material_review_validation_fixture(
    assessment_observation_slots: list[str],
    *,
    binding_status: str = "UNRELATED",
) -> tuple[object, EvidenceMaterialReviewStreamV2]:
    document = _java_evidence_turn_command_payload()
    visible = document["context_envelope"]["visible_evidence"][0]
    parsed_text = "附件正文记录了一项与当前争议关联度较低的材料内容。"
    file_sha256 = "b" * 64
    visible.update(
        {
            "content_type": "text/markdown",
            "file_hash": file_sha256,
            "parsed_text": parsed_text,
            "parse_status": "SUCCEEDED",
        }
    )
    document["context_envelope"]["evidence_content_authorities"] = [
        {
            "schema_version": "evidence_content_authority.v1",
            "case_id": document["context_envelope"]["case_snapshot"]["case_id"],
            "evidence_id": visible["evidence_id"],
            "file_sha256": file_sha256,
            "content_type": "text/markdown",
            "parser_version": "PARSER_ASSESSMENT_SLOT_TEST_V1",
            "parsed_content_sha256": hashlib.sha256(
                parsed_text.encode("utf-8")
            ).hexdigest(),
            "parsed_text": parsed_text,
            "parsed_byte_length": len(parsed_text.encode("utf-8")),
            "completed_at": "2026-08-20T10:00:00+08:00",
            "status": "SUCCEEDED",
        }
    ]
    request = EvidenceTurnRequest.model_validate(document)
    assembled = assemble_evidence_room_context_v2(request)
    evidence_id = str(visible["evidence_id"])
    source_unit_id = str(assembled.source_units[0]["source_unit_id"])
    fact_ids = [
        item["fact_id"] for item in assembled.base.working_set.allowed_fact_targets
    ]
    stream = EvidenceMaterialReviewStreamV2.model_validate(
        {
            "lead_public_text": "已收到本批材料，开始核验。",
            "frames": [
                {
                    "header": {
                        "frame_sequence": 2,
                        "frame_type": "EVIDENCE_OBSERVATION",
                        "observation_slot": "OBS_1",
                        "source_unit_id": source_unit_id,
                        "binding_status": binding_status,
                        "fact_bindings": (
                            [
                                {
                                    "fact_id": fact_ids[0],
                                    "relation": "CONTENT_SUPPORTS",
                                    "reason": "材料内容支持该冻结事实。",
                                }
                            ]
                            if binding_status == "BOUND"
                            else []
                        ),
                        "candidate_fact_ids": [],
                        "binding_reason": "材料内容与当前争议事实没有直接对应关系。",
                        "observation_kind": "PARSED_RECORD",
                        "epistemic_status": "PROVISIONAL",
                    },
                    "public_text": "该附件内容与当前争议事实没有直接对应关系。",
                },
                {
                    "header": {
                        "frame_sequence": 3,
                        "frame_type": "EVIDENCE_ASSESSMENT",
                        "evidence_id": evidence_id,
                        "observation_slots": assessment_observation_slots,
                        "authenticity_score": 0.72,
                        "authenticity_score_explanation": "材料来源可识别，但没有平台原始导出链。",
                        "relevance_score": 0.31,
                        "relevance_score_explanation": "材料正文没有直接覆盖当前冻结争议事实。",
                        "completeness_score": 0.68,
                        "completeness_score_explanation": "正文可读，但缺少形成上下文。",
                        "assessment_confidence": 0.86,
                        "assessment_confidence_explanation": "解析文本清晰，可判断当前文本范围。",
                        "risk_level": "MEDIUM",
                        "risk_explanation": "材料可读但来源链和事实关联均存在缺口。",
                        "source_basis": ["已读取当前附件解析正文。"],
                        "formation_time_assessment": "正文未提供可核对的形成时间。",
                        "findings": [
                            {
                                "finding_type": "PARSED_TEXT",
                                "description": "附件正文可以完整读取。",
                            }
                        ],
                        "limitations": ["仅能核验文本内容。"],
                        "unsupported_claims": ["不能单独证明核心争议事实。"],
                    },
                    "public_text": "材料可读，但与当前争议关联度较低。",
                },
                {
                    "header": {
                        "frame_sequence": 4,
                        "frame_type": "ROOM_READINESS",
                        "core_fact_coverage": "NONE",
                        "source_chain_coverage": "PARTIAL",
                        "time_integrity_coverage": "UNKNOWN",
                        "unresolved_conflicts": [],
                        "remaining_core_fact_ids": fact_ids,
                        "overall_readiness": "NOT_READY",
                        "readiness_reasons": ["当前材料未覆盖核心争议事实。"],
                    },
                    "public_text": "仍需补充与核心争议直接相关的材料。",
                },
            ],
        }
    )
    return assembled, stream


def test_v3_assessment_slots_are_derived_from_source_authority() -> None:
    assembled, stream = _material_review_validation_fixture([])

    _validate_v2_authority(stream, assembled)
    bound = _bind_v2_assessment_observation_slots(stream, assembled)

    assessment = next(
        frame.header
        for frame in bound.frames
        if frame.header.frame_type == "EVIDENCE_ASSESSMENT"
    )
    assert assessment.observation_slots == ["OBS_1"]


def test_v3_material_output_accepts_parsed_text_and_missing_semantic_fields() -> None:
    stream = EvidenceMaterialReviewStreamV2.model_validate(
        {
            "frames": [
                {
                    "header": {
                        "frame_sequence": 2,
                        "frame_type": "EVIDENCE_OBSERVATION",
                        "observation_kind": "PARSED_TEXT",
                    },
                    "public_text": "已读取文本材料。",
                },
                {
                    "header": {
                        "frame_sequence": 3,
                        "frame_type": "EVIDENCE_ASSESSMENT",
                        "risk_level": "MODEL_DEFINED_LEVEL",
                    },
                    "public_text": None,
                },
            ]
        }
    )

    rebound = _bind_v2_transport_sequences(stream)

    observation = rebound.frames[0].header
    assessment = rebound.frames[1].header
    assert observation.frame_sequence == 2
    assert observation.observation_kind == "PARSED_TEXT"
    assert observation.binding_status is None
    assert observation.fact_bindings == []
    assert assessment.frame_sequence == 3
    assert assessment.evidence_id is None
    assert assessment.risk_level == "MODEL_DEFINED_LEVEL"
    assert rebound.frames[1].public_text is None


def test_internal_leading_frame_preserves_authoritative_transport_identity() -> None:
    header = leading_evidence_frame_header_v2("ROOM_OPENING")

    frame = EvidenceFrameObjectV2(header=header, public_text="欢迎进入证据室。")

    assert frame.header.frame_sequence == 1
    assert frame.header.frame_type == "ROOM_WELCOME"


def test_v3_public_policy_streams_parsed_text_without_semantic_header_rejection() -> None:
    assembled, stream = _material_review_validation_fixture([])
    observation = stream.frames[0].header.model_dump(
        mode="json",
        exclude_none=True,
        exclude_defaults=True,
    )
    observation["observation_kind"] = "PARSED_TEXT"
    observation.pop("binding_status", None)
    observation.pop("epistemic_status", None)
    policy = EvidenceV2PublicOutputPolicy()
    policy.configure(assembled)
    policy.begin(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
    )
    policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="lead_public_text",
        delta="已收到文本材料。",
    )

    projected = policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
        delta=_event("frame_start", 2, header=observation),
    )

    header = json.loads(next(value for field, value in projected if field.endswith(".header")))
    assert header["observation_kind"] == "PARSED_TEXT"
    assert "binding_status" not in header
    assert "epistemic_status" not in header


def test_v3_provider_assessment_slot_guess_is_replaced_by_source_authority() -> None:
    assembled, stream = _material_review_validation_fixture(["OBS_UNKNOWN"])

    _validate_v2_authority(stream, assembled)
    bound = _bind_v2_assessment_observation_slots(stream, assembled)

    assessment = next(
        frame.header
        for frame in bound.frames
        if frame.header.frame_type == "EVIDENCE_ASSESSMENT"
    )
    assert assessment.observation_slots == ["OBS_1"]


def test_v3_repeated_model_observation_slot_remains_authoritative() -> None:
    assembled, stream = _material_review_validation_fixture([])
    document = stream.model_dump(mode="json")
    duplicate = json.loads(json.dumps(document["frames"][0]))
    duplicate["header"]["frame_sequence"] = 3
    for frame in document["frames"][1:]:
        frame["header"]["frame_sequence"] += 1
    document["frames"].insert(1, duplicate)
    repeated = EvidenceMaterialReviewStreamV2.model_validate(document)

    _validate_v2_authority(repeated, assembled)
    bound = _bind_v2_assessment_observation_slots(repeated, assembled)

    assessment = next(
        frame.header
        for frame in bound.frames
        if frame.header.frame_type == "EVIDENCE_ASSESSMENT"
    )
    assert assessment.observation_slots == ["OBS_1", "OBS_1"]


def test_v3_bound_observation_is_attached_before_terminal_materialization() -> None:
    assembled, stream = _material_review_validation_fixture(
        [],
        binding_status="BOUND",
    )

    _validate_v2_authority(stream, assembled)
    bound = _bind_v2_assessment_observation_slots(stream, assembled)

    assessment = next(
        frame.header
        for frame in bound.frames
        if frame.header.frame_type == "EVIDENCE_ASSESSMENT"
    )
    assert assessment.observation_slots == ["OBS_1"]


def test_v3_live_and_terminal_assessment_headers_share_derived_binding() -> None:
    assembled, stream = _material_review_validation_fixture(
        [],
        binding_status="BOUND",
    )
    policy = EvidenceV2PublicOutputPolicy()
    policy.configure(assembled)
    policy.begin(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
    )
    policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="lead_public_text",
        delta=stream.lead_public_text,
    )

    assessment_header = None
    for frame in stream.frames:
        sequence = frame.header.frame_sequence
        projected = policy.project_event(
            operation="evidence_turn",
            node_name="evidence_turn",
            field_name="frames",
            delta=_event(
                "frame_start",
                sequence,
                header=frame.header.model_dump(
                    mode="json",
                    exclude_none=True,
                    exclude_defaults=True,
                ),
            ),
        )
        if frame.header.frame_type == "EVIDENCE_ASSESSMENT":
            assessment_header = json.loads(
                next(value for field, value in projected if field.endswith(".header"))
            )
        policy.project_event(
            operation="evidence_turn",
            node_name="evidence_turn",
            field_name="frames",
            delta=_event(
                "public_text_delta",
                sequence,
                delta=frame.public_text,
            ),
        )
        policy.project_event(
            operation="evidence_turn",
            node_name="evidence_turn",
            field_name="frames",
            delta=_event("frame_end", sequence),
        )

    terminal = _bind_v2_assessment_observation_slots(stream, assembled)
    terminal_header = next(
        frame.header.model_dump(
            mode="json",
            exclude_none=True,
            exclude_defaults=True,
        )
        for frame in terminal.frames
        if frame.header.frame_type == "EVIDENCE_ASSESSMENT"
    )
    assert assessment_header == terminal_header
    assert assessment_header["observation_slots"] == ["OBS_1"]


def test_v2_provider_context_keeps_semantics_without_audit_metadata() -> None:
    request = EvidenceTurnRequest.model_validate(_freeze_bound_evidence_turn_payload())
    assembled = assemble_evidence_room_context_v2(request)
    assert request.context_envelope.frozen_submission is not None
    frozen = request.context_envelope.frozen_submission.matrix
    projected = assembled.payload["frozen_case_matrix"]

    assert projected["schema_version"] == "evidence_case_matrix_context.v2"
    assert projected["source_schema_version"] == "case_fact_matrix.v2"
    assert projected["matrix_version"] == frozen["matrix_version"]
    assert projected["matrix_kind"] == frozen["matrix_kind"]
    assert projected["party_map"] == frozen["party_map"]
    assert projected["case_overview"] == frozen["case_overview"]
    assert [item["fact_id"] for item in projected["fact_rows"]] == [
        item["fact_id"] for item in frozen["fact_rows"]
    ]
    assert projected["claims"]["respondent_direct"]["attitude"] == (
        frozen["claims"]["respondent_direct"]["attitude"]
    )
    for projected_row, frozen_row in zip(
        projected["fact_rows"],
        frozen["fact_rows"],
        strict=True,
    ):
        assert projected_row["introduced_stage"] == frozen_row["origin"][
            "introduced_stage"
        ]
        assert projected_row["party_alignment"] == frozen_row["party_alignment"]
        for role in ("USER", "MERCHANT"):
            assert projected_row["positions"][role] == {
                key: value
                for key, value in frozen_row["positions"][role].items()
                if key != "source_refs"
            }

    serialized = json.dumps(projected, ensure_ascii=False, separators=(",", ":"))
    for forbidden in (
        "content_hash",
        "fact_indexes",
        "generation_ref",
        "matrix_id",
        "parent_ref",
        "source_refs",
    ):
        assert forbidden not in serialized
    assert assembled.base.working_set.case_intake_dossier["case_fact_matrix"] == frozen


def test_v2_authority_bound_schema_omits_only_nonsemantic_titles() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    assembled = assemble_evidence_room_context_v2(request)
    base_schema = EvidenceRoomOpeningStreamV2.model_json_schema()
    bound_schema = _authority_bound_output_type(
        EvidenceRoomOpeningStreamV2,
        assembled,
    ).model_json_schema()

    assert '"title"' in json.dumps(base_schema, separators=(",", ":"))
    assert '"title"' not in json.dumps(bound_schema, separators=(",", ":"))
    assert '"description"' in json.dumps(bound_schema, separators=(",", ":"))


def _event(kind: str, sequence: int, **values: object) -> str:
    return json.dumps(
        {"kind": kind, "frame_sequence": sequence, **values},
        ensure_ascii=False,
        separators=(",", ":"),
    )


class _StreamingRunner:
    def __init__(self, stream: EvidenceRoomOpeningStreamV2) -> None:
        self.stream = stream
        self.calls = 0
        self.first_frame_sent = asyncio.Event()
        self.release = asyncio.Event()

    async def ainvoke_structured_stream(self, **_: object):
        self.calls += 1
        yield HarnessStreamDelta(
            kind="visible_delta",
            field="lead_public_text",
            delta=self.stream.lead_public_text,
        )
        self.first_frame_sent.set()
        await self.release.wait()
        for frame in self.stream.frames:
            header = frame.header.model_dump(
                mode="json",
                exclude_none=True,
                exclude_defaults=True,
            )
            sequence = header["frame_sequence"]
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="frames",
                delta=_event("frame_start", sequence, header=header),
            )
            assert frame.public_text is not None
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="frames",
                delta=_event("public_text_delta", sequence, delta=frame.public_text),
            )
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="frames",
                delta=_event("frame_end", sequence),
            )
        yield HarnessStreamCompleted(
            kind="completed",
            generation=HarnessGeneration(
                value=self.stream,
                model="v2-test-model",
                latency_ms=1,
                token_usage={"input": 1, "output": 1, "total": 2},
                context=None,
                messages=(),
            ),
        )


@pytest.mark.asyncio
async def test_v2_workflow_releases_first_frame_before_terminal_json_and_is_single_call() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    frames = _opening_frames(request)
    lead_public_text = "欢迎进入证据室，本轮将按冻结案情梳理证据。"
    stream = EvidenceRoomOpeningStreamV2.model_validate(
        {"lead_public_text": lead_public_text, "frames": frames}
    )
    runner = _StreamingRunner(stream)
    policy = EvidenceV2PublicOutputPolicy()
    events: list[object] = []
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="V2_WORKFLOW_STREAM_TEST",
        publish=events.append,
        public_output_policy=policy,
    )
    observer.begin_public_output("evidence_turn", "frames")

    with bind_stream_observer(observer):
        task = asyncio.create_task(EvidenceTurnWorkflowV2(model_runner=runner).arun(request))
        await asyncio.wait_for(runner.first_frame_sent.wait(), timeout=1)
        await asyncio.sleep(0)
        assert not task.done()
        assert any(
            getattr(event, "field", None) == "frame.1.public_text"
            for event in events
        )
        runner.release.set()
        result = await asyncio.wait_for(task, timeout=1)

    observer.finalize_public_output("evidence_turn", "frames", result.room_utterance)
    assert runner.calls == 1
    assert result.room_utterance == "\n\n".join(
        [lead_public_text, *(str(frame["public_text"]) for frame in frames)]
    )
    assert result.frame_manifest_sha256


@pytest.mark.asyncio
async def test_v3_workflow_binds_missing_readiness_to_empty_projection() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    frames = _opening_frames(request)[:-1]
    stream = EvidenceRoomOpeningStreamV2.model_validate(
        {
            "lead_public_text": "欢迎进入证据室，本轮将按冻结案情梳理证据。",
            "frames": frames,
        }
    )
    runner = _StreamingRunner(stream)
    runner.release.set()

    result = await EvidenceTurnWorkflowV2(model_runner=runner).arun(request)

    assert runner.calls == 1
    assert result.room_readiness == {}
    assert all(
        frame.frame_type != "ROOM_READINESS" for frame in result.frame_manifest
    )
    assert result.evidence_requests == [
        frame.header.model_dump(
            mode="json",
            exclude_none=True,
            exclude_defaults=True,
        )
        for frame in stream.frames
        if frame.header.frame_type == "EVIDENCE_REQUEST"
    ]


@pytest.mark.asyncio
async def test_v2_workflow_rejects_out_of_scope_focus_fact_before_public_text() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    frames = _opening_frames(request)
    assert isinstance(frames[0]["header"], dict)
    frames[0]["header"]["focus_fact_ids"] = ["FACT_NOT_IN_FROZEN_MATRIX"]
    stream = EvidenceRoomOpeningStreamV2.model_validate(
        {"lead_public_text": "欢迎进入证据室。", "frames": frames}
    )
    runner = _StreamingRunner(stream)
    policy = EvidenceV2PublicOutputPolicy()
    events: list[object] = []
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="V2_WORKFLOW_SCOPE_TEST",
        publish=events.append,
        public_output_policy=policy,
    )
    observer.begin_public_output("evidence_turn", "frames")

    with bind_stream_observer(observer):
        runner.release.set()
        task = asyncio.create_task(EvidenceTurnWorkflowV2(model_runner=runner).arun(request))
        with pytest.raises(Exception, match="FACT_ID_OUT_OF_SCOPE"):
            await asyncio.wait_for(task, timeout=1)
    assert not any(
        getattr(event, "field", None) == "frame.2.public_text" for event in events
    )


@pytest.mark.asyncio
async def test_v2_workflow_projects_readiness_fact_ids_to_frozen_authority() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    assembled = assemble_evidence_room_context_v2(request)
    allowed_fact_id = assembled.base.working_set.allowed_fact_targets[0]["fact_id"]
    frames = _opening_frames(request)
    assert isinstance(frames[-1]["header"], dict)
    frames[-1]["header"]["remaining_core_fact_ids"] = [
        allowed_fact_id,
        "FACT_NOT_IN_FROZEN_MATRIX",
    ]
    stream = EvidenceRoomOpeningStreamV2.model_validate(
        {"lead_public_text": "欢迎进入证据室。", "frames": frames}
    )
    runner = _StreamingRunner(stream)
    policy = EvidenceV2PublicOutputPolicy()
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="V2_WORKFLOW_READINESS_BINDING_TEST",
        publish=lambda event: None,
        public_output_policy=policy,
    )
    observer.begin_public_output("evidence_turn", "frames")

    with bind_stream_observer(observer):
        runner.release.set()
        result = await asyncio.wait_for(
            EvidenceTurnWorkflowV2(model_runner=runner).arun(request),
            timeout=1,
        )

    assert result.room_readiness["remaining_core_fact_ids"] == [allowed_fact_id]
    assert policy.frame_records[-1]["header"]["remaining_core_fact_ids"] == [
        allowed_fact_id
    ]
    rebound = _bind_v2_room_readiness_fact_ids(stream, assembled)
    assert rebound.frames[-1].header.remaining_core_fact_ids == [allowed_fact_id]


@pytest.mark.asyncio
async def test_v2_executor_bridge_preserves_provider_deltas_and_commits_one_frame() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    assembled = assemble_evidence_room_context_v2(request)
    policy = EvidenceV2PublicOutputPolicy()
    policy.configure(assembled)
    bridge = _EvidencePreviewBridge(
        provider_request=request,
        command_id="COMMAND_EVIDENCE_V3_TEST",
        attempt_id="ATTEMPT_EVIDENCE_V3_TEST",
        policy=policy,
    )
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="RUN_EVIDENCE_V3_TEST",
        publish=bridge.publish,
        public_output_policy=policy,
    )
    bridge.bind(observer)
    observer.begin_public_output("evidence_turn", "frames")

    observer.visible_delta(
        "evidence_turn",
        "lead_public_text",
        "欢迎",
    )
    observer.visible_delta(
        "evidence_turn",
        "lead_public_text",
        "进入证据室",
    )
    focus_fact_id = assembled.base.working_set.allowed_fact_targets[0]["fact_id"]
    observer.visible_delta(
        "evidence_turn",
        "frames",
        _event(
            "frame_start",
            2,
            header={
                "frame_sequence": 2,
                "frame_type": "OPENING_ORIENTATION",
                "focus_fact_ids": [focus_fact_id],
            },
        ),
    )

    events = list(bridge.pending)
    assert [event.event_type for event in events[:4]] == [
        "public_frame_start",
        "public_text_delta",
        "public_text_delta",
        "public_frame_committed",
    ]
    assert events[4].event_type == "public_frame_start"
    frame_id = events[0].payload.frame_id
    assert frame_id and all(
        event.payload.frame_id == frame_id for event in events[:4]
    )
    assert [events[1].payload.delta_index, events[2].payload.delta_index] == [0, 1]
    assert [events[1].payload.delta, events[2].payload.delta] == [
        "欢迎",
        "进入证据室",
    ]
    assert events[3].payload.durable_cursor == "v3:ATTEMPT_EVIDENCE_V3_TEST:FRAME:1"
    assert events[3].payload.public_text_sha256 == hashlib.sha256(
        "欢迎进入证据室".encode("utf-8")
    ).hexdigest()
    assert events[3].payload.public_text_chars == len("欢迎进入证据室")


def test_v2_material_leading_frame_uses_frozen_attachment_authority() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_turn_command_payload())
    assembled = assemble_evidence_room_context_v2(request)
    assert assembled.payload["turn_contract"]["turn_mode"] == "MATERIAL_REVIEW"
    policy = EvidenceV2PublicOutputPolicy()
    policy.configure(assembled)

    projected = policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="lead_public_text",
        delta="已收到本批材料，正在按冻结案情核验。",
    )

    assert [field for field, _ in projected] == [
        "frame.1.header",
        "frame.1.public_text",
    ]
    header = json.loads(projected[0][1])
    assert header == {
        "frame_sequence": 1,
        "frame_type": "MATERIAL_RECEIPT",
        "evidence_ids": list(request.context_envelope.current_event.attachment_refs),
    }


def test_production_runtime_binding_is_exactly_the_v2_evidence_workflow() -> None:
    from types import SimpleNamespace

    from app.llm import LiteLlmProxyClient

    settings = SimpleNamespace(
        java_api_service_url="http://127.0.0.1:8080",
        java_service_secret="test-secret",
    )
    workflow = _build_production_runtime_evidence_workflow(
        settings=settings,
        structured_client=LiteLlmProxyClient(
            "http://127.0.0.1:1",
            "test-model",
            "test-key",
            1.0,
        ),
    )
    assert workflow.protocol_version == "evidence-turn-result.v3"
    assert callable(workflow.run)
    assert callable(workflow.arun)

    factory = ProductionSpecializedRoomProviderFactory(
        security_runtime=object(),
        room_exchange=object(),
    )

    class RetiredWorkflow:
        def run(self, request):
            del request

        async def arun(self, request):
            del request

    with pytest.raises(
        GraphContractError,
        match="PRODUCTION_RUNTIME_FORMAL_EVIDENCE_WORKFLOW_REQUIRED",
    ):
        factory.with_evidence_workflow(RetiredWorkflow())
