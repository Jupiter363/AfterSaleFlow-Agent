from __future__ import annotations

from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal, cast

from langchain_core.runnables import Runnable
from langgraph.checkpoint.base import BaseCheckpointSaver

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graphs.evidence.contracts import (
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceGraphContext,
    EvidenceGraphContractError,
    JsonObject,
    VerifiedEvidenceAdmission,
    validate_verified_admission,
)
from app.graphs.evidence.graph import compile_evidence_v2_graph
from app.graphs.evidence.reducers import (
    assessment_refs_for_manifest,
    merge_evidence_assessments,
    require_exact_assessment_coverage,
)
from app.graphs.evidence.state import new_evidence_graph_state


EvidenceRuntimeMode = Literal["DISABLED", "SIGNED_SYNTHETIC_SHADOW"]
_RUNTIME_BINDING_METADATA_KEY = "evidence_runtime_binding_sha256"
_FORBIDDEN_AUTHORITY_KEYS = frozenset(
    {
        "formal_merge",
        "formal_status",
        "dossier_freeze",
        "freeze_dossier",
        "hearing_open",
        "open_hearing",
        "phase_transition",
        "trusted_business_decision",
        "memory_frame",
    }
)
_STATE_BINDING_FIELDS = (
    "schema_version",
    "bindings",
    "version_pins",
    "assessment_output_schema_version",
    "manifest_binding",
    "ordered_item_keys",
)
_STATE_REQUIRED_FIELDS = frozenset(
    {
        *_STATE_BINDING_FIELDS,
        "next_dispatch_index",
        "in_flight_keys",
        "raw_outputs",
        "validated_outputs",
        "execution_receipts",
        "usage_by_invocation",
        "cognitive_revision",
        "proposed_fact_matrix_patch",
        "proposed_review_items",
    }
)
_STATE_OPTIONAL_FIELDS = frozenset(
    {"route", "current_wave_keys", "terminal_draft", "result_json"}
)
_PROPOSAL_FIELDS = frozenset(
    {
        "schema_version",
        "execution_scope",
        "writer_mode",
        "formal_sink_eligible",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "thread_id",
        "manifest_id",
        "manifest_hash",
        "item_count",
        "ordered_item_keys",
        "assessment_refs",
        "coverage_status",
        "proposed_fact_links",
        "proposed_review_items",
        "profile_versions",
        "completed_at",
        "proposal_hash",
    }
)


@dataclass(frozen=True, slots=True)
class EvidenceRuntimeBundle:
    """Checkpointed synthetic-only runtime with no formal projection or sink."""

    graph: Any
    admission: VerifiedEvidenceAdmission
    completed_at: str
    runtime_mode: Literal["SIGNED_SYNTHETIC_SHADOW"]
    thread_id: str
    recursion_limit: int
    runtime_binding_sha256: str

    def start(self) -> dict[str, Any]:
        if self._checkpoint_values() is not None:
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_ALREADY_STARTED")
        result = self.graph.invoke(
            new_evidence_graph_state(admission=self.admission),
            self._config(),
            context=self._context(),
        )
        self._validate_runtime_state(result)
        return cast(dict[str, Any], result)

    def resume(self) -> dict[str, Any]:
        values = self._checkpoint_values()
        if values is None:
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_CHECKPOINT_REQUIRED")
        self._validate_runtime_state(values)
        result = self.graph.invoke(
            None,
            self._config(),
            context=self._context(),
        )
        self._validate_runtime_state(result)
        return cast(dict[str, Any], result)

    def run(self) -> dict[str, Any]:
        return self.start() if self._checkpoint_values() is None else self.resume()

    def terminal_proposal(self, state: Mapping[str, Any]) -> JsonObject:
        return extract_evidence_terminal_proposal(
            state,
            admission=self.admission,
            completed_at=self.completed_at,
        )

    def _context(self) -> EvidenceGraphContext:
        return EvidenceGraphContext(
            admission=self.admission,
            completed_at=self.completed_at,
        )

    def _checkpoint_values(self) -> Mapping[str, Any] | None:
        snapshot = self.graph.get_state(self._config())
        values = snapshot.values
        if not values:
            return None
        metadata = snapshot.metadata
        if not isinstance(metadata, Mapping) or metadata.get(
            _RUNTIME_BINDING_METADATA_KEY
        ) != self.runtime_binding_sha256:
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH")
        if _contains_forbidden_authority(values):
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_FORMAL_AUTHORITY_FORBIDDEN")
        return cast(Mapping[str, Any], values)

    def _config(self) -> dict[str, Any]:
        return {
            "configurable": {"thread_id": self.thread_id},
            "metadata": {
                _RUNTIME_BINDING_METADATA_KEY: self.runtime_binding_sha256,
            },
            "recursion_limit": self.recursion_limit,
        }

    def _validate_runtime_state(self, state: Mapping[str, Any]) -> None:
        validate_evidence_recovery_state(state, admission=self.admission)
        if state.get("result_json") is not None:
            extract_evidence_terminal_proposal(
                state,
                admission=self.admission,
                completed_at=self.completed_at,
            )


