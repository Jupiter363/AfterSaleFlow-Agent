from __future__ import annotations

import copy
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import uuid
from pathlib import Path

import pytest

from scripts.phase8.candidate import command_contract
from scripts.phase8.candidate import runtime_policy


ROOT = Path(__file__).resolve().parents[2]
EXTERNAL_TEST_PARENT = Path(ROOT.anchor) if os.name == "nt" else Path("/tmp")
RUNTIME_ROOT = ROOT / "infra-tests" / "phase8" / "runtime"
DOCKERFILE = RUNTIME_ROOT / "Dockerfile"
REQUIREMENTS_IN = RUNTIME_ROOT / "requirements.in"
REQUIREMENTS_LOCK = RUNTIME_ROOT / "requirements.lock"
RUNTIME_POLICY = RUNTIME_ROOT / "runtime-policy.json"
BASE_IMAGE = (
    "docker.io/library/python@"
    "sha256:cdbd05fb6f457ca275ff51ce00d93d865ca0b6a25f5ffb08262d94f6835771e5"
)
DIRECT_REQUIREMENTS = {
    "cryptography": "49.0.0",
    "jsonschema": "4.26.0",
    "pytest": "9.0.2",
    "pyyaml": "6.0.3",
}
LOCKED_WHEELS = {
    "attrs": (
        "26.1.0",
        "c647aa4a12dfbad9333ca4e71fe62ddc36f4e63b2d260a37a8b83d2f043ac309",
    ),
    "cffi": (
        "2.1.0",
        "aa7a1b53a2a4452ada2d1b5dade9960b2522f1e61293a811a077439e39029565",
    ),
    "cryptography": (
        "49.0.0",
        "0e959b578856a3924bc0cbb710fc12c387b9412a951389f3ca61704a9e25f325",
    ),
    "iniconfig": (
        "2.3.0",
        "f631c04d2c48c52b84d0d0549c99ff3859c98df65b3101406327ecc7d53fbf12",
    ),
    "jsonschema": (
        "4.26.0",
        "d489f15263b8d200f8387e64b4c3a75f06629559fb73deb8fdfb525f2dab50ce",
    ),
    "jsonschema-specifications": (
        "2025.9.1",
        "98802fee3a11ee76ecaca44429fda8a41bff98b00a0f2838151b113f210cc6fe",
    ),
    "packaging": (
        "26.2",
        "5fc45236b9446107ff2415ce77c807cee2862cb6fac22b8a73826d0693b0980e",
    ),
    "pluggy": (
        "1.6.0",
        "e920276dd6813095e9377c0bc5566d94c932c33b27a3e3945d8389c374dd4746",
    ),
    "pycparser": (
        "3.0",
        "b727414169a36b7d524c1c3e31839a521725078d7b2ff038656844266160a992",
    ),
    "pygments": (
        "2.20.0",
        "81a9e26dd42fd28a23a2d169d86d7ac03b46e2f8b59ed4698fb4785f946d0176",
    ),
    "pytest": (
        "9.0.2",
        "711ffd45bf766d5264d487b917733b453d917afd2b0ad65223959f59089f875b",
    ),
    "pyyaml": (
        "6.0.3",
        "b8bb0864c5a28024fac8a632c443c87c5aa6f215c0b126c449ae1a150412f31d",
    ),
    "referencing": (
        "0.37.0",
        "381329a9f99628c9069361716891d34ad94af76e461dcb0335825aecc7692231",
    ),
    "rpds-py": (
        "2026.6.3",
        "9c1255b302953c86a486b81d330d5ee1d5bd937691ce271b6be0ef0e299eaab7",
    ),
    "typing-extensions": (
        "4.16.0",
        "481caa481374e813c1b176ada14e97f1f67a4539ce9cfeb3f350d78d6370c2e8",
    ),
}
LOCK_ENTRY = re.compile(
    r"(?m)^([A-Za-z0-9_.-]+)==([A-Za-z0-9_.+-]+) \\\r?\n"
    r"    --hash=sha256:([0-9a-f]{64})$"
)
EXPECTED_DOCKERFILE_INSTRUCTIONS = (
    f"FROM --platform=linux/amd64 {BASE_IMAGE} AS runtime",
    "ARG SOURCE_DATE_EPOCH=0",
    (
        'LABEL org.opencontainers.image.title="AfterSaleFlow Phase 8 engineering test runtime" '
        'org.opencontainers.image.base.digest="sha256:'
        'cdbd05fb6f457ca275ff51ce00d93d865ca0b6a25f5ffb08262d94f6835771e5" '
        'com.aftersaleflow.authority="ENGINEERING_TEST_EXECUTION_ONLY"'
    ),
    "COPY requirements.lock /opt/phase8/requirements.lock",
    (
        "RUN --network=none "
        "--mount=type=bind,from=wheelhouse,source=/,target=/wheelhouse,readonly "
        "python -m pip install --no-index --find-links=/wheelhouse --require-hashes "
        "--only-binary=:all: --no-compile --no-cache-dir --disable-pip-version-check "
        "--target=/opt/phase8/site-packages "
        "--requirement=/opt/phase8/requirements.lock "
        "&& find /opt/phase8/site-packages -type d -name __pycache__ -prune "
        "-exec rm -rf '{}' + "
        "&& find /opt/phase8/site-packages -type f -name '*.py[co]' -delete"
    ),
    (
        "ENV CI=1 HOME=/tmp LANG=C.UTF-8 LC_ALL=C.UTF-8 "
        "PIP_CONFIG_FILE=/dev/null PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1 "
        "PIP_NO_INDEX=1 PIP_ONLY_BINARY=:all: PYTHONDONTWRITEBYTECODE=1 "
        "PYTHONHASHSEED=0 PYTHONNOUSERSITE=1 "
        "PYTHONPATH=/opt/phase8/site-packages PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 "
        "TMPDIR=/tmp TZ=UTC"
    ),
    "WORKDIR /workspace",
    "USER 65532:65532",
    'CMD ["python"]',
)


def _canonical_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _parse_requirements_in(text: str) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)==([A-Za-z0-9_.+-]+)", line)
        if match is None:
            raise ValueError(
                "requirements.in contains a non-exact or unsafe requirement"
            )
        parsed[_canonical_name(match.group(1))] = match.group(2)
    return parsed


def _parse_lock(text: str) -> dict[str, tuple[str, str]]:
    if re.search(
        r"(?i)(?:https?://|git\+|svn\+|hg\+|bzr\+|--editable|(?:^|\s)-e\s)", text
    ):
        raise ValueError("lock contains a URL, VCS, or editable requirement")
    parsed: dict[str, tuple[str, str]] = {}
    for match in LOCK_ENTRY.finditer(text):
        name = _canonical_name(match.group(1))
        if name in parsed:
            raise ValueError("lock contains a duplicate distribution")
        parsed[name] = (match.group(2), match.group(3))
    residue = LOCK_ENTRY.sub("", text)
    residue = re.sub(r"(?m)^#.*$", "", residue)
    if residue.strip():
        raise ValueError("lock contains an unhashed or unrecognized requirement")
    return parsed


def _dockerfile_instructions(text: str) -> tuple[str, ...]:
    instructions: list[str] = []
    continuation: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("#"):
            raise ValueError(
                "Dockerfile parser directives and comments are not allowed"
            )
        continues = line.endswith("\\")
        if continues:
            line = line[:-1].rstrip()
        continuation.append(line)
        if continues:
            continue
        logical = re.sub(r"\s+", " ", " ".join(continuation)).strip()
        if re.fullmatch(r"[A-Z]+(?: .*)?", logical) is None:
            raise ValueError("Dockerfile contains a malformed or continued instruction")
        instructions.append(logical)
        continuation.clear()
    if continuation:
        raise ValueError("Dockerfile ends with an unterminated continuation")
    return tuple(instructions)


def _validate_dockerfile(text: str) -> None:
    instructions = _dockerfile_instructions(text)
    if instructions != EXPECTED_DOCKERFILE_INSTRUCTIONS:
        raise ValueError(
            "Dockerfile instruction sequence drifted from the closed runtime"
        )
    keywords = tuple(instruction.partition(" ")[0] for instruction in instructions)
    if keywords != (
        "FROM",
        "ARG",
        "LABEL",
        "COPY",
        "RUN",
        "ENV",
        "WORKDIR",
        "USER",
        "CMD",
    ):
        raise ValueError("Dockerfile does not contain the one exact ordered stage")


def _policy() -> dict[str, object]:
    return runtime_policy.validate_runtime_policy(
        runtime_policy.parse_bounded_json_bytes(RUNTIME_POLICY.read_bytes())
    )


def _command(command_id: str) -> dict[str, object]:
    contract = command_contract.load_command_contract()
    return copy.deepcopy(
        next(item for item in contract["commands"] if item["id"] == command_id)
    )


def _git_blob_sha1(payload: bytes) -> str:
    header = f"blob {len(payload)}\0".encode("ascii")
    return hashlib.sha1(header + payload).hexdigest()


def _manifest_for_root(root: Path) -> list[dict[str, object]]:
    manifest: list[dict[str, object]] = []
    for path in sorted((item for item in root.rglob("*") if item.is_file())):
        relative = path.relative_to(root).as_posix()
        payload = path.read_bytes()
        metadata = os.lstat(path)
        executable = bool(
            metadata.st_mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        )
        manifest.append(
            {
                "git_blob_sha": _git_blob_sha1(payload),
                "mode": "100755" if executable else "100644",
                "path": relative,
                "sha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
                "type": "blob",
            }
        )
    return manifest


def _candidate_archive_path(root: Path, command_id: str, created_nonce: str) -> Path:
    return root.parent / f".{root.name}-{command_id}-{created_nonce[:12]}.tar"


