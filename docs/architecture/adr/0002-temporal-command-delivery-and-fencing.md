# ADR 0002: Temporal Command Delivery and Fencing

- Status: ACCEPTED
- Date: 2026-07-17
- Decision owner: Java and Temporal
- Approved by: project owner through the 2026-07-17 plan approval

## Context

Calling Temporal only after a Java transaction commits leaves a crash window. Calling Temporal
inside the transaction cannot atomically commit both systems and would turn availability failures
into long database transactions. Direct scheduler recovery also risks a second process writer.

## Decision

### Durable acceptance

Java authenticates and authorizes a command, canonicalizes its payload, and writes `case_command`
plus `case_command_outbox` in one ACID transaction. Durable acceptance may return
`PENDING_ORCHESTRATION`; it does not claim that Temporal has already advanced.

The normal delivery path is Temporal Update-With-Start with `command_id` as the Update ID. An
outbox dispatcher is the recovery path. Duplicate delivery is expected and is resolved by the Java
command ledger and request hash, not by timing assumptions or XA/2PC.

### Ordering

- Java allocates a strictly increasing `case_command_sequence` under a case-level lock or an
  equivalent serializable mechanism.
- Workflow Update and Signal handlers only validate envelope shape and enqueue work. The Workflow
  main loop serializes decisions.
- Domain callbacks carry `case_event_sequence`. Duplicate events are ignored; gaps use a bounded
  buffer and `LoadDomainEventsActivity` before any transition.
- Continue-As-New carries the next expected sequence and bounded pending references. Cross-run
  command deduplication remains in Java.

### Fencing

Each room activation persists `writer_mode`, `room_epoch`, monotonically increasing
`fencing_token`, process/room revisions, Workflow build, Graph version, checkpoint schema, and
stream protocol. Projection and Finalizer updates require the current epoch/token and a strictly
higher revision. An old Workflow, Activity, graph lease, scheduler, or delayed response therefore
cannot overwrite the active epoch.

### Failure and rollback

- Temporal unavailable: commands stay in outbox; reads continue from versioned Java projections.
- Java unavailable: Temporal retries idempotent Activities; it does not invent formal facts.
- Dispatcher overlap: `SKIP LOCKED` or equivalent leasing improves throughput, while command hash
  and sequence preserve correctness.
- Rollback never flips an active Temporal epoch back to a legacy writer. New intake is stopped,
  in-flight side effects are reconciled, and a higher recovery epoch/token is created at an approved
  safe boundary.

## Rejected Alternatives

- XA/2PC across PostgreSQL and Temporal.
- A best-effort post-commit callback without an outbox.
- A global mutable feature flag read during Workflow replay.
- Re-enabling Spring stage schedulers for an active Temporal epoch.

## Verification

Primary checks: `TEMP-010..018`, `TEMP-022..024`, `TEMP-030..033`, `JAVA-001..009`,
`E2E-001..011`, and `MIG-001`. Required tests include commit/delivery kill windows, duplicate and
reordered dispatch, stale fence rejection, Continue-As-New, and projection reconciliation.
