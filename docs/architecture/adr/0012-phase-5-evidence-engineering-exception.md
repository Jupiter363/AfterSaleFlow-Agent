# ADR 0012: Phase 5 Evidence Engineering Exception

- Status: ACCEPTED FOR DEVELOPMENT
- Date: 2026-07-22
- Scope: Phase 5 Evidence engineering and signed synthetic verification
- Approval: repository owner instruction to continue all phases automatically under the one-primary/five-owner delivery model

## Context

Phase 5 originally required `MIG-004=PASS`, the completed `GRAPH-016` tenant/global bulkhead
evidence, 100-file product/API/UI approval, and production asset authorization before P5.0 could
pass. That ordering contains a circular dependency: `P5-E1` is the Phase 5 task that implements and
proves the missing `GRAPH-016` controls, but `P5-E1` depends on P5.0 entry. The other three gates
also contain production or external-approval facets that cannot be satisfied by repository-only
engineering.

Treating pending external gates as passed would weaken the migration policy. Refusing to build any
disabled or synthetic-only component would prevent the repository from producing the evidence
needed to close those gates. Phase 4 ADR 0011 established the corresponding split between an
engineering lane and a promotion lane for Intake; Phase 5 needs the same fail-closed separation.

## Decision

After an accepted Phase 4 engineering candidate records both
`engineering_checkpoint: PASS` and `next_phase_permission: PHASE_5_ENGINEERING_ONLY`, the primary
may freeze a P5.0 contract candidate and run exact-SHA Batch 0. Phase 5 implementation may begin
only after a later entry-evidence commit records P5.0 `PASS` for that candidate.

This exception changes engineering entry only. It does not change promotion authority:

- `MIG-004` and `MIG-005` remain `PENDING_PROMOTION`.
- `GRAPH-016` is Phase 5 engineering exit evidence owned by `P5-E1`, not a P5.0 prerequisite. The
  Phase 5 engineering checkpoint cannot pass until room, tenant and global permits, bounded queues,
  fairness, cancellation and recovery are proven.
- The public Evidence submission contract remains capped at 50 until product, API and frontend
  owners separately approve 100 files. Engineering may exercise 1, 8 and 100 item manifests only
  through closed schemas, tests and disabled or Java-signed synthetic fixtures.
- Production asset authorization remains pending. Engineering asset access is limited to
  Java-signed synthetic capabilities bound to immutable fixture manifest, owner, visibility,
  object version and SHA-256. It cannot use party data or production object references.
- Java and Domain PostgreSQL remain the sole formal Evidence writers. Graph output is a proposal;
  no Graph path may merge, freeze, open Hearing or write a formal Evidence fact.
- Runtime defaults to `DISABLED`. The only executable Graph mode under this exception is isolated,
  Java-signed synthetic `SHADOW`; `LEGACY` remains the formal Evidence path.
- Real case shadow, real party traffic, a formal Finalizer sink, `TEMPORAL` Evidence allocation,
  canary, production deployment and promotion are forbidden.
- Temporal Workflow code may be implemented and tested as a deterministic kernel, but it cannot
  own a formal Evidence epoch under this exception.
- Existing active Evidence epochs remain `LEGACY`; writer mode and version pins never change in
  place. Hearing supplementation remains outside Phase 5 and capped at 50 files per party.

An upstream approval does not implicitly relax any restriction. Activating a public 100-file
contract, production asset loader, real shadow, formal writer, `TEMPORAL` allocation or canary
requires a separately frozen promotion candidate and its independent evidence.

## Entry And Exit

P5.0 retains the two-commit gate. The first commit freezes the contract pack, execution plan,
machine test policy and static gate tests. Batch 0 runs once from that clean detached SHA. A later
commit records commands, reports, hashes, durations and the exact candidate SHA. No implementation
owner starts before that evidence commit.

The Phase 5 engineering checkpoint may report `PASS` only when one accepted candidate proves all
Phase 5 engineering checks, including complete `GRAPH-016` evidence and fail-closed no-formal-sink
assembly. Its report must still state:

```text
engineering_checkpoint: PASS | FAIL
promotion_gate: PENDING | FAIL
next_phase_permission: PHASE_6_ENGINEERING_ONLY | BLOCKED
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
```

## Consequences

The team can build the Evidence Graph, bulkheads, reducers, deterministic Temporal kernel, Java
proposal boundary, additive persistence, compatible projections and signed synthetic reliability
evidence without claiming production readiness. External approval, production identity and object
authorization, real-data shadow, load/soak/failover/DR, canary observation and migration promotion
remain independently blocked.
