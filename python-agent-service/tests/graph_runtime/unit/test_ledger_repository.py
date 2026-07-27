from __future__ import annotations

from dataclasses import replace
from datetime import datetime, timedelta, timezone
import json
from typing import Any

import pytest

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graph_runtime.errors import (
    GraphCommandHashConflictError,
    GraphCommandNotFoundError,
    GraphCommandStateError,
    GraphNonceReplayError,
    GraphTerminalBindingError,
)
from app.graph_runtime.ledger import (
    AttemptRecord,
    AttemptStatus,
    CommandBinding,
    CommandStatus,
    InvocationNonce,
    PostgresCommandLedger,
    ResultRecord,
    require_transition,
)
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.registry import CommandProfileBinding


NOW = datetime(2026, 7, 19, 8, 0, tzinfo=timezone.utc)
THREAD = f"grt.v1.{'1' * 32}"


def _profile() -> CommandProfileBinding:
    return CommandProfileBinding(
        command_schema_version="room-graph-command.v1",
        prompt_version="hearing.prompt.v1",
        model_profile_id="model.standard.v1",
        output_schema_version="hearing.output.v1",
        policy_version="policy.v1",
        guardrail_version="guardrail.v1",
        tool_policy_version="tools.none.v1",
    )


def _binding(seed: str = "one") -> CommandBinding:
    request: dict[str, Any] = {
        "schema_version": "room-graph-command.v1",
        "seed": seed,
        "logical_run_id": "run-1",
        "attempt_id": "attempt-1",
        "graph_key": "hearing.flow",
        "graph_version": "hearing.v2",
        "retry_budget": {
            "provider_attempts_remaining": 2,
            "activity_attempts_remaining": 3,
        },
    }
    request["request_hash"] = canonical_sha256(request)
    return CommandBinding(
        thread_id=THREAD,
        command_id="command-1",
        request_schema_version="room-graph-command.v1",
        request_json=request,
        request_hash=request["request_hash"],
        room_epoch=7,
        graph_key="hearing.flow",
        graph_version="hearing.v2",
        checkpoint_schema_version="hearing.checkpoint.v2",
        profile=_profile(),
        deadline_at=NOW + timedelta(minutes=1),
    )


def _nonce(jti: str = "jti-1") -> InvocationNonce:
    return InvocationNonce(
        issuer="java-api-service",
        key_id="java-key-1",
        jti=jti,
        issued_at=NOW,
        token_expires_at=NOW + timedelta(seconds=60),
        retained_until=NOW + timedelta(hours=24),
    )


def _command_row(binding: CommandBinding, status: str = "REGISTERED") -> dict[str, Any]:
    return {
        "thread_id": binding.thread_id,
        "command_id": binding.command_id,
        "request_schema_version": binding.request_schema_version,
        "request_json": dict(binding.request_json),
        "request_hash": binding.request_hash,
        "execution_mode": binding.execution_lane.value,
        "activation_id": binding.activation_id,
        "room_fencing_token": binding.room_fencing_token,
        "command_hash": binding.command_hash,
        "command_envelope_hash": binding.command_envelope_hash,
        "room_epoch": binding.room_epoch,
        "graph_key": binding.graph_key,
        "graph_version": binding.graph_version,
        "checkpoint_schema_version": binding.checkpoint_schema_version,
        "prompt_version": binding.profile.prompt_version,
        "model_profile_id": binding.profile.model_profile_id,
        "output_schema_version": binding.profile.output_schema_version,
        "policy_version": binding.profile.policy_version,
        "guardrail_version": binding.profile.guardrail_version,
        "tool_policy_version": binding.profile.tool_policy_version,
        "deadline_at": binding.deadline_at,
        "status": status,
        "attempt_count": 0,
        "fencing_token": None,
        "start_checkpoint_ns": None,
        "start_checkpoint_id": None,
        "committed_checkpoint_ns": None,
        "committed_checkpoint_id": None,
        "result_ref": None,
        "result_hash": None,
        "error_code": None,
        "error_classification": None,
        "command_revision": 0,
    }


