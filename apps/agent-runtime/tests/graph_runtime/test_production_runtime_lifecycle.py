from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives.asymmetric import ec
import jwt
import pytest
from pydantic import ValidationError

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.production_runtime_lifecycle import (
    PRODUCTION_RUNTIME_LIFECYCLE_JWT_TYPE,
    FilesystemProductionCheckpointBarrierControl,
    PostgresProductionLifecycleRepository,
    ProductionCheckpointCommitBinding,
    ProductionCheckpointDurabilityRepository,
    ProductionCheckpointNotDurableError,
    ProductionCheckpointRecoveryBarrier,
    ProductionDrainIncompleteError,
    ProductionLifecycleAuthenticationError,
    ProductionLifecycleBinding,
    ProductionLifecycleBindingError,
    ProductionLifecycleReceipt,
    ProductionLifecycleReceiptVerifier,
    ProductionLifecycleState,
    ProductionLifecycleTransitionError,
    build_production_runtime_lifecycle_receipt,
)
from app.security.invocation_envelope import ResolvedVerificationKey, TransportIdentity


NOW = datetime(2026, 7, 30, 8, 0, tzinfo=timezone.utc)
NOW_EPOCH = int(NOW.timestamp())


def _binding(**changes: Any) -> ProductionLifecycleBinding:
    values: dict[str, Any] = {
        "activation_id": "p9act.v1." + "a" * 32,
        "environment_id": "isolated-preprod-cn-1",
        "environment_generation": 12,
        "manifest_hash": "b" * 64,
        "runtime_context_hash": "c" * 64,
    }
    values.update(changes)
    return ProductionLifecycleBinding(**values)


def _row(
    binding: ProductionLifecycleBinding,
    *,
    state: ProductionLifecycleState = ProductionLifecycleState.ACTIVE,
    drain_only_at: datetime | None = None,
    drained_at: datetime | None = None,
    revoked_at: datetime | None = None,
) -> dict[str, Any]:
    return {
        "activation_id": binding.activation_id,
        "environment_id": binding.environment_id,
        "environment_generation": binding.environment_generation,
        "context_hash": binding.runtime_context_hash,
        "activation_manifest_hash": binding.manifest_hash,
        "expires_at": NOW,
        "generation_activation_id": binding.activation_id,
        "generation_high_water": binding.environment_generation,
        "generation_context_hash": binding.runtime_context_hash,
        "lifecycle_state": state.value,
        "activated_at": NOW - timedelta(hours=1),
        "drain_only_at": drain_only_at,
        "drained_at": drained_at,
        "revoked_at": revoked_at,
    }


class _Cursor:
    def __init__(self, row: dict[str, Any] | None) -> None:
        self._row = row

    async def fetchone(self) -> dict[str, Any] | None:
        return self._row


class _LifecycleConnection:
    def __init__(self, row: dict[str, Any]) -> None:
        self.row = row
        self.reject_update = False
        self.statements: list[tuple[str, tuple[Any, ...]]] = []

    async def execute(self, sql: str, params: tuple[Any, ...]) -> _Cursor:
        normalized = " ".join(sql.split())
        self.statements.append((normalized, params))
        if normalized.startswith("select activation.activation_id"):
            return _Cursor(dict(self.row))
        if self.reject_update:
            return _Cursor(None)
        transitioned_at, _activation_id = params
        if "set lifecycle_state = 'DRAIN_ONLY'" in normalized:
            state = ProductionLifecycleState.DRAIN_ONLY
            self.row["drain_only_at"] = transitioned_at
        elif "set lifecycle_state = 'DRAINED'" in normalized:
            state = ProductionLifecycleState.DRAINED
            self.row["drained_at"] = transitioned_at
        elif "set lifecycle_state = 'REVOKED_TERMINAL'" in normalized:
            state = ProductionLifecycleState.REVOKED_TERMINAL
            self.row["revoked_at"] = transitioned_at
        else:
            raise AssertionError(normalized)
        self.row["lifecycle_state"] = state.value
        return _Cursor({"lifecycle_state": state.value})


