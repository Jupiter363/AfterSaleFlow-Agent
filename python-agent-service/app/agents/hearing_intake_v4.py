"""Pure materializers for Hearing V5 public frames and V4 formal authorities."""

from __future__ import annotations

import hashlib
from typing import Any, Iterable

from app.contracts.v1.codec import canonical_sha256
from app.llm import AgentOutputSchemaError
from app.schemas.case_fact_matrix import CaseFactMatrixV2
from app.schemas.hearing_flow import (
    HearingAnswerBundleV4,
    HearingFormalQuestionV4,
    HearingIntakeQuestionsLlmOutputV5,
    HearingIntakeQuestionsRequestV4,
    HearingIntakeQuestionsResultV5,
    HearingIntakeSynthesisLlmOutputV5,
    HearingIntakeSynthesisRequestV4,
    HearingIntakeSynthesisResultV5,
    HearingIssueStateSetV4,
    HearingIssueStateV4,
    HearingIssueTransitionSetV4,
    HearingPublicFrameV5,
    HearingQuestionSetV4,
    content_hash,
)


_SYNTHESIS_DIAGNOSTIC_CODES = {
    "every formal issue must be rebound exactly once in catalog order": (
        "HEARING_SYNTHESIS_ISSUE_CATALOG_ORDER"
    ),
    "new issue slots must be a continuous prefix": (
        "HEARING_SYNTHESIS_NEW_ISSUE_SLOT_PREFIX"
    ),
    "new issue slots must be unique": "HEARING_SYNTHESIS_NEW_ISSUE_SLOT_UNIQUE",
    "synthesis public frames are not canonical": (
        "HEARING_SYNTHESIS_PUBLIC_FRAMES_CANONICAL"
    ),
    "new issue requires_resolution must follow its model alignment": (
        "HEARING_SYNTHESIS_RESOLUTION_ALIGNMENT"
    ),
    "matrix effect issue binding is invalid": (
        "HEARING_SYNTHESIS_MATRIX_ISSUE_AUTHORITY"
    ),
    "claim effect role authority is invalid": (
        "HEARING_SYNTHESIS_CLAIM_ROLE_AUTHORITY"
    ),
    "existing fact effects must be unique": (
        "HEARING_SYNTHESIS_EXISTING_FACT_UNIQUE"
    ),
    "existing fact effect references unknown fact": (
        "HEARING_SYNTHESIS_EXISTING_FACT_AUTHORITY"
    ),
    "new fact slots must be a continuous prefix": (
        "HEARING_SYNTHESIS_NEW_FACT_SLOT_PREFIX"
    ),
    "new fact identity collides with M1": "HEARING_SYNTHESIS_NEW_FACT_COLLISION",
    "fact relationship is duplicated or reflexive": (
        "HEARING_SYNTHESIS_RELATIONSHIP_IDENTITY"
    ),
    "matrix summary fact refs must be unique": (
        "HEARING_SYNTHESIS_SUMMARY_FACT_UNIQUE"
    ),
    "new issue fact reference is not authorized": (
        "HEARING_SYNTHESIS_NEW_ISSUE_FACT_AUTHORITY"
    ),
    "relationship fact reference is not authorized": (
        "HEARING_SYNTHESIS_RELATIONSHIP_FACT_AUTHORITY"
    ),
    "matrix summary fact reference is not authorized": (
        "HEARING_SYNTHESIS_SUMMARY_FACT_AUTHORITY"
    ),
    "answer authority must be USER then MERCHANT": (
        "HEARING_SYNTHESIS_ANSWER_ROLE_ORDER"
    ),
    "answer bundle hash is invalid": "HEARING_SYNTHESIS_ANSWER_BUNDLE_HASH",
    "question set hash is invalid": "HEARING_SYNTHESIS_QUESTION_SET_HASH",
    "formal issue catalog hash is invalid": (
        "HEARING_SYNTHESIS_ISSUE_CATALOG_HASH"
    ),
    "question set slot order is invalid": "HEARING_SYNTHESIS_QUESTION_SLOT_ORDER",
    "issue has no unique current answer unit": (
        "HEARING_SYNTHESIS_CURRENT_ANSWER_CARDINALITY"
    ),
    "new issue source is not a current answer": (
        "HEARING_SYNTHESIS_NEW_ISSUE_ANSWER_AUTHORITY"
    ),
    "unilateral issue source role is invalid": (
        "HEARING_SYNTHESIS_UNILATERAL_ROLE_AUTHORITY"
    ),
    "unilateral issue cannot infer a counterparty": (
        "HEARING_SYNTHESIS_UNILATERAL_COUNTERPARTY_INFERENCE"
    ),
    "shared issue requires both current answers": (
        "HEARING_SYNTHESIS_SHARED_ANSWER_AUTHORITY"
    ),
    "effect uses another role's answer bundle": (
        "HEARING_SYNTHESIS_EFFECT_BUNDLE_AUTHORITY"
    ),
    "effect answer units are invalid": "HEARING_SYNTHESIS_EFFECT_UNIT_AUTHORITY",
    "fact update crosses party authority": "HEARING_SYNTHESIS_FACT_ROLE_AUTHORITY",
    "fact update answer source is invalid": (
        "HEARING_SYNTHESIS_FACT_ANSWER_AUTHORITY"
    ),
    "source reference budget is invalid": "HEARING_SYNTHESIS_SOURCE_REF_BUDGET",
}


