from __future__ import annotations

import ast
import copy
import hashlib
import io
import json
import os
import tarfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import pytest

from scripts.phase8.candidate import github_attestation as verifier


CANDIDATE = "a" * 40
TRUSTED_CODE = "b" * 40
TRUSTED_WORKFLOW = "c" * 40
TREE = "d" * 40
TRUSTED_CODE_TREE = "e" * 40
TRUSTED_WORKFLOW_TREE = "f" * 40
RUN_ID = 424242
ATTEMPT = 1
NOW = datetime(2026, 7, 26, 2, 0, tzinfo=timezone.utc)
JOB_NAMES = (
    "witness / phase8_build_runtime",
    "witness / phase8_observe_runtime",
    "witness / phase8_wave_a_static",
    "witness / phase8_wave_a_java",
    "witness / phase8_wave_b_static_and_models",
    "witness / phase8_wave_b_java_unit",
    "witness / phase8_wave_b_postgresql_integration",
    "witness / aggregate",
    "witness / attest",
    "witness / gate",
)
REPORT_PATH = "commands/000-wave_a_static/report.json"
REPORT = b'{"status":"PASS"}\n'
SHARED_BUILD_PATH = "runtime/shared/runtime-build-receipt.json"
SHARED_BUILD = b'{"kind":"runtime-build","status":"PASS"}\n'
SHARED_OBSERVATION_PATH = "runtime/shared/build-observation.json"
SHARED_OBSERVATION = b'{"kind":"build-observation","status":"PASS"}\n'
SHARED_ARCHIVE_INDEX_PATH = "runtime/shared/archive-index.json"
SHARED_ARCHIVE_INDEX = b'{"archives":{},"schema_version":"phase8-runtime-archive-index.v1"}\n'
EXECUTION_SET_PATH = "runtime/execution-set.json"
EXECUTION_SET = b'{"schema_version":"phase8-engineering-execution-set.v1"}'
DEFAULT_WITNESS_MEMBERS = (
    (REPORT_PATH, REPORT),
    (EXECUTION_SET_PATH, EXECUTION_SET),
    (SHARED_ARCHIVE_INDEX_PATH, SHARED_ARCHIVE_INDEX),
    (SHARED_OBSERVATION_PATH, SHARED_OBSERVATION),
    (SHARED_BUILD_PATH, SHARED_BUILD),
)


def _transition_additions(paths: tuple[str, ...], prefix: str) -> list[dict[str, Any]]:
    additions: list[dict[str, Any]] = []
    for index, path in enumerate(paths):
        payload = f"{prefix}-{index}\n".encode("ascii")
        additions.append(
            {
                "bytes": len(payload),
                "git_blob_sha": hashlib.sha1(
                    f"blob {len(payload)}\0".encode("ascii") + payload
                ).hexdigest(),
                "mode": "100644",
                "path": path,
                "sha256": hashlib.sha256(payload).hexdigest(),
                "status": "A",
            }
        )
    return additions


