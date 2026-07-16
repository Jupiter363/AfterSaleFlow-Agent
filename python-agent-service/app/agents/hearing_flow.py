"""Independent governed operations for the explicit ``hearing_flow.v2`` chain."""

from __future__ import annotations

import hashlib
from concurrent.futures import ThreadPoolExecutor
from contextvars import copy_context
from typing import Any, Iterable

from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
from app.schemas import (
    CaseAlignmentStatus,
    FactEvidenceCoverageStatus,
    FactEvidenceMatrixV2,
    FactStance,
    HearingBatchEvidenceAssessment,
    HearingCaseFactMatrixDelta,
    HearingDisputePoint,
    HearingEvidenceRequest,
    HearingEvidenceRequestsLlmOutput,
    HearingEvidenceRequestsRequest,
    HearingEvidenceRequestsResult,
    HearingEvidenceFileAssessmentLlmOutput,
    HearingEvidenceSynthesisLlmOutput,
    HearingEvidenceSynthesisRequest,
    HearingEvidenceSynthesisResult,
    HearingIntakeQuestion,
    HearingIntakeQuestionsLlmOutput,
    HearingIntakeQuestionsRequest,
    HearingIntakeQuestionsResult,
    HearingIntakeSynthesisLlmOutput,
    HearingIntakeSynthesisRequest,
    HearingIntakeSynthesisResult,
    HearingIssueMapping,
    HearingJudgeV1Request,
    HearingJudgeV1Result,
    HearingJudgeV2Draft,
    HearingJudgeV2Request,
    HearingJudgeV2Result,
    HearingJuryFinding,
    HearingJuryReviewDraft,
    HearingJuryReviewLlmOutput,
    HearingJuryReviewRequest,
    HearingJuryReviewResult,
    JudgeV1Draft,
    content_hash,
)


