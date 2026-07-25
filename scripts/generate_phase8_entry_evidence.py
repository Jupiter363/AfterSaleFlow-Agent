from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterator, Sequence

try:
    from scripts import run_phase8_entry_checkpoint as runner
except (ImportError, ModuleNotFoundError):
    import run_phase8_entry_checkpoint as runner  # type: ignore[no-redef]


ROOT = Path(__file__).resolve().parents[1]
ATTRIBUTES_NAME = ".gitattributes"
INDEX_NAME = "artifact-sha256.json"
CANDIDATE_NAME = "candidate.txt"
MANIFEST_NAME = "phase8-entry-execution-manifest.json"
REPORT_NAME = "static-phase8-entry.xml"
ENVIRONMENT_NAME = "source-tree-environment.json"
P0_REVIEW_NAME = "p0-review-disposition.json"
DECISION_NAME = "phase8-entry-decision.json"
PROVENANCE_NAME = "provenance-manifest.json"
STDOUT_NAME = "p/00-stdout.log"
STDERR_NAME = "p/01-stderr.log"
RAW_JUNIT_NAME = "p/02-junit.xml"

ATTRIBUTES_BYTES = b"* -text\n**/* -text\n"
INDEX_SCHEMA = "phase8-entry-artifact-index.v1"
ENVIRONMENT_SCHEMA = "phase8-entry-source-tree-environment.v1"
P0_REVIEW_SCHEMA = "phase8-entry-p0-review-disposition.v1"
DECISION_SCHEMA = "phase8-entry-decision.v1"
PROVENANCE_SCHEMA = "phase8-entry-provenance-manifest.v1"
RESULT_CEILING = "PASS_AWAITING_CHECKPOINT_A8"
NEXT_PERMISSION = "PENDING_A8_CHECKPOINT"
MIGRATION_GATES = ("MIG-006", "MIG-007", "MIG-008")
P0_TOPICS = (
    "P0-P8-HANDOFF-001",
    "P0-P8-ENTRY-TOPOLOGY-002",
    "P0-P8-BASELINE-003",
    "P0-P8-REFERENCE-004",
    "P0-P8-SCHEDULER-005",
    "P0-P8-V046-006",
    "P0-P8-V047-007",
    "P0-P8-RELEASE-008",
    "P0-P8-RECOVERY-009",
    "P0-P8-PRIVACY-010",
    "P0-P8-TEAM-011",
    "P0-P8-TEST-LIMITS-012",
    "P0-P8-AUTHORITY-013",
)
P0_LANES = ("authority", "data_migration", "security_privacy")
P0_LANE_TOPICS = {
    "authority": (
        "P0-P8-HANDOFF-001",
        "P0-P8-ENTRY-TOPOLOGY-002",
        "P0-P8-BASELINE-003",
        "P0-P8-SCHEDULER-005",
        "P0-P8-RELEASE-008",
        "P0-P8-RECOVERY-009",
        "P0-P8-TEAM-011",
        "P0-P8-TEST-LIMITS-012",
        "P0-P8-AUTHORITY-013",
    ),
    "data_migration": (
        "P0-P8-REFERENCE-004",
        "P0-P8-V046-006",
        "P0-P8-V047-007",
        "P0-P8-RECOVERY-009",
        "P0-P8-AUTHORITY-013",
    ),
    "security_privacy": (
        "P0-P8-ENTRY-TOPOLOGY-002",
        "P0-P8-RELEASE-008",
        "P0-P8-PRIVACY-010",
        "P0-P8-AUTHORITY-013",
    ),
}
SOURCE_HASH_NAMES = (
    MANIFEST_NAME,
    REPORT_NAME,
    ENVIRONMENT_NAME,
    STDOUT_NAME,
    STDERR_NAME,
    RAW_JUNIT_NAME,
)
INDEXED_NAMES = (
    ATTRIBUTES_NAME,
    CANDIDATE_NAME,
    MANIFEST_NAME,
    REPORT_NAME,
    ENVIRONMENT_NAME,
    P0_REVIEW_NAME,
    DECISION_NAME,
    PROVENANCE_NAME,
    STDOUT_NAME,
    STDERR_NAME,
    RAW_JUNIT_NAME,
)
EXPECTED_NAMES = frozenset((INDEX_NAME, *INDEXED_NAMES))
SOURCE_BINDINGS = (
    ("execution_manifest", MANIFEST_NAME, MANIFEST_NAME, None),
    ("normalized_junit", REPORT_NAME, REPORT_NAME, "normalized_report_sha256"),
    ("environment", ENVIRONMENT_NAME, ENVIRONMENT_NAME, None),
    ("stdout", STDOUT_NAME, STDOUT_NAME, "stdout_sha256"),
    ("stderr", STDERR_NAME, STDERR_NAME, "stderr_sha256"),
    ("raw_junit", RAW_JUNIT_NAME, RAW_JUNIT_NAME, "raw_report_sha256"),
)


def _error(message: str) -> Exception:
    return runner.EvidenceError(message)


def _canonical_json_bytes(document: Any) -> bytes:
    return (
        json.dumps(
            document,
            ensure_ascii=True,
            indent=2,
            sort_keys=True,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _write_json(path: Path, document: Any) -> None:
    path.write_bytes(_canonical_json_bytes(document))


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _load_json_bytes(payload: bytes, context: str) -> dict[str, Any]:
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise _error(f"cannot parse {context}: {exception}") from exception
    if not isinstance(document, dict):
        raise _error(f"{context} must be a JSON object")
    return document


def _load_canonical_json_bytes(payload: bytes, context: str) -> dict[str, Any]:
    document = _load_json_bytes(payload, context)
    if payload != _canonical_json_bytes(document):
        raise _error(f"{context} must use canonical LF JSON bytes")
    return document


def _release_id(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(
        r"[a-z0-9][a-z0-9._-]{2,79}", value
    ):
        raise _error(
            "release ID must be 3-80 lowercase letters, digits, dots, underscores, or hyphens"
        )
    return value


def _candidate(value: str, context: str = "candidate commit") -> str:
    validator = getattr(runner, "_assert_candidate", None)
    if callable(validator):
        try:
            return str(validator(value, context))
        except TypeError:
            return str(validator(value))
    normalized = value.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", normalized):
        raise _error(f"{context} must be one full SHA-1")
    return normalized


def _safe_process_environment() -> dict[str, str]:
    allowed = {
        "COMSPEC",
        "LANG",
        "LC_ALL",
        "PATH",
        "PATHEXT",
        "SYSTEMROOT",
        "TEMP",
        "TMP",
        "WINDIR",
    }
    environment = {
        key: value for key, value in os.environ.items() if key.upper() in allowed
    }
    environment.update(
        {
            "GIT_ATTR_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_NO_REPLACE_OBJECTS": "1",
        }
    )
    return environment


def _git_bytes(*arguments: str) -> bytes:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        env=_safe_process_environment(),
        capture_output=True,
        check=False,
    )
    if process.returncode:
        detail = process.stderr.decode("utf-8", errors="replace").strip()
        raise _error(f"git {' '.join(arguments)} failed: {detail}")
    return process.stdout


def _git_text(*arguments: str) -> str:
    return _git_bytes(*arguments).decode("utf-8", errors="strict")


def _assert_no_git_object_substitution() -> None:
    environment = _safe_process_environment()
    replace = subprocess.run(
        ["git", "for-each-ref", "--format=%(refname)", "refs/replace"],
        cwd=ROOT,
        env=environment,
        capture_output=True,
        check=False,
    )
    if replace.returncode or replace.stdout.strip():
        raise _error("Git replace-object state is forbidden for Phase 8 evidence")
    common = subprocess.run(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        cwd=ROOT,
        env=environment,
        capture_output=True,
        check=False,
    )
    if common.returncode:
        raise _error("cannot authenticate the Phase 8 Git common directory")
    common_dir = Path(common.stdout.decode("utf-8", errors="strict").strip())
    grafts = common_dir / "info" / "grafts"
    if grafts.exists() and grafts.stat().st_size:
        raise _error("Git graft state is forbidden for Phase 8 evidence")


def _candidate_scope(candidate: str) -> dict[str, Any]:
    _assert_no_git_object_substitution()
    validator = getattr(runner, "assert_contract_candidate", None)
    if not callable(validator):
        raise _error("Phase 8 runner does not expose assert_contract_candidate")
    scope = validator(candidate)
    if not isinstance(scope, dict) or scope.get("candidate_sha") != candidate:
        raise _error("cannot authenticate the exact C8 candidate scope")
    return scope


def _reviewed_path_blobs(candidate: str) -> list[dict[str, str]]:
    paths = tuple(getattr(runner, "C8_ALLOWED_PATHS", ()))
    if len(paths) != 12:
        raise _error("Phase 8 runner C8 allowlist drifted")
    return [
        {
            "path": path,
            "sha256": _sha256_bytes(_git_bytes("show", f"{candidate}:{path}")),
        }
        for path in paths
    ]


def _safe_relative(value: str, context: str) -> str:
    normalized = value.replace("\\", "/")
    path = PurePosixPath(normalized)
    if (
        not normalized
        or normalized != path.as_posix()
        or path.is_absolute()
        or path.anchor
        or any(part in {"", ".", ".."} or ":" in part for part in path.parts)
    ):
        raise _error(f"{context} path is unsafe: {value}")
    return normalized


def _file_attributes(metadata: os.stat_result) -> int:
    return int(getattr(metadata, "st_file_attributes", 0))


def _is_reparse(metadata: os.stat_result) -> bool:
    return bool(
        _file_attributes(metadata)
        & int(getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))
    )


def _lstat_no_reparse(path: Path, context: str) -> os.stat_result:
    try:
        metadata = path.lstat()
        junction_check = getattr(path, "is_junction", None)
        junction = bool(junction_check()) if callable(junction_check) else False
    except OSError as exception:
        raise _error(
            f"cannot authenticate {context} {path}: {exception}"
        ) from exception
    if stat.S_ISLNK(metadata.st_mode) or junction or _is_reparse(metadata):
        raise _error(
            f"{context} must not be a symlink, junction, or reparse point: {path}"
        )
    return metadata


def _assert_path_chain_no_reparse(path: Path, context: str) -> None:
    absolute = path.absolute()
    parents = list(reversed(absolute.parents))
    for component in (*parents, absolute):
        if component == Path(component.anchor):
            continue
        _lstat_no_reparse(component, context)


def _ancestry_identities(
    path: Path, context: str
) -> tuple[tuple[str, tuple[int, ...]], ...]:
    absolute = path.absolute()
    records: list[tuple[str, tuple[int, ...]]] = []
    for component in (*reversed(absolute.parents), absolute):
        if component == Path(component.anchor):
            continue
        records.append(
            (str(component), _directory_identity(_lstat_no_reparse(component, context)))
        )
    return tuple(records)


def _assert_single_regular(metadata: os.stat_result, context: str) -> None:
    if not stat.S_ISREG(metadata.st_mode):
        raise _error(f"{context} must be a regular file")
    if int(getattr(metadata, "st_nlink", 1)) != 1:
        raise _error(f"{context} must have exactly one filesystem link")


def _identity(metadata: os.stat_result) -> tuple[int, ...]:
    base = (
        int(metadata.st_dev),
        int(metadata.st_ino),
        int(stat.S_IFMT(metadata.st_mode)),
    )
    return base if os.name == "nt" else (*base, int(metadata.st_ctime_ns))


def _directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        int(metadata.st_dev),
        int(metadata.st_ino),
        int(stat.S_IFMT(metadata.st_mode)),
    )


