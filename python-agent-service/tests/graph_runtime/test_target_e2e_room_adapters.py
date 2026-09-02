from __future__ import annotations

import hashlib
import inspect
from datetime import datetime, timedelta, timezone

import pytest
from langchain_core.messages import HumanMessage

from app.contracts.v1.codec import canonicalize
from app.contracts.v1.models import SnapshotRef
from app.graph_runtime.intake_executor import CompiledIntakeGraphShadowExecutor
from app.graph_runtime.production_bindings import _build_target_e2e_room_providers
from app.graph_runtime.target_e2e_room_adapters import (
    TargetE2EOutcomeGraphProvider,
    TargetE2EIntakeProvider,
    TargetE2EObjectEvidenceAssetLoader,
)
from app.graph_runtime.target_e2e_fixture_transport import (
    TARGET_E2E_FIXTURE_MODEL,
    TARGET_E2E_FIXTURE_PROVIDER,
    TargetE2EDeterministicFixtureTransport,
)
from app.graphs.intake.contracts import IntakeCognitionDraft
from app.llm import GovernedProviderRequest
from app.model_runtime.transports import ModelTransportRequest
from app.schemas import FrozenIntakeSubmissionAuthorityV1


class _ObjectStore:
    def __init__(self, payload: bytes) -> None:
        self.payload = payload
        self.references: list[SnapshotRef] = []

    async def load(self, reference: SnapshotRef) -> bytes:
        self.references.append(reference)
        return self.payload

    async def put(self, **kwargs):
        raise AssertionError(kwargs)

    async def put_content_addressed(self, **kwargs):
        raise AssertionError(kwargs)


@pytest.mark.asyncio
async def test_evidence_asset_loader_reads_only_the_manifest_bound_parse_reference() -> None:
    document = {
        "schema_version": "target-e2e-evidence-asset.v1",
        "content": "inspected fixture",
        "source_refs": ["SOURCE_1"],
        "inspected_modalities": ["TEXT"],
        "receipt_ref": "RECEIPT_1",
        "receipt_hash": "1" * 64,
    }
    payload = canonicalize(document)
    store = _ObjectStore(payload)
    loader = TargetE2EObjectEvidenceAssetLoader(store)

    asset = await loader.load(
        {
            "evidence_id": "EVIDENCE_1",
            "parse_ref": "urn:synthetic-evidence-parse:fixture.json",
            "parse_hash": hashlib.sha256(payload).hexdigest(),
        }
    )

    assert asset.content == "inspected fixture"
    assert store.references[0].uri == "urn:synthetic-evidence-parse:fixture.json"
    assert store.references[0].sha256 == hashlib.sha256(payload).hexdigest()


def test_default_target_composite_requires_a_specialized_room_factory() -> None:
    source = inspect.getsource(_build_target_e2e_room_providers)

    assert "build_target_e2e_intake_provider" in source
    assert "TARGET_E2E_SPECIALIZED_ROOM_RUNTIME_REQUIRED" in source
    assert "specialized_provider_factory(kernel)" in source
    assert "for room_type in RoomType" not in source


def test_target_intake_uses_the_governed_executor_and_real_stored_object_uri() -> None:
    provider_source = inspect.getsource(TargetE2EIntakeProvider)
    executor_source = inspect.getsource(CompiledIntakeGraphShadowExecutor._target_proposal_source)

    assert "CompiledIntakeGraphShadowExecutor" in provider_source
    assert "proposal_id=f\"target-proposal.{stored.sha256[:32]}\"" in executor_source
    assert "payload_ref=f\"urn:target-e2e:proposal:intake:{stored.sha256}\"" in executor_source


def test_review_target_result_projects_the_exchange_persisted_proposal_as_a_patch() -> None:
    source = inspect.getsource(TargetE2EOutcomeGraphProvider.stream)

    assert 'operation="PROPOSE_PATCH"' in source
    assert "artifact_id=proposal.proposal_id" in source
    assert "schema_version=proposal.payload_schema_version" in source
    assert "uri=proposal.payload_ref" in source
    assert "sha256=proposal.payload_hash" in source


