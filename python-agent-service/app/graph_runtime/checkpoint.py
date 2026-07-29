"""Isolated Graph PostgreSQL pool and lease-fenced LangGraph checkpointer."""

from __future__ import annotations

from collections.abc import AsyncIterator, Callable, Mapping, Sequence
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass, replace
from typing import Any, Final

from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
)
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from app.graph_runtime.persistence_models import (
    GraphBindingError,
    GraphFenceContext,
    GraphFenceError,
    GraphGatewayMode,
    GraphPoolConfig,
)
from app.graph_runtime.ledger import PostgresCommandLedger, ResultRecord
from app.graph_runtime.target_e2e import (
    TargetE2ERoomProposalSource,
    build_target_e2e_result_envelope,
)
from app.graph_runtime.result import (
    TERMINAL_DRAFT_ADAPTER,
    ResultBindings,
    TerminalDraft,
    project_room_graph_result,
)


FENCE_CONTEXT_KEY: Final[str] = "__trusted_graph_fence_context__"
TERMINAL_RESULT_CONTEXT_KEY: Final[str] = "__trusted_graph_terminal_result__"
ROOM_GRAPH_RESULT_SCHEMA_VERSION: Final[str] = "room-graph-result.v1"

FENCE_LOCK_SQL: Final[str] = """
select fencing_token
  from agent_graph_lease
 where thread_id = %s
   and command_id = %s
   and owner_id = %s
   and fencing_token = %s
   and cancelled_at is null
   and released_at is null
   and lease_expires_at > clock_timestamp()
 for update
"""

TARGET_E2E_ROOM_FENCE_SQL: Final[str] = """
select room.room_fencing_token
  from agent_graph_target_e2e_room_authority room
  join agent_graph_target_e2e_activation activation
    on activation.activation_id = room.activation_id
  join agent_graph_target_e2e_activation_lifecycle lifecycle
    on lifecycle.activation_id = activation.activation_id
  join agent_graph_command command
    on command.thread_id = %s
   and command.command_id = %s
   and command.activation_id = activation.activation_id
 where room.tenant_surrogate = %s
   and room.case_id = %s
   and room.room_type = %s
   and room.activation_id = %s
   and room.room_epoch = %s
   and room.room_fencing_token = %s
   and command.execution_mode = 'TARGET_E2E_CANDIDATE'
   and command.room_fencing_token = room.room_fencing_token
   and command.command_hash = %s
   and command.command_envelope_hash = %s
   and command.registered_at < activation.expires_at
   and lifecycle.lifecycle_state in ('ACTIVE', 'DRAIN_ONLY')
 for share of room, lifecycle, command
"""

DRAIN_EXPIRED_TARGET_E2E_SQL: Final[str] = """
update agent_graph_target_e2e_activation_lifecycle lifecycle
   set lifecycle_state = 'DRAIN_ONLY',
       drain_only_at = coalesce(drain_only_at, activation.expires_at),
       updated_at = clock_timestamp()
  from agent_graph_target_e2e_activation activation
 where lifecycle.activation_id = activation.activation_id
   and lifecycle.activation_id = %s
   and lifecycle.lifecycle_state = 'ACTIVE'
   and activation.expires_at <= clock_timestamp()
"""

BIND_CHECKPOINT_SQL: Final[str] = """
update agent_graph_command
   set status = case
           when %s::text is not null then 'RESULT_CHECKPOINTED'
           else status
       end,
       fencing_token = %s,
       committed_checkpoint_ns = %s,
       committed_checkpoint_id = %s,
       result_ref = coalesce(%s, result_ref),
       result_hash = coalesce(%s, result_hash),
       result_checkpointed_at = case
           when %s::text is not null then coalesce(result_checkpointed_at, clock_timestamp())
           else result_checkpointed_at
       end,
       updated_at = clock_timestamp(),
       command_revision = command_revision + 1
 where thread_id = %s
   and command_id = %s
   and request_hash = %s
    and room_epoch = %s
    and graph_key = %s
    and graph_version = %s
   and checkpoint_schema_version = %s
   and status in ('EXECUTING', 'RESULT_CHECKPOINTED')
   and (result_hash is null or result_hash = %s)
   and (
       status = 'EXECUTING'
       or (
           status = 'RESULT_CHECKPOINTED'
           and committed_checkpoint_ns is not distinct from %s
           and committed_checkpoint_id = %s
           and result_ref = %s
           and result_hash = %s
       )
   )
 returning status, result_hash
"""

CHECKPOINT_METADATA_SQL: Final[str] = """
select metadata
  from checkpoints
 where thread_id = %s
   and checkpoint_ns = %s
   and checkpoint_id = %s
 for share
"""

LOCK_TERMINAL_CHECKPOINT_SQL: Final[str] = """
select metadata
  from checkpoints
 where thread_id = %s
   and checkpoint_ns = %s
   and checkpoint_id = %s
 for update
"""

BIND_EXTERNAL_TERMINAL_METADATA_SQL: Final[str] = """
update checkpoints
   set metadata = metadata || jsonb_build_object(
           'graph_result_hash', %s::text,
           'graph_result_ref', %s::text,
           'graph_proposal_hash', %s::text,
           'graph_result_envelope_hash', %s::text
       )
 where thread_id = %s
   and checkpoint_ns = %s
   and checkpoint_id = %s
 returning metadata
"""

