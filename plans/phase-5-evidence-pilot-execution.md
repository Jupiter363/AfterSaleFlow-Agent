# Phase 5 Evidence Pilot Execution Plan

## Status

```text
plan_status: PHASE_5_ENGINEERING_ACTIVE
engineering_execution: ALLOWED_WITH_DISABLED_JAVA_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS
contract_gate: P5.0 PASS
candidate_scope_integrity: PASS_AT_e70492a11e23307382ea762d0e8e7f57ab58870b
phase_4_engineering_checkpoint: PASS
promotion_gate: PENDING
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
contract_candidate_permission: CONSUMED_BY_ACCEPTED_P5_0
next_phase_permission: PHASE_5_ENGINEERING_ONLY
team_shape: primary + 5 simultaneously active delegated implementation owners
java_evidence_ledger_writer: SOLE_FORMAL_WRITER
graph_runtime_default: DISABLED
allowed_pre_promotion_runtime: DISABLED or Java-signed synthetic SHADOW
temporal_evidence_allocation: FORBIDDEN
formal_graph_sink: FORBIDDEN
real_case_shadow: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
pre_entry_contract_correction: ADR_0013_CONSUMED_AND_EXPIRED_AT_P5_0_ACCEPTANCE
```

P5.0 passed at contract candidate `e70492a11e23307382ea762d0e8e7f57ab58870b`. The separate entry
evidence commit `e5f6019b71a90174c09aecdcba336bd12788b75b` archives 396 passing
tests and is authenticated by the
[P5.0 entry checkpoint](../docs/runbooks/temporal-first/phase-5-p5.0-entry-checkpoint.md).
This grants Phase 5 engineering only. It is not a Phase 5 engineering checkpoint or Phase 6
permission, and it does not relax any runtime or promotion gate.

The accepted contract candidate is based on the Phase 4 evidence commit
`b8697ce7a46f4494d250d21f27a076f0711ae04d`. Its candidate
`1ba6e17fa2182156825f42d7e243978cf23ccdb4` passed the Phase 4 engineering checkpoint and grants
`PHASE_5_ENGINEERING_ONLY`. The exact tracked checkpoint is
`test-reports/temporal-first/phase-4-20260722-1ba6e17f/phase-4/phase-metrics.json`.
That handoff permitted P5.0 Batch 0 under the repository-owner-approved
[ADR 0012](../docs/architecture/adr/0012-phase-5-evidence-engineering-exception.md). The one-time
correction of the then-unaccepted Evidence v2 contract was governed by
[ADR 0013](../docs/architecture/adr/0013-phase-5-evidence-pre-entry-contract-correction.md).
ADR 0013 expired when P5.0 was accepted; later authority-bearing contract changes require a new
schema version, compatibility plan, and accepted ADR.

ADR 0012 separates an **engineering lane** from a **promotion lane**. The engineering lane builds
and proves fail-closed components with disabled or Java-signed synthetic inputs. `MIG-004`, the
100-file public product contract, production asset authorization, real shadow, canary and
production promotion remain external to P5.0 and stay pending. `GRAPH-016` is implemented and
proved inside Phase 5 by `P5-E1`; it is an engineering exit condition, not its own prerequisite.

This plan is governed by the
[P5.0 Evidence Contract Pack](../docs/runbooks/temporal-first/phase-5-p5.0-contract-pack.md) and the
machine-readable [Phase 5 test batches](./phase-5-evidence-pilot-test-batches.yaml). Source authority
is `plans/temporal-langgraph-room-refactor.md` section 7.6 plus the platform acceptance checklist,
machine manifest, and current-room behavior baseline.

Implementation was blocked until Batch 0 ran from the exact clean detached candidate and the
resulting evidence was committed separately. Those conditions are now satisfied only by the
candidate/evidence pair above. No later green source suite or engineering commit may be relabeled
as P5.0 entry evidence.

The reviewed generic bulkhead foundation is integrated as
`ca18e53e6f051004d20c6f8879f6ed440ab0dc20` followed by the Evidence room-cap correction
`09d65875ff6edfbc76d0d2a0e42610690e500bfd`. This proves a process-local primitive and the
per-room cap of eight; it does not close cross-replica tenant/global `GRAPH-016`. That check remains
an engineering exit obligation owned by `P5-E1`.

### Candidate-Scope Repair Ledger

