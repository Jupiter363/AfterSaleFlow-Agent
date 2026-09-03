"""Public projection policy for Evidence frame streams."""

from __future__ import annotations

import json
import logging
from collections.abc import Iterable, Mapping
from typing import Any

from app.agents.evidence_clerk.v2_contracts import (
    EvidenceFrameHeaderV2,
    leading_evidence_frame_header_v2,
    validate_evidence_frame_header_v2,
)
from app.graph_runtime.errors import GraphContractError


LOGGER = logging.getLogger(__name__)


def bind_room_readiness_fact_ids(
    header: EvidenceFrameHeaderV2,
    allowed_fact_ids: Iterable[str],
) -> EvidenceFrameHeaderV2:
    """Project readiness-only references onto immutable frozen fact authority.

    ``remaining_core_fact_ids`` is an optional status projection and an empty
    list is valid. Provider-created identifiers therefore carry no authority:
    preserve allowed identifiers in provider order and discard only
    out-of-scope references. Actionable observation/request bindings keep
    their fail-closed checks elsewhere in this policy.
    """

    if header.frame_type != "ROOM_READINESS":
        return header
    allowed = frozenset(str(fact_id) for fact_id in allowed_fact_ids)
    return header.model_copy(
        update={
            "remaining_core_fact_ids": [
                fact_id
                for fact_id in header.remaining_core_fact_ids
                if fact_id in allowed
            ]
        }
    )


def bind_assessment_observation_slots(
    header: EvidenceFrameHeaderV2,
    observation_evidence: Mapping[str, str] | Iterable[tuple[str, str]],
) -> EvidenceFrameHeaderV2:
    """Bind assessment slots from source authority, never provider guesswork."""

    if header.frame_type != "EVIDENCE_ASSESSMENT":
        return header
    evidence_id = str(header.evidence_id or "")
    if not evidence_id:
        return header
    bindings = (
        observation_evidence.items()
        if isinstance(observation_evidence, Mapping)
        else observation_evidence
    )
    slots = [
        slot
        for slot, bound_evidence_id in bindings
        if bound_evidence_id == evidence_id
    ]
    return header.model_copy(update={"observation_slots": slots})


