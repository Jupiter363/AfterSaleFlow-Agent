from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import shutil
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


FORBIDDEN_BASELINE_PORTS = {5173, 8080, 18000, 18010, 18080}
FORBIDDEN_PYTHON_ENV_PREFIXES = ("POSTGRES_", "JAVA_DB_", "DOMAIN_DB_")
ALLOWED_URL_HOSTS = {
    "domain-db",
    "graph-db",
    "redis",
    "minio",
    "elasticsearch",
    "temporal-server",
    "jwks-publisher",
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


def _walk_strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [item for child in value.values() for item in _walk_strings(child)]
    if isinstance(value, list):
        return [item for child in value for item in _walk_strings(child)]
    return []


def validate_rendered_config(config: dict[str, Any], env: dict[str, str]) -> dict[str, Any]:
    run_id = env.get("TARGET_E2E_RUN_ID", "")
    expected_project = f"aflow-target-e2e-{run_id}"
    if config.get("name") != expected_project:
        raise common.TargetE2EError("Compose project name is not the provisioned isolated run")
    services = config.get("services")
    if not isinstance(services, dict):
        raise common.TargetE2EError("rendered Compose services are missing")
    for name, service in services.items():
        image = service.get("image")
        if not isinstance(image, str) or not common.IMAGE_REFERENCE.fullmatch(image):
            raise common.TargetE2EError(f"{name} does not use an immutable registry digest")

    required_databases = {"domain-db", "graph-db", "temporal-db"}
    if not required_databases <= set(services):
        raise common.TargetE2EError("Domain, Graph, and Temporal databases must be distinct services")
    if _networks(services["domain-db"]) & _networks(services["graph-db"]):
        raise common.TargetE2EError("Domain and Graph databases share a network")

    python = services["python-agent-service"]
    python_environment = _environment(python)
    forbidden = sorted(
        key
        for key in python_environment
        if key.startswith(FORBIDDEN_PYTHON_ENV_PREFIXES)
    )
    if forbidden:
        raise common.TargetE2EError(f"Python received Domain database credentials: {forbidden}")
    dsn = python_environment.get("GRAPH_DATABASE_DSN", "")
    if urlsplit(dsn).hostname != "graph-db" or "/target_graph" not in dsn:
        raise common.TargetE2EError("Python Graph DSN does not target the isolated Graph database")
    if _networks(python) & _networks(services["domain-db"]):
        raise common.TargetE2EError("Python can reach the Domain database network")

    graph_adapter_members = {
        name for name, service in services.items() if "graph-adapter" in _networks(service)
    }
    if graph_adapter_members != {"graph-mtls-proxy", "python-agent-service"}:
        raise common.TargetE2EError("the header-to-ASGI adapter network must have exactly two members")
    mtls_members = {
        name for name, service in services.items() if "graph-mtls-client" in _networks(service)
    }
    if mtls_members != {"graph-mtls-proxy", "java-agent-worker", "mtls-proof"}:
        raise common.TargetE2EError("the mTLS client network membership drifted")

    publisher_dependencies = set(services["jwks-publisher"].get("depends_on", {}))
    if "python-agent-service" in publisher_dependencies:
        raise common.TargetE2EError("JWKS publisher reintroduced the Java/Python boot cycle")
    if python_environment.get("GRAPH_JWKS_URL") != "http://jwks-publisher:8080/.well-known/graph-jwks.json":
        raise common.TargetE2EError("Python does not use the separately healthy JWKS publisher")

    published_ports: set[int] = set()
    for service in services.values():
        for port in service.get("ports", []):
            published = int(port["published"])
            if published in FORBIDDEN_BASELINE_PORTS or published in published_ports:
                raise common.TargetE2EError("target E2E port overlaps a baseline or another target service")
            published_ports.add(published)
    if published_ports != {int(env["TARGET_E2E_GATEWAY_PORT"])}:
        raise common.TargetE2EError("only the isolated target E2E gateway may publish a host port")

    for network in config.get("networks", {}).values():
        name = network.get("name", "")
        if not name.startswith(f"aflow_target_e2e_{run_id}_"):
            raise common.TargetE2EError("network name is not run-scoped")
    for volume in config.get("volumes", {}).values():
        name = volume.get("name", "")
        if not name.startswith(f"aflow_target_e2e_{run_id}_"):
            raise common.TargetE2EError("volume name is not run-scoped")

    for value in _walk_strings(config):
        if not value.startswith(("http://", "https://")):
            continue
        hostname = urlsplit(value).hostname
        if hostname not in ALLOWED_URL_HOSTS:
            raise common.TargetE2EError(f"non-local endpoint is forbidden: {hostname}")

    return {
        "project": expected_project,
        "services": sorted(services),
        "networks": sorted(config.get("networks", {})),
        "volumes": sorted(config.get("volumes", {})),
        "published_ports": sorted(published_ports),
        "python_domain_credentials": False,
        "python_domain_network_access": False,
        "mtls_adapter_network_members": sorted(graph_adapter_members),
    }


def run_preflight(env_file: Path) -> dict[str, Any]:
    if shutil.which("docker") is None or shutil.which("openssl") is None:
        raise common.TargetE2EError("Docker CLI or OpenSSL is unavailable")
    env_file = common.assert_external_runtime_path(env_file)
    env = common.parse_env_file(env_file)
    if not common.RUN_ID.fullmatch(env.get("TARGET_E2E_RUN_ID", "")):
        raise common.TargetE2EError("provisioned run ID is invalid")
    if not common.SHA1.fullmatch(env.get("TARGET_E2E_BUILD_ID", "")):
        raise common.TargetE2EError("provisioned build ID is invalid")
    if env.get("TARGET_E2E_BUILD_ID") != env.get("TARGET_E2E_SOURCE_COMMIT"):
        raise common.TargetE2EError("build and source identities diverged")
    for key in common.IMAGE_KEYS:
        value = env.get(f"TARGET_E2E_{key.upper()}_IMAGE", "")
        if not common.IMAGE_REFERENCE.fullmatch(value):
            raise common.TargetE2EError(f"immutable image is missing: {key}")
    common.assert_external_runtime_path(Path(env["TARGET_E2E_SECRETS_DIR"]))
    activation_dir = common.assert_external_runtime_path(Path(env["TARGET_E2E_ACTIVATION_DIR"]))
    activation = common.load_json(activation_dir / "activation-input.json")
    now = dt.datetime.now(dt.timezone.utc)
    not_before = dt.datetime.fromisoformat(str(activation["not_before"]))
    expires = dt.datetime.fromisoformat(str(activation["expires_at"]))
    if not (not_before <= now < expires) or expires - not_before > dt.timedelta(hours=1):
        raise common.TargetE2EError("activation input is expired, future-dated, or not short-lived")
    if activation.get("application_contract_status") != "BLOCKING_UNTIL_CONSUMED_AND_RECEIPTED":
        raise common.TargetE2EError("activation input silently bypasses its application contract")
    if activation.get("run_id") != env["TARGET_E2E_RUN_ID"] or activation.get("build_id") != env["TARGET_E2E_BUILD_ID"]:
        raise common.TargetE2EError("activation input is not bound to this run and build")
    provided_hmac = activation.get("integrity_hmac_sha256")
    unsigned = dict(activation)
    unsigned.pop("integrity_hmac_sha256", None)
    activation_key = Path(env["TARGET_E2E_SECRETS_DIR"]) / "activation.hmac.key"
    expected_hmac = hmac.new(
        activation_key.read_bytes(),
        json.dumps(unsigned, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("ascii"),
        hashlib.sha256,
    ).hexdigest()
    if not isinstance(provided_hmac, str) or not hmac.compare_digest(provided_hmac, expected_hmac):
        raise common.TargetE2EError("activation input integrity verification failed")
    registry = common.load_json(activation_dir / "registry-seed.json")
    if registry.get("registry_hash") != env["TARGET_E2E_GRAPH_REGISTRY_HASH"]:
        raise common.TargetE2EError("registry seed is not bound to the configured registry hash")
    signature_checks = (
        (
            activation_dir / "activation.public.pem",
            activation_dir / "activation-input.sig",
            activation_dir / "activation-input.json",
        ),
        (
            Path(env["TARGET_E2E_SECRETS_DIR"])
            / "graph-public-keys"
            / f"{env['TARGET_E2E_GRAPH_SIGNING_KEY_ID']}.public.pem",
            activation_dir / "registry-seed.sig",
            activation_dir / "registry-seed.json",
        ),
    )
    for public_key, signature, payload in signature_checks:
        verified = common.run_command(
            [
                "openssl",
                "dgst",
                "-sha256",
                "-verify",
                str(public_key),
                "-signature",
                str(signature),
                str(payload),
            ],
            check=False,
        )
        if verified.returncode or "Verified OK" not in verified.stdout:
            raise common.TargetE2EError("P-256 registry or activation signature verification failed")

    result = common.run_command(
        common.compose_argv(env_file, "config", "--format", "json", profile="evidence")
    )
    try:
        config = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise common.TargetE2EError("Docker Compose did not emit JSON config") from error
    summary = validate_rendered_config(config, env)
    receipt = {
        "schema_version": "target-e2e-preflight.v1",
        "status": "PASS",
        "run_id": env["TARGET_E2E_RUN_ID"],
        "build_id": env["TARGET_E2E_BUILD_ID"],
        "activation_expires_at": activation["expires_at"],
        **summary,
    }
    evidence_dir = common.assert_external_runtime_path(Path(env["TARGET_E2E_EVIDENCE_DIR"]))
    common.write_json(evidence_dir / "preflight.json", receipt)
    return receipt


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        receipt = run_preflight(args.env_file)
    except common.TargetE2EError as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
