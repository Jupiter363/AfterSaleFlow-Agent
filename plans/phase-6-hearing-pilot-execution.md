# Phase 6 Hearing Pilot Execution Plan

## Status

```text
plan_status: READY_FOR_P6_0_ENTRY_GATE
engineering_execution: BLOCKED_UNTIL_EXACT_CANDIDATE_BATCH_0_AND_SEPARATE_ENTRY_EVIDENCE
contract_gate: P6.0 NOT_RUN
phase_5_candidate_C: c43f969f08755fd6eb90c0845809cda1785d11bf
phase_5_evidence_E: 8770d84aac4f653e8953d469246295b6e8c3b8fa
phase_5_acceptance_A: d3ea271188be57adac49592879aaf3417e90c5c0
phase_5_engineering_checkpoint: PASS
phase_5_next_phase_permission: PHASE_6_ENGINEERING_ONLY
promotion_gate: PENDING
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
phase_6_engineering_exception: ADR_0015
next_phase_permission: PHASE_6_ENGINEERING_ONLY_AFTER_P6_0_PASS
team_shape: one primary + eleven dependency-activated delegated roles
implementation_owners: five disjoint owners A-E
p0_review_lanes: three read-only lanes R1-R3
verification_lanes: two primary-controlled lanes V1-V2
lookahead_lane: one read-only lane L
java_hearing_formal_writer: SOLE_FORMAL_WRITER
current_java_hearing_flow_v2: FUTURE_LEGACY_WRITER_MODE
removed_generic_hearing_fallback: NOT_A_LEGACY_MODE
graph_runtime_default: DISABLED
allowed_phase_6_engineering_runtime: DISABLED or Java-signed synthetic SHADOW
V044_before_P6_0_entry: FORBIDDEN
temporal_hearing_allocation: FORBIDDEN
formal_graph_sink: FORBIDDEN
real_or_party_data_shadow: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

The accepted Phase 5 engineering handoff is the immutable triple above. Candidate `C` supplied the
clean source run, evidence commit `E` archived its reports, and acceptance commit `A` records:

```text
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_6_ENGINEERING_ONLY
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
```

That handoff permits only the Phase 6 contract-entry process. It is not P6.0 evidence, Phase 6
implementation authorization, a runtime grant, or promotion. P6.0 remains `NOT_RUN`. Except for
the closed contract, static gate, owner-brief, runner, and fixture artifacts that form the P6.0
candidate itself, every product-code, product-test, migration, runtime schema, configuration, API,
or UI implementation change is blocked until Batch 0 passes on one exact clean candidate SHA and
a separate entry-evidence commit records the acceptance. In particular, no `V044` file may be
created before that entry commit.

Phase 6 engineering is governed by
[ADR 0015](../docs/architecture/adr/0015-phase-6-hearing-engineering-exception.md). The exception
separates engineering entry from promotion while `MIG-004`, `MIG-005`, and `MIG-006` remain
pending. It permits only `DISABLED` or Java-signed synthetic `SHADOW` work after P6.0 passes. It
does not permit real or party data, a formal Graph sink, `TEMPORAL` Hearing allocation, canary,
production traffic, or promotion.

This plan is used with the
[P6.0 Hearing Contract Pack](../docs/runbooks/temporal-first/phase-6-p6.0-contract-pack.md), the
[Phase 6 test batches](./phase-6-hearing-pilot-test-batches.yaml), section 7.7 of the
[master plan](./temporal-langgraph-room-refactor.md), the
[Hearing Flow V2 contract](../docs/contracts/hearing-flow-v2.md), and the platform acceptance and
current-room baseline documents. The contract pack and machine schedule freeze the executable
details. If a proposed candidate conflicts with the existing Hearing V2 contract or current-room
baseline, the existing behavior remains authoritative until a separately reviewed versioned
contract change is accepted.

## Scope

### Goals

- Preserve the exact `hearing_flow.v2` 15-stage order and every `HRG-001..019` behavior.
- Build a future `HearingRoomWorkflow` that, only for a separately promoted and newly admitted
  `TEMPORAL` epoch, owns stage order, both party waits, immutable deadlines, deterministic Signal
  order, retries, cancellation, AgentRun order, and review handoff orchestration.
- Keep Java and Domain PostgreSQL as the sole formal writers of party actions, messages, matrix
  versions, immutable artifacts, the trial dossier, the V1/Jury/V2 chain, review handoff, audit,
  outbox, and fenced query projection.
- Replace the seven one-call Python functions with four explicit, version-pinned Hearing graph
  families: intake, evidence, judge, and jury. Each call is a bounded proposal command; Graph never
  owns a party wait, a stage transition, or a formal business fact.
- After P6.0 entry only, add `V044__hearing_temporal_projection.sql` for epoch, revision, fence,
  persisted writer mode, Temporal identity, idempotent receipt, and fenced projection metadata,
  without weakening V035 append-only constraints.
- Preserve the current HTTP and Vue behavior: two evidence rails and drawer, six progress groups,
  four digital roles, history restoration, message provenance, 20,000-character party statement,
  0-50 Hearing supplement items per party, and explicit navigation to Draft.
- Produce exact-SHA, centrally archived engineering evidence while keeping runtime formalization
  and promotion as independent later decisions.

### Non-Goals And Hard Prohibitions

- P6.0 planning and Batch 0 authorize no product implementation. No V044, product test, fixture,
  migration, runtime, API, or UI change may precede the separate P6.0 entry-evidence commit.
- No accepted engineering checkpoint may be relabeled as promotion. `MIG-004`, `MIG-005`, and
  `MIG-006` remain `PENDING_PROMOTION` until their independent promotion evidence and approvals
  pass.
- No real-case, active-case, party-data, production-like, or unsigned shadow; no formal Graph
  Finalizer reachability; no `TEMPORAL` Hearing epoch allocation; no canary or production traffic.
- No current active V035 flow changes mode, workflow, graph, prompt, model, schema, or policy pin in
  place. There is no automatic mid-flow migration or implicit handback.
- `LEGACY` means the current Java-owned 15-stage `hearing_flow.v2`, which becomes the persisted
  legacy writer mode when V044 is eventually admitted. It never means the removed generic
  three-round protocol, and that removed protocol is not a fallback.
- Temporal and Python never write Domain tables. Workflow History and Graph state never contain
  dossier or matrix payloads, raw private statements, prompt text, token deltas, credentials,
  clients, pools, or model objects.
- Graph output cannot advance a stage, create a formal Artifact, open review, or close a Hearing.
  Java must validate and commit the corresponding idempotent receipt atomically.
- No settlement capability, UI redesign, route change, display-group change, public-contract
  removal, or expansion of the 0-50 per-party Hearing supplement limit.
- No destructive edit to V001-V043_5, V035 rows, or historical Hearing artifacts. V044 is additive
  and remains reserved until the implementation gate opens.

## Authority And Mode Contract

| Concern | Sole authority | Durable truth | Forbidden behavior |
| --- | --- | --- | --- |
| Current formal 15-stage flow | Java `HearingFlowRuntimeService` and its schedulers | V035 stage/action/artifact rows | Treating the removed generic protocol as a fallback or changing an active flow's mode |
| Future promoted process order | Temporal `HearingRoomWorkflow` for an admitted new `TEMPORAL` epoch only | Temporal History plus Java receipt/projection refs | Java scheduler, GET, callback, or Graph independently selecting the next stage |
| Party action, message, Artifact, matrix, dossier, decision chain, handoff, audit, outbox | Java domain services and Domain PostgreSQL | Append-only V035 ledgers and immutable objects | Temporal/Python direct Domain writes or Java trusting an unvalidated proposal |
| Stage query projection | Java fenced Activity/Finalizer | Future V044 CAS projection keyed by epoch/revision/fence | Last-write-wins, stale worker overwrite, or projection becoming a second process authority |
| Model cognition | Version-pinned Python graph and Graph PostgreSQL | Bounded checkpoint/command ledger with refs and typed proposals | Party waits, private transcript sharing, formal truth, stage cursor, or Domain credentials |
| Presentation | Java-authorized API/projection and existing Vue UI | Server projection and persisted event cursor | Browser inference from message text or client-owned deadline progression |

Temporal is a future process authority, not the business database. For a promoted new epoch, the
Workflow decides which command is eligible and calls a Java Activity. Java rechecks tenant, case,
epoch, stage sequence, fence, operation key, request hash, parent hashes, and authorization before
committing a receipt. A lost response replays the same operation key and returns the same receipt.
Only the receipt allows the Workflow to continue.

| Persisted Hearing mode | Process owner | Formal writer | Graph behavior | Phase 6 current status |
| --- | --- | --- | --- | --- |
| Current Java flow, future `LEGACY` | Current Java `hearing_flow.v2` runtime and schedulers | Java | Current seven governed operations | Preserved formal behavior; not a Graph runtime grant |
| `DISABLED` | No new Phase 6 graph/process execution | Current Java flow remains formal | None | Required default |
| `SHADOW` | Current Java `hearing_flow.v2` remains authoritative | Java current path | Java-signed synthetic inputs write isolated comparison/checkpoint data only | Allowed only after P6.0 entry |
| `TEMPORAL` | Version-pinned `HearingRoomWorkflow` | Java Activities/Finalizers | Version-pinned graphs return proposals | Forbidden until independent promotion and new-epoch allocation |

Mode and every version pin are immutable within one epoch. Selector ambiguity, a missing admission
receipt, stale fence, or missing pin fails closed. `SHADOW` cannot call a formal Finalizer or create
a review task. `TEMPORAL` can never silently fall back to Java stage advancement when Temporal,
Python, or a provider is unavailable.

## Frozen 15-Stage Protocol

The following codes, order, and public semantics come from `HearingFlowStage` and
`docs/contracts/hearing-flow-v2.md`. Phase 6 cannot rename, skip, reorder, or merge them:

| Seq | Stage | Orchestration action | Required Java receipt before exit |
| ---: | --- | --- | --- |
| 1 | `COURT_PREPARING` | Load the committed Evidence opening and fixed source refs | admitted epoch and source-matrix binding |
| 2 | `CASE_INTRODUCTION` | Emit deterministic intake-officer template | append-only role-template message |
| 3 | `EVIDENCE_INTRODUCTION` | Emit deterministic evidence-clerk template | append-only role-template message |
| 4 | `INTAKE_QUESTIONS_GENERATING` | Invoke `hearing.intake.v1` questions | `hearing_question_set.v1` and completed AgentRun |
| 5 | `PARTY_ANSWERS_OPEN` | Wait for both party terminals or one shared deadline | one `SUBMITTED` or `AUTO_TIMEOUT` action per party |
| 6 | `INTAKE_SYNTHESIZING` | Invoke `hearing.intake.v1` synthesis | atomic case-matrix version and public synthesis |
| 7 | `EVIDENCE_REQUESTS_GENERATING` | Invoke `hearing.evidence.v1` requests | fact-bound `hearing_evidence_request_set.v1` |
| 8 | `PARTY_EVIDENCE_OPEN` | Wait for both party terminals or one shared deadline | one 0-50 item batch or timeout per party |
| 9 | `EVIDENCE_SYNTHESIZING` | Invoke bounded assessment and deterministic fan-in | all items terminal, one matrix version, one synthesis |
| 10 | `DOSSIER_FREEZING` | Request the single-shot dossier freeze | immutable `trial_dossier.v1` with exact matrix hashes |
| 11 | `JUDGE_V1_GENERATING` | Invoke `hearing.judge.v1` V1 | proposal bound to the frozen dossier |
| 12 | `JURY_REVIEWING` | Invoke `hearing.jury.v1` | report bound to exact V1 ID and hash |
| 13 | `JUDGE_V2_GENERATING` | Invoke `hearing.judge.v1` V2 | one V2 bound to dossier, V1, and Jury parents |
| 14 | `HUMAN_REVIEW_OPEN` | Retry idempotent review handoff | exact displayed V2 and review-task receipt |
| 15 | `CLOSED` | Seal the projection after handoff | append-only closure and fenced `CLOSED` projection |

Only stages 5 and 8 wait for parties. Each uses one absolute deadline equal to the lesser of its
20-minute window and the immutable three-hour Hearing deadline. A first submission resets neither.
Submit and timeout at the same Temporal timestamp follow deterministic History order; Java's unique
terminal-action constraint makes duplicate and late delivery idempotent or rejected without
replacement.

Every adjacent edge above is legal; every other edge is illegal. There are no loops, Jury bypass,
reopening, message-derived transition, or model-selected next stage. `POST /hearing/complete`
remains a read/redirect gate. Before any future `TEMPORAL` allocation, `GET /hearing` must be proven
side-effect free.

## Graph And Java Boundaries

The seven current governed operations are implemented as four graph families without adding a
second 15-stage state machine:

| Graph identity | Operations | Authorized input | Output boundary |
| --- | --- | --- | --- |
| `hearing.intake.v1` | questions and synthesis | Java-authorized case-matrix refs and post-barrier shared statement Artifact | typed question or matrix-delta proposal |
| `hearing.evidence.v1` | requests, per-file assessment, synthesis | formal visible Evidence refs, matrix/hash, request set, authorized supplement manifest | typed request, assessment, or matrix-delta proposal |
| `hearing.judge.v1` | V1 and V2 routed commands | immutable dossier; V2 also pins V1 and Jury parents | typed proposal only |
| `hearing.jury.v1` | Jury review | immutable dossier and exact V1 ID/hash | typed review proposal only |

Each graph uses explicit `StateGraph`, bounded serializable state, State Lenses, fail-closed routers,
governed `prompt | model | parser` Runnables, PostgreSQL checkpoints, command idempotency, lease
fencing, exact version pins, and approved room/tenant/global bulkheads. Evidence assessment fan-out
uses deterministic keyed reduction; the same key with a different canonical payload fails closed.

Before both parties are terminal, their raw statements remain actor-private and cannot enter shared
Graph state. Java may mint a shared statement Artifact only after the barrier and authorization
checks. The adapter validates one operation envelope, resolves one exact graph identity, invokes
one bounded graph, and returns one proposal. `NEEDS_INPUT` returns to Temporal; Graph never holds a
formal long-lived party interrupt.

V035 action/artifact rows, matrix and dossier versions, and the V1/Jury/V2 parent chain stay
immutable. Java Finalizers validate every identity, hash, version pin, command, fence, and business
rule in one transaction with messages, audit, and outbox. Temporal History stores stage/ref/hash/
revision only, not business payloads.

The P6.0 contract pack must freeze these existing compatibility facts rather than normalizing them
away:

- Hearing content hashes are lowercase SHA-256 over compact UTF-8 JSON with sorted keys and the
  named hash field omitted. This repository preimage is not RFC 8785, and Phase 6 cannot silently
  substitute another canonicalization algorithm.
- `hearing_party_statement.v1` is the current free-form statement shape and remains emitted by its
  current endpoint. `hearing_answer_bundle.v1` remains accepted and readable through the existing
  compatibility surface. V037 permits both on the append-only answer ledger; neither may be
  rewritten, conflated, or dropped during migration.
- Questions remain 1-5, requests 0-10, party evidence 0-50 per party, case-fact deltas at most 200,
  each assessment's fact links at most 50, frozen policy rules 1-100, and V2 fact findings and
  evidence assessments 1-200 with policy applications 1-100. Any expansion is a separate contract
  and product decision.
- Message provenance remains exactly `SYSTEM_STAGE_EVENT`, `ROLE_TEMPLATE`, `AGENT_LLM`, or
  `PARTY_ACTION`; timeout is a party action. No Judge LLM AgentRun may occur before the dossier is
  frozen, and the persisted V2 public text must exactly equal the displayed draft text.
- Database constraints are necessary but not sufficient validation. Java Finalizers remain
  mandatory for cardinality, identity/hash parents, actor privacy, version pins, and business
  authorization before any stage or formal ledger mutation.

## P6.0 Entry Gate

### Accepted Upstream Evidence And Exception

The Phase 5 triple `C/E/A` satisfies the upstream engineering checkpoint and grants
`PHASE_6_ENGINEERING_ONLY`. ADR 0015 supplies the bounded exception while the Phase 4 and Phase 5
promotion gates remain pending. Both facts are necessary but not sufficient: implementation still
waits for the Phase 6 two-commit entry gate.

The P6.0 contract candidate must authenticate the exact `C/E/A` triple, the accepted exception,
the protected worktree state, the existing Hearing baseline, the closed schemas/fixtures, owner
briefs, and the exact Batch 0 commands. Any upstream SHA substitution, local report, or copied
result invalidates the candidate.

### Exact-Candidate Two-Commit Gate

1. **Contract-candidate commit `C6`:** freeze only the approved plan, ADR, contract pack, machine
   schedule, static gates, closed schemas and fixtures, exact owner briefs, and entry runner. It
   contains no product implementation, V044, runtime activation, API, or UI change.
2. **Exact-SHA Batch 0:** the primary creates a clean detached worktree at exactly `C6`, verifies no
   unrecorded tracked changes, and runs every source command selected by the machine schedule.
   Every attempt records candidate SHA, argv and command hash, environment hash, start/end/duration,
   exit code, stdout/stderr and JUnit paths/hashes, test counts, and failure classification.
3. **Entry-evidence commit `E6`:** archive the immutable reports separately. Only `E6` may record
   `contract_gate: P6.0 PASS` and authorize A-E to implement. Batch 0 reports from another SHA,
   another working tree state, an unsealed retry, or a later implementation commit are invalid.

Until all three steps finish, this state is mandatory:

```text
contract_gate: P6.0 NOT_RUN
engineering_execution: BLOCKED
V044: FORBIDDEN
implementation_owners: NOT_STARTED
allowed_runtime: DISABLED
formal_hearing_writer: CURRENT_JAVA_HEARING_FLOW_V2
```

## P6.0 Through P6.9

| Step | Owner | Depends on | Deliverable and acceptance boundary |
| --- | --- | --- | --- |
| P6.0 | Primary, V1, V2 | accepted `C/E/A`, ADR 0015 | Freeze `C6`, run exact-SHA Batch 0, classify every attempt, commit separate `E6`; no implementation before PASS |
| P6.1 | B | P6.0 | `HearingRoomWorkflow` with explicit 15-stage transition table, two shared waits/deadlines, deterministic Signals, Agent Activity order, dossier and handoff receipts, replay tests |
| P6.2 | D | P6.0, P6.1/C contract adapters | Split `HearingFlowRuntimeService` into side-effect-free query, party-action ledger, and Java Activity/Finalizer boundaries; future `TEMPORAL` mode has no Java `nextStage/advance/expireIfDue` authority |
| P6.3 | C | P6.0 | Additive V044 epoch/revision/fence/writer-mode/Temporal identity/receipt/projection schema; preserve V035 append-only triggers and bind existing flow to future `LEGACY` mode |
| P6.4 | A | P6.0 | Intake/evidence/judge/jury graphs, registry pins, bounded state, deterministic reducer, signed-synthetic adapters, recovery tests; no formal sink |
| P6.5 | C, then D through frozen adapter | P6.3, P6.4 | Preserve exact question/answer/request/batch/matrix/dossier/V1/Jury/V2 ID/hash parent chain and single Java Finalizer transaction |
| P6.6 | E | P6.1, P6.2, P6.5 | Convert 30-second handoff recovery scheduler to detector-only engineering mode; prove idempotent Temporal retry and `CLOSED` projection after receipt |
| P6.7 | E | P6.1, P6.2, P6.3 | Convert 15-second deadline scheduler to detector-only engineering mode, prove side-effect-free GET and no Java advancement for future `TEMPORAL` mode |
| P6.8 | D | P6.2, P6.3 | Keep Vue/API presentation read-only against the authoritative projection while preserving privacy, two rails/drawer, role provenance, six groups, history, refresh, and Draft navigation |
| P6.9 | Primary, V1, V2 | P6.1-P6.8, P0 reviews closed | Freeze one engineering candidate; prove all transitions, races, recovery, hash/fence/privacy invariants, rollback, and exact-SHA evidence under allowed runtime only |

P6.1-P6.8 are implementation steps only after `P6.0 PASS`. A dependency may be consumed only from
a reviewed commit or a contract frozen at P6.0; agents do not coordinate through uncommitted edits.

## One-Primary Plus Eleven Role Topology

The logical topology is one primary, five implementation owners, three P0 review lanes, two
verification lanes, and one lookahead lane. Roles activate only when their dependency is ready;
all eleven need not remain active. Starts are staggered by 10-20 seconds. Released slots are
backfilled by the next ready logical role instead of collapsing ownership. All code, test,
migration, runtime, security, Temporal, transaction, and P0 review roles use `gpt-5.6-sol` with
`xhigh` reasoning. Pure mechanical verification may use `gpt-5.6-terra high`; read-only lookahead
may use `gpt-5.6-terra medium`.

### Five Disjoint Implementation Owners

The primary issues an exact path allowlist and a complete forbidden-path list before each
delegation. The scopes below never overlap; a shared-file change returns to the primary for a
single-writer integration commit.

| Owner | Logical ownership | Exact path family | Forbidden examples |
| --- | --- | --- | --- |
| A | Hearing graph families and Python contract adapters | `python-agent-service/app/graphs/hearing/**`, `app/agents/hearing_flow.py`, Hearing-only schema/registry adapters explicitly granted by the primary, `python-agent-service/tests/graphs/hearing/**`, `tests/agents/test_hearing_flow_v2.py` | Java, frontend, migrations, generic runtime changes not named in the grant |
| B | Temporal Hearing process and Activities interfaces | `java-api-service/src/main/java/com/example/dispute/workflow/temporal/room/hearing/**`, `workflow/activity/hearing/**`, their focused tests, exact case-child dispatch files granted by the primary | Hearing Domain implementations, Python, frontend, V044, generic worker refactors |
| C | Additive Hearing persistence and immutable receipt/parent contracts | exact V044 migration, `hearing/domain/**`, `hearing/infrastructure/persistence/**`, persistence/contract tests explicitly granted | Temporal decisions, controllers, frontend, schedulers, Graph DB |
| D | Java query/party-action/Finalizer API seam and compatible UI | `HearingFlowRuntimeService.java`, `HearingTrialDossierService.java`, new explicitly named query/Finalizer classes, `hearing/api/**`, corresponding focused tests, `frontend/src/**/hearing*`, `HearingCourtView.vue`, their unit tests | V044, persistence repositories, Temporal Workflow, Python, scheduler/recovery files |
| E | Detector schedulers, parity, reconciliation, metrics, and rollback | `HearingFlowDeadlineScheduler.java`, `HearingReviewHandoffService.java`, `HearingReviewHandoffRecoveryScheduler.java`, new explicitly named Hearing reliability/config paths and focused tests | Core D-owned services, V044, Python graph implementation, frontend product behavior, formal activation |

Owners commit only their allowlisted files and never stage unrelated changes. If an existing file
would cross scopes, the primary chooses one owner, removes it from every other grant, and records
that decision before editing. All five owners retain logical assignments even when dependencies or
capacity make their active turns sequential.

### P0 Review, Verification, And Lookahead

| Lane | Starts when | Read-only responsibility |
| --- | --- | --- |
| R1 | C/D stable diff exists | P0 transaction, Java sole-writer, V035/V044 immutability, fence, CAS, idempotency, hash-parent and rollback review |
| R2 | B or B/D integration diff exists | P0 Temporal determinism, History safety, timer/Signal race, retry, cancellation, worker replay, no active-epoch handback review |
| R3 | A/D/E stable diff exists | P0 security, actor privacy, signed-synthetic boundary, Graph/Java trust, no-formal-sink, API/UI compatibility review |
| V1 | P6.0 candidate or reviewable slice exists | Read-only/static traceability, focused Python/frontend/light shard execution, artifact metadata verification |
| V2 | Integrated candidate and heavy token are available | Maven, Temporal test server, PostgreSQL/Testcontainers, candidate runner, exact-SHA evidence verification |
| L | Integration is active | Read-only Phase 7 contract/lookahead inventory; no Phase 7 edit or expansion of Phase 6 scope |

At least two of the three planned P0 review lanes, which is at least 50%, remain in flight whenever
reviewable implementation exists. Reviewers do not edit implementation files. Only P0 findings
block post-commit integration: post-commit review is P0-only. Non-P0 observations go to the later
backlog and do not expand a commit. A P0 finding returns to its sole implementation owner, receives
a focused fix and test, and is re-reviewed before dependent integration.

## Dependency-Aware Waves

1. **Entry wave:** primary freezes `C6`; V1 validates static/traceability inputs; V2 runs Batch 0.
   No implementation or idle code review starts.
2. **Foundation wave:** after `E6`, A, B, and C start 10-20 seconds apart. D starts on the frozen API
   seam and existing behavior baseline. E starts only when a detector/recovery contract or stable
   foundation diff exists. R1-R3 activate as their corresponding stable diffs appear.
3. **Integration wave:** C exposes persistence/receipt adapters; B consumes receipt interfaces; A
   exposes typed proposals; D assembles Java Finalizers and compatible queries; E adds detector,
   reconciliation, and rollback controls. No concurrent writer touches a shared file.
4. **Experience/recovery wave:** D completes P6.8 after query semantics are stable; E completes P6.6
   and P6.7 after Temporal and Java ownership guards exist. V1 runs focused light shards while V2
   owns the serialized heavy resources.
5. **Candidate wave:** all P0 findings close, primary freezes one exact engineering candidate, and
   P6.9 runs once at the agreed checkpoint. L reports Phase 7 prerequisites without changing code.

429, 503, or usage failure does not weaken review. Preserve partial edits, wait/back off or
reassign the exact scope to an equivalent owner, and keep the same P0 review obligation.

## Verification And Resource Tokens

The machine-readable Phase 6 schedule is authoritative for command selection, deduplication, and
evidence output. Owners run focused static/unit checks for their paths. Cross-service, browser,
Docker, full regression, load, soak, failover, and DR work is grouped at the agreed unified
checkpoint or later promotion gate unless the user explicitly requests it earlier.

The primary controls these non-shareable process tokens:

- `LIGHT-1` and `LIGHT-2`: at most two concurrent light test processes across Python, frontend,
  static, or focused Java unit shards.
- `MAVEN-1` and `MAVEN-2`: the user permits at most two Maven processes. At most one may be the
  PostgreSQL/Testcontainers/Temporal-heavy lane; the other must be a focused non-Testcontainers
  Maven shard and must respect host capacity.
- `POSTGRES-TESTCONTAINERS`: exactly one exclusive PostgreSQL/Testcontainers lane repository-wide.
  It cannot overlap another Testcontainers consumer even when both Maven tokens exist.

No delegated owner starts Maven/Testcontainers, a full suite, browser E2E, Docker, or candidate
evidence generation without the primary assigning the corresponding token. Context/config changes
receive their focused Spring context smoke in the same slice. Two light shards may run while the
heavy lane runs only if they do not compete for its ports, containers, database, or report paths.

Verification is centralized into:

- **P6.0 Batch 0:** existing Hearing baseline, governance, closed-contract fixtures, and static
  traceability at exact `C6`.
- **Foundation checkpoint:** one deduplicated Python/Temporal/Java contract run after A-C stable
  integration.
- **Experience/recovery checkpoint:** focused API/UI/privacy/history/detector/replay work after D-E.
- **P6.9 engineering candidate:** one clean exact-SHA run across required Hearing engineering
  suites. It is not active-case shadow, canary, load, soak, DR, or promotion evidence.

Primary check coverage includes `ROOM-HEARING-001..007`, `TEMP-013..018`, `TEMP-020..029`,
`RUN-001..009`, `GRAPH-009`, `GRAPH-011..020`, `LCEL-001..014`, `JAVA-004..010`,
`E2E-008..009`, and `MIG-006`. `TEMP-019` is not defined and must not be invented. Baseline mapping
must explicitly cover `HRG-001..019`, `DRF-001..004`, `UI-002..005`, `CORE-001..010`, and
`SEC-001..006`, plus the upstream Evidence interface checks required by the machine schedule.

## Evidence And Failure Classification

Every entry or engineering candidate is one immutable SHA. Its evidence records source commit,
protected worktree exceptions, tool and dependency versions, command/argv hash, environment hash,
timestamps, duration, exit code, stdout/stderr hashes, raw and normalized report hashes, test
counts, and selected Check/Baseline IDs. Reports from different SHAs, untracked fixture edits, or
unsealed reruns are never mixed.

| Classification | Criteria | Required action | Gate effect |
| --- | --- | --- | --- |
| `PRODUCT` | Deterministic product, contract, writer, privacy, hash, transition, or recovery assertion fails | Return to sole owner, fix, focused test and P0 re-review; freeze a new candidate and rerun the complete affected gate | Blocks entry/engineering and promotion |
| `FIXTURE` | Product contract is independently proven but fixture, golden data, harness, or expected report is stale/malformed | Record proof, repair without weakening assertions, freeze a new candidate and rerun the complete affected gate | Blocks until new exact candidate passes |
| `INFRA` | Docker, port, disk, network, Temporal test server, Testcontainers, or tool failure occurs without product assertion | Preserve all evidence; one bounded failed-source retry on the same SHA only when the machine schedule permits, otherwise new run | Does not become PASS by assertion; unresolved blocks |
| `EXTERNAL_GATE` | Approval, production-equivalent identity/data, real shadow, canary observation, load/soak/DR, or other external condition is unavailable | Retain engineering evidence and keep promotion plus MIG-004/005/006 pending | Does not block an otherwise complete synthetic engineering checkpoint unless it is an explicit P6.0 input; always blocks promotion |

An entry `PRODUCT` or `FIXTURE` repair always creates a new `C6` and reruns all of Batch 0 from a
fresh detached worktree. An engineering candidate repair creates a new engineering SHA and reruns
the complete candidate checkpoint. A green focused rerun cannot replace the gate. Failure reports
must state the classification and retained artifact hashes before any retry.

## Rollback And No-Handback Rules

### Engineering Rollback

1. Keep the current formal Java `hearing_flow.v2` path intact; Graph returns to `DISABLED` and any
   signed-synthetic selector is removed.
2. Stop new synthetic commands, fence nonterminal Graph leases, and retain completed checkpoints,
   comparison rows, Java receipts, and additive V044 data for audit.
3. Revert version selection/adapters, never historical V035 facts, append-only artifacts, or
   migration history.
4. Re-run mode-default, no-formal-sink, Java-writer, privacy, stage-order, fence, and side-effect-
   free query gates.

### Future Promoted Rollback

1. Set new Hearing `TEMPORAL` cohort allocation to zero. Never mutate an active epoch's mode or
   version pins.
2. Existing `TEMPORAL` Hearings remain on compatible version-pinned workers until terminal.
   Temporal or provider unavailability does not authorize Java to resume stage advancement.
3. A higher recovery epoch is allowed only before the first formal Hearing stage or at a separately
   approved safe boundary with no in-flight side effect. An active epoch is never handed back to
   Java and never falls back to the removed generic protocol.
4. Reconcile Temporal revision against Java receipts before resuming. Dossier, V1, Jury, V2, and
   handoff receipts roll forward idempotently; they are never regenerated or deleted.
5. Any privacy leak, unauthorized data use, illegal/duplicate transition, stale-fence success,
   parent-hash break, Judge run before dossier, or duplicate review handoff stops all new cohorts.

Java may durably accept a party action while orchestration is unavailable and return pending, but
it cannot advance the stage. Provider failure leaves the AgentRun pending/retryable or enters
explicit manual recovery; it never fabricates output. Redis/SSE failure cannot change formal facts,
deadlines, Finalizers, or History.

## Engineering Exit And Promotion Separation

P6.9 engineering can pass only when one exact candidate proves all 14 adjacent transitions and
all illegal edges, both party waits, deadline/Signal races, seven operations across four graph
families, bounded assessment recovery, complete artifact/hash parents, single-shot dossier and V2,
idempotent handoff, stale-writer rejection, replay/version safety, side-effect-free reads, rollback,
and every preserved baseline under `DISABLED` or Java-signed synthetic `SHADOW` only.

The engineering report shape is:

```text
engineering_checkpoint: PASS | FAIL
contract_gate: P6.0 PASS
promotion_gate: PENDING | FAIL
next_phase_permission: PHASE_7_ENGINEERING_ONLY | BLOCKED
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
```

`PHASE_7_ENGINEERING_ONLY` would permit only a separately contracted Phase 7 entry process. It is
not `MIG-006=PASS` and does not authorize formal Hearing traffic.

Promotion is a different gate. It requires `MIG-004=PASS`, `MIG-005=PASS`, separately authorized
real active-case shadow/parity, deadline drills, captured History replay, formal new-epoch
`TEMPORAL` allocation, canary observation, and proof that the current Java `hearing_flow.v2`
advance/schedulers cannot progress those promoted epochs. Only that evidence may record:

```text
promotion_gate: PASS
MIG-006: PASS
```

Until then the Java formal Hearing writer remains authoritative, Phase 6 Graph/process runtime
remains disabled or signed-synthetic only, and no canary or promotion claim is valid.
