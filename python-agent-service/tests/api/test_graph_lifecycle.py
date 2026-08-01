from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any, cast

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import app.api.graph_lifecycle as graph_lifecycle
from app.api.graph_lifecycle import (
    GraphApplicationRuntime,
    GraphExecutorKernel,
    GraphRuntimeBindings,
    GraphRuntimeHandle,
    GraphRuntimeReadiness,
    create_graph_readiness_router,
)
from app.api.graph_stream_service import (
    ExactShadowExecutorRegistry,
    ProviderRuntimeBinding,
    ShadowExecutorRegistration,
)
from app.config import Settings
from app.graph_runtime.persistence_models import GraphGatewayMode
from app.graph_runtime.registry import VersionBinding
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    TransportIdentity,
    VerifiedInvocation,
    VerifiedReconciliation,
)


BASE_SETTINGS = {
    "litellm_master_key": "test-litellm-master-key",
    "langfuse_public_key": "test-public-key",
    "langfuse_secret_key": "test-secret-key",
    "java_service_secret": "test-java-service-secret",
    "python_agent_service_secret": "test-python-service-secret",
}


def _settings(**overrides: Any) -> Settings:
    return Settings(**{**BASE_SETTINGS, **overrides})


def _shadow_settings() -> Settings:
    return _settings(
        graph_gateway_mode="SHADOW",
        graph_database_dsn=("postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"),
        graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
        graph_expected_environment_generation="graphenv-test-001",
        graph_expected_restore_verification_hash="a" * 64,
    )


class _ThreadResolver:
    async def resolve(self, **kwargs: Any) -> Any:
        raise AssertionError("not used")


class _InputAuthorizer:
    async def authorize(self, **kwargs: Any) -> None:
        return None


class _Executor:
    async def stream(self, execution: Any):
        raise AssertionError("not used")
        yield


def _registered_executors() -> ExactShadowExecutorRegistry:
    binding = VersionBinding(
        graph_key="test.graph",
        graph_version="1.0.0",
        checkpoint_schema_version="checkpoint.v1",
        state_schema_version="state.v1",
        state_schema_hash="a" * 64,
        command_schema_version="room-graph-command.v1",
        result_schema_version="room-graph-result.v1",
        prompt_version="prompt.v1",
        model_profile_id="model.v1",
        output_schema_version="output.v1",
        policy_version="policy.v1",
        guardrail_version="guardrail.v1",
        tool_policy_version="tools.none.v1",
        binding_hash="b" * 64,
        code_build_id="build.v1",
    )
    return ExactShadowExecutorRegistry(
        [
            ShadowExecutorRegistration(
                binding=binding,
                executor=cast(Any, _Executor()),
                provider_binding=ProviderRuntimeBinding(
                    model_profile_id=binding.model_profile_id,
                    provider="litellm",
                    model="qwen3.7-plus",
                    allowed_nodes=frozenset({"test_node"}),
                ),
            )
        ]
    )


def _empty_executor_registry_factory(
    kernel: GraphExecutorKernel,
) -> ExactShadowExecutorRegistry:
    return ExactShadowExecutorRegistry()


def _bindings(
    *,
    executor_registry_factory: Any = _empty_executor_registry_factory,
) -> GraphRuntimeBindings:
    return GraphRuntimeBindings(
        thread_identity_resolver=cast(Any, _ThreadResolver()),
        input_authorizer=cast(Any, _InputAuthorizer()),
        executor_registry_factory=executor_registry_factory,
    )


class _FakeRuntime:
    def __init__(
        self,
        events: list[str],
        *,
        ready: bool = True,
        ready_error: Exception | None = None,
        check_error: Exception | None = None,
    ) -> None:
        self.events = events
        self._ready = ready
        self._ready_error = ready_error
        self._check_error = check_error
        self.execution_verifier = cast(Any, object())
        self.reconciliation_verifier = cast(Any, object())
        self.stream_service = cast(Any, object())
        self.reconciliation_service = cast(Any, object())

    @property
    def ready(self) -> bool:
        if self._ready_error is not None:
            raise self._ready_error
        return self._ready

    async def check_readiness(self) -> GraphRuntimeReadiness:
        self.events.append("check")
        if self._check_error is not None:
            raise self._check_error
        return GraphRuntimeReadiness(
            ready=self._ready,
            mode=GraphGatewayMode.SHADOW,
            code="GRAPH_READY" if self._ready else "GRAPH_NOT_READY",
            accepting=self._ready,
            persistence_code="GRAPH_PERSISTENCE_READY",
            security_code="GRAPH_JWKS_READY",
        )

    async def close(self) -> bool:
        self.events.append("close")
        self._ready = False
        return True


