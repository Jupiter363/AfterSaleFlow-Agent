"""Java-authoritative production-runtime lifecycle projection and recovery barrier.

This module intentionally has no activation or revocation decision API.  It can only
project a signed Java receipt onto an activation that is already registered in the
isolated Graph database.  Receipt records contain hashes and bounded identifiers only;
activation JWS bytes, command payloads, prompts, and credentials are never persisted.
"""

from __future__ import annotations

import asyncio
import base64
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
import hmac
import json
import os
from pathlib import Path
import re
import stat
import time
from typing import Any, Final, Literal, Protocol, Self

import jwt
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting
from app.graph_runtime.gateway import GatewayAdmission
from app.graph_runtime.identity import THREAD_ID_PATTERN
from app.graph_runtime.ledger import ResultRecord
from app.graph_runtime.persistence_models import GraphGatewayMode
from app.graph_runtime.production_runtime import ProductionRuntimeAuthority
from app.security.invocation_envelope import (
    ResolvedVerificationKey,
    TransportIdentity,
    VerificationKeyResolver,
)


PRODUCTION_RUNTIME_LIFECYCLE_PATH: Final[str] = (
    "/internal/graphs/production-runtime/activation/lifecycle"
)
PRODUCTION_RUNTIME_LIFECYCLE_JWT_TYPE: Final[str] = (
    "production-runtime-activation-lifecycle-receipt+jwt"
)
PRODUCTION_RUNTIME_EXECUTION_LANE: Final[str] = "PRODUCTION"
MAX_LIFECYCLE_JWS_BYTES: Final[int] = 16_384
MAX_LIFECYCLE_CREDENTIAL_SECONDS: Final[int] = 60
MAX_CHECKPOINT_BARRIER_MARKER_BYTES: Final[int] = 4_096
PRODUCTION_RUNTIME_CHECKPOINT_BARRIER_WINDOW: Final[str] = (
    "PYTHON_POST_CHECKPOINT_PRE_RESPONSE"
)

_ACTIVATION_ID = re.compile(r"^p9act\.v1\.[0-9a-f]{32}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_CERTIFICATE_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_JWS_HEADER_KEYS = frozenset({"alg", "kid", "typ"})


class ProductionLifecycleState(str, Enum):
    ACTIVE = "ACTIVE"
    DRAIN_ONLY = "DRAIN_ONLY"
    DRAINED = "DRAINED"
    REVOKED_TERMINAL = "REVOKED_TERMINAL"


_NEXT_STATE: Final[dict[ProductionLifecycleState, ProductionLifecycleState]] = {
    ProductionLifecycleState.ACTIVE: ProductionLifecycleState.DRAIN_ONLY,
    ProductionLifecycleState.DRAIN_ONLY: ProductionLifecycleState.DRAINED,
    ProductionLifecycleState.DRAINED: ProductionLifecycleState.REVOKED_TERMINAL,
}
_PREVIOUS_STATE: Final[dict[ProductionLifecycleState, ProductionLifecycleState]] = {
    target: source for source, target in _NEXT_STATE.items()
}
_TIMESTAMP_COLUMN: Final[dict[ProductionLifecycleState, str]] = {
    ProductionLifecycleState.DRAIN_ONLY: "drain_only_at",
    ProductionLifecycleState.DRAINED: "drained_at",
    ProductionLifecycleState.REVOKED_TERMINAL: "revoked_at",
}


class ProductionLifecycleError(RuntimeError):
    code = "PRODUCTION_RUNTIME_LIFECYCLE_REJECTED"

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.code)


class ProductionLifecycleAuthenticationError(ProductionLifecycleError):
    code = "PRODUCTION_RUNTIME_LIFECYCLE_AUTHENTICATION_REJECTED"


class ProductionLifecycleBindingError(ProductionLifecycleError):
    code = "PRODUCTION_RUNTIME_LIFECYCLE_BINDING_REJECTED"


class ProductionLifecycleTransitionError(ProductionLifecycleError):
    code = "PRODUCTION_RUNTIME_LIFECYCLE_TRANSITION_REJECTED"


class ProductionDrainIncompleteError(ProductionLifecycleError):
    code = "PRODUCTION_RUNTIME_DRAIN_INCOMPLETE"


class ProductionCheckpointBarrierError(RuntimeError):
    code = "PRODUCTION_RUNTIME_CHECKPOINT_RECOVERY_BARRIER_FAILED"

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.code)


class ProductionCheckpointNotDurableError(ProductionCheckpointBarrierError):
    code = "PRODUCTION_RUNTIME_CHECKPOINT_NOT_DURABLE"


