"""Durable recovery decisions for the four frozen Graph crash boundaries."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
import hmac
from typing import Any, Final

from app.contracts.v1.models import command_provider_attempt_limit
from app.graph_runtime.errors import (
    GraphLeaseUnavailableError,
    GraphRecoveryError,
    GraphTerminalBindingError,
)
from app.graph_runtime.lease import LeaseInspection, PostgresLeaseRepository
from app.graph_runtime.ledger import (
    AttemptRecord,
    AttemptStatus,
    CommandBinding,
    CommandRecord,
    CommandStatus,
    PostgresCommandLedger,
    ResultRecord,
)


class RecoveryAction(StrEnum):
    RESUME_BEFORE_MODEL = "RESUME_BEFORE_MODEL"
    REQUIRE_NEW_AGENT_ATTEMPT = "REQUIRE_NEW_AGENT_ATTEMPT"
    RECONCILE_TERMINAL = "RECONCILE_TERMINAL"
    RETURN_CACHED = "RETURN_CACHED"
    RETURN_CANCELLED = "RETURN_CANCELLED"
    RETURN_ABORTED = "RETURN_ABORTED"


@dataclass(frozen=True, slots=True)
class RecoveryDecision:
    action: RecoveryAction
    invoke_model: bool
    emit_attempt_reset: bool
    reason_code: str

    def __post_init__(self) -> None:
        if self.emit_attempt_reset:
            raise GraphRecoveryError(
                "Graph lease recovery cannot create a public AgentRun attempt reset"
            )
        should_invoke_model = self.action is RecoveryAction.RESUME_BEFORE_MODEL
        if self.invoke_model is not should_invoke_model:
            raise GraphRecoveryError("Graph recovery decision has inconsistent model authority")


CHECKPOINT_METADATA_SQL: Final[str] = """
select metadata
  from checkpoints
 where thread_id = %s and checkpoint_ns = %s and checkpoint_id = %s
 for share
