from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt
from typing_extensions import TypedDict

from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import ExecutionMetadata, RoomGraphCommand, Usage
from app.graph_runtime.checkpoint import (
    FENCE_CONTEXT_KEY,
    FencedPostgresSaver,
    TERMINAL_RESULT_CONTEXT_KEY,
    TerminalResultMaterializer,
    bind_fence_context,
)
from app.graph_runtime.compiled_executor import (
    CompiledGraphShadowExecutor,
    GraphPublicUpdate,
    TerminalResultPlan,
)
from app.graph_runtime.errors import GraphContractError, GraphTerminalBindingError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.result import CompletedDraft, ResultBindings
from app.graph_runtime.state import CommonGraphState


ROOT = Path(__file__).resolve().parents[4]
COMMAND_FIXTURE = ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-command-valid.json"


def _command() -> RoomGraphCommand:
    return RoomGraphCommand.model_validate(
        json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))["instance"]
    )


def _execution() -> GatewayExecution:
    command = _command()
    registry_binding = SimpleNamespace(
        state_schema_version="intake.state.v2",
        tool_policy_version="intake.tools.v1",
    )
    fence = GraphFenceContext(
        thread_id=command.thread_id,
        command_id=command.command_id,
        owner_id="worker-1",
        fencing_token=1,
        request_hash=command.request_hash,
        room_epoch=command.room_epoch,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )
    return GatewayExecution(
        admission=cast(
            Any,
            SimpleNamespace(
                command=command,
                registry=SimpleNamespace(binding=registry_binding),
            ),
        ),
        attempt=cast(Any, object()),
        lease=cast(Any, object()),
        fence=fence,
    )


def _state() -> dict[str, Any]:
    command = _command()
    invocation = command.invocation_context
    return {
        "bindings": {
            "schema_version": "graph-command-binding.v1",
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "tenant_surrogate": command.tenant_surrogate,
            "case_id": command.case_id,
            "room_type": command.room_type,
            "room_epoch": command.room_epoch,
            "actor_scope_hash": canonical_sha256(command.actor_scope.model_dump(mode="json")),
            "thread_id": command.thread_id,
        },
        "version_pins": {
            "schema_version": "graph-version-pins.v1",
            "graph_key": command.graph_key,
            "graph_version": command.graph_version,
            "checkpoint_schema_version": command.checkpoint_schema_version,
            "state_schema_version": "intake.state.v2",
            "prompt_version": invocation.prompt_profile_id,
            "model_profile_id": invocation.model_profile_id,
            "output_schema_version": invocation.output_schema_version,
            "policy_version": invocation.policy_version,
            "guardrail_version": invocation.guardrail_version,
            "tool_policy_version": "intake.tools.v1",
        },
        "messages": {},
        "work_items": {},
        "work_results": {},
        "artifact_refs": {},
        "node_results": {},
        "execution_receipts": {},
        "usage_by_invocation": {
            "invocation-1": {"input_tokens": 2, "output_tokens": 1, "total_tokens": 3}
        },
        "cognitive_revision": 1,
        "terminal_draft": {"status": "COMPLETED"},
    }


def _plan(execution: GatewayExecution, state: dict[str, Any]) -> TerminalResultPlan:
    command = execution.admission.command
    invocation = command.invocation_context
    return TerminalResultPlan(
        draft=CompletedDraft.model_validate(state["terminal_draft"]),
        bindings=ResultBindings(
            command_id=command.command_id,
            logical_run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            graph_key=command.graph_key,
            graph_version=command.graph_version,
            checkpoint_id="pending",
            cognitive_revision=state["cognitive_revision"],
            public_event_proposals=(),
            artifact_operations=(),
            usage=Usage.model_validate(state["usage_by_invocation"]["invocation-1"]),
            execution_metadata=ExecutionMetadata(
                prompt_version=invocation.prompt_profile_id,
                model_profile_id=invocation.model_profile_id,
                schema_version=invocation.output_schema_version,
                policy_version=invocation.policy_version,
                guardrail_version=invocation.guardrail_version,
            ),
        ),
    )


