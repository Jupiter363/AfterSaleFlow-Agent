from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


def _redact_config(config: dict[str, Any]) -> dict[str, Any]:
    redacted = json.loads(json.dumps(config))
    for service in redacted.get("services", {}).values():
        environment = service.get("environment")
        if isinstance(environment, dict):
            service["environment"] = common.redact_environment(environment)
    return redacted


def _container_summary(document: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": document["Id"],
        "name": document["Name"].lstrip("/"),
        "image_id": document["Image"],
        "state": {
            "status": document["State"].get("Status"),
            "exit_code": document["State"].get("ExitCode"),
            "health": document["State"].get("Health", {}).get("Status"),
        },
        "labels": document["Config"].get("Labels", {}),
        "environment_keys": sorted(
            item.split("=", 1)[0] for item in document["Config"].get("Env", [])
        ),
        "mounts": sorted(
            (
                {
                    "type": mount.get("Type"),
                    "name": mount.get("Name"),
                    "destination": mount.get("Destination"),
                    "read_only": not mount.get("RW", False),
                }
                for mount in document.get("Mounts", [])
            ),
            key=lambda item: str(item["destination"]),
        ),
        "networks": sorted(document["NetworkSettings"].get("Networks", {})),
    }


def _redact_log(payload: str, environment: dict[str, str]) -> str:
    sensitive_parts = ("PASSWORD", "SECRET", "KEY", "TOKEN", "USER")
    secrets = {
        key: value
        for key, value in environment.items()
        if value and any(part in key for part in sensitive_parts)
    }
    redacted = payload
    for key, value in sorted(secrets.items(), key=lambda item: len(item[1]), reverse=True):
        redacted = redacted.replace(value, f"<redacted:{key}>")
    return redacted


def export_forensics(env_file: Path) -> dict[str, Any]:
    env = common.parse_env_file(env_file)
    evidence_dir = common.assert_external_runtime_path(Path(env["TARGET_E2E_EVIDENCE_DIR"]))
    evidence_dir.mkdir(parents=True, exist_ok=True)
    config_result = common.run_command(
        common.compose_argv(env_file, "config", "--format", "json", profile="evidence")
    )
    config = json.loads(config_result.stdout)
    common.write_json(evidence_dir / "compose.redacted.json", _redact_config(config))

    identifiers = common.run_command(
        common.compose_argv(env_file, "ps", "--all", "--quiet")
    ).stdout.splitlines()
    containers: list[dict[str, Any]] = []
    if identifiers:
        inspected = json.loads(common.run_command(["docker", "inspect", *identifiers]).stdout)
        containers = sorted((_container_summary(item) for item in inspected), key=lambda item: item["name"])
    common.write_json(evidence_dir / "containers.json", containers)

    images: list[dict[str, Any]] = []
    for reference in sorted({service["image"] for service in config["services"].values()}):
        result = common.run_command(["docker", "image", "inspect", reference], check=False)
        if result.returncode:
            images.append({"reference": reference, "status": "UNAVAILABLE"})
            continue
        document = json.loads(result.stdout)[0]
        images.append(
            {
                "reference": reference,
                "status": "PRESENT",
                "image_id": document["Id"],
                "repo_digests": sorted(document.get("RepoDigests") or []),
            }
        )
    common.write_json(evidence_dir / "images.json", images)

    service_logs = evidence_dir / "logs"
    service_logs.mkdir(exist_ok=True)
    for service in sorted(config["services"]):
        result = common.run_command(
            common.compose_argv(env_file, "logs", "--no-color", "--timestamps", "--tail", "2000", service),
            check=False,
        )
        (service_logs / f"{service}.log").write_text(
            _redact_log(result.stdout + result.stderr, env),
            encoding="utf-8",
            errors="replace",
            newline="\n",
        )

    by_service = {
        item["labels"].get("com.docker.compose.service"): item for item in containers
    }
    python = by_service.get("python-agent-service")
    domain = by_service.get("domain-db")
    shared_networks = sorted(
        set(python["networks"]) & set(domain["networks"])
    ) if python and domain else ["RUNTIME_INSPECTION_INCOMPLETE"]
    forbidden_keys = sorted(
        key
        for key in (python["environment_keys"] if python else [])
        if key.startswith(("POSTGRES_", "JAVA_DB_", "DOMAIN_DB_"))
    )
    network_proof = {
        "schema_version": "target-e2e-network-proof.v1",
        "status": "PASS" if not shared_networks and not forbidden_keys else "FAIL",
        "python_domain_shared_networks": shared_networks,
        "python_domain_credential_keys": forbidden_keys,
    }
    common.write_json(evidence_dir / "network-proof.json", network_proof)

    files = sorted(path for path in evidence_dir.rglob("*") if path.is_file() and path.name != "forensic-manifest.json")
    manifest = {
        "schema_version": "target-e2e-forensic-manifest.v1",
        "run_id": env["TARGET_E2E_RUN_ID"],
        "build_id": env["TARGET_E2E_BUILD_ID"],
        "network_isolation_status": network_proof["status"],
        "files": [
            {
                "path": path.relative_to(evidence_dir).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": common.file_sha256(path),
            }
            for path in files
        ],
    }
    common.write_json(evidence_dir / "forensic-manifest.json", manifest)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        manifest = export_forensics(args.env_file)
    except (common.TargetE2EError, OSError, json.JSONDecodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
