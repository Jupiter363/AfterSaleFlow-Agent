from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]

C7 = "4ddeeabb39ce7b7de41ecc4f44e17ece389d2840"
E7 = "f1c1ca16228641f1072eb358c6df9235dc239914"
A7 = "e3acedc64d161f0342c8db3d5c313c2f404ea462"
SUPERSEDED_C8 = "6d4f9946ab357a7d3193ea1680473fe923322eb0"
SUPERSEDED_E8 = "4dc398d359806ab41ea702df54112956d17920ae"
SUPERSEDED_A8 = "7e3cbace3d206aef5eb23a03d36878a00634c9a9"
SUPERSEDED_A8_REF = "refs/tags/phase8-superseded-a8-7e3cbace"

EXECUTION_PLAN = ROOT / "plans/phase-8-production-hardening-execution.md"
TEST_BATCHES = ROOT / "plans/phase-8-production-hardening-test-batches.yaml"
OWNER_BRIEFS = ROOT / "plans/phase-8-owner-briefs.yaml"
CONTRACT_PACK = ROOT / "docs/runbooks/temporal-first/phase-8-p8.0-contract-pack.md"
BASELINE_INVENTORY = (
    ROOT / "docs/runbooks/temporal-first/phase-8-p8.0-baseline-inventory.md"
)
REVIEW_CLOSURE = ROOT / "docs/runbooks/temporal-first/phase-8-p8.0-review-closure.md"
SOURCE_PLAN = ROOT / "plans/temporal-langgraph-room-refactor.md"
PHASE7_CHECKPOINT = (
    ROOT / "docs/runbooks/temporal-first/phase-7-engineering-checkpoint.md"
)
ENTRY_RUNNER = ROOT / "scripts/run_phase8_entry_checkpoint.py"
EVIDENCE_GENERATOR = ROOT / "scripts/generate_phase8_entry_evidence.py"
ENTRY_RUNNER_TEST = ROOT / "tests/static/test_phase8_entry_runner.py"
ENTRY_EVIDENCE_TEST = ROOT / "tests/static/test_phase8_entry_evidence.py"

CONTRACT_ARTIFACTS = (
    EXECUTION_PLAN,
    TEST_BATCHES,
    OWNER_BRIEFS,
    CONTRACT_PACK,
    BASELINE_INVENTORY,
    REVIEW_CLOSURE,
)
PHASE8_BUNDLE = (*CONTRACT_ARTIFACTS, Path(__file__))
ENTRY_TOOLING = (
    ENTRY_RUNNER,
    EVIDENCE_GENERATOR,
    ENTRY_RUNNER_TEST,
    ENTRY_EVIDENCE_TEST,
)
C8_CONTRACT_ALLOWLIST = (*PHASE8_BUNDLE, SOURCE_PLAN, *ENTRY_TOOLING)
FUTURE_SECURITY_PATHS = {
    "deploy/production/phase8/security/workload-identities.yaml",
    "deploy/production/phase8/security/rbac.yaml",
    "deploy/production/phase8/security/network-policies.yaml",
    "deploy/production/phase8/security/mtls-policies.yaml",
    "deploy/production/phase8/security/kms-vault-policy.yaml",
    "deploy/production/phase8/security/object-store-policy.yaml",
    "tests/static/test_phase8_security_manifests.py",
    "docs/runbooks/temporal-first/phase-8-security-hardening.md",
}


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _yaml(path: Path) -> dict[str, Any]:
    value = yaml.safe_load(_text(path))
    assert isinstance(value, dict), path
    return value


def _git(*arguments: str) -> str:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def _c8_commit() -> str:
    test_path = Path(__file__).relative_to(ROOT).as_posix()
    introductions = _git(
        "log", "--diff-filter=A", "--format=%H", "--", test_path
    ).splitlines()
    assert introductions, "the P8.0 contract candidate C8 is not committed"
    return introductions[0]


def _contract_token_text(*paths: Path) -> str:
    text = "\n".join(_text(path) for path in paths).upper()
    return re.sub(r"[^A-Z0-9]+", "_", text).strip("_")


def _assert_any(text: str, *tokens: str) -> None:
    assert any(token in text for token in tokens), tokens


def _key_values(value: Any, keys: set[str]) -> list[Any]:
    matches: list[Any] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key in keys:
                matches.append(child)
            matches.extend(_key_values(child, keys))
    elif isinstance(value, list):
        for child in value:
            matches.extend(_key_values(child, keys))
    return matches


def _assert_int_contract(value: dict[str, Any], expected: int, *keys: str) -> None:
    matches = _key_values(value, set(keys))
    assert matches, keys
    assert any(
        item == expected or isinstance(item, list) and len(item) == expected
        for item in matches
    ), (keys, matches)


def _assert_forbidden_contract(value: dict[str, Any], *keys: str) -> None:
    matches = _key_values(value, set(keys))
    assert matches, keys
    assert all(
        item is False or isinstance(item, str) and item.upper() == "FORBIDDEN"
        for item in matches
    ), (keys, matches)


def test_phase8_contract_candidate_has_exact_seven_file_bundle() -> None:
    expected_bundle = {
        "plans/phase-8-production-hardening-execution.md",
        "plans/phase-8-production-hardening-test-batches.yaml",
        "plans/phase-8-owner-briefs.yaml",
        "docs/runbooks/temporal-first/phase-8-p8.0-contract-pack.md",
        "docs/runbooks/temporal-first/phase-8-p8.0-baseline-inventory.md",
        "docs/runbooks/temporal-first/phase-8-p8.0-review-closure.md",
        "tests/static/test_phase8_production_hardening_plan.py",
    }
    actual_bundle = {path.relative_to(ROOT).as_posix() for path in PHASE8_BUNDLE}

    assert len(PHASE8_BUNDLE) == 7
    assert actual_bundle == expected_bundle
    assert all(path.is_file() for path in PHASE8_BUNDLE)
    assert len(C8_CONTRACT_ALLOWLIST) == 12
    assert all(path.is_file() for path in ENTRY_TOOLING)

    allowlist_text = _contract_token_text(EXECUTION_PLAN, TEST_BATCHES, CONTRACT_PACK)
    for path in C8_CONTRACT_ALLOWLIST:
        relative = path.relative_to(ROOT).as_posix()
        token = re.sub(r"[^A-Z0-9]+", "_", relative.upper()).strip("_")
        assert token in allowlist_text, relative


def test_c8_commit_is_exact_allowlist_sole_parent_child_of_a7() -> None:
    test_path = Path(__file__).relative_to(ROOT).as_posix()
    c8 = _c8_commit()

    assert _git("rev-list", "--parents", "-n", "1", c8).split() == [c8, A7]
    records = _git(
        "diff-tree",
        "--no-commit-id",
        "--name-status",
        "-r",
        "--no-renames",
        c8,
    ).splitlines()
    changed = {}
    for record in records:
        status, path = record.split("\t")
        changed[path.replace("\\", "/")] = status
    expected = {path.relative_to(ROOT).as_posix() for path in C8_CONTRACT_ALLOWLIST}
    assert set(changed) == expected
    assert changed[test_path] == "A"
    assert all(status in {"A", "M"} for status in changed.values())


