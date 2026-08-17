"""Durable command, attempt, result, and invocation-nonce ledger."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import StrEnum
import hmac
import json
import re
from typing import Any, Final

from app.contracts.v1.codec import canonical_sha256_omitting, canonicalize
from app.contracts.v1.models import RoomGraphCommand, RoomGraphResult
from app.graph_runtime.errors import (
    GraphCommandBindingError,
    GraphCommandDeadlineError,
    GraphCommandHashConflictError,
    GraphCommandNotFoundError,
    GraphCommandStateError,
    GraphContractError,
    GraphNonceReplayError,
    GraphTerminalBindingError,
)
from app.graph_runtime.identity import THREAD_ID_PATTERN, _identifier, _sha256
from app.graph_runtime.persistence_models import GraphFenceContext, GraphGatewayMode
from app.graph_runtime.registry import CommandProfileBinding
from app.graph_runtime.target_e2e import (
    TargetE2EGraphResultEnvelope,
    TargetE2ERoomProposalSource,
)
from app.security.invocation_envelope import INVOCATION_CLOCK_SKEW_SECONDS


NONCE_RETENTION: Final = timedelta(hours=24)
MAX_TOKEN_LIFETIME: Final = timedelta(seconds=60)


class CommandStatus(StrEnum):
    REGISTERED = "REGISTERED"
    EXECUTING = "EXECUTING"
    RESULT_CHECKPOINTED = "RESULT_CHECKPOINTED"
    COMPLETED = "COMPLETED"
    CANCELLED = "CANCELLED"
    ABORTED = "ABORTED"


class AttemptStatus(StrEnum):
    EXECUTING = "EXECUTING"
    CHECKPOINTED = "CHECKPOINTED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    LEASE_LOST = "LEASE_LOST"
    CANCELLED = "CANCELLED"


class CheckpointRestoreKind(StrEnum):
    """Authoritative source of one current command restore pointer."""

    CURRENT_COMMITTED = "CURRENT_COMMITTED"
    COMPLETED_START = "COMPLETED_START"


LEGAL_TRANSITIONS: Final[dict[CommandStatus, frozenset[CommandStatus]]] = {
    CommandStatus.REGISTERED: frozenset(
        {CommandStatus.EXECUTING, CommandStatus.CANCELLED, CommandStatus.ABORTED}
    ),
    CommandStatus.EXECUTING: frozenset(
        {
            CommandStatus.RESULT_CHECKPOINTED,
            CommandStatus.CANCELLED,
            CommandStatus.ABORTED,
        }
    ),
    CommandStatus.RESULT_CHECKPOINTED: frozenset({CommandStatus.COMPLETED}),
    CommandStatus.COMPLETED: frozenset(),
    CommandStatus.CANCELLED: frozenset(),
    CommandStatus.ABORTED: frozenset(),
}


def require_transition(current: CommandStatus, target: CommandStatus) -> None:
    if target is current or target not in LEGAL_TRANSITIONS[current]:
        raise GraphCommandStateError(f"illegal command transition {current} -> {target}")


def _aware(value: datetime, name: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise GraphContractError(f"{name} must be timezone-aware")
    return value


@dataclass(frozen=True, slots=True)
class CommandBinding:
    thread_id: str
    command_id: str
    request_schema_version: str
    request_json: Mapping[str, Any]
    request_hash: str
    room_epoch: int
    graph_key: str
    graph_version: str
    checkpoint_schema_version: str
    profile: CommandProfileBinding
    deadline_at: datetime
    execution_lane: GraphGatewayMode = GraphGatewayMode.SHADOW
    activation_id: str | None = None
    room_fencing_token: int | None = None
    command_hash: str | None = None
    command_envelope_hash: str | None = None

    def __post_init__(self) -> None:
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise GraphContractError("thread_id must be an opaque grt.v1 ID")
        for name in (
            "command_id",
            "request_schema_version",
            "graph_key",
            "graph_version",
            "checkpoint_schema_version",
        ):
            _identifier(getattr(self, name), name)
        _sha256(self.request_hash, "request_hash")
        if isinstance(self.room_epoch, bool) or self.room_epoch < 0:
            raise GraphContractError("room_epoch must be non-negative")
        if not isinstance(self.request_json, Mapping):
            raise GraphContractError("request_json must be an object")
        try:
            canonical_request = canonicalize(dict(self.request_json))
        except (TypeError, ValueError) as error:
            raise GraphContractError("request_json is not RFC 8785 serializable") from error
        if len(canonical_request) > 65_536:
            raise GraphContractError("request_json exceeds the 64 KiB ledger limit")
        if self.request_json.get("request_hash") != self.request_hash:
            raise GraphCommandHashConflictError("request JSON does not bind request_hash")
        if canonical_sha256_omitting(self.request_json, "request_hash") != self.request_hash:
            raise GraphCommandHashConflictError("request self-hash is invalid")
        _aware(self.deadline_at, "deadline_at")
        if not isinstance(self.execution_lane, GraphGatewayMode) or self.execution_lane is (
            GraphGatewayMode.DISABLED
        ):
            raise GraphContractError("command execution lane is invalid")
        if self.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            if self.activation_id is None or re.fullmatch(
                r"p9act\.v1\.[0-9a-f]{32}", self.activation_id
            ) is None:
                raise GraphContractError("candidate command activation ID is invalid")
            _sha256(self.command_envelope_hash, "command_envelope_hash")
            _sha256(self.command_hash, "command_hash")
            if (
                not isinstance(self.room_fencing_token, int)
                or isinstance(self.room_fencing_token, bool)
                or self.room_fencing_token < 1
            ):
                raise GraphContractError("candidate command room fence is invalid")
        elif (
            self.activation_id is not None
            or self.room_fencing_token is not None
            or self.command_hash is not None
            or self.command_envelope_hash is not None
        ):
            raise GraphContractError("SHADOW command cannot carry candidate activation")

    @classmethod
    def from_command(
        cls,
        command: RoomGraphCommand,
        *,
        tool_policy_version: str,
        execution_lane: GraphGatewayMode = GraphGatewayMode.SHADOW,
        activation_id: str | None = None,
        room_fencing_token: int | None = None,
        command_hash: str | None = None,
        command_envelope_hash: str | None = None,
    ) -> CommandBinding:
        invocation = command.invocation_context
        return cls(
            thread_id=command.thread_id,
            command_id=command.command_id,
            request_schema_version=command.schema_version,
            request_json=command.model_dump(mode="json", exclude_none=True),
            request_hash=command.request_hash,
            room_epoch=command.room_epoch,
            graph_key=command.graph_key,
            graph_version=command.graph_version,
            checkpoint_schema_version=command.checkpoint_schema_version,
            profile=CommandProfileBinding(
                command_schema_version=command.schema_version,
                prompt_version=invocation.prompt_profile_id,
                model_profile_id=invocation.model_profile_id,
                output_schema_version=invocation.output_schema_version,
                policy_version=invocation.policy_version,
                guardrail_version=invocation.guardrail_version,
                tool_policy_version=tool_policy_version,
            ),
            deadline_at=command.deadline_at,
            execution_lane=execution_lane,
            activation_id=activation_id,
            room_fencing_token=room_fencing_token,
            command_hash=command_hash,
            command_envelope_hash=command_envelope_hash,
        )


@dataclass(frozen=True, slots=True)
class InvocationNonce:
    issuer: str
    key_id: str
    jti: str
    issued_at: datetime
    token_expires_at: datetime
    retained_until: datetime

    def __post_init__(self) -> None:
        for name in ("issuer", "key_id", "jti"):
            _identifier(getattr(self, name), name)
        _aware(self.issued_at, "issued_at")
        _aware(self.token_expires_at, "token_expires_at")
        _aware(self.retained_until, "retained_until")
        lifetime = self.token_expires_at - self.issued_at
        if lifetime <= timedelta(0) or lifetime > MAX_TOKEN_LIFETIME:
            raise GraphContractError("invocation token lifetime must be 1..60 seconds")
        if self.retained_until < self.issued_at + NONCE_RETENTION:
            raise GraphContractError("invocation nonce retention must be at least 24 hours")

    @classmethod
    def from_verified_invocation(cls, invocation: Any) -> InvocationNonce:
        claims = invocation.claims
        issued_at = datetime.fromtimestamp(claims.iat, tz=timezone.utc)
        return cls(
            issuer=claims.iss,
            key_id=invocation.key_id,
            jti=claims.jti,
            issued_at=issued_at,
            token_expires_at=datetime.fromtimestamp(claims.exp, tz=timezone.utc),
            retained_until=issued_at + NONCE_RETENTION,
        )


@dataclass(frozen=True, slots=True)
class CommandRecord:
    binding: CommandBinding
    status: CommandStatus
    attempt_count: int
    fencing_token: int | None
    start_checkpoint_ns: str | None
    start_checkpoint_id: str | None
    committed_checkpoint_ns: str | None
    committed_checkpoint_id: str | None
    result_ref: str | None
    result_hash: str | None
    error_code: str | None
    error_classification: str | None
    revision: int

    @property
    def terminal(self) -> bool:
        return self.status in {
            CommandStatus.COMPLETED,
            CommandStatus.CANCELLED,
            CommandStatus.ABORTED,
        }


@dataclass(frozen=True, slots=True)
class CommandRegistration:
    command: CommandRecord
    created: bool


@dataclass(frozen=True, slots=True)
class AttemptRecord:
    attempt_id: str
    thread_id: str
    command_id: str
    attempt_no: int
    owner_id: str
    fencing_token: int
    status: AttemptStatus
    provider_call_count: int
    error_code: str | None
    error_classification: str | None


@dataclass(frozen=True, slots=True)
class ResultRecord:
    result_id: str
    thread_id: str
    command_id: str
    request_hash: str
    result_schema_version: str
    checkpoint_ns: str
    checkpoint_id: str
    cognitive_revision: int
    terminal_status: str
    result_json: Mapping[str, Any]
    result_ref: str
    result_hash: str
    usage_json: Mapping[str, Any]
    execution_lane: GraphGatewayMode = GraphGatewayMode.SHADOW
    activation_id: str | None = None
    room_fencing_token: int | None = None
    command_hash: str | None = None
    command_envelope_hash: str | None = None
    proposal_hash: str | None = None
    result_envelope_hash: str | None = None
    proposal_source_json: Mapping[str, Any] | None = None
    result_envelope_json: Mapping[str, Any] | None = None


@dataclass(frozen=True, slots=True)
class RecoveryBudget:
    deadline_open: bool
    provider_call_count: int

    def __post_init__(self) -> None:
        if not isinstance(self.deadline_open, bool) or self.provider_call_count < 0:
            raise GraphCommandBindingError("persisted recovery budget is invalid")


@dataclass(frozen=True, slots=True)
class CompletedStartCheckpoint:
    """Database-proven terminal checkpoint used to start one later command."""

    command_id: str
    request_hash: str
    fencing_token: int
    execution_lane: GraphGatewayMode
    activation_id: str | None
    room_fencing_token: int | None
    command_hash: str | None
    command_envelope_hash: str | None
    checkpoint_ns: str
    checkpoint_id: str
    cognitive_revision: int
    execution_provider: str | None
    execution_model: str | None
    proposal_hash: str | None
    result_envelope_hash: str | None
    result_hash: str
    result_ref: str

    def __post_init__(self) -> None:
        try:
            _identifier(self.command_id, "command_id")
            _sha256(self.request_hash, "request_hash")
            _sha256(self.result_hash, "result_hash")
        except GraphContractError as error:
            raise GraphTerminalBindingError(
                "completed start checkpoint identity is invalid"
            ) from error
        if (
            not isinstance(self.fencing_token, int)
            or isinstance(self.fencing_token, bool)
            or self.fencing_token < 1
        ):
            raise GraphTerminalBindingError(
                "completed start checkpoint fence is invalid"
            )
        if (
            not isinstance(self.checkpoint_ns, str)
            or len(self.checkpoint_ns) > 128
            or not isinstance(self.checkpoint_id, str)
            or not self.checkpoint_id
            or len(self.checkpoint_id) > 128
            or not isinstance(self.cognitive_revision, int)
            or isinstance(self.cognitive_revision, bool)
            or self.cognitive_revision < 1
            or not isinstance(self.result_ref, str)
            or not self.result_ref
            or len(self.result_ref) > 512
        ):
            raise GraphTerminalBindingError(
                "completed start checkpoint result binding is invalid"
            )
        if self.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            try:
                _sha256(self.command_hash, "command_hash")
                _sha256(self.command_envelope_hash, "command_envelope_hash")
                _sha256(self.proposal_hash, "proposal_hash")
                _sha256(self.result_envelope_hash, "result_envelope_hash")
            except GraphContractError as error:
                raise GraphTerminalBindingError(
                    "completed candidate start checkpoint is invalid"
                ) from error
            if (
                self.activation_id is None
                or re.fullmatch(r"p9act\.v1\.[0-9a-f]{32}", self.activation_id) is None
                or not isinstance(self.room_fencing_token, int)
                or isinstance(self.room_fencing_token, bool)
                or self.room_fencing_token < 1
                or not isinstance(self.execution_provider, str)
                or not self.execution_provider
                or len(self.execution_provider) > 64
                or not isinstance(self.execution_model, str)
                or not self.execution_model
                or len(self.execution_model) > 128
            ):
                raise GraphTerminalBindingError(
                    "completed candidate start checkpoint authority is invalid"
                )
        elif self.execution_lane is GraphGatewayMode.SHADOW:
            if any(
                value is not None
                for value in (
                    self.activation_id,
                    self.room_fencing_token,
                    self.command_hash,
                    self.command_envelope_hash,
                    self.execution_provider,
                    self.execution_model,
                    self.proposal_hash,
                    self.result_envelope_hash,
                )
            ):
                raise GraphTerminalBindingError(
                    "completed SHADOW start checkpoint carries candidate authority"
                )
        else:
            raise GraphTerminalBindingError(
                "completed start checkpoint execution lane is invalid"
            )


@dataclass(frozen=True, slots=True)
class CheckpointRestoreAuthority:
    """MVCC-proven physical checkpoint selector for one exact active command."""

    kind: CheckpointRestoreKind
    checkpoint_ns: str
    checkpoint_id: str

    def __post_init__(self) -> None:
        if not isinstance(self.kind, CheckpointRestoreKind):
            raise GraphTerminalBindingError("checkpoint restore kind is invalid")
        if (
            not isinstance(self.checkpoint_ns, str)
            or len(self.checkpoint_ns) > 128
            or not isinstance(self.checkpoint_id, str)
            or not self.checkpoint_id
            or len(self.checkpoint_id) > 128
        ):
            raise GraphTerminalBindingError("checkpoint restore identity is invalid")


COMMAND_COLUMNS: Final[str] = """
thread_id, command_id, request_schema_version, request_json, request_hash,
execution_mode, activation_id, room_fencing_token, command_hash, command_envelope_hash, room_epoch,
graph_key, graph_version,
checkpoint_schema_version,
prompt_version, model_profile_id, output_schema_version, policy_version,
guardrail_version, tool_policy_version, deadline_at, status, attempt_count,
fencing_token, start_checkpoint_ns, start_checkpoint_id,
committed_checkpoint_ns, committed_checkpoint_id, result_ref, result_hash,
error_code, error_classification, command_revision
"""

INSERT_COMMAND_SQL: Final[str] = f"""
insert into agent_graph_command (
    thread_id, command_id, request_schema_version, request_json, request_hash,
    execution_mode, activation_id, room_fencing_token, command_hash, command_envelope_hash, room_epoch,
    graph_key, graph_version,
    checkpoint_schema_version, prompt_version, model_profile_id,
    output_schema_version, policy_version, guardrail_version,
    tool_policy_version, deadline_at, status
)
select %s, %s, %s, %s::jsonb, %s, %s, %s, %s, %s, %s, %s, %s, %s,
       %s, %s, %s, %s, %s, %s, %s, %s, 'REGISTERED'
 where %s > clock_timestamp()
   and (
       %s <> 'TARGET_E2E_CANDIDATE'
       or exists (
           select 1
             from agent_graph_target_e2e_activation activation
             join agent_graph_target_e2e_activation_lifecycle lifecycle
               on lifecycle.activation_id = activation.activation_id
             join agent_graph_target_e2e_environment_generation generation
               on generation.environment_id = activation.environment_id
            where activation.activation_id = %s
              and lifecycle.lifecycle_state = 'ACTIVE'
              and activation.expires_at > clock_timestamp()
              and generation.activation_id = activation.activation_id
              and generation.environment_generation = activation.environment_generation
       )
   )
