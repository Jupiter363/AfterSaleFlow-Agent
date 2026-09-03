from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Awaitable, Callable, Iterable, Mapping
from dataclasses import dataclass
from typing import Any, Protocol

import httpx

from app.security.invocation_envelope import InvocationEnvelopeError
from app.security.jwks import JwksSnapshot, JwksVerificationKeyResolver


_DEFAULT_MAX_DOCUMENT_BYTES = 65_536
_MINIMUM_KEY_OVERLAP_SECONDS = 65.0
_JWKS_MEDIA_TYPES = frozenset({"application/json", "application/jwk-set+json"})


class JwksDocumentFetcher(Protocol):
    async def __call__(self) -> Mapping[str, Any]: ...


ReferencedKeyIds = Callable[[], Awaitable[Iterable[str]]]


class JwksRefreshError(RuntimeError):
    """Public-safe failure from the bounded JWKS lifecycle."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True, slots=True)
class JwksRefreshStatus:
    running: bool
    generation: int
    key_ids: tuple[str, ...]
    last_success_monotonic: float | None
    last_error_code: str | None


@dataclass(frozen=True, slots=True)
class HttpJwksDocumentFetcher:
    client: httpx.AsyncClient
    url: str
    max_document_bytes: int = _DEFAULT_MAX_DOCUMENT_BYTES

    def __post_init__(self) -> None:
        if not self.url:
            raise ValueError("JWKS URL is required")
        if self.max_document_bytes < 1:
            raise ValueError("JWKS document limit must be positive")

    async def __call__(self) -> Mapping[str, Any]:
        try:
            response = await self.client.get(
                self.url,
                headers={"Accept": "application/jwk-set+json, application/json"},
            )
            response.raise_for_status()
        except httpx.HTTPError as error:
            raise JwksRefreshError("JWKS_HTTP_UNAVAILABLE") from error

        media_type = response.headers.get("content-type", "").partition(";")[0].strip().lower()
        if media_type not in _JWKS_MEDIA_TYPES:
            raise JwksRefreshError("JWKS_MEDIA_TYPE_REJECTED")
        payload = response.content
        if not payload or len(payload) > self.max_document_bytes:
            raise JwksRefreshError("JWKS_DOCUMENT_SIZE_REJECTED")
        try:
            document = json.loads(
                payload,
                object_pairs_hook=_unique_json_object,
                parse_constant=_reject_json_constant,
            )
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            raise JwksRefreshError("JWKS_DOCUMENT_JSON_REJECTED") from error
        if not isinstance(document, dict):
            raise JwksRefreshError("JWKS_DOCUMENT_JSON_REJECTED")
        return document


class JwksRefreshManager:
    """Load keys before admission and rotate them without publishing partial state."""

    def __init__(
        self,
        *,
        resolver: JwksVerificationKeyResolver,
        fetch_document: JwksDocumentFetcher,
        referenced_key_ids: ReferencedKeyIds,
        refresh_interval_seconds: float = 30.0,
        key_overlap_seconds: float = _MINIMUM_KEY_OVERLAP_SECONDS,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        if refresh_interval_seconds < 1:
            raise ValueError("JWKS refresh interval must be at least one second")
        if key_overlap_seconds < _MINIMUM_KEY_OVERLAP_SECONDS:
            raise ValueError("JWKS key overlap must be at least 65 seconds")
        self._resolver = resolver
        self._fetch_document = fetch_document
        self._referenced_key_ids = referenced_key_ids
        self._refresh_interval_seconds = refresh_interval_seconds
        self._key_overlap_seconds = key_overlap_seconds
        self._monotonic = monotonic
        self._refresh_lock = asyncio.Lock()
        self._stop = asyncio.Event()
        self._task: asyncio.Task[None] | None = None
        self._retired_at: dict[str, float] = {}
        self._last_success_monotonic: float | None = None
        self._last_error_code: str | None = None

    async def start(self) -> JwksSnapshot:
        if self._task is not None:
            raise RuntimeError("JWKS refresh manager is already running")
        self._stop.clear()
        snapshot = await self.refresh_once()
        self._task = asyncio.create_task(self._run(), name="graph-jwks-refresh")
        return snapshot

    async def close(self) -> None:
        task = self._task
        if task is None:
            return
        self._stop.set()
        try:
            await task
        finally:
            self._task = None

    async def refresh_once(self) -> JwksSnapshot:
        async with self._refresh_lock:
            try:
                document, referenced = await asyncio.gather(
                    self._fetch_document(),
                    self._referenced_key_ids(),
                )
                now = self._monotonic()
                current = self._resolver.snapshot()
                published = _candidate_key_ids(document)
                proposed_retired = {
                    key_id: retired_at
                    for key_id, retired_at in self._retired_at.items()
                    if key_id in current.key_ids and key_id not in published
                }
                for key_id in set(current.key_ids) - published:
                    proposed_retired.setdefault(key_id, now)
                retained = {
                    key_id
                    for key_id, retired_at in proposed_retired.items()
                    if now - retired_at < self._key_overlap_seconds
                }
                retained.update(referenced)
                snapshot = self._resolver.install(document, retain_key_ids=retained)
            except asyncio.CancelledError:
                raise
            except Exception as error:
                normalized = _normalize_refresh_error(error)
                self._last_error_code = normalized.code
                if normalized is error:
                    raise
                raise normalized from error

            self._retired_at = {
                key_id: retired_at
                for key_id, retired_at in proposed_retired.items()
                if key_id in snapshot.key_ids and key_id not in published
            }
            self._last_success_monotonic = now
            self._last_error_code = None
            return snapshot

    def status(self) -> JwksRefreshStatus:
        snapshot = self._resolver.snapshot()
        return JwksRefreshStatus(
            running=self._task is not None and not self._task.done(),
            generation=snapshot.generation,
            key_ids=snapshot.key_ids,
            last_success_monotonic=self._last_success_monotonic,
            last_error_code=self._last_error_code,
        )

    async def _run(self) -> None:
        while not self._stop.is_set():
            try:
                await asyncio.wait_for(
                    self._stop.wait(),
                    timeout=self._refresh_interval_seconds,
                )
            except TimeoutError:
                try:
                    await self.refresh_once()
                except asyncio.CancelledError:
                    raise
                except JwksRefreshError:
                    # The previous immutable snapshot remains installed. Readiness and
                    # request verification observe the stable error/status surface.
                    continue


def _candidate_key_ids(document: Mapping[str, Any]) -> set[str]:
    keys = document.get("keys")
    if not isinstance(keys, list):
        return set()
    return {
        key_id
        for candidate in keys
        if isinstance(candidate, dict)
        and isinstance((key_id := candidate.get("kid")), str)
    }


def _normalize_refresh_error(error: Exception) -> JwksRefreshError:
    if isinstance(error, JwksRefreshError):
        return error
    if isinstance(error, InvocationEnvelopeError):
        return JwksRefreshError("JWKS_DOCUMENT_REJECTED")
    return JwksRefreshError("JWKS_REFRESH_FAILED")


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, member in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON member: {key}")
        value[key] = member
    return value


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON value: {value}")
