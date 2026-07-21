# Phase 4 Intake Pilot Execution Plan

## Status

```text
plan_status: READY_FOR_P4_0_ENTRY_GATE
engineering_execution: BLOCKED_UNTIL_P4_0_ENTRY_EVIDENCE_IS_COMMITTED
promotion_gate: MIG-004 PENDING
next_phase_permission: PHASE_4_ENGINEERING_ONLY
team_shape: primary + 5 logical delegated owners in two waves
java_room_writer_default: LEGACY
graph_runtime_default: DISABLED
allowed_engineering_runtime: DISABLED or signed synthetic SHADOW
formal_intake_writer: FORBIDDEN_UNDER_CURRENT_GATE
```

Phase 3 passed its engineering checkpoint at candidate
`9351a9d65230ce5bfc332bc59ec567ecb8a964c5`; the archived evidence records 828 passing tests and
authorizes Phase 4 engineering only. `MIG-003` and all production promotion gates remain pending.
Consequently, this plan separates an **engineering lane** from a **promotion lane**. Engineering may
build and prove the Intake path behind fail-closed selectors, but it may use only disabled or signed
synthetic shadow execution. No real room epoch may select `TEMPORAL`, no shadow result may reach a
formal Finalizer, and no real party data may enter the synthetic manifest under the current gate.
ADR 0011 records the repository-owner-approved engineering exception and does not relax either
promotion gate.

Implementation starts only after the primary records P4.0 as passed. The gate deliberately uses two
commits so evidence never claims to test the commit that embeds that evidence. First, the primary
freezes a P4.0 contract candidate containing the approved contract pack, this plan, the Phase 4
test-batch policy, static gate checks, and baseline inventory. Batch 0 then runs from that exact SHA
in a clean detached worktree. A second entry-evidence commit archives reports that name the tested
candidate SHA. No owner may implement before the entry-evidence commit or from an older base.
The exact pre-implementation behavior map is
[Phase 4 P4.0 Intake Baseline Inventory](../docs/runbooks/temporal-first/phase-4-p4.0-baseline-inventory.md).
The independent code-map findings and their gate-level resolutions are recorded in
[Phase 4 P4.0 Independent Review Closure](../docs/runbooks/temporal-first/phase-4-p4.0-review-closure.md).

## Scope

### Goals

Phase 4 makes Intake the first production-shaped room migration without changing current Intake
behavior:

- Build an explicit, version-pinned `intake.v2` StateGraph using the Phase 3 PostgreSQL checkpoint,
  command ledger, lease, recovery, registry, governed LCEL, and signed command transport.
- Give each party a Java-issued private cognitive thread bound to tenant surrogate, case, room
  epoch, actor scope, and Agent Session. Initialize it once from a visibility-filtered immutable
  snapshot; stop round-tripping `memory_frame` as cognitive truth on the new path.
- Add a typed Intake child Workflow that owns Intake wait, command order, Activity retry,
  cancellation, and process branch timing. Java Activities continue to validate and commit every
  formal fact.
- Convert the current Intake service path into mode-aware command validation. A `TEMPORAL` epoch,
  when separately promoted, cannot be advanced by the legacy Java service or scheduler.
- Finalize typed Graph proposals atomically in Java while preserving private messages, dossier and
  fact-matrix behavior, participant invitations, summons, case status, audit, outbox, and AgentRun
  semantics.
- Preserve the public URLs, authorized projections, stream behavior, history mode, 740px room
  shell, 1060px breakpoint, accessibility, and all `INT-001..010` behavior.
- Produce shadow parity, privacy, race, crash-recovery, replay, rollback, and candidate evidence
  suitable for a later independently approved `1% -> 5% -> 25% -> 100%` new-epoch rollout.

### Non-Goals

- No Evidence, Hearing, Review, Outcome, or Tool Executor migration. Opening Evidence after
  bilateral Intake continues to use the existing formal Evidence contract and two-hour window.
