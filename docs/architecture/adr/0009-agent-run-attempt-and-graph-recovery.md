# ADR 0009: AgentRun Attempt Ownership and Graph Recovery

- Status: ACCEPTED; ORIGINAL PHASE-3 RESTRICTION SUPERSEDED BY THE IMPLEMENTED TARGET LANE
- Date: 2026-07-19
- Decision owner: Java AgentRun, Temporal orchestration, Python Graph runtime
- Scope: AgentRun V2, RoomGraphCommand, Agent Stream V2, and Graph recovery
- Runtime restriction: `DISABLED` or signed synthetic `SHADOW` only

## Current implementation note (2026-09-04)

The attempt/command/lease distinctions and fail-closed recovery rules in this ADR remain current.
Its original `DISABLED`/synthetic-`SHADOW` restriction applied to the Phase 3 implementation
window. The later manifest-bound `TARGET_E2E_CANDIDATE` lane is implemented and has completed an
isolated browser UAT; this does not authorize automatic production activation. Current runtime
identity and evidence are recorded in
[the current UAT baseline](../../release/current-uat-baseline.md), while default and target
deployment boundaries are documented in [deployment](../../deployment/README.md).

## Context

ADR 0003 defines a logical AgentRun with ordered public attempts and requires `attempt_reset`
before a client replaces visible text from a failed attempt. ADR 0004 and the Phase 3 Graph
gateway add durable command ledgers, database-clock leases, fencing tokens, checkpoint recovery,
and cached terminal reconciliation.

Those decisions leave an authority ambiguity. A Java AgentRun attempt, a Temporal Activity retry,
a RoomGraphCommand, a Python process execution, a Graph lease generation, and a public stream
attempt are not the same thing. Treating any Python lease takeover as a public retry would create
spurious resets. Reusing a RoomGraphCommand for a new public attempt would instead let two public
histories share one command identity.

The current implementation also exposes concrete conflicts:

- Python correctly returns `GRAPH_NEW_AGENT_ATTEMPT_REQUIRED` after a command has started and never
  authorizes a new public attempt itself. The shared stream schema and Java compatibility reader
  still admit `attempt_reset`, so every Python execution gateway must reject an executor-produced
  reset rather than relying on producer convention.
- Java persists stream events directly from the Python sequence. It has no authoritative step that
  binds a reset to the immediately previous Java attempt before public deltas are stored.
- Java stores the first RoomGraphCommand `request_hash` as if it were a logical-run hash, although a
  later attempt must have a new `command_id`, `attempt_id`, nonce, residual budget, and therefore a
  different command hash.
- The current envelope correctly separates the stable body-bound `command_nonce` from the JWS
  `jti`. Every HTTP delivery must retain the exact command body and signing `kid`, but use a fresh
  short-lived JWS and fresh `jti`; replay protection keys on `(issuer, kid, jti)`. That separation
  must be preserved in every client and recovery path.

This ADR resolves attempt and stream authority. It does not authorize formal writer mode or change
the frozen Phase 3 schemas in place.

## Decision

### 1. One-to-one public identity

The following identity is invariant:

```text
Java AgentRun attempt (logical_run_id, attempt_no, attempt_id)
    = one RoomGraphCommand (logical_run_id, unique command_id, same attempt_id)
    = one public Agent Stream attempt (run_id=logical_run_id, same attempt_id)
```

A logical AgentRun may contain multiple ordered attempts. Every later attempt has a new
`attempt_id`, new `command_id`, and new command `request_hash`. At most one attempt may commit the
logical run's formal result.

A Temporal Activity retry of the same `ExecuteAgentRunRequest` is a delivery retry, not a new
AgentRun attempt. It reuses the exact attempt and command identities. It may only replay, resume a
provably pre-execution command, or reconcile a committed terminal checkpoint. It may not make a
second ambiguous model call. The serialized RoomGraphCommand and its `command_nonce` remain byte
for byte stable; the delivery credential is re-signed with a fresh `jti`. The original signing key
must remain usable for the command's bounded recovery window because `envelope_key_id` is part of
the immutable command binding.

Python `owner_id`, lease revision, lease expiry, fencing token, worker process, and checkpoint
saver transaction are internal execution generations. Any number of those generations may serve
one RoomGraphCommand. They never allocate an AgentRun attempt and never emit `attempt_reset`.

