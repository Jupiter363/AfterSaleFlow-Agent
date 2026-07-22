# Phase 5 Evidence Pilot Execution Plan

## Status

```text
plan_status: DRAFT
engineering_execution: BLOCKED
contract_gate: P5.0 NOT_RUN
phase_4_engineering_checkpoint: NOT_RECORDED
promotion_gate: MIG-004 PENDING_PROMOTION
phase_5_promotion_gate: MIG-005 PENDING
next_phase_permission: BLOCKED
team_shape: primary + 5 logical delegated owners in two waves
java_evidence_ledger_writer: SOLE_FORMAL_WRITER
graph_runtime_default: DISABLED
allowed_pre_promotion_runtime: DISABLED or Java-signed synthetic SHADOW
temporal_evidence_allocation: FORBIDDEN
formal_graph_sink: FORBIDDEN
```

This is the P5.0 entry-contract draft, prepared from repository commit
`49e1c22f0e7203478cde2ea568058db86231092d`. It authorizes no Phase 5 implementation. The current
repository has not recorded the Phase 4 engineering checkpoint or `MIG-004=PASS`; the 100-file
product contract, tenant/global fan-out bulkhead, and production-shaped authorized asset boundary
also lack the approvals and evidence required by the Phase 5 source plan.

This plan is governed by the
[P5.0 Evidence Contract Pack](../docs/runbooks/temporal-first/phase-5-p5.0-contract-pack.md) and the
machine-readable [Phase 5 test batches](./phase-5-evidence-pilot-test-batches.yaml). Source authority
is `plans/temporal-langgraph-room-refactor.md` section 7.6 plus the platform acceptance checklist,
machine manifest, and current-room behavior baseline.

Implementation remains blocked until a later P5.0 contract candidate records every entry gate,
runs Batch 0 from that exact clean detached candidate SHA, and commits the resulting entry evidence
separately. No result produced from this draft may be relabeled as a P5.0 `PASS`.

## Scope

### Goals

- Define a version-pinned `evidence.v2` graph that evaluates at most 100 Java-authorized Evidence
  items using deterministic waves of `Send`, with no more than eight active items per room and
  approved tenant/global bulkheads.
- Preserve Java and Domain PostgreSQL as the sole formal writers for Evidence items, submissions,
  verifications, completion events, review queues, dossier versions, the fact-evidence matrix,
  audit, outbox, and the Hearing-opening receipt.
- Give Temporal exclusive ownership of Evidence command order, the shared two-hour deadline,
  the 30-minute warning point, party-completion waits, expiry ordering, freeze orchestration, and
  the single transition request to Hearing for a future `TEMPORAL` Evidence epoch.
- Restrict Graph output to bounded, typed assessment and matrix proposals. Java revalidates and
  merges them once after every manifest item reaches a terminal assessment.
- Freeze the manifest/hash/owner/visibility AssetLoader boundary. A proposal may claim visual
  inspection only for bytes actually loaded through an authorized capability and bound to the
  item manifest and SHA-256.
- Preserve `EVD-001..015`, `UI-001`, `UI-003..005`, `CORE-001..010`, and `SEC-001..006`, including
  actor-private views, zero-evidence respondent behavior, low-relevance semantics, active-run
  recovery, history mode, and the existing 30-minute reminder.
- Produce focused, centralized engineering evidence and one immutable Phase 5 candidate checkpoint
  suitable for a separately authorized promotion process.

### Non-Goals

- No Phase 5 source, test, migration, schema, fixture, runtime, or UI implementation is authorized
  by this draft.
- No real-case shadow, `TEMPORAL` Evidence allocation, canary, production traffic, formal Graph
  Finalizer, or claim that `MIG-004` or `MIG-005` passed.
- No Graph write to an Evidence table, verification row, dossier, matrix, completion record, room
  phase, audit row, outbox, or Hearing state.
- No Temporal or Python access to Domain repositories and no Java access to Graph checkpoint tables.
- No migration of Hearing supplementation. Its per-party batch limit remains 50, and no Hearing
  workflow, API, service, test, or UI behavior may change in Phase 5.
- No redefinition of low relevance as fabrication, no automatic rejection solely from low
  confidence, and no transfer of human-review authority to a model.
- No in-place writer-mode change for an active epoch and no simultaneous legacy and new Evidence
  timers for one case.
- No destructive edit to V001-V043_1 or historical Evidence records.

## Authority And Mode Contract

