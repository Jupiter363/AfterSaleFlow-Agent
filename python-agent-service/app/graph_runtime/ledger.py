"""Durable command, attempt, result, and invocation-nonce ledger."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from enum import StrEnum
import hmac
import json
import re
from typing import Any, ClassVar, Final

from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting, canonicalize
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
    TECHNICAL_COMPLETED = "TECHNICAL_COMPLETED"
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
            CommandStatus.TECHNICAL_COMPLETED,
            CommandStatus.CANCELLED,
            CommandStatus.ABORTED,
        }
    ),
    CommandStatus.RESULT_CHECKPOINTED: frozenset({CommandStatus.COMPLETED}),
    CommandStatus.COMPLETED: frozenset(),
    CommandStatus.TECHNICAL_COMPLETED: frozenset(),
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
            CommandStatus.TECHNICAL_COMPLETED,
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
class TechnicalCompletionRecord:
    completion_id: str
    thread_id: str
    command_id: str
    request_hash: str
    attempt_id: str
    fencing_token: int
    completion_schema_version: str
    completion_json: Mapping[str, Any]
    completion_hash: str

    def __post_init__(self) -> None:
        for field_name in (
            "completion_id",
            "command_id",
            "attempt_id",
            "completion_schema_version",
        ):
            _identifier(getattr(self, field_name), field_name)
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise GraphContractError("technical completion thread_id is invalid")
        _sha256(self.request_hash, "request_hash")
        _sha256(self.completion_hash, "completion_hash")
        if isinstance(self.fencing_token, bool) or self.fencing_token < 1:
            raise GraphContractError("technical completion fence is invalid")
        if not isinstance(self.completion_json, Mapping):
            raise GraphContractError("technical completion JSON must be an object")
        document = dict(self.completion_json)
        try:
            canonical = canonicalize(document)
        except (TypeError, ValueError) as error:
            raise GraphContractError(
                "technical completion JSON is not RFC 8785 serializable"
            ) from error
        if len(canonical) > 1_048_576:
            raise GraphContractError("technical completion exceeds the 1 MiB ledger limit")
        expected_bindings = {
            "completion_id": self.completion_id,
            "thread_id": self.thread_id,
            "command_id": self.command_id,
            "request_hash": self.request_hash,
            "attempt_id": self.attempt_id,
            "fencing_token": self.fencing_token,
            "schema_version": self.completion_schema_version,
            "completion_hash": self.completion_hash,
        }
        if any(document.get(key) != value for key, value in expected_bindings.items()):
            raise GraphTerminalBindingError(
                "technical completion document differs from its ledger binding"
            )
        if canonical_sha256_omitting(document, "completion_hash") != self.completion_hash:
            raise GraphTerminalBindingError("technical completion self-hash is invalid")

    def canonical_json_text(self) -> str:
        """Revalidate mutable nested values immediately before persistence."""

        refreshed = TechnicalCompletionRecord(
            completion_id=self.completion_id,
            thread_id=self.thread_id,
            command_id=self.command_id,
            request_hash=self.request_hash,
            attempt_id=self.attempt_id,
            fencing_token=self.fencing_token,
            completion_schema_version=self.completion_schema_version,
            completion_json=self.completion_json,
            completion_hash=self.completion_hash,
        )
        return canonicalize(dict(refreshed.completion_json)).decode("utf-8")


@dataclass(frozen=True, slots=True)
class ParallelReceiptExecutionRecord:
    """One immutable Java admission receipt bound to one attempt fence."""

    execution_id: str
    thread_id: str
    command_id: str
    request_hash: str
    attempt_id: str
    frame_set_id: str
    receipt_sha256: str
    authority_sha256: str
    predecessor_cycle_id: str | None
    predecessor_execution_id: str | None
    provider_call_count_at_admission: int
    owner_id: str
    fencing_token: int
    predecessor_abandonment_id: str | None = None

    def __post_init__(self) -> None:
        for field_name in (
            "execution_id",
            "command_id",
            "attempt_id",
            "frame_set_id",
            "owner_id",
        ):
            _identifier(getattr(self, field_name), field_name)
        if self.predecessor_cycle_id is not None:
            _identifier(self.predecessor_cycle_id, "predecessor_cycle_id")
        if self.predecessor_execution_id is not None:
            _identifier(self.predecessor_execution_id, "predecessor_execution_id")
        if self.predecessor_abandonment_id is not None:
            _identifier(
                self.predecessor_abandonment_id,
                "predecessor_abandonment_id",
            )
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise GraphContractError("parallel receipt execution thread_id is invalid")
        for field_name in ("request_hash", "receipt_sha256", "authority_sha256"):
            _sha256(getattr(self, field_name), field_name)
        if (
            isinstance(self.fencing_token, bool)
            or self.fencing_token < 1
            or isinstance(self.provider_call_count_at_admission, bool)
            or self.provider_call_count_at_admission < 0
        ):
            raise GraphContractError("parallel receipt execution fence is invalid")
        if sum(
            predecessor is not None
            for predecessor in (
                self.predecessor_cycle_id,
                self.predecessor_execution_id,
                self.predecessor_abandonment_id,
            )
        ) > 1:
            raise GraphTerminalBindingError(
                "parallel receipt execution cannot have multiple predecessors"
            )
        if self.execution_id != self.execution_id_for_receipt(
            self.receipt_sha256,
            self.fencing_token,
        ):
            raise GraphTerminalBindingError(
                "parallel receipt execution ID is not deterministic"
            )

    @staticmethod
    def execution_id_for_receipt(receipt_sha256: str, fencing_token: int) -> str:
        _sha256(receipt_sha256, "receipt_sha256")
        if isinstance(fencing_token, bool) or fencing_token < 1:
            raise GraphContractError("parallel receipt execution fence is invalid")
        return f"parallel-receipt-execution.{receipt_sha256[:24]}.{fencing_token}"

    @classmethod
    def create(
        cls,
        *,
        thread_id: str,
        command_id: str,
        request_hash: str,
        attempt_id: str,
        frame_set_id: str,
        receipt_sha256: str,
        authority_sha256: str,
        predecessor_cycle_id: str | None,
        predecessor_execution_id: str | None = None,
        predecessor_abandonment_id: str | None = None,
        provider_call_count_at_admission: int,
        owner_id: str,
        fencing_token: int,
    ) -> ParallelReceiptExecutionRecord:
        return cls(
            execution_id=cls.execution_id_for_receipt(receipt_sha256, fencing_token),
            thread_id=thread_id,
            command_id=command_id,
            request_hash=request_hash,
            attempt_id=attempt_id,
            frame_set_id=frame_set_id,
            receipt_sha256=receipt_sha256,
            authority_sha256=authority_sha256,
            predecessor_cycle_id=predecessor_cycle_id,
            predecessor_execution_id=predecessor_execution_id,
            provider_call_count_at_admission=provider_call_count_at_admission,
            owner_id=owner_id,
            fencing_token=fencing_token,
            predecessor_abandonment_id=predecessor_abandonment_id,
        )


_PARALLEL_FRAME_TYPES: Final[tuple[str, str, str]] = (
    "DIALOGUE_FRAME",
    "DOSSIER_FRAME",
    "QUALITY_FRAME",
)
_PARALLEL_RECEIPT_FIELDS: Final[frozenset[str]] = frozenset(
    {
        "schema_version",
        "request_hash",
        "frame_set_id",
        "run_id",
        "attempt_id",
        "java_receipt_id",
        "authority_sha256",
        "lanes",
        "receipt_sha256",
    }
)


def _validated_parallel_receipt_document(
    value: Mapping[str, Any],
) -> dict[str, Any]:
    document = dict(value)
    lanes = document.get("lanes")
    if (
        set(document) != _PARALLEL_RECEIPT_FIELDS
        or document.get("schema_version")
        != "intake.parallel-admission-receipt.v1"
        or not isinstance(lanes, list)
        or len(lanes) != len(_PARALLEL_FRAME_TYPES)
        or tuple(
            lane.get("frame_type") if isinstance(lane, Mapping) else None
            for lane in lanes
        )
        != _PARALLEL_FRAME_TYPES
    ):
        raise GraphTerminalBindingError(
            "parallel admission receipt document is invalid"
        )
    for field_name in (
        "frame_set_id",
        "run_id",
        "attempt_id",
        "java_receipt_id",
    ):
        _identifier(document.get(field_name), field_name)
    for field_name in ("request_hash", "authority_sha256"):
        _sha256(document.get(field_name), field_name)
    lane_fields = {
        "frame_type",
        "generation",
        "frame_id",
        "slot_state",
        "action",
        "next_local_index",
        "slot_version",
        "result_id",
        "result_sha256",
        "public_projection_sha256",
        "predecessor_failure_code",
    }
    for expected_frame_type, lane in zip(
        _PARALLEL_FRAME_TYPES,
        lanes,
        strict=True,
    ):
        if not isinstance(lane, Mapping) or set(lane) != lane_fields:
            raise GraphTerminalBindingError(
                "parallel admission receipt lane fields are invalid"
            )
        action = lane.get("action")
        slot_state = lane.get("slot_state")
        generation = lane.get("generation")
        next_local_index = lane.get("next_local_index")
        slot_version = lane.get("slot_version")
        result_id = lane.get("result_id")
        result_sha256 = lane.get("result_sha256")
        public_projection_sha256 = lane.get("public_projection_sha256")
        predecessor_failure_code = lane.get("predecessor_failure_code")
        if (
            lane.get("frame_type") != expected_frame_type
            or type(generation) is not int
            or not 1 <= generation <= 2
            or type(next_local_index) is not int
            or next_local_index < 0
            or type(slot_version) is not int
            or slot_version < 0
            or action not in {"RUN_CURRENT", "RUN_RETRY", "SKIP_SEALED"}
            or slot_state not in {"ADMITTED", "SEALED"}
        ):
            raise GraphTerminalBindingError(
                "parallel admission receipt lane authority is invalid"
            )
        _identifier(lane.get("frame_id"), "frame_id")
        if result_id is not None:
            _identifier(result_id, "result_id")
        for field_name, field_value in (
            ("result_sha256", result_sha256),
            ("public_projection_sha256", public_projection_sha256),
        ):
            if field_value is not None:
                _sha256(field_value, field_name)
        if predecessor_failure_code is not None:
            _identifier(predecessor_failure_code, "predecessor_failure_code")
        if (
            (action == "SKIP_SEALED")
            != (
                slot_state == "SEALED"
                and result_id is not None
                and result_sha256 is not None
                and public_projection_sha256 is not None
                and predecessor_failure_code is None
            )
            or (
                action != "SKIP_SEALED"
                and (
                    slot_state != "ADMITTED"
                    or next_local_index != 0
                    or result_id is not None
                    or result_sha256 is not None
                    or public_projection_sha256 is not None
                )
            )
            or ((action == "RUN_RETRY") != (predecessor_failure_code is not None))
            or (action == "RUN_RETRY" and generation != 2)
        ):
            raise GraphTerminalBindingError(
                "parallel admission receipt lane state is invalid"
            )
    receipt_sha256 = document.get("receipt_sha256")
    if not isinstance(receipt_sha256, str):
        raise GraphTerminalBindingError("parallel admission receipt hash is invalid")
    _sha256(receipt_sha256, "receipt_sha256")
    unsigned = dict(document)
    unsigned.pop("receipt_sha256")
    if canonical_sha256(unsigned) != receipt_sha256:
        raise GraphTerminalBindingError(
            "parallel admission receipt self-hash is invalid"
        )
    return json.loads(canonicalize(document))


@dataclass(frozen=True, slots=True)
class ParallelReceiptAbandonmentRecord:
    """Immutable proof that an expired receipt execution may be replaced.

    The record does not guess which Frame called the Provider.  It proves only
    that the exact receipt owned an execution whose durable Provider intent
    advanced before its lease expired and before any receipt cycle completed.
    Java combines this proof with its current STARTED slots and publishes the
    exact successor receipt.
    """

    abandonment_id: str
    execution_id: str
    thread_id: str
    command_id: str
    request_hash: str
    attempt_id: str
    frame_set_id: str
    receipt_sha256: str
    authority_sha256: str
    admission_receipt: Mapping[str, Any]
    provider_call_count_before: int
    provider_call_count_after: int
    owner_id: str
    fencing_token: int
    abandoned_at: datetime
    abandonment_sha256: str

    SCHEMA_VERSION: ClassVar[str] = "intake.parallel-receipt-abandonment.v1"
    AMBIGUOUS_FAILURE_CODE: ClassVar[str] = "CALL_STATE_AMBIGUOUS"

    def __post_init__(self) -> None:
        for field_name in (
            "abandonment_id",
            "execution_id",
            "command_id",
            "attempt_id",
            "frame_set_id",
            "owner_id",
        ):
            _identifier(getattr(self, field_name), field_name)
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise GraphContractError("parallel receipt abandonment thread_id is invalid")
        for field_name in (
            "request_hash",
            "receipt_sha256",
            "authority_sha256",
            "abandonment_sha256",
        ):
            _sha256(getattr(self, field_name), field_name)
        _aware(self.abandoned_at, "abandoned_at")
        if (
            isinstance(self.fencing_token, bool)
            or self.fencing_token < 1
            or isinstance(self.provider_call_count_before, bool)
            or isinstance(self.provider_call_count_after, bool)
            or self.provider_call_count_before < 0
            or self.provider_call_count_after <= self.provider_call_count_before
        ):
            raise GraphContractError("parallel receipt abandonment counters are invalid")
        receipt_document = _validated_parallel_receipt_document(
            self.admission_receipt
        )
        object.__setattr__(self, "admission_receipt", receipt_document)
        if (
            receipt_document["request_hash"] != self.request_hash
            or receipt_document["frame_set_id"] != self.frame_set_id
            or receipt_document["attempt_id"] != self.attempt_id
            or receipt_document["receipt_sha256"] != self.receipt_sha256
            or receipt_document["authority_sha256"] != self.authority_sha256
        ):
            raise GraphTerminalBindingError(
                "parallel admission receipt differs from its abandonment"
            )
        if self.execution_id != ParallelReceiptExecutionRecord.execution_id_for_receipt(
            self.receipt_sha256,
            self.fencing_token,
        ):
            raise GraphTerminalBindingError(
                "parallel receipt abandonment execution ID is not deterministic"
            )
        if self.abandonment_id != self.abandonment_id_for_receipt(
            self.receipt_sha256,
            self.fencing_token,
        ):
            raise GraphTerminalBindingError(
                "parallel receipt abandonment ID is not deterministic"
            )
        if canonical_sha256(self.hash_document()) != self.abandonment_sha256:
            raise GraphTerminalBindingError(
                "parallel receipt abandonment self-hash is invalid"
            )

    @staticmethod
    def abandonment_id_for_receipt(
        receipt_sha256: str,
        fencing_token: int,
    ) -> str:
        _sha256(receipt_sha256, "receipt_sha256")
        if isinstance(fencing_token, bool) or fencing_token < 1:
            raise GraphContractError("parallel receipt abandonment fence is invalid")
        return f"parallel-receipt-abandonment.{receipt_sha256[:24]}.{fencing_token}"

    @staticmethod
    def _canonical_time(value: datetime) -> str:
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def hash_document(self) -> dict[str, Any]:
        return {
            "schema_version": self.SCHEMA_VERSION,
            "abandonment_id": self.abandonment_id,
            "execution_id": self.execution_id,
            "thread_id": self.thread_id,
            "command_id": self.command_id,
            "request_hash": self.request_hash,
            "attempt_id": self.attempt_id,
            "frame_set_id": self.frame_set_id,
            "receipt_sha256": self.receipt_sha256,
            "authority_sha256": self.authority_sha256,
            "admission_receipt": dict(self.admission_receipt),
            "provider_call_count_before": self.provider_call_count_before,
            "provider_call_count_after": self.provider_call_count_after,
            "owner_id": self.owner_id,
            "fencing_token": self.fencing_token,
            "abandoned_at": self._canonical_time(self.abandoned_at),
        }

    def canonical_document(self) -> dict[str, Any]:
        document = self.hash_document()
        if canonical_sha256(document) != self.abandonment_sha256:
            raise GraphTerminalBindingError(
                "parallel receipt abandonment self-hash is invalid"
            )
        document["abandonment_sha256"] = self.abandonment_sha256
        return json.loads(canonicalize(document))

    def canonical_admission_receipt_json_text(self) -> str:
        refreshed = _validated_parallel_receipt_document(self.admission_receipt)
        return canonicalize(refreshed).decode("utf-8")

    def require_successor_receipt(
        self,
        successor: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Validate Java's monotonic successor without inventing lane state."""

        current = _validated_parallel_receipt_document(self.admission_receipt)
        candidate = _validated_parallel_receipt_document(successor)
        if (
            candidate["receipt_sha256"] == current["receipt_sha256"]
            or any(
                candidate[field] != current[field]
                for field in (
                    "request_hash",
                    "frame_set_id",
                    "run_id",
                    "attempt_id",
                    "authority_sha256",
                )
            )
        ):
            raise GraphTerminalBindingError(
                "parallel abandonment successor crossed its predecessor authority"
            )
        retry_count = 0
        for prior_lane, next_lane in zip(
            current["lanes"], candidate["lanes"], strict=True
        ):
            if next_lane["frame_type"] != prior_lane["frame_type"]:
                raise GraphTerminalBindingError(
                    "parallel abandonment successor Frame order drifted"
                )
            if prior_lane["action"] == "SKIP_SEALED":
                stable_fields = (
                    "generation",
                    "frame_id",
                    "slot_state",
                    "action",
                    "next_local_index",
                    "slot_version",
                    "result_id",
                    "result_sha256",
                    "public_projection_sha256",
                    "predecessor_failure_code",
                )
                if any(next_lane[field] != prior_lane[field] for field in stable_fields):
                    raise GraphTerminalBindingError(
                        "parallel abandonment changed an already sealed sibling"
                    )
                continue
            if next_lane["action"] == "SKIP_SEALED":
                if (
                    next_lane["generation"] != prior_lane["generation"]
                    or next_lane["frame_id"] != prior_lane["frame_id"]
                    or next_lane["slot_version"] <= prior_lane["slot_version"]
                ):
                    raise GraphTerminalBindingError(
                        "parallel abandonment successor changed a newly sealed lane"
                    )
                continue
            if (
                next_lane["action"] == prior_lane["action"]
                and next_lane["generation"] == prior_lane["generation"]
                and next_lane["frame_id"] == prior_lane["frame_id"]
                and next_lane["slot_state"] == prior_lane["slot_state"]
                and next_lane["next_local_index"] == prior_lane["next_local_index"]
                and next_lane["slot_version"] == prior_lane["slot_version"]
                and next_lane["result_id"] == prior_lane["result_id"]
                and next_lane["result_sha256"] == prior_lane["result_sha256"]
                and next_lane["public_projection_sha256"]
                == prior_lane["public_projection_sha256"]
                and next_lane["predecessor_failure_code"]
                == prior_lane["predecessor_failure_code"]
            ):
                continue
            if (
                prior_lane["generation"] >= 2
                or next_lane["action"] != "RUN_RETRY"
                or next_lane["generation"] != prior_lane["generation"] + 1
                or next_lane["frame_id"] == prior_lane["frame_id"]
                or next_lane["slot_state"] != "ADMITTED"
                or next_lane["next_local_index"] != 0
                or next_lane["slot_version"] <= prior_lane["slot_version"]
                or next_lane["result_id"] is not None
                or next_lane["result_sha256"] is not None
                or next_lane["public_projection_sha256"] is not None
                or next_lane["predecessor_failure_code"]
                != self.AMBIGUOUS_FAILURE_CODE
            ):
                raise GraphTerminalBindingError(
                    "parallel abandonment successor lacks exact ambiguous lineage"
                )
            retry_count += 1
        if retry_count < 1:
            raise GraphTerminalBindingError(
                "parallel abandonment successor did not retry an ambiguous lane"
            )
        return candidate

    @classmethod
    def create(
        cls,
        *,
        thread_id: str,
        command_id: str,
        request_hash: str,
        attempt_id: str,
        frame_set_id: str,
        receipt_sha256: str,
        authority_sha256: str,
        admission_receipt: Mapping[str, Any],
        provider_call_count_before: int,
        provider_call_count_after: int,
        owner_id: str,
        fencing_token: int,
        abandoned_at: datetime,
    ) -> ParallelReceiptAbandonmentRecord:
        abandonment_id = cls.abandonment_id_for_receipt(
            receipt_sha256,
            fencing_token,
        )
        execution_id = ParallelReceiptExecutionRecord.execution_id_for_receipt(
            receipt_sha256,
            fencing_token,
        )
        values = {
            "schema_version": cls.SCHEMA_VERSION,
            "abandonment_id": abandonment_id,
            "execution_id": execution_id,
            "thread_id": thread_id,
            "command_id": command_id,
            "request_hash": request_hash,
            "attempt_id": attempt_id,
            "frame_set_id": frame_set_id,
            "receipt_sha256": receipt_sha256,
            "authority_sha256": authority_sha256,
            "admission_receipt": dict(admission_receipt),
            "provider_call_count_before": provider_call_count_before,
            "provider_call_count_after": provider_call_count_after,
            "owner_id": owner_id,
            "fencing_token": fencing_token,
            "abandoned_at": cls._canonical_time(abandoned_at),
        }
        return cls(
            abandonment_id=abandonment_id,
            execution_id=execution_id,
            thread_id=thread_id,
            command_id=command_id,
            request_hash=request_hash,
            attempt_id=attempt_id,
            frame_set_id=frame_set_id,
            receipt_sha256=receipt_sha256,
            authority_sha256=authority_sha256,
            admission_receipt=dict(admission_receipt),
            provider_call_count_before=provider_call_count_before,
            provider_call_count_after=provider_call_count_after,
            owner_id=owner_id,
            fencing_token=fencing_token,
            abandoned_at=abandoned_at,
            abandonment_sha256=canonical_sha256(values),
        )


