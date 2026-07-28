from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import export_forensics  # noqa: E402
import ledger  # noqa: E402


def _expected_labels(lock: dict[str, Any]) -> dict[str, str]:
    return {
        "target-e2e.after-sale-flow.dev/run-id": lock["run_id"],
        "target-e2e.after-sale-flow.dev/project": lock["project_name"],
        "target-e2e.after-sale-flow.dev/lock-nonce": lock["lock_nonce"],
        "target-e2e.after-sale-flow.dev/image-lock-hash": lock["image_lock_hash"],
    }


def _validate_labels(
    labels: dict[str, Any], lock: dict[str, Any], context: str
) -> None:
    if any(labels.get(key) != value for key, value in _expected_labels(lock).items()):
        raise common.TargetE2EError(f"{context} labels do not match the host lock")


def _inspect_optional(kind: str, name: str) -> dict[str, Any] | None:
    result = common.run_command(["docker", kind, "inspect", name], check=False)
    if result.returncode:
        return None
    return json.loads(result.stdout)[0]


def _locked_resources(lock: dict[str, Any]) -> tuple[list[str], list[str], list[str]]:
    container_ids = [
        value
        for value in common.run_command(
            [
                "docker",
                "ps",
                "--all",
                "--filter",
                f"label=com.docker.compose.project={lock['project_name']}",
                "--format",
                "{{.ID}}",
            ]
        ).stdout.splitlines()
        if value
    ]
    if container_ids:
        containers = json.loads(
            common.run_command(["docker", "inspect", *container_ids]).stdout
        )
        for container in containers:
            labels = container["Config"].get("Labels") or {}
            _validate_labels(labels, lock, f"container {container['Id']}")
            if (
                labels.get("com.docker.compose.service")
                not in lock["resources"]["services"]
            ):
                raise common.TargetE2EError(
                    "project contains an unexpected service container"
                )
    labeled_container_ids = {
        value
        for value in common.run_command(
            [
                "docker",
                "ps",
                "--all",
                "--filter",
                f"label=target-e2e.after-sale-flow.dev/project={lock['project_name']}",
                "--format",
                "{{.ID}}",
            ]
        ).stdout.splitlines()
        if value
    }
    if labeled_container_ids != set(container_ids):
        raise common.TargetE2EError(
            "host contains a mislabeled or non-Compose container for this target E2E project"
        )

    existing_networks: list[str] = []
    for name in lock["resources"]["networks"]:
        network = _inspect_optional("network", name)
        if network is None:
            continue
        _validate_labels(network.get("Labels") or {}, lock, f"network {name}")
        existing_networks.append(name)
    labeled_networks = {
        value
        for value in common.run_command(
            [
                "docker",
                "network",
                "ls",
                "--filter",
                f"label=target-e2e.after-sale-flow.dev/project={lock['project_name']}",
                "--format",
                "{{.Name}}",
            ]
        ).stdout.splitlines()
        if value
    }
    if not labeled_networks <= set(lock["resources"]["networks"]):
        raise common.TargetE2EError(
            "host contains an unexpected network with this project label"
        )

    existing_volumes: list[str] = []
    for name in lock["resources"]["volumes"]:
        volume = _inspect_optional("volume", name)
        if volume is None:
            continue
        _validate_labels(volume.get("Labels") or {}, lock, f"volume {name}")
        existing_volumes.append(name)
    labeled_volumes = {
        value
        for value in common.run_command(
            [
                "docker",
                "volume",
                "ls",
                "--filter",
                f"label=target-e2e.after-sale-flow.dev/project={lock['project_name']}",
                "--format",
                "{{.Name}}",
            ]
        ).stdout.splitlines()
        if value
    }
    if not labeled_volumes <= set(lock["resources"]["volumes"]):
        raise common.TargetE2EError(
            "host contains an unexpected volume with this project label"
        )
    return container_ids, existing_networks, existing_volumes


def assert_no_locked_resources(lock: dict[str, Any]) -> None:
    containers, networks, volumes = _locked_resources(lock)
    if containers or networks or volumes:
        raise common.TargetE2EError(
            "locked Docker resources already exist before database bootstrap"
        )


def remove_exact_locked_resources(
    lock: dict[str, Any],
) -> tuple[list[str], list[str], list[str]]:
    container_ids, networks, volumes = _locked_resources(lock)
    if container_ids:
        common.run_command(["docker", "rm", "--force", *container_ids], timeout=120)
    for name in networks:
        common.run_command(["docker", "network", "rm", name])
    for name in volumes:
        common.run_command(["docker", "volume", "rm", name])
    return container_ids, networks, volumes