TRUSTED_TRANSITION = {
    "candidate_sha": CANDIDATE,
    "candidate_tree_sha": TREE,
    "trusted_code_sha": TRUSTED_CODE,
    "trusted_code_to_workflow_additions": _transition_additions(
        verifier.TRUSTED_CODE_TO_WORKFLOW_PATHS, "workflow"
    ),
    "trusted_code_tree_sha": TRUSTED_CODE_TREE,
    "trusted_workflow_sha": TRUSTED_WORKFLOW,
    "trusted_workflow_to_candidate_additions": _transition_additions(
        verifier.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS, "candidate"
    ),
    "trusted_workflow_tree_sha": TRUSTED_WORKFLOW_TREE,
}
TRUSTED_TRANSITION_SHA256 = hashlib.sha256(
    json.dumps(
        TRUSTED_TRANSITION,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
).hexdigest()


def _json(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _member_index(members: list[tuple[str, bytes]]) -> list[dict[str, Any]]:
    return [
        {
            "bytes": len(payload),
            "path": path,
            "sha256": hashlib.sha256(payload).hexdigest(),
        }
        for path, payload in sorted(members)
    ]


def _subject_bytes(
    *,
    trusted_code_sha: str = TRUSTED_CODE,
    trusted_workflow_sha: str = TRUSTED_WORKFLOW,
    caller_file_sha256: str | None = None,
    members: list[tuple[str, bytes]] | None = None,
    index_mutation: Callable[[list[dict[str, Any]]], None] | None = None,
    manifest_mutation: Callable[[dict[str, Any]], None] | None = None,
) -> bytes:
    witness_members = list(DEFAULT_WITNESS_MEMBERS if members is None else members)
    index = _member_index(witness_members)
    if index_mutation is not None:
        index_mutation(index)
    caller_binding = verifier._expected_caller_workflow_binding(trusted_workflow_sha)
    if caller_file_sha256 is not None:
        caller_binding["file_sha256"] = caller_file_sha256
    manifest_document = {
        "accepted_a8_sha": verifier.ACCEPTED_A8,
        "authority_ceiling": verifier.AUTHORITY_CEILING,
        "caller_workflow_binding": caller_binding,
        "caller_workflow_path": verifier.CALLER_WORKFLOW,
        "caller_workflow_ref": (
            f"{verifier.REPOSITORY}/{verifier.CALLER_WORKFLOW}@{verifier.BRANCH}"
        ),
        "caller_workflow_sha": CANDIDATE,
        "candidate_sha": CANDIDATE,
        "candidate_tree_sha": TREE,
        "command_artifact_set_sha256": hashlib.sha256(_json(index)).hexdigest(),
        "command_contract_payload_sha256": "f" * 64,
        "member_index": index,
        "schema_version": verifier.WITNESS_MANIFEST_SCHEMA_VERSION,
        "scope_inventory_sha256": "1" * 64,
        "sources_status": verifier.EXPECTED_SOURCES_STATUS,
        "trusted_code_sha": trusted_code_sha,
        "trusted_code_tree_sha": TRUSTED_CODE_TREE,
        "trusted_transition": copy.deepcopy(TRUSTED_TRANSITION),
        "trusted_transition_sha256": TRUSTED_TRANSITION_SHA256,
        "trusted_workflow_file_path": verifier.SIGNER_WORKFLOW,
        "trusted_workflow_ref": (
            f"{verifier.REPOSITORY}/{verifier.SIGNER_WORKFLOW}@{trusted_workflow_sha}"
        ),
        "trusted_workflow_repository": verifier.REPOSITORY,
        "trusted_workflow_sha": trusted_workflow_sha,
        "trusted_workflow_tree_sha": TRUSTED_WORKFLOW_TREE,
    }
    if manifest_mutation is not None:
        manifest_mutation(manifest_document)
    manifest = _json(manifest_document)
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:") as archive:
        archive_members = [*witness_members, ("manifest.json", manifest)]
        for name, payload in sorted(archive_members):
            member = tarfile.TarInfo(name)
            member.mode = 0o644
            member.size = len(payload)
            archive.addfile(member, io.BytesIO(payload))
    return output.getvalue()


SUBJECT = _subject_bytes()
SUBJECT_DIGEST = hashlib.sha256(SUBJECT).hexdigest()


def _artifact_zip(subject: bytes) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, mode="w", compression=zipfile.ZIP_DEFLATED) as archive:
        member = zipfile.ZipInfo(verifier.SUBJECT_FILENAME, (1980, 1, 1, 0, 0, 0))
        member.compress_type = zipfile.ZIP_DEFLATED
        member.external_attr = 0o100644 << 16
        archive.writestr(member, subject)
    return output.getvalue()


ARTIFACT_ZIP = _artifact_zip(SUBJECT)
ARTIFACT_ZIP_DIGEST = hashlib.sha256(ARTIFACT_ZIP).hexdigest()
COMMAND_ARTIFACT_SET_SHA256 = hashlib.sha256(
    _json(_member_index(list(DEFAULT_WITNESS_MEMBERS)))
).hexdigest()


def _run() -> dict[str, Any]:
    return {
        "attempt": ATTEMPT,
        "conclusion": "success",
        "createdAt": "2026-07-26T01:00:00Z",
        "databaseId": RUN_ID,
        "event": "push",
        "headBranch": "codex/p8-production-hardening",
        "headSha": CANDIDATE,
        "status": "completed",
        "updatedAt": "2026-07-26T01:55:00Z",
        "workflowDatabaseId": 12345,
        "workflowName": verifier.CALLER_WORKFLOW_NAME,
    }


def _run_view_jobs() -> list[dict[str, Any]]:
    return [
        {
            "completedAt": "2026-07-26T01:20:00Z",
            "conclusion": "success",
            "databaseId": 100 + index,
            "name": name,
            "startedAt": "2026-07-26T01:01:00Z",
            "status": "completed",
            "steps": [
                {
                    "completedAt": "2026-07-26T01:19:00Z",
                    "conclusion": "success",
                    "name": "Run bounded witness command",
                    "number": 1,
                    "startedAt": "2026-07-26T01:02:00Z",
                    "status": "completed",
                }
            ],
            "url": (
                f"https://github.com/{verifier.REPOSITORY}/actions/runs/"
                f"{RUN_ID}/job/{100 + index}"
            ),
        }
        for index, name in enumerate(JOB_NAMES, start=1)
    ]


def _rest_attempt_jobs() -> list[dict[str, Any]]:
    return [
        {
            "check_run_url": (
                f"https://api.github.com/repos/{verifier.REPOSITORY}/check-runs/"
                f"{100 + index}"
            ),
            "completed_at": "2026-07-26T01:20:00Z",
            "conclusion": "success",
            "created_at": "2026-07-26T01:00:30Z",
            "head_branch": "codex/p8-production-hardening",
            "head_sha": CANDIDATE,
            "html_url": (
                f"https://github.com/{verifier.REPOSITORY}/actions/runs/"
                f"{RUN_ID}/job/{100 + index}"
            ),
            "id": 100 + index,
            "labels": ["ubuntu-24.04"],
            "name": name,
            "node_id": f"CR_kwDOTHByAQ8AAAAA{100 + index}",
            "run_attempt": ATTEMPT,
            "run_id": RUN_ID,
            "run_url": (
                f"https://api.github.com/repos/{verifier.REPOSITORY}/actions/runs/"
                f"{RUN_ID}"
            ),
            "runner_group_id": 0,
            "runner_group_name": "GitHub Actions",
            "runner_id": 200 + index,
            "runner_name": f"GitHub Actions {200 + index}",
            "started_at": "2026-07-26T01:01:00Z",
            "status": "completed",
            "steps": [
                {
                    "completed_at": "2026-07-26T01:19:00Z",
                    "conclusion": "success",
                    "name": "Run bounded witness command",
                    "number": 1,
                    "started_at": "2026-07-26T01:02:00Z",
                    "status": "completed",
                }
            ],
            "url": (
                f"https://api.github.com/repos/{verifier.REPOSITORY}/actions/jobs/"
                f"{100 + index}"
            ),
            "workflow_name": "Phase 8 engineering caller",
        }
        for index, name in enumerate(JOB_NAMES, start=1)
    ]


def _attempt_jobs_pages() -> dict[int, dict[str, Any]]:
    return {
        1: {"total_count": 10, "jobs": _rest_attempt_jobs()},
        2: {"total_count": 10, "jobs": []},
    }


def _artifact() -> dict[str, Any]:
    formatting = {"run_id": RUN_ID, "run_attempt": ATTEMPT}
    names = [
        *(
            template.format(**formatting)
            for template in verifier.RAW_ARTIFACT_NAME_TEMPLATES
        ),
        verifier.RUNTIME_IMAGE_ARTIFACT_NAME_TEMPLATE.format(
            **formatting, archive_sha256="1" * 64
        ),
        verifier.OBSERVATION_ARTIFACT_NAME_TEMPLATE.format(**formatting),
        verifier.ARTIFACT_NAME_TEMPLATE.format(**formatting),
    ]
    return {
        "artifacts": [
            {
                "archive_download_url": (
                    f"https://api.github.com/repos/{verifier.REPOSITORY}/actions/artifacts/{9090 + index}/zip"
                ),
                "created_at": "2026-07-26T01:20:00Z",
                "digest": (
                    f"sha256:{ARTIFACT_ZIP_DIGEST}"
                    if name == verifier.ARTIFACT_NAME_TEMPLATE.format(**formatting)
                    else f"sha256:{index + 1:064x}"
                ),
                "expired": False,
                "expires_at": "2026-10-24T01:20:00Z",
                "id": 9090 + index,
                "name": name,
                "node_id": f"A_kwDOTHByAQ4artifact{index:02d}",
                "size_in_bytes": len(SUBJECT),
                "updated_at": "2026-07-26T01:21:00Z",
                "url": (
                    f"https://api.github.com/repos/{verifier.REPOSITORY}/actions/artifacts/{9090 + index}"
                ),
                "workflow_run": {
                    "head_branch": verifier.BRANCH.removeprefix("refs/heads/"),
                    "head_repository_id": int(verifier.REPOSITORY_ID),
                    "head_sha": CANDIDATE,
                    "id": RUN_ID,
                    "repository_id": int(verifier.REPOSITORY_ID),
                },
            }
            for index, name in enumerate(names)
        ],
        "total_count": 8,
    }


def _verification(predicate: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    signer_uri = f"https://github.com/{verifier.REPOSITORY}/{verifier.SIGNER_WORKFLOW}@refs/heads/main"
    return [
        {
            "attestation": {"bundle": "verified"},
            "verificationResult": {
                "mediaType": "application/vnd.dev.sigstore.verificationresult+json;version=0.1",
                "signature": {
                    "certificate": {
                        "buildConfigDigest": CANDIDATE,
                        "buildConfigURI": f"https://github.com/{verifier.REPOSITORY}/{verifier.CALLER_WORKFLOW}@{verifier.BRANCH}",
                        "buildSignerDigest": TRUSTED_WORKFLOW,
                        "buildSignerURI": signer_uri,
                        "buildTrigger": verifier.EVENT,
                        "certificateIssuer": "CN=sigstore-intermediate,O=sigstore.dev",
                        "githubWorkflowName": verifier.CALLER_WORKFLOW_NAME,
                        "githubWorkflowRef": verifier.BRANCH,
                        "githubWorkflowRepository": verifier.REPOSITORY,
                        "githubWorkflowSHA": CANDIDATE,
                        "githubWorkflowTrigger": verifier.EVENT,
                        "issuer": verifier.OIDC_ISSUER,
                        "runInvocationURI": f"https://github.com/{verifier.REPOSITORY}/actions/runs/{RUN_ID}/attempts/{ATTEMPT}",
                        "runnerEnvironment": verifier.RUNNER_ENVIRONMENT,
                        "sourceRepositoryDigest": CANDIDATE,
                        "sourceRepositoryIdentifier": verifier.REPOSITORY_ID,
                        "sourceRepositoryOwnerIdentifier": "987654321",
                        "sourceRepositoryOwnerURI": "https://github.com/Jupiter363",
                        "sourceRepositoryRef": verifier.BRANCH,
                        "sourceRepositoryURI": f"https://github.com/{verifier.REPOSITORY}",
                        "sourceRepositoryVisibilityAtSigning": "public",
                        "subjectAlternativeName": signer_uri,
                    }
                },
                "statement": {
                    "_type": "https://in-toto.io/Statement/v1",
                    "predicate": predicate
                    if predicate is not None
                    else {"builder": {"id": "github"}},
                    "predicateType": verifier.PREDICATE_TYPE,
                    "subject": [
                        {
                            "digest": {"sha256": SUBJECT_DIGEST},
                            "name": verifier.SUBJECT_FILENAME,
                        }
                    ],
                },
                "verifiedIdentity": {"issuer": {"issuer": verifier.OIDC_ISSUER}},
                "verifiedTimestamps": [
                    {
                        "timestamp": "2026-07-26T01:56:00Z",
                        "type": "Tlog",
                        "uri": "https://rekor.sigstore.dev",
                    }
                ],
            },
        }
    ]


class FakeGitHub:
    def __init__(self) -> None:
        self.run_list: Any = [_run()]
        self.run_view: Any = {**_run(), "jobs": _run_view_jobs()}
        self.attempt_jobs_pages: Any = _attempt_jobs_pages()
        self.artifacts: Any = _artifact()
        self.online: Any = _verification()
        self.offline: Any = _verification()
        self.subject = SUBJECT
        self.archive_payload: bytes | None = None
        self.same_name_rerun_subject: bytes | None = None
        self.calls: list[tuple[str, ...]] = []
        self.hook: Callable[[tuple[str, ...]], None] | None = None
        self.hardlink_subject = False

    def __call__(
        self,
        argv: tuple[str, ...],
        cwd: Path | None = None,
        *,
        executable: verifier.TrustedExecutable,
        max_stdout_bytes: int = verifier.MAX_GH_JSON_BYTES,
    ) -> verifier.CommandResult:
        assert isinstance(executable, verifier.TrustedExecutable)
        self.calls.append(argv)
        if self.hook is not None:
            self.hook(argv)
        if argv[:2] == ("run", "list"):
            payload = _json(self.run_list)
        elif argv[:2] == ("run", "view"):
            payload = _json(self.run_view)
        elif argv[:2] == ("api", "--method"):
            endpoint = argv[3]
            attempt_jobs_endpoint = (
                f"repos/{verifier.REPOSITORY}/actions/runs/{RUN_ID}/attempts/"
                f"{ATTEMPT}/jobs"
            )
            if endpoint == attempt_jobs_endpoint:
                page = int(
                    next(value.removeprefix("page=") for value in argv if value.startswith("page="))
                )
                assert page in {1, 2}
                payload = _json(self.attempt_jobs_pages[page])
            elif endpoint.endswith("/artifacts"):
                if self.artifacts["artifacts"]:
                    final = self.artifacts["artifacts"][-1]
                    if final["digest"] == f"sha256:{ARTIFACT_ZIP_DIGEST}":
                        final["digest"] = (
                            "sha256:"
                            + hashlib.sha256(_artifact_zip(self.subject)).hexdigest()
                        )
                payload = _json(self.artifacts)
            else:
                selected = self.artifacts["artifacts"][-1]
                assert endpoint.endswith(f"/actions/artifacts/{selected['id']}/zip")
                payload = (
                    self.archive_payload
                    if self.archive_payload is not None
                    else _artifact_zip(self.subject)
                )
        elif argv[:2] == ("run", "download"):
            directory = Path(argv[argv.index("--dir") + 1])
            substituted = self.same_name_rerun_subject or self.subject
            (directory / verifier.SUBJECT_FILENAME).write_bytes(substituted)
            payload = b"{}"
        elif argv[:2] == ("attestation", "download"):
            assert cwd is not None
            if self.hardlink_subject:
                subject = cwd.parent / "download" / verifier.SUBJECT_FILENAME
                os.link(subject, cwd.parent / "subject-alias.tar")
            bundle_name = (
                f"sha256-{SUBJECT_DIGEST}.jsonl"
                if os.name == "nt"
                else f"sha256:{SUBJECT_DIGEST}.jsonl"
            )
            (cwd / bundle_name).write_bytes(
                _json({"bundle": "sigstore-bundle", "version": 1}) + b"\n"
            )
            payload = b"Wrote one attestation bundle\n"
        elif argv[:2] == ("attestation", "trusted-root"):
            payload = _json(
                {"expiresAt": "2026-07-27T02:00:00Z", "root": "public-good"}
            )
        elif argv[:2] == ("attestation", "verify"):
            payload = _json(self.offline if "--bundle" in argv else self.online)
        else:  # pragma: no cover - catches implementation drift immediately
            raise AssertionError(argv)
        return verifier.CommandResult(0, payload, b"")


def _install(monkeypatch: pytest.MonkeyPatch, fake: FakeGitHub) -> None:
    monkeypatch.setattr(verifier, "_execute_gh", fake)
    monkeypatch.setattr(
        verifier,
        "_preflight_gh",
        lambda policy, state_home: verifier.TrustedExecutable(
            Path("C:/trusted/gh.exe"),
            (1, 2, 3, 4, 1),
            "0" * 64,
            "2.93.0",
            state_home=state_home,
        ),
    )
    monkeypatch.setattr(verifier, "_utc_now", lambda: NOW)


def _verify(tmp_path: Path, *, trusted_code_sha: str = TRUSTED_CODE) -> dict[str, Any]:
    (tmp_path / "external").mkdir()
    return verifier.verify_github_attestation(
        candidate_sha=CANDIDATE,
        trusted_code_sha=trusted_code_sha,
        trusted_workflow_sha=TRUSTED_WORKFLOW,
        artifact=verifier.SUBJECT_FILENAME,
        run_dir=(tmp_path / "external" / "run").absolute(),
    )


def test_policy_is_exact_repository_branch_identity_and_engineering_only() -> None:
    assert verifier.load_policy() == verifier.EXPECTED_POLICY
    assert hashlib.sha256(verifier.POLICY_PATH.read_bytes()).hexdigest() == (
        verifier.EXPECTED_POLICY_SHA256
    )
    policy = verifier.load_policy()
    assert policy["repository"] == "Jupiter363/AfterSaleFlow-Agent"
    assert policy["branch"] == "refs/heads/codex/p8-production-hardening"
    assert (
        policy["signer_workflow"] == ".github/workflows/phase8-engineering-witness.yml"
    )
    assert (
        policy["caller_workflow"] == ".github/workflows/phase8-engineering-caller.yml"
    )
    assert policy["event"] == "push"
    assert policy["runner_environment"] == "github-hosted"
    assert policy["repository_identity"] == {
        "id": "1282437633",
        "name": "Jupiter363/AfterSaleFlow-Agent",
        "node_id": "R_kgDOTHByAQ",
        "visibility": "public",
    }
    assert policy["run"] == {
        "artifact_count": 8,
        "attempt": 1,
        "job_names": list(verifier.EXPECTED_JOB_NAMES),
        "successful_run_count": 1,
        "total_run_count": 1,
    }
    assert policy["artifact"]["count"] == 8
    gates = policy["github_cli"]["capability_gates"]
    assert {tuple(gate["argv"]) for gate in gates}.isdisjoint(
        {("run", "download", "--help")}
    )
    assert next(gate for gate in gates if gate["argv"] == ["api", "--help"])[
        "required_tokens"
    ] == ["--method", "--raw-field"]
    assert policy["github_cli"]["version"] == "2.93.0"
    assert policy["github_cli"]["platforms"]["win32"] == {
        "authenticode_publisher": (
            'CN="GitHub, Inc.", O="GitHub, Inc.", L=San Francisco, S=California, C=US'
        ),
        "executable": r"C:\Program Files\GitHub CLI\gh.exe",
        "sha256": "4cb5ff2afa351c890ae55b2f1fbf4f4a43f6a1e0ab20dfb0567a593bf9cee9ff",
        "system_root": r"C:\Windows",
    }
    assert policy["attestation"] == {
        "count": 1,
        "oidc_issuer": "https://token.actions.githubusercontent.com",
        "predicate_type": "https://slsa.dev/provenance/v1",
        "sigstore_instance": "public-good",
    }
    assert policy["authority"]["production_authority"] is False
    assert policy["authority"]["production_promotion"] == "FORBIDDEN"
    assert policy["trusted_sha_roles"] == {
        "candidate_sha": "SIGSTORE_SOURCE_DIGEST_AND_CALLER_WORKFLOW_BLOB",
        "trusted_code_sha": "WITNESS_MANIFEST_EXACT_BINDING",
        "trusted_workflow_sha": "SIGSTORE_SIGNER_DIGEST",
    }
    assert all(
        policy["authority"][gate] == "PENDING_PROMOTION"
        for gate in ("MIG-006", "MIG-007", "MIG-008")
    )


def _preflight_process_result(
    policy: dict[str, Any],
    argv: tuple[str, ...],
    *,
    version: str = "2.93.0",
    omit_token: str | None = None,
) -> verifier.CommandResult:
    if argv == ("--version",):
        output = (
            f"gh version {version} (2026-05-27)\n"
            f"https://github.com/cli/cli/releases/tag/v{version}\n"
        ).encode("ascii")
    else:
        tokens = {
            token
            for gate in policy["github_cli"]["capability_gates"]
            for token in gate["required_tokens"]
            if token != omit_token
        }
        output = "\n".join(f"  {token} value" for token in sorted(tokens)).encode(
            "ascii"
        )
    return verifier.CommandResult(0, output, b"")


def test_preflight_ignores_path_shims_and_uses_only_policy_absolute_paths(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    shim = tmp_path / "gh.exe"
    shim.write_bytes(b"attacker")
    monkeypatch.setenv("PATH", str(tmp_path))
    monkeypatch.setattr(verifier.sys, "platform", "win32")
    policy = copy.deepcopy(verifier.EXPECTED_POLICY)
    loaded: list[str] = []

    def load(
        specification: dict[str, Any],
        *,
        context: str,
        version: str = "",
        require_single_link: bool = True,
    ) -> verifier.TrustedExecutable:
        loaded.append(specification["executable"])
        return verifier.TrustedExecutable(
            Path(specification["executable"]),
            (1, 2, 3, 4, 1),
            specification["sha256"],
            version,
            require_single_link,
        )

    monkeypatch.setattr(verifier, "_load_trusted_executable", load)
    monkeypatch.setattr(verifier, "_verify_authenticode", lambda *args: None)
    monkeypatch.setattr(
        verifier,
        "_run_trusted_process",
        lambda executable, argv, **kwargs: _preflight_process_result(policy, argv),
    )
    trusted = verifier._preflight_gh(policy, Path("C:/external-state"))
    assert trusted.path == Path(r"C:\Program Files\GitHub CLI\gh.exe")
    assert str(shim) not in loaded
    assert loaded == [
        r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
        r"C:\Program Files\GitHub CLI\gh.exe",
    ]
    source = Path(verifier.__file__).read_text(encoding="utf-8")
    assert "shutil.which" not in source
    assert not hasattr(verifier, "GH_EXECUTABLE")


def test_preflight_rejects_wrong_version_before_remote_calls(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(verifier.sys, "platform", "win32")
    policy = copy.deepcopy(verifier.EXPECTED_POLICY)
    monkeypatch.setattr(
        verifier,
        "_load_trusted_executable",
        lambda specification, context, version="", require_single_link=True: (
            verifier.TrustedExecutable(
                Path(specification["executable"]),
                (1, 2, 3, 4, 1),
                specification["sha256"],
                version,
                require_single_link,
            )
        ),
    )
    monkeypatch.setattr(verifier, "_verify_authenticode", lambda *args: None)
    monkeypatch.setattr(
        verifier,
        "_run_trusted_process",
        lambda executable, argv, **kwargs: _preflight_process_result(
            policy, argv, version="2.92.0"
        ),
    )
    with pytest.raises(verifier.GitHubAttestationError, match="GH_VERSION_MISMATCH"):
        verifier._preflight_gh(policy, Path("C:/external-state"))


def test_preflight_rejects_missing_required_capability(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(verifier.sys, "platform", "win32")
    policy = copy.deepcopy(verifier.EXPECTED_POLICY)
    monkeypatch.setattr(
        verifier,
        "_load_trusted_executable",
        lambda specification, context, version="", require_single_link=True: (
            verifier.TrustedExecutable(
                Path(specification["executable"]),
                (1, 2, 3, 4, 1),
                specification["sha256"],
                version,
                require_single_link,
            )
        ),
    )
    monkeypatch.setattr(verifier, "_verify_authenticode", lambda *args: None)
    monkeypatch.setattr(
        verifier,
        "_run_trusted_process",
        lambda executable, argv, **kwargs: _preflight_process_result(
            policy, argv, omit_token="--signer-digest"
        ),
    )
    with pytest.raises(verifier.GitHubAttestationError, match="GH_CAPABILITY_MISSING"):
        verifier._preflight_gh(policy, Path("C:/external-state"))


def test_preflight_is_mandatory_before_the_first_remote_call(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[tuple[str, ...]] = []
    monkeypatch.setattr(
        verifier,
        "_preflight_gh",
        lambda policy, state_home: (_ for _ in ()).throw(
            verifier.GitHubAttestationError("GH_VERSION_MISMATCH: blocked")
        ),
    )
    monkeypatch.setattr(
        verifier,
        "_execute_gh",
        lambda argv, cwd=None, executable=None: calls.append(argv),
    )
    (tmp_path / "external").mkdir()
    with pytest.raises(verifier.GitHubAttestationError, match="GH_VERSION_MISMATCH"):
        verifier.verify_github_attestation(
            candidate_sha=CANDIDATE,
            trusted_code_sha=TRUSTED_CODE,
            trusted_workflow_sha=TRUSTED_WORKFLOW,
            artifact=verifier.SUBJECT_FILENAME,
            run_dir=(tmp_path / "external" / "run").absolute(),
        )
    assert calls == []
    assert (tmp_path / "external" / "run" / "gh-state").is_dir()
    assert not (verifier.ROOT / ".local").exists()


def test_authenticated_executable_replacement_is_rejected_before_spawn(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    executable = (tmp_path / "trusted-gh.exe").absolute()
    executable.write_bytes(b"trusted-gh-binary")
    digest = hashlib.sha256(executable.read_bytes()).hexdigest()
    trusted = verifier._load_trusted_executable(
        {"executable": str(executable), "sha256": digest},
        context="test gh",
        version="2.93.0",
    )
    state_home = (tmp_path / "state").absolute()
    state_home.mkdir()
    trusted = verifier.TrustedExecutable(
        trusted.path,
        trusted.identity,
        trusted.sha256,
        trusted.version,
        trusted.single_link_required,
        state_home,
    )
    executable.write_bytes(b"replaced-gh-binary")
    spawned = False

    def forbidden_spawn(*args: Any, **kwargs: Any) -> Any:
        nonlocal spawned
        spawned = True
        raise AssertionError("replacement must be rejected before process creation")

    monkeypatch.setattr(verifier.subprocess, "run", forbidden_spawn)
    with pytest.raises(verifier.GitHubAttestationError, match="GH_EXECUTABLE_CHANGED"):
        verifier._execute_gh(("run", "list"), executable=trusted)
    assert spawned is False


def test_authenticated_executable_replacement_during_spawn_is_rejected_afterward(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    executable = (tmp_path / "trusted-gh.exe").absolute()
    executable.write_bytes(b"trusted-gh-binary")
    state_home = (tmp_path / "state").absolute()
    state_home.mkdir()
    digest = hashlib.sha256(executable.read_bytes()).hexdigest()
    loaded = verifier._load_trusted_executable(
        {"executable": str(executable), "sha256": digest},
        context="test gh",
        version="2.93.0",
    )
    trusted = verifier.TrustedExecutable(
        loaded.path,
        loaded.identity,
        loaded.sha256,
        loaded.version,
        loaded.single_link_required,
        state_home,
    )

    def replace_during_spawn(*args: Any, **kwargs: Any) -> Any:
        executable.write_bytes(b"replaced-gh-binary")
        return verifier.subprocess.CompletedProcess(args[0], 0, b"{}", b"")

    monkeypatch.setattr(verifier.subprocess, "run", replace_during_spawn)
    with pytest.raises(verifier.GitHubAttestationError, match="GH_EXECUTABLE_CHANGED"):
        verifier._execute_gh(("run", "list"), executable=trusted)


def test_token_process_rejects_state_ancestor_replacement_during_spawn(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    executable_path = (tmp_path / "trusted-gh.exe").absolute()
    executable_path.write_bytes(b"trusted-gh-binary")
    loaded = verifier._load_trusted_executable(
        {
            "executable": str(executable_path),
            "sha256": hashlib.sha256(executable_path.read_bytes()).hexdigest(),
        },
        context="test gh",
        version="2.93.0",
    )
    ancestor = (tmp_path / "external-state").absolute()
    state_home = ancestor / "state"
    state_home.mkdir(parents=True)
    trusted = verifier.TrustedExecutable(
        loaded.path,
        loaded.identity,
        loaded.sha256,
        loaded.version,
        loaded.single_link_required,
        state_home,
    )

    replacement_blocked = False

    def replace_ancestor(*args: Any, **kwargs: Any) -> Any:
        nonlocal replacement_blocked
        try:
            ancestor.rename(tmp_path / "moved-state")
        except OSError:
            replacement_blocked = True
        else:  # pragma: no cover - the post-spawn chain check is the fallback
            state_home.mkdir(parents=True)
        return verifier.subprocess.CompletedProcess(args[0], 0, b"{}", b"")

    monkeypatch.setattr(verifier.subprocess, "run", replace_ancestor)
    result = verifier._execute_gh(("run", "list"), executable=trusted)
    assert result.returncode == 0
    assert replacement_blocked is True


def test_authenticode_publisher_is_exact_and_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    executable = verifier.TrustedExecutable(
        Path("C:/trusted/gh.exe"), (1, 2, 3, 4, 1), "1" * 64, "2.93.0"
    )
    inspector = verifier.TrustedExecutable(
        Path("C:/trusted/powershell.exe"), (5, 6, 7, 8, 1), "2" * 64, ""
    )
    monkeypatch.setattr(verifier.sys, "platform", "win32")
    monkeypatch.setattr(
        verifier,
        "_run_trusted_process",
        lambda *args, **kwargs: verifier.CommandResult(
            0, b'{"publisher":"CN=Attacker","status":"Valid"}', b""
        ),
    )
    monkeypatch.setattr(verifier, "_assert_trusted_executable", lambda value: None)
    with pytest.raises(
        verifier.GitHubAttestationError, match="GH_AUTHENTICODE_INVALID"
    ):
        verifier._verify_authenticode(
            executable, inspector, "CN=GitHub, Inc.", Path("C:/external-state")
        )


def test_argv_freezes_all_remote_filters_and_never_uses_a_shell() -> None:
    run_list = verifier.build_run_list_argv(CANDIDATE)
    for pair in (
        ("--repo", verifier.REPOSITORY),
        ("--workflow", verifier.CALLER_WORKFLOW),
        ("--branch", verifier.BRANCH.removeprefix("refs/heads/")),
        ("--event", "push"),
        ("--commit", CANDIDATE),
    ):
        index = run_list.index(pair[0])
        assert run_list[index : index + 2] == pair
    assert "--status" not in run_list
    assert run_list[run_list.index("--limit") : run_list.index("--limit") + 2] == (
        "--limit",
        "2",
    )
    artifact_list = verifier.build_artifact_list_argv(RUN_ID)
    assert artifact_list[-2:] == ("-f", "per_page=9")
    artifact_id = _artifact()["artifacts"][-1]["id"]
    assert verifier.build_artifact_download_argv(artifact_id) == (
        "api",
        "--method",
        "GET",
        f"repos/{verifier.REPOSITORY}/actions/artifacts/{artifact_id}/zip",
    )
    assert not hasattr(verifier, "build_run_download_argv")
    online = verifier.build_online_verify_argv(
        Path("subject.tar"), CANDIDATE, TRUSTED_WORKFLOW
    )
    for pair in (
        ("--repo", verifier.REPOSITORY),
        ("--signer-workflow", f"{verifier.REPOSITORY}/{verifier.SIGNER_WORKFLOW}"),
        ("--signer-digest", TRUSTED_WORKFLOW),
        ("--source-digest", CANDIDATE),
        ("--source-ref", verifier.BRANCH),
        ("--predicate-type", verifier.PREDICATE_TYPE),
        ("--format", "json"),
    ):
        index = online.index(pair[0])
        assert online[index : index + 2] == pair
    assert "--deny-self-hosted-runners" in online
    tree = ast.parse(Path(verifier.__file__).read_text(encoding="utf-8"))
    run_calls = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "subprocess"
        and node.func.attr == "run"
    ]
    assert len(run_calls) == 1
    enclosing = next(
        node
        for node in ast.walk(tree)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name == "_run_trusted_process"
    )
    assert run_calls[0] in set(ast.walk(enclosing))
    shell = next(
        keyword.value for keyword in run_calls[0].keywords if keyword.arg == "shell"
    )
    assert isinstance(shell, ast.Constant) and shell.value is False


@pytest.mark.parametrize("page", (1, 2))
def test_attempt_jobs_argv_freezes_attempt_filter_and_pagination(page: int) -> None:
    assert verifier.build_attempt_jobs_argv(RUN_ID, ATTEMPT, page) == (
        "api",
        "--method",
        "GET",
        (
            f"repos/{verifier.REPOSITORY}/actions/runs/{RUN_ID}/attempts/"
            f"{ATTEMPT}/jobs"
        ),
        "-H",
        "X-GitHub-Api-Version: 2022-11-28",
        "-f",
        "filter=latest",
        "-f",
        "per_page=10",
        "-f",
        f"page={page}",
    )


@pytest.mark.parametrize("page", (-1, 0, 3))
def test_attempt_jobs_argv_rejects_pages_outside_exact_two_page_window(
    page: int,
) -> None:
    with pytest.raises(verifier.GitHubAttestationError, match="INTEGER_INVALID"):
        verifier.build_attempt_jobs_argv(RUN_ID, ATTEMPT, page)


def test_success_binds_unique_run_artifact_online_offline_and_ledger(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    _install(monkeypatch, fake)
    receipt = _verify(tmp_path)
    expected_composite = verifier.calculate_attestation_composite_sha256(
        candidate_sha=CANDIDATE,
        candidate_tree_sha=TREE,
        accepted_a8_sha=verifier.ACCEPTED_A8,
        scope_inventory_sha256="1" * 64,
        command_contract_payload_sha256="f" * 64,
        artifact_subject_sha256=SUBJECT_DIGEST,
        caller_workflow_file_sha256=verifier._expected_caller_workflow_binding(
            TRUSTED_WORKFLOW
        )["file_sha256"],
        caller_workflow_git_blob_sha1=verifier._expected_caller_workflow_binding(
            TRUSTED_WORKFLOW
        )["git_blob_sha1"],
        command_artifact_set_sha256=COMMAND_ARTIFACT_SET_SHA256,
        trusted_code_sha=TRUSTED_CODE,
        trusted_code_tree_sha=TRUSTED_CODE_TREE,
        trusted_transition_sha256=TRUSTED_TRANSITION_SHA256,
        trusted_workflow_sha=TRUSTED_WORKFLOW,
        trusted_workflow_tree_sha=TRUSTED_WORKFLOW_TREE,
        run_id=RUN_ID,
        run_attempt=ATTEMPT,
    )
    expected_key = hashlib.sha256(
        (
            f"{CANDIDATE}|{TRUSTED_CODE}|{TRUSTED_WORKFLOW}|"
            f"{RUN_ID}|{ATTEMPT}|{SUBJECT_DIGEST}|{expected_composite}"
        ).encode("ascii")
    ).hexdigest()
    assert receipt["acceptance_key"] == expected_key
    assert receipt["attestation_composite_sha256"] == expected_composite
    assert receipt["accepted"] is True
    assert receipt["candidate_sha"] == CANDIDATE
    assert receipt["trusted_code_sha"] == TRUSTED_CODE
    assert receipt["trusted_workflow_sha"] == TRUSTED_WORKFLOW
    assert receipt["candidate_tree_sha"] == TREE
    assert receipt["repository_id"] == verifier.REPOSITORY_ID
    assert receipt["command_artifact_set_sha256"] == COMMAND_ARTIFACT_SET_SHA256
    assert receipt["artifact"]["sha256"] == SUBJECT_DIGEST
    assert receipt["attestation"]["online_verified"] is True
    assert receipt["attestation"]["offline_verified"] is True
    assert receipt["attestation"]["predicate_authority"] == verifier.PREDICATE_AUTHORITY
    assert receipt["production_authority"] is False
    assert receipt["production_promotion"] == "FORBIDDEN"
    assert all(
        receipt[gate] == "PENDING_PROMOTION"
        for gate in ("MIG-006", "MIG-007", "MIG-008")
    )
    assert sum(call[:2] == ("attestation", "verify") for call in fake.calls) == 2
    assert [
        call
        for call in fake.calls
        if call[:4]
        == (
            "api",
            "--method",
            "GET",
            (
                f"repos/{verifier.REPOSITORY}/actions/runs/{RUN_ID}/attempts/"
                f"{ATTEMPT}/jobs"
            ),
        )
    ] == [
        verifier.build_attempt_jobs_argv(RUN_ID, ATTEMPT, 1),
        verifier.build_attempt_jobs_argv(RUN_ID, ATTEMPT, 2),
    ]
    selected_artifact_id = fake.artifacts["artifacts"][-1]["id"]
    assert (
        "api",
        "--method",
        "GET",
        f"repos/{verifier.REPOSITORY}/actions/artifacts/{selected_artifact_id}/zip",
    ) in fake.calls
    assert not any(call[:2] == ("run", "download") for call in fake.calls)
    ledger = tmp_path / "external" / "run" / "acceptance-ledger.jsonl"
    lines = ledger.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 2
    assert json.loads(lines[0])["record"] == "CLAIMED"
    assert json.loads(lines[1])["acceptance_key"] == expected_key
    assert json.loads(lines[1])["attestation_composite_sha256"] == expected_composite
    assert os.stat(ledger).st_nlink == 1


def test_same_name_rerun_cannot_substitute_the_selected_artifact_id(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    fake.same_name_rerun_subject = b"attacker-controlled same-name rerun artifact"
    _install(monkeypatch, fake)

    receipt = _verify(tmp_path)

    selected_id = fake.artifacts["artifacts"][-1]["id"]
    assert receipt["artifact"]["id"] == selected_id
    assert any(
        call == verifier.build_artifact_download_argv(selected_id)
        for call in fake.calls
    )
    assert not any(call[:2] == ("run", "download") for call in fake.calls)


def test_exact_id_archive_digest_and_single_member_topology_are_required(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    fake.archive_payload = b"not-the-selected-artifact"
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError,
        match="ARTIFACT_ARCHIVE_DIGEST_MISMATCH",
    ):
        _verify(tmp_path)

    fake = FakeGitHub()
    output = io.BytesIO()
    with zipfile.ZipFile(output, mode="w") as archive:
        archive.writestr(verifier.SUBJECT_FILENAME, SUBJECT)
        archive.writestr("substituted.txt", b"substitution")
    fake.archive_payload = output.getvalue()
    fake.artifacts["artifacts"][-1]["digest"] = (
        "sha256:" + hashlib.sha256(fake.archive_payload).hexdigest()
    )
    _install(monkeypatch, fake)
    topology_root = tmp_path / "topology"
    topology_root.mkdir()
    with pytest.raises(verifier.GitHubAttestationError, match="DOWNLOAD_AMBIGUOUS"):
        _verify(topology_root)


@pytest.mark.parametrize("count", (0, 2))
def test_exactly_one_successful_run_is_required(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, count: int
) -> None:
    fake = FakeGitHub()
    fake.run_list = [_run() for _ in range(count)]
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="RUN_CARDINALITY_INVALID"
    ):
        _verify(tmp_path)


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("headSha", "c" * 40),
        ("headBranch", "main"),
        ("event", "workflow_dispatch"),
        ("conclusion", "failure"),
        ("status", "in_progress"),
        ("attempt", 2),
        ("updatedAt", "2026-01-01T00:00:00Z"),
        ("createdAt", "2026-07-27T00:00:00Z"),
    ),
)
def test_run_binding_attempt_and_timestamp_drift_are_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, field: str, value: Any
) -> None:
    fake = FakeGitHub()
    fake.run_list[0][field] = value
    _install(monkeypatch, fake)
    with pytest.raises(verifier.GitHubAttestationError):
        _verify(tmp_path)


def test_attempt_jobs_require_github_hosted_runner_identity(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    fake.attempt_jobs_pages[1]["jobs"][1]["runner_group_name"] = "Default"
    fake.attempt_jobs_pages[1]["jobs"][1]["runner_name"] = "self-hosted-01"
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="RUNNER_ENVIRONMENT_INVALID"
    ):
        _verify(tmp_path)


@pytest.mark.parametrize(
    "case",
    (
        "page-1-total-count",
        "page-2-total-count",
        "page-1-job-count",
        "page-2-sentinel-not-empty",
    ),
)
def test_attempt_job_pages_require_exact_total_and_empty_sentinel(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, case: str
) -> None:
    fake = FakeGitHub()
    if case == "page-1-total-count":
        fake.attempt_jobs_pages[1]["total_count"] = 9
    elif case == "page-2-total-count":
        fake.attempt_jobs_pages[2]["total_count"] = 9
    elif case == "page-1-job-count":
        fake.attempt_jobs_pages[1]["jobs"].pop()
    else:
        fake.attempt_jobs_pages[2]["jobs"].append(
            copy.deepcopy(fake.attempt_jobs_pages[1]["jobs"][0])
        )
    _install(monkeypatch, fake)

    with pytest.raises(
        verifier.GitHubAttestationError, match="JOB_CARDINALITY_INVALID"
    ):
        _verify(tmp_path)


@pytest.mark.parametrize(
    "case",
    (
        "duplicate-rest-id",
        "duplicate-rest-name",
        "run-view-missing-and-extra-name",
        "rest-missing-and-extra-name",
    ),
)
def test_job_ids_and_expected_names_must_form_exact_unique_sets(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, case: str
) -> None:
    fake = FakeGitHub()
    if case == "duplicate-rest-id":
        fake.attempt_jobs_pages[1]["jobs"][1]["id"] = fake.attempt_jobs_pages[1][
            "jobs"
        ][0]["id"]
    elif case == "duplicate-rest-name":
        fake.attempt_jobs_pages[1]["jobs"][1]["name"] = fake.attempt_jobs_pages[1][
            "jobs"
        ][0]["name"]
    elif case == "run-view-missing-and-extra-name":
        fake.run_view["jobs"][0]["name"] = "witness / unexpected"
    else:
        fake.attempt_jobs_pages[1]["jobs"][0]["name"] = "witness / unexpected"
    _install(monkeypatch, fake)

    with pytest.raises(
        verifier.GitHubAttestationError,
        match=r"JOB_(?:CROSS_BINDING|SET)_INVALID",
    ):
        _verify(tmp_path)


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("run_id", RUN_ID + 1),
        ("run_attempt", ATTEMPT + 1),
        ("head_sha", "9" * 40),
        ("head_branch", "main"),
        ("workflow_name", "Attacker workflow"),
    ),
)
def test_attempt_jobs_must_bind_selected_run_attempt_commit_branch_and_workflow(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    field: str,
    value: Any,
) -> None:
    fake = FakeGitHub()
    fake.attempt_jobs_pages[1]["jobs"][0][field] = value
    _install(monkeypatch, fake)

    with pytest.raises(
        verifier.GitHubAttestationError, match="JOB_BINDING_INVALID"
    ):
        _verify(tmp_path)


@pytest.mark.parametrize(
    ("field", "value", "error"),
    (
        ("runner_id", None, "INTEGER_INVALID"),
        ("runner_id", 0, "INTEGER_INVALID"),
        ("runner_group_id", None, "RUNNER_ENVIRONMENT_INVALID"),
        ("runner_group_id", 1, "RUNNER_ENVIRONMENT_INVALID"),
        ("runner_group_name", None, "RUNNER_ENVIRONMENT_INVALID"),
        ("runner_group_name", "Default", "RUNNER_ENVIRONMENT_INVALID"),
        ("runner_name", None, "RUNNER_ENVIRONMENT_INVALID"),
        ("runner_name", "self-hosted-01", "RUNNER_ENVIRONMENT_INVALID"),
        ("labels", None, "RUNNER_ENVIRONMENT_INVALID"),
        ("labels", ["self-hosted"], "RUNNER_ENVIRONMENT_INVALID"),
    ),
)
def test_null_or_non_github_hosted_runner_fields_are_rejected(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    field: str,
    value: Any,
    error: str,
) -> None:
    fake = FakeGitHub()
    fake.attempt_jobs_pages[1]["jobs"][0][field] = value
    _install(monkeypatch, fake)

    with pytest.raises(verifier.GitHubAttestationError, match=error):
        _verify(tmp_path)


@pytest.mark.parametrize(
    ("case", "error"),
    (
        ("id", "JOB_CROSS_BINDING_INVALID"),
        ("name", "JOB_CROSS_BINDING_INVALID"),
        ("status", "JOB_STATUS_INVALID"),
        ("time", "JOB_CROSS_BINDING_INVALID"),
        ("url", "JOB_BINDING_INVALID"),
    ),
)
def test_run_view_and_rest_jobs_must_match_id_name_status_time_and_url(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, case: str, error: str
) -> None:
    fake = FakeGitHub()
    job = fake.attempt_jobs_pages[1]["jobs"][0]
    if case == "id":
        new_id = 999
        job["id"] = new_id
        job["check_run_url"] = (
            f"https://api.github.com/repos/{verifier.REPOSITORY}/check-runs/{new_id}"
        )
        job["html_url"] = (
            f"https://github.com/{verifier.REPOSITORY}/actions/runs/"
            f"{RUN_ID}/job/{new_id}"
        )
        job["url"] = (
            f"https://api.github.com/repos/{verifier.REPOSITORY}/actions/jobs/"
            f"{new_id}"
        )
    elif case == "name":
        job["name"] = "witness / unexpected"
    elif case == "status":
        job["status"] = "in_progress"
    elif case == "time":
        job["started_at"] = "2026-07-26T01:01:01Z"
    else:
        job["html_url"] = (
            f"https://github.com/{verifier.REPOSITORY}/actions/runs/"
            f"{RUN_ID}/job/999"
        )
    _install(monkeypatch, fake)

    with pytest.raises(verifier.GitHubAttestationError, match=error):
        _verify(tmp_path)


@pytest.mark.parametrize(
    "case",
    ("run-view-job", "rest-job", "run-view-step", "rest-step"),
)
def test_malformed_job_timestamps_are_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, case: str
) -> None:
    fake = FakeGitHub()
    if case == "run-view-job":
        fake.run_view["jobs"][0]["startedAt"] = "not-a-time"
    elif case == "rest-job":
        fake.attempt_jobs_pages[1]["jobs"][0]["created_at"] = "not-a-time"
    elif case == "run-view-step":
        fake.run_view["jobs"][0]["steps"][0]["startedAt"] = "not-a-time"
    else:
        fake.attempt_jobs_pages[1]["jobs"][0]["steps"][0][
            "started_at"
        ] = "not-a-time"
    _install(monkeypatch, fake)

    with pytest.raises(verifier.GitHubAttestationError, match="TIMESTAMP_INVALID"):
        _verify(tmp_path)


def test_irrelevant_rest_job_fields_are_accepted(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    fake.attempt_jobs_pages[1]["jobs"][0]["irrelevant_future_field"] = {
        "ignored": True
    }
    _install(monkeypatch, fake)

    assert _verify(tmp_path)["accepted"] is True


@pytest.mark.parametrize("count", (0, 7, 9))
def test_exactly_eight_artifacts_are_required(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, count: int
) -> None:
    fake = FakeGitHub()
    artifact = _artifact()["artifacts"][0]
    fake.artifacts = {
        "artifacts": [copy.deepcopy(artifact) for _ in range(count)],
        "total_count": count,
    }
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="ARTIFACT_CARDINALITY_INVALID"
    ):
        _verify(tmp_path)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda artifacts: artifacts[0].__setitem__("name", "phase8-raw-evil"),
        lambda artifacts: artifacts[5].__setitem__(
            "name",
            verifier.RUNTIME_IMAGE_ARTIFACT_NAME_TEMPLATE.format(
                run_id=RUN_ID, run_attempt=ATTEMPT, archive_sha256="g" * 64
            ),
        ),
        lambda artifacts: artifacts[5].__setitem__("name", artifacts[6]["name"]),
        lambda artifacts: artifacts[0].__setitem__("id", artifacts[1]["id"]),
        lambda artifacts: artifacts[0].__setitem__("node_id", artifacts[1]["node_id"]),
        lambda artifacts: artifacts[0].__setitem__("node_id", "invalid/node/id"),
        lambda artifacts: artifacts[0].__setitem__("digest", "sha512:" + "1" * 64),
        lambda artifacts: artifacts[0].__setitem__("digest", "sha256:" + "A" * 64),
        lambda artifacts: artifacts[0].pop("digest"),
        lambda artifacts: artifacts[0].pop("node_id"),
        lambda artifacts: artifacts[0].pop("url"),
        lambda artifacts: artifacts[0].__setitem__(
            "url",
            f"https://api.github.com/repos/{verifier.REPOSITORY}/actions/artifacts/1",
        ),
        lambda artifacts: artifacts[0].__setitem__(
            "archive_download_url",
            f"https://api.github.com/repos/{verifier.REPOSITORY}/actions/artifacts/1/zip",
        ),
        lambda artifacts: artifacts[0]["workflow_run"].__setitem__("id", RUN_ID + 1),
        lambda artifacts: artifacts[0]["workflow_run"].__setitem__(
            "repository_id", int(verifier.REPOSITORY_ID) + 1
        ),
        lambda artifacts: artifacts[0]["workflow_run"].__setitem__(
            "head_repository_id", int(verifier.REPOSITORY_ID) + 1
        ),
        lambda artifacts: artifacts[0]["workflow_run"].__setitem__(
            "head_branch", "main"
        ),
        lambda artifacts: artifacts[0]["workflow_run"].__setitem__(
            "head_sha", "f" * 40
        ),
        lambda artifacts: artifacts[0]["workflow_run"].pop("repository_id"),
        lambda artifacts: artifacts[0].__setitem__("unexpected", "field"),
        lambda artifacts: artifacts[0]["workflow_run"].__setitem__(
            "unexpected", "field"
        ),
    ),
)
def test_exact_artifact_topology_and_identity_are_required(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    mutation: Callable[[list[dict[str, Any]]], None],
) -> None:
    fake = FakeGitHub()
    mutation(fake.artifacts["artifacts"])
    _install(monkeypatch, fake)
    with pytest.raises(verifier.GitHubAttestationError):
        _verify(tmp_path)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda item: item["verificationResult"]["statement"].__setitem__(
            "subject", item["verificationResult"]["statement"]["subject"] * 2
        ),
        lambda item: item["verificationResult"]["statement"]["subject"][0][
            "digest"
        ].__setitem__("sha256", "c" * 64),
        lambda item: item["verificationResult"]["statement"].__setitem__(
            "predicateType", "https://example.invalid"
        ),
        lambda item: item["verificationResult"].__setitem__("verifiedTimestamps", []),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "githubWorkflowRepository", "attacker/repo"
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "buildSignerURI",
            "https://github.com/attacker/repo/.github/workflows/evil.yml@refs/heads/main",
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "buildConfigURI",
            "https://github.com/attacker/repo/.github/workflows/evil.yml@refs/heads/main",
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "sourceRepositoryDigest", "c" * 40
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "sourceRepositoryIdentifier", "555555555"
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "sourceRepositoryVisibilityAtSigning", "private"
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "buildSignerDigest", "9" * 40
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "sourceRepositoryRef", "refs/heads/main"
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "githubWorkflowTrigger", "workflow_dispatch"
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "runInvocationURI",
            f"https://github.com/{verifier.REPOSITORY}/actions/runs/{RUN_ID}/attempts/2",
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "runnerEnvironment", "self-hosted"
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "issuer", "https://issuer.invalid"
        ),
        lambda item: item["verificationResult"]["signature"]["certificate"].__setitem__(
            "certificateIssuer", "CN=private"
        ),
    ),
)
def test_attestation_identity_cardinality_and_timestamp_are_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    mutation: Callable[[dict[str, Any]], None],
) -> None:
    fake = FakeGitHub()
    mutation(fake.online[0])
    _install(monkeypatch, fake)
    with pytest.raises(verifier.GitHubAttestationError):
        _verify(tmp_path)


