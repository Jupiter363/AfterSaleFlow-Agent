from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import preflight  # noqa: E402


REQUIRED_HEALTHY = (
    "domain-db",
    "graph-db",
    "temporal-db",
    "redis",
    "minio",
    "elasticsearch",
    "temporal-server",
    "jwks-publisher",
    "python-agent-service",
    "graph-mtls-proxy",
    "ocr-parser-service",
    "java-api-service",
    "java-control-worker",
    "java-agent-worker",
    "frontend",
    "gateway",
)
REQUIRED_COMPLETED = (
    "minio-init",
    "elasticsearch-init",
    "temporal-namespace-init",
    "graph-migrate",
    "graph-restore-validation",
)


def _inspect(env_file: Path, service: str) -> dict[str, Any]:
    identifier = common.run_command(
        common.compose_argv(env_file, "ps", "--all", "--quiet", service)
    ).stdout.strip()
    if not identifier or "\n" in identifier:
        raise common.TargetE2EError(f"expected exactly one {service} container")
    document = json.loads(common.run_command(["docker", "inspect", identifier]).stdout)
    return document[0]


def check_readiness(env_file: Path) -> dict[str, Any]:
    preflight.run_preflight(env_file)
    env = common.parse_env_file(env_file)
    states: dict[str, str] = {}
    for service in REQUIRED_HEALTHY:
        inspected = _inspect(env_file, service)
        state = inspected["State"]
        health = state.get("Health", {}).get("Status")
        if state.get("Status") != "running" or health != "healthy":
            raise common.TargetE2EError(f"{service} is not healthy: {state.get('Status')}/{health}")
        states[service] = "healthy"
    for service in REQUIRED_COMPLETED:
        inspected = _inspect(env_file, service)
        state = inspected["State"]
        if state.get("Status") != "exited" or state.get("ExitCode") != 0:
            raise common.TargetE2EError(f"{service} did not complete successfully")
        states[service] = "completed"

    proof = common.run_command(
        common.compose_argv(env_file, "run", "--rm", "mtls-proof", profile="evidence"),
        timeout=120,
    )
    try:
        graph_readiness = json.loads(proof.stdout)
    except json.JSONDecodeError as error:
        raise common.TargetE2EError("mTLS proof did not return Graph readiness JSON") from error
    if graph_readiness.get("ready") is not True:
        raise common.TargetE2EError("mTLS reached Python but Graph is not ready")

    port = int(env["TARGET_E2E_GATEWAY_PORT"])
    with urlopen(f"http://127.0.0.1:{port}/healthz", timeout=5) as response:
        if response.status != 200 or response.read().strip() != b"ok":
            raise common.TargetE2EError("isolated gateway is not ready")

    python_inspect = _inspect(env_file, "python-agent-service")
    domain_inspect = _inspect(env_file, "domain-db")
    python_networks = set(python_inspect["NetworkSettings"]["Networks"])
    domain_networks = set(domain_inspect["NetworkSettings"]["Networks"])
    python_env_keys = {item.split("=", 1)[0] for item in python_inspect["Config"].get("Env", [])}
    leaked_keys = sorted(
        key for key in python_env_keys if key.startswith(preflight.FORBIDDEN_PYTHON_ENV_PREFIXES)
    )
    if leaked_keys or python_networks & domain_networks:
        raise common.TargetE2EError("runtime inspection disproved Python/Domain isolation")

    receipt = {
        "schema_version": "target-e2e-readiness.v1",
        "status": "INFRASTRUCTURE_READY_ONLY",
        "target_lane_runnable": False,
        "blocking_application_contracts": [
            "python-candidate-runtime-mode",
            "isolated-real-model-runtime",
            "intake-temporal-allocation",
            "short-lived-activation-consumption",
            "all-room-target-provenance",
            "complete-zero-legacy-inventory",
        ],
        "run_id": env["TARGET_E2E_RUN_ID"],
        "build_id": env["TARGET_E2E_BUILD_ID"],
        "services": states,
        "mtls": {
            "verified": True,
            "expected_spiffe_id": "spiffe://after-sale-flow/java-api-service",
            "graph_readiness": graph_readiness,
        },
        "network_proof": {
            "python_domain_shared_networks": [],
            "python_domain_credential_keys": [],
        },
    }
    evidence_dir = common.assert_external_runtime_path(Path(env["TARGET_E2E_EVIDENCE_DIR"]))
    common.write_json(evidence_dir / "readiness.json", receipt)
    return receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        receipt = check_readiness(args.env_file)
    except (common.TargetE2EError, OSError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