"""


def decide_recovery(
    command: CommandRecord,
    *,
    latest_attempt: AttemptRecord | None,
    retry_allowed: bool,
) -> RecoveryDecision:
    """Classify only durable facts; process memory is intentionally irrelevant."""

    if command.status is CommandStatus.COMPLETED:
        return RecoveryDecision(
            RecoveryAction.RETURN_CACHED,
            invoke_model=False,
            emit_attempt_reset=False,
            reason_code="COMMAND_ALREADY_COMPLETED",
        )
    if command.status is CommandStatus.RESULT_CHECKPOINTED:
        return RecoveryDecision(
            RecoveryAction.RECONCILE_TERMINAL,
            invoke_model=False,
            emit_attempt_reset=False,
            reason_code="TERMINAL_CHECKPOINT_COMMITTED",
        )
    if command.status is CommandStatus.CANCELLED:
        return RecoveryDecision(
            RecoveryAction.RETURN_CANCELLED,
            invoke_model=False,
            emit_attempt_reset=False,
            reason_code="COMMAND_CANCELLED",
        )
    if command.status is CommandStatus.ABORTED:
        return RecoveryDecision(
            RecoveryAction.RETURN_ABORTED,
            invoke_model=False,
            emit_attempt_reset=False,
            reason_code="COMMAND_ABORTED",
        )
    if command.status is CommandStatus.REGISTERED:
        if latest_attempt is not None:
            raise GraphRecoveryError("GRAPH_REGISTERED_COMMAND_HAS_ATTEMPT")
        if not retry_allowed:
            raise GraphRecoveryError("GRAPH_RETRY_BUDGET_EXHAUSTED")
        return RecoveryDecision(
            RecoveryAction.RESUME_BEFORE_MODEL,
            invoke_model=True,
            emit_attempt_reset=False,
            reason_code="NO_MODEL_CALL_DURABLY_STARTED",
        )
    if latest_attempt is None:
        raise GraphRecoveryError("GRAPH_EXECUTING_COMMAND_ATTEMPT_MISSING")
    return RecoveryDecision(
        RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT,
        invoke_model=False,
        emit_attempt_reset=False,
        reason_code=(
            "MODEL_RESPONSE_NOT_CHECKPOINTED"
            if latest_attempt.provider_call_count
            else "PUBLIC_ATTEMPT_EXECUTION_ALREADY_STARTED"
        ),
    )


class PostgresRecoveryCoordinator:
    def __init__(
        self,
        *,
        ledger: PostgresCommandLedger,
        leases: PostgresLeaseRepository,
    ) -> None:
        self._ledger = ledger
        self._leases = leases

    async def inspect(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
    ) -> RecoveryDecision:
        lease = await self._leases.lock_for_recovery(
            connection,
            thread_id=binding.thread_id,
        )
        command = await self._ledger.load(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        PostgresCommandLedger.require_same_binding(command.binding, binding)
        attempt = await self._ledger.latest_attempt(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        if command.status is CommandStatus.EXECUTING:
            return await self._inspect_executing(
                connection,
                binding=binding,
                command=command,
                attempt=attempt,
                lease=lease,
            )
        budget = await self._ledger.load_recovery_budget(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        request_budget = binding.request_json.get("retry_budget")
        if not isinstance(request_budget, Mapping):
            raise GraphRecoveryError("GRAPH_RETRY_BUDGET_MISSING")
        provider_remaining = request_budget.get("provider_attempts_remaining")
        activity_remaining = request_budget.get("activity_attempts_remaining")
        provider_limit = command_provider_attempt_limit(
            binding.request_json.get("room_type"),
            binding.request_json.get("stage_code"),
        )
        if (
            not isinstance(provider_remaining, int)
            or isinstance(provider_remaining, bool)
            or not 0 <= provider_remaining <= provider_limit
            or not isinstance(activity_remaining, int)
            or isinstance(activity_remaining, bool)
            or not 0 <= activity_remaining <= 3
        ):
            raise GraphRecoveryError("GRAPH_RETRY_BUDGET_INVALID")
        retry_allowed = (
            budget.deadline_open
            and command.attempt_count < activity_remaining
            and budget.provider_call_count < provider_remaining
        )
        if (
            command.status is CommandStatus.REGISTERED
            and attempt is None
            and not retry_allowed
        ):
            deadline_exhausted = not budget.deadline_open
            command = await self._ledger.terminate(
                connection,
                binding=binding,
                status=CommandStatus.ABORTED,
                error_code=(
                    "GRAPH_COMMAND_DEADLINE_EXCEEDED"
                    if deadline_exhausted
                    else "GRAPH_RETRY_BUDGET_EXHAUSTED"
                ),
                error_classification=("DEADLINE" if deadline_exhausted else "RETRY_BUDGET"),
            )
        return decide_recovery(
            command,
            latest_attempt=attempt,
            retry_allowed=retry_allowed,
        )

    async def _inspect_executing(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        command: CommandRecord,
        attempt: AttemptRecord | None,
        lease: LeaseInspection | None,
    ) -> RecoveryDecision:
        """Serialize active observation or expiry fencing before recovery decides."""

        if attempt is None:
            raise GraphRecoveryError("GRAPH_EXECUTING_COMMAND_ATTEMPT_MISSING")
        if (
            command.fencing_token is None
            or attempt.thread_id != binding.thread_id
            or attempt.command_id != binding.command_id
            or attempt.fencing_token != command.fencing_token
            or attempt.status is not AttemptStatus.EXECUTING
        ):
            raise GraphRecoveryError("GRAPH_EXECUTING_FENCE_INCONSISTENT")

        current = None if lease is None else lease.lease
        same_execution = current is not None and (
            current.thread_id,
            current.command_id,
            current.owner_id,
            current.fencing_token,
        ) == (
            binding.thread_id,
            binding.command_id,
            attempt.owner_id,
            command.fencing_token,
        )
        if same_execution and lease is not None and lease.active:
            raise GraphLeaseUnavailableError(
                "the durable Graph execution lease is still active"
            )
        if (
            current is not None
            and current.command_id == binding.command_id
            and lease is not None
            and lease.active
            and not same_execution
        ):
            raise GraphRecoveryError("GRAPH_ACTIVE_LEASE_BINDING_INCONSISTENT")

        if (
            same_execution
            and current is not None
            and current.released_at is None
            and current.cancelled_at is None
        ):
            await self._leases.cancel(
                connection,
                thread_id=binding.thread_id,
                active_command_id=binding.command_id,
                expected_fencing_token=command.fencing_token,
                cancellation_command_id=binding.command_id,
            )

        aborted = await self._ledger.terminate(
            connection,
            binding=binding,
            status=CommandStatus.ABORTED,
            error_code="GRAPH_EXECUTION_LEASE_LOST",
            error_classification="LEASE_LOST",
        )
        if aborted.status is not CommandStatus.ABORTED:
            return decide_recovery(
                aborted,
                latest_attempt=attempt,
                retry_allowed=False,
            )
        await self._ledger.finish_attempt(
            connection,
            attempt,
            status=AttemptStatus.LEASE_LOST,
            error_code="GRAPH_EXECUTION_LEASE_LOST",
            error_classification="LEASE_LOST",
        )
        return RecoveryDecision(
            RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT,
            invoke_model=False,
            emit_attempt_reset=False,
            reason_code="GRAPH_EXECUTION_LEASE_LOST",
        )

    async def reconcile_terminal(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        owner_id: str,
    ) -> tuple[CommandRecord, ResultRecord]:
        """Complete a committed terminal checkpoint without another model call."""

        await self._leases.lock_for_recovery(
            connection,
            thread_id=binding.thread_id,
        )
        command = await self._ledger.load(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        PostgresCommandLedger.require_same_binding(command.binding, binding)
        if command.status is CommandStatus.COMPLETED:
            result = await self._ledger.load_result(
                connection,
                thread_id=binding.thread_id,
                command_id=binding.command_id,
            )
            self._validate_command_result(command, result)
            return command, result
        if command.status is not CommandStatus.RESULT_CHECKPOINTED:
            raise GraphRecoveryError("command has no terminal checkpoint to reconcile")
        if (
            command.committed_checkpoint_id is None
            or command.committed_checkpoint_ns is None
            or command.result_ref is None
            or command.result_hash is None
            or command.fencing_token is None
        ):
            raise GraphTerminalBindingError("terminal command binding is incomplete")

        acquisition = await self._leases.acquire(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
            owner_id=owner_id,
        )
        if (
            acquisition.displaced is not None
            and acquisition.displaced.command_id != binding.command_id
        ):
            raise GraphRecoveryError(
                "terminal reconciliation cannot displace another Graph command"
            )
        await self._leases.lock_current(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
            owner_id=owner_id,
            fencing_token=acquisition.lease.fencing_token,
        )
        result = await self._ledger.load_result(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        self._validate_command_result(command, result)
        await self._validate_checkpoint(connection, command, result)
        await self._ledger.complete_checkpointed_attempt(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
            checkpoint_fencing_token=command.fencing_token,
        )
        completed = await self._ledger.complete_from_checkpoint(
            connection,
            binding=binding,
            fencing_token=acquisition.lease.fencing_token,
            result=result,
        )
        await self._leases.release(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
            owner_id=owner_id,
            fencing_token=acquisition.lease.fencing_token,
        )
        return completed, result

    @staticmethod
    def _validate_command_result(command: CommandRecord, result: ResultRecord) -> None:
        expected = (
            command.binding.thread_id,
            command.binding.command_id,
            command.binding.request_hash,
            command.committed_checkpoint_ns,
            command.committed_checkpoint_id,
            command.result_ref,
            command.result_hash,
        )
        actual = (
            result.thread_id,
            result.command_id,
            result.request_hash,
            result.checkpoint_ns,
            result.checkpoint_id,
            result.result_ref,
            result.result_hash,
        )
        if expected[:-1] != actual[:-1] or not hmac.compare_digest(
            str(expected[-1]), result.result_hash
        ):
            raise GraphTerminalBindingError("command and immutable result disagree")
        PostgresCommandLedger.require_result_matches_command(command, result)

    @staticmethod
    async def _validate_checkpoint(
        connection: Any,
        command: CommandRecord,
        result: ResultRecord,
    ) -> None:
        row = await (
            await connection.execute(
                CHECKPOINT_METADATA_SQL,
                (
                    command.binding.thread_id,
                    result.checkpoint_ns,
                    result.checkpoint_id,
                ),
            )
        ).fetchone()
        if row is None or not isinstance(row.get("metadata"), Mapping):
            raise GraphTerminalBindingError("terminal checkpoint metadata is missing")
        metadata = row["metadata"]
        expected = {
            "graph_thread_id": command.binding.thread_id,
            "graph_command_id": command.binding.command_id,
            "graph_request_hash": command.binding.request_hash,
            "graph_room_epoch": command.binding.room_epoch,
            "graph_key": command.binding.graph_key,
            "graph_version": command.binding.graph_version,
            "graph_checkpoint_schema_version": command.binding.checkpoint_schema_version,
            "graph_fencing_token": command.fencing_token,
            "graph_result_hash": result.result_hash,
            "graph_result_ref": result.result_ref,
        }
        if any(metadata.get(key) != value for key, value in expected.items()):
            raise GraphTerminalBindingError("terminal checkpoint binding is inconsistent")