def _version(metadata: os.stat_result) -> tuple[int, int]:
    return int(metadata.st_size), int(metadata.st_mtime_ns)


def _read_no_follow(
    path: Path, context: str
) -> tuple[bytes, tuple[int, ...], tuple[int, int]]:
    lexical = path.absolute()
    ancestry_before = _ancestry_identities(lexical, context)
    before = _lstat_no_reparse(lexical, context)
    _assert_single_regular(before, context)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(lexical, flags)
    except OSError as exception:
        raise _error(
            f"cannot open {context} without following links: {exception}"
        ) from exception
    try:
        opened = os.fstat(descriptor)
        _assert_single_regular(opened, context)
        if _identity(opened) != _identity(before) or _version(opened) != _version(
            before
        ):
            raise _error(f"{context} changed between lstat and no-follow open")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after = os.fstat(descriptor)
        payload = b"".join(chunks)
        if (
            _identity(after) != _identity(opened)
            or _version(after) != _version(opened)
            or len(payload) != after.st_size
        ):
            raise _error(f"{context} changed during authenticated read")
    finally:
        os.close(descriptor)
    final = _lstat_no_reparse(lexical, context)
    if _identity(final) != _identity(after) or _version(final) != _version(after):
        raise _error(f"{context} changed after authenticated read")
    if _ancestry_identities(lexical, context) != ancestry_before:
        raise _error(f"{context} ancestry changed during authenticated read")
    return payload, _identity(after), _version(after)


def _same_object_descendant(path: Path, root: Path, context: str) -> bool:
    try:
        root.stat()
    except FileNotFoundError:
        return False
    except OSError as exception:
        raise _error(
            f"cannot authenticate forbidden root {root}: {exception}"
        ) from exception
    current = path
    while True:
        try:
            if os.path.samefile(current, root):
                return True
        except OSError as exception:
            raise _error(
                f"cannot authenticate {context} ancestry: {exception}"
            ) from exception
        if current.parent == current:
            return False
        current = current.parent


def _candidate_tracks(candidate: str, path: Path) -> bool:
    try:
        relative = path.resolve(strict=False).relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return False
    return bool(_git_bytes("ls-tree", "-z", candidate, "--", relative))


@dataclass(frozen=True)
class P0Snapshot:
    candidate: str
    path: Path
    forbidden_roots: tuple[Path, ...]
    payload: bytes
    identity: tuple[int, ...]
    version: tuple[int, int]
    document: dict[str, Any]


def _source_hashes(blobs: dict[str, bytes]) -> dict[str, str]:
    return {name: _sha256_bytes(blobs[name]) for name in SOURCE_HASH_NAMES}


def _validate_p0_document(
    document: dict[str, Any],
    candidate: str,
    *,
    source_hashes: dict[str, str] | None = None,
) -> None:
    expected = {
        "candidate_commit",
        "candidate_diff",
        "candidate_changed_paths",
        "candidate_tree_sha",
        "closed_finding_ids",
        "cryptographic_production_attestation",
        "disposition_author_id",
        "independent_disposition",
        "open_p0_count",
        "production_reuse",
        "review_lanes",
        "review_scope",
        "reviewed_path_blobs",
        "reviewed_topics",
        "schema_version",
        "self_approved",
        "source_artifact_sha256",
        "status",
        "trust_ceiling",
    }
    scope = _candidate_scope(candidate)
    lanes = document.get("review_lanes")
    reviewed_blobs = document.get("reviewed_path_blobs")
    artifact_hashes = document.get("source_artifact_sha256")
    disposition_author = document.get("disposition_author_id")
    lane_ids = (
        [lane.get("lane") for lane in lanes if isinstance(lane, dict)]
        if isinstance(lanes, list)
        else []
    )
    reviewers = (
        [lane.get("reviewer_id") for lane in lanes if isinstance(lane, dict)]
        if isinstance(lanes, list)
        else []
    )

    def receipt_is_valid(lane: Any) -> bool:
        if not isinstance(lane, dict) or lane.get("lane") not in P0_LANE_TOPICS:
            return False
        receipt = lane.get("receipt")
        reviewer_id = lane.get("reviewer_id")
        expected_receipt = {
            "candidate_commit": candidate,
            "candidate_diff_sha256": _sha256_bytes(
                _canonical_json_bytes(scope.get("candidate_diff"))
            ),
            "candidate_tree_sha": scope.get("candidate_tree_sha"),
            "closed_finding_ids": list(P0_LANE_TOPICS[lane["lane"]]),
            "disposition": "ALL_P0_CLOSED",
            "lane": lane["lane"],
            "open_p0_count": 0,
            "reviewed_path_blobs_sha256": _sha256_bytes(
                _canonical_json_bytes(reviewed_blobs)
            ),
            "reviewed_topics": list(P0_LANE_TOPICS[lane["lane"]]),
            "reviewer_id": reviewer_id,
            "schema_version": "phase8-entry-p0-lane-review-receipt.v1",
            "self_approved": False,
            "source_artifact_set_sha256": _sha256_bytes(
                _canonical_json_bytes(artifact_hashes)
            ),
        }
        return (
            set(lane)
            == {
                "disposition",
                "lane",
                "open_p0_count",
                "receipt",
                "review_receipt_sha256",
                "reviewer_id",
                "self_approved",
            }
            and lane.get("disposition") == "ALL_P0_CLOSED"
            and lane.get("open_p0_count") == 0
            and lane.get("self_approved") is False
            and isinstance(reviewer_id, str)
            and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:@/-]{2,127}", reviewer_id)
            is not None
            and reviewer_id.lower() not in {"self", "author", "generator", "runner"}
            and receipt == expected_receipt
            and lane.get("review_receipt_sha256")
            == _sha256_bytes(_canonical_json_bytes(receipt))
        )

    if (
        set(document) != expected
        or document.get("schema_version") != P0_REVIEW_SCHEMA
        or document.get("candidate_commit") != candidate
        or document.get("candidate_tree_sha") != scope.get("candidate_tree_sha")
        or document.get("candidate_diff") != scope.get("candidate_diff")
        or document.get("candidate_changed_paths")
        != list(getattr(runner, "C8_ALLOWED_PATHS", ()))
        or reviewed_blobs != _reviewed_path_blobs(candidate)
        or document.get("independent_disposition") is not True
        or document.get("self_approved") is not False
        or document.get("cryptographic_production_attestation") is not False
        or document.get("production_reuse") != "FORBIDDEN"
        or document.get("trust_ceiling")
        != "ENGINEERING_PROCESS_ATTESTATION_NON_HOSTILE_LOCAL_OPERATOR"
        or not isinstance(disposition_author, str)
        or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:@/-]{2,127}", disposition_author)
        is None
        or document.get("review_scope") != "CONSOLIDATED_POST_INTEGRATION_P0_ONLY"
        or document.get("status") != "ALL_P0_CLOSED"
        or document.get("open_p0_count") != 0
        or document.get("reviewed_topics") != list(P0_TOPICS)
        or document.get("closed_finding_ids") != list(P0_TOPICS)
        or not isinstance(artifact_hashes, dict)
        or set(artifact_hashes) != set(SOURCE_HASH_NAMES)
        or any(
            re.fullmatch(r"[0-9a-f]{64}", str(artifact_hashes.get(name))) is None
            for name in SOURCE_HASH_NAMES
        )
        or source_hashes is not None
        and artifact_hashes != source_hashes
        or not isinstance(lanes, list)
        or len(lanes) != 3
        or lane_ids != list(P0_LANES)
        or len(set(reviewers)) != 3
        or disposition_author in reviewers
        or any(not receipt_is_valid(lane) for lane in lanes)
    ):
        raise _error(
            "P0 review disposition must bind the exact candidate, source evidence, and three independent lane receipts"
        )


