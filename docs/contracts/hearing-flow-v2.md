# Hearing Flow V2 Contract

This contract replaces the generic three-round hearing runtime. New and existing
cases use this flow; there is no legacy hearing fallback.

## Flow state

`schema_version` is always `hearing_flow.v2`.

| Stage | Owner | Party action | Exit condition |
| --- | --- | --- | --- |
| `COURT_PREPARING` | system | none | fixed opening and source matrices loaded |
| `CASE_INTRODUCTION` | intake officer template | none | full pre-hearing case matrix introduced |
| `EVIDENCE_INTRODUCTION` | evidence clerk template | none | full pre-hearing evidence matrix introduced |
| `INTAKE_QUESTIONS_GENERATING` | intake officer LLM | none | valid question set persisted |
| `PARTY_ANSWERS_OPEN` | parties | one answer bundle per party | both parties submitted or reached timeout |
| `INTAKE_SYNTHESIZING` | intake officer LLM | none | deltas merged into the full case matrix and synthesis persisted atomically |
| `EVIDENCE_REQUESTS_GENERATING` | evidence clerk LLM | none | fact-bound request set persisted |
| `PARTY_EVIDENCE_OPEN` | parties | one evidence batch per party | both parties submitted or reached timeout |
| `EVIDENCE_SYNTHESIZING` | evidence clerk LLM | none | all new files assessed, full matrix merged and synthesis persisted atomically |
| `DOSSIER_FREEZING` | system | none | one immutable trial dossier persisted |
| `JUDGE_V1_GENERATING` | presiding judge LLM | none | V1 proposal persisted |
| `JURY_REVIEWING` | jury LLM | none | report bound to the V1 id and hash persisted |
| `JUDGE_V2_GENERATING` | presiding judge LLM | none | V2 draft bound to the review report persisted |
| `HUMAN_REVIEW_OPEN` | system | none | exact displayed V2 copied asynchronously into the review packet and review task created |
| `CLOSED` | system | none | hearing room sealed |

Only `PARTY_ANSWERS_OPEN` and `PARTY_EVIDENCE_OPEN` have party deadlines. Both
parties share one absolute deadline. A first submission never changes the shared
matrix; it only records that party's terminal state and emits an acknowledgement.

## Message provenance

Every courtroom message declares one of these modes:

- `SYSTEM_STAGE_EVENT`: workflow narration such as next-step, processing and waiting notices.
- `ROLE_TEMPLATE`: deterministic role speech. It must have `agent_run_id = null`.
- `AGENT_LLM`: model-generated role speech. It must reference a completed AgentRun.
- `PARTY_ACTION`: a party answer or evidence-batch action.

Judge messages before `DOSSIER_FREEZING` are allowed only as `ROLE_TEMPLATE`.
The number of judge LLM AgentRuns before `TRIAL_DOSSIER_FROZEN` must be zero.

## Business objects

### `hearing_question_set.v1`

- `question_set_id`, `case_id`, `case_matrix_version`, `case_matrix_hash`
- `questions`: one to five items containing stable `question_id`, `fact_ids`,
  `issue_id`, `target_roles` (`USER`, `MERCHANT`, or both), and `question_text`
- `generated_by_agent_run_id`, `created_at`

### `hearing_answer_bundle.v1`

- `question_set_id`, `participant_role`, `submission_status`
  (`SUBMITTED` or `AUTO_TIMEOUT`), `submitted_at`
- `answers`: one item per applicable question containing `question_id`,
  `answer_text`, and optional `attachment_refs`
- `source_message_ids`

The server rejects missing, duplicate, foreign, or non-applicable question ids.

### `hearing_evidence_request_set.v1`

- `request_set_id`, `case_matrix_version`, `case_matrix_hash`
- `requests`: stable `request_id`, non-empty `fact_ids`, `target_roles`,
  `requested_material`, `verification_goal`, and `required`
- `generated_by_agent_run_id`, `created_at`

### `hearing_evidence_batch.v1`

- `request_set_id`, `participant_role`, `submission_status`
  (`SUBMITTED` or `AUTO_TIMEOUT`), `submitted_at`
- `evidence_ids` (zero to 50 unique ids), `request_ids`, and `batch_note`

All files in the two terminal batches are assessed in parallel. The server waits
for every assessment to reach a terminal state, then performs exactly one shared
matrix merge and freeze.

### `case_fact_matrix.v2`

The merged hearing version retains stable fact ids and appends a generation ref
with source stage `HEARING_CLARIFICATION`. Facts introduced after the pre-hearing
evidence freeze carry `evidence_coverage_status =
NOT_COVERED_BY_PREHEARING_FROZEN_DOSSIER`; this is coverage metadata, not a truth
finding. The matrix is versioned and never replaced by a model-authored blob.

### `fact_evidence_matrix.v2`

The shared matrix is keyed by `(fact_id, evidence_id)`, records assessment and
coverage state, and binds every row to its evidence assessment AgentRun. Its
version changes only when the normalized full matrix content changes.

### `trial_dossier.v1`

