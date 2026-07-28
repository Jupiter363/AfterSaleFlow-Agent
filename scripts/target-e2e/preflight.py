from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ledger  # noqa: E402


FORBIDDEN_BASELINE_PORTS = {5173, 8080, 18000, 18010, 18080}
FORBIDDEN_PYTHON_ENV_PREFIXES = ("POSTGRES_", "JAVA_DB_", "DOMAIN_DB_")
FORBIDDEN_PYTHON_ACTIVATION_PARTS = (
    "ACTIVATION_JWS",
    "ACTIVATION_DIRECTORY",
    "ACTIVATION_PATH",
)
EXPECTED_GRAPH_MTLS_PROXY_NETWORKS = frozenset(
    {"graph-mtls-client", "app-internal"}
)
ALLOWED_URL_HOSTS = {
    "domain-db",
    "graph-db",
    "redis",
    "minio",
    "elasticsearch",
    "temporal-server",
    "jwks-server",
    "graph-mtls-proxy",
    "java-api-service",
    "ocr-parser-service",
    "frontend",
    "model-contract-blocker",
    "observability-contract-blocker",
    "java-callback-contract-blocker",
    "127.0.0.1",
    "localhost",
}


def _networks(service: dict[str, Any]) -> set[str]:
    value = service.get("networks", {})
    return set(value if isinstance(value, dict) else value)


def _environment(service: dict[str, Any]) -> dict[str, str]:
    value = service.get("environment", {})
    if not isinstance(value, dict):
        raise common.TargetE2EError("rendered Compose environment must be an object")
    return {str(key): str(item) for key, item in value.items()}


def _validate_graph_mtls_proxy_networks(proxy: dict[str, Any]) -> None:
    if _networks(proxy) != EXPECTED_GRAPH_MTLS_PROXY_NETWORKS:
        raise common.TargetE2EError("mTLS proxy has an unexpected peer network")


def _walk_strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [item for child in value.values() for item in _walk_strings(child)]
    if isinstance(value, list):
        return [item for child in value for item in _walk_strings(child)]
    return []


def _resource_labels(resource: dict[str, Any]) -> dict[str, str]:
    labels = resource.get("labels", {})
    if not isinstance(labels, dict):
        raise common.TargetE2EError("resource labels must be an object")
    return {str(key): str(value) for key, value in labels.items()}