- No real shadow, canary, production traffic, formal writer activation, or legacy endpoint removal
  while `MIG-003` and the external gates remain pending.
- No in-place conversion of an active legacy Intake epoch and no writer-mode change inside an
  epoch. Mode and all versions are immutable at epoch creation.
- No Python access to Domain PostgreSQL, Java access to Graph checkpoint tables, Temporal direct
  repository access, or Vue access to Temporal/Python as a truth source.
- No transfer of formal admission, cancellation, participant invitation, summons, matrix freeze,
  room opening, evidence deadline, message, audit, or notification authority to Python or Temporal.
- No new Intake business deadline or automatic respondent timeout. Intake `WAITING_PARTY` remains a
  durable external wait until an existing authorized action occurs; changing that product behavior
  requires separate approval.
- No deletion or reinterpretation of historical `room_turn_memory` or `memory_frame` rows. Legacy
  and history readers remain compatible; only new graph-backed threads stop the write-back loop.
- No Prompt redesign, UI redesign, dynamic JSON workflow DSL, unbounded Graph interrupt, direct
  model HTTP in a business node, Agent tool execution, or natural-language byte-for-byte parity
  requirement.
- No destructive V001-V042 migration edits. Phase 4 Domain DDL starts at V043 and is expand-only.

## Authority And Writer Contract

| Concern | Sole authority and writer | Phase 4 responsibility | Forbidden behavior |
| --- | --- | --- | --- |
| Intake process phase, waits, branch order, retry, cancellation | Temporal Intake child Workflow | Enqueue sequenced commands/events; call idempotent Activities; advance only from committed Java receipts | Domain writes, raw payloads in History, direct model calls, wall-clock/random/non-deterministic code |
| Identity, authorization, messages, dossier, matrix, party completion, summons, status, evidence opening, deadline, audit/outbox | Java + Domain PostgreSQL | Accept commands, publish immutable snapshots, validate Graph results, commit formal facts atomically | Trust browser/Graph authority, accept stale epoch/fence, let Temporal bypass invariants |
| Checkpoint, cognitive revision, summary, private thread state | Python `intake.v2` + Graph PostgreSQL | Execute bounded StateGraph/LCEL command and return typed proposal/ref/hash | Domain DB credentials, formal admission/transition, opposing party private text, long party waits |
| Prompt/Message/ChatModel/Parser/Guardrail flow | LangChain Core / LCEL | Execute real `lens | prompt | model | parser | guardrail` with pinned profiles and callbacks | Derive permissions, writer mode, tools, profiles, or process branch from user/model text |
| Query and UI state | Java-authorized projection; Vue presentation | Preserve API/stream compatibility and render explicit version/pending/history state | Infer completion from model text, local timers, private Graph state, or Temporal Query |

### Mode Matrix

| Persisted Intake epoch mode | Formal process writer | Formal domain writer | Cognitive writer | Allowed output sink |
| --- | --- | --- | --- | --- |
| `LEGACY` | Existing Java Intake path | Java | Existing one-shot adapter and Java memory round trip | Existing Java Finalizer |
| `SHADOW` | Existing Java Intake path | Java legacy path | Version-pinned Graph using synthetic input under the current gate | Isolated comparison ledger only |
| `TEMPORAL` | Intake child Workflow | Java Activities/Finalizer | Version-pinned Graph | Formal Finalizer, only after the separate promotion entry gate |

`SHADOW` must not resolve, import, or call the formal Finalizer interface. `TEMPORAL` must be rejected
unless the room type is exactly `INTAKE`, the cohort selector is approved, every recorded dependency
is ready, and the epoch persists the complete immutable selection. Evidence and every other room
remain `LEGACY` until their own migration phase. Replacing the current global new-epoch switch with
a room-specific, stable-cohort selector is therefore a Phase 4 correctness requirement, not rollout
polish.

## P4.0 Frozen Contract Decisions

