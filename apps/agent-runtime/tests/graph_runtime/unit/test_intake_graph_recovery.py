from __future__ import annotations

import asyncio
import copy
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from app.contracts.v1.codec import canonical_sha256_omitting
import app.graph_runtime.intake_executor as intake_executor
from app.graphs.intake.baseline import BASELINE_INTAKE_NODE_NAME
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.graph import compile_intake_v2_graph
from app.graphs.intake.lcel import build_intake_model_node as _build_intake_model_node
from app.graphs.intake.runtime import (
    build_intake_runtime_bundle as _build_intake_runtime_bundle,
    extract_intake_terminal_proposal,
)
from app.graphs.intake.state import IntakeTurnContext, new_intake_graph_state
from app.graphs.intake.state import IntakeGraphBindings
from app.graph_runtime.state import VersionPinsState
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import prepare_baseline_prompt_authority
from app.harness.prompt_composer import PromptRepository
from app.llm import governed_max_output_tokens
from app.model_runtime.profiles import (
    ModelInvocationPolicy,
    ModelProfile,
    system_prompt_sha256,
)
from app.model_runtime.transports import (
    ModelTransportCompleted,
    ModelTransportRequest,
    ModelTransportResult,
)


ROOT = Path(__file__).resolve().parents[5]
FIXTURES = ROOT / "contracts" / "agent-platform" / "intake" / "v2" / "fixtures" / "valid"


