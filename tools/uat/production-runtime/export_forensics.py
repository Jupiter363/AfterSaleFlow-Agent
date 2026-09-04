from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ledger  # noqa: E402


def _redact_config(config: dict[str, Any]) -> dict[str, Any]:
    redacted = json.loads(json.dumps(config))
    for service in redacted.get("services", {}).values():
        environment = service.get("environment")
        if isinstance(environment, dict):
            service["environment"] = common.redact_environment(environment)
    return redacted


def _container_summary(
    document: dict[str, Any], lock: dict[str, Any]
) -> dict[str, Any]:
    labels = document["Config"].get("Labels", {})
    expected = {
        "production-runtime.after-sale-flow.dev/run-id": lock["run_id"],
        "production-runtime.after-sale-flow.dev/project": lock["project_name"],
        "production-runtime.after-sale-flow.dev/lock-nonce": lock["lock_nonce"],
        "production-runtime.after-sale-flow.dev/image-lock-hash": lock["image_lock_hash"],
    }
    if any(labels.get(key) != value for key, value in expected.items()):
        raise common.ProductionError(
            "forensic container labels do not match the host lock"
        )
    return {
        "id": document["Id"],
        "name": document["Name"].lstrip("/"),
        "service": labels.get("com.docker.compose.service"),
        "image_id": document["Image"],
        "state": {
            "status": document["State"].get("Status"),
            "exit_code": document["State"].get("ExitCode"),
            "health": document["State"].get("Health", {}).get("Status"),
        },
        "labels": labels,
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
    sensitive_parts = ("PASSWORD", "SECRET", "KEY", "TOKEN", "USER", "JWS")
    secrets = {
        key: value
        for key, value in environment.items()
        if value and any(part in key for part in sensitive_parts)
    }
    redacted = payload
    for key, value in sorted(
        secrets.items(), key=lambda item: len(item[1]), reverse=True
    ):
        redacted = redacted.replace(value, f"<redacted:{key}>")
    return redacted


def export_forensics(env_file: Path) -> dict[str, Any]:
    env, lock = common.validate_env_lock(env_file)
    evidence_dir = Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"])
    evidence_dir.mkdir(parents=True, exist_ok=True)
    run_context = common.load_json(Path(env["PRODUCTION_RUNTIME_RUN_CONTEXT_PATH"]))
    context = common.validate_run_context_bindings(run_context, env, lock)
    config_result = common.run_command(
        common.compose_argv(env_file, "config", "--format", "json", profile="evidence")
    )
    config = json.loads(config_result.stdout)
    common.write_json(evidence_dir / "compose.redacted.json", _redact_config(config))

    identifiers = [
        item
        for item in common.run_command(
            common.compose_argv(env_file, "ps", "--all", "--quiet")
        ).stdout.splitlines()
        if item
    ]
    containers: list[dict[str, Any]] = []
    if identifiers:
        inspected = json.loads(
            common.run_command(["docker", "inspect", *identifiers]).stdout
        )
        containers = sorted(
            (_container_summary(item, lock) for item in inspected),
            key=lambda item: item["name"],
        )
    common.write_json(evidence_dir / "containers.json", containers)

    _candidate, locked_images, _image_lock = common.load_image_lock(
        Path(env["PRODUCTION_RUNTIME_IMAGE_LOCK_PATH"])
    )
    images: list[dict[str, Any]] = []
    for key, expected in sorted(locked_images.items()):
        result = common.run_command(
            ["docker", "image", "inspect", expected["reference"]], check=False
        )
        if result.returncode:
            images.append(
                {
                    "key": key,
                    "reference": expected["reference"],
                    "status": "UNAVAILABLE",
                }
            )
            continue
        document = json.loads(result.stdout)[0]
        images.append(
            {
                "key": key,
                "reference": expected["reference"],
                "status": "PRESENT",
                "manifest_digest": expected["manifest_digest"],
                "config_digest": document["Id"],
                "layer_digests": document.get("RootFS", {}).get("Layers") or [],
                "locked_match": (
                    document["Id"] == expected["config_digest"]
                    and expected["reference"] in set(document.get("RepoDigests") or [])
                    and (document.get("RootFS", {}).get("Layers") or [])
                    == expected["layer_digests"]
                ),
            }
        )
    common.write_json(evidence_dir / "images.json", images)

    service_logs = evidence_dir / "logs"
    service_logs.mkdir(exist_ok=True)
    for service in sorted(config["services"]):
        result = common.run_command(
            common.compose_argv(
                env_file,
                "logs",
                "--no-color",
                "--timestamps",
                "--tail",
                "2000",
                service,
            ),
            check=False,
        )
        (service_logs / f"{service}.log").write_text(
            _redact_log(result.stdout + result.stderr, env),
            encoding="utf-8",
            errors="replace",
            newline="\n",
        )

    by_service = {item["service"]: item for item in containers}
    python = by_service.get("python-agent-service")
    domain = by_service.get("domain-db")
    shared_networks = (
        sorted(set(python["networks"]) & set(domain["networks"]))
        if python and domain
        else ["RUNTIME_INSPECTION_INCOMPLETE"]
    )
    forbidden_keys = sorted(
        key
        for key in (python["environment_keys"] if python else [])
        if key.startswith(("POSTGRES_", "JAVA_DB_", "DOMAIN_DB_"))
        or any(
            part in key
            for part in ("ACTIVATION_JWS", "ACTIVATION_PATH", "ACTIVATION_DIRECTORY")
        )
    )
    network_proof = {
        "schema_version": "production-runtime-network-proof.v2",
        "status": "PASS" if not shared_networks and not forbidden_keys else "FAIL",
        "python_networks": python["networks"] if python else [],
        "python_domain_shared_networks": shared_networks,
        "python_domain_or_activation_credential_keys": forbidden_keys,
    }
    common.write_json(evidence_dir / "network-proof.json", network_proof)

    files = sorted(
        path
        for path in evidence_dir.rglob("*")
        if path.is_file()
        and path.name not in {"forensic-manifest.json", "ledger.jsonl"}
        and not path.name.endswith(".append.lock")
    )
    base_manifest = {
        "schema_version": "production-runtime-forensic-manifest.v2",
        "run_id": lock["run_id"],
        "candidate_sha": lock["candidate_sha"],
        "run_context_hash": context["run_context_hash"],
        "host_lock_nonce": lock["lock_nonce"],
        "network_isolation_status": network_proof["status"],
        "locked_images_match": bool(images)
        and all(item.get("locked_match") is True for item in images),
        "files": [
            {
                "path": path.relative_to(evidence_dir).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": common.file_sha256(path),
            }
            for path in files
        ],
    }
    harness_private = ledger.load_private_key(
        Path(env["PRODUCTION_RUNTIME_SECRETS_DIR"])
        / "harness-attestation"
        / "harness.private.pem"
    )
    harness_public = ledger.load_public_key(
        Path(env["PRODUCTION_RUNTIME_PUBLIC_DIR"]) / "harness" / "harness.public.pem"
    )
    record = ledger.append_record(
        evidence_dir / "ledger.jsonl",
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
        context=context,
        source_kind="HARNESS_DIRECT",
        source_identity="production-runtime-forensic-exporter",
        case_id=None,
        payload_type="FORENSIC_EXPORT",
        payload=base_manifest,
    )
    manifest = ledger.attest_document(
        {**base_manifest, "ledger_record_hash": record["record_hash"]},
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
    )
    common.write_json(evidence_dir / "forensic-manifest.json", manifest)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        manifest = export_forensics(args.env_file)
    except (common.ProductionError, OSError, json.JSONDecodeError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
