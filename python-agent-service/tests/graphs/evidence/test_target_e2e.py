from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
import json

from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda, RunnableSequence
import pytest

from app.contracts.v1.codec import canonical_sha256
from app.graphs.evidence.contracts import (
    TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
    TARGET_E2E_GRAPH_KEY,
    TARGET_E2E_GRAPH_VERSION,
    TARGET_E2E_OUTPUT_SCHEMA_VERSION,
    EvidenceAdmissionVerifier,
    EvidenceGraphContractError,
    evidence_execution_scope,
)
from app.graphs.evidence.lcel import (
    TargetEvidenceAsset,
    TargetEvidenceAssetLoader,
    build_target_evidence_assessment_lcel,
)
ACTIVATION_ID = "p9act.v1.0123456789abcdef0123456789abcdef"


class _FixtureAssetLoader(TargetEvidenceAssetLoader):
    async def load(self, item):
        return TargetEvidenceAsset(
            content="The package was photographed immediately after delivery.",
            source_refs=("SOURCE_TARGET_FIXTURE",),
            inspected_modalities=("PDF_METADATA", "TEXT"),
            receipt_ref=f"ASSET_RECEIPT_{item['evidence_id']}",
            receipt_hash=item["parse_hash"],
        )


def _model_payload(*, source_ref: str = "SOURCE_TARGET_FIXTURE") -> dict[str, object]:
    return {
        "assessment_status": "COMPLETED",
        "authenticity_score": 0.91,
        "authenticity_reason_codes": ["SOURCE_HASH_MATCHED"],
        "relevance_score": 0.86,
        "relevance_reason_codes": ["FACT_LINK_MATCHED"],
        "completeness_score": 0.8,
        "confidence": 0.84,
        "candidate_fact_links": [
            {"fact_id": "FACT_ORDER_DAMAGE", "source_refs": [source_ref]}
        ],
        "limitations": ["ISOLATED_PREPRODUCTION_FIXTURE"],
        "review_reasons": [],
    }


def _deterministic_model(payload: dict[str, object]):
    encoded = json.dumps(payload, separators=(",", ":"))
    return RunnableLambda(lambda _: AIMessage(content=encoded))


def _work_item(admission_request_factory) -> dict[str, object]:
    manifest = json.loads(admission_request_factory(1).signed_manifest_payload)
    binding = manifest["command_binding"]
    return {
        "schema_version": "evidence-assessment-work-item.v1",
        "execution_scope": "TARGET_E2E_CANDIDATE",
        "command_binding": {
            "command_id": binding["command_id"],
            "logical_run_id": binding["logical_run_id"],
            "attempt_id": binding["attempt_id"],
        },
        "thread_id": manifest["thread_id"],
        "manifest_id": manifest["manifest_id"],
        "manifest_hash": manifest["manifest_hash"],
        "actor_scope_hash": manifest["actor_scope_hash"],
        "profile_versions": manifest["profile_versions"],
        "item": manifest["items"][0],
    }


@pytest.mark.asyncio
async def test_target_lcel_is_prompt_model_parser_and_deterministic(
    admission_request_factory,
) -> None:
    model = _deterministic_model(_model_payload())
    lcel = build_target_evidence_assessment_lcel(
        model=model,
        asset_loader=_FixtureAssetLoader(),
    )

    assert isinstance(lcel.runnable, RunnableSequence)
    assert lcel.model is model
    first = await lcel.ainvoke(_work_item(admission_request_factory))
    second = await lcel.ainvoke(_work_item(admission_request_factory))

    assert first == second
    assert first["execution_scope"] == "TARGET_E2E_CANDIDATE"
    assert first["formal_sink_eligible"] is False
    assert first["source_refs"] == ["SOURCE_TARGET_FIXTURE"]
    assert first["assessment_hash"] == canonical_sha256(
        {key: value for key, value in first.items() if key != "assessment_hash"}
    )