### 2. Authority by layer

| Decision | Sole authority |
| --- | --- |
| Allocate `attempt_no`, `attempt_id`, `command_id`, and immediate predecessor | Java AgentRun ledger under the logical-run lock |
| Accept and serialize an ordered attempt | Temporal AgentRun Workflow Update with `update_id=attempt_id` |
| Decide waits, Activity delivery retries, deadlines, and failure timing | Temporal, within Java-signed budgets |
| Acquire or take over a Graph lease and increment a fence | Python Graph database |
| Decide `RETURN_CACHED`, `RECONCILE_TERMINAL`, or `REQUIRE_NEW_AGENT_ATTEMPT` from Graph facts | Python Graph recovery coordinator |
| Decide whether a new public attempt is allowed | Java, using the Python disposition plus Java deadline, budget, and logical-run state |
| Persist the public attempt lineage and publish `attempt_reset` | Java AgentRun stream writer |
| Commit formal domain facts and the winning attempt | Java Finalizer under room epoch and fencing checks |

Temporal does not invent a new command because Workflow code cannot read Java business state or
sign a command. Python does not invent a new attempt because it does not own the logical AgentRun
or the public stream history.

### 3. Java attempt lineage

Java allocates attempts strictly in order while holding the logical AgentRun row lock. The durable
attempt record binds:

```text
logical_run_id
attempt_no
attempt_id
command_id
command_request_hash
logical_input_hash
previous_attempt_id (null only for attempt 1)
reset_required
public_sequence_offset
termination_code
```

`previous_attempt_id` is exactly attempt `N-1`; skipping an attempt is forbidden. `reset_required`
is true if and only if the immediately previous attempt has at least one durable public
`visible_delta` and Java has authorized the new attempt. The durable event store is authoritative;
a lagging heartbeat boolean cannot by itself prove that output was or was not public.

The new attempt must preserve the logical input and immutable policy. The allowed differences are
limited to attempt and command IDs, delivery authentication, residual retry counters, and the
attempt trace span. Residual budgets may stay equal or decrease but never increase. Tenant, case,
room epoch, Graph thread and versions, actor scope, process/stage revision, snapshot references,
prompt/model/schema/policy/guardrail/tool profiles, absolute deadline, and logical trace identity
must remain equal.

`logical_input_hash` is a Java canonical hash of those stable fields. It is not the
RoomGraphCommand self-hash. Each attempt row separately retains its exact command body and
`command_request_hash`.

Before admitting attempt `N+1`, Java reconciles attempt `N` one final time. A terminal checkpoint
or committed result wins and is completed under attempt `N`; Java must not create a reset or call a
new model. Only a durable `REQUIRE_NEW_AGENT_ATTEMPT` disposition can supersede attempt `N`.

### 4. Public stream prelude and Python projection

Java owns the canonical public prelude and persists it before any model-visible event:

```text
sequence 0: attempt_started(new attempt_id)
sequence 1: attempt_reset(reset_attempt_id=previous_attempt_id), only when reset_required
```

The reset belongs to the new attempt envelope. `reset_attempt_id` names the immediately previous
attempt whose temporary text the client must remove. A reset never names the current attempt, is
never terminal, and is never emitted when the prior attempt produced no durable visible text.

During compatibility with the current Python Agent Stream V2 producer:

1. Python's sequence-zero `attempt_started` is an identity handshake for the signed command.
2. Java validates and consumes that handshake; Java's ledger-derived prelude is the public record.
3. Python `attempt_reset` is always rejected as `AGENT_RUN_STREAM_RESET_AUTHORITY_VIOLATION`.
4. Python candidate sequence `p >= 1` maps to public sequence `p + offset`, where `offset` is `1`
   when Java inserted a reset and `0` otherwise.
5. The offset is fixed before the first candidate event is persisted and is stored with the Java
   attempt. No event may later be inserted ahead of a durable event.

A later internal Python protocol may omit the handshake, but that change must be versioned. It
does not change Java's ownership of the public prelude.

### 5. Attempt and logical terminals

`attempt_aborted` is an attempt-local terminal. It is used when Java may authorize a later
attempt. The next attempt includes `attempt_reset` only when the aborted attempt emitted durable
visible text.