def _diagnostic_code(node_name: str, message: str) -> str | None:
    if node_name != "hearing_intake_synthesis":
        return None
    if message.endswith("issue binding does not reference its current answer"):
        return "HEARING_SYNTHESIS_CURRENT_ANSWER_AUTHORITY"
    if message.startswith("unknown fact reference:"):
        return "HEARING_SYNTHESIS_FACT_REFERENCE_AUTHORITY"
    return _SYNTHESIS_DIAGNOSTIC_CODES.get(message)


def materialize_hearing_questions_v5(
    request: HearingIntakeQuestionsRequestV4,
    output: HearingIntakeQuestionsLlmOutputV5,
) -> HearingIntakeQuestionsResultV5:
    """Bind a QUESTION model document to server-owned slots and M1 authority."""

    _assert_matrix_integrity(request.case_fact_matrix, request.case_id)
    count = len(output.question_bindings)
    if len(output.frames) != count:
        _fail("hearing_intake_questions", "public frames and bindings must align")
    if count > len(request.question_slots):
        _fail("hearing_intake_questions", "model used more question slots than authorized")

    expected_slots = [slot.question_slot_id for slot in request.question_slots[:count]]
    frame_slots = [item.header.question_slot_id for item in output.frames]
    binding_slots = [item.question_slot_id for item in output.question_bindings]
    expected_sequences = list(range(2, count + 2))
    if (
        frame_slots != expected_slots
        or binding_slots != expected_slots
        or [item.header.frame_sequence for item in output.frames] != expected_sequences
    ):
        _fail("hearing_intake_questions", "question slots must be one continuous prefix")

    known_fact_ids = {row.fact_id for row in request.case_fact_matrix.fact_rows}
    question_set_id = _stable_id(
        "HEARING_QUESTION_SET",
        request.case_id,
        request.workflow_id,
        str(request.stage_sequence),
        request.prelude_authority_hash,
        request.case_fact_matrix.matrix_id,
        request.case_fact_matrix.content_hash,
    )
    questions: list[HearingFormalQuestionV4] = []
    for index, (frame, binding) in enumerate(
        zip(output.frames, output.question_bindings, strict=True)
    ):
        fact_ids = list(frame.header.fact_ids)
        if (
            len(fact_ids) != len(set(fact_ids))
            or set(fact_ids) - known_fact_ids
            or fact_ids != list(binding.issue_baseline.source_fact_ids)
        ):
            _fail("hearing_intake_questions", "question fact authority is invalid")
        for position in (
            binding.issue_baseline.effective_party_positions.USER,
            binding.issue_baseline.effective_party_positions.MERCHANT,
        ):
            if position is not None and position.position_source != "M1":
                _fail("hearing_intake_questions", "baseline positions must come from M1")
        slot = expected_slots[index]
        question_id = _stable_id("HEARING_QUESTION", question_set_id, slot)
        issue_id = _stable_id("HEARING_ISSUE", question_set_id, slot)
        issue_state_hash = canonical_sha256(
            {
                "schema_version": "hearing_issue_baseline_state.v4",
                "issue_id": issue_id,
                "issue_version": 1,
                "question_id": question_id,
                "question_slot_id": slot,
                "issue_baseline": binding.issue_baseline.model_dump(mode="json"),
            }
        )
        questions.append(
            HearingFormalQuestionV4(
                question_slot_id=slot,
                question_id=question_id,
                issue_id=issue_id,
                issue_state_hash=issue_state_hash,
                fact_ids=fact_ids,
                question_text=frame.public_text,
                issue_baseline=binding.issue_baseline,
                party_prompts=binding.party_prompts,
            )
        )

    formal_issue_catalog_hash = _formal_issue_catalog_hash(questions)
    question_set_payload: dict[str, Any] = {
        "schema_version": "hearing_question_set.v4",
        "question_set_id": question_set_id,
        "question_set_hash": "0" * 64,
        "formal_issue_catalog_hash": formal_issue_catalog_hash,
        "case_id": request.case_id,
        "source_matrix_id": request.case_fact_matrix.matrix_id,
        "source_matrix_version": request.case_fact_matrix.matrix_version,
        "source_matrix_hash": request.case_fact_matrix.content_hash,
        "prelude_authority_hash": request.prelude_authority_hash,
        "questions": [question.model_dump(mode="json") for question in questions],
    }
    question_set_payload["question_set_hash"] = content_hash(
        question_set_payload, hash_field="question_set_hash"
    )
    question_set = HearingQuestionSetV4.model_validate(question_set_payload)

    frames = [
        _public_frame(
            sequence=1,
            frame_type="HEARING_INTAKE_QUESTION_LEAD",
            authority_ref=question_set.question_set_id,
            text=output.lead_public_text,
        )
    ]
    frames.extend(
        _public_frame(
            sequence=index + 2,
            frame_type="SHARED_ISSUE_QUESTION",
            authority_ref=question.question_id,
            text=frame.public_text,
        )
        for index, (question, frame) in enumerate(
            zip(question_set.questions, output.frames, strict=True)
        )
    )
    return HearingIntakeQuestionsResultV5(
        case_id=request.case_id,
        workflow_id=request.workflow_id,
        stage_sequence=request.stage_sequence,
        question_set=question_set,
        public_frames=frames,
        lead_public_text=output.lead_public_text,
    )


