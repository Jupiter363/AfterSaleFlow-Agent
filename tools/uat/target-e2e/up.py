from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import preflight  # noqa: E402
import readiness  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--wait-timeout", type=int, default=600)
    args = parser.parse_args(argv)
    try:
        preflight.run_preflight(args.env_file)
        common.run_command(
            common.compose_argv(
                args.env_file,
                "up",
                "--detach",
                "--wait",
                "--wait-timeout",
                str(args.wait_timeout),
            ),
            timeout=args.wait_timeout + 60,
        )
        receipt = readiness.check_readiness(args.env_file)
    except (common.TargetE2EError, OSError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