The detailed contract pack owns wire-level schemas and fixtures. P4.0 must freeze the following
decisions before implementation:

1. **Selection**: `room-epoch-selection.v2` persists writer mode, separate Case Workflow and room
   child Workflow type/build bindings, `intake.v2` graph and checkpoint versions, stream protocol,
   prompt/model/output-schema/policy/guardrail/tool versions, epoch, and fencing token. The v2
   Intake child is selected only through Temporal marker `typed-intake-room-child-v1`; old v1
   History stays on `RoomControlWorkflow`. Flags select only new Intake epochs. Non-Intake
   selection fails closed to `LEGACY` in Phase 4.
2. **Thread identity**: Java issues one opaque UUIDv7 thread per exact
   `(tenant_surrogate, case_id, room_epoch, actor_scope_hash, agent_session_id)` binding. The value
   is never parsed for authority. A party thread cannot be rebound, shared, or reused by a reopened
   epoch.
3. **Initialization**: the first command imports exactly one bounded, content-addressed,
   visibility-filtered `case-snapshot.v1` reference and SHA-256. Java records the source domain
   revision. Later commands reference the checkpoint plus new formal message/event refs and cannot
   replace the initialization snapshot.
4. **Privacy**: initiator and respondent snapshots, messages, summaries, streams, traces, and
   checkpoints are separate. The respondent receives only formally shareable Intake projection and
   their own private inputs, never the initiator's raw private conversation or memory frame.
5. **Graph state**: Intake uses an explicit typed topology and a six-message window, with the Phase
   3 hard ceilings as outer guards: 1 MiB checkpoint state, 256 KiB node patch, 8 KiB per message,
   16 KiB summary, and 64 KiB terminal result. Duplicate stable IDs with different payload hashes
   fail closed. There is no long-running `interrupt`.
6. **Graph output**: `room-graph-result.v1` remains a proposal. Intake output uses the approved
   `intake-turn.v2` / `intake-dossier.v2` bindings and preserves the existing public dossier and
   `case_fact_matrix.v2` semantics. It may propose a room utterance, typed dossier patch,
   completeness/readiness, and normalized recommendation; it cannot confirm admission, complete a
   party, freeze the formal matrix, open Evidence, send a summons, or execute a tool.
7. **LCEL**: every model node is a real State Lens -> ChatPromptTemplate -> governed ChatModel ->
   strict parser -> deterministic guardrail Runnable. Trusted profiles and capability allowlists
   come only from the signed Java/registry binding. Intake has no formal tool capability.
8. **Workflow**: the typed room phase is `OPEN`, `WAITING_PARTY`, `AGENT_RUNNING`,
   `READY_TO_CONFIRM`, or `COMPLETED`, with party scope and terminal reason carried separately.
   Update/Signal handlers only enqueue; the deterministic main loop consumes case command/event
   sequence. `INTAKE_MESSAGE`, `INTAKE_CONFIRM`, and `INTAKE_CANCEL` are processed from durable
   Java refs, never raw browser payloads.
9. **Formal actions**: on an initiator acceptance Java commits completion, invitations and summons
   exactly once while the case remains Intake and the Workflow enters respondent wait. Rejection
   and initiator cancellation become terminal without invitation or downstream opening. Only
   respondent confirmation against a `BILATERAL_FROZEN` matrix can close Intake, open Evidence, and
   start the existing two-hour Evidence window exactly once.
10. **Finalization**: Java revalidates case, room epoch, fence, process/stage revision, actor scope,
    logical run/attempt, command/result/schema/profile hashes, and current domain invariants. One
    ACID transaction writes the formal message/dossier or matrix change, AgentRun terminal state,
    audit/event, and required outbox. Replay returns the same receipt; partial/invalid/stale output
    writes nothing.
11. **Persistence**: V043 is additive and records only Intake thread/snapshot/version bindings and
    idempotency/fence metadata needed by Java. It stores no Graph checkpoint body or new copy of
    private memory. Historical memory remains read-only compatibility data; a `TEMPORAL` thread
    never writes `memory_frame` back to Java.