class HearingFlowWorkflows:
    """Seven one-call operations; Java owns introductions and stage transitions."""

    def __init__(self, model_runner: Any | None) -> None:
        self._model_runner = model_runner

    def intake_questions(
        self, request: HearingIntakeQuestionsRequest
    ) -> HearingIntakeQuestionsResult:
        _assert_case_matrix_integrity(
            request.case_fact_matrix,
            expected_case_id=request.case_id,
            node_name="hearing_intake_questions",
        )
        output = self._invoke(
            "hearing_intake_questions",
            request,
            HearingIntakeQuestionsLlmOutput,
        )
        known = _case_fact_ids(request.case_fact_matrix)
        questions: list[HearingIntakeQuestion] = []
        seen: set[tuple[tuple[str, ...], str]] = set()
        for item in output.questions:
            fact_ids = sorted(_validated_fact_ids(item.fact_ids, known))
            key = (
                tuple(fact_ids),
                _normalized_issue_statement(item.issue_statement),
            )
            if key in seen:
                continue
            seen.add(key)
            issue_id = _stable_id(
                "HEARING_ISSUE",
                request.case_id,
                str(request.stage_sequence),
                *fact_ids,
                _normalized_issue_statement(item.issue_statement),
            )
            questions.append(
                HearingIntakeQuestion(
                    # The shared issue also acts as the legacy question identifier, so a
                    # Java v1 envelope that drops additive fields can still recover it.
                    question_id=issue_id,
                    issue_id=issue_id,
                    target_roles=["USER", "MERCHANT"],
                    fact_ids=fact_ids,
                    question_text=item.issue_statement,
                    issue_statement=item.issue_statement,
                    party_prompts=item.party_prompts,
                )
            )
            if len(questions) >= request.max_questions:
                break
        return HearingIntakeQuestionsResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            stage_sequence=request.stage_sequence,
            questions=questions,
            public_message=output.public_message,
        )

    def intake_synthesis(
        self, request: HearingIntakeSynthesisRequest
    ) -> HearingIntakeSynthesisResult:
        _assert_case_matrix_integrity(
            request.case_fact_matrix,
            expected_case_id=request.case_id,
            node_name="hearing_intake_synthesis",
        )
        issue_contexts = _intake_issue_contexts(request)
        party_statements = _party_statement_contexts(request)
        output = self._invoke_payload(
            "hearing_intake_synthesis",
            {
                "request": request.model_dump(mode="json"),
                "intake_issues": issue_contexts,
                "party_statements": party_statements,
            },
            HearingIntakeSynthesisLlmOutput,
        )
        issue_mappings = _resolved_issue_mappings(
            request,
            issue_contexts,
            party_statements,
            output.issue_mappings,
        )
        matrix = _merge_hearing_case_matrix(
            request,
            output.case_fact_matrix_delta,
        )
        points = _hearing_dispute_points(matrix)
        return HearingIntakeSynthesisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            stage_sequence=request.stage_sequence,
            case_fact_matrix=matrix,
            dispute_points=points,
            issue_mappings=issue_mappings,
            public_message=output.public_message,
        )

    def evidence_requests(
        self, request: HearingEvidenceRequestsRequest
    ) -> HearingEvidenceRequestsResult:
        _assert_case_matrix_integrity(
            request.case_fact_matrix,
            expected_case_id=request.case_id,
            node_name="hearing_evidence_requests",
        )
        _assert_evidence_matrix_compatible(
            request.evidence_dossier.fact_evidence_matrix,
            request.case_fact_matrix,
        )
        output = self._invoke(
            "hearing_evidence_requests",
            request,
            HearingEvidenceRequestsLlmOutput,
        )
        known_facts = _case_fact_ids(request.case_fact_matrix)
        requests: list[HearingEvidenceRequest] = []
        seen: set[tuple[tuple[str, ...], tuple[str, ...], str, str, bool]] = set()
        for item in output.requests:
            fact_ids = sorted(_validated_fact_ids(item.fact_ids, known_facts))
            target_roles = _canonical_roles(item.target_roles)
            key = (
                tuple(target_roles),
                tuple(fact_ids),
                item.requested_material.strip(),
                item.verification_goal.strip(),
                item.required,
            )
            if key in seen:
                continue
            seen.add(key)
            requests.append(
                HearingEvidenceRequest(
                    request_id=_stable_id(
                        "HEARING_EVIDENCE_REQUEST",
                        request.case_id,
                        str(request.stage_sequence),
                        *target_roles,
                        *fact_ids,
                        item.requested_material,
                        item.verification_goal,
                    ),
                    target_roles=target_roles,
                    fact_ids=fact_ids,
                    requested_material=item.requested_material,
                    verification_goal=item.verification_goal,
                    required=item.required,
                )
            )
        return HearingEvidenceRequestsResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            stage_sequence=request.stage_sequence,
            requests=requests,
            public_message=output.public_message,
        )

    def evidence_synthesis(
        self, request: HearingEvidenceSynthesisRequest
    ) -> HearingEvidenceSynthesisResult:
        _assert_case_matrix_integrity(
            request.case_fact_matrix,
            expected_case_id=request.case_id,
            node_name="hearing_evidence_synthesis",
        )
        assessments = self._assess_evidence_files(request)
        matrix = _merge_evidence_batch(request, assessments)
        output = self._invoke_payload(
            "hearing_evidence_synthesis",
            {
                "request": request.model_dump(mode="json"),
                "evidence_assessments": [item.model_dump(mode="json") for item in assessments],
                "merged_fact_evidence_matrix": matrix.model_dump(mode="json"),
            },
            HearingEvidenceSynthesisLlmOutput,
        )
        return HearingEvidenceSynthesisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            stage_sequence=request.stage_sequence,
            fact_evidence_matrix=matrix,
            evidence_summary=output.evidence_summary,
            evidence_gaps=output.evidence_gaps,
            public_message=output.public_message,
        )

    def _assess_evidence_files(
        self, request: HearingEvidenceSynthesisRequest
    ) -> list[HearingBatchEvidenceAssessment]:
        pending = [
            (batch, evidence) for batch in request.party_batches for evidence in batch.evidence
        ]
        if not pending:
            return []

        def assess(item: tuple[Any, Any]) -> HearingBatchEvidenceAssessment:
            batch, evidence = item
            output = self._invoke_payload(
                "hearing_evidence_file_assessment",
                {
                    "flow": {
                        "flow_schema_version": request.flow_schema_version,
                        "case_id": request.case_id,
                        "workflow_id": request.workflow_id,
                        "stage_code": request.stage_code,
                        "stage_sequence": request.stage_sequence,
                        "stage_deadline_at": request.stage_deadline_at,
                        "source_refs": request.source_refs,
                    },
                    "participant_role": batch.participant_role,
                    "batch_id": batch.batch_id,
                    "evidence_file": evidence.model_dump(mode="json"),
                    "requests": [value.model_dump(mode="json") for value in request.requests],
                    "case_fact_matrix": request.case_fact_matrix.model_dump(mode="json"),
                    "prior_fact_evidence_matrix": (
                        request.prior_fact_evidence_matrix.model_dump(mode="json")
                        if request.prior_fact_evidence_matrix is not None
                        else None
                    ),
                },
                HearingEvidenceFileAssessmentLlmOutput,
            )
            return HearingBatchEvidenceAssessment(
                evidence_id=evidence.evidence_id,
                fact_links=output.fact_links,
                summary=output.summary,
                requires_human_review=output.requires_human_review,
            )

        # The request schema bounds the batch at 100 files. One worker per file
        # preserves the V2 contract that all terminal-batch files are assessed
        # independently and in parallel before the single deterministic merge.
        with ThreadPoolExecutor(
            max_workers=len(pending),
            thread_name_prefix="hearing-evidence",
        ) as executor:
            futures = [executor.submit(copy_context().run, assess, item) for item in pending]
            return [future.result() for future in futures]

    def judge_v1(self, request: HearingJudgeV1Request) -> HearingJudgeV1Result:
        _validate_dossier_request(request, "hearing_judge_v1")
        output = self._invoke("hearing_judge_v1", request, JudgeV1Draft)
        proposal_id = _stable_id(
            "JUDGE_PROPOSAL",
            request.case_id,
            request.trial_dossier.content_hash,
            output.proposal_text,
        )
        payload = {
            "schema_version": "hearing_judge_v1.v1",
            "case_id": request.case_id,
            "workflow_id": request.workflow_id,
            "stage_sequence": request.stage_sequence,
            "trial_dossier_id": request.trial_dossier.trial_dossier_id,
            "trial_dossier_hash": request.trial_dossier.content_hash,
            "proposal_id": proposal_id,
            "proposal_hash": "0" * 64,
            **output.model_dump(mode="json"),
            "is_final_decision": False,
        }
        payload["proposal_hash"] = content_hash(payload, hash_field="proposal_hash")
        return HearingJudgeV1Result.model_validate(payload)

    def jury_review(self, request: HearingJuryReviewRequest) -> HearingJuryReviewResult:
        _validate_v1_binding(request)
        llm_output = self._invoke(
            "hearing_jury_review",
            request,
            HearingJuryReviewLlmOutput,
        )
        output = _normalize_jury_review(llm_output, request)
        review_id = _stable_id(
            "JURY_REVIEW",
            request.case_id,
            request.judge_v1.proposal_id,
            request.judge_v1.proposal_hash,
        )
        payload = {
            "schema_version": "hearing_jury_review.v1",
            "case_id": request.case_id,
            "workflow_id": request.workflow_id,
            "stage_sequence": request.stage_sequence,
            "trial_dossier_id": request.trial_dossier.trial_dossier_id,
            "trial_dossier_hash": request.trial_dossier.content_hash,
            "review_id": review_id,
            "review_hash": "0" * 64,
            "reviewed_proposal_id": request.judge_v1.proposal_id,
            "reviewed_proposal_hash": request.judge_v1.proposal_hash,
            **output.model_dump(mode="json"),
            "approval_performed": False,
            "execution_triggered": False,
            "is_final_decision": False,
        }
        payload["review_hash"] = content_hash(payload, hash_field="review_hash")
        return HearingJuryReviewResult.model_validate(payload)

    def judge_v2(self, request: HearingJudgeV2Request) -> HearingJudgeV2Result:
        _validate_v2_binding(request)
        output = self._invoke("hearing_judge_v2", request, HearingJudgeV2Draft)
        known_facts = _case_fact_ids(request.trial_dossier.case_fact_matrix)
        known_fact_evidence_links = {
            (link.fact_id, link.evidence_id)
            for link in request.trial_dossier.fact_evidence_matrix.links
        }
        assessed_evidence_ids: set[str] = set()
        for assessment in output.draft.evidence_assessment:
            fact_ids = _validated_fact_ids(assessment.fact_ids, known_facts)
            if assessment.evidence_id is None:
                continue
            unbound = {
                fact_id
                for fact_id in fact_ids
                if (fact_id, assessment.evidence_id) not in known_fact_evidence_links
            }
            if unbound:
                _schema_error(
                    "hearing_judge_v2",
                    "evidence assessment references facts not linked to its evidence: "
                    f"{sorted(unbound)}",
                )
            assessed_evidence_ids.add(assessment.evidence_id)
        for finding in output.draft.fact_findings:
            _validated_fact_ids([finding.fact_id], known_facts)
            unbound = {
                evidence_id
                for evidence_id in finding.evidence_ids
                if (finding.fact_id, evidence_id) not in known_fact_evidence_links
            }
            if unbound:
                _schema_error(
                    "hearing_judge_v2",
                    f"fact finding references evidence not linked to its fact: {sorted(unbound)}",
                )
            unassessed = set(finding.evidence_ids) - assessed_evidence_ids
            if unassessed:
                _schema_error(
                    "hearing_judge_v2",
                    f"fact finding references evidence without an assessment: {sorted(unassessed)}",
                )
        known_policy_refs = {
            (item.rule_code, item.rule_version) for item in request.trial_dossier.policy_rules
        }
        for application in output.draft.policy_application:
            _validated_fact_ids(application.fact_ids, known_facts)
            policy_ref = (application.rule_code, application.rule_version)
            if policy_ref not in known_policy_refs:
                _schema_error(
                    "hearing_judge_v2",
                    "policy application references a rule version absent from the frozen dossier: "
                    f"{policy_ref[0]}@{policy_ref[1]}",
                )
        v2_id = _stable_id(
            "JUDGE_V2",
            request.case_id,
            request.judge_v1.proposal_hash,
            request.jury_review.review_hash,
            output.draft.draft_text,
        )
        payload = {
            "schema_version": "hearing_judge_v2.v1",
            "case_id": request.case_id,
            "workflow_id": request.workflow_id,
            "stage_sequence": request.stage_sequence,
            "trial_dossier_id": request.trial_dossier.trial_dossier_id,
            "trial_dossier_hash": request.trial_dossier.content_hash,
            "judge_v2_id": v2_id,
            "judge_v2_hash": "0" * 64,
            "parent_proposal_id": request.judge_v1.proposal_id,
            "parent_proposal_hash": request.judge_v1.proposal_hash,
            "jury_review_id": request.jury_review.review_id,
            "jury_review_hash": request.jury_review.review_hash,
            **output.model_dump(mode="json"),
        }
        payload["judge_v2_hash"] = content_hash(payload, hash_field="judge_v2_hash")
        return HearingJudgeV2Result.model_validate(payload)

    def _invoke(self, node_name: str, request: Any, output_type: Any) -> Any:
        return self._invoke_payload(
            node_name,
            {"request": request.model_dump(mode="json")},
            output_type,
        )

    def _invoke_payload(self, node_name: str, case_data: dict[str, Any], output_type: Any) -> Any:
        if self._model_runner is None:
            raise AgentServiceUnavailable(f"{node_name} model runner is unavailable")
        generation = self._model_runner.invoke_structured(
            node_name=node_name,
            case_data=case_data,
            output_type=output_type,
        )
        return output_type.model_validate(generation.value)