@dataclass(frozen=True, slots=True)
class ProductionLifecycleBinding:
    """Exact non-secret deployment binding expected by this Graph replica."""

    activation_id: str
    environment_id: str
    environment_generation: int
    manifest_hash: str
    runtime_context_hash: str
    execution_lane: Literal["PRODUCTION"] = PRODUCTION_RUNTIME_EXECUTION_LANE

    def __post_init__(self) -> None:
        if _ACTIVATION_ID.fullmatch(self.activation_id) is None:
            raise ValueError("production-runtime lifecycle activation ID is invalid")
        if _IDENTIFIER.fullmatch(self.environment_id) is None:
            raise ValueError("production-runtime lifecycle environment ID is invalid")
        if (
            not isinstance(self.environment_generation, int)
            or isinstance(self.environment_generation, bool)
            or not 1 <= self.environment_generation <= 9_007_199_254_740_991
        ):
            raise ValueError("production-runtime lifecycle environment generation is invalid")
        if _SHA256.fullmatch(self.manifest_hash) is None:
            raise ValueError("production-runtime lifecycle manifest hash is invalid")
        if _SHA256.fullmatch(self.runtime_context_hash) is None:
            raise ValueError("production-runtime lifecycle runtime context hash is invalid")
        if self.execution_lane != PRODUCTION_RUNTIME_EXECUTION_LANE:
            raise ValueError("production-runtime lifecycle execution lane is invalid")


class ProductionLifecycleReceipt(BaseModel):
    """Deterministic record signed and issued by the Java control plane."""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True, populate_by_name=True)

    schema_version: Literal["production-runtime-activation-lifecycle-receipt.v1"] = Field(
        alias="schemaVersion"
    )
    execution_lane: Literal["PRODUCTION"] = Field(alias="executionLane")
    authority: Literal["JAVA_CONTROL_PLANE"]
    activation_id: str = Field(alias="activationId", pattern=_ACTIVATION_ID.pattern)
    environment_id: str = Field(alias="environmentId", pattern=_IDENTIFIER.pattern)
    environment_generation: int = Field(
        alias="environmentGeneration", ge=1, le=9_007_199_254_740_991
    )
    manifest_hash: str = Field(alias="manifestHash", pattern=_SHA256.pattern)
    runtime_context_hash: str = Field(alias="runtimeContextHash", pattern=_SHA256.pattern)
    from_state: ProductionLifecycleState = Field(alias="fromState", strict=False)
    to_state: ProductionLifecycleState = Field(alias="toState", strict=False)
    transitioned_at: datetime = Field(alias="transitionedAt", strict=False)
    receipt_hash: str = Field(alias="receiptHash", pattern=_SHA256.pattern)

    @model_validator(mode="after")
    def validate_transition_and_hash(self) -> Self:
        if self.transitioned_at.tzinfo is None:
            raise ValueError("production-runtime lifecycle transition time must be timezone-aware")
        if _NEXT_STATE.get(self.from_state) is not self.to_state:
            raise ValueError("production-runtime lifecycle receipt skips or regresses state")
        document = self.model_dump(mode="json", by_alias=True)
        if not hmac.compare_digest(
            self.receipt_hash,
            canonical_sha256_omitting(document, "receiptHash"),
        ):
            raise ValueError("production-runtime lifecycle receipt self-hash is invalid")
        return self

    def require_binding(self, expected: ProductionLifecycleBinding) -> None:
        actual = (
            self.execution_lane,
            self.activation_id,
            self.environment_id,
            self.environment_generation,
            self.manifest_hash,
            self.runtime_context_hash,
        )
        wanted = (
            expected.execution_lane,
            expected.activation_id,
            expected.environment_id,
            expected.environment_generation,
            expected.manifest_hash,
            expected.runtime_context_hash,
        )
        if not all(_constant_time_equal(left, right) for left, right in zip(actual, wanted)):
            raise ProductionLifecycleBindingError()


class ProductionLifecycleReceiptClaims(BaseModel):
    """Short-lived delivery credential carrying one immutable lifecycle receipt."""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    iss: Literal["java-api-service"]
    aud: Literal["python-agent-service"]
    sub: Literal["production-runtime-lifecycle-reconcile"]
    iat: int = Field(ge=0)
    nbf: int = Field(ge=0)
    exp: int = Field(ge=0)
    jti: str = Field(min_length=1, max_length=128, pattern=_IDENTIFIER.pattern)
    receipt: ProductionLifecycleReceipt


@dataclass(frozen=True, slots=True)
class VerifiedProductionLifecycleReceipt:
    receipt: ProductionLifecycleReceipt
    key_id: str
    transport_certificate_sha256: str


