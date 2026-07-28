"""Fail-closed Java exchange for immutable Intake inputs and proposals."""

from __future__ import annotations

import base64
import binascii
from collections.abc import Mapping
import json
import re
from typing import Any, Literal, Self, cast
from urllib.parse import unquote, urlsplit

import httpx
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.contracts.v1.codec import canonical_sha256_omitting, canonicalize
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import ThreadRecord
from app.graph_runtime.intake_binding import (
    CanonicalIntakeProposal,
    INTAKE_EVENT_SCHEMA,
    INTAKE_OUTPUT_SCHEMA,
    INTAKE_PROPOSAL_MAX_BYTES,
    INTAKE_SNAPSHOT_SCHEMA,
    LoadedIntakePayload,
    StoredIntakeProposal,
)


INTAKE_PAYLOAD_LOAD_PATH = "/internal/graph/intake/v2/payload:load"
INTAKE_PROPOSAL_PUT_PATH = "/internal/graph/intake/v2/proposals:put"
INTAKE_SNAPSHOT_MAX_BYTES = 256 * 1024
INTAKE_EVENT_MAX_BYTES = 32 * 1024
INTAKE_LOAD_RESPONSE_MAX_BYTES = 384 * 1024
INTAKE_PUT_RESPONSE_MAX_BYTES = 16 * 1024
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class _ExchangeModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class IntakeExchangeAuthority(_ExchangeModel):
    schema_version: Literal["intake-exchange-authority.v1"] = "intake-exchange-authority.v1"
    tenant_surrogate: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    case_id: str = Field(min_length=1, max_length=64)
    room_type: Literal["INTAKE"]
    room_epoch: int = Field(ge=0)
    thread_id: str = Field(pattern=r"^grt\.v1\.[0-9a-f]{32}$")
    actor_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    actor_role: Literal["USER", "MERCHANT"]
    audience: Literal["USER", "MERCHANT"]
    actor_capabilities: tuple[str, ...] = Field(max_length=32)
    actor_scope_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    agent_session_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    command_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    logical_run_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    attempt_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    request_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    graph_key: Literal["intake.v2", "all-rooms.target-e2e.v1"]
    graph_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    checkpoint_schema_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    process_revision: int = Field(ge=0)
    stage_code: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    stage_sequence: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_actor_scope(self) -> Self:
        if self.actor_role != self.audience:
            raise ValueError("private Intake exchange actor and audience must match")
        if len(self.actor_capabilities) != len(set(self.actor_capabilities)) or any(
            _IDENTIFIER.fullmatch(value) is None for value in self.actor_capabilities
        ):
            raise ValueError("Intake exchange capabilities are invalid")
        return self


class IntakeExchangeObjectReference(_ExchangeModel):
    artifact_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    schema_version: Literal["intake-domain-snapshot.v2", "intake-turn-event.v2"]
    uri: str = Field(min_length=1, max_length=1024)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    size_bytes: int = Field(gt=0, le=INTAKE_SNAPSHOT_MAX_BYTES)

    @model_validator(mode="after")
    def validate_schema_size(self) -> Self:
        maximum = (
            INTAKE_SNAPSHOT_MAX_BYTES
            if self.schema_version == INTAKE_SNAPSHOT_SCHEMA
            else INTAKE_EVENT_MAX_BYTES
        )
        if self.size_bytes > maximum:
            raise ValueError("Intake exchange object exceeds its schema limit")
        _require_immutable_uri(self.uri)
        return self


class IntakePayloadLoadRequest(_ExchangeModel):
    schema_version: Literal["intake-payload-load-request.v1"] = "intake-payload-load-request.v1"
    authority: IntakeExchangeAuthority
    object_ref: IntakeExchangeObjectReference


class IntakePayloadLoadReceipt(_ExchangeModel):
    schema_version: Literal["intake-payload-load-receipt.v1"]
    artifact_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    content_schema_version: Literal["intake-domain-snapshot.v2", "intake-turn-event.v2"]
    uri: str = Field(min_length=1, max_length=1024)
    object_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    size_bytes: int = Field(gt=0, le=INTAKE_SNAPSHOT_MAX_BYTES)

    @model_validator(mode="after")
    def validate_uri(self) -> Self:
        _require_immutable_uri(self.uri)
        return self


class IntakePayloadLoadResponse(_ExchangeModel):
    schema_version: Literal["intake-payload-load-response.v1"]
    authority: IntakeExchangeAuthority
    receipt: IntakePayloadLoadReceipt
    canonical_payload_base64: str = Field(min_length=4, max_length=350_000)


