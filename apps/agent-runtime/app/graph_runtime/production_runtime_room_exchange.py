"""Fail-closed Java exchange for non-Intake production-runtime room material.

The Python process receives neither Domain PostgreSQL credentials nor direct
object-store credentials.  A capability is minted for one already-admitted
``GatewayExecution`` and is carried on every read and proposal write.  Java
must validate that capability against the immutable command envelope before it
resolves an object reference or stores a proposal.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
from collections.abc import Mapping
import hashlib
import json
import re
from typing import Any, cast
from urllib.parse import urlsplit

import httpx

from app.agents.hearing_flow import HearingFlowWorkflows
from app.contracts.v1.codec import canonicalize
from app.contracts.v1.models import (
    MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT,
    SnapshotRef,
)
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import ThreadRecord
from app.graph_runtime.persistence_models import GraphGatewayMode
from app.graph_runtime.production_runtime_room_adapters import ProductionImmutableObjectStore
from app.graphs.hearing.contracts import HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.state import HearingGraphInvocation
from app.graphs.hearing.production_runtime import HearingProductionLoadedInvocation
from app.harness.invocation_context import AgentInvocationContext
from app.schemas import (
    HearingBatchEvidenceAssessment,
    HearingEvidenceSynthesisRequest,
    HearingEvidenceSynthesisResult,
    HearingEvidenceRequestsRequest,
    HearingEvidenceRequestsResult,
    HearingIntakeQuestionsRequestV4,
    HearingIntakeQuestionsResultV5,
    HearingIntakeSynthesisRequestV4,
    HearingIntakeSynthesisResultV5,
    HearingJudgeV1Request,
    HearingJudgeV1Result,
    HearingJudgeV2Request,
    HearingJudgeV2Result,
    HearingJuryReviewRequest,
    HearingJuryReviewResult,
)


PRODUCTION_RUNTIME_ROOM_OBJECT_LOAD_PATH = "/internal/graph/production-runtime/rooms/object:load"
PRODUCTION_RUNTIME_ROOM_PROPOSAL_PUT_PATH = "/internal/graph/production-runtime/rooms/proposal:put"
_ALLOWED_EXCHANGE_PATHS = frozenset(
    {PRODUCTION_RUNTIME_ROOM_OBJECT_LOAD_PATH, PRODUCTION_RUNTIME_ROOM_PROPOSAL_PUT_PATH}
)
_MAX_LOAD_BYTES = 512 * 1024
_MAX_PUT_BYTES = 64 * 1024
_PROPOSAL_REF = re.compile(
    r"^urn:production-runtime:proposal:(?:evidence|hearing|review):[0-9a-f]{64}$"
)

_HEARING_TYPES = {
    HearingOperation.INTAKE_QUESTIONS: (
        HearingIntakeQuestionsRequestV4,
        HearingIntakeQuestionsResultV5,
    ),
    HearingOperation.INTAKE_SYNTHESIS: (
        HearingIntakeSynthesisRequestV4,
        HearingIntakeSynthesisResultV5,
    ),
    HearingOperation.EVIDENCE_REQUESTS: (
        HearingEvidenceRequestsRequest,
        HearingEvidenceRequestsResult,
    ),
    HearingOperation.EVIDENCE_SYNTHESIS: (
        HearingEvidenceSynthesisRequest,
        HearingEvidenceSynthesisResult,
    ),
    HearingOperation.JUDGE_V1: (HearingJudgeV1Request, HearingJudgeV1Result),
    HearingOperation.JURY_REVIEW: (HearingJuryReviewRequest, HearingJuryReviewResult),
    HearingOperation.JUDGE_V2: (HearingJudgeV2Request, HearingJudgeV2Result),
}


class JavaProductionRoomExchange:
    """Create command-scoped object-store capabilities, never a URI-only client."""

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
            or len(java_service_secret) < 16
            or timeout_seconds <= 0
            or timeout_seconds > 30
        ):
            raise ValueError("production-runtime room exchange configuration is invalid")
        self._origin = java_api_service_url.rstrip("/")
        self._secret = java_service_secret
        self._timeout = timeout_seconds
        self._transport = transport
        self._client: httpx.AsyncClient | None = None
        self._lifecycle = asyncio.Condition()
        self._active_requests = 0
        self._idle = asyncio.Event()
        self._idle.set()
        self._closing = False
        self._closed = False
        self._close_task: asyncio.Task[None] | None = None

    async def aopen(self) -> None:
        """Construct the process-lifetime transport before accepting commands."""

        async with self._lifecycle:
            if self._closing or self._closed:
                raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_CLOSED")
            if self._client is None:
                self._client = self._build_client()

    async def aclose(self) -> None:
        """Drain active exchanges and close the shared transport exactly once."""

        async with self._lifecycle:
            if self._close_task is None:
                self._closing = True
                self._close_task = asyncio.create_task(self._close_when_idle())
            close_task = self._close_task
        assert close_task is not None
        await asyncio.shield(close_task)

    async def _close_when_idle(self) -> None:
        try:
            await self._idle.wait()
            client = self._client
            if client is not None:
                await client.aclose()
        finally:
            async with self._lifecycle:
                self._closed = True
                self._closing = False
                self._lifecycle.notify_all()

    async def _borrow_client(self) -> httpx.AsyncClient:
        async with self._lifecycle:
            if self._closing or self._closed:
                raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_CLOSED")
            client = self._client
            if client is None:
                # Direct consumers remain usable outside the application lifecycle.
                client = self._build_client()
                self._client = client
            if self._active_requests == 0:
                self._idle.clear()
            self._active_requests += 1
            return client

    def _build_client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=self._origin,
            timeout=self._timeout,
            transport=self._transport,
            follow_redirects=False,
        )

    def _return_client(self) -> None:
        self._active_requests -= 1
        if self._active_requests == 0:
            self._idle.set()

    def for_execution(self, execution: GatewayExecution) -> ProductionImmutableObjectStore:
        return _ScopedJavaProductionRoomExchange(self, _authority(execution))

    async def _post(
        self,
        path: str,
        payload: Mapping[str, Any],
        *,
        maximum_bytes: int,
    ) -> dict[str, Any]:
        if path not in _ALLOWED_EXCHANGE_PATHS:
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_PATH_REJECTED")
        headers = {
            "Accept": "application/json",
            "Accept-Encoding": "identity",
            "Content-Type": "application/json",
            "X-Service-Secret": self._secret,
        }
        client = await self._borrow_client()
        try:
            try:
                response = await client.post(
                    path,
                    headers=headers,
                    content=canonicalize(dict(payload)),
                )
            except httpx.HTTPError as error:
                raise GraphContractError(
                    "PRODUCTION_RUNTIME_ROOM_EXCHANGE_TRANSPORT_FAILED"
                ) from error
            if response.status_code != 200:
                raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_REJECTED")
            if response.headers.get("content-encoding", "identity").lower() != "identity":
                raise GraphContractError(
                    "PRODUCTION_RUNTIME_ROOM_EXCHANGE_CONTENT_ENCODING_INVALID"
                )
            if (
                response.headers.get("content-type", "").split(";", 1)[0].lower()
                != "application/json"
            ):
                raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_MEDIA_TYPE_INVALID")
            if len(response.content) > maximum_bytes:
                raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_RESPONSE_TOO_LARGE")
            try:
                value = json.loads(response.content, object_pairs_hook=_unique_object)
            except (UnicodeDecodeError, ValueError) as error:
                raise GraphContractError(
                    "PRODUCTION_RUNTIME_ROOM_EXCHANGE_RESPONSE_INVALID"
                ) from error
            if not isinstance(value, dict):
                raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_RESPONSE_INVALID")
            return cast(dict[str, Any], value)
        finally:
            self._return_client()


class DeterministicProductionHearingInvocationDecoder:
    """Decode a Java-published, immutable fixture invocation with no model egress.

    The preproduction fixture result is part of the canonical immutable input.
    It is not an in-memory fallback: malformed or missing fixture material is
    rejected before the LangGraph Hearing state machine begins.
    """

    def decode(
        self,
        *,
        execution: GatewayExecution,
        snapshot_payload: bytes,
        event_payload: bytes | None,
    ) -> HearingProductionLoadedInvocation:
        document = _canonical_document(snapshot_payload, "PRODUCTION_RUNTIME_HEARING_INVOCATION_INVALID")
        if document.get("schema_version") != "production-runtime-hearing-invocation.v1":
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_INVOCATION_REQUIRED")
        try:
            operation = HearingOperation(document["operation"])
            request_type, result_type = _HEARING_TYPES[operation]
            request = request_type.model_validate(document["request"])
            fixture_result = result_type.model_validate(document["fixture_proposal"])
        except (KeyError, TypeError, ValueError) as error:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_INVOCATION_INVALID") from error
        command = execution.admission.command
        if (
            request.case_id != command.case_id
            or request.stage_sequence != command.stage_sequence
            or fixture_result.case_id != request.case_id
            or fixture_result.workflow_id != request.workflow_id
            or fixture_result.stage_sequence != request.stage_sequence
        ):
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_INVOCATION_BINDING_MISMATCH")
        barrier = document.get("shared_barrier_receipt_hash")
        if barrier is not None and (
            not isinstance(barrier, str)
            or len(barrier) != 64
            or any(c not in "0123456789abcdef" for c in barrier)
        ):
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_BARRIER_INVALID")

        def execute(value: Any) -> Any:
            if value is not request:
                raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_REQUEST_IDENTITY_INVALID")
            return fixture_result

        invocation = HearingGraphInvocation(request=request, execute=execute)
        if operation is HearingOperation.EVIDENCE_SYNTHESIS:
            raw_results = document.get("fixture_work_results")
            if not isinstance(raw_results, Mapping):
                raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_WORK_RESULTS_REQUIRED")
            work_results = {
                key: HearingBatchEvidenceAssessment.model_validate(value)
                for key, value in raw_results.items()
                if isinstance(key, str)
            }
            if len(work_results) != len(raw_results):
                raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_WORK_RESULTS_INVALID")

            def plan(value: Any) -> list[str]:
                if value is not request:
                    raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_REQUEST_IDENTITY_INVALID")
                return sorted(work_results)

            def assess(value: Any, key: str) -> HearingBatchEvidenceAssessment:
                if value is not request or key not in work_results:
                    raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_WORK_RESULTS_INVALID")
                return work_results[key]

            def project(value: Any, results: Mapping[str, Mapping[str, Any]]) -> Any:
                if value is not request or set(results) != set(work_results):
                    raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_WORK_RESULTS_INVALID")
                return fixture_result

            invocation = HearingGraphInvocation(
                request=request,
                execute=execute,
                plan_work_items=plan,
                execute_work_item=assess,
                execute_with_work_results=project,
            )
        event = execution.admission.command.event_ref
        if event_payload is not None and event is None:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_EVENT_BINDING_INVALID")
        return HearingProductionLoadedInvocation(
            operation=operation,
            request=request,
            invocation=invocation,
            snapshot_uri=command.domain_snapshot_ref.uri,
            snapshot_hash=command.domain_snapshot_ref.sha256,
            event_uri=event.uri if event is not None else None,
            event_hash=event.sha256 if event is not None else None,
            shared_barrier_receipt_hash=barrier,
        )


class GovernedProductionHearingInvocationDecoder:
    """Decode the V4 invocation and retain its signed Harness sidecar."""

    def __init__(self, workflows: HearingFlowWorkflows) -> None:
        if not callable(getattr(workflows, "production_runtime_invocation", None)):
            raise ValueError("governed Hearing workflow is required")
        self._workflows = workflows

    def decode(
        self,
        *,
        execution: GatewayExecution,
        snapshot_payload: bytes,
        event_payload: bytes | None,
    ) -> HearingProductionLoadedInvocation:
        document = _canonical_document(snapshot_payload, "PRODUCTION_RUNTIME_HEARING_INVOCATION_INVALID")
        if document.get("schema_version") != "production-runtime-hearing-invocation.v4" or set(
            document
        ) != {
            "schema_version",
            "operation",
            "shared_barrier_receipt_hash",
            "request",
        }:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_INVOCATION_REQUIRED")
        try:
            operation = HearingOperation(document["operation"])
            request_type, _ = _HEARING_TYPES[operation]
            request = request_type.model_validate(document["request"])
        except (KeyError, TypeError, ValueError) as error:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_INVOCATION_INVALID") from error
        command = execution.admission.command
        if request.case_id != command.case_id or request.stage_sequence != command.stage_sequence:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_INVOCATION_BINDING_MISMATCH")
        barrier = document["shared_barrier_receipt_hash"]
        if (
            not isinstance(barrier, str)
            or len(barrier) != 64
            or any(character not in "0123456789abcdef" for character in barrier)
        ):
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_BARRIER_INVALID")
        agent_context = build_hearing_agent_context(execution)
        governed = self._workflows.production_runtime_invocation(
            operation,
            request,
            agent_context=agent_context,
        )
        if not isinstance(governed, HearingGraphInvocation) or governed.request is not request:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_WORKFLOW_BINDING_INVALID")
        if governed.agent_context != agent_context:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_AGENT_CONTEXT_LOST")
        event = command.event_ref
        if event_payload is not None and event is None:
            raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_EVENT_BINDING_INVALID")
        return HearingProductionLoadedInvocation(
            operation=operation,
            request=request,
            invocation=governed,
            snapshot_uri=command.domain_snapshot_ref.uri,
            snapshot_hash=command.domain_snapshot_ref.sha256,
            event_uri=event.uri if event is not None else None,
            event_hash=event.sha256 if event is not None else None,
            shared_barrier_receipt_hash=barrier,
        )


def build_hearing_agent_context(execution: GatewayExecution) -> AgentInvocationContext:
    """Project command/thread authority into the shared Hearing Harness context."""

    command = execution.admission.command
    record = execution.thread_record
    if not isinstance(record, ThreadRecord) or record.identity != execution.admission.thread:
        raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_THREAD_AUTHORITY_INVALID")
    identity = record.identity
    actor = identity.actor_scope
    role = actor.actor_role.value
    permission_level = {
        "USER": "PARTY_USER",
        "MERCHANT": "PARTY_MERCHANT",
        "SYSTEM": "SYSTEM_ALL",
    }.get(role)
    if permission_level is None:
        raise HearingGraphContractError("PRODUCTION_RUNTIME_HEARING_ACTOR_ROLE_INVALID")
    invocation = command.invocation_context
    access_session_id = f"ACCESS_{identity.actor_scope_hash[:32]}"
    conversation_scope = ":".join(
        (
            command.tenant_surrogate,
            command.case_id,
            "HEARING",
            str(command.room_epoch),
            role,
            invocation.agent_profile_id,
            invocation.prompt_profile_id,
            access_session_id,
        )
    )
    return AgentInvocationContext.model_validate(
        {
            "tenant_id": command.tenant_surrogate,
            "case_id": command.case_id,
            "room_type": "HEARING",
            "actor_id": actor.actor_id,
            "actor_role": role,
            "access_session_id": access_session_id,
            "permission_level": permission_level,
            "permission_scopes": sorted(actor.capabilities),
            "agent_key": invocation.agent_profile_id,
            "agent_invocation_id": command.attempt_id,
            "agent_session_id": identity.agent_session_id,
            "conversation_scope": conversation_scope,
            "scope_type": "ROOM_SHARED",
            "allowed_actor_ids": [actor.actor_id],
            "allowed_actor_roles": [role],
            "prompt_profile_id": invocation.prompt_profile_id,
            "memory_policy_id": "MEMEO_DEFAULT",
            "model_profile_id": invocation.model_profile_id,
            "output_schema_version": invocation.output_schema_version,
            "policy_version": invocation.policy_version,
            "guardrail_version": invocation.guardrail_version,
            "tool_capabilities": list(invocation.tool_capabilities),
            "retry_budget": {
                "provider_attempts_remaining": min(
                    command.retry_budget.provider_attempts_remaining,
                    MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT,
                ),
                "activity_attempts_remaining": (
                    command.retry_budget.activity_attempts_remaining
                ),
                "repairs_remaining": command.retry_budget.repairs_remaining,
            },
            "deadline_at": command.deadline_at,
            "traceparent": command.traceparent,
        }
    )

class _ScopedJavaProductionRoomExchange:
    def __init__(self, exchange: JavaProductionRoomExchange, authority: dict[str, Any]) -> None:
        self._exchange = exchange
        self._authority = authority

    async def load(self, reference: SnapshotRef) -> bytes:
        request = {
            "schema_version": "production-runtime-room-object-load-request.v1",
            "authority": self._authority,
            "object_ref": reference.model_dump(mode="json"),
        }
        response = await self._exchange._post(
            PRODUCTION_RUNTIME_ROOM_OBJECT_LOAD_PATH,
            request,
            maximum_bytes=_MAX_LOAD_BYTES,
        )
        if (
            response.get("schema_version") != "production-runtime-room-object-load-response.v1"
            or response.get("authority") != self._authority
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_OBJECT_RECEIPT_INVALID")
        receipt = response.get("receipt")
        if not isinstance(receipt, Mapping) or any(
            receipt.get(field) != value
            for field, value in reference.model_dump(mode="json").items()
            if field != "size_bytes"
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_OBJECT_RECEIPT_INVALID")
        encoded = response.get("canonical_payload_base64")
        if not isinstance(encoded, str):
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_OBJECT_PAYLOAD_INVALID")
        try:
            payload = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as error:
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_OBJECT_PAYLOAD_INVALID") from error
        receipt_size = receipt.get("size_bytes")
        if (
            not payload
            or not isinstance(receipt_size, int)
            or isinstance(receipt_size, bool)
            or receipt_size != len(payload)
            or len(payload) > reference.size_bytes
            or (
                reference.schema_version != "production-runtime-evidence-asset.v1"
                and len(payload) != reference.size_bytes
            )
            or hashlib.sha256(payload).hexdigest() != reference.sha256
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_OBJECT_BINDING_MISMATCH")
        return payload

    async def put(
        self,
        *,
        execution: GatewayExecution,
        proposal_id: str,
        schema_version: str,
        payload: bytes,
        payload_hash: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> str:
        if _authority(execution) != self._authority:
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_AUTHORITY_MISMATCH")
        return await self._put(
            proposal_id=proposal_id,
            schema_version=schema_version,
            payload=payload,
            payload_hash=payload_hash,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=cognitive_revision,
        )

    async def put_content_addressed(
        self,
        *,
        proposal_id: str,
        schema_version: str,
        payload: bytes,
        payload_hash: str,
    ) -> str:
        return await self._put(
            proposal_id=proposal_id,
            schema_version=schema_version,
            payload=payload,
            payload_hash=payload_hash,
            checkpoint_ns="",
            checkpoint_id="content-addressed",
            cognitive_revision=1,
        )

    async def _put(
        self,
        *,
        proposal_id: str,
        schema_version: str,
        payload: bytes,
        payload_hash: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> str:
        if not payload or hashlib.sha256(payload).hexdigest() != payload_hash:
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_PROPOSAL_HASH_INVALID")
        request = {
            "schema_version": "production-runtime-room-proposal-put-request.v1",
            "authority": self._authority,
            "proposal": {
                "proposal_id": proposal_id,
                "schema_version": schema_version,
                "sha256": payload_hash,
                "size_bytes": len(payload),
                "canonical_payload_base64": base64.b64encode(payload).decode("ascii"),
            },
            "checkpoint_ns": checkpoint_ns,
            "checkpoint_id": checkpoint_id,
            "cognitive_revision": cognitive_revision,
        }
        response = await self._exchange._post(
            PRODUCTION_RUNTIME_ROOM_PROPOSAL_PUT_PATH,
            request,
            maximum_bytes=_MAX_PUT_BYTES,
        )
        if (
            response.get("schema_version") != "production-runtime-room-proposal-put-response.v1"
            or response.get("authority") != self._authority
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_PROPOSAL_RECEIPT_INVALID")
        receipt = response.get("receipt")
        if not isinstance(receipt, Mapping) or (
            receipt.get("proposal_id"),
            receipt.get("schema_version"),
            receipt.get("sha256"),
            receipt.get("size_bytes"),
        ) != (proposal_id, schema_version, payload_hash, len(payload)):
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_PROPOSAL_RECEIPT_INVALID")
        payload_ref = receipt.get("payload_ref")
        if not isinstance(payload_ref, str) or _PROPOSAL_REF.fullmatch(payload_ref) is None:
            raise GraphContractError("PRODUCTION_RUNTIME_ROOM_PROPOSAL_RECEIPT_INVALID")
        return payload_ref


def _authority(execution: GatewayExecution) -> dict[str, Any]:
    command = execution.admission.command
    record = execution.thread_record
    binding = execution.admission.binding
    fence = execution.fence
    if (
        not isinstance(record, ThreadRecord)
        or record.identity != execution.admission.thread
        or fence.execution_lane is not GraphGatewayMode.PRODUCTION
        or execution.admission.candidate_authority is None
        or command.room_type not in {"EVIDENCE", "HEARING", "REVIEW"}
        or command.graph_key != "all-rooms.production-runtime.v2"
        or command.graph_version != "production-runtime-graph.2026-08-18.3"
        or command.checkpoint_schema_version != "production-runtime-checkpoint.v2"
    ):
        raise GraphContractError("PRODUCTION_RUNTIME_ROOM_EXCHANGE_EXECUTION_REQUIRED")
    return {
        "schema_version": "production-runtime-room-exchange-authority.v1",
        "activation_id": binding.activation_id,
        "room_fencing_token": binding.room_fencing_token,
        "command_hash": binding.command_hash,
        "command_envelope_hash": binding.command_envelope_hash,
        "tenant_surrogate": command.tenant_surrogate,
        "case_id": command.case_id,
        "room_type": command.room_type,
        "room_epoch": command.room_epoch,
        "thread_id": command.thread_id,
        "command_id": command.command_id,
        "logical_run_id": command.logical_run_id,
        "attempt_id": command.attempt_id,
        "request_hash": command.request_hash,
        "graph_key": command.graph_key,
        "graph_version": command.graph_version,
        "checkpoint_schema_version": command.checkpoint_schema_version,
        "process_revision": command.process_revision,
        "stage_code": command.stage_code,
        "stage_sequence": command.stage_sequence,
    }


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, member in pairs:
        if key in value:
            raise ValueError("duplicate JSON member")
        value[key] = member
    return value


def _canonical_document(payload: bytes, code: str) -> dict[str, Any]:
    try:
        value = json.loads(payload, object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, ValueError) as error:
        raise HearingGraphContractError(code) from error
    if not isinstance(value, dict) or canonicalize(value) != payload:
        raise HearingGraphContractError(code)
    return cast(dict[str, Any], value)


__all__ = [
    "DeterministicProductionHearingInvocationDecoder",
    "GovernedProductionHearingInvocationDecoder",
    "JavaProductionRoomExchange",
    "PRODUCTION_RUNTIME_ROOM_OBJECT_LOAD_PATH",
    "PRODUCTION_RUNTIME_ROOM_PROPOSAL_PUT_PATH",
]
