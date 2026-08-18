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

STABLE_EVIDENCE_GRAPH_CONTRACT_ERROR_CODES = frozenset(
    {
        "EVIDENCE_OPENING_PUBLIC_REPLY_BINDING_INVALID",
        "EVIDENCE_PUBLIC_OBSERVATION_ATTACHMENT_SCOPE_INVALID",
        "EVIDENCE_PUBLIC_OBSERVATION_CANONICAL_TEXT_INVALID",
        "EVIDENCE_PUBLIC_OBSERVATION_STREAM_FIELD_INVALID",
        "EVIDENCE_PUBLIC_OBSERVATION_STREAM_ITEM_INVALID",
        "EVIDENCE_PUBLIC_OBSERVATION_STREAM_NODE_INVALID",
        "EVIDENCE_PUBLIC_OBSERVATION_TERMINAL_RECONCILIATION_INVALID",
        "EVIDENCE_PUBLIC_OUTPUT_BACKPRESSURE_EXCEEDED",
        "EVIDENCE_PUBLIC_OUTPUT_BRIDGE_UNAVAILABLE",
        "EVIDENCE_PUBLIC_OUTPUT_FIELD_INVALID",
        "EVIDENCE_PUBLIC_OUTPUT_LOOP_CLOSED",
        "EVIDENCE_PUBLIC_OUTPUT_OBSERVER_DUPLICATED",
        "EVIDENCE_PUBLIC_OUTPUT_OBSERVER_UNAVAILABLE",
        "EVIDENCE_PUBLIC_OUTPUT_RUN_ALREADY_ACTIVE",
        "EVIDENCE_PUBLIC_OUTPUT_TERMINAL_MISMATCH",
        "EVIDENCE_TURN_CHECKPOINT_INVALID",
        "EVIDENCE_TURN_CLOCK_INVALID",
        "EVIDENCE_TURN_FORMAL_INVOCATION_REQUIRED",
        "EVIDENCE_TURN_FORMAL_WORKFLOW_REQUIRED",
        "EVIDENCE_TURN_INVOCATION_BINDING_INVALID",
        "EVIDENCE_TURN_INVOCATION_DOCUMENT_INVALID",
        "EVIDENCE_TURN_INVOCATION_HASH_INVALID",
        "EVIDENCE_TURN_INVOCATION_OBJECT_BINDING_INVALID",
        "EVIDENCE_TURN_MODEL_INVOCATION_BINDING_INVALID",
        "EVIDENCE_TURN_PROPOSAL_INVALID",
        "EVIDENCE_TURN_RECOVERY_BINDING_INVALID",
        "EVIDENCE_TURN_RECOVERY_STATE_INVALID",
        "EVIDENCE_TURN_REQUEST_INVALID",
        "EVIDENCE_TURN_RESULT_PROJECTION_INVALID",
        "EVIDENCE_TURN_USAGE_INVALID",
        "EVIDENCE_V2_ASSESSMENT_CARDINALITY_INVALID",
        "EVIDENCE_V2_ASSESSMENT_OUT_OF_SCOPE",
        "EVIDENCE_V2_ASSESSMENT_SLOT_COVERAGE_INVALID",
        "EVIDENCE_V2_ASSESSMENT_SLOT_DUPLICATED",
        "EVIDENCE_V2_ASSESSMENT_SLOT_OUT_OF_SCOPE",
        "EVIDENCE_V2_ASSESSMENT_SLOT_UNKNOWN",
        "EVIDENCE_V2_ATTACHMENT_SCOPE_DUPLICATED",
        "EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE",
        "EVIDENCE_V2_FORMAL_WORKFLOW_REQUIRED",
        "EVIDENCE_V2_FRAME_DUPLICATED",
        "EVIDENCE_V2_FRAME_EVENT_INVALID",
        "EVIDENCE_V2_FRAME_FIELD_INVALID",
        "EVIDENCE_V2_FRAME_HEADER_INVALID",
        "EVIDENCE_V2_FRAME_ORDER_INVALID",
        "EVIDENCE_V2_FRAME_POLICY_REQUIRES_PROJECTOR",
        "EVIDENCE_V2_FRAME_SEQUENCE_INVALID",
        "EVIDENCE_V2_FRAME_STREAM_EMPTY",
        "EVIDENCE_V2_FRAME_TERMINAL_INCOMPLETE",
        "EVIDENCE_V2_FRAME_TYPE_NOT_ALLOWED",
        "EVIDENCE_V2_MATERIAL_FRAME_ORDER_INVALID",
        "EVIDENCE_V2_MATERIAL_RECEIPT_REQUIRED",
        "EVIDENCE_V2_MATERIAL_RECEIPT_SCOPE_INVALID",
        "EVIDENCE_V2_OBSERVATION_DUPLICATED",
        "EVIDENCE_V2_OBSERVATION_SLOT_DUPLICATED",
        "EVIDENCE_V2_OPENING_FRAME_ORDER_INVALID",
        "EVIDENCE_V2_PUBLIC_DELTA_INVALID",
        "EVIDENCE_V2_PUBLIC_OUTPUT_TERMINAL_MISMATCH",
        "EVIDENCE_V2_PUBLIC_POLICY_REQUIRED",
        "EVIDENCE_V2_READINESS_NOT_LAST",
        "EVIDENCE_V2_REENTRY_REQUIRES_DURABLE_REPLAY",
        "EVIDENCE_V2_REQUEST_FACT_OUT_OF_SCOPE",
        "EVIDENCE_V2_REQUEST_SLOT_DUPLICATED",
        "EVIDENCE_V2_RESULT_REQUIRED",
        "EVIDENCE_V2_REVIEW_TASK_OUT_OF_SCOPE",
        "EVIDENCE_V2_SOURCE_UNIT_OUT_OF_SCOPE",
        "EVIDENCE_V2_STREAM_COMPLETION_DUPLICATED",
        "EVIDENCE_V2_STREAM_COMPLETION_MISSING",
        "EVIDENCE_V2_STREAM_UPDATE_INVALID",
        "EVIDENCE_V2_STREAMING_RUNNER_REQUIRED",
        "EVIDENCE_V2_TEXT_MODE_CONTAINS_MATERIAL_FRAME",
        "EVIDENCE_V2_TEXT_REPLY_REQUIRED",
        "EVIDENCE_V2_TURN_MODE_INVALID",
        "EVIDENCE_V3_FRAME_EVENT_INVALID",
        "EVIDENCE_V3_FRAME_HEADER_DUPLICATED",
        "EVIDENCE_V3_FRAME_HEADER_INVALID",
        "EVIDENCE_V3_FRAME_HEADER_MISSING",
        "EVIDENCE_V3_FRAME_IDENTITY_MISSING",
        "EVIDENCE_V3_FRAME_TERMINAL_INCOMPLETE",
        "EVIDENCE_V3_FRAME_TEXT_INVALID",
    }
)
STABLE_GRAPH_CONTRACT_DIAGNOSTIC_CODES = (
    STABLE_INTAKE_GRAPH_CONTRACT_ERROR_CODES
    | STABLE_EVIDENCE_GRAPH_CONTRACT_ERROR_CODES
)


def stable_graph_contract_diagnostic_code(error: BaseException) -> str | None:
    """Return only a reviewed, non-provider diagnostic from an exact contract error."""

    if (
        type(error) is not GraphContractError
        or len(error.args) != 1
        or type(error.args[0]) is not str
    ):
        return None
    code = error.args[0]
    if code in STABLE_GRAPH_CONTRACT_DIAGNOSTIC_CODES:
        return code
    return None


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
