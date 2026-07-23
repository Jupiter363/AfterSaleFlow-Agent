# Phase 6 P6.0 Hearing Contract Pack

## Entry State And Authorization

```text
document_status: P6_0_CONTRACT_CANDIDATE
contract_gate: P6.0 NOT_RUN
accepted_phase_5_checkpoint_sha: d3ea271188be57adac49592879aaf3417e90c5c0
phase_5_candidate_sha: c43f969f08755fd6eb90c0845809cda1785d11bf
phase_5_evidence_sha: 8770d84aac4f653e8953d469246295b6e8c3b8fa
engineering_execution: BLOCKED
phase_5_engineering_checkpoint: PASS
phase_6_engineering_exception: ADR_0015_ACCEPTED_FOR_ENGINEERING_ONLY
next_phase_permission: PHASE_6_ENGINEERING_ONLY_AFTER_P6_0_PASS
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
allowed_runtime: LEGACY, DISABLED, or Java-signed synthetic SHADOW
real_case_shadow: FORBIDDEN
temporal_hearing_epoch: FORBIDDEN
formal_graph_sink: FORBIDDEN
```

This pack is part of the P6.0 contract-candidate assembly. It does not authorize Phase 6 product
implementation and is not proof that P6.0 or Phase 6 engineering passed. The accepted Phase 5
`C/E/A` chain grants `PHASE_6_ENGINEERING_ONLY`, and ADR 0015 permits the exact-SHA P6.0 entry
process. Product code, product tests, V044, runtime activation, real shadow, and Temporal Hearing
allocation remain blocked until a separate P6.0 entry-evidence commit records Batch 0 `PASS`.

Java is the sole formal Hearing business writer. Temporal is the future process authority for time,
ordering, retry, and failure; party waits never live in LangGraph.

The governing sources are:

- `plans/temporal-langgraph-room-refactor.md` section 7.7;
- `docs/contracts/hearing-flow-v2.md`;
- `docs/acceptance/current-room-function-baseline.md`, especially `HRG-001..019`;
- `docs/acceptance/temporal-first-agent-platform-verification-checklist.md`;
- `plans/phase-6-hearing-pilot-execution.md` and its machine test schedule.
- `docs/architecture/adr/0015-phase-6-hearing-engineering-exception.md`;
- `contracts/agent-platform/hearing/v2/compatibility-matrix.yaml`.

P6.0 follows a two-commit gate. The contract-candidate commit contains approved contract/static
material only. Batch 0 runs from that exact clean detached SHA. A separate entry-evidence commit
records immutable reports through the candidate-bound evidence generator and may set P6.0 to
`PASS`. Until both commits exist and every upstream
authorization remains valid, implementation is blocked.

## Open P0 Contract Gaps

| Gap | Current observation at the base SHA | Required closure before implementation |
| --- | --- | --- |
| `P6-G0` upstream handoff | Accepted Phase 5 `C/E/A` and ADR 0015 are recorded | closed by exact pins; any substitution fails the candidate |
| `P6-G1` entry evidence | No P6.0 contract candidate or exact-SHA Batch 0 evidence exists | two-commit P6.0 gate completed |
| `P6-G2` process protocol | Java directly owns the 15-stage cursor; no Hearing child Workflow/Signal/Activity contract exists | closed schemas, deterministic 15-edge Workflow, replay fixtures |
| `P6-G3` Java authority split | `HearingFlowRuntimeService` combines query, action ledger, model finalization, stage advance, expiry, and projection | query/ledger/Finalizer adapters plus hard writer-mode guard |
| `P6-G4` persistence | V035 has no epoch/revision/fence/Temporal identity projection fields | frozen additive `V044__hearing_temporal_projection.sql` contract |
| `P6-G5` Graph topology | `hearing_flow.py` is seven one-call functions and uses an unbounded per-batch thread pool shape | four explicit governed graph families and bounded bulkheads |
| `P6-G6` shared privacy | The future shared-Hearing Artifact capability and post-barrier statement publication are not implemented | Java-minted immutable refs, actor/barrier checks, private-input rejection |
| `P6-G7` replay receipts | Stage/Graph/Finalizer/handoff operation-key and lost-response behavior is not frozen | idempotent Java receipts with request-hash conflict checks |
| `P6-G8` legacy executors | 15-second deadline and 30-second handoff schedulers can execute; query currently invokes legacy expiry | `EXECUTOR -> DETECTOR -> OFF` plan and side-effect-free GET |
| `P6-G9` release evidence | No signed synthetic 15-stage trace, deadline race, worker kill, or captured History replay exists | isolated parity ledger, fault evidence, exact versions and rollback proof |

