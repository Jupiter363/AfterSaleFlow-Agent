"""Public-safe failures raised by the durable Graph gateway."""

from __future__ import annotations

from enum import Enum

from psycopg import OperationalError
from psycopg import errors as psycopg_errors
from psycopg_pool import PoolTimeout, TooManyRequests


class GraphRuntimeError(RuntimeError):
    """Base failure carrying a stable code and no untrusted provider text."""

    code = "GRAPH_RUNTIME_ERROR"
    retryable = False

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.code)


class GraphContractError(GraphRuntimeError, ValueError):
    code = "GRAPH_CONTRACT_REJECTED"


class EvidenceModelInvocationContractError(GraphRuntimeError):
    """The formal Evidence workflow and its model runner cannot bind one call."""

    code = "EVIDENCE_MODEL_INVOCATION_CONTRACT_INVALID"


STABLE_INTAKE_GRAPH_CONTRACT_ERROR_CODES = frozenset(
    {
        "INTAKE_ACTION_GATE_ACTION_MISMATCH",
        "INTAKE_ACTION_GATE_DUPLICATE",
        "INTAKE_ACTION_GATE_INVALID",
        "INTAKE_ACTION_GATE_MISSING",
        "INTAKE_ACTION_GATE_ROOM_MISMATCH",
        "INTAKE_ACTION_GATE_SOURCE_INVALID",
        "INTAKE_ACTION_GATE_SOURCE_MISMATCH",
        "INTAKE_ACTION_GATE_UPDATE_INVALID",
        "INTAKE_CUSTOM_VISIBLE_DELTA_FORBIDDEN",
        "INTAKE_PUBLIC_UPDATE_BYPASS_FORBIDDEN",
        "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED",
        "INTAKE_RESPONDENT_ATTITUDE_STREAM_INVALID",
        "INTAKE_RESPONDENT_OPENING_ACTION_GATE_FORBIDDEN",
        "INTAKE_ROOM_UTTERANCE_STREAM_INVALID",
        "INTAKE_ROOM_UTTERANCE_STREAM_NORMALIZATION_DIVERGED",
        "INTAKE_ROOM_UTTERANCE_STREAM_ORDER_INVALID",
        "INTAKE_TARGET_CANONICAL_REPLAY_FIELD_INVALID",
        "INTAKE_TARGET_REPLY_FIRST_REPLAY_ORDER_INVALID",
        "INTAKE_USAGE_STREAM_DUPLICATE",
    }
)


class IntakeExecutorDiagnosticStage(str, Enum):
    """Closed, non-sensitive boundaries for an Intake executor contract failure."""

    GRAPH_STREAM_ADVANCE = "GRAPH_STREAM_ADVANCE"
    GRAPH_STREAM_CLOSE = "GRAPH_STREAM_CLOSE"
    TERMINAL_STATE_REHYDRATE = "TERMINAL_STATE_REHYDRATE"
    TERMINAL_PUBLIC_BINDING = "TERMINAL_PUBLIC_BINDING"
    CHECKPOINT_PREFLIGHT = "CHECKPOINT_PREFLIGHT"
    PROPOSAL_STORE_PUT = "PROPOSAL_STORE_PUT"
    RESULT_MATERIALIZE = "RESULT_MATERIALIZE"
    FORMAL_COMMIT = "FORMAL_COMMIT"
    TERMINAL_PUBLIC_REPLAY = "TERMINAL_PUBLIC_REPLAY"


class IntakeExecutorDiagnosticError(GraphContractError):
    """Internal carrier that preserves the public contract and original arguments."""

    def __init__(
        self,
        source: GraphContractError,
        *,
        stage: IntakeExecutorDiagnosticStage,
    ) -> None:
        if type(source) is not GraphContractError:
            raise TypeError("Intake executor diagnostics require an exact GraphContractError")
        if type(stage) is not IntakeExecutorDiagnosticStage:
            raise TypeError("Intake executor diagnostic stage is not trusted")
        Exception.__init__(self, *source.args)
        self.diagnostic_stage = stage


class GraphThreadNotFoundError(GraphRuntimeError):
    code = "GRAPH_THREAD_NOT_FOUND"


class GraphThreadBindingError(GraphRuntimeError):
    code = "GRAPH_THREAD_BINDING_CONFLICT"


class GraphVersionNotFoundError(GraphRuntimeError):
    code = "GRAPH_VERSION_NOT_FOUND"


class GraphVersionBindingError(GraphRuntimeError):
    code = "GRAPH_VERSION_BINDING_CONFLICT"


