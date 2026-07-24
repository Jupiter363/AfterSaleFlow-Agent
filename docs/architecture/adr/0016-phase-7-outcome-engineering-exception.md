# ADR 0016: Phase 7 Outcome Engineering Exception

- Status: ACCEPTED FOR ENGINEERING ONLY
- Date: 2026-07-24
- Scope: Phase 7 Review, Outcome, execution, closure, and evaluation engineering
- Approval: accepted Phase 6 engineering checkpoint plus repository-owner direction to prepare P7.0

## Context

The long-term plan makes `MIG-006=PASS` the normal Phase 7 entry condition. The accepted Phase 6
chain instead records an engineering checkpoint while keeping promotion pending:

```text
C6: ea046eae2792cd5afb9929bca40da8fb8c77a9bd
E6: e674263e9026e3fec46ec295767d432807f5ab44
A6: d18a1f130a925429e8c2dfd11352cea4ca8673a0
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_7_ENGINEERING_ONLY
MIG-006: PENDING_PROMOTION
```

Repository-only work cannot prove production promotion, external-tool idempotency, or real-effect
recovery. At the same time, production-shaped evidence cannot be collected safely until the
disabled Outcome process kernel, Java ledgers, private review graph, and no-op verification harness
exist. As in ADRs 0011, 0012, and 0015, this ADR breaks that circular dependency without weakening
the promotion lane.

## Decision

Phase 7 engineering may start only after a separate P7.0 entry-evidence commit records exact-SHA
Batch 0 `PASS` for the P7.0 contract candidate. Only that evidence commit may realize
`P7_0_ENGINEERING_ENTRY_PASS`. At this ADR's contract-candidate state, P7.0 is `NOT_RUN` and all
product implementation is blocked.

The engineering lane is constrained as follows:

- The formal Outcome selector remains `LEGACY`. No case may receive a Temporal Outcome allocation,
  formal Outcome Workflow, or formal graph sink under this exception.
- After `P7_0_ENGINEERING_ENTRY_PASS`, new engineering runtime is limited to `DISABLED` or isolated
  `JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW`. It may use only generated non-production fixtures and a
  Java-signed no-op effect adapter that proves `external_effect_performed=false`.
- Java and Domain PostgreSQL remain the sole formal business truth and writers for ReviewPacket,
  ReviewTask, reviewer authorization, human decision, approval policy, ActionRecord, external
  receipt, compensation fact, case status, closure, evaluation trace, audit, and query projection.
- Temporal engineering owns future process ordering, waits, absolute review deadlines, retries,
  compensation scheduling, closure ordering, and failure classification. Under this exception it
  is a deterministic test kernel only and cannot advance a formal case or invoke a real tool.
- LangGraph owns private bounded cognition for `outcome/review.v1` only. The graph reads one frozen,
  Java-authorized ReviewPacket snapshot, has no tool capability, and cannot approve, reject,
  escalate, execute, compensate, close, evaluate, or write a Domain fact.
- LangChain Core and LCEL own typed Prompt, Message, ChatModel, Parser, callback, and Runnable object
  flow. A model result is advisory and cannot become a human decision or execution command.
- Vue remains presentation-only. `DRAFT` and `OUTCOME` stay routes/query projections and are not
  added to `RoomType`.

The five and only five human decision values are frozen as `APPROVE`, `MODIFY_AND_APPROVE`,
`REQUEST_MORE_EVIDENCE`, `REJECT`, and `ESCALATE_MANUAL`. Only an authorized human reviewer may
create an immutable decision receipt. A review SLA timeout never approves and never fabricates a
human decision; it requests Java to persist a manual-escalation fact with `ESCALATE_MANUAL`
semantics and produces no execution operation.

`V045__outcome_operation_receipt_compensation.sql` is reserved as an additive migration. It may
add fenced Outcome projection and operation/receipt/compensation ledgers, but it may not rewrite or
delete historical ReviewPacket, ApprovalRecord, ActionRecord, or evaluation facts. Its product
implementation remains blocked until P7.0 passes.

Closure ordering is fixed: all approved operations must reach a Java-authoritative terminal state;
required compensations must also reach a terminal state or explicit manual recovery; Java then
commits `CLOSED`; only afterward may evaluation read the immutable closed snapshot. Evaluation is
read-only and cannot reopen a case, alter a decision, execute a tool, or write process state.

