from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ledger  # noqa: E402
import preflight  # noqa: E402


REQUIRED_HEALTHY = (
    "domain-db",
    "graph-db",
    "temporal-db",
    "redis",
    "minio",
    "elasticsearch",
    "temporal-server",
    "jwks-server",
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
SERVICE_IMAGE_KEY = {
    "domain-db": "postgres",
    "graph-db": "postgres",
    "temporal-db": "postgres",
    "redis": "redis",
    "minio": "minio",
    "elasticsearch": "elasticsearch",
    "temporal-server": "temporal",
    "jwks-server": "nginx",
    "python-agent-service": "python",
    "graph-mtls-proxy": "nginx",
    "ocr-parser-service": "ocr",
    "java-api-service": "java",
    "java-control-worker": "java",
    "java-agent-worker": "java",
    "frontend": "frontend",
    "gateway": "nginx",
    "minio-init": "minio_mc",
    "elasticsearch-init": "elasticsearch",
    "temporal-namespace-init": "temporal",
    "graph-migrate": "python",
    "graph-restore-validation": "python",
}


def _inspect(env_file: Path, service: str) -> dict[str, Any]:
    identifier = common.container_id(env_file, service)
    document = json.loads(common.run_command(["docker", "inspect", identifier]).stdout)
    return document[0]


def _measure_container(
    service: str,
    inspected: dict[str, Any],
    expected_image: dict[str, Any],
    lock: dict[str, Any],
) -> dict[str, Any]:
    labels = inspected["Config"].get("Labels") or {}
    expected_labels = {
        "target-e2e.after-sale-flow.dev/run-id": lock["run_id"],
        "target-e2e.after-sale-flow.dev/project": lock["project_name"],
        "target-e2e.after-sale-flow.dev/lock-nonce": lock["lock_nonce"],
        "target-e2e.after-sale-flow.dev/image-lock-hash": lock["image_lock_hash"],
    }
    if any(labels.get(key) != value for key, value in expected_labels.items()):
        raise common.TargetE2EError(
            f"{service} container labels do not match the host lock"
        )
    if inspected["Image"] != expected_image["config_digest"]:
        raise common.TargetE2EError(
            f"{service} container image ID differs from the OCI config digest"
        )
    image_document = json.loads(
        common.run_command(["docker", "image", "inspect", inspected["Image"]]).stdout
    )[0]
    if (
        expected_image["reference"] not in set(image_document.get("RepoDigests") or [])
        or image_document.get("RootFS", {}).get("Layers")
        != expected_image["layer_digests"]
    ):
        raise common.TargetE2EError(
            f"{service} running image manifest or layers differ from the lock"
        )
    return {
        "service": service,
        "container_id": inspected["Id"],
        "container_name": inspected["Name"].lstrip("/"),
        "image_reference": expected_image["reference"],
        "manifest_digest": expected_image["manifest_digest"],
        "config_digest": inspected["Image"],
        "layer_digests": expected_image["layer_digests"],
        "networks": sorted(inspected["NetworkSettings"].get("Networks", {})),
    }


def _database_measurement(
    inspected: dict[str, Any],
    *,
    admin_user: str,
    database: str,
    runtime_user: str,
    expected_volume: str,
) -> dict[str, Any]:
    query = (
        f"select datname from pg_database where datname='{database}';"
        f"select rolname from pg_roles where rolname='{runtime_user}';"
    )
    result = common.run_command(
        [
            "docker",
            "exec",
            inspected["Id"],
            "psql",
            "-U",
            admin_user,
            "-d",
            "postgres",
            "-Atc",
            query,
        ]
    )
    if result.stdout.splitlines() != [database, runtime_user]:
        raise common.TargetE2EError(
            f"database or runtime principal is missing: {database}"
        )
    volume_names = {
        mount.get("Name")
        for mount in inspected.get("Mounts", [])
        if mount.get("Destination") == "/var/lib/postgresql/data"
    }
    if volume_names != {expected_volume}:
        raise common.TargetE2EError(f"database volume identity drifted: {database}")
    return {
        "container_id": inspected["Id"],
        "database": database,
        "runtime_principal": runtime_user,
        "volume": expected_volume,
        "networks": sorted(inspected["NetworkSettings"].get("Networks", {})),
    }


def collect_runtime_measurement(env_file: Path) -> dict[str, Any]:
    env, lock = common.validate_env_lock(env_file)
    _candidate, images, _image_lock = common.load_image_lock(
        Path(env["TARGET_E2E_IMAGE_LOCK_PATH"])
    )
    states: dict[str, str] = {}
    containers: list[dict[str, Any]] = []
    inspections: dict[str, dict[str, Any]] = {}
    for service in REQUIRED_HEALTHY:
        inspected = _inspect(env_file, service)
        inspections[service] = inspected
        state = inspected["State"]
        health = state.get("Health", {}).get("Status")
        if state.get("Status") != "running" or health != "healthy":
            raise common.TargetE2EError(
                f"{service} is not healthy: {state.get('Status')}/{health}"
            )
        states[service] = "healthy"
        containers.append(
            _measure_container(
                service, inspected, images[SERVICE_IMAGE_KEY[service]], lock
            )
        )
    for service in REQUIRED_COMPLETED:
        inspected = _inspect(env_file, service)
        inspections[service] = inspected
        state = inspected["State"]
        if state.get("Status") != "exited" or state.get("ExitCode") != 0:
            raise common.TargetE2EError(f"{service} did not complete successfully")
        states[service] = "completed"
        containers.append(
            _measure_container(
                service, inspected, images[SERVICE_IMAGE_KEY[service]], lock
            )
        )

    proof = common.run_command(
        common.compose_argv(env_file, "run", "--rm", "mtls-proof", profile="evidence"),
        timeout=120,
    )
    try:
        graph_readiness = json.loads(proof.stdout)
    except json.JSONDecodeError as error:
        raise common.TargetE2EError(
            "mTLS proof did not return Graph readiness JSON"
        ) from error
    if graph_readiness.get("ready") is not True:
        raise common.TargetE2EError("mTLS reached Python but Graph is not ready")

    port = lock["gateway_port"]
    with urlopen(f"http://127.0.0.1:{port}/healthz", timeout=5) as response:
        if response.status != 200 or response.read().strip() != b"ok":
            raise common.TargetE2EError("isolated gateway is not ready")

    python_inspect = inspections["python-agent-service"]
    domain_inspect = inspections["domain-db"]
    graph_inspect = inspections["graph-db"]
    python_networks = set(python_inspect["NetworkSettings"]["Networks"])
    domain_networks = set(domain_inspect["NetworkSettings"]["Networks"])
    python_env_keys = {
        item.split("=", 1)[0] for item in python_inspect["Config"].get("Env", [])
    }
    leaked_keys = sorted(
        key
        for key in python_env_keys
        if key.startswith(preflight.FORBIDDEN_PYTHON_ENV_PREFIXES)
        or any(part in key for part in preflight.FORBIDDEN_PYTHON_ACTIVATION_PARTS)
    )
    if leaked_keys or python_networks & domain_networks or len(python_networks) != 1:
        raise common.TargetE2EError(
            "runtime inspection disproved Python authority isolation"
        )

    no_tcp_listener = common.run_command(
        [
            "docker",
            "exec",
            python_inspect["Id"],
            "python",
            "-c",
            "from pathlib import Path; files=(Path('/proc/net/tcp'),Path('/proc/net/tcp6')); rows=[line.split() for p in files for line in p.read_text().splitlines()[1:]]; assert not any(r[3]=='0A' for r in rows)",
        ]
    )
    if no_tcp_listener.returncode:
        raise common.TargetE2EError(
            "Python unexpectedly exposes a TCP listener instead of only its Unix socket"
        )
    jwks_probe = common.run_command(
        [
            "docker",
            "exec",
            python_inspect["Id"],
            "python",
            "-c",
            "import urllib.error,urllib.request; assert urllib.request.urlopen('http://jwks-server:8080/.well-known/graph-jwks.json').status==200;\ntry: urllib.request.urlopen('http://jwks-server:8080/api/disputes')\nexcept urllib.error.HTTPError as e: assert e.code==404\nelse: raise AssertionError('JWKS business-path bypass')",
        ]
    )
    if jwks_probe.returncode:
        raise common.TargetE2EError("JWKS resource-only probe failed")

    prefix = f"aflow_target_e2e_{lock['run_id']}_"
    domain_database = _database_measurement(
        domain_inspect,
        admin_user="target_domain_admin",
        database="target_domain",
        runtime_user="domain_app",
        expected_volume=prefix + "domain_data",
    )
    graph_database = _database_measurement(
        graph_inspect,
        admin_user="target_graph_admin",
        database="target_graph",
        runtime_user="graph_runtime",
        expected_volume=prefix + "graph_data",
    )
    if (
        domain_database["container_id"] == graph_database["container_id"]
        or domain_database["volume"] == graph_database["volume"]
        or set(domain_database["networks"]) & set(graph_database["networks"])
    ):
        raise common.TargetE2EError(
            "runtime database identities are not physically isolated"
        )

    return {
        "schema_version": "target-e2e-runtime-measurement.v2",
        "status": "INFRASTRUCTURE_READY_ONLY",
        "target_lane_runnable": False,
        "run_id": lock["run_id"],
        "candidate_sha": lock["candidate_sha"],
        "host_lock_nonce": lock["lock_nonce"],
        "services": states,
        "containers": sorted(containers, key=lambda item: item["service"]),
        "mtls": {
            "verified": True,
            "transport": "TLS1.3_TO_NGINX_THEN_UDS_ONLY",
            "expected_spiffe_id": "spiffe://after-sale-flow/java-api-service",
            "graph_readiness": graph_readiness,
            "tcp_bypass_listener_present": False,
        },
        "jwks": {
            "resource_only_probe": "PASS",
            "java_business_endpoint_reachable": False,
        },
        "database_identities": {
            "domain": domain_database,
            "graph": graph_database,
        },
        "network_proof": {
            "python_networks": sorted(python_networks),
            "python_domain_shared_networks": [],
            "python_domain_or_activation_credential_keys": [],
        },
        "blocking_application_contracts": [
            "python-candidate-runtime-mode",
            "isolated-real-model-runtime",
            "intake-temporal-allocation",
            "short-lived-activation-consumption",
            "all-room-target-provenance",
            "complete-zero-legacy-inventory",
        ],
    }


def check_readiness(env_file: Path) -> dict[str, Any]:
    preflight.run_preflight(env_file)
    env, lock = common.validate_env_lock(env_file)
    run_context = common.load_json(Path(env["TARGET_E2E_RUN_CONTEXT_PATH"]))
    context = common.ledger_context_from_run_context(run_context)
    measurement = collect_runtime_measurement(env_file)
    measurement["run_context_hash"] = context["run_context_hash"]
    measurement["runtime_measurement_hash"] = common.canonical_sha256(measurement)
    harness_private = ledger.load_private_key(
        Path(env["TARGET_E2E_SECRETS_DIR"])
        / "harness-attestation"
        / "harness.private.pem"
    )
    harness_public = ledger.load_public_key(
        Path(env["TARGET_E2E_PUBLIC_DIR"]) / "harness" / "harness.public.pem"
    )
    record = ledger.append_record(
        Path(env["TARGET_E2E_EVIDENCE_DIR"]) / "ledger.jsonl",
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
        context=context,
        source_kind="HARNESS_DIRECT",
        source_identity="target-e2e-runtime-measurer",
        case_id=None,
        payload_type="RUNTIME_MEASUREMENT",
        payload=measurement,
    )
    receipt = ledger.attest_document(
        {**measurement, "ledger_record_hash": record["record_hash"]},
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
    )
    common.write_json(Path(env["TARGET_E2E_EVIDENCE_DIR"]) / "readiness.json", receipt)
    return receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        receipt = check_readiness(args.env_file)
    except (common.TargetE2EError, OSError, json.JSONDecodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
