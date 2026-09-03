from __future__ import annotations

import math
import re
from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal, cast

from langchain_core.runnables import Runnable
from langchain_core.runnables.config import RunnableConfig

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graph_runtime.checkpoint import FencedPostgresSaver, bind_fence_context
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graphs.evidence.contracts import (
    ASSESSMENT_OUTPUT_SCHEMA_VERSION,
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceGraphContext,
    EvidenceGraphContractError,
    JsonObject,
    VerifiedEvidenceAdmission,
    evidence_execution_scope,
    manifest_items_by_key,
    validate_verified_admission,
)
from app.graphs.evidence.graph import compile_evidence_v2_graph
from app.graphs.evidence.nodes import _ASSESSMENT_FIELDS, _validate_assessment
from app.graphs.evidence.reducers import (
    assessment_refs_for_manifest,
    merge_evidence_assessments,
    require_exact_assessment_coverage,
)
from app.graphs.evidence.state import EvidenceGraphStateV2, new_evidence_graph_state


EvidenceRuntimeMode = Literal[
    "DISABLED",
    "SIGNED_SYNTHETIC_SHADOW",
    "TARGET_E2E_CANDIDATE",
]
_RUNTIME_BINDING_METADATA_KEY = "evidence_runtime_binding_sha256"
_RUNTIME_COMPLETED_AT_METADATA_KEY = "evidence_runtime_completed_at"
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_FACT_ID = re.compile(r"^FACT_[A-Za-z0-9_:-]{1,123}$")
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
    """Checkpointed proposal-only runtime with no formal projection or sink."""

    graph: Any
    admission: VerifiedEvidenceAdmission
    completed_at: str
    runtime_mode: Literal["SIGNED_SYNTHETIC_SHADOW", "TARGET_E2E_CANDIDATE"]
    thread_id: str
    recursion_limit: int
    runtime_binding_sha256: str
    fence: GraphFenceContext

    async def astart(self) -> dict[str, Any]:
        if await self._checkpoint_values() is not None:
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_ALREADY_STARTED")
        initial = new_evidence_graph_state(admission=self.admission)
        initial["cognitive_revision"] = 1
        result = await self.graph.ainvoke(
            initial,
            self._config(),
            context=self._context(),
        )
        self._validate_runtime_state(result)
        return cast(dict[str, Any], result)

    async def aresume(self) -> dict[str, Any]:
        values = await self._checkpoint_values()
        if values is None:
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_CHECKPOINT_REQUIRED")
        self._validate_runtime_state(values)
        result = await self.graph.ainvoke(
            None,
            self._config(),
            context=self._context(),
        )
        self._validate_runtime_state(result)
        return cast(dict[str, Any], result)

    async def arun(self) -> dict[str, Any]:
        return (
            await self.astart()
            if await self._checkpoint_values() is None
            else await self.aresume()
        )

    def terminal_proposal(self, state: Mapping[str, Any]) -> JsonObject:
        return extract_evidence_terminal_proposal(
            state,
            admission=self.admission,
            completed_at=self.completed_at,
        )

    async def aterminal_checkpoint(
        self,
    ) -> tuple[Mapping[str, Any], RunnableConfig]:
        snapshot = await self.graph.aget_state(self._config())
        values = getattr(snapshot, "values", None)
        config = getattr(snapshot, "config", None)
        if not isinstance(values, Mapping) or not isinstance(config, Mapping):
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINT_INVALID")
        self._validate_runtime_state(values)
        configurable = config.get("configurable")
        if (
            not isinstance(configurable, Mapping)
            or configurable.get("thread_id") != self.thread_id
            or not isinstance(configurable.get("checkpoint_id"), str)
            or not configurable.get("checkpoint_id")
        ):
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINT_INVALID")
        return cast(Mapping[str, Any], values), cast(RunnableConfig, dict(config))

    def _context(self) -> EvidenceGraphContext:
        return EvidenceGraphContext(
            admission=self.admission,
            completed_at=self.completed_at,
        )

    async def _checkpoint_values(self) -> Mapping[str, Any] | None:
        snapshot = await self.graph.aget_state(self._config())
        values = snapshot.values
        if not values:
            return None
        metadata = snapshot.metadata
        if not isinstance(metadata, Mapping) or metadata.get(
            _RUNTIME_BINDING_METADATA_KEY
        ) != self.runtime_binding_sha256:
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH")
        return cast(Mapping[str, Any], values)

    def _config(self) -> dict[str, Any]:
        config: dict[str, Any] = {
            "configurable": {"thread_id": self.thread_id},
            "metadata": {
                _RUNTIME_BINDING_METADATA_KEY: self.runtime_binding_sha256,
                _RUNTIME_COMPLETED_AT_METADATA_KEY: self.completed_at,
            },
            "recursion_limit": self.recursion_limit,
        }
        return dict(bind_fence_context(config, self.fence))

    def _validate_runtime_state(self, state: Mapping[str, Any]) -> None:
        validate_evidence_recovery_state(state, admission=self.admission)
        terminal_draft = state.get("terminal_draft")
        if isinstance(terminal_draft, Mapping):
            _validate_proposal_document(
                state,
                terminal_draft,
                admission=self.admission,
                completed_at=self.completed_at,
            )
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
    checkpointer: FencedPostgresSaver,
    bulkhead: PostgresGraphFanoutBulkhead,
    fence: GraphFenceContext,
    runtime_mode: EvidenceRuntimeMode,
    recursion_limit: int = 128,
) -> EvidenceRuntimeBundle:
    if runtime_mode == "DISABLED":
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_DISABLED")
    if runtime_mode not in {"SIGNED_SYNTHETIC_SHADOW", "TARGET_E2E_CANDIDATE"}:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_MODE_FORBIDDEN")
    command, manifest = validate_verified_admission(admission)
    expected_admission_mode = (
        "TARGET_E2E_CANDIDATE"
        if runtime_mode == "TARGET_E2E_CANDIDATE"
        else "SHADOW"
    )
    if admission.runtime_mode != expected_admission_mode:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_MODE_FORBIDDEN")
    if not isinstance(checkpointer, FencedPostgresSaver):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_FENCED_CHECKPOINTER_REQUIRED")
    if not isinstance(bulkhead, PostgresGraphFanoutBulkhead):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_POSTGRES_BULKHEAD_REQUIRED")
    _validate_runtime_fence(fence, command=command, admission=admission)
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
        "graph_lease_owner_id": fence.owner_id,
        "completed_at": completed_at,
        "version_pins": deepcopy(expected["version_pins"]),
        "assessment_output_schema_version": expected["assessment_output_schema_version"],
    }
    binding_hash = canonical_sha256(binding)
    graph = compile_evidence_v2_graph(
        item_assessor=item_assessor,
        checkpointer=checkpointer,
        bulkhead=bulkhead,
        graph_fence=fence,
    )
    if graph.checkpointer is not checkpointer:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINTER_BINDING_INVALID")
    return EvidenceRuntimeBundle(
        graph=graph,
        admission=admission,
        completed_at=completed_at,
        runtime_mode=runtime_mode,
        thread_id=cast(str, manifest["thread_id"]),
        recursion_limit=recursion_limit,
        runtime_binding_sha256=binding_hash,
        fence=fence,
    )


