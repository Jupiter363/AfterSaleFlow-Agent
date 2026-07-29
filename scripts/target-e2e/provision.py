from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

from cryptography import x509
from cryptography.hazmat.primitives import hashes

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common
import ledger
import teardown

SYNTHETIC_FIXTURE_SET_ID = "p9-synthetic-all-rooms-001"
SYNTHETIC_FIXTURE_SOURCE = (
    common.ROOT
    / "contracts"
    / "agent-platform"
    / "target-e2e"
    / "v1"
    / "fixtures"
    / "synthetic"
    / f"{SYNTHETIC_FIXTURE_SET_ID}.json"
)
TARGET_E2E_JAVA_ARTIFACT = "/home/app/app-target-e2e.jar"
CONTAINER_ID = re.compile(r"^[0-9a-f]{12,64}$")
POSTGRES_IDENTITY_PREFIXES = {
    "clusterIdentity": ("pg-system-id/", 18_446_744_073_709_551_615),
    "databaseIdentity": ("pg-database-oid/", 4_294_967_295),
    "runtimePrincipalIdentity": ("pg-role-oid/", 4_294_967_295),
}

DATABASE_IDENTITY_SQL = """
select json_build_object(
    'clusterIdentity', 'pg-system-id/' || control.system_identifier::text,
    'databaseIdentity', 'pg-database-oid/' || database.oid::text,
    'runtimePrincipalIdentity', 'pg-role-oid/' || role.oid::text,
    'databaseName', current_database(),
    'roleName', current_user
)::text
from pg_control_system() control
join pg_database database on database.datname = current_database()
join pg_roles role on role.rolname = current_user
""".strip()


def _tool(name: str) -> str:
    found = shutil.which(name)
    if found is None:
        raise common.TargetE2EError(
            f"required provisioning tool is unavailable: {name}"
        )
    return found


def _run(
    arguments: list[str], *, environment: dict[str, str] | None = None
) -> None:
    completed = subprocess.run(
        arguments,
        check=False,
        capture_output=True,
        text=True,
        shell=False,
        env=environment,
    )
    if completed.returncode:
        raise common.TargetE2EError(
            f"provisioning command failed: {completed.stderr.strip() or completed.stdout.strip()}"
        )


def _run_output(arguments: list[str]) -> str:
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
    return completed.stdout.strip()


def _secret() -> str:
    return secrets.token_urlsafe(32)


