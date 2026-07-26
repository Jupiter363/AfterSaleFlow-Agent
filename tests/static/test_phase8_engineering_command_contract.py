from __future__ import annotations

import ast
import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = (
    ROOT / "contracts/agent-platform/phase8/engineering-candidate-commands.json"
)
MODULE_PATH = ROOT / "scripts/phase8/candidate/command_contract.py"
EXPECTED_MODULE_SOURCE_SHA256 = (
    "edf2b91ae7a9dd15dff3b6b8e4f8a46710fa88a484cda6fc739fa9c30c75becd"
)


def _verify_module_source_bytes(raw: bytes) -> bytes:
    if hashlib.sha256(raw).hexdigest() != EXPECTED_MODULE_SOURCE_SHA256:
        raise RuntimeError(
            "command contract source failed independent SHA-256 verification"
        )
    return raw


def _exec_verified_module_bytes(raw: bytes, *, module_name: str):
    verified = _verify_module_source_bytes(raw)
    spec = importlib.util.spec_from_file_location(module_name, MODULE_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    code = compile(
        verified,
        str(MODULE_PATH),
        "exec",
        dont_inherit=True,
        optimize=0,
    )
    exec(code, module.__dict__)
    return module


_VERIFIED_MODULE_SOURCE_BYTES = _verify_module_source_bytes(MODULE_PATH.read_bytes())
command_contract = _exec_verified_module_bytes(
    _VERIFIED_MODULE_SOURCE_BYTES,
    module_name="phase8_command_contract_under_test",
)

WAVE_A_STATIC = (
    "tests/static/test_phase8_active_reference_audit.py",
    "tests/static/test_phase8_v046_migration.py",
    "tests/static/test_phase8_production_topology.py",
    "tests/static/test_phase8_security_manifests.py",
    "tests/static/test_phase8_observability_assets.py",
    "tests/static/test_phase8_candidate_runner.py",
)
WAVE_B_STATIC = (
    "tests/static/test_phase8_scheduler_lifecycle.py",
    "tests/static/test_phase8_cleanup_eligibility.py",
    "tests/static/test_phase8_stream_compatibility.py",
    "tests/static/test_phase8_stream_retention.py",
    "tests/static/test_phase8_capacity_harness.py",
    "tests/static/test_phase8_recovery_rotation_tools.py",
    "tests/static/test_phase8_scenario_catalog.py",
    "tests/static/test_phase8_external_gate_intake.py",
)


def _document() -> dict[str, object]:
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def _reseal(document: dict[str, object]) -> dict[str, object]:
    document["self_seal"]["payload_sha256"] = (  # type: ignore[index]
        command_contract.contract_payload_sha256(document)
    )
    return document


def _command(document: dict[str, object], command_id: str) -> dict[str, object]:
    return next(
        command
        for command in document["commands"]  # type: ignore[union-attr]
        if command["id"] == command_id
    )


def test_repository_contract_is_strict_valid_and_pinned() -> None:
    document = command_contract.load_command_contract()
    assert document["additional_fields"] == "DENY"
    assert document["command_order"] == list(command_contract.COMMAND_ORDER)
    assert document["self_seal"]["payload_sha256"] == (  # type: ignore[index]
        command_contract.contract_payload_sha256(document)
    )


def test_contract_binds_independently_verified_source_bytes_and_git_blob() -> None:
    source = _VERIFIED_MODULE_SOURCE_BYTES
    binding = _document()["required_blob_binding"]
    git_blob = hashlib.sha1(f"blob {len(source)}\0".encode("ascii") + source)
    assert binding["file_sha256"] == hashlib.sha256(source).hexdigest()
    assert binding["git_blob_sha1"] == git_blob.hexdigest()
    assert binding == command_contract.validator_blob_binding()


def test_independent_source_hash_rejects_module_self_forgery_before_execution() -> None:
    malicious = _VERIFIED_MODULE_SOURCE_BYTES + (
        b"\ncanonical_sha256 = lambda _value: '0' * 64\n"
    )
    with pytest.raises(RuntimeError, match="independent SHA-256"):
        _verify_module_source_bytes(malicious)


def test_verified_bootstrap_never_calls_source_loader_or_pyc(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    probe_spec = importlib.util.spec_from_file_location("loader_probe", MODULE_PATH)
    assert probe_spec is not None and probe_spec.loader is not None
    loader_type = type(probe_spec.loader)
    invoked: list[str] = []

    def forbidden_loader_path(*_args, **_kwargs):
        invoked.append("called")
        raise AssertionError("loader/pyc execution path must remain unused")

    monkeypatch.setattr(loader_type, "exec_module", forbidden_loader_path)
    monkeypatch.setattr(loader_type, "get_code", forbidden_loader_path)
    module_name = "phase8_command_contract_loader_probe"
    try:
        probe = _exec_verified_module_bytes(
            _VERIFIED_MODULE_SOURCE_BYTES,
            module_name=module_name,
        )
        assert probe.SCHEMA_VERSION == "phase8-engineering-command-contract.v1"
        assert invoked == []
    finally:
        sys.modules.pop(module_name, None)


def test_contract_freezes_linux_argv_cwds_backend_shell_timeout_reports_and_stop_first() -> (
    None
):
    commands = _document()["commands"]
    assert [command["id"] for command in commands] == list(  # type: ignore[union-attr]
        command_contract.COMMAND_ORDER
    )
    for command in commands:  # type: ignore[union-attr]
        assert command["backend_kind"] in {
            "PINNED_TEST_CONTAINER",
            "GITHUB_HOSTED_MAVEN",
        }
        assert command["cwd"] in {".", "java-api-service"}
        assert command["shell"] is False
        assert command["stop_first"] is True
        assert command["fresh_runner"] is True
        assert command["fresh_materialization"] is True
        assert 1 <= command["timeout_seconds"] <= 3600
        assert command["report"]["artifact_set_policy"] == (
            "EXACT_NO_MISSING_EXTRA_OR_DUPLICATE"
        )
        assert command["report"]["glob"].startswith(
            ("target/", "/tmp/phase8-artifacts/")
        )
        assert command["report"]["archive_prefix"].startswith("p/")
        assert command["report"]["expected_artifacts"]


@pytest.mark.parametrize(
    ("command_id", "selectors", "junit_path"),
    (
        ("wave_a_static", WAVE_A_STATIC, "/tmp/phase8-artifacts/wave_a_static.xml"),
        (
            "wave_b_static_and_models",
            WAVE_B_STATIC,
            "/tmp/phase8-artifacts/wave_b_static_and_models.xml",
        ),
    ),
)
def test_static_commands_freeze_selectors_disable_plugins_and_cache_and_emit_junit(
    command_id: str, selectors: tuple[str, ...], junit_path: str
) -> None:
    command = _command(_document(), command_id)
    assert command["argv"] == [
        "/usr/local/bin/python",
        "-m",
        "pytest",
        *selectors,
        "-p",
        "no:cacheprovider",
        "--tb=short",
        "-q",
        f"--junitxml={junit_path}",
    ]
    assert command["environment"] == {
        "CI": "1",
        "PYTHONDONTWRITEBYTECODE": "1",
        "PYTHONHASHSEED": "0",
        "PYTHONNOUSERSITE": "1",
        "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
    }
    archive_path = (
        "p/000-wave_a_static-junit.xml"
        if command_id == "wave_a_static"
        else "p/002-wave_b_static_and_models-junit.xml"
    )
    assert command["network_profile"] == "STATIC_EGRESS_DENIED"
    assert command["external_egress_denied"] is True
    assert command["credential_profile"] == "NO_CREDENTIALS_SECRETS_OR_ID_TOKEN"
    assert command["report"] == {
        "archive_prefix": archive_path.removesuffix("-junit.xml"),
        "artifact_set_policy": "EXACT_NO_MISSING_EXTRA_OR_DUPLICATE",
        "expected_artifacts": [
            {
                "archive_path": archive_path,
                "filename": Path(junit_path).name,
                "format": "JUNIT_XML",
                "suite_name": "pytest",
                "test_count": 88 if command_id == "wave_a_static" else 406,
            }
        ],
        "glob": junit_path,
        "source_root": "/tmp/phase8-artifacts",
    }


def test_maven_commands_freeze_wrapper_batch_flags_selectors_goals_and_reports() -> (
    None
):
    document = _document()
    wave_a = _command(document, "wave_a_java")
    wave_b = _command(document, "wave_b_java_unit")
    integration = _command(document, "wave_b_postgresql_integration")
    prefix = ["./mvnw", "-B", "-ntp", "-DforkCount=1"]
    assert wave_a["argv"] == [
        *prefix,
        "-Dtest=AgentRunV2MigrationIntegrationTest,AgentRunStreamReplayIntegrationTest",
        "test",
    ]
    assert wave_b["argv"] == [
        *prefix,
        "-Dtest=AgentRunRecoverySchedulerTest,AgentRunV2PropertiesTest,HearingSchedulerModeTest,JdbcHearingSchedulerDetectorTest,StreamBackfillCoordinatorTest,AgentRunStreamRetentionManifestTest,RedisAgentRunStreamFailoverTest",
        "test",
    ]
    assert integration["argv"] == [
        *prefix,
        "-Pintegration-test",
        "-Dit.test=AgentRunStreamReplayIntegrationTest",
        "verify",
    ]
    assert wave_a["report"]["glob"].endswith("/surefire-reports/TEST-*.xml")
    assert wave_b["report"]["glob"].endswith("/surefire-reports/TEST-*.xml")
    assert integration["report"]["glob"].endswith("/failsafe-reports/TEST-*.xml")
    for command in (wave_a, wave_b, integration):
        assert command["executable_path"] == "java-api-service/mvnw"
        assert command["executable_mode"] == "100755"
        assert command["network_profile"] == (
            "GITHUB_HOSTED_EPHEMERAL_DEPENDENCY_AND_DISPOSABLE_TEST_NETWORK"
        )
        assert command["external_egress_denied"] is False
        assert command["credential_profile"] == (
            "NO_PRODUCTION_CREDENTIALS_SECRETS_OR_ID_TOKEN"
        )


def test_maven_expected_junit_artifacts_are_exact_suite_and_count_allowlists() -> None:
    document = _document()
    assert [
        len(_command(document, command_id)["report"]["expected_artifacts"])
        for command_id in command_contract.COMMAND_ORDER
    ] == [1, 2, 1, 7, 1]
    for command_id, expected_specs in command_contract.MAVEN_SUITE_SPECS.items():
        artifacts = _command(document, command_id)["report"]["expected_artifacts"]
        assert (
            tuple(
                (item["filename"], item["suite_name"], item["test_count"])
                for item in artifacts
            )
            == expected_specs
        )


def test_contract_authority_is_engineering_only_and_all_production_gates_stay_closed() -> (
    None
):
    authority = _document()["authority"]
    assert authority == {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "authority_ceiling": "PHASE_8_ENGINEERING_CHECKPOINT_ONLY",
        "cloud_authority": "FORBIDDEN",
        "id_token_present": False,
        "maven_external_egress_denied": False,
        "maven_network_profile": (
            "GITHUB_HOSTED_EPHEMERAL_DEPENDENCY_AND_DISPOSABLE_TEST_NETWORK"
        ),
        "model_authority": "FORBIDDEN",
        "production_access": "FORBIDDEN",
        "production_actions": "FORBIDDEN",
        "production_authority": "FORBIDDEN",
        "production_credentials_present": False,
        "production_secrets_present": False,
        "production_traffic": "FORBIDDEN",
        "static_egress": "DENIED",
        "temporal_authority": "FORBIDDEN",
    }


def test_validator_blob_binding_and_indexed_maven_mode_are_required() -> None:
    document = _document()
    assert (
        document["required_blob_binding"] == command_contract.validator_blob_binding()
    )
    completed = subprocess.run(
        ["git", "ls-files", "--stage", "--", "java-api-service/mvnw"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    fields = completed.stdout.strip().split()
    assert fields[0] == "100755"
    assert fields[-1] == "java-api-service/mvnw"


@pytest.mark.parametrize(
    "mutator",
    (
        lambda doc: _command(doc, "wave_a_static")["argv"].append("tests/evil.py"),
        lambda doc: _command(doc, "wave_a_java")["argv"].__setitem__(0, "mvn"),
        lambda doc: _command(doc, "wave_b_java_unit")["argv"].__setitem__(
            -2, "-Dtest=InjectedTest"
        ),
        lambda doc: _command(doc, "wave_b_postgresql_integration")["argv"].__setitem__(
            -1, "deploy"
        ),
        lambda doc: _command(doc, "wave_a_static").__setitem__("cwd", "/tmp"),
        lambda doc: _command(doc, "wave_a_static").__setitem__("shell", True),
        lambda doc: _command(doc, "wave_a_static").__setitem__("stop_first", False),
        lambda doc: _command(doc, "wave_a_static").__setitem__("fresh_runner", False),
        lambda doc: _command(doc, "wave_a_static").__setitem__(
            "fresh_materialization", False
        ),
        lambda doc: _command(doc, "wave_a_static").__setitem__(
            "backend_kind", "FIXTURE_ONLY"
        ),
        lambda doc: _command(doc, "wave_a_java").__setitem__(
            "executable_path", "java-api-service/other"
        ),
        lambda doc: _command(doc, "wave_a_java").__setitem__(
            "executable_mode", "100644"
        ),
        lambda doc: _command(doc, "wave_a_static")["report"]["expected_artifacts"][
            0
        ].__setitem__("archive_path", "p/../escaped.xml"),
        lambda doc: _command(doc, "wave_a_static")["report"].__setitem__(
            "glob", "/tmp/phase8-artifacts/../escaped.xml"
        ),
        lambda doc: _command(doc, "wave_a_java")["report"].__setitem__(
            "glob", "/workspace/../secrets/*"
        ),
        lambda doc: _command(doc, "wave_a_java")["report"]["expected_artifacts"].pop(),
        lambda doc: _command(doc, "wave_b_java_unit")["report"]["expected_artifacts"][
            0
        ].__setitem__("test_count", 999),
        lambda doc: _command(doc, "wave_b_java_unit")["report"]["expected_artifacts"][
            0
        ].__setitem__("suite_name", "substituted.Suite"),
        lambda doc: _command(doc, "wave_a_static").__setitem__(
            "network_profile", "NETWORK_UNRESTRICTED"
        ),
        lambda doc: _command(doc, "wave_a_java").__setitem__(
            "external_egress_denied", True
        ),
        lambda doc: _command(doc, "wave_a_java").__setitem__(
            "credential_profile", "PRODUCTION_CREDENTIALS_PRESENT"
        ),
        lambda doc: doc["commands"].reverse(),
        lambda doc: doc["command_order"].reverse(),
        lambda doc: doc["authority"].__setitem__("maven_external_egress_denied", True),
        lambda doc: doc["authority"].__setitem__("id_token_present", True),
        lambda doc: doc["authority"].__setitem__("model_authority", "ALLOWED"),
        lambda doc: doc["authority"].__setitem__("production_actions", "ALLOWED"),
        lambda doc: doc["authority"].__setitem__("MIG-008", "PASS"),
        lambda doc: doc["required_blob_binding"].__setitem__(
            "path", "scripts/substituted.py"
        ),
    ),
)
def test_resealed_command_or_authority_drift_is_rejected(mutator) -> None:
    document = copy.deepcopy(_document())
    mutator(document)
    _reseal(document)
    with pytest.raises(command_contract.CommandContractValidationError):
        command_contract.validate_command_contract(document)


def test_contract_rejects_duplicate_expected_artifacts_even_when_resealed() -> None:
    document = copy.deepcopy(_document())
    artifacts = _command(document, "wave_a_java")["report"]["expected_artifacts"]
    artifacts.append(copy.deepcopy(artifacts[0]))
    _reseal(document)
    with pytest.raises(command_contract.CommandContractValidationError):
        command_contract.validate_command_contract(document)


def _observed_inventory(command_id: str) -> list[dict[str, object]]:
    command = _command(_document(), command_id)
    observed = []
    for artifact in command["report"]["expected_artifacts"]:
        observed.append(
            {
                "archive_path": artifact["archive_path"],
                "filename": artifact["filename"],
                "format": "JUNIT_XML",
                "suite_name": artifact.get("suite_name", "pytest"),
                "test_count": artifact.get("test_count", 137),
            }
        )
    return observed


@pytest.mark.parametrize("command_id", command_contract.COMMAND_ORDER)
def test_report_inventory_accepts_only_the_exact_expected_artifact_set(
    command_id: str,
) -> None:
    observed = _observed_inventory(command_id)
    assert command_contract.validate_report_inventory(command_id, observed) == observed


def test_static_report_identity_and_count_are_exact() -> None:
    observed = _observed_inventory("wave_a_static")
    assert observed[0]["suite_name"] == "pytest"
    assert observed[0]["test_count"] == 88
    for field, substituted in (
        ("suite_name", "pytest-substituted"),
        ("test_count", 87),
    ):
        drifted = copy.deepcopy(observed)
        drifted[0][field] = substituted
        with pytest.raises(command_contract.CommandContractValidationError):
            command_contract.validate_report_inventory("wave_a_static", drifted)


@pytest.mark.parametrize(
    "mutation", ("missing", "extra", "duplicate", "suite", "count")
)
def test_report_inventory_rejects_missing_extra_duplicate_or_suite_drift(
    mutation: str,
) -> None:
    command_id = "wave_a_java"
    observed = _observed_inventory(command_id)
    if mutation == "missing":
        observed.pop()
    elif mutation == "extra":
        extra = copy.deepcopy(observed[0])
        extra["filename"] = "TEST-extra.xml"
        extra["archive_path"] = "p/001-wave_a_java-TEST-extra.xml"
        observed.append(extra)
    elif mutation == "duplicate":
        observed.append(copy.deepcopy(observed[0]))
    elif mutation == "suite":
        observed[0]["suite_name"] = "substituted.Suite"
    else:
        observed[0]["test_count"] = 999
    with pytest.raises(command_contract.CommandContractValidationError):
        command_contract.validate_report_inventory(command_id, observed)


@pytest.mark.parametrize(
    "target",
    (
        "root",
        "authority",
        "blob_binding",
        "command",
        "environment",
        "report",
        "artifact",
        "self_seal",
    ),
)
def test_unknown_fields_are_denied_at_every_object_boundary(target: str) -> None:
    document = copy.deepcopy(_document())
    if target == "root":
        document["unexpected"] = True
    elif target == "authority":
        document["authority"]["unexpected"] = True  # type: ignore[index]
    elif target == "blob_binding":
        document["required_blob_binding"]["unexpected"] = True  # type: ignore[index]
    elif target == "command":
        _command(document, "wave_a_static")["unexpected"] = True
    elif target == "environment":
        _command(document, "wave_a_static")["environment"]["unexpected"] = "1"
    elif target == "report":
        _command(document, "wave_a_static")["report"]["unexpected"] = True
    elif target == "artifact":
        _command(document, "wave_a_static")["report"]["expected_artifacts"][0][
            "unexpected"
        ] = True
    else:
        document["self_seal"]["unexpected"] = True  # type: ignore[index]
    with pytest.raises(
        command_contract.CommandContractValidationError, match="keys|object"
    ):
        command_contract.validate_command_contract(document)


def test_duplicate_keys_are_rejected_before_validation() -> None:
    raw = CONTRACT_PATH.read_bytes().replace(
        b'"phase": 8,', b'"phase": 8,\n  "phase": 8,', 1
    )
    with pytest.raises(
        command_contract.CommandContractValidationError, match="duplicate"
    ):
        command_contract.parse_bounded_json_bytes(raw)


@pytest.mark.parametrize(
    "raw",
    (
        b"",
        b"[]",
        b"\xef\xbb\xbf{}",
        b'{"x": NaN}',
        b'\xff{"x": 1}',
    ),
)
def test_parser_rejects_empty_nonobject_bom_nonfinite_and_non_utf8(raw: bytes) -> None:
    with pytest.raises(command_contract.CommandContractValidationError):
        command_contract.parse_bounded_json_bytes(raw)


def test_parser_rejects_oversized_deep_and_high_node_documents() -> None:
    oversized = b"{" + b" " * command_contract.MAX_CONTRACT_BYTES + b"}"
    deep: object = "leaf"
    for _ in range(command_contract.MAX_JSON_DEPTH + 1):
        deep = {"x": deep}
    nodes = {str(index): index for index in range(command_contract.MAX_JSON_NODES + 1)}
    for raw in (json.dumps(deep).encode(), json.dumps(nodes).encode()):
        with pytest.raises(command_contract.CommandContractValidationError):
            command_contract.parse_bounded_json_bytes(raw)
    with pytest.raises(command_contract.CommandContractValidationError, match="byte"):
        command_contract.parse_bounded_json_bytes(oversized)


def test_canonical_hash_is_stable_across_key_order_and_rejects_nan() -> None:
    assert command_contract.canonical_sha256({"b": 2, "a": 1}) == (
        command_contract.canonical_sha256({"a": 1, "b": 2})
    )
    with pytest.raises(command_contract.CommandContractValidationError):
        command_contract.canonical_json_bytes({"value": float("nan")})


class _DriftedStat:
    def __init__(self, original, *, size_delta: int = 0) -> None:
        self.st_dev = original.st_dev
        self.st_ino = original.st_ino
        self.st_mode = original.st_mode
        self.st_size = original.st_size + size_delta
        self.st_mtime_ns = original.st_mtime_ns
        self.st_nlink = original.st_nlink


def test_stable_reader_uses_one_descriptor_and_rejects_fd_identity_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    path = tmp_path / "bounded.json"
    path.write_bytes(b'{"ok":true}')
    real_open = command_contract.os.open
    real_fstat = command_contract.os.fstat
    opened = 0
    fstats = 0

    def counted_open(*args, **kwargs):
        nonlocal opened
        opened += 1
        return real_open(*args, **kwargs)

    def drifting_fstat(descriptor):
        nonlocal fstats
        fstats += 1
        metadata = real_fstat(descriptor)
        return _DriftedStat(metadata, size_delta=1) if fstats == 2 else metadata

    monkeypatch.setattr(command_contract.os, "open", counted_open)
    monkeypatch.setattr(command_contract.os, "fstat", drifting_fstat)
    with pytest.raises(
        command_contract.CommandContractValidationError, match="changed"
    ):
        command_contract._read_stable_no_follow_file(
            path, maximum_bytes=1024, context="test snapshot"
        )
    assert opened == 1
    assert fstats == 2


def test_stable_reader_rejects_oversize_hardlink_and_alias_ancestry(
    tmp_path: Path,
) -> None:
    oversized = tmp_path / "oversized.json"
    oversized.write_bytes(b"x" * 9)
    with pytest.raises(
        command_contract.CommandContractValidationError, match="bounded"
    ):
        command_contract._read_stable_no_follow_file(
            oversized, maximum_bytes=8, context="oversized snapshot"
        )

    original = tmp_path / "original.json"
    hardlink = tmp_path / "hardlink.json"
    original.write_bytes(b"{}")
    try:
        command_contract.os.link(original, hardlink)
    except OSError:
        pass
    else:
        with pytest.raises(
            command_contract.CommandContractValidationError, match="single-link"
        ):
            command_contract._read_stable_no_follow_file(
                original, maximum_bytes=8, context="hardlink snapshot"
            )

    real_directory = tmp_path / "real"
    real_directory.mkdir()
    (real_directory / "contract.json").write_bytes(b"{}")
    alias = tmp_path / "alias"
    try:
        alias.symlink_to(real_directory, target_is_directory=True)
    except OSError:
        return
    with pytest.raises(
        command_contract.CommandContractValidationError, match="ancestry"
    ):
        command_contract._read_stable_no_follow_file(
            alias / "contract.json", maximum_bytes=8, context="alias snapshot"
        )


def test_validator_binding_rejects_bytes_different_from_import_snapshot(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def substituted_snapshot(*_args, **_kwargs):
        return (
            command_contract._IMPORTED_MODULE_BYTES + b"\n# substituted\n",
            command_contract._IMPORTED_MODULE_IDENTITY,
        )

    monkeypatch.setattr(
        command_contract, "_read_stable_no_follow_file", substituted_snapshot
    )
    with pytest.raises(
        command_contract.CommandContractValidationError, match="import-time"
    ):
        command_contract.validator_blob_binding()


ALLOWED_MODULE_IMPORTS = {
    ("from", "__future__", "annotations", None),
    ("import", "copy", None, None),
    ("import", "hashlib", None, None),
    ("import", "json", None, None),
    ("import", "os", None, None),
    ("import", "stat", None, None),
    ("from", "pathlib", "Path", None),
    ("from", "typing", "Any", None),
    ("from", "typing", "Mapping", None),
}
ALLOWED_MODULE_CALLS = {
    "COMMAND_ORDER.index",
    "CommandContractValidationError",
    "Path",
    "_EMPTY_BYTES.join",
    "_IMPORTED_MODULE_DIGEST.hexdigest",
    "_absolute_path_without_alias_ancestry",
    "_assert_bounded_tree",
    "_assert_exact_contract_shape",
    "_assert_exact_invariants",
    "_assert_exact_keys",
    "_assert_regular_bounded_metadata",
    "_git_blob_sha1",
    "_is_lower_hex",
    "_metadata_identity",
    "_metadata_is_alias",
    "_read_stable_no_follow_file",
    "absolute.is_absolute",
    "all",
    "all_archive_paths.extend",
    "any",
    "archive.is_absolute",
    "archive_path.startswith",
    "arg.startswith",
    "argv.count",
    "bool",
    "canonical_json_bytes",
    "canonical_sha256",
    "chunks.append",
    "command.get",
    "contract_payload_sha256",
    "copy.deepcopy",
    "current.values",
    "dict",
    "digest.hexdigest",
    "enumerate",
    "expected_by_filename.items",
    "hashlib.sha256",
    "hashlib.sha1",
    "header_text.encode",
    "isinstance",
    "item.casefold",
    "json.dumps",
    "json.loads",
    "len",
    "list",
    "min",
    "normalized.append",
    "os.close",
    "os.fspath",
    "os.fstat",
    "os.lstat",
    "os.open",
    "os.path.abspath",
    "os.read",
    "parse_bounded_json_bytes",
    "payload.pop",
    "raw.decode",
    "raw.startswith",
    "report_glob.startswith",
    "serialized.encode",
    "set",
    "stack.extend",
    "stack.pop",
    "stat.S_ISLNK",
    "stat.S_ISREG",
    "tuple",
    "type",
    "validate_command_contract",
    "validator_blob_binding",
    "zip",
}
ALLOWED_BUILTIN_CALL_ROOTS = {
    "all",
    "any",
    "bool",
    "dict",
    "enumerate",
    "isinstance",
    "len",
    "list",
    "min",
    "set",
    "tuple",
    "type",
    "zip",
}
EXPECTED_MODULE_DECLARATIONS = [
    "CommandContractValidationError",
    "_metadata_identity",
    "_metadata_is_alias",
    "_absolute_path_without_alias_ancestry",
    "_assert_regular_bounded_metadata",
    "_read_stable_no_follow_file",
    "_git_blob_sha1",
    "canonical_json_bytes",
    "canonical_sha256",
    "contract_payload_sha256",
    "validator_blob_binding",
    "_reject_duplicate_object_pairs",
    "_reject_json_constant",
    "_assert_bounded_tree",
    "parse_bounded_json_bytes",
    "_assert_exact_keys",
    "_is_lower_hex",
    "_assert_exact_contract_shape",
    "_assert_exact_invariants",
    "validate_command_contract",
    "validate_report_inventory",
    "load_command_contract",
]
EXPECTED_CALL_ROOT_BINDING_SHA256 = (
    "6cc66f9434c6795a0557c1c4e4948c8e4dda9b088d87e96ffea9e6a02e1626d7"
)
EXPECTED_INDIRECT_TARGET_SHA256 = (
    "c08fd28782e76a7bf62e41a6d74661f3679397b294e0f198d1c1c5b6842fc1d1"
)
EXPECTED_MODULE_CANONICAL_AST_SHA256 = (
    "914ae95736c64f9a2c7b2b773670c02b4920af05b62839ef230c522912c2d997"
)
EXPECTED_DUPLICATE_PAIRS_FUNCTION_AST_SHA256 = (
    "7bcf2fd7823544bfbff30249afe317eed67e40bc4982ebcab12668b1dd29b758"
)
FORBIDDEN_DYNAMIC_NAMES = {
    "__import__",
    "builtins",
    "compile",
    "eval",
    "exec",
    "getattr",
    "globals",
    "importlib",
    "locals",
    "open",
}


def _dotted_name(node: ast.expr) -> str | None:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        base = _dotted_name(node.value)
        return f"{base}.{node.attr}" if base else None
    return None


def _test_canonical_sha256(value: object) -> str:
    serialized = json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(serialized).hexdigest()


def _canonical_ast_sha256(node: ast.AST) -> str:
    canonical = ast.dump(node, annotate_fields=True, include_attributes=False)
    return _test_canonical_sha256(canonical)


def _enclosing_scope_name(node: ast.AST, parents: dict[ast.AST, ast.AST]) -> str:
    current = parents.get(node)
    while current is not None and not isinstance(
        current,
        (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Lambda, ast.Module),
    ):
        current = parents.get(current)
    if isinstance(current, ast.Module):
        return "<module>"
    if isinstance(current, ast.Lambda):
        return "<lambda>"
    assert isinstance(current, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
    return current.name


def _call_root_binding_records(
    tree: ast.Module, parents: dict[ast.AST, ast.AST], calls: set[str]
) -> list[tuple[str, str, str]]:
    roots = {call.split(".", 1)[0] for call in calls}
    records: list[tuple[str, str, str]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                name = alias.asname or alias.name.split(".", 1)[0]
                if name in roots:
                    records.append((name, "<module>", "Import"))
        elif isinstance(node, ast.ImportFrom):
            for alias in node.names:
                name = alias.asname or alias.name
                if name in roots:
                    records.append((name, "<module>", "ImportFrom"))
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            scope = _enclosing_scope_name(node, parents)
            if node.name in roots:
                records.append((node.name, scope, type(node).__name__))
            if not isinstance(node, ast.ClassDef):
                arguments = [
                    *node.args.posonlyargs,
                    *node.args.args,
                    *node.args.kwonlyargs,
                ]
                if node.args.vararg:
                    arguments.append(node.args.vararg)
                if node.args.kwarg:
                    arguments.append(node.args.kwarg)
                for argument in arguments:
                    if argument.arg in roots:
                        records.append((argument.arg, node.name, "arg"))
        elif isinstance(node, ast.Lambda):
            arguments = [
                *node.args.posonlyargs,
                *node.args.args,
                *node.args.kwonlyargs,
            ]
            if node.args.vararg:
                arguments.append(node.args.vararg)
            if node.args.kwarg:
                arguments.append(node.args.kwarg)
            for argument in arguments:
                if argument.arg in roots:
                    records.append((argument.arg, "<lambda>", "arg"))
        elif isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)):
            if node.id in roots:
                records.append(
                    (
                        node.id,
                        _enclosing_scope_name(node, parents),
                        type(parents[node]).__name__,
                    )
                )
        elif isinstance(node, ast.ExceptHandler) and node.name in roots:
            records.append(
                (node.name, _enclosing_scope_name(node, parents), "ExceptHandler")
            )
        elif isinstance(node, (ast.MatchAs, ast.MatchStar)) and node.name in roots:
            records.append(
                (node.name, _enclosing_scope_name(node, parents), type(node).__name__)
            )
        elif isinstance(node, ast.MatchMapping) and node.rest in roots:
            records.append(
                (node.rest, _enclosing_scope_name(node, parents), "MatchMapping")
            )
        elif isinstance(node, (ast.Global, ast.Nonlocal)):
            for name in node.names:
                if name in roots:
                    records.append(
                        (
                            name,
                            _enclosing_scope_name(node, parents),
                            type(node).__name__,
                        )
                    )
    return sorted(records)


def _is_exact_allowed_indirect_target(
    target: ast.Attribute | ast.Subscript,
    parents: dict[ast.AST, ast.AST],
) -> bool:
    node = parents[target]
    return (
        isinstance(node, ast.Assign)
        and len(node.targets) == 1
        and node.targets == [target]
        and node.type_comment is None
        and _enclosing_scope_name(node, parents) == "_reject_duplicate_object_pairs"
        and isinstance(target, ast.Subscript)
        and isinstance(target.ctx, ast.Store)
        and isinstance(target.value, ast.Name)
        and target.value.id == "result"
        and isinstance(target.slice, ast.Name)
        and target.slice.id == "key"
        and isinstance(node.value, ast.Name)
        and node.value.id == "value"
    )


def _indirect_target_record(
    target: ast.Attribute | ast.Subscript,
    parents: dict[ast.AST, ast.AST],
) -> dict[str, str]:
    node = parents[target]
    value = (
        node.value
        if isinstance(node, (ast.Assign, ast.AnnAssign, ast.AugAssign, ast.NamedExpr))
        else None
    )
    return {
        "context": type(target.ctx).__name__,
        "parent": type(node).__name__,
        "scope": _enclosing_scope_name(node, parents),
        "target": ast.dump(target, annotate_fields=True, include_attributes=False),
        "value": (
            ast.dump(value, annotate_fields=True, include_attributes=False)
            if value is not None
            else "<none>"
        ),
    }


def _audit_closed_world_module(source: str) -> None:
    tree = ast.parse(source)
    assert _canonical_ast_sha256(tree) == EXPECTED_MODULE_CANONICAL_AST_SHA256
    duplicate_pair_functions = [
        statement
        for statement in tree.body
        if isinstance(statement, ast.FunctionDef)
        and statement.name == "_reject_duplicate_object_pairs"
    ]
    assert len(duplicate_pair_functions) == 1
    assert _canonical_ast_sha256(duplicate_pair_functions[0]) == (
        EXPECTED_DUPLICATE_PAIRS_FUNCTION_AST_SHA256
    )
    parents = {
        child: parent
        for parent in ast.walk(tree)
        for child in ast.iter_child_nodes(parent)
    }
    imports: set[tuple[str, str, str | None, str | None]] = set()
    imported_bindings: set[str] = set()
    calls: set[str] = set()
    unresolved_calls: list[int] = []
    referenced_names: set[str] = set()
    module_declarations: list[str] = []
    module_assignment_names: list[str] = []
    indirect_target_records: list[dict[str, str]] = []

    for statement in tree.body:
        if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            module_declarations.append(statement.name)
        elif isinstance(statement, (ast.Assign, ast.AnnAssign)):
            targets = (
                statement.targets
                if isinstance(statement, ast.Assign)
                else [statement.target]
            )
            for target in targets:
                for current in ast.walk(target):
                    if isinstance(current, ast.Name) and isinstance(
                        current.ctx, ast.Store
                    ):
                        module_assignment_names.append(current.id)

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported_bindings.update(
                alias.asname or alias.name.split(".", 1)[0] for alias in node.names
            )
        elif isinstance(node, ast.ImportFrom):
            imported_bindings.update(alias.asname or alias.name for alias in node.names)

    protected_bindings = (
        imported_bindings
        | ALLOWED_BUILTIN_CALL_ROOTS
        | set(module_declarations)
        | set(module_assignment_names)
    )

    def assert_not_shadowed(
        name: str | None, *, module_declaration: bool = False
    ) -> None:
        if name is None or name not in protected_bindings:
            return
        assert name not in imported_bindings
        assert name not in ALLOWED_BUILTIN_CALL_ROOTS
        assert name not in module_declarations
        assert module_declaration and name in module_assignment_names

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                imports.add(("import", alias.name, None, alias.asname))
                imported_bindings.add(alias.asname or alias.name.split(".", 1)[0])
        elif isinstance(node, ast.ImportFrom) and node.module:
            assert node.level == 0
            for alias in node.names:
                assert alias.name != "*"
                imports.add(("from", node.module, alias.name, alias.asname))
                imported_bindings.add(alias.asname or alias.name)
        elif isinstance(node, ast.Call):
            name = _dotted_name(node.func)
            if name is None:
                unresolved_calls.append(node.lineno)
            else:
                calls.add(name)
        elif isinstance(node, ast.Name):
            referenced_names.add(node.id)
        if isinstance(node, (ast.Attribute, ast.Subscript)) and isinstance(
            node.ctx, (ast.Store, ast.Del)
        ):
            assert _is_exact_allowed_indirect_target(node, parents)
            indirect_target_records.append(_indirect_target_record(node, parents))
        if isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)):
            parent = parents[node]
            statement = parent
            while statement in parents and not isinstance(
                parents[statement], ast.Module
            ):
                statement = parents[statement]
            module_declaration = (
                statement in parents
                and isinstance(parents[statement], ast.Module)
                and isinstance(statement, (ast.Assign, ast.AnnAssign))
                and node.id in module_assignment_names
            )
            assert_not_shadowed(node.id, module_declaration=module_declaration)
        elif isinstance(node, ast.arguments):
            arguments = [*node.posonlyargs, *node.args, *node.kwonlyargs]
            if node.vararg:
                arguments.append(node.vararg)
            if node.kwarg:
                arguments.append(node.kwarg)
            for argument in arguments:
                assert_not_shadowed(argument.arg)
        elif isinstance(node, ast.ExceptHandler):
            assert_not_shadowed(node.name)
        elif isinstance(node, (ast.Global, ast.Nonlocal)):
            for name in node.names:
                assert_not_shadowed(name)
        elif isinstance(node, ast.MatchAs):
            assert_not_shadowed(node.name)
        elif isinstance(node, ast.MatchStar):
            assert_not_shadowed(node.name)
        elif isinstance(node, ast.MatchMapping):
            assert_not_shadowed(node.rest)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            parent = parents[node]
            if isinstance(parent, ast.Module):
                assert node.name in EXPECTED_MODULE_DECLARATIONS
            else:
                assert_not_shadowed(node.name)
    assert imports == ALLOWED_MODULE_IMPORTS
    assert module_declarations == EXPECTED_MODULE_DECLARATIONS
    assert len(module_assignment_names) == len(set(module_assignment_names))
    assert not unresolved_calls
    assert calls == ALLOWED_MODULE_CALLS
    assert (
        _test_canonical_sha256(_call_root_binding_records(tree, parents, calls))
        == EXPECTED_CALL_ROOT_BINDING_SHA256
    )
    assert len(indirect_target_records) == 1
    assert _test_canonical_sha256(indirect_target_records) == (
        EXPECTED_INDIRECT_TARGET_SHA256
    )
    assert referenced_names.isdisjoint(FORBIDDEN_DYNAMIC_NAMES)


def test_module_has_closed_world_import_and_call_capability_allowlists() -> None:
    _audit_closed_world_module(MODULE_PATH.read_text(encoding="utf-8"))


def test_module_ast_seal_ignores_comments_and_whitespace_only() -> None:
    source = MODULE_PATH.read_text(encoding="utf-8")
    _audit_closed_world_module(f"{source}\n\n# canonical AST ignores this comment\n")


@pytest.mark.parametrize(
    "mutation",
    (
        "\n__import__('os')\n",
        "\ngetattr(object(), 'run')()\n",
        "\nglobals()\n",
        "\nlocals()\n",
        "\neval('1')\n",
        "\nexec('pass')\n",
        "\ncompile('pass', '<x>', 'exec')\n",
        "\nopen('x')\n",
        "\nimport importlib\n",
        "\nimport builtins\n",
        "\ncallback()\n",
        "\nfrom pathlib import os as evil\ncopy.deepcopy = evil.system\ncopy.deepcopy('id')\n",
        "\nINJECTED = lambda hashlib: hashlib.sha256(b'x')\n",
        "\nINJECTED = lambda len: len(b'x')\n",
        "\nINJECTED = lambda canonical_sha256: canonical_sha256({})\n",
        "\nfor copy in ():\n    copy.deepcopy({})\n",
        "\nINJECTED = [copy.deepcopy({}) for copy in ()]\n",
        "\nwith None as copy:\n    copy.deepcopy({})\n",
        "\ntry:\n    pass\nexcept Exception as copy:\n    copy.deepcopy({})\n",
        "\n(copy, *REST) = ()\ncopy.deepcopy({})\n",
        "\n[copy] = ()\ncopy.deepcopy({})\n",
        "\nINJECTED = (copy := None)\ncopy.deepcopy({})\n",
        "\nmatch None:\n    case copy:\n        copy.deepcopy({})\n",
        "\ncopy.deepcopy = canonical_json_bytes\n",
        "\ncopy.__dict__['deepcopy'] = canonical_json_bytes\n",
        "\ndel copy.deepcopy\n",
        "\ncopy.deepcopy += canonical_json_bytes\n",
        "\n(copy.deepcopy,) = (canonical_json_bytes,)\n",
        "\n(copy if True else copy).deepcopy = canonical_json_bytes\n",
        "\n(copy or copy).deepcopy = canonical_json_bytes\n",
        "\n(copy := canonical_json_bytes).deepcopy = canonical_json_bytes\n",
        "\nfactory().deepcopy = canonical_json_bytes\n",
        "\n(lambda: copy)().deepcopy = canonical_json_bytes\n",
        "\nif True:\n    alias = copy\n    alias.deepcopy = canonical_json_bytes\n",
        "\nif True:\n    alias = json\n    alias.loads = canonical_json_bytes\n",
        "\nfor copy.deepcopy in ():\n    pass\n",
        "\nfor json.loads in ():\n    pass\n",
        "\nINJECTED = [None for copy.deepcopy in ()]\n",
        "\nwith None as json.loads:\n    pass\n",
        "\nasync def injected(values):\n    async for copy.deepcopy in values:\n        pass\n",
        "\nif False:\n    pass\n",
        "\n'otherwise invisible dead semantic node'\n",
    ),
)
def test_closed_world_guard_rejects_dynamic_import_execution_and_indirect_calls(
    mutation: str,
) -> None:
    source = MODULE_PATH.read_text(encoding="utf-8") + mutation
    with pytest.raises(AssertionError):
        _audit_closed_world_module(source)


def test_loader_rejects_alternate_contract_path(tmp_path: Path) -> None:
    alternate = tmp_path / "engineering-candidate-commands.json"
    alternate.write_bytes(CONTRACT_PATH.read_bytes())
    with pytest.raises(
        command_contract.CommandContractValidationError, match="repository"
    ):
        command_contract.load_command_contract(alternate)
