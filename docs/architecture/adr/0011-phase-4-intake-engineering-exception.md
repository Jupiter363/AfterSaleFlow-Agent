# ADR 0011: Phase 4 Intake Engineering Exception

- Status: ACCEPTED FOR DEVELOPMENT
- Date: 2026-07-20
- Scope: Phase 4 Intake pilot implementation and synthetic verification
- Approval: repository owner instruction to continue automatically with one primary and five delegated implementation owners

## Context

Phase 3 passed its engineering checkpoint at candidate
`9351a9d65230ce5bfc332bc59ec567ecb8a964c5`, but `MIG-003` and its production-equivalent identity,
load, soak, failover, DR, and approval evidence remain pending. Phase 4 must build the first bounded
room graph, typed Intake Workflow, Java proposal boundary, and recovery controls before those
controls can be evaluated as a production-shaped whole. Treating the Phase 3 engineering result as
promotion would violate the master migration gate; refusing to build any disabled component would
create a circular dependency.

## Decision

Phase 4 engineering may proceed only after the committed P4.0 entry gate passes, under all of these
restrictions:

- Java Intake writer remains `LEGACY`; Graph runtime remains `DISABLED` by default.
- Execution is limited to disabled paths or Java-signed synthetic `SHADOW` fixtures.
- Real case data, real party traffic, real room shadow, `TEMPORAL` Intake allocation, canary, and
  production deployment are forbidden.
- A shadow command can write only Graph checkpoints and an isolated comparison record. Runtime
  dependency wiring cannot resolve or call the formal Intake Finalizer from `SHADOW`.
- Java remains the only formal business writer. Temporal code may be tested as a deterministic
  Workflow kernel, but it is not the formal Intake process writer under this exception.
- Python receives no Domain PostgreSQL credential and cannot create a formal message, dossier,
  matrix, completion, invitation, summons, room transition, deadline, audit record, or tool effect.
- Existing active Intake epochs remain `LEGACY`. Mode and version bindings never change inside an
  epoch.
- Historical `memory_frame` data remains readable for compatibility, but graph-backed engineering
  paths must not treat Java's copy as cognitive truth or write a new copy back.
- `MIG-003` and `MIG-004` remain `PENDING_PROMOTION`. Engineering evidence cannot satisfy external
  gates or authorize cutover.

The logical team is one primary plus five delegated implementation owners. Because the current
runtime permits four simultaneously active agents total, the five delegated roles execute in two
waves without changing their ownership or acceptance responsibility.

## Entry And Exit

The P4.0 contract candidate is tested from a clean detached worktree. Its Batch 0 evidence is
archived in a later commit that names the tested SHA. Implementation starts only after that
entry-evidence commit records `P4.0=PASS`.

The Phase 4 engineering checkpoint may report `PASS` while promotion remains `PENDING`, but only if
the accepted candidate still proves that formal Intake traffic and formal Finalizer wiring are
unreachable. Enabling real shadow or a `TEMPORAL` cohort requires a new accepted gate after
`MIG-003=PASS` and the required security, operations, rollback, SLO, and observation approvals.

## Consequences

The team may implement P4-S1 through P4-S5, additive V043 bindings, typed contracts, deterministic
Temporal tests, Graph PostgreSQL recovery tests, Java transaction tests, frontend compatibility,
and signed synthetic parity evidence. It may not claim `MIG-004=PASS`, remove the legacy writer, or
begin the `1% -> 5% -> 25% -> 100%` rollout under this ADR.
