from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path

import pytest

from scripts import generate_phase7_candidate_evidence as generator
from scripts import run_phase7_candidate_checkpoint as runner


CANDIDATE = "c" * 40
EVIDENCE = "d" * 40
RELEASE = "phase-7-candidate-test"


def _junit(candidate: str, command_id: str, *, skipped: int = 0) -> bytes:
    skip = "<skipped />" if skipped else ""
    report = runner.SOURCE_REPORTS[command_id]
    report_stem = Path(report).stem
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<testsuites name="{report_stem}" tests="1" failures="0" errors="0" '
        f'skipped="{skipped}" time="0.1" '
        f'candidate_commit="{candidate}" source_command_id="{command_id}">\n'
        f'  <testsuite name="{command_id}" tests="1" failures="0" errors="0" '
        f'skipped="{skipped}" time="0.1" source_report="{report}">\n'
        f'    <testcase classname="{command_id}" name="passes" time="0.1">'
        f"{skip}</testcase>\n"
        "  </testsuite>\n"
        "</testsuites>\n"
    ).encode("utf-8")


def _p0(candidate: str = CANDIDATE) -> dict[str, object]:
    return {
        "candidate_commit": candidate,
        "closed_finding_ids": ["P0-HTTP-AUTHORITY-001"],
        "open_p0_count": 0,
        "review_scope": "CONSOLIDATED_POST_INTEGRATION_P0_ONLY",
        "reviewed_topics": list(generator.P0_REVIEW_TOPICS),
        "schema_version": generator.P0_REVIEW_SCHEMA,
        "status": "ALL_P0_CLOSED",
    }


