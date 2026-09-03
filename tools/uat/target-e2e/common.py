from __future__ import annotations

import datetime as dt
import getpass
import hashlib
import json
import os
import platform
import re
import stat
import subprocess
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[3]
COMPOSE_FILE = ROOT / "infra/compose/target-e2e.yml"
IMAGE_KEYS = (
    "postgres",
    "redis",
    "minio",
    "minio_mc",
    "elasticsearch",
    "temporal",
    "java",
    "python",
    "ocr",
    "frontend",
    "nginx",
    "curl",
)
APPLICATION_IMAGE_KEYS = {"java", "python", "ocr", "frontend"}

# The target-E2E release unit has one shared graph binding.  Keep the
# protocol identity in this module so provisioning and run-context validation
# cannot silently drift apart again.  The graph/checkpoint/prompt/proposal
# identities are the v2 cutover boundary; policy identifiers remain the
# existing approved target profiles.
TARGET_E2E_GRAPH_KEY = "all-rooms.target-e2e.v2"
TARGET_E2E_GRAPH_VERSION = "target-e2e-graph.2026-08-18.3"
TARGET_E2E_CHECKPOINT_SCHEMA_VERSION = "target-e2e-checkpoint.v2"
TARGET_E2E_PROMPT_VERSION = "all-rooms-prompt.target-e2e.v2"
TARGET_E2E_OUTPUT_SCHEMA_VERSION = "target-e2e-room-proposal-source.v2"
TARGET_E2E_TOOL_POLICY_VERSION = "tools.none.v1"
TARGET_E2E_ALLOWED_ROOM_TYPES = ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]
TARGET_E2E_ALLOWED_STAGE_CODES = [
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
]
IMAGE_RECORD_KEYS = {
    "reference",
    "manifest_digest",
    "config_digest",
    "layer_digests",
    "source_revision",
    "build_id",
}
BUILD_PROVENANCE_KEYS = {
    "builder_id",
    "invocation_id",
    "source_tree_sha256",
    "built_at",
    "attestation_type",
    "attestation_digest",
}
RUN_CONTEXT_KEYS = {
    "schema_version",
    "runtime_projection",
    "executor_bindings",
    "current_shadow_binding",
    "activation_manifest_hash",
    "image_lock_hash",
    "image_lock_path",
    "resources",
    "mtls",
    "jwks_sha256",
    "ledger_public_key_sha256",
    "lock_nonce",
    "self_hash",
}
RUNTIME_PROJECTION_KEYS = {
    "schemaVersion",
    "executionLane",
    "activationId",
    "activationManifestHash",
    "environmentId",
    "environmentGeneration",
    "candidateSha",
    "issuedAt",
    "expiresAt",
    "runNonce",
    "tenantSurrogate",
    "caseScope",
    "allowedRoomTypes",
    "composeProject",
    "temporalNamespace",
    "buildBindings",
    "imageDigests",
    "databaseIdentities",
    "trustedSigningKeyIds",
    "perCommandManifestAllowed",
}
TARGET_EXECUTOR_BINDING_KEYS = {
    "graph_key",
    "graph_version",
    "checkpoint_schema_version",
    "state_schema_version",
    "state_schema_hash",
    "command_schema_version",
    "result_schema_version",
    "agent_profile_id",
    "prompt_version",
    "model_profile_id",
    "output_schema_version",
    "policy_version",
    "guardrail_version",
    "tool_policy_version",
    "binding_hash",
    "code_build_id",
    "allowed_room_types",
    "allowed_stage_codes",
}
RUN_LOCK_KEYS = {
    "schema_version",
    "state",
    "project_name",
    "run_id",
    "runtime_root",
    "run_directory",
    "env_file",
    "lock_nonce",
    "owner",
    "candidate_sha",
    "image_lock_hash",
    "gateway_port",
    "port_lock",
    "resources",
    "ledger_public_key_sha256",
    "created_at",
    "released_at",
    "self_hash",
}
PORT_LOCK_KEYS = {
    "schema_version",
    "state",
    "gateway_port",
    "project_name",
    "run_id",
    "lock_nonce",
    "owner",
    "created_at",
    "released_at",
    "self_hash",
}
EXPECTED_SERVICES = (
    "domain-db",
    "graph-db",
    "temporal-db",
    "redis",
    "minio",
    "minio-init",
    "elasticsearch",
    "elasticsearch-init",
    "temporal-server",
    "temporal-namespace-init",
    "graph-migrate",
    "graph-restore-validation",
    "jwks-server",
    "graph-exchange-proxy",
    "graph-mtls-proxy",
    "python-agent-service",
    "ocr-parser-service",
    "java-api-service",
    "java-control-worker",
    "java-agent-worker",
    "frontend",
    "gateway",
    "mtls-proof",
)
NETWORK_SUFFIXES = (
    "domain_data",
    "graph_data",
    "temporal_data",
    "temporal_runtime",
    "cache_data",
    "object_data",
    "search_data",
    "python_egress",
    "graph_exchange",
    "graph_mtls_client",
    "ocr_plane",
    "app_internal",
    "edge",
)
VOLUME_SUFFIXES = (
    "domain_data",
    "graph_data",
    "temporal_data",
    "redis_data",
    "minio_data",
    "elasticsearch_data",
    "ocr_data",
    "ocr_models",
)
IMAGE_REFERENCE = re.compile(r"^[a-z0-9][a-z0-9._:/-]{2,254}@sha256:[0-9a-f]{64}$")
RUN_ID = re.compile(r"^[a-z0-9][a-z0-9-]{5,31}$")
TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{2,255}$")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