def test_replacement_c8_reauthenticates_the_post_a8_contract_correction() -> None:
    batches = _yaml(TEST_BATCHES)
    briefs = _yaml(OWNER_BRIEFS)
    expected_history = {
        "contract_candidate_C8": SUPERSEDED_C8,
        "entry_evidence_E8": SUPERSEDED_E8,
        "entry_checkpoint_A8": SUPERSEDED_A8,
        "preserved_ref": SUPERSEDED_A8_REF,
        "preserved_ref_must_not_move": True,
        "authority_scope": "HISTORICAL_OLD_CONTRACT_ONLY",
        "authorizes_replacement_contract_or_implementation": False,
    }
    replacement = batches["gate"]["superseded_historical_entry_chain"]
    assert {key: replacement[key] for key in expected_history} == expected_history
    assert replacement["replacement_requires"] == [
        "NEW_CONTRACT_ONLY_C8_SOLE_PARENT_DIRECT_CHILD_OF_EXACT_A7",
        "FRESH_EXACT_SHA_BATCH_0_WITH_NO_REUSED_REPORT_OR_RECEIPT",
        "NEW_EVIDENCE_ONLY_E8_SOLE_PARENT_DIRECT_CHILD_OF_REPLACEMENT_C8",
        "NEW_CHECKPOINT_ONLY_A8_SOLE_PARENT_DIRECT_CHILD_OF_REPLACEMENT_E8",
    ]
    assert briefs["authority"]["superseded_historical_entry_chain"] == expected_history
    assert _git("rev-parse", f"{SUPERSEDED_A8_REF}^{{commit}}") == SUPERSEDED_A8
    assert _git("rev-list", "--parents", "-n", "1", SUPERSEDED_C8).split() == [
        SUPERSEDED_C8,
        A7,
    ]
    assert _git("rev-list", "--parents", "-n", "1", SUPERSEDED_E8).split() == [
        SUPERSEDED_E8,
        SUPERSEDED_C8,
    ]
    assert _git("rev-list", "--parents", "-n", "1", SUPERSEDED_A8).split() == [
        SUPERSEDED_A8,
        SUPERSEDED_E8,
    ]

    for path in (EXECUTION_PLAN, TEST_BATCHES, OWNER_BRIEFS, CONTRACT_PACK):
        text = _text(path)
        for historical_sha in (SUPERSEDED_C8, SUPERSEDED_E8, SUPERSEDED_A8):
            assert historical_sha in text
        assert "HISTORICAL_OLD_CONTRACT_ONLY" in text

    replacement_c8 = _c8_commit()
    assert replacement_c8 != SUPERSEDED_C8
    assert _git("rev-parse", f"{replacement_c8}^") == A7


def test_master_plan_links_contracts_without_relaxing_release_entry() -> None:
    source = _text(SOURCE_PLAN)
    phase8 = source.split("### 7.9 Phase 8", maxsplit=1)[1].split("## 8.", maxsplit=1)[
        0
    ]
    normalized_phase8 = " ".join(phase8.split())

    for path in CONTRACT_ARTIFACTS:
        assert path.name in phase8
    assert A7 in phase8
    assert "PHASE_8_ENGINEERING_ONLY" in phase8
    assert "P8.0 engineering contract and entry lane" in phase8
    for marker in ("contract-only `C8`", "evidence-only `E8`", "checkpoint-only `A8`"):
        assert marker in phase8
    assert "only `A8` may record P8.0 `PASS`" in phase8
    assert "`MIG-000..007=PASS` remains mandatory" in normalized_phase8
    for forbidden_release_effect in (
        "production traffic",
        "canary",
        "promotion",
        "destructive cleanup",
        "`MIG-008=PASS`",
    ):
        assert forbidden_release_effect in normalized_phase8
    assert "**进入条件**：`MIG-000..007=PASS`" in phase8


def test_exact_a7_handoff_chain_and_engineering_ceiling_are_bound() -> None:
    assert _git("rev-list", "--parents", "-n", "1", E7).split() == [E7, C7]
    assert _git("rev-list", "--parents", "-n", "1", A7).split() == [A7, E7]
    _git("merge-base", "--is-ancestor", A7, "HEAD")

    checkpoint = _text(PHASE7_CHECKPOINT)
    for marker in (
        f"Candidate `C7`: `{C7}`",
        f"Evidence commit `E7`: `{E7}`",
        "engineering_checkpoint: PASS",
        "promotion_gate: PENDING",
        "next_phase_permission: PHASE_8_ENGINEERING_ONLY",
        "MIG-006: PENDING_PROMOTION",
        "MIG-007: PENDING_PROMOTION",
        "149 static",
        "22 Python",
        "276 Java",
        "60 frontend",
        "507 tests",
    ):
        assert marker in checkpoint

    for path in CONTRACT_ARTIFACTS:
        artifact = _text(path)
        assert A7 in artifact, path
        assert "PHASE_8_ENGINEERING_ONLY" in artifact, path


def test_phase8_statuses_remain_pre_entry_and_pre_release() -> None:
    for path in (CONTRACT_PACK, BASELINE_INVENTORY, REVIEW_CLOSURE):
        artifact = _text(path)
        for marker in (
            "P8.0: NOT_RUN",
            "engineering_execution: BLOCKED_PENDING_P8_0_ACCEPTANCE",
            "production_checkpoint: PENDING_EXTERNAL",
            "promotion_gate: PENDING",
            "MIG-006: PENDING_PROMOTION",
            "MIG-007: PENDING_PROMOTION",
            "MIG-008: PENDING_PROMOTION",
        ):
            assert marker in artifact, (path, marker)

    for path in (EXECUTION_PLAN, TEST_BATCHES, OWNER_BRIEFS):
        artifact = _text(path)
        assert "P8.0" in artifact and "NOT_RUN" in artifact, path
        assert "BLOCKED" in artifact, path
        assert "PENDING_EXTERNAL" in artifact, path
        for migration in ("006", "007", "008"):
            assert re.search(rf"MIG-{migration}\s*:\s*PENDING_PROMOTION", artifact), (
                path,
                migration,
            )


def test_phase8_entry_has_separate_candidate_evidence_and_acceptance_commits() -> None:
    for path in (
        EXECUTION_PLAN,
        TEST_BATCHES,
        OWNER_BRIEFS,
        CONTRACT_PACK,
        REVIEW_CLOSURE,
    ):
        entry = _contract_token_text(path)
        for token in (
            "A7",
            "C8",
            "BATCH_0",
            "E8",
            "A8",
            "SOLE_PARENT",
            "SEPARATE",
        ):
            assert token in entry, (path, token)
        assert re.search(r"C8.*SOLE_PARENT.*A7", entry), path
        assert "CLEAN" in entry and "DETACHED" in entry, path
        assert re.search(r"E8.*SOLE_PARENT.*C8", entry), path
        assert re.search(r"A8.*SOLE_PARENT.*E8", entry), path
        _assert_any(
            entry,
            "CONTRACT_ONLY",
            "EXACT_ALLOWLIST_C8",
            "EXACT_TWELVE_PATH_ALLOWLIST_C8",
        )
        assert "IMPLEMENTATION" in entry, path
        _assert_any(entry, "NO_IMPLEMENTATION", "IMPLEMENTATION_FORBIDDEN")
        assert "SELF" in entry and "PASS" in entry, path
        _assert_any(
            entry,
            "NO_SELF_PASS",
            "SELF_PASS_FORBIDDEN",
            "SELF_CLAIM",
            "CANNOT_SELF_PASS",
            "NO_IMPLEMENTATION_OR_SELF_PASS",
        )
        _assert_any(
            entry,
            "E8_CANNOT_RECORD_P8_0_PASS",
            "E8_NO_P8_0_PASS",
            "E8_DOES_NOT_OPEN_IMPLEMENTATION",
            "E8_CANNOT_RELEASE_IMPLEMENTATION",
            "NO_OWNER_RELEASE",
            "E8_MAY_RELEASE_IMPLEMENTATION_FALSE",
            "CANNOT_RELEASE_IMPLEMENTATION",
            "E8_RELEASE_DECISION_FORBIDDEN",
            "NO_RELEASE_DECISION",
        )
        _assert_any(
            entry,
            "ONLY_A8",
            "A8_ALONE",
            "A8_OPENS_IMPLEMENTATION",
        )


