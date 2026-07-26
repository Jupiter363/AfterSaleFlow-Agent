from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Mapping, Sequence, TypeVar

ROOT = Path(__file__).resolve().parents[3]
SCHEMA_PATH = (
    ROOT / "contracts/agent-platform/phase8/engineering-candidate-scope.schema.json"
)
ACCEPTED_A8 = "3c60bf5cc4e051a214e158cbf944fd6aba969f95"
SCHEMA_VERSION = "phase8-engineering-candidate-scope.v1"
SELF_PATH = "contracts/agent-platform/phase8/engineering-candidate-scope.json"
AUTHORITY_CEILING = "PHASE_8_ENGINEERING_CANDIDATE_SCOPE_ONLY"
V047_PATH = (
    "java-api-service/src/main/resources/db/migration/"
    "V047__remove_legacy_orchestration.sql"
)

MAX_VALIDATION_SECONDS = 120.0
MAX_MANIFEST_BYTES = 256 * 1024
MAX_SCHEMA_BYTES = 64 * 1024
MAX_JSON_DEPTH = 8
MAX_JSON_NODES = 4096
MAX_ANCESTOR_COMMITS = 2048
MAX_TREE_DEPTH = 64
MAX_TREE_ENTRIES = 100_000
MAX_CHANGED_PATHS = 512
MAX_OBJECTS = 200_000
MAX_COMMIT_BYTES = 1024 * 1024
MAX_TREE_OBJECT_BYTES = 16 * 1024 * 1024
MAX_SINGLE_BLOB_BYTES = 128 * 1024 * 1024
MAX_TOTAL_SNAPSHOT_BYTES = 512 * 1024 * 1024
MAX_TOTAL_GIT_OUTPUT_BYTES = 576 * 1024 * 1024
MAX_CONTROL_COMMAND_OUTPUT_BYTES = 64 * 1024
MAX_BATCH_HEADER_BYTES = 256
MAX_MATERIALIZATION_TOTAL_BYTES = 1024 * 1024 * 1024
MAX_MATERIALIZATION_MANIFEST_BYTES = 64 * 1024 * 1024
EXPECTED_SCHEMA_SHA256 = (
    "d1ec3cffc00ee5213cbf3ccf573fa966052ffdc875c55781dc37cbc7fba1012d"
)
FULL_REPOSITORY = "FULL_REPOSITORY"
JAVA_SERVICE_ONLY = "JAVA_SERVICE_ONLY"
JAVA_SERVICE_PREFIX = "java-api-service/"
TRUSTED_CODE_TO_WORKFLOW_PATHS = (
    ".github/workflows/phase8-engineering-witness.yml",
    "tests/static/test_phase8_engineering_witness_workflow.py",
)
TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS = (
    ".github/workflows/phase8-engineering-caller.yml",
    SELF_PATH,
)

SHA1 = re.compile(r"^[0-9a-f]{40}$")
BATCH_HEADER = re.compile(rb"^([0-9a-f]{40}) (commit|tree|blob) ([0-9]{1,12})\n$")
V047_COMPONENT = re.compile(r"^V047(?:__|\.|$)", re.IGNORECASE)
WINDOWS_DEVICE_COMPONENT = re.compile(
    r"^(?:(?:CON|PRN|AUX|NUL|CONIN\$|CONOUT\$)|"
    r"(?:COM|LPT)[1-9\u00b9\u00b2\u00b3])(?:\..*)?$",
    re.IGNORECASE,
)


class CandidateScopeValidationError(ValueError):
    """Raised when a Phase 8 candidate exceeds its declared exact scope."""


def _resolve_git_executable() -> Path:
    discovered = shutil.which("git")
    if not discovered:
        raise CandidateScopeValidationError("the fixed Git executable is unavailable")
    executable = Path(discovered).resolve(strict=True)
    if not executable.is_absolute() or not executable.is_file():
        raise CandidateScopeValidationError(
            "the fixed Git executable is not a regular file"
        )
    return executable


def _file_identity(path: Path) -> tuple[int, int, int, int]:
    metadata = path.stat()
    return (metadata.st_dev, metadata.st_ino, metadata.st_size, metadata.st_mtime_ns)


def _stat_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        getattr(metadata, "st_file_attributes", 0),
    )


GIT_EXECUTABLE = _resolve_git_executable()
GIT_EXECUTABLE_IDENTITY = _file_identity(GIT_EXECUTABLE)


@dataclass(frozen=True)
class _Deadline:
    expires_at: float

    @classmethod
    def start(cls) -> _Deadline:
        if MAX_VALIDATION_SECONDS <= 0:
            raise CandidateScopeValidationError("validation deadline is exhausted")
        return cls(time.monotonic() + MAX_VALIDATION_SECONDS)

    def remaining(self) -> float:
        remaining = self.expires_at - time.monotonic()
        if remaining <= 0:
            raise CandidateScopeValidationError("validation deadline exceeded")
        return remaining

    def check(self) -> None:
        self.remaining()


@dataclass(frozen=True)
class _GitResult:
    returncode: int
    stdout: bytes
    stderr: bytes = b""


@dataclass(frozen=True)
class _ObjectEnvelope:
    object_id: str
    object_type: str
    raw: bytes
    output_bytes: int


@dataclass(frozen=True)
class _ObjectRecord:
    object_id: str
    object_type: str
    raw: bytes
    sha256: str


@dataclass(frozen=True)
class _CommitRecord:
    tree: str
    parents: tuple[str, ...]


@dataclass(frozen=True)
class _TreeEntry:
    mode: str
    name: str
    object_id: str
    is_tree: bool


@dataclass(frozen=True)
class _FileFact:
    object_id: str
    mode: str
    size: int
    sha256: str


@dataclass
class _WalkBudget:
    entries: int = 0
    active_trees: set[str] = field(default_factory=set)


_T = TypeVar("_T")


class _ImmutableDict(dict[str, Any]):
    """JSON-compatible mapping that rejects mutation at every public boundary."""

    @staticmethod
    def _reject_mutation(*_args: Any, **_kwargs: Any) -> None:
        raise TypeError("trusted transition projection is immutable")

    __delitem__ = _reject_mutation
    __ior__ = _reject_mutation
    __setitem__ = _reject_mutation
    clear = _reject_mutation
    pop = _reject_mutation
    popitem = _reject_mutation
    setdefault = _reject_mutation
    update = _reject_mutation

    def copy(self) -> _ImmutableDict:
        return self


