from __future__ import annotations

from types import SimpleNamespace
from typing import Any, cast

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import app.api.graph_lifecycle as graph_lifecycle
from app.api.graph_lifecycle import (
    GraphApplicationRuntime,
    GraphRuntimeBindings,
    GraphRuntimeHandle,
    GraphRuntimeReadiness,
    create_graph_readiness_router,
)
from app.api.graph_stream_service import ExactShadowExecutorRegistry
from app.config import Settings
from app.graph_runtime.persistence_models import GraphGatewayMode


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
        graph_database_dsn=(
            "postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"
        ),
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


def _bindings() -> GraphRuntimeBindings:
    return GraphRuntimeBindings(
        thread_identity_resolver=cast(Any, _ThreadResolver()),
        input_authorizer=cast(Any, _InputAuthorizer()),
        executors=ExactShadowExecutorRegistry(),
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


@pytest.mark.asyncio
async def test_disabled_lifespan_never_constructs_graph_dependencies() -> None:
    calls = 0

    async def forbidden_factory(settings: Settings, bindings: GraphRuntimeBindings):
        nonlocal calls
        calls += 1
        raise AssertionError("disabled mode must not open Graph dependencies")

    handle = GraphRuntimeHandle(
        settings=_settings(),
        runtime_factory=forbidden_factory,
    )

    async with handle.lifespan(None):
        report = await handle.check_readiness()
        assert report.ready is True
        assert report.code == "GRAPH_DISABLED"
        assert handle.ready is False

    assert calls == 0
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
async def test_shadow_application_runtime_rejects_an_empty_executor_registry() -> None:
    with pytest.raises(ValueError, match="exact executor registration"):
        await GraphApplicationRuntime.open(_shadow_settings(), _bindings())


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

    class CheckpointRuntime:
        pool = object()

        @classmethod
        async def open(cls, dsn: str, config: Any) -> CheckpointRuntime:
            events.append("checkpoint_open")
            return cls()

        async def close(self) -> None:
            events.append("checkpoint_close")

    class PersistenceProbe:
        def __init__(self, config: Any, pool: Any) -> None:
            pass

        async def check(self) -> Any:
            events.append("persistence_check")
            return SimpleNamespace(ready=True, code="GRAPH_PERSISTENCE_READY")

    class Gateway:
        def __init__(self, **kwargs: Any) -> None:
            pass

        async def referenced_verification_key_ids(self) -> frozenset[str]:
            return frozenset()

    class SecurityRuntime:
        resolver = object()

        @classmethod
        async def open(cls, **kwargs: Any) -> SecurityRuntime:
            events.append("security_open")
            return cls()

        async def close(self) -> None:
            events.append("security_close")
            raise RuntimeError("security cleanup failed")

    class AdmissionGate:
        accepting = False

        async def start(self) -> None:
            events.append("gate_start")
            raise RuntimeError("gate start failed")

    monkeypatch.setattr(graph_lifecycle, "GraphCheckpointRuntime", CheckpointRuntime)
    monkeypatch.setattr(graph_lifecycle, "GraphPersistenceReadinessProbe", PersistenceProbe)
    monkeypatch.setattr(graph_lifecycle, "GraphCommandGateway", Gateway)
    monkeypatch.setattr(graph_lifecycle, "GraphSecurityRuntime", SecurityRuntime)
    monkeypatch.setattr(graph_lifecycle, "GraphStreamAdmissionGate", AdmissionGate)

    bindings = _bindings()
    bindings = GraphRuntimeBindings(
        thread_identity_resolver=bindings.thread_identity_resolver,
        input_authorizer=bindings.input_authorizer,
        executors=cast(Any, SimpleNamespace(registration_count=1)),
    )

    with pytest.raises(RuntimeError, match="security cleanup failed"):
        await GraphApplicationRuntime.open(_shadow_settings(), bindings)

    assert events == [
        "checkpoint_open",
        "persistence_check",
        "security_open",
        "gate_start",
        "security_close",
        "checkpoint_close",
    ]
