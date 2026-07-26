from __future__ import annotations

import ast
import copy
import hashlib
import json
import os
import tarfile
from dataclasses import replace
from pathlib import Path

import pytest

from scripts.phase8.candidate import command_contract
from scripts.phase8.candidate import github_witness as witness


SHA_A = "a" * 40
SHA_B = "b" * 40
DIGEST_A = "a" * 64
DIGEST_B = "b" * 64


def _junit(classname: str = "com.example.AgentRunV2MigrationIntegrationTest") -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<testsuite name="suite" tests="1" failures="0" errors="0" skipped="0">'
        f'<testcase classname="{classname}" name="works" time="0.1" />'
        "</testsuite>"
    ).encode("utf-8")


def _github(command_id: str) -> dict[str, str]:
    return {
        "candidate_sha": SHA_A,
        "job": f"phase8_{command_id}",
        "job_workflow_file_path": witness.TRUSTED_WORKFLOW_PATH,
        "job_workflow_ref": (
            f"{witness.FIXED_REPOSITORY}/{witness.TRUSTED_WORKFLOW_PATH}@{SHA_B}"
        ),
        "job_workflow_repository": witness.FIXED_REPOSITORY,
        "job_workflow_sha": SHA_B,
        "repository": witness.FIXED_REPOSITORY,
        "repository_id": witness.FIXED_REPOSITORY_ID,
        "run_attempt": "1",
        "run_id": "123",
        "runner_arch": "X64",
        "runner_environment": "github-hosted",
        "runner_os": "Linux",
        "server_url": "https://github.com",
        "trusted_code_sha": SHA_B,
        "workflow_ref": witness.CALLER_WORKFLOW_REF,
        "workflow_sha": SHA_A,
    }


def _trusted_transition() -> dict[str, object]:
    return {
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
        "trusted_code_sha": SHA_B,
        "trusted_code_to_workflow_additions": [],
        "trusted_code_tree_sha": SHA_A,
        "trusted_workflow_sha": SHA_B,
        "trusted_workflow_to_candidate_additions": [],
        "trusted_workflow_tree_sha": SHA_A,
    }


def _trusted_transition_sha256() -> str:
    return witness.candidate_scope.canonical_sha256(_trusted_transition())


def _trusted_inputs(tmp_path: Path) -> dict[str, witness.AuthenticatedFile]:
    tmp_path.mkdir(parents=True, exist_ok=True)
    result: dict[str, witness.AuthenticatedFile] = {}
    for relative in witness.TRUSTED_INPUT_PATHS:
        payload = relative.encode("ascii")
        path = tmp_path / Path(relative).name
        path.write_bytes(payload)
        metadata = path.stat()
        result[relative] = witness.AuthenticatedFile(
            path=path,
            identity=witness._file_identity(metadata),
            payload=payload,
            sha256=hashlib.sha256(payload).hexdigest(),
        )
    return result


def _raw_maven(
    tmp_path: Path,
) -> tuple[dict[str, object], dict[str, object], dict[str, witness.AuthenticatedFile]]:
    contract = command_contract.load_command_contract()
    command = contract["commands"][1]
    inputs = _trusted_inputs(tmp_path)
    expected_artifacts = command["report"]["expected_artifacts"]
    testcase_ids = [
        f"{artifact['suite_name']}::works-{index}"
        for artifact in expected_artifacts
        for index in range(artifact["test_count"])
    ]
    facts = {
        "errors": 0,
        "failures": 0,
        "skipped": 0,
        "suite_ids": sorted({item["suite_name"] for item in expected_artifacts}),
        "testcase_ids": sorted(testcase_ids),
        "tests": len(testcase_ids),
    }
    raw = {
        "attempt_id": "github-123-1",
        "authority": witness.RAW_AUTHORITY,
        "candidate": {
            "candidate_sha": SHA_A,
            "candidate_tree_sha": SHA_B,
            "scope_inventory_sha256": DIGEST_A,
            "trusted_transition": _trusted_transition(),
            "trusted_transition_sha256": _trusted_transition_sha256(),
        },
        "command": {
            **command,
            "contract_payload_sha256": contract["self_seal"]["payload_sha256"],
            "order": 1,
        },
        "execution": {
            "exit_code": 0,
            "output_limited": False,
            "report_totals": facts,
            "status": "PASSED",
            "stderr": {"bytes": 0, "sha256": DIGEST_A, "summary": ""},
            "stdout": {"bytes": 12, "sha256": DIGEST_B, "summary": "BUILD SUCCESS"},
            "timed_out": False,
        },
        "github": _github(command["id"]),
        "materialization": {
            "candidate_archive_ref": {
                "bytes": 3,
                "path": (
                    "commands/001-wave_a_java/materialization/"
                    f"candidate-sha256-{DIGEST_A}.tar"
                ),
                "sha256": DIGEST_A,
            },
            "manifest_ref": {
                "bytes": 1,
                "path": "commands/001-wave_a_java/materialization/manifest.json",
                "sha256": DIGEST_A,
            },
            "receipt_ref": {
                "bytes": 1,
                "path": "commands/001-wave_a_java/materialization/receipt.json",
                "sha256": DIGEST_B,
            },
        },
        "reports": [
            {
                "bytes": len(_junit()),
                "format": "JUNIT_XML",
                "path": f"reports/{artifact['filename']}",
                "sha256": hashlib.sha256(_junit()).hexdigest(),
            }
            for artifact in command["report"]["expected_artifacts"]
        ],
        "runtime": None,
        "schema_version": witness.RAW_SCHEMA_VERSION,
    }
    candidate = {"candidate_sha": SHA_A, "candidate_tree_sha": SHA_B}
    scope = {"derived_inventory_sha256": DIGEST_A}
    normalized = witness._validate_raw_result(
        raw,
        command=command,
        command_contract_payload_sha256=contract["self_seal"]["payload_sha256"],
        raw_directory_name="001-wave_a_java",
        order=1,
        attempt_id="github-123-1",
        candidate=candidate,
        scope=scope,
        github={
            key: value
            for key, value in _github(command["id"]).items()
            if key not in {"job", "trusted_code_sha"}
        },
        trusted_sha=SHA_B,
        trusted_transition=_trusted_transition(),
        trusted_transition_sha256=_trusted_transition_sha256(),
    )
    return raw, normalized, inputs


