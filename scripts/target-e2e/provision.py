from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


def _tool(name: str) -> str:
    found = shutil.which(name)
    if found is None:
        raise common.TargetE2EError(f"required provisioning tool is unavailable: {name}")
    return found


def _run(arguments: list[str]) -> None:
    completed = subprocess.run(arguments, check=False, capture_output=True, text=True, shell=False)
    if completed.returncode:
        raise common.TargetE2EError(
            f"provisioning command failed: {completed.stderr.strip() or completed.stdout.strip()}"
        )


def _secret() -> str:
    return secrets.token_urlsafe(32)


def _write(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(payload, encoding="ascii", newline="\n")
    if os.name != "nt":
        path.chmod(0o600)


def _generate_p256_key_pair(openssl: str, private_path: Path, public_path: Path) -> None:
    _run([openssl, "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(private_path)])
    _run([openssl, "pkey", "-in", str(private_path), "-pubout", "-out", str(public_path)])


def _generate_mtls(openssl: str, keytool: str, directory: Path, key_password: str, trust_password: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    ca_key = directory / "ca.key"
    ca_crt = directory / "ca.crt"
    server_key = directory / "server.key"
    server_csr = directory / "server.csr"
    server_crt = directory / "server.crt"
    client_key = directory / "client.key"
    client_csr = directory / "client.csr"
    client_crt = directory / "client.crt"
    server_ext = directory / "server.ext"
    client_ext = directory / "client.ext"

    _run([openssl, "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(ca_key)])
    _run([openssl, "req", "-x509", "-new", "-sha256", "-key", str(ca_key), "-days", "2", "-subj", "/CN=aflow-target-e2e-ca", "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-out", str(ca_crt)])
    _run([openssl, "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(server_key)])
    _run([openssl, "req", "-new", "-sha256", "-key", str(server_key), "-subj", "/CN=graph-mtls-proxy", "-out", str(server_csr)])
    _write(server_ext, "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:graph-mtls-proxy\n")
    _run([openssl, "x509", "-req", "-sha256", "-in", str(server_csr), "-CA", str(ca_crt), "-CAkey", str(ca_key), "-CAcreateserial", "-days", "1", "-extfile", str(server_ext), "-out", str(server_crt)])
    _run([openssl, "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(client_key)])
    _run([openssl, "req", "-new", "-sha256", "-key", str(client_key), "-subj", "/CN=java-api-service", "-out", str(client_csr)])
    _write(client_ext, "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=clientAuth\nsubjectAltName=URI:spiffe://after-sale-flow/java-api-service\n")
    _run([openssl, "x509", "-req", "-sha256", "-in", str(client_csr), "-CA", str(ca_crt), "-CAkey", str(ca_key), "-CAcreateserial", "-days", "1", "-extfile", str(client_ext), "-out", str(client_crt)])
    _run([openssl, "pkcs12", "-export", "-name", "java-agent-worker", "-inkey", str(client_key), "-in", str(client_crt), "-certfile", str(ca_crt), "-out", str(directory / "client.p12"), "-passout", f"pass:{key_password}"])
    _run([keytool, "-importcert", "-noprompt", "-alias", "target-e2e-ca", "-file", str(ca_crt), "-keystore", str(directory / "trust.p12"), "-storetype", "PKCS12", "-storepass", trust_password])
    for transient in (ca_key, server_csr, client_csr, server_ext, client_ext, directory / "ca.srl"):
        transient.unlink(missing_ok=True)
    if os.name != "nt":
        for secret_path in (server_key, client_key, directory / "client.p12", directory / "trust.p12"):
            secret_path.chmod(0o600)


def _registry(build_id: str) -> tuple[dict[str, Any], str]:
    binding: dict[str, Any] = {
        "graph_key": "intake.v2",
        "graph_version": "2.0.0",
        "checkpoint_schema_version": "intake-checkpoint.v2",
        "state_schema_version": "intake-graph-state.v2",
        "state_schema_hash": hashlib.sha256(b"target-e2e:intake-state:v2").hexdigest(),
        "command_schema_version": "room-graph-command.v1",
        "result_schema_version": "room-graph-result.v1",
        "agent_profile_id": "intake-agent.target-e2e.v1",
        "prompt_version": "intake-prompt.target-e2e.v1",
        "model_profile_id": "target-e2e.contract-blocked",
        "output_schema_version": "intake-turn-proposal.v2",
        "policy_version": "intake-policy.target-e2e.v1",
        "guardrail_version": "intake-guardrail.target-e2e.v1",
        "tool_policy_version": "no-tools.v1",
        "code_build_id": build_id,
        "allowed_room_types": ["INTAKE"],
        "allowed_stage_codes": ["INTAKE_MESSAGE", "INTAKE_CONFIRM"],
    }
    binding_hash = common.canonical_sha256(binding)
    binding["binding_hash"] = binding_hash
    return binding, binding_hash


def provision(image_lock: Path, runtime_root: Path, run_id: str | None) -> Path:
    openssl = _tool("openssl")
    keytool = _tool("keytool")
    build_id, images = common.load_image_lock(image_lock)
    repository_head = common.run_command(["git", "rev-parse", "HEAD"]).stdout.strip()
    if build_id != repository_head:
        raise common.TargetE2EError("image lock build_id is not the checked-out candidate commit")
    selected_run_id = run_id or f"p9-{secrets.token_hex(6)}"
    if not common.RUN_ID.fullmatch(selected_run_id):
        raise common.TargetE2EError("run ID must be a bounded lowercase DNS label")
    root = common.assert_external_runtime_path(runtime_root) / selected_run_id
    if root.exists():
        raise common.TargetE2EError(f"refusing to reuse existing runtime directory: {root}")
    secrets_dir = root / "secrets"
    activation_dir = root / "activation"
    evidence_dir = root / "evidence"
    for directory in (secrets_dir, activation_dir, evidence_dir):
        directory.mkdir(parents=True, exist_ok=False)

    graph_key_id = f"p9-{build_id[:12]}"
    graph_private = secrets_dir / "graph-signing-keys" / f"{graph_key_id}.private.pem"
    graph_public = secrets_dir / "graph-signing-keys" / f"{graph_key_id}.public.pem"
    graph_private.parent.mkdir()
    _generate_p256_key_pair(openssl, graph_private, graph_public)
    public_copy = secrets_dir / "graph-public-keys" / graph_public.name
    public_copy.parent.mkdir()
    shutil.copyfile(graph_public, public_copy)

    activation_private = secrets_dir / "activation-signing" / "activation.private.pem"
    activation_public = activation_dir / "activation.public.pem"
    activation_private.parent.mkdir()
    _generate_p256_key_pair(openssl, activation_private, activation_public)

    key_password = _secret()
    trust_password = _secret()
    _generate_mtls(openssl, keytool, secrets_dir / "mtls", key_password, trust_password)

    binding, registry_hash = _registry(build_id)
    registry_seed = {
        "schema_version": "target-e2e-graph-registry-seed.v1",
        "build_id": build_id,
        "run_id": selected_run_id,
        "bindings": [binding],
        "registry_hash": registry_hash,
    }
    registry_path = activation_dir / "registry-seed.json"
    common.write_json(registry_path, registry_seed)
    _run(
        [
            openssl,
            "dgst",
            "-sha256",
            "-sign",
            str(graph_private),
            "-out",
            str(activation_dir / "registry-seed.sig"),
            str(registry_path),
        ]
    )

    now = dt.datetime.now(dt.timezone.utc)
    expires = now + dt.timedelta(minutes=30)
    activation_key = secrets.token_bytes(32)
    activation = {
        "schema_version": "target-e2e-short-lived-activation.v1",
        "activation_id": f"activation-{secrets.token_hex(12)}",
        "run_id": selected_run_id,
        "build_id": build_id,
        "not_before": now.isoformat(timespec="seconds"),
        "expires_at": expires.isoformat(timespec="seconds"),
        "maximum_cases": 1,
        "required_worker_lane": "candidate",
        "required_allocation": "TEMPORAL",
        "registry_hash": registry_hash,
        "application_contract_status": "BLOCKING_UNTIL_CONSUMED_AND_RECEIPTED",
    }
    activation["integrity_hmac_sha256"] = hmac.new(
        activation_key,
        json.dumps(activation, ensure_ascii=True, separators=(",", ":"), sort_keys=True).encode("ascii"),
        hashlib.sha256,
    ).hexdigest()
    activation_path = activation_dir / "activation-input.json"
    common.write_json(activation_path, activation)
    _run(
        [
            openssl,
            "dgst",
            "-sha256",
            "-sign",
            str(activation_private),
            "-out",
            str(activation_dir / "activation-input.sig"),
            str(activation_path),
        ]
    )
    activation_hmac_path = secrets_dir / "activation.hmac.key"
    activation_hmac_path.write_bytes(activation_key)
    if os.name != "nt":
        activation_hmac_path.chmod(0o600)

    graph_generation = f"p9-{selected_run_id}-{build_id[:12]}"
    restore_hash = hashlib.sha256(f"{selected_run_id}:{build_id}:restore".encode()).hexdigest()
    environment = {
        "TARGET_E2E_RUN_ID": selected_run_id,
        "TARGET_E2E_BUILD_ID": build_id,
        "TARGET_E2E_SOURCE_COMMIT": build_id,
        "TARGET_E2E_SECRETS_DIR": secrets_dir.as_posix(),
        "TARGET_E2E_ACTIVATION_DIR": activation_dir.as_posix(),
        "TARGET_E2E_EVIDENCE_DIR": evidence_dir.as_posix(),
        "TARGET_E2E_GATEWAY_PORT": "25180",
        "TARGET_E2E_TEMPORAL_NAMESPACE": f"target-e2e-{selected_run_id}",
        "TARGET_E2E_DOMAIN_ADMIN_PASSWORD": _secret(),
        "TARGET_E2E_DOMAIN_APP_PASSWORD": _secret(),
        "TARGET_E2E_GRAPH_ADMIN_PASSWORD": _secret(),
        "TARGET_E2E_GRAPH_MIGRATOR_PASSWORD": _secret(),
        "TARGET_E2E_GRAPH_RUNTIME_PASSWORD": _secret(),
        "TARGET_E2E_GRAPH_RETENTION_PASSWORD": _secret(),
        "TARGET_E2E_TEMPORAL_ADMIN_PASSWORD": _secret(),
        "TARGET_E2E_TEMPORAL_DB_PASSWORD": _secret(),
        "TARGET_E2E_REDIS_PASSWORD": _secret(),
        "TARGET_E2E_MINIO_ROOT_USER": f"e2e{secrets.token_hex(8)}",
        "TARGET_E2E_MINIO_ROOT_PASSWORD": _secret(),
        "TARGET_E2E_JAVA_SERVICE_SECRET": _secret(),
        "TARGET_E2E_PYTHON_SERVICE_SECRET": _secret(),
        "TARGET_E2E_OCR_SERVICE_SECRET": _secret(),
        "TARGET_E2E_LOCAL_MODEL_KEY": _secret(),
        "TARGET_E2E_LOCAL_OBSERVABILITY_KEY": _secret(),
        "TARGET_E2E_GRAPH_ENVIRONMENT_GENERATION": graph_generation,
        "TARGET_E2E_GRAPH_RESTORE_HASH": restore_hash,
        "TARGET_E2E_GRAPH_SIGNING_KEY_ID": graph_key_id,
        "TARGET_E2E_GRAPH_REGISTRY_HASH": registry_hash,
        "TARGET_E2E_GRAPH_SHADOW_BINDINGS": json.dumps([binding], separators=(",", ":"), sort_keys=True),
        "TARGET_E2E_MTLS_KEYSTORE_PASSWORD": key_password,
        "TARGET_E2E_MTLS_TRUSTSTORE_PASSWORD": trust_password,
    }
    for key, value in images.items():
        environment[f"TARGET_E2E_{key.upper()}_IMAGE"] = value

    env_path = root / "target-e2e.env"
    env_lines = [f"{key}={common.env_quote(value)}" for key, value in sorted(environment.items())]
    _write(env_path, "\n".join(env_lines) + "\n")
    common.write_json(
        root / "provisioning-receipt.json",
        {
            "schema_version": "target-e2e-provisioning-receipt.v1",
            "run_id": selected_run_id,
            "build_id": build_id,
            "image_lock_sha256": common.file_sha256(image_lock),
            "registry_hash": registry_hash,
            "activation_expires_at": activation["expires_at"],
            "secrets_committed": False,
        },
    )
    return env_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image-lock", type=Path, required=True)
    parser.add_argument(
        "--runtime-root",
        type=Path,
        default=Path(tempfile.gettempdir()) / "aflow-target-e2e",
    )
    parser.add_argument("--run-id")
    args = parser.parse_args(argv)
    try:
        env_path = provision(args.image_lock, args.runtime_root, args.run_id)
    except common.TargetE2EError as error:
        parser.error(str(error))
    print(env_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