- `trial_dossier_id`, `case_id`, `frozen_at`
- exact `case_matrix_version`, `case_matrix_hash`, and frozen matrix payload
- exact `evidence_matrix_version`, `evidence_matrix_hash`, and frozen matrix payload
- `question_set_id`, both answer bundles, `request_set_id`, both evidence batches
- one to 100 active `policy_rules`, each frozen with `policy_id`, `rule_code`,
  `rule_version`, name, scope, effective window, conditions, outcome and source
  document
- `content_hash`

The judge and jury receive only this immutable object, never live tables.

### Decision chain

- `judge_proposal.v1`: `proposal_id`, `trial_dossier_id`, `trial_dossier_hash`,
  structured findings, rule applications, remedy, public text and `content_hash`.
- `jury_review_report.v1`: `report_id`, exact `proposal_id` and
  `proposal_content_hash`, findings, mandatory revisions, public text and
  `content_hash`.
- `adjudication_draft.v2`: `draft_id`, exact proposal/report ids and hashes,
  structured findings, rule applications, remedy, reviewer attention, public
  text and `content_hash`.

The nested V2 `draft` is the only adjudication content projected to the draft
page:

- `recommended_decision`, `confidence`, `draft_text`
- `fact_findings`: stable `fact_id`, finding text, linked `evidence_ids`, an
  optional `evidence_gap`, and confidence
- `evidence_assessment`: either an `EVIDENCE` assessment bound to one frozen
  `evidence_id` and its linked `fact_ids`, or an `EVIDENCE_GAP` assessment with
  a null evidence id and `weight = NONE`; both include assessment text,
  confidence and limitations
- `policy_application`: exact frozen `rule_code` and `rule_version`, rule name,
  linked `fact_ids`, applicability, rationale and limitations
- `reviewer_attention`, `draft_status`, `requires_human_review`, and
  `is_final_decision`

The draft page also reads `draft_version`, `review_task_id`, and
`review_task_status` from the Java outcome projection. Artifact hashes, parent
proposal/review bindings, workflow ids and AgentRun ids remain audit metadata
and are not rendered as adjudication content. Historical drafts containing
string-only evidence or policy arrays remain readable, but every newly
generated V2 must use the structured contract above.

V2 is generated once. The asynchronous review-packet job persists the same V2
object and hash; it must not call the judge again.

## Agent stream operations

| Java operation | Python stream endpoint | Role |
| --- | --- | --- |
| `HEARING_INTAKE_QUESTIONS` | `/internal/agents/hearing-flow/intake/questions/stream` | `INTAKE_OFFICER` |
| `HEARING_INTAKE_SYNTHESIS` | `/internal/agents/hearing-flow/intake/synthesis/stream` | `INTAKE_OFFICER` |
| `HEARING_EVIDENCE_REQUESTS` | `/internal/agents/hearing-flow/evidence/requests/stream` | `EVIDENCE_CLERK` |
| `HEARING_EVIDENCE_SYNTHESIS` | `/internal/agents/hearing-flow/evidence/synthesis/stream` | `EVIDENCE_CLERK` |
| `HEARING_JUDGE_V1` | `/internal/agents/hearing-flow/judge/v1/stream` | `PRESIDING_JUDGE` |
| `HEARING_JURY_REVIEW` | `/internal/agents/hearing-flow/jury/review/stream` | `JURY_PANEL` |
| `HEARING_JUDGE_V2` | `/internal/agents/hearing-flow/judge/v2/stream` | `PRESIDING_JUDGE` |

Every Python request envelope uses `flow_schema_version = hearing_flow.v2`,
`stage_code`, monotonic `stage_sequence`, and the optional shared
`stage_deadline_at`.

The old `HEARING_ROUND`, `HEARING_STAGE`, and `HEARING_ANALYSIS` operations and
their `/hearing/round-turn`, `/hearing/run-stage`, and
`/legacy/hearing/analyze` routes are not part of V2.

## Party HTTP surface

- `GET /api/disputes/{caseId}/hearing` returns the authoritative flow state,
  active question/request sets, both role terminal statuses, shared deadline,
  frozen object references and decision-chain references.
- `POST /api/disputes/{caseId}/hearing/answers` accepts exactly one
  `hearing_answer_bundle.v1` for the authenticated party.
- `POST /api/disputes/{caseId}/hearing/evidence-batches` accepts exactly one
  `hearing_evidence_batch.v1` for the authenticated party after its files have
  been uploaded through the evidence upload API.
- `POST /api/disputes/{caseId}/hearing/complete` remains a read/redirect gate;
  it cannot advance stages or invoke an Agent.

The old `/hearing/rounds`, `/hearing/rounds/complete`, and
`/hearing/rounds/current/submissions` endpoints are removed.

## Required invariants

1. Illegal stage transitions are rejected with the current stage in the error.
2. Submission endpoints are idempotent per case, stage, party, and request id.
3. A stage opens only after its predecessor's durable output commits.
4. Matrix update plus public synthesis reply commits atomically.
5. Dossier freeze is single-shot and binds both matrix versions and hashes.
6. V1, jury report and V2 form an id/hash chain validated at every boundary.
7. Refresh and reconnect read persisted stage state; message text is never used
   to infer workflow state.