@pytest.mark.parametrize("count", (0, 2))
def test_exactly_one_attestation_is_required(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, count: int
) -> None:
    fake = FakeGitHub()
    fake.online = [_verification()[0] for _ in range(count)]
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="ATTESTATION_CARDINALITY_INVALID"
    ):
        _verify(tmp_path)


def test_builder_predicate_cannot_grant_production_authority(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    forged = {
        "authority_ceiling": "PRODUCTION",
        "MIG-006": "PASS",
        "MIG-007": "PASS",
        "MIG-008": "PASS",
        "production_authority": True,
    }
    fake.online = _verification(forged)
    fake.offline = _verification(forged)
    _install(monkeypatch, fake)
    receipt = _verify(tmp_path)
    assert "predicate" not in receipt["attestation"]
    assert (
        receipt["attestation"]["predicate_sha256"]
        == hashlib.sha256(_json(forged)).hexdigest()
    )
    assert receipt["attestation"]["predicate_authority"] == verifier.PREDICATE_AUTHORITY
    assert receipt["authority_ceiling"] == verifier.AUTHORITY_CEILING
    assert receipt["production_authority"] is False


def test_trusted_code_sha_is_independently_bound_inside_the_attested_tar(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="WITNESS_MANIFEST_BINDING_MISMATCH"
    ):
        _verify(tmp_path, trusted_code_sha="9" * 40)
    assert not any(call[:2] == ("attestation", "verify") for call in fake.calls)


def _substitute_shared_build_index(index: list[dict[str, Any]]) -> None:
    item = next(entry for entry in index if entry["path"] == SHARED_BUILD_PATH)
    item["sha256"] = "9" * 64


@pytest.mark.parametrize(
    ("subject", "error"),
    (
        (
            _subject_bytes(
                members=[
                    member
                    for member in DEFAULT_WITNESS_MEMBERS
                    if member[0] != SHARED_BUILD_PATH
                ]
            ),
            "WITNESS_SHARED_RUNTIME_INVALID",
        ),
        (
            _subject_bytes(
                members=[
                    member
                    for member in DEFAULT_WITNESS_MEMBERS
                    if member[0] != SHARED_ARCHIVE_INDEX_PATH
                ]
            ),
            "WITNESS_SHARED_RUNTIME_INVALID",
        ),
        (
            _subject_bytes(
                members=[
                    member
                    for member in DEFAULT_WITNESS_MEMBERS
                    if member[0] != EXECUTION_SET_PATH
                ]
            ),
            "WITNESS_SHARED_RUNTIME_INVALID",
        ),
        (
            _subject_bytes(
                members=[
                    member
                    for member in DEFAULT_WITNESS_MEMBERS
                    if member[0] != SHARED_OBSERVATION_PATH
                ]
            ),
            "WITNESS_SHARED_RUNTIME_INVALID",
        ),
        (
            _subject_bytes(
                members=[
                    *DEFAULT_WITNESS_MEMBERS,
                    ("runtime/shared/extra.json", b"{}\n"),
                ]
            ),
            "WITNESS_TAR_INVALID",
        ),
        (
            _subject_bytes(
                members=[
                    ("runtime/shared/observation.json", payload)
                    if path == SHARED_OBSERVATION_PATH
                    else (path, payload)
                    for path, payload in DEFAULT_WITNESS_MEMBERS
                ]
            ),
            "WITNESS_TAR_INVALID",
        ),
        (
            _subject_bytes(index_mutation=_substitute_shared_build_index),
            "WITNESS_INDEX_INVALID",
        ),
        (
            _subject_bytes(
                members=[
                    *DEFAULT_WITNESS_MEMBERS,
                    (SHARED_BUILD_PATH, SHARED_BUILD),
                ]
            ),
            "WITNESS_TAR_INVALID",
        ),
    ),
    ids=(
        "missing-build",
        "missing-observation",
        "missing-archive-index",
        "missing-execution-set",
        "extra-shared",
        "renamed-observation",
        "substituted-index",
        "duplicate-build",
    ),
)
def test_shared_runtime_members_are_exact_once_and_index_bound(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    subject: bytes,
    error: str,
) -> None:
    fake = FakeGitHub()
    fake.subject = subject
    _install(monkeypatch, fake)
    with pytest.raises(verifier.GitHubAttestationError, match=error):
        _verify(tmp_path)
    assert not any(call[:2] == ("attestation", "verify") for call in fake.calls)


def test_required_runtime_member_allowlist_is_closed_world() -> None:
    assert verifier.REQUIRED_RUNTIME_MEMBERS == {
        EXECUTION_SET_PATH,
        SHARED_ARCHIVE_INDEX_PATH,
        SHARED_BUILD_PATH,
        SHARED_OBSERVATION_PATH,
    }
    assert all(
        verifier.WITNESS_MEMBER.fullmatch(path)
        for path in verifier.REQUIRED_RUNTIME_MEMBERS
    )
    assert verifier.WITNESS_MEMBER.fullmatch("runtime/shared/failure.json") is None
    assert (
        verifier.WITNESS_MEMBER.fullmatch("runtime/shared/build-observation.json.bak")
        is None
    )


@pytest.mark.parametrize(
    "subject",
    (
        _subject_bytes(trusted_workflow_sha="9" * 40),
        _subject_bytes(caller_file_sha256="9" * 64),
    ),
    ids=("trusted-workflow-root", "caller-workflow-blob"),
)
def test_manifest_strictly_binds_ceng_caller_blob_and_trusted_workflow_root(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    subject: bytes,
) -> None:
    fake = FakeGitHub()
    fake.subject = subject
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError,
        match="WITNESS_MANIFEST_BINDING_MISMATCH",
    ):
        _verify(tmp_path)
    assert not any(call[:2] == ("attestation", "verify") for call in fake.calls)