def test_cli_is_a_pure_aggregator_without_executor_injection() -> None:
    parser = witness._parser()
    destinations = {action.dest for action in parser._actions}
    assert {
        "candidate_dir",
        "candidate_sha",
        "raw_artifacts_dir",
        "output_dir",
        "attempt_id",
        "trusted_code_sha",
        "trusted_workflow_sha",
        "trusted_workflow_ref",
        "trusted_workflow_repository",
        "trusted_workflow_file_path",
    }.issubset(destinations)
    assert "executor" not in destinations
    assert "trusted_builder_root" not in destinations


def test_subprocess_is_confined_to_fixed_read_only_git_query() -> None:
    tree = ast.parse(Path(witness.__file__).read_text(encoding="utf-8"))
    subprocess_calls: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            if (
                isinstance(node.func.value, ast.Name)
                and node.func.value.id == "subprocess"
            ):
                subprocess_calls.append(node.func.attr)
                owner = next(
                    parent
                    for parent in ast.walk(tree)
                    if isinstance(parent, (ast.FunctionDef, ast.AsyncFunctionDef))
                    and node in ast.walk(parent)
                )
                assert owner.name == "_git_query"
    assert subprocess_calls == ["run"]
    imports = {
        alias.name
        for node in tree.body
        if isinstance(node, ast.Import)
        for alias in node.names
    }
    assert not imports.intersection({"requests", "socket", "urllib", "httpx"})
    source = Path(witness.__file__).read_text(encoding="utf-8")
    assert '"GIT_NO_LAZY_FETCH": "1"' in source
    assert '"protocol.allow=never"' in source
    assert '"--no-replace-objects"' in source
    assert "GIT_EXECUTABLE_IDENTITY" in source


def test_bounded_json_rejects_duplicate_keys_and_excess_depth() -> None:
    with pytest.raises(witness.WitnessValidationError, match="duplicate key"):
        witness._bounded_json(b'{"a":1,"a":2}', "fixture")
    payload = json.dumps({"a": {"b": {"c": []}}}).encode()
    original = witness.MAX_JSON_DEPTH
    try:
        witness.MAX_JSON_DEPTH = 2
        with pytest.raises(witness.WitnessValidationError, match="too complex"):
            witness._bounded_json(payload, "fixture")
    finally:
        witness.MAX_JSON_DEPTH = original


def test_junit_counts_actual_testcases_and_rejects_dtd_secret_and_duplicates() -> None:
    facts = witness._parse_junit(_junit(), "fixture")
    assert facts.tests == 1
    assert facts.failures == facts.errors == facts.skipped == 0
    with pytest.raises(witness.WitnessValidationError, match="XML declarations"):
        witness._parse_junit(b'<!DOCTYPE foo><testsuite name="x"/>', "fixture")
    with pytest.raises(witness.WitnessValidationError, match="credential"):
        witness._parse_junit(
            b'<testsuite name="x"><testcase classname="x" name="y"><system-out>password=hunter2</system-out></testcase></testsuite>',
            "fixture",
        )
    duplicate = (
        b'<testsuite name="x"><testcase classname="x" name="y"/>'
        b'<testcase classname="x" name="y"/></testsuite>'
    )
    with pytest.raises(witness.WitnessValidationError, match="duplicates"):
        witness._parse_junit(duplicate, "fixture")
    rerun = (
        b'<testsuite name="x"><testcase classname="x" name="y">'
        b"<rerunFailure/></testcase></testsuite>"
    )
    assert witness._parse_junit(rerun, "fixture").failures == 1
    namespaced = (
        b'<testsuite xmlns="urn:unexpected" name="x">'
        b'<testcase classname="x" name="y"/></testsuite>'
    )
    with pytest.raises(witness.WitnessValidationError, match="namespaces"):
        witness._parse_junit(namespaced, "fixture")


def test_maven_raw_result_binds_exact_command_and_report_totals(tmp_path: Path) -> None:
    raw, normalized, _ = _raw_maven(tmp_path)
    assert normalized["execution"]["report_totals"]["tests"] == 2
    assert normalized["candidate"]["trusted_transition"] == _trusted_transition()
    assert (
        normalized["candidate"]["trusted_transition_sha256"]
        == _trusted_transition_sha256()
    )
    raw["command"]["argv"] = ["./mvnw", "test"]
    contract = command_contract.load_command_contract()
    with pytest.raises(witness.WitnessValidationError, match="actual command differs"):
        witness._validate_raw_result(
            raw,
            command=contract["commands"][1],
            command_contract_payload_sha256=contract["self_seal"]["payload_sha256"],
            raw_directory_name="001-wave_a_java",
            order=1,
            attempt_id="github-123-1",
            candidate={"candidate_sha": SHA_A, "candidate_tree_sha": SHA_B},
            scope={"derived_inventory_sha256": DIGEST_A},
            github={
                key: value
                for key, value in _github("wave_a_java").items()
                if key not in {"job", "trusted_code_sha"}
            },
            trusted_sha=SHA_B,
            trusted_transition=_trusted_transition(),
            trusted_transition_sha256=_trusted_transition_sha256(),
        )


def test_output_summary_rejects_credential_material() -> None:
    with pytest.raises(witness.WitnessValidationError, match="credentials"):
        witness._validate_output_summary(
            {"bytes": 20, "sha256": DIGEST_A, "summary": "Authorization=Bearer abc"},
            "stdout",
        )


def test_materialization_refs_are_fixed_raw_root_relative_paths(tmp_path: Path) -> None:
    raw, _, inputs = _raw_maven(tmp_path)
    raw["materialization"]["manifest_ref"]["path"] = "manifest.json"
    contract = command_contract.load_command_contract()
    with pytest.raises(witness.WitnessValidationError, match="path differs"):
        witness._validate_raw_result(
            raw,
            command=contract["commands"][1],
            command_contract_payload_sha256=contract["self_seal"]["payload_sha256"],
            raw_directory_name="001-wave_a_java",
            order=1,
            attempt_id="github-123-1",
            candidate={"candidate_sha": SHA_A, "candidate_tree_sha": SHA_B},
            scope={"derived_inventory_sha256": DIGEST_A},
            github={
                key: value
                for key, value in _github("wave_a_java").items()
                if key not in {"job", "trusted_code_sha"}
            },
            trusted_sha=SHA_B,
            trusted_transition=_trusted_transition(),
            trusted_transition_sha256=_trusted_transition_sha256(),
        )
    assert inputs

    raw, _, _ = _raw_maven(tmp_path / "archive")
    raw["materialization"]["candidate_archive_ref"]["path"] = (
        f"commands/001-wave_a_java/materialization/candidate-sha256-{DIGEST_B}.tar"
    )
    with pytest.raises(witness.WitnessValidationError, match="content addressed"):
        witness._validate_raw_result(
            raw,
            command=contract["commands"][1],
            command_contract_payload_sha256=contract["self_seal"]["payload_sha256"],
            raw_directory_name="001-wave_a_java",
            order=1,
            attempt_id="github-123-1",
            candidate={"candidate_sha": SHA_A, "candidate_tree_sha": SHA_B},
            scope={"derived_inventory_sha256": DIGEST_A},
            github={
                key: value
                for key, value in _github("wave_a_java").items()
                if key not in {"job", "trusted_code_sha"}
            },
            trusted_sha=SHA_B,
            trusted_transition=_trusted_transition(),
            trusted_transition_sha256=_trusted_transition_sha256(),
        )