on conflict (thread_id, command_id) do nothing
returning {COMMAND_COLUMNS}
"""

LOCK_TARGET_E2E_ADMISSION_SQL: Final[str] = """
select lifecycle.lifecycle_state
  from agent_graph_target_e2e_activation activation
  join agent_graph_target_e2e_activation_lifecycle lifecycle
    on lifecycle.activation_id = activation.activation_id
  join agent_graph_target_e2e_environment_generation generation
    on generation.environment_id = activation.environment_id
 where activation.activation_id = %s
   and generation.activation_id = activation.activation_id
   and generation.environment_generation = activation.environment_generation
 for share of lifecycle
"""

LOAD_COMMAND_SQL: Final[str] = f"""
select {COMMAND_COLUMNS}
  from agent_graph_command
 where thread_id = %s and command_id = %s
 for update
"""


LOAD_CHECKPOINT_RESTORE_AUTHORITY_SQL: Final[str] = """
select command.start_checkpoint_ns, command.start_checkpoint_id,
       command.committed_checkpoint_ns, command.committed_checkpoint_id
  from agent_graph_command command
 where command.thread_id = %s
   and command.command_id = %s
   and command.request_hash = %s
   and command.room_epoch = %s
   and command.graph_key = %s
   and command.graph_version = %s
   and command.checkpoint_schema_version = %s
   and command.execution_mode = %s
   and command.activation_id is not distinct from %s
   and command.room_fencing_token is not distinct from %s
   and command.command_hash is not distinct from %s
   and command.command_envelope_hash is not distinct from %s
   and command.fencing_token = %s
   and command.status = 'EXECUTING'