class ProductionLifecycleReceiptVerifier:
    """Verify Java mTLS identity, strict ES256 JWS, time, and exact deployment binding."""

    def __init__(
        self,
        *,
        key_resolver: VerificationKeyResolver,
        expected_binding: ProductionLifecycleBinding,
        now: Callable[[], int] | None = None,
    ) -> None:
        self._key_resolver = key_resolver
        self._expected_binding = expected_binding
        self._now = now or (lambda: int(time.time()))

    @property
    def expected_binding(self) -> ProductionLifecycleBinding:
        return self._expected_binding

    def verify(
        self,
        *,
        token: str,
        transport_identity: TransportIdentity,
    ) -> VerifiedProductionLifecycleReceipt:
        self._require_java_transport(transport_identity)
        header, unverified_payload = _decode_unique_jws(token)
        if frozenset(header) != _JWS_HEADER_KEYS:
            raise ProductionLifecycleAuthenticationError()
        if header.get("alg") != "ES256" or header.get("typ") != PRODUCTION_RUNTIME_LIFECYCLE_JWT_TYPE:
            raise ProductionLifecycleAuthenticationError()
        kid = header.get("kid")
        if not isinstance(kid, str) or _IDENTIFIER.fullmatch(kid) is None:
            raise ProductionLifecycleAuthenticationError()
        key = self._resolve_key(kid)
        try:
            payload = jwt.decode(
                token,
                key.public_key,
                algorithms=["ES256"],
                audience="python-agent-service",
                issuer="java-api-service",
                options={
                    "require": ["iss", "aud", "sub", "iat", "nbf", "exp", "jti", "receipt"],
                    "verify_exp": False,
                    "verify_iat": False,
                    "verify_nbf": False,
                },
            )
        except jwt.PyJWTError as error:
            raise ProductionLifecycleAuthenticationError() from error
        if payload != unverified_payload:
            raise ProductionLifecycleAuthenticationError()
        try:
            claims = ProductionLifecycleReceiptClaims.model_validate(payload)
        except ValueError as error:
            raise ProductionLifecycleAuthenticationError() from error
        now = self._now()
        if (
            claims.iat > now
            or claims.nbf > now
            or claims.exp <= now
            or claims.nbf < claims.iat
            or claims.exp < claims.nbf
            or claims.exp - claims.iat > MAX_LIFECYCLE_CREDENTIAL_SECONDS
        ):
            raise ProductionLifecycleAuthenticationError()
        # JWT NumericDate is whole seconds while the durable lifecycle timestamp
        # retains microseconds. A transition inside the issuance second is valid.
        if int(claims.receipt.transitioned_at.timestamp()) > claims.iat:
            raise ProductionLifecycleAuthenticationError()
        claims.receipt.require_binding(self._expected_binding)
        return VerifiedProductionLifecycleReceipt(
            receipt=claims.receipt,
            key_id=key.kid,
            transport_certificate_sha256=transport_identity.certificate_sha256,
        )

    def _resolve_key(self, kid: str) -> ResolvedVerificationKey:
        try:
            key = self._key_resolver.resolve(kid)
        except Exception as error:
            raise ProductionLifecycleAuthenticationError() from error
        if (
            not isinstance(key, ResolvedVerificationKey)
            or key.kid != kid
            or key.algorithm != "ES256"
            or key.curve != "P-256"
            or key.use != "sig"
        ):
            raise ProductionLifecycleAuthenticationError()
        return key

    @staticmethod
    def _require_java_transport(identity: TransportIdentity) -> None:
        if (
            not isinstance(identity, TransportIdentity)
            or not identity.authenticated
            or identity.service_id != "java-api-service"
            or _CERTIFICATE_SHA256.fullmatch(identity.certificate_sha256) is None
        ):
            raise ProductionLifecycleAuthenticationError()


LOAD_LIFECYCLE_BINDING_SQL: Final[str] = """
select activation.activation_id,
       activation.environment_id,
       activation.environment_generation,
       activation.context_hash,
       activation.context_json ->> 'activationManifestHash' as activation_manifest_hash,
       activation.expires_at,
       generation.activation_id as generation_activation_id,
       generation.environment_generation as generation_high_water,
       generation.context_hash as generation_context_hash,
       lifecycle.lifecycle_state,
       lifecycle.activated_at,
       lifecycle.drain_only_at,
       lifecycle.drained_at,
       lifecycle.revoked_at
  from agent_graph_production_runtime_activation activation
  join agent_graph_production_runtime_environment_generation generation
    on generation.environment_id = activation.environment_id
  join agent_graph_production_runtime_activation_lifecycle lifecycle
    on lifecycle.activation_id = activation.activation_id
 where activation.activation_id = %s
 for update of lifecycle
"""

ADVANCE_TO_DRAIN_ONLY_SQL: Final[str] = """
update agent_graph_production_runtime_activation_lifecycle
   set lifecycle_state = 'DRAIN_ONLY',
       drain_only_at = %s,
       updated_at = clock_timestamp()
 where activation_id = %s
   and lifecycle_state = 'ACTIVE'
returning lifecycle_state
"""

ADVANCE_TO_DRAINED_SQL: Final[str] = """
update agent_graph_production_runtime_activation_lifecycle lifecycle
   set lifecycle_state = 'DRAINED',
       drained_at = %s,
       updated_at = clock_timestamp()
 where lifecycle.activation_id = %s
   and lifecycle.lifecycle_state = 'DRAIN_ONLY'
   and not exists (
       select 1
         from agent_graph_command command
        where command.activation_id = lifecycle.activation_id
          and command.execution_mode = 'PRODUCTION'
          and command.status in ('REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED')
   )
   and not exists (
       select 1
         from agent_graph_lease lease
         join agent_graph_command command
           on command.thread_id = lease.thread_id
          and command.command_id = lease.command_id
        where command.activation_id = lifecycle.activation_id
          and lease.released_at is null
          and lease.cancelled_at is null
   )
returning lifecycle_state
"""