def _external_p0_path(
    candidate: str, path: Path, forbidden_roots: Sequence[Path]
) -> Path:
    if not path.is_absolute():
        raise _error("P0 review disposition must be an explicit absolute path")
    lexical = path.absolute()
    _assert_path_chain_no_reparse(lexical, "P0 review disposition")
    _assert_single_regular(
        _lstat_no_reparse(lexical, "P0 review disposition"), "P0 review disposition"
    )
    resolved = lexical.resolve(strict=True)
    if _candidate_tracks(candidate, resolved):
        raise _error("P0 review disposition is tracked by the candidate")
    for raw_root in forbidden_roots:
        lexical_root = raw_root.absolute()
        resolved_root = lexical_root.resolve(strict=False)
        if (
            resolved == resolved_root
            or resolved.is_relative_to(resolved_root)
            or _same_object_descendant(lexical, lexical_root, "P0 review disposition")
        ):
            raise _error(
                "P0 review disposition must be external to candidate, run, and output paths"
            )
    return resolved


def snapshot_p0_review(
    path: Path, candidate_commit: str, *, forbidden_roots: Sequence[Path]
) -> P0Snapshot:
    candidate = _candidate(candidate_commit)
    roots = tuple(root.absolute() for root in forbidden_roots)
    resolved = _external_p0_path(candidate, path, roots)
    payload, identity, version = _read_no_follow(resolved, "P0 review disposition")
    document = _load_canonical_json_bytes(payload, "P0 review disposition")
    _validate_p0_document(document, candidate)
    return P0Snapshot(candidate, resolved, roots, payload, identity, version, document)


def _revalidate_p0_snapshot(snapshot: P0Snapshot) -> None:
    path = _external_p0_path(
        snapshot.candidate, snapshot.path, snapshot.forbidden_roots
    )
    payload, identity, version = _read_no_follow(path, "P0 review disposition")
    if (
        identity != snapshot.identity
        or version != snapshot.version
        or _sha256_bytes(payload) != _sha256_bytes(snapshot.payload)
    ):
        raise _error("external P0 review disposition changed after snapshot")


def _load_green_manifest(path: Path, candidate: str) -> dict[str, Any]:
    loader = getattr(runner, "load_green_manifest", None)
    if not callable(loader):
        raise _error("Phase 8 runner does not expose load_green_manifest")
    manifest = loader(path, candidate)
    if not isinstance(manifest, dict):
        raise _error("Phase 8 runner returned an invalid green manifest")
    return manifest


_MANIFEST_KEYS = {
    "MIG-006",
    "MIG-007",
    "MIG-008",
    "accepted_phase_7_candidate_C7",
    "accepted_phase_7_checkpoint_A7",
    "accepted_phase_7_evidence_E7",
    "candidate_changed_paths",
    "candidate_commit",
    "candidate_diff",
    "candidate_parent",
    "candidate_sha",
    "candidate_tree_sha",
    "commands",
    "contract_gate",
    "dependency_git_blobs",
    "environment",
    "environment_file",
    "environment_sha256",
    "git_tree_clean_after",
    "git_tree_clean_before",
    "implementation",
    "implementation_authorized",
    "manifest_sha256",
    "p8_0_contract_gate",
    "phase",
    "production_capabilities",
    "quarantine_used",
    "release",
    "report_reuse_used",
    "resume_used",
    "retry_count",
    "schema_version",
    "status",
    "verification_finished_at",
    "verification_started_at",
}
_COMMAND_KEYS = {
    "accepted",
    "argv",
    "argv_sha256",
    "candidate_sha_after",
    "candidate_sha_before",
    "cwd",
    "duration_ms",
    "ended_at",
    "errors",
    "exit_code",
    "failure_classification",
    "failures",
    "id",
    "normalized_report_path",
    "normalized_report_sha256",
    "raw_report_path",
    "raw_report_sha256",
    "report_kind",
    "resource_class",
    "shell",
    "skipped",
    "started_at",
    "stderr_path",
    "stderr_sha256",
    "stdout_path",
    "stdout_sha256",
    "tests",
}
_ENVIRONMENT_KEYS = {
    "architecture",
    "candidate_sha",
    "candidate_tree_sha",
    "captured_at",
    "command_argv_sha256",
    "dependency_git_blobs",
    "environment_id",
    "git_version",
    "os",
    "os_release",
    "python_executable",
    "python_implementation",
    "python_version",
    "schema_version",
    "snapshot_sha256",
    "source_git_blobs",
    "timezone",
}


def _rendered_raw_report_path(argv: Any) -> Path | None:
    template = tuple(getattr(runner, "ARGV_TEMPLATE", ()))
    if not isinstance(argv, list) or len(argv) != len(template):
        return None
    raw_path: Path | None = None
    for actual, expected in zip(argv, template, strict=True):
        if not isinstance(actual, str):
            return None
        if "{absolute_raw_report}" not in expected:
            if actual != expected:
                return None
            continue
        prefix, suffix = expected.split("{absolute_raw_report}", 1)
        if not actual.startswith(prefix) or not actual.endswith(suffix):
            return None
        rendered = actual[len(prefix) : len(actual) - len(suffix) if suffix else None]
        path = Path(rendered)
        if (
            not path.is_absolute()
            or path.name != "02-junit.xml"
            or path.parent.name != "p"
        ):
            return None
        raw_path = path
    return raw_path


def _validate_exact_argv(argv: Any) -> bool:
    return _rendered_raw_report_path(argv) is not None


def _path_key(path: Path) -> str:
    return os.path.normcase(os.path.normpath(str(path)))


def _command(manifest: dict[str, Any], candidate: str) -> dict[str, Any]:
    records = manifest.get("commands")
    if (
        not isinstance(records, list)
        or len(records) != 1
        or not isinstance(records[0], dict)
    ):
        raise _error("Phase 8 entry manifest must contain exactly one command")
    record = records[0]
    source_id = getattr(runner, "SOURCE_ID", "static_phase8_entry")
    expected_paths = {
        "stdout_path": STDOUT_NAME,
        "stderr_path": STDERR_NAME,
        "raw_report_path": RAW_JUNIT_NAME,
        "normalized_report_path": REPORT_NAME,
    }
    if (
        set(record) != set(getattr(runner, "COMMAND_KEYS", _COMMAND_KEYS))
        or record.get("id") != source_id
        or record.get("accepted") is not True
        or record.get("exit_code") != 0
        or record.get("candidate_sha_before") != candidate
        or record.get("candidate_sha_after") != candidate
        or record.get("cwd") != "."
        or record.get("resource_class") != "light"
        or record.get("shell") is not False
        or record.get("report_kind") != "PYTEST_JUNIT"
        or record.get("failure_classification") != "NONE"
        or not isinstance(record.get("duration_ms"), int)
        or record["duration_ms"] < 0
        or not _validate_exact_argv(record.get("argv"))
        or record.get("argv_sha256") != runner._json_sha256(record["argv"])
        or any(record.get(field) != value for field, value in expected_paths.items())
        or any(record.get(field) != 0 for field in ("failures", "errors", "skipped"))
        or not isinstance(record.get("tests"), int)
        or record["tests"] < 24
    ):
        raise _error(
            "Phase 8 entry command authority, path, candidate, or result drifted"
        )
    for field in (
        "stdout_sha256",
        "stderr_sha256",
        "raw_report_sha256",
        "normalized_report_sha256",
    ):
        if not re.fullmatch(r"[0-9a-f]{64}", str(record.get(field))):
            raise _error(f"Phase 8 entry command {field} is invalid")
    return record