@dataclass(frozen=True, slots=True)
class ParallelReceiptCycleRecord:
    """One immutable, non-terminal parallel execution receipt.

    A cycle records the exact public technical events produced for one Java
    admission receipt.  It does not complete the Graph command or attempt.  A
    later receipt may use this record to advance the lease fence while keeping
    the same attempt and the already sealed sibling Frames.
    """

    cycle_id: str
    execution_id: str
    thread_id: str
    command_id: str
    request_hash: str
    attempt_id: str
    frame_set_id: str
    receipt_sha256: str
    authority_sha256: str
    admission_receipt: Mapping[str, Any]
    canonical_events: tuple[Mapping[str, Any], ...]
    terminal_error_code: str
    terminal_retryable: bool
    completion_sha256: str
    provider_call_count_before: int
    provider_call_count_after: int
    owner_id: str
    fencing_token: int

    SCHEMA_VERSION: ClassVar[str] = "intake-parallel-receipt-cycle.v1"

    def __post_init__(self) -> None:
        for field_name in (
            "cycle_id",
            "execution_id",
            "command_id",
            "attempt_id",
            "frame_set_id",
            "owner_id",
        ):
            _identifier(getattr(self, field_name), field_name)
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise GraphContractError("parallel receipt cycle thread_id is invalid")
        for field_name in (
            "request_hash",
            "receipt_sha256",
            "authority_sha256",
            "completion_sha256",
        ):
            _sha256(getattr(self, field_name), field_name)
        if (
            isinstance(self.fencing_token, bool)
            or self.fencing_token < 1
            or isinstance(self.provider_call_count_before, bool)
            or isinstance(self.provider_call_count_after, bool)
            or self.provider_call_count_before < 0
            or self.provider_call_count_after <= self.provider_call_count_before
        ):
            raise GraphContractError("parallel receipt cycle counters are invalid")
        if (
            not isinstance(self.canonical_events, Sequence)
            or isinstance(self.canonical_events, (str, bytes, bytearray))
            or not self.canonical_events
        ):
            raise GraphContractError("parallel receipt cycle requires public events")
        normalized: list[dict[str, Any]] = []
        for event in self.canonical_events:
            if not isinstance(event, Mapping):
                raise GraphContractError("parallel receipt cycle event must be an object")
            normalized.append(dict(event))
        object.__setattr__(self, "canonical_events", tuple(normalized))
        receipt_document = _validated_parallel_receipt_document(
            self.admission_receipt
        )
        object.__setattr__(self, "admission_receipt", receipt_document)
        if (
            receipt_document["request_hash"] != self.request_hash
            or receipt_document["frame_set_id"] != self.frame_set_id
            or receipt_document["attempt_id"] != self.attempt_id
            or receipt_document["receipt_sha256"] != self.receipt_sha256
            or receipt_document["authority_sha256"] != self.authority_sha256
        ):
            raise GraphTerminalBindingError(
                "parallel admission receipt differs from its cycle"
            )
        if len(canonicalize(normalized)) > 1_048_576:
            raise GraphContractError("parallel receipt cycle exceeds the 1 MiB ledger limit")
        if self.cycle_id != self.cycle_id_for_receipt(self.receipt_sha256):
            raise GraphTerminalBindingError("parallel receipt cycle ID is not deterministic")
        if self.execution_id != ParallelReceiptExecutionRecord.execution_id_for_receipt(
            self.receipt_sha256,
            self.fencing_token,
        ):
            raise GraphTerminalBindingError(
                "parallel receipt cycle execution ID is not deterministic"
            )
        if (
            re.fullmatch(r"[A-Z][A-Z0-9_]{2,127}", self.terminal_error_code)
            is None
            or self.terminal_retryable is not True
        ):
            raise GraphContractError("parallel receipt cycle terminal outcome is invalid")
        if canonical_sha256(self.hash_document()) != self.completion_sha256:
            raise GraphTerminalBindingError("parallel receipt cycle self-hash is invalid")

    @staticmethod
    def cycle_id_for_receipt(receipt_sha256: str) -> str:
        _sha256(receipt_sha256, "receipt_sha256")
        return f"parallel-receipt-cycle.{receipt_sha256[:32]}"

    def hash_document(self) -> dict[str, Any]:
        return {
            "schema_version": self.SCHEMA_VERSION,
            "cycle_id": self.cycle_id,
            "execution_id": self.execution_id,
            "thread_id": self.thread_id,
            "command_id": self.command_id,
            "request_hash": self.request_hash,
            "attempt_id": self.attempt_id,
            "frame_set_id": self.frame_set_id,
            "receipt_sha256": self.receipt_sha256,
            "authority_sha256": self.authority_sha256,
            "admission_receipt": dict(self.admission_receipt),
            "events": [dict(event) for event in self.canonical_events],
            "terminal_error_code": self.terminal_error_code,
            "terminal_retryable": self.terminal_retryable,
            "provider_call_count_before": self.provider_call_count_before,
            "provider_call_count_after": self.provider_call_count_after,
            "owner_id": self.owner_id,
            "fencing_token": self.fencing_token,
        }

    def canonical_events_json_text(self) -> str:
        """Revalidate nested mutable values immediately before persistence."""

        refreshed = ParallelReceiptCycleRecord(
            cycle_id=self.cycle_id,
            execution_id=self.execution_id,
            thread_id=self.thread_id,
            command_id=self.command_id,
            request_hash=self.request_hash,
            attempt_id=self.attempt_id,
            frame_set_id=self.frame_set_id,
            receipt_sha256=self.receipt_sha256,
            authority_sha256=self.authority_sha256,
            admission_receipt=self.admission_receipt,
            canonical_events=self.canonical_events,
            terminal_error_code=self.terminal_error_code,
            terminal_retryable=self.terminal_retryable,
            completion_sha256=self.completion_sha256,
            provider_call_count_before=self.provider_call_count_before,
            provider_call_count_after=self.provider_call_count_after,
            owner_id=self.owner_id,
            fencing_token=self.fencing_token,
        )
        return canonicalize(
            [dict(event) for event in refreshed.canonical_events]
        ).decode("utf-8")

    def canonical_admission_receipt_json_text(self) -> str:
        refreshed = _validated_parallel_receipt_document(self.admission_receipt)
        return canonicalize(refreshed).decode("utf-8")

    def require_successor_receipt(
        self,
        successor: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Prove one Java plan is the direct monotonic successor of this cycle."""

        current = _validated_parallel_receipt_document(self.admission_receipt)
        candidate = _validated_parallel_receipt_document(successor)
        if (
            candidate["receipt_sha256"] == current["receipt_sha256"]
            or any(
                candidate[field] != current[field]
                for field in (
                    "request_hash",
                    "frame_set_id",
                    "run_id",
                    "attempt_id",
                    "authority_sha256",
                )
            )
        ):
            raise GraphTerminalBindingError(
                "parallel successor receipt crossed its predecessor authority"
            )
        terminal_events: dict[str, Mapping[str, Any]] = {}
        for event in self.canonical_events:
            frame_type = event.get("frame_type")
            if (
                frame_type in _PARALLEL_FRAME_TYPES
                and event.get("event_kind") in {"FRAME_SEALED", "FRAME_INTERRUPTED"}
            ):
                terminal_events[str(frame_type)] = event
        for prior_lane, next_lane in zip(
            current["lanes"], candidate["lanes"], strict=True
        ):
            frame_type = prior_lane["frame_type"]
            if next_lane["frame_type"] != frame_type:
                raise GraphTerminalBindingError(
                    "parallel successor receipt Frame order drifted"
                )
            if prior_lane["action"] == "SKIP_SEALED":
                stable_fields = (
                    "generation",
                    "frame_id",
                    "slot_state",
                    "action",
                    "next_local_index",
                    "slot_version",
                    "result_id",
                    "result_sha256",
                    "public_projection_sha256",
                    "predecessor_failure_code",
                )
                if any(next_lane[field] != prior_lane[field] for field in stable_fields):
                    raise GraphTerminalBindingError(
                        "parallel successor changed an already sealed sibling"
                    )
                continue
            terminal = terminal_events.get(frame_type)
            if terminal is None:
                raise GraphTerminalBindingError(
                    "parallel receipt cycle lost an active lane terminal event"
                )
            if terminal["event_kind"] == "FRAME_SEALED":
                if (
                    next_lane["slot_state"] != "SEALED"
                    or next_lane["action"] != "SKIP_SEALED"
                    or next_lane["generation"] != terminal["generation"]
                    or next_lane["frame_id"] != terminal["frame_id"]
                    or next_lane["next_local_index"] != terminal["next_local_index"]
                    or next_lane["result_id"] is None
                    or next_lane["result_sha256"] != terminal["result_sha256"]
                    or next_lane["public_projection_sha256"]
                    != terminal["public_projection_sha256"]
                    or next_lane["predecessor_failure_code"] is not None
                    or next_lane["slot_version"] <= prior_lane["slot_version"]
                ):
                    raise GraphTerminalBindingError(
                        "parallel successor did not preserve a newly sealed lane"
                    )
                continue
            if (
                terminal.get("retryable") is not True
                or terminal["generation"] >= 2
                or next_lane["slot_state"] != "ADMITTED"
                or next_lane["action"] != "RUN_RETRY"
                or next_lane["generation"] != terminal["generation"] + 1
                or next_lane["frame_id"] == terminal["frame_id"]
                or next_lane["next_local_index"] != 0
                or next_lane["result_id"] is not None
                or next_lane["result_sha256"] is not None
                or next_lane["public_projection_sha256"] is not None
                or next_lane["predecessor_failure_code"] != terminal["error_code"]
                or next_lane["slot_version"] <= prior_lane["slot_version"]
            ):
                raise GraphTerminalBindingError(
                    "parallel successor did not advance a retryable failed lane"
                )
        return candidate

    @classmethod
    def create(
        cls,
        *,
        thread_id: str,
        command_id: str,
        request_hash: str,
        attempt_id: str,
        frame_set_id: str,
        receipt_sha256: str,
        authority_sha256: str,
        admission_receipt: Mapping[str, Any],
        canonical_events: Sequence[Mapping[str, Any]],
        terminal_error_code: str,
        terminal_retryable: bool,
        provider_call_count_before: int,
        provider_call_count_after: int,
        owner_id: str,
        fencing_token: int,
    ) -> ParallelReceiptCycleRecord:
        cycle_id = cls.cycle_id_for_receipt(receipt_sha256)
        execution_id = ParallelReceiptExecutionRecord.execution_id_for_receipt(
            receipt_sha256,
            fencing_token,
        )
        values = {
            "schema_version": cls.SCHEMA_VERSION,
            "cycle_id": cycle_id,
            "execution_id": execution_id,
            "thread_id": thread_id,
            "command_id": command_id,
            "request_hash": request_hash,
            "attempt_id": attempt_id,
            "frame_set_id": frame_set_id,
            "receipt_sha256": receipt_sha256,
            "authority_sha256": authority_sha256,
            "admission_receipt": dict(admission_receipt),
            "events": [dict(event) for event in canonical_events],
            "terminal_error_code": terminal_error_code,
            "terminal_retryable": terminal_retryable,
            "provider_call_count_before": provider_call_count_before,
            "provider_call_count_after": provider_call_count_after,
            "owner_id": owner_id,
            "fencing_token": fencing_token,
        }
        return cls(
            cycle_id=cycle_id,
            execution_id=execution_id,
            thread_id=thread_id,
            command_id=command_id,
            request_hash=request_hash,
            attempt_id=attempt_id,
            frame_set_id=frame_set_id,
            receipt_sha256=receipt_sha256,
            authority_sha256=authority_sha256,
            admission_receipt=dict(admission_receipt),
            canonical_events=tuple(values["events"]),
            terminal_error_code=terminal_error_code,
            terminal_retryable=terminal_retryable,
            completion_sha256=canonical_sha256(values),
            provider_call_count_before=provider_call_count_before,
            provider_call_count_after=provider_call_count_after,
            owner_id=owner_id,
            fencing_token=fencing_token,
        )


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

TECHNICAL_COMPLETION_COLUMNS: Final[str] = """
completion_id, thread_id, command_id, request_hash, attempt_id, fencing_token,
completion_schema_version, completion_json, completion_hash
"""

LOAD_TECHNICAL_COMPLETION_SQL: Final[str] = f"""
select {TECHNICAL_COMPLETION_COLUMNS}
  from agent_graph_technical_completion
 where thread_id = %s and command_id = %s
"""

PARALLEL_RECEIPT_EXECUTION_COLUMNS: Final[str] = """
execution_id, thread_id, command_id, request_hash, attempt_id, frame_set_id,
receipt_sha256, authority_sha256, predecessor_cycle_id, predecessor_execution_id,
provider_call_count_at_admission, owner_id, fencing_token,
predecessor_abandonment_id
"""

LOAD_PARALLEL_RECEIPT_EXECUTION_SQL: Final[str] = f"""
select {PARALLEL_RECEIPT_EXECUTION_COLUMNS}
  from agent_graph_parallel_receipt_execution
 where thread_id = %s
   and command_id = %s
   and attempt_id = %s
   and receipt_sha256 = %s
 order by fencing_token desc
 limit 1
"""

INSERT_PARALLEL_RECEIPT_EXECUTION_SQL: Final[str] = f"""
insert into agent_graph_parallel_receipt_execution (
    execution_id, thread_id, command_id, request_hash, attempt_id, frame_set_id,
    receipt_sha256, authority_sha256, predecessor_cycle_id,
    predecessor_execution_id, provider_call_count_at_admission, owner_id,
    fencing_token, predecessor_abandonment_id
)
values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
on conflict (thread_id, command_id, attempt_id, receipt_sha256, fencing_token)
do nothing
returning {PARALLEL_RECEIPT_EXECUTION_COLUMNS}
"""

PARALLEL_RECEIPT_ABANDONMENT_COLUMNS: Final[str] = """
abandonment_id, execution_id, thread_id, command_id, request_hash, attempt_id,
frame_set_id, receipt_sha256, authority_sha256, admission_receipt_json,
provider_call_count_before, provider_call_count_after, owner_id, fencing_token,
abandoned_at, abandonment_sha256
"""

LOAD_PARALLEL_RECEIPT_ABANDONMENT_SQL: Final[str] = f"""
select {PARALLEL_RECEIPT_ABANDONMENT_COLUMNS}
  from agent_graph_parallel_receipt_abandonment
 where thread_id = %s
   and command_id = %s
   and attempt_id = %s
   and receipt_sha256 = %s
"""

LOAD_LATEST_PARALLEL_RECEIPT_ABANDONMENT_SQL: Final[str] = f"""
select {PARALLEL_RECEIPT_ABANDONMENT_COLUMNS}
  from agent_graph_parallel_receipt_abandonment
 where thread_id = %s
   and command_id = %s
   and attempt_id = %s
 order by fencing_token desc
 limit 1
"""

INSERT_PARALLEL_RECEIPT_ABANDONMENT_SQL: Final[str] = f"""
insert into agent_graph_parallel_receipt_abandonment (
    abandonment_id, execution_id, thread_id, command_id, request_hash,
    attempt_id, frame_set_id, receipt_sha256, authority_sha256,
    admission_receipt_json, provider_call_count_before,
    provider_call_count_after, owner_id, fencing_token, abandoned_at,
    abandonment_sha256
)
values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s, %s, %s, %s)
on conflict (execution_id) do nothing
returning {PARALLEL_RECEIPT_ABANDONMENT_COLUMNS}
"""

PARALLEL_RECEIPT_CYCLE_COLUMNS: Final[str] = """
cycle_id, execution_id, thread_id, command_id, request_hash, attempt_id, frame_set_id,
receipt_sha256, authority_sha256, admission_receipt_json, canonical_events_json,
terminal_error_code, terminal_retryable, completion_sha256,
provider_call_count_before, provider_call_count_after, owner_id, fencing_token
"""

LOAD_PARALLEL_RECEIPT_CYCLE_SQL: Final[str] = f"""
select {PARALLEL_RECEIPT_CYCLE_COLUMNS}
  from agent_graph_parallel_receipt_cycle
 where thread_id = %s
   and command_id = %s
   and attempt_id = %s
   and receipt_sha256 = %s