def _intake_issue_contexts(request: HearingIntakeSynthesisRequest) -> list[dict[str, Any]]:
    contexts: list[dict[str, Any]] = []
    seen: set[str] = set()
    for question in request.questions:
        issue_id = question.issue_id or question.question_id
        if issue_id in seen:
            _schema_error(
                "hearing_intake_synthesis",
                f"duplicate intake issue_id: {issue_id}",
            )
        seen.add(issue_id)
        issue_statement = question.issue_statement or question.question_text
        prompts = (
            question.party_prompts.model_dump(mode="json")
            if question.party_prompts is not None
            else {"USER": question.question_text, "MERCHANT": question.question_text}
        )
        contexts.append(
            {
                "issue_id": issue_id,
                "issue_statement": issue_statement,
                "fact_ids": list(question.fact_ids),
                "party_prompts": prompts,
            }
        )
    return contexts


def _party_statement_contexts(request: HearingIntakeSynthesisRequest) -> list[dict[str, Any]]:
    return [
        {
            "participant_role": submission.participant_role,
            "terminal_status": submission.terminal_status,
            "statement_text": _party_statement_text(submission),
            "statement_refs": _party_statement_refs(submission),
        }
        for submission in request.party_submissions
    ]


def _party_statement_text(submission: Any) -> str | None:
    direct = getattr(submission, "statement_text", None)
    if isinstance(direct, str) and direct.strip():
        return direct.strip()

    payload = submission.submission
    for key in ("statement_text", "raw_statement", "statement"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()

    answers = payload.get("answers")
    if not isinstance(answers, list):
        return None
    texts: list[str] = []
    for answer in answers:
        if isinstance(answer, dict):
            value = answer.get("answer_text", answer.get("answerText"))
        else:
            value = answer
        if isinstance(value, str) and value.strip():
            texts.append(value.strip())
    return "\n\n".join(texts) or None


def _party_statement_refs(submission: Any) -> list[str]:
    nested_refs: list[Any] = []
    for key in ("statement_refs", "source_message_ids", "sourceMessageIds"):
        values = submission.submission.get(key, [])
        if isinstance(values, list):
            nested_refs.extend(values)
    refs = [*submission.source_refs, *nested_refs]
    return list(
        dict.fromkeys(
            text
            for value in refs
            if 3 <= len(text := str(value).strip()) <= 128
        )
    )[-50:]


def _resolved_issue_mappings(
    request: HearingIntakeSynthesisRequest,
    issue_contexts: list[dict[str, Any]],
    party_statements: list[dict[str, Any]],
    drafts: list[Any],
) -> list[HearingIssueMapping]:
    expected = {item["issue_id"]: item for item in issue_contexts}
    drafts_by_id = {item.issue_id: item for item in drafts}
    if len(drafts_by_id) != len(drafts):
        _schema_error("hearing_intake_synthesis", "issue mappings must use unique issue_id values")
    if set(drafts_by_id) != set(expected):
        _schema_error(
            "hearing_intake_synthesis",
            "issue mappings must cover every intake issue exactly once",
        )

    statements_by_role = {item["participant_role"]: item for item in party_statements}
    resolved: list[HearingIssueMapping] = []
    for context in issue_contexts:
        draft = drafts_by_id[context["issue_id"]]
        positions: dict[str, Any] = {}
        for role in ("USER", "MERCHANT"):
            statement = statements_by_role[role]
            position = getattr(draft.party_positions, role)
            has_statement = bool(statement["statement_text"])
            if position.coverage != "NOT_ADDRESSED" and (
                statement["terminal_status"] != "COMPLETED" or not has_statement
            ):
                _schema_error(
                    "hearing_intake_synthesis",
                    f"cannot map an addressed issue without a completed party statement: {role}",
                )
            positions[role] = {
                **position.model_dump(mode="json"),
                "statement_refs": statement["statement_refs"] if has_statement else [],
            }
        resolved.append(
            HearingIssueMapping.model_validate(
                {
                    "issue_id": context["issue_id"],
                    "issue_statement": context["issue_statement"],
                    "fact_ids": context["fact_ids"],
                    "party_positions": positions,
                }
            )
        )
    return resolved


def _merge_hearing_case_matrix(
    request: HearingIntakeSynthesisRequest,
    delta: HearingCaseFactMatrixDelta,
) -> Any:
    previous = request.case_fact_matrix
    rows_by_id = {row.fact_id: row.model_dump(mode="json") for row in previous.fact_rows}
    ordered_ids = [row.fact_id for row in previous.fact_rows]
    fingerprints = {
        _fact_fingerprint(row.category, row.fact_target): row.fact_id for row in previous.fact_rows
    }
    resolved_keys = {fact_id: fact_id for fact_id in rows_by_id}
    resolved_delta_ids: set[str] = set()
    submissions_by_role = {item.participant_role: item for item in request.party_submissions}
    role_source_refs: dict[str, list[str]] = {}
    for role in ("USER", "MERCHANT"):
        submission = submissions_by_role[role]
        refs = _party_statement_refs(submission)
        if not refs:
            refs = [
                _stable_id(
                    "HEARING_SOURCE",
                    request.case_id,
                    str(request.stage_sequence),
                    role,
                )
            ]
        role_source_refs[role] = refs
    current_refs = list(
        dict.fromkeys(
            [
                *request.source_refs,
                *role_source_refs["USER"],
                *role_source_refs["MERCHANT"],
            ]
        )
    )
    if not current_refs:
        current_refs = [
            _stable_id(
                "HEARING_SOURCE",
                request.case_id,
                str(request.stage_sequence),
            )
        ]

    for item in delta.fact_rows:
        updates = item.positions.model_dump(mode="json")
        updated_roles = [role for role in ("USER", "MERCHANT") if updates[role] is not None]
        for role in updated_roles:
            submission = submissions_by_role[role]
            if submission.terminal_status != "COMPLETED" or not _party_statement_text(submission):
                _schema_error(
                    "hearing_intake_synthesis",
                    "cannot create a hearing position without a completed, "
                    f"substantive party answer: {role}",
                )
        row_source_refs = list(
            dict.fromkeys(ref for role in updated_roles for ref in role_source_refs[role])
        )
        previous_row: dict[str, Any] | None = None
        if item.fact_key.startswith("FACT_"):
            previous_row = rows_by_id.get(item.fact_key)
            if previous_row is None:
                _schema_error(
                    "hearing_intake_synthesis",
                    f"delta references unknown fact {item.fact_key}",
                )
            fact_id = item.fact_key
        else:
            fingerprint = _fact_fingerprint(item.category, item.fact_target)
            fact_id = fingerprints.get(fingerprint) or _stable_id(
                "FACT_HEARING",
                request.case_id,
                str(item.category),
                _normalized_fact_target(item.fact_target),
            )
            previous_row = rows_by_id.get(fact_id)

        if fact_id in resolved_delta_ids:
            _schema_error(
                "hearing_intake_synthesis",
                f"delta resolves duplicate fact {fact_id}",
            )
        resolved_delta_ids.add(fact_id)
        resolved_keys[item.fact_key] = fact_id

        if previous_row is not None:
            if _fact_fingerprint(
                previous_row["category"], previous_row["fact_target"]
            ) != _fact_fingerprint(item.category, item.fact_target) or previous_row[
                "materiality"
            ] != str(item.materiality):
                _schema_error(
                    "hearing_intake_synthesis",
                    f"existing fact {fact_id} cannot change identity or materiality",
                )
            row = dict(previous_row)
            row["positions"] = {
                role: dict(value) for role, value in previous_row["positions"].items()
            }
        else:
            row = {
                "fact_id": fact_id,
                "category": str(item.category),
                "fact_target": item.fact_target,
                "materiality": str(item.materiality),
                "origin": {
                    "introduced_stage": "HEARING_CLARIFICATION",
                    "source_refs": row_source_refs[-50:],
                },
                "positions": {
                    "USER": _not_addressed_position(),
                    "MERCHANT": _not_addressed_position(),
                },
                "truth_status": "NOT_EVALUATED",
                "evidence_coverage_status": "NOT_COVERED_BY_FROZEN_DOSSIER",
            }
            ordered_ids.append(fact_id)
            fingerprints[_fact_fingerprint(item.category, item.fact_target)] = fact_id

        for role in ("USER", "MERCHANT"):
            update = updates[role]
            if update is None:
                continue
            prior_refs = row["positions"][role].get("source_refs", [])
            row["positions"][role] = {
                **update,
                "source_type": "DIRECT_PARTY_STATEMENT",
                "source_refs": list(dict.fromkeys([*prior_refs, *role_source_refs[role]]))[-50:],
            }

        alignment = _hearing_alignment(
            row["positions"],
            fact_target=item.fact_target,
            agreed_statement=item.agreed_statement,
            conflict_summary=item.conflict_summary,
        )
        status = CaseAlignmentStatus(alignment["status"])
        row["party_alignment"] = alignment
        row["requires_resolution"] = status != CaseAlignmentStatus.AGREED
        rows_by_id[fact_id] = row

    unknown_summary_keys = set(delta.summary_source_fact_keys) - set(resolved_keys)
    if unknown_summary_keys:
        _schema_error(
            "hearing_intake_synthesis",
            f"summary references unknown fact keys: {sorted(unknown_summary_keys)}",
        )
    summary_ids = list(dict.fromkeys(resolved_keys[key] for key in delta.summary_source_fact_keys))
    if not summary_ids:
        _schema_error("hearing_intake_synthesis", "case overview requires fact refs")

    rows = [rows_by_id[fact_id] for fact_id in ordered_ids]
    matrix_version = previous.matrix_version + 1
    source_context_hash = content_hash(
        {
            "case_id": request.case_id,
            "parent_hash": previous.content_hash,
            "questions": [item.model_dump(mode="json") for item in request.questions],
            "party_submissions": [
                item.model_dump(mode="json") for item in request.party_submissions
            ],
            "delta": delta.model_dump(mode="json"),
        },
        hash_field="source_context_hash",
    )
    payload = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": request.case_id,
        "matrix_id": _stable_id(
            "CASE_MATRIX",
            request.case_id,
            str(matrix_version),
            source_context_hash,
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
        "source_refs": list(dict.fromkeys([*previous.source_refs, *current_refs]))[-256:],
        "case_overview": {
            "neutral_summary": delta.neutral_summary,
            "core_conflict": delta.core_conflict,
            "summary_source_fact_ids": summary_ids,
        },
        "claims": previous.claims.model_dump(mode="json"),
        "fact_rows": rows,
        "fact_relationships": [
            item.model_dump(mode="json") for item in previous.fact_relationships
        ],
        "generation_ref": {
            "actor_role": "SYSTEM",
            "source_stage": "HEARING_CLARIFICATION",
            "latest_source_ref": current_refs[-1],
            "source_context_hash": source_context_hash,
        },
        "fact_indexes": _hearing_fact_indexes(rows),
    }
    payload["content_hash"] = content_hash(payload, hash_field="content_hash")
    return request.case_fact_matrix.__class__.model_validate(payload)


def _hearing_dispute_points(matrix: Any) -> list[HearingDisputePoint]:
    points: list[HearingDisputePoint] = []
    for row in matrix.fact_rows:
        if row.requires_resolution is not True:
            continue
        summary = row.party_alignment.conflict_summary or f"{row.fact_target}仍需结合证据处理。"
        points.append(
            HearingDisputePoint(
                dispute_point_id=_stable_id(
                    "DISPUTE_POINT",
                    matrix.case_id,
                    row.fact_id,
                    summary,
                ),
                fact_ids=[row.fact_id],
                summary=summary,
                requires_resolution=True,
            )
        )
        if len(points) >= 30:
            break
    return points


def _hearing_alignment(
    positions: dict[str, dict[str, Any]],
    *,
    fact_target: str,
    agreed_statement: str | None,
    conflict_summary: str | None,
) -> dict[str, Any]:
    user = FactStance(positions["USER"]["stance"])
    merchant = FactStance(positions["MERCHANT"]["stance"])
    substantive = {FactStance.CONFIRM, FactStance.DENY, FactStance.PARTIAL}
    if user not in substantive and merchant not in substantive:
        status = CaseAlignmentStatus.UNRESOLVED
    elif user not in substantive or merchant not in substantive:
        status = CaseAlignmentStatus.ONE_SIDED
    else:
        user_value = _normalized_fact_target(positions["USER"].get("asserted_value") or "")
        merchant_value = _normalized_fact_target(positions["MERCHANT"].get("asserted_value") or "")
        same_value = bool(user_value) and user_value == merchant_value
        if user == merchant and user in {FactStance.CONFIRM, FactStance.DENY} and same_value:
            status = CaseAlignmentStatus.AGREED
        elif FactStance.PARTIAL in {user, merchant} and agreed_statement and conflict_summary:
            status = CaseAlignmentStatus.PARTIALLY_AGREED
        else:
            status = CaseAlignmentStatus.CONTESTED

    if status == CaseAlignmentStatus.AGREED:
        return {
            "status": status,
            "agreed_statement": agreed_statement or fact_target,
            "conflict_summary": None,
        }
    if status == CaseAlignmentStatus.PARTIALLY_AGREED:
        return {
            "status": status,
            "agreed_statement": agreed_statement,
            "conflict_summary": conflict_summary,
        }
    return {
        "status": status,
        "agreed_statement": None,
        "conflict_summary": conflict_summary or "双方对该事实尚未形成一致陈述。",
    }


def _hearing_fact_indexes(rows: list[dict[str, Any]]) -> dict[str, list[str]]:
    by_status = {
        status: [row["fact_id"] for row in rows if row["party_alignment"]["status"] == status]
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


def _fact_fingerprint(category: Any, fact_target: str) -> tuple[str, str]:
    return str(category), _normalized_fact_target(fact_target)


def _normalized_issue_statement(value: str) -> str:
    return " ".join(value.strip().casefold().split())


def _normalized_fact_target(value: str) -> str:
    return " ".join(str(value).strip().casefold().split())


def _merge_evidence_batch(
    request: HearingEvidenceSynthesisRequest,
    assessments: list[HearingBatchEvidenceAssessment],
) -> FactEvidenceMatrixV2:
    case_matrix = request.case_fact_matrix
    known_facts = _case_fact_ids(case_matrix)
    new_files = [item for batch in request.party_batches for item in batch.evidence]
    new_ids = [item.evidence_id for item in new_files]
    assessments_by_id = {item.evidence_id: item for item in assessments}
    if len(assessments_by_id) != len(assessments):
        _schema_error("hearing_evidence_synthesis", "duplicate evidence assessments")
    if set(assessments_by_id) != set(new_ids):
        _schema_error(
            "hearing_evidence_synthesis",
            "assessments must cover every new evidence file exactly once",
        )
    prior = request.prior_fact_evidence_matrix
    if prior is not None:
        _assert_evidence_matrix_compatible(prior, case_matrix)
    prior_coverage_by_fact = {
        item.fact_id: item for item in (prior.fact_coverage if prior is not None else [])
    }
    links_by_key = {
        (item.fact_id, item.evidence_id): item.model_dump(mode="json")
        for item in (prior.links if prior is not None else [])
    }
    batch_by_evidence = {
        item.evidence_id: batch.batch_id
        for batch in request.party_batches
        for item in batch.evidence
    }
    review_ids: set[str] = set()
    for evidence_id in new_ids:
        assessment = assessments_by_id[evidence_id]
        if assessment.requires_human_review:
            review_ids.add(evidence_id)
        for link in assessment.fact_links:
            _validated_fact_ids([link.fact_id], known_facts)
            links_by_key[(link.fact_id, evidence_id)] = {
                **link.model_dump(mode="json"),
                "evidence_id": evidence_id,
                "source_batch_id": batch_by_evidence[evidence_id],
            }
    links = list(links_by_key.values())
    coverage = []
    for row in case_matrix.fact_rows:
        evidence_ids = list(
            dict.fromkeys(item["evidence_id"] for item in links if item["fact_id"] == row.fact_id)
        )
        new_linked_ids = set(evidence_ids).intersection(new_ids)
        prior_coverage = prior_coverage_by_fact.get(row.fact_id)
        prior_requires_review = (
            prior_coverage is not None
            and prior_coverage.coverage_status == FactEvidenceCoverageStatus.REQUIRES_HUMAN_REVIEW
        )
        if prior_requires_review or (new_linked_ids and review_ids.intersection(new_linked_ids)):
            status = FactEvidenceCoverageStatus.REQUIRES_HUMAN_REVIEW
            note = "该事实已有材料，但至少一份材料需要人工复核。"
        elif new_linked_ids:
            status = FactEvidenceCoverageStatus.COVERED_BY_SUBMITTED_EVIDENCE
            note = "该事实已有关联的正式提交材料。"
        elif prior_coverage is not None:
            status = prior_coverage.coverage_status
            evidence_ids = list(dict.fromkeys([*prior_coverage.evidence_ids, *evidence_ids]))
            note = prior_coverage.note
        elif evidence_ids:
            status = FactEvidenceCoverageStatus.COVERED_BY_FROZEN_DOSSIER
            note = "该事实的关联材料来自庭前冻结证据卷宗。"
        else:
            status = FactEvidenceCoverageStatus.NOT_COVERED_BY_FROZEN_DOSSIER
            note = "该事实尚未被当前证据卷宗覆盖。"
        coverage.append(
            {
                "fact_id": row.fact_id,
                "coverage_status": status,
                "evidence_ids": evidence_ids,
                "note": note,
            }
        )
    version = (prior.matrix_version + 1) if prior is not None else 1
    matrix_id = _stable_id(
        "FACT_EVIDENCE_MATRIX",
        request.case_id,
        str(version),
        *new_ids,
    )
    payload = {
        "schema_version": "fact_evidence_matrix.v2",
        "case_id": request.case_id,
        "matrix_id": matrix_id,
        "matrix_version": version,
        "matrix_status": "WORKING",
        "parent_ref": (
            {
                "matrix_id": prior.matrix_id,
                "matrix_version": prior.matrix_version,
                "content_hash": prior.content_hash,
            }
            if prior is not None
            else None
        ),
        "case_fact_matrix_id": case_matrix.matrix_id,
        "case_fact_matrix_version": case_matrix.matrix_version,
        "case_fact_matrix_hash": case_matrix.content_hash,
        "content_hash": "0" * 64,
        "source_refs": list(
            dict.fromkeys(
                [
                    *request.source_refs,
                    *[batch.batch_id for batch in request.party_batches],
                ]
            )
        )[-256:],
        "links": links,
        "fact_coverage": coverage,
    }
    payload["content_hash"] = content_hash(payload, hash_field="content_hash")
    return FactEvidenceMatrixV2.model_validate(payload)


def _normalize_jury_review(
    output: HearingJuryReviewLlmOutput,
    request: HearingJuryReviewRequest,
) -> HearingJuryReviewDraft:
    dimensions = (
        "FACT_COMPLETENESS",
        "EVIDENCE_CONSISTENCY",
        "RULE_APPLICABILITY",
        "PROCEDURAL_FAIRNESS",
        "REMEDY_FEASIBILITY",
        "RISK_AND_OMISSIONS",
    )
    severity_rank = {
        "NONE": 0,
        "LOW": 1,
        "MEDIUM": 2,
        "HIGH": 3,
        "BLOCKER": 4,
    }
    grouped: dict[str, list[HearingJuryFinding]] = {
        dimension: [] for dimension in dimensions
    }
    for finding in output.findings:
        grouped[finding.dimension].append(finding)

    normalized: list[HearingJuryFinding] = []
    repair_revisions: list[str] = []
    for dimension in dimensions:
        candidates = grouped[dimension]
        if not candidates:
            normalized.append(
                HearingJuryFinding(
                    dimension=dimension,
                    severity="MEDIUM",
                    assessment=(
                        "模型原始评议未单独覆盖此维度，终稿必须补充审查并由人工复核。"
                    ),
                    basis=[
                        request.judge_v1.proposal_id,
                        request.trial_dossier.trial_dossier_id,
                    ],
                    requires_revision=True,
                )
            )
            repair_revisions.append(
                f"补充审查 {dimension} 并在终稿中明确说明结论。"
            )
            continue

        strongest = max(candidates, key=lambda item: severity_rank[item.severity])
        normalized.append(
            strongest.model_copy(
                update={
                    "assessment": "；".join(
                        dict.fromkeys(item.assessment for item in candidates)
                    )[:20_000],
                    "basis": list(
                        dict.fromkeys(
                            basis for item in candidates for basis in item.basis
                        )
                    )[:10],
                    "requires_revision": any(
                        item.requires_revision for item in candidates
                    ),
                }
            )
        )

    mandatory_revisions = list(
        dict.fromkeys([*repair_revisions, *output.mandatory_revisions])
    )[:20]
    if (
        any(
            finding.requires_revision or finding.severity in {"HIGH", "BLOCKER"}
            for finding in normalized
        )
        and not mandatory_revisions
    ):
        mandatory_revisions.append("终稿必须逐项回应独立评议指出的修订要求。")

    return HearingJuryReviewDraft(
        findings=normalized,
        mandatory_revisions=mandatory_revisions,
        public_message=output.public_message,
    )


def _validate_v1_binding(request: HearingJuryReviewRequest) -> None:
    _validate_dossier_request(request, "hearing_jury_review")
    proposal = request.judge_v1
    dossier = request.trial_dossier
    if (
        proposal.case_id != request.case_id
        or proposal.workflow_id != request.workflow_id
        or proposal.trial_dossier_id != dossier.trial_dossier_id
        or proposal.trial_dossier_hash != dossier.content_hash
        or proposal.stage_sequence >= request.stage_sequence
    ):
        _schema_error("hearing_jury_review", "judge V1 is not bound to this dossier")


def _validate_v2_binding(request: HearingJudgeV2Request) -> None:
    _validate_dossier_request(request, "hearing_judge_v2")
    proposal = request.judge_v1
    review = request.jury_review
    dossier = request.trial_dossier
    if (
        proposal.case_id != request.case_id
        or review.case_id != request.case_id
        or proposal.workflow_id != request.workflow_id
        or review.workflow_id != request.workflow_id
        or proposal.trial_dossier_id != dossier.trial_dossier_id
        or review.trial_dossier_id != dossier.trial_dossier_id
        or proposal.trial_dossier_hash != dossier.content_hash
        or review.trial_dossier_hash != dossier.content_hash
        or review.reviewed_proposal_id != proposal.proposal_id
        or review.reviewed_proposal_hash != proposal.proposal_hash
        or proposal.stage_sequence >= review.stage_sequence
        or review.stage_sequence >= request.stage_sequence
    ):
        _schema_error("hearing_judge_v2", "V1, jury review, and dossier bindings differ")


def _assert_evidence_matrix_compatible(matrix: Any, case_matrix: Any) -> None:
    _assert_case_matrix_integrity(
        case_matrix,
        expected_case_id=case_matrix.case_id,
        node_name="hearing_evidence",
    )
    if matrix.case_id != case_matrix.case_id:
        _schema_error("hearing_evidence", "evidence matrix belongs to another case")
    allowed_bindings = {
        (
            case_matrix.matrix_id,
            case_matrix.matrix_version,
            case_matrix.content_hash,
        )
    }
    if case_matrix.parent_ref is not None:
        allowed_bindings.add(
            (
                case_matrix.parent_ref.matrix_id,
                case_matrix.parent_ref.matrix_version,
                case_matrix.parent_ref.content_hash,
            )
        )
    actual_binding = (
        matrix.case_fact_matrix_id,
        matrix.case_fact_matrix_version,
        matrix.case_fact_matrix_hash,
    )
    if actual_binding not in allowed_bindings:
        _schema_error(
            "hearing_evidence",
            "evidence matrix is not bound to the current case matrix or its direct parent",
        )
    referenced_facts = {
        *(item.fact_id for item in matrix.links),
        *(item.fact_id for item in matrix.fact_coverage),
    }
    unknown_facts = referenced_facts - _case_fact_ids(case_matrix)
    if unknown_facts:
        _schema_error(
            "hearing_evidence",
            f"evidence matrix references facts absent from the hearing matrix: {sorted(unknown_facts)}",
        )


def _assert_case_matrix_integrity(
    matrix: Any,
    *,
    expected_case_id: str,
    node_name: str,
) -> None:
    if matrix.case_id != expected_case_id:
        _schema_error(node_name, "case matrix belongs to another case")
    if matrix.content_hash != content_hash(matrix, hash_field="content_hash"):
        _schema_error(node_name, "input case matrix hash is invalid")


def _validate_dossier_request(request: Any, node_name: str) -> None:
    dossier = request.trial_dossier
    if dossier.case_id != request.case_id:
        _schema_error(node_name, "trial dossier belongs to another case")
    _assert_case_matrix_integrity(
        dossier.case_fact_matrix,
        expected_case_id=request.case_id,
        node_name=node_name,
    )


def _case_fact_ids(matrix: Any) -> set[str]:
    return {item.fact_id for item in matrix.fact_rows}


def _validated_fact_ids(values: Iterable[str], known: set[str]) -> list[str]:
    result = list(dict.fromkeys(str(item) for item in values))
    unknown = set(result) - known
    if unknown:
        _schema_error("hearing_flow", f"unknown fact ids: {sorted(unknown)}")
    if not result:
        _schema_error("hearing_flow", "at least one fact id is required")
    return result


def _canonical_roles(values: Iterable[str]) -> list[str]:
    roles = set(values)
    return [role for role in ("USER", "MERCHANT") if role in roles]


def _stable_id(prefix: str, *parts: str) -> str:
    digest = hashlib.sha256("\u001f".join(parts).encode("utf-8")).hexdigest()[:24]
    return f"{prefix}_{digest}"


def _schema_error(node_name: str, message: str) -> None:
    raise AgentOutputSchemaError(node_name, message)