@pytest.mark.asyncio
async def test_target_lcel_rejects_model_forged_source_reference(
    admission_request_factory,
) -> None:
    lcel = build_target_evidence_assessment_lcel(
        model=_deterministic_model(_model_payload(source_ref="SOURCE_UNVERIFIED")),
        asset_loader=_FixtureAssetLoader(),
    )

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_TARGET_SOURCE_REF_FORGED"):
        await lcel.ainvoke(_work_item(admission_request_factory))


def test_target_admission_is_separate_from_public_shadow_verifier(
    service_security_runtime,
    admission_request_factory,
    admission_refresher,
) -> None:
    request = admission_request_factory(1)
    command = deepcopy(dict(request.room_graph_command))
    command["graph_key"] = TARGET_E2E_GRAPH_KEY
    command["graph_version"] = TARGET_E2E_GRAPH_VERSION
    command["checkpoint_schema_version"] = TARGET_E2E_CHECKPOINT_SCHEMA_VERSION
    command["invocation_context"]["output_schema_version"] = (
        TARGET_E2E_OUTPUT_SCHEMA_VERSION
    )
    manifest = json.loads(request.signed_manifest_payload)
    manifest.update(
        execution_scope="TARGET_E2E_CANDIDATE",
        writer_mode="PROPOSAL_ONLY",
        registration_id=ACTIVATION_ID,
    )
    manifest["profile_versions"]["graph_version"] = TARGET_E2E_GRAPH_VERSION
    manifest["profile_versions"]["checkpoint_schema_version"] = (
        TARGET_E2E_CHECKPOINT_SCHEMA_VERSION
    )
    target_request = admission_refresher(
        request,
        command=command,
        manifest=manifest,
        refresh_internal_manifest_hash=True,
        resign=True,
    )
    target_request = replace(
        target_request,
        registry_output_schema_version=TARGET_E2E_OUTPUT_SCHEMA_VERSION,
    )
    verifier = EvidenceAdmissionVerifier.from_security_runtime(service_security_runtime)

    admission = verifier._verify_target_candidate(target_request)  # noqa: SLF001
    assert admission.runtime_mode == "TARGET_E2E_CANDIDATE"
    assert evidence_execution_scope(admission) == "TARGET_E2E_CANDIDATE"

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_SYNTHETIC_SHADOW_SCOPE_REQUIRED",
    ):
        verifier.verify(target_request)


def test_target_admission_rejects_shadow_relabel(
    service_security_runtime,
    admission_request_factory,
    admission_refresher,
) -> None:
    verifier = EvidenceAdmissionVerifier.from_security_runtime(service_security_runtime)
    request = admission_request_factory(1)
    command = deepcopy(dict(request.room_graph_command))
    command["graph_key"] = TARGET_E2E_GRAPH_KEY
    command["graph_version"] = TARGET_E2E_GRAPH_VERSION
    command["checkpoint_schema_version"] = TARGET_E2E_CHECKPOINT_SCHEMA_VERSION
    command["invocation_context"]["output_schema_version"] = (
        TARGET_E2E_OUTPUT_SCHEMA_VERSION
    )
    manifest = json.loads(request.signed_manifest_payload)
    manifest["profile_versions"]["graph_version"] = TARGET_E2E_GRAPH_VERSION
    manifest["profile_versions"]["checkpoint_schema_version"] = (
        TARGET_E2E_CHECKPOINT_SCHEMA_VERSION
    )
    relabeled = admission_refresher(
        request,
        command=command,
        manifest=manifest,
        refresh_internal_manifest_hash=True,
        resign=True,
    )
    relabeled = replace(
        relabeled,
        registry_output_schema_version=TARGET_E2E_OUTPUT_SCHEMA_VERSION,
    )

    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_TARGET_E2E_SCOPE_REQUIRED",
    ):
        verifier._verify_target_candidate(relabeled)  # noqa: SLF001