def _install_open_dependencies(
    monkeypatch: pytest.MonkeyPatch,
    events: list[str],
    *,
    gate_start_error: Exception | None = None,
    security_close_error: Exception | None = None,
    bulkhead_ready: bool = True,
) -> tuple[Any, list[Any], list[Any]]:
    pool = object()
    saver = object()
    gateways: list[Any] = []
    durable_bulkheads: list[Any] = []

    class CheckpointRuntime:
        def __init__(self) -> None:
            self.pool = pool
            self.saver = saver

        @classmethod
        async def open(cls, dsn: str, config: Any) -> CheckpointRuntime:
            assert config.idle_in_transaction_timeout_ms == 150_000
            events.append("checkpoint_open")
            return cls()

        async def close(self) -> None:
            events.append("checkpoint_close")

    class PersistenceProbe:
        def __init__(self, config: Any, actual_pool: Any) -> None:
            assert actual_pool is pool

        async def check(self) -> Any:
            events.append("persistence_check")
            return SimpleNamespace(ready=True, code="GRAPH_PERSISTENCE_READY")

    class Gateway:
        def __init__(self, **kwargs: Any) -> None:
            assert kwargs["pool"] is pool
            gateways.append(self)

        async def referenced_verification_key_ids(self) -> frozenset[str]:
            return frozenset()

    class DurableBulkhead:
        def __init__(self, actual_pool: Any, config: Any) -> None:
            assert actual_pool is pool
            assert config.global_limit == 32
            assert config.tenant_limit == 16
            assert config.room_limit == 8
            assert config.global_queue_limit == 256
            assert config.tenant_queue_limit == 128
            assert config.room_queue_limit == 100
            assert config.permit_lease_seconds == 20
            durable_bulkheads.append(self)
            self.ready = bulkhead_ready

        async def open(self) -> None:
            events.append("bulkhead_open")

        async def check_readiness(self) -> Any:
            events.append("bulkhead_readiness")
            return SimpleNamespace(
                ready=self.ready,
                code="GRAPH_BULKHEAD_READY" if self.ready else "GRAPH_BULKHEAD_UNAVAILABLE",
            )

        async def drain(self) -> bool:
            events.append("bulkhead_drain")
            return True

        async def close(self) -> None:
            events.append("bulkhead_close")

    class SecurityRuntime:
        resolver = object()

        def readiness(self) -> Any:
            return SimpleNamespace(ready=True, code="GRAPH_JWKS_READY")

        async def close(self) -> None:
            events.append("security_close")
            if security_close_error is not None:
                raise security_close_error

    async def open_security_runtime(**kwargs: Any) -> SecurityRuntime:
        del kwargs
        events.append("security_open")
        return SecurityRuntime()

    class AdmissionGate:
        accepting = False

        async def start(self) -> None:
            events.append("gate_start")
            if gate_start_error is not None:
                raise gate_start_error
            self.accepting = True

        async def drain(self, timeout_seconds: float) -> bool:
            events.append("drain")
            self.accepting = False
            return True

    monkeypatch.setattr(graph_lifecycle, "GraphCheckpointRuntime", CheckpointRuntime)
    monkeypatch.setattr(graph_lifecycle, "GraphPersistenceReadinessProbe", PersistenceProbe)
    monkeypatch.setattr(graph_lifecycle, "GraphCommandGateway", Gateway)
    monkeypatch.setattr(graph_lifecycle, "PostgresGraphFanoutBulkhead", DurableBulkhead)
    monkeypatch.setattr(
        graph_lifecycle,
        "_open_graph_security_runtime_for_lifecycle",
        open_security_runtime,
    )
    monkeypatch.setattr(graph_lifecycle, "GraphStreamAdmissionGate", AdmissionGate)
    return saver, gateways, durable_bulkheads