The following status is retained only as the historical pre-entry context for this ledger:

```text
contract_gate: P5.0 NOT_RUN
candidate_scope_integrity: PRE_ENTRY_CONTRACT_CORRECTION_REQUIRES_FRESH_EXACT_SHA_BATCH_0
engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE
```

The contract material frozen by `6e23c580dc4ac53f2c3b8e8ca2894388fd9c3500` was not a successful
P5.0 candidate. Diagnostic Batch 0 attempts exposed baseline and evidence-capture defects. The
following repairs are permitted before the primary selects a new exact candidate because the
contract gate is still `NOT_RUN`, engineering implementation remains blocked, and none of them
adds Phase 5 Evidence behavior:

| Repair | Classification | Candidate-scope constraint |
| --- | --- | --- |
| `99cdd435`, `d76fde17` | Entry evidence authentication/serialization | Authenticate the accepted Phase 4 bundle across checkout line endings and publish LF-stable evidence bytes; never rewrite or relabel Phase 4 evidence |
| `24a705dc` | Test-fixture isolation | Keep formal-sink architecture fixtures outside Spring component discovery; production assembly is unchanged |
| `a3be6744`, `e97e1341`, `fb69bd4c` | Existing Spring baseline proxyability | Remove only class-level `final` and prove class proxies preserve caller transaction, `MANDATORY` propagation, SQL/lock order, idempotency and Java authority |
| `c9e6c7ba` | Entry runner report retention | Retain long Surefire reports under deterministic short artifact names without changing selected suites or acceptance rules |
| `b9201f0bc1d9ad7fca1cc0ca7b68cd75e62a503a` (`79b8c797522671aa46f2299198eab7ba6f651006` source) `EvidenceApiIntegrationTest` admission and cleanup | `FIXTURE` baseline correction | After `completeIntake`, use the domain admission transition to reach `EVIDENCE_OPEN`; use the existing audited, case-scoped purge service, rebuild unconditionally, and count only this case's Evidence. Do not change `SecurityConfiguration`, `EvidenceController` authorization, or production access rules |
| `b9201f0bc1d9ad7fca1cc0ca7b68cd75e62a503a` (`79b8c797522671aa46f2299198eab7ba6f651006` source) `occurred_at` response mapping | `PRODUCT` baseline contract repair | Normalize each non-null response value to the same instant at UTC and preserve null so immediate-create and PostgreSQL-reload views have one canonical JSON `Z` representation. Do not alter the stored instant, request acceptance, authorization, writer ownership, or runtime mode |

These repairs do not inherit any result from `6e23c580`, `d76fde17`, or another diagnostic SHA.
The UTC mapping is a baseline `PRODUCT` repair because the accepted API response must not change
representation depending on whether the same item is returned directly after creation or reloaded
from PostgreSQL. `withOffsetSameInstant(UTC)` preserves the business instant and only canonicalizes
the response boundary. At source repair SHA
`79b8c797522671aa46f2299198eab7ba6f651006`, Evidence API/service tests passed 15/15 and the
retained Security and Intake progress checks passed 8/8 and 4/4, for 27/27 focused checks. Main
integration `b9201f0bc1d9ad7fca1cc0ca7b68cd75e62a503a` carries an identical patch on exactly the
three repair paths; no exact-`b9201f0b` test execution is claimed. These are repair checks, not P5.0
Batch 0 evidence.

The primary must review the final diff, include both classified sub-repairs above, run the focused
repair checks, and then execute all of Batch 0 from one fresh clean detached SHA. Any additional
product source, migration, runtime, public-contract, or authorization change reopens candidate-scope
review and cannot be classified as an entry repair by assertion alone.

### Quarantined Diagnostic And Atomic Contract Correction

The exact candidate `45d7f087eafe4f50be0d491b3d612446a3e1e94e` ran a diagnostic Batch 0
whose source suites reported static 122, Python 61, Java 67, and frontend 97 tests passing, for 347
total. Its local manifest records `status=PASS`, `batch_0=PASS`, source `accepted=true`, and
`contract_gate=P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT`. It is quarantined because that run
did not cover a P0 authority invariant: `evidence-batch-manifest.v1` lacked a direct Java ES256
signature, and its profile used one ambiguous output pin instead of separate assessment and
terminal proposal pins. Source artifacts exist locally, but no repository P5.0 entry-evidence
bundle or checkpoint was assembled, committed, or accepted. ADR 0013 overrides the local PASS and
accepted flags for entry-gate purposes, and no source result is inherited by a later candidate.