class GraphVersionUnavailableError(GraphRuntimeError):
    code = "GRAPH_VERSION_UNAVAILABLE"


class GraphGatewayDisabledError(GraphRuntimeError):
    code = "GRAPH_GATEWAY_DISABLED"


class GraphBulkheadDisabledError(GraphRuntimeError):
    code = "GRAPH_BULKHEAD_DISABLED"


class GraphBulkheadClosedError(GraphRuntimeError):
    code = "GRAPH_BULKHEAD_CLOSED"
    retryable = True


class GraphBulkheadSaturatedError(GraphRuntimeError):
    code = "GRAPH_BULKHEAD_QUEUE_SATURATED"
    retryable = True

    def __init__(self, scope: str) -> None:
        self.scope = scope
        super().__init__(f"{self.code}:{scope}")


class GraphBulkheadTimeoutError(GraphRuntimeError):
    code = "GRAPH_BULKHEAD_WAIT_TIMEOUT"
    retryable = True


class GraphBulkheadConfigurationError(GraphRuntimeError):
    code = "GRAPH_BULKHEAD_CONFIGURATION_INVALID"


class GraphPermitUnavailableError(GraphRuntimeError):
    code = "GRAPH_PERMIT_UNAVAILABLE"
    retryable = True


class GraphPermitLostError(GraphRuntimeError):
    code = "GRAPH_PERMIT_LOST"
    retryable = True


class GraphPermitBindingError(GraphRuntimeError):
    code = "GRAPH_PERMIT_BINDING_CONFLICT"


class GraphNonceReplayError(GraphRuntimeError):
    code = "GRAPH_INVOCATION_NONCE_REPLAY"


class GraphCommandNotFoundError(GraphRuntimeError):
    code = "GRAPH_COMMAND_NOT_FOUND"


class GraphCommandHashConflictError(GraphRuntimeError):
    code = "GRAPH_COMMAND_HASH_CONFLICT"


class GraphCommandBindingError(GraphRuntimeError):
    code = "GRAPH_COMMAND_BINDING_CONFLICT"


class GraphCommandStateError(GraphRuntimeError):
    code = "GRAPH_COMMAND_STATE_CONFLICT"


class GraphResultNotCommittedError(GraphCommandStateError):
    code = "GRAPH_RESULT_NOT_COMMITTED"


class GraphCommandCancelledError(GraphCommandStateError):
    code = "GRAPH_COMMAND_CANCELLED"


class GraphCommandAbortedError(GraphCommandStateError):
    code = "GRAPH_COMMAND_ABORTED"


class GraphCommandDeadlineError(GraphRuntimeError):
    code = "GRAPH_COMMAND_DEADLINE_EXCEEDED"


class GraphLeaseUnavailableError(GraphRuntimeError):
    code = "GRAPH_LEASE_UNAVAILABLE"
    retryable = True


class GraphLeaseLostError(GraphRuntimeError):
    code = "GRAPH_LEASE_LOST"
    retryable = True


class GraphCancellationRejectedError(GraphRuntimeError):
    code = "GRAPH_CANCELLATION_REJECTED"


class GraphRecoveryError(GraphRuntimeError):
    code = "GRAPH_RECOVERY_CONFLICT"


class GraphNewAgentAttemptRequiredError(GraphRecoveryError):
    """The public AgentRun attempt has started and cannot be replayed under a new fence."""

    code = "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED"
    retryable = True


class GraphTerminalBindingError(GraphRecoveryError):
    code = "GRAPH_TERMINAL_BINDING_CONFLICT"


_TRANSIENT_PERSISTENCE_ERRORS = (
    psycopg_errors.LockNotAvailable,
    psycopg_errors.ConnectionException,
    psycopg_errors.AdminShutdown,
    psycopg_errors.CrashShutdown,
    psycopg_errors.CannotConnectNow,
    psycopg_errors.SerializationFailure,
    psycopg_errors.DeadlockDetected,
    psycopg_errors.IdleInTransactionSessionTimeout,
    PoolTimeout,
    TooManyRequests,
)


def normalize_transient_persistence_error(error: Exception) -> GraphRuntimeError | None:
    """Map only proven transient PostgreSQL failures to a public-safe retry contract.

    Psycopg uses the bare ``OperationalError`` for failures such as a connection
    timeout.  Generated subclasses are deliberately not covered by that fallback:
    each retryable SQLSTATE must be opted in above so an unknown database failure
    remains non-retryable at the API boundary.
    """

    if type(error) in _TRANSIENT_PERSISTENCE_ERRORS or type(error) is OperationalError:
        return GraphLeaseUnavailableError()
    return None