def _deep_immutable(value: Any) -> Any:
    if isinstance(value, Mapping):
        return _ImmutableDict(
            {
                key: _deep_immutable(item)
                for key, item in sorted(value.items(), key=lambda pair: pair[0])
            }
        )
    if isinstance(value, (list, tuple)):
        return tuple(_deep_immutable(item) for item in value)
    return value


def canonical_json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as exception:
        raise CandidateScopeValidationError(
            f"derived candidate scope is not canonical JSON: {exception}"
        ) from exception


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def _minimal_git_environment() -> dict[str, str]:
    environment: dict[str, str] = {}
    for key in ("SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP"):
        value = os.environ.get(key)
        if value:
            environment[key] = value
    environment.update(
        {
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "NUL" if os.name == "nt" else "/dev/null",
            "GIT_NO_LAZY_FETCH": "1",
            "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_TERMINAL_PROMPT": "0",
            "LANG": "C",
            "LC_ALL": "C",
        }
    )
    return environment


def _fixed_git_command(*arguments: str) -> list[str]:
    return [
        str(GIT_EXECUTABLE),
        "--no-replace-objects",
        "-c",
        "protocol.allow=never",
        "-c",
        "core.quotePath=false",
        "-c",
        "core.ignoreCase=false",
        *arguments,
    ]


def _assert_git_executable_unchanged() -> None:
    try:
        identity = _file_identity(GIT_EXECUTABLE)
    except OSError as exception:
        raise CandidateScopeValidationError(
            "fixed Git executable is unavailable"
        ) from exception
    if identity != GIT_EXECUTABLE_IDENTITY:
        raise CandidateScopeValidationError("fixed Git executable identity changed")


def _bounded_thread_call(
    callback: Callable[[], _T],
    *,
    deadline: _Deadline,
    on_timeout: Callable[[], None],
    context: str,
) -> _T:
    completed = threading.Event()
    result: list[_T] = []
    error: list[BaseException] = []

    def invoke() -> None:
        try:
            result.append(callback())
        except BaseException as exception:  # transported to the validation thread
            error.append(exception)
        finally:
            completed.set()

    thread = threading.Thread(target=invoke, name="phase8-scope-io", daemon=True)
    thread.start()
    if not completed.wait(deadline.remaining()):
        on_timeout()
        raise CandidateScopeValidationError(
            f"validation deadline exceeded during {context}"
        )
    if error:
        raise CandidateScopeValidationError(
            f"bounded I/O failed during {context}"
        ) from error[0]
    if not result:
        raise CandidateScopeValidationError(
            f"bounded I/O returned no result during {context}"
        )
    return result[0]


def _run_git(arguments: Sequence[str], deadline: _Deadline) -> _GitResult:
    if not arguments or any(not isinstance(argument, str) for argument in arguments):
        raise CandidateScopeValidationError("Git arguments must be fixed strings")
    _assert_git_executable_unchanged()
    command = _fixed_git_command(*arguments)
    try:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=_minimal_git_environment(),
            shell=False,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    except OSError as exception:
        raise CandidateScopeValidationError(
            "fixed Git operation failed to execute"
        ) from exception
    if process.stdout is None:
        process.kill()
        raise CandidateScopeValidationError("fixed Git output pipe is unavailable")

    def stop() -> None:
        if process.poll() is None:
            process.kill()

    try:
        output = _bounded_thread_call(
            lambda: process.stdout.read(MAX_CONTROL_COMMAND_OUTPUT_BYTES + 1),
            deadline=deadline,
            on_timeout=stop,
            context="fixed Git command output",
        )
        if len(output) > MAX_CONTROL_COMMAND_OUTPUT_BYTES:
            stop()
            raise CandidateScopeValidationError(
                "fixed Git command output exceeds its limit"
            )
        try:
            returncode = process.wait(timeout=deadline.remaining())
        except subprocess.TimeoutExpired as exception:
            stop()
            raise CandidateScopeValidationError(
                "validation deadline exceeded during Git"
            ) from exception
    finally:
        stop()
        process.stdout.close()
    return _GitResult(returncode, output)


def _git_bytes(deadline: _Deadline, *arguments: str) -> bytes:
    result = _run_git(arguments, deadline)
    if result.returncode != 0:
        detail = result.stdout.decode("utf-8", errors="replace").strip()
        raise CandidateScopeValidationError(
            f"fixed Git operation {' '.join(arguments)} failed: {detail}"
        )
    return result.stdout


def _git_text(deadline: _Deadline, *arguments: str) -> str:
    try:
        return _git_bytes(deadline, *arguments).decode("utf-8", errors="strict").strip()
    except UnicodeDecodeError as exception:
        raise CandidateScopeValidationError(
            "Git metadata is not strict UTF-8"
        ) from exception


