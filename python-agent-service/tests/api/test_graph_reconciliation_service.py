from __future__ import annotations

import asyncio
import json
from dataclasses import replace
from pathlib import Path
from typing import Any, Callable, cast

import pytest

from app.api.graph_reconciliation_service import GatewayBackedGraphReconciliationService
from app.api.graph_stream_service import GraphStreamAdmissionGate
from app.contracts.v1.codec import canonical_sha256_omitting
from app.contracts.v1.models import RoomGraphCommand, RoomGraphResult
from app.graph_runtime.errors import GraphGatewayDisabledError, GraphTerminalBindingError
from app.graph_runtime.gateway import (
    GraphReconciliation,
    ReconciliationDisposition,
)
from app.graph_runtime.identity import ActorScopeBinding, RoomType, ThreadIdentity
from app.graph_runtime.ledger import CommandBinding, CommandRecord, CommandStatus, ResultRecord
from app.graph_runtime.registry import RegistryRecord, RegistryState, VersionBinding
from app.security.invocation_envelope import VerifiedReconciliation


ROOT = Path(__file__).resolve().parents[3]
COMMAND_FIXTURE = (
    ROOT / "contracts/agent-platform/v1/fixtures/valid/room-graph-command-valid.json"
)
RESPONSE_FIXTURE = (
    ROOT / "contracts/agent-platform/v1/fixtures/valid/graph-reconcile-response-valid.json"
)
SERVICE_PATH = ROOT / "python-agent-service/app/api/graph_reconciliation_service.py"


def _command() -> RoomGraphCommand:
    document = json.loads(COMMAND_FIXTURE.read_text(encoding="utf-8"))
    return RoomGraphCommand.model_validate(document["instance"])


def _thread(command: RoomGraphCommand) -> ThreadIdentity:
    return ThreadIdentity(
        thread_id=command.thread_id,
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type=RoomType(command.room_type),
        room_epoch=command.room_epoch,
        actor_scope=ActorScopeBinding.from_json(command.actor_scope.model_dump(mode="json")),
        agent_session_id="trusted-session-1",
        shared_session=False,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
    )


def _version(command: RoomGraphCommand) -> VersionBinding:
    invocation = command.invocation_context
    return VersionBinding(
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        state_schema_version="state.v1",
        state_schema_hash="a" * 64,
        command_schema_version=command.schema_version,
        result_schema_version="room-graph-result.v1",
        prompt_version=invocation.prompt_profile_id,
        model_profile_id=invocation.model_profile_id,
        output_schema_version=invocation.output_schema_version,
        policy_version=invocation.policy_version,
        guardrail_version=invocation.guardrail_version,
        tool_policy_version="intake-tools.v1",
        binding_hash="c" * 64,
        code_build_id="build.v1",
    )


def _result_json() -> dict[str, Any]:
    document = json.loads(RESPONSE_FIXTURE.read_text(encoding="utf-8"))
    result = document["instance"]["result"]
    result["output_hash"] = canonical_sha256_omitting(result, "output_hash")
    return result


def _reconciliation(
    disposition: ReconciliationDisposition = ReconciliationDisposition.RETURN_CACHED,
) -> GraphReconciliation:
    command = _command()
    version = _version(command)
    binding = CommandBinding.from_command(
        command,
        tool_policy_version=version.tool_policy_version,
    )
    result_json = _result_json()
    result_hash = result_json["output_hash"]
    result_ref = f"urn:after-sale-flow:graph-result:{result_hash}"
    record = CommandRecord(
        binding=binding,
        status=CommandStatus.COMPLETED,
        attempt_count=1,
        fencing_token=1,
        start_checkpoint_ns="",
        start_checkpoint_id="checkpoint-parent",
        committed_checkpoint_ns="",
        committed_checkpoint_id=result_json["checkpoint_id"],
        result_ref=result_ref,
        result_hash=result_hash,
        error_code=None,
        error_classification=None,
        revision=2,
    )
    result = ResultRecord(
        result_id=f"result.{result_hash[:32]}",
        thread_id=binding.thread_id,
        command_id=binding.command_id,
        request_hash=binding.request_hash,
        result_schema_version=version.result_schema_version,
        checkpoint_ns="",
        checkpoint_id=result_json["checkpoint_id"],
        cognitive_revision=result_json["cognitive_revision"],
        terminal_status=result_json["status"],
        result_json=result_json,
        result_ref=result_ref,
        result_hash=result_hash,
        usage_json=result_json["usage"],
    )
    return GraphReconciliation(
        disposition=disposition,
        command=record,
        result=result,
        registry=RegistryRecord(version, RegistryState.SHADOW, True, 1),
    )