ADVANCE_TO_REVOKED_SQL: Final[str] = """
update agent_graph_production_runtime_activation_lifecycle lifecycle
   set lifecycle_state = 'REVOKED_TERMINAL',
       revoked_at = %s,
       updated_at = clock_timestamp()
 where lifecycle.activation_id = %s
   and lifecycle.lifecycle_state = 'DRAINED'
   and not exists (
       select 1
         from agent_graph_command command
        where command.activation_id = lifecycle.activation_id
          and command.execution_mode = 'PRODUCTION'
          and command.status in ('REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED')
   )
   and not exists (
       select 1
         from agent_graph_lease lease
         join agent_graph_command command
           on command.thread_id = lease.thread_id
          and command.command_id = lease.command_id
        where command.activation_id = lifecycle.activation_id
          and lease.released_at is null
          and lease.cancelled_at is null
   )
returning lifecycle_state
"""

_ADVANCE_SQL: Final[dict[ProductionLifecycleState, str]] = {
    ProductionLifecycleState.DRAIN_ONLY: ADVANCE_TO_DRAIN_ONLY_SQL,
    ProductionLifecycleState.DRAINED: ADVANCE_TO_DRAINED_SQL,
    ProductionLifecycleState.REVOKED_TERMINAL: ADVANCE_TO_REVOKED_SQL,
}


@dataclass(frozen=True, slots=True)
class ProductionLifecycleReconciliation:
    lifecycle_state: ProductionLifecycleState
    receipt_hash: str
    idempotent_replay: bool


class PostgresProductionLifecycleRepository:
    """Lock and monotonically project one verified Java receipt."""

    async def reconcile(
        self,
        connection: Any,
        *,
        receipt: ProductionLifecycleReceipt,
        expected_binding: ProductionLifecycleBinding,
    ) -> ProductionLifecycleReconciliation:
        if type(receipt) is not ProductionLifecycleReceipt:
            raise ProductionLifecycleBindingError()
        receipt.require_binding(expected_binding)
        row = await (
            await connection.execute(
                LOAD_LIFECYCLE_BINDING_SQL,
                (receipt.activation_id,),
            )
        ).fetchone()
        if row is None:
            raise ProductionLifecycleBindingError()
        self._require_durable_binding(row, expected_binding)
        try:
            current = ProductionLifecycleState(row["lifecycle_state"])
        except (KeyError, ValueError) as error:
            raise ProductionLifecycleBindingError() from error

        if current is receipt.to_state:
            persisted_at = row[_TIMESTAMP_COLUMN[current]]
            expected_replay = build_production_runtime_lifecycle_receipt(
                expected_binding,
                from_state=_PREVIOUS_STATE[current],
                to_state=current,
                transitioned_at=persisted_at,
            )
            if receipt != expected_replay:
                raise ProductionLifecycleTransitionError(
                    "lifecycle target was already recorded under a different receipt hash"
                )
            return ProductionLifecycleReconciliation(
                lifecycle_state=current,
                receipt_hash=receipt.receipt_hash,
                idempotent_replay=True,
            )
        if current is not receipt.from_state:
            raise ProductionLifecycleTransitionError()
        self._require_timestamp_order(row, receipt)
        cursor = await connection.execute(
            _ADVANCE_SQL[receipt.to_state],
            (receipt.transitioned_at, receipt.activation_id),
        )
        updated = await cursor.fetchone()
        if updated is None:
            if receipt.to_state in {
                ProductionLifecycleState.DRAINED,
                ProductionLifecycleState.REVOKED_TERMINAL,
            }:
                raise ProductionDrainIncompleteError()
            raise ProductionLifecycleTransitionError()
        if updated["lifecycle_state"] != receipt.to_state.value:
            raise ProductionLifecycleTransitionError()
        return ProductionLifecycleReconciliation(
            lifecycle_state=receipt.to_state,
            receipt_hash=receipt.receipt_hash,
            idempotent_replay=False,
        )

    @staticmethod
    def _require_durable_binding(
        row: Mapping[str, Any],
        expected: ProductionLifecycleBinding,
    ) -> None:
        actual = (
            row.get("activation_id"),
            row.get("environment_id"),
            row.get("environment_generation"),
            row.get("context_hash"),
            row.get("activation_manifest_hash"),
            row.get("generation_activation_id"),
            row.get("generation_high_water"),
            row.get("generation_context_hash"),
        )
        wanted = (
            expected.activation_id,
            expected.environment_id,
            expected.environment_generation,
            expected.runtime_context_hash,
            expected.manifest_hash,
            expected.activation_id,
            expected.environment_generation,
            expected.runtime_context_hash,
        )
        if not all(_constant_time_equal(left, right) for left, right in zip(actual, wanted)):
            raise ProductionLifecycleBindingError()

    @staticmethod
    def _require_timestamp_order(
        row: Mapping[str, Any],
        receipt: ProductionLifecycleReceipt,
    ) -> None:
        transitioned_at = _utc(receipt.transitioned_at)
        if receipt.to_state is ProductionLifecycleState.DRAIN_ONLY:
            lower_bound = row.get("expires_at")
            strict = False
        elif receipt.to_state is ProductionLifecycleState.DRAINED:
            lower_bound = row.get("drain_only_at")
            strict = False
        else:
            lower_bound = row.get("drained_at")
            strict = True
        if not isinstance(lower_bound, datetime):
            raise ProductionLifecycleBindingError()
        lower_bound = _utc(lower_bound)
        if transitioned_at < lower_bound or (strict and transitioned_at == lower_bound):
            raise ProductionLifecycleTransitionError()