@pytest.mark.parametrize(
    "mutation",
    (
        "transition-hash",
        "candidate-sha",
        "candidate-tree",
        "trusted-code-sha",
        "trusted-code-tree",
        "trusted-workflow-sha",
        "trusted-workflow-tree",
        "top-level-workflow-tree",
        "workflow-delta-path",
        "candidate-delta-mode",
        "candidate-delta-extra",
    ),
)
def test_manifest_trusted_transition_is_exact_and_canonically_bound(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    mutation: str,
) -> None:
    def mutate(manifest: dict[str, Any]) -> None:
        transition = manifest["trusted_transition"]
        if mutation == "transition-hash":
            manifest["trusted_transition_sha256"] = "9" * 64
            return
        if mutation == "candidate-sha":
            transition["candidate_sha"] = "9" * 40
        elif mutation == "candidate-tree":
            transition["candidate_tree_sha"] = "9" * 40
        elif mutation == "trusted-code-sha":
            transition["trusted_code_sha"] = "9" * 40
        elif mutation == "trusted-code-tree":
            transition["trusted_code_tree_sha"] = "9" * 40
        elif mutation == "trusted-workflow-sha":
            transition["trusted_workflow_sha"] = "9" * 40
        elif mutation == "trusted-workflow-tree":
            transition["trusted_workflow_tree_sha"] = "9" * 40
        elif mutation == "top-level-workflow-tree":
            manifest["trusted_workflow_tree_sha"] = "9" * 40
        elif mutation == "workflow-delta-path":
            transition["trusted_code_to_workflow_additions"][0]["path"] = (
                "plans/substituted.md"
            )
        elif mutation == "candidate-delta-mode":
            transition["trusted_workflow_to_candidate_additions"][0]["mode"] = "100755"
        else:
            transition["trusted_workflow_to_candidate_additions"].append(
                copy.deepcopy(transition["trusted_workflow_to_candidate_additions"][0])
            )
        manifest["trusted_transition_sha256"] = hashlib.sha256(
            verifier._canonical_json_bytes(transition)
        ).hexdigest()

    fake = FakeGitHub()
    fake.subject = _subject_bytes(manifest_mutation=mutate)
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError,
        match="WITNESS_(?:MANIFEST_BINDING_MISMATCH|TRANSITION_INVALID)",
    ):
        _verify(tmp_path)
    assert not any(call[:2] == ("attestation", "verify") for call in fake.calls)


