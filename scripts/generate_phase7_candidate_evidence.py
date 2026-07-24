from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import stat
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Sequence

try:
    from scripts import run_phase7_candidate_checkpoint as runner
except (ImportError, ModuleNotFoundError):  # Direct execution puts scripts/ on sys.path.
    import run_phase7_candidate_checkpoint as runner  # type: ignore[no-redef]


ROOT = Path(__file__).resolve().parents[1]
CANDIDATE_NAME = "candidate.txt"
HASH_INDEX_NAME = "artifact-sha256.json"
ATTRIBUTES_NAME = ".gitattributes"
PROVENANCE_MANIFEST_NAME = "provenance-manifest.json"
SOURCE_ENVIRONMENT_NAME = "source-tree-environment.json"
P0_REVIEW_NAME = "p0-review-disposition.json"
DECISION_NAME = "phase7-engineering-decision.json"

HASH_INDEX_SCHEMA = "phase7-candidate-artifact-index.v1"
PROVENANCE_MANIFEST_SCHEMA = "phase7-candidate-provenance-manifest.v1"
SOURCE_ENVIRONMENT_SCHEMA = "phase7-candidate-source-tree-environment.v1"
P0_REVIEW_SCHEMA = "phase7-p0-review-disposition.v1"
DECISION_SCHEMA = "phase7-engineering-decision.v1"

ENGINEERING_CHECKPOINT = "PHASE_7_ENGINEERING_CHECKPOINT"
NEXT_PHASE_PERMISSION = "PHASE_8_ENGINEERING_ONLY"
ATTRIBUTES_BYTES = b"* -text\n**/* -text\n"
WINDOWS_PORTABLE_PATH_LIMIT = 248
PORTABLE_MAX_ARCHIVE_RELATIVE = "p/3q99/r/99-ffffffffffff.xml"
MIGRATION_GATES = ("MIG-006", "MIG-007")
P0_REVIEW_TOPICS = (
    "TEMPORAL_DETERMINISM_AND_AUTHORITY",
    "TRANSACTION_IDEMPOTENCY_AND_COMPENSATION",
    "PRIVACY_TOOL_CAPABILITY_AND_CLIENT_AUTHORITY",
)


