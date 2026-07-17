# ADR 0006: Production Topology, Security, Tools, and Disaster Recovery

- Status: ACCEPTED
- Date: 2026-07-17
- Decision owner: Platform, SRE, Security, DBA, Tool Owners, Legal
- Approved by: project owner through the 2026-07-17 plan approval

## Context

The target must support 1,000 active rooms, 250 burst AgentRuns, 100 sustained and 200 burst model
calls, and 2,500 SSE clients without making Redis, a single JVM, a static service secret, or an
unqueryable external effect part of correctness.

### D-01 Temporal production service and regional capability

- Decision: ACCEPTED
- Accountable roles: Platform and SRE
- Approval reference: project owner plan approval, 2026-07-17

Use Temporal Cloud as the production default, with a multi-AZ namespace and a documented regional
DR capability that preserves zero acknowledged Workflow events. Self-hosting is prohibited unless
an equivalent supported topology, upgrade/replay practice, on-call staffing, and failure evidence
is separately approved. Namespace retention, Search Attributes, codec, and Worker Versioning are
managed as versioned infrastructure.

### D-04 Immutable snapshot storage and legal retention

- Decision: ACCEPTED
- Accountable roles: Security, Legal, SRE
- Approval reference: project owner plan approval, 2026-07-17

Large inputs and outputs use versioned S3/MinIO objects addressed by SHA-256 and an immutable Java
authorization manifest. Separate evidence, graph-input, graph-output, and audit prefixes/buckets use
KMS encryption, least-privilege service roles, versioning, audit logging, lifecycle rules, and legal
hold support. Temporal stores only authorized references, hashes, versions, and bounded metadata.

### D-05 Service authentication, nonce, and payload encryption

- Decision: ACCEPTED
- Accountable role: Security
- Approval reference: project owner plan approval, 2026-07-17

Java-Python traffic uses service-mesh mTLS plus a Java-signed ES256 invocation envelope with a
maximum 60-second lifetime, audience, issuer, tenant surrogate, case, room epoch, actor scope,
command ID, nonce, capabilities, profile versions, and payload hash. Python verifies signature,
expiry, audience, bindings, and a durable nonce/replay record. Keys rotate through KMS; overlapping
verification keys support in-flight work. Sensitive Temporal payload references use an encrypted
codec with versioned key IDs and tested old-key reads.

### D-07 Tool idempotency, status query, and compensation

- Decision: ACCEPTED
- Accountable roles: Tool Owners and Business
- Approval reference: project owner plan approval, 2026-07-17

The repository currently provides simulated execution tools and Java ActionRecord/idempotency
guards, not proven production refund or fulfillment adapters. A real tool cannot be automatically
retried until its capability record documents an external idempotency key, status-query behavior,
timeout semantics, receipt schema, and compensation or explicit human-recovery path. Missing any
capability makes an ambiguous result non-retryable and routes it to manual recovery. Redis locks are
contention optimization only; the Java operation ledger and external key are authoritative.

### D-08 Deployment and infrastructure as code

- Decision: ACCEPTED
- Accountable role: SRE
- Approval reference: project owner plan approval, 2026-07-17

Production uses Kubernetes and declarative infrastructure as code across three failure domains.
Java API, Temporal control workers, Temporal Agent workers, Python Agent, LiteLLM, and OTel are
separate deployments, identities, pools, HPA policies, PDBs, and topology-spread groups. Docker
Compose remains the local and CI integration topology, not the production HA claim.

### D-09 Data retention and deletion

- Decision: ACCEPTED
- Accountable roles: Legal, Security, DBA
- Approval reference: project owner plan approval, 2026-07-17

Until jurisdiction-specific policy replaces these minimums: hot Agent stream chunks are retained at
least 24 hours; terminal stream events and execution manifests are retained with the case audit
record; command/operation/attempt records remain through the case retention and appeal window;
Graph checkpoints are retained while any active thread or audit manifest references them; evidence
and snapshots follow legal hold and case deletion policy. Partition deletion requires successful
compaction/archive, reference checks, and an auditable deletion job. Final durations are deployment
configuration approved by Legal and never hard-coded into Workflow logic.

## Initial Topology

| Deployment | Minimum replicas | Initial request | Primary scaling signal |
| --- | ---: | --- | --- |
| Java API | 3 | 2 vCPU / 4 GiB | request latency and SSE connections |
| Java Temporal control worker | 3 | 2 vCPU / 4 GiB | control task queue latency |
| Java Agent worker | 3 | 4 vCPU / 8 GiB | in-flight Activity and heartbeat delay |
| Python Agent | 4 | 4 vCPU / 8 GiB | graph queue, in-flight graphs, memory |
| LiteLLM | 3 | 2 vCPU / 4 GiB | provider latency and open connections |
| OTel Collector | 2 | 2 vCPU / 4 GiB | export queue and dropped telemetry |

Task queues are `case-control`, `room-control`, `agent-execution`, and
`notification-and-tools`. Domain, Graph, and Temporal PostgreSQL use separate databases or at least
schemas, roles, pools, migrations, backups, and resource limits. PgBouncer protects primary pools;
reporting and large lists use read replicas.

## Disaster Recovery

Recovery order is Domain PostgreSQL, Temporal, Graph PostgreSQL, object store, Java/Python workers,
then projection reconciliation. In-region committed Domain transactions and acknowledged Temporal
events have RPO 0. Multi-AZ recovery is under 5 minutes. Regional recovery targets RPO 5 minutes and
RTO 30 minutes for Domain/Graph/Object data while Temporal still preserves acknowledged-event RPO
0. Restore tests prove that completed external actions are not executed again.

## Verification

Primary checks: `ENV-001..017`, `HA-001..016`, `SEC-001..016`, `DR-001..009`,
`PERF-001..019`, and `GATE-001..010`. Operational claims require deployment manifests, load data,
chaos timelines, key-rotation output, PITR/restore evidence, and named on-call sign-off.
