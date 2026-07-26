from __future__ import annotations

import copy
import io
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import time
from pathlib import Path

import pytest

from scripts.phase8.candidate import github_command_runner as runner


ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = ROOT / "scripts" / "phase8" / "candidate" / "github_command_runner.py"
SHA_A = "a" * 40
SHA_B = "b" * 40


def _github_environment(command_id: str = "wave_a_java") -> dict[str, str]:
    return {
        "GITHUB_ACTIONS": "true",
        "GITHUB_JOB": f"phase8_{command_id}",
        "GITHUB_REPOSITORY": runner.FIXED_REPOSITORY,
        "GITHUB_REPOSITORY_ID": runner.FIXED_REPOSITORY_ID,
        "GITHUB_RUN_ATTEMPT": "1",
        "GITHUB_RUN_ID": "123456",
        "GITHUB_SERVER_URL": "https://github.com",
        "GITHUB_SHA": SHA_A,
        "GITHUB_WORKFLOW_REF": (
            f"{runner.FIXED_REPOSITORY}/{runner.CALLER_WORKFLOW_PATH}@"
            f"{runner.FIXED_BRANCH}"
        ),
        "GITHUB_WORKFLOW_SHA": SHA_A,
        "RUNNER_ARCH": "X64",
        "RUNNER_ENVIRONMENT": "github-hosted",
        "RUNNER_OS": "Linux",
    }


def _identity(monkeypatch: pytest.MonkeyPatch, command_id: str = "wave_a_java"):
    for key, value in _github_environment(command_id).items():
        monkeypatch.setenv(key, value)
    return runner._github_identity(
        expected_job=f"phase8_{command_id}",
        candidate_sha=SHA_A,
        trusted_code_sha=SHA_B,
        trusted_workflow_sha=SHA_B,
        trusted_workflow_ref=(
            f"{runner.FIXED_REPOSITORY}/{runner.TRUSTED_WORKFLOW_PATH}@{SHA_B}"
        ),
        trusted_workflow_repository=runner.FIXED_REPOSITORY,
        trusted_workflow_file_path=runner.TRUSTED_WORKFLOW_PATH,
    )


def test_github_identity_separates_caller_workflow_code_and_repository(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    identity = _identity(monkeypatch)
    assert identity.values["workflow_sha"] == SHA_A
    assert identity.values["job_workflow_sha"] == SHA_B
    assert identity.values["trusted_code_sha"] == SHA_B
    assert identity.values["repository_id"] == "1282437633"
    assert identity.attempt_id == "github-123456-1"


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("GITHUB_REPOSITORY", "fork/AfterSaleFlow-Agent"),
        ("GITHUB_REPOSITORY_ID", "999"),
        ("GITHUB_WORKFLOW_SHA", SHA_B),
        ("GITHUB_WORKFLOW_REF", "bad/ref"),
        ("GITHUB_JOB", "phase8_wave_b_java_unit"),
        ("RUNNER_ENVIRONMENT", "self-hosted"),
        ("RUNNER_OS", "Windows"),
    ],
)
def test_github_identity_drift_fails_closed(
    monkeypatch: pytest.MonkeyPatch, key: str, value: str
) -> None:
    environment = _github_environment()
    environment[key] = value
    for name, observed in environment.items():
        monkeypatch.setenv(name, observed)
    with pytest.raises(runner.CommandRunnerError, match="GitHub"):
        runner._github_identity(
            expected_job="phase8_wave_a_java",
            candidate_sha=SHA_A,
            trusted_code_sha=SHA_B,
            trusted_workflow_sha=SHA_B,
            trusted_workflow_ref=(
                f"{runner.FIXED_REPOSITORY}/{runner.TRUSTED_WORKFLOW_PATH}@{SHA_B}"
            ),
            trusted_workflow_repository=runner.FIXED_REPOSITORY,
            trusted_workflow_file_path=runner.TRUSTED_WORKFLOW_PATH,
        )


def test_job_workflow_context_cannot_be_substituted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for key, value in _github_environment().items():
        monkeypatch.setenv(key, value)
    with pytest.raises(runner.CommandRunnerError, match="job_workflow_ref"):
        runner._github_identity(
            expected_job="phase8_wave_a_java",
            candidate_sha=SHA_A,
            trusted_code_sha=SHA_B,
            trusted_workflow_sha=SHA_B,
            trusted_workflow_ref=(
                f"{runner.FIXED_REPOSITORY}/{runner.TRUSTED_WORKFLOW_PATH}@main"
            ),
            trusted_workflow_repository=runner.FIXED_REPOSITORY,
            trusted_workflow_file_path=runner.TRUSTED_WORKFLOW_PATH,
        )


def test_candidate_environment_does_not_inherit_credentials_or_oidc(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("GITHUB_TOKEN", "must-not-pass")
    monkeypatch.setenv("SOME_PASSWORD", "must-not-pass")
    monkeypatch.delenv("ACTIONS_ID_TOKEN_REQUEST_URL", raising=False)
    monkeypatch.delenv("ACTIONS_ID_TOKEN_REQUEST_TOKEN", raising=False)
    command = {"environment": {"CI": "1", "MAVEN_OPTS": "-Djava.awt.headless=true"}}
    environment = runner._candidate_environment(command, tmp_path)
    assert environment["CI"] == "1"
    assert environment["MAVEN_OPTS"] == "-Djava.awt.headless=true"
    assert "GITHUB_TOKEN" not in environment
    assert "SOME_PASSWORD" not in environment
    assert all(runner.FORBIDDEN_ENV_KEY.search(key) is None for key in environment)


def test_candidate_environment_rejects_oidc_capability(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("ACTIONS_ID_TOKEN_REQUEST_URL", "https://example.invalid")
    with pytest.raises(runner.CommandRunnerError, match="OIDC"):
        runner._candidate_environment({"environment": {"CI": "1"}}, tmp_path)


def test_stable_file_rejects_symlink_and_hardlink(tmp_path: Path) -> None:
    source = tmp_path / "source.json"
    source.write_text("{}", encoding="ascii")
    hardlink = tmp_path / "hardlink.json"
    os.link(source, hardlink)
    with pytest.raises(runner.CommandRunnerError, match="linked"):
        runner._read_stable_file(source, max_bytes=100, context="source")
    hardlink.unlink()
    symlink = tmp_path / "symlink.json"
    try:
        symlink.symlink_to(source)
    except OSError:
        pytest.skip("symlink creation is unavailable")
    with pytest.raises(runner.CommandRunnerError, match="linked"):
        runner._read_stable_file(symlink, max_bytes=100, context="symlink")


@pytest.mark.parametrize(
    "path",
    ["../escape", "/absolute", "a\\b", "C:ads", ".git/config", "NUL", "a//b"],
)
def test_unsafe_materialization_paths_are_rejected(path: str) -> None:
    with pytest.raises(runner.CommandRunnerError):
        runner._safe_relative_path(path, "test path")


def test_bounded_process_has_no_shell_and_enforces_timeout(tmp_path: Path) -> None:
    result = runner._run_bounded(
        [sys.executable, "-c", "import time; time.sleep(2)"],
        cwd=tmp_path,
        env={"PATH": os.environ.get("PATH", "")},
        timeout_seconds=1,
    )
    assert result.timed_out is True
    assert result.exit_code == 124


def test_bounded_process_rejects_credential_environment(tmp_path: Path) -> None:
    with pytest.raises(runner.CommandRunnerError, match="credential"):
        runner._run_bounded(
            [sys.executable, "-c", "pass"],
            cwd=tmp_path,
            env={"API_TOKEN": "secret"},
            timeout_seconds=1,
        )


def _junit(*, body: str = "", tests: int = 1) -> bytes:
    return (
        f'<testsuite name="suite" tests="{tests}" failures="0" errors="0" skipped="0">'
        f'<testcase classname="example.Case" name="works">{body}</testcase>'
        "</testsuite>"
    ).encode("ascii")


def _write_report_archive(
    path: Path,
    entries: list[tuple[str, bytes, bytes]],
    *,
    include_root: bool = True,
) -> runner.HashedFile:
    with tarfile.open(path, "w", format=tarfile.USTAR_FORMAT) as archive:
        if include_root:
            root = tarfile.TarInfo(".")
            root.type = tarfile.DIRTYPE
            root.mode = 0o700
            root.mtime = 0
            archive.addfile(root)
        for name, payload, entry_type in entries:
            member = tarfile.TarInfo(name)
            member.type = entry_type
            member.mode = 0o600
            member.mtime = 0
            if entry_type == tarfile.REGTYPE:
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))
            else:
                if entry_type == tarfile.SYMTYPE:
                    member.linkname = "target.xml"
                archive.addfile(member)
    return runner._hash_stable_file(
        path,
        max_bytes=runner.MAX_REPORT_STREAM_BYTES,
        context="test report archive",
    )


def _mock_report_stream(
    monkeypatch: pytest.MonkeyPatch,
    entries: list[tuple[str, bytes, bytes]],
) -> None:
    def stream(**kwargs):
        report_aliases = tuple(kwargs.get("report_aliases", ()))
        aliases = dict(report_aliases)
        quarantined = {
            alias: runner._report_quarantine_name(index, alias)
            for index, (_logical, alias) in enumerate(report_aliases)
        }
        transported = []
        for name, payload, entry_type in entries:
            filename = name[2:] if name.startswith("./") else name
            alias = quarantined.get(filename, aliases.get(filename, filename))
            transported_name = f"./{alias}" if name.startswith("./") else alias
            transported.append((transported_name, payload, entry_type))
        return _write_report_archive(kwargs["archive_path"], transported)

    monkeypatch.setattr(runner, "_stream_container_report_archive", stream)


