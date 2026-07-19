"""Fail-closed admission and execution ports for signed synthetic shadow commands."""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, replace
from enum import StrEnum
import hmac
from typing import Any, Final, Protocol

from app.contracts.v1.models import AgentStreamEvent, RoomGraphCommand
from app.graph_runtime.errors import (
    GraphCommandAbortedError,
    GraphCommandCancelledError,
    GraphContractError,
    GraphGatewayDisabledError,
    GraphNewAgentAttemptRequiredError,
    GraphResultNotCommittedError,
    GraphRuntimeError,
    GraphTerminalBindingError,
    GraphThreadBindingError,
)
from app.graph_runtime.identity import (
    ActorScopeBinding,
    PostgresThreadIdentityRepository,
    ThreadIdentity,
    ThreadLifecycle,
    ThreadRecord,
)
from app.graph_runtime.lease import (
    LeaseAcquisition,
    LeaseAcquisitionKind,
    LeaseRecord,
    PostgresLeaseRepository,
)
from app.graph_runtime.ledger import (
    AttemptRecord,
    AttemptStatus,
    CommandBinding,
    CommandRecord,
    CommandStatus,
    InvocationNonce,
    PostgresCommandLedger,
    ResultRecord,
)
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.recovery import PostgresRecoveryCoordinator, RecoveryDecision
from app.graph_runtime.registry import (
    PostgresGraphVersionRegistry,
    RegistryRecord,
)
from app.security.invocation_envelope import (
    ReconciliationClaims,
    VerifiedInvocation,
    VerifiedReconciliation,
    invocation_binding_claims,
)


class AdmissionAction(StrEnum):
    ACQUIRE = "ACQUIRE"
    OBSERVE_OR_TAKEOVER = "OBSERVE_OR_TAKEOVER"
    RECONCILE = "RECONCILE"
    RETURN_CACHED = "RETURN_CACHED"
    RETURN_CANCELLED = "RETURN_CANCELLED"
    RETURN_ABORTED = "RETURN_ABORTED"


class ReconciliationDisposition(StrEnum):
    RETURN_CACHED = "RETURN_CACHED"
    RECONCILED_TERMINAL = "RECONCILED_TERMINAL"


_STREAM_PAYLOAD_FIELDS: Final[dict[str, frozenset[str]]] = {
    "attempt_started": frozenset({"node"}),
    "visible_delta": frozenset({"node", "field", "delta"}),
    "usage": frozenset({"usage"}),
    "attempt_aborted": frozenset({"reason_code"}),
    "attempt_reset": frozenset({"reset_attempt_id", "reason_code"}),
    "final": frozenset({"final_result_ref", "final_result_hash"}),
    "error": frozenset({"error_code", "retryable"}),
}


@dataclass(frozen=True, slots=True)
class GatewayAdmission:
    command: RoomGraphCommand
    binding: CommandBinding
    thread: ThreadIdentity
    registry: RegistryRecord
    record: CommandRecord
    action: AdmissionAction
    created: bool


@dataclass(frozen=True, slots=True)
class GatewayExecution:
    admission: GatewayAdmission
    attempt: AttemptRecord
    lease: LeaseRecord
    fence: GraphFenceContext
    thread_record: ThreadRecord | None = None


@dataclass(frozen=True, slots=True)
class GraphReconciliation:
    disposition: ReconciliationDisposition
    command: CommandRecord
    result: ResultRecord
    registry: RegistryRecord


@dataclass(frozen=True, slots=True)
class GatewayAuditEvent:
    event_type: str
    code: str
    command_id: str
    thread_id: str
    request_hash: str
    graph_key: str
    graph_version: str
    traceparent: str
    fencing_token: int | None = None


class GatewayAuditSink(Protocol):
    async def emit(self, event: GatewayAuditEvent) -> None: ...


class ImmutableInputAuthorizer(Protocol):
    """Validates immutable references and rejects private input for shared Hearing."""

    async def authorize(
        self,
        *,
        command: RoomGraphCommand,
        thread: ThreadIdentity,
    ) -> None: ...


