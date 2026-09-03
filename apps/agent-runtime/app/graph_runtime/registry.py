"""Immutable Graph version registry and fail-closed shadow selection."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, Final

from app.graph_runtime.errors import (
    GraphContractError,
    GraphVersionBindingError,
    GraphVersionNotFoundError,
    GraphVersionUnavailableError,
)
from app.graph_runtime.identity import _identifier, _sha256


class RegistryState(StrEnum):
    """There is deliberately no state that grants formal-writer authority."""

    DISABLED = "DISABLED"
    SHADOW = "SHADOW"
    ACTIVE_CANDIDATE = "ACTIVE_CANDIDATE"
    RETIRED = "RETIRED"


@dataclass(frozen=True, slots=True)
class CommandProfileBinding:
    command_schema_version: str
    prompt_version: str
    model_profile_id: str
    output_schema_version: str
    policy_version: str
    guardrail_version: str
    tool_policy_version: str

    def __post_init__(self) -> None:
        for name in (
            "command_schema_version",
            "prompt_version",
            "model_profile_id",
            "output_schema_version",
            "policy_version",
            "guardrail_version",
            "tool_policy_version",
        ):
            _identifier(getattr(self, name), name)


@dataclass(frozen=True, slots=True)
class VersionBinding:
    graph_key: str
    graph_version: str
    checkpoint_schema_version: str
    state_schema_version: str
    state_schema_hash: str
    command_schema_version: str
    result_schema_version: str
    prompt_version: str
    model_profile_id: str
    output_schema_version: str
    policy_version: str
    guardrail_version: str
    tool_policy_version: str
    binding_hash: str
    code_build_id: str

    def __post_init__(self) -> None:
        for name in (
            "graph_key",
            "graph_version",
            "checkpoint_schema_version",
            "state_schema_version",
            "command_schema_version",
            "result_schema_version",
            "prompt_version",
            "model_profile_id",
            "output_schema_version",
            "policy_version",
            "guardrail_version",
            "tool_policy_version",
            "code_build_id",
        ):
            _identifier(getattr(self, name), name)
        _sha256(self.state_schema_hash, "state_schema_hash")
        _sha256(self.binding_hash, "binding_hash")

    @property
    def command_profile(self) -> CommandProfileBinding:
        return CommandProfileBinding(
            command_schema_version=self.command_schema_version,
            prompt_version=self.prompt_version,
            model_profile_id=self.model_profile_id,
            output_schema_version=self.output_schema_version,
            policy_version=self.policy_version,
            guardrail_version=self.guardrail_version,
            tool_policy_version=self.tool_policy_version,
        )

    def require_profile(self, actual: CommandProfileBinding) -> None:
        if actual != self.command_profile:
            raise GraphVersionBindingError()


@dataclass(frozen=True, slots=True)
class RegistryRecord:
    binding: VersionBinding
    state: RegistryState
    loadable: bool
    revision: int

    def __post_init__(self) -> None:
        if not isinstance(self.state, RegistryState):
            raise GraphContractError(
                "registry state must be DISABLED, SHADOW, ACTIVE_CANDIDATE, or RETIRED"
            )
        if not isinstance(self.loadable, bool):
            raise GraphContractError("registry loadable flag must be boolean")
        if isinstance(self.revision, bool) or self.revision < 0:
            raise GraphContractError("registry revision must be non-negative")
        if self.state in {
            RegistryState.SHADOW,
            RegistryState.ACTIVE_CANDIDATE,
            RegistryState.RETIRED,
        } and not self.loadable:
            raise GraphVersionBindingError("active and retired registry rows must remain loadable")

    def require_new_shadow_command(self) -> VersionBinding:
        if self.state is not RegistryState.SHADOW or not self.loadable:
            raise GraphVersionUnavailableError()
        return self.binding

    def require_thread_restore(self) -> VersionBinding:
        if self.state not in {
            RegistryState.SHADOW,
            RegistryState.ACTIVE_CANDIDATE,
            RegistryState.RETIRED,
        } or not self.loadable:
            raise GraphVersionUnavailableError()
        return self.binding

    def require_new_candidate_command(self) -> VersionBinding:
        if self.state is not RegistryState.ACTIVE_CANDIDATE or not self.loadable:
            raise GraphVersionUnavailableError()
        return self.binding


@dataclass(frozen=True, slots=True)
class MigrationSafety:
    migration_safe: bool
    quiescent_node: bool
    reducers_complete: bool
    pending_send_count: int = 0
    model_call_pending: bool = False
    tool_proposal_pending: bool = False
    uncommitted_result: bool = False

    @property
    def safe(self) -> bool:
        return (
            self.migration_safe
            and self.quiescent_node
            and self.reducers_complete
            and self.pending_send_count == 0
            and not self.model_call_pending
            and not self.tool_proposal_pending
            and not self.uncommitted_result
        )

    def require_safe(self) -> None:
        if self.pending_send_count < 0 or self.pending_send_count > 8 or not self.safe:
            raise GraphVersionUnavailableError("GRAPH_CHECKPOINT_NOT_MIGRATION_SAFE")


LOAD_VERSION_SQL: Final[str] = """
select graph_key, graph_version, checkpoint_schema_version, registry_state,
       state_schema_version, state_schema_hash, command_schema_version,
       result_schema_version, prompt_version, model_profile_id,
       output_schema_version, policy_version, guardrail_version,
       tool_policy_version, binding_hash, code_build_id, loadable,
       registry_revision
  from agent_graph_version_registry
 where graph_key = %s
   and graph_version = %s
   and checkpoint_schema_version = %s
