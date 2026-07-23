from __future__ import annotations

import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = "c43f969f08755fd6eb90c0845809cda1785d11bf"
EVIDENCE_COMMIT = "8770d84aac4f653e8953d469246295b6e8c3b8fa"
RELEASE_ID = "phase-5-20260723-c43f969f"
EVIDENCE_RELATIVE = Path("test-reports/temporal-first") / RELEASE_ID / "phase-5"
EVIDENCE = ROOT / EVIDENCE_RELATIVE
CHECKPOINT_RELATIVE = Path(
    "docs/runbooks/temporal-first/phase-5-engineering-checkpoint.md"
)
CHECKPOINT = ROOT / CHECKPOINT_RELATIVE
TEST_RELATIVE = Path("tests/static/test_phase5_engineering_checkpoint.py")
MATRIX_RELATIVE = Path("plans/phase-5-evidence-pilot-test-batches.yaml")

SOURCE_REPORTS = {
    "python-phase5-junit.xml": ("python_phase_5_deduplicated", 362),
    "java-phase5-junit.xml": ("java_phase_5_deduplicated", 212),
    "frontend-phase5-junit.xml": ("frontend_phase_5_deduplicated", 116),
    "static-phase5-junit.xml": ("static_phase_5_deduplicated", 200),
}
COMMAND_ORDER = tuple(command_id for command_id, _ in SOURCE_REPORTS.values())
ARCHIVED_SOURCE_SHA256 = {
    "python-phase5-junit.xml": (
        "8b35e5c4469ef02c39b175376458a21e1fba59c293316c82d33b94607c445207"
    ),
    "java-phase5-junit.xml": (
        "4788c3e043d6eb08e01782825520791dcc3708c88c4e67b19ef606956503b84d"
    ),
    "frontend-phase5-junit.xml": (
        "85243751b4be73dc420a0ae28eb7c143ac145906d29d28279cf2dc4ab5453a98"
    ),
    "static-phase5-junit.xml": (
        "cdc7da524c1a4dc1591266803c4581ab089be4a5df9c9a9ff14cace1c96b9379"
    ),
}
EXECUTION_SOURCE_SHA256 = {
    **ARCHIVED_SOURCE_SHA256,
    "java-phase5-junit.xml": (
        "018eae70fe7056c3e8f683e790814a727a40f70605aca66cdada688d30be8a3d"
    ),
}
DERIVED_REPORTS = {
    "batch-0-junit.xml": ("P5-BATCH-0", 418),
    "batch-1-junit.xml": ("P5-BATCH-1", 386),
    "batch-2-junit.xml": ("P5-BATCH-2", 556),
    "batch-3-junit.xml": ("P5-BATCH-3", 890),
}
EXPECTED_EVIDENCE_FILES = {
    "artifact-sha256.json",
    "baseline-id-coverage.json",
    *SOURCE_REPORTS,
    *DERIVED_REPORTS,
    "candidate-commit.txt",
    "check-id-coverage.json",
    "external-gates.json",
    "failure-classification.json",
    "phase-metrics.json",
    "phase5-candidate-execution-manifest.json",
}
EXPECTED_STATUS = {
    "engineering_checkpoint": "PASS",
    "promotion_gate": "PENDING",
    "next_phase_permission": "PHASE_6_ENGINEERING_ONLY",
    "MIG-004": "PENDING_PROMOTION",
    "MIG-005": "PENDING_PROMOTION",
}
EXPECTED_RUNTIME_RESTRICTIONS = {
    "allowed_modes": ["LEGACY", "DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
    "formal_evidence_graph_sink": "forbidden",
    "temporal_evidence_allocation": "forbidden",
    "real_case_shadow": "forbidden",
    "canary": "forbidden",
    "promotion": "forbidden",
    "t3_unified_checkpoint": "not_executed",
}
BASELINE_IDS = frozenset(
    """
    CORE-001 CORE-002 CORE-003 CORE-004 CORE-005 CORE-006 CORE-007 CORE-008
    CORE-009 CORE-010 EVD-001 EVD-002 EVD-003 EVD-004 EVD-005 EVD-006
    EVD-007 EVD-008 EVD-009 EVD-010 EVD-011 EVD-012 EVD-013 EVD-014
    EVD-015 SEC-001 SEC-002 SEC-003 SEC-004 SEC-005 SEC-006 UI-001 UI-003
    UI-004 UI-005
    """.split()
)
CHECK_STATUSES = {
    **{
        check_id: "PASS_ENGINEERING"
        for check_id in """
        GRAPH-009 GRAPH-016 GRAPH-017 GRAPH-018 GRAPH-019 LCEL-009
        ROOM-EVIDENCE-001 ROOM-EVIDENCE-002 ROOM-EVIDENCE-003
        ROOM-EVIDENCE-004 ROOM-EVIDENCE-005 ROOM-EVIDENCE-006
        TEMP-020 TEMP-021 TEMP-022 TEMP-023 TEMP-024
        """.split()
    },
    "ENV-014": "PASS_ENGINEERING_CAPACITY_ONLY",
    "MIG-005": "PENDING_PROMOTION",
}
FAILURE_CLASSIFICATIONS = {
    "PRODUCT": {
        "action": "return_to_owner_and_block_dependent_batch",
        "candidate_checkpoint_action": "block_candidate",
    },
    "FIXTURE": {
        "action": (
            "prove_contract_correct_repair_fixture_and_rerun_affected_scope_"
            "before_candidate_freeze"
        ),
        "candidate_checkpoint_action": "block_candidate",
    },
    "INFRA": {
        "action": "preserve_trace_restore_environment_and_rerun_same_sha_failed_scope",
        "candidate_checkpoint_action": (
            "resume_same_sha_subject_to_candidate_checkpoint_limit"
        ),
    },
    "EXTERNAL_GATE": {
        "action": "record_owner_and_due_condition_without_claiming_pass",
        "candidate_checkpoint_action": "block_candidate",
    },
}


def _git(*arguments: str) -> str:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return process.stdout.decode("utf-8").strip()


def _git_bytes(*arguments: str) -> bytes:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return process.stdout


def _json(name: str) -> dict[str, Any]:
    value = json.loads((EVIDENCE / name).read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _acceptance_commit() -> str:
    commits = _git(
        "log",
        "--diff-filter=A",
        "--format=%H",
        "--",
        CHECKPOINT_RELATIVE.as_posix(),
    ).splitlines()
    assert len(commits) == 1, "checkpoint document must be introduced exactly once"
    return commits[0]


def _commit_changes(commit: str) -> set[tuple[str, str]]:
    rows = _git(
        "diff-tree",
        "--root",
        "--no-commit-id",
        "--name-status",
        "-r",
        commit,
    ).splitlines()
    return {tuple(row.split("\t", maxsplit=1)) for row in rows}


def _junit_totals(path: Path) -> dict[str, int]:
    root, cases = _junit_inventory(path)
    totals = {
        field: int(root.attrib.get(field, "0"))
        for field in ("tests", "failures", "errors", "skipped")
    }
    assert len(cases) == totals["tests"]
    assert root.attrib["candidate_commit"] == CANDIDATE
    return totals


def _junit_inventory(path: Path) -> tuple[ET.Element, list[tuple[str, str]]]:
    root = ET.parse(path).getroot()
    inventory: list[tuple[str, str]] = []
    failing_tags = {"failure", "error", "skipped"}
    for suite in root.findall("testsuite"):
        source_report = suite.attrib.get("source_report")
        assert source_report in SOURCE_REPORTS
        for case in suite.findall("testcase"):
            assert not {child.tag.rsplit("}", 1)[-1] for child in case} & failing_tags
            classname = case.attrib.get("classname")
            name = case.attrib.get("name")
            assert classname and name
            inventory.append((source_report, f"{classname}#{name}"))
    return root, inventory


def _source_inventories() -> dict[str, set[str]]:
    inventories: dict[str, set[str]] = {}
    all_identities: list[str] = []
    for filename, (command_id, expected_tests) in SOURCE_REPORTS.items():
        root, pairs = _junit_inventory(EVIDENCE / filename)
        assert root.attrib["source_command_id"] == command_id
        assert len(pairs) == expected_tests
        assert all(source_report == filename for source_report, _ in pairs)
        identities = [identity for _, identity in pairs]
        assert len(identities) == len(set(identities))
        inventories[filename] = set(identities)
        all_identities.extend(identities)
    assert len(all_identities) == 890
    assert len(set(all_identities)) == 890
    return inventories


def _validate_coverage_rows(
    rows: list[dict[str, Any]],
    expected_statuses: dict[str, str],
    inventories: dict[str, set[str]],
) -> tuple[int, int]:
    by_id = {row["id"]: row for row in rows}
    assert len(by_id) == len(rows)
    assert set(by_id) == set(expected_statuses)
    binding_count = 0
    testcase_count = 0
    for item_id, expected_status in expected_statuses.items():
        row = by_id[item_id]
        assert row["status"] == expected_status
        assert row["bindings"]
        for binding in row["bindings"]:
            report = binding["report"]
            assert report in inventories
            assert binding["selector"]
            assert binding["report_sha256"] == ARCHIVED_SOURCE_SHA256[report]
            assert binding["report_sha256"] == _sha256(EVIDENCE / report)
            testcases = binding["testcases"]
            assert testcases and len(testcases) == len(set(testcases))
            assert set(testcases) <= inventories[report]
            binding_count += 1
            testcase_count += len(testcases)
    return binding_count, testcase_count


def test_acceptance_is_separate_from_the_exact_evidence_commit() -> None:
    acceptance = _acceptance_commit()
    assert _git("show", "-s", "--format=%P", acceptance) == EVIDENCE_COMMIT
    assert _commit_changes(acceptance) == {
        ("A", CHECKPOINT_RELATIVE.as_posix()),
        ("A", TEST_RELATIVE.as_posix()),
    }

    assert _git("show", "-s", "--format=%P", EVIDENCE_COMMIT) == CANDIDATE
    expected_evidence_changes = {
        ("A", (EVIDENCE_RELATIVE / name).as_posix()) for name in EXPECTED_EVIDENCE_FILES
    }
    assert _commit_changes(EVIDENCE_COMMIT) == expected_evidence_changes
    assert {path.name for path in EVIDENCE.iterdir()} == EXPECTED_EVIDENCE_FILES
    assert (EVIDENCE / "candidate-commit.txt").read_bytes() == (
        CANDIDATE + "\n"
    ).encode("ascii")


def test_evidence_index_authenticates_fifteen_immutable_lf_git_blobs() -> None:
    index_path = EVIDENCE / "artifact-sha256.json"
    index = _json(index_path.name)
    assert index["schema_version"] == "phase5-candidate-artifact-index.v1"
    assert index["candidate_commit"] == CANDIDATE
    indexed = {item["path"]: item for item in index["artifacts"]}
    assert len(indexed) == 15
    assert set(indexed) == EXPECTED_EVIDENCE_FILES - {index_path.name}

    index_relative = (EVIDENCE_RELATIVE / index_path.name).as_posix()
    assert b"\r" not in index_path.read_bytes()
    assert index_path.read_bytes().endswith(b"\n")
    assert _git_bytes("show", f"{EVIDENCE_COMMIT}:{index_relative}") == (
        index_path.read_bytes()
    )

    for name, record in indexed.items():
        path = EVIDENCE / name
        payload = path.read_bytes()
        relative = (EVIDENCE_RELATIVE / name).as_posix()
        assert b"\r" not in payload
        assert payload.endswith(b"\n")
        assert _git_bytes("show", f"{EVIDENCE_COMMIT}:{relative}") == payload
        assert int(_git("cat-file", "-s", f"{EVIDENCE_COMMIT}:{relative}")) == len(
            payload
        )
        assert record == {
            "path": name,
            "sha256": hashlib.sha256(payload).hexdigest(),
            "bytes": len(payload),
        }


def test_manifest_and_all_eight_junit_reports_are_candidate_bound() -> None:
    manifest_path = EVIDENCE / "phase5-candidate-execution-manifest.json"
    manifest = _json(manifest_path.name)
    seal = manifest["manifest_sha256"]
    unsigned = dict(manifest)
    unsigned.pop("manifest_sha256")
    canonical = json.dumps(
        unsigned,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    assert seal == hashlib.sha256(canonical).hexdigest()
    assert manifest["candidate_commit"] == CANDIDATE
    assert manifest["status"] == "PASS"
    assert manifest["promotion_gate"] == "PENDING"
    assert manifest["MIG-004"] == "PENDING_PROMOTION"
    assert manifest["MIG-005"] == "PENDING_PROMOTION"
    assert manifest["quarantined_attempts"] == []
    assert manifest["pending_failure"] is None
    assert manifest["quarantined_attempts_reused"] is False
    assert manifest["runtime_restrictions"] == EXPECTED_RUNTIME_RESTRICTIONS

    assert [item["id"] for item in manifest["commands"]] == list(COMMAND_ORDER)
    assert len(manifest["commands"]) == 4
    commands = {item["id"]: item for item in manifest["commands"]}
    metrics = _json("phase-metrics.json")
    source_metrics = {item["command_id"]: item for item in metrics["source_reports"]}
    assert list(source_metrics) == list(COMMAND_ORDER)
    for filename, (command_id, expected_tests) in SOURCE_REPORTS.items():
        command = commands[command_id]
        source_metric = source_metrics[command_id]
        assert command["candidate_commit"] == CANDIDATE
        assert command["accepted"] is True
        assert command["exit_code"] == 0
        assert command["failure_classification"] == "NONE"
        assert command["report"] == filename
        assert command["report_path"] == f"source/{filename}"
        assert command["report_sha256"] == EXECUTION_SOURCE_SHA256[filename]
        assert command["report_sha256"] == source_metric["execution_source_sha256"]
        assert (
            command["matrix_command_sha256"] == source_metric["matrix_command_sha256"]
        )
        assert source_metric["name"] == filename
        assert source_metric["sha256"] == ARCHIVED_SOURCE_SHA256[filename]
        assert source_metric["sha256"] == _sha256(EVIDENCE / filename)
        assert source_metric["exit_code"] == 0
        assert {
            field: command[field]
            for field in ("tests", "failures", "errors", "skipped")
        } == {
            "tests": expected_tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        }

    inventories = _source_inventories()
    source_pairs = {
        (report, identity)
        for report, identities in inventories.items()
        for identity in identities
    }
    for filename, (_, expected_tests) in {
        **SOURCE_REPORTS,
        **DERIVED_REPORTS,
    }.items():
        assert _junit_totals(EVIDENCE / filename) == {
            "tests": expected_tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        }
        root, pairs = _junit_inventory(EVIDENCE / filename)
        assert len(pairs) == len(set(pairs))
        if filename in DERIVED_REPORTS:
            properties = {
                item.attrib["name"]: item.attrib["value"]
                for item in root.findall("./properties/property")
            }
            assert properties == {
                f"source_sha256.{report}": digest
                for report, digest in ARCHIVED_SOURCE_SHA256.items()
            }
            assert set(pairs) <= source_pairs
            if filename == "batch-3-junit.xml":
                assert set(pairs) == source_pairs


def test_coverage_metrics_and_external_gates_preserve_engineering_only_scope() -> None:
    baselines = _json("baseline-id-coverage.json")
    checks = _json("check-id-coverage.json")
    matrix = yaml.safe_load((ROOT / MATRIX_RELATIVE).read_text(encoding="utf-8"))
    matrix_baselines = {
        item for group in matrix["baseline_ids"].values() for item in group
    }
    matrix_checks = {
        item for group in matrix["primary_check_ids"].values() for item in group
    }
    assert matrix_baselines == BASELINE_IDS
    assert matrix_checks == set(CHECK_STATUSES)

    assert baselines["candidate_commit"] == CANDIDATE
    assert baselines["all_required_ids_mapped"] is True
    assert baselines["summary"] == {"PASS_ENGINEERING": 35, "total": 35}

    assert checks["candidate_commit"] == CANDIDATE
    assert checks["all_required_ids_mapped"] is True
    assert checks["summary"] == {
        "PASS_ENGINEERING": 17,
        "PASS_ENGINEERING_CAPACITY_ONLY": 1,
        "PENDING_PROMOTION": 1,
        "total": 19,
    }
    inventories = _source_inventories()
    baseline_bindings, baseline_testcases = _validate_coverage_rows(
        baselines["baselines"],
        {item_id: "PASS_ENGINEERING" for item_id in BASELINE_IDS},
        inventories,
    )
    check_bindings, check_testcases = _validate_coverage_rows(
        checks["checks"], CHECK_STATUSES, inventories
    )
    assert baseline_bindings + check_bindings == 66
    assert baseline_testcases + check_testcases == 76

    metrics = _json("phase-metrics.json")
    assert metrics["schema_version"] == "temporal-first-phase-metrics.v1"
    assert metrics["release_id"] == RELEASE_ID
    assert metrics["candidate_commit"] == CANDIDATE
    assert metrics["status"] == EXPECTED_STATUS
    assert metrics["runtime_restrictions"] == EXPECTED_RUNTIME_RESTRICTIONS
    assert metrics["candidate_verification"] == {
        "source_execution_mode": "RECORDED_CANDIDATE_BOUND_SOURCE_RUNNER",
        "deduplicated_execution": True,
        "runner_execution": "sequential",
        "mixed_candidate_results": False,
        "quarantined_attempts_reused": False,
        "unconditional_rerun": False,
        "same_sha_infra_reruns_per_source": 1,
        "distinct_tests": 890,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }
    assert metrics["source_execution_manifest"] == {
        "name": "phase5-candidate-execution-manifest.json",
        "sha256": _sha256(EVIDENCE / "phase5-candidate-execution-manifest.json"),
        "manifest_sha256": _json("phase5-candidate-execution-manifest.json")[
            "manifest_sha256"
        ],
    }

    source_metrics = {item["name"]: item for item in metrics["source_reports"]}
    for filename, (command_id, expected_tests) in SOURCE_REPORTS.items():
        item = source_metrics[filename]
        assert item["command_id"] == command_id
        assert item["sha256"] == _sha256(EVIDENCE / filename)
        assert {
            field: item[field] for field in ("tests", "failures", "errors", "skipped")
        } == {
            "tests": expected_tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        }

    batch_metrics = {item["report"]: item for item in metrics["batch_views"]}
    for filename, (batch_id, expected_tests) in DERIVED_REPORTS.items():
        item = batch_metrics[filename]
        assert item["id"] == batch_id
        assert item["sha256"] == _sha256(EVIDENCE / filename)
        assert {
            field: item[field] for field in ("tests", "failures", "errors", "skipped")
        } == {
            "tests": expected_tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
        }

    gates = _json("external-gates.json")
    assert gates["candidate_commit"] == CANDIDATE
    assert gates["promotion_gate"] == "PENDING"
    assert gates["promotion_gates"] == {
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }
    assert gates["runtime_restrictions"] == EXPECTED_RUNTIME_RESTRICTIONS
    assert gates["traffic_constraints"] == {
        "formal_evidence_graph_sink_allowed": False,
        "temporal_evidence_allocation_allowed": False,
        "real_case_shadow_allowed": False,
        "production_traffic_allowed": False,
        "canary_allowed": False,
        "promotion_allowed": False,
        "synthetic_fixtures_only": True,
    }
    assert gates["unified_checkpoint"] == {
        "tier": "T3",
        "automatic": False,
        "executed": False,
        "classification": "EXTERNAL_GATE",
    }


def test_failure_classification_has_no_hidden_accepted_or_reused_failure() -> None:
    document = _json("failure-classification.json")
    matrix = yaml.safe_load((ROOT / MATRIX_RELATIVE).read_text(encoding="utf-8"))
    policy = matrix["failure_classification"]
    manifest = _json("phase5-candidate-execution-manifest.json")

    assert document["schema_version"] == "temporal-first-failure-classification.v1"
    assert document["phase"] == 5
    assert document["candidate_commit"] == CANDIDATE
    assert document["classifications"] == FAILURE_CLASSIFICATIONS
    assert document["classifications"] == {
        name: policy[name] for name in policy["required_values"]
    }
    assert document["classify_before_rerun"] is True
    assert policy["classify_before_rerun"] is True
    assert document["bounded_same_sha_infra_reruns_per_source"] == 1
    assert document["accepted_source_suite_failures"] == []
    assert document["quarantined_source_attempts"] == []
    assert document["open_product_failures"] == []
    assert document["quarantined_attempts_reused"] is False
    assert document["decision"] == EXPECTED_STATUS
    assert manifest["quarantined_attempts"] == document["quarantined_source_attempts"]
    assert manifest["quarantined_attempts_reused"] is False
    assert manifest["pending_failure"] is None
    assert all(
        item["failure_classification"] == "NONE" for item in manifest["commands"]
    )


def test_checkpoint_records_pass_without_claiming_promotion_or_t3() -> None:
    checkpoint = CHECKPOINT.read_text(encoding="utf-8")
    normalized = " ".join(checkpoint.split())
    for required in (
        CANDIDATE,
        EVIDENCE_COMMIT,
        RELEASE_ID,
        EVIDENCE_RELATIVE.as_posix() + "/",
        "engineering_checkpoint: PASS",
        "promotion_gate: PENDING",
        "next_phase_permission: PHASE_6_ENGINEERING_ONLY",
        "MIG-004: PENDING_PROMOTION",
        "MIG-005: PENDING_PROMOTION",
        "362 Python",
        "212 Java",
        "116 frontend",
        "200 static",
        "890 distinct tests",
        "418, 386, 556, and 890 tests",
        "all 35 baseline IDs",
        "all 19 required Check IDs",
        "phase_5_graph_runtime: DISABLED_OR_JAVA_SIGNED_SYNTHETIC_SHADOW_ONLY",
        "legacy_formal_java_path: PRESERVED_NOT_A_GRAPH_RUNTIME_GRANT",
        "formal_evidence_graph_sink: FORBIDDEN",
        "temporal_evidence_allocation: FORBIDDEN",
        "real_case_shadow: FORBIDDEN",
        "production_traffic: FORBIDDEN",
        "canary: FORBIDDEN",
        "promotion: FORBIDDEN",
        "hearing_supplement_changes: FORBIDDEN",
        "T3_unified_checkpoint: NOT_EXECUTED",
    ):
        assert required in checkpoint
    assert "`LEGACY` is not a Graph runtime grant" in checkpoint
    assert "The T3 unified checkpoint was not executed" in normalized
    assert "MIG-004: PASS" not in checkpoint
    assert "MIG-005: PASS" not in checkpoint
    assert "promotion_gate: PASS" not in checkpoint