ADR 0013 permits one atomic in-place correction only because the affected v1 schemas have never
been accepted by P5.0, released, promoted, selected by an epoch, or consumed by a compatible
reader. The corrected contract candidate must contain all of the following together:

- direct manifest `signature_algorithm=ES256`, `signing_key_id`, and `signature` fields, with
  schema `x-signature` metadata declaring `input_encoding=ASCII_LOWERCASE_HEX_TEXT` and
  `encoding=JOSE_P1363_BASE64URL`; asset capability uses the same encodings;
- a lowercase `manifest_hash` preimage omitting exactly `manifest_hash` and `signature`, with ES256
  signing the ASCII bytes of the 64-character lowercase hex text
  (`ASCII_LOWERCASE_HEX_TEXT`), not decoded digest bytes;
- independent `assessment_output_schema_version=evidence-item-assessment.v1` and
  `terminal_output_schema_version=evidence-batch-proposal.v1` pins;
- dual-pin propagation through manifest, item, terminal, and projection contracts, while asset
  capability binds `profile_versions_hash` and finalization receipt does not claim profile fields;
- no `authorization_proof_ref` field anywhere in the Evidence v2 contract set;
- fail-closed `RoomGraphCommand` identity, room-epoch, thread, snapshot, graph/checkpoint, and
  invocation/profile `x-gateway-cross-binding` with failure `BEFORE_CHECKPOINT_MUTATION`, followed
  by independent Graph lease-fence enforcement; the signed manifest binds the distinct Java room
  fence and Java Finalizer revalidates it;
- `domain_snapshot_ref` SHA-256, exact size, and content-addressed immutable URI over the full RFC
  8785 canonical signed manifest bytes, verified before parsing; internal `manifest_hash` is then
  recomputed with hash and signature omitted before Java ES256 verification, and the two hashes are
  never interchangeable;
- `RoomGraphCommand` invocation and Graph registry output mapped to terminal
  `evidence-batch-proposal.v1`, while only the internal item LCEL parser uses
  `evidence-item-assessment.v1`;
- regenerated hashes for every positive and negative fixture plus Python validation and Java
  parity for the same corrected bytes.

All implementation owners consume this exact authority mapping:

```text
snapshot_payload_hash_scope: FULL_RFC8785_CANONICAL_SIGNED_MANIFEST_BYTES
snapshot_payload_size_scope: EXACT_FULL_CANONICAL_SIGNED_MANIFEST_BYTES
snapshot_payload_uri: IMMUTABLE_CONTENT_ADDRESSED_BY_SNAPSHOT_SHA256
internal_manifest_hash_scope: RFC8785_OMIT_MANIFEST_HASH_AND_SIGNATURE
snapshot_and_internal_hashes_interchangeable: false
validation_order: SNAPSHOT_SHA_SIZE_URI -> PARSE_CANONICAL_JSON -> INTERNAL_MANIFEST_HASH -> JAVA_ES256_SIGNATURE
room_graph_command_output_schema_version: evidence-batch-proposal.v1
graph_registry_output_schema_version: evidence-batch-proposal.v1
item_lcel_parser_output_schema_version: evidence-item-assessment.v1
java_room_fence_source: SIGNED_MANIFEST
graph_lease_fence_source: CURRENT_GRAPH_LEASE
fence_tokens_interchangeable: false
engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE
```

Partial correction is forbidden. The primary must freeze a new exact clean detached SHA, run the
full P5-BATCH-0 from a fresh detached worktree, and commit entry evidence separately. After the first
P5.0 acceptance, any authority field, hash preimage, signature scope, trust binding, or output-pin
change requires a new schema version, compatibility plan, and accepted ADR.

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

- No Phase 5 feature source, behavior expansion, migration, runtime, public contract, or UI
  implementation is authorized before exact-SHA P5.0 entry evidence is committed. Only the
  bounded, semantics-preserving entry repairs in the candidate-scope ledger and the atomic
  still-unaccepted contract correction authorized by ADR 0013 are permitted.
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

1. The team is one primary plus five simultaneously active delegated implementation owners. Wave A
   gives D and E independent contract/test-harness work while A-C build the three foundations;
   dependency-ordered integration continues in Wave B without releasing either owner.