@pytest.mark.parametrize(
    ("field", "replacement"),
    (
        ("candidate_sha", "2" * 40),
        ("candidate_tree_sha", "2" * 40),
        ("accepted_a8_sha", "2" * 40),
        ("scope_inventory_sha256", "2" * 64),
        ("command_contract_payload_sha256", "2" * 64),
        ("artifact_subject_sha256", "2" * 64),
        ("caller_workflow_file_sha256", "2" * 64),
        ("caller_workflow_git_blob_sha1", "2" * 40),
        ("command_artifact_set_sha256", "2" * 64),
        ("trusted_code_sha", "2" * 40),
        ("trusted_code_tree_sha", "2" * 40),
        ("trusted_transition_sha256", "2" * 64),
        ("trusted_workflow_sha", "2" * 40),
        ("trusted_workflow_tree_sha", "2" * 40),
        ("run_id", 2),
        ("run_attempt", 2),
    ),
)
def test_composite_rejects_old_witness_or_mixed_candidate_report_and_subject(
    field: str, replacement: Any
) -> None:
    values = {
        "candidate_sha": CANDIDATE,
        "candidate_tree_sha": TREE,
        "accepted_a8_sha": verifier.ACCEPTED_A8,
        "scope_inventory_sha256": "1" * 64,
        "command_contract_payload_sha256": "f" * 64,
        "artifact_subject_sha256": SUBJECT_DIGEST,
        "caller_workflow_file_sha256": verifier._expected_caller_workflow_binding(
            TRUSTED_WORKFLOW
        )["file_sha256"],
        "caller_workflow_git_blob_sha1": verifier._expected_caller_workflow_binding(
            TRUSTED_WORKFLOW
        )["git_blob_sha1"],
        "command_artifact_set_sha256": COMMAND_ARTIFACT_SET_SHA256,
        "trusted_code_sha": TRUSTED_CODE,
        "trusted_code_tree_sha": TRUSTED_CODE_TREE,
        "trusted_transition_sha256": TRUSTED_TRANSITION_SHA256,
        "trusted_workflow_sha": TRUSTED_WORKFLOW,
        "trusted_workflow_tree_sha": TRUSTED_WORKFLOW_TREE,
        "run_id": RUN_ID,
        "run_attempt": ATTEMPT,
    }
    baseline = verifier.calculate_attestation_composite_sha256(**values)
    values[field] = replacement
    assert verifier.calculate_attestation_composite_sha256(**values) != baseline