Every open gap is blocking. `P6-G0` is closed by the accepted upstream chain and ADR 0015; the
remaining rows are either P6.0 entry obligations or post-entry engineering deliverables and cannot
be treated as implemented by this document.

## Current Baseline To Preserve

At the observed SHA:

- `HearingFlowStage` declares exactly 15 ordered values from `COURT_PREPARING` through `CLOSED`.
- `HearingFlowRuntimeService` starts the V035 flow, validates party actions, invokes seven Agent
  operations, finalizes outputs, advances stages, expires party waits, persists messages/artifacts,
  and projects the room.
- `HearingFlowDeadlineScheduler` scans due party stages every 15 seconds. `get` also reaches the
  legacy expiry path, which must be removed before a Temporal epoch is possible.
- `HearingReviewHandoffRecoveryScheduler` retries the idempotent handoff every 30 seconds.
- `app/agents/hearing_flow.py` exposes intake questions/synthesis, evidence requests/synthesis,
  Judge V1, Jury review, and Judge V2 as independent governed calls, but not LangGraph.
- V035 action and Artifact rows are append-only; the dossier and decision chain use stable IDs,
  content hashes, and parent references.
- The Hearing UI presents six progress groups, two evidence rails or a responsive drawer, four
  public digital roles, history mode, and explicit Draft navigation. Settlement remains hidden.

The migration changes orchestration ownership, not these observable business rules. In particular:

1. the global Hearing window remains three hours;
2. both party wait stages retain a shared absolute deadline capped at 20 minutes and at the global
   deadline;
3. each party has one terminal action, `SUBMITTED` or `AUTO_TIMEOUT`, per wait stage;
4. party statements remain private until both parties are terminal;
5. each supplement batch contains zero to 50 unique Evidence IDs and a note of at most 1000
   characters;
6. Judge cannot run before the immutable dossier;
7. the decision chain is always Judge V1, Jury review, Judge V2;
8. V2 is single-shot and asynchronous handoff copies the exact displayed V2 without reinvocation;
9. `/hearing/complete` is a read/redirect gate; and
10. only a persisted closure event opens the result page.

## Exact Stage Machine

The only legal stage sequence is:

```text
COURT_PREPARING
CASE_INTRODUCTION
EVIDENCE_INTRODUCTION
INTAKE_QUESTIONS_GENERATING
PARTY_ANSWERS_OPEN
INTAKE_SYNTHESIZING
EVIDENCE_REQUESTS_GENERATING
PARTY_EVIDENCE_OPEN
EVIDENCE_SYNTHESIZING
DOSSIER_FREEZING
JUDGE_V1_GENERATING
JURY_REVIEWING
JUDGE_V2_GENERATING
HUMAN_REVIEW_OPEN
CLOSED
```

There are exactly 14 adjacent transitions. No stage can skip, repeat, move backward, reopen, or be
derived from message text. A retry replays the same stage operation and receipt; it does not create
a second transition. `FAILED` is a flow/stage execution status, not a sixteenth business stage.

### Party Wait Semantics

`PARTY_ANSWERS_OPEN` and `PARTY_EVIDENCE_OPEN` are the only wait stages. On entry, Temporal records
one absolute deadline:

```text
stage_deadline_at = min(stage_opened_at + 20 minutes, hearing_deadline_at)
```

It never recomputes that deadline from wall-clock time after replay and never resets it after the
first submission. Java accepts and commits an authorized party terminal action before its outbox
Signal is delivered. The Workflow consumes only a committed receipt containing the event sequence.
Duplicates return the prior result. Same-timestamp timeout and Signal ordering follows Temporal
History command order; Java's unique terminal constraint makes late arrivals unable to replace the
winner.

### Stage Completion Rule

Every non-wait stage has one deterministic operation key and exits only after a matching Java
receipt commits. For an Agent stage, the chain is:

1. Temporal schedules the version-pinned AgentRun Activity;
2. Java creates or reuses the logical run/attempt and signed Graph command;
3. Python returns one typed proposal or explicit failure/needs-input result;
4. Java Finalizer validates the proposal and commits formal facts plus the stage receipt;
5. Temporal observes the receipt and moves to the next adjacent stage.

Model completion, an SSE terminal event, or a Python HTTP response alone cannot complete a stage.

## Reserved Runtime Contract Set

The P6.0 compatibility matrix closes the current 15-stage, seven-operation, authority, identity,
hash, limit, privacy, and gate surface without duplicating the authoritative Hearing payload models.
After P6.0 entry, P6.1-P6.3 may introduce the following versioned runtime envelopes and their
positive/negative fixtures. Their names and purposes are reserved here; no envelope is admitted to
a runtime selector merely by appearing in this pack:

| Contract | Purpose |
| --- | --- |
| `hearing-workflow-input.v1` | Evidence-opening receipt, tenant/case/room epoch, mode/fence, three-hour deadline, and all version pins |
| `hearing-stage-command.v1` | one expected stage/sequence operation with immutable refs and request hash |
| `hearing-party-terminal-receipt.v1` | committed participant action, request ID/hash, event sequence, terminal status, and stage deadline |
| `hearing-graph-command.v1` | authorized immutable Artifact refs and exact graph/model/schema/policy versions |
| `hearing-graph-result.v1` | bounded typed proposal, source hashes, checkpoint identity, AgentRun and output hash |
| `hearing-domain-receipt.v1` | idempotent Java fact/finalizer result used by Workflow replay |
| `hearing-stage-projection.v1` | fenced Java query projection containing stage/ref/hash/revision only |
| `hearing-shadow-observation.v1` | signed synthetic expected/actual transition and normalized hash comparison |

Closed schemas reject unknown fields at authority boundaries. Hearing business self-hashes remain
the compatibility-matrix profile: compact UTF-8 JSON, lexicographically sorted keys, no whitespace,
and exactly the named top-level hash field omitted. This profile is not RFC 8785 and cannot be
silently replaced by it. Transport-envelope request hashes introduced later must name their own
canonicalization independently. Every envelope freezes maximum strings/collections/state bytes,
enum values, and positive/negative fixtures. No contract may contain a Domain credential, local
path, arbitrary URL, raw object bytes, model client, Java entity, or a free-form next-stage value.

## Temporal Workflow Protocol

### Workflow Identity And Bounded State

The P6.1 workflow contract must freeze the exact Workflow ID formula before any selector can admit
a Hearing epoch. It must derive from persisted
tenant surrogate, case ID, room type `HEARING`, and room epoch, not operator input. Workflow state is
bounded to:

```text
schema/workflow version
tenant surrogate, case ID, room epoch, writer mode, fencing token
current stage and monotonic stage sequence
hearing deadline and optional current shared stage deadline
two participant IDs/roles and their terminal receipt refs
immutable input/output Artifact IDs and hashes
current logical AgentRun/attempt/command refs
graph, prompt, model, output schema, policy, guardrail, tool versions
last Java domain receipt ID/hash and process revision
handoff receipt ref and terminal/failure classification
```

No matrix, dossier, evidence bytes, party text, prompt, model output body, token delta, or SSE event
is stored in History. Java/object storage remains the payload source; History holds references and
hashes.

### Commands And Queries

- Start/Update handlers enqueue validated commands; they do not block on Java/Python network I/O.
- Party Signals carry only a signed/authorized committed Java receipt reference and event sequence.
- Duplicate command IDs with the same request hash return the prior receipt; a different hash is a
  conflict.
- Workflow queries read deterministic in-memory state only. The Java HTTP query reads the fenced
  Domain projection and never calls `expireIfDue`, schedules a model, or advances a stage.
- Temporal time, retry policy, cancellation scopes, and version markers are used instead of system
  clocks, random IDs, threads, locks, filesystem, database, or HTTP inside Workflow code.
- Active Histories remain pinned to compatible Worker/graph/model contracts. Code changes use
  Temporal versioning and captured replay tests; they never reinterpret a prior event.

### Activity Policy

Activities use the platform retry taxonomy and a deadline-derived budget. Business conflict,
authorization, stale fence, schema/hash mismatch, or illegal transition is non-retryable. Network,
worker loss, or completion-response loss is retryable within the remaining command deadline. Each
side-effect Activity has an idempotent Java operation key and returns a durable receipt. Heartbeats
carry bounded progress refs, never payloads.