12. **Projection**: UI and API consume a versioned Java projection with explicit writer mode,
    process revision, room epoch, pending state, and active AgentRun. Existing URLs and response
    semantics remain compatible; projection lag is reported as processing, never guessed.
13. **Version and recovery**: active epochs pin Workflow/Graph/profile versions. Old versions stay
    loadable while referenced. Graph command replay, Activity replay, Finalizer replay, Workflow
    replay, and a late old-epoch result must all converge without a second formal result.
14. **Parity**: compare schema validity, stable facts, source refs/hashes, readiness class,
    normalized patch, guardrail result, and privacy. Natural-language bytes are excluded. Any
    privacy leak, duplicate formal transition, stale-fence success, or unauthorized field is a hard
    zero-tolerance failure.

An incompatible change requires an additive contract version or accepted ADR plus shared Java and
Python fixtures. An owner's local test cannot reinterpret these decisions.

## Entry Gates

### Engineering Entry Gate: P4.0

The primary records P4.0 `PASS` only when all conditions hold:

- The Phase 3 engineering checkpoint and archived evidence resolve to the recorded candidate; the
  three Phase 3 statuses are acknowledged without relabeling `MIG-003` as passed.
- Branch, HEAD, start time, and working-tree exceptions are recorded. The unrelated user deletion
  `docs/api/README.md` remains untouched and unstaged.
- Legacy baseline results produced from the exact frozen P4.0 contract candidate exist for
  `INT-001..010`, `OVR-003`, `CORE-004..010`,
  `SEC-001..006`, `UI-001`, `UI-003`, and `UI-004`. Any pre-existing red item is classified and
  owned before implementation; it is not silently accepted as a new baseline.
- The contract pack freezes the fourteen decisions above, exact schemas/state transitions,
  Activity operation keys, V043 responsibility, safe rollback boundaries, and Check-ID mapping.
- Static gates prove defaults are Java `LEGACY` and Graph `DISABLED`, only signed synthetic
  `SHADOW` is admitted, `TEMPORAL` Intake allocation is unreachable, Python has no Domain DB
  credential, and a shadow registry cannot resolve a formal Finalizer.
- The test-batch policy assigns one accountable owner to every implementation path and primary
  Check ID, caps heavy execution at one,
  and separates focused checks from the single candidate checkpoint.
- Every delegated brief states owned and forbidden paths, input contracts, focused checks,
  deferred batch, review partner, and commit-sized definition of done.

The entry-evidence commit must name the contract candidate SHA, report command/duration/exit code
and report hashes, and preserve the protected unrelated working-tree exception. Any missing item
leaves `engineering_execution: BLOCKED`; no partial gate permits code work.

### Promotion Entry Gate

This gate is outside the currently authorized engineering lane. Real shadow or `TEMPORAL` cohort
allocation remains blocked until:

- `MIG-001`, `MIG-002`, and `MIG-003` are independently `PASS`, including real mTLS identity, KMS
  keys/rotation, private ACL, immutable evidence storage, 1,000-room load, soak, Graph/Domain
  restore/failover evidence, security/operations/change approval, and an approved production
  topology.
- The exact Phase 4 candidate images, schemas, Workflow build, Graph/profile versions, cohort hash
  policy, minimum sample/observation windows, SLO/error budgets, and rollback operator are signed.
- Authorized real-data shadow first completes with zero privacy/authority violations and approved
  parity thresholds. Synthetic evidence cannot substitute for this step.
- Compatible readers and rollback/reconciliation tooling are deployed before the writer selector;
  active legacy epochs remain pinned to legacy workers.

No code or local report generated by this plan may mark that gate `PASS`.

### P4-R1.5 Authority-Binding Gate