"""

LOAD_CANDIDATE_TERMINAL_PROOF_SQL: Final[str] = f"""
select {', '.join(f'command.{column.strip()}' for column in COMMAND_COLUMNS.split(','))}
  from agent_graph_command command
  join agent_graph_target_e2e_activation activation
    on activation.activation_id = command.activation_id
  join agent_graph_invocation_nonce nonce
    on nonce.thread_id = command.thread_id
   and nonce.command_id = command.command_id
   and nonce.request_hash = command.request_hash
 where command.thread_id = %s
   and command.command_id = %s
   and command.request_hash = %s
   and command.execution_mode = 'TARGET_E2E_CANDIDATE'
   and command.activation_id = %s
   and command.room_fencing_token = %s
   and command.command_hash = %s
   and command.command_envelope_hash = %s
   and nonce.issuer = %s
   and nonce.key_id = %s
   and nonce.jti = %s
   and nonce.issued_at = %s
   and nonce.token_expires_at = %s
   and command.registered_at <= nonce.token_expires_at
   and command.registered_at < activation.expires_at
   and command.status in ('RESULT_CHECKPOINTED', 'COMPLETED')
"""

# A reconciliation request is deliberately a new, short-lived credential.  It
# must not be looked up by that credential's delivery nonce: doing so makes a
# worker restart unrecoverable because the original admission nonce is the one
# that was consumed when the command was registered.  The query below instead
# proves that the immutable command was admitted by a nonce that was valid at
# registration time.  It uses the same bounded verifier clock window as the
# original JWS validation, so cross-process wall-clock ordering cannot turn an
# already accepted admission into an unreconcilable terminal command.  The
# fresh credential is still verified by the envelope verifier and binds the
# same command/envelope hashes before this query runs.
LOAD_CANDIDATE_RECONCILIATION_PROOF_SQL: Final[str] = f"""
select {', '.join(f'command.{column.strip()}' for column in COMMAND_COLUMNS.split(','))}
  from agent_graph_command command
  join agent_graph_target_e2e_activation activation
    on activation.activation_id = command.activation_id
 where command.thread_id = %s
   and command.command_id = %s
   and command.request_hash = %s
   and command.execution_mode = 'TARGET_E2E_CANDIDATE'
   and command.activation_id = %s
   and command.room_fencing_token = %s
   and command.command_hash = %s
   and command.command_envelope_hash = %s
   and command.registered_at < activation.expires_at
   and command.status in ('RESULT_CHECKPOINTED', 'COMPLETED')
   and exists (
       select 1
         from agent_graph_invocation_nonce nonce
        where nonce.thread_id = command.thread_id
          and nonce.command_id = command.command_id
          and nonce.request_hash = command.request_hash
          and nonce.issuer = %s
          and nonce.key_id = %s
           and nonce.issued_at <= command.registered_at
               + make_interval(secs => {INVOCATION_CLOCK_SKEW_SECONDS})
           and nonce.token_expires_at >= command.registered_at
               - make_interval(secs => {INVOCATION_CLOCK_SKEW_SECONDS})
   )
"""

INSERT_NONCE_SQL: Final[str] = """
insert into agent_graph_invocation_nonce (
    issuer, key_id, jti, thread_id, command_id, request_hash,
    issued_at, token_expires_at, retained_until
)
values (%s, %s, %s, %s, %s, %s, %s, %s, %s)
on conflict (issuer, key_id, jti) do nothing
returning jti
"""

REFERENCED_KEY_IDS_SQL: Final[str] = """
select distinct nonce.key_id
  from agent_graph_invocation_nonce nonce
  join agent_graph_command command
    on command.thread_id = nonce.thread_id
   and command.command_id = nonce.command_id
   and command.request_hash = nonce.request_hash
 where command.status in ('REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED')
    or nonce.retained_until > clock_timestamp()
"""

RECOVERY_BUDGET_SQL: Final[str] = """
select command.deadline_at > clock_timestamp() as deadline_open,
       coalesce(sum(attempt.provider_call_count), 0)::bigint as provider_call_count
  from agent_graph_command command
  left join agent_graph_command_attempt attempt
    on attempt.thread_id = command.thread_id
   and attempt.command_id = command.command_id
 where command.thread_id = %s and command.command_id = %s
 group by command.deadline_at
"""

QUALIFIED_COMMAND_COLUMNS: Final[str] = ", ".join(
    f"command.{column.strip()}" for column in COMMAND_COLUMNS.split(",")
)


BEGIN_ATTEMPT_SQL: Final[str] = f"""
update agent_graph_command command
   set status = 'EXECUTING',
       attempt_count = attempt_count + 1,
       fencing_token = %s,
       start_checkpoint_ns = thread.last_checkpoint_ns,
       start_checkpoint_id = thread.last_checkpoint_id,
       started_at = coalesce(started_at, clock_timestamp()),
       updated_at = clock_timestamp(),
       command_revision = command_revision + 1
  from graph_thread_registry thread
 where command.thread_id = %s and command.command_id = %s and command.request_hash = %s
   and command.room_epoch = %s and command.graph_key = %s and command.graph_version = %s
   and command.checkpoint_schema_version = %s
   and command.status = 'REGISTERED'
   and command.deadline_at > clock_timestamp()
   and command.attempt_count < (
       command.request_json #>> '{{retry_budget,activity_attempts_remaining}}'
   )::integer
   and command.start_checkpoint_ns is null
   and command.start_checkpoint_id is null
   and thread.thread_id = command.thread_id
   and thread.room_epoch = command.room_epoch
   and thread.graph_key = command.graph_key
   and thread.graph_version = command.graph_version
   and thread.checkpoint_schema_version = command.checkpoint_schema_version
   and thread.lifecycle_status = 'ACTIVE'
   and (
       (thread.last_checkpoint_ns is null and thread.last_checkpoint_id is null)
       or (thread.last_checkpoint_ns is not null and thread.last_checkpoint_id is not null)
   )
returning {QUALIFIED_COMMAND_COLUMNS}
"""


LOAD_COMPLETED_START_CHECKPOINT_SQL: Final[str] = f"""
select {', '.join(f'predecessor.{column.strip()}' for column in COMMAND_COLUMNS.split(','))}
  from agent_graph_command current_command
  join agent_graph_command predecessor
    on predecessor.thread_id = current_command.thread_id
   and predecessor.command_id = %s
 where current_command.thread_id = %s
   and current_command.command_id = %s
   and current_command.request_hash = %s
   and current_command.room_epoch = %s
   and current_command.graph_key = %s
   and current_command.graph_version = %s
   and current_command.checkpoint_schema_version = %s
   and current_command.execution_mode = %s
   and current_command.activation_id is not distinct from %s
   and current_command.room_fencing_token is not distinct from %s
   and current_command.command_hash is not distinct from %s
   and current_command.command_envelope_hash is not distinct from %s
   and current_command.fencing_token = %s
   and current_command.status = 'EXECUTING'
   and current_command.start_checkpoint_ns is not distinct from %s
   and current_command.start_checkpoint_id = %s
   and predecessor.command_id <> current_command.command_id
   and predecessor.status = 'COMPLETED'
   and predecessor.completed_at is not null
   and current_command.started_at is not null
   and predecessor.completed_at <= current_command.started_at
   and predecessor.committed_checkpoint_ns is not distinct from %s
   and predecessor.committed_checkpoint_id = %s
   and predecessor.result_ref is not null
   and predecessor.result_hash is not null
   and predecessor.room_epoch = current_command.room_epoch
   and predecessor.graph_key = current_command.graph_key
   and predecessor.graph_version = current_command.graph_version
   and predecessor.checkpoint_schema_version = current_command.checkpoint_schema_version
   and predecessor.execution_mode = current_command.execution_mode
   and predecessor.activation_id is not distinct from current_command.activation_id
   and predecessor.room_fencing_token is not distinct from current_command.room_fencing_token
