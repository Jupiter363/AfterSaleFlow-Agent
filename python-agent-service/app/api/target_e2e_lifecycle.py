"""Target-only HTTP adapter for Java-authoritative activation lifecycle receipts."""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
import logging
from typing import Any, Protocol

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.graph_runtime.target_e2e_lifecycle import (
    TARGET_E2E_LIFECYCLE_PATH,
    TargetE2EDrainIncompleteError,
    TargetE2ELifecycleAuthenticationError,
    TargetE2ELifecycleBindingError,
    TargetE2ELifecycleError,
    TargetE2ELifecycleReconciliation,
    TargetE2ELifecycleTransitionError,
    VerifiedTargetE2ELifecycleReceipt,
)
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    TransportIdentity,
    extract_bearer_token,
)


LOGGER = logging.getLogger(__name__)
_FORBIDDEN_BOOTSTRAP_HEADER = "x-aftersaleflow-target-e2e-activation"
_NO_STORE_HEADERS: Mapping[str, str] = {
    "Cache-Control": "no-store, no-transform",
    "Pragma": "no-cache",
    "X-Content-Type-Options": "nosniff",
}


class TargetE2ELifecycleTransportIdentityResolver(Protocol):
    def resolve(self, scope: Mapping[str, Any]) -> TransportIdentity: ...


class TargetE2ELifecycleVerifierPort(Protocol):
    def verify(
        self,
        *,
        token: str,
        transport_identity: TransportIdentity,
    ) -> VerifiedTargetE2ELifecycleReceipt: ...


class TargetE2ELifecycleReconcilerPort(Protocol):
    async def reconcile(
        self,
        verified: VerifiedTargetE2ELifecycleReceipt,
    ) -> TargetE2ELifecycleReconciliation: ...


@dataclass(frozen=True, slots=True)
class TargetE2ELifecycleEndpointDependencies:
    mode: str
    ready: Callable[[], bool]
    transport_identity_resolver: TargetE2ELifecycleTransportIdentityResolver
    receipt_verifier: TargetE2ELifecycleVerifierPort
    reconciler: TargetE2ELifecycleReconcilerPort


def create_target_e2e_lifecycle_router(
    dependencies: TargetE2ELifecycleEndpointDependencies,
) -> APIRouter:
    """Create a default-off route; callers must mount it only in target assembly."""

    router = APIRouter()

    @router.post(TARGET_E2E_LIFECYCLE_PATH, response_model=None)
    async def reconcile_target_e2e_lifecycle(request: Request) -> JSONResponse:
        if dependencies.mode != "TARGET_E2E_CANDIDATE":
            return _error_response(503, "TARGET_E2E_LIFECYCLE_DISABLED", False)
        if request.headers.get(_FORBIDDEN_BOOTSTRAP_HEADER) is not None:
            return _error_response(
                400,
                "TARGET_E2E_ACTIVATION_HEADER_FORBIDDEN",
                False,
            )
        try:
            ready = dependencies.ready()
        except Exception:
            ready = False
        if not ready:
            return _error_response(503, "TARGET_E2E_LIFECYCLE_NOT_READY", True)
        if await _has_request_body(request):
            return _error_response(400, "TARGET_E2E_LIFECYCLE_BODY_FORBIDDEN", False)

        try:
            authorizations = request.headers.getlist("authorization")
            if len(authorizations) != 1:
                raise TargetE2ELifecycleAuthenticationError()
            token = extract_bearer_token(authorizations[0])
            transport_identity = dependencies.transport_identity_resolver.resolve(
                request.scope
            )
            verified = dependencies.receipt_verifier.verify(
                token=token,
                transport_identity=transport_identity,
            )
            if type(verified) is not VerifiedTargetE2ELifecycleReceipt:
                raise TargetE2ELifecycleAuthenticationError()
        except (InvocationEnvelopeError, TargetE2ELifecycleAuthenticationError):
            return _error_response(
                401,
                TargetE2ELifecycleAuthenticationError.code,
                False,
            )
        except TargetE2ELifecycleBindingError as error:
            return _error_response(409, error.code, False)
        except Exception as error:
            _log_safe_failure("receipt authentication", error)
            return _error_response(500, "TARGET_E2E_LIFECYCLE_INTERNAL_ERROR", False)

        try:
            result = await dependencies.reconciler.reconcile(verified)
            if type(result) is not TargetE2ELifecycleReconciliation:
                raise TypeError("lifecycle reconciler returned an invalid result")
        except TargetE2EDrainIncompleteError as error:
            return _error_response(409, error.code, True)
        except (
            TargetE2ELifecycleBindingError,
            TargetE2ELifecycleTransitionError,
        ) as error:
            return _error_response(409, error.code, False)
        except TargetE2ELifecycleError as error:
            return _error_response(400, error.code, False)
        except Exception as error:
            _log_safe_failure("receipt reconciliation", error)
            return _error_response(500, "TARGET_E2E_LIFECYCLE_INTERNAL_ERROR", False)

        return JSONResponse(
            status_code=200,
            content={
                "schemaVersion": "target-e2e-activation-lifecycle-result.v1",
                "executionLane": "TARGET_E2E_CANDIDATE",
                "lifecycleState": result.lifecycle_state.value,
                "receiptHash": result.receipt_hash,
                "idempotentReplay": result.idempotent_replay,
            },
            headers=dict(_NO_STORE_HEADERS),
        )

    return router


async def _has_request_body(request: Request) -> bool:
    content_length = request.headers.get("content-length")
    if content_length is not None:
        try:
            if int(content_length) != 0:
                return True
        except ValueError:
            return True
    async for chunk in request.stream():
        if chunk:
            return True
    return False


def _error_response(status: int, code: str, retryable: bool) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={"code": code, "retryable": retryable},
        headers=dict(_NO_STORE_HEADERS),
    )


def _log_safe_failure(operation: str, error: Exception) -> None:
    # Never interpolate exception text: drivers may include SQL values in it.
    LOGGER.error(
        "target-E2E lifecycle %s failed class=%s",
        operation,
        type(error).__name__,
    )