2. The primary owns shared contracts, exact path grants, integration order, the single heavy-test
   token, failure classification, and the final immutable candidate checkpoint.
3. The public Evidence submission batch remains 1-50 until product/API/UI approval is recorded.
   Closed contract tests and disabled or signed synthetic fixtures may exercise 1, 8 and 100 unique
   Evidence IDs to build the proposed future capability. Hearing supplementation stays at 0-50 per
   party.
4. `evidence.v2` consumes one immutable `evidence-batch-manifest.v1` with at most 100 stable item
   keys. The manifest directly carries a Java ES256 signature over its canonical hash, which binds
   actor scope, epoch/fence, object version, content hash, owner, visibility, and the separate
   assessment and terminal output pins. Python never derives authorization from content.
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
13. Wave A additive Evidence binding DDL uses
    `V043_4__evidence_graph_bindings.sql`, after the existing Intake migrations
    `V043_2__intake_shadow_comparisons.sql` and
    `V043_3__intake_signed_synthetic_admission.sql`. The candidate must freeze its exact schema
    before implementation. Wave B may not edit `V043_4`. `P5-R2` must first amend or accept a new
    additive migration identity in an erratum candidate and evidence commit; until then the only
    permitted reference is the placeholder `P5-R2_AUTHORIZED_MIGRATION`. V044 and V045 remain
    reserved by existing plans.
14. Runtime before promotion is only `DISABLED` or Java-signed synthetic `SHADOW`. Real party data,
    a formal sink, `TEMPORAL` allocation, canary, and promotion remain unreachable.
15. Phase 5 does not alter Hearing supplementation contracts or behavior.

## Entry Gates

### P5.0 Engineering Entry Gate

The primary may record P5.0 `PASS` only after all of the following are immutable and cross-linked:

- A Phase 4 engineering checkpoint from one accepted candidate grants
  `next_phase_permission: PHASE_5_ENGINEERING_ONLY`.
- ADR 0012 remains accepted and the candidate explicitly records `MIG-004`, the public 100-file
  approval and production asset authorization as pending external promotion gates.
- The public Evidence submission limit remains 50. Only closed schemas, tests and Java-signed
  synthetic manifests may exercise 1, 8 and 100 items before product approval.
- `GRAPH-016` is assigned to `P5-E1` and remains mandatory Phase 5 engineering exit evidence; it is
  not represented as an already-passed P5.0 prerequisite.
- Asset loading is restricted to Java-signed synthetic capabilities and immutable synthetic
  fixtures; real party data and production object references remain unreachable.
- This execution plan, the test-batch policy, the P5.0 contract pack, the closed Evidence v2
  schemas/fixtures, their static contract tests, and the closed candidate-scope repair ledger are
  frozen in one contract candidate. No Phase 5 feature source, migration, public-contract, runtime,
  or authorization change beyond ADR 0013's one-time pre-entry correction is present; every
  pre-entry baseline repair is listed and reviewed.
- ADR 0013's manifest signature and split-pin correction is complete as one atomic diff; all
  fixture hashes are regenerated, Python validation and Java parity pass over the same contract
  bytes, and the quarantined `45d7f087` diagnostic contributes no accepted repository
  entry-evidence checkpoint.
- Batch 0 passes from that exact clean detached contract-candidate SHA and its report hashes,
  commands, durations, exit codes, environment, and protected worktree exceptions are committed in
  a later entry-evidence commit.
- Each delegated brief names exact owned and forbidden paths, input contracts, T0 checks, deferred
  centralized batch, review partner, and commit-sized definition of done.

The Phase 4 handoff, ADR 0012, public-limit, synthetic-only and owner-brief conditions are now
frozen. `engineering_execution` remains blocked while this exact candidate awaits Batch 0 and a
separate entry-evidence commit; delegated implementation cannot start before both exist.

### Promotion Entry Gate

Engineering completion never implies promotion. Promotion also requires independent
`MIG-004=PASS`, product/API/frontend approval of the public 100-file contract, approved production
asset authorization, production identity/key rotation, private object ACLs, immutable storage,
real-data authority, exact image/workflow/graph/profile versions, minimum sample/observation
windows, SLO/error budgets, rollback authority, and compatible readers. The promotion candidate
must pass authorized real shadow before a separately approved new-epoch cohort sequence. This plan
cannot mark any of those gates `PASS`.