@pytest.mark.asyncio
async def test_java_receipts_advance_exactly_and_replay_without_another_update() -> None:
    binding = _binding()
    connection = _LifecycleConnection(_row(binding))
    repository = PostgresProductionLifecycleRepository()
    transitions = (
        (
            ProductionLifecycleState.ACTIVE,
            ProductionLifecycleState.DRAIN_ONLY,
            NOW,
        ),
        (
            ProductionLifecycleState.DRAIN_ONLY,
            ProductionLifecycleState.DRAINED,
            NOW + timedelta(seconds=1),
        ),
        (
            ProductionLifecycleState.DRAINED,
            ProductionLifecycleState.REVOKED_TERMINAL,
            NOW + timedelta(seconds=2),
        ),
    )

    for source, target, transitioned_at in transitions:
        receipt = build_production_runtime_lifecycle_receipt(
            binding,
            from_state=source,
            to_state=target,
            transitioned_at=transitioned_at,
        )
        first = await repository.reconcile(
            connection,
            receipt=receipt,
            expected_binding=binding,
        )
        statement_count = len(connection.statements)
        replay = await repository.reconcile(
            connection,
            receipt=receipt,
            expected_binding=binding,
        )

        assert first.lifecycle_state is target
        assert first.idempotent_replay is False
        assert replay == first.__class__(target, receipt.receipt_hash, True)
        assert len(connection.statements) == statement_count + 1

    assert connection.row["lifecycle_state"] == "REVOKED_TERMINAL"


@pytest.mark.asyncio
async def test_replay_with_another_valid_hash_and_timestamp_is_rejected() -> None:
    binding = _binding()
    persisted_at = NOW + timedelta(seconds=1)
    connection = _LifecycleConnection(
        _row(
            binding,
            state=ProductionLifecycleState.DRAINED,
            drain_only_at=NOW,
            drained_at=persisted_at,
        )
    )
    changed = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.DRAIN_ONLY,
        to_state=ProductionLifecycleState.DRAINED,
        transitioned_at=persisted_at + timedelta(microseconds=1),
    )

    with pytest.raises(ProductionLifecycleTransitionError, match="different receipt hash"):
        await PostgresProductionLifecycleRepository().reconcile(
            connection,
            receipt=changed,
            expected_binding=binding,
        )

    assert len(connection.statements) == 1


@pytest.mark.asyncio
async def test_cross_binding_regression_and_premature_transition_fail_closed() -> None:
    binding = _binding()
    receipt = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.ACTIVE,
        to_state=ProductionLifecycleState.DRAIN_ONLY,
        transitioned_at=NOW,
    )
    repository = PostgresProductionLifecycleRepository()

    with pytest.raises(ProductionLifecycleBindingError):
        await repository.reconcile(
            _LifecycleConnection(_row(binding)),
            receipt=receipt,
            expected_binding=_binding(manifest_hash="d" * 64),
        )

    regressed = _LifecycleConnection(
        _row(
            binding,
            state=ProductionLifecycleState.DRAINED,
            drain_only_at=NOW,
            drained_at=NOW + timedelta(seconds=1),
        )
    )
    with pytest.raises(ProductionLifecycleTransitionError):
        await repository.reconcile(
            regressed,
            receipt=receipt,
            expected_binding=binding,
        )

    premature = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.ACTIVE,
        to_state=ProductionLifecycleState.DRAIN_ONLY,
        transitioned_at=NOW - timedelta(microseconds=1),
    )
    with pytest.raises(ProductionLifecycleTransitionError):
        await repository.reconcile(
            _LifecycleConnection(_row(binding)),
            receipt=premature,
            expected_binding=binding,
        )


@pytest.mark.asyncio
async def test_drained_requires_zero_unresolved_commands_and_active_leases() -> None:
    binding = _binding()
    connection = _LifecycleConnection(
        _row(
            binding,
            state=ProductionLifecycleState.DRAIN_ONLY,
            drain_only_at=NOW,
        )
    )
    connection.reject_update = True
    receipt = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.DRAIN_ONLY,
        to_state=ProductionLifecycleState.DRAINED,
        transitioned_at=NOW + timedelta(seconds=1),
    )

    with pytest.raises(ProductionDrainIncompleteError):
        await PostgresProductionLifecycleRepository().reconcile(
            connection,
            receipt=receipt,
            expected_binding=binding,
        )

    update_sql = connection.statements[-1][0]
    assert "command.status in ('REGISTERED', 'EXECUTING', 'RESULT_CHECKPOINTED')" in update_sql
    assert "lease.released_at is null" in update_sql


def test_receipt_model_rejects_skip_and_unknown_payload_members() -> None:
    binding = _binding()
    receipt = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.ACTIVE,
        to_state=ProductionLifecycleState.DRAIN_ONLY,
        transitioned_at=NOW,
    ).model_dump(mode="json", by_alias=True)
    receipt["toState"] = "DRAINED"
    receipt["receiptHash"] = canonical_sha256(
        {key: value for key, value in receipt.items() if key != "receiptHash"}
    )
    with pytest.raises(ValidationError, match="skips or regresses"):
        ProductionLifecycleReceipt.model_validate(receipt)

    receipt["payload"] = "must-not-be-recorded"
    with pytest.raises(ValidationError, match="Extra inputs"):
        ProductionLifecycleReceipt.model_validate(receipt)


