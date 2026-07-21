# Phase 4 P4.0 Intake Pilot Contract Pack

## Entry State And Authorization

```text
phase_3_candidate: 9351a9d65230ce5bfc332bc59ec567ecb8a964c5
phase_3_engineering_checkpoint: PASS
phase_3_promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
next_phase_permission: PHASE_4_ENGINEERING_ONLY
java_intake_writer_default: LEGACY
graph_runtime_default: DISABLED
allowed_execution: DISABLED_OR_SIGNED_SYNTHETIC_SHADOW
real_case_shadow: FORBIDDEN
formal_temporal_intake_writer: FORBIDDEN
formal_graph_finalizer_sink: FORBIDDEN
MIG-004: PENDING_PROMOTION
```

This pack authorizes contract-driven engineering only. It does not satisfy `MIG-003`, promote
`MIG-004`, allocate a real `TEMPORAL` Intake epoch, send real party data to a shadow graph, or let a
Graph result reach a formal Finalizer. Java remains the only formal business writer and the legacy
Intake path remains the only formal Intake process writer under the current gate.

P4.0 uses two commits. The first freezes this pack, schemas, fixtures, plans, static gates, and the
baseline inventory. Batch 0 runs from that exact contract-candidate SHA in a clean detached
worktree. A later entry-evidence commit names the tested SHA and archives reports and hashes.
Implementation is blocked until the entry-evidence commit records `P4.0=PASS`. Evidence is never
claimed for the commit that embeds that evidence.

Normative precedence is: accepted ADRs, Agent Platform v1 JSON Schemas, this pack, the production
verification checklist, the Phase 4 execution plan, then the machine-readable test matrix. A newly
found contradiction returns to the primary owner; a delegated owner may not choose a local wire or
authority interpretation.

The Phase 4 engineering exception is recorded by
[ADR 0011](../../architecture/adr/0011-phase-4-intake-engineering-exception.md).

## Authority And Modes

| State or effect | Sole authority | Store | Current engineering restriction |
| --- | --- | --- | --- |
| Intake wait, command order, branch, cancellation, retry | Temporal Intake Workflow in the target mode | Temporal History | Implemented and tested only with synthetic references; not selected for a real epoch |
| Formal message, dossier, matrix, party completion, invitation, summons, room transition, deadline, audit, outbox | Java domain transaction | Domain PostgreSQL | Legacy writer remains formal; new formal ports are unreachable in runtime wiring |
| Cognitive state, summary, node result, cognitive revision | `intake.v2` LangGraph | Graph PostgreSQL | `DISABLED` or signed synthetic `SHADOW` only |
| Prompt, Message, ChatModel, Parser, guardrail flow | Governed LangChain/LCEL runtime | Trace and immutable manifest refs | Cannot derive identity, authority, mode, version, or capabilities |
| Query and presentation | Java-authorized projection, then Vue | Domain projection/browser memory | Never infers a transition from Graph text or client timers |

The persisted epoch mode is immutable:

| Mode | Formal process writer | Formal domain writer | Cognitive writer | Legal sink |
| --- | --- | --- | --- | --- |
| `LEGACY` | Existing Java Intake path | Java | Existing compatibility adapter | Existing Java transaction |
| `SHADOW` | Existing Java Intake path | Java legacy path | Pinned Graph | Isolated bounded comparison only |
| `TEMPORAL` | Intake child Workflow | Java Activities/Finalizer | Pinned Graph | Formal Finalizer after a separate promotion gate |

The Phase 4 engineering build exposes no runtime path from `SHADOW` to the formal Finalizer. A
constructor, bean, registry, or environment flag that can connect them fails the static gate.

### Typed Child Selection

The existing `room-epoch-selection.v1.workflow_type` identifies the Case Workflow and cannot be
reinterpreted as a room child type. Phase 4 adds `room-epoch-selection.v2` with distinct immutable
fields:

```text
case_workflow_type
case_workflow_build_id
room_workflow_type
room_workflow_build_id
```