## Java Business-Truth Contract

Java remains the only component allowed to:

- authorize a party, reviewer, Evidence ref, policy rule, or shared publication;
- append a Hearing action/message/Artifact/audit/outbox row;
- merge and version case/fact-evidence matrices;
- freeze `trial_dossier.v1`;
- finalize Judge V1, Jury, or Judge V2 with exact parent ID/hash validation;
- create the review packet/task and close the Hearing projection.

Each operation validates tenant, case, room epoch, mode, expected stage/sequence, process revision,
fence, actor scope, command/request hash, AgentRun terminal state, schema versions, source refs, and
parent hashes. Fact write, AgentRun finalization, public message, audit, outbox, and receipt commit in
one transaction where applicable. A repeated identical request returns the original object and
receipt; it never adds another action, message, Artifact, matrix version, dossier, V2, or handoff.

The split of `HearingFlowRuntimeService` must produce explicit query, party-action ledger,
Finalizer/domain operation, and projection adapters. For `TEMPORAL` mode, any call path equivalent
to `advance`, `nextStage`, `expireIfDue`, scheduler execution, GET side effect, or model-callback
stage selection fails closed at the database-backed writer guard. Hiding an endpoint in Vue is not
a guard.

## Operation And Idempotency Keys

The exact encoding and length constraints must be frozen by the P6.0 contract candidate. The
semantic formulas are:

```text
hearing.stage:{tenant}:{case}:{epoch}:{stage_sequence}:{stage_code}
hearing.party:{tenant}:{case}:{epoch}:{stage_sequence}:{participant_id}:{request_id}
hearing.agent:{tenant}:{case}:{epoch}:{stage_sequence}:{operation}:{command_hash}
hearing.finalize:{tenant}:{case}:{epoch}:{stage_sequence}:{artifact_type}:{request_hash}
hearing.handoff:{tenant}:{case}:{epoch}:{judge_v2_id}:{judge_v2_hash}
hearing.close:{tenant}:{case}:{epoch}:{handoff_receipt_hash}
```

Keys bind the request hash and cannot be reused with another payload. Commit followed by lost
response returns the prior receipt. A later Worker, Graph lease, AgentRun attempt, Finalizer, or
scheduler with a stale epoch/revision/fence cannot commit.

## Hearing Graph And LCEL Contract

Phase 6 uses four graph identities, not one long-running cognitive Workflow:

| Graph | Routed operations | Required ordering invariant |
| --- | --- | --- |
| `hearing.intake.v1` | questions and synthesis | synthesis requires both party terminal receipts and the exact question set |
| `hearing.evidence.v1` | requests, per-file assessment, synthesis | all authorized batch items terminal before one deterministic merge proposal |
| `hearing.judge.v1` | V1 and V2 | V1 requires dossier; V2 requires dossier plus exact V1 and Jury parents |
| `hearing.jury.v1` | Jury review | requires exact dossier and V1 ID/hash; cannot approve or execute |

Every operation uses an explicit `StateGraph` topology with a fail-closed router. State Lenses select
only required fields. Nodes use real LCEL object flow (`prompt | governed model | parser`) and a
typed output schema. Provider adapters, budgets, retry classification, guardrails, and tool policy
are injected outside serializable state. Graph registry identity and all version pins are included
in the Java-signed command and rechecked at every result boundary.

Evidence file assessment uses deterministic `Send` tasks and a stable-key reducer. Logical work may
be parallel, but physical room/tenant/global concurrency and queues are bounded by the approved
bulkhead contract. Identical duplicate results are idempotent; the same key with another canonical
payload hash is a conflict. Completion order cannot alter matrix/proposal hashes.

Graph checkpoint/command/lease state is in Graph PostgreSQL. Python has no Domain DB credentials and
no local sticky-state dependency. Process death before/after model invocation and before/after
checkpoint commit resumes from the same command/checkpoint semantics without a second formal
effect.

## Shared Hearing Privacy And Artifact Capability

`GRAPH-009` is an entry invariant:

- shared Hearing Graph commands contain only Java-authorized immutable formal Artifact references;
- raw actor-private chat, private Evidence-room transcript, unadmitted Evidence, raw A2A content,
  chain-of-thought, and reviewer-private notes are forbidden;
