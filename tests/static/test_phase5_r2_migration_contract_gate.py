from __future__ import annotations

import json
from pathlib import Path

import yaml

from scripts import run_phase5_r2_migration_contract_gate as gate


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/architecture/contracts/phase-5-evidence-migration-contract-erratum.yaml"
ADR = ROOT / "docs/architecture/adr/0014-phase-5-evidence-migration-contract-erratum.md"


def test_r2_contract_authorizes_exactly_one_additive_migration() -> None:
    contract = yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))
    adr = ADR.read_text(encoding="utf-8")

    assert contract["authorized_migration_path"] == gate.AUTHORIZED_MIGRATION
    assert contract["forbidden_migration_path"] == gate.FORBIDDEN_MIGRATION
    assert contract["forbidden_migration_sha256"] == gate.FORBIDDEN_MIGRATION_SHA256
    assert contract["additive_only"] is True
    assert set(contract["allowed_structures"]) == {
        "java_authority_snapshot",
        "evidence_finalization_receipt",
        "receipt_load_binding",
        "terminal_summary_sidecar",
        "operational_recovery_projection",
    }
    assert all(value is False for value in contract["runtime_restrictions"].values())
    assert "V043_5__evidence_finalization_and_operational_recovery.sql" in adr
    assert "V043_4__evidence_graph_bindings.sql" in adr
    assert "Temporal history or memory is never an authority source." in " ".join(
        contract["required_invariants"]
    )


def test_r2_gate_authenticates_current_candidate(monkeypatch) -> None:
    candidate = gate._git_text("rev-parse", "HEAD")
    monkeypatch.setattr(gate, "_assert_clean", lambda value: None)
    manifest = gate.authenticate(candidate)

    assert manifest["status"] == "PASS"
    assert manifest["candidate_commit"] == candidate
    assert manifest["authorized_migration_path"] == gate.AUTHORIZED_MIGRATION
    assert manifest["forbidden_migration_sha256"] == gate.FORBIDDEN_MIGRATION_SHA256
    json.dumps(manifest, sort_keys=True)