Legacy/v1 selections continue to start `RoomControlWorkflow`. A v2 Intake selection pins
`room_workflow_type=IntakeRoomWorkflow`. `CaseProcessWorkflowImpl` dispatches the typed child only
behind the Temporal version marker `typed-intake-room-child-v1`; old History follows the recorded
default branch and remains replayable. An unknown selection version, room type, child type, or build
binding fails closed. Workflow type is never chosen from a mutable flag after epoch creation.

## Contract Set

The platform envelopes remain `room-graph-command.v1` and `room-graph-result.v1`. Phase 4 adds the
following Intake-specific schemas under `contracts/agent-platform/intake/v2/`:

| Schema | Purpose | Maximum encoded size |
| --- | --- | --- |
| `room-epoch-selection.v2` | Pin separate Case/room Workflow identities and every Graph/profile version for a new epoch | 16 KiB |
| `graph-private-thread-registration.v1` | Idempotently provision the complete private thread binding before its first command | 16 KiB |
| `intake-domain-snapshot.v2` | One-time, visibility-filtered initialization facts and formal references | 256 KiB |
| `intake-turn-event.v2` | One accepted party message/event reference for a cognitive turn | 32 KiB |
| `intake-turn-proposal.v2` | Graph-authored room utterance, typed dossier patch, readiness, and source bindings | 64 KiB |
| `intake-finalization-receipt.v1` | Java's idempotent formal commit receipt | 16 KiB |

Every schema is Draft 2020-12, uses `additionalProperties: false` at every authority-bearing
object, has a fixed `schema_version`, and ships positive and negative fixtures. Canonical hashes use
RFC 8785 bytes and SHA-256. A reference hash, envelope hash, and content hash are independently
verified at the receiving boundary.

No Phase 4 contract adds admission, cancellation, party completion, matrix freeze, Evidence open,
deadline, summons, or tool execution authority to `RoomGraphResult`. It remains a proposal.

## Private Thread Registration

`RoomGraphCommand.v1` intentionally does not contain `agent_session_id`. Phase 4 must not smuggle
that value through a capability string, browser field, prompt, snapshot body, or reversible
`thread_id`. Java provisions the binding first with `graph-private-thread-registration.v1`.

Required registration fields are:

```text
schema_version
registration_id
tenant_surrogate
case_id
room_type = INTAKE
room_epoch
thread_id
actor_scope
actor_scope_hash
agent_session_id
graph_key = intake.v2
graph_version
checkpoint_schema_version
state_schema_version
prompt_version
model_profile_id
output_schema_version
policy_version
guardrail_version
tool_policy_version
writer_mode
issued_at
registration_hash
```

Java generates an opaque UUIDv7 wire ID `grt.v1.<32-lowercase-hex-digits>` and persists the exact
binding and hash in Domain PostgreSQL. A transactional outbox delivers the registration through an
mTLS-authenticated, ES256-signed internal request with capability `graph.thread.register`. Python
verifies the service identity, signature, nonce, body hash, exact actor scope, room type, registry
binding, and current admitted mode before `ensure_registered` writes Graph PostgreSQL.

The Graph-side immutable uniqueness tuple is:

```text
(tenant_surrogate, case_id, room_epoch, actor_scope_hash, agent_session_id,
 graph_key, graph_version, checkpoint_schema_version)
```

The same `thread_id` plus the same canonical hash returns the existing registration. The same ID
with another hash, or the same private tuple with another thread ID, fails closed and emits a
bounded security audit. A reopened room epoch or a different party always receives another thread.
Python never queries Domain PostgreSQL and Java never writes Graph PostgreSQL directly.

Under the current gate, runtime registrations are restricted to signed synthetic manifest entries.
The production-shaped registration port and persistence may be tested, but real-case provisioning
is unreachable until the promotion gate is amended.

## Snapshot And Event Boundary

The first graph command imports exactly one `intake-domain-snapshot.v2` reference. The snapshot is
created from formal Java data, visibility-filtered for one actor/session, stored immutably, and
bound to:

```text
tenant_surrogate, case_id, room_epoch, thread_id, actor_scope_hash, agent_session_id,
domain_revision, room_revision, projection_revision, visibility, created_at
```