ADVANCE_THREAD_CHECKPOINT_SQL: Final[str] = """
update graph_thread_registry
   set cognitive_revision = %s,
       last_checkpoint_ns = %s,
       last_checkpoint_id = %s,
       updated_at = clock_timestamp()
 where thread_id = %s
   and room_epoch = %s
   and graph_key = %s
   and graph_version = %s
   and checkpoint_schema_version = %s
   and lifecycle_status = 'ACTIVE'
   and (
       cognitive_revision = %s
       or (
           cognitive_revision = %s
           and (
               (
                   last_checkpoint_ns is not distinct from %s
                   and last_checkpoint_id = %s
               )
               or (
                   last_checkpoint_ns is not distinct from %s
                   and last_checkpoint_id = %s
               )
           )
       )
   )
returning cognitive_revision, last_checkpoint_ns, last_checkpoint_id
"""


def create_graph_pool(
    connection_string: str,
    config: GraphPoolConfig | None = None,
) -> AsyncConnectionPool:
    """Create a closed, bounded pool. The application lifespan owns open/close."""

    if not connection_string:
        raise ValueError("Graph runtime connection string is required")
    selected = config or GraphPoolConfig()
    options = " ".join(
        (
            f"-csearch_path={selected.schema},pg_catalog,pg_temp",
            f"-cstatement_timeout={selected.statement_timeout_ms}",
            f"-clock_timeout={selected.lock_timeout_ms}",
            f"-cidle_in_transaction_session_timeout={selected.idle_in_transaction_timeout_ms}",
        )
    )
    return AsyncConnectionPool(
        conninfo=connection_string,
        min_size=selected.min_size,
        max_size=selected.max_size,
        max_waiting=selected.max_waiting,
        timeout=selected.acquire_timeout_seconds,
        max_idle=selected.max_idle_seconds,
        max_lifetime=selected.max_lifetime_seconds,
        reconnect_timeout=selected.reconnect_timeout_seconds,
        open=False,
        check=AsyncConnectionPool.check_connection,
        name="graph-runtime",
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            "row_factory": dict_row,
            "connect_timeout": selected.connect_timeout_seconds,
            "application_name": selected.application_name,
            "options": options,
        },
    )


def bind_fence_context(config: RunnableConfig, fence: GraphFenceContext) -> RunnableConfig:
    """Attach a non-JSON Python capability after signed-command validation."""

    configurable = dict(config.get("configurable") or {})
    existing = configurable.get(FENCE_CONTEXT_KEY)
    if existing is not None and existing != fence:
        raise GraphBindingError("RunnableConfig already carries another Graph fence")
    thread_id = configurable.get("thread_id")
    if thread_id is not None and thread_id != fence.thread_id:
        raise GraphBindingError("RunnableConfig thread_id conflicts with Graph fence")
    configurable["thread_id"] = fence.thread_id
    configurable[FENCE_CONTEXT_KEY] = fence
    bound = dict(config)
    bound["configurable"] = configurable
    return bound


def bind_terminal_result_context(
    config: RunnableConfig,
    materializer: TerminalResultMaterializer,
) -> RunnableConfig:
    """Attach a typed terminal result capability for the saver transaction only."""

    if type(materializer) is not TerminalResultMaterializer:
        raise GraphBindingError("terminal result capability has an invalid type")
    configurable = dict(config.get("configurable") or {})
    existing = configurable.get(TERMINAL_RESULT_CONTEXT_KEY)
    if existing is not None and existing != materializer:
        raise GraphBindingError("RunnableConfig already carries another terminal result")
    configurable[TERMINAL_RESULT_CONTEXT_KEY] = materializer
    bound = dict(config)
    bound["configurable"] = configurable
    return bound


