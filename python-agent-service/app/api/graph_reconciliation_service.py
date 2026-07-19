"""Result-only Graph reconciliation orchestration for durable SHADOW commands."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Protocol

from app.api.graph_stream_service import GraphStreamAdmissionGate
from app.contracts.v1.codec import canonical_sha256_omitting
from app.contracts.v1.models import GraphReconcileResponse, RoomGraphCommand, RoomGraphResult
from app.graph_runtime.errors import GraphTerminalBindingError
from app.graph_runtime.gateway import (
    GraphReconciliation,
    ReconciliationDisposition,
)
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.ledger import CommandBinding, CommandRecord, CommandStatus, ResultRecord
from app.graph_runtime.registry import RegistryRecord
from app.security.invocation_envelope import VerifiedReconciliation


class GraphReconciliationGatewayPort(Protocol):
    async def reconcile_only(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
        expected_thread: ThreadIdentity,
        owner_id: str,
    ) -> GraphReconciliation: ...


class GraphReconciliationService(Protocol):
    async def reconcile(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
        expected_thread: ThreadIdentity,
    ) -> GraphReconcileResponse: ...


class GatewayBackedGraphReconciliationService:
    """Map one existing durable Graph result without granting execution authority."""

    def __init__(
        self,
        *,
        gateway: GraphReconciliationGatewayPort,
        admission_gate: GraphStreamAdmissionGate,
        owner_id: str,
    ) -> None:
        if (
            not isinstance(owner_id, str)
            or not owner_id
            or len(owner_id) > 128
            or "\x00" in owner_id
        ):
            raise ValueError("Graph reconciliation owner_id must contain 1..128 non-NUL characters")
        self._gateway = gateway
        self._gate = admission_gate
        self._owner_id = owner_id

    async def reconcile(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
        expected_thread: ThreadIdentity,
    ) -> GraphReconcileResponse:
        token = await self._gate.enter()
        try:
            reconciliation = await self._gateway.reconcile_only(
                command=command,
                verified_reconciliation=verified_reconciliation,
                expected_thread=expected_thread,
                owner_id=self._owner_id,
            )
            return self._materialize_response(command, reconciliation)
        finally:
            await self._gate.leave(token)

    @classmethod
    def _materialize_response(
        cls,
        request_command: RoomGraphCommand,
        reconciliation: GraphReconciliation,
    ) -> GraphReconcileResponse:
        if not isinstance(reconciliation, GraphReconciliation) or not isinstance(
            reconciliation.disposition, ReconciliationDisposition
        ):
            raise GraphTerminalBindingError("Graph reconciliation returned an invalid envelope")
        command = reconciliation.command
        result = reconciliation.result
        registry = reconciliation.registry
        if not isinstance(command, CommandRecord) or not isinstance(result, ResultRecord):
            raise GraphTerminalBindingError("Graph reconciliation returned an invalid durable result")
        if not isinstance(registry, RegistryRecord):
            raise GraphTerminalBindingError("Graph reconciliation returned an invalid registry binding")

        registry_binding = registry.require_thread_restore()
        expected_command = CommandBinding.from_command(
            request_command,
            tool_policy_version=registry_binding.tool_policy_version,
        )
        if command.binding != expected_command or command.status is not CommandStatus.COMPLETED:
            raise GraphTerminalBindingError(
                "reconciled command differs from the exact requested command"
            )
        if (
            registry_binding.graph_key != command.binding.graph_key
            or registry_binding.graph_version != command.binding.graph_version
            or registry_binding.checkpoint_schema_version
            != command.binding.checkpoint_schema_version
            or registry_binding.command_profile != command.binding.profile
            or registry_binding.result_schema_version != result.result_schema_version
        ):
            raise GraphTerminalBindingError(
                "reconciled command differs from its durable registry binding"
            )

        cls._require_result_columns(command, result)
        nested = cls._validated_nested_result(command, result)
        request_json = command.binding.request_json
        logical_run_id = request_json.get("logical_run_id")
        attempt_id = request_json.get("attempt_id")
        if not isinstance(logical_run_id, str) or not isinstance(attempt_id, str):
            raise GraphTerminalBindingError("persisted command has no run and attempt binding")

        try:
            return GraphReconcileResponse(
                schema_version="graph-reconcile-response.v1",
                disposition=reconciliation.disposition.value,
                thread_id=command.binding.thread_id,
                command_id=command.binding.command_id,
                request_hash=command.binding.request_hash,
                logical_run_id=logical_run_id,
                attempt_id=attempt_id,
                graph_key=command.binding.graph_key,
                graph_version=command.binding.graph_version,
                checkpoint_schema_version=command.binding.checkpoint_schema_version,
                checkpoint_ns=result.checkpoint_ns,
                checkpoint_id=result.checkpoint_id,
                result_ref=result.result_ref,
                result_hash=result.result_hash,
                registry_binding_hash=registry_binding.binding_hash,
                tool_policy_version=registry_binding.tool_policy_version,
                result=nested,
            )
        except (TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted reconciliation fields violate GraphReconcileResponse.v1"
            ) from error

    @staticmethod
    def _require_result_columns(command: CommandRecord, result: ResultRecord) -> None:
        binding = command.binding
        if (
            result.thread_id != binding.thread_id
            or result.command_id != binding.command_id
            or result.request_hash != binding.request_hash
            or command.committed_checkpoint_ns != result.checkpoint_ns
            or command.committed_checkpoint_id != result.checkpoint_id
            or command.result_ref != result.result_ref
            or command.result_hash != result.result_hash
        ):
            raise GraphTerminalBindingError(
                "persisted result columns differ from the completed command"
            )
        if not isinstance(result.result_json, Mapping) or not isinstance(
            result.usage_json, Mapping
        ):
            raise GraphTerminalBindingError("persisted reconciliation result is not an object")
        try:
            actual_hash = canonical_sha256_omitting(result.result_json, "output_hash")
        except (TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted reconciliation result has an invalid self-hash"
            ) from error
        if result.result_json.get("output_hash") != result.result_hash or actual_hash != result.result_hash:
            raise GraphTerminalBindingError(
                "persisted reconciliation result has an invalid self-hash"
            )

    @staticmethod
    def _validated_nested_result(
        command: CommandRecord,
        result: ResultRecord,
    ) -> RoomGraphResult:
        try:
            nested = RoomGraphResult.model_validate(result.result_json)
        except (TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted result JSON violates RoomGraphResult.v1"
            ) from error
        request = command.binding.request_json
        profile = command.binding.profile
        expected_metadata = {
            "prompt_version": profile.prompt_version,
            "model_profile_id": profile.model_profile_id,
            "schema_version": profile.output_schema_version,
            "policy_version": profile.policy_version,
            "guardrail_version": profile.guardrail_version,
        }
        expected = (
            result.result_schema_version,
            result.command_id,
            request.get("logical_run_id"),
            request.get("attempt_id"),
            command.binding.graph_key,
            command.binding.graph_version,
            result.checkpoint_id,
            result.cognitive_revision,
            result.terminal_status,
            result.result_hash,
            dict(result.usage_json),
            expected_metadata,
        )
        actual = (
            nested.schema_version,
            nested.command_id,
            nested.logical_run_id,
            nested.attempt_id,
            nested.graph_key,
            nested.graph_version,
            nested.checkpoint_id,
            nested.cognitive_revision,
            nested.status,
            nested.output_hash,
            nested.usage.model_dump(mode="json"),
            nested.execution_metadata.model_dump(mode="json"),
        )
        if actual != expected:
            raise GraphTerminalBindingError(
                "persisted RoomGraphResult differs from its command or result columns"
            )
        return nested
