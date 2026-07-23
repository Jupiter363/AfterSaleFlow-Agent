# ADR 0015: Phase 6 Hearing Engineering Exception

- Status: ACCEPTED FOR ENGINEERING ONLY
- Date: 2026-07-24
- Scope: Phase 6 Hearing contract, disabled implementation, and Java-signed synthetic verification
- Approval: accepted Phase 5 engineering checkpoint plus repository-owner direction to prepare P6.0

## Context

The master plan makes `MIG-005=PASS` the normal Phase 6 entry condition. The accepted Phase 5
checkpoint instead records `engineering_checkpoint: PASS`, `promotion_gate: PENDING`, and
`next_phase_permission: PHASE_6_ENGINEERING_ONLY`; it also leaves `MIG-004` and `MIG-005` pending
promotion. This is deliberate: production and external-approval evidence cannot be claimed from
repository-only work, while the disabled Hearing workflow, graph, protocol, and recovery controls
must exist before their production-shaped evidence can be collected.

Treating the checkpoint as promotion would violate the migration gates. Refusing to prepare any
disabled or synthetic-only Hearing component would recreate the circular dependency resolved for
Intake and Evidence by ADRs 0011 and 0012. This ADR therefore separates an engineering lane from a
promotion lane without changing the latter.

## Decision

Phase 6 engineering may start only after a separate P6.0 entry-evidence commit records Batch 0
`PASS` for the exact contract-candidate SHA. Until then, including at this ADR's candidate commit,
product implementation is blocked.

The engineering lane is constrained as follows:

- The formal selector remains Java `LEGACY`. Its business state is the fixed 15-stage
  `hearing_flow.v2`; it is not the removed generic three-round fallback.
- Runtime is limited to `DISABLED` or isolated Java-signed synthetic `SHADOW`. Real case data,
  party data, real shadow, production traffic, and production object references are forbidden.
- Java and Domain PostgreSQL remain the only formal business truth and writers for stages, party
  actions, matrices, dossiers, artifacts, messages, review handoff, and audit facts.
- Temporal engineering owns the future process order, waits, absolute deadlines, retry/failure
  timing, and handoff order. Under this exception it is a deterministic test kernel only: it may
  not receive a formal Hearing allocation or advance a formal epoch.
- Graph owns private cognition checkpoints and bounded proposals only. It cannot become a formal
  sink, mutate Domain facts, publish party actions, freeze a dossier, create a draft or review task,
  advance a stage, or perform handoff.
- LCEL owns typed model invocation and closed request/result parsing only. It has no business,
  timing, writer, or tool-effect authority.
- V037 compatibility readers continue to accept historical `hearing_answer_bundle.v1` and
  identity-complete `hearing_party_statement.v1`. New Phase 6 canonical party emissions must carry
  stable `participant_id`; historical rows are not rewritten and role is only an audit/display
  snapshot.
- Writer mode, epoch, revision, fence, workflow build, graph, prompt, model, policy, guardrail, and
  schema pins are immutable for an active flow. There is no mid-flight Java-to-Temporal or
  Temporal-to-Java writer transfer.

The exact stage order, two shared party deadlines, seven cognitive operations, message provenance,
party privacy, matrix limits, dossier freeze, jury requirement, and V1/Jury/V2 ID/hash parent chain
are frozen by `contracts/agent-platform/hearing/v2/compatibility-matrix.yaml`. Hearing self-hashes
use the pinned implementation's sorted-key compact UTF-8 JSON preimage with exactly the named
top-level hash field omitted. This is not an implicit RFC 8785 grant.

## Gate Record

```yaml
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_6_ENGINEERING_ONLY
p6_0_entry_gate: AWAITING_EXACT_SHA_BATCH_0
product_implementation: BLOCKED
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
formal_hearing_selector: LEGACY
allowed_runtime_modes: [DISABLED, JAVA_SIGNED_SYNTHETIC_SHADOW]
```

No upstream approval implicitly changes this record. A contradiction between this record and the
accepted Phase 5 checkpoint fails closed and blocks Phase 6 entry.

## Promotion Lane

Promotion remains subject to the master plan. This ADR does not authorize `MIG-004`, `MIG-005`, or
`MIG-006` to pass; a formal Graph sink; `TEMPORAL` Hearing allocation; V044 implementation; real or
party-data shadow; canary; production deployment; or promotion. Each needs its separately frozen
candidate, exact-SHA evidence, and required security, operations, privacy, replay, rollback, SLO,
and observation approvals.

## Replay, Failure, And Rollback

Workflow code must preserve deterministic replay through explicit version markers and compatible
workers. Captured histories pin workflow and activity names plus payload schemas; incompatible
changes require a new workflow build/version and may not reinterpret an active history. Temporal
persists only process state and immutable refs/hashes/revisions, never live matrices, dossiers,
prompts, token deltas, or private reasoning.

At an equal party-deadline timestamp, deterministic Workflow event order arbitrates Signal versus
Timer. A Java terminal action that committed first carries the authoritative monotonic
`case_event_sequence`; duplicate or late delivery returns the persisted terminal state. Provider or
worker failure cannot fabricate an output or skip jury review. A stage opens only after its
predecessor's durable output commits, and every external effect is idempotent and fenced.

Rollback freezes new commands, drains or cancels in-flight effects, reconciles Domain and process
revisions, and stops new Temporal epochs. An active flow is never handed back in place. A recovery
writer may start only before the first stage or at a formally approved safe boundary, with a higher
epoch/fence and an explicit recovery record. Compatible workers remain available until all pinned
histories finish.

## Consequences

After the P6.0 exact-SHA entry gate passes, the team may implement and test the disabled Hearing
workflow, isolated graphs, typed LCEL protocol, additive persistence design, replay fixtures, and
Java-signed synthetic parity. It may not make any of those paths formal or begin rollout under this
ADR.