`final` and `error` are logical-run terminals. No later attempt may follow either event. A failure
that can create a new model attempt must therefore end with `attempt_aborted`, not a retryable
`error`. The `error.payload.retryable` flag describes transport replay of the same durable terminal
to a client; it is not authority to call a model again.

Java must synthesize a bounded `attempt_aborted` or `error` when execution fails before Python can
produce a valid terminal frame. A public attempt is never left open merely because the Python
connection ended.

### 6. Recovery matrix

| Durable Graph state | Model call | Java/public action |
| --- | ---: | --- |
| Command not registered | No | Redeliver the same command only through a valid delivery credential |
| `REGISTERED`, no Graph attempt | Allowed within deadline and remaining budget | Same Java attempt; no reset |
| `EXECUTING`, any Graph attempt exists | Forbidden for that command | Return `GRAPH_NEW_AGENT_ATTEMPT_REQUIRED`; Java may allocate the next attempt |
| Provider-call intent exists, no terminal checkpoint | Forbidden for that command | Same as above; never guess whether the provider completed |
| `RESULT_CHECKPOINTED` | No | Reconcile the same command and same public attempt |
| `COMPLETED` | No | Return the cached result for the same command and attempt |
| `CANCELLED` or `ABORTED` | No | Return the stable terminal disposition; Java applies logical-run policy |
| Binding, hash, fence, or checkpoint conflict | No | Fail closed and require investigation; do not create an automatic attempt |

An internal lease loss is not by itself a retry decision. Java follows the Graph recovery
disposition. A lease takeover that restores a checkpoint remains the same public attempt and emits
no reset.

### 7. Cached terminal and public replay

PostgreSQL on the Java side is authoritative for public stream replay. Redis is only a wakeup
channel, and reconnecting a browser never calls Python.

For a repeated Activity delivery of the same attempt:

- If the public `final` and Java finalization receipt already exist, Java returns the existing
  result and receipt without appending an event.
- If Python is `COMPLETED`, Java validates the cached result against the exact command and attempt.
  It does not publish another `attempt_started`, reset, delta, or usage event.
- If Python is `RESULT_CHECKPOINTED`, Python reconciles without a model call. Java appends at most
  one missing `final`, bound to the immutable result reference and hash.
- A missing `final` is appended at the Java durable high-watermark plus one. It cannot be appended
  after `attempt_aborted` or `error`.
- Any already persisted sequence is reused only when its canonical public event hash matches. A
  conflicting payload is an integrity failure, not a reason to renumber or start another attempt.

The event used for an idempotent append is stored in a Java outbox before publication. Recovery
does not regenerate `occurred_at` with the current clock and thereby change the event hash.

Only a durable `final` for the winning attempt may invoke Finalizer. Finalizer response loss reads
the committed Java receipt; it never calls Python or emits another public event. A late final from
a superseded attempt is rejected as `AGENT_RUN_STALE_ATTEMPT_FINAL` even if its Python fence once
was valid.

### 8. Sequence, hash, and cursor rules

Public `sequence_no` is contiguous and attempt-local, starting at zero. Ordering across attempts is
the Java `attempt_no`; sequence numbers never form a logical-run-global counter.

The canonical public event, after Java prelude insertion and sequence mapping, is what Java hashes
and stores. A Python candidate frame or Python lease generation is not the public hash identity.
The immutable key is `(run_id, attempt_id, sequence_no)`, and a duplicate key is accepted only with
the same canonical hash.

Agent Stream V2 is not a hash chain. Integrity comes from the immutable tuple/hash rows, contiguous
sequences, the attempt ledger, and the winning manifest's `final_stream_sequence_no`. Adding a
previous-event hash would require a new stream schema; it must not be smuggled into V2.

A V2 replay cursor is `(attempt_id, sequence_no)`. Replaying from an old-attempt cursor returns the
remaining old-attempt events and then later attempts in `attempt_no` order. Vue applies a reset only
to `reset_attempt_id`; it never clears committed output from another logical run or concatenates
deltas across attempts.

### 9. Failure codes and retry meaning