def validate_rendered_config(
    config: dict[str, Any],
    env: dict[str, str],
    lock: dict[str, Any],
    images: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    run_id = env["TARGET_E2E_RUN_ID"]
    expected_project = lock["project_name"]
    if config.get("name") != expected_project:
        raise common.TargetE2EError(
            "Compose project name is not the host-locked project"
        )
    services = config.get("services")
    if not isinstance(services, dict) or set(services) != set(common.EXPECTED_SERVICES):
        raise common.TargetE2EError(
            "rendered Compose service inventory drifted from the host lock"
        )
    image_by_service = {
        "domain-db": "postgres",
        "graph-db": "postgres",
        "temporal-db": "postgres",
        "redis": "redis",
        "minio": "minio",
        "minio-init": "minio_mc",
        "elasticsearch": "elasticsearch",
        "elasticsearch-init": "elasticsearch",
        "temporal-server": "temporal",
        "temporal-namespace-init": "temporal",
        "graph-migrate": "python",
        "graph-restore-validation": "python",
        "jwks-server": "nginx",
        "graph-mtls-proxy": "nginx",
        "python-agent-service": "python",
        "ocr-parser-service": "ocr",
        "java-api-service": "java",
        "java-control-worker": "java",
        "java-agent-worker": "java",
        "frontend": "frontend",
        "gateway": "nginx",
        "mtls-proof": "curl",
    }
    required_labels = {
        "target-e2e.after-sale-flow.dev/run-id": run_id,
        "target-e2e.after-sale-flow.dev/project": expected_project,
        "target-e2e.after-sale-flow.dev/lock-nonce": lock["lock_nonce"],
        "target-e2e.after-sale-flow.dev/image-lock-hash": lock["image_lock_hash"],
    }
    for name, service in services.items():
        expected_image = images[image_by_service[name]]["reference"]
        if service.get("image") != expected_image:
            raise common.TargetE2EError(f"{name} image is not the locked OCI manifest")
        labels = _resource_labels(service)
        if any(labels.get(key) != value for key, value in required_labels.items()):
            raise common.TargetE2EError(
                f"{name} does not carry the exact host-lock labels"
            )

    required_databases = {"domain-db", "graph-db", "temporal-db"}
    if not required_databases <= set(services):
        raise common.TargetE2EError(
            "Domain, Graph, and Temporal databases must be distinct services"
        )
    if _networks(services["domain-db"]) & _networks(services["graph-db"]):
        raise common.TargetE2EError("Domain and Graph databases share a network")

    python = services["python-agent-service"]
    python_environment = _environment(python)
    forbidden = sorted(
        key
        for key in python_environment
        if key.startswith(FORBIDDEN_PYTHON_ENV_PREFIXES)
    )
    forbidden_activation = sorted(
        key
        for key in python_environment
        if any(part in key for part in FORBIDDEN_PYTHON_ACTIVATION_PARTS)
    )
    if forbidden or forbidden_activation:
        raise common.TargetE2EError(
            f"Python received forbidden Domain or activation authority: {forbidden + forbidden_activation}"
        )
    dsn = python_environment.get("GRAPH_DATABASE_DSN", "")
    if urlsplit(dsn).hostname != "graph-db" or "/target_graph" not in dsn:
        raise common.TargetE2EError(
            "Python Graph DSN does not target the isolated Graph database"
        )
    if _networks(python) != {"python-egress"}:
        raise common.TargetE2EError(
            "Python must attach to exactly one private egress network"
        )
    command = " ".join(str(part) for part in python.get("command", []))
    if "--uds /run/target-e2e/python/agent.sock" not in command or "--host" in command:
        raise common.TargetE2EError(
            "Python backend must listen only on the private Unix socket"
        )
    python_mount_sources = {
        str(item.get("source", ""))
        for item in python.get("volumes", [])
        if isinstance(item, dict)
    }
    if any("activation" in source.lower() for source in python_mount_sources):
        raise common.TargetE2EError(
            "activation files are forbidden in the Python container"
        )
    if _networks(python) & _networks(services["domain-db"]):
        raise common.TargetE2EError("Python can reach the Domain database network")

    proxy = services["graph-mtls-proxy"]
    _validate_graph_mtls_proxy_networks(proxy)
    socket_sources = {
        str(item.get("source", ""))
        for item in proxy.get("volumes", [])
        if isinstance(item, dict)
    } & python_mount_sources
    if socket_sources != {env["TARGET_E2E_SOCKET_DIR"]}:
        raise common.TargetE2EError(
            "Nginx and Python do not share exactly the locked UDS directory"
        )
    mtls_members = {
        name
        for name, service in services.items()
        if "graph-mtls-client" in _networks(service)
    }
    if mtls_members != {"graph-mtls-proxy", "java-agent-worker", "mtls-proof"}:
        raise common.TargetE2EError("the mTLS client network membership drifted")

    jwks = services["jwks-server"]
    if jwks.get("image") != images["nginx"]["reference"] or _networks(jwks) != {
        "python-egress"
    }:
        raise common.TargetE2EError("JWKS must be a public-only static Nginx service")
    if jwks.get("environment"):
        raise common.TargetE2EError(
            "static JWKS server must not receive application credentials"
        )
    if (
        python_environment.get("GRAPH_JWKS_URL")
        != "http://jwks-server:8080/.well-known/graph-jwks.json"
    ):
        raise common.TargetE2EError(
            "Python does not use the isolated static JWKS resource"
        )
    python_network_members = {
        name
        for name, service in services.items()
        if "python-egress" in _networks(service)
    }
    if python_network_members != {
        "python-agent-service",
        "graph-db",
        "minio",
        "jwks-server",
    }:
        raise common.TargetE2EError(
            "Python egress network exposes an unexpected business service"
        )
    if any(name.startswith("java-") for name in python_network_members):
        raise common.TargetE2EError("Python can reach a Java business endpoint")

    published_ports: set[int] = set()
    for service in services.values():
        for port in service.get("ports", []):
            published = int(port["published"])
            if published in FORBIDDEN_BASELINE_PORTS or published in published_ports:
                raise common.TargetE2EError(
                    "target E2E port overlaps a baseline or another target service"
                )
            published_ports.add(published)
    if published_ports != {lock["gateway_port"]}:
        raise common.TargetE2EError(
            "only the locked target E2E gateway port may be published"
        )

    rendered_network_names = sorted(
        value.get("name") for value in config.get("networks", {}).values()
    )
    rendered_volume_names = sorted(
        value.get("name") for value in config.get("volumes", {}).values()
    )
    if rendered_network_names != sorted(lock["resources"]["networks"]):
        raise common.TargetE2EError(
            "rendered network set does not equal the locked resource set"
        )
    if rendered_volume_names != sorted(lock["resources"]["volumes"]):
        raise common.TargetE2EError(
            "rendered volume set does not equal the locked resource set"
        )
    for collection in (config.get("networks", {}), config.get("volumes", {})):
        for resource in collection.values():
            labels = _resource_labels(resource)
            if any(labels.get(key) != value for key, value in required_labels.items()):
                raise common.TargetE2EError(
                    "network or volume host-lock labels drifted"
                )

    for value in _walk_strings(config):
        if not value.startswith(("http://", "https://")):
            continue
        hostname = urlsplit(value).hostname
        if hostname not in ALLOWED_URL_HOSTS:
            raise common.TargetE2EError(f"non-local endpoint is forbidden: {hostname}")

    return {
        "project": expected_project,
        "services": sorted(services),
        "networks": rendered_network_names,
        "volumes": rendered_volume_names,
        "published_ports": sorted(published_ports),
        "python_domain_credentials": False,
        "python_domain_network_access": False,
        "python_listener": "UNIX_DOMAIN_SOCKET_ONLY",
        "jwks_surface": ["/.well-known/graph-jwks.json"],
    }


def verify_local_images(images: dict[str, dict[str, Any]]) -> dict[str, Any]:
    measured: dict[str, Any] = {}
    for key, expected in images.items():
        result = common.run_command(
            ["docker", "image", "inspect", expected["reference"]], check=False
        )
        if result.returncode:
            raise common.TargetE2EError(f"locked image is unavailable locally: {key}")
        document = json.loads(result.stdout)[0]
        if document.get("Id") != expected["config_digest"]:
            raise common.TargetE2EError(f"local image config digest mismatch: {key}")
        repo_digests = set(document.get("RepoDigests") or [])
        if expected["reference"] not in repo_digests:
            raise common.TargetE2EError(f"local image manifest digest mismatch: {key}")
        layers = document.get("RootFS", {}).get("Layers") or []
        if layers != expected["layer_digests"]:
            raise common.TargetE2EError(f"local image layer inventory mismatch: {key}")
        measured[key] = {
            "reference": expected["reference"],
            "manifest_digest": expected["manifest_digest"],
            "config_digest": document["Id"],
            "layer_digests": layers,
        }
    return measured


def run_preflight(env_file: Path) -> dict[str, Any]:
    if shutil.which("docker") is None:
        raise common.TargetE2EError("Docker CLI is unavailable")
    env, lock = common.validate_env_lock(env_file)
    candidate, images, image_lock = common.load_image_lock(
        Path(env["TARGET_E2E_IMAGE_LOCK_PATH"])
    )
    if (
        candidate != lock["candidate_sha"]
        or image_lock["self_hash"] != lock["image_lock_hash"]
    ):
        raise common.TargetE2EError("image lock does not match the atomic host lock")
    run_context = common.load_json(Path(env["TARGET_E2E_RUN_CONTEXT_PATH"]))
    context = common.validate_run_context_bindings(run_context, env, lock)
    if context["run_context_hash"] != env["TARGET_E2E_RUN_CONTEXT_HASH"]:
        raise common.TargetE2EError("run context hash does not match the env file")
    projection = run_context["runtime_projection"]
    issued = common.parse_timestamp(
        projection["issuedAt"], "runtime projection issuedAt"
    )
    expires = common.parse_timestamp(
        projection["expiresAt"], "runtime projection expiresAt"
    )
    if not (issued <= common.utc_now() < expires) or expires - issued > dt.timedelta(
        hours=2
    ):
        raise common.TargetE2EError(
            "runtime projection is expired or exceeds the activation ceiling"
        )

    harness_public = ledger.load_public_key(
        Path(env["TARGET_E2E_PUBLIC_DIR"]) / "harness" / "harness.public.pem"
    )
    provisioning_receipt = common.load_json(
        Path(lock["run_directory"]) / "provisioning-receipt.json"
    )
    ledger.verify_attested_document(
        provisioning_receipt,
        harness_public,
        expected_key_sha256=lock["ledger_public_key_sha256"],
        context="provisioning receipt",
    )
    records = ledger.verify_ledger(
        Path(env["TARGET_E2E_EVIDENCE_DIR"]) / "ledger.jsonl",
        harness_public,
        expected_public_key_sha256=lock["ledger_public_key_sha256"],
        expected_context=context,
    )
    if not records or records[0]["payload_type"] != "PROVISIONED_RUN_CONTEXT":
        raise common.TargetE2EError("evidence ledger lacks its provisioned root record")

    measured_images = verify_local_images(images)
    result = common.run_command(
        common.compose_argv(env_file, "config", "--format", "json", profile="evidence")
    )
    try:
        config = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise common.TargetE2EError(
            "Docker Compose did not emit JSON config"
        ) from error
    summary = validate_rendered_config(config, env, lock, images)
    payload = {
        "schema_version": "target-e2e-preflight.v2",
        "status": "PASS",
        "run_id": lock["run_id"],
        "candidate_sha": candidate,
        "run_context_hash": context["run_context_hash"],
        "image_lock_hash": image_lock["self_hash"],
        "measured_images": measured_images,
        "activation_expires_at": projection["expiresAt"],
        **summary,
    }
    harness_private = ledger.load_private_key(
        Path(env["TARGET_E2E_SECRETS_DIR"])
        / "harness-attestation"
        / "harness.private.pem"
    )
    record = ledger.append_record(
        Path(env["TARGET_E2E_EVIDENCE_DIR"]) / "ledger.jsonl",
        harness_private,
        harness_public,
        key_id=f"p9-harness-{candidate[:12]}",
        context=context,
        source_kind="HARNESS_DIRECT",
        source_identity="target-e2e-preflight",
        case_id=None,
        payload_type="PREFLIGHT_MEASUREMENT",
        payload=payload,
    )
    receipt = ledger.attest_document(
        {**payload, "ledger_record_hash": record["record_hash"]},
        harness_private,
        harness_public,
        key_id=f"p9-harness-{candidate[:12]}",
    )
    common.write_json(Path(env["TARGET_E2E_EVIDENCE_DIR"]) / "preflight.json", receipt)
    return receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        receipt = run_preflight(args.env_file)
    except (common.TargetE2EError, OSError, json.JSONDecodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
