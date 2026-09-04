from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import assert_evidence  # noqa: E402
import common  # noqa: E402
import export_forensics  # noqa: E402
import p9_gate  # noqa: E402
import preflight  # noqa: E402
import readiness  # noqa: E402


REQUIRED_SCENARIO_ASSERTIONS = p9_gate.REQUIRED_SCENARIO_ASSERTIONS


def load_command(path: Path) -> list[str]:
    common.assert_regular_single_link(path, "Batch 4 command file")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise common.ProductionError("Batch 4 command file is not strict UTF-8 JSON") from error
    if (
        not isinstance(value, list)
        or not 1 <= len(value) <= 64
        or any(
            not isinstance(item, str)
            or not item
            or len(item) > 2048
            or "\x00" in item
            for item in value
        )
    ):
        raise common.ProductionError("Batch 4 command must be a bounded argv array")
    executable = value[0]
    if Path(executable).is_absolute():
        if not Path(executable).is_file():
            raise common.ProductionError("Batch 4 command executable is unavailable")
    elif shutil.which(executable) is None:
        raise common.ProductionError("Batch 4 command executable is unavailable")
    return list(value)


def _run_stage(command: list[str], env_file: Path, case_id: str, stage: str) -> None:
    completed = common.run_command(
        [*command, "--env-file", str(env_file), "--case-id", case_id, "--stage", stage],
        check=False,
        timeout=1800,
    )
    if completed.returncode:
        raise common.ProductionError(f"Batch 4 {stage} stage failed")
    if len(completed.stdout.encode("utf-8")) > 1024 * 1024 or len(
        completed.stderr.encode("utf-8")
    ) > 1024 * 1024:
        raise common.ProductionError(f"Batch 4 {stage} stage output is unbounded")


def validate_scenario_receipt(
    receipt: dict[str, Any],
    *,
    env: dict[str, str],
    lock: dict[str, Any],
    case_id: str,
) -> None:
    p9_gate.validate_scenario_receipt(receipt, env=env, lock=lock, case_id=case_id)


def run_batch4(
    env_file: Path,
    case_id: str,
    *,
    journey_command: list[str],
    drain_command: list[str],
    wait_timeout: int,
) -> dict[str, Any]:
    if wait_timeout < 30 or wait_timeout > 1800:
        raise common.ProductionError("Batch 4 wait timeout is outside the bounded range")
    preflight.run_preflight(env_file)
    common.run_command(
        common.compose_argv(
            env_file,
            "up",
            "--detach",
            "--wait",
            "--wait-timeout",
            str(wait_timeout),
        ),
        timeout=wait_timeout + 60,
    )
    readiness.check_readiness(env_file)
    _run_stage(journey_command, env_file, case_id, "journey-and-recovery")
    assert_evidence.assert_run(env_file, case_id)
    _run_stage(drain_command, env_file, case_id, "drain-and-revoke")
    env, lock = common.validate_env_lock(env_file)
    scenario_path = Path(env["PRODUCTION_RUNTIME_EVIDENCE_DIR"]) / "batch-4-scenario.json"
    scenario = common.load_json(scenario_path)
    validate_scenario_receipt(scenario, env=env, lock=lock, case_id=case_id)
    export_forensics.export_forensics(env_file)
    return p9_gate.create_evidence(env_file, case_id)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--journey-command", type=Path, required=True)
    parser.add_argument("--drain-command", type=Path, required=True)
    parser.add_argument("--wait-timeout", type=int, default=600)
    args = parser.parse_args(argv)
    try:
        evidence = run_batch4(
            args.env_file,
            args.case_id,
            journey_command=load_command(args.journey_command),
            drain_command=load_command(args.drain_command),
            wait_timeout=args.wait_timeout,
        )
    except (
        common.ProductionError,
        OSError,
        UnicodeError,
        json.JSONDecodeError,
        subprocess.SubprocessError,
    ) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(evidence, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
