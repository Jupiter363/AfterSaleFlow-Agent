# ADR 0004: Graph Checkpoint, Command Ledger, and Lease

- Status: ACCEPTED
- Date: 2026-07-17
- Decision owner: Python, Platform, Security
- Approved by: project owner through the 2026-07-17 plan approval

## Current implementation note (2026-09-04)

This decision is implemented in the target lane: Graph PostgreSQL, the command ledger,
database-clock lease/fencing, checkpoint persistence and Java finalization remain separate from
the Domain and Temporal stores. The current graph selection is
`all-rooms.production-runtime.v2` / `production-runtime-graph.2026-08-18.3` /
`production-runtime-checkpoint.v2`; Intake additionally uses exact-three Frame authority. Historical
check IDs later in this ADR are traceability labels, not unresolved release status.

## Context

Intake and Evidence currently compile a graph for one invocation and rely on Java to send memory
back on the next turn. Hearing operations are independent functions. Process retries can therefore
repeat model work, and an overlapping Python retry has no durable fence.

## Decision

### Storage and identity

Production graphs use `langgraph-checkpoint-postgres` in a Graph database isolated from Domain and
Temporal persistence by database or schema, role, pool, migration, backup, and resource limits.
Thread identity binds tenant surrogate, case, room epoch, actor scope, agent session, graph key, and
graph version. A shared Hearing thread receives only formal, authorized artifacts and never private
party chat text.

### Command ledger

`agent_graph_command` has a unique `(thread_id, command_id)` and stores the RFC 8785 request hash,
deadline, version bindings, status, checkpoint/result references, and result hash. The same command
and hash returns the committed result. The same command with another hash fails closed and emits a
security audit event.

### Lease and crash recovery

A persisted lease uses a monotonically increasing fencing token. A takeover may start only after
the prior lease expires or is explicitly cancelled; the old token can no longer commit checkpoint,
ledger, or result state. Recovery is defined at four boundaries:

1. Before model invocation: retry from the prior checkpoint.
2. After model response but before checkpoint: the failed attempt is recorded and stream reset
   semantics apply; retry budget decides whether another provider call is allowed.
3. After checkpoint but before command completion: reconcile the checkpoint's committed command and
   result hash into the command ledger without calling the model.
4. After command completion but before Java response: return the cached result and hash.

### State and topology

- State contains bounded serializable values and immutable references, never clients, pools,
  secrets, request objects, or tool implementations.
- Each model node receives an explicit State Lens. Whole-state prompt injection is forbidden.
- Room topology is explicit typed `StateGraph` code. Unknown router values fail closed.
- `Send` is limited to independent work, with a room limit of 8 and additional tenant/global
  semaphores. Reducers merge by stable key and reject duplicate-key payload conflicts.
- Formal party wait and deadlines return `NEEDS_INPUT` to Temporal. Graph `interrupt` is not used as
  a second long-running wait owner.
- Graph and checkpoint schema versions are pinned to room epoch. Old versions remain loadable until
  active references reach zero or a tested safe-boundary migration is approved.

## Migration

Intake and Evidence import one bounded, hash-bound initialization snapshot. Java keeps historical
memory readable but stops round-tripping `memory_frame` as cognitive truth after cutover. Hearing
builds new threads from formal dossier/artifact references; it does not import a process cursor.

## Verification

Primary checks: `GRAPH-001..022`, `HA-003..005`, `HA-011`, `SEC-003..005`, and `MIG-003`.
Tests must cover all four crash boundaries, lease takeover, command hash conflict, deterministic
Reducer order, unauthorized thread IDs, database failover, and version pinning.