def materialize_hearing_synthesis_v5(
    request: HearingIntakeSynthesisRequestV4,
    output: HearingIntakeSynthesisLlmOutputV5,
) -> HearingIntakeSynthesisResultV5:
    """Materialize canonical issue transitions, M2 and the final issue state set."""

    _assert_matrix_integrity(request.case_fact_matrix, request.case_id)
    _assert_question_set_integrity(request.question_set)
    _answer_authority(request)
    question_by_issue = {
        question.issue_id: question for question in request.question_set.questions
    }
    old_issue_ids = list(question_by_issue)
    if [item.issue_id for item in output.issue_rebindings] != old_issue_ids:
        _fail(
            "hearing_intake_synthesis",
            "every formal issue must be rebound exactly once in catalog order",
        )

    new_slots = [item.new_issue_slot_id for item in output.new_issue_proposals]
    if new_slots != request.new_issue_slots[: len(new_slots)]:
        _fail("hearing_intake_synthesis", "new issue slots must be a continuous prefix")
    if len(new_slots) != len(set(new_slots)):
        _fail("hearing_intake_synthesis", "new issue slots must be unique")

    expected_frame_refs = [*old_issue_ids, *new_slots]
    expected_frame_types = [
        *("REBIND_ISSUE_SYNTHESIS" for _ in old_issue_ids),
        *("NEW_ISSUE_SYNTHESIS" for _ in new_slots),
    ]
    if (
        [item.header.issue_ref for item in output.frames] != expected_frame_refs
        or [item.header.frame_type for item in output.frames] != expected_frame_types
        or [item.header.frame_sequence for item in output.frames]
        != list(range(2, len(output.frames) + 2))
    ):
        _fail("hearing_intake_synthesis", "synthesis public frames are not canonical")

    transition_set_id = _stable_id(
        "HEARING_ISSUE_TRANSITION_SET",
        request.case_id,
        request.workflow_id,
        str(request.stage_sequence),
        request.question_set.question_set_id,
        request.question_set.question_set_hash,
        *(bundle.answer_bundle_id for bundle in request.party_answer_bundles),
        *(bundle.answer_bundle_hash for bundle in request.party_answer_bundles),
    )
    issue_states: list[HearingIssueStateV4] = []
    for rebinding in output.issue_rebindings:
        question = question_by_issue[rebinding.issue_id]
        source_bundles: list[str] = []
        source_units: list[str] = []
        effective_party_positions: dict[str, Any] = {}
        for role in ("USER", "MERCHANT"):
            binding = getattr(rebinding.party_bindings, role)
            effective_party_positions[role] = (
                None
                if binding.current_position is None
                else {
                    "position_source": "CURRENT_ANSWER",
                    "position_summary": binding.current_position.position_summary,
                }
            )
            source_bundles.append(binding.answer_bundle_id)
            source_units.append(binding.answer_unit_id)
        issue_states.append(
            _issue_state(
                issue_id=question.issue_id,
                issue_version=question.issue_version + 1,
                issue_kind="REBIND",
                issue_statement=question.issue_baseline.issue_statement,
                positions=effective_party_positions,
                alignment=rebinding.current_alignment.model_dump(mode="json"),
                source_question_id=question.question_id,
                source_bundle_ids=list(dict.fromkeys(source_bundles)),
                source_unit_ids=list(dict.fromkeys(source_units)),
            )
        )

    new_issue_id_by_slot: dict[str, str] = {}
    for proposal in output.new_issue_proposals:
        expected_requires_resolution = proposal.current_alignment.status != "AGREED"
        if proposal.requires_resolution != expected_requires_resolution:
            _fail(
                "hearing_intake_synthesis",
                "new issue requires_resolution must follow its model alignment",
            )
        issue_id = _stable_id(
            "HEARING_ISSUE",
            transition_set_id,
            proposal.new_issue_slot_id,
        )
        new_issue_id_by_slot[proposal.new_issue_slot_id] = issue_id
        issue_states.append(
            _issue_state(
                issue_id=issue_id,
                issue_version=1,
                issue_kind=proposal.issue_kind,
                issue_statement=proposal.issue_statement,
                positions=proposal.effective_party_positions.model_dump(mode="json"),
                alignment=proposal.current_alignment.model_dump(mode="json"),
                source_question_id=None,
                source_bundle_ids=list(proposal.source_answer_bundle_ids),
                source_unit_ids=list(proposal.source_answer_unit_ids),
            )
        )

    transition_payload: dict[str, Any] = {
        "schema_version": "hearing_issue_transition_set.v4",
        "transition_set_id": transition_set_id,
        "transition_hash": "0" * 64,
        "case_id": request.case_id,
        "question_set_id": request.question_set.question_set_id,
        "question_set_hash": request.question_set.question_set_hash,
        "answer_bundle_ids": tuple(
            bundle.answer_bundle_id for bundle in request.party_answer_bundles
        ),
        "answer_bundle_hashes": tuple(
            bundle.answer_bundle_hash for bundle in request.party_answer_bundles
        ),
        "issues": [issue.model_dump(mode="json") for issue in issue_states],
    }
    transition_payload["transition_hash"] = content_hash(
        transition_payload, hash_field="transition_hash"
    )
    transition_set = HearingIssueTransitionSetV4.model_validate(transition_payload)

    matrix = materialize_hearing_case_matrix_v4(
        request,
        output,
        transition_set,
        new_issue_id_by_slot=new_issue_id_by_slot,
    )
    issue_state_payload: dict[str, Any] = {
        "schema_version": "hearing_issue_state_set.v4",
        "issue_state_set_id": _stable_id(
            "HEARING_ISSUE_STATE_SET",
            transition_set.transition_set_id,
            transition_set.transition_hash,
            matrix.matrix_id,
            matrix.content_hash,
        ),
        "content_hash": "0" * 64,
        "case_id": request.case_id,
        "transition_set_id": transition_set.transition_set_id,
        "transition_hash": transition_set.transition_hash,
        "question_set_id": request.question_set.question_set_id,
        "question_set_hash": request.question_set.question_set_hash,
        "answer_bundle_ids": transition_set.answer_bundle_ids,
        "answer_bundle_hashes": transition_set.answer_bundle_hashes,
        "matrix_id": matrix.matrix_id,
        "matrix_version": matrix.matrix_version,
        "matrix_hash": matrix.content_hash,
        "issues": [issue.model_dump(mode="json") for issue in issue_states],
    }
    issue_state_payload["content_hash"] = content_hash(
        issue_state_payload, hash_field="content_hash"
    )
    issue_state_set = HearingIssueStateSetV4.model_validate(issue_state_payload)

    frames = [
        _public_frame(
            sequence=1,
            frame_type="HEARING_INTAKE_SYNTHESIS_LEAD",
            authority_ref=transition_set.transition_set_id,
            text=output.lead_public_text,
        )
    ]
    formal_frame_refs = [*old_issue_ids, *(new_issue_id_by_slot[slot] for slot in new_slots)]
    frames.extend(
        _public_frame(
            sequence=index + 2,
            frame_type=frame.header.frame_type,
            authority_ref=formal_frame_refs[index],
            text=frame.public_text,
        )
        for index, frame in enumerate(output.frames)
    )
    return HearingIntakeSynthesisResultV5(
        case_id=request.case_id,
        workflow_id=request.workflow_id,
        stage_sequence=request.stage_sequence,
        public_frames=frames,
        issue_transition_set=transition_set,
        case_fact_matrix=matrix,
        issue_state_set=issue_state_set,
        lead_public_text=output.lead_public_text,
    )


