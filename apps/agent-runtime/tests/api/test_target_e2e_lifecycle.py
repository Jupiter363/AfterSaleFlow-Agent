from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from app.api.target_e2e_lifecycle import (
    TargetE2ELifecycleEndpointDependencies,
    create_target_e2e_lifecycle_router,
)
from app.graph_runtime.target_e2e_lifecycle import (
    TARGET_E2E_LIFECYCLE_PATH,
    TargetE2EDrainIncompleteError,
    TargetE2ELifecycleAuthenticationError,
    TargetE2ELifecycleBinding,
    TargetE2ELifecycleBindingError,
    TargetE2ELifecycleReconciliation,
    TargetE2ELifecycleState,
    VerifiedTargetE2ELifecycleReceipt,
    build_target_e2e_lifecycle_receipt,
)
from app.security.invocation_envelope import TransportIdentity


def _verified() -> VerifiedTargetE2ELifecycleReceipt:
    binding = TargetE2ELifecycleBinding(
        activation_id="p9act.v1." + "a" * 32,
        environment_id="isolated-preprod-cn-1",
        environment_generation=12,
        manifest_hash="b" * 64,
        runtime_context_hash="c" * 64,
    )
    receipt = build_target_e2e_lifecycle_receipt(
        binding,
        from_state=TargetE2ELifecycleState.ACTIVE,
        to_state=TargetE2ELifecycleState.DRAIN_ONLY,
        transitioned_at=datetime(2026, 7, 30, 8, 0, tzinfo=timezone.utc),
    )
    return VerifiedTargetE2ELifecycleReceipt(receipt, "java-key-1", "d" * 64)


class _TransportResolver:
    def __init__(self) -> None:
        self.calls = 0

    def resolve(self, scope: Any) -> TransportIdentity:
        del scope
        self.calls += 1
        return TransportIdentity("java-api-service", True, "d" * 64)


class _Verifier:
    def __init__(self, verified: VerifiedTargetE2ELifecycleReceipt) -> None:
        self.verified = verified
        self.calls: list[tuple[str, TransportIdentity]] = []
        self.failure: Exception | None = None

    def verify(
        self,
        *,
        token: str,
        transport_identity: TransportIdentity,
    ) -> VerifiedTargetE2ELifecycleReceipt:
        self.calls.append((token, transport_identity))
        if self.failure is not None:
            raise self.failure
        return self.verified


class _Reconciler:
    def __init__(self, verified: VerifiedTargetE2ELifecycleReceipt) -> None:
        self.verified = verified
        self.calls: list[VerifiedTargetE2ELifecycleReceipt] = []
        self.failure: Exception | None = None

    async def reconcile(
        self,
        verified: VerifiedTargetE2ELifecycleReceipt,
    ) -> TargetE2ELifecycleReconciliation:
        self.calls.append(verified)
        if self.failure is not None:
            raise self.failure
        return TargetE2ELifecycleReconciliation(
            lifecycle_state=verified.receipt.to_state,
            receipt_hash=verified.receipt.receipt_hash,
            idempotent_replay=False,
        )


def _client(
    *,
    mode: str = "TARGET_E2E_CANDIDATE",
    ready: bool = True,
) -> tuple[TestClient, _TransportResolver, _Verifier, _Reconciler]:
    verified = _verified()
    transport = _TransportResolver()
    verifier = _Verifier(verified)
    reconciler = _Reconciler(verified)
    app = FastAPI()
    app.include_router(
        create_target_e2e_lifecycle_router(
            TargetE2ELifecycleEndpointDependencies(
                mode=mode,
                ready=lambda: ready,
                transport_identity_resolver=transport,
                receipt_verifier=verifier,
                reconciler=reconciler,
            )
        )
    )
    return TestClient(app), transport, verifier, reconciler


def _post(client: TestClient, **kwargs: Any) -> Any:
    headers = {"Authorization": "Bearer signed.lifecycle.receipt"}
    headers.update(kwargs.pop("headers", {}))
    return client.post(TARGET_E2E_LIFECYCLE_PATH, headers=headers, **kwargs)


