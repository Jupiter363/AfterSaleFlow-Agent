"""Fail-closed admission and execution ports for signed synthetic shadow commands."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from enum import StrEnum
import hmac
from typing import Any, Final, Protocol, TypeVar

from psycopg import errors as psycopg_errors

from app.contracts.v1.models import AgentStreamEvent, RoomGraphCommand
from app.graph_runtime.errors import (
    GraphCommandAbortedError,
    GraphCommandCancelledError,
    GraphCommandDeadlineError,
    GraphCommandStateError,
    GraphContractError,
    GraphGatewayDisabledError,
    GraphNewAgentAttemptRequiredError,
    GraphResultNotCommittedError,
    GraphRuntimeError,
    GraphTerminalBindingError,
    GraphThreadBindingError,
    GraphVersionBindingError,
    normalize_transient_persistence_error,
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
    CommandProfileBinding,
    PostgresGraphVersionRegistry,
    RegistryRecord,
)
from app.graph_runtime.target_e2e import (
    PostgresTargetE2ERoomAuthorityRepository,
    PostgresTargetE2ESyntheticCaseRepository,
    TargetE2ERuntimeAuthority,
    VerifiedTargetE2EInvocation,
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

_ControlPlaneResult = TypeVar("_ControlPlaneResult")
_CONTROL_PLANE_RETRY_LIMIT: Final[int] = 2
_CONTROL_PLANE_RETRY_INITIAL_SECONDS: Final[float] = 0.05
_CONTROL_PLANE_RETRY_MAX_SECONDS: Final[float] = 0.2
_CONTROL_PLANE_LEASE_SAFETY_MARGIN: Final[timedelta] = timedelta(seconds=2)
_TARGET_E2E_GRAPH_KEY: Final[str] = "all-rooms.target-e2e.v1"
_TARGET_E2E_LEGACY_PROMPT_VERSION: Final[str] = "all-rooms-prompt.target-e2e.v1"
_TARGET_E2E_INTAKE_ROLES: Final[frozenset[str]] = frozenset({"USER", "MERCHANT"})


@dataclass(frozen=True, slots=True)
class GatewayAdmission:
    command: RoomGraphCommand
    binding: CommandBinding
    thread: ThreadIdentity
    registry: RegistryRecord
    record: CommandRecord
    action: AdmissionAction
    created: bool
    candidate_authority: TargetE2ERuntimeAuthority | None = None


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


class GraphTerminalResultBarrier(Protocol):
    """Optional post-commit hook that runs before a durable final reaches HTTP/SSE."""

    async def wait_after_durable_commit(
        self,
        *,
        admission: GatewayAdmission,
        result: ResultRecord,
    ) -> None: ...


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


class _NullTerminalResultBarrier:
    async def wait_after_durable_commit(
        self,
        *,
        admission: GatewayAdmission,
        result: ResultRecord,
    ) -> None:
        del admission, result


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
        terminal_result_barrier: GraphTerminalResultBarrier | None = None,
        acquire_timeout_seconds: float = 3.0,
    ) -> None:
        if not isinstance(mode, GraphGatewayMode):
            raise GraphContractError(
                "gateway mode must be DISABLED, SHADOW, or TARGET_E2E_CANDIDATE"
            )
        if acquire_timeout_seconds <= 0:
            raise GraphContractError("pool acquire timeout must be positive")
        if mode is not GraphGatewayMode.DISABLED and pool is None:
            raise GraphContractError("active gateway requires Graph PostgreSQL")
        if mode is not GraphGatewayMode.DISABLED and input_authorizer is None:
            raise GraphContractError("active gateway requires an immutable input authorizer")
        self._mode = mode
        self._pool = pool
        self._threads = threads or PostgresThreadIdentityRepository()
        self._registry = registry or PostgresGraphVersionRegistry()
        self._ledger = ledger or PostgresCommandLedger()
        self._leases = leases or PostgresLeaseRepository()
        self._target_room_authority = PostgresTargetE2ERoomAuthorityRepository()
        self._target_synthetic_cases = PostgresTargetE2ESyntheticCaseRepository()
        self._recovery = PostgresRecoveryCoordinator(
            ledger=self._ledger,
            leases=self._leases,
        )
        self._input_authorizer = input_authorizer or _FailClosedInputAuthorizer()
        self._audit = audit_sink or _NullAuditSink()
        self._terminal_result_barrier = terminal_result_barrier or _NullTerminalResultBarrier()
        self._acquire_timeout_seconds = acquire_timeout_seconds
        # A provider-intent recorder keeps its own immutable GatewayExecution.  Keep
        # the latest successful renewal by fence here so an old recorder snapshot
        # cannot shorten a still-active database lease during a transient retry.
        self._latest_leases: dict[tuple[str, str, str, int], LeaseRecord] = {}

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
            execution_lane, activation_id, candidate_authority = self._require_invocation_lane(
                verified_invocation
            )
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
                    self._require_registry_profile_binding(command, verified_invocation, registry)
                    binding = CommandBinding.from_command(
                        command,
                        tool_policy_version=version.tool_policy_version,
                        execution_lane=execution_lane,
                        activation_id=activation_id,
                        room_fencing_token=(
                            verified_invocation.room_fencing_token
                            if isinstance(verified_invocation, VerifiedTargetE2EInvocation)
                            else None
                        ),
                        command_hash=(
                            verified_invocation.command_hash
                            if isinstance(verified_invocation, VerifiedTargetE2EInvocation)
                            else None
                        ),
                        command_envelope_hash=(
                            verified_invocation.command_envelope_hash
                            if isinstance(verified_invocation, VerifiedTargetE2EInvocation)
                            else None
                        ),
                    )
                    self._require_registry_command_profile(
                        command=command,
                        registry=registry,
                        actual_profile=binding.profile,
                        execution_lane=execution_lane,
                    )
                    if candidate_authority is not None:
                        if not isinstance(
                            verified_invocation,
                            VerifiedTargetE2EInvocation,
                        ):
                            raise GraphThreadBindingError("TARGET_E2E_CREDENTIAL_REQUIRED")
                        await self._target_room_authority.advance(
                            connection,
                            authority=candidate_authority,
                            command=command,
                            room_fencing_token=verified_invocation.room_fencing_token,
                            command_hash=verified_invocation.command_hash,
                            command_envelope_hash=(verified_invocation.command_envelope_hash),
                        )
                        await self._target_synthetic_cases.reserve(
                            connection,
                            authority=candidate_authority,
                            case_id=command.case_id,
                        )
                    thread = await self._threads.ensure_registered(connection, expected_thread)
                    registration = await self._ledger.register_with_nonce(
                        connection,
                        binding=binding,
                        nonce=nonce,
                    )
                    if registration.created:
                        if execution_lane is GraphGatewayMode.SHADOW:
                            registry.require_new_shadow_command()
                        else:
                            registry.require_new_candidate_command()
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
                candidate_authority=candidate_authority,
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
                lease = await self._leases.renew(
                    connection,
                    thread_id=admission.binding.thread_id,
                    command_id=admission.binding.command_id,
                    owner_id=owner_id,
                    fencing_token=acquisition.lease.fencing_token,
                    command_deadline_at=admission.command.deadline_at,
                )
        fence = GraphFenceContext(
            thread_id=admission.binding.thread_id,
            command_id=admission.binding.command_id,
            owner_id=owner_id,
            fencing_token=lease.fencing_token,
            request_hash=admission.binding.request_hash,
            room_epoch=admission.binding.room_epoch,
            graph_key=admission.binding.graph_key,
            graph_version=admission.binding.graph_version,
            checkpoint_schema_version=admission.binding.checkpoint_schema_version,
            execution_lane=admission.binding.execution_lane,
            activation_id=admission.binding.activation_id,
            room_fencing_token=admission.binding.room_fencing_token,
            command_hash=admission.binding.command_hash,
            command_envelope_hash=admission.binding.command_envelope_hash,
            environment_id=(
                admission.candidate_authority.context.environmentId
                if admission.candidate_authority is not None
                else None
            ),
            environment_generation=(
                admission.candidate_authority.context.environmentGeneration
                if admission.candidate_authority is not None
                else None
            ),
            tenant_surrogate=(
                admission.thread.tenant_surrogate
                if admission.candidate_authority is not None
                else None
            ),
            case_id=(
                admission.thread.case_id if admission.candidate_authority is not None else None
            ),
            room_type=(
                admission.thread.room_type.value
                if admission.candidate_authority is not None
                else None
            ),
            binding_hash=(
                admission.registry.binding.binding_hash
                if admission.candidate_authority is not None
                else None
            ),
            code_build_id=(
                admission.registry.binding.code_build_id
                if admission.candidate_authority is not None
                else None
            ),
        )
        updated_admission = GatewayAdmission(
            command=admission.command,
            binding=admission.binding,
            thread=admission.thread,
            registry=admission.registry,
            record=current,
            action=admission.action,
            created=admission.created,
            candidate_authority=admission.candidate_authority,
        )
        execution = GatewayExecution(
            updated_admission,
            attempt,
            lease,
            fence,
            thread_record,
        )
        self._remember_lease(execution, lease)
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
                    self._require_registry_command_profile(
                        command=command,
                        registry=registry,
                        actual_profile=binding.profile,
                        execution_lane=binding.execution_lane,
                    )
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

    async def reconcile_candidate_only(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedTargetE2EInvocation,
        expected_thread: ThreadIdentity,
    ) -> ResultRecord:
        """Reconcile one already-admitted candidate command to its exact result envelope."""

        self._require_shadow()
        if self._mode is not GraphGatewayMode.TARGET_E2E_CANDIDATE:
            raise GraphGatewayDisabledError()
        lane, activation_id, authority = self._require_invocation_lane(verified_invocation)
        if authority is None:
            raise GraphThreadBindingError("TARGET_E2E_CREDENTIAL_REQUIRED")
        self._require_invocation_binding(command, verified_invocation)
        self._require_command_thread(command, expected_thread)
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
                    command,
                    verified_invocation,
                    registry,
                )
                binding = CommandBinding.from_command(
                    command,
                    tool_policy_version=registry.binding.tool_policy_version,
                    execution_lane=lane,
                    activation_id=activation_id,
                    room_fencing_token=verified_invocation.room_fencing_token,
                    command_hash=verified_invocation.command_hash,
                    command_envelope_hash=verified_invocation.command_envelope_hash,
                )
                self._require_registry_command_profile(
                    command=command,
                    registry=registry,
                    actual_profile=binding.profile,
                    execution_lane=lane,
                )
                _, result = await self._ledger.load_candidate_reconciliation_proof(
                    connection,
                    binding=binding,
                    issuer=verified_invocation.claims.iss,
                    key_id=verified_invocation.key_id,
                )
        return result

    @staticmethod
    def _lease_key(execution: GatewayExecution) -> tuple[str, str, str, int]:
        return (
            execution.fence.thread_id,
            execution.fence.command_id,
            execution.fence.owner_id,
            execution.fence.fencing_token,
        )

    def _remember_lease(
        self,
        execution: GatewayExecution,
        lease: LeaseRecord,
    ) -> LeaseRecord:
        """Retain the newest known lease without allowing stale snapshots to win."""

        key = self._lease_key(execution)
        current = self._latest_leases.get(key)
        if current is not None and (
            current.revision > lease.revision
            or (current.revision == lease.revision and current.renewed_at >= lease.renewed_at)
        ):
            return current
        self._latest_leases[key] = lease
        return lease

    def _latest_lease(self, execution: GatewayExecution) -> LeaseRecord:
        return self._remember_lease(execution, execution.lease)

    def _forget_lease(self, execution: GatewayExecution) -> None:
        self._latest_leases.pop(self._lease_key(execution), None)

    def cleanup_execution_lease(self, execution: GatewayExecution) -> None:
        """Forget the exact execution fence after its stream has fully joined."""

        self._forget_lease(execution)

    async def _retry_control_plane_operation(
        self,
        execution: GatewayExecution,
        operation: Callable[[], Awaitable[_ControlPlaneResult]],
        *,
        retry_permitted: Callable[[], bool] | None = None,
        retry_lock_contention_until_command_deadline: bool = False,
    ) -> _ControlPlaneResult:
        """Retry only proven transient control-plane failures inside a live lease window.

        The durable SQL mutation remains the authority on whether the lease is active.
        This local bound prevents retry amplification while allowing a pre-warmed
        control pool to recover from a short connection or lock interruption.
        """

        retries = 0
        delay_seconds = _CONTROL_PLANE_RETRY_INITIAL_SECONDS
        monotonic_deadline: float | None = None
        if retry_lock_contention_until_command_deadline:
            signed_deadline = execution.admission.command.deadline_at
            now = datetime.now(signed_deadline.tzinfo)
            remaining_seconds = (signed_deadline - now).total_seconds()
            if remaining_seconds <= 0:
                raise GraphCommandDeadlineError()
            monotonic_deadline = asyncio.get_running_loop().time() + remaining_seconds
        while True:
            try:
                if monotonic_deadline is not None:
                    remaining_seconds = (
                        monotonic_deadline - asyncio.get_running_loop().time()
                    )
                    if remaining_seconds <= 0:
                        raise GraphCommandDeadlineError()
                    try:
                        # Renewal is an idempotent owner/fence CAS.  Bound its
                        # complete pool-acquire/transaction/row-lock await by the
                        # one monotonic projection of the signed command deadline,
                        # not a fresh wall-clock allowance per retry.  External
                        # task cancellation still propagates unchanged.
                        async with asyncio.timeout_at(monotonic_deadline):
                            return await operation()
                    except TimeoutError as error:
                        raise GraphCommandDeadlineError() from error
                return await operation()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                if normalize_transient_persistence_error(error) is None:
                    raise
                if retry_permitted is not None and not retry_permitted():
                    raise
                if monotonic_deadline is not None:
                    # A checkpoint transaction can legitimately retain the exact
                    # lease row while it atomically persists one batch.  The cached
                    # pre-transaction lease expiry is not authority for that wait:
                    # the checkpoint transaction refreshes the row before commit.
                    # RENEW_SQL then reads the database clock only after acquiring
                    # that row and enforces the exact durable command deadline.
                    # Continue retries only inside the original monotonic budget.
                    remaining_seconds = (
                        monotonic_deadline - asyncio.get_running_loop().time()
                    )
                else:
                    if retries >= _CONTROL_PLANE_RETRY_LIMIT:
                        raise
                    lease = self._latest_lease(execution)
                    deadline = lease.lease_expires_at - _CONTROL_PLANE_LEASE_SAFETY_MARGIN
                    now = datetime.now(deadline.tzinfo)
                    remaining_seconds = (deadline - now).total_seconds()
                if delay_seconds >= remaining_seconds:
                    if monotonic_deadline is not None:
                        raise GraphCommandDeadlineError() from error
                    raise
                retries += 1
                await asyncio.sleep(delay_seconds)
                delay_seconds = min(
                    delay_seconds * 2,
                    _CONTROL_PLANE_RETRY_MAX_SECONDS,
                )

    async def renew_execution(self, execution: GatewayExecution) -> LeaseRecord:
        self._require_shadow()

        async def renew() -> LeaseRecord:
            async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
                async with connection.transaction():
                    return await self._leases.renew(
                        connection,
                        thread_id=execution.fence.thread_id,
                        command_id=execution.fence.command_id,
                        owner_id=execution.fence.owner_id,
                        fencing_token=execution.fence.fencing_token,
                        command_deadline_at=execution.admission.command.deadline_at,
                    )

        lease = await self._retry_control_plane_operation(
            execution,
            renew,
            retry_lock_contention_until_command_deadline=True,
        )
        return self._remember_lease(execution, lease)

    async def record_provider_call(self, execution: GatewayExecution) -> GatewayExecution:
        """Persist provider-call intent before transport so crash recovery never guesses."""

        self._require_shadow()
        provider_intent_started = False

        async def record() -> AttemptRecord:
            nonlocal provider_intent_started
            async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
                async with connection.transaction():
                    # PROVIDER_CALL_SQL increments provider_call_count.  If the
                    # connection breaks after this point, commit status is ambiguous
                    # and retrying could record two intents for one HTTP request.
                    provider_intent_started = True
                    return await self._ledger.record_provider_call(
                        connection,
                        execution.attempt,
                    )

        attempt = await self._retry_control_plane_operation(
            execution,
            record,
            retry_permitted=lambda: not provider_intent_started,
        )
        return replace(
            execution,
            attempt=attempt,
            lease=self._latest_lease(execution),
        )

    async def finish_execution_attempt(
        self,
        execution: GatewayExecution,
        *,
        status: AttemptStatus,
        error_code: str,
        error_classification: str,
    ) -> GatewayExecution:
        self._require_shadow()
        mutation_started = False

        async def finish_once() -> GatewayExecution:
            nonlocal mutation_started
            async with self._pool.connection(
                timeout=self._acquire_timeout_seconds
            ) as connection:
                async with connection.transaction():
                    current = await self._ledger.load(
                        connection,
                        thread_id=execution.admission.binding.thread_id,
                        command_id=execution.admission.binding.command_id,
                    )
                    self._ledger.require_same_binding(
                        current.binding,
                        execution.admission.binding,
                    )
                    durable_attempt = await self._ledger.latest_attempt(
                        connection,
                        thread_id=execution.admission.binding.thread_id,
                        command_id=execution.admission.binding.command_id,
                    )
                    replay = self._completed_attempt_abort_adoption(
                        execution,
                        command=current,
                        attempt=durable_attempt,
                        status=status,
                        error_code=error_code,
                        error_classification=error_classification,
                    )
                    if replay is not None:
                        command, attempt = replay
                        lease = execution.lease
                    else:
                        try:
                            lease = await self._leases.cancel(
                                connection,
                                thread_id=execution.fence.thread_id,
                                active_command_id=execution.fence.command_id,
                                expected_fencing_token=execution.fence.fencing_token,
                                cancellation_command_id=execution.fence.command_id,
                            )
                        except Exception as error:
                            # PostgreSQL lock timeout is proven pre-mutation and may
                            # take the normal bounded retry.  Any other failure after
                            # issuing CANCEL_SQL has ambiguous commit authority; the
                            # next attempt must first adopt exact durable terminal state.
                            if type(error) is not psycopg_errors.LockNotAvailable:
                                mutation_started = True
                            raise
                        mutation_started = True
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
                            durable_attempt = await self._ledger.latest_attempt(
                                connection,
                                thread_id=execution.admission.binding.thread_id,
                                command_id=execution.admission.binding.command_id,
                            )
                            adopted = self._completed_attempt_abort_adoption(
                                execution,
                                command=current,
                                attempt=durable_attempt,
                                status=status,
                                error_code=error_code,
                                error_classification=error_classification,
                            )
                            if adopted is None:
                                raise GraphCommandStateError(
                                    "durable result could not be adopted during cleanup"
                                )
                            command, attempt = adopted
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
            self.cleanup_execution_lease(execution)
            return replace(execution, admission=admission, attempt=attempt, lease=lease)

        try:
            return await self._retry_control_plane_operation(
                execution,
                finish_once,
                retry_permitted=lambda: not mutation_started,
            )
        except asyncio.CancelledError:
            raise
        except Exception:
            if not mutation_started:
                raise
            # Resolve an ambiguous first transaction by adopting the exact durable
            # terminal record, or perform the still-required mutation if it rolled
            # back.  This second pass is what makes retained cleanup replay-safe.
            return await finish_once()

    @staticmethod
    def _completed_attempt_abort_adoption(
        execution: GatewayExecution,
        *,
        command: CommandRecord,
        attempt: AttemptRecord | None,
        status: AttemptStatus,
        error_code: str,
        error_classification: str,
    ) -> tuple[CommandRecord, AttemptRecord] | None:
        expected_command_status = (
            CommandStatus.CANCELLED
            if status is AttemptStatus.CANCELLED
            else CommandStatus.ABORTED
        )
        result_terminal_statuses = {
            CommandStatus.RESULT_CHECKPOINTED,
            CommandStatus.COMPLETED,
        }
        if command.status not in result_terminal_statuses and not command.terminal:
            return None
        attempt_identity_mismatch = (
            attempt is None
            or attempt.attempt_id != execution.attempt.attempt_id
            or attempt.thread_id != execution.fence.thread_id
            or attempt.command_id != execution.fence.command_id
            or attempt.owner_id != execution.fence.owner_id
            or attempt.fencing_token != execution.fence.fencing_token
        )
        if attempt_identity_mismatch:
            raise GraphCommandStateError(
                "durable command cleanup replay differs from its exact attempt fence"
            )
        assert attempt is not None
        durable_result_adoption = (
            command.status in result_terminal_statuses
            and attempt.status is AttemptStatus.COMPLETED
            and command.fencing_token == execution.fence.fencing_token
            and command.committed_checkpoint_ns is not None
            and command.committed_checkpoint_id is not None
            and command.result_ref is not None
            and command.result_hash is not None
            and command.error_code is None
            and command.error_classification is None
            and attempt.error_code is None
            and attempt.error_classification is None
        )
        if durable_result_adoption:
            # The terminal checkpoint/result transaction can commit and release
            # its lease before the durable signal resumes this stream task.  Adopt
            # that exact completed attempt; cleanup must never overwrite it with an
            # abort merely because signal scheduling lagged the database commit.
            return command, attempt
        if command.status in result_terminal_statuses:
            raise GraphCommandStateError(
                "durable result cleanup replay has an incomplete terminal binding"
            )
        if command.status is expected_command_status and (
            attempt.status is status
            and attempt.error_code == error_code
            and attempt.error_classification == error_classification
            and command.error_code == error_code
            and command.error_classification == error_classification
        ):
            return command, attempt
        definitive_takeover = (
            command.status is CommandStatus.ABORTED
            and attempt.status is AttemptStatus.LEASE_LOST
            and command.error_code == attempt.error_code
            and command.error_classification == attempt.error_classification
            and command.error_code
            in {"GRAPH_LEASE_DISPLACED", "GRAPH_EXECUTION_LEASE_LOST"}
            and command.error_classification
            in {"LEASE_EXPIRED_TAKEOVER", "LEASE_LOST"}
        )
        if not definitive_takeover:
            raise GraphCommandStateError(
                "durable command terminal state conflicts with exact-fence cleanup replay"
            )
        return command, attempt

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
        durable_terminal_signal: asyncio.Event | None = None,
    ) -> tuple[CommandRecord, ResultRecord]:
        self._require_shadow()
        async with self._pool.connection(timeout=self._acquire_timeout_seconds) as connection:
            async with connection.transaction():
                completed, result = await self._recovery.reconcile_terminal(
                    connection,
                    binding=admission.binding,
                    owner_id=owner_id,
                )
            # The transaction has committed and released its durable lease before
            # audit delivery can block.  Wake stream renewal immediately so a valid
            # post-terminal renew failure is never treated as a live-command abort.
            if durable_terminal_signal is not None:
                durable_terminal_signal.set()
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
        durable_terminal_signal: asyncio.Event | None = None,
        terminal_processing_started: asyncio.Event | None = None,
    ) -> AsyncIterator[AgentStreamEvent]:
        """Validate Agent Stream v2 identity/order and fence ``final`` through reconciliation.

        When supplied, ``durable_terminal_signal`` is set once the durable command has
        transitioned to a terminal state, before any post-commit final validation or
        delivery barrier.  The caller can then stop lease renewal without confusing a
        valid terminal lease release for an execution failure.

        ``terminal_processing_started`` is deliberately weaker: it is set immediately
        after a validated terminal envelope is read and before its first terminal
        control-plane await.  It closes the scheduling gap between a terminal SQL
        transaction committing/releasing its lease and the durable signal being
        resumed.  Callers must still fail closed unless the durable signal eventually
        arrives (or the terminal source itself fails).
        """

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
            if (
                event.event_type in {"final", "attempt_aborted", "error"}
                and terminal_processing_started is not None
            ):
                terminal_processing_started.set()
            if event.event_type == "final":
                if durable_terminal_signal is not None:
                    _, result = await self.reconcile_terminal(
                        execution.admission,
                        owner_id=execution.fence.owner_id,
                        durable_terminal_signal=durable_terminal_signal,
                    )
                else:
                    _, result = await self.reconcile_terminal(
                        execution.admission,
                        owner_id=execution.fence.owner_id,
                    )
                self.cleanup_execution_lease(execution)
                payload = event.payload
                if (
                    payload.final_result_ref != result.result_ref
                    or payload.final_result_hash != result.result_hash
                ):
                    raise GraphTerminalBindingError("final stream event conflicts with ledger")
                await self._terminal_result_barrier.wait_after_durable_commit(
                    admission=execution.admission,
                    result=result,
                )
                terminal_seen = True
            elif event.event_type == "attempt_aborted":
                execution = await self.finish_execution_attempt(
                    execution,
                    status=AttemptStatus.FAILED,
                    error_code=event.payload.reason_code or "ATTEMPT_ABORTED",
                    error_classification="RECOVERABLE_ATTEMPT",
                )
                if durable_terminal_signal is not None:
                    durable_terminal_signal.set()
                terminal_seen = True
            elif event.event_type == "error":
                execution = await self.finish_execution_attempt(
                    execution,
                    status=AttemptStatus.FAILED,
                    error_code=event.payload.error_code or "GRAPH_STREAM_ERROR",
                    error_classification="STREAM_ERROR",
                )
                if durable_terminal_signal is not None:
                    durable_terminal_signal.set()
                terminal_seen = True
            expected_sequence += 1
            yield event
        if not terminal_seen:
            raise GraphContractError("stream ended without final, attempt_aborted, or error")

    def _require_shadow(self) -> None:
        if self._mode is GraphGatewayMode.DISABLED or self._pool is None:
            raise GraphGatewayDisabledError()

    def _require_invocation_lane(
        self,
        invocation: VerifiedInvocation,
    ) -> tuple[GraphGatewayMode, str | None, TargetE2ERuntimeAuthority | None]:
        if self._mode is GraphGatewayMode.SHADOW:
            if isinstance(invocation, VerifiedTargetE2EInvocation):
                raise GraphThreadBindingError("SHADOW_CANDIDATE_CREDENTIAL_REJECTED")
            return GraphGatewayMode.SHADOW, None, None
        if self._mode is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            if not isinstance(invocation, VerifiedTargetE2EInvocation):
                raise GraphThreadBindingError("TARGET_E2E_CREDENTIAL_REQUIRED")
            return self._mode, invocation.authority.activation_id, invocation.authority
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
    def _require_registry_command_profile(
        *,
        command: RoomGraphCommand,
        registry: RegistryRecord,
        actual_profile: CommandProfileBinding,
        execution_lane: GraphGatewayMode,
    ) -> None:
        """Allow only the frozen candidate Intake prompt alias.

        The durable command and its signed profile continue to carry the actual
        role-specific PromptComposer ID.  The exception exists solely because the
        candidate all-room registry row is pinned to the older room-level prompt.
        """

        binding = registry.binding
        role = command.actor_scope.actor_role
        audience = command.actor_scope.audience
        is_target_intake_candidate = (
            execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE
            and command.graph_key == _TARGET_E2E_GRAPH_KEY
            and binding.graph_key == _TARGET_E2E_GRAPH_KEY
            and command.room_type == "INTAKE"
        )
        if is_target_intake_candidate:
            if (
                binding.prompt_version == _TARGET_E2E_LEGACY_PROMPT_VERSION
                and role in _TARGET_E2E_INTAKE_ROLES
                and audience == role
                and actual_profile
                == replace(
                    binding.command_profile,
                    prompt_version=f"DISPUTE_INTAKE_OFFICER:{role}:v1",
                )
            ):
                return
            raise GraphVersionBindingError()
        binding.require_profile(actual_profile)

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
