# Phase 8 P8.0 Baseline Inventory

## Inventory State

```text
inventory_status: A7_BOUND_REQUIRES_P8_0_BATCH_0_REAUDIT
phase_7_candidate_C7: 4ddeeabb39ce7b7de41ecc4f44e17ece389d2840
phase_7_evidence_E7: f1c1ca16228641f1072eb358c6df9235dc239914
accepted_phase_7_checkpoint_A7: e3acedc64d161f0342c8db3d5c313c2f404ea462
phase_7_engineering_checkpoint: PASS
next_phase_permission: PHASE_8_ENGINEERING_ONLY
P8.0: NOT_RUN
engineering_execution: BLOCKED_PENDING_P8_0_ACCEPTANCE
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
real_case_or_party_data: FORBIDDEN
```

This inventory is anchored to the accepted Phase 7 checkpoint `A7`. It separates facts verified
from the `C7 -> E7 -> A7` Git and evidence chain from observations copied from the older Phase 8
draft at `3eebc36b1f8aa470e68b8f4b84203b0b1c13f783`. Those older observations were made against another
base and are not current facts. Batch 0 must re-audit them from the exact clean P8.0 contract
candidate before Phase 8 implementation starts.

Nothing in this inventory is P8.0 evidence, a production checkpoint, a migration pass, or cleanup
authorization.

The strict entry topology is contract-only `C8`, the sole-parent direct child of exact `A7`, then
Batch 0 from clean detached exact `C8`, then evidence-only `E8`, the sole-parent direct child of
`C8`, then checkpoint-only `A8`, the sole-parent direct child of `E8`. `C8` contains no product or
runtime implementation and cannot self-pass. `E8` records no release or entry decision. Only `A8`
may record P8.0 `PASS` and release the five engineering implementation owners.

## Verified Phase 7 Handoff

The following facts are verified from committed objects:

1. `C7` is `4ddeeabb39ce7b7de41ecc4f44e17ece389d2840`.
2. `E7` is `f1c1ca16228641f1072eb358c6df9235dc239914`, the sole-parent direct child of `C7`, and contains
   the sealed Phase 7 engineering evidence.
3. `A7` is `e3acedc64d161f0342c8db3d5c313c2f404ea462`, the sole-parent direct child of `E7`. Its complete
   diff adds only the Phase 7 engineering checkpoint and its static acceptance test.
4. The Phase 7 evidence reports 149 static, 22 Python, 276 Java, and 60 frontend tests: 507 total,
   with zero failures, errors, or skips.
5. The Phase 7 checkpoint records `engineering_checkpoint: PASS` and
   `next_phase_permission: PHASE_8_ENGINEERING_ONLY`.
6. The same checkpoint keeps `MIG-006` and `MIG-007` at `PENDING_PROMOTION`; it does not authorize
   formal Outcome activation, real case or party data, real tools, production traffic, canary, or
   promotion.
7. The `A7` tree contains the additive Phase 7 migration
   `V045__outcome_operation_receipt_compensation.sql`. It contains neither a V046 migration nor a
   V047 migration.

The `149/22/276/60 = 507` result is Phase 7 handoff evidence only. It is not a P8.0 Batch 0 count,
cannot be copied into Phase 8 evidence, and cannot satisfy a Phase 8 engineering or release gate.

### Verified A7 Scheduler Facts

The Hearing schedulers already have modes and detector implementations at `A7`; the stale claim
that those modes do not exist is false for this tree. The current source instead exposes three
specific Phase 8 engineering gaps:

1. `HearingSchedulerControl` requires `HearingWriterMode.TEMPORAL` to use
   `SchedulerMode.DETECTOR`, while `drainedOff()` constructs `OFF + LEGACY + DRAINED`. Therefore a
   `DETECTOR + TEMPORAL -> OFF + TEMPORAL` transition is not representable without regressing the
   recorded writer mode. Phase 8 must make the transition representable without transferring or
   falsifying writer authority.
2. `AgentRunRecoveryScheduler` executes would-be legacy candidates from `V1 + LEGACY_WORKER`, but
   its current `DETECTOR` path scans `V2 + TEMPORAL_ACTIVITY`. It does not yet enumerate the same
   candidate set the retiring executor would have claimed.
3. `JdbcHearingSchedulerDetector` starts its deadline and handoff scans from
   `hearing_temporal_projection` rows with `writer_mode = 'TEMPORAL'`. A legacy candidate that has
   no projection row is outside those projection-driven scans.

