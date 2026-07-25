# Phase 8 Alert Response

## Status And Boundaries

```text
artifact_scope: RENDER_ONLY_ENGINEERING
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
python_runtime_otel_setup: RELEASE_BLOCKER_UNRESOLVED
langfuse_metadata_and_payload_redaction: RELEASE_BLOCKER_UNRESOLVED
```

These dashboards, recording rules, alerts, and collector manifests are machine-lintable engineering
assets. They do not prove that a workload emits telemetry, that a collector is deployed, that a
policy is enforced, or that a production alert routes successfully. Never treat a missing series as
a healthy result. The I3 owner controls the service account, RBAC, network, and mTLS policies used by
the collector identity `part-of=after-sale-flow`, `name=otel-collector`, service account
`after-sale-otel-collector`, and OTLP ports `4317/4318`.

Two release blockers remain open and are not closed by these static assets:

1. Python runtime OpenTelemetry initialization and export are not implemented and tested.
2. Current Langfuse case/user/workflow metadata and raw prompt/output export redaction are not
   implemented and tested.

Both blockers require separately owned runtime changes, focused tests, and same-candidate external
security preflight receipts. Until then, production and promotion remain pending. Do not route real
traffic, credentials, real business records, prompt/output bodies, or hidden reasoning through this
render-only collector configuration.

## Common Triage

1. Record alert name, candidate SHA, deployment/configuration identity, environment, observation
   window, and attempt lineage without copying sensitive payloads into tickets or chat.
2. Confirm collector health and source completeness. Empty or absent telemetry is `UNKNOWN`, not
   recovery or success.
3. Compare aggregate dashboards with the authoritative Domain PostgreSQL and Temporal control-plane
   views through approved read-only procedures. Never edit internal Temporal or database tables.
4. Freeze promotion. Preserve failed observations and classify the failure as `PRODUCT`, `FIXTURE`,
   `INFRA`, or `EXTERNAL_GATE` before any retry.
5. Escalate to the named owner. A rollback must preserve the current formal writer, compatible
   payload readers, durable History, checkpoints, and additive V046 data.

## Alert Index

| Alert | Owner | Initial action |
| --- | --- | --- |
| `Phase8CommandSloFastBurn` | SRE | Freeze rollout and correlate aggregate command failure and latency. |
| `Phase8CommandSloSlowBurn` | Service Platform | Open an error-budget incident and bound the affected window. |
| `Phase8CommandOutboxStuck` | Java Platform | Inspect deliverable-state counts and relay health read-only. |
| `Phase8AgentActivityHeartbeatMissing` | Agent Platform | Stop new admission and inspect worker/Activity health. |
| `Phase8TemporalQueueBacklog` | Workflow Platform | Inspect queue capacity and schedule-to-start latency. |
| `Phase8TelemetryExporterFailure` | Observability Platform | Treat downstream dashboards as incomplete. |
| `Phase8TelemetryCollectorMissing` | Observability Platform | Validate collector replicas, scrape discovery, and I3 policy. |
| `Phase8RequiredTelemetryMissing` | Observability Platform | Mark affected dashboards and gates `UNKNOWN`. |
| `Phase8RequiredTelemetryStale` | Observability Platform | Verify source advancement and scrape freshness. |
| `Phase8UnauthorizedTrafficSpike` | Security | Freeze rollout and validate workload identity and mTLS. |
| `Phase8AuditExportFailure` | Security | Preserve local audit state and block release evidence. |
| `Phase8ProjectionReconciliationBacklog` | Java Platform | Compare authoritative high-watermarks and proposals. |
| `Phase8RecoveryPointObjectiveAtRisk` | Disaster Recovery | Block release and validate approved backup receipts. |
| `Phase8RecoveryValidationFailed` | Disaster Recovery | Stop the drill; preserve ordering and failure receipts. |

### Phase8CommandSloFastBurn

Freeze rollout and admission expansion. Check the Command and Outbox dashboard, then separate
transport rejection, invariant rejection, persistence failure, and downstream timeout using only
bounded `operation` and `outcome` dimensions. Page SRE and Java Platform. Roll back only to the
same-candidate compatible deployment; do not change writer authority.