Its content contains only the party-visible initial facts, shareable formal Intake projection,
the party's own accepted messages/source references, current dossier projection, and versioned
source bindings. It excludes the other party's private conversation, either party's legacy
`memory_frame`, internal handoff notes, hidden reasoning, credentials, raw audit records, reviewer
notes, and browser-supplied trusted configuration.

The loader verifies URI scheme/allowlist, immutable object version, size, schema, hash, tenant,
case, room, epoch, actor, session, visibility, and registration binding before returning bytes.
Loading occurs after registration authorization and before graph mutation. A hash or binding
failure writes no command checkpoint.

Initialization is monotonic. A thread with `initial_snapshot_hash` may replay the identical first
command, but cannot import another initialization snapshot. Later commands use
`intake-turn-event.v2`, whose required fields bind event/message ID, Java sequence, source hash,
actor scope, room epoch, accepted domain revision, occurred time, and bounded party text. Sequence
duplicates with the same hash replay; the same stable ID with a different hash fails closed.

## Intake Graph State And Topology

`IntakeGraphStateV2` extends the bounded common graph state with only canonical JSON values and
immutable references. It contains:

```text
schema_version = intake-graph-state.v2
bindings
version_pins
cognitive_revision
initial_snapshot_ref/hash/domain_revision
last_event_ref/hash/sequence
messages (stable-ID keyed reducer)
memory_summary
dossier_draft
readiness
missing_fields
recommendation
node_results
execution_receipts
usage_by_invocation
route
terminal_draft/result_json
```

It never contains a database/model client, connection pool, request object, secret, tool executor,
formal Java entity, entire Temporal payload, or `memory_frame`. The active prompt window contains at
most six ordered messages. Phase 3 outer limits remain mandatory: 1 MiB checkpoint, 256 KiB patch,
8 KiB per message, 64 KiB message total, 16 KiB summary, 64 pending work items, 100 artifact refs,
and eight `Send` items. The Intake terminal proposal is additionally capped at 64 KiB.

The exact topology is:

```text
START
  -> authorize_and_load
  -> import_snapshot_once_or_apply_event
  -> route_turn
       INITIALIZE -> deterministic_seed
       MESSAGE    -> intake_lcel
       REPLAY     -> cached_terminal_projection
  -> apply_dossier_patch
  -> validate_readiness
  -> project_intake_proposal
  -> checkpoint_terminal
  -> END
```

Routers are deterministic and exhaustive. Unknown routes fail closed. Confirmation and cancellation
are Workflow/domain commands, not model nodes. The graph has no long-running `interrupt`, Timer, or
external party wait; an incomplete turn returns `NEEDS_INPUT` with the exact party scope.

## Governed Intake LCEL

The model node is a real Runnable object flow:

```text
Intake State Lens
  | ChatPromptTemplate
  | GovernedChatModel
  | strict intake output parser
  | deterministic Intake guardrail
```

The State Lens emits only the current party's authorized six-message window, bounded summary,
dossier draft, immutable source refs, and trusted version identifiers. Untrusted text is a Human
message. System instructions, Prompt/Model profiles, schema, policy, guardrail, temperature, budget,
and empty tool allowlist come only from the signed registration/command and registry binding.

The parser rejects unknown fields and formal-action fields. The guardrail enforces source fidelity,
actor isolation, reference membership, patch allowlists, fact-ID/hash consistency, readiness
preconditions, and no internal-field exposure. Direct model HTTP from an Intake business node,
whole-state prompt formatting, or a `RunnableLambda` wrapper around the legacy monolith is forbidden.

## Intake Turn Proposal

`intake-turn-proposal.v2` binds the command, logical run, attempt, thread, case, room epoch, actor
scope hash, Agent Session, source event/snapshot hashes, cognitive revision, and all profile
versions. Its business payload is limited to:

```text
room_utterance
dossier_patch (approved Intake branches only)
unilateral_or_bilateral_matrix_patch proposal
readiness = INCOMPLETE | READY_TO_CONFIRM | NEEDS_REVIEW
missing_fields
recommendation = ACCEPTED | NEED_MORE_INFO | NOT_ADMISSIBLE
knowledge_answer_mode = NONE | STUB
confidence
```

