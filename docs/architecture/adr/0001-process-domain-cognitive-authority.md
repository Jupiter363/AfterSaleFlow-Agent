# ADR 0001: Process, Domain, and Cognitive Authority

- Status: ACCEPTED
- Date: 2026-07-17
- Decision owner: Architecture, Java, Temporal, Python, Security
- Approved by: project owner through the 2026-07-17 plan approval
- Supersedes: no current authoritative ADR

## Current implementation note (2026-09-04)

The authority split in this ADR remains current. The implementation has progressed beyond the
one-shot state described in the historical context: the target lane now runs the durable
Temporal-first room lifecycle, while Java/PostgreSQL remains the formal domain authority and
Python/LangGraph remains proposal-only cognitive authority. The current identities, Intake V4
parallel topology and latest browser result are recorded in
[the current UAT baseline](../../release/current-uat-baseline.md).

## Context

The current application has strong Java domain ledgers, but process advancement is split between
application services, schedulers, workers, and one Evidence Temporal Workflow. Intake and Evidence
use one-shot LangGraph calls without checkpoints, while Hearing uses seven independent Python
operations. A safe migration needs one writer for each state category and an explicit meaning for
"truth" at every boundary.

## Decision

1. Temporal Event History is the sole process authority for case phase, room phase, waits, timers,
   cancellation, retry scheduling, compensation scheduling, and child completion.
2. Java and Domain PostgreSQL are the sole formal domain ledger for identity, authorization,
   messages, evidence, submissions, artifacts, review decisions, ActionRecords, external receipts,
   audit facts, and query projections.
3. A Java Activity may append a formal fact only after applying domain invariants. Temporal decides
   when that Activity is requested; the Activity result or a sequenced domain event lets the
   Workflow advance. Temporal does not bypass Java invariants.
4. Python and Graph PostgreSQL are the sole bounded cognitive authority for graph checkpoints,
   cognitive revisions, node results, memory summaries, and pending fan-out work. A graph result is
   always a proposal until a Java Finalizer commits it.
5. LangChain Core and LCEL own Prompt, Message, ChatModel, Parser, callback, and model-stream object
   flow. They do not own domain permission, process transitions, or tool approval.
6. Vue owns presentation state only. It reads Java-authorized projections and streams and never
   infers a formal transition from model text or local timers.

## Writer Matrix

| State | Sole writer | Durable store | Fencing |
| --- | --- | --- | --- |
| Case and room process | Temporal Workflow | Temporal History | workflow/run/build, process revision, room epoch |
| Process query projection | Java Projection Activity acting for Temporal | Domain PostgreSQL | revision CAS plus epoch/fencing token |
| Formal domain fact | Java domain transaction | Domain PostgreSQL | aggregate constraints, command ID, operation key |
| Cognitive state | Versioned LangGraph runtime | Graph PostgreSQL | thread, command hash, checkpoint revision, lease token |
| Model object flow | Governed LCEL runtime | execution trace/manifest references | prompt/model/schema/policy versions |
| UI transient view | Vue store | browser memory | actor generation and stream cursor |

No shared state may have two automatic writers in one room epoch. Redis, SSE connections,
Elasticsearch, caches, and observability backends are never correctness authorities.

### D-02 Tenant authority and surrogate

- Decision: ACCEPTED
- Accountable roles: Security and Java
- Approval reference: project owner plan approval, 2026-07-17

Java authentication is the only issuer of a stable, non-PII `tenant_surrogate`. The surrogate is an
opaque identifier with a maximum of 128 characters and is bound to case participation, actor,
role, room epoch, and capability at command acceptance. Browsers, Temporal payload callers, and
Python graph state cannot assert or replace tenant authority. Python validates the signed binding
but never queries or writes the Domain database to reconstruct it.

## Consequences

- Existing Java stage columns become fenced projections after their room epoch cuts over.
- Existing append-only hearing action and artifact tables remain formal domain ledgers.
- Intake and Evidence memory is initialized once into a versioned graph state; Java does not keep a
  competing cognitive write-back loop.
- `DRAFT` and `OUTCOME` remain projections rather than `RoomType` values.
- Architecture tests must reject direct Java phase advancement, Python Domain DB credentials, and
  Workflow dependencies on repositories, HTTP clients, system clocks, or randomness.

## Verification

Primary checks: `ARCH-001..011`, `JAVA-004..012`, `GRAPH-007..013`, `SEC-001..006`, and
`MIG-000`. Evidence remains `TODO` until implementation tests run.