def test_e8_bundle_and_a8_checkpoint_contract_are_exact() -> None:
    batches = _yaml(TEST_BATCHES)
    topology = batches["gate"]["checkpoint_topology"]
    assert topology["E8_required_artifact_count"] == 12
    assert topology["E8_required_artifact_paths_relative_to_evidence_root"] == [
        ".gitattributes",
        "artifact-sha256.json",
        "candidate.txt",
        "phase8-entry-execution-manifest.json",
        "static-phase8-entry.xml",
        "source-tree-environment.json",
        "p0-review-disposition.json",
        "phase8-entry-decision.json",
        "provenance-manifest.json",
        "p/00-stdout.log",
        "p/01-stderr.log",
        "p/02-junit.xml",
    ]
    assert topology["E8_artifact_index"] == "artifact-sha256.json"
    assert topology["E8_artifact_index_exact_covered_file_count"] == 11
    assert topology["E8_artifact_index_covers_every_other_required_file"] is True
    assert topology["E8_missing_extra_or_nonregular_path"] == "REJECT"
    assert topology["E8_diff_must_equal_exact_required_artifact_set"] is True
    assert topology["E8_decision"] == "PASS_AWAITING_CHECKPOINT_A8"
    assert topology["E8_next_state"] == "PENDING_A8_CHECKPOINT"
    assert topology["A8_exact_allowed_paths"] == [
        "docs/runbooks/temporal-first/phase-8-p8.0-entry-checkpoint.md",
        "tests/static/test_phase8_p8_0_entry_checkpoint.py",
    ]
    assert topology["A8_is_only_implementation_release_authority"] is True

    batch_0 = batches["batches"]["batch_0_entry"]
    assert batch_0["runner"]["invocation_argv"] == [
        "D:/miniconda/python.exe",
        "scripts/run_phase8_entry_checkpoint.py",
        "--execute",
        "--candidate-sha",
        "{candidate_sha}",
        "--run-dir",
        "{absolute_fresh_run_dir}",
        "--environment-id",
        "{environment_id}",
    ]
    assert batch_0["runner"]["shell"] is False
    assert batch_0["retry_policy"]["execution_attempt_limit_per_candidate"] == 1
    assert batch_0["retry_policy"]["retry_allowed"] is False
    assert batch_0["retry_policy"]["same_sha_retry_allowed_only_for"] == []


def test_local_engineering_seals_cannot_be_relabelled_as_authentication() -> None:
    batches = _yaml(TEST_BATCHES)
    trust = batches["gate"]["local_engineering_trust_boundary"]
    assert trust == {
        "operator_threat_model": "NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR",
        "malicious_local_admin_resistance": "OUT_OF_SCOPE_FOR_P8_0",
        "sha256_self_seal_semantics": "BYTE_INTEGRITY_AND_DRIFT_DETECTION_ONLY",
        "self_seal_proves_source_or_execution_authenticity": False,
        "cryptographic_execution_attestation_present": False,
        "required_p0_process_attestation_lanes": [
            "authority",
            "data_migration",
            "security_privacy",
        ],
        "required_p0_topic_count": 13,
        "every_lane_self_approved": False,
        "C8_runner_or_E8_may_self_authorize": False,
        "only_A8_may_open_engineering": True,
        "local_evidence_reusable_as_production_attestation": False,
        "production_cryptographic_execution_and_operator_attestation": (
            "REQUIRED_EXTERNAL"
        ),
    }
    p0_contract = batches["batches"]["batch_0_entry"]["evidence_schema"][
        "p0_review_disposition_contract"
    ]
    assert p0_contract["fixed_lanes"] == [
        "authority",
        "data_migration",
        "security_privacy",
    ]
    assert p0_contract["exact_closed_topic_count"] == 13
    assert p0_contract["self_approved"] is False
    assert p0_contract["cryptographic_production_attestation"] is False
    assert p0_contract["production_reuse"] == "forbidden"

    for path in (EXECUTION_PLAN, CONTRACT_PACK, REVIEW_CLOSURE):
        text = _text(path)
        assert "NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR" in text
        assert "byte integrity" in text.lower()
        assert "execution" in text and "authenticity" in text
        assert "authority" in text
        assert "data_migration" in text
        assert "security_privacy" in text
        assert "self_approved: false" in text
        assert "13" in text
        assert "production" in text.lower() and "external" in text.lower()


def test_phase8_uses_one_plus_eleven_adaptive_roles_and_test_limits() -> None:
    batches = _yaml(TEST_BATCHES)
    briefs = _yaml(OWNER_BRIEFS)

    for contract in (batches, briefs):
        _assert_int_contract(contract, 1, "active_primary_agents", "max_active_primary")
        _assert_int_contract(
            contract,
            11,
            "active_delegated_agents",
            "max_active_delegated",
            "logical_delegated_roles",
        )
        _assert_int_contract(
            contract,
            5,
            "implementation_owner_count",
            "logical_implementation_owners",
            "implementation_owners",
        )
        _assert_int_contract(
            contract,
            3,
            "p0_review_lane_count",
            "logical_p0_review_lanes",
            "p0_review_lanes",
        )
        _assert_int_contract(
            contract,
            2,
            "verification_lane_count",
            "logical_verification_lanes",
            "verification_lanes",
        )
        _assert_int_contract(
            contract,
            1,
            "lookahead_lane_count",
            "logical_lookahead_lanes",
            "lookahead_lane",
        )
        _assert_int_contract(
            contract,
            2,
            "max_concurrent_light_processes",
            "max_concurrent_light_test_processes",
            "light_test_process_limit",
            "light_test_slots",
            "max_light_test_processes",
        )
        _assert_int_contract(
            contract,
            1,
            "maven_testcontainers_process_limit",
            "combined_maven_testcontainers_process_limit",
            "maven_testcontainers_slots",
            "maven_testcontainers_processes",
            "maven_testcontainers_lanes",
            "max_combined_maven_testcontainers_processes",
        )

    combined = _contract_token_text(TEST_BATCHES, OWNER_BRIEFS, CONTRACT_PACK)
    assert "DEPENDENCY_AWARE" in combined
    assert "ONE_OWNER_PER_PATH" in combined
    _assert_any(
        combined,
        "START_10_20_SECONDS_APART",
        "LAUNCH_SPACING_SECONDS_10_20",
        "START_SPACING_SECONDS_10_20",
        "START_10_TO_20_SECONDS_APART",
    )
    assert "AT_LEAST_50" in combined and "P0_REVIEW" in combined


