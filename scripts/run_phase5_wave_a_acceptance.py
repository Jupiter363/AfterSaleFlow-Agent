from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Sequence

import yaml

try:
    from scripts import run_phase4_candidate_checkpoint as shared
    from scripts import run_phase5_wave_a_checkpoint as wave_runner
except (ImportError, ModuleNotFoundError):
    import run_phase4_candidate_checkpoint as shared  # type: ignore[no-redef]
    import run_phase5_wave_a_checkpoint as wave_runner  # type: ignore[no-redef]


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
SCHEMA_VERSION = "phase5-wave-a-acceptance-execution.v1"
MANIFEST_NAME = "phase5-wave-a-acceptance-execution.json"
EXPECTED_TESTED_CANDIDATE = "edfd54952dcc5a07d87a90fdb094c01b1a7df79b"
EXPECTED_EVIDENCE_COMMIT = "0292321fdb376c3392c86daf6cf98365bfee7c4a"
EXPECTED_BASE_COMMIT = "496d0d459b97000f62742fe064d8ef70956ea419"
EXPECTED_P5_ENTRY_EVIDENCE_COMMIT = "e5f6019b71a90174c09aecdcba336bd12788b75b"
EXPECTED_EVIDENCE_DIR = (
    "test-reports/temporal-first/phase-5-wave-a-20260723-edfd5495/phase-5-wave-a"
)
EXPECTED_ACCEPTANCE_DIR = (
    "test-reports/temporal-first/phase-5-wave-a-acceptance-20260723-edfd5495/"
    "phase-5-wave-a-acceptance"
)
EVIDENCE_FILES = (
    "candidate-commit.txt",
    "task-commit-bindings.json",
    "phase5-wave-a-execution-manifest.json",
    "python-phase5-wave-a-junit.xml",
    "java-phase5-wave-a-junit.xml",
    "static-phase5-wave-a-junit.xml",
    "wave-a-metrics.json",
    "artifact-sha256.json",
)
ACCEPTANCE_FILES = (
    "accepted-tooling-candidate.txt",
    "phase5-wave-a-acceptance.json",
    "artifact-sha256.json",
)
TOOLING_FILES = (
    "scripts/run_phase5_wave_a_acceptance.py",
    "scripts/generate_phase5_wave_a_acceptance.py",
    "tests/static/test_phase5_wave_a_acceptance.py",
    "contracts/agent-platform/evidence/v2/phase5-wave-a-acceptance.schema.json",
    "docs/runbooks/temporal-first/phase-5-wave-a-acceptance.md",
    "plans/phase-5-evidence-pilot-execution.md",
    "plans/phase-5-evidence-pilot-test-batches.yaml",
    "plans/phase-5-owner-briefs.yaml",
)
STATE_TRANSITION_FILES = (
    "plans/phase-5-evidence-pilot-execution.md",
    "plans/phase-5-evidence-pilot-test-batches.yaml",
    "plans/phase-5-owner-briefs.yaml",
)
EXPECTED_TOOLING_PLAN_BLOB_OIDS = {
    "plans/phase-5-evidence-pilot-execution.md": (
        "b857bc6047b29857ae546765d741be41dd2eff56"
    ),
    "plans/phase-5-evidence-pilot-test-batches.yaml": (
        "dfbe7972bacece62225792b2501f4423fd7e9386"
    ),
    "plans/phase-5-owner-briefs.yaml": (
        "eecbe60b01076be5130d57564549dd81ce2d570a"
    ),
}
EXECUTION_PLAN_PATH = "plans/phase-5-evidence-pilot-execution.md"
TEST_MATRIX_PATH = "plans/phase-5-evidence-pilot-test-batches.yaml"
OWNER_BRIEFS_PATH = "plans/phase-5-owner-briefs.yaml"
ACCEPTANCE_DECISION_KEYS = frozenset(
    {
        "P5-WAVE-A-INTEGRATED",
        "wave_b",
        "evidence_commit_alone_opens_wave_b",
        "state_transition_commit_required",
        "acceptance_commit_is_derived_from_git_history",
        "promotion_gate",
        "MIG-004",
        "MIG-005",
    }
)
RUNTIME_RESTRICTION_KEYS = frozenset(
    {
        "real_provider",
        "formal_evidence_sink",
        "temporal_evidence_allocation",
        "real_case_shadow",
        "canary",
        "promotion",
    }
)
SOURCE_EXPECTATIONS = {
    "p5_wave_a_python": ("python-phase5-wave-a-junit.xml", 120),
    "p5_wave_a_java": ("java-phase5-wave-a-junit.xml", 144),
    "p5_wave_a_static": ("static-phase5-wave-a-junit.xml", 98),
}
EXPECTED_TOTALS = {"tests": 362, "failures": 0, "errors": 0, "skipped": 0}


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _git_bytes(*arguments: str) -> bytes:
    process = subprocess.run(
        ["git", "--no-replace-objects", *arguments],
        cwd=ROOT,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if process.returncode:
        detail = process.stderr.decode("utf-8", errors="replace").strip()
        raise shared.EvidenceError(f"Wave A acceptance Git authentication failed: {detail}")
    return process.stdout


def _git_text(*arguments: str) -> str:
    return _git_bytes(*arguments).decode("utf-8", errors="strict").strip()


def _raw_parent_commits(commit: str) -> list[str]:
    try:
        raw = _git_bytes("cat-file", "-p", commit).decode("utf-8", errors="strict")
    except UnicodeDecodeError as exception:
        raise shared.EvidenceError(f"{commit} commit object is not UTF-8 parseable") from exception
    parents: list[str] = []
    for line in raw.splitlines():
        if line == "":
            break
        if line.startswith("parent "):
            parent = line.removeprefix("parent ")
            if not __import__("re").fullmatch(r"[0-9a-f]{40}", parent):
                raise shared.EvidenceError(f"{commit} contains an invalid raw parent header")
            parents.append(parent)
    return parents


def _assert_raw_direct_parent(parent: str, child: str, context: str) -> None:
    parents = _raw_parent_commits(child)
    if parents != [parent]:
        raise shared.EvidenceError(f"{context} must be a direct raw single-parent commit")


def _assert_ancestor(ancestor: str, descendant: str, context: str) -> None:
    pending = [descendant]
    seen: set[str] = set()
    while pending:
        current = pending.pop()
        if current == ancestor:
            return
        if current in seen:
            continue
        seen.add(current)
        if len(seen) > 20000:
            raise shared.EvidenceError(f"{context} raw ancestry search exceeded limit")
        pending.extend(_raw_parent_commits(current))
    raise shared.EvidenceError(f"{context} raw ancestry is not authenticated")


def _strict_json(value: bytes, context: str) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, item in pairs:
            if key in result:
                raise shared.EvidenceError(f"{context} contains duplicate JSON key {key}")
            result[key] = item
        return result

    try:
        document = json.loads(
            value.decode("utf-8"),
            object_pairs_hook=reject_duplicates,
            parse_constant=lambda constant: (_ for _ in ()).throw(
                shared.EvidenceError(f"{context} contains non-finite {constant}")
            ),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise shared.EvidenceError(f"cannot parse {context}: {exception}") from exception
    if not isinstance(document, dict):
        raise shared.EvidenceError(f"{context} must be a JSON object")
    return document


def _strict_yaml(value: bytes, context: str) -> dict[str, Any]:
    class UniqueKeyLoader(yaml.SafeLoader):
        pass

    def construct_mapping(
        loader: yaml.SafeLoader, node: yaml.MappingNode, deep: bool = False
    ) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in result:
                raise shared.EvidenceError(f"{context} contains duplicate YAML key {key}")
            result[key] = loader.construct_object(value_node, deep=deep)
        return result

    UniqueKeyLoader.add_constructor(
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping
    )
    try:
        document = yaml.load(value.decode("utf-8"), Loader=UniqueKeyLoader)
    except (UnicodeDecodeError, yaml.YAMLError) as exception:
        raise shared.EvidenceError(f"cannot parse {context}: {exception}") from exception
    if not isinstance(document, dict):
        raise shared.EvidenceError(f"{context} must be a YAML object")
    return document


def _assert_lf(value: bytes, context: str) -> None:
    if b"\r" in value or (value and not value.endswith(b"\n")):
        raise shared.EvidenceError(f"{context} is not canonical LF text")


def _assert_history_safe() -> None:
    if os.environ.get("GIT_REPLACE_REF_BASE"):
        raise shared.EvidenceError(
            "GIT_REPLACE_REF_BASE is forbidden for Wave A acceptance"
        )
    if os.environ.get("GIT_GRAFT_FILE"):
        raise shared.EvidenceError(
            "GIT_GRAFT_FILE is forbidden for Wave A acceptance"
        )
    replace_refs = _git_text(
        "for-each-ref", "--format=%(refname)", "refs/replace"
    )
    if replace_refs:
        raise shared.EvidenceError(
            "Git replace refs are forbidden for Wave A acceptance"
        )
    common_dir = Path(_git_text("rev-parse", "--git-common-dir"))
    if not common_dir.is_absolute():
        common_dir = (ROOT / common_dir).resolve()
    git_dir = Path(_git_text("rev-parse", "--git-dir"))
    if not git_dir.is_absolute():
        git_dir = (ROOT / git_dir).resolve()
    if (common_dir / "shallow").exists() or (git_dir / "shallow").exists():
        raise shared.EvidenceError("shallow history is forbidden for Wave A acceptance")
    if (common_dir / "info" / "grafts").exists() or (git_dir / "info" / "grafts").exists():
        raise shared.EvidenceError("Git grafts are forbidden for Wave A acceptance")


def _commit(value: Any, context: str) -> str:
    if not isinstance(value, str) or not __import__("re").fullmatch(r"[0-9a-f]{40}", value):
        raise shared.EvidenceError(f"{context} must be a full lowercase SHA-1 commit")
    if _git_text("cat-file", "-t", value) != "commit":
        raise shared.EvidenceError(f"{context} is not a commit object")
    return value


def _git_blob_record(commit: str, relative: str) -> tuple[bytes, str]:
    if (
        relative.startswith("/")
        or "\\" in relative
        or any(part in {"", ".", ".."} for part in relative.split("/"))
    ):
        raise shared.EvidenceError("Git evidence path is not canonical")
    record = _git_bytes("ls-tree", "-z", commit, "--", relative)
    entries = [item for item in record.split(b"\0") if item]
    if len(entries) != 1:
        raise shared.EvidenceError(f"{relative} is missing or ambiguous in {commit}")
    metadata, separator, encoded_path = entries[0].partition(b"\t")
    fields = metadata.split()
    if (
        not separator
        or len(fields) != 3
        or fields[0] != b"100644"
        or fields[1] != b"blob"
        or encoded_path.decode("utf-8") != relative
    ):
        raise shared.EvidenceError(f"{relative} must be a regular non-symlink Git blob")
    oid = fields[2].decode("ascii")
    value = _git_bytes("show", f"{commit}:{relative}")
    if len(value) > 2_000_000:
        raise shared.EvidenceError(f"{relative} exceeds the acceptance size limit")
    _assert_lf(value, relative)
    return value, oid


def _git_blob(commit: str, relative: str) -> bytes:
    return _git_blob_record(commit, relative)[0]


def _load_matrix(commit: str) -> dict[str, Any]:
    relative = MATRIX_PATH.relative_to(ROOT).as_posix()
    return _strict_yaml(_git_blob(commit, relative), "Phase 5 test matrix")


def _acceptance_contract(matrix: dict[str, Any]) -> dict[str, Any]:
    try:
        contract = matrix["batches"]["P5-BATCH-1"]["acceptance"]
    except (KeyError, TypeError) as exception:
        raise shared.EvidenceError("Wave A acceptance contract is missing") from exception
    expected = {
        "status": "READY_FOR_SEPARATE_ACCEPTANCE_RUN",
        "tested_candidate_commit": EXPECTED_TESTED_CANDIDATE,
        "evidence_commit": EXPECTED_EVIDENCE_COMMIT,
        "evidence_path": EXPECTED_EVIDENCE_DIR,
        "acceptance_output": EXPECTED_ACCEPTANCE_DIR,
        "acceptance_runner": "scripts/run_phase5_wave_a_acceptance.py",
        "acceptance_generator": "scripts/generate_phase5_wave_a_acceptance.py",
        "acceptance_execution_manifest": MANIFEST_NAME,
        "acceptance_schema": "phase5-wave-a-acceptance.v1",
        "acceptance_required_files": list(ACCEPTANCE_FILES),
    }
    for key, value in expected.items():
        if contract.get(key) != value:
            raise shared.EvidenceError(f"Wave A acceptance contract drifted at {key}")
    return contract


def _assert_exact_evidence_commit(candidate: str, evidence: str) -> None:
    _assert_raw_direct_parent(candidate, evidence, "Wave A evidence")
    changed = {
        path
        for item in _git_text(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "--no-renames",
            "-r",
            candidate,
            evidence,
        ).splitlines()
        if item and (status_path := item.split("\t", 1)) and len(status_path) == 2
        for status, path in [status_path]
        if status == "A"
    }
    raw_changes = [
        item
        for item in _git_text(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "--no-renames",
            "-r",
            candidate,
            evidence,
        ).splitlines()
        if item
    ]
    expected = {f"{EXPECTED_EVIDENCE_DIR}/{name}" for name in EVIDENCE_FILES}
    if len(raw_changes) != len(expected) or changed != expected:
        raise shared.EvidenceError(
            f"Wave A evidence commit file set drifted: missing={sorted(expected - changed)}, "
            f"unexpected={sorted(changed - expected)}"
        )


def _assert_exact_child_delta(
    parent: str,
    child: str,
    expected_paths: Sequence[str],
    expected_status: str | dict[str, str],
    context: str,
) -> None:
    _assert_raw_direct_parent(parent, child, context)
    records = [
        item.split("\t", 1)
        for item in _git_text(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "--no-renames",
            "-r",
            parent,
            child,
        ).splitlines()
        if item
    ]
    expected_statuses = (
        {path: expected_status for path in expected_paths}
        if isinstance(expected_status, str)
        else expected_status
    )
    if set(expected_statuses) != set(expected_paths):
        raise shared.EvidenceError(f"{context} status contract is incomplete")
    if (
        len(records) != len(expected_paths)
        or any(
            len(item) != 2 or expected_statuses.get(item[1]) != item[0]
            for item in records
        )
        or {item[1] for item in records} != set(expected_paths)
    ):
        raise shared.EvidenceError(f"{context} delta is not the exact authorized file set")
    for path in expected_paths:
        _git_blob(child, path)


def _assert_tooling_commit(evidence: str, tooling: str) -> None:
    statuses = {
        path: "M" if path in STATE_TRANSITION_FILES else "A"
        for path in TOOLING_FILES
    }
    _assert_exact_child_delta(
        evidence, tooling, TOOLING_FILES, statuses, "Wave A acceptance tooling"
    )
    for path, expected_oid in EXPECTED_TOOLING_PLAN_BLOB_OIDS.items():
        _value, oid = _git_blob_record(tooling, path)
        if oid != expected_oid:
            raise shared.EvidenceError(
                f"Wave A acceptance tooling plan blob drifted at {path}"
            )


def _junit_totals(value: bytes, command_id: str) -> tuple[dict[str, int], set[str]]:
    try:
        root = ET.fromstring(value)
    except ET.ParseError as exception:
        raise shared.EvidenceError(f"{command_id} JUnit is invalid: {exception}") from exception
    if (
        root.attrib.get("candidate_commit") != EXPECTED_TESTED_CANDIDATE
        or root.attrib.get("source_command_id") != command_id
    ):
        raise shared.EvidenceError(f"{command_id} JUnit identity drifted")
    cases = list(root.iter("testcase"))
    totals = {
        "tests": len(cases),
        "failures": sum(len(case.findall("failure")) for case in cases),
        "errors": sum(len(case.findall("error")) for case in cases),
        "skipped": sum(len(case.findall("skipped")) for case in cases),
    }
    declared = {key: int(root.attrib.get(key, "-1")) for key in totals}
    if declared != totals:
        raise shared.EvidenceError(f"{command_id} JUnit declared totals drifted")
    identities = {
        f"{case.attrib.get('classname', '')}::{case.attrib.get('name', '')}"
        for case in cases
    }
    if len(identities) != len(cases):
        raise shared.EvidenceError(f"{command_id} JUnit contains duplicate test identities")
    return totals, identities


def _validate_index(blobs: dict[str, bytes]) -> None:
    index = _strict_json(blobs["artifact-sha256.json"], "Wave A artifact index")
    if set(index) != {"schema_version", "candidate_commit", "artifacts"} or (
        index.get("schema_version") != "phase5-wave-a-artifact-index.v1"
        or index.get("candidate_commit") != EXPECTED_TESTED_CANDIDATE
    ):
        raise shared.EvidenceError("Wave A artifact index identity drifted")
    records = index.get("artifacts")
    if not isinstance(records, list) or any(not isinstance(item, dict) for item in records):
        raise shared.EvidenceError("Wave A artifact index records are invalid")
    expected_order = [name for name in EVIDENCE_FILES if name != "artifact-sha256.json"]
    if (
        len(records) != len(expected_order)
        or [item.get("path") for item in records] != expected_order
        or any(set(item) != {"path", "sha256", "bytes"} for item in records)
    ):
        raise shared.EvidenceError("Wave A artifact index file set drifted")
    for item in records:
        name = item["path"]
        if item.get("sha256") != _sha256(blobs[name]) or item.get("bytes") != len(blobs[name]):
            raise shared.EvidenceError(f"Wave A artifact index hash drifted for {name}")


def _validate_task_bindings(value: bytes) -> dict[str, Any]:
    document = _strict_json(value, "Wave A task bindings")
    if (
        document.get("schema_version") != wave_runner.TASK_BINDINGS_SCHEMA
        or document.get("candidate_commit") != EXPECTED_TESTED_CANDIDATE
    ):
        raise shared.EvidenceError("Wave A task bindings identity drifted")
    tasks = document.get("tasks")
    if not isinstance(tasks, list) or [item.get("id") for item in tasks] != list(
        wave_runner.TASK_REQUIREMENTS
    ):
        raise shared.EvidenceError("Wave A task binding set drifted")
    for item in tasks:
        reviewer, commands = wave_runner.TASK_REQUIREMENTS[item["id"]]
        commit = _commit(item.get("commit", ""), f"{item['id']} commit")
        if (
            item.get("review_partner") != reviewer
            or item.get("p0_review") != "PASS"
            or item.get("t0") != {"result": "PASS", "command_ids": list(commands)}
        ):
            raise shared.EvidenceError(f"{item['id']} review or T0 binding drifted")
        _assert_ancestor(commit, EXPECTED_TESTED_CANDIDATE, f"{item['id']} task commit")
    return document


def _validate_execution_manifest(value: bytes, blobs: dict[str, bytes]) -> dict[str, Any]:
    manifest = _strict_json(value, "Wave A execution manifest")
    shared._assert_execution_manifest_seal(manifest)
    if (
        manifest.get("schema_version") != wave_runner.SCHEMA_VERSION
        or manifest.get("phase") != 5
        or manifest.get("batch") != wave_runner.BATCH_ID
        or manifest.get("candidate_commit") != EXPECTED_TESTED_CANDIDATE
        or manifest.get("status") != "PASS"
        or manifest.get("batch_1") != "PASS_AWAITING_EVIDENCE_COMMIT"
        or manifest.get("wave_a_barrier")
        != "BLOCKED_PENDING_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE"
        or manifest.get("promotion_gate") != "PENDING"
        or manifest.get("MIG-004") != "PENDING_PROMOTION"
        or manifest.get("MIG-005") != "PENDING_PROMOTION"
        or manifest.get("pending_failure") is not None
    ):
        raise shared.EvidenceError("Wave A execution manifest status or gate drifted")
    commands = manifest.get("commands")
    if not isinstance(commands, list) or [item.get("id") for item in commands] != list(
        SOURCE_EXPECTATIONS
    ):
        raise shared.EvidenceError("Wave A execution source set drifted")
    for item in commands:
        report, tests = SOURCE_EXPECTATIONS[item["id"]]
        if (
            item.get("candidate_commit") != EXPECTED_TESTED_CANDIDATE
            or item.get("report") != report
            or item.get("report_sha256") != _sha256(blobs[report])
            or item.get("tests") != tests
            or any(item.get(key) != 0 for key in ("failures", "errors", "skipped"))
            or item.get("accepted") is not True
        ):
            raise shared.EvidenceError(f"{item['id']} execution record drifted")
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise shared.EvidenceError("Wave A environment seal is missing")
    environment_digest = environment.get("snapshot_sha256")
    unsigned_environment = dict(environment)
    unsigned_environment.pop("snapshot_sha256", None)
    if environment_digest != wave_runner.shared._json_sha256(unsigned_environment):
        raise shared.EvidenceError("Wave A environment seal drifted")
    for dependency in environment.get("dependency_manifests", []):
        if not isinstance(dependency, dict) or not isinstance(dependency.get("path"), str):
            raise shared.EvidenceError("Wave A dependency manifest record is invalid")
        dependency_bytes = _git_blob(EXPECTED_TESTED_CANDIDATE, dependency["path"])
        authenticated_hashes = {
            _sha256(dependency_bytes),
            _sha256(dependency_bytes.replace(b"\n", b"\r\n")),
        }
        if dependency.get("sha256") not in authenticated_hashes:
            raise shared.EvidenceError(
                f"Wave A dependency hash drifted for {dependency['path']}"
            )
    return manifest


def _validate_metrics(
    value: bytes,
    blobs: dict[str, bytes],
    manifest: dict[str, Any],
    bindings: dict[str, Any],
) -> dict[str, Any]:
    metrics = _strict_json(value, "Wave A metrics")
    decision = metrics.get("checkpoint_decision")
    if (
        metrics.get("schema_version") != "phase5-wave-a-evidence.v1"
        or metrics.get("phase") != 5
        or metrics.get("batch") != "P5-BATCH-1"
        or metrics.get("candidate_commit") != EXPECTED_TESTED_CANDIDATE
        or metrics.get("result")
        != "PASS_AWAITING_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE"
        or not isinstance(decision, dict)
        or decision.get("wave_a_barrier")
        != "BLOCKED_UNTIL_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE"
        or decision.get("wave_b_execution") != "BLOCKED"
        or decision.get("evidence_commit_opens_wave_b") is not False
        or decision.get("promotion_gate") != "PENDING"
        or decision.get("MIG-004") != "PENDING_PROMOTION"
        or decision.get("MIG-005") != "PENDING_PROMOTION"
    ):
        raise shared.EvidenceError("Wave A metrics identity or pending gate drifted")
    totals = metrics.get("totals")
    if not isinstance(totals, dict) or any(
        totals.get(key) != value for key, value in EXPECTED_TOTALS.items()
    ):
        raise shared.EvidenceError("Wave A metrics totals are not 362/0/0/0")
    suites = metrics.get("source_suites")
    if not isinstance(suites, list) or [item.get("id") for item in suites] != list(
        SOURCE_EXPECTATIONS
    ):
        raise shared.EvidenceError("Wave A metrics source suites drifted")
    independent = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    identities: set[str] = set()
    for suite in suites:
        report, expected_tests = SOURCE_EXPECTATIONS[suite["id"]]
        parsed, suite_identities = _junit_totals(blobs[report], suite["id"])
        if identities.intersection(suite_identities):
            raise shared.EvidenceError("Wave A reports contain cross-source duplicate identities")
        identities.update(suite_identities)
        if (
            suite.get("report") != report
            or suite.get("sha256") != _sha256(blobs[report])
            or suite.get("candidate_commit") != EXPECTED_TESTED_CANDIDATE
            or parsed["tests"] != expected_tests
            or any(suite.get(key) != parsed[key] for key in independent)
        ):
            raise shared.EvidenceError(f"{suite['id']} metric/JUnit binding drifted")
        for key in independent:
            independent[key] += parsed[key]
    if independent != EXPECTED_TOTALS:
        raise shared.EvidenceError("Wave A independently parsed totals are not 362/0/0/0")
    manifest_ref = metrics.get("execution_manifest")
    binding_ref = metrics.get("task_bindings")
    if (
        not isinstance(manifest_ref, dict)
        or manifest_ref.get("path") != "phase5-wave-a-execution-manifest.json"
        or manifest_ref.get("sha256")
        != _sha256(blobs["phase5-wave-a-execution-manifest.json"])
        or manifest_ref.get("manifest_sha256") != manifest.get("manifest_sha256")
        or not isinstance(binding_ref, dict)
        or binding_ref.get("path") != "task-commit-bindings.json"
        or binding_ref.get("sha256") != _sha256(blobs["task-commit-bindings.json"])
        or binding_ref.get("tasks") != bindings.get("tasks")
    ):
        raise shared.EvidenceError("Wave A metrics manifest or binding reference drifted")
    restrictions = metrics.get("runtime_restrictions")
    if not isinstance(restrictions, dict) or any(
        restrictions.get(key) is not False
        for key in (
            "real_provider",
            "formal_evidence_sink",
            "temporal_evidence_allocation",
            "real_case_shadow",
            "promotion",
        )
    ):
        raise shared.EvidenceError("Wave A runtime restrictions drifted")
    return metrics


def authenticate_wave_a_evidence(
    downstream_commit: str, matrix: dict[str, Any] | None = None
) -> dict[str, Any]:
    _assert_history_safe()
    downstream = _commit(downstream_commit, "acceptance tooling candidate")
    contract = _acceptance_contract(matrix or _load_matrix(downstream))
    candidate = _commit(contract["tested_candidate_commit"], "tested candidate")
    evidence = _commit(contract["evidence_commit"], "evidence commit")
    if candidate == evidence or evidence == downstream:
        raise shared.EvidenceError("tested, evidence, and acceptance tooling commits must be distinct")
    _assert_ancestor(candidate, evidence, "tested candidate before evidence")
    _assert_ancestor(evidence, downstream, "evidence before acceptance tooling candidate")
    _assert_ancestor(EXPECTED_BASE_COMMIT, candidate, "accepted Wave A base")
    _assert_ancestor(EXPECTED_P5_ENTRY_EVIDENCE_COMMIT, candidate, "P5.0 entry evidence")
    _assert_exact_evidence_commit(candidate, evidence)
    _assert_tooling_commit(evidence, downstream)
    blobs: dict[str, bytes] = {}
    evidence_oids: dict[str, str] = {}
    for name in EVIDENCE_FILES:
        relative = f"{EXPECTED_EVIDENCE_DIR}/{name}"
        value, oid = _git_blob_record(evidence, relative)
        if _git_blob(downstream, relative) != value:
            raise shared.EvidenceError(f"Wave A evidence blob drifted after commit for {name}")
        blobs[name] = value
        evidence_oids[name] = oid
    if blobs["candidate-commit.txt"] != (candidate + "\n").encode("ascii"):
        raise shared.EvidenceError("Wave A candidate-commit.txt drifted")
    _validate_index(blobs)
    bindings = _validate_task_bindings(blobs["task-commit-bindings.json"])
    manifest = _validate_execution_manifest(
        blobs["phase5-wave-a-execution-manifest.json"], blobs
    )
    metrics = _validate_metrics(blobs["wave-a-metrics.json"], blobs, manifest, bindings)
    return {
        "tested_candidate_commit": candidate,
        "accepted_base_commit": EXPECTED_BASE_COMMIT,
        "evidence_commit": evidence,
        "acceptance_tooling_candidate_commit": downstream,
        "evidence_path": EXPECTED_EVIDENCE_DIR,
        "evidence_file_count": len(blobs),
        "evidence_tree_oid": _git_text("rev-parse", f"{evidence}^{{tree}}"),
        "artifact_index_sha256": _sha256(blobs["artifact-sha256.json"]),
        "artifact_index_blob_oid": evidence_oids["artifact-sha256.json"],
        "artifacts": [
            {"path": name, "sha256": _sha256(blobs[name]), "bytes": len(blobs[name])}
            for name in EVIDENCE_FILES
        ],
        "totals": {key: metrics["totals"][key] for key in EXPECTED_TOTALS},
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }


def _validate_acceptance_document(
    value: bytes, candidate: str, authenticated: dict[str, Any]
) -> dict[str, Any]:
    document = _strict_json(value, "Wave A acceptance evidence")
    allowed = {
        "schema_version",
        "phase",
        "checkpoint",
        "result",
        "tested_candidate_commit",
        "accepted_base_commit",
        "evidence_commit",
        "evidence_tree_oid",
        "artifact_index_sha256",
        "artifact_index_blob_oid",
        "acceptance_tooling_candidate_commit",
        "evidence_path",
        "evidence_file_count",
        "evidence_artifacts",
        "totals",
        "decision",
        "runtime_restrictions",
    }
    decision = document.get("decision")
    if set(document) != allowed or (
        document.get("schema_version") != "phase5-wave-a-acceptance.v1"
        or document.get("phase") != 5
        or document.get("checkpoint") != "P5-WAVE-A-INTEGRATED"
        or document.get("result") != "PASS_AWAITING_STATE_TRANSITION_COMMIT"
        or document.get("tested_candidate_commit") != EXPECTED_TESTED_CANDIDATE
        or document.get("accepted_base_commit") != EXPECTED_BASE_COMMIT
        or document.get("evidence_commit") != EXPECTED_EVIDENCE_COMMIT
        or document.get("evidence_tree_oid") != authenticated["evidence_tree_oid"]
        or document.get("artifact_index_sha256")
        != authenticated["artifact_index_sha256"]
        or document.get("artifact_index_blob_oid")
        != authenticated["artifact_index_blob_oid"]
        or document.get("acceptance_tooling_candidate_commit") != candidate
        or document.get("evidence_path") != EXPECTED_EVIDENCE_DIR
        or document.get("evidence_file_count") != 8
        or document.get("evidence_artifacts") != authenticated["artifacts"]
        or document.get("totals") != EXPECTED_TOTALS
        or not isinstance(decision, dict)
        or set(decision) != ACCEPTANCE_DECISION_KEYS
        or decision.get("P5-WAVE-A-INTEGRATED")
        != "BLOCKED_PENDING_STATE_TRANSITION_COMMIT"
        or decision.get("wave_b") != "BLOCKED_PENDING_STATE_TRANSITION_COMMIT"
        or decision.get("evidence_commit_alone_opens_wave_b") is not False
        or decision.get("state_transition_commit_required") is not True
        or decision.get("acceptance_commit_is_derived_from_git_history") is not True
        or decision.get("promotion_gate") != "PENDING"
        or decision.get("MIG-004") != "PENDING_PROMOTION"
        or decision.get("MIG-005") != "PENDING_PROMOTION"
    ):
        raise shared.EvidenceError("Wave A acceptance evidence status or binding drifted")
    restrictions = document.get("runtime_restrictions")
    if (
        not isinstance(restrictions, dict)
        or set(restrictions) != RUNTIME_RESTRICTION_KEYS
        or any(restrictions[key] is not False for key in RUNTIME_RESTRICTION_KEYS)
    ):
        raise shared.EvidenceError("Wave A acceptance runtime restrictions drifted")
    return document


def _replace_exact_once(
    value: bytes, old: bytes, new: bytes, context: str
) -> bytes:
    if value.count(old) != 1:
        raise shared.EvidenceError(
            f"Wave A state transition pre-image drifted at {context}"
        )
    return value.replace(old, new, 1)


def _binding_lines(bindings: dict[str, str], indent: int) -> bytes:
    spaces = b" " * indent
    return b"".join(
        spaces + key.encode("ascii") + b": " + bindings[key].encode("ascii") + b"\n"
        for key in (
            "tested_candidate_commit",
            "evidence_commit",
            "acceptance_tooling_candidate_commit",
            "acceptance_evidence_commit",
        )
    )


def expected_state_transition_postimages(
    preimages: dict[str, bytes], bindings: dict[str, str]
) -> dict[str, bytes]:
    if set(preimages) != set(STATE_TRANSITION_FILES):
        raise shared.EvidenceError("Wave A state transition pre-image set drifted")
    if set(bindings) != {
        "tested_candidate_commit",
        "evidence_commit",
        "acceptance_tooling_candidate_commit",
        "acceptance_evidence_commit",
    } or any(not __import__("re").fullmatch(r"[0-9a-f]{40}", value) for value in bindings.values()):
        raise shared.EvidenceError("Wave A state transition bindings are invalid")
    _strict_yaml(preimages[TEST_MATRIX_PATH], "pre-transition Phase 5 matrix")
    _strict_yaml(preimages[OWNER_BRIEFS_PATH], "pre-transition Phase 5 owner briefs")

    matrix = preimages[TEST_MATRIX_PATH]
    matrix = _replace_exact_once(
        matrix,
        b"  wave_a:\n    status: READY\n",
        b"  wave_a:\n    status: INTEGRATED\n",
        "Wave A status",
    )
    matrix = _replace_exact_once(
        matrix,
        b"  wave_b:\n    status: BLOCKED_ON_WAVE_A_INTEGRATION\n",
        b"  wave_b:\n    status: READY\n",
        "Wave B status",
    )
    matrix = _replace_exact_once(
        matrix,
        b"    acceptance:\n      status: READY_FOR_SEPARATE_ACCEPTANCE_RUN\n",
        b"    acceptance:\n      status: ACCEPTED_BY_STATE_TRANSITION\n"
        b"      accepted_bindings:\n"
        + _binding_lines(bindings, 8),
        "acceptance status and bindings",
    )

    briefs = preimages[OWNER_BRIEFS_PATH]
    briefs = _replace_exact_once(
        briefs,
        b"  P5-WAVE-A-INTEGRATED:\n    status: BLOCKED\n",
        b"  P5-WAVE-A-INTEGRATED:\n    status: OPEN\n"
        b"    accepted_bindings:\n"
        + _binding_lines(bindings, 6),
        "integration barrier status and bindings",
    )
    briefs = _replace_exact_once(
        briefs,
        b"  post_wave_a_migration_contract_gate:\n"
        b"    task_id: P5-R2\n"
        b"    status: BLOCKED_ON_WAVE_A_ACCEPTANCE\n",
        b"  post_wave_a_migration_contract_gate:\n"
        b"    task_id: P5-R2\n"
        b"    status: READY\n",
        "P5-R2 gate status",
    )

    plan = preimages[EXECUTION_PLAN_PATH]
    if not plan.endswith(b"\n"):
        raise shared.EvidenceError("Wave A execution plan pre-image is not LF terminated")
    if b"### Wave A Acceptance State Transition Record" in plan:
        raise shared.EvidenceError("Wave A execution plan already contains a transition record")
    record = (
        b"\n### Wave A Acceptance State Transition Record\n\n"
        + b"- Tested candidate: `"
        + bindings["tested_candidate_commit"].encode("ascii")
        + b"`.\n- Evidence commit: `"
        + bindings["evidence_commit"].encode("ascii")
        + b"`.\n- Acceptance tooling commit: `"
        + bindings["acceptance_tooling_candidate_commit"].encode("ascii")
        + b"`.\n- Acceptance evidence commit: `"
        + bindings["acceptance_evidence_commit"].encode("ascii")
        + b"`.\n- Decision: `P5-WAVE-A-INTEGRATED=OPEN`, `Wave B=READY`, `P5-R2=READY`.\n"
        b"- Guard state: candidate wave remains blocked; runtime, traffic, canary, promotion, `MIG-004`, and `MIG-005` remain unchanged.\n"
    )
    return {
        EXECUTION_PLAN_PATH: plan + record,
        TEST_MATRIX_PATH: matrix,
        OWNER_BRIEFS_PATH: briefs,
    }


def _assert_exact_state_transition_postimages(
    preimages: dict[str, bytes],
    postimages: dict[str, bytes],
    bindings: dict[str, str],
) -> None:
    expected = expected_state_transition_postimages(preimages, bindings)
    if set(postimages) != set(expected):
        raise shared.EvidenceError("Wave A state transition post-image set drifted")
    for path, value in expected.items():
        if postimages[path] != value:
            raise shared.EvidenceError(
                f"Wave A state transition contains an unauthorized mutation in {path}"
            )


def _validate_acceptance_index(
    value: bytes, candidate: str, acceptance_blobs: dict[str, bytes]
) -> dict[str, Any]:
    index = _strict_json(value, "Wave A acceptance artifact index")
    expected_order = ["accepted-tooling-candidate.txt", "phase5-wave-a-acceptance.json"]
    records = index.get("artifacts")
    if (
        set(index)
        != {"schema_version", "acceptance_tooling_candidate_commit", "artifacts"}
        or index.get("schema_version")
        != "phase5-wave-a-acceptance-artifact-index.v1"
        or index.get("acceptance_tooling_candidate_commit") != candidate
        or not isinstance(records, list)
        or len(records) != len(expected_order)
        or [item.get("path") for item in records if isinstance(item, dict)]
        != expected_order
        or any(
            not isinstance(item, dict)
            or set(item) != {"path", "sha256", "bytes"}
            for item in records
        )
    ):
        raise shared.EvidenceError("Wave A acceptance artifact index drifted")
    if set(acceptance_blobs) != set(expected_order):
        raise shared.EvidenceError("Wave A acceptance blob set drifted")
    for item in records:
        blob = acceptance_blobs[item["path"]]
        if item["sha256"] != _sha256(blob) or item["bytes"] != len(blob):
            raise shared.EvidenceError(
                f"Wave A acceptance artifact index hash drifted for {item['path']}"
            )
    return index


def verify_state_transition(
    state_transition_commit: str, expected_tooling_commit: str
) -> dict[str, Any]:
    _assert_history_safe()
    state_commit = _commit(state_transition_commit, "Wave A state transition commit")
    expected_tooling = _commit(
        expected_tooling_commit, "expected reviewed acceptance tooling commit"
    )
    state_parents = _raw_parent_commits(state_commit)
    if len(state_parents) != 1:
        raise shared.EvidenceError("Wave A state transition must have one raw parent")
    acceptance_commit = _commit(
        state_parents[0], "Wave A acceptance evidence commit"
    )
    acceptance_relative = f"{EXPECTED_ACCEPTANCE_DIR}/phase5-wave-a-acceptance.json"
    candidate_relative = f"{EXPECTED_ACCEPTANCE_DIR}/accepted-tooling-candidate.txt"
    index_relative = f"{EXPECTED_ACCEPTANCE_DIR}/artifact-sha256.json"
    candidate_bytes = _git_blob(acceptance_commit, candidate_relative)
    try:
        tooling_candidate = _commit(
            candidate_bytes.decode("ascii").removesuffix("\n"),
            "acceptance tooling candidate",
        )
    except UnicodeDecodeError as exception:
        raise shared.EvidenceError("acceptance tooling candidate file is not ASCII") from exception
    if candidate_bytes != (tooling_candidate + "\n").encode("ascii"):
        raise shared.EvidenceError("acceptance tooling candidate file drifted")
    if tooling_candidate != expected_tooling:
        raise shared.EvidenceError(
            "Wave A acceptance tooling commit does not match the reviewed expected SHA"
        )
    _assert_exact_child_delta(
        tooling_candidate,
        acceptance_commit,
        [f"{EXPECTED_ACCEPTANCE_DIR}/{name}" for name in ACCEPTANCE_FILES],
        "A",
        "Wave A acceptance evidence",
    )
    authenticated = authenticate_wave_a_evidence(tooling_candidate)
    acceptance_bytes = _git_blob(acceptance_commit, acceptance_relative)
    index_bytes = _git_blob(acceptance_commit, index_relative)
    document = _validate_acceptance_document(
        acceptance_bytes, tooling_candidate, authenticated
    )
    acceptance_blobs = {
        "accepted-tooling-candidate.txt": candidate_bytes,
        "phase5-wave-a-acceptance.json": acceptance_bytes,
    }
    _validate_acceptance_index(index_bytes, tooling_candidate, acceptance_blobs)
    for relative, expected in (
        (candidate_relative, candidate_bytes),
        (acceptance_relative, acceptance_bytes),
        (index_relative, index_bytes),
    ):
        if _git_blob(state_commit, relative) != expected:
            raise shared.EvidenceError("Wave A acceptance evidence drifted before transition")
    _assert_exact_child_delta(
        acceptance_commit,
        state_commit,
        STATE_TRANSITION_FILES,
        "M",
        "Wave A state transition",
    )
    bindings = {
        "tested_candidate_commit": EXPECTED_TESTED_CANDIDATE,
        "evidence_commit": EXPECTED_EVIDENCE_COMMIT,
        "acceptance_tooling_candidate_commit": tooling_candidate,
        "acceptance_evidence_commit": acceptance_commit,
    }
    preimages = {
        path: _git_blob(acceptance_commit, path) for path in STATE_TRANSITION_FILES
    }
    postimages = {
        path: _git_blob(state_commit, path) for path in STATE_TRANSITION_FILES
    }
    _assert_exact_state_transition_postimages(preimages, postimages, bindings)
    matrix = _strict_yaml(
        postimages[TEST_MATRIX_PATH],
        "state-transition Phase 5 matrix",
    )
    briefs = _strict_yaml(
        postimages[OWNER_BRIEFS_PATH],
        "state-transition Phase 5 owner briefs",
    )
    acceptance_state = matrix["batches"]["P5-BATCH-1"]["acceptance"]
    barrier = briefs["integration_barriers"]["P5-WAVE-A-INTEGRATED"]
    if (
        matrix["waves"]["wave_a"]["status"] != "INTEGRATED"
        or matrix["waves"]["wave_b"]["status"] != "READY"
        or matrix["waves"]["candidate_wave"]["status"]
        != "BLOCKED_ON_WAVE_B_AND_ENGINEERING_EVIDENCE"
        or acceptance_state.get("status") != "ACCEPTED_BY_STATE_TRANSITION"
        or acceptance_state.get("accepted_bindings") != bindings
        or barrier.get("status") != "OPEN"
        or barrier.get("accepted_bindings") != bindings
        or briefs["primary_integration_only"]["post_wave_a_migration_contract_gate"].get(
            "status"
        )
        != "READY"
        or matrix["gate"]["accepted_entry_state"].get("promotion_gate") != "PENDING"
        or matrix["gate"]["accepted_entry_state"].get("MIG-004")
        != "PENDING_PROMOTION"
        or matrix["gate"]["accepted_entry_state"].get("MIG-005")
        != "PENDING_PROMOTION"
    ):
        raise shared.EvidenceError("Wave A state transition did not apply the exact target state")
    return {
        "status": "PASS",
        "state_transition_commit": state_commit,
        "acceptance_evidence_commit": acceptance_commit,
        "acceptance_tooling_candidate_commit": tooling_candidate,
        "tested_candidate_commit": document["tested_candidate_commit"],
        "evidence_commit": document["evidence_commit"],
        "P5-WAVE-A-INTEGRATED": "OPEN",
        "wave_b": "READY",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }


def _write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    shared.seal_execution_manifest(manifest)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(
        (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    )
    os.replace(temporary, path)


def execute_acceptance(candidate_commit: str, run_root: Path) -> dict[str, Any]:
    candidate = _commit(candidate_commit, "acceptance tooling candidate")
    run_root = run_root.resolve()
    shared.assert_candidate_run_directory(run_root)
    shared.assert_clean_detached_candidate(candidate)
    if run_root.exists():
        raise shared.EvidenceError("Wave A acceptance run directory already exists")
    authenticated = authenticate_wave_a_evidence(candidate)
    run_root.mkdir(parents=True)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "phase": 5,
        "checkpoint": "P5-WAVE-A-INTEGRATED",
        "status": "PASS_AWAITING_ACCEPTANCE_EVIDENCE_COMMIT",
        "authenticated_handoff": authenticated,
        "decision_after_acceptance_bundle_commit": {
            "acceptance_result": "PASS_AWAITING_STATE_TRANSITION_COMMIT",
            "P5-WAVE-A-INTEGRATED": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "wave_b": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "evidence_commit_alone_opens_wave_b": False,
            "state_transition_commit_required": True,
            "promotion_gate": "PENDING",
            "MIG-004": "PENDING_PROMOTION",
            "MIG-005": "PENDING_PROMOTION",
        },
    }
    _write_manifest(run_root / MANIFEST_NAME, manifest)
    shared.assert_clean_detached_candidate(candidate, allowed_untracked_roots=(run_root,))
    return manifest


def load_pass_manifest(path: Path, candidate_commit: str) -> dict[str, Any]:
    candidate = _commit(candidate_commit, "acceptance tooling candidate")
    try:
        manifest = _strict_json(path.read_bytes(), "Wave A acceptance execution manifest")
    except OSError as exception:
        raise shared.EvidenceError(f"cannot read acceptance manifest: {exception}") from exception
    shared._assert_execution_manifest_seal(manifest)
    if (
        path.name != MANIFEST_NAME
        or manifest.get("schema_version") != SCHEMA_VERSION
        or manifest.get("phase") != 5
        or manifest.get("checkpoint") != "P5-WAVE-A-INTEGRATED"
        or manifest.get("status") != "PASS_AWAITING_ACCEPTANCE_EVIDENCE_COMMIT"
        or manifest.get("authenticated_handoff", {}).get(
            "acceptance_tooling_candidate_commit"
        )
        != candidate
    ):
        raise shared.EvidenceError("Wave A acceptance manifest identity drifted")
    authenticated = authenticate_wave_a_evidence(candidate)
    if manifest.get("authenticated_handoff") != authenticated:
        raise shared.EvidenceError("Wave A acceptance manifest handoff drifted")
    return manifest


def candidate_plan(candidate_commit: str) -> dict[str, Any]:
    candidate = _commit(candidate_commit, "acceptance tooling candidate")
    contract = _acceptance_contract(_load_matrix(candidate))
    return {
        "schema_version": "phase5-wave-a-acceptance-plan.v1",
        "candidate_commit": candidate,
        "tested_candidate_commit": contract["tested_candidate_commit"],
        "evidence_commit": contract["evidence_commit"],
        "evidence_path": contract["evidence_path"],
        "expected_evidence_files": list(EVIDENCE_FILES),
        "expected_totals": EXPECTED_TOTALS,
        "pre_run_barrier": "BLOCKED",
        "post_run_status": "PASS_AWAITING_ACCEPTANCE_EVIDENCE_COMMIT",
        "post_commit_decision": {
            "acceptance_result": "PASS_AWAITING_STATE_TRANSITION_COMMIT",
            "P5-WAVE-A-INTEGRATED": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "wave_b": "BLOCKED_PENDING_STATE_TRANSITION_COMMIT",
            "promotion_gate": "PENDING",
            "MIG-005": "PENDING_PROMOTION",
        },
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Authenticate P5 Wave A Git evidence before a separate acceptance commit."
    )
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--verify-state-transition", action="store_true")
    parser.add_argument("--expected-tooling-commit")
    parser.add_argument("--run-dir", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if arguments.verify_state_transition:
            if arguments.execute or arguments.run_dir is not None:
                raise shared.EvidenceError(
                    "--verify-state-transition cannot be combined with execution options"
                )
            if arguments.expected_tooling_commit is None:
                raise shared.EvidenceError(
                    "--expected-tooling-commit is required with --verify-state-transition"
                )
            print(
                json.dumps(
                    verify_state_transition(
                        arguments.candidate_commit, arguments.expected_tooling_commit
                    ),
                    sort_keys=True,
                )
            )
            return 0
        if not arguments.execute:
            if arguments.run_dir is not None:
                raise shared.EvidenceError("--run-dir requires --execute")
            if arguments.expected_tooling_commit is not None:
                raise shared.EvidenceError(
                    "--expected-tooling-commit requires --verify-state-transition"
                )
            print(json.dumps(candidate_plan(arguments.candidate_commit), indent=2))
            return 0
        if arguments.run_dir is None:
            raise shared.EvidenceError("--run-dir is required with --execute")
        manifest = execute_acceptance(arguments.candidate_commit, arguments.run_dir)
    except (shared.EvidenceError, OSError, KeyError, TypeError, ValueError) as exception:
        print(f"Phase 5 Wave A acceptance rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "status": manifest["status"],
                "tested_candidate_commit": manifest["authenticated_handoff"][
                    "tested_candidate_commit"
                ],
                "evidence_commit": manifest["authenticated_handoff"]["evidence_commit"],
                "manifest": str((arguments.run_dir / MANIFEST_NAME).resolve()),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
