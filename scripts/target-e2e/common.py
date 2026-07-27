from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = ROOT / "docker-compose.target-e2e.yml"
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
IMAGE_REFERENCE = re.compile(
    r"^[a-z0-9][a-z0-9._:/-]{2,254}@sha256:[0-9a-f]{64}$"
)
RUN_ID = re.compile(r"^[a-z0-9][a-z0-9-]{5,31}$")
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class TargetE2EError(RuntimeError):
    pass


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


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("ascii")
    return hashlib.sha256(payload).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def assert_external_runtime_path(path: Path) -> Path:
    resolved = path.expanduser().resolve()
    repository = ROOT.resolve()
    if resolved == repository or repository in resolved.parents:
        raise TargetE2EError("runtime secrets and evidence must be outside the Git worktree")
    return resolved


def load_image_lock(path: Path) -> tuple[str, dict[str, str]]:
    document = load_json(path)
    if set(document) != {"schema_version", "build_id", "images"}:
        raise TargetE2EError("image lock fields drifted")
    if document["schema_version"] != "target-e2e-image-lock.v1":
        raise TargetE2EError("unsupported image lock schema")
    build_id = document["build_id"]
    images = document["images"]
    if not isinstance(build_id, str) or not SHA1.fullmatch(build_id):
        raise TargetE2EError("image lock build_id must be the exact candidate Git SHA")
    if not isinstance(images, dict) or set(images) != set(IMAGE_KEYS):
        raise TargetE2EError("image lock must contain the exact target E2E image inventory")
    if any(not isinstance(value, str) or not IMAGE_REFERENCE.fullmatch(value) for value in images.values()):
        raise TargetE2EError("every target E2E image must be a registry name plus sha256 digest")
    return build_id, dict(images)


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


def compose_argv(env_file: Path, *arguments: str, profile: str | None = None) -> list[str]:
    command = [
        "docker",
        "compose",
        "--env-file",
        str(env_file.resolve()),
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
        text=True,
        timeout=timeout,
        shell=False,
        env={**os.environ, "COMPOSE_IGNORE_ORPHANS": "false"},
    )
    if check and completed.returncode:
        message = completed.stderr.strip() or completed.stdout.strip()
        raise TargetE2EError(f"command failed ({completed.returncode}): {message}")
    return completed


def container_id(env_file: Path, service: str) -> str:
    result = run_command(compose_argv(env_file, "ps", "--quiet", service))
    identifier = result.stdout.strip()
    if not identifier or "\n" in identifier:
        raise TargetE2EError(f"expected exactly one running {service} container")
    return identifier


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