| Concern | Sole authority/writer | Phase 5 responsibility | Forbidden behavior |
| --- | --- | --- | --- |
| Evidence wait, shared deadline, warning, completion order, retry, cancellation | Temporal `EvidenceRoomWorkflow` | Consume references and committed Java receipts deterministically | Domain writes, raw evidence bytes in History, wall-clock or model calls |
| Evidence metadata/binary authorization, submissions, verifications, dossier/matrix, review queue, admission, Hearing opening | Java + Domain PostgreSQL/object store | Validate actor/epoch/fence/hash; commit facts and outbox atomically | Trust Graph/Temporal as business authority or accept stale results |
| Checkpoint, per-item cognition, bounded fan-out, deterministic proposal | Python `evidence.v2` + Graph PostgreSQL | Process authorized immutable refs and return typed proposals | Domain credentials, formal truth labels, phase transition, direct Hearing call |
| User-visible projection and stream | Java-authorized API/projection; Vue presentation | Preserve privacy, active run, timer and history behavior | Infer formal state or countdown ownership in the browser |

| Persisted Evidence epoch mode | Process writer | Formal Evidence writer | Graph use | Allowed sink |
| --- | --- | --- | --- | --- |
| `LEGACY` | Existing Evidence services plus `EvidenceWindowWorkflow` | Java | Existing Evidence clerk path | Existing Java persistence path |
| `SHADOW` | Existing legacy path | Java legacy path | Version-pinned Graph, signed synthetic inputs only under current gate | Isolated comparison ledger only |
| `TEMPORAL` | Typed Evidence child Workflow | Java Activities/Finalizer | Version-pinned Graph | Formal sink only after a separate promotion gate |

`SHADOW` must not resolve the formal Evidence Finalizer. A future `TEMPORAL` selector must fail
closed unless the room type is exactly `EVIDENCE`, all immutable versions and gate receipts match,
and the new epoch persists the full selection. Current formal Evidence traffic remains `LEGACY`.

## P5.0 Frozen Decisions

1. The logical team is one primary plus five delegated implementation owners. With four total
   active-agent slots, owners A-C run in Wave A and D-E reuse released slots in Wave B.
2. The primary owns shared contracts, exact path grants, integration order, the single heavy-test
   token, failure classification, and the final immutable candidate checkpoint.
3. One Evidence submission batch contains 1-100 unique Evidence IDs only after the product/API/UI
   approval is recorded. Existing 1-50 behavior remains compatible. Hearing supplementation stays
   at 0-50 per party.
4. `evidence.v2` consumes one immutable `evidence-batch-manifest.v1` with at most 100 stable item
   keys. Java signs actor scope, epoch/fence, object version, content hash, owner, visibility, and
   all version pins; Python never derives authorization from content.
5. `Send` schedules independent item assessments. At most eight are active for one room; tenant and
   global semaphores plus bounded queues are mandatory. The graph dispatches deterministic waves
   until all manifest keys are terminal; 100 simultaneous tasks are forbidden.
6. Fan-in uses a stable Evidence-ID keyed reducer. Identical replay is idempotent; the same key with
   another canonical payload hash fails the command. Fold order cannot change the proposal hash.
7. Per-item outputs distinguish authenticity, relevance, completeness, confidence, source refs,
   actual loaded modalities, and review need. Low relevance never implies fabrication, and low
   confidence alone does not prevent terminal completion.
8. Only an AssetLoader capability bound to the manifest item, owner/visibility, immutable object
   version, MIME/size, and SHA-256 may deliver bytes to a model. `IMAGE_PIXELS` may be claimed only
   when the capability receipt records `LOADED`; otherwise the proposal states the limitation.
9. Graph emits proposals only. Java revalidates complete manifest coverage, terminal status,
   hashes, epoch/fence, actor scope, policy versions, and admissible references before one ACID
   merge/freeze operation. No last-write-wins or partial formal merge is allowed.
10. Temporal owns the original shared deadline. The first submission or first party completion does
    not reset it. The reminder is 30 minutes before expiry. Duplicate Signals are idempotent.
11. Initiator admission still requires at least one formally submitted Evidence item; respondent
    may complete with zero. Hearing can open only from a committed Java admission/freeze receipt.
12. Legacy `evidence-window-{caseId}` remains pinned to `LEGACY`. A new Evidence child and the legacy
    workflow cannot own timers for the same case/epoch.
13. Additive Evidence binding DDL uses the next compatible V043 sub-version after
    `V043_1__intake_authority_bindings.sql`; the candidate must freeze the exact migration name and
    schema before implementation. V044 and V045 remain reserved by existing plans.
14. Runtime before promotion is only `DISABLED` or Java-signed synthetic `SHADOW`. Real party data,
    a formal sink, `TEMPORAL` allocation, canary, and promotion remain unreachable.
15. Phase 5 does not alter Hearing supplementation contracts or behavior.

