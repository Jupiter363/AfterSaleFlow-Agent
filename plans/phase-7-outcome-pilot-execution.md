# Phase 7 Outcome Engineering Pilot Execution Plan

## Status

```text
plan_status: P7_0_PASS_ENGINEERING_ACTIVE
contract_gate: P7.0 PASS
engineering_execution: ALLOWED_UNDER_ADR_0016_ENGINEERING_RESTRICTIONS
phase_7_engineering_state: BATCHES_1_AND_2_PASS_AWAITING_C7_ENG_FREEZE
batch_1_foundation: PASS
batch_2_integration: PASS
batch_3_candidate_tooling: IMPLEMENTED_CHECKPOINT_NOT_RUN
phase_7_engineering_candidate_C7_eng: NOT_FROZEN
phase_7_engineering_evidence_E7_eng: NOT_CREATED
phase_7_checkpoint_A7: NOT_CREATED
phase_7_engineering_checkpoint: NOT_RUN
accepted_phase_7_candidate_C7: 0aa260f722fced0eba4314bd4793e415b5bf0b05
accepted_phase_7_evidence_E7: e29cefb3e028bb84f6a227e46fecdf5711eba48c
phase_7_entry_release: phase-7-entry-20260724-0aa260f7
phase_7_entry_evidence_path: test-reports/temporal-first/phase-7-entry-20260724-0aa260f7/phase-7-entry
phase_7_entry_source_counts: static=78 python=3 java=18 frontend=41 total=140
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

Phase 6 acceptance `A6` permitted only a Phase 7 engineering entry process. Contract candidate
`C7=0aa260f722fced0eba4314bd4793e415b5bf0b05` subsequently passed all four exact-SHA Batch 0
sources: 78 static, 3 Python, 18 Java, and 41 frontend tests, 140 total. Direct-child evidence
commit `E7=e29cefb3e028bb84f6a227e46fecdf5711eba48c` authenticates the reports under release
`phase-7-entry-20260724-0aa260f7` and realizes `P7_0_ENGINEERING_ENTRY_PASS`.

This releases A-G for Phase 7 engineering implementation under ADR 0016. It does not satisfy the
master plan's production entry condition `MIG-006=PASS`, authorize a formal Outcome Workflow, or
make `MIG-006` or `MIG-007` pass.

Phase 7 implementation has passed Batch 1 foundations and Batch 2 integration preflight. The Batch 3
runner, evidence generator, and their focused static contract tests are implemented, but no
engineering candidate has been frozen. Consolidated candidate-bound P0 review, the exact-SHA
four-source Batch 3 run, and the engineering checkpoint remain unrecorded.

This plan is used with [the Phase 7 test matrix](./phase-7-outcome-pilot-test-batches.yaml),
[the Phase 7 owner briefs](./phase-7-owner-briefs.yaml), section 7.8 of the
[master refactor plan](./temporal-langgraph-room-refactor.md), the current-room behavior baseline,
and the platform acceptance checklist. ADR 0016 supplies only the bounded engineering exception;
the accepted contract pack, candidate, and evidence commit define the active engineering boundary.

## Engineering Boundary

### Active Engineering Goals

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

## Accepted P7.0 Exact-Candidate Gate

### Accepted Upstream Evidence

The immutable upstream chain is:

```text
C6 = ea046eae2792cd5afb9929bca40da8fb8c77a9bd
E6 = e674263e9026e3fec46ec295767d432807f5ab44
A6 = d18a1f130a925429e8c2dfd11352cea4ca8673a0
```

`A6` records `PHASE_7_ENGINEERING_ONLY`; it is not a promotion receipt. The accepted `C7/E7` pair
verifies this exact chain and `ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY`, freezes the exception,
contract pack, owner briefs, and machine schedule, and keeps Phase 7 product implementation and
V045 out of the candidate commit.

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

Before the accepted `C7/E7` pair, the mandatory fail-closed state was:

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

The accepted entry record is:

```text
contract_gate: P7.0 PASS
entry_decision: ENTRY_EVIDENCE_ACCEPTED
candidate: 0aa260f722fced0eba4314bd4793e415b5bf0b05
evidence: e29cefb3e028bb84f6a227e46fecdf5711eba48c
source_counts: static=78 python=3 java=18 frontend=41 total=140
implementation_owners: A-G READY
```

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
implementation the primary controls a ceiling of two isolated Maven/Testcontainers processes, one
P0 review process, and two light processes. Roles activate only when a stable diff or runnable shard
exists; they are logical responsibilities, not permanently occupied agent slots. Batch 0 is the
narrower sequential exception described above and never uses more than one heavy process.

## Batched Verification

- **Batch 0:** entry baseline only, exact candidate SHA, four source commands, no implementation.
- **Batch 1 - PASS:** owner foundations after P7.0 PASS; pure Workflow, Java
  authority, migration contract, no-op execution, Graph, and focused frontend units.
- **Batch 2 - PASS:** integrated operation/receipt/compensation, reviewer race,
  closure/evaluation ordering, compatibility readers, and synthetic recovery.
- **Batch 3 - tooling implemented, checkpoint not run:** one future frozen Phase 7 engineering
  candidate, four exact-SHA sources, candidate-bound P0 disposition, and immutable evidence.
- **Unified/promotion:** deferred. Real tools, real data, canary, load, chaos, production, `MIG-006`,
  and `MIG-007` cannot be passed by Phase 7 synthetic evidence.

No later engineering batch currently claims PASS. Exact commands and resource controls remain
authoritative in the machine-readable test matrix.

## Batch 3 Engineering Candidate Tooling

`P7-R3` owns fail-closed shared assembly and candidate preparation. The implemented checkpoint
tooling is candidate content, not evidence that the implementation or tests passed:

- runner: `scripts/run_phase7_candidate_checkpoint.py`
- evidence generator and post-commit verifier: `scripts/generate_phase7_candidate_evidence.py`
- runner contract tests: `tests/static/test_phase7_candidate_runner.py`
- evidence contract tests: `tests/static/test_phase7_candidate_evidence.py`

After Batch 1, Batch 2, and shared assembly are independently closed, `P7-R3` may freeze exactly one
clean engineering candidate `C7-eng`. `P7-R4` then runs these four sources sequentially from a clean
detached worktree at that exact SHA:

| Ordered source | Exact selector | Normalized artifact | Raw provenance |
| --- | --- | --- | --- |
| `static_phase7_candidate` | nine Phase 7 entry, contract, plan, router, candidate, and traceability static files frozen in the matrix | `static-phase7-candidate.xml` | one pytest JUnit plus stdout/stderr |
| `python_phase7_candidate` | `tests/graphs/outcome`, `tests/agents/test_review_copilot.py`, `tests/test_evaluation.py` | `python-phase7-candidate.xml` | one pytest JUnit plus stdout/stderr |
| `java_phase7_candidate` | the 24 exact Maven classes frozen in the matrix, including `JdbcOutcomeOperationLedgerTest` | `java-phase7-candidate.xml` | 24 suffixed Surefire XML files plus stdout/stderr |
| `frontend_phase7_candidate` | the six Draft, Outcome, Review, and review API Vitest files frozen in the matrix | `frontend-phase7-candidate.xml` | one Vitest JUnit plus stdout/stderr |

The sealed execution manifest retains source argv, contract, environment, source-tree, stdout,
stderr, raw-report, normalized-report, and SHA-256 bindings. The evidence bundle copies all four
normalized reports and maps every accepted or quarantined raw JUnit/stdout/stderr artifact through
`provenance-manifest.json`; normalized testcase fingerprints must replay exactly from the retained
raw provenance.

Evidence generation also requires a separately authored, explicitly supplied external absolute-path
`p0-review-disposition.json`. Its source must be a regular no-follow file outside the candidate
workspace, run directory, evidence output, and staging directory, and it cannot be candidate-tracked.
The generator snapshots it before assembly and rejects source-byte or identity drift. The document has
schema `phase7-p0-review-disposition.v1`, the exact `C7-eng` SHA, review scope
`CONSOLIDATED_POST_INTEGRATION_P0_ONLY`, all three frozen P0 topics, status `ALL_P0_CLOSED`, zero
open P0 findings, and sorted unique closed finding IDs. This input is not inferred from runner
green status and cannot be reused for another candidate.

The engineering checkpoint uses a three-commit topology:

1. **`C7-eng`:** exact integrated engineering candidate, including the candidate runner, generator,
   static tooling contracts, and the additive V045 implementation.
2. **`E7-eng`:** evidence-only sole-parent direct child of `C7-eng`; it adds only the immutable
   `test-reports/temporal-first/<release>/phase-7-candidate/**` bundle authenticated by the generator.
3. **`A7`:** checkpoint-only sole-parent direct child of `E7-eng`; it records acceptance of the
   verified `C7-eng/E7-eng` chain and no broader runtime or promotion authority.

The runner can report only `PHASE_7_ENGINEERING_SOURCES_GREEN_AWAITING_SEPARATE_EVIDENCE`.
`E7-eng` must be verified as the sole-parent evidence child before `A7`; only `A7` may record the
accepted Phase 7 engineering checkpoint and at most `PHASE_8_ENGINEERING_ONLY`. Throughout all
three commits, `MIG-006` and `MIG-007` remain `PENDING_PROMOTION`, and formal activation, Temporal
Outcome allocation, real effects, real-case shadow, canary, and promotion remain forbidden.

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
