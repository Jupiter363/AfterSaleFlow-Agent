from __future__ import annotations

import copy
import re
from pathlib import Path
from typing import Any, Callable, Iterable

import pytest
import yaml

from scripts.phase8.candidate import command_contract
from scripts.phase8.candidate import github_attestation
from scripts.phase8.candidate import github_command_runner
from scripts.phase8.candidate import runtime_policy


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "phase8-engineering-witness.yml"

C0 = "10e69724038a5bea9cdd99f8fc2be5485860d7c9"
WORKFLOW_FILE = ".github/workflows/phase8-engineering-witness.yml"
REPOSITORY = "Jupiter363/AfterSaleFlow-Agent"

CHECKOUT = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
SETUP_PYTHON = "actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97"
SETUP_JAVA = "actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95"
UPLOAD = "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"
DOWNLOAD = "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c"
ATTEST = "actions/attest@f7c74d28b9d84cb8768d0b8ca14a4bac6ef463e6"
ALLOWED_ACTIONS = {CHECKOUT, SETUP_PYTHON, SETUP_JAVA, UPLOAD, DOWNLOAD, ATTEST}
FULL_SHA_ACTION = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$")

BUILD = "phase8_build_runtime"
OBSERVE = "phase8_observe_runtime"
COMMAND_IDS = tuple(command_contract.COMMAND_ORDER)
COMMAND_JOBS = tuple(f"phase8_{command_id}" for command_id in COMMAND_IDS)
PRODUCERS = (BUILD, OBSERVE, *COMMAND_JOBS)
JOB_IDS = (*PRODUCERS, "aggregate", "attest", "gate")
STATIC_COMMANDS = frozenset(command_contract.STATIC_COMMAND_IDS)
MAVEN_PRODUCER_JOBS = frozenset(
    f"phase8_{command_id}" for command_id in command_contract.MAVEN_SUITE_SPECS
)

ENABLE_ROOTLESS_USERNS_PROGRAM = """set -euo pipefail
userns="$(sysctl -n kernel.apparmor_restrict_unprivileged_userns)"
unconfined="$(sysctl -n kernel.apparmor_restrict_unprivileged_unconfined)"
[[ "$userns" =~ ^[01]$ ]]
[[ "$unconfined" =~ ^[01]$ ]]
printf 'userns=%s\\n' "$userns" >> "$GITHUB_OUTPUT"
printf 'unconfined=%s\\n' "$unconfined" >> "$GITHUB_OUTPUT"
sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_unconfined=0
sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_userns=0
test "$(sysctl -n kernel.apparmor_restrict_unprivileged_unconfined)" = "0"
test "$(sysctl -n kernel.apparmor_restrict_unprivileged_userns)" = "0"
"""

RESTORE_ROOTLESS_USERNS_PROGRAM = """set -euo pipefail
[[ "$ORIGINAL_USERNS" =~ ^[01]$ ]]
[[ "$ORIGINAL_UNCONFINED" =~ ^[01]$ ]]
sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_userns="$ORIGINAL_USERNS"
sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_unconfined="$ORIGINAL_UNCONFINED"
test "$(sysctl -n kernel.apparmor_restrict_unprivileged_userns)" = "$ORIGINAL_USERNS"
test "$(sysctl -n kernel.apparmor_restrict_unprivileged_unconfined)" = "$ORIGINAL_UNCONFINED"
"""

RESTORE_ROOTLESS_USERNS_IF = (
    "${{ always() && steps.enable-rootless-userns.outputs.userns != '' "
    "&& steps.enable-rootless-userns.outputs.unconfined != '' }}"
)
RESTORE_ROOTLESS_USERNS_ENV = {
    "ORIGINAL_USERNS": "${{ steps.enable-rootless-userns.outputs.userns }}",
    "ORIGINAL_UNCONFINED": (
        "${{ steps.enable-rootless-userns.outputs.unconfined }}"
    ),
}

EXPECTED_JOB_NAMES = tuple(f"witness / {job_id}" for job_id in JOB_IDS)
RAW_NAME_TEMPLATES = tuple(github_attestation.RAW_ARTIFACT_NAME_TEMPLATES)
FINAL_NAME_TEMPLATE = github_attestation.ARTIFACT_NAME_TEMPLATE
OBSERVATION_NAME_TEMPLATE = github_attestation.OBSERVATION_ARTIFACT_NAME_TEMPLATE
RUNTIME_NAME_TEMPLATE = github_attestation.RUNTIME_IMAGE_ARTIFACT_NAME_TEMPLATE

GITHUB_FORMATTING = {
    "run_id": "${{ github.run_id }}",
    "run_attempt": "${{ github.run_attempt }}",
    "archive_sha256": "${{ steps.inspect.outputs.archive-sha256 }}",
}
EXPECTED_ARTIFACT_NAMES = {
    BUILD: RUNTIME_NAME_TEMPLATE.format(**GITHUB_FORMATTING),
    OBSERVE: OBSERVATION_NAME_TEMPLATE.format(**GITHUB_FORMATTING),
    **{
        job_id: template.format(**GITHUB_FORMATTING)
        for job_id, template in zip(COMMAND_JOBS, RAW_NAME_TEMPLATES, strict=True)
    },
    "aggregate": FINAL_NAME_TEMPLATE.format(**GITHUB_FORMATTING),
}
EXTERNAL_OUTPUT_DIRS = {
    BUILD: "/tmp/phase8-build",
    OBSERVE: "/tmp/phase8-observe",
    **{job_id: "/tmp/phase8-command-output" for job_id in COMMAND_JOBS},
    "aggregate": "/tmp/phase8-witness",
}
EXPECTED_UPLOAD_PATHS = {
    **{job_id: EXTERNAL_OUTPUT_DIRS[job_id] for job_id in PRODUCERS},
    "aggregate": "/tmp/phase8-witness/phase8-engineering-witness.tar",
}

RAW_ROOT = "${{ runner.temp }}/phase8-raw"
RAW_ROOT_SHELL = "${RUNNER_TEMP}/phase8-raw"
AGGREGATE_DOWNLOADS = {
    "download-build": (
        BUILD,
        f"{RAW_ROOT}/shared-runtime/producer",
    ),
    "download-observe": (
        OBSERVE,
        f"{RAW_ROOT}/shared-runtime/observer",
    ),
    **{
        f"download-{command_id.replace('_', '-')}": (
            job_id,
            f"{RAW_ROOT}/commands/{index:03d}-{command_id}",
        )
        for index, (command_id, job_id) in enumerate(
            zip(COMMAND_IDS, COMMAND_JOBS, strict=True)
        )
    },
}


class _StrictSafeLoader(yaml.SafeLoader):
    pass


def _construct_unique_mapping(
    loader: _StrictSafeLoader, node: yaml.MappingNode, deep: bool = False
) -> dict[Any, Any]:
    loader.flatten_mapping(node)
    result: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


_StrictSafeLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_unique_mapping
)


def _load_workflow(text: str | None = None) -> dict[str, Any]:
    document = WORKFLOW_PATH.read_text(encoding="ascii") if text is None else text
    parsed = yaml.load(document, Loader=_StrictSafeLoader)
    if not isinstance(parsed, dict):
        raise ValueError("workflow must be a YAML mapping")
    return parsed


def _steps(job: dict[str, Any]) -> list[dict[str, Any]]:
    steps = job.get("steps")
    if not isinstance(steps, list) or not all(isinstance(step, dict) for step in steps):
        raise ValueError("job steps must be a list of mappings")
    return steps


def _step(job: dict[str, Any], step_id: str) -> dict[str, Any]:
    matches = [step for step in _steps(job) if step.get("id") == step_id]
    if len(matches) != 1:
        raise ValueError(f"expected one step with id {step_id}")
    return matches[0]


def _action_steps(jobs: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    return [step for job in jobs for step in _steps(job) if "uses" in step]


def _walk_keys(value: Any) -> Iterable[Any]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield key
            yield from _walk_keys(child)
    elif isinstance(value, list):
        for child in value:
            yield from _walk_keys(child)


def _as_needs(job: dict[str, Any]) -> list[str]:
    needs = job.get("needs", [])
    if isinstance(needs, str):
        return [needs]
    if isinstance(needs, list) and all(isinstance(item, str) for item in needs):
        return needs
    raise ValueError("needs must be a job id or ordered job id list")


def _contains_argument(program: str, option: str, value: str | None = None) -> bool:
    if value is None:
        return (
            re.search(rf"(?m)(?:^|\s){re.escape(option)}(?:\s|$)", program) is not None
        )
    return (
        re.search(
            rf"(?m)(?:^|\s){re.escape(option)}(?:=|\s+)[\"']?{re.escape(value)}[\"']?(?:\s|\\|$)",
            program,
        )
        is not None
    )


def _argument_value(program: str, option: str) -> str:
    match = re.search(
        rf"(?m)(?:^|\s){re.escape(option)}(?:=|\s+)([\"'][^\"']+[\"']|\S+)",
        program,
    )
    if match is None:
        raise ValueError(f"missing CLI argument {option}")
    return match.group(1)


def _validate_unique_tar_argument(
    program: str,
    *,
    root_assignment: str | None,
    directory: str,
    option: str,
) -> None:
    if root_assignment is not None and root_assignment not in program:
        raise ValueError(f"{option} archive root is not exact")
    match = re.search(
        r"mapfile\s+-d\s+''\s+([A-Za-z_][A-Za-z0-9_]*)\s+<\s+<\(\s*"
        rf"find\s+{re.escape(directory)}\s+-mindepth\s+1\s+-maxdepth\s+1\s+"
        r"(?:\\\s*)?-type\s+f\s+-name\s+'sha256-\*\.tar'\s+-print0\s*\)",
        program,
    )
    if match is None:
        raise ValueError(f"{option} does not enumerate the exact archive directory")
    array_name = match.group(1)
    if f'test "${{#{array_name}[@]}}" = "1"' not in program:
        raise ValueError(
            f"{option} archive directory is not required to contain one tar"
        )
    if _argument_value(program, option) != f'"${{{array_name}[0]}}"':
        raise ValueError(f"{option} is not bound to its unique content-addressed tar")


def _validate_identity_program(program: str) -> None:
    required = (
        "read -r observed_candidate trusted_workflow_sha extra_parent < <(",
        'git -C "${GITHUB_WORKSPACE}/candidate" rev-list --parents -n 1 "$candidate_sha"',
        'test "$observed_candidate" = "$candidate_sha"',
        'test -n "$trusted_workflow_sha"',
        'test -z "${extra_parent:-}"',
        (
            'trusted_workflow_ref="Jupiter363/AfterSaleFlow-Agent/'
            ".github/workflows/phase8-engineering-witness.yml@${trusted_workflow_sha}"
        ),
    )
    if any(fragment not in program for fragment in required):
        raise ValueError(
            "W0 identity is not safely derived from the candidate's unique parent"
        )


def _assert_c0_constant_cross_lock() -> None:
    if EXPECTED_JOB_NAMES != tuple(github_attestation.EXPECTED_JOB_NAMES):
        raise ValueError("workflow job topology differs from C0 EXPECTED_JOB_NAMES")
    if github_command_runner.BUILD_JOB != BUILD:
        raise ValueError("build job differs from the C0 runner")
    if github_command_runner.OBSERVE_JOB != OBSERVE:
        raise ValueError("observer job differs from the C0 runner")
    if tuple(github_command_runner.COMMAND_JOBS) != COMMAND_IDS:
        raise ValueError("command job order differs from the C0 runner")
    if tuple(github_command_runner.COMMAND_JOBS.values()) != COMMAND_JOBS:
        raise ValueError("command job names differ from the C0 runner")
    if runtime_policy.STATIC_COMMAND_ARTIFACT_PREFIXES != {
        command_id: RAW_NAME_TEMPLATES[COMMAND_IDS.index(command_id)].rsplit(
            "-{run_id}-{run_attempt}", 1
        )[0]
        for command_id in STATIC_COMMANDS
    }:
        raise ValueError("static artifact names differ from the C0 runtime policy")


def _validate_root_and_actions(workflow: dict[str, Any]) -> None:
    if set(workflow) != {"name", "on", "permissions", "jobs"}:
        raise ValueError("workflow root surface changed")
    if workflow["on"] != {"workflow_call": {}}:
        raise ValueError("only input-free workflow_call is permitted")
    if workflow["permissions"] != {}:
        raise ValueError("default permissions must be empty")
    if tuple(workflow["jobs"]) != JOB_IDS:
        raise ValueError("workflow job ids, order, or membership changed")
    if any(
        key in {"inputs", "secrets", "concurrency", "environment"}
        for key in _walk_keys(workflow)
    ):
        raise ValueError(
            "external input, secret, concurrency, or deployment environment is forbidden"
        )

    jobs = workflow["jobs"]
    attest_permissions = {
        "contents": "read",
        "id-token": "write",
        "attestations": "write",
        "artifact-metadata": "write",
    }
    for job_id, job in jobs.items():
        if job.get("runs-on") != "ubuntu-24.04":
            raise ValueError("only ubuntu-24.04 GitHub-hosted runners are permitted")
        if "uses" in job:
            raise ValueError("reusable, relative, or dynamic jobs are forbidden")
        if job_id == "attest":
            expected_permissions = attest_permissions
        elif job_id == "gate":
            expected_permissions = {}
        else:
            expected_permissions = {"contents": "read"}
        if job.get("permissions") != expected_permissions:
            raise ValueError(
                f"{job_id} permissions differ from the exact least-privilege set"
            )

    action_steps = _action_steps(jobs.values())
    uses = [step.get("uses") for step in action_steps]
    if any(
        not isinstance(action, str) or FULL_SHA_ACTION.fullmatch(action) is None
        for action in uses
    ):
        raise ValueError("branch, tag, relative, or abbreviated action reference")
    if any(action not in ALLOWED_ACTIONS for action in uses):
        raise ValueError("unapproved action entered the workflow")
    if "github.workflow_sha" in repr(workflow):
        raise ValueError("github.workflow_sha is not the W0 workflow identity")
    if "${{ inputs." in repr(workflow) or "${{ secrets." in repr(workflow):
        raise ValueError("caller-controlled expression entered the workflow")

    for job in jobs.values():
        for step in _steps(job):
            if "run" not in step:
                continue
            if step.get("shell") != "bash":
                raise ValueError("every shell program must select bash explicitly")
            program = step["run"]
            if not isinstance(program, str):
                raise ValueError("shell program must be a string")
            if re.search(r"(?m)(?:^|[;&|()]\s*)(?:eval|source|curl)(?:\s|$)", program):
                raise ValueError("dynamic evaluation or network fetch is forbidden")


def _validate_checkouts_and_identity(workflow: dict[str, Any]) -> None:
    jobs = workflow["jobs"]
    for job_id in (*PRODUCERS, "aggregate"):
        job = jobs[job_id]
        candidate = _step(job, "candidate-checkout")
        if candidate.get("uses") != CHECKOUT or candidate.get("with") != {
            "ref": "${{ github.sha }}",
            "path": "candidate",
            "fetch-depth": 0,
            "persist-credentials": False,
        }:
            raise ValueError(f"{job_id} candidate checkout is not exact")

    for job_id in PRODUCERS:
        trusted = _step(jobs[job_id], "trusted-code-checkout")
        if trusted.get("uses") != CHECKOUT or trusted.get("with") != {
            "ref": C0,
            "path": "trusted-code",
            "fetch-depth": 0,
            "persist-credentials": False,
        }:
            raise ValueError(f"{job_id} trusted checkout is not the literal C0")

    aggregate_trusted = _step(jobs["aggregate"], "trusted-code-checkout")
    if aggregate_trusted != {
        "id": "trusted-code-checkout",
        "shell": "bash",
        "env": {"TRUSTED_CODE_SHA": C0},
        "run": """set -euo pipefail
candidate="${GITHUB_WORKSPACE}/candidate"
trusted="${GITHUB_WORKSPACE}/trusted-code"
test ! -e "$trusted"
test ! -L "$trusted"
test "$(git -C "$candidate" rev-parse HEAD)" = "$GITHUB_SHA"
git -C "$candidate" cat-file -e "${TRUSTED_CODE_SHA}^{commit}"
git -C "$candidate" merge-base --is-ancestor "$TRUSTED_CODE_SHA" "$GITHUB_SHA"
git -C "$candidate" worktree add --detach "$trusted" "$TRUSTED_CODE_SHA"
test -f "$trusted/.git"
test "$(git -C "$trusted" rev-parse HEAD)" = "$TRUSTED_CODE_SHA"
candidate_common="$(git -C "$candidate" rev-parse --path-format=absolute --git-common-dir)"
trusted_common="$(git -C "$trusted" rev-parse --path-format=absolute --git-common-dir)"
test -d "$candidate_common"
test ! -L "$candidate_common"
test "$candidate_common" = "$trusted_common"
""",
    }:
        raise ValueError("aggregate trusted worktree is not the literal shared-store C0")

    for job_id in ("attest", "gate"):
        if any(step.get("uses") == CHECKOUT for step in _steps(jobs[job_id])):
            raise ValueError(f"{job_id} may not checkout candidate or trusted code")


def _validate_needs(workflow: dict[str, Any]) -> None:
    jobs = workflow["jobs"]
    if _as_needs(jobs[BUILD]):
        raise ValueError("runtime build must be the first job")
    if _as_needs(jobs[OBSERVE]) != [BUILD]:
        raise ValueError("runtime observer must directly follow the build")

    previous = OBSERVE
    for command_id, job_id in zip(COMMAND_IDS, COMMAND_JOBS, strict=True):
        needs = _as_needs(jobs[job_id])
        required = [previous]
        if command_id in STATIC_COMMANDS:
            required = list(dict.fromkeys((BUILD, OBSERVE, previous)))
        if needs != required:
            raise ValueError("commands are not an exact serial, runtime-bound chain")
        previous = job_id

    if _as_needs(jobs["aggregate"]) != list(PRODUCERS):
        raise ValueError("aggregate must directly need all seven producers")
    if jobs["aggregate"].get("if") != "${{ always() }}":
        raise ValueError("aggregate must run under always()")
    if _as_needs(jobs["attest"]) != ["aggregate"]:
        raise ValueError("attest may consume only aggregate")
    if _as_needs(jobs["gate"]) != [*PRODUCERS, "aggregate", "attest"]:
        raise ValueError("gate must directly need all producers, aggregate, and attest")
    if jobs["gate"].get("if") != "${{ always() }}":
        raise ValueError("gate must run under always()")


def _validate_runner_step(
    job_id: str, job: dict[str, Any], operation: str, command_id: str | None = None
) -> None:
    execute = _step(job, "execute")
    program = execute.get("run", "")
    trusted_cwd = execute.get("working-directory") == "trusted-code" or (
        'cd "${GITHUB_WORKSPACE}/trusted-code"' in program
    )
    if not trusted_cwd:
        raise ValueError(f"{job_id} does not execute from the literal C0 checkout")
    _validate_identity_program(program)
    effective_env = {**job.get("env", {}), **execute.get("env", {})}
    if effective_env.get("PYTHONDONTWRITEBYTECODE") not in ("1", 1):
        raise ValueError(f"{job_id} can write Python bytecode into trusted code")
    invocation = "python -m scripts.phase8.candidate.github_command_runner"
    if invocation not in program or not _contains_argument(program, operation):
        raise ValueError(f"{job_id} does not invoke the exact C0 runner operation")
    for option in (
        "--candidate-sha",
        "--trusted-code-sha",
        "--trusted-workflow-sha",
        "--trusted-workflow-ref",
        "--trusted-workflow-repository",
        "--trusted-workflow-file-path",
        "--output-dir",
    ):
        if not _contains_argument(program, option):
            raise ValueError(f"{job_id} omitted runner identity option {option}")
    if not _contains_argument(program, "--output-dir", EXTERNAL_OUTPUT_DIRS[job_id]):
        raise ValueError(f"{job_id} output directory is not the exact external path")
    identity_surface = f"{program}\n{effective_env!r}"
    if (
        C0 not in identity_surface
        or REPOSITORY not in identity_surface
        or WORKFLOW_FILE not in identity_surface
    ):
        raise ValueError(
            f"{job_id} runner identity is not bound to C0 and the exact workflow"
        )

    runtime_options = {
        "--image-archive",
        "--execution-image-archive",
        "--observer-image-archive",
        "--observer-execution-image-archive",
        "--producer-receipt",
        "--runtime-build-receipt",
        "--build-observation-receipt",
        "--wheelhouse-root",
    }
    present_runtime = {
        option for option in runtime_options if _contains_argument(program, option)
    }
    if command_id is None:
        if _contains_argument(program, "--command-id"):
            raise ValueError(f"{job_id} unexpectedly selects a command")
        expected_runtime = (
            {
                "--image-archive",
                "--execution-image-archive",
                "--producer-receipt",
            }
            if operation == "observe-runtime"
            else set()
        )
        if present_runtime != expected_runtime:
            raise ValueError(f"{job_id} runtime CLI option set differs")
        return
    if not _contains_argument(program, "--command-id", command_id):
        raise ValueError(f"{job_id} command id differs from the C0 order")
    expected_runtime = (
        runtime_options - {"--producer-receipt"}
        if command_id in STATIC_COMMANDS
        else set()
    )
    if present_runtime != expected_runtime:
        raise ValueError(
            "static commands need all runtime inputs and Maven commands need none"
        )


def _producer_outputs() -> dict[str, str]:
    return {
        "execution-status": "${{ steps.execute.outcome }}",
        "artifact-status": "${{ steps.upload.outcome }}",
        "artifact-id": "${{ steps.upload.outputs.artifact-id }}",
        "artifact-digest": "${{ steps.upload.outputs.artifact-digest }}",
    }


def _validate_upload(job_id: str, job: dict[str, Any]) -> None:
    upload = _step(job, "upload")
    if job_id == BUILD:
        expected_if = (
            "${{ always() && steps.execute.outcome == 'success' "
            "&& steps.inspect.outcome == 'success' }}"
        )
    elif job_id == "aggregate":
        expected_if = (
            "${{ always() && steps.aggregate.outcome == 'success' "
            "&& steps.digest.outcome == 'success' }}"
        )
    elif job_id in MAVEN_PRODUCER_JOBS:
        expected_if = (
            "${{ always() && steps.execute.outcome == 'success' "
            "&& steps.restore-rootless-userns.outcome == 'success' }}"
        )
    else:
        expected_if = "${{ always() && steps.execute.outcome == 'success' }}"
    if upload.get("uses") != UPLOAD or upload.get("if") != expected_if:
        raise ValueError(f"{job_id} upload is not the single fail-closed upload")
    settings = upload.get("with")
    if not isinstance(settings, dict):
        raise ValueError("upload settings must be a mapping")
    expected_common = {
        "name": EXPECTED_ARTIFACT_NAMES[job_id],
        "if-no-files-found": "error",
        "compression-level": 0,
        "overwrite": False,
        "include-hidden-files": False,
        "retention-days": 90,
    }
    if {key: settings.get(key) for key in expected_common} != expected_common:
        raise ValueError(f"{job_id} artifact name or immutable upload policy drifted")
    if set(settings) != {*expected_common, "path"}:
        raise ValueError(f"{job_id} upload surface is not closed")
    if settings["path"] != EXPECTED_UPLOAD_PATHS[job_id]:
        raise ValueError(f"{job_id} upload path differs from its exact external output")


def _validate_rootless_userns_guard(
    job_id: str, job: dict[str, Any], *, is_maven: bool
) -> None:
    steps = _steps(job)
    guarded_ids = {"enable-rootless-userns", "restore-rootless-userns"}
    guard_steps = [step for step in steps if step.get("id") in guarded_ids]
    if not is_maven:
        if guard_steps:
            raise ValueError(f"{job_id} may not mutate the host user-namespace policy")
        return

    if len(guard_steps) != 2:
        raise ValueError(f"{job_id} rootless user-namespace guard is incomplete")
    enable = _step(job, "enable-rootless-userns")
    restore = _step(job, "restore-rootless-userns")
    expected_enable = {
        "id": "enable-rootless-userns",
        "shell": "bash",
        "run": ENABLE_ROOTLESS_USERNS_PROGRAM,
    }
    expected_restore = {
        "id": "restore-rootless-userns",
        "if": RESTORE_ROOTLESS_USERNS_IF,
        "shell": "bash",
        "env": RESTORE_ROOTLESS_USERNS_ENV,
        "run": RESTORE_ROOTLESS_USERNS_PROGRAM,
    }
    if enable != expected_enable:
        raise ValueError(f"{job_id} rootless user-namespace enable step drifted")
    if restore != expected_restore:
        raise ValueError(f"{job_id} rootless user-namespace restore step drifted")

    enable_index = steps.index(enable)
    execute_index = steps.index(_step(job, "execute"))
    restore_index = steps.index(restore)
    if (enable_index, execute_index, restore_index) != (
        execute_index - 1,
        execute_index,
        execute_index + 1,
    ):
        raise ValueError(f"{job_id} execute is not enclosed by the exact host guard")


def _validate_producers(workflow: dict[str, Any]) -> None:
    jobs = workflow["jobs"]
    for job_id in PRODUCERS:
        job = jobs[job_id]
        python_steps = [
            step for step in _steps(job) if step.get("uses") == SETUP_PYTHON
        ]
        if len(python_steps) != 1 or python_steps[0].get("with") != {
            "python-version": "3.11"
        }:
            raise ValueError(f"{job_id} Python toolchain is not exact")
        java_steps = [step for step in _steps(job) if step.get("uses") == SETUP_JAVA]
        is_maven = job_id in MAVEN_PRODUCER_JOBS
        expected_java_count = 1 if is_maven else 0
        if len(java_steps) != expected_java_count or (
            java_steps
            and java_steps[0].get("with")
            != {"distribution": "temurin", "java-version": "21"}
        ):
            raise ValueError(f"{job_id} Java toolchain surface changed")
        _validate_rootless_userns_guard(job_id, job, is_maven=is_maven)

        expected_outputs = _producer_outputs()
        if job_id == BUILD:
            expected_outputs = {
                "archive-sha256": "${{ steps.inspect.outputs.archive-sha256 }}",
                **expected_outputs,
            }
        if job.get("outputs") != expected_outputs:
            raise ValueError(
                f"{job_id} outputs do not bind execution and artifact identity"
            )
        operation = "build-runtime" if job_id == BUILD else "observe-runtime"
        command_id = None
        if job_id in COMMAND_JOBS:
            operation = "execute-command"
            command_id = COMMAND_IDS[COMMAND_JOBS.index(job_id)]
        _validate_runner_step(job_id, job, operation, command_id)
        _validate_upload(job_id, job)
        if len([step for step in _steps(job) if step.get("uses") == UPLOAD]) != 1:
            raise ValueError(f"{job_id} must produce exactly one artifact")

        downloads = [step for step in _steps(job) if step.get("uses") == DOWNLOAD]
        if job_id == OBSERVE:
            if len(downloads) != 1:
                raise ValueError("observer must download only the exact build artifact")
            _validate_download(
                _step(job, "download-build"),
                artifact_id=f"${{{{ needs.{BUILD}.outputs.artifact-id }}}}",
                path="${{ runner.temp }}/phase8-observe-input",
                merge_multiple=True,
            )
            program = _step(job, "execute").get("run", "")
            observe_root = "${RUNNER_TEMP}/phase8-observe-input"
            _validate_unique_tar_argument(
                program,
                root_assignment=None,
                directory=f'"{observe_root}/oci"',
                option="--image-archive",
            )
            _validate_unique_tar_argument(
                program,
                root_assignment=None,
                directory=f'"{observe_root}/docker"',
                option="--execution-image-archive",
            )
            if _argument_value(program, "--producer-receipt") != (
                f'"{observe_root}/runtime-build-receipt.json"'
            ):
                raise ValueError("observer producer receipt root differs")
        elif command_id in STATIC_COMMANDS:
            if len(downloads) != 2:
                raise ValueError(
                    "static command must download exactly two runtime artifacts"
                )
            _validate_download(
                _step(job, "download-build"),
                artifact_id=f"${{{{ needs.{BUILD}.outputs.artifact-id }}}}",
                path=("${{ runner.temp }}/phase8-static-input/shared-runtime/producer"),
                merge_multiple=True,
            )
            _validate_download(
                _step(job, "download-observe"),
                artifact_id=f"${{{{ needs.{OBSERVE}.outputs.artifact-id }}}}",
                path=("${{ runner.temp }}/phase8-static-input/shared-runtime/observer"),
                merge_multiple=True,
            )
            program = _step(job, "execute").get("run", "")
            producer_root = "${RUNNER_TEMP}/phase8-static-input/shared-runtime/producer"
            observer_root = "${RUNNER_TEMP}/phase8-static-input/shared-runtime/observer"
            _validate_unique_tar_argument(
                program,
                root_assignment=f'producer="{producer_root}"',
                directory='"$producer/oci"',
                option="--image-archive",
            )
            _validate_unique_tar_argument(
                program,
                root_assignment=f'producer="{producer_root}"',
                directory='"$producer/docker"',
                option="--execution-image-archive",
            )
            _validate_unique_tar_argument(
                program,
                root_assignment=f'observer="{observer_root}"',
                directory='"$observer/oci"',
                option="--observer-image-archive",
            )
            _validate_unique_tar_argument(
                program,
                root_assignment=f'observer="{observer_root}"',
                directory='"$observer/docker"',
                option="--observer-execution-image-archive",
            )
            expected_static_paths = {
                "--runtime-build-receipt": '"$producer/runtime-build-receipt.json"',
                "--build-observation-receipt": (
                    '"$observer/build-observation-receipt.json"'
                ),
                "--wheelhouse-root": '"$producer/wheelhouse"',
            }
            if any(
                _argument_value(program, option) != value
                for option, value in expected_static_paths.items()
            ):
                raise ValueError("static runtime receipt or wheelhouse root differs")
        elif downloads:
            raise ValueError(
                "build and Maven command jobs may not download runtime artifacts"
            )

    build_inspect = _step(jobs[BUILD], "inspect")
    inspect_run = build_inspect.get("run", "")
    if (
        'find "/tmp/phase8-build/oci"' not in inspect_run
        or "${RUNNER_TEMP}/phase8-build" in inspect_run
        or "archive-sha256" not in inspect_run
    ):
        raise ValueError(
            "build inspect is not bound to the external OCI output and its SHA-256"
        )


def _validate_download(
    step: dict[str, Any], *, artifact_id: str, path: str, merge_multiple: bool
) -> None:
    if step.get("uses") != DOWNLOAD:
        raise ValueError("artifact download action changed")
    settings = step.get("with")
    expected = {
        "artifact-ids": artifact_id,
        "path": path,
        "digest-mismatch": "error",
        "merge-multiple": merge_multiple,
        "skip-decompress": False,
    }
    if settings != expected:
        raise ValueError("download is not ID-bound with strict digest and merge policy")


def _validate_aggregate(workflow: dict[str, Any]) -> None:
    aggregate = workflow["jobs"]["aggregate"]
    if aggregate.get("outputs") != {
        "execution-status": "${{ steps.aggregate.outcome }}",
        "artifact-status": "${{ steps.upload.outcome }}",
        "digest-status": "${{ steps.digest.outcome }}",
        "artifact-id": "${{ steps.upload.outputs.artifact-id }}",
        "artifact-digest": "${{ steps.upload.outputs.artifact-digest }}",
        "witness-sha256": "${{ steps.digest.outputs.witness-sha256 }}",
    }:
        raise ValueError(
            "aggregate outputs lost execution, artifact, or subject identity"
        )
    _step(aggregate, "preflight")
    for step_id, (producer, path) in AGGREGATE_DOWNLOADS.items():
        _validate_download(
            _step(aggregate, step_id),
            artifact_id=f"${{{{ needs.{producer}.outputs.artifact-id }}}}",
            path=path,
            merge_multiple=True,
        )

    step = _step(aggregate, "aggregate")
    program = step.get("run", "")
    trusted_cwd = step.get("working-directory") == "trusted-code" or (
        'cd "${GITHUB_WORKSPACE}/trusted-code"' in program
    )
    if not trusted_cwd:
        raise ValueError("aggregate does not execute from the literal C0 checkout")
    _validate_identity_program(program)
    effective_env = {**aggregate.get("env", {}), **step.get("env", {})}
    if effective_env.get("PYTHONDONTWRITEBYTECODE") not in ("1", 1):
        raise ValueError("aggregate can write Python bytecode into trusted code")
    if "python -m scripts.phase8.candidate.github_witness" not in program:
        raise ValueError("aggregate does not invoke the C0 github_witness module")
    for option in (
        "--candidate-dir",
        "--candidate-sha",
        "--raw-artifacts-dir",
        "--output-dir",
        "--attempt-id",
        "--trusted-code-sha",
        "--trusted-workflow-sha",
        "--trusted-workflow-ref",
        "--trusted-workflow-repository",
        "--trusted-workflow-file-path",
    ):
        if not _contains_argument(program, option):
            raise ValueError(f"aggregate omitted github_witness option {option}")
    if not _contains_argument(
        program, "--output-dir", EXTERNAL_OUTPUT_DIRS["aggregate"]
    ):
        raise ValueError("aggregate output directory is not the exact external path")
    identity_surface = f"{program}\n{effective_env!r}"
    if (
        C0 not in identity_surface
        or REPOSITORY not in identity_surface
        or WORKFLOW_FILE not in identity_surface
    ):
        raise ValueError("aggregate identity is not bound to C0 and the exact workflow")
    if RAW_ROOT_SHELL not in program:
        raise ValueError("aggregate does not consume the fixed raw artifact tree")

    digest = _step(aggregate, "digest")
    digest_run = digest.get("run", "")
    for fragment in (
        'subject="/tmp/phase8-witness/phase8-engineering-witness.tar"',
        "sha256sum",
        "witness-sha256",
    ):
        if fragment not in digest_run:
            raise ValueError("aggregate subject digest chain is incomplete")
    _validate_upload("aggregate", aggregate)
    if len([step for step in _steps(aggregate) if step.get("uses") == UPLOAD]) != 1:
        raise ValueError("aggregate must produce exactly the eighth artifact")


def _validate_attest(workflow: dict[str, Any]) -> None:
    attest = workflow["jobs"]["attest"]
    if attest.get("outputs") != {
        "download-status": "${{ steps.download.outcome }}",
        "verification-status": "${{ steps.verify.outcome }}",
        "attestation-status": "${{ steps.attest.outcome }}",
    }:
        raise ValueError("attest outputs do not expose the complete status chain")
    if [step.get("id") for step in _steps(attest)] != ["download", "verify", "attest"]:
        raise ValueError("candidate-controlled code or another step entered attest")
    serialized = repr(attest)
    if any(
        forbidden in serialized
        for forbidden in (
            "actions/checkout",
            "github.workspace",
            "scripts.phase8",
            "candidate/",
            "trusted-code",
        )
    ):
        raise ValueError(
            "attest may not checkout or execute candidate/trusted repository code"
        )
    _validate_download(
        _step(attest, "download"),
        artifact_id="${{ needs.aggregate.outputs.artifact-id }}",
        path="${{ runner.temp }}/phase8-attest",
        merge_multiple=True,
    )
    verify = _step(attest, "verify")
    if verify.get("env") != {
        "EXPECTED_ARTIFACT_DIGEST": "${{ needs.aggregate.outputs.artifact-digest }}",
        "EXPECTED_ARTIFACT_ID": "${{ needs.aggregate.outputs.artifact-id }}",
        "EXPECTED_WITNESS_SHA256": "${{ needs.aggregate.outputs.witness-sha256 }}",
    }:
        raise ValueError(
            "attest verification lost aggregate artifact or subject identity"
        )
    verify_run = verify.get("run", "")
    if re.search(r"(?m)(?:^|\s)(?:python3?|node|java|mvnw?|\./)(?:\s|$)", verify_run):
        raise ValueError("attest verification may not execute repository code")
    for fragment in (
        'test "${#entries[@]}" = "1"',
        "phase8-engineering-witness.tar",
        '[[ "$EXPECTED_ARTIFACT_ID" =~ ^[1-9][0-9]*$ ]]',
        '[[ "$EXPECTED_ARTIFACT_DIGEST" =~ ^[0-9a-f]{64}$ ]]',
        '[[ "$EXPECTED_WITNESS_SHA256" =~ ^[0-9a-f]{64}$ ]]',
        "sha256sum",
        'test "$actual" = "$EXPECTED_WITNESS_SHA256"',
    ):
        if fragment not in verify_run:
            raise ValueError("single-subject SHA-256 verification weakened")
    signing = _step(attest, "attest")
    if signing.get("uses") != ATTEST or signing.get("with") != {
        "subject-path": "${{ runner.temp }}/phase8-attest/phase8-engineering-witness.tar"
    }:
        raise ValueError("attestation subject is not the single verified witness tar")


def _gate_bindings() -> tuple[set[str], set[str], set[str]]:
    success: set[str] = set()
    artifact_ids: set[str] = set()
    digests: set[str] = set()
    for job_id in (*PRODUCERS, "aggregate"):
        success.update(
            {
                f"${{{{ needs.{job_id}.result }}}}",
                f"${{{{ needs.{job_id}.outputs.execution-status }}}}",
                f"${{{{ needs.{job_id}.outputs.artifact-status }}}}",
            }
        )
        artifact_ids.add(f"${{{{ needs.{job_id}.outputs.artifact-id }}}}")
        digests.add(f"${{{{ needs.{job_id}.outputs.artifact-digest }}}}")
    success.add("${{ needs.aggregate.outputs.digest-status }}")
    digests.add("${{ needs.phase8_build_runtime.outputs.archive-sha256 }}")
    digests.add("${{ needs.aggregate.outputs.witness-sha256 }}")
    success.update(
        {
            "${{ needs.attest.result }}",
            "${{ needs.attest.outputs.download-status }}",
            "${{ needs.attest.outputs.verification-status }}",
            "${{ needs.attest.outputs.attestation-status }}",
        }
    )
    return success, artifact_ids, digests


def _validate_gate(workflow: dict[str, Any]) -> None:
    gate = workflow["jobs"]["gate"]
    if [step.get("id") for step in _steps(gate)] != ["enforce"]:
        raise ValueError("gate must have one explicit enforcement step")
    enforce = _step(gate, "enforce")
    env = enforce.get("env")
    if not isinstance(env, dict) or not all(isinstance(key, str) for key in env):
        raise ValueError("gate bindings must be an environment mapping")
    success, artifact_ids, digests = _gate_bindings()
    expected_values = success | artifact_ids | digests
    if set(env.values()) != expected_values or len(env) != len(expected_values):
        raise ValueError(
            "gate does not bind every result, status, artifact ID, and digest exactly once"
        )
    program = enforce.get("run", "")
    inverse = {value: key for key, value in env.items()}
    for value in success:
        key = inverse[value]
        if f"${key}" not in program and f"${{{key}}}" not in program:
            raise ValueError(f"gate does not require success for {value}")
    for value in artifact_ids | digests:
        key = inverse[value]
        if f"${key}" not in program and f"${{{key}}}" not in program:
            raise ValueError(f"gate does not explicitly inspect {value}")
    for fragment in (
        'for value in "${statuses[@]}"; do',
        'test "$value" = "success"',
        'for value in "${artifact_ids[@]}"; do',
        '[[ "$value" =~ ^[1-9][0-9]*$ ]]',
        'for value in "${digests[@]}"; do',
        '[[ "$value" =~ ^[0-9a-f]{64}$ ]]',
        'test "${#artifact_ids[@]}" = "8"',
        'sort -u | wc -l)" = "8"',
    ):
        if fragment not in program:
            raise ValueError(
                "gate array validation, cardinality, or uniqueness weakened"
            )


def _validate_workflow(workflow: dict[str, Any]) -> None:
    _assert_c0_constant_cross_lock()
    _validate_root_and_actions(workflow)
    _validate_checkouts_and_identity(workflow)
    _validate_needs(workflow)
    _validate_producers(workflow)
    _validate_aggregate(workflow)
    _validate_attest(workflow)
    _validate_gate(workflow)
    uploads = [
        step
        for job in workflow["jobs"].values()
        for step in _steps(job)
        if step.get("uses") == UPLOAD
    ]
    if len(uploads) != 8:
        raise ValueError(
            "workflow must upload exactly seven raw artifacts and one final artifact"
        )


def test_workflow_is_a_strict_c0_engineering_witness_pipeline() -> None:
    _validate_workflow(_load_workflow())


def test_strict_parser_rejects_duplicate_keys() -> None:
    text = WORKFLOW_PATH.read_text(encoding="ascii")
    with pytest.raises(ValueError, match="duplicate YAML key"):
        _load_workflow(
            text.replace("permissions: {}", "permissions: {}\npermissions: {}", 1)
        )


Mutation = Callable[[dict[str, Any]], None]


def _omit_job(workflow: dict[str, Any]) -> None:
    del workflow["jobs"][COMMAND_JOBS[-1]]


def _extra_job(workflow: dict[str, Any]) -> None:
    workflow["jobs"]["bypass"] = copy.deepcopy(workflow["jobs"]["gate"])


def _parallel_command(workflow: dict[str, Any]) -> None:
    workflow["jobs"][COMMAND_JOBS[1]]["needs"] = OBSERVE


def _reordered_command(workflow: dict[str, Any]) -> None:
    jobs = workflow["jobs"]
    first = jobs.pop(COMMAND_JOBS[0])
    second = jobs.pop(COMMAND_JOBS[1])
    rebuilt: dict[str, Any] = {}
    for key, value in jobs.items():
        rebuilt[key] = value
        if key == OBSERVE:
            rebuilt[COMMAND_JOBS[1]] = second
            rebuilt[COMMAND_JOBS[0]] = first
    workflow["jobs"] = rebuilt


def _wrong_c0(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"][BUILD], "trusted-code-checkout")["with"]["ref"] = "0" * 40


def _dynamic_ref(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"][BUILD], "trusted-code-checkout")["with"]["ref"] = (
        "${{ github.sha }}"
    )


def _wrong_module(workflow: dict[str, Any]) -> None:
    step = _step(workflow["jobs"][BUILD], "execute")
    step["run"] = step["run"].replace(
        "python -m scripts.phase8.candidate.github_command_runner",
        "python trusted-code/scripts/phase8/candidate/github_command_runner.py",
    )


def _missing_no_bytecode(workflow: dict[str, Any]) -> None:
    job = workflow["jobs"][BUILD]
    job.get("env", {}).pop("PYTHONDONTWRITEBYTECODE", None)
    _step(job, "execute").get("env", {}).pop("PYTHONDONTWRITEBYTECODE", None)


def _runner_temp_producer_output(workflow: dict[str, Any]) -> None:
    job = workflow["jobs"][BUILD]
    execute = _step(job, "execute")
    execute["run"] = execute["run"].replace(
        '--output-dir "/tmp/phase8-build"',
        '--output-dir "${RUNNER_TEMP}/phase8-build"',
    )
    _step(job, "upload")["with"]["path"] = "${{ runner.temp }}/phase8-build"


def _replace_cli_argument(program: str, option: str, replacement: str) -> str:
    updated, count = re.subn(
        rf"({re.escape(option)}\s+)([\"'][^\"']+[\"']|\S+)",
        rf"\g<1>{replacement}",
        program,
        count=1,
    )
    if count != 1:
        raise AssertionError(f"fixture omitted {option}")
    return updated


def _omit_observer_docker_archive(workflow: dict[str, Any]) -> None:
    step = _step(workflow["jobs"][COMMAND_JOBS[0]], "execute")
    step["run"], count = re.subn(
        r"(?m)^\s*--observer-execution-image-archive[^\n]*\n",
        "",
        step["run"],
        count=1,
    )
    if count != 1:
        raise AssertionError("fixture omitted observer Docker CLI argument")


def _cross_wire_observer_docker_archive(workflow: dict[str, Any]) -> None:
    step = _step(workflow["jobs"][COMMAND_JOBS[0]], "execute")
    producer_value = _argument_value(step["run"], "--execution-image-archive")
    step["run"] = _replace_cli_argument(
        step["run"], "--observer-execution-image-archive", producer_value
    )


def _substitute_docker_with_oci(workflow: dict[str, Any]) -> None:
    step = _step(workflow["jobs"][COMMAND_JOBS[0]], "execute")
    oci_value = _argument_value(step["run"], "--image-archive")
    step["run"] = _replace_cli_argument(
        step["run"], "--execution-image-archive", oci_value
    )


def _wrong_archive_root(workflow: dict[str, Any]) -> None:
    step = _step(workflow["jobs"][COMMAND_JOBS[0]], "execute")
    step["run"] = step["run"].replace(
        'find "$producer/docker"', 'find "$observer/docker"', 1
    )


def _weaken_unique_tar_cardinality(workflow: dict[str, Any]) -> None:
    step = _step(workflow["jobs"][OBSERVE], "execute")
    archive_value = _argument_value(step["run"], "--execution-image-archive")
    match = re.fullmatch(r'"\$\{([A-Za-z_][A-Za-z0-9_]*)\[0\]\}"', archive_value)
    if match is None:
        raise AssertionError("fixture Docker archive is not array-bound")
    assertion = f'test "${{#{match.group(1)}[@]}}" = "1"'
    step["run"] = step["run"].replace(assertion, assertion.replace('"1"', '"2"'), 1)


def _maven_runtime_archive(workflow: dict[str, Any]) -> None:
    step = _step(workflow["jobs"][COMMAND_JOBS[1]], "execute")
    output = '--output-dir "/tmp/phase8-command-output"'
    step["run"] = step["run"].replace(
        output,
        '--image-archive "/tmp/forbidden-runtime.tar" \\\n              ' + output,
        1,
    )


def _maven_job(workflow: dict[str, Any]) -> dict[str, Any]:
    job_id = next(job_id for job_id in COMMAND_JOBS if job_id in MAVEN_PRODUCER_JOBS)
    return workflow["jobs"][job_id]


def _omit_userns_enable(workflow: dict[str, Any]) -> None:
    job = _maven_job(workflow)
    job["steps"].remove(_step(job, "enable-rootless-userns"))


def _late_userns_capture(workflow: dict[str, Any]) -> None:
    step = _step(_maven_job(workflow), "enable-rootless-userns")
    output = "printf 'userns=%s\\n' \"$userns\" >> \"$GITHUB_OUTPUT\"\n"
    step["run"] = step["run"].replace(output, "", 1) + output


def _unsafe_userns_enable_order(workflow: dict[str, Any]) -> None:
    step = _step(_maven_job(workflow), "enable-rootless-userns")
    expected = (
        "sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_unconfined=0\n"
        "sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_userns=0"
    )
    replacement = (
        "sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_userns=0\n"
        "sudo -n sysctl -w kernel.apparmor_restrict_unprivileged_unconfined=0"
    )
    step["run"] = step["run"].replace(expected, replacement, 1)


def _interactive_userns_sudo(workflow: dict[str, Any]) -> None:
    step = _step(_maven_job(workflow), "enable-rootless-userns")
    step["run"] = step["run"].replace("sudo -n sysctl", "sudo sysctl", 1)


def _restore_requires_enable_success(workflow: dict[str, Any]) -> None:
    step = _step(_maven_job(workflow), "restore-rootless-userns")
    step["if"] = "${{ always() && steps.enable-rootless-userns.outcome == 'success' }}"


def _cross_wire_userns_restore(workflow: dict[str, Any]) -> None:
    step = _step(_maven_job(workflow), "restore-rootless-userns")
    step["env"]["ORIGINAL_USERNS"] = (
        "${{ steps.enable-rootless-userns.outputs.unconfined }}"
    )


def _move_userns_restore_after_upload(workflow: dict[str, Any]) -> None:
    job = _maven_job(workflow)
    restore = _step(job, "restore-rootless-userns")
    steps = job["steps"]
    steps.remove(restore)
    steps.append(restore)


def _upload_ignores_userns_restore(workflow: dict[str, Any]) -> None:
    upload = _step(_maven_job(workflow), "upload")
    upload["if"] = "${{ always() && steps.execute.outcome == 'success' }}"


def _userns_guard_on_non_maven_producer(workflow: dict[str, Any]) -> None:
    source = _maven_job(workflow)
    target = workflow["jobs"][BUILD]
    execute_index = _steps(target).index(_step(target, "execute"))
    target["steps"][execute_index:execute_index] = [
        copy.deepcopy(_step(source, "enable-rootless-userns"))
    ]
    target["steps"].insert(
        execute_index + 2, copy.deepcopy(_step(source, "restore-rootless-userns"))
    )


def _name_download(workflow: dict[str, Any]) -> None:
    settings = _step(workflow["jobs"]["aggregate"], "download-build")["with"]
    settings["name"] = settings.pop("artifact-ids")


def _pattern_download(workflow: dict[str, Any]) -> None:
    settings = _step(workflow["jobs"]["aggregate"], "download-build")["with"]
    settings["pattern"] = "phase8-*"
    settings.pop("artifact-ids")


def _all_download(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"]["aggregate"], "download-build")["with"].pop("artifact-ids")


def _artifact_id_cross_wire(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"]["aggregate"], "download-build")["with"]["artifact-ids"] = (
        f"${{{{ needs.{OBSERVE}.outputs.artifact-id }}}}"
    )


def _weak_digest_mismatch(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"]["aggregate"], "download-build")["with"][
        "digest-mismatch"
    ] = "warn"


def _merge_false(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"]["aggregate"], "download-build")["with"]["merge-multiple"] = (
        False
    )


def _wrong_artifact_name(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"][COMMAND_JOBS[0]], "upload")["with"]["name"] += "-mutable"


def _short_retention(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"][BUILD], "upload")["with"]["retention-days"] = 30


def _replaceable_artifact(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"][BUILD], "upload")["with"]["overwrite"] = True


def _expanded_permission(workflow: dict[str, Any]) -> None:
    workflow["jobs"][BUILD]["permissions"]["id-token"] = "write"


def _candidate_checkout_in_attest(workflow: dict[str, Any]) -> None:
    workflow["jobs"]["attest"]["steps"].insert(
        0,
        {
            "id": "candidate-checkout",
            "uses": CHECKOUT,
            "with": {"ref": "${{ github.sha }}"},
        },
    )


def _trusted_checkout_in_attest(workflow: dict[str, Any]) -> None:
    workflow["jobs"]["attest"]["steps"].insert(
        0,
        {"id": "trusted-code-checkout", "uses": CHECKOUT, "with": {"ref": C0}},
    )


def _gate_omits_status(workflow: dict[str, Any]) -> None:
    env = _step(workflow["jobs"]["gate"], "enforce")["env"]
    key = next(
        key
        for key, value in env.items()
        if value == f"${{{{ needs.{BUILD}.outputs.artifact-status }}}}"
    )
    del env[key]


def _aggregate_indirect(workflow: dict[str, Any]) -> None:
    workflow["jobs"]["aggregate"]["needs"] = COMMAND_JOBS[-1]


def _aggregate_not_always(workflow: dict[str, Any]) -> None:
    workflow["jobs"]["aggregate"].pop("if")


def _aggregate_independent_trusted_checkout(workflow: dict[str, Any]) -> None:
    workflow["jobs"]["aggregate"]["steps"] = [
        (
            {
                "id": "trusted-code-checkout",
                "uses": CHECKOUT,
                "with": {
                    "ref": C0,
                    "path": "trusted-code",
                    "fetch-depth": 0,
                    "persist-credentials": False,
                },
            }
            if step.get("id") == "trusted-code-checkout"
            else step
        )
        for step in _steps(workflow["jobs"]["aggregate"])
    ]


def _extra_artifact(workflow: dict[str, Any]) -> None:
    extra = copy.deepcopy(_step(workflow["jobs"][BUILD], "upload"))
    extra["id"] = "extra-upload"
    workflow["jobs"][BUILD]["steps"].append(extra)


def _branch_action(workflow: dict[str, Any]) -> None:
    _step(workflow["jobs"][BUILD], "candidate-checkout")["uses"] = (
        "actions/checkout@main"
    )


@pytest.mark.parametrize(
    "mutation",
    [
        _omit_job,
        _extra_job,
        _parallel_command,
        _reordered_command,
        _wrong_c0,
        _dynamic_ref,
        _wrong_module,
        _missing_no_bytecode,
        _runner_temp_producer_output,
        _omit_observer_docker_archive,
        _cross_wire_observer_docker_archive,
        _substitute_docker_with_oci,
        _wrong_archive_root,
        _weaken_unique_tar_cardinality,
        _maven_runtime_archive,
        _omit_userns_enable,
        _late_userns_capture,
        _unsafe_userns_enable_order,
        _interactive_userns_sudo,
        _restore_requires_enable_success,
        _cross_wire_userns_restore,
        _move_userns_restore_after_upload,
        _upload_ignores_userns_restore,
        _userns_guard_on_non_maven_producer,
        _name_download,
        _pattern_download,
        _all_download,
        _artifact_id_cross_wire,
        _weak_digest_mismatch,
        _merge_false,
        _wrong_artifact_name,
        _short_retention,
        _replaceable_artifact,
        _expanded_permission,
        _candidate_checkout_in_attest,
        _trusted_checkout_in_attest,
        _gate_omits_status,
        _aggregate_indirect,
        _aggregate_not_always,
        _aggregate_independent_trusted_checkout,
        _extra_artifact,
        _branch_action,
    ],
)
def test_security_mutations_fail_closed(mutation: Mutation) -> None:
    workflow = copy.deepcopy(_load_workflow())
    mutation(workflow)
    with pytest.raises(ValueError):
        _validate_workflow(workflow)
