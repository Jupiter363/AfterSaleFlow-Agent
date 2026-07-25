# Phase 8 Production Hardening Engineering Execution Plan

## Status

```text
document_status: P8_0_CONTRACT_CANDIDATE
plan_status: FROZEN_AWAITING_EXACT_SHA_BATCH_0
P8.0: NOT_RUN
contract_gate: P8.0 NOT_RUN
implementation: BLOCKED
engineering_execution: BLOCKED_PENDING_P8_0_ACCEPTANCE
phase_8_engineering_implementation: BLOCKED
phase_8_engineering_lane: BLOCKED_PENDING_P8_0_CHECKPOINT
phase_8_external_release_lane: BLOCKED_PENDING_EXTERNAL_AUTHORIZATION_AND_EVIDENCE
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
phase_7_engineering_candidate_C7: 4ddeeabb39ce7b7de41ecc4f44e17ece389d2840
phase_7_engineering_evidence_E7: f1c1ca16228641f1072eb358c6df9235dc239914
accepted_phase_7_checkpoint_A7: e3acedc64d161f0342c8db3d5c313c2f404ea462
phase_7_engineering_checkpoint: PASS
current_permission: PHASE_8_ENGINEERING_ONLY
phase_8_contract_candidate_C8: TO_BE_RECORDED_AT_FREEZE
phase_8_contract_candidate_allowlist: 12_EXACT_PATHS_FROZEN
phase_8_entry_evidence_E8: NOT_CREATED
phase_8_entry_checkpoint_A8: NOT_CREATED
next_phase_permission: BLOCKED_PENDING_P8_0_PASS
superseded_historical_C8: 6d4f9946ab357a7d3193ea1680473fe923322eb0
superseded_historical_E8: 4dc398d359806ab41ea702df54112956d17920ae
superseded_historical_A8: 7e3cbace3d206aef5eb23a03d36878a00634c9a9
superseded_historical_ref: refs/tags/phase8-superseded-a8-7e3cbace
superseded_historical_ref_must_not_move: true
superseded_historical_chain_authority: HISTORICAL_OLD_CONTRACT_ONLY
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
production_scheduler_off_activation: FORBIDDEN
scheduler_OFF: FORBIDDEN
production_v046_apply_or_switch: FORBIDDEN
production_V046_apply_or_switch: FORBIDDEN
production_load_chaos_pitr_dr_rotation: FORBIDDEN
production_load_chaos_PITR_DR_rotation: FORBIDDEN
V047_or_destructive_cleanup: FORBIDDEN
V047: FORBIDDEN
real_case_or_party_data: FORBIDDEN
production_traffic: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
team_topology: 1 primary + 11 adaptive delegated logical roles
implementation_owners: 5
in_flight_p0_review_lanes: 3
verification_lanes: 2
lookahead_lanes: 1
light_test_process_ceiling: 2
maven_testcontainers_lane_ceiling: 1
```

This document is the frozen Phase 8 execution-plan component of a future P8.0 contract candidate.
It is not an entry-evidence record and cannot attest its own candidate SHA. Phase 8 engineering
implementation remains blocked until all P8.0 contract artifacts are frozen in one clean candidate,
Batch 0 passes from that exact detached SHA, a later separate entry-evidence commit authenticates
the result, and a final checkpoint-only acceptance commit validates the complete chain.

The accepted Phase 7 chain is exact and immutable for this gate:

```text
C7 = 4ddeeabb39ce7b7de41ecc4f44e17ece389d2840
E7 = f1c1ca16228641f1072eb358c6df9235dc239914
A7 = e3acedc64d161f0342c8db3d5c313c2f404ea462
```

`A7` records `engineering_checkpoint: PASS` and
`next_phase_permission: PHASE_8_ENGINEERING_ONLY`. It does not promote `MIG-006` or `MIG-007`,
authorize a production runtime, or satisfy the master plan's external release conditions. The new
Phase 7 runtime therefore remains `DISABLED` or `JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW`; Java and Domain
PostgreSQL remain the sole formal Outcome authority.

The governing sources are:

- `plans/temporal-langgraph-room-refactor.md` sections 7.9, 11.2, and 12;
- `docs/runbooks/temporal-first/phase-7-engineering-checkpoint.md` and its sealed `E7` evidence;
- `docs/acceptance/temporal-first-agent-platform-verification-checklist.md`;
- `docs/acceptance/current-room-function-baseline.md`;
- `plans/agent-collaboration-execution.md`, as overridden for Phase 4-8 by `AGENTS.md`; and
- the Phase 8 P8.0 contract pack, owner briefs, and machine test schedule frozen beside this plan.

## Lane Separation

Phase 8 has two non-interchangeable lanes. Evidence from one lane cannot be relabeled as evidence
from the other.

### Engineering Lane

After P8.0 entry passes, the engineering lane may implement and verify, using only local, synthetic,
or explicitly disposable infrastructure:

- fail-closed active-reference inventory and retirement-eligibility reporting;
- scheduler transition guards and detector behavior without activating production `OFF`;
- additive V046 migration, resumable backfill, compatibility readers, archive validation, and kill
  switch mechanics without applying or switching V046 in production;
- deployment manifests, HPA/PDB/topology policies, capacity harnesses, and policy validation without
  production apply;
- OTel, dashboard, alert, runbook, evidence-sealing, restore, DR, and rotation tooling without real
  production drills or secret material; and