## Vertical Slices

| Slice | Lead | Production-shaped behavior | Focused evidence | Rollback point |
| --- | --- | --- | --- | --- |
| `P5-S1` graph and reducer | A | Manifest-backed 1/8/100 item graph, deterministic dispatch waves and keyed proposal | bounds, recovery, order, duplicate/conflict properties | disable `evidence.v2`; retain checkpoints |
| `P5-S2` Temporal process | B | Shared deadline, warning, completion/expiry ordering, Java receipt orchestration | time-skipping, duplicate Signal, kill/replay, commit-response loss | stop new child provisioning; retain active History |
| `P5-S3` Java authority | C | Manifest minting, asset authorization, formal assessment/finalizer, additive bindings | transaction/idempotency/fence/privacy/admission tests | stop formal adapter; reconcile receipts; no DDL rollback |
| `P5-S4` compatible experience | D | Disabled/synthetic 100-card rendering, private/reviewer views, active run, timer and history projection; public requests remain capped at 50 | API/store/view/a11y/role-switch focused tests | select legacy reader; retain additive fields |
| `P5-S5` reliability/release | E | signed synthetic shadow, bulkheads, parity, fault matrix, selector and rollback controls | crash/race/queue/hash/privacy/no-formal-sink evidence | set cohort zero/disable Graph; preserve ledgers |

## Team, Ownership, And Waves

| Owner | Owned concern | Typical future paths | Forbidden boundary |
| --- | --- | --- | --- |
| A | `evidence.v2`, bounded scheduler, state, reducers, recovery properties | `python-agent-service/app/graphs/evidence/**`, narrow graph tests | Java, frontend, Domain DB, formal merge |
| B | Evidence child Workflow, command loop, timers, Activity contracts | `java-api-service/**/workflow/temporal/room/evidence/**` | Domain fact implementation, Python, UI, Hearing behavior |
| C | Java manifest/asset authority, mode guards, Finalizer, durable receipt projection | Evidence application/persistence adapters and exact tests, plus only the migration path accepted by `P5-R2` | Workflow decisions, Graph DB, frontend, Hearing supplement |
| D | Java projection/API compatibility and Evidence Vue/store behavior | EvidenceProcessProjectionQuery, EvidenceController process-projection route, view/API/store and focused tests | selectors, Temporal/Python runtime, InternalEvidenceController, formal rules |
| E | production Graph permit queue, local Java bulkhead parity, selector, observability, crash/rollback harness | Graph `G004`, permit repository/lifecycle/readiness/restore/bindings and narrow Java shadow paths | formal activation, secrets, deployment, other rooms |
| R | contracts, shared fixtures, exact briefs, integration, reviews, test tokens, candidate evidence | shared contract/plan/report/config paths explicitly granted | duplicating delegated slice implementation |

Path families are not blanket permissions. Before delegation, the primary records exact owned and
forbidden files. Shared worker assembly, public contracts, migration identity, selector wiring, and
candidate evidence remain primary-controlled. No owner stages unrelated changes.

### Wave 0: Contract And Entry Evidence

R freezes the P5.0 candidate only after the Phase 4 engineering handoff exists and ADR 0012 remains
accepted, runs Batch 0 from that SHA, and commits entry evidence separately. Owners receive only
their brief and relevant YAML excerpt. No owner may implement from this pre-checkpoint plan or from
an untested candidate.

### Wave A: Five Parallel Foundations

- A owns `P5-S1` Graph/reducer work.
- B owns `P5-S2` Workflow/timer work.
- C owns `P5-S3` Java authority and additive persistence.
- D owns compatible projection contracts and baseline API/UI test fixtures that do not depend on
  the final Graph/Workflow implementation.
- E owns bulkhead/no-formal-sink harnesses, metrics contracts and signed-synthetic test fixtures.
- R integrates one reviewed commit at a time, freezes one clean detached merged SHA, and runs the
  three-source `P5-BATCH-1` checkpoint once. The Python and static sources are token-free; the one
  Maven source owns the single heavy token and uses `forkCount=1`. Batch 1 runs no frontend,
  browser, database, real-provider, or formal-sink flow.