@pytest.mark.asyncio
async def test_target_evidence_runs_formal_turn_and_replays_exact_guarded_utterance() -> None:
    import asyncio
    import json
    import runpy
    from copy import deepcopy
    from dataclasses import replace
    from pathlib import Path
    from types import SimpleNamespace

    from langgraph.checkpoint.memory import InMemorySaver

    from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting
    from app.contracts.v1.models import RoomGraphCommand, SnapshotRef
    from app.graph_runtime.checkpoint import ExternalTerminalCommit
    from app.graph_runtime.errors import GraphContractError
    from app.graph_runtime.executor import TargetE2ESpecializedRoomProviderFactory
    from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
    from app.graph_runtime.production_bindings import _build_target_e2e_evidence_workflow
    from app.agents.evidence_clerk.public_reply import (
        EVIDENCE_CANONICAL_OPENING,
        EvidencePublicObservationAuthorityError,
        EvidencePublicOutputPolicyError,
        build_submission_observation_catalog,
        compose_evidence_opening_public_reply,
        compose_evidence_submission_public_reply,
        validate_public_observation_prefix,
    )
    from app.agents.evidence_clerk.assessment_policy import (
        recover_parsed_text_fact_coordinate,
    )
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.harness.model_runner import (
        HarnessGeneration,
    )
    from app.schemas import (
        EvidenceTurnRequest,
        EvidenceTurnResult,
        PublicEvidenceObservationCoordinateProposalV1,
    )
    from app.security.graph_runtime import GraphSecurityRuntime
    from app.streaming import (
        STREAM_MAX_VISIBLE_OUTPUT_CHARS,
        IncrementalVisibleJsonProjector,
        VisibleFieldSpec,
        current_stream_observer,
    )
    from app.llm import LiteLlmProxyClient

    helper_module = runpy.run_path(
        str(Path(__file__).parents[1] / "agents" / "test_evidence_clerk_turn.py")
    )
    settings = helper_module["_settings"]()
    shared_client = LiteLlmProxyClient(
        settings.resolved_llm_base_url,
        settings.resolved_llm_model,
        settings.resolved_llm_api_key,
        settings.llm_timeout_seconds,
    )
    production_workflow = _build_target_e2e_evidence_workflow(
        settings=settings,
        structured_client=shared_client,
    )
    assert callable(getattr(production_workflow, "run", None))
    assert callable(getattr(production_workflow, "arun", None))
    request_document = helper_module["_java_evidence_turn_command_payload"]()
    submission_observation = (
        "页面日期显示2026-08-13，形成时间仍需与平台记录核对"
    )
    second_submission_observation = "退款工单未生成"
    capability_observation = (
        "当前多模态核验仅接收受控图片输入；该格式需要人工复核"
    )
    submitted_evidence = request_document["context_envelope"]["visible_evidence"][0]
    submitted_evidence.update(
        {
            "evidence_type": "DOCUMENT",
            "content_type": "text/markdown",
            "original_filename": "refund-record.md",
            "file_hash": hashlib.sha256(b"refund-record-content").hexdigest(),
            "parsed_text": (
                "# 退款记录核对摘录\n"
                f"- {submission_observation}\n"
                "- 承诺退款金额为20元\n"
                f"- {second_submission_observation}\n"
                "- 未发现成功退款流水"
            ),
            "desensitized": True,
            "metadata": {"claimed_fact": "承诺退款金额为20元"},
        }
    )
    second_evidence = deepcopy(submitted_evidence)
    second_evidence.update(
        {
            "evidence_id": "EVIDENCE_recipient_record",
            "original_filename": "recipient-record.md",
            "file_hash": hashlib.sha256(b"recipient-record-content").hexdigest(),
            "parsed_text": "收件记录摘要\n收件人称本人及同住人员未收到包裹\n仍需核对物流底单",
            "metadata": {"claimed_fact": "本人及同住人员未收到包裹"},
        }
    )
    request_document["context_envelope"]["visible_evidence"].append(
        second_evidence
    )
    request_document["context_envelope"]["current_event"][
        "attachment_refs"
    ].append(second_evidence["evidence_id"])
    dossier = request_document["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier.pop("unilateral_case_matrix", None)
    case_fact_matrix = helper_module["_case_fact_matrix_v2"]()
    case_fact_matrix["fact_rows"][0]["fact_target"] = (
        "退款工单未生成，且未发现成功退款流水"
    )
    second_fact_row = deepcopy(case_fact_matrix["fact_rows"][0])
    second_fact_row["fact_id"] = "FACT_RECIPIENT"
    second_fact_row["fact_target"] = "收件人称本人及同住人员未收到包裹"
    case_fact_matrix["fact_rows"].append(second_fact_row)
    case_fact_matrix["case_overview"]["summary_source_fact_ids"].append(
        "FACT_RECIPIENT"
    )
    helper_module["_rehash_case_fact_matrix"](case_fact_matrix)
    dossier["case_fact_matrix"] = case_fact_matrix

    arbitrary_safe_submission_sentence = "本轮正在核对材料形成时间。"
    risky_sentence = "不判断责任但实际由商家承担。"
    raw_room_utterance = (
        arbitrary_safe_submission_sentence + risky_sentence
    )
    parsed_text = submitted_evidence["parsed_text"]
    assert isinstance(parsed_text, str)
    parsed_content_sha256 = hashlib.sha256(parsed_text.encode("utf-8")).hexdigest()
    second_parsed_text = second_evidence["parsed_text"]
    assert isinstance(second_parsed_text, str)
    second_parsed_content_sha256 = hashlib.sha256(
        second_parsed_text.encode("utf-8")
    ).hexdigest()
    request_document["context_envelope"]["evidence_content_authorities"] = [
        {
            "schema_version": "evidence_content_authority.v1",
            "case_id": request_document["context_envelope"]["case_snapshot"][
                "case_id"
            ],
            "evidence_id": "EVIDENCE_signature_photo",
            "file_sha256": submitted_evidence["file_hash"],
            "content_type": "text/markdown",
            "parser_version": "PARSER_TEST_V1",
            "parsed_content_sha256": parsed_content_sha256,
            "parsed_text": parsed_text,
            "parsed_byte_length": len(parsed_text.encode("utf-8")),
            "completed_at": "2026-08-17T10:00:00+08:00",
            "status": "SUCCEEDED",
        },
        {
            "schema_version": "evidence_content_authority.v1",
            "case_id": request_document["context_envelope"]["case_snapshot"][
                "case_id"
            ],
            "evidence_id": "EVIDENCE_recipient_record",
            "file_sha256": second_evidence["file_hash"],
            "content_type": "text/markdown",
            "parser_version": "PARSER_TEST_V1",
            "parsed_content_sha256": second_parsed_content_sha256,
            "parsed_text": second_parsed_text,
            "parsed_byte_length": len(second_parsed_text.encode("utf-8")),
            "completed_at": "2026-08-17T10:00:01+08:00",
            "status": "SUCCEEDED",
        },
    ]
    request = EvidenceTurnRequest.model_validate(request_document)
    submission_working_set = EvidenceContextAssembler().assemble(request).working_set
    assert (
        recover_parsed_text_fact_coordinate(
            parsed_text,
            submission_working_set.allowed_fact_targets,
        )
        == "FACT_SIGNATURE"
    )
    authority_catalog = build_submission_observation_catalog(
        evidence_content_authorities=request.context_envelope.evidence_content_authorities,
        visible_evidence=request.context_envelope.visible_evidence,
        attachment_refs=request.context_envelope.current_event.attachment_refs,
        allowed_fact_targets=submission_working_set.allowed_fact_targets,
        case_id=request.context_envelope.case_snapshot.case_id,
        actor_id=request.context_envelope.actor_snapshot.actor_id,
        actor_role=request.context_envelope.actor_snapshot.actor_role,
    )
    first_coordinate = next(
        item
        for item in authority_catalog
        if item.evidence_id == "EVIDENCE_signature_photo"
        and "FACT_SIGNATURE" in item.fact_ids
    )
    second_coordinate = next(
        item
        for item in authority_catalog
        if item.evidence_id == "EVIDENCE_recipient_record"
        and "FACT_RECIPIENT" in item.fact_ids
    )
    assert first_coordinate.attachment_order < second_coordinate.attachment_order
    raw_public_observation = {
        "schema_version": "public_evidence_observation_coordinate.v1",
        "provider_slot_id": "OBS_01",
        "coordinate_id": first_coordinate.coordinate_id,
        "observation_kind": "PARSED_RECORD",
        "epistemic_status": "PENDING_VERIFICATION",
    }
    canonical_public_observation = validate_public_observation_prefix(
        prior_accepted=(),
        candidate=PublicEvidenceObservationCoordinateProposalV1.model_validate(
            raw_public_observation
        ),
        evidence_content_authorities=request.context_envelope.evidence_content_authorities,
        visible_evidence=request.context_envelope.visible_evidence,
        attachment_refs=request.context_envelope.current_event.attachment_refs,
        allowed_fact_targets=submission_working_set.allowed_fact_targets,
        case_id=request.context_envelope.case_snapshot.case_id,
        actor_id=request.context_envelope.actor_snapshot.actor_id,
        actor_role=request.context_envelope.actor_snapshot.actor_role,
        authority_catalog=authority_catalog,
    )
    assert canonical_public_observation.observation_id is not None
    second_raw_public_observation = {
        "schema_version": "public_evidence_observation_coordinate.v1",
        "provider_slot_id": "OBS_02",
        "coordinate_id": second_coordinate.coordinate_id,
        "observation_kind": "PARSED_TRANSACTION_STATUS",
        "epistemic_status": "PROVISIONAL",
    }
    second_canonical_public_observation = validate_public_observation_prefix(
        prior_accepted=(canonical_public_observation,),
        candidate=PublicEvidenceObservationCoordinateProposalV1.model_validate(
            second_raw_public_observation
        ),
        evidence_content_authorities=request.context_envelope.evidence_content_authorities,
        visible_evidence=request.context_envelope.visible_evidence,
        attachment_refs=request.context_envelope.current_event.attachment_refs,
        allowed_fact_targets=submission_working_set.allowed_fact_targets,
        case_id=request.context_envelope.case_snapshot.case_id,
        actor_id=request.context_envelope.actor_snapshot.actor_id,
        actor_role=request.context_envelope.actor_snapshot.actor_role,
        authority_catalog=authority_catalog,
    )
    assert second_canonical_public_observation.observation_id is not None
    canonical_public_observations = (
        canonical_public_observation,
        second_canonical_public_observation,
    )

    def validate_coordinate(candidate, *, prior=()):
        return validate_public_observation_prefix(
            prior_accepted=prior,
            candidate=PublicEvidenceObservationCoordinateProposalV1.model_validate(
                candidate
            ),
            evidence_content_authorities=(
                request.context_envelope.evidence_content_authorities
            ),
            visible_evidence=request.context_envelope.visible_evidence,
            attachment_refs=request.context_envelope.current_event.attachment_refs,
            allowed_fact_targets=submission_working_set.allowed_fact_targets,
            case_id=request.context_envelope.case_snapshot.case_id,
            actor_id=request.context_envelope.actor_snapshot.actor_id,
            actor_role=request.context_envelope.actor_snapshot.actor_role,
            authority_catalog=authority_catalog,
        )

    with pytest.raises(
        EvidencePublicObservationAuthorityError,
        match="coordinate is unauthorized",
    ):
        validate_coordinate(
            {
                **raw_public_observation,
                "coordinate_id": "ECOORD_FOREIGN_AUTHORITY",
            }
        )
    with pytest.raises(
        EvidencePublicObservationAuthorityError,
        match="order or source span is invalid",
    ):
        validate_coordinate(
            {
                **raw_public_observation,
                "provider_slot_id": "OBS_02",
            },
            prior=(canonical_public_observation,),
        )
    reverse_first = validate_coordinate(
        {
            **second_raw_public_observation,
            "provider_slot_id": "OBS_01",
        }
    )
    with pytest.raises(
        EvidencePublicObservationAuthorityError,
        match="order or source span is invalid",
    ):
        validate_coordinate(
            {
                **raw_public_observation,
                "provider_slot_id": "OBS_02",
            },
            prior=(reverse_first,),
        )
    for forbidden_provider_field in (
        "evidence_id",
        "fact_id",
        "parsed_content_sha256",
        "source_quote",
    ):
        with pytest.raises(ValueError):
            PublicEvidenceObservationCoordinateProposalV1.model_validate(
                {
                    **raw_public_observation,
                    forbidden_provider_field: "FORGED_PROVIDER_AUTHORITY",
                }
            )
    submission_assessment = {
        "evidence_id": "EVIDENCE_signature_photo",
        "public_observation_ids": [canonical_public_observation.observation_id],
        "analysis_method": "TEXT_ONLY",
        "inspected_modalities": ["PARSED_TEXT"],
        "authenticity_score": 0.55,
        "relevance_score": 0.82,
        "completeness_score": 0.48,
        "assessment_confidence": 0.72,
        "source_basis": [submission_observation],
        "fact_links": [
            {
                "fact_id": "FACT_SIGNATURE",
                "relation": "INCONCLUSIVE",
                "reason": "材料所载退款记录仍需与平台原始记录核对。",
                "confidence": 0.61,
            }
        ],
        "supported_fact_ids": [],
        "unsupported_claims": ["当前材料不能单独还原完整事实"],
        "formation_time_assessment": submission_observation,
        "findings": [],
        "limitations": [capability_observation],
        "recommendation": "NEEDS_HUMAN_REVIEW",
        "human_review": {
            "required": True,
            "reason_codes": ["SOURCE_RECONCILIATION_REQUIRED"],
            "instructions": ["人工复核平台原始记录和形成时间"],
        },
        "summary": "页面日期与退款记录范围仍待交叉核对",
    }
    second_submission_assessment = deepcopy(submission_assessment)
    second_submission_assessment.update(
        {
            "evidence_id": "EVIDENCE_recipient_record",
            "public_observation_ids": [
                second_canonical_public_observation.observation_id
            ],
            "source_basis": [second_submission_observation],
            "fact_links": [
                {
                    "fact_id": "FACT_RECIPIENT",
                    "relation": "INCONCLUSIVE",
                    "reason": "材料所载收件范围仍需与物流原始记录核对。",
                    "confidence": 0.61,
                }
            ],
            "formation_time_assessment": "收件记录形成时间仍需核对",
            "summary": "收件记录与未签收争议相关但仍待核验",
        }
    )
    submission_assessments = [
        submission_assessment,
        second_submission_assessment,
    ]
    submission_room_utterance = compose_evidence_submission_public_reply(
        fact_targets=submission_working_set.allowed_fact_targets,
        public_observations=canonical_public_observations,
        evidence_assessments=submission_assessments,
        human_review_tasks=[],
    )
    serialized_public_observations = tuple(
        json.dumps(
            observation,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        for observation in (
            raw_public_observation,
            second_raw_public_observation,
        )
    )
    timeline: list[str] = []

    class FakeFormalWorkflow:
        def __init__(self) -> None:
            self.calls = []
            self.provider_calls = 0
            self.invoke_started = asyncio.Event()
            self.release_after_bootstrap = asyncio.Event()
            self.first_partial_submitted = asyncio.Event()
            self.release_first_complete = asyncio.Event()
            self.first_complete_submitted = asyncio.Event()
            self.release_second_partial = asyncio.Event()
            self.second_partial_submitted = asyncio.Event()
            self.release_second_complete = asyncio.Event()
            self.second_complete_submitted = asyncio.Event()
            self.release_array_close = asyncio.Event()
            self.array_closed = asyncio.Event()
            self.raw_terminal_submitted = asyncio.Event()
            self.release_terminal = asyncio.Event()
            self.completed = asyncio.Event()

        def run(self, request):
            raise AssertionError("formal Target Evidence must not use the sync workflow")

        @staticmethod
        def result(room_utterance: str | None = None) -> EvidenceTurnResult:
            if room_utterance is None:
                room_utterance = compose_evidence_submission_public_reply(
                    fact_targets=submission_working_set.allowed_fact_targets,
                    public_observations=canonical_public_observations,
                    evidence_assessments=submission_assessments,
                    human_review_tasks=[],
                )
                timeline.append("terminal:composed")
            return EvidenceTurnResult(
                room_utterance=room_utterance,
                internal_handoff={
                    "evidence_change_summary": "已读取本轮真实证据。",
                    "matrix_change_summary": "未改变冻结事实坐标。",
                    "remaining_conflicts": [],
                    "uncovered_fact_ids": [],
                    "human_review_evidence_ids": [],
                    "judge_attention_points": [],
                },
                confidence=0.8,
                memory_frame={"guarded": True},
                canvas_operations=[],
                referenced_evidence_ids=[
                    "EVIDENCE_signature_photo",
                    "EVIDENCE_recipient_record",
                ],
                public_observations=list(canonical_public_observations),
                evidence_assessments=submission_assessments,
            )

        @staticmethod
        def publish_usage(observer) -> None:
            observer.usage(
                node_name="evidence_turn",
                model="formal-evidence-test-model",
                latency_ms=1,
                token_usage={"input": 11, "output": 7, "total": 18},
            )

        async def arun(self, request):
            self.calls.append(request)
            self.provider_calls += 1
            observer = current_stream_observer()
            assert observer is not None
            if len(self.calls) > 1:
                for serialized_observation in serialized_public_observations:
                    observer.visible_delta(
                        "evidence_turn",
                        "public_observations",
                        serialized_observation,
                    )
                observer.visible_delta(
                    "evidence_turn",
                    "room_utterance",
                    raw_room_utterance,
                )
                self.publish_usage(observer)
                return self.result()

            self.invoke_started.set()
            await self.release_after_bootstrap.wait()
            projector = IncrementalVisibleJsonProjector(
                (
                    VisibleFieldSpec(
                        "public_observations",
                        "public_observations",
                        "json_array_items",
                        max_array_items=12,
                    ),
                )
            )
            partial_document = (
                '{"public_observations":[' + serialized_public_observations[0][:-1]
            )
            assert projector.feed(partial_document) == []
            self.first_partial_submitted.set()
            await self.release_first_complete.wait()
            completed_items = projector.feed(serialized_public_observations[0][-1:])
            assert completed_items == [
                ("public_observations", serialized_public_observations[0])
            ]
            for field_name, item_json in completed_items:
                observer.visible_delta("evidence_turn", field_name, item_json)
            self.first_complete_submitted.set()
            await self.release_second_partial.wait()
            assert projector.feed("," + serialized_public_observations[1][:-1]) == []
            self.second_partial_submitted.set()
            await self.release_second_complete.wait()
            completed_items = projector.feed(serialized_public_observations[1][-1:])
            assert completed_items == [
                ("public_observations", serialized_public_observations[1])
            ]
            for field_name, item_json in completed_items:
                observer.visible_delta("evidence_turn", field_name, item_json)
            self.second_complete_submitted.set()
            await self.release_array_close.wait()
            assert projector.feed("]") == []
            timeline.append("provider:array_closed")
            self.array_closed.set()
            timeline.append("provider:terminal_fields_submitted")
            observer.visible_delta(
                "evidence_turn",
                "room_utterance",
                raw_room_utterance,
            )
            self.raw_terminal_submitted.set()
            await self.release_terminal.wait()
            self.publish_usage(observer)
            result = self.result()
            timeline.append("provider:completed")
            self.completed.set()
            return result

    provider_substantive = (
        "本轮核验对象为“商家是否已退款20元”的关联性核对。"
    )
    provider_unsafe = "该记录真实有效，商家应当退款并承担责任。"
    generic_opening_room_utterance = (
        EVIDENCE_CANONICAL_OPENING + provider_substantive + provider_unsafe
    )
    opening_questions = (
        "请补充2026-08-13页面记录和承诺退款20元的形成来源。",
        "请补充退款工单未生成的页面记录。",
        "请补充未发现成功退款流水的账户记录。",
    )

    class MatrixSpecificOpeningRunner:
        def __init__(self) -> None:
            self.calls = 0
            self.substantive_emitted = asyncio.Event()
            self.release_completion = asyncio.Event()
            self.completed = asyncio.Event()

        async def ainvoke_structured(self, **kwargs):
            self.calls += 1
            output_type = kwargs["output_type"]
            observer = current_stream_observer()
            assert observer is not None
            visible_prefix = EVIDENCE_CANONICAL_OPENING + provider_substantive
            for index, chunk in enumerate(visible_prefix):
                if index == len(visible_prefix) - 1:
                    self.substantive_emitted.set()
                observer.visible_delta(
                    "evidence_turn",
                    "room_utterance",
                    chunk,
                )
            await asyncio.wait_for(self.release_completion.wait(), timeout=2.0)
            for chunk in provider_unsafe:
                observer.visible_delta(
                    "evidence_turn",
                    "room_utterance",
                    chunk,
                )
            generation = HarnessGeneration(
                value=output_type(
                    room_utterance=generic_opening_room_utterance,
                    evidence_requests=[
                        {
                            "question_id": f"REQ_OPENING_{index}",
                            "target_evidence_id": None,
                            "question": question,
                            "reason": "用于核验冻结矩阵中的签收事实。",
                        }
                        for index, question in enumerate(opening_questions, start=1)
                    ],
                    verification_suggestions=[],
                    authenticity_flags=[],
                    evidence_assessments=[],
                    fact_matrix_patch=[],
                    human_review_tasks=[],
                    internal_handoff={
                        "evidence_change_summary": "本轮为开场举证指引。",
                        "matrix_change_summary": "冻结事实矩阵保持不变。",
                        "remaining_conflicts": ["包裹签收事实仍待核验。"],
                        "uncovered_fact_ids": ["FACT_SIGNATURE"],
                        "human_review_evidence_ids": [],
                        "judge_attention_points": [],
                    },
                    confidence=0.8,
                ),
                model="formal-evidence-stream-test-model",
                latency_ms=1,
                token_usage={"input": 11, "output": 7, "total": 18},
                context=SimpleNamespace(),
                messages=(),
            )
            observer.usage(
                node_name="evidence_turn",
                model=generation.model,
                latency_ms=generation.latency_ms,
                token_usage=generation.token_usage,
            )
            self.completed.set()
            return generation

    class FencedMemorySaver(InMemorySaver):
        def __init__(self) -> None:
            super().__init__()
            self.commits: list[ExternalTerminalCommit] = []

        async def avalidate_external_terminal_checkpoint(
            self, config, *, cognitive_revision: int
        ) -> None:
            assert cognitive_revision == 1

        async def acommit_external_terminal(self, config, commit):
            timeline.append("fenced_commit")
            self.commits.append(commit)
            return config

    class ObjectStore:
        def __init__(self, payload: bytes) -> None:
            self.payload = payload
            self.puts: list[dict[str, object]] = []

        async def load(self, reference: SnapshotRef) -> bytes:
            assert reference.sha256 == hashlib.sha256(self.payload).hexdigest()
            assert reference.size_bytes == len(self.payload)
            return self.payload

        async def put(self, **kwargs):
            assert hashlib.sha256(kwargs["payload"]).hexdigest() == kwargs["payload_hash"]
            timeline.append("object_put")
            self.puts.append(dict(kwargs))
            return f"urn:target-e2e:proposal:evidence:{kwargs['payload_hash']}"

        async def put_content_addressed(self, **kwargs):
            raise AssertionError("formal Evidence must use the checkpoint-bound put")

    class RoomExchange:
        def __init__(self) -> None:
            self.stores: dict[str, ObjectStore] = {}

        def for_execution(self, execution):
            return self.stores[execution.admission.command.domain_snapshot_ref.sha256]

    actor_scope = {
        "actor_id": "USER_local_1",
        "actor_role": "USER",
        "audience": "USER",
        "capabilities": [
            "case:CASE_evidence_turn_llm:command:EVIDENCE_OPENING",
            "case:CASE_evidence_turn_llm:command:EVIDENCE_SUBMIT"
        ],
    }
    identity = {
        "command_id": "evidence-submit:EVIDENCE_BATCH_TEST",
        "logical_run_id": "target-evidence-run:formal-test",
        "attempt_id": "target-evidence-run:formal-test:1",
        "tenant_surrogate": "legacy-default",
        "case_id": "CASE_evidence_turn_llm",
        "room_epoch": 0,
        "fencing_token": 7,
        "thread_id": "grt.v1.0123456789abcdef0123456789abcdef",
        "actor_id": "USER_local_1",
        "actor_role": "USER",
        "actor_scope_hash": canonical_sha256(actor_scope),
    }
    invocation = {
        "schema_version": "target-e2e-evidence-turn-invocation.v2",
        "logical_run_id": identity["logical_run_id"],
        "tenant_surrogate": identity["tenant_surrogate"],
        "case_id": identity["case_id"],
        "room_epoch": identity["room_epoch"],
        "fencing_token": identity["fencing_token"],
        "thread_id": identity["thread_id"],
        "actor_id": identity["actor_id"],
        "actor_role": identity["actor_role"],
        "actor_scope_hash": identity["actor_scope_hash"],
        "evidence_turn_request": request_document,
        "invocation_hash": "0" * 64,
    }
    invocation["invocation_hash"] = canonical_sha256_omitting(
        invocation, "invocation_hash"
    )
    invocation_payload = canonicalize(invocation)

    def snapshot_ref(payload: bytes) -> SnapshotRef:
        digest = hashlib.sha256(payload).hexdigest()
        return SnapshotRef(
            artifact_id=f"target-evidence-turn-invocation:{digest[:32]}",
            schema_version="target-e2e-evidence-turn-invocation.v2",
            uri=f"urn:target-e2e:object:target-evidence-turn-invocation:{digest[:32]}:{digest}",
            sha256=digest,
            size_bytes=len(payload),
        )

    command = RoomGraphCommand.model_validate_json(
        json.dumps(
            {
                "schema_version": "room-graph-command.v1",
                "command_id": identity["command_id"],
                "logical_run_id": identity["logical_run_id"],
                "attempt_id": identity["attempt_id"],
                "tenant_surrogate": identity["tenant_surrogate"],
                "case_id": identity["case_id"],
                "room_type": "EVIDENCE",
                "room_epoch": identity["room_epoch"],
                "graph_key": "all-rooms.target-e2e.v2",
        "graph_version": "target-e2e-graph.2026-08-18.2",
                "checkpoint_schema_version": "target-e2e-checkpoint.v2",
                "thread_id": identity["thread_id"],
                "actor_scope": actor_scope,
                "process_revision": 12,
                "stage_code": "EVIDENCE_SEAL",
                "stage_sequence": 12,
                "domain_snapshot_ref": snapshot_ref(invocation_payload).model_dump(
                    mode="json"
                ),
                "event_ref": {
                    "artifact_id": "evidence-submit-event:COMMAND_TEST",
                    "schema_version": "target-e2e-case-command-payload.v1",
                    "uri": "urn:target-e2e:object:evidence-submit-event:COMMAND_TEST:"
                    + "a" * 64,
                    "sha256": "a" * 64,
                    "size_bytes": 128,
                },
                "invocation_context": {
                    "agent_profile_id": "all-rooms-agent.target-e2e.v1",
                    "prompt_profile_id": "all-rooms-prompt.target-e2e.v2",
                    "model_profile_id": "target-e2e.formal-evidence",
                    "output_schema_version": "target-e2e-room-proposal-source.v2",
                    "policy_version": "target-e2e.proposal-only.v1",
                    "guardrail_version": "evidence-guardrail.v1",
                    "tool_capabilities": [],
                    "envelope_key_id": "JAVA_GRAPH_COMMAND_ES256_TEST",
                    "envelope_nonce": "NONCE_EVIDENCE_FORMAL_TEST",
                },
                "retry_budget": {
                    "provider_attempts_remaining": 1,
                    "activity_attempts_remaining": 1,
                    "repairs_remaining": 0,
                },
                "deadline_at": "2026-08-08T00:00:00Z",
                "traceparent": "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                "request_hash": "b" * 64,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    )
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="OWNER_EVIDENCE_FORMAL_TEST",
        fencing_token=1,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id="p9act.v1.0123456789abcdef0123456789abcdef",
        room_fencing_token=identity["fencing_token"],
        command_hash=canonical_sha256(command.model_dump(mode="json")),
        command_envelope_hash="c" * 64,
        execution_provider="litellm",
        execution_model="formal-evidence-test-model",
        environment_id="target-e2e-test",
        environment_generation=1,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type="EVIDENCE",
        binding_hash="d" * 64,
        code_build_id="target-e2e-test-build",
    )
    execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=command,
            binding=SimpleNamespace(room_fencing_token=identity["fencing_token"]),
        ),
        fence=fence,
    )
    workflow = FakeFormalWorkflow()
    saver = FencedMemorySaver()
    store = ObjectStore(invocation_payload)
    room_exchange = RoomExchange()
    room_exchange.stores[command.domain_snapshot_ref.sha256] = store
    unused_hearing_decoder = SimpleNamespace(
        decode=lambda **_kwargs: (_ for _ in ()).throw(
            AssertionError("Evidence selector must not decode a Hearing invocation")
        )
    )
    provider_factory = TargetE2ESpecializedRoomProviderFactory(
        security_runtime=object.__new__(GraphSecurityRuntime),
        room_exchange=room_exchange,
        hearing_decoder=unused_hearing_decoder,
    ).with_evidence_workflow(workflow)
    providers = tuple(
        provider_factory(
            SimpleNamespace(saver=saver, durable_bulkhead=object())
        )
    )
    evidence_provider = providers[0]

    first_stream = evidence_provider.stream(execution).__aiter__()
    first = [await anext(first_stream)]
    timeline.append(f"yield:{first[0].event_type}")
    first_visible_task = asyncio.create_task(anext(first_stream))
    first_visible = await asyncio.wait_for(first_visible_task, timeout=1.0)
    first.append(first_visible)
    timeline.append(f"yield:{first_visible.event_type}")
    assert first_visible.event_type == "visible_delta"
    assert first_visible.payload.field == "room_utterance"
    assert first_visible.payload.delta == EVIDENCE_CANONICAL_OPENING
    assert not workflow.invoke_started.is_set()
    await asyncio.wait_for(workflow.invoke_started.wait(), timeout=1.0)
    assert not workflow.release_after_bootstrap.is_set()
    assert not workflow.first_partial_submitted.is_set()
    assert not workflow.completed.is_set()
    assert store.puts == []
    assert saver.commits == []

    workflow.release_after_bootstrap.set()
    await asyncio.wait_for(workflow.first_partial_submitted.wait(), timeout=1.0)
    first_item_task = asyncio.create_task(anext(first_stream))
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(asyncio.shield(first_item_task), timeout=0.05)
    assert not workflow.first_complete_submitted.is_set()
    assert not workflow.completed.is_set()
    assert store.puts == []
    assert saver.commits == []

    workflow.release_first_complete.set()
    await asyncio.wait_for(workflow.first_complete_submitted.wait(), timeout=1.0)
    first_material_event = await asyncio.wait_for(first_item_task, timeout=1.0)
    first.append(first_material_event)
    timeline.append(f"yield:{first_material_event.event_type}:OBS_01")
    assert first_material_event.event_type == "visible_delta"
    assert first_material_event.payload.field == "room_utterance"
    assert (
        first_material_event.payload.delta
        == canonical_public_observation.public_text
    )
    assert first_coordinate.source_quote in (
        first_material_event.payload.delta or ""
    )
    assert all(
        serialized not in (first_material_event.payload.delta or "")
        for serialized in serialized_public_observations
    )
    assert not workflow.second_partial_submitted.is_set()
    assert not workflow.array_closed.is_set()
    assert not workflow.completed.is_set()
    assert store.puts == []
    assert saver.commits == []

    workflow.release_second_partial.set()
    await asyncio.wait_for(workflow.second_partial_submitted.wait(), timeout=1.0)
    second_item_task = asyncio.create_task(anext(first_stream))
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(asyncio.shield(second_item_task), timeout=0.05)
    assert not workflow.second_complete_submitted.is_set()
    assert not workflow.array_closed.is_set()
    assert not workflow.completed.is_set()

    workflow.release_second_complete.set()
    await asyncio.wait_for(workflow.second_complete_submitted.wait(), timeout=1.0)
    second_material_event = await asyncio.wait_for(second_item_task, timeout=1.0)
    first.append(second_material_event)
    timeline.append(f"yield:{second_material_event.event_type}:OBS_02")
    assert second_material_event.event_type == "visible_delta"
    assert second_material_event.payload.field == "room_utterance"
    assert (
        second_material_event.payload.delta
        == second_canonical_public_observation.public_text
    )
    assert second_coordinate.source_quote in (
        second_material_event.payload.delta or ""
    )
    assert not workflow.array_closed.is_set()
    assert not workflow.completed.is_set()
    assert store.puts == []
    assert saver.commits == []

    workflow.release_array_close.set()
    await asyncio.wait_for(workflow.array_closed.wait(), timeout=1.0)
    await asyncio.wait_for(workflow.raw_terminal_submitted.wait(), timeout=1.0)
    terminal_task = asyncio.create_task(anext(first_stream))
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(asyncio.shield(terminal_task), timeout=0.05)
    workflow.release_terminal.set()
    first.append(await asyncio.wait_for(terminal_task, timeout=1.0))
    timeline.append(f"yield:{first[-1].event_type}")
    async for event in first_stream:
        first.append(event)
        timeline.append(f"yield:{event.event_type}")
    first_types = [event.event_type for event in first]
    first_usage_index = first_types.index("usage")
    assert first_types[0] == "attempt_started"
    assert first_types[-1] == "final"
    assert first_types.count("usage") == 1
    assert all(
        event_type == "visible_delta"
        for event_type in first_types[1:first_usage_index]
    )
    assert "".join(
        event.payload.delta or ""
        for event in first
        if event.event_type == "visible_delta"
    ) == submission_room_utterance
    assert submission_room_utterance.count(EVIDENCE_CANONICAL_OPENING) == 1
    assert arbitrary_safe_submission_sentence not in submission_room_utterance
    assert first_coordinate.source_quote in submission_room_utterance
    assert second_coordinate.source_quote in submission_room_utterance
    public_bytes = "".join(
        event.payload.delta or ""
        for event in first
        if event.event_type == "visible_delta"
    )
    for private_value in (
        first_coordinate.coordinate_id,
        second_coordinate.coordinate_id,
        first_coordinate.evidence_id,
        second_coordinate.evidence_id,
        first_coordinate.parsed_content_sha256,
        second_coordinate.parsed_content_sha256,
        "source_quote",
        "coordinate_id",
        "parsed_content_sha256",
    ):
        assert private_value not in public_bytes
    assert risky_sentence not in submission_room_utterance
    assert raw_room_utterance not in submission_room_utterance
    assert all(
        event.payload.field == "room_utterance"
        for event in first
        if event.event_type == "visible_delta"
    )
    assert timeline.index("yield:visible_delta:OBS_01") < timeline.index(
        "yield:visible_delta:OBS_02"
    )
    assert timeline.index("yield:visible_delta:OBS_02") < timeline.index(
        "provider:array_closed"
    )
    assert timeline.index("provider:array_closed") < timeline.index(
        "provider:terminal_fields_submitted"
    )
    assert timeline.index("provider:terminal_fields_submitted") < timeline.index(
        "terminal:composed"
    )
    assert timeline.index("terminal:composed") < timeline.index(
        "provider:completed"
    )
    assert timeline.index("object_put") < timeline.index("fenced_commit")
    assert timeline.index("fenced_commit") < timeline.index("yield:final")
    proposal = json.loads(store.puts[0]["payload"])
    assert proposal["room_utterance"] == submission_room_utterance
    assert (
        proposal["evidence_turn_result"]["room_utterance"]
        == submission_room_utterance
    )
    assert set(proposal["evidence_turn_result"]) == {
        "room_utterance",
        "memory_patch",
        "canvas_operations",
        "referenced_evidence_ids",
        "verification_suggestions",
        "authenticity_flags",
        "public_observations",
        "evidence_assessments",
        "fact_matrix_patch",
        "human_review_tasks",
        "internal_handoff",
        "liability_determined",
        "remedy_recommended",
        "knowledge_answer_mode",
        "confidence",
    }
    assert proposal["evidence_turn_result"]["knowledge_answer_mode"] == "NONE"
    assert proposal["input_hash"] == command.domain_snapshot_ref.sha256
    assert proposal["usage"] == {
        "input_tokens": 11,
        "output_tokens": 7,
        "total_tokens": 18,
    }
    assert proposal["proposal_hash"] == canonical_sha256_omitting(
        proposal, "proposal_hash"
    )
    assert saver.commits[0].result.result_json["artifact_operations"] == [
        {
            "operation": "PROPOSE_PATCH",
            "artifact": {
                "artifact_id": store.puts[0]["proposal_id"],
                "schema_version": store.puts[0]["schema_version"],
                "uri": (
                    "urn:target-e2e:proposal:evidence:"
                    + str(store.puts[0]["payload_hash"])
                ),
                "sha256": store.puts[0]["payload_hash"],
            },
        }
    ]
    governed_agent_context_fields = {
        "model_profile_id",
        "output_schema_version",
        "policy_version",
        "guardrail_version",
        "tool_capabilities",
        "retry_budget",
        "deadline_at",
        "traceparent",
    }
    assert governed_agent_context_fields.isdisjoint(
        request_document["agent_context"]
    )
    received_agent_context = workflow.calls[0].agent_context
    assert received_agent_context.model_dump(
        mode="json",
        exclude=governed_agent_context_fields,
    ) == request_document["agent_context"]
    assert received_agent_context.model_profile_id == (
        command.invocation_context.model_profile_id
    )
    assert received_agent_context.output_schema_version == (
        command.invocation_context.output_schema_version
    )
    assert received_agent_context.policy_version == (
        command.invocation_context.policy_version
    )
    assert received_agent_context.guardrail_version == (
        command.invocation_context.guardrail_version
    )
    assert received_agent_context.tool_capabilities == list(
        command.invocation_context.tool_capabilities
    )
    assert received_agent_context.retry_budget is not None
    assert received_agent_context.retry_budget.model_dump(mode="json") == (
        command.retry_budget.model_dump(mode="json")
    )
    assert received_agent_context.deadline_at == command.deadline_at
    assert received_agent_context.traceparent == command.traceparent
    assert workflow.provider_calls == 1
    assert workflow.calls[0].context_envelope.visible_evidence[0].evidence_id == (
        "EVIDENCE_signature_photo"
    )
    assert (
        workflow.calls[0]
        .context_envelope.intake_dossier_snapshot.payload["case_fact_matrix"]["fact_rows"][0][
            "fact_id"
        ]
        == "FACT_SIGNATURE"
    )

    conflicting_invocation = deepcopy(invocation)
    conflicting_invocation["evidence_turn_request"]["agent_context"][
        "traceparent"
    ] = "00-fedcba9876543210fedcba9876543210-fedcba9876543210-01"
    conflicting_invocation["invocation_hash"] = canonical_sha256_omitting(
        conflicting_invocation,
        "invocation_hash",
    )
    conflicting_payload = canonicalize(conflicting_invocation)
    conflicting_command = command.model_copy(
        update={"domain_snapshot_ref": snapshot_ref(conflicting_payload)}
    )
    conflicting_fence = replace(
        fence,
        command_hash=canonical_sha256(
            conflicting_command.model_dump(mode="json")
        ),
    )
    conflicting_execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=conflicting_command,
            binding=execution.admission.binding,
        ),
        fence=conflicting_fence,
    )
    conflicting_store = ObjectStore(conflicting_payload)
    room_exchange.stores[
        conflicting_command.domain_snapshot_ref.sha256
    ] = conflicting_store
    conflicting_saver = FencedMemorySaver()
    conflicting_provider = tuple(
        provider_factory(
            SimpleNamespace(saver=conflicting_saver, durable_bulkhead=object())
        )
    )[0]
    workflow_calls_before_conflict = len(workflow.calls)
    provider_calls_before_conflict = workflow.provider_calls
    conflicting_events = []
    with pytest.raises(
        GraphContractError,
        match="^EVIDENCE_TURN_MODEL_INVOCATION_BINDING_INVALID$",
    ):
        async for event in conflicting_provider.stream(conflicting_execution):
            conflicting_events.append(event)
    assert [event.event_type for event in conflicting_events] == ["attempt_started"]
    assert len(workflow.calls) == workflow_calls_before_conflict
    assert workflow.provider_calls == provider_calls_before_conflict
    assert conflicting_store.puts == []
    assert conflicting_saver.commits == []

    replay_stream = evidence_provider.stream(execution).__aiter__()
    replay = [await anext(replay_stream)]
    replay_commit_count = len(saver.commits)
    replay_visible = await anext(replay_stream)
    assert len(saver.commits) == replay_commit_count
    assert replay_visible.payload.delta == EVIDENCE_CANONICAL_OPENING
    replay.append(replay_visible)
    replay.extend([event async for event in replay_stream])
    assert len(saver.commits) == replay_commit_count + 1
    assert [event.event_type for event in replay] == [
        "attempt_started",
        "visible_delta",
        "visible_delta",
        "usage",
        "final",
    ]
    assert sum(event.event_type == "visible_delta" for event in replay) == 2
    assert sum(event.event_type == "final" for event in replay) == 1
    assert "".join(
        event.payload.delta or ""
        for event in replay
        if event.event_type == "visible_delta"
    ) == submission_room_utterance
    assert len(workflow.calls) == 1
    assert governed_agent_context_fields.isdisjoint(
        request_document["agent_context"]
    )
    assert store.puts[0]["payload"] == store.puts[1]["payload"]
    assert saver.commits[0].result.result_hash == saver.commits[1].result.result_hash

    later_command = command.model_copy(
        update={
            "command_id": "evidence-submit:EVIDENCE_BATCH_TEST:retry:2",
            "attempt_id": "target-evidence-run:formal-test:2",
            "request_hash": "e" * 64,
        }
    )
    later_fence = replace(
        fence,
        command_id=later_command.command_id,
        request_hash=later_command.request_hash,
        command_hash=canonical_sha256(later_command.model_dump(mode="json")),
        command_envelope_hash="f" * 64,
    )
    later_execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=later_command,
            binding=execution.admission.binding,
        ),
        fence=later_fence,
    )
    later_saver = FencedMemorySaver()
    later_provider = tuple(
        provider_factory(
            SimpleNamespace(saver=later_saver, durable_bulkhead=object())
        )
    )[0]
    later = [event async for event in later_provider.stream(later_execution)]
    later_types = [event.event_type for event in later]
    later_usage_index = later_types.index("usage")
    assert later_types[0] == "attempt_started"
    assert later_types[-1] == "final"
    assert later_types.count("usage") == 1
    assert all(
        event_type == "visible_delta"
        for event_type in later_types[1:later_usage_index]
    )
    assert all(len(serialized) > 64 for serialized in serialized_public_observations)
    assert "".join(
        event.payload.delta or ""
        for event in later
        if event.event_type == "visible_delta"
    ) == submission_room_utterance
    assert all(event.attempt_id == later_command.attempt_id for event in later)
    assert later_command.domain_snapshot_ref == command.domain_snapshot_ref
    assert len(workflow.calls) == 2
    later_proposal = json.loads(store.puts[2]["payload"])
    assert later_proposal["room_utterance"] == submission_room_utterance
    assert later_proposal["command_id"] == later_command.command_id
    assert later_proposal["logical_run_id"] == later_command.logical_run_id
    assert later_proposal["attempt_id"] == later_command.attempt_id
    assert store.puts[2]["execution"] is later_execution
    assert later_saver.commits[0].result.result_hash == later[-1].payload.final_result_hash

    empty_relevant_assessment = {
        **submission_assessment,
        "public_observation_ids": [],
        "relevance_score": 0.49,
        "fact_links": [],
    }
    empty_relevant_room_utterance = compose_evidence_submission_public_reply(
        fact_targets=submission_working_set.allowed_fact_targets,
        public_observations=(),
        evidence_assessments=[empty_relevant_assessment],
        human_review_tasks=[],
    )

    class EmptyRelevantObservationWorkflow:
        def __init__(self) -> None:
            self.calls = 0

        def run(self, request):
            raise AssertionError("formal Target Evidence must not use the sync workflow")

        async def arun(self, request):
            self.calls += 1
            observer = current_stream_observer()
            assert observer is not None
            FakeFormalWorkflow.publish_usage(observer)
            terminal_payload = FakeFormalWorkflow.result(
                empty_relevant_room_utterance
            ).model_dump(mode="json")
            terminal_payload.update(
                {
                    "public_observations": [],
                    "evidence_assessments": [empty_relevant_assessment],
                }
            )
            return EvidenceTurnResult.model_validate(terminal_payload)

    empty_command = command.model_copy(
        update={
            "command_id": "evidence-submit:EVIDENCE_BATCH_TEST:empty-relevant",
            "attempt_id": "target-evidence-run:formal-test:empty-relevant",
            "request_hash": "2" * 64,
        }
    )
    empty_fence = replace(
        fence,
        command_id=empty_command.command_id,
        request_hash=empty_command.request_hash,
        command_hash=canonical_sha256(empty_command.model_dump(mode="json")),
        command_envelope_hash="5" * 64,
    )
    empty_execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=empty_command,
            binding=execution.admission.binding,
        ),
        fence=empty_fence,
    )
    empty_saver = FencedMemorySaver()
    empty_workflow = EmptyRelevantObservationWorkflow()
    empty_provider = tuple(
        TargetE2ESpecializedRoomProviderFactory(
            security_runtime=object.__new__(GraphSecurityRuntime),
            room_exchange=room_exchange,
            hearing_decoder=unused_hearing_decoder,
        )
        .with_evidence_workflow(empty_workflow)(
            SimpleNamespace(saver=empty_saver, durable_bulkhead=object())
        )
    )[0]
    puts_before_empty = len(store.puts)
    empty_events = []
    with pytest.raises(
        EvidencePublicObservationAuthorityError,
        match="relevant parsed evidence requires public observation authority",
    ):
        async for event in empty_provider.stream(empty_execution):
            empty_events.append(event)
    assert [event.event_type for event in empty_events] == [
        "attempt_started",
        "visible_delta",
    ]
    assert empty_events[1].payload.delta == EVIDENCE_CANONICAL_OPENING
    assert len(store.puts) == puts_before_empty
    assert empty_saver.commits == []
    assert empty_workflow.calls == 1

    class InvalidObservationWorkflow:
        def __init__(self) -> None:
            self.calls = 0

        def run(self, request):
            raise AssertionError("formal Target Evidence must not use the sync workflow")

        async def arun(self, request):
            self.calls += 1
            observer = current_stream_observer()
            assert observer is not None
            invalid_item = {
                **raw_public_observation,
                "provider_slot_id": "OBS_02",
            }
            observer.visible_delta(
                "evidence_turn",
                "public_observations",
                json.dumps(invalid_item, ensure_ascii=False, separators=(",", ":")),
            )

    failure_command = command.model_copy(
        update={
            "command_id": "evidence-submit:EVIDENCE_BATCH_TEST:invalid-observation",
            "attempt_id": "target-evidence-run:formal-test:3",
            "request_hash": "3" * 64,
        }
    )
    failure_fence = replace(
        fence,
        command_id=failure_command.command_id,
        request_hash=failure_command.request_hash,
        command_hash=canonical_sha256(failure_command.model_dump(mode="json")),
        command_envelope_hash="6" * 64,
    )
    failure_execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=failure_command,
            binding=execution.admission.binding,
        ),
        fence=failure_fence,
    )
    failure_saver = FencedMemorySaver()
    failure_workflow = InvalidObservationWorkflow()
    failure_provider = tuple(
        TargetE2ESpecializedRoomProviderFactory(
            security_runtime=object.__new__(GraphSecurityRuntime),
            room_exchange=room_exchange,
            hearing_decoder=unused_hearing_decoder,
        )
        .with_evidence_workflow(failure_workflow)(
            SimpleNamespace(saver=failure_saver, durable_bulkhead=object())
        )
    )[0]
    puts_before_failure = len(store.puts)
    failure_events = []
    with pytest.raises(
        EvidencePublicObservationAuthorityError,
        match="slot order is invalid",
    ):
        async for event in failure_provider.stream(failure_execution):
            failure_events.append(event)

    assert [event.event_type for event in failure_events] == [
        "attempt_started",
        "visible_delta",
    ]
    assert failure_events[1].payload.delta == EVIDENCE_CANONICAL_OPENING
    assert len(store.puts) == puts_before_failure
    assert failure_saver.commits == []
    assert failure_workflow.calls == 1

    async def assert_rejected(document, code: str) -> None:
        payload = canonicalize(document)
        rejected_command = command.model_copy(
            update={"domain_snapshot_ref": snapshot_ref(payload)}
        )
        rejected_fence = replace(
            fence,
            command_hash=canonical_sha256(rejected_command.model_dump(mode="json")),
        )
        rejected_execution = SimpleNamespace(
            admission=SimpleNamespace(
                command=rejected_command,
                binding=execution.admission.binding,
            ),
            fence=rejected_fence,
        )
        room_exchange.stores[rejected_command.domain_snapshot_ref.sha256] = ObjectStore(
            payload
        )
        rejected = []
        with pytest.raises(GraphContractError, match=code):
            async for event in evidence_provider.stream(rejected_execution):
                rejected.append(event)
        assert [event.event_type for event in rejected] == ["attempt_started"]

    legacy_v1 = deepcopy(invocation)
    legacy_v1["schema_version"] = "target-e2e-evidence-turn-invocation.v1"
    legacy_v1["invocation_hash"] = canonical_sha256_omitting(
        legacy_v1, "invocation_hash"
    )
    await assert_rejected(legacy_v1, "EVIDENCE_TURN_INVOCATION_DOCUMENT_INVALID")

    attempt_local = deepcopy(invocation)
    attempt_local["command_id"] = identity["command_id"]
    attempt_local["attempt_id"] = identity["attempt_id"]
    attempt_local["invocation_hash"] = canonical_sha256_omitting(
        attempt_local, "invocation_hash"
    )
    await assert_rejected(
        attempt_local, "EVIDENCE_TURN_INVOCATION_DOCUMENT_INVALID"
    )

    tampered = deepcopy(invocation)
    tampered["actor_id"] = "USER_other"
    tampered["invocation_hash"] = canonical_sha256_omitting(
        tampered, "invocation_hash"
    )
    await assert_rejected(
        tampered, "EVIDENCE_TURN_INVOCATION_BINDING_INVALID"
    )

    opening_facts = (
        ("FACT_SIGNATURE", "包裹是否由用户本人签收", "CORE"),
        ("FACT_REFUND", "商家是否已退款20元", "CORE"),
        ("FACT_COMMITMENT", "商家关于退款时间的承诺是否存在", "CORE"),
        ("FACT_TICKET", "退款工单是否已经生成", "CORE"),
        ("FACT_FLOW", "是否存在成功退款流水", "CORE"),
    )
    opening_request_document = helper_module[
        "_freeze_bound_evidence_turn_payload"
    ](*opening_facts)
    opening_request_document["context_envelope"]["current_event"] = deepcopy(
        helper_module["_java_evidence_opening_command_payload"]()[
            "context_envelope"
        ]["current_event"]
    )
    frozen_submission = opening_request_document["context_envelope"][
        "frozen_submission"
    ]
    camel_authority = frozen_submission["authority"]
    frozen_submission["authority"] = {
        field_name: camel_authority[field.alias or field_name]
        for field_name, field in FrozenIntakeSubmissionAuthorityV1.model_fields.items()
    }
    opening_tenant = frozen_submission["authority"]["tenant_surrogate"]
    opening_room_epoch = frozen_submission["evidence_room_epoch"]
    opening_fencing_token = frozen_submission["evidence_fencing_token"]
    opening_capability = (
        f"case:{identity['case_id']}:command:EVIDENCE_OPENING"
    )
    submission_capability = (
        f"case:{identity['case_id']}:command:EVIDENCE_SUBMIT"
    )
    opening_actor_scope = command.actor_scope.model_copy(
        update={"capabilities": (opening_capability, submission_capability)}
    )
    opening_logical_run_id = "target-evidence-run:formal-opening-test"
    opening_invocation = deepcopy(invocation)
    opening_invocation.update(
        {
            "logical_run_id": opening_logical_run_id,
            "tenant_surrogate": opening_tenant,
            "room_epoch": opening_room_epoch,
            "fencing_token": opening_fencing_token,
            "actor_scope_hash": canonical_sha256(
                opening_actor_scope.model_dump(mode="json")
            ),
            "evidence_turn_request": opening_request_document,
            "invocation_hash": "0" * 64,
        }
    )
    opening_invocation["invocation_hash"] = canonical_sha256_omitting(
        opening_invocation, "invocation_hash"
    )
    opening_payload = canonicalize(opening_invocation)
    opening_command = command.model_copy(
        update={
            "command_id": "evidence-opening:EVIDENCE_OPENING_TEST",
            "logical_run_id": opening_logical_run_id,
            "attempt_id": opening_logical_run_id + ":1",
            "tenant_surrogate": opening_tenant,
            "room_epoch": opening_room_epoch,
            "actor_scope": opening_actor_scope,
            "domain_snapshot_ref": snapshot_ref(opening_payload),
            "event_ref": command.event_ref.model_copy(
                update={
                    "artifact_id": "evidence-opening-event:COMMAND_TEST",
                    "uri": "urn:target-e2e:object:evidence-opening-event:COMMAND_TEST:"
                    + "8" * 64,
                    "sha256": "8" * 64,
                }
            ),
            "request_hash": "8" * 64,
        }
    )
    opening_fence = replace(
        fence,
        command_id=opening_command.command_id,
        request_hash=opening_command.request_hash,
        room_epoch=opening_room_epoch,
        room_fencing_token=opening_fencing_token,
        command_hash=canonical_sha256(opening_command.model_dump(mode="json")),
        command_envelope_hash="9" * 64,
        tenant_surrogate=opening_tenant,
    )
    opening_execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=opening_command,
            binding=SimpleNamespace(
                room_fencing_token=opening_fencing_token
            ),
        ),
        fence=opening_fence,
    )
    opening_store = ObjectStore(opening_payload)
    room_exchange.stores[opening_command.domain_snapshot_ref.sha256] = (
        opening_store
    )
    opening_saver = FencedMemorySaver()
    opening_runner = MatrixSpecificOpeningRunner()
    opening_workflow = EvidenceTurnWorkflow(model_runner=opening_runner)
    opening_provider = tuple(
        TargetE2ESpecializedRoomProviderFactory(
            security_runtime=object.__new__(GraphSecurityRuntime),
            room_exchange=room_exchange,
            hearing_decoder=unused_hearing_decoder,
        )
        .with_evidence_workflow(opening_workflow)(
            SimpleNamespace(
                saver=opening_saver,
                durable_bulkhead=object(),
            )
        )
    )[0]
    opening_calls_before = opening_runner.calls

    opening_stream = opening_provider.stream(opening_execution).__aiter__()
    opening_first = [await anext(opening_stream), await anext(opening_stream)]
    substantive_event = await asyncio.wait_for(anext(opening_stream), timeout=1.0)
    opening_first.append(substantive_event)
    assert substantive_event.event_type == "visible_delta"
    assert substantive_event.payload.delta == provider_substantive
    assert opening_runner.substantive_emitted.is_set()
    assert not opening_runner.completed.is_set()
    opening_runner.release_completion.set()
    opening_first.extend([event async for event in opening_stream])
    opening_replay = [
        event
        async for event in opening_provider.stream(opening_execution)
    ]

    assert opening_first[0].event_type == "attempt_started"
    assert opening_first[-1].event_type == "final"
    assert opening_replay[0].event_type == "attempt_started"
    assert opening_replay[-1].event_type == "final"
    assert (
        opening_replay[-1].payload.final_result_hash
        == opening_first[-1].payload.final_result_hash
    )
    opening_visible_text = "".join(
        event.payload.delta or ""
        for event in opening_first
        if event.event_type == "visible_delta"
    )
    for _fact_id, fact_target, _materiality in opening_facts:
        assert fact_target in opening_visible_text
    for question in opening_questions:
        assert question.rstrip("。！？!?") in opening_visible_text
    assert len(opening_visible_text) <= STREAM_MAX_VISIBLE_OUTPUT_CHARS
    assert provider_unsafe not in opening_visible_text
    assert "真实有效" not in opening_visible_text
    assert "应当退款" not in opening_visible_text
    assert "承担责任" not in opening_visible_text
    assert opening_runner.completed.is_set()
    with pytest.raises(
        EvidencePublicOutputPolicyError,
        match="fact authority exceeds the governed limit",
    ):
        compose_evidence_opening_public_reply(
            EVIDENCE_CANONICAL_OPENING,
            fact_targets=(
                {
                    "fact_id": f"FACT_BOUND_{index}",
                    "fact": f"第{index}项冻结核心事实",
                    "materiality": "CORE",
                }
                for index in range(101)
            ),
            evidence_requests=(),
        )
    opening_proposal = json.loads(opening_store.puts[0]["payload"])
    assert opening_proposal["room_utterance"] == opening_visible_text
    assert (
        opening_proposal["evidence_turn_result"]["room_utterance"]
        == opening_visible_text
    )
    assert "evidence_requests" not in opening_proposal["evidence_turn_result"]
    assert opening_runner.calls == opening_calls_before + 1
    assert len(opening_store.puts) == 2
    assert opening_store.puts[1]["payload"] == opening_store.puts[0]["payload"]
    assert opening_saver.commits[1].result == opening_saver.commits[0].result

    async def assert_opening_binding_rejected(
        case_index: int,
        *,
        capabilities: tuple[str, ...],
        request_document: dict[str, object],
    ) -> None:
        rejected_logical_run_id = (
            f"target-evidence-run:opening-binding-negative-{case_index}"
        )
        rejected_actor_scope = opening_actor_scope.model_copy(
            update={"capabilities": capabilities}
        )
        rejected_invocation = deepcopy(opening_invocation)
        rejected_invocation.update(
            {
                "logical_run_id": rejected_logical_run_id,
                "actor_scope_hash": canonical_sha256(
                    rejected_actor_scope.model_dump(mode="json")
                ),
                "evidence_turn_request": deepcopy(request_document),
                "invocation_hash": "0" * 64,
            }
        )
        rejected_invocation["invocation_hash"] = canonical_sha256_omitting(
            rejected_invocation, "invocation_hash"
        )
        rejected_payload = canonicalize(rejected_invocation)
        request_hash = format(case_index, "x") * 64
        rejected_command = opening_command.model_copy(
            update={
                "command_id": f"evidence-opening:binding-negative-{case_index}",
                "logical_run_id": rejected_logical_run_id,
                "attempt_id": rejected_logical_run_id + ":1",
                "actor_scope": rejected_actor_scope,
                "domain_snapshot_ref": snapshot_ref(rejected_payload),
                "request_hash": request_hash,
            }
        )
        rejected_fence = replace(
            opening_fence,
            command_id=rejected_command.command_id,
            request_hash=request_hash,
            command_hash=canonical_sha256(
                rejected_command.model_dump(mode="json")
            ),
            command_envelope_hash=format(case_index + 8, "x") * 64,
        )
        rejected_execution = SimpleNamespace(
            admission=SimpleNamespace(
                command=rejected_command,
                binding=opening_execution.admission.binding,
            ),
            fence=rejected_fence,
        )
        rejected_store = ObjectStore(rejected_payload)
        room_exchange.stores[rejected_command.domain_snapshot_ref.sha256] = (
            rejected_store
        )
        rejected_saver = FencedMemorySaver()
        rejected_provider = tuple(
            provider_factory(
                SimpleNamespace(
                    saver=rejected_saver,
                    durable_bulkhead=object(),
                )
            )
        )[0]
        calls_before = len(workflow.calls)
        provider_calls_before = workflow.provider_calls
        rejected_events = []

        with pytest.raises(
            GraphContractError,
            match="^EVIDENCE_TURN_INVOCATION_BINDING_INVALID$",
        ):
            async for event in rejected_provider.stream(rejected_execution):
                rejected_events.append(event)

        assert [event.event_type for event in rejected_events] == [
            "attempt_started"
        ]
        assert len(workflow.calls) == calls_before
        assert workflow.provider_calls == provider_calls_before
        assert rejected_store.puts == []
        assert rejected_saver.commits == []

    empty_attachment_submission = deepcopy(request_document)
    empty_attachment_submission["context_envelope"]["current_event"][
        "attachment_refs"
    ] = []
    empty_attachment_submission["context_envelope"]["current_event"][
        "text"
    ] = "Evidence reference without a bound attachment"
    empty_attachment_submission["context_envelope"]["evidence_content_authorities"] = []
    rejection_cases = (
        ((opening_capability,), request_document),
        ((submission_capability,), opening_request_document),
        ((), opening_request_document),
        (
            (submission_capability, opening_capability),
            opening_request_document,
        ),
        (
            ("case:CASE_foreign:command:EVIDENCE_OPENING",),
            opening_request_document,
        ),
        (
            (opening_capability, submission_capability),
            empty_attachment_submission,
        ),
    )
    for case_index, (capabilities, rejected_request) in enumerate(
        rejection_cases,
        start=1,
    ):
        await assert_opening_binding_rejected(
            case_index,
            capabilities=capabilities,
            request_document=rejected_request,
        )