def _canonical_json_bytes(document: Any) -> bytes:
    return (
        json.dumps(
            document,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _write_json_lf(path: Path, document: Any) -> None:
    path.write_bytes(_canonical_json_bytes(document))


def _load_json(path: Path, context: str) -> dict[str, Any]:
    try:
        payload = path.read_bytes()
        document = json.loads(payload)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise runner.EvidenceError(f"cannot read {context} {path}: {exception}") from exception
    if not isinstance(document, dict):
        raise runner.EvidenceError(f"{context} must be a JSON object")
    return document


def _load_canonical_json(path: Path, context: str) -> dict[str, Any]:
    document = _load_json(path, context)
    if path.read_bytes() != _canonical_json_bytes(document):
        raise runner.EvidenceError(f"{context} must use canonical LF JSON bytes")
    return document


def _release_id(value: str) -> str:
    try:
        return _validate_release_id(value)
    except runner.EvidenceError as exception:
        raise argparse.ArgumentTypeError(str(exception)) from exception


def _validate_release_id(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(
        r"[a-z0-9][a-z0-9._-]{2,79}", value
    ):
        raise runner.EvidenceError(
            "release ID must be 3-80 lowercase letters, digits, dots, underscores, or hyphens"
        )
    return value


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _git_bytes(*arguments: str) -> bytes:
    process = subprocess.run(
        ["git", *arguments], cwd=ROOT, capture_output=True, check=False
    )
    if process.returncode:
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise runner.EvidenceError(f"git {' '.join(arguments)} failed: {error}")
    return process.stdout


def _git_text(*arguments: str) -> str:
    return _git_bytes(*arguments).decode("utf-8", errors="strict")


def _assert_clean_detached_candidate(
    candidate: str, *, allowed_untracked_roots: Sequence[Path]
) -> None:
    if _git_text("rev-parse", "HEAD").strip() != candidate:
        raise runner.EvidenceError("candidate does not match HEAD")
    symbolic = subprocess.run(
        ["git", "symbolic-ref", "-q", "HEAD"],
        cwd=ROOT,
        capture_output=True,
        check=False,
    )
    if symbolic.returncode == 0:
        raise runner.EvidenceError("candidate evidence requires a detached worktree")
    roots = [path.resolve() for path in allowed_untracked_roots]
    process = subprocess.run(
        ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"],
        cwd=ROOT,
        capture_output=True,
        check=False,
    )
    if process.returncode:
        raise runner.EvidenceError("cannot authenticate candidate worktree status")
    for raw in process.stdout.split(b"\0"):
        if not raw:
            continue
        if len(raw) < 4 or raw[:3] != b"?? ":
            raise runner.EvidenceError("candidate worktree has tracked changes")
        try:
            relative = raw[3:].decode("utf-8", errors="strict")
        except UnicodeDecodeError as exception:
            raise runner.EvidenceError("candidate worktree status path is invalid UTF-8") from exception
        path = (ROOT / relative).resolve()
        if not any(path == root or path.is_relative_to(root) for root in roots):
            raise runner.EvidenceError(
                f"candidate worktree has unrelated untracked output: {relative}"
            )


def _git_hash_object(payload: bytes, *, logical_path: str | None = None) -> str:
    command = ["git", "hash-object"]
    command.append("--no-filters" if logical_path is None else f"--path={logical_path}")
    command.append("--stdin")
    process = subprocess.run(
        command, cwd=ROOT, input=payload, capture_output=True, check=False
    )
    output = process.stdout.decode("ascii", errors="replace").strip()
    if process.returncode or not re.fullmatch(r"[0-9a-f]{40,64}", output):
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise runner.EvidenceError(
            f"cannot apply Git clean filter for Phase 7 candidate evidence: {error or output}"
        )
    return output


def _assert_git_filter_stable(
    path: Path, *, require_lf: bool, logical_path: str
) -> None:
    payload = path.read_bytes()
    if require_lf and b"\r" in payload:
        raise runner.EvidenceError(
            f"Phase 7 candidate evidence artifact {path.name} contains non-LF bytes"
        )
    if _safe_output_relative_path(logical_path) != logical_path:
        raise runner.EvidenceError(
            f"Phase 7 candidate Git-filter path is not canonical: {logical_path}"
        )
    if _git_hash_object(payload) != _git_hash_object(
        payload, logical_path=logical_path
    ):
        raise runner.EvidenceError(
            f"Phase 7 candidate artifact {path.name} changes under Git clean filters"
        )


def _safe_source_path(value: str) -> str:
    normalized = value.replace("\\", "/")
    path = PurePosixPath(normalized)
    if (
        not normalized
        or path.is_absolute()
        or path.anchor
        or any(part in {"", ".", ".."} or ":" in part for part in path.parts)
        or not normalized.startswith("a/")
    ):
        raise runner.EvidenceError(
            f"execution provenance path is not a safe compact attempt-bound relative path: {value}"
        )
    return path.as_posix()


def _safe_archive_path(value: str) -> str:
    normalized = value.replace("\\", "/")
    path = PurePosixPath(normalized)
    if (
        not normalized
        or path.is_absolute()
        or path.anchor
        or any(part in {"", ".", ".."} or ":" in part for part in path.parts)
        or not re.fullmatch(
            r"p/[0-3][aq][0-9]{2}/[oer]/[0-9]{2}-[0-9a-f]{12}\.(?:log|xml)",
            normalized,
        )
    ):
        raise runner.EvidenceError(f"provenance archive path is unsafe: {value}")
    return path.as_posix()


def _safe_output_relative_path(value: str) -> str:
    normalized = value.replace("\\", "/")
    path = PurePosixPath(normalized)
    if (
        not normalized
        or path.is_absolute()
        or path.anchor
        or path.as_posix() != normalized
        or any(part in {"", ".", ".."} or ":" in part for part in path.parts)
    ):
        raise runner.EvidenceError(f"evidence output path is unsafe: {value}")
    return normalized


def _provenance_specs(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    commands = manifest.get("commands")
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(commands, list) or not isinstance(quarantined, list):
        raise runner.EvidenceError("execution provenance command lists are invalid")
    command_positions = {
        command_id: position for position, command_id in enumerate(runner.COMMAND_ORDER)
    }
    specs: list[dict[str, Any]] = []
    source_paths: set[str] = set()
    archive_paths: set[str] = set()
    for scope, records in (("accepted", commands), ("quarantined", quarantined)):
        for record_index, record in enumerate(records):
            if record_index > 99 or not isinstance(record, dict):
                raise runner.EvidenceError("execution provenance record is invalid")
            command_id = record.get("id")
            if command_id not in command_positions:
                raise runner.EvidenceError("execution provenance record has an unknown command")
            raw_reports = record.get("raw_reports")
            if not isinstance(raw_reports, list):
                raise runner.EvidenceError("execution provenance raw_reports is invalid")
            artifacts: list[tuple[str, int, Any, Any]] = [
                ("stdout", 0, record.get("stdout_path"), record.get("stdout_sha256")),
                ("stderr", 0, record.get("stderr_path"), record.get("stderr_sha256")),
            ]
            for raw_index, raw in enumerate(raw_reports):
                if raw_index > 99 or not isinstance(raw, dict):
                    raise runner.EvidenceError("execution provenance raw report is invalid")
                artifacts.append(("raw", raw_index, raw.get("path"), raw.get("sha256")))
            for kind, ordinal, source_value, source_sha256 in artifacts:
                if not isinstance(source_value, str) or not re.fullmatch(
                    r"[0-9a-f]{64}", str(source_sha256)
                ):
                    raise runner.EvidenceError("execution provenance source binding is incomplete")
                source_path = _safe_source_path(source_value)
                if source_path in source_paths:
                    raise runner.EvidenceError(
                        f"execution provenance source path is duplicated: {source_path}"
                    )
                position = command_positions[command_id]
                scope_code = "a" if scope == "accepted" else "q"
                kind_code = {"stdout": "o", "stderr": "e", "raw": "r"}[kind]
                extension = "xml" if kind == "raw" else "log"
                archive_path = (
                    f"p/{position}{scope_code}{record_index:02d}/{kind_code}/"
                    f"{ordinal:02d}-{source_sha256[:12]}.{extension}"
                )
                archive_path = _safe_archive_path(archive_path)
                if archive_path in archive_paths:
                    raise runner.EvidenceError(
                        f"execution provenance archive path collides: {archive_path}"
                    )
                source_paths.add(source_path)
                archive_paths.add(archive_path)
                specs.append(
                    {
                        "archive_path": archive_path,
                        "command_id": command_id,
                        "kind": kind,
                        "ordinal": ordinal,
                        "record_index": record_index,
                        "record_scope": scope,
                        "source_path": source_path,
                        "source_sha256": source_sha256,
                    }
                )
    return specs


def _provenance_paths(manifest: dict[str, Any]) -> list[str]:
    return [item["source_path"] for item in _provenance_specs(manifest)]


def _provenance_archive_paths(manifest: dict[str, Any]) -> list[str]:
    return [item["archive_path"] for item in _provenance_specs(manifest)]


def _primary_names(manifest: dict[str, Any]) -> list[str]:
    return [
        ATTRIBUTES_NAME,
        CANDIDATE_NAME,
        runner.MANIFEST_NAME,
        *runner.SOURCE_REPORTS.values(),
        SOURCE_ENVIRONMENT_NAME,
        P0_REVIEW_NAME,
        DECISION_NAME,
        PROVENANCE_MANIFEST_NAME,
    ]


def _indexed_names(manifest: dict[str, Any]) -> list[str]:
    return [*_primary_names(manifest), *_provenance_archive_paths(manifest)]


def _utf16_path_units(path: Path) -> int:
    return len(str(path.absolute()).encode("utf-16-le")) // 2


def _assert_portable_output_paths(
    output_dir: Path, relative_paths: Sequence[str]
) -> None:
    root = output_dir.absolute()
    seen: set[str] = set()
    for relative in relative_paths:
        normalized = PurePosixPath(relative.replace("\\", "/"))
        if (
            normalized.is_absolute()
            or normalized.anchor
            or any(part in {"", ".", ".."} or ":" in part for part in normalized.parts)
        ):
            raise runner.EvidenceError(f"evidence output path is unsafe: {relative}")
        value = normalized.as_posix()
        if value in seen:
            raise runner.EvidenceError(f"evidence output path collides: {value}")
        seen.add(value)
        final_path = root.joinpath(*normalized.parts)
        length = _utf16_path_units(final_path)
        if length > WINDOWS_PORTABLE_PATH_LIMIT:
            raise runner.EvidenceError(
                "evidence output path exceeds portable Windows budget "
                f"{WINDOWS_PORTABLE_PATH_LIMIT}: {length} UTF-16 units: {final_path}"
            )


def _lstat_regular_no_reparse(path: Path, context: str) -> os.stat_result:
    try:
        metadata = path.lstat()
    except OSError as exception:
        raise runner.EvidenceError(f"cannot lstat {context} {path}: {exception}") from exception
    attributes = int(getattr(metadata, "st_file_attributes", 0))
    reparse_flag = int(getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))
    junction_check = getattr(path, "is_junction", None)
    try:
        junction = bool(junction_check()) if callable(junction_check) else False
    except OSError as exception:
        raise runner.EvidenceError(
            f"cannot authenticate junction state for {context} {path}: {exception}"
        ) from exception
    if stat.S_ISLNK(metadata.st_mode) or junction or attributes & reparse_flag:
        raise runner.EvidenceError(f"{context} is a link, junction, or reparse point: {path}")
    return metadata


def _copy_provenance_artifact(
    *, run_root: Path, output_dir: Path, relative: str, archive_path: str
) -> None:
    source_relative = PurePosixPath(_safe_source_path(relative))
    archive_relative = PurePosixPath(_safe_archive_path(archive_path))
    source_root_lexical = run_root.absolute()
    root_metadata = _lstat_regular_no_reparse(source_root_lexical, "provenance source root")
    if not stat.S_ISDIR(root_metadata.st_mode):
        raise runner.EvidenceError("execution provenance source root is not a directory")
    source_root = source_root_lexical.resolve(strict=True)
    source_lexical = source_root_lexical
    for part in source_relative.parts[:-1]:
        source_lexical /= part
        metadata = _lstat_regular_no_reparse(source_lexical, "provenance source parent")
        if not stat.S_ISDIR(metadata.st_mode):
            raise runner.EvidenceError("execution provenance source parent is not a directory")
    source_lexical /= source_relative.parts[-1]
    source_metadata = _lstat_regular_no_reparse(source_lexical, "provenance source artifact")
    if not stat.S_ISREG(source_metadata.st_mode):
        raise runner.EvidenceError(f"execution provenance source is not regular: {relative}")
    source = source_lexical.resolve(strict=True)
    if not source.is_relative_to(source_root):
        raise runner.EvidenceError(f"execution provenance source escapes run root: {relative}")

    destination_root_lexical = output_dir.absolute()
    destination_root = destination_root_lexical.resolve(strict=True)
    destination_parent = destination_root_lexical
    for part in archive_relative.parts[:-1]:
        destination_parent /= part
        try:
            destination_parent.mkdir()
        except FileExistsError:
            pass
        metadata = _lstat_regular_no_reparse(
            destination_parent, "provenance destination parent"
        )
        if not stat.S_ISDIR(metadata.st_mode):
            raise runner.EvidenceError("provenance destination parent is not a directory")
    if not destination_parent.resolve(strict=True).is_relative_to(destination_root):
        raise runner.EvidenceError("provenance destination parent escapes output root")
    destination = destination_parent / archive_relative.parts[-1]
    try:
        destination.lstat()
    except FileNotFoundError:
        pass
    else:
        raise runner.EvidenceError(f"provenance destination already exists: {archive_path}")

    read_flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    write_flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_BINARY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        source_descriptor = os.open(source_lexical, read_flags)
    except OSError as exception:
        raise runner.EvidenceError(
            f"cannot open provenance source without following links {relative}: {exception}"
        ) from exception
    try:
        opened_source = os.fstat(source_descriptor)
        if (
            not stat.S_ISREG(opened_source.st_mode)
            or (opened_source.st_dev, opened_source.st_ino)
            != (source_metadata.st_dev, source_metadata.st_ino)
        ):
            raise runner.EvidenceError(
                f"execution provenance source changed during authentication: {relative}"
            )
        try:
            destination_descriptor = os.open(destination, write_flags, 0o600)
        except OSError as exception:
            raise runner.EvidenceError(
                f"cannot exclusively create provenance destination {archive_path}: {exception}"
            ) from exception
        try:
            with os.fdopen(source_descriptor, "rb", closefd=False) as source_stream:
                with os.fdopen(destination_descriptor, "wb", closefd=False) as destination_stream:
                    shutil.copyfileobj(source_stream, destination_stream, length=1024 * 1024)
                    destination_stream.flush()
                    os.fsync(destination_descriptor)
        finally:
            os.close(destination_descriptor)
    finally:
        os.close(source_descriptor)
    destination_metadata = _lstat_regular_no_reparse(
        destination, "provenance destination artifact"
    )
    if not stat.S_ISREG(destination_metadata.st_mode):
        raise runner.EvidenceError(
            f"execution provenance destination is not regular: {archive_path}"
        )


def _build_provenance_manifest(
    *, manifest: dict[str, Any], candidate: str, output_dir: Path
) -> dict[str, Any]:
    artifacts: list[dict[str, Any]] = []
    for spec in _provenance_specs(manifest):
        archive = output_dir / spec["archive_path"]
        digest = _sha256(archive)
        if digest != spec["source_sha256"]:
            raise runner.EvidenceError(
                f"archived provenance bytes drifted for {spec['source_path']}"
            )
        artifacts.append(
            {**spec, "archive_sha256": digest, "bytes": archive.stat().st_size}
        )
    return {
        "artifact_count": len(artifacts),
        "artifacts": artifacts,
        "candidate_commit": candidate,
        "schema_version": PROVENANCE_MANIFEST_SCHEMA,
    }


def _validate_provenance_manifest(
    *,
    manifest: dict[str, Any],
    provenance: dict[str, Any],
    candidate: str,
    artifact_reader: Callable[[str], bytes],
) -> dict[str, str]:
    expected = _provenance_specs(manifest)
    artifacts = provenance.get("artifacts")
    if set(provenance) != {
        "artifact_count",
        "artifacts",
        "candidate_commit",
        "schema_version",
    } or (
        provenance.get("schema_version") != PROVENANCE_MANIFEST_SCHEMA
        or provenance.get("candidate_commit") != candidate
        or provenance.get("artifact_count") != len(expected)
        or not isinstance(artifacts, list)
        or len(artifacts) != len(expected)
    ):
        raise runner.EvidenceError("provenance manifest authority or count drifted")
    expected_keys = {
        "archive_path",
        "archive_sha256",
        "bytes",
        "command_id",
        "kind",
        "ordinal",
        "record_index",
        "record_scope",
        "source_path",
        "source_sha256",
    }
    mapping: dict[str, str] = {}
    archives: set[str] = set()
    for expected_item, actual in zip(expected, artifacts, strict=True):
        if not isinstance(actual, dict) or set(actual) != expected_keys:
            raise runner.EvidenceError("provenance artifact mapping field set drifted")
        if any(actual.get(key) != value for key, value in expected_item.items()):
            raise runner.EvidenceError("provenance source-to-archive mapping drifted")
        source_path = actual["source_path"]
        archive_path = _safe_archive_path(actual["archive_path"])
        if source_path in mapping or archive_path in archives:
            raise runner.EvidenceError("provenance mapping collides")
        try:
            payload = artifact_reader(archive_path)
        except (OSError, KeyError) as exception:
            raise runner.EvidenceError(
                f"cannot read mapped provenance artifact {archive_path}: {exception}"
            ) from exception
        digest = _sha256_bytes(payload)
        if (
            actual.get("source_sha256") != digest
            or actual.get("archive_sha256") != digest
            or actual.get("bytes") != len(payload)
        ):
            raise runner.EvidenceError(
                f"provenance artifact byte identity drifted: {archive_path}"
            )
        mapping[source_path] = archive_path
        archives.add(archive_path)
    return mapping


@dataclass(frozen=True)
class P0ReviewSnapshot:
    candidate: str
    forbidden_roots: tuple[Path, ...]
    path: Path
    identity: tuple[int, ...]
    payload: bytes
    sha256: str
    document: dict[str, Any]


def _stat_identity(metadata: os.stat_result) -> tuple[int, ...]:
    identity = (
        int(metadata.st_dev),
        int(metadata.st_ino),
        int(stat.S_IFMT(metadata.st_mode)),
    )
    # Windows can report st_ctime_ns differently for lstat and fstat of the
    # same file object. Retain ctime as a POSIX change fence while Windows
    # uses the portable object identity; mutable content metadata is checked
    # separately below on every platform.
    if os.name != "nt":
        return (*identity, int(metadata.st_ctime_ns))
    return identity


def _stat_content_version(metadata: os.stat_result) -> tuple[int, int]:
    return (
        int(metadata.st_size),
        int(metadata.st_mtime_ns),
    )


def _assert_single_link_regular(metadata: os.stat_result, context: str) -> None:
    if not stat.S_ISREG(metadata.st_mode):
        raise runner.EvidenceError(f"{context} must be a regular file")
    if int(getattr(metadata, "st_nlink", 1)) != 1:
        raise runner.EvidenceError(
            f"{context} must have exactly one filesystem link"
        )


def _same_object_descendant(path: Path, root: Path, context: str) -> bool:
    try:
        root.stat()
    except FileNotFoundError:
        return False
    except OSError as exception:
        raise runner.EvidenceError(
            f"cannot authenticate forbidden root for {context} {root}: {exception}"
        ) from exception

    current = path
    while True:
        try:
            if os.path.samefile(current, root):
                return True
        except OSError as exception:
            raise runner.EvidenceError(
                f"cannot authenticate filesystem ancestry for {context} {current}: {exception}"
            ) from exception
        parent = current.parent
        if parent == current:
            return False
        current = parent


def _read_regular_no_follow(path: Path, context: str) -> tuple[bytes, tuple[int, ...]]:
    lexical = path.absolute()
    before = _lstat_regular_no_reparse(lexical, context)
    _assert_single_link_regular(before, context)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(lexical, flags)
    except OSError as exception:
        raise runner.EvidenceError(f"cannot open {context} without following links: {exception}") from exception
    try:
        opened = os.fstat(descriptor)
        _assert_single_link_regular(opened, context)
        if (
            _stat_identity(opened) != _stat_identity(before)
            or _stat_content_version(opened) != _stat_content_version(before)
        ):
            raise runner.EvidenceError(f"{context} changed between lstat and no-follow open")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after = os.fstat(descriptor)
        _assert_single_link_regular(after, context)
        payload = b"".join(chunks)
        if (
            _stat_identity(after) != _stat_identity(opened)
            or _stat_content_version(after) != _stat_content_version(opened)
            or len(payload) != after.st_size
        ):
            raise runner.EvidenceError(f"{context} changed while its authenticated snapshot was read")
    finally:
        os.close(descriptor)
    final_path = _lstat_regular_no_reparse(lexical, context)
    _assert_single_link_regular(final_path, context)
    if (
        _stat_identity(final_path) != _stat_identity(after)
        or _stat_content_version(final_path) != _stat_content_version(after)
    ):
        raise runner.EvidenceError(f"{context} path identity changed after its authenticated read")
    return payload, _stat_identity(after)


def _snapshot_p0_review_disposition(
    candidate: str,
    path: Path,
    *,
    forbidden_roots: Sequence[Path],
) -> P0ReviewSnapshot:
    roots = tuple(root.absolute() for root in forbidden_roots)
    resolved = _assert_external_p0_review_path(
        candidate, path, forbidden_roots=roots
    )
    payload, identity = _read_regular_no_follow(resolved, "P0 review disposition")
    revalidated = _assert_external_p0_review_path(
        candidate, resolved, forbidden_roots=roots
    )
    if _stat_identity(
        _lstat_regular_no_reparse(revalidated, "P0 review disposition")
    ) != identity:
        raise runner.EvidenceError(
            "P0 review disposition identity changed during boundary validation"
        )
    try:
        review = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise runner.EvidenceError(
            f"cannot parse authenticated P0 review disposition bytes: {exception}"
        ) from exception
    if not isinstance(review, dict):
        raise runner.EvidenceError("P0 review disposition must be a JSON object")
    _validate_p0_review_document(review, candidate)
    return P0ReviewSnapshot(
        candidate=candidate,
        forbidden_roots=roots,
        path=resolved,
        identity=identity,
        payload=payload,
        sha256=_sha256_bytes(payload),
        document=review,
    )


def load_p0_review_disposition(path: Path, candidate_commit: str) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    return _snapshot_p0_review_disposition(
        candidate, path, forbidden_roots=(ROOT,)
    ).document


def _candidate_path_tracked(candidate: str, path: Path) -> bool:
    try:
        relative = path.resolve(strict=False).relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return False
    tree = subprocess.run(
        ["git", "ls-tree", "-z", candidate, "--", relative],
        cwd=ROOT,
        capture_output=True,
        check=False,
    )
    if tree.returncode:
        error = tree.stderr.decode("utf-8", errors="replace").strip()
        raise runner.EvidenceError(f"cannot authenticate P0 review candidate tracking: {error}")
    indexed = subprocess.run(
        ["git", "ls-files", "-z", "--", relative],
        cwd=ROOT,
        capture_output=True,
        check=False,
    )
    if indexed.returncode:
        raise runner.EvidenceError("cannot authenticate P0 review index tracking")
    return bool(tree.stdout or indexed.stdout)


def _assert_external_p0_review_path(
    candidate: str, path: Path, *, forbidden_roots: Sequence[Path]
) -> Path:
    if not path.is_absolute():
        raise runner.EvidenceError("P0 review disposition must be an explicit absolute path")
    lexical = path.absolute()
    metadata = _lstat_regular_no_reparse(lexical, "P0 review disposition")
    _assert_single_link_regular(metadata, "P0 review disposition")
    resolved = lexical.resolve(strict=True)
    lexical_roots = [root.absolute() for root in forbidden_roots]
    roots = [root.resolve(strict=False) for root in lexical_roots]
    if _candidate_path_tracked(candidate, resolved):
        raise runner.EvidenceError("P0 review disposition is tracked by the candidate")
    if any(
        resolved == root
        or resolved.is_relative_to(root)
        or _same_object_descendant(lexical, lexical_root, "P0 review disposition")
        for lexical_root, root in zip(lexical_roots, roots, strict=True)
    ):
        raise runner.EvidenceError(
            "P0 review disposition must be external to candidate, run, and output paths"
        )
    return resolved


def _assert_p0_snapshot(snapshot: P0ReviewSnapshot) -> None:
    path = _assert_external_p0_review_path(
        snapshot.candidate,
        snapshot.path,
        forbidden_roots=snapshot.forbidden_roots,
    )
    payload, identity = _read_regular_no_follow(
        path, "P0 review disposition"
    )
    if identity != snapshot.identity or _sha256_bytes(payload) != snapshot.sha256:
        raise runner.EvidenceError("external P0 review disposition changed after snapshot")


def _validate_p0_review_document(review: dict[str, Any], candidate: str) -> None:
    expected_keys = {
        "candidate_commit",
        "closed_finding_ids",
        "open_p0_count",
        "review_scope",
        "reviewed_topics",
        "schema_version",
        "status",
    }
    closed = review.get("closed_finding_ids")
    if (
        set(review) != expected_keys
        or review.get("schema_version") != P0_REVIEW_SCHEMA
        or review.get("candidate_commit") != candidate
        or review.get("review_scope") != "CONSOLIDATED_POST_INTEGRATION_P0_ONLY"
        or review.get("status") != "ALL_P0_CLOSED"
        or review.get("open_p0_count") != 0
        or review.get("reviewed_topics") != list(P0_REVIEW_TOPICS)
        or not isinstance(closed, list)
        or any(
            not isinstance(item, str)
            or not re.fullmatch(r"P0-[A-Z0-9][A-Z0-9._-]{1,79}", item)
            for item in closed
        )
        or closed != sorted(set(closed))
    ):
        raise runner.EvidenceError(
            "P0 review disposition must be exact-candidate ALL_P0_CLOSED with zero open findings"
        )


def _xml_fingerprint(element: ET.Element) -> tuple[Any, ...]:
    text = element.text or ""
    if len(element) and not text.strip():
        text = ""
    tail = element.tail or ""
    if not tail.strip():
        tail = ""
    return (
        element.tag,
        tuple(sorted(element.attrib.items())),
        text,
        tail,
        tuple(_xml_fingerprint(child) for child in element),
    )


def _junit_summary(
    payload: bytes, context: str
) -> tuple[
    list[tuple[str, str, str, str, float, tuple[Any, ...]]],
    dict[str, int | float],
]:
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exception:
        raise runner.EvidenceError(f"{context}: JUnit is invalid: {exception}") from exception
    local = lambda tag: tag.rsplit("}", 1)[-1]
    if local(root.tag) not in {"testsuite", "testsuites"}:
        raise runner.EvidenceError(f"{context}: JUnit root is invalid")
    fingerprints: list[tuple[str, str, str, str, float, tuple[Any, ...]]] = []
    identities: set[tuple[str, str]] = set()
    failures = errors = skipped = 0
    suites = (
        [root]
        if local(root.tag) == "testsuite"
        else [element for element in root.iter() if local(element.tag) == "testsuite"]
    )
    for suite in suites:
        suite_name = suite.get("name") or "unnamed-suite"
        suite_fingerprints: list[
            tuple[str, str, str, str, float, tuple[Any, ...]]
        ] = []
        for case in (child for child in suite if local(child.tag) == "testcase"):
            classname = case.get("classname")
            name = case.get("name")
            if not classname or not name:
                raise runner.EvidenceError(f"{context}: JUnit testcase identity is missing")
            identity = (classname, name)
            if identity in identities:
                raise runner.EvidenceError(f"{context}: JUnit duplicates testcase {identity}")
            identities.add(identity)
            children = {local(child.tag) for child in case}
            if children & {"flakyFailure", "flakyError", "rerunFailure", "rerunError"}:
                raise runner.EvidenceError(f"{context}: JUnit contains retry/flake outcomes")
            status = "passed"
            if "failure" in children:
                failures += 1
                status = "failure"
            elif "error" in children:
                errors += 1
                status = "error"
            elif "skipped" in children:
                skipped += 1
                status = "skipped"
            try:
                duration = float(case.get("time", "0") or "0")
            except ValueError as exception:
                raise runner.EvidenceError(f"{context}: JUnit time is invalid") from exception
            if duration < 0 or not math.isfinite(duration):
                raise runner.EvidenceError(
                    f"{context}: JUnit time is negative or non-finite"
                )
            fingerprint = (
                suite_name,
                classname,
                name,
                status,
                duration,
                _xml_fingerprint(case),
            )
            fingerprints.append(fingerprint)
            suite_fingerprints.append(fingerprint)
        if suite_fingerprints:
            suite_totals = {
                "tests": len(suite_fingerprints),
                "failures": sum(item[3] == "failure" for item in suite_fingerprints),
                "errors": sum(item[3] == "error" for item in suite_fingerprints),
                "skipped": sum(item[3] == "skipped" for item in suite_fingerprints),
            }
            for field, actual in suite_totals.items():
                declared = suite.get(field)
                if declared is not None:
                    try:
                        declared_value = int(declared)
                    except ValueError as exception:
                        raise runner.EvidenceError(
                            f"{context}: JUnit suite declared {field} is invalid"
                        ) from exception
                    if declared_value != actual:
                        raise runner.EvidenceError(
                            f"{context}: JUnit suite declared {field} drifted"
                        )
            try:
                flakes = int(suite.get("flakes", "0"))
            except ValueError as exception:
                raise runner.EvidenceError(
                    f"{context}: JUnit suite declared flakes is invalid"
                ) from exception
            if flakes:
                raise runner.EvidenceError(f"{context}: JUnit suite declares flakes")
    if not fingerprints:
        raise runner.EvidenceError(f"{context}: JUnit is empty")
    totals: dict[str, int | float] = {
        "errors": errors,
        "failures": failures,
        "skipped": skipped,
        "tests": len(fingerprints),
        "time": round(sum(item[4] for item in fingerprints), 6),
    }
    for field in ("tests", "failures", "errors", "skipped"):
        declared = root.get(field)
        if declared is not None:
            try:
                declared_value = int(declared)
            except ValueError as exception:
                raise runner.EvidenceError(
                    f"{context}: JUnit declared {field} is invalid"
                ) from exception
            if declared_value != totals[field]:
                raise runner.EvidenceError(f"{context}: JUnit declared {field} drifted")
    return fingerprints, totals


def _validate_normalized_report(
    payload: bytes,
    *,
    candidate: str,
    command_id: str,
    minimum_tests: int,
) -> tuple[
    list[tuple[str, str, str, str, float, tuple[Any, ...]]],
    dict[str, int | float],
]:
    if b"\r" in payload:
        raise runner.EvidenceError(f"{command_id}: normalized JUnit contains CR bytes")
    fingerprints, totals = _junit_summary(payload, command_id)
    root = ET.fromstring(payload)
    expected_report = runner.SOURCE_REPORTS[command_id]
    expected_root_keys = {
        "candidate_commit",
        "errors",
        "failures",
        "name",
        "skipped",
        "source_command_id",
        "tests",
        "time",
    }
    suites = list(root)
    expected_suite_keys = {
        "errors",
        "failures",
        "name",
        "skipped",
        "source_report",
        "tests",
        "time",
    }
    suite_names = [suite.get("name") or "" for suite in suites]

    def declared_time_equals(element: ET.Element, actual: float) -> bool:
        try:
            declared = float(element.get("time", "nan"))
        except ValueError:
            return False
        return math.isfinite(declared) and declared >= 0 and declared == actual

    def normalized_suite_time_matches(element: ET.Element) -> bool:
        try:
            actual = round(
                sum(
                    float(case.get("time", "0") or "0")
                    for case in element
                    if case.tag.rsplit("}", 1)[-1] == "testcase"
                ),
                6,
            )
        except ValueError:
            return False
        return declared_time_equals(element, actual)

    if (
        root.tag.rsplit("}", 1)[-1] != "testsuites"
        or set(root.attrib) != expected_root_keys
        or root.get("name") != Path(expected_report).stem
        or root.get("candidate_commit") != candidate
        or root.get("source_command_id") != command_id
        or not suites
        or suite_names != sorted(suite_names)
        or len(suite_names) != len(set(suite_names))
        or not declared_time_equals(root, float(totals["time"]))
        or any(
            suite.tag.rsplit("}", 1)[-1] != "testsuite"
            or set(suite.attrib) != expected_suite_keys
            or not suite.get("name")
            or suite.get("source_report") != expected_report
            or not normalized_suite_time_matches(suite)
            or any(child.tag.rsplit("}", 1)[-1] != "testcase" for child in suite)
            or [
                (child.get("classname"), child.get("name")) for child in suite
            ]
            != sorted((child.get("classname"), child.get("name")) for child in suite)
            for suite in suites
        )
        or int(totals["tests"]) < minimum_tests
        or totals["failures"]
        or totals["errors"]
        or totals["skipped"]
    ):
        raise runner.EvidenceError(
            f"{command_id}: normalized JUnit is not exact-candidate zero-skip green"
        )
    return fingerprints, totals


def _raw_report_fingerprints(
    *,
    record: dict[str, Any],
    provenance_mapping: dict[str, str],
    artifact_reader: Callable[[str], bytes],
) -> list[tuple[str, str, str, str, float, tuple[Any, ...]]]:
    command_id = str(record.get("id"))
    raw_records = record.get("raw_reports")
    if (
        not isinstance(raw_records, list)
        or record.get("raw_report_count") != len(raw_records)
        or record.get("expected_report_count") != len(raw_records)
    ):
        raise runner.EvidenceError(f"{command_id}: raw report count drifted")
    fingerprints: list[tuple[str, str, str, str, float, tuple[Any, ...]]] = []
    identities: set[tuple[str, str]] = set()
    for raw in raw_records:
        if (
            not isinstance(raw, dict)
            or set(raw) != {"path", "sha256"}
        ):
            raise runner.EvidenceError(f"{command_id}: raw report binding is invalid")
        source_path = raw.get("path")
        archive_path = provenance_mapping.get(source_path)
        if archive_path is None:
            raise runner.EvidenceError(f"{command_id}: raw report provenance is missing")
        payload = artifact_reader(archive_path)
        if raw.get("sha256") != _sha256_bytes(payload):
            raise runner.EvidenceError(f"{command_id}: raw report hash drifted")
        current, totals = _junit_summary(payload, f"{command_id} raw")
        if totals["failures"] or totals["errors"] or totals["skipped"]:
            raise runner.EvidenceError(f"{command_id}: raw JUnit is not zero-skip green")
        for fingerprint in current:
            identity = (fingerprint[1], fingerprint[2])
            if identity in identities:
                raise runner.EvidenceError(
                    f"{command_id}: raw JUnit sources duplicate a testcase"
                )
            identities.add(identity)
            fingerprints.append(fingerprint)
    return fingerprints


def _validate_source_reports(
    *,
    manifest: dict[str, Any],
    candidate: str,
    provenance_mapping: dict[str, str],
    artifact_reader: Callable[[str], bytes],
) -> tuple[list[dict[str, Any]], dict[str, int | float]]:
    records = manifest.get("commands")
    if not isinstance(records, list) or [
        item.get("id") if isinstance(item, dict) else None for item in records
    ] != list(runner.COMMAND_ORDER):
        raise runner.EvidenceError("execution manifest lacks the exact four-command set")
    rows: list[dict[str, Any]] = []
    totals: dict[str, int | float] = {
        "errors": 0,
        "failures": 0,
        "skipped": 0,
        "tests": 0,
        "time": 0.0,
    }
    seen_cross_source: set[tuple[str, str]] = set()
    for record in records:
        if not isinstance(record, dict):
            raise runner.EvidenceError("accepted command record is invalid")
        command_id = str(record.get("id"))
        report_name = runner.SOURCE_REPORTS.get(command_id)
        if (
            not isinstance(report_name, str)
            or record.get("candidate_commit") != candidate
            or record.get("accepted") is not True
            or record.get("exit_code") != 0
            or record.get("failure_classification") != "NONE"
            or record.get("report") != report_name
            or record.get("report_path") != f"r/{report_name}"
            or not isinstance(record.get("minimum_tests"), int)
            or isinstance(record.get("minimum_tests"), bool)
        ):
            raise runner.EvidenceError(f"{command_id}: accepted command binding drifted")
        normalized = artifact_reader(report_name)
        if record.get("report_sha256") != _sha256_bytes(normalized):
            raise runner.EvidenceError(f"{command_id}: normalized report hash drifted")
        normalized_fingerprints, junit = _validate_normalized_report(
            normalized,
            candidate=candidate,
            command_id=command_id,
            minimum_tests=record["minimum_tests"],
        )
        raw_fingerprints = _raw_report_fingerprints(
            record=record,
            provenance_mapping=provenance_mapping,
            artifact_reader=artifact_reader,
        )
        if sorted(normalized_fingerprints) != sorted(raw_fingerprints):
            raise runner.EvidenceError(
                f"{command_id}: normalized JUnit does not match raw provenance"
            )
        if any(record.get(field) != junit[field] for field in junit):
            raise runner.EvidenceError(f"{command_id}: JUnit totals record drifted")
        for fingerprint in normalized_fingerprints:
            identity = (fingerprint[1], fingerprint[2])
            if identity in seen_cross_source:
                raise runner.EvidenceError("normalized reports duplicate cross-source testcases")
            seen_cross_source.add(identity)
        row = {
            "candidate_commit": candidate,
            "command_id": command_id,
            "environment_sha256": record.get("environment_sha256"),
            "expected_report_count": record.get("expected_report_count"),
            "command_contract_blob_sha256": record.get("command_contract_blob_sha256"),
            "executed_argv_sha256": record.get("executed_argv_sha256"),
            "executed_command_sha256": record.get("executed_command_sha256"),
            "exit_code": 0,
            "failure_classification": "NONE",
            "junit": junit,
            "minimum_tests": record.get("minimum_tests"),
            "raw_report_count": record.get("raw_report_count"),
            "report": report_name,
            "report_bytes": len(normalized),
            "report_sha256": _sha256_bytes(normalized),
            "selected_test_file_count": record.get("selected_test_file_count"),
        }
        rows.append(row)
        for field in ("tests", "failures", "errors", "skipped"):
            totals[field] = int(totals[field]) + int(junit[field])
        totals["time"] = round(float(totals["time"]) + float(junit["time"]), 6)
    return rows, totals


def _snapshot_sha256(document: dict[str, Any]) -> str:
    unsigned = dict(document)
    unsigned.pop("snapshot_sha256", None)
    helper = getattr(runner, "_json_sha256", None)
    if callable(helper):
        return str(helper(unsigned))
    payload = json.dumps(
        unsigned, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return _sha256_bytes(payload)


def _validate_source_tree_environment(
    document: dict[str, Any],
    *,
    candidate: str,
    candidate_blob_reader: Callable[[str], bytes],
) -> None:
    if set(document) != {
        "candidate_commit",
        "environment",
        "schema_version",
        "source_tree",
    } or (
        document.get("schema_version") != SOURCE_ENVIRONMENT_SCHEMA
        or document.get("candidate_commit") != candidate
    ):
        raise runner.EvidenceError("source-tree/environment manifest authority drifted")
    source_tree = document.get("source_tree")
    environment = document.get("environment")
    if not isinstance(source_tree, dict) or not isinstance(environment, dict):
        raise runner.EvidenceError("source-tree/environment snapshots are missing")
    if (
        source_tree.get("candidate_commit") != candidate
        or source_tree.get("snapshot_sha256") != _snapshot_sha256(source_tree)
        or environment.get("snapshot_sha256") != _snapshot_sha256(environment)
    ):
        raise runner.EvidenceError("source-tree/environment snapshot seal drifted")
    recomputed_source_tree = runner.capture_source_tree(candidate)
    if source_tree != recomputed_source_tree:
        raise runner.EvidenceError(
            "source-tree snapshot drifted from independent candidate recomputation"
        )
    if (
        environment.get("candidate_commit") != candidate
        or environment.get("source_contract_sha256")
        != runner._source_contract_sha256(candidate)
    ):
        raise runner.EvidenceError("environment candidate or source-contract hash drifted")
    dependencies = environment.get("dependency_manifests")
    if not isinstance(dependencies, list) or not dependencies:
        raise runner.EvidenceError("environment dependency Git-blob inventory is missing")
    seen: set[str] = set()
    for item in dependencies:
        if (
            not isinstance(item, dict)
            or set(item) != {"byte_source", "path", "sha256"}
            or item.get("byte_source") != "CANDIDATE_GIT_BLOB"
            or not isinstance(item.get("path"), str)
            or item["path"] in seen
        ):
            raise runner.EvidenceError("environment dependency Git-blob record drifted")
        payload = candidate_blob_reader(item["path"])
        if item.get("sha256") != _sha256_bytes(payload):
            raise runner.EvidenceError(
                f"candidate dependency Git blob drifted: {item['path']}"
            )
        seen.add(item["path"])
    if list(item["path"] for item in dependencies) != list(
        runner.DEPENDENCY_MANIFEST_PATHS
    ):
        raise runner.EvidenceError("environment dependency Git-blob inventory drifted")
    runner_record = environment.get("runner")
    if (
        not isinstance(runner_record, dict)
        or set(runner_record) != {"byte_source", "path", "sha256"}
        or runner_record.get("path") != runner.RUNNER_PATH
        or runner_record.get("byte_source") != "CANDIDATE_GIT_BLOB"
        or runner_record.get("sha256")
        != _sha256_bytes(candidate_blob_reader(runner.RUNNER_PATH))
    ):
        raise runner.EvidenceError("candidate runner Git-blob hash drifted")

def _validate_archived_command_records(
    manifest: dict[str, Any], candidate: str
) -> None:
    expected_manifest_keys = {
        "MIG-006",
        "MIG-007",
        "accepted_phase_7_candidate_C7",
        "accepted_phase_7_evidence_E7",
        "attempt_id",
        "batch",
        "candidate_commit",
        "commands",
        "concurrency",
        "decision_ceiling",
        "environment",
        "manifest_sha256",
        "next_phase_permission",
        "pending_failure",
        "phase",
        "quarantined_attempts",
        "quarantined_attempts_reused",
        "run_root",
        "schema_version",
        "source_contract_sha256",
        "source_tree",
        "status",
        "verification_finished_at",
        "verification_started_at",
    }
    if (
        set(manifest) != expected_manifest_keys
        or manifest.get("phase") != 7
        or manifest.get("batch") != "P7-BATCH-3"
        or manifest.get("accepted_phase_7_candidate_C7")
        != runner.PHASE7_ENTRY_CANDIDATE
        or manifest.get("accepted_phase_7_evidence_E7")
        != runner.PHASE7_ENTRY_EVIDENCE
        or manifest.get("source_contract_sha256")
        != runner._source_contract_sha256(candidate)
    ):
        raise runner.EvidenceError("committed candidate manifest field or authority set drifted")
    environment = manifest.get("environment")
    if not isinstance(environment, dict):
        raise runner.EvidenceError("committed candidate environment is missing")
    environment_sha256 = environment.get("snapshot_sha256")
    runner_record = environment.get("runner")
    if not isinstance(runner_record, dict):
        raise runner.EvidenceError("committed candidate runner binding is missing")
    runner_blob_sha256 = runner_record.get("sha256")
    run_root_value = manifest.get("run_root")
    if not isinstance(run_root_value, str) or not Path(run_root_value).is_absolute():
        raise runner.EvidenceError("committed candidate run_root is not absolute")
    run_root = Path(run_root_value)
    run_token = hashlib.sha256(str(run_root.resolve()).encode("utf-8")).hexdigest()[:6]
    contracts = runner.source_contracts(candidate)
    base_record_keys = {
        "accepted",
        "candidate_commit",
        "command_contract_blob_sha256",
        "cwd",
        "duration_seconds",
        "environment_sha256",
        "executed_argv",
        "executed_argv_sha256",
        "executed_command",
        "executed_command_sha256",
        "exit_code",
        "expected_report_count",
        "failure_classification",
        "finished_at",
        "frozen_command",
        "frozen_command_sha256",
        "id",
        "minimum_tests",
        "raw_report_count",
        "raw_reports",
        "report_suffix",
        "resource_class",
        "selected_test_file_count",
        "started_at",
        "stderr_path",
        "stderr_sha256",
        "stdout_path",
        "stdout_sha256",
    }
    accepted_keys = base_record_keys | {
        "errors",
        "failures",
        "report",
        "report_path",
        "report_sha256",
        "skipped",
        "tests",
        "time",
    }

    def validate_record(record: dict[str, Any], *, accepted: bool) -> None:
        expected_keys = accepted_keys if accepted else base_record_keys
        if not accepted and "failure_reason" in record:
            expected_keys = expected_keys | {"failure_reason"}
        command_id = record.get("id")
        if set(record) != expected_keys or command_id not in contracts:
            raise runner.EvidenceError("committed command record field set drifted")
        contract = contracts[command_id]
        alias = runner.SOURCE_ALIASES[command_id]
        stdout_path = record.get("stdout_path")
        match = (
            re.fullmatch(rf"a/{re.escape(alias)}-([0-9]{{2}})/stdout\.log", stdout_path)
            if isinstance(stdout_path, str)
            else None
        )
        if not match:
            raise runner.EvidenceError(f"{command_id}: compact attempt stdout path drifted")
        attempt_number = int(match.group(1))
        attempt_relative = f"a/{alias}-{attempt_number:02d}"
        suffix = f"p7c-{candidate[:10]}-{run_token}-{alias}{attempt_number:02d}"
        expected_argv = runner._format_command(
            command_id,
            contract,
            run_root / attempt_relative / "junit.xml",
            suffix,
            (ROOT / contract["cwd"]).resolve(),
        )
        executed_argv = record.get("executed_argv")
        executed_command = record.get("executed_command")
        duration = record.get("duration_seconds")
        if (
            record.get("candidate_commit") != candidate
            or record.get("cwd") != contract["cwd"]
            or record.get("resource_class") != contract["resource_class"]
            or record.get("expected_report_count") != contract["expected_report_count"]
            or record.get("selected_test_file_count")
            != contract["selected_test_file_count"]
            or record.get("minimum_tests") != contract["minimum_tests"]
            or record.get("frozen_command") != contract["command"]
            or record.get("frozen_command_sha256")
            != _sha256_bytes(contract["command"].encode("utf-8"))
            or executed_argv != expected_argv
            or record.get("executed_argv_sha256") != runner._json_sha256(expected_argv)
            or executed_command != runner.render_command_argv(expected_argv)
            or record.get("executed_command_sha256")
            != _sha256_bytes(str(executed_command).encode("utf-8"))
            or record.get("command_contract_blob_sha256") != runner_blob_sha256
            or record.get("environment_sha256") != environment_sha256
            or record.get("report_suffix") != suffix
            or record.get("stderr_path") != f"{attempt_relative}/stderr.log"
            or not isinstance(duration, (int, float))
            or isinstance(duration, bool)
            or not math.isfinite(float(duration))
            or float(duration) < 0
        ):
            raise runner.EvidenceError(
                f"{command_id}: command, environment, or exact-attempt binding drifted"
            )
        raw = record.get("raw_reports")
        if (
            not isinstance(raw, list)
            or record.get("raw_report_count") != len(raw)
            or len(raw) != contract["expected_report_count"]
        ):
            raise runner.EvidenceError(f"{command_id}: raw report count binding drifted")
        for index, item in enumerate(raw, start=1):
            expected_path = (
                f"{attempt_relative}/raw/j-{index:03d}.xml"
                if contract["report_kind"] == "SUREFIRE_GLOB"
                else f"{attempt_relative}/junit.xml"
            )
            expected_item_keys = {"path", "sha256"}
            if (
                not isinstance(item, dict)
                or set(item) != expected_item_keys
                or item.get("path") != expected_path
                or not re.fullmatch(r"[0-9a-f]{64}", str(item.get("sha256")))
            ):
                raise runner.EvidenceError(f"{command_id}: raw report path binding drifted")
        if accepted:
            report_name = runner.SOURCE_REPORTS[command_id]
            if (
                record.get("accepted") is not True
                or record.get("exit_code") != 0
                or record.get("failure_classification") != "NONE"
                or record.get("report") != report_name
                or record.get("report_path") != f"r/{report_name}"
            ):
                raise runner.EvidenceError(f"{command_id}: accepted status binding drifted")
        elif (
            record.get("accepted") is not False
            or record.get("failure_classification") != "INFRA"
            or record.get("exit_code") == 0
        ):
            raise runner.EvidenceError("quarantined attempt classification drifted")

    commands = manifest.get("commands")
    if not isinstance(commands, list) or [
        item.get("id") if isinstance(item, dict) else None for item in commands
    ] != list(runner.COMMAND_ORDER):
        raise runner.EvidenceError("committed accepted source order drifted")
    for record in commands:
        if not isinstance(record, dict):
            raise runner.EvidenceError("committed accepted source record is invalid")
        validate_record(record, accepted=True)
    quarantined = manifest.get("quarantined_attempts")
    if not isinstance(quarantined, list):
        raise runner.EvidenceError("committed quarantine inventory is invalid")
    seen_quarantine: set[str] = set()
    for record in quarantined:
        if not isinstance(record, dict):
            raise runner.EvidenceError("committed quarantined source record is invalid")
        command_id = str(record.get("id"))
        if command_id in seen_quarantine:
            raise runner.EvidenceError("committed source exceeded INFRA retry ceiling")
        seen_quarantine.add(command_id)
        validate_record(record, accepted=False)


def load_green_manifest(
    execution_manifest_path: Path, candidate_commit: str
) -> dict[str, Any]:
    candidate = runner._assert_candidate(candidate_commit)
    path = execution_manifest_path.resolve()
    if path.name != runner.MANIFEST_NAME:
        raise runner.EvidenceError(f"execution manifest must be named {runner.MANIFEST_NAME}")
    manifest = runner.load_pass_manifest(path, candidate)
    if not isinstance(manifest, dict):
        raise runner.EvidenceError("candidate runner returned a non-object manifest")
    if (
        manifest.get("schema_version") != runner.SCHEMA_VERSION
        or manifest.get("candidate_commit") != candidate
        or manifest.get("status") != runner.GREEN_STATUS
        or manifest.get("decision_ceiling") != "PHASE_7_ENGINEERING_CHECKPOINT_ONLY"
        or manifest.get("next_phase_permission") != "PENDING_SEPARATE_EVIDENCE"
        or any(manifest.get(gate) != "PENDING_PROMOTION" for gate in MIGRATION_GATES)
        or manifest.get("pending_failure") is not None
        or manifest.get("quarantined_attempts_reused") is not False
    ):
        raise runner.EvidenceError("candidate execution manifest authority or claims drifted")
    concurrency = manifest.get("concurrency")
    if concurrency != {
        "observed_maximum_source_processes": 1,
        "policy_maximum_heavy_processes": 2,
        "policy_maximum_light_processes": 2,
        "runner_execution": "sequential",
    }:
        raise runner.EvidenceError("candidate execution concurrency controls drifted")
    source_dir = path.parent / "r"
    expected_reports = set(runner.SOURCE_REPORTS.values())
    if not source_dir.is_dir() or {
        item.name for item in source_dir.iterdir() if item.is_file()
    } != expected_reports:
        raise runner.EvidenceError("source report directory is incomplete or contains extras")
    if any(not item.is_file() or item.is_symlink() for item in source_dir.iterdir()):
        raise runner.EvidenceError("source report directory contains a non-regular artifact")
    return manifest


def _source_environment_document(
    manifest: dict[str, Any], candidate: str
) -> dict[str, Any]:
    document = {
        "candidate_commit": candidate,
        "environment": manifest.get("environment"),
        "schema_version": SOURCE_ENVIRONMENT_SCHEMA,
        "source_tree": manifest.get("source_tree"),
    }
    _validate_source_tree_environment(
        document,
        candidate=candidate,
        candidate_blob_reader=lambda path: runner._git_bytes(candidate, path),
    )
    return document


def _runtime_restrictions() -> dict[str, bool]:
    return {
        "canary": False,
        "formal_outcome_sink": False,
        "formal_outcome_workflow": False,
        "real_case_or_party_data": False,
        "real_case_shadow": False,
        "real_tool_effect": False,
        "temporal_outcome_allocation": False,
        "production_traffic": False,
        "promotion": False,
    }


def _decision_document(
    *,
    release_id: str,
    candidate: str,
    manifest: dict[str, Any],
    source_rows: list[dict[str, Any]],
    totals: dict[str, int | float],
    p0_review: dict[str, Any],
    p0_review_sha256: str,
    source_environment_sha256: str,
    provenance: dict[str, Any],
    provenance_sha256: str,
    execution_manifest_sha256: str,
) -> dict[str, Any]:
    return {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "candidate_commit": candidate,
        "checkpoint_effect_after_commit": ENGINEERING_CHECKPOINT,
        "decision_ceiling": ENGINEERING_CHECKPOINT,
        "engineering_checkpoint_after_commit": "PASS",
        "engineering_checkpoint_before_commit": "NOT_RECORDED",
        "evidence_commit_requirement": "DIRECT_CHILD_OF_CANDIDATE",
        "execution_manifest": {
            "manifest_sha256": manifest.get("manifest_sha256"),
            "path": runner.MANIFEST_NAME,
            "schema_version": manifest.get("schema_version"),
            "sha256": execution_manifest_sha256,
        },
        "execution_provenance": {
            "artifact_count": provenance.get("artifact_count"),
            "manifest": PROVENANCE_MANIFEST_NAME,
            "manifest_sha256": provenance_sha256,
            "mixed_attempt_results": False,
            "quarantined_attempts_reused": False,
        },
        "next_phase_permission_after_commit": NEXT_PHASE_PERMISSION,
        "p0_review": {
            "closed_finding_ids": p0_review.get("closed_finding_ids"),
            "open_p0_count": 0,
            "path": P0_REVIEW_NAME,
            "sha256": p0_review_sha256,
            "status": "ALL_P0_CLOSED",
        },
        "promotion_gate": "PENDING",
        "release_id": release_id,
        "result": "PASS_AWAITING_SEPARATE_DIRECT_CHILD_EVIDENCE_COMMIT",
        "runtime_restrictions": _runtime_restrictions(),
        "schema_version": DECISION_SCHEMA,
        "source_reports": source_rows,
        "source_tree_environment": {
            "path": SOURCE_ENVIRONMENT_NAME,
            "sha256": source_environment_sha256,
        },
        "totals": totals,
    }


def _archive_manifest(
    source: Path, destination: Path, expected_manifest: dict[str, Any]
) -> str:
    current = _load_json(source, "Phase 7 candidate execution manifest")
    if current != expected_manifest:
        raise runner.EvidenceError(
            "Phase 7 candidate execution manifest changed after green authentication"
        )
    seal_validator = getattr(runner, "_assert_execution_manifest_seal", None)
    if callable(seal_validator):
        seal_validator(current)
    destination.write_bytes(_canonical_json_bytes(current))
    return _sha256(destination)


def _validate_bundle_documents(
    *,
    candidate: str,
    release_id: str,
    manifest: dict[str, Any],
    blobs: dict[str, bytes],
    candidate_blob_reader: Callable[[str], bytes],
) -> dict[str, Any]:
    release_id = _validate_release_id(release_id)
    if blobs.get(ATTRIBUTES_NAME) != ATTRIBUTES_BYTES:
        raise runner.EvidenceError("candidate evidence byte-preservation attributes drifted")
    if blobs.get(CANDIDATE_NAME) != (candidate + "\n").encode("ascii"):
        raise runner.EvidenceError("candidate evidence exact SHA binding drifted")
    canonical_names = {
        HASH_INDEX_NAME,
        runner.MANIFEST_NAME,
        SOURCE_ENVIRONMENT_NAME,
        P0_REVIEW_NAME,
        DECISION_NAME,
        PROVENANCE_MANIFEST_NAME,
    }
    documents: dict[str, dict[str, Any]] = {}
    for name in canonical_names:
        try:
            document = json.loads(blobs[name])
        except (KeyError, UnicodeDecodeError, json.JSONDecodeError) as exception:
            raise runner.EvidenceError(f"candidate evidence JSON {name} is invalid") from exception
        if not isinstance(document, dict) or blobs[name] != _canonical_json_bytes(document):
            raise runner.EvidenceError(f"candidate evidence JSON {name} is not canonical LF")
        documents[name] = document
    archived = documents[runner.MANIFEST_NAME]
    if archived != manifest:
        raise runner.EvidenceError("archived candidate execution manifest changed")
    seal_validator = getattr(runner, "_assert_execution_manifest_seal", None)
    if callable(seal_validator):
        seal_validator(archived)
    _validate_archived_manifest_claims(archived, candidate)
    _validate_archived_command_records(archived, candidate)

    provenance = documents[PROVENANCE_MANIFEST_NAME]
    mapping = _validate_provenance_manifest(
        manifest=archived,
        provenance=provenance,
        candidate=candidate,
        artifact_reader=lambda relative: blobs[relative],
    )
    source_environment = documents[SOURCE_ENVIRONMENT_NAME]
    if (
        source_environment.get("source_tree") != archived.get("source_tree")
        or source_environment.get("environment") != archived.get("environment")
    ):
        raise runner.EvidenceError(
            "archived manifest source-tree/environment differs from canonical document"
        )
    _validate_source_tree_environment(
        source_environment,
        candidate=candidate,
        candidate_blob_reader=candidate_blob_reader,
    )
    p0_review = documents[P0_REVIEW_NAME]
    # Reuse the structural validator without trusting a filesystem copy.
    _validate_p0_review_document(p0_review, candidate)

    source_rows, totals = _validate_source_reports(
        manifest=archived,
        candidate=candidate,
        provenance_mapping=mapping,
        artifact_reader=lambda relative: blobs[relative],
    )
    expected_decision = _decision_document(
        release_id=release_id,
        candidate=candidate,
        manifest=archived,
        source_rows=source_rows,
        totals=totals,
        p0_review=p0_review,
        p0_review_sha256=_sha256_bytes(blobs[P0_REVIEW_NAME]),
        source_environment_sha256=_sha256_bytes(blobs[SOURCE_ENVIRONMENT_NAME]),
        provenance=provenance,
        provenance_sha256=_sha256_bytes(blobs[PROVENANCE_MANIFEST_NAME]),
        execution_manifest_sha256=_sha256_bytes(blobs[runner.MANIFEST_NAME]),
    )
    if documents[DECISION_NAME] != expected_decision:
        raise runner.EvidenceError("Phase 7 engineering decision claims or totals drifted")
    if any(expected_decision[gate] != "PENDING_PROMOTION" for gate in MIGRATION_GATES):
        raise runner.EvidenceError("Phase 7 engineering decision promoted a migration gate")
    if any(expected_decision["runtime_restrictions"].values()):
        raise runner.EvidenceError("Phase 7 engineering decision grants a forbidden runtime action")

    index = documents[HASH_INDEX_NAME]
    expected_index_paths = _indexed_names(archived)
    artifacts = index.get("artifacts")
    if (
        set(index) != {"artifacts", "candidate_commit", "schema_version"}
        or index.get("schema_version") != HASH_INDEX_SCHEMA
        or index.get("candidate_commit") != candidate
        or not isinstance(artifacts, list)
        or [item.get("path") if isinstance(item, dict) else None for item in artifacts]
        != expected_index_paths
    ):
        raise runner.EvidenceError("candidate artifact index authority or coverage drifted")
    for artifact in artifacts:
        if not isinstance(artifact, dict) or set(artifact) != {"bytes", "path", "sha256"}:
            raise runner.EvidenceError("candidate artifact index record drifted")
        payload = blobs.get(artifact["path"])
        if (
            payload is None
            or artifact["sha256"] != _sha256_bytes(payload)
            or artifact["bytes"] != len(payload)
        ):
            raise runner.EvidenceError(f"candidate artifact {artifact['path']} drifted")
    return expected_decision


def _validate_bundle(
    *,
    output_dir: Path,
    candidate: str,
    release_id: str,
    manifest: dict[str, Any],
) -> dict[str, Any]:
    release_id = _validate_release_id(release_id)
    expected_names = {HASH_INDEX_NAME, *_indexed_names(manifest)}
    _assert_portable_output_paths(output_dir, sorted(expected_names))
    entries = list(output_dir.rglob("*"))
    files = [item for item in entries if item.is_file()]
    relatives = {item.relative_to(output_dir).as_posix() for item in files}
    if relatives != expected_names or any(item.is_symlink() for item in entries):
        raise runner.EvidenceError("Phase 7 candidate evidence output file set drifted")
    primary_lf = {HASH_INDEX_NAME, *_primary_names(manifest)}
    logical_root = (
        PurePosixPath("test-reports")
        / "temporal-first"
        / release_id
        / "phase-7-candidate"
    )
    for path in files:
        relative = path.relative_to(output_dir).as_posix()
        _assert_git_filter_stable(
            path,
            require_lf=relative in primary_lf,
            logical_path=(logical_root / relative).as_posix(),
        )
    blobs = {
        path.relative_to(output_dir).as_posix(): path.read_bytes() for path in files
    }
    return _validate_bundle_documents(
        candidate=candidate,
        release_id=release_id,
        manifest=manifest,
        blobs=blobs,
        candidate_blob_reader=lambda path: runner._git_bytes(candidate, path),
    )


def assemble_candidate_evidence(
    *,
    manifest: dict[str, Any],
    execution_manifest_path: Path,
    p0_review: dict[str, Any],
    output_dir: Path,
    release_id: str,
    candidate_commit: str,
) -> dict[str, Any]:
    release_id = _validate_release_id(release_id)
    candidate = runner._assert_candidate(candidate_commit)
    _validate_p0_review_document(p0_review, candidate)
    run_root = execution_manifest_path.resolve().parent
    source_dir = run_root / "r"
    _assert_portable_output_paths(
        output_dir, [HASH_INDEX_NAME, *_indexed_names(manifest)]
    )
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / ATTRIBUTES_NAME).write_bytes(ATTRIBUTES_BYTES)
    (output_dir / CANDIDATE_NAME).write_bytes((candidate + "\n").encode("ascii"))
    execution_manifest_sha256 = _archive_manifest(
        execution_manifest_path, output_dir / runner.MANIFEST_NAME, manifest
    )
    for record in manifest["commands"]:
        report_name = runner.SOURCE_REPORTS[record["id"]]
        source = source_dir / report_name
        if _sha256(source) != record.get("report_sha256"):
            raise runner.EvidenceError(f"accepted report {report_name} drifted")
        payload = source.read_bytes()
        _validate_normalized_report(
            payload,
            candidate=candidate,
            command_id=record["id"],
            minimum_tests=record["minimum_tests"],
        )
        (output_dir / report_name).write_bytes(payload)
    for spec in _provenance_specs(manifest):
        _copy_provenance_artifact(
            run_root=run_root,
            output_dir=output_dir,
            relative=spec["source_path"],
            archive_path=spec["archive_path"],
        )
    provenance = _build_provenance_manifest(
        manifest=manifest, candidate=candidate, output_dir=output_dir
    )
    _write_json_lf(output_dir / PROVENANCE_MANIFEST_NAME, provenance)
    source_environment = _source_environment_document(manifest, candidate)
    _write_json_lf(output_dir / SOURCE_ENVIRONMENT_NAME, source_environment)
    _write_json_lf(output_dir / P0_REVIEW_NAME, p0_review)
    provenance_mapping = _validate_provenance_manifest(
        manifest=manifest,
        provenance=provenance,
        candidate=candidate,
        artifact_reader=lambda relative: (output_dir / relative).read_bytes(),
    )
    source_rows, totals = _validate_source_reports(
        manifest=manifest,
        candidate=candidate,
        provenance_mapping=provenance_mapping,
        artifact_reader=lambda relative: (output_dir / relative).read_bytes(),
    )
    decision = _decision_document(
        release_id=release_id,
        candidate=candidate,
        manifest=manifest,
        source_rows=source_rows,
        totals=totals,
        p0_review=p0_review,
        p0_review_sha256=_sha256(output_dir / P0_REVIEW_NAME),
        source_environment_sha256=_sha256(output_dir / SOURCE_ENVIRONMENT_NAME),
        provenance=provenance,
        provenance_sha256=_sha256(output_dir / PROVENANCE_MANIFEST_NAME),
        execution_manifest_sha256=execution_manifest_sha256,
    )
    _write_json_lf(output_dir / DECISION_NAME, decision)
    index = {
        "artifacts": [
            {
                "bytes": (output_dir / name).stat().st_size,
                "path": name,
                "sha256": _sha256(output_dir / name),
            }
            for name in _indexed_names(manifest)
        ],
        "candidate_commit": candidate,
        "schema_version": HASH_INDEX_SCHEMA,
    }
    _write_json_lf(output_dir / HASH_INDEX_NAME, index)
    _validate_bundle(
        output_dir=output_dir,
        candidate=candidate,
        release_id=release_id,
        manifest=manifest,
    )
    return decision


def _committed_json(evidence_commit: str, path: str, context: str) -> dict[str, Any]:
    try:
        document = json.loads(_git_bytes("show", f"{evidence_commit}:{path}"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise runner.EvidenceError(f"cannot decode committed {context}: {exception}") from exception
    if not isinstance(document, dict):
        raise runner.EvidenceError(f"committed {context} must be a JSON object")
    return document


def _validate_archived_manifest_claims(
    manifest: dict[str, Any], candidate: str
) -> None:
    if (
        manifest.get("schema_version") != runner.SCHEMA_VERSION
        or manifest.get("candidate_commit") != candidate
        or manifest.get("status") != runner.GREEN_STATUS
        or manifest.get("decision_ceiling") != "PHASE_7_ENGINEERING_CHECKPOINT_ONLY"
        or manifest.get("next_phase_permission") != "PENDING_SEPARATE_EVIDENCE"
        or any(manifest.get(gate) != "PENDING_PROMOTION" for gate in MIGRATION_GATES)
        or manifest.get("pending_failure") is not None
        or manifest.get("quarantined_attempts_reused") is not False
        or manifest.get("concurrency")
        != {
            "observed_maximum_source_processes": 1,
            "policy_maximum_heavy_processes": 2,
            "policy_maximum_light_processes": 2,
            "runner_execution": "sequential",
        }
    ):
        raise runner.EvidenceError("committed candidate execution manifest claims drifted")
    records = manifest.get("commands")
    if not isinstance(records, list) or [
        item.get("id") if isinstance(item, dict) else None for item in records
    ] != list(runner.COMMAND_ORDER):
        raise runner.EvidenceError("committed candidate source order drifted")


def _assert_committed_regular_blob_modes(
    commit: str, expected_paths: set[str]
) -> None:
    raw = _git_bytes("ls-tree", "-z", commit, "--", *sorted(expected_paths))
    observed: set[str] = set()
    for record in raw.split(b"\0"):
        if not record:
            continue
        try:
            metadata, encoded_path = record.split(b"\t", 1)
            mode, object_type, _object_id = metadata.split(b" ", 2)
            path = encoded_path.decode("utf-8", errors="strict").replace("\\", "/")
        except (UnicodeDecodeError, ValueError) as exception:
            raise runner.EvidenceError(
                "cannot authenticate committed evidence tree entry"
            ) from exception
        if path in observed:
            raise runner.EvidenceError(
                f"committed evidence tree path is duplicated: {path}"
            )
        if mode not in {b"100644", b"100755"} or object_type != b"blob":
            raise runner.EvidenceError(
                f"committed evidence path must be a regular blob: {path}"
            )
        observed.add(path)
    if observed != expected_paths:
        raise runner.EvidenceError(
            "committed evidence regular-blob topology drifted; "
            f"missing={sorted(expected_paths - observed)}, "
            f"extra={sorted(observed - expected_paths)}"
        )


def verify_evidence_commit(
    *, evidence_commit: str, candidate_commit: str, release_id: str
) -> dict[str, Any]:
    release_id = _validate_release_id(release_id)
    evidence = runner._assert_candidate(evidence_commit, "evidence commit")
    candidate = runner._assert_candidate(candidate_commit)
    parent_record = _git_text("rev-list", "--parents", "-n", "1", evidence).strip().split()
    if parent_record != [evidence, candidate]:
        raise runner.EvidenceError(
            "Phase 7 candidate evidence commit must have the candidate as its sole parent"
        )
    prefix = f"test-reports/temporal-first/{release_id}/phase-7-candidate"
    manifest = _committed_json(
        evidence, f"{prefix}/{runner.MANIFEST_NAME}", "candidate execution manifest"
    )
    _validate_archived_manifest_claims(manifest, candidate)
    seal_validator = getattr(runner, "_assert_execution_manifest_seal", None)
    if callable(seal_validator):
        seal_validator(manifest)
    expected_names = {HASH_INDEX_NAME, *_indexed_names(manifest)}
    _assert_portable_output_paths(ROOT / prefix, sorted(expected_names))
    expected_paths = {f"{prefix}/{name}" for name in expected_names}
    records = [
        line
        for line in _git_text(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "-r",
            "--no-renames",
            evidence,
        ).splitlines()
        if line.strip()
    ]
    changed_paths: set[str] = set()
    for record in records:
        fields = record.split("\t")
        if len(fields) != 2 or fields[0] != "A":
            raise runner.EvidenceError(
                "Phase 7 candidate evidence commit may only add its immutable bundle"
            )
        changed_paths.add(fields[1].replace("\\", "/"))
    if changed_paths != expected_paths:
        raise runner.EvidenceError(
            "Phase 7 candidate evidence commit content topology drifted; "
            f"missing={sorted(expected_paths - changed_paths)}, "
            f"extra={sorted(changed_paths - expected_paths)}"
        )
    _assert_committed_regular_blob_modes(evidence, expected_paths)
    blobs = {
        name: _git_bytes("show", f"{evidence}:{prefix}/{name}")
        for name in expected_names
    }
    decision = _validate_bundle_documents(
        candidate=candidate,
        release_id=release_id,
        manifest=manifest,
        blobs=blobs,
        candidate_blob_reader=lambda path: runner._git_bytes(candidate, path),
    )
    return {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "candidate_commit": candidate,
        "decision_ceiling": decision["decision_ceiling"],
        "evidence_commit": evidence,
        "next_phase_permission": decision["next_phase_permission_after_commit"],
        "sole_parent_verified": True,
        "status": "EVIDENCE_COMMIT_VERIFIED",
    }


def generate_candidate_evidence(
    *,
    release_id: str,
    candidate_commit: str,
    execution_manifest_path: Path,
    p0_review_disposition_path: Path,
    output_dir: Path,
) -> dict[str, Any]:
    release_id = _validate_release_id(release_id)
    candidate = runner._assert_candidate(candidate_commit)
    if not p0_review_disposition_path.is_absolute():
        raise runner.EvidenceError("P0 review disposition must be an explicit absolute path")
    manifest_path = execution_manifest_path.resolve()
    run_root = manifest_path.parent
    review_path = p0_review_disposition_path.absolute()
    output = output_dir.resolve()
    staging = output.with_name(f".{output.name}.assembling")
    if output.exists() or staging.exists():
        raise runner.EvidenceError("candidate evidence output or staging path exists")
    manifest = load_green_manifest(manifest_path, candidate)
    p0_snapshot = _snapshot_p0_review_disposition(
        candidate,
        review_path,
        forbidden_roots=(ROOT, run_root, output, staging),
    )
    p0_review = p0_snapshot.document
    _assert_clean_detached_candidate(
        candidate, allowed_untracked_roots=(run_root, staging)
    )
    try:
        staging.parent.mkdir(parents=True, exist_ok=True)
        decision = assemble_candidate_evidence(
            manifest=manifest,
            execution_manifest_path=manifest_path,
            p0_review=p0_review,
            output_dir=staging,
            release_id=release_id,
            candidate_commit=candidate,
        )
        _assert_clean_detached_candidate(
            candidate, allowed_untracked_roots=(run_root, staging)
        )
        _validate_bundle(
            output_dir=staging,
            candidate=candidate,
            release_id=release_id,
            manifest=manifest,
        )
        _assert_p0_snapshot(p0_snapshot)
        staging.rename(output)
        return decision
    except Exception:
        if staging.exists():
            shutil.rmtree(staging)
        raise


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Assemble immutable Phase 7 engineering-candidate evidence from one exact-SHA "
            "green runner manifest and a separately authored P0 disposition."
        )
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--candidate-commit", required=True)
    parser.add_argument("--execution-manifest", type=Path)
    parser.add_argument("--p0-review-disposition", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument(
        "--verify-evidence-commit",
        help=(
            "verify a committed evidence-only direct child of --candidate-commit; "
            "generation inputs are forbidden in this mode"
        ),
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        candidate = arguments.candidate_commit.strip().lower()
        if arguments.verify_evidence_commit:
            if (
                arguments.execution_manifest is not None
                or arguments.p0_review_disposition is not None
                or arguments.output_dir is not None
            ):
                raise runner.EvidenceError(
                    "--verify-evidence-commit cannot be combined with generation inputs"
                )
            result = verify_evidence_commit(
                evidence_commit=arguments.verify_evidence_commit.strip().lower(),
                candidate_commit=candidate,
                release_id=arguments.release_id,
            )
            print(json.dumps(result, sort_keys=True))
            return 0
        if arguments.execution_manifest is None or arguments.p0_review_disposition is None:
            raise runner.EvidenceError(
                "generation requires --execution-manifest and --p0-review-disposition"
            )
        output = (
            arguments.output_dir
            or ROOT
            / "test-reports/temporal-first"
            / arguments.release_id
            / "phase-7-candidate"
        )
        decision = generate_candidate_evidence(
            release_id=arguments.release_id,
            candidate_commit=candidate,
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
        print(f"Phase 7 candidate evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": decision["candidate_commit"],
                "decision_ceiling": decision["decision_ceiling"],
                "evidence_dir": str(output.resolve()),
                "next_phase_permission_after_commit": decision[
                    "next_phase_permission_after_commit"
                ],
                "result": decision["result"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