def test_phase8_engineering_and_release_lanes_cannot_be_composed() -> None:
    gate = _contract_token_text(
        EXECUTION_PLAN, TEST_BATCHES, OWNER_BRIEFS, CONTRACT_PACK
    )

    assert "ENGINEERING_LANE" in gate
    assert "RELEASE_LANE" in gate
    assert "MIG_000_007_PASS" in gate
    assert "SAME_CANDIDATE" in gate
    assert "SAME_IMMUTABLE_DEPLOYMENT" in gate
    assert "MIXED" in gate and "FORBIDDEN" in gate
    assert "UNIFIED_PRODUCTION_CHECKPOINT" in gate
    assert "PENDING_EXTERNAL" in gate
    assert "99" in gate and "BASELINE" in gate
    _assert_any(gate, "ZERO_OPEN_P0", "NO_OPEN_P0", "OPEN_P0_COUNT_0")
    _assert_any(
        gate,
        "ENGINEERING_ACCEPTANCE_IS_NOT_PRODUCTION_PASS",
        "ENGINEERING_READINESS_IS_PRODUCTION_PASS_FALSE",
        "ENGINEERING_READINESS_IS_NOT_PRODUCTION_READINESS",
    )


def test_phase8_targets_1000_rooms_without_claiming_load_evidence() -> None:
    batches = _yaml(TEST_BATCHES)
    _assert_int_contract(
        batches, 1000, "active_rooms", "target_active_rooms", "room_target"
    )

    capacity = _contract_token_text(
        EXECUTION_PLAN, TEST_BATCHES, CONTRACT_PACK, BASELINE_INVENTORY
    )
    assert "1000" in capacity and "ROOM" in capacity
    assert "PENDING_EXTERNAL" in capacity
    _assert_any(
        capacity,
        "NO_1000_ROOM_PRODUCTION_EVIDENCE",
        "1000_ROOM_LOAD_NOT_RUN",
        "1000_ROOM_TARGET_IS_NOT_EVIDENCE",
        "NO_ACCEPTED_1_000_ROOM",
        "UNOBSERVED_EXTERNAL_RELEASE_TARGET",
    )


def test_active_reference_audit_is_complete_and_fails_closed() -> None:
    batches = _yaml(TEST_BATCHES)
    for path in (EXECUTION_PLAN, TEST_BATCHES, CONTRACT_PACK):
        contract = _contract_token_text(path)
        for token in (
            "OBJECT_STORE",
            "CODEC",
            "SCHEMA",
            "PROMPT",
            "ARTIFACT",
            "MANIFEST",
            "RETAINED_WINDOW",
            "FRONTEND",
            "API",
            "ENDPOINT",
            "AGENT_STREAM_V1",
            "TELEMETRY",
            "PAGINATION",
            "QUERY",
            "PERMISSION",
            "LAG",
            "PRODUCER",
            "SELECTOR",
            "DEPLOYMENT",
            "HIGH_WATERMARK",
        ):
            assert token in contract, (path, token)
        _assert_any(
            contract,
            "NO_NEW_REFERENCE",
            "CREATING_A_NEW_REFERENCE",
            "A_NEW_REFERENCE",
        )
        for outcome in ("UNKNOWN", "PARTIAL", "ERROR"):
            assert re.search(rf"{outcome}.*BLOCK_DELETE", contract), (path, outcome)
        assert "QUIESCENCE" in contract, path
        assert "MONOTONIC" in contract, path
        _assert_any(
            contract,
            "BETWEEN",
            "CONTINUOUS_NO_NEW_REFERENCE_HIGH_WATERMARK",
        )

    audit = batches["active_reference_audit"]
    assert set(audit["required_reference_classes"]) == {
        "TEMPORAL_WORKFLOW",
        "TEMPORAL_CHILD",
        "TEMPORAL_CONTINUE_AS_NEW",
        "TEMPORAL_SCHEDULE",
        "TEMPORAL_PENDING_WORK",
        "TEMPORAL_ROOM_EPOCH_BUILD_REACHABILITY",
        "WORKER_BUILD_ID",
        "GRAPH_THREAD",
        "GRAPH_VERSION",
        "GRAPH_CHECKPOINT",
        "ROOM_EPOCH",
        "LEGACY_V1_LOGICAL_RUN",
        "LEGACY_V1_ATTEMPT",
        "HOT_STREAM_READER",
        "DOMAIN_CASE_COMMAND",
        "DOMAIN_OPERATION",
        "DOMAIN_FINALIZER",
        "DEPLOYED_API_VERSION",
        "DEPLOYED_WORKER_VERSION",
        "DEPLOYED_GRAPH_VERSION",
        "DEPLOYED_COMPATIBILITY_READER_VERSION",
        "OUTBOX",
        "LEASE",
        "STREAM_CURSOR",
        "LEGACY_READER_VERSION",
        "MEMORY_FRAME_READER",
        "LEGACY_ENDPOINT_CALLER",
        "OBJECT_STORE_MANIFEST",
        "OBJECT_STORE_CODEC",
        "OBJECT_STORE_SCHEMA",
        "OBJECT_STORE_PROMPT",
        "OBJECT_STORE_ARTIFACT",
        "RETAINED_WINDOW_FRONTEND_LEGACY_ENDPOINT",
        "RETAINED_WINDOW_API_LEGACY_ENDPOINT",
        "AGENT_STREAM_V1_TELEMETRY",
    }
    assert audit["required_authority_joins"] == {
        "temporal_workflow_identity": [
            "workflow_type",
            "worker_build_id",
            "room_epoch",
        ],
        "nonterminal_epoch_version_pins": [
            "room_epoch",
            "workflow_type",
            "worker_build_id",
            "graph_version",
            "prompt_version",
            "schema_version",
            "artifact_version",
        ],
        "pending_formal_work": [
            "case_command",
            "outbox",
            "domain_operation",
            "finalizer",
        ],
        "legacy_agent_run_hot_reader_join": [
            "agent_run_v1_logical_run",
            "retained_window_frontend_reader",
            "retained_window_api_reader",
            "agent_stream_v1_telemetry",
        ],
    }

    decisions = _key_values(
        batches,
        {
            "unknown_decision",
            "partial_decision",
            "error_decision",
            "pagination_failure_decision",
            "query_failure_decision",
            "permission_failure_decision",
            "lag_or_staleness_decision",
        },
    )
    assert decisions
    assert all(value == "BLOCK_DELETE" for value in decisions)


def test_detector_is_observation_only_and_current_hearing_gaps_are_scheduled() -> None:
    for path in (EXECUTION_PLAN, TEST_BATCHES, CONTRACT_PACK):
        detector = _contract_token_text(path)
        for token in (
            "DETECTOR",
            "IMMUTABLE",
            "PROPOSAL",
            "AUDIT",
            "ENQUEUE",
            "FORMAL_RECONCILIATION",
        ):
            assert token in detector, (path, token)
        _assert_any(detector, "MUTATION", "MUTATE")

    baseline = _contract_token_text(BASELINE_INVENTORY)
    for token in (
        "DRAINEDOFF",
        "LEGACY",
        "V2_TEMPORAL",
        "V1_LEGACY",
    ):
        assert token in baseline
    _assert_any(baseline, "ABSENT_PROJECTION", "PROJECTION_IS_ABSENT")
    assert "TEMPORAL" in baseline and "OFF" in baseline
    _assert_any(
        baseline,
        "WOULD_BE_LEGACY_CANDIDATE",
        "LEGACY_CANDIDATE_ENUMERATION",
    )
    assert re.search(r"BEFORE.*OFF", baseline)