class ProductionLifecycleReconciler:
    """Transaction boundary for one already verified receipt."""

    def __init__(
        self,
        *,
        pool: Any,
        repository: PostgresProductionLifecycleRepository,
        expected_binding: ProductionLifecycleBinding,
    ) -> None:
        self._pool = pool
        self._repository = repository
        self._expected_binding = expected_binding

    async def reconcile(
        self,
        verified: VerifiedProductionLifecycleReceipt,
    ) -> ProductionLifecycleReconciliation:
        if type(verified) is not VerifiedProductionLifecycleReceipt:
            raise ProductionLifecycleAuthenticationError()
        async with self._pool.connection() as connection:
            async with connection.transaction():
                return await self._repository.reconcile(
                    connection,
                    receipt=verified.receipt,
                    expected_binding=self._expected_binding,
                )


@dataclass(frozen=True, slots=True)
class ProductionCheckpointCommitBinding:
    """Hash-only identity for the post-commit/pre-response recovery window."""

    execution_lane: Literal["PRODUCTION"]
    activation_id: str
    environment_id: str
    environment_generation: int
    manifest_hash: str
    runtime_context_hash: str
    thread_id: str
    command_id: str
    request_hash: str
    checkpoint_namespace: str
    checkpoint_id: str
    result_hash: str

    def __post_init__(self) -> None:
        lifecycle = ProductionLifecycleBinding(
            activation_id=self.activation_id,
            environment_id=self.environment_id,
            environment_generation=self.environment_generation,
            manifest_hash=self.manifest_hash,
            runtime_context_hash=self.runtime_context_hash,
            execution_lane=self.execution_lane,
        )
        del lifecycle
        if THREAD_ID_PATTERN.fullmatch(self.thread_id) is None:
            raise ValueError("checkpoint barrier thread ID is invalid")
        for value, name in (
            (self.command_id, "command ID"),
            (self.checkpoint_id, "checkpoint ID"),
        ):
            if not value or len(value) > 128 or _IDENTIFIER.fullmatch(value) is None:
                raise ValueError(f"checkpoint barrier {name} is invalid")
        if len(self.checkpoint_namespace) > 128 or (
            self.checkpoint_namespace
            and _IDENTIFIER.fullmatch(self.checkpoint_namespace) is None
        ):
            raise ValueError("checkpoint barrier checkpoint namespace is invalid")
        if _SHA256.fullmatch(self.request_hash) is None or _SHA256.fullmatch(self.result_hash) is None:
            raise ValueError("checkpoint barrier hash is invalid")

    def require_lifecycle_binding(self, expected: ProductionLifecycleBinding) -> None:
        actual = (
            self.execution_lane,
            self.activation_id,
            self.environment_id,
            self.environment_generation,
            self.manifest_hash,
            self.runtime_context_hash,
        )
        wanted = (
            expected.execution_lane,
            expected.activation_id,
            expected.environment_id,
            expected.environment_generation,
            expected.manifest_hash,
            expected.runtime_context_hash,
        )
        if not all(_constant_time_equal(left, right) for left, right in zip(actual, wanted)):
            raise ProductionCheckpointBarrierError("checkpoint barrier binding mismatch")


REQUIRE_DURABLE_TARGET_CHECKPOINT_SQL: Final[str] = """
select exists (
    select 1
      from agent_graph_command command
      join agent_graph_result result
        on result.thread_id = command.thread_id
       and result.command_id = command.command_id
       and result.request_hash = command.request_hash
       and result.activation_id = command.activation_id
      join checkpoints checkpoint
        on checkpoint.thread_id = command.thread_id
       and checkpoint.checkpoint_ns = command.committed_checkpoint_ns
       and checkpoint.checkpoint_id = command.committed_checkpoint_id
      join agent_graph_production_runtime_activation activation
        on activation.activation_id = command.activation_id
      join agent_graph_production_runtime_environment_generation generation
        on generation.environment_id = activation.environment_id
     where command.thread_id = %s
       and command.command_id = %s
       and command.request_hash = %s
       and command.execution_mode = 'PRODUCTION'
       and command.activation_id = %s
       and command.status in ('RESULT_CHECKPOINTED', 'COMPLETED')
       and command.committed_checkpoint_ns = %s
       and command.committed_checkpoint_id = %s
       and command.result_hash = %s
       and result.checkpoint_ns = command.committed_checkpoint_ns
       and result.checkpoint_id = command.committed_checkpoint_id
       and result.result_hash = command.result_hash
       and activation.environment_id = %s
       and activation.environment_generation = %s
       and activation.context_hash = %s
       and activation.context_json ->> 'activationManifestHash' = %s
       and generation.activation_id = activation.activation_id
       and generation.environment_generation = activation.environment_generation
       and generation.context_hash = activation.context_hash
) as durable
"""


