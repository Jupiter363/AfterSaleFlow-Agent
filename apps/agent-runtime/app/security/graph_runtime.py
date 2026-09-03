from __future__ import annotations

import hashlib
import hmac
import secrets
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from threading import RLock
from urllib.parse import urlsplit
from weakref import WeakKeyDictionary

import httpx
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.contracts.v1.codec import canonicalize
from app.security.invocation_envelope import ResolvedVerificationKey
from app.security.jwks import JwksSnapshot, JwksVerificationKeyResolver
from app.security.jwks_loader import (
    HttpJwksDocumentFetcher,
    JwksRefreshManager,
    JwksRefreshStatus,
)


ReferencedKeyIds = Callable[[], Awaitable[Iterable[str]]]

_RUNTIME_TOKEN = object()
_RESOLVER_CAPABILITY_TOKEN = object()
_SNAPSHOT_TOKEN = object()
_RUNTIME_SEAL_KEY = secrets.token_bytes(32)
_REGISTRY_LOCK = RLock()


class GraphSecurityRuntimeError(RuntimeError):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True, slots=True)
class GraphSecurityReadiness:
    ready: bool
    code: str
    generation: int
    key_count: int
    refresh_error_code: str | None = None


class _GraphVerificationResolver:
    __slots__ = ("_runtime", "_resolver", "_seal", "_token", "__weakref__")

    def __init__(
        self,
        runtime: GraphSecurityRuntime,
        resolver: JwksVerificationKeyResolver,
        *,
        _token: object,
    ) -> None:
        if _token is not _RESOLVER_CAPABILITY_TOKEN:
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_CAPABILITY_BOOTSTRAP_REQUIRED")
        object.__setattr__(self, "_runtime", runtime)
        object.__setattr__(self, "_resolver", resolver)
        object.__setattr__(self, "_token", _token)
        nonce = secrets.token_bytes(32)
        object.__setattr__(self, "_seal", _resolver_capability_seal(self, nonce))
        with _REGISTRY_LOCK:
            _RESOLVER_CAPABILITIES[self] = nonce

    def __setattr__(self, name: str, value: object) -> None:
        del name, value
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_CAPABILITY_IMMUTABLE")

    def resolve(self, kid: str) -> ResolvedVerificationKey:
        _validate_graph_security_runtime(self._runtime, require_open=True)
        _validate_resolver_capability(self, self._runtime)
        key = self._resolver.resolve(kid)
        _validate_resolved_key(key, kid)
        return ResolvedVerificationKey(
            kid=key.kid,
            public_key=key.public_key,
            algorithm=key.algorithm,
            curve=key.curve,
            use=key.use,
        )

    def snapshot(self) -> JwksSnapshot:
        _validate_graph_security_runtime(self._runtime, require_open=True)
        _validate_resolver_capability(self, self._runtime)
        return self._resolver.snapshot()


class _GraphVerificationSnapshot:
    __slots__ = (
        "_runtime",
        "_generation",
        "_keys",
        "_seal",
        "_token",
        "__weakref__",
    )

    def __init__(
        self,
        *,
        runtime: GraphSecurityRuntime,
        generation: int,
        keys: tuple[ResolvedVerificationKey, ...],
        _token: object,
    ) -> None:
        if _token is not _SNAPSHOT_TOKEN:
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_BOOTSTRAP_REQUIRED")
        if generation < 1 or not keys:
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_NOT_READY")
        object.__setattr__(self, "_runtime", runtime)
        object.__setattr__(self, "_generation", generation)
        object.__setattr__(self, "_keys", tuple(keys))
        object.__setattr__(self, "_token", _token)
        nonce = secrets.token_bytes(32)
        object.__setattr__(self, "_seal", _verification_snapshot_seal(self, nonce))
        with _REGISTRY_LOCK:
            _VERIFICATION_SNAPSHOTS[self] = nonce

    def __setattr__(self, name: str, value: object) -> None:
        del name, value
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_IMMUTABLE")

    def resolve(self, kid: str) -> ResolvedVerificationKey:
        _validate_graph_verification_snapshot(self._runtime, self)
        matches = tuple(key for key in self._keys if key.kid == kid)
        if len(matches) != 1:
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_KEY_UNAVAILABLE")
        key = matches[0]
        return ResolvedVerificationKey(
            kid=key.kid,
            public_key=key.public_key,
            algorithm=key.algorithm,
            curve=key.curve,
            use=key.use,
        )


