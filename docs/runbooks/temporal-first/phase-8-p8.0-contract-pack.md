# Phase 8 P8.0 Cleanup And Production Hardening Contract Pack

## Entry State

```text
document_status: P8_0_CONTRACT_CANDIDATE
contract_gate: P8.0 NOT_RUN
P8.0: NOT_RUN
contract_candidate_state: AWAITING_FREEZE
phase_7_candidate_C7: 4ddeeabb39ce7b7de41ecc4f44e17ece389d2840
phase_7_evidence_E7: f1c1ca16228641f1072eb358c6df9235dc239914
accepted_phase_7_checkpoint_A7: e3acedc64d161f0342c8db3d5c313c2f404ea462
superseded_historical_C8: 6d4f9946ab357a7d3193ea1680473fe923322eb0
superseded_historical_E8: 4dc398d359806ab41ea702df54112956d17920ae
superseded_historical_A8: 7e3cbace3d206aef5eb23a03d36878a00634c9a9
superseded_historical_ref: refs/tags/phase8-superseded-a8-7e3cbace
superseded_historical_ref_must_not_move: true
superseded_historical_chain_authority: HISTORICAL_OLD_CONTRACT_ONLY
phase_7_engineering_checkpoint: PASS
current_permission: PHASE_8_ENGINEERING_ONLY
entry_pass_token: P8_0_ENGINEERING_ENTRY_PASS
implementation: BLOCKED
engineering_execution: BLOCKED_PENDING_P8_0_ACCEPTANCE
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
scheduler_OFF: FORBIDDEN
production_V046_apply_or_switch: FORBIDDEN
V047: FORBIDDEN
real_case_or_party_data: FORBIDDEN
production_load_chaos_PITR_DR_rotation: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

This pack is P8.0 contract-candidate material only. The accepted Phase 7 handoff permits a Phase 8
engineering entry process; it does not make P8.0 pass and does not permit implementation to begin.
The Phase 7 result of 149 static, 22 Python, 276 Java, and 60 frontend tests, 507 total, is handoff
evidence only. P8.0 must produce its own exact-candidate Batch 0 evidence.

The governing sources are the accepted Phase 7 checkpoint, the master Temporal-first refactor
plan, the production verification checklist, the current-room behavior baseline, the test manifest,
and this candidate's Phase 8 execution plan and machine batch schedule. If they conflict, the more
restrictive authority, safety, evidence, or production boundary wins until integrated P0 review
resolves the conflict in a new candidate.

## Candidate, Evidence, And Acceptance Topology

P8.0 is fail-closed and uses distinct Git objects and responsibilities:

```text
C8_DIRECT_CHILD_OF_A7: REQUIRED_SOLE_PARENT
EXACT_CLEAN_DETACHED_C8: BATCH_0_SOURCE
E8_IS_SOLE_PARENT_DIRECT_CHILD_OF_C8: REQUIRED_EVIDENCE_ONLY
A8_IS_SOLE_PARENT_DIRECT_CHILD_OF_E8: REQUIRED_CHECKPOINT_ONLY
CONTRACT_CANDIDATE_IMPLEMENTATION_FORBIDDEN: true
CONTRACT_CANDIDATE_CANNOT_SELF_PASS: true
E8_RELEASE_DECISION: FORBIDDEN
ONLY_A8_MAY_RECORD_P8_0_PASS: true
```

1. `A7` is the immutable sole parent and accepted input. Its exact SHA is
   `e3acedc64d161f0342c8db3d5c313c2f404ea462`; substituting a branch name, tag, abbreviated
   expectation, or reconstructed tree is invalid.
2. `C8` is the one contract-only, exact-allowlist candidate and sole-parent direct child of `A7`.
   It freezes the Phase 8 plans, owner briefs, batch schedule, contracts, static contract tests,
   and no product implementation. Its exact SHA and complete allowed path set are recorded before
   execution. `C8` cannot attest or mark itself `PASS`.
3. Batch 0 runs once from a clean detached `C8`. It authenticates the `C7 -> E7 -> A7` handoff,
   candidate ancestry and scope, internal cross-references, ownership boundaries, forbidden
   actions, batch commands, and inherited source baselines. No report from the Phase 7 set of 507
   tests is relabeled as a P8.0 result.
4. `E8` is the evidence-only sole-parent direct child of `C8`. It records exact commands, start and
   end times, durations, environment, exit codes, raw and normalized report paths, SHA-256 hashes,
   clean-tree proof, tested candidate SHA, attempt lineage, and failure classification. It changes
   no contract or product path, makes no release decision, and cannot record P8.0 `PASS`. After a
   successful Batch 0, its ceiling is `PASS_AWAITING_CHECKPOINT_A8` with
   `next_phase_permission: PENDING_A8_CHECKPOINT`.
5. `A8` is the checkpoint-only sole-parent direct child of `E8`. It independently authenticates
   the complete topology and evidence-only scope. Only `A8` may record P8.0 `PASS`, issue
   `P8_0_ENGINEERING_ENTRY_PASS`, and release the engineering owners. It contains no implementation
   or release authorization.

No implementation owner may edit product, migration, runtime configuration, deployment, harness,
or operational tooling before sole-parent checkpoint-only `A8` records P8.0 `PASS`. If any contract path changes after `C8`, a
new candidate and fresh Batch 0 are required. Evidence from different candidates or attempts may
not be combined.

The historical `C8=6d4f9946ab357a7d3193ea1680473fe923322eb0`,
`E8=4dc398d359806ab41ea702df54112956d17920ae`, and
`A8=7e3cbace3d206aef5eb23a03d36878a00634c9a9` authenticate only their superseded
contract bytes. They grant no authority over this correction or its implementation. This
correction is valid only when committed as a replacement contract-only `C8` whose sole parent is
exact `A7`, followed by a fresh exact-SHA Batch 0 without reused reports or receipts, a new
evidence-only `E8`, and a new checkpoint-only `A8`.

### Exact C8 Twelve-Path Allowlist

Relative to `A7`, `C8` changes exactly these 12 paths and no others:

```text
c8_contract_allowlist_path_count: 12
```

1. `plans/phase-8-production-hardening-execution.md`
2. `plans/phase-8-production-hardening-test-batches.yaml`
3. `plans/phase-8-owner-briefs.yaml`
4. `docs/runbooks/temporal-first/phase-8-p8.0-contract-pack.md`
5. `docs/runbooks/temporal-first/phase-8-p8.0-baseline-inventory.md`
6. `docs/runbooks/temporal-first/phase-8-p8.0-review-closure.md`
7. `tests/static/test_phase8_production_hardening_plan.py`
8. `plans/temporal-langgraph-room-refactor.md`
9. `scripts/run_phase8_entry_checkpoint.py`
10. `scripts/generate_phase8_entry_evidence.py`
11. `tests/static/test_phase8_entry_runner.py`
12. `tests/static/test_phase8_entry_evidence.py`

The master plan is the only allowed modified pre-A7 path; the other 11 are additions. Rename,
deletion, generated Batch 0 evidence, product/runtime implementation, or any thirteenth changed path
fails the entry gate.

### Exact E8 Twelve-Blob Evidence Scope

Under the one candidate-bound
`test-reports/temporal-first/<release>/phase-8-entry/` prefix, `E8` contains exactly these 12
regular blobs and no other path:

1. `.gitattributes`
2. `artifact-sha256.json`
3. `candidate.txt`
4. `phase8-entry-execution-manifest.json`
5. `static-phase8-entry.xml`
6. `source-tree-environment.json`
7. `p0-review-disposition.json`
8. `phase8-entry-decision.json`
9. `provenance-manifest.json`
10. `p/00-stdout.log`
11. `p/01-stderr.log`
12. `p/02-junit.xml`

```text
E8_regular_blob_count: 12
artifact_sha256_index_entry_count: 11
E8_result_after_successful_batch_0: PASS_AWAITING_CHECKPOINT_A8
next_phase_permission: PENDING_A8_CHECKPOINT
```

`artifact-sha256.json` indexes exactly the other 11 regular blobs and does not index itself. Every
listed path must be present once and hash-match; an extra path, missing path, directory/symlink in
place of a regular blob, duplicate entry, omitted index entry, self-entry, or unindexed blob fails
closed. `E8` remains evidence-only and releases no owner. Only sole-parent checkpoint-only `A8`
may validate this scope, record P8.0 `PASS`, and issue `P8_0_ENGINEERING_ENTRY_PASS`.

## Batch 0 Fail-Closed Gates

Batch 0 passes only if all of these checks pass on the same clean detached `C8`:

- the full `C7`, `E7`, and `A7` SHAs exist, have the required sole-parent ancestry, and their path
  scopes match the recorded Phase 7 contracts;
- Phase 7 reports remain 149 static, 22 Python, 276 Java, and 60 frontend tests, 507 total, with
  zero failures, errors, or skips, and are treated only as inherited handoff evidence;
- `A7` records `PHASE_8_ENGINEERING_ONLY` while `MIG-006` and `MIG-007` remain
  `PENDING_PROMOTION`;
- `C8` is the exact-allowlist, sole-parent direct child of `A7`, is clean, and contains every
  required plan, contract, schedule, owner brief, traceability rule, and static gate without
  product implementation or a self-`PASS` claim;
- the candidate contains no V047, destructive cleanup, production credential, production secret,
  production endpoint, production apply, scheduler `OFF` activation, real traffic, canary, or
  promotion change;
- every Batch 0 command is declared before execution and produces the expected report count with
  exact SHA, command, environment, exit code, timestamp, and hash provenance;
- all required source groups execute; a missing command, empty selection, skipped required test,
  unclassified retry, mixed attempt, or stale report fails the gate; and
- integrated P0 review closes every question in the review register against the frozen diff.

Unknown data is never zero, absent evidence is never a pass, and a validator cannot approve its own
changed contract. A timeout, permission denial, incomplete page, excessive replica lag, schema or
parse failure, hash mismatch, dirty tree, unexpected path, or ambiguous ancestry makes P8.0 fail.

### Local Engineering Trust Boundary

P8.0 assumes a `NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR`. SHA-256 seals provide byte integrity and
drift detection only; they are not signer, source, operator, or execution-authenticity proofs, and
malicious local-administrator resistance is outside this engineering entry gate. This limitation
cannot be hidden or upgraded by `C8`, the runner, `E8`, or `A8`.

The external P0 disposition is an engineering process attestation. It must close exactly the 13
registered P0 topics through the fixed `authority`, `data_migration`, and `security_privacy` lanes.
Each distinct lane binds its reviewer identifier, `self_approved: false`, exact C8 commit and tree,
the exact twelve-path diff and Git-blob hashes, its assigned topic closure, and the exact manifest
and source-report hashes. This process receipt is not described as signed or authenticated.

Production still requires independent cryptographic execution and operator attestation bound to
the real environment, deployment, credentials, window, and signatures. Local P8.0 seals or review
receipts are never reusable as production evidence.

The only valid post-acceptance entry state is:

```text
P8.0: PASS
entry_effect: P8_0_ENGINEERING_ENTRY_PASS
engineering_execution: ALLOWED_WITHIN_PHASE_8_ENGINEERING_LANE
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
```

## Engineering Lane

After P8.0 acceptance, engineering may implement only controls and readiness evidence that remain
fail-closed by default:

- complete active-reference inventory adapters, schemas, sealed reports, and non-destructive
  eligibility decisions;
- per-scheduler lifecycle configuration, epoch fences, detector-only observation, parity, and
  rollback tests without activating `OFF` in a real environment;
- additive V046 expand/backfill/resume/validate/archive/compatible-reader/compatible-writer
  machinery and tests against disposable PostgreSQL without a real apply or switch;
- render-only three-failure-domain manifests, HPA/PDB/topology/PgBouncer/read-replica policies,
  admission controls, and synthetic capacity harnesses;
- OTel configuration, low-cardinality instrumentation, dashboards, alerts, runbooks, and
  fail-closed PITR/DR/object-restore/rotation tooling without performing real operations; and
- exact-SHA scenario runners, evidence schemas, and external-evidence intake validators that keep
  missing external results at `EXTERNAL_GATE`.

Engineering success may produce an engineering checkpoint with
`next_phase_permission: EXTERNAL_PRODUCTION_CHECKPOINT_ONLY`. It cannot change a migration or
promotion state and cannot be called production readiness or a release pass.

## Release Lane

The release lane is separate and requires an immutable candidate, images, configuration,
three-failure-domain deployment, authorized operators, real credentials, approved data/fixtures,
provider capacity, change windows, rollback targets, observation windows, and independent owner
signatures. Before any real release-lane execution it also requires the master production entry
`MIG-000..007=PASS`, all 99 behavior baselines current, and no open P0. Neither A7 nor P8.0
engineering permission opens this lane.

Only this lane may execute:

- a real V046 apply, online backfill, validation, reader switch, writer switch, observation, or
  rollback;
- a per-scheduler release observation followed by an authorized `DETECTOR -> OFF` transition;
- production-equivalent 1,000-room, 250-AgentRun, 2,500-SSE load and burst/recovery tests;
- load-coupled pod, Redis, provider, Temporal, and database chaos/failover;
- active Workflow/Graph rollout, rollback, replay, fence, checkpoint, and lease recovery;
- security fuzzing and real secret, certificate, database credential, and payload-codec rotation;
- Domain PITR, Temporal regional DR, Graph/object restore, projection reconciliation, and a 24-hour
  soak; and
- canary, promotion, `GATE-001..010`, or migration promotion decisions.

Repository tests, mocks, rendered manifests, local Compose, synthetic load, stub drills, and signed
local hashes cannot substitute for release-lane evidence.

## Authority And Active-Reference Contract

Java and Domain PostgreSQL remain the sole formal business ledger. Temporal owns process time and
failure only for separately approved `TEMPORAL` epochs and never bypasses Java invariants.
Graph/LCEL are private bounded cognition with no Domain sink, formal writer, phase authority,
cleanup authority, or long wait. Because `MIG-003..007` are `PENDING_PROMOTION`, formal Intake,
Evidence, Hearing, and Outcome remain on their legacy selectors.

One sealed active-reference report must cover each retirement target across:

- Temporal Workflow type, Child, Continue-As-New, pending work, Schedule, Worker Build ID, and room
  epoch reachability;
- Graph thread, version, checkpoint, command/result, lease, and deployed reader reachability;
- nonterminal Domain `case_room_epoch` rows joined to writer, Workflow Build, Graph, and stream
  version pins; pending `case_command`, outbox, `domain_operation`, and Finalizer work; nonterminal
  AgentRun V1 logical runs/attempts; hot stream readers; leases; stream cursor/archive
  high-watermarks; endpoint callers; and compatibility readers; and
- object-store codec, schema, prompt, and artifact manifests; retained-window frontend/API legacy
  endpoints; `agent_stream.v1` telemetry; and every old Worker, Graph, scheduler, stage entry,
  table, index, route, and configuration proposed for retirement.

Each row binds candidate, environment manifest, authority source, query ID/hash, target, count,
oldest/newest reference, retention boundary, scan high-watermark, replica lag, owner, reason,
evidence references, and row hash. The only decisions are `RETAIN`, `BLOCK_DELETE`, and
`ELIGIBLE`. Error, unknown, unsupported class, partial pagination, stale high-watermark, excessive
lag, missing owner, or invalid schema always yields `BLOCK_DELETE`.

The machine decision mapping is exact: `UNKNOWN -> BLOCK_DELETE`, `PARTIAL -> BLOCK_DELETE`, and
`ERROR -> BLOCK_DELETE`. Pagination, query, permission, timeout, and lag errors are `ERROR` or
`PARTIAL`, never zero.

`ELIGIBLE` requires two complete authoritative zero scans separated by the maximum visibility and
retention window with identical target inventory and signed hashes. The full interval must also
prove producer, selector, and deployment quiescence and a continuous monotonic authoritative
no-new-reference ledger and high-watermark between the scans. Two point-in-time zeros
alone are insufficient. Eligibility is an input to human authorization; it never performs or
authorizes deletion.

## Scheduler Contract

Each scheduler has an independent owner, authority query set, persisted/configured mode, epoch
fence, observation window, rollback target, and evidence row.

The three legacy executor retirement candidates are `AgentRunRecoveryScheduler`,
`HearingFlowDeadlineScheduler`, and `HearingReviewHandoffRecoveryScheduler`. The
`TemporalCommandOutboxRelay`, control recovery schedules, SSE heartbeat, and Activity heartbeat
are retained classes, not presumed legacy executors. There is no blanket
`EXECUTOR -> DETECTOR -> OFF` transition for every scheduled job. An unknown, incomplete, or
ambiguous classification yields `BLOCK_DELETE` and blocks retirement.

```text
detector_invariants: [NO_MUTATION, NO_ENQUEUE, NO_FORMAL_RECONCILIATION]
current_gap_markers: [TEMPORAL_OFF, DRAINEDOFF, V2_TEMPORAL, V1_LEGACY,
  ABSENT_PROJECTION, WOULD_BE_LEGACY_CANDIDATE, BEFORE_OFF]