def materialize_hearing_case_matrix_v4(
    request: HearingIntakeSynthesisRequestV4,
    output: HearingIntakeSynthesisLlmOutputV5,
    transition_set: HearingIssueTransitionSetV4,
    *,
    new_issue_id_by_slot: dict[str, str],
) -> CaseFactMatrixV2:
    """Apply issue-bound effects to M1 without modifying old fact identity."""

    previous = request.case_fact_matrix
    old_issue_ids = {question.issue_id for question in request.question_set.questions}
    allowed_issue_refs = old_issue_ids | set(new_issue_id_by_slot)
    effects = output.matrix_effects
    for collection in (
        effects.claim_effects,
        effects.existing_fact_effects,
        effects.new_fact_effects,
        effects.relationship_effects,
    ):
        for effect in collection:
            refs = list(effect.source_issue_refs)
            if not refs or len(refs) != len(set(refs)) or set(refs) - allowed_issue_refs:
                _fail("hearing_intake_synthesis", "matrix effect issue binding is invalid")

    claims = previous.claims.model_dump(mode="json")
    initiator_role = previous.party_map.initiator_role
    respondent_role = previous.party_map.respondent_role
    seen_claim_roles: set[str] = set()
    for effect in effects.claim_effects:
        expected_role = (
            initiator_role
            if effect.effect_type == "INITIATOR_CLAIM_REPLACE"
            else respondent_role
        )
        if effect.subject_role != expected_role or expected_role in seen_claim_roles:
            _fail("hearing_intake_synthesis", "claim effect role authority is invalid")
        seen_claim_roles.add(expected_role)
        source_refs = _bounded_unique(
            [effect.answer_bundle_id, *effect.answer_unit_ids], maximum=50
        )
        replacement = effect.replacement.model_dump(mode="json")
        if effect.effect_type == "INITIATOR_CLAIM_REPLACE":
            claims["initiator_claim"] = {
                "initiator_role": initiator_role,
                **replacement,
                "source_refs": source_refs,
            }
        else:
            claims["respondent_direct"] = {
                "respondent_role": respondent_role,
                **replacement,
                "source_type": "RESPONDENT_DIRECT_HEARING",
                "source_refs": source_refs,
            }
    claims["claim_conflict"] = output.matrix_summary.core_conflict

    rows_by_id = {
        row.fact_id: row.model_dump(mode="json") for row in previous.fact_rows
    }
    ordered_fact_ids = [row.fact_id for row in previous.fact_rows]
    if len(effects.existing_fact_effects) != len(
        {effect.fact_id for effect in effects.existing_fact_effects}
    ):
        _fail("hearing_intake_synthesis", "existing fact effects must be unique")
    for effect in effects.existing_fact_effects:
        row = rows_by_id.get(effect.fact_id)
        if row is None:
            _fail("hearing_intake_synthesis", "existing fact effect references unknown fact")
        _apply_fact_updates(row, effect.party_updates.model_dump(mode="json"))
        _apply_alignment(row, effect.alignment.model_dump(mode="json"))

    new_fact_slots = [effect.new_fact_slot_id for effect in effects.new_fact_effects]
    if new_fact_slots != request.new_fact_slots[: len(new_fact_slots)]:
        _fail("hearing_intake_synthesis", "new fact slots must be a continuous prefix")
    new_fact_id_by_slot: dict[str, str] = {}
    for effect in effects.new_fact_effects:
        fact_id = _stable_id(
            "FACT_HEARING",
            request.case_id,
            request.workflow_id,
            str(request.stage_sequence),
            effect.new_fact_slot_id,
        )
        if fact_id in rows_by_id:
            _fail("hearing_intake_synthesis", "new fact identity collides with M1")
        new_fact_id_by_slot[effect.new_fact_slot_id] = fact_id
        updates = effect.party_updates.model_dump(mode="json")
        origin_refs = [
            source
            for role in ("USER", "MERCHANT")
            if updates[role] is not None
            for source in [
                updates[role]["answer_bundle_id"],
                *updates[role]["answer_unit_ids"],
            ]
        ]
        row: dict[str, Any] = {
            "fact_id": fact_id,
            "category": str(effect.category),
            "fact_target": effect.fact_target,
            "materiality": str(effect.materiality),
            "origin": {
                "introduced_stage": "HEARING_CLARIFICATION",
                "source_refs": _bounded_unique(origin_refs, maximum=50),
            },
            "positions": {
                "USER": _not_addressed_position(),
                "MERCHANT": _not_addressed_position(),
            },
            "party_alignment": effect.alignment.model_dump(mode="json"),
            "requires_resolution": effect.alignment.status != "AGREED",
            "truth_status": "NOT_EVALUATED",
            "evidence_coverage_status": "NOT_COVERED_BY_FROZEN_DOSSIER",
        }
        _apply_fact_updates(row, updates)
        rows_by_id[fact_id] = row
        ordered_fact_ids.append(fact_id)

    def resolve_fact_ref(value: str, *, failure_message: str) -> str:
        if value in rows_by_id:
            return value
        resolved = new_fact_id_by_slot.get(value)
        if resolved is None:
            _fail("hearing_intake_synthesis", failure_message)
        return resolved

    for proposal in output.new_issue_proposals:
        for fact_ref in proposal.fact_refs:
            resolve_fact_ref(
                fact_ref,
                failure_message="new issue fact reference is not authorized",
            )

    relationships = [
        relationship.model_dump(mode="json")
        for relationship in previous.fact_relationships
    ]
    relationship_keys = {
        (
            item["relationship_type"],
            item["from_fact_id"],
            item["to_fact_id"],
        )
        for item in relationships
    }
    for effect in effects.relationship_effects:
        from_fact_id = resolve_fact_ref(
            effect.from_fact_ref,
            failure_message="relationship fact reference is not authorized",
        )
        to_fact_id = resolve_fact_ref(
            effect.to_fact_ref,
            failure_message="relationship fact reference is not authorized",
        )
        key = (effect.relationship_type, from_fact_id, to_fact_id)
        if from_fact_id == to_fact_id or key in relationship_keys:
            _fail("hearing_intake_synthesis", "fact relationship is duplicated or reflexive")
        relationship_keys.add(key)
        relationships.append(
            {
                "relationship_type": effect.relationship_type,
                "from_fact_id": from_fact_id,
                "to_fact_id": to_fact_id,
                "source_refs": _bounded_unique(
                    [
                        new_issue_id_by_slot.get(reference, reference)
                        for reference in effect.source_issue_refs
                    ],
                    maximum=20,
                ),
            }
        )

    summary_fact_ids = [
        resolve_fact_ref(
            reference,
            failure_message="matrix summary fact reference is not authorized",
        )
        for reference in output.matrix_summary.summary_fact_refs
    ]
    if len(summary_fact_ids) != len(set(summary_fact_ids)):
        _fail("hearing_intake_synthesis", "matrix summary fact refs must be unique")

    effect_hash = canonical_sha256(output.matrix_effects.model_dump(mode="json"))
    source_context_hash = canonical_sha256(
        {
            "schema_version": "hearing_m2_source_context.v4",
            "parent_matrix_hash": previous.content_hash,
            "question_set_hash": request.question_set.question_set_hash,
            "answer_bundle_hashes": [
                bundle.answer_bundle_hash for bundle in request.party_answer_bundles
            ],
            "transition_hash": transition_set.transition_hash,
        }
    )
    source_refs = _bounded_unique(
        [
            *previous.source_refs,
            *request.source_refs,
            request.question_set.question_set_id,
            *(bundle.answer_bundle_id for bundle in request.party_answer_bundles),
            *(
                message_id
                for bundle in request.party_answer_bundles
                for message_id in bundle.source_message_ids
            ),
            transition_set.transition_set_id,
        ],
        maximum=256,
    )
    matrix_version = previous.matrix_version + 1
    rows = [rows_by_id[fact_id] for fact_id in ordered_fact_ids]
    matrix_payload: dict[str, Any] = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": request.case_id,
        "matrix_id": _stable_id(
            "CASE_MATRIX",
            request.case_id,
            str(matrix_version),
            transition_set.transition_hash,
            effect_hash,
        ),
        "matrix_version": matrix_version,
        "matrix_kind": "HEARING_CLARIFIED_FROZEN",
        "parent_ref": {
            "matrix_id": previous.matrix_id,
            "matrix_version": previous.matrix_version,
            "content_hash": previous.content_hash,
        },
        "content_hash": "0" * 64,
        "party_map": previous.party_map.model_dump(mode="json"),
        "source_refs": source_refs,
        "case_overview": {
            "neutral_summary": output.matrix_summary.summary_text,
            "core_conflict": output.matrix_summary.core_conflict,
            "summary_source_fact_ids": summary_fact_ids,
        },
        "claims": claims,
        "fact_rows": rows,
        "fact_relationships": relationships,
        "generation_ref": {
            "actor_role": "SYSTEM",
            "source_stage": "HEARING_CLARIFICATION",
            "latest_source_ref": transition_set.transition_set_id,
            "source_context_hash": source_context_hash,
        },
        "fact_indexes": _fact_indexes(rows),
    }
    matrix_payload["content_hash"] = content_hash(
        matrix_payload, hash_field="content_hash"
    )
    return CaseFactMatrixV2.model_validate(matrix_payload)