def _validate_manifest_claims(
    manifest: dict[str, Any], candidate: str
) -> dict[str, Any]:
    scope = _candidate_scope(candidate)
    environment = manifest.get("environment")
    capabilities = manifest.get("production_capabilities")
    expected_capability_keys = set(getattr(runner, "PRODUCTION_CAPABILITY_KEYS", ()))
    if (
        set(manifest) != set(getattr(runner, "MANIFEST_KEYS", _MANIFEST_KEYS))
        or manifest.get("schema_version")
        != getattr(runner, "SCHEMA_VERSION", "phase8-entry-execution-manifest.v1")
        or manifest.get("status")
        != getattr(
            runner,
            "GREEN_STATUS",
            "SOURCES_GREEN_AWAITING_SOLE_PARENT_E8_ENTRY_EVIDENCE",
        )
        or manifest.get("candidate_sha") != candidate
        or manifest.get("candidate_commit") != candidate
        or manifest.get("candidate_parent") != getattr(runner, "A7", None)
        or manifest.get("candidate_parent") != scope.get("candidate_parent")
        or manifest.get("candidate_tree_sha") != scope.get("candidate_tree_sha")
        or manifest.get("candidate_changed_paths")
        != list(getattr(runner, "C8_ALLOWED_PATHS", ()))
        or manifest.get("candidate_changed_paths")
        != scope.get("candidate_changed_paths")
        or manifest.get("candidate_diff") != scope.get("candidate_diff")
        or manifest.get("dependency_git_blobs") != scope.get("dependency_blobs")
        or manifest.get("accepted_phase_7_authority") != scope.get("phase7_authority")
        or manifest.get("accepted_phase_7_candidate_C7") != getattr(runner, "C7", None)
        or manifest.get("accepted_phase_7_evidence_E7") != getattr(runner, "E7", None)
        or manifest.get("accepted_phase_7_checkpoint_A7") != getattr(runner, "A7", None)
        or manifest.get("phase") != 8
        or not re.fullmatch(
            rf"phase-8-entry-\d{{8}}-{re.escape(candidate[:12])}",
            str(manifest.get("release")),
        )
        or manifest.get("git_tree_clean_before") is not True
        or manifest.get("git_tree_clean_after") is not True
        or any(manifest.get(gate) != "PENDING_PROMOTION" for gate in MIGRATION_GATES)
        or manifest.get("contract_gate") != "P8.0_NOT_RUN"
        or manifest.get("p8_0_contract_gate") != "REMAINS_NOT_RUN_UNTIL_A8"
        or manifest.get("implementation_authorized") is not False
        or manifest.get("implementation") != "REMAINS_BLOCKED_UNTIL_A8"
        or manifest.get("retry_count") != 0
        or manifest.get("resume_used") is not False
        or manifest.get("report_reuse_used") is not False
        or manifest.get("quarantine_used") is not False
        or not isinstance(capabilities, dict)
        or set(capabilities) != expected_capability_keys
        or any(value is not False for value in capabilities.values())
        or manifest.get("environment_file") != ENVIRONMENT_NAME
        or re.fullmatch(r"[0-9a-f]{64}", str(manifest.get("environment_sha256")))
        is None
        or manifest.get("self_seal_trust")
        != "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION"
        or manifest.get("local_threat_model")
        != "HOSTILE_LOCAL_ADMIN_OR_OPERATOR_OUT_OF_SCOPE"
        or manifest.get("production_attestation_requirement")
        != "EXTERNALLY_ATTESTED_CI_OIDC_KMS_OR_EQUIVALENT_SIGNED_EXECUTION_RECEIPT"
    ):
        raise _error(
            "Phase 8 green manifest authority or migration-gate claims drifted"
        )
    environment_id = (
        environment.get("environment_id") if isinstance(environment, dict) else None
    )
    if not isinstance(environment_id, str) or not re.fullmatch(
        r"(?:local|synthetic)-[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", environment_id
    ):
        raise _error("Phase 8 environment_id must be a strict local/synthetic/ci token")
    record = _command(manifest, candidate)
    if (
        not isinstance(environment, dict)
        or set(environment)
        != set(getattr(runner, "ENVIRONMENT_KEYS", _ENVIRONMENT_KEYS))
        or environment.get("schema_version") != ENVIRONMENT_SCHEMA
        or environment.get("candidate_sha") != candidate
        or environment.get("candidate_tree_sha") != scope.get("candidate_tree_sha")
        or environment.get("dependency_git_blobs") != scope.get("dependency_blobs")
        or environment.get("source_git_blobs")
        != [
            row
            for row in scope.get("dependency_blobs", [])
            if row.get("path") in getattr(runner, "SELECTORS", ())
        ]
        or environment.get("python_executable") != record["argv"][0]
        or environment.get("command_argv_sha256") != record["argv_sha256"]
        or environment.get("subprocess_environment_keys")
        != sorted(runner._subprocess_environment())
        or environment.get("pytest_plugin_autoload_disabled") is not True
    ):
        raise _error("Phase 8 environment source/Git/command binding drifted")
    unsigned_environment = dict(environment)
    environment_seal = unsigned_environment.pop("snapshot_sha256", None)
    duplicate_environment_seal = unsigned_environment.pop("environment_sha256", None)
    calculated_environment_seal = runner._json_sha256(unsigned_environment)
    if (
        environment_seal != calculated_environment_seal
        or duplicate_environment_seal != calculated_environment_seal
    ):
        raise _error("Phase 8 environment snapshot seal drifted")
    attempt = manifest.get("attempt_ledger")
    run_dir = (
        Path(attempt["run_dir"])
        if isinstance(attempt, dict) and isinstance(attempt.get("run_dir"), str)
        else Path()
    )
    attempt_path = (
        Path(attempt["path"])
        if isinstance(attempt, dict) and isinstance(attempt.get("path"), str)
        else Path()
    )
    argv_raw_path = _rendered_raw_report_path(record.get("argv"))
    if (
        not isinstance(attempt, dict)
        or set(attempt)
        != {"attempt_number", "candidate_sha", "path", "run_dir", "sha256"}
        or attempt.get("attempt_number") != 1
        or attempt.get("candidate_sha") != candidate
        or not attempt_path.is_absolute()
        or attempt_path.name != f"{candidate}.json"
        or attempt_path.parent.name != ".phase8-entry-attempts"
        or not re.fullmatch(r"[0-9a-f]{64}", str(attempt.get("sha256")))
        or not run_dir.is_absolute()
        or run_dir.parent.name != ".codex-run"
        or argv_raw_path is None
        or _path_key(argv_raw_path) != _path_key(run_dir / "p" / "02-junit.xml")
        or _path_key(attempt_path)
        != _path_key(run_dir.parent / ".phase8-entry-attempts" / f"{candidate}.json")
    ):
        raise _error("Phase 8 attempt-ledger immutable binding drifted")
    try:
        started = datetime.fromisoformat(record["started_at"])
        ended = datetime.fromisoformat(record["ended_at"])
        captured = datetime.fromisoformat(environment["captured_at"])
    except (KeyError, TypeError, ValueError) as exception:
        raise _error("Phase 8 source timestamps are invalid") from exception
    release_match = re.fullmatch(
        rf"phase-8-entry-(\d{{8}})-{re.escape(candidate[:12])}",
        str(manifest.get("release")),
    )
    if (
        started.tzinfo is None
        or ended.tzinfo is None
        or captured.tzinfo is None
        or not captured <= started <= ended
        or manifest.get("verification_started_at") != record["started_at"]
        or manifest.get("verification_finished_at") != record["ended_at"]
        or release_match is None
        or release_match.group(1) != started.astimezone(timezone.utc).strftime("%Y%m%d")
    ):
        raise _error("Phase 8 source/release timestamp ordering drifted")
    return record


def _read_run_artifact(
    run_root: Path, relative: str, expected_sha: str | None
) -> bytes:
    safe = _safe_relative(relative, "runner artifact")
    path = run_root.joinpath(*PurePosixPath(safe).parts)
    try:
        resolved = path.resolve(strict=True)
        resolved.relative_to(run_root.resolve(strict=True))
    except (OSError, ValueError) as exception:
        raise _error(f"runner artifact escapes its run root: {relative}") from exception
    payload, _, _ = _read_no_follow(path, f"runner artifact {relative}")
    if expected_sha is not None and _sha256_bytes(payload) != expected_sha:
        raise _error(f"runner artifact SHA-256 drifted: {relative}")
    return payload