P4-R1 worker assembly and the Wave B D/E tasks are additionally blocked by the additive
[P4-R1.5 Intake Authority-Binding Contract](../docs/runbooks/temporal-first/phase-4-p4-r1.5-authority-binding-contract.md)
and its machine-readable
[`phase-4-r15-authority-binding-contract.yaml`](./phase-4-r15-authority-binding-contract.yaml).
This gate closes the ambiguity created by multiple same-party Agent Sessions without changing the
frozen P4.0 Graph wire contracts. Implementation must persist an exact Java-owned epoch-party and
command authority choice, use a read-only `REPEATABLE_READ` bridge, and keep R1.5 execution limited
to inert signed-synthetic `SHADOW`. A missing or ambiguous registration fails closed; no reader may
choose a registration by role, time, or insertion order.

The party authority directly binds the full access-session tenant/case/actor/role/permission tuple
and the full AgentSession tenant/case/room/access/actor/profile tuple. Mutable `ACTIVE` status is a
same-transaction acceptance/start check, not a foreign-key column. AgentSession profile identity is
the fixed `agent-session-profile.v1` RFC 8785 hash registry ID
`asp.v1.<64-lowercase-sha256-hex>`, whose encoded length is 71.

Payload authority is closed to `EXISTING_PRIVATE_EVENT`, `SERVER_MINTED_HUMAN_INPUT`, and
`SERVER_CANONICAL_BRANCH` with the exact command/schema matrix in the manifest. Existing events
must reference the exact V043 private EVENT binding and route. New human input requires immutable
put-before-DB provenance plus idempotent orphan cleanup; Java branch payloads are canonical and
bounded. Command authority is one-to-one with payload authority. `CaseCommandRef.payloadRef`
compares only schema/URI/hash/size; artifact ID and object version require separate provenance.
The payload table CHECK rejects mixed EVENT/put-receipt row shapes. Both server-minted kinds use a
72-character deterministic put key; same-key/same-hash retry returns the same receipt, while a
different hash conflicts. Epoch binding checks both sessions `ACTIVE`, asserts exactly the two
party enum rows, and only then exposes the bootstrap outbox.
The authority row persists the exact put-receipt schema/ID/time/hash snapshot and recomputes its
RFC 8785 hash on reads. Command authority binds `case_command` through request hash as well as
identity, persists sequence/type, and compares the four payload-ref fields transactionally without
placing the long URI in a composite index.

The gate is satisfied only after the migration, JDBC read port, worker context smoke, typed-child
replay tests, and no-formal-sink checks pass and receive independent review. Until P4-E1 separately
admits an authenticated synthetic Activity driver, `executionContext` remains null. Real shadow,
`TEMPORAL` allocation, formal Activities/Finalizer registration, canary, and promotion remain
forbidden.

## Vertical Slices

Each slice has one accountable lead and one rollback point. The primary may integrate narrow
contract adapters, but it does not duplicate owner implementation. The first slice is deliberately
a production-shaped synthetic path through Workflow, Java Activity, signed Graph command,
checkpoint/ledger, shadow sink, projection, and recovery.