def test_scheduler_scope_is_three_legacy_executors_not_wholesale_off() -> None:
    expected_legacy = {
        "AgentRunRecoveryScheduler",
        "HearingFlowDeadlineScheduler",
        "HearingReviewHandoffRecoveryScheduler",
    }
    batches = _yaml(TEST_BATCHES)
    inventory = _key_values(
        batches,
        {
            "exact_legacy_schedulers",
            "legacy_executor_schedulers",
            "legacy_schedulers_in_scope",
            "retirement_candidates_exactly",
        },
    )
    assert inventory
    assert any(set(value) == expected_legacy for value in inventory)
    scheduler_contract = batches["scheduler_retirement"]
    assert set(scheduler_contract["retained_jobs_not_subject_to_wholesale_off"]) == {
        "TemporalCommandOutboxRelay",
        "ControlPlaneRecoverySchedulingConfiguration",
        "SSE_HEARTBEAT",
        "AgentRunHeartbeatMonitor",
    }
    assert scheduler_contract["wholesale_scheduler_off_allowed"] is False

    for path in (EXECUTION_PLAN, TEST_BATCHES, CONTRACT_PACK):
        scheduler = _contract_token_text(path)
        for name in expected_legacy:
            token = re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()
            _assert_any(scheduler, name.upper(), token)
        for retained_kind in ("RELAY", "RECOVERY", "HEARTBEAT"):
            assert retained_kind in scheduler, (path, retained_kind)
        assert "RETAIN" in scheduler, path

    scheduler_bundle = _contract_token_text(EXECUTION_PLAN, TEST_BATCHES, CONTRACT_PACK)
    assert "WHOLESALE_OFF" in scheduler_bundle


def test_security_topology_and_dry_run_boundaries_are_explicit() -> None:
    for path in (EXECUTION_PLAN, TEST_BATCHES, CONTRACT_PACK):
        security = _contract_token_text(path)
        for token in (
            "KMS",
            "VAULT",
            "IDENTIT",
            "RBAC",
            "MTLS",
            "PRIVATE",
            "IMMUTABLE",
            "OBJECT",
            "ACL",
            "AUDIT",
            "DRY_RUN",
            "NETWORK",
            "CLOUD",
            "DATABASE",
            "TEMPORAL",
            "FORBIDDEN",
        ):
            assert token in security, (path, token)
        _assert_any(security, "SECRET_ENV", "SECRET_BEARING_ENVIRONMENT")
        _assert_any(security, "VERSIONED", "VERSIONING")
        _assert_any(security, "NETWORKPOLICY", "NETWORK_POLICY")
        for token in (
            "BATCH_0",
            "FIXED",
            "ALLOWLIST",
            "GIT",
            "PYTHON",
            "SHELL_FALSE",
            "RECOVERY",
            "ROTATION",
            "SUBPROCESS",
        ):
            assert token in security, (path, token)
        _assert_any(
            security,
            "LOCAL_ARGV",
            "LOCAL_SUBPROCESS_EXECUTION",
            "FIXED_ALLOWLISTED_LOCAL_GIT",
            "FIXED_PREDECLARED_LOCAL_GIT",
        )

    batches = _yaml(TEST_BATCHES)
    for keys in (
        (
            "network_access_allowed",
            "dry_run_network_allowed",
            "network_access",
        ),
        (
            "cloud_api_access_allowed",
            "dry_run_cloud_access_allowed",
            "cloud_api_access",
        ),
        (
            "database_access_allowed",
            "dry_run_database_access_allowed",
            "database_access",
        ),
        (
            "temporal_access_allowed",
            "dry_run_temporal_access_allowed",
            "temporal_access",
        ),
        (
            "secret_environment_access_allowed",
            "dry_run_secret_env_access_allowed",
            "secret_environment_reads",
        ),
    ):
        _assert_forbidden_contract(batches, *keys)

    shell_values = _key_values(batches, {"shell", "shell_enabled"})
    assert shell_values and all(value is False for value in shell_values)
    assert batches["contract_tool_sandbox"]["local_subprocess_execution"] == (
        "ALLOWLISTED_FIXED_ARGV_GIT_AND_PYTHON_ONLY"
    )
    recovery = batches["recovery_dr_rotation_dry_run_sandbox"]
    for key in (
        "subprocess_execution",
        "network_access",
        "cloud_api_access",
        "database_access",
        "temporal_access",
        "secret_environment_reads",
    ):
        assert recovery[key].upper() == "FORBIDDEN"


