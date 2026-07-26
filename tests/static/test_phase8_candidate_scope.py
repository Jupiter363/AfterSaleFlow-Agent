from __future__ import annotations

import builtins
import copy
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Any

import pytest

from scripts.phase8.candidate import candidate_scope as scope


OTHER_PATH = "scripts/phase8/candidate/candidate_scope.py"


def _authority() -> dict[str, object]:
    return {
        "authority_ceiling": scope.AUTHORITY_CEILING,
        "engineering_checkpoint_granted": False,
        "production_access": False,
        "production_actions": False,
        "production_checkpoint_granted": False,
        "production_credentials": False,
        "production_traffic": False,
        "promotion_granted": False,
    }


def _manifest(
    accepted: str,
    changes: list[dict[str, str]] | None = None,
) -> dict[str, object]:
    return {
        "schema_version": scope.SCHEMA_VERSION,
        "phase": 8,
        "accepted_entry_sha": accepted,
        "self_path": scope.SELF_PATH,
        "authority": _authority(),
        "allowed_changes": changes
        if changes is not None
        else [{"path": scope.SELF_PATH, "status": "A"}],
    }


def _json_bytes(document: object) -> bytes:
    return json.dumps(
        document, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")


def _git_sha(kind: str, raw: bytes) -> str:
    return hashlib.sha1(f"{kind} {len(raw)}\0".encode("ascii") + raw).hexdigest()


class _GraphBuilder:
    def __init__(self) -> None:
        self.objects: dict[str, tuple[str, bytes]] = {}

    def add(self, object_type: str, raw: bytes) -> str:
        object_id = _git_sha(object_type, raw)
        self.objects[object_id] = (object_type, raw)
        return object_id

    def blob(self, raw: bytes) -> str:
        return self.add("blob", raw)

    def tree(self, entries: list[tuple[str, str, str]], *, sort: bool = True) -> str:
        if sort:
            entries = sorted(
                entries,
                key=lambda entry: (
                    entry[1].encode("utf-8") + (b"/" if entry[0] == "40000" else b"")
                ),
            )
        raw = b"".join(
            mode.encode("ascii")
            + b" "
            + name.encode("utf-8")
            + b"\0"
            + bytes.fromhex(object_id)
            for mode, name, object_id in entries
        )
        return self.add("tree", raw)

    def tree_from_files(self, files: dict[str, tuple[str, bytes]]) -> str:
        root: dict[str, Any] = {}
        for path, leaf in files.items():
            components = path.split("/")
            node = root
            for component in components[:-1]:
                child = node.setdefault(component, {})
                if not isinstance(child, dict):
                    raise AssertionError("fixture contains a file/directory conflict")
                node = child
            if components[-1] in node:
                raise AssertionError("fixture path is duplicated")
            node[components[-1]] = leaf

        def materialize(node: dict[str, Any]) -> str:
            entries: list[tuple[str, str, str]] = []
            for name, value in node.items():
                if isinstance(value, dict):
                    entries.append(("40000", name, materialize(value)))
                else:
                    mode, raw = value
                    entries.append((mode, name, self.blob(raw)))
            return self.tree(entries)

        return materialize(root)

    def commit(self, tree: str, parents: tuple[str, ...]) -> str:
        headers = [f"tree {tree}", *(f"parent {parent}" for parent in parents)]
        raw = "\n".join(
            [
                *headers,
                "author Phase Eight <phase8@example.invalid> 0 +0000",
                "committer Phase Eight <phase8@example.invalid> 0 +0000",
                "",
                "candidate",
                "",
            ]
        ).encode("ascii")
        return self.add("commit", raw)


class _FakeObjectSource:
    def __init__(self, objects: dict[str, tuple[str, bytes]]) -> None:
        self.objects = objects
        self.reads: Counter[str] = Counter()
        self.raw_overrides: dict[str, bytes] = {}
        self.type_overrides: dict[str, str] = {}
        self.identity_overrides: dict[str, str] = {}
        self.extra_output_bytes = 0
        self.closed = False

    def __enter__(self) -> _FakeObjectSource:
        return self

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        self.closed = True

    def read_object(self, object_id: str) -> scope._ObjectEnvelope:
        self.reads[object_id] += 1
        object_type, raw = self.objects[object_id]
        raw = self.raw_overrides.get(object_id, raw)
        object_type = self.type_overrides.get(object_id, object_type)
        returned = self.identity_overrides.get(object_id, object_id)
        return scope._ObjectEnvelope(
            returned,
            object_type,
            raw,
            len(raw) + 64 + self.extra_output_bytes,
        )


class _Fixture:
    def __init__(
        self,
        *,
        changes: list[dict[str, str]] | None = None,
        base_extra: dict[str, tuple[str, bytes]] | None = None,
        candidate_extra: dict[str, tuple[str, bytes]] | None = None,
        remove: tuple[str, ...] = (),
        intermediate_commits: int = 0,
        merge_candidate: bool = False,
    ) -> None:
        self.builder = _GraphBuilder()
        base_files = {"README.md": ("100644", b"trusted baseline")}
        base_files.update(base_extra or {})
        self.base_tree = self.builder.tree_from_files(base_files)
        self.base = self.builder.commit(self.base_tree, ())
        self.document = _manifest(self.base, changes)
        self.manifest_bytes = _json_bytes(self.document)
        candidate_files = dict(base_files)
        for path in remove:
            candidate_files.pop(path)
        candidate_files[scope.SELF_PATH] = ("100644", self.manifest_bytes)
        candidate_files.update(candidate_extra or {})
        self.candidate_tree = self.builder.tree_from_files(candidate_files)
        parent = self.base
        for _ in range(intermediate_commits):
            parent = self.builder.commit(self.base_tree, (parent,))
        parents = (parent, "9" * 40) if merge_candidate else (parent,)
        self.candidate = self.builder.commit(self.candidate_tree, parents)
        self.source = _FakeObjectSource(self.builder.objects)

    def replace_candidate_tree(self, raw: bytes) -> None:
        self.candidate_tree = self.builder.add("tree", raw)
        candidate_type, candidate_raw = self.builder.objects[self.candidate]
        assert candidate_type == "commit"
        parents = tuple(
            line.removeprefix(b"parent ").decode("ascii")
            for line in candidate_raw.splitlines()
            if line.startswith(b"parent ")
        )
        self.candidate = self.builder.commit(self.candidate_tree, parents)
        self.source.objects = self.builder.objects


class _ControlGit:
    def __init__(self, git_root: Path) -> None:
        self.git_root = git_root
        self.calls: list[tuple[str, ...]] = []
        self.replace_on_check: int | None = None
        self.replace_checks = 0
        self.output_override: bytes | None = None

    def run(
        self, arguments: tuple[str, ...], _deadline: scope._Deadline
    ) -> scope._GitResult:
        self.calls.append(arguments)
        if self.output_override is not None:
            return scope._GitResult(0, self.output_override)
        if arguments == ("rev-parse", "--show-object-format"):
            return scope._GitResult(0, b"sha1\n")
        if arguments == (
            "for-each-ref",
            "--count=2",
            "--format=%(refname)",
            "refs/replace",
        ):
            self.replace_checks += 1
            if self.replace_on_check == self.replace_checks:
                return scope._GitResult(
                    0, b"refs/replace/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
                )
            return scope._GitResult(0, b"")
        if arguments in {
            ("rev-parse", "--path-format=absolute", "--git-dir"),
            ("rev-parse", "--path-format=absolute", "--git-common-dir"),
        }:
            return scope._GitResult(0, str(self.git_root).encode("utf-8") + b"\n")
        raise AssertionError(f"unexpected control Git invocation: {arguments!r}")


def _install(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    fixture: _Fixture,
) -> _ControlGit:
    git_root = tmp_path / "git-metadata"
    git_root.mkdir(parents=True)
    control = _ControlGit(git_root)
    monkeypatch.setattr(scope, "ACCEPTED_A8", fixture.base)
    monkeypatch.setattr(scope, "_run_git", control.run)
    monkeypatch.setattr(scope, "_open_object_source", lambda _deadline: fixture.source)
    return control


class _TrustedTransitionFixture:
    def __init__(
        self,
        *,
        code_extra: dict[str, tuple[str, bytes]] | None = None,
        workflow_extra: dict[str, tuple[str, bytes]] | None = None,
        candidate_extra: dict[str, tuple[str, bytes]] | None = None,
        workflow_remove: tuple[str, ...] = (),
        candidate_remove: tuple[str, ...] = (),
        workflow_merge: bool = False,
        candidate_merge: bool = False,
    ) -> None:
        self.builder = _GraphBuilder()
        code_files = {"README.md": ("100644", b"trusted code\n")}
        code_files.update(code_extra or {})
        self.code_tree = self.builder.tree_from_files(code_files)
        self.trusted_code = self.builder.commit(self.code_tree, ())

        workflow_files = dict(code_files)
        for path in workflow_remove:
            workflow_files.pop(path)
        workflow_files.update(
            {
                scope.TRUSTED_CODE_TO_WORKFLOW_PATHS[0]: (
                    "100644",
                    b"name: reusable witness\n",
                ),
                scope.TRUSTED_CODE_TO_WORKFLOW_PATHS[1]: (
                    "100644",
                    b"def test_workflow(): pass\n",
                ),
            }
        )
        workflow_files.update(workflow_extra or {})
        self.workflow_tree = self.builder.tree_from_files(workflow_files)
        workflow_parents = (
            (self.trusted_code, "8" * 40) if workflow_merge else (self.trusted_code,)
        )
        self.trusted_workflow = self.builder.commit(
            self.workflow_tree, workflow_parents
        )

        candidate_files = dict(workflow_files)
        for path in candidate_remove:
            candidate_files.pop(path)
        candidate_files.update(
            {
                scope.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS[0]: (
                    "100644",
                    b"name: caller\n",
                ),
                scope.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS[1]: (
                    "100644",
                    b'{"phase":8}\n',
                ),
            }
        )
        candidate_files.update(candidate_extra or {})
        self.candidate_tree = self.builder.tree_from_files(candidate_files)
        candidate_parents = (
            (self.trusted_workflow, "7" * 40)
            if candidate_merge
            else (self.trusted_workflow,)
        )
        self.candidate = self.builder.commit(self.candidate_tree, candidate_parents)
        self.source = _FakeObjectSource(self.builder.objects)


def _install_transition(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    fixture: _TrustedTransitionFixture,
) -> _ControlGit:
    git_root = tmp_path / "git-metadata"
    git_root.mkdir(parents=True)
    control = _ControlGit(git_root)
    monkeypatch.setattr(scope, "_run_git", control.run)
    monkeypatch.setattr(scope, "_open_object_source", lambda _deadline: fixture.source)
    return control


def _validate_transition(
    fixture: _TrustedTransitionFixture,
) -> tuple[Any, str]:
    return scope.validate_trusted_transition(
        candidate_sha=fixture.candidate,
        trusted_code_sha=fixture.trusted_code,
        trusted_workflow_sha=fixture.trusted_workflow,
    )


def test_trusted_transition_is_exact_immutable_and_canonically_hashed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _TrustedTransitionFixture()
    control = _install_transition(monkeypatch, tmp_path, fixture)

    projection, projection_sha256 = _validate_transition(fixture)

    assert set(projection) == {
        "candidate_sha",
        "candidate_tree_sha",
        "trusted_code_sha",
        "trusted_code_to_workflow_additions",
        "trusted_code_tree_sha",
        "trusted_workflow_sha",
        "trusted_workflow_to_candidate_additions",
        "trusted_workflow_tree_sha",
    }
    assert projection["candidate_sha"] == fixture.candidate
    assert projection["candidate_tree_sha"] == fixture.candidate_tree
    assert projection["trusted_code_sha"] == fixture.trusted_code
    assert projection["trusted_code_tree_sha"] == fixture.code_tree
    assert projection["trusted_workflow_sha"] == fixture.trusted_workflow
    assert projection["trusted_workflow_tree_sha"] == fixture.workflow_tree
    assert [
        item["path"] for item in projection["trusted_code_to_workflow_additions"]
    ] == list(scope.TRUSTED_CODE_TO_WORKFLOW_PATHS)
    assert [
        item["path"] for item in projection["trusted_workflow_to_candidate_additions"]
    ] == list(scope.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS)
    assert all(
        item["mode"] == "100644" and item["status"] == "A"
        for key in (
            "trusted_code_to_workflow_additions",
            "trusted_workflow_to_candidate_additions",
        )
        for item in projection[key]
    )
    assert projection_sha256 == scope.canonical_sha256(projection)
    with pytest.raises(TypeError, match="immutable"):
        projection["candidate_sha"] = "0" * 40
    with pytest.raises(TypeError, match="immutable"):
        projection["trusted_code_to_workflow_additions"][0]["mode"] = "100755"
    assert fixture.source.closed
    assert all(count == 1 for count in fixture.source.reads.values())
    assert not any(
        call[0] in {"cat-file", "diff", "ls-tree", "rev-list"} for call in control.calls
    )
    assert control.replace_checks == 2


@pytest.mark.parametrize(
    "edge",
    ("workflow-merge", "candidate-merge", "candidate-wrong-parent"),
)
def test_trusted_transition_rejects_merge_and_wrong_parent_graphs(
    edge: str, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _TrustedTransitionFixture(
        workflow_merge=edge == "workflow-merge",
        candidate_merge=edge == "candidate-merge",
    )
    if edge == "candidate-wrong-parent":
        fixture.candidate = fixture.builder.commit(
            fixture.candidate_tree, (fixture.trusted_code,)
        )
        fixture.source.objects = fixture.builder.objects
    _install_transition(monkeypatch, tmp_path, fixture)

    with pytest.raises(scope.CandidateScopeValidationError, match="sole parent"):
        _validate_transition(fixture)


@pytest.mark.parametrize(
    ("edge", "mutation", "error"),
    (
        ("workflow", "extra", "exactly the fixed added paths"),
        ("workflow", "modified", "exactly the fixed added paths"),
        ("workflow", "deleted", "exactly the fixed added paths"),
        ("workflow", "preexisting", "newly added"),
        ("candidate", "extra", "exactly the fixed added paths"),
        ("candidate", "modified", "exactly the fixed added paths"),
        ("candidate", "deleted", "exactly the fixed added paths"),
        ("candidate", "preexisting", "exactly the fixed added paths"),
    ),
)
def test_trusted_transition_is_closed_world_add_only(
    edge: str,
    mutation: str,
    error: str,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    kwargs: dict[str, object] = {}
    if mutation == "extra":
        kwargs[f"{edge}_extra"] = {"plans/forbidden.md": ("100644", b"extra")}
    elif mutation == "modified":
        kwargs[f"{edge}_extra"] = {"README.md": ("100644", b"modified")}
    elif mutation == "deleted":
        kwargs[f"{edge}_remove"] = ("README.md",)
    else:
        path = (
            scope.TRUSTED_CODE_TO_WORKFLOW_PATHS[0]
            if edge == "workflow"
            else scope.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS[0]
        )
        owner = "code_extra" if edge == "workflow" else "workflow_extra"
        kwargs[owner] = {path: ("100644", b"preexisting")}
    fixture = _TrustedTransitionFixture(**kwargs)
    _install_transition(monkeypatch, tmp_path, fixture)

    with pytest.raises(scope.CandidateScopeValidationError, match=error):
        _validate_transition(fixture)


@pytest.mark.parametrize("edge", ("workflow", "candidate"))
def test_trusted_transition_requires_regular_non_executable_additions(
    edge: str, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    path = (
        scope.TRUSTED_CODE_TO_WORKFLOW_PATHS[0]
        if edge == "workflow"
        else scope.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS[0]
    )
    fixture = _TrustedTransitionFixture(
        **{f"{edge}_extra": {path: ("100755", b"executable")}}
    )
    _install_transition(monkeypatch, tmp_path, fixture)

    with pytest.raises(scope.CandidateScopeValidationError, match="mode 100644"):
        _validate_transition(fixture)


def test_trusted_transition_reads_an_exact_real_git_graph(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    repository = tmp_path / "transition-repository"

    def git(*arguments: str) -> str:
        completed = subprocess.run(
            [str(scope.GIT_EXECUTABLE), *arguments],
            cwd=repository,
            shell=False,
            check=True,
            capture_output=True,
        )
        return completed.stdout.decode("ascii").strip()

    repository.mkdir()
    git("init", "-q")
    git("config", "user.email", "phase8@example.invalid")
    git("config", "user.name", "Phase Eight")
    (repository / "README.md").write_bytes(b"trusted code\n")
    git("add", "README.md")
    git("commit", "-q", "-m", "trusted code")
    trusted_code = git("rev-parse", "HEAD")

    for path in scope.TRUSTED_CODE_TO_WORKFLOW_PATHS:
        target = repository / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(f"trusted workflow: {path}\n".encode("ascii"))
    git("add", *scope.TRUSTED_CODE_TO_WORKFLOW_PATHS)
    git("commit", "-q", "-m", "trusted workflow")
    trusted_workflow = git("rev-parse", "HEAD")

    for path in scope.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS:
        target = repository / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(f"engineering candidate: {path}\n".encode("ascii"))
    git("add", *scope.TRUSTED_WORKFLOW_TO_CANDIDATE_PATHS)
    git("commit", "-q", "-m", "engineering candidate")
    candidate = git("rev-parse", "HEAD")

    monkeypatch.setattr(scope, "ROOT", repository)
    transition, transition_sha256 = scope.validate_trusted_transition(
        candidate_sha=candidate,
        trusted_code_sha=trusted_code,
        trusted_workflow_sha=trusted_workflow,
    )

    assert transition["trusted_code_sha"] == trusted_code
    assert transition["trusted_workflow_sha"] == trusted_workflow
    assert transition["candidate_sha"] == candidate
    assert transition_sha256 == scope.canonical_sha256(transition)
    assert git("rev-list", "--parents", "--max-count=2", candidate).splitlines() == [
        f"{candidate} {trusted_workflow}",
        f"{trusted_workflow} {trusted_code}",
    ]


def test_schema_is_strict_and_manifest_has_no_candidate_or_self_hash() -> None:
    raw_schema = scope.SCHEMA_PATH.read_bytes()
    schema = json.loads(raw_schema.decode("utf-8"))
    assert hashlib.sha256(raw_schema).hexdigest() == scope.EXPECTED_SCHEMA_SHA256
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    assert schema["additionalProperties"] is False
    serialized = _json_bytes(_manifest(scope.ACCEPTED_A8))
    assert b"candidate_sha" not in serialized
    assert b"self_hash" not in serialized
    assert b"sha256" not in serialized


def test_module_validates_when_jsonschema_import_is_blocked(tmp_path: Path) -> None:
    module_name = "phase8_candidate_scope_without_jsonschema"
    spec = importlib.util.spec_from_file_location(module_name, scope.__file__)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    fixture = _Fixture()
    git_root = tmp_path / "git-metadata"
    git_root.mkdir()
    control = _ControlGit(git_root)
    original_import = builtins.__import__

    def block_jsonschema(
        name: str,
        globals: dict[str, object] | None = None,
        locals: dict[str, object] | None = None,
        fromlist: tuple[str, ...] = (),
        level: int = 0,
    ) -> object:
        if name == "jsonschema" or name.startswith("jsonschema."):
            raise ImportError("jsonschema is intentionally unavailable")
        return original_import(name, globals, locals, fromlist, level)

    sys.modules[module_name] = module
    try:
        builtins.__import__ = block_jsonschema
        spec.loader.exec_module(module)
        module.ACCEPTED_A8 = fixture.base
        module._run_git = control.run
        module._open_object_source = lambda _deadline: fixture.source
        result = module.validate(fixture.candidate, fixture.manifest_bytes)
    finally:
        builtins.__import__ = original_import
        sys.modules.pop(module_name, None)
    assert result["candidate_sha"] == fixture.candidate


@pytest.mark.parametrize("replacement_kind", ["weakened", "swapped"])
def test_schema_weaken_or_swap_is_rejected_by_frozen_digest(
    replacement_kind: str,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    fixture = _Fixture()
    _install(monkeypatch, tmp_path, fixture)
    replacement = tmp_path / "candidate-scope.schema.json"
    if replacement_kind == "weakened":
        schema = json.loads(scope.SCHEMA_PATH.read_text(encoding="utf-8"))
        schema["additionalProperties"] = True
        replacement.write_bytes(_json_bytes(schema))
    else:
        replacement.write_bytes(b'{"type":"object"}')
    monkeypatch.setattr(scope, "SCHEMA_PATH", replacement)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="frozen documentation contract"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


@pytest.mark.parametrize(
    "drift",
    [
        "root_extra",
        "root_missing",
        "phase_bool",
        "authority_extra",
        "authority_missing",
        "authority_false_type",
        "changes_object",
        "change_extra",
        "change_missing",
        "path_type",
        "status_type",
        "status_value",
    ],
)
def test_manual_manifest_contract_rejects_extra_missing_and_type_drift(
    drift: str,
) -> None:
    document = _manifest(scope.ACCEPTED_A8)
    if drift == "root_extra":
        document["unexpected"] = False
    elif drift == "root_missing":
        del document["phase"]
    elif drift == "phase_bool":
        document["phase"] = True
    elif drift == "authority_extra":
        document["authority"]["unexpected"] = False
    elif drift == "authority_missing":
        del document["authority"]["production_access"]
    elif drift == "authority_false_type":
        document["authority"]["production_access"] = 0
    elif drift == "changes_object":
        document["allowed_changes"] = {}
    elif drift == "change_extra":
        document["allowed_changes"][0]["unexpected"] = False
    elif drift == "change_missing":
        del document["allowed_changes"][0]["status"]
    elif drift == "path_type":
        document["allowed_changes"][0]["path"] = 7
    elif drift == "status_type":
        document["allowed_changes"][0]["status"] = False
    elif drift == "status_value":
        document["allowed_changes"][0]["status"] = "D"
    with pytest.raises(scope.CandidateScopeValidationError, match="scope manifest"):
        scope.validate("a" * 40, _json_bytes(document))


def test_validate_uses_one_authenticated_snapshot_and_derives_inventory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    control = _install(monkeypatch, tmp_path, fixture)

    result = scope.validate(fixture.candidate, fixture.manifest_bytes)

    self_blob = fixture.builder.blob(fixture.manifest_bytes)
    assert result["candidate_sha"] == fixture.candidate
    assert result["candidate_parent_sha"] == fixture.base
    assert result["candidate_tree_sha"] == fixture.candidate_tree
    assert result["production_authority"] is False
    assert result["derived_inventory"] == [
        {
            "bytes": len(fixture.manifest_bytes),
            "git_blob_sha": self_blob,
            "mode": "100644",
            "path": scope.SELF_PATH,
            "sha256": hashlib.sha256(fixture.manifest_bytes).hexdigest(),
            "status": "A",
        }
    ]
    assert result["derived_inventory_sha256"] == scope.canonical_sha256(
        result["derived_inventory"]
    )
    assert fixture.source.closed
    assert fixture.source.reads
    assert all(read_count == 1 for read_count in fixture.source.reads.values())
    assert not any(
        call[0] in {"cat-file", "diff", "ls-tree", "rev-list"} for call in control.calls
    )
    assert control.replace_checks == 2


def _assert_materialization_contract(
    result: dict[str, object], inventory_kind: str
) -> dict[str, object]:
    inventories = result["materialization_inventories"]
    assert set(inventories) == {scope.FULL_REPOSITORY, scope.JAVA_SERVICE_ONLY}
    inventory = inventories[inventory_kind]
    assert set(inventory) == {
        "entries",
        "file_count",
        "manifest_sha256",
        "total_bytes",
    }
    entries = inventory["entries"]
    assert inventory["file_count"] == len(entries)
    assert inventory["total_bytes"] == sum(entry["size"] for entry in entries)
    assert [entry["path"] for entry in entries] == sorted(
        (entry["path"] for entry in entries), key=lambda path: path.encode("utf-8")
    )
    assert all(
        set(entry) == {"git_blob_sha", "mode", "path", "sha256", "size", "type"}
        and entry["type"] == "blob"
        for entry in entries
    )
    canonical = scope.canonical_json_bytes(
        {
            "entries": entries,
            "file_count": inventory["file_count"],
            "inventory_kind": inventory_kind,
            "total_bytes": inventory["total_bytes"],
        }
    )
    assert inventory["manifest_sha256"] == hashlib.sha256(canonical).hexdigest()
    return inventory


def test_materialization_inventories_are_closed_world_and_repo_relative(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    java_path = "java-api-service/src/main/java/example/Changed.java"
    note_path = "notes/engineering.txt"
    changes = [
        {"path": scope.SELF_PATH, "status": "A"},
        {"path": java_path, "status": "M"},
        {"path": note_path, "status": "A"},
    ]
    fixture = _Fixture(
        changes=changes,
        base_extra={java_path: ("100644", b"class Changed {}\n")},
        candidate_extra={
            java_path: ("100755", b"final class Changed {}\n"),
            note_path: ("100644", b"full repository only\n"),
        },
    )
    _install(monkeypatch, tmp_path, fixture)

    result = scope.validate(fixture.candidate, fixture.manifest_bytes)

    full = _assert_materialization_contract(result, scope.FULL_REPOSITORY)
    java = _assert_materialization_contract(result, scope.JAVA_SERVICE_ONLY)
    full_paths = {entry["path"] for entry in full["entries"]}
    assert {scope.SELF_PATH, java_path, note_path, "README.md"}.issubset(full_paths)
    assert [entry["path"] for entry in java["entries"]] == [java_path]
    assert java["entries"][0]["mode"] == "100755"
    assert (
        java["entries"][0]["sha256"]
        == hashlib.sha256(b"final class Changed {}\n").hexdigest()
    )


def test_materialization_hash_is_stable_and_binds_content_and_mode(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    java_path = "java-api-service/pom.xml"
    changes = [
        {"path": scope.SELF_PATH, "status": "A"},
        {"path": java_path, "status": "M"},
    ]

    def validate_variant(
        directory: str, mode: str, content: bytes
    ) -> dict[str, object]:
        fixture = _Fixture(
            changes=changes,
            base_extra={java_path: ("100644", b"<project>old</project>\n")},
            candidate_extra={java_path: (mode, content)},
        )
        _install(monkeypatch, tmp_path / directory, fixture)
        return scope.validate(fixture.candidate, fixture.manifest_bytes)

    first = validate_variant("first", "100644", b"<project>new</project>\n")
    stable = validate_variant("stable", "100644", b"<project>new</project>\n")
    content_changed = validate_variant(
        "content", "100644", b"<project>different</project>\n"
    )
    mode_changed = validate_variant("mode", "100755", b"<project>new</project>\n")
    first_hash = first["materialization_inventories"][scope.JAVA_SERVICE_ONLY][
        "manifest_sha256"
    ]
    assert (
        stable["materialization_inventories"][scope.JAVA_SERVICE_ONLY][
            "manifest_sha256"
        ]
        == first_hash
    )
    assert (
        content_changed["materialization_inventories"][scope.JAVA_SERVICE_ONLY][
            "manifest_sha256"
        ]
        != first_hash
    )
    assert (
        mode_changed["materialization_inventories"][scope.JAVA_SERVICE_ONLY][
            "manifest_sha256"
        ]
        != first_hash
    )


def test_non_java_change_only_changes_full_materialization_hash(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    java_path = "java-api-service/pom.xml"
    base_extra = {java_path: ("100644", b"<project/>\n")}
    baseline = _Fixture(base_extra=base_extra)
    _install(monkeypatch, tmp_path / "baseline", baseline)
    baseline_result = scope.validate(baseline.candidate, baseline.manifest_bytes)

    note_path = "notes/only-full.txt"
    changed = _Fixture(
        changes=[
            {"path": scope.SELF_PATH, "status": "A"},
            {"path": note_path, "status": "A"},
        ],
        base_extra=base_extra,
        candidate_extra={note_path: ("100644", b"outside java\n")},
    )
    _install(monkeypatch, tmp_path / "changed", changed)
    changed_result = scope.validate(changed.candidate, changed.manifest_bytes)
    baseline_inventories = baseline_result["materialization_inventories"]
    changed_inventories = changed_result["materialization_inventories"]
    assert (
        baseline_inventories[scope.JAVA_SERVICE_ONLY]["manifest_sha256"]
        == changed_inventories[scope.JAVA_SERVICE_ONLY]["manifest_sha256"]
    )
    assert (
        baseline_inventories[scope.FULL_REPOSITORY]["manifest_sha256"]
        != changed_inventories[scope.FULL_REPOSITORY]["manifest_sha256"]
    )


def test_materialization_total_and_manifest_byte_budgets_are_enforced(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    original_total_limit = scope.MAX_MATERIALIZATION_TOTAL_BYTES
    fixture = _Fixture()
    _install(monkeypatch, tmp_path / "total", fixture)
    monkeypatch.setattr(scope, "MAX_MATERIALIZATION_TOTAL_BYTES", 1)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="materialization exceeds"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture()
    _install(monkeypatch, tmp_path / "manifest", fixture)
    monkeypatch.setattr(scope, "MAX_MATERIALIZATION_TOTAL_BYTES", original_total_limit)
    monkeypatch.setattr(scope, "MAX_MATERIALIZATION_MANIFEST_BYTES", 1)
    with pytest.raises(scope.CandidateScopeValidationError, match="manifest exceeds"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_materialization_manifest_hash_streaming_honors_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    entries = [
        {
            "git_blob_sha": "a" * 40,
            "mode": "100644",
            "path": f"file-{index}.txt",
            "sha256": "b" * 64,
            "size": 1,
            "type": "blob",
        }
        for index in range(2)
    ]
    clock = [0.0]
    original_canonical_json_bytes = scope.canonical_json_bytes

    def expire_after_first_entry(value: object) -> bytes:
        encoded = original_canonical_json_bytes(value)
        if isinstance(value, dict):
            clock[0] = 2.0
        return encoded

    monkeypatch.setattr(scope.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(scope, "canonical_json_bytes", expire_after_first_entry)
    with pytest.raises(scope.CandidateScopeValidationError, match="deadline"):
        scope._materialization_manifest_sha256(
            scope.FULL_REPOSITORY,
            entries,
            file_count=2,
            total_bytes=2,
            deadline=scope._Deadline(1.0),
        )


@pytest.mark.parametrize("extra_key", ["candidate_sha", "self_hash", "self_sha256"])
def test_manifest_rejects_self_referential_fields(extra_key: str) -> None:
    document = _manifest(scope.ACCEPTED_A8)
    document[extra_key] = "a" * 64
    with pytest.raises(scope.CandidateScopeValidationError, match="keys drifted"):
        scope.validate("a" * 40, _json_bytes(document))


def test_duplicate_json_key_is_rejected_before_git() -> None:
    raw = b'{"schema_version":"one","schema_version":"two"}'
    with pytest.raises(scope.CandidateScopeValidationError, match="duplicate JSON key"):
        scope.validate("a" * 40, raw)


@pytest.mark.parametrize(
    "path",
    ["../escape.py", "/absolute.py", "a\\b.py", "a//b.py", "a/./b.py", "NUL/x"],
)
def test_manifest_rejects_noncanonical_or_aliased_paths(path: str) -> None:
    document = _manifest(
        scope.ACCEPTED_A8,
        [
            {"path": scope.SELF_PATH, "status": "A"},
            {"path": path, "status": "A"},
        ],
    )
    with pytest.raises(
        scope.CandidateScopeValidationError, match="path|alias|canonical"
    ):
        scope.validate("a" * 40, _json_bytes(document))


def test_manifest_requires_newly_added_self_path() -> None:
    omitted = _manifest(scope.ACCEPTED_A8, [{"path": OTHER_PATH, "status": "A"}])
    with pytest.raises(scope.CandidateScopeValidationError, match="self_path"):
        scope.validate("a" * 40, _json_bytes(omitted))

    modified = _manifest(scope.ACCEPTED_A8, [{"path": scope.SELF_PATH, "status": "M"}])
    with pytest.raises(scope.CandidateScopeValidationError, match="newly added"):
        scope.validate("a" * 40, _json_bytes(modified))


def test_declared_scope_rejects_omission_injection_and_order_drift(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    changes = [
        {"path": scope.SELF_PATH, "status": "A"},
        {"path": OTHER_PATH, "status": "A"},
    ]
    fixture = _Fixture(
        changes=changes,
        candidate_extra={OTHER_PATH: ("100644", b"validator")},
    )
    fixture.document["allowed_changes"] = [changes[0]]
    declared = _json_bytes(fixture.document)
    fixture.source.objects[fixture.builder.blob(fixture.manifest_bytes)] = (
        "blob",
        fixture.manifest_bytes,
    )
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="exactly match"):
        scope.validate(fixture.candidate, declared)

    reordered = list(reversed(changes))
    fixture = _Fixture(
        changes=reordered,
        candidate_extra={OTHER_PATH: ("100644", b"validator")},
    )
    _install(monkeypatch, tmp_path / "second", fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="exactly match"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_delete_and_rename_are_rejected_from_authenticated_trees(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture(
        base_extra={"old.py": ("100644", b"old")},
        remove=("old.py",),
    )
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="destructive|renamed"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture(
        base_extra={"old.py": ("100644", b"same")},
        candidate_extra={"new.py": ("100644", b"same")},
        remove=("old.py",),
    )
    _install(monkeypatch, tmp_path / "rename", fixture)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="destructive|renamed"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


@pytest.mark.parametrize("mode", ["120000", "160000", "040000"])
def test_tree_rejects_symlink_submodule_and_nonblob_modes(
    mode: str, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    blob = fixture.builder.blob(fixture.manifest_bytes)
    raw = mode.encode("ascii") + b" manifest\0" + bytes.fromhex(blob)
    fixture.replace_candidate_tree(raw)
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="non-blob mode"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_recursive_tree_rejects_casefolded_directory_collision(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    left = fixture.builder.tree([("100644", "a", fixture.builder.blob(b"left"))])
    right = fixture.builder.tree([("100644", "b", fixture.builder.blob(b"right"))])
    root = fixture.builder.tree([("40000", "Dir", left), ("40000", "dir", right)])
    fixture.candidate = fixture.builder.commit(root, (fixture.base,))
    fixture.source.objects = fixture.builder.objects
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="component collision"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_recursive_tree_rejects_file_directory_alias_conflict(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    child = fixture.builder.tree([("100644", "b", fixture.builder.blob(b"nested"))])
    root = fixture.builder.tree(
        [
            ("40000", "A", child),
            ("100644", "a", fixture.builder.blob(b"file")),
        ]
    )
    fixture.candidate = fixture.builder.commit(root, (fixture.base,))
    fixture.source.objects = fixture.builder.objects
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="component collision"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


@pytest.mark.parametrize(
    "name",
    [
        "CON",
        "aux.txt",
        "COM\u00b9.log",
        "CONIN$",
        "trailing.",
        "trailing ",
        ".git",
        "bad:name",
    ],
)
def test_recursive_tree_rejects_windows_alias_components(
    name: str, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    root = fixture.builder.tree([("100644", name, fixture.builder.blob(b"alias"))])
    fixture.candidate = fixture.builder.commit(root, (fixture.base,))
    fixture.source.objects = fixture.builder.objects
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="alias"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_empty_and_hidden_tree_discrepancies_are_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    fixture.replace_candidate_tree(b"")
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="empty Git tree"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture()
    fixture.replace_candidate_tree(b"100644 truncated\0short")
    _install(monkeypatch, tmp_path / "hidden", fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="truncated"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_v047_is_rejected_even_when_unchanged_from_a8(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture(base_extra={scope.V047_PATH: ("100644", b"forbidden")})
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="V047"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_candidate_must_be_linear_single_parent(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture(merge_candidate=True)
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="linear"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


@pytest.mark.parametrize("misplaced_kind", ["parent", "tree"])
def test_commit_rejects_parent_or_tree_after_non_parent_headers(
    misplaced_kind: str,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    fixture = _Fixture()
    misplaced = fixture.base if misplaced_kind == "parent" else fixture.candidate_tree
    raw = (
        f"tree {fixture.candidate_tree}\n"
        "author Phase Eight <phase8@example.invalid> 0 +0000\n"
        f"{misplaced_kind} {misplaced}\n"
        "committer Phase Eight <phase8@example.invalid> 0 +0000\n"
        "\nmisplaced\n"
    ).encode("ascii")
    fixture.candidate = fixture.builder.add("commit", raw)
    fixture.source.objects = fixture.builder.objects
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="misplaced"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_commit_rejects_mixed_crlf_parent_header(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    raw = (
        f"tree {fixture.candidate_tree}\n"
        f"parent {fixture.base}\r\n"
        "author Phase Eight <phase8@example.invalid> 0 +0000\n"
        "committer Phase Eight <phase8@example.invalid> 0 +0000\n"
        "\ncrlf\n"
    ).encode("ascii")
    fixture.candidate = fixture.builder.add("commit", raw)
    fixture.source.objects = fixture.builder.objects
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="CR or control"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_raw_commit_blob_and_mixed_identity_substitution_are_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    fixture.source.raw_overrides[fixture.candidate] = (
        fixture.builder.objects[fixture.candidate][1] + b"substituted"
    )
    _install(monkeypatch, tmp_path, fixture)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="commit object substitution"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture()
    self_blob = fixture.builder.blob(fixture.manifest_bytes)
    fixture.source.raw_overrides[self_blob] = b"substituted"
    _install(monkeypatch, tmp_path / "blob", fixture)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="blob object substitution"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture()
    fixture.source.raw_overrides[fixture.candidate_tree] = (
        fixture.builder.objects[fixture.candidate_tree][1] + b"hidden"
    )
    _install(monkeypatch, tmp_path / "tree", fixture)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="tree object substitution"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture()
    fixture.source.identity_overrides[fixture.candidate] = "8" * 40
    _install(monkeypatch, tmp_path / "mixed", fixture)
    with pytest.raises(scope.CandidateScopeValidationError, match="mixed identity"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_manifest_bytes_must_be_the_candidate_self_blob(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    _install(monkeypatch, tmp_path, fixture)
    different = copy.deepcopy(fixture.document)
    different_raw = json.dumps(different, indent=2).encode("utf-8")
    assert different_raw != fixture.manifest_bytes
    with pytest.raises(scope.CandidateScopeValidationError, match="self_path blob"):
        scope.validate(fixture.candidate, different_raw)


def test_replace_state_is_rechecked_after_snapshot(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    control = _install(monkeypatch, tmp_path, fixture)
    control.replace_on_check = 2
    with pytest.raises(scope.CandidateScopeValidationError, match="replace refs"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)
    assert control.replace_checks == 2


def test_graft_and_alternate_files_are_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    control = _install(monkeypatch, tmp_path, fixture)
    alternate = control.git_root / "objects/info/alternates"
    alternate.parent.mkdir(parents=True)
    alternate.write_text("D:/untrusted/objects", encoding="utf-8")
    with pytest.raises(scope.CandidateScopeValidationError, match="graft/alternate"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_global_deadline_is_hard(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(scope, "MAX_VALIDATION_SECONDS", 0.0)
    with pytest.raises(scope.CandidateScopeValidationError, match="deadline"):
        scope.validate("a" * 40, b"{}")


def test_deadline_is_rechecked_after_each_raw_object_read(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture()
    _install(monkeypatch, tmp_path, fixture)
    clock = [0.0]
    original_read = fixture.source.read_object

    def expire_after_read(object_id: str) -> scope._ObjectEnvelope:
        envelope = original_read(object_id)
        clock[0] = scope.MAX_VALIDATION_SECONDS + 1.0
        return envelope

    monkeypatch.setattr(scope.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(fixture.source, "read_object", expire_after_read)
    with pytest.raises(scope.CandidateScopeValidationError, match="deadline"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_history_commit_limit_is_enforced(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture(intermediate_commits=2)
    _install(monkeypatch, tmp_path, fixture)
    monkeypatch.setattr(scope, "MAX_ANCESTOR_COMMITS", 2)
    with pytest.raises(scope.CandidateScopeValidationError, match="commit limit"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_tree_depth_and_entry_limits_are_enforced(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    fixture = _Fixture(
        changes=[
            {"path": scope.SELF_PATH, "status": "A"},
            {"path": "a/b/c/deep.txt", "status": "A"},
        ],
        candidate_extra={"a/b/c/deep.txt": ("100644", b"deep")},
    )
    _install(monkeypatch, tmp_path, fixture)
    monkeypatch.setattr(scope, "MAX_TREE_DEPTH", 2)
    with pytest.raises(scope.CandidateScopeValidationError, match="depth limit"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture(
        changes=[
            {"path": scope.SELF_PATH, "status": "A"},
            {"path": "one.txt", "status": "A"},
            {"path": "two.txt", "status": "A"},
        ],
        candidate_extra={
            "one.txt": ("100644", b"one"),
            "two.txt": ("100644", b"two"),
        },
    )
    _install(monkeypatch, tmp_path / "entries", fixture)
    monkeypatch.setattr(scope, "MAX_TREE_ENTRIES", 2)
    with pytest.raises(scope.CandidateScopeValidationError, match="entry limit"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_blob_snapshot_and_output_byte_limits_are_enforced(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    original_blob_limit = scope.MAX_SINGLE_BLOB_BYTES
    original_snapshot_limit = scope.MAX_TOTAL_SNAPSHOT_BYTES
    fixture = _Fixture()
    _install(monkeypatch, tmp_path, fixture)
    monkeypatch.setattr(scope, "MAX_SINGLE_BLOB_BYTES", 8)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="blob object exceeds"
    ):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture()
    _install(monkeypatch, tmp_path / "total", fixture)
    monkeypatch.setattr(scope, "MAX_SINGLE_BLOB_BYTES", original_blob_limit)
    monkeypatch.setattr(scope, "MAX_TOTAL_SNAPSHOT_BYTES", 8)
    with pytest.raises(scope.CandidateScopeValidationError, match="total byte limit"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)

    fixture = _Fixture()
    _install(monkeypatch, tmp_path / "output", fixture)
    monkeypatch.setattr(scope, "MAX_TOTAL_SNAPSHOT_BYTES", original_snapshot_limit)
    fixture.source.extra_output_bytes = 1024
    monkeypatch.setattr(scope, "MAX_TOTAL_GIT_OUTPUT_BYTES", 100)
    with pytest.raises(scope.CandidateScopeValidationError, match="output exceeds"):
        scope.validate(fixture.candidate, fixture.manifest_bytes)


def test_fixed_git_boundary_uses_absolute_executable_and_minimal_environment() -> None:
    environment = scope._minimal_git_environment()
    command = scope._fixed_git_command("cat-file", "--batch")
    assert scope.GIT_EXECUTABLE.is_absolute()
    assert environment["GIT_NO_LAZY_FETCH"] == "1"
    assert environment["GIT_NO_REPLACE_OBJECTS"] == "1"
    assert environment["GIT_CONFIG_NOSYSTEM"] == "1"
    assert environment["GIT_OPTIONAL_LOCKS"] == "0"
    assert "GIT_ALTERNATE_OBJECT_DIRECTORIES" not in environment
    assert "GIT_OBJECT_DIRECTORY" not in environment
    assert "PYTHONPATH" not in environment
    assert command[0] == str(scope.GIT_EXECUTABLE)
    assert command.count("protocol.allow=never") == 1
    assert command[-2:] == ["cat-file", "--batch"]


def test_partial_clone_batch_is_configured_to_fail_closed_before_lazy_fetch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    def reject_start(command: list[str], **kwargs: object) -> None:
        captured["command"] = command
        captured["environment"] = kwargs["env"]
        raise OSError("synthetic start stop")

    monkeypatch.setattr(scope.subprocess, "Popen", reject_start)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="snapshot could not start"
    ):
        scope._BatchObjectSource(scope._Deadline.start())
    assert "protocol.allow=never" in captured["command"]
    environment = captured["environment"]
    assert environment["GIT_NO_LAZY_FETCH"] == "1"
    assert environment["GIT_TERMINAL_PROMPT"] == "0"


def test_missing_promisor_blob_cannot_launch_remote_helper(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    source_root = tmp_path / "source"
    clone_root = tmp_path / "partial"

    def git(*arguments: str, cwd: Path | None = None) -> bytes:
        completed = subprocess.run(
            [str(scope.GIT_EXECUTABLE), *arguments],
            cwd=cwd,
            shell=False,
            check=True,
            capture_output=True,
        )
        return completed.stdout.strip()

    git("init", "-q", str(source_root))
    git("config", "user.email", "phase8@example.invalid", cwd=source_root)
    git("config", "user.name", "Phase Eight", cwd=source_root)
    git("config", "uploadpack.allowFilter", "true", cwd=source_root)
    source_blob = source_root / "promised.bin"
    source_blob.write_bytes(b"promised-but-not-local" * 4096)
    git("add", "promised.bin", cwd=source_root)
    git("commit", "-q", "-m", "promisor fixture", cwd=source_root)
    blob_id = git("rev-parse", "HEAD:promised.bin", cwd=source_root).decode("ascii")
    git(
        "-c",
        "protocol.file.allow=always",
        "clone",
        "-q",
        "--filter=blob:none",
        "--no-checkout",
        source_root.as_uri(),
        str(clone_root),
    )
    packed = b"".join(
        git("verify-pack", "-v", str(index), cwd=clone_root)
        for index in (clone_root / ".git/objects/pack").glob("*.idx")
    )
    assert blob_id.encode("ascii") not in packed
    sentinel = tmp_path / "remote-helper-invoked"
    if os.name == "nt":
        helper = tmp_path / "remote-helper.cmd"
        helper.write_text(
            f'@echo off\r\necho invoked>"{sentinel}"\r\nexit /b 1\r\n',
            encoding="ascii",
        )
    else:
        helper = tmp_path / "remote-helper.sh"
        helper.write_text(
            f"#!/bin/sh\nprintf invoked > '{sentinel}'\nexit 1\n",
            encoding="ascii",
        )
        helper.chmod(0o700)
    git("remote", "set-url", "origin", f"ext::{helper.as_posix()}", cwd=clone_root)

    monkeypatch.setattr(scope, "ROOT", clone_root)
    with pytest.raises(
        scope.CandidateScopeValidationError, match="header is out of bounds"
    ):
        with scope._BatchObjectSource(scope._Deadline.start()) as object_source:
            object_source.read_object(blob_id)
    assert not sentinel.exists()