def _fixture(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


@pytest.fixture
def snapshot() -> dict[str, Any]:
    return _fixture("intake-domain-snapshot-valid.json")


@pytest.fixture
def event() -> dict[str, Any]:
    return _fixture("intake-turn-event-valid.json")


@pytest.fixture
def bindings(snapshot: dict[str, Any]) -> IntakeGraphBindings:
    return {
        "schema_version": "intake-graph-bindings.v2",
        "private": {
            "schema_version": "intake-private-binding.v1",
            "tenant_surrogate": snapshot["tenant_surrogate"],
            "case_id": snapshot["case_id"],
            "room_type": "INTAKE",
            "room_epoch": snapshot["room_epoch"],
            "actor_scope_hash": snapshot["actor_scope_hash"],
            "thread_id": snapshot["thread_id"],
            "agent_session_id": snapshot["agent_session_id"],
            "audience": "USER",
        },
        "command": {
            "schema_version": "intake-command-binding.v1",
            "command_id": "COMMAND_P4_USER_1",
            "logical_run_id": "RUN_P4_USER_1",
            "attempt_id": "ATTEMPT_P4_USER_1_1",
        },
    }


@pytest.fixture
def version_pins() -> VersionPinsState:
    return {
        "schema_version": "graph-version-pins.v1",
        "graph_key": "intake.v2",
        "graph_version": "2.0.0",
        "checkpoint_schema_version": "intake-checkpoint.v2",
        "state_schema_version": "intake-graph-state.v2",
        "prompt_version": "DISPUTE_INTAKE_OFFICER:USER:v1",
        "model_profile_id": "intake-model.synthetic.v1",
        "output_schema_version": "intake-turn-proposal.v2",
        "policy_version": "intake-policy.v2",
        "guardrail_version": "intake-guardrail.v2",
        "tool_policy_version": "no-tools.v1",
    }


class RecoveryTransport:
    def __init__(self, *, follow_up_event: dict[str, Any] | None = None) -> None:
        self.generate_calls = 0
        self.requests: list[ModelTransportRequest] = []
        self._follow_up_event = copy.deepcopy(follow_up_event)

    def generate(self, request: ModelTransportRequest) -> ModelTransportResult:
        self.generate_calls += 1
        self.requests.append(request)
        if self.generate_calls == 1:
            document = {
                "room_utterance": (
                    "I have reviewed the submitted form. What resolution would you like "
                    "to request?"
                ),
                "case_detail": {
                    "case_story": {
                        "one_sentence_summary": (
                            "The submitted form reports that the order arrived damaged."
                        )
                    },
                    "requested_resolution": {"kind": "REFUND"},
                    "intake_quality": {"score": 90},
                },
                "unilateral_case_matrix": {
                    "schema_version": "unilateral_case_matrix.draft.v1",
                    "fact_rows": [
                        {
                            "fact_key": "NEW_OPENING_FORM_SUMMARY",
                            "category": "AFTER_SALES",
                            "fact_target": "The submitted form reports a damaged order.",
                            "materiality": "CORE",
                            "position_summary": (
                                "The current party reported that the order arrived damaged."
                            ),
                            "asserted_value": "DAMAGED_ORDER_REPORTED",
                            "source_scope": "CURRENT_SOURCE",
                        }
                    ],
                    "summary_source_fact_keys": ["NEW_OPENING_FORM_SUMMARY"],
                },
                "missing_fields": [],
                "admission_recommendation": "ACCEPTED",
                "knowledge_answer_mode": "NONE",
                "confidence": 0.8,
            }
        else:
            if self._follow_up_event is None:
                raise AssertionError("follow-up baseline response was not configured")
            document = {
                "room_utterance": "Please confirm the requested resolution.",
                "case_detail": {
                    "case_story": {
                        "one_sentence_summary": (
                            "The imported case concerns the requested resolution."
                        )
                    },
                    "requested_resolution": {
                        "kind": "REFUND",
                        "source_refs": [self._follow_up_event["message_id"]],
                        "source_hash": self._follow_up_event["event_hash"],
                    },
                    "intake_quality": {"score": 90},
                },
                "unilateral_case_matrix": {
                    "schema_version": "unilateral_case_matrix.draft.v1",
                    "fact_rows": [
                        {
                            "fact_key": "NEW_REQUESTED_RESOLUTION",
                            "category": "AFTER_SALES",
                            "fact_target": "The requested resolution is a refund.",
                            "materiality": "CORE",
                            "position_summary": "The current party requests a refund.",
                            "asserted_value": "REFUND",
                            "source_scope": "CURRENT_SOURCE",
                        }
                    ],
                    "summary_source_fact_keys": ["NEW_REQUESTED_RESOLUTION"],
                },
                "missing_fields": [],
                "admission_recommendation": "ACCEPTED",
                "knowledge_answer_mode": "NONE",
                "confidence": 0.9,
            }
        return ModelTransportResult(
            json_document=json.dumps(document, separators=(",", ":")),
            model="intake-model",
            latency_ms=3,
            token_usage={"input": 7, "output": 4, "total": 11},
        )

    async def agenerate(self, request: ModelTransportRequest) -> ModelTransportResult:
        return self.generate(request)

    def stream(self, request: ModelTransportRequest):
        raise AssertionError("stream is outside this recovery contract")

    async def astream(self, request: ModelTransportRequest):
        yield ModelTransportCompleted(result=await self.agenerate(request))


class CrashBoundaryHook:
    def __init__(self, *, boundary: str) -> None:
        self.boundary = boundary
        self.crashed = False

    def __call__(self, phase: str) -> None:
        if not self.crashed and self.boundary == phase:
            self.crashed = True
            raise RuntimeError(f"synthetic crash at {phase}")


def _profile() -> ModelProfile:
    return ModelProfile(
        profile_id="intake-model.synthetic.v1",
        provider="synthetic",
        model="intake-model",
        temperature=0,
        max_output_tokens=governed_max_output_tokens(BASELINE_INTAKE_NODE_NAME),
        tool_allowlist=(),
        max_provider_attempts=1,
    )


def _policy(
    *,
    invocation_id: str = "ATTEMPT_P4_USER_2_1",
) -> ModelInvocationPolicy:
    trusted_system_prompt = _trusted_system_prompt(invocation_id=invocation_id)
    return ModelInvocationPolicy(
        invocation_id=invocation_id,
        node_name=BASELINE_INTAKE_NODE_NAME,
        deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1),
        provider_attempts_remaining=1,
        repairs_remaining=0,
        prompt_version="DISPUTE_INTAKE_OFFICER:USER:v1",
        output_schema_version="intake-turn-proposal.v2",
        policy_version="intake-policy.v2",
        guardrail_version="intake-guardrail.v2",
        trusted_system_sha256=system_prompt_sha256(trusted_system_prompt),
    )


def _opening_policy() -> ModelInvocationPolicy:
    return _policy(invocation_id="ATTEMPT_P4_USER_1_1")