class _BatchObjectSource:
    def __init__(self, deadline: _Deadline) -> None:
        deadline.check()
        _assert_git_executable_unchanged()
        command = _fixed_git_command("cat-file", "--batch")
        try:
            self._process = subprocess.Popen(
                command,
                cwd=ROOT,
                env=_minimal_git_environment(),
                shell=False,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
        except OSError as exception:
            raise CandidateScopeValidationError(
                "fixed Git object snapshot could not start"
            ) from exception
        if self._process.stdin is None or self._process.stdout is None:
            self._kill()
            raise CandidateScopeValidationError("fixed Git batch pipes are unavailable")
        self._deadline = deadline
        self._closed = False
        self._executor = ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="phase8-scope-batch"
        )

    def __enter__(self) -> _BatchObjectSource:
        return self

    def __exit__(self, error_type: object, _value: object, _traceback: object) -> None:
        if error_type is None:
            self.close()
        else:
            self.abort()

    def _kill(self) -> None:
        if self._process.poll() is None:
            self._process.kill()

    def _io(self, callback: Callable[[], _T], context: str) -> _T:
        future = self._executor.submit(callback)
        try:
            return future.result(timeout=self._deadline.remaining())
        except FutureTimeoutError as exception:
            self._kill()
            raise CandidateScopeValidationError(
                f"validation deadline exceeded during {context}"
            ) from exception
        except CandidateScopeValidationError:
            raise
        except BaseException as exception:
            raise CandidateScopeValidationError(
                f"bounded I/O failed during {context}"
            ) from exception

    def _read_exact(self, size: int) -> bytes:
        def read() -> bytes:
            chunks: list[bytes] = []
            remaining = size
            while remaining:
                chunk = self._process.stdout.read(remaining)
                if not chunk:
                    break
                chunks.append(chunk)
                remaining -= len(chunk)
            return b"".join(chunks)

        payload = self._io(read, "Git batch object body")
        if len(payload) != size:
            self._kill()
            raise CandidateScopeValidationError("Git batch object body was truncated")
        return payload

    def read_object(self, object_id: str) -> _ObjectEnvelope:
        if self._closed:
            raise CandidateScopeValidationError("Git object snapshot source is closed")
        requested = _assert_sha1(object_id, "requested Git object")

        def request() -> None:
            self._process.stdin.write(requested.encode("ascii") + b"\n")
            self._process.stdin.flush()

        self._io(request, "Git batch object request")
        header = self._io(
            lambda: self._process.stdout.readline(MAX_BATCH_HEADER_BYTES + 1),
            "Git batch object header",
        )
        if not header or len(header) > MAX_BATCH_HEADER_BYTES:
            self._kill()
            raise CandidateScopeValidationError(
                "Git batch object header is out of bounds"
            )
        match = BATCH_HEADER.fullmatch(header)
        if match is None:
            self._kill()
            raise CandidateScopeValidationError("Git batch object header is malformed")
        returned = match.group(1).decode("ascii")
        object_type = match.group(2).decode("ascii")
        size = int(match.group(3))
        if returned != requested:
            self._kill()
            raise CandidateScopeValidationError(
                "Git batch returned a mixed object identity"
            )
        size_limit = {
            "commit": MAX_COMMIT_BYTES,
            "tree": MAX_TREE_OBJECT_BYTES,
            "blob": MAX_SINGLE_BLOB_BYTES,
        }[object_type]
        if size > size_limit:
            self._kill()
            raise CandidateScopeValidationError(
                f"Git {object_type} object exceeds its byte limit"
            )
        raw = self._read_exact(size)
        delimiter = self._read_exact(1)
        if delimiter != b"\n":
            self._kill()
            raise CandidateScopeValidationError(
                "Git batch object delimiter is malformed"
            )
        return _ObjectEnvelope(returned, object_type, raw, len(header) + size + 1)

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            if self._process.stdin is not None:
                try:
                    self._process.stdin.close()
                except OSError as exception:
                    self._kill()
                    raise CandidateScopeValidationError(
                        "Git object snapshot input closed unexpectedly"
                    ) from exception
            try:
                returncode = self._process.wait(timeout=self._deadline.remaining())
            except (CandidateScopeValidationError, subprocess.TimeoutExpired):
                self._kill()
                raise CandidateScopeValidationError(
                    "Git object snapshot did not close before the deadline"
                )
            if returncode != 0:
                raise CandidateScopeValidationError(
                    "Git object snapshot process failed"
                )
        finally:
            self._kill()
            if self._process.stdout is not None:
                self._process.stdout.close()
            self._executor.shutdown(wait=False, cancel_futures=True)

    def abort(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self._kill()
            if self._process.stdin is not None:
                try:
                    self._process.stdin.close()
                except OSError:
                    pass
            try:
                self._process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self._kill()
        finally:
            if self._process.stdout is not None:
                self._process.stdout.close()
            self._executor.shutdown(wait=False, cancel_futures=True)


def _open_object_source(deadline: _Deadline) -> _BatchObjectSource:
    return _BatchObjectSource(deadline)


class _ObjectSnapshot:
    def __init__(self, source: Any, deadline: _Deadline) -> None:
        self._source = source
        self._deadline = deadline
        self.records: dict[str, _ObjectRecord] = {}
        self.total_raw_bytes = 0
        self.total_output_bytes = 0

    def get(self, object_id: str, expected_type: str) -> _ObjectRecord:
        self._deadline.check()
        object_id = _assert_sha1(object_id, f"{expected_type} object")
        cached = self.records.get(object_id)
        if cached is not None:
            if cached.object_type != expected_type:
                raise CandidateScopeValidationError(
                    "Git object type changed within snapshot"
                )
            return cached
        if len(self.records) >= MAX_OBJECTS:
            raise CandidateScopeValidationError(
                "Git object snapshot exceeds its object limit"
            )
        envelope = self._source.read_object(object_id)
        if envelope.object_id != object_id or envelope.object_type != expected_type:
            raise CandidateScopeValidationError(
                "Git object snapshot returned mixed identity/type"
            )
        raw = envelope.raw
        if not isinstance(raw, bytes):
            raise CandidateScopeValidationError(
                "Git object snapshot payload must be bytes"
            )
        size_limit = {
            "commit": MAX_COMMIT_BYTES,
            "tree": MAX_TREE_OBJECT_BYTES,
            "blob": MAX_SINGLE_BLOB_BYTES,
        }[expected_type]
        if len(raw) > size_limit:
            raise CandidateScopeValidationError(
                f"Git {expected_type} object exceeds its byte limit"
            )
        self.total_raw_bytes += len(raw)
        self.total_output_bytes += envelope.output_bytes
        if self.total_raw_bytes > MAX_TOTAL_SNAPSHOT_BYTES:
            raise CandidateScopeValidationError(
                "Git object snapshot exceeds its total byte limit"
            )
        if self.total_output_bytes > MAX_TOTAL_GIT_OUTPUT_BYTES:
            raise CandidateScopeValidationError(
                "Git object output exceeds its total byte limit"
            )
        if _git_object_sha1(expected_type, raw) != object_id:
            raise CandidateScopeValidationError(
                f"raw Git {expected_type} object substitution detected"
            )
        self._deadline.check()
        record = _ObjectRecord(
            object_id=object_id,
            object_type=expected_type,
            raw=raw,
            sha256=hashlib.sha256(raw).hexdigest(),
        )
        self.records[object_id] = record
        return record


def _assert_sha1(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA1.fullmatch(value):
        raise CandidateScopeValidationError(
            f"{context} must be an exact lowercase 40-character Git SHA"
        )
    return value


def _validate_component(value: str, context: str) -> str:
    if not value or len(value.encode("utf-8")) > 255:
        raise CandidateScopeValidationError(f"{context} is not a bounded Git component")
    if unicodedata.normalize("NFC", value) != value:
        raise CandidateScopeValidationError(f"{context} is not NFC-normalized")
    if (
        "/" in value
        or "\\" in value
        or ":" in value
        or "\x00" in value
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        raise CandidateScopeValidationError(
            f"{context} contains an alias or control byte"
        )
    if (
        value in {".", ".."}
        or value.endswith((" ", "."))
        or value.casefold() == ".git"
        or WINDOWS_DEVICE_COMPONENT.fullmatch(value)
    ):
        raise CandidateScopeValidationError(f"{context} has a filesystem alias")
    return value


def _relative_git_path(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 512:
        raise CandidateScopeValidationError(f"{context} is not a bounded relative path")
    if unicodedata.normalize("NFC", value) != value:
        raise CandidateScopeValidationError(f"{context} is not NFC-normalized")
    pure = PurePosixPath(value)
    if pure.is_absolute() or pure.as_posix() != value:
        raise CandidateScopeValidationError(f"{context} is not canonical")
    for component in pure.parts:
        _validate_component(component, context)
    return value


def _windows_component_key(value: str) -> str:
    return unicodedata.normalize("NFC", value).casefold()


def _reject_duplicate_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    document: dict[str, Any] = {}
    for key, value in pairs:
        if key in document:
            raise CandidateScopeValidationError(f"duplicate JSON key rejected: {key!r}")
        document[key] = value
    return document


def _reject_float(token: str) -> None:
    raise CandidateScopeValidationError(f"JSON floating-point number rejected: {token}")


def _reject_constant(token: str) -> None:
    raise CandidateScopeValidationError(f"non-finite JSON number rejected: {token}")


def _assert_bounded_json_tree(value: Any) -> None:
    nodes = 0
    stack = [(value, 1)]
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > MAX_JSON_NODES:
            raise CandidateScopeValidationError(
                "scope manifest exceeds the JSON node limit"
            )
        if depth > MAX_JSON_DEPTH:
            raise CandidateScopeValidationError(
                "scope manifest exceeds the JSON depth limit"
            )
        if isinstance(current, dict):
            stack.extend((child, depth + 1) for child in current.values())
        elif isinstance(current, list):
            stack.extend((child, depth + 1) for child in current)


def _parse_manifest(raw: bytes) -> dict[str, Any]:
    if not isinstance(raw, bytes):
        raise CandidateScopeValidationError("scope manifest input must be bytes")
    if not raw or len(raw) > MAX_MANIFEST_BYTES:
        raise CandidateScopeValidationError(
            "scope manifest byte length is out of bounds"
        )
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise CandidateScopeValidationError("scope manifest must be BOM-free UTF-8")
    try:
        document = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_reject_duplicate_object_pairs,
            parse_constant=_reject_constant,
            parse_float=_reject_float,
        )
    except CandidateScopeValidationError:
        raise
    except (RecursionError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise CandidateScopeValidationError(
            "scope manifest is not strict UTF-8 JSON"
        ) from exception
    if not isinstance(document, dict):
        raise CandidateScopeValidationError("scope manifest root must be an object")
    _assert_bounded_json_tree(document)
    return document


def _is_link_or_reparse(metadata: os.stat_result) -> bool:
    return stat.S_ISLNK(metadata.st_mode) or bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def _read_schema_snapshot(deadline: _Deadline) -> bytes:
    try:
        before = os.lstat(SCHEMA_PATH)
        if (
            not stat.S_ISREG(before.st_mode)
            or _is_link_or_reparse(before)
            or before.st_nlink != 1
            or before.st_size <= 0
            or before.st_size > MAX_SCHEMA_BYTES
        ):
            raise CandidateScopeValidationError(
                "candidate scope schema must be a bounded single-link regular file"
            )
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(SCHEMA_PATH, flags)
    except CandidateScopeValidationError:
        raise
    except OSError as exception:
        raise CandidateScopeValidationError(
            "candidate scope schema is unavailable"
        ) from exception
    try:
        opened_before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened_before.st_mode)
            or opened_before.st_nlink != 1
            or _stat_identity(opened_before) != _stat_identity(before)
        ):
            raise CandidateScopeValidationError(
                "candidate scope schema identity changed before read"
            )
        chunks: list[bytes] = []
        remaining = MAX_SCHEMA_BYTES + 1
        while remaining:
            deadline.check()
            chunk = os.read(descriptor, min(64 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        opened_after = os.fstat(descriptor)
        if _stat_identity(opened_after) != _stat_identity(opened_before):
            raise CandidateScopeValidationError(
                "candidate scope schema changed during read"
            )
    except OSError as exception:
        raise CandidateScopeValidationError(
            "candidate scope schema read failed"
        ) from exception
    finally:
        os.close(descriptor)
    try:
        after = os.lstat(SCHEMA_PATH)
    except OSError as exception:
        raise CandidateScopeValidationError(
            "candidate scope schema disappeared after read"
        ) from exception
    if _is_link_or_reparse(after) or _stat_identity(after) != _stat_identity(before):
        raise CandidateScopeValidationError(
            "candidate scope schema path identity changed after read"
        )
    payload = b"".join(chunks)
    if not payload or len(payload) > MAX_SCHEMA_BYTES:
        raise CandidateScopeValidationError(
            "candidate scope schema byte length is out of bounds"
        )
    return payload


def _verify_schema_contract(deadline: _Deadline) -> None:
    payload = _read_schema_snapshot(deadline)
    if hashlib.sha256(payload).hexdigest() != EXPECTED_SCHEMA_SHA256:
        raise CandidateScopeValidationError(
            "candidate scope schema does not match the frozen documentation contract"
        )


def _require_exact_keys(value: Any, expected: set[str], context: str) -> dict[str, Any]:
    if type(value) is not dict:
        raise CandidateScopeValidationError(f"{context} must be an exact JSON object")
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise CandidateScopeValidationError(
            f"{context} keys drifted (missing={missing}, extra={extra})"
        )
    return value


def _assert_manifest_contract(document: dict[str, Any]) -> None:
    root = _require_exact_keys(
        document,
        {
            "accepted_entry_sha",
            "allowed_changes",
            "authority",
            "phase",
            "schema_version",
            "self_path",
        },
        "scope manifest root",
    )
    exact_scalars: tuple[tuple[str, type[Any], Any], ...] = (
        ("schema_version", str, SCHEMA_VERSION),
        ("phase", int, 8),
        ("accepted_entry_sha", str, ACCEPTED_A8),
        ("self_path", str, SELF_PATH),
    )
    for key, expected_type, expected_value in exact_scalars:
        value = root[key]
        if type(value) is not expected_type or value != expected_value:
            raise CandidateScopeValidationError(
                f"scope manifest {key} type or constant drifted"
            )
    authority = _require_exact_keys(
        root["authority"],
        {
            "authority_ceiling",
            "engineering_checkpoint_granted",
            "production_access",
            "production_actions",
            "production_checkpoint_granted",
            "production_credentials",
            "production_traffic",
            "promotion_granted",
        },
        "scope manifest authority",
    )
    if (
        type(authority["authority_ceiling"]) is not str
        or authority["authority_ceiling"] != AUTHORITY_CEILING
    ):
        raise CandidateScopeValidationError(
            "scope manifest authority ceiling type or constant drifted"
        )
    for key in (
        "engineering_checkpoint_granted",
        "production_access",
        "production_actions",
        "production_checkpoint_granted",
        "production_credentials",
        "production_traffic",
        "promotion_granted",
    ):
        if authority[key] is not False:
            raise CandidateScopeValidationError(
                f"scope manifest authority {key} must be exact false"
            )
    changes = root["allowed_changes"]
    if type(changes) is not list or not 1 <= len(changes) <= MAX_CHANGED_PATHS:
        raise CandidateScopeValidationError(
            "scope manifest allowed_changes type or length drifted"
        )
    for index, change_value in enumerate(changes):
        change = _require_exact_keys(
            change_value,
            {"path", "status"},
            f"scope manifest allowed_changes[{index}]",
        )
        if type(change["path"]) is not str:
            raise CandidateScopeValidationError(
                f"scope manifest allowed_changes[{index}].path must be a string"
            )
        if type(change["status"]) is not str or change["status"] not in {"A", "M"}:
            raise CandidateScopeValidationError(
                f"scope manifest allowed_changes[{index}].status drifted"
            )


def _validate_manifest(raw: bytes, deadline: _Deadline) -> dict[str, Any]:
    deadline.check()
    _verify_schema_contract(deadline)
    document = _parse_manifest(raw)
    _assert_manifest_contract(document)
    deadline.check()
    changes = document["allowed_changes"]
    seen: dict[str, str] = {}
    for index, change in enumerate(changes):
        path = _relative_git_path(change["path"], f"allowed_changes[{index}].path")
        folded = path.casefold()
        if path in seen.values() or folded in seen:
            raise CandidateScopeValidationError(
                f"allowed change path is duplicated: {path}"
            )
        if V047_COMPONENT.match(PurePosixPath(path).name):
            raise CandidateScopeValidationError(
                "V047 is forbidden in a Phase 8 candidate"
            )
        seen[folded] = path
    self_changes = [
        change for change in changes if change["path"] == document["self_path"]
    ]
    if len(self_changes) != 1:
        raise CandidateScopeValidationError(
            "scope manifest must include its self_path once"
        )
    if self_changes[0]["status"] != "A":
        raise CandidateScopeValidationError(
            "scope manifest self_path must be newly added"
        )
    return document


def _assert_no_object_substitution(deadline: _Deadline) -> None:
    deadline.check()
    forbidden_environment = (
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_COMMON_DIR",
        "GIT_OBJECT_DIRECTORY",
        "GIT_REPLACE_REF_BASE",
    )
    present = [key for key in forbidden_environment if os.environ.get(key)]
    if present:
        raise CandidateScopeValidationError(
            "Git object substitution environment is forbidden: " + ", ".join(present)
        )
    if _git_text(deadline, "rev-parse", "--show-object-format") != "sha1":
        raise CandidateScopeValidationError(
            "candidate repository must use Git SHA-1 objects"
        )
    replacements = _git_text(
        deadline, "for-each-ref", "--count=2", "--format=%(refname)", "refs/replace"
    ).splitlines()
    if replacements:
        raise CandidateScopeValidationError("Git replace refs are forbidden")
    roots = {
        _git_text(deadline, "rev-parse", "--path-format=absolute", "--git-dir"),
        _git_text(deadline, "rev-parse", "--path-format=absolute", "--git-common-dir"),
    }
    for raw_root in roots:
        root = Path(raw_root)
        if not root.is_absolute():
            raise CandidateScopeValidationError("Git metadata root is not absolute")
        for relative in (Path("info/grafts"), Path("objects/info/alternates")):
            path = root / relative
            if path.exists() or path.is_symlink():
                raise CandidateScopeValidationError(
                    f"Git graft/alternate state is forbidden: {path}"
                )
    deadline.check()


def _git_object_sha1(kind: str, payload: bytes) -> str:
    header = f"{kind} {len(payload)}\0".encode("ascii")
    return hashlib.sha1(header + payload).hexdigest()


def _parse_commit(raw: bytes, deadline: _Deadline) -> _CommitRecord:
    header, separator, _message = raw.partition(b"\n\n")
    if not separator or b"\x00" in header:
        raise CandidateScopeValidationError("raw Git commit object is malformed")
    lines = header.split(b"\n")
    if any(any(byte < 32 or byte == 127 for byte in line) for line in lines):
        raise CandidateScopeValidationError(
            "raw Git commit header contains CR or control bytes"
        )
    if not lines or len(lines[0]) != 45 or not lines[0].startswith(b"tree "):
        raise CandidateScopeValidationError("raw Git commit has no leading tree header")
    try:
        tree = lines[0][5:].decode("ascii", errors="strict")
    except UnicodeDecodeError as exception:
        raise CandidateScopeValidationError(
            "raw Git commit tree SHA is malformed"
        ) from exception
    parents: list[str] = []
    index = 1
    while index < len(lines) and lines[index].startswith(b"parent "):
        line = lines[index]
        if len(line) != 47:
            raise CandidateScopeValidationError(
                "raw Git commit parent SHA is malformed"
            )
        try:
            parents.append(line[7:].decode("ascii", errors="strict"))
        except UnicodeDecodeError as exception:
            raise CandidateScopeValidationError(
                "raw Git commit parent SHA is malformed"
            ) from exception
        index += 1
    for remaining_index, line in enumerate(lines[index:], start=index):
        if remaining_index % 256 == 0:
            deadline.check()
        if line.startswith((b"tree ", b"parent ")):
            raise CandidateScopeValidationError(
                "raw Git commit contains a misplaced tree or parent header"
            )
    return _CommitRecord(
        tree=_assert_sha1(tree, "commit tree"),
        parents=tuple(_assert_sha1(parent, "commit parent") for parent in parents),
    )


def _capture_linear_history(
    snapshot: _ObjectSnapshot, candidate: str
) -> tuple[str, str, str]:
    if candidate == ACCEPTED_A8:
        raise CandidateScopeValidationError("candidate must be newer than accepted A8")
    current = candidate
    visited: set[str] = set()
    candidate_tree = ""
    candidate_parent = ""
    commits_after_a8 = 0
    while True:
        snapshot._deadline.check()
        if current in visited:
            raise CandidateScopeValidationError(
                "candidate commit history contains a cycle"
            )
        visited.add(current)
        commit = _parse_commit(snapshot.get(current, "commit").raw, snapshot._deadline)
        if current == ACCEPTED_A8:
            return candidate_tree, candidate_parent, commit.tree
        commits_after_a8 += 1
        if commits_after_a8 > MAX_ANCESTOR_COMMITS:
            raise CandidateScopeValidationError(
                "candidate history exceeds its commit limit"
            )
        if len(commit.parents) != 1:
            raise CandidateScopeValidationError(
                "candidate history must be linear and single-parent after A8"
            )
        if current == candidate:
            candidate_tree = commit.tree
            candidate_parent = commit.parents[0]
        current = commit.parents[0]
        if not current:
            raise CandidateScopeValidationError(
                "candidate is not descended from accepted exact A8"
            )


def _parse_tree(raw: bytes, deadline: _Deadline) -> list[_TreeEntry]:
    if not raw:
        raise CandidateScopeValidationError("empty Git tree objects are forbidden")
    entries: list[_TreeEntry] = []
    cursor = 0
    previous_sort_key: bytes | None = None
    aliases: dict[str, tuple[str, bool]] = {}
    while cursor < len(raw):
        if len(entries) % 256 == 0:
            deadline.check()
        space = raw.find(b" ", cursor)
        nul = raw.find(b"\x00", space + 1) if space >= 0 else -1
        if space <= cursor or nul <= space + 1 or nul + 21 > len(raw):
            raise CandidateScopeValidationError("raw Git tree entry is truncated")
        mode_raw = raw[cursor:space]
        name_raw = raw[space + 1 : nul]
        object_raw = raw[nul + 1 : nul + 21]
        cursor = nul + 21
        try:
            mode = mode_raw.decode("ascii", errors="strict")
            name = name_raw.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exception:
            raise CandidateScopeValidationError(
                "raw Git tree metadata is not strict UTF-8/ASCII"
            ) from exception
        name = _validate_component(name, "raw Git tree component")
        if mode == "40000":
            is_tree = True
        elif mode in {"100644", "100755"}:
            is_tree = False
        else:
            raise CandidateScopeValidationError(
                f"raw Git tree rejects symlink, submodule, or non-blob mode: {mode} {name}"
            )
        sort_key = name_raw + (b"/" if is_tree else b"")
        if previous_sort_key is not None and sort_key <= previous_sort_key:
            raise CandidateScopeValidationError(
                "raw Git tree entries are duplicate or unsorted"
            )
        previous_sort_key = sort_key
        alias_key = _windows_component_key(name)
        previous = aliases.get(alias_key)
        if previous is not None:
            kind = "directory" if is_tree else "file"
            previous_kind = "directory" if previous[1] else "file"
            raise CandidateScopeValidationError(
                f"raw Git tree component collision: {previous[0]} ({previous_kind}) / {name} ({kind})"
            )
        aliases[alias_key] = (name, is_tree)
        entries.append(
            _TreeEntry(
                mode=mode,
                name=name,
                object_id=object_raw.hex(),
                is_tree=is_tree,
            )
        )
    if cursor != len(raw):
        raise CandidateScopeValidationError(
            "raw Git tree contains hidden trailing bytes"
        )
    return entries


def _walk_tree(
    snapshot: _ObjectSnapshot,
    tree_id: str,
    *,
    prefix: tuple[str, ...],
    depth: int,
    budget: _WalkBudget,
    files: dict[str, _FileFact],
    path_aliases: dict[str, str],
) -> None:
    snapshot._deadline.check()
    if depth > MAX_TREE_DEPTH:
        raise CandidateScopeValidationError("candidate tree exceeds its depth limit")
    if tree_id in budget.active_trees:
        raise CandidateScopeValidationError("candidate tree graph contains a cycle")
    budget.active_trees.add(tree_id)
    try:
        entries = _parse_tree(snapshot.get(tree_id, "tree").raw, snapshot._deadline)
        for entry in entries:
            budget.entries += 1
            if budget.entries > MAX_TREE_ENTRIES:
                raise CandidateScopeValidationError(
                    "candidate tree exceeds its entry limit"
                )
            components = (*prefix, entry.name)
            path = _relative_git_path("/".join(components), "authenticated tree path")
            alias = "/".join(_windows_component_key(part) for part in components)
            previous = path_aliases.get(alias)
            if previous is not None and previous != path:
                raise CandidateScopeValidationError(
                    f"authenticated tree path collision: {previous} / {path}"
                )
            path_aliases[alias] = path
            if entry.is_tree:
                _walk_tree(
                    snapshot,
                    entry.object_id,
                    prefix=components,
                    depth=depth + 1,
                    budget=budget,
                    files=files,
                    path_aliases=path_aliases,
                )
                continue
            if path in files:
                raise CandidateScopeValidationError(
                    f"authenticated tree duplicates path: {path}"
                )
            blob = snapshot.get(entry.object_id, "blob")
            files[path] = _FileFact(
                object_id=entry.object_id,
                mode=entry.mode,
                size=len(blob.raw),
                sha256=blob.sha256,
            )
    finally:
        budget.active_trees.remove(tree_id)


def _tree_files(
    snapshot: _ObjectSnapshot,
    tree_id: str,
    *,
    budget: _WalkBudget | None = None,
) -> dict[str, _FileFact]:
    files: dict[str, _FileFact] = {}
    _walk_tree(
        snapshot,
        tree_id,
        prefix=(),
        depth=0,
        budget=budget if budget is not None else _WalkBudget(),
        files=files,
        path_aliases={},
    )
    if not files:
        raise CandidateScopeValidationError("authenticated tree contains no files")
    if V047_PATH in files:
        raise CandidateScopeValidationError("V047 is forbidden in a Phase 8 candidate")
    for index, path in enumerate(files):
        if index % 1024 == 0:
            snapshot._deadline.check()
        if V047_COMPONENT.match(PurePosixPath(path).name):
            raise CandidateScopeValidationError(
                "V047 is forbidden in a Phase 8 candidate"
            )
    return files


def _derive_changes(
    base: Mapping[str, _FileFact],
    candidate: Mapping[str, _FileFact],
    deadline: _Deadline,
) -> list[dict[str, str]]:
    removed = sorted(
        set(base).difference(candidate), key=lambda path: path.encode("utf-8")
    )
    if removed:
        raise CandidateScopeValidationError(
            f"candidate contains destructive or renamed path: {removed[0]}"
        )
    changed: list[dict[str, str]] = []
    for index, path in enumerate(
        sorted(candidate, key=lambda item: item.encode("utf-8"))
    ):
        if index % 1024 == 0:
            deadline.check()
        before = base.get(path)
        after = candidate[path]
        if before is None:
            status = "A"
        elif (before.mode, before.object_id) != (after.mode, after.object_id):
            status = "M"
        else:
            continue
        changed.append({"path": path, "status": status})
        if len(changed) > MAX_CHANGED_PATHS:
            raise CandidateScopeValidationError(
                "candidate changed-path count exceeds its limit"
            )
    if not changed:
        raise CandidateScopeValidationError("candidate contains no changed paths")
    return changed


def _derive_inventory(
    changed: list[dict[str, str]],
    candidate: Mapping[str, _FileFact],
    deadline: _Deadline,
) -> list[dict[str, Any]]:
    derived: list[dict[str, Any]] = []
    for change in changed:
        deadline.check()
        fact = candidate[change["path"]]
        derived.append(
            {
                "bytes": fact.size,
                "git_blob_sha": fact.object_id,
                "mode": fact.mode,
                "path": change["path"],
                "sha256": fact.sha256,
                "status": change["status"],
            }
        )
    return derived


def _require_exact_additions(
    base: Mapping[str, _FileFact],
    target: Mapping[str, _FileFact],
    expected_paths: tuple[str, ...],
    *,
    context: str,
    deadline: _Deadline,
) -> list[dict[str, Any]]:
    changed_paths: list[str] = []
    for index, path in enumerate(
        sorted(set(base).union(target), key=lambda item: item.encode("utf-8"))
    ):
        if index % 1024 == 0:
            deadline.check()
        before = base.get(path)
        after = target.get(path)
        if before is None or after is None:
            changed_paths.append(path)
        elif (before.mode, before.object_id) != (after.mode, after.object_id):
            changed_paths.append(path)
        if len(changed_paths) > len(expected_paths):
            break

    expected = list(expected_paths)
    if changed_paths != expected:
        raise CandidateScopeValidationError(
            f"{context} must contain exactly the fixed added paths "
            f"(expected={expected}, actual={changed_paths})"
        )

    additions: list[dict[str, Any]] = []
    for path in expected_paths:
        deadline.check()
        if path in base:
            raise CandidateScopeValidationError(
                f"{context} path must be newly added, not modified: {path}"
            )
        fact = target.get(path)
        if fact is None:
            raise CandidateScopeValidationError(
                f"{context} required addition is absent: {path}"
            )
        if fact.mode != "100644":
            raise CandidateScopeValidationError(
                f"{context} addition must use mode 100644: {path}"
            )
        additions.append(
            {
                "bytes": fact.size,
                "git_blob_sha": fact.object_id,
                "mode": fact.mode,
                "path": path,
                "sha256": fact.sha256,
                "status": "A",
            }
        )
    return additions


def _materialization_manifest_sha256(
    inventory_kind: str,
    entries: list[dict[str, Any]],
    *,
    file_count: int,
    total_bytes: int,
    deadline: _Deadline,
) -> str:
    hasher = hashlib.sha256()
    canonical_bytes = 0

    def append(chunk: bytes) -> None:
        nonlocal canonical_bytes
        canonical_bytes += len(chunk)
        if canonical_bytes > MAX_MATERIALIZATION_MANIFEST_BYTES:
            raise CandidateScopeValidationError(
                f"{inventory_kind} materialization manifest exceeds its byte limit"
            )
        hasher.update(chunk)

    append(b'{"entries":[')
    for index, entry in enumerate(entries):
        deadline.check()
        if index:
            append(b",")
        append(canonical_json_bytes(entry))
    append(b'],"file_count":')
    append(str(file_count).encode("ascii"))
    append(b',"inventory_kind":')
    append(canonical_json_bytes(inventory_kind))
    append(b',"total_bytes":')
    append(str(total_bytes).encode("ascii"))
    append(b"}")
    deadline.check()
    return hasher.hexdigest()


def _derive_materialization_inventory(
    inventory_kind: str,
    candidate: Mapping[str, _FileFact],
    deadline: _Deadline,
) -> dict[str, Any]:
    if inventory_kind not in {FULL_REPOSITORY, JAVA_SERVICE_ONLY}:
        raise CandidateScopeValidationError(
            "materialization inventory kind is not fixed"
        )
    entries: list[dict[str, Any]] = []
    total_bytes = 0
    for index, path in enumerate(
        sorted(candidate, key=lambda item: item.encode("utf-8"))
    ):
        if index % 256 == 0:
            deadline.check()
        if inventory_kind == JAVA_SERVICE_ONLY and not path.startswith(
            JAVA_SERVICE_PREFIX
        ):
            continue
        fact = candidate[path]
        total_bytes += fact.size
        if total_bytes > MAX_MATERIALIZATION_TOTAL_BYTES:
            raise CandidateScopeValidationError(
                f"{inventory_kind} materialization exceeds its total byte limit"
            )
        entries.append(
            {
                "git_blob_sha": fact.object_id,
                "mode": fact.mode,
                "path": path,
                "sha256": fact.sha256,
                "size": fact.size,
                "type": "blob",
            }
        )
        if len(entries) > MAX_TREE_ENTRIES:
            raise CandidateScopeValidationError(
                f"{inventory_kind} materialization exceeds its entry limit"
            )
    file_count = len(entries)
    manifest_sha256 = _materialization_manifest_sha256(
        inventory_kind,
        entries,
        file_count=file_count,
        total_bytes=total_bytes,
        deadline=deadline,
    )
    deadline.check()
    return {
        "entries": entries,
        "file_count": file_count,
        "manifest_sha256": manifest_sha256,
        "total_bytes": total_bytes,
    }


def validate_trusted_transition(
    *,
    candidate_sha: str,
    trusted_code_sha: str,
    trusted_workflow_sha: str,
) -> tuple[Mapping[str, Any], str]:
    """Authenticate the exact C0 -> W0 -> Ceng trust-root transition."""

    deadline = _Deadline.start()
    candidate = _assert_sha1(candidate_sha, "engineering candidate commit")
    trusted_code = _assert_sha1(trusted_code_sha, "trusted code commit")
    trusted_workflow = _assert_sha1(trusted_workflow_sha, "trusted workflow commit")
    _assert_no_object_substitution(deadline)
    with _open_object_source(deadline) as source:
        snapshot = _ObjectSnapshot(source, deadline)
        code_commit = _parse_commit(snapshot.get(trusted_code, "commit").raw, deadline)
        workflow_commit = _parse_commit(
            snapshot.get(trusted_workflow, "commit").raw, deadline
        )
        candidate_commit = _parse_commit(
            snapshot.get(candidate, "commit").raw, deadline
        )
        if workflow_commit.parents != (trusted_code,):
            raise CandidateScopeValidationError(
                "trusted workflow commit must have the exact trusted code commit "
                "as its sole parent"
            )
        if candidate_commit.parents != (trusted_workflow,):
            raise CandidateScopeValidationError(
                "engineering candidate commit must have the exact trusted workflow "
                "commit as its sole parent"
            )

        tree_budget = _WalkBudget()
        code_files = _tree_files(snapshot, code_commit.tree, budget=tree_budget)
        workflow_files = _tree_files(snapshot, workflow_commit.tree, budget=tree_budget)
        candidate_files = _tree_files(
            snapshot, candidate_commit.tree, budget=tree_budget
        )
        workflow_additions = _require_exact_additions(
            code_files,
            workflow_files,
            TRUSTED_CODE_TO_WORKFLOW_PATHS,
            context="trusted code to trusted workflow transition",
            deadline=deadline,
        )
        candidate_additions = _require_exact_additions(
            workflow_files,
            candidate_files,
            TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS,
            context="trusted workflow to engineering candidate transition",
            deadline=deadline,
        )
        projection = {
            "candidate_sha": candidate,
            "candidate_tree_sha": candidate_commit.tree,
            "trusted_code_sha": trusted_code,
            "trusted_code_to_workflow_additions": workflow_additions,
            "trusted_code_tree_sha": code_commit.tree,
            "trusted_workflow_sha": trusted_workflow,
            "trusted_workflow_to_candidate_additions": candidate_additions,
            "trusted_workflow_tree_sha": workflow_commit.tree,
        }
    _assert_no_object_substitution(deadline)
    deadline.check()
    immutable_projection = _deep_immutable(projection)
    return immutable_projection, canonical_sha256(immutable_projection)


def validate(candidate_sha: str, manifest_bytes: bytes) -> dict[str, Any]:
    """Validate and derive the exact, engineering-only Phase 8 candidate scope."""

    deadline = _Deadline.start()
    candidate = _assert_sha1(candidate_sha, "candidate commit")
    manifest = _validate_manifest(manifest_bytes, deadline)
    _assert_no_object_substitution(deadline)
    with _open_object_source(deadline) as source:
        snapshot = _ObjectSnapshot(source, deadline)
        candidate_tree, candidate_parent, base_tree = _capture_linear_history(
            snapshot, candidate
        )
        tree_budget = _WalkBudget()
        base_files = _tree_files(snapshot, base_tree, budget=tree_budget)
        candidate_files = _tree_files(snapshot, candidate_tree, budget=tree_budget)
        changed = _derive_changes(base_files, candidate_files, deadline)
        if manifest["allowed_changes"] != changed:
            raise CandidateScopeValidationError(
                "declared allowed_changes do not exactly match the ordered A8..candidate diff"
            )
        self_fact = candidate_files.get(SELF_PATH)
        if self_fact is None:
            raise CandidateScopeValidationError(
                "scope manifest self_path is absent from candidate tree"
            )
        self_record = snapshot.records[self_fact.object_id]
        if self_record.raw != manifest_bytes:
            raise CandidateScopeValidationError(
                "scope manifest bytes do not match the candidate self_path blob"
            )
        derived = _derive_inventory(changed, candidate_files, deadline)
        materialization_inventories = {
            FULL_REPOSITORY: _derive_materialization_inventory(
                FULL_REPOSITORY, candidate_files, deadline
            ),
            JAVA_SERVICE_ONLY: _derive_materialization_inventory(
                JAVA_SERVICE_ONLY, candidate_files, deadline
            ),
        }
    _assert_no_object_substitution(deadline)
    deadline.check()
    return {
        "accepted_entry_sha": ACCEPTED_A8,
        "allowed_changes": changed,
        "authority_ceiling": AUTHORITY_CEILING,
        "candidate_parent_sha": candidate_parent,
        "candidate_sha": candidate,
        "candidate_tree_sha": candidate_tree,
        "derived_inventory": derived,
        "derived_inventory_sha256": canonical_sha256(derived),
        "materialization_inventories": materialization_inventories,
        "production_authority": False,
        "self_path": SELF_PATH,
    }


__all__ = [
    "ACCEPTED_A8",
    "AUTHORITY_CEILING",
    "CandidateScopeValidationError",
    "SCHEMA_PATH",
    "SCHEMA_VERSION",
    "SELF_PATH",
    "TRUSTED_CODE_TO_WORKFLOW_PATHS",
    "TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS",
    "canonical_json_bytes",
    "canonical_sha256",
    "validate",
    "validate_trusted_transition",
]
