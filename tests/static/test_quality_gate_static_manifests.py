from __future__ import annotations

import re
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[2]
STATIC_TESTS = ROOT / "tests" / "static"
WORKFLOW = ROOT / ".github" / "workflows" / "quality-gate.yml"
CURRENT_MANIFEST = ROOT / ".github" / "static-current-contracts.txt"
FROZEN_MANIFEST = ROOT / ".github" / "static-frozen-evidence-verifiers.txt"
HISTORICAL_MANIFEST = ROOT / ".github" / "static-historical-replay-only.txt"
SAFE_SELECTOR = re.compile(r"tests/static/test_[A-Za-z0-9_]+\.py")
SAFE_NODE = re.compile(
    r"tests/static/test_[A-Za-z0-9_]+\.py::test_[A-Za-z0-9_]+(?:\[[^\]\r\n]+\])?"
)

EXPECTED_HISTORICAL_NODES = {
    "tests/static/test_phase5_r2_migration_contract_gate.py::test_r2_gate_authenticates_current_candidate",
    "tests/static/test_phase6_hearing_contracts.py::test_source_snapshot_hashes_are_exact_raw_git_blob_pins",
    "tests/static/test_phase7_outcome_contracts.py::test_source_snapshot_is_bound_to_exact_accepted_a6_git_blobs",
    "tests/static/test_phase8_p8_0_entry_checkpoint.py::test_e8_manifest_attempt_and_junit_are_exact_candidate_all_green",
    "tests/static/test_phase8_p8_0_entry_checkpoint.py::test_superseded_a8_chain_remains_reachable_but_has_historical_authority_only",
    "tests/static/test_phase8_production_hardening_plan.py::test_replacement_c8_reauthenticates_the_post_a8_contract_correction",
}

REQUIRED_FROZEN = {
    "tests/static/test_phase5_engineering_checkpoint.py",
    "tests/static/test_phase5_p5_0_entry_checkpoint.py",
    "tests/static/test_phase6_engineering_checkpoint.py",
    "tests/static/test_phase6_p6_0_entry_checkpoint.py",
    "tests/static/test_phase7_engineering_checkpoint.py",
    "tests/static/test_phase7_p7_0_entry_checkpoint.py",
    "tests/static/test_phase8_engineering_checkpoint.py",
}


def _entries(manifest: Path) -> list[str]:
    return [
        line.strip()
        for line in manifest.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _assert_safe_existing_selectors(entries: list[str]) -> None:
    for entry in entries:
        selector = PurePosixPath(entry)
        assert entry == selector.as_posix()
        assert SAFE_SELECTOR.fullmatch(entry)
        assert selector.parts[:2] == ("tests", "static")
        assert not selector.is_absolute()
        assert ".." not in selector.parts
        assert (ROOT / selector).is_file()


def test_static_manifests_are_safe_disjoint_and_complete() -> None:
    file_manifests = {
        "current": _entries(CURRENT_MANIFEST),
        "frozen": _entries(FROZEN_MANIFEST),
    }

    for entries in file_manifests.values():
        assert entries == sorted(entries)
        assert len(entries) == len(set(entries))
        _assert_safe_existing_selectors(entries)

    memberships: dict[str, list[str]] = {}
    for category, entries in file_manifests.items():
        for entry in entries:
            memberships.setdefault(entry, []).append(category)

    assert all(len(categories) == 1 for categories in memberships.values())
    repository_tests = {
        path.relative_to(ROOT).as_posix() for path in STATIC_TESTS.glob("test_*.py")
    }
    assert set(memberships) == repository_tests
    assert set(file_manifests["frozen"]) >= REQUIRED_FROZEN


def test_historical_nodes_are_exact_safe_current_file_deselections() -> None:
    current = set(_entries(CURRENT_MANIFEST))
    historical_nodes = _entries(HISTORICAL_MANIFEST)

    assert historical_nodes == sorted(historical_nodes)
    assert len(historical_nodes) == len(set(historical_nodes))
    assert set(historical_nodes) == EXPECTED_HISTORICAL_NODES
    for node in historical_nodes:
        assert SAFE_NODE.fullmatch(node)
        selector, separator, test_name = node.partition("::")
        assert separator == "::"
        assert test_name.startswith("test_")
        _assert_safe_existing_selectors([selector])
        assert selector in current


def test_quality_gate_bootstraps_auditor_then_runs_partitioned_manifests() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    bootstrap = "python -m pytest -q tests/static/test_quality_gate_static_manifests.py"
    bootstrap_position = workflow.index(bootstrap)
    current_position = workflow.index("name: Static current repository contracts")
    frozen_position = workflow.index("name: Static frozen evidence verifiers")
    assert workflow.count(bootstrap) == 1
    assert bootstrap_position < current_position < frozen_position
    assert "manifest=.github/static-current-contracts.txt" in workflow
    assert "historical_manifest=.github/static-historical-replay-only.txt" in workflow
    assert 'deselect_args+=("--deselect=$historical_node")' in workflow
    assert 'python -m pytest -q "${deselect_args[@]}" "${selectors[@]}"' in workflow
    assert "manifest=.github/static-frozen-evidence-verifiers.txt" in workflow
    assert workflow.count('python -m pytest -q "${selectors[@]}"') == 1
    assert "python -m pytest tests/static -q" not in workflow
    assert "eval " not in workflow