### Phase8CommandSloSlowBurn

Open an error-budget incident with the complete six-hour and three-day windows. Compare throughput,
success ratio, and p95 latency against the approved SLO. The owner decides whether to reduce
admission or roll back; a static dashboard is not a release decision.

### Phase8CommandOutboxStuck

Pause new admission if backlog grows. Inspect aggregate deliverable-state counts, oldest age, relay
health, database pool saturation, and Temporal acceptance. Do not manually mark rows delivered,
rewrite lease state, or replay an effect without an idempotency receipt.

### Phase8AgentActivityHeartbeatMissing

Stop new model admission for the affected bounded worker class. Inspect worker health, task queue
schedule-to-start latency, model saturation, and last durable heartbeat. Do not terminate or retry
an Activity until Temporal confirms the authoritative state and retry contract.

### Phase8TemporalQueueBacklog

Confirm the bounded task queue class, worker availability, poller health, and schedule-to-start
latency. Protect the isolated control pool before scaling model work. Never move work between queues
or Worker Build IDs without the approved compatibility and rollback procedure.

### Phase8TelemetryExporterFailure

Assume all dependent telemetry is incomplete. Inspect collector receiver refusal, exporter failure,
memory limiting, and bounded queue metrics. Preserve local diagnostics without payload bodies.
Remote exporter endpoints and credentials are intentionally absent from the render-only manifest;
their production configuration and validation are external gates.

### Phase8TelemetryCollectorMissing

Check that at least two collector replicas are discoverable and that the Service uses the shared
labels and `4317/4318` ports. Ask I3 to verify the separately owned service account, RBAC,
NetworkPolicy, and mTLS objects. Do not weaken policy or mount credentials to make a scrape green.

### Phase8RequiredTelemetryMissing

Mark the affected dashboard, alert family, and release gate `UNKNOWN`. Use the bounded `service`
label on the alert to identify the missing source, then check workload readiness, scrape discovery,
collector receiver refusal, and policy. Do not substitute a zero, a cached dashboard value, or a
different environment's series.

### Phase8RequiredTelemetryStale

Treat a present but non-advancing source heartbeat as missing. Compare source timestamp progression
with scrape timestamps and collector acceptance, then freeze rollout until the source advances for
the full alert window. Restarting a collector does not by itself prove the workload source is fresh.

### Phase8UnauthorizedTrafficSpike

Freeze rollout. Security verifies workload identity, certificate validity, mTLS enforcement, and
bounded denial reason classes. Do not add identity values to labels or diagnostics. A denied request
must not be replayed under a broader identity.

### Phase8AuditExportFailure

Block release and preserve the local immutable audit chain. Security validates collector health,
object-store workload identity, ACL, versioning, and retention through approved read-only checks.
Never paste audit payloads or credentials into alert annotations.

### Phase8ProjectionReconciliationBacklog

Compare Domain high-watermarks, projection cursors, and immutable reconciliation proposals. A
proposal is not a formal mutation. Do not directly repair a projection or promote detector output;
use the approved idempotent reconciliation path after owner review.

### Phase8RecoveryPointObjectiveAtRisk

Block release and contact Disaster Recovery plus DBA owners. Validate same-candidate backup
freshness and recovery-point receipts. Do not start a real restore, PITR, or failover from this alert;
those operations require external authorization and an approved window.

### Phase8RecoveryValidationFailed

Stop the synthetic or authorized drill at the failed boundary. Preserve the failure receipt and
verify restore order: Domain, Temporal, Graph, object store, workers, then projections. Never edit
internal tables, blindly replay an external effect, or continue after an order violation.

## Resolution

Resolve an alert only after the source series is present, the owning system is stable for the
specified alert window, the authoritative state is reconciled, and the incident record contains no
sensitive payload. Resolution never changes `MIG-006`, `MIG-007`, `MIG-008`, the production
checkpoint, or the promotion gate. Those remain separate, authorized decisions.