def test_post_a8_engineering_batches_and_security_provider_are_frozen() -> None:
    batches = _yaml(TEST_BATCHES)
    briefs = _yaml(OWNER_BRIEFS)

    batch_1_groups = {
        group["id"]: group
        for group in batches["batches"]["batch_1_foundation"]["source_groups"]
    }
    assert batch_1_groups["phase8_wave_a_static"]["selectors"] == [
        "tests/static/test_phase8_active_reference_audit.py",
        "tests/static/test_phase8_v046_migration.py",
        "tests/static/test_phase8_production_topology.py",
        "tests/static/test_phase8_security_manifests.py",
        "tests/static/test_phase8_observability_assets.py",
        "tests/static/test_phase8_candidate_runner.py",
    ]
    batch_1_maven = batch_1_groups["phase8_wave_a_java"]
    assert batch_1_maven["cwd"] == "java-api-service"
    assert batch_1_maven["shell"] is False
    assert batch_1_maven["selectors"] == [
        "AgentRunV2MigrationIntegrationTest",
        "AgentRunStreamReplayIntegrationTest",
    ]
    assert batch_1_maven["argv"] == [
        r".\mvnw.cmd",
        "-DforkCount=1",
        "-Dtest=AgentRunV2MigrationIntegrationTest,AgentRunStreamReplayIntegrationTest",
        "test",
    ]

    batch_2_source_groups = batches["batches"]["batch_2_integration"][
        "source_groups"
    ]
    assert [group["id"] for group in batch_2_source_groups] == [
        "phase8_wave_b_static_and_models",
        "phase8_wave_b_java_unit",
        "phase8_wave_b_postgresql_integration",
    ]
    batch_2_groups = {group["id"]: group for group in batch_2_source_groups}

    resources = batches["resources"]
    assert resources["maven_testcontainers_lanes"] == 1
    assert resources["max_combined_maven_testcontainers_processes"] == 1

    batch_2_unit = batch_2_groups["phase8_wave_b_java_unit"]
    assert batch_2_unit["resource_class"] == "maven"
    assert batch_2_unit["max_processes"] == 1
    assert batch_2_unit["cwd"] == "java-api-service"
    assert batch_2_unit["shell"] is False
    assert batch_2_unit["selectors"] == [
        "AgentRunRecoverySchedulerTest",
        "AgentRunV2PropertiesTest",
        "HearingSchedulerModeTest",
        "JdbcHearingSchedulerDetectorTest",
        "StreamBackfillCoordinatorTest",
        "AgentRunStreamRetentionManifestTest",
        "RedisAgentRunStreamFailoverTest",
    ]
    assert batch_2_unit["argv"] == [
        r".\mvnw.cmd",
        "-DforkCount=1",
        "-Dtest=AgentRunRecoverySchedulerTest,AgentRunV2PropertiesTest,"
        "HearingSchedulerModeTest,JdbcHearingSchedulerDetectorTest,"
        "StreamBackfillCoordinatorTest,AgentRunStreamRetentionManifestTest,"
        "RedisAgentRunStreamFailoverTest",
        "test",
    ]
    unit_test_arg = next(
        arg for arg in batch_2_unit["argv"] if arg.startswith("-Dtest=")
    )
    assert "IntegrationTest" not in unit_test_arg

    batch_2_postgresql = batch_2_groups["phase8_wave_b_postgresql_integration"]
    assert batch_2_postgresql["resource_class"] == "maven_testcontainers"
    assert batch_2_postgresql["max_processes"] == 1
    assert batch_2_postgresql["disposable_postgresql_only"] is True
    assert batch_2_postgresql["cwd"] == "java-api-service"
    assert batch_2_postgresql["shell"] is False
    assert batch_2_postgresql["selectors"] == [
        "AgentRunStreamReplayIntegrationTest"
    ]
    assert batch_2_postgresql["argv"] == [
        r".\mvnw.cmd",
        "-DforkCount=1",
        "-Pintegration-test",
        "-Dit.test=AgentRunStreamReplayIntegrationTest",
        "verify",
    ]
    assert not any(
        arg.startswith("-Dtest=") for arg in batch_2_postgresql["argv"]
    )

    classifications = ["PRODUCT", "FIXTURE", "CONTRACT", "INFRA", "EXTERNAL_GATE"]
    assert batches["failure_classification"]["required_values"] == classifications
    assert briefs["test_policy"]["failure_classification"] == classifications
    assert briefs["test_policy"]["failure_classification_semantics"] == {
        "PRODUCT": "Product code, runtime, migration, deployment, or test behavior changed.",
        "CONTRACT": (
            "Replacement sole-parent C8 candidate from exact A7 plus a full fresh "
            "Batch 0 is mandatory."
        ),
    }
    assert (
        "java-api-service/src/test/java/com/example/dispute/agentstream/persistence/"
        "AgentRunV2MigrationIntegrationTest.java"
        in briefs["implementation_owners"]["I2"]["tasks"]["P8-I2-1"][
            "exact_owned_paths"
        ]
    )

    security = batches["security_and_asset_contract"]
    assert security["service_mesh"] == {
        "provider": "ISTIO",
        "security_api_version": "security.istio.io/v1",
        "engineering_mode": "RENDER_ONLY",
        "production_crd_installation_and_sidecar_readiness": "REQUIRED_EXTERNAL",
        "provider_neutral_configmap_counts_as_enforcement": False,
    }
    assert security["otel_identity_boundary"] == {
        "part_of_label": "after-sale-flow",
        "name_label": "otel-collector",
        "service_account": "after-sale-otel-collector",
        "otlp_grpc_port": 4317,
        "otlp_http_port": 4318,
        "I3_owns_identity_network_and_mtls": True,
        "I4_owns_otel_workload_manifest": True,
        "I3_kustomization_must_not_reference_I4_paths": True,
    }
    runtime_gaps = security["release_blocking_runtime_gaps"]
    assert runtime_gaps == {
        "temporal_cloud_tls_or_mtls_credential_adapter": "REQUIRED",
        "trusted_proxy_or_direct_mtls_to_asgi_identity_bridge": "REQUIRED",
        "reporting_read_replica_routing": "REQUIRED",
        "object_store_workload_identity_provider_chain": "REQUIRED",
        "langfuse_prompt_output_and_identity_redaction": "REQUIRED",
        "render_or_static_manifest_cannot_close_these_gaps": True,
    }

    execution = _text(EXECUTION_PLAN)
    for token in (
        "Istio",
        "security.istio.io/v1",
        "after-sale-otel-collector",
        "Langfuse redaction",
        "release-blocking",
    ):
        assert token in execution


def test_external_security_preflight_is_fail_closed_before_real_traffic() -> None:
    checkpoint = _yaml(TEST_BATCHES)["unified_production_checkpoint"]
    preflight = checkpoint["external_security_preflight"]
    assert preflight["status"] == "PENDING_EXTERNAL"
    assert preflight["execution_from_engineering_lane"] == "forbidden"
    assert preflight["separate_external_authorization_required"] is True
    assert preflight["must_complete_before_any_real_traffic"] is True
    assert preflight["binding"] == {
        "same_candidate_commit": True,
        "same_configuration_sha256": True,
        "same_environment_identity": True,
        "same_deployment_manifest_sha256": True,
        "same_checkpoint_attempt_lineage": True,
    }
    assert preflight["required_control_receipts"] == [
        "TEMPORAL_CLOUD_TLS_OR_MTLS_CREDENTIAL_ADAPTER_ACCEPTED",
        "TRUSTED_PROXY_OR_DIRECT_MTLS_ASGI_IDENTITY_BRIDGE_ACCEPTED",
        "REPORTING_READ_REPLICA_ROUTING_ACCEPTED",
        "OBJECT_STORE_WORKLOAD_IDENTITY_PROVIDER_CHAIN_ACCEPTED",
        "LANGFUSE_IDENTITY_PROMPT_OUTPUT_REDACTION_ACCEPTED",
        "ISTIO_SECURITY_IO_V1_CRD_READINESS_ACCEPTED",
        "ISTIO_DATAPLANE_INTERCEPTION_ACCEPTED",
        "ISTIO_STRICT_MTLS_ENFORCEMENT_ACCEPTED",
        "ISTIO_AUTHORIZATION_POLICY_ENFORCEMENT_ACCEPTED",
        "I3_I4_OTEL_NAMESPACE_LABEL_SERVICE_ACCOUNT_AND_PORT_BINDING_ACCEPTED",
    ]
    assert set(preflight["immutable_receipt_required_fields"]) == {
        "schema_version",
        "control_id",
        "candidate_sha",
        "configuration_sha256",
        "environment_identity",
        "deployment_manifest_sha256",
        "attempt_id",
        "operator_identity",
        "authorization_reference",
        "signer_identity",
        "signature_algorithm",
        "signing_key_id",
        "trust_root_id",
        "observed_at",
        "status",
        "evidence_sha256",
        "signed_payload_sha256",
        "signature",
        "receipt_sha256",
    }
    assert preflight["signed_payload_exact_fields"] == [
        "schema_version",
        "control_id",
        "status",
        "evidence_sha256",
        "candidate_sha",
        "configuration_sha256",
        "environment_identity",
        "deployment_manifest_sha256",
        "attempt_id",
        "operator_identity",
        "authorization_reference",
        "signer_identity",
        "signature_algorithm",
        "signing_key_id",
        "trust_root_id",
        "observed_at",
    ]
    assert preflight["receipt_acceptance"] == {
        "every_required_control_receipt_present": True,
        "every_receipt_status": "ACCEPTED",
        "every_receipt_matches_bound_context": True,
        "every_cryptographic_signature_verifies_against_independent_trust_root": True,
        "every_signed_payload_matches_exact_required_fields": True,
        "claimed_signer_identity_equals_verified_key_or_certificate_subject": True,
        "verified_signer_and_operator_authorized_for_control_environment_and_attempt": True,
        "signer_is_not_runner_generator_candidate_author_or_evidence_author": True,
        "signing_key_not_revoked_at_observed_at": True,
        "signing_credential_valid_and_unexpired_at_observed_at": True,
        "I3_I4_otel_binding_matches_exact_namespace_labels_service_account_and_ports": True,
        "receipt_or_evidence_secret_material": "forbidden",
        "authority_identity_or_authenticated_header_mutation_decision": (
            "BLOCK_PRODUCTION_CHECKPOINT_AND_PROMOTION"
        ),
        "missing_failed_partial_stale_or_mixed_receipt_decision": (
            "BLOCK_PRODUCTION_CHECKPOINT_AND_PROMOTION"
        ),
    }

    order = checkpoint["execution_order"]
    assert order.index(
        "PRODUCTION_EQUIVALENT_THREE_FAILURE_DOMAIN_DEPLOYMENT"
    ) < order.index("EXTERNAL_SECURITY_PREFLIGHT_BEFORE_REAL_TRAFFIC")
    assert order.index("EXTERNAL_SECURITY_PREFLIGHT_BEFORE_REAL_TRAFFIC") < order.index(
        "MULTI_ROLE_BROWSER_E2E_AND_CURRENT_BASELINES"
    )
    assert "EXTERNAL_SECURITY_PREFLIGHT" in checkpoint["currently_forbidden_steps"]

    execution = _text(EXECUTION_PLAN)
    contract_pack = _text(CONTRACT_PACK)
    assert execution.index(
        "2. Build Java, Python, and Vue plus replay suites"
    ) < execution.index(
        "3. Complete the separately authorized external security preflight"
    )
    assert execution.index(
        "3. Complete the separately authorized external security preflight"
    ) < execution.index(
        "4. Run USER, MERCHANT, PLATFORM_REVIEWER, ADMIN, and SYSTEM boundary E2E"
    )
    assert "render success cannot substitute for any" in execution
    assert "It does not prove CRD" in contract_pack
    for text in (execution, contract_pack):
        lowered = text.lower()
        for token in (
            "same-candidate",
            "same-configuration",
            "same-environment",
            "same-deployment",
            "same-attempt-lineage",
            "before any real traffic",
        ):
            assert token in lowered


