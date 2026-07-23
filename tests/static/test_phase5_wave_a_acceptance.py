from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from scripts import generate_phase5_wave_a_acceptance as generator
from scripts import run_phase5_wave_a_acceptance as runner


ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
BRIEFS = ROOT / "plans/phase-5-owner-briefs.yaml"
SCHEMA = (
    ROOT
    / "contracts/agent-platform/evidence/v2/phase5-wave-a-acceptance.schema.json"
)


def _fixed_blobs() -> dict[str, bytes]:
    return {
        name: runner._git_blob(
            runner.EXPECTED_EVIDENCE_COMMIT,
            f"{runner.EXPECTED_EVIDENCE_DIR}/{name}",
        )
        for name in runner.EVIDENCE_FILES
    }


def _authenticated_fixture(candidate: str = "a" * 40) -> dict:
    return {
        "tested_candidate_commit": runner.EXPECTED_TESTED_CANDIDATE,
        "accepted_base_commit": runner.EXPECTED_BASE_COMMIT,
        "evidence_commit": runner.EXPECTED_EVIDENCE_COMMIT,
        "acceptance_tooling_candidate_commit": candidate,
        "evidence_path": runner.EXPECTED_EVIDENCE_DIR,
        "evidence_file_count": 8,
        "evidence_tree_oid": "c" * 40,
        "artifact_index_sha256": "d" * 64,
        "artifact_index_blob_oid": "e" * 40,
        "artifacts": [
            {"path": name, "sha256": "b" * 64, "bytes": 1}
            for name in runner.EVIDENCE_FILES
        ],
        "totals": dict(runner.EXPECTED_TOTALS),
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }


def _acceptance_fixture(candidate: str = "a" * 40) -> tuple[dict, dict]:
    authenticated = _authenticated_fixture(candidate)
    document = {
        "schema_version": "phase5-wave-a-acceptance.v1",
        "phase": 5,
        "checkpoint": "P5-WAVE-A-INTEGRATED",
        "result": "PASS_AWAITING_STATE_TRANSITION_COMMIT",
        "tested_candidate_commit": runner.EXPECTED_TESTED_CANDIDATE,
        "accepted_base_commit": runner.EXPECTED_BASE_COMMIT,
        "evidence_commit": runner.EXPECTED_EVIDENCE_COMMIT,
        "evidence_tree_oid": authenticated["evidence_tree_oid"],
        "artifact_index_sha256": authenticated["artifact_index_sha256"],
        "artifact_index_blob_oid": authenticated["artifact_index_blob_oid"],
        "acceptance_tooling_candidate_commit": candidate,
        "evidence_path": runner.EXPECTED_EVIDENCE_DIR,
        "evidence_file_count": 8,
        "evidence_artifacts": authenticated["artifacts"],
        "totals": dict(runner.EXPECTED_TOTALS),
        "decision": {
            "P5-WAVE-A-INTEGRATED": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "wave_b": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "evidence_commit_alone_opens_wave_b": False,
            "state_transition_commit_required": True,
            "acceptance_commit_is_derived_from_git_history": True,
            "promotion_gate": "PENDING",
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
        },
        "runtime_restrictions": {
            key: False for key in runner.RUNTIME_RESTRICTION_KEYS
        },
    }
    return document, authenticated


def _transition_bindings() -> dict[str, str]:
    return {
        "tested_candidate_commit": runner.EXPECTED_TESTED_CANDIDATE,
        "evidence_commit": runner.EXPECTED_EVIDENCE_COMMIT,
        "acceptance_tooling_candidate_commit": "a" * 40,
        "acceptance_evidence_commit": "b" * 40,
    }


def _transition_preimages() -> dict[str, bytes]:
    return {
        path: (ROOT / path).read_bytes()
        for path in runner.STATE_TRANSITION_FILES
    }


def test_fixed_evidence_commit_is_direct_child_with_exact_eight_added_blobs() -> None:
    runner._assert_history_safe()
    assert runner._commit(
        runner.EXPECTED_TESTED_CANDIDATE, "tested candidate"
    ) == runner.EXPECTED_TESTED_CANDIDATE
    assert runner._commit(
        runner.EXPECTED_EVIDENCE_COMMIT, "evidence commit"
    ) == runner.EXPECTED_EVIDENCE_COMMIT

    runner._assert_exact_evidence_commit(
        runner.EXPECTED_TESTED_CANDIDATE, runner.EXPECTED_EVIDENCE_COMMIT
    )

    for name in runner.EVIDENCE_FILES:
        value, oid = runner._git_blob_record(
            runner.EXPECTED_EVIDENCE_COMMIT,
            f"{runner.EXPECTED_EVIDENCE_DIR}/{name}",
        )
        assert value.endswith(b"\n")
        assert b"\r" not in value
        assert len(oid) == 40