## Entry Gates

### P5.0 Engineering Entry Gate

The primary may record P5.0 `PASS` only after all of the following are immutable and cross-linked:

- A Phase 4 engineering checkpoint from one accepted candidate grants
  `next_phase_permission: PHASE_5_ENGINEERING_ONLY`.
- `MIG-004=PASS` is independently recorded. A pending promotion gate cannot be replaced by local or
  synthetic evidence.
- Product/API/frontend owners approve the Evidence submission expansion from 50 to 100 while
  preserving 1-50 behavior and the separate Hearing limit of 50.
- `GRAPH-016` has complete tenant/global semaphore and bounded-queue evidence, not only the existing
  per-room unit bound.
- The authorized asset-loading boundary is approved with immutable object version, tenant/case,
  epoch/fence, actor/owner/visibility, manifest, MIME/size, SHA-256, and actual-load receipt checks.
- This execution plan, the test-batch policy, and the P5.0 contract pack are frozen in one contract
  candidate with no source/test/migration changes.
- Batch 0 passes from that exact clean detached contract-candidate SHA and its report hashes,
  commands, durations, exit codes, environment, and protected worktree exceptions are committed in
  a later entry-evidence commit.
- Each delegated brief names exact owned and forbidden paths, input contracts, T0 checks, deferred
  centralized batch, review partner, and commit-sized definition of done.

Any missing condition leaves `engineering_execution: BLOCKED`. The current draft fails the first
five conditions and therefore cannot start Batch 0 or delegate implementation.

### Promotion Entry Gate

Engineering completion never implies promotion. Real shadow, a formal sink, or a `TEMPORAL`
Evidence epoch additionally requires approved production identity/key rotation, private object
ACLs, immutable storage, real-data authority, exact image/workflow/graph/profile versions,
minimum sample/observation windows, SLO/error budgets, rollback authority, and compatible readers.
The promotion candidate must pass authorized real shadow before a separately approved new-epoch
cohort sequence. This draft cannot mark that gate `PASS`.

## Vertical Slices

| Slice | Lead | Production-shaped behavior | Focused evidence | Rollback point |
| --- | --- | --- | --- | --- |
| `P5-S1` graph and reducer | A | Manifest-backed 1/8/100 item graph, deterministic dispatch waves and keyed proposal | bounds, recovery, order, duplicate/conflict properties | disable `evidence.v2`; retain checkpoints |
| `P5-S2` Temporal process | B | Shared deadline, warning, completion/expiry ordering, Java receipt orchestration | time-skipping, duplicate Signal, kill/replay, commit-response loss | stop new child provisioning; retain active History |
| `P5-S3` Java authority | C | Manifest minting, asset authorization, formal assessment/finalizer, additive bindings | transaction/idempotency/fence/privacy/admission tests | stop formal adapter; reconcile receipts; no DDL rollback |
| `P5-S4` compatible experience | D | 100-card rendering, private/reviewer views, active run, timer and history projection | API/store/view/a11y/role-switch focused tests | select legacy reader; retain additive fields |
| `P5-S5` reliability/release | E | signed synthetic shadow, bulkheads, parity, fault matrix, selector and rollback controls | crash/race/queue/hash/privacy/no-formal-sink evidence | set cohort zero/disable Graph; preserve ledgers |

## Team, Ownership, And Waves

| Owner | Owned concern | Typical future paths | Forbidden boundary |
| --- | --- | --- | --- |
| A | `evidence.v2`, bounded scheduler, state, reducers, recovery properties | `python-agent-service/app/graphs/evidence/**`, narrow graph tests | Java, frontend, Domain DB, formal merge |
| B | Evidence child Workflow, command loop, timers, Activity contracts | `java-api-service/**/workflow/temporal/room/evidence/**` | Domain fact implementation, Python, UI, Hearing behavior |
| C | Java manifest/asset authority, mode guards, Finalizer, V043 sub-version | Evidence application/persistence adapters and exact tests | Workflow decisions, Graph DB, frontend, Hearing supplement |
| D | Java projection/API compatibility and Evidence Vue/store behavior | Evidence projection/controller/view/API/store and focused tests | selectors, Temporal/Python runtime, formal rules |
| E | bulkheads, synthetic parity, selector, observability, crash/rollback harness | narrow Evidence config/shadow/recovery paths | formal activation, secrets, deployment, other rooms |
| R | contracts, shared fixtures, exact briefs, integration, reviews, test tokens, candidate evidence | shared contract/plan/report/config paths explicitly granted | duplicating delegated slice implementation |

