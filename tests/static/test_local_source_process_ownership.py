from __future__ import annotations

import ctypes
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import time
from ctypes import wintypes
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / ".local-dev" / "launch-source.ps1"
JAVA_API_RESTART = ROOT / ".local-dev" / "restart-java-api-current-activation.ps1"
JAVA_CONTROL_RESTART = (
    ROOT / ".local-dev" / "restart-java-control-worker-current-activation.ps1"
)
JAVA_AGENT_RESTART = (
    ROOT / ".local-dev" / "restart-java-agent-worker-current-activation.ps1"
)
SAFE_ENTRYPOINT = "Stop-OwnedSourceProcess"
PUBLICATION_ENTRYPOINT = "Publish-SourceProcessOwnershipOrCompensate"
LAUNCH_TOMBSTONE_ENTRYPOINT = "Initialize-SourceProcessOwnershipLaunchTombstone"
ROOT_INSTANCE_ENTRYPOINT = "Bind-SourceProcessOwnershipRootInstance"
CLEANUP_DRAIN_ENTRYPOINT = "Wait-SourceProcessOwnershipCleanupRegistryEmpty"
OWNERSHIP_SCHEMA = "local-source-process-ownership.v1"
LAUNCH_TOMBSTONE_SCHEMA = "local-source-process-launch-tombstone.v1"
ROOT_INSTANCE_SCHEMA = "local-source-process-root-instance.v2"
OWNERSHIP_FIELDS = {
    "schema_version",
    "name",
    "process_kind",
    "pid",
    "parent_pid",
    "creation_date",
    "executable_path",
    "command_line",
    "working_directory",
    "project_root",
}
REQUIRED_PARAMETERS = {
    "Name",
    "OwnershipRecordDirectory",
    "ProjectRoot",
    "SnapshotProvider",
    "TreeTerminator",
    "OwnershipRecordRemover",
    "ProtectedProcessPolicy",
}
PUBLICATION_REQUIRED_PARAMETERS = {
    "Name",
    "ProcessKind",
    "CapturedProcess",
    "WorkingDirectory",
    "ProjectRoot",
    "OwnershipRecordDirectory",
    "OwnershipWriter",
    "UnpublishedProcessTerminator",
    "UnpublishedProcessCleanupRegistry",
}
LAUNCH_TOMBSTONE_REQUIRED_PARAMETERS = {
    "Name",
    "ProcessKind",
    "CapturedProcess",
    "ProcessId",
    "ExpectedExecutablePath",
    "ExpectedCommandLine",
    "WorkingDirectory",
    "ProjectRoot",
    "LaunchTimestamp",
    "LauncherProcessId",
    "LauncherCreationDate",
    "LauncherExecutablePath",
}
LAUNCH_TOMBSTONE_FIELDS = {
    "schema_version",
    "name",
    "process_kind",
    "pid",
    "expected_executable_path",
    "expected_command_line",
    "working_directory",
    "project_root",
    "launched_at",
    "launcher_pid",
    "launcher_creation_date",
    "launcher_executable_path",
}
ROOT_INSTANCE_REQUIRED_PARAMETERS = {
    "CapturedProcess",
    "RootInstanceProvider",
}
ROOT_INSTANCE_FIELDS = {
    "schema_version",
    "pid",
    "creation_date",
    "exit_date",
    "executable_path",
    "is_alive_at_bind",
    "handle_reference",
}
CLEANUP_DRAIN_REQUIRED_PARAMETERS = {
    "Registry",
    "RetryAction",
    "PauseAction",
}
FORBIDDEN_DIRECT_PRIMITIVES = {
    "taskkill": re.compile(r"(?i)\btaskkill(?:\.exe)?\b"),
    "Stop-Process": re.compile(r"(?i)\bStop-Process\b"),
    "CIM termination": re.compile(
        r"(?is)\bInvoke-CimMethod\b.*?\b(?:Terminate|Delete)\b"
    ),
    "WMI termination": re.compile(r"(?i)\.Terminate\s*\("),
    "Remove-Item": re.compile(r"(?i)\bRemove-Item\b"),
    "direct file deletion": re.compile(r"(?i)\[System\.IO\.File\]::Delete\s*\("),
}


def _matching_brace(text: str, opening: int) -> int:
    depth = 0
    quote: str | None = None
    line_comment = False
    index = opening
    while index < len(text):
        char = text[index]
        if line_comment:
            if char in "\r\n":
                line_comment = False
            index += 1
            continue
        if quote == "'":
            if char == "'" and index + 1 < len(text) and text[index + 1] == "'":
                index += 2
                continue
            if char == "'":
                quote = None
            index += 1
            continue
        if quote == '"':
            if char == "`":
                index += 2
                continue
            if char == '"':
                quote = None
            index += 1
            continue
        if char == "#":
            line_comment = True
        elif char in {"'", '"'}:
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise AssertionError("unterminated PowerShell function definition")


def _function_definitions(text: str) -> list[tuple[str, str]]:
    definitions: list[tuple[str, str]] = []
    declaration = re.compile(
        r"(?im)^[ \t]*function[ \t]+([A-Za-z0-9_-]+)[ \t]*\{"
    )
    for match in declaration.finditer(text):
        opening = text.find("{", match.start(), match.end())
        closing = _matching_brace(text, opening)
        definitions.append((match.group(1), text[match.start() : closing + 1]))
    return definitions


def _ownership_function_bundle() -> str | None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    names = {name for name, _ in definitions}
    if SAFE_ENTRYPOINT not in names:
        return None
    ownership_names = re.compile(
        r"(?i)(?:OwnedSourceProcess|SourceProcessOwnership|OwnedProcessTree|OwnershipRecord)"
    )
    return "\n\n".join(
        definition
        for name, definition in definitions
        if name == SAFE_ENTRYPOINT or ownership_names.search(name)
    )


def _contract_bundle_or_outcome(*, decisive: bool) -> str:
    bundle = _ownership_function_bundle()
    if bundle is None:
        message = (
            f"{SAFE_ENTRYPOINT} is absent; the launcher has no injectable, "
            "identity-bound ownership-record termination seam"
        )
        if decisive:
            pytest.fail(message)
        pytest.skip(f"blocked by decisive old-red: {message}")

    entrypoint = next(
        definition
        for name, definition in _function_definitions(bundle)
        if name == SAFE_ENTRYPOINT
    )
    missing_parameters = sorted(
        name
        for name in REQUIRED_PARAMETERS
        if re.search(rf"(?i)\${re.escape(name)}\b", entrypoint) is None
    )
    unsafe = sorted(
        name
        for name, pattern in FORBIDDEN_DIRECT_PRIMITIVES.items()
        if pattern.search(bundle)
    )
    contract_errors: list[str] = []
    if missing_parameters:
        contract_errors.append(f"missing injectable parameters {missing_parameters}")
    if unsafe:
        contract_errors.append(f"contains direct destructive primitives {unsafe}")
    if contract_errors:
        message = f"{SAFE_ENTRYPOINT} contract is unsafe: " + "; ".join(contract_errors)
        if decisive:
            pytest.fail(message)
        pytest.skip(f"blocked by decisive old-red: {message}")
    return bundle


def _publication_contract_bundle_or_fail() -> str:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    publication = next(
        (
            definition
            for name, definition in definitions
            if name == PUBLICATION_ENTRYPOINT
        ),
        None,
    )
    if publication is None:
        pytest.fail(
            f"{PUBLICATION_ENTRYPOINT} is absent; a started source process can be "
            "orphaned when ownership-record publication fails"
        )
    missing_parameters = sorted(
        name
        for name in PUBLICATION_REQUIRED_PARAMETERS
        if re.search(rf"(?i)\${re.escape(name)}\b", publication) is None
    )
    assert not missing_parameters, (
        f"{PUBLICATION_ENTRYPOINT} is missing injectable lifecycle parameters "
        f"{missing_parameters}"
    )
    bundle = _ownership_function_bundle()
    assert bundle is not None
    return bundle


def _launch_tombstone_contract_bundle_or_fail() -> str:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    initializer = next(
        (
            definition
            for name, definition in definitions
            if name == LAUNCH_TOMBSTONE_ENTRYPOINT
        ),
        None,
    )
    if initializer is None:
        pytest.fail(
            f"{LAUNCH_TOMBSTONE_ENTRYPOINT} is absent; fast exit can erase "
            "ownership provenance before captured identity is readable"
        )
    missing_parameters = sorted(
        name
        for name in LAUNCH_TOMBSTONE_REQUIRED_PARAMETERS
        if re.search(rf"(?i)\${re.escape(name)}\b", initializer) is None
    )
    assert not missing_parameters, (
        f"{LAUNCH_TOMBSTONE_ENTRYPOINT} is missing immutable launch inputs "
        f"{missing_parameters}"
    )
    bundle = _ownership_function_bundle()
    assert bundle is not None
    return bundle


def _root_instance_contract_bundle_or_fail() -> str:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    binder = next(
        (
            definition
            for name, definition in definitions
            if name == ROOT_INSTANCE_ENTRYPOINT
        ),
        None,
    )
    if binder is None:
        pytest.fail(
            f"{ROOT_INSTANCE_ENTRYPOINT} is absent; a launch tombstone alone "
            "cannot distinguish a true descendant from PID reuse"
        )
    missing_parameters = sorted(
        name
        for name in ROOT_INSTANCE_REQUIRED_PARAMETERS
        if re.search(rf"(?i)\${re.escape(name)}\b", binder) is None
    )
    assert not missing_parameters, (
        f"{ROOT_INSTANCE_ENTRYPOINT} is missing identity-bound inputs "
        f"{missing_parameters}"
    )
    bundle = _ownership_function_bundle()
    assert bundle is not None
    return bundle


def _cleanup_drain_contract_bundle_or_fail() -> str:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    drain = next(
        (
            definition
            for name, definition in definitions
            if name == CLEANUP_DRAIN_ENTRYPOINT
        ),
        None,
    )
    if drain is None:
        pytest.fail(
            f"{CLEANUP_DRAIN_ENTRYPOINT} is absent; outer failure handling can "
            "return or rethrow while unpublished process references remain"
        )
    missing_parameters = sorted(
        name
        for name in CLEANUP_DRAIN_REQUIRED_PARAMETERS
        if re.search(rf"(?i)\${re.escape(name)}\b", drain) is None
    )
    assert not missing_parameters, (
        f"{CLEANUP_DRAIN_ENTRYPOINT} is missing injected retry controls "
        f"{missing_parameters}"
    )
    bundle = _ownership_function_bundle()
    assert bundle is not None
    return bundle


def _command_call_count(text: str, command: str) -> int:
    return len(
        re.findall(
            rf"(?im)^(?![ \t]*function\b)[ \t]*{re.escape(command)}\b",
            text,
        )
    )


JAVA_PRODUCER_SPECS = {
    "java-api": ("API", "1024m", "5005"),
    "java-control-worker": ("CONTROL", "1536m", "5006"),
    "java-agent-worker": ("AGENT", "1024m", "5007"),
}
JAVA_APPLICATION_MAIN = "com.example.dispute.DisputeApplication"


def _java_authoritative_jvm_prefix(name: str) -> tuple[str, ...]:
    _, maximum_heap, debug_port = JAVA_PRODUCER_SPECS[name]
    return (
        "-Xms128m",
        f"-Xmx{maximum_heap}",
        "-XX:+ExitOnOutOfMemoryError",
        "-agentlib:jdwp="
        f"transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{debug_port}",
        "-cp",
    )


def _java_authoritative_argument_tokens(
    working_directory: Path,
    name: str,
    *,
    prefix_tokens: tuple[str, ...] | None = None,
    classpath_entries: tuple[str, ...] | None = None,
    application_tokens: tuple[str, ...] | None = None,
) -> tuple[str, ...]:
    role, _, _ = JAVA_PRODUCER_SPECS[name]
    if prefix_tokens is None:
        prefix_tokens = _java_authoritative_jvm_prefix(name)
    if classpath_entries is None:
        classpath_entries = (
            str(working_directory / "target" / "target-e2e-classes"),
        )
    if application_tokens is None:
        application_tokens = (f"--app.temporal.worker.role={role}",)
    return (
        *prefix_tokens,
        ";".join(classpath_entries),
        JAVA_APPLICATION_MAIN,
        *application_tokens,
    )


def _render_java_argument_replay_command(
    executable: Path,
    argument_tokens: tuple[str, ...],
    *,
    separator: str,
    trailing_whitespace: str = "",
) -> str:
    rendered_tokens = [f'"{executable}"']
    for token in argument_tokens:
        if token.startswith('"') and token.endswith('"'):
            rendered_tokens.append(token)
        elif ";" in token or re.search(r"[ \t]", token):
            rendered_tokens.append(f'"{token}"')
        else:
            rendered_tokens.append(token)
    return separator.join(rendered_tokens) + trailing_whitespace


def _java_authoritative_command(
    executable: Path,
    working_directory: Path,
    name: str,
    *,
    prefix_tokens: tuple[str, ...] | None = None,
    classpath_entries: tuple[str, ...] | None = None,
    application_tokens: tuple[str, ...] | None = None,
    separator: str = " ",
    trailing_whitespace: str = "",
) -> str:
    return _render_java_argument_replay_command(
        executable,
        _java_authoritative_argument_tokens(
            working_directory,
            name,
            prefix_tokens=prefix_tokens,
            classpath_entries=classpath_entries,
            application_tokens=application_tokens,
        ),
        separator=separator,
        trailing_whitespace=trailing_whitespace,
    )


def _ownership_record(
    project_root: Path,
    *,
    name: str = "java-api",
    pid: int = 4100,
    process_kind: str = "JAVA",
) -> dict[str, Any]:
    process_kind = process_kind.upper()
    if process_kind == "JAVA":
        executable = project_root / ".tools" / "jdk" / "bin" / "java.exe"
        working_directory = project_root / "java-api-service"
        command_line = _java_authoritative_command(
            executable,
            working_directory,
            name,
        )
    elif process_kind == "PYTHON":
        executable = project_root / ".tools" / "python" / "python.exe"
        working_directory = project_root / "python-agent-service"
        command_line = (
            f'"{executable}" -m uvicorn --app-dir "{working_directory}" '
            "mtls_adapter:create_app"
        )
    elif process_kind == "FRONTEND":
        executable = project_root / ".tools" / "cmd.exe"
        working_directory = project_root / "frontend"
        command_line = (
            f'"{executable}" /d /c pnpm --dir "{working_directory}" dev'
        )
    else:
        raise AssertionError(f"unsupported fixture process kind: {process_kind}")
    record = {
        "schema_version": OWNERSHIP_SCHEMA,
        "name": name,
        "process_kind": process_kind,
        "pid": pid,
        "parent_pid": 1000,
        "creation_date": "2026-08-06T00:00:00.0000000Z",
        "executable_path": str(executable),
        "command_line": command_line,
        "working_directory": str(working_directory),
        "project_root": str(project_root),
    }
    assert set(record) == OWNERSHIP_FIELDS
    return record


def _snapshot_process(
    record: dict[str, Any],
    *,
    pid: int | None = None,
    parent_pid: int | None = None,
    creation_date: str | None = None,
    executable_path: str | None = None,
    command_line: str | None = None,
    working_directory: str | None = None,
) -> dict[str, Any]:
    return {
        "ProcessId": record["pid"] if pid is None else pid,
        "ParentProcessId": record["parent_pid"] if parent_pid is None else parent_pid,
        "CreationDate": record["creation_date"] if creation_date is None else creation_date,
        "ExecutablePath": (
            record["executable_path"] if executable_path is None else executable_path
        ),
        "CommandLine": record["command_line"] if command_line is None else command_line,
        "WorkingDirectory": (
            record["working_directory"]
            if working_directory is None
            else working_directory
        ),
    }


def _write_record(record_directory: Path, name: str, raw: bytes) -> Path:
    record_directory.mkdir(parents=True, exist_ok=True)
    record_path = record_directory / f"{name}.ownership.json"
    record_path.write_bytes(raw)
    return record_path


def _run_sandboxed_powershell(
    tmp_path: Path,
    *,
    bundle: str,
    name: str,
    project_root: Path,
    record_directory: Path,
    snapshots: list[list[dict[str, Any]]],
    terminator_mode: str = "success",
    snapshot_error_calls: list[int] | None = None,
    protected_pids: list[int] | None = None,
    protected_executables: list[str] | None = None,
    protected_command_fragments: list[str] | None = None,
    invocation_count: int = 1,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "ownership-functions-only.ps1"
    case_file = tmp_path / "case.json"
    result_file = tmp_path / "result.json"
    harness_file = tmp_path / "invoke-ownership-contract.ps1"
    function_file.write_text(bundle, encoding="utf-8")
    case_file.write_text(
        json.dumps(
            {
                "name": name,
                "project_root": str(project_root),
                "record_directory": str(record_directory),
                "snapshots": snapshots,
                "terminator_mode": terminator_mode,
                "snapshot_error_calls": snapshot_error_calls or [],
                "protected_pids": protected_pids or [],
                "protected_executables": protected_executables or [],
                "protected_command_fragments": protected_command_fragments or [],
                "invocation_count": invocation_count,
            }
        ),
        encoding="utf-8",
    )
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$CaseFile,
    [Parameter(Mandatory = $true)][string]$ResultFile
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Only a file containing extracted ownership function definitions is loaded.
. $FunctionFile
$case = Get-Content -LiteralPath $CaseFile -Raw | ConvertFrom-Json
$recordPath = Join-Path $case.record_directory "$($case.name).ownership.json"
$script:snapshotCallCount = 0
$script:terminationCalls = [System.Collections.Generic.List[object]]::new()
$script:removalCalls = [System.Collections.Generic.List[string]]::new()

# All process discovery, protection, termination, and record deletion are fakes.
$snapshotProvider = {
    $callNumber = $script:snapshotCallCount + 1
    $script:snapshotCallCount = $callNumber
    if (@($case.snapshot_error_calls) -contains $callNumber) {
        throw "synthetic snapshot failure"
    }
    $index = [Math]::Min($callNumber - 1, $case.snapshots.Count - 1)
    return @($case.snapshots[$index])
}
$protectedProcessPolicy = {
    param($Process)
    if (@($case.protected_pids) -contains [int]$Process.Pid) {
        return $true
    }
    foreach ($executable in @($case.protected_executables)) {
        if ([string]::Equals(
                [string]$Process.ExecutablePath,
                [string]$executable,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    foreach ($fragment in @($case.protected_command_fragments)) {
        if ([string]$Process.CommandLine -like "*$fragment*") {
            return $true
        }
    }
    return $false
}
$treeTerminator = {
    param($Plan)
    if ($args.Count -ne 0) {
        throw "TreeTerminator must receive one identity-bound plan."
    }
    $script:terminationCalls.Add($Plan)
    if ($case.terminator_mode -eq "throw") {
        throw "synthetic terminator failure"
    }
}
$ownershipRecordRemover = {
    param([string]$Path)
    $actual = [System.IO.Path]::GetFullPath($Path)
    $expected = [System.IO.Path]::GetFullPath($recordPath)
    if (-not [string]::Equals($actual, $expected, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "record remover escaped its sandbox"
    }
    $script:removalCalls.Add($actual)
    [System.IO.File]::Delete($actual)
}

# These names shadow every destructive/discovery primitive in the child scope.
function taskkill.exe { throw "direct taskkill is forbidden by the test harness" }
function Stop-Process { throw "direct Stop-Process is forbidden by the test harness" }
function Get-Process { throw "direct Get-Process is forbidden by the test harness" }
function Get-CimInstance { throw "direct CIM discovery is forbidden by the test harness" }
function Invoke-CimMethod { throw "direct CIM termination is forbidden by the test harness" }
function Remove-Item { throw "direct Remove-Item is forbidden by the test harness" }

$functionResults = @()
$invocationErrors = @()
for ($invocation = 0; $invocation -lt [int]$case.invocation_count; $invocation += 1) {
    $currentResult = $null
    $currentError = $null
    try {
        $currentResult = Stop-OwnedSourceProcess `
            -Name $case.name `
            -OwnershipRecordDirectory $case.record_directory `
            -ProjectRoot $case.project_root `
            -SnapshotProvider $snapshotProvider `
            -TreeTerminator $treeTerminator `
            -OwnershipRecordRemover $ownershipRecordRemover `
            -ProtectedProcessPolicy $protectedProcessPolicy
    }
    catch {
        $currentError = [pscustomobject]@{
            type = $_.Exception.GetType().FullName
            message = $_.Exception.Message
        }
    }
    $functionResults += ,$currentResult
    $invocationErrors += ,$currentError
}
$functionResult = $functionResults[$functionResults.Count - 1]
$invocationError = $invocationErrors[$invocationErrors.Count - 1]

$output = [pscustomobject]@{
    function_result = $functionResult
    invocation_error = $invocationError
    function_results = @($functionResults)
    invocation_errors = @($invocationErrors)
    snapshot_calls = $script:snapshotCallCount
    termination_calls = @($script:terminationCalls)
    removal_calls = @($script:removalCalls)
    record_exists = [System.IO.File]::Exists($recordPath)
    record_content = if ([System.IO.File]::Exists($recordPath)) {
        [System.IO.File]::ReadAllText($recordPath)
    } else {
        $null
    }
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 100 -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-CaseFile",
            str(case_file),
            "-ResultFile",
            str(result_file),
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_publication_failure_harness(
    tmp_path: Path,
    *,
    bundle: str,
    writer_mode: str = "throw",
    terminator_mode: str = "success",
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    project_root = tmp_path / "candidate"
    working_directory = project_root / "java-api-service"
    record_directory = tmp_path / "ownership"
    record_directory.mkdir(parents=True)
    function_file = tmp_path / "ownership-functions-only.ps1"
    result_file = tmp_path / "publication-result.json"
    harness_file = tmp_path / "invoke-publication-contract.ps1"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory,
    [Parameter(Mandatory = $true)][string]$RecordDirectory,
    [Parameter(Mandatory = $true)][string]$WriterMode,
    [Parameter(Mandatory = $true)][string]$TerminatorMode
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$capturedExecutable = Join-Path $ProjectRoot ".tools\jdk\bin\java.exe"
$capturedTargetClasses = Join-Path $WorkingDirectory "target\target-e2e-classes"
$capturedCommandLine =
    '"' + $capturedExecutable + '" ' +
    '-Xms128m -Xmx1024m -XX:+ExitOnOutOfMemoryError ' +
    '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005 ' +
    '-cp "' + $capturedTargetClasses + '" ' +
    'com.example.dispute.DisputeApplication --app.temporal.worker.role=API'
$capturedProcess = [pscustomobject]@{
    Id = 5100
    CreationDate = "2026-08-06T00:00:00.0000000Z"
    ExecutablePath = $capturedExecutable
    CommandLine = $capturedCommandLine
    HasExited = $false
}
$writerCalls = [System.Collections.Generic.List[object]]::new()
$terminatorCalls = [System.Collections.Generic.List[object]]::new()
$cleanupRegistry = [System.Collections.Generic.List[object]]::new()
$sameCapturedReferenceState = [pscustomobject]@{ Value = $false }

$ownershipWriter = {
    param(
        [string]$Name,
        [string]$ProcessKind,
        $CapturedProcess,
        [string]$WorkingDirectory,
        [string]$ProjectRoot,
        [string]$OwnershipRecordDirectory
    )
    $writerCalls.Add([pscustomobject]@{
        name = $Name
        process_kind = $ProcessKind
        same_reference = [object]::ReferenceEquals($script:capturedProcess, $CapturedProcess)
    })
    if ($WriterMode -eq "throw") {
        throw "RAW_WRITER_DETAIL_MUST_NOT_ESCAPE"
    }
    return $true
}.GetNewClosure()
$unpublishedProcessTerminator = {
    param($CapturedProcess)
    $sameCapturedReferenceState.Value =
        [object]::ReferenceEquals($capturedProcess, $CapturedProcess)
    $terminatorCalls.Add([pscustomobject]@{
        pid = $CapturedProcess.Id
        creation_date = $CapturedProcess.CreationDate
        executable_path = $CapturedProcess.ExecutablePath
        command_line = $CapturedProcess.CommandLine
    })
    switch ($TerminatorMode) {
        "throw" { throw "RAW_TERMINATOR_DETAIL_MUST_NOT_ESCAPE" }
        "false" { return $false }
        "alive" { return $true }
        "success" {
            $CapturedProcess.HasExited = $true
            return $true
        }
        default { throw "invalid fake terminator mode" }
    }
}.GetNewClosure()

function Start-Process { throw "Start-Process is forbidden by the publication harness" }
function taskkill.exe { throw "direct taskkill is forbidden by the publication harness" }
function Stop-Process { throw "direct Stop-Process is forbidden by the publication harness" }
function Get-Process { throw "direct Get-Process is forbidden by the publication harness" }
function Get-CimInstance { throw "direct CIM discovery is forbidden by the publication harness" }
function Invoke-CimMethod { throw "direct CIM termination is forbidden by the publication harness" }
function Remove-Item { throw "direct Remove-Item is forbidden by the publication harness" }

$publicationResult = $null
$invocationError = $null
try {
    $publicationResult = Publish-SourceProcessOwnershipOrCompensate `
        -Name "java-api" `
        -ProcessKind "JAVA" `
        -CapturedProcess $capturedProcess `
        -WorkingDirectory $WorkingDirectory `
        -ProjectRoot $ProjectRoot `
        -OwnershipRecordDirectory $RecordDirectory `
        -OwnershipWriter $ownershipWriter `
        -UnpublishedProcessTerminator $unpublishedProcessTerminator `
        -UnpublishedProcessCleanupRegistry $cleanupRegistry
}
catch {
    $invocationError = [pscustomobject]@{
        type = $_.Exception.GetType().FullName
        message = $_.Exception.Message
    }
}

$output = [pscustomobject]@{
    publication_result = $publicationResult
    invocation_error = $invocationError
    writer_calls = @($writerCalls)
    terminator_calls = @($terminatorCalls)
    same_captured_reference = $sameCapturedReferenceState.Value
    cleanup_registry_count = $cleanupRegistry.Count
    cleanup_registry_same_reference =
        $cleanupRegistry.Count -eq 1 -and
        [object]::ReferenceEquals($cleanupRegistry[0], $capturedProcess)
    record_files = @(
        [System.IO.Directory]::GetFiles($RecordDirectory) |
            ForEach-Object { [System.IO.Path]::GetFileName($_) }
    )
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 100 -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(project_root),
            "-WorkingDirectory",
            str(working_directory),
            "-RecordDirectory",
            str(record_directory),
            "-WriterMode",
            writer_mode,
            "-TerminatorMode",
            terminator_mode,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_closed_stage_diagnostics_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "ownership-functions-only.ps1"
    harness_file = tmp_path / "invoke-closed-stage-diagnostics.ps1"
    result_file = tmp_path / "closed-stage-diagnostics-result.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$warningMessages = [System.Collections.Generic.List[string]]::new()
function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warningMessages.Add($Message)
}

$capturedProcess = [pscustomobject]@{
    Id = 8811
    CreationDate = "2026-08-06T00:00:00.0000000Z"
    ExecutablePath = "C:\sensitive\runtime.exe"
    CommandLine = "RAW_SENSITIVE_COMMAND_MUST_NOT_ESCAPE"
}
$result = $null
$invocationError = $null

function Add-Member {
    param(
        [Parameter(Mandatory = $true)]$InputObject,
        [Parameter(Mandatory = $true)][string]$NotePropertyName,
        [Parameter(Mandatory = $true)]$NotePropertyValue,
        [switch]$Force
    )
    if ($Scenario -eq "publish-ATTACH_CAPTURED_IDENTITY" -or
        $Scenario -eq "comp-MARK_CONFIRMED") {
        throw "RAW_SENSITIVE_ADD_MEMBER_MUST_NOT_ESCAPE"
    }
    Microsoft.PowerShell.Utility\Add-Member `
        -InputObject $InputObject `
        -NotePropertyName $NotePropertyName `
        -NotePropertyValue $NotePropertyValue `
        -Force:$Force
}

if ($Scenario.StartsWith("publish-")) {
    function Get-SourceProcessOwnershipCapturedIdentity {
        param(
            $CapturedProcess,
            [string]$Name,
            [string]$ProcessKind,
            [string]$WorkingDirectory,
            [string]$ProjectRoot
        )
        if ($Scenario -eq "publish-CAPTURE_HANDLE_IDENTITY") {
            throw "RAW_SENSITIVE_CAPTURE_MUST_NOT_ESCAPE"
        }
        return [pscustomobject]@{
            Pid = $CapturedProcess.Id
            CreationDate = $CapturedProcess.CreationDate
            ExecutablePath = $CapturedProcess.ExecutablePath
        }
    }
    $ownershipWriter = {
        param(
            [string]$Name,
            [string]$ProcessKind,
            $CapturedProcess,
            [string]$WorkingDirectory,
            [string]$ProjectRoot,
            [string]$OwnershipRecordDirectory
        )
        if ($Scenario -eq "publish-ATOMIC_PUBLISH") {
            throw "RAW_SENSITIVE_WRITER_MUST_NOT_ESCAPE"
        }
        return $true
    }.GetNewClosure()
    $terminator = { param($Process) return $false }
    $cleanupRegistry = [System.Collections.Generic.List[object]]::new()
    try {
        $result = Publish-SourceProcessOwnershipOrCompensate `
            -Name "java-api" `
            -ProcessKind "JAVA" `
            -CapturedProcess $capturedProcess `
            -WorkingDirectory "C:\candidate\java-api-service" `
            -ProjectRoot "C:\candidate" `
            -OwnershipRecordDirectory "C:\ownership" `
            -OwnershipWriter $ownershipWriter `
            -UnpublishedProcessTerminator $terminator `
            -UnpublishedProcessCleanupRegistry $cleanupRegistry
    }
    catch {
        $invocationError = $_.Exception.Message
    }
}
else {
    $script:snapshotCall = 0
    $script:snapshotValidationCall = 0
    $script:rootStateCall = 0
    $script:planCall = 0
    $script:SourceProcessProtectedPolicy = { param($Process) return $false }
    $script:SourceProcessSnapshotProvider = {
        $script:snapshotCall += 1
        return [pscustomobject]@{ Sequence = $script:snapshotCall }
    }
    function Test-SourceProcessOwnershipSnapshotValue {
        param([object[]]$Snapshot)
        $script:snapshotValidationCall += 1
        $stage = @("FIRST_SNAPSHOT", "PRE_SNAPSHOT", "POST_SNAPSHOT")[
            $script:snapshotValidationCall - 1
        ]
        return $Scenario -ne ("comp-" + $stage)
    }
    function Update-SourceProcessOwnershipRootInstanceExitDate {
        param($CapturedProcess)
        $script:rootStateCall += 1
        $stage = @("FIRST_ROOT_STATE", "PRE_ROOT_STATE", "POST_ROOT_STATE")[
            $script:rootStateCall - 1
        ]
        return $Scenario -ne ("comp-" + $stage)
    }
    function New-SourceProcessOwnershipUnpublishedPlan {
        param($CapturedProcess, [object[]]$Snapshot, [scriptblock]$ProtectedProcessPolicy)
        $script:planCall += 1
        $stage = @("FIRST_PLAN", "PRE_PLAN")[$script:planCall - 1]
        if ($Scenario -eq ("comp-" + $stage)) {
            return [pscustomobject]@{ Code = "INJECTED_FALSE" }
        }
        $identity = [pscustomobject]@{
            Pid = $CapturedProcess.Id
            Depth = 0
        }
        return [pscustomobject]@{
            Root = $identity
            Processes = @($identity)
        }
    }
    function Invoke-SourceProcessOwnershipUnpublishedPlanTermination {
        param($CapturedProcess, $Plan)
        if ($Scenario -eq "comp-BIND_AND_KILL") {
            throw "RAW_SENSITIVE_BIND_MUST_NOT_ESCAPE"
        }
    }
    function Test-OwnedProcessTreeIdentityAlive {
        param($Identity, [object[]]$Snapshot)
        return $Scenario -eq "comp-POST_IDENTITY"
    }
    function Test-OwnedProcessTreeResidualOwnership {
        param($Plan, [object[]]$PreTerminationSnapshot, [object[]]$Snapshot)
        return $Scenario -eq "comp-POST_RESIDUAL"
    }
    try {
        $result = Invoke-SourceProcessOwnershipUnpublishedTermination `
            -CapturedProcess $capturedProcess
    }
    catch {
        $invocationError = $_.Exception.Message
    }
}

$output = [pscustomobject]@{
    result = $result
    invocation_error = $invocationError
    warnings = @($warningMessages)
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_cleanup_registry_retry_harness(
    tmp_path: Path,
    *,
    bundle: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "ownership-functions-only.ps1"
    result_file = tmp_path / "cleanup-registry-result.json"
    harness_file = tmp_path / "invoke-cleanup-registry-contract.ps1"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$capturedProcess = [pscustomobject]@{
    Id = 5200
    HasExited = $false
}
$registry = [System.Collections.Generic.List[object]]::new()
$registry.Add($capturedProcess)
$sameReferenceChecks = [System.Collections.Generic.List[bool]]::new()

$throwingTerminator = {
    param($Process)
    $sameReferenceChecks.Add([object]::ReferenceEquals($capturedProcess, $Process))
    throw "synthetic retry termination failure"
}.GetNewClosure()
$aliveTerminator = {
    param($Process)
    $sameReferenceChecks.Add([object]::ReferenceEquals($capturedProcess, $Process))
    return $true
}.GetNewClosure()
$successfulTerminator = {
    param($Process)
    $sameReferenceChecks.Add([object]::ReferenceEquals($capturedProcess, $Process))
    $Process.HasExited = $true
    return $true
}.GetNewClosure()

function Start-Process { throw "Start-Process is forbidden by the registry harness" }
function taskkill.exe { throw "direct taskkill is forbidden by the registry harness" }
function Stop-Process { throw "direct Stop-Process is forbidden by the registry harness" }
function Get-Process { throw "direct Get-Process is forbidden by the registry harness" }
function Get-CimInstance { throw "direct CIM discovery is forbidden by the registry harness" }
function Invoke-CimMethod { throw "direct CIM termination is forbidden by the registry harness" }
function Remove-Item { throw "direct Remove-Item is forbidden by the registry harness" }

$throwResult = Invoke-SourceProcessOwnershipCleanupRegistryRetry `
    -Registry $registry `
    -Terminator $throwingTerminator
$afterThrowCount = $registry.Count
$afterThrowSameReference =
    $registry.Count -eq 1 -and
    [object]::ReferenceEquals($registry[0], $capturedProcess)
$aliveResult = Invoke-SourceProcessOwnershipCleanupRegistryRetry `
    -Registry $registry `
    -Terminator $aliveTerminator
$afterAliveCount = $registry.Count
$afterAliveSameReference =
    $registry.Count -eq 1 -and
    [object]::ReferenceEquals($registry[0], $capturedProcess)
$successResult = Invoke-SourceProcessOwnershipCleanupRegistryRetry `
    -Registry $registry `
    -Terminator $successfulTerminator

$output = [pscustomobject]@{
    throw_result = $throwResult
    after_throw_count = $afterThrowCount
    after_throw_same_reference = $afterThrowSameReference
    alive_result = $aliveResult
    after_alive_count = $afterAliveCount
    after_alive_same_reference = $afterAliveSameReference
    success_result = $successResult
    final_count = $registry.Count
    same_reference_checks = @($sameReferenceChecks)
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 100 -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_fast_exit_tombstone_harness(
    tmp_path: Path,
    *,
    bundle: str,
    with_descendants: bool = False,
    bind_root_instance_for_descendants: bool = False,
    descendants_after_exit: bool = False,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    project_root = tmp_path / "candidate"
    working_directory = project_root / "java-api-service"
    executable = project_root / ".tools" / "jdk" / "bin" / "java.exe"
    launcher_executable = project_root / ".tools" / "powershell.exe"
    function_file = tmp_path / "ownership-functions-only.ps1"
    result_file = tmp_path / "fast-exit-tombstone-result.json"
    harness_file = tmp_path / "invoke-fast-exit-tombstone-contract.ps1"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory,
    [Parameter(Mandatory = $true)][string]$ExpectedExecutablePath,
    [Parameter(Mandatory = $true)][string]$LauncherExecutablePath,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$livenessReadState = [pscustomobject]@{ Count = 0 }
$snapshotState = [pscustomobject]@{ Count = 0 }
$planTerminationState = [pscustomobject]@{
    Count = 0
    SameCapturedReference = $false
    RootPid = $null
    ProcessPids = @()
}
$rootInstanceProviderState = [pscustomobject]@{
    Count = 0
    SameCapturedReference = $false
}
$capturedProcess = [pscustomobject]@{
    Id = 5300
    HasExited = $true
}
$throwingStartTime = {
    $livenessReadState.Count += 1
    throw "StartTime is unavailable after fast exit"
}.GetNewClosure()
$throwingMainModule = {
    $livenessReadState.Count += 1
    throw "MainModule is unavailable after fast exit"
}.GetNewClosure()
Add-Member -InputObject $capturedProcess `
    -MemberType ScriptProperty `
    -Name "StartTime" `
    -Value $throwingStartTime
Add-Member -InputObject $capturedProcess `
    -MemberType ScriptProperty `
    -Name "MainModule" `
    -Value $throwingMainModule

$targetClasses = Join-Path $WorkingDirectory "target\target-e2e-classes"
$expectedCommandLine =
    '"' + $ExpectedExecutablePath + '" ' +
    '-Xms128m -Xmx1024m -XX:+ExitOnOutOfMemoryError ' +
    '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005 ' +
    '-cp "' + $targetClasses + ';D:\synthetic-m2\spring-core.jar" ' +
    'com.example.dispute.DisputeApplication ' +
    '--spring.profiles.active=local,target-e2e,api ' +
    '--app.temporal.worker.role=API --app.target-e2e.enabled=true ' +
    '--server.port=8081'
$childCreationDate = if ($Scenario -eq "reused") {
    "2026-08-06T00:00:04.0000000Z"
} else {
    "2026-08-06T00:00:01.0000000Z"
}
$grandchildCreationDate = if ($Scenario -eq "reused") {
    "2026-08-06T00:00:05.0000000Z"
} else {
    "2026-08-06T00:00:02.0000000Z"
}
$child = [pscustomobject]@{
    ProcessId = 5401
    ParentProcessId = 5300
    CreationDate = $childCreationDate
    ExecutablePath = $ExpectedExecutablePath
    CommandLine = $expectedCommandLine + " --owned-child"
    WorkingDirectory = $WorkingDirectory
}
$grandchild = [pscustomobject]@{
    ProcessId = 5402
    ParentProcessId = 5401
    CreationDate = $grandchildCreationDate
    ExecutablePath = $ExpectedExecutablePath
    CommandLine = $expectedCommandLine + " --owned-grandchild"
    WorkingDirectory = $WorkingDirectory
}
$script:SourceProcessSnapshotProvider = {
    $snapshotState.Count += 1
    if ($Scenario -in @("descendants", "authorized", "reused") -and
        $snapshotState.Count -le 2) {
        return @($child, $grandchild)
    }
    return @()
}.GetNewClosure()
$script:SourceProcessProtectedPolicy = {
    param($Process)
    return $false
}

function Start-Process { throw "Start-Process is forbidden by the tombstone harness" }
function taskkill.exe { throw "direct taskkill is forbidden by the tombstone harness" }
function Stop-Process { throw "direct Stop-Process is forbidden by the tombstone harness" }
function Get-Process { throw "direct Get-Process is forbidden by the tombstone harness" }
function Get-CimInstance { throw "direct CIM discovery is forbidden by the tombstone harness" }
function Invoke-CimMethod { throw "direct CIM termination is forbidden by the tombstone harness" }
function Remove-Item { throw "direct Remove-Item is forbidden by the tombstone harness" }
function Invoke-SourceProcessOwnershipUnpublishedPlanTermination {
    param(
        [Parameter(Mandatory = $true)]$CapturedProcess,
        [Parameter(Mandatory = $true)]$Plan
    )
    $script:planTerminationState.Count += 1
    $script:planTerminationState.SameCapturedReference =
        [object]::ReferenceEquals($script:capturedProcess, $CapturedProcess)
    $script:planTerminationState.RootPid = [int]$Plan.Root.Pid
    $script:planTerminationState.ProcessPids = @(
        $Plan.Processes | ForEach-Object { [int]$_.Pid }
    )
}

$tombstone = Initialize-SourceProcessOwnershipLaunchTombstone `
    -Name "java-api" `
    -ProcessKind "JAVA" `
    -CapturedProcess $capturedProcess `
    -ProcessId 5300 `
    -ExpectedExecutablePath $ExpectedExecutablePath `
    -ExpectedCommandLine $expectedCommandLine `
    -WorkingDirectory $WorkingDirectory `
    -ProjectRoot $ProjectRoot `
    -LaunchTimestamp "2026-08-06T00:00:00.0000000Z" `
    -LauncherProcessId 900 `
    -LauncherCreationDate "2026-08-05T23:59:00.0000000Z" `
    -LauncherExecutablePath $LauncherExecutablePath
$rootInstanceAuthority = $null
if ($Scenario -in @("authorized", "reused")) {
    $rootInstanceProvider = {
        param($Process)
        $rootInstanceProviderState.Count += 1
        $rootInstanceProviderState.SameCapturedReference =
            [object]::ReferenceEquals($capturedProcess, $Process)
        return [pscustomobject]@{
            ProcessId = 5300
            CreationDate = "2026-08-06T00:00:00.0000000Z"
            ExitDate = $null
            ExecutablePath = $ExpectedExecutablePath
            IsAlive = $true
            HandleReference = $Process
        }
    }.GetNewClosure()
    $rootHandleStateProvider = {
        param($Process)
        return [pscustomobject]@{
            Pid = 5300
            CreationDate = "2026-08-06T00:00:00.0000000Z"
            ExitDate = $null
            IsExited = $false
        }
    }
    $rootInstanceAuthority = Bind-SourceProcessOwnershipRootInstance `
        -CapturedProcess $capturedProcess `
        -RootInstanceProvider $rootInstanceProvider `
        -HandleStateProvider $rootHandleStateProvider
    $rootInstanceAuthority.exit_date = "2026-08-06T00:00:03.0000000Z"
}
$attachedProperty = $capturedProcess.PSObject.Properties["SourceOwnershipLaunchTombstone"]
$rootAuthorityProperty = $capturedProcess.PSObject.Properties[
    "SourceOwnershipRootInstanceAuthority"
]
$registry = [System.Collections.Generic.List[object]]::new()
$registry.Add($capturedProcess)
$retryResult = Invoke-SourceProcessOwnershipCleanupRegistryRetry `
    -Registry $registry `
    -Terminator ${function:Invoke-SourceProcessOwnershipUnpublishedTermination}
$compensationProperty = $capturedProcess.PSObject.Properties[
    "SourceOwnershipCompensationConfirmed"
]

$output = [pscustomobject]@{
    tombstone = $tombstone
    attached = $null -ne $attachedProperty
    attached_same_reference =
        $null -ne $attachedProperty -and
        [object]::ReferenceEquals($tombstone, $attachedProperty.Value)
    retry_result = $retryResult
    registry_count = $registry.Count
    snapshot_calls = $snapshotState.Count
    liveness_reads = $livenessReadState.Count
    plan_termination_calls = $planTerminationState.Count
    plan_same_captured_reference = $planTerminationState.SameCapturedReference
    plan_root_pid = $planTerminationState.RootPid
    plan_process_pids = @($planTerminationState.ProcessPids)
    root_instance_provider_calls = $rootInstanceProviderState.Count
    root_instance_provider_same_reference =
        $rootInstanceProviderState.SameCapturedReference
    root_authority_attached = $null -ne $rootAuthorityProperty
    root_authority_same_reference =
        $null -ne $rootAuthorityProperty -and
        $null -ne $rootInstanceAuthority -and
        [object]::ReferenceEquals($rootInstanceAuthority, $rootAuthorityProperty.Value)
    root_authority_schema = if ($null -ne $rootInstanceAuthority) {
        [string]$rootInstanceAuthority.schema_version
    } else { $null }
    root_authority_pid = if ($null -ne $rootInstanceAuthority) {
        [int]$rootInstanceAuthority.pid
    } else { $null }
    root_authority_creation_date = if ($null -ne $rootInstanceAuthority) {
        [string]$rootInstanceAuthority.creation_date
    } else { $null }
    root_authority_exit_date = if ($null -ne $rootInstanceAuthority) {
        [string]$rootInstanceAuthority.exit_date
    } else { $null }
    root_authority_handle_reference_matches =
        $null -ne $rootInstanceAuthority -and
        [object]::ReferenceEquals(
            $rootInstanceAuthority.handle_reference,
            $capturedProcess)
    compensation_confirmed =
        $null -ne $compensationProperty -and [bool]$compensationProperty.Value
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 100 -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(project_root),
            "-WorkingDirectory",
            str(working_directory),
            "-ExpectedExecutablePath",
            str(executable),
            "-LauncherExecutablePath",
            str(launcher_executable),
            "-Scenario",
            (
                "reused"
                if descendants_after_exit
                else "authorized"
                if with_descendants and bind_root_instance_for_descendants
                else "descendants"
                if with_descendants
                else "empty"
            ),
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_cleanup_drain_harness(
    tmp_path: Path,
    *,
    bundle: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "ownership-functions-only.ps1"
    result_file = tmp_path / "cleanup-drain-result.json"
    harness_file = tmp_path / "invoke-cleanup-drain-contract.ps1"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$capturedProcess = [pscustomobject]@{
    Id = 5500
    HasExited = $false
}
$registry = [System.Collections.Generic.List[object]]::new()
$registry.Add($capturedProcess)
$retryState = [pscustomobject]@{
    Count = 0
    ReferenceChecks = [System.Collections.Generic.List[bool]]::new()
}
$pauseAttempts = [System.Collections.Generic.List[int]]::new()
$retryAction = {
    param($CurrentRegistry)
    $retryState.Count += 1
    $retryState.ReferenceChecks.Add(
        $CurrentRegistry.Count -eq 1 -and
        [object]::ReferenceEquals($CurrentRegistry[0], $capturedProcess)
    )
    switch ($retryState.Count) {
        1 { throw "RAW_RETRY_DETAIL_MUST_NOT_ESCAPE" }
        2 { return $false }
        3 {
            $capturedProcess.HasExited = $true
            $CurrentRegistry.RemoveAt(0)
            return $true
        }
        default { throw "drain retried after registry was empty" }
    }
}.GetNewClosure()
$pauseAction = {
    param([int]$AttemptNumber)
    $pauseAttempts.Add($AttemptNumber)
}.GetNewClosure()

function Start-Sleep { throw "real sleep is forbidden by the cleanup drain harness" }
function Start-Process { throw "Start-Process is forbidden by the cleanup drain harness" }
function taskkill.exe { throw "direct taskkill is forbidden by the cleanup drain harness" }
function Stop-Process { throw "direct Stop-Process is forbidden by the cleanup drain harness" }
function Get-Process { throw "direct Get-Process is forbidden by the cleanup drain harness" }
function Get-CimInstance { throw "direct CIM discovery is forbidden by the cleanup drain harness" }
function Invoke-CimMethod { throw "direct CIM termination is forbidden by the cleanup drain harness" }
function Remove-Item { throw "direct Remove-Item is forbidden by the cleanup drain harness" }

$drainResult = $null
$invocationError = $null
try {
    $drainResult = Wait-SourceProcessOwnershipCleanupRegistryEmpty `
        -Registry $registry `
        -RetryAction $retryAction `
        -PauseAction $pauseAction
}
catch {
    $invocationError = [pscustomobject]@{
        type = $_.Exception.GetType().FullName
        message = $_.Exception.Message
    }
}

$output = [pscustomobject]@{
    drain_result = $drainResult
    invocation_error = $invocationError
    retry_calls = $retryState.Count
    reference_checks = @($retryState.ReferenceChecks)
    pause_attempts = @($pauseAttempts)
    registry_count = $registry.Count
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 100 -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _result_code(result: dict[str, Any]) -> str:
    assert result["invocation_error"] is None, result["invocation_error"]
    function_result = result["function_result"]
    assert isinstance(function_result, dict), function_result
    for key, value in function_result.items():
        if key.lower() == "code":
            return str(value)
    raise AssertionError(f"ownership result has no fixed Code: {function_result!r}")


def _run_live_unpublished_root_plan_harness(
    tmp_path: Path,
    *,
    bundle: str,
    record: dict[str, Any],
    captured_name: str,
    snapshot: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "ownership-functions-only.ps1"
    case_file = tmp_path / "live-unpublished-root-case.json"
    result_file = tmp_path / "live-unpublished-root-result.json"
    harness_file = tmp_path / "invoke-live-unpublished-root-plan.ps1"
    function_file.write_text(bundle, encoding="utf-8")
    snapshot = snapshot or [
        {
            "ProcessId": record["pid"],
            "ParentProcessId": record["parent_pid"],
            "CreationDate": record["creation_date"],
            "ExecutablePath": record["executable_path"],
            "CommandLine": record["command_line"],
            "WorkingDirectory": record["working_directory"],
        }
    ]
    case_file.write_text(
        json.dumps(
            {
                "record": record,
                "captured_name": captured_name,
                "snapshot": snapshot,
            }
        ),
        encoding="utf-8",
    )
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$CaseFile,
    [Parameter(Mandatory = $true)][string]$ResultFile
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile
$case = Get-Content -LiteralPath $CaseFile -Raw | ConvertFrom-Json
$record = $case.record
$capturedProcess = [pscustomobject]@{
    Id = [int]$record.pid
    HasExited = $false
}
$tombstone = [pscustomobject][ordered]@{
    schema_version = "local-source-process-launch-tombstone.v1"
    name = [string]$record.name
    process_kind = [string]$record.process_kind
    pid = [int]$record.pid
    expected_executable_path = [string]$record.executable_path
    expected_command_line = ([string]$record.command_line).TrimEnd()
    working_directory = [string]$record.working_directory
    project_root = [string]$record.project_root
    launched_at = [string]$record.creation_date
    launcher_pid = [int]$record.parent_pid
    launcher_creation_date = "2026-08-05T23:59:00.0000000Z"
    launcher_executable_path = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
}
$rootAuthority = [pscustomobject][ordered]@{
    schema_version = "local-source-process-root-instance.v2"
    pid = [int]$record.pid
    creation_date = [string]$record.creation_date
    exit_date = $null
    executable_path = [string]$record.executable_path
    is_alive_at_bind = $true
    handle_reference = $capturedProcess
}
$capturedIdentity = [pscustomobject]@{
    Name = [string]$case.captured_name
    ProcessKind = [string]$record.process_kind
    Pid = [int]$record.pid
    ParentPid = [int]$record.parent_pid
    CreationDate = [string]$record.creation_date
    ExecutablePath = [string]$record.executable_path
    CommandLine = [string]$record.command_line
    WorkingDirectory = [string]$record.working_directory
    ProjectRoot = [string]$record.project_root
    Depth = 0
}
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipLaunchTombstone" `
    -NotePropertyValue $tombstone
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipRootInstanceAuthority" `
    -NotePropertyValue $rootAuthority
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipCapturedIdentity" `
    -NotePropertyValue $capturedIdentity
$snapshot = @($case.snapshot | ForEach-Object {
    [pscustomobject]@{
        ProcessId = [int]$_.ProcessId
        ParentProcessId = [int]$_.ParentProcessId
        CreationDate = [string]$_.CreationDate
        ExecutablePath = [string]$_.ExecutablePath
        CommandLine = [string]$_.CommandLine
        WorkingDirectory = if ($null -eq $_.WorkingDirectory) {
            $null
        } else {
            [string]$_.WorkingDirectory
        }
    }
})
$plan = New-SourceProcessOwnershipUnpublishedPlan `
    -CapturedProcess $capturedProcess `
    -Snapshot $snapshot `
    -ProtectedProcessPolicy { param($Process) return $false }
$output = [pscustomobject]@{
    code = if ($null -ne $plan.PSObject.Properties["Code"]) {
        [string]$plan.Code
    } else {
        $null
    }
    root_name = if ($null -ne $plan.PSObject.Properties["Root"]) {
        [string]$plan.Root.Name
    } else {
        $null
    }
    root_process_kind = if ($null -ne $plan.PSObject.Properties["Root"]) {
        [string]$plan.Root.ProcessKind
    } else {
        $null
    }
    process_pids = if ($null -ne $plan.PSObject.Properties["Processes"]) {
        @($plan.Processes | ForEach-Object { [int]$_.Pid })
    } else {
        @()
    }
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-CaseFile",
            str(case_file),
            "-ResultFile",
            str(result_file),
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    result = json.loads(result_file.read_text(encoding="utf-8-sig"))
    process_pids = result["process_pids"]
    if isinstance(process_pids, int):
        result["process_pids"] = [process_pids]
    elif not process_pids:
        result["process_pids"] = []
    return result


def _run_cim_free_publication_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "ownership-functions-only.ps1"
    harness_file = tmp_path / "invoke-cim-free-publication.ps1"
    result_file = tmp_path / "cim-free-publication-result.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile
$name = "python-agent"
$kind = "PYTHON"
$pidValue = 6200
$launcherPid = 6100
$creationDate = "2026-08-06T00:00:00.0430000Z"
$launchTimestamp = "2026-08-06T00:00:00.0000000Z"
$pythonExecutable = "D:\miniconda\python.exe"
$workingDirectory = Join-Path $ProjectRoot "python-agent-service"
$appDirectory = Join-Path $ProjectRoot "deploy\target-e2e\python"
$expectedCommand = '"' + $pythonExecutable +
    '" -m uvicorn mtls_adapter:create_app --factory --app-dir ' +
    $appDirectory +
    ' --host 127.0.0.1 --port 18000 --loop asyncio:SelectorEventLoop'
$capturedProcess = [pscustomobject]@{
    Id = $pidValue
    HasExited = $false
}
$tombstone = [pscustomobject][ordered]@{
    schema_version = "local-source-process-launch-tombstone.v1"
    name = $name
    process_kind = $kind
    pid = $pidValue
    expected_executable_path = $pythonExecutable
    expected_command_line = $expectedCommand
    working_directory = $workingDirectory
    project_root = $ProjectRoot
    launched_at = $launchTimestamp
    launcher_pid = $launcherPid
    launcher_creation_date = "2026-08-05T23:59:00.0000000Z"
    launcher_executable_path =
        "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
}
$rootAuthority = [pscustomobject][ordered]@{
    schema_version = "local-source-process-root-instance.v2"
    pid = $pidValue
    creation_date = $creationDate
    exit_date = $null
    executable_path = $pythonExecutable
    is_alive_at_bind = $true
    handle_reference = $capturedProcess
}
$capturedExecutable = if ($Scenario -eq "authority-mismatch") {
    "D:\miniconda\python-mismatch.exe"
} else {
    $pythonExecutable
}
$capturedIdentity = [pscustomobject]@{
    Name = $name
    ProcessKind = $kind
    Pid = $pidValue
    CreationDate = $creationDate
    ExecutablePath = $capturedExecutable
    CommandLine = $null
    WorkingDirectory = $workingDirectory
    ProjectRoot = $ProjectRoot
}
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipLaunchTombstone" `
    -NotePropertyValue $tombstone
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipRootInstanceAuthority" `
    -NotePropertyValue $rootAuthority
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipCapturedIdentity" `
    -NotePropertyValue $capturedIdentity

$record = $null
$buildError = $null
try {
    $record = New-SourceProcessOwnershipPublicationRecord `
        -Name $name `
        -ProcessKind $kind `
        -CapturedProcess $capturedProcess `
        -WorkingDirectory $workingDirectory `
        -ProjectRoot $ProjectRoot
}
catch {
    $buildError = $_.Exception.Message
}

$observedCommand = switch ($Scenario) {
    "trailing" { $expectedCommand + " `t" }
    "argv-extra" { $expectedCommand + " --unexpected" }
    "argv-reordered" {
        $expectedCommand.Replace(
            "--host 127.0.0.1 --port 18000",
            "--port 18000 --host 127.0.0.1")
    }
    "argv-changed" { $expectedCommand.Replace("--port 18000", "--port 18001") }
    default { $expectedCommand }
}
$commandEquivalent = $null
$rootIdentityAccepted = $null
if ($null -ne $record) {
    $commandEquivalent = Test-SourceProcessOwnershipClosedCommandEquivalent `
        -Expected $record.command_line `
        -Actual $observedCommand
    $observedCreationDate = if ($Scenario -eq "creation-submillisecond") {
        "2026-08-06T00:00:00.0437000Z"
    } else {
        $record.creation_date
    }
    $observedIdentity = [pscustomobject]@{
        Name = $name
        ProcessKind = $kind
        Pid = $pidValue
        ParentPid = $launcherPid
        CreationDate = $observedCreationDate
        ExecutablePath = $pythonExecutable
        CommandLine = $observedCommand
        WorkingDirectory = $null
        ProjectRoot = $ProjectRoot
        Depth = 0
    }
    $rootIdentityAccepted = Test-SourceProcessOwnershipRootIdentity `
        -Record $record `
        -Identity $observedIdentity
}

$recordPath = Join-Path (Split-Path -Parent $ResultFile) "$name.ownership.json"
$sentinel = "EXISTING_RECORD_MUST_NOT_BE_OVERWRITTEN"
if ($Scenario -eq "existing") {
    [System.IO.File]::WriteAllText(
        $recordPath,
        $sentinel,
        [System.Text.UTF8Encoding]::new($false))
}
$writeError = $null
if ($null -ne $record) {
    try {
        Write-SourceProcessOwnershipRecordFile `
            -Record $record `
            -RecordPath $recordPath | Out-Null
    }
    catch {
        $writeError = $_.Exception.Message
    }
}
$finalExists = [System.IO.File]::Exists($recordPath)
$finalContent = if ($finalExists) {
    [System.IO.File]::ReadAllText(
        $recordPath,
        [System.Text.UTF8Encoding]::new($false, $true))
} else {
    $null
}
$finalHasBom = $false
if ($finalExists) {
    $finalBytes = [System.IO.File]::ReadAllBytes($recordPath)
    $finalHasBom = $finalBytes.Length -ge 3 -and
        $finalBytes[0] -eq 0xEF -and
        $finalBytes[1] -eq 0xBB -and
        $finalBytes[2] -eq 0xBF
}
$temporaryCount = @(
    Get-ChildItem `
        -LiteralPath (Split-Path -Parent $recordPath) `
        -Filter ((Split-Path -Leaf $recordPath) + ".*.tmp") `
        -File `
        -ErrorAction SilentlyContinue
).Count
$output = [pscustomobject]@{
    expected_command = $expectedCommand
    record = $record
    build_error = $buildError
    command_equivalent = $commandEquivalent
    root_identity_accepted = $rootIdentityAccepted
    write_error = $writeError
    final_exists = $finalExists
    final_content = $finalContent
    final_has_bom = $finalHasBom
    temporary_count = $temporaryCount
    sentinel = $sentinel
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(tmp_path / "candidate"),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_native_handle_identity_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "ownership-functions-only.ps1"
    harness_file = tmp_path / "invoke-native-handle-identity.ps1"
    result_file = tmp_path / "native-handle-identity-result.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$pidValue = 7200
$launcherPid = 7100
$creationDate = "2026-08-06T00:00:00.0430000Z"
$launchTimestamp = "2026-08-06T00:00:00.0420000Z"
$launcherExecutable =
    "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
$name = "python-agent"
$kind = "PYTHON"
$executable = "C:\runtime\python.exe"
$workingDirectory = Join-Path $ProjectRoot "python-agent-service"
$appDirectory = Join-Path $ProjectRoot "deploy\target-e2e\python"
$expectedCommand = '"' + $executable +
    '" -m uvicorn mtls_adapter:create_app --factory --app-dir ' +
    $appDirectory +
    ' --host 127.0.0.1 --port 18000 --loop asyncio:SelectorEventLoop'

if ($Scenario -eq "java-success") {
    $name = "java-api"
    $kind = "JAVA"
    $executable = "C:\runtime\java.exe"
    $workingDirectory = Join-Path $ProjectRoot "java-api-service"
    $classpath = Join-Path $workingDirectory "target\target-e2e-classes"
    $expectedCommand = '"' + $executable + '" -cp "' + $classpath +
        '" com.example.dispute.DisputeApplication ' +
        '--app.temporal.worker.role=API'
}
elseif ($Scenario -eq "frontend-success") {
    $name = "frontend"
    $kind = "FRONTEND"
    $executable = "C:\Windows\System32\cmd.exe"
    $workingDirectory = Join-Path $ProjectRoot "frontend"
    $expectedCommand = '"' + $executable + '" /d /c pnpm --dir "' +
        $workingDirectory + '" dev'
}

$capturedProcess = [pscustomobject]@{ Id = $pidValue }
$tombstone = [pscustomobject][ordered]@{
    schema_version = "local-source-process-launch-tombstone.v1"
    name = $name
    process_kind = $kind
    pid = $pidValue
    expected_executable_path = $executable
    expected_command_line = $expectedCommand
    working_directory = $workingDirectory
    project_root = $ProjectRoot
    launched_at = $launchTimestamp
    launcher_pid = $launcherPid
    launcher_creation_date = "2026-08-05T23:59:00.0000000Z"
    launcher_executable_path = $launcherExecutable
}
$rootAuthority = [pscustomobject][ordered]@{
    schema_version = "local-source-process-root-instance.v2"
    pid = $pidValue
    creation_date = $creationDate
    exit_date = $null
    executable_path = if ($Scenario -eq "actual-exe-mismatch") {
        "C:\runtime\unexpected.exe"
    } else { $executable }
    is_alive_at_bind = $true
    handle_reference = $capturedProcess
}
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipLaunchTombstone" `
    -NotePropertyValue $tombstone
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipRootInstanceAuthority" `
    -NotePropertyValue $rootAuthority

$providerState = [pscustomobject]@{ Count = 0 }
$exitScenarios = $Scenario -in @(
    "captured-root-exit",
    "published-tree-exit",
    "unpublished-plan-exit",
    "exit-pid-mismatch",
    "exit-creation-mismatch",
    "exit-wait-timeout",
    "exit-wait-failed",
    "exit-zero-time"
)
$handleSnapshotProvider = {
    param($Process)
    $providerState.Count += 1
    if ($Scenario -eq "provider-failure") {
        throw "PURE_PROVIDER_QUERY_FAILED"
    }
    if ($exitScenarios -and $providerState.Count -gt 1) {
        throw "IMAGE_QUERY_AFTER_EXIT"
    }
    $snapshotPid = if ($Scenario -eq "pid-mismatch") {
        $pidValue + 1
    } else {
        $pidValue
    }
    $snapshotCreation = if ($Scenario -eq "creation-mismatch") {
        "2026-08-06T00:00:00.0450000Z"
    } else {
        $creationDate
    }
    $snapshotExecutable = if ($Scenario -eq "actual-exe-mismatch") {
        "C:\runtime\unexpected.exe"
    } else {
        $executable
    }
    $exited = $Scenario -eq "exit-before-move" -and $providerState.Count -ge 2
    return [pscustomobject]@{
        Pid = $snapshotPid
        CreationDate = $snapshotCreation
        ExitDate = if ($exited) {
            "2026-08-06T00:00:01.0000000Z"
        } else {
            $null
        }
        ExecutablePath = $snapshotExecutable
        IsAlive = -not $exited
    }
}.GetNewClosure()
$exitProviderState = [pscustomobject]@{ Count = 0 }
$exitStateProvider = {
    param($Process)
    $exitProviderState.Count += 1
    if ($Scenario -eq "exit-wait-failed") {
        throw "PURE_EXIT_STATE_QUERY_FAILED"
    }
    $statePid = if ($Scenario -eq "exit-pid-mismatch") {
        $pidValue + 1
    } else {
        $pidValue
    }
    $stateCreation = if ($Scenario -eq "exit-creation-mismatch") {
        "2026-08-06T00:00:00.0450000Z"
    } else {
        $creationDate
    }
    $isExited = $Scenario -ne "exit-wait-timeout"
    return [pscustomobject]@{
        Pid = $statePid
        CreationDate = $stateCreation
        ExitDate = if ($isExited -and $Scenario -ne "exit-zero-time") {
            "2026-08-06T00:00:01.0000000Z"
        } else {
            $null
        }
        IsExited = $isExited
    }
}.GetNewClosure()
$handleStateProvider = {
    param($Process)
    $providerState.Count += 1
    if ($Scenario -eq "provider-failure") {
        throw "PURE_PROVIDER_QUERY_FAILED"
    }
    return [pscustomobject]@{
        Pid = if ($Scenario -eq "pid-mismatch") {
            $pidValue + 1
        } else { $pidValue }
        CreationDate = if ($Scenario -eq "creation-mismatch") {
            "2026-08-06T00:00:00.0450000Z"
        } else { $creationDate }
        ExitDate = $null
        IsExited = $false
    }
}.GetNewClosure()

$identity = $null
$identityError = $null
try {
    $identity = Get-SourceProcessOwnershipCapturedIdentity `
        -CapturedProcess $capturedProcess `
        -Name $name `
        -ProcessKind $kind `
        -WorkingDirectory $workingDirectory `
        -ProjectRoot $ProjectRoot `
        -HandleStateProvider $handleStateProvider
}
catch {
    $identityError = $_.Exception.Message
}

$recordPath = Join-Path (Split-Path -Parent $ResultFile) "$name.ownership.json"
$writeError = $null
if ($Scenario -eq "exit-before-move" -and $null -ne $identity) {
    $beforeMove = {
        $current = Get-SourceProcessOwnershipHandleSnapshot `
            -CapturedProcess $capturedProcess `
            -SnapshotProvider $handleSnapshotProvider
        if (-not (Test-SourceProcessOwnershipHandleSnapshotMatchesIdentity `
                -Snapshot $current `
                -Identity $identity `
                -RequireAlive)) {
            throw "HANDLE_EXITED_BEFORE_MOVE"
        }
    }.GetNewClosure()
    try {
        Write-SourceProcessOwnershipRecordFile `
            -Record ([pscustomobject]@{ schema_version = "test.v1" }) `
            -RecordPath $recordPath `
            -BeforeMove $beforeMove | Out-Null
    }
    catch {
        $writeError = $_.Exception.Message
    }
}

$rootMatch = $null
$reusedDescendantMatch = $null
if ($Scenario -eq "unpublished-reused-descendant" -and $null -ne $identity) {
    $rootSnapshot = Get-SourceProcessOwnershipHandleSnapshot `
        -CapturedProcess $capturedProcess `
        -SnapshotProvider $handleSnapshotProvider
    $rootMatch = Test-SourceProcessOwnershipHandleSnapshotMatchesIdentity `
        -Snapshot $rootSnapshot `
        -Identity $identity `
        -RequireAlive
    $reusedDescendant = [pscustomobject]@{
        Pid = 7300
        CreationDate = "2026-08-06T00:00:02.0000000Z"
        ExitDate = $null
        ExecutablePath = $executable
        IsAlive = $true
    }
    $plannedDescendant = [pscustomobject]@{
        Pid = 7300
        CreationDate = "2026-08-06T00:00:01.0000000Z"
        ExecutablePath = $executable
    }
    $reusedDescendantMatch =
        Test-SourceProcessOwnershipHandleSnapshotMatchesIdentity `
            -Snapshot $reusedDescendant `
            -Identity $plannedDescendant `
            -RequireAlive
}

$exitState = $null
$exitStateError = $null
$exitMatch = $null
if ($exitScenarios -and $null -ne $identity) {
    try {
        $exitState = Get-SourceProcessOwnershipHandleExitState `
            -CapturedProcess $capturedProcess `
            -StateProvider $exitStateProvider
        $exitMatch = Test-SourceProcessOwnershipHandleExitStateMatchesIdentity `
            -State $exitState `
            -Identity $identity
    }
    catch {
        $exitStateError = $_.Exception.Message
    }
}

$temporaryCount = @(
    Get-ChildItem `
        -LiteralPath (Split-Path -Parent $recordPath) `
        -Filter ((Split-Path -Leaf $recordPath) + ".*.tmp") `
        -File `
        -ErrorAction SilentlyContinue
).Count
$output = [pscustomobject]@{
    identity = $identity
    identity_error = $identityError
    provider_calls = $providerState.Count
    write_error = $writeError
    final_exists = [System.IO.File]::Exists($recordPath)
    temporary_count = $temporaryCount
    root_match = $rootMatch
    reused_descendant_match = $reusedDescendantMatch
    exit_state = $exitState
    exit_state_error = $exitStateError
    exit_match = $exitMatch
    exit_provider_calls = $exitProviderState.Count
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(tmp_path / "candidate"),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_retained_root_handle_authority_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "retained-root-authority-functions.ps1"
    harness_file = tmp_path / "invoke-retained-root-authority.ps1"
    result_file = tmp_path / "retained-root-authority-result.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$capturedProcess = [System.Diagnostics.Process]::GetCurrentProcess()
$pidValue = $capturedProcess.Id
$creationDate = "2026-08-06T00:00:00.0430000Z"
$launchTimestamp = "2026-08-06T00:00:00.0420000Z"
$executable = "C:\runtime\java.exe"
$workingDirectory = Join-Path $ProjectRoot "java-api-service"
$classpath = Join-Path $workingDirectory "target\target-e2e-classes"
$expectedCommand = '"' + $executable + '" -cp "' + $classpath +
    '" com.example.dispute.DisputeApplication ' +
    '--app.temporal.worker.role=API'
$tombstone = [pscustomobject][ordered]@{
    schema_version = "local-source-process-launch-tombstone.v1"
    name = "java-api"
    process_kind = "JAVA"
    pid = $pidValue
    expected_executable_path = $executable
    expected_command_line = $expectedCommand
    working_directory = $workingDirectory
    project_root = $ProjectRoot
    launched_at = $launchTimestamp
    launcher_pid = 7100
    launcher_creation_date = "2026-08-05T23:59:00.0000000Z"
    launcher_executable_path =
        "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
}
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipLaunchTombstone" `
    -NotePropertyValue $tombstone `
    -Force

$script:fullImageCalls = 0
function Get-SourceProcessOwnershipNativeHandleSnapshot {
    param([Parameter(Mandatory = $true)]$Process)
    $script:fullImageCalls += 1
    if ($script:fullImageCalls -gt 1) {
        throw "SECOND_FULL_IMAGE_QUERY_FORBIDDEN"
    }
    $isExited = $Scenario -eq "exited-state"
    return [pscustomobject]@{
        Pid = if ($Scenario -eq "wrong-pid") { $pidValue + 1 } else { $pidValue }
        CreationDate = $creationDate
        ExitDate = if ($isExited) {
            "2026-08-06T00:00:01.0000000Z"
        } else { $null }
        ExecutablePath = if ($Scenario -eq "wrong-executable") {
            "C:\runtime\unexpected.exe"
        } else { $executable }
        IsAlive = -not $isExited
    }
}

$script:stateCalls = 0
$script:statePhase = "publication"
function Get-SourceProcessOwnershipNativeHandleExitState {
    param([Parameter(Mandatory = $true)]$Process)
    $script:stateCalls += 1
    $isExited = $script:statePhase -eq "compensation"
    return [pscustomobject]@{
        Pid = $pidValue
        CreationDate = if ($Scenario -eq "wrong-creation") {
            "2026-08-06T00:00:00.0450000Z"
        } else { $creationDate }
        ExitDate = if ($isExited) {
            "2026-08-06T00:00:01.0000000Z"
        } else { $null }
        IsExited = $isExited
    }
}

$rootAuthority = $null
$bindError = $null
try {
    $rootAuthority = Bind-SourceProcessOwnershipRootInstance `
        -CapturedProcess $capturedProcess `
        -RootInstanceProvider `
            ${function:Get-SourceProcessOwnershipRootInstanceFromHandle}
}
catch {
    $bindError = $_.Exception.Message
}

$captureError = $null
$publishCode = $null
$publishError = $null
$compensationError = $null
$descendantMatch = $null
$script:writerCalls = 0
if ($null -ne $rootAuthority -and $Scenario -eq "wrong-creation") {
    try {
        Get-SourceProcessOwnershipCapturedIdentity `
            -CapturedProcess $capturedProcess `
            -Name "java-api" `
            -ProcessKind "JAVA" `
            -WorkingDirectory $workingDirectory `
            -ProjectRoot $ProjectRoot | Out-Null
    }
    catch {
        $captureError = $_.Exception.Message
    }
}
elseif ($null -ne $rootAuthority -and
        $Scenario -in @("single-full-success", "descendant-full-bind-mismatch")) {
    $writer = {
        param(
            [string]$Name,
            [string]$ProcessKind,
            $CapturedProcess,
            [string]$WorkingDirectory,
            [string]$ProjectRoot,
            [string]$OwnershipRecordDirectory
        )
        $script:writerCalls += 1
        if ($null -eq $CapturedProcess.PSObject.Properties[
                "SourceOwnershipCapturedIdentity"]) {
            throw "CAPTURED_IDENTITY_NOT_ATTACHED"
        }
        return $true
    }
    try {
        $published = Publish-SourceProcessOwnershipOrCompensate `
            -Name "java-api" `
            -ProcessKind "JAVA" `
            -CapturedProcess $capturedProcess `
            -WorkingDirectory $workingDirectory `
            -ProjectRoot $ProjectRoot `
            -OwnershipRecordDirectory (Split-Path -Parent $ResultFile) `
            -OwnershipWriter $writer `
            -UnpublishedProcessTerminator { param($Process) return $false } `
            -UnpublishedProcessCleanupRegistry `
                ([System.Collections.Generic.List[object]]::new())
        $publishCode = [string]$published.Code
    }
    catch {
        $publishError = $_.Exception.Message
    }

    if ($Scenario -eq "single-full-success" -and $null -eq $publishError) {
        $script:statePhase = "compensation"
        $identity = $capturedProcess.PSObject.Properties[
            "SourceOwnershipCapturedIdentity"
        ].Value
        $planIdentity = [pscustomobject]@{
            Pid = $identity.Pid
            CreationDate = $identity.CreationDate
            ExecutablePath = $identity.ExecutablePath
            Depth = 0
        }
        try {
            Invoke-SourceProcessOwnershipUnpublishedPlanTermination `
                -CapturedProcess $capturedProcess `
                -Plan ([pscustomobject]@{
                    Root = $planIdentity
                    Processes = @($planIdentity)
                })
        }
        catch {
            $compensationError = $_.Exception.Message
        }
    }
    elseif ($Scenario -eq "descendant-full-bind-mismatch" -and
            $null -eq $publishError) {
        $descendantProcess = [pscustomobject]@{ Id = $pidValue + 1 }
        $descendantSnapshot = Get-SourceProcessOwnershipHandleSnapshot `
            -CapturedProcess $descendantProcess `
            -SnapshotProvider {
                param($Process)
                return [pscustomobject]@{
                    Pid = $Process.Id
                    CreationDate = "2026-08-06T00:00:02.0000000Z"
                    ExitDate = $null
                    ExecutablePath = "C:\runtime\unexpected-child.exe"
                    IsAlive = $true
                }
            }
        $descendantMatch = Test-SourceProcessOwnershipHandleSnapshotMatchesIdentity `
            -Snapshot $descendantSnapshot `
            -Identity ([pscustomobject]@{
                Pid = $pidValue + 1
                CreationDate = "2026-08-06T00:00:02.0000000Z"
                ExecutablePath = "C:\runtime\expected-child.exe"
            }) `
            -RequireAlive
    }
}

$output = [pscustomobject]@{
    bind_error = $bindError
    capture_error = $captureError
    publish_code = $publishCode
    publish_error = $publishError
    compensation_error = $compensationError
    descendant_match = $descendantMatch
    full_image_calls = $script:fullImageCalls
    state_calls = $script:stateCalls
    writer_calls = $script:writerCalls
    root_schema = if ($null -eq $rootAuthority) {
        $null
    } else { [string]$rootAuthority.schema_version }
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(tmp_path / "candidate"),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_pre_authority_cleanup_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "pre-authority-functions.ps1"
    harness_file = tmp_path / "invoke-pre-authority-cleanup.ps1"
    result_file = tmp_path / "pre-authority-cleanup-result.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$warnings = [System.Collections.Generic.List[string]]::new()
function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warnings.Add($Message)
}

$pidValue = 7400
$launcherPid = 7300
$creationDate = "2026-08-06T00:00:00.0430000Z"
$launchTimestamp = "2026-08-06T00:00:00.0420000Z"
$executable = "C:\runtime\java.exe"
$workingDirectory = Join-Path $ProjectRoot "java-api-service"
$classpath = Join-Path $workingDirectory "target\target-e2e-classes"
$command = '"' + $executable + '" -cp "' + $classpath +
    '" com.example.dispute.DisputeApplication ' +
    '--app.temporal.worker.role=API --server.port=8081'
$capturedProcess = [pscustomobject]@{ Id = $pidValue }
$tombstone = Initialize-SourceProcessOwnershipLaunchTombstone `
    -Name "java-api" `
    -ProcessKind "JAVA" `
    -CapturedProcess $capturedProcess `
    -ProcessId $pidValue `
    -ExpectedExecutablePath $executable `
    -ExpectedCommandLine $command `
    -WorkingDirectory $workingDirectory `
    -ProjectRoot $ProjectRoot `
    -LaunchTimestamp $launchTimestamp `
    -LauncherProcessId $launcherPid `
    -LauncherCreationDate "2026-08-05T23:59:00.0000000Z" `
    -LauncherExecutablePath `
        "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
$validState = [pscustomobject]@{
    Pid = $pidValue
    CreationDate = $creationDate
    ExitDate = $null
    IsExited = $false
}
$stateProvider = { param($Process) return $validState }.GetNewClosure()
$script:SourceProcessUnpublishedCleanupRegistry =
    [System.Collections.Generic.List[object]]::new()
$terminatorState = [pscustomobject]@{ Count = 0 }
$terminator = {
    param($Process, $PreAuthority)
    $terminatorState.Count += 1
    $PreAuthority.exit_date = "2026-08-06T00:00:01.0000000Z"
    return $true
}.GetNewClosure()
$fullProvider = {
    param($Process)
    if ($Scenario.StartsWith("full-image-throw")) {
        throw "FULL_IMAGE_PROVIDER_FAILED"
    }
    return [pscustomobject]@{
        ProcessId = $pidValue
        CreationDate = $creationDate
        ExitDate = $null
        ExecutablePath = $executable
        IsAlive = $true
        HandleReference = $Process
    }
}.GetNewClosure()

$bindError = $null
$rootAuthority = $null
if ($Scenario.StartsWith("full-image-throw") -or $Scenario -eq "full-success") {
    try {
        $rootAuthority = Bind-SourceProcessOwnershipRootInstance `
            -CapturedProcess $capturedProcess `
            -RootInstanceProvider $fullProvider `
            -HandleStateProvider $stateProvider `
            -PreAuthorityTerminator $terminator
    }
    catch {
        $bindError = $_.Exception.Message
    }
}

$preProperty = $capturedProcess.PSObject.Properties[
    "SourceOwnershipPreAuthority"
]
$preAuthority = if ($null -ne $preProperty) {
    $preProperty.Value
}
else {
    New-SourceProcessOwnershipPreAuthority `
        -CapturedProcess $capturedProcess `
        -Tombstone $tombstone `
        -State $validState
}

$validationTombstone = $tombstone
$validationState = $validState
$validationProcess = $capturedProcess
switch ($Scenario) {
    "invalid-pid" { $preAuthority.pid = $pidValue + 1 }
    "invalid-creation" {
        $preAuthority.creation_date = "2026-08-06T00:00:00.0450000Z"
    }
    "invalid-handle" { $validationProcess = [pscustomobject]@{ Id = $pidValue } }
    "invalid-exited" {
        $validationState = [pscustomobject]@{
            Pid = $pidValue
            CreationDate = $creationDate
            ExitDate = "2026-08-06T00:00:01.0000000Z"
            IsExited = $true
        }
    }
    "invalid-executable" {
        $preAuthority.expected_executable_path = "C:\runtime\unexpected.exe"
    }
    "invalid-tombstone" {
        $validationTombstone = $tombstone.PSObject.Copy()
        $validationTombstone.expected_command_line = $command + " --drift"
    }
}
$preAuthorityValid = Test-SourceProcessOwnershipPreAuthority `
    -Authority $preAuthority `
    -CapturedProcess $validationProcess `
    -Tombstone $validationTombstone `
    -State $validationState

$rootRow = [pscustomobject]@{
    ProcessId = $pidValue
    ParentProcessId = $launcherPid
    CreationDate = if ($Scenario -eq "snapshot-drift") {
        "2026-08-06T00:00:00.0450000Z"
    } else { $creationDate }
    ExecutablePath = $executable
    CommandLine = $command
    WorkingDirectory = $workingDirectory
}
$snapshot = @($rootRow)
if ($Scenario -eq "protected-descendant") {
    $snapshot += [pscustomobject]@{
        ProcessId = $pidValue + 1
        ParentProcessId = $pidValue
        CreationDate = "2026-08-06T00:00:01.0000000Z"
        ExecutablePath = $executable
        CommandLine = $command + " --owned-child"
        WorkingDirectory = $workingDirectory
    }
}
$protectedPolicy = {
    param($Process)
    return $Scenario -eq "protected-descendant" -and
        [int]$Process.Pid -eq $pidValue + 1
}.GetNewClosure()
$planCode = $null
$planCount = $null
if ($Scenario -in @(
        "full-image-throw",
        "protected-descendant",
        "snapshot-drift")) {
    if ($null -eq $capturedProcess.PSObject.Properties[
            "SourceOwnershipPreAuthority"]) {
        Add-Member -InputObject $capturedProcess `
            -NotePropertyName "SourceOwnershipPreAuthority" `
            -NotePropertyValue $preAuthority `
            -Force
    }
    $plan = New-SourceProcessOwnershipUnpublishedPlan `
        -CapturedProcess $capturedProcess `
        -Snapshot $snapshot `
        -ProtectedProcessPolicy $protectedPolicy
    $codeProperty = $plan.PSObject.Properties["Code"]
    if ($null -ne $codeProperty) {
        $planCode = [string]$codeProperty.Value
    }
    else {
        $planCount = @($plan.Processes).Count
    }
}

$registryCountBeforeDrain = $null
$registrySameReference = $null
$compensationBeforeDrain = $null
$registryCountAfterDrain = $null
$compensationAfterDrain = $null
$snapshotCalls = 0
$planTerminationCalls = 0
$terminatedPlanPids = @()
if ($Scenario.StartsWith("full-image-throw")) {
    $registryCountBeforeDrain =
        $script:SourceProcessUnpublishedCleanupRegistry.Count
    $registrySameReference = $registryCountBeforeDrain -eq 1 -and
        [object]::ReferenceEquals(
            $script:SourceProcessUnpublishedCleanupRegistry[0],
            $capturedProcess)
    $compensationProperty = $capturedProcess.PSObject.Properties[
        "SourceOwnershipCompensationConfirmed"
    ]
    $compensationBeforeDrain = $null -ne $compensationProperty -and
        [bool]$compensationProperty.Value
    $child = [pscustomobject]@{
        ProcessId = $pidValue + 1
        ParentProcessId = $pidValue
        CreationDate = "2026-08-06T00:00:00.5000000Z"
        ExecutablePath = $executable
        CommandLine = $command + " --owned-child"
        WorkingDirectory = $workingDirectory
    }
    $censusRows = if ($Scenario -eq "full-image-throw") {
        @($child)
    }
    else {
        @()
    }
    $snapshotState = [pscustomobject]@{ Index = 0 }
    $script:SourceProcessSnapshotProvider = {
        $current = if ($snapshotState.Index -lt 2) {
            @($censusRows)
        }
        else {
            @()
        }
        $snapshotState.Index += 1
        return $current
    }.GetNewClosure()
    $script:SourceProcessProtectedPolicy = { param($Process) return $false }
    $planTerminationState = [pscustomobject]@{
        Count = 0
        Pids = @()
    }
    function Invoke-SourceProcessOwnershipUnpublishedPlanTermination {
        param($CapturedProcess, $Plan)
        $planTerminationState.Count += 1
        $planTerminationState.Pids = @(
            $Plan.Processes | ForEach-Object { [int]$_.Pid }
        )
    }
    Invoke-SourceProcessOwnershipCleanupRegistryRetry `
        -Registry $script:SourceProcessUnpublishedCleanupRegistry `
        -Terminator ${function:Invoke-SourceProcessOwnershipUnpublishedTermination} |
        Out-Null
    $registryCountAfterDrain =
        $script:SourceProcessUnpublishedCleanupRegistry.Count
    $compensationProperty = $capturedProcess.PSObject.Properties[
        "SourceOwnershipCompensationConfirmed"
    ]
    $compensationAfterDrain = $null -ne $compensationProperty -and
        [bool]$compensationProperty.Value
    $snapshotCalls = $snapshotState.Index
    $planTerminationCalls = $planTerminationState.Count
    $terminatedPlanPids = @($planTerminationState.Pids)
}

$output = [pscustomobject]@{
    bind_error = $bindError
    root_schema = if ($null -eq $rootAuthority) {
        $null
    } else { [string]$rootAuthority.schema_version }
    pre_schema = [string]$preAuthority.schema_version
    pre_valid = $preAuthorityValid
    terminator_calls = $terminatorState.Count
    warnings = @($warnings)
    plan_code = $planCode
    plan_count = $planCount
    registry_before = $registryCountBeforeDrain
    registry_same_reference = $registrySameReference
    compensation_before = $compensationBeforeDrain
    registry_after = $registryCountAfterDrain
    compensation_after = $compensationAfterDrain
    snapshot_calls = $snapshotCalls
    plan_termination_calls = $planTerminationCalls
    terminated_plan_pids = @($terminatedPlanPids)
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(tmp_path / "candidate"),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_root_authority_predicate_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "root-authority-predicate-functions.ps1"
    harness_file = tmp_path / "invoke-root-authority-predicate.ps1"
    result_file = tmp_path / "root-authority-predicate-result.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$warnings = [System.Collections.Generic.List[string]]::new()
function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warnings.Add($Message)
}
$pidValue = 7600
$launcherPid = 7500
$creationDate = "2026-08-06T00:00:00.0430000Z"
$launchTimestamp = "2026-08-06T00:00:00.0420000Z"
$executable = "C:\runtime\java.exe"
$workingDirectory = Join-Path $ProjectRoot "java-api-service"
$classpath = Join-Path $workingDirectory "target\target-e2e-classes"
$command = '"' + $executable +
    '" -cp "' + $classpath +
    '" com.example.dispute.DisputeApplication ' +
    '--app.temporal.worker.role=API --server.port=8081'
$capturedProcess = [pscustomobject]@{ Id = $pidValue }
$tombstone = Initialize-SourceProcessOwnershipLaunchTombstone `
    -Name "java-api" `
    -ProcessKind "JAVA" `
    -CapturedProcess $capturedProcess `
    -ProcessId $pidValue `
    -ExpectedExecutablePath $executable `
    -ExpectedCommandLine $command `
    -WorkingDirectory $workingDirectory `
    -ProjectRoot $ProjectRoot `
    -LaunchTimestamp $launchTimestamp `
    -LauncherProcessId $launcherPid `
    -LauncherCreationDate "2026-08-05T23:59:00.0000000Z" `
    -LauncherExecutablePath `
        "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
$authority = [pscustomobject][ordered]@{
    schema_version = "local-source-process-root-instance.v2"
    pid = $pidValue
    creation_date = $creationDate
    exit_date = $null
    executable_path = $executable
    is_alive_at_bind = $true
    handle_reference = $capturedProcess
}
switch ($Scenario) {
    "AUTHORITY_SHAPE" {
        Add-Member -InputObject $authority `
            -NotePropertyName "unexpected" `
            -NotePropertyValue $true
    }
    "SCHEMA_VERSION" { $authority.schema_version = "unexpected.v1" }
    "PID_BINDING" { $authority.pid = $pidValue + 1 }
    "BIND_LIVENESS" { $authority.is_alive_at_bind = $false }
    "HANDLE_REFERENCE" {
        $authority.handle_reference = [pscustomobject]@{ Id = $pidValue }
    }
    "CREATION_VALUE" { $authority.creation_date = "not-a-date" }
    "EXECUTABLE_VALUE" { $authority.executable_path = "::invalid::" }
    "EXECUTABLE_BINDING" {
        $authority.executable_path = "C:\runtime\unexpected.exe"
    }
    "LAUNCH_ORDER" {
        $authority.creation_date = "2026-08-06T00:00:00.0410000Z"
    }
    "EXIT_VALUE" {
        $authority.exit_date = "2026-08-06T00:00:00.0400000Z"
    }
}
$code = Get-SourceProcessOwnershipRootInstanceAuthorityValidationCode `
    -Authority $authority `
    -CapturedProcess $capturedProcess `
    -Tombstone $tombstone
$accepted = Test-SourceProcessOwnershipRootInstanceAuthority `
    -Authority $authority `
    -CapturedProcess $capturedProcess `
    -Tombstone $tombstone

$bindError = $null
if ($Scenario -eq "BIND_WARNING_ORDER") {
    $stateProvider = {
        param($Process)
        return [pscustomobject]@{
            Pid = $pidValue
            CreationDate = $creationDate
            ExitDate = $null
            IsExited = $false
        }
    }.GetNewClosure()
    $fullProvider = {
        param($Process)
        return [pscustomobject]@{
            ProcessId = $pidValue
            CreationDate = $creationDate
            ExitDate = $null
            ExecutablePath = "C:\runtime\unexpected.exe"
            IsAlive = $true
            HandleReference = $Process
        }
    }.GetNewClosure()
    try {
        Bind-SourceProcessOwnershipRootInstance `
            -CapturedProcess $capturedProcess `
            -RootInstanceProvider $fullProvider `
            -HandleStateProvider $stateProvider `
            -PreAuthorityTerminator { param($Process, $PreAuthority) return $true } |
            Out-Null
    }
    catch {
        $bindError = $_.Exception.Message
    }
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]@{
        code = $code
        accepted = $accepted
        warnings = @($warnings)
        bind_error = $bindError
    } | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(tmp_path / "candidate"),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_java_executable_resolution_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
    java_home: str,
    reported_home: Path,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    function_file = tmp_path / "java-resolution-functions.ps1"
    harness_file = tmp_path / "invoke-java-resolution.ps1"
    result_file = tmp_path / "java-resolution-result.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$Scenario,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$ReportedHome
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$calls = [System.Collections.Generic.List[object]]::new()
$killCalls = [System.Collections.Generic.List[int]]::new()
$disposeCalls = [System.Collections.Generic.List[int]]::new()
$stateCalls = [System.Collections.Generic.List[int]]::new()
$cleanupAttempts = [System.Collections.Generic.List[int]]::new()
$cleanupReferences = [System.Collections.Generic.List[bool]]::new()
$pauseCalls = [System.Collections.Generic.List[int]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warnings.Add($Message)
}
$probeRunner = {
    param($ExecutablePath, $Arguments, $TimeoutMilliseconds)
    [void]$calls.Add([pscustomobject]@{
        executable_path = [string]$ExecutablePath
        arguments = @($Arguments)
        timeout_milliseconds = [int]$TimeoutMilliseconds
    })
    $result = [pscustomobject][ordered]@{
        schema_version = "local-source-java-probe.v1"
        exit_code = 0
        stdout = ""
        stderr = "    java.home = $ReportedHome"
        timed_out = $false
    }
    switch ($Scenario) {
        "MULTILINE_STDERR" {
            $result.stderr = "openjdk version 21`n    java.home = $ReportedHome`nVM settings complete"
        }
        "CROSS_STREAM_DUPLICATE" {
            $result.stdout = "java.home = $ReportedHome"
            $result.stderr = "openjdk version 21`njava.home = $ReportedHome`ntrailer"
        }
        "NONZERO" { $result.exit_code = 7 }
        "MALFORMED_RESULT" {
            return [pscustomobject]@{
                schema_version = "local-source-java-probe.v1"
                exit_code = "0"
                stdout = ""
                stderr = "java.home = $ReportedHome"
                timed_out = $false
            }
        }
        "TIMEOUT" { $result.timed_out = $true }
    }
    return $result
}.GetNewClosure()

$resolved = $null
$errorMessage = $null
try {
    if ($Scenario.StartsWith("LIFECYCLE_", [StringComparison]::Ordinal)) {
        $creation = "2026-08-08T00:00:00.0000000Z"
        $exit = "2026-08-08T00:00:00.0010000Z"
        $fakeProcess = [pscustomobject]@{ Id = 9300 }
        $processFactory = {
            param($StartInfo)
            return $fakeProcess
        }.GetNewClosure()
        $startProvider = { param($Process) return $true }
        $streamTaskProvider = {
            param($Process)
            return [pscustomobject]@{
                stdout_task = "stdout-token"
                stderr_task = "stderr-token"
            }
        }
        $waitProvider = {
            param($Process, $TimeoutMilliseconds)
            if ($Scenario -eq "LIFECYCLE_WAIT_EXCEPTION" -and
                $killCalls.Count -eq 0) {
                throw "injected wait detail"
            }
            if ($Scenario -eq "LIFECYCLE_TIMEOUT" -and
                $killCalls.Count -eq 0) {
                return $false
            }
            return $true
        }.GetNewClosure()
        $streamResultProvider = {
            param($StdoutTask, $StderrTask)
            if ($Scenario -eq "LIFECYCLE_STREAM_EXCEPTION") {
                throw "injected stream detail"
            }
            return [pscustomobject]@{
                stdout = ""
                stderr = "java.home = $ReportedHome"
            }
        }.GetNewClosure()
        $stateProvider = {
            param($Process)
            [void]$stateCalls.Add($stateCalls.Count + 1)
            if ($Scenario -eq "LIFECYCLE_INITIAL_BIND_EXCEPTION" -and
                $stateCalls.Count -eq 1) {
                throw "injected initial bind detail"
            }
            $exited = if ($Scenario -eq "LIFECYCLE_SUCCESS") {
                $stateCalls.Count -gt 1
            }
            else {
                $killCalls.Count -gt 0
            }
            return [pscustomobject]@{
                Pid = 9300
                CreationDate = $creation
                ExitDate = if ($exited) { $exit } else { $null }
                IsExited = [bool]$exited
            }
        }.GetNewClosure()
        $killProvider = {
            param($Process)
            [void]$killCalls.Add([int]$Process.Id)
            return $true
        }.GetNewClosure()
        $exitCodeProvider = { param($Process) return 0 }
        $disposeProvider = {
            param($Process)
            [void]$disposeCalls.Add([int]$Process.Id)
        }.GetNewClosure()
        $pauseAction = {
            [void]$pauseCalls.Add($pauseCalls.Count + 1)
        }.GetNewClosure()
        $probeParameters = @{
            ExecutablePath = Join-Path $ReportedHome "bin\\java.exe"
            Arguments = @("-XshowSettings:properties", "-version")
            TimeoutMilliseconds = 1234
            ProcessFactory = $processFactory
            StartProvider = $startProvider
            StreamTaskProvider = $streamTaskProvider
            WaitProvider = $waitProvider
            StreamResultProvider = $streamResultProvider
            HandleStateProvider = $stateProvider
            KillProvider = $killProvider
            ExitCodeProvider = $exitCodeProvider
            DisposeProvider = $disposeProvider
            PauseAction = $pauseAction
        }
        if ($Scenario -eq "LIFECYCLE_RETRY") {
            $cleanupAttemptProvider = {
                param(
                    $Process,
                    $BoundIdentity,
                    $ExpectedProcessId,
                    $StateProvider,
                    $Terminator,
                    $CleanupWaiter)
                [void]$cleanupAttempts.Add($cleanupAttempts.Count + 1)
                [void]$cleanupReferences.Add(
                    [object]::ReferenceEquals($Process, $fakeProcess))
                return $cleanupAttempts.Count -ge 3
            }.GetNewClosure()
            $probeParameters.CleanupAttemptProvider = $cleanupAttemptProvider
        }
        $resolved = Invoke-SourceJavaExecutableProbe @probeParameters
    }
    else {
        $javaHomeArgument = if ($JavaHome -ceq "__NULL__") { $null } else { $JavaHome }
        $resolved = Resolve-SourceJavaExecutable `
            -JavaHome $javaHomeArgument `
            -ProbeRunner $probeRunner `
            -ProbeTimeoutMilliseconds 1234
    }
}
catch {
    $errorMessage = $_.Exception.Message
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]@{
        resolved = $resolved
        error = $errorMessage
        calls = @($calls)
        kill_calls = @($killCalls)
        dispose_calls = @($disposeCalls)
        state_calls = @($stateCalls)
        cleanup_attempts = @($cleanupAttempts)
        cleanup_references = @($cleanupReferences)
        pause_calls = @($pauseCalls)
        warnings = @($warnings)
    } | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-Scenario",
            scenario,
            "-JavaHome",
            java_home,
            "-ReportedHome",
            str(reported_home),
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_real_java_probe_native_handle_harness(
    tmp_path: Path,
    *,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    required = (
        "Get-SourceProcessOwnershipCanonicalPath",
        "Test-SourceProcessOwnershipInteger",
        "ConvertTo-SourceProcessOwnershipDate",
        "Test-SourceProcessOwnershipCreationDateEquivalent",
        "Initialize-SourceProcessOwnershipExitNativeMethods",
        "Get-SourceProcessOwnershipNativeSafeHandleExitState",
        "Initialize-SourceJavaProbeRetainedNativeMethods",
        "Get-SourceJavaProbeRetainedNativeHandleExitState",
        "New-SourceJavaProbeNativeHandleAuthority",
        "Test-SourceJavaProbeNativeHandleAuthority",
        "Get-SourceJavaProbeNativeHandleExitState",
        "Release-SourceJavaProbeNativeHandleAuthority",
        "Get-SourceJavaProbeCleanupAuthorityState",
        "Test-SourceJavaProbeCleanupAuthorityBundle",
        "Complete-SourceJavaProbeCleanupAuthorityBundle",
        "New-SourceJavaProbeCleanupAuthorityBundle",
        "Get-SourceProcessOwnershipHandleExitState",
        "Test-SourceProcessOwnershipHandleExitStateMatchesIdentity",
        "Stop-SourceJavaProbeProcessExact",
        "Wait-SourceJavaProbeCleanupProof",
        "Invoke-SourceJavaExecutableProbe",
    )
    assert set(required) <= set(definitions)
    function_file = tmp_path / "real-java-probe-native-functions.ps1"
    harness_file = tmp_path / "invoke-real-java-probe-native.ps1"
    result_file = tmp_path / "real-java-probe-native-result.json"
    function_file.write_text(
        "\n\n".join(definitions[name] for name in required),
        encoding="utf-8",
    )
    harness_file.write_text(
        r'''
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$Scenario,
    [Parameter(Mandatory = $true)][string]$PowerShellExecutable
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$warnings = [System.Collections.Generic.List[string]]::new()
$killCalls = [System.Collections.Generic.List[int]]::new()
$waitCalls = [System.Collections.Generic.List[int]]::new()
$cleanupAttempts = [System.Collections.Generic.List[int]]::new()
$probeWaitCalls = [System.Collections.Generic.List[string]]::new()
$pauseCalls = [System.Collections.Generic.List[int]]::new()
$releaseCalls = [System.Collections.Generic.List[int]]::new()
$disposeCalls = [System.Collections.Generic.List[int]]::new()
$completionEvents = [System.Collections.Generic.List[string]]::new()
$failureObservation = [pscustomobject]@{
    process_table_visible = $null
    error = $null
}
$injectedState = [pscustomobject]@{
    real_process = $null
    ultra_fast_event = $null
    safe_handle = $null
    state_calls = 0
}
function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warnings.Add($Message)
}
function Test-RealProbeProcessTableVisible {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    $candidate = $null
    try {
        $candidate = [System.Diagnostics.Process]::GetProcessById($ProcessId)
        return -not $candidate.HasExited
    }
    catch [System.ArgumentException] {
        return $false
    }
    finally {
        try {
            if ($null -ne $candidate) {
                $candidate.Dispose()
            }
        }
        catch { }
    }
}
function Wait-RealProbeProcessTableAbsent {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $visible = Test-RealProbeProcessTableVisible -ProcessId $ProcessId
        if (-not $visible) {
            return $true
        }
        [Threading.Thread]::Sleep(10)
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}

$script:originalRelease =
    ${function:Release-SourceJavaProbeNativeHandleAuthority}
function Release-SourceJavaProbeNativeHandleAuthority {
    param(
        [Parameter(Mandatory = $true)]$Authority,
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$CapturedProcess
    )
    [void]$releaseCalls.Add([int]$CapturedProcess.Id)
    [void]$completionEvents.Add("release")
    & $script:originalRelease `
        -Authority $Authority `
        -CapturedProcess $CapturedProcess
}

$output = [ordered]@{
    scenario = $Scenario
    error = $null
    elapsed_ms = 0
    pid = 0
    running_state = $null
    exited_state = $null
    exited_result_count = 0
    process_table_visible = $null
    failure_observation_error = $null
    warnings = @()
    kill_calls = @()
    wait_calls = @()
    cleanup_attempts = @()
    probe_wait_calls = @()
    pause_calls = @()
    release_calls = @()
    dispose_calls = @()
    completion_events = @()
    child_exit_code = $null
    child_stderr = $null
}

if ($Scenario -eq "CONTROLLED_RETAINED_STATE") {
    $eventName = "CodexIssue0020_" + [Guid]::NewGuid().ToString("N")
    $exitEvent = [Threading.EventWaitHandle]::new(
        $false,
        [Threading.EventResetMode]::ManualReset,
        $eventName)
    $process = [System.Diagnostics.Process]::new()
    $authority = $null
    try {
        $info = [System.Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $PowerShellExecutable
        $info.Arguments = '-NoProfile -NonInteractive -Command ' +
            '"$e=[Threading.EventWaitHandle]::OpenExisting(' +
            '$env:CODEX_ISSUE0020_EVENT);[void]$e.WaitOne();$e.Dispose()"'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.EnvironmentVariables["CODEX_ISSUE0020_EVENT"] = $eventName
        $process.StartInfo = $info
        if (-not $process.Start()) {
            throw "real retained-state child did not start"
        }
        $output.pid = [int]$process.Id
        $safeHandle = $process.SafeHandle
        $authority = New-SourceJavaProbeNativeHandleAuthority `
            -CapturedProcess $process `
            -SafeHandle $safeHandle
        $output.running_state = Get-SourceJavaProbeNativeHandleExitState `
            -Authority $authority `
            -CapturedProcess $process
        [void]$exitEvent.Set()
        $process.WaitForExit()
        $output.process_table_visible = -not (
            Wait-RealProbeProcessTableAbsent -ProcessId $output.pid)
        $exitedResults = @(
            Get-SourceJavaProbeNativeHandleExitState `
                -Authority $authority `
                -CapturedProcess $process)
        $output.exited_result_count = $exitedResults.Count
        if ($exitedResults.Count -eq 1) {
            $output.exited_state = $exitedResults[0]
        }
    }
    catch {
        $output.error = $_.Exception.Message
    }
    finally {
        if ($null -ne $authority -and
            -not [bool]$authority.reference_released) {
            Release-SourceJavaProbeNativeHandleAuthority `
                -Authority $authority `
                -CapturedProcess $process
        }
        if (-not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
        }
        [void]$disposeCalls.Add([int]$output.pid)
        $process.Dispose()
        $exitEvent.Dispose()
    }
}
else {
    $childArguments =
        '-NoProfile -NonInteractive -Command "Start-Sleep -Milliseconds 1000"'
    $childEventName = $null
    $childSignalPath = $null
    $redirectStandardInput = $false
    if ($Scenario -eq "ULTRA_FAST_EXIT") {
        $eventName = "CodexIssue0020_" + [Guid]::NewGuid().ToString("N")
        $injectedState.ultra_fast_event = [Threading.EventWaitHandle]::new(
            $false,
            [Threading.EventResetMode]::ManualReset,
            $eventName)
        $childArguments = '-NoProfile -NonInteractive -Command ' +
            '"$e=[Threading.EventWaitHandle]::OpenExisting(' +
            '$env:CODEX_ISSUE0020_EVENT);[void]$e.WaitOne();$e.Dispose()"'
        $childEventName = $eventName
    }
    elseif ($Scenario -eq "POST_EXIT_WAIT_FAILURE") {
        $childSignalPath = Join-Path `
            ([IO.Path]::GetDirectoryName($ResultFile)) `
            ("issue0020-exit-" + [Guid]::NewGuid().ToString("N"))
        $childArguments = '-NoProfile -NonInteractive -Command ' +
            '"while (-not [IO.File]::Exists(' +
            '$env:CODEX_ISSUE0020_SIGNAL)) {' +
            '[Threading.Thread]::Sleep(10)}"'
    }
    $processFactory = {
        param($IgnoredStartInfo)
        $candidate = [System.Diagnostics.Process]::new()
        $info = [System.Diagnostics.ProcessStartInfo]::new()
        $info.FileName = $PowerShellExecutable
        $info.Arguments = $childArguments
        $info.RedirectStandardInput = $redirectStandardInput
        if ($null -ne $childEventName) {
            $info.EnvironmentVariables["CODEX_ISSUE0020_EVENT"] =
                $childEventName
        }
        if ($null -ne $childSignalPath) {
            $info.EnvironmentVariables["CODEX_ISSUE0020_SIGNAL"] =
                $childSignalPath
        }
        $info.UseShellExecute = $false
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.CreateNoWindow = $true
        $candidate.StartInfo = $info
        $injectedState.real_process = $candidate
        return $candidate
    }.GetNewClosure()
    $startProvider = {
        param($Candidate)
        $started = [bool]$Candidate.Start()
        if ($started) {
            $output.pid = [int]$Candidate.Id
            $injectedState.safe_handle = $Candidate.SafeHandle
        }
        return $started
    }.GetNewClosure()
    $handleStateProvider = {
        param($Candidate)
        $injectedState.state_calls += 1
        if ($Scenario -eq "ULTRA_FAST_EXIT" -and
            $injectedState.state_calls -eq 1) {
            [void]$injectedState.ultra_fast_event.Set()
            $Candidate.WaitForExit()
        }
        return Get-SourceProcessOwnershipNativeSafeHandleExitState `
            -SafeHandle $injectedState.safe_handle
    }.GetNewClosure()
    $killProvider = {
        param($Candidate)
        [void]$killCalls.Add([int]$Candidate.Id)
        $Candidate.Kill()
        return $true
    }.GetNewClosure()
    $cleanupWaitProvider = {
        param($Candidate, $Milliseconds)
        [void]$waitCalls.Add([int]$Candidate.Id)
        return [bool]$Candidate.WaitForExit([int]$Milliseconds)
    }.GetNewClosure()
    $waitProvider = $null
    if ($Scenario -eq "POST_EXIT_WAIT_FAILURE") {
        $waitProvider = {
            param($Candidate, $Milliseconds)
            if ($null -ne $Milliseconds) {
                [void]$probeWaitCalls.Add("timed")
                [IO.File]::WriteAllText(
                    $childSignalPath,
                    "exit",
                    [Text.UTF8Encoding]::new($false))
                return [bool]$Candidate.WaitForExit([int]$Milliseconds)
            }
            [void]$probeWaitCalls.Add("flush")
            $Candidate.WaitForExit()
            $pidValue = [int]$Candidate.Id
            try {
                $failureObservation.process_table_visible = -not (
                    Wait-RealProbeProcessTableAbsent -ProcessId $pidValue)
            }
            catch {
                $failureObservation.error = $_.Exception.Message
            }
            throw "controlled post-exit wait failure"
        }.GetNewClosure()
    }
    $streamTaskProvider = {
        param($Candidate)
        return [pscustomobject]@{
            stdout_task = $Candidate.StandardOutput.ReadToEndAsync()
            stderr_task = $Candidate.StandardError.ReadToEndAsync()
        }
    }
    $script:originalStop = ${function:Stop-SourceJavaProbeProcessExact}
    $cleanupAttemptProvider = {
        param(
            $Candidate,
            $BoundIdentity,
            $ExpectedProcessId,
            $StateProvider,
            $Terminator,
            $CleanupWaiter)
        [void]$cleanupAttempts.Add($cleanupAttempts.Count + 1)
        return & $script:originalStop `
            -CapturedProcess $Candidate `
            -BoundIdentity $BoundIdentity `
            -ExpectedProcessId $ExpectedProcessId `
            -HandleStateProvider $StateProvider `
            -KillProvider $Terminator `
            -WaitProvider $CleanupWaiter
    }.GetNewClosure()
    $pauseAction = {
        [void]$pauseCalls.Add($pauseCalls.Count + 1)
    }.GetNewClosure()
    $disposeProvider = {
        param($Candidate)
        [void]$disposeCalls.Add([int]$Candidate.Id)
        [void]$completionEvents.Add("dispose")
        if ($Candidate.HasExited) {
            $output.child_exit_code = [int]$Candidate.ExitCode
            $output.child_stderr = [string]$Candidate.StandardError.ReadToEnd()
        }
        $Candidate.Dispose()
    }.GetNewClosure()
    $streamResultProvider = {
        param($StdoutTask, $StderrTask)
        return [pscustomobject]@{
            stdout = [string]$StdoutTask.GetAwaiter().GetResult()
            stderr = [string]$StderrTask.GetAwaiter().GetResult()
        }
    }.GetNewClosure()
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        Invoke-SourceJavaExecutableProbe `
            -ExecutablePath $PowerShellExecutable `
            -Arguments @("-XshowSettings:properties", "-version") `
            -TimeoutMilliseconds 5000 `
            -ProcessFactory $processFactory `
            -StartProvider $startProvider `
            -HandleStateProvider $handleStateProvider `
            -StreamTaskProvider $streamTaskProvider `
            -WaitProvider $waitProvider `
            -StreamResultProvider $streamResultProvider `
            -KillProvider $killProvider `
            -CleanupWaitProvider $cleanupWaitProvider `
            -CleanupAttemptProvider $cleanupAttemptProvider `
            -PauseAction $pauseAction `
            -DisposeProvider $disposeProvider | Out-Null
    }
    catch {
        $output.error = $_.Exception.Message
    }
    finally {
        $stopwatch.Stop()
        $output.elapsed_ms = [long]$stopwatch.ElapsedMilliseconds
        if ($output.pid -eq 0 -and $null -ne $injectedState.real_process) {
            try { $output.pid = [int]$injectedState.real_process.Id } catch { }
        }
        if ($null -ne $injectedState.ultra_fast_event) {
            $injectedState.ultra_fast_event.Dispose()
        }
    }
    $output.process_table_visible = $failureObservation.process_table_visible
    $output.failure_observation_error = $failureObservation.error
}

$output.warnings = @($warnings)
$output.kill_calls = @($killCalls)
$output.wait_calls = @($waitCalls)
$output.cleanup_attempts = @($cleanupAttempts)
$output.probe_wait_calls = @($probeWaitCalls)
$output.pause_calls = @($pauseCalls)
$output.release_calls = @($releaseCalls)
$output.dispose_calls = @($disposeCalls)
$output.completion_events = @($completionEvents)
[System.IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]$output | ConvertTo-Json -Depth 20 -Compress),
    [System.Text.UTF8Encoding]::new($false))
'''.strip(),
        encoding="utf-8",
    )
    command = [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-Scenario",
            scenario,
            "-PowerShellExecutable",
            str(Path(shutil.which("powershell.exe") or "powershell.exe").resolve()),
        ]
    harness = subprocess.Popen(
        command,
        cwd=tmp_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        stdout, stderr = harness.communicate(timeout=20)
    except subprocess.TimeoutExpired as timeout:
        subprocess.run(
            ["taskkill.exe", "/PID", str(harness.pid), "/T", "/F"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        harness.communicate(timeout=10)
        raise AssertionError(
            f"isolated real-process harness stalled in {scenario}"
        ) from timeout
    assert harness.returncode == 0, stderr or stdout
    assert result_file.is_file(), stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_real_java_probe_pre_bind_authority_failure_harness(
    tmp_path: Path,
) -> dict[str, Any]:
    powershell = shutil.which("powershell.exe")
    if powershell is None:
        pytest.skip("Windows PowerShell is not available")

    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    required = (
        "Get-SourceProcessOwnershipCanonicalPath",
        "Test-SourceProcessOwnershipInteger",
        "ConvertTo-SourceProcessOwnershipDate",
        "Test-SourceProcessOwnershipCreationDateEquivalent",
        "Initialize-SourceProcessOwnershipExitNativeMethods",
        "Get-SourceProcessOwnershipNativeSafeHandleExitState",
        "Initialize-SourceJavaProbeRetainedNativeMethods",
        "Get-SourceJavaProbeRetainedNativeHandleExitState",
        "New-SourceJavaProbeNativeHandleAuthority",
        "Test-SourceJavaProbeNativeHandleAuthority",
        "Get-SourceJavaProbeNativeHandleExitState",
        "Release-SourceJavaProbeNativeHandleAuthority",
        "Get-SourceJavaProbeCleanupAuthorityState",
        "Test-SourceJavaProbeCleanupAuthorityBundle",
        "Complete-SourceJavaProbeCleanupAuthorityBundle",
        "New-SourceJavaProbeCleanupAuthorityBundle",
        "Get-SourceProcessOwnershipHandleExitState",
        "Test-SourceProcessOwnershipHandleExitStateMatchesIdentity",
        "Stop-SourceJavaProbeProcessExact",
        "Wait-SourceJavaProbeCleanupProof",
        "Invoke-SourceJavaExecutableProbe",
    )
    assert set(required) <= set(definitions)
    function_file = tmp_path / "pre-bind-authority-functions.ps1"
    harness_file = tmp_path / "invoke-pre-bind-authority-failure.ps1"
    result_file = tmp_path / "pre-bind-authority-result.json"
    function_file.write_text(
        "\n\n".join(definitions[name] for name in required),
        encoding="utf-8",
    )
    harness_file.write_text(
        r'''
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$PowerShellExecutable
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$warnings = [System.Collections.Generic.List[string]]::new()
$killCalls = [System.Collections.Generic.List[int]]::new()
$waitCalls = [System.Collections.Generic.List[int]]::new()
$disposeCalls = [System.Collections.Generic.List[int]]::new()
$cleanupAttempts = [System.Collections.Generic.List[object]]::new()
$pauseCalls = [System.Collections.Generic.List[int]]::new()
$productionReleaseCalls = [System.Collections.Generic.List[int]]::new()
$state = [pscustomobject]@{
    real_process = $null
    supervisor_safe_handle = $null
    supervisor_native_handle = [IntPtr]::Zero
    supervisor_reference_retained = $false
    supervisor_release_count = 0
    process_disposed = $false
}

function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warnings.Add($Message)
}

$output = [ordered]@{
    error = $null
    watchdog_triggered = $false
    exact_process_reference = $false
    safe_handle_type = $null
    safe_handle_valid = $false
    authority_returned = $false
    pid = 0
    running_state = $null
    exited_state = $null
    child_has_exited = $false
    cleanup_attempts = @()
    warnings = @()
    pause_calls = @()
    kill_calls = @()
    wait_calls = @()
    dispose_calls = @()
    supervisor_release_count = 0
    production_release_calls = @()
    harness_forced_cleanup = $false
}

$writeEvidence = {
    $output.cleanup_attempts = @($cleanupAttempts)
    $output.warnings = @($warnings)
    $output.pause_calls = @($pauseCalls)
    $output.kill_calls = @($killCalls)
    $output.wait_calls = @($waitCalls)
    $output.dispose_calls = @($disposeCalls)
    $output.supervisor_release_count =
        [int]$state.supervisor_release_count
    $output.production_release_calls = @($productionReleaseCalls)
    [IO.File]::WriteAllText(
        $ResultFile,
        ([pscustomobject]$output | ConvertTo-Json -Depth 20 -Compress),
        [Text.UTF8Encoding]::new($false))
}.GetNewClosure()

$eventName = "CodexPreBindProbe_" + [Guid]::NewGuid().ToString("N")
$exitEvent = [Threading.EventWaitHandle]::new(
    $false,
    [Threading.EventResetMode]::ManualReset,
    $eventName)

$processFactory = {
    param($IgnoredStartInfo)
    $candidate = [Diagnostics.Process]::new()
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $PowerShellExecutable
    $info.Arguments = '-NoProfile -NonInteractive -Command ' +
        '"$e=[Threading.EventWaitHandle]::OpenExisting(' +
        '$env:CODEX_PRE_BIND_EVENT);[void]$e.WaitOne();$e.Dispose()"'
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $info.EnvironmentVariables["CODEX_PRE_BIND_EVENT"] = $eventName
    $candidate.StartInfo = $info
    $state.real_process = $candidate
    return $candidate
}.GetNewClosure()

$startProvider = {
    param($Candidate)
    $started = [bool]$Candidate.Start()
    if ($started) {
        $output.pid = [int]$Candidate.Id
    }
    return $started
}.GetNewClosure()

$originalProductionRelease =
    ${function:Release-SourceJavaProbeNativeHandleAuthority}
function Release-SourceJavaProbeNativeHandleAuthority {
    param(
        [Parameter(Mandatory = $true)]$Authority,
        [Parameter(Mandatory = $true)]
        [Diagnostics.Process]$CapturedProcess
    )
    [void]$productionReleaseCalls.Add([int]$CapturedProcess.Id)
    & $originalProductionRelease `
        -Authority $Authority `
        -CapturedProcess $CapturedProcess
}

function New-SourceJavaProbeNativeHandleAuthority {
    param(
        [Parameter(Mandatory = $true)]
        [Diagnostics.Process]$CapturedProcess,
        [Parameter(Mandatory = $true)]
        [Microsoft.Win32.SafeHandles.SafeProcessHandle]$SafeHandle
    )
    $output.exact_process_reference = [object]::ReferenceEquals(
        $state.real_process,
        $CapturedProcess)
    $output.safe_handle_type = $SafeHandle.GetType().FullName
    $output.safe_handle_valid =
        -not $SafeHandle.IsInvalid -and -not $SafeHandle.IsClosed
    if (-not $output.exact_process_reference -or
        -not $output.safe_handle_valid) {
        throw "pre-bind real process authority is unavailable"
    }
    $runningState = Get-SourceProcessOwnershipNativeSafeHandleExitState `
        -SafeHandle $SafeHandle
    if ($runningState.IsExited -or
        [int]$runningState.Pid -ne [int]$CapturedProcess.Id -or
        $null -eq $runningState.CreationDate -or
        $null -ne $runningState.ExitDate) {
        throw "pre-bind real process authority is not running"
    }
    $output.running_state = $runningState
    $referenceRetained = $false
    $SafeHandle.DangerousAddRef([ref]$referenceRetained)
    if (-not $referenceRetained) {
        throw "pre-bind supervisor authority was not retained"
    }
    $state.supervisor_safe_handle = $SafeHandle
    $state.supervisor_native_handle = $SafeHandle.DangerousGetHandle()
    $state.supervisor_reference_retained = $true
    throw "controlled authority construction failure before return"
}

$killProvider = {
    param($Candidate)
    [void]$killCalls.Add([int]$Candidate.Id)
    $Candidate.Kill()
    return $true
}.GetNewClosure()

$waitProvider = {
    param($Candidate, $Milliseconds)
    [void]$waitCalls.Add([int]$Candidate.Id)
    return [bool]$Candidate.WaitForExit([int]$Milliseconds)
}.GetNewClosure()

$disposeProvider = {
    param($Candidate)
    [void]$disposeCalls.Add([int]$Candidate.Id)
    $Candidate.Dispose()
    $state.process_disposed = $true
}.GetNewClosure()

$originalStop = ${function:Stop-SourceJavaProbeProcessExact}
$cleanupAttemptProvider = {
    param(
        $Candidate,
        $BoundIdentity,
        $ExpectedProcessId,
        $StateProvider,
        $Terminator,
        $CleanupWaiter)
    $record = [ordered]@{
        expected_process_id = [int]$ExpectedProcessId
        bound_identity_is_null = $null -eq $BoundIdentity
        bound_pid = if ($null -eq $BoundIdentity) {
            $null
        }
        else {
            [int]$BoundIdentity.Pid
        }
        result = $false
    }
    $result = & $originalStop `
        -CapturedProcess $Candidate `
        -BoundIdentity $BoundIdentity `
        -ExpectedProcessId $ExpectedProcessId `
        -HandleStateProvider $StateProvider `
        -KillProvider $Terminator `
        -WaitProvider $CleanupWaiter
    $record.result = [bool]$result
    [void]$cleanupAttempts.Add([pscustomobject]$record)
    return [bool]$result
}.GetNewClosure()

$pauseAction = {
    [void]$pauseCalls.Add($pauseCalls.Count + 1)
    $output.watchdog_triggered = $true
    try {
        $output.exited_state =
            Get-SourceJavaProbeRetainedNativeHandleExitState `
                -NativeHandle $state.supervisor_native_handle
        $output.child_has_exited = [bool]$output.exited_state.IsExited
    }
    finally {
        if ($state.supervisor_reference_retained) {
            $state.supervisor_safe_handle.DangerousRelease()
            $state.supervisor_reference_retained = $false
            $state.supervisor_release_count += 1
        }
        if (-not $state.process_disposed) {
            & $disposeProvider $state.real_process
        }
        $exitEvent.Dispose()
        & $writeEvidence
    }
    [Environment]::Exit(86)
}.GetNewClosure()

try {
    $candidate = & $processFactory $null
    if (-not (& $startProvider $candidate)) {
        throw "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
    }
    New-SourceJavaProbeCleanupAuthorityBundle `
        -CapturedProcess $candidate `
        -HandleStateProvider $null `
        -KillProvider $killProvider `
        -WaitProvider $waitProvider `
        -CleanupAttemptProvider $cleanupAttemptProvider `
        -PauseAction $pauseAction `
        -DisposeProvider $disposeProvider | Out-Null
}
catch {
    $output.error = $_.Exception.Message
}
finally {
    if ($state.supervisor_reference_retained) {
        try {
            $output.exited_state =
                Get-SourceJavaProbeRetainedNativeHandleExitState `
                    -NativeHandle $state.supervisor_native_handle
            $output.child_has_exited = [bool]$output.exited_state.IsExited
        }
        finally {
            $state.supervisor_safe_handle.DangerousRelease()
            $state.supervisor_reference_retained = $false
            $state.supervisor_release_count += 1
        }
    }
    if ($null -ne $state.real_process -and
        -not $state.process_disposed) {
        if (-not $state.real_process.HasExited) {
            $output.harness_forced_cleanup = $true
            $state.real_process.Kill()
            $state.real_process.WaitForExit()
        }
        & $disposeProvider $state.real_process
    }
    $exitEvent.Dispose()
    & $writeEvidence
}
'''.strip(),
        encoding="utf-8",
    )
    command = [
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(harness_file),
        "-FunctionFile",
        str(function_file),
        "-ResultFile",
        str(result_file),
        "-PowerShellExecutable",
        str(Path(powershell).resolve()),
    ]
    harness = subprocess.Popen(
        command,
        cwd=tmp_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        stdout, stderr = harness.communicate(timeout=20)
    except subprocess.TimeoutExpired as timeout:
        subprocess.run(
            ["taskkill.exe", "/PID", str(harness.pid), "/T", "/F"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        stdout, stderr = harness.communicate(timeout=10)
        raise AssertionError(
            "isolated pre-bind harness exceeded its emergency ceiling"
        ) from timeout
    assert result_file.is_file(), stderr or stdout
    result = json.loads(result_file.read_text(encoding="utf-8-sig"))
    result["harness_returncode"] = harness.returncode
    return result


def _run_v4_native_start_authority_harness(
    tmp_path: Path,
    *,
    scenario: str,
) -> dict[str, Any]:
    powershell = shutil.which("powershell.exe")
    if powershell is None:
        pytest.skip("Windows PowerShell is not available")

    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    required = (
        "Get-SourceProcessOwnershipCanonicalPath",
        "Test-SourceProcessOwnershipInteger",
        "ConvertTo-SourceProcessOwnershipDate",
        "Initialize-SourceJavaProbeNativeStartAuthority",
        "Invoke-SourceJavaNativeExecutableProbe",
        "Invoke-SourceJavaExecutableProbe",
    )
    assert set(required) <= set(definitions)
    def top_level_slice(name: str, next_name: str) -> str:
        marker = f"function {name} {{"
        next_marker = f"\nfunction {next_name} {{"
        start = source.index(marker)
        end = source.index(next_marker, start)
        return source[start:end].rstrip()

    initializer = top_level_slice(required[3], required[4])
    native_runner = top_level_slice(required[4], required[5])
    wrapper = top_level_slice(required[5], "Get-SourceJavaHomeFromProbeResult")
    instrumentation_counts: dict[str, int] = {}
    if scenario.startswith("V4_CLOSE_"):
        using_anchor = "using System.ComponentModel;"
        assert initializer.count(using_anchor) == 1
        initializer = initializer.replace(
            using_anchor,
            using_anchor + "\nusing System.Collections.Generic;",
            1,
        )
        ledger_anchor = "    private readonly NativeResourceLedger ledger;"
        ledger_audit = r'''    public static string AuditPath;
    private static readonly Dictionary<string, object> AuditReferences =
        new Dictionary<string, object>(StringComparer.Ordinal);

    private static RawHandleLease FindRawLease(
        NativeResourceLedger ownedLedger,
        string resourceId)
    {
        switch (resourceId)
        {
            case "process": return ownedLedger.Process;
            case "thread": return ownedLedger.Thread;
            case "stdout-read": return ownedLedger.StdoutRead;
            case "stdout-write": return ownedLedger.StdoutWrite;
            case "stderr-read": return ownedLedger.StderrRead;
            case "stderr-write": return ownedLedger.StderrWrite;
            case "child-stdin": return ownedLedger.ChildStdin;
            default: return null;
        }
    }

    private static ManagedLease FindManagedLease(
        NativeResourceLedger ownedLedger,
        string resourceId)
    {
        switch (resourceId)
        {
            case "stdout-reader": return ownedLedger.StdoutReader;
            case "stdout-stream": return ownedLedger.StdoutStream;
            case "stdout-wrapper": return ownedLedger.StdoutWrapper;
            case "stderr-reader": return ownedLedger.StderrReader;
            case "stderr-stream": return ownedLedger.StderrStream;
            case "stderr-wrapper": return ownedLedger.StderrWrapper;
            default: return null;
        }
    }

    private static void EmitLeaseAudit(
        NativeResourceLedger ownedLedger,
        string resourceId,
        int attempt,
        string moment)
    {
        if (String.IsNullOrWhiteSpace(AuditPath)) return;
        RawHandleLease raw = FindRawLease(ownedLedger, resourceId);
        ManagedLease managed = FindManagedLease(ownedLedger, resourceId);
        bool referencePresent = managed != null && managed.Reference != null;
        bool sameReference = false;
        if (referencePresent)
        {
            object firstReference;
            if (!AuditReferences.TryGetValue(resourceId, out firstReference))
            {
                AuditReferences[resourceId] = managed.Reference;
                firstReference = managed.Reference;
            }
            sameReference = Object.ReferenceEquals(firstReference, managed.Reference);
        }
        string line = String.Join("|", new string[] {
            moment,
            resourceId,
            attempt.ToString(CultureInfo.InvariantCulture),
            ownedLedger.Phase,
            raw == null ? "0" : raw.Value.ToInt64().ToString(CultureInfo.InvariantCulture),
            raw == null ? "" : raw.State.ToString(),
            raw == null ? "0" : raw.CloseAttempts.ToString(CultureInfo.InvariantCulture),
            raw == null ? "0" : raw.InjectedFailures.ToString(CultureInfo.InvariantCulture),
            raw == null ? "False" : raw.RealCloseAttempted.ToString(),
            raw == null ? "0" : raw.LastWin32Error.ToString(CultureInfo.InvariantCulture),
            referencePresent.ToString(),
            sameReference.ToString(),
            managed == null ? "" : managed.State.ToString(),
            managed == null ? "0" : managed.DisposeAttempts.ToString(CultureInfo.InvariantCulture),
            managed == null ? "0" : managed.InjectedFailures.ToString(CultureInfo.InvariantCulture),
            managed == null ? "False" : managed.RealDisposeAttempted.ToString(),
            ownedLedger.StartupResourcesComplete.ToString(),
            ownedLedger.OutputResourcesComplete.ToString(),
            ownedLedger.AllResourcesComplete.ToString(),
            ownedLedger.ExitProven.ToString(),
            ownedLedger.Pid.ToString(CultureInfo.InvariantCulture),
            ownedLedger.CreationFileTime.ToString(CultureInfo.InvariantCulture),
            ownedLedger.ExitFileTime.ToString(CultureInfo.InvariantCulture)
        });
        File.AppendAllText(
            AuditPath,
            line + Environment.NewLine,
            Encoding.UTF8);
    }

    private readonly NativeResourceLedger ledger;'''
        assert initializer.count(ledger_anchor) == 1
        initializer = initializer.replace(ledger_anchor, ledger_audit, 1)
        audit_replacements = (
            (
                """            lease.Value = IntPtr.Zero;
            lease.LastWin32Error = 0;
            lease.State = RawLeaseState.CLOSED;""",
                """            lease.Value = IntPtr.Zero;
            lease.LastWin32Error = 0;
            lease.State = RawLeaseState.CLOSED;
            EmitLeaseAudit(
                ledger,
                lease.ResourceId,
                lease.CloseAttempts,
                \"FINAL\");""",
            ),
            (
                """            lease.Reference = null;
            lease.State = ManagedLeaseState.DISPOSED;""",
                """            lease.Reference = null;
            lease.State = ManagedLeaseState.DISPOSED;
            EmitLeaseAudit(
                ledger,
                lease.ResourceId,
                lease.DisposeAttempts,
                \"FINAL\");""",
            ),
            (
                """        lease.State = RawLeaseState.CLOSE_TRUTH_BROKEN;
        ledger.CloseTruthBroken = true;
        ledger.Phase = \"CLOSE_TRUTH_BROKEN\";
        throw new CloseTruthBrokenException(lease.ResourceId);""",
                """        lease.State = RawLeaseState.CLOSE_TRUTH_BROKEN;
        ledger.CloseTruthBroken = true;
        ledger.Phase = \"CLOSE_TRUTH_BROKEN\";
        EmitLeaseAudit(
            ledger,
            lease.ResourceId,
            lease.CloseAttempts,
            \"BROKEN\");
        throw new CloseTruthBrokenException(lease.ResourceId);""",
            ),
            (
                """        lease.State = ManagedLeaseState.CLOSE_TRUTH_BROKEN;
        ledger.CloseTruthBroken = true;
        ledger.Phase = \"CLOSE_TRUTH_BROKEN\";
        throw new CloseTruthBrokenException(lease.ResourceId);""",
                """        lease.State = ManagedLeaseState.CLOSE_TRUTH_BROKEN;
        ledger.CloseTruthBroken = true;
        ledger.Phase = \"CLOSE_TRUTH_BROKEN\";
        EmitLeaseAudit(
            ledger,
            lease.ResourceId,
            lease.DisposeAttempts,
            \"BROKEN\");
        throw new CloseTruthBrokenException(lease.ResourceId);""",
            ),
        )
        for old, new in audit_replacements:
            assert initializer.count(old) == 1
            initializer = initializer.replace(old, new, 1)
        observer_anchor = """        try
        {
            ledger.CloseObserver(resourceId, attempt);
        }
        catch
        {
            // The observer is non-authoritative and cannot alter close truth.
        }"""
        observer_with_audit = observer_anchor + """
        EmitLeaseAudit(ledger, resourceId, attempt, \"OBSERVER\");"""
        assert initializer.count(observer_anchor) == 1
        initializer = initializer.replace(observer_anchor, observer_with_audit, 1)
        phase_anchor = """            ledger.Phase = \"CLOSED\";
        }
    }

    private static void FailFastIfCloseTruthBroken("""
        phase_audit = """            ledger.Phase = \"CLOSED\";
            EmitLeaseAudit(ledger, \"phase\", 0, \"FINAL_PHASE\");
        }
    }

    private static void FailFastIfCloseTruthBroken("""
        assert initializer.count(phase_anchor) == 1
        initializer = initializer.replace(phase_anchor, phase_audit, 1)
        if scenario in {
            "V4_CLOSE_RAW_TRUTH_BROKEN",
            "V4_CLOSE_MANAGED_TRUTH_BROKEN",
        }:
            fail_fast_anchor = """        if (ledger.CloseTruthBroken)
        {
            Environment.FailFast(CloseTruthFailure);
        }"""
            fail_fast_audit = """        if (ledger.CloseTruthBroken)
        {
            EmitLeaseAudit(
                ledger,
                \"fail-fast\",
                0,
                CloseTruthFailure);
            Environment.FailFast(CloseTruthFailure);
        }"""
            assert initializer.count(fail_fast_anchor) == 1
            initializer = initializer.replace(
                fail_fast_anchor,
                fail_fast_audit,
                1,
            )
            adapter_anchor = "    private readonly NativeResourceLedger ledger;"
            adapter_helpers = r'''    public static string TestFailureTarget;
    public static int TestAdapterCalls;

    private static bool CloseRawWithTestAdapter(
        string resourceId,
        IntPtr handle)
    {
        if (String.Equals(
                resourceId,
                TestFailureTarget,
                StringComparison.Ordinal))
        {
            TestAdapterCalls += 1;
            return false;
        }
        return CloseHandle(handle);
    }

    private static void DisposeWithTestAdapter(
        string resourceId,
        IDisposable reference)
    {
        if (String.Equals(
                resourceId,
                TestFailureTarget,
                StringComparison.Ordinal))
        {
            TestAdapterCalls += 1;
            throw new InvalidOperationException(
                "Injected managed close failure.");
        }
        reference.Dispose();
    }

    private readonly NativeResourceLedger ledger;'''
            assert initializer.count(adapter_anchor) == 1
            initializer = initializer.replace(
                adapter_anchor,
                adapter_helpers,
                1,
            )
            audit_tail = """            ownedLedger.ExitFileTime.ToString(CultureInfo.InvariantCulture)
        });"""
            audit_adapter_tail = """            ownedLedger.ExitFileTime.ToString(CultureInfo.InvariantCulture),
            TestAdapterCalls.ToString(CultureInfo.InvariantCulture)
        });"""
            assert initializer.count(audit_tail) == 1
            initializer = initializer.replace(
                audit_tail,
                audit_adapter_tail,
                1,
            )
            if scenario == "V4_CLOSE_RAW_TRUTH_BROKEN":
                raw_close = "closed = CloseHandle(lease.Value);"
                assert initializer.count(raw_close) == 1
                initializer = initializer.replace(
                    raw_close,
                    "closed = CloseRawWithTestAdapter("
                    "lease.ResourceId, lease.Value);",
                    1,
                )
            else:
                managed_close = "lease.Reference.Dispose();"
                assert initializer.count(managed_close) == 1
                initializer = initializer.replace(
                    managed_close,
                    "DisposeWithTestAdapter("
                    "lease.ResourceId, lease.Reference);",
                    1,
                )
    if scenario == "PRE_READY_FAILURE_OBSERVATION_LEGACY":
        replacements = (
            (
                "    private string phase;",
                """    private string phase;
    public static string AuditPath;
    private static int auditPid;
    private static long auditCreation;
    private static long auditExit;
    private static void EmitAudit(
        string stage,
        int observedPid,
        long observedCreation,
        long observedExit,
        bool streamsClosed,
        bool processClosed)
    {
        if (observedPid > 0) auditPid = observedPid;
        if (observedCreation > 0) auditCreation = observedCreation;
        if (observedExit > 0) auditExit = observedExit;
        if (!String.IsNullOrWhiteSpace(AuditPath))
        {
            try
            {
                File.AppendAllText(
                    AuditPath,
                    stage + "|" +
                    auditPid.ToString(CultureInfo.InvariantCulture) + "|" +
                    (auditCreation > 0 ? FormatFileTime(auditCreation) : "") + "|" +
                    (auditExit > 0 ? FormatFileTime(auditExit) : "") + "|" +
                    streamsClosed.ToString() + "|" +
                    processClosed.ToString() + Environment.NewLine,
                    Encoding.UTF8);
            }
            catch { }
        }
    }""",
            ),
            (
                "            exactPid = checked((int)processInformation.dwProcessId);",
                """            exactPid = checked((int)processInformation.dwProcessId);
            EmitAudit(
                \"AFTER_CREATE_PROCESS\",
                exactPid,
                0,
                0,
                false,
                false);""",
            ),
            (
                "            exactCreation = ReadExactCreationFileTime(rawProcessHandle, exactPid);",
                """            exactCreation = ReadExactCreationFileTime(rawProcessHandle, exactPid);
            EmitAudit(
                \"AFTER_IDENTITY_BIND\",
                exactPid,
                exactCreation,
                0,
                false,
                false);""",
            ),
            (
                """                if (actualPid == checked((uint)boundPid) &&
                    GetProcessTimes(
                        process,
                        out creation,
                        out exit,
                        out kernel,
                        out user) &&
                    creation == boundCreation &&
                    exit >= creation &&
                    exit > 0 &&
                    wait == WaitObject0)
                {
                    break;
                }""",
                """                if (actualPid == checked((uint)boundPid) &&
                    GetProcessTimes(
                        process,
                        out creation,
                        out exit,
                        out kernel,
                        out user) &&
                    creation == boundCreation &&
                    exit >= creation &&
                    exit > 0 &&
                    wait == WaitObject0)
                {
                    EmitAudit(
                        \"EXIT_PROVEN\",
                        boundPid,
                        boundCreation,
                        exit,
                        false,
                        false);
                    break;
                }""",
            ),
            (
                "        DisposeQuietly(activeStderrHandle);",
                """        DisposeQuietly(activeStderrHandle);
        EmitAudit(
            \"STREAMS_CLOSED\",
            boundPid,
            boundCreation,
            auditExit,
            true,
            false);""",
            ),
            (
                """                else
                {
                    TryCloseRawHandle(ref rawProcessHandle);
                }
            }""",
                """                else
                {
                    TryCloseRawHandle(ref rawProcessHandle);
                }
                EmitAudit(
                    \"PROCESS_HANDLE_CLOSED\",
                    exactPid,
                    exactCreation,
                    auditExit,
                    true,
                    true);
            }""",
            ),
        )
        for old, new in replacements:
            count = initializer.count(old)
            instrumentation_counts[old.splitlines()[0].strip()] = count
            assert count == 1
            initializer = initializer.replace(old, new, 1)

        old_name = "function Invoke-SourceJavaNativeExecutableProbe {"
        assert native_runner.count(old_name) == 1
        native_runner = native_runner.replace(
            old_name,
            "function Invoke-SourceJavaNativeExecutableProbeWithStageFailure {",
            1,
        )
        old_param = (
            "        [Parameter(Mandatory = $true)]"
            "[int]$TimeoutMilliseconds\n    )"
        )
        new_param = (
            "        [Parameter(Mandatory = $true)]"
            "[int]$TimeoutMilliseconds,\n"
            "        [Parameter(Mandatory = $true)]"
            "[scriptblock]$StageFailureProvider\n    )"
        )
        assert native_runner.count(old_param) == 1
        native_runner = native_runner.replace(old_param, new_param, 1)
        old_start = """            [SourceJavaProbeNativeStartAuthority]::Start(
                $ExecutablePath,
                $Arguments)"""
        new_start = """            [SourceJavaProbeNativeStartAuthority]::Start(
                $ExecutablePath,
                $Arguments,
                [Func[string, bool]]$StageFailureProvider)"""
        assert native_runner.count(old_start) == 1
        native_runner = native_runner.replace(old_start, new_start, 1)

    function_file = tmp_path / f"v4-native-functions-{scenario}.ps1"
    harness_file = tmp_path / f"invoke-v4-native-{scenario}.ps1"
    result_file = tmp_path / f"v4-native-result-{scenario}.json"
    fixture_file = tmp_path / "native-probe-fixture.exe"
    functions = [definitions[name] for name in required[:3]]
    functions.extend((initializer, native_runner, wrapper))
    function_file.write_text("\n\n".join(functions), encoding="utf-8")
    harness_file.write_text(
        r'''
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$FixtureFile,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$fixtureSource = @'
using System;
using System.Threading;
public static class NativeProbeFixture
{
    public static int Main(string[] args)
    {
        string mode = Environment.GetEnvironmentVariable("CODEX_NATIVE_MODE");
        if (String.Equals(mode, "LARGE", StringComparison.Ordinal))
        {
            int length = Int32.Parse(
                Environment.GetEnvironmentVariable("CODEX_NATIVE_LENGTH"));
            Console.Out.Write(new String('O', length));
            Console.Error.Write(new String('E', length));
            return 0;
        }
        if (String.Equals(mode, "HOLD", StringComparison.Ordinal))
        {
            string eventName = Environment.GetEnvironmentVariable(
                "CODEX_NATIVE_EVENT");
            using (EventWaitHandle handle = EventWaitHandle.OpenExisting(eventName))
            {
                handle.WaitOne();
            }
            return 0;
        }
        Console.Out.Write("native-out");
        Console.Error.Write("native-err");
        return 7;
    }
}
'@
Add-Type `
    -TypeDefinition $fixtureSource `
    -OutputAssembly $FixtureFile `
    -OutputType ConsoleApplication
. $FunctionFile

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $digest = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([BitConverter]::ToString($digest)).Replace("-", "").ToLowerInvariant()
}

$output = [ordered]@{
    scenario = $Scenario
    error = $null
    schema_version = $null
    exit_code = $null
    stdout = $null
    stderr = $null
    stdout_length = 0
    stderr_length = 0
    stdout_sha256 = $null
    stderr_sha256 = $null
    first = $null
    second = $null
    audit = @()
    factory_calls = 0
    start_calls = 0
    elapsed_ms = 0
    close_target = $null
    final_phase = $null
    close_count_before_noop = 0
    close_count_after_noop = 0
    error_chain = @()
}
$stopwatch = [Diagnostics.Stopwatch]::StartNew()
try {
    if ($Scenario.StartsWith("V4_CLOSE_", [StringComparison]::Ordinal)) {
        Initialize-SourceJavaProbeNativeStartAuthority
        $auditPath = $ResultFile + ".audit"
        [SourceJavaProbeNativeStartAuthority]::AuditPath = $auditPath
        $target = switch ($Scenario) {
            "V4_CLOSE_RAW_STDOUT_WRITE" { "stdout-write" }
            "V4_CLOSE_RAW_THREAD" { "thread" }
            "V4_CLOSE_RAW_STDOUT_READ" { "stdout-read" }
            "V4_CLOSE_RAW_PROCESS" { "process" }
            "V4_CLOSE_MANAGED_READER" { "stdout-reader" }
            "V4_CLOSE_MANAGED_STREAM" { "stdout-stream" }
            "V4_CLOSE_MANAGED_WRAPPER" { "stdout-wrapper" }
            "V4_CLOSE_PRE_CREATE_FAILURE" { "stdout-write" }
            "V4_CLOSE_POST_CREATE_FAILURE" { "thread" }
            "V4_CLOSE_RAW_TRUTH_BROKEN" { "stdout-read" }
            "V4_CLOSE_MANAGED_TRUTH_BROKEN" { "stdout-reader" }
            default { throw "unknown V4 close scenario" }
        }
        $truthBroken = $Scenario -eq "V4_CLOSE_RAW_TRUTH_BROKEN" -or
            $Scenario -eq "V4_CLOSE_MANAGED_TRUTH_BROKEN"
        $failureCount = if ($truthBroken) { 0 }
        elseif ($target.StartsWith("stdout-", [StringComparison]::Ordinal) -and
            ($target -eq "stdout-reader" -or $target -eq "stdout-stream" -or
                $target -eq "stdout-wrapper")) { 1 } else { 2 }
        $output.close_target = $target
        if ($truthBroken) {
            [SourceJavaProbeNativeStartAuthority]::TestFailureTarget = $target
        }
        $directive = [Func[string, int, SourceJavaProbeCloseDirective]]{
            param($ResourceId, $Attempt)
            if ([string]$ResourceId -ceq $target -and [int]$Attempt -le $failureCount) {
                return [SourceJavaProbeCloseDirective]::FAILED_RETAINED
            }
            return [SourceJavaProbeCloseDirective]::PROCEED_REAL
        }
        $observer = [Action[string, int]]{ param($ResourceId, $Attempt) }
        $stage = [Func[string, bool]]{
            param($Stage)
            return $Scenario -eq "V4_CLOSE_POST_CREATE_FAILURE" -and
                [string]$Stage -ceq "AFTER_CREATE_PROCESS"
        }
        $application = if ($Scenario -eq "V4_CLOSE_PRE_CREATE_FAILURE") {
            $FixtureFile + ".missing"
        }
        else { $FixtureFile }
        try {
            $authority = [SourceJavaProbeNativeStartAuthority]::Start(
                $application,
                [string[]]@("-XshowSettings:properties", "-version"),
                $stage,
                $directive,
                $observer)
            $authority.WaitForExitUnbounded()
            $terminal = $authority.GetState()
            $output.exit_code = $authority.GetExitCode()
            $nativeOutput = $authority.ConsumeOutput()
            $output.stdout = $nativeOutput.Stdout
            $output.stderr = $nativeOutput.Stderr
            $authority.Complete()
            $output.final_phase = $authority.Phase
            $output.close_count_before_noop = [IO.File]::ReadAllLines($auditPath).Count
            $authority.Complete()
            $output.close_count_after_noop = [IO.File]::ReadAllLines($auditPath).Count
        }
        catch {
            $cursor = $_.Exception
            $messages = @()
            while ($null -ne $cursor) {
                $messages += [string]$cursor.Message
                $cursor = $cursor.InnerException
            }
            $output.error_chain = @($messages)
            $output.error = if ($messages -contains
                "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED") {
                "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
            }
            else { [string]$messages[0] }
        }
        if ([IO.File]::Exists($auditPath)) {
            $output.audit = @(
                foreach ($line in [IO.File]::ReadAllLines($auditPath)) {
                    $parts = $line.Split('|')
                    [pscustomobject]@{
                        moment = $parts[0]
                        resource = $parts[1]
                        attempt = [int]$parts[2]
                        phase = $parts[3]
                        raw_value = [long]$parts[4]
                        raw_state = $parts[5]
                        close_attempts = [int]$parts[6]
                        injected_failures = [int]$parts[7]
                        real_close_attempted = [bool]::Parse($parts[8])
                        last_error = [int]$parts[9]
                        reference_present = [bool]::Parse($parts[10])
                        same_reference = [bool]::Parse($parts[11])
                        managed_state = $parts[12]
                        dispose_attempts = [int]$parts[13]
                        managed_failures = [int]$parts[14]
                        real_dispose_attempted = [bool]::Parse($parts[15])
                        startup_complete = [bool]::Parse($parts[16])
                        output_complete = [bool]::Parse($parts[17])
                        all_complete = [bool]::Parse($parts[18])
                        exit_proven = [bool]::Parse($parts[19])
                        pid = [int]$parts[20]
                        creation_file_time = [long]$parts[21]
                        exit_file_time = [long]$parts[22]
                        adapter_calls = if ($parts.Count -gt 23) {
                            [int]$parts[23]
                        }
                        else { 0 }
                    }
                })
        }
    }
    elseif ($Scenario -eq "NORMAL" -or $Scenario -eq "LARGE") {
        $env:CODEX_NATIVE_MODE = $Scenario
        if ($Scenario -eq "LARGE") {
            $env:CODEX_NATIVE_LENGTH = "524288"
        }
        $result = Invoke-SourceJavaExecutableProbe `
            -ExecutablePath $FixtureFile `
            -Arguments @("-XshowSettings:properties", "-version") `
            -TimeoutMilliseconds 10000
        $output.schema_version = $result.schema_version
        $output.exit_code = $result.exit_code
        $output.stdout = if ($Scenario -eq "NORMAL") { $result.stdout } else { $null }
        $output.stderr = if ($Scenario -eq "NORMAL") { $result.stderr } else { $null }
        $output.stdout_length = $result.stdout.Length
        $output.stderr_length = $result.stderr.Length
        $output.stdout_sha256 = Get-TextSha256 -Text $result.stdout
        $output.stderr_sha256 = Get-TextSha256 -Text $result.stderr
    }
    elseif ($Scenario -eq "REPEAT") {
        Initialize-SourceJavaProbeNativeStartAuthority
        $env:CODEX_NATIVE_MODE = "NORMAL"
        $records = @()
        foreach ($attempt in 1..2) {
            $authority = [SourceJavaProbeNativeStartAuthority]::Start(
                $FixtureFile,
                [string[]]@("-XshowSettings:properties", "-version"))
            $readyPhase = $authority.Phase
            $processId = $authority.Pid
            $creation = $authority.CreationDate
            $authority.WaitForExitUnbounded()
            $state = $authority.GetState()
            $exitCode = $authority.GetExitCode()
            $result = $authority.ConsumeOutput()
            $authority.Complete()
            $authority.Complete()
            $records += [pscustomobject]@{
                attempt = $attempt
                ready_phase = $readyPhase
                pid = $processId
                creation_date = $creation
                terminal_pid = $state.Pid
                terminal_creation_date = $state.CreationDate
                exit_date = $state.ExitDate
                is_exited = $state.IsExited
                exit_code = $exitCode
                stdout = $result.Stdout
                stderr = $result.Stderr
                final_phase = $authority.Phase
            }
        }
        $output.first = $records[0]
        $output.second = $records[1]
    }
    elseif ($Scenario -eq "PRE_READY_FAILURE") {
        Initialize-SourceJavaProbeNativeStartAuthority
        $eventName = "CodexNativeFailure_" + [Guid]::NewGuid().ToString("N")
        $holdEvent = [Threading.EventWaitHandle]::new(
            $false,
            [Threading.EventResetMode]::ManualReset,
            $eventName)
        $env:CODEX_NATIVE_MODE = "HOLD"
        $env:CODEX_NATIVE_EVENT = $eventName
        try {
            [SourceJavaProbeNativeStartAuthority]::Start(
                $FixtureFile,
                [string[]]@("-XshowSettings:properties", "-version"),
                [Func[string, bool]]{
                    param($Stage)
                    return [string]$Stage -ceq "BEFORE_READY_RETURN"
                }) | Out-Null
        }
        catch {
            $cursor = $_.Exception
            $messages = @()
            while ($null -ne $cursor) {
                $messages += [string]$cursor.Message
                $cursor = $cursor.InnerException
            }
            $output.error_chain = @($messages)
            $output.error = if ($messages -contains
                "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED") {
                "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
            }
            else { [string]$messages[0] }
        }
        finally {
            $holdEvent.Dispose()
        }
        $output.audit = @()
    }
    elseif ($Scenario -eq "INCOMPLETE_INJECTION") {
        $factoryCalls = 0
        $startCalls = 0
        try {
            Invoke-SourceJavaExecutableProbe `
                -ExecutablePath $FixtureFile `
                -Arguments @("-XshowSettings:properties", "-version") `
                -TimeoutMilliseconds 10000 `
                -ProcessFactory {
                    param($Ignored)
                    $factoryCalls += 1
                    return [pscustomobject]@{}
                } | Out-Null
        }
        catch {
            $output.error = $_.Exception.Message
        }
        $output.factory_calls = $factoryCalls
        $output.start_calls = $startCalls
    }
}
catch {
    $output.error = $_.Exception.Message
}
finally {
    $stopwatch.Stop()
    $output.elapsed_ms = [long]$stopwatch.ElapsedMilliseconds
    Remove-Item Env:CODEX_NATIVE_MODE -ErrorAction SilentlyContinue
    Remove-Item Env:CODEX_NATIVE_LENGTH -ErrorAction SilentlyContinue
    Remove-Item Env:CODEX_NATIVE_EVENT -ErrorAction SilentlyContinue
}
[IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]$output | ConvertTo-Json -Depth 20 -Compress),
    [Text.UTF8Encoding]::new($false))
'''.strip(),
        encoding="utf-8",
    )
    command = [
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(harness_file),
        "-FunctionFile",
        str(function_file),
        "-ResultFile",
        str(result_file),
        "-FixtureFile",
        str(fixture_file),
        "-Scenario",
        scenario,
    ]
    harness = subprocess.Popen(
        command,
        cwd=tmp_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        stdout, stderr = harness.communicate(timeout=30)
    except subprocess.TimeoutExpired as timeout:
        subprocess.run(
            ["taskkill.exe", "/PID", str(harness.pid), "/T", "/F"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        harness.communicate(timeout=10)
        raise AssertionError(
            f"isolated V4 native harness stalled in {scenario}"
        ) from timeout
    if scenario in {
        "V4_CLOSE_RAW_TRUTH_BROKEN",
        "V4_CLOSE_MANAGED_TRUTH_BROKEN",
    }:
        audit_file = Path(str(result_file) + ".audit")
        assert harness.returncode != 0
        assert audit_file.is_file(), stderr or stdout
        audit: list[dict[str, Any]] = []
        for line in audit_file.read_text(encoding="utf-8-sig").splitlines():
            parts = line.split("|")
            audit.append(
                {
                    "moment": parts[0],
                    "resource": parts[1],
                    "attempt": int(parts[2]),
                    "phase": parts[3],
                    "raw_value": int(parts[4]),
                    "raw_state": parts[5],
                    "close_attempts": int(parts[6]),
                    "injected_failures": int(parts[7]),
                    "real_close_attempted": parts[8] == "True",
                    "last_error": int(parts[9]),
                    "reference_present": parts[10] == "True",
                    "same_reference": parts[11] == "True",
                    "managed_state": parts[12],
                    "dispose_attempts": int(parts[13]),
                    "managed_failures": int(parts[14]),
                    "real_dispose_attempted": parts[15] == "True",
                    "startup_complete": parts[16] == "True",
                    "output_complete": parts[17] == "True",
                    "all_complete": parts[18] == "True",
                    "exit_proven": parts[19] == "True",
                    "pid": int(parts[20]),
                    "creation_file_time": int(parts[21]),
                    "exit_file_time": int(parts[22]),
                    "adapter_calls": int(parts[23]),
                }
            )
        return {
            "host_returncode": harness.returncode,
            "stdout": stdout,
            "stderr": stderr,
            "audit": audit,
            "result_file_exists": result_file.exists(),
        }
    assert harness.returncode == 0, stderr or stdout
    assert result_file.is_file(), stdout
    result = json.loads(result_file.read_text(encoding="utf-8-sig"))
    result["instrumentation_counts"] = instrumentation_counts
    return result


def _run_local_docker_hang_old_red_harness(tmp_path: Path) -> dict[str, Any]:
    powershell = shutil.which("powershell.exe")
    if powershell is None:
        pytest.skip("Windows PowerShell is not available")

    source = LAUNCHER.read_text(encoding="utf-8-sig")
    function_start = source.index("function Invoke-LocalDocker {")
    function_end = source.index(
        "\nfunction Assert-LocalDockerCommandSucceeded {",
        function_start,
    )
    local_docker_function = source[function_start:function_end].rstrip()
    function_file = tmp_path / "local-docker-function.ps1"
    harness_file = tmp_path / "invoke-local-docker-hang.ps1"
    result_file = tmp_path / "local-docker-result.json"
    ready_file = tmp_path / "fake-docker-ready.txt"
    fake_docker = tmp_path / "docker.exe"
    function_file.write_text(local_docker_function, encoding="utf-8")
    harness_file.write_text(
        r'''
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ReadyFile,
    [Parameter(Mandatory = $true)][string]$FakeDocker
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$fixtureSource = @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;

public static class FakeDocker
{
    public static int Main(string[] args)
    {
        string readyPath = Environment.GetEnvironmentVariable(
            "CODEX_ISSUE0021_READY");
        string eventName = Environment.GetEnvironmentVariable(
            "CODEX_ISSUE0021_EVENT");
        string observedOtel = Environment.GetEnvironmentVariable(
            "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT");
        int pid;
        using (Process current = Process.GetCurrentProcess())
        {
            pid = current.Id;
        }
        File.WriteAllText(
            readyPath,
            pid.ToString() + "|" +
                (observedOtel == null ? "<NULL>" : observedOtel),
            new UTF8Encoding(false));
        Console.Out.WriteLine("fake-docker-ready");
        Console.Error.WriteLine("fake-docker-blocked");
        using (EventWaitHandle blocker = EventWaitHandle.OpenExisting(eventName))
        {
            blocker.WaitOne();
        }
        return 0;
    }
}
'@
Add-Type `
    -TypeDefinition $fixtureSource `
    -OutputAssembly $FakeDocker `
    -OutputType ConsoleApplication
. $FunctionFile

$eventName = "CodexIssue0021_" + [Guid]::NewGuid().ToString("N")
$blocker = [Threading.EventWaitHandle]::new(
    $false,
    [Threading.EventResetMode]::ManualReset,
    $eventName)
$savedPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
$savedOtel = "PT1S"
[Environment]::SetEnvironmentVariable(
    "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT",
    $savedOtel,
    "Process")
[Environment]::SetEnvironmentVariable(
    "CODEX_ISSUE0021_READY",
    $ReadyFile,
    "Process")
[Environment]::SetEnvironmentVariable(
    "CODEX_ISSUE0021_EVENT",
    $eventName,
    "Process")
[Environment]::SetEnvironmentVariable(
    "PATH",
    ([IO.Path]::GetDirectoryName($FakeDocker) + ";" + $savedPath),
    "Process")
$script:LocalDockerExitCode = 0
$output = [ordered]@{
    completed_by_production = $false
    canonical_error = $null
    error_chain = @()
    local_docker_exit_code = $null
    environment_restored = $false
    output_lines = @()
}
try {
    $output.output_lines = @(
        Invoke-LocalDocker `
            -CommandTimeoutMilliseconds 300 `
            HANG)
    $output.completed_by_production = $true
}
catch {
    $output.completed_by_production = $true
    $output.canonical_error = $_.Exception.Message
    $cursor = $_.Exception
    while ($null -ne $cursor) {
        $output.error_chain += [pscustomobject]@{
            type = $cursor.GetType().FullName
            message = [string]$cursor.Message
            stack_trace = [string]$cursor.StackTrace
            native_error_code = if ($cursor -is [ComponentModel.Win32Exception]) {
                [int]$cursor.NativeErrorCode
            }
            else { $null }
        }
        $cursor = $cursor.InnerException
    }
}
finally {
    $output.local_docker_exit_code = $script:LocalDockerExitCode
    $output.environment_restored =
        [Environment]::GetEnvironmentVariable(
            "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT",
            "Process") -ceq $savedOtel
    [Environment]::SetEnvironmentVariable("PATH", $savedPath, "Process")
    [Environment]::SetEnvironmentVariable(
        "CODEX_ISSUE0021_READY", $null, "Process")
    [Environment]::SetEnvironmentVariable(
        "CODEX_ISSUE0021_EVENT", $null, "Process")
    $blocker.Dispose()
}
[IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]$output | ConvertTo-Json -Depth 10 -Compress),
    [Text.UTF8Encoding]::new($false))
'''.strip(),
        encoding="utf-8",
    )

    harness = subprocess.Popen(
        [
            powershell,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-ReadyFile",
            str(ready_file),
            "-FakeDocker",
            str(fake_docker),
        ],
        cwd=tmp_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    ready_deadline = time.monotonic() + 20
    while not ready_file.is_file() and harness.poll() is None:
        if time.monotonic() >= ready_deadline:
            break
        time.sleep(0.02)
    if not ready_file.is_file():
        early_stdout, early_stderr = harness.communicate(timeout=10)
        early_result = (
            json.loads(result_file.read_text(encoding="utf-8-sig"))
            if result_file.is_file()
            else None
        )
        raise AssertionError(
            "fake docker did not publish readiness: "
            f"returncode={harness.returncode}, result={early_result}, "
            f"stdout={early_stdout!r}, stderr={early_stderr!r}"
        )
    ready_parts = ready_file.read_text(encoding="utf-8-sig").split("|")
    assert len(ready_parts) == 2
    fake_pid = int(ready_parts[0])
    assert fake_pid > 0 and fake_pid != harness.pid

    class FileTime(ctypes.Structure):
        _fields_ = (("low", wintypes.DWORD), ("high", wintypes.DWORD))

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
    kernel32.WaitForSingleObject.restype = wintypes.DWORD
    kernel32.GetProcessTimes.argtypes = (
        wintypes.HANDLE,
        ctypes.POINTER(FileTime),
        ctypes.POINTER(FileTime),
        ctypes.POINTER(FileTime),
        ctypes.POINTER(FileTime),
    )
    kernel32.GetProcessTimes.restype = wintypes.BOOL
    kernel32.GetExitCodeProcess.argtypes = (
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.DWORD),
    )
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel32.CloseHandle.restype = wintypes.BOOL
    synchronize = 0x00100000
    query_limited_information = 0x00001000
    witness = kernel32.OpenProcess(
        synchronize | query_limited_information,
        False,
        fake_pid,
    )
    assert witness
    creation = FileTime()
    pre_exit = FileTime()
    kernel = FileTime()
    user = FileTime()
    assert kernel32.GetProcessTimes(
        witness,
        ctypes.byref(creation),
        ctypes.byref(pre_exit),
        ctypes.byref(kernel),
        ctypes.byref(user),
    )
    creation_ticks = (creation.high << 32) | creation.low
    assert creation_ticks > 0

    emergency_cleanup_used = False
    stdout = ""
    stderr = ""
    try:
        stdout, stderr = harness.communicate(timeout=3)
    except subprocess.TimeoutExpired:
        emergency_cleanup_used = True
        cleanup = subprocess.run(
            ["taskkill.exe", "/PID", str(harness.pid), "/T", "/F"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        assert cleanup.returncode == 0, cleanup.stderr or cleanup.stdout
        stdout, stderr = harness.communicate(timeout=10)

    wait_result = kernel32.WaitForSingleObject(witness, 5000)
    assert wait_result == 0
    creation_after = FileTime()
    exit_after = FileTime()
    kernel_after = FileTime()
    user_after = FileTime()
    assert kernel32.GetProcessTimes(
        witness,
        ctypes.byref(creation_after),
        ctypes.byref(exit_after),
        ctypes.byref(kernel_after),
        ctypes.byref(user_after),
    )
    creation_after_ticks = (creation_after.high << 32) | creation_after.low
    exit_ticks = (exit_after.high << 32) | exit_after.low
    exit_code = wintypes.DWORD()
    assert kernel32.GetExitCodeProcess(witness, ctypes.byref(exit_code))
    assert kernel32.CloseHandle(witness)
    assert creation_after_ticks == creation_ticks
    assert exit_ticks >= creation_ticks
    assert exit_code.value != 259
    assert harness.poll() is not None

    production_result = (
        json.loads(result_file.read_text(encoding="utf-8-sig"))
        if result_file.is_file()
        else None
    )
    return {
        "completed_by_production": production_result is not None
        and production_result["completed_by_production"] is True,
        "canonical_error": None
        if production_result is None
        else production_result["canonical_error"],
        "local_docker_exit_code": None
        if production_result is None
        else production_result["local_docker_exit_code"],
        "environment_restored": None
        if production_result is None
        else production_result["environment_restored"],
        "result_file_exists": result_file.is_file(),
        "emergency_cleanup_used": emergency_cleanup_used,
        "harness_pid": harness.pid,
        "harness_returncode": harness.returncode,
        "fake_pid": fake_pid,
        "fake_observed_otel": ready_parts[1],
        "fake_creation_file_time": creation_ticks,
        "fake_exit_file_time": exit_ticks,
        "fake_exit_code": exit_code.value,
        "fake_exact_exit_proven": True,
        "stdout": stdout,
        "stderr": stderr,
    }


def test_local_docker_permanent_hang_times_out_and_proves_exact_cleanup(
    tmp_path: Path,
) -> None:
    result = _run_local_docker_hang_old_red_harness(tmp_path)

    assert result["emergency_cleanup_used"] is False, result
    assert result["completed_by_production"] is True
    assert result["canonical_error"] == "LOCAL_DOCKER_COMMAND_TIMEOUT"
    assert result["local_docker_exit_code"] == 124
    assert result["environment_restored"] is True
    assert result["result_file_exists"] is True
    assert result["fake_observed_otel"] == "<NULL>"
    assert result["fake_exact_exit_proven"] is True
    assert result["fake_creation_file_time"] > 0
    assert result["fake_exit_file_time"] >= result["fake_creation_file_time"]
    assert result["harness_returncode"] == 0


def _run_local_docker_runtime_contract_harness(tmp_path: Path) -> dict[str, Any]:
    powershell = shutil.which("powershell.exe")
    if powershell is None:
        pytest.skip("Windows PowerShell is not available")

    source = LAUNCHER.read_text(encoding="utf-8-sig")
    function_start = source.index("function Invoke-LocalDocker {")
    function_end = source.index(
        "\nfunction Assert-LocalDockerCommandSucceeded {",
        function_start,
    )
    function_file = tmp_path / "local-docker-runtime-function.ps1"
    harness_file = tmp_path / "invoke-local-docker-runtime.ps1"
    result_file = tmp_path / "local-docker-runtime-result.json"
    fake_docker = tmp_path / "docker.exe"
    function_file.write_text(
        source[function_start:function_end].rstrip(),
        encoding="utf-8",
    )
    harness_file.write_text(
        r'''
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$FakeDocker
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$fixtureSource = @'
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;

public static class FakeDockerRuntime
{
    private static string RequiredEnvironment(string name)
    {
        string value = Environment.GetEnvironmentVariable(name);
        if (String.IsNullOrEmpty(value)) throw new InvalidOperationException(name);
        return value;
    }

    private static void Append(string path, string value)
    {
        File.AppendAllText(path, value + Environment.NewLine, new UTF8Encoding(false));
    }

    private static Process StartSelf(string mode)
    {
        string executable;
        using (Process current = Process.GetCurrentProcess())
        {
            executable = current.MainModule.FileName;
        }
        ProcessStartInfo info = new ProcessStartInfo();
        info.FileName = executable;
        info.Arguments = mode;
        info.UseShellExecute = false;
        info.CreateNoWindow = true;
        Process child = new Process();
        child.StartInfo = info;
        if (!child.Start()) throw new InvalidOperationException("child-start");
        return child;
    }

    private static void AuditEnvironment(string mode, string[] args)
    {
        string path = RequiredEnvironment("CODEX_ISSUE0021_AUDIT");
        string otel = Environment.GetEnvironmentVariable(
            "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT");
        string sentinel = Environment.GetEnvironmentVariable(
            "CODEX_ISSUE0021_SENTINEL");
        int pid;
        long startTicks;
        using (Process current = Process.GetCurrentProcess())
        {
            pid = current.Id;
            startTicks = current.StartTime.ToUniversalTime().Ticks;
        }
        Append(path, mode + "|" + pid.ToString() + "|" +
            startTicks.ToString() + "|" +
            (otel == null ? "<NULL>" : otel) + "|" + sentinel);
        if (String.Equals(mode, "ARGV", StringComparison.Ordinal))
        {
            string argvPath = RequiredEnvironment("CODEX_ISSUE0021_ARGV");
            foreach (string argument in args)
            {
                Append(argvPath, Convert.ToBase64String(
                    Encoding.UTF8.GetBytes(argument)));
            }
        }
    }

    public static int Main(string[] args)
    {
        string mode = args.Length == 0 ? "NORMAL" : args[0];
        AuditEnvironment(mode, args);
        if (String.Equals(mode, "NORMAL", StringComparison.Ordinal))
        {
            Console.Out.WriteLine("normal-out");
            return 0;
        }
        if (String.Equals(mode, "NONZERO", StringComparison.Ordinal))
        {
            Console.Out.WriteLine("nonzero-out");
            Console.Error.WriteLine("nonzero-err");
            return 17;
        }
        if (String.Equals(mode, "ULTRA_FAST", StringComparison.Ordinal))
        {
            return 3;
        }
        if (String.Equals(mode, "PIPE_ZERO", StringComparison.Ordinal)) return 0;
        if (String.Equals(mode, "PIPE_ONE", StringComparison.Ordinal))
        {
            Console.Out.Write("one");
            return 0;
        }
        if (String.Equals(mode, "PIPE_MULTI", StringComparison.Ordinal))
        {
            Console.Out.Write("first\nsecond\n");
            Console.Error.Write("err-first\nerr-second\n");
            return 0;
        }
        if (String.Equals(mode, "LARGE", StringComparison.Ordinal))
        {
            const int length = 1100000;
            Console.Out.Write(new String('O', length));
            Console.Error.Write(new String('E', length));
            return 0;
        }
        if (String.Equals(mode, "ARGV", StringComparison.Ordinal)) return 0;
        if (String.Equals(mode, "DESCENDANT_NORMAL", StringComparison.Ordinal))
        {
            using (Process holder = StartSelf("HOLDER")) { }
            using (Process signaler = StartSelf("SIGNALER")) { }
            Console.Out.WriteLine("root-out");
            return 0;
        }
        if (String.Equals(mode, "DESCENDANT_HOLD", StringComparison.Ordinal))
        {
            using (Process holder = StartSelf("HOLDER")) { }
            Console.Out.WriteLine("root-out");
            return 0;
        }
        if (String.Equals(mode, "HOLDER", StringComparison.Ordinal) ||
            String.Equals(mode, "SIBLING", StringComparison.Ordinal))
        {
            string readyPath = RequiredEnvironment("CODEX_ISSUE0021_CHILD_READY");
            Append(readyPath, mode + "|" + Process.GetCurrentProcess().Id.ToString());
            Console.Out.WriteLine(mode == "HOLDER" ? "child-out" : "sibling-out");
            using (EventWaitHandle blocker = EventWaitHandle.OpenExisting(
                RequiredEnvironment("CODEX_ISSUE0021_EVENT")))
            {
                blocker.WaitOne();
            }
            return 0;
        }
        if (String.Equals(mode, "SIGNALER", StringComparison.Ordinal))
        {
            string readyPath = RequiredEnvironment("CODEX_ISSUE0021_CHILD_READY");
            while (!File.Exists(readyPath)) Thread.Sleep(10);
            using (EventWaitHandle blocker = EventWaitHandle.OpenExisting(
                RequiredEnvironment("CODEX_ISSUE0021_EVENT")))
            {
                blocker.Set();
            }
            return 0;
        }
        return 91;
    }
}
'@
Add-Type `
    -TypeDefinition $fixtureSource `
    -OutputAssembly $FakeDocker `
    -OutputType ConsoleApplication
. $FunctionFile

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant() }
    finally { $hash.Dispose() }
}

function Invoke-CapturedDocker {
        param(
            [Parameter(Mandatory = $true)]
            [AllowEmptyString()]
            [string[]]$Arguments,
        [int]$TimeoutMilliseconds = 5000,
        [switch]$RedirectAll
    )
    $caught = $null
    $records = @()
    $errors = @()
    if ($RedirectAll) {
        try {
            $records = @(& { Invoke-LocalDocker `
                    -CommandTimeoutMilliseconds $TimeoutMilliseconds `
                    @Arguments } *> $null)
        }
        catch { $caught = $_.Exception.Message }
    }
    else {
        try {
            $records = @(Invoke-LocalDocker `
                    -CommandTimeoutMilliseconds $TimeoutMilliseconds `
                        @Arguments `
                        -ErrorVariable +errors `
                        -ErrorAction SilentlyContinue)
        }
        catch { $caught = $_.Exception.Message }
    }
        return [pscustomobject]@{
            arguments = @($Arguments)
            output = @($records | ForEach-Object { [string]$_ })
            errors = @($errors |
                Where-Object {
                    $exceptionProperty = $_.PSObject.Properties["Exception"]
                    $_.GetType().FullName -cne
                        "System.Management.Automation.StopUpstreamCommandsException" -and
                    ($null -eq $exceptionProperty -or
                        $exceptionProperty.Value.GetType().FullName -cne
                            "System.Management.Automation.StopUpstreamCommandsException")
                } |
                ForEach-Object { [string]$_ })
        caught = $caught
        exit_code = [int]$script:LocalDockerExitCode
        outcome = [string]$script:LocalDockerOutcomeCode
        output_types = @($records | ForEach-Object { $_.GetType().FullName })
    }
}

$savedPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
$savedOtel = "PT1S"
$auditPath = $ResultFile + ".audit"
$argvPath = $ResultFile + ".argv"
$childReady = $ResultFile + ".child"
[Environment]::SetEnvironmentVariable("PATH", ([IO.Path]::GetDirectoryName($FakeDocker) + ";" + $savedPath), "Process")
[Environment]::SetEnvironmentVariable("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", $savedOtel, "Process")
[Environment]::SetEnvironmentVariable("CODEX_ISSUE0021_SENTINEL", "preserved-value", "Process")
[Environment]::SetEnvironmentVariable("CODEX_ISSUE0021_AUDIT", $auditPath, "Process")
[Environment]::SetEnvironmentVariable("CODEX_ISSUE0021_ARGV", $argvPath, "Process")
[Environment]::SetEnvironmentVariable("CODEX_ISSUE0021_CHILD_READY", $childReady, "Process")

$output = [ordered]@{}
try {
    $output.normal = Invoke-CapturedDocker -Arguments @("NORMAL")
    $output.nonzero = Invoke-CapturedDocker -Arguments @("NONZERO")
    $output.ultra_fast = Invoke-CapturedDocker -Arguments @("ULTRA_FAST")
    $output.pipe_zero = Invoke-CapturedDocker -Arguments @("PIPE_ZERO")
    $output.pipe_one = Invoke-CapturedDocker -Arguments @("PIPE_ONE")
    $output.pipe_multi = Invoke-CapturedDocker -Arguments @("PIPE_MULTI")
    $output.redirected = Invoke-CapturedDocker -Arguments @("PIPE_MULTI") -RedirectAll

        $large = [LocalDockerCliExecutionAuthority]::Execute(
            $FakeDocker,
            [string[]]@("LARGE"),
            10000)
        $output.large = [pscustomobject]@{
            exit_code = [int]$large.ActualExitCode
            timed_out = [bool]$large.TimedOut
            stdout_length = ([string]$large.Stdout).Length
            stderr_length = ([string]$large.Stderr).Length
            stdout_sha256 = Get-TextSha256 -Text ([string]$large.Stdout)
            stderr_sha256 = Get-TextSha256 -Text ([string]$large.Stderr)
            pid = [int]$large.Pid
            creation_date = [string]$large.CreationDate
            exit_date = [string]$large.ExitDate
        }

    $edgeArguments = @(
        "ARGV", "", "white space", "`t", "Unicode-物流-✓", 'quote"inside',
        'back\slash', 'trail\', '{name:^/proxy$}',
        'type=bind,source=C:\path with space,target=/run/x,readonly',
        'semi;colon', 'amp&ersand', '$(New-Item shell-marker)', '>marker.txt')
    $output.argv = Invoke-CapturedDocker -Arguments $edgeArguments
    $output.argv_expected = @($edgeArguments)

    $eventName = "CodexIssue0021Normal_" + [Guid]::NewGuid().ToString("N")
    $event = [Threading.EventWaitHandle]::new($false, [Threading.EventResetMode]::ManualReset, $eventName)
    [Environment]::SetEnvironmentVariable("CODEX_ISSUE0021_EVENT", $eventName, "Process")
    Remove-Item -LiteralPath $childReady -ErrorAction SilentlyContinue
    $output.descendant_normal = Invoke-CapturedDocker -Arguments @("DESCENDANT_NORMAL")
    $event.Dispose()

    $siblingEventName = "CodexIssue0021Sibling_" + [Guid]::NewGuid().ToString("N")
    $siblingEvent = [Threading.EventWaitHandle]::new($false, [Threading.EventResetMode]::ManualReset, $siblingEventName)
    [Environment]::SetEnvironmentVariable("CODEX_ISSUE0021_EVENT", $siblingEventName, "Process")
    Remove-Item -LiteralPath $childReady -ErrorAction SilentlyContinue
    $sibling = [Diagnostics.Process]::new()
    $sibling.StartInfo = [Diagnostics.ProcessStartInfo]::new($FakeDocker, "SIBLING")
    $sibling.StartInfo.UseShellExecute = $false
    $sibling.StartInfo.CreateNoWindow = $true
    [void]$sibling.Start()
    $siblingPid = [int]$sibling.Id
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(5)
    while (-not (Test-Path -LiteralPath $childReady) -and [DateTime]::UtcNow -lt $readyDeadline) {
        [Threading.Thread]::Sleep(10)
    }
    if (-not (Test-Path -LiteralPath $childReady)) { throw "sibling readiness missing" }
    $productionEventName = "CodexIssue0021Production_" + [Guid]::NewGuid().ToString("N")
    $productionEvent = [Threading.EventWaitHandle]::new($false, [Threading.EventResetMode]::ManualReset, $productionEventName)
    [Environment]::SetEnvironmentVariable("CODEX_ISSUE0021_EVENT", $productionEventName, "Process")
    Remove-Item -LiteralPath $childReady -ErrorAction SilentlyContinue
    $output.descendant_timeout = Invoke-CapturedDocker `
        -Arguments @("DESCENDANT_HOLD") `
        -TimeoutMilliseconds 300
    $sibling.Refresh()
        $output.unrelated_sibling = [pscustomobject]@{
            pid = $siblingPid
            alive_after_timeout = -not $sibling.HasExited
            exited_after_release = $false
        }
    [void]$siblingEvent.Set()
    [void]$sibling.WaitForExit(5000)
    $output.unrelated_sibling.exited_after_release = $sibling.HasExited
    $sibling.Dispose()
    $siblingEvent.Dispose()
    $productionEvent.Dispose()

    $output.repeat_first = Invoke-CapturedDocker -Arguments @("NORMAL")
    $output.repeat_second = Invoke-CapturedDocker -Arguments @("NORMAL")

    $oldPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
    $emptyPath = Join-Path ([IO.Path]::GetDirectoryName($ResultFile)) "empty-path"
    [IO.Directory]::CreateDirectory($emptyPath) | Out-Null
    [Environment]::SetEnvironmentVariable("PATH", $emptyPath, "Process")
    $output.start_failure = Invoke-CapturedDocker -Arguments @("NORMAL")
    [Environment]::SetEnvironmentVariable("PATH", $oldPath, "Process")

    $auditLines = @([IO.File]::ReadAllLines($auditPath))
    $output.audit_lines = $auditLines
    $output.argv_actual = @(
        [IO.File]::ReadAllLines($argvPath) | ForEach-Object {
            [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_))
        })
    $output.parent_environment = [pscustomobject]@{
        otel = [Environment]::GetEnvironmentVariable("OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", "Process")
        sentinel = [Environment]::GetEnvironmentVariable("CODEX_ISSUE0021_SENTINEL", "Process")
    }
}
finally {
    [Environment]::SetEnvironmentVariable("PATH", $savedPath, "Process")
    foreach ($name in @(
        "CODEX_ISSUE0021_SENTINEL", "CODEX_ISSUE0021_AUDIT",
        "CODEX_ISSUE0021_ARGV", "CODEX_ISSUE0021_CHILD_READY",
        "CODEX_ISSUE0021_EVENT")) {
        [Environment]::SetEnvironmentVariable($name, $null, "Process")
    }
}
    [IO.File]::WriteAllText(
        $ResultFile,
        ([pscustomobject]$output | ConvertTo-Json -Depth 30 -Compress),
        [Text.UTF8Encoding]::new($false))
    '''.strip(),
        encoding="utf-8-sig",
    )
    harness = subprocess.Popen(
        [
            powershell,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-FakeDocker",
            str(fake_docker),
        ],
        cwd=tmp_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    emergency_cleanup_used = False
    try:
        stdout, stderr = harness.communicate(timeout=45)
    except subprocess.TimeoutExpired as timeout:
        emergency_cleanup_used = True
        subprocess.run(
            ["taskkill.exe", "/PID", str(harness.pid), "/T", "/F"],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        harness.communicate(timeout=10)
        raise AssertionError("local Docker runtime harness stalled") from timeout
    assert emergency_cleanup_used is False
    assert harness.returncode == 0, stderr or stdout
    assert result_file.is_file(), stdout
    result = json.loads(result_file.read_text(encoding="utf-8-sig"))
    result["emergency_cleanup_used"] = emergency_cleanup_used
    return result


def test_local_docker_runtime_stream_argv_environment_and_job_contracts(
    tmp_path: Path,
) -> None:
    result = _run_local_docker_runtime_contract_harness(tmp_path)

    normal = result["normal"]
    assert normal["output"] == ["normal-out"]
    assert normal["errors"] == []
    assert normal["caught"] is None
    assert normal["exit_code"] == 0
    assert normal["outcome"] == "LOCAL_DOCKER_CLI_SUCCEEDED"
    assert normal["output_types"] == ["System.String"]

    nonzero = result["nonzero"]
    assert nonzero["output"] == ["nonzero-out"]
    assert nonzero["errors"] == ["nonzero-err"]
    assert nonzero["caught"] is None
    assert nonzero["exit_code"] == 17
    assert nonzero["outcome"] == "LOCAL_DOCKER_CLI_NONZERO_EXIT"
    assert result["ultra_fast"] == {
        "arguments": ["ULTRA_FAST"],
        "output": [],
        "errors": [],
        "caught": None,
        "exit_code": 3,
        "outcome": "LOCAL_DOCKER_CLI_NONZERO_EXIT",
        "output_types": [],
    }

    assert result["pipe_zero"]["output"] == []
    assert result["pipe_one"]["output"] == ["one"]
    assert result["pipe_multi"]["output"] == ["first", "second"]
    assert result["pipe_multi"]["errors"] == ["err-first", "err-second"]
    assert result["redirected"]["output"] == []
    assert result["redirected"]["errors"] == []
    assert result["redirected"]["exit_code"] == 0

    large = result["large"]
    assert large["exit_code"] == 0
    assert large["timed_out"] is False
    assert large["stdout_length"] == 1_100_000
    assert large["stderr_length"] == 1_100_000
    assert large["stdout_sha256"] == hashlib.sha256(b"O" * 1_100_000).hexdigest()
    assert large["stderr_sha256"] == hashlib.sha256(b"E" * 1_100_000).hexdigest()
    assert large["pid"] > 0
    assert large["creation_date"]
    assert large["exit_date"]

    assert result["argv_actual"] == result["argv_expected"]
    assert result["argv"]["exit_code"] == 0
    assert not (tmp_path / "shell-marker").exists()
    assert not (tmp_path / "marker.txt").exists()

    descendant_normal = result["descendant_normal"]
    assert descendant_normal["caught"] is None
    assert descendant_normal["exit_code"] == 0
    assert descendant_normal["output"] == ["root-out"]
    assert any(line.startswith("HOLDER|") for line in result["audit_lines"])
    assert any(line.startswith("SIGNALER|") for line in result["audit_lines"])
    descendant_timeout = result["descendant_timeout"]
    assert descendant_timeout["caught"] == "LOCAL_DOCKER_COMMAND_TIMEOUT"
    assert descendant_timeout["exit_code"] == 124
    assert descendant_timeout["outcome"] == "LOCAL_DOCKER_CLI_TIMED_OUT"
    assert result["unrelated_sibling"]["alive_after_timeout"] is True
    assert result["unrelated_sibling"]["exited_after_release"] is True

    for repeated in (result["repeat_first"], result["repeat_second"]):
        assert repeated["output"] == ["normal-out"]
        assert repeated["exit_code"] == 0
        assert repeated["outcome"] == "LOCAL_DOCKER_CLI_SUCCEEDED"
    normal_audits = [
        line.split("|")
        for line in result["audit_lines"]
        if line.startswith("NORMAL|")
    ]
    assert len(normal_audits) == 3
    assert len({(entry[1], entry[2]) for entry in normal_audits}) == 3
    assert all(entry[3:] == ["<NULL>", "preserved-value"] for entry in normal_audits)

    start_failure = result["start_failure"]
    assert "docker.exe" in start_failure["caught"]
    assert "not recognized" in start_failure["caught"]
    assert start_failure["exit_code"] == 1
    assert start_failure["outcome"] == "LOCAL_DOCKER_CLI_START_FAILED"
    assert result["parent_environment"] == {
        "otel": "PT1S",
        "sentinel": "preserved-value",
    }
    assert result["emergency_cleanup_used"] is False


def test_local_docker_native_authority_and_all_callers_are_fail_closed() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    function_start = launcher.index("function Invoke-LocalDocker {")
    function_end = launcher.index(
        "\nfunction Assert-LocalDockerCommandSucceeded {",
        function_start,
    )
    function = launcher[function_start:function_end]
    execute_start = function.index(
        "public static LocalDockerCliExecutionResult Execute("
    )
    execute_end = function.index("private static void ValidateInput(", execute_start)
    execute = function[execute_start:execute_end]

    assert "CREATE_SUSPENDED" in function
    assert "JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE" in function
    assert "CREATE_BREAKAWAY_FROM_JOB" not in function
    assert "CREATE_NEW_PROCESS_GROUP" not in function
    assert "OpenProcess(" not in function
    assert "GetProcessById" not in function
    assert "Win32_Process" not in function
    assert function.index("AssignProcessToJobObject(", execute_start) < function.index(
        "ResumeThread(", execute_start
    )
    assert execute.index("StartOutputDrains(ledger)") < execute.index(
        "ResumeThread(ledger.Thread)"
    )
    assert execute.index("long started = Stopwatch.GetTimestamp()") < execute.index(
        "ResumeThread(ledger.Thread)"
    )
    assert execute.count("ElapsedMilliseconds(started)") == 1
    assert execute.index("TerminateJobObject(ledger.Job, TimeoutExitCode)") < execute.index(
        "WaitForJobZeroUnbounded(ledger.Job)"
    ) < execute.index("WaitForRootUnbounded(ledger.Process)")
    normal_zero = execute.index('Fatal("normal-job-zero")')
    normal_root_wait = execute.index("WaitForRootUnbounded(ledger.Process)", normal_zero)
    assert normal_zero < normal_root_wait < execute.index("ProveExactRootExit(ledger)")
    assert execute.index("ProveExactRootExit(ledger)") < execute.index(
        "RequireTaskResult(ledger.StdoutTask"
    ) < execute.index("CloseAll(ledger)") < execute.index('ledger.Phase = "CLOSED"')
    close_raw = function[
        function.index("private static void CloseRaw(") : function.index(
            "private static void ReleaseStartupAllocations("
        )
    ]
    assert close_raw.index("if (!CloseHandle(handle))") < close_raw.index(
        "handle = IntPtr.Zero;",
        close_raw.index("if (!CloseHandle(handle))"),
    )
    assert "SourceJavaProbeNativeStartAuthority" in launcher

    invocation_count = launcher.count("Invoke-LocalDocker `")
    strict_contexts = re.findall(
        r'Assert-LocalDockerCommandSucceeded -Context "([A-Z_]+)"', launcher
    )
    assert invocation_count == 8
    assert strict_contexts == [
        "TEMPORAL_BUILD_READ",
        "PROXY_DISCOVERY",
        "PROXY_INSPECT",
        "PROXY_REMOVE",
        "PROXY_RUN",
        "TEMPORAL_BUILD_UPDATE",
        "TEMPORAL_CONTAINER_DISCOVERY",
    ]
    proxy_state = launcher[
        launcher.index("function Get-LocalProxyContainerState {") : launcher.index(
            "function Remove-LocalProxyContainerBounded {"
        )
    ]
    assert proxy_state.index('Assert-LocalDockerCommandSucceeded -Context "PROXY_DISCOVERY"') < proxy_state.index(
        'State = "ABSENT"'
    )
    temporal_discovery = launcher[
        launcher.index("$temporalContainers = @(Invoke-LocalDocker `") : launcher.index(
            "Wait-ExternalHttp `",
            launcher.index("$temporalContainers = @(Invoke-LocalDocker `"),
        )
    ]
    assert 'Assert-LocalDockerCommandSucceeded -Context "TEMPORAL_CONTAINER_DISCOVERY"' in temporal_discovery
    assert "LOCAL_DOCKER_PROXY_REMOVAL_UNPROVEN" in launcher
    assert "LOCAL_DOCKER_PROXY_START_UNPROVEN" in launcher
    assert "LOCAL_DOCKER_TEMPORAL_BUILD_UPDATE_UNPROVEN" in launcher
    assert "LOCAL_DOCKER_CLI_ROLLBACK_UNPROVEN" in launcher

    allowed_manifest = launcher[
        launcher.index("$allowedDirtyPaths =") : launcher.index(
            "$reviewedDirtySourcePolicy =",
            launcher.index("$allowedDirtyPaths ="),
        )
    ]
    assert allowed_manifest.count('".local-dev/launch-source.ps1"') == 1
    assert (
        allowed_manifest.count(
            '"tests/static/test_local_source_process_ownership.py"'
        )
        == 2
    )
    deployment = (
        ROOT / "tests" / "static" / "test_phase9_target_e2e_deployment.py"
    ).read_text(encoding="utf-8-sig")
    assert '".local-dev/launch-source.ps1"' in deployment
    assert '"tests/static/test_local_source_process_ownership.py"' in deployment


def _run_local_docker_reconciliation_harness(tmp_path: Path) -> dict[str, Any]:
    powershell = shutil.which("powershell.exe")
    if powershell is None:
        pytest.skip("Windows PowerShell is not available")

    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    function_start = launcher.index("function Assert-LocalDockerCommandSucceeded {")
    function_end = launcher.index("\nfunction Wait-Http {", function_start)
    function_file = tmp_path / "local-docker-reconciliation-functions.ps1"
    harness_file = tmp_path / "local-docker-reconciliation-harness.ps1"
    result_file = tmp_path / "local-docker-reconciliation-result.json"
    function_file.write_text(
        launcher[function_start:function_end].rstrip(),
        encoding="utf-8",
    )
    harness_file.write_text(
        r'''
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$SandboxRoot
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile
$env:TEMPORAL_NAMESPACE = "default"
$script:ProviderQueue = [Collections.Generic.Queue[object]]::new()
$script:ProviderCalls = [Collections.Generic.List[object]]::new()

function Set-ProviderQueue {
    param([Parameter(Mandatory = $true)][object[]]$Items)
    $script:ProviderQueue.Clear()
    $script:ProviderCalls.Clear()
    foreach ($item in $Items) { $script:ProviderQueue.Enqueue($item) }
}

function Invoke-LocalDocker {
    [CmdletBinding()]
    param(
        [int]$CommandTimeoutMilliseconds,
        [Parameter(ValueFromRemainingArguments = $true)][object[]]$DockerArguments
    )
    if ($script:ProviderQueue.Count -eq 0) { throw "provider queue exhausted" }
    $item = $script:ProviderQueue.Dequeue()
    $script:ProviderCalls.Add([pscustomobject]@{
            timeout = $CommandTimeoutMilliseconds
            arguments = @($DockerArguments | ForEach-Object { [string]$_ })
        })
    $script:LocalDockerExitCode = [int]$item.exit_code
    $script:LocalDockerOutcomeCode = [string]$item.outcome
    if (-not [string]::IsNullOrEmpty([string]$item.error)) {
        throw [InvalidOperationException]::new([string]$item.error)
    }
    foreach ($line in @($item.output)) { Write-Output ([string]$line) }
}

function New-ProviderItem {
    param(
        [string]$Outcome = "LOCAL_DOCKER_CLI_SUCCEEDED",
        [int]$ExitCode = 0,
        [object[]]$Output = @(),
        [string]$Error = ""
    )
    return [pscustomobject]@{
        outcome = $Outcome
        exit_code = $ExitCode
        output = @($Output)
        error = $Error
    }
}

function Invoke-CapturedScenario {
    param([Parameter(Mandatory = $true)][scriptblock]$Action)
    $errorMessage = ""
    $errorDetail = ""
    $value = $null
    try { $value = & $Action }
    catch {
        $errorMessage = $_.Exception.Message
        $errorDetail = [string]$_.InvocationInfo.PositionMessage + "|" +
            [string]$_.ScriptStackTrace
    }
    return [pscustomobject]@{
        error = $errorMessage
        error_detail = $errorDetail
        value = $value
        calls = @($script:ProviderCalls)
        remaining = $script:ProviderQueue.Count
    }
}

$timeout = New-ProviderItem `
    -Outcome "LOCAL_DOCKER_CLI_TIMED_OUT" `
    -ExitCode 124 `
    -Error "LOCAL_DOCKER_COMMAND_TIMEOUT"
$success = New-ProviderItem

Set-ProviderQueue -Items @($timeout, $success)
$removeAccepted = Invoke-CapturedScenario {
    Remove-LocalProxyContainerBounded -Name "proxy"
    return "accepted"
}

Set-ProviderQueue -Items @(
    $timeout,
    (New-ProviderItem -Output @("cid-present")))
$removeBlocked = Invoke-CapturedScenario {
    Remove-LocalProxyContainerBounded -Name "proxy"
    return "unexpected"
}

$nginx = Join-Path $SandboxRoot "nginx.conf"
$mtls = Join-Path $SandboxRoot "mtls"
[IO.File]::WriteAllText($nginx, "fixture")
[IO.Directory]::CreateDirectory($mtls) | Out-Null
$inspectExact = @([pscustomobject]@{
        Name = "/proxy"
        Config = [pscustomobject]@{ Image = "image:test" }
        State = [pscustomobject]@{ Running = $true }
        HostConfig = [pscustomobject]@{
            PortBindings = [pscustomobject]@{
                "8443/tcp" = @([pscustomobject]@{ HostIp = "127.0.0.1"; HostPort = "18443" })
                "8080/tcp" = @([pscustomobject]@{ HostIp = "127.0.0.1"; HostPort = "18080" })
            }
        }
        Mounts = @(
            [pscustomobject]@{
                Type = "bind"; Destination = "/etc/nginx/conf.d/default.conf"
                RW = $false; Source = $nginx
            },
            [pscustomobject]@{
                Type = "bind"; Destination = "/run/local-target/mtls"
                RW = $false; Source = $mtls
            })
    }) | ConvertTo-Json -Depth 10 -Compress
Set-ProviderQueue -Items @(
    (New-ProviderItem -Output @("cid-exact")),
    (New-ProviderItem -Output @($inspectExact)))
$exactReadback = Invoke-CapturedScenario {
    return Get-LocalProxyContainerState `
        -Name "proxy" `
        -ExpectedImage "image:test" `
        -ExpectedNginxConfig $nginx `
        -ExpectedMtlsDirectory $mtls
}
Set-ProviderQueue -Items @(
    $timeout,
    (New-ProviderItem -Output @("cid-exact")),
    (New-ProviderItem -Output @($inspectExact)))
$startAccepted = Invoke-CapturedScenario {
    return Start-LocalProxyContainerBounded `
        -Name "proxy" `
        -Image "image:test" `
        -NginxConfig $nginx `
        -MtlsDirectory $mtls
}

$inspectMismatch = $inspectExact.Replace('"image:test"', '"image:wrong"')
Set-ProviderQueue -Items @(
    $timeout,
    (New-ProviderItem -Output @("cid-mismatch")),
    (New-ProviderItem -Output @($inspectMismatch)),
    $success,
    $success)
$startBlocked = Invoke-CapturedScenario {
    Start-LocalProxyContainerBounded `
        -Name "proxy" `
        -Image "image:test" `
        -NginxConfig $nginx `
        -MtlsDirectory $mtls
    return "unexpected"
}

$oldRouting = @(
    [pscustomobject]@{
        isDefaultSet = $false
        defaultForSet = "legacy-build"
    },
    [pscustomobject]@{
        isDefaultSet = $true
        defaultForSet = "old-build"
    }) | ConvertTo-Json -Compress
$newRouting = @([pscustomobject]@{
        isDefaultSet = $true
        defaultForSet = "new-build"
    }) | ConvertTo-Json -Compress
$emptyRouting = "null"
Set-ProviderQueue -Items @(
    (New-ProviderItem -Output @($oldRouting)),
    $timeout,
    (New-ProviderItem -Output @($newRouting)))
$temporalAccepted = Invoke-CapturedScenario {
    Ensure-TemporalDefaultBuildId `
        -Container "temporal" `
        -TaskQueue "queue" `
        -BuildId "new-build"
    return "accepted"
}

Set-ProviderQueue -Items @(
    (New-ProviderItem -Output @($emptyRouting)),
    $success,
    (New-ProviderItem -Output @($newRouting)))
$temporalEmptyAccepted = Invoke-CapturedScenario {
    Ensure-TemporalDefaultBuildId `
        -Container "temporal" `
        -TaskQueue "empty-queue" `
        -BuildId "new-build"
    return "accepted"
}

Set-ProviderQueue -Items @(
    (New-ProviderItem -Output @("{}")))
$temporalMalformedBlocked = Invoke-CapturedScenario {
    Ensure-TemporalDefaultBuildId `
        -Container "temporal" `
        -TaskQueue "malformed-queue" `
        -BuildId "new-build"
    return "unexpected"
}

Set-ProviderQueue -Items @(
    (New-ProviderItem -Output @($oldRouting)),
    $timeout,
    (New-ProviderItem `
        -Outcome "LOCAL_DOCKER_CLI_NONZERO_EXIT" `
        -ExitCode 19 `
        -Error "readback failed"))
$temporalBlocked = Invoke-CapturedScenario {
    Ensure-TemporalDefaultBuildId `
        -Container "temporal" `
        -TaskQueue "queue" `
        -BuildId "new-build"
    return "unexpected"
}

$output = [pscustomobject]@{
    remove_accepted = $removeAccepted
    remove_blocked = $removeBlocked
    exact_readback = $exactReadback
    start_accepted = $startAccepted
    start_blocked = $startBlocked
    temporal_accepted = $temporalAccepted
    temporal_empty_accepted = $temporalEmptyAccepted
    temporal_malformed_blocked = $temporalMalformedBlocked
    temporal_blocked = $temporalBlocked
}
[IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Depth 30 -Compress),
    [Text.UTF8Encoding]::new($false))
'''.strip(),
        encoding="utf-8-sig",
    )
    completed = subprocess.run(
        [
            powershell,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-SandboxRoot",
            str(tmp_path),
        ],
        cwd=tmp_path,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def test_local_docker_mutations_reconcile_exact_state_without_blind_retry(
    tmp_path: Path,
) -> None:
    result = _run_local_docker_reconciliation_harness(tmp_path)

    exact_readback = result["exact_readback"]
    assert exact_readback["error"] == ""
    assert exact_readback["value"]["State"] == "EXACT_DESIRED"

    remove_accepted = result["remove_accepted"]
    assert remove_accepted["error"] == ""
    assert remove_accepted["value"] == "accepted"
    assert [call["arguments"][0] for call in remove_accepted["calls"]] == [
        "rm",
        "ps",
    ]
    assert remove_accepted["remaining"] == 0

    remove_blocked = result["remove_blocked"]
    assert remove_blocked["error"] == "LOCAL_DOCKER_PROXY_REMOVAL_UNPROVEN"
    assert [call["arguments"][0] for call in remove_blocked["calls"]] == [
        "rm",
        "ps",
    ]

    start_accepted = result["start_accepted"]
    assert start_accepted["error"] == ""
    assert start_accepted["value"] == "cid-exact"
    assert [call["arguments"][0] for call in start_accepted["calls"]] == [
        "run",
        "ps",
        "inspect",
    ]

    start_blocked = result["start_blocked"]
    assert start_blocked["error"] == "LOCAL_DOCKER_PROXY_START_UNPROVEN"
    assert [call["arguments"][0] for call in start_blocked["calls"]] == [
        "run",
        "ps",
        "inspect",
        "rm",
        "ps",
    ]

    temporal_accepted = result["temporal_accepted"]
    assert temporal_accepted["error"] == ""
    assert temporal_accepted["value"] == "accepted"
    temporal_commands = [call["arguments"] for call in temporal_accepted["calls"]]
    assert [arguments[0] for arguments in temporal_commands] == [
        "exec",
        "exec",
        "exec",
    ]
    assert sum("update-build-ids" in arguments for arguments in temporal_commands) == 1
    update_arguments = temporal_commands[1]
    assert "add-new-compatible" in update_arguments
    existing_index = update_arguments.index("--existing-compatible-build-id")
    assert update_arguments[existing_index + 1] == "old-build"

    temporal_empty_accepted = result["temporal_empty_accepted"]
    assert temporal_empty_accepted["error"] == ""
    assert temporal_empty_accepted["value"] == "accepted"
    temporal_empty_commands = [
        call["arguments"] for call in temporal_empty_accepted["calls"]
    ]
    assert [arguments[0] for arguments in temporal_empty_commands] == [
        "exec",
        "exec",
        "exec",
    ]
    assert sum(
        "update-build-ids" in arguments for arguments in temporal_empty_commands
    ) == 1
    assert "add-new-default" in temporal_empty_commands[1]
    assert "add-new-compatible" not in temporal_empty_commands[1]

    temporal_malformed_blocked = result["temporal_malformed_blocked"]
    assert temporal_malformed_blocked["error"] == (
        "LOCAL_DOCKER_TEMPORAL_BUILD_READ_INVALID"
    )
    assert len(temporal_malformed_blocked["calls"]) == 1
    assert all(
        "update-build-ids" not in arguments
        for arguments in (
            call["arguments"] for call in temporal_malformed_blocked["calls"]
        )
    )

    temporal_blocked = result["temporal_blocked"]
    assert temporal_blocked["error"] == (
        "LOCAL_DOCKER_TEMPORAL_BUILD_UPDATE_UNPROVEN:queue"
    )
    temporal_blocked_commands = [
        call["arguments"] for call in temporal_blocked["calls"]
    ]
    assert sum(
        "update-build-ids" in arguments for arguments in temporal_blocked_commands
    ) == 1
    assert all(
        scenario["remaining"] == 0
        for scenario in result.values()
    )


def _run_writer_substage_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    state_directory = tmp_path / scenario / "状态-所有权"
    state_directory.mkdir(parents=True, exist_ok=True)
    function_file = tmp_path / f"writer-substage-functions-{scenario}.ps1"
    harness_file = tmp_path / f"writer-substage-harness-{scenario}.ps1"
    result_file = tmp_path / f"writer-substage-result-{scenario}.json"
    function_file.write_text(bundle, encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$StateDirectory,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile

$warnings = [System.Collections.Generic.List[string]]::new()
function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warnings.Add($Message)
}
$recordPath = Join-Path $StateDirectory "python-agent.ownership.json"
$sentinel = "EXISTING_RECORD_MUST_NOT_BE_OVERWRITTEN"
$stage = $null
$failed = $false
$completed = $false
$beforeMove = $null
$temporaryCleanup = $null
if ($Scenario -eq "precheck") {
    [System.IO.File]::WriteAllText(
        $recordPath,
        $sentinel,
        [System.Text.UTF8Encoding]::new($false))
}
elseif ($Scenario -eq "pre-move") {
    $beforeMove = { throw "RAW_SENSITIVE_PRE_MOVE_MUST_NOT_ESCAPE" }
}
elseif ($Scenario -eq "move") {
    $beforeMove = {
        [System.IO.File]::WriteAllText(
            $recordPath,
            $sentinel,
            [System.Text.UTF8Encoding]::new($false))
    }.GetNewClosure()
}
elseif ($Scenario -eq "cleanup-precedence") {
    $beforeMove = { throw "RAW_SENSITIVE_PRIMARY_MUST_NOT_ESCAPE" }
    $temporaryCleanup = {
        param($TemporaryFile)
        $TemporaryFile.Refresh()
        if ($TemporaryFile.Exists) {
            $TemporaryFile.Delete()
        }
        throw "RAW_SENSITIVE_CLEANUP_MUST_NOT_ESCAPE"
    }
}

if ($Scenario -eq "closed-vocabulary") {
    $untrusted = [System.InvalidOperationException]::new(
        "RAW_SENSITIVE_EXCEPTION_MUST_NOT_ESCAPE")
    $untrusted.Data["SourceProcessOwnershipWriterFailureCarrier"] =
        "local-source-writer-stage.v1"
    $untrusted.Data["SourceProcessOwnershipWriterFailureStage"] =
        "RAW_SENSITIVE_STAGE_MUST_NOT_ESCAPE"
    $stage = Get-SourceProcessOwnershipWriterFailureStage -Failure $untrusted
}
else {
    try {
        Write-SourceProcessOwnershipRecordFile `
            -Record ([pscustomobject]@{
                schema_version = "test.v1"
                marker = "safe"
            }) `
            -RecordPath $recordPath `
            -BeforeMove $beforeMove `
            -TemporaryCleanup $temporaryCleanup | Out-Null
        $completed = $true
    }
    catch {
        $failed = $true
        $stage = Get-SourceProcessOwnershipWriterFailureStage `
            -Failure $_.Exception
        if ($null -ne $stage) {
            Write-SourceProcessOwnershipWriterStageFailure -Stage $stage
        }
    }
}

$finalExists = [System.IO.File]::Exists($recordPath)
$finalContent = if ($finalExists) {
    [System.IO.File]::ReadAllText(
        $recordPath,
        [System.Text.UTF8Encoding]::new($false, $true))
}
else {
    $null
}
$temporaryCount = @(
    Get-ChildItem `
        -LiteralPath $StateDirectory `
        -Filter "python-agent.ownership.json.*.tmp" `
        -File `
        -ErrorAction SilentlyContinue
).Count
[System.IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]@{
        stage = $stage
        warnings = @($warnings)
        failed = $failed
        completed = $completed
        final_exists = $finalExists
        final_content = $finalContent
        temporary_count = $temporaryCount
        sentinel = $sentinel
    } | ConvertTo-Json -Depth 10 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
            "-StateDirectory",
            str(state_directory),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


def _run_pre_move_predicate_harness(
    tmp_path: Path,
    *,
    bundle: str,
    scenario: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    scenario_directory = tmp_path / scenario
    scenario_directory.mkdir(parents=True, exist_ok=True)
    owner_file = scenario_directory / "invoke-pre-move-predicate-owner.ps1"
    result_file = scenario_directory / "pre-move-predicate-result.json"
    owner_source = (
        r"""
param(
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$Scenario
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
""".strip()
        + "\n\n"
        + bundle
        + "\n\n"
        + r"""
$warnings = [System.Collections.Generic.List[string]]::new()
function Write-Warning {
    param([Parameter(Position = 0)][string]$Message)
    [void]$warnings.Add($Message)
}

$capturedProcess = [System.Diagnostics.Process]::GetCurrentProcess()
$pidValue = $capturedProcess.Id
$creationDate = "2026-08-06T00:00:00.0430000Z"
$executable = "C:\runtime\java.exe"
$workingDirectory = Join-Path $ProjectRoot "java-api-service"
$classpath = Join-Path $workingDirectory "target\target-e2e-classes"
$expectedCommand = '"' + $executable + '" -cp "' + $classpath +
    '" com.example.dispute.DisputeApplication ' +
    '--app.temporal.worker.role=API'
$recordDirectory = Join-Path (Split-Path -Parent $ResultFile) "ownership"
[void][System.IO.Directory]::CreateDirectory($recordDirectory)
$recordPath = Join-Path $recordDirectory "java-api.ownership.json"
$tombstone = [pscustomobject][ordered]@{
    schema_version = "local-source-process-launch-tombstone.v1"
    name = "java-api"
    process_kind = "JAVA"
    pid = $pidValue
    expected_executable_path = $executable
    expected_command_line = $expectedCommand
    working_directory = $workingDirectory
    project_root = $ProjectRoot
    launched_at = "2026-08-06T00:00:00.0420000Z"
    launcher_pid = 7100
    launcher_creation_date = "2026-08-05T23:59:00.0000000Z"
    launcher_executable_path =
        "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
}
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipLaunchTombstone" `
    -NotePropertyValue $tombstone `
    -Force

function Get-SourceProcessOwnershipNativeHandleSnapshot {
    param([Parameter(Mandatory = $true)]$Process)
    return [pscustomobject]@{
        Pid = $pidValue
        CreationDate = $creationDate
        ExitDate = $null
        ExecutablePath = $executable
        IsAlive = $true
    }
}
function Get-SourceProcessOwnershipNativeHandleExitState {
    param([Parameter(Mandatory = $true)]$Process)
    return [pscustomobject]@{
        Pid = $pidValue
        CreationDate = $creationDate
        ExitDate = $null
        IsExited = $false
    }
}

$rootAuthority = Bind-SourceProcessOwnershipRootInstance `
    -CapturedProcess $capturedProcess `
    -RootInstanceProvider `
        ${function:Get-SourceProcessOwnershipRootInstanceFromHandle}
$identity = Get-SourceProcessOwnershipCapturedIdentity `
    -CapturedProcess $capturedProcess `
    -Name "java-api" `
    -ProcessKind "JAVA" `
    -WorkingDirectory $workingDirectory `
    -ProjectRoot $ProjectRoot
Add-Member -InputObject $capturedProcess `
    -NotePropertyName "SourceOwnershipCapturedIdentity" `
    -NotePropertyValue $identity `
    -Force

$script:stateCalls = 0
function Get-SourceProcessOwnershipHandleExitState {
    param([Parameter(Mandatory = $true)]$CapturedProcess)
    $script:stateCalls += 1
    if ($script:stateCalls -eq 1 -or $Scenario -eq "live") {
        return [pscustomobject]@{
            Pid = $pidValue
            CreationDate = $creationDate
            ExitDate = $null
            IsExited = $false
        }
    }
    switch ($Scenario) {
        "provider-error" {
            throw "RAW_SENSITIVE_HANDLE_STATE_ERROR_MUST_NOT_ESCAPE"
        }
        "pid-binding" {
            return [pscustomobject]@{
                Pid = $pidValue + 1
                CreationDate = $creationDate
                ExitDate = $null
                IsExited = $false
            }
        }
        "creation-binding" {
            return [pscustomobject]@{
                Pid = $pidValue
                CreationDate = "2026-08-06T00:00:00.0450000Z"
                ExitDate = $null
                IsExited = $false
            }
        }
        "state-shape" {
            return [pscustomobject]@{
                Pid = $pidValue
                CreationDate = $creationDate
                ExitDate = $null
                IsExited = $null
            }
        }
        "process-exited" {
            return [pscustomobject]@{
                Pid = $pidValue
                CreationDate = $creationDate
                ExitDate = "2026-08-06T00:00:01.0000000Z"
                IsExited = $true
            }
        }
        default { throw "UNKNOWN_TEST_SCENARIO" }
    }
}

$productionPreMovePredicate =
    ${function:Invoke-SourceProcessOwnershipPreMovePredicate}
$script:expectedCapturedProcess = $capturedProcess
$script:predicateCalls = 0
$script:predicateProcessReference = $false
$script:predicateIdentityReference = $false
function Invoke-SourceProcessOwnershipPreMovePredicate {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$CapturedProcess,
        [Parameter(Mandatory = $true)]$Identity
    )
    $script:predicateCalls += 1
    $script:predicateProcessReference = [object]::ReferenceEquals(
        $script:expectedCapturedProcess,
        $CapturedProcess)
    $rootIdentityProperty = $CapturedProcess.PSObject.Properties[
        "SourceOwnershipRootIdentity"
    ]
    $script:predicateIdentityReference =
        $null -ne $rootIdentityProperty -and
        [object]::ReferenceEquals($rootIdentityProperty.Value, $Identity)
    & $productionPreMovePredicate `
        -CapturedProcess $CapturedProcess `
        -Identity $Identity
}

$completed = $false
$failed = $false
$writerStage = $null
try {
    $completed = [bool](Write-SourceProcessOwnershipRecord `
        -Name "java-api" `
        -ProcessKind "JAVA" `
        -CapturedProcess $capturedProcess `
        -WorkingDirectory $workingDirectory `
        -ProjectRoot $ProjectRoot `
        -OwnershipRecordDirectory $recordDirectory)
}
catch {
    $failed = $true
    $writerStage = Get-SourceProcessOwnershipWriterFailureStage `
        -Failure $_.Exception
}
$temporaryCount = @(
    Get-ChildItem `
        -LiteralPath $recordDirectory `
        -Filter "java-api.ownership.json.*.tmp" `
        -File `
        -ErrorAction SilentlyContinue
).Count
[System.IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]@{
        warnings = @($warnings)
        completed = $completed
        failed = $failed
        writer_stage = $writerStage
        predicate_calls = $script:predicateCalls
        predicate_process_reference = $script:predicateProcessReference
        predicate_identity_reference = $script:predicateIdentityReference
        state_calls = $script:stateCalls
        final_exists = [System.IO.File]::Exists($recordPath)
        temporary_count = $temporaryCount
        retained_root_reference = [object]::ReferenceEquals(
            $rootAuthority.handle_reference,
            $capturedProcess)
    } | ConvertTo-Json -Depth 10 -Compress),
    [System.Text.UTF8Encoding]::new($false))
""".strip()
    )
    owner_file.write_text(owner_source, encoding="utf-8")
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(owner_file),
            "-ResultFile",
            str(result_file),
            "-ProjectRoot",
            str(tmp_path / "candidate"),
            "-Scenario",
            scenario,
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    assert result_file.is_file(), completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8-sig"))


@pytest.mark.parametrize(
    ("fixture_kind", "captured_name", "expected_code"),
    (
        ("frontend", "frontend", None),
        ("frontend", "python-agent", "PROCESS_IDENTITY_MISMATCH"),
        ("python-null-wd", "python-agent", None),
        ("python-null-wd", "frontend", "PROCESS_IDENTITY_MISMATCH"),
    ),
)
def test_live_unpublished_root_plan_preserves_authoritative_role_binding(
    tmp_path: Path,
    fixture_kind: str,
    captured_name: str,
    expected_code: str | None,
) -> None:
    bundle = _launch_tombstone_contract_bundle_or_fail()
    project_root = tmp_path / "candidate"
    snapshot = None
    if fixture_kind == "frontend":
        record = _ownership_record(
            project_root,
            name="frontend",
            pid=6200,
            process_kind="FRONTEND",
        )
        system_cmd = Path("C:/Windows/System32/cmd.exe")
        record["executable_path"] = str(system_cmd)
        record["command_line"] = (
            f'"{system_cmd}" /d /c pnpm --dir '
            f'"{record["working_directory"]}" dev '
        )
    else:
        assert fixture_kind == "python-null-wd"
        record = _ownership_record(
            project_root,
            name="python-agent",
            pid=6210,
            process_kind="PYTHON",
        )
        python_executable = Path("D:/miniconda/python.exe")
        app_directory = project_root / "deploy" / "target-e2e" / "python"
        record["executable_path"] = str(python_executable)
        record["command_line"] = (
            f'"{python_executable}" -m uvicorn mtls_adapter:create_app --factory '
            f'--app-dir {app_directory} --host 127.0.0.1 --port 18000 '
            "--loop asyncio:SelectorEventLoop "
        )
        snapshot = [
            {
                "ProcessId": record["pid"],
                "ParentProcessId": record["parent_pid"],
                "CreationDate": record["creation_date"],
                "ExecutablePath": record["executable_path"],
                "CommandLine": record["command_line"],
                "WorkingDirectory": None,
            },
            {
                "ProcessId": record["pid"] + 1,
                "ParentProcessId": record["pid"],
                "CreationDate": "2026-08-06T00:00:00.0430000Z",
                "ExecutablePath": "C:/Windows/System32/conhost.exe",
                "CommandLine": r"\??\C:\WINDOWS\system32\conhost.exe 0x4",
                "WorkingDirectory": None,
            },
        ]

    result = _run_live_unpublished_root_plan_harness(
        tmp_path,
        bundle=bundle,
        record=record,
        captured_name=captured_name,
        snapshot=snapshot,
    )

    assert result["code"] == expected_code
    if expected_code is None:
        assert result["root_name"] == record["name"]
        assert result["root_process_kind"] == record["process_kind"]
        expected_pids = (
            [process["ProcessId"] for process in snapshot]
            if snapshot is not None
            else [record["pid"]]
        )
        assert result["process_pids"] == expected_pids
    else:
        assert result["root_name"] is None
        assert result["root_process_kind"] is None
        assert not result["process_pids"]


@pytest.mark.parametrize(
    "scenario",
    (
        "trailing",
        "argv-extra",
        "argv-reordered",
        "argv-changed",
        "creation-submillisecond",
        "authority-mismatch",
        "existing",
    ),
)
def test_cim_free_publication_authority_and_command_replay(
    tmp_path: Path,
    scenario: str,
) -> None:
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    writer = definitions["Write-SourceProcessOwnershipRecord"]
    assert "SourceProcessSnapshotProvider" not in writer, (
        "initial publication must not depend on transient CIM readiness"
    )
    assert "New-SourceProcessOwnershipPublicationRecord" in writer
    assert "Write-SourceProcessOwnershipRecordFile" in writer
    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_cim_free_publication_harness(
        tmp_path,
        bundle=bundle,
        scenario=scenario,
    )

    if scenario == "authority-mismatch":
        assert result["record"] is None
        assert result["build_error"]
        assert result["final_exists"] is False
        assert result["temporary_count"] == 0
        return

    record = result["record"]
    assert set(record) == OWNERSHIP_FIELDS
    project_root = str(tmp_path / "candidate")
    assert record == {
        "schema_version": OWNERSHIP_SCHEMA,
        "name": "python-agent",
        "process_kind": "PYTHON",
        "pid": 6200,
        "parent_pid": 6100,
        "creation_date": "2026-08-06T00:00:00.0430000+00:00",
        "executable_path": r"D:\miniconda\python.exe",
        "command_line": result["expected_command"],
        "working_directory": str(Path(project_root) / "python-agent-service"),
        "project_root": project_root,
    }
    assert result["build_error"] is None
    assert result["temporary_count"] == 0

    if scenario.startswith("argv-"):
        assert result["command_equivalent"] is False
        assert result["root_identity_accepted"] is False
    else:
        assert result["command_equivalent"] is True
        assert result["root_identity_accepted"] is True

    if scenario == "existing":
        assert result["write_error"]
        assert result["final_content"] == result["sentinel"]
    else:
        assert result["write_error"] is None
        assert json.loads(result["final_content"]) == record
        assert result["final_has_bom"] is False


@pytest.mark.parametrize(
    "scenario",
    (
        "java-success",
        "python-success",
        "frontend-success",
        "actual-exe-mismatch",
        "pid-mismatch",
        "creation-mismatch",
        "provider-failure",
        "exit-before-move",
        "unpublished-reused-descendant",
        "captured-root-exit",
        "published-tree-exit",
        "unpublished-plan-exit",
        "exit-pid-mismatch",
        "exit-creation-mismatch",
        "exit-wait-timeout",
        "exit-wait-failed",
        "exit-zero-time",
    ),
)
def test_native_handle_identity_is_shared_by_publication_and_compensation(
    tmp_path: Path,
    scenario: str,
) -> None:
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    native_helper = "Get-SourceProcessOwnershipNativeHandleSnapshot"
    shared_helper = "Get-SourceProcessOwnershipHandleSnapshot"
    matcher = "Test-SourceProcessOwnershipHandleSnapshotMatchesIdentity"
    native_exit_helper = "Get-SourceProcessOwnershipNativeHandleExitState"
    shared_exit_helper = "Get-SourceProcessOwnershipHandleExitState"
    exit_matcher = "Test-SourceProcessOwnershipHandleExitStateMatchesIdentity"
    for helper in (native_helper, shared_helper, matcher):
        assert helper in definitions, f"{helper} must be one closed handle authority"
    for helper in (native_exit_helper, shared_exit_helper, exit_matcher):
        assert helper in definitions, f"{helper} must be one closed exit authority"
    native_source = definitions[native_helper]
    for native_call in (
        "GetProcessId",
        "GetProcessTimes",
        "QueryFullProcessImageName",
        "WaitForSingleObject",
    ):
        assert native_call in native_source
    assert "SnapshotProvider" in definitions[shared_helper]
    native_exit_source = definitions[native_exit_helper]
    for native_call in ("GetProcessId", "GetProcessTimes", "WaitForSingleObject"):
        assert native_call in native_exit_source
    assert "QueryFullProcessImageName" not in native_exit_source
    assert "StateProvider" in definitions[shared_exit_helper]

    live_identity_consumers = (
        "Get-SourceProcessOwnershipRootInstanceFromHandle",
        "Get-SourceProcessOwnershipCapturedIdentity",
        "Write-SourceProcessOwnershipRecord",
    )
    termination_consumers = (
        "Invoke-SourceProcessOwnershipCapturedRootTermination",
        "Invoke-SourceProcessOwnershipTreeTermination",
        "Invoke-SourceProcessOwnershipUnpublishedPlanTermination",
    )
    exit_consumers = (
        "Test-SourceProcessOwnershipCapturedProcessExited",
        "Update-SourceProcessOwnershipRootInstanceExitDate",
    )
    forbidden_managed_identity = (".StartTime", ".MainModule", ".HasExited")
    for consumer in live_identity_consumers:
        consumer_source = definitions[consumer]
        assert shared_helper in consumer_source
        assert not any(
            forbidden in consumer_source for forbidden in forbidden_managed_identity
        ), f"{consumer} must use only the shared native handle snapshot"
    for consumer in termination_consumers:
        consumer_source = definitions[consumer]
        assert shared_helper in consumer_source
        assert shared_exit_helper in consumer_source
        after_wait = consumer_source.split("WaitForExit", maxsplit=1)[1]
        assert shared_helper not in after_wait
        assert shared_exit_helper in after_wait
        assert exit_matcher in after_wait
        assert not any(
            forbidden in consumer_source for forbidden in forbidden_managed_identity
        ), f"{consumer} must keep one native handle across termination"
    for consumer in exit_consumers:
        consumer_source = definitions[consumer]
        assert shared_helper not in consumer_source
        assert shared_exit_helper in consumer_source
        assert exit_matcher in consumer_source
        assert not any(
            forbidden in consumer_source for forbidden in forbidden_managed_identity
        ), f"{consumer} must use the image-free native exit state"
    unpublished_source = definitions[
        "Invoke-SourceProcessOwnershipUnpublishedPlanTermination"
    ]
    assert matcher in unpublished_source
    assert "$CapturedProcess" in unpublished_source
    publish_source = definitions[PUBLICATION_ENTRYPOINT]
    assert "Write-SourceProcessOwnershipSafeStageFailure" in publish_source
    assert "CAPTURE_HANDLE_IDENTITY" in publish_source
    assert "ATOMIC_PUBLISH" in publish_source
    assert "COMPENSATION_TERMINATE" in publish_source
    assert "COMPENSATION_POSTCONDITION" in publish_source

    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_native_handle_identity_harness(
        tmp_path,
        bundle=bundle,
        scenario=scenario,
    )
    if scenario.endswith("success"):
        assert result["identity_error"] is None
        assert result["identity"]["Pid"] == 7200
        assert result["identity"]["CreationDate"].endswith("+00:00")
        assert result["provider_calls"] == 1
    elif scenario in {
        "actual-exe-mismatch",
        "pid-mismatch",
        "creation-mismatch",
        "provider-failure",
    }:
        assert result["identity"] is None
        assert result["identity_error"]
        assert result["final_exists"] is False
        assert result["temporary_count"] == 0
    elif scenario == "exit-before-move":
        assert result["identity_error"] is None
        assert result["write_error"]
        assert result["provider_calls"] == 2
        assert result["final_exists"] is False
        assert result["temporary_count"] == 0
    elif scenario == "unpublished-reused-descendant":
        assert result["identity_error"] is None
        assert result["root_match"] is True
        assert result["reused_descendant_match"] is False
    elif scenario in {
        "captured-root-exit",
        "published-tree-exit",
        "unpublished-plan-exit",
    }:
        assert result["identity_error"] is None
        assert result["provider_calls"] == 1
        assert result["exit_provider_calls"] == 1
        assert result["exit_state_error"] is None
        assert result["exit_match"] is True
    elif scenario in {"exit-creation-mismatch", "exit-wait-timeout"}:
        assert result["identity_error"] is None
        assert result["provider_calls"] == 1
        assert result["exit_provider_calls"] == 1
        assert result["exit_state_error"] is None
        assert result["exit_match"] is False
    else:
        assert scenario in {
            "exit-pid-mismatch",
            "exit-wait-failed",
            "exit-zero-time",
        }
        assert result["identity_error"] is None
        assert result["provider_calls"] == 1
        assert result["exit_provider_calls"] == 1
        assert result["exit_state"] is None
        assert result["exit_state_error"]


@pytest.mark.parametrize(
    "scenario",
    (
        "single-full-success",
        "wrong-executable",
        "wrong-pid",
        "wrong-creation",
        "exited-state",
        "descendant-full-bind-mismatch",
    ),
)
def test_retained_root_handle_authority_uses_single_full_image_bind(
    tmp_path: Path,
    scenario: str,
) -> None:
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    full_helper = "Get-SourceProcessOwnershipHandleSnapshot"
    state_helper = "Get-SourceProcessOwnershipHandleExitState"
    root_provider = definitions["Get-SourceProcessOwnershipRootInstanceFromHandle"]
    root_validator = definitions["Test-SourceProcessOwnershipRootInstanceAuthority"]
    capture = definitions["Get-SourceProcessOwnershipCapturedIdentity"]
    writer = definitions["Write-SourceProcessOwnershipRecord"]
    unpublished_bind = definitions[
        "Invoke-SourceProcessOwnershipUnpublishedPlanTermination"
    ]
    unpublished_gate = definitions[
        "Invoke-SourceProcessOwnershipUnpublishedTermination"
    ]

    assert "local-source-process-root-instance.v2" in root_validator
    for field in ("executable_path", "is_alive_at_bind", "handle_reference"):
        assert f'"{field}"' in root_validator
    assert full_helper in root_provider
    assert "ExecutablePath = $snapshot.ExecutablePath" in root_provider
    assert "IsAlive = $snapshot.IsAlive" in root_provider
    assert full_helper not in capture
    assert state_helper in capture
    assert full_helper not in writer
    assert writer.count(state_helper) == 2
    assert "$usesCapturedRoot" in unpublished_bind
    assert unpublished_bind.count(full_helper) == 1
    assert state_helper in unpublished_bind
    assert "Sort-Object -Property Depth -Descending" in unpublished_bind
    assert unpublished_gate.count("$script:SourceProcessSnapshotProvider") == 3
    assert "$script:SourceProcessProtectedPolicy" in unpublished_gate

    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_retained_root_handle_authority_harness(
        tmp_path,
        bundle=bundle,
        scenario=scenario,
    )
    expected_full_image_calls = (
        2
        if scenario in {"wrong-executable", "wrong-pid", "exited-state"}
        else 1
    )
    assert result["full_image_calls"] == expected_full_image_calls
    if scenario == "single-full-success":
        assert result["bind_error"] is None
        assert result["publish_error"] is None
        assert result["publish_code"] == "PUBLISHED"
        assert result["compensation_error"] is None
        assert result["writer_calls"] == 1
        assert result["state_calls"] >= 2
        assert result["root_schema"] == "local-source-process-root-instance.v2"
    elif scenario in {"wrong-executable", "wrong-pid", "exited-state"}:
        assert result["bind_error"]
        assert result["publish_code"] is None
        assert result["writer_calls"] == 0
    elif scenario == "wrong-creation":
        assert result["bind_error"] is None
        assert result["capture_error"]
        assert result["publish_code"] is None
    else:
        assert scenario == "descendant-full-bind-mismatch"
        assert result["bind_error"] is None
        assert result["publish_error"] is None
        assert result["publish_code"] == "PUBLISHED"
        assert result["descendant_match"] is False


@pytest.mark.parametrize(
    "scenario",
    (
        "full-image-throw",
        "full-image-throw-no-child",
        "invalid-pid",
        "invalid-creation",
        "invalid-handle",
        "invalid-exited",
        "invalid-executable",
        "invalid-tombstone",
        "protected-descendant",
        "snapshot-drift",
        "full-success",
    ),
)
def test_pre_authority_cleanup_is_closed_before_full_image_bind(
    tmp_path: Path,
    scenario: str,
) -> None:
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    validator = definitions["Test-SourceProcessOwnershipPreAuthority"]
    binder = definitions["Bind-SourceProcessOwnershipRootInstance"]
    planner = definitions["New-SourceProcessOwnershipUnpublishedPlan"]
    capture = definitions["Get-SourceProcessOwnershipCapturedIdentity"]
    writer = definitions["Write-SourceProcessOwnershipRecord"]
    publisher = definitions[PUBLICATION_ENTRYPOINT]

    assert "local-source-process-preauthority.v1" in validator
    for field in (
        "handle_reference",
        "expected_executable_path",
        "expected_command_line",
        "launcher_creation_date",
        "is_running_at_bind",
    ):
        assert f'"{field}"' in validator
    stages = (
        "PREAUTH_STATE",
        "PREAUTH_ATTACH",
        "FULL_IMAGE_BIND",
        "AUTHORITY_BUILD",
        "AUTHORITY_VALIDATE",
        "AUTHORITY_ATTACH",
        "PREAUTH_TERMINATE",
    )
    for stage in stages:
        assert f'"{stage}"' in binder or stage in definitions[
            "Write-SourceProcessOwnershipSafeBindStageFailure"
        ]
    assert binder.index("PREAUTH_ATTACH") < binder.index("FULL_IMAGE_BIND")
    assert "SourceOwnershipPreAuthority" in planner
    assert "$null -eq $rootInstanceAuthority -and $null -ne $preAuthority" in planner
    assert "$usesPreAuthority -and -not $rootIsPresent" in planner
    for forbidden_consumer in (capture, writer, publisher):
        assert "SourceOwnershipPreAuthority" not in forbidden_consumer

    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_pre_authority_cleanup_harness(
        tmp_path,
        bundle=bundle,
        scenario=scenario,
    )
    if scenario.startswith("full-image-throw"):
        assert result["bind_error"] == "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_FAILED"
        assert result["pre_schema"] == "local-source-process-preauthority.v1"
        assert result["pre_valid"] is True
        assert result["terminator_calls"] == 1
        assert result["warnings"] == [
            "SOURCE_PROCESS_OWNERSHIP_BIND_STAGE_FAILED:FULL_IMAGE_BIND"
        ]
        assert result["registry_before"] == 1
        assert result["registry_same_reference"] is True
        assert result["compensation_before"] is False
        assert result["registry_after"] == 0
        assert result["compensation_after"] is True
        assert result["snapshot_calls"] == 3
        if scenario == "full-image-throw":
            assert result["plan_code"] is None
            assert result["plan_count"] == 1
            assert result["plan_termination_calls"] == 1
            assert result["terminated_plan_pids"] == [7401]
        else:
            assert result["plan_termination_calls"] == 0
            assert result["terminated_plan_pids"] == []
    elif scenario.startswith("invalid-"):
        assert result["pre_valid"] is False
    elif scenario == "protected-descendant":
        assert result["pre_valid"] is True
        assert result["plan_code"] == "PROTECTED_PROCESS"
    elif scenario == "snapshot-drift":
        assert result["pre_valid"] is True
        assert result["plan_code"] == "PROCESS_IDENTITY_MISMATCH"
    else:
        assert scenario == "full-success"
        assert result["bind_error"] is None
        assert result["pre_valid"] is True
        assert result["root_schema"] == "local-source-process-root-instance.v2"
        retained_path = tmp_path / "retained"
        retained_path.mkdir()
        retained = _run_retained_root_handle_authority_harness(
            retained_path,
            bundle=bundle,
            scenario="single-full-success",
        )
        assert retained["publish_code"] == "PUBLISHED"
        assert retained["full_image_calls"] == 1


@pytest.mark.parametrize(
    "scenario",
    (
        "AUTHORITY_SHAPE",
        "SCHEMA_VERSION",
        "PID_BINDING",
        "BIND_LIVENESS",
        "HANDLE_REFERENCE",
        "CREATION_VALUE",
        "EXECUTABLE_VALUE",
        "EXECUTABLE_BINDING",
        "LAUNCH_ORDER",
        "EXIT_VALUE",
        "VALID",
        "BIND_WARNING_ORDER",
    ),
)
def test_root_authority_predicate_diagnostics_are_closed_and_exact(
    tmp_path: Path,
    scenario: str,
) -> None:
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    code_helper = "Get-SourceProcessOwnershipRootInstanceAuthorityValidationCode"
    warning_helper = "Write-SourceProcessOwnershipRootAuthorityPredicateFailure"
    assert code_helper in definitions
    assert warning_helper in definitions
    inventory = {
        "AUTHORITY_SHAPE",
        "SCHEMA_VERSION",
        "PID_BINDING",
        "BIND_LIVENESS",
        "HANDLE_REFERENCE",
        "CREATION_VALUE",
        "EXECUTABLE_VALUE",
        "EXECUTABLE_BINDING",
        "LAUNCH_ORDER",
        "EXIT_VALUE",
        "VALID",
    }
    helper = definitions[code_helper]
    for code in inventory:
        assert f'"{code}"' in helper or f'"{code}"' in definitions[warning_helper]
    validator = definitions["Test-SourceProcessOwnershipRootInstanceAuthority"]
    binder = definitions["Bind-SourceProcessOwnershipRootInstance"]
    assert code_helper in validator
    assert code_helper in binder
    assert warning_helper in binder
    assert binder.index(warning_helper) < binder.index(
        'throw "Root instance authority is invalid."'
    )

    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_root_authority_predicate_harness(
        tmp_path,
        bundle=bundle,
        scenario=scenario,
    )
    expected = "VALID" if scenario == "BIND_WARNING_ORDER" else scenario
    assert result["code"] == expected
    assert result["accepted"] is (expected == "VALID")
    if scenario == "BIND_WARNING_ORDER":
        assert result["bind_error"] == "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_FAILED"
        assert result["warnings"] == [
            "SOURCE_PROCESS_OWNERSHIP_ROOT_AUTHORITY_PREDICATE_FAILED:"
            "EXECUTABLE_BINDING",
            "SOURCE_PROCESS_OWNERSHIP_BIND_STAGE_FAILED:AUTHORITY_VALIDATE",
        ]
    else:
        assert result["warnings"] == []


@pytest.mark.parametrize(
    "scenario",
    (
        "JAVA_HOME_MISSING",
        "JAVA_HOME_BLANK",
        "JAVA_HOME_RELATIVE",
        "JAVA_HOME_QUOTED",
        "JAVA_HOME_CONTROL",
        "VALID_DIRECT",
        "MULTILINE_STDERR",
        "CROSS_STREAM_DUPLICATE",
        "HOME_MISMATCH",
        "NONZERO",
        "MISSING_LEAF",
        "MALFORMED_RESULT",
        "TIMEOUT",
        "LIFECYCLE_SUCCESS",
        "LIFECYCLE_TIMEOUT",
        "LIFECYCLE_STREAM_EXCEPTION",
        "LIFECYCLE_WAIT_EXCEPTION",
        "LIFECYCLE_RETRY",
        "LIFECYCLE_INITIAL_BIND_EXCEPTION",
    ),
)
def test_java_executable_resolution_is_self_verified_and_closed(
    tmp_path: Path,
    scenario: str,
) -> None:
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    required = (
        "Invoke-SourceJavaExecutableProbe",
        "Get-SourceJavaProbeCleanupAuthorityState",
        "Test-SourceJavaProbeCleanupAuthorityBundle",
        "Complete-SourceJavaProbeCleanupAuthorityBundle",
        "New-SourceJavaProbeCleanupAuthorityBundle",
        "Stop-SourceJavaProbeProcessExact",
        "Wait-SourceJavaProbeCleanupProof",
        "Get-SourceJavaHomeFromProbeResult",
        "Resolve-SourceJavaExecutable",
    )
    assert set(required) <= set(definitions)
    runner = definitions[required[0]]
    authority_state = definitions[required[1]]
    authority_validator = definitions[required[2]]
    authority_completion = definitions[required[3]]
    authority_constructor = definitions[required[4]]
    cleanup = definitions[required[5]]
    cleanup_loop = definitions[required[6]]
    resolver = definitions[required[8]]
    combined = "\n".join(definitions[name] for name in required)
    assert "UseShellExecute = $false" in runner
    assert "RedirectStandardOutput = $true" in runner
    assert "RedirectStandardError = $true" in runner
    assert "CreateNoWindow = $true" in runner
    assert runner.count("ReadToEndAsync()") == 2
    assert "New-SourceJavaProbeCleanupAuthorityBundle" in runner
    assert "Complete-SourceJavaProbeCleanupAuthorityBundle" in runner
    assert "$provided = @(& $StateProvider $CapturedProcess)" in authority_state
    assert "CreationDate" in authority_state and "ExitDate" in authority_state
    assert "local-source-java-probe-cleanup-authority.v1" in authority_validator
    assert "Wait-SourceJavaProbeCleanupProof" in authority_constructor
    assert "DisposeProvider" in authority_completion
    assert "Wait-SourceJavaProbeCleanupProof" in runner
    assert "Test-SourceProcessOwnershipHandleExitStateMatchesIdentity" in cleanup
    state_validator = definitions[
        "Test-SourceProcessOwnershipHandleExitStateMatchesIdentity"
    ]
    assert "CreationDate" in state_validator and "ExitDate" in state_validator
    assert "Test-SourceProcessOwnershipCreationDateEquivalent" in state_validator
    assert "ProcessStartInfo.ArgumentList" not in combined
    assert "HashData" not in combined
    assert "while (-not $exitProven)" in cleanup_loop
    loop_body = cleanup_loop.split("while (-not $exitProven)", 1)[1]
    loop_body = loop_body.split("\n    return $true", 1)[0]
    assert "MaximumAttempts" not in loop_body
    assert re.search(r"(?m)^\s*(?:throw|return)\b", loop_body) is None
    assert "SOURCE_JAVA_EXECUTABLE_PROBE_CLEANUP_PENDING" in loop_body
    assert "$CapturedProcess" in loop_body
    assert "PauseAction" in loop_body
    assert '"-XshowSettings:properties"' in resolver
    assert '"-version"' in resolver
    assert "Bootstrap" not in resolver
    assert "Get-Command" not in resolver
    assert "JAVA_HOME" not in runner
    assert "Test-Path" in resolver and "-PathType Leaf" in resolver
    assert "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED" in resolver
    validator = definitions["Test-SourceProcessOwnershipRootInstanceAuthority"]
    assert "Resolve-SourceJavaExecutable" not in validator

    java_assignment = re.search(
        r"(?m)^\$javaExe\s*=\s*Resolve-SourceJavaExecutable\b",
        source,
    )
    assert java_assignment is not None
    assignment_tail = source[java_assignment.start() : java_assignment.start() + 500]
    assert "-JavaHome $env:JAVA_HOME" in assignment_tail
    assert 'Get-Command "java.exe"' not in source
    assert source.count("-FilePath $javaExe") == 2
    start_java = definitions["Start-JavaSourceProcess"]
    assert "Get-SourceProcessOwnershipCanonicalPath -Path $javaExe" in start_java
    assert "-ExpectedExecutablePath $expectedExecutablePath" in start_java
    assert "'\"' + $expectedExecutablePath + '\" '" in start_java

    java_home_path = tmp_path / "real-jdk"
    reported_home = java_home_path
    derived_leaf = java_home_path / "bin" / "java.exe"
    java_home = str(java_home_path)
    if scenario == "JAVA_HOME_MISSING":
        java_home = "__NULL__"
    elif scenario == "JAVA_HOME_BLANK":
        java_home = " "
    elif scenario == "JAVA_HOME_RELATIVE":
        java_home = "relative\\jdk"
    elif scenario == "JAVA_HOME_QUOTED":
        java_home = '"C:\\jdk"'
    elif scenario == "JAVA_HOME_CONTROL":
        java_home = "C:\\jdk\tbad"
    elif scenario == "HOME_MISMATCH":
        reported_home = tmp_path / "other-jdk"
    if scenario != "MISSING_LEAF":
        derived_leaf.parent.mkdir(parents=True)
        derived_leaf.write_bytes(b"")

    bundle = "\n\n".join(
        definitions[name]
        for name in (
            "Get-SourceProcessOwnershipCanonicalPath",
            "Test-SourceProcessOwnershipInteger",
            "ConvertTo-SourceProcessOwnershipDate",
            "Test-SourceProcessOwnershipCreationDateEquivalent",
            "Get-SourceProcessOwnershipHandleExitState",
            "Test-SourceProcessOwnershipHandleExitStateMatchesIdentity",
            *required,
        )
    )
    result = _run_java_executable_resolution_harness(
        tmp_path,
        bundle=bundle,
        scenario=scenario,
        java_home=java_home,
        reported_home=reported_home,
    )

    if scenario in {"VALID_DIRECT", "MULTILINE_STDERR"}:
        assert result["error"] is None
        assert Path(result["resolved"]).resolve() == derived_leaf.resolve()
        assert len(result["calls"]) == 1
        call = result["calls"][0]
        assert Path(call["executable_path"]).resolve() == derived_leaf.resolve()
        assert call["arguments"] == ["-XshowSettings:properties", "-version"]
        assert call["timeout_milliseconds"] == 1234
    elif scenario == "LIFECYCLE_SUCCESS":
        assert result["error"] is None
        assert result["resolved"]["schema_version"] == "local-source-java-probe.v1"
        assert result["kill_calls"] == []
        assert result["dispose_calls"] == [9300]
        assert len(result["state_calls"]) == 2
        assert result["cleanup_attempts"] == []
        assert result["pause_calls"] == []
        assert result["warnings"] == []
    elif scenario == "LIFECYCLE_RETRY":
        assert result["resolved"] is None
        assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
        assert result["cleanup_attempts"] == [1, 2, 3]
        assert result["cleanup_references"] == [True, True, True]
        assert result["pause_calls"] == [1, 2]
        assert result["warnings"] == [
            "SOURCE_JAVA_EXECUTABLE_PROBE_CLEANUP_PENDING",
            "SOURCE_JAVA_EXECUTABLE_PROBE_CLEANUP_PENDING",
        ]
        assert result["dispose_calls"] == [9300]
    elif scenario == "LIFECYCLE_INITIAL_BIND_EXCEPTION":
        assert result["resolved"] is None
        assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
        assert result["kill_calls"] == [9300]
        assert result["dispose_calls"] == [9300]
        assert len(result["state_calls"]) == 3
        assert result["pause_calls"] == []
        assert result["warnings"] == []
    elif scenario.startswith("LIFECYCLE_"):
        assert result["resolved"] is None
        assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
        assert result["kill_calls"] == [9300]
        assert result["dispose_calls"] == [9300]
        assert len(result["state_calls"]) == 3
        assert result["pause_calls"] == []
        assert result["warnings"] == []
    else:
        assert result["resolved"] is None
        assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
        expected_calls = 0 if scenario.startswith("JAVA_HOME_") or scenario == "MISSING_LEAF" else 1
        assert len(result["calls"]) == expected_calls


def test_real_java_probe_retained_handle_proves_exit_after_process_table_absence(
    tmp_path: Path,
) -> None:
    result = _run_real_java_probe_native_handle_harness(
        tmp_path,
        scenario="CONTROLLED_RETAINED_STATE",
    )

    assert result["error"] is None
    assert result["process_table_visible"] is False
    assert result["exited_result_count"] == 1
    assert result["running_state"] == {
        "Pid": result["pid"],
        "CreationDate": result["exited_state"]["CreationDate"],
        "ExitDate": None,
        "IsExited": False,
    }
    assert result["exited_state"]["Pid"] == result["pid"]
    assert result["exited_state"]["ExitDate"]
    assert result["exited_state"]["IsExited"] is True
    assert result["release_calls"] == [result["pid"]]
    assert result["dispose_calls"] == [result["pid"]]


def test_real_java_probe_post_exit_failure_cleans_on_first_attempt(
    tmp_path: Path,
) -> None:
    result = _run_real_java_probe_native_handle_harness(
        tmp_path,
        scenario="POST_EXIT_WAIT_FAILURE",
    )

    assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
    assert result["error_chain"].count("SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED") == 1
    assert result["process_table_visible"] is False
    assert result["failure_observation_error"] is None
    assert result["probe_wait_calls"] == ["timed", "flush"]
    assert result["child_exit_code"] == 0
    assert result["child_stderr"] == ""
    assert result["cleanup_attempts"] == [1]
    assert result["warnings"] == []
    assert result["pause_calls"] == []
    assert result["kill_calls"] == []
    assert result["wait_calls"] == []
    assert result["release_calls"] == []
    assert result["dispose_calls"] == [result["pid"]]
    assert result["completion_events"] == ["dispose"]
    assert result["elapsed_ms"] < 5000


def test_real_java_probe_ultra_fast_exit_closes_without_pid_fallback(
    tmp_path: Path,
) -> None:
    result = _run_real_java_probe_native_handle_harness(
        tmp_path,
        scenario="ULTRA_FAST_EXIT",
    )

    assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
    assert result["cleanup_attempts"] == [1]
    assert result["warnings"] == []
    assert result["pause_calls"] == []
    assert result["kill_calls"] == []
    assert result["wait_calls"] == []
    assert result["release_calls"] == []
    assert result["dispose_calls"] == [result["pid"]]
    assert result["completion_events"] == ["dispose"]
    assert result["elapsed_ms"] < 5000
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    runner = dict(_function_definitions(source))["Invoke-SourceJavaExecutableProbe"]
    assert "Get-CimInstance" not in runner
    assert "Win32_Process" not in runner
    assert "GetProcessById" not in runner


def test_real_java_probe_pre_bind_authority_failure_cleans_with_exact_identity(
    tmp_path: Path,
) -> None:
    result = _run_real_java_probe_pre_bind_authority_failure_harness(tmp_path)

    assert result["exact_process_reference"] is True
    assert result["safe_handle_type"] == (
        "Microsoft.Win32.SafeHandles.SafeProcessHandle"
    )
    assert result["safe_handle_valid"] is True
    assert result["authority_returned"] is False
    assert result["running_state"] == {
        "Pid": result["pid"],
        "CreationDate": result["exited_state"]["CreationDate"],
        "ExitDate": None,
        "IsExited": False,
    }
    assert result["exited_state"]["Pid"] == result["pid"]
    assert result["exited_state"]["ExitDate"]
    assert result["exited_state"]["IsExited"] is True
    assert result["child_has_exited"] is True
    assert result["supervisor_release_count"] == 1
    assert result["production_release_calls"] == []
    assert result["dispose_calls"] == [result["pid"]]
    assert result["harness_forced_cleanup"] is False

    assert result["harness_returncode"] == 0, result
    assert result["watchdog_triggered"] is False
    assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
    assert result["cleanup_attempts"] == [
        {
            "expected_process_id": result["pid"],
            "bound_identity_is_null": False,
            "bound_pid": result["pid"],
            "result": True,
        }
    ]
    assert result["warnings"] == []
    assert result["pause_calls"] == []
    assert result["kill_calls"] == [result["pid"]]
    assert result["wait_calls"] == [result["pid"]]


def test_v3_native_default_start_compiles_and_closes_exact_authority(
    tmp_path: Path,
) -> None:
    result = _run_v4_native_start_authority_harness(
        tmp_path,
        scenario="NORMAL",
    )

    assert result["error"] is None
    assert result["schema_version"] == "local-source-java-probe.v1"
    assert result["exit_code"] == 7
    assert result["stdout"] == "native-out"
    assert result["stderr"] == "native-err"
    assert result["stdout_length"] == len("native-out")
    assert result["stderr_length"] == len("native-err")
    assert result["stdout_sha256"] == hashlib.sha256(b"native-out").hexdigest()
    assert result["stderr_sha256"] == hashlib.sha256(b"native-err").hexdigest()

    source = LAUNCHER.read_text(encoding="utf-8-sig")
    initializer_start = source.index(
        "function Initialize-SourceJavaProbeNativeStartAuthority {"
    )
    native_runner_start = source.index(
        "\nfunction Invoke-SourceJavaNativeExecutableProbe {",
        initializer_start,
    )
    wrapper_start = source.index(
        "\nfunction Invoke-SourceJavaExecutableProbe {",
        native_runner_start,
    )
    next_start = source.index(
        "\nfunction Get-SourceJavaHomeFromProbeResult {",
        wrapper_start,
    )
    native_initializer = source[initializer_start:native_runner_start]
    native_runner = source[native_runner_start:wrapper_start]
    wrapper = source[wrapper_start:next_start]
    default_route = "\n".join((native_initializer, native_runner))
    assert "CreateProcessW" in native_initializer
    assert "local-source-java-probe-native-start-authority.v2" in native_initializer
    assert "RawHandleLease" in native_initializer
    assert "ManagedLease" in native_initializer
    assert "SourceJavaProbeCloseDirective" in native_initializer
    assert "FAILED_RETAINED" in native_initializer
    assert "SOURCE_JAVA_EXECUTABLE_PROBE_CLOSE_TRUTH_BROKEN" in native_initializer
    assert "CloseRawLease" in native_initializer
    assert "CloseManagedLease" in native_initializer
    assert "STARTUPINFOEX" in native_initializer
    assert "PROC_THREAD_ATTRIBUTE_HANDLE_LIST" not in native_initializer
    assert "ProcThreadAttributeHandleList" in native_initializer
    assert "GetProcessId" in native_initializer
    assert "GetProcessTimes" in native_initializer
    assert "WaitForSingleObject" in native_initializer
    assert "TerminateProcess" in native_initializer
    assert "System.Diagnostics.Process" not in default_route
    assert re.search(r"\bProcess\s*\.\s*SafeHandle\b", default_route) is None
    assert re.search(r"\$process\s*\.\s*SafeHandle\b", default_route) is None
    assert "GetProcessById" not in default_route
    assert "Get-CimInstance" not in default_route
    assert "OpenProcess" not in default_route
    assert "Invoke-SourceJavaNativeExecutableProbe" in wrapper


def test_v3_native_pre_ready_failure_closes_transaction_exactly(
    tmp_path: Path,
) -> None:
    result = _run_v4_native_start_authority_harness(
        tmp_path,
        scenario="PRE_READY_FAILURE",
    )

    assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
    assert result["elapsed_ms"] < 10000
    assert result["instrumentation_counts"] == {}
    assert result["audit"] == []


def test_v3_native_large_stdout_stderr_drain_without_deadlock(
    tmp_path: Path,
) -> None:
    result = _run_v4_native_start_authority_harness(
        tmp_path,
        scenario="LARGE",
    )

    length = 524288
    assert result["error"] is None
    assert result["exit_code"] == 0
    assert result["stdout_length"] == length
    assert result["stderr_length"] == length
    assert result["stdout_sha256"] == hashlib.sha256(b"O" * length).hexdigest()
    assert result["stderr_sha256"] == hashlib.sha256(b"E" * length).hexdigest()
    assert result["elapsed_ms"] < 10000


def test_v3_native_repeated_start_uses_fresh_exact_authority(
    tmp_path: Path,
) -> None:
    result = _run_v4_native_start_authority_harness(
        tmp_path,
        scenario="REPEAT",
    )

    assert result["error"] is None
    first = result["first"]
    second = result["second"]
    for attempt, record in enumerate((first, second), start=1):
        assert record["attempt"] == attempt
        assert record["ready_phase"] == "READY"
        assert record["pid"] > 0
        assert record["creation_date"]
        assert record["terminal_pid"] == record["pid"]
        assert record["terminal_creation_date"] == record["creation_date"]
        assert record["exit_date"]
        assert record["is_exited"] is True
        assert record["exit_code"] == 7
        assert record["stdout"] == "native-out"
        assert record["stderr"] == "native-err"
        assert record["final_phase"] == "CLOSED"
    assert (first["pid"], first["creation_date"]) != (
        second["pid"],
        second["creation_date"],
    )


def test_v3_incomplete_injection_fails_before_factory_or_start(
    tmp_path: Path,
) -> None:
    result = _run_v4_native_start_authority_harness(
        tmp_path,
        scenario="INCOMPLETE_INJECTION",
    )

    assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
    assert result["factory_calls"] == 0
    assert result["start_calls"] == 0


@pytest.mark.parametrize(
    ("scenario", "resource", "phase"),
    (
        ("V4_CLOSE_RAW_STDOUT_WRITE", "stdout-write", "CHILD_CREATED"),
        ("V4_CLOSE_RAW_THREAD", "thread", "CHILD_CREATED"),
        ("V4_CLOSE_RAW_STDOUT_READ", "stdout-read", "CLOSING"),
        ("V4_CLOSE_RAW_PROCESS", "process", "CLOSING"),
    ),
)
def test_v4_native_raw_close_lease_retains_exact_handle_until_real_success(
    tmp_path: Path,
    scenario: str,
    resource: str,
    phase: str,
) -> None:
    result = _run_v4_native_start_authority_harness(tmp_path, scenario=scenario)

    assert result["error"] is None
    assert result["exit_code"] == 7
    assert result["stdout"] == "native-out"
    assert result["stderr"] == "native-err"
    assert result["final_phase"] == "CLOSED"
    assert result["close_count_after_noop"] == result["close_count_before_noop"]
    target = [entry for entry in result["audit"] if entry["resource"] == resource]
    assert [entry["moment"] for entry in target] == [
        "OBSERVER",
        "OBSERVER",
        "OBSERVER",
        "FINAL",
    ]
    first, second, real, final = target
    assert first["attempt"] == 1
    assert second["attempt"] == 2
    assert real["attempt"] == 3
    assert final["attempt"] == 3
    retained_value = first["raw_value"]
    assert retained_value not in {0, -1}
    for attempt, injected_failures in ((first, 1), (second, 2)):
        assert attempt["phase"] == phase
        assert attempt["raw_value"] == retained_value
        assert attempt["raw_state"] == "RETRY_PENDING"
        assert attempt["injected_failures"] == injected_failures
        assert attempt["real_close_attempted"] is False
        assert attempt["all_complete"] is False
    assert real["raw_value"] == retained_value
    assert real["raw_state"] == "RETRY_PENDING"
    assert real["real_close_attempted"] is True
    assert real["last_error"] == 0
    assert final["raw_value"] == 0
    assert final["raw_state"] == "CLOSED"
    assert final["real_close_attempted"] is True
    assert final["last_error"] == 0

    real_attempts = [
        entry
        for entry in target
        if entry["moment"] == "OBSERVER" and entry["real_close_attempted"]
    ]
    assert len(real_attempts) == 1
    observer_attempts: dict[str, list[int]] = {}
    for entry in result["audit"]:
        if entry["moment"] == "OBSERVER":
            observer_attempts.setdefault(entry["resource"], []).append(entry["attempt"])
    assert observer_attempts[resource] == [1, 2, 3]
    assert all(
        attempts == [1]
        for observed_resource, attempts in observer_attempts.items()
        if observed_resource != resource
    )
    if resource == "process":
        assert real["exit_proven"] is True
        assert real["pid"] > 0
        assert real["creation_file_time"] > 0
        assert real["exit_file_time"] >= real["creation_file_time"]
        assert real["startup_complete"] is True
        assert real["output_complete"] is True


@pytest.mark.parametrize(
    "scenario,resource",
    (
        ("V4_CLOSE_MANAGED_READER", "stdout-reader"),
        ("V4_CLOSE_MANAGED_STREAM", "stdout-stream"),
        ("V4_CLOSE_MANAGED_WRAPPER", "stdout-wrapper"),
    ),
)
def test_v4_native_managed_close_retains_same_reference_until_disposed(
    tmp_path: Path,
    scenario: str,
    resource: str,
) -> None:
    result = _run_v4_native_start_authority_harness(tmp_path, scenario=scenario)

    assert result["error"] is None
    assert result["final_phase"] == "CLOSED"
    assert result["close_count_after_noop"] == result["close_count_before_noop"]
    target = [entry for entry in result["audit"] if entry["resource"] == resource]
    assert [entry["moment"] for entry in target] == [
        "OBSERVER",
        "OBSERVER",
        "FINAL",
    ]
    retained, real, final = target
    assert retained["attempt"] == 1
    assert retained["phase"] == "CLOSING"
    assert retained["reference_present"] is True
    assert retained["same_reference"] is True
    assert retained["managed_state"] == "RETRY_PENDING"
    assert retained["managed_failures"] == 1
    assert retained["real_dispose_attempted"] is False
    assert retained["all_complete"] is False
    assert real["attempt"] == 2
    assert real["reference_present"] is True
    assert real["same_reference"] is True
    assert real["managed_state"] == "RETRY_PENDING"
    assert real["real_dispose_attempted"] is True
    assert final["attempt"] == 2
    assert final["reference_present"] is False
    assert final["managed_state"] == "DISPOSED"
    assert final["real_dispose_attempted"] is True
    assert len(
        [
            entry
            for entry in target
            if entry["moment"] == "OBSERVER"
            and entry["real_dispose_attempted"]
        ]
    ) == 1
    observer_attempts: dict[str, list[int]] = {}
    for entry in result["audit"]:
        if entry["moment"] == "OBSERVER":
            observer_attempts.setdefault(entry["resource"], []).append(entry["attempt"])
    assert observer_attempts[resource] == [1, 2]
    assert all(
        attempts == [1]
        for observed_resource, attempts in observer_attempts.items()
        if observed_resource != resource
    )


@pytest.mark.parametrize(
    ("scenario", "resource", "child_created"),
    (
        ("V4_CLOSE_PRE_CREATE_FAILURE", "stdout-write", False),
        ("V4_CLOSE_POST_CREATE_FAILURE", "thread", True),
    ),
)
def test_v4_native_failed_start_retries_retained_close_before_canonical_error(
    tmp_path: Path,
    scenario: str,
    resource: str,
    child_created: bool,
) -> None:
    result = _run_v4_native_start_authority_harness(tmp_path, scenario=scenario)

    assert result["error"] == "SOURCE_JAVA_EXECUTABLE_RESOLUTION_FAILED"
    assert result["elapsed_ms"] < 10000
    target = [entry for entry in result["audit"] if entry["resource"] == resource]
    assert [entry["moment"] for entry in target] == [
        "OBSERVER",
        "OBSERVER",
        "OBSERVER",
        "FINAL",
    ]
    retained_value = target[0]["raw_value"]
    assert retained_value not in {0, -1}
    assert [entry["raw_value"] for entry in target[:3]] == [
        retained_value,
        retained_value,
        retained_value,
    ]
    assert [entry["raw_state"] for entry in target[:2]] == [
        "RETRY_PENDING",
        "RETRY_PENDING",
    ]
    assert [entry["real_close_attempted"] for entry in target[:3]] == [
        False,
        False,
        True,
    ]
    assert target[-1]["raw_value"] == 0
    assert target[-1]["raw_state"] == "CLOSED"
    phase = [entry for entry in result["audit"] if entry["moment"] == "FINAL_PHASE"]
    assert len(phase) == 1
    assert phase[0]["phase"] == "CLOSED"
    assert phase[0]["all_complete"] is True
    assert phase[0]["exit_proven"] is child_created
    if child_created:
        assert phase[0]["pid"] > 0
        assert phase[0]["creation_file_time"] > 0
        assert phase[0]["exit_file_time"] >= phase[0]["creation_file_time"]


@pytest.mark.parametrize(
    ("scenario", "resource", "managed"),
    (
        ("V4_CLOSE_RAW_TRUTH_BROKEN", "stdout-read", False),
        ("V4_CLOSE_MANAGED_TRUTH_BROKEN", "stdout-reader", True),
    ),
)
def test_v4_native_real_close_failure_fail_stops_with_truth_broken_lease(
    tmp_path: Path,
    scenario: str,
    resource: str,
    managed: bool,
) -> None:
    result = _run_v4_native_start_authority_harness(tmp_path, scenario=scenario)

    assert result["host_returncode"] != 0
    assert result["result_file_exists"] is False
    target = [entry for entry in result["audit"] if entry["resource"] == resource]
    expected_moments = ["BROKEN"] if managed else ["OBSERVER", "BROKEN"]
    assert [entry["moment"] for entry in target] == expected_moments
    observed = target[0]
    broken = target[-1]
    assert broken["attempt"] == 1
    assert observed["phase"] == ("CLOSE_TRUTH_BROKEN" if managed else "CLOSING")
    assert broken["phase"] == "CLOSE_TRUTH_BROKEN"
    assert observed["exit_proven"] is True
    assert broken["exit_proven"] is True
    assert observed["pid"] > 0
    assert observed["creation_file_time"] > 0
    assert observed["exit_file_time"] >= observed["creation_file_time"]
    assert observed["adapter_calls"] == 1
    assert broken["adapter_calls"] == 1
    assert observed["all_complete"] is False
    assert broken["all_complete"] is False
    if managed:
        assert observed["reference_present"] is True
        assert observed["same_reference"] is True
        assert observed["managed_state"] == "CLOSE_TRUTH_BROKEN"
        assert observed["dispose_attempts"] == 1
        assert observed["real_dispose_attempted"] is True
        assert broken["reference_present"] is True
        assert broken["same_reference"] is True
        assert broken["managed_state"] == "CLOSE_TRUTH_BROKEN"
    else:
        retained_handle = observed["raw_value"]
        assert retained_handle not in {0, -1}
        assert observed["raw_state"] == "OPEN"
        assert observed["real_close_attempted"] is True
        assert broken["raw_value"] == retained_handle
        assert broken["raw_state"] == "CLOSE_TRUTH_BROKEN"
        assert broken["real_close_attempted"] is True
    fail_fast = [
        entry
        for entry in result["audit"]
        if entry["resource"] == "fail-fast"
    ]
    assert len(fail_fast) == 1
    assert fail_fast[0]["moment"] == (
        "SOURCE_JAVA_EXECUTABLE_PROBE_CLOSE_TRUTH_BROKEN"
    )
    assert fail_fast[0]["phase"] == "CLOSE_TRUTH_BROKEN"
    assert fail_fast[0]["adapter_calls"] == 1


def test_ownership_record_reader_preserves_iso_date_as_schema_string(
    tmp_path: Path,
) -> None:
    pwsh = shutil.which("pwsh")
    if pwsh is None:
        pytest.skip("PowerShell 7 is not available")

    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    record["creation_date"] = "2026-08-15T06:11:39.0879086+00:00"
    record_path = _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    harness_file = tmp_path / "read-ownership-record.ps1"
    result_file = tmp_path / "result.json"
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$LauncherFile,
    [Parameter(Mandatory = $true)][string]$RecordPath,
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$ResultFile
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$parseTokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $LauncherFile,
    [ref]$parseTokens,
    [ref]$parseErrors
)
if (@($parseErrors).Count -ne 0) {
    throw "launcher PowerShell AST is invalid"
}
$definitions = $ast.FindAll(
    { param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] },
    $true
)
$bundle = ($definitions | ForEach-Object { $_.Extent.Text }) -join "`n`n"
. ([scriptblock]::Create($bundle))
$record = Read-SourceProcessOwnershipRecord `
    -Path $RecordPath `
    -Name $Name `
    -ProjectRoot $ProjectRoot
$output = if ($null -eq $record) {
    [pscustomobject]@{
        record_is_null = $true
        creation_date_type = $null
        creation_date = $null
    }
} else {
    [pscustomobject]@{
        record_is_null = $false
        creation_date_type = $record.creation_date.GetType().FullName
        creation_date = [string]$record.creation_date
    }
}
[System.IO.File]::WriteAllText(
    $ResultFile,
    ($output | ConvertTo-Json -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )

    completed = subprocess.run(
        [
            pwsh,
            "-NoProfile",
            "-NonInteractive",
            "-File",
            str(harness_file),
            "-LauncherFile",
            str(LAUNCHER),
            "-RecordPath",
            str(record_path),
            "-Name",
            record["name"],
            "-ProjectRoot",
            str(project_root),
            "-ResultFile",
            str(result_file),
        ],
        cwd=tmp_path,
        check=False,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 0, completed.stderr or completed.stdout
    result = json.loads(result_file.read_text(encoding="utf-8-sig"))
    assert result == {
        "record_is_null": False,
        "creation_date_type": "System.String",
        "creation_date": record["creation_date"],
    }


def test_stale_reused_pid_is_rejected_without_termination(tmp_path: Path) -> None:
    bundle = _contract_bundle_or_outcome(decisive=True)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    reused = _snapshot_process(
        record,
        creation_date="2026-08-06T01:00:00.0000000Z",
        executable_path=str(project_root / "unrelated" / "python.exe"),
        command_line='"D:\\unrelated\\python.exe" -m unrelated_service',
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[reused]],
    )

    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_exact_owned_java_tree_is_terminated_once_and_record_removed(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    root = _snapshot_process(record)
    helper = _snapshot_process(
        record,
        pid=4101,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:01.0000000Z",
        command_line=record["command_line"] + " --owned-helper",
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root, helper], [root, helper], []],
    )

    assert _result_code(result) == "TERMINATED"
    assert result["snapshot_calls"] >= 3
    assert len(result["termination_calls"]) == 1
    plan = result["termination_calls"][0]
    normalized = {key.lower(): value for key, value in plan.items()}
    assert set(normalized) >= {"root", "processes"}
    root_identity = {key.lower(): value for key, value in normalized["root"].items()}
    assert int(root_identity["pid"]) == record["pid"]
    process_ids = {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in normalized["processes"]
    }
    assert process_ids == {record["pid"], helper["ProcessId"]}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_malformed_or_pid_only_record_is_rejected_without_termination(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    valid = _ownership_record(project_root)
    nondecimal = {**valid, "pid": "4100"}
    overflow = {**valid, "pid": 2_147_483_648}
    extra_field = {**valid, "unexpected": "must-fail-closed"}
    invalid_records = (
        b"",
        b"not-a-decimal-pid",
        b"4100",
        json.dumps(nondecimal, separators=(",", ":")).encode("utf-8"),
        json.dumps(overflow, separators=(",", ":")).encode("utf-8"),
        json.dumps(extra_field, separators=(",", ":")).encode("utf-8"),
    )
    for index, raw_record in enumerate(invalid_records):
        case_root = tmp_path / f"invalid-{index}"
        record_directory = case_root / "ownership"
        record_path = _write_record(record_directory, "java-api", raw_record)
        result = _run_sandboxed_powershell(
            case_root,
            bundle=bundle,
            name="java-api",
            project_root=project_root,
            record_directory=record_directory,
            snapshots=[[]],
        )

        assert _result_code(result) == "OWNERSHIP_RECORD_INVALID"
        assert result["snapshot_calls"] == 0
        assert result["termination_calls"] == []
        assert result["removal_calls"] == []
        assert result["record_exists"] is True
        assert record_path.read_bytes() == raw_record


def test_absent_or_exited_record_is_zero_termination_and_repeat_idempotent(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )

    exited = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[]],
    )
    assert _result_code(exited) == "PROCESS_ALREADY_EXITED"
    assert exited["snapshot_calls"] == 1
    assert exited["termination_calls"] == []
    assert len(exited["removal_calls"]) == 1
    assert exited["record_exists"] is False

    repeated = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[]],
    )
    assert _result_code(repeated) == "NO_OWNERSHIP_RECORD"
    assert repeated["snapshot_calls"] == 0
    assert repeated["termination_calls"] == []
    assert repeated["removal_calls"] == []
    assert repeated["record_exists"] is False


@pytest.mark.parametrize(
    ("location", "criterion"),
    (
        ("root", "pid"),
        ("root", "executable"),
        ("root", "command"),
        ("descendant", "pid"),
        ("descendant", "executable"),
        ("descendant", "command"),
    ),
)
def test_protected_root_or_descendant_is_never_terminated(
    tmp_path: Path,
    location: str,
    criterion: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    is_frontend_child_executable = location == "descendant" and criterion == "executable"
    process_kind = "FRONTEND" if is_frontend_child_executable else "JAVA"
    name = "frontend" if is_frontend_child_executable else "java-api"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root, name=name, process_kind=process_kind)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    helper_executable = (
        str(project_root / ".tools" / "node.exe")
        if process_kind == "FRONTEND"
        else record["executable_path"]
    )
    helper = _snapshot_process(
        record,
        pid=4101,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:01.0000000Z",
        executable_path=helper_executable,
        command_line=record["command_line"] + " --owned-helper",
    )
    protected = root if location == "root" else helper
    protected_pids = [protected["ProcessId"]] if criterion == "pid" else []
    protected_executables = (
        [protected["ExecutablePath"]] if criterion == "executable" else []
    )
    protected_fragments = (
        ["--owned-helper" if location == "descendant" else "DisputeApplication"]
        if criterion == "command"
        else []
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root, helper]],
        protected_pids=protected_pids,
        protected_executables=protected_executables,
        protected_command_fragments=protected_fragments,
    )

    assert _result_code(result) == "PROTECTED_PROCESS"
    assert result["snapshot_calls"] == 1
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


@pytest.mark.parametrize(
    "violation",
    ("wrong-root-parent", "unknown-child", "out-of-worktree-child", "older-child"),
)
def test_unowned_or_invalid_ancestry_is_never_terminated(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(
        record,
        parent_pid=9999 if violation == "wrong-root-parent" else record["parent_pid"],
    )
    helper = _snapshot_process(
        record,
        pid=4101,
        parent_pid=record["pid"],
        creation_date=(
            "2026-08-05T23:59:59.0000000Z"
            if violation == "older-child"
            else "2026-08-06T00:00:01.0000000Z"
        ),
        executable_path=(
            str(project_root / ".tools" / "mystery.exe")
            if violation == "unknown-child"
            else record["executable_path"]
        ),
        command_line=(
            '"D:\\outside-worktree\\java.exe" -jar unrelated.jar'
            if violation == "out-of-worktree-child"
            else record["command_line"] + " --owned-helper"
        ),
        working_directory=(
            "D:\\outside-worktree"
            if violation == "out-of-worktree-child"
            else record["working_directory"]
        ),
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root, helper]],
    )

    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


@pytest.mark.parametrize(
    ("name", "process_kind"),
    (
        ("java-api", "JAVA"),
        ("python-agent", "PYTHON"),
        ("frontend", "FRONTEND"),
    ),
)
def test_expected_java_python_and_frontend_ancestry_is_terminated(
    tmp_path: Path,
    name: str,
    process_kind: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name=name,
        process_kind=process_kind,
    )
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    root = _snapshot_process(record)
    helper_executable = (
        str(project_root / ".tools" / "node.exe")
        if process_kind == "FRONTEND"
        else record["executable_path"]
    )
    helper = _snapshot_process(
        record,
        pid=4101,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:01.0000000Z",
        executable_path=helper_executable,
        command_line=record["command_line"] + " --owned-helper",
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root, helper], [root, helper], []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_snapshot_drift_before_termination_is_never_terminated(tmp_path: Path) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    helper = _snapshot_process(
        record,
        pid=4101,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:01.0000000Z",
        command_line=record["command_line"] + " --owned-helper",
    )
    drifted_helper = {**helper, "CommandLine": helper["CommandLine"] + " --drift"}

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root, helper], [root, drifted_helper]],
    )

    assert _result_code(result) == "PROCESS_IDENTITY_DRIFT"
    assert result["snapshot_calls"] == 2
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


@pytest.mark.parametrize(
    ("terminator_mode", "postcondition", "expected_code"),
    (
        ("throw", "alive", "PROCESS_TERMINATION_FAILED"),
        ("success", "alive", "PROCESS_STILL_RUNNING"),
    ),
)
def test_termination_failure_or_alive_postcondition_preserves_record(
    tmp_path: Path,
    terminator_mode: str,
    postcondition: str,
    expected_code: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    helper = _snapshot_process(
        record,
        pid=4101,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:01.0000000Z",
        command_line=record["command_line"] + " --owned-helper",
    )
    snapshots = [[root, helper], [root, helper]]
    if postcondition == "alive":
        snapshots.append([root, helper])

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=snapshots,
        terminator_mode=terminator_mode,
    )

    assert _result_code(result) == expected_code
    assert result["snapshot_calls"] == 3
    assert len(result["termination_calls"]) == 1
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_terminator_exception_with_empty_postcondition_removes_record_and_replays(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    root = _snapshot_process(record)
    helper = _snapshot_process(
        record,
        pid=4101,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:01.0000000Z",
        command_line=record["command_line"] + " --owned-helper",
    )
    tree = [root, helper]

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
        terminator_mode="throw",
        invocation_count=2,
    )

    assert result["invocation_errors"] == [None, None]
    assert [
        str(next(value for key, value in item.items() if key.lower() == "code"))
        for item in result["function_results"]
    ] == ["TERMINATED", "NO_OWNERSHIP_RECORD"]
    assert result["snapshot_calls"] == 3
    assert len(result["termination_calls"]) == 1
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_terminator_exception_with_late_owned_descendant_preserves_record(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    late_child = _snapshot_process(
        record,
        pid=4200,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:02.0000000Z",
        command_line=record["command_line"] + " --late-owned-helper",
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root], [root], [late_child]],
        terminator_mode="throw",
    )

    assert _result_code(result) == "PROCESS_TERMINATION_FAILED"
    assert result["snapshot_calls"] == 3
    assert len(result["termination_calls"]) == 1
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_terminator_exception_with_unavailable_postcondition_preserves_record(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root], [root], []],
        terminator_mode="throw",
        snapshot_error_calls=[3],
    )

    assert _result_code(result) == "PROCESS_SNAPSHOT_UNAVAILABLE"
    assert result["snapshot_calls"] == 3
    assert len(result["termination_calls"]) == 1
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_postcondition_rejects_new_owned_descendant_after_termination(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    late_child = _snapshot_process(
        record,
        pid=4200,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:02.0000000Z",
        command_line=record["command_line"] + " --late-owned-helper",
    )
    validation_snapshot = [root]
    pretermination_snapshot = [root]
    postcondition_snapshot = [late_child]
    assert all(
        process["ProcessId"] != late_child["ProcessId"]
        for snapshot in (validation_snapshot, pretermination_snapshot)
        for process in snapshot
    )
    assert postcondition_snapshot[0]["ProcessId"] == late_child["ProcessId"]

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[
            validation_snapshot,
            pretermination_snapshot,
            postcondition_snapshot,
        ],
    )

    assert _result_code(result) == "PROCESS_STILL_RUNNING"
    assert len(result["termination_calls"]) == 1
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_postcondition_does_not_claim_same_service_sibling_root(tmp_path: Path) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    api_root = _snapshot_process(record)
    control_sibling = _snapshot_process(
        record,
        pid=4300,
        parent_pid=record["parent_pid"],
        creation_date="2026-08-06T00:00:02.0000000Z",
        command_line=record["command_line"].replace(
            "--app.temporal.worker.role=API",
            "--app.temporal.worker.role=CONTROL",
        ),
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[
            [api_root, control_sibling],
            [api_root, control_sibling],
            [control_sibling],
        ],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = result["termination_calls"][0]
    normalized = {key.lower(): value for key, value in plan.items()}
    planned_pids = {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in normalized["processes"]
    }
    assert planned_pids == {record["pid"]}
    assert control_sibling["ProcessId"] not in planned_pids
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_root_absent_retry_preserves_record_while_owned_descendant_survives(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root)
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    api_root = _snapshot_process(record)
    late_child = _snapshot_process(
        record,
        pid=4400,
        parent_pid=record["pid"],
        creation_date="2026-08-06T00:00:02.0000000Z",
        command_line=record["command_line"] + " --late-owned-helper",
    )
    first_validation = [api_root]
    first_pretermination = [api_root]
    first_postcondition = [late_child]
    retry_validation = [late_child]
    assert late_child["ProcessId"] not in {
        process["ProcessId"]
        for snapshot in (first_validation, first_pretermination)
        for process in snapshot
    }
    assert first_postcondition == retry_validation

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[
            first_validation,
            first_pretermination,
            first_postcondition,
            retry_validation,
        ],
        invocation_count=2,
    )

    assert result["invocation_errors"] == [None, None]
    assert [
        str(next(value for key, value in item.items() if key.lower() == "code"))
        for item in result["function_results"]
    ] == ["PROCESS_STILL_RUNNING", "PROCESS_STILL_RUNNING"]
    assert len(result["termination_calls"]) == 1
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_publication_writer_failure_compensates_captured_process_once(
    tmp_path: Path,
) -> None:
    bundle = _publication_contract_bundle_or_fail()
    result = _run_publication_failure_harness(tmp_path, bundle=bundle)

    assert result["publication_result"] is None
    assert result["invocation_error"] == {
        "type": "System.InvalidOperationException",
        "message": "SOURCE_PROCESS_OWNERSHIP_PUBLICATION_FAILED",
    }
    assert len(result["writer_calls"]) == 1
    assert result["writer_calls"][0]["same_reference"] is True
    assert len(result["terminator_calls"]) == 1
    assert result["same_captured_reference"] is True
    assert result["terminator_calls"][0] == {
        "pid": 5100,
        "creation_date": "2026-08-06T00:00:00.0000000Z",
        "executable_path": str(
            tmp_path / "candidate" / ".tools" / "jdk" / "bin" / "java.exe"
        ),
        "command_line": _java_authoritative_command(
            tmp_path / "candidate" / ".tools" / "jdk" / "bin" / "java.exe",
            tmp_path / "candidate" / "java-api-service",
            "java-api",
        ),
    }
    assert result["cleanup_registry_count"] == 0
    assert result["record_files"] == []
    assert "RAW_WRITER_DETAIL_MUST_NOT_ESCAPE" not in json.dumps(result)


@pytest.mark.parametrize("terminator_mode", ("throw", "false", "alive"))
def test_publication_compensation_failure_registers_captured_process(
    tmp_path: Path,
    terminator_mode: str,
) -> None:
    bundle = _publication_contract_bundle_or_fail()
    result = _run_publication_failure_harness(
        tmp_path,
        bundle=bundle,
        terminator_mode=terminator_mode,
    )

    assert result["publication_result"] is None
    assert result["invocation_error"] == {
        "type": "System.InvalidOperationException",
        "message": "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_FAILED",
    }
    assert len(result["writer_calls"]) == 1
    assert result["writer_calls"][0]["same_reference"] is True
    assert len(result["terminator_calls"]) == 1
    assert result["same_captured_reference"] is True
    assert result["cleanup_registry_count"] == 1
    assert result["cleanup_registry_same_reference"] is True
    assert result["record_files"] == []
    serialized = json.dumps(result)
    assert "RAW_WRITER_DETAIL_MUST_NOT_ESCAPE" not in serialized
    assert "RAW_TERMINATOR_DETAIL_MUST_NOT_ESCAPE" not in serialized


def test_publication_writer_success_does_not_compensate(tmp_path: Path) -> None:
    bundle = _publication_contract_bundle_or_fail()
    result = _run_publication_failure_harness(
        tmp_path,
        bundle=bundle,
        writer_mode="success",
    )

    assert result["invocation_error"] is None
    publication_result = {
        key.lower(): value for key, value in result["publication_result"].items()
    }
    assert publication_result["code"] == "PUBLISHED"
    assert len(result["writer_calls"]) == 1
    assert result["writer_calls"][0]["same_reference"] is True
    assert result["terminator_calls"] == []
    assert result["cleanup_registry_count"] == 0


@pytest.mark.parametrize(
    "scenario",
    (
        "publish-success",
        "publish-CAPTURE_HANDLE_IDENTITY",
        "publish-ATTACH_CAPTURED_IDENTITY",
        "publish-ATOMIC_PUBLISH",
        "comp-success",
        "comp-FIRST_SNAPSHOT",
        "comp-FIRST_ROOT_STATE",
        "comp-FIRST_PLAN",
        "comp-PRE_SNAPSHOT",
        "comp-PRE_ROOT_STATE",
        "comp-PRE_PLAN",
        "comp-BIND_AND_KILL",
        "comp-POST_ROOT_STATE",
        "comp-POST_SNAPSHOT",
        "comp-POST_IDENTITY",
        "comp-POST_RESIDUAL",
        "comp-MARK_CONFIRMED",
    ),
)
def test_closed_source_ownership_stage_diagnostics_are_exact_and_non_sensitive(
    tmp_path: Path,
    scenario: str,
) -> None:
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    compensation_stages = (
        "FIRST_SNAPSHOT",
        "FIRST_ROOT_STATE",
        "FIRST_PLAN",
        "PRE_SNAPSHOT",
        "PRE_ROOT_STATE",
        "PRE_PLAN",
        "BIND_AND_KILL",
        "POST_ROOT_STATE",
        "POST_SNAPSHOT",
        "POST_IDENTITY",
        "POST_RESIDUAL",
        "MARK_CONFIRMED",
    )
    compensation_helper = "Write-SourceProcessOwnershipCompensationStageFailure"
    assert compensation_helper in definitions
    helper_source = definitions[compensation_helper]
    assert set(re.findall(r'"([A-Z_]+)"', helper_source)) == set(
        compensation_stages
    )
    assert (
        "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_STAGE_FAILED:$Stage"
        in helper_source
    )

    unpublished_source = definitions[
        "Invoke-SourceProcessOwnershipUnpublishedTermination"
    ]
    assert "return $false" not in unpublished_source
    for stage in compensation_stages:
        assert f'$failureStage = "{stage}"' in unpublished_source
    assert compensation_helper in unpublished_source

    publication_source = definitions[PUBLICATION_ENTRYPOINT]
    catch_offset = publication_source.index("catch {")
    initial_stage_offset = publication_source.index(
        "Write-SourceProcessOwnershipSafeStageFailure -Stage $failureStage",
        catch_offset,
    )
    compensation_offset = publication_source.index(
        "$terminationSucceeded", catch_offset
    )
    assert catch_offset < initial_stage_offset < compensation_offset

    bundle = _publication_contract_bundle_or_fail()
    result = _run_closed_stage_diagnostics_harness(
        tmp_path,
        bundle=bundle,
        scenario=scenario,
    )
    serialized = json.dumps(result)
    assert "RAW_SENSITIVE" not in serialized
    assert "8811" not in serialized
    assert r"C:\sensitive" not in serialized
    assert all(
        warning.startswith(
            (
                "SOURCE_PROCESS_OWNERSHIP_STAGE_FAILED:",
                "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_STAGE_FAILED:",
            )
        )
        for warning in result["warnings"]
    )

    if scenario == "publish-success":
        assert result["invocation_error"] is None
        assert result["warnings"] == []
        publication_result = {
            key.lower(): value for key, value in result["result"].items()
        }
        assert publication_result["code"] == "PUBLISHED"
    elif scenario.startswith("publish-"):
        expected_stage = scenario.removeprefix("publish-")
        assert result["result"] is None
        assert (
            result["invocation_error"]
            == "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_FAILED"
        )
        assert result["warnings"] == [
            f"SOURCE_PROCESS_OWNERSHIP_STAGE_FAILED:{expected_stage}",
            "SOURCE_PROCESS_OWNERSHIP_STAGE_FAILED:COMPENSATION_TERMINATE",
        ]
    elif scenario == "comp-success":
        assert result["invocation_error"] is None
        assert result["result"] is True
        assert result["warnings"] == []
    else:
        expected_stage = scenario.removeprefix("comp-")
        assert expected_stage in compensation_stages
        assert result["invocation_error"] is None
        assert result["result"] is False
        assert result["warnings"] == [
            "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_STAGE_FAILED:"
            + expected_stage
        ]


def test_safe_ownership_writer_substage_diagnostics_are_closed_and_precedence_safe(
    tmp_path: Path,
) -> None:
    writer_stages = (
        "WRITER_PRECHECK",
        "INITIAL_HANDLE_STATE",
        "PUBLICATION_RECORD",
        "ROOT_IDENTITY",
        "TEMP_WRITE",
        "PRE_MOVE_HANDLE_STATE",
        "MOVE_NO_OVERWRITE",
        "TEMP_CLEANUP",
    )
    source = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(source))
    for helper in (
        "Set-SourceProcessOwnershipWriterFailureStage",
        "Get-SourceProcessOwnershipWriterFailureStage",
        "Write-SourceProcessOwnershipWriterStageFailure",
    ):
        assert helper in definitions
    warning_helper = definitions[
        "Write-SourceProcessOwnershipWriterStageFailure"
    ]
    assert set(re.findall(r'"([A-Z_]+)"', warning_helper)) == set(
        writer_stages
    )
    assert "SOURCE_PROCESS_OWNERSHIP_WRITER_STAGE_FAILED:$Stage" in warning_helper

    stage_reader = definitions["Get-SourceProcessOwnershipWriterFailureStage"]
    assert "local-source-writer-stage.v1" in stage_reader
    assert "-cnotcontains" in stage_reader
    assert "SourceProcessOwnershipWriterFailureStage" in stage_reader
    assert "SourceProcessOwnershipWriterFailureCarrier" in stage_reader

    writer = definitions["Write-SourceProcessOwnershipRecord"]
    for stage in writer_stages[:4]:
        assert f'$failureStage = "{stage}"' in writer
    file_writer = definitions["Write-SourceProcessOwnershipRecordFile"]
    for stage in (
        "WRITER_PRECHECK",
        "TEMP_WRITE",
        "PRE_MOVE_HANDLE_STATE",
        "MOVE_NO_OVERWRITE",
        "TEMP_CLEANUP",
    ):
        assert stage in file_writer
    assert "$primaryFailure" in file_writer
    assert "$null -eq $primaryFailure" in file_writer
    assert "Start-Sleep" not in file_writer
    assert "Retry" not in file_writer

    publication = definitions[PUBLICATION_ENTRYPOINT]
    outer_warning = publication.index(
        "Write-SourceProcessOwnershipSafeStageFailure -Stage $failureStage"
    )
    stage_read = publication.index(
        "Get-SourceProcessOwnershipWriterFailureStage"
    )
    writer_warning = publication.index(
        "Write-SourceProcessOwnershipWriterStageFailure"
    )
    compensation = publication.index("$terminationSucceeded")
    assert outer_warning < stage_read < writer_warning < compensation
    assert publication.count(
        "Write-SourceProcessOwnershipWriterStageFailure"
    ) == 1

    bundle = _ownership_function_bundle()
    assert bundle is not None
    expected = {
        "success": (None, False, True),
        "precheck": ("WRITER_PRECHECK", True, False),
        "pre-move": ("PRE_MOVE_HANDLE_STATE", True, False),
        "move": ("MOVE_NO_OVERWRITE", True, False),
        "cleanup-precedence": ("PRE_MOVE_HANDLE_STATE", True, False),
        "closed-vocabulary": (None, False, False),
    }
    for scenario, (stage, failed, completed) in expected.items():
        result = _run_writer_substage_harness(
            tmp_path,
            bundle=bundle,
            scenario=scenario,
        )
        assert result["stage"] == stage
        assert result["failed"] is failed
        assert result["completed"] is completed
        assert result["temporary_count"] == 0
        assert "RAW_SENSITIVE" not in json.dumps(result)
        if stage is None:
            assert result["warnings"] == []
        else:
            assert result["warnings"] == [
                f"SOURCE_PROCESS_OWNERSHIP_WRITER_STAGE_FAILED:{stage}"
            ]
        if scenario in {"precheck", "move"}:
            assert result["final_content"] == result["sentinel"]
        elif scenario == "success":
            assert json.loads(result["final_content"]) == {
                "schema_version": "test.v1",
                "marker": "safe",
            }
        else:
            assert result["final_exists"] is False


def test_cleanup_registry_retry_clears_only_confirmed_termination(
    tmp_path: Path,
) -> None:
    bundle = _publication_contract_bundle_or_fail()
    result = _run_cleanup_registry_retry_harness(tmp_path, bundle=bundle)

    assert result["throw_result"] is False
    assert result["after_throw_count"] == 1
    assert result["after_throw_same_reference"] is True
    assert result["alive_result"] is False
    assert result["after_alive_count"] == 1
    assert result["after_alive_same_reference"] is True
    assert result["success_result"] is True
    assert result["final_count"] == 0
    assert result["same_reference_checks"] == [True, True, True]


def test_pre_move_handle_state_predicates_are_closed_and_publication_atomic(
    tmp_path: Path,
) -> None:
    bundle = _ownership_function_bundle()
    assert bundle is not None
    scenarios = {
        "live": None,
        "provider-error": "HANDLE_STATE_ERROR",
        "pid-binding": "PID_BINDING",
        "creation-binding": "CREATION_BINDING",
        "state-shape": "STATE_SHAPE",
        "process-exited": "PROCESS_EXITED",
    }
    for scenario, expected_code in scenarios.items():
        result = _run_pre_move_predicate_harness(
            tmp_path,
            bundle=bundle,
            scenario=scenario,
        )
        assert result["predicate_calls"] == 1
        assert result["predicate_process_reference"] is True
        assert result["predicate_identity_reference"] is True
        assert result["state_calls"] == 2
        assert result["retained_root_reference"] is True
        assert "RAW_SENSITIVE" not in json.dumps(result)
        if expected_code is None:
            assert result["warnings"] == []
            assert result["writer_stage"] is None
            assert result["completed"] is True
            assert result["failed"] is False
            assert result["final_exists"] is True
        else:
            assert result["warnings"] == [
                "SOURCE_PROCESS_OWNERSHIP_PRE_MOVE_PREDICATE_FAILED:"
                + expected_code
            ]
            assert result["writer_stage"] == "PRE_MOVE_HANDLE_STATE"
            assert result["completed"] is False
            assert result["failed"] is True
            assert result["final_exists"] is False
        assert result["temporary_count"] == 0


def test_all_source_starts_publish_ownership_through_compensation_seam() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    by_name = {name: definition for name, definition in definitions}
    assert PUBLICATION_ENTRYPOINT in by_name, (
        f"{PUBLICATION_ENTRYPOINT} must own publication failure compensation"
    )
    assert "Start-JavaSourceProcess" in by_name
    java_start = by_name["Start-JavaSourceProcess"]
    assert _command_call_count(java_start, PUBLICATION_ENTRYPOINT) == 1
    assert java_start.index("Start-Process") < java_start.index(PUBLICATION_ENTRYPOINT)

    frontend_start = launcher.index("$frontend = Start-Process")
    python_start = launcher.index("$pythonAgent = Start-Process")
    frontend_publication = launcher.find(
        PUBLICATION_ENTRYPOINT,
        frontend_start,
        python_start,
    )
    python_publication = launcher.find(PUBLICATION_ENTRYPOINT, python_start)
    assert frontend_publication > frontend_start
    assert python_publication > python_start
    assert _command_call_count(launcher, PUBLICATION_ENTRYPOINT) == 3

    python_tombstone = launcher.index(LAUNCH_TOMBSTONE_ENTRYPOINT, python_start)
    python_start_slice = launcher[python_start:python_tombstone]
    expected_python_redirections = (
        '-RedirectStandardOutput (Join-Path $stateDir "python-agent.out.log")',
        '-RedirectStandardError (Join-Path $stateDir "python-agent.err.log")',
    )
    for redirection in expected_python_redirections:
        assert python_start_slice.count(redirection) == 1
    assert python_start_slice.count("-RedirectStandardOutput") == 1
    assert python_start_slice.count("-RedirectStandardError") == 1

    outside_publication = launcher.replace(by_name[PUBLICATION_ENTRYPOINT], "", 1)
    assert _command_call_count(
        outside_publication,
        "Write-SourceProcessOwnershipRecord",
    ) == 0, (
        "source Start-Process call sites must not publish ownership directly; "
        f"all publication must route through {PUBLICATION_ENTRYPOINT}"
    )


def test_fast_exited_root_without_descendants_clears_cleanup_registry(
    tmp_path: Path,
) -> None:
    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_fast_exit_tombstone_harness(tmp_path, bundle=bundle)

    tombstone = result["tombstone"]
    assert {key.lower() for key in tombstone} == LAUNCH_TOMBSTONE_FIELDS
    normalized = {key.lower(): value for key, value in tombstone.items()}
    assert normalized["schema_version"] == LAUNCH_TOMBSTONE_SCHEMA
    assert normalized["name"] == "java-api"
    assert normalized["process_kind"] == "JAVA"
    assert normalized["pid"] == 5300
    assert normalized["launcher_pid"] == 900
    assert result["attached"] is True
    assert result["attached_same_reference"] is True
    assert result["retry_result"] is True
    assert result["registry_count"] == 0
    assert result["snapshot_calls"] >= 3
    assert result["liveness_reads"] == 0


def test_fast_exited_root_tombstone_compensates_bound_descendant_tree(
    tmp_path: Path,
) -> None:
    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_fast_exit_tombstone_harness(
        tmp_path,
        bundle=bundle,
        with_descendants=True,
        bind_root_instance_for_descendants=True,
    )

    assert result["attached"] is True
    assert result["attached_same_reference"] is True
    assert result["root_instance_provider_calls"] == 1
    assert result["root_instance_provider_same_reference"] is True
    assert result["root_authority_attached"] is True
    assert result["root_authority_handle_reference_matches"] is True
    assert result["root_authority_creation_date"] == "2026-08-06T00:00:00.0000000+00:00"
    assert result["root_authority_exit_date"] == "2026-08-06T00:00:03.0000000+00:00"
    assert result["plan_termination_calls"] == 1
    assert result["plan_same_captured_reference"] is True
    assert result["plan_root_pid"] == 5300
    assert set(result["plan_process_pids"]) == {5401, 5402}
    assert result["snapshot_calls"] == 3
    assert result["retry_result"] is True
    assert result["registry_count"] == 0
    assert result["liveness_reads"] == 0


def test_all_source_starts_attach_launch_tombstone_before_identity_reads() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    by_name = {name: definition for name, definition in definitions}
    assert LAUNCH_TOMBSTONE_ENTRYPOINT in by_name, (
        f"{LAUNCH_TOMBSTONE_ENTRYPOINT} must preserve fast-exit provenance"
    )
    initializer = by_name[LAUNCH_TOMBSTONE_ENTRYPOINT]
    forbidden_initializer_reads = (
        ".StartTime",
        ".MainModule",
        "Get-CimInstance",
        "SourceProcessSnapshotProvider",
        "Get-SourceProcessOwnershipCapturedIdentity",
        "Write-SourceProcessOwnershipRecord",
    )
    assert not any(token in initializer for token in forbidden_initializer_reads)
    assert _command_call_count(launcher, LAUNCH_TOMBSTONE_ENTRYPOINT) == 3

    assert "Start-JavaSourceProcess" in by_name
    java_start = by_name["Start-JavaSourceProcess"]
    java_process = java_start.index("Start-Process")
    java_tombstone = java_start.index(LAUNCH_TOMBSTONE_ENTRYPOINT)
    java_publication = java_start.index(PUBLICATION_ENTRYPOINT)
    assert java_process < java_tombstone < java_publication

    frontend_process = launcher.index("$frontend = Start-Process")
    frontend_tombstone = launcher.index(
        LAUNCH_TOMBSTONE_ENTRYPOINT,
        frontend_process,
    )
    frontend_publication = launcher.index(PUBLICATION_ENTRYPOINT, frontend_process)
    assert frontend_process < frontend_tombstone < frontend_publication

    python_process = launcher.index("$pythonAgent = Start-Process")
    python_tombstone = launcher.index(LAUNCH_TOMBSTONE_ENTRYPOINT, python_process)
    python_publication = launcher.index(PUBLICATION_ENTRYPOINT, python_process)
    assert python_process < python_tombstone < python_publication

    forbidden_pre_tombstone_reads = forbidden_initializer_reads + (
        PUBLICATION_ENTRYPOINT,
    )
    for pre_tombstone in (
        java_start[java_process:java_tombstone],
        launcher[frontend_process:frontend_tombstone],
        launcher[python_process:python_tombstone],
    ):
        assert not any(token in pre_tombstone for token in forbidden_pre_tombstone_reads)


def test_reused_root_descendants_after_exit_are_not_authorized(
    tmp_path: Path,
) -> None:
    bundle = _root_instance_contract_bundle_or_fail()
    result = _run_fast_exit_tombstone_harness(
        tmp_path,
        bundle=bundle,
        with_descendants=True,
        descendants_after_exit=True,
    )

    assert result["root_instance_provider_calls"] == 1
    assert result["root_instance_provider_same_reference"] is True
    assert result["root_authority_attached"] is True
    assert result["root_authority_same_reference"] is True
    assert result["root_authority_schema"] == ROOT_INSTANCE_SCHEMA
    assert result["root_authority_pid"] == 5300
    assert result["root_authority_creation_date"] == "2026-08-06T00:00:00.0000000+00:00"
    assert result["root_authority_exit_date"] == "2026-08-06T00:00:03.0000000+00:00"
    assert result["root_authority_handle_reference_matches"] is True
    assert result["plan_termination_calls"] == 0
    assert result["retry_result"] is False
    assert result["registry_count"] == 1
    assert result["compensation_confirmed"] is False
    assert result["liveness_reads"] == 0


def test_root_absent_descendants_without_instance_authority_fail_closed(
    tmp_path: Path,
) -> None:
    bundle = _launch_tombstone_contract_bundle_or_fail()
    result = _run_fast_exit_tombstone_harness(
        tmp_path,
        bundle=bundle,
        with_descendants=True,
    )

    assert result["root_authority_attached"] is False
    assert result["plan_termination_calls"] == 0
    assert result["retry_result"] is False
    assert result["registry_count"] == 1
    assert result["compensation_confirmed"] is False
    assert result["liveness_reads"] == 0


def test_all_source_starts_bind_root_instance_before_publication() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    by_name = {name: definition for name, definition in definitions}
    assert ROOT_INSTANCE_ENTRYPOINT in by_name, (
        f"{ROOT_INSTANCE_ENTRYPOINT} must bind the captured root handle instance"
    )
    assert _command_call_count(launcher, ROOT_INSTANCE_ENTRYPOINT) == 3

    assert "Start-JavaSourceProcess" in by_name
    java_start = by_name["Start-JavaSourceProcess"]
    java_process = java_start.index("Start-Process")
    java_tombstone = java_start.index(LAUNCH_TOMBSTONE_ENTRYPOINT)
    java_instance = java_start.index(ROOT_INSTANCE_ENTRYPOINT)
    java_publication = java_start.index(PUBLICATION_ENTRYPOINT)
    assert java_process < java_tombstone < java_instance < java_publication

    frontend_process = launcher.index("$frontend = Start-Process")
    frontend_tombstone = launcher.index(
        LAUNCH_TOMBSTONE_ENTRYPOINT,
        frontend_process,
    )
    frontend_instance = launcher.index(ROOT_INSTANCE_ENTRYPOINT, frontend_process)
    frontend_publication = launcher.index(PUBLICATION_ENTRYPOINT, frontend_process)
    assert (
        frontend_process
        < frontend_tombstone
        < frontend_instance
        < frontend_publication
    )

    python_process = launcher.index("$pythonAgent = Start-Process")
    python_tombstone = launcher.index(LAUNCH_TOMBSTONE_ENTRYPOINT, python_process)
    python_instance = launcher.index(ROOT_INSTANCE_ENTRYPOINT, python_process)
    python_publication = launcher.index(PUBLICATION_ENTRYPOINT, python_process)
    assert python_process < python_tombstone < python_instance < python_publication


def test_cleanup_registry_drain_retries_until_exact_reference_is_removed(
    tmp_path: Path,
) -> None:
    bundle = _cleanup_drain_contract_bundle_or_fail()
    result = _run_cleanup_drain_harness(tmp_path, bundle=bundle)

    assert result["invocation_error"] is None
    drain_result = {key.lower(): value for key, value in result["drain_result"].items()}
    assert drain_result == {"code": "CLEANUP_REGISTRY_DRAINED"}
    assert result["retry_calls"] == 3
    assert result["reference_checks"] == [True, True, True]
    assert result["pause_attempts"] == [1, 2]
    assert result["registry_count"] == 0
    assert "RAW_RETRY_DETAIL_MUST_NOT_ESCAPE" not in json.dumps(result)


def test_outer_catch_blocks_until_cleanup_registry_is_empty() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = _function_definitions(launcher)
    by_name = {name: definition for name, definition in definitions}
    assert CLEANUP_DRAIN_ENTRYPOINT in by_name, (
        f"outer failure handling must call {CLEANUP_DRAIN_ENTRYPOINT}"
    )
    outer_catch_start = launcher.rfind("catch {")
    assert outer_catch_start >= 0
    outer_catch = launcher[outer_catch_start:]
    assert _command_call_count(outer_catch, CLEANUP_DRAIN_ENTRYPOINT) == 1
    assert _command_call_count(
        outer_catch,
        "Invoke-SourceProcessOwnershipCleanupRegistryRetry",
    ) == 0
    drain_index = outer_catch.index(CLEANUP_DRAIN_ENTRYPOINT)
    stop_index = outer_catch.index("Stop-TrackedProcess")
    rethrow_index = outer_catch.rindex("throw")
    assert drain_index < stop_index < rethrow_index
    assert "SOURCE_PROCESS_OWNERSHIP_COMPENSATION_PENDING" not in outer_catch


def test_frontend_real_shape_conhost_and_esbuild_tree_is_owned(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="frontend",
        pid=6100,
        process_kind="FRONTEND",
    )
    system32 = Path("C:/Windows/System32")
    root_cmd = system32 / "cmd.exe"
    record["executable_path"] = str(root_cmd)
    record["command_line"] = (
        f'"{root_cmd}" /d /c pnpm --dir "{record["working_directory"]}" dev'
    )
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    root = _snapshot_process(record)
    conhost = _snapshot_process(
        record,
        pid=6101,
        parent_pid=6100,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(system32 / "conhost.exe"),
        command_line=r"\??\C:\Windows\System32\conhost.exe 0x4",
    )
    conhost["WorkingDirectory"] = None
    node_executable = project_root / ".tools" / "node.exe"
    project_node = _snapshot_process(
        record,
        pid=6102,
        parent_pid=6100,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(node_executable),
        command_line=(
            f'"{node_executable}" '
            f'"{project_root / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"}"'
        ),
    )
    child_cmd = _snapshot_process(
        record,
        pid=6103,
        parent_pid=6102,
        creation_date="2026-08-06T00:00:00.3000000Z",
        executable_path=str(root_cmd),
        command_line=(
            f'"{root_cmd}" /d /s /c "{node_executable}" '
            f'"{project_root / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"}"'
        ),
    )
    vite_node = _snapshot_process(
        record,
        pid=6104,
        parent_pid=6103,
        creation_date="2026-08-06T00:00:00.4000000Z",
        executable_path=str(node_executable),
        command_line=(
            f'"{node_executable}" '
            f'"{project_root / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"}" '
            "--host 127.0.0.1"
        ),
    )
    esbuild_executable = (
        project_root
        / "frontend"
        / "node_modules"
        / "@esbuild"
        / "win32-x64"
        / "esbuild.exe"
    )
    esbuild = _snapshot_process(
        record,
        pid=6105,
        parent_pid=6104,
        creation_date="2026-08-06T00:00:00.5000000Z",
        executable_path=str(esbuild_executable),
        command_line=f'"{esbuild_executable}" --service=0.25.0 --ping',
    )
    tree = [root, conhost, project_node, child_cmd, vite_node, esbuild]
    root_exit = "2026-08-06T00:00:10.0000000Z"
    assert all(
        root["CreationDate"] <= process["CreationDate"] <= root_exit
        for process in tree[1:]
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {6100, 6101, 6102, 6103, 6104, 6105}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_python_real_shape_conhost_and_reload_tree_is_owned(tmp_path: Path) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="python-agent",
        pid=6200,
        process_kind="PYTHON",
    )
    python_executable = Path("D:/miniconda/python.exe")
    record["executable_path"] = str(python_executable)
    record["command_line"] = (
        f'"{python_executable}" -m uvicorn mtls_adapter:create_app --reload '
        f'--app-dir "{record["working_directory"]}"'
    )
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    root = _snapshot_process(record)
    conhost = _snapshot_process(
        record,
        pid=6201,
        parent_pid=6200,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(Path("C:/Windows/System32/conhost.exe")),
        command_line=r"\??\C:\Windows\System32\conhost.exe 0x4",
    )
    conhost["WorkingDirectory"] = None
    reload_child = _snapshot_process(
        record,
        pid=6202,
        parent_pid=6200,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(python_executable),
        command_line=record["command_line"] + " --reload-child",
    )
    tree = [root, conhost, reload_child]
    root_exit = "2026-08-06T00:00:10.0000000Z"
    assert all(
        root["CreationDate"] <= process["CreationDate"] <= root_exit
        for process in tree[1:]
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {6200, 6201, 6202}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def _frontend_helper_negative_fixture(
    tmp_path: Path,
    *,
    pid: int,
) -> tuple[Path, Path, dict[str, Any], bytes, dict[str, Any], Path, Path]:
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="frontend",
        pid=pid,
        process_kind="FRONTEND",
    )
    system32 = Path("C:/Windows/System32")
    root_cmd = system32 / "cmd.exe"
    record["executable_path"] = str(root_cmd)
    record["command_line"] = (
        f'"{root_cmd}" /d /c pnpm --dir "{record["working_directory"]}" dev'
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    return (
        project_root,
        record_directory,
        record,
        raw_record,
        _snapshot_process(record),
        root_cmd,
        project_root / ".tools" / "node.exe",
    )


@pytest.mark.parametrize("violation", ("non-system32", "wrong-parent", "older"))
def test_frontend_conhost_helper_requires_exact_system_root_and_lifetime(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    (
        project_root,
        record_directory,
        record,
        raw_record,
        root,
        _,
        node_executable,
    ) = _frontend_helper_negative_fixture(tmp_path, pid=6300)
    tree = [root]
    conhost_parent = root
    if violation == "wrong-parent":
        conhost_parent = _snapshot_process(
            record,
            pid=6301,
            parent_pid=6300,
            creation_date="2026-08-06T00:00:00.1000000Z",
            executable_path=str(node_executable),
            command_line=(
                f'"{node_executable}" '
                f'"{project_root / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"}"'
            ),
        )
        tree.append(conhost_parent)
    conhost_executable = (
        project_root / "frontend" / "tools" / "conhost.exe"
        if violation == "non-system32"
        else Path("C:/Windows/System32/conhost.exe")
    )
    conhost = _snapshot_process(
        record,
        pid=6302,
        parent_pid=conhost_parent["ProcessId"],
        creation_date=(
            "2026-08-05T23:59:59.0000000Z"
            if violation == "older"
            else "2026-08-06T00:00:00.2000000Z"
        ),
        executable_path=str(conhost_executable),
        command_line=rf"\??\{conhost_executable} 0x4",
    )
    conhost["WorkingDirectory"] = None
    tree.append(conhost)

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree],
    )

    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


@pytest.mark.parametrize(
    "violation",
    (
        "outside-project",
        "outside-node-modules",
        "non-node-parent",
        "command-mismatch",
        "working-directory-mismatch",
    ),
)
def test_frontend_esbuild_helper_requires_project_node_parent_and_binding(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    (
        project_root,
        record_directory,
        record,
        raw_record,
        root,
        root_cmd,
        node_executable,
    ) = _frontend_helper_negative_fixture(tmp_path, pid=6400)
    parent_executable = root_cmd if violation == "non-node-parent" else node_executable
    parent = _snapshot_process(
        record,
        pid=6401,
        parent_pid=6400,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(parent_executable),
        command_line=(
            f'"{parent_executable}" '
            f'"{project_root / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"}"'
        ),
    )
    if violation == "outside-project":
        esbuild_executable = Path("D:/outside/node_modules/esbuild.exe")
    elif violation == "outside-node-modules":
        esbuild_executable = project_root / "frontend" / "tools" / "esbuild.exe"
    else:
        esbuild_executable = (
            project_root
            / "frontend"
            / "node_modules"
            / "@esbuild"
            / "win32-x64"
            / "esbuild.exe"
        )
    command_executable = (
        project_root
        / "frontend"
        / "node_modules"
        / "other"
        / "esbuild.exe"
        if violation == "command-mismatch"
        else esbuild_executable
    )
    esbuild = _snapshot_process(
        record,
        pid=6402,
        parent_pid=6401,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(esbuild_executable),
        command_line=f'"{command_executable}" --service=0.25.0 --ping',
        working_directory=(
            str(project_root)
            if violation == "working-directory-mismatch"
            else record["working_directory"]
        ),
    )
    tree = [root, parent, esbuild]

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree],
    )

    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


@pytest.mark.parametrize(
    "violation",
    (
        "missing-role",
        "wrong-role",
        "duplicate-role",
        "conflicting-role",
        "non-system32",
        "non-root-parent",
        "older-than-root",
        "command-executable-mismatch",
    ),
)
def test_java_conhost_helper_rejects_non_authoritative_shapes(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(project_root, name="java-api", pid=6500)
    root_command_line = record["command_line"]
    if violation == "missing-role":
        root_command_line = root_command_line.replace(
            " --app.temporal.worker.role=API",
            "",
        )
    elif violation == "wrong-role":
        root_command_line = root_command_line.replace(
            "--app.temporal.worker.role=API",
            "--app.temporal.worker.role=CONTROL",
        )
    elif violation == "duplicate-role":
        root_command_line += " --app.temporal.worker.role=API"
    elif violation == "conflicting-role":
        root_command_line += " --app.temporal.worker.role=CONTROL"
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record, command_line=root_command_line)
    conhost_parent = root
    tree = [root]
    if violation == "non-root-parent":
        conhost_parent = _snapshot_process(
            record,
            pid=6501,
            parent_pid=6500,
            creation_date="2026-08-06T00:00:00.0200000Z",
            executable_path=record["executable_path"],
            command_line=record["command_line"] + " --child",
        )
        tree.append(conhost_parent)
    conhost_executable = Path("C:/Windows/System32/conhost.exe")
    if violation == "non-system32":
        conhost_executable = project_root / "java-api-service" / "conhost.exe"
    conhost_command = rf"\??\{conhost_executable} 0x4"
    if violation == "command-executable-mismatch":
        conhost_command = r"\??\C:\Windows\System32\cmd.exe 0x4"
    conhost = _snapshot_process(
        record,
        pid=6502,
        parent_pid=conhost_parent["ProcessId"],
        creation_date=(
            "2026-08-05T23:59:59.9990000Z"
            if violation == "older-than-root"
            else "2026-08-06T00:00:00.0500000Z"
        ),
        executable_path=str(conhost_executable),
        command_line=conhost_command,
    )
    conhost["WorkingDirectory"] = None
    tree.append(conhost)

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree],
    )

    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_frontend_live_corepack_and_vite_null_working_directory_tree_is_owned(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    (
        project_root,
        record_directory,
        record,
        _,
        root,
        root_cmd,
        _,
    ) = _frontend_helper_negative_fixture(tmp_path, pid=6600)
    conhost_executable = Path("C:/Windows/System32/conhost.exe")
    conhost = _snapshot_process(
        record,
        pid=6601,
        parent_pid=6600,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(conhost_executable),
        command_line=rf"\??\{conhost_executable} 0x4",
    )
    conhost["WorkingDirectory"] = None
    external_node = Path("D:/New Folder for enviroment/node.exe")
    corepack_pnpm = (
        Path("D:/New Folder for enviroment")
        / "node_modules"
        / "corepack"
        / "dist"
        / "pnpm.js"
    )
    corepack_node = _snapshot_process(
        record,
        pid=6602,
        parent_pid=6600,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(external_node),
        command_line=f'"{external_node}" "{corepack_pnpm}" dev',
    )
    corepack_node["WorkingDirectory"] = None
    vite_cmd = _snapshot_process(
        record,
        pid=6603,
        parent_pid=6602,
        creation_date="2026-08-06T00:00:00.3000000Z",
        executable_path=str(root_cmd),
        command_line="cmd.exe /d /s /c vite --host 0.0.0.0",
    )
    vite_cmd["WorkingDirectory"] = None
    vite_entrypoint = (
        project_root / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"
    )
    vite_node = _snapshot_process(
        record,
        pid=6604,
        parent_pid=6603,
        creation_date="2026-08-06T00:00:00.4000000Z",
        executable_path=str(external_node),
        command_line=f'"{external_node}" "{vite_entrypoint}" --host 0.0.0.0',
    )
    esbuild_executable = (
        project_root
        / "frontend"
        / "node_modules"
        / "@esbuild"
        / "win32-x64"
        / "esbuild.exe"
    )
    esbuild = _snapshot_process(
        record,
        pid=6605,
        parent_pid=6604,
        creation_date="2026-08-06T00:00:00.5000000Z",
        executable_path=str(esbuild_executable),
        command_line=f'"{esbuild_executable}" --service=0.25.0 --ping',
    )
    tree = [root, conhost, corepack_node, vite_cmd, vite_node, esbuild]
    root_exit = "2026-08-06T00:00:10.0000000Z"
    assert corepack_node["WorkingDirectory"] is None
    assert vite_cmd["WorkingDirectory"] is None
    assert all(
        root["CreationDate"] <= process["CreationDate"] <= root_exit
        for process in tree[1:]
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {6600, 6601, 6602, 6603, 6604, 6605}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_python_live_spawn_reload_null_working_directory_child_is_owned(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="python-agent",
        pid=6700,
        process_kind="PYTHON",
    )
    python_executable = Path("D:/miniconda/python.exe")
    record["executable_path"] = str(python_executable)
    record["command_line"] = (
        f'"{python_executable}" -m uvicorn mtls_adapter:create_app --reload '
        f'--app-dir "{record["working_directory"]}"'
    )
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    root = _snapshot_process(record)
    conhost_executable = Path("C:/Windows/System32/conhost.exe")
    conhost = _snapshot_process(
        record,
        pid=6701,
        parent_pid=6700,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(conhost_executable),
        command_line=rf"\??\{conhost_executable} 0x4",
    )
    conhost["WorkingDirectory"] = None
    reload_child = _snapshot_process(
        record,
        pid=6702,
        parent_pid=6700,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(python_executable),
        command_line=(
            f'"{python_executable}" "-c" '
            '"from multiprocessing.spawn import spawn_main; spawn_main()"'
        ),
    )
    reload_child["WorkingDirectory"] = None
    tree = [root, conhost, reload_child]
    root_exit = "2026-08-06T00:00:10.0000000Z"
    assert reload_child["WorkingDirectory"] is None
    assert all(
        root["CreationDate"] <= process["CreationDate"] <= root_exit
        for process in tree[1:]
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {6700, 6701, 6702}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def _assert_fake_tree_rejected_and_record_preserved(
    tmp_path: Path,
    *,
    bundle: str,
    project_root: Path,
    record_directory: Path,
    record: dict[str, Any],
    raw_record: bytes,
    tree: list[dict[str, Any]],
) -> None:
    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree],
    )
    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


@pytest.mark.parametrize(
    ("command_form", "base_pid"),
    (("legacy-horizontal-whitespace", 6800), ("producer-dir", 6900)),
)
def test_frontend_authoritative_corepack_command_forms_own_full_tree(
    tmp_path: Path,
    command_form: str,
    base_pid: int,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    (
        project_root,
        record_directory,
        record,
        _,
        root,
        root_cmd,
        _,
    ) = _frontend_helper_negative_fixture(tmp_path, pid=base_pid)
    external_node = Path("D:/New Folder for enviroment/node.exe")
    corepack_pnpm = (
        Path("D:/New Folder for enviroment")
        / "node_modules"
        / "corepack"
        / "dist"
        / "pnpm.js"
    )
    frontend_directory = project_root / "frontend"
    corepack_command = (
        f'"{external_node}"   \t"{corepack_pnpm}"\t  dev'
        if command_form == "legacy-horizontal-whitespace"
        else (
            f'"{external_node}" "{corepack_pnpm}" '
            f'--dir "{frontend_directory}" dev'
        )
    )
    corepack_node = _snapshot_process(
        record,
        pid=base_pid + 1,
        parent_pid=base_pid,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(external_node),
        command_line=corepack_command,
    )
    corepack_node["WorkingDirectory"] = None
    vite_cmd = _snapshot_process(
        record,
        pid=base_pid + 2,
        parent_pid=base_pid + 1,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(root_cmd),
        command_line="cmd.exe /d /s /c vite --host 0.0.0.0",
    )
    vite_cmd["WorkingDirectory"] = None
    vite_entrypoint = frontend_directory / "node_modules" / "vite" / "bin" / "vite.js"
    vite_node = _snapshot_process(
        record,
        pid=base_pid + 3,
        parent_pid=base_pid + 2,
        creation_date="2026-08-06T00:00:00.3000000Z",
        executable_path=str(external_node),
        command_line=f'"{external_node}" "{vite_entrypoint}" --host 0.0.0.0',
    )
    esbuild_executable = (
        frontend_directory
        / "node_modules"
        / "@esbuild"
        / "win32-x64"
        / "esbuild.exe"
    )
    esbuild = _snapshot_process(
        record,
        pid=base_pid + 4,
        parent_pid=base_pid + 3,
        creation_date="2026-08-06T00:00:00.4000000Z",
        executable_path=str(esbuild_executable),
        command_line=f'"{esbuild_executable}" --service=0.25.0 --ping',
    )
    tree = [root, corepack_node, vite_cmd, vite_node, esbuild]

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {base_pid + offset for offset in range(5)}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


@pytest.mark.parametrize(
    "violation",
    (
        "arbitrary-script",
        "wrong-dir",
        "extra-token",
        "shell-chaining",
        "wrong-parent",
    ),
)
def test_frontend_null_wd_corepack_requires_exact_command_and_root_parent(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    (
        project_root,
        record_directory,
        record,
        raw_record,
        root,
        _,
        _,
    ) = _frontend_helper_negative_fixture(tmp_path, pid=7000)
    external_node = Path("D:/New Folder for enviroment/node.exe")
    install_root = Path("D:/New Folder for enviroment")
    corepack_pnpm = install_root / "node_modules" / "corepack" / "dist" / "pnpm.js"
    frontend_directory = project_root / "frontend"
    command_line = f'"{external_node}" "{corepack_pnpm}" dev'
    if violation == "arbitrary-script":
        command_line = f'"{external_node}" "{install_root / "arbitrary.js"}" dev'
    elif violation == "wrong-dir":
        command_line = (
            f'"{external_node}" "{corepack_pnpm}" '
            f'--dir "{project_root / "not-frontend"}" dev'
        )
    elif violation == "extra-token":
        command_line += " --extra"
    elif violation == "shell-chaining":
        command_line += " && whoami"
    candidate_parent = root
    tree = [root]
    if violation == "wrong-parent":
        candidate_parent = _snapshot_process(
            record,
            pid=7001,
            parent_pid=7000,
            creation_date="2026-08-06T00:00:00.1000000Z",
            executable_path=str(external_node),
            command_line=(
                f'"{external_node}" '
                f'"{frontend_directory / "node_modules" / "vite" / "bin" / "vite.js"}"'
            ),
        )
        tree.append(candidate_parent)
    corepack_node = _snapshot_process(
        record,
        pid=7002,
        parent_pid=candidate_parent["ProcessId"],
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(external_node),
        command_line=command_line,
    )
    corepack_node["WorkingDirectory"] = None
    tree.append(corepack_node)

    _assert_fake_tree_rejected_and_record_preserved(
        tmp_path,
        bundle=bundle,
        project_root=project_root,
        record_directory=record_directory,
        record=record,
        raw_record=raw_record,
        tree=tree,
    )


@pytest.mark.parametrize("violation", ("arbitrary-command", "non-corepack-parent"))
def test_frontend_null_wd_vite_cmd_requires_exact_command_and_corepack_parent(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    (
        project_root,
        record_directory,
        record,
        raw_record,
        root,
        root_cmd,
        _,
    ) = _frontend_helper_negative_fixture(tmp_path, pid=7100)
    external_node = Path("D:/New Folder for enviroment/node.exe")
    corepack_pnpm = (
        Path("D:/New Folder for enviroment")
        / "node_modules"
        / "corepack"
        / "dist"
        / "pnpm.js"
    )
    parent = _snapshot_process(
        record,
        pid=7101,
        parent_pid=7100,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(external_node),
        command_line=(
            f'"{external_node}" "{corepack_pnpm}" dev'
            if violation == "arbitrary-command"
            else (
                f'"{external_node}" '
                f'"{project_root / "frontend" / "node_modules" / "vite" / "bin" / "vite.js"}"'
            )
        ),
    )
    parent["WorkingDirectory"] = (
        None if violation == "arbitrary-command" else record["working_directory"]
    )
    vite_cmd = _snapshot_process(
        record,
        pid=7102,
        parent_pid=7101,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(root_cmd),
        command_line=(
            "cmd.exe /d /s /c whoami"
            if violation == "arbitrary-command"
            else "cmd.exe /d /s /c vite --host 0.0.0.0"
        ),
    )
    vite_cmd["WorkingDirectory"] = None

    _assert_fake_tree_rejected_and_record_preserved(
        tmp_path,
        bundle=bundle,
        project_root=project_root,
        record_directory=record_directory,
        record=record,
        raw_record=raw_record,
        tree=[root, parent, vite_cmd],
    )


@pytest.mark.parametrize("violation", ("arbitrary-c", "wrong-parent"))
def test_python_null_wd_reload_requires_exact_spawn_and_root_parent(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="python-agent",
        pid=7200,
        process_kind="PYTHON",
    )
    python_executable = Path("D:/miniconda/python.exe")
    record["executable_path"] = str(python_executable)
    record["command_line"] = (
        f'"{python_executable}" -m uvicorn mtls_adapter:create_app --reload '
        f'--app-dir "{record["working_directory"]}"'
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    candidate_parent = root
    tree = [root]
    if violation == "wrong-parent":
        candidate_parent = _snapshot_process(
            record,
            pid=7201,
            parent_pid=7200,
            creation_date="2026-08-06T00:00:00.1000000Z",
            executable_path=str(python_executable),
            command_line=record["command_line"] + " --reload-child",
        )
        tree.append(candidate_parent)
    reload_child = _snapshot_process(
        record,
        pid=7202,
        parent_pid=candidate_parent["ProcessId"],
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(python_executable),
        command_line=(
            f'"{python_executable}" "-c" "print(123)"'
            if violation == "arbitrary-c"
            else (
                f'"{python_executable}" "-c" '
                '"from multiprocessing.spawn import spawn_main; spawn_main()"'
            )
        ),
    )
    reload_child["WorkingDirectory"] = None
    tree.append(reload_child)

    _assert_fake_tree_rejected_and_record_preserved(
        tmp_path,
        bundle=bundle,
        project_root=project_root,
        record_directory=record_directory,
        record=record,
        raw_record=raw_record,
        tree=tree,
    )


@pytest.mark.parametrize("process_shape", ("frontend-node", "frontend-cmd", "python"))
def test_generic_null_wd_runtime_basename_is_not_authority(
    tmp_path: Path,
    process_shape: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    is_python = process_shape == "python"
    record = _ownership_record(
        project_root,
        name="python-agent" if is_python else "frontend",
        pid=7300,
        process_kind="PYTHON" if is_python else "FRONTEND",
    )
    if is_python:
        executable = Path("D:/miniconda/python.exe")
        record["executable_path"] = str(executable)
        record["command_line"] = (
            f'"{executable}" -m uvicorn mtls_adapter:create_app --reload '
            f'--app-dir "{record["working_directory"]}"'
        )
    else:
        root_cmd = Path("C:/Windows/System32/cmd.exe")
        record["executable_path"] = str(root_cmd)
        record["command_line"] = (
            f'"{root_cmd}" /d /c pnpm --dir "{record["working_directory"]}" dev'
        )
        executable = (
            Path("D:/New Folder for enviroment/node.exe")
            if process_shape == "frontend-node"
            else root_cmd
        )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    candidate = _snapshot_process(
        record,
        pid=7301,
        parent_pid=7300,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(executable),
        command_line=(
            f'"{executable}" --version'
            if process_shape != "frontend-cmd"
            else "cmd.exe /d /s /c echo unrelated"
        ),
    )
    candidate["WorkingDirectory"] = None

    _assert_fake_tree_rejected_and_record_preserved(
        tmp_path,
        bundle=bundle,
        project_root=project_root,
        record_directory=record_directory,
        record=record,
        raw_record=raw_record,
        tree=[root, candidate],
    )


@pytest.mark.parametrize(
    ("name", "role", "root_pid", "conhost_created"),
    (
        ("java-api", "API", 7600, "2026-08-06T00:00:00.0430000Z"),
        (
            "java-control-worker",
            "CONTROL",
            7610,
            "2026-08-06T00:00:00.0500000Z",
        ),
        (
            "java-agent-worker",
            "AGENT",
            7620,
            "2026-08-06T00:00:00.0560000Z",
        ),
    ),
    ids=("api", "control", "agent"),
)
def test_java_live_conhost_is_owned_for_each_worker_role(
    tmp_path: Path,
    name: str,
    role: str,
    root_pid: int,
    conhost_created: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name=name,
        pid=root_pid,
        process_kind="JAVA",
    )
    assert f"--app.temporal.worker.role={role}" in record["command_line"]
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    root = _snapshot_process(record)
    conhost_executable = Path("C:/Windows/System32/conhost.exe")
    conhost = _snapshot_process(
        record,
        pid=root_pid + 1,
        parent_pid=root_pid,
        creation_date=conhost_created,
        executable_path=str(conhost_executable),
        command_line=r"\??\C:\WINDOWS\system32\conhost.exe 0x4",
    )
    conhost["WorkingDirectory"] = None
    external_java = Path("D:/external-jdk/temurin-21/bin/java.exe")
    direct_java_child = _snapshot_process(
        record,
        pid=root_pid + 2,
        parent_pid=root_pid,
        creation_date="2026-08-06T00:00:00.0600000Z",
        executable_path=str(external_java),
        command_line=_java_authoritative_command(
            external_java,
            project_root / "java-api-service",
            name,
        ),
        working_directory=str(project_root / "java-api-service"),
    )
    tree = [root, conhost, direct_java_child]

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {root_pid, root_pid + 1, root_pid + 2}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


@pytest.mark.parametrize(
    "violation",
    (
        "wrong-parent",
        "older-than-root",
        "wrong-executable",
        "command-executable-mismatch",
        "wrong-project-provenance",
        "wrong-role",
    ),
)
def test_java_direct_external_child_requires_exact_role_and_project_authority(
    tmp_path: Path,
    violation: str,
) -> None:
    """External Java children are permitted only by closed role/project authority."""
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="java-control-worker",
        pid=7640,
        process_kind="JAVA",
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    conhost = _snapshot_process(
        record,
        pid=7641,
        parent_pid=7640,
        creation_date="2026-08-06T00:00:00.0500000Z",
        executable_path="C:/Windows/System32/conhost.exe",
        command_line=r"\??\C:\WINDOWS\system32\conhost.exe 0x4",
    )
    conhost["WorkingDirectory"] = None
    external_java = Path("D:/external-jdk/temurin-21/bin/java.exe")
    executable = external_java
    expected_working_directory = project_root / "java-api-service"
    candidate_working_directory = expected_working_directory
    role = "CONTROL"
    creation_date = "2026-08-06T00:00:00.0600000Z"
    parent_pid = root["ProcessId"]
    command_executable = external_java
    if violation == "wrong-parent":
        parent_pid = conhost["ProcessId"]
    elif violation == "older-than-root":
        creation_date = "2026-08-05T23:59:59.9990000Z"
    elif violation == "wrong-executable":
        executable = Path("D:/external-jdk/temurin-21/bin/python.exe")
        command_executable = executable
    elif violation == "command-executable-mismatch":
        command_executable = Path("D:/unrelated/java.exe")
    elif violation == "wrong-project-provenance":
        candidate_working_directory = project_root / "frontend"
    elif violation == "wrong-role":
        role = "AGENT"
    direct_java_child = _snapshot_process(
        record,
        pid=7642,
        parent_pid=parent_pid,
        creation_date=creation_date,
        executable_path=str(executable),
        command_line=_java_authoritative_command(
            command_executable,
            expected_working_directory,
            "java-control-worker",
            application_tokens=(f"--app.temporal.worker.role={role}",),
        ),
        working_directory=str(candidate_working_directory),
    )
    tree = [root, conhost, direct_java_child]

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree],
    )

    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


@pytest.mark.parametrize(
    ("launcher_mode", "mode_tokens"),
    (
        ("jar", ("-jar", "D:/unrelated.jar")),
        ("module-short", ("-m", "unrelated.module/Main")),
        ("module-long", ("--module", "unrelated.module/Main")),
        ("argument-file", ("@D:/unrelated.args",)),
        ("source-short", ("--source", "21")),
        ("source-equals", ("--source=21",)),
    ),
    ids=(
        "jar",
        "module-short",
        "module-long",
        "argument-file",
        "source-short",
        "source-equals",
    ),
)
def test_java_direct_application_child_rejects_alternate_entrypoint_modes(
    tmp_path: Path,
    launcher_mode: str,
    mode_tokens: tuple[str, ...],
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="java-control-worker",
        pid=7650,
        process_kind="JAVA",
    )
    external_java = Path("D:/external-jdk/temurin-21/bin/java.exe")
    working_directory = project_root / "java-api-service"
    prefix_tokens = list(_java_authoritative_jvm_prefix(record["name"]))
    classpath_index = prefix_tokens.index("-cp")
    prefix_tokens[classpath_index:classpath_index] = mode_tokens
    mutated_prefix = tuple(prefix_tokens)
    record["command_line"] = _java_authoritative_command(
        Path(record["executable_path"]),
        working_directory,
        record["name"],
        prefix_tokens=mutated_prefix,
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    command_line = _java_authoritative_command(
        external_java,
        working_directory,
        record["name"],
        prefix_tokens=mutated_prefix,
    )
    direct_java_child = _snapshot_process(
        record,
        pid=7651,
        parent_pid=root["ProcessId"],
        creation_date="2026-08-06T00:00:00.0600000Z",
        executable_path=str(external_java),
        command_line=command_line,
        working_directory=str(working_directory),
    )
    tree = [root, direct_java_child]
    assert launcher_mode in {
        "jar",
        "module-short",
        "module-long",
        "argument-file",
        "source-short",
        "source-equals",
    }
    assert direct_java_child["ParentProcessId"] == root["ProcessId"]
    assert root["CreationDate"] <= direct_java_child["CreationDate"]
    assert direct_java_child["ExecutablePath"] == str(external_java)
    assert direct_java_child["WorkingDirectory"] == str(working_directory)
    assert command_line.count(" -cp ") == 1
    assert command_line.count(JAVA_APPLICATION_MAIN) == 1
    assert all(token in command_line for token in mode_tokens)
    assert command_line.index(mode_tokens[0]) < command_line.index(" -cp ")

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) in {
        "OWNERSHIP_RECORD_INVALID",
        "PROCESS_IDENTITY_MISMATCH",
    }
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def _java_direct_child_classpath_case(
    tmp_path: Path,
    *,
    classpath_shape: str,
) -> tuple[dict[str, Any], bytes, dict[str, Any], dict[str, Any]]:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="java-api",
        pid=7660,
        process_kind="JAVA",
    )
    external_java = Path("D:/external-jdk/temurin-21/bin/java.exe")
    working_directory = project_root / "java-api-service"
    target_classes = working_directory / "target" / "target-e2e-classes"
    classpath_value = {
        "external-first": f"D:/unrelated.jar;{target_classes}",
        "traversal-outside-target": (
            f'{working_directory / "target" / ".." / "outside.jar"};{target_classes}'
        ),
        "empty-entry": f";{target_classes}",
        "wildcard-entry": f"D:/dependencies/*;{target_classes}",
        "project-first": (
            f"{target_classes};D:/dependency.jar;D:/more-dependency.jar"
        ),
    }[classpath_shape]
    classpath_entries = tuple(classpath_value.split(";"))
    if classpath_shape == "project-first":
        record["command_line"] = _java_authoritative_command(
            Path(record["executable_path"]),
            working_directory,
            record["name"],
            classpath_entries=classpath_entries,
        )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    command_line = _java_authoritative_command(
        external_java,
        working_directory,
        record["name"],
        classpath_entries=classpath_entries,
    )
    direct_java_child = _snapshot_process(
        record,
        pid=7661,
        parent_pid=root["ProcessId"],
        creation_date="2026-08-06T00:00:00.0600000Z",
        executable_path=str(external_java),
        command_line=command_line,
        working_directory=str(working_directory),
    )
    tree = [root, direct_java_child]
    assert direct_java_child["ParentProcessId"] == root["ProcessId"]
    assert root["CreationDate"] <= direct_java_child["CreationDate"]
    assert direct_java_child["ExecutablePath"] == str(external_java)
    assert direct_java_child["WorkingDirectory"] == str(working_directory)
    assert command_line.count(" -cp ") == 1
    assert command_line.count(JAVA_APPLICATION_MAIN) == 1
    assert command_line.index(JAVA_APPLICATION_MAIN) > command_line.index(classpath_value)

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )
    return result, raw_record, root, direct_java_child


@pytest.mark.parametrize(
    "classpath_shape",
    (
        "external-first",
        "traversal-outside-target",
        "empty-entry",
        "wildcard-entry",
    ),
)
def test_java_direct_application_child_rejects_untrusted_classpath_shapes(
    tmp_path: Path,
    classpath_shape: str,
) -> None:
    result, raw_record, _, _ = _java_direct_child_classpath_case(
        tmp_path,
        classpath_shape=classpath_shape,
    )

    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_java_direct_application_child_accepts_project_first_dependency_classpath(
    tmp_path: Path,
) -> None:
    result, _, root, direct_java_child = _java_direct_child_classpath_case(
        tmp_path,
        classpath_shape="project-first",
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {root["ProcessId"], direct_java_child["ProcessId"]}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def _java_authoritative_producer_command_case(
    tmp_path: Path,
    *,
    root_shape: str,
) -> tuple[dict[str, Any], bytes, dict[str, Any], dict[str, Any]]:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="java-api",
        pid=7690,
        process_kind="JAVA",
    )
    working_directory = project_root / "java-api-service"
    target_directory = working_directory / "target"
    first_classpath_entry = target_directory / "target-e2e-classes"
    mode_tokens_by_shape = {
        "jar": ("-jar", "D:/unrelated.jar"),
        "module-short": ("-m", "unrelated.module/Main"),
        "module-long": ("--module", "unrelated.module/Main"),
        "argument-file": ("@D:/unrelated.args",),
        "source-short": ("--source", "21"),
        "source-equals": ("--source=21",),
    }
    if root_shape == "target-unrelated-jar":
        first_classpath_entry = target_directory / "unrelated.jar"
        mode_tokens: tuple[str, ...] = ()
    elif root_shape == "target-other-classes":
        first_classpath_entry = target_directory / "other-classes"
        mode_tokens = ()
    elif root_shape == "producer":
        mode_tokens = ()
    elif root_shape in mode_tokens_by_shape:
        mode_tokens = mode_tokens_by_shape[root_shape]
    else:
        raise AssertionError(f"unsupported Java producer grammar shape: {root_shape}")

    classpath_value = ";".join(
        (
            str(first_classpath_entry),
            "D:/m2/repository/org/springframework/spring-core.jar",
            "D:/m2/repository/io/temporal/temporal-sdk.jar",
        )
    )
    argument_tokens = (
        "-Xms128m",
        "-Xmx1024m",
        "-XX:+ExitOnOutOfMemoryError",
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005",
        *mode_tokens,
        "-cp",
        classpath_value,
        "com.example.dispute.DisputeApplication",
        "--spring.profiles.active=local,target-e2e,api",
        "--app.temporal.worker.role=API",
        "--app.target-e2e.enabled=true",
        "--server.port=8081",
    )
    root_executable = Path(record["executable_path"])
    record["command_line"] = _render_java_argument_replay_command(
        root_executable,
        argument_tokens,
        separator=" ",
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)

    external_java = Path("D:/external-jdk/temurin-21/bin/java.exe")
    direct_java_child = _snapshot_process(
        record,
        pid=7691,
        parent_pid=root["ProcessId"],
        creation_date="2026-08-06T00:00:00.0600000Z",
        executable_path=str(external_java),
        command_line=_render_java_argument_replay_command(
            external_java,
            argument_tokens,
            separator=" ",
        ),
        working_directory=str(working_directory),
    )
    root_prefix = f'"{root_executable}"'
    child_prefix = f'"{external_java}"'
    assert root["CommandLine"] == record["command_line"]
    assert root["CommandLine"][len(root_prefix) :] == direct_java_child[
        "CommandLine"
    ][len(child_prefix) :]

    tree = [root, direct_java_child]
    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )
    return result, raw_record, root, direct_java_child


@pytest.mark.parametrize(
    "root_shape",
    (
        "target-unrelated-jar",
        "target-other-classes",
        "jar",
        "module-short",
        "module-long",
        "argument-file",
        "source-short",
        "source-equals",
    ),
)
def test_java_root_record_rejects_non_authoritative_producer_grammar(
    tmp_path: Path,
    root_shape: str,
) -> None:
    result, raw_record, _, _ = _java_authoritative_producer_command_case(
        tmp_path,
        root_shape=root_shape,
    )

    assert _result_code(result) == "OWNERSHIP_RECORD_INVALID"
    assert result["snapshot_calls"] == 0
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_java_authoritative_producer_command_owns_exact_external_jdk_replay(
    tmp_path: Path,
) -> None:
    result, _, root, direct_java_child = _java_authoritative_producer_command_case(
        tmp_path,
        root_shape="producer",
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {root["ProcessId"], direct_java_child["ProcessId"]}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def _java_root_argument_replay_case(
    tmp_path: Path,
    *,
    drift: str | None,
) -> tuple[
    dict[str, Any],
    bytes,
    dict[str, Any],
    dict[str, Any],
    tuple[str, ...],
    tuple[str, ...],
]:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="java-api",
        pid=7670,
        process_kind="JAVA",
    )
    working_directory = project_root / "java-api-service"
    target_classes = working_directory / "target" / "target-e2e-classes"
    dependency_entries = (
        "D:/m2/repository/org/springframework/spring-core.jar",
        "D:/m2/repository/io/temporal/temporal-sdk.jar",
    )
    classpath_value = ";".join((str(target_classes), *dependency_entries))
    root_argument_tokens = (
        "-Xms128m",
        "-Xmx1024m",
        "-XX:+ExitOnOutOfMemoryError",
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005",
        "-cp",
        classpath_value,
        "com.example.dispute.DisputeApplication",
        "--spring.profiles.active=local,api",
        "--app.temporal.worker.role=API",
        "--server.port=8081",
        "--app.target-e2e.enabled=true",
    )
    record["command_line"] = _render_java_argument_replay_command(
        Path(record["executable_path"]),
        root_argument_tokens,
        separator=" ",
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)

    child_argument_tokens = list(root_argument_tokens)
    classpath_index = child_argument_tokens.index("-cp") + 1
    if drift == "target-unrelated-jar":
        child_argument_tokens[classpath_index] = ";".join(
            (
                str(working_directory / "target" / "unrelated.jar"),
                *dependency_entries,
            )
        )
    elif drift == "different-target-classes-root":
        child_argument_tokens[classpath_index] = ";".join(
            (
                str(working_directory / "target" / "test-classes"),
                *dependency_entries,
            )
        )
    elif drift == "inserted-jvm-flag":
        child_argument_tokens.insert(
            child_argument_tokens.index("-cp"),
            "-Dsynthetic.unrelated=true",
        )
    elif drift == "altered-jvm-flag":
        child_argument_tokens[child_argument_tokens.index("-Xmx1024m")] = "-Xmx2048m"
    elif drift == "changed-application-argument":
        child_argument_tokens[
            child_argument_tokens.index("--server.port=8081")
        ] = "--server.port=9091"
    elif drift == "extra-application-argument":
        child_argument_tokens.append("--synthetic.unrelated=true")
    elif drift is not None:
        raise AssertionError(f"unsupported Java argument replay drift: {drift}")

    child_argument_token_tuple = tuple(child_argument_tokens)
    external_java = Path("D:/external-jdk/temurin-21/bin/java.exe")
    direct_java_child = _snapshot_process(
        record,
        pid=7671,
        parent_pid=root["ProcessId"],
        creation_date="2026-08-06T00:00:00.0600000Z",
        executable_path=str(external_java),
        command_line=_render_java_argument_replay_command(
            external_java,
            child_argument_token_tuple,
            separator="\t",
            trailing_whitespace=" \t",
        ),
        working_directory=str(working_directory),
    )
    tree = [root, direct_java_child]
    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )
    return (
        result,
        raw_record,
        root,
        direct_java_child,
        root_argument_tokens,
        child_argument_token_tuple,
    )


def test_java_direct_application_child_accepts_exact_root_argument_replay(
    tmp_path: Path,
) -> None:
    result, _, root, direct_java_child, root_arguments, child_arguments = (
        _java_root_argument_replay_case(tmp_path, drift=None)
    )

    assert root["ExecutablePath"] != direct_java_child["ExecutablePath"]
    assert root["CommandLine"] != direct_java_child["CommandLine"]
    assert root_arguments == child_arguments
    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {root["ProcessId"], direct_java_child["ProcessId"]}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


@pytest.mark.parametrize(
    "drift",
    (
        "target-unrelated-jar",
        "different-target-classes-root",
        "inserted-jvm-flag",
        "altered-jvm-flag",
        "changed-application-argument",
        "extra-application-argument",
    ),
)
def test_java_direct_application_child_rejects_root_argument_tail_drift(
    tmp_path: Path,
    drift: str,
) -> None:
    result, raw_record, _, _, root_arguments, child_arguments = (
        _java_root_argument_replay_case(tmp_path, drift=drift)
    )

    assert root_arguments != child_arguments
    assert _result_code(result) == "PROCESS_IDENTITY_MISMATCH"
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def _java_quoted_role_replay_case(
    tmp_path: Path,
    *,
    role_tokens: tuple[str, ...],
) -> tuple[dict[str, Any], bytes, dict[str, Any], dict[str, Any]]:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="java-api",
        pid=7680,
        process_kind="JAVA",
    )
    working_directory = project_root / "java-api-service"
    classpath_value = ";".join(
        (
            str(working_directory / "target" / "target-e2e-classes"),
            "D:/m2/repository/org/springframework/spring-core.jar",
            "D:/m2/repository/io/temporal/temporal-sdk.jar",
        )
    )
    argument_tokens = (
        "-Xms128m",
        "-Xmx1024m",
        "-XX:+ExitOnOutOfMemoryError",
        "-cp",
        classpath_value,
        "com.example.dispute.DisputeApplication",
        "--spring.profiles.active=local,api",
        *role_tokens,
        "--server.port=8081",
    )
    root_executable = Path(record["executable_path"])
    record["command_line"] = _render_java_argument_replay_command(
        root_executable,
        argument_tokens,
        separator=" ",
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)

    external_java = Path("D:/external-jdk/temurin-21/bin/java.exe")
    direct_java_child = _snapshot_process(
        record,
        pid=7681,
        parent_pid=root["ProcessId"],
        creation_date="2026-08-06T00:00:00.0600000Z",
        executable_path=str(external_java),
        command_line=_render_java_argument_replay_command(
            external_java,
            argument_tokens,
            separator=" ",
        ),
        working_directory=str(working_directory),
    )
    root_prefix = f'"{root_executable}"'
    child_prefix = f'"{external_java}"'
    assert root["CommandLine"] == record["command_line"]
    assert root["CommandLine"][len(root_prefix) :] == direct_java_child[
        "CommandLine"
    ][len(child_prefix) :]

    tree = [root, direct_java_child]
    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )
    return result, raw_record, root, direct_java_child


@pytest.mark.parametrize(
    "role_tokens",
    (
        (
            "--app.temporal.worker.role=API",
            '"--app.temporal.worker.role=CONTROL"',
        ),
        (
            "--app.temporal.worker.role=API",
            '"--app.temporal.worker.role=API"',
        ),
    ),
    ids=("quoted-conflicting-role", "quoted-duplicate-role"),
)
def test_java_role_argv_replay_rejects_quoted_duplicate_or_conflicting_tokens(
    tmp_path: Path,
    role_tokens: tuple[str, ...],
) -> None:
    result, raw_record, _, _ = _java_quoted_role_replay_case(
        tmp_path,
        role_tokens=role_tokens,
    )

    assert _result_code(result) == "OWNERSHIP_RECORD_INVALID"
    assert result["snapshot_calls"] == 0
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_java_role_argv_replay_accepts_single_quoted_expected_token(
    tmp_path: Path,
) -> None:
    result, _, root, direct_java_child = _java_quoted_role_replay_case(
        tmp_path,
        role_tokens=('"--app.temporal.worker.role=API"',),
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {root["ProcessId"], direct_java_child["ProcessId"]}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def _python_null_wd_root_command(
    project_root: Path,
    python_executable: Path,
    *,
    app_directory: Path | None = None,
    reload_directory: Path | None = None,
    host: str = "127.0.0.1",
    port: str = "18000",
) -> str:
    app_directory = (
        project_root / "deploy" / "target-e2e" / "python"
        if app_directory is None
        else app_directory
    )
    command = (
        f'"{python_executable}" -m uvicorn mtls_adapter:create_app --factory '
        f'--app-dir "{app_directory}" --host {host} --port {port} --reload'
    )
    if reload_directory is not None:
        command += f' --reload-dir "{reload_directory}"'
    return command + " --loop asyncio"


def _python_null_wd_live_tree(
    record: dict[str, Any],
    python_executable: Path,
) -> list[dict[str, Any]]:
    root_pid = int(record["pid"])
    root = _snapshot_process(record)
    root["WorkingDirectory"] = None
    conhost = _snapshot_process(
        record,
        pid=root_pid + 1,
        parent_pid=root_pid,
        creation_date="2026-08-06T00:00:00.0430000Z",
        executable_path=str(Path("C:/Windows/System32/conhost.exe")),
        command_line=r"\??\C:\WINDOWS\system32\conhost.exe 0x4",
    )
    conhost["WorkingDirectory"] = None
    reload_child = _snapshot_process(
        record,
        pid=root_pid + 2,
        parent_pid=root_pid,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(python_executable),
        command_line=(
            f'"{python_executable}" "-c" '
            '"from multiprocessing.spawn import spawn_main; '
            f'spawn_main(parent_pid={root_pid}, pipe_handle=81)" '
            "--multiprocessing-fork"
        ),
    )
    reload_child["WorkingDirectory"] = None
    return [root, conhost, reload_child]


@pytest.mark.parametrize(
    ("command_form", "base_pid"),
    (("legacy", 7700), ("current-producer", 7710)),
)
def test_python_live_root_null_wd_authoritative_commands_own_full_tree(
    tmp_path: Path,
    command_form: str,
    base_pid: int,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="python-agent",
        pid=base_pid,
        process_kind="PYTHON",
    )
    python_executable = Path("D:/miniconda/python.exe")
    record["executable_path"] = str(python_executable)
    record["command_line"] = _python_null_wd_root_command(
        project_root,
        python_executable,
        reload_directory=(
            project_root / "python-agent-service"
            if command_form == "current-producer"
            else None
        ),
    )
    assert str(project_root).casefold() in record["command_line"].casefold()
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )
    tree = _python_null_wd_live_tree(record, python_executable)

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {base_pid, base_pid + 1, base_pid + 2}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


@pytest.mark.parametrize(
    "violation",
    (
        "shortened",
        "outside-app-dir",
        "wrong-reload-dir",
        "host-mismatch",
        "port-zero",
        "port-overflow",
        "port-nondecimal",
        "reordered",
        "extra-token",
        "shell-chaining",
    ),
)
def test_python_null_wd_root_command_rejects_non_authoritative_grammar(
    tmp_path: Path,
    violation: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="python-agent",
        pid=7720,
        process_kind="PYTHON",
    )
    python_executable = Path("D:/miniconda/python.exe")
    expected_app_directory = project_root / "deploy" / "target-e2e" / "python"
    expected_reload_directory = project_root / "python-agent-service"
    record["executable_path"] = str(python_executable)
    if violation == "shortened":
        command = (
            f'"{python_executable}" -m uvicorn mtls_adapter:create_app '
            f'--app-dir "{expected_app_directory}" --reload'
        )
    elif violation == "outside-app-dir":
        command = _python_null_wd_root_command(
            project_root,
            python_executable,
            app_directory=Path("C:/outside/target-e2e/python"),
            reload_directory=expected_reload_directory,
        )
    elif violation == "wrong-reload-dir":
        command = _python_null_wd_root_command(
            project_root,
            python_executable,
            reload_directory=project_root / "frontend",
        )
    elif violation == "host-mismatch":
        command = _python_null_wd_root_command(
            project_root,
            python_executable,
            host="0.0.0.0",
        )
    elif violation in {"port-zero", "port-overflow", "port-nondecimal"}:
        invalid_port = {
            "port-zero": "0",
            "port-overflow": "65536",
            "port-nondecimal": "eighteen-thousand",
        }[violation]
        command = _python_null_wd_root_command(
            project_root,
            python_executable,
            port=invalid_port,
        )
    elif violation == "reordered":
        command = (
            f'"{python_executable}" -m uvicorn mtls_adapter:create_app --factory '
            f'--host 127.0.0.1 --app-dir "{expected_app_directory}" '
            "--port 18000 --reload --loop asyncio"
        )
    else:
        command = _python_null_wd_root_command(project_root, python_executable)
        command += " --workers 2" if violation == "extra-token" else " & whoami"
    assert str(project_root).casefold() in command.casefold()
    assert "uvicorn" in command
    record["command_line"] = command
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    root["WorkingDirectory"] = None

    _assert_fake_tree_rejected_and_record_preserved(
        tmp_path,
        bundle=bundle,
        project_root=project_root,
        record_directory=record_directory,
        record=record,
        raw_record=raw_record,
        tree=[root],
    )


def _legacy_frontend_adoption_fixture(
    tmp_path: Path,
    *,
    include_project_bound_authority: bool,
) -> tuple[
    Path,
    Path,
    dict[str, Any],
    list[dict[str, Any]],
]:
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="frontend",
        pid=7800,
        process_kind="FRONTEND",
    )
    system_cmd = Path("C:/Windows/System32/cmd.exe")
    record["executable_path"] = str(system_cmd)
    record["command_line"] = r'"C:\Windows\system32\cmd.exe" /d /c pnpm dev'
    root = _snapshot_process(record)
    root["WorkingDirectory"] = None
    conhost = _snapshot_process(
        record,
        pid=7801,
        parent_pid=7800,
        creation_date="2026-08-06T00:00:00.0430000Z",
        executable_path=str(Path("C:/Windows/System32/conhost.exe")),
        command_line=r"\??\C:\WINDOWS\system32\conhost.exe 0x4",
    )
    conhost["WorkingDirectory"] = None
    external_node = Path("D:/New Folder for enviroment/node.exe")
    corepack_pnpm = (
        external_node.parent / "node_modules" / "corepack" / "dist" / "pnpm.js"
    )
    corepack_node = _snapshot_process(
        record,
        pid=7802,
        parent_pid=7800,
        creation_date="2026-08-06T00:00:00.1000000Z",
        executable_path=str(external_node),
        command_line=f'"{external_node}"   "{corepack_pnpm}"\tdev',
    )
    corepack_node["WorkingDirectory"] = None
    vite_cmd = _snapshot_process(
        record,
        pid=7803,
        parent_pid=7802,
        creation_date="2026-08-06T00:00:00.2000000Z",
        executable_path=str(system_cmd),
        command_line="cmd.exe /d /s /c vite --host 0.0.0.0",
    )
    vite_cmd["WorkingDirectory"] = None
    generic_legacy_tree = [root, conhost, corepack_node, vite_cmd]
    if not include_project_bound_authority:
        # Reserved green-phase negative: this exact generic legacy root/helper
        # tree has no project-bound descendant and must never authorize a stop.
        return project_root, record_directory, record, generic_legacy_tree

    frontend_directory = project_root / "frontend"
    vite_entrypoint = frontend_directory / "node_modules" / "vite" / "bin" / "vite.js"
    vite_node = _snapshot_process(
        record,
        pid=7804,
        parent_pid=7803,
        creation_date="2026-08-06T00:00:00.3000000Z",
        executable_path=str(external_node),
        command_line=f'"{external_node}" "{vite_entrypoint}" --host 0.0.0.0',
    )
    esbuild_executable = (
        frontend_directory
        / "node_modules"
        / "@esbuild"
        / "win32-x64"
        / "esbuild.exe"
    )
    esbuild = _snapshot_process(
        record,
        pid=7805,
        parent_pid=7804,
        creation_date="2026-08-06T00:00:00.4000000Z",
        executable_path=str(esbuild_executable),
        command_line=f'"{esbuild_executable}" --service=0.25.0 --ping',
    )
    return (
        project_root,
        record_directory,
        record,
        generic_legacy_tree + [vite_node, esbuild],
    )


def test_legacy_frontend_root_adopts_only_full_project_bound_descendant_tree(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root, record_directory, record, tree = _legacy_frontend_adoption_fixture(
        tmp_path,
        include_project_bound_authority=True,
    )
    assert record["command_line"] == (
        r'"C:\Windows\system32\cmd.exe" /d /c pnpm dev'
    )
    assert str(project_root).casefold() not in record["command_line"].casefold()
    assert all(process["WorkingDirectory"] is None for process in tree[:4])
    assert str(project_root).casefold() in tree[4]["CommandLine"].casefold()
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {7800, 7801, 7802, 7803, 7804, 7805}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_legacy_frontend_corepack_accepts_redundant_install_boundary_separators(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root, record_directory, record, tree = _legacy_frontend_adoption_fixture(
        tmp_path,
        include_project_bound_authority=True,
    )
    root, conhost, corepack_node, vite_cmd, vite_node, esbuild = tree
    redundant_node_token = r"D:\New Folder for enviroment\\node.exe"
    redundant_corepack_token = (
        r"D:\New Folder for enviroment\\node_modules\corepack\dist\pnpm.js"
    )
    corepack_node["CommandLine"] = (
        f'"{redundant_node_token}"  \t"{redundant_corepack_token}"   dev'
    )
    assert record["command_line"] == (
        r'"C:\Windows\system32\cmd.exe" /d /c pnpm dev'
    )
    assert root["ProcessId"] == 7800
    assert conhost["ParentProcessId"] == root["ProcessId"]
    assert corepack_node["ParentProcessId"] == root["ProcessId"]
    assert corepack_node["ExecutablePath"] == str(
        Path("D:/New Folder for enviroment/node.exe")
    )
    assert corepack_node["WorkingDirectory"] is None
    assert root["CreationDate"] <= corepack_node["CreationDate"]
    assert "\\\\node.exe" in corepack_node["CommandLine"]
    assert "\\\\node_modules\\corepack\\dist\\pnpm.js" in corepack_node[
        "CommandLine"
    ]
    assert vite_cmd["ParentProcessId"] == corepack_node["ProcessId"]
    assert str(project_root).casefold() in vite_node["CommandLine"].casefold()
    assert str(project_root).casefold() in esbuild["ExecutablePath"].casefold()
    _write_record(
        record_directory,
        record["name"],
        json.dumps(record, separators=(",", ":")).encode("utf-8"),
    )

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[tree, tree, []],
    )

    assert _result_code(result) == "TERMINATED"
    assert len(result["termination_calls"]) == 1
    plan = {
        key.lower(): value for key, value in result["termination_calls"][0].items()
    }
    assert {
        int({key.lower(): value for key, value in process.items()}["pid"])
        for process in plan["processes"]
    } == {7800, 7801, 7802, 7803, 7804, 7805}
    assert len(result["removal_calls"]) == 1
    assert result["record_exists"] is False


def test_legacy_frontend_root_without_project_bound_descendant_fails_closed(
    tmp_path: Path,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root, record_directory, record, tree = _legacy_frontend_adoption_fixture(
        tmp_path,
        include_project_bound_authority=False,
    )
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    assert len(tree) == 4
    assert all(process["WorkingDirectory"] is None for process in tree)
    assert all(
        str(project_root).casefold() not in process["CommandLine"].casefold()
        for process in tree
    )

    _assert_fake_tree_rejected_and_record_preserved(
        tmp_path,
        bundle=bundle,
        project_root=project_root,
        record_directory=record_directory,
        record=record,
        raw_record=raw_record,
        tree=tree,
    )


@pytest.mark.parametrize(
    ("violation", "expected_code"),
    (
        ("switch-s", "OWNERSHIP_RECORD_INVALID"),
        ("extra-args", "OWNERSHIP_RECORD_INVALID"),
        ("shell-chaining", "OWNERSHIP_RECORD_INVALID"),
        ("wrong-cmd-path", "PROCESS_IDENTITY_MISMATCH"),
        ("command-executable-mismatch", "PROCESS_IDENTITY_MISMATCH"),
    ),
)
def test_legacy_frontend_root_grammar_rejects_non_authoritative_commands(
    tmp_path: Path,
    violation: str,
    expected_code: str,
) -> None:
    bundle = _contract_bundle_or_outcome(decisive=False)
    project_root = tmp_path / "candidate"
    record_directory = tmp_path / "ownership"
    record = _ownership_record(
        project_root,
        name="frontend",
        pid=7810,
        process_kind="FRONTEND",
    )
    system_cmd = Path("C:/Windows/System32/cmd.exe")
    project_cmd = project_root / "frontend" / "tools" / "cmd.exe"
    if violation == "wrong-cmd-path":
        record["executable_path"] = str(project_cmd)
        record["command_line"] = f'"{project_cmd}" /d /c pnpm dev'
    elif violation == "command-executable-mismatch":
        record["executable_path"] = str(system_cmd)
        record["command_line"] = f'"{project_cmd}" /d /c pnpm dev'
    else:
        record["executable_path"] = str(system_cmd)
        record["command_line"] = {
            "switch-s": r'"C:\Windows\system32\cmd.exe" /d /s /c pnpm dev',
            "extra-args": (
                r'"C:\Windows\system32\cmd.exe" /d /c pnpm dev --host 0.0.0.0'
            ),
            "shell-chaining": (
                r'"C:\Windows\system32\cmd.exe" /d /c pnpm dev & whoami'
            ),
        }[violation]
    raw_record = json.dumps(record, separators=(",", ":")).encode("utf-8")
    _write_record(record_directory, record["name"], raw_record)
    root = _snapshot_process(record)
    root["WorkingDirectory"] = None

    result = _run_sandboxed_powershell(
        tmp_path,
        bundle=bundle,
        name=record["name"],
        project_root=project_root,
        record_directory=record_directory,
        snapshots=[[root]],
    )

    assert _result_code(result) == expected_code
    assert result["termination_calls"] == []
    assert result["removal_calls"] == []
    assert result["record_exists"] is True
    assert result["record_content"].encode("utf-8") == raw_record


def test_creation_date_equivalence_bridges_handle_and_cim_precision(
    tmp_path: Path,
) -> None:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    source = LAUNCHER.read_text(encoding="utf-8")
    definitions = dict(_function_definitions(source))
    helper_name = "Test-SourceProcessOwnershipCreationDateEquivalent"
    assert helper_name in definitions["New-SourceProcessOwnershipPublicationRecord"]
    assert helper_name in definitions["Test-SourceProcessOwnershipRootIdentity"]
    assert helper_name in definitions["New-SourceProcessOwnershipUnpublishedPlan"]
    assert helper_name in definitions["Invoke-SourceProcessOwnershipTreeTermination"]
    assert helper_name in definitions[
        "Invoke-SourceProcessOwnershipUnpublishedPlanTermination"
    ]

    function_file = tmp_path / "ownership-functions.ps1"
    harness_file = tmp_path / "creation-date-harness.ps1"
    result_file = tmp_path / "creation-date-result.json"
    function_file.write_text(_contract_bundle_or_outcome(decisive=True), encoding="utf-8")
    harness_file.write_text(
        r"""
param(
    [Parameter(Mandatory = $true)][string]$FunctionFile,
    [Parameter(Mandatory = $true)][string]$ResultFile
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. $FunctionFile
$handleTime = "2026-08-06T11:02:02.1277882+00:00"
$cimTime = "2026-08-06T11:02:02.1270000Z"
$differentTime = "2026-08-06T11:02:02.1290000Z"
[System.IO.File]::WriteAllText(
    $ResultFile,
    ([pscustomobject]@{
        Equivalent = Test-SourceProcessOwnershipCreationDateEquivalent `
            -First $handleTime `
            -Second $cimTime
        Different = Test-SourceProcessOwnershipCreationDateEquivalent `
            -First $handleTime `
            -Second $differentTime
    } |
        ConvertTo-Json -Compress),
    [System.Text.UTF8Encoding]::new($false)
)
""".strip(),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-ResultFile",
            str(result_file),
        ],
        text=True,
        capture_output=True,
        check=False,
        timeout=30,
    )

    assert completed.returncode == 0, completed.stderr
    result = json.loads(result_file.read_text(encoding="utf-8"))
    assert result == {"Equivalent": True, "Different": False}


def _run_launcher_helper_harness(
    tmp_path: Path,
    *,
    helper_names: set[str],
    body: str,
) -> dict[str, Any]:
    if shutil.which("powershell.exe") is None:
        pytest.skip("Windows PowerShell is not available")

    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    definitions = dict(_function_definitions(launcher))
    missing = sorted(helper_names - definitions.keys())
    assert not missing, f"launcher helper definitions are missing: {missing}"
    function_file = tmp_path / "launcher-helper-functions.ps1"
    harness_file = tmp_path / "invoke-launcher-helpers.ps1"
    result_file = tmp_path / "result.json"
    function_file.write_text(
        "\n\n".join(definitions[name] for name in sorted(helper_names)),
        encoding="utf-8",
    )
    harness_file.write_text(
        "\n".join(
            (
                "param(",
                "    [Parameter(Mandatory = $true)][string]$FunctionFile,",
                "    [Parameter(Mandatory = $true)][string]$SandboxRoot,",
                "    [Parameter(Mandatory = $true)][string]$ResultFile",
                ")",
                '$ErrorActionPreference = "Stop"',
                "Set-StrictMode -Version Latest",
                ". $FunctionFile",
                body,
                "[System.IO.File]::WriteAllText(",
                "    $ResultFile,",
                "    ($output | ConvertTo-Json -Depth 20 -Compress),",
                "    [System.Text.UTF8Encoding]::new($false)",
                ")",
            )
        ),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness_file),
            "-FunctionFile",
            str(function_file),
            "-SandboxRoot",
            str(tmp_path),
            "-ResultFile",
            str(result_file),
        ],
        text=True,
        capture_output=True,
        check=False,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout
    return json.loads(result_file.read_text(encoding="utf-8"))


def test_reviewed_dirty_source_authority_hashes_untracked_and_detects_drift(
    tmp_path: Path,
) -> None:
    result = _run_launcher_helper_harness(
        tmp_path,
        helper_names={
            "Assert-TargetE2eDirtySourceAuthorityUnchanged",
            "ConvertTo-TargetE2eNormalizedDirtySourcePath",
            "New-TargetE2eReviewedDirtySourcePolicy",
            "Resolve-TargetE2eDirtySourceAuthority",
        },
        body=r"""
$allowedPath = "new-contract.java"
$allowedFile = Join-Path $SandboxRoot $allowedPath
[System.IO.File]::WriteAllText(
    $allowedFile,
    "frozen-authority`n",
    [System.Text.UTF8Encoding]::new($false))
$policy = New-TargetE2eReviewedDirtySourcePolicy `
    -ModifiedPaths @() `
    -UntrackedPaths @($allowedPath)
$entries = @(Resolve-TargetE2eDirtySourceAuthority `
        -StatusLines @("?? $allowedPath") `
        -ProjectRoot $SandboxRoot `
        -Policy $policy)
$expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $allowedFile).Hash.ToLowerInvariant()
$unknownError = ""
try {
    Resolve-TargetE2eDirtySourceAuthority `
        -StatusLines @("?? unknown.java") `
        -ProjectRoot $SandboxRoot `
        -Policy $policy | Out-Null
}
catch {
    $unknownError = $_.Exception.Message
}
[System.IO.File]::AppendAllText(
    $allowedFile,
    "x",
    [System.Text.UTF8Encoding]::new($false))
$driftError = ""
try {
    Assert-TargetE2eDirtySourceAuthorityUnchanged `
        -Entries $entries `
        -ProjectRoot $SandboxRoot
}
catch {
    $driftError = $_.Exception.Message
}
$output = [pscustomobject]@{
    count = $entries.Count
    path = $entries[0].Path
    status = $entries[0].Status
    sha256 = $entries[0].Sha256
    expected_hash = $expectedHash
    unknown_error = $unknownError
    drift_error = $driftError
}
""".strip(),
    )

    assert result == {
        "count": 1,
        "path": "new-contract.java",
        "status": "??",
        "sha256": result["expected_hash"],
        "expected_hash": result["expected_hash"],
        "unknown_error": "TARGET_E2E_DIRTY_SOURCE_AUTHORITY_REJECTED",
        "drift_error": "TARGET_E2E_DIRTY_SOURCE_AUTHORITY_DRIFT",
    }
    assert re.fullmatch(r"[0-9a-f]{64}", result["sha256"])


def test_reviewed_modified_python_sources_are_closed_and_binding_complete(
    tmp_path: Path,
) -> None:
    reviewed_paths = (
        "python-agent-service/app/agents/dispute_intake_officer/room_utterance.py",
        "python-agent-service/app/agents/dispute_intake_officer/workflow.py",
        "python-agent-service/app/graph_runtime/intake_executor.py",
    )
    adjacent_path = "python-agent-service/app/graph_runtime/unreviewed_adjacent.py"
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    allowlist_match = re.search(
        r"\$allowedDirtyPaths\s*=.*?@\((?P<body>.*?)\)\s*\|\s*ForEach-Object\s*"
        r"\{\s*\[void\]\$allowedDirtyPaths\.Add\(\$_\)\s*\}",
        launcher,
        flags=re.DOTALL,
    )
    assert allowlist_match is not None
    allowed_paths = set(
        re.findall(r'"([^"\r\n]+)"', allowlist_match.group("body"))
    )
    assert set(reviewed_paths) <= allowed_paths
    assert adjacent_path not in allowed_paths

    result = _run_launcher_helper_harness(
        tmp_path,
        helper_names={
            "ConvertTo-TargetE2eNormalizedDirtySourcePath",
            "Get-TargetE2eSourceBindingHash",
            "New-TargetE2eReviewedDirtySourcePolicy",
            "Resolve-TargetE2eDirtySourceAuthority",
        },
        body=r"""
$firstPath = "python-agent-service/app/agents/dispute_intake_officer/room_utterance.py"
$secondPath = "python-agent-service/app/agents/dispute_intake_officer/workflow.py"
$thirdPath = "python-agent-service/app/graph_runtime/intake_executor.py"
$adjacentPath = "python-agent-service/app/graph_runtime/unreviewed_adjacent.py"
$files = @(
    [pscustomobject]@{ Path = $firstPath; Content = "room-utterance-reviewed`n" },
    [pscustomobject]@{ Path = $secondPath; Content = "workflow-reviewed`n" },
    [pscustomobject]@{ Path = $thirdPath; Content = "intake-executor-reviewed`n" }
)
foreach ($file in $files) {
    $target = Join-Path $SandboxRoot $file.Path
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    [System.IO.File]::WriteAllText(
        $target,
        $file.Content,
        [System.Text.UTF8Encoding]::new($false))
}
$policy = New-TargetE2eReviewedDirtySourcePolicy `
    -ModifiedPaths @($firstPath, $secondPath, $thirdPath) `
    -UntrackedPaths @()
$entries = @(Resolve-TargetE2eDirtySourceAuthority `
        -StatusLines @(" M $thirdPath", " M $secondPath", " M $firstPath") `
        -ProjectRoot $SandboxRoot `
        -Policy $policy)
$entryBindings = @($entries | ForEach-Object { $_.Path + "|" + $_.Sha256 })
$candidateSha = "a" * 40
$binding = Get-TargetE2eSourceBindingHash `
    -Value ((@("HEAD|$candidateSha") + $entryBindings) -join "`n")
$withoutFirst = Get-TargetE2eSourceBindingHash `
    -Value ((@("HEAD|$candidateSha") + @($entryBindings | Select-Object -Skip 1)) -join "`n")
$withoutSecond = Get-TargetE2eSourceBindingHash `
    -Value ((@("HEAD|$candidateSha") + @($entryBindings[0], $entryBindings[2])) -join "`n")
$withoutThird = Get-TargetE2eSourceBindingHash `
    -Value ((@("HEAD|$candidateSha") + @($entryBindings | Select-Object -First 2)) -join "`n")
$unknownError = ""
try {
    Resolve-TargetE2eDirtySourceAuthority `
        -StatusLines @(" M $adjacentPath") `
        -ProjectRoot $SandboxRoot `
        -Policy $policy | Out-Null
}
catch {
    $unknownError = $_.Exception.Message
}
$output = [pscustomobject]@{
    paths = @($entries | ForEach-Object { $_.Path })
    statuses = @($entries | ForEach-Object { $_.Status })
    hashes = @($entries | ForEach-Object { $_.Sha256 })
    binding = $binding
    binding_without_first = $withoutFirst
    binding_without_second = $withoutSecond
    binding_without_third = $withoutThird
    unknown_error = $unknownError
}
""".strip(),
    )

    expected_hashes = (
        hashlib.sha256(b"room-utterance-reviewed\n").hexdigest(),
        hashlib.sha256(b"workflow-reviewed\n").hexdigest(),
        hashlib.sha256(b"intake-executor-reviewed\n").hexdigest(),
    )
    expected_binding_material = "\n".join(
        (
            "HEAD|" + "a" * 40,
            *(f"{path}|{sha256}" for path, sha256 in zip(reviewed_paths, expected_hashes)),
        )
    )
    expected_binding = hashlib.sha256(
        expected_binding_material.encode("utf-8")
    ).hexdigest()

    assert result["paths"] == list(reviewed_paths)
    assert result["statuses"] == [" M", " M", " M"]
    assert result["hashes"] == list(expected_hashes)
    assert result["binding"] == expected_binding
    assert result["binding"] != result["binding_without_first"]
    assert result["binding"] != result["binding_without_second"]
    assert result["binding"] != result["binding_without_third"]
    assert result["unknown_error"] == "TARGET_E2E_DIRTY_SOURCE_AUTHORITY_REJECTED"


def test_clean_worktree_accepts_empty_dirty_source_authority(tmp_path: Path) -> None:
    result = _run_launcher_helper_harness(
        tmp_path,
        helper_names={"Assert-TargetE2eDirtySourceAuthorityUnchanged"},
        body=r"""
Assert-TargetE2eDirtySourceAuthorityUnchanged `
    -Entries @() `
    -ProjectRoot $SandboxRoot
$output = [pscustomobject]@{ accepted = $true }
""".strip(),
    )

    assert result == {"accepted": True}


def test_terminal_no_commit_uat_paths_have_exact_reviewed_status_authority() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")

    def reviewed_paths(variable: str) -> set[str]:
        match = re.search(
            rf"\${variable}\s*=.*?@\((?P<body>.*?)\)\s*\|\s*ForEach-Object\s*"
            rf"\{{\s*\[void\]\${variable}\.Add\(\$_\)\s*\}}",
            launcher,
            flags=re.DOTALL,
        )
        assert match is not None
        return set(re.findall(r'"([^"\r\n]+)"', match.group("body")))

    modified_paths = reviewed_paths("allowedDirtyPaths")
    untracked_paths = reviewed_paths("allowedUntrackedPaths")
    expected_modified = {
        "frontend/src/views/disputes/IntakeRoomView.test.js",
        "frontend/src/views/disputes/IntakeRoomView.vue",
        "java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/entity/AgentRunEntity.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/activity/domain/CaseProcessLedgerActivitiesImpl.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/infrastructure/persistence/entity/CaseCommandEntity.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/temporal/caseprocess/CaseCommandLifecycleActivities.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/intake/IntakeAgentRunFinalizationReadResult.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/intake/IntakeRoomCarryState.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/temporal/intake/finalizationread/JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/activity/domain/CaseProcessLedgerActivitiesImplTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/recovery/TemporalWorkerRecoveryTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/temporal/caseprocess/CaseProcessTypedChildDispatchTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/targete2e/temporal/intake/finalizationread/JdbcTargetIntakeAgentRunFinalizationReceiptReadPortTest.java",
    }
    expected_untracked = {
        "java-api-service/src/main/java/com/example/dispute/workflow/temporal/caseprocess/TargetIntakeCommandTerminalNoCommit.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/intake/IntakeTerminalNoCommitRecoveryRequest.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/intake/IntakeTerminalNoCommitRecoveryResult.java",
    }

    assert expected_modified <= modified_paths
    assert expected_untracked <= untracked_paths
    assert expected_modified.isdisjoint(untracked_paths)
    assert expected_untracked.isdisjoint(modified_paths)
    assert all(
        not any(token in path for token in ("*", "?", "[", "]", "\\"))
        for path in expected_modified | expected_untracked
    )


def test_topology_ownership_gate_reconciles_five_roles_before_provision(
    tmp_path: Path,
) -> None:
    result = _run_launcher_helper_harness(
        tmp_path,
        helper_names={"Invoke-SourceTopologyOwnershipGate"},
        body=r"""
$names = @("java-api", "java-control-worker", "java-agent-worker", "python-agent", "frontend")
$staleEvents = [System.Collections.Generic.List[string]]::new()
$staleAction = {
    param([string]$Name)
    $staleEvents.Add("reconcile:$Name")
    return [pscustomobject]@{ Code = "PROCESS_ALREADY_EXITED" }
}
$receipt = Invoke-SourceTopologyOwnershipGate `
    -Names $names `
    -ReconcileAction $staleAction
$staleEvents.Add("provision")

$invalidEvents = [System.Collections.Generic.List[string]]::new()
$invalidAction = {
    param([string]$Name)
    $invalidEvents.Add("reconcile:$Name")
    if ($Name -eq "java-control-worker") {
        return [pscustomobject]@{ Code = "PROCESS_IDENTITY_MISMATCH" }
    }
    return [pscustomobject]@{ Code = "PROCESS_ALREADY_EXITED" }
}
$invalidError = ""
try {
    Invoke-SourceTopologyOwnershipGate `
        -Names $names `
        -ReconcileAction $invalidAction | Out-Null
    $invalidEvents.Add("provision")
}
catch {
    $invalidError = $_.Exception.Message
}
$output = [pscustomobject]@{
    receipt_schema = $receipt.SchemaVersion
    receipt_count = @($receipt.Results).Count
    stale_events = @($staleEvents)
    invalid_events = @($invalidEvents)
    invalid_error = $invalidError
}
""".strip(),
    )

    assert result["receipt_schema"] == "local-source-topology-ownership-gate.v1"
    assert result["receipt_count"] == 5
    assert result["stale_events"] == [
        "reconcile:java-api",
        "reconcile:java-control-worker",
        "reconcile:java-agent-worker",
        "reconcile:python-agent",
        "reconcile:frontend",
        "provision",
    ]
    assert result["invalid_events"] == [
        "reconcile:java-api",
        "reconcile:java-control-worker",
    ]
    assert result["invalid_error"] == (
        "SOURCE_TOPOLOGY_OWNERSHIP_GATE_REJECTED:java-control-worker"
    )


def test_topology_ownership_gate_precedes_every_irreversible_launcher_call() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")
    build_index = launcher.index("if ($mustBuildJavaOverlay)")
    gate_index = launcher.index(
        "$sourceTopologyOwnershipGate = Invoke-SourceTopologyOwnershipGate"
    )
    migration_index = launcher.index("Invoke-DomainMigrationPreflight", gate_index)
    provision_index = launcher.index(
        '& "D:\\miniconda\\python.exe" $provisioner', gate_index
    )
    routing_index = launcher.index("Ensure-TemporalDefaultBuildId `", gate_index)

    assert build_index < gate_index
    assert gate_index < migration_index < provision_index < routing_index
    between_gate_and_migration = launcher[gate_index:migration_index]
    assert between_gate_and_migration.count(
        "Assert-TargetE2eSourceBindingUnchanged"
    ) == 1


def test_prepare_java_overlay_only_is_exact_and_exits_before_runtime_mutation() -> None:
    launcher = LAUNCHER.read_text(encoding="utf-8-sig")

    assert launcher.count("[switch]$PrepareJavaOverlayOnly") == 1
    assert (
        "$mustBuildJavaOverlay =\n"
        "    $PrepareJavaOverlayOnly -or\n"
    ) in launcher
    assert (
        '"target\\target-e2e-classes.staging." + '
        '[Guid]::NewGuid().ToString("N")'
    ) in launcher
    assert '"-Dtarget-e2e.classes-directory=$stagedTargetClasses"' in launcher

    binding_validation_index = launcher.index(
        "$compiledWorktreeBinding -cne $expectedJavaSourceBinding"
    )
    build_only_index = launcher.index(
        "if ($PrepareJavaOverlayOnly) {", binding_validation_index
    )
    output_index = launcher.index(
        'schema_version = "local-target-e2e-java-overlay.v1"', build_only_index
    )
    exit_index = launcher.index("exit 0", output_index)
    catch_index = launcher.index("\ncatch {", exit_index)
    build_only_body = launcher[build_only_index:catch_index]

    assert "Assert-TargetE2eSourceBindingUnchanged" in build_only_body
    assert (
        "staged_overlay_path = "
        "[System.IO.Path]::GetFullPath($stagedTargetClasses)"
    ) in build_only_body
    for field in (
        "compiled_source_sha",
        "compiled_worktree_binding",
        "expected_control_build_id",
        "artifact_marker_sha256",
    ):
        assert f"{field} =" in build_only_body
    assert "Move-Item" not in build_only_body

    gate_index = launcher.index(
        "$sourceTopologyOwnershipGate = Invoke-SourceTopologyOwnershipGate"
    )
    migration_index = launcher.index("\nInvoke-DomainMigrationPreflight\n", gate_index)
    provision_index = launcher.index(
        '& "D:\\miniconda\\python.exe" $provisioner', gate_index
    )
    canonical_promotion_index = launcher.index(
        "Move-Item -LiteralPath $stagedOverlay -Destination $canonicalOverlay",
        gate_index,
    )
    proxy_index = launcher.index(
        "$existingProxyState = Get-LocalProxyContainerState", gate_index
    )
    routing_index = launcher.index("Ensure-TemporalDefaultBuildId `", gate_index)
    process_index = launcher.index("$javaApi = Start-JavaSourceProcess", gate_index)
    assert binding_validation_index < build_only_index < output_index < exit_index
    assert exit_index < min(
        gate_index,
        migration_index,
        provision_index,
        canonical_promotion_index,
        proxy_index,
        routing_index,
        process_index,
    )

    cleanup_start = launcher.index("function Remove-TargetE2eStagedOverlayExact")
    cleanup_end = launcher.index("\n}\n\ntry {", cleanup_start) + 2
    cleanup = launcher[cleanup_start:cleanup_end]
    assert '"^target-e2e-classes\\.staging\\.[0-9a-f]{32}$"' in cleanup
    assert "Remove-Item -LiteralPath $stagedOverlay -Recurse -Force" in cleanup
    assert "Remove-Item -LiteralPath $targetDirectory" not in cleanup

    def reviewed_paths(variable: str) -> set[str]:
        match = re.search(
            rf"\${variable}\s*=.*?@\((?P<body>.*?)\)\s*\|\s*ForEach-Object\s*"
            rf"\{{\s*\[void\]\${variable}\.Add\(\$_\)\s*\}}",
            launcher,
            flags=re.DOTALL,
        )
        assert match is not None
        return set(re.findall(r'"([^"\r\n]+)"', match.group("body")))

    modified_paths = reviewed_paths("allowedDirtyPaths")
    untracked_paths = reviewed_paths("allowedUntrackedPaths")
    expected_modified = {
        "python-agent-service/app/api/intake_parallel_stream_service.py",
        "java-api-service/src/main/java/com/example/dispute/workflow/activity/system/TemporalWorkerProbeWorkflowImpl.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/application/intake/IntakePrivateThreadRegistration.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerProperties.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/projection/evidence/EvidenceProcessProjectionView.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/recovery/ExactCaseProcessWorkflowPinRecoveryMain.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/exchange/rooms/TargetE2eRoomExchangeContract.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/finalization/TargetE2eExecutionLaneVerifier.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/ingress/materialization/TargetIntakeRuntimePins.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/ingress/rooms/TargetE2eEvidenceManifestPublisher.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/ingress/rooms/TargetE2eHearingInvocationPublisher.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/temporal/TargetTypedRoomProtocol.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/targete2e/temporal/intake/finalizationread/JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/config/TemporalWorkerConfigurationTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/config/TemporalWorkerPropertiesTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/recovery/ExactCaseProcessWorkflowPinRecoveryMainTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/targete2e/ingress/materialization/TargetIntakeRuntimePinsTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/targete2e/temporal/intake/finalizationread/JdbcTargetIntakeAgentRunFinalizationReceiptReadPortTest.java",
    }
    expected_untracked = {
        "java-api-service/src/main/java/com/example/dispute/workflow/recovery/ExactCaseProcessWorkflowRePinRecoveryMain.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/recovery/ExactIntakeRoomWorkflowRePinRecoveryMain.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/recovery/ExactCaseProcessWorkflowRePinRecoveryMainTest.java",
        "java-api-service/src/test/java/com/example/dispute/workflow/recovery/ExactIntakeRoomWorkflowRePinRecoveryMainTest.java",
    }
    assert expected_modified <= modified_paths
    assert expected_untracked <= untracked_paths
    assert expected_modified.isdisjoint(untracked_paths)
    assert expected_untracked.isdisjoint(modified_paths)
    assert all(
        not any(token in path for token in ("*", "?", "[", "]", "\\"))
        for path in expected_modified | expected_untracked
    )


def test_mid_round_intake_resume_continues_only_uncommitted_ordinals(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    script = ROOT / ".local-dev" / "five-round-intake-api-uat.py"
    spec = importlib.util.spec_from_file_location("five_round_mid_resume_test", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)

    values = {
        module.RESUME_BOUNDARY_ENV: module.INITIATOR_WAITING_PARTY_MID_ROUNDS,
        module.RESUME_CASE_ID_ENV: "CASE_MID_ROUND",
        module.RESUME_INITIATOR_ID_ENV: "user-mid-round",
        module.RESUME_RESPONDENT_ID_ENV: "merchant-mid-round",
        module.RESUME_OPERATION_ID_ENV: "mid-round-op-v1",
        module.RESUME_EXPECTED_PHASE_ENV: "WAITING_PARTY",
        module.RESUME_EXPECTED_DOSSIER_VERSION_ENV: "2",
        module.RESUME_EXPECTED_SOURCE_TURN_ENV: "2",
        module.RESUME_EXPECTED_MATRIX_ID_ENV: "CASE_MATRIX_MID_ROUND",
        module.RESUME_EXPECTED_MATRIX_VERSION_ENV: "2",
        module.RESUME_EXPECTED_MATRIX_HASH_ENV: "a" * 64,
        module.RESUME_CONTINUATION_TEXT_ENV: "冻结的第三轮恢复文本，且不是内建夹具。",
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)

    configuration = module.load_resume_configuration(5)
    assert configuration is not None
    assert configuration.expected_matrix_version == 2

    monkeypatch.setenv(
        module.RESUME_BOUNDARY_ENV,
        module.INITIATOR_WAITING_PARTY_BEFORE_RESPONDENT,
    )
    with pytest.raises(module.uat.UatFailure):
        module.load_resume_configuration(5)
    for name in (
        module.RESUME_EXPECTED_DOSSIER_VERSION_ENV,
        module.RESUME_EXPECTED_SOURCE_TURN_ENV,
        module.RESUME_EXPECTED_MATRIX_VERSION_ENV,
    ):
        monkeypatch.setenv(name, "5")
    assert module.load_resume_configuration(5) is not None

    monkeypatch.setenv(
        module.RESUME_BOUNDARY_ENV,
        module.INITIATOR_WAITING_PARTY_MID_ROUNDS,
    )
    for name in (
        module.RESUME_EXPECTED_DOSSIER_VERSION_ENV,
        module.RESUME_EXPECTED_SOURCE_TURN_ENV,
        module.RESUME_EXPECTED_MATRIX_VERSION_ENV,
    ):
        monkeypatch.setenv(name, "2")
    configuration = module.load_resume_configuration(5)
    assert configuration is not None

    matrices = {
        version: module.MatrixState(
            f"matrix-{version}",
            version,
            f"{version}" * 64,
            "INITIATOR_FROZEN",
            None,
            None,
            None,
        )
        for version in range(2, 6)
    }
    preflight = module.ResumePreflight(
        matrix=matrices[2],
        room_id="room-mid-round",
        message_ids=frozenset({"message-1", "message-2"}),
        run_ids=frozenset({"run-1", "run-2"}),
        committed_texts=frozenset(),
    )
    posted_ordinals: list[int] = []
    formal_versions: list[int] = []
    readiness: list[tuple[int, int]] = []

    class Timings:
        def measure(self, _name: str, operation: Any) -> Any:
            return operation()

    monkeypatch.setattr(module, "require_resume_preflight", lambda *_: preflight)
    monkeypatch.setattr(
        module,
        "post_resume_continuation",
        lambda *_: ("message-3", "run-3"),
    )
    monkeypatch.setattr(module, "observe_intake_agent_run", lambda *_: object())
    monkeypatch.setattr(module, "record_parallel_intake_timing", lambda *_: None)

    def post_party_text(
        _context: Any, _stage: str, ordinal: int, rounds_per_party: int
    ) -> str:
        assert rounds_per_party == 5
        posted_ordinals.append(ordinal)
        return f"run-{ordinal}"

    def wait_for_formal_turn(
        _context: Any,
        _stage: str,
        _run_id: str,
        *,
        expected_version: int,
        expected_dossier_version: int,
        previous: Any,
    ) -> Any:
        assert expected_dossier_version == expected_version
        assert previous.version == expected_version - 1
        formal_versions.append(expected_version)
        return matrices[expected_version]

    monkeypatch.setattr(module, "post_party_text", post_party_text)
    monkeypatch.setattr(module, "wait_for_formal_turn", wait_for_formal_turn)
    monkeypatch.setattr(
        module,
        "require_semantic_ready",
        lambda _context,
        _stage,
        *,
        expected_dossier_version,
        expected_source_turn_no: readiness.append(
            (expected_dossier_version, expected_source_turn_no)
        ),
    )

    result = module.execute_resume_waiting_party(
        Timings(), object(), configuration, 5
    )

    assert posted_ordinals == [4, 5]
    assert formal_versions == [3, 4, 5]
    assert readiness == [(5, 5)]
    assert result["resume_new_agent_run_ids"] == ["run-3", "run-4", "run-5"]
    assert result["initiator_rounds"] == 5
    assert result["final_matrix_version"] == 5
    assert result["stopped_after"] == "INITIATOR_READY_TO_CONFIRM"


def test_five_round_v3_observer_accepts_room_utterance_without_ordered_sections(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from types import SimpleNamespace

    script = ROOT / ".local-dev" / "five-round-intake-api-uat.py"
    spec = importlib.util.spec_from_file_location("five_round_v3_observer_test", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)

    run_id = "target-intake-run:v3-visible-only"
    attempt_id = "target-intake-attempt:v3-visible-only:1"
    events = [
        {
            "protocol": "agent-stream.v3",
            "run_id": run_id,
            "attempt_id": attempt_id,
            "attempt_no": 1,
            "sequence": 0,
            "type": "attempt_started",
        },
        {
            "protocol": "agent-stream.v3",
            "run_id": run_id,
            "attempt_id": attempt_id,
            "attempt_no": 1,
            "sequence": 1,
            "type": "visible_delta",
            "field": "room_utterance",
            "delta": "商家您好，请说明立场。",
        },
        {
            "protocol": "agent-stream.v3",
            "run_id": run_id,
            "attempt_id": attempt_id,
            "attempt_no": 1,
            "sequence": 2,
            "type": "usage",
            "token_usage": {
                "input_tokens": 10,
                "output_tokens": 5,
                "total_tokens": 15,
            },
        },
        {
            "protocol": "agent-stream.v3",
            "run_id": run_id,
            "attempt_id": attempt_id,
            "attempt_no": 1,
            "sequence": 3,
            "type": "final",
            "response": {
                "final_result_ref": "urn:after-sale-flow:graph-result:" + "a" * 64,
                "final_result_hash": "a" * 64,
            },
        },
    ]
    monkeypatch.setattr(
        module,
        "read_timed_evidence_sse_events",
        lambda *_: SimpleNamespace(events=events),
    )
    monkeypatch.setattr(module.uat, "read_replay_events", lambda *_: list(events))

    observation = module.observe_intake_agent_run(
        object(), "v3_visible_only", run_id, 0.0
    )
    assert observation.protocol == "agent-stream.v3"

    events[1] = {**events[1], "field": "private_trace"}
    with pytest.raises(module.uat.UatFailure):
        module.observe_intake_agent_run(
            object(), "v3_visible_only_negative", run_id, 0.0
        )


def test_java_api_stopped_start_requires_explicit_operator_authority() -> None:
    script = JAVA_API_RESTART.read_text(encoding="utf-8-sig")

    assert script.count("[switch]$AllowStartIfStopped") == 1
    assert "if ($listeners.Count -gt 1)" in script
    stopped_guard = script.index("if ($listeners.Count -eq 0)")
    explicit_guard = script.index("if (-not $AllowStartIfStopped)", stopped_guard)
    start_index = script.index("$javaApi = Start-Process", explicit_guard)
    owned_listener_branch = script.index("} else {", explicit_guard)
    stop_index = script.index("Stop-Process -Id $oldPid -Force", owned_listener_branch)

    assert stopped_guard < explicit_guard < owned_listener_branch < stop_index < start_index
    assert "Java API is stopped; use -AllowStartIfStopped" in script
    assert (
        "TARGET_E2E_INTAKE_EXECUTION_PROVIDER_ID = "
        "[string]$env:DEFAULT_LLM_PROVIDER"
    ) in script


def test_current_workers_can_start_if_stopped_without_replacing_recovery_workers() -> None:
    control = JAVA_CONTROL_RESTART.read_text(encoding="utf-8-sig")
    agent = JAVA_AGENT_RESTART.read_text(encoding="utf-8-sig")

    assert control.count("[switch]$AllowStartIfStopped") == 1
    assert agent.count("[switch]$AllowStartIfStopped") == 1
    assert control.count('if ($PSVersionTable.PSEdition -ne "Core")') == 1
    assert agent.count('if ($PSVersionTable.PSEdition -ne "Core")') == 1
    assert control.index('if ($PSVersionTable.PSEdition -ne "Core")') < control.index(
        "$setupSource ="
    )
    assert agent.index('if ($PSVersionTable.PSEdition -ne "Core")') < agent.index(
        "$setupSource ="
    )
    assert '& $pwsh @forwardArguments' in control
    assert '& $pwsh @forwardArguments' in agent
    assert "$targetState.control_build_id" in control
    assert "$targetState.agent_build_id" in agent
    assert "CASE_PROCESS_RECOVERY_ONLY" in control
    assert "CASE_PROCESS_INTAKE_CONTINUATION_ONLY" in control
    assert "A conflicting normal CONTROL worker already exists." in control
    assert "A conflicting AGENT worker already exists." in agent

    control_start_only = control.index("if ($AllowStartIfStopped)")
    control_stop = control.index("Stop-Process -Id $process.ProcessId -Force")
    control_start = control.index("$controlWorker = Start-Process")
    agent_start_only = agent.index("if ($AllowStartIfStopped)")
    agent_stop = agent.index("Stop-Process -Id $process.ProcessId -Force")
    agent_start = agent.index("$agentWorker = Start-Process")

    assert control_start_only < control_stop < control_start
    assert agent_start_only < agent_stop < agent_start
    assert 'disposition = "ALREADY_RUNNING"' in control
    assert 'disposition = "ALREADY_RUNNING"' in agent
