# Phase 2 P2.0 Shared Contract Pack

## Entry And Scope

```text
entry: ADR-0007 APPROVED_OFF_SHADOW_DEVELOPMENT_EXCEPTION
promotion_gate: MIG-001 PENDING
runtime_default: V2 OFF
production_cutover: FORBIDDEN
```

This pack freezes the interfaces used by all Wave 1 owners. It does not enable a V2 writer or
executor. JSON Schema remains authoritative for the existing `RoomGraphCommand`, `RoomGraphResult`,
`agent-stream.v2`, and execution manifest contracts.

## Java Execution Boundary

- `ExecuteAgentRunRequest` wraps the existing `RoomGraphCommand`; identity fields must not be copied
  into another independently mutable structure.
- Activity heartbeats use `AgentRunAttemptHeartbeat` and persist only public progress metadata.
- `ExecuteAgentRunResult` is hash-bound to `RoomGraphResult.output_hash`; failed or cancelled results
  cannot carry a graph result.
- `AgentRunFinalizationReceipt` binds attempt, fence, result hash, manifest hash, and final stream
  sequence. `ALREADY_COMMITTED` is a successful idempotent replay, not a second commit.
- Start-to-close, heartbeat timeout, and progress heartbeat interval are fixed at 10 minutes,
  15 seconds, and 5 seconds for Phase 2.

## Runtime Selector

`app.agent-run-v2` has these legal transition states:

| enabled | protocol-default | scheduler-mode | Meaning |
| --- | --- | --- | --- |
| false | V1 | EXECUTOR | Current production-compatible default |
| true | V1 | DETECTOR | V2 storage/worker shadow; legacy scheduler detects only V2 candidates |
| true | V1 | OFF | V2 explicit synthetic selection; legacy scheduler disabled |
| true | V2 | DETECTOR/OFF | Development exception only; never a production default in Phase 2 |

`V2 + EXECUTOR` is invalid because Temporal Activity is the only V2 executor.

## V041 Expand And Backfill Contract

V041 is expand-only and must preserve every V1 row and reader:

- `agent_run` becomes the logical-run row and gains tenant, protocol, logical idempotency,
  executor/finalization state, committed attempt, and final result hash columns.
- Existing rows backfill as V1 with deterministic legacy logical keys. The V1 attempt identifier is
  the existing `agent_run.id`, so V040 legacy manifests and old events remain valid without mutation;
  no model output or modern provenance may be fabricated.
- `agent_run_attempt` is append-oriented and enforces `(agent_run_id, attempt_no)` plus a stable
  attempt identifier. It records heartbeat, public-output, provider/model/version, usage, result,
  and error metadata.
- `agent_run_stream_event` keeps V1 replay while adding nullable attempt binding, protocol, audience,
  and payload hash. V2 uniqueness is `(agent_run_id, attempt_id, sequence_no)`; V1 retains its
  per-run sequence invariant.
- `agent_execution_manifest` remains immutable and at most one committed formal manifest may bind
  a logical run.
- Demo purge must delete new child rows before `agent_run`; production foreign keys remain strict.

Forbidden in V041: dropping/renaming V1 columns, rewriting V1 payloads, enabling V2 defaults,
deleting legacy indexes before a dual-reader checkpoint, or adding an irreversible data cleanup.
Rollback is application rollback with V2 disabled; expanded storage remains readable and dormant.

Wire spellings are intentionally asymmetric and immutable: V1 remains `agent_stream.v1`; V2 is
`agent-stream.v2`. The Java selector uses `V1|V2` only as an internal enum and must not rewrite wire
payloads. A V2 logical run retains the existing immutable role/actor allowlist. The singular
`AgentStreamEvent.audience` is projected for the authenticated reader; it does not create another
model run or duplicate the durable event identity `(run_id, attempt_id, sequence_no)`.

## Wave 1 Ownership

- A owns V041, logical/attempt/manifest persistence, and exact database invariants.
- B owns Temporal Activity implementation and returns worker registration requirements to the primary.
- C owns V1/V2 stream adapters and public allowlisting across Java/Python; frontend changes wait for C2.
- The primary owns shared contracts, runtime selector, worker registration, Coordinator/Lifecycle,
  Finalizer, and integration.

No owner may change these shared records or selector semantics without returning the change to the
primary and rebasing all Wave 1 worktrees onto the new contract commit.
