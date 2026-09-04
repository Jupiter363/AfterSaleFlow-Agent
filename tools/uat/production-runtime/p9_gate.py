from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ledger  # noqa: E402


P9_ENGINEERING_RESULT = "TARGET_ARCHITECTURE_PREPRODUCTION_E2E_PASS"
P9_EXTERNAL_CEILING = {
    "production_checkpoint": "PENDING_EXTERNAL",
    "promotion_gate": "PENDING",
    "migrations": "PENDING_PROMOTION",
    "next_permission": "SEPARATELY_AUTHORIZED_EXTERNAL_PRODUCTION_CHECKPOINT_ONLY",
}
REQUIRED_LEDGER_PAYLOADS = {
    "PROVISIONED_RUN_CONTEXT",
    "RUNTIME_MEASUREMENT",
    "TARGET_ACCEPTANCE_EVIDENCE",
    "FORENSIC_EXPORT",
}
REQUIRED_SCENARIO_ASSERTIONS = frozenset(
    {
        "DEFAULT_DISABLED_WITHOUT_MANIFEST",
        "IDENTICAL_REPLICA_ATTACH_AND_CONFLICTING_REPLAY_REJECTED",
        "BROWSER_ALL_ROOM_OUTCOME_CLOSURE_EVALUATION",
        "THREE_COMMIT_RECOVERY_WINDOWS_NO_DUPLICATE_EFFECT",
        "SSE_RECONNECT_AND_JAVA_PROJECTION_RELOAD",
        "DATABASE_TEMPORAL_GRAPH_SECURITY_ASSERTIONS",
        "DRAIN_ONLY_PREEXPIRY_ONLY_THEN_REVOKED_TERMINAL",
        "ZERO_UNRESOLVED_LEDGER_OUTBOX_LEASE_ATTEMPT_OR_FINALIZATION",
    }
)
P9_EVIDENCE_FIELDS = {
    "schema_version",
    "status",
    "run_id",
    "candidate_sha",
    "activation_id",
    "environment_generation",
    "case_id",
    "image_lock_hash",
    "target_assertion_hash",
    "forensic_manifest_hash",
    "batch_4_scenario_hash",
    "ledger_head_hash",
    "recorded_at",
    "engineering_checkpoint",
    *P9_EXTERNAL_CEILING,
    "self_hash",
    "attestation",
}


def validate_scenario_receipt(
    receipt: dict[str, Any],
    *,
    env: dict[str, str],
    lock: dict[str, Any],
    case_id: str,
) -> None:
    expected_fields = {
        "schema_version",
        "status",
        "run_id",
        "candidate_sha",
        "activation_id",
        "environment_generation",
        "case_id",
        "assertions",
        "engineering_checkpoint",
        *P9_EXTERNAL_CEILING,
        "self_hash",
    }
    common.verify_self_hash(receipt, "Batch 4 scenario receipt")
    if set(receipt) != expected_fields:
        raise common.ProductionError("Batch 4 scenario receipt fields drifted")
    run_context = common.load_json(Path(env["PRODUCTION_RUNTIME_RUN_CONTEXT_PATH"]))
    projection = run_context["runtime_projection"]
    expected = (
        "production-runtime-batch-4-scenario.v1",
        "PASS",
        lock["run_id"],
        lock["candidate_sha"],
        projection["activationId"],
        projection["environmentGeneration"],
        case_id,
        P9_ENGINEERING_RESULT,
    )
    actual = tuple(
        receipt[key]
        for key in (
            "schema_version",
            "status",
            "run_id",
            "candidate_sha",
            "activation_id",
            "environment_generation",
            "case_id",
            "engineering_checkpoint",
        )
    )
    assertions = receipt["assertions"]
    if (
        actual != expected
        or not isinstance(assertions, dict)
        or set(assertions) != REQUIRED_SCENARIO_ASSERTIONS
        or any(value != "PASS" for value in assertions.values())
        or any(receipt[key] != value for key, value in P9_EXTERNAL_CEILING.items())
    ):
        raise common.ProductionError("Batch 4 scenario receipt did not prove every hard gate")