def _agent_context(
    *,
    invocation_id: str = "ATTEMPT_P4_USER_2_1",
) -> AgentInvocationContext:
    return AgentInvocationContext.model_validate(
        {
            "tenant_id": "tenant-synthetic",
            "case_id": "CASE_P4_SYNTHETIC_1",
            "room_type": "INTAKE",
            "actor_id": "ACTOR_P4_USER_1",
            "actor_role": "USER",
            "access_session_id": "ACCESS_P4_USER_1",
            "permission_level": "PARTY_USER",
            "permission_scopes": [],
            "agent_key": "DISPUTE_INTAKE_OFFICER",
            "agent_invocation_id": invocation_id,
            "agent_session_id": "AGENT_SESSION_P4_USER_1",
            "conversation_scope": ":".join(
                (
                    "tenant-synthetic",
                    "CASE_P4_SYNTHETIC_1",
                    "INTAKE",
                    "ACTOR_P4_USER_1",
                    "USER",
                    "DISPUTE_INTAKE_OFFICER",
                    "DISPUTE_INTAKE_OFFICER:USER:v1",
                    "ACCESS_P4_USER_1",
                )
            ),
            "scope_type": "INTAKE_PARTY_PRIVATE",
            "allowed_actor_ids": ["ACTOR_P4_USER_1"],
            "allowed_actor_roles": ["USER"],
            "prompt_profile_id": "DISPUTE_INTAKE_OFFICER:USER:v1",
            "memory_policy_id": "INTAKE_MEMORY_SYNTHETIC_V1",
            "model_profile_id": "intake-model.synthetic.v1",
            "output_schema_version": "intake-turn-proposal.v2",
            "policy_version": "intake-policy.v2",
            "guardrail_version": "intake-guardrail.v2",
            "tool_capabilities": [],
        }
    )


def _trusted_system_prompt(
    *,
    invocation_id: str = "ATTEMPT_P4_USER_2_1",
) -> str:
    context = _agent_context(invocation_id=invocation_id)
    return prepare_baseline_prompt_authority(
        prompts=PromptRepository(),
        node_name=BASELINE_INTAKE_NODE_NAME,
        agent_context=context,
        prompt_profile_id=context.prompt_profile_id,
    ).system_prompt


def build_intake_model_node(**kwargs: Any):
    policy = kwargs["policy"]
    return _build_intake_model_node(
        **kwargs,
        agent_context=_agent_context(invocation_id=policy.invocation_id),
        trusted_system_prompt=_trusted_system_prompt(invocation_id=policy.invocation_id),
    )


def build_intake_runtime_bundle(**kwargs: Any):
    policy = kwargs["policy"]
    return _build_intake_runtime_bundle(
        **kwargs,
        agent_context=_agent_context(invocation_id=policy.invocation_id),
        trusted_system_prompt=_trusted_system_prompt(invocation_id=policy.invocation_id),
    )


def _command_bindings(bindings):
    selected = copy.deepcopy(bindings)
    selected["command"].update(
        command_id="COMMAND_P4_USER_2",
        logical_run_id="RUN_P4_USER_2",
        attempt_id="ATTEMPT_P4_USER_2_1",
    )
    return selected