"""

LOAD_LATEST_PARALLEL_RECEIPT_CYCLE_SQL: Final[str] = f"""
select {PARALLEL_RECEIPT_CYCLE_COLUMNS}
  from agent_graph_parallel_receipt_cycle
 where thread_id = %s
   and command_id = %s
   and attempt_id = %s
 order by fencing_token desc
 limit 1
"""

INSERT_PARALLEL_RECEIPT_CYCLE_SQL: Final[str] = f"""
insert into agent_graph_parallel_receipt_cycle (
    cycle_id, execution_id, thread_id, command_id, request_hash, attempt_id,
    frame_set_id, receipt_sha256, authority_sha256, admission_receipt_json,
    canonical_events_json, terminal_error_code, terminal_retryable,
    completion_sha256, provider_call_count_before, provider_call_count_after,
    owner_id, fencing_token
)
select %s, %s, %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb,
       %s, %s, %s, %s, %s, %s, %s
 where exists (
       select 1
         from agent_graph_command command
         join agent_graph_command_attempt attempt
           on attempt.thread_id = command.thread_id
          and attempt.command_id = command.command_id
         join agent_graph_lease lease
           on lease.thread_id = command.thread_id
          and lease.command_id = command.command_id
        where command.thread_id = %s
          and command.command_id = %s
          and command.request_hash = %s
          and command.status = 'EXECUTING'
          and command.fencing_token = %s
          and attempt.attempt_id = %s
          and attempt.attempt_status = 'EXECUTING'
          and attempt.owner_id = %s
          and attempt.fencing_token = %s
          and lease.owner_id = attempt.owner_id
          and lease.fencing_token = attempt.fencing_token
          and lease.released_at is null
          and lease.cancelled_at is null
          and lease.lease_expires_at > clock_timestamp()
   )