def _answer_authority(
    request: HearingIntakeSynthesisRequestV4,
) -> dict[str, HearingAnswerBundleV4]:
    bundles = {
        bundle.participant_role: bundle for bundle in request.party_answer_bundles
    }
    if list(bundles) != ["USER", "MERCHANT"]:
        _fail("hearing_intake_synthesis", "answer authority must be USER then MERCHANT")
    for bundle in request.party_answer_bundles:
        if bundle.answer_bundle_hash != content_hash(
            bundle, hash_field="answer_bundle_hash"
        ):
            _fail("hearing_intake_synthesis", "answer bundle hash is invalid")
    return bundles


def _assert_question_set_integrity(question_set: HearingQuestionSetV4) -> None:
    if question_set.question_set_hash != content_hash(
        question_set, hash_field="question_set_hash"
    ):
        _fail("hearing_intake_synthesis", "question set hash is invalid")
    if question_set.formal_issue_catalog_hash != _formal_issue_catalog_hash(
        list(question_set.questions)
    ):
        _fail("hearing_intake_synthesis", "formal issue catalog hash is invalid")
    slots = [question.question_slot_id for question in question_set.questions]
    expected = [f"QUESTION_SLOT_{index:02d}" for index in range(1, len(slots) + 1)]
    if slots != expected:
        _fail("hearing_intake_synthesis", "question set slot order is invalid")


