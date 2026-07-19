"""Opaque Graph thread identity and exact persisted binding validation."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum
import hmac
import json
import re
from typing import Any, Final

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.errors import (
    GraphContractError,
    GraphThreadBindingError,
    GraphThreadNotFoundError,
)


THREAD_ID_PATTERN: Final = re.compile(r"^grt\.v1\.[0-9a-f]{32}$")
IDENTIFIER_PATTERN: Final = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
SHA256_PATTERN: Final = re.compile(r"^[0-9a-f]{64}$")


class RoomType(StrEnum):
    INTAKE = "INTAKE"
    EVIDENCE = "EVIDENCE"
    HEARING = "HEARING"
    REVIEW = "REVIEW"


class ActorRole(StrEnum):
    USER = "USER"
    MERCHANT = "MERCHANT"
    PLATFORM_REVIEWER = "PLATFORM_REVIEWER"
    ADMIN = "ADMIN"
    SYSTEM = "SYSTEM"


class Audience(StrEnum):
    USER = "USER"
    MERCHANT = "MERCHANT"
    PLATFORM_REVIEWER = "PLATFORM_REVIEWER"
    SYSTEM = "SYSTEM"


class ThreadLifecycle(StrEnum):
    ACTIVE = "ACTIVE"
    RETIRED = "RETIRED"
    CANCELLED = "CANCELLED"


def _bounded(value: str, name: str, maximum: int = 128) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum or "\x00" in value:
        raise GraphContractError(f"{name} must be 1..{maximum} non-NUL characters")
    return value


def _identifier(value: str, name: str) -> str:
    _bounded(value, name)
    if IDENTIFIER_PATTERN.fullmatch(value) is None:
        raise GraphContractError(f"{name} is not a wire identifier")
    return value


def _sha256(value: str, name: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        raise GraphContractError(f"{name} must be a lowercase SHA-256")
    return value


@dataclass(frozen=True, slots=True)
class ActorScopeBinding:
    """Canonical actor scope whose ordered capability list is hash-bound."""

    actor_id: str
    actor_role: ActorRole
    audience: Audience
    capabilities: tuple[str, ...]

    def __post_init__(self) -> None:
        _identifier(self.actor_id, "actor_id")
        if not isinstance(self.actor_role, ActorRole):
            raise GraphContractError("actor_role is invalid")
        if not isinstance(self.audience, Audience):
            raise GraphContractError("audience is invalid")
        if len(self.capabilities) > 32 or len(set(self.capabilities)) != len(
            self.capabilities
        ):
            raise GraphContractError("capabilities must be unique and contain at most 32 items")
        for capability in self.capabilities:
            _identifier(capability, "capability")

    @classmethod
    def from_json(cls, value: Mapping[str, Any]) -> ActorScopeBinding:
        if set(value) != {"actor_id", "actor_role", "audience", "capabilities"}:
            raise GraphContractError("actor scope has missing or unknown members")
        capabilities = value["capabilities"]
        if isinstance(capabilities, (str, bytes)) or not isinstance(capabilities, Sequence):
            raise GraphContractError("actor scope capabilities must be an array")
        try:
            return cls(
                actor_id=value["actor_id"],
                actor_role=ActorRole(value["actor_role"]),
                audience=Audience(value["audience"]),
                capabilities=tuple(capabilities),
            )
        except (TypeError, ValueError) as error:
            raise GraphContractError("actor scope contains an invalid member") from error

    def to_json(self) -> dict[str, Any]:
        return {
            "actor_id": self.actor_id,
            "actor_role": self.actor_role.value,
            "audience": self.audience.value,
            "capabilities": list(self.capabilities),
        }

    @property
    def sha256(self) -> str:
        return canonical_sha256(self.to_json())


@dataclass(frozen=True, slots=True)
class ThreadIdentity:
    """Complete authorization binding for an opaque Java-issued thread ID."""

    thread_id: str
    tenant_surrogate: str
    case_id: str
    room_type: RoomType
    room_epoch: int
    actor_scope: ActorScopeBinding
    agent_session_id: str
    shared_session: bool
    graph_key: str
    graph_version: str
    checkpoint_schema_version: str

    def __post_init__(self) -> None:
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise GraphContractError("thread_id must be an opaque grt.v1 UUIDv7 wire ID")
        _identifier(self.tenant_surrogate, "tenant_surrogate")
        _bounded(self.case_id, "case_id", 64)
        if not isinstance(self.room_type, RoomType):
            raise GraphContractError("room_type is invalid")
        if isinstance(self.room_epoch, bool) or self.room_epoch < 0:
            raise GraphContractError("room_epoch must be non-negative")
        if not isinstance(self.actor_scope, ActorScopeBinding):
            raise GraphContractError("actor_scope must be canonical")
        _identifier(self.agent_session_id, "agent_session_id")
        _identifier(self.graph_key, "graph_key")
        _identifier(self.graph_version, "graph_version")
        _identifier(self.checkpoint_schema_version, "checkpoint_schema_version")
        self._validate_room_scope()

    def _validate_room_scope(self) -> None:
        role = self.actor_scope.actor_role
        audience = self.actor_scope.audience
        party_scope = (role is ActorRole.USER and audience is Audience.USER) or (
            role is ActorRole.MERCHANT and audience is Audience.MERCHANT
        )
        if self.shared_session:
            if (
                self.room_type is not RoomType.HEARING
                or role is not ActorRole.SYSTEM
                or audience is not Audience.SYSTEM
            ):
                raise GraphContractError(
                    "shared sessions require a SYSTEM-scoped Hearing thread"
                )
            return
        if self.room_type in {RoomType.INTAKE, RoomType.EVIDENCE, RoomType.HEARING}:
            if not party_scope:
                raise GraphContractError("private room threads require an exact party scope")
        elif self.room_type is RoomType.REVIEW and (
            role is not ActorRole.PLATFORM_REVIEWER
            or audience is not Audience.PLATFORM_REVIEWER
        ):
            raise GraphContractError("Review threads require a platform reviewer scope")

    @property
    def actor_scope_hash(self) -> str:
        return self.actor_scope.sha256

    @property
    def accepts_private_conversation(self) -> bool:
        return not self.shared_session

    def binding_values(self) -> tuple[Any, ...]:
        return (
            self.thread_id,
            self.tenant_surrogate,
            self.case_id,
            self.room_type.value,
            self.room_epoch,
            self.actor_scope.to_json(),
            self.actor_scope_hash,
            self.agent_session_id,
            self.shared_session,
            self.graph_key,
            self.graph_version,
            self.checkpoint_schema_version,
        )


@dataclass(frozen=True, slots=True)
class ThreadRecord:
    identity: ThreadIdentity
    lifecycle: ThreadLifecycle
    cognitive_revision: int
    last_checkpoint_ns: str | None
    last_checkpoint_id: str | None

    def __post_init__(self) -> None:
        if not isinstance(self.lifecycle, ThreadLifecycle):
            raise GraphContractError("thread lifecycle is invalid")
        if self.cognitive_revision < 0:
            raise GraphContractError("cognitive_revision must be non-negative")


LOAD_THREAD_SQL: Final[str] = """
select thread_id, tenant_surrogate, case_id, room_type, room_epoch,
       actor_scope_json, actor_scope_hash, agent_session_id, shared_session,
       graph_key, graph_version, checkpoint_schema_version, lifecycle_status,
       cognitive_revision, last_checkpoint_ns, last_checkpoint_id
  from graph_thread_registry
 where thread_id = %s
 for share
