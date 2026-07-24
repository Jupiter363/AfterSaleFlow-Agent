from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver

from app.graphs.outcome.contracts import OutcomeReviewRuntimeMode
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.graph import compile_outcome_review_v1_graph
from app.graphs.outcome.state import (
    OutcomeReviewInvocation,
    OutcomeReviewPrivateCommand,
    OutcomeReviewProjection,
    canonical_sha256,
    new_outcome_review_state,
    validate_outcome_review_recovery_state,
)
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


_BINDING_METADATA_KEY = "outcome_review_runtime_binding_sha256"


@dataclass(frozen=True, slots=True)
class OutcomeReviewGraphSession:
    graph: Any
    command: OutcomeReviewPrivateCommand
    invocation: OutcomeReviewInvocation
    runtime_binding_sha256: str

    def query(self, request: ReviewCopilotRequest) -> ReviewCopilotAnswer:
        if request != self.invocation.request:
            raise OutcomeReviewContractError("OUTCOME_REVIEW_SILENT_PACKET_REFRESH_FORBIDDEN")
        return self.run()

    def run(self) -> ReviewCopilotAnswer:
        snapshot = self.graph.get_state(self._config())
        if snapshot.values:
            self._validate_snapshot(snapshot.values, snapshot.metadata)
            result = self.graph.invoke(
                None,
                self._config(),
                context=self.invocation,
                durability="sync",
            )
        else:
            initial = new_outcome_review_state(
                command=self.command,
                request=self.invocation.request,
            )
            result = self.graph.invoke(
                initial,
                self._config(),
                context=self.invocation,
                durability="sync",
            )
        return self._answer(result)

    def _config(self) -> dict[str, Any]:
        return {
            "configurable": {"thread_id": self.command.thread_id},
            "metadata": {_BINDING_METADATA_KEY: self.runtime_binding_sha256},
            "max_concurrency": 1,
            "recursion_limit": 12,
        }

    def _validate_snapshot(
        self,
        values: Mapping[str, Any],
        metadata: Mapping[str, Any] | None,
    ) -> None:
        if not isinstance(metadata, Mapping) or metadata.get(
            _BINDING_METADATA_KEY
        ) != self.runtime_binding_sha256:
            raise OutcomeReviewContractError("OUTCOME_REVIEW_STALE_COMMAND_OR_FENCE")
        validate_outcome_review_recovery_state(
            dict(values),
            command=self.command,
            request=self.invocation.request,
        )

    @staticmethod
    def _answer(state: Mapping[str, Any]) -> ReviewCopilotAnswer:
        projection = state.get("projection")
        if not isinstance(projection, Mapping):
            if state.get("status") == "PROPOSED":
                raise OutcomeReviewContractError("OUTCOME_REVIEW_RESULT_ALREADY_PROJECTED")
            raise OutcomeReviewContractError("OUTCOME_REVIEW_RESULT_MISSING")
        value = OutcomeReviewProjection.model_validate(projection)
        if value.approval_performed or value.execution_triggered or value.is_final_decision:
            raise OutcomeReviewContractError("OUTCOME_REVIEW_FORMAL_AUTHORITY_FORBIDDEN")
        return value.answer


def build_outcome_review_graph_session(
    *,
    command: OutcomeReviewPrivateCommand,
    request: ReviewCopilotRequest,
    reviewer_actor_hash: str,
    answerer,
    validate_answer,
    checkpointer: BaseCheckpointSaver,
    runtime_mode: OutcomeReviewRuntimeMode,
    java_signature_verified: bool,
    synthetic_only: bool,
    contains_real_case_or_party_data: bool,
) -> OutcomeReviewGraphSession:
    if runtime_mode == "DISABLED":
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RUNTIME_DISABLED")
    if runtime_mode != "JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW":
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RUNTIME_MODE_FORBIDDEN")
    if (
        java_signature_verified is not True
        or synthetic_only is not True
        or contains_real_case_or_party_data is not False
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_SYNTHETIC_AUTHORITY_REQUIRED")
    if not isinstance(checkpointer, BaseCheckpointSaver):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_CHECKPOINTER_REQUIRED")
    if reviewer_actor_hash != command.reviewer_actor_hash:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_REVIEWER_BINDING_MISMATCH")
    invocation = OutcomeReviewInvocation(
        request=request,
        reviewer_actor_hash=reviewer_actor_hash,
        answerer=answerer,
        validate_answer=validate_answer,
    )
    initial = new_outcome_review_state(command=command, request=request)
    binding_hash = canonical_sha256(
        {
            "schema_version": "outcome-review-runtime-binding.v1",
            "runtime_mode": runtime_mode,
            "state": initial,
        }
    )
    graph = compile_outcome_review_v1_graph(checkpointer=checkpointer)
    if graph.checkpointer is not checkpointer:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_CHECKPOINTER_BINDING_INVALID")
    return OutcomeReviewGraphSession(
        graph=graph,
        command=command,
        invocation=invocation,
        runtime_binding_sha256=binding_hash,
    )


__all__ = ["OutcomeReviewGraphSession", "build_outcome_review_graph_session"]