class _Gateway:
    def __init__(
        self,
        reconciliation: GraphReconciliation,
        *,
        failure: BaseException | None = None,
        started: asyncio.Event | None = None,
        blocker: asyncio.Event | None = None,
    ) -> None:
        self.reconciliation = reconciliation
        self.failure = failure
        self.started = started
        self.blocker = blocker
        self.calls: list[dict[str, Any]] = []

    async def reconcile_only(self, **kwargs: Any) -> GraphReconciliation:
        self.calls.append(kwargs)
        if self.started is not None:
            self.started.set()
        if self.blocker is not None:
            await self.blocker.wait()
        if self.failure is not None:
            raise self.failure
        return self.reconciliation


async def _service(
    gateway: _Gateway,
) -> tuple[GatewayBackedGraphReconciliationService, GraphStreamAdmissionGate]:
    gate = GraphStreamAdmissionGate()
    await gate.start()
    return (
        GatewayBackedGraphReconciliationService(
            gateway=cast(Any, gateway),
            admission_gate=gate,
            owner_id="replica-1",
        ),
        gate,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("disposition", "expected"),
    [
        (ReconciliationDisposition.RETURN_CACHED, "RETURN_CACHED"),
        (ReconciliationDisposition.RECONCILED_TERMINAL, "RECONCILED_TERMINAL"),
    ],
)
async def test_exact_persisted_result_is_mapped_for_both_dispositions(
    disposition: ReconciliationDisposition,
    expected: str,
) -> None:
    reconciliation = _reconciliation(disposition)
    gateway = _Gateway(reconciliation)
    service, gate = await _service(gateway)
    command = _command()
    thread = _thread(command)
    verified = cast(VerifiedReconciliation, object())

    response = await service.reconcile(
        command=command,
        verified_reconciliation=verified,
        expected_thread=thread,
    )

    assert len(gateway.calls) == 1
    assert gateway.calls[0] == {
        "command": command,
        "verified_reconciliation": verified,
        "expected_thread": thread,
        "owner_id": "replica-1",
    }
    assert response.disposition == expected
    assert response.thread_id == reconciliation.command.binding.thread_id
    assert response.command_id == reconciliation.command.binding.command_id
    assert response.request_hash == reconciliation.command.binding.request_hash
    assert response.checkpoint_ns == reconciliation.result.checkpoint_ns
    assert response.checkpoint_id == reconciliation.result.checkpoint_id
    assert response.result_ref == reconciliation.result.result_ref
    assert response.result_hash == reconciliation.result.result_hash
    assert response.registry_binding_hash == reconciliation.registry.binding.binding_hash
    assert response.tool_policy_version == reconciliation.registry.binding.tool_policy_version
    assert response.result == RoomGraphResult.model_validate(reconciliation.result.result_json)
    assert await gate.drain(0.01) is True


def _command_identity_drift(value: GraphReconciliation) -> GraphReconciliation:
    binding = replace(value.command.binding, command_id="graph-cmd-other")
    return replace(value, command=replace(value.command, binding=binding))


def _registry_graph_drift(value: GraphReconciliation) -> GraphReconciliation:
    binding = replace(value.registry.binding, graph_version="2.0.0")
    return replace(value, registry=replace(value.registry, binding=binding))


def _registry_profile_drift(value: GraphReconciliation) -> GraphReconciliation:
    binding = replace(value.registry.binding, prompt_version="intake.user.v2")
    return replace(value, registry=replace(value.registry, binding=binding))


def _registry_tool_policy_drift(value: GraphReconciliation) -> GraphReconciliation:
    binding = replace(value.registry.binding, tool_policy_version="intake-tools.v2")
    return replace(value, registry=replace(value.registry, binding=binding))


def _checkpoint_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(
        value,
        command=replace(value.command, committed_checkpoint_id="checkpoint-other"),
    )


def _checkpoint_namespace_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(
        value,
        command=replace(value.command, committed_checkpoint_ns="room-other"),
    )


def _command_status_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, command=replace(value.command, status=CommandStatus.RESULT_CHECKPOINTED))


def _result_ref_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, result=replace(value.result, result_ref="urn:result:other"))


def _result_hash_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, result=replace(value.result, result_hash="d" * 64))


def _result_thread_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(
        value,
        result=replace(value.result, thread_id="grt.v1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
    )


def _result_command_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, result=replace(value.result, command_id="graph-cmd-other"))


def _result_request_hash_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, result=replace(value.result, request_hash="e" * 64))


def _result_schema_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, result=replace(value.result, result_schema_version="room-result.v2"))


def _result_revision_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, result=replace(value.result, cognitive_revision=99))


def _result_status_drift(value: GraphReconciliation) -> GraphReconciliation:
    return replace(value, result=replace(value.result, terminal_status="NEEDS_REVIEW"))


def _result_usage_drift(value: GraphReconciliation) -> GraphReconciliation:
    usage = {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}
    return replace(value, result=replace(value.result, usage_json=usage))