def _formal_issue_catalog_hash(questions: list[HearingFormalQuestionV4]) -> str:
    return canonical_sha256(
        {
            "schema_version": "hearing_formal_issue_catalog.v4",
            "issues": [
                {
                    "question_slot_id": question.question_slot_id,
                    "question_id": question.question_id,
                    "issue_id": question.issue_id,
                    "issue_version": question.issue_version,
                    "issue_state_hash": question.issue_state_hash,
                    "issue_baseline": question.issue_baseline.model_dump(mode="json"),
                }
                for question in questions
            ],
        }
    )


def _unit_for_issue(bundle: HearingAnswerBundleV4, issue_id: str) -> Any:
    matches = [unit for unit in bundle.answer_units if unit.issue_id == issue_id]
    if len(matches) != 1:
        _fail("hearing_intake_synthesis", "issue has no unique current answer unit")
    return matches[0]


def _apply_fact_updates(
    row: dict[str, Any],
    updates: dict[str, Any],
) -> None:
    for role in ("USER", "MERCHANT"):
        update = updates[role]
        if update is None:
            continue
        if update["stance"] == "NOT_ADDRESSED":
            row["positions"][role] = {
                "stance": update["stance"],
                "position_summary": update["position_summary"],
                "asserted_value": update.get("asserted_value"),
                "source_type": "NO_DIRECT_POSITION",
                "source_refs": [],
            }
            continue
        prior_refs = row["positions"][role].get("source_refs", [])
        row["positions"][role] = {
            "stance": update["stance"],
            "position_summary": update["position_summary"],
            "asserted_value": update.get("asserted_value"),
            "source_type": "DIRECT_PARTY_STATEMENT",
            "source_refs": _bounded_unique(
                [
                    *prior_refs,
                    update["answer_bundle_id"],
                    *update["answer_unit_ids"],
                ],
                maximum=50,
            ),
        }