class _KeyResolver:
    def __init__(self, public_key: Any) -> None:
        self._public_key = public_key

    def resolve(self, kid: str) -> ResolvedVerificationKey:
        return ResolvedVerificationKey(kid=kid, public_key=self._public_key)


def _token(
    private_key: Any,
    receipt: ProductionLifecycleReceipt,
    *,
    now: int = NOW_EPOCH,
) -> str:
    return jwt.encode(
        {
            "iss": "java-api-service",
            "aud": "python-agent-service",
            "sub": "production-runtime-lifecycle-reconcile",
            "iat": now,
            "nbf": now,
            "exp": now + 30,
            "jti": "lifecycle-delivery-1",
            "receipt": receipt.model_dump(mode="json", by_alias=True),
        },
        private_key,
        algorithm="ES256",
        headers={"alg": "ES256", "kid": "java-target-key-1", "typ": PRODUCTION_RUNTIME_LIFECYCLE_JWT_TYPE},
    )


def test_verifier_requires_java_mtls_signature_time_and_exact_binding() -> None:
    binding = _binding()
    receipt = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.ACTIVE,
        to_state=ProductionLifecycleState.DRAIN_ONLY,
        transitioned_at=NOW,
    )
    private_key = ec.generate_private_key(ec.SECP256R1())
    verifier = ProductionLifecycleReceiptVerifier(
        key_resolver=_KeyResolver(private_key.public_key()),
        expected_binding=binding,
        now=lambda: NOW_EPOCH,
    )
    identity = TransportIdentity("java-api-service", True, "e" * 64)

    verified = verifier.verify(token=_token(private_key, receipt), transport_identity=identity)

    assert verified.receipt == receipt
    assert verified.key_id == "java-target-key-1"

    same_second_receipt = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.ACTIVE,
        to_state=ProductionLifecycleState.DRAIN_ONLY,
        transitioned_at=NOW + timedelta(microseconds=999_999),
    )
    assert (
        verifier.verify(
            token=_token(private_key, same_second_receipt),
            transport_identity=identity,
        ).receipt
        == same_second_receipt
    )

    with pytest.raises(ProductionLifecycleAuthenticationError):
        verifier.verify(
            token=_token(private_key, receipt),
            transport_identity=TransportIdentity("other-service", True, "e" * 64),
        )
    with pytest.raises(ProductionLifecycleAuthenticationError):
        verifier.verify(
            token=_token(ec.generate_private_key(ec.SECP256R1()), receipt),
            transport_identity=identity,
        )

    future_receipt = build_production_runtime_lifecycle_receipt(
        binding,
        from_state=ProductionLifecycleState.ACTIVE,
        to_state=ProductionLifecycleState.DRAIN_ONLY,
        transitioned_at=NOW + timedelta(seconds=1),
    )
    with pytest.raises(ProductionLifecycleAuthenticationError):
        verifier.verify(
            token=_token(private_key, future_receipt),
            transport_identity=identity,
        )

    changed_binding_verifier = ProductionLifecycleReceiptVerifier(
        key_resolver=_KeyResolver(private_key.public_key()),
        expected_binding=_binding(environment_generation=13),
        now=lambda: NOW_EPOCH,
    )
    with pytest.raises(ProductionLifecycleBindingError):
        changed_binding_verifier.verify(
            token=_token(private_key, receipt),
            transport_identity=identity,
        )


class _DurabilityRepository(ProductionCheckpointDurabilityRepository):
    def __init__(self, events: list[str], *, durable: bool = True) -> None:
        self.events = events
        self.durable = durable

    async def require_durable(self, connection: Any, binding: Any) -> None:
        del connection, binding
        self.events.append("durable")
        if not self.durable:
            raise ProductionCheckpointNotDurableError()


def _checkpoint_binding(binding: ProductionLifecycleBinding) -> ProductionCheckpointCommitBinding:
    return ProductionCheckpointCommitBinding(
        execution_lane="PRODUCTION",
        activation_id=binding.activation_id,
        environment_id=binding.environment_id,
        environment_generation=binding.environment_generation,
        manifest_hash=binding.manifest_hash,
        runtime_context_hash=binding.runtime_context_hash,
        thread_id="grt.v1." + "f" * 32,
        command_id="command-1",
        request_hash="1" * 64,
        checkpoint_namespace="room",
        checkpoint_id="checkpoint-1",
        result_hash="2" * 64,
    )