class _Graph:
    def __init__(
        self,
        saver: Any,
        updates: list[Any] | None = None,
        *,
        next_nodes: tuple[str, ...] = (),
        tasks: tuple[Any, ...] = (),
        interrupts: tuple[Any, ...] = (),
        snapshot_namespace: str | None = None,
    ) -> None:
        self.checkpointer = saver
        self.updates = updates or [
            GraphPublicUpdate.visible_delta(
                node="test_node",
                field="answer",
                delta="visible",
            ),
            GraphPublicUpdate.usage(Usage(input_tokens=2, output_tokens=1, total_tokens=3)),
        ]
        self.state = _state()
        self.parent_config: dict[str, Any] | None = None
        self.final_config: dict[str, Any] | None = None
        self.final_state: dict[str, Any] | None = None
        self.update_values: dict[str, Any] | None = None
        self.next_nodes = next_nodes
        self.tasks = tasks
        self.interrupts = interrupts
        self.snapshot_namespace = snapshot_namespace
        self.stream_closed = False

    async def astream(
        self,
        input: dict[str, Any],
        config: dict[str, Any],
        *,
        stream_mode: str,
    ):
        assert stream_mode == "custom"
        assert config["configurable"][FENCE_CONTEXT_KEY] == _execution().fence
        self.state = dict(input)
        self.parent_config = {
            "configurable": {
                **config["configurable"],
                "checkpoint_ns": (
                    config["configurable"]["checkpoint_ns"]
                    if self.snapshot_namespace is None
                    else self.snapshot_namespace
                ),
                "checkpoint_id": "cp-parent",
            }
        }
        try:
            for update in self.updates:
                yield update
        finally:
            self.stream_closed = True

    async def aget_state(self, config: dict[str, Any]) -> Any:
        if self.final_config is not None and config == self.final_config:
            return SimpleNamespace(
                values=self.final_state,
                config=self.final_config,
                next=(),
                tasks=(),
                interrupts=(),
            )
        assert self.parent_config is not None
        return SimpleNamespace(
            values=self.state,
            config=self.parent_config,
            next=self.next_nodes,
            tasks=self.tasks,
            interrupts=self.interrupts,
        )

    async def aupdate_state(
        self,
        config: dict[str, Any],
        values: dict[str, Any],
        *,
        as_node: str,
    ) -> dict[str, Any]:
        assert as_node == "project_result"
        self.update_values = values
        materializer = config["configurable"][TERMINAL_RESULT_CONTEXT_KEY]
        assert isinstance(materializer, TerminalResultMaterializer)
        checkpoint_ns = config["configurable"].get("checkpoint_ns", "")
        result = materializer.materialize(checkpoint_ns, "cp-final")
        source_fence = config["configurable"][FENCE_CONTEXT_KEY]
        effective_fence = replace(
            source_fence,
            result_hash=result.result_hash,
            result_ref=result.result_ref,
        )
        self.final_config = {
            "configurable": {
                "thread_id": source_fence.thread_id,
                "checkpoint_ns": checkpoint_ns,
                "checkpoint_id": "cp-final",
                FENCE_CONTEXT_KEY: effective_fence,
            }
        }
        self.final_state = {**self.state, "result_json": dict(result.result_json)}
        return self.final_config


@pytest.mark.asyncio
async def test_compiled_executor_streams_only_typed_updates_and_checkpointed_final() -> None:
    saver = cast(Any, object())
    graph = _Graph(saver)
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: _state(),
        terminal_plan=_plan,
    )

    events = [event async for event in executor.stream(_execution())]

    assert [event.event_type for event in events] == [
        "attempt_started",
        "visible_delta",
        "usage",
        "final",
    ]
    assert events[1].payload.delta == "visible"
    assert events[-1].payload.final_result_hash == graph.final_state["result_json"]["output_hash"]
    assert graph.update_values == {"result_json": {"status": "PENDING_TERMINAL_COMMIT"}}
    assert TERMINAL_RESULT_CONTEXT_KEY not in graph.final_config["configurable"]
    assert graph.stream_closed is True


def test_compiled_executor_requires_the_runtime_fenced_saver_instance() -> None:
    with pytest.raises(GraphContractError, match="process fenced saver"):
        CompiledGraphShadowExecutor(
            graph=cast(Any, _Graph(object())),
            saver=cast(Any, object()),
            initial_state=lambda execution: _state(),
            terminal_plan=_plan,
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("field", ["bindings", "version_pins"])
async def test_compiled_executor_rejects_untrusted_initial_state_identity(field: str) -> None:
    saver = cast(Any, object())
    graph = _Graph(saver)
    initial = _state()
    initial[field] = {**initial[field], "schema_version": "forged.v1"}
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: initial,
        terminal_plan=_plan,
    )

    with pytest.raises(GraphContractError, match="binding|version pins"):
        _ = [event async for event in executor.stream(_execution())]

    assert graph.parent_config is None
    assert graph.update_values is None