on conflict (thread_id, command_id, attempt_id, receipt_sha256) do nothing
returning {PARALLEL_RECEIPT_CYCLE_COLUMNS}
"""

REBIND_PARALLEL_COMMAND_FENCE_SQL: Final[str] = f"""
update agent_graph_command
   set fencing_token = %s,
       updated_at = clock_timestamp(),
       command_revision = command_revision + 1
 where thread_id = %s
   and command_id = %s
   and request_hash = %s
   and status = 'EXECUTING'
   and fencing_token = %s
returning {COMMAND_COLUMNS}
"""

REBIND_PARALLEL_ATTEMPT_FENCE_SQL: Final[str] = """
update agent_graph_command_attempt attempt
   set owner_id = %s,
       fencing_token = %s,
       last_heartbeat_at = clock_timestamp()
 where attempt.attempt_id = %s
   and attempt.thread_id = %s
   and attempt.command_id = %s
   and attempt.owner_id = %s
   and attempt.fencing_token = %s
   and attempt.attempt_status = 'EXECUTING'
   and exists (
       select 1
         from agent_graph_parallel_receipt_execution execution
         left join agent_graph_parallel_receipt_cycle cycle
           on cycle.cycle_id = execution.predecessor_cycle_id
         left join agent_graph_parallel_receipt_abandonment abandonment
           on abandonment.abandonment_id = execution.predecessor_abandonment_id
         left join agent_graph_parallel_receipt_execution predecessor
           on predecessor.execution_id = coalesce(
               execution.predecessor_execution_id,
               abandonment.execution_id
           )
        where execution.execution_id = %s
          and execution.attempt_id = attempt.attempt_id
          and execution.thread_id = attempt.thread_id
          and execution.command_id = attempt.command_id
          and execution.owner_id = %s
          and execution.fencing_token = %s
          and (
              (
                  execution.predecessor_cycle_id is not null
                  and execution.predecessor_execution_id is null
                  and cycle.owner_id = attempt.owner_id
                  and cycle.fencing_token = attempt.fencing_token
                  and cycle.provider_call_count_after = attempt.provider_call_count
              )
              or (
                  execution.predecessor_cycle_id is null
                  and execution.predecessor_execution_id is not null
                  and execution.predecessor_abandonment_id is null
                  and predecessor.attempt_id = attempt.attempt_id
                  and predecessor.thread_id = attempt.thread_id
                  and predecessor.command_id = attempt.command_id
                  and predecessor.receipt_sha256 = execution.receipt_sha256
                  and predecessor.owner_id = attempt.owner_id
                  and predecessor.fencing_token = attempt.fencing_token
                  and predecessor.provider_call_count_at_admission
                      = attempt.provider_call_count
                  and not exists (
                      select 1
                        from agent_graph_parallel_receipt_cycle receipt_cycle
                       where receipt_cycle.attempt_id = attempt.attempt_id
                         and receipt_cycle.receipt_sha256 = execution.receipt_sha256
                  )
              )
              or (
                  execution.predecessor_cycle_id is null
                  and execution.predecessor_execution_id is null
                  and execution.predecessor_abandonment_id is not null
                  and predecessor.attempt_id = attempt.attempt_id
                  and predecessor.thread_id = attempt.thread_id
                  and predecessor.command_id = attempt.command_id
                  and predecessor.receipt_sha256 <> execution.receipt_sha256
                  and predecessor.authority_sha256 = execution.authority_sha256
                  and predecessor.owner_id = attempt.owner_id
                  and predecessor.fencing_token = attempt.fencing_token
                  and abandonment.execution_id = predecessor.execution_id
                  and abandonment.attempt_id = attempt.attempt_id
                  and abandonment.thread_id = attempt.thread_id
                  and abandonment.command_id = attempt.command_id
                  and abandonment.receipt_sha256 = predecessor.receipt_sha256
                  and abandonment.authority_sha256 = predecessor.authority_sha256
                  and abandonment.owner_id = attempt.owner_id
                  and abandonment.fencing_token = attempt.fencing_token
                  and abandonment.provider_call_count_before
                      = predecessor.provider_call_count_at_admission
                  and abandonment.provider_call_count_after
                      = attempt.provider_call_count
                  and not exists (
                      select 1
                        from agent_graph_parallel_receipt_cycle receipt_cycle
                       where receipt_cycle.execution_id = predecessor.execution_id
                  )
              )
          )
   )
