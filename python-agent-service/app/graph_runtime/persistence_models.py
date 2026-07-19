"""Typed, dependency-free contracts for Graph PostgreSQL persistence."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
import re
from typing import Any, Mapping


LOWER_SHA256 = re.compile(r"^[0-9a-f]{64}$")
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
    idle_in_transaction_timeout_ms: int = 5_000
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
        if self.result_hash is not None:
            require_sha256(self.result_hash, "result_hash")
            require_bounded_text(self.result_ref, "result_ref", 512)
        elif self.result_ref is not None:
            raise GraphPersistenceConfigurationError(
                "result_ref cannot be present without result_hash"
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
    timeout_seconds: float = 2.0

    def __post_init__(self) -> None:
        if not isinstance(self.mode, GraphGatewayMode):
            raise GraphPersistenceConfigurationError("readiness mode must be DISABLED or SHADOW")
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
