from __future__ import annotations

from dataclasses import asdict
from typing import Any

import pytest

from app.graph_runtime.errors import (
    GraphContractError,
    GraphThreadBindingError,
    GraphVersionBindingError,
    GraphVersionUnavailableError,
)
from app.graph_runtime.identity import (
    ActorRole,
    ActorScopeBinding,
    Audience,
    PostgresThreadIdentityRepository,
    RoomType,
    ThreadIdentity,
)
from app.graph_runtime.registry import (
    CommandProfileBinding,
    MigrationSafety,
    PostgresGraphVersionRegistry,
    RegistryState,
)


THREAD = f"grt.v1.{'3' * 32}"


def _scope(role: ActorRole = ActorRole.USER) -> ActorScopeBinding:
    audience = {
        ActorRole.USER: Audience.USER,
        ActorRole.MERCHANT: Audience.MERCHANT,
        ActorRole.PLATFORM_REVIEWER: Audience.PLATFORM_REVIEWER,
        ActorRole.SYSTEM: Audience.SYSTEM,
    }[role]
    return ActorScopeBinding(
        actor_id="actor-1",
        actor_role=role,
        audience=audience,
        capabilities=("graph.read",),
    )


def _identity(**overrides: Any) -> ThreadIdentity:
    values: dict[str, Any] = {
        "thread_id": THREAD,
        "tenant_surrogate": "tenant-1",
        "case_id": "case-1",
        "room_type": RoomType.INTAKE,
        "room_epoch": 2,
        "actor_scope": _scope(),
        "agent_session_id": "agent-session-1",
        "shared_session": False,
        "graph_key": "intake.flow",
        "graph_version": "intake.v2",
        "checkpoint_schema_version": "intake.checkpoint.v2",
    }
    values.update(overrides)
    return ThreadIdentity(**values)


def _thread_row(identity: ThreadIdentity) -> dict[str, Any]:
    return {
        "thread_id": identity.thread_id,
        "tenant_surrogate": identity.tenant_surrogate,
        "case_id": identity.case_id,
        "room_type": identity.room_type.value,
        "room_epoch": identity.room_epoch,
        "actor_scope_json": identity.actor_scope.to_json(),
        "actor_scope_hash": identity.actor_scope_hash,
        "agent_session_id": identity.agent_session_id,
        "shared_session": identity.shared_session,
        "graph_key": identity.graph_key,
        "graph_version": identity.graph_version,
        "checkpoint_schema_version": identity.checkpoint_schema_version,
        "lifecycle_status": "ACTIVE",
        "cognitive_revision": 0,
        "last_checkpoint_ns": None,
        "last_checkpoint_id": None,
    }


def _registry_row(state: str = "SHADOW", **overrides: Any) -> dict[str, Any]:
    values = {
        "graph_key": "intake.flow",
        "graph_version": "intake.v2",
        "checkpoint_schema_version": "intake.checkpoint.v2",
        "registry_state": state,
        "state_schema_version": "intake.state.v2",
        "state_schema_hash": "a" * 64,
        "command_schema_version": "room-graph-command.v1",
        "result_schema_version": "room-graph-result.v1",
        "prompt_version": "intake.prompt.v2",
        "model_profile_id": "model.standard.v1",
        "output_schema_version": "intake.output.v2",
        "policy_version": "policy.v2",
        "guardrail_version": "guardrail.v2",
        "tool_policy_version": "tools.none.v1",
        "binding_hash": "b" * 64,
        "code_build_id": "build-1",
        "loadable": True,
        "registry_revision": 1,
    }
    values.update(overrides)
    return values


class _Cursor:
    def __init__(self, row: Any) -> None:
        self.row = row

    async def fetchone(self) -> Any:
        return self.row


class _Connection:
    def __init__(self, row: Any) -> None:
        self.row = row

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        return _Cursor(self.row)


def test_private_and_shared_hearing_shapes_remain_distinct_and_valid() -> None:
    private = _identity(room_type=RoomType.HEARING, graph_key="hearing.flow")
    shared = _identity(
        room_type=RoomType.HEARING,
        actor_scope=_scope(ActorRole.SYSTEM),
        shared_session=True,
        graph_key="hearing.flow",
    )

    assert private.accepts_private_conversation is True
    assert shared.accepts_private_conversation is False
    assert private.actor_scope_hash != shared.actor_scope_hash


def test_shared_hearing_cannot_use_party_scope() -> None:
    with pytest.raises(GraphContractError, match="SYSTEM-scoped"):
        _identity(room_type=RoomType.HEARING, shared_session=True)