def test_junit_is_derived_from_testcases_not_forged_summary_attributes() -> None:
    facts = runner._parse_junit(_junit(tests=999), "report")
    assert facts.tests == 1
    assert facts.testcase_ids == ("example.Case::works",)


@pytest.mark.parametrize(
    "payload",
    [
        b'<!DOCTYPE x [<!ENTITY x "bad">]><testsuite/>',
        _junit(body="<failure>authorization=secret-value</failure>"),
        (
            b'<testsuite name="suite">'
            b'<testcase classname="example.Case" name="same"/>'
            b'<testcase classname="example.Case" name="same"/>'
            b"</testsuite>"
        ),
    ],
)
def test_forged_or_sensitive_junit_is_rejected(payload: bytes) -> None:
    with pytest.raises(runner.CommandRunnerError):
        runner._parse_junit(payload, "report")


def test_junit_rejects_late_doctype() -> None:
    payload = b" " * 8192 + b'<!DOCTYPE x><testsuite name="suite"/>'
    with pytest.raises(runner.CommandRunnerError, match="XML entities"):
        runner._parse_junit(payload, "late-doctype")


def test_junit_rejects_excessive_depth() -> None:
    depth = runner.MAX_JUNIT_XML_DEPTH + 1
    payload = (
        b'<testsuite name="suite">'
        + b"<nested>" * depth
        + b'<testcase classname="example.Case" name="works"/>'
        + b"</nested>" * depth
        + b"</testsuite>"
    )
    with pytest.raises(runner.CommandRunnerError, match="complexity budget"):
        runner._parse_junit(payload, "deep-junit")


def test_output_summary_redacts_secret_shaped_output() -> None:
    summary = runner._output_summary(b"authorization=secret-value")
    assert summary["summary"] == "sensitive output suppressed"
    assert summary["bytes"] == 26


def test_cli_rejects_arbitrary_command_id() -> None:
    parser = runner._parser()
    with pytest.raises(SystemExit):
        parser.parse_args(
            [
                "execute-command",
                "--candidate-sha",
                SHA_A,
                "--trusted-code-sha",
                SHA_B,
                "--trusted-workflow-sha",
                SHA_B,
                "--trusted-workflow-ref",
                "ref",
                "--trusted-workflow-repository",
                runner.FIXED_REPOSITORY,
                "--trusted-workflow-file-path",
                runner.TRUSTED_WORKFLOW_PATH,
                "--output-dir",
                "/tmp/output",
                "--command-id",
                "arbitrary-command",
            ]
        )


def test_source_has_no_aggregator_import_shell_eval_or_network_fetch() -> None:
    source = RUNNER_PATH.read_text(encoding="ascii")
    assert "import github_witness" not in source
    assert "from scripts.phase8.candidate import github_witness" not in source
    assert "shell=True" not in source
    assert re_search_tokens(source, ("eval(", "exec(", "curl ", "wget ")) == []


def re_search_tokens(source: str, tokens: tuple[str, ...]) -> list[str]:
    return [token for token in tokens if token in source]


def test_materialization_receipt_mutation_is_detectable() -> None:
    receipt = {
        "accepted_a8": runner.candidate_scope.ACCEPTED_A8,
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
        "closure_kind": runner.candidate_scope.FULL_REPOSITORY,
        "created_nonce": "c" * 64,
        "exact_git_blobs": True,
        "external_to_all_worktrees": True,
        "hardlink_alias": False,
        "manifest_file_count": 1,
        "manifest_sha256": "d" * 64,
        "manifest_total_bytes": 1,
        "no_follow": True,
        "receipt_kind": runner.runtime_policy.MATERIALIZATION_RECEIPT_KIND,
        "receipt_sha256": "",
        "reparse_point": False,
        "root_identity": [1, 2, 3, 1, 0, 4, 0],
        "root_path": "/tmp/materialized",
        "schema_version": runner.runtime_policy.MATERIALIZATION_RECEIPT_SCHEMA_VERSION,
        "scope_inventory_sha256": "e" * 64,
        "symlink": False,
        "verified_nonce": "f" * 64,
    }
    receipt["receipt_sha256"] = runner.runtime_policy.canonical_receipt_sha256(receipt)
    mutated = copy.deepcopy(receipt)
    mutated["candidate_sha"] = SHA_B
    assert mutated["receipt_sha256"] != runner.runtime_policy.canonical_receipt_sha256(
        mutated
    )


