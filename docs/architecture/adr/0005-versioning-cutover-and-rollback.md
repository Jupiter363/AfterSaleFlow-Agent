# ADR 0005: Versioning, Cutover, and Rollback

- Status: ACCEPTED
- Date: 2026-07-17
- Decision owner: Architecture, Release, Java, Temporal, Python, Product
- Approved by: project owner through the 2026-07-17 plan approval

## Context

Long-running cases cannot safely read mutable deployment flags or silently switch Workflow, Graph,
checkpoint, prompt, model, schema, policy, stream, or tool behavior. A rollback that revives a
legacy writer against an active Temporal epoch would be more dangerous than the original failure.

## Decision

### Immutable execution selection

At room epoch creation Java persists:

```text
writer_mode = LEGACY | SHADOW | TEMPORAL
process_contract_version
workflow_type, build_id
graph_key, graph_version, checkpoint_schema_version
stream_protocol_version
prompt, model, schema, policy, guardrail, tool versions
fencing_token
```

Dynamic feature flags select only a new epoch. Workflow replay and active graph execution read the
recorded selection. Compatible readers are deployed before writers; destructive schema changes are
deferred until reference audits prove zero active use.

### Shadow and canary

Shadow uses identical authorized immutable inputs but writes only an isolated comparison ledger. It
cannot invoke a formal Finalizer, send a user-visible message, create a review task, or execute a
tool. Natural-language bytes are not a parity requirement; schema validity, privacy, guardrails,
formal fields, references, hashes, transitions, and terminal classifications are.

New room epochs progress through 1%, 5%, 25%, and 100% cohorts only after the prior cohort's hard
gates pass. Active legacy epochs stay on compatible legacy workers until completion. No automatic
mid-stage migration is permitted.

### Rollback

Stop new cohort allocation, drain or cancel in-flight noncommitted work, reconcile command,
operation, process revision, Graph lease, and stream cursor, then create a higher recovery epoch and
fencing token at a room-specific safe boundary. New fields and artifacts remain readable. Existing
external effects are never deleted or replayed to simulate rollback.

### D-03 Evidence batch limits

- Decision: ACCEPTED
- Accountable roles: Product, Evidence, Frontend
- Approval reference: project owner plan approval, 2026-07-17

The Evidence room contract expands from 1-50 to 1-100 files. Existing 1-50 behavior remains
compatible. Per-room model assessment concurrency remains 8, and merge occurs exactly once after
all authorized files are terminal. The Hearing supplemental evidence batch remains at 0-50 per
party with a 1,000-character note limit. This decision does not add video MIME support.

## Cleanup Gate

Legacy code, schema, worker, scheduler, and graph versions may be removed only after Temporal
visibility, Domain epoch/command/operation tables, AgentRun attempts, Graph thread/lease registry,
object manifests, and endpoint telemetry all show zero active references for the approved retention
window.

## Verification

Primary checks: `REL-001..010`, `TEMP-027..029`, `CONTRACT-009..010`, `MIG-004..008`, and
`GATE-005`. Every rollout and rollback report records the exact commit, images, versions, cohort,
queries, and reconciliation result.