The top-level dossier allowlist preserves current Intake branches and rejects `memory_frame`,
internal handoff fields, process state, writer mode, room transition, evidence deadline, review/tool
instructions, and hidden reasoning. Stable fact/source IDs cannot be deleted or rebound to another
hash by a later patch. Java never trusts proposal readiness as formal admission.

The proposal is written to an immutable, hash-bound proposal store and referenced by one
`PROPOSE_PATCH` operation in `RoomGraphResult.v1`. The Graph ledger commits the terminal checkpoint,
proposal ref/hash, and result hash under the same lease fence. Synthetic shadow uses an isolated
fixture proposal store. Production object storage and ACL proof remain external gates.

## Intake Workflow Protocol

The target `IntakeRoomWorkflow` state is small and deterministic:

```text
OPEN
WAITING_PARTY
AGENT_RUNNING
READY_TO_CONFIRM
COMPLETED
```

Party scope, initiator/respondent completion, terminal reason, pending operation key, command/event
sequence, process/room revision, epoch/fence, AgentRun/Graph refs, and pinned versions are separate
typed fields. Handlers only validate envelope shape and enqueue references. The main loop consumes
strictly sequenced `INTAKE_MESSAGE`, `INTAKE_CONFIRM`, and `INTAKE_CANCEL` commands plus committed
domain-event refs. Raw browser payloads and private message text do not enter Temporal History.

There is no new respondent timeout. `WAITING_PARTY` is a durable external wait until an authorized
submission/cancellation arrives. Time-skipping tests cover timers inherited from Activity retry and
cancellation policy, not a new product deadline.

Formal branch semantics remain:

| Command/condition | Java commit | Workflow result |
| --- | --- | --- |
| Initiator message | Formal party message, AgentRun request/outbox | Wait for idempotent Graph/Finalizer receipt |
| Initiator accept after Java readiness validation | Initiator completion, invitations, summons once; case remains Intake | Wait for respondent |
| Initiator not admissible | Terminal case state without invitation/downstream room | Complete `NOT_ADMISSIBLE` |
| Initiator cancel | Terminal cancellation without invitation/downstream room | Complete `CANCELLED` |
| Respondent message after unlock | Respondent-private message and AgentRun request | Wait for independent readiness |
| Respondent confirm against bilateral frozen matrix | Respondent completion, Intake close, Evidence open, existing two-hour Evidence window once | Complete `ADMITTED` |

Duplicate/out-of-order commands, stale epoch/fence, unauthorized actor, respondent action while
locked, respondent cancel, and confirmation without Java readiness fail without advancing state.

## Activity And Operation Keys

Every side-effect Activity uses the existing `domain_operation` ledger. Stable operation keys are:

```text
intake.snapshot.publish:{case_id}:{room_epoch}:{actor_scope_hash}:{domain_revision}
intake.graph.execute:{case_id}:{room_epoch}:{thread_id}:{command_id}
intake.turn.finalize:{case_id}:{room_epoch}:{thread_id}:{command_id}:{result_hash}
intake.initiator.accept:{case_id}:{room_epoch}:{command_id}
intake.initiator.reject:{case_id}:{room_epoch}:{command_id}
intake.cancel:{case_id}:{room_epoch}:{command_id}
intake.respondent.confirm:{case_id}:{room_epoch}:{command_id}
```

`operation_key` has a dedicated 512-character bound. This is intentionally wider than ordinary
identifiers so the exact Finalizer key remains lossless when both legal 128-character identifiers,
the opaque thread ID, the epoch, and the result hash are present; keys are never truncated or
silently replaced with a second derived hash.

An identical key/hash returns the committed receipt. A different request hash conflicts. Business,
authorization, schema, stale revision, stale fence, and guardrail failures are non-retryable.
Infrastructure retries share the end-to-end deadline and budget; Java commit followed by lost
Activity completion returns the existing operation receipt without repeating the model or domain
effect.

## Java Finalizer Contract