## Gate Record

```yaml
phase_6_candidate_sha: ea046eae2792cd5afb9929bca40da8fb8c77a9bd
phase_6_evidence_sha: e674263e9026e3fec46ec295767d432807f5ab44
accepted_phase_6_checkpoint_sha: d18a1f130a925429e8c2dfd11352cea4ca8673a0
phase_6_engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_7_ENGINEERING_ONLY
phase_7_engineering_exception: ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY
contract_gate: P7.0 NOT_RUN
contract_candidate_state: CONTRACT_CANDIDATE_READY
p7_0_entry_gate: AWAITING_EXACT_SHA_BATCH_0
entry_pass_token: P7_0_ENGINEERING_ENTRY_PASS
next_phase_permission_after_gate: PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS
implementation: BLOCKED
product_implementation: BLOCKED
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
formal_outcome_selector: LEGACY
allowed_runtime_modes: [DISABLED, JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW]
temporal_outcome_allocation: FORBIDDEN
formal_outcome_workflow: FORBIDDEN
real_tools: FORBIDDEN
real_external_effects: FORBIDDEN
real_data: FORBIDDEN
real_case_shadow: FORBIDDEN
production_traffic: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

Any pin substitution, upstream contradiction, dirty candidate, reused partial report, or evidence
committed with the candidate fails closed. The contract candidate cannot mark its own Batch 0 as
passed.

## Forbidden Under This Exception

This ADR does not authorize any of the following:

- `TEMPORAL` Outcome allocation or a formal `OutcomeRoomWorkflow`;
- a formal `outcome/review.v1` sink or any Agent approval/execution authority;
- real case, tenant, reviewer, party, packet, action, or production-object data in shadow;
- real tools, external calls, money movement, fulfillment, notifications, or other effects;
- a synthetic adapter that can reach a production endpoint or return a real external receipt;
- production traffic, canary, promotion, secrets, IAM changes, or deployment changes; or
- declaring `MIG-006` or `MIG-007` passed.

## Replay, Failure, Privacy, And Rollback

Workflow code must be deterministic and version-pinned. Histories carry bounded IDs, hashes,
deadlines, revisions, fences, statuses, and receipt references, not ReviewPacket bodies, prompts,
private reasoning, tool credentials, or external response bodies. Same-time reviewer submission and
SLA timeout follow Temporal History order, but Java authorization, immutable decision uniqueness,
and monotonic event sequence decide which formal fact exists. A late timeout cannot replace a
committed decision, and a late decision cannot silently replace a committed manual escalation.

Every operation uses a stable operation key and request hash. Completion loss returns the existing
Java receipt; the same key with a different hash is a conflict. When an invocation may have reached
the provider but has no authoritative result, the operation is `AMBIGUOUS`: it is nonterminal,
blocks closure, and permits neither blind retry nor compensation. An authoritative provider receipt
query or Java reconciliation receipt must resolve it before any next external effect is scheduled.
A compensating action has its own operation key and immutable parent receipt. Irreversible effects
or an `AMBIGUOUS` result that cannot be resolved automatically require manual reconciliation. Its
Java receipt must resolve the operation before any later recovery effect or processing.

Review graph checkpoints and traces are private to the authorized reviewer and frozen packet.
They cannot be read by another actor as a substitute for the shared ReviewPacket projection, and
they never contain credentials or unrestricted URLs. Synthetic observations contain no real case
or actor identifiers.

Rollback stops new engineering starts, disables the synthetic worker, drains no-op activities, and
preserves every Java ledger and test history. An active epoch is never transferred in place. A
future recovery starts with a higher epoch/fence at an approved boundary; an old inline path cannot
take over an operation key already owned by another epoch.

## Consequences

After a separate exact-SHA P7.0 evidence commit realizes `P7_0_ENGINEERING_ENTRY_PASS`, the team may
implement and verify disabled Outcome workflow code, the private read-only review graph, additive
V045 ledgers, no-op tool activities, compensation scheduling, and closure/evaluation ordering. It
may not make those paths formal or produce a real external effect under this ADR.