def build_evidence_runtime_bundle(
    *,
    item_assessor: Runnable[JsonObject, Mapping[str, Any]],
    admission: VerifiedEvidenceAdmission,
    completed_at: str,
    checkpointer: BaseCheckpointSaver[Any],
    runtime_mode: EvidenceRuntimeMode,
    recursion_limit: int = 128,
) -> EvidenceRuntimeBundle:
    if runtime_mode == "DISABLED":
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_DISABLED")
    if runtime_mode != "SIGNED_SYNTHETIC_SHADOW":
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_MODE_FORBIDDEN")
    command, manifest = validate_verified_admission(admission)
    if admission.runtime_mode != "SHADOW":
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_MODE_FORBIDDEN")
    if checkpointer is None:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINTER_REQUIRED")
    if not isinstance(checkpointer, BaseCheckpointSaver):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINTER_INVALID")
    if not _is_rfc3339_instant(completed_at):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_COMPLETED_AT_INVALID")
    if (
        not isinstance(recursion_limit, int)
        or isinstance(recursion_limit, bool)
        or recursion_limit < 16
    ):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_RECURSION_LIMIT_INVALID")

    expected = new_evidence_graph_state(admission=admission)
    binding: JsonObject = {
        "schema_version": "evidence-runtime-binding.v1",
        "runtime_mode": runtime_mode,
        "thread_id": cast(str, manifest["thread_id"]),
        "command_id": cast(str, command["command_id"]),
        "logical_run_id": cast(str, command["logical_run_id"]),
        "attempt_id": cast(str, command["attempt_id"]),
        "manifest_id": cast(str, manifest["manifest_id"]),
        "manifest_hash": cast(str, manifest["manifest_hash"]),
        "snapshot_payload_sha256": admission.snapshot_payload_sha256,
        "java_room_fencing_token": expected["manifest_binding"][
            "java_room_fencing_token"
        ],
        "graph_lease_fencing_token": expected["manifest_binding"][
            "graph_lease_fencing_token"
        ],
        "completed_at": completed_at,
        "version_pins": deepcopy(expected["version_pins"]),
        "assessment_output_schema_version": expected["assessment_output_schema_version"],
    }
    binding_hash = canonical_sha256(binding)
    graph = compile_evidence_v2_graph(
        item_assessor=item_assessor,
        checkpointer=checkpointer,
    )
    if graph.checkpointer is not checkpointer:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINTER_BINDING_INVALID")
    return EvidenceRuntimeBundle(
        graph=graph,
        admission=admission,
        completed_at=completed_at,
        runtime_mode="SIGNED_SYNTHETIC_SHADOW",
        thread_id=cast(str, manifest["thread_id"]),
        recursion_limit=recursion_limit,
        runtime_binding_sha256=binding_hash,
    )