def _approved_run_root(run_root: Path) -> Path:
    resolved = run_root.resolve(strict=True)
    if resolved == ROOT.resolve() or resolved.is_relative_to(ROOT.resolve()):
        raise _error("Phase 8 run artifacts must be external to the candidate worktree")
    approved = next(
        (path for path in (resolved, *resolved.parents) if path.name == ".codex-run"),
        None,
    )
    if approved is None:
        raise _error(
            "Phase 8 run artifacts must be under an approved external .codex-run root"
        )
    _assert_path_chain_no_reparse(resolved, "Phase 8 run directory")
    if _same_object_descendant(resolved, ROOT.resolve(), "Phase 8 run directory"):
        raise _error("Phase 8 run directory aliases the candidate worktree")
    return resolved


def _run_source_names() -> tuple[str, ...]:
    return (
        MANIFEST_NAME,
        ENVIRONMENT_NAME,
        REPORT_NAME,
        STDOUT_NAME,
        STDERR_NAME,
        RAW_JUNIT_NAME,
    )


def _snapshot_run_sources(
    run_root: Path,
) -> dict[str, tuple[tuple[int, ...], tuple[int, int], str]]:
    snapshots: dict[str, tuple[tuple[int, ...], tuple[int, int], str]] = {}
    for relative in _run_source_names():
        path = run_root.joinpath(*PurePosixPath(relative).parts)
        payload, identity, version = _read_no_follow(
            path, f"runner artifact {relative}"
        )
        snapshots[relative] = identity, version, _sha256_bytes(payload)
    return snapshots


