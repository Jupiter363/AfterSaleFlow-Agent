"""FastAPI-owned lifecycle for the disabled/shadow Graph platform."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
import socket
from typing import Any, Protocol, cast
from uuid import uuid4

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.api.graph_commands import (
    GraphCommandEndpointDependencies,
    GraphCommandStreamService,
    GraphReconciliationEndpointDependencies,
    TransportIdentityResolver,
    TrustedReconciliationThreadIdentityResolver,
    TrustedThreadIdentityResolver,
    TargetE2EInvocationEnvelopeVerifierPort,
)
from app.api.target_e2e_lifecycle import (
    TargetE2ELifecycleEndpointDependencies,
)
from app.api.graph_reconciliation_service import (
    GatewayBackedGraphReconciliationService,
    GraphReconciliationService,
    TargetE2EReconciliationArtifacts,
)
from app.api.graph_stream_service import (
    ExactShadowExecutorRegistry,
    GatewayBackedGraphCommandStreamService,
    GraphStreamAdmissionGate,
)
from app.config import Settings
from app.contracts.v1.codec import ContractCodec
from app.contracts.v1.models import AgentStreamEvent, GraphReconcileResponse, RoomGraphCommand
from app.graph_runtime.checkpoint import FencedPostgresSaver, GraphCheckpointRuntime
from app.graph_runtime.errors import GraphGatewayDisabledError
from app.graph_runtime.gateway import GraphCommandGateway, ImmutableInputAuthorizer
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.persistence_models import (
    GraphGatewayMode,
    GraphPoolConfig,
    GraphReadinessConfig,
)
from app.graph_runtime.postgres_bulkhead import (
    PostgresBulkheadConfig,
    PostgresGraphFanoutBulkhead,
)
from app.graph_runtime.readiness import GraphPersistenceReadinessProbe
from app.graph_runtime.registry import PostgresGraphVersionRegistry
from app.graph_runtime.target_e2e import (
    PostgresTargetE2EActivationRepository,
    TargetE2EGraphCommandEnvelope,
    TargetE2EInputAuthorizer,
    TargetE2ERoomProposalSource,
    TargetE2EThreadIdentityResolver,
    TargetE2EInvocationVerifier,
    TargetE2ERuntimeAuthority,
    VerifiedTargetE2EInvocation,
)
from app.graph_runtime.target_e2e_lifecycle import (
    FilesystemTargetE2ECheckpointBarrierControl,
    PostgresTargetE2ELifecycleRepository,
    TargetE2ECheckpointGatewayBarrier,
    TargetE2ECheckpointRecoveryBarrier,
    TargetE2ELifecycleBinding,
    TargetE2ELifecycleReceiptVerifier,
    TargetE2ELifecycleReconciler,
    TargetE2ELifecycleReconciliation,
    VerifiedTargetE2ELifecycleReceipt,
)
from app.security.graph_runtime import (
    GraphSecurityRuntime,
    _open_for_lifecycle as _open_graph_security_runtime_for_lifecycle,
)
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    InvocationEnvelopeVerifier,
    ReconciliationEnvelopeVerifier,
    TransportIdentity,
    VerificationKeyResolver,
    VerifiedInvocation,
    VerifiedReconciliation,
)
from app.security.transport_identity import AsgiMtlsIdentityResolver


GRAPH_READY_PATH = "/ready/graph"
GRAPH_SHUTDOWN_DRAIN_SECONDS = 5.0
_SHADOW_BULKHEAD_GLOBAL_LIMIT = 32
_SHADOW_BULKHEAD_TENANT_LIMIT = 16
_SHADOW_BULKHEAD_ROOM_LIMIT = 8
_SHADOW_BULKHEAD_GLOBAL_QUEUE_LIMIT = 256
_SHADOW_BULKHEAD_TENANT_QUEUE_LIMIT = 128
_SHADOW_BULKHEAD_ROOM_QUEUE_LIMIT = 100
_SHADOW_BULKHEAD_PERMIT_LEASE_SECONDS = 20
_SHADOW_BULKHEAD_WAIT_TIMEOUT_SECONDS = 5.0
_SHADOW_BULKHEAD_POLL_INTERVAL_SECONDS = 0.05


def _shadow_bulkhead_config() -> PostgresBulkheadConfig:
    """Build the fixed durable capacity only while SHADOW startup is opening."""

    return PostgresBulkheadConfig(
        global_limit=_SHADOW_BULKHEAD_GLOBAL_LIMIT,
        tenant_limit=_SHADOW_BULKHEAD_TENANT_LIMIT,
        room_limit=_SHADOW_BULKHEAD_ROOM_LIMIT,
        global_queue_limit=_SHADOW_BULKHEAD_GLOBAL_QUEUE_LIMIT,
        tenant_queue_limit=_SHADOW_BULKHEAD_TENANT_QUEUE_LIMIT,
        room_queue_limit=_SHADOW_BULKHEAD_ROOM_QUEUE_LIMIT,
        permit_lease_seconds=_SHADOW_BULKHEAD_PERMIT_LEASE_SECONDS,
        wait_timeout_seconds=_SHADOW_BULKHEAD_WAIT_TIMEOUT_SECONDS,
        poll_interval_seconds=_SHADOW_BULKHEAD_POLL_INTERVAL_SECONDS,
    )


@dataclass(frozen=True, slots=True)
class GraphExecutorKernel:
    """Process-owned resources that every compiled Graph executor must share."""

    saver: FencedPostgresSaver
    gateway: GraphCommandGateway
    durable_bulkhead: PostgresGraphFanoutBulkhead


class ExecutorRegistryFactory(Protocol):
    """Build exact executor registrations from the live process kernel."""

    def __call__(
        self,
        kernel: GraphExecutorKernel,
        /,
    ) -> ExactShadowExecutorRegistry: ...


@dataclass(frozen=True, slots=True)
class GraphRuntimeBindings:
    """Trusted process-local dependencies that cannot be selected by a command body."""

    thread_identity_resolver: TrustedThreadIdentityResolver
    input_authorizer: ImmutableInputAuthorizer
    executor_registry_factory: ExecutorRegistryFactory


@dataclass(frozen=True, slots=True)
class GraphRuntimeReadiness:
    ready: bool
    mode: GraphGatewayMode
    code: str
    accepting: bool
    persistence_code: str
    security_code: str
    bulkhead_code: str = "GRAPH_BULKHEAD_NOT_CONFIGURED"

    def public_payload(self) -> dict[str, str | bool]:
        return {
            "ready": self.ready,
            "mode": self.mode.value,
            "code": self.code,
            "accepting": self.accepting,
            "persistence_code": self.persistence_code,
            "security_code": self.security_code,
            "bulkhead_code": self.bulkhead_code,
        }


class GraphRuntimeInstance(Protocol):
    execution_verifier: InvocationEnvelopeVerifier
    reconciliation_verifier: ReconciliationEnvelopeVerifier
    stream_service: GraphCommandStreamService
    reconciliation_service: GraphReconciliationService
    target_e2e_verifier: TargetE2EInvocationEnvelopeVerifierPort | None

    @property
    def target_e2e_lifecycle_key_resolver(self) -> VerificationKeyResolver: ...

    @property
    def target_e2e_lifecycle_pool(self) -> Any: ...

    @property
    def ready(self) -> bool: ...

    async def check_readiness(self) -> GraphRuntimeReadiness: ...

    async def close(self) -> bool: ...


RuntimeFactory = Callable[
    [Settings, GraphRuntimeBindings | None],
    Awaitable[GraphRuntimeInstance],
]


class GraphApplicationRuntime:
    """One process-lifetime pool, saver, gateway, key runtime, and admission gate."""

    def __init__(
        self,
        *,
        checkpoint_runtime: GraphCheckpointRuntime,
        persistence_probe: GraphPersistenceReadinessProbe,
        durable_bulkhead: PostgresGraphFanoutBulkhead,
        security_runtime: GraphSecurityRuntime,
        gateway: GraphCommandGateway,
        stream_service: GatewayBackedGraphCommandStreamService,
        reconciliation_service: GatewayBackedGraphReconciliationService,
        admission_gate: GraphStreamAdmissionGate,
        execution_verifier: InvocationEnvelopeVerifier,
        reconciliation_verifier: ReconciliationEnvelopeVerifier,
        target_e2e_verifier: TargetE2EInvocationVerifier | None = None,
        mode: GraphGatewayMode = GraphGatewayMode.SHADOW,
    ) -> None:
        self._checkpoint_runtime = checkpoint_runtime
        self._persistence_probe = persistence_probe
        self._durable_bulkhead = durable_bulkhead
        self._security_runtime = security_runtime
        self._gateway = gateway
        self.stream_service = stream_service
        self.reconciliation_service = reconciliation_service
        self._admission_gate = admission_gate
        self.execution_verifier = execution_verifier
        self.reconciliation_verifier = reconciliation_verifier
        self.target_e2e_verifier = target_e2e_verifier
        self._mode = mode
        self._persistence_ready = True
        self._bulkhead_ready = True
        self._closed = False
        self._close_complete = False
        self._drained = False
        self._close_lock = asyncio.Lock()

    @classmethod
    async def open(
        cls,
        settings: Settings,
        bindings: GraphRuntimeBindings | None,
    ) -> GraphApplicationRuntime:
        mode = GraphGatewayMode(settings.graph_gateway_mode)
        if mode is GraphGatewayMode.DISABLED:
            raise ValueError("Graph application runtime opens only in an active mode")
        if settings.graph_database_dsn is None or settings.graph_jwks_url is None:
            raise ValueError("active Graph dependencies are incomplete")
        if bindings is None and mode is not GraphGatewayMode.TARGET_E2E_CANDIDATE:
            raise ValueError("active Graph runtime requires trusted runtime bindings")
        if bindings is not None and mode is GraphGatewayMode.TARGET_E2E_CANDIDATE:
            raise ValueError(
                "target-E2E runtime bindings must be assembled after Graph security readiness"
            )
        if bindings is not None and not callable(bindings.executor_registry_factory):
            raise ValueError("active Graph runtime requires an executor registry factory")

        pool_config = GraphPoolConfig(
            schema=settings.graph_database_schema,
            min_size=settings.graph_pool_min_size,
            max_size=settings.graph_pool_max_size,
            max_waiting=settings.graph_pool_max_waiting,
            acquire_timeout_seconds=settings.graph_pool_acquire_timeout_seconds,
            max_idle_seconds=settings.graph_pool_max_idle_seconds,
            max_lifetime_seconds=settings.graph_pool_max_lifetime_seconds,
        )
        checkpoint_runtime = await GraphCheckpointRuntime.open(
            settings.graph_database_dsn.get_secret_value(),
            pool_config,
        )
        readiness_config = GraphReadinessConfig(
            mode=mode,
            expected_database=settings.graph_database_name,
            expected_user=settings.graph_database_user,
            expected_environment_generation=settings.graph_expected_environment_generation,
            expected_restore_verification_hash=(settings.graph_expected_restore_verification_hash),
            schema=settings.graph_database_schema,
            timeout_seconds=settings.graph_readiness_timeout_seconds,
        )
        probe = GraphPersistenceReadinessProbe(
            readiness_config,
            checkpoint_runtime.pool,
        )
        security_runtime: GraphSecurityRuntime | None = None
        durable_bulkhead: PostgresGraphFanoutBulkhead | None = None
        try:
            report = await probe.check()
            if not report.ready:
                raise RuntimeError(report.code)
            # Candidate endpoint dependencies do not need an executor registry
            # before JWKS is live.  Keep this bootstrap capability local to the
            # opening transaction; the real binding is assembled below only
            # after the security runtime has proven ready.
            input_authorizer = (
                TargetE2EInputAuthorizer()
                if mode is GraphGatewayMode.TARGET_E2E_CANDIDATE
                else bindings.input_authorizer
            )
            terminal_result_barrier = None
            if mode is GraphGatewayMode.TARGET_E2E_CANDIDATE:
                lifecycle_binding = _build_target_e2e_lifecycle_binding(settings)
                barrier_control = None
                if settings.graph_target_e2e_checkpoint_barrier_enabled:
                    barrier_directory = (
                        settings.graph_target_e2e_checkpoint_barrier_directory
                    )
                    if barrier_directory is None:
                        raise ValueError(
                            "enabled checkpoint recovery barrier has no control directory"
                        )
                    barrier_control = FilesystemTargetE2ECheckpointBarrierControl(
                        barrier_directory,
                        poll_interval_seconds=(
                            settings.graph_target_e2e_checkpoint_barrier_poll_interval_seconds
                        ),
                    )
                recovery_barrier = TargetE2ECheckpointRecoveryBarrier(
                    expected_binding=lifecycle_binding,
                    enabled=settings.graph_target_e2e_checkpoint_barrier_enabled,
                    isolated_synthetic_environment=settings.graph_target_e2e_isolated,
                    maximum_wait_seconds=(
                        settings.graph_target_e2e_checkpoint_barrier_maximum_wait_seconds
                    ),
                    durability_timeout_seconds=(
                        settings.graph_target_e2e_checkpoint_barrier_durability_timeout_seconds
                    ),
                    arming_policy=(
                        barrier_control.is_armed if barrier_control is not None else None
                    ),
                    release_waiter=(
                        barrier_control.wait_for_release
                        if barrier_control is not None
                        else None
                    ),
                )
                terminal_result_barrier = TargetE2ECheckpointGatewayBarrier(
                    pool=checkpoint_runtime.pool,
                    expected_binding=lifecycle_binding,
                    barrier=recovery_barrier,
                    acquire_timeout_seconds=settings.graph_pool_acquire_timeout_seconds,
                )
            gateway = GraphCommandGateway(
                mode=mode,
                pool=checkpoint_runtime.pool,
                input_authorizer=input_authorizer,
                terminal_result_barrier=terminal_result_barrier,
                acquire_timeout_seconds=settings.graph_pool_acquire_timeout_seconds,
            )
            durable_bulkhead = PostgresGraphFanoutBulkhead(
                checkpoint_runtime.pool,
                _shadow_bulkhead_config(),
            )
            await durable_bulkhead.open()
            bulkhead_report = await durable_bulkhead.check_readiness()
            if not bulkhead_report.ready:
                raise RuntimeError(bulkhead_report.code)
            security_runtime = await _open_graph_security_runtime_for_lifecycle(
                jwks_url=str(settings.graph_jwks_url),
                timeout_seconds=settings.graph_jwks_timeout_seconds,
                refresh_interval_seconds=settings.graph_jwks_refresh_seconds,
                referenced_key_ids=gateway.referenced_verification_key_ids,
            )
            target_e2e_verifier: TargetE2EInvocationVerifier | None = None
            if mode is GraphGatewayMode.TARGET_E2E_CANDIDATE:
                context = settings.graph_target_e2e_runtime_context
                if context is None or not settings.graph_target_e2e_bindings:
                    raise ValueError("target-E2E runtime projection is incomplete")
                authority = TargetE2ERuntimeAuthority.from_context(
                    context,
                    settings.graph_target_e2e_bindings,
                )
                async with checkpoint_runtime.pool.connection(
                    timeout=settings.graph_pool_acquire_timeout_seconds
                ) as connection:
                    async with connection.transaction():
                        for configured in settings.graph_target_e2e_bindings:
                            registered_binding = await PostgresGraphVersionRegistry().load(
                                connection,
                                graph_key=configured.graph_key,
                                graph_version=configured.graph_version,
                                checkpoint_schema_version=(
                                    configured.checkpoint_schema_version
                                ),
                            )
                            registered_binding.require_new_candidate_command()
                            if (
                                registered_binding.binding.binding_hash
                                != configured.binding_hash
                                or registered_binding.binding.code_build_id
                                != configured.code_build_id
                            ):
                                raise ValueError(
                                    "target-E2E executor differs from candidate registry"
                                )
                        await PostgresTargetE2EActivationRepository().register(
                            connection,
                            authority,
                        )
                target_e2e_verifier = TargetE2EInvocationVerifier(
                    key_resolver=security_runtime.resolver,
                    authority=authority,
                )
                if not security_runtime.readiness().ready:
                    raise RuntimeError("target-E2E Graph security runtime is not ready")
                bindings = _build_target_e2e_runtime_bindings(
                    settings=settings,
                    security_runtime=security_runtime,
                )
            if bindings is None:
                raise RuntimeError("active Graph runtime bindings were not assembled")
            executors = bindings.executor_registry_factory(
                GraphExecutorKernel(
                    saver=checkpoint_runtime.saver,
                    gateway=gateway,
                    durable_bulkhead=durable_bulkhead,
                )
            )
            if type(executors) is not ExactShadowExecutorRegistry:
                raise TypeError(
                    "SHADOW Graph executor factory must return an exact executor registry"
                )
            if executors.registration_count < 1:
                raise ValueError("SHADOW Graph runtime requires an exact executor registration")
            gate = GraphStreamAdmissionGate()
            owner_id = _process_owner_id()
            stream_service = GatewayBackedGraphCommandStreamService(
                gateway=gateway,
                executors=executors,
                owner_id=owner_id,
                admission_gate=gate,
            )
            reconciliation_service = GatewayBackedGraphReconciliationService(
                gateway=gateway,
                owner_id=owner_id,
                admission_gate=gate,
            )
            runtime = cls(
                checkpoint_runtime=checkpoint_runtime,
                persistence_probe=probe,
                durable_bulkhead=durable_bulkhead,
                security_runtime=security_runtime,
                gateway=gateway,
                stream_service=stream_service,
                reconciliation_service=reconciliation_service,
                admission_gate=gate,
                execution_verifier=InvocationEnvelopeVerifier(
                    key_resolver=security_runtime.resolver,
                ),
                reconciliation_verifier=ReconciliationEnvelopeVerifier(
                    key_resolver=security_runtime.resolver,
                ),
                target_e2e_verifier=target_e2e_verifier,
                mode=mode,
            )
            await gate.start()
            return runtime
        except BaseException:
            try:
                if durable_bulkhead is not None:
                    await durable_bulkhead.close()
            finally:
                try:
                    if security_runtime is not None:
                        await security_runtime.close()
                finally:
                    await checkpoint_runtime.close()
            raise

    @property
    def ready(self) -> bool:
        if (
            self._closed
            or not self._persistence_ready
            or not self._bulkhead_ready
            or not self._admission_gate.accepting
        ):
            return False
        try:
            return bool(self._security_runtime.readiness().ready)
        except Exception:
            return False

    @property
    def target_e2e_lifecycle_key_resolver(self) -> VerificationKeyResolver:
        if self._mode is not GraphGatewayMode.TARGET_E2E_CANDIDATE:
            raise GraphGatewayDisabledError("TARGET_E2E_LIFECYCLE_DISABLED")
        return self._security_runtime.resolver

    @property
    def target_e2e_lifecycle_pool(self) -> Any:
        if self._mode is not GraphGatewayMode.TARGET_E2E_CANDIDATE:
            raise GraphGatewayDisabledError("TARGET_E2E_LIFECYCLE_DISABLED")
        return self._checkpoint_runtime.pool

    async def check_readiness(self) -> GraphRuntimeReadiness:
        if self._closed:
            return GraphRuntimeReadiness(
                ready=False,
                mode=self._mode,
                code="GRAPH_RUNTIME_CLOSED",
                accepting=False,
                persistence_code="GRAPH_PERSISTENCE_CLOSED",
                security_code="GRAPH_JWKS_CLOSED",
                bulkhead_code="GRAPH_BULKHEAD_CLOSED",
            )
        try:
            persistence = await self._persistence_probe.check()
        except Exception:
            persistence = None
        self._persistence_ready = bool(persistence is not None and persistence.ready)
        try:
            security = self._security_runtime.readiness()
        except Exception:
            security = None
        try:
            bulkhead = await self._durable_bulkhead.check_readiness()
        except Exception:
            bulkhead = None
        self._bulkhead_ready = bool(bulkhead is not None and bulkhead.ready)
        ready = bool(
            persistence is not None
            and persistence.ready
            and security is not None
            and security.ready
            and bulkhead is not None
            and bulkhead.ready
            and self._admission_gate.accepting
        )
        if persistence is None:
            code = "GRAPH_PERSISTENCE_CHECK_FAILED"
        elif not persistence.ready:
            code = persistence.code
        elif security is None:
            code = "GRAPH_JWKS_CHECK_FAILED"
        elif not security.ready:
            code = security.code
        elif bulkhead is None:
            code = "GRAPH_BULKHEAD_CHECK_FAILED"
        elif not bulkhead.ready:
            code = bulkhead.code
        elif not self._admission_gate.accepting:
            code = "GRAPH_GATEWAY_DRAINING"
        else:
            code = "GRAPH_READY"
        return GraphRuntimeReadiness(
            ready=ready,
            mode=self._mode,
            code=code,
            accepting=self._admission_gate.accepting,
            persistence_code=(
                persistence.code if persistence is not None else "GRAPH_PERSISTENCE_CHECK_FAILED"
            ),
            security_code=(security.code if security is not None else "GRAPH_JWKS_CHECK_FAILED"),
            bulkhead_code=(
                bulkhead.code if bulkhead is not None else "GRAPH_BULKHEAD_CHECK_FAILED"
            ),
        )

    async def close(self) -> bool:
        async with self._close_lock:
            if self._close_complete:
                return self._drained
            self._closed = True
            drained = False
            bulkhead_drained = False
            try:
                try:
                    drained = await self._admission_gate.drain(GRAPH_SHUTDOWN_DRAIN_SECONDS)
                finally:
                    try:
                        await self._durable_bulkhead.drain()
                        bulkhead_drained = True
                    finally:
                        try:
                            await self._durable_bulkhead.close()
                        finally:
                            try:
                                await self._security_runtime.close()
                            finally:
                                await self._checkpoint_runtime.close()
            finally:
                self._drained = drained and bulkhead_drained
                self._close_complete = True
            return self._drained


def _build_target_e2e_runtime_bindings(
    *,
    settings: Settings,
    security_runtime: GraphSecurityRuntime,
) -> GraphRuntimeBindings:
    """Assemble the candidate executor only from this opened JWKS runtime.

    This is deliberately lifecycle-local.  The specialized providers retain
    the exact security runtime that accepted the candidate activation, and
    their factory cannot be reconstructed from settings before JWKS readiness.
    """

    if type(security_runtime) is not GraphSecurityRuntime or not security_runtime.readiness().ready:
        raise RuntimeError("target-E2E Graph security runtime is not ready")
    from app.graph_runtime.executor import TargetE2ESpecializedRoomProviderFactory
    from app.graph_runtime.production_bindings import build_graph_runtime_bindings
    from app.graph_runtime.target_e2e_room_exchange import JavaTargetE2ERoomExchange

    room_exchange = JavaTargetE2ERoomExchange(
        java_api_service_url=settings.java_api_service_url,
        java_service_secret=settings.java_service_secret,
    )
    provider_factory = TargetE2ESpecializedRoomProviderFactory(
        security_runtime=security_runtime,
        room_exchange=room_exchange,
    )
    return build_graph_runtime_bindings(
        settings,
        target_e2e_specialized_provider_factory=provider_factory,
    )


def _build_target_e2e_lifecycle_binding(
    settings: Settings,
) -> TargetE2ELifecycleBinding:
    if settings.graph_gateway_mode != "TARGET_E2E_CANDIDATE":
        raise ValueError("target-E2E lifecycle binding requires candidate mode")
    context = settings.graph_target_e2e_runtime_context
    manifest_hash = settings.target_e2e_activation_manifest_hash
    if context is None or manifest_hash is None:
        raise ValueError("target-E2E lifecycle deployment binding is incomplete")
    authority = TargetE2ERuntimeAuthority.from_context(
        context,
        settings.graph_target_e2e_bindings,
    )
    return TargetE2ELifecycleBinding(
        activation_id=context.activationId,
        environment_id=context.environmentId,
        environment_generation=context.environmentGeneration,
        manifest_hash=manifest_hash,
        runtime_context_hash=authority.context_hash,
    )


class GraphRuntimeHandle:
    """Stable route dependencies whose SHADOW implementation exists only inside lifespan."""

    def __init__(
        self,
        *,
        settings: Settings,
        bindings: GraphRuntimeBindings | None = None,
        runtime_factory: RuntimeFactory | None = None,
        transport_identity_resolver: TransportIdentityResolver | None = None,
    ) -> None:
        self._settings = settings
        self._mode = GraphGatewayMode(settings.graph_gateway_mode)
        if self._mode is GraphGatewayMode.TARGET_E2E_CANDIDATE and bindings is not None:
            raise ValueError(
                "target-E2E runtime bindings must be assembled inside the Graph lifecycle"
            )
        self._bindings = bindings
        self._runtime_factory = runtime_factory or GraphApplicationRuntime.open
        self._transport_identity_resolver = transport_identity_resolver or AsgiMtlsIdentityResolver(
            expected_spiffe_id=settings.graph_expected_spiffe_id
        )
        self._runtime: GraphRuntimeInstance | None = None
        self._execution_verifier = _RuntimeExecutionVerifier(self)
        self._target_e2e_verifier = _RuntimeTargetE2EVerifier(self)
        self._reconciliation_verifier = _RuntimeReconciliationVerifier(self)
        self._stream_service = _RuntimeStreamService(self)
        self._reconciliation_service = _RuntimeReconciliationService(self)
        self._target_e2e_lifecycle_binding = (
            _build_target_e2e_lifecycle_binding(settings)
            if self._mode is GraphGatewayMode.TARGET_E2E_CANDIDATE
            else None
        )
        self._target_e2e_lifecycle_verifier = _RuntimeTargetE2ELifecycleVerifier(self)
        self._target_e2e_lifecycle_reconciler = _RuntimeTargetE2ELifecycleReconciler(self)
        self._thread_resolver = (
            bindings.thread_identity_resolver
            if bindings is not None
            else TargetE2EThreadIdentityResolver()
            if self._mode is GraphGatewayMode.TARGET_E2E_CANDIDATE
            else _FailClosedThreadResolver()
        )

    @property
    def mode(self) -> GraphGatewayMode:
        return self._mode

    @property
    def ready(self) -> bool:
        try:
            return self._runtime is not None and self._runtime.ready
        except Exception:
            return False

    def endpoint_dependencies(self) -> GraphCommandEndpointDependencies:
        return GraphCommandEndpointDependencies(
            mode=self._mode.value,
            codec=_contract_codec(),
            transport_identity_resolver=self._transport_identity_resolver,
            envelope_verifier=self._execution_verifier,
            thread_identity_resolver=self._thread_resolver,
            stream_service=self._stream_service,
            ready=lambda: self.ready,
            target_e2e_envelope_verifier=self._target_e2e_verifier,
        )

    def reconciliation_endpoint_dependencies(
        self,
    ) -> GraphReconciliationEndpointDependencies:
        return GraphReconciliationEndpointDependencies(
            mode=self._mode.value,
            codec=_contract_codec(),
            transport_identity_resolver=self._transport_identity_resolver,
            envelope_verifier=self._reconciliation_verifier,
            thread_identity_resolver=cast(
                TrustedReconciliationThreadIdentityResolver,
                self._thread_resolver,
            ),
            reconciliation_service=self._reconciliation_service,
            ready=lambda: self.ready,
            target_e2e_envelope_verifier=self._target_e2e_verifier,
            target_e2e_thread_identity_resolver=cast(
                TrustedThreadIdentityResolver,
                self._thread_resolver,
            ),
        )

    def target_e2e_lifecycle_endpoint_dependencies(
        self,
    ) -> TargetE2ELifecycleEndpointDependencies:
        if (
            self._mode is not GraphGatewayMode.TARGET_E2E_CANDIDATE
            or self._target_e2e_lifecycle_binding is None
        ):
            raise ValueError("target-E2E lifecycle route is not available in this mode")
        return TargetE2ELifecycleEndpointDependencies(
            mode=self._mode.value,
            ready=lambda: self.ready,
            transport_identity_resolver=self._transport_identity_resolver,
            receipt_verifier=self._target_e2e_lifecycle_verifier,
            reconciler=self._target_e2e_lifecycle_reconciler,
        )

    @asynccontextmanager
    async def lifespan(self, app: Any) -> AsyncIterator[None]:
        if self._mode is GraphGatewayMode.DISABLED:
            yield
            return
        bindings = self._bindings
        if bindings is None:
            if self._mode is not GraphGatewayMode.TARGET_E2E_CANDIDATE:
                raise RuntimeError("active Graph mode requires trusted runtime bindings")
        runtime = await self._runtime_factory(self._settings, bindings)
        try:
            if not runtime.ready:
                raise RuntimeError("active Graph runtime did not become ready during startup")
            self._runtime = runtime
            try:
                yield
            finally:
                self._runtime = None
        finally:
            await runtime.close()

    async def check_readiness(self) -> GraphRuntimeReadiness:
        if self._mode is GraphGatewayMode.DISABLED:
            return GraphRuntimeReadiness(
                ready=True,
                mode=self._mode,
                code="GRAPH_DISABLED",
                accepting=False,
                persistence_code="GRAPH_DISABLED",
                security_code="GRAPH_DISABLED",
                bulkhead_code="GRAPH_DISABLED",
            )
        if self._runtime is None:
            return GraphRuntimeReadiness(
                ready=False,
                mode=self._mode,
                code="GRAPH_RUNTIME_NOT_STARTED",
                accepting=False,
                persistence_code="GRAPH_PERSISTENCE_NOT_STARTED",
                security_code="GRAPH_JWKS_NOT_STARTED",
                bulkhead_code="GRAPH_BULKHEAD_NOT_STARTED",
            )
        try:
            return await self._runtime.check_readiness()
        except Exception:
            return GraphRuntimeReadiness(
                ready=False,
                mode=self._mode,
                code="GRAPH_READINESS_CHECK_FAILED",
                accepting=False,
                persistence_code="GRAPH_READINESS_CHECK_FAILED",
                security_code="GRAPH_READINESS_CHECK_FAILED",
                bulkhead_code="GRAPH_READINESS_CHECK_FAILED",
            )

    def require_runtime(self) -> GraphRuntimeInstance:
        runtime = self._runtime
        if runtime is None:
            raise GraphGatewayDisabledError("GRAPH_GATEWAY_NOT_READY")
        try:
            ready = runtime.ready
        except Exception:
            ready = False
        if not ready:
            raise GraphGatewayDisabledError("GRAPH_GATEWAY_NOT_READY")
        return runtime

    def require_target_e2e_lifecycle_binding(self) -> TargetE2ELifecycleBinding:
        binding = self._target_e2e_lifecycle_binding
        if self._mode is not GraphGatewayMode.TARGET_E2E_CANDIDATE or binding is None:
            raise GraphGatewayDisabledError("TARGET_E2E_LIFECYCLE_DISABLED")
        return binding


class _RuntimeTargetE2ELifecycleVerifier:
    def __init__(self, handle: GraphRuntimeHandle) -> None:
        self._handle = handle

    def verify(
        self,
        *,
        token: str,
        transport_identity: TransportIdentity,
    ) -> VerifiedTargetE2ELifecycleReceipt:
        runtime = self._handle.require_runtime()
        verifier = TargetE2ELifecycleReceiptVerifier(
            key_resolver=runtime.target_e2e_lifecycle_key_resolver,
            expected_binding=self._handle.require_target_e2e_lifecycle_binding(),
        )
        return verifier.verify(token=token, transport_identity=transport_identity)


class _RuntimeTargetE2ELifecycleReconciler:
    def __init__(self, handle: GraphRuntimeHandle) -> None:
        self._handle = handle
        self._repository = PostgresTargetE2ELifecycleRepository()

    async def reconcile(
        self,
        verified: VerifiedTargetE2ELifecycleReceipt,
    ) -> TargetE2ELifecycleReconciliation:
        runtime = self._handle.require_runtime()
        reconciler = TargetE2ELifecycleReconciler(
            pool=runtime.target_e2e_lifecycle_pool,
            repository=self._repository,
            expected_binding=self._handle.require_target_e2e_lifecycle_binding(),
        )
        return await reconciler.reconcile(verified)


class _RuntimeExecutionVerifier:
    def __init__(self, handle: GraphRuntimeHandle) -> None:
        self._handle = handle

    def verify(
        self,
        *,
        token: str,
        command: RoomGraphCommand,
        transport_identity: TransportIdentity,
    ) -> VerifiedInvocation:
        verified = self._handle.require_runtime().execution_verifier.verify(
            token=token,
            command=command,
            transport_identity=transport_identity,
        )
        if not isinstance(verified, VerifiedInvocation):
            raise InvocationEnvelopeError("INVOCATION_EXECUTION_CREDENTIAL_TYPE_REJECTED")
        return verified


class _RuntimeTargetE2EVerifier:
    def __init__(self, handle: GraphRuntimeHandle) -> None:
        self._handle = handle

    def verify_envelope(
        self,
        *,
        token: str,
        envelope: TargetE2EGraphCommandEnvelope,
        transport_identity: TransportIdentity,
    ) -> VerifiedTargetE2EInvocation:
        verifier = self._handle.require_runtime().target_e2e_verifier
        if verifier is None:
            raise InvocationEnvelopeError("TARGET_E2E_VERIFIER_NOT_CONFIGURED")
        verified = verifier.verify_envelope(
            token=token,
            envelope=envelope,
            transport_identity=transport_identity,
        )
        if not isinstance(verified, VerifiedTargetE2EInvocation):
            raise InvocationEnvelopeError("TARGET_E2E_CREDENTIAL_TYPE_REJECTED")
        return verified

    def verify_envelope_for_reconciliation(
        self,
        *,
        token: str,
        envelope: TargetE2EGraphCommandEnvelope,
        transport_identity: TransportIdentity,
    ) -> VerifiedTargetE2EInvocation:
        verifier = self._handle.require_runtime().target_e2e_verifier
        if verifier is None:
            raise InvocationEnvelopeError("TARGET_E2E_VERIFIER_NOT_CONFIGURED")
        verified = verifier.verify_envelope_for_reconciliation(
            token=token,
            envelope=envelope,
            transport_identity=transport_identity,
        )
        if not isinstance(verified, VerifiedTargetE2EInvocation):
            raise InvocationEnvelopeError("TARGET_E2E_CREDENTIAL_TYPE_REJECTED")
        return verified


class _RuntimeReconciliationVerifier:
    def __init__(self, handle: GraphRuntimeHandle) -> None:
        self._handle = handle

    def verify(
        self,
        *,
        token: str,
        command: RoomGraphCommand,
        transport_identity: TransportIdentity,
    ) -> VerifiedReconciliation:
        verified = self._handle.require_runtime().reconciliation_verifier.verify(
            token=token,
            command=command,
            transport_identity=transport_identity,
        )
        if not isinstance(verified, VerifiedReconciliation):
            raise InvocationEnvelopeError("INVOCATION_RECONCILIATION_CREDENTIAL_TYPE_REJECTED")
        return verified


class _RuntimeStreamService:
    def __init__(self, handle: GraphRuntimeHandle) -> None:
        self._handle = handle

    async def open_stream(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation,
        expected_thread: ThreadIdentity,
    ) -> AsyncIterator[AgentStreamEvent]:
        return await self._handle.require_runtime().stream_service.open_stream(
            command=command,
            verified_invocation=verified_invocation,
            expected_thread=expected_thread,
        )


class _RuntimeReconciliationService:
    def __init__(self, handle: GraphRuntimeHandle) -> None:
        self._handle = handle

    async def reconcile(
        self,
        *,
        command: RoomGraphCommand,
        verified_reconciliation: VerifiedReconciliation,
        expected_thread: ThreadIdentity,
    ) -> GraphReconcileResponse:
        return await self._handle.require_runtime().reconciliation_service.reconcile(
            command=command,
            verified_reconciliation=verified_reconciliation,
            expected_thread=expected_thread,
        )

    async def reconcile_target_e2e(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedTargetE2EInvocation,
        expected_thread: ThreadIdentity,
    ) -> TargetE2EReconciliationArtifacts:
        return await (
            self._handle.require_runtime().reconciliation_service.reconcile_target_e2e(
                command=command,
                verified_invocation=verified_invocation,
                expected_thread=expected_thread,
            )
        )

    async def retrieve_target_e2e_proposal_source(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedTargetE2EInvocation,
        expected_thread: ThreadIdentity,
        expected_result_ref: str,
        expected_proposal_hash: str,
    ) -> TargetE2ERoomProposalSource:
        return await (
            self._handle.require_runtime()
            .reconciliation_service.retrieve_target_e2e_proposal_source(
                command=command,
                verified_invocation=verified_invocation,
                expected_thread=expected_thread,
                expected_result_ref=expected_result_ref,
                expected_proposal_hash=expected_proposal_hash,
            )
        )


class _FailClosedThreadResolver:
    async def resolve(self, **kwargs: Any) -> ThreadIdentity:
        raise GraphGatewayDisabledError("GRAPH_TRUSTED_THREAD_RESOLVER_MISSING")


def create_graph_readiness_router(handle: GraphRuntimeHandle) -> APIRouter:
    router = APIRouter()

    @router.get(GRAPH_READY_PATH, response_model=None)
    async def graph_readiness() -> JSONResponse:
        report = await handle.check_readiness()
        return JSONResponse(
            status_code=200 if report.ready else 503,
            content=report.public_payload(),
            headers={
                "Cache-Control": "no-store, no-transform",
                "Pragma": "no-cache",
                "X-Content-Type-Options": "nosniff",
            },
        )

    return router


@lru_cache(maxsize=1)
def _contract_codec() -> ContractCodec:
    root = Path(__file__).resolve().parents[3] / "contracts" / "agent-platform" / "v1"
    return ContractCodec(root)


def _process_owner_id() -> str:
    host = "".join(
        character if character.isalnum() or character in "._:-" else "_"
        for character in socket.gethostname()
    )[:64]
    return f"{host or 'graph-replica'}:{uuid4().hex}"