"""

INSERT_THREAD_SQL: Final[str] = """
insert into graph_thread_registry (
    thread_id, tenant_surrogate, case_id, room_type, room_epoch,
    actor_scope_json, actor_scope_hash, agent_session_id, shared_session,
    graph_key, graph_version, checkpoint_schema_version
)
values (%s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s, %s, %s, %s)
on conflict do nothing
returning thread_id
"""


class PostgresThreadIdentityRepository:
    """Loads the complete tuple; it never parses authority from ``thread_id``."""

    async def load(self, connection: Any, thread_id: str) -> ThreadRecord:
        if THREAD_ID_PATTERN.fullmatch(thread_id) is None:
            raise GraphContractError("thread_id must be an opaque grt.v1 UUIDv7 wire ID")
        row = await (await connection.execute(LOAD_THREAD_SQL, (thread_id,))).fetchone()
        if row is None:
            raise GraphThreadNotFoundError()
        return self._from_row(row)

    async def require_exact(
        self,
        connection: Any,
        expected: ThreadIdentity,
    ) -> ThreadRecord:
        actual = await self.require_binding(connection, expected)
        if actual.lifecycle is not ThreadLifecycle.ACTIVE:
            raise GraphThreadBindingError("GRAPH_THREAD_NOT_ACTIVE")
        return actual

    async def require_binding(
        self,
        connection: Any,
        expected: ThreadIdentity,
    ) -> ThreadRecord:
        """Compare the immutable tuple without granting execution authority."""

        actual = await self.load(connection, expected.thread_id)
        if actual.identity != expected:
            raise GraphThreadBindingError()
        return actual

    async def ensure_registered(
        self,
        connection: Any,
        expected: ThreadIdentity,
    ) -> ThreadRecord:
        """Create the Graph-side row once, then compare the complete immutable tuple."""

        values = list(expected.binding_values())
        values[5] = json.dumps(values[5], ensure_ascii=False, separators=(",", ":"))
        await (await connection.execute(INSERT_THREAD_SQL, tuple(values))).fetchone()
        try:
            return await self.require_binding(connection, expected)
        except GraphThreadNotFoundError as error:
            raise GraphThreadBindingError(
                "another opaque thread already owns the requested identity tuple"
            ) from error

    @staticmethod
    def _from_row(row: Mapping[str, Any]) -> ThreadRecord:
        scope_json = row["actor_scope_json"]
        if not isinstance(scope_json, Mapping):
            raise GraphThreadBindingError("persisted actor scope is not an object")
        scope = ActorScopeBinding.from_json(scope_json)
        persisted_hash = row["actor_scope_hash"]
        _sha256(persisted_hash, "actor_scope_hash")
        if not hmac.compare_digest(scope.sha256, persisted_hash):
            raise GraphThreadBindingError("persisted actor scope hash is inconsistent")
        try:
            identity = ThreadIdentity(
                thread_id=row["thread_id"],
                tenant_surrogate=row["tenant_surrogate"],
                case_id=row["case_id"],
                room_type=RoomType(row["room_type"]),
                room_epoch=row["room_epoch"],
                actor_scope=scope,
                agent_session_id=row["agent_session_id"],
                shared_session=row["shared_session"],
                graph_key=row["graph_key"],
                graph_version=row["graph_version"],
                checkpoint_schema_version=row["checkpoint_schema_version"],
            )
            return ThreadRecord(
                identity=identity,
                lifecycle=ThreadLifecycle(row["lifecycle_status"]),
                cognitive_revision=row["cognitive_revision"],
                last_checkpoint_ns=row["last_checkpoint_ns"],
                last_checkpoint_id=row["last_checkpoint_id"],
            )
        except (KeyError, TypeError, ValueError) as error:
            raise GraphThreadBindingError("persisted thread binding is invalid") from error
