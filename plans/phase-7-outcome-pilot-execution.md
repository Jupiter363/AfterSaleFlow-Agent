# Phase 7 Outcome Engineering Pilot Execution Plan

## Status

```text
plan_status: P7_0_CONTRACT_CANDIDATE_PREPARATION
contract_gate: P7.0 NOT_RUN
engineering_execution: BLOCKED
accepted_phase_6_candidate_C6: ea046eae2792cd5afb9929bca40da8fb8c77a9bd
accepted_phase_6_evidence_E6: e674263e9026e3fec46ec295767d432807f5ab44
accepted_phase_6_checkpoint_A6: d18a1f130a925429e8c2dfd11352cea4ca8673a0
phase_6_engineering_checkpoint: PASS
next_phase_permission: PHASE_7_ENGINEERING_ONLY
phase_7_engineering_exception: ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
formal_outcome_writer: LEGACY_JAVA
allowed_new_runtime: DISABLED or JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW
outcome_temporal_allocation: FORBIDDEN
formal_outcome_workflow_activation: FORBIDDEN
real_tool_effects: FORBIDDEN
real_or_party_data_shadow: FORBIDDEN
production_traffic: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

Phase 6 acceptance `A6` permits only a Phase 7 engineering entry process. It does not satisfy the
master plan's production entry condition `MIG-006=PASS`, does not authorize a formal Outcome
Workflow, and does not make `MIG-006` or `MIG-007` pass. Phase 7 implementation remains blocked
until a contract-candidate commit is frozen, all four Batch 0 source commands pass from a clean
detached worktree at that exact SHA, and a separate direct-child evidence commit records P7.0
`PASS`.

This plan is used with [the Phase 7 test matrix](./phase-7-outcome-pilot-test-batches.yaml),
[the Phase 7 owner briefs](./phase-7-owner-briefs.yaml), section 7.8 of the
[master refactor plan](./temporal-langgraph-room-refactor.md), the current-room behavior baseline,
and the platform acceptance checklist. ADR 0016 supplies only the bounded engineering exception;
the contract pack must be part of the contract candidate. Until P7.0 passes, this document is
planning only.

## Engineering Boundary

### Goals After P7.0 Passes

- Build an unreachable, deterministic `OutcomeRoomWorkflow` kernel for review wait/SLA, five human
  decisions, synthetic no-op execution/compensation, closure, and evaluation ordering.
- Preserve Java and Domain PostgreSQL as the sole formal authority for frozen `ReviewPacket`, human
  decisions, action snapshots, operation/receipt/compensation records, case closure, and evaluation.
- Add an additive V045 engineering ledger for operation identity, request hash, external key,
  receipt, compensation parentage, fence, and replay-safe projection.
- Move review assistance behind a private, read-only `outcome.review.v1` LangGraph using bounded
  state and governed `prompt | model | parser` LCEL, with no tool capability.
- Preserve Draft, Review queue/workbench, and Outcome API/UI compatibility, including legacy
  readers and the explicit simulated label when no real `ActionRecord` exists.
- Produce exact-SHA engineering evidence without activating a formal writer or external effect.

### Non-Goals And Hard Prohibitions

- No product/runtime implementation before the separate P7.0 evidence commit records exact-SHA
  Batch 0 `PASS`.
- No `TEMPORAL` Outcome epoch allocation, worker registration, formal workflow start, selector
  admission, or formal Graph sink during Phase 7 engineering.
- No real case, party, reviewer, production-like, or unsigned shadow input. Synthetic fixtures must
  be Java-signed and resolve to no Domain case.
- No network, payment, refund, inventory, notification, account, or other real tool side effect.
  Shadow tools are deterministic no-op stubs and cannot hold production credentials.
- No Agent approval, rejection, escalation, modification, execution, compensation, or closure
  authority. The private Graph is advisory and read-only.
- No automatic approval on review timeout. High-risk timeout can only produce an escalation or
  manual-attention proposal for later Java validation.
- No DRAFT or OUTCOME `RoomType`, route redesign, removal of legacy readers, or inference of formal
  success from model text or animation.
- No change to active legacy outcome ownership, no in-place epoch migration, and no implicit
  fallback from a future Temporal epoch to legacy execution.
- No canary, promotion, secret, deployment, production traffic, or claim that `MIG-006` or
  `MIG-007` passed.

## Authority And Mode Contract

| Concern | Sole authority in Phase 7 engineering | Durable truth | Forbidden behavior |
| --- | --- | --- | --- |
| Current formal review/outcome | Existing Java services | Existing Domain PostgreSQL rows and events | Engineering kernel becoming reachable or a second writer |
| Future process ordering | Unregistered Temporal kernel source only | Test History and synthetic refs | Worker registration, real allocation, or formal command delivery |
| ReviewPacket and human decision | Java review application/domain | Immutable packet and actor-bound decision ledger | Agent decision, mutable packet, last-write-wins reviewer race |
| Operation/receipt/compensation | Java finalizer plus V045 ledger | Hash-bound append-only parent chain | Redis-only idempotency, blind retry, or external response as business truth |
| Model cognition | Private `outcome.review.v1` graph | Isolated Graph checkpoint and proposal refs | Tool capability, Domain credentials, raw unapproved material, formal mutation |
| Closure/evaluation | Java closure authority; evaluation is read-only | CLOSED snapshot then evaluation record | Evaluation closing/reopening a case or writing process state |
| Presentation | Java-authorized compatibility projection | Existing API shapes and server authorization | Client-owned approval/execution or simulated animation shown as real |

| Mode | Formal process owner | New engineering behavior | Phase 7 status |
| --- | --- | --- | --- |
| `LEGACY` | Existing Java review/outcome services | No new runtime | Sole formal mode |
| `DISABLED` | Existing Java remains formal | No Outcome Workflow/Graph execution | Required default |
| `JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW` | Existing Java remains formal | Isolated fixture comparison with deterministic no-op tools | Allowed only after P7.0 PASS |
| `TEMPORAL` | Future `OutcomeRoomWorkflow` | Formal Activity/Finalizer chain | Forbidden |

Unknown mode, missing signature, case-resolving input, non-no-op capability, missing version pin,
stale fence, request-hash conflict, or selector ambiguity fails closed.

## Frozen Outcome Protocol

The engineering kernel may model only the following receipt-driven order. The implementation can
use explicit states, but cannot treat them as a new `RoomType` or expose them as formal runtime:

1. Accept an exact frozen Hearing V2 handoff reference and immutable `ReviewPacket` reference.
2. Wait for an actor-bound reviewer decision until an immutable SLA deadline.
3. Resolve the deterministic race between a committed decision and the SLA timer.
4. Validate exactly one of `APPROVE`, `MODIFY_AND_APPROVE`, `REQUEST_MORE_EVIDENCE`, `REJECT`, or
   `ESCALATE_MANUAL`.
5. For `APPROVE` or valid `MODIFY_AND_APPROVE`, bind the approved action snapshot hash and operation
   keys.
6. For `REQUEST_MORE_EVIDENCE`, `REJECT`, or `ESCALATE_MANUAL`, produce no execution operation.
7. Run only synthetic no-op execution Activities and record idempotent receipts.
8. Run required synthetic compensation in reverse parent order; unresolved irreversible work enters
   explicit manual-recovery state.
9. Close only after every required operation is terminal and the Java closure receipt is committed.
10. Invoke evaluation only against the exact CLOSED snapshot; evaluation failure cannot reopen or
    rewrite the process.

Temporal History contains only IDs, enums, hashes, revisions, deadlines, operation keys, and
receipt references. It contains no packet body, reviewer rationale, prompt, model output, tool
credential, external payload, or evaluation content.

## Decision And Effect Invariants

- A `ReviewPacket` is frozen before Copilot or decision use. Later case material is not visible to
  the graph or decision unless a new formally versioned packet is created by Java.
- Every decision is bound to tenant, case, packet ID/hash/version, reviewer actor, task state,
  policy version, deadline, command ID, operation key, request hash, epoch, revision, and fence.
- The first valid actor-bound decision wins. Identical replay returns the same receipt; another
  reviewer, decision, reason, packet hash, or request body conflicts and cannot replace it.
- All five decisions require a non-empty reason and server-side authorization.
  `MODIFY_AND_APPROVE` carries a real diff and cannot change frozen packet identity fields.
- Approval transfers authority to the execution chain; it never executes a tool from the browser,
  controller, reviewer Agent, or Graph.
- An external response is not a committed effect until Java stores the validated receipt against
  the exact operation key and request hash. Lost responses replay safely.
- A tool without idempotency/status-query/compensation semantics cannot be automatically retried.
  Phase 7 engineering substitutes a deterministic no-op adapter and records the unresolved real
  capability as a promotion blocker.
- Compensation never deletes the original operation or receipt. It appends a hash-bound child and
  can enter explicit `MANUAL_RECOVERY_REQUIRED`.
- Closure precedes evaluation. Evaluation reads a CLOSED snapshot and has no workflow, decision,
  action, closure, outbox, or tool write capability.

## P7.0 Exact-Candidate Gate

### Accepted Upstream Evidence

The immutable upstream chain is:

```text
C6 = ea046eae2792cd5afb9929bca40da8fb8c77a9bd
E6 = e674263e9026e3fec46ec295767d432807f5ab44
A6 = d18a1f130a925429e8c2dfd11352cea4ca8673a0
```

`A6` records `PHASE_7_ENGINEERING_ONLY`; it is not a promotion receipt. The P7.0 candidate must
verify this exact chain and `ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY`, freeze the exception and
contract pack, bind the owner briefs
and machine schedule, and contain no Phase 7 product implementation or V045 migration.

### Two-Commit Entry Protocol

1. **P7 contract candidate:** freeze only planning, contracts, engineering exception, static gates,
   closed fixtures/schemas, owner briefs, entry runner, and evidence generator.
2. **Exact-SHA Batch 0:** run exactly four source commands sequentially from a clean detached
   worktree at the candidate SHA. The entry runner has one heavy-process ceiling because it has
   exactly one Java source; its light-process ceiling remains two even though sequential execution
   uses one source process at a time. Do not reuse a report from another SHA or attempt.
3. **Separate P7 entry-evidence commit:** direct child of the candidate, containing only immutable
   generated evidence and the entry decision. Only this commit may record
   `P7_0_ENGINEERING_ENTRY_PASS` and release owners A-G.

Until step 3 succeeds, the mandatory state is:

```text
contract_gate: P7.0 NOT_RUN
engineering_execution: BLOCKED
V045: FORBIDDEN
implementation_owners: NOT_STARTED
allowed_new_runtime: DISABLED
formal_outcome_writer: LEGACY_JAVA
```

Any product failure, fixture error, exact-SHA mismatch, dirty candidate, missing source report,
evidence-generator mismatch, or contract ambiguity blocks implementation. Infrastructure retry is
allowed only after classification and must retain a fresh attempt directory.

## Delivery Sequence

| Step | Owner | Depends on | Deliverable and boundary |
| --- | --- | --- | --- |
| P7.0 | Primary, V1, V2 | accepted `C6/E6/A6` | Contract candidate, exact-SHA four-source Batch 0, separate evidence commit; no implementation before PASS |
| P7.1 | A | P7.0 PASS | Shared Outcome wire/protocol schemas and compatibility fixtures only; no runtime implementation |
| P7.2 | B | P7.0 PASS, A protocol | Pure deterministic Outcome Workflow kernel and replay tests; no worker registration or Activity implementation |
| P7.3 | C | P7.0 PASS, A protocol | Frozen ReviewPacket and actor-bound five-decision Java authority with idempotent receipts |
| P7.4 | D | P7.0 PASS, A/C contracts | Additive V045 operation/receipt/compensation/fence ledger and persistence tests |
| P7.5 | E | P7.0 PASS, A/B/D contracts | Deterministic synthetic no-op execution, compensation, closure, and read-only evaluation ordering |
| P7.6 | F | P7.0 PASS, A/C packet contracts | Private `outcome.review.v1` graph and compatibility adapter; no tools |
| P7.7 | G | P7.0 PASS, C/E projections | Draft/Review/Outcome compatibility and simulated-vs-real presentation |
| P7.8 | Primary, R1, V1-V2 | A-G integrated | Fail-closed shared assembly, consolidated P0 review, focused recovery/parity checks, and exact-SHA evidence |

The seven implementation owners have disjoint write domains. Protocol, Python, and frontend work
are independent owners rather than sequential subscopes of one writer. The primary alone owns
global worker/config/selector/registry/router/integration files.

## Owner Topology

- **A - shared Outcome wire/protocol contracts:** new Outcome contract types, closed schemas, and
  fixtures only; no workflow, service, persistence, Graph, UI, or runtime registration.
- **B - Temporal Outcome kernel:** `workflow/temporal/room/outcome/**` and its tests only.
- **C - Java human review authority:** review API/application/domain and focused unit tests; no
  persistence migration, tool execution, Workflow, Python, or frontend.
- **D - V045 persistence ledger:** migration plus isolated execution-ledger domain/persistence and
  database contract tests; no service/controller/workflow/tool implementation.
- **E - Synthetic execution/closure/evaluation:** deterministic no-op tool Activities, outcome and
  evaluation application behavior, and focused tests; no migration/review/Graph/frontend.
- **F - private Outcome Graph:** Python `outcome/review.v1`, governed LCEL, compatibility adapter,
  and focused Python tests only.
- **G - frontend compatibility:** Vue Draft/Review/Outcome views, review API/store, and focused
  frontend tests only.
- **Primary - integration:** Temporal worker/config/selector, Graph registry/main, Vue router,
  application configuration, candidate/evidence tooling, and final merge.

Support lanes are read-only unless the primary explicitly transfers an unowned fix: exactly one
consolidated P0 review lane, two verification lanes, and one lookahead lane. During Phase 7
implementation the primary controls a combined ceiling of two Maven/Testcontainers/heavy processes
and two light processes. Roles activate only when a stable diff or runnable shard exists; they are
logical responsibilities, not permanently occupied agent slots. Batch 0 is the narrower sequential
exception described above and never uses more than one heavy process.

## Batched Verification

- **Batch 0:** entry baseline only, exact candidate SHA, four source commands, no implementation.
- **Batch 1:** owner foundations after P7.0 PASS; pure Workflow, Java authority, migration contract,
  no-op execution, Graph, and focused frontend units.
- **Batch 2:** integrated operation/receipt/compensation, reviewer race, closure/evaluation ordering,
  compatibility readers, and synthetic recovery.
- **Batch 3:** one frozen Phase 7 engineering candidate and immutable evidence from the same SHA.
- **Unified/promotion:** deferred. Real tools, real data, canary, load, chaos, production, `MIG-006`,
  and `MIG-007` cannot be passed by Phase 7 synthetic evidence.

No later batch in the planning candidate claims PASS. Exact commands and resource controls are
authoritative in the machine-readable test matrix.

## Engineering Exit And Handoff

A Phase 7 engineering checkpoint can pass only when all owned work is integrated, every P0 finding
is closed, focused tests are green on one exact SHA, evidence is archived separately, and static
gates prove the runtime remains unreachable and effect-free. Its maximum handoff is
`PHASE_8_ENGINEERING_ONLY`; it cannot authorize Temporal Outcome allocation, real tools, real-case
shadow, canary, promotion, or `MIG-007=PASS`.

The engineering checkpoint must preserve:

```text
formal_outcome_writer: LEGACY_JAVA
allowed_new_runtime: DISABLED or JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW
outcome_temporal_allocation: FORBIDDEN
formal_outcome_workflow_activation: FORBIDDEN
real_tool_effects: FORBIDDEN
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
```