returning attempt_id, thread_id, command_id, attempt_no, owner_id,
          fencing_token, attempt_status, provider_call_count,
          error_code, error_classification
"""

INSERT_TECHNICAL_COMPLETION_SQL: Final[str] = f"""
insert into agent_graph_technical_completion (
    completion_id, thread_id, command_id, request_hash, attempt_id, fencing_token,
    completion_schema_version, completion_json, completion_hash
)
select %s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s
 where exists (
       select 1
         from agent_graph_command command
         join agent_graph_command_attempt attempt
           on attempt.thread_id = command.thread_id
          and attempt.command_id = command.command_id
         join agent_graph_lease lease
           on lease.thread_id = command.thread_id
          and lease.command_id = command.command_id
        where command.thread_id = %s
          and command.command_id = %s
          and command.request_hash = %s
          and command.status = 'EXECUTING'
          and command.fencing_token = %s
          and attempt.attempt_id = %s
          and attempt.owner_id = %s
          and attempt.fencing_token = %s
          and attempt.attempt_status = 'EXECUTING'
          and lease.owner_id = attempt.owner_id
          and lease.fencing_token = attempt.fencing_token
          and lease.released_at is null
          and lease.cancelled_at is null
          and lease.lease_expires_at > clock_timestamp()
   )