def _verified_run_material(
    env_file: Path,
) -> tuple[dict[str, str], dict[str, Any], Any, list[dict[str, Any]]]:
    env, lock = common.validate_env_lock(env_file)
    run_context = common.load_json(Path(env["PRODUCTION_RUNTIME_RUN_CONTEXT_PATH"]))
    context = common.validate_run_context_bindings(run_context, env, lock)
    harness_public = ledger.load_public_key(
        Path(env["PRODUCTION_RUNTIME_PUBLIC_DIR"]) / "harness" / "harness.public.pem"
    )
    records = ledger.verify_ledger(
        Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"]) / "ledger.jsonl",
        harness_public,
        expected_public_key_sha256=lock["ledger_public_key_sha256"],
        expected_context=context,
        require_fresh_last=True,
    )
    return env, lock, harness_public, records


def create_evidence(env_file: Path, case_id: str) -> dict[str, Any]:
    env, lock, harness_public, records = _verified_run_material(env_file)
    evidence_dir = Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"])
    assertion = common.load_json(evidence_dir / "target-assertion.json")
    forensic = common.load_json(evidence_dir / "forensic-manifest.json")
    scenario = common.load_json(evidence_dir / "batch-4-scenario.json")
    validate_scenario_receipt(scenario, env=env, lock=lock, case_id=case_id)
    for document, context_name in (
        (assertion, "target assertion"),
        (forensic, "forensic manifest"),
    ):
        ledger.verify_attested_document(
            document,
            harness_public,
            expected_key_sha256=lock["ledger_public_key_sha256"],
            context=context_name,
        )
    projection = common.load_json(Path(env["PRODUCTION_RUNTIME_RUN_CONTEXT_PATH"]))[
        "runtime_projection"
    ]
    payload_types = {record["payload_type"] for record in records}
    if not REQUIRED_LEDGER_PAYLOADS.issubset(payload_types):
        raise common.ProductionError("P9.0 evidence ledger is incomplete")
    if (
        assertion.get("schema_version") != "production-runtime-assertion-result.v2"
        or assertion.get("status") != "PASS"
        or assertion.get("case_id") != case_id
        or assertion.get("candidate_sha") != lock["candidate_sha"]
        or assertion.get("activation_id") != projection["activationId"]
    ):
        raise common.ProductionError("target assertion cannot support P9.0 evidence")
    if (
        forensic.get("schema_version") != "production-runtime-forensic-manifest.v2"
        or forensic.get("candidate_sha") != lock["candidate_sha"]
        or forensic.get("network_isolation_status") != "PASS"
        or forensic.get("locked_images_match") is not True
    ):
        raise common.ProductionError("forensic manifest cannot support P9.0 evidence")
    expected_scenario = (
        "production-runtime-batch-4-scenario.v1",
        "PASS",
        lock["run_id"],
        lock["candidate_sha"],
        projection["activationId"],
        projection["environmentGeneration"],
        case_id,
    )
    actual_scenario = tuple(
        scenario.get(key)
        for key in (
            "schema_version",
            "status",
            "run_id",
            "candidate_sha",
            "activation_id",
            "environment_generation",
            "case_id",
        )
    )
    if actual_scenario != expected_scenario:
        raise common.ProductionError("Batch 4 scenario receipt binding is invalid")
    if scenario.get("engineering_checkpoint") != P9_ENGINEERING_RESULT:
        raise common.ProductionError("Batch 4 did not close the engineering checkpoint")
    if any(scenario.get(key) != value for key, value in P9_EXTERNAL_CEILING.items()):
        raise common.ProductionError("Batch 4 receipt exceeds the P9.0 authority ceiling")
    harness_private = ledger.load_private_key(
        Path(env["PRODUCTION_RUNTIME_SECRETS_DIR"])
        / "harness-attestation"
        / "harness.private.pem"
    )
    document = ledger.attest_document(
        {
            "schema_version": "phase-9-p9.0-evidence.v1",
            "status": "PASS_AWAITING_ACCEPTANCE",
            "run_id": lock["run_id"],
            "candidate_sha": lock["candidate_sha"],
            "activation_id": projection["activationId"],
            "environment_generation": projection["environmentGeneration"],
            "case_id": case_id,
            "image_lock_hash": lock["image_lock_hash"],
            "target_assertion_hash": assertion["self_hash"],
            "forensic_manifest_hash": forensic["self_hash"],
            "batch_4_scenario_hash": scenario["self_hash"],
            "ledger_head_hash": records[-1]["record_hash"],
            "recorded_at": common.utc_now().isoformat(timespec="milliseconds"),
            "engineering_checkpoint": P9_ENGINEERING_RESULT,
            **P9_EXTERNAL_CEILING,
        },
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
    )
    common.atomic_create_json(evidence_dir / "p9.0-evidence.json", document)
    return document