class IntakeProposalDocument(_ExchangeModel):
    artifact_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    schema_version: Literal["intake-turn-proposal.v2"]
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    size_bytes: int = Field(gt=0, le=INTAKE_PROPOSAL_MAX_BYTES)
    canonical_payload_base64: str = Field(min_length=4, max_length=90_000)


class IntakeProposalPutRequest(_ExchangeModel):
    schema_version: Literal["intake-proposal-put-request.v1"] = "intake-proposal-put-request.v1"
    authority: IntakeExchangeAuthority
    idempotency_key: str = Field(min_length=1, max_length=512)
    checkpoint_ns: str = Field(max_length=128)
    checkpoint_id: str = Field(min_length=1, max_length=128)
    cognitive_revision: int = Field(ge=1)
    proposal: IntakeProposalDocument


class IntakeProposalPutReceipt(_ExchangeModel):
    schema_version: Literal["intake-proposal-put-receipt.v1"]
    artifact_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    content_schema_version: Literal["intake-turn-proposal.v2"]
    uri: str = Field(min_length=1, max_length=1024)
    object_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    size_bytes: int = Field(gt=0, le=INTAKE_PROPOSAL_MAX_BYTES)

    @model_validator(mode="after")
    def validate_uri(self) -> Self:
        _require_immutable_uri(self.uri)
        return self


class IntakeProposalPutResponse(_ExchangeModel):
    schema_version: Literal["intake-proposal-put-response.v1"]
    authority: IntakeExchangeAuthority
    checkpoint_ns: str = Field(max_length=128)
    checkpoint_id: str = Field(min_length=1, max_length=128)
    cognitive_revision: int = Field(ge=1)
    receipt: IntakeProposalPutReceipt