def _apply_alignment(row: dict[str, Any], alignment: dict[str, Any]) -> None:
    row["party_alignment"] = alignment
    row["requires_resolution"] = alignment["status"] != "AGREED"


def _issue_state(
    *,
    issue_id: str,
    issue_version: int,
    issue_kind: str,
    issue_statement: str,
    positions: dict[str, Any],
    alignment: dict[str, Any],
    source_question_id: str | None,
    source_bundle_ids: list[str],
    source_unit_ids: list[str],
) -> HearingIssueStateV4:
    payload: dict[str, Any] = {
        "issue_id": issue_id,
        "issue_version": issue_version,
        "issue_state_hash": "0" * 64,
        "issue_kind": issue_kind,
        "issue_statement": issue_statement,
        "effective_party_positions": positions,
        "current_alignment": alignment,
        "requires_resolution": alignment["status"] != "AGREED",
        "source_question_id": source_question_id,
        "source_answer_bundle_ids": source_bundle_ids,
        "source_answer_unit_ids": source_unit_ids,
    }
    payload["issue_state_hash"] = content_hash(payload, hash_field="issue_state_hash")
    return HearingIssueStateV4.model_validate(payload)


def _public_frame(
    *, sequence: int, frame_type: str, authority_ref: str, text: str
) -> HearingPublicFrameV5:
    return HearingPublicFrameV5(
        frame_sequence=sequence,
        frame_type=frame_type,
        authority_ref=authority_ref,
        public_text=text,
        public_text_hash=hashlib.sha256(text.encode("utf-8")).hexdigest(),
    )