@dataclass(frozen=True, slots=True)
class TerminalResultMaterializer:
    """Pure immutable projection evaluated only after the database fence is locked."""

    thread_id: str
    request_hash: str
    draft: TerminalDraft
    bindings: ResultBindings
    target_proposal_source: TargetE2ERoomProposalSource | None = None

    def __post_init__(self) -> None:
        try:
            draft = TERMINAL_DRAFT_ADAPTER.validate_python(self.draft)
        except ValueError as error:
            raise TypeError("terminal result materializer draft is invalid") from error
        if not isinstance(self.bindings, ResultBindings):
            raise TypeError("terminal result materializer bindings are invalid")
        if self.target_proposal_source is not None and not isinstance(
            self.target_proposal_source,
            TargetE2ERoomProposalSource,
        ):
            raise TypeError("target-E2E proposal source is invalid")
        object.__setattr__(self, "draft", draft)

    def materialize(
        self,
        checkpoint_ns: str,
        checkpoint_id: str,
        *,
        fence: GraphFenceContext | None = None,
    ) -> ResultRecord:
        if len(checkpoint_ns) > 128 or not checkpoint_id or len(checkpoint_id) > 128:
            raise GraphBindingError("terminal checkpoint identity is invalid")
        bindings = self.bindings.model_copy(update={"checkpoint_id": checkpoint_id})
        result = project_room_graph_result(self.draft, bindings)
        result_json = result.model_dump(mode="json", exclude_none=True)
        result_ref = f"urn:after-sale-flow:graph-result:{result.output_hash}"
        record = ResultRecord(
            result_id=f"result.{result.output_hash[:32]}",
            thread_id=self.thread_id,
            command_id=bindings.command_id,
            request_hash=self.request_hash,
            result_schema_version=result.schema_version,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=result.cognitive_revision,
            terminal_status=result.status,
            result_json=result_json,
            result_ref=result_ref,
            result_hash=result.output_hash,
            usage_json=result.usage.model_dump(mode="json"),
        )
        if fence is None or fence.execution_lane is GraphGatewayMode.SHADOW:
            if self.target_proposal_source is not None:
                raise GraphBindingError("SHADOW terminal result cannot carry target proposal")
            return record
        if fence.execution_lane is not GraphGatewayMode.TARGET_E2E_CANDIDATE:
            raise GraphBindingError("terminal result has an invalid execution lane")
        proposal_source = self.target_proposal_source
        if proposal_source is None:
            raise GraphBindingError("candidate terminal result requires a proposal source")
        try:
            proposal_source.require_result_binding(result)
            envelope = build_target_e2e_result_envelope(
                result,
                activation_id=fence.activation_id or "",
                room_fencing_token=fence.room_fencing_token or 0,
                command_hash=fence.command_hash or "",
                command_envelope_hash=fence.command_envelope_hash or "",
                proposal_hash=proposal_source.proposal_hash,
            )
            envelope.require_proposal_hash(
                proposal_source.proposal.model_dump(mode="json")
            )
        except ValueError as error:
            raise GraphBindingError("candidate terminal result binding is invalid") from error
        return replace(
            record,
            execution_lane=fence.execution_lane,
            activation_id=fence.activation_id,
            room_fencing_token=fence.room_fencing_token,
            command_hash=fence.command_hash,
            command_envelope_hash=fence.command_envelope_hash,
            proposal_hash=envelope.proposal_hash,
            result_envelope_hash=envelope.result_envelope_hash,
            proposal_source_json=proposal_source.model_dump(mode="json"),
            result_envelope_json=envelope.model_dump(mode="json", exclude_none=True),
        )


@dataclass(frozen=True, slots=True)
class ExternalTerminalCommit:
    """Typed generic result publication against an already durable terminal checkpoint."""

    result: ResultRecord
    cognitive_revision: int

    def __post_init__(self) -> None:
        if not isinstance(self.result, ResultRecord):
            raise TypeError("external terminal commit result is invalid")
        if (
            not isinstance(self.cognitive_revision, int)
            or isinstance(self.cognitive_revision, bool)
            or self.cognitive_revision < 1
            or self.result.cognitive_revision != self.cognitive_revision
        ):
            raise TypeError("external terminal commit revision is invalid")


