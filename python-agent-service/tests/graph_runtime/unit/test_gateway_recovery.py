from __future__ import annotations

from dataclasses import replace
from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.errors import (
    GraphLeaseUnavailableError,
    GraphRecoveryError,
    GraphTerminalBindingError,
)
from app.graph_runtime.lease import (
    LeaseAcquisition,
    LeaseAcquisitionKind,
    LeaseDisplacement,
    LeaseInspection,
    LeaseRecord,
)
from app.graph_runtime.ledger import (
    AttemptRecord,
    AttemptStatus,
    CommandBinding,
    CommandRecord,
    CommandStatus,
    RecoveryBudget,
    ResultRecord,
)
from app.graph_runtime.recovery import (
    PostgresRecoveryCoordinator,
    RecoveryAction,
    RecoveryDecision,
    decide_recovery,
)
from app.graph_runtime.registry import CommandProfileBinding


NOW = datetime(2026, 7, 19, 8, 0, tzinfo=timezone.utc)
THREAD = f"grt.v1.{'4' * 32}"
RESULT_HASH = "e" * 64


def _binding(
    *,
    room_type: str | None = None,
    stage_code: str | None = None,
    provider_attempt_budget: int = 2,
) -> CommandBinding:
    request: dict[str, Any] = {
        "schema_version": "room-graph-command.v1",
        "logical_run_id": "run-1",
        "attempt_id": "attempt-1",
        "graph_key": "evidence.flow",
        "graph_version": "evidence.v2",
        "retry_budget": {
            "provider_attempts_remaining": provider_attempt_budget,
            "activity_attempts_remaining": 2,
        },
    }
    if room_type is not None:
        request["room_type"] = room_type
    if stage_code is not None:
        request["stage_code"] = stage_code
    request["request_hash"] = canonical_sha256(request)
    return CommandBinding(
        thread_id=THREAD,
        command_id="command-1",
        request_schema_version="room-graph-command.v1",
        request_json=request,
        request_hash=request["request_hash"],
        room_epoch=3,
        graph_key="evidence.flow",
        graph_version="evidence.v2",
        checkpoint_schema_version="evidence.checkpoint.v2",
        profile=CommandProfileBinding(
            command_schema_version="room-graph-command.v1",
            prompt_version="evidence.prompt.v2",
            model_profile_id="model.standard.v1",
            output_schema_version="evidence.output.v2",
            policy_version="policy.v2",
            guardrail_version="guardrail.v2",
            tool_policy_version="tools.none.v1",
        ),
        deadline_at=NOW + timedelta(minutes=1),
    )


def _command(
    status: CommandStatus,
    *,
    binding: CommandBinding | None = None,
) -> CommandRecord:
    terminal_checkpoint = status in {CommandStatus.RESULT_CHECKPOINTED, CommandStatus.COMPLETED}
    return CommandRecord(
        binding=binding or _binding(),
        status=status,
        attempt_count=1 if status is not CommandStatus.REGISTERED else 0,
        fencing_token=1 if status is not CommandStatus.REGISTERED else None,
        start_checkpoint_ns=None,
        start_checkpoint_id=None,
        committed_checkpoint_ns="evidence" if terminal_checkpoint else None,
        committed_checkpoint_id="checkpoint-1" if terminal_checkpoint else None,
        result_ref="s3://graph-results/result-1.json" if terminal_checkpoint else None,
        result_hash=RESULT_HASH if terminal_checkpoint else None,
        error_code=None,
        error_classification=None,
        revision=2,
    )


def _attempt(provider_calls: int) -> AttemptRecord:
    return AttemptRecord(
        attempt_id="attempt-1",
        thread_id=THREAD,
        command_id="command-1",
        attempt_no=1,
        owner_id="worker-old",
        fencing_token=1,
        status=AttemptStatus.EXECUTING,
        provider_call_count=provider_calls,
        error_code=None,
        error_classification=None,
    )


