"""Isolated Graph PostgreSQL pool and lease-fenced LangGraph checkpointer."""

from __future__ import annotations

from collections.abc import AsyncIterator, Callable, Sequence
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass
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
    GraphPoolConfig,
)


FENCE_CONTEXT_KEY: Final[str] = "__trusted_graph_fence_context__"

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

BIND_CHECKPOINT_SQL: Final[str] = """
update agent_graph_command
   set status = case
           when %s is not null then 'RESULT_CHECKPOINTED'
           else status
       end,
       fencing_token = %s,
       committed_checkpoint_ns = %s,
       committed_checkpoint_id = %s,
       result_ref = coalesce(%s, result_ref),
       result_hash = coalesce(%s, result_hash),
       result_checkpointed_at = case
           when %s is not null then coalesce(result_checkpointed_at, clock_timestamp())
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
            f"-csearch_path={selected.schema},pg_catalog",
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
    ) -> None:
        super().__init__()
        self._pool = pool
        self._acquire_timeout_seconds = acquire_timeout_seconds
        self._reader = reader or AsyncPostgresSaver(pool, serde=self.serde)
        self._direct_saver_factory = direct_saver_factory or (
            lambda connection, serde: AsyncPostgresSaver(connection, serde=serde)
        )

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
        bound_metadata = self._bind_metadata(metadata, fence)
        async with self._connection() as connection:
            async with connection.transaction():
                await self._lock_fence(connection, fence)
                saver = self._direct_saver_factory(connection, self.serde)
                saved = await saver.aput(config, checkpoint, bound_metadata, new_versions)
                checkpoint_config = saved.get("configurable") or {}
                checkpoint_ns = str(checkpoint_config.get("checkpoint_ns") or "")
                checkpoint_id = str(checkpoint_config.get("checkpoint_id") or "")
                if not checkpoint_id or len(checkpoint_id) > 128 or len(checkpoint_ns) > 128:
                    raise GraphBindingError("PostgresSaver returned an invalid checkpoint identity")
                await self._bind_command_checkpoint(
                    connection,
                    fence,
                    checkpoint_ns=checkpoint_ns,
                    checkpoint_id=checkpoint_id,
                )
        return bind_fence_context(saved, fence)

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

    async def adelete_thread(self, thread_id: str) -> None:
        raise GraphFenceError("runtime saver cannot delete Graph threads")

    def get_next_version(self, current: Any | None, channel: None) -> Any:
        return self._reader.get_next_version(current, channel)

    def _connection(self) -> AbstractAsyncContextManager[Any]:
        return self._pool.connection(timeout=self._acquire_timeout_seconds)

    async def _lock_fence(self, connection: Any, fence: GraphFenceContext) -> None:
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
            raise GraphBindingError("pending-write checkpoint does not exist")
        self._validate_checkpoint_metadata(row["metadata"], fence)

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
    ) -> CheckpointMetadata:
        bound = dict(metadata)
        for key, value in fence.checkpoint_metadata().items():
            if key in bound and bound[key] != value:
                raise GraphBindingError(f"checkpoint metadata conflicts at {key}")
            bound[key] = value
        return bound  # type: ignore[return-value]

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
        expected = {
            "graph_thread_id": fence.thread_id,
            "graph_room_epoch": fence.room_epoch,
            "graph_key": fence.graph_key,
            "graph_version": fence.graph_version,
            "graph_checkpoint_schema_version": fence.checkpoint_schema_version,
        }
        for key, value in expected.items():
            if metadata.get(key) != value:
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
        result_hash = metadata.get("graph_result_hash")
        result_ref = metadata.get("graph_result_ref")
        if (result_hash is None) != (result_ref is None):
            raise GraphBindingError("checkpoint metadata has an incomplete result binding")

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