- the first party statement remains private while the other party is nonterminal;
- after both terminals, Java may publish one immutable shared statement Artifact whose audience,
  source message IDs, epoch/fence, hash, and publication receipt are explicit;
- Evidence refs require formal admission, owner/visibility/audience checks, immutable object version,
  MIME/size/hash, and actual-load capability receipts before a model can claim inspection;
- public messages expose only the allowlisted `SYSTEM_STAGE_EVENT`, `ROLE_TEMPLATE`, `AGENT_LLM`,
  and `PARTY_ACTION` provenance. Internal audit assistants and raw model/A2A traces never appear.

Shared graph thread identity uses a persisted system/shared-session marker. It cannot be changed to
a party scope by parsing an identifier. Cross-tenant, case, epoch, actor, session, Artifact, and
parent-hash substitutions fail closed.

## Decision Chain And Handoff

The Java Finalizer enforces:

```text
trial_dossier.v1
  -> judge_proposal.v1 (dossier ID/hash)
  -> jury_review_report.v1 (dossier and proposal ID/hash)
  -> adjudication_draft.v2 (dossier, proposal, and review ID/hash)
  -> review handoff (exact displayed V2 ID/hash)
```

Judge AgentRuns before dossier freeze are forbidden. Jury cannot be bypassed. V2 is created once;
late or conflicting output is rejected. Handoff is idempotent and never calls Judge again. Temporal
retries the same handoff operation key until the Java receipt commits, then requests the fenced
`CLOSED` projection. Result-page availability depends on the persisted closure event, not model
text or an SSE event.

## V044 Persistence Reservation

The exact reserved filename is `V044__hearing_temporal_projection.sql`. It is absent from the P6.0
candidate by rule. After P6.0 entry, the P6.3 migration contract must implement an additive design
that provides:

- immutable Hearing epoch and writer mode selection;
- process revision and fencing token;
- Temporal namespace/workflow/run/build/deployment identity;
- current-stage projection CAS and last acknowledged receipt/History refs;
- idempotent operation receipts and reconciliation/active-reference indexes;
- constraints that reject old Java advance/schedulers for a `TEMPORAL` epoch.

All existing V035 flows are backfilled as `LEGACY`. Old readers keep working. V035 action and
Artifact append-only triggers remain intact. Historical stage/action/Artifact/dossier rows are not
rewritten. Temporal and Graph persistence never gain foreign keys or credentials into Domain
tables.

## Mode, Shadow, And Promotion Contract

`DISABLED` is the new-runtime default. `LEGACY` preserves the current Java-owned Hearing V2.
`SHADOW` runs only Java-signed synthetic fixtures under ADR 0015 after P6.0 entry: Java remains the formal writer,
Temporal computes an expected 15-stage trace, Graph proposals/finalizer validations write isolated
comparison data, and no draft/review/handoff/closure side effect is permitted.

Real-case shadow requires separate data authority and cannot be inferred from signed synthetic
admission. `TEMPORAL` requires a separately promoted new Hearing epoch after `MIG-006`. An active
V035 flow is never switched in place. Allocation ambiguity, incomplete version selection, absent
key/trust material, or missing Java authority bean fails application readiness.

Shadow compares normalized stage/ref/hash/deadline/guardrail outputs, not natural-language equality.
It records stage mismatch, timer drift, illegal transition, hash conflict, Agent retry/reset,
handoff age, old-writer attempt, and History event count. Shadow data cannot be replayed into formal
tables.

## Failure And Recovery Matrix

| Failure window | Required result |
| --- | --- |
| Java commit before Activity response loss | retry returns the same receipt; no second fact or stage |
| Signal duplicate/reorder/gap | event-sequence dedupe/gap recovery; no deadline reset |
| Signal and deadline same timestamp | deterministic History winner plus one Java terminal action |
| Python death around model/checkpoint boundaries | same command resumes or returns stored result; no formal side effect |
| Agent provider outage | bounded retry/manual recovery; no fabricated proposal or skipped stage |
| old Worker or lease returns late | epoch/revision/fence rejects result |
| matrix/dossier commit before completion loss | receipt lookup resumes at next adjacent stage |
| V1/Jury/V2 parent substitution | Java Finalizer rejects non-retryably |
| handoff response loss | same review task/packet and receipt returned; Judge is not reinvoked |
| Java/Domain DB unavailable | formal operation fails closed; Temporal retries within policy |
| Temporal unavailable | Java may accept a party action durably as pending; Java cannot advance |
| Redis/SSE unavailable | UI catches up from DB cursor; facts, Timer, and Finalizer are unaffected |

