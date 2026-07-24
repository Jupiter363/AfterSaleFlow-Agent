# Phase 7 P7.0 Outcome Baseline Inventory

## Observation Boundary

This factual inventory is observed at the accepted Phase 6 checkpoint and the accepted P7.0
contract-entry evidence:

```text
A6: d18a1f130a925429e8c2dfd11352cea4ca8673a0
phase_6_engineering_checkpoint: PASS
C7: 0aa260f722fced0eba4314bd4793e415b5bf0b05
E7: e29cefb3e028bb84f6a227e46fecdf5711eba48c
release: phase-7-entry-20260724-0aa260f7
contract_gate: P7.0 PASS
entry_effect: P7_0_ENGINEERING_ENTRY_PASS
engineering_implementation: ALLOWED_UNDER_ADR_0016_ONLY
next_phase_permission: PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
formal_outcome_selector: LEGACY
allowed_new_runtime_modes: [DISABLED, JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW]
temporal_outcome_allocation: FORBIDDEN
formal_outcome_workflow: FORBIDDEN
formal_outcome_graph_sink: FORBIDDEN
real_tool_effects: FORBIDDEN
real_or_party_data_shadow: FORBIDDEN
production_traffic: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
java_outcome_business_truth: SOLE_FORMAL_TRUTH
```

The corresponding exception is `ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY`. `E7`, as the direct child
of `C7`, realizes `P7_0_ENGINEERING_ENTRY_PASS`; the separate P7.0 checkpoint records that decision.
This inventory still describes the legacy baseline Phase 7 must preserve. Entry acceptance closes
no product implementation gap and changes no formal runtime or writer.

## Current End-To-End Baseline

1. Hearing Judge V2 is persisted as a non-final adjudication draft and handed to Java review
   orchestration.
2. Java freezes a ReviewPacket, creates a ReviewTask, authorizes the reviewer, validates policy and
   immutable hashes, and records one human decision.
3. Approved decisions currently create an execution-assistant handoff event; the review service
   itself does not invoke a tool.
4. A restricted SYSTEM/ADMIN execution entry invokes `ToolExecutorService`, which revalidates the
   frozen approval and uses ActionRecord plus a Redis execution lock around each tool call.
5. `CaseClosureService` refuses closure until approved actions satisfy the expected successful
   multiset, commits `CLOSED`, freezes an evaluation snapshot, and invokes the offline Evaluation
   Agent.
6. Draft and Outcome are front-end/query projections. They are not `RoomType` values.

There is no Outcome Temporal Workflow, Outcome epoch allocation, `outcome/review.v1` StateGraph,
V045 migration, operation/receipt/compensation parent ledger, or synthetic no-op shadow runtime at
this baseline.

## Java Review Inventory

### Primary Paths

- `review/application/ReviewApplicationService.java` freezes ReviewPacket, creates/list/starts
  review tasks, enforces reviewer authorization, evaluates policy, and persists decisions.
- `review/application/PostReviewOrchestrationService.java` classifies the post-review path and
  writes lifecycle/audit handoff facts without executing approved actions.
- `review/application/ReviewCopilotStreamService.java` authorizes one frozen packet, starts the
  Copilot AgentRun, and exposes active-run recovery through the shared AgentRun protocol.
- `review/api/ReviewController.java` exposes review list, packet, start, decision, and Copilot
  endpoints.
- ReviewPacket, ReviewTask, ApprovalRecord, ApprovalPolicyDecision, RemedyPlan, draft, dossier, and
  Hearing artifacts remain Domain PostgreSQL/JPA facts.

### Current Decision Behavior

`ApprovalDecisionType` declares the five current values:

```text
APPROVE
MODIFY_AND_APPROVE
REQUEST_MORE_EVIDENCE
REJECT
ESCALATE_MANUAL
```

Java requires platform reviewer authority and validates the frozen packet/plan/action bindings.
`APPROVE` adopts the frozen plan. `MODIFY_AND_APPROVE` requires a real plan diff. The other three
decisions do not authorize execution. Duplicate decision handling is Java/DB idempotent. Another
reviewer retains read-only visibility but is not the task's decision writer.

The task has a due/expiry model, but there is no Temporal durable review wait, same-time
Timer-versus-decision History rule, or Java SLA-escalation receipt dedicated to an Outcome epoch.
No current timeout is evidence for an automatic approval contract.

## Execution Inventory

### Primary Paths

- `executor/application/ToolExecutorService.java` loads and validates an immutable execution
  snapshot, skips already successful actions, and invokes allowed tool adapters.
- `executor/api/ExecutionController.java` is the restricted execution and ActionRecord query
  surface.
- `executor/application/ActionExecutionLock.java` and its Redis implementation optimize concurrent
  execution exclusion.
- `executor/domain/ToolRegistry.java` resolves allowed adapters.
- `ActionRecordEntity` and `ActionRecordRepository` persist governed execution state.

### Current Correctness Shape

Before invocation, Java validates case, plan, approval, ReviewPacket, action hash, actor role, and
idempotency. Each action creates or locks an ActionRecord, calls the adapter outside the long DB
transaction, then records success or controlled failure. Existing successful actions are not
replayed.