@pytest.mark.asyncio
async def test_target_evidence_submission_authorizes_deterministic_terminal_reply_after_live_preview() -> None:
    import hashlib
    import json
    import runpy
    from pathlib import Path

    from app.agents.evidence_clerk.public_reply import (
        EVIDENCE_CANONICAL_OPENING,
        EVIDENCE_PUBLIC_FIELD,
        EVIDENCE_PUBLIC_NODE,
        compose_evidence_submission_public_reply,
        guard_evidence_public_reply,
        validate_public_observation_prefix,
    )
    from app.graph_runtime.errors import GraphContractError
    from app.graph_runtime.evidence_turn_executor import (
        CompiledEvidenceTurnExecutor,
        _EvidencePreviewBridge,
        _SubmissionObservationPublicOutputPolicy,
    )
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest, EvidenceTurnResult
    from app.streaming import (
        AgentStreamObserver,
        StreamVisibleDeltaEvent,
        current_stream_observer,
    )

    helper_module = runpy.run_path(
        str(Path(__file__).parents[1] / "agents" / "test_evidence_clerk_turn.py")
    )
    request_document = helper_module["_java_evidence_turn_command_payload"]()
    source_quote = "退款工单未生成"
    parsed_text = f"退款记录摘要\n{source_quote}\n仍需与平台记录核对"
    submitted = request_document["context_envelope"]["visible_evidence"][0]
    submitted.update(
        {
            "content_type": "text/markdown",
            "file_hash": hashlib.sha256(b"submission-preview").hexdigest(),
            "parsed_text": parsed_text,
        }
    )
    parsed_content_sha256 = hashlib.sha256(parsed_text.encode("utf-8")).hexdigest()
    request_document["context_envelope"]["evidence_content_authorities"] = [
        {
            "schema_version": "evidence_content_authority.v1",
            "case_id": request_document["context_envelope"]["case_snapshot"][
                "case_id"
            ],
            "evidence_id": "EVIDENCE_signature_photo",
            "file_sha256": submitted["file_hash"],
            "content_type": "text/markdown",
            "parser_version": "PARSER_TEST_V1",
            "parsed_content_sha256": parsed_content_sha256,
            "parsed_text": parsed_text,
            "parsed_byte_length": len(parsed_text.encode("utf-8")),
            "completed_at": "2026-08-17T10:00:00+08:00",
            "status": "SUCCEEDED",
        }
    ]
    request = EvidenceTurnRequest.model_validate(request_document)
    current_event = request.context_envelope.current_event
    assert current_event.event_type == "PARTY_MESSAGE"
    assert current_event.message_type == "PARTY_EVIDENCE_REFERENCE"
    assert current_event.attachment_refs

    working_set = EvidenceContextAssembler().assemble(request).working_set
    proposal = {
        "schema_version": "public_evidence_observation.v1",
        "provider_slot_id": "OBS_01",
        "evidence_id": "EVIDENCE_signature_photo",
        "fact_id": "FACT_SIGNATURE",
        "observation_kind": "PARSED_TRANSACTION_STATUS",
        "epistemic_status": "PROVISIONAL",
        "parsed_content_sha256": parsed_content_sha256,
        "source_quote": source_quote,
    }
    canonical_observation = validate_public_observation_prefix(
        prior_accepted=(),
        candidate=proposal,
        evidence_content_authorities=request.context_envelope.evidence_content_authorities,
        visible_evidence=request.context_envelope.visible_evidence,
        attachment_refs=current_event.attachment_refs,
        allowed_fact_targets=working_set.allowed_fact_targets,
        case_id=request.context_envelope.case_snapshot.case_id,
        actor_id=request.context_envelope.actor_snapshot.actor_id,
        actor_role=request.context_envelope.actor_snapshot.actor_role,
    )
    assessment = {
        "evidence_id": "EVIDENCE_signature_photo",
        "public_observation_ids": [canonical_observation.observation_id],
        "analysis_method": "TEXT_ONLY",
        "inspected_modalities": ["PARSED_TEXT"],
        "authenticity_score": 0.55,
        "relevance_score": 0.82,
        "completeness_score": 0.48,
        "assessment_confidence": 0.72,
        "source_basis": ["当前可读取的截图文字。"],
        "fact_links": [
            {
                "fact_id": "FACT_SIGNATURE",
                "relation": "INCONCLUSIVE",
                "reason": "截图涉及签收记录，但签收人字段不清晰。",
                "confidence": 0.61,
            }
        ],
        "supported_fact_ids": [],
        "unsupported_claims": ["当前截图不足以单独还原实际签收人身份。"],
        "formation_time_assessment": "截图形成时间仍需核对。",
        "recommendation": "PLAUSIBLE",
        "summary": "截图涉及签收争议，但覆盖范围有限。",
    }
    expected_reply = compose_evidence_submission_public_reply(
        fact_targets=working_set.allowed_fact_targets,
        public_observations=[canonical_observation],
        evidence_assessments=[assessment],
        human_review_tasks=[],
    )
    accepted_result = EvidenceTurnResult(
        room_utterance=expected_reply,
        public_observations=[canonical_observation],
        evidence_assessments=[assessment],
        internal_handoff={
            "evidence_change_summary": "已读取本轮证据。",
            "matrix_change_summary": "冻结事实坐标保持不变。",
            "remaining_conflicts": ["签收人身份仍待核验。"],
            "uncovered_fact_ids": ["FACT_SIGNATURE"],
            "human_review_evidence_ids": [],
            "judge_attention_points": ["核对原始签收记录。"],
        },
        referenced_evidence_ids=["EVIDENCE_signature_photo"],
        confidence=0.72,
    )
    raw_provider_reply = "该证据真实有效，因此商家应当退款。"

    class SubmissionWorkflow:
        def __init__(self, result: EvidenceTurnResult, bridge: _EvidencePreviewBridge) -> None:
            self.result = result
            self.bridge = bridge
            self.calls = 0
            self.live_visible_text = ""

        async def arun(self, provider_request: EvidenceTurnRequest) -> EvidenceTurnResult:
            self.calls += 1
            assert provider_request == request
            observer = current_stream_observer()
            assert observer is not None
            observer.visible_delta(
                EVIDENCE_PUBLIC_NODE,
                "public_observations",
                json.dumps(proposal, ensure_ascii=False, separators=(",", ":")),
            )
            observer.visible_delta(
                EVIDENCE_PUBLIC_NODE,
                EVIDENCE_PUBLIC_FIELD,
                raw_provider_reply,
            )
            self.live_visible_text = self.bridge.policy.visible_text
            observer.usage(
                node_name=EVIDENCE_PUBLIC_NODE,
                model="evidence-submission-test-model",
                latency_ms=1,
                token_usage={"input": 5, "output": 3, "total": 8},
            )
            return self.result

    async def invoke(
        candidate: EvidenceTurnResult,
        *,
        run_id: str,
    ) -> tuple[EvidenceTurnResult, object, _EvidencePreviewBridge, SubmissionWorkflow]:
        policy = _SubmissionObservationPublicOutputPolicy(request)
        bridge = _EvidencePreviewBridge(provider_request=request, policy=policy)
        workflow = SubmissionWorkflow(candidate, bridge)
        executor = object.__new__(CompiledEvidenceTurnExecutor)
        executor._workflow = workflow
        executor._preview_bridges = {run_id: bridge}
        observer = AgentStreamObserver(
            operation="evidence_turn",
            run_id=run_id,
            publish=lambda event: (
                bridge.publish(event)
                if isinstance(event, StreamVisibleDeltaEvent)
                else bridge.observed_usage.append(event)
            ),
            public_output_policy=policy,
        )
        bridge.bind(observer)
        observer.begin_public_output(EVIDENCE_PUBLIC_NODE, EVIDENCE_PUBLIC_FIELD)
        result, usage = await executor._invoke_workflow(
            request,
            logical_run_id=run_id,
        )
        return result, usage, bridge, workflow

    result, usage, bridge, workflow = await invoke(
        accepted_result,
        run_id="target-evidence-run:submission-terminal-authority",
    )

    assert workflow.calls == 1
    assert workflow.live_visible_text == (
        EVIDENCE_CANONICAL_OPENING + canonical_observation.public_text
    )
    assert result.room_utterance == expected_reply
    assert bridge.policy.visible_text == expected_reply
    assert expected_reply.startswith(workflow.live_visible_text)
    assert usage.model_dump(mode="json") == {
        "input_tokens": 5,
        "output_tokens": 3,
        "total_tokens": 8,
    }

    replayed_reply = compose_evidence_submission_public_reply(
        fact_targets=working_set.allowed_fact_targets,
        public_observations=result.public_observations,
        evidence_assessments=result.evidence_assessments,
        human_review_tasks=result.human_review_tasks,
    )
    assert replayed_reply.encode("utf-8") == expected_reply.encode("utf-8")
    assert (
        bridge.policy.finalize(
            operation=EVIDENCE_PUBLIC_NODE,
            node_name=EVIDENCE_PUBLIC_NODE,
            field_name=EVIDENCE_PUBLIC_FIELD,
            final_text=replayed_reply,
        )
        == ()
    )
    assert bridge.policy.visible_text == expected_reply
    assert raw_provider_reply not in "".join(
        event.delta for event in bridge.pending
    )

    unauthorized_reply = guard_evidence_public_reply(
        "本轮仅记录一段与权威评估无关的任意终态说明。"
    )
    assert unauthorized_reply != expected_reply
    with pytest.raises(
        GraphContractError,
        match="^EVIDENCE_PUBLIC_OUTPUT_TERMINAL_MISMATCH$",
    ):
        await invoke(
            accepted_result.model_copy(
                update={"room_utterance": unauthorized_reply}
            ),
            run_id="target-evidence-run:submission-terminal-unauthorized",
        )