- replay, compatibility, migration, security, and rollback tests required to form an immutable
  engineering candidate.

Engineering readiness is not production readiness. A rendered manifest, disposable database test,
synthetic fault, modelled capacity result, stub restore, or local credential fixture cannot pass an
external production gate.

### External Release Lane

Only separately authorized SRE, DBA, Security, service-owner, and Business release procedures may:

- activate a production scheduler transition to `OFF`;
- apply V046 to a real environment, perform its reader/writer switch, or retire the old stream;
- run production-equivalent load, chaos, failover, PITR, regional DR, soak, or live rotation;
- use production traffic, real tenant/case/party data, production credentials, or canary cohorts;
- change `MIG-006`, `MIG-007`, or `MIG-008` to `PASS`; or
- authorize V047 or any destructive schema, code, endpoint, worker, graph, writer, or scheduler
  cleanup.

At this contract-candidate state the external release lane is closed. No local or synthetic result
may be used to claim canary, production, promotion, migration promotion, or `GATE-001..010` success.
It remains closed until the master production entry is independently proven with
`MIG-000..007=PASS`, current evidence for all 99 baselines, and no open P0. Neither A7 nor a future
P8.0 engineering-entry pass authorizes any real release action.

## Scope

### Engineering Goals

1. Produce a complete, fail-closed active-reference audit across Temporal Visibility and Worker
   Build IDs, Graph registry/checkpoints, Domain room epochs, legacy V1 runs, outbox, leases, stream
   cursors, deployed readers, and retained/archive boundaries.
2. Implement the guarded `EXECUTOR -> DETECTOR -> OFF` scheduler lifecycle, one-executor proof,
   persisted epoch ownership, reconciliation, observation evidence, and rollback behavior while
   leaving production `OFF` activation external.
3. Implement V046 as an additive online expand/backfill/validate/swap/archive design with bounded
   batches, resumable high-watermarks, compatible readers, actor/audience/cursor parity, slow-
   consumer recovery, and Redis fan-out outage tolerance.
4. Define production-equivalent three-failure-domain deployment and capacity artifacts for the
   unchanged 1,000-active-room target, including HPA, PDB, topology spread, isolated queues and
   pools, PgBouncer, read replicas, and at least two OTel collectors.
5. Complete SLO instrumentation, trace continuity, eight dashboard groups, burn-rate/stuck/
   heartbeat/queue/export alerts, runbooks, restore ordering, and secret/certificate/credential/
   codec rotation procedures.
6. Build exact-SHA candidate, evidence, and gate tooling that cannot combine different commits,
   images, deployments, configurations, environments, or attempts.

### Non-Goals And Hard Prohibitions

- No new business behavior, prompt policy, model authority, public API semantic, or formal writer.
- No Phase 8 product, migration, runtime, deployment, or test implementation before a separate P8.0
  entry-evidence commit records exact-candidate Batch 0 `PASS`.
- No production scheduler `OFF`, real V046 apply/switch, production traffic, real-data shadow,
  canary, or promotion.
- No real load, chaos, failover, PITR, DR, 24-hour soak, secret rotation, certificate rotation,
  database credential rotation, or payload-codec key rotation in the engineering lane.
- No V047 creation, destructive SQL, table/column drop, old endpoint deletion, or removal of a legacy
  worker, graph, scheduler, writer, reader, or compatibility path.
- No deletion decision based on a partial scan, stale replica, missing query, permission failure,
  timeout, unknown retention boundary, inconsistent high-watermark, or a single zero count.
- No fabricated live baseline, current production count, measured throughput, measured resource
  headroom, or operational readiness claim. Targets inherited from the master plan are labelled as
  targets until same-candidate external evidence measures them.

## P8.0 Exact-Candidate Entry Gate

### Accepted Upstream Evidence

P8.0 must verify all of the following from Git objects and committed evidence, not from mutable
working-tree text:

1. `C7` is exactly `4ddeeabb39ce7b7de41ecc4f44e17ece389d2840`.
2. `E7` is exactly `f1c1ca16228641f1072eb358c6df9235dc239914` and binds its Phase 7 engineering reports to `C7`.
3. `A7` is exactly `e3acedc64d161f0342c8db3d5c313c2f404ea462`, is the checkpoint-only direct child of `E7`, and records Phase 7 engineering `PASS`.
4. `A7` grants only `PHASE_8_ENGINEERING_ONLY` and leaves `MIG-006` and `MIG-007` as
   `PENDING_PROMOTION`.

The historical Phase 7 P7.0 entry `C7/E7` pair is a different earlier gate. P8.0 must use the Phase
7 engineering `C7/E7/A7` chain above and must reject ambiguous shorthand.

### Exact C8 Path Allowlist

The sole-parent `C8` diff from exact `A7` contains exactly these 12 paths and no others:

```text
plans/phase-8-production-hardening-execution.md
plans/phase-8-production-hardening-test-batches.yaml
plans/phase-8-owner-briefs.yaml
docs/runbooks/temporal-first/phase-8-p8.0-contract-pack.md
docs/runbooks/temporal-first/phase-8-p8.0-baseline-inventory.md
docs/runbooks/temporal-first/phase-8-p8.0-review-closure.md
tests/static/test_phase8_production_hardening_plan.py
plans/temporal-langgraph-room-refactor.md
scripts/run_phase8_entry_checkpoint.py
scripts/generate_phase8_entry_evidence.py
tests/static/test_phase8_entry_runner.py
tests/static/test_phase8_entry_evidence.py
```

