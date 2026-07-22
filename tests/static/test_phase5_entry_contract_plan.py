from __future__ import annotations

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
EXECUTION_PLAN = ROOT / "plans/phase-5-evidence-pilot-execution.md"
TEST_BATCHES = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
CONTRACT_PACK = (
    ROOT / "docs/runbooks/temporal-first/phase-5-p5.0-contract-pack.md"
)
MIGRATIONS = ROOT / "java-api-service/src/main/resources/db/migration"
EVIDENCE_MIGRATION = "V043_4__evidence_graph_bindings.sql"


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