def test_execute_command_forbids_runtime_inputs_for_maven(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(runner, "_github_identity", lambda **_: object())
    monkeypatch.setattr(runner, "_trusted_snapshot", lambda _: (SHA_B, SHA_B))
    monkeypatch.setattr(
        runner,
        "_candidate_snapshot",
        lambda _sha, **_kwargs: runner.CandidateSnapshot(
            SHA_A, SHA_B, "c" * 64, {}, {}
        ),
    )
    monkeypatch.setattr(
        runner, "_load_command", lambda _: ({"id": "wave_a_java"}, 1, "d" * 64)
    )
    monkeypatch.setattr(runner, "_fresh_output_directory", lambda path: path)
    monkeypatch.setattr(
        runner, "_materialize_candidate", lambda *args, **kwargs: object()
    )
    with pytest.raises(runner.CommandRunnerError, match="static runtime inputs"):
        runner.execute_command(
            command_id="wave_a_java",
            candidate_sha=SHA_A,
            trusted_code_sha=SHA_B,
            trusted_workflow_sha=SHA_B,
            trusted_workflow_ref="ref",
            trusted_workflow_repository=runner.FIXED_REPOSITORY,
            trusted_workflow_file_path=runner.TRUSTED_WORKFLOW_PATH,
            output_dir=tmp_path / "out",
            image_archive=tmp_path / "image.tar",
        )


def test_static_command_requires_all_observed_runtime_inputs(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(runner, "_github_identity", lambda **_: object())
    monkeypatch.setattr(runner, "_trusted_snapshot", lambda _: (SHA_B, SHA_B))
    monkeypatch.setattr(
        runner,
        "_candidate_snapshot",
        lambda _sha, **_kwargs: runner.CandidateSnapshot(
            SHA_A, SHA_B, "c" * 64, {}, {}
        ),
    )
    monkeypatch.setattr(
        runner, "_load_command", lambda _: ({"id": "wave_a_static"}, 0, "d" * 64)
    )
    monkeypatch.setattr(runner, "_fresh_output_directory", lambda path: path)
    monkeypatch.setattr(
        runner, "_materialize_candidate", lambda *args, **kwargs: object()
    )
    with pytest.raises(runner.CommandRunnerError, match="lacks observed runtime"):
        runner.execute_command(
            command_id="wave_a_static",
            candidate_sha=SHA_A,
            trusted_code_sha=SHA_B,
            trusted_workflow_sha=SHA_B,
            trusted_workflow_ref="ref",
            trusted_workflow_repository=runner.FIXED_REPOSITORY,
            trusted_workflow_file_path=runner.TRUSTED_WORKFLOW_PATH,
            output_dir=tmp_path / "out",
        )


def test_git_control_environment_disables_lazy_fetch_and_all_protocols() -> None:
    environment = runner._minimal_control_environment()
    assert environment["GIT_NO_LAZY_FETCH"] == "1"
    assert environment["GIT_CONFIG_COUNT"] == "1"
    assert environment["GIT_CONFIG_KEY_0"] == "protocol.allow"
    assert environment["GIT_CONFIG_VALUE_0"] == "never"
    assert environment["GIT_NO_REPLACE_OBJECTS"] == "1"


def test_network_tool_environment_rejects_oidc_capability(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("ACTIONS_ID_TOKEN_REQUEST_URL", "https://example.invalid")
    with pytest.raises(runner.CommandRunnerError, match="OIDC"):
        runner._network_tool_environment(tmp_path)


def test_ephemeral_buildkit_builder_uses_private_pinned_lifecycle(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    docker = Path("/usr/bin/docker")
    environment = {"DOCKER_CONFIG": str(tmp_path / "docker-config")}
    calls: list[tuple[list[str], Path, dict[str, str]]] = []

    def run_bounded(argv, *, cwd, env, timeout_seconds):
        assert timeout_seconds in {120, 600}
        calls.append((list(argv), cwd, dict(env)))
        return runner.ProcessResult(0, False, False, b"", b"")

    monkeypatch.setattr(runner, "_run_bounded", run_bounded)
    with runner._ephemeral_buildkit_builder(
        docker=docker, cwd=tmp_path, environment=environment
    ) as builder_name:
        assert builder_name.startswith("phase8-buildkit-")

    assert calls == [
        (
            [
                str(docker),
                "image",
                "pull",
                "--platform=linux/amd64",
                runner.runtime_policy.BUILDKIT_IMAGE,
            ],
            tmp_path,
            environment,
        ),
        (
            [
                str(docker),
                "buildx",
                "create",
                "--name",
                builder_name,
                f"--driver={runner.runtime_policy.BUILDX_DRIVER}",
                f"--driver-opt=image={runner.runtime_policy.BUILDKIT_IMAGE}",
                "--platform=linux/amd64",
                "--bootstrap",
            ],
            tmp_path,
            environment,
        ),
        (
            [str(docker), "buildx", "rm", "--force", builder_name],
            tmp_path,
            environment,
        ),
    ]
    assert "--use" not in calls[1][0]


def test_ephemeral_buildkit_builder_cleanup_preserves_primary_failure(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[list[str]] = []

    def run_bounded(argv, **_kwargs):
        values = list(argv)
        calls.append(values)
        if values[1:3] == ["buildx", "rm"]:
            return runner.ProcessResult(1, False, False, b"secret", b"credential")
        return runner.ProcessResult(0, False, False, b"", b"")

    monkeypatch.setattr(runner, "_run_bounded", run_bounded)
    with pytest.raises(RuntimeError, match="primary failure"):
        with runner._ephemeral_buildkit_builder(
            docker=Path("/usr/bin/docker"), cwd=tmp_path, environment={}
        ):
            raise RuntimeError("primary failure")
    assert calls[-1][1:4] == ["buildx", "rm", "--force"]


def test_ephemeral_buildkit_builder_cleanup_failure_is_redacted(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    def run_bounded(argv, **_kwargs):
        values = list(argv)
        if values[1:3] == ["buildx", "rm"]:
            return runner.ProcessResult(1, False, False, b"secret", b"credential")
        return runner.ProcessResult(0, False, False, b"", b"")

    monkeypatch.setattr(runner, "_run_bounded", run_bounded)
    with pytest.raises(runner.CommandRunnerError) as captured:
        with runner._ephemeral_buildkit_builder(
            docker=Path("/usr/bin/docker"), cwd=tmp_path, environment={}
        ):
            pass
    assert captured.value.code == "BUILDER_CLEANUP_FAILED"
    assert "secret" not in str(captured.value)
    assert "credential" not in str(captured.value)


@pytest.mark.parametrize(
    ("failure_index", "expected_code"),
    [(0, "BUILDKIT_IMAGE_PULL_FAILED"), (1, "BUILDER_BOOTSTRAP_FAILED")],
)
def test_ephemeral_buildkit_builder_stage_failures_are_stable_and_redacted(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    failure_index: int,
    expected_code: str,
) -> None:
    call_index = 0

    def run_bounded(_argv, **_kwargs):
        nonlocal call_index
        result = (
            runner.ProcessResult(1, False, False, b"access-token", b"password")
            if call_index == failure_index
            else runner.ProcessResult(0, False, False, b"", b"")
        )
        call_index += 1
        return result

    monkeypatch.setattr(runner, "_run_bounded", run_bounded)
    with pytest.raises(runner.CommandRunnerError) as captured:
        with runner._ephemeral_buildkit_builder(
            docker=Path("/usr/bin/docker"), cwd=tmp_path, environment={}
        ):
            pass
    assert captured.value.code == expected_code
    assert "access-token" not in str(captured.value)
    assert "password" not in str(captured.value)


def test_docker_execution_archive_load_failure_is_stable_and_redacted(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    archive = tmp_path / "execution.tar"
    archive.write_bytes(b"docker-archive")
    monkeypatch.setattr(
        runner, "_resolve_executable", lambda _: Path("/usr/bin/docker")
    )
    monkeypatch.setattr(runner, "_docker_environment", lambda _: {})
    monkeypatch.setattr(
        runner,
        "_run_bounded",
        lambda *_args, **_kwargs: runner.ProcessResult(
            1, False, False, b"access-token", b"password"
        ),
    )
    with pytest.raises(runner.CommandRunnerError) as captured:
        runner._docker_load_and_inspect(archive, expected_image_id=None, home=tmp_path)
    assert captured.value.code == "IMAGE_LOAD_FAILED"
    assert "access-token" not in str(captured.value)
    assert "password" not in str(captured.value)


def test_static_execution_emits_only_receipt_refs_and_fixed_docker_dispatch(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    identity = _identity(monkeypatch, "wave_a_static")
    output_dir = tmp_path / "output"
    output_dir.mkdir()
    home = tmp_path / "home"
    home.mkdir()
    candidate_archive_path = tmp_path / "candidate.tar"
    candidate_archive_path.write_bytes(b"candidate-archive")
    image_archive = tmp_path / "image.tar"
    image_archive.write_bytes(b"immutable-oci")
    execution_image_archive = tmp_path / "execution-image.tar"
    execution_image_archive.write_bytes(b"immutable-docker")
    build_path = tmp_path / "runtime-build-receipt.json"
    build_path.write_text('{"receipt":"build"}', encoding="ascii")
    observation_path = tmp_path / "build-observation.json"
    observation_path.write_text('{"receipt":"observation"}', encoding="ascii")
    observer_image_archive = tmp_path / "observer-image.tar"
    observer_image_archive.write_bytes(b"independent-oci")
    observer_execution_image_archive = tmp_path / "observer-execution-image.tar"
    observer_execution_image_archive.write_bytes(b"independent-docker")
    producer_root = tmp_path / "producer"
    wheelhouse_root = producer_root / "wheelhouse"
    wheelhouse_root.mkdir(parents=True)
    (producer_root / runner.WHEELHOUSE_MANIFEST_NAME).write_text("[]", encoding="ascii")

    command = {
        "argv": ["/usr/local/bin/python", "-m", "pytest", "-q"],
        "cwd": ".",
        "id": "wave_a_static",
        "report": {
            "expected_artifacts": [
                {
                    "archive_path": "p/000-wave_a_static-junit.xml",
                    "filename": "wave_a_static.xml",
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "/tmp/phase8-artifacts/wave_a_static.xml",
            "source_root": "/tmp/phase8-artifacts",
        },
        "timeout_seconds": 30,
    }
    manifest_receipt = {
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
        "manifest_sha256": "1" * 64,
        "receipt_sha256": "2" * 64,
    }
    candidate_binding = {
        "accepted_entry_sha": runner.candidate_scope.ACCEPTED_A8,
        "candidate_archive_bytes": len(b"candidate-archive"),
        "candidate_archive_entry_count": 0,
        "candidate_archive_format": runner.runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
        "candidate_archive_sha256": runner._sha256(b"candidate-archive"),
        "candidate_sha": SHA_A,
        "candidate_tree_sha": SHA_B,
        "closure_kind": runner.candidate_scope.FULL_REPOSITORY,
        "derived_inventory_sha256": "3" * 64,
        "manifest_file_count": 1,
        "manifest_sha256": "1" * 64,
        "manifest_total_bytes": 1,
    }
    materialization = runner.Materialization(
        [],
        manifest_receipt,
        candidate_binding,
        {"inventory": "expected"},
        runner._runtime_run_binding(identity),
        runner._hash_stable_file(
            candidate_archive_path,
            max_bytes=1024,
            context="candidate archive",
        ),
    )
    producer_identity = runner._runtime_job_identity(identity)
    builder_identity = dict(producer_identity, job_name=runner.BUILD_JOB)
    observer_identity = dict(producer_identity, job_name=runner.OBSERVE_JOB)
    image_id = f"sha256:{'4' * 64}"
    projection = {"image_id": image_id, "projection": "observed"}
    build_receipt = {
        "builder_job_identity": builder_identity,
        "code_sha": SHA_A,
        "code_tree_sha": SHA_B,
        "image_id": image_id,
        "docker_archive_sha256": runner._sha256(b"immutable-docker"),
        "oci_archive_sha256": runner._sha256(b"immutable-oci"),
        "receipt_sha256": "5" * 64,
    }
    build_binding = {
        "oci_archive_sha256": build_receipt["oci_archive_sha256"],
        "binding": "independently-observed",
    }
    observation = {
        "base_image_inspect_projection": {"image_id": "base"},
        "base_image_inspect_projection_sha256": "0" * 64,
        "build_provenance": {"source": "producer"},
        "build_provenance_sha256": "6" * 64,
        "observer_build_parameters": {},
        "observer_build_parameters_sha256": "a" * 64,
        "observer_image_inspect_projection": projection,
        "observer_image_inspect_projection_sha256": "b" * 64,
        "observer_job_identity": observer_identity,
        "observer_job_identity_sha256": runner.runtime_policy.canonical_sha256(
            observer_identity
        ),
        "observer_docker_archive_bytes": len(b"independent-docker"),
        "observer_docker_archive_sha256": runner._sha256(b"independent-docker"),
        "observer_oci_archive_bytes": len(b"independent-oci"),
        "observer_oci_archive_sha256": runner._sha256(b"independent-oci"),
        "producer_image_inspect_projection": projection,
        "producer_image_inspect_projection_sha256": "7" * 64,
        "producer_docker_archive_bytes": len(b"immutable-docker"),
        "producer_docker_archive_sha256": runner._sha256(b"immutable-docker"),
        "producer_oci_archive_bytes": len(b"immutable-oci"),
        "producer_oci_archive_sha256": build_receipt["oci_archive_sha256"],
        "receipt_sha256": "8" * 64,
        "source_build_receipt_sha256": build_receipt["receipt_sha256"],
        "wheelhouse_manifest": [],
        "wheelhouse_manifest_sha256": "9" * 64,
    }
    parsed = iter((build_receipt, observation))
    monkeypatch.setattr(
        runner.runtime_policy, "parse_receipt_json_bytes", lambda _: next(parsed)
    )
    monkeypatch.setattr(
        runner.runtime_policy,
        "assert_materialization_authorized_live",
        lambda *args, **kwargs: (
            manifest_receipt,
            candidate_binding,
            candidate_archive_path.open("rb"),
        ),
    )
    monkeypatch.setattr(
        runner.runtime_policy,
        "validate_runtime_build_receipt",
        lambda *args, **kwargs: (
            build_receipt,
            build_binding,
            observation,
        ),
    )
    monkeypatch.setattr(
        runner.runtime_policy,
        "verify_shared_runtime_receipts",
        lambda *args, **kwargs: object(),
    )
    monkeypatch.setattr(
        runner.command_contract,
        "load_command_contract",
        lambda: {"validated": "command-contract"},
    )
    captured_dispatch: dict[str, object] = {}

    def authorize(_command, dispatch, _policy, **_kwargs):
        captured_dispatch.update(dispatch)
        return "a" * 64, candidate_archive_path.open("rb")

    monkeypatch.setattr(
        runner.runtime_policy, "assert_static_dispatch_authorized", authorize
    )
    loaded_archives: list[Path] = []

    def load_and_inspect(archive, **_kwargs):
        loaded_archives.append(archive)
        return projection

    monkeypatch.setattr(runner, "_docker_load_and_inspect", load_and_inspect)
    monkeypatch.setattr(
        runner, "_resolve_executable", lambda _: Path("/usr/bin/docker")
    )
    monkeypatch.setattr(runner, "_docker_environment", lambda _: {"PATH": "/usr/bin"})
    docker_calls: list[list[str]] = []
    execution_order: list[str] = []

    def run_bounded(argv, *, cwd, env, timeout_seconds):
        del cwd, env, timeout_seconds
        values = list(argv)
        docker_calls.append(values)
        operation = values[1]
        execution_order.append(operation)
        if operation == "create":
            return runner.ProcessResult(0, False, False, f"{'b' * 64}\n".encode(), b"")
        if operation == "start":
            return runner.ProcessResult(0, False, False, b"tests passed", b"")
        return runner.ProcessResult(0, False, False, b"", b"")

    monkeypatch.setattr(runner, "_run_bounded", run_bounded)

    def seed_candidate(*_args, **_kwargs):
        execution_order.append("seed-candidate")
        return runner.ProcessResult(0, False, False, b"", b""), {}

    monkeypatch.setattr(runner, "_run_bounded_with_verified_archive", seed_candidate)

    def stream_reports(**kwargs):
        exporter_argv = [
            "/usr/bin/docker",
            "exec",
            "--workdir=/",
            "--user=0:0",
            "b" * 64,
            *runner.REPORT_EXPORTER_ARGV,
            "/tmp/phase8-artifacts",
            ".",
        ]
        docker_calls.append(exporter_argv)
        execution_order.append("report-stream")
        return _write_report_archive(
            kwargs["archive_path"],
            [("./wave_a_static.xml", _junit(), tarfile.REGTYPE)],
        )

    monkeypatch.setattr(runner, "_stream_container_report_archive", stream_reports)
    process, runtime_refs, reports, facts = runner._execute_static(
        command=command,
        identity=identity,
        snapshot=runner.CandidateSnapshot(SHA_A, SHA_B, "3" * 64, {}, {}),
        materialization=materialization,
        output_dir=output_dir,
        home=home,
        image_archive=image_archive,
        execution_image_archive=execution_image_archive,
        observer_image_archive=observer_image_archive,
        observer_execution_image_archive=observer_execution_image_archive,
        runtime_build_receipt_path=build_path,
        build_observation_receipt_path=observation_path,
        wheelhouse_root=wheelhouse_root,
    )

    assert process.exit_code == 0
    assert facts.tests == 1
    assert len(reports) == 1
    expected_runtime_keys = {
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
    assert set(runtime_refs) == expected_runtime_keys
    assert "image_archive_ref" not in runtime_refs
    assert "observer_image_archive_ref" not in runtime_refs
    assert "producer_image_archive_ref" not in runtime_refs
    assert runtime_refs["build_observation_receipt_ref"]["path"] == (
        "shared-runtime/observer/build-observation-receipt.json"
    )
    assert runtime_refs["observer_oci_archive_ref"]["path"] == (
        f"shared-runtime/observer/oci/sha256-{runner._sha256(b'independent-oci')}.tar"
    )
    assert runtime_refs["observer_docker_archive_ref"]["path"] == (
        "shared-runtime/observer/docker/"
        f"sha256-{runner._sha256(b'independent-docker')}.tar"
    )
    assert runtime_refs["producer_docker_archive_ref"]["path"] == (
        "shared-runtime/producer/docker/"
        f"sha256-{runner._sha256(b'immutable-docker')}.tar"
    )
    assert runtime_refs["producer_oci_archive_ref"]["path"] == (
        f"shared-runtime/producer/oci/sha256-{runner._sha256(b'immutable-oci')}.tar"
    )
    assert loaded_archives == [execution_image_archive]
    create_argv = captured_dispatch["create_argv"]
    assert "--network=none" in create_argv
    assert "--read-only" in create_argv
    assert "--cap-drop=ALL" in create_argv
    assert "--security-opt=no-new-privileges:true" in create_argv
    assert (
        "--tmpfs=/workspace:rw,nosuid,nodev,noexec,size=536870912,mode=0755"
        in create_argv
    )
    assert create_argv[-3:] == list(runner.runtime_policy.TRUSTED_SLEEPER_ARGV)
    candidate_copy_argv = captured_dispatch["candidate_copy_argv"]
    assert candidate_copy_argv[:7] == [
        "docker",
        "exec",
        "--interactive",
        "--user=0:0",
        runner.runtime_policy.CONTAINER_ID_TOKEN,
        "/usr/local/bin/python",
        "-c",
    ]
    assert (
        candidate_copy_argv[7]
        == runner.runtime_policy.TRUSTED_CANDIDATE_EXTRACTOR_SCRIPT
    )
    assert candidate_copy_argv[8:] == [
        str(materialization.archive.bytes),
        materialization.archive.sha256,
        str(len(materialization.manifest)),
    ]
    assert captured_dispatch["start_argv"][:2] == ["docker", "start"]
    assert captured_dispatch["exec_argv"][:3] == [
        "docker",
        "exec",
        "--workdir=/workspace",
    ]
    assert execution_order.index("start") < execution_order.index("seed-candidate")
    assert all(call[1] != "cp" for call in docker_calls)
    assert any(call[5:11] == list(runner.REPORT_EXPORTER_ARGV) for call in docker_calls)
    assert docker_calls[-1][1:3] == ["rm", "--force"]
    transport = runner._parse_json_bytes(
        (output_dir / "runtime" / "artifact-transport-receipt.json").read_bytes(),
        max_bytes=runner.MAX_JSON_BYTES,
        context="transport",
    )
    assert transport["producer_job_identity"]["job_name"] == "phase8_wave_a_static"
    assert (
        transport["artifact_payload_kind"]
        == runner.runtime_policy.ARTIFACT_PAYLOAD_KIND
    )
    raw_result = runner._write_raw_result(
        output_dir=output_dir,
        identity=identity,
        snapshot=runner.CandidateSnapshot(
            SHA_A,
            SHA_B,
            "3" * 64,
            {},
            {},
            {"candidate_sha": SHA_A},
            "e" * 64,
        ),
        command=command,
        order=0,
        materialization=materialization,
        process=process,
        reports=reports,
        facts=facts,
        runtime=runtime_refs,
        contract_payload_sha256="f" * 64,
    )
    assert set(raw_result["runtime"]) == expected_runtime_keys


def test_static_report_extraction_rejects_extra_junit_files(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": "TEST-expected.xml",
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "target/surefire-reports/TEST-*.xml",
            "source_root": "target/surefire-reports",
        }
    }

    _mock_report_stream(
        monkeypatch,
        [
            ("./TEST-expected.xml", _junit(), tarfile.REGTYPE),
            ("./TEST-extra.xml", _junit(), tarfile.REGTYPE),
        ],
    )
    with pytest.raises(runner.CommandRunnerError, match="exact artifact set"):
        runner._copy_container_reports(
            docker=Path("/usr/bin/docker"),
            docker_environment={"PATH": "/usr/bin"},
            container_id="a" * 64,
            command=command,
            source_root="/workspace/java-api-service/target/surefire-reports",
            output_dir=tmp_path / "output",
            staging_root=tmp_path / "staging",
        )


def test_maven_candidate_extractor_uses_fresh_timestamps_with_fixed_order() -> None:
    assert runner.MAVEN_CANDIDATE_EXTRACTOR_ARGV == (
        "/bin/tar",
        "--extract",
        "--file=-",
        "--directory=/workspace",
        "--no-same-owner",
        "--same-permissions",
        "--touch",
        "--no-overwrite-dir",
    )


def test_maven_candidate_extractor_preserves_payload_mode_and_hash_but_not_epoch(
    tmp_path: Path,
) -> None:
    tar = shutil.which("tar")
    if tar is None:
        pytest.skip("tar is unavailable")
    version = subprocess.run(
        [tar, "--version"],
        check=False,
        capture_output=True,
        timeout=10,
    )
    if version.returncode != 0 or b"GNU tar" not in version.stdout:
        pytest.skip("the pinned Maven extractor uses GNU tar")

    payload = b"create table phase8_mtime_proof (id bigint primary key);\n"
    relative_path = (
        "java-api-service/src/main/resources/db/migration/"
        "V040_4__room_epoch_provisioning.sql"
    )
    archive_path = tmp_path / "candidate.tar"
    with tarfile.open(archive_path, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        member = tarfile.TarInfo(relative_path)
        member.size = len(payload)
        member.mode = 0o640
        member.mtime = 0
        archive.addfile(member, io.BytesIO(payload))

    extracted_root = tmp_path / "workspace"
    extracted_root.mkdir()
    argv = [
        tar,
        *(
            f"--directory={extracted_root}"
            if value == "--directory=/workspace"
            else value
            for value in runner.MAVEN_CANDIDATE_EXTRACTOR_ARGV[1:]
        ),
    ]
    started_ns = time.time_ns()
    extracted = subprocess.run(
        argv,
        input=archive_path.read_bytes(),
        check=False,
        capture_output=True,
        timeout=10,
    )
    finished_ns = time.time_ns()

    assert extracted.returncode == 0, extracted.stderr.decode(errors="replace")
    output = extracted_root / relative_path
    assert output.read_bytes() == payload
    assert runner._sha256(output.read_bytes()) == runner._sha256(payload)
    metadata = output.stat()
    assert stat.S_IMODE(metadata.st_mode) == 0o640
    assert metadata.st_mtime_ns > 0
    assert started_ns - 2_000_000_000 <= metadata.st_mtime_ns <= finished_ns


def test_static_report_extraction_rejects_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": "expected.xml",
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "/tmp/phase8-artifacts/expected.xml",
            "source_root": "/tmp/phase8-artifacts",
        }
    }

    _mock_report_stream(monkeypatch, [("./nested", b"", tarfile.DIRTYPE)])
    with pytest.raises(runner.CommandRunnerError, match="non-regular"):
        runner._copy_container_reports(
            docker=Path("/usr/bin/docker"),
            docker_environment={"PATH": "/usr/bin"},
            container_id="a" * 64,
            command=command,
            source_root="/tmp/phase8-artifacts",
            output_dir=tmp_path / "output",
            staging_root=tmp_path / "staging",
        )


def test_static_report_extraction_rejects_symlink(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": "expected.xml",
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "/tmp/phase8-artifacts/expected.xml",
            "source_root": "/tmp/phase8-artifacts",
        }
    }

    _mock_report_stream(monkeypatch, [("./expected.xml", b"", tarfile.SYMTYPE)])
    with pytest.raises(runner.CommandRunnerError, match="non-regular"):
        runner._copy_container_reports(
            docker=Path("/usr/bin/docker"),
            docker_environment={"PATH": "/usr/bin"},
            container_id="a" * 64,
            command=command,
            source_root="/tmp/phase8-artifacts",
            output_dir=tmp_path / "output",
            staging_root=tmp_path / "staging",
        )


def test_report_export_uses_fixed_tar_argv_without_shell_or_mounts(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    captured: list[str] = []

    def run_stream(argv, **kwargs):
        captured.extend(argv)
        archive = _write_report_archive(
            kwargs["target"],
            [("./expected.xml", _junit(), tarfile.REGTYPE)],
        )
        return runner.ProcessResult(0, False, False, b"", b""), archive

    monkeypatch.setattr(runner, "_run_bounded_stdout_to_file", run_stream)
    archive = runner._stream_container_report_archive(
        docker=Path("/usr/bin/docker"),
        docker_environment={"PATH": "/usr/bin"},
        container_id="a" * 64,
        source_root="/tmp/phase8-artifacts",
        archive_path=tmp_path / "reports.ustar",
    )
    assert archive.bytes > 0
    assert captured == [
        str(Path("/usr/bin/docker")),
        "exec",
        "--workdir=/",
        "--user=0:0",
        "a" * 64,
        *runner.REPORT_EXPORTER_ARGV,
        "/tmp/phase8-artifacts",
        ".",
    ]
    assert "sh" not in captured
    assert not any("mount" in item for item in captured)


def test_report_export_adds_exact_anchored_transforms_in_contract_order(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    captured: list[str] = []
    logical_names = ("TEST-example.FirstTest.xml", "TEST-example.Second+Test.xml")
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {"filename": filename} for filename in logical_names
            ]
        }
    }
    aliases = runner._report_transport_aliases(command)

    def run_stream(argv, **kwargs):
        captured.extend(argv)
        archive = _write_report_archive(
            kwargs["target"],
            [
                (f"./{alias_name}", _junit(), tarfile.REGTYPE)
                for _logical_name, alias_name in aliases
            ],
        )
        return runner.ProcessResult(0, False, False, b"", b""), archive

    monkeypatch.setattr(runner, "_run_bounded_stdout_to_file", run_stream)
    runner._stream_container_report_archive(
        docker=Path("/usr/bin/docker"),
        docker_environment={"PATH": "/usr/bin"},
        container_id="a" * 64,
        source_root="/tmp/phase8-artifacts",
        archive_path=tmp_path / "reports.ustar",
        report_aliases=aliases,
    )

    transforms = runner._report_transform_argv(aliases)
    assert captured == [
        str(Path("/usr/bin/docker")),
        "exec",
        "--workdir=/",
        "--user=0:0",
        "a" * 64,
        *runner.REPORT_EXPORTER_ARGV[:-1],
        *transforms,
        runner.REPORT_EXPORTER_ARGV[-1],
        "/tmp/phase8-artifacts",
        ".",
    ]
    assert transforms[0].startswith(r"--transform=s|^\./phase8-junit-")
    assert transforms[2].startswith(r"--transform=s|^\./TEST-example\.FirstTest\.xml$|")
    assert "Second+Test" in transforms[3]


def test_report_transport_aliases_cover_ustar_boundaries_and_real_wave_b_name() -> None:
    logical_names = (
        "A" * 96 + ".xml",
        "B" * 97 + ".xml",
        (
            "TEST-com.example.dispute.agentstream.infrastructure.persistence."
            "AgentRunStreamRetentionManifestTest.xml"
        ),
    )
    assert [len(name.encode("utf-8")) for name in logical_names] == [100, 101, 103]
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {"filename": filename} for filename in logical_names
            ]
        }
    }

    aliases = runner._report_transport_aliases(command)

    assert aliases == runner._report_transport_aliases(command)
    assert tuple(logical for logical, _alias in aliases) == logical_names
    assert all(
        len(f"./{alias}".encode("utf-8")) <= runner.USTAR_MEMBER_NAME_BYTES
        for _logical, alias in aliases
    )
    assert len({alias.casefold() for _logical, alias in aliases}) == len(aliases)


def test_wave_b_transport_aliases_cover_all_seven_contract_reports() -> None:
    contract = runner.command_contract.load_command_contract()
    command = next(
        item for item in contract["commands"] if item["id"] == "wave_b_java_unit"
    )

    aliases = runner._report_transport_aliases(command)

    assert len(aliases) == 7
    assert tuple(logical for logical, _alias in aliases) == tuple(
        artifact["filename"] for artifact in command["report"]["expected_artifacts"]
    )
    assert any(len(logical.encode("utf-8")) == 103 for logical, _alias in aliases)
    assert len({alias.casefold() for _logical, alias in aliases}) == 7


@pytest.mark.parametrize(
    "aliases",
    [
        (("Report.xml", "alias-one.xml"), ("report.xml", "alias-two.xml")),
        (("one.xml", "Alias.xml"), ("two.xml", "alias.xml")),
        (("alias.xml", "one.xml"), ("two.xml", "alias.xml")),
        (("one.xml", "a" * 99),),
        (("one.xml", "../escape.xml"),),
    ],
)
def test_report_transport_aliases_reject_duplicate_colliding_or_unsafe_names(
    aliases: tuple[tuple[str, str], ...],
) -> None:
    with pytest.raises(runner.CommandRunnerError, match="alias"):
        runner._validate_report_transport_aliases(aliases)


def test_report_archive_restores_long_logical_name_and_payload_hash(tmp_path: Path) -> None:
    logical_name = (
        "TEST-com.example.dispute.agentstream.infrastructure.persistence."
        "AgentRunStreamRetentionManifestTest.xml"
    )
    command = {
        "report": {"expected_artifacts": [{"filename": logical_name}]}
    }
    aliases = runner._report_transport_aliases(command)
    alias_name = aliases[0][1]
    payload = _junit()
    archive = _write_report_archive(
        tmp_path / "reports.ustar",
        [(f"./{alias_name}", payload, tarfile.REGTYPE)],
    )
    staging = tmp_path / "staging"

    runner._extract_container_report_archive(archive, staging, aliases)

    restored = staging / logical_name
    assert restored.read_bytes() == payload
    assert runner._sha256(restored.read_bytes()) == runner._sha256(payload)
    assert not (staging / alias_name).exists()


def test_report_archive_rejects_alias_and_logical_name_collision(tmp_path: Path) -> None:
    aliases = (("expected.xml", "phase8-junit-000-deadbeef.xml"),)
    archive = _write_report_archive(
        tmp_path / "reports.ustar",
        [
            ("./phase8-junit-000-deadbeef.xml", _junit(), tarfile.REGTYPE),
            ("./expected.xml", _junit(), tarfile.REGTYPE),
        ],
    )
    with pytest.raises(runner.CommandRunnerError, match="duplicate path"):
        runner._extract_container_report_archive(
            archive, tmp_path / "staging", aliases
        )


def test_preexisting_transport_alias_remains_an_extra_report(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": "TEST-expected.xml",
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "target/surefire-reports/TEST-*.xml",
            "source_root": "target/surefire-reports",
        }
    }
    alias = runner._report_transport_aliases(command)[0][1]
    _mock_report_stream(
        monkeypatch, [(f"./{alias}", _junit(), tarfile.REGTYPE)]
    )

    with pytest.raises(runner.CommandRunnerError, match="reserved transport name"):
        runner._copy_container_reports(
            docker=Path("/usr/bin/docker"),
            docker_environment={"PATH": "/usr/bin"},
            container_id="a" * 64,
            command=command,
            source_root="/workspace/java-api-service/target/surefire-reports",
            output_dir=tmp_path / "output",
            staging_root=tmp_path / "staging",
        )


def test_report_archive_extracts_regular_top_level_files(tmp_path: Path) -> None:
    archive = _write_report_archive(
        tmp_path / "reports.ustar",
        [
            ("./expected.xml", _junit(), tarfile.REGTYPE),
            ("./diagnostic.txt", b"bounded", tarfile.REGTYPE),
        ],
    )
    staging = tmp_path / "staging"
    runner._extract_container_report_archive(archive, staging)
    assert (staging / "expected.xml").read_bytes() == _junit()
    assert (staging / "diagnostic.txt").read_bytes() == b"bounded"


@pytest.mark.parametrize(
    ("entries", "message"),
    [
        (
            [("./../escape.xml", _junit(), tarfile.REGTYPE)],
            "unsafe or duplicate",
        ),
        (
            [
                ("./expected.xml", _junit(), tarfile.REGTYPE),
                ("./expected.xml", _junit(), tarfile.REGTYPE),
            ],
            "unsafe or duplicate",
        ),
    ],
)
def test_report_archive_rejects_traversal_and_duplicates(
    tmp_path: Path,
    entries: list[tuple[str, bytes, bytes]],
    message: str,
) -> None:
    archive = _write_report_archive(tmp_path / "reports.ustar", entries)
    with pytest.raises(runner.CommandRunnerError, match=message):
        runner._extract_container_report_archive(archive, tmp_path / "staging")


def test_report_archive_rejects_oversized_content(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    archive = _write_report_archive(
        tmp_path / "reports.ustar",
        [("./expected.xml", b"12345", tarfile.REGTYPE)],
    )
    monkeypatch.setattr(runner, "MAX_REPORT_DIRECTORY_BYTES", 4)
    with pytest.raises(runner.CommandRunnerError, match="content bound"):
        runner._extract_container_report_archive(archive, tmp_path / "staging")


@pytest.mark.parametrize("payload", [b"not-a-tar" * 1024, b""])
def test_report_archive_rejects_malformed_or_empty_stream(
    tmp_path: Path, payload: bytes
) -> None:
    path = tmp_path / "reports.ustar"
    path.write_bytes(payload)
    if payload:
        archive = runner._hash_stable_file(
            path,
            max_bytes=runner.MAX_REPORT_STREAM_BYTES,
            context="malformed report stream",
        )
        with pytest.raises(runner.CommandRunnerError, match="malformed"):
            runner._extract_container_report_archive(archive, tmp_path / "staging")
    else:
        with pytest.raises(runner.CommandRunnerError, match="linked or oversized"):
            runner._hash_stable_file(
                path,
                max_bytes=runner.MAX_REPORT_STREAM_BYTES,
                context="empty report stream",
            )


def test_report_archive_rejects_partial_and_trailing_streams(tmp_path: Path) -> None:
    complete = _write_report_archive(
        tmp_path / "complete.ustar",
        [("./expected.xml", _junit(), tarfile.REGTYPE)],
    )
    payload = complete.path.read_bytes()
    partial_path = tmp_path / "partial.ustar"
    partial_path.write_bytes(payload[: -runner.TAR_BLOCK_BYTES])
    partial = runner._hash_stable_file(
        partial_path,
        max_bytes=runner.MAX_REPORT_STREAM_BYTES,
        context="partial report stream",
    )
    with pytest.raises(runner.CommandRunnerError, match="framing"):
        runner._extract_container_report_archive(partial, tmp_path / "partial-staging")

    trailing_path = tmp_path / "trailing.ustar"
    trailing_payload = bytearray(payload)
    trailing_payload[-1] = 1
    trailing_path.write_bytes(trailing_payload)
    trailing = runner._hash_stable_file(
        trailing_path,
        max_bytes=runner.MAX_REPORT_STREAM_BYTES,
        context="trailing report stream",
    )
    with pytest.raises(runner.CommandRunnerError, match="trailing data"):
        runner._extract_container_report_archive(
            trailing, tmp_path / "trailing-staging"
        )


def test_bounded_stdout_stream_stops_at_byte_limit(tmp_path: Path) -> None:
    result, streamed = runner._run_bounded_stdout_to_file(
        [sys.executable, "-c", "import os; os.write(1, b'x' * 4096)"],
        cwd=tmp_path,
        env={"PYTHONDONTWRITEBYTECODE": "1"},
        timeout_seconds=10,
        target=tmp_path / "bounded.bin",
        maximum_bytes=32,
    )
    assert result.exit_code == 125
    assert result.output_limited is True
    assert result.stdout == b""
    assert streamed.bytes == 32
    assert streamed.sha256 == runner._sha256(b"x" * 32)


def test_github_output_must_be_a_direct_child_of_tmp(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("GITHUB_ACTIONS", "true")
    with pytest.raises(runner.CommandRunnerError, match="direct child of /tmp"):
        runner._fresh_output_directory(tmp_path / "output")


def test_raw_output_is_private_and_no_host_source_tree_helper_remains(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.delenv("GITHUB_ACTIONS", raising=False)
    output = runner._fresh_output_directory(tmp_path / "raw")
    if os.name != "nt":
        assert os.stat(output).st_mode & 0o777 == 0o700
    assert not hasattr(runner, "_external_materialization_root")


def test_maven_reports_reject_extra_test_xml_but_allow_bounded_text(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    expected = "TEST-example.ExpectedTest.xml"
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": expected,
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "target/surefire-reports/TEST-*.xml",
            "source_root": "target/surefire-reports",
        },
    }
    accepted = tmp_path / "accepted"
    accepted.mkdir()

    _mock_report_stream(
        monkeypatch,
        [
            (f"./{expected}", _junit(), tarfile.REGTYPE),
            ("./example.ExpectedTest.txt", b"diagnostic", tarfile.REGTYPE),
        ],
    )
    reports, facts = runner._copy_container_reports(
        docker=Path("/usr/bin/docker"),
        docker_environment={"PATH": "/usr/bin"},
        container_id="a" * 64,
        command=command,
        source_root="/workspace/java-api-service/target/surefire-reports",
        output_dir=accepted,
        staging_root=tmp_path / "accepted-staging",
    )
    assert len(reports) == 1
    assert facts.tests == 1

    rejected = tmp_path / "rejected"
    rejected.mkdir()

    _mock_report_stream(
        monkeypatch,
        [
            (f"./{expected}", _junit(), tarfile.REGTYPE),
            (
                "./TEST-example.HiddenFailureTest.xml",
                _junit(body="<failure>failed</failure>"),
                tarfile.REGTYPE,
            ),
        ],
    )
    with pytest.raises(runner.CommandRunnerError, match="exact artifact set"):
        runner._copy_container_reports(
            docker=Path("/usr/bin/docker"),
            docker_environment={"PATH": "/usr/bin"},
            container_id="a" * 64,
            command=command,
            source_root="/workspace/java-api-service/target/surefire-reports",
            output_dir=rejected,
            staging_root=tmp_path / "rejected-staging",
        )


def test_failsafe_summary_xml_is_control_metadata_not_a_test_report(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    expected = "TEST-example.IntegrationTest.xml"
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": expected,
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "target/failsafe-reports/TEST-*.xml",
            "source_root": "target/failsafe-reports",
        }
    }
    output = tmp_path / "output"
    output.mkdir()
    _mock_report_stream(
        monkeypatch,
        [
            (f"./{expected}", _junit(), tarfile.REGTYPE),
            ("./failsafe-summary.xml", b"<failsafe-summary/>", tarfile.REGTYPE),
        ],
    )

    reports, facts = runner._copy_container_reports(
        docker=Path("/usr/bin/docker"),
        docker_environment={"PATH": "/usr/bin"},
        container_id="a" * 64,
        command=command,
        source_root="/workspace/java-api-service/target/failsafe-reports",
        output_dir=output,
        staging_root=tmp_path / "staging",
    )

    assert [report["path"] for report in reports] == [f"reports/{expected}"]
    assert facts.tests == 1


def test_failsafe_extra_matching_test_report_is_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    expected = "TEST-example.IntegrationTest.xml"
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": expected,
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "target/failsafe-reports/TEST-*.xml",
            "source_root": "target/failsafe-reports",
        }
    }
    output = tmp_path / "output"
    output.mkdir()
    _mock_report_stream(
        monkeypatch,
        [
            (f"./{expected}", _junit(), tarfile.REGTYPE),
            ("./TEST-example.HiddenIntegrationTest.xml", _junit(), tarfile.REGTYPE),
            ("./failsafe-summary.xml", b"<failsafe-summary/>", tarfile.REGTYPE),
        ],
    )

    with pytest.raises(runner.CommandRunnerError, match="exact artifact set"):
        runner._copy_container_reports(
            docker=Path("/usr/bin/docker"),
            docker_environment={"PATH": "/usr/bin"},
            container_id="a" * 64,
            command=command,
            source_root="/workspace/java-api-service/target/failsafe-reports",
            output_dir=output,
            staging_root=tmp_path / "staging",
        )


@pytest.mark.parametrize(
    ("contract_source", "report_glob", "runtime_source"),
    [
        (
            "target/failsafe-reports",
            None,
            "/workspace/java-api-service/target/failsafe-reports",
        ),
        (
            "target/failsafe-reports",
            "target/other/TEST-*.xml",
            "/workspace/java-api-service/target/failsafe-reports",
        ),
        (
            "target/failsafe-reports",
            "target/failsafe-reports/*.xml",
            "/workspace/java-api-service/target/failsafe-reports",
        ),
        (
            "target/failsafe-reports",
            "target/failsafe-reports/TEST-?.xml",
            "/workspace/java-api-service/target/failsafe-reports",
        ),
        (
            "target/failsafe-reports",
            r"target\failsafe-reports\TEST-*.xml",
            "/workspace/java-api-service/target/failsafe-reports",
        ),
        (
            "target/failsafe-reports",
            "target/failsafe-reports/TEST-*.xml",
            "/workspace/java-api-service/target/surefire-reports",
        ),
        (
            "target/../failsafe-reports",
            "target/../failsafe-reports/TEST-*.xml",
            "/workspace/java-api-service/target/failsafe-reports",
        ),
        (
            "target/failsafe-reports",
            "target/failsafe-reports/TEST-*.xml",
            "/other/java-api-service/target/failsafe-reports",
        ),
    ],
)
def test_report_glob_rejects_malformed_or_mismatched_contracts(
    contract_source: str, report_glob: str | None, runtime_source: str
) -> None:
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [{"filename": "TEST-example.Test.xml"}],
            "glob": report_glob,
            "source_root": contract_source,
        }
    }
    with pytest.raises(runner.CommandRunnerError, match="report (glob|root)"):
        runner._validated_report_glob(command, runtime_source)