def _replace_nested_result(
    value: GraphReconciliation,
    **updates: Any,
) -> GraphReconciliation:
    result_json = dict(value.result.result_json)
    result_json.update(updates)
    result_hash = canonical_sha256_omitting(result_json, "output_hash")
    result_json["output_hash"] = result_hash
    result_ref = f"urn:after-sale-flow:graph-result:{result_hash}"
    return replace(
        value,
        command=replace(value.command, result_ref=result_ref, result_hash=result_hash),
        result=replace(
            value.result,
            result_json=result_json,
            result_ref=result_ref,
            result_hash=result_hash,
        ),
    )


def _nested_run_drift(value: GraphReconciliation) -> GraphReconciliation:
    return _replace_nested_result(value, logical_run_id="run-other")


def _nested_attempt_drift(value: GraphReconciliation) -> GraphReconciliation:
    return _replace_nested_result(value, attempt_id="attempt-other")


def _nested_graph_drift(value: GraphReconciliation) -> GraphReconciliation:
    return _replace_nested_result(value, graph_key="evidence.v2")


def _nested_self_hash_drift(value: GraphReconciliation) -> GraphReconciliation:
    result_json = dict(value.result.result_json)
    result_json["public_event_proposals"] = []
    return replace(value, result=replace(value.result, result_json=result_json))


Mismatch = Callable[[GraphReconciliation], GraphReconciliation]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "mutate",
    [
        _command_identity_drift,
        _registry_graph_drift,
        _registry_profile_drift,
        _registry_tool_policy_drift,
        _checkpoint_drift,
        _checkpoint_namespace_drift,
        _command_status_drift,
        _result_ref_drift,
        _result_hash_drift,
        _result_thread_drift,
        _result_command_drift,
        _result_request_hash_drift,
        _result_schema_drift,
        _result_revision_drift,
        _result_status_drift,
        _result_usage_drift,
        _nested_run_drift,
        _nested_attempt_drift,
        _nested_graph_drift,
        _nested_self_hash_drift,
    ],
    ids=lambda mutate: cast(Mismatch, mutate).__name__.removeprefix("_"),
)
async def test_every_response_binding_mismatch_fails_closed_and_releases_gate(
    mutate: Mismatch,
) -> None:
    gateway = _Gateway(mutate(_reconciliation()))
    service, gate = await _service(gateway)
    command = _command()

    with pytest.raises(GraphTerminalBindingError):
        await service.reconcile(
            command=command,
            verified_reconciliation=cast(VerifiedReconciliation, object()),
            expected_thread=_thread(command),
        )

    assert len(gateway.calls) == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_gateway_error_releases_admission_token() -> None:
    failure = GraphTerminalBindingError("durable result conflict")
    gateway = _Gateway(_reconciliation(), failure=failure)
    service, gate = await _service(gateway)
    command = _command()

    with pytest.raises(GraphTerminalBindingError, match="durable result conflict"):
        await service.reconcile(
            command=command,
            verified_reconciliation=cast(VerifiedReconciliation, object()),
            expected_thread=_thread(command),
        )

    assert len(gateway.calls) == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_cancellation_releases_admission_token() -> None:
    started = asyncio.Event()
    gateway = _Gateway(
        _reconciliation(),
        started=started,
        blocker=asyncio.Event(),
    )
    service, gate = await _service(gateway)
    command = _command()
    task = asyncio.create_task(
        service.reconcile(
            command=command,
            verified_reconciliation=cast(VerifiedReconciliation, object()),
            expected_thread=_thread(command),
        )
    )
    await started.wait()

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert len(gateway.calls) == 1
    assert await gate.drain(0.01) is True


@pytest.mark.asyncio
async def test_draining_gate_rejects_without_calling_gateway() -> None:
    gateway = _Gateway(_reconciliation())
    gate = GraphStreamAdmissionGate()
    service = GatewayBackedGraphReconciliationService(
        gateway=cast(Any, gateway),
        admission_gate=gate,
        owner_id="replica-1",
    )
    command = _command()

    with pytest.raises(GraphGatewayDisabledError, match="GRAPH_GATEWAY_DRAINING"):
        await service.reconcile(
            command=command,
            verified_reconciliation=cast(VerifiedReconciliation, object()),
            expected_thread=_thread(command),
        )

    assert gateway.calls == []


@pytest.mark.parametrize("owner_id", ["", "x" * 129, "owner\x00other"])
def test_owner_id_is_bounded(owner_id: str) -> None:
    with pytest.raises(ValueError, match="owner_id"):
        GatewayBackedGraphReconciliationService(
            gateway=cast(Any, _Gateway(_reconciliation())),
            admission_gate=GraphStreamAdmissionGate(),
            owner_id=owner_id,
        )


def test_service_has_no_executor_or_agent_stream_dependency() -> None:
    source = SERVICE_PATH.read_text(encoding="utf-8")

    assert "executor" not in source.casefold()
    assert "AgentStreamEvent" not in source
    assert "AgentStreamPayload" not in source
    assert ".execute_stream" not in source
