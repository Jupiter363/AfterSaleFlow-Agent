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
)
from app.api.graph_reconciliation_service import (
    GatewayBackedGraphReconciliationService,
    GraphReconciliationService,
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
from app.graph_runtime.readiness import GraphPersistenceReadinessProbe
from app.security.graph_runtime import (
    GraphSecurityRuntime,
    _open_for_lifecycle as _open_graph_security_runtime_for_lifecycle,
)
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    InvocationEnvelopeVerifier,
    ReconciliationEnvelopeVerifier,
    TransportIdentity,
    VerifiedInvocation,
    VerifiedReconciliation,
)
from app.security.transport_identity import AsgiMtlsIdentityResolver


GRAPH_READY_PATH = "/ready/graph"
GRAPH_SHUTDOWN_DRAIN_SECONDS = 5.0


@dataclass(frozen=True, slots=True)
class GraphExecutorKernel:
    """Process-owned resources that every compiled Graph executor must share."""

    saver: FencedPostgresSaver
    gateway: GraphCommandGateway


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

    def public_payload(self) -> dict[str, str | bool]:
        return {
            "ready": self.ready,
            "mode": self.mode.value,
            "code": self.code,
            "accepting": self.accepting,
            "persistence_code": self.persistence_code,
            "security_code": self.security_code,
        }


class GraphRuntimeInstance(Protocol):
    execution_verifier: InvocationEnvelopeVerifier
    reconciliation_verifier: ReconciliationEnvelopeVerifier
    stream_service: GraphCommandStreamService
    reconciliation_service: GraphReconciliationService

    @property
    def ready(self) -> bool: ...

    async def check_readiness(self) -> GraphRuntimeReadiness: ...

    async def close(self) -> bool: ...


RuntimeFactory = Callable[
    [Settings, GraphRuntimeBindings],
    Awaitable[GraphRuntimeInstance],
]