on conflict (thread_id, command_id) do nothing
returning {TECHNICAL_COMPLETION_COLUMNS}
"""

COMPLETE_TECHNICAL_ATTEMPT_SQL: Final[str] = """
update agent_graph_command_attempt
   set attempt_status = 'COMPLETED', completed_at = clock_timestamp(),
       last_heartbeat_at = clock_timestamp()
 where attempt_id = %s and thread_id = %s and command_id = %s
   and owner_id = %s and fencing_token = %s
   and attempt_status = 'EXECUTING'
returning attempt_id, thread_id, command_id, attempt_no, owner_id,
          fencing_token, attempt_status, provider_call_count,
          error_code, error_classification
"""

COMPLETE_TECHNICAL_COMMAND_SQL: Final[str] = f"""
update agent_graph_command
   set status = 'TECHNICAL_COMPLETED', technical_completed_at = clock_timestamp(),
       updated_at = clock_timestamp(), command_revision = command_revision + 1
 where thread_id = %s and command_id = %s and request_hash = %s
   and status = 'EXECUTING' and fencing_token = %s
   and committed_checkpoint_ns is null and committed_checkpoint_id is null
   and result_ref is null and result_hash is null
   and exists (
       select 1 from agent_graph_technical_completion completion
        where completion.thread_id = agent_graph_command.thread_id
          and completion.command_id = agent_graph_command.command_id
          and completion.request_hash = agent_graph_command.request_hash
          and completion.attempt_id = %s
          and completion.fencing_token = %s
          and completion.completion_hash = %s
   )
