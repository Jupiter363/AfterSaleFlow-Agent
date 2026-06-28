from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
from hashlib import sha256
from typing import Any, Iterator, Protocol


@dataclass(frozen=True)
class AgentTraceContext:
    trace_id: str
    request_id: str
    case_id: str
    workflow_id: str
    user_id: str | None
    role: str
    prompt_version: str


def redacted_trace_input(prompt: str) -> str:
    digest = sha256(prompt.encode("utf-8")).hexdigest()
    return f"[REDACTED_INPUT sha256={digest} length={len(prompt)}]"


class WorkflowTrace(Protocol):
    def complete(self, output: dict[str, Any]) -> None: ...


class AgentTracer(Protocol):
    @contextmanager
    def workflow(
        self, context: AgentTraceContext, payload: dict[str, Any]
    ) -> Iterator[WorkflowTrace]: ...

    def generation(
        self,
        context: AgentTraceContext,
        node_name: str,
        model: str,
        prompt: str,
        output: dict[str, Any],
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None: ...


class _NoOpWorkflowTrace:
    def complete(self, output: dict[str, Any]) -> None:
        pass


class NoOpAgentTracer:
    @contextmanager
    def workflow(
        self, context: AgentTraceContext, payload: dict[str, Any]
    ) -> Iterator[WorkflowTrace]:
        yield _NoOpWorkflowTrace()

    def generation(
        self,
        context: AgentTraceContext,
        node_name: str,
        model: str,
        prompt: str,
        output: dict[str, Any],
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None:
        pass


class LangfuseAgentTracer:
    def __init__(self, public_key: str, secret_key: str, host: str) -> None:
        from langfuse import Langfuse

        self._client = Langfuse(
            public_key=public_key,
            secret_key=secret_key,
            base_url=host,
        )

    @contextmanager
    def workflow(
        self, context: AgentTraceContext, payload: dict[str, Any]
    ) -> Iterator[WorkflowTrace]:
        from langfuse import propagate_attributes

        with self._client.start_as_current_observation(
            as_type="span",
            name="hearing-c1-c6",
            input=payload,
        ) as root:
            with propagate_attributes(
                trace_name="hearing-c1-c6",
                user_id=context.user_id,
                session_id=context.case_id,
                version=context.prompt_version,
                metadata={
                    "trace_id": context.trace_id,
                    "request_id": context.request_id,
                    "case_id": context.case_id,
                    "workflow_id": context.workflow_id,
                    "role": context.role,
                },
                tags=["hearing", "c1-c6"],
            ):
                yield _LangfuseWorkflowTrace(root)

    def generation(
        self,
        context: AgentTraceContext,
        node_name: str,
        model: str,
        prompt: str,
        output: dict[str, Any],
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None:
        with self._client.start_as_current_observation(
            as_type="generation",
            name=node_name,
            model=model,
            input=prompt,
            output=output,
            metadata={
                "node_name": node_name,
                "prompt_version": context.prompt_version,
                "latency_ms": str(latency_ms),
                "token_usage": str(token_usage.get("total", 0)),
            },
        ):
            pass

    def flush(self) -> None:
        self._client.flush()


class _LangfuseWorkflowTrace:
    def __init__(self, observation: Any) -> None:
        self._observation = observation

    def complete(self, output: dict[str, Any]) -> None:
        self._observation.update(output=output)