| Slice | Lead | Production-shaped behavior | Depends on | Required focused evidence | Rollback point |
| --- | --- | --- | --- | --- | --- |
| `P4-S1` private turn thin path | A | One signed synthetic private Intake message traverses typed Intake Workflow/Activity, Java snapshot/ref, durable `intake.v2`, governed LCEL, cached terminal result, shadow comparison, and versioned projection without formal writes | P4.0 | Thread isolation, one-time import, six-message state, Runnable object flow, four Graph crash boundaries, cached replay | Disable/remove `intake.v2` registry allocation; pin old graph; retain G tables/checkpoints for evidence |
| `P4-S2` initiator decisions | B | Initiator ready/accept, reject, and cancel branches use sequenced Workflow commands and idempotent Java receipts; acceptance waits for respondent and does not open Evidence | S1 | Time-skipping branch table, duplicate/out-of-order command, commit-before-Signal loss, Workflow kill/replay, unauthorized respondent cancel | Stop Intake child provisioning; keep all new epochs `LEGACY/SHADOW`; no domain schema rollback |
| `P4-S3` respondent and formal boundary | C | Respondent runs an independent thread/readiness calculation; typed Finalizer and domain Activities prove bilateral freeze, exact-once Intake completion, Evidence opening, and two-hour-window handoff under test-only formal adapters | S1, S2 | Finalizer atomicity/idempotency, stale epoch/fence, same-time duplicate confirmations, commit/completion loss, no inherited initiator completeness | Freeze new allocation; reconcile operation receipt; before terminal commit create a higher fenced recovery epoch, after commit roll forward to Evidence |
| `P4-S4` compatible experience | D | Java projection/reader and Vue preserve private messages, active stream, dossier, locked respondent view, history mode, routes, breakpoint and accessibility across legacy and future Temporal projections | S1 contract, S2 states, S3 receipts | API compatibility, stream reset/final, refresh/role-switch late response, `INT-*`, `OVR-003`, `CORE-*`, `SEC-*`, `UI-*` focused tests | Select legacy reader, retain additive projection fields, never infer writer state client-side |
| `P4-S5` parity, recovery and release controls | E | Synthetic parity, privacy scans, Workflow/Graph/Java crash-race matrix, room-specific cohort selector, observability, rollback rehearsal, Check-ID evidence and candidate hardening | S1-S4 | Signed synthetic shadow only, no formal sink reachability, replay/version pin, context smoke, migration/selector guards, candidate evidence generation | Set Intake cohort to zero/`LEGACY`, revoke new registry allocation, preserve additive V043/G data and reconcile before any new epoch |

Slice S3 may implement the formal ports and run transaction tests, but current runtime wiring must
keep those ports unreachable. “Test-only formal adapter” means an explicitly injected test fixture,
not a hidden environment flag or production bypass.

## Team, Ownership, And Waves

The logical team is one primary plus five delegated implementation owners. The runtime supports
only four concurrently active agents, so five roles execute in two waves rather than oversubscribing
the machine.

| Owner | Owned concern | Typical paths | Forbidden boundary |
| --- | --- | --- | --- |
| A, Graph cognition | `intake.v2` state/nodes/topology, snapshot import, registry adapter, LCEL Intake pipeline | `python-agent-service/app/graphs/intake/**`, narrow Intake adapters and corresponding tests | Java, frontend, Graph platform rewrites, Domain DB, formal actions |
| B, Temporal process | Intake Workflow contracts/impl, deterministic branch loop, Activity ports, replay/time-skipping tests | `java-api-service/**/workflow/temporal/room/intake/**` and narrow worker registration | Domain fact implementation, Python, frontend, global selector ownership |
| C, Java domain | Snapshot/thread command factory, mode guards, Intake Activities/Finalizer, V043 additive binding | Intake application/domain persistence and exact focused tests/migration | Workflow decisions, Python checkpoint data, frontend, Evidence business changes |
| D, experience | Versioned Intake reader/projection, API compatibility, Vue/store behavior | Intake controller/view/API/store tests and narrow projection readers | Writer selection, Temporal/Python runtime, business transition rules |
| E, reliability/release | Shadow comparison, room-specific selector/cohort, telemetry, crash/race harness, evidence policy | Narrow cutover/config/observability/harness paths assigned by primary | Formal activation, secrets, production deployment, unrelated room migration |
| R, primary | Contract pack, shared fixtures, shared integration adapters, ownership enforcement, test tokens, reviews, candidate/evidence | Shared contract/config/plan/report paths explicitly reserved in task briefs | Parallel reimplementation of delegated modules |

Path patterns above are planning boundaries, not permission to edit every matching file. Each brief
must enumerate exact owned files before work starts. Shared files, typed-child dispatch, worker
registration, and public contracts are primary-controlled. V043 is the explicit exception: owner C
implements that single additive migration from the frozen contract and R reviews/integrates it. An
owner submits an interface request rather than editing across another owner's boundary. No owner
stages unrelated changes.