def _green_run(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Path, dict[str, object], dict[str, object]]:
    run_root = tmp_path / "run"
    (run_root / "r").mkdir(parents=True)
    contracts: dict[str, dict[str, object]] = {}
    for command_id in runner.COMMAND_ORDER:
        contracts[command_id] = {
            "command": f"tool-{command_id} --junit={{raw_report}}",
            "cwd": ".",
            "expected_report_count": 1,
            "minimum_tests": 1,
            "report": runner.SOURCE_REPORTS[command_id],
            "report_kind": (
                "SUREFIRE_GLOB"
                if command_id == "java_phase7_candidate"
                else "PYTEST_JUNIT"
            ),
            "resource_class": (
                "heavy" if command_id == "java_phase7_candidate" else "light"
            ),
            "selected_test_file_count": 1,
        }
        if command_id == "java_phase7_candidate":
            contracts[command_id]["raw_report_glob"] = (
                "target/surefire-reports/TEST-*-{report_suffix}.xml"
            )
    contract_sha = "8" * 64

    def fake_contracts(candidate: str | None = None) -> dict[str, dict[str, object]]:
        return contracts

    def fake_format(
        command_id: str,
        contract: dict[str, object],
        raw_path: Path,
        report_suffix: str,
        cwd: Path,
    ) -> list[str]:
        return [command_id, str(raw_path.resolve()), report_suffix]

    monkeypatch.setattr(runner, "_assert_candidate", lambda value, *_args: value)
    monkeypatch.setattr(runner, "source_contracts", fake_contracts)
    monkeypatch.setattr(runner, "_source_contract_sha256", lambda *_: contract_sha)
    monkeypatch.setattr(runner, "_format_command", fake_format)
    monkeypatch.setattr(generator, "_validate_source_tree_environment", lambda *_args, **_kwargs: None)

    source_tree: dict[str, object] = {
        "base_commit": runner.PHASE7_ENTRY_EVIDENCE,
        "candidate_commit": CANDIDATE,
        "candidate_tree": "1" * 40,
        "changed_paths": [{"path": runner.V045_PATH, "status": "A"}],
        "prior_migrations_unchanged": True,
        "v045": {"path": runner.V045_PATH, "status": "ADDED_ONLY"},
        "worker_selector_formal_effect_authority_unchanged": True,
    }
    source_tree["snapshot_sha256"] = runner._json_sha256(source_tree)
    environment: dict[str, object] = {
        "candidate_commit": CANDIDATE,
        "dependency_manifests": [
            {
                "byte_source": "CANDIDATE_GIT_BLOB",
                "path": "frontend/package.json",
                "sha256": "2" * 64,
            }
        ],
        "environment_id": "focused-test",
        "runner": {
            "byte_source": "CANDIDATE_GIT_BLOB",
            "path": runner.RUNNER_PATH,
            "sha256": "3" * 64,
        },
        "source_contract_sha256": contract_sha,
    }
    environment["snapshot_sha256"] = runner._json_sha256(environment)
    run_token = hashlib.sha256(str(run_root.resolve()).encode("utf-8")).hexdigest()[:6]
    records: list[dict[str, object]] = []
    for command_id in runner.COMMAND_ORDER:
        alias = runner.SOURCE_ALIASES[command_id]
        attempt = run_root / "a" / f"{alias}-01"
        attempt.mkdir(parents=True)
        raw_path = attempt / "junit.xml"
        retained_path = raw_path
        if command_id == "java_phase7_candidate":
            retained_path = attempt / "raw" / "j-001.xml"
            retained_path.parent.mkdir()
        payload = _junit(CANDIDATE, command_id)
        retained_path.write_bytes(payload)
        (attempt / "stdout.log").write_bytes(f"{command_id} stdout\n".encode())
        (attempt / "stderr.log").write_bytes(b"")
        report_name = runner.SOURCE_REPORTS[command_id]
        (run_root / "r" / report_name).write_bytes(payload)
        suffix = f"p7c-{CANDIDATE[:10]}-{run_token}-{alias}01"
        argv = fake_format(command_id, contracts[command_id], raw_path, suffix, generator.ROOT)
        rendered = runner.render_command_argv(argv)
        records.append(
            {
                "accepted": True,
                "candidate_commit": CANDIDATE,
                "command_contract_blob_sha256": "3" * 64,
                "cwd": ".",
                "duration_seconds": 0.1,
                "environment_sha256": environment["snapshot_sha256"],
                "errors": 0,
                "executed_argv": argv,
                "executed_argv_sha256": runner._json_sha256(argv),
                "executed_command": rendered,
                "executed_command_sha256": hashlib.sha256(rendered.encode()).hexdigest(),
                "exit_code": 0,
                "expected_report_count": 1,
                "failure_classification": "NONE",
                "failures": 0,
                "finished_at": "2026-07-24T12:00:01+00:00",
                "frozen_command": contracts[command_id]["command"],
                "frozen_command_sha256": hashlib.sha256(
                    str(contracts[command_id]["command"]).encode()
                ).hexdigest(),
                "id": command_id,
                "minimum_tests": 1,
                "raw_report_count": 1,
                "raw_reports": [
                    {
                        "path": retained_path.relative_to(run_root).as_posix(),
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    }
                ],
                "report": report_name,
                "report_path": f"r/{report_name}",
                "report_sha256": hashlib.sha256(payload).hexdigest(),
                "report_suffix": suffix,
                "resource_class": contracts[command_id]["resource_class"],
                "selected_test_file_count": 1,
                "skipped": 0,
                "started_at": "2026-07-24T12:00:00+00:00",
                "stderr_path": f"a/{alias}-01/stderr.log",
                "stderr_sha256": hashlib.sha256(b"").hexdigest(),
                "stdout_path": f"a/{alias}-01/stdout.log",
                "stdout_sha256": hashlib.sha256(
                    f"{command_id} stdout\n".encode()
                ).hexdigest(),
                "tests": 1,
                "time": 0.1,
            }
        )
    manifest: dict[str, object] = {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "accepted_phase_7_candidate_C7": runner.PHASE7_ENTRY_CANDIDATE,
        "accepted_phase_7_evidence_E7": runner.PHASE7_ENTRY_EVIDENCE,
        "attempt_id": run_root.name,
        "batch": "P7-BATCH-3",
        "candidate_commit": CANDIDATE,
        "commands": records,
        "concurrency": {
            "observed_maximum_source_processes": 1,
            "policy_maximum_heavy_processes": 2,
            "policy_maximum_light_processes": 2,
            "runner_execution": "sequential",
        },
        "decision_ceiling": "PHASE_7_ENGINEERING_CHECKPOINT_ONLY",
        "environment": environment,
        "next_phase_permission": "PENDING_SEPARATE_EVIDENCE",
        "pending_failure": None,
        "phase": 7,
        "quarantined_attempts": [],
        "quarantined_attempts_reused": False,
        "run_root": str(run_root.resolve()),
        "schema_version": runner.SCHEMA_VERSION,
        "source_contract_sha256": contract_sha,
        "source_tree": source_tree,
        "status": runner.GREEN_STATUS,
        "verification_finished_at": "2026-07-24T12:01:00+00:00",
        "verification_started_at": "2026-07-24T12:00:00+00:00",
    }
    runner.seal_execution_manifest(manifest)
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_json(manifest_path, manifest)
    return manifest_path, manifest, _p0()