@pytest.mark.asyncio
async def test_checkpoint_barrier_is_default_off_and_when_enabled_waits_after_durability() -> None:
    binding = _binding()
    checkpoint = _checkpoint_binding(binding)
    disabled_events: list[str] = []
    disabled = ProductionCheckpointRecoveryBarrier(
        expected_binding=binding,
        durability_repository=_DurabilityRepository(disabled_events),
    )

    disabled_result = await disabled.wait_after_durable_commit(object(), checkpoint)

    assert disabled_result.enforced is False
    assert disabled_events == []

    events: list[str] = []

    async def release(_binding: ProductionCheckpointCommitBinding) -> None:
        events.append("released")

    unarmed = ProductionCheckpointRecoveryBarrier(
        expected_binding=binding,
        enabled=True,
        isolated_synthetic_environment=True,
        arming_policy=lambda _binding: False,
        release_waiter=release,
        durability_repository=_DurabilityRepository(events),
    )
    unarmed_result = await unarmed.wait_after_durable_commit(object(), checkpoint)
    assert unarmed_result.enforced is False
    assert events == []

    enabled = ProductionCheckpointRecoveryBarrier(
        expected_binding=binding,
        enabled=True,
        isolated_synthetic_environment=True,
        arming_policy=lambda _binding: True,
        release_waiter=release,
        durability_repository=_DurabilityRepository(events),
    )

    result = await enabled.wait_after_durable_commit(object(), checkpoint)

    assert result.enforced is True
    assert result.durable is True
    assert events == ["durable", "released"]


@pytest.mark.asyncio
async def test_checkpoint_barrier_rejects_non_durable_commit_and_is_bounded() -> None:
    binding = _binding()
    checkpoint = _checkpoint_binding(binding)

    async def release(_binding: ProductionCheckpointCommitBinding) -> None:
        await asyncio.Event().wait()

    nondurable = ProductionCheckpointRecoveryBarrier(
        expected_binding=binding,
        enabled=True,
        isolated_synthetic_environment=True,
        arming_policy=lambda _binding: True,
        release_waiter=release,
        durability_repository=_DurabilityRepository([], durable=False),
    )
    with pytest.raises(ProductionCheckpointNotDurableError):
        await nondurable.wait_after_durable_commit(object(), checkpoint)

    bounded = ProductionCheckpointRecoveryBarrier(
        expected_binding=binding,
        enabled=True,
        isolated_synthetic_environment=True,
        maximum_wait_seconds=0.01,
        arming_policy=lambda _binding: True,
        release_waiter=release,
        durability_repository=_DurabilityRepository([]),
    )
    with pytest.raises(RuntimeError, match="timed out"):
        await bounded.wait_after_durable_commit(object(), checkpoint)


@pytest.mark.asyncio
async def test_postgres_durability_probe_accepts_only_explicit_true() -> None:
    binding = _binding()
    checkpoint = _checkpoint_binding(binding)

    class Connection:
        def __init__(self, durable: Any) -> None:
            self.durable = durable
            self.params: tuple[Any, ...] | None = None

        async def execute(self, sql: str, params: tuple[Any, ...]) -> _Cursor:
            assert "agent_graph_result" in sql
            assert "PRODUCTION" in sql
            self.params = params
            return _Cursor({"durable": self.durable})

    accepted = Connection(True)
    await ProductionCheckpointDurabilityRepository().require_durable(accepted, checkpoint)
    assert accepted.params is not None
    assert accepted.params[-1] == checkpoint.manifest_hash

    with pytest.raises(ProductionCheckpointNotDurableError):
        await ProductionCheckpointDurabilityRepository().require_durable(
            Connection(1),
            checkpoint,
        )


@pytest.mark.asyncio
async def test_filesystem_checkpoint_barrier_requires_exact_arm_and_release(
    tmp_path: Path,
) -> None:
    binding = _checkpoint_binding(_binding())
    control = FilesystemProductionCheckpointBarrierControl(
        tmp_path / "recovery-barrier",
        poll_interval_seconds=0.001,
    )
    assert control.is_armed(binding) is False

    arm = control.arm_document(binding)
    arm_path = control.arm_path(binding)
    arm_path.parent.mkdir(parents=True)
    arm_path.write_text(
        json.dumps(arm, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
        encoding="utf-8",
    )
    assert control.is_armed(binding) is True

    waiter = asyncio.create_task(control.wait_for_release(binding))
    reached_path = control.reached_path(binding)
    for _ in range(100):
        if reached_path.exists():
            break
        await asyncio.sleep(0.001)
    reached = json.loads(reached_path.read_text(encoding="utf-8"))
    release = control.release_document(reached["selfHash"])
    control.release_path(reached["selfHash"]).write_text(
        json.dumps(release, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
        encoding="utf-8",
    )

    await asyncio.wait_for(waiter, timeout=1)
    assert reached["checkpointId"] == binding.checkpoint_id
    assert reached["resultHash"] == binding.result_hash