@pytest.mark.parametrize(
    "reserved_name",
    [
        "PhAsE8-JuNiT-attacker.xml",
        "PHASE8-EXTRA-attacker.xml",
    ],
)
def test_reserved_transport_prefix_case_variants_fail_closed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, reserved_name: str
) -> None:
    expected = "TEST-example.ExpectedTest.xml"
    command = {
        "cwd": "java-api-service",
        "report": {
            "expected_artifacts": [
                {
                    "filename": expected,
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "target/surefire-reports/TEST-*.xml",
            "source_root": "target/surefire-reports",
        }
    }
    _mock_report_stream(
        monkeypatch,
        [
            (f"./{expected}", _junit(), tarfile.REGTYPE),
            (f"./{reserved_name}", b"reserved", tarfile.REGTYPE),
        ],
    )

    with pytest.raises(runner.CommandRunnerError, match="reserved transport name"):
        runner._copy_container_reports(
            docker=Path("/usr/bin/docker"),
            docker_environment={"PATH": "/usr/bin"},
            container_id="a" * 64,
            command=command,
            source_root="/workspace/java-api-service/target/surefire-reports",
            output_dir=tmp_path / "output",
            staging_root=tmp_path / "staging",
        )


def test_maven_uses_isolated_rootless_dind_lifecycle_without_host_bridge(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    candidate_archive_path = tmp_path / "candidate.tar"
    candidate_archive_path.write_bytes(b"candidate-archive")
    home = tmp_path / "runner-home"
    home.mkdir()
    output_dir = tmp_path / "output"
    output_dir.mkdir()
    materialization = runner.Materialization(
        [],
        {},
        {},
        {},
        {},
        runner._hash_stable_file(
            candidate_archive_path,
            max_bytes=1024,
            context="candidate archive",
        ),
    )
    command = {
        "argv": ["./mvnw", "-B", "test"],
        "cwd": "java-api-service",
        "environment": {"CI": "1", "MAVEN_OPTS": "-Djava.awt.headless=true"},
        "report": {
            "expected_artifacts": [
                {
                    "filename": "TEST-example.ExpectedTest.xml",
                    "suite_name": "suite",
                    "test_count": 1,
                }
            ],
            "glob": "target/surefire-reports/TEST-*.xml",
            "source_root": "target/surefire-reports",
        },
        "timeout_seconds": 30,
    }
    monkeypatch.setattr(
        runner, "_resolve_executable", lambda _: Path("/usr/bin/docker")
    )
    monkeypatch.setattr(runner, "_docker_environment", lambda _: {"PATH": "/usr/bin"})
    calls: list[list[str]] = []
    create_count = 0

    def run_bounded(argv, **_kwargs):
        nonlocal create_count
        values = list(argv)
        calls.append(values)
        if values[1:3] == ["image", "pull"]:
            return runner.ProcessResult(0, False, False, b"pulled", b"")
        if values[1:3] == ["network", "create"]:
            return runner.ProcessResult(0, False, False, b"network", b"")
        if values[1] == "create":
            create_count += 1
            container_id = "c" * 64 if create_count == 1 else "d" * 64
            return runner.ProcessResult(
                0, False, False, f"{container_id}\n".encode(), b""
            )
        if values[1] == "exec" and values[-4:] == [
            "docker",
            f"--host={runner.MAVEN_DIND_LOCAL_HOST}",
            "info",
            "--format={{json .SecurityOptions}}",
        ]:
            return runner.ProcessResult(0, False, False, b'["name=rootless"]', b"")
        if values[1] == "exec" and values[-2:] == ["id", "-u"]:
            return runner.ProcessResult(0, False, False, b"1000\n", b"")
        return runner.ProcessResult(0, False, False, b"", b"")

    monkeypatch.setattr(runner, "_run_bounded", run_bounded)
    _mock_report_stream(
        monkeypatch,
        [("./TEST-example.ExpectedTest.xml", _junit(), tarfile.REGTYPE)],
    )
    monkeypatch.setattr(
        runner,
        "_authorize_materialization_archive",
        lambda _: candidate_archive_path.open("rb"),
    )
    seed_calls: list[list[str]] = []

    def seed_candidate(argv, **_kwargs):
        assert any(call[1:] == ["start", "d" * 64] for call in calls)
        seed_calls.append(list(argv))
        return runner.ProcessResult(0, False, False, b"", b""), {}

    monkeypatch.setattr(runner, "_run_bounded_with_verified_archive", seed_candidate)
    result, reports, facts = runner._execute_maven_container(
        command, materialization, home, output_dir
    )
    assert result.exit_code == 0
    assert len(reports) == 1
    assert facts.tests == 1
    creates = [values for values in calls if values[1] == "create"]
    assert len(creates) == 2
    dind_create, maven_create = creates
    assert runner.DIND_IMAGE in dind_create
    assert runner.MAVEN_IMAGE in maven_create
    network_create = next(
        values for values in calls if values[1:3] == ["network", "create"]
    )
    network_name = network_create[-1]
    assert "--driver=bridge" in network_create
    assert f"--network={network_name}" in dind_create
    assert f"--network={network_name}" in maven_create
    assert f"--network-alias={runner.MAVEN_DIND_ALIAS}" in dind_create
    assert "--privileged" in dind_create
    assert f"--tmpfs={runner.MAVEN_DIND_DATA_TMPFS}" in dind_create
    assert "mode=0700,uid=1000,gid=1000" in runner.MAVEN_DIND_DATA_TMPFS
    assert "--read-only" in maven_create
    assert "--cap-drop=ALL" in maven_create
    assert "--security-opt=no-new-privileges:true" in maven_create
    assert (
        "--tmpfs=/workspace:rw,nosuid,nodev,exec,size=2147483648,mode=0755"
        in maven_create
    )
    assert f"--env=DOCKER_HOST={runner.MAVEN_DIND_HOST}" in maven_create
    assert runner.DIND_READY_TIMEOUT_SECONDS == 180
    assert runner.MAVEN_JANSI_OPTS not in command["environment"]["MAVEN_OPTS"]
    assert any(
        value.startswith("--env=MAVEN_OPTS=") and runner.MAVEN_JANSI_OPTS in value
        for value in maven_create
    )
    assert any(
        value == f"--env=TESTCONTAINERS_RYUK_DISABLED={runner.MAVEN_RYUK_DISABLED}"
        for value in maven_create
    )
    assert all("docker.sock" not in value for call in calls for value in call)
    assert all("host-gateway" not in value for call in calls for value in call)
    assert all(not value.startswith("--publish") for value in dind_create)
    assert all(not value.startswith("-p") for value in dind_create)
    assert all(str(runner.TRUSTED_ROOT) not in value for value in maven_create)
    assert len(seed_calls) == 1
    assert seed_calls[0][:5] == [
        str(Path("/usr/bin/docker")),
        "exec",
        "--interactive",
        "--user=0:0",
        "d" * 64,
    ]
    assert seed_calls[0][5:] == list(runner.MAVEN_CANDIDATE_EXTRACTOR_ARGV)
    target_prepares = [
        values
        for values in calls
        if values[1] == "exec" and "/bin/mkdir" in values
    ]
    assert target_prepares == [
        [
            str(Path("/usr/bin/docker")),
            "exec",
            "--user=0:0",
            "d" * 64,
            "/bin/mkdir",
            "-p",
            "-m",
            "0777",
            "/workspace/java-api-service/target",
        ]
    ]
    assert all("/bin/chown" not in value for call in calls for value in call)
    starts = [values for values in calls if values[1] == "start"]
    assert [values[1:] for values in starts] == [
        ["start", "c" * 64],
        ["start", "d" * 64],
    ]
    cleanups = [values[1:] for values in calls if values[1] in {"rm", "network"}]
    assert cleanups[-3:] == [
        ["rm", "--force", "d" * 64],
        ["rm", "--force", "c" * 64],
        ["network", "rm", network_name],
    ]


@pytest.mark.parametrize(
    ("state", "logs", "expected"),
    [
        (b"running|0|false\n", b"", "DIND_PROBE_FAILED"),
        (b"exited|1|false\n", b"", "DIND_EXITED"),
        (b"exited|137|true\n", b"", "DIND_OOM_KILLED"),
        (
            b"exited|1|false\n",
            b"error: attempting to run rootless dockerd but need writable HOME",
            "DIND_HOME_UNWRITABLE",
        ),
        (
            b"exited|1|false\n",
            b"rootlesskit: failed to start the child: operation not permitted",
            "DIND_USER_NAMESPACE_DENIED",
        ),
        (
            b"exited|1|false\n",
            b"mount overlay: operation not permitted",
            "DIND_OPERATION_NOT_PERMITTED",
        ),
    ],
)
def test_dind_failure_classification_exposes_only_fixed_codes(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    state: bytes,
    logs: bytes,
    expected: str,
) -> None:
    calls: list[list[str]] = []

    def run_bounded(argv, **_kwargs):
        values = list(argv)
        calls.append(values)
        if values[1] == "inspect":
            return runner.ProcessResult(0, False, False, state, b"")
        if values[1] == "logs":
            return runner.ProcessResult(0, False, False, logs, b"")
        raise AssertionError(values)

    monkeypatch.setattr(runner, "_run_bounded", run_bounded)
    assert (
        runner._classify_dind_start_failure(
            docker=Path("/usr/bin/docker"),
            container_id="c" * 64,
            cwd=tmp_path,
            environment={"PATH": "/usr/bin"},
        )
        == expected
    )
    assert [call[1] for call in calls] == ["inspect", "logs"]


def test_maven_and_dind_images_pin_index_and_linux_amd64_manifest_digests() -> None:
    assert runner.MAVEN_IMAGE_TAG == "maven:3.9.11-eclipse-temurin-21"
    assert runner.DIND_IMAGE_TAG == "docker:28.5.2-dind-rootless"
    for image, index_digest, platform_digest in (
        (
            runner.MAVEN_IMAGE,
            runner.MAVEN_IMAGE_INDEX_DIGEST,
            runner.MAVEN_IMAGE_PLATFORM_DIGEST,
        ),
        (
            runner.DIND_IMAGE,
            runner.DIND_IMAGE_INDEX_DIGEST,
            runner.DIND_IMAGE_PLATFORM_DIGEST,
        ),
    ):
        assert runner.SHA256.fullmatch(index_digest.removeprefix("sha256:"))
        assert runner.SHA256.fullmatch(platform_digest.removeprefix("sha256:"))
        assert index_digest != platform_digest
        assert image.endswith(f"@{platform_digest}")


def test_wheelhouse_manifest_writer_round_trips_exact_canonical_bytes(
    tmp_path: Path,
) -> None:
    manifest = [
        {
            "bytes": 17 + index,
            "filename": f"{distribution.replace('-', '_')}-{version}-py3-none-any.whl",
            "sha256": sha256,
        }
        for index, (distribution, (version, sha256)) in enumerate(
            sorted(runner.runtime_policy._requirements_lock_records().items())
        )
    ]
    manifest.sort(key=lambda entry: entry["filename"])
    path = tmp_path / runner.WHEELHOUSE_MANIFEST_NAME
    path.write_bytes(b"stale\n")

    stable = runner._atomic_wheelhouse_manifest(path, manifest)
    expected = runner.runtime_policy.canonical_json_bytes(manifest)

    assert stable.path == path
    assert stable.payload == expected
    assert stable.sha256 == runner._sha256(expected)
    assert path.read_bytes() == expected
    assert not expected.endswith(b"\n")
    assert runner.runtime_policy.parse_wheelhouse_manifest_bytes(expected) == manifest
    assert list(tmp_path.iterdir()) == [path]


def test_observer_build_failure_removes_temporary_oci(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    trusted_root = tmp_path / "trusted"
    trusted_root.mkdir()
    dockerfile_path = trusted_root / "Dockerfile"
    lock_path = trusted_root / "requirements.lock"
    dockerfile_path.write_bytes(b"FROM scratch\n")
    lock_path.write_bytes(b"package==1 --hash=sha256:" + b"a" * 64 + b"\n")
    trusted_dockerfile = runner._read_stable_file(
        dockerfile_path, max_bytes=1024, context="trusted Dockerfile"
    )
    trusted_lock = runner._read_stable_file(
        lock_path, max_bytes=1024, context="trusted requirements lock"
    )
    producer_root = tmp_path / "producer"
    (producer_root / "wheelhouse").mkdir(parents=True)
    image_archive = producer_root / "image.tar"
    image_archive.write_bytes(b"producer")
    execution_image_archive = producer_root / "execution-image.tar"
    execution_image_archive.write_bytes(b"producer-docker")
    output_dir = tmp_path / "observer"
    output_dir.mkdir()
    home = tmp_path / "home"
    home.mkdir()
    wheel_manifest = [{"bytes": 1, "filename": "package.whl", "sha256": "b" * 64}]
    build_receipt = {
        "dockerfile_git_blob": runner._git_blob_sha1(trusted_dockerfile.payload),
        "dockerfile_sha256": trusted_dockerfile.sha256,
        "image_id": f"sha256:{'c' * 64}",
        "requirements_lock_git_blob": runner._git_blob_sha1(trusted_lock.payload),
        "requirements_lock_sha256": trusted_lock.sha256,
        "wheelhouse_manifest_sha256": runner.runtime_policy.canonical_sha256(
            wheel_manifest
        ),
    }
    monkeypatch.setattr(
        runner, "_runtime_input_files", lambda: (trusted_dockerfile, trusted_lock)
    )
    monkeypatch.setattr(
        runner.runtime_policy,
        "validate_wheelhouse_directory",
        lambda *_args, **_kwargs: (
            wheel_manifest,
            build_receipt["wheelhouse_manifest_sha256"],
        ),
    )
    monkeypatch.setattr(
        runner, "_resolve_executable", lambda _: Path("/usr/bin/docker")
    )
    monkeypatch.setattr(runner, "_docker_environment", lambda _: {"PATH": "/usr/bin"})
    monkeypatch.setattr(
        runner,
        "_docker_inspect_projection",
        lambda *args, **kwargs: {"image_id": "base"},
    )
    monkeypatch.setattr(
        runner,
        "_docker_load_and_inspect",
        lambda *args, **kwargs: {"image_id": build_receipt["image_id"]},
    )
    captured_build_argv: list[str] = []

    def fail_build(argv, **_kwargs):
        values = list(argv)
        if values[1:3] == ["buildx", "build"]:
            captured_build_argv.extend(values)
            for output_argument in (
                value for value in values if value.startswith("--output=")
            ):
                temporary_path = Path(
                    output_argument.split("dest=", 1)[1].split(",", 1)[0]
                )
                temporary_path.write_bytes(b"partial-archive")
            return runner.ProcessResult(1, False, False, b"", b"build failed")
        return runner.ProcessResult(0, False, False, b"", b"")

    monkeypatch.setattr(runner, "_run_bounded", fail_build)
    with pytest.raises(runner.CommandRunnerError) as captured:
        runner._rebuild_observer_runtime(
            image_archive=image_archive,
            execution_image_archive=execution_image_archive,
            producer_root=producer_root,
            build_receipt=build_receipt,
            wheel_manifest=wheel_manifest,
            output_dir=output_dir,
            home=home,
        )
    assert captured.value.code == "RUNTIME_BUILD_FAILED"
    assert "build failed" not in str(captured.value)
    assert not (output_dir / ".observer-image.oci.tmp").exists()
    assert not (output_dir / ".observer-image.docker.tmp").exists()
    assert (
        sum(
            value.startswith("--builder=phase8-buildkit-")
            for value in captured_build_argv
        )
        == 1
    )
    outputs = [value for value in captured_build_argv if value.startswith("--output=")]
    assert len(outputs) == 2
    assert outputs[0].startswith("--output=type=oci,dest=")
    assert outputs[1].startswith("--output=type=docker,dest=")
    for output in outputs:
        assert "compression=uncompressed" in output
        assert "oci-mediatypes=true" in output
        assert "rewrite-timestamp=true" in output


def test_runner_source_has_no_pending_or_root_only_materialization_fallback() -> None:
    source = RUNNER_PATH.read_text(encoding="ascii")
    assert "RUNTIME_INTERFACE_PENDING" not in source
    assert "_verify_materialization_without_repository" not in source
    assert source.count("_revalidate_materialization(materialization)") == 1
    assert ".rglob(" not in source
    assert "shared-runtime/producer/inputs" not in source
    assert "shared-runtime/producer/image.tar" not in source
    assert "shared-runtime/observer/image.tar" not in source
    assert source.count('f"--builder={builder_name}"') == 2
    assert source.count("--output=type=oci,dest=") == 2
    assert source.count("--output=type=docker,dest=") == 2