class GraphSecurityRuntime:
    """Own the bounded HTTP lifecycle and the process-local Graph trust root."""

    __slots__ = (
        "_resolver",
        "_resolver_capability",
        "_manager",
        "_client",
        "_closed",
        "_seal",
        "_token",
        "__weakref__",
    )

    def __init__(
        self,
        *,
        resolver: JwksVerificationKeyResolver,
        manager: JwksRefreshManager,
        client: httpx.AsyncClient,
        _token: object = None,
    ) -> None:
        if _token is not _RUNTIME_TOKEN:
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_BOOTSTRAP_REQUIRED")
        if (
            type(resolver) is not JwksVerificationKeyResolver
            or type(manager) is not JwksRefreshManager
            or type(client) is not httpx.AsyncClient
        ):
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_DEPENDENCY_INVALID")
        object.__setattr__(self, "_resolver", resolver)
        object.__setattr__(self, "_manager", manager)
        object.__setattr__(self, "_client", client)
        object.__setattr__(self, "_closed", False)
        object.__setattr__(self, "_token", _token)
        capability = _GraphVerificationResolver(
            self,
            resolver,
            _token=_RESOLVER_CAPABILITY_TOKEN,
        )
        object.__setattr__(self, "_resolver_capability", capability)
        nonce = secrets.token_bytes(32)
        object.__setattr__(self, "_seal", _runtime_seal(self, nonce))
        with _REGISTRY_LOCK:
            _GRAPH_SECURITY_RUNTIMES[self] = nonce

    def __setattr__(self, name: str, value: object) -> None:
        del name, value
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_IMMUTABLE")

    @property
    def resolver(self) -> _GraphVerificationResolver:
        _validate_graph_security_runtime(self, require_open=True)
        return self._resolver_capability

    def capture_verification_snapshot(self) -> object:
        _validate_graph_security_runtime(self, require_open=True)
        before = self._resolver.snapshot()
        if before.generation < 1 or not before.key_ids:
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_NOT_READY")
        keys = tuple(self._resolver.resolve(kid) for kid in before.key_ids)
        for key, key_id in zip(keys, before.key_ids, strict=True):
            _validate_resolved_key(key, key_id)
        after = self._resolver.snapshot()
        if before != after:
            raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_ROTATED_DURING_CAPTURE")
        return _GraphVerificationSnapshot(
            runtime=self,
            generation=before.generation,
            keys=keys,
            _token=_SNAPSHOT_TOKEN,
        )

    def readiness(self) -> GraphSecurityReadiness:
        _validate_graph_security_runtime(self, require_open=False)
        if self._closed:
            return GraphSecurityReadiness(
                ready=False,
                code="GRAPH_JWKS_CLOSED",
                generation=0,
                key_count=0,
            )
        status = self._manager.status()
        ready = bool(
            status.running
            and status.generation >= 1
            and status.key_ids
            and status.last_success_monotonic is not None
        )
        if not ready:
            code = status.last_error_code or "GRAPH_JWKS_UNAVAILABLE"
        elif status.last_error_code is not None:
            code = "GRAPH_JWKS_READY_CACHED"
        else:
            code = "GRAPH_JWKS_READY"
        return GraphSecurityReadiness(
            ready=ready,
            code=code,
            generation=status.generation,
            key_count=len(status.key_ids),
            refresh_error_code=status.last_error_code,
        )

    def status(self) -> JwksRefreshStatus:
        _validate_graph_security_runtime(self, require_open=False)
        return self._manager.status()

    async def refresh_now(self) -> None:
        _validate_graph_security_runtime(self, require_open=True)
        await self._manager.refresh_once()

    async def close(self) -> None:
        _validate_graph_security_runtime(self, require_open=False)
        if self._closed:
            return
        object.__setattr__(self, "_closed", True)
        object.__setattr__(self, "_seal", _runtime_seal(self, _runtime_nonce(self)))
        try:
            await self._manager.close()
        finally:
            await self._client.aclose()


