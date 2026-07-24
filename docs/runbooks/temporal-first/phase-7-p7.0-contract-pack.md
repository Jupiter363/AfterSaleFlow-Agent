# Phase 7 P7.0 Outcome Contract Pack

## Entry State And Authorization

```text
document_status: P7_0_CONTRACT_CANDIDATE
contract_gate: P7.0 NOT_RUN
contract_candidate_state: CONTRACT_CANDIDATE_READY
phase_6_candidate_sha: ea046eae2792cd5afb9929bca40da8fb8c77a9bd
phase_6_evidence_sha: e674263e9026e3fec46ec295767d432807f5ab44
accepted_phase_6_checkpoint_sha: d18a1f130a925429e8c2dfd11352cea4ca8673a0
phase_6_engineering_checkpoint: PASS
phase_7_engineering_exception: ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY
current_permission: PHASE_7_ENGINEERING_ONLY
next_phase_permission: PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS
entry_pass_token: P7_0_ENGINEERING_ENTRY_PASS
implementation: BLOCKED
engineering_execution: BLOCKED
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

This pack is contract-candidate material only. It neither authorizes Phase 7 implementation nor
proves P7.0 passed. Batch 0 must run from the exact clean contract-candidate SHA, and a separate
evidence commit must authenticate the reports. Only that separate commit may realize
`P7_0_ENGINEERING_ENTRY_PASS`; the candidate cannot attest itself.

The governing sources are:

- `plans/temporal-langgraph-room-refactor.md` section 7.8;
- `docs/acceptance/current-room-function-baseline.md`, especially `DRF-001..006`,
  `REV-001..012`, and `OUT-001..007`;
- `docs/acceptance/temporal-first-agent-platform-verification-checklist.md`;
- `docs/architecture/adr/0001-process-domain-cognitive-authority.md`;
- `docs/architecture/adr/0005-versioning-cutover-and-rollback.md`;
- `docs/architecture/adr/0016-phase-7-outcome-engineering-exception.md`; and
- `docs/runbooks/temporal-first/phase-6-engineering-checkpoint.md`.

## Authority And Scope

Java and Domain PostgreSQL are the sole formal business truth. They alone authorize and persist
ReviewPacket, reviewer assignment, decision, approval, execution plan, ActionRecord, receipt,
compensation, case status, closure, evaluation, audit, and query facts. Temporal may eventually own
time, waits, retry and compensation scheduling, and ordering, but never bypasses a Java invariant.
LangGraph supplies a private bounded proposal based on one frozen packet. LCEL supplies the typed
model object flow. Neither can approve, execute, compensate, close, or evaluate formally.

The current formal selector remains `LEGACY`. After P7.0 passes, engineering-only code may run only
as `DISABLED` or `JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW`. A no-op shadow observation is not an Outcome
allocation, a tool receipt, a formal Workflow result, or promotion evidence.

## Frozen Human Decision Contract

The accepted decision vocabulary contains exactly five values:

| Decision | Java-authoritative result | Execution eligibility |
| --- | --- | --- |
| `APPROVE` | immutable human decision adopting the frozen plan and action hash exactly | eligible for later deterministic execution validation |
| `MODIFY_AND_APPROVE` | immutable human decision plus a bounded real plan diff; immutable packet fields cannot change | eligible only for the newly hashed approved plan |
| `REQUEST_MORE_EVIDENCE` | server-owned return-to-evidence/manual follow-up state | forbidden |
| `REJECT` | server-owned rejection/manual terminal state | forbidden |
| `ESCALATE_MANUAL` | server-owned manual escalation state | forbidden |

Every human decision requires an authorized assigned reviewer, reason, confirmation, packet
version, packet hash, action hash, decision idempotency key, and immutable Java receipt. A repeated
key and identical hash returns the original receipt; a changed hash conflicts. A different reviewer
may read the task but cannot submit its decision.

An SLA timeout never maps to `APPROVE` or `MODIFY_AND_APPROVE`. It cannot fabricate a reviewer or an
ApprovalRecord. Temporal may request a Java SLA-escalation receipt whose semantic disposition is
`ESCALATE_MANUAL`; that receipt produces no action and remains distinguishable from a human
decision. At an equal timestamp, Temporal History orders Timer and reviewer command, while Java's
unique decision/escalation fence and monotonic event sequence preserve one authoritative outcome.

## Frozen Process Ordering

The future engineering kernel models this order without making it formal:

```text
HEARING_V2_HANDOFF_RECEIVED
REVIEW_PACKET_FROZEN
REVIEW_WAIT_OPEN
HUMAN_DECISION_OR_SLA_ESCALATION_RECORDED
APPROVED_OPERATIONS_RESERVED (approval decisions only)
OPERATIONS_TERMINAL_OR_MANUAL_RECOVERY
COMPENSATIONS_TERMINAL_OR_MANUAL_RECOVERY (when required)
CASE_CLOSED
CLOSED_SNAPSHOT_EVALUATED
```

No step is inferred from model text, SSE completion, or an HTTP success alone. Each step advances
only from an immutable Java receipt with matching case, epoch, fence, revision, operation key, and
request hash. `REQUEST_MORE_EVIDENCE`, `REJECT`, `ESCALATE_MANUAL`, and SLA escalation never enter
the operation stages. Evaluation provider failure cannot reopen the case or change the decision.

## Reserved Contract Set

The following names are reserved for post-entry engineering. They are not admitted to a runtime
selector merely by appearing here:

| Contract | Purpose |
| --- | --- |
| `outcome-workflow-input.v1` | V2 handoff ref, case/epoch/fence, absolute SLA deadline, mode, and immutable version pins |
| `outcome-review-command.v1` | start/wait/decision command ref and expected Java revision |
| `outcome-review-receipt.v1` | packet, assignment, decision or SLA-escalation fact reference and hash |
| `outcome-graph-command.v1` | Java-signed capability for one frozen ReviewPacket and one reviewer thread |
| `outcome-graph-result.v1` | bounded read-only answer/proposal with cited packet refs and checkpoint identity |
| `outcome-operation-command.v1` | approved action ref, operation key, request hash, retry class, and no-op marker |
| `outcome-operation-receipt.v1` | Java-persisted terminal or nonterminal `AMBIGUOUS` external-result reference and response hash |
| `outcome-compensation-receipt.v1` | compensation operation and immutable parent operation/receipt reference |
| `outcome-closure-receipt.v1` | Java closure fact and immutable closed-snapshot ref/hash |
| `outcome-shadow-observation.v1` | signed synthetic expected/actual ordering with explicit zero-effect proof |

Every boundary is closed-schema and size-bounded. Payloads contain immutable refs and hashes, not
ReviewPacket bodies, credentials, arbitrary URLs, tool clients, external response bodies, prompts,
private reasoning, or Java entities. Unknown versions and fields fail closed.

## Private Review Graph And LCEL

The reserved Graph identity is `outcome/review.v1`. It is private to the authorized reviewer,
ReviewTask, frozen packet version, and room epoch. Its input is a Java-minted capability containing
only authorized immutable Artifact references. Its State Lens resolves those references without
Domain credentials and rejects post-freeze, cross-case, cross-tenant, cross-reviewer, expired, or
hash-mismatched data.

The graph may route bounded packet Q&A and critique nodes, checkpoint their private cognitive
state, and return a typed advisory result. It has no tool node or tool capability. Its terminal
values cannot contain `approved=true`, an executable command, a case transition, or a purported
human decision receipt. Java validates all citations and output limits before exposing an answer.

LCEL is `prompt | model | parser` under immutable prompt/model/schema/policy/guardrail pins and
bounded Runnable configuration. Parser failure, provider failure, unsafe output, missing citation,
or unknown route returns an explicit non-final failure. No fallback may manufacture an approval or
execution plan.

## V045 Additive Reservation

`V045__outcome_operation_receipt_compensation.sql` is the only Phase 7 migration reservation. Its
post-entry implementation may add:

- a fenced Outcome process projection keyed by case and epoch;
- a unique operation ledger keyed by case, epoch, operation key, and request hash;
- immutable external receipt references and controlled terminal or nonterminal `AMBIGUOUS`
  classifications; and
- compensation operations with immutable parent operation and receipt links.

V045 must be additive. It cannot rewrite or delete historical ReviewPacket, ReviewTask,
ApprovalRecord, ActionRecord, evaluation, or audit rows. Existing ActionRecord remains a formal
Java ledger; a new operation row coordinates process attempts and may reference it, but cannot
become a second business truth. Foreign keys, uniqueness, check constraints, append-only receipt
guards, epoch/fence CAS, and request-hash conflicts must be enforced in PostgreSQL and Java.

No V045 file or product migration test is authorized until `P7_0_ENGINEERING_ENTRY_PASS`.

## Tool Operation, Receipt, And Compensation Rules

Each external effect has one stable logical operation key and one approved request hash. Before an
Activity can invoke an adapter, Java revalidates reviewer authority, immutable approval, packet and
plan version/hash, action allowlist, expiry, case/epoch/fence, and operation ownership. The adapter
receives the same external idempotency key where the provider supports it.

Retry classification is explicit:

- pre-invocation validation and policy failures are non-retryable and produce no effect;
- known transient pre-effect failures may use bounded retry;
- timeout or lost response after possible invocation is `AMBIGUOUS`; it remains operation-
  nonterminal and closure-blocking, and permits neither blind retry nor compensation;
- an authoritative provider receipt query or Java reconciliation receipt must resolve
  `AMBIGUOUS` before any next external effect is scheduled;
- a provider without idempotency and status-query support cannot be blindly retried; and
- irreversible effects or an `AMBIGUOUS` result that cannot be resolved automatically require
  manual reconciliation; its Java receipt must resolve the operation before any recovery effect.

A compensation is a separate approved operation with its own key, request hash, receipt, and
immutable parent. It cannot be scheduled while the parent operation is `AMBIGUOUS`; authoritative
receipt query or reconciliation must resolve the parent first. Compensation never deletes or edits
the original external receipt. Compensation failure is visible and blocks automatic closure unless
the policy explicitly records a Java-authoritative manual-recovery terminal receipt.

## Synthetic No-Op Shadow Contract

`JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW` requires all of the following:

```text
synthetic: true
effect_mode: NOOP
java_signature_verified: true
production_reference_count: 0
external_adapter: SYNTHETIC_NOOP_ONLY
external_effect_performed: false
formal_fact_written: false
```

The no-op adapter cannot resolve DNS, open an external socket, use a production credential, call a
tool registry entry, emit a real notification, or mint a provider-looking receipt. The observation
is stored only in the bounded engineering shadow ledger. Missing or inconsistent markers fail
closed before scheduling.

## Closure And Evaluation

Java may commit closure only after matching the approved action multiset to Java-authoritative
operation and ActionRecord terminal receipts. Required successful actions must be `SUCCEEDED`; any
required compensation must be terminal; `AMBIGUOUS`, running, failed-without-disposition, or
mismatched facts block closure. `AMBIGUOUS` is operation-nonterminal and cannot be bypassed by blind
retry or compensation. Decisions that never authorize execution follow their server-owned terminal
or manual route and cannot be represented as successfully executed.

The immutable closed snapshot is minted after `CLOSED` commits. Evaluation starts afterward, reads
only that snapshot, and returns a typed offline analysis. Evaluation failure changes only the
evaluation trace status. It cannot modify online case state, reviewer facts, approved plans,
operations, receipts, rules, prompts, or process history.

## DRAFT, Review, And OUTCOME Compatibility

`RoomType` remains exactly `INTAKE`, `EVIDENCE`, `HEARING`, and `REVIEW`. `DRAFT` and `OUTCOME` are
routes/query projections. Existing history readers remain supported:

- Draft continues to show non-final V2 and historical structures without implying execution.
- Review remains reviewer-only, with frozen packet visibility, assigned-writer enforcement,
  read-only access for another reviewer, and history-mode control fencing.
- Outcome remains read-only, with the human-confirmed/approved gate and the four sections for V2,
  human decision, approved plan, and execution.
- Real ActionRecord failure or future `AMBIGUOUS` states cannot be covered by animation. When no
  real receipt exists, any UI animation remains explicitly synthetic and is not a formal execution
  fact.

## Replay, Privacy, And Rollback

Workflow histories pin workflow/activity names, schemas, mode, epoch, fence, build, graph, prompt,
model, parser, policy, guardrail, tool capability, and adapter versions. Replay uses deterministic
time and version markers. Active histories are never reinterpreted by a new worker build.

Temporal stores only bounded refs/hashes/statuses. Review graph checkpoints are reviewer-private
and cannot leak another reviewer's questions or model reasoning. Domain and Graph credentials stay
separate. Synthetic fixtures contain no real tenant, case, actor, packet, action, or object-store
reference.

Rollback stops new engineering starts, disables the no-op worker, preserves immutable Java facts,
and reconciles revisions. It never erases a human decision, ActionRecord, receipt, compensation, or
evaluation trace. A different writer can start only in a new epoch with a higher fence at an
approved boundary; the legacy inline path cannot take over an existing operation key.

## P7.0 Batch 0 And Gate

Batch 0 is static and contract-only. It must prove, from the exact clean candidate SHA:

1. the `C6/E6/A6` ancestry and file-change boundaries;
2. exact status tokens and the accepted ADR 0016 engineering exception;
3. all five decisions and the timeout-never-approves rule;
4. the Java/Temporal/Graph/LCEL writer split;
5. the private no-tool `outcome/review.v1` graph boundary;
6. additive V045 reservation and operation/receipt/compensation parent rules;
7. no-op synthetic zero-effect markers;
8. closure-before-read-only-evaluation ordering;
9. DRAFT/OUTCOME projection, replay, privacy, and rollback invariants; and
10. absence of Phase 7 product source, migration, runtime selector, or activation changes.

The evidence generator records candidate SHA, commands, exit codes, test counts, raw reports,
manifest hashes, and a candidate tree inventory. Reports from a dirty tree, a different SHA, or an
earlier partial run are not reusable.

## Non-Authorization Statement

This contract pack closes no Phase 7 implementation gap. It adds no runtime, V045 migration,
schema, fixture, selector, Workflow, graph, tool adapter, receipt, compensation, UI behavior,
real-data permission, external effect, deployment, canary, promotion, or production evidence.
`P7.0 NOT_RUN` remains authoritative until a separate evidence commit realizes
`P7_0_ENGINEERING_ENTRY_PASS` for this exact candidate.