"""


class PostgresGraphVersionRegistry:
    async def load(
        self,
        connection: Any,
        *,
        graph_key: str,
        graph_version: str,
        checkpoint_schema_version: str,
    ) -> RegistryRecord:
        _identifier(graph_key, "graph_key")
        _identifier(graph_version, "graph_version")
        _identifier(checkpoint_schema_version, "checkpoint_schema_version")
        row = await (
            await connection.execute(
                LOAD_VERSION_SQL,
                (graph_key, graph_version, checkpoint_schema_version),
            )
        ).fetchone()
        if row is None:
            raise GraphVersionNotFoundError()
        return self._from_row(row)

    async def require_new_shadow_command(
        self,
        connection: Any,
        *,
        graph_key: str,
        graph_version: str,
        checkpoint_schema_version: str,
        profile: CommandProfileBinding,
    ) -> RegistryRecord:
        record = await self.load(
            connection,
            graph_key=graph_key,
            graph_version=graph_version,
            checkpoint_schema_version=checkpoint_schema_version,
        )
        record.require_new_shadow_command().require_profile(profile)
        return record

    async def require_thread_restore(
        self,
        connection: Any,
        *,
        graph_key: str,
        graph_version: str,
        checkpoint_schema_version: str,
    ) -> RegistryRecord:
        record = await self.load(
            connection,
            graph_key=graph_key,
            graph_version=graph_version,
            checkpoint_schema_version=checkpoint_schema_version,
        )
        record.require_thread_restore()
        return record

    @staticmethod
    def _from_row(row: Mapping[str, Any]) -> RegistryRecord:
        try:
            binding = VersionBinding(
                graph_key=row["graph_key"],
                graph_version=row["graph_version"],
                checkpoint_schema_version=row["checkpoint_schema_version"],
                state_schema_version=row["state_schema_version"],
                state_schema_hash=row["state_schema_hash"],
                command_schema_version=row["command_schema_version"],
                result_schema_version=row["result_schema_version"],
                prompt_version=row["prompt_version"],
                model_profile_id=row["model_profile_id"],
                output_schema_version=row["output_schema_version"],
                policy_version=row["policy_version"],
                guardrail_version=row["guardrail_version"],
                tool_policy_version=row["tool_policy_version"],
                binding_hash=row["binding_hash"],
                code_build_id=row["code_build_id"],
            )
            return RegistryRecord(
                binding=binding,
                state=RegistryState(row["registry_state"]),
                loadable=row["loadable"],
                revision=row["registry_revision"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphVersionBindingError("persisted Graph version binding is invalid") from error