def test_assembles_canonical_candidate_bound_direct_child_content(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path, manifest, review = _green_run(tmp_path, monkeypatch)
    output = tmp_path / "evidence"
    decision = generator.assemble_candidate_evidence(
        manifest=manifest,
        execution_manifest_path=manifest_path,
        p0_review=review,
        output_dir=output,
        release_id=RELEASE,
        candidate_commit=CANDIDATE,
    )

    assert decision["decision_ceiling"] == "PHASE_7_ENGINEERING_CHECKPOINT"
    assert decision["next_phase_permission_after_commit"] == "PHASE_8_ENGINEERING_ONLY"
    assert decision["MIG-006"] == decision["MIG-007"] == "PENDING_PROMOTION"
    assert not any(decision["runtime_restrictions"].values())
    assert decision["totals"]["tests"] == 4
    assert decision["totals"]["skipped"] == 0
    assert (output / generator.ATTRIBUTES_NAME).read_bytes() == generator.ATTRIBUTES_BYTES
    for name in (
        generator.HASH_INDEX_NAME,
        generator.PROVENANCE_MANIFEST_NAME,
        generator.SOURCE_ENVIRONMENT_NAME,
        generator.P0_REVIEW_NAME,
        generator.DECISION_NAME,
        runner.MANIFEST_NAME,
    ):
        payload = (output / name).read_bytes()
        assert b"\r" not in payload
        assert payload == generator._canonical_json_bytes(json.loads(payload))
    index = json.loads((output / generator.HASH_INDEX_NAME).read_bytes())
    assert [item["path"] for item in index["artifacts"]] == generator._indexed_names(manifest)
    assert all(len(item["path"]) <= len(generator.PORTABLE_MAX_ARCHIVE_RELATIVE) for item in index["artifacts"] if item["path"].startswith("p/"))
    java = next(item for item in manifest["commands"] if item["id"] == "java_phase7_candidate")
    assert set(java["raw_reports"][0]) == {"path", "sha256"}
    assert java["raw_reports"][0]["path"].endswith("/raw/j-001.xml")


def test_archived_manifest_and_source_environment_must_match_exactly(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path, manifest, review = _green_run(tmp_path, monkeypatch)
    output = tmp_path / "evidence"
    generator.assemble_candidate_evidence(
        manifest=manifest,
        execution_manifest_path=manifest_path,
        p0_review=review,
        output_dir=output,
        release_id=RELEASE,
        candidate_commit=CANDIDATE,
    )
    names = {generator.HASH_INDEX_NAME, *generator._indexed_names(manifest)}
    blobs = {
        name: (output / name).read_bytes()
        for name in names
    }
    source_environment = json.loads(blobs[generator.SOURCE_ENVIRONMENT_NAME])
    source_environment["environment"]["environment_id"] = "forged"
    blobs[generator.SOURCE_ENVIRONMENT_NAME] = generator._canonical_json_bytes(
        source_environment
    )
    with pytest.raises(runner.EvidenceError, match="differs from canonical document"):
        generator._validate_bundle_documents(
            candidate=CANDIDATE,
            release_id=RELEASE,
            manifest=manifest,
            blobs=blobs,
            candidate_blob_reader=lambda path: runner._git_bytes(CANDIDATE, path),
        )


@pytest.mark.parametrize(
    ("mutation", "value"),
    (
        ("candidate_commit", "f" * 40),
        ("status", "P0_REVIEW_INCOMPLETE"),
        ("open_p0_count", 1),
        ("closed_finding_ids", ["bad-id"]),
    ),
)
def test_p0_review_input_is_external_exact_sha_and_fail_closed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
    value: object,
) -> None:
    monkeypatch.setattr(runner, "_assert_candidate", lambda candidate, *_: candidate)
    review = _p0()
    review[mutation] = value
    path = tmp_path / "review.json"
    path.write_text(json.dumps(review), encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="ALL_P0_CLOSED"):
        generator.load_p0_review_disposition(path, CANDIDATE)


def test_p0_review_requires_explicit_external_absolute_file(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    review_path = tmp_path / "review.json"
    review_path.write_bytes(generator._canonical_json_bytes(_p0()))
    monkeypatch.setattr(runner, "_assert_candidate", lambda candidate, *_: candidate)
    with pytest.raises(runner.EvidenceError, match="explicit absolute"):
        generator.load_p0_review_disposition(Path("review.json"), CANDIDATE)

    with pytest.raises(runner.EvidenceError, match="external"):
        generator._assert_external_p0_review_path(
            CANDIDATE, review_path, forbidden_roots=(tmp_path,)
        )


def test_p0_review_rejects_candidate_tracked_self_claim(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = generator.ROOT / "scripts" / "run_phase7_candidate_checkpoint.py"
    monkeypatch.setattr(generator, "_candidate_path_tracked", lambda *_: True)
    with pytest.raises(runner.EvidenceError, match="tracked"):
        generator._assert_external_p0_review_path(
            CANDIDATE, path, forbidden_roots=()
        )


def test_p0_review_rejects_symlink_and_snapshot_change(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    target = tmp_path / "target.json"
    target.write_bytes(generator._canonical_json_bytes(_p0()))
    link = tmp_path / "review.json"
    try:
        link.symlink_to(target)
    except (OSError, NotImplementedError):
        pytest.skip("symlink creation is unavailable")
    with pytest.raises(runner.EvidenceError, match="link"):
        generator._assert_external_p0_review_path(CANDIDATE, link, forbidden_roots=())

    monkeypatch.setattr(generator, "_candidate_path_tracked", lambda *_: False)
    snapshot = generator._snapshot_p0_review_disposition(
        CANDIDATE, target, forbidden_roots=()
    )
    target.write_bytes(target.read_bytes() + b"tamper")
    with pytest.raises(runner.EvidenceError, match="changed"):
        generator._assert_p0_snapshot(snapshot)


def test_p0_review_rejects_hard_link_to_forbidden_content(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    forbidden_root = tmp_path / "candidate"
    forbidden_root.mkdir()
    tracked_copy = forbidden_root / "ignored-review.json"
    tracked_copy.write_bytes(generator._canonical_json_bytes(_p0()))
    external_link = tmp_path / "external-review.json"
    try:
        os.link(tracked_copy, external_link)
    except OSError as exception:
        pytest.skip(f"hard-link creation is unavailable: {exception}")

    monkeypatch.setattr(generator, "_candidate_path_tracked", lambda *_: False)
    with pytest.raises(runner.EvidenceError, match="exactly one filesystem link"):
        generator._assert_external_p0_review_path(
            CANDIDATE, external_link, forbidden_roots=(forbidden_root,)
        )


def test_p0_review_rejects_an_external_alias_with_forbidden_physical_ancestor(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    forbidden_root = tmp_path / "candidate"
    forbidden_root.mkdir()
    external = tmp_path / "outside" / "review.json"
    external.parent.mkdir()
    external.write_bytes(generator._canonical_json_bytes(_p0()))
    calls: list[tuple[Path, Path]] = []

    def same_object_descendant(path: Path, root: Path, _context: str) -> bool:
        calls.append((path, root))
        return root == forbidden_root

    monkeypatch.setattr(generator, "_candidate_path_tracked", lambda *_: False)
    monkeypatch.setattr(
        generator, "_same_object_descendant", same_object_descendant
    )
    with pytest.raises(runner.EvidenceError, match="external"):
        generator._assert_external_p0_review_path(
            CANDIDATE, external, forbidden_roots=(forbidden_root,)
        )
    assert calls == [(external.absolute(), forbidden_root.absolute())]


def test_committed_evidence_rejects_symlink_mode_even_when_blob_bytes_match(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = "test-reports/temporal-first/phase-7-candidate-test/phase-7-candidate/artifact-sha256.json"
    monkeypatch.setattr(
        generator,
        "_git_bytes",
        lambda *_: f"120000 blob {'a' * 40}\t{path}\0".encode("utf-8"),
    )

    with pytest.raises(runner.EvidenceError, match="regular blob"):
        generator._assert_committed_regular_blob_modes(EVIDENCE, {path})


def test_committed_evidence_accepts_regular_blob_modes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    paths = {
        "test-reports/temporal-first/phase-7-candidate-test/phase-7-candidate/a.json",
        "test-reports/temporal-first/phase-7-candidate-test/phase-7-candidate/b.xml",
    }
    entries = b"".join(
        f"100644 blob {'a' * 40}\t{path}\0".encode("utf-8")
        for path in sorted(paths)
    )
    monkeypatch.setattr(generator, "_git_bytes", lambda *_: entries)

    generator._assert_committed_regular_blob_modes(EVIDENCE, paths)


def test_p0_review_parse_uses_one_no_follow_snapshot_during_replace_restore_race(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    accepted = _p0()
    substituted = _p0()
    substituted["closed_finding_ids"] = ["P0-SUBSTITUTED-001"]
    path = tmp_path / "review.json"
    replacement = tmp_path / "replacement.json"
    held = tmp_path / "held-original.json"
    accepted_bytes = generator._canonical_json_bytes(accepted)
    path.write_bytes(accepted_bytes)
    replacement.write_bytes(generator._canonical_json_bytes(substituted))
    monkeypatch.setattr(generator, "_candidate_path_tracked", lambda *_: False)
    real_loads = generator.json.loads

    def replace_and_restore_while_parsing(payload: object) -> object:
        path.replace(held)
        replacement.replace(path)
        parsed = real_loads(payload)
        path.replace(replacement)
        held.replace(path)
        return parsed

    monkeypatch.setattr(generator.json, "loads", replace_and_restore_while_parsing)
    snapshot = generator._snapshot_p0_review_disposition(
        CANDIDATE, path, forbidden_roots=()
    )

    assert snapshot.document == accepted
    assert snapshot.payload == accepted_bytes
    assert snapshot.sha256 == hashlib.sha256(accepted_bytes).hexdigest()
    generator._assert_p0_snapshot(snapshot)


@pytest.mark.parametrize("release_id", ("Bad Release", "x", "../escape"))
def test_programmatic_entrypoints_validate_release_id(
    release_id: str, tmp_path: Path
) -> None:
    with pytest.raises(runner.EvidenceError, match="release ID"):
        generator.assemble_candidate_evidence(
            manifest={},
            execution_manifest_path=tmp_path / runner.MANIFEST_NAME,
            p0_review={},
            output_dir=tmp_path / "bundle",
            release_id=release_id,
            candidate_commit=CANDIDATE,
        )
    with pytest.raises(runner.EvidenceError, match="release ID"):
        generator.generate_candidate_evidence(
            release_id=release_id,
            candidate_commit=CANDIDATE,
            execution_manifest_path=tmp_path / runner.MANIFEST_NAME,
            p0_review_disposition_path=tmp_path / "review.json",
            output_dir=tmp_path / "bundle",
        )
    with pytest.raises(runner.EvidenceError, match="release ID"):
        generator.verify_evidence_commit(
            evidence_commit=EVIDENCE,
            candidate_commit=CANDIDATE,
            release_id=release_id,
        )


def test_generation_allows_only_the_explicit_p0_input_as_untracked(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path = tmp_path / "run" / runner.MANIFEST_NAME
    review_path = tmp_path / "separate-p0.json"
    output = tmp_path / "bundle"
    observed: list[tuple[Path, ...]] = []
    review_path.write_bytes(generator._canonical_json_bytes(_p0()))

    monkeypatch.setattr(runner, "_assert_candidate", lambda value, *_args: value)
    monkeypatch.setattr(generator, "load_green_manifest", lambda *_args: {})
    monkeypatch.setattr(
        generator, "load_p0_review_disposition", lambda *_args: _p0()
    )
    monkeypatch.setattr(
        generator,
        "_assert_clean_detached_candidate",
        lambda _candidate, *, allowed_untracked_roots: observed.append(
            tuple(allowed_untracked_roots)
        ),
    )

    def assemble(**arguments: object) -> dict[str, object]:
        Path(arguments["output_dir"]).mkdir()
        return {"result": "PASS"}

    monkeypatch.setattr(generator, "assemble_candidate_evidence", assemble)
    monkeypatch.setattr(generator, "_validate_bundle", lambda **_kwargs: {})

    generator.generate_candidate_evidence(
        release_id=RELEASE,
        candidate_commit=CANDIDATE,
        execution_manifest_path=manifest_path,
        p0_review_disposition_path=review_path,
        output_dir=output,
    )

    staging = output.with_name(f".{output.name}.assembling").resolve()
    expected = (manifest_path.parent.resolve(), staging)
    assert observed == [expected, expected]


def test_provenance_rejects_traversal_collision_and_byte_tamper(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path, manifest, review = _green_run(tmp_path, monkeypatch)
    bad = json.loads(json.dumps(manifest))
    bad["commands"][0]["stdout_path"] = "a/s-01/../escape.log"
    with pytest.raises(runner.EvidenceError, match="safe compact"):
        generator._provenance_specs(bad)

    bad = json.loads(json.dumps(manifest))
    bad["commands"][1]["stdout_path"] = bad["commands"][0]["stdout_path"]
    with pytest.raises(runner.EvidenceError, match="duplicated"):
        generator._provenance_specs(bad)

    output = tmp_path / "evidence"
    generator.assemble_candidate_evidence(
        manifest=manifest,
        execution_manifest_path=manifest_path,
        p0_review=review,
        output_dir=output,
        release_id=RELEASE,
        candidate_commit=CANDIDATE,
    )
    provenance = json.loads((output / generator.PROVENANCE_MANIFEST_NAME).read_bytes())
    archive = output / provenance["artifacts"][0]["archive_path"]
    archive.write_bytes(b"tampered")
    with pytest.raises(runner.EvidenceError, match="byte identity|artifact"):
        generator._validate_bundle(
            output_dir=output,
            candidate=CANDIDATE,
            release_id=RELEASE,
            manifest=manifest,
        )


def test_rejects_command_hash_and_decision_claim_tampering(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path, manifest, review = _green_run(tmp_path, monkeypatch)
    tampered = json.loads(json.dumps(manifest))
    tampered["commands"][0]["frozen_command_sha256"] = "0" * 64
    runner.seal_execution_manifest(tampered)
    with pytest.raises(runner.EvidenceError, match="command|binding"):
        generator._validate_archived_command_records(tampered, CANDIDATE)

    tampered = json.loads(json.dumps(manifest))
    tampered["commands"][0]["executed_argv"][-1] = "forged-suffix"
    runner.seal_execution_manifest(tampered)
    with pytest.raises(runner.EvidenceError, match="command|binding"):
        generator._validate_archived_command_records(tampered, CANDIDATE)

    tampered = json.loads(json.dumps(manifest))
    tampered["commands"][0]["raw_report_count"] = 2
    runner.seal_execution_manifest(tampered)
    with pytest.raises(runner.EvidenceError, match="count binding"):
        generator._validate_archived_command_records(tampered, CANDIDATE)

    output = tmp_path / "evidence"
    generator.assemble_candidate_evidence(
        manifest=manifest,
        execution_manifest_path=manifest_path,
        p0_review=review,
        output_dir=output,
        release_id=RELEASE,
        candidate_commit=CANDIDATE,
    )
    decision_path = output / generator.DECISION_NAME
    decision = json.loads(decision_path.read_bytes())
    decision["MIG-006"] = "PASS"
    decision_path.write_bytes(generator._canonical_json_bytes(decision))
    with pytest.raises(runner.EvidenceError, match="decision claims"):
        generator._validate_bundle(
            output_dir=output,
            candidate=CANDIDATE,
            release_id=RELEASE,
            manifest=manifest,
        )


def test_rejects_crlf_and_skipped_normalized_report(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path, manifest, review = _green_run(tmp_path, monkeypatch)
    report = manifest_path.parent / "r" / next(iter(runner.SOURCE_REPORTS.values()))
    command_id = runner.COMMAND_ORDER[0]
    payload = _junit(CANDIDATE, command_id, skipped=1)
    report.write_bytes(payload)
    manifest["commands"][0]["report_sha256"] = hashlib.sha256(payload).hexdigest()
    manifest["commands"][0]["skipped"] = 1
    runner.seal_execution_manifest(manifest)
    runner._write_json(manifest_path, manifest)
    with pytest.raises(runner.EvidenceError, match="zero-skip"):
        generator.assemble_candidate_evidence(
            manifest=manifest,
            execution_manifest_path=manifest_path,
            p0_review=review,
            output_dir=tmp_path / "skipped",
            release_id=RELEASE,
            candidate_commit=CANDIDATE,
        )

    payload = _junit(CANDIDATE, command_id).replace(b"\n", b"\r\n")
    with pytest.raises(runner.EvidenceError, match="CR bytes"):
        generator._validate_normalized_report(
            payload,
            candidate=CANDIDATE,
            command_id=command_id,
            minimum_tests=1,
        )


def test_rejects_normalized_junit_content_not_replayed_from_raw_provenance(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path, manifest, review = _green_run(tmp_path, monkeypatch)
    command_id = runner.COMMAND_ORDER[0]
    report = manifest_path.parent / "r" / runner.SOURCE_REPORTS[command_id]
    payload = report.read_bytes().replace(
        b"</testcase>", b"<system-out>forged</system-out></testcase>"
    )
    report.write_bytes(payload)
    manifest["commands"][0]["report_sha256"] = hashlib.sha256(payload).hexdigest()
    runner.seal_execution_manifest(manifest)
    runner._write_json(manifest_path, manifest)

    with pytest.raises(runner.EvidenceError, match="does not match raw provenance"):
        generator.assemble_candidate_evidence(
            manifest=manifest,
            execution_manifest_path=manifest_path,
            p0_review=review,
            output_dir=tmp_path / "forged-normalized",
            release_id=RELEASE,
            candidate_commit=CANDIDATE,
        )


def test_git_filter_check_uses_canonical_bundle_path(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    artifact = tmp_path / "artifact.json"
    artifact.write_bytes(b"{}\n")
    logical = (
        "test-reports/temporal-first/phase-7-candidate-test/"
        "phase-7-candidate/artifact.json"
    )
    observed: list[str | None] = []

    def fake_hash(_payload: bytes, *, logical_path: str | None = None) -> str:
        observed.append(logical_path)
        return "1" * 40 if logical_path is None else "2" * 40

    monkeypatch.setattr(generator, "_git_hash_object", fake_hash)
    with pytest.raises(runner.EvidenceError, match="changes under Git clean filters"):
        generator._assert_git_filter_stable(
            artifact, require_lf=True, logical_path=logical
        )
    assert observed == [None, logical]

def test_source_tree_environment_authenticates_candidate_git_blobs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    dependencies = {
        path: f"{path}\n".encode("utf-8")
        for path in runner.DEPENDENCY_MANIFEST_PATHS
    }
    runner_payload = b"runner"
    changed = [{"path": runner.V045_PATH, "status": "A"}]
    source_tree: dict[str, object] = {
        "base_commit": runner.PHASE7_ENTRY_EVIDENCE,
        "candidate_commit": CANDIDATE,
        "candidate_tree": "9" * 40,
        "changed_paths": changed,
        "prior_migrations_unchanged": True,
        "v045": {"path": runner.V045_PATH, "status": "ADDED_ONLY"},
        "worker_selector_formal_effect_authority_unchanged": True,
    }
    source_tree["snapshot_sha256"] = runner._json_sha256(source_tree)
    environment: dict[str, object] = {
        "candidate_commit": CANDIDATE,
        "dependency_manifests": [
            {
                "byte_source": "CANDIDATE_GIT_BLOB",
                "path": path,
                "sha256": hashlib.sha256(dependencies[path]).hexdigest(),
            }
            for path in runner.DEPENDENCY_MANIFEST_PATHS
        ],
        "runner": {
            "byte_source": "CANDIDATE_GIT_BLOB",
            "path": runner.RUNNER_PATH,
            "sha256": hashlib.sha256(runner_payload).hexdigest(),
        },
        "source_contract_sha256": "7" * 64,
    }
    environment["snapshot_sha256"] = runner._json_sha256(environment)
    document = {
        "candidate_commit": CANDIDATE,
        "environment": environment,
        "schema_version": generator.SOURCE_ENVIRONMENT_SCHEMA,
        "source_tree": source_tree,
    }
    monkeypatch.setattr(runner, "_source_contract_sha256", lambda *_: "7" * 64)
    monkeypatch.setattr(
        runner, "capture_source_tree", lambda *_: copy.deepcopy(source_tree)
    )
    blobs = {
        **dependencies,
        runner.RUNNER_PATH: runner_payload,
    }
    generator._validate_source_tree_environment(
        document,
        candidate=CANDIDATE,
        candidate_blob_reader=lambda path: blobs[path],
    )
    environment["dependency_manifests"][0]["sha256"] = "0" * 64
    environment["snapshot_sha256"] = runner._json_sha256(
        {key: value for key, value in environment.items() if key != "snapshot_sha256"}
    )
    with pytest.raises(runner.EvidenceError, match="dependency Git blob"):
        generator._validate_source_tree_environment(
            document,
            candidate=CANDIDATE,
            candidate_blob_reader=lambda path: blobs[path],
        )


def test_source_tree_environment_rejects_archived_boolean_instead_of_recomputation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_tree = {
        "base_commit": runner.PHASE7_ENTRY_EVIDENCE,
        "candidate_commit": CANDIDATE,
        "candidate_tree": "9" * 40,
        "changed_paths": [{"path": runner.V045_PATH, "status": "A"}],
        "prior_migrations_unchanged": True,
        "v045": {"path": runner.V045_PATH, "status": "ADDED_ONLY"},
        "worker_selector_formal_effect_authority_unchanged": True,
    }
    source_tree["snapshot_sha256"] = runner._json_sha256(source_tree)
    recomputed = copy.deepcopy(source_tree)
    recomputed["prior_migrations_unchanged"] = False
    recomputed["snapshot_sha256"] = runner._json_sha256(
        {key: value for key, value in recomputed.items() if key != "snapshot_sha256"}
    )
    environment = {
        "candidate_commit": CANDIDATE,
        "dependency_manifests": [
            {
                "byte_source": "CANDIDATE_GIT_BLOB",
                "path": path,
                "sha256": hashlib.sha256(path.encode()).hexdigest(),
            }
            for path in runner.DEPENDENCY_MANIFEST_PATHS
        ],
        "runner": {
            "byte_source": "CANDIDATE_GIT_BLOB",
            "path": runner.RUNNER_PATH,
            "sha256": hashlib.sha256(b"runner").hexdigest(),
        },
        "source_contract_sha256": "7" * 64,
    }
    environment["snapshot_sha256"] = runner._json_sha256(environment)
    document = {
        "candidate_commit": CANDIDATE,
        "environment": environment,
        "schema_version": generator.SOURCE_ENVIRONMENT_SCHEMA,
        "source_tree": source_tree,
    }
    monkeypatch.setattr(runner, "capture_source_tree", lambda *_: recomputed)

    with pytest.raises(runner.EvidenceError, match="independent candidate recomputation"):
        generator._validate_source_tree_environment(
            document,
            candidate=CANDIDATE,
            candidate_blob_reader=lambda path: (
                b"runner" if path == runner.RUNNER_PATH else path.encode()
            ),
        )


def test_portable_path_budget_rejects_before_writing(
    tmp_path_factory: pytest.TempPathFactory,
) -> None:
    output = tmp_path_factory.mktemp("p7c") / "x"
    relative = generator.PORTABLE_MAX_ARCHIVE_RELATIVE
    while generator._utf16_path_units(output / relative) <= generator.WINDOWS_PORTABLE_PATH_LIMIT:
        output = output.with_name(output.name + "x")
    with pytest.raises(runner.EvidenceError, match="portable Windows budget"):
        generator._assert_portable_output_paths(output, [relative])
    assert not output.exists()


def test_post_commit_verifier_rejects_wrong_parent_and_extra_content(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _, manifest, _ = _green_run(tmp_path, monkeypatch)
    monkeypatch.setattr(runner, "_assert_candidate", lambda candidate, *_: candidate)
    monkeypatch.setattr(
        generator,
        "_git_text",
        lambda *_: f"{EVIDENCE} {'f' * 40}\n",
    )
    with pytest.raises(runner.EvidenceError, match="sole parent"):
        generator.verify_evidence_commit(
            evidence_commit=EVIDENCE,
            candidate_commit=CANDIDATE,
            release_id=RELEASE,
        )

    monkeypatch.setattr(generator, "_committed_json", lambda *_: manifest)

    def git_text(*arguments: str) -> str:
        if arguments[0] == "rev-list":
            return f"{EVIDENCE} {CANDIDATE}\n"
        if arguments[0] == "diff-tree":
            return "A\tunrelated.txt\n"
        raise AssertionError(arguments)

    monkeypatch.setattr(generator, "_git_text", git_text)
    with pytest.raises(runner.EvidenceError, match="content topology"):
        generator.verify_evidence_commit(
            evidence_commit=EVIDENCE,
            candidate_commit=CANDIDATE,
            release_id=RELEASE,
        )


def test_load_green_manifest_rejects_exact_sha_claim_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest_path, manifest, _ = _green_run(tmp_path, monkeypatch)
    drifted = dict(manifest)
    drifted["candidate_commit"] = "f" * 40
    monkeypatch.setattr(runner, "load_pass_manifest", lambda *_: drifted)
    with pytest.raises(runner.EvidenceError, match="authority or claims"):
        generator.load_green_manifest(manifest_path, CANDIDATE)