def _opening_inputs(
    snapshot: dict[str, Any],
    event: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Build the exact fresh-command ingress pair plus its next participant turn."""

    opening_snapshot = copy.deepcopy(snapshot)
    form_source = opening_snapshot["initial_case_facts"].get("form_source")
    assert form_source in {"EXTERNAL_IMPORT", "FORM_SUBMISSION"}
    opening_snapshot["initial_case_facts"].update(
        order_reference="ORDER_P4_0001",
        logistics_reference="LOG_P4_00001",
        requested_outcome_hint="REFUND",
    )
    opening_snapshot["own_messages"] = []
    opening_snapshot["source_refs"] = ["SNAPSHOT_SOURCE_P4"]
    opening_snapshot["snapshot_hash"] = canonical_sha256_omitting(
        opening_snapshot,
        "snapshot_hash",
    )

    initial_form = copy.deepcopy(event)
    initial_form.update(
        event_id="EVENT_P4_USER_1",
        message_id="MESSAGE_P4_USER_1",
        sequence_no=1,
        domain_revision=opening_snapshot["domain_revision"] + 1,
        source_type="INITIAL_FORM",
        text=opening_snapshot["initial_case_facts"]["form_description"],
        source_refs=["MESSAGE_P4_USER_1"],
    )
    initial_form["event_hash"] = canonical_sha256_omitting(initial_form, "event_hash")

    follow_up_event = copy.deepcopy(event)
    follow_up_event["domain_revision"] = initial_form["domain_revision"] + 1
    follow_up_event["event_hash"] = canonical_sha256_omitting(
        follow_up_event,
        "event_hash",
    )
    return opening_snapshot, initial_form, follow_up_event


def _initialize(
    graph,
    config,
    bindings,
    version_pins,
    snapshot,
    initial_form,
):
    return graph.invoke(
        new_intake_graph_state(bindings=bindings, version_pins=version_pins),
        config,
        context=IntakeTurnContext(
            "BOOTSTRAP_EVENT",
            {"snapshot": snapshot, "event": initial_form},
        ),
    )


def _assert_opening_model_run(
    result: dict[str, Any],
    transport: RecoveryTransport,
    snapshot: dict[str, Any],
    initial_form: dict[str, Any],
) -> None:
    """Prove the first turn used the governed baseline model, not a seed node."""

    assert transport.generate_calls == 1
    assert len(transport.requests) == 1
    request = transport.requests[0]
    assert request.node_name == BASELINE_INTAKE_NODE_NAME
    assert request.governed_request.max_output_tokens == governed_max_output_tokens(
        BASELINE_INTAKE_NODE_NAME
    )
    assert request.governed_request.tool_allowlist == ()
    assert len(request.messages) == 2
    assert request.messages[0].content == _trusted_system_prompt(
        invocation_id="ATTEMPT_P4_USER_1_1"
    )
    human_prompt = request.messages[1].content
    assert isinstance(human_prompt, str)
    assert snapshot["initial_case_facts"]["form_source"] in human_prompt
    assert snapshot["initial_case_facts"]["form_description"] in human_prompt
    assert initial_form["source_type"] == "INITIAL_FORM"
    assert result["route"] == "message"
    assert result["initial_snapshot_hash"] == snapshot["snapshot_hash"]
    assert result["last_event_hash"] == initial_form["event_hash"]
    assert result["last_event_sequence"] == 1
    assert len(result["messages"]) == 1
    assert {message["role"] for message in result["messages"].values()} == {"AI"}
    assert result["result_json"]["room_utterance"] == (
        "I have reviewed the submitted form. What resolution would you like to request?"
    )
    assert result["result_json"]["source_event_hash"] == initial_form["event_hash"]
    assert set(result["execution_receipts"]) == {"ATTEMPT_P4_USER_1_1"}


def _baseline_hash(
    bindings,
    version_pins,
    snapshot,
    initial_form,
    event,
) -> str:
    transport = RecoveryTransport(follow_up_event=event)
    saver = InMemorySaver()
    opening_bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_opening_policy(),
        checkpointer=saver,
    )
    config = {"configurable": {"thread_id": "intake-clean-baseline"}}
    opening = _initialize(
        opening_bundle.graph,
        config,
        bindings,
        version_pins,
        snapshot,
        initial_form,
    )
    _assert_opening_model_run(opening, transport, snapshot, initial_form)
    bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_policy(),
        checkpointer=saver,
    )
    result = bundle.graph.invoke(
        {"bindings": _command_bindings(bindings)},
        config,
        context=IntakeTurnContext("EVENT", event),
    )
    assert transport.generate_calls == 2
    return extract_intake_terminal_proposal(result).proposal_hash


@pytest.mark.parametrize(
    "checkpointer",
    [
        pytest.param(None, id="none"),
        pytest.param(False, id="false"),
        pytest.param(True, id="true"),
        pytest.param(object(), id="object"),
    ],
)
def test_runtime_bundle_requires_checkpoint_saver(checkpointer) -> None:
    with pytest.raises(
        IntakeGraphContractError,
        match="INTAKE_RUNTIME_CHECKPOINTER_(REQUIRED|INVALID)",
    ):
        build_intake_runtime_bundle(
            transport=RecoveryTransport(),
            profile=_profile(),
            policy=_policy(),
            checkpointer=checkpointer,
        )


@pytest.mark.parametrize(
    ("boundary", "expected_model_calls"),
    [
        ("before_model", 2),
        ("after_model_before_checkpoint", 3),
    ],
)
def test_crash_before_terminal_checkpoint_resumes_to_identical_proposal_hash(
    bindings,
    version_pins,
    snapshot,
    event,
    boundary,
    expected_model_calls,
) -> None:
    saver = InMemorySaver()
    opening_snapshot, initial_form, follow_up_event = _opening_inputs(snapshot, event)
    transport = RecoveryTransport(follow_up_event=follow_up_event)
    opening_built = build_intake_model_node(
        transport=transport,
        profile=_profile(),
        policy=_opening_policy(),
    )
    opening_graph = compile_intake_v2_graph(
        intake_lcel=opening_built.runnable,
        checkpointer=saver,
    )
    config = {"configurable": {"thread_id": f"intake-crash-{boundary}"}}
    opening = _initialize(
        opening_graph,
        config,
        bindings,
        version_pins,
        opening_snapshot,
        initial_form,
    )
    _assert_opening_model_run(opening, transport, opening_snapshot, initial_form)

    crashing_built = build_intake_model_node(
        transport=transport,
        profile=_profile(),
        policy=_policy(),
        _test_hook=CrashBoundaryHook(boundary=boundary),
    )
    crashing_graph = compile_intake_v2_graph(
        intake_lcel=crashing_built.runnable,
        checkpointer=saver,
    )

    with pytest.raises(RuntimeError, match="synthetic crash"):
        crashing_graph.invoke(
            {"bindings": _command_bindings(bindings)},
            config,
            context=IntakeTurnContext("EVENT", follow_up_event),
        )

    recovered_built = build_intake_model_node(
        transport=transport,
        profile=_profile(),
        policy=_policy(),
    )
    recovered_graph = compile_intake_v2_graph(
        intake_lcel=recovered_built.runnable,
        checkpointer=saver,
    )
    recovered = recovered_graph.invoke(
        None,
        config,
        context=IntakeTurnContext("EVENT", follow_up_event),
    )

    assert extract_intake_terminal_proposal(recovered).proposal_hash == _baseline_hash(
        bindings,
        version_pins,
        opening_snapshot,
        initial_form,
        follow_up_event,
    )
    assert transport.generate_calls == expected_model_calls


def test_crash_after_terminal_checkpoint_reconciles_from_cache_without_model(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    saver = InMemorySaver()
    opening_snapshot, initial_form, follow_up_event = _opening_inputs(snapshot, event)
    transport = RecoveryTransport(follow_up_event=follow_up_event)
    opening_bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_opening_policy(),
        checkpointer=saver,
    )
    config = {"configurable": {"thread_id": "intake-after-checkpoint"}}
    opening = _initialize(
        opening_bundle.graph,
        config,
        bindings,
        version_pins,
        opening_snapshot,
        initial_form,
    )
    _assert_opening_model_run(opening, transport, opening_snapshot, initial_form)
    bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_policy(),
        checkpointer=saver,
    )
    completed = bundle.graph.invoke(
        {"bindings": _command_bindings(bindings)},
        config,
        context=IntakeTurnContext("EVENT", follow_up_event),
    )
    expected = extract_intake_terminal_proposal(completed).proposal_hash

    replacement = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_policy(),
        checkpointer=saver,
    )
    reconciled = replacement.graph.invoke(
        {},
        config,
        context=IntakeTurnContext("EVENT", copy.deepcopy(follow_up_event)),
    )

    assert extract_intake_terminal_proposal(reconciled).proposal_hash == expected
    assert transport.generate_calls == 2


def test_crash_after_completion_before_response_returns_same_cached_hash(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    saver = InMemorySaver()
    opening_snapshot, initial_form, follow_up_event = _opening_inputs(snapshot, event)
    transport = RecoveryTransport(follow_up_event=follow_up_event)
    opening_bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_opening_policy(),
        checkpointer=saver,
    )
    config = {"configurable": {"thread_id": "intake-after-completion"}}
    opening = _initialize(
        opening_bundle.graph,
        config,
        bindings,
        version_pins,
        opening_snapshot,
        initial_form,
    )
    _assert_opening_model_run(opening, transport, opening_snapshot, initial_form)
    bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_policy(),
        checkpointer=saver,
    )
    completed = bundle.graph.invoke(
        {"bindings": _command_bindings(bindings)},
        config,
        context=IntakeTurnContext("EVENT", follow_up_event),
    )
    expected = extract_intake_terminal_proposal(completed).proposal_hash

    first_retry = bundle.graph.invoke(
        {},
        config,
        context=IntakeTurnContext("EVENT", copy.deepcopy(follow_up_event)),
    )
    second_retry = bundle.graph.invoke(
        {},
        config,
        context=IntakeTurnContext("EVENT", copy.deepcopy(follow_up_event)),
    )

    assert extract_intake_terminal_proposal(first_retry).proposal_hash == expected
    assert extract_intake_terminal_proposal(second_retry).proposal_hash == expected
    assert transport.generate_calls == 2


def test_cached_replay_with_different_event_hash_fails_without_model(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    saver = InMemorySaver()
    opening_snapshot, initial_form, follow_up_event = _opening_inputs(snapshot, event)
    transport = RecoveryTransport(follow_up_event=follow_up_event)
    opening_bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_opening_policy(),
        checkpointer=saver,
    )
    config = {"configurable": {"thread_id": "intake-replay-conflict"}}
    opening = _initialize(
        opening_bundle.graph,
        config,
        bindings,
        version_pins,
        opening_snapshot,
        initial_form,
    )
    _assert_opening_model_run(opening, transport, opening_snapshot, initial_form)
    bundle = build_intake_runtime_bundle(
        transport=transport,
        profile=_profile(),
        policy=_policy(),
        checkpointer=saver,
    )
    bundle.graph.invoke(
        {"bindings": _command_bindings(bindings)},
        config,
        context=IntakeTurnContext("EVENT", follow_up_event),
    )
    conflicting = copy.deepcopy(follow_up_event)
    conflicting["text"] = "Conflicting bytes for the same stable event."
    conflicting["event_hash"] = canonical_sha256_omitting(conflicting, "event_hash")

    with pytest.raises(Exception, match="INTAKE_STABLE_ID_REBINDING"):
        bundle.graph.invoke(
            {},
            config,
            context=IntakeTurnContext("EVENT", conflicting),
        )
    assert transport.generate_calls == 2


def _bootstrap_execution(snapshot_ref: object, event_ref: object) -> SimpleNamespace:
    return SimpleNamespace(
        admission=SimpleNamespace(
            command=SimpleNamespace(
                domain_snapshot_ref=snapshot_ref,
                event_ref=event_ref,
            )
        ),
        thread_record=SimpleNamespace(last_checkpoint_id=None),
    )


@pytest.mark.asyncio
async def test_bootstrap_context_loads_exact_inputs_concurrently_then_decodes_in_order(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    snapshot_ref = object()
    event_ref = object()
    execution = _bootstrap_execution(snapshot_ref, event_ref)

    class Loader:
        def __init__(self) -> None:
            self.started: set[object] = set()
            self.completed: set[object] = set()
            self.both_started = asyncio.Event()
            self.release = asyncio.Event()

        async def load(self, selected_execution, *, object_ref=None):
            assert selected_execution is execution
            assert object_ref in {snapshot_ref, event_ref}
            self.started.add(object_ref)
            if self.started == {snapshot_ref, event_ref}:
                self.both_started.set()
            await self.release.wait()
            self.completed.add(object_ref)
            return object_ref

    loader = Loader()
    decoded: list[object] = []

    def decode(*, command, loaded, object_ref):
        assert command is execution.admission.command
        assert loaded is object_ref
        assert loader.completed == {snapshot_ref, event_ref}
        decoded.append(object_ref)
        return IntakeTurnContext(
            "SNAPSHOT" if object_ref is snapshot_ref else "EVENT",
            {"reference": "snapshot" if object_ref is snapshot_ref else "event"},
        )

    monkeypatch.setattr(intake_executor, "decode_authorized_intake_ingress", decode)
    executor = object.__new__(intake_executor.CompiledIntakeGraphShadowExecutor)
    executor._input_loader = loader
    task = asyncio.create_task(executor._load_context(execution, execution))
    try:
        await asyncio.wait_for(loader.both_started.wait(), timeout=0.5)
        loader.release.set()
        context = await task
    finally:
        loader.release.set()
        if not task.done():
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task

    assert context.ingress_kind == "BOOTSTRAP_EVENT"
    assert context.ingress_payload == {
        "snapshot": {"reference": "snapshot"},
        "event": {"reference": "event"},
    }
    assert decoded == [snapshot_ref, event_ref]


@pytest.mark.asyncio
async def test_bootstrap_context_preserves_snapshot_decode_error_priority_over_event_load_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    snapshot_ref = object()
    event_ref = object()
    execution = _bootstrap_execution(snapshot_ref, event_ref)

    class EventLoadFailure(Exception):
        pass

    class SnapshotDecodeFailure(Exception):
        pass

    class Loader:
        def __init__(self) -> None:
            self.event_failed = asyncio.Event()

        async def load(self, selected_execution, *, object_ref=None):
            assert selected_execution is execution
            if object_ref is event_ref:
                self.event_failed.set()
                raise EventLoadFailure("event load failed first")
            assert object_ref is snapshot_ref
            await self.event_failed.wait()
            return snapshot_ref

    loader = Loader()

    def decode(*, command, loaded, object_ref):
        assert command is execution.admission.command
        assert loaded is snapshot_ref
        assert object_ref is snapshot_ref
        raise SnapshotDecodeFailure("snapshot decode wins")

    monkeypatch.setattr(intake_executor, "decode_authorized_intake_ingress", decode)
    executor = object.__new__(intake_executor.CompiledIntakeGraphShadowExecutor)
    executor._input_loader = loader
    task = asyncio.create_task(executor._load_context(execution, execution))
    try:
        await asyncio.wait_for(loader.event_failed.wait(), timeout=0.5)
        with pytest.raises(SnapshotDecodeFailure, match="snapshot decode wins"):
            await task
    finally:
        if not task.done():
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task


@pytest.mark.asyncio
async def test_bootstrap_context_cancellation_cancels_both_inflight_exchange_reads(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    snapshot_ref = object()
    event_ref = object()
    execution = _bootstrap_execution(snapshot_ref, event_ref)

    class Loader:
        def __init__(self) -> None:
            self.started: set[object] = set()
            self.cancelled: set[object] = set()
            self.both_started = asyncio.Event()
            self.never = asyncio.Event()

        async def load(self, selected_execution, *, object_ref=None):
            assert selected_execution is execution
            assert object_ref in {snapshot_ref, event_ref}
            self.started.add(object_ref)
            if self.started == {snapshot_ref, event_ref}:
                self.both_started.set()
            try:
                await self.never.wait()
            except asyncio.CancelledError:
                self.cancelled.add(object_ref)
                raise

    loader = Loader()
    monkeypatch.setattr(
        intake_executor,
        "decode_authorized_intake_ingress",
        lambda **_: pytest.fail("cancelled reads must never decode"),
    )
    executor = object.__new__(intake_executor.CompiledIntakeGraphShadowExecutor)
    executor._input_loader = loader
    task = asyncio.create_task(executor._load_context(execution, execution))
    try:
        await asyncio.wait_for(loader.both_started.wait(), timeout=0.5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    finally:
        if not task.done():
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task

    assert loader.cancelled == {snapshot_ref, event_ref}


@pytest.mark.asyncio
async def test_bootstrap_context_snapshot_failure_cancels_inflight_event_read(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    snapshot_ref = object()
    event_ref = object()
    execution = _bootstrap_execution(snapshot_ref, event_ref)

    class SnapshotLoadFailure(Exception):
        pass

    class Loader:
        def __init__(self) -> None:
            self.event_started = asyncio.Event()
            self.event_cancelled = asyncio.Event()
            self.never = asyncio.Event()

        async def load(self, selected_execution, *, object_ref=None):
            assert selected_execution is execution
            if object_ref is snapshot_ref:
                await self.event_started.wait()
                raise SnapshotLoadFailure("snapshot load failed")
            assert object_ref is event_ref
            self.event_started.set()
            try:
                await self.never.wait()
            except asyncio.CancelledError:
                self.event_cancelled.set()
                raise

    loader = Loader()
    monkeypatch.setattr(
        intake_executor,
        "decode_authorized_intake_ingress",
        lambda **_: pytest.fail("failed bootstrap reads must never decode"),
    )
    executor = object.__new__(intake_executor.CompiledIntakeGraphShadowExecutor)
    executor._input_loader = loader

    with pytest.raises(SnapshotLoadFailure, match="snapshot load failed"):
        await executor._load_context(execution, execution)

    assert loader.event_cancelled.is_set()