Captured History replay covers every code/version change before a Worker image is accepted. Kill
injection is required before and after each Agent stage, Java commit, Graph checkpoint, dossier
freeze, and handoff. Recovery must preserve the 15-stage trace and all object hashes.

## Scheduler And Query Cutover

Both legacy schedulers use three explicit modes:

| Mode | Deadline scheduler | Handoff scheduler |
| --- | --- | --- |
| `EXECUTOR` | may expire `LEGACY` waits only | may retry `LEGACY` handoff only |
| `DETECTOR` | compares legacy due result with Temporal projection and emits metrics only | detects pending/mismatch and emits metrics only |
| `OFF` | no scan/advance | no handoff execution |

They cannot execute against `TEMPORAL` epochs in any mode. Promotion requires DETECTOR parity and
then OFF for new epochs. HTTP GET and `/hearing/complete` remain side-effect free in every mode.

## P6.0 Verification Requirements

The exact machine schedule is `plans/phase-6-hearing-pilot-test-batches.yaml`. `P6-G0` is closed;
Batch 0 becomes runnable only after the complete contract candidate is committed and passes P0
review. It then runs from that exact clean detached SHA and proves the unchanged legacy baseline
plus static plan/contract consistency. The separate evidence commit must bind every report to that
SHA before any implementation owner starts.

Later engineering verification must prove:

| Requirement | Minimum evidence |
| --- | --- |
| `ROOM-HEARING-001..002` | all adjacent/illegal transitions, two wait stages, time-skipping and same-tick races |
| `ROOM-HEARING-003..006` | complete object/hash chain, independent Judge/Jury nodes, no review before V2 |
| `ROOM-HEARING-007` | DB-backed rejection of legacy advance/schedulers for `TEMPORAL` mode |
| `GRAPH-009`, `GRAPH-011..020` | shared privacy, bounded state, explicit graphs, routers, Send/reducer, no long party interrupt |
| `JAVA-004..010` | append-only, fences, Finalizer idempotency/transaction and invalid-result rejection |
| `HRG-001..019` | explicit baseline mapping with no UI or behavior regression |
| `MIG-006` | remains `PENDING_PROMOTION` for synthetic engineering evidence |

The primary centralizes PostgreSQL, Temporal test server, Maven/Spring, frontend build/browser,
Docker, full regression, load, soak, and DR. Delegated owners run focused T0 checks only. Results
are classified before rerun and never mixed across candidate SHAs.

## Frozen P6.0 Decisions

1. The fixed 15-stage sequence and Hearing V2 business contracts do not change.
2. Temporal owns time, ordering, retry, cancellation, and failure recovery only for a future
   admitted `TEMPORAL` epoch; Java owns all formal business facts and the fenced projection.
3. Party waits never live in LangGraph. Python exposes four explicit short-lived graph families for
   the seven existing operations.
4. Shared Hearing Graph receives only Java-authorized formal Artifact refs; private raw statements
   stay private until a committed two-party barrier publication.
5. V035 ledgers stay append-only. V044 is additive and reserved; no V044 file is created before the
   accepted P6.0 entry-evidence commit.
6. `LEGACY`, signed synthetic `SHADOW`, and future `TEMPORAL` are immutable per epoch. No active
   flow is migrated or downgraded in place.
7. Existing Hearing UI/API behavior is preserved; GET and complete are side-effect free.
8. Dossier, V1, Jury, V2, handoff, and closure use idempotent Java receipts and exact parent hashes.
9. The logical topology is one primary, five disjoint implementation owners, three P0 review lanes,
   two verification lanes, and one lookahead lane. Roles activate by dependency; the heavy
   Maven/Testcontainers lane and candidate evidence remain primary-controlled and serialized.
10. P6.0 remains `NOT_RUN` and implementation remains blocked until exact-SHA Batch 0 passes and a
    separate entry-evidence commit records acceptance. Synthetic evidence cannot set
    `MIG-006=PASS`.