`P5-BATCH-1` consumes an external machine-readable task-binding document for `P5-A1`, `P5-A2`,
`P5-B1`, `P5-B2`, `P5-C1`, `P5-C2`, `P5-D0`, and `P5-E0`. Every record binds the reviewed task
commit, declared review partner, P0 review `PASS`, and exact T0 command IDs with `PASS`. The runner
verifies every task commit is an ancestor of the merged candidate. `P5-R1` is completed by this
integration and checkpoint; it is not a circular pre-run prerequisite.

The runner writes only under a fresh `.codex-run` directory, normalizes three candidate-bound JUnit
reports, seals its manifest, and permits a same-SHA retry only after an `INFRA` classification.
`PRODUCT`, `FIXTURE`, and `EXTERNAL_GATE` block the candidate. The generator atomically publishes
the matrix-declared eight-file LF-stable evidence bundle in a separate evidence-only commit. That
bundle alone does not open Wave B: R must subsequently authenticate the tested and evidence commits
in the Wave A checkpoint and update the still-blocked integration barrier.

Wave A acceptance uses a fail-closed four-commit handoff after the tested candidate: the eight-file
evidence-only commit, a reviewed tooling-only commit, a three-file acceptance-evidence-only commit,
and a state-transition-only commit. The acceptance runner reads the fixed eight files from Git blobs,
requires the tested candidate to be the evidence commit's sole parent, independently recounts
`120 + 144 + 98 = 362` JUnit cases with zero failures, errors, or skips, and keeps promotion plus
`MIG-004` and `MIG-005` pending. The generator result is
`PASS_AWAITING_STATE_TRANSITION_COMMIT`; it does not open the barrier and does not embed its future
commit SHA. The final state-transition commit must be the direct child of the acceptance evidence
commit, bind all four earlier full SHAs, modify only the three Phase 5 governance plans, leave the
candidate wave and all runtime/promotion gates blocked, and only then set `P5-WAVE-A-INTEGRATED` to
`OPEN`, Wave A to `INTEGRATED`, Wave B to `READY`, and `P5-R2` to `READY`.

The task-binding input is an external LF JSON file with
`schema_version=phase5-wave-a-task-bindings.v1`, the full merged `candidate_commit`, and the exact
ordered eight-task list. Each task carries `id`, full `commit`, `review_partner`,
`p0_review=PASS`, and `t0={result: PASS, command_ids: [...]}` exactly as declared by the owner
brief. Keep this input outside the candidate worktree; the runner validates it and archives the
canonical copy into its run directory.

From the clean detached merged candidate, R executes:

```powershell
$candidate = git rev-parse HEAD
$run = ".codex-run/phase5-wave-a-$($candidate.Substring(0, 12))"
$taskBindings = Resolve-Path "D:\evidence-input\phase5-wave-a-task-bindings.json"
D:\miniconda\python.exe scripts/run_phase5_wave_a_checkpoint.py `
  --candidate-commit $candidate `
  --execute `
  --run-dir $run `
  --task-bindings $taskBindings `
  --environment-id local-phase5-wave-a
```

After a source failure, R classifies before retry. Only `INFRA` may use `--resume` on the same SHA;
all other classifications block the candidate. After a PASS manifest, assemble without rerunning:

```powershell
$release = "phase-5-wave-a-20260723-$($candidate.Substring(0, 8))"
D:\miniconda\python.exe scripts/generate_phase5_wave_a_evidence.py `
  --release-id $release `
  --candidate-commit $candidate `
  --base-commit 496d0d459b97000f62742fe064d8ef70956ea419 `
  --execution-manifest "$run/phase5-wave-a-execution-manifest.json"