class FencedPostgresSaver(BaseCheckpointSaver[Any]):
    """Async saver that atomically checks the durable lease before every write.

    A pool-backed ``AsyncPostgresSaver`` is used only for reads. Writes always create a direct saver
    over the already checked connection inside the same explicit transaction.
    """

    def __init__(
        self,
        pool: AsyncConnectionPool,
        *,
        acquire_timeout_seconds: float = 3.0,
        reader: BaseCheckpointSaver[Any] | None = None,
        direct_saver_factory: Callable[[Any, Any], AsyncPostgresSaver] | None = None,
        ledger: PostgresCommandLedger | None = None,
    ) -> None:
        super().__init__()
        self._pool = pool
        self._acquire_timeout_seconds = acquire_timeout_seconds
        self._reader = reader or AsyncPostgresSaver(pool, serde=self.serde)
        self._direct_saver_factory = direct_saver_factory or (
            lambda connection, serde: AsyncPostgresSaver(connection, serde=serde)
        )
        self._ledger = ledger or PostgresCommandLedger()

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        fence = self._require_fence(config)
        found = await self._reader.aget_tuple(config)
        if found is None:
            return None
        self._validate_checkpoint_tuple(found, fence)
        return self._bind_tuple(found, fence)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        if config is None:
            raise GraphBindingError("runtime checkpoint listing requires a trusted thread fence")
        fence = self._require_fence(config)
        if before is not None:
            before_fence = self._require_fence(before)
            if before_fence != fence:
                raise GraphBindingError("checkpoint list cursor belongs to another fence")
        async for item in self._reader.alist(
            config,
            filter=filter,
            before=before,
            limit=limit,
        ):
            self._validate_checkpoint_tuple(item, fence)
            yield self._bind_tuple(item, fence)

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        fence = self._require_fence(config)
        materializer = self._terminal_materializer(config)
        async with self._connection() as connection:
            async with connection.transaction():
                await self._lock_fence(connection, fence)
                cognitive_revision = self._checkpoint_revision(checkpoint)
                terminal_result, checkpoint_to_save = self._materialize_terminal_result(
                    config,
                    checkpoint,
                    new_versions,
                    materializer,
                )
                effective_fence = self._terminal_fence(fence, terminal_result)
                bound_metadata = self._bind_metadata(
                    metadata,
                    effective_fence,
                    cognitive_revision,
                )
                saver = self._direct_saver_factory(connection, self.serde)
                saved = await saver.aput(
                    config,
                    checkpoint_to_save,
                    bound_metadata,
                    new_versions,
                )
                checkpoint_config = saved.get("configurable") or {}
                checkpoint_ns = str(checkpoint_config.get("checkpoint_ns") or "")
                checkpoint_id = str(checkpoint_config.get("checkpoint_id") or "")
                if not checkpoint_id or len(checkpoint_id) > 128 or len(checkpoint_ns) > 128:
                    raise GraphBindingError("PostgresSaver returned an invalid checkpoint identity")
                if terminal_result is not None and (
                    terminal_result.checkpoint_ns != checkpoint_ns
                    or terminal_result.checkpoint_id != checkpoint_id
                ):
                    raise GraphBindingError(
                        "terminal result conflicts with the saved checkpoint identity"
                    )
                await self._bind_command_checkpoint(
                    connection,
                    effective_fence,
                    checkpoint_ns=checkpoint_ns,
                    checkpoint_id=checkpoint_id,
                )
                if self._checkpoint_has_applied_revision(checkpoint_to_save):
                    parent = config.get("configurable") or {}
                    parent_checkpoint_ns = parent.get("checkpoint_ns", "")
                    parent_checkpoint_id = parent.get("checkpoint_id")
                    if (
                        not isinstance(parent_checkpoint_ns, str)
                        or len(parent_checkpoint_ns) > 128
                        or (
                            parent_checkpoint_id is not None
                            and (
                                not isinstance(parent_checkpoint_id, str)
                                or not parent_checkpoint_id
                                or len(parent_checkpoint_id) > 128
                            )
                        )
                    ):
                        raise GraphBindingError("checkpoint parent identity is invalid")
                    await self._advance_thread_checkpoint(
                        connection,
                        effective_fence,
                        cognitive_revision=cognitive_revision,
                        checkpoint_ns=checkpoint_ns,
                        checkpoint_id=checkpoint_id,
                        parent_checkpoint_ns=parent_checkpoint_ns,
                        parent_checkpoint_id=parent_checkpoint_id,
                    )
                if terminal_result is not None:
                    await self._ledger.store_terminal_result(
                        connection,
                        fence=effective_fence,
                        result=terminal_result,
                        expected_result_schema_version=ROOM_GRAPH_RESULT_SCHEMA_VERSION,
                    )
        return bind_fence_context(
            self._without_terminal_result_context(saved),
            effective_fence,
        )

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        fence = self._require_fence(config)
        async with self._connection() as connection:
            async with connection.transaction():
                await self._lock_fence(connection, fence)
                await self._validate_pending_write_target(connection, config, fence)
                saver = self._direct_saver_factory(connection, self.serde)
                await saver.aput_writes(config, writes, task_id, task_path)

    async def avalidate_external_terminal_checkpoint(
        self,
        config: RunnableConfig,
        *,
        cognitive_revision: int,
    ) -> None:
        """Reject stale storage writers before they create an immutable external object."""

        fence = self._require_fence(config)
        async with self._connection() as connection:
            async with connection.transaction():
                await self._lock_fence(connection, fence)
                await self._lock_external_terminal_checkpoint(
                    connection,
                    config,
                    fence,
                    cognitive_revision=cognitive_revision,
                )

    async def acommit_external_terminal(
        self,
        config: RunnableConfig,
        commit: ExternalTerminalCommit,
    ) -> RunnableConfig:
        """Atomically publish a generic result without rewriting domain-owned checkpoint state."""

        if type(commit) is not ExternalTerminalCommit:
            raise GraphBindingError("external terminal commit capability has an invalid type")
        fence = self._require_fence(config)
        result = commit.result
        configurable = config.get("configurable") or {}
        checkpoint_ns = str(configurable.get("checkpoint_ns") or "")
        checkpoint_id = str(configurable.get("checkpoint_id") or "")
        if (
            result.thread_id != fence.thread_id
            or result.command_id != fence.command_id
            or result.request_hash != fence.request_hash
            or result.checkpoint_ns != checkpoint_ns
            or result.checkpoint_id != checkpoint_id
        ):
            raise GraphBindingError(
                "external terminal result differs from its exact checkpoint fence"
            )
        effective_fence = self._terminal_fence(fence, result)
        async with self._connection() as connection:
            async with connection.transaction():
                await self._lock_fence(connection, fence)
                checkpoint_is_terminal_bound = await self._lock_external_terminal_checkpoint(
                    connection,
                    config,
                    fence,
                    cognitive_revision=commit.cognitive_revision,
                    terminal_fence=effective_fence,
                )
                if not checkpoint_is_terminal_bound:
                    await self._bind_external_terminal_checkpoint_metadata(
                        connection,
                        checkpoint_ns=checkpoint_ns,
                        checkpoint_id=checkpoint_id,
                        fence=effective_fence,
                        cognitive_revision=commit.cognitive_revision,
                    )
                await self._bind_command_checkpoint(
                    connection,
                    effective_fence,
                    checkpoint_ns=checkpoint_ns,
                    checkpoint_id=checkpoint_id,
                )
                await self._advance_thread_checkpoint(
                    connection,
                    effective_fence,
                    cognitive_revision=commit.cognitive_revision,
                    checkpoint_ns=checkpoint_ns,
                    checkpoint_id=checkpoint_id,
                    parent_checkpoint_ns=checkpoint_ns,
                    parent_checkpoint_id=checkpoint_id,
                )
                await self._ledger.store_terminal_result(
                    connection,
                    fence=effective_fence,
                    result=result,
                    expected_result_schema_version=ROOM_GRAPH_RESULT_SCHEMA_VERSION,
                )
        rebound = dict(config)
        rebound_configurable = dict(config.get("configurable") or {})
        rebound_configurable.pop(FENCE_CONTEXT_KEY, None)
        rebound["configurable"] = rebound_configurable
        return bind_fence_context(rebound, effective_fence)

    async def adelete_thread(self, thread_id: str) -> None:
        raise GraphFenceError("runtime saver cannot delete Graph threads")

    def get_next_version(self, current: Any | None, channel: None) -> Any:
        return self._reader.get_next_version(current, channel)

    def _connection(self) -> AbstractAsyncContextManager[Any]:
        return self._pool.connection(timeout=self._acquire_timeout_seconds)

    @staticmethod
    def _terminal_materializer(
        config: RunnableConfig,
    ) -> TerminalResultMaterializer | None:
        value = (config.get("configurable") or {}).get(TERMINAL_RESULT_CONTEXT_KEY)
        if value is None:
            return None
        if type(value) is not TerminalResultMaterializer:
            raise GraphBindingError("RunnableConfig has a forged terminal result capability")
        return value

    @staticmethod
    def _materialize_terminal_result(
        config: RunnableConfig,
        checkpoint: Checkpoint,
        new_versions: ChannelVersions,
        materializer: TerminalResultMaterializer | None,
    ) -> tuple[ResultRecord | None, Checkpoint]:
        if materializer is None:
            return None, checkpoint
        configurable = config.get("configurable") or {}
        fence = configurable.get(FENCE_CONTEXT_KEY)
        if not isinstance(fence, GraphFenceContext):
            raise GraphBindingError("terminal result has no trusted Graph fence")
        checkpoint_ns = str(configurable.get("checkpoint_ns") or "")
        checkpoint_id = str(checkpoint.get("id") or "")
        channel_values = checkpoint.get("channel_values")
        channel_versions = checkpoint.get("channel_versions")
        if (
            len(checkpoint_ns) > 128
            or not checkpoint_id
            or len(checkpoint_id) > 128
            or not isinstance(channel_values, dict)
            or not isinstance(channel_versions, dict)
            or "result_json" not in channel_values
            or "result_json" not in new_versions
            or channel_versions.get("result_json") != new_versions.get("result_json")
        ):
            raise GraphBindingError(
                "terminal materialization requires a versioned result_json checkpoint value"
            )
        result = materializer.materialize(
            checkpoint_ns,
            checkpoint_id,
            fence=fence,
        )
        if result.checkpoint_ns != checkpoint_ns or result.checkpoint_id != checkpoint_id:
            raise GraphBindingError("terminal materializer returned another checkpoint identity")
        FencedPostgresSaver._require_terminal_state_matches_result(channel_values, result)
        materialized = dict(checkpoint)
        materialized_values = dict(channel_values)
        materialized_values["result_json"] = dict(result.result_json)
        materialized["channel_values"] = materialized_values
        return result, materialized  # type: ignore[return-value]

    @staticmethod
    def _require_terminal_state_matches_result(
        channel_values: dict[str, Any],
        result: ResultRecord,
    ) -> None:
        if channel_values.get("cognitive_revision") != result.cognitive_revision:
            raise GraphBindingError("terminal result revision differs from its checkpoint state")
        result_json = result.result_json
        expected_draft: dict[str, Any] = {"status": result.terminal_status}
        detail_field = {
            "COMPLETED": None,
            "NEEDS_INPUT": "needs_input",
            "NEEDS_REVIEW": "needs_review",
            "FAILED": "error",
        }.get(result.terminal_status)
        if result.terminal_status not in {
            "COMPLETED",
            "NEEDS_INPUT",
            "NEEDS_REVIEW",
            "FAILED",
        }:
            raise GraphBindingError("terminal result status is invalid")
        if detail_field is not None:
            detail = result_json.get(detail_field)
            if not isinstance(detail, dict):
                raise GraphBindingError("terminal result detail is missing")
            expected_draft[detail_field] = detail
        if channel_values.get("terminal_draft") != expected_draft:
            raise GraphBindingError("terminal result draft differs from its checkpoint state")

        usage_by_invocation = channel_values.get("usage_by_invocation")
        if not isinstance(usage_by_invocation, dict):
            raise GraphBindingError("terminal checkpoint usage is not a mapping")
        totals = {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0}
        for usage in usage_by_invocation.values():
            if not isinstance(usage, dict) or set(usage) != set(totals):
                raise GraphBindingError("terminal checkpoint usage is invalid")
            for field in totals:
                value = usage[field]
                if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                    raise GraphBindingError("terminal checkpoint usage is invalid")
                totals[field] += value
            if usage["total_tokens"] != usage["input_tokens"] + usage["output_tokens"]:
                raise GraphBindingError("terminal checkpoint usage is inconsistent")
        if (
            totals["total_tokens"] != totals["input_tokens"] + totals["output_tokens"]
            or dict(result.usage_json) != totals
            or result_json.get("usage") != totals
        ):
            raise GraphBindingError("terminal result usage differs from its checkpoint state")

    @staticmethod
    def _without_terminal_result_context(config: RunnableConfig) -> RunnableConfig:
        configurable = dict(config.get("configurable") or {})
        configurable.pop(TERMINAL_RESULT_CONTEXT_KEY, None)
        sanitized = dict(config)
        sanitized["configurable"] = configurable
        return sanitized

    @staticmethod
    def _terminal_fence(
        fence: GraphFenceContext,
        result: ResultRecord | None,
    ) -> GraphFenceContext:
        if result is None:
            return fence
        if (
            result.thread_id != fence.thread_id
            or result.command_id != fence.command_id
            or result.request_hash != fence.request_hash
        ):
            raise GraphBindingError("terminal result identity conflicts with the Graph fence")
        if fence.result_hash is not None and (
            fence.result_hash != result.result_hash or fence.result_ref != result.result_ref
        ):
            raise GraphBindingError("terminal result conflicts with an existing fence binding")
        return replace(
            fence,
            result_hash=result.result_hash,
            result_ref=result.result_ref,
            proposal_hash=result.proposal_hash,
            result_envelope_hash=result.result_envelope_hash,
        )

    async def _lock_fence(self, connection: Any, fence: GraphFenceContext) -> None:
        if fence.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            await connection.execute(
                DRAIN_EXPIRED_TARGET_E2E_SQL,
                (fence.activation_id,),
            )
        cursor = await connection.execute(
            FENCE_LOCK_SQL,
            (
                fence.thread_id,
                fence.command_id,
                fence.owner_id,
                fence.fencing_token,
            ),
        )
        row = await cursor.fetchone()
        if row is None:
            raise GraphFenceError("Graph lease is stale, expired, released, or cancelled")
        if fence.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            room_row = await (
                await connection.execute(
                    TARGET_E2E_ROOM_FENCE_SQL,
                    (
                        fence.thread_id,
                        fence.command_id,
                        fence.tenant_surrogate,
                        fence.case_id,
                        fence.room_type,
                        fence.activation_id,
                        fence.room_epoch,
                        fence.room_fencing_token,
                        fence.command_hash,
                        fence.command_envelope_hash,
                    ),
                )
            ).fetchone()
            if room_row is None:
                raise GraphFenceError("Java room authority fence is stale")

    async def _bind_command_checkpoint(
        self,
        connection: Any,
        fence: GraphFenceContext,
        *,
        checkpoint_ns: str,
        checkpoint_id: str,
    ) -> None:
        cursor = await connection.execute(
            BIND_CHECKPOINT_SQL,
            (
                fence.result_hash,
                fence.fencing_token,
                checkpoint_ns,
                checkpoint_id,
                fence.result_ref,
                fence.result_hash,
                fence.result_hash,
                fence.thread_id,
                fence.command_id,
                fence.request_hash,
                fence.room_epoch,
                fence.graph_key,
                fence.graph_version,
                fence.checkpoint_schema_version,
                fence.result_hash,
                checkpoint_ns,
                checkpoint_id,
                fence.result_ref,
                fence.result_hash,
            ),
        )
        row = await cursor.fetchone()
        if row is None:
            raise GraphBindingError("checkpoint conflicts with the durable Graph command binding")
        if fence.result_hash is not None and row["result_hash"] != fence.result_hash:
            raise GraphBindingError("terminal checkpoint result hash was not durably bound")

    async def _advance_thread_checkpoint(
        self,
        connection: Any,
        fence: GraphFenceContext,
        *,
        cognitive_revision: int,
        checkpoint_ns: str,
        checkpoint_id: str,
        parent_checkpoint_ns: str,
        parent_checkpoint_id: str | None,
    ) -> None:
        cursor = await connection.execute(
            ADVANCE_THREAD_CHECKPOINT_SQL,
            (
                cognitive_revision,
                checkpoint_ns,
                checkpoint_id,
                fence.thread_id,
                fence.room_epoch,
                fence.graph_key,
                fence.graph_version,
                fence.checkpoint_schema_version,
                cognitive_revision - 1,
                cognitive_revision,
                checkpoint_ns,
                checkpoint_id,
                parent_checkpoint_ns,
                parent_checkpoint_id,
            ),
        )
        row = await cursor.fetchone()
        if row is None or (
            row["cognitive_revision"],
            row["last_checkpoint_ns"],
            row["last_checkpoint_id"],
        ) != (cognitive_revision, checkpoint_ns, checkpoint_id):
            raise GraphBindingError(
                "checkpoint does not advance the durable thread revision exactly once"
            )

    async def _validate_pending_write_target(
        self,
        connection: Any,
        config: RunnableConfig,
        fence: GraphFenceContext,
    ) -> None:
        configurable = config.get("configurable") or {}
        checkpoint_ns = str(configurable.get("checkpoint_ns") or "")
        checkpoint_id = str(configurable.get("checkpoint_id") or "")
        if not checkpoint_id or len(checkpoint_id) > 128 or len(checkpoint_ns) > 128:
            raise GraphBindingError("pending writes require a bounded checkpoint identity")
        cursor = await connection.execute(
            CHECKPOINT_METADATA_SQL,
            (fence.thread_id, checkpoint_ns, checkpoint_id),
        )
        row = await cursor.fetchone()
        if row is None:
            # LangGraph persists task writes before the corresponding checkpoint
            # record. The later aput is fenced and binds the checkpoint metadata
            # atomically, so an unfinished run can leave only unread orphan writes.
            return
        self._validate_checkpoint_metadata(row["metadata"], fence)

    async def _lock_external_terminal_checkpoint(
        self,
        connection: Any,
        config: RunnableConfig,
        fence: GraphFenceContext,
        *,
        cognitive_revision: int,
        terminal_fence: GraphFenceContext | None = None,
    ) -> bool:
        configurable = config.get("configurable") or {}
        checkpoint_ns = str(configurable.get("checkpoint_ns") or "")
        checkpoint_id = str(configurable.get("checkpoint_id") or "")
        if (
            not checkpoint_id
            or len(checkpoint_id) > 128
            or len(checkpoint_ns) > 128
            or not isinstance(cognitive_revision, int)
            or isinstance(cognitive_revision, bool)
            or cognitive_revision < 1
        ):
            raise GraphBindingError("external terminal checkpoint identity is invalid")
        row = await (
            await connection.execute(
                LOCK_TERMINAL_CHECKPOINT_SQL,
                (fence.thread_id, checkpoint_ns, checkpoint_id),
            )
        ).fetchone()
        if row is None:
            raise GraphBindingError("external terminal checkpoint does not exist")
        if terminal_fence is None:
            self._validate_exact_checkpoint_metadata(
                row["metadata"],
                fence,
                cognitive_revision=cognitive_revision,
            )
            return False
        return self._validate_external_terminal_checkpoint_metadata(
            row["metadata"],
            fence,
            terminal_fence,
            cognitive_revision=cognitive_revision,
        )

    async def _bind_external_terminal_checkpoint_metadata(
        self,
        connection: Any,
        *,
        checkpoint_ns: str,
        checkpoint_id: str,
        fence: GraphFenceContext,
        cognitive_revision: int,
    ) -> None:
        cursor = await connection.execute(
            BIND_EXTERNAL_TERMINAL_METADATA_SQL,
            (
                fence.result_hash,
                fence.result_ref,
                fence.proposal_hash,
                fence.result_envelope_hash,
                fence.thread_id,
                checkpoint_ns,
                checkpoint_id,
            ),
        )
        row = await cursor.fetchone()
        if row is None:
            raise GraphBindingError("terminal checkpoint metadata was not durably bound")
        bound = self._validate_external_terminal_checkpoint_metadata(
            row["metadata"],
            replace(
                fence,
                result_hash=None,
                result_ref=None,
                proposal_hash=None,
                result_envelope_hash=None,
            ),
            fence,
            cognitive_revision=cognitive_revision,
        )
        if not bound:
            raise GraphBindingError("terminal checkpoint metadata was not durably bound")

    @classmethod
    def _validate_external_terminal_checkpoint_metadata(
        cls,
        metadata: Any,
        fence: GraphFenceContext,
        terminal_fence: GraphFenceContext,
        *,
        cognitive_revision: int,
    ) -> bool:
        """Accept only the original metadata or its exact terminal-fence projection."""

        if not isinstance(metadata, dict):
            raise GraphBindingError("checkpoint metadata is not an object")
        terminal_fields = (
            "graph_result_hash",
            "graph_result_ref",
            "graph_proposal_hash",
            "graph_result_envelope_hash",
        )
        original = fence.checkpoint_metadata()
        expected = terminal_fence.checkpoint_metadata()
        normalized = dict(metadata)
        for field in terminal_fields:
            normalized[field] = original[field]
        cls._validate_exact_checkpoint_metadata(
            normalized,
            fence,
            cognitive_revision=cognitive_revision,
        )
        actual_terminal = {field: metadata.get(field) for field in terminal_fields}
        original_terminal = {field: original[field] for field in terminal_fields}
        expected_terminal = {field: expected[field] for field in terminal_fields}
        if actual_terminal == original_terminal:
            return False
        if actual_terminal == expected_terminal:
            return True
        raise GraphBindingError("terminal checkpoint metadata conflicts with its result fence")

    @staticmethod
    def _require_fence(config: RunnableConfig) -> GraphFenceContext:
        configurable = config.get("configurable") or {}
        fence = configurable.get(FENCE_CONTEXT_KEY)
        if not isinstance(fence, GraphFenceContext):
            raise GraphBindingError("RunnableConfig has no trusted GraphFenceContext capability")
        if configurable.get("thread_id") != fence.thread_id:
            raise GraphBindingError("RunnableConfig thread_id conflicts with Graph fence")
        return fence

    @staticmethod
    def _bind_metadata(
        metadata: CheckpointMetadata,
        fence: GraphFenceContext,
        cognitive_revision: int,
    ) -> CheckpointMetadata:
        bound = dict(metadata)
        expected = {
            **fence.checkpoint_metadata(),
            "graph_cognitive_revision": cognitive_revision,
        }
        for key, value in expected.items():
            if key in bound and bound[key] != value:
                raise GraphBindingError(f"checkpoint metadata conflicts at {key}")
            bound[key] = value
        return bound  # type: ignore[return-value]

    @staticmethod
    def _checkpoint_revision(checkpoint: Checkpoint) -> int:
        values = checkpoint.get("channel_values")
        revision: Any = None
        if isinstance(values, dict):
            revision = values.get("cognitive_revision")
            pending_input = values.get("__start__")
            if revision is None and isinstance(pending_input, Mapping):
                revision = pending_input.get("cognitive_revision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            raise GraphBindingError(
                "every Graph checkpoint must carry a positive cognitive revision"
            )
        return revision

    @staticmethod
    def _checkpoint_has_applied_revision(checkpoint: Checkpoint) -> bool:
        values = checkpoint.get("channel_values")
        return isinstance(values, dict) and "cognitive_revision" in values

    @classmethod
    def _validate_checkpoint_tuple(
        cls,
        item: CheckpointTuple,
        fence: GraphFenceContext,
    ) -> None:
        configurable = item.config.get("configurable") or {}
        if configurable.get("thread_id") != fence.thread_id:
            raise GraphBindingError("checkpoint tuple belongs to another Graph thread")
        cls._validate_checkpoint_metadata(item.metadata, fence)

    @staticmethod
    def _validate_checkpoint_metadata(
        metadata: Any,
        fence: GraphFenceContext,
    ) -> None:
        if not isinstance(metadata, dict):
            raise GraphBindingError("checkpoint metadata is not an object")
        base_expected = {
            "graph_thread_id": fence.thread_id,
            "graph_room_epoch": fence.room_epoch,
            "graph_key": fence.graph_key,
            "graph_version": fence.graph_version,
            "graph_checkpoint_schema_version": fence.checkpoint_schema_version,
        }
        candidate_expected = {
            "graph_execution_lane": fence.execution_lane.value,
            "graph_activation_id": fence.activation_id,
            "graph_room_fencing_token": fence.room_fencing_token,
            "graph_command_hash": fence.command_hash,
            "graph_command_envelope_hash": fence.command_envelope_hash,
            "graph_environment_id": fence.environment_id,
            "graph_environment_generation": fence.environment_generation,
            "graph_tenant_surrogate": fence.tenant_surrogate,
            "graph_case_id": fence.case_id,
            "graph_room_type": fence.room_type,
            "graph_binding_hash": fence.binding_hash,
            "graph_code_build_id": fence.code_build_id,
            "graph_proposal_hash": fence.proposal_hash,
            "graph_result_envelope_hash": fence.result_envelope_hash,
        }
        for key, value in base_expected.items():
            if metadata.get(key) != value:
                raise GraphBindingError(f"checkpoint metadata conflicts at {key}")
        if fence.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            for key, value in candidate_expected.items():
                if metadata.get(key) != value:
                    raise GraphBindingError(f"checkpoint metadata conflicts at {key}")
        else:
            for key, value in candidate_expected.items():
                if key in metadata and metadata[key] != value:
                    raise GraphBindingError(f"checkpoint metadata conflicts at {key}")
        command_id = metadata.get("graph_command_id")
        request_hash = metadata.get("graph_request_hash")
        fencing_token = metadata.get("graph_fencing_token")
        if not isinstance(command_id, str) or not command_id or len(command_id) > 128:
            raise GraphBindingError("checkpoint metadata has an invalid command identity")
        if (
            not isinstance(request_hash, str)
            or len(request_hash) != 64
            or any(character not in "0123456789abcdef" for character in request_hash)
        ):
            raise GraphBindingError("checkpoint metadata has an invalid request hash")
        if not isinstance(fencing_token, int) or isinstance(fencing_token, bool):
            raise GraphBindingError("checkpoint metadata has an invalid fencing token")
        if fencing_token < 1:
            raise GraphBindingError("checkpoint metadata has an invalid fencing token")
        cognitive_revision = metadata.get("graph_cognitive_revision")
        if (
            not isinstance(cognitive_revision, int)
            or isinstance(cognitive_revision, bool)
            or cognitive_revision < 1
        ):
            raise GraphBindingError("checkpoint metadata has an invalid cognitive revision")
        result_hash = metadata.get("graph_result_hash")
        result_ref = metadata.get("graph_result_ref")
        if (result_hash is None) != (result_ref is None):
            raise GraphBindingError("checkpoint metadata has an incomplete result binding")

    @classmethod
    def _validate_exact_checkpoint_metadata(
        cls,
        metadata: Any,
        fence: GraphFenceContext,
        *,
        cognitive_revision: int,
    ) -> None:
        cls._validate_checkpoint_metadata(metadata, fence)
        exact = {
            "graph_command_id": fence.command_id,
            "graph_request_hash": fence.request_hash,
            "graph_fencing_token": fence.fencing_token,
            "graph_cognitive_revision": cognitive_revision,
        }
        if any(metadata.get(key) != value for key, value in exact.items()):
            raise GraphBindingError(
                "external terminal checkpoint differs from the active command fence"
            )

    @staticmethod
    def _bind_tuple(item: CheckpointTuple, fence: GraphFenceContext) -> CheckpointTuple:
        config = bind_fence_context(item.config, fence)
        parent = (
            None if item.parent_config is None else bind_fence_context(item.parent_config, fence)
        )
        return item._replace(config=config, parent_config=parent)


@dataclass(slots=True)
class GraphCheckpointRuntime:
    """Process-lifetime pool/saver pair for FastAPI lifespan integration."""

    pool: AsyncConnectionPool
    saver: FencedPostgresSaver
    close_timeout_seconds: float = 5.0

    @classmethod
    async def open(
        cls,
        connection_string: str,
        config: GraphPoolConfig | None = None,
    ) -> "GraphCheckpointRuntime":
        selected = config or GraphPoolConfig()
        pool = create_graph_pool(connection_string, selected)
        try:
            await pool.open(wait=True, timeout=selected.acquire_timeout_seconds)
        except BaseException:
            await pool.close(timeout=selected.acquire_timeout_seconds)
            raise
        return cls(
            pool=pool,
            saver=FencedPostgresSaver(
                pool,
                acquire_timeout_seconds=selected.acquire_timeout_seconds,
            ),
        )

    async def close(self) -> None:
        await self.pool.close(timeout=self.close_timeout_seconds)