def test_future_security_assets_have_one_owner_and_are_absent_from_c8() -> None:
    briefs = _yaml(OWNER_BRIEFS)
    owner_paths: dict[str, set[str]] = {}
    for owner_id, owner in briefs["implementation_owners"].items():
        paths = {
            path
            for task in owner["tasks"].values()
            for path in task["exact_owned_paths"]
        }
        owner_paths[owner_id] = paths

    matching_owners = [
        owner_id
        for owner_id, paths in owner_paths.items()
        if FUTURE_SECURITY_PATHS <= paths
    ]
    assert len(matching_owners) == 1
    for owner_id, paths in owner_paths.items():
        if owner_id != matching_owners[0]:
            assert FUTURE_SECURITY_PATHS.isdisjoint(paths)

    c8 = _c8_commit()
    for path in FUTURE_SECURITY_PATHS:
        assert _git("ls-tree", "-r", "--name-only", c8, "--", path) == ""


def test_v046_contract_preserves_identity_delivery_and_replay_invariants() -> None:
    for path in (EXECUTION_PLAN, TEST_BATCHES, OWNER_BRIEFS, CONTRACT_PACK):
        v046 = _contract_token_text(path)
        assert "V046" in v046, path
        assert "PRODUCTION" in v046 and "FORBIDDEN" in v046, path

    combined = _contract_token_text(
        EXECUTION_PLAN, TEST_BATCHES, OWNER_BRIEFS, CONTRACT_PACK
    )
    for token in (
        "GLOBAL",
        "IDENTITY",
        "ATOMIC",
        "HASH",
        "TARGET",
        "HIGHEST_CONTIGUOUS",
        "DELIVERY_HIGH_WATERMARK",
        "BACKFILL_CURSOR",
        "V1",
        "V2",
        "ACTOR",
        "AUDIENCE",
        "RESET",
        "TERMINAL",
        "RECONNECT",
        "COMPOSITE_CURSOR",
        "ROLLBACK",
        "STREAM_013",
        "24",
        "ARCHIVE_READBACK",
        "RETAINED_TERMINAL",
        "MANIFEST",
        "REDIS",
        "MIG_000_007_PASS",
    ):
        assert token in combined, token

    batches = _yaml(TEST_BATCHES)
    v046 = batches["migrations"]["V046__stream_partition_and_retention.sql"]
    assert v046["authority_scope"] == "DELIVERY_STORAGE_ONLY"
    assert v046["formal_business_completion_authority"] == (
        "JAVA_AND_DOMAIN_POSTGRESQL_TRANSACTIONAL_FINALIZER_ONLY"
    )
    for key in (
        "stream_terminal_may_authorize_formal_business_completion",
        "delivery_high_watermark_may_authorize_formal_business_completion",
        "archive_receipt_may_authorize_formal_business_completion",
    ):
        assert v046[key] is False
    assert v046["event_identity_scope"] == "GLOBAL_UNPARTITIONED"
    assert set(v046["transactional_delivery_invariants"]) == {
        "IMMUTABLE_EVENT_IDENTITY_AND_CANONICAL_HASH",
        "TARGET_ROW_AND_DELIVERY_HIGH_WATERMARK_COMMIT_ATOMICALLY",
        "DELIVERY_HIGH_WATERMARK_IS_HIGHEST_CONTIGUOUS",
        "DELIVERY_HIGH_WATERMARK_NEVER_REGRESSES",
        "BACKFILL_CURSOR_IS_SEPARATE_FROM_DELIVERY_HIGH_WATERMARK",
    }
    assert set(v046["compatibility_parity"]) == {
        "V1_AND_V2_ACTOR_ID",
        "V1_AND_V2_AUDIENCE",
        "V1_AND_V2_RESET",
        "V1_AND_V2_TERMINAL",
        "V1_AND_V2_RECONNECT",
        "V1_AND_V2_COMPOSITE_CURSOR",
        "ANY_PARITY_UNKNOWN_PARTIAL_OR_MISMATCH_FAILS_CLOSED",
    }
    assert v046["rollback"] == ("TARGET_AWARE_COMPATIBLE_READER_AND_WRITER_SELECTION")
    assert v046["retention_release_gate"] == {
        "stream_check": "STREAM-013",
        "terminal_required": True,
        "minimum_hot_retention_hours": 24,
        "archive_readback_required": True,
        "retained_terminal_required": True,
        "retained_manifest_required": True,
        "detach_or_drop": "RELEASE_ONLY_AFTER_ALL_CONDITIONS",
    }
    assert v046["redis_fanout_authority"] == "NON_FORMAL_CACHE"
    assert v046["redis_outage_behavior"] == ("FORMAL_STREAM_REPLAYS_FROM_DOMAIN_DB")