class EvidenceV2PublicOutputPolicy:
    """Accept structure, then pass model public text through unchanged.

    Header/ID authority checks are deterministic.  The text itself is never
    searched, rewritten, composed, or semantically re-evaluated.
    """

    def __init__(self) -> None:
        self._frames: list[dict[str, Any]] = []
        self._current: dict[str, Any] | None = None
        self._visible_text = ""
        self._started = False
        self._finalized = False
        self._mode: str | None = None
        self._allowed_frame_types: frozenset[str] = frozenset()
        self._fact_ids: frozenset[str] = frozenset()
        self._attachment_ids: tuple[str, ...] = ()
        self._source_units: dict[str, dict[str, Any]] = {}
        # Model-owned observation slots are not uniqueness keys.  Preserve every
        # source binding in emission order so a repeated label never rejects the
        # whole turn or silently overwrites an earlier attachment binding.
        self._observation_evidence: list[tuple[str, str]] = []
        self._leading_header: EvidenceFrameHeaderV2 | None = None

    def configure(self, assembled: Any) -> None:
        """Bind the live policy to one immutable assembled room context."""

        payload = assembled.payload
        contract = payload["turn_contract"]
        self._mode = str(contract["turn_mode"])
        self._allowed_frame_types = frozenset(contract["allowed_frame_types"])
        self._fact_ids = frozenset(
            item["fact_id"] for item in assembled.base.working_set.allowed_fact_targets
        )
        self._attachment_ids = tuple(
            assembled.base.raw_envelope.current_event.attachment_refs
        )
        self._leading_header = leading_evidence_frame_header_v2(
            self._mode,
            attachment_ids=self._attachment_ids,
        )
        self._source_units = {
            str(item["source_unit_id"]): dict(item) for item in assembled.source_units
        }

    @property
    def visible_field_names(self) -> tuple[str, str]:
        return ("lead_public_text", "frames")

    @property
    def source_observed(self) -> bool:
        return bool(self._frames)

    @property
    def visible_text(self) -> str:
        return self._visible_text

    @property
    def frame_records(self) -> tuple[dict[str, Any], ...]:
        return tuple(dict(frame) for frame in self._frames)

    def allows_node(self, operation: str, node_name: str) -> bool:
        return operation == "evidence_turn" and node_name == "evidence_turn"

    def begin(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
    ) -> tuple[str, ...]:
        self._require_node(operation, node_name, field_name)
        self._started = True
        return ()

    def project_event(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        delta: str,
    ) -> tuple[tuple[str, str], ...]:
        self._require_node(operation, node_name, field_name)
        if not isinstance(delta, str) or not delta:
            raise GraphContractError("EVIDENCE_V2_FRAME_EVENT_INVALID")
        if field_name == "lead_public_text":
            return self._project_leading_text(delta)
        try:
            event = json.loads(delta)
        except (TypeError, ValueError) as error:
            raise GraphContractError("EVIDENCE_V2_FRAME_EVENT_INVALID") from error
        if not isinstance(event, dict):
            raise GraphContractError("EVIDENCE_V2_FRAME_EVENT_INVALID")
        kind = event.get("kind")
        sequence = event.get("frame_sequence")
        if (
            kind not in {"frame_start", "public_text_delta", "frame_end"}
            or isinstance(sequence, bool)
            or not isinstance(sequence, int)
            or sequence < 1
        ):
            raise GraphContractError("EVIDENCE_V2_FRAME_EVENT_INVALID")
        if kind == "frame_start":
            leading_end: tuple[tuple[str, str], ...] = ()
            if self._current is None:
                raise GraphContractError("EVIDENCE_V2_LEAD_PUBLIC_TEXT_MISSING")
            if self._current["frame_sequence"] == 1 and not self._current["ended"]:
                self._current["ended"] = True
                leading_end = (("frame.1.end", "{}"),)
            return leading_end + self._start_frame(sequence, event.get("header"))
        if self._current is None or self._current["frame_sequence"] != sequence:
            raise GraphContractError("EVIDENCE_V2_FRAME_ORDER_INVALID")
        if kind == "public_text_delta":
            text = event.get("delta")
            if not isinstance(text, str) or not text:
                raise GraphContractError("EVIDENCE_V2_PUBLIC_DELTA_INVALID")
            self._current["public_text"] += text
            self._visible_text = "\n\n".join(
                str(frame["public_text"])
                for frame in self._frames
                if not frame["internal"]
            )
            return ((f"frame.{sequence}.public_text", text),)
        if self._current["ended"]:
            raise GraphContractError("EVIDENCE_V2_FRAME_DUPLICATED")
        self._current["ended"] = True
        return ((f"frame.{sequence}.end", "{}"),)

    def _project_leading_text(
        self,
        delta: str,
    ) -> tuple[tuple[str, str], ...]:
        leading_start: tuple[tuple[str, str], ...] = ()
        if self._current is None:
            if self._leading_header is None:
                raise GraphContractError("EVIDENCE_V2_LEADING_FRAME_UNCONFIGURED")
            leading_start = self._start_frame(
                1,
                self._leading_header.model_dump(
                    mode="json",
                    exclude_none=True,
                    exclude_defaults=True,
                ),
            )
        if (
            self._current is None
            or self._current["frame_sequence"] != 1
            or self._current["ended"]
        ):
            raise GraphContractError("EVIDENCE_V2_LEAD_PUBLIC_TEXT_INVALID")
        self._current["public_text"] += delta
        self._visible_text = "\n\n".join(
            str(frame["public_text"])
            for frame in self._frames
            if not frame["internal"]
        )
        return leading_start + (("frame.1.public_text", delta),)

    def accept(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        delta: str,
    ) -> tuple[str, ...]:
        del operation, node_name, field_name, delta
        raise GraphContractError("EVIDENCE_V2_FRAME_POLICY_REQUIRES_PROJECTOR")

    def finalize(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        final_text: str,
        allow_canonical_fallback: bool = False,
    ) -> tuple[str, ...]:
        del allow_canonical_fallback
        self._require_node(operation, node_name, field_name)
        # The final object is authoritative for completion.  A missing model
        # frame_end, optional frame, or empty public field must not turn a
        # usable generation into a failed turn.  Close any projected frames
        # and bind the terminal text without re-validating model formatting.
        if not self._started:
            self._started = True
        for frame in self._frames:
            frame["ended"] = True
        if self._current is not None:
            self._current["ended"] = True
        if isinstance(final_text, str):
            self._visible_text = final_text
        self._finalized = True
        return ()

    def _start_frame(
        self,
        sequence: int,
        raw_header: Any,
    ) -> tuple[tuple[str, str], ...]:
        if self._current is not None and not self._current["ended"]:
            raise GraphContractError("EVIDENCE_V2_FRAME_ORDER_INVALID")
        if sequence != len(self._frames) + 1:
            raise GraphContractError("EVIDENCE_V2_FRAME_SEQUENCE_INVALID")
        try:
            header = validate_evidence_frame_header_v2(raw_header)
        except ValueError as error:
            LOGGER.warning(
                "evidence v2 frame header rejected: sequence=%s raw_header=%s validation_error=%s",
                sequence,
                json.dumps(
                    raw_header,
                    ensure_ascii=False,
                    separators=(",", ":"),
                    default=str,
                )[:8_000],
                str(error),
            )
            raise GraphContractError("EVIDENCE_V2_FRAME_HEADER_INVALID") from error
        if header.frame_sequence != sequence:
            raise GraphContractError("EVIDENCE_V2_FRAME_SEQUENCE_INVALID")
        header = bind_assessment_observation_slots(
            header,
            self._observation_evidence,
        )
        header = bind_room_readiness_fact_ids(header, self._fact_ids)
        self._validate_header_scope(header, sequence)
        frame = {
            "frame_sequence": sequence,
            "frame_type": header.frame_type,
            "header": header.model_dump(
                mode="json",
                exclude_none=True,
                exclude_defaults=True,
            ),
            "public_text": "",
            "internal": False,
            "ended": False,
        }
        self._frames.append(frame)
        self._current = frame
        return (
            (
                f"frame.{sequence}.header",
                json.dumps(frame["header"], ensure_ascii=False, separators=(",", ":")),
            ),
        )

    def _validate_header_scope(
        self,
        header: EvidenceFrameHeaderV2,
        sequence: int,
    ) -> None:
        frame_type = header.frame_type
        # Frame selection and semantic completeness are model-owned.  Only
        # immutable server authority (attachment/source/fact IDs) remains a
        # rejection boundary.
        if frame_type == "MATERIAL_RECEIPT":
            if header.evidence_ids and tuple(header.evidence_ids) != self._attachment_ids:
                raise GraphContractError("EVIDENCE_V2_MATERIAL_RECEIPT_SCOPE_INVALID")
            if any(fact_id not in self._fact_ids for fact_id in header.focus_fact_ids):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        elif frame_type == "OPENING_ORIENTATION":
            if any(fact_id not in self._fact_ids for fact_id in header.focus_fact_ids):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        elif frame_type == "EVIDENCE_OBSERVATION":
            self._validate_observation(header)
        elif frame_type == "EVIDENCE_ASSESSMENT":
            self._validate_assessment(header)
        elif frame_type == "EVIDENCE_REQUEST":
            self._validate_request(header)
        elif frame_type == "ROOM_READINESS" and any(
            fact_id not in self._fact_ids for fact_id in header.remaining_core_fact_ids
        ):
            raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")

    def _validate_observation(self, header: EvidenceFrameHeaderV2) -> None:
        slot = str(header.observation_slot or "")
        source_id = str(header.source_unit_id or "")
        if not source_id:
            return
        source = self._source_units.get(source_id)
        if source is None or source.get("evidence_id") not in self._attachment_ids:
            raise GraphContractError("EVIDENCE_V2_SOURCE_UNIT_OUT_OF_SCOPE")
        if any(
            binding.fact_id is not None and binding.fact_id not in self._fact_ids
            for binding in header.fact_bindings
        ):
            raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        if any(fact_id not in self._fact_ids for fact_id in header.candidate_fact_ids):
            raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        if slot:
            self._observation_evidence.append((slot, str(source["evidence_id"])))

    def _validate_assessment(self, header: EvidenceFrameHeaderV2) -> None:
        evidence_id = str(header.evidence_id or "")
        if not evidence_id:
            return
        if evidence_id not in self._attachment_ids:
            raise GraphContractError("EVIDENCE_V2_ASSESSMENT_OUT_OF_SCOPE")

    def _validate_request(self, header: EvidenceFrameHeaderV2) -> None:
        if any(fact_id not in self._fact_ids for fact_id in header.target_fact_ids):
            raise GraphContractError("EVIDENCE_V2_REQUEST_FACT_OUT_OF_SCOPE")

    @staticmethod
    def _require_node(operation: str, node_name: str, field_name: str) -> None:
        if (
            operation != "evidence_turn"
            or node_name != "evidence_turn"
            or field_name not in {"lead_public_text", "frames"}
        ):
            raise GraphContractError("EVIDENCE_V2_FRAME_FIELD_INVALID")


__all__ = ["EvidenceV2PublicOutputPolicy", "bind_assessment_observation_slots"]