def validate_evidence_recovery_state(
    state: Mapping[str, Any],
    *,
    admission: VerifiedEvidenceAdmission,
) -> None:
    if not isinstance(state, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_STATE_INVALID")
    if _contains_forbidden_authority(state):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_FORMAL_AUTHORITY_FORBIDDEN")
    fields = set(state)
    if not _STATE_REQUIRED_FIELDS <= fields or fields - (
        _STATE_REQUIRED_FIELDS | _STATE_OPTIONAL_FIELDS
    ):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_STATE_FIELDS_INVALID")
    expected = new_evidence_graph_state(admission=admission)
    for field in _STATE_BINDING_FIELDS:
        if state.get(field) != expected[field]:
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_ADMISSION_BINDING_MISMATCH")

    ordered = expected["ordered_item_keys"]
    validated = merge_evidence_assessments(
        None,
        cast(Mapping[str, JsonObject] | None, state.get("validated_outputs")),
    )
    raw = merge_evidence_assessments(
        None,
        cast(Mapping[str, JsonObject] | None, state.get("raw_outputs")),
    )
    if not set(validated) <= set(ordered) or not set(raw) <= set(ordered):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_MANIFEST_COVERAGE_INVALID")
    _validate_scheduler_progress(state, ordered, validated)
    if state.get("result_json") is not None:
        require_exact_assessment_coverage(validated, ordered)
        _validate_terminal_integrity(state)


def extract_evidence_terminal_proposal(
    state: Mapping[str, Any],
    *,
    admission: VerifiedEvidenceAdmission,
    completed_at: str,
) -> JsonObject:
    validate_evidence_recovery_state(state, admission=admission)
    _, manifest = validate_verified_admission(admission)
    ordered = cast(list[str], manifest["ordered_item_keys"])
    assessments = cast(Mapping[str, JsonObject], state["validated_outputs"])
    expected_refs = assessment_refs_for_manifest(assessments, ordered)
    proposal = state.get("result_json")
    if not isinstance(proposal, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_MISSING")
    try:
        normalized = cast(JsonObject, deepcopy(dict(proposal)))
        canonicalize(normalized)
    except (TypeError, ValueError) as error:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_INVALID") from error
    expected_hash = normalized.get("proposal_hash")
    preimage = dict(normalized)
    preimage.pop("proposal_hash", None)
    command_binding = cast(Mapping[str, Any], manifest["command_binding"])
    exact = {
        "schema_version": TERMINAL_OUTPUT_SCHEMA_VERSION,
        "execution_scope": "SIGNED_SYNTHETIC_ONLY",
        "writer_mode": "PROPOSAL_ONLY",
        "formal_sink_eligible": False,
        "command_id": command_binding["command_id"],
        "logical_run_id": command_binding["logical_run_id"],
        "attempt_id": command_binding["attempt_id"],
        "thread_id": manifest["thread_id"],
        "manifest_id": manifest["manifest_id"],
        "manifest_hash": manifest["manifest_hash"],
        "item_count": len(ordered),
        "ordered_item_keys": ordered,
        "assessment_refs": expected_refs,
        "coverage_status": "COMPLETE",
        "profile_versions": manifest["profile_versions"],
        "completed_at": completed_at,
        "proposed_fact_links": state["proposed_fact_matrix_patch"],
        "proposed_review_items": state["proposed_review_items"],
    }
    if set(normalized) != _PROPOSAL_FIELDS or any(
        normalized.get(field) != value for field, value in exact.items()
    ):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_BINDING_MISMATCH")
    if expected_hash != canonical_sha256(preimage):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROPOSAL_HASH_MISMATCH")
    return normalized


def _validate_scheduler_progress(
    state: Mapping[str, Any],
    ordered: list[str],
    validated: Mapping[str, JsonObject],
) -> None:
    index = state.get("next_dispatch_index")
    in_flight = state.get("in_flight_keys")
    current = state.get("current_wave_keys", [])
    if (
        not isinstance(index, int)
        or isinstance(index, bool)
        or not 0 <= index <= len(ordered)
        or not isinstance(in_flight, list)
        or not isinstance(current, list)
        or len(in_flight) > 8
        or len(current) > 8
        or len(in_flight) != len(set(in_flight))
        or len(current) != len(set(current))
        or any(key not in ordered for key in in_flight + current)
        or (current and current != in_flight)
        or set(validated) - set(ordered[: index + len(in_flight)])
    ):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_SCHEDULER_STATE_INVALID")


def _contains_forbidden_authority(value: Any) -> bool:
    if isinstance(value, Mapping):
        if _FORBIDDEN_AUTHORITY_KEYS & set(value):
            return True
        return any(_contains_forbidden_authority(member) for member in value.values())
    if isinstance(value, list):
        return any(_contains_forbidden_authority(member) for member in value)
    return False


def _validate_terminal_integrity(state: Mapping[str, Any]) -> None:
    result = state.get("result_json")
    terminal_draft = state.get("terminal_draft")
    if not isinstance(result, Mapping) or result != terminal_draft:
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_TERMINAL_STATE_INVALID")
    if set(result) != _PROPOSAL_FIELDS:
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_TERMINAL_STATE_INVALID")
    expected_hash = result.get("proposal_hash")
    preimage = dict(result)
    preimage.pop("proposal_hash", None)
    if expected_hash != canonical_sha256(preimage):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROPOSAL_HASH_MISMATCH")


def _is_rfc3339_instant(value: object) -> bool:
    if not isinstance(value, str) or not value:
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


__all__ = [
    "EvidenceRuntimeBundle",
    "EvidenceRuntimeMode",
    "build_evidence_runtime_bundle",
    "extract_evidence_terminal_proposal",
    "validate_evidence_recovery_state",
]
