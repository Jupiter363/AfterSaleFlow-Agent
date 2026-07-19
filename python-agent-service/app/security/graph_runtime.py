from __future__ import annotations

from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from urllib.parse import urlsplit

import httpx

from app.security.jwks import JwksVerificationKeyResolver
from app.security.jwks_loader import (
    HttpJwksDocumentFetcher,
    JwksRefreshManager,
    JwksRefreshStatus,
)


ReferencedKeyIds = Callable[[], Awaitable[Iterable[str]]]


@dataclass(frozen=True, slots=True)
class GraphSecurityReadiness:
    ready: bool
    code: str
    generation: int
    key_count: int
    refresh_error_code: str | None = None


class GraphSecurityRuntime:
    """Own the bounded HTTP and rotation lifecycle for Graph verification keys."""

    def __init__(
        self,
        *,
        resolver: JwksVerificationKeyResolver,
        manager: JwksRefreshManager,
        client: httpx.AsyncClient,
    ) -> None:
        self.resolver = resolver
        self._manager = manager
        self._client = client
        self._closed = False

    @classmethod
    async def open(
        cls,
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
        except BaseException:
            await client.aclose()
            raise
        return cls(resolver=resolver, manager=manager, client=client)

    def readiness(self) -> GraphSecurityReadiness:
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
        return self._manager.status()

    async def refresh_now(self) -> None:
        if self._closed:
            raise RuntimeError("Graph security runtime is closed")
        await self._manager.refresh_once()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            await self._manager.close()
        finally:
            await self._client.aclose()


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
