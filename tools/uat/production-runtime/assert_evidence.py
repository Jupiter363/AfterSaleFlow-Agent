from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ledger  # noqa: E402
import readiness  # noqa: E402


ROOMS = ("INTAKE", "EVIDENCE", "HEARING", "REVIEW")
CASE_ID = re.compile(r"^CASE_P9_SYNTHETIC_[A-Z0-9_]{1,32}$")
TOP_LEVEL_KEYS = {
    "schema_version",
    "candidate_sha",
    "activation_id",
    "environment_generation",
    "compose_project",
    "temporal_namespace",
    "database_identities",
    "case_id",
    "run_nonce",
    "run_context_hash",
    "runtime_measurement_hash",
    "inventory_complete",
    "legacy_run_count",
    "shadow_run_count",
    "infra_only",
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
    "execution_lane",
    "shadow",
}
ROOM_KEYS = {
    "room_type",
    "allocation",
    "protocol",
    "execution_engine",
    "execution_lane",
    "temporal_run_id",
    "room_fencing_token",
    "graph_checkpoint_id",
    "graph_checkpoint_hash",
    "graph_result_hash",
    "proposal_hash",
    "result_envelope_hash",
    "graph_output_authority",
    "agent_run_manifest_hash",
    "isolated_domain_db_binding_hash",
    "java_final_receipt_id",
    "java_final_receipt_hash",
    "java_writer",
    "domain_commit_status",
    "completed_at",
}
ACTIVATION_RECEIPT_KEYS = {
    "activation_id",
    "state",
    "consumed_at",
    "manifest_hash",
}
JWS_PAYLOAD_KEYS = {
    "iss",
    "aud",
    "iat",
    "exp",
    "jti",
    "schema_version",
    "evidence",
}


