from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import preflight  # noqa: E402
import readiness  # noqa: E402
import networks  # noqa: E402


def start_services(env_file: Path, wait_timeout: int = 600) -> dict:
    preflight.run_preflight(env_file)
    _env, lock = common.validate_env_lock(env_file)
    networks.ensure_networks(env_file, lock)
    common.run_command(
        common.compose_argv(env_file, "up", "--detach", "--wait", "--wait-timeout",
                            str(wait_timeout), "--pull", "never"),
        timeout=wait_timeout + 60,
    )
    return readiness.check_readiness(env_file)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--wait-timeout", type=int, default=600)
    args = parser.parse_args(argv)
    try:
        receipt = start_services(args.env_file, args.wait_timeout)
    except (common.ProductionError, OSError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