_GRAPH_SECURITY_RUNTIMES: WeakKeyDictionary[GraphSecurityRuntime, bytes] = WeakKeyDictionary()
_RESOLVER_CAPABILITIES: WeakKeyDictionary[_GraphVerificationResolver, bytes] = WeakKeyDictionary()
_VERIFICATION_SNAPSHOTS: WeakKeyDictionary[_GraphVerificationSnapshot, bytes] = WeakKeyDictionary()


async def _open_for_lifecycle(
    *,
    jwks_url: str,
    timeout_seconds: float,
    refresh_interval_seconds: float,
    referenced_key_ids: ReferencedKeyIds,
    transport: httpx.AsyncBaseTransport | None = None,
) -> GraphSecurityRuntime:
    _validate_jwks_url(jwks_url)
    if timeout_seconds <= 0:
        raise ValueError("JWKS timeout must be positive")
    client = httpx.AsyncClient(
        timeout=httpx.Timeout(timeout_seconds),
        limits=httpx.Limits(max_connections=2, max_keepalive_connections=1),
        follow_redirects=False,
        trust_env=False,
        transport=transport,
    )
    resolver = JwksVerificationKeyResolver()
    manager = JwksRefreshManager(
        resolver=resolver,
        fetch_document=HttpJwksDocumentFetcher(client=client, url=jwks_url),
        referenced_key_ids=referenced_key_ids,
        refresh_interval_seconds=refresh_interval_seconds,
    )
    try:
        await manager.start()
        return GraphSecurityRuntime(
            resolver=resolver,
            manager=manager,
            client=client,
            _token=_RUNTIME_TOKEN,
        )
    except BaseException:
        try:
            await manager.close()
        finally:
            await client.aclose()
        raise


def _validate_graph_security_runtime(
    runtime: object,
    *,
    require_open: bool,
) -> None:
    if type(runtime) is not GraphSecurityRuntime:
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_INVALID")
    nonce = _runtime_nonce(runtime)
    if runtime._token is not _RUNTIME_TOKEN:
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_INVALID")
    expected = _runtime_seal(runtime, nonce)
    if not hmac.compare_digest(runtime._seal, expected):
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_INVALID")
    _validate_resolver_capability(runtime._resolver_capability, runtime)
    if require_open and runtime._closed:
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_CLOSED")


def _validate_resolver_capability(
    capability: object,
    runtime: GraphSecurityRuntime,
) -> None:
    if type(capability) is not _GraphVerificationResolver:
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_CAPABILITY_INVALID")
    with _REGISTRY_LOCK:
        nonce = _RESOLVER_CAPABILITIES.get(capability)
    if (
        nonce is None
        or capability._token is not _RESOLVER_CAPABILITY_TOKEN
        or capability._runtime is not runtime
        or capability._resolver is not runtime._resolver
    ):
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_CAPABILITY_INVALID")
    expected = _resolver_capability_seal(capability, nonce)
    if not hmac.compare_digest(capability._seal, expected):
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_CAPABILITY_INVALID")


def _validate_graph_verification_snapshot(runtime: object, snapshot: object) -> str:
    _validate_graph_security_runtime(runtime, require_open=True)
    if type(snapshot) is not _GraphVerificationSnapshot:
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_INVALID")
    with _REGISTRY_LOCK:
        nonce = _VERIFICATION_SNAPSHOTS.get(snapshot)
    if nonce is None or snapshot._token is not _SNAPSHOT_TOKEN or snapshot._runtime is not runtime:
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_INVALID")
    expected = _verification_snapshot_seal(snapshot, nonce)
    if not hmac.compare_digest(snapshot._seal, expected):
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_SNAPSHOT_INVALID")
    return _graph_security_binding(runtime, snapshot, nonce)