class TargetE2EError(RuntimeError):
    pass


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def parse_timestamp(value: Any, context: str) -> dt.datetime:
    if not isinstance(value, str):
        raise TargetE2EError(f"{context} must be an ISO-8601 timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise TargetE2EError(f"{context} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise TargetE2EError(f"{context} must include a timezone")
    return parsed.astimezone(dt.timezone.utc)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise TargetE2EError(f"cannot load strict JSON from {path}") from error
    if not isinstance(value, dict):
        raise TargetE2EError(f"{path} must contain a JSON object")
    return value


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(payload, encoding="utf-8", newline="\n")
    temporary.replace(path)


def atomic_create_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (
        json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    ).encode("ascii")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        descriptor = os.open(path, flags, 0o600)
    except FileExistsError as error:
        raise TargetE2EError(f"atomic host lock already exists: {path}") from error
    try:
        os.write(descriptor, payload)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def seal_self_hash(document: dict[str, Any]) -> dict[str, Any]:
    sealed = dict(document)
    sealed.pop("self_hash", None)
    sealed["self_hash"] = canonical_sha256(sealed)
    return sealed


def verify_self_hash(document: dict[str, Any], context: str) -> None:
    claimed = document.get("self_hash")
    unsigned = dict(document)
    unsigned.pop("self_hash", None)
    expected = canonical_sha256(unsigned)
    if (
        not isinstance(claimed, str)
        or not SHA256.fullmatch(claimed)
        or claimed != expected
    ):
        raise TargetE2EError(f"{context} self-hash is missing or invalid")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def assert_external_runtime_path(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    repository = ROOT.resolve()
    if resolved == repository or repository in resolved.parents:
        raise TargetE2EError(
            "runtime secrets and evidence must be outside the Git worktree"
        )
    return resolved


def assert_regular_single_link(path: Path, context: str) -> None:
    metadata = os.lstat(path)
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise TargetE2EError(f"{context} must be a regular non-link file")
    if os.name != "nt" and metadata.st_nlink != 1:
        raise TargetE2EError(f"{context} must have exactly one hard link")


def load_image_lock(
    path: Path,
) -> tuple[str, dict[str, dict[str, Any]], dict[str, Any]]:
    document = load_json(path)
    expected_fields = {
        "schema_version",
        "candidate_sha",
        "source_revision",
        "build_provenance",
        "images",
        "self_hash",
    }
    if set(document) != expected_fields:
        raise TargetE2EError("image lock fields drifted")
    if document["schema_version"] != "target-e2e-image-lock.v2":
        raise TargetE2EError("unsupported image lock schema")
    verify_self_hash(document, "image lock")
    candidate = document["candidate_sha"]
    if not isinstance(candidate, str) or not SHA1.fullmatch(candidate):
        raise TargetE2EError("image lock candidate_sha must be an exact Git SHA")
    if document["source_revision"] != candidate:
        raise TargetE2EError("image lock source revision diverges from the candidate")
    provenance = document["build_provenance"]
    if not isinstance(provenance, dict) or set(provenance) != BUILD_PROVENANCE_KEYS:
        raise TargetE2EError("image lock build provenance is incomplete")
    if not all(
        isinstance(provenance[key], str) and provenance[key]
        for key in ("builder_id", "invocation_id", "attestation_type")
    ):
        raise TargetE2EError("image lock provenance tokens are invalid")
    for key in ("source_tree_sha256", "attestation_digest"):
        if not isinstance(provenance[key], str) or not DIGEST.fullmatch(
            provenance[key]
        ):
            raise TargetE2EError("image lock provenance digests are invalid")
    parse_timestamp(provenance["built_at"], "image lock built_at")
    images = document["images"]
    if not isinstance(images, dict) or set(images) != set(IMAGE_KEYS):
        raise TargetE2EError(
            "image lock must contain the exact target E2E image inventory"
        )
    normalized: dict[str, dict[str, Any]] = {}
    for key, record in images.items():
        if not isinstance(record, dict) or set(record) != IMAGE_RECORD_KEYS:
            raise TargetE2EError(f"image record fields drifted for {key}")
        reference = record["reference"]
        if not isinstance(reference, str) or not IMAGE_REFERENCE.fullmatch(reference):
            raise TargetE2EError(
                f"image {key} lacks an immutable registry manifest reference"
            )
        if record["manifest_digest"] != reference.rsplit("@", 1)[1]:
            raise TargetE2EError(f"image {key} reference and manifest digest differ")
        if not isinstance(record["config_digest"], str) or not DIGEST.fullmatch(
            record["config_digest"]
        ):
            raise TargetE2EError(f"image {key} config digest is invalid")
        layers = record["layer_digests"]
        if (
            not isinstance(layers, list)
            or not layers
            or any(
                not isinstance(layer, str) or not DIGEST.fullmatch(layer)
                for layer in layers
            )
        ):
            raise TargetE2EError(f"image {key} layer inventory is invalid")
        if not isinstance(record["build_id"], str) or not TOKEN.fullmatch(
            record["build_id"]
        ):
            raise TargetE2EError(f"image {key} build ID is invalid")
        source_revision = record["source_revision"]
        if not isinstance(source_revision, str) or not TOKEN.fullmatch(source_revision):
            raise TargetE2EError(f"image {key} source revision is invalid")
        if key in APPLICATION_IMAGE_KEYS and source_revision != candidate:
            raise TargetE2EError(
                f"application image {key} is not built from the candidate"
            )
        normalized[key] = dict(record)
    return candidate, normalized, document


def expected_resource_names(run_id: str) -> dict[str, list[str]]:
    prefix = f"aflow_target_e2e_{run_id}_"
    return {
        "networks": [prefix + suffix for suffix in NETWORK_SUFFIXES],
        "volumes": [prefix + suffix for suffix in VOLUME_SUFFIXES],
        "services": list(EXPECTED_SERVICES),
    }


def current_owner() -> dict[str, str]:
    return {"hostname": platform.node(), "user": getpass.getuser()}


def load_run_lock(path: Path, *, require_active: bool = True) -> dict[str, Any]:
    assert_regular_single_link(path, "run lock")
    lock = load_json(path)
    if (
        set(lock) != RUN_LOCK_KEYS
        or lock.get("schema_version") != "target-e2e-host-lock.v1"
    ):
        raise TargetE2EError("run lock fields drifted")
    verify_self_hash(lock, "run lock")
    if require_active and lock.get("state") != "ACTIVE":
        raise TargetE2EError("run lock is not ACTIVE")
    if lock.get("owner") != current_owner():
        raise TargetE2EError("run lock owner does not match this host user")
    run_id = lock.get("run_id")
    if not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id):
        raise TargetE2EError("run lock ID is invalid")
    project_name = f"aflow-target-e2e-{run_id}"
    if lock.get("project_name") != project_name:
        raise TargetE2EError("run lock project is not derived from its run ID")
    if not isinstance(lock.get("candidate_sha"), str) or not SHA1.fullmatch(
        lock["candidate_sha"]
    ):
        raise TargetE2EError("run lock candidate SHA is invalid")
    if not isinstance(lock.get("image_lock_hash"), str) or not SHA256.fullmatch(
        lock["image_lock_hash"]
    ):
        raise TargetE2EError("run lock image-lock hash is invalid")
    if not isinstance(lock.get("lock_nonce"), str) or not SHA256.fullmatch(
        lock["lock_nonce"]
    ):
        raise TargetE2EError("run lock nonce is invalid")
    if (
        type(lock.get("gateway_port")) is not int
        or not 25180 <= lock["gateway_port"] <= 25999
    ):
        raise TargetE2EError("run lock gateway port is outside the reserved range")
    runtime_root = assert_external_runtime_path(Path(lock.get("runtime_root", "")))
    run_directory = runtime_root / run_id
    expected_paths = {
        "run_directory": run_directory,
        "env_file": run_directory / "target-e2e.env",
        "port_lock": runtime_root
        / ".locks"
        / f"gateway-{lock['gateway_port']}.lock.json",
    }
    for key, expected_path in expected_paths.items():
        if Path(lock.get(key, "")).resolve() != expected_path.resolve():
            raise TargetE2EError(f"run lock path is not host-derived: {key}")
    expected_lock_path = runtime_root / ".locks" / f"{project_name}.lock.json"
    if path.resolve() != expected_lock_path.resolve():
        raise TargetE2EError("run lock path is not the host-derived project lock")
    if lock.get("resources") != expected_resource_names(run_id):
        raise TargetE2EError("run lock resource inventory drifted")
    if require_active and (
        not isinstance(lock.get("ledger_public_key_sha256"), str)
        or not SHA256.fullmatch(lock["ledger_public_key_sha256"])
    ):
        raise TargetE2EError("active run lock has no trusted ledger key fingerprint")
    return lock


def validate_port_lock(lock: dict[str, Any]) -> dict[str, Any]:
    path = Path(lock["port_lock"])
    assert_regular_single_link(path, "gateway port lock")
    document = load_json(path)
    verify_self_hash(document, "gateway port lock")
    if (
        set(document) != PORT_LOCK_KEYS
        or document.get("schema_version") != "target-e2e-port-lock.v1"
    ):
        raise TargetE2EError("gateway port lock fields drifted")
    expected = {
        "state": "ACTIVE",
        "gateway_port": lock["gateway_port"],
        "project_name": lock["project_name"],
        "run_id": lock["run_id"],
        "lock_nonce": lock["lock_nonce"],
        "owner": lock["owner"],
    }
    if any(document.get(key) != value for key, value in expected.items()):
        raise TargetE2EError("gateway port lock is not active for this exact run")
    return document


def validate_env_lock(env_file: Path) -> tuple[dict[str, str], dict[str, Any]]:
    env_file = assert_external_runtime_path(env_file)
    assert_regular_single_link(env_file, "target E2E env file")
    env = parse_env_file(env_file)
    lock_path = Path(env.get("TARGET_E2E_LOCK_PATH", ""))
    lock = load_run_lock(lock_path)
    expected = {
        "TARGET_E2E_RUN_ID": lock["run_id"],
        "TARGET_E2E_PROJECT_NAME": lock["project_name"],
        "TARGET_E2E_LOCK_NONCE": lock["lock_nonce"],
        "TARGET_E2E_BUILD_ID": lock["candidate_sha"],
        "TARGET_E2E_SOURCE_COMMIT": lock["candidate_sha"],
        "TARGET_E2E_IMAGE_LOCK_HASH": lock["image_lock_hash"],
        "TARGET_E2E_GATEWAY_PORT": str(lock["gateway_port"]),
    }
    for key, value in expected.items():
        if env.get(key) != value:
            raise TargetE2EError(f"env file does not match host lock field {key}")
    if env_file.resolve() != Path(lock["env_file"]).resolve():
        raise TargetE2EError("env file path does not match the host lock")
    if Path(lock["run_directory"]).resolve() != env_file.parent.resolve():
        raise TargetE2EError("run directory does not match the locked env directory")
    run_directory = Path(lock["run_directory"])
    exact_runtime_paths = {
        "TARGET_E2E_IMAGE_LOCK_PATH": run_directory / "image-lock.snapshot.json",
        "TARGET_E2E_RUN_CONTEXT_PATH": run_directory / "run-context.json",
        "TARGET_E2E_SECRETS_DIR": run_directory / "secrets",
        "TARGET_E2E_PUBLIC_DIR": run_directory / "public",
        "TARGET_E2E_ACTIVATION_DIR": run_directory / "java-activation",
        "TARGET_E2E_EVIDENCE_DIR": run_directory / "evidence",
        "TARGET_E2E_SOCKET_DIR": run_directory / "python-socket",
    }
    for key, expected_path in exact_runtime_paths.items():
        if Path(env.get(key, "")).resolve() != expected_path.resolve():
            raise TargetE2EError(f"env file redirects locked runtime path {key}")
    validate_port_lock(lock)
    return env, lock


def ledger_context_from_run_context(run_context: dict[str, Any]) -> dict[str, Any]:
    verify_self_hash(run_context, "run context")
    if (
        set(run_context) != RUN_CONTEXT_KEYS
        or run_context.get("schema_version") != "target-e2e-run-context.v2"
    ):
        raise TargetE2EError("run context fields drifted")
    projection = run_context.get("runtime_projection")
    if (
        not isinstance(projection, dict)
        or set(projection) != RUNTIME_PROJECTION_KEYS
        or projection.get("schemaVersion") != "graph-target-e2e-runtime-context.v1"
        or projection.get("executionLane") != "TARGET_E2E_CANDIDATE"
        or projection.get("perCommandManifestAllowed") is not False
    ):
        raise TargetE2EError("run context runtime projection fields drifted")
    for key in (
        "activationId",
        "environmentId",
        "runNonce",
        "tenantSurrogate",
        "temporalNamespace",
    ):
        if not isinstance(projection[key], str) or not TOKEN.fullmatch(projection[key]):
            raise TargetE2EError(f"runtime projection token is invalid: {key}")
    if (
        type(projection["environmentGeneration"]) is not int
        or projection["environmentGeneration"] < 1
    ):
        raise TargetE2EError("runtime projection environment generation is invalid")
    if not isinstance(projection["candidateSha"], str) or not SHA1.fullmatch(
        projection["candidateSha"]
    ):
        raise TargetE2EError("runtime projection candidate SHA is invalid")
    if (
        not isinstance(projection["activationManifestHash"], str)
        or not SHA256.fullmatch(projection["activationManifestHash"])
        or projection["activationManifestHash"]
        != run_context["activation_manifest_hash"]
    ):
        raise TargetE2EError("runtime projection activation manifest hash is invalid")
    parse_timestamp(projection["issuedAt"], "runtime projection issuedAt")
    parse_timestamp(projection["expiresAt"], "runtime projection expiresAt")
    if projection["allowedRoomTypes"] != ["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]:
        raise TargetE2EError("runtime projection room inventory drifted")
    project = projection.get("composeProject")
    if not isinstance(project, str) or not project.startswith("aflow-target-e2e-"):
        raise TargetE2EError("runtime projection Compose project is invalid")
    run_id = project.removeprefix("aflow-target-e2e-")
    if not RUN_ID.fullmatch(run_id) or run_context[
        "resources"
    ] != expected_resource_names(run_id):
        raise TargetE2EError("runtime projection resource inventory drifted")
    build_bindings = projection["buildBindings"]
    if (
        not isinstance(build_bindings, dict)
        or set(build_bindings)
        != {
            "caseBuildId",
            "controlBuildId",
            "agentBuildId",
        }
        or any(
            not isinstance(value, str) or not TOKEN.fullmatch(value)
            for value in build_bindings.values()
        )
    ):
        raise TargetE2EError("runtime projection build bindings are invalid")
    image_digests = projection["imageDigests"]
    if (
        not isinstance(image_digests, dict)
        or set(image_digests)
        != {
            "javaApi",
            "temporalControlWorker",
            "temporalAgentWorker",
            "pythonAgent",
            "frontend",
        }
        or any(
            not isinstance(value, str) or not DIGEST.fullmatch(value)
            for value in image_digests.values()
        )
    ):
        raise TargetE2EError("runtime projection image digests are invalid")
    database_identities = projection["databaseIdentities"]
    if not isinstance(database_identities, dict) or set(database_identities) != {
        "domain",
        "graph",
    }:
        raise TargetE2EError("runtime projection database identities drifted")
    domain = database_identities["domain"]
    graph = database_identities["graph"]
    if domain != {
        "service": "domain-db",
        "database": "target_domain",
        "schema": "public",
        "expectedUser": "domain_app",
    }:
        raise TargetE2EError("runtime projection Domain identity is not authority-free")
    if (
        not isinstance(graph, dict)
        or set(graph)
        != {
            "service",
            "database",
            "schema",
            "runtimeUser",
            "environmentGeneration",
            "restoreVerificationHash",
        }
        or any(
            graph.get(key) != value
            for key, value in {
                "service": "graph-db",
                "database": "target_graph",
                "schema": "graph_runtime",
                "runtimeUser": "graph_runtime",
            }.items()
        )
    ):
        raise TargetE2EError("runtime projection Graph identity drifted")
    if (
        type(graph["environmentGeneration"]) is not int
        or graph["environmentGeneration"] != projection["environmentGeneration"]
    ):
        raise TargetE2EError("runtime projection Graph generation is invalid")
    if not isinstance(graph["restoreVerificationHash"], str) or not SHA256.fullmatch(
        graph["restoreVerificationHash"]
    ):
        raise TargetE2EError("runtime projection restore verification hash is invalid")
    trusted_key_ids = projection["trustedSigningKeyIds"]
    if (
        not isinstance(trusted_key_ids, list)
        or not trusted_key_ids
        or len(trusted_key_ids) != len(set(trusted_key_ids))
        or any(
            not isinstance(key_id, str) or not TOKEN.fullmatch(key_id)
            for key_id in trusted_key_ids
        )
    ):
        raise TargetE2EError("runtime projection trusted signing keys are invalid")
    if not isinstance(projection["caseScope"], dict):
        raise TargetE2EError("runtime projection case scope is missing")
    executor_bindings = run_context["executor_bindings"]
    if not isinstance(executor_bindings, list) or len(executor_bindings) != 1:
        raise TargetE2EError("run context must contain exactly one composite executor binding")
    executor_binding = executor_bindings[0]
    if not isinstance(executor_binding, dict) or set(executor_binding) != TARGET_EXECUTOR_BINDING_KEYS:
        raise TargetE2EError("target E2E executor binding fields drifted")
    expected_executor_values = {
        "graph_key": TARGET_E2E_GRAPH_KEY,
        "graph_version": TARGET_E2E_GRAPH_VERSION,
        "checkpoint_schema_version": TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
        "output_schema_version": TARGET_E2E_OUTPUT_SCHEMA_VERSION,
        "tool_policy_version": TARGET_E2E_TOOL_POLICY_VERSION,
        "allowed_room_types": list(TARGET_E2E_ALLOWED_ROOM_TYPES),
        "allowed_stage_codes": list(TARGET_E2E_ALLOWED_STAGE_CODES),
    }
    if any(executor_binding.get(key) != value for key, value in expected_executor_values.items()):
        raise TargetE2EError("target E2E executor binding is not the all-room composite")
    for key in ("state_schema_hash", "binding_hash"):
        if not isinstance(executor_binding[key], str) or not SHA256.fullmatch(executor_binding[key]):
            raise TargetE2EError(f"target E2E executor binding hash is invalid: {key}")
    if not isinstance(executor_binding["code_build_id"], str) or not TOKEN.fullmatch(
        executor_binding["code_build_id"]
    ):
        raise TargetE2EError("target E2E executor code build ID is invalid")
    for key in (
        "activation_manifest_hash",
        "image_lock_hash",
        "jwks_sha256",
        "ledger_public_key_sha256",
        "lock_nonce",
    ):
        if not isinstance(run_context[key], str) or not SHA256.fullmatch(
            run_context[key]
        ):
            raise TargetE2EError(f"run context hash binding is invalid: {key}")
    mtls = run_context["mtls"]
    if not isinstance(mtls, dict) or set(mtls) != {
        "ca_certificate_sha256",
        "client_certificate_sha256",
        "expected_spiffe_id",
    }:
        raise TargetE2EError("run context mTLS binding fields drifted")
    if mtls["expected_spiffe_id"] != "spiffe://after-sale-flow/java-api-service" or any(
        not isinstance(mtls[key], str) or not SHA256.fullmatch(mtls[key])
        for key in ("ca_certificate_sha256", "client_certificate_sha256")
    ):
        raise TargetE2EError("run context mTLS binding is invalid")
    return {
        "run_context_hash": run_context["self_hash"],
        "candidate_sha": projection.get("candidateSha"),
        "activation_id": projection.get("activationId"),
        "environment_generation": projection.get("environmentGeneration"),
        "compose_project": projection.get("composeProject"),
        "temporal_namespace": projection.get("temporalNamespace"),
        "run_nonce": projection.get("runNonce"),
    }


def validate_run_context_bindings(
    run_context: dict[str, Any],
    env: dict[str, str],
    lock: dict[str, Any],
) -> dict[str, Any]:
    context = ledger_context_from_run_context(run_context)
    projection = run_context["runtime_projection"]
    _candidate, images, image_lock = load_image_lock(
        Path(env["TARGET_E2E_IMAGE_LOCK_PATH"])
    )
    expected_run_bindings = {
        "image_lock_hash": lock["image_lock_hash"],
        "lock_nonce": lock["lock_nonce"],
        "ledger_public_key_sha256": lock["ledger_public_key_sha256"],
        "resources": lock["resources"],
    }
    if any(
        run_context.get(key) != value for key, value in expected_run_bindings.items()
    ):
        raise TargetE2EError("run context does not match the atomic host lock")
    if (
        Path(run_context["image_lock_path"]).resolve()
        != Path(env["TARGET_E2E_IMAGE_LOCK_PATH"]).resolve()
    ):
        raise TargetE2EError("run context image-lock path is not the locked snapshot")
    if (
        image_lock["self_hash"] != lock["image_lock_hash"]
        or image_lock["candidate_sha"] != lock["candidate_sha"]
    ):
        raise TargetE2EError("image-lock snapshot does not match the atomic host lock")
    try:
        environment_generation = int(env.get("TARGET_E2E_ENVIRONMENT_GENERATION", ""))
    except ValueError as error:
        raise TargetE2EError("environment generation is not an integer") from error
    expected_projection_bindings: dict[str, Any] = {
        "candidateSha": lock["candidate_sha"],
        "composeProject": lock["project_name"],
        "activationId": env.get("TARGET_E2E_ACTIVATION_ID"),
        "environmentId": env.get("TARGET_E2E_ENVIRONMENT_ID"),
        "environmentGeneration": environment_generation,
        "temporalNamespace": env.get("TARGET_E2E_TEMPORAL_NAMESPACE"),
        "runNonce": env.get("TARGET_E2E_RUN_NONCE"),
    }
    if any(
        projection.get(key) != value
        for key, value in expected_projection_bindings.items()
    ):
        raise TargetE2EError("runtime projection does not match the locked environment")
    runtime_context_hash = canonical_sha256(projection)
    if (
        env.get("TARGET_E2E_ACTIVATION_MANIFEST_HASH")
        != run_context["activation_manifest_hash"]
        or env.get("TARGET_E2E_GRAPH_RUNTIME_CONTEXT_HASH") != runtime_context_hash
    ):
        raise TargetE2EError(
            "runtime projection hashes differ from the signed run context"
        )
    expected_image_digests = {
        "javaApi": images["java"]["manifest_digest"],
        "temporalControlWorker": images["java"]["manifest_digest"],
        "temporalAgentWorker": images["java"]["manifest_digest"],
        "pythonAgent": images["python"]["manifest_digest"],
        "frontend": images["frontend"]["manifest_digest"],
    }
    if projection["imageDigests"] != expected_image_digests:
        raise TargetE2EError(
            "runtime projection image digests differ from the image lock"
        )
    if run_context["mtls"]["ca_certificate_sha256"] != env.get(
        "TARGET_E2E_MTLS_CA_CERT_SHA256"
    ) or run_context["mtls"]["client_certificate_sha256"] != env.get(
        "TARGET_E2E_MTLS_CLIENT_CERT_SHA256"
    ):
        raise TargetE2EError(
            "runtime projection mTLS fingerprints differ from the environment"
        )
    jwks_path = Path(env["TARGET_E2E_PUBLIC_DIR"]) / "jwks" / "graph-jwks.json"
    if file_sha256(jwks_path) != run_context["jwks_sha256"]:
        raise TargetE2EError("static JWKS resource differs from the signed run context")
    try:
        env_projection = json.loads(env["GRAPH_TARGET_E2E_RUNTIME_CONTEXT"])
        env_bindings = json.loads(env["GRAPH_TARGET_E2E_BINDINGS"])
    except (KeyError, json.JSONDecodeError) as error:
        raise TargetE2EError(
            "Python runtime projection environment is invalid"
        ) from error
    if env_projection != projection or env_bindings != run_context["executor_bindings"]:
        raise TargetE2EError(
            "Python runtime projection differs from the signed run context"
        )
    return context


def parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw or raw.startswith("#"):
            continue
        if "=" not in raw:
            raise TargetE2EError(f"invalid env line {number}")
        key, value = raw.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in values:
            raise TargetE2EError(f"invalid or duplicate env key on line {number}")
        if value.startswith("'") and value.endswith("'"):
            value = value[1:-1]
        values[key] = value
    return values


def compose_argv(
    env_file: Path, *arguments: str, profile: str | None = None
) -> list[str]:
    command = [
        "docker",
        "compose",
        "--env-file",
        str(env_file.resolve()),
        "--project-directory",
        str(ROOT),
        "--file",
        str(COMPOSE_FILE),
    ]
    if profile:
        command.extend(("--profile", profile))
    command.extend(arguments)
    return command


def run_command(
    arguments: Iterable[str],
    *,
    timeout: int = 120,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        list(arguments),
        cwd=ROOT,
        check=False,
        capture_output=True,
        encoding="utf-8",
        errors="strict",
        timeout=timeout,
        shell=False,
        env={**os.environ, "COMPOSE_IGNORE_ORPHANS": "false"},
    )
    if check and completed.returncode:
        message = completed.stderr.strip() or completed.stdout.strip()
        raise TargetE2EError(f"command failed ({completed.returncode}): {message}")
    return completed


def container_id(env_file: Path, service: str) -> str:
    result = run_command(compose_argv(env_file, "ps", "--all", "--quiet", service))
    identifiers = [item for item in result.stdout.splitlines() if item]
    if len(identifiers) != 1:
        raise TargetE2EError(f"expected exactly one {service} container")
    return identifiers[0]


def env_quote(value: str) -> str:
    if "\x00" in value or "\n" in value or "\r" in value or "'" in value:
        raise TargetE2EError("env values must be single-line text")
    return "'" + value + "'"


def redact_environment(values: dict[str, Any]) -> dict[str, Any]:
    sensitive = ("PASSWORD", "SECRET", "TOKEN", "KEY", "DSN", "CREDENTIAL")
    return {
        key: "<redacted>" if any(part in key.upper() for part in sensitive) else value
        for key, value in values.items()
    }