The last four paths are contract-entry tooling only. They add no Phase 8 product implementation,
runtime selector, migration, deployment, production operation, or release authority.

### Three-Commit And Batch 0 Entry Protocol

1. **P8.0 contract candidate (`C8`)**: freeze the 12 paths above in one clean contract-only commit.
   The entry runner is `scripts/run_phase8_entry_checkpoint.py`; its evidence generator is
   `scripts/generate_phase8_entry_evidence.py`. Record the resulting SHA and exact allowlist in the
   candidate-bound manifest. `C8` must be the sole-parent direct child of exact `A7`, and any path
   outside the allowlist fails entry. It must contain no Phase 8 implementation, V046 migration,
   V047 file, production activation, or generated Batch 0 evidence, and it cannot record its own
   `P8.0 PASS`.
2. **Exact-SHA Batch 0**: run the scheduled entry sources from a clean detached worktree at exact
   `C8`. Capture commands, tool versions, environment, start/end times, durations, exit codes, test
   counts, raw and normalized reports, file hashes, Git ancestry, candidate tree inventory, and the
   absence checks. A dirty tree, changed contract file, wrong SHA, missing source, or reused report
   fails closed.
3. **Separate entry-evidence commit (`E8`)**: commit only immutable candidate-bound evidence after
   Batch 0 succeeds. `E8` must be the sole-parent direct child of `C8`. It records no contract,
   implementation, self-acceptance, owner release, or `P8_0_ENGINEERING_ENTRY_PASS`.
4. **Checkpoint-only acceptance commit (`A8`)**: independently validate the candidate, Batch 0,
   evidence scope, sole-parent topology, and all closed P0 reviews. `A8` must be the sole-parent
   direct child of `E8` and may add only the checkpoint record and its static gate. Only `A8` may
   record `P8.0 PASS`, issue `P8_0_ENGINEERING_ENTRY_PASS`, and release the five implementation
   owners. Neither `C8` nor `E8` may attest itself.

The mandatory Git and execution order is exactly:

```text
A7 -> C8 (contract-only exact allowlist; no implementation; no self-PASS)
   -> Batch 0 (clean detached exact C8)
   -> E8 (separate evidence-only sole-parent direct child; no owner release)
   -> A8 (checkpoint-only sole-parent direct child; only source of P8.0 PASS)
```

Any contract-candidate change creates a new `C8` and requires a fresh Batch 0. Infrastructure
failure consumes the candidate's single Batch 0 attempt; no same-SHA retry, report reuse, mixed
attempt, or quarantine is allowed. Any failure blocks entry and requires a separately reviewed new
contract candidate.

The historical chain `6d4f9946ab357a7d3193ea1680473fe923322eb0` ->
`4dc398d359806ab41ea702df54112956d17920ae` ->
`7e3cbace3d206aef5eb23a03d36878a00634c9a9` authenticates only its superseded
contract bytes. It cannot authorize this correction or any implementation. This corrected contract
must be committed as a replacement contract-only `C8` whose sole parent is exact `A7`, followed by
a fresh exact-SHA Batch 0 with no reused report or receipt, a new evidence-only `E8`, and a new
checkpoint-only `A8`.

### Local Engineering Trust Boundary

P8.0 runs under a `NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR` threat model. SHA-256 manifests and
self-seals provide byte integrity and drift detection while binding exact Git objects; they are not
signatures and do not prove operator identity, source authenticity, or execution authenticity. A hostile local administrator
can replace the process and is outside this local engineering gate.

The consolidated P0 disposition therefore records an engineering process attestation, not a
cryptographic production attestation. It must contain the fixed `authority`, `data_migration`, and
`security_privacy` lanes, close exactly all 13 P0 topics, bind each lane to the exact C8 commit,
tree, twelve-path diff and blob hashes, manifest, and report hashes, and record
`self_approved: false`. Contract authors and the primary integrator cannot substitute for those
independent review lanes. Neither the runner nor `E8` may authorize implementation; only the
separate checkpoint-only `A8` may open the engineering lane.

Production treats every local P8.0 seal and process receipt as non-authoritative. Cryptographic
execution, operator, environment, deployment, credential, and signature attestations remain an
external gate, and local evidence cannot be relabeled or reused to satisfy it.

The only allowed post-entry state is:

```text
contract_gate: P8.0 PASS
phase_8_engineering_implementation: ALLOWED
phase_8_engineering_lane: ENGINEERING_ONLY
phase_8_external_release_lane: BLOCKED_PENDING_EXTERNAL_AUTHORIZATION_AND_EVIDENCE
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
production_scheduler_off_activation: FORBIDDEN
production_v046_apply_or_switch: FORBIDDEN
V047_or_destructive_cleanup: FORBIDDEN
production_traffic: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

P8.0 closes only the engineering entry gate. It supplies no production evidence and changes no
migration, runtime, canary, or promotion status.

## Frozen Engineering Decisions

1. Java and Domain PostgreSQL remain the current formal business truth while the migration gates
   are pending. Temporal owns durable time and failure only for a separately approved persisted
   `TEMPORAL` epoch; it gains no general business authority. Graph and LCEL remain private
   cognition, and Redis remains a fan-out cache, never stream truth.
2. Every audit adapter returns explicit completeness and provenance. `UNKNOWN`, permission denial,
   query error, timeout, unsupported API, stale replica, or missing retention metadata becomes
   `BLOCK_DELETE`, never zero.
3. Active-reference zero requires two complete reports spanning the maximum applicable visibility
   and retention window, bounded replica lag, matching high-watermarks, stable query hashes,
   candidate/environment identity, and named owners.
4. `DETECTOR` can enumerate would-have-claimed work and emit bounded immutable observation,
   proposal, or audit records only. It cannot perform a formal reconciliation mutation, enqueue,
   advance a phase, claim a lease, or emit a formal result. Any unexplained detector candidate
   blocks `OFF`.
5. A `TEMPORAL` epoch can never be executed by a legacy scheduler. Rollback from `OFF` returns only
   to `DETECTOR`; legacy `EXECUTOR` remains limited to persisted `LEGACY` epochs.
6. V046 is expand-only in engineering. Production apply and reader/writer switch are external, with
   fail-closed lock, replica-lag, latency, parity, and archive thresholds. Rollback selects a
   compatible reader and preserves both stores.
7. The old stream remains read-only for at least one complete release cycle after an externally
   authorized switch. Archive manifests bind immutable partition, sequence, count, hash, object
   version, high-watermark, and creation receipt.
8. Capacity artifacts target three failure domains and 1,000 active rooms, but initial resource
   values and load shapes are design targets, not observed live baselines. Production measurements
   must be captured later from one immutable release candidate and deployment.
9. HPA cannot use CPU alone. Control and agent queues/pools are isolated; PDB, topology spread or
   anti-affinity, PgBouncer, reporting replicas, and OTel exporter health are mandatory.
10. V047 is a later, separately authorized destructive candidate. Prior checkpoint evidence may
    authorize its creation but cannot count as test evidence for the changed cleanup commit.

## Engineering Slices

| Slice | Implementation owner | Engineering deliverable | Focused proof | Forbidden boundary |
| --- | --- | --- | --- | --- |
| `P8-S1` reference and scheduler retirement | I1 | authoritative reference adapters, scheduler state machine, reconciliation, eligibility report | completeness, fail-closed unknowns, epoch fencing, one executor, detector non-mutation | production `OFF`, deletion, V047 |
| `P8-S2` stream lifecycle | I2 | V046 expand/backfill/validation/archive and compatible reader mechanics | additive DDL, restart safety, count/hash/audience/cursor parity, slow-consumer replay | production apply/switch, old-table drop |
| `P8-S3` topology and capacity | I3 | three-domain manifests, HPA/PDB/spread, pools, admission and capacity harness | render and policy checks, isolation, synthetic model assertions | production apply, real traffic, secrets |
| `P8-S4` observability and resilience | I4 | OTel, dashboards, alerts, runbooks, restore/DR/rotation tooling | config/schema/static checks, synthetic drills, old payload readability | real PITR/DR/rotation, secret material |
| `P8-S5` candidate and evidence | I5 | exact-SHA runner, scenario catalogs, evidence validator, checkpoint assembly | provenance, stop-on-fail, hash and same-candidate adversarial tests | self-signoff, external pass claims, V047 |

Before delegation, the primary must give each owner exact owned paths and exact forbidden paths.
No two writers may own the same file, and delegated agents may not stage unrelated changes. The
primary alone resolves shared files and integrates reviewed commits.

## Active-Reference And Retirement Contract

The reference inventory covers at least:

- Temporal Workflow type, Workflow/Child identity, Continue-As-New chain, Schedule, pending
  Activity, task queue, compatible Worker Build ID, room epoch, and retained History reachability;
- Graph thread/checkpoint/command/result/lease rows and GraphRegistry version reachability;
- Domain nonterminal `case_room_epoch` writer mode/fence plus workflow-build, graph-version, prompt,
  schema, policy, codec, and stream-version pins; pending `case_command`, outbox,
  `domain_operation`, Finalizer, lease, scheduler, stream cursor, old reader, and archive
  high-watermark state;
- nonterminal AgentRun V1 logical-run/attempt identity, executor ownership, hot-stream reader, and
  replay-cursor reachability;
- object-store schema, prompt, Artifact, archive, and payload-codec manifests, including old codec
  reader reachability and retained object versions/hashes;
- deployed Java/Python/API/worker/reader versions and every compatibility-reader path;
- retained-window frontend routes, Java/Python legacy API endpoints, and all callers;
- `agent_stream.v1` event, cursor, compatibility-reader, archive, and telemetry references; and
- remaining `memory_frame` readers and retired hearing endpoint or adapter call sites.

Every report row includes source system, reference class, query ID and hash, candidate version,
count, oldest/newest timestamps, retention boundary, scan high-watermark, replica-lag bound,
completeness status, owner, and `RETAIN`, `BLOCK_DELETE`, or `ELIGIBLE` disposition. Reports are
immutable and bind the candidate SHA, environment manifest, credentials class without secrets,
tool versions, timestamps, and SHA-256 hashes.

The fail-closed mapping is exact: `UNKNOWN`, `PARTIAL`, `ERROR`, timeout, permission denial,
unsupported reference class, parse/schema failure, stale or incomplete pagination, missing owner,
and invalid or inconsistent high-watermark all produce `BLOCK_DELETE`. None can default to zero.

The entire interval between the two authoritative zero scans must prove reference quiescence: every
producer, selector, deployment, compatibility reader, API/frontend caller, archive job, and codec
writer capable of creating a new reference is disabled, fenced, or observed at a monotonic durable
high-watermark. A new reference, changed target inventory, deployment change, selector change, or
regressed/unprovable high-watermark resets the interval and yields `BLOCK_DELETE`. High-watermarks
come from durable ledgers independent of hot partitions; they are never inferred only from
`MAX(...)` over data that retention or archive may remove.

An eligibility report is advisory and non-destructive. No tool in the engineering lane may delete,
retire, deregister, switch, or disable a production component automatically.

## Scheduler Retirement Contract

Each legacy scheduler, including room, deadline, handoff, and AgentRun-related schedulers, has its
own persisted ownership row, audit queries, observation window, owner, and rollback record. An
aggregate pass cannot hide one scheduler.

At A7 the concrete legacy-executor retirement candidates are `AgentRunRecoveryScheduler`,
`HearingFlowDeadlineScheduler`, and `HearingReviewHandoffRecoveryScheduler`. The
`TemporalCommandOutboxRelay`, domain-event recovery, projection reconciliation, room-epoch
bootstrap, SSE heartbeats, and Activity heartbeats are retained infrastructure or transport unless
a later exact reference audit separately proves them obsolete. They do not enter a blanket
`EXECUTOR -> DETECTOR -> OFF` transition merely because they are scheduled jobs.

1. `EXECUTOR` may serve only a persisted `LEGACY` epoch and must reject `TEMPORAL` epochs.
2. `DETECTOR` computes bounded would-have-claimed candidates and emits metrics plus immutable,
   read-only observation or reconciliation proposals. It performs no formal reconciliation
   mutation, business mutation, enqueue, lease claim, or phase transition.
3. Observation requires at least one complete business cycle and one release, zero unexplained
   detector candidates, no dual executor, no command loss, and reconciliation parity.
4. `OFF` stops detector scanning only after external release authorization. Engineering can test
   the guard but cannot activate it in production.
5. Removal is deferred to the separately authorized V047 candidate after all reference, retention,
   compatibility-reader, restore, and signature gates pass.

### Current A7 Scheduler Gaps Requiring P0 Repair

The accepted A7 baseline already contains Hearing scheduler modes and detectors. Phase 8 must not
mistake their existence for retirement correctness. The following current behaviors are explicit
P0 engineering gaps and block `OFF` until repaired and reviewed:

- `HearingSchedulerControl` rejects `TEMPORAL + OFF`, while `drainedOff()` returns
  `OFF + LEGACY`. Consequently `DETECTOR(TEMPORAL) -> OFF` cannot be represented without reverting
  the writer label and risking an authority regression. The persisted state model must represent a
  drained `OFF` scheduler without changing the epoch's writer authority.
- `AgentRunRecoveryScheduler` currently makes `DETECTOR` inspect V2/`TEMPORAL_ACTIVITY` rows rather
  than the V1/`LEGACY_WORKER` would-have-been executor candidates whose absence is required before
  retiring the legacy scheduler. The query, observation semantics, and regression tests must be
  corrected without executing or failing detected rows.
- `JdbcHearingSchedulerDetector` currently begins both scans from
  `hearing_temporal_projection`. A legacy deadline or handoff candidate with no projection row is
  absent from the result and can fabricate a zero. The authoritative query must include legacy
  candidates that lack projections and classify incomplete joins as blocking mismatches.

Until all three gaps are fixed, P0-reviewed, and included in same-candidate evidence, scheduler
retirement remains `BLOCK_DELETE` and production `OFF` remains forbidden.

## V046 Stream Lifecycle Contract

The engineering design is:

```text
expand partitioned target + global identity registry + archive manifest + high-watermark ledger
-> bounded idempotent backfill
-> transactional capture/dual-write by immutable event identity
-> exact V1/V2 count/hash/sequence/audience/actor/cursor/reset/terminal validation
-> externally authorized reader switch
-> externally authorized writer switch
-> old store read-only for at least one complete release cycle
```

Partition size is selected from measured evidence, not hard-coded from an invented live volume.
Because PostgreSQL partition-local uniqueness cannot preserve the existing global event identity
when the time partition key is absent from `(agent_run_id, attempt_id, sequence_no)` or event ID,
V046 must use an unpartitioned durable identity/idempotency registry, or an equivalently global and
authoritative mechanism. Adding time to a partition-local unique key is not an acceptable substitute.

For every captured or dual-written event, the immutable identity lookup, exact payload-hash conflict
check, target-event insert, and monotonic non-leading delivery high-watermark advance commit in one
Domain PostgreSQL transaction. The delivery high-watermark is the highest contiguous committed
sequence for the exact V1/V2 stream identity; it cannot cross a gap, lead an uncommitted event, or
regress. It survives independently of hot-partition archive or deletion and is never reconstructed
solely from a partition `MAX(...)`.

Backfill uses a separate resumable progress cursor. That cursor records scan work only and cannot
assert delivery completeness, cursor parity, or cleanup eligibility. Backfill remains idempotent
under crash/retry, and only the contiguous delivery high-watermark can advance reader/switch safety.

Validation must prove exact V1/V2 count, canonical hash, event identity, sequence, audience,
actor-id visibility, composite-cursor continuation and reconnect, reset behavior, and terminal-event
parity. Any old/new mismatch fails closed before switch. Slow consumers use bounded buffers and
reconnect through Domain DB cursor replay. Total Redis loss cannot erase committed delivery rows;
Redis remains non-authoritative.

V046 changes delivery storage only. A stream `final` marker, delivery high-watermark, archive
receipt, or migration receipt cannot authorize formal business completion, a formal message, an
Artifact, or any other business fact. The existing Java plus Domain PostgreSQL transaction and
Finalizer remain the sole formal commit boundary.

Rollback after any target-only write must remain target-aware or use a proven compatible union
reader; it must never select stale old-only data after the target high-watermark has advanced beyond
the old store. No rollback may discard, hide, resequence, or widen the audience of a target event.

`STREAM-013` remains a P0 release gate. No hot partition may detach or drop before the run is
terminal, hot retention remains at least 24 hours, compaction/archive object version and hash pass
readback verification, and the terminal event plus immutable AgentRun manifest remain retained.
Partition cleanup is release-only; archive metadata without verified object readback cannot
authorize it.

Production lock duration over five seconds, unbounded or threshold-breaching replica lag, command
or query p95 breach, count/hash/audience/cursor mismatch, or archive failure stops the external
operation before cutover. Engineering tests may exercise that behavior only on disposable data.

## Capacity, SLO, And Operations Targets

All numbers in this section are inherited design targets from master-plan section 12. They are not
current production observations, measured live baselines, or readiness evidence.

| Load or resource | Historical design target retained for Phase 8 | Required control |
| --- | --- | --- |
| Active rooms | 1,000, with at least 70% waiting or on Timer; 10x growth objective | bounded History and admission/backpressure |
| Room commands | 20/s steady; 50/s for 30 seconds | durable accept, bounded outbox/queue, recovery measurement |
| Agent triggers | 5/s steady; 20/s for 30 seconds; 250-run burst | global/tenant/room/node bulkheads and control isolation |
| Model concurrency | 100 sustained; 200 short burst | provider profile quotas, bounded queues, separate latency/cost signals |
| Evidence | batch target 100; room fan-out 8 | global/tenant semaphore and content-hash deduplication |
| SSE | 2,500 connections | bounded JVM buffers, disconnect and DB-cursor replay |
| PostgreSQL | separate Domain/Graph/Temporal roles and pools; peak pool target below 80% | PgBouncer, read replicas, failover and burst proof |

Initial deployment targets remain Java API 3 replicas at 2 vCPU/4 GiB, Java control worker 3 at
2/4, Java agent worker 3 at 4/8, Python Agent 4 at 4/8, LiteLLM 3 at 2/4, and OTel Collector 2 at
2/4 across three failure domains. These values are test starting points, not permanent allocations
or evidence that such capacity currently exists.

The retained SLO and rollback targets are:

- Java command/query availability 99.95% monthly;
- Temporal control availability 99.95% monthly;
- Agent execution availability 99.9% monthly, with provider exclusions separately approved;
- durable command acceptance p95 below 300 ms;
- Temporal dispatch p95 below 1 second;
- SSE reconnect/replay p95 below 2 seconds;
- outbox oldest age no more than 60 seconds;
- regional-boundary Domain committed transactions and acknowledged Temporal events at RPO 0;
- multi-AZ recovery below 5 minutes; and
- regional non-Temporal recovery at RPO 5 minutes and RTO 30 minutes.

W3C trace context spans HTTP, command/outbox, Workflow/Activity, AgentRun attempt, Graph command/node,
LCEL/model, Finalizer, domain event, and SSE. Async boundaries use links. Prometheus labels remain
low-cardinality; PII, evidence bodies, credentials, secrets, and private reasoning never enter
labels or logs.

Dashboards cover command/outbox, Temporal queue/history, AgentRun/stream, Graph/checkpoint/lease,
model/provider, projection/reconciliation, security, and DR. Alerts combine multi-window burn rate,
stuck age, heartbeat, queue lag, and exporter queue/drop health, and link to a named owner and
runbook. Process uptime alone is insufficient.

Restore procedures preserve the ordering:

```text
Domain DB -> Temporal -> Graph DB -> object storage
-> Java/Python workers -> projection reconciliation
```

Recovery never edits Temporal or database internal tables directly and never blindly replays a
completed external effect. Real PITR, DR, credential, certificate, secret, or codec rotation remains
in the external release lane.

Security artifacts use KMS/Vault-backed references rather than secret values. Java, Python,
Temporal workers, LiteLLM, OTel, migration, archive, and release operators have distinct identities
with least-privilege RBAC, NetworkPolicy, and mTLS boundaries. Evidence/archive objects are private,
versioned, immutable, actor-scoped, and covered by ACL and access audit. The concrete future assets
are:

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

The engineering mesh provider is frozen to Istio `security.istio.io/v1`. I3 may render
`PeerAuthentication` and `AuthorizationPolicy` only; CRD installation, sidecar interception, and
production enforcement remain external gates. A provider-neutral ConfigMap is documentation, not
mTLS enforcement. The I4-owned OTel workload uses stable labels
`app.kubernetes.io/part-of=after-sale-flow` and `app.kubernetes.io/name=otel-collector`, service
account `after-sale-otel-collector`, and OTLP ports `4317`/`4318`; I3 owns matching identity,
network, and mTLS policy but its kustomization does not import I4 paths.

Render-only assets cannot close runtime security gaps. Temporal Cloud TLS or mTLS credentials, a
trusted-proxy or direct-mTLS bridge into the Python ASGI identity extension, reporting read-replica
routing, an object-store workload-identity provider chain, and Langfuse redaction of identity
metadata plus raw prompt/output remain release-blocking until separately owned runtime code and
tests prove them. Static manifest success must keep those facts unresolved rather than relabeling
them as production security evidence.

Before any real traffic, a separately authorized external security preflight must produce an
immutable receipt for each of those five runtime controls and for Istio
security.istio.io/v1 CRD readiness, dataplane interception, strict mTLS enforcement,
authorization-policy enforcement, and the exact I3/I4 OTel namespace, labels, service account, and
ports. Every receipt is same-candidate, same-configuration, same-environment, same-deployment, and
same-attempt-lineage bound, and its exact control/status/evidence/context payload must be signed by
an authorized, unexpired, non-revoked, non-self-approving signer under an independent trust root.
Missing, failed, partial, stale, mixed-context, unsigned, invalid, untrusted, or secret-bearing
receipts block the production checkpoint and promotion; render success cannot substitute for any
receipt.

The P8.0 Batch 0 runner and generator are the only contract dry-run subprocess exception. They may
invoke only fixed allowlisted local Git and Python argv with `shell=False`, without user-controlled
command interpolation, and remain forbidden from network, cloud, database, Temporal, production
credential, or secret-environment access. Recovery, DR, object-restore, and rotation dry-run tools
under `scripts/phase8/recovery/` are fixture-only and must fail closed on all subprocess, network,
cloud, database, Temporal, or secret-environment access.

## Team Topology And Scheduling

Phase 8 uses one primary plus eleven adaptive delegated logical roles:

| Role class | Logical roles | Responsibility |
| --- | ---: | --- |
| Implementation | 5 | Own `P8-S1..S5` as concrete, disjoint write scopes with focused checks |
| In-flight P0 review | 3 | Temporal/scheduler correctness; migration/transaction correctness; security/runtime/evidence correctness |
| Verification | 2 | Broker isolated light shards and the single Maven/Testcontainers lane; capture attributable results |
| Lookahead | 1 | Read-only inventory, dependencies, next-wave briefs, and risk discovery |

The topology is adaptive, not a requirement to keep idle agents active. The primary starts roles in
dependency-aware waves, 10-20 seconds apart, and backfills released slots. All five implementation
ownership domains remain explicit even when capacity delays activation. Review starts only from a
stable diff or candidate, and verification starts only from a testable change.

While implementation is active, at least 50% of the three planned P0 review lanes must be active or
queued against reviewable P0-sensitive work. Post-commit review is P0-only. Security, Temporal,
transaction, migration, runtime configuration, and P0 review roles use the required high-capability
model and reasoning class defined by `AGENTS.md`; capacity or service errors do not weaken review.

At most two light test processes run concurrently. Exactly one Maven/Testcontainers lane exists;
the verification owners broker it rather than starting a second lane. Shared worktrees, build
directories, ports, containers, networks, and report paths force serialization. Full regression,
browser E2E, cross-service load, chaos, PITR/DR, and soak are held for the unified checkpoint and,
where real infrastructure is required, the external release lane.

On 429, 503, timeout, or usage failure, preserve the partial diff, worktree, notes, and test output;
reassign or retry after bounded backoff with the nearest allowed model class. Do not discard partial
work, cross an ownership boundary, or reduce P0 review.

## Engineering Verification Batches

### P8-BATCH-0: Contract Entry

Run only after the exact P8.0 contract candidate is frozen. Verify the `C7/E7/A7` chain, contract
status tokens, lane boundaries, ownership map, machine schedule, evidence tooling, inherited
targets, absence of Phase 8 implementation, absence of V047, and absence of production activation.
Execute `scripts/run_phase8_entry_checkpoint.py` from clean detached exact `C8`; it invokes the
allowlisted fixed local Git/Python argv with `shell=False` and uses
`scripts/generate_phase8_entry_evidence.py` to seal the attempt.
The result remains evidence-incomplete until committed separately as `E8`, and implementation
remains blocked until the sole-parent checkpoint-only `A8` validates that evidence and records
`P8_0_ENGINEERING_ENTRY_PASS`.

### P8-BATCH-1: Foundation

After the first reviewed implementation wave, run deduplicated focused checks for reference-query
completeness, scheduler guards, additive V046 syntax and compatibility, deployment policy, OTel
schema, and evidence-runner adversarial cases. This batch cannot activate a runtime or environment.

### P8-BATCH-2: Engineering Integration

After all five slices integrate, run the scheduled disposable PostgreSQL migration/restart tests,
scheduler transition and reconciliation tests, deployment rendering and policy tests, queue/pool
admission tests, alert/runbook/codec checks, captured-History replay, authorization and privacy
checks, and explicit no-delete/no-V047/no-production-activation tests.

### P8 Engineering Candidate Checkpoint

Freeze one clean immutable engineering candidate only after all owned checks and P0 reviews close.
The candidate evidence must remain same-SHA and may report only:

```text
engineering_checkpoint: PASS | FAIL
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING | FAIL
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
next_phase_permission: EXTERNAL_PRODUCTION_CHECKPOINT_ONLY | BLOCKED
```

It cannot mark a production `GATE-*`, migration promotion, canary, or release as passed.

## Unified External Production Checkpoint

The external checkpoint uses one authorized release commit, immutable image set, configuration,
environment manifest, and deployment. It stops at the first failure and preserves every attempt:

### Separately Authorized V046 Release Sequence

The following is a required future release sequence inside the same-candidate unified checkpoint,
not present authorization. Current production V046 apply or switch remains `FORBIDDEN`:

1. Apply the additive expand schema and seal its apply receipt.
2. Complete bounded resumable backfill, prove the separate progress cursor, and seal proof that the
   highest contiguous nonregressing delivery high-watermark is complete without a gap.
3. Enable transactional capture/dual-write and seal its identity, exact-hash conflict, target-row,
   and atomic high-watermark receipt.
4. Seal exact old/new count, hash, sequence, actor/audience, reset, terminal, reconnect, and
   composite-cursor parity.
5. Perform the compatible reader switch and seal its observation and rollback receipt.
6. Perform the writer switch and seal its target-aware rollback receipt.
7. Keep the old table read-only for at least one complete release cycle and seal the retention,
   archive-readback, terminal-event, immutable AgentRun manifest, and `STREAM-013` receipt.

`MIG-008=PASS` requires every receipt above, bound to the same commit, images, configuration,
environment, deployment, and attempt as the successful unified checkpoint. A missing, mixed, stale,
synthetic, or failed receipt leaves `MIG-008: PENDING_PROMOTION`.

1. Generate the complete 279-check and 99-baseline evidence tables; verify P0 closure and every
   migration, replay, compatibility, privacy, and no-destructive-cleanup prerequisite.
2. Build Java, Python, and Vue plus replay suites, then deploy the production-equivalent three-
   failure-domain topology and seal version/configuration manifests.
3. Complete the separately authorized external security preflight, cryptographically verify every
   same-context receipt, and stop before real traffic if any required runtime control or Istio
   enforcement receipt is missing, failed, partial, stale, mixed, unsigned, or untrusted.
4. Run USER, MERCHANT, PLATFORM_REVIEWER, ADMIN, and SYSTEM boundary E2E plus all 99 baselines.
5. Run the retained 60-minute 1,000-room, 250-AgentRun, 2,500-SSE, and 100-model steady profile.
6. Inject the retained 30-second 50-command/s, 20-Agent/s, and 200-model burst and verify bounded
   recovery within 30 minutes without violating control-queue SLOs.
7. Under load, inject duplicate/order/delay/hash faults and failures across Java, Python, Redis,
   LiteLLM, Temporal, and Domain/Graph PostgreSQL.
8. Exercise active Temporal/Graph rollout and rollback, captured History replay, checkpoint/lease
   recovery, and compatible old-version pinning.
9. Run cross-tenant/case/actor/role fuzz, PII/private-reasoning/log scans, and authorized secret,
   certificate, credential, and codec rotation.
10. Run Domain PITR, Graph/object restore, Temporal regional DR, reconciliation, and external-effect
   no-replay checks.
11. Complete the 24-hour soak and obtain Architecture, Java, Python, SRE, Security, and Business
    signatures for `GATE-010`.

These steps are historical required targets until actually executed. This plan records no live
room count, current capacity, production deployment, drill, or gate result. Reports from different
SHAs, images, configurations, deployments, environments, or attempts cannot be combined.

Hard failures include a duplicate formal message, Artifact, or external effect; loss of an accepted
command or acknowledged Temporal event; stale revision/fence overwrite; private-data leak;
unrecoverable checkpoint; unapproved execution; illegal phase transition; SLO breach; incomplete
reference audit; or evidence provenance mismatch. A hard failure returns to focused diagnosis and
cannot be erased by rerunning only the failed check.

## V047 Destructive Cleanup Gate

`V047__remove_legacy_orchestration.sql` must not exist in the P8.0 contract candidate or the Phase 8
engineering candidate. Its creation requires a later, explicit authorization after all of the
following are real and accepted:

- the unified production checkpoint passed on one immutable release candidate and deployment;
- every migration gate `MIG-000..008=PASS`, including separately authorized promotion evidence for
  the currently pending earlier migrations and `MIG-008`;
- two complete zero-reference scans span the full visibility and retention window;
- no legacy reader, detector candidate, old Build/Graph/thread/epoch/run/outbox/lease, or stream
  cursor reference remains;
- V046 old storage stayed read-only for at least one complete release cycle;
- PITR backup/restore and compatible rollback evidence is accepted; and
- Architecture, Java, Python, SRE, Security, and Business owners sign the cleanup authorization.

That authorization permits creation of a separate V047 candidate only. Because V047 changes the
commit, earlier evidence is authorization context, not test evidence; the cleanup candidate must
run its own migration compatibility, replay, security, and baseline checks before any release.

## Rollback And Failure Policy

- Reference-audit uncertainty retains every version, reader, writer, scheduler, endpoint, and row.
- Scheduler rollback permits `OFF -> DETECTOR`; it never permits a legacy executor for a
  `TEMPORAL` epoch.
- V046 rollback stops backfill/switch jobs, selects a compatible reader, and preserves additive new
  data plus the old store.
- Deployment rollback pins compatible Worker and Graph versions and keeps additive schema.
- Restore and reconciliation use public supported APIs and ledgers, never direct internal-table
  edits.
- External effects use recorded idempotency and compensation; they are never replayed blindly.
- Failures are classified `PRODUCT`, `FIXTURE`, `INFRA`, `CONTRACT`, or `EXTERNAL_GATE` before any
  rerun. Same-SHA infrastructure retries retain earlier attempts.

A Phase boundary is an internal checkpoint, not evidence of completion. Phase 8 finishes only
after the separately authorized unified production checkpoint records real same-candidate evidence,
or it records a genuine external blocker without claiming `PASS`.