_SENSITIVE_PATTERNS = (
    ("private key", re.compile(rb"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----", re.I)),
    ("AWS access key", re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    (
        "bearer credential",
        re.compile(rb"\bBearer[ \t]+[A-Za-z0-9._~+/-]{20,}={0,2}\b", re.I),
    ),
    (
        "JWT",
        re.compile(
            rb"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"
        ),
    ),
    (
        "assigned secret",
        re.compile(
            rb"(?:password|passwd|api[_-]?key|client[_-]?secret|access[_-]?token)[\"']?\s*[:=]\s*[\"'][^\"'\r\n]{6,}[\"']",
            re.I,
        ),
    ),
    ("email address", re.compile(rb"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.I)),
    (
        "Chinese identity number",
        re.compile(rb"(?<![0-9A-Fa-f])\d{17}[0-9Xx](?![0-9A-Fa-f])"),
    ),
    ("hidden reasoning", re.compile(rb"<\s*(?:think|reasoning)\b", re.I)),
)


def _assert_privacy_safe(payload: bytes, context: str) -> None:
    for label, pattern in _SENSITIVE_PATTERNS:
        if pattern.search(payload):
            raise _error(f"{context} contains forbidden secret/PII material: {label}")


def _assert_privacy_safe_bundle(blobs: dict[str, bytes]) -> None:
    for name, payload in blobs.items():
        if name == ATTRIBUTES_NAME or name == CANDIDATE_NAME:
            continue
        _assert_privacy_safe(payload, f"Phase 8 evidence {name}")


def _junit_totals(
    payload: bytes, candidate: str, *, require_binding: bool = False
) -> dict[str, int | float]:
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exception:
        raise _error(f"normalized Phase 8 JUnit is invalid: {exception}") from exception
    suites = [root] if root.tag.rsplit("}", 1)[-1] == "testsuite" else list(root)
    cases = [
        case
        for suite in suites
        for case in suite.iter()
        if case.tag.rsplit("}", 1)[-1] == "testcase"
    ]
    failures = sum(
        1
        for case in cases
        for child in case
        if child.tag.rsplit("}", 1)[-1] == "failure"
    )
    errors = sum(
        1 for case in cases for child in case if child.tag.rsplit("}", 1)[-1] == "error"
    )
    skipped = sum(
        1
        for case in cases
        for child in case
        if child.tag.rsplit("}", 1)[-1] == "skipped"
    )
    candidates = {
        element.get("candidate_commit") or element.get("candidate_sha")
        for element in (root, *suites)
        if element.get("candidate_commit") or element.get("candidate_sha")
    }
    commands = {
        element.get("source_command_id")
        for element in (root, *suites)
        if element.get("source_command_id")
    }
    if (candidates and candidates != {candidate}) or (
        require_binding
        and (
            candidates != {candidate}
            or commands != {getattr(runner, "SOURCE_ID", "static_phase8_entry")}
        )
    ):
        raise _error("normalized Phase 8 JUnit candidate binding drifted")
    if len(cases) < 24 or failures or errors or skipped:
        raise _error("normalized Phase 8 JUnit must have at least 24 all-pass tests")
    return {
        "tests": len(cases),
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "time": round(sum(float(case.get("time", "0") or 0) for case in cases), 6),
    }


def _assert_raw_junit_matches(
    raw_payload: bytes,
    normalized_payload: bytes,
    candidate: str,
    command: dict[str, Any],
) -> dict[str, int | float]:
    raw = _junit_totals(raw_payload, candidate)
    normalized = _junit_totals(normalized_payload, candidate, require_binding=True)
    for field in ("tests", "failures", "errors", "skipped"):
        if raw[field] != normalized[field] or normalized[field] != command.get(field):
            raise _error("raw, normalized, and manifest JUnit totals disagree")
    return normalized


def _runtime_restrictions() -> dict[str, bool]:
    return {
        "implementation_authorized": False,
        "formal_business_authority": False,
        "real_case_or_party_data": False,
        "real_tool_effect": False,
        "temporal_outcome_allocation": False,
        "production_access": False,
        "production_apply_or_switch": False,
        "scheduler_off_activation": False,
        "v047_authoring": False,
        "production_load": False,
        "production_chaos": False,
        "production_pitr": False,
        "production_dr": False,
        "production_rotation": False,
        "production_soak": False,
        "production_traffic": False,
        "canary": False,
        "promotion": False,
    }


def _source_environment(manifest: dict[str, Any], candidate: str) -> dict[str, Any]:
    environment = manifest.get("environment")
    if (
        not isinstance(environment, dict)
        or environment.get("schema_version") != ENVIRONMENT_SCHEMA
        or environment.get("candidate_sha") != candidate
    ):
        raise _error("Phase 8 source-tree/environment authority drifted")
    return environment


def _provenance(
    *,
    candidate: str,
    source_payloads: dict[str, bytes],
    archived_payloads: dict[str, bytes],
) -> dict[str, Any]:
    artifacts = []
    for kind, source_name, archive_name, _ in SOURCE_BINDINGS:
        source = source_payloads[source_name]
        archived = archived_payloads[archive_name]
        artifacts.append(
            {
                "archive_bytes": len(archived),
                "archive_path": archive_name,
                "archive_sha256": _sha256_bytes(archived),
                "byte_identical": source == archived,
                "kind": kind,
                "source_bytes": len(source),
                "source_path": source_name,
                "source_sha256": _sha256_bytes(source),
            }
        )
    return {
        "artifacts": artifacts,
        "candidate_commit": candidate,
        "schema_version": PROVENANCE_SCHEMA,
    }


def _decision(
    *,
    release_id: str,
    candidate: str,
    totals: dict[str, int | float],
    blobs: dict[str, bytes],
) -> dict[str, Any]:
    return {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "artifacts": {
            MANIFEST_NAME: _sha256_bytes(blobs[MANIFEST_NAME]),
            ENVIRONMENT_NAME: _sha256_bytes(blobs[ENVIRONMENT_NAME]),
            P0_REVIEW_NAME: _sha256_bytes(blobs[P0_REVIEW_NAME]),
            PROVENANCE_NAME: _sha256_bytes(blobs[PROVENANCE_NAME]),
            REPORT_NAME: _sha256_bytes(blobs[REPORT_NAME]),
        },
        "candidate_commit": candidate,
        "decision_ceiling": RESULT_CEILING,
        "implementation_authorized": False,
        "next_phase_permission": NEXT_PERMISSION,
        "p8_0_contract_gate": "PENDING_A8_CHECKPOINT",
        "production_checkpoint": "PENDING_EXTERNAL",
        "promotion_gate": "PENDING",
        "release_id": release_id,
        "result": RESULT_CEILING,
        "runtime_restrictions": _runtime_restrictions(),
        "schema_version": DECISION_SCHEMA,
        "totals": totals,
    }


def _index(candidate: str, blobs: dict[str, bytes]) -> dict[str, Any]:
    return {
        "artifacts": [
            {
                "bytes": len(blobs[name]),
                "path": name,
                "sha256": _sha256_bytes(blobs[name]),
            }
            for name in INDEXED_NAMES
        ],
        "candidate_commit": candidate,
        "schema_version": INDEX_SCHEMA,
    }


def _assert_manifest_copy(payload: bytes, candidate: str) -> dict[str, Any]:
    document = _load_canonical_json_bytes(
        payload, "archived Phase 8 execution manifest"
    )
    _validate_manifest_claims(document, candidate)
    validator = getattr(runner, "_assert_manifest_seal", None)
    if callable(validator):
        validator(document)
    else:
        seal = document.get("manifest_sha256")
        unsigned = dict(document)
        unsigned.pop("manifest_sha256", None)
        compact = json.dumps(
            unsigned,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        if seal != _sha256_bytes(compact):
            raise _error("archived Phase 8 execution manifest seal drifted")
    return document


def _validate_documents(
    *, blobs: dict[str, bytes], candidate: str, release_id: str
) -> dict[str, Any]:
    if set(blobs) != EXPECTED_NAMES:
        raise _error("Phase 8 entry evidence file set drifted")
    if blobs[ATTRIBUTES_NAME] != ATTRIBUTES_BYTES:
        raise _error("Phase 8 entry byte-preservation attributes drifted")
    if blobs[CANDIDATE_NAME] != (candidate + "\n").encode("ascii"):
        raise _error("Phase 8 entry candidate binding drifted")
    documents = {
        name: _load_canonical_json_bytes(blobs[name], f"Phase 8 evidence {name}")
        for name in (
            INDEX_NAME,
            MANIFEST_NAME,
            ENVIRONMENT_NAME,
            P0_REVIEW_NAME,
            DECISION_NAME,
            PROVENANCE_NAME,
        )
    }
    manifest = _assert_manifest_copy(blobs[MANIFEST_NAME], candidate)
    if manifest.get("release") != release_id:
        raise _error(
            "Phase 8 evidence release does not match its authenticated execution manifest"
        )
    command = _command(manifest, candidate)
    totals = _assert_raw_junit_matches(
        blobs[RAW_JUNIT_NAME], blobs[REPORT_NAME], candidate, command
    )
    if any(
        command.get(field) != totals[field]
        for field in ("tests", "failures", "errors", "skipped")
    ):
        raise _error("Phase 8 command and normalized JUnit totals drifted")
    expected_env = _source_environment(manifest, candidate)
    if documents[ENVIRONMENT_NAME] != expected_env:
        raise _error("Phase 8 source-tree/environment document drifted")
    if manifest.get("environment_sha256") != _sha256_bytes(blobs[ENVIRONMENT_NAME]):
        raise _error("Phase 8 environment artifact hash drifted from the manifest")
    for name, field in (
        (REPORT_NAME, "normalized_report_sha256"),
        (STDOUT_NAME, "stdout_sha256"),
        (STDERR_NAME, "stderr_sha256"),
        (RAW_JUNIT_NAME, "raw_report_sha256"),
    ):
        if command.get(field) != _sha256_bytes(blobs[name]):
            raise _error(f"Phase 8 command artifact hash drifted: {name}")
    _validate_p0_document(
        documents[P0_REVIEW_NAME], candidate, source_hashes=_source_hashes(blobs)
    )
    provenance = documents[PROVENANCE_NAME]
    expected_provenance_paths = [(item[1], item[2]) for item in SOURCE_BINDINGS]
    records = provenance.get("artifacts")
    if (
        set(provenance) != {"artifacts", "candidate_commit", "schema_version"}
        or provenance.get("schema_version") != PROVENANCE_SCHEMA
        or provenance.get("candidate_commit") != candidate
        or not isinstance(records, list)
        or [
            (item.get("source_path"), item.get("archive_path"))
            for item in records
            if isinstance(item, dict)
        ]
        != expected_provenance_paths
    ):
        raise _error("Phase 8 provenance authority or coverage drifted")
    for record in records:
        if not isinstance(record, dict) or set(record) != {
            "archive_bytes",
            "archive_path",
            "archive_sha256",
            "byte_identical",
            "kind",
            "source_bytes",
            "source_path",
            "source_sha256",
        }:
            raise _error("Phase 8 provenance record field set drifted")
        archive = blobs.get(str(record["archive_path"]))
        if (
            archive is None
            or record["byte_identical"] is not True
            or record["archive_bytes"] != len(archive)
            or record["source_bytes"] != len(archive)
            or record["archive_sha256"] != _sha256_bytes(archive)
            or record["source_sha256"] != _sha256_bytes(archive)
        ):
            raise _error(
                f"Phase 8 provenance bytes drifted: {record.get('archive_path')}"
            )
    expected_decision = _decision(
        release_id=release_id, candidate=candidate, totals=totals, blobs=blobs
    )
    if documents[DECISION_NAME] != expected_decision:
        raise _error("Phase 8 entry decision drifted")
    if (
        expected_decision["result"] != RESULT_CEILING
        or expected_decision["next_phase_permission"] != NEXT_PERMISSION
        or expected_decision["implementation_authorized"] is not False
        or any(
            expected_decision[gate] != "PENDING_PROMOTION" for gate in MIGRATION_GATES
        )
        or any(expected_decision["runtime_restrictions"].values())
    ):
        raise _error("Phase 8 entry decision exceeded the pre-A8 ceiling")
    index = documents[INDEX_NAME]
    rows = index.get("artifacts")
    if (
        set(index) != {"artifacts", "candidate_commit", "schema_version"}
        or index.get("schema_version") != INDEX_SCHEMA
        or index.get("candidate_commit") != candidate
        or not isinstance(rows, list)
        or [row.get("path") if isinstance(row, dict) else None for row in rows]
        != list(INDEXED_NAMES)
        or INDEX_NAME in [row.get("path") for row in rows if isinstance(row, dict)]
    ):
        raise _error("Phase 8 artifact index must cover exactly the other eleven blobs")
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"bytes", "path", "sha256"}:
            raise _error("Phase 8 artifact index record drifted")
        payload = blobs.get(row["path"])
        if (
            payload is None
            or row["bytes"] != len(payload)
            or row["sha256"] != _sha256_bytes(payload)
        ):
            raise _error(f"Phase 8 indexed artifact drifted: {row.get('path')}")
    _assert_privacy_safe_bundle(blobs)
    return expected_decision


def _git_filter_environment(home: Path) -> dict[str, str]:
    environment = _safe_process_environment()
    environment.update(
        {
            "GIT_ATTR_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "HOME": str(home),
            "USERPROFILE": str(home),
            "XDG_CONFIG_HOME": str(home),
        }
    )
    return environment


def _isolated_git(
    repository: Path,
    environment: dict[str, str],
    *arguments: str,
    payload: bytes | None = None,
) -> bytes:
    process = subprocess.run(
        ["git", "-c", "core.autocrlf=input", *arguments],
        cwd=repository,
        env=environment,
        input=payload,
        capture_output=True,
        check=False,
    )
    if process.returncode:
        raise _error(
            "cannot validate Phase 8 evidence Git filters: "
            + process.stderr.decode("utf-8", errors="replace").strip()
        )
    return process.stdout


@contextmanager
def _git_filter_repository(
    logical_root: PurePosixPath,
) -> Iterator[tuple[Path, dict[str, str]]]:
    with tempfile.TemporaryDirectory(prefix="phase8-entry-git-filter-") as temporary:
        repository = Path(temporary)
        environment = _git_filter_environment(repository)
        _isolated_git(repository, environment, "init", "--quiet", "--template=")
        attributes = repository.joinpath(*(logical_root / ATTRIBUTES_NAME).parts)
        attributes.parent.mkdir(parents=True, exist_ok=True)
        attributes.write_bytes(ATTRIBUTES_BYTES)
        yield repository, environment


def _assert_git_filter_stable(
    payload: bytes,
    logical_path: str,
    logical_attributes: str,
    repository: Path,
    environment: dict[str, str],
    *,
    require_lf: bool,
) -> None:
    if require_lf and b"\r" in payload:
        raise _error(f"Phase 8 evidence contains non-LF bytes: {logical_path}")
    check = _isolated_git(
        repository, environment, "check-attr", "-z", "text", "--", logical_path
    )
    if check != f"{logical_path}\0text\0unset\0".encode("utf-8"):
        raise _error(f"Phase 8 evidence is not protected by -text: {logical_path}")
    plain = _isolated_git(
        repository,
        environment,
        "hash-object",
        "--no-filters",
        "--stdin",
        payload=payload,
    )
    filtered = _isolated_git(
        repository,
        environment,
        "hash-object",
        f"--path={logical_path}",
        "--stdin",
        payload=payload,
    )
    if plain != filtered:
        raise _error(
            f"Phase 8 evidence changes under Git clean filters: {logical_path}"
        )


def _walk_regular_bundle(output_dir: Path) -> dict[str, bytes]:
    root = output_dir.absolute()
    root_meta = _lstat_no_reparse(root, "Phase 8 evidence root")
    if not stat.S_ISDIR(root_meta.st_mode):
        raise _error("Phase 8 evidence root must be a directory")
    root_identity = _identity(root_meta)
    blobs: dict[str, bytes] = {}
    directory_snapshots: dict[Path, tuple[tuple[int, ...], tuple[int, int]]] = {}
    for directory, names, filenames in os.walk(root, followlinks=False):
        directory_path = Path(directory)
        directory_meta = _lstat_no_reparse(directory_path, "Phase 8 evidence directory")
        directory_snapshots[directory_path] = (
            _identity(directory_meta),
            _version(directory_meta),
        )
        for name in names:
            child = directory_path / name
            if not stat.S_ISDIR(
                _lstat_no_reparse(child, "Phase 8 evidence directory").st_mode
            ):
                raise _error(
                    f"Phase 8 evidence contains a non-directory parent: {child}"
                )
        for name in filenames:
            child = directory_path / name
            metadata = _lstat_no_reparse(child, "Phase 8 evidence artifact")
            _assert_single_regular(metadata, "Phase 8 evidence artifact")
            relative = child.relative_to(root).as_posix()
            if relative in blobs:
                raise _error(f"Phase 8 evidence path is duplicated: {relative}")
            payload, _, _ = _read_no_follow(child, "Phase 8 evidence artifact")
            blobs[relative] = payload
    final_root = _lstat_no_reparse(root, "Phase 8 evidence root")
    if _identity(final_root) != root_identity:
        raise _error("Phase 8 evidence root identity changed during validation")
    for directory, (identity, _version_before) in directory_snapshots.items():
        current = _lstat_no_reparse(directory, "Phase 8 evidence directory")
        if _identity(current) != identity:
            raise _error(
                "Phase 8 evidence directory identity changed during validation"
            )
    if set(blobs) != EXPECTED_NAMES:
        raise _error(
            "Phase 8 evidence exact file set drifted; "
            f"missing={sorted(EXPECTED_NAMES - set(blobs))}, extra={sorted(set(blobs) - EXPECTED_NAMES)}"
        )
    return blobs


def validate_bundle(
    output_dir: Path, candidate_commit: str, release_id: str
) -> dict[str, Any]:
    candidate = _candidate(candidate_commit)
    release = _release_id(release_id)
    blobs = _walk_regular_bundle(output_dir)
    logical_root = (
        PurePosixPath("test-reports") / "temporal-first" / release / "phase-8-entry"
    )
    logical_attributes = (logical_root / ATTRIBUTES_NAME).as_posix()
    canonical_lf = {
        INDEX_NAME,
        CANDIDATE_NAME,
        MANIFEST_NAME,
        REPORT_NAME,
        ENVIRONMENT_NAME,
        P0_REVIEW_NAME,
        DECISION_NAME,
        PROVENANCE_NAME,
    }
    with _git_filter_repository(logical_root) as (repository, environment):
        for name, payload in blobs.items():
            _assert_git_filter_stable(
                payload,
                (logical_root / name).as_posix(),
                logical_attributes,
                repository,
                environment,
                require_lf=name in canonical_lf,
            )
    return _validate_documents(blobs=blobs, candidate=candidate, release_id=release)


def _assert_clean_detached(candidate: str, allowed_untracked: Sequence[Path]) -> None:
    if _git_text("rev-parse", "HEAD").strip() != candidate:
        raise _error("candidate does not match detached HEAD")
    symbolic = subprocess.run(
        ["git", "symbolic-ref", "-q", "HEAD"],
        cwd=ROOT,
        env=_safe_process_environment(),
        capture_output=True,
        check=False,
    )
    if symbolic.returncode == 0:
        raise _error("Phase 8 evidence generation requires a detached worktree")
    allowed = [path.resolve(strict=False) for path in allowed_untracked]
    status = _git_bytes("status", "--porcelain=v1", "-z", "--untracked-files=all")
    for record in status.split(b"\0"):
        if not record:
            continue
        if not record.startswith(b"?? "):
            raise _error("Phase 8 candidate worktree has tracked changes")
        relative = record[3:].decode("utf-8", errors="strict")
        path = (ROOT / relative).resolve(strict=False)
        if not any(path == root or path.is_relative_to(root) for root in allowed):
            raise _error(
                f"Phase 8 candidate has unrelated untracked output: {relative}"
            )


def assemble_entry_evidence(
    *,
    manifest: dict[str, Any],
    manifest_path: Path,
    p0_snapshot: P0Snapshot,
    output_dir: Path,
    release_id: str,
    candidate_commit: str,
    output_precreated: bool = False,
) -> dict[str, Any]:
    candidate = _candidate(candidate_commit)
    release = _release_id(release_id)
    command = _validate_manifest_claims(manifest, candidate)
    if manifest.get("release") != release:
        raise _error("Phase 8 release ID must equal the authenticated runner release")
    run_root = _approved_run_root(manifest_path.resolve().parent)
    if _path_key(Path(manifest["attempt_ledger"]["run_dir"])) != _path_key(run_root):
        raise _error("Phase 8 manifest attempt ledger belongs to another run directory")
    if output_precreated:
        metadata = _lstat_no_reparse(output_dir, "Phase 8 evidence staging root")
        if not stat.S_ISDIR(metadata.st_mode) or any(output_dir.iterdir()):
            raise _error(
                "precreated Phase 8 evidence staging root is not empty and regular"
            )
    else:
        output_dir.mkdir(exist_ok=False)
    (output_dir / "p").mkdir(exist_ok=False)
    source_payloads: dict[str, bytes] = {}
    manifest_payload = _read_run_artifact(run_root, MANIFEST_NAME, None)
    if (
        _load_canonical_json_bytes(manifest_payload, "live execution manifest")
        != manifest
    ):
        raise _error("Phase 8 execution manifest changed after green validation")
    source_payloads[MANIFEST_NAME] = manifest_payload
    environment_payload = _read_run_artifact(
        run_root, ENVIRONMENT_NAME, str(manifest.get("environment_sha256"))
    )
    expected_environment = _source_environment(manifest, candidate)
    if (
        _load_canonical_json_bytes(
            environment_payload, "runner source-tree environment"
        )
        != expected_environment
    ):
        raise _error("runner source-tree environment claims drifted")
    source_payloads[ENVIRONMENT_NAME] = environment_payload
    for _, source_name, _, hash_field in SOURCE_BINDINGS[1:2] + SOURCE_BINDINGS[3:]:
        source_payloads[source_name] = _read_run_artifact(
            run_root, source_name, str(command.get(hash_field))
        )
    totals = _assert_raw_junit_matches(
        source_payloads[RAW_JUNIT_NAME],
        source_payloads[REPORT_NAME],
        candidate,
        command,
    )
    for source_name, payload in source_payloads.items():
        _assert_privacy_safe(payload, f"runner artifact {source_name}")
    _validate_p0_document(
        p0_snapshot.document,
        candidate,
        source_hashes=_source_hashes(source_payloads),
    )
    archived_payloads = dict(source_payloads)
    archived_payloads[ATTRIBUTES_NAME] = ATTRIBUTES_BYTES
    archived_payloads[CANDIDATE_NAME] = (candidate + "\n").encode("ascii")
    archived_payloads[P0_REVIEW_NAME] = p0_snapshot.payload
    provenance = _provenance(
        candidate=candidate,
        source_payloads=source_payloads,
        archived_payloads=archived_payloads,
    )
    archived_payloads[PROVENANCE_NAME] = _canonical_json_bytes(provenance)
    decision = _decision(
        release_id=release, candidate=candidate, totals=totals, blobs=archived_payloads
    )
    archived_payloads[DECISION_NAME] = _canonical_json_bytes(decision)
    archived_payloads[INDEX_NAME] = _canonical_json_bytes(
        _index(candidate, archived_payloads)
    )
    for name, payload in archived_payloads.items():
        target = output_dir.joinpath(*PurePosixPath(name).parts)
        with target.open("xb") as handle:
            handle.write(payload)
    validate_bundle(output_dir, candidate, release)
    return decision


def _ensure_output_parent(release_id: str) -> Path:
    target = ROOT / "test-reports" / "temporal-first" / release_id
    root = ROOT.resolve(strict=True)
    _assert_path_chain_no_reparse(root, "candidate worktree")
    current = root
    for part in ("test-reports", "temporal-first", release_id):
        current /= part
        try:
            current.mkdir()
        except FileExistsError:
            pass
        metadata = _lstat_no_reparse(current, "Phase 8 evidence parent")
        if not stat.S_ISDIR(metadata.st_mode):
            raise _error(f"Phase 8 evidence parent is not a directory: {current}")
    return target


def _assert_staging_identity(
    staging: Path,
    *,
    staging_identity: tuple[int, ...],
    parent_identity: tuple[int, ...],
    ancestry: tuple[tuple[str, tuple[int, ...]], ...],
) -> None:
    if (
        _directory_identity(_lstat_no_reparse(staging, "Phase 8 evidence staging root"))
        != staging_identity
        or _directory_identity(
            _lstat_no_reparse(staging.parent, "Phase 8 evidence staging parent")
        )
        != parent_identity
        or _ancestry_identities(staging, "Phase 8 evidence staging root") != ancestry
    ):
        raise _error("Phase 8 evidence staging identity or ancestry changed")


def _safe_remove_staging(
    staging: Path,
    *,
    staging_identity: tuple[int, ...] | None,
    parent_identity: tuple[int, ...] | None,
    ancestry: tuple[tuple[str, tuple[int, ...]], ...] | None,
) -> None:
    if not staging.exists() and not staging.is_symlink():
        return
    if staging_identity is None or parent_identity is None or ancestry is None:
        return
    try:
        _assert_staging_identity(
            staging,
            staging_identity=staging_identity,
            parent_identity=parent_identity,
            ancestry=ancestry,
        )
        metadata = _lstat_no_reparse(staging, "Phase 8 evidence staging root")
        if not stat.S_ISDIR(metadata.st_mode):
            raise _error("Phase 8 evidence staging root was replaced")
        for directory, names, filenames in os.walk(
            staging, topdown=False, followlinks=False
        ):
            directory_path = Path(directory)
            for name in filenames:
                child = directory_path / name
                child_meta = _lstat_no_reparse(child, "Phase 8 staging artifact")
                _assert_single_regular(child_meta, "Phase 8 staging artifact")
                child.unlink()
            for name in names:
                child = directory_path / name
                child_meta = _lstat_no_reparse(child, "Phase 8 staging directory")
                if not stat.S_ISDIR(child_meta.st_mode):
                    raise _error("Phase 8 staging directory was replaced")
                child.rmdir()
        staging.rmdir()
    except (OSError, runner.EvidenceError):
        # Fail closed and preserve a suspicious path for manual inspection.
        return


def generate_entry_evidence(
    *,
    release_id: str,
    candidate_commit: str,
    execution_manifest_path: Path,
    p0_review_disposition_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
    release = _release_id(release_id)
    candidate = _candidate(candidate_commit)
    if not execution_manifest_path.is_absolute():
        raise _error("execution manifest must be an explicit absolute path")
    if not p0_review_disposition_path.is_absolute():
        raise _error("P0 review disposition must be an explicit absolute path")
    manifest_path = execution_manifest_path.resolve(strict=True)
    run_root = _approved_run_root(manifest_path.parent)
    expected_parent = ROOT / "test-reports" / "temporal-first" / release
    expected_output = expected_parent / "phase-8-entry"
    output = output_dir.absolute()
    if output != expected_output.absolute():
        raise _error("Phase 8 E8 output must use the exact candidate evidence prefix")
    staging = output.with_name(f".{output.name}.assembling")
    if output.exists() or staging.exists():
        raise _error("Phase 8 evidence output or staging path already exists")
    manifest = _load_green_manifest(manifest_path, candidate)
    worktree_roots = tuple(
        Path(line.removeprefix("worktree "))
        for line in _git_text("worktree", "list", "--porcelain").splitlines()
        if line.startswith("worktree ")
    )
    snapshot = snapshot_p0_review(
        p0_review_disposition_path,
        candidate,
        forbidden_roots=(*worktree_roots, run_root, output, staging),
    )
    source_snapshot = _snapshot_run_sources(run_root)
    _assert_clean_detached(candidate, (run_root, staging))
    staging_identity: tuple[int, ...] | None = None
    parent_identity: tuple[int, ...] | None = None
    staging_ancestry: tuple[tuple[str, tuple[int, ...]], ...] | None = None
    try:
        actual_parent = _ensure_output_parent(release)
        if actual_parent.absolute() != expected_parent.absolute():
            raise _error("Phase 8 evidence parent drifted")
        parent_identity = _directory_identity(
            _lstat_no_reparse(actual_parent, "Phase 8 evidence staging parent")
        )
        staging.mkdir(exist_ok=False)
        staging_identity = _directory_identity(
            _lstat_no_reparse(staging, "Phase 8 evidence staging root")
        )
        staging_ancestry = _ancestry_identities(
            staging, "Phase 8 evidence staging root"
        )
        decision = assemble_entry_evidence(
            manifest=manifest,
            manifest_path=manifest_path,
            p0_snapshot=snapshot,
            output_dir=staging,
            release_id=release,
            candidate_commit=candidate,
            output_precreated=True,
        )
        _assert_clean_detached(candidate, (run_root, staging))
        validate_bundle(staging, candidate, release)
        if _snapshot_run_sources(run_root) != source_snapshot:
            raise _error(
                "Phase 8 runner artifacts changed after authenticated snapshot"
            )
        _revalidate_p0_snapshot(snapshot)
        _assert_staging_identity(
            staging,
            staging_identity=staging_identity,
            parent_identity=parent_identity,
            ancestry=staging_ancestry,
        )
        staging.rename(output)
        validate_bundle(output, candidate, release)
        return decision
    except Exception:
        _safe_remove_staging(
            staging,
            staging_identity=staging_identity,
            parent_identity=parent_identity,
            ancestry=staging_ancestry,
        )
        raise


def _assert_committed_regular_blobs(commit: str, expected_paths: set[str]) -> None:
    raw = _git_bytes("ls-tree", "-rz", commit, "--", *sorted(expected_paths))
    observed: set[str] = set()
    for entry in raw.split(b"\0"):
        if not entry:
            continue
        try:
            metadata, encoded = entry.split(b"\t", 1)
            mode, kind, _ = metadata.split(b" ", 2)
            path = encoded.decode("utf-8", errors="strict").replace("\\", "/")
        except (UnicodeDecodeError, ValueError) as exception:
            raise _error(
                "cannot authenticate committed Phase 8 evidence entry"
            ) from exception
        if path in observed or mode not in {b"100644", b"100755"} or kind != b"blob":
            raise _error(
                f"committed Phase 8 evidence must contain unique regular blobs: {path}"
            )
        observed.add(path)
    if observed != expected_paths:
        raise _error("committed Phase 8 evidence regular-blob topology drifted")


def verify_evidence_commit(
    *, evidence_commit: str, candidate_commit: str, release_id: str
) -> dict[str, Any]:
    evidence = _candidate(evidence_commit, "evidence commit")
    candidate = _candidate(candidate_commit)
    release = _release_id(release_id)
    _candidate_scope(candidate)
    parent = _git_text("rev-list", "--parents", "-n", "1", evidence).strip().split()
    if parent != [evidence, candidate]:
        raise _error("E8 must have exact C8 as its sole parent")
    prefix = f"test-reports/temporal-first/{release}/phase-8-entry"
    expected_paths = {f"{prefix}/{name}" for name in EXPECTED_NAMES}
    changed: set[str] = set()
    for line in _git_text(
        "diff-tree", "--no-commit-id", "--name-status", "-r", "--no-renames", evidence
    ).splitlines():
        fields = line.split("\t")
        if len(fields) != 2 or fields[0] != "A":
            raise _error("E8 may only add its immutable evidence bundle")
        changed.add(fields[1].replace("\\", "/"))
    if changed != expected_paths:
        raise _error(
            "E8 evidence-only scope drifted; "
            f"missing={sorted(expected_paths - changed)}, extra={sorted(changed - expected_paths)}"
        )
    _assert_committed_regular_blobs(evidence, expected_paths)
    blobs = {
        name: _git_bytes("show", f"{evidence}:{prefix}/{name}")
        for name in EXPECTED_NAMES
    }
    decision = _validate_documents(blobs=blobs, candidate=candidate, release_id=release)
    return {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "candidate_commit": candidate,
        "decision_ceiling": decision["decision_ceiling"],
        "evidence_commit": evidence,
        "next_phase_permission": decision["next_phase_permission"],
        "sole_parent_verified": True,
        "status": "E8_VERIFIED_AWAITING_A8_CHECKPOINT",
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Generate or verify the exact evidence-only Phase 8 P8.0 entry bundle."
    )
    parser.add_argument("--release-id", required=True)
    parser.add_argument(
        "--candidate-commit", "--candidate-sha", dest="candidate_commit", required=True
    )
    parser.add_argument("--execution-manifest", type=Path)
    parser.add_argument("--p0-review-disposition", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--verify-evidence-commit")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if arguments.verify_evidence_commit:
            if any(
                value is not None
                for value in (
                    arguments.execution_manifest,
                    arguments.p0_review_disposition,
                    arguments.output_dir,
                )
            ):
                raise _error(
                    "commit verification cannot be combined with generation inputs"
                )
            result = verify_evidence_commit(
                evidence_commit=arguments.verify_evidence_commit,
                candidate_commit=arguments.candidate_commit,
                release_id=arguments.release_id,
            )
        else:
            if (
                arguments.execution_manifest is None
                or arguments.p0_review_disposition is None
            ):
                raise _error(
                    "generation requires --execution-manifest and --p0-review-disposition"
                )
            output = (
                arguments.output_dir
                or ROOT
                / "test-reports"
                / "temporal-first"
                / arguments.release_id
                / "phase-8-entry"
            )
            result = generate_entry_evidence(
                release_id=arguments.release_id,
                candidate_commit=arguments.candidate_commit,
                execution_manifest_path=arguments.execution_manifest,
                p0_review_disposition_path=arguments.p0_review_disposition,
                output_dir=output,
            )
    except (
        runner.EvidenceError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
        subprocess.SubprocessError,
    ) as exception:
        print(f"Phase 8 entry evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