def _required_token(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 256:
        raise common.ProductionError(f"{context} must be a bounded non-empty token")
    return value


def _timestamp(value: Any, context: str) -> dt.datetime:
    return common.parse_timestamp(_required_token(value, context), context)


def _require_sha256(value: Any, context: str) -> str:
    if not isinstance(value, str) or not common.SHA256.fullmatch(value):
        raise common.ProductionError(f"{context} must be a lowercase SHA-256")
    return value


def _validate_evidence(
    document: dict[str, Any],
    *,
    run_context: dict[str, Any],
    runtime_measurement_hash: str,
    case_id: str,
) -> dict[str, Any]:
    if set(document) != TOP_LEVEL_KEYS:
        raise common.ProductionError("Java evidence fields drifted or are incomplete")
    if document["schema_version"] != "target-architecture-e2e-evidence.v2":
        raise common.ProductionError(
            "Java evidence schema is unavailable or unsupported"
        )
    projection = run_context["runtime_projection"]
    expected = {
        "candidate_sha": projection["candidateSha"],
        "activation_id": projection["activationId"],
        "environment_generation": projection["environmentGeneration"],
        "compose_project": projection["composeProject"],
        "temporal_namespace": projection["temporalNamespace"],
        "database_identities": projection["databaseIdentities"],
        "case_id": case_id,
        "run_nonce": projection["runNonce"],
        "run_context_hash": run_context["self_hash"],
        "runtime_measurement_hash": runtime_measurement_hash,
    }
    for key, value in expected.items():
        if document.get(key) != value:
            raise common.ProductionError(f"Java evidence binding mismatch: {key}")
    if not case_id.startswith(projection["caseScope"]["caseIdPrefix"]):
        raise common.ProductionError("case ID is outside the activation case scope")
    if document["inventory_complete"] is not True:
        raise common.ProductionError(
            "run inventory is not complete; zero legacy cannot be proven"
        )
    if (
        type(document["legacy_run_count"]) is not int
        or document["legacy_run_count"] != 0
    ):
        raise common.ProductionError("legacy AgentRun inventory is nonzero or unproven")
    if (
        type(document["shadow_run_count"]) is not int
        or document["shadow_run_count"] != 0
    ):
        raise common.ProductionError(
            "SHADOW runs cannot prove the production runtime lane"
        )
    if document["infra_only"] is not False:
        raise common.ProductionError(
            "infrastructure-only evidence cannot pass target acceptance"
        )

    runs = document["runs"]
    if not isinstance(runs, list) or not runs:
        raise common.ProductionError("complete AgentRun inventory must be non-empty")
    inventory: dict[str, str] = {}
    for index, run in enumerate(runs):
        if not isinstance(run, dict) or set(run) != RUN_KEYS:
            raise common.ProductionError(
                "AgentRun inventory entry is malformed or non-strict"
            )
        if run["room_type"] not in ROOMS:
            raise common.ProductionError(f"AgentRun {index} has an unknown room")
        if run["allocation"] != "TEMPORAL":
            raise common.ProductionError(f"AgentRun {index} was not TEMPORAL")
        if run["protocol"] != "V3" or run["execution_engine"] != "TEMPORAL_ACTIVITY":
            raise common.ProductionError(
                f"AgentRun {index} did not execute V2 as a Temporal activity"
            )
        if (
            run["execution_lane"] != "PRODUCTION"
            or run["shadow"] is not False
        ):
            raise common.ProductionError(
                f"AgentRun {index} escaped or shadowed the candidate lane"
            )
        run_id = _required_token(run["run_id"], f"runs[{index}].run_id")
        inventory[run_id] = run["room_type"]
    if len(inventory) != len(runs):
        raise common.ProductionError(
            "complete AgentRun inventory contains duplicate run IDs"
        )

    rooms = document["rooms"]
    if not isinstance(rooms, list) or [
        room.get("room_type") for room in rooms if isinstance(room, dict)
    ] != list(ROOMS):
        raise common.ProductionError(
            "evidence must show Intake followed by every room in canonical order"
        )
    completion_times: list[dt.datetime] = []
    for index, room in enumerate(rooms):
        if not isinstance(room, dict) or set(room) != ROOM_KEYS:
            raise common.ProductionError(
                f"room evidence {index} is incomplete or contains unknown claims"
            )
        room_type = room["room_type"]
        if room["allocation"] != "TEMPORAL":
            raise common.ProductionError(f"{room_type} allocation is not TEMPORAL")
        if room["protocol"] != "V3" or room["execution_engine"] != "TEMPORAL_ACTIVITY":
            raise common.ProductionError(f"{room_type} did not run V2/TEMPORAL_ACTIVITY")
        if room["execution_lane"] != "PRODUCTION":
            raise common.ProductionError(
                f"{room_type} did not run on the candidate lane"
            )
        if room["graph_output_authority"] != "PROPOSAL_ONLY":
            raise common.ProductionError(
                f"{room_type} Graph output claimed formal authority"
            )
        if (
            room["java_writer"] != "JAVA_FINALIZER_ONLY"
            or room["domain_commit_status"] != "COMMITTED"
        ):
            raise common.ProductionError(
                f"{room_type} lacks the Java-only committed receipt"
            )
        if (
            type(room["room_fencing_token"]) is not int
            or room["room_fencing_token"] < 1
        ):
            raise common.ProductionError(f"{room_type} room fencing token is invalid")
        for key in (
            "graph_checkpoint_hash",
            "graph_result_hash",
            "proposal_hash",
            "result_envelope_hash",
            "agent_run_manifest_hash",
            "isolated_domain_db_binding_hash",
            "java_final_receipt_hash",
        ):
            _require_sha256(room[key], f"{room_type}.{key}")
        for key in (
            "temporal_run_id",
            "graph_checkpoint_id",
            "java_final_receipt_id",
        ):
            _required_token(room[key], f"{room_type}.{key}")
        if inventory.get(room["temporal_run_id"]) != room_type:
            raise common.ProductionError(
                f"{room_type} receipt is not linked to the complete run inventory"
            )
        completion_times.append(
            _timestamp(room["completed_at"], f"{room_type}.completed_at")
        )
    if completion_times != sorted(completion_times) or len(
        set(completion_times)
    ) != len(completion_times):
        raise common.ProductionError(
            "room completion order does not prove Intake then all later rooms"
        )

    activation = document["activation_receipt"]
    if not isinstance(activation, dict) or set(activation) != ACTIVATION_RECEIPT_KEYS:
        raise common.ProductionError("Java activation receipt is absent or malformed")
    if (
        activation["activation_id"] != projection["activationId"]
        or activation["state"] != "ACTIVE"
    ):
        raise common.ProductionError(
            "Java activation receipt is not the active run grant"
        )
    consumed = _timestamp(activation["consumed_at"], "activation_receipt.consumed_at")
    if consumed > completion_times[0]:
        raise common.ProductionError(
            "activation was not consumed before Intake completed"
        )
    if (
        _require_sha256(
            activation["manifest_hash"], "activation_receipt.manifest_hash"
        )
        != run_context["activation_manifest_hash"]
    ):
        raise common.ProductionError(
            "Java activation receipt does not match the provisioned activation manifest"
        )

    return {
        "schema_version": "production-runtime-assertion-result.v2",
        "status": "PASS",
        "run_id": projection["composeProject"].removeprefix("aflow-production-runtime-"),
        "candidate_sha": projection["candidateSha"],
        "activation_id": projection["activationId"],
        "case_id": case_id,
        "rooms": list(ROOMS),
        "legacy_run_count": 0,
        "shadow_run_count": 0,
        "runtime_measurement_hash": runtime_measurement_hash,
        "java_evidence_hash": common.canonical_sha256(document),
    }


def verify_java_attestation(
    compact: str,
    *,
    run_context: dict[str, Any],
    runtime_measurement_hash: str,
    case_id: str,
    trusted_public_keys: dict[str, Any],
    now: dt.datetime | None = None,
) -> tuple[dict[str, Any], str, dict[str, Any]]:
    payload, key_id = ledger.verify_compact_jws(
        compact,
        trusted_public_keys,
        expected_typ="production-runtime-final-evidence+jwt",
        expected_issuer="java-finalizer",
        expected_audience="production-runtime-evidence-harness",
        now=now,
    )
    if set(payload) != JWS_PAYLOAD_KEYS:
        raise common.ProductionError("Java evidence JWS claim set drifted")
    if payload["schema_version"] != "production-runtime-java-evidence-attestation.v1":
        raise common.ProductionError("Java evidence JWS schema is unsupported")
    projection = run_context["runtime_projection"]
    if payload["jti"] != f"{projection['runNonce']}:{case_id}":
        raise common.ProductionError("Java evidence JWS nonce/case identity is invalid")
    evidence = payload["evidence"]
    if not isinstance(evidence, dict):
        raise common.ProductionError("Java evidence JWS contains no evidence object")
    result = _validate_evidence(
        evidence,
        run_context=run_context,
        runtime_measurement_hash=runtime_measurement_hash,
        case_id=case_id,
    )
    return result, key_id, evidence


def _fixed_attestation_path(evidence_dir: Path, case_id: str) -> Path:
    if not CASE_ID.fullmatch(case_id):
        raise common.ProductionError("case ID is not a bounded target synthetic case")
    path = evidence_dir / "inbox" / f"{case_id}.java-evidence.jws"
    if path.parent.resolve() != (evidence_dir / "inbox").resolve():
        raise common.ProductionError("Java attestation path escapes the run-local inbox")
    common.assert_regular_single_link(path, "Java evidence attestation")
    return path


def assert_run(env_file: Path, case_id: str) -> dict[str, Any]:
    env, lock = common.validate_env_lock(env_file)
    evidence_dir = Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"])
    run_context = common.load_json(Path(env["PRODUCTION_RUNTIME_RUN_CONTEXT_PATH"]))
    context = common.validate_run_context_bindings(run_context, env, lock)
    harness_public = ledger.load_public_key(
        Path(env["PRODUCTION_RUNTIME_PUBLIC_DIR"]) / "harness" / "harness.public.pem"
    )
    records = ledger.verify_ledger(
        evidence_dir / "ledger.jsonl",
        harness_public,
        expected_public_key_sha256=lock["ledger_public_key_sha256"],
        expected_context=context,
    )
    if not any(record["payload_type"] == "RUNTIME_MEASUREMENT" for record in records):
        raise common.ProductionError(
            "a direct harness runtime measurement is required before assertion"
        )

    measurement = readiness.collect_runtime_measurement(env_file)
    measurement["run_context_hash"] = context["run_context_hash"]
    runtime_measurement_hash = common.canonical_sha256(measurement)
    attestation_path = _fixed_attestation_path(evidence_dir, case_id)
    compact = attestation_path.read_text(encoding="ascii").strip()
    trusted_keys: dict[str, Any] = {}
    for key_id in run_context["runtime_projection"]["trustedSigningKeyIds"]:
        key_path = (
            Path(env["PRODUCTION_RUNTIME_PUBLIC_DIR"]) / "graph-keys" / f"{key_id}.public.pem"
        )
        trusted_keys[key_id] = ledger.load_public_key(key_path)
    result, java_key_id, java_evidence = verify_java_attestation(
        compact,
        run_context=run_context,
        runtime_measurement_hash=runtime_measurement_hash,
        case_id=case_id,
        trusted_public_keys=trusted_keys,
    )

    harness_private = ledger.load_private_key(
        Path(env["PRODUCTION_RUNTIME_SECRETS_DIR"])
        / "harness-attestation"
        / "harness.private.pem"
    )
    record = ledger.append_record(
        evidence_dir / "ledger.jsonl",
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
        context=context,
        source_kind="JAVA_SIGNED",
        source_identity=f"java-finalizer:{java_key_id}",
        case_id=case_id,
        payload_type="TARGET_ACCEPTANCE_EVIDENCE",
        payload={
            "compact_jws": compact,
            "compact_jws_sha256": hashlib.sha256(compact.encode("ascii")).hexdigest(),
            "java_signing_key_id": java_key_id,
            "runtime_measurement": measurement,
            "evidence": java_evidence,
            "assertion": result,
        },
    )
    ledger.verify_ledger(
        evidence_dir / "ledger.jsonl",
        harness_public,
        expected_public_key_sha256=lock["ledger_public_key_sha256"],
        expected_context=context,
        require_fresh_last=True,
    )
    receipt = ledger.attest_document(
        {**result, "ledger_record_hash": record["record_hash"]},
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
    )
    common.write_json(evidence_dir / "target-assertion.json", receipt)
    return receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--case-id", required=True)
    args = parser.parse_args(argv)
    try:
        receipt = assert_run(args.env_file, args.case_id)
    except (common.ProductionError, OSError, json.JSONDecodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
