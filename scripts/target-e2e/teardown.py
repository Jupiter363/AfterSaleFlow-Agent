from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import export_forensics  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        env = common.parse_env_file(args.env_file)
        run_id = env.get("TARGET_E2E_RUN_ID", "")
        if not common.RUN_ID.fullmatch(run_id):
            raise common.TargetE2EError("refusing teardown for a non-target project")
        manifest = export_forensics.export_forensics(args.env_file)
        common.run_command(
            common.compose_argv(
                args.env_file,
                "down",
                "--volumes",
                "--remove-orphans",
                "--timeout",
                "30",
            ),
            timeout=120,
        )
        receipt = {
            "schema_version": "target-e2e-teardown-receipt.v1",
            "status": "PASS",
            "run_id": run_id,
            "build_id": env["TARGET_E2E_BUILD_ID"],
            "forensic_manifest_sha256": common.canonical_sha256(manifest),
            "volumes_removed": True,
            "networks_removed": True,
        }
        evidence_dir = common.assert_external_runtime_path(Path(env["TARGET_E2E_EVIDENCE_DIR"]))
        common.write_json(evidence_dir / "teardown.json", receipt)
    except (common.TargetE2EError, OSError, json.JSONDecodeError) as error:
        print(f"BLOCKED: teardown requires a successful forensic export: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
