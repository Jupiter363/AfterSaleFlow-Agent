from __future__ import annotations

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
        self.verifier = cast(Any, object())
        self.stream_service = cast(Any, object())

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
) -> tuple[Any, list[Any]]:
    pool = object()
    saver = object()
    gateways: list[Any] = []

    class CheckpointRuntime:
        def __init__(self) -> None:
            self.pool = pool
            self.saver = saver

        @classmethod
        async def open(cls, dsn: str, config: Any) -> CheckpointRuntime:
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

    class SecurityRuntime:
        resolver = object()

        @classmethod
        async def open(cls, **kwargs: Any) -> SecurityRuntime:
            events.append("security_open")
            return cls()

        def readiness(self) -> Any:
            return SimpleNamespace(ready=True, code="GRAPH_JWKS_READY")

        async def close(self) -> None:
            events.append("security_close")
            if security_close_error is not None:
                raise security_close_error

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
    monkeypatch.setattr(graph_lifecycle, "GraphSecurityRuntime", SecurityRuntime)
    monkeypatch.setattr(graph_lifecycle, "GraphStreamAdmissionGate", AdmissionGate)
    return saver, gateways


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
    }
    assert response.headers["cache-control"] == "no-store, no-transform"
    assert response.headers["pragma"] == "no-cache"
    assert response.headers["x-content-type-options"] == "nosniff"


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
async def test_shadow_executor_factory_receives_the_exact_runtime_kernel(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    saver, gateways = _install_open_dependencies(monkeypatch, events)

    def executor_factory(kernel: GraphExecutorKernel) -> ExactShadowExecutorRegistry:
        events.append("executor_factory")
        assert kernel.saver is saver
        assert kernel.gateway is gateways[0]
        return _registered_executors()

    runtime = await GraphApplicationRuntime.open(
        _shadow_settings(),
        _bindings(executor_registry_factory=executor_factory),
    )
    assert len(gateways) == 1
    assert await runtime.close() is True
    assert events == [
        "checkpoint_open",
        "persistence_check",
        "security_open",
        "executor_factory",
        "gate_start",
        "drain",
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
        "security_open",
        "security_close",
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

    assert events[-2:] == ["security_close", "checkpoint_close"]


@pytest.mark.asyncio
async def test_shadow_executor_factory_failure_closes_security_then_checkpoint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    saver, gateways = _install_open_dependencies(monkeypatch, events)

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
        "security_open",
        "executor_factory",
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
    gate: _AdmissionGate | None = None,
) -> GraphApplicationRuntime:
    return GraphApplicationRuntime(
        checkpoint_runtime=cast(Any, _CheckpointRuntime(events)),
        persistence_probe=cast(Any, probe or _Probe(events)),
        security_runtime=cast(Any, security or _SecurityRuntime(events)),
        gateway=cast(Any, object()),
        stream_service=cast(Any, object()),
        admission_gate=cast(Any, gate or _AdmissionGate(events)),
        verifier=cast(Any, object()),
    )


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
async def test_application_close_preserves_dependency_order_when_drain_fails() -> None:
    events: list[str] = []
    runtime = _application_runtime(
        events=events,
        gate=_AdmissionGate(events, drain_error=RuntimeError("drain failed")),
    )

    with pytest.raises(RuntimeError, match="drain failed"):
        await runtime.close()

    assert runtime.ready is False
    assert events == ["drain", "security_close", "checkpoint_close"]
    assert await runtime.close() is False
    assert events == ["drain", "security_close", "checkpoint_close"]


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
        "security_open",
        "gate_start",
        "security_close",
        "checkpoint_close",
    ]