def test_static_raw_result_is_ref_only_and_witness_uses_runtime_authorizer() -> None:
    contract = command_contract.load_command_contract()
    command = contract["commands"][0]
    selected = [
        Path(item).stem for item in command["argv"] if item.startswith("tests/")
    ]
    testcase_ids = [f"tests.static.{name}::works" for name in selected]
    testcase_ids.extend(
        f"tests.static.{selected[0]}::case-{index}"
        for index in range(
            command["report"]["expected_artifacts"][0]["test_count"] - len(testcase_ids)
        )
    )
    facts = {
        "errors": 0,
        "failures": 0,
        "skipped": 0,
        "suite_ids": ["pytest"],
        "testcase_ids": sorted(testcase_ids),
        "tests": len(testcase_ids),
    }
    raw = {
        "attempt_id": "github-123-1",
        "authority": witness.RAW_AUTHORITY,
        "candidate": {
            "candidate_sha": SHA_A,
            "candidate_tree_sha": SHA_B,
            "scope_inventory_sha256": DIGEST_A,
            "trusted_transition": _trusted_transition(),
            "trusted_transition_sha256": _trusted_transition_sha256(),
        },
        "command": {
            **command,
            "contract_payload_sha256": contract["self_seal"]["payload_sha256"],
            "order": 0,
        },
        "execution": {
            "exit_code": 0,
            "output_limited": False,
            "report_totals": facts,
            "status": "PASSED",
            "stderr": {"bytes": 0, "sha256": DIGEST_A, "summary": ""},
            "stdout": {"bytes": 1, "sha256": DIGEST_B, "summary": "ok"},
            "timed_out": False,
        },
        "github": _github(command["id"]),
        "materialization": {
            "candidate_archive_ref": {
                "bytes": 3,
                "path": (
                    "commands/000-wave_a_static/materialization/"
                    f"candidate-sha256-{DIGEST_A}.tar"
                ),
                "sha256": DIGEST_A,
            },
            "manifest_ref": {
                "bytes": 1,
                "path": "commands/000-wave_a_static/materialization/manifest.json",
                "sha256": DIGEST_A,
            },
            "receipt_ref": {
                "bytes": 1,
                "path": "commands/000-wave_a_static/materialization/receipt.json",
                "sha256": DIGEST_B,
            },
        },
        "reports": [
            {
                "bytes": 1,
                "format": "JUNIT_XML",
                "path": f"reports/{command['report']['expected_artifacts'][0]['filename']}",
                "sha256": DIGEST_A,
            }
        ],
        "runtime": {
            "artifact_transport_receipt_ref": {
                "bytes": 1,
                "path": (
                    "commands/000-wave_a_static/runtime/artifact-transport-receipt.json"
                ),
                "sha256": DIGEST_A,
            },
            "build_observation_receipt_ref": {
                "bytes": 1,
                "path": "shared-runtime/observer/build-observation-receipt.json",
                "sha256": DIGEST_A,
            },
            "dispatch_ref": {
                "bytes": 1,
                "path": "commands/000-wave_a_static/runtime/dispatch.json",
                "sha256": DIGEST_A,
            },
            "observer_docker_archive_ref": {
                "bytes": 1,
                "path": f"shared-runtime/observer/docker/sha256-{DIGEST_B}.tar",
                "sha256": DIGEST_B,
            },
            "observer_oci_archive_ref": {
                "bytes": 1,
                "path": f"shared-runtime/observer/oci/sha256-{DIGEST_A}.tar",
                "sha256": DIGEST_A,
            },
            "producer_docker_archive_ref": {
                "bytes": 1,
                "path": f"shared-runtime/producer/docker/sha256-{DIGEST_A}.tar",
                "sha256": DIGEST_A,
            },
            "producer_oci_archive_ref": {
                "bytes": 1,
                "path": f"shared-runtime/producer/oci/sha256-{DIGEST_B}.tar",
                "sha256": DIGEST_B,
            },
            "runtime_build_receipt_ref": {
                "bytes": 1,
                "path": "shared-runtime/producer/runtime-build-receipt.json",
                "sha256": DIGEST_A,
            },
            "wheelhouse_manifest_ref": {
                "bytes": 1,
                "path": "shared-runtime/producer/wheelhouse-manifest.json",
                "sha256": DIGEST_A,
            },
        },
        "schema_version": witness.RAW_SCHEMA_VERSION,
    }

    def validate(candidate: dict[str, object]) -> dict[str, object]:
        return witness._validate_raw_result(
            candidate,
            command=command,
            command_contract_payload_sha256=contract["self_seal"]["payload_sha256"],
            raw_directory_name="000-wave_a_static",
            order=0,
            attempt_id="github-123-1",
            candidate={"candidate_sha": SHA_A, "candidate_tree_sha": SHA_B},
            scope={"derived_inventory_sha256": DIGEST_A},
            github={
                key: value
                for key, value in _github(command["id"]).items()
                if key not in {"job", "trusted_code_sha"}
            },
            trusted_sha=SHA_B,
            trusted_transition=_trusted_transition(),
            trusted_transition_sha256=_trusted_transition_sha256(),
        )

    normalized = validate(raw)
    assert set(normalized["runtime"]) == {
        "artifact_transport_receipt_ref",
        "build_observation_receipt_ref",
        "dispatch_ref",
        "observer_docker_archive_ref",
        "observer_oci_archive_ref",
        "producer_docker_archive_ref",
        "producer_oci_archive_ref",
        "runtime_build_receipt_ref",
        "wheelhouse_manifest_ref",
    }
    source = Path(witness.__file__).read_text(encoding="utf-8")
    assert "verify_materialization_receipt_offline" in source
    assert "verify_shared_runtime_receipts" in source
    assert "verify_static_dispatch_receipts" in source
    assert 'archive_files["runtime/shared/archive-index.json"]' in source
    assert '"physical_identity": list(item.identity)' in source

    omitted = copy.deepcopy(raw)
    del omitted["runtime"]["producer_docker_archive_ref"]
    with pytest.raises(witness.WitnessValidationError, match="fields differ"):
        validate(omitted)

    extra = copy.deepcopy(raw)
    extra["runtime"]["extra_docker_archive_ref"] = copy.deepcopy(
        extra["runtime"]["producer_docker_archive_ref"]
    )
    with pytest.raises(witness.WitnessValidationError, match="fields differ"):
        validate(extra)

    cross_wired = copy.deepcopy(raw)
    cross_wired["runtime"]["producer_docker_archive_ref"]["path"] = (
        f"shared-runtime/observer/docker/sha256-{DIGEST_A}.tar"
    )
    with pytest.raises(witness.WitnessValidationError, match="content addressed"):
        validate(cross_wired)

    substituted = copy.deepcopy(raw)
    substituted["runtime"]["observer_docker_archive_ref"]["path"] = (
        f"shared-runtime/observer/docker/sha256-{DIGEST_A}.tar"
    )
    with pytest.raises(witness.WitnessValidationError, match="content addressed"):
        validate(substituted)

    traversal = copy.deepcopy(raw)
    traversal["runtime"]["producer_docker_archive_ref"]["path"] = (
        "shared-runtime/producer/docker/../escape.tar"
    )
    with pytest.raises(witness.WitnessValidationError, match="path"):
        validate(traversal)


