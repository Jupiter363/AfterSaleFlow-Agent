"""Typed, dependency-free contracts for Graph PostgreSQL persistence."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
import re
from typing import Any, Mapping


LOWER_SHA256 = re.compile(r"^[0-9a-f]{64}$")
PRODUCTION_RUNTIME_ACTIVATION_ID = re.compile(r"^p9act\.v1\.[0-9a-f]{32}$")
THREAD_ID = re.compile(r"^grt\.v1\.[0-9a-f]{32}$")
SQL_IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]{0,62}$")


class GraphPersistenceError(RuntimeError):
    """Base error for fail-closed Graph persistence behavior."""


class GraphPersistenceConfigurationError(GraphPersistenceError, ValueError):
    """A static persistence setting violates the frozen contract."""


class GraphMigrationError(GraphPersistenceError):
    """The Graph migration ledger or DDL is incomplete or conflicting."""


class GraphFenceError(GraphPersistenceError):
    """The current execution no longer owns the durable thread lease."""


class GraphBindingError(GraphPersistenceError):
    """A checkpoint conflicts with its command or version binding."""


class GraphReadinessError(GraphPersistenceError):
    """Graph PostgreSQL cannot safely admit commands."""


class GraphGatewayMode(StrEnum):
    DISABLED = "DISABLED"
    SHADOW = "SHADOW"
    PRODUCTION = "PRODUCTION"


@dataclass(frozen=True, slots=True)
class GraphPoolConfig:
    """Bounded process-level pool settings.

    Production defaults are intentionally small. Admission control, not one connection per room,
    carries concurrency.
    """

    schema: str = "graph_runtime"
    min_size: int = 2
    max_size: int = 16
    max_waiting: int = 64
    acquire_timeout_seconds: float = 3.0
    connect_timeout_seconds: int = 3
    max_idle_seconds: float = 300.0
    max_lifetime_seconds: float = 1800.0
    reconnect_timeout_seconds: float = 60.0
    statement_timeout_ms: int = 5_000
    lock_timeout_ms: int = 2_000
    # Checkpoint writes can be cooperatively suspended while a graph model call runs.
    # Keep this above the default 120-second model deadline without disabling the guard.
    idle_in_transaction_timeout_ms: int = 150_000
    application_name: str = "python-agent-graph-runtime"

    def __post_init__(self) -> None:
        require_sql_identifier(self.schema, "schema")
        if self.min_size < 0 or self.max_size < 1 or self.min_size > self.max_size:
            raise GraphPersistenceConfigurationError(
                "pool sizes require 0 <= min_size <= max_size and max_size >= 1"
            )
        if self.max_waiting < self.max_size:
            raise GraphPersistenceConfigurationError(
                "max_waiting must be at least the maximum pool size"
            )
        if self.acquire_timeout_seconds <= 0 or self.connect_timeout_seconds <= 0:
            raise GraphPersistenceConfigurationError("pool timeouts must be positive")
        if self.max_idle_seconds <= 0 or self.max_lifetime_seconds <= 0:
            raise GraphPersistenceConfigurationError("pool lifetimes must be positive")
        if self.reconnect_timeout_seconds <= 0:
            raise GraphPersistenceConfigurationError("reconnect timeout must be positive")
        if (
            min(
                self.statement_timeout_ms,
                self.lock_timeout_ms,
                self.idle_in_transaction_timeout_ms,
            )
            <= 0
        ):
            raise GraphPersistenceConfigurationError("database timeouts must be positive")
        if not self.application_name or len(self.application_name) > 63:
            raise GraphPersistenceConfigurationError("application_name must be 1..63 characters")


@dataclass(frozen=True, slots=True)
class GraphFenceContext:
    """Trusted runtime-only identity attached after command/envelope validation."""

    thread_id: str
    command_id: str
    owner_id: str
    fencing_token: int
    request_hash: str
    room_epoch: int
    graph_key: str
    graph_version: str
    checkpoint_schema_version: str
    execution_lane: GraphGatewayMode = GraphGatewayMode.SHADOW
    activation_id: str | None = None
    room_fencing_token: int | None = None
    command_hash: str | None = None
    command_envelope_hash: str | None = None
    execution_provider: str | None = None
    execution_model: str | None = None
    environment_id: str | None = None
    environment_generation: int | None = None
    tenant_surrogate: str | None = None
    case_id: str | None = None
    room_type: str | None = None
    binding_hash: str | None = None
    code_build_id: str | None = None
    proposal_hash: str | None = None
    result_envelope_hash: str | None = None
    result_hash: str | None = None
    result_ref: str | None = None

    def __post_init__(self) -> None:
        if not THREAD_ID.fullmatch(self.thread_id):
            raise GraphPersistenceConfigurationError("thread_id is not a grt.v1 opaque ID")
        require_bounded_text(self.command_id, "command_id", 128)
        require_bounded_text(self.owner_id, "owner_id", 128)
        if self.fencing_token < 1:
            raise GraphPersistenceConfigurationError("fencing_token must start at one")
        require_sha256(self.request_hash, "request_hash")
        if self.room_epoch < 0:
            raise GraphPersistenceConfigurationError("room_epoch must be non-negative")
        require_bounded_text(self.graph_key, "graph_key", 128)
        require_bounded_text(self.graph_version, "graph_version", 128)
        require_bounded_text(self.checkpoint_schema_version, "checkpoint_schema_version", 128)
        if not isinstance(self.execution_lane, GraphGatewayMode) or self.execution_lane is (
            GraphGatewayMode.DISABLED
        ):
            raise GraphPersistenceConfigurationError("execution_lane must be an active Graph lane")
        candidate_values = (
            self.activation_id,
            self.command_envelope_hash,
            self.command_hash,
            self.environment_id,
            self.environment_generation,
            self.tenant_surrogate,
            self.case_id,
            self.room_type,
            self.binding_hash,
            self.code_build_id,
        )
        if self.execution_lane is GraphGatewayMode.PRODUCTION:
            if any(value is None for value in candidate_values):
                raise GraphPersistenceConfigurationError(
                    "candidate fence requires complete activation and authority bindings"
                )
            if self.activation_id is None or PRODUCTION_RUNTIME_ACTIVATION_ID.fullmatch(
                self.activation_id
            ) is None:
                raise GraphPersistenceConfigurationError("activation_id is invalid")
            require_sha256(self.binding_hash, "binding_hash")
            require_sha256(self.command_envelope_hash, "command_envelope_hash")
            require_sha256(self.command_hash, "command_hash")
            if (
                not isinstance(self.environment_generation, int)
                or isinstance(self.environment_generation, bool)
                or self.environment_generation < 1
            ):
                raise GraphPersistenceConfigurationError("environment_generation is invalid")
            if (
                not isinstance(self.room_fencing_token, int)
                or isinstance(self.room_fencing_token, bool)
                or self.room_fencing_token < 1
            ):
                raise GraphPersistenceConfigurationError("room_fencing_token is invalid")
            for name in (
                "environment_id",
                "tenant_surrogate",
                "case_id",
                "room_type",
                "code_build_id",
            ):
                require_bounded_text(getattr(self, name), name, 128)
        elif self.room_fencing_token is not None or any(
            value is not None for value in candidate_values
        ):
            raise GraphPersistenceConfigurationError(
                "SHADOW fence cannot carry candidate activation authority"
            )
        if (self.execution_provider is None) != (self.execution_model is None):
            raise GraphPersistenceConfigurationError(
                "execution provider and model must be bound together"
            )
        if self.execution_provider is not None:
            require_bounded_text(self.execution_provider, "execution_provider", 64)
            require_bounded_text(self.execution_model, "execution_model", 128)
        if self.result_hash is not None:
            require_sha256(self.result_hash, "result_hash")
            require_bounded_text(self.result_ref, "result_ref", 512)
            if self.execution_lane is GraphGatewayMode.PRODUCTION:
                require_sha256(self.proposal_hash, "proposal_hash")
                require_sha256(self.result_envelope_hash, "result_envelope_hash")
        elif self.result_ref is not None:
            raise GraphPersistenceConfigurationError(
                "result_ref cannot be present without result_hash"
            )
        elif self.proposal_hash is not None or self.result_envelope_hash is not None:
            raise GraphPersistenceConfigurationError(
                "proposal/result envelope hashes require a terminal result"
            )

    def checkpoint_metadata(self) -> dict[str, Any]:
        return {
            "graph_thread_id": self.thread_id,
            "graph_command_id": self.command_id,
            "graph_request_hash": self.request_hash,
            "graph_room_epoch": self.room_epoch,
            "graph_key": self.graph_key,
            "graph_version": self.graph_version,
            "graph_checkpoint_schema_version": self.checkpoint_schema_version,
            "graph_execution_lane": self.execution_lane.value,
            "graph_activation_id": self.activation_id,
            "graph_room_fencing_token": self.room_fencing_token,
            "graph_command_hash": self.command_hash,
            "graph_command_envelope_hash": self.command_envelope_hash,
            "graph_execution_provider": self.execution_provider,
            "graph_execution_model": self.execution_model,
            "graph_environment_id": self.environment_id,
            "graph_environment_generation": self.environment_generation,
            "graph_tenant_surrogate": self.tenant_surrogate,
            "graph_case_id": self.case_id,
            "graph_room_type": self.room_type,
            "graph_binding_hash": self.binding_hash,
            "graph_code_build_id": self.code_build_id,
            "graph_proposal_hash": self.proposal_hash,
            "graph_result_envelope_hash": self.result_envelope_hash,
            "graph_fencing_token": self.fencing_token,
            "graph_result_hash": self.result_hash,
            "graph_result_ref": self.result_ref,
        }


@dataclass(frozen=True, slots=True)
class GraphReadinessConfig:
    mode: GraphGatewayMode
    expected_database: str | None = None
    expected_user: str | None = "graph_runtime"
    expected_environment_generation: str | None = None
    expected_restore_verification_hash: str | None = None
    schema: str = "graph_runtime"
    timeout_seconds: float = 10.0

    def __post_init__(self) -> None:
        if not isinstance(self.mode, GraphGatewayMode):
            raise GraphPersistenceConfigurationError(
                "readiness mode must be DISABLED, SHADOW, or PRODUCTION"
            )
        if self.mode is GraphGatewayMode.DISABLED:
            return
        require_bounded_text(self.expected_database, "expected_database", 63)
        require_bounded_text(self.expected_user, "expected_user", 63)
        require_bounded_text(
            self.expected_environment_generation,
            "expected_environment_generation",
            64,
        )
        require_sha256(
            self.expected_restore_verification_hash,
            "expected_restore_verification_hash",
        )
        require_sql_identifier(self.schema, "schema")
        if self.timeout_seconds <= 0:
            raise GraphPersistenceConfigurationError("readiness timeout must be positive")


@dataclass(frozen=True, slots=True)
class GraphReadinessReport:
    ready: bool
    mode: GraphGatewayMode
    code: str
    checks: Mapping[str, bool] = field(default_factory=dict)

    @classmethod
    def disabled(cls) -> "GraphReadinessReport":
        return cls(
            ready=True,
            mode=GraphGatewayMode.DISABLED,
            code="GRAPH_DISABLED",
            checks={"pool_not_required": True},
        )


def require_sha256(value: str | None, field_name: str) -> str:
    if value is None or LOWER_SHA256.fullmatch(value) is None:
        raise GraphPersistenceConfigurationError(f"{field_name} must be a lowercase SHA-256")
    return value


def require_sql_identifier(value: str, field_name: str) -> str:
    if SQL_IDENTIFIER.fullmatch(value) is None:
        raise GraphPersistenceConfigurationError(
            f"{field_name} must be a safe lowercase SQL identifier"
        )
    return value


def require_bounded_text(value: str | None, field_name: str, maximum: int) -> str:
    if value is None or not value or len(value) > maximum or "\x00" in value:
        raise GraphPersistenceConfigurationError(
            f"{field_name} must be 1..{maximum} non-NUL characters"
        )
    return value