def _release_port_lock(lock: dict[str, Any], released_at: str) -> None:
    path = Path(lock["port_lock"])
    common.assert_regular_single_link(path, "gateway port lock")
    document = common.load_json(path)
    common.verify_self_hash(document, "gateway port lock")
    expected = {
        "state": "ACTIVE",
        "gateway_port": lock["gateway_port"],
        "project_name": lock["project_name"],
        "run_id": lock["run_id"],
        "lock_nonce": lock["lock_nonce"],
        "owner": lock["owner"],
    }
    if any(document.get(key) != value for key, value in expected.items()):
        raise common.TargetE2EError("gateway port lock owner or identity drifted")
    common.write_json(
        path,
        common.seal_self_hash(
            {**document, "state": "RELEASED", "released_at": released_at}
        ),
    )


def teardown(env_file: Path) -> dict[str, Any]:
    env, lock = common.validate_env_lock(env_file)
    manifest = export_forensics.export_forensics(env_file)
    container_ids, networks, volumes = remove_exact_locked_resources(lock)

    evidence_dir = Path(env["TARGET_E2E_EVIDENCE_DIR"])
    run_context = common.load_json(Path(env["TARGET_E2E_RUN_CONTEXT_PATH"]))
    context = common.validate_run_context_bindings(run_context, env, lock)
    harness_private = ledger.load_private_key(
        Path(env["TARGET_E2E_SECRETS_DIR"])
        / "harness-attestation"
        / "harness.private.pem"
    )
    harness_public = ledger.load_public_key(
        Path(env["TARGET_E2E_PUBLIC_DIR"]) / "harness" / "harness.public.pem"
    )
    base_receipt = {
        "schema_version": "target-e2e-teardown-receipt.v2",
        "status": "PASS",
        "run_id": lock["run_id"],
        "candidate_sha": lock["candidate_sha"],
        "host_lock_nonce": lock["lock_nonce"],
        "forensic_manifest_hash": manifest["self_hash"],
        "removed_container_ids": sorted(container_ids),
        "removed_networks": sorted(networks),
        "removed_volumes": sorted(volumes),
        "broad_compose_down_used": False,
    }
    record = ledger.append_record(
        evidence_dir / "ledger.jsonl",
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
        context=context,
        source_kind="HARNESS_DIRECT",
        source_identity="target-e2e-exact-resource-teardown",
        case_id=None,
        payload_type="EXACT_RESOURCE_TEARDOWN",
        payload=base_receipt,
    )
    receipt = ledger.attest_document(
        {**base_receipt, "ledger_record_hash": record["record_hash"]},
        harness_private,
        harness_public,
        key_id=f"p9-harness-{lock['candidate_sha'][:12]}",
    )
    common.write_json(evidence_dir / "teardown.json", receipt)
    released_at = common.utc_now().isoformat(timespec="milliseconds")
    _release_port_lock(lock, released_at)
    released_lock = common.seal_self_hash(
        {**lock, "state": "RELEASED", "released_at": released_at}
    )
    common.write_json(Path(env["TARGET_E2E_LOCK_PATH"]), released_lock)
    return receipt


def cleanup_incomplete_provision(lock_path: Path) -> dict[str, Any]:
    lock = common.load_run_lock(lock_path, require_active=False)
    if lock["state"] not in {"PROVISIONING", "FAILED_CLEANUP_REQUIRED"}:
        raise common.TargetE2EError(
            "incomplete-provision cleanup requires a non-active provisioning lock"
        )
    container_ids, networks, volumes = remove_exact_locked_resources(lock)
    (Path(lock["run_directory"]) / ".bootstrap.env").unlink(missing_ok=True)
    released_at = common.utc_now().isoformat(timespec="milliseconds")
    _release_port_lock(lock, released_at)
    failed_lock = common.seal_self_hash(
        {**lock, "state": "FAILED", "released_at": released_at}
    )
    common.write_json(lock_path, failed_lock)
    return {
        "schema_version": "target-e2e-incomplete-provision-cleanup.v1",
        "status": "PASS",
        "run_id": lock["run_id"],
        "removed_container_ids": sorted(container_ids),
        "removed_networks": sorted(networks),
        "removed_volumes": sorted(volumes),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--env-file", type=Path)
    source.add_argument("--lock-file", type=Path)
    args = parser.parse_args(argv)
    try:
        receipt = (
            teardown(args.env_file)
            if args.env_file is not None
            else cleanup_incomplete_provision(args.lock_file)
        )
    except (common.TargetE2EError, OSError, json.JSONDecodeError) as error:
        print(
            f"BLOCKED: teardown requires exact lock ownership and forensic export: {error}",
            file=sys.stderr,
        )
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
