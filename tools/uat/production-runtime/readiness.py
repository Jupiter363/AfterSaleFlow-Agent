from __future__ import annotations

import argparse
import inspect
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


def _parse_tcp_listener_rows(lines: list[str], *, family: str) -> list[dict[str, str]]:
    listeners: list[dict[str, str]] = []
    for line in lines:
        fields = line.split()
        if len(fields) < 10 or fields[3] != "0A":
            continue
        address = "<invalid>"
        if family == "ipv4":
            try:
                encoded_address = fields[1].split(":", 1)[0]
                address = ".".join(
                    str(value) for value in bytes.fromhex(encoded_address)[::-1]
                )
            except ValueError:
                pass
        listeners.append(
            {
                "family": family,
                "address": address,
                "uid": fields[7],
                "inode": fields[9],
            }
        )
    return listeners


def _owned_socket_inodes(proc_root: Path = Path("/proc")) -> set[str]:
    inodes: set[str] = set()
    for process in proc_root.iterdir():
        if not process.name.isdecimal():
            continue
        try:
            descriptors = (process / "fd").iterdir()
            for descriptor in descriptors:
                try:
                    target = str(descriptor.readlink())
                except (FileNotFoundError, PermissionError):
                    continue
                if target.startswith("socket:[") and target.endswith("]"):
                    inodes.add(target[8:-1])
        except (FileNotFoundError, PermissionError):
            continue
    return inodes


def _resolver_ipv4_addresses() -> set[str]:
    nameservers: set[str] = set()
    for line in Path("/etc/resolv.conf").read_text(encoding="utf-8").splitlines():
        fields = line.split("#", 1)[0].split()
        if len(fields) == 2 and fields[0] == "nameserver":
            octets = fields[1].split(".")
            if len(octets) == 4 and all(
                octet.isdecimal() and 0 <= int(octet) <= 255 for octet in octets
            ):
                nameservers.add(".".join(str(int(octet)) for octet in octets))
    return nameservers


def _unexpected_tcp_listeners(
    listeners: list[dict[str, str]],
    owned_socket_inodes: set[str],
    resolver_ipv4_addresses: set[str],
) -> list[dict[str, str]]:
    return [
        listener
        for listener in listeners
        if listener["inode"] in owned_socket_inodes
        or not (
            listener["family"] == "ipv4"
            and listener["uid"] == "0"
            and listener["address"] == "127.0.0.11"
            and "127.0.0.11" in resolver_ipv4_addresses
        )
    ]


def _tcp_listener_probe() -> str:
    helpers = (
        _parse_tcp_listener_rows,
        _owned_socket_inodes,
        _resolver_ipv4_addresses,
        _unexpected_tcp_listeners,
    )
    return "\n\n".join(
        (
            "from __future__ import annotations",
            "from pathlib import Path",
            *(inspect.getsource(helper) for helper in helpers),
            "listeners = _parse_tcp_listener_rows("
            "Path('/proc/net/tcp').read_text(encoding='utf-8').splitlines()[1:], "
            "family='ipv4')",
            "listeners.extend(_parse_tcp_listener_rows("
            "Path('/proc/net/tcp6').read_text(encoding='utf-8').splitlines()[1:], "
            "family='ipv6'))",
            "unexpected = _unexpected_tcp_listeners("
            "listeners, _owned_socket_inodes(), _resolver_ipv4_addresses())",
            "assert not unexpected, f'unexpected TCP listener(s): {unexpected!r}'",
        )
    )


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
        "production-runtime.after-sale-flow.dev/run-id": lock["run_id"],
        "production-runtime.after-sale-flow.dev/project": lock["project_name"],
        "production-runtime.after-sale-flow.dev/lock-nonce": lock["lock_nonce"],
        "production-runtime.after-sale-flow.dev/image-lock-hash": lock["image_lock_hash"],
    }
    if any(labels.get(key) != value for key, value in expected_labels.items()):
        raise common.ProductionError(
            f"{service} container labels do not match the host lock"
        )
    if inspected["Image"] != expected_image["config_digest"]:
        raise common.ProductionError(
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
        raise common.ProductionError(
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
        raise common.ProductionError(
            f"database or runtime principal is missing: {database}"
        )
    volume_names = {
        mount.get("Name")
        for mount in inspected.get("Mounts", [])
        if mount.get("Destination") == "/var/lib/postgresql/data"
    }
    if volume_names != {expected_volume}:
        raise common.ProductionError(f"database volume identity drifted: {database}")
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
        Path(env["PRODUCTION_RUNTIME_IMAGE_LOCK_PATH"])
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
            raise common.ProductionError(
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
            raise common.ProductionError(f"{service} did not complete successfully")
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
        raise common.ProductionError(
            "mTLS proof did not return Graph readiness JSON"
        ) from error
    if graph_readiness.get("ready") is not True:
        raise common.ProductionError("mTLS reached Python but Graph is not ready")

    port = lock["gateway_port"]
    with urlopen(f"http://127.0.0.1:{port}/healthz", timeout=5) as response:
        if response.status != 200 or response.read().strip() != b"ok":
            raise common.ProductionError("isolated gateway is not ready")

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
        raise common.ProductionError(
            "runtime inspection disproved Python authority isolation"
        )

    no_tcp_listener = common.run_command(
        [
            "docker",
            "exec",
            python_inspect["Id"],
            "python",
            "-c",
            _tcp_listener_probe(),
        ]
    )
    if no_tcp_listener.returncode:
        raise common.ProductionError(
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
        raise common.ProductionError("JWKS resource-only probe failed")

    prefix = f"aflow_production_runtime_{lock['run_id']}_"
    domain_database = _database_measurement(
        domain_inspect,
        admin_user="production_domain_admin",
        database="production_domain",
        runtime_user="domain_app",
        expected_volume=prefix + "domain_data",
    )
    graph_database = _database_measurement(
        graph_inspect,
        admin_user="production_graph_admin",
        database="production_graph",
        runtime_user="graph_runtime",
        expected_volume=prefix + "graph_data",
    )
    if (
        domain_database["container_id"] == graph_database["container_id"]
        or domain_database["volume"] == graph_database["volume"]
        or set(domain_database["networks"]) & set(graph_database["networks"])
    ):
        raise common.ProductionError(
            "runtime database identities are not physically isolated"
        )

    return {
        "schema_version": "production-runtime-measurement.v2",
        "status": "INFRASTRUCTURE_READY_ONLY",
        "production_lane_runnable": False,
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
    run_context = common.load_json(Path(env["PRODUCTION_RUNTIME_RUN_CONTEXT_PATH"]))
    context = common.ledger_context_from_run_context(run_context)
    measurement = collect_runtime_measurement(env_file)
    measurement["run_context_hash"] = context["run_context_hash"]
    measurement["runtime_measurement_hash"] = common.canonical_sha256(measurement)
    harness_private = ledger.load_private_key(
        Path(env["PRODUCTION_RUNTIME_SECRETS_DIR"])
        / "harness-attestation"
        / "harness.private.pem"
    )
    harness_public = ledger.load_public_key(
        Path(env["PRODUCTION_RUNTIME_PUBLIC_DIR"]) / "harness" / "harness.public.pem"
    )
    record = ledger.append_record(
        Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"]) / "ledger.jsonl",
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
        context=context,
        source_kind="HARNESS_DIRECT",
        source_identity="production-runtime-measurer",
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
    common.write_json(Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"]) / "readiness.json", receipt)
    return receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        receipt = check_readiness(args.env_file)
    except (common.ProductionError, OSError, json.JSONDecodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