```

| Mode | Permitted | Forbidden |
| --- | --- | --- |
| `EXECUTOR` | Execute only eligible persisted legacy epochs under one authoritative owner. | A Temporal epoch, second executor, or unowned mutation. |
| `DETECTOR` | Enumerate the exact work the retiring legacy executor would claim and emit bounded observation or immutable reconciliation proposals/audit. | Formal reconciliation, mutation, enqueue, phase advance, timer ownership, or a fabricated zero. |
| `OFF` | Stop scanning after release authorization and current zero/parity evidence. | Engineering activation, silent reactivation, or cleanup authorization. |

For each positively classified legacy executor retirement candidate, the only forward path is
`EXECUTOR -> DETECTOR -> OFF`; no aggregate scheduler pass is valid.
Any unexplained, persistent, mismatched, or unbounded detector candidate, duplicate owner, lost
accepted command, illegal phase, parity gap, or stale reference blocks `OFF`. A transient candidate
is acceptable only when its immutable observation is matched to the authoritative owner and
bounded lifecycle. Engineering may test the modes but must not activate `OFF` in a real environment.
Rollback from `OFF` is to `DETECTOR`, never to a legacy executor for a Temporal epoch.

At `A7`, `HearingSchedulerControl` cannot represent `TEMPORAL + OFF` because `TEMPORAL` requires
`DETECTOR` and `drainedOff()` returns `OFF + LEGACY`; the Phase 8 control must preserve the writer
marker across the transition. `AgentRunRecoveryScheduler.DETECTOR` currently scans
`V2 + TEMPORAL_ACTIVITY`, not the retiring executor's `V1 + LEGACY_WORKER` would-be candidates.
`JdbcHearingSchedulerDetector` is projection-driven and can miss legacy candidates with no
projection. Transition representability and complete would-be legacy candidate enumeration must
be implemented and tested before any release observation can support `OFF`.

## V046 Stream Contract

V046 is additive engineering work followed by a separately authorized release operation:

```text
expand target, archive receipt, and high-watermark schema
-> bounded idempotent backfill
-> concurrency-safe dual-write by immutable event identity
-> count/hash/sequence/audience/cursor validation
-> compatible reader switch
-> writer switch
-> old store read-only for at least one release cycle
```

V046 changes delivery storage only. A stream `final` row, delivery high-watermark, archive manifest,
or archive receipt is not formal business completion and cannot self-authorize a room message,
artifact, phase result, or other Domain fact. The existing Java plus Domain PostgreSQL transaction
and Finalizer remains the sole formal commit boundary.

An unpartitioned authoritative identity/idempotency registry, or equivalent global enforcement,
preserves event identity uniqueness across time partitions. Partition-local uniqueness is
insufficient when the partition key is absent from the formal identity, and adding event time to a
key must not weaken the current uniqueness contract.

Each dual-write or backfill batch transaction atomically claims/verifies immutable identity and
payload hash, commits the target row, and advances its persistent PostgreSQL high-watermark. The
high-watermark never regresses, including after hot-partition cleanup, and is never reconstructed
from `max()` over retained hot partitions. The delivery high-watermark is the highest contiguous
committed sequence for its V1/V2 identity and may never advance across a gap. A separate migration
backfill progress cursor cannot substitute for or advance the delivery high-watermark. Concurrent
dual-write and backfill are idempotent for the same identity and payload; the same identity with
another payload hash is a hard conflict.

Replay validation freezes the current V1 `(run, sequence)` and V2
`(run, attempt, sequence)` identities and proves exact ordering, composite-cursor, reset, terminal,
and reconnect semantics. V1/V2 audience parity includes actor-ID authorization, not only row
counts. Archive receipts bind object version, partition, sequence bounds, row count, canonical
hash, and creation receipt. PostgreSQL and Java remain authoritative; Redis is a non-authoritative
wake-up hint and consumer buffers are bounded.

`STREAM-013` is mandatory: hot-partition detach or drop is release-only and requires every run to
be terminal, at least 24 hours of hot retention, verified compaction/archive receipt plus object
version/hash/readback, and continued long-audit retention of the terminal event and immutable
AgentRun manifest. Archive metadata alone grants no partition-cleanup permission.

Engineering may create and test V046 only in disposable environments. A real apply or switch is
release-only and stops before cutover on validation mismatch, archive failure, DDL lock over five
seconds, excessive replica lag, approved p95 breach, pool use at or above 80%, or kill-switch
assertion. Rollback selects a compatible reader and preserves additive data and both stores.
After any new-only write is possible, rollback must not select an old-only reader that can omit
that data; it uses a compatible reader over authoritative identity/HWM state and preserves both
stores.

Any future `MIG-008=PASS` must bind the same immutable release candidate and unified checkpoint to
accepted, separately authorized V046 receipts for expand/apply, bounded backfill plus contiguous
delivery HWM, capture plus concurrency-safe dual-write, exact parity plus archive validation,
reader-switch observation, writer-switch observation and rollback readiness, and old-store
read-only retention. Local or engineering V046 evidence cannot supply those receipts. At P8.0,
real V046 apply or switch remains `FORBIDDEN` and `MIG-008` remains `PENDING_PROMOTION`.

## V047 Non-Contract

`V047__remove_legacy_orchestration.sql` must not exist in the P8.0 contract candidate, Batch 0
evidence commit, P8.0 acceptance, or Phase 8 engineering candidate. No placeholder, commented
destructive SQL, pre-approved delete list, or code removal is permitted.

V047 may be authored only in a new cleanup candidate after all of the following are real and
current: `MIG-000..008=PASS`; `GATE-001..010` pass on one immutable release;
two complete zero-reference scans span the full window; V046 old storage remains read-only for one
release cycle; compatible readers have ended; PITR/restore and rollback evidence is current; and
Architecture, Java, Python, SRE, Security, and Business owners sign. That authorization is not test
evidence for the changed V047 candidate, which must rerun compatibility, replay, migration, and
baseline checks.

## Capacity, SLO, Recovery, And Evidence Contract

The release target is three failure domains with service-specific HPA, PDB/topology spread,
isolated control and model pools, PgBouncer, reporting replicas, at least 70% of 1,000 rooms waiting
on durable timers, 20 commands/s steady, 50 commands/s burst, 250 AgentRun burst, 100/200 model
concurrency, and 2,500 SSE clients. The target includes 60 minutes steady load, 30 minutes recovery,
and 24 hours soak. These numbers are target conditions, not present evidence.

The release must prove low-cardinality end-to-end telemetry, eight dashboard groups, owned
burn-rate/stuck/heartbeat/queue/exporter alerts, no formal event loss, pool use below 80%, and
approved latency/RPO/RTO bounds. Reports exclude secrets, evidence bytes, PII, and hidden reasoning.

Security configuration uses KMS/Vault references rather than secret material, workload identities,
least-privilege RBAC, default-deny NetworkPolicy, mTLS, and private versioned immutable object
storage with explicit ACL and audit evidence. The engineering deliverables reserve these concrete
paths:

```text
deploy/production/phase8/security/workload-identities.yaml
deploy/production/phase8/security/rbac.yaml
deploy/production/phase8/security/network-policies.yaml
deploy/production/phase8/security/mtls-policies.yaml
deploy/production/phase8/security/kms-vault-policy.yaml
deploy/production/phase8/security/object-store-policy.yaml
tests/static/test_phase8_security_manifests.py
docs/runbooks/temporal-first/phase-8-security-hardening.md
```

The manifests contain references and policy only, never secret values. The static test proves only
that the intended identity separation, least-privilege, default-deny, Istio
security.istio.io/v1 resource schema/selectors, KMS/Vault indirection, and private
immutable/versioned object ACL and audit contracts render as declared. It does not prove CRD
availability, dataplane interception, transport encryption, strict mTLS enforcement,
authorization enforcement, or production readiness.

Before any real traffic, a separately authorized external security preflight must accept immutable
same-candidate, same-configuration, same-environment, same-deployment, and same-attempt-lineage
receipts for Temporal Cloud TLS or mTLS credentials, the trusted proxy or direct-mTLS Python ASGI
identity bridge, reporting read-replica routing, object-store workload identity, Langfuse
identity/prompt/output redaction, Istio CRD readiness, dataplane interception, strict mTLS,
authorization-policy enforcement, and the exact I3/I4 OTel namespace, labels, service account, and
ports. Each receipt's exact control/status/evidence/context payload must carry a valid
cryptographic signature from an authorized, unexpired, non-revoked, non-self-approving signer under
an independent trust root. Missing, failed, partial, stale, mixed-context, unsigned, invalid,
untrusted, or secret-bearing receipts keep production and promotion pending.

Batch 0 verification may execute only fixed predeclared local Git and Python argv with
`shell=false`. It rejects arbitrary argv, shell strings, and unallowlisted executables. That narrow
verification permission does not extend to Phase 8 recovery, DR, or rotation dry-run tooling. Those
fixture-only tools must not access the network, spawn a subprocess, call a cloud API, connect to a
database or Temporal, or read secret-bearing environment variables.

Recovery order is Domain, Temporal, Graph, object store, workers, then projections. No operation
may edit internal Temporal or database tables, blindly replay an external effect, or rotate away
the ability to read active History, checkpoints, or compatible payloads.

Every evidence item binds exact source SHA, image and configuration where relevant, environment,
operator, command/scenario, time, exit code, report hash, attempt lineage, and failure
classification. A PRODUCT change invalidates the candidate. A same-SHA INFRA retry preserves the
failed attempt. Missing authorization or environment is `EXTERNAL_GATE`, never `PASS`.

## Adaptive Team And Test Limits

Phase 8 uses an adaptive one-primary-plus-eleven logical-role topology when capacity and dependency
state make it useful:

- five disjoint implementation owners;
- three in-flight P0 review lanes;
- two verification lanes; and
- one lookahead lane.

The primary assigns exact owned and forbidden paths, prevents concurrent writers, preserves all
five logical implementation owners when capacity is constrained, and backfills released slots in
later waves. Roles start 10-20 seconds apart and activate only when a stable diff or testable
candidate exists. At least 50% of planned P0 review remains in flight while implementation
proceeds. Review, verification, and lookahead roles do not replace implementation owners.

At most two light test processes may run concurrently. There is one reserved Maven/Testcontainers
lane; no second Maven/Testcontainers process competes with it. Owners run focused checks while
editing. Full regression, browser E2E, load, chaos, and cross-service verification are grouped at
the agreed unified checkpoint, not repeated after each task.

## Exit Boundaries

An engineering candidate can emit only:

```text
engineering_checkpoint: PASS | FAIL
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING | FAIL
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
next_phase_permission: EXTERNAL_PRODUCTION_CHECKPOINT_ONLY | BLOCKED
```

Only one immutable release candidate/deployment with real `GATE-001..010` evidence and all required
signatures may emit production and migration passes. Until then, scheduler `OFF`, real V046 apply
or switch, V047, production traffic, load, chaos, PITR, DR, rotation, canary, and promotion remain
forbidden or `PENDING_EXTERNAL`.
