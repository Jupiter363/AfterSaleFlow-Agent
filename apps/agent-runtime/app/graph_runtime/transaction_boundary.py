"""Cancellation-safe boundaries for Graph PostgreSQL transactions.

The Graph stream can be cancelled while psycopg is waiting for a query.  If the
task is cancelled in that window, psycopg's transaction and pool context managers
may be interrupted before they finish their rollback.  The pool then receives an
``INTRANS`` connection and discards it, which can make an otherwise valid lease
look lost.  This module keeps the database operation in a child task, requests a
server-side query cancellation, and drains the child through the transaction and
pool exits before propagating the caller's cancellation.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
import sys
from typing import Any, TypeVar

import anyio


_Result = TypeVar("_Result")
_DEFAULT_CANCEL_TIMEOUT_SECONDS = 2.0

logger = logging.getLogger(__name__)


class _TransactionState:
    __slots__ = ("connection", "force_close_reason", "force_close_started")

    def __init__(self) -> None:
        self.connection: Any | None = None
        self.force_close_reason: str | None = None
        self.force_close_started = False


async def run_postgres_transaction(
    pool: Any,
    *,
    timeout: float | None,
    operation: Callable[[Any], Awaitable[_Result]],
    operation_name: str = "graph transaction",
    cancel_timeout_seconds: float = _DEFAULT_CANCEL_TIMEOUT_SECONDS,
    stage_callback: Callable[[str], None] | None = None,
) -> _Result:
    """Run one transaction and make external cancellation safe for the pool.

    The operation is deliberately executed in a separate task.  The caller task
    can therefore observe cancellation without injecting it into psycopg's
    transaction context.  Once cancellation is observed, the active PostgreSQL
    command is cancelled through ``cancel_safe`` and the child is drained until
    both the transaction and pool contexts have returned the connection.

    The child is cancelled only while it is still acquiring a connection.  After
    checkout, cancelling the child would recreate the exact ``INTRANS`` race this
    boundary is intended to prevent.  A missing/older fake connection is tolerated
    in tests; the child is still drained and the original cancellation is retained.
    """

    if not isinstance(operation_name, str) or not operation_name:
        raise ValueError("operation_name must be non-empty")
    if (
        not isinstance(cancel_timeout_seconds, (int, float))
        or isinstance(cancel_timeout_seconds, bool)
        or cancel_timeout_seconds <= 0
    ):
        raise ValueError("cancel_timeout_seconds must be positive")

    state = _TransactionState()

    def notify(stage: str) -> None:
        if stage_callback is None:
            return
        try:
            stage_callback(stage)
        except Exception:
            # Observability must never change transaction semantics.
            logger.debug(
                "graph transaction stage callback failed: operation=%s stage=%s",
                operation_name,
                stage,
                exc_info=True,
            )

    async def force_close_connection(reason: str) -> None:
        """Make an indeterminate transaction unusable before pool return."""

        connection = state.connection
        if connection is None or state.force_close_started:
            return
        state.force_close_started = True
        notify("CONNECTION_FORCE_CLOSE_STARTED")
        close = getattr(connection, "close", None)
        if not callable(close):
            notify("CONNECTION_FORCE_CLOSE_UNAVAILABLE")
            logger.warning(
                "graph transaction connection force-close unavailable: "
                "operation=%s reason=%s",
                operation_name,
                reason,
            )
            return
        try:
            await close()
        except Exception as error:
            notify("CONNECTION_FORCE_CLOSE_FAILED")
            logger.warning(
                "graph transaction connection force-close failed: "
                "operation=%s reason=%s error_type=%s error=%s",
                operation_name,
                reason,
                type(error).__name__,
                str(error)[:256],
            )
            return
        notify("CONNECTION_FORCE_CLOSE_SUCCEEDED")
        logger.warning(
            "graph transaction connection force-closed: operation=%s reason=%s",
            operation_name,
            reason,
        )

    def request_force_close(reason: str) -> None:
        """Delegate connection disposal to the task that owns its socket wait."""

        if state.force_close_reason is not None:
            return
        state.force_close_reason = reason
        notify("CONNECTION_FORCE_CLOSE_REQUESTED")

    async def execute() -> _Result:
        pool_context = pool.connection(timeout=timeout)
        pool_entered = False
        try:
            connection = await pool_context.__aenter__()
            pool_entered = True
            state.connection = connection
            notify("POOL_ACQUIRED")
            transaction = connection.transaction()
            await transaction.__aenter__()
            notify("TRANSACTION_ENTERED")
            try:
                result = await operation(connection)
                if state.force_close_reason is not None:
                    # An operation that suppresses task cancellation must not
                    # commit an indeterminate transaction.
                    raise asyncio.CancelledError
            except BaseException as operation_error:
                if state.force_close_reason is not None:
                    # The operation await has now unwound its psycopg socket
                    # registration.  Closing here, from the owning child task,
                    # cannot leave an invalid descriptor in SelectorEventLoop.
                    await force_close_connection(state.force_close_reason)
                notify("TRANSACTION_ROLLBACK_STARTED")
                try:
                    suppressed = await transaction.__aexit__(
                        type(operation_error),
                        operation_error,
                        operation_error.__traceback__,
                    )
                except BaseException:
                    notify("TRANSACTION_ROLLBACK_FAILED")
                    await force_close_connection("transaction_rollback_failed")
                    raise
                notify("TRANSACTION_ROLLBACK_SUCCEEDED")
                if suppressed:
                    return None  # type: ignore[return-value]
                raise
            notify("TRANSACTION_COMMIT_STARTED")
            try:
                suppressed = await transaction.__aexit__(None, None, None)
            except BaseException:
                notify("TRANSACTION_COMMIT_FAILED")
                await force_close_connection("transaction_commit_failed")
                raise
            notify("TRANSACTION_COMMIT_SUCCEEDED")
            if suppressed:
                return None  # type: ignore[return-value]
            return result
        finally:
            # Keep the reference until the pool context has finished.  A
            # cancellation arriving during transaction or pool __aexit__ still
            # needs to be treated as an active checked-out connection by parent.
            if pool_entered:
                if state.force_close_reason is not None:
                    await force_close_connection(state.force_close_reason)
                try:
                    await pool_context.__aexit__(*sys.exc_info())
                except BaseException:
                    notify("POOL_RELEASE_FAILED")
                    await force_close_connection("pool_release_failed")
                    raise
                notify("POOL_RELEASE_SUCCEEDED")
            state.connection = None

    task = asyncio.create_task(execute(), name=f"{operation_name} transaction")
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        # AnyIO's level cancellation can be delivered repeatedly while cleanup is
        # running.  A shielded cancel scope is required in addition to asyncio's
        # shield so the psycopg cancel and drain cannot be interrupted halfway.
        with anyio.CancelScope(shield=True):
            connection = state.connection
            if connection is None and not task.done():
                # No connection has been checked out yet.  Cancelling a task in
                # the pool acquire path is safe; there is no transaction to strand.
                task.cancel()
            elif connection is not None:
                cancel_safe = getattr(connection, "cancel_safe", None)
                cancel_requested = False
                if callable(cancel_safe):
                    try:
                        await cancel_safe(timeout=float(cancel_timeout_seconds))
                        cancel_requested = True
                    except Exception as error:
                        # The original cancellation remains authoritative.  The
                        # child is still drained so the pool can discard a broken
                        # connection deterministically rather than seeing INTRANS.
                        logger.warning(
                            "graph transaction query cancellation failed: "
                            "operation=%s error_type=%s error=%s",
                            operation_name,
                            type(error).__name__,
                            str(error)[:256],
                        )
                if not cancel_requested and not task.done():
                    # Older/fake connections may not expose cancel_safe, or the
                    # secure cancellation handshake may time out.  The active
                    # transaction is then indeterminate.  Mark it for disposal
                    # before cancelling the child; the child closes it only after
                    # its active socket wait has unwound, then pool return observes
                    # BAD rather than INTRANS.
                    request_force_close("query_cancellation_failed")
                    task.cancel()

            async def request_cleanup_force_close() -> None:
                request_force_close("transaction_cleanup_timeout")

            await _drain_transaction_task(
                task,
                operation_name=operation_name,
                timeout_seconds=max(1.0, float(cancel_timeout_seconds) * 2.0),
                force_close=request_cleanup_force_close,
            )
        raise


async def _drain_transaction_task(
    task: asyncio.Task[Any],
    *,
    operation_name: str,
    timeout_seconds: float | None = None,
    force_close: Callable[[], Awaitable[None]] | None = None,
) -> None:
    """Wait for a transaction child and consume its cancellation-side error."""

    async def drain() -> None:
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError:
                # The caller may be under an AnyIO level-cancel scope.  The
                # enclosing shielded CancelScope makes this normally unreachable,
                # but retaining the loop keeps direct asyncio cancellation safe.
                continue
            except BaseException:
                # The operation may raise the database's query-cancelled exception
                # as soon as ``cancel_safe`` reaches the server.  Consume its result
                # below without replacing the caller's original cancellation.
                continue

    try:
        if timeout_seconds is None:
            await drain()
        else:
            async with asyncio.timeout(timeout_seconds):
                await drain()
    except TimeoutError:
        logger.warning(
            "graph transaction child did not drain after cancellation: operation=%s",
            operation_name,
        )
        if not task.done():
            if force_close is not None:
                await force_close()
            task.cancel()
        try:
            await asyncio.shield(task)
        except BaseException:
            pass
    try:
        task.result()
    except asyncio.CancelledError:
        # A task cancelled while acquiring a pool connection is expected cleanup.
        return
    except BaseException as error:
        # QueryCanceledError/rollback errors are details of the caller's original
        # cancellation.  They are logged for diagnosis but never replace it.
        logger.debug(
            "graph transaction child ended during cancellation: operation=%s "
            "error_type=%s error=%s",
            operation_name,
            type(error).__name__,
            str(error)[:256],
        )
