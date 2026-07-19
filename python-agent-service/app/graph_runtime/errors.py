"""Public-safe failures raised by the durable Graph gateway."""

from __future__ import annotations


class GraphRuntimeError(RuntimeError):
    """Base failure carrying a stable code and no untrusted provider text."""

    code = "GRAPH_RUNTIME_ERROR"
    retryable = False

    def __init__(self, message: str | None = None) -> None:
        super().__init__(message or self.code)


class GraphContractError(GraphRuntimeError, ValueError):
    code = "GRAPH_CONTRACT_REJECTED"


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
