from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / ".local-dev" / "launch-source.ps1"
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
    $index = [Math]::Min($script:snapshotCallCount, $case.snapshots.Count - 1)
    $script:snapshotCallCount += 1
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
        "Stop-SourceJavaProbeProcessExact",
        "Wait-SourceJavaProbeCleanupProof",
        "Get-SourceJavaHomeFromProbeResult",
        "Resolve-SourceJavaExecutable",
    )
    assert set(required) <= set(definitions)
    runner = definitions[required[0]]
    cleanup = definitions[required[1]]
    cleanup_loop = definitions[required[2]]
    parser = definitions[required[3]]
    resolver = definitions[required[4]]
    combined = "\n".join((runner, cleanup, cleanup_loop, parser, resolver))
    assert "UseShellExecute = $false" in runner
    assert "RedirectStandardOutput = $true" in runner
    assert "RedirectStandardError = $true" in runner
    assert "CreateNoWindow = $true" in runner
    assert runner.count("ReadToEndAsync()") == 2
    assert "Get-SourceProcessOwnershipHandleExitState" in runner
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
        ("throw", "not-read", "PROCESS_TERMINATION_FAILED"),
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