### Wave 0: Contract Gate

R commits the P4.0 contract candidate and records its exact SHA, runs Batch 0 from a clean detached
worktree at that SHA, then commits the resulting entry-gate evidence separately. Owners A-E receive
the tested contract pack, the entry-evidence commit, their task brief, and the relevant test-batch
excerpt. Implementation remains blocked until both commits exist and the recorded gate is `PASS`.

### Wave A: Three Parallel Foundations

- A builds `P4-S1` Graph cognition and private-thread tests.
- B builds the deterministic `P4-S2` Workflow branch kernel against frozen Activity ports.
- C builds the `P4-S3` Java snapshot/thread/finalization ports and additive persistence.
- R owns shared contract adapters and integrates one commit at a time in dependency order.

After integration, R runs the first deduplicated affected batch. Product/fixture failures return to
their owner; infrastructure failures preserve evidence and rerun only the failed scope.

### Wave B: Experience And Hardening

- D builds `P4-S4` against the integrated projection/receipt contracts.
- E builds `P4-S5` selector, parity, recovery, observability, and evidence controls.
- Completed Wave A owners perform bounded cross-review instead of starting overlapping code.

R integrates, runs the second affected batch, closes findings, freezes one candidate, and runs the
single Phase 4 engineering checkpoint. Only R owns Docker Compose or full service execution; one
PostgreSQL/Testcontainers/Temporal heavy test runs at a time.

## Review Gates

- A reviews C for one-time snapshot semantics, thread/epoch binding, no memory write-back, hash
  validation, and no Domain DB use from Python.
- B reviews C for operation keys, retry taxonomy, commit/completion loss, cancellation and stale
  result fencing.
- C reviews B for Workflow determinism, handler enqueue-only behavior, branch receipts, no direct
  repository/model access, and replay compatibility.
- D reviews Java/Python public projections for privacy/audience, stream finality, history mode, and
  unchanged public behavior.
- E reviews all owners for mode defaults, formal-sink reachability, room-specific selector safety,
  observability cardinality, rollback feasibility, and Check-ID claim quality.
- R adjudicates shared contract changes and verifies every finding is fixed by its owner before the
  dependent slice advances.

## Verification Scope

The machine-readable schedule is `plans/phase-4-intake-pilot-test-batches.yaml`. Slice work uses
focused static/unit/component checks only. Full repository, browser E2E, 1,000-room load, 24-hour
soak, production failover, and DR remain external/unified checkpoints and are not repeated per
slice.

Primary Phase 4 architecture checks are:

```text
ROOM-INTAKE-001..004
GRAPH-007..008
GRAPH-020..022
TEMP-020..023
JAVA-007..011
MIG-004
```

Supporting checks touched by implementation, including contract, AgentRun, stream, security,
observability, release, and recovery checks, remain explicitly mapped in the batch policy and
evidence manifest; they are not implied by a broad suite pass. Baseline evidence must identify
individual `INT-001..010`, `OVR-003`, `CORE-004..010`, `SEC-001..006`, `UI-001`, `UI-003`, and
`UI-004` cases.

Every command record includes candidate SHA, environment manifest, start/end time, exit code,
JUnit/trace/report path, and SHA-256. Failures are classified `PRODUCT`, `FIXTURE`, `INFRA`, or
`EXTERNAL_GATE` before rerun. Configuration, worker registration, selector, or Spring profile
changes require the corresponding application-context smoke test in the same slice.

## Rollback Protocol

### Engineering Rollback

1. Leave Java new-epoch mode at `LEGACY` and Graph at `DISABLED`, or remove only the signed synthetic
   binding under test.
2. Stop new synthetic commands; let completed ledger entries remain cached and cancel/reconcile
   nonterminal leases by fencing token.
