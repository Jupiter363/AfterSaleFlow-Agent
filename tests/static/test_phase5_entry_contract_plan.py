from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
EXECUTION_PLAN = ROOT / "plans/phase-5-evidence-pilot-execution.md"
TEST_BATCHES = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
CONTRACT_PACK = (
    ROOT / "docs/runbooks/temporal-first/phase-5-p5.0-contract-pack.md"
)
PRE_ENTRY_CORRECTION = (
    ROOT
    / "docs/architecture/adr/"
    "0013-phase-5-evidence-pre-entry-contract-correction.md"
)
EVIDENCE_CONTRACT_ROOT = ROOT / "contracts/agent-platform/evidence/v2"
MIGRATIONS = ROOT / "java-api-service/src/main/resources/db/migration"
EVIDENCE_MIGRATION = "V043_4__evidence_graph_bindings.sql"


def _defines_or_carries_field(value: Any, field: str) -> bool:
    if isinstance(value, dict):
        if field in value:
            return True
        required = value.get("required")
        if isinstance(required, list) and field in required:
            return True
        return any(_defines_or_carries_field(item, field) for item in value.values())
    if isinstance(value, list):
        return any(_defines_or_carries_field(item, field) for item in value)
    return False


def _mapping_values_for_key(value: Any, key: str) -> list[Any]:
    matches: list[Any] = []
    if isinstance(value, dict):
        for item_key, item_value in value.items():
            if item_key == key:
                matches.append(item_value)
            matches.extend(_mapping_values_for_key(item_value, key))
    elif isinstance(value, list):
        for item in value:
            matches.extend(_mapping_values_for_key(item, key))
    return matches


def test_phase5_evidence_migration_follows_all_committed_intake_subversions() -> None:
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    batches = yaml.safe_load(TEST_BATCHES.read_text(encoding="utf-8"))
    owner_c = batches["owners"]["C"]

    assert (MIGRATIONS / "V043_2__intake_shadow_comparisons.sql").is_file()
    assert (MIGRATIONS / "V043_3__intake_signed_synthetic_admission.sql").is_file()
    assert (
        f"java-api-service/src/main/resources/db/migration/{EVIDENCE_MIGRATION}"
        in owner_c["change_routes"]
    )
    assert EVIDENCE_MIGRATION in execution
    assert EVIDENCE_MIGRATION in contract
    assert "V043_2__evidence_graph_bindings.sql" not in execution
    assert "V043_2__evidence_graph_bindings.sql" not in contract
    assert "V043_2__evidence_graph_bindings.sql" not in TEST_BATCHES.read_text(
        encoding="utf-8"
    )


def test_phase5_entry_requires_corrected_manifest_authority_before_batch0() -> None:
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    adr = PRE_ENTRY_CORRECTION.read_text(encoding="utf-8")

    for document in (execution, contract, adr):
        assert "signature_algorithm=ES256" in document
        assert "JOSE_P1363_BASE64URL" in document
        assert "ASCII_LOWERCASE_HEX_TEXT" in document
        assert "x-signature" in document
        assert "signature_encoding=" not in document
        assert "manifest_hash" in document and "signature" in document
        assert "assessment_output_schema_version=evidence-item-assessment.v1" in document
        assert (
            "terminal_output_schema_version=evidence-batch-proposal.v1" in document
        )
        assert "authorization_proof_ref" in document
        assert "BEFORE_CHECKPOINT_MUTATION" in document

    assert "no `authorization_proof_ref` field" in execution
    assert "`authorization_proof_ref` is forbidden" in contract
    assert "`authorization_proof_ref` is not" in adr
    for path in EVIDENCE_CONTRACT_ROOT.rglob("*.json"):
        payload = json.loads(path.read_text(encoding="utf-8"))
        assert not _defines_or_carries_field(payload, "authorization_proof_ref"), path

    matrix = yaml.safe_load(
        (EVIDENCE_CONTRACT_ROOT / "compatibility-matrix.yaml").read_text(
            encoding="utf-8"
        )
    )
    policy_mentions = _mapping_values_for_key(matrix, "authorization_proof_ref")
    assert all(value == "forbidden" for value in policy_mentions)

    for field in (
        "command_id",
        "logical_run_id",
        "attempt_id",
        "tenant",
        "case",
        "room identity",
        "thread_id",
        "room_epoch",
        "domain_snapshot_ref",
        "graph/checkpoint",
        "invocation/profile",
    ):
        assert field in contract
    normalized_contract = " ".join(contract.split())
    assert "RoomGraphCommand.v1` has no `fencing_token`" in normalized_contract
    assert "current Graph lease fence" in normalized_contract
    assert "tokens are distinct" in normalized_contract
    assert "Java Finalizer revalidates the room fence" in normalized_contract
    assert "not the decoded 32-byte digest" in contract
    assert "finalization receipt does not carry `profile_versions`" in contract
    assert "new exact clean detached SHA" in execution
    assert "full P5-BATCH-0" in execution
    assert "regenerated" in execution and "fixture" in execution
    assert "Python" in execution and "Java" in execution and "parity" in execution