@pytest.mark.asyncio
async def test_compiled_executor_rejects_untyped_custom_graph_output() -> None:
    saver = cast(Any, object())
    graph = _Graph(saver, updates=[{"event_type": "visible_delta"}])
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: _state(),
        terminal_plan=_plan,
    )

    with pytest.raises(GraphContractError, match="untyped public update"):
        _ = [event async for event in executor.stream(_execution())]

    assert graph.update_values is None
    assert graph.stream_closed is True


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("next_nodes", "tasks", "interrupts"),
    [
        (("pause",), (), ()),
        ((), (object(),), ()),
        ((), (), (object(),)),
    ],
)
async def test_compiled_executor_never_terminalizes_pending_graph_work(
    next_nodes: tuple[str, ...],
    tasks: tuple[Any, ...],
    interrupts: tuple[Any, ...],
) -> None:
    saver = cast(Any, object())
    graph = _Graph(
        saver,
        next_nodes=next_nodes,
        tasks=tasks,
        interrupts=interrupts,
    )
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: _state(),
        terminal_plan=_plan,
    )

    with pytest.raises(GraphContractError, match="quiescent terminal state"):
        _ = [event async for event in executor.stream(_execution())]

    assert graph.update_values is None
    assert graph.stream_closed is True


@pytest.mark.asyncio
async def test_compiled_executor_rejects_checkpoint_namespace_drift() -> None:
    saver = cast(Any, object())
    graph = _Graph(saver, snapshot_namespace="another-room")
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: _state(),
        terminal_plan=_plan,
    )

    with pytest.raises(GraphContractError, match="trusted fence binding"):
        _ = [event async for event in executor.stream(_execution())]

    assert graph.update_values is None


@pytest.mark.asyncio
async def test_compiled_executor_closes_inner_graph_when_consumer_stops() -> None:
    saver = cast(Any, object())
    graph = _Graph(saver)
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: _state(),
        terminal_plan=_plan,
    )
    stream = executor.stream(_execution())

    assert (await anext(stream)).event_type == "attempt_started"
    assert (await anext(stream)).event_type == "visible_delta"
    assert graph.stream_closed is False
    await stream.aclose()

    assert graph.stream_closed is True
    assert graph.update_values is None


class _PauseState(TypedDict):
    value: int


class _FencePreservingCompiledGraph:
    def __init__(self, graph: Any, saver: Any) -> None:
        self._graph = graph
        self.checkpointer = saver

    def astream(self, input: Any, config: Any, *, stream_mode: str):
        return self._graph.astream(input, config, stream_mode=stream_mode)

    async def aget_state(self, config: Any) -> Any:
        snapshot = await self._graph.aget_state(config)
        fence = config["configurable"][FENCE_CONTEXT_KEY]
        return snapshot._replace(config=bind_fence_context(snapshot.config, fence))

    async def aupdate_state(
        self,
        config: Any,
        values: Any,
        *,
        as_node: str,
    ) -> Any:
        return await self._graph.aupdate_state(config, values, as_node=as_node)


class _MemoryFencedSaver(FencedPostgresSaver):
    """Exercise real LangGraph checkpoint mechanics without a PostgreSQL test token."""

    def __init__(self) -> None:
        BaseCheckpointSaver.__init__(self)
        self._memory = InMemorySaver(serde=self.serde)
        self.terminal_results: list[Any] = []

    async def aget_tuple(self, config: Any) -> Any:
        fence = self._require_fence(config)
        found = await self._memory.aget_tuple(config)
        if found is None:
            return None
        self._validate_checkpoint_tuple(found, fence)
        return self._bind_tuple(found, fence)

    async def alist(self, config: Any, **kwargs: Any):
        fence = self._require_fence(config)
        async for item in self._memory.alist(config, **kwargs):
            self._validate_checkpoint_tuple(item, fence)
            yield self._bind_tuple(item, fence)

    async def aput(
        self,
        config: Any,
        checkpoint: Any,
        metadata: Any,
        new_versions: Any,
    ) -> Any:
        fence = self._require_fence(config)
        materializer = self._terminal_materializer(config)
        result, checkpoint_to_save = self._materialize_terminal_result(
            config,
            checkpoint,
            new_versions,
            materializer,
        )
        effective_fence = self._terminal_fence(fence, result)
        saved = await self._memory.aput(
            self._without_terminal_result_context(config),
            checkpoint_to_save,
            self._bind_metadata(metadata, effective_fence),
            new_versions,
        )
        if result is not None:
            self.terminal_results.append(result)
        return bind_fence_context(saved, effective_fence)

    async def aput_writes(
        self,
        config: Any,
        writes: Any,
        task_id: str,
        task_path: str = "",
    ) -> None:
        self._require_fence(config)
        await self._memory.aput_writes(
            self._without_terminal_result_context(config),
            writes,
            task_id,
            task_path,
        )

    def get_next_version(self, current: Any, channel: Any) -> Any:
        return self._memory.get_next_version(current, channel)


