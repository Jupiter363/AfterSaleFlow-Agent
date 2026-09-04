"""Isolated production-runtime runtime projection and signed command authority."""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
import hmac
import json
import re
from typing import Any, Literal

import jwt
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.config import (
    GraphProductionBindingSettings,
    GraphProductionExplicitCaseScope,
    GraphProductionRuntimeContextSettings,
)
from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting
from app.contracts.v1.models import RoomGraphCommand, RoomGraphResult
from app.graph_runtime.errors import GraphContractError, GraphThreadBindingError
from app.graph_runtime.identity import (
    ActorScopeBinding,
    RoomType,
    ThreadIdentity,
)
from app.security.invocation_envelope import (
    InvocationClaims,
    InvocationEnvelopeError,
    InvocationEnvelopeVerifier,
    ResolvedVerificationKey,
    TransportIdentity,
    VerificationKeyResolver,
    VerifiedInvocation,
    invocation_binding_claims,
)


PRODUCTION_RUNTIME_COMMAND_PATH = "/internal/graphs/production-runtime/commands/stream"
PRODUCTION_RUNTIME_ENVELOPE_VERSION = "production-runtime-graph-command-envelope.v1"
PRODUCTION_RUNTIME_EXECUTION_LANE = "PRODUCTION"
PRODUCTION_RUNTIME_COMMAND_JWT_TYPE = "production-runtime-graph-command+jwt"
_ACTIVATION_ID = re.compile(r"^p9act\.v1\.[0-9a-f]{32}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
_PROPOSAL_SCHEMA_BY_ROOM = {
    "INTAKE": "production-runtime-intake-proposal.v1",
    "EVIDENCE": "production-runtime-evidence-proposal.v2",
    "HEARING": "production-runtime-hearing-proposal.v1",
    "REVIEW": "production-runtime-review-proposal.v1",
}


def production_runtime_command_hash(command: RoomGraphCommand) -> str:
    return canonical_sha256(command.model_dump(mode="json", exclude_none=True))


class ProductionGraphCommandEnvelope(BaseModel):
    """Additive wrapper; the embedded frozen command contract remains unchanged."""

    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    schema_version: Literal["production-runtime-graph-command-envelope.v1"]
    execution_lane: Literal["PRODUCTION"]
    activation_id: str = Field(pattern=_ACTIVATION_ID.pattern)
    room_fencing_token: int = Field(ge=1, le=9_007_199_254_740_991)
    command_hash: str = Field(pattern=_SHA256.pattern)
    command: RoomGraphCommand
    command_envelope_hash: str = Field(pattern=_SHA256.pattern)

    @model_validator(mode="after")
    def validate_command_hash(self) -> ProductionGraphCommandEnvelope:
        if not hmac.compare_digest(
            self.command_hash,
            production_runtime_command_hash(self.command),
        ):
            raise ValueError("production-runtime envelope command hash is invalid")
        if not hmac.compare_digest(
            self.command_envelope_hash,
            canonical_sha256_omitting(
                self.model_dump(mode="json", exclude_none=True),
                "command_envelope_hash",
            ),
        ):
            raise ValueError("production-runtime command envelope self-hash is invalid")
        return self


class ProductionGraphResultEnvelope(BaseModel):
    """Proposal-only result wrapper bound to its originating candidate command."""

    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    schema_version: Literal["production-runtime-graph-result-envelope.v1"]
    execution_lane: Literal["PRODUCTION"]
    activation_id: str = Field(pattern=_ACTIVATION_ID.pattern)
    room_fencing_token: int = Field(ge=1, le=9_007_199_254_740_991)
    command_hash: str = Field(pattern=_SHA256.pattern)
    command_envelope_hash: str = Field(pattern=_SHA256.pattern)
    execution_provider: str = Field(min_length=1, max_length=64)
    execution_model: str = Field(min_length=1, max_length=128)
    result_hash: str = Field(pattern=_SHA256.pattern)
    proposal_hash: str = Field(pattern=_SHA256.pattern)
    result_envelope_hash: str = Field(pattern=_SHA256.pattern)
    graph_output_authority: Literal["PROPOSAL_ONLY"]
    result: RoomGraphResult

    @model_validator(mode="after")
    def validate_hashes(self) -> ProductionGraphResultEnvelope:
        nested = self.result.model_dump(mode="json", exclude_none=True)
        if (
            not hmac.compare_digest(
                self.result.output_hash,
                canonical_sha256_omitting(nested, "output_hash"),
            )
            or not hmac.compare_digest(self.result_hash, self.result.output_hash)
        ):
            raise ValueError("production-runtime result hash differs from nested result")
        if not hmac.compare_digest(
            self.result_envelope_hash,
            canonical_sha256_omitting(
                self.model_dump(mode="json", exclude_none=True),
                "result_envelope_hash",
            ),
        ):
            raise ValueError("production-runtime result envelope self-hash is invalid")
        return self

    def require_proposal_hash(self, proposal: Mapping[str, Any]) -> None:
        if not hmac.compare_digest(self.proposal_hash, canonical_sha256(dict(proposal))):
            raise ValueError("production-runtime proposal hash is invalid")


class ProductionRoomProposal(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    schema_version: Literal[
        "production-runtime-intake-proposal.v1",
        "production-runtime-evidence-proposal.v2",
        "production-runtime-hearing-proposal.v1",
        "production-runtime-review-proposal.v1",
    ]
    proposal_id: str = Field(pattern=_IDENTIFIER.pattern)
    command_id: str = Field(pattern=_IDENTIFIER.pattern)
    logical_run_id: str = Field(pattern=_IDENTIFIER.pattern)
    attempt_id: str = Field(pattern=_IDENTIFIER.pattern)
    payload_schema_version: str = Field(pattern=_IDENTIFIER.pattern)
    payload_ref: str = Field(
        min_length=1,
        max_length=512,
        pattern=r"^urn:production-runtime:proposal:",
    )
    payload_hash: str = Field(pattern=_SHA256.pattern)
    terminal_class: Literal["NEEDS_INPUT", "COMPLETED", "NEEDS_REVIEW"]
    formal_authority: Literal[False]


class ProductionRoomProposalSource(BaseModel):
    """Exact JSON-pointer source whose ``/proposal`` value is hashed for finalization."""

    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    schema_version: Literal["production-runtime-room-proposal-source.v2"]
    room_type: Literal["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]
    proposal: ProductionRoomProposal

    @model_validator(mode="after")
    def validate_room_schema(self) -> ProductionRoomProposalSource:
        if self.proposal.schema_version != _PROPOSAL_SCHEMA_BY_ROOM[self.room_type]:
            raise ValueError("production-runtime proposal schema differs from its room")
        return self

    @property
    def proposal_hash(self) -> str:
        return canonical_sha256(self.proposal.model_dump(mode="json"))

    def require_result_binding(self, result: RoomGraphResult) -> None:
        expected = (
            self.proposal.command_id,
            self.proposal.logical_run_id,
            self.proposal.attempt_id,
            self.proposal.terminal_class,
        )
        actual = (
            result.command_id,
            result.logical_run_id,
            result.attempt_id,
            result.status,
        )
        if expected != actual:
            raise ValueError("production-runtime proposal source differs from the nested result")


def build_production_runtime_result_envelope(
    result: RoomGraphResult,
    *,
    activation_id: str,
    room_fencing_token: int,
    command_hash: str,
    command_envelope_hash: str,
    execution_provider: str,
    execution_model: str,
    proposal_hash: str,
) -> ProductionGraphResultEnvelope:
    values = {
        "schema_version": "production-runtime-graph-result-envelope.v1",
        "execution_lane": PRODUCTION_RUNTIME_EXECUTION_LANE,
        "activation_id": activation_id,
        "room_fencing_token": room_fencing_token,
        "command_hash": command_hash,
        "command_envelope_hash": command_envelope_hash,
        "execution_provider": execution_provider,
        "execution_model": execution_model,
        "result_hash": result.output_hash,
        "proposal_hash": proposal_hash,
        "graph_output_authority": "PROPOSAL_ONLY",
        "result": result.model_dump(mode="json", exclude_none=True),
    }
    return ProductionGraphResultEnvelope.model_validate(
        {
            **values,
            "result_envelope_hash": canonical_sha256(values),
        }
    )


class ProductionInvocationClaims(InvocationClaims):
    execution_lane: Literal["PRODUCTION"]
    activation_id: str = Field(pattern=_ACTIVATION_ID.pattern)
    room_fencing_token: int = Field(ge=1, le=9_007_199_254_740_991)
    command_hash: str = Field(pattern=_SHA256.pattern)
    command_envelope_hash: str = Field(pattern=_SHA256.pattern)
    agent_session_id: str | None = Field(default=None, pattern=_IDENTIFIER.pattern)
    parallel_phase: Literal["PREPARE", "EXECUTE", "ABANDON", "TERMINATE"] | None = None
    parallel_admission_receipt_sha256: str | None = Field(
        default=None,
        pattern=_SHA256.pattern,
    )
    parallel_failure_code: str | None = Field(
        default=None,
        pattern=r"^[A-Z][A-Z0-9_]{2,127}$",
    )

    @model_validator(mode="after")
    def validate_parallel_delivery_binding(self) -> ProductionInvocationClaims:
        if (
            self.parallel_phase == "PREPARE"
            and (
                self.parallel_admission_receipt_sha256 is not None
                or self.parallel_failure_code is not None
            )
        ) or (
            self.parallel_phase in {"EXECUTE", "ABANDON"}
            and (
                self.parallel_admission_receipt_sha256 is None
                or self.parallel_failure_code is not None
            )
        ) or (
            self.parallel_phase == "TERMINATE"
            and (
                self.parallel_admission_receipt_sha256 is None
                or self.parallel_failure_code is None
            )
        ) or (
            self.parallel_phase is None
            and (
                self.parallel_admission_receipt_sha256 is not None
                or self.parallel_failure_code is not None
            )
        ):
            raise ValueError("production-runtime parallel delivery binding is invalid")
        return self


@dataclass(frozen=True, slots=True)
class ProductionRuntimeAuthority:
    """Trusted non-secret startup projection plus exact local executor bindings."""

    context: GraphProductionRuntimeContextSettings
    context_hash: str
    command_bindings: tuple[GraphProductionBindingSettings, ...]

    @property
    def activation_id(self) -> str:
        return self.context.activationId

    @classmethod
    def from_context(
        cls,
        context: GraphProductionRuntimeContextSettings,
        bindings: tuple[GraphProductionBindingSettings, ...],
    ) -> ProductionRuntimeAuthority:
        if len(bindings) != 1:
            raise ValueError("production-runtime runtime authority requires one composite binding")
        binding = bindings[0]
        if (
            binding.graph_key != "all-rooms.production-runtime.v2"
            or binding.graph_version != "production-runtime-graph.2026-08-18.3"
            or binding.checkpoint_schema_version != "production-runtime-checkpoint.v2"
            or binding.output_schema_version != "production-runtime-room-proposal-source.v2"
            or frozenset(binding.allowed_room_types)
            != frozenset({"INTAKE", "EVIDENCE", "HEARING", "REVIEW"})
        ):
            raise ValueError("production-runtime runtime authority binding is not the frozen composite")
        configured_rooms = {
            room_type for binding in bindings for room_type in binding.allowed_room_types
        }
        if not set(context.allowedRoomTypes).issubset(configured_rooms):
            raise ValueError("production-runtime runtime scope exceeds executor bindings")
        document = context.model_dump(mode="json", by_alias=True)
        return cls(
            context=context,
            context_hash=canonical_sha256(document),
            command_bindings=bindings,
        )

    def authorize(
        self,
        envelope: ProductionGraphCommandEnvelope,
    ) -> None:
        command = envelope.command
        expected_scope = (
            self.activation_id,
            self.context.tenantSurrogate,
        )
        actual_scope = (
            envelope.activation_id,
            command.tenant_surrogate,
        )
        if actual_scope != expected_scope:
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_ACTIVATION_SCOPE_MISMATCH")
        command_binding = (
            command.graph_key,
            command.graph_version,
            command.checkpoint_schema_version,
        )
        if not any(
            command_binding
            == (item.graph_key, item.graph_version, item.checkpoint_schema_version)
            and command.room_type in item.allowed_room_types
            for item in self.command_bindings
        ):
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_COMMAND_BINDING_MISMATCH")
        case_scope = self.context.caseScope
        if isinstance(case_scope, GraphProductionExplicitCaseScope):
            if command.case_id not in case_scope.allowedCaseIds:
                raise InvocationEnvelopeError("PRODUCTION_RUNTIME_CASE_NOT_ALLOWED")
        elif self.synthetic_slot(command.case_id) is None:
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_SYNTHETIC_CASE_NOT_ALLOWED")
        if command.room_type not in self.context.allowedRoomTypes:
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_ROOM_NOT_ALLOWED")

    def synthetic_slot(self, case_id: str) -> int | None:
        scope = self.context.caseScope
        if isinstance(scope, GraphProductionExplicitCaseScope):
            return None
        if not case_id.startswith(scope.caseIdPrefix):
            return None
        suffix = case_id.removeprefix(scope.caseIdPrefix)
        if not suffix or not suffix.isascii() or not suffix.isdigit():
            return None
        slot = int(suffix)
        if slot < 1 or slot > scope.maxCases:
            return None
        return slot


@dataclass(frozen=True)
class VerifiedProductionInvocation(VerifiedInvocation):
    authority: ProductionRuntimeAuthority
    command_hash: str
    command_envelope_hash: str
    room_fencing_token: int


class ProductionInvocationVerifier(InvocationEnvelopeVerifier):
    """Verify command mTLS/JWS independently from the deployment activation JWS."""

    def __init__(
        self,
        *,
        key_resolver: VerificationKeyResolver,
        authority: ProductionRuntimeAuthority,
        now: Callable[[], int] | None = None,
    ) -> None:
        super().__init__(key_resolver=key_resolver, now=now)
        self._authority = authority

    def verify_envelope(
        self,
        *,
        token: str,
        envelope: ProductionGraphCommandEnvelope,
        transport_identity: TransportIdentity,
    ) -> VerifiedProductionInvocation:
        return self._verify_envelope(
            token=token,
            envelope=envelope,
            transport_identity=transport_identity,
            allow_expired=False,
            parallel_phase=None,
            admission_receipt_sha256=None,
            failure_code=None,
        )

    def verify_parallel_envelope(
        self,
        *,
        token: str,
        envelope: ProductionGraphCommandEnvelope,
        transport_identity: TransportIdentity,
        phase: Literal["PREPARE", "EXECUTE", "ABANDON", "TERMINATE"],
        admission_receipt_sha256: str | None,
        failure_code: str | None = None,
    ) -> VerifiedProductionInvocation:
        if not envelope.command.is_parallel_intake_command:
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_PARALLEL_COMMAND_REQUIRED")
        if (
            phase == "PREPARE"
            and (admission_receipt_sha256 is not None or failure_code is not None)
        ) or (
            phase in {"EXECUTE", "ABANDON"}
            and (
                admission_receipt_sha256 is None
                or _SHA256.fullmatch(admission_receipt_sha256) is None
                or failure_code is not None
            )
        ) or (
            phase == "TERMINATE"
            and (
                admission_receipt_sha256 is None
                or _SHA256.fullmatch(admission_receipt_sha256) is None
                or failure_code is None
                or re.fullmatch(r"[A-Z][A-Z0-9_]{2,127}", failure_code) is None
            )
        ):
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_PARALLEL_DELIVERY_BINDING_REJECTED")
        return self._verify_envelope(
            token=token,
            envelope=envelope,
            transport_identity=transport_identity,
            allow_expired=False,
            parallel_phase=phase,
            admission_receipt_sha256=admission_receipt_sha256,
            failure_code=failure_code,
        )

    def verify_envelope_for_reconciliation(
        self,
        *,
        token: str,
        envelope: ProductionGraphCommandEnvelope,
        transport_identity: TransportIdentity,
    ) -> VerifiedProductionInvocation:
        """Verify an old sealed command without granting any new execution authority."""

        return self._verify_envelope(
            token=token,
            envelope=envelope,
            transport_identity=transport_identity,
            allow_expired=True,
            parallel_phase=None,
            admission_receipt_sha256=None,
            failure_code=None,
        )

    def _verify_envelope(
        self,
        *,
        token: str,
        envelope: ProductionGraphCommandEnvelope,
        transport_identity: TransportIdentity,
        allow_expired: bool,
        parallel_phase: Literal["PREPARE", "EXECUTE", "ABANDON", "TERMINATE"] | None,
        admission_receipt_sha256: str | None,
        failure_code: str | None,
    ) -> VerifiedProductionInvocation:
        self._authority.authorize(envelope)
        verified = (
            self._verify_reconciliation_credential(
                token=token,
                command=envelope.command,
                transport_identity=transport_identity,
            )
            if allow_expired
            else super().verify(
                token=token,
                command=envelope.command,
                transport_identity=transport_identity,
            )
        )
        claims = verified.claims
        if verified.key_id not in self._authority.context.trustedSigningKeyIds:
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_COMMAND_KEY_REJECTED")
        if not isinstance(claims, ProductionInvocationClaims):
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_COMMAND_CLAIMS_REJECTED")
        if (
            claims.parallel_phase != parallel_phase
            or claims.parallel_admission_receipt_sha256
            != admission_receipt_sha256
            or claims.parallel_failure_code != failure_code
        ):
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_PARALLEL_DELIVERY_BINDING_MISMATCH")
        expected = (
            envelope.execution_lane,
            envelope.activation_id,
            envelope.room_fencing_token,
            envelope.command_hash,
            envelope.command_envelope_hash,
        )
        actual = (
            claims.execution_lane,
            claims.activation_id,
            claims.room_fencing_token,
            claims.command_hash,
            claims.command_envelope_hash,
        )
        if actual != expected:
            raise InvocationEnvelopeError("PRODUCTION_RUNTIME_COMMAND_ENVELOPE_MISMATCH")
        return VerifiedProductionInvocation(
            claims=claims,
            key_id=verified.key_id,
            request_hash=verified.request_hash,
            transport_certificate_sha256=verified.transport_certificate_sha256,
            authority=self._authority,
            command_hash=envelope.command_hash,
            command_envelope_hash=envelope.command_envelope_hash,
            room_fencing_token=envelope.room_fencing_token,
        )

    def _verify_reconciliation_credential(
        self,
        *,
        token: str,
        command: RoomGraphCommand,
        transport_identity: TransportIdentity,
    ) -> VerifiedInvocation:
        self._verify_transport_identity(transport_identity)
        header = self._decode_header(token)
        key = self._resolve_key(header["kid"])
        claims = self._decode_claims(token, key)
        now = self._now()  # noqa: SLF001
        if claims.exp <= claims.iat or claims.exp - claims.iat > 60:
            raise InvocationEnvelopeError("INVOCATION_JWS_LIFETIME_REJECTED")
        if claims.nbf < claims.iat or claims.nbf > claims.exp:
            raise InvocationEnvelopeError("INVOCATION_JWS_TIME_ORDER_REJECTED")
        if claims.iat > now + 5 or claims.nbf > now + 5:
            raise InvocationEnvelopeError("INVOCATION_JWS_NOT_YET_VALID")
        self._verify_fresh_delivery_nonce(claims)
        expected = invocation_binding_claims(command)
        if not hmac.compare_digest(command.request_hash, str(expected["request_hash"])):
            raise InvocationEnvelopeError("INVOCATION_COMMAND_SELF_HASH_MISMATCH")
        actual = claims.model_dump(mode="json")
        for name, value in expected.items():
            if name == "profile_bindings_hash":
                continue
            candidate = actual[name]
            if isinstance(value, int):
                matches = type(candidate) is int and candidate == value
            else:
                matches = isinstance(candidate, str) and hmac.compare_digest(
                    candidate,
                    value,
                )
            if not matches:
                raise InvocationEnvelopeError(f"INVOCATION_{name.upper()}_MISMATCH")
        if command.invocation_context.envelope_key_id != key.kid:
            raise InvocationEnvelopeError("INVOCATION_COMMAND_KEY_ID_MISMATCH")
        return VerifiedInvocation(
            claims=claims,
            key_id=key.kid,
            request_hash=str(expected["request_hash"]),
            transport_certificate_sha256=transport_identity.certificate_sha256,
        )

    @staticmethod
    def _decode_header(token: str) -> Mapping[str, str]:
        return InvocationEnvelopeVerifier._decode_header_for(  # noqa: SLF001
            token,
            expected_type=PRODUCTION_RUNTIME_COMMAND_JWT_TYPE,
        )

    @staticmethod
    def _decode_claims(
        token: str,
        key: ResolvedVerificationKey,
    ) -> ProductionInvocationClaims:
        try:
            payload = jwt.decode(
                token,
                key.public_key,
                algorithms=["ES256"],
                audience="python-agent-service",
                issuer="java-api-service",
                options={
                    "require": ["iss", "aud", "sub", "iat", "nbf", "exp", "jti"],
                    "verify_exp": False,
                    "verify_iat": False,
                    "verify_nbf": False,
                },
            )
            return ProductionInvocationClaims.model_validate(payload)
        except (jwt.PyJWTError, ValueError) as error:
            raise InvocationEnvelopeError("INVOCATION_JWS_CLAIMS_REJECTED") from error


INSERT_GENERATION_HIGH_WATER_SQL = """
insert into agent_graph_production_runtime_environment_generation (
    environment_id, environment_generation, activation_id, context_hash
)
values (%s, %s, %s, %s)
on conflict (environment_id) do nothing
"""

LOAD_GENERATION_HIGH_WATER_SQL = """
select environment_generation, activation_id, context_hash
  from agent_graph_production_runtime_environment_generation
 where environment_id = %s
 for update
"""

ADVANCE_GENERATION_HIGH_WATER_SQL = """
update agent_graph_production_runtime_environment_generation
   set environment_generation = %s,
       activation_id = %s,
       context_hash = %s,
       updated_at = clock_timestamp()
 where environment_id = %s
   and environment_generation < %s
"""

REGISTER_RUNTIME_CONTEXT_SQL = """
insert into agent_graph_production_runtime_activation (
    activation_id, run_nonce, context_hash, environment_id,
    environment_generation, candidate_sha, tenant_surrogate, case_scope,
    allowed_room_types, temporal_namespace, context_json, issued_at, expires_at
)
values (%s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s, %s::jsonb, %s, %s)
on conflict (activation_id) do nothing
returning activation_id
"""

LOAD_RUNTIME_CONTEXT_SQL = """
select activation_id, run_nonce, context_hash, environment_id,
       environment_generation, candidate_sha, tenant_surrogate, case_scope,
       allowed_room_types, temporal_namespace, context_json, issued_at, expires_at
  from agent_graph_production_runtime_activation
 where activation_id = %s
"""

ACTIVATE_RUNTIME_CONTEXT_SQL = """
insert into agent_graph_production_runtime_activation_lifecycle (
    activation_id, lifecycle_state, activated_at
)
select activation_id, 'ACTIVE', clock_timestamp()
  from agent_graph_production_runtime_activation
 where activation_id = %s and issued_at <= clock_timestamp() and expires_at > clock_timestamp()
on conflict (activation_id) do nothing
returning activation_id
"""

DRAIN_EXPIRED_RUNTIME_CONTEXT_SQL = """
update agent_graph_production_runtime_activation_lifecycle lifecycle
   set lifecycle_state = 'DRAIN_ONLY',
       drain_only_at = coalesce(drain_only_at, activation.expires_at),
       updated_at = clock_timestamp()
  from agent_graph_production_runtime_activation activation
 where lifecycle.activation_id = activation.activation_id
   and lifecycle.activation_id = %s
   and lifecycle.lifecycle_state = 'ACTIVE'
   and activation.expires_at <= clock_timestamp()
"""

LOAD_RUNTIME_LIFECYCLE_SQL = """
select lifecycle_state
  from agent_graph_production_runtime_activation_lifecycle
 where activation_id = %s
 for update
"""


class PostgresProductionActivationRepository:
    """Register a non-secret runtime projection under a durable generation high-water."""
    async def register(
        self,
        connection: Any,
        authority: ProductionRuntimeAuthority,
    ) -> None:
        context = authority.context
        await connection.execute(
            INSERT_GENERATION_HIGH_WATER_SQL,
            (
                context.environmentId,
                context.environmentGeneration,
                context.activationId,
                authority.context_hash,
            ),
        )
        generation = await (
            await connection.execute(
                LOAD_GENERATION_HIGH_WATER_SQL,
                (context.environmentId,),
            )
        ).fetchone()
        if generation is None:
            raise GraphContractError("PRODUCTION_RUNTIME_GENERATION_REGISTRATION_FAILED")
        stored_generation = generation["environment_generation"]
        if context.environmentGeneration < stored_generation:
            raise GraphContractError("PRODUCTION_RUNTIME_ENVIRONMENT_GENERATION_STALE")
        if context.environmentGeneration == stored_generation:
            if (
                generation["activation_id"],
                generation["context_hash"],
            ) != (context.activationId, authority.context_hash):
                raise GraphContractError("PRODUCTION_RUNTIME_ENVIRONMENT_GENERATION_CONFLICT")
        else:
            updated = await connection.execute(
                ADVANCE_GENERATION_HIGH_WATER_SQL,
                (
                    context.environmentGeneration,
                    context.activationId,
                    authority.context_hash,
                    context.environmentId,
                    context.environmentGeneration,
                ),
            )
            if updated.rowcount != 1:
                raise GraphContractError("PRODUCTION_RUNTIME_ENVIRONMENT_GENERATION_CONFLICT")
        expected = (
            context.activationId,
            context.runNonce,
            authority.context_hash,
            context.environmentId,
            context.environmentGeneration,
            context.candidateSha,
            context.tenantSurrogate,
            context.caseScope.model_dump(mode="json"),
            list(context.allowedRoomTypes),
            context.temporalNamespace,
            context.model_dump(mode="json", by_alias=True),
            context.issuedAt,
            context.expiresAt,
        )
        await connection.execute(
            REGISTER_RUNTIME_CONTEXT_SQL,
            (
                *expected[:7],
                json.dumps(expected[7], separators=(",", ":")),
                json.dumps(expected[8], separators=(",", ":")),
                expected[9],
                json.dumps(expected[10], separators=(",", ":")),
                *expected[11:],
            ),
        )
        row = await (
            await connection.execute(LOAD_RUNTIME_CONTEXT_SQL, (context.activationId,))
        ).fetchone()
        if row is None:
            raise GraphContractError("PRODUCTION_RUNTIME_ACTIVATION_REGISTRATION_FAILED")
        actual = tuple(row[name] for name in (
            "activation_id",
            "run_nonce",
            "context_hash",
            "environment_id",
            "environment_generation",
            "candidate_sha",
            "tenant_surrogate",
            "case_scope",
            "allowed_room_types",
            "temporal_namespace",
            "context_json",
            "issued_at",
            "expires_at",
        ))
        normalized = (
            *actual[:7],
            dict(actual[7]),
            list(actual[8]),
            actual[9],
            dict(actual[10]),
            *actual[11:],
        )
        if normalized != expected:
            raise GraphContractError("PRODUCTION_RUNTIME_ACTIVATION_REUSE_REJECTED")
        await connection.execute(ACTIVATE_RUNTIME_CONTEXT_SQL, (context.activationId,))
        await connection.execute(
            DRAIN_EXPIRED_RUNTIME_CONTEXT_SQL,
            (context.activationId,),
        )
        lifecycle = await (
            await connection.execute(
                LOAD_RUNTIME_LIFECYCLE_SQL,
                (context.activationId,),
            )
        ).fetchone()
        if lifecycle is None or lifecycle["lifecycle_state"] not in {"ACTIVE", "DRAIN_ONLY"}:
            raise GraphContractError("PRODUCTION_RUNTIME_ACTIVATION_NOT_ADMISSIBLE")


ADVANCE_ROOM_AUTHORITY_SQL = """
insert into agent_graph_production_runtime_room_authority (
    tenant_surrogate, case_id, room_type, activation_id, room_epoch,
    room_fencing_token
)
select %s, %s, %s, %s, %s, %s
 where exists (
       select 1
         from agent_graph_production_runtime_activation activation
         join agent_graph_production_runtime_activation_lifecycle lifecycle
           on lifecycle.activation_id = activation.activation_id
         join agent_graph_production_runtime_environment_generation generation
           on generation.environment_id = activation.environment_id
        where activation.activation_id = %s
          and lifecycle.lifecycle_state in ('ACTIVE', 'DRAIN_ONLY')
          and (
              (
                  lifecycle.lifecycle_state = 'ACTIVE'
                  and activation.expires_at > clock_timestamp()
                  and generation.activation_id = activation.activation_id
                  and generation.environment_generation = activation.environment_generation
              )
              or exists (
                  select 1
                    from agent_graph_command command
                   where command.thread_id = %s
                     and command.command_id = %s
                     and command.execution_mode = 'PRODUCTION'
                     and command.activation_id = activation.activation_id
                     and command.room_fencing_token = %s
                     and command.command_hash = %s
                     and command.command_envelope_hash = %s
                     and command.registered_at < activation.expires_at
              )
          )
 )
on conflict (tenant_surrogate, case_id, room_type) do update
set activation_id = excluded.activation_id,
    room_epoch = excluded.room_epoch,
    room_fencing_token = excluded.room_fencing_token,
    updated_at = clock_timestamp()
where excluded.room_epoch > agent_graph_production_runtime_room_authority.room_epoch
   or (
       excluded.room_epoch = agent_graph_production_runtime_room_authority.room_epoch
       and (
           excluded.room_fencing_token
               > agent_graph_production_runtime_room_authority.room_fencing_token
           or (
               excluded.room_fencing_token
                   = agent_graph_production_runtime_room_authority.room_fencing_token
               and excluded.activation_id
                   = agent_graph_production_runtime_room_authority.activation_id
           )
       )
   )
returning activation_id, room_epoch, room_fencing_token
"""

LOCK_ENVIRONMENT_GENERATION_SQL = """
select environment_generation, activation_id
  from agent_graph_production_runtime_environment_generation
 where environment_id = %s
 for share
"""


class PostgresProductionRoomAuthorityRepository:
    """Keep Java's room fence separate from the Graph execution lease fence."""

    async def advance(
        self,
        connection: Any,
        *,
        authority: ProductionRuntimeAuthority,
        command: RoomGraphCommand,
        room_fencing_token: int,
        command_hash: str,
        command_envelope_hash: str,
    ) -> None:
        generation = await (
            await connection.execute(
                LOCK_ENVIRONMENT_GENERATION_SQL,
                (authority.context.environmentId,),
            )
        ).fetchone()
        if generation is None:
            raise GraphThreadBindingError("PRODUCTION_RUNTIME_ENVIRONMENT_GENERATION_UNAVAILABLE")
        await connection.execute(
            DRAIN_EXPIRED_RUNTIME_CONTEXT_SQL,
            (authority.activation_id,),
        )
        row = await (
            await connection.execute(
                ADVANCE_ROOM_AUTHORITY_SQL,
                (
                    command.tenant_surrogate,
                    command.case_id,
                    command.room_type,
                    authority.activation_id,
                    command.room_epoch,
                    room_fencing_token,
                    authority.activation_id,
                    command.thread_id,
                    command.command_id,
                    room_fencing_token,
                    command_hash,
                    command_envelope_hash,
                ),
            )
        ).fetchone()
        if row is None or (
            row["activation_id"],
            row["room_epoch"],
            row["room_fencing_token"],
        ) != (authority.activation_id, command.room_epoch, room_fencing_token):
            raise GraphThreadBindingError("PRODUCTION_RUNTIME_STALE_ROOM_FENCE")


RESERVE_SYNTHETIC_CASE_SQL = """
insert into agent_graph_production_runtime_synthetic_case_reservation (
    activation_id, slot_number, generated_case_id, fixture_set_id, fixture_set_hash
)
values (%s, %s, %s, %s, %s)
on conflict do nothing
returning activation_id
"""

LOAD_SYNTHETIC_CASE_SQL = """
select activation_id, slot_number, generated_case_id, fixture_set_id, fixture_set_hash
  from agent_graph_production_runtime_synthetic_case_reservation
 where activation_id = %s and slot_number = %s
"""


class PostgresProductionSyntheticCaseRepository:
    async def reserve(
        self,
        connection: Any,
        *,
        authority: ProductionRuntimeAuthority,
        case_id: str,
    ) -> None:
        slot = authority.synthetic_slot(case_id)
        if slot is None:
            if isinstance(authority.context.caseScope, GraphProductionExplicitCaseScope):
                return
            raise GraphThreadBindingError("PRODUCTION_RUNTIME_SYNTHETIC_CASE_NOT_ALLOWED")
        scope = authority.context.caseScope
        expected = (
            authority.activation_id,
            slot,
            case_id,
            scope.fixtureSetId,
            scope.fixtureSetHash,
        )
        await connection.execute(RESERVE_SYNTHETIC_CASE_SQL, expected)
        row = await (
            await connection.execute(
                LOAD_SYNTHETIC_CASE_SQL,
                (authority.activation_id, slot),
            )
        ).fetchone()
        if row is None or tuple(row[name] for name in (
            "activation_id",
            "slot_number",
            "generated_case_id",
            "fixture_set_id",
            "fixture_set_hash",
        )) != expected:
            raise GraphThreadBindingError("PRODUCTION_RUNTIME_GENERATED_CASE_ID_GLOBAL_CONFLICT")


class ProductionThreadIdentityResolver:
    async def resolve(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation | None = None,
        **_: Any,
    ) -> ThreadIdentity:
        if not isinstance(verified_invocation, VerifiedProductionInvocation):
            raise GraphThreadBindingError("PRODUCTION_RUNTIME_VERIFIED_AUTHORITY_REQUIRED")
        if (
            verified_invocation.request_hash != command.request_hash
            or verified_invocation.command_hash != production_runtime_command_hash(command)
        ):
            raise GraphThreadBindingError("PRODUCTION_RUNTIME_COMMAND_AUTHORITY_MISMATCH")
        scope = ActorScopeBinding.from_json(command.actor_scope.model_dump(mode="json"))
        agent_session_id = verified_invocation.claims.agent_session_id
        if command.room_type == "INTAKE":
            if agent_session_id is None:
                raise GraphThreadBindingError("PRODUCTION_RUNTIME_AGENT_SESSION_REQUIRED")
        elif agent_session_id is None:
            # Non-Intake target rooms have not yet adopted the v2 session claim. Their existing
            # deterministic binding remains isolated from the Intake authority path.
            session_hash = canonical_sha256(
                {
                    "activation_id": verified_invocation.authority.activation_id,
                    "tenant_surrogate": command.tenant_surrogate,
                    "case_id": command.case_id,
                    "room_type": command.room_type,
                    "thread_id": command.thread_id,
                    "actor_scope": command.actor_scope.model_dump(mode="json"),
                }
            )
            agent_session_id = f"p9ses.v1.{session_hash[:32]}"
        return ThreadIdentity(
            thread_id=command.thread_id,
            tenant_surrogate=command.tenant_surrogate,
            case_id=command.case_id,
            room_type=RoomType(command.room_type),
            room_epoch=command.room_epoch,
            actor_scope=scope,
            agent_session_id=agent_session_id,
            shared_session=(command.room_type == "HEARING" and command.actor_scope.actor_role == "SYSTEM"),
            graph_key=command.graph_key,
            graph_version=command.graph_version,
            checkpoint_schema_version=command.checkpoint_schema_version,
        )


class ProductionInputAuthorizer:
    """Input bytes remain outside Python authority; only the signed immutable refs are admitted."""

    async def authorize(self, *, command: RoomGraphCommand, thread: ThreadIdentity) -> None:
        if (
            command.thread_id != thread.thread_id
            or command.tenant_surrogate != thread.tenant_surrogate
            or command.case_id != thread.case_id
            or command.room_type != thread.room_type.value
            or canonical_sha256_omitting(command, "request_hash") != command.request_hash
        ):
            raise GraphThreadBindingError("PRODUCTION_RUNTIME_INPUT_AUTHORITY_MISMATCH")


__all__ = [
    "PostgresProductionActivationRepository",
    "PostgresProductionRoomAuthorityRepository",
    "PostgresProductionSyntheticCaseRepository",
    "PRODUCTION_RUNTIME_COMMAND_PATH",
    "ProductionGraphCommandEnvelope",
    "ProductionGraphResultEnvelope",
    "ProductionInputAuthorizer",
    "ProductionInvocationClaims",
    "ProductionInvocationVerifier",
    "ProductionRoomProposal",
    "ProductionRoomProposalSource",
    "ProductionRuntimeAuthority",
    "ProductionThreadIdentityResolver",
    "VerifiedProductionInvocation",
    "build_production_runtime_result_envelope",
    "production_runtime_command_hash",
]