Before any scheduler can become eligible for `OFF`, Phase 8 engineering must prove a transition
that preserves the Temporal writer marker, enumerate the exact would-be `V1 + LEGACY` candidates,
and detect legacy work even when its projection is absent. A detector produces observation or a
immutable reconciliation proposal only. It must never perform formal reconciliation, mutation, enqueue,
phase advancement, or timer ownership.

The retirement-candidate estate is limited to `AgentRunRecoveryScheduler`,
`HearingFlowDeadlineScheduler`, and `HearingReviewHandoffRecoveryScheduler`. The
`TemporalCommandOutboxRelay`, control recovery jobs, SSE heartbeat, and Activity heartbeat are
retained schedules unless an exact audit proves otherwise. Phase 8 must not apply a blanket
`EXECUTOR -> DETECTOR -> OFF` rule to every scheduled job; an unknown classification blocks
retirement.

## Historical Observations Requiring Batch 0 Re-Audit

The predecessor draft recorded the following observations against its historical audited base
`aa9617d10533d364592979f2a85473e528ed1a6c`. They are retained only as re-audit questions. Their
current answer is `UNKNOWN_PENDING_BATCH_0`.

| Area | Historical observation, not an A7 fact | Required exact-candidate re-audit |
| --- | --- | --- |
| Scheduler estate | The predecessor draft said Hearing deadline and review-handoff modes were absent. That statement is superseded by the verified A7 scheduler facts above. | Re-audit every scheduler, trigger, owner, default, epoch fence, mutation/enqueue path, candidate query, detector coverage, transition representability, and rollback mode. |
| Legacy references | V1 defaults, legacy Intake routes/callers, `room_turn_memory`, an evidence Workflow registration, old Graph versions, and compatibility readers were described as active. | Join Domain, Temporal Visibility/Build ID, Graph/checkpoint/lease, outbox, run, cursor, endpoint, and deployed-reader references with complete pagination and high-watermarks. |
| Stream storage | `agent_run_stream_event` was described as unpartitioned and retention/archive metadata as non-durable. | Inspect the exact schema, readers, writers, archive receipts, cursor semantics, Redis fallback, buffer bounds, and migration history. |
| Deployment | Development Compose was described as single-host with no production three-failure-domain, HPA, PDB, PgBouncer, or read-replica proof. | Inventory rendered manifests and defaults without applying any production environment. Missing assets remain gaps, not inferred failures or passes. |
| Observability | Collector topology, business metrics, eight dashboard groups, alert routing, and runbook ownership were described as incomplete. | Inventory code and configuration, then validate schema, cardinality, ownership, and fail-closed alert contracts. Do not treat process liveness as SLO evidence. |
| Verification | Existing unit, integration, replay, and fixture browser coverage was described as useful but not a unified production checkpoint. | Bind every accepted suite and baseline to the candidate. Record missing live or external suites as `EXTERNAL_GATE`. |
| Capacity and recovery | No accepted 1,000-room, 2,500-SSE, load-coupled chaos, 24-hour soak, Domain PITR, regional DR, object restore, or production rotation evidence was identified. | Confirm repository harness readiness separately from real execution. A missing environment, credential, operator, or window cannot become a synthetic pass. |

A historical zero, file count, service count, default, route, feature flag, test count, or missing-file
claim must not be repeated as current evidence until Batch 0 records the exact query/command, clean
candidate SHA, output, exit code, and hash. Query errors, timeouts, permission failures, partial
pages, excessive replica lag, unknown classes, and parse failures are nonzero-risk outcomes and
must fail closed.

## Phase 8 Target Contracts, Not Baseline Facts

The following are targets that Phase 8 must prove; they are not claims about `A7`:

- a sealed active-reference report covering old Workers, Graphs, schedulers, stage entries,
  Temporal Workflow type/Build ID/room epoch, nonterminal `case_room_epoch` writer/workflow-build/
  graph/stream version pins, pending `case_command`/outbox/`domain_operation`/Finalizer work,
  nonterminal AgentRun V1 logical runs/attempts, hot stream readers, leases, stream cursors, object-store
  codec/schema/prompt/artifact manifests, retained-window frontend/API legacy endpoints,
  `agent_stream.v1` telemetry, and deployed readers;