def test_raw_topology_must_be_an_exact_stopped_prefix(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    root = (tmp_path / "raw").resolve()
    root.mkdir()
    (root / "commands").mkdir()
    (root / "commands" / "001-wave_a_java").mkdir()
    (root / "shared-runtime").mkdir()
    shared = object()
    monkeypatch.setattr(witness, "_read_shared_runtime", lambda path: shared)
    monkeypatch.setattr(
        witness, "_verify_shared_runtime", lambda actual, **kwargs: object()
    )
    with pytest.raises(witness.WitnessValidationError, match="exact prefix"):
        witness._read_raw_prefix(
            root,
            contract=command_contract.load_command_contract(),
            attempt_id="github-123-1",
            candidate={},
            scope={},
            github={},
            trusted_sha=SHA_B,
            trusted_transition=_trusted_transition(),
            trusted_transition_sha256=_trusted_transition_sha256(),
            policy={},
        )


def test_shared_runtime_topology_is_exact_and_bounded(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    root = (tmp_path / "shared-runtime").resolve()
    producer_dir = root / "producer"
    observer_dir = root / "observer"
    producer_oci_dir = producer_dir / "oci"
    producer_docker_dir = producer_dir / "docker"
    observer_oci_dir = observer_dir / "oci"
    observer_docker_dir = observer_dir / "docker"
    wheelhouse_dir = producer_dir / "wheelhouse"
    producer_oci_dir.mkdir(parents=True)
    producer_docker_dir.mkdir()
    observer_oci_dir.mkdir(parents=True)
    observer_docker_dir.mkdir()
    wheelhouse_dir.mkdir()
    (producer_dir / "runtime-build-receipt.json").write_text("{}", encoding="utf-8")
    (observer_dir / "build-observation-receipt.json").write_text("{}", encoding="utf-8")

    producer_payload = b"producer-oci"
    observer_payload = b"observer-oci"
    producer_docker_payload = b"producer-docker"
    observer_docker_payload = b"observer-docker"
    producer_digest = hashlib.sha256(producer_payload).hexdigest()
    observer_digest = hashlib.sha256(observer_payload).hexdigest()
    producer_docker_digest = hashlib.sha256(producer_docker_payload).hexdigest()
    observer_docker_digest = hashlib.sha256(observer_docker_payload).hexdigest()
    producer_image = producer_oci_dir / f"sha256-{producer_digest}.tar"
    observer_image = observer_oci_dir / f"sha256-{observer_digest}.tar"
    producer_docker_image = producer_docker_dir / f"sha256-{producer_docker_digest}.tar"
    observer_docker_image = observer_docker_dir / f"sha256-{observer_docker_digest}.tar"
    producer_image.write_bytes(producer_payload)
    observer_image.write_bytes(observer_payload)
    producer_docker_image.write_bytes(producer_docker_payload)
    observer_docker_image.write_bytes(observer_docker_payload)

    locked = witness.runtime_policy._requirements_lock_records()
    synthetic_lock: dict[str, tuple[str, str]] = {}
    wheelhouse_manifest: list[dict[str, object]] = []
    for distribution, (version, _) in sorted(locked.items()):
        filename = f"{distribution.replace('-', '_')}-{version}-py3-none-any.whl"
        payload = f"wheel:{distribution}:{version}".encode("ascii")
        digest = hashlib.sha256(payload).hexdigest()
        synthetic_lock[distribution] = (version, digest)
        (wheelhouse_dir / filename).write_bytes(payload)
        wheelhouse_manifest.append(
            {"bytes": len(payload), "filename": filename, "sha256": digest}
        )
    wheelhouse_manifest.sort(key=lambda entry: str(entry["filename"]))
    monkeypatch.setattr(
        witness.runtime_policy,
        "_requirements_lock_records",
        lambda: synthetic_lock,
    )
    (producer_dir / "wheelhouse-manifest.json").write_bytes(
        witness.runtime_policy.canonical_json_bytes(wheelhouse_manifest)
    )

    shared = witness._read_shared_runtime(root)
    assert shared.root == root
    assert shared.build_receipt == {}
    assert shared.observation_receipt == {}
    assert shared.producer_oci_archive_path == producer_image
    assert shared.producer_docker_archive_path == producer_docker_image
    assert shared.observer_oci_archive_path == observer_image
    assert shared.observer_docker_archive_path == observer_docker_image
    assert shared.wheelhouse_manifest == wheelhouse_manifest
    assert len(shared.wheel_files) == witness.EXPECTED_WHEELHOUSE_FILES == 15

    receipt = {
        "producer_oci_archive_bytes": len(producer_payload),
        "producer_oci_archive_sha256": producer_digest,
        "producer_docker_archive_bytes": len(producer_docker_payload),
        "producer_docker_archive_sha256": producer_docker_digest,
        "observer_oci_archive_bytes": len(observer_payload),
        "observer_oci_archive_sha256": observer_digest,
        "observer_docker_archive_bytes": len(observer_docker_payload),
        "observer_docker_archive_sha256": observer_docker_digest,
    }
    bound = replace(shared, observation_receipt=receipt)
    archives = witness._capture_shared_runtime_archives(bound)
    assert set(archives) == {
        "producer_oci",
        "producer_docker",
        "observer_oci",
        "observer_docker",
    }

    mismatched = replace(
        bound,
        observation_receipt={
            **receipt,
            "producer_docker_archive_sha256": DIGEST_A,
        },
    )
    with pytest.raises(witness.WitnessValidationError, match="digest differs"):
        witness._capture_shared_runtime_archives(mismatched)

    extra_docker = producer_docker_dir / f"sha256-{DIGEST_A}.tar"
    extra_docker.write_bytes(b"extra")
    with pytest.raises(witness.WitnessValidationError, match="archive set differs"):
        witness._read_shared_runtime(root)
    extra_docker.unlink()

    observer_docker_image.unlink()
    os.link(producer_docker_image, observer_docker_image)
    aliased_receipt = {
        **receipt,
        "observer_docker_archive_bytes": len(producer_docker_payload),
        "observer_docker_archive_sha256": producer_docker_digest,
    }
    with pytest.raises(
        witness.WitnessValidationError, match="identity differs|aliased"
    ):
        witness._capture_shared_runtime_archives(
            replace(shared, observation_receipt=aliased_receipt)
        )
    observer_docker_image.unlink()
    observer_docker_image.write_bytes(observer_docker_payload)

    (observer_dir / "extra.json").write_text("{}", encoding="utf-8")
    with pytest.raises(witness.WitnessValidationError, match="file set differs"):
        witness._read_shared_runtime(root)


def test_raw_tree_budget_accounts_for_four_bounded_runtime_archives(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    assert witness.MAX_RAW_TREE_BYTES == (
        (2 * witness.runtime_policy.MAX_OCI_ARCHIVE_BYTES)
        + (2 * witness.runtime_policy.MAX_DOCKER_ARCHIVE_BYTES)
        + witness.MAX_CANDIDATE_ARCHIVE_TOTAL_BYTES
        + witness.MAX_REPORT_TOTAL_BYTES
        + (4 * 1024 * 1024 * 1024)
    )
    root = tmp_path / "raw"
    root.mkdir()
    (root / "oversized").write_bytes(b"1234")
    monkeypatch.setattr(witness, "MAX_RAW_TREE_BYTES", 3)
    with pytest.raises(witness.WitnessValidationError, match="byte budget"):
        witness._assert_directory_tree(root, "raw fixture")


def test_materialization_binding_selects_the_exact_closure_inventory() -> None:
    full = {
        "entries": [],
        "file_count": 10,
        "manifest_sha256": DIGEST_A,
        "total_bytes": 100,
    }
    java = {
        "entries": [],
        "file_count": 4,
        "manifest_sha256": DIGEST_B,
        "total_bytes": 40,
    }
    candidate = {
        "accepted_entry_sha": witness.ACCEPTED_A8,
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
    }
    scope = {
        "derived_inventory_sha256": DIGEST_A,
        "materialization_inventories": {
            witness.candidate_scope.FULL_REPOSITORY: full,
            witness.candidate_scope.JAVA_SERVICE_ONLY: java,
        },
    }
    binding, selected = witness._expected_materialization_binding(
        candidate,
        scope,
        witness.candidate_scope.JAVA_SERVICE_ONLY,
        {"bytes": 123, "sha256": DIGEST_B},
    )
    assert selected is java
    assert binding == {
        "accepted_entry_sha": witness.ACCEPTED_A8,
        "candidate_archive_bytes": 123,
        "candidate_archive_entry_count": 4,
        "candidate_archive_format": witness.runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
        "candidate_archive_sha256": DIGEST_B,
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
        "closure_kind": witness.candidate_scope.JAVA_SERVICE_ONLY,
        "derived_inventory_sha256": DIGEST_A,
        "manifest_file_count": 4,
        "manifest_sha256": DIGEST_B,
        "manifest_total_bytes": 40,
    }


def test_materialization_set_requires_distinct_archive_evidence_and_nonces() -> None:
    seen: dict[str, set[object]] = {
        "archive_device_inode": set(),
        "archive_fallback_identity": set(),
        "archive_path": set(),
        "nonce": set(),
        "receipt_sha256": set(),
    }
    closures: dict[str, tuple[object, ...]] = {}
    first = {
        "closure_kind": witness.candidate_scope.FULL_REPOSITORY,
        "created_nonce": "1" * 64,
        "receipt_sha256": DIGEST_A,
        "verified_nonce": "2" * 64,
    }
    first_archive = {
        "archive_bytes": 100,
        "archive_entry_count": 10,
        "archive_format": witness.runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
        "archive_path": "/runner-temp/candidate-a.tar",
        "archive_physical_identity": [1, 2],
        "archive_sha256": DIGEST_A,
        "physical_identity_kind": "DEVICE_INODE",
    }
    witness._assert_distinct_materialization(first, first_archive, seen, closures)

    same_physical_file = {
        **first,
        "created_nonce": "3" * 64,
        "receipt_sha256": DIGEST_B,
        "verified_nonce": "4" * 64,
    }
    same_physical_archive = {
        **first_archive,
        "archive_path": "/runner-temp/candidate-b.tar",
    }
    with pytest.raises(
        witness.WitnessValidationError, match="archive_device_inode was reused"
    ):
        witness._assert_distinct_materialization(
            same_physical_file, same_physical_archive, seen, closures
        )

    same_path_archive = {
        **first_archive,
        "archive_physical_identity": [3, 4],
    }
    with pytest.raises(witness.WitnessValidationError, match="archive_path was reused"):
        witness._assert_distinct_materialization(
            same_physical_file, same_path_archive, seen, closures
        )

    reused_nonce = {
        **first,
        "created_nonce": first["verified_nonce"],
        "receipt_sha256": "c" * 64,
        "verified_nonce": "5" * 64,
    }
    new_archive = {
        **first_archive,
        "archive_path": "/runner-temp/candidate-c.tar",
        "archive_physical_identity": [5, 6],
    }
    with pytest.raises(witness.WitnessValidationError, match="nonce was reused"):
        witness._assert_distinct_materialization(
            reused_nonce, new_archive, seen, closures
        )

    fallback_receipt = {
        **first,
        "created_nonce": "6" * 64,
        "receipt_sha256": DIGEST_B,
        "verified_nonce": "7" * 64,
    }
    fallback_archive = {
        **first_archive,
        "archive_path": "/runner-temp/candidate-d.tar",
        "archive_physical_identity": [0, 0, 1, 1, 100, 200, 0],
        "physical_identity_kind": "CANONICAL_PATH_SINGLE_LINK",
    }
    witness._assert_distinct_materialization(
        fallback_receipt, fallback_archive, seen, closures
    )

    mismatched_full_receipt = {
        **first,
        "created_nonce": "8" * 64,
        "receipt_sha256": "d" * 64,
        "verified_nonce": "9" * 64,
    }
    mismatched_full_archive = {
        **first_archive,
        "archive_path": "/runner-temp/candidate-e.tar",
        "archive_physical_identity": [7, 8],
        "archive_sha256": DIGEST_B,
    }
    with pytest.raises(witness.WitnessValidationError, match="one closure differ"):
        witness._assert_distinct_materialization(
            mismatched_full_receipt, mismatched_full_archive, seen, closures
        )

    different_closure = {
        **first,
        "closure_kind": witness.candidate_scope.JAVA_SERVICE_ONLY,
        "created_nonce": "a" * 64,
        "receipt_sha256": "e" * 64,
        "verified_nonce": "b" * 64,
    }
    java_archive = {
        **first_archive,
        "archive_bytes": 50,
        "archive_entry_count": 5,
        "archive_path": "/runner-temp/candidate-f.tar",
        "archive_physical_identity": [0, 0, 1, 1, 100, 200, 0],
        "archive_sha256": DIGEST_B,
        "physical_identity_kind": "CANONICAL_PATH_SINGLE_LINK",
    }
    witness._assert_distinct_materialization(
        different_closure, java_archive, seen, closures
    )


def test_final_merger_receives_the_three_java_only_materialization_bundles() -> None:
    contract = command_contract.load_command_contract()
    java_commands = [
        command["id"]
        for command in contract["commands"]
        if command["backend_kind"] != command_contract.STATIC_BACKEND_KIND
    ]
    assert java_commands == [
        "wave_a_java",
        "wave_b_java_unit",
        "wave_b_postgresql_integration",
    ]
    source = Path(witness.__file__).read_text(encoding="utf-8")
    assert "java_materialization_executions.append(materialization_execution)" in source
    assert "runtime_policy.verify_engineering_materialization_set(" in source
    assert "java_materialization_executions," in source
    assert 'archive_files["runtime/execution-set.json"]' in source


def test_shared_runtime_verifier_binds_fixed_builder_and_observer_jobs(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    github = {
        key: value
        for key, value in _github("wave_a_static").items()
        if key not in {"job", "trusted_code_sha"}
    }
    builder = witness._github_job_identity(
        github, witness.runtime_policy.BUILD_JOB_NAME
    )
    observer = witness._github_job_identity(
        github, witness.runtime_policy.OBSERVER_JOB_NAME
    )
    root = tmp_path / "shared-runtime"
    wheelhouse_root = root / "producer" / "wheelhouse"
    wheelhouse_root.mkdir(parents=True)
    build_path = root / "producer" / "runtime-build-receipt.json"
    observation_path = root / "observer" / "build-observation-receipt.json"
    observation_path.parent.mkdir()
    wheelhouse_manifest_path = root / "producer" / "wheelhouse-manifest.json"
    producer_image_path = root / "producer" / "oci" / f"sha256-{DIGEST_A}.tar"
    observer_image_path = root / "observer" / "oci" / f"sha256-{DIGEST_B}.tar"
    producer_docker_path = root / "producer" / "docker" / f"sha256-{DIGEST_B}.tar"
    observer_docker_path = root / "observer" / "docker" / f"sha256-{DIGEST_A}.tar"
    producer_image_path.parent.mkdir()
    observer_image_path.parent.mkdir()
    producer_docker_path.parent.mkdir()
    observer_docker_path.parent.mkdir()
    for path in (build_path, observation_path):
        path.write_text("{}", encoding="utf-8")
    wheelhouse_manifest_path.write_text("[]", encoding="utf-8")
    producer_image_path.write_bytes(b"producer")
    observer_image_path.write_bytes(b"observer")
    producer_docker_path.write_bytes(b"producer-docker")
    observer_docker_path.write_bytes(b"observer-docker")
    build_receipt = {
        "builder_job_identity": builder,
        "receipt_sha256": DIGEST_A,
    }
    observation_receipt = {
        "base_image_inspect_projection": {},
        "base_image_inspect_projection_sha256": DIGEST_A,
        "build_provenance": {},
        "build_provenance_sha256": DIGEST_A,
        "observer_build_parameters": {},
        "observer_build_parameters_sha256": DIGEST_A,
        "observer_image_inspect_projection": {},
        "observer_image_inspect_projection_sha256": DIGEST_A,
        "observer_job_identity": observer,
        "observer_docker_archive_bytes": len(b"observer-docker"),
        "observer_docker_archive_sha256": DIGEST_A,
        "observer_oci_archive_bytes": len(b"observer"),
        "observer_oci_archive_sha256": DIGEST_B,
        "producer_image_inspect_projection": {},
        "producer_image_inspect_projection_sha256": DIGEST_A,
        "producer_docker_archive_bytes": len(b"producer-docker"),
        "producer_docker_archive_sha256": DIGEST_B,
        "producer_oci_archive_bytes": len(b"producer"),
        "producer_oci_archive_sha256": DIGEST_A,
        "receipt_sha256": DIGEST_B,
        "wheelhouse_manifest": [],
    }
    shared = witness.SharedRuntimeEvidence(
        root=root,
        build_file=witness._read_authenticated_file(build_path, "build", 100),
        build_receipt=build_receipt,
        producer_oci_archive_path=producer_image_path,
        producer_docker_archive_path=producer_docker_path,
        wheelhouse_manifest_file=witness._read_authenticated_file(
            wheelhouse_manifest_path, "wheelhouse manifest", 100
        ),
        wheelhouse_manifest=[],
        wheel_files=(),
        observation_file=witness._read_authenticated_file(
            observation_path, "observation", 100
        ),
        observation_receipt=observation_receipt,
        observer_oci_archive_path=observer_image_path,
        observer_docker_archive_path=observer_docker_path,
    )
    captured: dict[str, object] = {}
    sentinel = object()

    def verify(*args: object, **kwargs: object) -> object:
        captured["args"] = args
        captured["kwargs"] = kwargs
        return sentinel

    monkeypatch.setattr(
        witness.runtime_policy, "verify_shared_runtime_receipts", verify
    )
    assert (
        witness._verify_shared_runtime(shared, github=github, policy={}, contract={})
        is sentinel
    )
    expected = captured["args"][2]
    assert expected["observer_job_identity"] == observer
    assert expected["producer_oci_archive_bytes"] == len(b"producer")
    assert expected["producer_oci_archive_sha256"] == DIGEST_A
    assert expected["observer_oci_archive_bytes"] == len(b"observer")
    assert expected["observer_oci_archive_sha256"] == DIGEST_B
    assert expected["producer_docker_archive_bytes"] == len(b"producer-docker")
    assert expected["producer_docker_archive_sha256"] == DIGEST_B
    assert expected["observer_docker_archive_bytes"] == len(b"observer-docker")
    assert expected["observer_docker_archive_sha256"] == DIGEST_A
    assert captured["kwargs"]["producer_oci_archive_path"] == producer_image_path
    assert captured["kwargs"]["observer_oci_archive_path"] == observer_image_path
    assert captured["kwargs"]["producer_docker_archive_path"] == producer_docker_path
    assert captured["kwargs"]["observer_docker_archive_path"] == observer_docker_path
    assert captured["kwargs"]["wheelhouse_root"] == wheelhouse_root
    del observation_receipt["build_provenance"]
    with pytest.raises(witness.WitnessValidationError, match="shape differs"):
        witness._verify_shared_runtime(shared, github=github, policy={}, contract={})


def test_witness_run_binding_matches_runtime_policy_and_fixed_job_identities() -> None:
    github = {
        key: value
        for key, value in _github("wave_a_static").items()
        if key not in {"job", "trusted_code_sha"}
    }
    expected = {
        "caller_workflow_ref": witness.CALLER_WORKFLOW_REF,
        "caller_workflow_sha": SHA_A,
        "repository": witness.FIXED_REPOSITORY,
        "repository_id": witness.FIXED_REPOSITORY_ID,
        "run_attempt": 1,
        "run_id": "123",
        "runner_arch": "X64",
        "runner_environment": "github-hosted",
        "runner_os": "Linux",
        "trusted_workflow_path": witness.TRUSTED_WORKFLOW_PATH,
        "trusted_workflow_ref": (
            f"{witness.FIXED_REPOSITORY}/{witness.TRUSTED_WORKFLOW_PATH}@{SHA_B}"
        ),
        "trusted_workflow_repository": witness.FIXED_REPOSITORY,
        "trusted_workflow_sha": SHA_B,
    }

    run_binding = witness._expected_run_binding(github)
    assert run_binding == expected
    assert set(run_binding) == witness.runtime_policy._RUN_BINDING_KEYS
    validated, _ = witness.runtime_policy.validate_expected_run_binding(run_binding)
    assert validated == expected

    for job_name in (
        witness.runtime_policy.BUILD_JOB_NAME,
        witness.runtime_policy.OBSERVER_JOB_NAME,
    ):
        identity = witness._github_job_identity(github, job_name)
        assert identity["runner_arch"] == "X64"
        assert identity["runner_environment"] == "github-hosted"
        assert identity["runner_os"] == "Linux"
        validated_identity, _ = witness.runtime_policy.validate_github_job_identity(
            identity,
            allowed_job_names=(job_name,),
            expected_run_binding=expected,
        )
        assert validated_identity == identity


def test_authenticated_file_rejects_symlink_and_hardlink(tmp_path: Path) -> None:
    original = tmp_path / "original"
    original.write_bytes(b"content")
    hardlink = tmp_path / "hardlink"
    os.link(original, hardlink)
    with pytest.raises(witness.WitnessValidationError, match="single-link"):
        witness._read_authenticated_file(original, "fixture", 100)
    hardlink.unlink()
    symlink = tmp_path / "symlink"
    try:
        symlink.symlink_to(original)
    except OSError:
        pytest.skip("symlinks unavailable")
    with pytest.raises(witness.WitnessValidationError, match="single-link"):
        witness._read_authenticated_file(symlink, "fixture", 100)


def test_verified_digest_input_is_rehashed_before_green_output(tmp_path: Path) -> None:
    path = tmp_path / "image.tar"
    path.write_bytes(b"oci")
    metadata = path.stat()
    captured = witness._capture_verified_digest_file(
        path,
        expected_bytes=3,
        expected_sha256=hashlib.sha256(b"oci").hexdigest(),
        context="image",
    )
    witness._revalidate_authenticated(captured, "image")

    path.write_bytes(b"bad")
    os.utime(path, ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
    with pytest.raises(
        witness.WitnessValidationError, match="changed after validation"
    ):
        witness._revalidate_authenticated(captured, "image")


def test_candidate_object_authority_is_delegated_to_scope_snapshot() -> None:
    source = Path(witness.__file__).read_text(encoding="utf-8")
    assert "def _candidate_snapshot" not in source
    assert '"ls-tree"' not in source
    assert '"merge-base"' not in source
    assert '"rev-list"' not in source
    assert "candidate_scope.validate(candidate_sha, manifest)" in source
    assert 'scope["derived_inventory"]' in source


def test_deterministic_tar_is_sorted_regular_0644_without_pax(tmp_path: Path) -> None:
    files = {"runtime/000/receipt.json": b"{}\n", "commands/000/report.json": b"{}\n"}
    first = tmp_path / "first.tar"
    second = tmp_path / "second.tar"
    assert witness._deterministic_tar(first, files) == witness._deterministic_tar(
        second, files
    )
    assert first.read_bytes() == second.read_bytes()
    assert b"././@LongLink" not in first.read_bytes()
    assert b"PaxHeader" not in first.read_bytes()
    with tarfile.open(first, "r:") as archive:
        members = archive.getmembers()
        assert [member.name for member in members] == sorted(files)
        assert all(member.isfile() and member.mode == 0o644 for member in members)
        assert all(not member.pax_headers for member in members)
        assert all(
            member.uid == member.gid == 0 and member.mtime == 0 for member in members
        )
    raw = first.read_bytes()
    assert len(raw) % (20 * 512) == 0
    offset = 0
    raw_names: list[str] = []
    while raw[offset : offset + 512] != bytes(512):
        header = raw[offset : offset + 512]
        assert len(header) == 512
        assert header[257:263] == b"ustar\x00"
        assert header[156:157] in {b"0", b"\x00"}
        assert int(header[100:108].rstrip(b"\x00 ") or b"0", 8) == 0o644
        assert int(header[108:116].rstrip(b"\x00 ") or b"0", 8) == 0
        assert int(header[116:124].rstrip(b"\x00 ") or b"0", 8) == 0
        assert int(header[136:148].rstrip(b"\x00 ") or b"0", 8) == 0
        assert header[157:257].rstrip(b"\x00") == b""
        assert header[345:500].rstrip(b"\x00") == b""
        name = header[:100].split(b"\x00", 1)[0].decode("ascii")
        raw_names.append(name)
        size = int(header[124:136].rstrip(b"\x00 ") or b"0", 8)
        offset += 512 + ((size + 511) // 512) * 512
    assert raw_names == sorted(files)
    assert raw[offset : offset + 1024] == bytes(1024)
    assert raw[offset:] == bytes(len(raw) - offset)
    with pytest.raises(witness.WitnessValidationError, match="credentials"):
        witness._deterministic_tar(
            tmp_path / "credential.tar", {"report.json": b'{"password":"secret"}'}
        )
    with pytest.raises(witness.WitnessValidationError, match="short-name"):
        witness._deterministic_tar(
            tmp_path / "long-name.tar", {f"runtime/{'x' * 100}.json": b"{}"}
        )


def test_junit_archive_uses_short_names_and_report_keeps_exact_mapping() -> None:
    source = Path(witness.__file__).read_text(encoding="utf-8")
    assert 'f"commands/{directory_name}/junit/{report_order:03d}.xml"' in source
    assert '"archive_path": artifact_spec["archive_path"]' in source
    assert '"filename": filename' in source
    assert '"member_path": archive_path' in source
    assert "tarfile.USTAR_FORMAT" in source
    assert "tarfile.GNU_FORMAT" not in source


def test_failure_after_output_creation_seals_fail_tar_and_returns_nonzero(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    candidate = (tmp_path / "candidate").resolve()
    raw = (tmp_path / "raw").resolve()
    candidate.mkdir()
    raw.mkdir()
    output = (tmp_path / "output").resolve()
    monkeypatch.setattr(
        witness,
        "_build_result",
        lambda **kwargs: (_ for _ in ()).throw(
            witness.WitnessValidationError("SOURCES_NOT_GREEN", "test failed")
        ),
    )
    status, exit_code = witness.aggregate_witness(
        candidate_dir=candidate,
        candidate_sha=SHA_A,
        raw_artifacts_dir=raw,
        output_dir=output,
        attempt_id="github-123-1",
        trusted_code_sha=SHA_B,
        trusted_workflow_sha=SHA_B,
        trusted_workflow_ref=(
            f"{witness.FIXED_REPOSITORY}/{witness.TRUSTED_WORKFLOW_PATH}@{SHA_B}"
        ),
        trusted_workflow_repository=witness.FIXED_REPOSITORY,
        trusted_workflow_file_path=witness.TRUSTED_WORKFLOW_PATH,
    )
    assert exit_code != 0
    assert status["state"] == witness.FAIL
    assert (output / witness.ARCHIVE_NAME).is_file()
    with tarfile.open(output / witness.ARCHIVE_NAME, "r:") as archive:
        failure = json.load(archive.extractfile("manifest.json"))
    assert failure["state"] == witness.FAIL
    assert failure["authority"]["authenticated_checkpoint"] is False


def test_success_manifest_exactly_indexes_non_manifest_members() -> None:
    members = {
        "commands/000-wave_a_static/report.json": b"{}\n",
        "commands/000-wave_a_static/junit/junit.xml": b"<testsuite/>\n",
        "runtime/000-wave_a_static/receipt.json": b"{}\n",
    }
    index = [
        {
            "bytes": len(payload),
            "path": path,
            "sha256": hashlib.sha256(payload).hexdigest(),
        }
        for path, payload in sorted(members.items())
    ]
    manifest = {
        "accepted_a8_sha": witness.ACCEPTED_A8,
        "authority_ceiling": witness.AUTHORITY,
        "caller_workflow_binding": {
            "file_sha256": DIGEST_A,
            "git_blob_sha1": SHA_A,
            "mode": "100644",
            "path": witness.CALLER_WORKFLOW_PATH,
            "trusted_workflow_sha": SHA_B,
        },
        "caller_workflow_path": witness.CALLER_WORKFLOW_PATH,
        "caller_workflow_ref": witness.CALLER_WORKFLOW_REF,
        "caller_workflow_sha": SHA_A,
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
        "command_artifact_set_sha256": witness._canonical_sha256(index),
        "command_contract_payload_sha256": DIGEST_A,
        "member_index": index,
        "schema_version": witness.WITNESS_SCHEMA_VERSION,
        "scope_inventory_sha256": DIGEST_B,
        "sources_status": {
            "candidate_scope": "PASS",
            "command_contract": "PASS",
            "command_execution": "PASS",
            "runtime_supply_chain": "PASS",
        },
        "trusted_code_sha": SHA_B,
        "trusted_code_tree_sha": SHA_A,
        "trusted_transition": _trusted_transition(),
        "trusted_transition_sha256": _trusted_transition_sha256(),
        "trusted_workflow_file_path": witness.TRUSTED_WORKFLOW_PATH,
        "trusted_workflow_ref": (
            f"{witness.FIXED_REPOSITORY}/{witness.TRUSTED_WORKFLOW_PATH}@{SHA_B}"
        ),
        "trusted_workflow_repository": witness.FIXED_REPOSITORY,
        "trusted_workflow_sha": SHA_B,
        "trusted_workflow_tree_sha": SHA_A,
    }
    assert set(manifest) == {
        "accepted_a8_sha",
        "authority_ceiling",
        "caller_workflow_binding",
        "caller_workflow_path",
        "caller_workflow_ref",
        "caller_workflow_sha",
        "candidate_sha",
        "candidate_tree_sha",
        "command_artifact_set_sha256",
        "command_contract_payload_sha256",
        "member_index",
        "schema_version",
        "scope_inventory_sha256",
        "sources_status",
        "trusted_code_sha",
        "trusted_code_tree_sha",
        "trusted_transition",
        "trusted_transition_sha256",
        "trusted_workflow_file_path",
        "trusted_workflow_ref",
        "trusted_workflow_repository",
        "trusted_workflow_sha",
        "trusted_workflow_tree_sha",
    }
    assert manifest["command_artifact_set_sha256"] == witness._canonical_sha256(
        manifest["member_index"]
    )