class JavaIntakeExchangeClient:
    """HTTP implementation whose service credential never grants URI-only access."""

    def __init__(
        self,
        *,
        java_api_service_url: str,
        java_service_secret: str,
        timeout_seconds: float = 5.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        parsed = urlsplit(java_api_service_url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.path not in {"", "/"}
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("java_api_service_url must be a strict internal service origin")
        if len(java_service_secret) < 16 or timeout_seconds <= 0 or timeout_seconds > 30:
            raise ValueError("Intake exchange client configuration is invalid")
        self._origin = java_api_service_url.rstrip("/")
        self._secret = java_service_secret
        self._timeout = timeout_seconds
        self._transport = transport

    async def load(self, execution: GatewayExecution) -> LoadedIntakePayload:
        command = execution.admission.command
        reference = command.event_ref or command.domain_snapshot_ref
        request = IntakePayloadLoadRequest(
            authority=_authority(execution),
            object_ref=IntakeExchangeObjectReference.model_validate(
                reference.model_dump(mode="json")
            ),
        )
        response = IntakePayloadLoadResponse.model_validate(
            await self._post(
                INTAKE_PAYLOAD_LOAD_PATH,
                request.model_dump(mode="json"),
                maximum_bytes=INTAKE_LOAD_RESPONSE_MAX_BYTES,
            )
        )
        if response.authority != request.authority:
            raise GraphContractError("Intake payload response authority differs from its request")
        receipt = response.receipt
        expected = request.object_ref
        if (
            receipt.artifact_id,
            receipt.content_schema_version,
            receipt.uri,
            receipt.sha256,
            receipt.size_bytes,
        ) != (
            expected.artifact_id,
            expected.schema_version,
            expected.uri,
            expected.sha256,
            expected.size_bytes,
        ):
            raise GraphContractError("Intake payload receipt differs from its exact reference")
        payload = _decode_canonical_payload(
            response.canonical_payload_base64,
            maximum_bytes=_maximum_input_bytes(expected.schema_version),
        )
        if len(payload) != receipt.size_bytes:
            raise GraphContractError("Intake payload bytes differ from the receipt size")
        document = _canonical_json_document(payload)
        hash_field = (
            "snapshot_hash" if expected.schema_version == INTAKE_SNAPSHOT_SCHEMA else "event_hash"
        )
        if canonical_sha256_omitting(document, hash_field) != receipt.sha256:
            raise GraphContractError("Intake payload bytes differ from the receipt hash")
        return LoadedIntakePayload(
            artifact_id=receipt.artifact_id,
            schema_version=receipt.content_schema_version,
            uri=receipt.uri,
            sha256=receipt.sha256,
            size_bytes=receipt.size_bytes,
            object_version=receipt.object_version,
            canonical_payload=payload,
        )

    async def put(
        self,
        execution: GatewayExecution,
        *,
        proposal: CanonicalIntakeProposal,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> StoredIntakeProposal:
        command = execution.admission.command
        if proposal.schema_version != INTAKE_OUTPUT_SCHEMA:
            raise GraphContractError("Intake proposal store received another schema")
        if len(proposal.canonical_payload) != proposal.size_bytes:
            raise GraphContractError("Intake proposal bytes differ from their declared size")
        document = _canonical_json_document(proposal.canonical_payload)
        if canonical_sha256_omitting(document, "proposal_hash") != proposal.sha256:
            raise GraphContractError("Intake proposal bytes differ from their declared hash")
        _require_proposal_execution_binding(
            document,
            execution,
            cognitive_revision=cognitive_revision,
        )
        request = IntakeProposalPutRequest(
            authority=_authority(execution),
            idempotency_key=(
                f"intake.proposal:{command.thread_id}:{command.command_id}:{proposal.sha256}"
            ),
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=cognitive_revision,
            proposal=IntakeProposalDocument(
                artifact_id=proposal.artifact_id,
                schema_version=INTAKE_OUTPUT_SCHEMA,
                sha256=proposal.sha256,
                size_bytes=proposal.size_bytes,
                canonical_payload_base64=base64.b64encode(proposal.canonical_payload).decode(
                    "ascii"
                ),
            ),
        )
        response = IntakeProposalPutResponse.model_validate(
            await self._post(
                INTAKE_PROPOSAL_PUT_PATH,
                request.model_dump(mode="json"),
                maximum_bytes=INTAKE_PUT_RESPONSE_MAX_BYTES,
            )
        )
        if (
            response.authority != request.authority
            or response.checkpoint_ns != checkpoint_ns
            or response.checkpoint_id != checkpoint_id
            or response.cognitive_revision != cognitive_revision
        ):
            raise GraphContractError("Intake proposal receipt lost its terminal authority")
        receipt = response.receipt
        if (
            receipt.artifact_id,
            receipt.content_schema_version,
            receipt.sha256,
            receipt.size_bytes,
        ) != (
            proposal.artifact_id,
            proposal.schema_version,
            proposal.sha256,
            proposal.size_bytes,
        ):
            raise GraphContractError("Intake proposal receipt differs from canonical bytes")
        return StoredIntakeProposal(
            artifact_id=receipt.artifact_id,
            schema_version=receipt.content_schema_version,
            uri=receipt.uri,
            object_version=receipt.object_version,
            sha256=receipt.sha256,
            size_bytes=receipt.size_bytes,
        )

    async def _post(
        self,
        path: str,
        payload: Mapping[str, Any],
        *,
        maximum_bytes: int,
    ) -> dict[str, Any]:
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Service-Secret": self._secret,
        }
        try:
            async with httpx.AsyncClient(
                base_url=self._origin,
                timeout=self._timeout,
                transport=self._transport,
                follow_redirects=False,
            ) as client:
                async with client.stream(
                    "POST",
                    path,
                    headers=headers,
                    content=canonicalize(dict(payload)),
                ) as response:
                    if response.status_code != 200:
                        raise GraphContractError("Intake exchange rejected the bound request")
                    media_type = response.headers.get("content-type", "").split(";", 1)[0]
                    if media_type.strip().lower() != "application/json":
                        raise GraphContractError("Intake exchange returned another media type")
                    declared = response.headers.get("content-length")
                    if declared is not None and (
                        not declared.isdigit() or int(declared) > maximum_bytes
                    ):
                        raise GraphContractError("Intake exchange response exceeds its limit")
                    chunks: list[bytes] = []
                    total = 0
                    async for chunk in response.aiter_bytes():
                        total += len(chunk)
                        if total > maximum_bytes:
                            raise GraphContractError("Intake exchange response exceeds its limit")
                        chunks.append(chunk)
        except GraphContractError:
            raise
        except httpx.HTTPError as error:
            raise GraphContractError("Intake exchange transport failed") from error
        try:
            value = json.loads(b"".join(chunks), object_pairs_hook=_unique_object)
        except (UnicodeDecodeError, ValueError) as error:
            raise GraphContractError(
                "Intake exchange response is not unique-member JSON"
            ) from error
        if not isinstance(value, dict):
            raise GraphContractError("Intake exchange response is not an object")
        return cast(dict[str, Any], value)


def _authority(execution: GatewayExecution) -> IntakeExchangeAuthority:
    command = execution.admission.command
    record = execution.thread_record
    if not isinstance(record, ThreadRecord) or record.identity != execution.admission.thread:
        raise GraphContractError("Intake exchange has no authoritative thread record")
    identity = record.identity
    scope = command.actor_scope
    if identity.actor_scope.to_json() != scope.model_dump(mode="json"):
        raise GraphContractError("Intake exchange actor authority differs from the thread")
    return IntakeExchangeAuthority(
        tenant_surrogate=command.tenant_surrogate,
        case_id=command.case_id,
        room_type="INTAKE",
        room_epoch=command.room_epoch,
        thread_id=command.thread_id,
        actor_id=scope.actor_id,
        actor_role=cast(Any, scope.actor_role),
        audience=cast(Any, scope.audience),
        actor_capabilities=scope.capabilities,
        actor_scope_hash=identity.actor_scope_hash,
        agent_session_id=identity.agent_session_id,
        command_id=command.command_id,
        logical_run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        request_hash=command.request_hash,
        graph_key=command.graph_key,
        graph_version=command.graph_version,
        checkpoint_schema_version=command.checkpoint_schema_version,
        process_revision=command.process_revision,
        stage_code=command.stage_code,
        stage_sequence=command.stage_sequence,
    )


def _maximum_input_bytes(schema_version: str) -> int:
    if schema_version == INTAKE_SNAPSHOT_SCHEMA:
        return INTAKE_SNAPSHOT_MAX_BYTES
    if schema_version == INTAKE_EVENT_SCHEMA:
        return INTAKE_EVENT_MAX_BYTES
    raise GraphContractError("Intake payload schema is not loadable")


def _require_proposal_execution_binding(
    proposal: Mapping[str, Any],
    execution: GatewayExecution,
    *,
    cognitive_revision: int,
) -> None:
    command = execution.admission.command
    record = execution.thread_record
    invocation = command.invocation_context
    registry = execution.admission.registry.binding
    target_candidate = execution.fence.execution_lane.value == "TARGET_E2E_CANDIDATE"
    expected = {
        "command_id": command.command_id,
        "logical_run_id": command.logical_run_id,
        "attempt_id": command.attempt_id,
        "case_id": command.case_id,
        "room_epoch": command.room_epoch,
        "thread_id": command.thread_id,
        "actor_scope_hash": record.identity.actor_scope_hash,
        "agent_session_id": record.identity.agent_session_id,
        "cognitive_revision": cognitive_revision,
        "profile_versions": {
            "graph_version": command.graph_version,
            "checkpoint_schema_version": command.checkpoint_schema_version,
            "prompt_version": invocation.prompt_profile_id,
            "model_profile_id": invocation.model_profile_id,
            # The all-room envelope carries the wrapper schema, while the
            # immutable Intake artifact remains the private v2 proposal.
            "output_schema_version": (
                INTAKE_OUTPUT_SCHEMA
                if target_candidate
                else invocation.output_schema_version
            ),
            "policy_version": invocation.policy_version,
            "guardrail_version": invocation.guardrail_version,
            "tool_policy_version": (
                "no-tools.v1"
                if target_candidate
                else registry.tool_policy_version
            ),
        },
    }
    if any(proposal.get(field) != value for field, value in expected.items()):
        raise GraphContractError("Intake proposal differs from its exact execution binding")


def _decode_canonical_payload(value: str, *, maximum_bytes: int) -> bytes:
    try:
        payload = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error) as error:
        raise GraphContractError("Intake exchange payload is not canonical base64") from error
    if not payload or len(payload) > maximum_bytes:
        raise GraphContractError("Intake exchange payload exceeds its schema limit")
    return payload


def _canonical_json_document(payload: bytes) -> dict[str, Any]:
    try:
        value = json.loads(payload, object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, ValueError) as error:
        raise GraphContractError("Intake exchange payload is not unique-member JSON") from error
    if not isinstance(value, dict) or canonicalize(value) != payload:
        raise GraphContractError("Intake exchange payload is not canonical JSON")
    return cast(dict[str, Any], value)


def _require_immutable_uri(value: str) -> None:
    parsed = urlsplit(value)
    path = unquote(parsed.path)
    if (
        parsed.scheme not in {"s3", "minio"}
        or not parsed.netloc
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or not path.startswith("/")
        or path.endswith("/")
        or "\\" in path
        or "//" in path
        or any(part in {"", ".", ".."} for part in path.split("/")[1:])
    ):
        raise ValueError("Intake exchange URI is not an immutable object URI")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, member in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON member: {key}")
        value[key] = member
    return value


__all__ = [
    "INTAKE_PAYLOAD_LOAD_PATH",
    "INTAKE_PROPOSAL_PUT_PATH",
    "IntakeExchangeAuthority",
    "IntakePayloadLoadRequest",
    "IntakePayloadLoadResponse",
    "IntakeProposalPutRequest",
    "IntakeProposalPutResponse",
    "JavaIntakeExchangeClient",
]