class _Cursor:
    def __init__(self, value: Any) -> None:
        self.value = value

    async def fetchone(self) -> Any:
        return self.value

    async def fetchall(self) -> list[Any]:
        return self.value


class _Connection:
    def __init__(self, responses: list[Any]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, Any]] = []

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        self.calls.append((" ".join(query.split()).lower(), params))
        return _Cursor(self.responses.pop(0))


@pytest.mark.asyncio
async def test_register_inserts_command_then_nonce_on_one_connection() -> None:
    binding = _binding()
    connection = _Connection([_command_row(binding), {"jti": "jti-1"}])

    registration = await PostgresCommandLedger().register_with_nonce(
        connection,
        binding=binding,
        nonce=_nonce(),
    )

    assert registration.created is True
    assert registration.command.binding == binding
    assert "insert into agent_graph_command" in connection.calls[0][0]
    assert "insert into agent_graph_invocation_nonce" in connection.calls[1][0]
    assert "on conflict (issuer, key_id, jti) do nothing" in connection.calls[1][0]


@pytest.mark.asyncio
async def test_same_command_and_hash_joins_with_a_fresh_transport_nonce() -> None:
    binding = _binding()
    connection = _Connection([None, _command_row(binding), {"jti": "jti-2"}])

    registration = await PostgresCommandLedger().register_with_nonce(
        connection,
        binding=binding,
        nonce=_nonce("jti-2"),
    )

    assert registration.created is False
    assert registration.command.status is CommandStatus.REGISTERED
    assert len(connection.calls) == 3


@pytest.mark.asyncio
async def test_same_id_with_different_hash_fails_before_nonce_insert() -> None:
    expected = _binding("expected")
    conflicting = _binding("conflicting")
    connection = _Connection([None, _command_row(conflicting)])

    with pytest.raises(GraphCommandHashConflictError):
        await PostgresCommandLedger().register_with_nonce(
            connection,
            binding=expected,
            nonce=_nonce(),
        )

    assert len(connection.calls) == 2
    assert all("invocation_nonce" not in query for query, _ in connection.calls)


@pytest.mark.asyncio
async def test_exact_jws_replay_is_rejected_even_for_idempotent_command() -> None:
    binding = _binding()
    connection = _Connection([None, _command_row(binding), None])

    with pytest.raises(GraphNonceReplayError):
        await PostgresCommandLedger().register_with_nonce(
            connection,
            binding=binding,
            nonce=_nonce(),
        )


@pytest.mark.asyncio
async def test_existing_only_nonce_consumption_never_inserts_a_command() -> None:
    binding = _binding()
    connection = _Connection([_command_row(binding, status="COMPLETED"), {"jti": "jti-2"}])

    command = await PostgresCommandLedger().consume_nonce_for_existing(
        connection,
        binding=binding,
        nonce=_nonce("jti-2"),
    )

    assert command.status is CommandStatus.COMPLETED
    assert "select" in connection.calls[0][0]
    assert "for update" in connection.calls[0][0]
    assert "insert into agent_graph_command " not in connection.calls[0][0]
    assert "insert into agent_graph_invocation_nonce" in connection.calls[1][0]


@pytest.mark.asyncio
async def test_existing_only_nonce_consumption_has_no_side_effect_for_missing_command() -> None:
    connection = _Connection([None])

    with pytest.raises(GraphCommandNotFoundError):
        await PostgresCommandLedger().consume_nonce_for_existing(
            connection,
            binding=_binding(),
            nonce=_nonce(),
        )

    assert len(connection.calls) == 1
    assert "insert" not in connection.calls[0][0]