Path families are not blanket permissions. Before delegation, the primary records exact owned and
forbidden files. Shared worker assembly, public contracts, migration identity, selector wiring, and
candidate evidence remain primary-controlled. No owner stages unrelated changes.

### Wave 0: Contract And Entry Evidence

R freezes the P5.0 candidate only after upstream gates exist, runs Batch 0 from that SHA, and commits
entry evidence separately. Owners receive only their brief and relevant YAML excerpt. No owner may
implement from this draft or from an untested candidate.

### Wave A: Three Parallel Foundations

- A owns `P5-S1` Graph/reducer work.
- B owns `P5-S2` Workflow/timer work.
- C owns `P5-S3` Java authority and additive persistence.
- R integrates one reviewed commit at a time and runs one deduplicated affected batch.

### Wave B: Experience And Hardening

- D owns `P5-S4` projections and compatible frontend behavior.
- E owns `P5-S5` bulkhead, synthetic parity, recovery, selector, and release controls.
- Released Wave A owners perform bounded cross-review.
- R integrates, closes findings, freezes one candidate, and alone runs the final checkpoint.

## Review Gates

- A reviews C for exact manifest membership/hash binding and no Domain access from Python.
- B reviews C for operation keys, commit-response loss, deadline immutability, and stale fences.
- C reviews B for determinism, enqueue-only handlers, Java receipt authority, and no duplicate timer.
- D reviews Java/Python projections for actor privacy, actual-load claims, active run and history mode.
- E reviews all slices for bulkhead enforcement, mode defaults, no formal sink, rollback and metrics.
- R maps every P0 finding to an owner and blocks dependent integration until closure.

## Verification Scope

The machine-readable schedule is `plans/phase-5-evidence-pilot-test-batches.yaml`. Delegated work
uses focused T0 checks. Maven/PostgreSQL/Temporal, frontend builds, browser flows, Docker, full
regression, load, soak, failover, and DR are primary-owned and run only at their centralized or
agreed unified checkpoints.

Primary Phase 5 checks are:

```text
ROOM-EVIDENCE-001..006
GRAPH-009
GRAPH-016..019
TEMP-020..024
LCEL-009
MIG-005
```

Baseline evidence maps every `EVD-001..015`, `UI-001`, `UI-003..005`, `CORE-001..010`, and
`SEC-001..006` behavior explicitly. `ENV-014` is capacity evidence and cannot substitute for the
separate 100-file product approval.

Every accepted command record includes candidate SHA, environment manifest, timestamps, duration,
exit code, report path, and SHA-256. Failures are classified `PRODUCT`, `FIXTURE`, `INFRA`, or
`EXTERNAL_GATE` before a bounded rerun. Results from different candidate commits are never mixed.

## Rollback Protocol

### Engineering Rollback

1. Keep formal Evidence epochs `LEGACY` and Graph `DISABLED`; remove only the signed synthetic
   binding under test.
2. Stop new synthetic commands, fence nonterminal leases, and retain completed ledger/checkpoint
   rows for replay and audit.
3. Revert version selection and adapters, not additive Domain or Graph data.
4. Re-run readiness, bulkhead, and no-formal-sink static gates.

### Future Canary Rollback

1. Set new Evidence cohort allocation to zero without changing active epoch mode.
2. Preserve the original deadline, completed-party set, formal submissions, review rows, and any
   committed dossier/freeze/Hearing-opening receipt.
3. At a safe pre-freeze wait boundary, create a higher fenced recovery epoch from Java formal refs;
   never start a second timer against the old epoch.
4. After freeze or Hearing opening commits, roll forward from the idempotent Java receipt. Do not
   reopen Evidence or delete an immutable dossier.
5. Any privacy leak, unauthorized asset byte, stale-fence success, duplicate merge/freeze, or
   conflicting reducer key stops all cohorts immediately.

## Engineering Exit

The Phase 5 engineering checkpoint can pass only when one candidate proves all five slices,
complete 1/8/100 terminal coverage at maximum room concurrency eight, tenant/global bounded-queue
behavior, deterministic reducer/hash output, authorized actual-load claims, Temporal timer/race
recovery, exactly one Java merge/freeze, all baseline behaviors, and fail-closed runtime defaults.

The report shape is:

```text
engineering_checkpoint: PASS | FAIL
promotion_gate: PENDING | FAIL
next_phase_permission: PHASE_6_ENGINEERING_ONLY | BLOCKED
MIG-005: PENDING_PROMOTION
```

`MIG-005=PASS` additionally requires authorized real shadow, promotion evidence, canary observation,
and Java's formal Evidence ledger remaining the only writer. Until then, no report may claim
promotion or permit Hearing supplementation work under Phase 5.