returning {COMMAND_COLUMNS}
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

    async def load_technical_completion(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
    ) -> TechnicalCompletionRecord:
        row = await (
            await connection.execute(
                LOAD_TECHNICAL_COMPLETION_SQL,
                (thread_id, command_id),
            )
        ).fetchone()
        if row is None:
            raise GraphTerminalBindingError("technical completion row is missing")
        return self._technical_completion_from_row(row)

    async def load_parallel_receipt_cycle(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        attempt_id: str,
        receipt_sha256: str,
    ) -> ParallelReceiptCycleRecord | None:
        row = await (
            await connection.execute(
                LOAD_PARALLEL_RECEIPT_CYCLE_SQL,
                (thread_id, command_id, attempt_id, receipt_sha256),
            )
        ).fetchone()
        return None if row is None else self._parallel_receipt_cycle_from_row(row)

    async def load_parallel_receipt_execution(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        attempt_id: str,
        receipt_sha256: str,
    ) -> ParallelReceiptExecutionRecord | None:
        row = await (
            await connection.execute(
                LOAD_PARALLEL_RECEIPT_EXECUTION_SQL,
                (thread_id, command_id, attempt_id, receipt_sha256),
            )
        ).fetchone()
        return None if row is None else self._parallel_receipt_execution_from_row(row)

    async def load_parallel_receipt_abandonment(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        attempt_id: str,
        receipt_sha256: str,
    ) -> ParallelReceiptAbandonmentRecord | None:
        row = await (
            await connection.execute(
                LOAD_PARALLEL_RECEIPT_ABANDONMENT_SQL,
                (thread_id, command_id, attempt_id, receipt_sha256),
            )
        ).fetchone()
        return (
            None
            if row is None
            else self._parallel_receipt_abandonment_from_row(row)
        )

    async def load_latest_parallel_receipt_abandonment(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        attempt_id: str,
    ) -> ParallelReceiptAbandonmentRecord | None:
        row = await (
            await connection.execute(
                LOAD_LATEST_PARALLEL_RECEIPT_ABANDONMENT_SQL,
                (thread_id, command_id, attempt_id),
            )
        ).fetchone()
        return (
            None
            if row is None
            else self._parallel_receipt_abandonment_from_row(row)
        )

    async def store_parallel_receipt_execution(
        self,
        connection: Any,
        *,
        execution_attempt: AttemptRecord,
        receipt_execution: ParallelReceiptExecutionRecord,
    ) -> ParallelReceiptExecutionRecord:
        if (
            receipt_execution.thread_id != execution_attempt.thread_id
            or receipt_execution.command_id != execution_attempt.command_id
            or receipt_execution.attempt_id != execution_attempt.attempt_id
            or receipt_execution.owner_id != execution_attempt.owner_id
            or receipt_execution.fencing_token != execution_attempt.fencing_token
            or execution_attempt.status is not AttemptStatus.EXECUTING
        ):
            raise GraphTerminalBindingError(
                "parallel receipt execution differs from its Graph attempt"
            )
        row = await (
            await connection.execute(
                INSERT_PARALLEL_RECEIPT_EXECUTION_SQL,
                (
                    receipt_execution.execution_id,
                    receipt_execution.thread_id,
                    receipt_execution.command_id,
                    receipt_execution.request_hash,
                    receipt_execution.attempt_id,
                    receipt_execution.frame_set_id,
                    receipt_execution.receipt_sha256,
                    receipt_execution.authority_sha256,
                    receipt_execution.predecessor_cycle_id,
                    receipt_execution.predecessor_execution_id,
                    receipt_execution.provider_call_count_at_admission,
                    receipt_execution.owner_id,
                    receipt_execution.fencing_token,
                    receipt_execution.predecessor_abandonment_id,
                ),
            )
        ).fetchone()
        if row is not None:
            return self._parallel_receipt_execution_from_row(row)
        existing = await self.load_parallel_receipt_execution(
            connection,
            thread_id=receipt_execution.thread_id,
            command_id=receipt_execution.command_id,
            attempt_id=receipt_execution.attempt_id,
            receipt_sha256=receipt_execution.receipt_sha256,
        )
        if existing != receipt_execution:
            raise GraphTerminalBindingError(
                "immutable parallel receipt execution conflicts with existing row"
            )
        return existing

    async def store_parallel_receipt_abandonment(
        self,
        connection: Any,
        *,
        execution_attempt: AttemptRecord,
        abandonment: ParallelReceiptAbandonmentRecord,
    ) -> ParallelReceiptAbandonmentRecord:
        abandonment.canonical_document()
        if (
            abandonment.thread_id != execution_attempt.thread_id
            or abandonment.command_id != execution_attempt.command_id
            or abandonment.attempt_id != execution_attempt.attempt_id
            or abandonment.owner_id != execution_attempt.owner_id
            or abandonment.fencing_token != execution_attempt.fencing_token
            or abandonment.provider_call_count_after
            != execution_attempt.provider_call_count
            or execution_attempt.status is not AttemptStatus.EXECUTING
        ):
            raise GraphTerminalBindingError(
                "parallel receipt abandonment differs from its executing attempt"
            )
        row = await (
            await connection.execute(
                INSERT_PARALLEL_RECEIPT_ABANDONMENT_SQL,
                (
                    abandonment.abandonment_id,
                    abandonment.execution_id,
                    abandonment.thread_id,
                    abandonment.command_id,
                    abandonment.request_hash,
                    abandonment.attempt_id,
                    abandonment.frame_set_id,
                    abandonment.receipt_sha256,
                    abandonment.authority_sha256,
                    abandonment.canonical_admission_receipt_json_text(),
                    abandonment.provider_call_count_before,
                    abandonment.provider_call_count_after,
                    abandonment.owner_id,
                    abandonment.fencing_token,
                    abandonment.abandoned_at,
                    abandonment.abandonment_sha256,
                ),
            )
        ).fetchone()
        if row is not None:
            return self._parallel_receipt_abandonment_from_row(row)
        existing = await self.load_parallel_receipt_abandonment(
            connection,
            thread_id=abandonment.thread_id,
            command_id=abandonment.command_id,
            attempt_id=abandonment.attempt_id,
            receipt_sha256=abandonment.receipt_sha256,
        )
        if existing != abandonment:
            raise GraphTerminalBindingError(
                "immutable parallel receipt abandonment conflicts with existing row"
            )
        return existing

    async def load_latest_parallel_receipt_cycle(
        self,
        connection: Any,
        *,
        thread_id: str,
        command_id: str,
        attempt_id: str,
    ) -> ParallelReceiptCycleRecord | None:
        row = await (
            await connection.execute(
                LOAD_LATEST_PARALLEL_RECEIPT_CYCLE_SQL,
                (thread_id, command_id, attempt_id),
            )
        ).fetchone()
        return None if row is None else self._parallel_receipt_cycle_from_row(row)

    async def store_parallel_receipt_cycle(
        self,
        connection: Any,
        *,
        execution_attempt: AttemptRecord,
        cycle: ParallelReceiptCycleRecord,
    ) -> ParallelReceiptCycleRecord:
        if (
            cycle.thread_id != execution_attempt.thread_id
            or cycle.command_id != execution_attempt.command_id
            or cycle.attempt_id != execution_attempt.attempt_id
            or cycle.owner_id != execution_attempt.owner_id
            or cycle.fencing_token != execution_attempt.fencing_token
            or cycle.provider_call_count_after
            != execution_attempt.provider_call_count
            or execution_attempt.status is not AttemptStatus.EXECUTING
        ):
            raise GraphTerminalBindingError(
                "parallel receipt cycle differs from its executing attempt"
            )
        row = await (
            await connection.execute(
                INSERT_PARALLEL_RECEIPT_CYCLE_SQL,
                (
                    cycle.cycle_id,
                    cycle.execution_id,
                    cycle.thread_id,
                    cycle.command_id,
                    cycle.request_hash,
                    cycle.attempt_id,
                    cycle.frame_set_id,
                    cycle.receipt_sha256,
                    cycle.authority_sha256,
                    cycle.canonical_admission_receipt_json_text(),
                    cycle.canonical_events_json_text(),
                    cycle.terminal_error_code,
                    cycle.terminal_retryable,
                    cycle.completion_sha256,
                    cycle.provider_call_count_before,
                    cycle.provider_call_count_after,
                    cycle.owner_id,
                    cycle.fencing_token,
                    cycle.thread_id,
                    cycle.command_id,
                    cycle.request_hash,
                    cycle.fencing_token,
                    cycle.attempt_id,
                    cycle.owner_id,
                    cycle.fencing_token,
                ),
            )
        ).fetchone()
        if row is not None:
            return self._parallel_receipt_cycle_from_row(row)
        existing = await self.load_parallel_receipt_cycle(
            connection,
            thread_id=cycle.thread_id,
            command_id=cycle.command_id,
            attempt_id=cycle.attempt_id,
            receipt_sha256=cycle.receipt_sha256,
        )
        if existing != cycle:
            raise GraphTerminalBindingError(
                "immutable parallel receipt cycle conflicts with existing row"
            )
        return existing

    async def rebind_parallel_attempt(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        attempt: AttemptRecord,
        prior_cycle: ParallelReceiptCycleRecord | None,
        prior_execution: ParallelReceiptExecutionRecord | None,
        prior_abandonment: ParallelReceiptAbandonmentRecord | None,
        receipt_execution: ParallelReceiptExecutionRecord,
        next_owner_id: str,
        next_fencing_token: int,
    ) -> tuple[CommandRecord, AttemptRecord]:
        _identifier(next_owner_id, "next_owner_id")
        if (prior_cycle is None) == (prior_execution is None):
            raise GraphTerminalBindingError(
                "parallel attempt handoff requires one exact predecessor"
            )
        if prior_abandonment is not None and prior_execution is None:
            raise GraphTerminalBindingError(
                "parallel attempt abandonment requires its exact execution"
            )
        predecessor_frame_set_id = (
            prior_cycle.frame_set_id
            if prior_cycle is not None
            else prior_execution.frame_set_id
        )
        predecessor_authority_sha256 = (
            prior_cycle.authority_sha256
            if prior_cycle is not None
            else prior_execution.authority_sha256
        )
        common_invalid = (
            receipt_execution.thread_id != binding.thread_id
            or receipt_execution.command_id != binding.command_id
            or receipt_execution.request_hash != binding.request_hash
            or receipt_execution.attempt_id != attempt.attempt_id
            or receipt_execution.frame_set_id != predecessor_frame_set_id
            or receipt_execution.authority_sha256
            != predecessor_authority_sha256
            or receipt_execution.owner_id != next_owner_id
            or receipt_execution.fencing_token != next_fencing_token
            or attempt.status is not AttemptStatus.EXECUTING
            or next_fencing_token != attempt.fencing_token + 1
        )
        cycle_invalid = prior_cycle is not None and (
            prior_cycle.thread_id != binding.thread_id
            or prior_cycle.command_id != binding.command_id
            or prior_cycle.request_hash != binding.request_hash
            or prior_cycle.attempt_id != attempt.attempt_id
            or prior_cycle.owner_id != attempt.owner_id
            or prior_cycle.fencing_token != attempt.fencing_token
            or prior_cycle.provider_call_count_after != attempt.provider_call_count
            or receipt_execution.predecessor_cycle_id != prior_cycle.cycle_id
            or receipt_execution.predecessor_execution_id is not None
            or receipt_execution.predecessor_abandonment_id is not None
            or receipt_execution.provider_call_count_at_admission
            != attempt.provider_call_count
        )
        execution_common_invalid = prior_execution is not None and (
            prior_execution.thread_id != binding.thread_id
            or prior_execution.command_id != binding.command_id
            or prior_execution.request_hash != binding.request_hash
            or prior_execution.attempt_id != attempt.attempt_id
            or prior_execution.frame_set_id != receipt_execution.frame_set_id
            or prior_execution.authority_sha256 != receipt_execution.authority_sha256
            or prior_execution.owner_id != attempt.owner_id
            or prior_execution.fencing_token != attempt.fencing_token
            or receipt_execution.predecessor_cycle_id is not None
            or receipt_execution.provider_call_count_at_admission
            != attempt.provider_call_count
        )
        same_receipt_lineage = prior_execution is not None and (
            prior_abandonment is None
            and prior_execution.receipt_sha256 == receipt_execution.receipt_sha256
            and prior_execution.provider_call_count_at_admission
            == attempt.provider_call_count
            and receipt_execution.predecessor_execution_id
            == prior_execution.execution_id
            and receipt_execution.predecessor_abandonment_id is None
        )
        abandonment_lineage = (
            prior_execution is not None
            and prior_abandonment is not None
            and prior_execution.receipt_sha256 != receipt_execution.receipt_sha256
            and prior_abandonment.execution_id == prior_execution.execution_id
            and prior_abandonment.thread_id == binding.thread_id
            and prior_abandonment.command_id == binding.command_id
            and prior_abandonment.request_hash == binding.request_hash
            and prior_abandonment.attempt_id == attempt.attempt_id
            and prior_abandonment.frame_set_id == prior_execution.frame_set_id
            and prior_abandonment.receipt_sha256 == prior_execution.receipt_sha256
            and prior_abandonment.authority_sha256
            == prior_execution.authority_sha256
            and prior_abandonment.owner_id == attempt.owner_id
            and prior_abandonment.fencing_token == attempt.fencing_token
            and prior_abandonment.provider_call_count_before
            == prior_execution.provider_call_count_at_admission
            and prior_abandonment.provider_call_count_after
            == attempt.provider_call_count
            and receipt_execution.predecessor_execution_id is None
            and receipt_execution.predecessor_abandonment_id
            == prior_abandonment.abandonment_id
        )
        execution_invalid = prior_execution is not None and (
            execution_common_invalid
            or not (same_receipt_lineage or abandonment_lineage)
        )
        if common_invalid or cycle_invalid or execution_invalid:
            raise GraphTerminalBindingError(
                "parallel attempt handoff lacks exact prior receipt authority"
            )
        command_row = await (
            await connection.execute(
                REBIND_PARALLEL_COMMAND_FENCE_SQL,
                (
                    next_fencing_token,
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    attempt.fencing_token,
                ),
            )
        ).fetchone()
        if command_row is None:
            raise GraphCommandStateError("parallel command fence handoff failed")
        command = self._command_from_row(command_row)
        self.require_same_binding(command.binding, binding)
        stored_execution = await self.store_parallel_receipt_execution(
            connection,
            execution_attempt=replace(
                attempt,
                owner_id=next_owner_id,
                fencing_token=next_fencing_token,
            ),
            receipt_execution=receipt_execution,
        )
        if stored_execution != receipt_execution:
            raise GraphTerminalBindingError(
                "parallel receipt execution persistence drifted"
            )
        attempt_row = await (
            await connection.execute(
                REBIND_PARALLEL_ATTEMPT_FENCE_SQL,
                (
                    next_owner_id,
                    next_fencing_token,
                    attempt.attempt_id,
                    attempt.thread_id,
                    attempt.command_id,
                    attempt.owner_id,
                    attempt.fencing_token,
                    receipt_execution.execution_id,
                    next_owner_id,
                    next_fencing_token,
                ),
            )
        ).fetchone()
        if attempt_row is None:
            raise GraphCommandStateError("parallel attempt fence handoff failed")
        rebound = self._attempt_from_row(attempt_row)
        if (
            rebound.attempt_id != attempt.attempt_id
            or rebound.attempt_no != attempt.attempt_no
            or rebound.provider_call_count != attempt.provider_call_count
            or rebound.owner_id != next_owner_id
            or rebound.fencing_token != next_fencing_token
            or rebound.status is not AttemptStatus.EXECUTING
        ):
            raise GraphTerminalBindingError("parallel attempt handoff drifted")
        return command, rebound

    async def store_technical_completion(
        self,
        connection: Any,
        *,
        execution_attempt: AttemptRecord,
        completion: TechnicalCompletionRecord,
    ) -> TechnicalCompletionRecord:
        if (
            completion.thread_id != execution_attempt.thread_id
            or completion.command_id != execution_attempt.command_id
            or completion.attempt_id != execution_attempt.attempt_id
            or completion.fencing_token != execution_attempt.fencing_token
            or execution_attempt.status is not AttemptStatus.EXECUTING
        ):
            raise GraphTerminalBindingError(
                "technical completion differs from its executing attempt"
            )
        row = await (
            await connection.execute(
                INSERT_TECHNICAL_COMPLETION_SQL,
                (
                    completion.completion_id,
                    completion.thread_id,
                    completion.command_id,
                    completion.request_hash,
                    completion.attempt_id,
                    completion.fencing_token,
                    completion.completion_schema_version,
                    completion.canonical_json_text(),
                    completion.completion_hash,
                    completion.thread_id,
                    completion.command_id,
                    completion.request_hash,
                    completion.fencing_token,
                    completion.attempt_id,
                    execution_attempt.owner_id,
                    completion.fencing_token,
                ),
            )
        ).fetchone()
        if row is not None:
            return self._technical_completion_from_row(row)
        existing = await self.load_technical_completion(
            connection,
            thread_id=completion.thread_id,
            command_id=completion.command_id,
        )
        if existing != completion:
            raise GraphTerminalBindingError(
                "immutable technical completion conflicts with existing row"
            )
        return existing

    async def complete_technical_attempt(
        self,
        connection: Any,
        attempt: AttemptRecord,
    ) -> AttemptRecord:
        row = await (
            await connection.execute(
                COMPLETE_TECHNICAL_ATTEMPT_SQL,
                (
                    attempt.attempt_id,
                    attempt.thread_id,
                    attempt.command_id,
                    attempt.owner_id,
                    attempt.fencing_token,
                ),
            )
        ).fetchone()
        if row is not None:
            return self._attempt_from_row(row)
        existing = await self.latest_attempt(
            connection,
            thread_id=attempt.thread_id,
            command_id=attempt.command_id,
        )
        if existing != replace(attempt, status=AttemptStatus.COMPLETED):
            raise GraphTerminalBindingError("technical attempt cannot be completed")
        return existing

    async def complete_technical_command(
        self,
        connection: Any,
        *,
        binding: CommandBinding,
        fencing_token: int,
        completion: TechnicalCompletionRecord,
    ) -> CommandRecord:
        if (
            completion.thread_id != binding.thread_id
            or completion.command_id != binding.command_id
            or completion.request_hash != binding.request_hash
            or completion.fencing_token != fencing_token
        ):
            raise GraphTerminalBindingError(
                "technical completion differs from its command binding"
            )
        row = await (
            await connection.execute(
                COMPLETE_TECHNICAL_COMMAND_SQL,
                (
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    fencing_token,
                    completion.attempt_id,
                    fencing_token,
                    completion.completion_hash,
                ),
            )
        ).fetchone()
        if row is not None:
            return self._command_from_row(row)
        existing = await self.load(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        self.require_same_binding(existing.binding, binding)
        if existing.status is not CommandStatus.TECHNICAL_COMPLETED:
            raise GraphTerminalBindingError("technical command cannot be completed")
        durable = await self.load_technical_completion(
            connection,
            thread_id=binding.thread_id,
            command_id=binding.command_id,
        )
        if durable != completion:
            raise GraphTerminalBindingError("technical completion replay drifted")
        return existing

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
    def _technical_completion_from_row(
        row: Mapping[str, Any],
    ) -> TechnicalCompletionRecord:
        try:
            return TechnicalCompletionRecord(
                completion_id=row["completion_id"],
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                request_hash=row["request_hash"],
                attempt_id=row["attempt_id"],
                fencing_token=row["fencing_token"],
                completion_schema_version=row["completion_schema_version"],
                completion_json=row["completion_json"],
                completion_hash=row["completion_hash"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted technical completion binding is invalid"
            ) from error

    @staticmethod
    def _parallel_receipt_cycle_from_row(
        row: Mapping[str, Any],
    ) -> ParallelReceiptCycleRecord:
        try:
            raw_events = row["canonical_events_json"]
            if (
                not isinstance(raw_events, Sequence)
                or isinstance(raw_events, (str, bytes, bytearray))
            ):
                raise TypeError("canonical receipt events are not an array")
            return ParallelReceiptCycleRecord(
                cycle_id=row["cycle_id"],
                execution_id=row["execution_id"],
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                request_hash=row["request_hash"],
                attempt_id=row["attempt_id"],
                frame_set_id=row["frame_set_id"],
                receipt_sha256=row["receipt_sha256"],
                authority_sha256=row["authority_sha256"],
                admission_receipt=row["admission_receipt_json"],
                canonical_events=tuple(raw_events),
                terminal_error_code=row["terminal_error_code"],
                terminal_retryable=row["terminal_retryable"],
                completion_sha256=row["completion_sha256"],
                provider_call_count_before=row["provider_call_count_before"],
                provider_call_count_after=row["provider_call_count_after"],
                owner_id=row["owner_id"],
                fencing_token=row["fencing_token"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted parallel receipt cycle binding is invalid"
            ) from error

    @staticmethod
    def _parallel_receipt_execution_from_row(
        row: Mapping[str, Any],
    ) -> ParallelReceiptExecutionRecord:
        try:
            return ParallelReceiptExecutionRecord(
                execution_id=row["execution_id"],
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                request_hash=row["request_hash"],
                attempt_id=row["attempt_id"],
                frame_set_id=row["frame_set_id"],
                receipt_sha256=row["receipt_sha256"],
                authority_sha256=row["authority_sha256"],
                predecessor_cycle_id=row["predecessor_cycle_id"],
                predecessor_execution_id=row["predecessor_execution_id"],
                provider_call_count_at_admission=row[
                    "provider_call_count_at_admission"
                ],
                owner_id=row["owner_id"],
                fencing_token=row["fencing_token"],
                predecessor_abandonment_id=row["predecessor_abandonment_id"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted parallel receipt execution binding is invalid"
            ) from error

    @staticmethod
    def _parallel_receipt_abandonment_from_row(
        row: Mapping[str, Any],
    ) -> ParallelReceiptAbandonmentRecord:
        try:
            return ParallelReceiptAbandonmentRecord(
                abandonment_id=row["abandonment_id"],
                execution_id=row["execution_id"],
                thread_id=row["thread_id"],
                command_id=row["command_id"],
                request_hash=row["request_hash"],
                attempt_id=row["attempt_id"],
                frame_set_id=row["frame_set_id"],
                receipt_sha256=row["receipt_sha256"],
                authority_sha256=row["authority_sha256"],
                admission_receipt=row["admission_receipt_json"],
                provider_call_count_before=row["provider_call_count_before"],
                provider_call_count_after=row["provider_call_count_after"],
                owner_id=row["owner_id"],
                fencing_token=row["fencing_token"],
                abandoned_at=row["abandoned_at"],
                abandonment_sha256=row["abandonment_sha256"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphTerminalBindingError(
                "persisted parallel receipt abandonment binding is invalid"
            ) from error

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