class GraphApplicationRuntime:
    """One process-lifetime pool, saver, gateway, key runtime, and admission gate."""

    def __init__(
        self,
        *,
        checkpoint_runtime: GraphCheckpointRuntime,
        persistence_probe: GraphPersistenceReadinessProbe,
        security_runtime: GraphSecurityRuntime,
        gateway: GraphCommandGateway,
        stream_service: GatewayBackedGraphCommandStreamService,
        reconciliation_service: GatewayBackedGraphReconciliationService,
        admission_gate: GraphStreamAdmissionGate,
        execution_verifier: InvocationEnvelopeVerifier,
        reconciliation_verifier: ReconciliationEnvelopeVerifier,
    ) -> None:
        self._checkpoint_runtime = checkpoint_runtime
        self._persistence_probe = persistence_probe
        self._security_runtime = security_runtime
        self._gateway = gateway
        self.stream_service = stream_service
        self.reconciliation_service = reconciliation_service
        self._admission_gate = admission_gate
        self.execution_verifier = execution_verifier
        self.reconciliation_verifier = reconciliation_verifier
        self._persistence_ready = True
        self._closed = False
        self._close_complete = False
        self._drained = False
        self._close_lock = asyncio.Lock()

    @classmethod
    async def open(
        cls,
        settings: Settings,
        bindings: GraphRuntimeBindings,
    ) -> GraphApplicationRuntime:
        mode = GraphGatewayMode(settings.graph_gateway_mode)
        if mode is not GraphGatewayMode.SHADOW:
            raise ValueError("Graph application runtime opens only in SHADOW mode")
        if settings.graph_database_dsn is None or settings.graph_jwks_url is None:
            raise ValueError("SHADOW Graph dependencies are incomplete")
        if not callable(bindings.executor_registry_factory):
            raise ValueError("SHADOW Graph runtime requires an executor registry factory")

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
        try:
            report = await probe.check()
            if not report.ready:
                raise RuntimeError(report.code)
            gateway = GraphCommandGateway(
                mode=mode,
                pool=checkpoint_runtime.pool,
                input_authorizer=bindings.input_authorizer,
                acquire_timeout_seconds=settings.graph_pool_acquire_timeout_seconds,
            )
            security_runtime = await _open_graph_security_runtime_for_lifecycle(
                jwks_url=str(settings.graph_jwks_url),
                timeout_seconds=settings.graph_jwks_timeout_seconds,
                refresh_interval_seconds=settings.graph_jwks_refresh_seconds,
                referenced_key_ids=gateway.referenced_verification_key_ids,
            )
            executors = bindings.executor_registry_factory(
                GraphExecutorKernel(
                    saver=checkpoint_runtime.saver,
                    gateway=gateway,
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
            )
            await gate.start()
            return runtime
        except BaseException:
            try:
                if security_runtime is not None:
                    await security_runtime.close()
            finally:
                await checkpoint_runtime.close()
            raise

    @property
    def ready(self) -> bool:
        if self._closed or not self._persistence_ready or not self._admission_gate.accepting:
            return False
        try:
            return bool(self._security_runtime.readiness().ready)
        except Exception:
            return False

    async def check_readiness(self) -> GraphRuntimeReadiness:
        if self._closed:
            return GraphRuntimeReadiness(
                ready=False,
                mode=GraphGatewayMode.SHADOW,
                code="GRAPH_RUNTIME_CLOSED",
                accepting=False,
                persistence_code="GRAPH_PERSISTENCE_CLOSED",
                security_code="GRAPH_JWKS_CLOSED",
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
        ready = bool(
            persistence is not None
            and persistence.ready
            and security is not None
            and security.ready
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
        elif not self._admission_gate.accepting:
            code = "GRAPH_GATEWAY_DRAINING"
        else:
            code = "GRAPH_READY"
        return GraphRuntimeReadiness(
            ready=ready,
            mode=GraphGatewayMode.SHADOW,
            code=code,
            accepting=self._admission_gate.accepting,
            persistence_code=(
                persistence.code if persistence is not None else "GRAPH_PERSISTENCE_CHECK_FAILED"
            ),
            security_code=(security.code if security is not None else "GRAPH_JWKS_CHECK_FAILED"),
        )

    async def close(self) -> bool:
        async with self._close_lock:
            if self._close_complete:
                return self._drained
            self._closed = True
            drained = False
            try:
                try:
                    drained = await self._admission_gate.drain(GRAPH_SHUTDOWN_DRAIN_SECONDS)
                finally:
                    try:
                        await self._security_runtime.close()
                    finally:
                        await self._checkpoint_runtime.close()
            finally:
                self._drained = drained
                self._close_complete = True
            return drained


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
        self._bindings = bindings
        self._runtime_factory = runtime_factory or GraphApplicationRuntime.open
        self._transport_identity_resolver = transport_identity_resolver or AsgiMtlsIdentityResolver(
            expected_spiffe_id=settings.graph_expected_spiffe_id
        )
        self._runtime: GraphRuntimeInstance | None = None
        self._execution_verifier = _RuntimeExecutionVerifier(self)
        self._reconciliation_verifier = _RuntimeReconciliationVerifier(self)
        self._stream_service = _RuntimeStreamService(self)
        self._reconciliation_service = _RuntimeReconciliationService(self)
        self._thread_resolver = (
            bindings.thread_identity_resolver
            if bindings is not None
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
        )

    @asynccontextmanager
    async def lifespan(self, app: Any) -> AsyncIterator[None]:
        if self._mode is GraphGatewayMode.DISABLED:
            yield
            return
        if self._bindings is None:
            raise RuntimeError("SHADOW Graph mode requires trusted runtime bindings")
        runtime = await self._runtime_factory(self._settings, self._bindings)
        try:
            if not runtime.ready:
                raise RuntimeError("SHADOW Graph runtime did not become ready during startup")
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
            )
        if self._runtime is None:
            return GraphRuntimeReadiness(
                ready=False,
                mode=self._mode,
                code="GRAPH_RUNTIME_NOT_STARTED",
                accepting=False,
                persistence_code="GRAPH_PERSISTENCE_NOT_STARTED",
                security_code="GRAPH_JWKS_NOT_STARTED",
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