def _write_private(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(payload, encoding="ascii", newline="\n")
    if os.name != "nt":
        path.chmod(0o600)


def _write_environment(path: Path, values: dict[str, str]) -> None:
    _write_private(
        path,
        "\n".join(
            f"{key}={common.env_quote(value)}" for key, value in sorted(values.items())
        )
        + "\n",
    )


def _parse_database_identity(
    output: str, *, expected_database: str, expected_role: str
) -> dict[str, str]:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if len(lines) != 1:
        raise common.TargetE2EError(
            "database identity measurement did not return exactly one row"
        )
    try:
        document = json.loads(lines[0])
    except json.JSONDecodeError as error:
        raise common.TargetE2EError(
            "database identity measurement did not return strict JSON"
        ) from error
    expected_fields = {
        "clusterIdentity",
        "databaseIdentity",
        "runtimePrincipalIdentity",
        "databaseName",
        "roleName",
    }
    if not isinstance(document, dict) or set(document) != expected_fields:
        raise common.TargetE2EError("database identity measurement fields drifted")
    if (
        document["databaseName"] != expected_database
        or document["roleName"] != expected_role
    ):
        raise common.TargetE2EError(
            "database identity measurement used the wrong database or runtime role"
        )
    identity = {
        key: document[key]
        for key in (
            "clusterIdentity",
            "databaseIdentity",
            "runtimePrincipalIdentity",
        )
    }
    for key, value in identity.items():
        prefix, maximum = POSTGRES_IDENTITY_PREFIXES[key]
        if not isinstance(value, str) or not value.startswith(prefix):
            raise common.TargetE2EError("database identity measurement is malformed")
        number = value[len(prefix) :]
        if (
            not number.isascii()
            or not number.isdecimal()
            or number.startswith("0")
            or int(number) > maximum
        ):
            raise common.TargetE2EError("database identity measurement is malformed")
    return identity


def _measure_database_identity(
    env_path: Path, *, service: str, database: str, runtime_role: str
) -> dict[str, str]:
    result = common.run_command(
        common.compose_argv(
            env_path,
            "exec",
            "--no-TTY",
            service,
            "psql",
            "--set=ON_ERROR_STOP=1",
            "--quiet",
            "--tuples-only",
            "--no-align",
            "--username",
            runtime_role,
            "--dbname",
            database,
            "--command",
            DATABASE_IDENTITY_SQL,
        ),
        timeout=30,
    )
    return _parse_database_identity(
        result.stdout, expected_database=database, expected_role=runtime_role
    )


def _bootstrap_database_identities(
    env_path: Path, lock: dict[str, Any]
) -> tuple[dict[str, str], dict[str, str]]:
    teardown.assert_no_locked_resources(lock)
    common.run_command(
        common.compose_argv(
            env_path,
            "up",
            "--detach",
            "--wait",
            "--wait-timeout",
            "120",
            "--pull",
            "never",
            "domain-db",
            "graph-db",
        ),
        timeout=180,
    )
    domain = _measure_database_identity(
        env_path,
        service="domain-db",
        database="target_domain",
        runtime_role="domain_app",
    )
    graph = _measure_database_identity(
        env_path,
        service="graph-db",
        database="target_graph",
        runtime_role="graph_runtime",
    )
    if (
        domain["clusterIdentity"] == graph["clusterIdentity"]
        or domain["databaseIdentity"] == graph["databaseIdentity"]
        or domain["runtimePrincipalIdentity"] == graph["runtimePrincipalIdentity"]
    ):
        raise common.TargetE2EError(
            "bootstrap measured non-isolated Domain and Graph database identities"
        )
    return domain, graph


def _reject_duplicate_members(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise common.TargetE2EError(
                f"synthetic fixture contains a duplicate JSON member: {key}"
            )
        value[key] = item
    return value


def _canonical_fixture(path: Path) -> tuple[dict[str, Any], bytes, str]:
    try:
        if path.is_symlink() or not path.is_file():
            raise common.TargetE2EError(
                "synthetic fixture source must be a regular non-link file"
            )
        raw = path.read_bytes()
        if not raw or len(raw) > 256 * 1024:
            raise common.TargetE2EError("synthetic fixture source size is invalid")
        document = json.loads(
            raw.decode("utf-8"), object_pairs_hook=_reject_duplicate_members
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise common.TargetE2EError(
            "synthetic fixture source is not strict UTF-8 JSON"
        ) from error
    if not isinstance(document, dict):
        raise common.TargetE2EError("synthetic fixture source must be a JSON object")
    expected_fields = {
        "schemaVersion",
        "fixtureSetId",
        "caseIdPrefix",
        "maximumCases",
        "roomTypes",
        "scenarios",
    }
    if (
        set(document) != expected_fields
        or document.get("schemaVersion") != "target-e2e-synthetic-fixture-set.v1"
        or document.get("fixtureSetId") != SYNTHETIC_FIXTURE_SET_ID
        or document.get("caseIdPrefix") != "CASE_P9_SYNTHETIC_"
        or document.get("maximumCases") != 4
        or document.get("roomTypes") != ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]
        or not isinstance(document.get("scenarios"), list)
        or len(document["scenarios"]) != 4
    ):
        raise common.TargetE2EError(
            "synthetic fixture source does not match the frozen all-room fixture contract"
        )
    canonical = common.canonical_bytes(document)
    return document, canonical, hashlib.sha256(canonical).hexdigest()


def _write_public_bytes(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    if os.name != "nt":
        path.chmod(0o444)


def _openssl_environment(openssl: str) -> dict[str, str]:
    """Discover one real OpenSSL config and override broken compiled host defaults."""

    executable = Path(openssl).expanduser().resolve()
    candidates: list[Path] = []
    configured = os.environ.get("OPENSSL_CONF")
    if configured:
        candidates.append(Path(configured).expanduser())
    # Conda installs the executable under ``Library/bin`` but its usable config
    # under ``Library/ssl``.  The binary's compiled OPENSSLDIR can point at a
    # machine-global path that does not exist, so this relative candidate must
    # be considered without relying on an activated Conda shell.
    candidates.extend(
        (
            executable.parent.parent / "ssl" / "openssl.cnf",
            executable.parent / "openssl.cnf",
            Path("/etc/ssl/openssl.cnf"),
        )
    )
    selected: Path | None = None
    seen: set[Path] = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        try:
            payload = resolved.read_bytes()
        except OSError:
            continue
        if resolved.is_file() and 0 < len(payload) <= 1024 * 1024 and b"\x00" not in payload:
            selected = resolved
            break
    if selected is None:
        raise common.TargetE2EError(
            "no bounded readable OpenSSL config was found beside the selected executable"
        )
    environment = dict(os.environ)
    environment["OPENSSL_CONF"] = str(selected)
    return environment


def _java_artifact_digest(
    docker: str, image_reference: str, runtime_directory: Path
) -> str:
    container_id = _run_output(
        [docker, "create", "--entrypoint", "/bin/true", image_reference]
    )
    if not CONTAINER_ID.fullmatch(container_id):
        raise common.TargetE2EError(
            "Docker did not return one canonical container ID for artifact measurement"
        )
    try:
        with tempfile.TemporaryDirectory(
            prefix=".artifact-measurement-", dir=runtime_directory
        ) as temporary:
            artifact = Path(temporary) / "app-target-e2e.jar"
            _run(
                [
                    docker,
                    "cp",
                    f"{container_id}:{TARGET_E2E_JAVA_ARTIFACT}",
                    str(artifact),
                ]
            )
            common.assert_regular_single_link(
                artifact, "measured target E2E Java artifact"
            )
            if artifact.stat().st_size < 1:
                raise common.TargetE2EError(
                    "measured target E2E Java artifact is empty"
                )
            return common.file_sha256(artifact)
    finally:
        _run([docker, "rm", "--force", container_id])


def _isolation_attestation_payload(
    *,
    now: dt.datetime,
    environment_id: str,
    environment_generation: int,
    candidate: str,
    artifact_digest: str,
    image_digests: dict[str, str],
    domain_identity: dict[str, str],
    graph_identity: dict[str, str],
    attestation_nonce: str,
) -> dict[str, Any]:
    def database(identity: dict[str, str]) -> dict[str, Any]:
        return {
            "bypassRowLevelSecurity": False,
            "clusterIdentity": identity["clusterIdentity"],
            "createDatabase": False,
            "createRole": False,
            "databaseIdentity": identity["databaseIdentity"],
            "peerPrincipalCanConnect": False,
            "replication": False,
            "runtimePrincipalIdentity": identity["runtimePrincipalIdentity"],
            "superuser": False,
        }

    payload: dict[str, Any] = {
        "schemaVersion": "target-e2e-runtime-measurement.v1",
        "attestationNonce": attestation_nonce,
        "environmentId": environment_id,
        "environmentGeneration": environment_generation,
        "candidateSha": candidate,
        "artifactDigest": artifact_digest,
        "issuedAt": now.isoformat(timespec="seconds"),
        "expiresAt": (now + dt.timedelta(minutes=15)).isoformat(timespec="seconds"),
        "imageDigestsHash": common.canonical_sha256(
            {
                "frontend": image_digests["frontend"],
                "javaApi": image_digests["javaApi"],
                "pythonAgent": image_digests["pythonAgent"],
                "temporalAgentWorker": image_digests["temporalAgentWorker"],
                "temporalControlWorker": image_digests["temporalControlWorker"],
            }
        ),
        "databaseMeasurementHash": common.canonical_sha256(
            {
                "domain": database(domain_identity),
                "graph": database(graph_identity),
            }
        ),
        "networkIsolationEnforced": True,
        "externalEffectEndpointsEnabled": False,
        "graphDomainCredentialsPresent": False,
        "graphDomainPrivilegesPresent": False,
    }
    payload["attestationHash"] = common.canonical_sha256(payload)
    return payload


def _generate_p256_key_pair(
    openssl: str, private_path: Path, public_path: Path
) -> None:
    private_path.parent.mkdir(parents=True, exist_ok=True)
    public_path.parent.mkdir(parents=True, exist_ok=True)
    environment = _openssl_environment(openssl)
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
        ],
        environment=environment,
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
        ],
        environment=environment,
    )
    if os.name != "nt":
        public_path.chmod(0o444)


