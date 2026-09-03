# Temporal-first Service Levels and Capacity Contract

- Status: CURRENT PRODUCTION CAPACITY CONTRACT
- Updated: 2026-09-04
- Owners: Architecture, SRE, Java, Temporal, Python, AI Platform
- Approval: project owner plan approval, 2026-07-17

## Measurement Rules

SLIs are computed from server-side durable boundaries, not browser perception alone. Each report is
tagged with immutable release ID, commit, deployment, environment, service, Workflow/Graph build,
and model profile. Case, actor, command, and run identifiers belong in controlled traces/logs, not
Prometheus labels. Business denials and client errors are excluded only by a versioned reason-code
allowlist. Missing telemetry fails the gate; it is not interpreted as success.

## Objectives

| SLI | Objective | Start and stop boundary |
| --- | --- | --- |
| Java command/query availability | 99.95% monthly | valid authorized request to durable response |
| Temporal control availability | 99.95% monthly | durable command commit to Workflow accept or explicit bounded pending state |
| Agent execution availability | 99.9% monthly | authorized logical run to valid result, needs-input/review, or classified terminal failure |
| Durable command acceptance | p95 < 300 ms | Java request entry to Domain PostgreSQL commit |
| Temporal dispatch | p95 < 1 second | Domain commit to Workflow Update acceptance |
| SSE reconnect and replay | p95 < 2 seconds | valid cursor reconnect to durable high-watermark catch-up |
| Model first token | profile-specific | Agent queue exit to first allowlisted provider delta |
| Domain in-region RPO | 0 committed transactions | acknowledged PostgreSQL commit |
| Temporal in-region/approved failover RPO | 0 acknowledged events | acknowledged Update, Signal, or Timer event |
| Multi-AZ recovery | < 5 minutes | fault detection to healthy service SLI |
| Regional DR | RPO 5 minutes / RTO 30 minutes | declared region loss to reconciled service, excluding Temporal's stricter event RPO |

Model queue delay, provider first-token latency, provider completion latency, parsing, guardrail, and
Finalizer latency are separate histograms. Provider-wide outage may be excluded from Agent
availability only after incident classification proves the platform accepted, queued, retried, and
reported commands correctly; platform, quota-planning, or configuration failures are not excluded.

## Initial Workload Envelope

| Workload | Steady | Burst / shape |
| --- | ---: | --- |
| Active room Workflows | 1,000 | at least 70% waiting or on Timer |
| Room commands | 20/s | 50/s for 30 seconds |
| Agent-triggering commands | 5/s | 20/s for 30 seconds |
| Logical AgentRuns | measured arrival | 250 concurrent burst |
| Model calls | 100 concurrent | 200 concurrent for 30 seconds |
| SSE clients | 2,500 | USER, MERCHANT, REVIEWER audiences |
| Evidence batch | 100 files | at most 8 active assessments per room |

Model slots follow Little's Law: `required concurrency = arrival rate * mean service time`. At 5
Agent commands/s and 20 seconds mean service time, 100 slots are fully occupied. Provider request
and token quotas keep at least 30% steady-state headroom. If measured service time or fan-out makes
that impossible, admission control queues work or the approved quota/profile changes; HPA does not
pretend to create provider capacity.

## Backpressure and Error Budget

Concurrency is limited by global model/profile, tenant, room, and node fan-out bulkheads. Excess
work enters a durable bounded queue with visible processing state. When Agent queue age breaches its
SLO, noncritical background Agents stop accepting new work while deadline, cancellation, review,
and already accepted commands continue.

Canary expansion stops on any hard correctness failure, command acceptance or dispatch SLO breach
for 10 consecutive minutes, oldest outbox age above 60 seconds, control queue p95 above 1 second,
database pool usage at or above 80% for 10 minutes, provider headroom below 30%, or a burst queue
that does not return to its approved bound within 30 minutes. Multi-window burn-rate alerts page on
fast exhaustion and ticket on slow exhaustion; exact windows are stored with the monitoring rule
and release evidence.

## Required Dashboards and Runbooks

Dashboards cover command/outbox, Temporal queue/history/replay, AgentRun/stream, Graph checkpoint
and lease, model/provider, projection/reconciliation, security, and DR. Alerts link to runbooks under
`docs/runbooks/temporal-first/` for stuck commands, Workflow replay, Agent heartbeat, Graph lease,
provider outage, database failover, projection drift, tool ambiguity/compensation, key rotation, and
regional DR. Production release requires a no-internal-table-edit recovery exercise and a 24-hour soak.