class ShadowGraphExecutor(Protocol):
    """Graph adapter; it must checkpoint terminal output before yielding ``final``."""

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]: ...


class _NullAuditSink:
    async def emit(self, event: GatewayAuditEvent) -> None:
        return None


class _FailClosedInputAuthorizer:
    async def authorize(
        self,
        *,
        command: RoomGraphCommand,
        thread: ThreadIdentity,
    ) -> None:
        if thread.shared_session:
            raise GraphThreadBindingError("GRAPH_SHARED_HEARING_INPUT_AUTHORIZER_REQUIRED")


class GraphCommandGateway:
    """Owns short database transactions; Java remains the only formal business writer."""

    def __init__(
        self,
        *,
        mode: GraphGatewayMode,
        pool: Any | None,
        threads: PostgresThreadIdentityRepository | None = None,
        registry: PostgresGraphVersionRegistry | None = None,
        ledger: PostgresCommandLedger | None = None,
        leases: PostgresLeaseRepository | None = None,
        input_authorizer: ImmutableInputAuthorizer | None = None,
        audit_sink: GatewayAuditSink | None = None,
        acquire_timeout_seconds: float = 3.0,
    ) -> None:
        if not isinstance(mode, GraphGatewayMode):
            raise GraphContractError("gateway mode must be DISABLED or SHADOW")
        if acquire_timeout_seconds <= 0:
            raise GraphContractError("pool acquire timeout must be positive")
        if mode is GraphGatewayMode.SHADOW and pool is None:
            raise GraphContractError("SHADOW gateway requires Graph PostgreSQL")
        if mode is GraphGatewayMode.SHADOW and input_authorizer is None:
            raise GraphContractError("SHADOW gateway requires an immutable input authorizer")
        self._mode = mode
        self._pool = pool
        self._threads = threads or PostgresThreadIdentityRepository()
        self._registry = registry or PostgresGraphVersionRegistry()
        self._ledger = ledger or PostgresCommandLedger()
        self._leases = leases or PostgresLeaseRepository()
        self._recovery = PostgresRecoveryCoordinator(
            ledger=self._ledger,
            leases=self._leases,
        )
        self._input_authorizer = input_authorizer or _FailClosedInputAuthorizer()
        self._audit = audit_sink or _NullAuditSink()
        self._acquire_timeout_seconds = acquire_timeout_seconds

    async def admit(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
        expected_thread: ThreadIdentity,
    ) -> GatewayAdmission:
        """Atomically register the command and durable transport nonce."""

        try:
            self._require_shadow()
            self._require_invocation_binding(command, verified_invocation)
            self._require_command_thread(command, expected_thread)
            await self._input_authorizer.authorize(command=command, thread=expected_thread)
            nonce = InvocationNonce.from_verified_invocation(verified_invocation)
            async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
                async with connection.transaction():
                    registry = await self._registry.load(
                        connection,
                        graph_key=command.graph_key,
                        graph_version=command.graph_version,
                        checkpoint_schema_version=command.checkpoint_schema_version,
                    )
                    version = registry.binding
                    self._require_registry_profile_binding(
                        command, verified_invocation, registry
                    )
                    binding = CommandBinding.from_command(
                        command,
                        tool_policy_version=version.tool_policy_version,
                    )
                    version.require_profile(binding.profile)
                    thread = await self._threads.ensure_registered(connection, expected_thread)
                    registration = await self._ledger.register_with_nonce(
                        connection,
                        binding=binding,
                        nonce=nonce,
                    )
                    if registration.created:
                        registry.require_new_shadow_command()
                    else:
                        registry.require_thread_restore()
                    if (
                        registration.command.status
                        in {
                            CommandStatus.REGISTERED,
                            CommandStatus.EXECUTING,
                        }
                        and thread.lifecycle is not ThreadLifecycle.ACTIVE
                    ):
                        raise GraphThreadBindingError("GRAPH_THREAD_NOT_ACTIVE")
            action = self._admission_action(registration.command.status)
            admission = GatewayAdmission(
                command=command,
                binding=binding,
                thread=expected_thread,
                registry=registry,
                record=registration.command,
                action=action,
                created=registration.created,
            )
            await self._emit(admission, event_type="graph.command.admitted", code=action.value)
            return admission
        except GraphRuntimeError as error:
            await self._audit.emit(
                GatewayAuditEvent(
                    event_type="graph.command.rejected",
                    code=error.code,
                    command_id=command.command_id,
                    thread_id=command.thread_id,
                    request_hash=command.request_hash,
                    graph_key=command.graph_key,
                    graph_version=command.graph_version,
                    traceparent=command.traceparent,
                )
            )
            raise

    async def acquire_execution(
        self,
        admission: GatewayAdmission,
        *,
        owner_id: str,
        attempt_id: str,
    ) -> GatewayExecution:
        self._require_shadow()
        if admission.action not in {
            AdmissionAction.ACQUIRE,
            AdmissionAction.OBSERVE_OR_TAKEOVER,
        }:
            raise GraphContractError("admission is not executable")
        if attempt_id != admission.command.attempt_id:
            raise GraphContractError("execution attempt differs from the signed command")
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                acquisition = await self._leases.acquire(
                    connection,
                    thread_id=admission.binding.thread_id,
                    command_id=admission.binding.command_id,
                    owner_id=owner_id,
                )
                if acquisition.kind is LeaseAcquisitionKind.IDEMPOTENT:
                    raise GraphNewAgentAttemptRequiredError(
                        "an existing lease cannot start the public AgentRun attempt again"
                    )
                if acquisition.kind is LeaseAcquisitionKind.TAKEOVER:
                    await self._resolve_displaced_execution(
                        connection,
                        acquisition=acquisition,
                        next_binding=admission.binding,
                    )
                current = await self._ledger.load(
                    connection,
                    thread_id=admission.binding.thread_id,
                    command_id=admission.binding.command_id,
                )
                self._ledger.require_same_binding(current.binding, admission.binding)
                candidate = await self._ledger.latest_attempt(
                    connection,
                    thread_id=admission.binding.thread_id,
                    command_id=admission.binding.command_id,
                )
                if current.status is not CommandStatus.REGISTERED or candidate is not None:
                    raise GraphNewAgentAttemptRequiredError(
                        "a public AgentRun attempt can execute its Graph command only once"
                    )
                thread_record = await self._threads.require_binding(
                    connection,
                    admission.thread,
                )
                if thread_record.lifecycle is not ThreadLifecycle.ACTIVE:
                    raise GraphThreadBindingError("GRAPH_THREAD_NOT_ACTIVE")
                current, attempt = await self._ledger.begin_attempt(
                    connection,
                    binding=admission.binding,
                    attempt_id=attempt_id,
                    owner_id=owner_id,
                    fencing_token=acquisition.lease.fencing_token,
                )
        fence = GraphFenceContext(
            thread_id=admission.binding.thread_id,
            command_id=admission.binding.command_id,
            owner_id=owner_id,
            fencing_token=acquisition.lease.fencing_token,
            request_hash=admission.binding.request_hash,
            room_epoch=admission.binding.room_epoch,
            graph_key=admission.binding.graph_key,
            graph_version=admission.binding.graph_version,
            checkpoint_schema_version=admission.binding.checkpoint_schema_version,
        )
        updated_admission = GatewayAdmission(
            command=admission.command,
            binding=admission.binding,
            thread=admission.thread,
            registry=admission.registry,
            record=current,
            action=admission.action,
            created=admission.created,
        )
        execution = GatewayExecution(
            updated_admission,
            attempt,
            acquisition.lease,
            fence,
            thread_record,
        )
        await self._emit(
            updated_admission,
            event_type="graph.command.execution_acquired",
            code=acquisition.kind.value,
            fencing_token=fence.fencing_token,
        )
        return execution

    async def _resolve_displaced_execution(
        self,
        connection: Any,
        *,
        acquisition: LeaseAcquisition,
        next_binding: CommandBinding,
    ) -> None:
        displaced = acquisition.displaced
        if displaced is None:
            raise GraphContractError("lease takeover lost its displaced command binding")
        if displaced.command_id == next_binding.command_id:
            return

        command = await self._ledger.load(
            connection,
            thread_id=next_binding.thread_id,
            command_id=displaced.command_id,
        )
        if command.status is CommandStatus.RESULT_CHECKPOINTED:
            raise GraphNewAgentAttemptRequiredError(
                "the displaced terminal command must reconcile before another command"
            )
        if command.status in {
            CommandStatus.COMPLETED,
            CommandStatus.CANCELLED,
            CommandStatus.ABORTED,
        }:
            return
        if command.status is CommandStatus.EXECUTING:
            attempt = await self._ledger.latest_attempt(
                connection,
                thread_id=next_binding.thread_id,
                command_id=displaced.command_id,
            )
            if (
                attempt is None
                or attempt.status is not AttemptStatus.EXECUTING
                or attempt.owner_id != displaced.owner_id
                or attempt.fencing_token != displaced.fencing_token
                or command.fencing_token != displaced.fencing_token
            ):
                raise GraphContractError(
                    "displaced executing command has no matching active attempt"
                )
            await self._ledger.finish_attempt(
                connection,
                attempt,
                status=AttemptStatus.LEASE_LOST,
                error_code="GRAPH_LEASE_DISPLACED",
                error_classification="LEASE_EXPIRED_TAKEOVER",
            )
        elif command.status is not CommandStatus.REGISTERED:
            raise GraphContractError("displaced command has an unknown durable status")
        await self._ledger.terminate(
            connection,
            binding=command.binding,
            status=CommandStatus.ABORTED,
            error_code="GRAPH_LEASE_DISPLACED",
            error_classification="LEASE_EXPIRED_TAKEOVER",
        )

    async def reconcile_only(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
        expected_thread: ThreadIdentity,
        owner_id: str,
    ) -> GraphReconciliation:
        """Return or reconcile one existing durable result without execution authority."""

        try:
            self._require_shadow()
            if not owner_id or len(owner_id) > 128:
                raise GraphContractError("Graph reconciliation owner is invalid")
            self._require_reconciliation_binding(command, verified_reconciliation)
            self._require_command_thread(command, expected_thread)
            nonce = InvocationNonce.from_verified_invocation(verified_reconciliation)
            async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
                async with connection.transaction():
                    await self._threads.require_binding(connection, expected_thread)
                    registry = await self._registry.require_thread_restore(
                        connection,
                        graph_key=command.graph_key,
                        graph_version=command.graph_version,
                        checkpoint_schema_version=command.checkpoint_schema_version,
                    )
                    self._require_registry_profile_binding(
                        command, verified_reconciliation, registry
                    )
                    binding = CommandBinding.from_command(
                        command,
                        tool_policy_version=registry.binding.tool_policy_version,
                    )
                    registry.binding.require_profile(binding.profile)
                    existing = await self._ledger.consume_nonce_for_existing(
                        connection,
                        binding=binding,
                        nonce=nonce,
                    )
                    disposition = self._reconciliation_disposition(existing.status)
                    completed, result = await self._recovery.reconcile_terminal(
                        connection,
                        binding=binding,
                        owner_id=owner_id,
                    )
            reconciliation = GraphReconciliation(
                disposition=disposition,
                command=completed,
                result=result,
                registry=registry,
            )
            await self._audit.emit(
                GatewayAuditEvent(
                    event_type="graph.command.result_reconciled",
                    code=disposition.value,
                    command_id=binding.command_id,
                    thread_id=binding.thread_id,
                    request_hash=binding.request_hash,
                    graph_key=binding.graph_key,
                    graph_version=binding.graph_version,
                    traceparent=command.traceparent,
                    fencing_token=completed.fencing_token,
                )
            )
            return reconciliation
        except GraphRuntimeError as error:
            await self._audit.emit(
                GatewayAuditEvent(
                    event_type="graph.command.result_rejected",
                    code=error.code,
                    command_id=command.command_id,
                    thread_id=command.thread_id,
                    request_hash=command.request_hash,
                    graph_key=command.graph_key,
                    graph_version=command.graph_version,
                    traceparent=command.traceparent,
                )
            )
            raise

    async def renew_execution(self, execution: GatewayExecution) -> LeaseRecord:
        self._require_shadow()
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                return await self._leases.renew(
                    connection,
                    thread_id=execution.fence.thread_id,
                    command_id=execution.fence.command_id,
                    owner_id=execution.fence.owner_id,
                    fencing_token=execution.fence.fencing_token,
                )

    async def record_provider_call(self, execution: GatewayExecution) -> GatewayExecution:
        """Persist provider-call intent before transport so crash recovery never guesses."""

        self._require_shadow()
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                attempt = await self._ledger.record_provider_call(
                    connection,
                    execution.attempt,
                )
        return replace(execution, attempt=attempt)

    async def finish_execution_attempt(
        self,
        execution: GatewayExecution,
        *,
        status: AttemptStatus,
        error_code: str,
        error_classification: str,
    ) -> GatewayExecution:
        self._require_shadow()
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                lease = await self._leases.cancel(
                    connection,
                    thread_id=execution.fence.thread_id,
                    active_command_id=execution.fence.command_id,
                    expected_fencing_token=execution.fence.fencing_token,
                    cancellation_command_id=execution.fence.command_id,
                )
                current = await self._ledger.load(
                    connection,
                    thread_id=execution.admission.binding.thread_id,
                    command_id=execution.admission.binding.command_id,
                )
                self._ledger.require_same_binding(
                    current.binding,
                    execution.admission.binding,
                )
                if current.status in {
                    CommandStatus.RESULT_CHECKPOINTED,
                    CommandStatus.COMPLETED,
                }:
                    command = current
                    attempt = execution.attempt
                else:
                    command_status = (
                        CommandStatus.CANCELLED
                        if status is AttemptStatus.CANCELLED
                        else CommandStatus.ABORTED
                    )
                    command = await self._ledger.terminate(
                        connection,
                        binding=execution.admission.binding,
                        status=command_status,
                        error_code=error_code,
                        error_classification=error_classification,
                    )
                    attempt = await self._ledger.finish_attempt(
                        connection,
                        execution.attempt,
                        status=status,
                        error_code=error_code,
                        error_classification=error_classification,
                    )
        admission = replace(
            execution.admission,
            record=command,
            action=self._admission_action(command.status),
        )
        return replace(execution, admission=admission, attempt=attempt, lease=lease)

    async def inspect_recovery(
        self,
        admission: GatewayAdmission,
    ) -> RecoveryDecision:
        self._require_shadow()
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                return await self._recovery.inspect(
                    connection,
                    binding=admission.binding,
                )

    async def reconcile_terminal(
        self,
        admission: GatewayAdmission,
        *,
        owner_id: str,
    ) -> tuple[CommandRecord, ResultRecord]:
        self._require_shadow()
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                completed, result = await self._recovery.reconcile_terminal(
                    connection,
                    binding=admission.binding,
                    owner_id=owner_id,
                )
        await self._emit(
            admission,
            event_type="graph.command.reconciled",
            code="COMPLETED_WITHOUT_MODEL_CALL",
            fencing_token=completed.fencing_token,
        )
        return completed, result

    async def referenced_verification_key_ids(self) -> frozenset[str]:
        """Return JWKS ``kid`` values retained by nonterminal durable commands."""

        self._require_shadow()
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                return await self._ledger.referenced_verification_key_ids(connection)

    async def execute_stream(
        self,
        *,
        execution: GatewayExecution,
        executor: ShadowGraphExecutor,
    ) -> AsyncIterator[AgentStreamEvent]:
        """Validate Agent Stream v2 identity/order and fence ``final`` through reconciliation."""

        self._require_shadow()
        expected_sequence = 0
        terminal_seen = False
        async for event in executor.stream(execution):
            if terminal_seen:
                raise GraphContractError("stream emitted an event after its terminal event")
            self._require_stream_identity(event, execution, expected_sequence)
            if expected_sequence == 0 and event.event_type != "attempt_started":
                raise GraphContractError("stream must begin with attempt_started")
            if expected_sequence > 0 and event.event_type == "attempt_started":
                raise GraphContractError("stream cannot contain another attempt_started event")
            if event.event_type == "attempt_reset":
                raise GraphContractError("AGENT_RUN_STREAM_RESET_AUTHORITY_VIOLATION")
            if event.event_type == "final":
                _, result = await self.reconcile_terminal(
                    execution.admission,
                    owner_id=execution.fence.owner_id,
                )
                payload = event.payload
                if (
                    payload.final_result_ref != result.result_ref
                    or payload.final_result_hash != result.result_hash
                ):
                    raise GraphTerminalBindingError("final stream event conflicts with ledger")
                terminal_seen = True
            elif event.event_type == "attempt_aborted":
                execution = await self.finish_execution_attempt(
                    execution,
                    status=AttemptStatus.FAILED,
                    error_code=event.payload.reason_code or "ATTEMPT_ABORTED",
                    error_classification="RECOVERABLE_ATTEMPT",
                )
                terminal_seen = True
            elif event.event_type == "error":
                execution = await self.finish_execution_attempt(
                    execution,
                    status=AttemptStatus.FAILED,
                    error_code=event.payload.error_code or "GRAPH_STREAM_ERROR",
                    error_classification="STREAM_ERROR",
                )
                terminal_seen = True
            expected_sequence += 1
            yield event
        if not terminal_seen:
            raise GraphContractError("stream ended without final, attempt_aborted, or error")

    def _require_shadow(self) -> None:
        if self._mode is not GraphGatewayMode.SHADOW or self._pool is None:
            raise GraphGatewayDisabledError()

    @staticmethod
    def _require_invocation_binding(
        command: RoomGraphCommand,
        invocation: VerifiedInvocation,
    ) -> None:
        expected = invocation_binding_claims(command)
        actual = invocation.claims.model_dump(mode="json")
        for name, value in expected.items():
            if name == "profile_bindings_hash":
                continue
            candidate = actual.get(name)
            if isinstance(value, int):
                matches = type(candidate) is int and candidate == value
            else:
                matches = isinstance(candidate, str) and hmac.compare_digest(candidate, value)
            if not matches:
                raise GraphThreadBindingError(f"verified invocation differs at {name}")
        if (
            not hmac.compare_digest(invocation.request_hash, command.request_hash)
            or invocation.key_id != command.invocation_context.envelope_key_id
        ):
            raise GraphThreadBindingError("verified invocation transport binding differs")

    @staticmethod
    def _require_reconciliation_binding(
        command: RoomGraphCommand,
        invocation: VerifiedReconciliation,
    ) -> None:
        if not isinstance(invocation, VerifiedReconciliation) or not isinstance(
            invocation.claims,
            ReconciliationClaims,
        ):
            raise GraphThreadBindingError("reconciliation credential type differs")
        expected = invocation_binding_claims(command)
        actual = invocation.claims.model_dump(mode="json")
        for name, value in expected.items():
            if name == "profile_bindings_hash":
                continue
            candidate = actual.get(name)
            if isinstance(value, int):
                matches = type(candidate) is int and candidate == value
            else:
                matches = isinstance(candidate, str) and hmac.compare_digest(candidate, value)
            if not matches:
                raise GraphThreadBindingError(f"verified reconciliation differs at {name}")
        if (
            not hmac.compare_digest(invocation.request_hash, command.request_hash)
            or invocation.claims.capability != "RECONCILE_ONLY"
            or invocation.claims.original_envelope_key_id
            != command.invocation_context.envelope_key_id
        ):
            raise GraphThreadBindingError("verified reconciliation transport binding differs")

    @staticmethod
    def _require_registry_profile_binding(
        command: RoomGraphCommand,
        invocation: VerifiedInvocation | VerifiedReconciliation,
        registry: RegistryRecord,
    ) -> None:
        expected = invocation_binding_claims(
            command,
            registry_binding_hash=registry.binding.binding_hash,
            tool_policy_version=registry.binding.tool_policy_version,
        )["profile_bindings_hash"]
        if not hmac.compare_digest(
            invocation.claims.profile_bindings_hash,
            str(expected),
        ):
            raise GraphThreadBindingError(
                "verified invocation differs from the exact Graph registry profile"
            )

    @staticmethod
    def _require_command_thread(
        command: RoomGraphCommand,
        thread: ThreadIdentity,
    ) -> None:
        scope = ActorScopeBinding.from_json(command.actor_scope.model_dump(mode="json"))
        expected = (
            command.thread_id,
            command.tenant_surrogate,
            command.case_id,
            command.room_type,
            command.room_epoch,
            scope,
            command.graph_key,
            command.graph_version,
            command.checkpoint_schema_version,
        )
        actual = (
            thread.thread_id,
            thread.tenant_surrogate,
            thread.case_id,
            thread.room_type.value,
            thread.room_epoch,
            thread.actor_scope,
            thread.graph_key,
            thread.graph_version,
            thread.checkpoint_schema_version,
        )
        if expected != actual:
            raise GraphThreadBindingError()

    @staticmethod
    def _admission_action(status: CommandStatus) -> AdmissionAction:
        return {
            CommandStatus.REGISTERED: AdmissionAction.ACQUIRE,
            CommandStatus.EXECUTING: AdmissionAction.OBSERVE_OR_TAKEOVER,
            CommandStatus.RESULT_CHECKPOINTED: AdmissionAction.RECONCILE,
            CommandStatus.COMPLETED: AdmissionAction.RETURN_CACHED,
            CommandStatus.CANCELLED: AdmissionAction.RETURN_CANCELLED,
            CommandStatus.ABORTED: AdmissionAction.RETURN_ABORTED,
        }[status]

    @staticmethod
    def _reconciliation_disposition(
        status: CommandStatus,
    ) -> ReconciliationDisposition:
        if status is CommandStatus.COMPLETED:
            return ReconciliationDisposition.RETURN_CACHED
        if status is CommandStatus.RESULT_CHECKPOINTED:
            return ReconciliationDisposition.RECONCILED_TERMINAL
        if status is CommandStatus.REGISTERED:
            raise GraphResultNotCommittedError()
        if status is CommandStatus.EXECUTING:
            raise GraphNewAgentAttemptRequiredError(
                "executing command requires a new public AgentRun attempt"
            )
        if status is CommandStatus.CANCELLED:
            raise GraphCommandCancelledError()
        if status is CommandStatus.ABORTED:
            raise GraphCommandAbortedError()
        raise GraphContractError("unknown durable Graph command status")

    @staticmethod
    def _require_stream_identity(
        event: AgentStreamEvent,
        execution: GatewayExecution,
        expected_sequence: int,
    ) -> None:
        command = execution.admission.command
        if (
            event.schema_version != "agent-stream.v2"
            or event.run_id != command.logical_run_id
            or event.attempt_id != execution.attempt.attempt_id
            or event.audience != command.actor_scope.audience
            or event.sequence_no != expected_sequence
        ):
            raise GraphContractError("Agent Stream v2 identity or ordering conflict")
        present_payload_fields = frozenset(event.payload.model_dump(exclude_none=True))
        if present_payload_fields != _STREAM_PAYLOAD_FIELDS[event.event_type]:
            raise GraphContractError("Agent Stream v2 payload fields conflict with event type")

    async def _emit(
        self,
        admission: GatewayAdmission,
        *,
        event_type: str,
        code: str,
        fencing_token: int | None = None,
    ) -> None:
        await self._audit.emit(
            GatewayAuditEvent(
                event_type=event_type,
                code=code,
                command_id=admission.binding.command_id,
                thread_id=admission.binding.thread_id,
                request_hash=admission.binding.request_hash,
                graph_key=admission.binding.graph_key,
                graph_version=admission.binding.graph_version,
                traceparent=admission.command.traceparent,
                fencing_token=fencing_token,
            )
        )
