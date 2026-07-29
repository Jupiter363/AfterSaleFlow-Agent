from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


BASE_IMAGE_KEYS = frozenset(common.IMAGE_KEYS) - common.APPLICATION_IMAGE_KEYS
REPOSITORY_PREFIX = re.compile(r"^[a-z0-9][a-z0-9._:/-]{2,220}$")
INVOCATION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
OCI_REVISION_LABEL = "org.opencontainers.image.revision"
OCI_VERSION_LABEL = "org.opencontainers.image.version"
TARGET_BUILD_LABEL = "target-e2e.after-sale-flow.dev/build-id"


@dataclass(frozen=True, slots=True)
class ApplicationImage:
    repository: str
    context: Path
    dockerfile: Path


APPLICATION_IMAGES = {
    "java": ApplicationImage(
        repository="java",
        context=common.ROOT / "java-api-service",
        dockerfile=common.ROOT / "java-api-service" / "Dockerfile.target-e2e",
    ),
    "python": ApplicationImage(
        repository="python",
        context=common.ROOT / "python-agent-service",
        dockerfile=common.ROOT / "python-agent-service" / "Dockerfile",
    ),
    "ocr": ApplicationImage(
        repository="ocr",
        context=common.ROOT / "ocr-parser-service",
        dockerfile=common.ROOT / "ocr-parser-service" / "Dockerfile",
    ),
    "frontend": ApplicationImage(
        repository="frontend",
        context=common.ROOT / "frontend",
        dockerfile=common.ROOT / "frontend" / "Dockerfile",
    ),
}


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise common.TargetE2EError(f"base image input repeats JSON member: {key}")
        value[key] = item
    return value


def load_base_images(path: Path) -> dict[str, str]:
    common.assert_regular_single_link(path, "base image input")
    try:
        document = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_strict_object)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise common.TargetE2EError("base image input is not strict UTF-8 JSON") from error
    if not isinstance(document, dict) or set(document) != BASE_IMAGE_KEYS:
        raise common.TargetE2EError(
            "base image input must contain the exact non-application inventory"
        )
    if any(
        not isinstance(reference, str) or common.IMAGE_REFERENCE.fullmatch(reference) is None
        for reference in document.values()
    ):
        raise common.TargetE2EError("every base image must be an immutable manifest reference")
    return {str(key): str(value) for key, value in document.items()}


def _run(arguments: list[str], *, timeout: int = 3600) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        arguments,
        check=False,
        capture_output=True,
        text=True,
        shell=False,
        timeout=timeout,
    )
    if completed.returncode:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise common.TargetE2EError(f"image-lock command failed: {detail}")
    return completed


def _repository_state(candidate: str) -> str:
    if common.SHA1.fullmatch(candidate) is None:
        raise common.TargetE2EError("candidate must be one exact lowercase 40-character Git SHA")
    head = _run(["git", "rev-parse", "HEAD"], timeout=30).stdout.strip()
    resolved = _run(["git", "rev-parse", f"{candidate}^{{commit}}"], timeout=30).stdout.strip()
    dirty = _run(["git", "status", "--porcelain=v1", "--untracked-files=all"], timeout=30).stdout
    if head != candidate or resolved != candidate:
        raise common.TargetE2EError("candidate must equal the exact checked-out HEAD commit")
    if dirty:
        raise common.TargetE2EError("candidate image build requires a clean working tree")
    return head


def _source_tree_digest(candidate: str) -> str:
    completed = subprocess.run(
        ["git", "archive", "--format=tar", candidate],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        shell=False,
        timeout=120,
    )
    if completed.returncode:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise common.TargetE2EError(f"cannot archive exact candidate: {detail}")
    return "sha256:" + hashlib.sha256(completed.stdout).hexdigest()


def _inspect_image(docker: str, reference: str) -> dict[str, Any]:
    raw = _run([docker, "image", "inspect", reference], timeout=120).stdout
    try:
        documents = json.loads(raw)
    except json.JSONDecodeError as error:
        raise common.TargetE2EError("Docker image inspect did not return JSON") from error
    if not isinstance(documents, list) or len(documents) != 1 or not isinstance(documents[0], dict):
        raise common.TargetE2EError("Docker image inspect did not return exactly one image")
    return documents[0]


