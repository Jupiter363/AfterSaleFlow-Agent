from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import zipfile
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/archive_temporal_phase_evidence.py"
SPEC = importlib.util.spec_from_file_location("archive_temporal_phase_evidence", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
evidence = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = evidence
SPEC.loader.exec_module(evidence)

HEAD = "a" * 40
IDENTITY = evidence.EvidenceIdentity(
    case_id="CASE_MIG001_SYNTHETIC",
    epoch_id="EPOCH_MIG001_SYNTHETIC",
    bootstrap_update_id="bootstrap-mig001-1",
    command_id="command-mig001-1",
    case_workflow_id="case-process:synthetic:CASE_MIG001_SYNTHETIC",
    case_run_id="11111111-1111-1111-1111-111111111111",
    room_workflow_id="room-workflow:CASE_MIG001_SYNTHETIC:INTAKE:0",
    room_run_id="22222222-2222-2222-2222-222222222222",
)


def _write(path: Path, text: str) -> Path:
    path.write_text(text, encoding="utf-8")
    return path


def _junit(classes: list[str], *, skipped: bool = False, failed: bool = False) -> str:
    cases = []
    for index, classname in enumerate(classes):
        status = ""
        if skipped and index == 0:
            status = '<skipped message="approved"/>'
        elif failed and index == 0:
            status = '<failure message="failed"/>'
        cases.append(
            f'<testcase classname="{classname}" name="scenario{index}">{status}</testcase>'
        )
    return (
        f'<testsuite name="phase-one" tests="{len(classes)}" '
        f'failures="{int(failed)}" errors="0" skipped="{int(skipped)}">'
        + "".join(cases)
        + "</testsuite>"
    )


def _event(event_id: int, event_type: str, attributes: dict | None = None) -> dict:
    event = {"eventId": str(event_id), "eventType": f"EVENT_TYPE_{event_type}"}
    if attributes:
        event.update(attributes)
    return event


def _accepted_update(event_id: int, update_id: str, name: str) -> dict:
    return _event(
        event_id,
        "WORKFLOW_EXECUTION_UPDATE_ACCEPTED",
        {
            "workflowExecutionUpdateAcceptedEventAttributes": {
                "protocolInstanceId": update_id,
                "acceptedRequest": {
                    "meta": {"updateId": update_id},
                    "input": {"name": name},
                },
            }
        },
    )


def _completed_update(event_id: int, update_id: str) -> dict:
    return _event(
        event_id,
        "WORKFLOW_EXECUTION_UPDATE_COMPLETED",
        {"workflowExecutionUpdateCompletedEventAttributes": {"meta": {"updateId": update_id}}},
    )


def _case_history() -> dict:
    return {
        "events": [
            _event(
                1,
                "WORKFLOW_EXECUTION_STARTED",
                {
                    "workflowExecutionStartedEventAttributes": {
                        "workflowType": {"name": "CaseProcessWorkflow"},
                        "taskQueue": {"name": "case-control"},
                        "originalExecutionRunId": IDENTITY.case_run_id,
                    }
                },
            ),
            _accepted_update(2, IDENTITY.bootstrap_update_id, "provisionRoomEpoch"),
            _event(
                3,
                "START_CHILD_WORKFLOW_EXECUTION_INITIATED",
                {
                    "startChildWorkflowExecutionInitiatedEventAttributes": {
                        "workflowId": IDENTITY.room_workflow_id,
                        "workflowType": {"name": "RoomControlWorkflow"},
                        "taskQueue": {"name": "room-control"},
                    }
                },
            ),
            _event(
                4,
                "CHILD_WORKFLOW_EXECUTION_STARTED",
                {
                    "childWorkflowExecutionStartedEventAttributes": {
                        "workflowExecution": {
                            "workflowId": IDENTITY.room_workflow_id,
                            "runId": IDENTITY.room_run_id,
                        }
                    }
                },
            ),
            _event(
                5,
                "WORKFLOW_PROPERTIES_MODIFIED",
                {
                    "workflowPropertiesModifiedEventAttributes": {
                        "upsertedMemo": {
                            "fields": {
                                evidence.AUTHORITY_MEMO_KEY: {"data": "synthetic"}
                            }
                        }
                    }
                },
            ),
            _completed_update(6, IDENTITY.bootstrap_update_id),
            _accepted_update(7, IDENTITY.command_id, "acceptCommand"),
            _event(
                8,
                "SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED",
                {
                    "signalExternalWorkflowExecutionInitiatedEventAttributes": {
                        "workflowExecution": {
                            "workflowId": IDENTITY.room_workflow_id,
                            "runId": IDENTITY.room_run_id,
                        },
                        "signalName": "roomCommandAccepted",
                    }
                },
            ),
            _completed_update(9, IDENTITY.command_id),
        ]
    }


def _room_history() -> dict:
    return {
        "events": [
            _event(
                1,
                "WORKFLOW_EXECUTION_STARTED",
                {
                    "workflowExecutionStartedEventAttributes": {
                        "workflowType": {"name": "RoomControlWorkflow"},
                        "taskQueue": {"name": "room-control"},
                        "originalExecutionRunId": IDENTITY.room_run_id,
                        "parentWorkflowExecution": {
                            "workflowId": IDENTITY.case_workflow_id,
                            "runId": IDENTITY.case_run_id,
                        },
                    }
                },
            ),
            _event(
                2,
                "WORKFLOW_EXECUTION_SIGNALED",
                {
                    "workflowExecutionSignaledEventAttributes": {
                        "signalName": "roomCommandAccepted",
                        "externalWorkflowExecution": {
                            "workflowId": IDENTITY.case_workflow_id,
                            "runId": IDENTITY.case_run_id,
                        },
                    }
                },
            ),
        ]
    }


def _snapshot(phase: str, captured_at: str) -> dict:
    identity = IDENTITY.as_dict()
    if phase == "before":
        identity = {**identity, "case_run_id": None, "room_run_id": None}
    return {
        "schema_version": "mig-001-sql-snapshot.v1",
        "snapshot_phase": phase,
        "captured_at_utc": captured_at,
        "writer_mode": "SHADOW",
        "identity": identity,
        "assertions": {
            key: True for key in evidence.REQUIRED_SQL_ASSERTIONS[phase]
        },
    }


def _metadata(*, skip_approval: bool = False) -> list[str]:
    values = [
        "environment_id=synthetic-compose-mig001",
        "temporal_namespace=default",
        "control_build_id=build-123",
        f"java_image_digest=sha256:{'b' * 64}",
        "postgresql_version=16.4",
        "temporal_server_version=1.25.2",
        "kms_key_id=alias/mig001-evidence",
        "artifact_retention_days=30",
    ]
    if skip_approval:
        values.append("skip_approval=CHANGE-123")
    return values


def _inputs(tmp_path: Path, *, skipped: bool = False) -> dict:
    surefire = _write(
        tmp_path / "TEST-surefire.xml",
        _junit(sorted(evidence.REQUIRED_SUREFIRE_TEST_CLASSES), skipped=skipped),
    )
    failsafe = _write(
        tmp_path / "TEST-failsafe.xml",
        _junit(sorted(evidence.REQUIRED_FAILSAFE_TEST_CLASSES)),
    )
    case_history = _write(tmp_path / "case-history.json", json.dumps(_case_history()))
    room_history = _write(tmp_path / "room-history.json", json.dumps(_room_history()))
    before = _write(
        tmp_path / "sql-before.json",
        json.dumps(_snapshot("before", "2026-07-18T12:00:00Z")),
    )
    after = _write(
        tmp_path / "sql-after.json",
        json.dumps(_snapshot("after", "2026-07-18T12:01:00Z")),
    )
    return {
        "repository": tmp_path,
        "release_id": "release-2026.07.18",
        "evaluated_head": HEAD,
        "environment_name": "compose-mig-001",
        "identity": IDENTITY,
        "surefire_xml": [surefire],
        "failsafe_xml": [failsafe],
        "temporal_histories": [case_history, room_history],
        "sql_before": before,
        "sql_after": after,
        "metadata": _metadata(skip_approval=skipped),
    }


@pytest.fixture(autouse=True)
def clean_git(monkeypatch: pytest.MonkeyPatch) -> None:
    def output(repository: Path, *arguments: str) -> str:
        if arguments == ("rev-parse", "--show-toplevel"):
            return str(repository.resolve())
        if arguments == ("rev-parse", "HEAD"):
            return HEAD
        if arguments == ("status", "--porcelain=v1", "--untracked-files=all"):
            return ""
        raise AssertionError(arguments)

    monkeypatch.setattr(evidence, "_git_output", output)


def _collect(tmp_path: Path, **changes) -> evidence.EvidenceCollection:
    values = _inputs(tmp_path)
    values.update(changes)
    return evidence.collect_evidence(**values)


def _rewrite_json(path: Path, mutate) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    mutate(document)
    _write(path, json.dumps(document))


def test_collects_bound_shadow_technical_pass(tmp_path: Path) -> None:
    collection = _collect(tmp_path)
    document = collection.document

    assert document["technical_result"] == "PASS"
    assert document["promotion_status"] == "PENDING_APPROVAL"
    assert document["scenario"] == {
        "writer_mode": "SHADOW",
        "identity": IDENTITY.as_dict(),
    }
    assert document["tests"]["tests"] == (
        len(evidence.REQUIRED_SUREFIRE_TEST_CLASSES)
        + len(evidence.REQUIRED_FAILSAFE_TEST_CLASSES)
    )
    assert document["tests"]["coverage"]["surefire"]["required_classes"] == sorted(
        evidence.REQUIRED_SUREFIRE_TEST_CLASSES
    )
    assert document["tests"]["coverage"]["failsafe"]["required_classes"] == sorted(
        evidence.REQUIRED_FAILSAFE_TEST_CLASSES
    )
    assert {item["workflow_kind"] for item in document["temporal_histories"]} == {
        "CASE",
        "ROOM",
    }
    assert len(collection.artifacts) == 6


@pytest.mark.parametrize("group", ["surefire_xml", "failsafe_xml"])
def test_rejects_missing_required_test_class(tmp_path: Path, group: str) -> None:
    values = _inputs(tmp_path)
    required = (
        evidence.REQUIRED_SUREFIRE_TEST_CLASSES
        if group == "surefire_xml"
        else evidence.REQUIRED_FAILSAFE_TEST_CLASSES
    )
    _write(values[group][0], _junit(sorted(required)[1:]))

    with pytest.raises(evidence.EvidenceRejected, match="test classes did not run"):
        evidence.collect_evidence(**values)


def test_rejects_failure_and_unapproved_skip(tmp_path: Path) -> None:
    values = _inputs(tmp_path)
    _write(
        values["surefire_xml"][0],
        _junit(sorted(evidence.REQUIRED_SUREFIRE_TEST_CLASSES), failed=True),
    )
    with pytest.raises(evidence.EvidenceRejected, match="failures/errors"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path, skipped=True)
    with pytest.raises(evidence.EvidenceRejected, match="exceeds max_skips"):
        evidence.collect_evidence(**values)
    values["metadata"] = [
        item
        for item in values["metadata"]
        if not item.startswith("skip_approval=")
    ]
    with pytest.raises(evidence.EvidenceRejected, match="skip_approval"):
        evidence.collect_evidence(**values, max_skips=1)
    assert evidence.collect_evidence(**_inputs(tmp_path, skipped=True), max_skips=1)


def test_rejects_missing_or_wrong_history_identity(tmp_path: Path) -> None:
    values = _inputs(tmp_path)
    values["temporal_histories"] = values["temporal_histories"][:1]
    with pytest.raises(evidence.EvidenceRejected, match="at least two"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path)
    _rewrite_json(
        values["temporal_histories"][1],
        lambda document: document["events"][0][
            "workflowExecutionStartedEventAttributes"
        ].update(originalExecutionRunId="wrong-run"),
    )
    with pytest.raises(evidence.EvidenceRejected, match="run ID does not match"):
        evidence.collect_evidence(**values)


@pytest.mark.parametrize(
    ("event_type", "message"),
    [
        ("WORKFLOW_EXECUTION_UPDATE_COMPLETED", "accept/complete pair"),
        ("WORKFLOW_PROPERTIES_MODIFIED", "authority checkpoint memo"),
        ("CHILD_WORKFLOW_EXECUTION_STARTED", "lacks the bound Room child"),
        (
            "SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED",
            "bound Room command signal",
        ),
    ],
)
def test_rejects_case_history_missing_required_event(
    tmp_path: Path, event_type: str, message: str
) -> None:
    values = _inputs(tmp_path)

    def remove(document: dict) -> None:
        candidates = [
            event
            for event in document["events"]
            if event["eventType"] == f"EVENT_TYPE_{event_type}"
        ]
        document["events"].remove(candidates[0])

    _rewrite_json(values["temporal_histories"][0], remove)
    with pytest.raises(evidence.EvidenceRejected, match=message):
        evidence.collect_evidence(**values)


def test_rejects_wrong_update_id_and_room_signal(tmp_path: Path) -> None:
    values = _inputs(tmp_path)

    def wrong_update(document: dict) -> None:
        attributes = document["events"][1]["workflowExecutionUpdateAcceptedEventAttributes"]
        attributes["acceptedRequest"]["meta"]["updateId"] = "wrong-update"

    _rewrite_json(values["temporal_histories"][0], wrong_update)
    with pytest.raises(evidence.EvidenceRejected, match="accept/complete pair"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path)
    _rewrite_json(
        values["temporal_histories"][1],
        lambda document: document["events"][1][
            "workflowExecutionSignaledEventAttributes"
        ].update(signalName="wrongSignal"),
    )
    with pytest.raises(evidence.EvidenceRejected, match="roomCommandAccepted"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path)
    _rewrite_json(
        values["temporal_histories"][1],
        lambda document: document["events"][1][
            "workflowExecutionSignaledEventAttributes"
        ]["externalWorkflowExecution"].update(runId="wrong-case-run"),
    )
    with pytest.raises(evidence.EvidenceRejected, match="bound Case run"):
        evidence.collect_evidence(**values)


def test_rejects_history_events_outside_their_update(tmp_path: Path) -> None:
    values = _inputs(tmp_path)

    def move_command_signal_before_update(document: dict) -> None:
        signal = document["events"].pop(7)
        signal["eventId"] = "7"
        document["events"][6]["eventId"] = "8"
        document["events"].insert(6, signal)

    _rewrite_json(values["temporal_histories"][0], move_command_signal_before_update)
    with pytest.raises(evidence.EvidenceRejected, match="inside the acceptCommand update"):
        evidence.collect_evidence(**values)


def test_rejects_sql_tuple_mode_assertion_and_time_mismatch(tmp_path: Path) -> None:
    values = _inputs(tmp_path)
    _rewrite_json(
        values["sql_after"],
        lambda document: document["identity"].update(command_id="wrong-command"),
    )
    with pytest.raises(evidence.EvidenceRejected, match="command_id does not match"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path)
    _rewrite_json(
        values["sql_before"], lambda document: document.update(writer_mode="TEMPORAL")
    )
    with pytest.raises(evidence.EvidenceRejected, match="must be SHADOW"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path)
    _rewrite_json(
        values["sql_after"],
        lambda document: document["assertions"].update(command_shadow_completed=False),
    )
    with pytest.raises(evidence.EvidenceRejected, match="did not pass"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path)
    _rewrite_json(
        values["sql_after"],
        lambda document: document.update(captured_at_utc="2026-07-18T11:59:59Z"),
    )
    with pytest.raises(evidence.EvidenceRejected, match="predates"):
        evidence.collect_evidence(**values)


def test_metadata_is_allowlisted_and_complete(tmp_path: Path) -> None:
    values = _inputs(tmp_path)
    values["metadata"].append("database_password=secret")
    with pytest.raises(evidence.EvidenceRejected, match="not allowlisted"):
        evidence.collect_evidence(**values)

    values = _inputs(tmp_path)
    values["metadata"] = [
        item for item in values["metadata"] if not item.startswith("kms_key_id=")
    ]
    with pytest.raises(evidence.EvidenceRejected, match="required metadata"):
        evidence.collect_evidence(**values)


def test_rejects_dirty_repository_and_head_mismatch(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    def dirty(repository: Path, *arguments: str) -> str:
        if arguments == ("rev-parse", "--show-toplevel"):
            return str(repository.resolve())
        if arguments == ("rev-parse", "HEAD"):
            return HEAD
        return " M Changed.java"

    monkeypatch.setattr(evidence, "_git_output", dirty)
    with pytest.raises(evidence.EvidenceRejected, match="repository is dirty"):
        evidence.collect_evidence(**_inputs(tmp_path))

    def wrong_head(repository: Path, *arguments: str) -> str:
        if arguments == ("rev-parse", "--show-toplevel"):
            return str(repository.resolve())
        if arguments == ("rev-parse", "HEAD"):
            return "c" * 40
        return ""

    monkeypatch.setattr(evidence, "_git_output", wrong_head)
    with pytest.raises(evidence.EvidenceRejected, match="does not match"):
        evidence.collect_evidence(**_inputs(tmp_path))


def test_writes_deterministic_content_addressed_bundle(tmp_path: Path) -> None:
    collection = _collect(tmp_path)
    first = evidence.write_evidence_bundle(collection, tmp_path / "bundle-a")
    second = evidence.write_evidence_bundle(collection, tmp_path / "bundle-b")

    assert first.sha256 == second.sha256
    assert first.path.name == f"mig-001-sha256-{first.sha256}.zip"
    assert hashlib.sha256(first.path.read_bytes()).hexdigest() == first.sha256
    with zipfile.ZipFile(first.path) as archive:
        names = set(archive.namelist())
        assert "evidence.json" in names
        assert {artifact.bundle_path for artifact in collection.artifacts} <= names
        manifest = json.loads(archive.read("evidence.json"))
        assert manifest["promotion_status"] == "PENDING_APPROVAL"
    with pytest.raises(evidence.EvidenceRejected, match="already exists"):
        evidence.write_evidence_bundle(collection, tmp_path / "bundle-a")
