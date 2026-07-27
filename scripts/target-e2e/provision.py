from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

from cryptography import x509
from cryptography.hazmat.primitives import hashes

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ledger  # noqa: E402


def _tool(name: str) -> str:
    found = shutil.which(name)
    if found is None:
        raise common.TargetE2EError(
            f"required provisioning tool is unavailable: {name}"
        )
    return found


def _run(arguments: list[str]) -> None:
    completed = subprocess.run(
        arguments,
        check=False,
        capture_output=True,
        text=True,
        shell=False,
    )
    if completed.returncode:
        raise common.TargetE2EError(
            f"provisioning command failed: {completed.stderr.strip() or completed.stdout.strip()}"
        )


def _secret() -> str:
    return secrets.token_urlsafe(32)


def _write_private(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(payload, encoding="ascii", newline="\n")
    if os.name != "nt":
        path.chmod(0o600)


def _generate_p256_key_pair(
    openssl: str, private_path: Path, public_path: Path
) -> None:
    private_path.parent.mkdir(parents=True, exist_ok=True)
    public_path.parent.mkdir(parents=True, exist_ok=True)
    _run(
        [
            openssl,
            "genpkey",
            "-algorithm",
            "EC",
            "-pkeyopt",
            "ec_paramgen_curve:P-256",
            "-out",
            str(private_path),
        ]
    )
    _run(
        [
            openssl,
            "pkey",
            "-in",
            str(private_path),
            "-pubout",
            "-out",
            str(public_path),
        ]
    )


def _generate_mtls(
    openssl: str,
    keytool: str,
    directory: Path,
    key_password: str,
    trust_password: str,
) -> None:
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

    _run(
        [
            openssl,
            "ecparam",
            "-name",
            "prime256v1",
            "-genkey",
            "-noout",
            "-out",
            str(ca_key),
        ]
    )
    _run(
        [
            openssl,
            "req",
            "-x509",
            "-new",
            "-sha256",
            "-key",
            str(ca_key),
            "-days",
            "2",
            "-subj",
            "/CN=aflow-target-e2e-ca",
            "-addext",
            "basicConstraints=critical,CA:TRUE",
            "-addext",
            "keyUsage=critical,keyCertSign,cRLSign",
            "-out",
            str(ca_crt),
        ]
    )
    _run(
        [
            openssl,
            "ecparam",
            "-name",
            "prime256v1",
            "-genkey",
            "-noout",
            "-out",
            str(server_key),
        ]
    )
    _run(
        [
            openssl,
            "req",
            "-new",
            "-sha256",
            "-key",
            str(server_key),
            "-subj",
            "/CN=graph-mtls-proxy",
            "-out",
            str(server_csr),
        ]
    )
    _write_private(
        server_ext,
        "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:graph-mtls-proxy\n",
    )
    _run(
        [
            openssl,
            "x509",
            "-req",
            "-sha256",
            "-in",
            str(server_csr),
            "-CA",
            str(ca_crt),
            "-CAkey",
            str(ca_key),
            "-CAcreateserial",
            "-days",
            "1",
            "-extfile",
            str(server_ext),
            "-out",
            str(server_crt),
        ]
    )
    _run(
        [
            openssl,
            "ecparam",
            "-name",
            "prime256v1",
            "-genkey",
            "-noout",
            "-out",
            str(client_key),
        ]
    )
    _run(
        [
            openssl,
            "req",
            "-new",
            "-sha256",
            "-key",
            str(client_key),
            "-subj",
            "/CN=java-api-service",
            "-out",
            str(client_csr),
        ]
    )
    _write_private(
        client_ext,
        "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=clientAuth\nsubjectAltName=URI:spiffe://after-sale-flow/java-api-service\n",
    )
    _run(
        [
            openssl,
            "x509",
            "-req",
            "-sha256",
            "-in",
            str(client_csr),
            "-CA",
            str(ca_crt),
            "-CAkey",
            str(ca_key),
            "-CAcreateserial",
            "-days",
            "1",
            "-extfile",
            str(client_ext),
            "-out",
            str(client_crt),
        ]
    )
    _run(
        [
            openssl,
            "pkcs12",
            "-export",
            "-name",
            "java-agent-worker",
            "-inkey",
            str(client_key),
            "-in",
            str(client_crt),
            "-certfile",
            str(ca_crt),
            "-out",
            str(directory / "client.p12"),
            "-passout",
            f"pass:{key_password}",
        ]
    )
    _run(
        [
            keytool,
            "-importcert",
            "-noprompt",
            "-alias",
            "target-e2e-ca",
            "-file",
            str(ca_crt),
            "-keystore",
            str(directory / "trust.p12"),
            "-storetype",
            "PKCS12",
            "-storepass",
            trust_password,
        ]
    )
    for transient in (
        ca_key,
        server_csr,
        client_csr,
        server_ext,
        client_ext,
        directory / "ca.srl",
    ):
        transient.unlink(missing_ok=True)
    if os.name != "nt":
        for secret_path in (
            server_key,
            client_key,
            directory / "client.p12",
            directory / "trust.p12",
        ):
            secret_path.chmod(0o600)


def _b64url_integer(value: int) -> str:
    return (
        base64.urlsafe_b64encode(value.to_bytes(32, "big")).rstrip(b"=").decode("ascii")
    )


def _write_static_jwks(public_key_path: Path, destination: Path, key_id: str) -> None:
    public_key = ledger.load_public_key(public_key_path)
    numbers = public_key.public_numbers()
    common.write_json(
        destination,
        {
            "keys": [
                {
                    "alg": "ES256",
                    "crv": "P-256",
                    "kid": key_id,
                    "kty": "EC",
                    "use": "sig",
                    "x": _b64url_integer(numbers.x),
                    "y": _b64url_integer(numbers.y),
                }
            ]
        },
    )
    if os.name != "nt":
        destination.chmod(0o444)


def _target_binding(candidate: str) -> tuple[dict[str, Any], str]:
    binding = {
        "graph_key": "all-rooms.target-e2e.v1",
        "graph_version": "target-e2e-graph.2026-07-27.1",
        "checkpoint_schema_version": "target-e2e-checkpoint.v1",
        "state_schema_version": "target-e2e-graph-state.v1",
        "state_schema_hash": hashlib.sha256(
            b"target-e2e:all-rooms-graph-state:v1"
        ).hexdigest(),
        "command_schema_version": "room-graph-command.v1",
        "result_schema_version": "room-graph-result.v1",
        "agent_profile_id": "all-rooms-agent.target-e2e.v1",
        "prompt_version": "all-rooms-prompt.target-e2e.v1",
        "model_profile_id": "target-e2e.contract-blocked",
        "output_schema_version": "target-e2e-room-proposal-source.v1",
        "policy_version": "all-rooms-policy.target-e2e.v1",
        "guardrail_version": "all-rooms-guardrail.target-e2e.v1",
        "tool_policy_version": "tools.none.v1",
        "code_build_id": f"p9-graph-{candidate[:8]}",
        "allowed_room_types": ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"],
        "allowed_stage_codes": [
            "INTAKE_MESSAGE",
            "EVIDENCE_SEAL",
            "HEARING_DELIBERATION",
            "REVIEW_OUTCOME",
        ],
    }
    binding_hash = common.canonical_sha256(binding)
    return {**binding, "binding_hash": binding_hash}, binding_hash


def _current_shadow_binding(candidate: str, binding_hash: str) -> dict[str, Any]:
    return {
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
        "binding_hash": binding_hash,
        "code_build_id": f"p9-graph-{candidate[:8]}",
        "allowed_room_types": ["INTAKE"],
        "allowed_stage_codes": ["INTAKE_MESSAGE"],
    }


def _reserve_host_lock(
    runtime_root: Path,
    run_id: str,
    candidate: str,
    image_lock_hash: str,
    gateway_port: int,
) -> tuple[Path, Path, dict[str, Any]]:
    locks = runtime_root / ".locks"
    locks.mkdir(parents=True, exist_ok=True)
    coordinator = locks / "provision.coordinator"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        coordinator_fd = os.open(coordinator, flags, 0o600)
    except FileExistsError as error:
        raise common.TargetE2EError(
            "another target E2E provisioning operation owns the host lock"
        ) from error
    os.close(coordinator_fd)
    try:
        project_name = f"aflow-target-e2e-{run_id}"
        run_directory = runtime_root / run_id
        lock_nonce = secrets.token_hex(32)
        run_lock_path = locks / f"{project_name}.lock.json"
        port_lock_path = locks / f"gateway-{gateway_port}.lock.json"
        now = common.utc_now().isoformat(timespec="milliseconds")
        port_lock = common.seal_self_hash(
            {
                "schema_version": "target-e2e-port-lock.v1",
                "state": "ACTIVE",
                "gateway_port": gateway_port,
                "project_name": project_name,
                "run_id": run_id,
                "lock_nonce": lock_nonce,
                "owner": common.current_owner(),
                "created_at": now,
                "released_at": None,
            }
        )
        if port_lock_path.exists():
            common.assert_regular_single_link(
                port_lock_path, "existing gateway port lock"
            )
            prior_port_lock = common.load_json(port_lock_path)
            common.verify_self_hash(prior_port_lock, "existing gateway port lock")
            prior_run_id = prior_port_lock.get("run_id")
            if (
                set(prior_port_lock) != common.PORT_LOCK_KEYS
                or prior_port_lock.get("schema_version") != "target-e2e-port-lock.v1"
                or prior_port_lock.get("gateway_port") != gateway_port
                or not isinstance(prior_run_id, str)
                or not common.RUN_ID.fullmatch(prior_run_id)
                or prior_port_lock.get("project_name")
                != f"aflow-target-e2e-{prior_run_id}"
                or not isinstance(prior_port_lock.get("lock_nonce"), str)
                or not common.SHA256.fullmatch(prior_port_lock["lock_nonce"])
            ):
                raise common.TargetE2EError("existing gateway port lock is malformed")
            if prior_port_lock["state"] not in {"RELEASED", "FAILED"}:
                raise common.TargetE2EError(
                    f"gateway port {gateway_port} is owned by an active target E2E run"
                )
            history = locks / "history"
            history.mkdir(exist_ok=True)
            archive = (
                history
                / f"gateway-{gateway_port}-{prior_port_lock['self_hash']}.lock.json"
            )
            common.atomic_create_json(archive, prior_port_lock)
            replacement = locks / f"gateway-{gateway_port}.{lock_nonce}.reserve"
            common.atomic_create_json(replacement, port_lock)
            replacement.replace(port_lock_path)
        else:
            common.atomic_create_json(port_lock_path, port_lock)
        run_lock = common.seal_self_hash(
            {
                "schema_version": "target-e2e-host-lock.v1",
                "state": "PROVISIONING",
                "project_name": project_name,
                "run_id": run_id,
                "runtime_root": runtime_root.as_posix(),
                "run_directory": run_directory.as_posix(),
                "env_file": (run_directory / "target-e2e.env").as_posix(),
                "lock_nonce": lock_nonce,
                "owner": common.current_owner(),
                "candidate_sha": candidate,
                "image_lock_hash": image_lock_hash,
                "gateway_port": gateway_port,
                "port_lock": port_lock_path.as_posix(),
                "resources": common.expected_resource_names(run_id),
                "ledger_public_key_sha256": "",
                "created_at": now,
                "released_at": None,
            }
        )
        try:
            common.atomic_create_json(run_lock_path, run_lock)
        except BaseException:
            failed_port = common.seal_self_hash(
                {
                    **port_lock,
                    "state": "FAILED",
                    "released_at": common.utc_now().isoformat(timespec="milliseconds"),
                }
            )
            common.write_json(port_lock_path, failed_port)
            raise
        if run_directory.exists():
            failed = common.seal_self_hash({**run_lock, "state": "FAILED"})
            common.write_json(run_lock_path, failed)
            common.write_json(
                port_lock_path,
                common.seal_self_hash(
                    {
                        **port_lock,
                        "state": "FAILED",
                        "released_at": common.utc_now().isoformat(
                            timespec="milliseconds"
                        ),
                    }
                ),
            )
            raise common.TargetE2EError(
                "run directory already exists and is permanently non-reusable"
            )
        return run_lock_path, port_lock_path, run_lock
    finally:
        coordinator.unlink(missing_ok=True)


def _manifest_digest(images: dict[str, dict[str, Any]], key: str) -> str:
    return str(images[key]["manifest_digest"])


def provision(
    image_lock: Path,
    runtime_root: Path,
    run_id: str | None,
    gateway_port: int,
) -> Path:
    openssl = _tool("openssl")
    keytool = _tool("keytool")
    candidate, images, image_lock_document = common.load_image_lock(image_lock)
    repository_head = common.run_command(["git", "rev-parse", "HEAD"]).stdout.strip()
    if candidate != repository_head:
        raise common.TargetE2EError(
            "image lock candidate is not the checked-out candidate commit"
        )
    selected_run_id = run_id or f"p9-{secrets.token_hex(6)}"
    if not common.RUN_ID.fullmatch(selected_run_id):
        raise common.TargetE2EError("run ID must be a bounded lowercase DNS label")
    if gateway_port < 25180 or gateway_port > 25999:
        raise common.TargetE2EError(
            "gateway port must be inside the reserved target E2E range"
        )
    shared_root = common.assert_external_runtime_path(runtime_root)
    run_lock_path, _port_lock_path, run_lock = _reserve_host_lock(
        shared_root,
        selected_run_id,
        candidate,
        image_lock_document["self_hash"],
        gateway_port,
    )
    root = shared_root / selected_run_id
    try:
        secrets_dir = root / "secrets"
        activation_dir = root / "java-activation"
        evidence_dir = root / "evidence"
        public_dir = root / "public"
        socket_dir = root / "python-socket"
        for directory in (
            secrets_dir,
            activation_dir,
            evidence_dir / "inbox",
            public_dir,
            socket_dir,
        ):
            directory.mkdir(parents=True, exist_ok=False)
        if os.name != "nt":
            socket_dir.chmod(0o777)

        graph_key_id = f"p9-graph-{candidate[:12]}"
        graph_private = (
            secrets_dir / "graph-signing-keys" / f"{graph_key_id}.private.pem"
        )
        graph_public = secrets_dir / "graph-signing-keys" / f"{graph_key_id}.public.pem"
        _generate_p256_key_pair(openssl, graph_private, graph_public)
        public_graph_key = public_dir / "graph-keys" / graph_public.name
        public_graph_key.parent.mkdir(parents=True)
        shutil.copyfile(graph_public, public_graph_key)
        jwks_path = public_dir / "jwks" / "graph-jwks.json"
        jwks_path.parent.mkdir(parents=True)
        _write_static_jwks(public_graph_key, jwks_path, graph_key_id)

        activation_key_id = f"p9-java-activation-{candidate[:12]}"
        activation_private_path = (
            secrets_dir / "activation-signing" / "activation.private.pem"
        )
        activation_public_path = activation_dir / "activation.public.pem"
        _generate_p256_key_pair(
            openssl, activation_private_path, activation_public_path
        )
        activation_private = ledger.load_private_key(activation_private_path)

        harness_key_id = f"p9-harness-{candidate[:12]}"
        harness_private_path = (
            secrets_dir / "harness-attestation" / "harness.private.pem"
        )
        harness_public_path = public_dir / "harness" / "harness.public.pem"
        _generate_p256_key_pair(openssl, harness_private_path, harness_public_path)
        harness_private = ledger.load_private_key(harness_private_path)
        harness_public = ledger.load_public_key(harness_public_path)
        harness_fingerprint = ledger.public_key_sha256(harness_public)

        key_password = _secret()
        trust_password = _secret()
        mtls_dir = secrets_dir / "mtls"
        _generate_mtls(openssl, keytool, mtls_dir, key_password, trust_password)
        public_mtls = public_dir / "mtls"
        public_mtls.mkdir()
        shutil.copyfile(mtls_dir / "ca.crt", public_mtls / "ca.crt")
        client_certificate = x509.load_pem_x509_certificate(
            (mtls_dir / "client.crt").read_bytes()
        )
        client_fingerprint = client_certificate.fingerprint(hashes.SHA256()).hex()
        ca_certificate = x509.load_pem_x509_certificate(
            (mtls_dir / "ca.crt").read_bytes()
        )
        ca_fingerprint = ca_certificate.fingerprint(hashes.SHA256()).hex()

        target_binding, binding_hash = _target_binding(candidate)
        current_shadow_binding = _current_shadow_binding(candidate, binding_hash)
        registry_seed = common.seal_self_hash(
            {
                "schema_version": "target-e2e-graph-registry-seed.v2",
                "candidate_sha": candidate,
                "run_id": selected_run_id,
                "bindings": [target_binding],
                "registry_hash": binding_hash,
            }
        )
        common.write_json(activation_dir / "registry-seed.json", registry_seed)

        now = common.utc_now()
        expires = now + dt.timedelta(minutes=30)
        activation_id = f"p9act.v1.{secrets.token_hex(16)}"
        run_nonce = f"p9-nonce-{secrets.token_hex(16)}"
        environment_id = f"p9-isolated-{selected_run_id}"
        environment_generation = int(now.timestamp())
        tenant_surrogate = f"tenant-{selected_run_id}"
        project_name = run_lock["project_name"]
        temporal_namespace = f"after-sale-flow-p9-{selected_run_id}"
        build_bindings = {
            "caseBuildId": f"p9-case-{candidate[:8]}",
            "controlBuildId": f"p9-control-{candidate[:8]}",
            "agentBuildId": f"p9-agent-{candidate[:8]}",
        }
        image_digests = {
            "javaApi": _manifest_digest(images, "java"),
            "temporalControlWorker": _manifest_digest(images, "java"),
            "temporalAgentWorker": _manifest_digest(images, "java"),
            "pythonAgent": _manifest_digest(images, "python"),
            "frontend": _manifest_digest(images, "frontend"),
        }
        domain_identity = {
            "clusterIdentity": f"{selected_run_id}-domain-cluster",
            "databaseIdentity": f"{selected_run_id}-domain-db",
            "runtimePrincipalIdentity": f"{selected_run_id}-java-domain-runtime",
        }
        graph_identity = {
            "clusterIdentity": f"{selected_run_id}-graph-cluster",
            "databaseIdentity": f"{selected_run_id}-graph-db",
            "runtimePrincipalIdentity": f"{selected_run_id}-python-graph-runtime",
        }
        case_scope = {
            "mode": "ISOLATED_SYNTHETIC_NEW_CASES",
            "caseIdPrefix": "CASE_P9_SYNTHETIC_",
            "maxCases": 4,
            "fixtureSetId": "p9-synthetic-all-rooms-001",
            "fixtureSetHash": hashlib.sha256(
                f"{candidate}:{selected_run_id}:fixture-set".encode("ascii")
            ).hexdigest(),
            "containsRealCaseOrPartyData": False,
            "externalEffectsAllowed": False,
        }
        activation_manifest: dict[str, Any] = {
            "contractVersion": "target-e2e-activation.v1",
            "activationId": activation_id,
            "executionLane": "TARGET_E2E_CANDIDATE",
            "environmentId": environment_id,
            "environmentGeneration": environment_generation,
            "candidateSha": candidate,
            "issuedAt": now.isoformat(timespec="seconds"),
            "expiresAt": expires.isoformat(timespec="seconds"),
            "nonce": run_nonce,
            "tenantSurrogate": tenant_surrogate,
            "caseScope": case_scope,
            "allowedRoomTypes": ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"],
            "buildBindings": build_bindings,
            "graphBinding": {
                "key": target_binding["graph_key"],
                "version": target_binding["graph_version"],
                "checkpointSchemaVersion": target_binding[
                    "checkpoint_schema_version"
                ],
                "bindingHash": binding_hash,
                "codeBuildId": target_binding["code_build_id"],
            },
            "imageDigests": image_digests,
            "temporalNamespace": temporal_namespace,
            "databaseIdentities": {"domain": domain_identity, "graph": graph_identity},
            "authority": {
                "environmentClass": "ISOLATED_PREPRODUCTION",
                "graphOutputAuthority": "PROPOSAL_ONLY",
                "graphDomainCredentialsPresent": False,
                "graphDomainWriteAllowed": False,
                "formalWriter": "JAVA_FINALIZER_ONLY",
                "javaDomainCommitAllowed": True,
                "externalEffectsAllowed": False,
                "productionTrafficAllowed": False,
                "productionPromotionAuthority": False,
                "migrationPromotionAuthority": False,
            },
            "productionDefaults": {
                "formalCaseSelector": "LEGACY",
                "targetE2EActivation": "DISABLED",
            },
        }
        activation_manifest["manifestHash"] = common.canonical_sha256(
            activation_manifest
        )
        common.write_json(
            activation_dir / "activation-manifest.json", activation_manifest
        )
        activation_jws = ledger.sign_compact_jws(
            activation_manifest,
            activation_private,
            key_id=activation_key_id,
            typ="target-e2e-activation+jwt",
        )
        _write_private(activation_dir / "activation.jws", activation_jws + "\n")

        graph_generation = environment_generation
        restore_hash = hashlib.sha256(
            f"{selected_run_id}:{candidate}:restore".encode("ascii")
        ).hexdigest()
        runtime_context = {
            "schemaVersion": "graph-target-e2e-runtime-context.v1",
            "executionLane": "TARGET_E2E_CANDIDATE",
            "activationId": activation_id,
            "environmentId": environment_id,
            "environmentGeneration": environment_generation,
            "candidateSha": candidate,
            "issuedAt": activation_manifest["issuedAt"],
            "expiresAt": activation_manifest["expiresAt"],
            "runNonce": run_nonce,
            "tenantSurrogate": tenant_surrogate,
            "caseScope": case_scope,
            "allowedRoomTypes": ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"],
            "composeProject": project_name,
            "temporalNamespace": temporal_namespace,
            "buildBindings": build_bindings,
            "imageDigests": image_digests,
            "databaseIdentities": {
                "domain": {
                    "service": "domain-db",
                    "database": "target_domain",
                    "schema": "public",
                    "expectedUser": "domain_app",
                },
                "graph": {
                    "service": "graph-db",
                    "database": "target_graph",
                    "schema": "graph_runtime",
                    "runtimeUser": "graph_runtime",
                    "environmentGeneration": graph_generation,
                    "restoreVerificationHash": restore_hash,
                },
            },
            "trustedSigningKeyIds": [graph_key_id],
            "perCommandManifestAllowed": False,
        }
        executor_bindings = [target_binding]
        image_lock_snapshot = root / "image-lock.snapshot.json"
        common.write_json(image_lock_snapshot, image_lock_document)
        run_context = common.seal_self_hash(
            {
                "schema_version": "target-e2e-run-context.v2",
                "runtime_projection": runtime_context,
                "executor_bindings": executor_bindings,
                "current_shadow_binding": current_shadow_binding,
                "activation_manifest_hash": activation_manifest["manifestHash"],
                "image_lock_hash": image_lock_document["self_hash"],
                "image_lock_path": image_lock_snapshot.as_posix(),
                "resources": run_lock["resources"],
                "mtls": {
                    "ca_certificate_sha256": ca_fingerprint,
                    "client_certificate_sha256": client_fingerprint,
                    "expected_spiffe_id": "spiffe://after-sale-flow/java-api-service",
                },
                "jwks_sha256": common.file_sha256(jwks_path),
                "ledger_public_key_sha256": harness_fingerprint,
                "lock_nonce": run_lock["lock_nonce"],
            }
        )
        run_context_path = root / "run-context.json"
        common.write_json(run_context_path, run_context)

        environment = {
            "TARGET_E2E_RUN_ID": selected_run_id,
            "TARGET_E2E_PROJECT_NAME": project_name,
            "TARGET_E2E_BUILD_ID": candidate,
            "TARGET_E2E_SOURCE_COMMIT": candidate,
            "TARGET_E2E_LOCK_PATH": run_lock_path.as_posix(),
            "TARGET_E2E_LOCK_NONCE": run_lock["lock_nonce"],
            "TARGET_E2E_IMAGE_LOCK_PATH": image_lock_snapshot.as_posix(),
            "TARGET_E2E_IMAGE_LOCK_HASH": image_lock_document["self_hash"],
            "TARGET_E2E_RUN_CONTEXT_PATH": run_context_path.as_posix(),
            "TARGET_E2E_RUN_CONTEXT_HASH": run_context["self_hash"],
            "TARGET_E2E_SECRETS_DIR": secrets_dir.as_posix(),
            "TARGET_E2E_PUBLIC_DIR": public_dir.as_posix(),
            "TARGET_E2E_ACTIVATION_DIR": activation_dir.as_posix(),
            "TARGET_E2E_EVIDENCE_DIR": evidence_dir.as_posix(),
            "TARGET_E2E_SOCKET_DIR": socket_dir.as_posix(),
            "TARGET_E2E_GATEWAY_PORT": str(gateway_port),
            "TARGET_E2E_TEMPORAL_NAMESPACE": temporal_namespace,
            "TARGET_E2E_ENVIRONMENT_ID": environment_id,
            "TARGET_E2E_ENVIRONMENT_GENERATION": str(environment_generation),
            "TARGET_E2E_ACTIVATION_ID": activation_id,
            "TARGET_E2E_RUN_NONCE": run_nonce,
            "TARGET_E2E_TENANT_SURROGATE": tenant_surrogate,
            "TARGET_E2E_DOMAIN_CLUSTER_IDENTITY": domain_identity["clusterIdentity"],
            "TARGET_E2E_DOMAIN_DATABASE_IDENTITY": domain_identity["databaseIdentity"],
            "TARGET_E2E_DOMAIN_PRINCIPAL_IDENTITY": domain_identity[
                "runtimePrincipalIdentity"
            ],
            "TARGET_E2E_GRAPH_CLUSTER_IDENTITY": graph_identity["clusterIdentity"],
            "TARGET_E2E_GRAPH_DATABASE_IDENTITY": graph_identity["databaseIdentity"],
            "TARGET_E2E_GRAPH_PRINCIPAL_IDENTITY": graph_identity[
                "runtimePrincipalIdentity"
            ],
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
            "TARGET_E2E_GRAPH_ENVIRONMENT_GENERATION": str(graph_generation),
            "TARGET_E2E_GRAPH_RESTORE_HASH": restore_hash,
            "TARGET_E2E_GRAPH_SIGNING_KEY_ID": graph_key_id,
            "TARGET_E2E_GRAPH_REGISTRY_HASH": binding_hash,
            "TARGET_E2E_GRAPH_SHADOW_BINDINGS": json.dumps(
                [current_shadow_binding], separators=(",", ":"), sort_keys=True
            ),
            "GRAPH_TARGET_E2E_RUNTIME_CONTEXT": json.dumps(
                runtime_context, separators=(",", ":"), sort_keys=True
            ),
            "GRAPH_TARGET_E2E_BINDINGS": json.dumps(
                executor_bindings, separators=(",", ":"), sort_keys=True
            ),
            "TARGET_E2E_MTLS_CLIENT_CERT_SHA256": client_fingerprint,
            "TARGET_E2E_MTLS_CA_CERT_SHA256": ca_fingerprint,
            "TARGET_E2E_MTLS_KEYSTORE_PASSWORD": key_password,
            "TARGET_E2E_MTLS_TRUSTSTORE_PASSWORD": trust_password,
        }
        for key, record in images.items():
            environment[f"TARGET_E2E_{key.upper()}_IMAGE"] = record["reference"]

        env_path = root / "target-e2e.env"
        env_lines = [
            f"{key}={common.env_quote(value)}"
            for key, value in sorted(environment.items())
        ]
        _write_private(env_path, "\n".join(env_lines) + "\n")

        ledger_context = {
            "run_context_hash": run_context["self_hash"],
            "candidate_sha": candidate,
            "activation_id": activation_id,
            "environment_generation": environment_generation,
            "compose_project": project_name,
            "temporal_namespace": temporal_namespace,
            "run_nonce": run_nonce,
        }
        first_record = ledger.append_record(
            evidence_dir / "ledger.jsonl",
            harness_private,
            harness_public,
            key_id=harness_key_id,
            context=ledger_context,
            source_kind="HARNESS_DIRECT",
            source_identity="target-e2e-provisioner",
            case_id=None,
            payload_type="PROVISIONED_RUN_CONTEXT",
            payload=run_context,
        )
        receipt = ledger.attest_document(
            {
                "schema_version": "target-e2e-provisioning-receipt.v2",
                "run_id": selected_run_id,
                "candidate_sha": candidate,
                "image_lock_hash": image_lock_document["self_hash"],
                "run_context_hash": run_context["self_hash"],
                "ledger_record_hash": first_record["record_hash"],
                "registry_hash": binding_hash,
                "activation_id": activation_id,
                "activation_manifest_hash": activation_manifest["manifestHash"],
                "activation_expires_at": activation_manifest["expiresAt"],
                "host_lock_nonce": run_lock["lock_nonce"],
                "secrets_committed": False,
            },
            harness_private,
            harness_public,
            key_id=harness_key_id,
        )
        common.write_json(root / "provisioning-receipt.json", receipt)
        active_lock = common.seal_self_hash(
            {
                **run_lock,
                "state": "ACTIVE",
                "ledger_public_key_sha256": harness_fingerprint,
            }
        )
        common.write_json(run_lock_path, active_lock)
        return env_path
    except BaseException:
        failed_lock = common.seal_self_hash({**run_lock, "state": "FAILED"})
        common.write_json(run_lock_path, failed_lock)
        port_document = common.load_json(_port_lock_path)
        common.write_json(
            _port_lock_path,
            common.seal_self_hash(
                {
                    **port_document,
                    "state": "FAILED",
                    "released_at": common.utc_now().isoformat(timespec="milliseconds"),
                }
            ),
        )
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image-lock", type=Path, required=True)
    parser.add_argument(
        "--runtime-root",
        type=Path,
        default=Path.home() / ".after-sale-flow" / "target-e2e",
    )
    parser.add_argument("--run-id")
    parser.add_argument("--gateway-port", type=int, default=25180)
    args = parser.parse_args(argv)
    try:
        env_path = provision(
            args.image_lock,
            args.runtime_root,
            args.run_id,
            args.gateway_port,
        )
    except common.TargetE2EError as error:
        parser.error(str(error))
    print(env_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