def test_mig008_requires_accepted_same_candidate_v046_release_receipts() -> None:
    checkpoint = _yaml(TEST_BATCHES)["unified_production_checkpoint"]
    assert checkpoint["entry_requires"] == {
        **{f"MIG-{number:03d}": "PASS" for number in range(8)},
        "all_99_behavior_baselines_current": True,
        "open_p0_count": 0,
        "same_immutable_release_candidate_and_deployment": True,
        "external_security_configuration_receipts_accepted": True,
    }
    assert checkpoint["before_all_entry_requires_pass"] == {
        "any_release_lane_execution": "forbidden",
        "real_v046_apply_or_switch": "forbidden",
        "production_traffic_load_chaos_pitr_dr_rotation_soak": "forbidden",
    }

    release = checkpoint["execution_order_after_separate_external_authorization"]
    assert release["status_now"] == "FORBIDDEN"
    assert release["entry_requires_must_already_pass"] is True
    assert release["separate_change_authorization_required"] is True
    assert release["same_candidate_and_immutable_deployment_required"] is True
    assert release["must_complete_before_MIG_008_success"] is True
    assert release["ordered_v046_steps"] == [
        "V046_EXPAND_AND_PRODUCTION_APPLY_RECEIPT",
        "V046_BOUNDED_BACKFILL_AND_HIGHEST_CONTIGUOUS_NONREGRESSING_HWM_RECEIPT",
        "V046_TRANSACTIONAL_CAPTURE_OR_DUAL_WRITE_RECEIPT",
        "V046_EXACT_COUNT_HASH_SEQUENCE_EVENT_IDENTITY_ACTOR_ID_AUDIENCE_VISIBILITY_RESET_TERMINAL_RECONNECT_COMPOSITE_CURSOR_PARITY_AND_ARCHIVE_READBACK_RECEIPT",
        "V046_COMPATIBLE_READER_SWITCH_AND_OBSERVATION_RECEIPT",
        "V046_WRITER_SWITCH_OBSERVATION_AND_TARGET_AWARE_ROLLBACK_PROOF_RECEIPT",
        "V046_OLD_TABLE_READ_ONLY_FULL_RELEASE_RETENTION_RECEIPT",
    ]
    assert set(release["immutable_receipt_required_fields"]) == {
        "schema_version",
        "candidate_sha",
        "deployment_manifest_sha256",
        "migration_version",
        "step_id",
        "attempt_id",
        "operator_identity",
        "authorization_reference",
        "started_at",
        "ended_at",
        "exit_status",
        "source_and_target_counts",
        "canonical_hashes",
        "sequence_continuity_and_event_identity",
        "actor_id_audience_visibility_reset_terminal_reconnect_composite_cursor_parity",
        "delivery_high_watermark",
        "archive_manifest_version_and_sha256",
        "terminal_event_identity_and_sha256",
        "immutable_agent_run_manifest_identity_and_sha256",
        "hot_retention_started_at",
        "hot_retention_ended_at",
        "hot_retention_duration_hours",
        "archive_object_version_and_sha256",
        "archive_readback_receipt_sha256",
        "old_table_read_only_observation_started_at",
        "old_table_read_only_observation_ended_at",
        "observation_window",
        "rollback_target_and_result",
        "receipt_sha256",
    }
    assert release["receipt_acceptance"] == {
        "every_ordered_step_receipt_present": True,
        "every_receipt_status": "ACCEPTED",
        "candidate_sha_must_match_checkpoint_candidate": True,
        "deployment_manifest_must_match_checkpoint_deployment": True,
        "mixed_attempt_or_candidate_receipts": "forbidden",
        "failed_partial_or_missing_receipt_decision": "BLOCK_MIG_008",
    }
    assert {
        "V046_PRODUCTION_APPLY_OR_SWITCH",
        "SEPARATELY_AUTHORIZED_V046_RELEASE_SEQUENCE",
        "EXTERNAL_SECURITY_PREFLIGHT",
    } <= set(checkpoint["currently_forbidden_steps"])
    assert checkpoint["success_output_requires_external_evidence"] == {
        "same_candidate_unified_checkpoint_pass": True,
        "all_external_security_preflight_receipts_accepted": True,
        "external_security_receipt_set_immutable_complete_and_same_bound_context": True,
        "all_ordered_v046_receipts_accepted": True,
        "v046_receipt_set_immutable_and_complete": True,
        "production_checkpoint": "PASS",
        "promotion_gate": "PASS",
        "MIG-008": "PASS",
    }


def test_all_production_and_destructive_actions_remain_forbidden() -> None:
    batches = _yaml(TEST_BATCHES)
    briefs = _yaml(OWNER_BRIEFS)
    aliases = (
        (
            "production_scheduler_off_activation_allowed",
            "scheduler_off_activation_allowed",
        ),
        (
            "legacy_code_or_schema_deletion_allowed",
            "legacy_deletion_allowed",
        ),
        (
            "production_v046_apply_or_switch_allowed",
            "production_v046_switch_allowed",
            "production_v046_apply_allowed",
        ),
        (
            "production_v046_apply_or_switch_allowed",
            "production_v046_reader_or_writer_switch_allowed",
        ),
        ("v047_authoring_allowed", "v047_creation_allowed"),
        ("v047_execution_allowed", "v047_apply_allowed"),
        ("production_traffic_allowed", "real_production_traffic_allowed"),
        ("real_production_load_allowed", "production_load_allowed"),
        ("production_chaos_allowed", "chaos_or_failover_allowed"),
        ("production_pitr_or_dr_allowed", "pitr_or_regional_dr_allowed"),
        ("production_rotation_allowed", "secret_or_key_rotation_allowed"),
        ("production_soak_allowed", "soak_allowed"),
        ("canary_allowed", "production_canary_allowed"),
        ("promotion_allowed", "production_promotion_allowed"),
    )

    for contract in (batches, briefs):
        for keys in aliases:
            _assert_forbidden_contract(contract, *keys)
        _assert_forbidden_contract(
            contract,
            "real_case_or_party_data",
            "real_case_or_party_data_allowed",
        )

    for path in CONTRACT_ARTIFACTS:
        authorization = _contract_token_text(path)
        assert "V047" in authorization, path
    authorization = _contract_token_text(*CONTRACT_ARTIFACTS)
    assert "MIG_000_008_PASS" in authorization
    v047 = batches["migrations"]["V047__remove_legacy_orchestration.sql"]
    assert set(v047["required_prior_migrations"]) == {
        f"MIG-{number:03d}_PASS" for number in range(9)
    }

    for path in CONTRACT_ARTIFACTS:
        prohibitions = _contract_token_text(path)
        for token in (
            "SCHEDULER_OFF",
            "V046",
            "V047",
            "PRODUCTION_TRAFFIC",
            "PRODUCTION_LOAD",
            "CHAOS",
            "PITR",
            "DR",
            "ROTATION",
            "SOAK",
            "CANARY",
            "PROMOTION",
            "REAL_CASE_OR_PARTY_DATA",
        ):
            assert token in prohibitions, (path, token)


def test_review_closure_has_no_pre_integrated_pass_claim() -> None:
    closure = _text(REVIEW_CLOSURE)

    assert "## P0 Contract Questions" in closure
    assert "AWAITING_INTEGRATED_REVIEW" in closure
    statuses = re.findall(r"status:\s*([A-Z0-9_]+)", closure)
    assert statuses
    assert set(statuses) <= {
        "BASELINE_ONLY",
        "NOT_RUN",
        "BLOCKED_PENDING_P8_0_ACCEPTANCE",
        "PENDING_EXTERNAL",
        "PENDING",
        "PENDING_PROMOTION",
        "AWAITING_INTEGRATED_REVIEW",
        "AWAITING_INTEGRATED_P0_REVIEW",
    }