def test_review_requires_exact_reviewer_scope() -> None:
    with pytest.raises(GraphContractError, match="reviewer"):
        _identity(room_type=RoomType.REVIEW)
    review = _identity(
        room_type=RoomType.REVIEW,
        actor_scope=_scope(ActorRole.PLATFORM_REVIEWER),
        graph_key="review.flow",
    )
    assert review.actor_scope.actor_role is ActorRole.PLATFORM_REVIEWER


@pytest.mark.asyncio
async def test_thread_repository_compares_full_tuple_including_agent_session() -> None:
    expected = _identity()
    repository = PostgresThreadIdentityRepository()

    loaded = await repository.require_exact(_Connection(_thread_row(expected)), expected)

    assert loaded.identity == expected
    forged = _identity(agent_session_id="agent-session-other")
    with pytest.raises(GraphThreadBindingError):
        await repository.require_exact(_Connection(_thread_row(expected)), forged)


@pytest.mark.asyncio
async def test_corrupt_persisted_actor_scope_hash_fails_closed() -> None:
    identity = _identity()
    row = _thread_row(identity)
    row["actor_scope_hash"] = "f" * 64

    with pytest.raises(GraphThreadBindingError, match="inconsistent"):
        await PostgresThreadIdentityRepository().load(_Connection(row), THREAD)


@pytest.mark.asyncio
async def test_inactive_thread_binding_is_readable_but_cannot_grant_execution() -> None:
    identity = _identity()
    row = _thread_row(identity)
    row["lifecycle_status"] = "RETIRED"
    repository = PostgresThreadIdentityRepository()

    bound = await repository.require_binding(_Connection(row), identity)

    assert bound.identity == identity
    with pytest.raises(GraphThreadBindingError, match="NOT_ACTIVE"):
        await repository.require_exact(_Connection(row), identity)


def test_registry_exposes_candidate_without_formal_writer_authority() -> None:
    assert {state.value for state in RegistryState} == {
        "DISABLED",
        "SHADOW",
        "ACTIVE_CANDIDATE",
        "RETIRED",
    }
    assert "FORMAL" not in {state.value for state in RegistryState}
    with pytest.raises(ValueError):
        RegistryState("FORMAL_WRITER")


@pytest.mark.asyncio
async def test_shadow_registry_requires_exact_immutable_profile_binding() -> None:
    registry = PostgresGraphVersionRegistry()
    record = await registry.load(
        _Connection(_registry_row()),
        graph_key="intake.flow",
        graph_version="intake.v2",
        checkpoint_schema_version="intake.checkpoint.v2",
    )

    assert record.require_new_shadow_command() == record.binding
    record.binding.require_profile(record.binding.command_profile)
    wrong = CommandProfileBinding(
        **{
            **asdict(record.binding.command_profile),
            "model_profile_id": "model.unpinned",
        }
    )
    with pytest.raises(GraphVersionBindingError):
        record.binding.require_profile(wrong)


@pytest.mark.asyncio
async def test_disabled_rejects_execution_and_retired_allows_only_restore() -> None:
    registry = PostgresGraphVersionRegistry()
    disabled = await registry.load(
        _Connection(_registry_row("DISABLED")),
        graph_key="intake.flow",
        graph_version="intake.v2",
        checkpoint_schema_version="intake.checkpoint.v2",
    )
    retired = await registry.load(
        _Connection(_registry_row("RETIRED")),
        graph_key="intake.flow",
        graph_version="intake.v2",
        checkpoint_schema_version="intake.checkpoint.v2",
    )

    with pytest.raises(GraphVersionUnavailableError):
        disabled.require_new_shadow_command()
    with pytest.raises(GraphVersionUnavailableError):
        retired.require_new_shadow_command()
    assert retired.require_thread_restore() == retired.binding


@pytest.mark.asyncio
async def test_candidate_registry_is_loadable_but_never_shadow_relabelled() -> None:
    candidate = await PostgresGraphVersionRegistry().load(
        _Connection(_registry_row("ACTIVE_CANDIDATE")),
        graph_key="intake.flow",
        graph_version="intake.v2",
        checkpoint_schema_version="intake.checkpoint.v2",
    )

    assert candidate.require_new_candidate_command() == candidate.binding
    with pytest.raises(GraphVersionUnavailableError):
        candidate.require_new_shadow_command()


def test_migration_safety_requires_every_quiescence_predicate() -> None:
    MigrationSafety(True, True, True).require_safe()
    unsafe = MigrationSafety(
        migration_safe=True,
        quiescent_node=True,
        reducers_complete=True,
        pending_send_count=1,
    )
    with pytest.raises(GraphVersionUnavailableError):
        unsafe.require_safe()