def test_fixed_evidence_blobs_recount_362_and_keep_all_gates_pending() -> None:
    blobs = _fixed_blobs()
    runner._validate_index(blobs)
    bindings = runner._validate_task_bindings(blobs["task-commit-bindings.json"])
    manifest = runner._validate_execution_manifest(
        blobs["phase5-wave-a-execution-manifest.json"], blobs
    )
    metrics = runner._validate_metrics(
        blobs["wave-a-metrics.json"], blobs, manifest, bindings
    )

    assert {key: metrics["totals"][key] for key in runner.EXPECTED_TOTALS} == {
        "tests": 362,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }
    assert metrics["checkpoint_decision"] == {
        "wave_a_barrier": "BLOCKED_UNTIL_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE",
        "wave_b_execution": "BLOCKED",
        "evidence_commit_opens_wave_b": False,
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }


def test_strict_json_rejects_duplicate_keys_and_nonfinite_numbers() -> None:
    with pytest.raises(runner.shared.EvidenceError, match="duplicate JSON key"):
        runner._strict_json(b'{"schema":"a","schema":"b"}\n', "fixture")
    with pytest.raises(runner.shared.EvidenceError, match="non-finite"):
        runner._strict_json(b'{"value":NaN}\n', "fixture")


def test_history_safe_rejects_replace_ref_namespace_entries(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    git_dir = tmp_path / "git"
    (git_dir / "info").mkdir(parents=True)
    monkeypatch.delenv("GIT_REPLACE_REF_BASE", raising=False)

    def fake_git_text(*arguments: str) -> str:
        if arguments == ("for-each-ref", "--format=%(refname)", "refs/replace"):
            return "refs/replace/" + "a" * 40
        if arguments in {
            ("rev-parse", "--git-common-dir"),
            ("rev-parse", "--git-dir"),
        }:
            return str(git_dir)
        raise AssertionError(arguments)

    monkeypatch.setattr(runner, "_git_text", fake_git_text)

    with pytest.raises(runner.shared.EvidenceError, match="replace refs are forbidden"):
        runner._assert_history_safe()


def test_history_safe_rejects_replace_ref_base_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GIT_REPLACE_REF_BASE", "refs/alternate-replace")

    with pytest.raises(runner.shared.EvidenceError, match="GIT_REPLACE_REF_BASE"):
        runner._assert_history_safe()


def test_history_safe_rejects_graft_file_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("GIT_GRAFT_FILE", "C:/tmp/grafts")

    with pytest.raises(runner.shared.EvidenceError, match="GIT_GRAFT_FILE"):
        runner._assert_history_safe()


def test_index_rejects_duplicate_rows_even_when_the_path_set_matches() -> None:
    blobs = _fixed_blobs()
    index = json.loads(blobs["artifact-sha256.json"])
    index["artifacts"][-1] = dict(index["artifacts"][0])
    blobs["artifact-sha256.json"] = (
        json.dumps(index, indent=2).encode("utf-8") + b"\n"
    )

    with pytest.raises(runner.shared.EvidenceError, match="file set drifted"):
        runner._validate_index(blobs)


def test_junit_recount_rejects_declared_total_drift() -> None:
    report = _fixed_blobs()["python-phase5-wave-a-junit.xml"]
    tampered = report.replace(b'tests="120"', b'tests="121"', 1)

    with pytest.raises(runner.shared.EvidenceError, match="declared totals drifted"):
        runner._junit_totals(tampered, "p5_wave_a_python")


def test_tooling_contract_stays_blocked_until_separate_state_transition() -> None:
    matrix = yaml.safe_load(MATRIX.read_text(encoding="utf-8"))
    briefs = yaml.safe_load(BRIEFS.read_text(encoding="utf-8"))
    acceptance = matrix["batches"]["P5-BATCH-1"]["acceptance"]
    transition = acceptance["state_transition"]

    assert matrix["waves"]["wave_a"]["status"] == "READY"
    assert matrix["waves"]["wave_b"]["status"] == "BLOCKED_ON_WAVE_A_INTEGRATION"
    assert matrix["waves"]["candidate_wave"]["status"] == (
        "BLOCKED_ON_WAVE_B_AND_ENGINEERING_EVIDENCE"
    )
    assert briefs["integration_barriers"]["P5-WAVE-A-INTEGRATED"]["status"] == (
        "BLOCKED"
    )
    assert acceptance["status"] == "READY_FOR_SEPARATE_ACCEPTANCE_RUN"
    assert acceptance["acceptance_evidence_result"] == (
        "PASS_AWAITING_STATE_TRANSITION_COMMIT"
    )
    assert acceptance["evidence_commit_alone_opens_wave_b"] is False
    assert acceptance["acceptance_evidence_commit_alone_opens_wave_b"] is False
    assert transition["required"] is True
    assert transition["target_state"] == {
        "wave_a": "INTEGRATED",
        "P5-WAVE-A-INTEGRATED": "OPEN",
        "wave_b": "READY",
        "P5-R2": "READY",
        "candidate_wave": "BLOCKED_ON_WAVE_B_AND_ENGINEERING_EVIDENCE",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }


def test_acceptance_schema_forbids_self_commit_and_early_open() -> None:
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    properties = schema["properties"]
    decision = properties["decision"]["properties"]
    runtime = properties["runtime_restrictions"]["properties"]

    assert schema["additionalProperties"] is False
    assert "acceptance_commit" not in properties
    assert properties["result"]["const"] == "PASS_AWAITING_STATE_TRANSITION_COMMIT"
    assert decision["P5-WAVE-A-INTEGRATED"]["const"] == (
        "BLOCKED_PENDING_STATE_TRANSITION_COMMIT"
    )
    assert decision["wave_b"]["const"] == "BLOCKED_PENDING_STATE_TRANSITION_COMMIT"
    assert decision["state_transition_commit_required"]["const"] is True
    assert set(properties["decision"]["required"]) == set(decision) == set(
        runner.ACCEPTANCE_DECISION_KEYS
    )
    assert set(properties["runtime_restrictions"]["required"]) == set(runtime) == set(
        runner.RUNTIME_RESTRICTION_KEYS
    )


@pytest.mark.parametrize(
    ("section", "mutation", "key"),
    [
        ("decision", "extra", "candidate_wave"),
        ("decision", "missing", "evidence_commit_alone_opens_wave_b"),
        ("runtime_restrictions", "extra", "runtime_mode"),
        ("runtime_restrictions", "missing", "formal_evidence_sink"),
    ],
)
def test_acceptance_document_rejects_extra_or_missing_nested_keys(
    section: str, mutation: str, key: str
) -> None:
    document, authenticated = _acceptance_fixture()
    if mutation == "extra":
        document[section][key] = False
    else:
        document[section].pop(key)
    payload = (json.dumps(document, indent=2) + "\n").encode("utf-8")

    with pytest.raises(runner.shared.EvidenceError, match="status|restrictions"):
        runner._validate_acceptance_document(payload, "a" * 40, authenticated)


def test_acceptance_document_rejects_numeric_false() -> None:
    document, authenticated = _acceptance_fixture()
    document["runtime_restrictions"]["formal_evidence_sink"] = 0

    with pytest.raises(runner.shared.EvidenceError, match="runtime restrictions"):
        runner._validate_acceptance_document(
            (json.dumps(document) + "\n").encode("utf-8"),
            "a" * 40,
            authenticated,
        )


def test_generator_writes_only_pending_three_file_bundle(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    candidate = "a" * 40
    run_root = tmp_path / ".codex-run" / "acceptance"
    run_root.mkdir(parents=True)
    manifest_path = run_root / runner.MANIFEST_NAME
    manifest_path.write_text("{}\n", encoding="utf-8")
    output = tmp_path / runner.EXPECTED_ACCEPTANCE_DIR
    authenticated = _authenticated_fixture(candidate)
    monkeypatch.setattr(generator, "ROOT", tmp_path)
    monkeypatch.setattr(generator.shared, "_assert_candidate", lambda value, *_: value)
    monkeypatch.setattr(generator.shared, "assert_candidate_run_directory", lambda *_: None)
    monkeypatch.setattr(
        generator.shared, "assert_clean_detached_candidate", lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(
        generator.runner,
        "load_pass_manifest",
        lambda *_args, **_kwargs: {"authenticated_handoff": authenticated},
    )
    monkeypatch.setattr(generator, "_assert_clean_filter_stable", lambda *_args: None)

    result = generator.generate_acceptance(
        candidate_commit=candidate,
        execution_manifest=manifest_path,
        output_dir=output,
    )

    assert result["result"] == "PASS_AWAITING_STATE_TRANSITION_COMMIT"
    assert result["decision"]["P5-WAVE-A-INTEGRATED"] == (
        "BLOCKED_PENDING_STATE_TRANSITION_COMMIT"
    )
    assert result["decision"]["wave_b"] == "BLOCKED_PENDING_STATE_TRANSITION_COMMIT"
    assert "acceptance_commit" not in result
    assert {path.name for path in output.iterdir()} == set(runner.ACCEPTANCE_FILES)


def test_canonical_state_transition_accepts_only_computed_postimages() -> None:
    preimages = _transition_preimages()
    bindings = _transition_bindings()
    postimages = runner.expected_state_transition_postimages(preimages, bindings)

    runner._assert_exact_state_transition_postimages(preimages, postimages, bindings)
    assert b"status: INTEGRATED" in postimages[runner.TEST_MATRIX_PATH]
    assert b"status: READY" in postimages[runner.TEST_MATRIX_PATH]
    assert b"status: OPEN" in postimages[runner.OWNER_BRIEFS_PATH]
    assert bindings["acceptance_evidence_commit"].encode("ascii") in postimages[
        runner.EXECUTION_PLAN_PATH
    ]


def test_tooling_commit_freezes_exact_plan_blob_oids(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_git_blob_record(commit: str, relative: str) -> tuple[bytes, str]:
        if relative in runner.EXPECTED_TOOLING_PLAN_BLOB_OIDS:
            return b"plan\n", runner.EXPECTED_TOOLING_PLAN_BLOB_OIDS[relative]
        return b"blob\n", "f" * 40

    monkeypatch.setattr(runner, "_git_blob_record", fake_git_blob_record)
    monkeypatch.setattr(runner, "_assert_exact_child_delta", lambda *args, **kwargs: None)

    runner._assert_tooling_commit("a" * 40, "b" * 40)


@pytest.mark.parametrize(
    ("path", "old", "new"),
    [
        (
            runner.TEST_MATRIX_PATH,
            b"runtime_modes_allowed: [LEGACY, DISABLED, SIGNED_SYNTHETIC_SHADOW]",
            b"runtime_modes_allowed: [PRODUCTION]",
        ),
        (
            runner.TEST_MATRIX_PATH,
            b"formal_evidence_graph_sink_allowed: false",
            b"formal_evidence_graph_sink_allowed: true",
        ),
        (
            runner.TEST_MATRIX_PATH,
            b"temporal_evidence_allocation_allowed: false",
            b"temporal_evidence_allocation_allowed: true",
        ),
        (
            runner.TEST_MATRIX_PATH,
            b"real_case_shadow_allowed: false",
            b"real_case_shadow_allowed: true",
        ),
        (
            runner.TEST_MATRIX_PATH,
            b"production_traffic_allowed: false",
            b"production_traffic_allowed: true",
        ),
        (
            runner.TEST_MATRIX_PATH,
            b"canary_allowed: false",
            b"canary_allowed: true",
        ),
        (
            runner.TEST_MATRIX_PATH,
            b"promotion_allowed: false",
            b"promotion_allowed: true",
        ),
        (
            runner.TEST_MATRIX_PATH,
            b"status: BLOCKED_ON_WAVE_B_AND_ENGINEERING_EVIDENCE",
            b"status: READY",
        ),
        (
            runner.OWNER_BRIEFS_PATH,
            b"    owner: R\n    prerequisites:",
            b"    owner: E\n    prerequisites:",
        ),
    ],
    ids=[
        "production-runtime",
        "formal-sink",
        "temporal-allocation",
        "real-shadow",
        "production-traffic",
        "canary",
        "promotion",
        "candidate-wave",
        "barrier-owner",
    ],
)
def test_state_transition_rejects_every_forbidden_semantic_mutation(
    path: str, old: bytes, new: bytes
) -> None:
    preimages = _transition_preimages()
    bindings = _transition_bindings()
    postimages = runner.expected_state_transition_postimages(preimages, bindings)
    assert postimages[path].count(old) >= 1
    postimages[path] = postimages[path].replace(old, new, 1)

    with pytest.raises(runner.shared.EvidenceError, match="unauthorized mutation"):
        runner._assert_exact_state_transition_postimages(
            preimages, postimages, bindings
        )


def test_state_transition_verifier_requires_direct_exact_plan_only_commit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    parent = "a" * 40
    child = "b" * 40
    monkeypatch.setattr(
        runner,
        "_raw_parent_commits",
        lambda commit: ["c" * 40] if commit == child else [],
    )

    with pytest.raises(runner.shared.EvidenceError, match="direct raw single-parent"):
        runner._assert_exact_child_delta(
            parent,
            child,
            runner.STATE_TRANSITION_FILES,
            "M",
            "Wave A state transition",
        )


def test_verify_state_transition_requires_expected_reviewed_tooling_sha(
    capsys: pytest.CaptureFixture[str],
) -> None:
    result = runner.main(
        ["--candidate-commit", "a" * 40, "--verify-state-transition"]
    )

    captured = capsys.readouterr()
    assert result == 2
    assert "--expected-tooling-commit is required" in captured.err