@pytest.mark.asyncio
async def test_disabled_lifespan_never_constructs_graph_dependencies() -> None:
    runtime_calls = 0
    registry_calls = 0

    async def forbidden_factory(settings: Settings, bindings: GraphRuntimeBindings):
        nonlocal runtime_calls
        runtime_calls += 1
        raise AssertionError("disabled mode must not open Graph dependencies")

    def forbidden_registry_factory(
        kernel: GraphExecutorKernel,
    ) -> ExactShadowExecutorRegistry:
        nonlocal registry_calls
        registry_calls += 1
        raise AssertionError("disabled mode must not construct Graph executors")

    handle = GraphRuntimeHandle(
        settings=_settings(),
        bindings=_bindings(
            executor_registry_factory=forbidden_registry_factory,
        ),
        runtime_factory=forbidden_factory,
    )

    async with handle.lifespan(None):
        report = await handle.check_readiness()
        assert report.ready is True
        assert report.code == "GRAPH_DISABLED"
        assert handle.ready is False

    assert runtime_calls == 0
    assert registry_calls == 0
    assert handle.endpoint_dependencies().mode == "DISABLED"
    assert handle.reconciliation_endpoint_dependencies().mode == "DISABLED"


def test_target_e2e_bindings_are_assembled_from_the_open_security_runtime(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The candidate provider factory must receive the opened runtime, not settings."""

    import app.graph_runtime.executor as executor
    import app.graph_runtime.production_bindings as production_bindings

    class SecurityRuntime:
        def readiness(self) -> Any:
            return SimpleNamespace(ready=True)

    captured: dict[str, Any] = {}

    class ProviderFactory:
        def __init__(self, **kwargs: Any) -> None:
            captured["security_runtime"] = kwargs["security_runtime"]
            captured["room_exchange"] = kwargs["room_exchange"]

    expected = _bindings()

    def build_bindings(settings: Settings, **kwargs: Any) -> GraphRuntimeBindings:
        captured["settings"] = settings
        captured["provider_factory"] = kwargs["target_e2e_specialized_provider_factory"]
        return expected

    monkeypatch.setattr(graph_lifecycle, "GraphSecurityRuntime", SecurityRuntime)
    monkeypatch.setattr(executor, "TargetE2ESpecializedRoomProviderFactory", ProviderFactory)
    monkeypatch.setattr(production_bindings, "build_graph_runtime_bindings", build_bindings)

    runtime = SecurityRuntime()
    actual = graph_lifecycle._build_target_e2e_runtime_bindings(
        settings=_settings(),
        security_runtime=cast(Any, runtime),
    )

    assert actual is expected
    assert captured["settings"].java_api_service_url == "http://java-api-service:8080"
    assert captured["security_runtime"] is runtime
    assert captured["provider_factory"] is not None
    assert captured["room_exchange"]._origin == "http://java-api-service:8080"


@pytest.mark.asyncio
async def test_target_e2e_handle_defers_binding_construction_to_runtime_lifecycle() -> None:
    events: list[str] = []

    async def runtime_factory(
        settings: Settings,
        bindings: GraphRuntimeBindings | None,
    ) -> _FakeRuntime:
        del settings
        assert bindings is None
        events.append("runtime_open")
        return _FakeRuntime(events)

    handle = GraphRuntimeHandle(settings=_settings(), runtime_factory=runtime_factory)
    handle._mode = GraphGatewayMode.TARGET_E2E_CANDIDATE

    async with handle.lifespan(None):
        assert handle.ready is True

    assert events == ["runtime_open", "close"]


def test_disabled_readiness_route_is_dependency_free_and_noncacheable() -> None:
    handle = GraphRuntimeHandle(settings=_settings())
    app = FastAPI()
    app.include_router(create_graph_readiness_router(handle))

    response = TestClient(app).get("/ready/graph")

    assert response.status_code == 200
    assert response.json() == {
        "ready": True,
        "mode": "DISABLED",
        "code": "GRAPH_DISABLED",
        "accepting": False,
        "persistence_code": "GRAPH_DISABLED",
        "security_code": "GRAPH_DISABLED",
        "bulkhead_code": "GRAPH_DISABLED",
    }
    assert response.headers["cache-control"] == "no-store, no-transform"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["x-content-type-options"] == "nosniff"


def test_main_registers_execution_reconciliation_and_readiness_routes() -> None:
    from app.main import create_app

    application = create_app(settings=_settings())
    with TestClient(application) as client:
        execution = client.post("/internal/graphs/commands/stream")
        reconciliation = client.post("/internal/graphs/commands/reconcile")
        readiness = client.get("/ready/graph")

    assert execution.status_code == 503
    assert execution.json()["code"] == "GRAPH_GATEWAY_DISABLED"
    assert reconciliation.status_code == 503
    assert reconciliation.json()["code"] == "GRAPH_GATEWAY_DISABLED"
    assert readiness.status_code == 200
    assert readiness.json()["code"] == "GRAPH_DISABLED"


def test_main_rejects_shadow_startup_without_a_deployment_manifest() -> None:
    from app.main import create_app

    with pytest.raises(ValueError, match="signed-synthetic SHADOW bindings are incomplete"):
        create_app(settings=_shadow_settings())


def test_main_rejects_injected_shadow_authority_outside_local_or_test() -> None:
    from app.main import create_app

    settings = _shadow_settings().model_copy(update={"app_env": "production"})
    with pytest.raises(ValueError, match="deployment-owned exact bindings"):
        create_app(
            settings=settings,
            graph_runtime_bindings=_bindings(),
        )

    handle = GraphRuntimeHandle(settings=settings, bindings=_bindings())
    with pytest.raises(ValueError, match="deployment-owned exact bindings"):
        create_app(
            settings=settings,
            graph_runtime_handle=handle,
        )


@pytest.mark.asyncio
async def test_shadow_lifespan_installs_only_a_ready_runtime_and_closes_it() -> None:
    events: list[str] = []
    runtime = _FakeRuntime(events)

    async def factory(settings: Settings, bindings: GraphRuntimeBindings):
        events.append("open")
        assert settings.graph_gateway_mode == "SHADOW"
        assert bindings is configured
        return runtime

    configured = _bindings()
    handle = GraphRuntimeHandle(
        settings=_shadow_settings(),
        bindings=configured,
        runtime_factory=factory,
    )

    assert (await handle.check_readiness()).code == "GRAPH_RUNTIME_NOT_STARTED"
    async with handle.lifespan(None):
        assert handle.ready is True
        report = await handle.check_readiness()
        assert report.ready is True
        assert report.code == "GRAPH_READY"
    assert handle.ready is False
    assert events == ["open", "check", "close"]


@pytest.mark.asyncio
async def test_runtime_handle_exposes_distinct_typed_verifier_and_service_proxies() -> None:
    events: list[str] = []
    runtime = _FakeRuntime(events)
    execution = VerifiedInvocation(
        claims=cast(Any, object()),
        key_id="execution-key",
        request_hash="a" * 64,
        transport_certificate_sha256="b" * 64,
    )
    reconciliation = VerifiedReconciliation(
        claims=cast(Any, object()),
        key_id="reconciliation-key",
        request_hash="a" * 64,
        transport_certificate_sha256="b" * 64,
    )

    class StaticVerifier:
        def __init__(self, result: Any) -> None:
            self.result = result

        def verify(self, **kwargs: Any) -> Any:
            return self.result

    async def empty_stream():
        if False:
            yield None

    stream = empty_stream()
    reconciliation_response = object()

    class StreamService:
        async def open_stream(self, **kwargs: Any) -> Any:
            return stream

    class ReconciliationService:
        async def reconcile(self, **kwargs: Any) -> Any:
            return reconciliation_response

    runtime.execution_verifier = cast(Any, StaticVerifier(execution))
    runtime.reconciliation_verifier = cast(Any, StaticVerifier(reconciliation))
    runtime.stream_service = cast(Any, StreamService())
    runtime.reconciliation_service = cast(Any, ReconciliationService())

    async def factory(settings: Settings, bindings: GraphRuntimeBindings) -> _FakeRuntime:
        return runtime

    handle = GraphRuntimeHandle(
        settings=_shadow_settings(),
        bindings=_bindings(),
        runtime_factory=factory,
    )
    execution_dependencies = handle.endpoint_dependencies()
    reconciliation_dependencies = handle.reconciliation_endpoint_dependencies()
    command = cast(Any, object())
    thread = cast(Any, object())
    transport = TransportIdentity("java-api-service", True, "c" * 64)

    assert execution_dependencies.envelope_verifier is not (
        reconciliation_dependencies.envelope_verifier
    )
    assert execution_dependencies.stream_service is not (
        reconciliation_dependencies.reconciliation_service
    )
    assert execution_dependencies.thread_identity_resolver is (
        reconciliation_dependencies.thread_identity_resolver
    )

    async with handle.lifespan(None):
        assert (
            execution_dependencies.envelope_verifier.verify(
                token="execution-token",
                command=command,
                transport_identity=transport,
            )
            is execution
        )
        assert (
            reconciliation_dependencies.envelope_verifier.verify(
                token="reconciliation-token",
                command=command,
                transport_identity=transport,
            )
            is reconciliation
        )
        assert (
            await execution_dependencies.stream_service.open_stream(
                command=command,
                verified_invocation=execution,
                expected_thread=thread,
            )
            is stream
        )
        assert (
            await reconciliation_dependencies.reconciliation_service.reconcile(
                command=command,
                verified_reconciliation=reconciliation,
                expected_thread=thread,
            )
            is reconciliation_response
        )

        runtime.execution_verifier = cast(Any, StaticVerifier(reconciliation))
        runtime.reconciliation_verifier = cast(Any, StaticVerifier(execution))
        with pytest.raises(
            InvocationEnvelopeError,
            match="EXECUTION_CREDENTIAL_TYPE_REJECTED",
        ):
            execution_dependencies.envelope_verifier.verify(
                token="wrong-token",
                command=command,
                transport_identity=transport,
            )
        with pytest.raises(
            InvocationEnvelopeError,
            match="RECONCILIATION_CREDENTIAL_TYPE_REJECTED",
        ):
            reconciliation_dependencies.envelope_verifier.verify(
                token="wrong-token",
                command=command,
                transport_identity=transport,
            )

    await stream.aclose()


@pytest.mark.asyncio
async def test_shadow_startup_rejects_and_closes_a_nonready_runtime() -> None:
    events: list[str] = []
    runtime = _FakeRuntime(events, ready=False)

    async def factory(settings: Settings, bindings: GraphRuntimeBindings):
        events.append("open")
        return runtime

    handle = GraphRuntimeHandle(
        settings=_shadow_settings(),
        bindings=_bindings(),
        runtime_factory=factory,
    )

    with pytest.raises(RuntimeError, match="did not become ready"):
        async with handle.lifespan(None):
            raise AssertionError("nonready runtime must not enter serving lifespan")

    assert events == ["open", "close"]
    assert handle.ready is False


@pytest.mark.asyncio
async def test_shadow_startup_closes_runtime_when_ready_probe_raises() -> None:
    events: list[str] = []
    runtime = _FakeRuntime(events, ready_error=RuntimeError("readiness failed"))

    async def factory(settings: Settings, bindings: GraphRuntimeBindings):
        events.append("open")
        return runtime

    handle = GraphRuntimeHandle(
        settings=_shadow_settings(),
        bindings=_bindings(),
        runtime_factory=factory,
    )

    with pytest.raises(RuntimeError, match="readiness failed"):
        async with handle.lifespan(None):
            raise AssertionError("startup readiness failure must not serve")

    assert events == ["open", "close"]
    assert handle.ready is False


@pytest.mark.asyncio
async def test_shadow_mode_without_trusted_bindings_fails_before_factory() -> None:
    calls = 0

    async def forbidden_factory(settings: Settings, bindings: GraphRuntimeBindings):
        nonlocal calls
        calls += 1
        raise AssertionError("factory must not run without trusted bindings")

    handle = GraphRuntimeHandle(
        settings=_shadow_settings(),
        runtime_factory=forbidden_factory,
    )

    with pytest.raises(RuntimeError, match="trusted runtime bindings"):
        async with handle.lifespan(None):
            raise AssertionError("missing bindings must fail closed")
    assert calls == 0


@pytest.mark.asyncio
async def test_shadow_application_runtime_rejects_a_missing_executor_factory() -> None:
    bindings = _bindings(executor_registry_factory=cast(Any, None))

    with pytest.raises(ValueError, match="executor registry factory"):
        await GraphApplicationRuntime.open(_shadow_settings(), bindings)


@pytest.mark.asyncio
async def test_shadow_services_share_the_runtime_kernel_owner_and_shutdown_gate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    saver, gateways, durable_bulkheads = _install_open_dependencies(monkeypatch, events)
    owner_calls = 0

    def process_owner_id() -> str:
        nonlocal owner_calls
        owner_calls += 1
        return "graph-replica:test-owner"

    monkeypatch.setattr(graph_lifecycle, "_process_owner_id", process_owner_id)

    def executor_factory(kernel: GraphExecutorKernel) -> ExactShadowExecutorRegistry:
        events.append("executor_factory")
        assert kernel.saver is saver
        assert kernel.gateway is gateways[0]
        assert kernel.durable_bulkhead is durable_bulkheads[0]
        return _registered_executors()

    runtime = await GraphApplicationRuntime.open(
        _shadow_settings(),
        _bindings(executor_registry_factory=executor_factory),
    )
    assert len(gateways) == 1
    assert len(durable_bulkheads) == 1
    assert owner_calls == 1
    assert runtime.stream_service._owner_id == "graph-replica:test-owner"
    assert runtime.reconciliation_service._owner_id == "graph-replica:test-owner"
    assert runtime.stream_service._gate is runtime.reconciliation_service._gate
    assert runtime.stream_service._gate is runtime._admission_gate
    assert await runtime.close() is True
    assert events == [
        "checkpoint_open",
        "persistence_check",
        "bulkhead_open",
        "bulkhead_readiness",
        "security_open",
        "executor_factory",
        "gate_start",
        "drain",
        "bulkhead_drain",
        "bulkhead_close",
        "security_close",
        "checkpoint_close",
    ]


@pytest.mark.asyncio
async def test_shadow_application_runtime_rejects_an_empty_executor_registry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    _install_open_dependencies(monkeypatch, events)

    with pytest.raises(ValueError, match="exact executor registration"):
        await GraphApplicationRuntime.open(_shadow_settings(), _bindings())

    assert events == [
        "checkpoint_open",
        "persistence_check",
        "bulkhead_open",
        "bulkhead_readiness",
        "security_open",
        "bulkhead_close",
        "security_close",
        "checkpoint_close",
    ]


@pytest.mark.asyncio
async def test_shadow_startup_fails_closed_when_durable_bulkhead_is_not_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    _install_open_dependencies(monkeypatch, events, bulkhead_ready=False)

    with pytest.raises(RuntimeError, match="GRAPH_BULKHEAD_UNAVAILABLE"):
        await GraphApplicationRuntime.open(_shadow_settings(), _bindings())

    assert events == [
        "checkpoint_open",
        "persistence_check",
        "bulkhead_open",
        "bulkhead_readiness",
        "bulkhead_close",
        "checkpoint_close",
    ]


@pytest.mark.asyncio
async def test_shadow_application_runtime_rejects_a_forged_executor_registry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    _install_open_dependencies(monkeypatch, events)

    def forged_factory(kernel: GraphExecutorKernel) -> Any:
        return SimpleNamespace(registration_count=1)

    with pytest.raises(TypeError, match="exact executor registry"):
        await GraphApplicationRuntime.open(
            _shadow_settings(),
            _bindings(executor_registry_factory=forged_factory),
        )

    assert events[-3:] == ["bulkhead_close", "security_close", "checkpoint_close"]


@pytest.mark.asyncio
async def test_shadow_executor_factory_failure_closes_security_then_checkpoint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    saver, gateways, _ = _install_open_dependencies(monkeypatch, events)

    def failing_factory(kernel: GraphExecutorKernel) -> ExactShadowExecutorRegistry:
        events.append("executor_factory")
        assert kernel.saver is saver
        assert kernel.gateway is gateways[0]
        raise RuntimeError("executor construction failed")

    with pytest.raises(RuntimeError, match="executor construction failed"):
        await GraphApplicationRuntime.open(
            _shadow_settings(),
            _bindings(executor_registry_factory=failing_factory),
        )

    assert events == [
        "checkpoint_open",
        "persistence_check",
        "bulkhead_open",
        "bulkhead_readiness",
        "security_open",
        "executor_factory",
        "bulkhead_close",
        "security_close",
        "checkpoint_close",
    ]


@pytest.mark.asyncio
async def test_runtime_readiness_exception_is_a_bounded_fail_closed_report() -> None:
    events: list[str] = []
    runtime = _FakeRuntime(events, check_error=RuntimeError("private probe detail"))

    async def factory(settings: Settings, bindings: GraphRuntimeBindings):
        events.append("open")
        return runtime

    handle = GraphRuntimeHandle(
        settings=_shadow_settings(),
        bindings=_bindings(),
        runtime_factory=factory,
    )

    async with handle.lifespan(None):
        report = await handle.check_readiness()
        assert report.ready is False
        assert report.code == "GRAPH_READINESS_CHECK_FAILED"
        assert "private" not in str(report.public_payload())

    assert events == ["open", "check", "close"]


class _Probe:
    def __init__(
        self,
        events: list[str],
        *,
        ready: bool = True,
        error: Exception | None = None,
    ) -> None:
        self.events = events
        self.ready = ready
        self.error = error

    async def check(self) -> Any:
        self.events.append("persistence_check")
        if self.error is not None:
            raise self.error
        return SimpleNamespace(
            ready=self.ready,
            code="GRAPH_PERSISTENCE_READY" if self.ready else "GRAPH_DB_UNAVAILABLE",
        )


class _SecurityRuntime:
    def __init__(
        self,
        events: list[str],
        *,
        ready: bool = True,
        error: Exception | None = None,
    ) -> None:
        self.events = events
        self.ready = ready
        self.error = error

    def readiness(self) -> Any:
        if self.error is not None:
            raise self.error
        return SimpleNamespace(
            ready=self.ready,
            code="GRAPH_JWKS_READY" if self.ready else "GRAPH_JWKS_UNAVAILABLE",
        )

    async def close(self) -> None:
        self.events.append("security_close")


class _DurableBulkhead:
    def __init__(
        self,
        events: list[str],
        *,
        ready: bool = True,
        error: Exception | None = None,
    ) -> None:
        self.events = events
        self.ready = ready
        self.error = error

    async def check_readiness(self) -> Any:
        self.events.append("bulkhead_readiness")
        if self.error is not None:
            raise self.error
        return SimpleNamespace(
            ready=self.ready,
            code="GRAPH_BULKHEAD_READY" if self.ready else "GRAPH_BULKHEAD_UNAVAILABLE",
        )

    async def drain(self) -> bool:
        self.events.append("bulkhead_drain")
        return True

    async def close(self) -> None:
        self.events.append("bulkhead_close")


class _CheckpointRuntime:
    def __init__(self, events: list[str]) -> None:
        self.events = events

    async def close(self) -> None:
        self.events.append("checkpoint_close")


class _AdmissionGate:
    def __init__(
        self,
        events: list[str],
        *,
        drain_error: Exception | None = None,
    ) -> None:
        self.events = events
        self.drain_error = drain_error
        self.accepting = True

    async def drain(self, timeout_seconds: float) -> bool:
        self.events.append("drain")
        self.accepting = False
        if self.drain_error is not None:
            raise self.drain_error
        return True


def _application_runtime(
    *,
    events: list[str],
    probe: _Probe | None = None,
    security: _SecurityRuntime | None = None,
    bulkhead: _DurableBulkhead | None = None,
    gate: _AdmissionGate | None = None,
) -> GraphApplicationRuntime:
    return GraphApplicationRuntime(
        checkpoint_runtime=cast(Any, _CheckpointRuntime(events)),
        persistence_probe=cast(Any, probe or _Probe(events)),
        durable_bulkhead=cast(Any, bulkhead or _DurableBulkhead(events)),
        security_runtime=cast(Any, security or _SecurityRuntime(events)),
        gateway=cast(Any, object()),
        stream_service=cast(Any, object()),
        reconciliation_service=cast(Any, object()),
        admission_gate=cast(Any, gate or _AdmissionGate(events)),
        execution_verifier=cast(Any, object()),
        reconciliation_verifier=cast(Any, object()),
    )


@pytest.mark.asyncio
async def test_application_close_waits_for_both_shared_admission_tokens() -> None:
    events: list[str] = []
    gate = graph_lifecycle.GraphStreamAdmissionGate()
    await gate.start()
    stream_token = await gate.enter()
    reconciliation_token = await gate.enter()
    runtime = _application_runtime(
        events=events,
        gate=cast(Any, gate),
    )

    close_task = asyncio.create_task(runtime.close())
    await asyncio.sleep(0)
    assert close_task.done() is False

    await gate.leave(stream_token)
    await asyncio.sleep(0)
    assert close_task.done() is False

    await gate.leave(reconciliation_token)
    assert await close_task is True
    assert events == ["bulkhead_drain", "bulkhead_close", "security_close", "checkpoint_close"]


@pytest.mark.asyncio
async def test_application_readiness_probe_errors_disable_admission_without_leaking() -> None:
    events: list[str] = []
    runtime = _application_runtime(
        events=events,
        probe=_Probe(events, error=RuntimeError("private database detail")),
    )

    report = await runtime.check_readiness()

    assert report.ready is False
    assert report.code == "GRAPH_PERSISTENCE_CHECK_FAILED"
    assert report.persistence_code == "GRAPH_PERSISTENCE_CHECK_FAILED"
    assert runtime.ready is False
    assert "private" not in str(report.public_payload())


@pytest.mark.asyncio
async def test_application_security_readiness_errors_are_fail_closed() -> None:
    events: list[str] = []
    runtime = _application_runtime(
        events=events,
        security=_SecurityRuntime(events, error=RuntimeError("private JWKS detail")),
    )

    assert runtime.ready is False
    report = await runtime.check_readiness()

    assert report.ready is False
    assert report.code == "GRAPH_JWKS_CHECK_FAILED"
    assert report.security_code == "GRAPH_JWKS_CHECK_FAILED"


@pytest.mark.asyncio
async def test_application_bulkhead_readiness_errors_are_fail_closed() -> None:
    events: list[str] = []
    runtime = _application_runtime(
        events=events,
        bulkhead=_DurableBulkhead(
            events,
            error=RuntimeError("private bulkhead database detail"),
        ),
    )

    report = await runtime.check_readiness()

    assert report.ready is False
    assert report.code == "GRAPH_BULKHEAD_CHECK_FAILED"
    assert report.bulkhead_code == "GRAPH_BULKHEAD_CHECK_FAILED"
    assert runtime.ready is False
    assert "private" not in str(report.public_payload())


@pytest.mark.asyncio
async def test_application_close_preserves_dependency_order_when_drain_fails() -> None:
    events: list[str] = []
    runtime = _application_runtime(
        events=events,
        gate=_AdmissionGate(events, drain_error=RuntimeError("drain failed")),
    )

    with pytest.raises(RuntimeError, match="drain failed"):
        await runtime.close()

    assert runtime.ready is False
    assert events == [
        "drain",
        "bulkhead_drain",
        "bulkhead_close",
        "security_close",
        "checkpoint_close",
    ]
    assert await runtime.close() is False
    assert events == [
        "drain",
        "bulkhead_drain",
        "bulkhead_close",
        "security_close",
        "checkpoint_close",
    ]


@pytest.mark.asyncio
async def test_partial_open_closes_pool_even_when_security_cleanup_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    _install_open_dependencies(
        monkeypatch,
        events,
        gate_start_error=RuntimeError("gate start failed"),
        security_close_error=RuntimeError("security cleanup failed"),
    )

    with pytest.raises(RuntimeError, match="security cleanup failed"):
        await GraphApplicationRuntime.open(
            _shadow_settings(),
            _bindings(executor_registry_factory=lambda kernel: _registered_executors()),
        )

    assert events == [
        "checkpoint_open",
        "persistence_check",
        "bulkhead_open",
        "bulkhead_readiness",
        "security_open",
        "gate_start",
        "bulkhead_close",
        "security_close",
        "checkpoint_close",
    ]