@pytest.mark.asyncio
async def test_real_langgraph_interrupt_cannot_be_mistaken_for_end() -> None:
    def pause(state: _PauseState) -> dict[str, Any]:
        interrupt("wait-for-temporal")
        return {}

    builder = StateGraph(_PauseState)
    builder.add_node("pause", pause)
    builder.add_edge(START, "pause")
    builder.add_edge("pause", END)
    saver = InMemorySaver()
    graph = _FencePreservingCompiledGraph(builder.compile(checkpointer=saver), saver)
    terminal_calls = 0

    def forbidden_terminal_plan(
        execution: GatewayExecution,
        state: dict[str, Any],
    ) -> TerminalResultPlan:
        nonlocal terminal_calls
        terminal_calls += 1
        raise AssertionError("an interrupted graph cannot project a terminal result")

    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=cast(Any, saver),
        initial_state=lambda execution: {**_state(), "value": 1},
        terminal_plan=forbidden_terminal_plan,
    )

    with pytest.raises(GraphContractError, match="quiescent terminal state"):
        _ = [event async for event in executor.stream(_execution())]

    snapshot = await graph.aget_state(
        bind_fence_context(
            {
                "configurable": {
                    "thread_id": _execution().fence.thread_id,
                    "checkpoint_ns": "",
                }
            },
            _execution().fence,
        )
    )
    assert snapshot.next == ("pause",)
    assert snapshot.tasks
    assert snapshot.interrupts
    assert terminal_calls == 0


@pytest.mark.asyncio
async def test_real_langgraph_terminal_update_materializes_the_versioned_result_channel() -> None:
    def finish(state: CommonGraphState) -> dict[str, Any]:
        get_stream_writer()(
            GraphPublicUpdate.usage(Usage(input_tokens=2, output_tokens=1, total_tokens=3))
        )
        return {
            "cognitive_revision": 1,
            "terminal_draft": {"status": "COMPLETED"},
        }

    saver = _MemoryFencedSaver()
    builder = StateGraph(CommonGraphState)
    builder.add_node("finish", finish)
    builder.add_edge(START, "finish")
    builder.add_edge("finish", END)
    graph = builder.compile(checkpointer=saver)
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: _state(),
        terminal_plan=_plan,
        terminal_node="finish",
    )

    events = [event async for event in executor.stream(_execution())]

    assert [event.event_type for event in events] == [
        "attempt_started",
        "usage",
        "final",
    ]
    assert len(saver.terminal_results) == 1
    result = saver.terminal_results[0]
    assert result.result_hash == events[-1].payload.final_result_hash
    assert result.checkpoint_id
    snapshot = await graph.aget_state(
        bind_fence_context(
            {
                "configurable": {
                    "thread_id": _execution().fence.thread_id,
                    "checkpoint_ns": "",
                }
            },
            _execution().fence,
        )
    )
    assert snapshot.values["result_json"] == dict(result.result_json)
    assert snapshot.next == ()
    assert snapshot.tasks == ()
    assert snapshot.interrupts == ()


@pytest.mark.asyncio
@pytest.mark.parametrize("drift", ["revision", "draft", "usage", "public_usage"])
async def test_terminal_plan_must_match_the_quiescent_durable_state(drift: str) -> None:
    saver = cast(Any, object())
    updates = None
    initial = _state()
    if drift == "revision":
        initial["cognitive_revision"] = 2
    elif drift == "draft":
        initial["terminal_draft"] = {
            "status": "FAILED",
            "error": {"code": "STATE_FAILED", "retryable": False},
        }
    elif drift == "usage":
        initial["usage_by_invocation"] = {
            "invocation-1": {"input_tokens": 5, "output_tokens": 1, "total_tokens": 6}
        }
    else:
        updates = [GraphPublicUpdate.usage(Usage(input_tokens=9, output_tokens=1, total_tokens=10))]
    graph = _Graph(saver, updates=updates)
    executor = CompiledGraphShadowExecutor(
        graph=cast(Any, graph),
        saver=saver,
        initial_state=lambda execution: initial,
        terminal_plan=lambda execution, state: _plan(execution, _state()),
    )

    with pytest.raises(GraphTerminalBindingError, match="terminal result"):
        _ = [event async for event in executor.stream(_execution())]

    assert graph.update_values is None


def test_terminal_plan_rejects_profile_or_command_drift() -> None:
    execution = _execution()
    plan = _plan(execution, _state())
    drifted = replace(
        execution,
        admission=cast(
            Any,
            SimpleNamespace(
                command=execution.admission.command.model_copy(
                    update={"command_id": "command-other"}
                )
            ),
        ),
    )

    with pytest.raises(GraphTerminalBindingError, match="signed command"):
        plan.materialize(drifted, "room", "cp-final")