@pytest.mark.asyncio
async def test_candidate_reconcile_requires_read_only_pre_cutoff_jws_admission_proof() -> None:
    binding = replace(
        _binding(),
        execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
        activation_id=f"p9act.v1.{'a' * 32}",
        room_fencing_token=11,
        command_hash="b" * 64,
        command_envelope_hash="c" * 64,
    )
    connection = _Connection([None])

    with pytest.raises(GraphTerminalBindingError, match="pre-cutoff"):
        await PostgresCommandLedger().load_candidate_terminal_proof(
            connection,
            binding=binding,
            issuer="java-api-service",
            key_id="java-key-1",
            jti="jti-1",
            issued_at=NOW,
            token_expires_at=NOW + timedelta(seconds=60),
        )

    query = connection.calls[0][0]
    assert "join agent_graph_invocation_nonce nonce" in query
    assert "command.registered_at <= nonce.token_expires_at" in query
    assert "command.registered_at < activation.expires_at" in query
    assert "insert " not in query and "update " not in query and "delete " not in query


@pytest.mark.asyncio
async def test_repeated_identical_infrastructure_termination_is_idempotent() -> None:
    binding = _binding()
    terminal = _command_row(binding, status="ABORTED")
    terminal.update(
        {
            "error_code": "GRAPH_DEADLINE_EXCEEDED",
            "error_classification": "DEADLINE",
        }
    )
    connection = _Connection([None, terminal])

    record = await PostgresCommandLedger().terminate(
        connection,
        binding=binding,
        status=CommandStatus.ABORTED,
        error_code="GRAPH_DEADLINE_EXCEEDED",
        error_classification="DEADLINE",
    )

    assert record.status is CommandStatus.ABORTED
    assert record.error_code == "GRAPH_DEADLINE_EXCEEDED"


@pytest.mark.asyncio
async def test_recovery_window_jwks_kids_include_terminal_commands() -> None:
    connection = _Connection(
        [[{"key_id": "java-key-2"}, {"key_id": "java-key-1"}, {"key_id": "java-key-2"}]]
    )

    keys = await PostgresCommandLedger().referenced_verification_key_ids(connection)

    assert keys == frozenset({"java-key-1", "java-key-2"})
    sql = " ".join(connection.calls[0][0].lower().split())
    assert "join agent_graph_command command" in sql
    assert "command.status in ('registered', 'executing', 'result_checkpointed')" in sql
    assert "nonce.retained_until > clock_timestamp()" in sql