| Code | Same-command Activity retry | New public attempt | Handling |
| --- | ---: | ---: | --- |
| `GRAPH_NEW_AGENT_ATTEMPT_REQUIRED` | No | Conditional | End the current attempt locally; Java checks deadline/budget and allocates the next command |
| `GRAPH_LEASE_UNAVAILABLE` | Inspect first | No direct authority | Retry only if the command remains `REGISTERED`; otherwise follow the recovery matrix |
| `GRAPH_LEASE_LOST` | Inspect first | No direct authority | Reconcile checkpoint, return cached, or require a Java attempt; never reset directly |
| `GRAPH_COMMAND_DEADLINE_EXCEEDED` | No | No | Logical terminal `error` |
| `GRAPH_COMMAND_HASH_CONFLICT` or `GRAPH_COMMAND_BINDING_CONFLICT` | No | No | Security/integrity terminal and audit |
| `GRAPH_TERMINAL_BINDING_CONFLICT` | No | No | Quarantine and operator reconciliation |
| `AGENT_RUN_STREAM_RESET_AUTHORITY_VIOLATION` | No | No | Reject Python stream, emit no untrusted reset |
| `AGENT_RUN_PUBLIC_SEQUENCE_CONFLICT` | No | No | Quarantine immutable stream rows |
| `AGENT_RUN_STALE_ATTEMPT_FINAL` | No | No | Reject late final and retain the selected attempt |
| `AGENT_RUN_LINEAGE_CONFLICT` | No | No | Reject a non-sequential or semantically changed attempt |

The existing boolean `retryable` is never sufficient to select the row in this table. Java uses a
stable recovery classification. In particular, `GRAPH_NEW_AGENT_ATTEMPT_REQUIRED` is retryable at
the logical-run level but non-retryable for the current Activity request.

## Transaction and Crash Boundaries

Java writes the new attempt, predecessor binding, reset decision, and public-prelude outbox in one
transaction. Temporal dispatch occurs after that transaction. Duplicate dispatch uses
`attempt_id` as the Update ID.

For this implementation, the immutable `agent_run_stream_event` prelude rows are the durable
outbox and the public replay fact; there is no second prelude table or relay that could become a
competing source of truth. Redis publishes only a best-effort high-watermark hint. A missed hint
delays a live subscriber until PostgreSQL catch-up but cannot lose or regenerate the prelude.

The required recovery behavior is:

| Crash point | Recovery |
| --- | --- |
| Attempt row committed, prelude not published | Outbox publishes the same stored prelude |
| Prelude committed, Temporal Update response lost | Update replay returns the same accepted attempt |
| Python lease lost before command execution record | Same command may resume only if Graph reports `REGISTERED` with no attempt |
| Python execution record exists, no checkpoint | Current command is sealed; Java decides whether to create the next attempt |
| Terminal checkpoint committed, Python response lost | Reconcile same attempt, append only a missing final, then Finalizer |
| Java final committed, response lost | Read the finalization receipt; no Python call or stream append |

No crash recovery path rewrites an existing public sequence or turns a Python fence generation
into an AgentRun attempt.

## Migration and Rollout

1. Keep AgentRun V2 and Graph execution in signed synthetic `SHADOW`.
2. Add Java attempt-lineage and prelude-outbox storage. Backfill attempt one with a null predecessor
   and zero offset.
3. Backfill later SHADOW attempts only when the ordered attempt rows and immutable stream events
   prove the immediate predecessor and reset decision. Otherwise pin and drain the old Workflow
   build; do not infer lineage.
4. Make Java reject Python `attempt_reset`, generate the public prelude, and apply the deterministic
   sequence mapping behind a versioned worker/build gate.
5. Add same-attempt cached/reconcile append logic based on the Java high-watermark and stored event
   hashes.
6. Deploy readers and Vue reset handling before enabling the new writer. V1 streams remain
   read-only and never receive synthetic V2 resets.
7. Run the tests below from one candidate commit. Promotion remains blocked until every contract
   delta is resolved and independent environment evidence is approved.

The versioned build in step 4 also corrects pre-production SHADOW serialization to omit absent
optional Agent Stream fields, as required by the V2 JSON Schema. Attempts whose hashes were
created by the earlier null-emitting serializer stay pinned to and drain on their old build; they
must not be replayed through the corrected serializer.

Rollback stops admission of new V2 attempts and drains the pinned build. It never deletes a reset,
renumbers a stream, changes an event hash, or turns an active V2 logical run into a V1 executor.