"""

INSERT_ATTEMPT_SQL: Final[str] = """
insert into agent_graph_command_attempt (
    attempt_id, thread_id, command_id, attempt_no, owner_id,
    fencing_token, attempt_status
)
values (%s, %s, %s, %s, %s, %s, 'EXECUTING')
returning attempt_id, thread_id, command_id, attempt_no, owner_id,
          fencing_token, attempt_status, provider_call_count,
          error_code, error_classification
"""

LATEST_ATTEMPT_SQL: Final[str] = """
select attempt_id, thread_id, command_id, attempt_no, owner_id,
       fencing_token, attempt_status, provider_call_count,
       error_code, error_classification
  from agent_graph_command_attempt
 where thread_id = %s and command_id = %s
 order by attempt_no desc
 limit 1
 for update
"""

PROVIDER_CALL_SQL: Final[str] = """
update agent_graph_command_attempt attempt
   set provider_call_count = provider_call_count + 1,
       last_heartbeat_at = clock_timestamp()
 where attempt_id = %s and thread_id = %s and command_id = %s
   and owner_id = %s and fencing_token = %s
   and attempt_status = 'EXECUTING'
   and exists (
       select 1
         from agent_graph_command command
        where command.thread_id = attempt.thread_id
          and command.command_id = attempt.command_id
          and command.status = 'EXECUTING'
          and command.deadline_at > clock_timestamp()
          and (
              select coalesce(sum(budget_attempt.provider_call_count), 0)
                from agent_graph_command_attempt budget_attempt
               where budget_attempt.thread_id = attempt.thread_id
                 and budget_attempt.command_id = attempt.command_id
          ) < (
              command.request_json #>> '{retry_budget,provider_attempts_remaining}'
          )::integer
   )
   and exists (
       select 1 from agent_graph_lease lease
        where lease.thread_id = attempt.thread_id
          and lease.command_id = attempt.command_id
          and lease.owner_id = attempt.owner_id
          and lease.fencing_token = attempt.fencing_token
          and lease.released_at is null and lease.cancelled_at is null
          and lease.lease_expires_at > clock_timestamp()
   )
returning attempt_id, thread_id, command_id, attempt_no, owner_id,
          fencing_token, attempt_status, provider_call_count,
          error_code, error_classification
"""

FINISH_ATTEMPT_SQL: Final[str] = """
update agent_graph_command_attempt
   set attempt_status = %s, error_code = %s, error_classification = %s,
       completed_at = clock_timestamp(), last_heartbeat_at = clock_timestamp()
 where attempt_id = %s and thread_id = %s and command_id = %s
   and owner_id = %s and fencing_token = %s
   and attempt_status = 'EXECUTING'
returning attempt_id, thread_id, command_id, attempt_no, owner_id,
          fencing_token, attempt_status, provider_call_count,
          error_code, error_classification
"""

TERMINATE_COMMAND_SQL: Final[str] = f"""
update agent_graph_command
   set status = %s, error_code = %s, error_classification = %s,
       cancelled_at = case when %s = 'CANCELLED' then clock_timestamp() end,
       aborted_at = case when %s = 'ABORTED' then clock_timestamp() end,
       updated_at = clock_timestamp(), command_revision = command_revision + 1
 where thread_id = %s and command_id = %s and request_hash = %s
   and status in ('REGISTERED', 'EXECUTING')
returning {COMMAND_COLUMNS}
"""

RESULT_COLUMNS: Final[str] = """
result_id, thread_id, command_id, request_hash, execution_mode, activation_id,
room_fencing_token,
command_hash, command_envelope_hash, proposal_hash, result_envelope_hash,
proposal_source_json, result_envelope_json,
result_schema_version,
checkpoint_ns, checkpoint_id, cognitive_revision, terminal_status,
result_json, result_ref, result_hash, usage_json
"""

LOAD_RESULT_SQL: Final[str] = f"""
select {RESULT_COLUMNS}
  from agent_graph_result
 where thread_id = %s and command_id = %s
"""

INSERT_RESULT_SQL: Final[str] = f"""
insert into agent_graph_result (
    result_id, thread_id, command_id, request_hash, execution_mode, activation_id,
    room_fencing_token,
    command_hash, command_envelope_hash, proposal_hash, result_envelope_hash,
    proposal_source_json, result_envelope_json,
    result_schema_version, checkpoint_ns, checkpoint_id, cognitive_revision,
    terminal_status, result_json, result_ref, result_hash, usage_json
)
select %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
       %s, %s::jsonb, %s::jsonb, %s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s::jsonb
 where exists (
       select 1 from agent_graph_lease lease
        where lease.thread_id = %s and lease.command_id = %s
          and lease.owner_id = %s and lease.fencing_token = %s
          and lease.released_at is null and lease.cancelled_at is null
          and lease.lease_expires_at > clock_timestamp()
   )
   and exists (
       select 1 from agent_graph_command command
        where command.thread_id = %s and command.command_id = %s
          and command.request_hash = %s and command.room_epoch = %s
          and command.graph_key = %s and command.graph_version = %s
           and command.checkpoint_schema_version = %s
           and command.execution_mode = %s and command.activation_id is not distinct from %s
           and command.room_fencing_token is not distinct from %s
           and command.command_hash is not distinct from %s
           and command.command_envelope_hash is not distinct from %s
          and command.status = 'RESULT_CHECKPOINTED'
          and command.fencing_token = %s
          and command.committed_checkpoint_ns is not distinct from %s
          and command.committed_checkpoint_id = %s
          and command.result_ref = %s and command.result_hash = %s
   )
on conflict (thread_id, command_id) do nothing
returning {RESULT_COLUMNS}
"""

CHECKPOINT_ATTEMPT_SQL: Final[str] = """
update agent_graph_command_attempt
   set attempt_status = 'CHECKPOINTED', last_heartbeat_at = clock_timestamp()
 where thread_id = %s and command_id = %s and owner_id = %s
   and fencing_token = %s and attempt_status in ('EXECUTING', 'CHECKPOINTED')
returning attempt_id, thread_id, command_id, attempt_no, owner_id,
          fencing_token, attempt_status, provider_call_count,
          error_code, error_classification
"""

COMPLETE_CHECKPOINTED_ATTEMPT_SQL: Final[str] = """
update agent_graph_command_attempt
   set attempt_status = 'COMPLETED', completed_at = clock_timestamp(),
       last_heartbeat_at = clock_timestamp()
 where thread_id = %s and command_id = %s and fencing_token = %s
   and attempt_status = 'CHECKPOINTED'
returning attempt_id, thread_id, command_id, attempt_no, owner_id,
          fencing_token, attempt_status, provider_call_count,
          error_code, error_classification
"""

COMPLETE_COMMAND_SQL: Final[str] = f"""
update agent_graph_command
   set status = 'COMPLETED', fencing_token = %s,
       completed_at = clock_timestamp(), updated_at = clock_timestamp(),
       command_revision = command_revision + 1
 where thread_id = %s and command_id = %s and request_hash = %s
   and room_epoch = %s and graph_key = %s and graph_version = %s
   and checkpoint_schema_version = %s
   and status = 'RESULT_CHECKPOINTED'
   and committed_checkpoint_ns is not distinct from %s
   and committed_checkpoint_id = %s and result_ref = %s and result_hash = %s