```

### Wave B: Experience And Hardening

- R first closes `P5-R2`, the primary-owned migration-contract erratum/candidate/evidence gate after
  Wave A acceptance. It may amend or accept one new additive migration identity, but no concrete
  filename is authorized before that recorded decision: plans name it only as
  `P5-R2_AUTHORIZED_MIGRATION`. R2's exact candidate surface is ADR 0014, the P5.R2 checkpoint
  runbook, its runner/static gate, and `test-reports/temporal-first/<release-id>/phase-5-r2-migration-contract/`.
  Its static gate runs on one clean detached candidate SHA; a separate evidence-only commit records
  that SHA, the concrete additive migration filename, command results, checksums, and artifact
  SHA-256 values before C3 dispatch. `V043_4` remains immutable. C and B then complete the two
  post-Wave-A prerequisites. `P5-C3` creates the durable Java finalization receipt ledger and
  validated terminal-summary sidecar only through the path accepted by `P5-R2`. C2 remains port-only;
  C3 atomically validates and locks a trusted current-authority snapshot (tenant/case/epoch, Java
  room fence, process/room/source revisions, and current fact/source allowlists), immutable
  actual-load receipt ref/hash, receipt binding, proposal hash, and current applicable fences before
  a new receipt insert. An exact committed receipt replay is returned by tenant plus `operationKey`
  and `requestHash` even after current authority or Graph lease changes. `P5-B3` adds a concrete,
  unregistered production `EvidenceRoomActivities` read adapter backed by the C3 durable ledger and
  operational store; B3 separately validates current recovery authority before exposing a Java-readable fenced operational recovery
  projection and reconciles production `EvidenceRoomActivities` only from durable receipt refs and
  current Graph lease validation. Neither task may fabricate terminal or recovery state from
  Temporal memory, create a formal sink, or allocate `TEMPORAL` Evidence.
- D starts `P5-D1` only after `P5-C2`, `P5-C3`, `P5-B3`, and the still-blocked
  `P5-WAVE-A-INTEGRATED` barrier are satisfied. It adds `EvidenceProcessProjectionQuery` and the
  authenticated `GET /api/disputes/{caseId}/evidence/process-projection` route on
  `EvidenceController`, with a controller test and a Java adapter Maven T0 selector. The query is
  under `workflow/projection/evidence`, not the formal Evidence application package. The response
  is private and `no-store`; the API, store, and view must test stale role changes and history
  behavior. `InternalEvidenceController` is explicitly untouched.
- E completes `P5-S5` with production Graph PostgreSQL `G004` durable permit admission, not a
  process-local Java bulkhead. The database owns a fair queue and atomically grants room, tenant,
  and global permits; leases have fence tokens, cancellation, expiry/takeover, and crash recovery.
  Evidence async item admission and takeover validate the current Graph lease. Permit, Graph lease,
  and Java finalization fences are distinct and non-interchangeable. Lifecycle, readiness, restore,
  production bindings, unit tests, and integration tests are in scope; labels are bounded and no
  fallback is permitted. Java bulkheads remain signed-synthetic local parity/no-formal-sink checks
  only.
- Runtime remains `DISABLED` or Java-signed synthetic `SHADOW`; real-case shadow, a formal sink,
  `TEMPORAL` allocation, public 100 submissions, canary, and promotion remain forbidden.
- A-C remain active for dependency fixes and bounded cross-review.
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
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
```

`MIG-005=PASS` additionally requires authorized real shadow, promotion evidence, canary observation,
and Java's formal Evidence ledger remaining the only writer. Until then, no report may claim
promotion or permit Hearing supplementation work under Phase 5.

### Wave A Acceptance State Transition Record

- Tested candidate: `edfd54952dcc5a07d87a90fdb094c01b1a7df79b`.
- Evidence commit: `0292321fdb376c3392c86daf6cf98365bfee7c4a`.
- Acceptance tooling commit: `ffc1409709046f8859deafc8917481f99f94659a`.
- Acceptance evidence commit: `c6f9d7dbdd8d9322b219cef866a812a12004f539`.
- Decision: `P5-WAVE-A-INTEGRATED=OPEN`, `Wave B=READY`, `P5-R2=READY`.
- Guard state: candidate wave remains blocked; runtime, traffic, canary, promotion, `MIG-004`, and `MIG-005` remain unchanged.

### P5.R2 Migration Contract Gate Record

- Candidate: `c2c6e51c3f099ecbe867679b75a44a5b6ffb736e`.
- Evidence: `test-reports/temporal-first/phase-5-r2-20260723-c2c6e51c/phase-5-r2-migration-contract/phase5-r2-migration-contract-gate.json`.
- Authorized migration: `java-api-service/src/main/resources/db/migration/V043_5__evidence_finalization_and_operational_recovery.sql`.
- V043_4 SHA-256: `f2872430c63db6b8f561ef982ea4b3329d04bd7ecde744aaa625880c02399cb0`.
- Runtime guard: formal sink, `TEMPORAL` allocation, real-case shadow, canary, promotion, and production traffic remain forbidden.