def _build_manifest_digest(path: Path) -> str:
    common.assert_regular_single_link(path, "Buildx metadata")
    try:
        document = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=_strict_object
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise common.TargetE2EError("Buildx metadata is not strict UTF-8 JSON") from error
    digest = document.get("containerimage.digest") if isinstance(document, dict) else None
    if not isinstance(digest, str) or common.DIGEST.fullmatch(digest) is None:
        raise common.TargetE2EError("Buildx metadata has no exact manifest digest")
    return digest


def _record_from_inspection(
    inspection: dict[str, Any],
    *,
    repository: str,
    source_revision: str,
    build_id: str,
    expected_reference: str | None = None,
) -> dict[str, Any]:
    repo_digests = inspection.get("RepoDigests")
    if not isinstance(repo_digests, list):
        raise common.TargetE2EError(f"image {repository} has no registry manifest digest")
    matching = sorted(
        value
        for value in repo_digests
        if isinstance(value, str) and value.startswith(repository + "@")
    )
    if expected_reference is not None:
        if expected_reference not in matching:
            raise common.TargetE2EError(
                "pulled image does not retain the required manifest reference"
            )
        reference = expected_reference
    elif len(matching) == 1:
        reference = matching[0]
    else:
        raise common.TargetE2EError("built image manifest identity is missing or ambiguous")
    config_digest = inspection.get("Id")
    layers = (inspection.get("RootFS") or {}).get("Layers")
    if (
        not isinstance(config_digest, str)
        or common.DIGEST.fullmatch(config_digest) is None
        or not isinstance(layers, list)
        or not layers
        or any(
            not isinstance(value, str) or common.DIGEST.fullmatch(value) is None
            for value in layers
        )
    ):
        raise common.TargetE2EError("Docker image config or ordered layer identity is invalid")
    return {
        "reference": reference,
        "manifest_digest": reference.rsplit("@", 1)[1],
        "config_digest": config_digest,
        "layer_digests": list(layers),
        "source_revision": source_revision,
        "build_id": build_id,
    }


def _build_application_image(
    *,
    docker: str,
    candidate: str,
    build_id: str,
    repository_prefix: str,
    key: str,
    specification: ApplicationImage,
    metadata_directory: Path,
) -> dict[str, Any]:
    if not specification.dockerfile.is_file() or not specification.context.is_dir():
        raise common.TargetE2EError(f"application image build inputs are missing for {key}")
    repository = f"{repository_prefix.rstrip('/')}/{specification.repository}"
    tag = f"{repository}:{build_id}"
    metadata_path = metadata_directory / f"buildx-{key}-metadata.json"
    _run(
        [
            docker,
            "buildx",
            "build",
            "--file",
            str(specification.dockerfile),
            "--label",
            f"{OCI_REVISION_LABEL}={candidate}",
            "--label",
            f"{OCI_VERSION_LABEL}={build_id}",
            "--label",
            f"{TARGET_BUILD_LABEL}={build_id}",
            "--provenance=false",
            "--sbom=false",
            "--tag",
            tag,
            "--metadata-file",
            str(metadata_path),
            "--push",
            str(specification.context),
        ]
    )
    reference = f"{repository}@{_build_manifest_digest(metadata_path)}"
    _run([docker, "pull", reference], timeout=900)
    inspection = _inspect_image(docker, reference)
    labels = (inspection.get("Config") or {}).get("Labels")
    if not isinstance(labels, dict) or (
        labels.get(OCI_REVISION_LABEL),
        labels.get(OCI_VERSION_LABEL),
        labels.get(TARGET_BUILD_LABEL),
    ) != (candidate, build_id, build_id):
        raise common.TargetE2EError(f"built application image labels drifted for {key}")
    record = _record_from_inspection(
        inspection,
        repository=repository,
        source_revision=candidate,
        build_id=build_id,
        expected_reference=reference,
    )
    exact = _record_from_inspection(
        _inspect_image(docker, reference),
        repository=repository,
        source_revision=candidate,
        build_id=build_id,
        expected_reference=reference,
    )
    if exact != record:
        raise common.TargetE2EError(f"built image changed when pulled by digest for {key}")
    return record


