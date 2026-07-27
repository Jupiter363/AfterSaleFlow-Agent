from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path
from typing import Any
from urllib.error import URLError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


ROOMS = ("INTAKE", "EVIDENCE", "HEARING", "REVIEW")
ROOM_KEYS = {
    "room_type",
    "allocation",
    "protocol",
    "execution_engine",
    "worker_lane",
    "temporal_run_id",
    "graph_checkpoint_id",
    "graph_checkpoint_hash",
    "graph_result_hash",
    "java_final_receipt_id",
    "java_final_receipt_hash",
    "completed_at",
}
TOP_LEVEL_KEYS = {
    "schema_version",
    "run_id",
    "build_id",
    "case_id",
    "inventory_complete",
    "legacy_run_count",
    "runs",
    "rooms",
    "activation_receipt",
}
RUN_KEYS = {
    "run_id",
    "room_type",
    "allocation",
    "protocol",
    "execution_engine",
    "worker_lane",
}


def _required_token(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 256:
        raise common.TargetE2EError(f"{context} must be a bounded non-empty token")
    return value


def _timestamp(value: Any, context: str) -> dt.datetime:
    try:
        parsed = dt.datetime.fromisoformat(_required_token(value, context))
    except ValueError as error:
        raise common.TargetE2EError(f"{context} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise common.TargetE2EError(f"{context} must include a timezone")
    return parsed


def validate_target_evidence(
    document: dict[str, Any],
    expected_run_id: str,
    expected_build_id: str,
    expected_registry_hash: str,
) -> dict[str, Any]:
    if set(document) != TOP_LEVEL_KEYS:
        raise common.TargetE2EError("application evidence fields drifted or are incomplete")
    if document["schema_version"] != "target-architecture-e2e-evidence.v1":
        raise common.TargetE2EError("application evidence schema is unavailable or unsupported")
    if document["run_id"] != expected_run_id or document["build_id"] != expected_build_id:
        raise common.TargetE2EError("application evidence is for a different run or build")
    _required_token(document["case_id"], "case_id")
    if document["inventory_complete"] is not True:
        raise common.TargetE2EError("run inventory is not complete; zero LEGACY cannot be proven")
    if type(document["legacy_run_count"]) is not int or document["legacy_run_count"] != 0:
        raise common.TargetE2EError("LEGACY AgentRun inventory is nonzero or unproven")

    runs = document["runs"]
    if not isinstance(runs, list) or not runs:
        raise common.TargetE2EError("complete AgentRun inventory must be non-empty")
    inventory: dict[str, str] = {}
    for index, run in enumerate(runs):
        if not isinstance(run, dict) or set(run) != RUN_KEYS:
            raise common.TargetE2EError("AgentRun inventory entry is malformed or non-strict")
        if run.get("room_type") not in ROOMS:
            raise common.TargetE2EError(f"AgentRun {index} has an unknown room")
        if run.get("allocation") != "TEMPORAL":
            raise common.TargetE2EError(f"AgentRun {index} was not TEMPORAL")
        if run.get("protocol") != "V2" or run.get("execution_engine") != "TEMPORAL_ACTIVITY":
            raise common.TargetE2EError(f"AgentRun {index} did not execute V2 as a Temporal activity")
        if run.get("worker_lane") != "candidate":
            raise common.TargetE2EError(f"AgentRun {index} escaped the candidate worker lane")
        run_id = _required_token(run.get("run_id"), f"runs[{index}].run_id")
        inventory[run_id] = run["room_type"]
    if len(inventory) != len(runs):
        raise common.TargetE2EError("complete AgentRun inventory contains duplicate run IDs")

    rooms = document["rooms"]
    if not isinstance(rooms, list) or [room.get("room_type") for room in rooms if isinstance(room, dict)] != list(ROOMS):
        raise common.TargetE2EError("evidence must show Intake followed by every room in canonical order")
    completion_times: list[dt.datetime] = []
    for index, room in enumerate(rooms):
        if not isinstance(room, dict) or set(room) != ROOM_KEYS:
            raise common.TargetE2EError(f"room evidence {index} is incomplete or contains unknown claims")
        if room["allocation"] != "TEMPORAL":
            raise common.TargetE2EError(f"{room['room_type']} allocation is not TEMPORAL")
        if room["protocol"] != "V2" or room["execution_engine"] != "TEMPORAL_ACTIVITY":
            raise common.TargetE2EError(f"{room['room_type']} did not run V2/TEMPORAL_ACTIVITY")
        if room["worker_lane"] != "candidate":
            raise common.TargetE2EError(f"{room['room_type']} did not run on the candidate lane")
        for key in ("graph_checkpoint_hash", "graph_result_hash", "java_final_receipt_hash"):
            value = room[key]
            if not isinstance(value, str) or not common.SHA256.fullmatch(value):
                raise common.TargetE2EError(f"{room['room_type']} is missing a proven {key}")
        for key in ("temporal_run_id", "graph_checkpoint_id", "java_final_receipt_id"):
            _required_token(room[key], f"{room['room_type']}.{key}")
        if inventory.get(room["temporal_run_id"]) != room["room_type"]:
            raise common.TargetE2EError(f"{room['room_type']} receipt is not linked to complete run inventory")
        completion_times.append(_timestamp(room["completed_at"], f"{room['room_type']}.completed_at"))
    if completion_times != sorted(completion_times) or len(set(completion_times)) != len(completion_times):
        raise common.TargetE2EError("room completion order does not prove Intake then all later rooms")

    activation = document["activation_receipt"]
    if not isinstance(activation, dict) or set(activation) != {
        "activation_id",
        "consumed",
        "consumed_at",
        "registry_hash",
    }:
        raise common.TargetE2EError("short-lived activation receipt is absent or malformed")
    if activation["consumed"] is not True:
        raise common.TargetE2EError("short-lived activation input was not consumed")
    activation_consumed_at = _timestamp(
        activation["consumed_at"], "activation_receipt.consumed_at"
    )
    if activation_consumed_at > completion_times[0]:
        raise common.TargetE2EError("activation was not consumed before Intake completed")
    registry_hash = activation["registry_hash"]
    if not isinstance(registry_hash, str) or not common.SHA256.fullmatch(registry_hash):
        raise common.TargetE2EError("activation registry hash is invalid")
    if registry_hash != expected_registry_hash:
        raise common.TargetE2EError("activation receipt is not bound to the provisioned registry")

    return {
        "schema_version": "target-e2e-assertion-receipt.v1",
        "status": "PASS",
        "run_id": expected_run_id,
        "build_id": expected_build_id,
        "case_id": document["case_id"],
        "rooms": list(ROOMS),
        "legacy_run_count": 0,
        "evidence_sha256": common.canonical_sha256(document),
    }


def _load_source(source: str) -> dict[str, Any]:
    if source.startswith(("http://", "https://")):
        request = Request(source, headers={"Accept": "application/json"})
        try:
            with urlopen(request, timeout=15) as response:
                if response.status != 200:
                    raise common.TargetE2EError(f"evidence endpoint returned HTTP {response.status}")
                payload = response.read()
            value = json.loads(payload)
        except (OSError, URLError, json.JSONDecodeError) as error:
            raise common.TargetE2EError(
                "BLOCKING_APPLICATION_CONTRACT: target evidence endpoint is unavailable"
            ) from error
        if not isinstance(value, dict):
            raise common.TargetE2EError("target evidence endpoint returned a non-object")
        return value
    return common.load_json(Path(source))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument(
        "--source",
        required=True,
        help="Strict evidence JSON file or an application-owned target evidence endpoint",
    )
    args = parser.parse_args(argv)
    env = common.parse_env_file(args.env_file)
    evidence_dir = common.assert_external_runtime_path(Path(env["TARGET_E2E_EVIDENCE_DIR"]))
    try:
        document = _load_source(args.source)
        receipt = validate_target_evidence(
            document,
            env["TARGET_E2E_RUN_ID"],
            env["TARGET_E2E_BUILD_ID"],
            env["TARGET_E2E_GRAPH_REGISTRY_HASH"],
        )
    except common.TargetE2EError as error:
        failure = {
            "schema_version": "target-e2e-assertion-receipt.v1",
            "status": "BLOCKED",
            "run_id": env.get("TARGET_E2E_RUN_ID"),
            "build_id": env.get("TARGET_E2E_BUILD_ID"),
            "blocking_check": str(error),
        }
        common.write_json(evidence_dir / "target-assertion.json", failure)
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    common.write_json(evidence_dir / "target-assertion.json", receipt)
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