def _assert_matrix_integrity(matrix: CaseFactMatrixV2, case_id: str) -> None:
    if matrix.case_id != case_id or matrix.content_hash != content_hash(
        matrix, hash_field="content_hash"
    ):
        _fail("hearing_intake", "frozen M1 authority is invalid")


def _fact_indexes(rows: list[dict[str, Any]]) -> dict[str, list[str]]:
    by_status = {
        status: [
            row["fact_id"]
            for row in rows
            if row["party_alignment"]["status"] == status
        ]
        for status in (
            "NOT_COMPUTED",
            "AGREED",
            "PARTIALLY_AGREED",
            "CONTESTED",
            "ONE_SIDED",
            "UNRESOLVED",
        )
    }
    return {
        "not_computed_fact_ids": by_status["NOT_COMPUTED"],
        "agreed_fact_ids": by_status["AGREED"],
        "partially_agreed_fact_ids": by_status["PARTIALLY_AGREED"],
        "contested_fact_ids": by_status["CONTESTED"],
        "one_sided_fact_ids": by_status["ONE_SIDED"],
        "unresolved_fact_ids": by_status["UNRESOLVED"],
        "core_fact_ids": [row["fact_id"] for row in rows if row["materiality"] == "CORE"],
        "requires_resolution_fact_ids": [
            row["fact_id"] for row in rows if row["requires_resolution"] is True
        ],
    }


def _not_addressed_position() -> dict[str, Any]:
    return {
        "stance": "NOT_ADDRESSED",
        "position_summary": "该方尚未就此事实形成直接陈述。",
        "asserted_value": None,
        "source_type": "NO_DIRECT_POSITION",
        "source_refs": [],
    }


def _bounded_unique(values: Iterable[str], *, maximum: int) -> list[str]:
    result = list(dict.fromkeys(values))
    if not result or len(result) > maximum:
        _fail("hearing_intake_synthesis", "source reference budget is invalid")
    return result


def _stable_id(prefix: str, *parts: str) -> str:
    digest = hashlib.sha256("\u001f".join(parts).encode("utf-8")).hexdigest()[:24]
    return f"{prefix}_{digest}"


def _fail(node_name: str, message: str) -> None:
    raise AgentOutputSchemaError(
        node_name,
        message,
        diagnostic_code=_diagnostic_code(node_name, message),
    )


__all__ = [
    "materialize_hearing_case_matrix_v4",
    "materialize_hearing_questions_v5",
    "materialize_hearing_synthesis_v5",
]