Current ActionRecord status compatibility includes `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`,
`COMPENSATING`, and `COMPENSATED`. However, the current main path does not provide a dedicated
Outcome process operation table, immutable external receipt parent chain, compensation operation
ledger, provider status-query protocol, or Temporal retry/compensation scheduler. Correctness still
partly relies on the Redis lock around the external-call window, so timeout-after-provider-success
cannot yet be treated as a proven exactly-once outcome. Phase 7 must classify such an unresolved
result as operation-nonterminal `AMBIGUOUS`: it blocks closure and permits neither blind retry nor
compensation until an authoritative provider receipt query or Java reconciliation resolves it
before any next external effect.

## Closure And Evaluation Inventory

### Primary Paths

- `evaluation/application/CaseClosureService.java` validates the latest human approval and the
  expected successful action multiset, closes the case, creates an evaluation trace/snapshot, and
  invokes evaluation.
- `evaluation/api/ClosureController.java` exposes restricted close, evaluation, and metrics APIs.
- `evaluation/infrastructure/RestClientEvaluationAgentClient.java` calls the Python evaluation
  endpoint.
- `python-agent-service/app/agents/evaluation_agent.py` requires offline mode and a closed case,
  validates typed output, and rejects online-state mutation.

The Java transaction commits the `CLOSED` state and pending immutable evaluation snapshot before
the provider result is finalized. Evaluation completion or failure changes the evaluation trace;
evaluation cannot reopen the case. At this baseline the ordering is driven from the Java service
and existing activity/controller calls, not an Outcome Workflow receipt chain.

## Python Review And Evaluation Inventory

`python-agent-service/app/agents/review_copilot.py` is a direct `ReviewCopilot` class. It applies
input/output guardrails, validates citations against available fact/rule/draft/deliberation refs,
and returns one typed answer. It does not define a compiled `StateGraph`, graph registry identity,
durable checkpoint, lease/fence, command ledger, reducer, or resumable node boundary.

The current Copilot is intended to read one frozen ReviewPacket and has no direct Domain database
writer or tool execution call. That intended boundary is not yet the signed capability and private
checkpoint contract reserved as `outcome/review.v1`.

`python-agent-service/app/agents/evaluation_agent.py` is offline-only and requires `CLOSED`; it
delegates analysis to an injected workflow-like object and validates `EvaluationAnalysisResult`.
There is no Phase 7 evaluation graph receipt integrated into an Outcome Temporal history.

## Frontend Inventory

### Draft

- `frontend/src/views/disputes/AdjudicationDraftView.vue` reads the Outcome projection and displays
  the non-final V2 draft, facts, citations, gaps, rule application, and proposed remedy details.
- Historical string arrays and current structured V2 data remain compatible.
- Party users have no final-review action. Reviewer actions are fenced by role, task state, and
  history mode.

### Review

- `frontend/src/views/reviews/ReviewQueueView.vue` lists pending/in-review work for platform
  reviewers without exposing the full packet in the queue.
- `frontend/src/views/reviews/ReviewWorkbenchView.vue` displays frozen packet tabs, streams Copilot
  through AgentRun APIs, and presents the five decision controls to the authorized reviewer.
- Another reviewer is read-only. Historical mode disables Copilot and decisions.

### Outcome

- `frontend/src/views/disputes/OutcomeView.vue` is read-only and gates the formal four-section view
  on human-confirmed/approved facts.
- It displays persisted ActionRecord status and controlled receipts/errors when present.
- With no real receipt, the page may show a clearly labeled front-end-only simulated animation; the
  animation is not a business fact and may not cover a real failure.

`RoomType.java` remains exactly `INTAKE`, `EVIDENCE`, `HEARING`, and `REVIEW`. No `DRAFT` or
`OUTCOME` enum member exists.

## Database Inventory

The existing review/execution/evaluation chain is established primarily by:

| Migration | Current role |
| --- | --- |
| `V004__init_review_executor_tables.sql` | initial review, approval, and execution tables |
| `V005__init_policy_audit_tables.sql` | policy, audit, and evaluation facts |
| `V007__final_dispute_core.sql` | final dispute core relationships and constraints |
| `V008__final_agent_hearing_governance.sql` | Agent/Hearing governance references used by review |
| `V009__freeze_review_and_execution_chain.sql` | frozen packet and approval/execution chain hardening |
| `V039__temporal_command_control_plane.sql` | shared command/process control plane, not Outcome allocation |
| `V040__immutable_snapshot_and_manifest.sql` | shared immutable Artifact/snapshot support |
| `V044__hearing_temporal_projection.sql` | Hearing engineering projection only |

There is no `V045__outcome_operation_receipt_compensation.sql`. There is no Phase 7 Outcome
projection/epoch binding or dedicated operation, external receipt, and compensation parent chain.
Historical review, approval, ActionRecord, and evaluation rows must remain readable and immutable
where already governed.

## Focused Test Inventory

The current regression surface includes these Java suites:

- `ReviewApplicationServiceIntegrationTest` and `ReviewApplicationServiceV2Test`;
- `ReviewControllerTest`, `FrozenReviewPacketTest`, and `ApprovalPolicyEngineTest`;
- `PostReviewOrchestrationServiceIntegrationTest`;
- `ToolExecutorServiceIntegrationTest`;
- `CaseClosureServiceIntegrationTest`, `ClosureControllerTest`, and
  `RestClientEvaluationAgentClientTest`;
- `CaseOutcomeServiceTest` and `CaseOutcomeControllerTest`; and
- remedy planner/application/controller tests used by the approved plan chain.

Python has `tests/test_evaluation.py` and broader Final Agent/Copilot contract coverage. Frontend has
focused tests for `AdjudicationDraftView`, Review queue/workbench/API, and `OutcomeView`.

There is no `OutcomeRoomWorkflowTest`, `OutcomeRoomWorkflowReplayTest`,
`ReviewTemporalCommandIntegrationTest`, `ToolActivityIdempotencyTest`, or
`CompensationWorkflowTest` at this baseline. Existing tests prove the legacy Java path, not a
Temporal Outcome allocation or external exactly-once guarantee.

## Baseline Traceability

| Baseline | Current factual anchor | Phase 7 preservation rule |
| --- | --- | --- |
| `DRF-001..004` | Draft reads non-final V2 plus historical shapes | preserve content and non-final labeling |
| `DRF-005..006` | role/task/history gates control review entry | Temporal must not widen permissions |
| `REV-001..003` | reviewer-only queue and bounded list projection | preserve authorization and privacy |
| `REV-004..008` | frozen packet, cited Copilot, assigned writer, history lock | graph remains private/read-only/no-tool |
| `REV-009` | reason/confirmation and idempotent decision | one immutable Java receipt |
| `REV-010` | exact approve or real bounded modification | preserve packet immutability and hashes |
| `REV-011..012` | only approval decisions hand off; Agent never executes | timeout and non-approval never produce effects |
| `OUT-001..003` | formal read gate and four-section result | Outcome remains a query projection |
| `OUT-004..006` | real ActionRecord/receipt status outranks animation | no-op shadow cannot masquerade as a real receipt |
| `OUT-007` | restricted executor revalidates approval | Workflow cannot bypass Java authorization |
| `OVR-007` | persisted lifecycle facts drive navigation | model text never closes or routes a case |
| `CORE-010` | persisted event/stream replay is the recovery baseline | new receipts carry monotonic revision/sequence |
| `SEC-006` | restricted high-impact action boundary | no Agent or reviewer UI tool capability |

## Current P0 Gaps Before Phase 7 Implementation

| Gap | Current evidence | Gate effect |
| --- | --- | --- |
| `P7-G0` | accepted `A6`, ADR 0016, exact `C7`, and direct-child `E7` are present | entry authorization chain accepted; no product gap closed |
| `P7-G1` | 140 exact-candidate Batch 0 tests passed and `E7` realizes `P7_0_ENGINEERING_ENTRY_PASS` | entry-only gap closed; engineering allowed under ADR 0016 |
| `P7-G2` | no Outcome Workflow, durable review wait, SLA timer, or replay fixture | Temporal cannot own Outcome time/failure |
| `P7-G3` | Java review decisions exist, but no epoch/fence domain receipt protocol exists | formal selector must remain `LEGACY` |
| `P7-G4` | direct Copilot class, no `outcome/review.v1` StateGraph/checkpoint/capability | graph cannot enter engineering shadow |
| `P7-G5` | V045 and operation/receipt/compensation parent ledgers are absent | `AMBIGUOUS` effects stay nonterminal and block retry, compensation, and closure until authoritative reconciliation |
| `P7-G6` | Redis lock plus ActionRecord protects the current call window | no exactly-once external-effect claim |
| `P7-G7` | closure/evaluation ordering exists in Java, not a Workflow receipt chain | no Outcome process-completion claim |
| `P7-G8` | existing UI/history readers cover legacy facts only | Phase 7 compatibility parity is unproven |
| `P7-G9` | no signed synthetic no-op trace, timer race, response-loss, compensation, or replay evidence | no Phase 7 engineering checkpoint or promotion claim |

`P7-G0` and `P7-G1` close only the contract-entry prerequisites. They close no Phase 7 product,
runtime, migration, reliability, replay, privacy, effect, compensation, UI, or promotion gap.

## Non-Authorization Statement

This inventory adds no product source, migration, runtime selector, Workflow, Activity, graph,
checkpoint, tool adapter, receipt, compensation, UI behavior, external call, real-data permission,
deployment, canary, promotion, or production evidence. The accepted candidate contains only entry
contracts, synthetic-no-op schema/fixtures, plans, evidence tooling, and static tests. P7.0 entry
explicitly closes no product implementation gap. `MIG-006: PENDING_PROMOTION`, `MIG-007:
PENDING_PROMOTION`, and `formal_outcome_selector: LEGACY` remain authoritative; all formal, real
effect/data/shadow, production, canary, and promotion paths remain forbidden.