Before loading a proposal, Java revalidates tenant, case, room type/epoch, thread registration,
actor/session, command/logical run/attempt, request/result/proposal hashes, graph/checkpoint/profile
versions, process/room/stage revision, fencing token, current participation, and the AgentRun's sole
formal-final eligibility. It reloads the immutable proposal and recomputes its hash.

One ACID transaction writes the allowed formal message/dossier/matrix projection changes, AgentRun
terminal state, audit/domain event, required outbox, and `domain_operation` receipt. Existing
domain services still decide admission, completion, matrix freeze, invitations, summons, and room
opening. A repeated Finalizer returns the same receipt and formal IDs. Invalid, partial, stale,
cross-scope, or late results write none of those objects.

`SHADOW` runtime wiring uses a distinct comparison consumer and cannot resolve this Finalizer. Tests
may inject the formal port explicitly to prove transaction behavior, but production configuration
under the engineering gate cannot construct that graph.

## V043 Domain Persistence

V043 is expand-only and owns two Intake binding tables plus constraints/indexes:

| Table | Responsibility | Forbidden content |
| --- | --- | --- |
| `case_intake_graph_thread_binding` | Java-issued thread ID, complete private tuple, registration hash/status, pinned versions, epoch/fence, created/retired metadata | Checkpoint state, prompt text, party message text, memory summary |
| `case_intake_snapshot_binding` | Thread, snapshot type/ref/hash/size/visibility, source domain revision, one-time initialization marker | Snapshot body, Graph state, legacy `memory_frame` |

Finalizer idempotency continues to use the existing `domain_operation` ledger; execution manifests
continue to use the existing manifest table. V043 also adds nullable
`room_workflow_type`/`room_workflow_build_id` fields and the v2 selection constraint to the existing
fenced room epoch/provisioning projection. Existing rows remain v1-compatible; new v2 rows require
both child fields. V043 does not duplicate Graph or Temporal persistence and never modifies
V001-V042 in place.

## Projection And Compatibility

The Java Intake projection exposes explicit schema version, writer mode, room epoch, process and
room revisions, fencing token, room phase, pending state, active logical run/attempt, stream cursor,
party completion/lock state, and projection timestamp. Legacy fields and URLs remain compatible.
Projection lag returns a versioned processing state; neither API nor Vue guesses a transition.

Historical `room_turn_memory` and `memory_frame` remain readable for legacy/history views. A
graph-backed thread never writes `memory_frame` into a new dossier patch or uses Java's copy as its
cognitive source of truth.

## Shadow Parity, Privacy, And Observability

Synthetic parity compares only schema validity, stable facts, source ID/hash membership, readiness
class, normalized patch, recommendation class, guardrail result, terminal class, and privacy
invariants. Natural-language byte equality is excluded. Comparison rows contain bounded hashes,
classifications, and diffs, never party text or checkpoint state.

Metrics include command age, thread-registration conflicts, snapshot rejects, Graph restore/cache
hits, readiness diff, invariant diff, Finalizer conflicts, stale-fence rejects, duplicate-transition
attempts, projection lag, and private-thread leakage. Labels use bounded enums and hashed/sampled
identifiers; no PII, message text, prompt body, or hidden reasoning enters metrics/logs/traces.

Privacy leakage, unauthorized field, duplicate formal effect, stale-fence success, cross-thread
checkpoint, or formal sink reachability from `SHADOW` has a zero threshold and fails the candidate.

## Versioning And Rollback

Workflow build, graph/checkpoint/state, prompt/model/output schema, policy/guardrail/tool policy, and
stream versions are pinned when the epoch/thread is created. An active epoch never follows a
mutable default. Old versions remain loadable while referenced by an epoch, command, checkpoint,
manifest, operation receipt, or evidence bundle.

Engineering rollback leaves Intake allocation `LEGACY`, disables the synthetic registry binding,
fences/cancels nonterminal synthetic commands, and preserves V043/G001-G003 data for replay. It does
not restore unsigned transport, process-memory checkpoints, shared secrets, or direct business-node
model HTTP.

Future canary rollback has three safe boundaries:

1. Before a formal terminal Intake action, stop allocation and create a higher fenced legacy
   recovery epoch from formal refs.