@pytest.mark.asyncio
async def test_target_evidence_preview_close_cancels_without_terminal_side_effects() -> None:
    import asyncio
    from types import SimpleNamespace

    from langgraph.checkpoint.memory import InMemorySaver

    from app.agents.evidence_clerk.public_reply import (
        EVIDENCE_CANONICAL_OPENING,
        guard_evidence_public_reply,
    )
    from app.graph_runtime.evidence_turn_executor import CompiledEvidenceTurnExecutor
    from app.schemas import EvidenceTurnResult
    from app.streaming import current_stream_observer

    safe_sentence = EVIDENCE_CANONICAL_OPENING
    logical_run_id = "target-evidence-run:preview-cancellation"

    class CancellableWorkflow:
        def __init__(self) -> None:
            self.calls = 0
            self.provider_started = asyncio.Event()
            self.provider_cancelled = asyncio.Event()
            self.workflow_cancelled = asyncio.Event()
            self.never_complete = asyncio.Event()

        async def _provider_call(self) -> None:
            self.provider_started.set()
            try:
                await self.never_complete.wait()
            except asyncio.CancelledError:
                self.provider_cancelled.set()
                raise

        async def arun(self, request):
            self.calls += 1
            observer = current_stream_observer()
            assert observer is not None
            assert observer.finalized_public_output == EVIDENCE_CANONICAL_OPENING
            observer.visible_delta(
                "evidence_turn",
                "room_utterance",
                safe_sentence,
            )
            try:
                await self._provider_call()
            except asyncio.CancelledError:
                self.workflow_cancelled.set()
                raise
            raise AssertionError("cancelled Evidence workflow unexpectedly completed")

    class TrackingSaver(InMemorySaver):
        def __init__(self) -> None:
            super().__init__()
            self.terminal_commits = []

        async def acommit_external_terminal(self, config, commit):
            self.terminal_commits.append(commit)
            return config

    class TrackingStore:
        def __init__(self) -> None:
            self.puts = []

        async def put(self, **kwargs):
            self.puts.append(dict(kwargs))
            raise AssertionError("cancelled preview must not persist a proposal")

    workflow = CancellableWorkflow()
    saver = TrackingSaver()
    store = TrackingStore()
    executor = CompiledEvidenceTurnExecutor(saver=saver, workflow=workflow)
    request = SimpleNamespace(
        agent_context=SimpleNamespace(
            agent_invocation_id="AGENT_INVOCATION_PREVIEW_CANCELLATION"
        ),
        context_envelope=SimpleNamespace(
            current_event=SimpleNamespace(
                event_type="ROOM_OPENING",
                message_type="AGENT_MESSAGE",
                attachment_refs=(),
            )
        ),
    )
    execution = SimpleNamespace(
        admission=SimpleNamespace(
            command=SimpleNamespace(
                logical_run_id=logical_run_id,
                attempt_id=logical_run_id + ":1",
                actor_scope=SimpleNamespace(audience="USER"),
            )
        )
    )

    async def load_invocation(received_execution, received_store):
        assert received_execution is execution
        assert received_store is store
        return ({}, request, "a" * 64)

    def initial_state(received_execution, **kwargs):
        assert received_execution is execution
        assert kwargs["request"] is request
        return {}

    def provider_governed_request(received_execution, received_request):
        assert received_execution is execution
        assert received_request is request
        return request

    async def run_or_replay(received_execution, initial):
        assert received_execution is execution
        assert initial == {}
        result, usage = await executor._invoke_workflow(
            request,
            logical_run_id=logical_run_id,
        )
        return (
            {
                "evidence_turn_result": EvidenceTurnResult.model_validate(
                    result
                ).model_dump(mode="json"),
                "usage": usage.model_dump(mode="json"),
                "completed_at": "2099-01-01T00:00:00Z",
                "cognitive_revision": 1,
            },
            {"configurable": {"checkpoint_ns": "", "checkpoint_id": "unused"}},
        )

    executor._load_invocation = load_invocation
    executor._initial_state = initial_state
    executor._provider_governed_request = provider_governed_request
    executor._run_or_replay = run_or_replay

    stream = executor.stream(execution, store=store).__aiter__()
    emitted = [await anext(stream), await asyncio.wait_for(anext(stream), timeout=1.0)]
    assert [event.event_type for event in emitted] == [
        "attempt_started",
        "visible_delta",
    ]
    assert emitted[1].payload.delta == safe_sentence
    assert guard_evidence_public_reply(safe_sentence).startswith(safe_sentence)
    await asyncio.wait_for(workflow.provider_started.wait(), timeout=1.0)
    assert workflow.calls == 1
    assert logical_run_id in executor._preview_bridges

    await stream.aclose()
    await asyncio.wait_for(workflow.provider_cancelled.wait(), timeout=1.0)
    await asyncio.wait_for(workflow.workflow_cancelled.wait(), timeout=1.0)

    assert store.puts == []
    assert saver.terminal_commits == []
    assert executor._preview_bridges == {}
    assert all(event.event_type not in {"usage", "final"} for event in emitted)