def _result() -> ResultRecord:
    binding = _binding()
    result_json: dict[str, Any] = {
        "schema_version": "room-graph-result.v1",
        "command_id": "command-1",
        "logical_run_id": "run-1",
        "attempt_id": "attempt-1",
        "graph_key": "evidence.flow",
        "graph_version": "evidence.v2",
        "checkpoint_id": "checkpoint-1",
        "cognitive_revision": 1,
        "status": "COMPLETED",
        "public_event_proposals": [],
        "artifact_operations": [],
        "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
        "execution_metadata": {
            "prompt_version": binding.profile.prompt_version,
            "model_profile_id": binding.profile.model_profile_id,
            "schema_version": binding.profile.output_schema_version,
            "policy_version": binding.profile.policy_version,
            "guardrail_version": binding.profile.guardrail_version,
        },
    }
    result_json["output_hash"] = RESULT_HASH
    return ResultRecord(
        result_id="result-1",
        thread_id=THREAD,
        command_id="command-1",
        request_hash=binding.request_hash,
        result_schema_version="room-graph-result.v1",
        checkpoint_ns="evidence",
        checkpoint_id="checkpoint-1",
        cognitive_revision=1,
        terminal_status="COMPLETED",
        result_json=result_json,
        result_ref="s3://graph-results/result-1.json",
        result_hash=RESULT_HASH,
        usage_json={"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
    )


def _lease(
    token: int = 2,
    *,
    owner_id: str = "worker-new",
) -> LeaseRecord:
    return LeaseRecord(
        thread_id=THREAD,
        command_id="command-1",
        owner_id=owner_id,
        fencing_token=token,
        lease_expires_at=NOW + timedelta(seconds=30),
        acquired_at=NOW,
        renewed_at=NOW,
        released_at=None,
        cancelled_at=None,
        cancelled_by_command_id=None,
        revision=token - 1,
    )


def test_crash_before_model_resumes_from_committed_checkpoint() -> None:
    decision = decide_recovery(
        _command(CommandStatus.REGISTERED),
        latest_attempt=None,
        retry_allowed=True,
    )

    assert decision.action is RecoveryAction.RESUME_BEFORE_MODEL
    assert decision.invoke_model is True
    assert decision.emit_attempt_reset is False


def test_crash_after_model_before_checkpoint_requires_new_public_attempt() -> None:
    decision = decide_recovery(
        _command(CommandStatus.EXECUTING),
        latest_attempt=_attempt(1),
        retry_allowed=True,
    )

    assert decision.action is RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT
    assert decision.invoke_model is False
    assert decision.emit_attempt_reset is False
    assert decision.reason_code == "MODEL_RESPONSE_NOT_CHECKPOINTED"


def test_crash_after_stream_start_before_model_still_requires_new_public_attempt() -> None:
    decision = decide_recovery(
        _command(CommandStatus.EXECUTING),
        latest_attempt=_attempt(0),
        retry_allowed=True,
    )

    assert decision.action is RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT
    assert decision.invoke_model is False
    assert decision.reason_code == "PUBLIC_ATTEMPT_EXECUTION_ALREADY_STARTED"


def test_inconsistent_command_attempt_pairs_fail_closed() -> None:
    with pytest.raises(GraphRecoveryError, match="REGISTERED_COMMAND_HAS_ATTEMPT"):
        decide_recovery(
            _command(CommandStatus.REGISTERED),
            latest_attempt=_attempt(0),
            retry_allowed=True,
        )
    with pytest.raises(GraphRecoveryError, match="EXECUTING_COMMAND_ATTEMPT_MISSING"):
        decide_recovery(
            _command(CommandStatus.EXECUTING),
            latest_attempt=None,
            retry_allowed=True,
        )


def test_recovery_decision_cannot_grant_model_or_reset_authority_inconsistently() -> None:
    with pytest.raises(GraphRecoveryError, match="model authority"):
        RecoveryDecision(
            RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT,
            invoke_model=True,
            emit_attempt_reset=False,
            reason_code="INVALID",
        )
    with pytest.raises(GraphRecoveryError, match="cannot create"):
        RecoveryDecision(
            RecoveryAction.RESUME_BEFORE_MODEL,
            invoke_model=True,
            emit_attempt_reset=True,
            reason_code="INVALID",
        )


def test_crash_after_checkpoint_reconciles_without_second_model_call() -> None:
    decision = decide_recovery(
        _command(CommandStatus.RESULT_CHECKPOINTED),
        latest_attempt=_attempt(1),
        retry_allowed=True,
    )

    assert decision.action is RecoveryAction.RECONCILE_TERMINAL
    assert decision.invoke_model is False


def test_crash_after_completion_returns_cached_without_second_model_call() -> None:
    decision = decide_recovery(
        _command(CommandStatus.COMPLETED),
        latest_attempt=_attempt(1),
        retry_allowed=True,
    )

    assert decision.action is RecoveryAction.RETURN_CACHED
    assert decision.invoke_model is False


def test_started_attempt_requires_new_public_attempt_even_when_budget_is_closed() -> None:
    decision = decide_recovery(
        _command(CommandStatus.EXECUTING),
        latest_attempt=_attempt(1),
        retry_allowed=False,
    )

    assert decision.action is RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT
    assert decision.invoke_model is False


class _InspectionLedger:
    def __init__(self, *, deadline_open: bool, attempt_count: int = 1) -> None:
        self.deadline_open = deadline_open
        self.attempt_count = attempt_count

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return replace(
            _command(CommandStatus.EXECUTING),
            attempt_count=self.attempt_count,
        )

    async def latest_attempt(self, connection: Any, **kwargs: Any) -> AttemptRecord:
        return _attempt(1)

    async def load_recovery_budget(self, connection: Any, **kwargs: Any) -> RecoveryBudget:
        return RecoveryBudget(
            deadline_open=self.deadline_open,
            provider_call_count=1,
        )

    async def terminate(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return replace(
            _command(CommandStatus.EXECUTING),
            attempt_count=self.attempt_count,
            status=CommandStatus.ABORTED,
            error_code=kwargs["error_code"],
            error_classification=kwargs["error_classification"],
        )

    async def finish_attempt(
        self,
        connection: Any,
        attempt: AttemptRecord,
        **kwargs: Any,
    ) -> AttemptRecord:
        return replace(
            attempt,
            status=kwargs["status"],
            error_code=kwargs["error_code"],
            error_classification=kwargs["error_classification"],
        )


class _RegisteredBudgetLedger:
    def __init__(
        self,
        *,
        deadline_open: bool,
        binding: CommandBinding | None = None,
        provider_call_count: int = 0,
    ) -> None:
        self.deadline_open = deadline_open
        self.binding = binding or _binding()
        self.provider_call_count = provider_call_count
        self.termination: tuple[CommandStatus, str, str] | None = None

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return _command(CommandStatus.REGISTERED, binding=self.binding)

    async def latest_attempt(self, connection: Any, **kwargs: Any) -> None:
        return None

    async def load_recovery_budget(self, connection: Any, **kwargs: Any) -> RecoveryBudget:
        return RecoveryBudget(
            deadline_open=self.deadline_open,
            provider_call_count=self.provider_call_count,
        )

    async def terminate(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        status: CommandStatus,
        error_code: str,
        error_classification: str,
    ) -> CommandRecord:
        self.termination = (status, error_code, error_classification)
        return replace(
            _command(CommandStatus.REGISTERED, binding=self.binding),
            status=status,
            error_code=error_code,
            error_classification=error_classification,
        )


@pytest.mark.asyncio
async def test_recovery_uses_durable_db_deadline_and_call_count() -> None:
    coordinator = PostgresRecoveryCoordinator(
        ledger=_InspectionLedger(deadline_open=True),  # type: ignore[arg-type]
        leases=_Leases(),  # type: ignore[arg-type]
    )

    decision = await coordinator.inspect(object(), binding=_binding())

    assert decision.action is RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT


@pytest.mark.asyncio
async def test_active_execution_lease_requires_retrying_the_same_command() -> None:
    active = LeaseInspection(
        lease=_lease(token=1, owner_id="worker-old"),
        database_now=NOW,
    )
    coordinator = PostgresRecoveryCoordinator(
        ledger=_InspectionLedger(deadline_open=True),  # type: ignore[arg-type]
        leases=_Leases(inspection=active),  # type: ignore[arg-type]
    )

    with pytest.raises(GraphLeaseUnavailableError):
        await coordinator.inspect(object(), binding=_binding())


@pytest.mark.asyncio
async def test_expired_db_deadline_never_reexecutes_the_started_attempt() -> None:
    coordinator = PostgresRecoveryCoordinator(
        ledger=_InspectionLedger(deadline_open=False),  # type: ignore[arg-type]
        leases=_Leases(),  # type: ignore[arg-type]
    )

    decision = await coordinator.inspect(object(), binding=_binding())

    assert decision.action is RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT
    assert decision.invoke_model is False


@pytest.mark.asyncio
async def test_activity_attempt_budget_never_reexecutes_the_started_attempt() -> None:
    coordinator = PostgresRecoveryCoordinator(
        ledger=_InspectionLedger(  # type: ignore[arg-type]
            deadline_open=True,
            attempt_count=2,
        ),
        leases=_Leases(),  # type: ignore[arg-type]
    )

    decision = await coordinator.inspect(object(), binding=_binding())

    assert decision.action is RecoveryAction.REQUIRE_NEW_AGENT_ATTEMPT
    assert decision.invoke_model is False


@pytest.mark.asyncio
async def test_unstarted_expired_command_is_durably_aborted() -> None:
    ledger = _RegisteredBudgetLedger(deadline_open=False)
    coordinator = PostgresRecoveryCoordinator(
        ledger=ledger,  # type: ignore[arg-type]
        leases=_Leases(),  # type: ignore[arg-type]
    )

    decision = await coordinator.inspect(object(), binding=_binding())

    assert decision.action is RecoveryAction.RETURN_ABORTED
    assert ledger.termination == (
        CommandStatus.ABORTED,
        "GRAPH_COMMAND_DEADLINE_EXCEEDED",
        "DEADLINE",
    )


@pytest.mark.asyncio
async def test_hearing_e2_recovery_retains_provider_authority_after_two_file_calls() -> None:
    binding = _binding(
        room_type="HEARING",
        stage_code="EVIDENCE_SYNTHESIZING",
        provider_attempt_budget=6,
    )
    ledger = _RegisteredBudgetLedger(
        deadline_open=True,
        binding=binding,
        provider_call_count=2,
    )
    coordinator = PostgresRecoveryCoordinator(
        ledger=ledger,  # type: ignore[arg-type]
        leases=_Leases(),  # type: ignore[arg-type]
    )

    decision = await coordinator.inspect(object(), binding=binding)

    assert decision.action is RecoveryAction.RESUME_BEFORE_MODEL
    assert decision.invoke_model is True


@pytest.mark.asyncio
async def test_adjacent_stage_cannot_use_hearing_e2_aggregate_provider_budget() -> None:
    binding = _binding(
        room_type="HEARING",
        stage_code="JUDGE_V1_GENERATING",
        provider_attempt_budget=6,
    )
    coordinator = PostgresRecoveryCoordinator(
        ledger=_RegisteredBudgetLedger(
            deadline_open=True,
            binding=binding,
        ),  # type: ignore[arg-type]
        leases=_Leases(),  # type: ignore[arg-type]
    )

    with pytest.raises(GraphRecoveryError, match="GRAPH_RETRY_BUDGET_INVALID"):
        await coordinator.inspect(object(), binding=binding)


class _Cursor:
    def __init__(self, row: Any) -> None:
        self.row = row

    async def fetchone(self) -> Any:
        return self.row


class _Connection:
    def __init__(self, metadata: dict[str, Any]) -> None:
        self.metadata = metadata
        self.queries: list[str] = []

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        self.queries.append(" ".join(query.split()).lower())
        return _Cursor({"metadata": self.metadata})


class _Ledger:
    def __init__(self) -> None:
        self.command = _command(CommandStatus.RESULT_CHECKPOINTED)
        self.result = _result()
        self.completed_with_fence: int | None = None

    async def load(self, connection: Any, **kwargs: Any) -> CommandRecord:
        return self.command

    async def load_result(self, connection: Any, **kwargs: Any) -> ResultRecord:
        return self.result

    async def complete_checkpointed_attempt(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> AttemptRecord:
        return _attempt(1)

    async def complete_from_checkpoint(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        fencing_token: int,
        result: ResultRecord,
    ) -> CommandRecord:
        self.completed_with_fence = fencing_token
        return replace(
            self.command,
            status=CommandStatus.COMPLETED,
            fencing_token=fencing_token,
        )


class _Leases:
    def __init__(
        self,
        *,
        inspection: LeaseInspection | None = None,
        displaced_command_id: str = "command-1",
    ) -> None:
        self.locked = False
        self.current_locked = False
        self.released = False
        self.cancelled = False
        self.inspection = inspection or LeaseInspection(
            lease=_lease(token=1, owner_id="worker-old"),
            database_now=NOW + timedelta(seconds=31),
        )
        self.displaced_command_id = displaced_command_id

    async def lock_for_recovery(
        self,
        connection: Any,
        **kwargs: Any,
    ) -> LeaseInspection | None:
        self.locked = True
        return self.inspection

    async def cancel(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        self.cancelled = True
        return replace(
            self.inspection.lease,
            fencing_token=self.inspection.lease.fencing_token + 1,
            cancelled_at=NOW,
            cancelled_by_command_id=kwargs["cancellation_command_id"],
            revision=self.inspection.lease.revision + 1,
        )

    async def acquire(self, connection: Any, **kwargs: Any) -> LeaseAcquisition:
        return LeaseAcquisition(
            LeaseAcquisitionKind.TAKEOVER,
            _lease(),
            LeaseDisplacement(
                command_id=self.displaced_command_id,
                owner_id="worker-old",
                fencing_token=1,
            ),
        )

    async def lock_current(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        self.locked = True
        self.current_locked = True
        return _lease()

    async def release(self, connection: Any, **kwargs: Any) -> LeaseRecord:
        self.released = True
        return replace(_lease(), released_at=NOW)


def _metadata(**overrides: Any) -> dict[str, Any]:
    command = _command(CommandStatus.RESULT_CHECKPOINTED)
    values = {
        "graph_thread_id": THREAD,
        "graph_command_id": "command-1",
        "graph_request_hash": command.binding.request_hash,
        "graph_room_epoch": 3,
        "graph_key": "evidence.flow",
        "graph_version": "evidence.v2",
        "graph_checkpoint_schema_version": "evidence.checkpoint.v2",
        "graph_fencing_token": 1,
        "graph_result_hash": RESULT_HASH,
        "graph_result_ref": "s3://graph-results/result-1.json",
    }
    values.update(overrides)
    return values


@pytest.mark.asyncio
async def test_terminal_reconciliation_locks_new_fence_and_uses_old_checkpoint_binding() -> None:
    ledger = _Ledger()
    leases = _Leases()
    coordinator = PostgresRecoveryCoordinator(ledger=ledger, leases=leases)  # type: ignore[arg-type]

    completed, result = await coordinator.reconcile_terminal(
        _Connection(_metadata()),
        binding=_binding(),
        owner_id="worker-new",
    )

    assert leases.locked is True
    assert leases.released is True
    assert ledger.completed_with_fence == 2
    assert completed.status is CommandStatus.COMPLETED
    assert result.result_hash == RESULT_HASH


@pytest.mark.asyncio
async def test_terminal_reconciliation_cannot_take_over_another_command() -> None:
    ledger = _Ledger()
    leases = _Leases(displaced_command_id="command-newer")
    coordinator = PostgresRecoveryCoordinator(ledger=ledger, leases=leases)  # type: ignore[arg-type]

    with pytest.raises(GraphRecoveryError, match="cannot displace another"):
        await coordinator.reconcile_terminal(
            _Connection(_metadata()),
            binding=_binding(),
            owner_id="worker-new",
        )

    assert leases.locked is True
    assert leases.current_locked is False
    assert ledger.completed_with_fence is None


@pytest.mark.asyncio
async def test_terminal_reconciliation_rejects_checkpoint_hash_mismatch() -> None:
    coordinator = PostgresRecoveryCoordinator(
        ledger=_Ledger(),  # type: ignore[arg-type]
        leases=_Leases(),  # type: ignore[arg-type]
    )

    with pytest.raises(GraphTerminalBindingError, match="inconsistent"):
        await coordinator.reconcile_terminal(
            _Connection(_metadata(graph_result_hash="f" * 64)),
            binding=_binding(),
            owner_id="worker-new",
        )