- two authoritative zero scans across the full retention/visibility window plus producer,
  selector, and deployment quiescence and a continuous, monotonic no-new-reference ledger and
  high-watermark between those scans;
- independent per-scheduler `EXECUTOR -> DETECTOR -> OFF` lifecycle evidence, with detector mode
  unable to mutate, enqueue, advance a phase, or own a timer;
- additive V046 expand/backfill/validate/compatible-read/compatible-write machinery, archive
  receipts, high-watermarks, bounded consumers, and PostgreSQL-authoritative replay;
- renderable three-failure-domain topology, service-specific HPA, PDB/topology spread, isolated
  pools, PgBouncer, read replicas, admission control, and observable SLOs;
- concrete KMS/Vault key-reference, workload-identity, least-privilege RBAC, default-deny
  NetworkPolicy, mTLS, and private versioned immutable object-storage ACL/audit manifests and
  static tests reserved by the P8.0 contract;
- exact-candidate scenario and evidence validators for load, chaos, replay, security, recovery,
  rotation, soak, and six-role approval; and
- a separately authorized cleanup process that cannot create V047 until all reference, retention,
  recovery, release, and signature gates are satisfied, including `MIG-000..008=PASS`.

V046 changes delivery storage only. A stream `final` row, delivery high-watermark, archive
manifest, or archive receipt is not formal business completion and cannot authorize a room message
or artifact. The existing Java plus Domain PostgreSQL transaction/Finalizer remains the sole formal
commit boundary. Any future `MIG-008=PASS` must bind the same release candidate/checkpoint to
accepted, separately authorized V046 receipts for expand/apply, bounded backfill plus contiguous
HWM, capture plus dual-write, exact parity plus archive validation, observed reader and writer
switches, rollback readiness, and old-store read-only retention.

## Engineering And Release Lanes

After a separately accepted P8.0 gate, the engineering lane may build fail-closed audit tooling,
scheduler guards, V046 migration machinery for disposable environments, render-only deployment
assets, observability, recovery/rotation tooling, synthetic capacity models, and evidence
  validators. Engineering evidence may establish readiness only.

Batch 0 verification is allowed to execute only its fixed, predeclared local Git and Python argv
with `shell=false`; it may not accept arbitrary argv or invoke an unallowlisted executable. This is
not a permission for operational tooling. Phase 8 recovery, DR, and rotation dry-run tooling is
fixture-only and must not access the network, spawn a subprocess, call cloud APIs, connect to a
database or Temporal, or read secret-bearing environment variables.

The release lane alone may:

- activate any scheduler `OFF` mode;
- apply or switch V046 against a real environment;
- execute production-equivalent deployment, load, chaos, failover, security, replay, PITR, DR,
  object restore, credential/certificate/codec rotation, or 24-hour soak;
- use real production traffic, credentials, data, canary, or promotion controls;
- record `MIG-006`, `MIG-007`, `MIG-008`, or `GATE-001..010` as passed; or
- authorize authoring, applying, or switching V047 and deleting old code, schema, endpoints,
  Workers, Graphs, schedulers, or readers.

Real release-lane execution does not open from A7 or P8.0 engineering permission. It first requires
the master production entry state `MIG-000..007=PASS`, all 99 behavior baselines current, and no
open P0. V047 additionally requires `MIG-008=PASS`, making its migration prerequisite
`MIG-000..008=PASS`.

Because `MIG-003..007` remain `PENDING_PROMOTION`, formal Intake, Evidence, Hearing, and Outcome
selectors remain legacy. Until real release evidence exists, all of those actions remain forbidden or
`PENDING_EXTERNAL`. In particular, no scheduler may be turned `OFF`, no real V046 apply or switch
may run, and V047 must not be authored in the P8.0 or engineering candidate.

## Non-Authorization Statement

This document records an accepted Phase 7 engineering handoff and a Phase 8 re-audit boundary. It
does not claim P8.0 Batch 0 ran, does not close any Phase 8 P0 question, and does not authorize
implementation before sole-parent checkpoint-only `A8` records a separate P8.0 acceptance after
contract-only `C8`, clean-detached Batch 0, and evidence-only `E8`. `MIG-006`, `MIG-007`, and `MIG-008` remain
`PENDING_PROMOTION`; production, load, chaos, PITR, DR, rotation, scheduler `OFF`, real V046
cutover, V047, canary, and promotion remain forbidden.