def test_lifecycle_endpoint_authenticates_then_returns_hash_only_result() -> None:
    client, transport, verifier, reconciler = _client()

    response = _post(client)

    assert response.status_code == 200
    assert response.json() == {
        "schemaVersion": "target-e2e-activation-lifecycle-result.v1",
        "executionLane": "TARGET_E2E_CANDIDATE",
        "lifecycleState": "DRAIN_ONLY",
        "receiptHash": _verified().receipt.receipt_hash,
        "idempotentReplay": False,
    }
    assert response.headers["cache-control"] == "no-store, no-transform"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert transport.calls == 1
    assert verifier.calls[0][0] == "signed.lifecycle.receipt"
    assert reconciler.calls == [verifier.verified]
    assert "signed.lifecycle.receipt" not in response.text


@pytest.mark.parametrize(
    ("mode", "ready", "status", "code"),
    [
        ("DISABLED", True, 503, "TARGET_E2E_LIFECYCLE_DISABLED"),
        ("SHADOW", True, 503, "TARGET_E2E_LIFECYCLE_DISABLED"),
        ("TARGET_E2E_CANDIDATE", False, 503, "TARGET_E2E_LIFECYCLE_NOT_READY"),
    ],
)
def test_lifecycle_endpoint_is_default_off_and_fails_before_authentication(
    mode: str,
    ready: bool,
    status: int,
    code: str,
) -> None:
    client, transport, verifier, reconciler = _client(mode=mode, ready=ready)

    response = _post(client)

    assert response.status_code == status
    assert response.json()["code"] == code
    assert transport.calls == 0
    assert verifier.calls == []
    assert reconciler.calls == []


def test_lifecycle_endpoint_rejects_bootstrap_header_body_and_duplicate_authorization() -> None:
    client, transport, verifier, reconciler = _client()

    bootstrap = _post(
        client,
        headers={"X-AfterSaleFlow-Target-E2E-Activation": "must-not-be-a-bearer"},
    )
    body = _post(client, content=b"secret-or-payload")
    duplicate_auth = client.post(
        TARGET_E2E_LIFECYCLE_PATH,
        headers=[
            ("Authorization", "Bearer signed.lifecycle.receipt"),
            ("Authorization", "Bearer another.signed.receipt"),
        ],
    )

    assert bootstrap.status_code == 400
    assert bootstrap.json()["code"] == "TARGET_E2E_ACTIVATION_HEADER_FORBIDDEN"
    assert body.status_code == 400
    assert body.json()["code"] == "TARGET_E2E_LIFECYCLE_BODY_FORBIDDEN"
    assert duplicate_auth.status_code == 401
    assert duplicate_auth.json()["code"] == "TARGET_E2E_LIFECYCLE_AUTHENTICATION_REJECTED"
    assert transport.calls == 0
    assert verifier.calls == []
    assert reconciler.calls == []


def test_lifecycle_endpoint_maps_authentication_and_drain_conflicts_without_details() -> None:
    client, _transport, verifier, reconciler = _client()
    verifier.failure = TargetE2ELifecycleAuthenticationError("private signature detail")

    unauthorized = _post(client)

    assert unauthorized.status_code == 401
    assert unauthorized.json() == {
        "code": "TARGET_E2E_LIFECYCLE_AUTHENTICATION_REJECTED",
        "retryable": False,
    }
    assert "private" not in unauthorized.text

    verifier.failure = TargetE2ELifecycleBindingError("private deployment binding")
    binding_conflict = _post(client)

    assert binding_conflict.status_code == 409
    assert binding_conflict.json() == {
        "code": "TARGET_E2E_LIFECYCLE_BINDING_REJECTED",
        "retryable": False,
    }
    assert "private" not in binding_conflict.text

    verifier.failure = None
    reconciler.failure = TargetE2EDrainIncompleteError("private command identity")
    drain = _post(client)

    assert drain.status_code == 409
    assert drain.json() == {"code": "TARGET_E2E_DRAIN_INCOMPLETE", "retryable": True}
    assert "private" not in drain.text