## Verification

Required tests include:

- Java ledger concurrency proving one sequential next attempt and one immutable predecessor.
- Temporal duplicate Update, reordered Update, bounded attempt, deadline, and Continue-As-New
  history tests.
- Python recovery tests proving every lease takeover has `emit_attempt_reset=false` and a started
  command returns `GRAPH_NEW_AGENT_ATTEMPT_REQUIRED` without a model call.
- Python and Java negative tests rejecting executor-produced `attempt_reset`.
- Java stream tests for reset-if-and-only-if-prior-visible, immediate-predecessor binding,
  contiguous sequence mapping, and exact duplicate hashes.
- Crash tests for every boundary in the table, including terminal checkpoint and Finalizer response
  loss.
- Cached-result tests proving zero additional provider calls and zero duplicate public prelude or
  reset events.
- Delivery-retry tests proving the command body/hash and `command_nonce` stay unchanged while each
  JWS uses a fresh `jti`, and proving reuse of an old `jti` is durably rejected.
- Late old-attempt final, conflicting sequence hash, stale fence, and logical-input mutation tests.
- Browser replay tests from cursors before reset, at reset, after reset, and across reconnects.
- Migration tests covering V1 rows, pre-change V2 rows, pinned workers, and rollback without stream
  mutation.

Primary acceptance mappings are `RUN-004..009`, `STREAM-001..013`, `TEMP-033..036`,
`GRAPH-003..010`, `GRAPH-021..022`, `HA-004..005`, and `DR-007`.

## Unresolved Contract Deltas

These are promotion blockers, not optional refinements. Delivery nonce separation is not one of
them: `jti` is already outside the RoomGraphCommand body, while `command_nonce` deliberately binds
the stable command. A retry signs the unchanged body with the same retained `kid` and a fresh
`jti`; V1 must never be weakened to accept replay of a consumed `jti`.

The explicit recovery-action delta is resolved in `ExecuteAgentRunResult.v3`. Java now carries the
closed `RETRY_SAME_COMMAND`, `CREATE_NEXT_ATTEMPT`, `RECONCILE_TERMINAL`, or `FAIL_LOGICAL_RUN`
action through the gateway, Activity, Temporal payload, and Workflow decision. The legacy
`retryable` field remains only as a constructor-enforced compatibility summary and is not a routing
authority. `v3` must be introduced on a new pinned Temporal worker build; existing histories that
contain `ExecuteAgentRunResult.v2` remain assigned to their prior compatible build and are not
replayed through the `v3` constructor.

1. **Logical hash versus attempt hash.** Java `CreateLogicalRun` and `AgentRunEntity` currently bind
   the first command `request_hash` as a logical invariant. They require an explicit
   `logical_input_hash` and per-attempt `command_request_hash`; later attempt command hashes are
   expected to differ.
2. **Temporal predecessor binding.** The current `ExecuteAgentRunRequest` lacks
   `previous_attempt_id` and `logical_input_hash`. Java can derive them under the row lock for Phase
   3 SHADOW, but a versioned Temporal payload is required before cross-build replay and
   Continue-As-New promotion.
3. **Standalone manifest lineage.** `agent-execution-manifest.v1` identifies the winning attempt but
   not its attempt number or predecessor. The Java ledger and stream are authoritative for V1. If a
   standalone exported manifest must prove the retry chain, that is an additive manifest V2, not an
   in-place V1 field.

`RoomGraphCommand` does not need `previous_attempt_id`: predecessor and reset authority are
deliberately Java-only. `agent-stream.v2.reset_attempt_id` already carries the required public
reference. Neither schema is modified by this ADR.

## Rejected Alternatives

- Treat every Python lease fencing-token increment as a public attempt.
- Let Python emit `attempt_reset` based on process-local or Graph-only history.
- Reuse one RoomGraphCommand across multiple public attempts.
- Blindly retry a started command after an ambiguous provider call.
- Publish a new attempt when a terminal checkpoint can be reconciled.
- Use `error.retryable` or `ExecuteAgentRunResult.retryable` as the sole retry decision.
- Insert a reset into an already persisted sequence and renumber later events.
- Change frozen V1/V2 schemas in place or accept an already consumed delivery nonce.