def accept_evidence(
    env_file: Path,
    *,
    acceptance_private_key: Path,
    acceptance_public_key: Path,
    acceptance_key_id: str,
) -> dict[str, Any]:
    env, lock, harness_public, _records = _verified_run_material(env_file)
    evidence_dir = Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"])
    evidence = common.load_json(evidence_dir / "p9.0-evidence.json")
    ledger.verify_attested_document(
        evidence,
        harness_public,
        expected_key_sha256=lock["ledger_public_key_sha256"],
        context="P9.0 evidence",
    )
    projection = common.load_json(Path(env["PRODUCTION_RUNTIME_RUN_CONTEXT_PATH"]))[
        "runtime_projection"
    ]
    expected_binding = (
        lock["run_id"],
        lock["candidate_sha"],
        projection["activationId"],
        projection["environmentGeneration"],
        lock["image_lock_hash"],
    )
    actual_binding = tuple(
        evidence.get(key)
        for key in (
            "run_id",
            "candidate_sha",
            "activation_id",
            "environment_generation",
            "image_lock_hash",
        )
    )
    if (
        set(evidence) != P9_EVIDENCE_FIELDS
        or actual_binding != expected_binding
        or evidence.get("schema_version") != "phase-9-p9.0-evidence.v1"
        or evidence.get("status") != "PASS_AWAITING_ACCEPTANCE"
        or evidence.get("engineering_checkpoint") != P9_ENGINEERING_RESULT
        or any(evidence.get(key) != value for key, value in P9_EXTERNAL_CEILING.items())
    ):
        raise common.ProductionError("P9.0 evidence is not acceptance eligible")
    if common.TOKEN.fullmatch(acceptance_key_id) is None:
        raise common.ProductionError("P9.0 acceptance key ID is invalid")
    private_key = ledger.load_private_key(acceptance_private_key)
    public_key = ledger.load_public_key(acceptance_public_key)
    fingerprint = ledger.public_key_sha256(public_key)
    if fingerprint == lock["ledger_public_key_sha256"]:
        raise common.ProductionError("P9.0 acceptance must use an independent key")
    acceptance = ledger.attest_document(
        {
            "schema_version": "phase-9-p9.0-acceptance.v1",
            "status": "PASS",
            "p9_0": "PASS",
            "run_id": evidence["run_id"],
            "candidate_sha": evidence["candidate_sha"],
            "activation_id": evidence["activation_id"],
            "environment_generation": evidence["environment_generation"],
            "case_id": evidence["case_id"],
            "evidence_hash": evidence["self_hash"],
            "accepted_at": common.utc_now().isoformat(timespec="milliseconds"),
            "engineering_checkpoint": P9_ENGINEERING_RESULT,
            **P9_EXTERNAL_CEILING,
        },
        private_key,
        public_key,
        key_id=acceptance_key_id,
    )
    ledger.verify_attested_document(
        acceptance,
        public_key,
        expected_key_sha256=fingerprint,
        context="P9.0 acceptance",
    )
    common.atomic_create_json(evidence_dir / "p9.0-acceptance.json", acceptance)
    return acceptance


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)
    evidence = subcommands.add_parser("evidence")
    evidence.add_argument("--env-file", type=Path, required=True)
    evidence.add_argument("--case-id", required=True)
    accept = subcommands.add_parser("accept")
    accept.add_argument("--env-file", type=Path, required=True)
    accept.add_argument("--acceptance-private-key", type=Path, required=True)
    accept.add_argument("--acceptance-public-key", type=Path, required=True)
    accept.add_argument("--acceptance-key-id", required=True)
    args = parser.parse_args(argv)
    try:
        document = (
            create_evidence(args.env_file, args.case_id)
            if args.command == "evidence"
            else accept_evidence(
                args.env_file,
                acceptance_private_key=args.acceptance_private_key,
                acceptance_public_key=args.acceptance_public_key,
                acceptance_key_id=args.acceptance_key_id,
            )
        )
    except (common.ProductionError, OSError, json.JSONDecodeError, ValueError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(document, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