def test_target_fixture_transport_emits_a_valid_intake_cognition_draft_without_network() -> None:
    now = datetime.now(timezone.utc)
    transport = TargetE2EDeterministicFixtureTransport(
        activation_id="p9act.v1." + "a" * 32,
        fixture_set_id="fixture-set-1",
        fixture_set_hash="b" * 64,
        binding_hash="c" * 64,
        candidate_sha="d" * 40,
    )
    def request_for(content: str) -> ModelTransportRequest:
        return ModelTransportRequest(
        node_name="intake_lcel",
        messages=(HumanMessage(content="trusted system placeholder"), HumanMessage(content=content)),
        output_type=IntakeCognitionDraft,
        governed_request=GovernedProviderRequest(
            provider=TARGET_E2E_FIXTURE_PROVIDER,
            model=TARGET_E2E_FIXTURE_MODEL,
            temperature=0,
            max_output_tokens=1024,
            response_format="STRICT_JSON_SCHEMA",
            tool_allowlist=(),
            deadline_at=now + timedelta(minutes=1),
            provider_attempts_remaining=1,
            repairs_remaining=0,
        ),
    )

    initiator = transport.generate(
        request_for(
            "Authorized audience: USER\n"
            "<authorized_dossier_json>{}</authorized_dossier_json>"
        )
    )
    respondent = transport.generate(
        request_for(
            "Authorized audience: MERCHANT\n"
            "<authorized_dossier_json>{\"case_fact_matrix\":{\"schema_version\":\"case_fact_matrix.v2\",\"fact_rows\":[{\"fact_id\":\"FACT_TARGET_E2E_DELIVERY\",\"category\":\"FULFILLMENT\",\"fact_target\":\"Whether the signed parcel was received by the user.\",\"materiality\":\"CORE\"}]}}</authorized_dossier_json>"
        )
    )

    assert initiator.model == TARGET_E2E_FIXTURE_MODEL
    initiator_draft = IntakeCognitionDraft.model_validate_json(initiator.json_document)
    assert initiator_draft.matrix_patch.schema_version == "unilateral_case_matrix.draft.v1"
    assert initiator_draft.dossier_patch.case_story == {
        "one_sentence_summary": "The parties ask the platform to resolve a signed-but-not-received parcel dispute."
    }
    assert initiator_draft.dossier_patch.claim_resolution == {
        "requested_resolution": "REFUND",
        "reason_summary": "The initiator reports that the signed parcel was not received.",
        "position_summary": "The initiator requests a refund for the undelivered parcel.",
    }
    assert initiator_draft.dossier_patch.dispute_core_state == {
        "core_conflict": "Whether the signed parcel was actually received by the user.",
        "facts_in_dispute": ["Parcel receipt after a signed delivery record."],
        "next_verification_focus": ["Delivery record and recipient confirmation."],
    }
    assert (
        IntakeCognitionDraft.model_validate_json(respondent.json_document)
        .matrix_patch.schema_version
        == "case_fact_matrix.delta.v2"
    )
    assert transport.fixture_binding_hash == transport.fixture_binding_hash