def _generate_mtls(
    openssl: str,
    keytool: str,
    directory: Path,
    key_password: str,
    trust_password: str,
) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    openssl_environment = _openssl_environment(openssl)
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
        ],
        environment=openssl_environment,
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
            "INTAKE_QUESTIONS_GENERATING",
            "INTAKE_SYNTHESIZING",
            "EVIDENCE_REQUESTS_GENERATING",
            "EVIDENCE_SYNTHESIZING",
            "JUDGE_V1_GENERATING",
            "JURY_REVIEWING",
            "JUDGE_V2_GENERATING",
            "REVIEW_OUTCOME",
        ],
    }
    binding_hash = common.canonical_sha256(binding)
    return {**binding, "binding_hash": binding_hash}, binding_hash


def _activation_graph_binding(
    target_binding: dict[str, Any],
) -> tuple[dict[str, Any], str]:
    binding = {
        "key": target_binding["graph_key"],
        "version": target_binding["graph_version"],
        "checkpointSchemaVersion": target_binding["checkpoint_schema_version"],
        "codeBuildId": target_binding["code_build_id"],
    }
    binding_hash = common.canonical_sha256(binding)
    return {**binding, "bindingHash": binding_hash}, binding_hash


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
    docker = _tool("docker")
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

        isolation_key_id = f"p9-isolation-attestation-{candidate[:12]}"
        isolation_private_path = (
            secrets_dir
            / "isolation-attestation-signing"
            / "isolation-attestation.private.pem"
        )
        isolation_public_path = activation_dir / "isolation-attestation.public.pem"
        _generate_p256_key_pair(openssl, isolation_private_path, isolation_public_path)
        isolation_private = ledger.load_private_key(isolation_private_path)

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
        activation_graph_binding, activation_graph_binding_hash = (
            _activation_graph_binding(target_binding)
        )
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

        activation_id = f"p9act.v1.{secrets.token_hex(16)}"
        run_nonce = f"p9-nonce-{secrets.token_hex(16)}"
        environment_id = f"p9-isolated-{selected_run_id}"
        tenant_surrogate = f"tenant-{selected_run_id}"
        project_name = run_lock["project_name"]
        temporal_namespace = f"after-sale-flow-p9-{selected_run_id}"
        control_build_id = f"p9-control-{candidate[:8]}"
        build_bindings = {
            "caseBuildId": control_build_id,
            "controlBuildId": control_build_id,
            "agentBuildId": f"p9-agent-{candidate[:8]}",
        }
        image_digests = {
            "javaApi": _manifest_digest(images, "java"),
            "temporalControlWorker": _manifest_digest(images, "java"),
            "temporalAgentWorker": _manifest_digest(images, "java"),
            "pythonAgent": _manifest_digest(images, "python"),
            "frontend": _manifest_digest(images, "frontend"),
        }
        fixture_document, fixture_bytes, fixture_hash = _canonical_fixture(
            SYNTHETIC_FIXTURE_SOURCE
        )
        fixture_path = (
            activation_dir / "synthetic-fixtures" / f"{SYNTHETIC_FIXTURE_SET_ID}.json"
        )
        _write_public_bytes(fixture_path, fixture_bytes)
        case_scope = {
            "mode": "ISOLATED_SYNTHETIC_NEW_CASES",
            "caseIdPrefix": "CASE_P9_SYNTHETIC_",
            "maxCases": 4,
            "fixtureSetId": fixture_document["fixtureSetId"],
            "fixtureSetHash": fixture_hash,
            "containsRealCaseOrPartyData": False,
            "externalEffectsAllowed": False,
        }
        restore_hash = hashlib.sha256(
            f"{selected_run_id}:{candidate}:restore".encode("ascii")
        ).hexdigest()
        artifact_digest = _java_artifact_digest(
            docker, images["java"]["reference"], root
        )
        credentials = {
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
        }
        environment = {
            "TARGET_E2E_RUN_ID": selected_run_id,
            "TARGET_E2E_PROJECT_NAME": project_name,
            "TARGET_E2E_BUILD_ID": candidate,
            "TARGET_E2E_CASE_BUILD_ID": build_bindings["caseBuildId"],
            "TARGET_E2E_CONTROL_BUILD_ID": build_bindings["controlBuildId"],
            "TARGET_E2E_AGENT_BUILD_ID": build_bindings["agentBuildId"],
            "TARGET_E2E_SOURCE_COMMIT": candidate,
            "TARGET_E2E_LOCK_NONCE": run_lock["lock_nonce"],
            "TARGET_E2E_IMAGE_LOCK_HASH": image_lock_document["self_hash"],
            "TARGET_E2E_SECRETS_DIR": secrets_dir.as_posix(),
            "TARGET_E2E_PUBLIC_DIR": public_dir.as_posix(),
            "TARGET_E2E_ACTIVATION_DIR": activation_dir.as_posix(),
            "TARGET_E2E_EVIDENCE_DIR": evidence_dir.as_posix(),
            "TARGET_E2E_SOCKET_DIR": socket_dir.as_posix(),
            "TARGET_E2E_GATEWAY_PORT": str(gateway_port),
            "TARGET_E2E_TEMPORAL_NAMESPACE": temporal_namespace,
            "TARGET_E2E_ENVIRONMENT_ID": environment_id,
            "TARGET_E2E_ENVIRONMENT_GENERATION": "1",
            "TARGET_E2E_ACTIVATION_ID": activation_id,
            "TARGET_E2E_ACTIVATION_KEY_ID": activation_key_id,
            "TARGET_E2E_ISOLATION_ATTESTATION_KEY_ID": isolation_key_id,
            "TARGET_E2E_JAVA_ARTIFACT_SHA256": artifact_digest,
            "TARGET_E2E_SYNTHETIC_FIXTURE_SHA256": fixture_hash,
            "TARGET_E2E_RUN_NONCE": run_nonce,
            "TARGET_E2E_TENANT_SURROGATE": tenant_surrogate,
            "TARGET_E2E_DOMAIN_CLUSTER_IDENTITY": "BOOTSTRAP_PENDING",
            "TARGET_E2E_DOMAIN_DATABASE_IDENTITY": "BOOTSTRAP_PENDING",
            "TARGET_E2E_DOMAIN_PRINCIPAL_IDENTITY": "BOOTSTRAP_PENDING",
            "TARGET_E2E_ISOLATED_DOMAIN_DB_BINDING_HASH": "BOOTSTRAP_PENDING",
            "TARGET_E2E_GRAPH_CLUSTER_IDENTITY": "BOOTSTRAP_PENDING",
            "TARGET_E2E_GRAPH_DATABASE_IDENTITY": "BOOTSTRAP_PENDING",
            "TARGET_E2E_GRAPH_PRINCIPAL_IDENTITY": "BOOTSTRAP_PENDING",
            "TARGET_E2E_GRAPH_ENVIRONMENT_GENERATION": "1",
            "TARGET_E2E_GRAPH_RESTORE_HASH": restore_hash,
            "TARGET_E2E_GRAPH_SIGNING_KEY_ID": graph_key_id,
            "TARGET_E2E_GRAPH_REGISTRY_HASH": binding_hash,
            "TARGET_E2E_GRAPH_ACTIVATION_BINDING_HASH": activation_graph_binding_hash,
            "TARGET_E2E_GRAPH_VERSION": target_binding["graph_version"],
            "TARGET_E2E_GRAPH_CHECKPOINT_SCHEMA_VERSION": target_binding[
                "checkpoint_schema_version"
            ],
            "TARGET_E2E_GRAPH_CODE_BUILD_ID": target_binding["code_build_id"],
            "TARGET_E2E_GRAPH_SHADOW_BINDINGS": json.dumps(
                [current_shadow_binding], separators=(",", ":"), sort_keys=True
            ),
            "GRAPH_TARGET_E2E_RUNTIME_CONTEXT": "{}",
            "GRAPH_TARGET_E2E_BINDINGS": json.dumps(
                [target_binding], separators=(",", ":"), sort_keys=True
            ),
            "TARGET_E2E_MTLS_CLIENT_CERT_SHA256": client_fingerprint,
            "TARGET_E2E_MTLS_CA_CERT_SHA256": ca_fingerprint,
            "TARGET_E2E_MTLS_KEYSTORE_PASSWORD": key_password,
            "TARGET_E2E_MTLS_TRUSTSTORE_PASSWORD": trust_password,
            **credentials,
        }
        for key, record in images.items():
            environment[f"TARGET_E2E_{key.upper()}_IMAGE"] = record["reference"]
            environment[f"TARGET_E2E_{key.upper()}_IMAGE_DIGEST"] = record[
                "manifest_digest"
            ]

        bootstrap_env_path = root / ".bootstrap.env"
        _write_environment(bootstrap_env_path, environment)
        domain_identity, graph_identity = _bootstrap_database_identities(
            bootstrap_env_path, run_lock
        )
        now = common.utc_now()
        expires = now + dt.timedelta(minutes=30)
        environment_generation = int(now.timestamp())
        graph_generation = environment_generation
        environment.update(
            {
                "TARGET_E2E_ENVIRONMENT_GENERATION": str(environment_generation),
                "TARGET_E2E_GRAPH_ENVIRONMENT_GENERATION": str(graph_generation),
                "TARGET_E2E_DOMAIN_CLUSTER_IDENTITY": domain_identity[
                    "clusterIdentity"
                ],
                "TARGET_E2E_DOMAIN_DATABASE_IDENTITY": domain_identity[
                    "databaseIdentity"
                ],
                "TARGET_E2E_DOMAIN_PRINCIPAL_IDENTITY": domain_identity[
                    "runtimePrincipalIdentity"
                ],
                "TARGET_E2E_GRAPH_CLUSTER_IDENTITY": graph_identity[
                    "clusterIdentity"
                ],
                "TARGET_E2E_GRAPH_DATABASE_IDENTITY": graph_identity[
                    "databaseIdentity"
                ],
                "TARGET_E2E_GRAPH_PRINCIPAL_IDENTITY": graph_identity[
                    "runtimePrincipalIdentity"
                ],
                "TARGET_E2E_ISOLATED_DOMAIN_DB_BINDING_HASH": common.canonical_sha256(
                    {
                        "schema_version": "target-e2e-isolated-domain-db-binding.v1",
                        "environment_id": environment_id,
                        "environment_generation": environment_generation,
                        "activation_id": activation_id,
                        "binding_kind": "ISOLATED_DOMAIN_POSTGRESQL",
                        "cluster_identity": domain_identity["clusterIdentity"],
                        "database_identity": domain_identity["databaseIdentity"],
                        "runtime_principal_identity": domain_identity[
                            "runtimePrincipalIdentity"
                        ],
                    }
                ),
            }
        )
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
            "graphBinding": activation_graph_binding,
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
        _write_public_bytes(
            activation_dir / "activation.jws", (activation_jws + "\n").encode("ascii")
        )

        isolation_nonce = f"p9-isolation-nonce-{secrets.token_hex(16)}"
        isolation_payload = _isolation_attestation_payload(
            now=now,
            environment_id=environment_id,
            environment_generation=environment_generation,
            candidate=candidate,
            artifact_digest=artifact_digest,
            image_digests=image_digests,
            domain_identity=domain_identity,
            graph_identity=graph_identity,
            attestation_nonce=isolation_nonce,
        )
        common.write_json(
            activation_dir / "isolation-attestation.json", isolation_payload
        )
        isolation_jws = ledger.sign_compact_jws(
            isolation_payload,
            isolation_private,
            key_id=isolation_key_id,
            typ="target-e2e-runtime-measurement+jwt",
        )
        _write_public_bytes(
            activation_dir / "isolation-attestation.jws",
            (isolation_jws + "\n").encode("ascii"),
        )

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

        environment.update(
            {
                "TARGET_E2E_LOCK_PATH": run_lock_path.as_posix(),
                "TARGET_E2E_IMAGE_LOCK_PATH": image_lock_snapshot.as_posix(),
                "TARGET_E2E_RUN_CONTEXT_PATH": run_context_path.as_posix(),
                "TARGET_E2E_RUN_CONTEXT_HASH": run_context["self_hash"],
                "TARGET_E2E_ISOLATION_ATTESTATION_JWS_SHA256": hashlib.sha256(
                    isolation_jws.encode("ascii")
                ).hexdigest(),
                "GRAPH_TARGET_E2E_RUNTIME_CONTEXT": json.dumps(
                    runtime_context, separators=(",", ":"), sort_keys=True
                ),
                "GRAPH_TARGET_E2E_BINDINGS": json.dumps(
                    executor_bindings, separators=(",", ":"), sort_keys=True
                ),
            }
        )
        env_path = root / "target-e2e.env"
        _write_environment(env_path, environment)
        bootstrap_env_path.unlink(missing_ok=True)

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
    except BaseException as failure:
        try:
            teardown.remove_exact_locked_resources(run_lock)
            (root / ".bootstrap.env").unlink(missing_ok=True)
        except BaseException as cleanup_failure:
            cleanup_required_lock = common.seal_self_hash(
                {**run_lock, "state": "FAILED_CLEANUP_REQUIRED"}
            )
            common.write_json(run_lock_path, cleanup_required_lock)
            raise common.TargetE2EError(
                "provisioning failed and exact locked-resource cleanup is required"
            ) from cleanup_failure
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
        raise failure


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