class ProductionCheckpointDurabilityRepository:
    async def require_durable(
        self,
        connection: Any,
        binding: ProductionCheckpointCommitBinding,
    ) -> None:
        row = await (
            await connection.execute(
                REQUIRE_DURABLE_TARGET_CHECKPOINT_SQL,
                (
                    binding.thread_id,
                    binding.command_id,
                    binding.request_hash,
                    binding.activation_id,
                    binding.checkpoint_namespace,
                    binding.checkpoint_id,
                    binding.result_hash,
                    binding.environment_id,
                    binding.environment_generation,
                    binding.runtime_context_hash,
                    binding.manifest_hash,
                ),
            )
        ).fetchone()
        if row is None or row.get("durable") is not True:
            raise ProductionCheckpointNotDurableError()


class ProductionBarrierReleaseWaiter(Protocol):
    def __call__(
        self,
        binding: ProductionCheckpointCommitBinding,
    ) -> Awaitable[None]: ...


class ProductionBarrierArmingPolicy(Protocol):
    def __call__(self, binding: ProductionCheckpointCommitBinding) -> bool: ...


@dataclass(frozen=True, slots=True)
class ProductionCheckpointBarrierResult:
    enforced: bool
    durable: bool
    released: bool


class ProductionCheckpointRecoveryBarrier:
    """Optional bounded synthetic failpoint after checkpoint commit.

    Production/default construction is disabled.  Enabling requires an explicit
    isolated-synthetic assertion and a release waiter supplied by the Batch 4 harness.
    A timeout fails the request; it never silently crosses the recovery window.
    """

    def __init__(
        self,
        *,
        expected_binding: ProductionLifecycleBinding,
        enabled: bool = False,
        isolated_synthetic_environment: bool = False,
        maximum_wait_seconds: float = 5.0,
        durability_timeout_seconds: float = 2.0,
        arming_policy: ProductionBarrierArmingPolicy | None = None,
        release_waiter: ProductionBarrierReleaseWaiter | None = None,
        durability_repository: ProductionCheckpointDurabilityRepository | None = None,
    ) -> None:
        if enabled and (
            not isolated_synthetic_environment
            or arming_policy is None
            or release_waiter is None
        ):
            raise ValueError("enabled checkpoint recovery barrier must be isolated and synthetic")
        if not 0 < maximum_wait_seconds <= 30:
            raise ValueError("checkpoint recovery barrier wait must be bounded to 30 seconds")
        if not 0 < durability_timeout_seconds <= 5:
            raise ValueError("checkpoint durability probe must be bounded to 5 seconds")
        self._expected_binding = expected_binding
        self._enabled = enabled
        self._maximum_wait_seconds = maximum_wait_seconds
        self._durability_timeout_seconds = durability_timeout_seconds
        self._arming_policy = arming_policy
        self._release_waiter = release_waiter
        self._durability_repository = (
            durability_repository or ProductionCheckpointDurabilityRepository()
        )

    @property
    def enabled(self) -> bool:
        return self._enabled

    async def wait_after_durable_commit(
        self,
        connection: Any,
        binding: ProductionCheckpointCommitBinding,
    ) -> ProductionCheckpointBarrierResult:
        """Verify the durable commit, then pause before the caller emits its response."""

        if not self._enabled:
            return ProductionCheckpointBarrierResult(
                enforced=False,
                durable=False,
                released=True,
            )
        binding.require_lifecycle_binding(self._expected_binding)
        arming_policy = self._arming_policy
        if arming_policy is None:  # Constructor invariant; retained as a fail-closed guard.
            raise ProductionCheckpointBarrierError()
        try:
            armed = arming_policy(binding)
        except ProductionCheckpointBarrierError:
            raise
        except Exception as error:
            raise ProductionCheckpointBarrierError("checkpoint barrier arm is invalid") from error
        if armed is not True:
            return ProductionCheckpointBarrierResult(
                enforced=False,
                durable=False,
                released=True,
            )
        try:
            await asyncio.wait_for(
                self._durability_repository.require_durable(connection, binding),
                timeout=self._durability_timeout_seconds,
            )
        except TimeoutError as error:
            raise ProductionCheckpointNotDurableError() from error
        waiter = self._release_waiter
        if waiter is None:  # Constructor invariant; retained as a fail-closed guard.
            raise ProductionCheckpointBarrierError()
        try:
            await asyncio.wait_for(
                waiter(binding),
                timeout=self._maximum_wait_seconds,
            )
        except TimeoutError as error:
            raise ProductionCheckpointBarrierError("checkpoint recovery barrier timed out") from error
        return ProductionCheckpointBarrierResult(
            enforced=True,
            durable=True,
            released=True,
        )