2. After initiator acceptance, preserve invitations/summons and resume respondent-only Intake in a
   higher fenced recovery epoch without repeating external effects.
3. After respondent completion opens Evidence, never reopen Intake; reuse the operation receipt and
   reconcile forward.

Mode never changes inside an epoch and migrations are not rolled back destructively.

## P4.0 Verification Requirements

The contract gate requires:

- Positive/negative Java and Python fixtures for all six Intake schemas and RFC 8785 hashes.
- Static proof of one primary owner per Phase 4 Check ID and non-overlapping delegated change paths.
- Static/runtime proof that defaults are `LEGACY` and `DISABLED`, real `TEMPORAL` allocation is
  unreachable, and signed synthetic `SHADOW` cannot resolve a formal Finalizer.
- Thread registration replay/conflict/cross-scope tests and one-time snapshot import tests.
- Exact baseline inventory for `INT-001..010`, `OVR-003`, `CORE-004..010`, `SEC-001..006`,
  `UI-001`, `UI-003`, and `UI-004`.
- Batch 0 reports from one clean contract-candidate SHA, followed by a separate entry-evidence
  commit.

The recorded state after a successful engineering entry gate remains:

```text
P4.0: PASS
engineering_execution: ALLOWED_WITH_DISABLED_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
formal_intake_writer: FORBIDDEN
```

## Frozen P4.0 Decisions

1. Phase 4 engineering is synthetic-only; no local result can enable real shadow, canary, or a
   formal writer.
2. Five delegated implementation owners retain unique accountability and execute in two waves under
   the four-agent concurrency cap.
3. Private thread registration is a separate signed, outbox-recoverable, idempotent protocol; no
   component derives Agent Session authority from `RoomGraphCommand.v1` or `thread_id`.
4. Initialization imports one actor-filtered immutable snapshot once; later messages are ordered
   event refs. `memory_frame` is neither imported nor written back on graph-backed threads.
5. The explicit topology, bounded typed state, six-message window, exhaustive routers, and real
   governed LCEL chain are fixed. LangGraph owns no long party wait.
6. Graph output is a 64 KiB hash-bound `intake-turn-proposal.v2` pointer inside
   `RoomGraphResult.v1`; it never carries formal-action authority.
7. Workflow has the five fixed states and three accepted command types. There is no new respondent
   timeout.
8. Java revalidates every authority/hash/version/revision/fence binding and commits formal effects
   plus the operation receipt atomically; replay returns the same receipt.
9. V043 stores only Intake thread and snapshot bindings. It stores no checkpoint, private snapshot
   body, prompt, summary, or memory copy.
10. Any privacy, writer, fencing, idempotency, or duplicate-transition violation is a hard failure.
11. P4.0 evidence uses a tested contract-candidate SHA plus a later evidence commit, never a
    self-referential evidence SHA.
12. `room-epoch-selection.v2` separates Case and room child workflow identities; typed Intake child
    dispatch uses `typed-intake-room-child-v1`, while v1 and old History stay on the generic child.
13. Promotion remains independently gated; `MIG-004` cannot pass while `MIG-003` is pending.

## Matrix Proposal Authority Erratum

The original `unilateral_or_bilateral_matrix_patch proposal` wording is refined by
[`phase-4-p4.0-matrix-authority-erratum.md`](./phase-4-p4.0-matrix-authority-erratum.md). The strict
`intake-turn-proposal.v2.matrix_patch` union is `null`, a current-actor
`unilateral_case_matrix.draft.v1`, or an unlocked-respondent `case_fact_matrix.delta.v2`.

Neither non-null member is a formal matrix. Java alone authorizes the actor and unlock state,
derives stable IDs, versions, provenance and canonical hashes, and creates or freezes the formal
unilateral/bilateral projection. The total proposal limit remains 64 KiB. This correction requires
a new exact-SHA Batch 0 re-authentication before its implementation can enter the Phase 4
integration candidate. `MIG-003` and `MIG-004` remain `PENDING_PROMOTION`.

An incompatible change requires an additive schema/contract version or accepted ADR, updated
cross-language fixtures, and explicit notification to every affected owner.