def _write_candidate_archive(
    root: Path, manifest: list[dict[str, object]], archive_path: Path
) -> bytes:
    with tarfile.open(archive_path, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for entry in manifest:
            payload = (root / str(entry["path"])).read_bytes()
            info = tarfile.TarInfo(name=str(entry["path"]))
            info.mode = 0o755 if entry["mode"] == "100755" else 0o644
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            info.mtime = 0
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
    return archive_path.read_bytes()


def _run_binding() -> dict[str, object]:
    repository = "example/after-sale-flow"
    trusted_sha = "7" * 40
    return {
        "caller_workflow_ref": (
            f"{repository}/{runtime_policy.CALLER_WORKFLOW_PATH}@"
            f"{runtime_policy.FIXED_CALLER_WORKFLOW_REF}"
        ),
        "caller_workflow_sha": "6" * 40,
        "repository": repository,
        "repository_id": runtime_policy.GITHUB_REPOSITORY_ID,
        "run_attempt": 1,
        "run_id": "123456789",
        "runner_arch": "X64",
        "runner_environment": "github-hosted",
        "runner_os": "Linux",
        "trusted_workflow_path": runtime_policy.TRUSTED_WORKFLOW_PATH,
        "trusted_workflow_ref": (
            f"{repository}/{runtime_policy.TRUSTED_WORKFLOW_PATH}@{trusted_sha}"
        ),
        "trusted_workflow_repository": repository,
        "trusted_workflow_sha": trusted_sha,
    }


def _job_identity(job_name: str) -> dict[str, object]:
    return {
        **_run_binding(),
        "job_name": job_name,
        "schema_version": runtime_policy.GITHUB_JOB_IDENTITY_SCHEMA_VERSION,
    }


def _materialization_receipt(
    root: Path,
    *,
    candidate_sha: str = "c" * 40,
    tree_sha: str = "d" * 40,
    manifest: list[dict[str, object]] | None = None,
    closure_kind: str = runtime_policy.FULL_REPOSITORY,
    command_id: str = "wave_a_static",
    derived_inventory_sha256: str | None = None,
    created_nonce: str = "1" * 64,
    verified_nonce: str = "2" * 64,
) -> tuple[
    dict[str, object],
    dict[str, object],
    dict[str, object],
    list[dict[str, object]],
]:
    materialization_manifest = (
        manifest if manifest is not None else _manifest_for_root(root)
    )
    validated_manifest, _, manifest_file_count, manifest_total_bytes = (
        runtime_policy.validate_materialization_manifest(materialization_manifest)
    )
    scope_inventory: dict[str, object] = {
        "entries": validated_manifest,
        "file_count": manifest_file_count,
        "manifest_sha256": runtime_policy.canonical_sha256(
            {
                "entries": validated_manifest,
                "file_count": manifest_file_count,
                "inventory_kind": closure_kind,
                "total_bytes": manifest_total_bytes,
            }
        ),
        "total_bytes": manifest_total_bytes,
    }
    archive_path = _candidate_archive_path(root, command_id, created_nonce)
    archive_payload = _write_candidate_archive(
        root, materialization_manifest, archive_path
    )
    scope_inventory_sha256 = runtime_policy.canonical_sha256(scope_inventory)
    producer_identity = _job_identity(runtime_policy.COMMAND_JOB_NAMES[command_id])
    receipt: dict[str, object] = {
        "accepted_a8": runtime_policy.ACCEPTED_A8,
        "candidate_archive_bytes": len(archive_payload),
        "candidate_archive_entry_count": manifest_file_count,
        "candidate_archive_format": runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
        "candidate_archive_sha256": hashlib.sha256(archive_payload).hexdigest(),
        "candidate_sha": candidate_sha,
        "candidate_tree_sha": tree_sha,
        "closure_kind": closure_kind,
        "command_id": command_id,
        "created_nonce": created_nonce,
        "exact_git_blobs": True,
        "manifest_file_count": manifest_file_count,
        "manifest_sha256": scope_inventory["manifest_sha256"],
        "manifest_total_bytes": manifest_total_bytes,
        "producer_job_identity": producer_identity,
        "producer_job_identity_sha256": runtime_policy.canonical_sha256(
            producer_identity
        ),
        "receipt_kind": runtime_policy.MATERIALIZATION_RECEIPT_KIND,
        "receipt_sha256": "0" * 64,
        "schema_version": runtime_policy.MATERIALIZATION_RECEIPT_SCHEMA_VERSION,
        "scope_inventory_sha256": (
            scope_inventory_sha256
            if derived_inventory_sha256 is None
            else derived_inventory_sha256
        ),
        "verified_nonce": verified_nonce,
    }
    receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(receipt)
    candidate_binding = {
        "accepted_entry_sha": receipt["accepted_a8"],
        "candidate_archive_bytes": receipt["candidate_archive_bytes"],
        "candidate_archive_entry_count": receipt["candidate_archive_entry_count"],
        "candidate_archive_format": receipt["candidate_archive_format"],
        "candidate_archive_sha256": receipt["candidate_archive_sha256"],
        "candidate_sha": receipt["candidate_sha"],
        "candidate_tree_sha": receipt["candidate_tree_sha"],
        "closure_kind": receipt["closure_kind"],
        "derived_inventory_sha256": receipt["scope_inventory_sha256"],
        "manifest_file_count": receipt["manifest_file_count"],
        "manifest_sha256": receipt["manifest_sha256"],
        "manifest_total_bytes": receipt["manifest_total_bytes"],
    }
    return receipt, candidate_binding, scope_inventory, materialization_manifest


def _test_oci_payloads(
    policy: dict[str, object],
) -> tuple[dict[str, bytes], dict[str, object]]:
    uncompressed_layers = [
        b"synthetic-pinned-base-layer\n",
        b"synthetic-runtime-layer-one\n",
        b"synthetic-runtime-layer-two\n",
    ]
    layers = [gzip.compress(uncompressed_layers[0], mtime=0), *uncompressed_layers[1:]]
    rootfs_layers = [
        f"sha256:{hashlib.sha256(payload).hexdigest()}"
        for payload in uncompressed_layers
    ]
    base_environment = {
        "PATH": "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin",
        "PYTHON_VERSION": "3.11.14",
    }
    final_environment = copy.deepcopy(base_environment)
    final_environment.update(policy["runtime"]["fixed_environment"])  # type: ignore[index]
    labels = {"org.opencontainers.image.vendor": "Docker Official Images"}
    labels.update(runtime_policy.REQUIRED_IMAGE_LABELS)
    image_config = {
        "architecture": "amd64",
        "config": {
            "ArgsEscaped": True,
            "Cmd": ["python"],
            "Entrypoint": None,
            "Env": [
                f"{key}={value}" for key, value in sorted(final_environment.items())
            ],
            "Labels": labels,
            "User": "65532:65532",
            "WorkingDir": "/workspace",
        },
        "os": "linux",
        "rootfs": {"diff_ids": rootfs_layers, "type": "layers"},
    }
    config_payload = json.dumps(
        image_config, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    config_hex = hashlib.sha256(config_payload).hexdigest()
    layer_descriptors = [
        {
            **(
                {}
                if index == 0
                else {"annotations": {"buildkit/rewritten-timestamp": "0"}}
            ),
            "digest": f"sha256:{hashlib.sha256(payload).hexdigest()}",
            "mediaType": (
                "application/vnd.oci.image.layer.v1.tar+gzip"
                if index == 0
                else "application/vnd.oci.image.layer.v1.tar"
            ),
            "size": len(payload),
        }
        for index, payload in enumerate(layers)
    ]
    image_manifest = {
        "config": {
            "digest": f"sha256:{config_hex}",
            "mediaType": "application/vnd.oci.image.config.v1+json",
            "size": len(config_payload),
        },
        "layers": layer_descriptors,
        "mediaType": "application/vnd.oci.image.manifest.v1+json",
        "schemaVersion": 2,
    }
    manifest_payload = json.dumps(
        image_manifest, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    manifest_hex = hashlib.sha256(manifest_payload).hexdigest()
    index_payload = json.dumps(
        {
            "manifests": [
                {
                    "annotations": {
                        "org.opencontainers.image.created": "1970-01-01T00:00:00Z"
                    },
                    "digest": f"sha256:{manifest_hex}",
                    "mediaType": "application/vnd.oci.image.manifest.v1+json",
                    "platform": {"architecture": "amd64", "os": "linux"},
                    "size": len(manifest_payload),
                }
            ],
            "mediaType": "application/vnd.oci.image.index.v1+json",
            "schemaVersion": 2,
        },
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    files = {
        "index.json": index_payload,
        "oci-layout": b'{"imageLayoutVersion":"1.0.0"}',
        f"blobs/sha256/{config_hex}": config_payload,
        f"blobs/sha256/{manifest_hex}": manifest_payload,
    }
    for descriptor, payload in zip(layer_descriptors, layers, strict=True):
        files[f"blobs/sha256/{descriptor['digest'].removeprefix('sha256:')}"] = payload
    projection = {
        "architecture": "amd64",
        "cmd": ["python"],
        "config_digest": f"sha256:{config_hex}",
        "entrypoint": None,
        "environment": [
            f"{key}={value}" for key, value in sorted(final_environment.items())
        ],
        "exposed_ports": [],
        "healthcheck": None,
        "image_id": f"sha256:{config_hex}",
        "labels": labels,
        "onbuild": [],
        "os": "linux",
        "rootfs_layers": rootfs_layers,
        "shell": [],
        "stop_signal": None,
        "user": "65532:65532",
        "volumes": [],
        "workdir": "/workspace",
    }
    return files, projection


def _reseal_test_oci_metadata(
    files: dict[str, bytes],
    index: dict[str, object],
    manifest: dict[str, object],
    config: dict[str, object],
) -> None:
    original_index = json.loads(files["index.json"])
    original_manifest_name = (
        "blobs/sha256/"
        f"{original_index['manifests'][0]['digest'].removeprefix('sha256:')}"
    )
    original_manifest = json.loads(files[original_manifest_name])
    original_config_name = (
        f"blobs/sha256/{original_manifest['config']['digest'].removeprefix('sha256:')}"
    )
    files.pop(original_manifest_name)
    files.pop(original_config_name)

    config_payload = json.dumps(
        config, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    config_digest = hashlib.sha256(config_payload).hexdigest()
    manifest["config"]["digest"] = f"sha256:{config_digest}"  # type: ignore[index]
    manifest["config"]["size"] = len(config_payload)  # type: ignore[index]
    manifest_payload = json.dumps(
        manifest, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    manifest_digest = hashlib.sha256(manifest_payload).hexdigest()
    index["manifests"][0]["digest"] = f"sha256:{manifest_digest}"  # type: ignore[index]
    index["manifests"][0]["size"] = len(manifest_payload)  # type: ignore[index]
    files[f"blobs/sha256/{config_digest}"] = config_payload
    files[f"blobs/sha256/{manifest_digest}"] = manifest_payload
    files["index.json"] = json.dumps(
        index, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")


def _write_test_oci_archive(path: Path, files: dict[str, bytes]) -> None:
    with tarfile.open(path, mode="w") as archive:
        for name, payload in sorted(files.items()):
            info = tarfile.TarInfo(name=name)
            info.mode = 0o644
            info.mtime = 0
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))


def _base_image_projection() -> dict[str, object]:
    base_layer = hashlib.sha256(b"synthetic-pinned-base-layer\n").hexdigest()
    base_config = f"sha256:{'a' * 64}"
    return {
        "architecture": "amd64",
        "cmd": ["python3"],
        "config_digest": base_config,
        "entrypoint": None,
        "environment": [
            "PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin",
            "PYTHON_VERSION=3.11.14",
        ],
        "exposed_ports": [],
        "healthcheck": None,
        "image_id": base_config,
        "labels": {"org.opencontainers.image.vendor": "Docker Official Images"},
        "onbuild": [],
        "os": "linux",
        "reference": runtime_policy.BASE_IMAGE,
        "rootfs_layers": [f"sha256:{base_layer}"],
        "shell": [],
        "stop_signal": None,
        "user": "0:0",
        "volumes": [],
        "workdir": "/",
    }


def _observer_oci_archive_path(producer_path: Path) -> Path:
    return producer_path.with_name(f"{producer_path.stem}-observer.tar")


def _producer_docker_archive_path(producer_path: Path) -> Path:
    return producer_path.with_name(f"{producer_path.stem}-execution-docker.tar")


def _observer_docker_archive_path(producer_path: Path) -> Path:
    return producer_path.with_name(
        f"{producer_path.stem}-observer-execution-docker.tar"
    )


def _wheelhouse_root(producer_path: Path) -> Path:
    return producer_path.with_name(f"{producer_path.stem}-wheelhouse")


def _write_test_docker_archive(path: Path) -> None:
    members = {
        "config.json": b"{}",
        "manifest.json": b'[{"Config":"config.json","Layers":[],"RepoTags":null}]',
    }
    with tarfile.open(path, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, payload in sorted(members.items()):
            info = tarfile.TarInfo(name=name)
            info.mode = 0o644
            info.mtime = 0
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))


def _runtime_build_receipt(
    policy: dict[str, object],
    oci_archive_path: Path,
    validated_command_contract: dict[str, object],
    *,
    code_sha: str = "c" * 40,
    code_tree_sha: str = "d" * 40,
) -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    dockerfile = DOCKERFILE.read_bytes()
    requirements_lock = REQUIREMENTS_LOCK.read_bytes()
    contract = validated_command_contract
    _, image_projection = _test_oci_payloads(policy)
    image_id = image_projection["image_id"]
    archive_payload = oci_archive_path.read_bytes()
    observer_archive_payload = _observer_oci_archive_path(oci_archive_path).read_bytes()
    docker_archive_payload = _producer_docker_archive_path(
        oci_archive_path
    ).read_bytes()
    observer_docker_archive_payload = _observer_docker_archive_path(
        oci_archive_path
    ).read_bytes()
    rootfs_layers = image_projection["rootfs_layers"]
    wheelhouse_manifest = [
        {
            "bytes": 1000 + index,
            "filename": f"{name.replace('-', '_')}-{version}-py3-none-any.whl",
            "sha256": sha256,
        }
        for index, (name, (version, sha256)) in enumerate(sorted(LOCKED_WHEELS.items()))
    ]
    wheelhouse_sha256 = runtime_policy.canonical_sha256(wheelhouse_manifest)
    builder_identity = _job_identity(runtime_policy.BUILD_JOB_NAME)
    observer_identity = _job_identity(runtime_policy.OBSERVER_JOB_NAME)
    build_parameters = copy.deepcopy(runtime_policy.BUILD_PARAMETERS)
    build_parameters_sha256 = runtime_policy.canonical_sha256(build_parameters)
    image_projection_sha256 = runtime_policy.canonical_sha256(image_projection)
    receipt: dict[str, object] = {
        "base_image": runtime_policy.BASE_IMAGE,
        "base_image_acquisition_network_profile": (
            runtime_policy.BASE_IMAGE_ACQUISITION_NETWORK_PROFILE
        ),
        "build_nonce": "3" * 64,
        "build_parameters": build_parameters,
        "build_parameters_sha256": build_parameters_sha256,
        "builder_job_identity": builder_identity,
        "builder_job_identity_sha256": runtime_policy.canonical_sha256(
            builder_identity
        ),
        "code_sha": code_sha,
        "code_tree_sha": code_tree_sha,
        "command_contract_sha256": command_contract.canonical_sha256(contract),
        "config_digest": image_id,
        "dockerfile_git_blob": _git_blob_sha1(dockerfile),
        "dockerfile_sha256": hashlib.sha256(dockerfile).hexdigest(),
        "docker_build_run_network": "none",
        "docker_archive_bytes": len(docker_archive_payload),
        "docker_archive_sha256": hashlib.sha256(docker_archive_payload).hexdigest(),
        "image_id": image_id,
        "image_inspect_projection": image_projection,
        "image_inspect_projection_sha256": image_projection_sha256,
        "oci_archive_bytes": len(archive_payload),
        "oci_archive_sha256": hashlib.sha256(archive_payload).hexdigest(),
        "platform": "linux/amd64",
        "receipt_kind": runtime_policy.RUNTIME_BUILD_RECEIPT_KIND,
        "receipt_sha256": "0" * 64,
        "requirements_lock_git_blob": _git_blob_sha1(requirements_lock),
        "requirements_lock_sha256": hashlib.sha256(requirements_lock).hexdigest(),
        "rootfs_digest": runtime_policy.canonical_sha256(rootfs_layers),
        "runtime_policy_sha256": runtime_policy.canonical_sha256(policy),
        "schema_version": runtime_policy.RUNTIME_BUILD_RECEIPT_SCHEMA_VERSION,
        "verified_nonce": "4" * 64,
        "wheelhouse_acquisition_network_profile": (
            runtime_policy.WHEELHOUSE_ACQUISITION_NETWORK_PROFILE
        ),
        "wheelhouse_manifest": wheelhouse_manifest,
        "wheelhouse_manifest_sha256": wheelhouse_sha256,
    }
    receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(receipt)
    provenance_keys = {
        "base_image",
        "base_image_acquisition_network_profile",
        "build_parameters",
        "build_parameters_sha256",
        "builder_job_identity",
        "builder_job_identity_sha256",
        "code_sha",
        "code_tree_sha",
        "command_contract_sha256",
        "dockerfile_git_blob",
        "dockerfile_sha256",
        "docker_build_run_network",
        "docker_archive_bytes",
        "docker_archive_sha256",
        "image_inspect_projection_sha256",
        "oci_archive_bytes",
        "oci_archive_sha256",
        "platform",
        "requirements_lock_git_blob",
        "requirements_lock_sha256",
        "runtime_policy_sha256",
        "wheelhouse_acquisition_network_profile",
        "wheelhouse_manifest_sha256",
    }
    build_provenance = {key: copy.deepcopy(receipt[key]) for key in provenance_keys}
    observation: dict[str, object] = {
        "base_image_inspect_projection": _base_image_projection(),
        "base_image_inspect_projection_sha256": runtime_policy.canonical_sha256(
            _base_image_projection()
        ),
        "build_provenance": build_provenance,
        "build_provenance_sha256": runtime_policy.canonical_sha256(build_provenance),
        "observer_build_parameters": copy.deepcopy(build_parameters),
        "observer_build_parameters_sha256": build_parameters_sha256,
        "observer_image_inspect_projection": copy.deepcopy(image_projection),
        "observer_image_inspect_projection_sha256": image_projection_sha256,
        "observer_job_identity": observer_identity,
        "observer_job_identity_sha256": runtime_policy.canonical_sha256(
            observer_identity
        ),
        "observer_nonce": "5" * 64,
        "observer_docker_archive_bytes": len(observer_docker_archive_payload),
        "observer_docker_archive_sha256": hashlib.sha256(
            observer_docker_archive_payload
        ).hexdigest(),
        "observer_oci_archive_bytes": len(observer_archive_payload),
        "observer_oci_archive_sha256": hashlib.sha256(
            observer_archive_payload
        ).hexdigest(),
        "producer_image_inspect_projection": copy.deepcopy(image_projection),
        "producer_image_inspect_projection_sha256": image_projection_sha256,
        "producer_docker_archive_bytes": len(docker_archive_payload),
        "producer_docker_archive_sha256": hashlib.sha256(
            docker_archive_payload
        ).hexdigest(),
        "producer_oci_archive_bytes": len(archive_payload),
        "producer_oci_archive_sha256": hashlib.sha256(archive_payload).hexdigest(),
        "receipt_kind": runtime_policy.BUILD_OBSERVATION_RECEIPT_KIND,
        "receipt_sha256": "0" * 64,
        "schema_version": runtime_policy.BUILD_OBSERVATION_RECEIPT_SCHEMA_VERSION,
        "source_build_nonce": receipt["build_nonce"],
        "source_build_receipt_sha256": receipt["receipt_sha256"],
        "wheelhouse_manifest": wheelhouse_manifest,
        "wheelhouse_manifest_sha256": wheelhouse_sha256,
    }
    observation["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(observation)
    observation_binding_keys = {
        "base_image_inspect_projection",
        "base_image_inspect_projection_sha256",
        "build_provenance",
        "build_provenance_sha256",
        "observer_build_parameters",
        "observer_build_parameters_sha256",
        "observer_image_inspect_projection",
        "observer_image_inspect_projection_sha256",
        "observer_job_identity",
        "observer_job_identity_sha256",
        "observer_docker_archive_bytes",
        "observer_docker_archive_sha256",
        "observer_oci_archive_bytes",
        "observer_oci_archive_sha256",
        "producer_image_inspect_projection",
        "producer_image_inspect_projection_sha256",
        "producer_docker_archive_bytes",
        "producer_docker_archive_sha256",
        "producer_oci_archive_bytes",
        "producer_oci_archive_sha256",
        "source_build_receipt_sha256",
        "wheelhouse_manifest",
        "wheelhouse_manifest_sha256",
    }
    observation_binding = {
        key: copy.deepcopy(observation[key]) for key in observation_binding_keys
    }
    return receipt, observation, observation_binding


def _observation_binding(observation: dict[str, object]) -> dict[str, object]:
    return {
        key: copy.deepcopy(observation[key])
        for key in (
            "base_image_inspect_projection",
            "base_image_inspect_projection_sha256",
            "build_provenance",
            "build_provenance_sha256",
            "observer_build_parameters",
            "observer_build_parameters_sha256",
            "observer_image_inspect_projection",
            "observer_image_inspect_projection_sha256",
            "observer_job_identity",
            "observer_job_identity_sha256",
            "observer_docker_archive_bytes",
            "observer_docker_archive_sha256",
            "observer_oci_archive_bytes",
            "observer_oci_archive_sha256",
            "producer_image_inspect_projection",
            "producer_image_inspect_projection_sha256",
            "producer_docker_archive_bytes",
            "producer_docker_archive_sha256",
            "producer_oci_archive_bytes",
            "producer_oci_archive_sha256",
            "source_build_receipt_sha256",
            "wheelhouse_manifest",
            "wheelhouse_manifest_sha256",
        )
    }


def _reseal_wheel_observation(
    build: dict[str, object], observation: dict[str, object]
) -> None:
    wheelhouse_sha = runtime_policy.canonical_sha256(observation["wheelhouse_manifest"])
    build["wheelhouse_manifest"] = copy.deepcopy(observation["wheelhouse_manifest"])
    build["wheelhouse_manifest_sha256"] = wheelhouse_sha
    build["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(build)
    provenance = observation["build_provenance"]
    provenance["wheelhouse_manifest_sha256"] = wheelhouse_sha  # type: ignore[index]
    observation["build_provenance_sha256"] = runtime_policy.canonical_sha256(provenance)
    observation["wheelhouse_manifest_sha256"] = wheelhouse_sha
    observation["source_build_receipt_sha256"] = build["receipt_sha256"]
    observation["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(observation)


@pytest.fixture
def external_candidate_root() -> Path:
    root = EXTERNAL_TEST_PARENT / f".phase8-runtime-test-{uuid.uuid4().hex}"
    root.mkdir(parents=False, exist_ok=False)
    (root / "nested").mkdir()
    (root / "case.txt").write_bytes(b"candidate-case-v1\n")
    (root / "nested" / "evidence.json").write_bytes(b'{"status":"synthetic"}\n')
    try:
        yield root
    finally:
        shutil.rmtree(root, ignore_errors=True)
        for archive_path in root.parent.glob(f".{root.name}-*.tar"):
            archive_path.unlink(missing_ok=True)


@pytest.fixture
def oci_archive_path() -> Path:
    path = EXTERNAL_TEST_PARENT / f".phase8-runtime-image-{uuid.uuid4().hex}.tar"
    observer_path = _observer_oci_archive_path(path)
    docker_path = _producer_docker_archive_path(path)
    observer_docker_path = _observer_docker_archive_path(path)
    wheelhouse_root = _wheelhouse_root(path)
    files, _ = _test_oci_payloads(_policy())
    with tarfile.open(path, mode="w") as archive:
        for name, payload in sorted(files.items()):
            info = tarfile.TarInfo(name=name)
            info.mode = 0o644
            info.mtime = 0
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
    shutil.copyfile(path, observer_path)
    _write_test_docker_archive(docker_path)
    shutil.copyfile(docker_path, observer_docker_path)
    wheelhouse_root.mkdir()
    for index, (name, (version, _)) in enumerate(sorted(LOCKED_WHEELS.items())):
        filename = f"{name.replace('-', '_')}-{version}-py3-none-any.whl"
        (wheelhouse_root / filename).write_bytes(b"x" * (1000 + index))
    try:
        yield path
    finally:
        path.unlink(missing_ok=True)
        observer_path.unlink(missing_ok=True)
        docker_path.unlink(missing_ok=True)
        observer_docker_path.unlink(missing_ok=True)
        shutil.rmtree(wheelhouse_root, ignore_errors=True)


def _valid_dispatch(
    command_id: str,
    root: Path,
    oci_archive_path: Path,
    *,
    created_nonce: str = "1" * 64,
    verified_nonce: str = "2" * 64,
) -> dict[str, object]:
    policy = _policy()
    validated_contract = command_contract.load_command_contract()
    command = copy.deepcopy(
        next(
            item for item in validated_contract["commands"] if item["id"] == command_id
        )
    )
    expected_run_binding = _run_binding()
    expected_builder_job_identity = _job_identity(runtime_policy.BUILD_JOB_NAME)
    (
        materialization_receipt,
        expected_candidate_binding,
        expected_scope_inventory,
        materialization_manifest,
    ) = _materialization_receipt(
        root,
        command_id=command_id,
        created_nonce=created_nonce,
        verified_nonce=verified_nonce,
    )
    candidate_archive_path = _candidate_archive_path(root, command_id, created_nonce)
    _, candidate_binding, archive_handle = (
        runtime_policy.assert_materialization_authorized_live(
            materialization_receipt,
            materialization_manifest,
            expected_candidate_binding,
            expected_scope_inventory,
            expected_run_binding,
            candidate_archive_path,
        )
    )
    archive_evidence = archive_handle.evidence()
    archive_handle.close()
    build_receipt, build_observation, observation_binding = _runtime_build_receipt(
        policy, oci_archive_path, validated_contract
    )
    build, build_binding, observation = runtime_policy.validate_runtime_build_receipt(
        build_receipt,
        build_observation,
        observation_binding,
        expected_run_binding=expected_run_binding,
        expected_builder_job_identity=expected_builder_job_identity,
        producer_oci_archive_path=oci_archive_path,
        producer_docker_archive_path=_producer_docker_archive_path(oci_archive_path),
        policy=policy,
        validated_command_contract=validated_contract,
    )
    dispatch = runtime_policy._expected_dispatch(
        command,
        policy,
        materialization_receipt,
        candidate_binding,
        build,
        build_binding,
        observation,
        archive_evidence,
    )
    return {
        "build_observation": build_observation,
        "build_receipt": build_receipt,
        "candidate_binding": candidate_binding,
        "candidate_archive_path": candidate_archive_path,
        "command": command,
        "dispatch": dispatch,
        "expected_candidate_binding": expected_candidate_binding,
        "expected_builder_job_identity": expected_builder_job_identity,
        "expected_run_binding": expected_run_binding,
        "expected_scope_inventory": expected_scope_inventory,
        "materialization_manifest": materialization_manifest,
        "materialization_receipt": materialization_receipt,
        "observation_binding": observation_binding,
        "oci_archive_path": oci_archive_path,
        "observer_oci_archive_path": _observer_oci_archive_path(oci_archive_path),
        "producer_docker_archive_path": _producer_docker_archive_path(oci_archive_path),
        "observer_docker_archive_path": _observer_docker_archive_path(oci_archive_path),
        "policy": policy,
        "validated_contract": validated_contract,
        "wheelhouse_root": _wheelhouse_root(oci_archive_path),
    }


def _authorize(bundle: dict[str, object]) -> str:
    dispatch_sha256, archive_handle = runtime_policy.assert_static_dispatch_authorized(
        bundle["command"],  # type: ignore[arg-type]
        bundle["dispatch"],  # type: ignore[arg-type]
        bundle["policy"],  # type: ignore[arg-type]
        materialization_receipt=bundle["materialization_receipt"],  # type: ignore[arg-type]
        materialization_manifest=bundle["materialization_manifest"],
        expected_candidate_binding=bundle["expected_candidate_binding"],  # type: ignore[arg-type]
        expected_scope_inventory=bundle["expected_scope_inventory"],  # type: ignore[arg-type]
        expected_run_binding=bundle["expected_run_binding"],  # type: ignore[arg-type]
        candidate_archive_path=bundle["candidate_archive_path"],  # type: ignore[arg-type]
        validated_command_contract=bundle["validated_contract"],  # type: ignore[arg-type]
        validated_shared_runtime=_validated_shared_runtime(bundle),
    )
    sink = io.BytesIO()
    consumption = archive_handle.stream_into(sink)
    assert hashlib.sha256(sink.getvalue()).hexdigest() == consumption["archive_sha256"]
    return dispatch_sha256


def _artifact_transport_receipt(
    bundle: dict[str, object], dispatch_sha256: str
) -> tuple[dict[str, object], dict[str, object]]:
    command = bundle["command"]
    archive_path = command["report"]["expected_artifacts"][0]["archive_path"]  # type: ignore[index]
    junit_index = [{"archive_path": archive_path, "bytes": 123, "sha256": "5" * 64}]
    executor_name = (
        "phase8_wave_a_static"
        if command["id"] == "wave_a_static"  # type: ignore[index]
        else "phase8_wave_b_static_and_models"
    )
    producer_identity = _job_identity(executor_name)
    artifact_prefix = (
        "phase8-raw-000-wave_a_static"
        if command["id"] == "wave_a_static"  # type: ignore[index]
        else "phase8-raw-002-wave_b_static_and_models"
    )
    receipt: dict[str, object] = {
        "artifact_name": (
            f"{artifact_prefix}-{producer_identity['run_id']}-"
            f"{producer_identity['run_attempt']}"
        ),
        "artifact_payload_kind": runtime_policy.ARTIFACT_PAYLOAD_KIND,
        "artifact_payload_sha256": runtime_policy.canonical_junit_file_index_sha256(
            junit_index
        ),
        "build_observation_receipt_sha256": bundle["build_observation"][  # type: ignore[index]
            "receipt_sha256"
        ],
        "command_id": command["id"],  # type: ignore[index]
        "dispatch_sha256": dispatch_sha256,
        "materialization_receipt_sha256": bundle["materialization_receipt"][  # type: ignore[index]
            "receipt_sha256"
        ],
        "manifest_sha256": bundle["materialization_receipt"]["manifest_sha256"],  # type: ignore[index]
        "oci_archive_sha256": bundle["build_receipt"]["oci_archive_sha256"],  # type: ignore[index]
        "producer_job_identity": producer_identity,
        "producer_job_identity_sha256": runtime_policy.canonical_sha256(
            producer_identity
        ),
        "receipt_kind": runtime_policy.ARTIFACT_TRANSPORT_RECEIPT_KIND,
        "receipt_sha256": "0" * 64,
        "runtime_build_receipt_sha256": bundle["build_receipt"][  # type: ignore[index]
            "receipt_sha256"
        ],
        "schema_version": runtime_policy.ARTIFACT_TRANSPORT_RECEIPT_SCHEMA_VERSION,
        "transport_nonce": (
            "8" * 64 if command["id"] == "wave_a_static" else "9" * 64  # type: ignore[index]
        ),
    }
    receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(receipt)
    excluded = {"receipt_kind", "receipt_sha256", "schema_version", "transport_nonce"}
    binding = {
        key: copy.deepcopy(value)
        for key, value in receipt.items()
        if key not in excluded
    }
    return receipt, binding


def _validated_shared_runtime(bundle: dict[str, object]) -> object:
    wheelhouse_root = Path(bundle["wheelhouse_root"])  # type: ignore[arg-type]
    manifest = bundle["build_receipt"]["wheelhouse_manifest"]  # type: ignore[index]
    expected_by_name = {entry["filename"]: entry for entry in manifest}
    original_hash = runtime_policy._hash_regular_file_no_follow

    def trusted_wheel_hash(
        path: Path, *, max_bytes: int
    ) -> tuple[int, str, str, list[int]]:
        if path.parent == wheelhouse_root:
            entry = expected_by_name[path.name]
            metadata = os.lstat(path)
            return (
                int(entry["bytes"]),
                str(entry["sha256"]),
                "0" * 40,
                runtime_policy._lstat_identity(metadata),
            )
        return original_hash(path, max_bytes=max_bytes)

    runtime_policy._hash_regular_file_no_follow = trusted_wheel_hash
    try:
        return runtime_policy.verify_shared_runtime_receipts(
            bundle["build_receipt"],  # type: ignore[arg-type]
            bundle["build_observation"],  # type: ignore[arg-type]
            bundle["observation_binding"],  # type: ignore[arg-type]
            expected_run_binding=bundle["expected_run_binding"],  # type: ignore[arg-type]
            expected_builder_job_identity=bundle[  # type: ignore[arg-type]
                "expected_builder_job_identity"
            ],
            producer_oci_archive_path=bundle["oci_archive_path"],  # type: ignore[arg-type]
            observer_oci_archive_path=bundle["observer_oci_archive_path"],  # type: ignore[arg-type]
            producer_docker_archive_path=bundle["producer_docker_archive_path"],  # type: ignore[arg-type]
            observer_docker_archive_path=bundle["observer_docker_archive_path"],  # type: ignore[arg-type]
            wheelhouse_root=wheelhouse_root,
            policy=bundle["policy"],  # type: ignore[arg-type]
            validated_command_contract=bundle["validated_contract"],  # type: ignore[arg-type]
        )
    finally:
        runtime_policy._hash_regular_file_no_follow = original_hash


def _verify_offline(
    bundle: dict[str, object],
    transport_receipt: dict[str, object],
    transport_binding: dict[str, object],
    *,
    validated_shared_runtime: object | None = None,
) -> str:
    shared_runtime = validated_shared_runtime
    if shared_runtime is None:
        shared_runtime = _validated_shared_runtime(bundle)
    dispatch_sha256, _ = runtime_policy.verify_static_dispatch_receipts(
        bundle["command"],  # type: ignore[arg-type]
        bundle["dispatch"],  # type: ignore[arg-type]
        bundle["policy"],  # type: ignore[arg-type]
        materialization_receipt=bundle["materialization_receipt"],  # type: ignore[arg-type]
        materialization_manifest=bundle["materialization_manifest"],
        expected_candidate_binding=bundle["expected_candidate_binding"],  # type: ignore[arg-type]
        expected_scope_inventory=bundle["expected_scope_inventory"],  # type: ignore[arg-type]
        expected_run_binding=bundle["expected_run_binding"],  # type: ignore[arg-type]
        candidate_archive_path=bundle["candidate_archive_path"],  # type: ignore[arg-type]
        validated_command_contract=bundle["validated_contract"],  # type: ignore[arg-type]
        validated_shared_runtime=shared_runtime,
        artifact_transport_receipt=transport_receipt,
        expected_transport_binding=transport_binding,
    )
    return dispatch_sha256


def test_direct_requirements_are_the_exact_minimum_toolset() -> None:
    assert _parse_requirements_in(REQUIREMENTS_IN.read_text(encoding="ascii")) == (
        DIRECT_REQUIREMENTS
    )


def test_lock_is_the_exact_hashed_linux_wheel_closure() -> None:
    assert _parse_lock(REQUIREMENTS_LOCK.read_text(encoding="ascii")) == LOCKED_WHEELS


@pytest.mark.parametrize(
    "payload",
    [
        "unsafe==1.0.0\n",
        "unsafe @ https://packages.invalid/unsafe.whl\n",
        "unsafe @ git+https://example.invalid/repository.git@deadbeef\n",
        "--editable ../unsafe\n",
    ],
)
def test_lock_rejects_unhashed_url_vcs_and_editable_entries(payload: str) -> None:
    with pytest.raises(ValueError):
        _parse_lock(REQUIREMENTS_LOCK.read_text(encoding="ascii") + payload)


def test_dockerfile_uses_only_the_pinned_offline_non_root_supply_chain() -> None:
    _validate_dockerfile(DOCKERFILE.read_text(encoding="ascii"))


@pytest.mark.parametrize(
    ("old", "new"),
    [
        (
            f"{BASE_IMAGE} AS runtime",
            "docker.io/library/python:3.11-slim AS runtime",
        ),
        (
            "docker.io/library/python@sha256:",
            "public.ecr.aws/docker/library/python@sha256:",
        ),
        ("RUN --network=none", "RUN"),
        ("--require-hashes", "--no-deps"),
        ("USER 65532:65532", "USER 0:0"),
    ],
)
def test_dockerfile_rejects_tag_network_hash_and_root_drift(old: str, new: str) -> None:
    tampered = DOCKERFILE.read_text(encoding="ascii").replace(old, new, 1)
    with pytest.raises(ValueError):
        _validate_dockerfile(tampered)


@pytest.mark.parametrize(
    "trailing_instruction",
    [
        "USER 0:0",
        "RUN --network=default python -c \"print('networked')\"",
        "FROM docker.io/library/python:3.11-slim AS escape",
        "COPY . /workspace",
        "ADD https://packages.invalid/archive.tgz /tmp/archive.tgz",
        'ENTRYPOINT ["/bin/sh"]',
        'CMD ["/bin/sh"]',
        "WORKDIR /tmp",
    ],
)
def test_dockerfile_rejects_every_appended_or_multistage_instruction(
    trailing_instruction: str,
) -> None:
    tampered = DOCKERFILE.read_text(encoding="ascii") + f"\n{trailing_instruction}\n"
    with pytest.raises(ValueError):
        _validate_dockerfile(tampered)


def test_dockerfile_rejects_a_command_appended_inside_the_only_run() -> None:
    tampered = DOCKERFILE.read_text(encoding="ascii").replace(
        "    && find /opt/phase8/site-packages -type f -name '*.py[co]' -delete",
        "    && find /opt/phase8/site-packages -type f -name '*.py[co]' -delete \\\n+    && python -c \"print('unapproved')\"",
        1,
    )
    with pytest.raises(ValueError):
        _validate_dockerfile(tampered)


def test_dockerfile_rejects_an_unpinned_frontend_parser_directive() -> None:
    tampered = "# syntax=untrusted.invalid/dockerfile:latest\n" + DOCKERFILE.read_text(
        encoding="ascii"
    )
    with pytest.raises(ValueError):
        _validate_dockerfile(tampered)


def test_runtime_policy_is_loaded_through_the_production_validator() -> None:
    policy = runtime_policy.load_runtime_policy()
    assert policy == _policy()
    assert runtime_policy.BUILDX_DRIVER == "docker-container"
    assert runtime_policy.BUILDKIT_IMAGE == (
        "docker.io/moby/buildkit@"
        "sha256:2f5adac4ecd194d9f8c10b7b5d7bceb5186853db1b26e5abd3a657af0b7e26ec"
    )
    assert policy["build"]["build_parameters"] == runtime_policy.BUILD_PARAMETERS
    assert runtime_policy.BUILD_PARAMETERS["export_formats"] == ["oci", "docker"]
    assert policy["runtime"]["candidate_source"] == {
        "archive_format": runtime_policy.CANDIDATE_ARCHIVE_FORMAT,
        "method": "VERIFIED_STDIN_TO_TRUSTED_EXTRACTOR_ON_TMPFS",
        "source_kind": "validated-content-addressed-candidate-archive-fd",
        "target": "/workspace",
    }
    assert policy["runtime"]["resources"]["tmpfs"] == [
        "/tmp:rw,nosuid,nodev,noexec,size=268435456,mode=1777",
        "/workspace:rw,nosuid,nodev,noexec,size=536870912,mode=0755",
    ]
    assert (
        runtime_policy.canonical_sha256(policy) == runtime_policy.EXPECTED_POLICY_SHA256
    )


def test_aggregate_runtime_policy_imports_without_site_packages() -> None:
    for path in (
        ROOT / "scripts/phase8/candidate/runtime_policy.py",
        ROOT / "scripts/phase8/candidate/command_contract.py",
    ):
        source = path.read_text(encoding="utf-8")
        assert "jsonschema" not in source
        assert "Draft202012Validator" not in source
    completed = subprocess.run(
        [
            sys.executable,
            "-S",
            "-c",
            (
                "from scripts.phase8.candidate import runtime_policy; "
                "assert runtime_policy.FULL_REPOSITORY == 'FULL_REPOSITORY'"
            ),
        ],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        timeout=10,
    )
    assert completed.returncode == 0, completed.stderr


def test_runtime_policy_rejects_duplicate_keys_bom_nonfinite_and_bounds() -> None:
    raw = RUNTIME_POLICY.read_bytes()
    duplicate = raw.replace(
        b'"authority": "ENGINEERING_TEST_EXECUTION_ONLY",',
        (
            b'"authority": "ENGINEERING_TEST_EXECUTION_ONLY",'
            b'"authority": "ENGINEERING_TEST_EXECUTION_ONLY",'
        ),
        1,
    )
    bad_documents = [
        duplicate,
        b"\xef\xbb\xbf" + raw,
        b'{"value":NaN}',
        b"x" * (runtime_policy.MAX_POLICY_BYTES + 1),
        json.dumps({"nested": [[[[[[[[[[[[["too-deep"]]]]]]]]]]]]]}).encode("ascii"),
        json.dumps({"nodes": [0] * (runtime_policy.MAX_JSON_NODES + 1)}).encode(
            "ascii"
        ),
    ]
    for document in bad_documents:
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy.parse_bounded_json_bytes(document)


def test_runtime_policy_supports_only_the_two_exact_static_contract_commands() -> None:
    policy = _policy()
    for command_id in runtime_policy.SUPPORTED_COMMAND_IDS:
        command = _command(command_id)
        assert runtime_policy.assert_command_authorized(command, policy) == command


@pytest.mark.parametrize("command_id", runtime_policy.FORBIDDEN_COMMAND_IDS)
def test_runtime_policy_rejects_all_three_maven_groups(command_id: str) -> None:
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.assert_command_authorized(_command(command_id), _policy())


@pytest.mark.parametrize(
    "field", ["argv", "environment", "cwd", "report", "backend_kind"]
)
def test_command_authorization_rejects_every_contract_execution_field_drift(
    field: str,
) -> None:
    command = _command("wave_a_static")
    if field in {"argv", "environment", "report"}:
        command[field] = copy.deepcopy(command[field])
        if field == "argv":
            command[field].append("--collect-only")  # type: ignore[union-attr]
        else:
            command[field]["unexpected"] = "drift"  # type: ignore[index]
    elif field == "cwd":
        command[field] = "java-api-service"
    else:
        command[field] = "GITHUB_HOSTED_MAVEN"
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.assert_command_authorized(command, _policy())


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("runtime", "user"), "0:0"),
        (("runtime", "network"), "bridge"),
        (("runtime", "read_only_rootfs"), False),
        (("runtime", "cap_drop"), []),
        (("runtime", "host_namespace_sharing", "pid"), True),
        (("runtime", "mounts", "maximum_count"), 2),
        (("runtime", "candidate_source", "archive_format"), "PAX"),
        (("runtime", "candidate_source", "method"), "BIND"),
        (("runtime", "credentials", "host_environment_inherited"), True),
        (("runtime", "pull"), "missing"),
        (("execution_scope", "supported_backend_kind"), "HOST_EXECUTION"),
        (("build", "build_parameters", "builder_driver"), "docker"),
        (("build", "build_parameters", "builder_image"), "moby/buildkit:latest"),
        (("build", "build_parameters", "export_formats"), ["docker", "oci"]),
    ],
)
def test_production_policy_validator_rejects_authority_and_isolation_drift(
    path: tuple[object, ...], value: object
) -> None:
    tampered: object = copy.deepcopy(_policy())
    cursor = tampered
    for component in path[:-1]:
        cursor = cursor[component]  # type: ignore[index]
    cursor[path[-1]] = value  # type: ignore[index]
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.validate_runtime_policy(tampered)  # type: ignore[arg-type]


def test_production_policy_validator_rejects_socket_env_and_unknown_fields() -> None:
    for path in [(), ("runtime",), ("runtime", "resources"), ("runtime", "mounts")]:
        tampered = copy.deepcopy(_policy())
        cursor = tampered
        for component in path:
            cursor = cursor[component]  # type: ignore[index]
        cursor["unexpected"] = True  # type: ignore[index]
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy.validate_runtime_policy(tampered)
    for mutation in ("socket", "environment"):
        tampered = copy.deepcopy(_policy())
        runtime = tampered["runtime"]
        if mutation == "socket":
            runtime["mounts"]["allowed"].append(  # type: ignore[index,union-attr]
                {
                    "type": "bind",
                    "source_kind": "container-runtime-socket",
                    "target": "/var/run/docker.sock",
                    "read_only": False,
                    "bind_propagation": "rprivate",
                }
            )
        else:
            runtime["fixed_environment"]["AWS_SECRET_ACCESS_KEY"] = "leaked"  # type: ignore[index]
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy.validate_runtime_policy(tampered)


@pytest.mark.parametrize("command_id", runtime_policy.SUPPORTED_COMMAND_IDS)
def test_static_dispatch_authorization_returns_a_canonical_bound_hash(
    command_id: str, external_candidate_root: Path, oci_archive_path: Path
) -> None:
    bundle = _valid_dispatch(command_id, external_candidate_root, oci_archive_path)
    digest = _authorize(bundle)
    assert re.fullmatch(r"[0-9a-f]{64}", digest)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("backend_kind", "HOST_EXECUTION"),
        ("command_id", "wave_a_java"),
        ("cwd", "java-api-service"),
        ("image_id", f"sha256:{'f' * 64}"),
        ("network", "bridge"),
        ("user", "0:0"),
        ("timeout_seconds", 1),
    ],
)
def test_static_dispatch_rejects_backend_id_image_network_user_and_timeout_drift(
    field: str, value: object, external_candidate_root: Path, oci_archive_path: Path
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch = bundle["dispatch"]
    dispatch[field] = value
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


@pytest.mark.parametrize(
    "field",
    [
        "candidate_copy_argv",
        "create_argv",
        "exec_argv",
        "fixed_env",
        "inner_argv",
        "report",
        "resources",
        "start_argv",
        "tmpfs",
    ],
)
def test_static_dispatch_rejects_effective_execution_collection_drift(
    field: str, external_candidate_root: Path, oci_archive_path: Path
) -> None:
    bundle = _valid_dispatch(
        "wave_b_static_and_models", external_candidate_root, oci_archive_path
    )
    dispatch = bundle["dispatch"]
    dispatch[field] = copy.deepcopy(dispatch[field])
    if isinstance(dispatch[field], list):
        dispatch[field].append("unexpected")  # type: ignore[union-attr]
    else:
        dispatch[field]["unexpected"] = "drift"  # type: ignore[index]
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


def test_static_dispatch_pins_verified_root_extractor_on_root_owned_tmpfs(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch = bundle["dispatch"]
    candidate_binding = bundle["candidate_binding"]
    create_argv = dispatch["create_argv"]
    assert "--read-only" in create_argv
    assert (
        "--tmpfs=/workspace:rw,nosuid,nodev,noexec,size=536870912,mode=0755"
        in create_argv
    )
    assert dispatch["start_argv"] == [
        "docker",
        "start",
        runtime_policy.CONTAINER_ID_TOKEN,
    ]
    candidate_copy_argv = dispatch["candidate_copy_argv"]
    assert candidate_copy_argv[:7] == [
        "docker",
        "exec",
        "--interactive",
        "--user=0:0",
        runtime_policy.CONTAINER_ID_TOKEN,
        "/usr/local/bin/python",
        "-c",
    ]
    assert candidate_copy_argv[7] == runtime_policy.TRUSTED_CANDIDATE_EXTRACTOR_SCRIPT
    assert candidate_copy_argv[8:] == [
        str(candidate_binding["candidate_archive_bytes"]),
        candidate_binding["candidate_archive_sha256"],
        str(candidate_binding["candidate_archive_entry_count"]),
    ]
    assert 'tarfile.open(staged, mode="r:")' in candidate_copy_argv[7]
    assert "os.O_EXCL" in candidate_copy_argv[7]
    assert "os.O_NOFOLLOW" in candidate_copy_argv[7]
    assert "staged.unlink()" in candidate_copy_argv[7]
    assert "docker" not in candidate_copy_argv[7]


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("archive_format", "PAX"),
        ("method", "BIND"),
        ("source_kind", "git-worktree"),
        ("target", "/root"),
        ("archive_sha256", "f" * 64),
        ("archive_entry_count", 0),
    ],
)
def test_static_dispatch_rejects_untrusted_or_mutable_candidate_source(
    field: str, value: object, external_candidate_root: Path, oci_archive_path: Path
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch = bundle["dispatch"]
    dispatch["candidate_source"][field] = value  # type: ignore[index]
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


def test_static_dispatch_rejects_extra_fields_and_removed_runtime_flags(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch = bundle["dispatch"]
    dispatch["unexpected"] = True
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch = bundle["dispatch"]
    dispatch["create_argv"].remove("--network=none")  # type: ignore[union-attr]
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


def test_receipt_parser_rejects_duplicate_keys_and_oversized_documents() -> None:
    receipt = {"receipt_kind": "one", "receipt_sha256": "0" * 64}
    raw = json.dumps(receipt, separators=(",", ":")).encode("ascii")
    duplicate = raw.replace(
        b'"receipt_kind":"one"',
        b'"receipt_kind":"one","receipt_kind":"two"',
        1,
    )
    for payload in (duplicate, b"x" * (runtime_policy.MAX_RECEIPT_BYTES + 1)):
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy.parse_receipt_json_bytes(payload)


def test_receipts_are_canonical_but_do_not_claim_external_authentication(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    for key in ("materialization_receipt", "build_receipt", "build_observation"):
        receipt = bundle[key]
        assert "authenticated" not in receipt
        assert receipt["receipt_sha256"] == runtime_policy.canonical_receipt_sha256(
            receipt
        )


def test_materialization_rejects_directories_as_candidate_archives(
    external_candidate_root: Path,
) -> None:
    receipt, candidate_binding, scope_inventory, manifest = _materialization_receipt(
        external_candidate_root
    )
    for path in (Path(ROOT.anchor), Path.home(), ROOT):
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy.assert_materialization_authorized_live(
                receipt,
                manifest,
                candidate_binding,
                scope_inventory,
                _run_binding(),
                path,
            )


@pytest.mark.parametrize(
    "bad_path",
    [
        "relative/candidate-tree",
        "\\\\server\\share\\candidate-tree",
        "D:\\phase8:alternate-stream\\candidate-tree",
        "D:\\phase8,comma\\candidate-tree",
    ],
)
def test_materialization_receipt_rejects_relative_unc_ads_and_unsafe_paths(
    bad_path: str, external_candidate_root: Path
) -> None:
    receipt, candidate_binding, scope_inventory, manifest = _materialization_receipt(
        external_candidate_root
    )
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.assert_materialization_authorized_live(
            receipt,
            manifest,
            candidate_binding,
            scope_inventory,
            _run_binding(),
            bad_path,
        )


def test_materialization_receipt_rejects_explicit_dot_traversal(
    external_candidate_root: Path,
) -> None:
    receipt, candidate_binding, scope_inventory, manifest = _materialization_receipt(
        external_candidate_root
    )
    raw_alias = str(
        external_candidate_root.parent
        / external_candidate_root.name
        / ".."
        / external_candidate_root.name
    )
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.assert_materialization_authorized_live(
            receipt,
            manifest,
            candidate_binding,
            scope_inventory,
            _run_binding(),
            raw_alias,
        )


def test_materialization_receipt_rejects_symlinked_archive(
    external_candidate_root: Path,
) -> None:
    receipt, candidate_binding, scope_inventory, manifest = _materialization_receipt(
        external_candidate_root
    )
    archive_path = _candidate_archive_path(
        external_candidate_root, "wave_a_static", "1" * 64
    )
    link = archive_path.with_name(f"{archive_path.name}-link")
    try:
        os.symlink(archive_path, link)
    except OSError as exception:
        pytest.fail(f"test environment could not create required symlink: {exception}")
    try:
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy.assert_materialization_authorized_live(
                receipt,
                manifest,
                candidate_binding,
                scope_inventory,
                _run_binding(),
                link,
            )
    finally:
        link.unlink(missing_ok=True)


def test_materialization_receipt_rejects_unexpected_archive_authority_claims(
    external_candidate_root: Path,
) -> None:
    for field, value in (
        ("archive_hardlink_alias", True),
        ("archive_no_follow", False),
    ):
        receipt, candidate_binding, scope_inventory, manifest = (
            _materialization_receipt(external_candidate_root)
        )
        receipt[field] = value
        receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(receipt)
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy.assert_materialization_authorized_live(
                receipt,
                manifest,
                candidate_binding,
                scope_inventory,
                _run_binding(),
                _candidate_archive_path(
                    external_candidate_root, "wave_a_static", "1" * 64
                ),
            )


def test_public_full_repository_materialization_verifies_live_and_offline(
    external_candidate_root: Path,
) -> None:
    receipt, candidate_binding, scope_inventory, manifest = _materialization_receipt(
        external_candidate_root
    )
    archive_path = _candidate_archive_path(
        external_candidate_root, "wave_a_static", "1" * 64
    )
    live_receipt, live_binding, archive_handle = (
        runtime_policy.assert_materialization_authorized_live(
            receipt,
            manifest,
            candidate_binding,
            scope_inventory,
            _run_binding(),
            archive_path,
        )
    )
    live_evidence = archive_handle.evidence()
    archive_handle.close()
    shutil.rmtree(external_candidate_root)
    offline_receipt, offline_binding, offline_evidence = (
        runtime_policy.verify_materialization_receipt_offline(
            receipt,
            manifest,
            candidate_binding,
            scope_inventory,
            _run_binding(),
            archive_path,
        )
    )
    assert offline_receipt == live_receipt
    assert offline_binding == live_binding
    assert live_binding == candidate_binding
    assert offline_evidence == live_evidence


def test_dispatch_projects_the_normalized_trusted_candidate_binding(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch = bundle["dispatch"]
    candidate_binding = bundle["candidate_binding"]
    assert dispatch["accepted_a8"] == candidate_binding["accepted_entry_sha"]  # type: ignore[index]
    assert (
        dispatch["scope_inventory_sha256"]
        == candidate_binding[  # type: ignore[index]
            "derived_inventory_sha256"
        ]
    )


def test_public_java_service_materialization_verifies_exact_scope_offline() -> None:
    root = EXTERNAL_TEST_PARENT / f".phase8-runtime-java-{uuid.uuid4().hex}"
    java_root = root / "java-api-service"
    java_root.mkdir(parents=True)
    (java_root / "pom.xml").write_bytes(b"<project/>\n")
    try:
        receipt, candidate_binding, scope_inventory, manifest = (
            _materialization_receipt(
                root,
                closure_kind=runtime_policy.JAVA_SERVICE_ONLY,
                command_id="wave_a_java",
            )
        )
        archive_path = _candidate_archive_path(root, "wave_a_java", "1" * 64)
        _, _, archive_handle = runtime_policy.assert_materialization_authorized_live(
            receipt,
            manifest,
            candidate_binding,
            scope_inventory,
            _run_binding(),
            archive_path,
        )
        archive_handle.close()
        shutil.rmtree(root)
        runtime_policy.verify_materialization_receipt_offline(
            receipt,
            manifest,
            candidate_binding,
            scope_inventory,
            _run_binding(),
            archive_path,
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_offline_materialization_rejects_paired_fake_inventory_and_receipt(
    external_candidate_root: Path,
) -> None:
    _, trusted_binding, _, manifest = _materialization_receipt(external_candidate_root)
    fake_receipt, _, fake_scope, fake_manifest = _materialization_receipt(
        external_candidate_root,
        manifest=manifest[:-1],
    )
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.verify_materialization_receipt_offline(
            fake_receipt,
            fake_manifest,
            trusted_binding,
            fake_scope,
            _run_binding(),
            _candidate_archive_path(external_candidate_root, "wave_a_static", "1" * 64),
        )


def test_java_scope_rejects_non_java_inventory_even_when_every_hash_is_resealed(
    external_candidate_root: Path,
) -> None:
    receipt, _, _, manifest = _materialization_receipt(external_candidate_root)
    validated, _, count, total = runtime_policy.validate_materialization_manifest(
        manifest
    )
    scope_inventory = {
        "entries": validated,
        "file_count": count,
        "manifest_sha256": runtime_policy.canonical_sha256(
            {
                "entries": validated,
                "file_count": count,
                "inventory_kind": runtime_policy.JAVA_SERVICE_ONLY,
                "total_bytes": total,
            }
        ),
        "total_bytes": total,
    }
    receipt["closure_kind"] = runtime_policy.JAVA_SERVICE_ONLY
    receipt["manifest_sha256"] = scope_inventory["manifest_sha256"]
    receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(receipt)
    candidate_binding = {
        "accepted_entry_sha": receipt["accepted_a8"],
        "candidate_archive_bytes": receipt["candidate_archive_bytes"],
        "candidate_archive_entry_count": receipt["candidate_archive_entry_count"],
        "candidate_archive_format": receipt["candidate_archive_format"],
        "candidate_archive_sha256": receipt["candidate_archive_sha256"],
        "candidate_sha": receipt["candidate_sha"],
        "candidate_tree_sha": receipt["candidate_tree_sha"],
        "closure_kind": receipt["closure_kind"],
        "derived_inventory_sha256": receipt["scope_inventory_sha256"],
        "manifest_file_count": count,
        "manifest_sha256": receipt["manifest_sha256"],
        "manifest_total_bytes": total,
    }
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.verify_materialization_receipt_offline(
            receipt,
            manifest,
            candidate_binding,
            scope_inventory,
            _run_binding(),
            _candidate_archive_path(external_candidate_root, "wave_a_static", "1" * 64),
        )


def test_archive_dispatch_is_independent_of_post_receipt_producer_overwrite(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    target = external_candidate_root / "case.txt"
    original = target.read_bytes()
    target.write_bytes(b"tampered-case-v01\n")
    assert len(target.read_bytes()) == len(original)
    assert re.fullmatch(r"[0-9a-f]{64}", _authorize(bundle))


@pytest.mark.parametrize("mutation", ["extra-file", "missing-file", "extra-directory"])
def test_archive_dispatch_is_independent_of_post_receipt_producer_tree_changes(
    mutation: str,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    if mutation == "extra-file":
        (external_candidate_root / "extra.txt").write_bytes(b"extra\n")
    elif mutation == "missing-file":
        (external_candidate_root / "case.txt").unlink()
    else:
        (external_candidate_root / "unexpected-empty-directory").mkdir()
    assert re.fullmatch(r"[0-9a-f]{64}", _authorize(bundle))


def test_archive_dispatch_is_independent_of_post_receipt_producer_aliases(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    for alias_kind in ("symlink", "hardlink"):
        bundle = _valid_dispatch(
            "wave_a_static", external_candidate_root, oci_archive_path
        )
        alias = external_candidate_root / f"{alias_kind}-alias.txt"
        target = external_candidate_root / "case.txt"
        try:
            if alias_kind == "symlink":
                os.symlink(target, alias)
            else:
                os.link(target, alias)
        except OSError as exception:
            pytest.fail(
                f"test environment could not create required {alias_kind}: {exception}"
            )
        try:
            assert re.fullmatch(r"[0-9a-f]{64}", _authorize(bundle))
        finally:
            alias.unlink(missing_ok=True)


def test_materialization_manifest_rejects_arbitrary_hash_and_summary_resealing(
    external_candidate_root: Path,
) -> None:
    receipt, candidate_binding, scope_inventory, manifest = _materialization_receipt(
        external_candidate_root
    )
    tampered_manifest = copy.deepcopy(manifest)
    tampered_manifest[0]["sha256"] = "f" * 64
    validated, _, count, total = runtime_policy.validate_materialization_manifest(
        tampered_manifest
    )
    tampered_scope = {
        "entries": validated,
        "file_count": count,
        "manifest_sha256": runtime_policy.canonical_sha256(
            {
                "entries": validated,
                "file_count": count,
                "inventory_kind": runtime_policy.FULL_REPOSITORY,
                "total_bytes": total,
            }
        ),
        "total_bytes": total,
    }
    receipt["manifest_sha256"] = tampered_scope["manifest_sha256"]
    receipt["manifest_file_count"] = count
    receipt["manifest_total_bytes"] = total
    receipt["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(receipt)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.assert_materialization_authorized_live(
            receipt,
            tampered_manifest,
            candidate_binding,
            tampered_scope,
            _run_binding(),
            _candidate_archive_path(external_candidate_root, "wave_a_static", "1" * 64),
        )


def test_alternate_valid_image_receipt_cannot_replace_independent_build_binding(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    alternate = copy.deepcopy(bundle["build_receipt"])
    alternate["image_id"] = f"sha256:{'f' * 64}"
    alternate["config_digest"] = alternate["image_id"]
    alternate["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(alternate)
    bundle["build_receipt"] = alternate
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


@pytest.mark.parametrize(
    "field",
    [
        "image_inspect_projection_sha256",
        "docker_archive_sha256",
        "oci_archive_sha256",
        "rootfs_digest",
        "wheelhouse_manifest_sha256",
    ],
)
def test_arbitrary_build_hashes_cannot_self_prove_runtime_content(
    field: str,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    build = copy.deepcopy(bundle["build_receipt"])
    build[field] = "f" * 64
    build["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(build)
    bundle["build_receipt"] = build
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


def test_copied_image_identity_cannot_override_the_content_addressed_oci_archive(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    build = copy.deepcopy(bundle["build_receipt"])
    observation = copy.deepcopy(bundle["build_observation"])
    forged_image_id = f"sha256:{'f' * 64}"
    projection = observation["producer_image_inspect_projection"]
    projection["image_id"] = forged_image_id  # type: ignore[index]
    projection["config_digest"] = forged_image_id  # type: ignore[index]
    projection_sha = runtime_policy.canonical_sha256(projection)
    build["image_id"] = forged_image_id
    build["config_digest"] = forged_image_id
    build["image_inspect_projection_sha256"] = projection_sha
    build["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(build)
    observation["image_inspect_projection_sha256"] = projection_sha
    observation["source_build_receipt_sha256"] = build["receipt_sha256"]
    observation["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(observation)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.validate_runtime_build_receipt(
            build,
            observation,
            _observation_binding(observation),
            expected_run_binding=bundle["expected_run_binding"],  # type: ignore[arg-type]
            expected_builder_job_identity=bundle["expected_builder_job_identity"],  # type: ignore[arg-type]
            producer_oci_archive_path=oci_archive_path,
            producer_docker_archive_path=_producer_docker_archive_path(
                oci_archive_path
            ),
            policy=bundle["policy"],  # type: ignore[arg-type]
            validated_command_contract=bundle["validated_contract"],  # type: ignore[arg-type]
        )


def test_resealed_wheel_manifest_must_still_equal_the_hashed_lock_closure(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    build = copy.deepcopy(bundle["build_receipt"])
    observation = copy.deepcopy(bundle["build_observation"])
    wheelhouse = observation["wheelhouse_manifest"]
    wheelhouse[0]["sha256"] = "f" * 64  # type: ignore[index]
    _reseal_wheel_observation(build, observation)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.validate_runtime_build_receipt(
            build,
            observation,
            _observation_binding(observation),
            expected_run_binding=bundle["expected_run_binding"],  # type: ignore[arg-type]
            expected_builder_job_identity=bundle["expected_builder_job_identity"],  # type: ignore[arg-type]
            producer_oci_archive_path=oci_archive_path,
            producer_docker_archive_path=_producer_docker_archive_path(
                oci_archive_path
            ),
            policy=bundle["policy"],  # type: ignore[arg-type]
            validated_command_contract=bundle["validated_contract"],  # type: ignore[arg-type]
        )


@pytest.mark.parametrize("mutation", ["duplicate-hash", "renamed-distribution"])
def test_wheel_manifest_is_an_exact_distribution_version_hash_bijection(
    mutation: str,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    build = copy.deepcopy(bundle["build_receipt"])
    observation = copy.deepcopy(bundle["build_observation"])
    wheelhouse = observation["wheelhouse_manifest"]
    if mutation == "duplicate-hash":
        duplicate = copy.deepcopy(wheelhouse[0])  # type: ignore[index]
        duplicate["filename"] = f"z_{duplicate['filename']}"
        wheelhouse.append(duplicate)  # type: ignore[union-attr]
        wheelhouse.sort(key=lambda item: item["filename"])  # type: ignore[union-attr]
    else:
        wheelhouse[0]["filename"] = "untrusted_pkg-1.0-py3-none-any.whl"  # type: ignore[index]
        wheelhouse.sort(key=lambda item: item["filename"])  # type: ignore[union-attr]
    _reseal_wheel_observation(build, observation)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.validate_runtime_build_receipt(
            build,
            observation,
            _observation_binding(observation),
            expected_run_binding=bundle["expected_run_binding"],  # type: ignore[arg-type]
            expected_builder_job_identity=bundle["expected_builder_job_identity"],  # type: ignore[arg-type]
            producer_oci_archive_path=oci_archive_path,
            producer_docker_archive_path=_producer_docker_archive_path(
                oci_archive_path
            ),
            policy=bundle["policy"],  # type: ignore[arg-type]
            validated_command_contract=bundle["validated_contract"],  # type: ignore[arg-type]
        )


@pytest.mark.parametrize("collision", ["job_identity", "nonce"])
def test_builder_and_observer_must_have_independent_job_identity_and_nonce(
    collision: str,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    build = copy.deepcopy(bundle["build_receipt"])
    observation = copy.deepcopy(bundle["build_observation"])
    if collision == "job_identity":
        observation["observer_job_identity"] = copy.deepcopy(
            build["builder_job_identity"]
        )
        observation["observer_job_identity_sha256"] = build[
            "builder_job_identity_sha256"
        ]
    else:
        observation["observer_nonce"] = build["build_nonce"]
    observation["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(observation)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy.validate_runtime_build_receipt(
            build,
            observation,
            _observation_binding(observation),
            expected_run_binding=bundle["expected_run_binding"],  # type: ignore[arg-type]
            expected_builder_job_identity=bundle["expected_builder_job_identity"],  # type: ignore[arg-type]
            producer_oci_archive_path=oci_archive_path,
            producer_docker_archive_path=_producer_docker_archive_path(
                oci_archive_path
            ),
            policy=bundle["policy"],  # type: ignore[arg-type]
            validated_command_contract=bundle["validated_contract"],  # type: ignore[arg-type]
        )


def test_oci_archive_mutation_after_receipts_is_rejected_before_dispatch(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    with oci_archive_path.open("ab") as archive:
        archive.write(b"post-receipt-mutation")
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


@pytest.mark.parametrize("role", ["producer", "observer"])
def test_docker_archive_mutation_after_receipts_is_rejected(
    role: str,
    oci_archive_path: Path,
) -> None:
    archive_path = (
        _producer_docker_archive_path(oci_archive_path)
        if role == "producer"
        else _observer_docker_archive_path(oci_archive_path)
    )
    payload = archive_path.read_bytes()
    with archive_path.open("ab") as archive:
        archive.write(b"post-receipt-mutation")
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy._assert_docker_archive_with_evidence(
            archive_path,
            expected_bytes=len(payload),
            expected_sha256=hashlib.sha256(payload).hexdigest(),
        )


@pytest.mark.parametrize("alias_kind", ["symlink", "hardlink"])
def test_docker_archive_aliases_are_rejected(
    alias_kind: str,
    oci_archive_path: Path,
) -> None:
    producer_path = _producer_docker_archive_path(oci_archive_path)
    payload = producer_path.read_bytes()
    alias_path = producer_path.with_name(f"{producer_path.stem}-{alias_kind}.tar")
    try:
        if alias_kind == "symlink":
            os.symlink(producer_path, alias_path)
        else:
            os.link(producer_path, alias_path)
    except OSError as exception:
        pytest.fail(
            f"test environment could not create required {alias_kind}: {exception}"
        )
    try:
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy._assert_docker_archive_with_evidence(
                alias_path,
                expected_bytes=len(payload),
                expected_sha256=hashlib.sha256(payload).hexdigest(),
            )
    finally:
        alias_path.unlink(missing_ok=True)


def test_docker_archive_materializations_must_be_physically_independent(
    oci_archive_path: Path,
) -> None:
    archive_path = _producer_docker_archive_path(oci_archive_path)
    payload = archive_path.read_bytes()
    evidence = runtime_policy._assert_docker_archive_with_evidence(
        archive_path,
        expected_bytes=len(payload),
        expected_sha256=hashlib.sha256(payload).hexdigest(),
    )
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy._require_distinct_archive_evidence(
            [evidence, copy.deepcopy(evidence)], context="test Docker topology"
        )


def test_docker_archive_rejects_nonregular_and_oversized_paths(
    monkeypatch: pytest.MonkeyPatch,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy._assert_docker_archive_with_evidence(
            external_candidate_root,
            expected_bytes=1,
            expected_sha256="0" * 64,
        )

    docker_path = _producer_docker_archive_path(oci_archive_path)
    payload = docker_path.read_bytes()
    monkeypatch.setattr(
        runtime_policy, "MAX_DOCKER_ARCHIVE_BYTES", docker_path.stat().st_size - 1
    )
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        runtime_policy._assert_docker_archive_with_evidence(
            docker_path,
            expected_bytes=len(payload),
            expected_sha256=hashlib.sha256(payload).hexdigest(),
        )


@pytest.mark.parametrize(
    "mutation",
    [
        "index-media-type",
        "index-platform",
        "index-created-annotation",
        "manifest-media-type",
        "gzip-unknown-annotation",
        "unknown-compression",
        "uncompressed-diff-id",
    ],
)
def test_oci_buildkit_shape_rejects_metadata_and_layer_drift(mutation: str) -> None:
    files, _ = _test_oci_payloads(_policy())
    index = json.loads(files["index.json"])
    manifest_name = (
        f"blobs/sha256/{index['manifests'][0]['digest'].removeprefix('sha256:')}"
    )
    manifest = json.loads(files[manifest_name])
    config_name = f"blobs/sha256/{manifest['config']['digest'].removeprefix('sha256:')}"
    config = json.loads(files[config_name])
    if mutation == "index-media-type":
        index["mediaType"] = "application/vnd.docker.distribution.manifest.list.v2+json"
    elif mutation == "index-platform":
        index["manifests"][0]["platform"]["architecture"] = "arm64"
    elif mutation == "index-created-annotation":
        index["manifests"][0]["annotations"]["org.opencontainers.image.created"] = (
            "1970-01-01T00:00:01Z"
        )
    elif mutation == "manifest-media-type":
        manifest["mediaType"] = "application/vnd.docker.distribution.manifest.v2+json"
    elif mutation == "gzip-unknown-annotation":
        manifest["layers"][0]["annotations"] = {"unexpected": "value"}
    elif mutation == "unknown-compression":
        manifest["layers"][0]["mediaType"] = (
            "application/vnd.oci.image.layer.v1.tar+zstd"
        )
    else:
        config["rootfs"]["diff_ids"][-1] = f"sha256:{'f' * 64}"
    _reseal_test_oci_metadata(files, index, manifest, config)
    archive_path = (
        EXTERNAL_TEST_PARENT
        / f".phase8-runtime-buildkit-mutation-{uuid.uuid4().hex}.tar"
    )
    try:
        _write_test_oci_archive(archive_path, files)
        payload = archive_path.read_bytes()
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy._assert_oci_archive(
                archive_path,
                expected_bytes=len(payload),
                expected_sha256=hashlib.sha256(payload).hexdigest(),
            )
    finally:
        archive_path.unlink(missing_ok=True)


def test_uncompressed_oci_diff_ids_must_match_actual_layer_blobs() -> None:
    files, _ = _test_oci_payloads(_policy())
    index = json.loads(files["index.json"])
    old_manifest_digest = index["manifests"][0]["digest"].removeprefix("sha256:")
    old_manifest_name = f"blobs/sha256/{old_manifest_digest}"
    manifest = json.loads(files.pop(old_manifest_name))
    old_config_digest = manifest["config"]["digest"].removeprefix("sha256:")
    old_config_name = f"blobs/sha256/{old_config_digest}"
    config = json.loads(files.pop(old_config_name))
    config["rootfs"]["diff_ids"] = [f"sha256:{'f' * 64}" for _ in manifest["layers"]]
    config_payload = json.dumps(
        config, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    config_digest = hashlib.sha256(config_payload).hexdigest()
    files[f"blobs/sha256/{config_digest}"] = config_payload
    manifest["config"]["digest"] = f"sha256:{config_digest}"
    manifest["config"]["size"] = len(config_payload)
    manifest_payload = json.dumps(
        manifest, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    manifest_digest = hashlib.sha256(manifest_payload).hexdigest()
    files[f"blobs/sha256/{manifest_digest}"] = manifest_payload
    index["manifests"][0]["digest"] = f"sha256:{manifest_digest}"
    index["manifests"][0]["size"] = len(manifest_payload)
    files["index.json"] = json.dumps(
        index, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    archive_path = (
        EXTERNAL_TEST_PARENT / f".phase8-runtime-forged-{uuid.uuid4().hex}.tar"
    )
    try:
        with tarfile.open(archive_path, mode="w") as archive:
            for name, payload in sorted(files.items()):
                info = tarfile.TarInfo(name=name)
                info.mode = 0o644
                info.mtime = 0
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
        payload = archive_path.read_bytes()
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            runtime_policy._assert_oci_archive(
                archive_path,
                expected_bytes=len(payload),
                expected_sha256=hashlib.sha256(payload).hexdigest(),
            )
    finally:
        archive_path.unlink(missing_ok=True)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        (
            "base_image_acquisition_network_profile",
            "UNPINNED_PUBLIC_EGRESS",
        ),
        ("docker_build_run_network", "default"),
        (
            "wheelhouse_acquisition_network_profile",
            "UNHASHED_PUBLIC_EGRESS",
        ),
    ],
)
def test_build_receipt_rejects_network_semantic_conflation(
    field: str,
    value: str,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    build = copy.deepcopy(bundle["build_receipt"])
    build[field] = value
    build["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(build)
    bundle["build_receipt"] = build
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


def test_build_receipt_rejects_mutable_repository_name_without_immutable_id(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    build = copy.deepcopy(bundle["build_receipt"])
    builder_identity = build["builder_job_identity"]
    builder_identity["repository_id"] = "999999999"  # type: ignore[index]
    build["builder_job_identity_sha256"] = runtime_policy.canonical_sha256(
        builder_identity
    )
    build["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(build)
    bundle["build_receipt"] = build
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


def test_alternate_valid_path_receipt_cannot_replace_independent_candidate_binding(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    other = external_candidate_root.parent / f"{external_candidate_root.name}-other"
    other.mkdir()
    try:
        bundle = _valid_dispatch(
            "wave_a_static", external_candidate_root, oci_archive_path
        )
        alternate, _, alternate_scope, alternate_manifest = _materialization_receipt(
            other
        )
        bundle["materialization_receipt"] = alternate
        bundle["materialization_manifest"] = alternate_manifest
        bundle["expected_scope_inventory"] = alternate_scope
        with pytest.raises(runtime_policy.RuntimePolicyValidationError):
            _authorize(bundle)
    finally:
        other.rmdir()


def test_mixed_candidate_and_build_receipts_are_rejected_even_when_each_is_valid(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    other_materialization, _, _, _ = _materialization_receipt(
        external_candidate_root, candidate_sha="f" * 40, tree_sha="1" * 40
    )
    other_build, other_observation, other_observation_binding = _runtime_build_receipt(
        bundle["policy"],  # type: ignore[arg-type]
        oci_archive_path,
        bundle["validated_contract"],  # type: ignore[arg-type]
        code_sha=other_materialization["candidate_sha"],
        code_tree_sha=other_materialization["candidate_tree_sha"],
    )
    bundle["build_receipt"] = other_build
    bundle["build_observation"] = other_observation
    bundle["observation_binding"] = other_observation_binding
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _authorize(bundle)


def test_offline_verifier_accepts_transport_after_producer_root_is_destroyed(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch_sha256 = _authorize(bundle)
    transport, transport_binding = _artifact_transport_receipt(bundle, dispatch_sha256)
    shutil.rmtree(external_candidate_root)
    assert _verify_offline(bundle, transport, transport_binding) == dispatch_sha256


def test_topology_c_reuses_one_observed_oci_across_two_fresh_static_executors(
    monkeypatch: pytest.MonkeyPatch,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    second_root = EXTERNAL_TEST_PARENT / f".phase8-runtime-second-{uuid.uuid4().hex}"
    (second_root / "nested").mkdir(parents=True)
    (second_root / "case.txt").write_bytes(b"candidate-case-v1\n")
    (second_root / "nested" / "evidence.json").write_bytes(b'{"status":"synthetic"}\n')
    try:
        wave_a = _valid_dispatch(
            "wave_a_static",
            external_candidate_root,
            oci_archive_path,
            created_nonce="1" * 64,
            verified_nonce="2" * 64,
        )
        wave_b = _valid_dispatch(
            "wave_b_static_and_models",
            second_root,
            oci_archive_path,
            created_nonce="a" * 64,
            verified_nonce="b" * 64,
        )
        wave_a_dispatch = _authorize(wave_a)
        wave_b_dispatch = _authorize(wave_b)
        wave_a_transport, wave_a_transport_binding = _artifact_transport_receipt(
            wave_a, wave_a_dispatch
        )
        wave_b_transport, wave_b_transport_binding = _artifact_transport_receipt(
            wave_b, wave_b_dispatch
        )
        assert wave_a["build_receipt"] == wave_b["build_receipt"]
        assert wave_a["build_observation"] == wave_b["build_observation"]
        assert (
            wave_a["materialization_receipt"]["receipt_sha256"]  # type: ignore[index]
            != wave_b["materialization_receipt"]["receipt_sha256"]  # type: ignore[index]
        )
        assert (
            wave_a_transport["producer_job_identity_sha256"]
            != wave_b_transport["producer_job_identity_sha256"]
        )
        original_assert_oci_archive = runtime_policy._assert_oci_archive
        archive_verifications = 0

        def counted_assert_oci_archive(
            *args: object, **kwargs: object
        ) -> dict[str, object]:
            nonlocal archive_verifications
            archive_verifications += 1
            return original_assert_oci_archive(*args, **kwargs)  # type: ignore[arg-type]

        monkeypatch.setattr(
            runtime_policy, "_assert_oci_archive", counted_assert_oci_archive
        )
        shared_runtime = _validated_shared_runtime(wave_a)
        assert archive_verifications == 1
        runtime_projection = runtime_policy._shared_runtime_projection(
            shared_runtime,
            expected_run_binding=wave_a["expected_run_binding"],  # type: ignore[arg-type]
            policy=wave_a["policy"],  # type: ignore[arg-type]
            validated_command_contract=wave_a["validated_contract"],  # type: ignore[arg-type]
        )
        assert {
            "producer_oci_archive",
            "observer_oci_archive",
            "producer_docker_archive",
            "observer_docker_archive",
        } <= runtime_projection.keys()
        assert (
            runtime_projection["producer_docker_archive"]["archive_sha256"]
            == (
                wave_a["build_receipt"]["docker_archive_sha256"]  # type: ignore[index]
            )
        )
        assert (
            runtime_projection["observer_docker_archive"]["archive_sha256"]
            == (
                wave_a["build_observation"]["observer_docker_archive_sha256"]  # type: ignore[index]
            )
        )
        shutil.rmtree(external_candidate_root)
        shutil.rmtree(second_root)
        assert (
            _verify_offline(
                wave_a,
                wave_a_transport,
                wave_a_transport_binding,
                validated_shared_runtime=shared_runtime,
            )
            == wave_a_dispatch
        )
        assert (
            _verify_offline(
                wave_b,
                wave_b_transport,
                wave_b_transport_binding,
                validated_shared_runtime=shared_runtime,
            )
            == wave_b_dispatch
        )
        assert archive_verifications == 1
    finally:
        shutil.rmtree(second_root, ignore_errors=True)


def test_offline_verifier_rejects_an_unverified_shared_runtime_mapping(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch_sha256 = _authorize(bundle)
    transport, transport_binding = _artifact_transport_receipt(bundle, dispatch_sha256)
    shutil.rmtree(external_candidate_root)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _verify_offline(
            bundle,
            transport,
            transport_binding,
            validated_shared_runtime={},
        )


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("artifact_payload_sha256", "9" * 64),
        ("producer_job_identity_sha256", "a" * 64),
    ],
)
def test_offline_verifier_rejects_transport_not_matching_independent_job_binding(
    field: str,
    value: object,
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch_sha256 = _authorize(bundle)
    transport, transport_binding = _artifact_transport_receipt(bundle, dispatch_sha256)
    transport[field] = value
    transport["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(transport)
    shutil.rmtree(external_candidate_root)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _verify_offline(bundle, transport, transport_binding)


def test_offline_verifier_rejects_nested_producer_job_identity_drift(
    external_candidate_root: Path,
    oci_archive_path: Path,
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch_sha256 = _authorize(bundle)
    transport, transport_binding = _artifact_transport_receipt(bundle, dispatch_sha256)
    producer_identity = copy.deepcopy(transport["producer_job_identity"])
    producer_identity["run_id"] = "987654321"  # type: ignore[index]
    transport["producer_job_identity"] = producer_identity
    transport["producer_job_identity_sha256"] = runtime_policy.canonical_sha256(
        producer_identity
    )
    transport["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(transport)
    shutil.rmtree(external_candidate_root)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _verify_offline(bundle, transport, transport_binding)


@pytest.mark.parametrize(
    "field",
    [
        "command_id",
        "dispatch_sha256",
        "build_observation_receipt_sha256",
        "materialization_receipt_sha256",
        "manifest_sha256",
        "oci_archive_sha256",
        "runtime_build_receipt_sha256",
    ],
)
def test_offline_verifier_rejects_transport_from_another_receipt_bundle(
    field: str, external_candidate_root: Path, oci_archive_path: Path
) -> None:
    bundle = _valid_dispatch("wave_a_static", external_candidate_root, oci_archive_path)
    dispatch_sha256 = _authorize(bundle)
    transport, transport_binding = _artifact_transport_receipt(bundle, dispatch_sha256)
    if field == "command_id":
        replacement: object = "wave_b_static_and_models"
    else:
        replacement = "f" * 64
    transport[field] = replacement
    transport["receipt_sha256"] = runtime_policy.canonical_receipt_sha256(transport)
    transport_binding[field] = replacement
    shutil.rmtree(external_candidate_root)
    with pytest.raises(runtime_policy.RuntimePolicyValidationError):
        _verify_offline(bundle, transport, transport_binding)