class FilesystemProductionCheckpointBarrierControl:
    """Host-visible, hash-bound arm/reached/release markers for one recovery window."""

    def __init__(self, directory: Path, *, poll_interval_seconds: float = 0.05) -> None:
        directory = Path(directory)
        if not directory.is_absolute() or directory == Path(directory.anchor):
            raise ValueError("checkpoint barrier directory must be an absolute child path")
        if not 0 < poll_interval_seconds <= 1:
            raise ValueError("checkpoint barrier poll interval must be bounded to one second")
        self._directory = directory
        self._poll_interval_seconds = poll_interval_seconds

    def is_armed(self, binding: ProductionCheckpointCommitBinding) -> bool:
        document = self.arm_document(binding)
        return self._read_exact_marker(
            self._marker_path("arm", document["selfHash"]),
            document,
            missing_ok=True,
        )

    async def wait_for_release(self, binding: ProductionCheckpointCommitBinding) -> None:
        reached = self.reached_document(binding)
        barrier_hash = reached["selfHash"]
        self._write_exact_marker(
            self._marker_path("reached", barrier_hash),
            reached,
        )
        release = self.release_document(barrier_hash)
        release_path = self._marker_path("release", barrier_hash)
        while not self._read_exact_marker(release_path, release, missing_ok=True):
            await asyncio.sleep(self._poll_interval_seconds)

    @staticmethod
    def arm_document(binding: ProductionCheckpointCommitBinding) -> dict[str, Any]:
        return _seal_checkpoint_barrier_marker(
            {
                "schemaVersion": "production-runtime-checkpoint-barrier-arm.v1",
                "barrierWindow": PRODUCTION_RUNTIME_CHECKPOINT_BARRIER_WINDOW,
                "activationId": binding.activation_id,
                "threadId": binding.thread_id,
                "commandId": binding.command_id,
                "requestHash": binding.request_hash,
            }
        )

    @staticmethod
    def reached_document(binding: ProductionCheckpointCommitBinding) -> dict[str, Any]:
        return _seal_checkpoint_barrier_marker(
            {
                "schemaVersion": "production-runtime-checkpoint-barrier-reached.v1",
                "barrierWindow": PRODUCTION_RUNTIME_CHECKPOINT_BARRIER_WINDOW,
                "executionLane": binding.execution_lane,
                "activationId": binding.activation_id,
                "environmentId": binding.environment_id,
                "environmentGeneration": binding.environment_generation,
                "manifestHash": binding.manifest_hash,
                "runtimeContextHash": binding.runtime_context_hash,
                "threadId": binding.thread_id,
                "commandId": binding.command_id,
                "requestHash": binding.request_hash,
                "checkpointNamespace": binding.checkpoint_namespace,
                "checkpointId": binding.checkpoint_id,
                "resultHash": binding.result_hash,
            }
        )

    @staticmethod
    def release_document(barrier_hash: str) -> dict[str, Any]:
        if _SHA256.fullmatch(barrier_hash) is None:
            raise ValueError("checkpoint barrier hash is invalid")
        return _seal_checkpoint_barrier_marker(
            {
                "schemaVersion": "production-runtime-checkpoint-barrier-release.v1",
                "barrierWindow": PRODUCTION_RUNTIME_CHECKPOINT_BARRIER_WINDOW,
                "barrierHash": barrier_hash,
                "action": "RELEASE",
            }
        )

    def arm_path(self, binding: ProductionCheckpointCommitBinding) -> Path:
        document = self.arm_document(binding)
        return self._marker_path("arm", document["selfHash"])

    def reached_path(self, binding: ProductionCheckpointCommitBinding) -> Path:
        document = self.reached_document(binding)
        return self._marker_path("reached", document["selfHash"])

    def release_path(self, barrier_hash: str) -> Path:
        return self._marker_path("release", barrier_hash)

    def _marker_path(self, kind: str, marker_hash: str) -> Path:
        if kind not in {"arm", "reached", "release"}:
            raise ValueError("checkpoint barrier marker kind is invalid")
        if _SHA256.fullmatch(marker_hash) is None:
            raise ValueError("checkpoint barrier marker hash is invalid")
        return self._directory / f"{PRODUCTION_RUNTIME_CHECKPOINT_BARRIER_WINDOW}.{marker_hash}.{kind}.json"

    @staticmethod
    def _read_exact_marker(
        path: Path,
        expected: Mapping[str, Any],
        *,
        missing_ok: bool,
    ) -> bool:
        try:
            metadata = path.lstat()
        except FileNotFoundError:
            if missing_ok:
                return False
            raise ProductionCheckpointBarrierError("checkpoint barrier marker is missing")
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_CHECKPOINT_BARRIER_MARKER_BYTES:
            raise ProductionCheckpointBarrierError("checkpoint barrier marker is invalid")
        try:
            raw = path.read_bytes()
            if len(raw) > MAX_CHECKPOINT_BARRIER_MARKER_BYTES:
                raise ValueError("marker exceeds limit")
            document = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_json_object)
        except (OSError, UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
            raise ProductionCheckpointBarrierError("checkpoint barrier marker is invalid") from error
        if document != expected:
            raise ProductionCheckpointBarrierError("checkpoint barrier marker binding mismatch")
        return True

    def _write_exact_marker(self, path: Path, document: Mapping[str, Any]) -> None:
        if self._read_exact_marker(path, document, missing_ok=True):
            return
        try:
            self._directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            raw = json.dumps(
                document,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            ).encode("utf-8")
            if len(raw) > MAX_CHECKPOINT_BARRIER_MARKER_BYTES:
                raise ValueError("marker exceeds limit")
            temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
            flags |= getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(temporary, flags, 0o600)
            try:
                stream = os.fdopen(descriptor, "wb", closefd=True)
                descriptor = -1
                with stream:
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, path)
            finally:
                if descriptor >= 0:
                    os.close(descriptor)
                temporary.unlink(missing_ok=True)
        except (OSError, ValueError) as error:
            raise ProductionCheckpointBarrierError(
                "checkpoint barrier reached marker could not be persisted"
            ) from error