def build_lock(
    *,
    candidate: str,
    base_images: dict[str, str],
    repository_prefix: str,
    output_directory: Path,
    invocation_id: str,
    builder_id: str,
    docker: str = "docker",
    built_at: dt.datetime | None = None,
) -> tuple[Path, Path]:
    _repository_state(candidate)
    if set(base_images) != BASE_IMAGE_KEYS:
        raise common.TargetE2EError("base image inventory is incomplete")
    if REPOSITORY_PREFIX.fullmatch(repository_prefix) is None or "@" in repository_prefix:
        raise common.TargetE2EError("repository prefix is invalid")
    if INVOCATION_ID.fullmatch(invocation_id) is None or common.TOKEN.fullmatch(builder_id) is None:
        raise common.TargetE2EError("build invocation or builder identity is invalid")
    docker_path = shutil.which(docker)
    if docker_path is None:
        raise common.TargetE2EError("Docker CLI is unavailable")
    root = common.assert_external_runtime_path(output_directory)
    root.mkdir(parents=True, exist_ok=False)
    source_tree_sha256 = _source_tree_digest(candidate)
    timestamp = (built_at or common.utc_now()).astimezone(dt.timezone.utc)
    invocation_hash = hashlib.sha256(invocation_id.encode("ascii")).hexdigest()
    build_id = f"p9-{candidate[:12]}-{invocation_hash[:12]}"
    images: dict[str, dict[str, Any]] = {}
    for key, reference in sorted(base_images.items()):
        _run([docker_path, "pull", reference], timeout=900)
        repository = reference.rsplit("@", 1)[0]
        images[key] = _record_from_inspection(
            _inspect_image(docker_path, reference),
            repository=repository,
            source_revision=reference.rsplit("@", 1)[1],
            build_id=f"upstream-{key}-{reference[-12:]}",
            expected_reference=reference,
        )
    for key, specification in APPLICATION_IMAGES.items():
        images[key] = _build_application_image(
            docker=docker_path,
            candidate=candidate,
            build_id=build_id,
            repository_prefix=repository_prefix,
            key=key,
            specification=specification,
            metadata_directory=root,
        )
    if set(images) != set(common.IMAGE_KEYS):
        raise common.TargetE2EError(
            "measured image inventory differs from the deployment inventory"
        )
    attestation = common.seal_self_hash(
        {
            "schema_version": "target-e2e-build-attestation.v1",
            "candidate_sha": candidate,
            "source_revision": candidate,
            "source_tree_sha256": source_tree_sha256,
            "builder_id": builder_id,
            "invocation_id": invocation_id,
            "build_id": build_id,
            "built_at": timestamp.isoformat(timespec="seconds"),
            "docker_cli": docker_path,
            "images": images,
        }
    )
    attestation_path = root / "build-attestation.json"
    common.write_json(attestation_path, attestation)
    lock = common.seal_self_hash(
        {
            "schema_version": "target-e2e-image-lock.v2",
            "candidate_sha": candidate,
            "source_revision": candidate,
            "build_provenance": {
                "builder_id": builder_id,
                "invocation_id": invocation_id,
                "source_tree_sha256": source_tree_sha256,
                "built_at": timestamp.isoformat(timespec="seconds"),
                "attestation_type": "target-e2e-build-attestation.v1",
                "attestation_digest": "sha256:" + common.canonical_sha256(attestation),
            },
            "images": images,
        }
    )
    lock_path = root / "target-e2e-image-lock.json"
    common.write_json(lock_path, lock)
    common.load_image_lock(lock_path)
    return lock_path, attestation_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--base-images", type=Path, required=True)
    parser.add_argument("--repository-prefix", required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    parser.add_argument("--invocation-id", required=True)
    default_builder = os.environ.get("USERNAME") or os.environ.get("USER") or "unknown"
    parser.add_argument("--builder-id", default=f"{default_builder}-docker")
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args(argv)
    try:
        lock_path, attestation_path = build_lock(
            candidate=args.candidate,
            base_images=load_base_images(args.base_images),
            repository_prefix=args.repository_prefix,
            output_directory=args.output_directory,
            invocation_id=args.invocation_id,
            builder_id=args.builder_id,
            docker=args.docker,
        )
    except (common.TargetE2EError, OSError, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {"image_lock": str(lock_path), "attestation": str(attestation_path)},
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