returning {COMMAND_COLUMNS}
"""


class PostgresCommandLedger:
    """SQL-only repository. Gateway methods provide the explicit transaction boundary."""

    async def register_with_nonce(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        nonce: InvocationNonce,
    ) -> CommandRegistration:
        if binding.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            await self._lock_target_e2e_admission(connection, binding)
        params = self._insert_params(binding)
        row = await (await connection.execute(INSERT_COMMAND_SQL, params)).fetchone()
        created = row is not None
        if row is None:
            row = await (
                await connection.execute(
                    LOAD_COMMAND_SQL,
                    (binding.thread_id, binding.command_id),
                )
            ).fetchone()
            if row is None:
                raise GraphCommandDeadlineError()
        record = self._command_from_row(row)
        self.require_same_binding(record.binding, binding)
        await self._consume_nonce(connection, binding=binding, nonce=nonce)
        return CommandRegistration(record, created)

    @staticmethod
    async def _lock_target_e2e_admission(
        connection: Any,
        binding: CommandBinding,
    ) -> None:
        activation_id = binding.activation_id
        if activation_id is None:
            raise GraphCommandBindingError("candidate activation binding is absent")
        row = await (
            await connection.execute(
                LOCK_TARGET_E2E_ADMISSION_SQL,
                (activation_id,),
            )
        ).fetchone()
        if row is None:
            raise GraphCommandBindingError("candidate activation binding is not registered")

    async def consume_nonce_for_existing(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        nonce: InvocationNonce,
    ) -> CommandRecord:
        """Lock an existing exact command and consume a transport nonce without registration."""

        record = await self.load(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        self.require_same_binding(record.binding, binding)
        await self._consume_nonce(connection, binding=binding, nonce=nonce)
        return record

    async def load(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
    ) -> CommandRecord:
        row = await (await connection.execute(LOAD_COMMAND_SQL, (thread_id, command_id))).fetchone()
        if row is None:
            raise GraphCommandNotFoundError()
        return self._command_from_row(row)

    async def load_checkpoint_restore_authority(
        self,
        connection: Any,
        *,
        fence: GraphFenceContext,
    ) -> CheckpointRestoreAuthority | None:
        """Select an exact active command's restore pointer without locking rows.

        Physical checkpoint recency is never authority.  The current command's
        committed pointer wins after it has written a checkpoint; before that,
        only its immutable start pointer may authorize a cross-command read.
        """

        row = await (
            await connection.execute(
                LOAD_CHECKPOINT_RESTORE_AUTHORITY_SQL,
                (
                    fence.thread_id,
                    fence.command_id,
                    fence.request_hash,
                    fence.room_epoch,
                    fence.graph_key,
                    fence.graph_version,
                    fence.checkpoint_schema_version,
                    fence.execution_lane.value,
                    fence.activation_id,
                    fence.room_fencing_token,
                    fence.command_hash,
                    fence.command_envelope_hash,
                    fence.fencing_token,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphTerminalBindingError(
                "active command checkpoint restore authority is missing or stale"
            )

        start = self._checkpoint_restore_pair(row, "start")
        committed = self._checkpoint_restore_pair(row, "committed")
        if committed is not None:
            return CheckpointRestoreAuthority(
                kind=CheckpointRestoreKind.CURRENT_COMMITTED,
                checkpoint_ns=committed[0],
                checkpoint_id=committed[1],
            )
        if start is not None:
            return CheckpointRestoreAuthority(
                kind=CheckpointRestoreKind.COMPLETED_START,
                checkpoint_ns=start[0],
                checkpoint_id=start[1],
            )
        return None

    @staticmethod
    def _checkpoint_restore_pair(
        row: Mapping[str, Any],
        prefix: str,
    ) -> tuple[str, str] | None:
        checkpoint_ns = row.get(f"{prefix}_checkpoint_ns")
        checkpoint_id = row.get(f"{prefix}_checkpoint_id")
        if checkpoint_ns is None and checkpoint_id is None:
            return None
        if (
            not isinstance(checkpoint_ns, str)
            or len(checkpoint_ns) > 128
            or not isinstance(checkpoint_id, str)
            or not checkpoint_id
            or len(checkpoint_id) > 128
        ):
            raise GraphTerminalBindingError(
                f"active command {prefix} checkpoint pointer is incomplete or invalid"
            )
        return checkpoint_ns, checkpoint_id

    async def referenced_verification_key_ids(self, connection: Any) -> frozenset[str]:
        rows = await (await connection.execute(REFERENCED_KEY_IDS_SQL)).fetchall()
        return frozenset(_identifier(row["key_id"], "key_id") for row in rows)

    @staticmethod
    async def _consume_nonce(
        connection: Any,
        *,
        binding: CommandBinding,
        nonce: InvocationNonce,
    ) -> None:
        nonce_row = await (
            await connection.execute(
                INSERT_NONCE_SQL,
                (
                    nonce.issuer,
                    nonce.key_id,
                    nonce.jti,
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    nonce.issued_at,
                    nonce.token_expires_at,
                    nonce.retained_until,
                ),
            )
        ).fetchone()
        if nonce_row is None:
            raise GraphNonceReplayError()

    async def load_recovery_budget(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
    ) -> RecoveryBudget:
        row = await (
            await connection.execute(RECOVERY_BUDGET_SQL, (thread_id, command_id))
        ).fetchone()
        if row is None:
            raise GraphCommandNotFoundError()
        return RecoveryBudget(
            deadline_open=row["deadline_open"],
            provider_call_count=row["provider_call_count"],
        )

    async def begin_attempt(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        attempt_id: str,
        owner_id: str,
        fencing_token: int,
    ) -> tuple[CommandRecord, AttemptRecord]:
        _identifier(attempt_id, "attempt_id")
        if len(attempt_id) > 64:
            raise GraphContractError("attempt_id exceeds the Graph ledger limit")
        _identifier(owner_id, "owner_id")
        if fencing_token < 1:
            raise GraphContractError("fencing_token must be positive")
        row = await (
            await connection.execute(
                BEGIN_ATTEMPT_SQL,
                (
                    fencing_token,
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    binding.room_epoch,
                    binding.graph_key,
                    binding.graph_version,
                    binding.checkpoint_schema_version,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphCommandStateError()
        command = self._command_from_row(row)
        attempt_row = await (
            await connection.execute(
                INSERT_ATTEMPT_SQL,
                (
                    attempt_id,
                    binding.thread_id,
                    binding.command_id,
                    command.attempt_count,
                    owner_id,
                    fencing_token,
                ),
            )
        ).fetchone()
        if attempt_row is None:
            raise GraphCommandStateError("attempt insert returned no row")
        return command, self._attempt_from_row(attempt_row)

    async def latest_attempt(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
    ) -> AttemptRecord | None:
        row = await (
            await connection.execute(LATEST_ATTEMPT_SQL, (thread_id, command_id))
        ).fetchone()
        return None if row is None else self._attempt_from_row(row)

    async def record_provider_call(
        self,
        connection: Any,
        attempt: AttemptRecord,
    ) -> AttemptRecord:
        row = await (
            await connection.execute(
                PROVIDER_CALL_SQL,
                (
                    attempt.attempt_id,
                    attempt.thread_id,
                    attempt.command_id,
                    attempt.owner_id,
                    attempt.fencing_token,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphCommandStateError("provider call lost its lease or attempt")
        return self._attempt_from_row(row)

    async def finish_attempt(
        self,
        connection: Any,
        attempt: AttemptRecord,
        *,
        status: AttemptStatus,
        error_code: str | None = None,
        error_classification: str | None = None,
    ) -> AttemptRecord:
        if status not in {
            AttemptStatus.FAILED,
            AttemptStatus.LEASE_LOST,
            AttemptStatus.CANCELLED,
        }:
            raise GraphCommandStateError(
                "infrastructure finish cannot bypass checkpointed completion"
            )
        if error_code is not None:
            _identifier(error_code, "error_code")
        if error_classification is not None:
            _identifier(error_classification, "error_classification")
        row = await (
            await connection.execute(
                FINISH_ATTEMPT_SQL,
                (
                    status.value,
                    error_code,
                    error_classification,
                    attempt.attempt_id,
                    attempt.thread_id,
                    attempt.command_id,
                    attempt.owner_id,
                    attempt.fencing_token,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphCommandStateError()
        return self._attempt_from_row(row)

    async def terminate(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        status: CommandStatus,
        error_code: str,
        error_classification: str,
    ) -> CommandRecord:
        if status not in {CommandStatus.CANCELLED, CommandStatus.ABORTED}:
            raise GraphCommandStateError("only cancellation or abort may terminate infrastructure")
        _identifier(error_code, "error_code")
        _identifier(error_classification, "error_classification")
        row = await (
            await connection.execute(
                TERMINATE_COMMAND_SQL,
                (
                    status.value,
                    error_code,
                    error_classification,
                    status.value,
                    status.value,
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                ),
            )
        ).fetchone()
        if row is None:
            current = await self.load(
                connection,
                thread_id=binding.thread_id,
                command_id=binding.command_id,
            )
            self.require_same_binding(current.binding, binding)
            if current.status is CommandStatus.RESULT_CHECKPOINTED:
                return current
            if (
                current.status is status
                and current.error_code == error_code
                and current.error_classification == error_classification
            ):
                return current
            raise GraphCommandStateError()
        return self._command_from_row(row)

    async def load_result(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
    ) -> ResultRecord:
        row = await (await connection.execute(LOAD_RESULT_SQL, (thread_id, command_id))).fetchone()
        if row is None:
            raise GraphTerminalBindingError("terminal result row is missing")
        return self._result_from_row(row)

    async def load_completed_start_checkpoint(
        self,
        connection: Any,
        *,
        fence: GraphFenceContext,
        checkpoint_ns: str,
        checkpoint_id: str,
        predecessor_command_id: str,
    ) -> CompletedStartCheckpoint:
        """Prove that one checkpoint is this command's exact completed predecessor.

        The current command's start pointer is captured atomically by
        ``BEGIN_ATTEMPT_SQL``.  A checkpoint from another command is readable
        only when that immutable pointer, the predecessor's committed pointer,
        and its terminal result all agree.
        """

        try:
            _identifier(predecessor_command_id, "predecessor_command_id")
        except GraphContractError as error:
            raise GraphTerminalBindingError(
                "start checkpoint predecessor identity is invalid"
            ) from error
        if (
            not isinstance(checkpoint_ns, str)
            or len(checkpoint_ns) > 128
            or not isinstance(checkpoint_id, str)
            or not checkpoint_id
            or len(checkpoint_id) > 128
            or predecessor_command_id == fence.command_id
        ):
            raise GraphTerminalBindingError("start checkpoint identity is invalid")

        row = await (
            await connection.execute(
                LOAD_COMPLETED_START_CHECKPOINT_SQL,
                (
                    predecessor_command_id,
                    fence.thread_id,
                    fence.command_id,
                    fence.request_hash,
                    fence.room_epoch,
                    fence.graph_key,
                    fence.graph_version,
                    fence.checkpoint_schema_version,
                    fence.execution_lane.value,
                    fence.activation_id,
                    fence.room_fencing_token,
                    fence.command_hash,
                    fence.command_envelope_hash,
                    fence.fencing_token,
                    checkpoint_ns,
                    checkpoint_id,
                    checkpoint_ns,
                    checkpoint_id,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphTerminalBindingError(
                "checkpoint is not the current command's completed start predecessor"
            )

        predecessor = self._command_from_row(row)
        expected_lineage = (
            fence.thread_id,
            fence.room_epoch,
            fence.graph_key,
            fence.graph_version,
            fence.checkpoint_schema_version,
            fence.execution_lane,
            fence.activation_id,
            fence.room_fencing_token,
        )
        actual_lineage = (
            predecessor.binding.thread_id,
            predecessor.binding.room_epoch,
            predecessor.binding.graph_key,
            predecessor.binding.graph_version,
            predecessor.binding.checkpoint_schema_version,
            predecessor.binding.execution_lane,
            predecessor.binding.activation_id,
            predecessor.binding.room_fencing_token,
        )
        if (
            predecessor.binding.command_id != predecessor_command_id
            or predecessor.status is not CommandStatus.COMPLETED
            or predecessor.fencing_token is None
            or isinstance(predecessor.fencing_token, bool)
            or predecessor.fencing_token < 1
            or actual_lineage != expected_lineage
            or (
                predecessor.committed_checkpoint_ns,
                predecessor.committed_checkpoint_id,
            )
            != (checkpoint_ns, checkpoint_id)
            or predecessor.result_ref is None
            or predecessor.result_hash is None
        ):
            raise GraphTerminalBindingError(
                "completed start checkpoint command binding is invalid"
            )

        result = await self.load_result(
            connection,
            thread_id=fence.thread_id,
            command_id=predecessor_command_id,
        )
        self.require_result_matches_command(predecessor, result)
        if (
            result.checkpoint_ns != checkpoint_ns
            or result.checkpoint_id != checkpoint_id
            or result.cognitive_revision < 1
            or result.result_ref != predecessor.result_ref
            or result.result_hash != predecessor.result_hash
        ):
            raise GraphTerminalBindingError(
                "completed start checkpoint result binding is invalid"
            )

        execution_provider: str | None = None
        execution_model: str | None = None
        if result.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            try:
                envelope = TargetE2EGraphResultEnvelope.model_validate(
                    result.result_envelope_json
                )
            except ValueError as error:
                raise GraphTerminalBindingError(
                    "completed candidate start checkpoint envelope is invalid"
                ) from error
            execution_provider = envelope.execution_provider
            execution_model = envelope.execution_model

        return CompletedStartCheckpoint(
            command_id=predecessor.binding.command_id,
            request_hash=predecessor.binding.request_hash,
            fencing_token=predecessor.fencing_token,
            execution_lane=predecessor.binding.execution_lane,
            activation_id=predecessor.binding.activation_id,
            room_fencing_token=predecessor.binding.room_fencing_token,
            command_hash=predecessor.binding.command_hash,
            command_envelope_hash=predecessor.binding.command_envelope_hash,
            checkpoint_ns=result.checkpoint_ns,
            checkpoint_id=result.checkpoint_id,
            cognitive_revision=result.cognitive_revision,
            execution_provider=execution_provider,
            execution_model=execution_model,
            proposal_hash=result.proposal_hash,
            result_envelope_hash=result.result_envelope_hash,
            result_hash=result.result_hash,
            result_ref=result.result_ref,
        )

    async def load_candidate_terminal_proof(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        issuer: str,
        key_id: str,
        jti: str,
        issued_at: datetime,
        token_expires_at: datetime,
    ) -> tuple[CommandRecord, ResultRecord]:
        """Read an immutable pre-cutoff admission/result proof without recovery mutation."""

        row = await (
            await connection.execute(
                LOAD_CANDIDATE_TERMINAL_PROOF_SQL,
                (
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    binding.activation_id,
                    binding.room_fencing_token,
                    binding.command_hash,
                    binding.command_envelope_hash,
                    issuer,
                    key_id,
                    jti,
                    issued_at,
                    token_expires_at,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphTerminalBindingError(
                "candidate command has no exact pre-cutoff terminal admission proof"
            )
        command = self._command_from_row(row)
        self.require_same_binding(command.binding, binding)
        result = await self.load_result(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        self.require_result_matches_command(command, result)
        return command, result

    async def load_candidate_reconciliation_proof(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        issuer: str,
        key_id: str,
    ) -> tuple[CommandRecord, ResultRecord]:
        """Load a terminal candidate through a fresh read-only credential.

        ``issuer`` and ``key_id`` identify the trusted credential family.  The
        credential's JTI and time claims are intentionally not used as the
        durable admission selector: those claims belong to the new
        reconciliation request.  Admission is proven by an historical nonce
        that was valid when this exact immutable command was registered.
        """

        row = await (
            await connection.execute(
                LOAD_CANDIDATE_RECONCILIATION_PROOF_SQL,
                (
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    binding.activation_id,
                    binding.room_fencing_token,
                    binding.command_hash,
                    binding.command_envelope_hash,
                    issuer,
                    key_id,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphTerminalBindingError(
                "candidate command has no pre-cutoff terminal admission proof"
            )
        command = self._command_from_row(row)
        self.require_same_binding(command.binding, binding)
        result = await self.load_result(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        self.require_result_matches_command(command, result)
        return command, result

    async def store_terminal_result(
        self,
        connection: Any,
        *,
        fence: GraphFenceContext,
        result: ResultRecord,
        expected_result_schema_version: str,
    ) -> ResultRecord:
        """Insert an immutable result on the checkpointer's direct transaction connection."""

        self._validate_result_record(result)
        _identifier(expected_result_schema_version, "expected_result_schema_version")
        if (
            fence.result_hash is None
            or fence.result_ref is None
            or result.thread_id != fence.thread_id
            or result.command_id != fence.command_id
            or result.request_hash != fence.request_hash
            or result.result_hash != fence.result_hash
            or result.result_ref != fence.result_ref
            or result.result_schema_version != expected_result_schema_version
        ):
            raise GraphTerminalBindingError("terminal result differs from its fence capability")
        contract_result = RoomGraphResult.model_validate(result.result_json)
        if (
            contract_result.graph_key != fence.graph_key
            or contract_result.graph_version != fence.graph_version
            or contract_result.checkpoint_id != result.checkpoint_id
        ):
            raise GraphTerminalBindingError("terminal result Graph binding differs from its fence")
        command = await self.load(
            connection,
            thread_id=fence.thread_id,
            command_id=fence.command_id,
        )
        if command.status is not CommandStatus.RESULT_CHECKPOINTED:
            raise GraphTerminalBindingError(
                "terminal result requires its checkpointed command on the same connection"
            )
        self.require_result_matches_command(command, result)
        attempt_row = await (
            await connection.execute(
                CHECKPOINT_ATTEMPT_SQL,
                (
                    fence.thread_id,
                    fence.command_id,
                    fence.owner_id,
                    fence.fencing_token,
                ),
            )
        ).fetchone()
        if attempt_row is None:
            raise GraphTerminalBindingError("terminal result has no fenced executing attempt")
        row = await (
            await connection.execute(
                INSERT_RESULT_SQL,
                (
                    result.result_id,
                    result.thread_id,
                    result.command_id,
                    result.request_hash,
                    result.execution_lane.value,
                    result.activation_id,
                    result.room_fencing_token,
                    result.command_hash,
                    result.command_envelope_hash,
                    result.proposal_hash,
                    result.result_envelope_hash,
                    (
                        json.dumps(
                            dict(result.proposal_source_json),
                            ensure_ascii=False,
                            separators=(",", ":"),
                        )
                        if result.proposal_source_json is not None
                        else None
                    ),
                    (
                        json.dumps(
                            dict(result.result_envelope_json),
                            ensure_ascii=False,
                            separators=(",", ":"),
                        )
                        if result.result_envelope_json is not None
                        else None
                    ),
                    result.result_schema_version,
                    result.checkpoint_ns,
                    result.checkpoint_id,
                    result.cognitive_revision,
                    result.terminal_status,
                    json.dumps(
                        dict(result.result_json),
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    result.result_ref,
                    result.result_hash,
                    json.dumps(
                        dict(result.usage_json),
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    fence.thread_id,
                    fence.command_id,
                    fence.owner_id,
                    fence.fencing_token,
                    fence.thread_id,
                    fence.command_id,
                    fence.request_hash,
                    fence.room_epoch,
                    fence.graph_key,
                    fence.graph_version,
                    fence.checkpoint_schema_version,
                    fence.execution_lane.value,
                    fence.activation_id,
                    fence.room_fencing_token,
                    fence.command_hash,
                    fence.command_envelope_hash,
                    fence.fencing_token,
                    result.checkpoint_ns,
                    result.checkpoint_id,
                    result.result_ref,
                    result.result_hash,
                ),
            )
        ).fetchone()
        if row is not None:
            return self._result_from_row(row)
        existing = await self.load_result(
            connection,
            thread_id=result.thread_id,
            command_id=result.command_id,
        )
        if existing != result:
            raise GraphTerminalBindingError("immutable result conflicts with existing row")
        return existing

    async def complete_checkpointed_attempt(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        checkpoint_fencing_token: int,
    ) -> AttemptRecord:
        row = await (
            await connection.execute(
                COMPLETE_CHECKPOINTED_ATTEMPT_SQL,
                (thread_id, command_id, checkpoint_fencing_token),
            )
        ).fetchone()
        if row is not None:
            return self._attempt_from_row(row)
        existing = await self.latest_attempt(
            connection,
            thread_id=thread_id,
            command_id=command_id,
        )
        if (
            existing is None
            or existing.fencing_token != checkpoint_fencing_token
            or existing.status is not AttemptStatus.COMPLETED
        ):
            raise GraphTerminalBindingError("checkpointed attempt cannot be completed")
        return existing

    async def complete_from_checkpoint(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        fencing_token: int,
        result: ResultRecord,
    ) -> CommandRecord:
        if (
            result.thread_id != binding.thread_id
            or result.command_id != binding.command_id
            or result.request_hash != binding.request_hash
        ):
            raise GraphTerminalBindingError()
        row = await (
            await connection.execute(
                COMPLETE_COMMAND_SQL,
                (
                    fencing_token,
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    binding.room_epoch,
                    binding.graph_key,
                    binding.graph_version,
                    binding.checkpoint_schema_version,
                    result.checkpoint_ns,
                    result.checkpoint_id,
                    result.result_ref,
                    result.result_hash,
                ),
            )
        ).fetchone()
        if row is None:
            raise GraphTerminalBindingError()
        return self._command_from_row(row)

    @staticmethod
    def _insert_params(binding: CommandBinding) -> tuple[Any, ...]:
        profile = binding.profile
        return (
            binding.thread_id,
            binding.command_id,
            binding.request_schema_version,
            json.dumps(
                dict(binding.request_json),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            binding.request_hash,
            binding.execution_lane.value,
            binding.activation_id,
            binding.room_fencing_token,
            binding.command_hash,
            binding.command_envelope_hash,
            binding.room_epoch,
            binding.graph_key,
            binding.graph_version,
            binding.checkpoint_schema_version,
            profile.prompt_version,
            profile.model_profile_id,
            profile.output_schema_version,
            profile.policy_version,
            profile.guardrail_version,
            profile.tool_policy_version,
            binding.deadline_at,
            binding.deadline_at,
            binding.execution_lane.value,
            binding.activation_id,
        )

    @staticmethod
    def require_same_binding(actual: CommandBinding, expected: CommandBinding) -> None:
        if not hmac.compare_digest(actual.request_hash, expected.request_hash):
            raise GraphCommandHashConflictError()
        if actual != expected:
            raise GraphCommandBindingError()

    @staticmethod
    def require_result_matches_command(
        command: CommandRecord,
        result: ResultRecord,
    ) -> None:
        """Bind cached/reconciled output to the original run, attempt, and profiles."""

        try:
            contract_result = RoomGraphResult.model_validate(result.result_json)
        except ValueError as error:
            raise GraphTerminalBindingError("result JSON violates RoomGraphResult.v1") from error
        request = command.binding.request_json
        expected_identity = (
            command.binding.thread_id,
            command.binding.command_id,
            command.binding.request_hash,
            request.get("logical_run_id"),
            request.get("attempt_id"),
            command.binding.graph_key,
            command.binding.graph_version,
            command.binding.execution_lane,
            command.binding.activation_id,
            command.binding.room_fencing_token,
            command.binding.command_hash,
            command.binding.command_envelope_hash,
        )
        actual_identity = (
            result.thread_id,
            result.command_id,
            result.request_hash,
            contract_result.logical_run_id,
            contract_result.attempt_id,
            contract_result.graph_key,
            contract_result.graph_version,
            result.execution_lane,
            result.activation_id,
            result.room_fencing_token,
            result.command_hash,
            result.command_envelope_hash,
        )
        if expected_identity != actual_identity:
            raise GraphTerminalBindingError(
                "terminal result differs from its immutable command identity"
            )
        profile = command.binding.profile
        expected_metadata = {
            "prompt_version": profile.prompt_version,
            "model_profile_id": profile.model_profile_id,
            "schema_version": profile.output_schema_version,
            "policy_version": profile.policy_version,
            "guardrail_version": profile.guardrail_version,
        }
        if contract_result.execution_metadata.model_dump(mode="json") != expected_metadata:
            raise GraphTerminalBindingError(
                "terminal result differs from its immutable profile binding"
            )

    @staticmethod
    def _command_from_row(row: Mapping[str, Any]) -> CommandRecord:
        try:
            binding = CommandBinding(
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                request_schema_version=row["request_schema_version"],
                request_json=row["request_json"],
                request_hash=row["request_hash"],
                execution_lane=GraphGatewayMode(row["execution_mode"]),
                activation_id=row["activation_id"],
                room_fencing_token=row["room_fencing_token"],
                command_hash=row["command_hash"],
                command_envelope_hash=row["command_envelope_hash"],
                room_epoch=row["room_epoch"],
                graph_key=row["graph_key"],
                graph_version=row["graph_version"],
                checkpoint_schema_version=row["checkpoint_schema_version"],
                profile=CommandProfileBinding(
                    command_schema_version=row["request_schema_version"],
                    prompt_version=row["prompt_version"],
                    model_profile_id=row["model_profile_id"],
                    output_schema_version=row["output_schema_version"],
                    policy_version=row["policy_version"],
                    guardrail_version=row["guardrail_version"],
                    tool_policy_version=row["tool_policy_version"],
                ),
                deadline_at=row["deadline_at"],
            )
            result_hash = row["result_hash"]
            result_ref = row["result_ref"]
            if (result_hash is None) != (result_ref is None):
                raise GraphTerminalBindingError("partial result binding")
            if result_hash is not None:
                _sha256(result_hash, "result_hash")
            return CommandRecord(
                binding=binding,
                status=CommandStatus(row["status"]),
                attempt_count=row["attempt_count"],
                fencing_token=row["fencing_token"],
                start_checkpoint_ns=row["start_checkpoint_ns"],
                start_checkpoint_id=row["start_checkpoint_id"],
                committed_checkpoint_ns=row["committed_checkpoint_ns"],
                committed_checkpoint_id=row["committed_checkpoint_id"],
                result_ref=result_ref,
                result_hash=result_hash,
                error_code=row["error_code"],
                error_classification=row["error_classification"],
                revision=row["command_revision"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphCommandBindingError("persisted command binding is invalid") from error

    @staticmethod
    def _attempt_from_row(row: Mapping[str, Any]) -> AttemptRecord:
        try:
            return AttemptRecord(
                attempt_id=row["attempt_id"],
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                attempt_no=row["attempt_no"],
                owner_id=row["owner_id"],
                fencing_token=row["fencing_token"],
                status=AttemptStatus(row["attempt_status"]),
                provider_call_count=row["provider_call_count"],
                error_code=row["error_code"],
                error_classification=row["error_classification"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphCommandBindingError("persisted attempt binding is invalid") from error

    @staticmethod
    def _result_from_row(row: Mapping[str, Any]) -> ResultRecord:
        try:
            result = ResultRecord(
                result_id=row["result_id"],
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                request_hash=row["request_hash"],
                execution_lane=GraphGatewayMode(row["execution_mode"]),
                activation_id=row["activation_id"],
                room_fencing_token=row["room_fencing_token"],
                command_hash=row["command_hash"],
                command_envelope_hash=row["command_envelope_hash"],
                proposal_hash=row["proposal_hash"],
                result_envelope_hash=row["result_envelope_hash"],
                proposal_source_json=row["proposal_source_json"],
                result_envelope_json=row["result_envelope_json"],
                result_schema_version=row["result_schema_version"],
                checkpoint_ns=row["checkpoint_ns"],
                checkpoint_id=row["checkpoint_id"],
                cognitive_revision=row["cognitive_revision"],
                terminal_status=row["terminal_status"],
                result_json=row["result_json"],
                result_ref=row["result_ref"],
                result_hash=row["result_hash"],
                usage_json=row["usage_json"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphTerminalBindingError("persisted result binding is invalid") from error
        PostgresCommandLedger._validate_result_record(result)
        return result

    @staticmethod
    def _validate_result_record(result: ResultRecord) -> None:
        _identifier(result.result_id, "result_id")
        if len(result.result_id) > 64:
            raise GraphTerminalBindingError("result_id exceeds the Graph ledger limit")
        if THREAD_ID_PATTERN.fullmatch(result.thread_id) is None:
            raise GraphTerminalBindingError("result thread ID is invalid")
        _identifier(result.command_id, "command_id")
        _sha256(result.request_hash, "request_hash")
        if not isinstance(result.execution_lane, GraphGatewayMode) or result.execution_lane is (
            GraphGatewayMode.DISABLED
        ):
            raise GraphTerminalBindingError("result execution lane is invalid")
        if result.execution_lane is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            if result.activation_id is None or re.fullmatch(
                r"p9act\.v1\.[0-9a-f]{32}", result.activation_id
            ) is None:
                raise GraphTerminalBindingError("candidate result activation ID is invalid")
            try:
                _sha256(result.command_envelope_hash, "command_envelope_hash")
                _sha256(result.command_hash, "command_hash")
                _sha256(result.proposal_hash, "proposal_hash")
                _sha256(result.result_envelope_hash, "result_envelope_hash")
            except GraphContractError as error:
                raise GraphTerminalBindingError(
                    "candidate result command envelope hash is invalid"
                ) from error
            if (
                not isinstance(result.room_fencing_token, int)
                or isinstance(result.room_fencing_token, bool)
                or result.room_fencing_token < 1
            ):
                raise GraphTerminalBindingError("candidate result room fence is invalid")
            try:
                proposal_source = TargetE2ERoomProposalSource.model_validate(
                    result.proposal_source_json
                )
                envelope = TargetE2EGraphResultEnvelope.model_validate(
                    result.result_envelope_json
                )
                nested = RoomGraphResult.model_validate(result.result_json)
                proposal_source.require_result_binding(nested)
                envelope.require_proposal_hash(
                    proposal_source.proposal.model_dump(mode="json")
                )
            except (TypeError, ValueError) as error:
                raise GraphTerminalBindingError(
                    "candidate result proposal or envelope is invalid"
                ) from error
            if (
                envelope.activation_id != result.activation_id
                or envelope.room_fencing_token != result.room_fencing_token
                or envelope.command_hash != result.command_hash
                or envelope.command_envelope_hash != result.command_envelope_hash
                or envelope.result_hash != result.result_hash
                or envelope.proposal_hash != result.proposal_hash
                or envelope.result_envelope_hash != result.result_envelope_hash
                or envelope.result.model_dump(mode="json", exclude_none=True)
                != dict(result.result_json)
            ):
                raise GraphTerminalBindingError(
                    "candidate result envelope differs from durable columns"
                )
        elif (
            result.activation_id is not None
            or result.room_fencing_token is not None
            or result.command_hash is not None
            or result.command_envelope_hash is not None
            or result.proposal_hash is not None
            or result.result_envelope_hash is not None
            or result.proposal_source_json is not None
            or result.result_envelope_json is not None
        ):
            raise GraphTerminalBindingError("SHADOW result cannot carry candidate activation")
        _identifier(result.result_schema_version, "result_schema_version")
        if (
            len(result.checkpoint_ns) > 128
            or not result.checkpoint_id
            or len(result.checkpoint_id) > 128
        ):
            raise GraphTerminalBindingError("result checkpoint identity is invalid")
        if result.cognitive_revision < 0:
            raise GraphTerminalBindingError("result cognitive revision is invalid")
        if result.terminal_status not in {
            "COMPLETED",
            "NEEDS_INPUT",
            "NEEDS_REVIEW",
            "FAILED",
        }:
            raise GraphTerminalBindingError("result terminal status is invalid")
        if not result.result_ref or len(result.result_ref) > 512:
            raise GraphTerminalBindingError("result reference is invalid")
        _sha256(result.result_hash, "result_hash")
        if not isinstance(result.result_json, Mapping) or not isinstance(
            result.usage_json, Mapping
        ):
            raise GraphTerminalBindingError("persisted result payload is invalid")
        try:
            result_bytes = canonicalize(dict(result.result_json))
            usage_bytes = canonicalize(dict(result.usage_json))
        except (TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted result payload is not RFC 8785 serializable"
            ) from error
        if len(result_bytes) > 65_536 or len(usage_bytes) > 16_384:
            raise GraphTerminalBindingError("persisted result payload exceeds its ledger limit")
        if result.result_json.get("output_hash") != result.result_hash:
            raise GraphTerminalBindingError("result JSON hash binding differs")
        if canonical_sha256_omitting(result.result_json, "output_hash") != result.result_hash:
            raise GraphTerminalBindingError("result JSON self-hash is invalid")
        try:
            contract_result = RoomGraphResult.model_validate(result.result_json)
        except ValueError as error:
            raise GraphTerminalBindingError("result JSON violates RoomGraphResult.v1") from error
        if dict(result.usage_json) != contract_result.usage.model_dump(mode="json"):
            raise GraphTerminalBindingError("result usage columns differ from RoomGraphResult.v1")
        if (
            contract_result.schema_version != result.result_schema_version
            or contract_result.command_id != result.command_id
            or contract_result.checkpoint_id != result.checkpoint_id
            or contract_result.cognitive_revision != result.cognitive_revision
            or contract_result.status != result.terminal_status
        ):
            raise GraphTerminalBindingError("result columns differ from RoomGraphResult.v1")
