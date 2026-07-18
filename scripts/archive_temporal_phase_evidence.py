#!/usr/bin/env python3
"""Build a fail-closed, content-addressed MIG-001 evidence bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence


SCHEMA_VERSION = "temporal-first-phase-evidence.v1"
SQL_SNAPSHOT_SCHEMA_VERSION = "mig-001-sql-snapshot.v1"
CHECK_ID = "MIG-001"
TASK_QUEUES = {"case_control": "case-control", "room_control": "room-control"}
REQUIRED_SUREFIRE_TEST_CLASSES = frozenset(
    {
        "com.example.dispute.workflow.caseprocess.CaseProcessWorkflowReplayTest",
        "com.example.dispute.workflow.room.RoomControlWorkflowReplayTest",
        "com.example.dispute.workflow.recovery.TemporalWorkerRecoveryTest",
        "com.example.dispute.workflow.recovery.CaseDomainEventRecoveryRelayTest",
        "com.example.dispute.workflow.projection.ProcessProjectionReconciliationSchedulerTest",
    }
)
REQUIRED_FAILSAFE_TEST_CLASSES = frozenset(
    {
        "com.example.dispute.database.TemporalControlPlaneMigrationIntegrationTest",
        "com.example.dispute.workflow.room.RoomEpochAllocatorIntegrationTest",
        "com.example.dispute.workflow.bootstrap.RoomEpochBootstrapStoreIntegrationTest",
        "com.example.dispute.workflow.outbox.CaseCommandOutboxStoreIntegrationTest",
        "com.example.dispute.workflow.outbox.CommandOutboxKillWindowIntegrationTest",
        "com.example.dispute.workflow.caseprocess.ActivityCompletionLossIntegrationTest",
        "com.example.dispute.workflow.projection.ProcessProjectionFencingIntegrationTest",
        "com.example.dispute.workflow.projection.ProcessProjectionReconcilerIntegrationTest",
    }
)
REQUIRED_SQL_ASSERTIONS = {
    "before": frozenset(
        {
            "single_writer_slot",
            "shadow_epoch_active_pending",
            "projection_pre_activation_matches_epoch",
            "bootstrap_recoverable",
            "bootstrap_not_dead_letter",
            "command_outbox_not_dead_letter",
            "no_open_critical_reconciliation",
        }
    ),
    "after": frozenset(
        {
            "single_writer_slot",
            "shadow_epoch_active_ready",
            "projection_ready_matches_epoch",
            "bootstrap_delivered",
            "bootstrap_receipt_matches_epoch",
            "bootstrap_no_recoverable",
            "bootstrap_not_dead_letter",
            "command_shadow_completed",
            "command_outbox_terminal",
            "command_outbox_no_recoverable",
            "command_outbox_not_dead_letter",
            "no_open_critical_reconciliation",
        }
    ),
}
WORKFLOW_KINDS = {
    ("CaseProcessWorkflow", "case-control"): "CASE",
    ("RoomControlWorkflow", "room-control"): "ROOM",
}
REQUIRED_METADATA_KEYS = frozenset(
    {
        "environment_id",
        "temporal_namespace",
        "control_build_id",
        "java_image_digest",
        "postgresql_version",
        "temporal_server_version",
        "kms_key_id",
        "artifact_retention_days",
    }
)
ALLOWED_METADATA_KEYS = REQUIRED_METADATA_KEYS | {"skip_approval"}
AUTHORITY_MEMO_KEY = "case_process_authority_checkpoint_v1"
RELEASE_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
SAFE_ARCHIVE_NAME_RE = re.compile(r"[^A-Za-z0-9._-]+")
SHA256_RE = re.compile(r"sha256:[0-9a-f]{64}")


class EvidenceRejected(RuntimeError):
    """Raised when an input cannot support a technical PASS decision."""


@dataclass(frozen=True)
class EvidenceIdentity:
    case_id: str
    epoch_id: str
    bootstrap_update_id: str
    command_id: str
    case_workflow_id: str
    case_run_id: str
    room_workflow_id: str
    room_run_id: str

    def __post_init__(self) -> None:
        for field, value in self.as_dict().items():
            if not isinstance(value, str) or not value.strip():
                raise EvidenceRejected(f"{field} must not be blank")

    def as_dict(self) -> dict[str, str]:
        return {
            "case_id": self.case_id,
            "epoch_id": self.epoch_id,
            "bootstrap_update_id": self.bootstrap_update_id,
            "command_id": self.command_id,
            "case_workflow_id": self.case_workflow_id,
            "case_run_id": self.case_run_id,
            "room_workflow_id": self.room_workflow_id,
            "room_run_id": self.room_run_id,
        }


@dataclass(frozen=True)
class Artifact:
    path: Path
    role: str
    bundle_path: str
    data: bytes
    sha256: str

    @property
    def size_bytes(self) -> int:
        return len(self.data)


@dataclass(frozen=True)
class EvidenceCollection:
    document: dict[str, Any]
    artifacts: tuple[Artifact, ...]


@dataclass(frozen=True)
class BundleOutput:
    path: Path
    sha256: str


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _safe_archive_name(path: Path) -> str:
    candidate = SAFE_ARCHIVE_NAME_RE.sub("_", path.name).strip("._")
    return candidate or "artifact"


def _read_artifact(path: Path, role: str, role_index: int) -> Artifact:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise EvidenceRejected(f"missing {role} file: {resolved}")
    try:
        data = resolved.read_bytes()
    except OSError as exception:
        raise EvidenceRejected(f"cannot read {role} file {resolved}: {exception}") from exception
    if not data:
        raise EvidenceRejected(f"empty {role} file: {resolved}")
    digest = hashlib.sha256(data).hexdigest()
    bundle_path = (
        f"artifacts/{role}/{role_index:03d}-{digest[:12]}-{_safe_archive_name(resolved)}"
    )
    return Artifact(resolved, role, bundle_path, data, digest)


def _display_path(path: Path, repository: Path) -> str:
    try:
        return path.relative_to(repository).as_posix()
    except ValueError:
        return path.as_posix()


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _non_negative_int(value: str, field: str, path: Path) -> int:
    try:
        parsed = int(value)
    except ValueError as exception:
        raise EvidenceRejected(f"{path}: JUnit {field} is not an integer") from exception
    if parsed < 0:
        raise EvidenceRejected(f"{path}: JUnit {field} is negative")
    return parsed


def _parse_junit(artifact: Artifact, repository: Path) -> dict[str, Any]:
    try:
        root = ET.fromstring(artifact.data)
    except ET.ParseError as exception:
        raise EvidenceRejected(f"invalid JUnit XML {artifact.path}: {exception}") from exception
    if _local_name(root.tag) not in {"testsuite", "testsuites"}:
        raise EvidenceRejected(f"{artifact.path}: JUnit root must be testsuite or testsuites")

    cases = [element for element in root.iter() if _local_name(element.tag) == "testcase"]
    if not cases:
        raise EvidenceRejected(f"{artifact.path}: JUnit XML contains no test cases")
    failures = 0
    errors = 0
    skipped = 0
    classnames: set[str] = set()
    for case in cases:
        classname = case.attrib.get("classname")
        if classname is None or not classname.strip():
            raise EvidenceRejected(f"{artifact.path}: every JUnit testcase requires classname")
        classnames.add(classname.split("$", 1)[0])
        statuses = {_local_name(child.tag) for child in case}
        failures += int("failure" in statuses)
        errors += int("error" in statuses)
        skipped += int("skipped" in statuses)

    computed = {
        "tests": len(cases),
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
    }
    for field, count in computed.items():
        declared = root.attrib.get(field)
        if declared is not None and _non_negative_int(declared, field, artifact.path) != count:
            raise EvidenceRejected(
                f"{artifact.path}: declared JUnit {field} does not match testcase content"
            )
    return {
        "path": _display_path(artifact.path, repository),
        "suite": root.attrib.get("name") or artifact.path.stem,
        "classes": sorted(classnames),
        **computed,
        "sha256": artifact.sha256,
    }


def _load_json(artifact: Artifact) -> Any:
    try:
        return json.loads(artifact.data.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise EvidenceRejected(f"invalid JSON {artifact.path}: {exception}") from exception


def _field(container: Any, camel: str, snake: str) -> Any:
    if not isinstance(container, dict):
        return None
    return container.get(camel, container.get(snake))


def _named_field(container: Any, camel: str, snake: str) -> str | None:
    value = _field(container, camel, snake)
    if isinstance(value, dict):
        value = value.get("name")
    return value if isinstance(value, str) and value.strip() else None


def _canonical_event_type(value: str) -> str:
    candidate = value.removeprefix("EVENT_TYPE_")
    if "_" not in candidate:
        candidate = re.sub(r"(?<!^)(?=[A-Z])", "_", candidate)
    return candidate.upper()


def _execution_identity(container: Any) -> tuple[str | None, str | None]:
    execution = _field(container, "workflowExecution", "workflow_execution")
    return (
        _field(execution, "workflowId", "workflow_id"),
        _field(execution, "runId", "run_id"),
    )


def _event_attributes(event: dict[str, Any], stem: str) -> Any:
    snake = re.sub(r"(?<!^)(?=[A-Z])", "_", stem).lower()
    return _field(event, stem + "EventAttributes", snake + "_event_attributes")


def _accepted_update(event: dict[str, Any]) -> tuple[str | None, str | None]:
    attributes = _event_attributes(event, "workflowExecutionUpdateAccepted")
    request = _field(attributes, "acceptedRequest", "accepted_request")
    meta = _field(request, "meta", "meta")
    update_input = _field(request, "input", "input")
    update_id = _field(meta, "updateId", "update_id")
    if update_id is None:
        update_id = _field(attributes, "protocolInstanceId", "protocol_instance_id")
    return update_id, _field(update_input, "name", "name")


def _completed_update_id(event: dict[str, Any]) -> str | None:
    attributes = _event_attributes(event, "workflowExecutionUpdateCompleted")
    meta = _field(attributes, "meta", "meta")
    return _field(meta, "updateId", "update_id")


def _event_id(event: dict[str, Any], path: Path) -> int:
    raw_event_id = _field(event, "eventId", "event_id")
    try:
        event_id = int(raw_event_id)
    except (TypeError, ValueError) as exception:
        raise EvidenceRejected(
            f"{path}: every Temporal event requires an integer eventId"
        ) from exception
    if event_id < 1:
        raise EvidenceRejected(f"{path}: Temporal eventId values must be positive")
    return event_id


def _require_update_pair(
    events: Sequence[dict[str, Any]], update_id: str, update_name: str, path: Path
) -> tuple[int, int]:
    accepted = [
        _event_id(event, path)
        for event in events
        if _canonical_event_type(str(_field(event, "eventType", "event_type")))
        == "WORKFLOW_EXECUTION_UPDATE_ACCEPTED"
        and _accepted_update(event) == (update_id, update_name)
    ]
    completed = [
        _event_id(event, path)
        for event in events
        if _canonical_event_type(str(_field(event, "eventType", "event_type")))
        == "WORKFLOW_EXECUTION_UPDATE_COMPLETED"
        and _completed_update_id(event) == update_id
    ]
    if len(accepted) != 1 or len(completed) != 1 or accepted[0] >= completed[0]:
        raise EvidenceRejected(
            f"{path}: update {update_id} must have one ordered {update_name} accept/complete pair"
        )
    return accepted[0], completed[0]


def _authority_memo_event_ids(
    events: Sequence[dict[str, Any]], path: Path
) -> list[int]:
    matches = []
    for event in events:
        event_type = _canonical_event_type(str(_field(event, "eventType", "event_type")))
        if event_type != "WORKFLOW_PROPERTIES_MODIFIED":
            continue
        attributes = _event_attributes(event, "workflowPropertiesModified")
        memo = _field(attributes, "upsertedMemo", "upserted_memo")
        fields = _field(memo, "fields", "fields")
        if isinstance(fields, dict) and AUTHORITY_MEMO_KEY in fields:
            matches.append(_event_id(event, path))
    return matches


def _parse_temporal_history(
    artifact: Artifact, repository: Path, identity: EvidenceIdentity
) -> dict[str, Any]:
    document = _load_json(artifact)
    events: Any = None
    if isinstance(document, list):
        events = document
    elif isinstance(document, dict):
        events = document.get("events")
        if events is None and isinstance(document.get("history"), dict):
            events = document["history"].get("events")
    if not isinstance(events, list) or not events:
        raise EvidenceRejected(f"{artifact.path}: Temporal History contains no events")
    if any(not isinstance(event, dict) for event in events):
        raise EvidenceRejected(f"{artifact.path}: Temporal History events must be objects")

    event_ids: list[int] = []
    event_types: list[str] = []
    for event in events:
        event_ids.append(_event_id(event, artifact.path))
        raw_type = _field(event, "eventType", "event_type")
        if not isinstance(raw_type, str) or not raw_type.strip():
            raise EvidenceRejected(f"{artifact.path}: every Temporal event requires eventType")
        event_types.append(_canonical_event_type(raw_type))
    if len(event_ids) != len(set(event_ids)) or any(
        current <= previous for previous, current in zip(event_ids, event_ids[1:])
    ):
        raise EvidenceRejected(
            f"{artifact.path}: Temporal eventId values must be unique and strictly increasing"
        )
    if event_types[0] != "WORKFLOW_EXECUTION_STARTED":
        raise EvidenceRejected(
            f"{artifact.path}: first Temporal event must be WorkflowExecutionStarted"
        )

    start = events[0]
    start_attributes = _event_attributes(start, "workflowExecutionStarted")
    workflow_type = _named_field(start_attributes, "workflowType", "workflow_type")
    task_queue = _named_field(start_attributes, "taskQueue", "task_queue")
    workflow_kind = WORKFLOW_KINDS.get((workflow_type, task_queue))
    if workflow_kind is None:
        raise EvidenceRejected(
            f"{artifact.path}: History must identify a fixed Case or Room control workflow"
        )
    run_id = _field(start_attributes, "originalExecutionRunId", "original_execution_run_id")
    expected_run_id = identity.case_run_id if workflow_kind == "CASE" else identity.room_run_id
    if run_id != expected_run_id:
        raise EvidenceRejected(f"{artifact.path}: History run ID does not match scenario")

    required_events: dict[str, int] = {"workflow_started": event_ids[0]}
    if workflow_kind == "CASE":
        bootstrap_accept, bootstrap_complete = _require_update_pair(
            events, identity.bootstrap_update_id, "provisionRoomEpoch", artifact.path
        )
        command_accept, command_complete = _require_update_pair(
            events, identity.command_id, "acceptCommand", artifact.path
        )
        child_initiated = []
        child_started = []
        command_signals = []
        for event, event_type in zip(events, event_types):
            if event_type == "START_CHILD_WORKFLOW_EXECUTION_INITIATED":
                attributes = _event_attributes(event, "startChildWorkflowExecutionInitiated")
                if (
                    _field(attributes, "workflowId", "workflow_id") == identity.room_workflow_id
                    and _named_field(attributes, "workflowType", "workflow_type")
                    == "RoomControlWorkflow"
                    and _named_field(attributes, "taskQueue", "task_queue") == "room-control"
                ):
                    child_initiated.append(_event_id(event, artifact.path))
            elif event_type == "CHILD_WORKFLOW_EXECUTION_STARTED":
                attributes = _event_attributes(event, "childWorkflowExecutionStarted")
                if _execution_identity(attributes) == (
                    identity.room_workflow_id,
                    identity.room_run_id,
                ):
                    child_started.append(_event_id(event, artifact.path))
            elif event_type == "SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED":
                attributes = _event_attributes(event, "signalExternalWorkflowExecutionInitiated")
                signal_name = _field(attributes, "signalName", "signal_name")
                if signal_name == "roomCommandAccepted" and _execution_identity(attributes) == (
                    identity.room_workflow_id,
                    identity.room_run_id,
                ):
                    command_signals.append(_event_id(event, artifact.path))
        authority_memos = _authority_memo_event_ids(events, artifact.path)
        if len(child_initiated) != 1 or len(child_started) != 1:
            raise EvidenceRejected(
                f"{artifact.path}: Case History lacks the bound Room child"
            )
        if len(authority_memos) != 1:
            raise EvidenceRejected(
                f"{artifact.path}: Case History requires one authority checkpoint memo"
            )
        if len(command_signals) != 1:
            raise EvidenceRejected(
                f"{artifact.path}: Case History requires one bound Room command signal"
            )
        if not (
            bootstrap_accept
            < child_initiated[0]
            < child_started[0]
            < authority_memos[0]
            < bootstrap_complete
        ):
            raise EvidenceRejected(
                f"{artifact.path}: Room child and authority memo must occur inside the "
                "provisionRoomEpoch update"
            )
        if not command_accept < command_signals[0] < command_complete:
            raise EvidenceRejected(
                f"{artifact.path}: Room command signal must occur inside the acceptCommand update"
            )
        required_events.update(
            {
                "bootstrap_update_accepted": bootstrap_accept,
                "room_child_initiated": child_initiated[0],
                "room_child_started": child_started[0],
                "authority_memo": authority_memos[0],
                "bootstrap_update_completed": bootstrap_complete,
                "command_update_accepted": command_accept,
                "room_command_signal_initiated": command_signals[0],
                "command_update_completed": command_complete,
            }
        )
    else:
        parent = _field(
            start_attributes, "parentWorkflowExecution", "parent_workflow_execution"
        )
        if _execution_identity({"workflowExecution": parent}) != (
            identity.case_workflow_id,
            identity.case_run_id,
        ):
            raise EvidenceRejected(f"{artifact.path}: Room History parent does not match Case run")
        command_signals = []
        for event, event_type in zip(events, event_types):
            if event_type != "WORKFLOW_EXECUTION_SIGNALED":
                continue
            attributes = _event_attributes(event, "workflowExecutionSignaled")
            external_execution = _field(
                attributes, "externalWorkflowExecution", "external_workflow_execution"
            )
            if (
                _field(attributes, "signalName", "signal_name") == "roomCommandAccepted"
                and _execution_identity({"workflowExecution": external_execution})
                == (identity.case_workflow_id, identity.case_run_id)
            ):
                command_signals.append(_event_id(event, artifact.path))
        if len(command_signals) != 1:
            raise EvidenceRejected(
                f"{artifact.path}: Room History requires one roomCommandAccepted signal "
                "from the bound Case run"
            )
        required_events["room_command_signal_received"] = command_signals[0]

    return {
        "path": _display_path(artifact.path, repository),
        "event_count": len(events),
        "first_event_id": event_ids[0],
        "last_event_id": event_ids[-1],
        "workflow_kind": workflow_kind,
        "workflow_type": workflow_type,
        "task_queue": task_queue,
        "run_id": run_id,
        "required_events": required_events,
        "sha256": artifact.sha256,
    }


def _parse_timestamp(value: Any, field: str, path: Path) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise EvidenceRejected(f"{path}: SQL snapshot {field} is required")
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exception:
        raise EvidenceRejected(f"{path}: SQL snapshot {field} is invalid") from exception
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise EvidenceRejected(f"{path}: SQL snapshot {field} must include a timezone")
    return parsed.astimezone(timezone.utc)


def _validate_snapshot_identity(
    document: dict[str, Any], expected_phase: str, identity: EvidenceIdentity, path: Path
) -> dict[str, Any]:
    snapshot_identity = document.get("identity")
    if not isinstance(snapshot_identity, dict):
        raise EvidenceRejected(f"{path}: SQL snapshot identity is required")
    expected = identity.as_dict()
    if frozenset(snapshot_identity) != frozenset(expected):
        raise EvidenceRejected(f"{path}: SQL snapshot identity fields are incomplete")
    for field, expected_value in expected.items():
        actual = snapshot_identity[field]
        if expected_phase == "before" and field in {"case_run_id", "room_run_id"}:
            if actual not in {None, expected_value}:
                raise EvidenceRejected(f"{path}: before {field} conflicts with scenario")
        elif actual != expected_value:
            raise EvidenceRejected(f"{path}: SQL snapshot {field} does not match scenario")
    return snapshot_identity


def _parse_sql_snapshot(
    artifact: Artifact,
    expected_phase: str,
    repository: Path,
    identity: EvidenceIdentity,
) -> tuple[dict[str, Any], datetime]:
    document = _load_json(artifact)
    if not isinstance(document, dict):
        raise EvidenceRejected(f"{artifact.path}: SQL snapshot must be a JSON object")
    if document.get("schema_version") != SQL_SNAPSHOT_SCHEMA_VERSION:
        raise EvidenceRejected(f"{artifact.path}: unsupported SQL snapshot schema")
    if document.get("snapshot_phase") != expected_phase:
        raise EvidenceRejected(f"{artifact.path}: SQL snapshot phase must be {expected_phase}")
    if document.get("writer_mode") != "SHADOW":
        raise EvidenceRejected(f"{artifact.path}: MIG-001 canary writer_mode must be SHADOW")
    snapshot_identity = _validate_snapshot_identity(
        document, expected_phase, identity, artifact.path
    )
    captured_at = document.get("captured_at_utc")
    captured_time = _parse_timestamp(captured_at, "captured_at_utc", artifact.path)
    assertions = document.get("assertions")
    if not isinstance(assertions, dict) or not assertions:
        raise EvidenceRejected(f"{artifact.path}: SQL snapshot assertions are required")
    required = REQUIRED_SQL_ASSERTIONS[expected_phase]
    actual = frozenset(assertions)
    if actual != required:
        missing = sorted(required - actual)
        unexpected = sorted(actual - required)
        raise EvidenceRejected(
            f"{artifact.path}: SQL assertions do not match {expected_phase} contract; "
            f"missing={missing}, unexpected={unexpected}"
        )
    invalid = sorted(key for key, value in assertions.items() if value is not True)
    if invalid:
        raise EvidenceRejected(f"{artifact.path}: SQL assertions did not pass: {invalid}")
    return (
        {
            "path": _display_path(artifact.path, repository),
            "captured_at_utc": captured_at,
            "writer_mode": "SHADOW",
            "identity": snapshot_identity,
            "assertions": sorted(assertions),
            "sha256": artifact.sha256,
        },
        captured_time,
    )


def _git_output(repository: Path, *arguments: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(repository), *arguments],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
    except OSError as exception:
        raise EvidenceRejected(f"cannot execute git: {exception}") from exception
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "unknown git error"
        raise EvidenceRejected(f"git {' '.join(arguments)} failed: {detail}")
    return result.stdout.strip()


def _inspect_git(repository: Path, evaluated_head: str) -> dict[str, Any]:
    top_level = Path(_git_output(repository, "rev-parse", "--show-toplevel")).resolve()
    if top_level != repository:
        raise EvidenceRejected(f"repository must be the Git top level: {top_level}")
    actual_head = _git_output(repository, "rev-parse", "HEAD")
    if actual_head != evaluated_head:
        raise EvidenceRejected(
            f"evaluated HEAD {evaluated_head} does not match repository HEAD {actual_head}"
        )
    dirty = _git_output(repository, "status", "--porcelain=v1", "--untracked-files=all")
    if dirty:
        raise EvidenceRejected(f"repository is dirty: {dirty.splitlines()[0]}")
    return {"evaluated_head": evaluated_head, "actual_head": actual_head, "clean": True}


def _parse_metadata(values: Sequence[str], max_skips: int) -> dict[str, str]:
    metadata: dict[str, str] = {}
    for value in values:
        key, separator, item = value.partition("=")
        if not separator or key not in ALLOWED_METADATA_KEYS or not item.strip():
            raise EvidenceRejected(f"metadata key is not allowlisted or value is blank: {key!r}")
        if key in metadata:
            raise EvidenceRejected(f"duplicate metadata key: {key}")
        if len(item) > 256 or "\n" in item or "\r" in item:
            raise EvidenceRejected(f"metadata value is invalid: {key}")
        metadata[key] = item
    missing = REQUIRED_METADATA_KEYS - metadata.keys()
    if missing:
        raise EvidenceRejected(f"required metadata is missing: {sorted(missing)}")
    if not SHA256_RE.fullmatch(metadata["java_image_digest"]):
        raise EvidenceRejected("java_image_digest must be a sha256 digest")
    try:
        retention_days = int(metadata["artifact_retention_days"])
    except ValueError as exception:
        raise EvidenceRejected("artifact_retention_days must be an integer") from exception
    if retention_days < 1 or retention_days > 3650:
        raise EvidenceRejected("artifact_retention_days must be between 1 and 3650")
    if max_skips > 0 and "skip_approval" not in metadata:
        raise EvidenceRejected("skip_approval metadata is required when skips are allowed")
    return dict(sorted(metadata.items()))


def _artifact_entry(artifact: Artifact, repository: Path) -> dict[str, Any]:
    return {
        "role": artifact.role,
        "source_path": _display_path(artifact.path, repository),
        "bundle_path": artifact.bundle_path,
        "size_bytes": artifact.size_bytes,
        "sha256": artifact.sha256,
    }


def _assert_inputs_unchanged(artifacts: Sequence[Artifact]) -> None:
    for artifact in artifacts:
        try:
            current = artifact.path.read_bytes()
        except OSError as exception:
            raise EvidenceRejected(
                f"cannot re-read input {artifact.path}: {exception}"
            ) from exception
        if hashlib.sha256(current).hexdigest() != artifact.sha256:
            raise EvidenceRejected(f"input changed while collecting evidence: {artifact.path}")


def _coverage(
    reports: Sequence[dict[str, Any]], required: frozenset[str], label: str
) -> dict[str, Any]:
    classes = {classname for report in reports for classname in report["classes"]}
    missing = sorted(required - classes)
    if missing:
        raise EvidenceRejected(f"required {label} test classes did not run: {missing}")
    return {"required_classes": sorted(required), "observed_classes": sorted(classes)}


def collect_evidence(
    *,
    repository: Path,
    release_id: str,
    evaluated_head: str,
    environment_name: str,
    identity: EvidenceIdentity,
    surefire_xml: Sequence[Path],
    failsafe_xml: Sequence[Path],
    temporal_histories: Sequence[Path],
    sql_before: Path,
    sql_after: Path,
    max_skips: int = 0,
    metadata: Sequence[str] = (),
) -> EvidenceCollection:
    repository = repository.expanduser().resolve()
    if not RELEASE_ID_RE.fullmatch(release_id):
        raise EvidenceRejected("release_id contains unsupported characters")
    if not environment_name.strip():
        raise EvidenceRejected("environment_name must not be blank")
    if max_skips < 0:
        raise EvidenceRejected("max_skips must not be negative")
    if not surefire_xml or not failsafe_xml:
        raise EvidenceRejected("both Surefire and Failsafe JUnit XML files are required")
    if len(temporal_histories) < 2:
        raise EvidenceRejected("at least two Case/Room Temporal History JSON files are required")

    environment_metadata = _parse_metadata(metadata, max_skips)
    initial_git = _inspect_git(repository, evaluated_head)
    artifacts: list[Artifact] = []
    for role, paths in (
        ("surefire_xml", surefire_xml),
        ("failsafe_xml", failsafe_xml),
        ("temporal_history", temporal_histories),
        ("sql_before", (sql_before,)),
        ("sql_after", (sql_after,)),
    ):
        artifacts.extend(
            _read_artifact(path, role, index) for index, path in enumerate(paths, start=1)
        )
    paths = [artifact.path for artifact in artifacts]
    if len(paths) != len(set(paths)):
        raise EvidenceRejected("the same input file was supplied more than once")

    surefire_count = len(surefire_xml)
    failsafe_count = len(failsafe_xml)
    history_count = len(temporal_histories)
    surefire = [
        _parse_junit(artifact, repository) for artifact in artifacts[:surefire_count]
    ]
    failsafe_start = surefire_count
    failsafe_end = failsafe_start + failsafe_count
    failsafe = [
        _parse_junit(artifact, repository)
        for artifact in artifacts[failsafe_start:failsafe_end]
    ]
    history_end = failsafe_end + history_count
    histories = [
        _parse_temporal_history(artifact, repository, identity)
        for artifact in artifacts[failsafe_end:history_end]
    ]
    history_kinds = {history["workflow_kind"] for history in histories}
    if history_kinds != {"CASE", "ROOM"}:
        raise EvidenceRejected("Temporal histories must include both Case and Room workflows")
    before, before_time = _parse_sql_snapshot(
        artifacts[-2], "before", repository, identity
    )
    after, after_time = _parse_sql_snapshot(artifacts[-1], "after", repository, identity)
    if after_time < before_time:
        raise EvidenceRejected("SQL after snapshot predates the before snapshot")

    reports = [*surefire, *failsafe]
    totals = {
        field: sum(report[field] for report in reports)
        for field in ("tests", "failures", "errors", "skipped")
    }
    if totals["failures"] or totals["errors"]:
        raise EvidenceRejected(
            f"JUnit failures/errors are non-zero: {totals['failures']}/{totals['errors']}"
        )
    if totals["skipped"] > max_skips:
        raise EvidenceRejected(
            f"JUnit skipped count {totals['skipped']} exceeds max_skips {max_skips}"
        )
    coverage = {
        "surefire": _coverage(
            surefire, REQUIRED_SUREFIRE_TEST_CLASSES, "Surefire"
        ),
        "failsafe": _coverage(
            failsafe, REQUIRED_FAILSAFE_TEST_CLASSES, "Failsafe"
        ),
    }

    _assert_inputs_unchanged(artifacts)
    final_git = _inspect_git(repository, evaluated_head)
    if final_git != initial_git:
        raise EvidenceRejected("Git state changed while collecting evidence")
    document = {
        "schema_version": SCHEMA_VERSION,
        "check_id": CHECK_ID,
        "release_id": release_id,
        "technical_result": "PASS",
        "promotion_status": "PENDING_APPROVAL",
        "generated_at_utc": _utc_now(),
        "git": final_git,
        "scenario": {"writer_mode": "SHADOW", "identity": identity.as_dict()},
        "environment": {
            "name": environment_name,
            "os": platform.system(),
            "os_release": platform.release(),
            "machine": platform.machine(),
            "python_version": platform.python_version(),
            "metadata": environment_metadata,
        },
        "task_queues": TASK_QUEUES,
        "tests": {
            "files": len(reports),
            **totals,
            "max_skips": max_skips,
            "coverage": coverage,
            "surefire_reports": surefire,
            "failsafe_reports": failsafe,
        },
        "temporal_histories": histories,
        "sql_snapshots": {"before": before, "after": after},
        "artifacts": sorted(
            (_artifact_entry(artifact, repository) for artifact in artifacts),
            key=lambda item: item["bundle_path"],
        ),
        "bundle": {"format": "deterministic-zip.v1", "hash_algorithm": "SHA-256"},
    }
    return EvidenceCollection(document, tuple(artifacts))


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def write_evidence_bundle(
    collection: EvidenceCollection, output_dir: Path, overwrite: bool = False
) -> BundleOutput:
    output_dir = output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest = (
        json.dumps(collection.document, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w+b",
            prefix=".mig-001-bundle.",
            suffix=".zip.tmp",
            dir=output_dir,
            delete=False,
        ) as temporary:
            temporary_name = temporary.name
        with zipfile.ZipFile(temporary_name, "w", allowZip64=True) as archive:
            archive.writestr(_zip_info("evidence.json"), manifest)
            for artifact in sorted(collection.artifacts, key=lambda item: item.bundle_path):
                archive.writestr(_zip_info(artifact.bundle_path), artifact.data)
        bundle_data = Path(temporary_name).read_bytes()
        digest = hashlib.sha256(bundle_data).hexdigest()
        destination = output_dir / f"mig-001-sha256-{digest}.zip"
        if destination.exists() and not overwrite:
            raise EvidenceRejected(f"content-addressed bundle already exists: {destination}")
        os.replace(temporary_name, destination)
        temporary_name = None
        return BundleOutput(destination, digest)
    finally:
        if temporary_name is not None:
            Path(temporary_name).unlink(missing_ok=True)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--evaluated-head", required=True)
    parser.add_argument("--environment-name", required=True)
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--epoch-id", required=True)
    parser.add_argument("--bootstrap-update-id", required=True)
    parser.add_argument("--command-id", required=True)
    parser.add_argument("--case-workflow-id", required=True)
    parser.add_argument("--case-run-id", required=True)
    parser.add_argument("--room-workflow-id", required=True)
    parser.add_argument("--room-run-id", required=True)
    parser.add_argument("--surefire-xml", type=Path, action="append", required=True)
    parser.add_argument("--failsafe-xml", type=Path, action="append", required=True)
    parser.add_argument("--temporal-history", type=Path, action="append", required=True)
    parser.add_argument("--sql-before", type=Path, required=True)
    parser.add_argument("--sql-after", type=Path, required=True)
    parser.add_argument("--bundle-output-dir", type=Path, required=True)
    parser.add_argument("--max-skips", type=int, default=0)
    parser.add_argument("--metadata", action="append", default=[])
    parser.add_argument("--overwrite", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        identity = EvidenceIdentity(
            case_id=arguments.case_id,
            epoch_id=arguments.epoch_id,
            bootstrap_update_id=arguments.bootstrap_update_id,
            command_id=arguments.command_id,
            case_workflow_id=arguments.case_workflow_id,
            case_run_id=arguments.case_run_id,
            room_workflow_id=arguments.room_workflow_id,
            room_run_id=arguments.room_run_id,
        )
        collection = collect_evidence(
            repository=arguments.repository,
            release_id=arguments.release_id,
            evaluated_head=arguments.evaluated_head,
            environment_name=arguments.environment_name,
            identity=identity,
            surefire_xml=arguments.surefire_xml,
            failsafe_xml=arguments.failsafe_xml,
            temporal_histories=arguments.temporal_history,
            sql_before=arguments.sql_before,
            sql_after=arguments.sql_after,
            max_skips=arguments.max_skips,
            metadata=arguments.metadata,
        )
        bundle = write_evidence_bundle(
            collection, arguments.bundle_output_dir, arguments.overwrite
        )
    except EvidenceRejected as exception:
        print(f"{CHECK_ID} evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {"bundle_path": str(bundle.path), "bundle_sha256": bundle.sha256},
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