@pytest.mark.asyncio
async def test_terminal_result_insert_is_fence_and_checkpoint_guarded() -> None:
    binding = _binding()
    result_json: dict[str, Any] = {
        "schema_version": "room-graph-result.v1",
        "command_id": binding.command_id,
        "logical_run_id": "run-1",
        "attempt_id": "attempt-1",
        "graph_key": binding.graph_key,
        "graph_version": binding.graph_version,
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
    result_hash = canonical_sha256(result_json)
    result_json["output_hash"] = result_hash
    result = ResultRecord(
        result_id="result-1",
        thread_id=binding.thread_id,
        command_id=binding.command_id,
        request_hash=binding.request_hash,
        result_schema_version="room-graph-result.v1",
        checkpoint_ns="hearing",
        checkpoint_id="checkpoint-1",
        cognitive_revision=1,
        terminal_status="COMPLETED",
        result_json=result_json,
        result_ref="s3://graph-results/result-1.json",
        result_hash=result_hash,
        usage_json={"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
    )
    row = {
        "result_id": result.result_id,
        "thread_id": result.thread_id,
        "command_id": result.command_id,
        "request_hash": result.request_hash,
        "execution_mode": result.execution_lane.value,
        "activation_id": result.activation_id,
        "room_fencing_token": result.room_fencing_token,
        "command_hash": result.command_hash,
        "command_envelope_hash": result.command_envelope_hash,
        "proposal_hash": result.proposal_hash,
        "result_envelope_hash": result.result_envelope_hash,
        "proposal_source_json": result.proposal_source_json,
        "result_envelope_json": result.result_envelope_json,
        "result_schema_version": result.result_schema_version,
        "checkpoint_ns": result.checkpoint_ns,
        "checkpoint_id": result.checkpoint_id,
        "cognitive_revision": result.cognitive_revision,
        "terminal_status": result.terminal_status,
        "result_json": dict(result.result_json),
        "result_ref": result.result_ref,
        "result_hash": result.result_hash,
        "usage_json": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
    }
    fence = GraphFenceContext(
        thread_id=binding.thread_id,
        command_id=binding.command_id,
        owner_id="worker-1",
        fencing_token=1,
        request_hash=binding.request_hash,
        room_epoch=binding.room_epoch,
        graph_key=binding.graph_key,
        graph_version=binding.graph_version,
        checkpoint_schema_version=binding.checkpoint_schema_version,
        result_hash=result_hash,
        result_ref=result.result_ref,
    )
    attempt_row = {
        "attempt_id": "attempt-1",
        "thread_id": binding.thread_id,
        "command_id": binding.command_id,
        "attempt_no": 1,
        "owner_id": "worker-1",
        "fencing_token": 1,
        "attempt_status": "CHECKPOINTED",
        "provider_call_count": 1,
        "error_code": None,
        "error_classification": None,
    }
    checkpointed_command = _command_row(binding, status="RESULT_CHECKPOINTED")
    checkpointed_command.update(
        {
            "fencing_token": 1,
            "committed_checkpoint_ns": "hearing",
            "committed_checkpoint_id": "checkpoint-1",
            "result_ref": result.result_ref,
            "result_hash": result.result_hash,
        }
    )
    connection = _Connection([checkpointed_command, attempt_row, row])

    stored = await PostgresCommandLedger().store_terminal_result(
        connection,
        fence=fence,
        result=result,
        expected_result_schema_version="room-graph-result.v1",
    )

    assert stored == result
    assert "for update" in connection.calls[0][0]
    assert "attempt_status = 'checkpointed'" in connection.calls[1][0]
    sql = connection.calls[2][0]
    assert "from agent_graph_lease" in sql
    assert "lease.fencing_token = %s" in sql
    assert "command.status = 'result_checkpointed'" in sql
    assert "command.committed_checkpoint_id = %s" in sql


def test_command_size_uses_rfc8785_bytes_not_python_dict_rendering() -> None:
    request: dict[str, Any] = {
        "schema_version": "room-graph-command.v1",
        "values": [-0.0] * 14_000,
    }
    request["request_hash"] = canonical_sha256(request)

    assert len(canonicalize(request)) < 65_536
    assert len(json.dumps(request, separators=(",", ":")).encode("utf-8")) > 65_536
    binding = CommandBinding(
        thread_id=THREAD,
        command_id="command-canonical-size",
        request_schema_version="room-graph-command.v1",
        request_json=request,
        request_hash=request["request_hash"],
        room_epoch=7,
        graph_key="hearing.flow",
        graph_version="hearing.v2",
        checkpoint_schema_version="hearing.checkpoint.v2",
        profile=_profile(),
        deadline_at=NOW + timedelta(minutes=1),
    )

    assert binding.request_hash == request["request_hash"]


@pytest.mark.asyncio
async def test_attempt_and_provider_budgets_are_database_atomic() -> None:
    binding = _binding()
    command_row = _command_row(binding, status="EXECUTING")
    command_row.update({"attempt_count": 1, "fencing_token": 1})
    attempt_row = {
        "attempt_id": "attempt-2",
        "thread_id": binding.thread_id,
        "command_id": binding.command_id,
        "attempt_no": 1,
        "owner_id": "worker-2",
        "fencing_token": 2,
        "attempt_status": "EXECUTING",
        "provider_call_count": 0,
        "error_code": None,
        "error_classification": None,
    }
    connection = _Connection([command_row, attempt_row, {**attempt_row, "provider_call_count": 1}])
    ledger = PostgresCommandLedger()

    _, attempt = await ledger.begin_attempt(
        connection,
        binding=binding,
        attempt_id="attempt-2",
        owner_id="worker-2",
        fencing_token=2,
    )
    await ledger.record_provider_call(connection, attempt)

    begin_sql = connection.calls[0][0]
    provider_sql = connection.calls[2][0]
    assert "deadline_at > clock_timestamp()" in begin_sql
    assert "status = 'registered'" in begin_sql
    assert "status in ('registered', 'executing')" not in begin_sql
    assert "activity_attempts_remaining" in begin_sql
    assert "command.deadline_at > clock_timestamp()" in provider_sql
    assert "sum(budget_attempt.provider_call_count)" in provider_sql
    assert "provider_attempts_remaining" in provider_sql


@pytest.mark.asyncio
async def test_late_attempt_failure_cannot_overwrite_checkpointed_attempt() -> None:
    attempt = AttemptRecord(
        attempt_id="attempt-1",
        thread_id=THREAD,
        command_id="command-1",
        attempt_no=1,
        owner_id="worker-1",
        fencing_token=1,
        status=AttemptStatus.CHECKPOINTED,
        provider_call_count=1,
        error_code=None,
        error_classification=None,
    )
    connection = _Connection([None])

    with pytest.raises(GraphCommandStateError):
        await PostgresCommandLedger().finish_attempt(
            connection,
            attempt,
            status=AttemptStatus.FAILED,
            error_code="LATE_TIMEOUT",
            error_classification="INFRASTRUCTURE",
        )

    assert "attempt_status = 'executing'" in connection.calls[0][0]


@pytest.mark.asyncio
async def test_attempt_success_cannot_bypass_checkpointed_completion() -> None:
    attempt = AttemptRecord(
        attempt_id="attempt-1",
        thread_id=THREAD,
        command_id="command-1",
        attempt_no=1,
        owner_id="worker-1",
        fencing_token=1,
        status=AttemptStatus.EXECUTING,
        provider_call_count=1,
        error_code=None,
        error_classification=None,
    )
    connection = _Connection([])

    with pytest.raises(GraphCommandStateError, match="checkpointed completion"):
        await PostgresCommandLedger().finish_attempt(
            connection,
            attempt,
            status=AttemptStatus.COMPLETED,
        )

    assert connection.calls == []


def test_cached_result_must_match_original_run_attempt_and_profiles() -> None:
    binding = _binding()
    command = PostgresCommandLedger._command_from_row(_command_row(binding, status="COMPLETED"))
    payload: dict[str, Any] = {
        "schema_version": "room-graph-result.v1",
        "command_id": binding.command_id,
        "logical_run_id": "forged-run",
        "attempt_id": "attempt-1",
        "graph_key": binding.graph_key,
        "graph_version": binding.graph_version,
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
    payload["output_hash"] = canonical_sha256(payload)
    result = ResultRecord(
        result_id="result-forged",
        thread_id=binding.thread_id,
        command_id=binding.command_id,
        request_hash=binding.request_hash,
        result_schema_version="room-graph-result.v1",
        checkpoint_ns="hearing",
        checkpoint_id="checkpoint-1",
        cognitive_revision=1,
        terminal_status="COMPLETED",
        result_json=payload,
        result_ref="s3://graph-results/result-forged.json",
        result_hash=payload["output_hash"],
        usage_json={"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
    )

    with pytest.raises(GraphTerminalBindingError, match="immutable command identity"):
        PostgresCommandLedger.require_result_matches_command(command, result)


def test_six_state_transition_table_is_exact() -> None:
    allowed = {
        (CommandStatus.REGISTERED, CommandStatus.EXECUTING),
        (CommandStatus.REGISTERED, CommandStatus.CANCELLED),
        (CommandStatus.REGISTERED, CommandStatus.ABORTED),
        (CommandStatus.EXECUTING, CommandStatus.RESULT_CHECKPOINTED),
        (CommandStatus.EXECUTING, CommandStatus.CANCELLED),
        (CommandStatus.EXECUTING, CommandStatus.ABORTED),
        (CommandStatus.RESULT_CHECKPOINTED, CommandStatus.COMPLETED),
    }
    for current in CommandStatus:
        for target in CommandStatus:
            if (current, target) in allowed:
                require_transition(current, target)
            else:
                with pytest.raises(GraphCommandStateError):
                    require_transition(current, target)