class ProductionCheckpointGatewayBarrier:
    """Translate one reconciled candidate result into the exact recovery binding."""

    def __init__(
        self,
        *,
        pool: Any,
        expected_binding: ProductionLifecycleBinding,
        barrier: ProductionCheckpointRecoveryBarrier,
        acquire_timeout_seconds: float,
    ) -> None:
        if pool is None or acquire_timeout_seconds <= 0:
            raise ValueError("checkpoint gateway barrier requires Graph PostgreSQL")
        self._pool = pool
        self._expected_binding = expected_binding
        self._barrier = barrier
        self._acquire_timeout_seconds = acquire_timeout_seconds

    async def wait_after_durable_commit(
        self,
        *,
        admission: GatewayAdmission,
        result: ResultRecord,
    ) -> None:
        authority = admission.candidate_authority
        command = admission.binding
        if (
            not isinstance(authority, ProductionRuntimeAuthority)
            or command.execution_lane is not GraphGatewayMode.PRODUCTION
            or result.execution_lane is not GraphGatewayMode.PRODUCTION
            or result.activation_id != authority.activation_id
            or (result.thread_id, result.command_id, result.request_hash)
            != (command.thread_id, command.command_id, command.request_hash)
        ):
            raise ProductionCheckpointBarrierError("checkpoint result binding mismatch")
        binding = ProductionCheckpointCommitBinding(
            execution_lane="PRODUCTION",
            activation_id=authority.activation_id,
            environment_id=authority.context.environmentId,
            environment_generation=authority.context.environmentGeneration,
            manifest_hash=authority.context.activationManifestHash,
            runtime_context_hash=authority.context_hash,
            thread_id=result.thread_id,
            command_id=result.command_id,
            request_hash=result.request_hash,
            checkpoint_namespace=result.checkpoint_ns,
            checkpoint_id=result.checkpoint_id,
            result_hash=result.result_hash,
        )
        binding.require_lifecycle_binding(self._expected_binding)
        async with self._pool.connection(
            timeout=self._acquire_timeout_seconds
        ) as connection:
            await self._barrier.wait_after_durable_commit(connection, binding)


def build_production_runtime_lifecycle_receipt(
    binding: ProductionLifecycleBinding,
    *,
    from_state: ProductionLifecycleState,
    to_state: ProductionLifecycleState,
    transitioned_at: datetime,
) -> ProductionLifecycleReceipt:
    document: dict[str, Any] = {
        "schemaVersion": "production-runtime-activation-lifecycle-receipt.v1",
        "executionLane": binding.execution_lane,
        "authority": "JAVA_CONTROL_PLANE",
        "activationId": binding.activation_id,
        "environmentId": binding.environment_id,
        "environmentGeneration": binding.environment_generation,
        "manifestHash": binding.manifest_hash,
        "runtimeContextHash": binding.runtime_context_hash,
        "fromState": from_state.value,
        "toState": to_state.value,
        "transitionedAt": _utc(transitioned_at).isoformat().replace("+00:00", "Z"),
    }
    document["receiptHash"] = canonical_sha256(document)
    return ProductionLifecycleReceipt.model_validate(document)


def _seal_checkpoint_barrier_marker(document: Mapping[str, Any]) -> dict[str, Any]:
    sealed = dict(document)
    if "selfHash" in sealed:
        raise ValueError("checkpoint barrier marker cannot supply its own hash")
    sealed["selfHash"] = canonical_sha256(sealed)
    return sealed


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    document: dict[str, Any] = {}
    for key, value in pairs:
        if key in document:
            raise ValueError("duplicate checkpoint barrier marker member")
        document[key] = value
    return document


def _decode_unique_jws(token: str) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(token, str) or len(token.encode("utf-8")) > MAX_LIFECYCLE_JWS_BYTES:
        raise ProductionLifecycleAuthenticationError()
    parts = token.split(".")
    if len(parts) != 3 or any(not part for part in parts):
        raise ProductionLifecycleAuthenticationError()
    try:
        header = _decode_unique_json_segment(parts[0])
        payload = _decode_unique_json_segment(parts[1])
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as error:
        raise ProductionLifecycleAuthenticationError() from error
    return header, payload


def _decode_unique_json_segment(segment: str) -> dict[str, Any]:
    padding = "=" * (-len(segment) % 4)
    raw = base64.b64decode(
        (segment + padding).encode("ascii"),
        altchars=b"-_",
        validate=True,
    )
    if len(raw) > MAX_LIFECYCLE_JWS_BYTES:
        raise ValueError("JWS segment exceeds lifecycle limit")

    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JWS JSON member")
            result[key] = value
        return result

    decoded = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    if not isinstance(decoded, dict):
        raise ValueError("JWS segment is not an object")
    return decoded


def _constant_time_equal(left: Any, right: Any) -> bool:
    if isinstance(left, str) and isinstance(right, str):
        return hmac.compare_digest(left, right)
    if isinstance(left, int) and not isinstance(left, bool):
        return isinstance(right, int) and not isinstance(right, bool) and left == right
    return left == right


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise ValueError("timestamp must be timezone-aware")
    return value.astimezone(timezone.utc)