3. Revert the application/registry version selection, not V043 or G001-G003 data. Additive tables
   and evidence rows remain for replay and audit.
4. Re-run readiness and static no-formal-writer gates. No rollback may restore an unsigned endpoint,
   process memory checkpointer, static shared secret, or direct legacy model HTTP path.

### Future Canary Rollback

1. Set the new Intake cohort allocation to zero; do not mutate active epoch mode.
2. Freeze new commands for affected epochs, drain or cancel noncommitted AgentRun/Graph work, and
   reconcile command, domain operation, process revision, lease/fence, and stream cursor.
3. At `OPEN` or `READY_TO_CONFIRM` with no committed terminal action, create a higher fenced legacy
   recovery epoch from formal messages/dossier refs.
4. At `WAITING_PARTY` after initiator acceptance, preserve the committed completion, invitations,
   and summons; create a higher legacy recovery epoch that resumes respondent-only Intake and never
   repeats external notifications.
5. After respondent completion/Evidence opening commits, do not reopen Intake. Reuse the idempotent
   receipt and reconcile forward into the existing Evidence path.
6. Any privacy leak, duplicate formal transition, stale-fence success, or unauthorized byte stops
   all cohorts immediately and invokes the security incident runbook.

## Engineering Exit

The Phase 4 engineering checkpoint is `PASS` only when:

- P4-S1 through P4-S5 are integrated, independent cross-review findings are closed, and one frozen
  candidate produces all engineering evidence from that same SHA.
- Signed synthetic commands prove the production-shaped Intake path, private thread isolation,
  one-time snapshot import, graph recovery, LCEL object flow, typed proposal, shadow sink,
  projection, and cached replay.
- Workflow branch/time-skipping/replay tests and Java transaction/idempotency/fencing tests prove
  all current Intake outcomes without a duplicate message, matrix, summons, transition, deadline,
  AgentRun final, audit event, or outbox effect.
- The exact Phase 4 baseline cases pass, including cross-actor privacy, respondent lock,
  initiator accept/reject/cancel, independent respondent readiness, bilateral freeze, Evidence
  handoff, source-text fidelity, stream/final behavior, and no internal field exposure.
- Room-specific selection cannot migrate Evidence or another room, runtime defaults remain
  `LEGACY`/`DISABLED`, signed synthetic `SHADOW` cannot call a formal Finalizer, and no real room
  epoch selects `TEMPORAL`.
- Evidence reports limitations and external gates without promotion inflation.

The expected engineering-only report shape is:

```text
engineering_checkpoint: PASS | FAIL
promotion_gate: PENDING | FAIL
next_phase_permission: PHASE_5_ENGINEERING_ONLY | BLOCKED
```

`PHASE_5_ENGINEERING_ONLY` permits only another explicitly contracted disabled/signed-synthetic
engineering phase. It does not override Phase 5's formal entry condition or authorize Evidence
traffic.

## Promotion Exit

`MIG-004=PASS` requires the separate promotion entry gate plus authorized real shadow and the
approved `1% -> 5% -> 25% -> 100%` new-epoch observation sequence. Each cohort must meet its signed
minimum sample/window and all SLO/error-budget conditions. Cohort expansion stops when command
acceptance p95 exceeds 300 ms for 10 minutes, Temporal dispatch p95 exceeds 1 second for 10 minutes,
outbox oldest age exceeds 60 seconds, or canary error-budget consumption exceeds 2% per hour.
Privacy/authority/fencing/duplicate-transition thresholds are always zero.

Only after the 100% new-epoch observation succeeds, active legacy epochs remain supported, Java no
longer round-trips `memory_frame` for graph-backed Intake threads, rollback is rehearsed, and all
required approvals/evidence are immutable may the report state:

```text
engineering_checkpoint: PASS
promotion_gate: PASS
MIG-004: PASS
```

Until then `MIG-004` remains `PENDING_PROMOTION`, even if every local and synthetic test is green.