async def recover_evidence_runtime_completed_at(
    *,
    checkpointer: FencedPostgresSaver,
    fence: GraphFenceContext,
) -> str | None:
    config = bind_fence_context(
        {"configurable": {"thread_id": fence.thread_id}},
        fence,
    )
    checkpoint = await checkpointer.aget_tuple(config)
    if checkpoint is None:
        return None
    metadata = getattr(checkpoint, "metadata", None)
    if not isinstance(metadata, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH")
    completed_at = metadata.get(_RUNTIME_COMPLETED_AT_METADATA_KEY)
    if not _is_rfc3339_instant(completed_at):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH")
    return cast(str, completed_at)


def validate_evidence_recovery_state(
    state: Mapping[str, Any],
    *,
    admission: VerifiedEvidenceAdmission,
) -> None:
    if not isinstance(state, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_STATE_INVALID")
    fields = set(state)
    if not _STATE_REQUIRED_FIELDS <= fields or fields - (
        _STATE_REQUIRED_FIELDS | _STATE_OPTIONAL_FIELDS
    ):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_STATE_FIELDS_INVALID")
    _, manifest = validate_verified_admission(admission)
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
    items = manifest_items_by_key(manifest)
    typed_state = cast(EvidenceGraphStateV2, state)
    for assessments in (validated, raw):
        for evidence_id, assessment in assessments.items():
            item = items.get(evidence_id)
            if item is None:
                raise EvidenceGraphContractError("EVIDENCE_RECOVERY_MANIFEST_COVERAGE_INVALID")
            # Recovery must apply the exact validator used before A1 fan-in.
            _validate_assessment(typed_state, manifest, item, assessment)
            _validate_assessment_wire(assessment, item=item)
    _validate_runtime_records(state)
    _validate_scheduler_progress(state, ordered, validated, raw)
    if state["proposed_fact_matrix_patch"] or state["proposed_review_items"]:
        complete = require_exact_assessment_coverage(validated, ordered)
        expected_matrix, expected_reviews = _expected_terminal_projections(complete, ordered)
        if (
            state["proposed_fact_matrix_patch"] != expected_matrix
            or state["proposed_review_items"] != expected_reviews
        ):
            raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROJECTION_INVALID")
    terminal_draft = state.get("terminal_draft")
    if terminal_draft is not None:
        if not isinstance(terminal_draft, Mapping) or not _is_rfc3339_instant(
            terminal_draft.get("completed_at")
        ):
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_TERMINAL_STATE_INVALID")
        require_exact_assessment_coverage(validated, ordered)
        _validate_proposal_document(
            state,
            terminal_draft,
            admission=admission,
            completed_at=cast(str, terminal_draft["completed_at"]),
        )
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
    require_exact_assessment_coverage(
        cast(Mapping[str, JsonObject], state["validated_outputs"]),
        cast(list[str], manifest["ordered_item_keys"]),
    )
    proposal = state.get("result_json")
    if not isinstance(proposal, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_MISSING")
    return _validate_proposal_document(
        state,
        proposal,
        admission=admission,
        completed_at=completed_at,
    )


def _validate_proposal_document(
    state: Mapping[str, Any],
    proposal: Mapping[str, Any],
    *,
    admission: VerifiedEvidenceAdmission,
    completed_at: str,
) -> JsonObject:
    _, manifest = validate_verified_admission(admission)
    ordered = cast(list[str], manifest["ordered_item_keys"])
    assessments = cast(Mapping[str, JsonObject], state["validated_outputs"])
    expected_refs = assessment_refs_for_manifest(assessments, ordered)
    try:
        normalized = cast(JsonObject, deepcopy(dict(proposal)))
        encoded = canonicalize(normalized)
    except (TypeError, ValueError) as error:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_INVALID") from error
    if len(encoded) > 262_144:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_INVALID")
    expected_hash = normalized.get("proposal_hash")
    preimage = dict(normalized)
    preimage.pop("proposal_hash", None)
    command_binding = cast(Mapping[str, Any], manifest["command_binding"])
    expected_fact_links, expected_review_items = _expected_terminal_projections(
        assessments,
        ordered,
    )
    exact = {
        "schema_version": TERMINAL_OUTPUT_SCHEMA_VERSION,
        "execution_scope": evidence_execution_scope(admission),
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
        "proposed_fact_links": expected_fact_links,
        "proposed_review_items": expected_review_items,
    }
    if set(normalized) != _PROPOSAL_FIELDS or any(
        normalized.get(field) != value for field, value in exact.items()
    ):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_BINDING_MISMATCH")
    if expected_hash != canonical_sha256(preimage):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROPOSAL_HASH_MISMATCH")
    if (
        state["proposed_fact_matrix_patch"] != expected_fact_links
        or state["proposed_review_items"] != expected_review_items
    ):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_PROPOSAL_BINDING_MISMATCH")
    return normalized


def _validate_scheduler_progress(
    state: Mapping[str, Any],
    ordered: list[str],
    validated: Mapping[str, JsonObject],
    raw: Mapping[str, JsonObject],
) -> None:
    index = state.get("next_dispatch_index")
    in_flight = state.get("in_flight_keys")
    current = state.get("current_wave_keys", [])
    revision = state.get("cognitive_revision")
    route = state.get("route")
    if (
        not isinstance(revision, int)
        or isinstance(revision, bool)
        or revision < 1
        or not isinstance(index, int)
        or isinstance(index, bool)
        or not 0 <= index <= len(ordered)
        or not isinstance(in_flight, list)
        or not isinstance(current, list)
        or len(in_flight) > 8
        or len(current) > 8
        or len(in_flight) != len(set(in_flight))
        or len(current) != len(set(current))
        or any(key not in ordered for key in in_flight + current)
        or current != in_flight
        or in_flight != ordered[index : index + len(in_flight)]
        or not set(ordered[:index]) <= set(validated)
        or set(validated) - set(ordered[: index + len(in_flight)])
        or not set(raw) <= set(in_flight)
        or route not in {None, "dispatch", "complete"}
        or (route == "complete" and (index != len(ordered) or in_flight))
    ):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_SCHEDULER_STATE_INVALID")


def _validate_runtime_fence(
    fence: GraphFenceContext,
    *,
    command: JsonObject,
    admission: VerifiedEvidenceAdmission,
) -> None:
    if type(fence) is not GraphFenceContext:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_FENCE_REQUIRED")
    expected = {
        "thread_id": command["thread_id"],
        "command_id": command["command_id"],
        "fencing_token": admission.graph_lease_fencing_token,
        "request_hash": command["request_hash"],
        "room_epoch": command["room_epoch"],
        "graph_key": command["graph_key"],
        "graph_version": command["graph_version"],
        "checkpoint_schema_version": command["checkpoint_schema_version"],
    }
    if any(getattr(fence, field) != value for field, value in expected.items()):
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_FENCE_BINDING_MISMATCH")
    if fence.result_hash is not None or fence.result_ref is not None:
        raise EvidenceGraphContractError("EVIDENCE_RUNTIME_FORMAL_RESULT_FENCE_FORBIDDEN")


def _validate_assessment_wire(assessment: JsonObject, *, item: JsonObject) -> None:
    if set(assessment) != _ASSESSMENT_FIELDS or len(canonicalize(assessment)) > 65_536:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FIELDS_INVALID")
    for field in ("command_id", "logical_run_id", "attempt_id", "manifest_id"):
        if not isinstance(assessment[field], str) or not _IDENTIFIER.fullmatch(
            cast(str, assessment[field])
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_BINDING_MISMATCH")
    for field in ("assessment_hash", "manifest_hash", "item_hash", "actor_scope_hash"):
        if not isinstance(assessment[field], str) or not _SHA256.fullmatch(
            cast(str, assessment[field])
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_HASH_INVALID")
    if assessment["schema_version"] != ASSESSMENT_OUTPUT_SCHEMA_VERSION:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_SCOPE_INVALID")
    for field in (
        "authenticity_score",
        "relevance_score",
        "completeness_score",
        "confidence",
    ):
        value = assessment[field]
        if (
            not isinstance(value, (int, float))
            or isinstance(value, bool)
            or not math.isfinite(value)
            or not 0 <= value <= 1
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_SCORE_INVALID")
    for field, maximum, minimum in (
        ("authenticity_reason_codes", 16, 0),
        ("relevance_reason_codes", 16, 0),
        ("source_refs", 64, 1),
        ("limitations", 16, 0),
        ("review_reasons", 16, 0),
    ):
        values = assessment[field]
        if not _is_sorted_identifier_list(values, minimum=minimum, maximum=maximum):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_LIST_INVALID")
    inspected = assessment["inspected_modalities"]
    permitted = item["permitted_modalities"]
    if (
        not isinstance(inspected, list)
        or inspected != sorted(set(inspected))
        or len(inspected) > 4
        or not set(inspected) <= {"TEXT", "IMAGE_PIXELS", "PDF_METADATA", "OCR"}
        or not isinstance(permitted, list)
        or not set(inspected) <= set(permitted)
    ):
        raise EvidenceGraphContractError("EVIDENCE_ASSET_RECEIPT_BINDING_INVALID")
    links = assessment["candidate_fact_links"]
    if not isinstance(links, list) or len(links) > 64:
        raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FACT_LINK_INVALID")
    for link in links:
        if (
            not isinstance(link, dict)
            or set(link) != {"fact_id", "source_refs"}
            or not isinstance(link["fact_id"], str)
            or not _FACT_ID.fullmatch(link["fact_id"])
            or not _is_sorted_identifier_list(link["source_refs"], minimum=1, maximum=16)
        ):
            raise EvidenceGraphContractError("EVIDENCE_ASSESSMENT_FACT_LINK_INVALID")
    receipt_ref = assessment["asset_load_receipt_ref"]
    receipt_hash = assessment["asset_load_receipt_hash"]
    if assessment["asset_load_status"] == "LOADED" and (
        not isinstance(receipt_ref, str)
        or not _IDENTIFIER.fullmatch(receipt_ref)
        or not isinstance(receipt_hash, str)
        or not _SHA256.fullmatch(receipt_hash)
    ):
        raise EvidenceGraphContractError("EVIDENCE_ASSET_RECEIPT_BINDING_INVALID")


def _validate_runtime_records(state: Mapping[str, Any]) -> None:
    receipts = state["execution_receipts"]
    usage_by_invocation = state["usage_by_invocation"]
    if (
        not isinstance(receipts, dict)
        or not isinstance(usage_by_invocation, dict)
        or len(receipts) > 100
        or len(usage_by_invocation) > 100
    ):
        raise EvidenceGraphContractError("EVIDENCE_RECOVERY_RUNTIME_RECORD_INVALID")
    for invocation_id, receipt in receipts.items():
        if (
            not isinstance(invocation_id, str)
            or not _IDENTIFIER.fullmatch(invocation_id)
            or not isinstance(receipt, dict)
            or set(receipt) != {"invocation_id", "node_name", "output_hash"}
            or receipt["invocation_id"] != invocation_id
            or not isinstance(receipt["node_name"], str)
            or not _IDENTIFIER.fullmatch(receipt["node_name"])
            or not isinstance(receipt["output_hash"], str)
            or not _SHA256.fullmatch(receipt["output_hash"])
        ):
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_RUNTIME_RECORD_INVALID")
    for invocation_id, usage in usage_by_invocation.items():
        if (
            not isinstance(invocation_id, str)
            or not _IDENTIFIER.fullmatch(invocation_id)
            or not isinstance(usage, dict)
            or set(usage) != {"input_tokens", "output_tokens", "total_tokens"}
            or any(
                not isinstance(usage[field], int)
                or isinstance(usage[field], bool)
                or usage[field] < 0
                for field in ("input_tokens", "output_tokens", "total_tokens")
            )
            or usage["total_tokens"] != usage["input_tokens"] + usage["output_tokens"]
        ):
            raise EvidenceGraphContractError("EVIDENCE_RECOVERY_RUNTIME_RECORD_INVALID")


def _expected_terminal_projections(
    assessments: Mapping[str, JsonObject],
    ordered: list[str],
) -> tuple[list[JsonObject], list[JsonObject]]:
    fact_links: dict[str, dict[str, set[str]]] = {}
    reviews: list[JsonObject] = []
    for evidence_id in ordered:
        assessment = assessments[evidence_id]
        for link in cast(list[dict[str, Any]], assessment["candidate_fact_links"]):
            grouped = fact_links.setdefault(
                cast(str, link["fact_id"]),
                {"evidence_ids": set(), "source_refs": set()},
            )
            grouped["evidence_ids"].add(evidence_id)
            grouped["source_refs"].update(cast(list[str], link["source_refs"]))
        if assessment["assessment_status"] == "NEEDS_REVIEW":
            review_key = f"REVIEW_{evidence_id}"
            if not _IDENTIFIER.fullmatch(review_key):
                raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROJECTION_INVALID")
            reviews.append(
                {
                    "review_key": review_key,
                    "evidence_id": evidence_id,
                    "reason_codes": deepcopy(assessment["review_reasons"]),
                    "priority": "MEDIUM",
                }
            )
    matrix: list[JsonObject] = [
        {
            "fact_id": fact_id,
            "evidence_ids": sorted(values["evidence_ids"]),
            "source_refs": sorted(values["source_refs"]),
        }
        for fact_id, values in sorted(fact_links.items())
    ]
    if (
        len(matrix) > 100
        or len(reviews) > 100
        or any(len(cast(list[str], link["source_refs"])) > 64 for link in matrix)
    ):
        raise EvidenceGraphContractError("EVIDENCE_TERMINAL_PROJECTION_LIMIT_EXCEEDED")
    return matrix, reviews


def _is_sorted_identifier_list(value: Any, *, minimum: int, maximum: int) -> bool:
    return (
        isinstance(value, list)
        and minimum <= len(value) <= maximum
        and value == sorted(set(value))
        and all(isinstance(member, str) and _IDENTIFIER.fullmatch(member) for member in value)
    )


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
    "recover_evidence_runtime_completed_at",
    "validate_evidence_recovery_state",
]
