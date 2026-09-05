"""One supported local entry point: exact build, provision, preflight, start, readiness."""
from __future__ import annotations

import argparse
import json
import secrets
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_image_lock
import common
import local_config
import model_config
import provision
import up


def announce(stage: str) -> None:
    print(f"STARTUP_STAGE={stage}", flush=True)


def start(runtime_root: Path, model_env_file: Path) -> dict:
    settings = local_config.load()
    candidate = common.run_command(["git", "rev-parse", "HEAD"]).stdout.strip()
    build_image_lock._repository_state(candidate)
    model_config.load_model_configuration(model_env_file)
    common.run_command(["docker", "info", "--format", "{{.ServerVersion}}"])
    root = common.assert_external_runtime_path(runtime_root)
    root.mkdir(parents=True, exist_ok=True)
    pointer_path = root / "current-run.json"
    base_images = build_image_lock.load_base_images(local_config.BASE_IMAGES_FILE)
    configuration_hash = common.canonical_sha256({"settings": settings, "base_images": base_images})
    if pointer_path.exists():
        common.assert_regular_single_link(pointer_path, "current UAT pointer")
        pointer = common.load_json(pointer_path)
        if (set(pointer) != {"schema_version", "candidate_sha", "configuration_hash", "env_file"}
                or pointer["schema_version"] != "production-runtime-current-run.v1"
                or pointer["candidate_sha"] != candidate
                or pointer["configuration_hash"] != configuration_hash):
            raise common.ProductionError("current UAT belongs to a different source/configuration; explicitly archive/teardown it first")
        env_file = Path(pointer["env_file"])
        env, lock = common.validate_env_lock(env_file)
        if Path(lock["runtime_root"]).resolve() != root or lock["candidate_sha"] != candidate:
            raise common.ProductionError("current UAT pointer does not match its host lock")
        # Do not silently rotate an activation's frozen model credentials on resume.
        model = model_config.load_model_configuration(model_env_file)
        if env.get("PRODUCTION_RUNTIME_GRAPH_MODEL_PROFILE_ID") != model.profile_id:
            raise common.ProductionError("model configuration changed; an existing activation cannot be rewritten")
        announce("REUSE_EXACT_RUN")
    else:
        invocation = f"uat-{candidate[:8]}-{secrets.token_hex(6)}"
        announce("BUILD_EXACT_IMAGES")
        image_lock, _attestation = build_image_lock.build_lock(
            candidate=candidate, base_images=base_images,
            repository_prefix=settings["repository_prefix"],
            output_directory=root / "builds" / invocation,
            invocation_id=invocation, builder_id="local-docker-uat",
        )
        announce("PROVISION_PRIVATE_CONFIGURATION_AND_NETWORKS")
        env_file = provision.provision(image_lock, root, None, settings["gateway_port"], model_env_file)
        common.write_json(pointer_path, {
            "schema_version": "production-runtime-current-run.v1", "candidate_sha": candidate,
            "configuration_hash": configuration_hash, "env_file": str(env_file),
        })
    announce("PREFLIGHT_START_READINESS")
    readiness = up.start_services(env_file, settings["wait_timeout_seconds"])
    result = {"schema_version": "production-runtime-startup.v1", "candidate_sha": candidate,
              "env_file": str(env_file), "url": f"http://127.0.0.1:{settings['gateway_port']}/disputes",
              "readiness": readiness, "business_e2e_passed": False}
    common.write_json(env_file.parent / "startup-receipt.json", result)
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path,
                        default=Path.home() / ".after-sale-flow" / "production-runtime-local")
    parser.add_argument("--model-env-file", type=Path, default=common.ROOT / ".env")
    args = parser.parse_args(argv)
    try:
        receipt = start(args.runtime_root, args.model_env_file)
    except (common.ProductionError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