def test_online_and_offline_verification_must_be_identical(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    fake.offline = _verification({"builder": {"id": "different"}})
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="ONLINE_OFFLINE_MISMATCH"
    ):
        _verify(tmp_path)


@pytest.mark.parametrize("target_flag", ("--bundle", "--custom-trusted-root"))
def test_bundle_and_trusted_root_replacement_during_offline_verify_is_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, target_flag: str
) -> None:
    fake = FakeGitHub()

    def replace(argv: tuple[str, ...]) -> None:
        if argv[:2] == ("attestation", "verify") and "--bundle" in argv:
            path = Path(argv[argv.index(target_flag) + 1])
            path.unlink()
            path.write_bytes(b'{"replacement":true}')

    fake.hook = replace
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="VERIFICATION_INPUT_CHANGED"
    ):
        _verify(tmp_path)


def test_ledger_deletion_mid_run_is_rejected_before_replay_can_continue(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()

    def delete_ledger(argv: tuple[str, ...]) -> None:
        if argv[:2] == ("run", "list"):
            (tmp_path / "external" / "run" / "acceptance-ledger.jsonl").unlink()

    fake.hook = delete_ledger
    _install(monkeypatch, fake)
    with pytest.raises(verifier.GitHubAttestationError, match="LEDGER_CHANGED"):
        _verify(tmp_path)


def test_run_directory_ancestor_replacement_is_rejected_after_remote_call(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    external = tmp_path / "external"

    def replace_ancestor(argv: tuple[str, ...]) -> None:
        if argv[:2] == ("run", "list"):
            external.rename(tmp_path / "moved-run")
            (external / "run").mkdir(parents=True)

    fake.hook = replace_ancestor
    _install(monkeypatch, fake)
    with pytest.raises(
        verifier.GitHubAttestationError, match="DIRECTORY_CHAIN_CHANGED"
    ):
        _verify(tmp_path)


def test_existing_or_relative_run_directory_is_rejected_without_remote_calls(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    _install(monkeypatch, fake)
    existing = tmp_path / "existing"
    existing.mkdir()
    for path in (existing, Path("relative-run")):
        with pytest.raises(verifier.GitHubAttestationError):
            verifier.verify_github_attestation(
                candidate_sha=CANDIDATE,
                trusted_code_sha=TRUSTED_CODE,
                trusted_workflow_sha=TRUSTED_WORKFLOW,
                artifact=verifier.SUBJECT_FILENAME,
                run_dir=path,
            )
    assert fake.calls == []


def test_hardlinked_downloaded_subject_is_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fake = FakeGitHub()
    fake.hardlink_subject = True
    _install(monkeypatch, fake)
    with pytest.raises(verifier.GitHubAttestationError, match="FILE_UNSAFE"):
        _verify(tmp_path)


@pytest.mark.parametrize(
    ("candidate", "trusted_code", "trusted_workflow", "artifact"),
    (
        ("a" * 39, TRUSTED_CODE, TRUSTED_WORKFLOW, verifier.SUBJECT_FILENAME),
        ("A" * 40, TRUSTED_CODE, TRUSTED_WORKFLOW, verifier.SUBJECT_FILENAME),
        (CANDIDATE, "b" * 39, TRUSTED_WORKFLOW, verifier.SUBJECT_FILENAME),
        (CANDIDATE, TRUSTED_CODE, "c" * 39, verifier.SUBJECT_FILENAME),
        (
            CANDIDATE,
            TRUSTED_CODE,
            TRUSTED_WORKFLOW,
            "phase8-engineering-witness.tar; gh run delete 1",
        ),
        (
            CANDIDATE + " --repo attacker/repo",
            TRUSTED_CODE,
            TRUSTED_WORKFLOW,
            verifier.SUBJECT_FILENAME,
        ),
    ),
)
def test_cli_values_cannot_inject_commands(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    candidate: str,
    trusted_code: str,
    trusted_workflow: str,
    artifact: str,
) -> None:
    fake = FakeGitHub()
    _install(monkeypatch, fake)
    with pytest.raises(verifier.GitHubAttestationError):
        verifier.verify_github_attestation(
            candidate_sha=candidate,
            trusted_code_sha=trusted_code,
            trusted_workflow_sha=trusted_workflow,
            artifact=artifact,
            run_dir=(tmp_path / "external" / "run").absolute(),
        )
    assert fake.calls == []


def test_cli_does_not_offer_executable_runner_or_authority_overrides() -> None:
    actions = {action.dest for action in verifier._parser()._actions}
    assert actions == {
        "help",
        "candidate_sha",
        "trusted_code_sha",
        "trusted_workflow_sha",
        "artifact",
        "run_dir",
    }
    assert not ({"gh", "runner", "repo", "branch", "workflow", "authority"} & actions)
