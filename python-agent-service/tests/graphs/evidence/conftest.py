from __future__ import annotations

import json
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
import pytest
from langchain_core.runnables import RunnableLambda

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graphs.evidence.contracts import (
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    VERIFIED_ADMISSION_STEPS,
    EvidenceGraphContext,
    JsonObject,
    VerifiedEvidenceAdmission,
)


ROOT = Path(__file__).resolve().parents[4]
CONTRACT_ROOT = ROOT / "contracts" / "agent-platform"
EVIDENCE_FIXTURES = CONTRACT_ROOT / "evidence" / "v2" / "fixtures" / "valid"
COMMAND_FIXTURE = (
    CONTRACT_ROOT / "v1" / "fixtures" / "valid" / "room-graph-command-evidence-valid.json"
)


def load_manifest(count: int) -> JsonObject:
    names = {
        1: "evidence-batch-manifest-synthetic-1-valid.json",
        8: "evidence-batch-manifest-synthetic-8-valid.json",
        100: "evidence-batch-manifest-synthetic-100-valid.json",
    }
    return json.loads((EVIDENCE_FIXTURES / names[count]).read_text(encoding="utf-8"))


def make_admission(count: int = 1) -> VerifiedEvidenceAdmission:
    manifest = load_manifest(count)
    command = json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    binding = manifest["command_binding"]
    profiles = manifest["profile_versions"]
    for name in ("command_id", "logical_run_id", "attempt_id"):
        command[name] = binding[name]
    for name in ("tenant_surrogate", "case_id", "room_type", "room_epoch", "thread_id"):
        command[name] = manifest[name]
    command["deadline_at"] = binding["deadline_at"]
    command["graph_version"] = profiles["graph_version"]
    command["checkpoint_schema_version"] = profiles["checkpoint_schema_version"]
    command["invocation_context"].update(
        prompt_profile_id=profiles["prompt_version"],
        model_profile_id=profiles["model_profile_id"],
        output_schema_version=profiles["terminal_output_schema_version"],
        policy_version=profiles["policy_version"],
        guardrail_version=profiles["guardrail_version"],
    )
    command["domain_snapshot_ref"]["artifact_id"] = manifest["manifest_id"]
    command["domain_snapshot_ref"]["schema_version"] = manifest["schema_version"]
    admission = VerifiedEvidenceAdmission(
        runtime_mode="SHADOW",
        room_graph_command=command,
        manifest=manifest,
        registry_output_schema_version=TERMINAL_OUTPUT_SCHEMA_VERSION,
        graph_lease_fencing_token=41,
        validation_steps=VERIFIED_ADMISSION_STEPS,
        direct_java_es256_signature_verified=True,
    )
    return refresh_admission(admission)


def refresh_admission(
    admission: VerifiedEvidenceAdmission,
    *,
    refresh_internal_manifest_hash: bool = False,
) -> VerifiedEvidenceAdmission:
    manifest = deepcopy(dict(admission.manifest))
    command = deepcopy(dict(admission.room_graph_command))
    if refresh_internal_manifest_hash:
        preimage = dict(manifest)
        preimage.pop("manifest_hash", None)
        preimage.pop("signature", None)
        manifest["manifest_hash"] = canonical_sha256(preimage)
    full_payload = canonicalize(manifest)
    full_hash = canonical_sha256(manifest)
    snapshot = command["domain_snapshot_ref"]
    snapshot.update(
        artifact_id=manifest["manifest_id"],
        schema_version=manifest["schema_version"],
        sha256=full_hash,
        size_bytes=len(full_payload),
        uri=(
            f"s3://evidence-synthetic-manifests/{manifest['case_id']}/"
            f"epoch-{manifest['room_epoch']}/{full_hash}.json"
        ),
    )
    command_preimage = dict(command)
    command_preimage.pop("request_hash", None)
    command["request_hash"] = canonical_sha256(command_preimage)
    return replace(admission, room_graph_command=command, manifest=manifest)


def assessment_for_work_item(work_item: JsonObject) -> JsonObject:
    binding = work_item["command_binding"]
    item = work_item["item"]
    evidence_id = item["evidence_id"]
    assessment: JsonObject = {
        "schema_version": "evidence-item-assessment.v1",
        "execution_scope": "SIGNED_SYNTHETIC_ONLY",
        "formal_sink_eligible": False,
        "command_id": binding["command_id"],
        "logical_run_id": binding["logical_run_id"],
        "attempt_id": binding["attempt_id"],
        "thread_id": work_item["thread_id"],
        "manifest_id": work_item["manifest_id"],
        "manifest_hash": work_item["manifest_hash"],
        "evidence_id": evidence_id,
        "item_hash": item["item_hash"],
        "formal_evidence_revision": item["formal_evidence_revision"],
        "actor_scope_hash": work_item["actor_scope_hash"],
        "profile_versions": deepcopy(work_item["profile_versions"]),
        "assessment_status": "COMPLETED",
        "authenticity_score": 0.91,
        "authenticity_reason_codes": ["SOURCE_HASH_MATCHED"],
        "relevance_score": 0.86,
        "relevance_reason_codes": ["FACT_LINK_MATCHED"],
        "completeness_score": 0.8,
        "confidence": 0.84,
        "candidate_fact_links": [
            {"fact_id": "FACT_ORDER_DAMAGE", "source_refs": ["SOURCE_SYNTHETIC"]}
        ],
        "source_refs": ["SOURCE_SYNTHETIC"],
        "inspected_modalities": ["PDF_METADATA", "TEXT"],
        "asset_load_status": "LOADED",
        "asset_load_receipt_ref": f"ASSET_RECEIPT_{evidence_id}",
        "asset_load_receipt_hash": "2" * 64,
        "limitations": ["SYNTHETIC_FIXTURE_ONLY"],
        "review_reasons": [],
    }
    assessment["assessment_hash"] = canonical_sha256(assessment)
    return assessment


@pytest.fixture
def admission() -> VerifiedEvidenceAdmission:
    return make_admission(1)


@pytest.fixture
def context(admission: VerifiedEvidenceAdmission) -> EvidenceGraphContext:
    return EvidenceGraphContext(admission=admission, completed_at="2026-07-22T12:05:00Z")


@pytest.fixture
def assessor() -> RunnableLambda:
    return RunnableLambda(assessment_for_work_item)


@pytest.fixture
def admission_factory():
    return make_admission


@pytest.fixture
def admission_refresher():
    return refresh_admission


@pytest.fixture
def assessment_factory():
    return assessment_for_work_item