def _resolve_graph_verification_key(
    runtime: object,
    snapshot: object,
    kid: str,
) -> ResolvedVerificationKey:
    _validate_graph_verification_snapshot(runtime, snapshot)
    return snapshot.resolve(kid)


def _runtime_nonce(runtime: GraphSecurityRuntime) -> bytes:
    with _REGISTRY_LOCK:
        nonce = _GRAPH_SECURITY_RUNTIMES.get(runtime)
    if nonce is None:
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_RUNTIME_INVALID")
    return nonce


def _runtime_seal(runtime: GraphSecurityRuntime, nonce: bytes) -> str:
    payload = canonicalize(
        {
            "schema_version": "graph-security-runtime-seal.v1",
            "registry_nonce": nonce.hex(),
            "runtime_identity": id(runtime),
            "resolver_identity": id(runtime._resolver),
            "resolver_capability_identity": id(runtime._resolver_capability),
            "manager_identity": id(runtime._manager),
            "client_identity": id(runtime._client),
            "closed": runtime._closed,
        }
    )
    return hmac.new(_RUNTIME_SEAL_KEY, payload, hashlib.sha256).hexdigest()


def _resolver_capability_seal(
    capability: _GraphVerificationResolver,
    nonce: bytes,
) -> str:
    payload = canonicalize(
        {
            "schema_version": "graph-verification-resolver-seal.v1",
            "registry_nonce": nonce.hex(),
            "capability_identity": id(capability),
            "runtime_identity": id(capability._runtime),
            "resolver_identity": id(capability._resolver),
        }
    )
    return hmac.new(_RUNTIME_SEAL_KEY, payload, hashlib.sha256).hexdigest()


def _verification_snapshot_seal(
    snapshot: _GraphVerificationSnapshot,
    nonce: bytes,
) -> str:
    payload = canonicalize(
        {
            "schema_version": "graph-verification-snapshot-seal.v1",
            "registry_nonce": nonce.hex(),
            "snapshot_identity": id(snapshot),
            "runtime_identity": id(snapshot._runtime),
            "generation": snapshot._generation,
            "keys": [_key_descriptor(key) for key in snapshot._keys],
        }
    )
    return hmac.new(_RUNTIME_SEAL_KEY, payload, hashlib.sha256).hexdigest()


def _graph_security_binding(
    runtime: GraphSecurityRuntime,
    snapshot: _GraphVerificationSnapshot,
    snapshot_nonce: bytes,
) -> str:
    payload = canonicalize(
        {
            "schema_version": "graph-security-trust-binding.v1",
            "runtime_identity": id(runtime),
            "runtime_registry_nonce": _runtime_nonce(runtime).hex(),
            "runtime_seal": runtime._seal,
            "snapshot_identity": id(snapshot),
            "snapshot_registry_nonce": snapshot_nonce.hex(),
            "snapshot_seal": snapshot._seal,
        }
    )
    return hmac.new(_RUNTIME_SEAL_KEY, payload, hashlib.sha256).hexdigest()


def _key_descriptor(key: ResolvedVerificationKey) -> dict[str, str]:
    _validate_resolved_key(key, key.kid)
    public_der = key.public_key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return {
        "kid": key.kid,
        "public_key_sha256": hashlib.sha256(public_der).hexdigest(),
        "algorithm": key.algorithm,
        "curve": key.curve,
        "use": key.use,
    }


def _validate_resolved_key(key: object, expected_kid: str) -> None:
    if (
        type(key) is not ResolvedVerificationKey
        or key.kid != expected_kid
        or key.algorithm != "ES256"
        or key.curve != "P-256"
        or key.use != "sig"
        or not isinstance(key.public_key, ec.EllipticCurvePublicKey)
        or not isinstance(key.public_key.curve, ec.SECP256R1)
    ):
        raise GraphSecurityRuntimeError("GRAPH_SECURITY_KEY_INVALID")


def _validate_jwks_url(value: str) -> None:
    parsed = urlsplit(value)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or bool(parsed.fragment)
    ):
        raise ValueError("JWKS URL must be an absolute HTTP URL without credentials or fragment")
