# Temporal-first Retry Taxonomy and Budget

- Status: ACCEPTED
- Version: `retry-taxonomy.v1`
- Date: 2026-07-17
- Owners: Java, Temporal, Python, AI Platform, Tool Owners

## Governing Rule

Every command carries an absolute deadline and a remaining retry budget. Each layer consumes that
single budget; layers do not multiply independent retry loops. A deadline or budget exhausted result
is explicit and never converted into a successful model answer or formal domain fact.

Baseline limits are:

| Layer | Maximum | Applies to |
| --- | ---: | --- |
| Provider retry inside one Activity attempt | 2 retries after the first call | transient connect, timeout before response, 429, selected 5xx |
| Temporal infrastructure Activity attempt | 3 total attempts | worker/pod/network failure with idempotent operation |
| Schema repair | 1 repair for the same raw result | syntactically recoverable provider output |
| Agent Activity time | 10 minutes StartToClose | bounded by the command deadline |
| Agent heartbeat | at least every 5 seconds | 15-second HeartbeatTimeout baseline |

The implementation passes the remaining counts and absolute deadline from Workflow to Activity,
Graph command, and model profile. Circuit-breaker rejection before a provider call does not consume
a provider call, but it does consume elapsed deadline and is observable.

## Classification

| Class | Retry | Owner | Examples | Terminal handling |
| --- | --- | --- | --- | --- |
| `BUSINESS_REJECTED` | Never | Java | invalid phase, permission, unmet admission rule | stable domain error |
| `CONTRACT_INVALID` | Never | receiver | unknown version, extra dangerous field, bad enum/size | security/contract audit |
| `HASH_OR_ID_CONFLICT` | Never | receiver | same ID with different canonical hash | fail closed and page if formal |
| `STALE_REVISION_OR_FENCE` | Never | Java/Graph | old epoch, stage, revision, lease token | return current state; audit stale writer |
| `GUARDRAIL_REJECTED` | Never as infrastructure | Python/Java | unsupported claim, unauthorized reference/tool | needs-review or explicit failure |
| `SCHEMA_REPAIRABLE` | Once | LCEL parser | bounded malformed JSON with same raw response | parse repaired value or fail |
| `PROVIDER_TRANSIENT` | Bounded local | AI Platform | connect failure, 429, selected 5xx, pre-result timeout | circuit/queue then Activity failure |
| `PROVIDER_AMBIGUOUS` | No blind retry | AI Platform | response may have completed but receipt is unknown | reconcile provider/request ID or review |
| `JAVA_DB_TRANSIENT` | Activity retry | Java/Temporal | connection/failover before known commit | operation ledger determines commit |
| `JAVA_COMMIT_RESPONSE_LOST` | Read ledger only | Java/Temporal | commit succeeded, response lost | return existing operation result |
| `GRAPH_BEFORE_CHECKPOINT` | Activity retry under new fence | Python/Temporal | pod loss before durable node result | retry only if provider budget permits |
| `GRAPH_AFTER_CHECKPOINT` | Reconcile only | Python | checkpoint committed, ledger/response lost | return committed result without model |
| `STREAM_DELIVERY` | Replay, not model retry | Java/Frontend | Redis loss, SSE disconnect, slow consumer | resume durable cursor/reset attempt |
| `TOOL_TRANSIENT_SAFE` | Activity retry | Tool owner | adapter proves external idempotency and status query | use same external operation key |
| `TOOL_AMBIGUOUS` | Never blindly | Tool owner | timeout after possible side effect | query receipt, compensate, or manual recovery |
| `CANCELLED` | Never unless new command | Temporal | user/process cancellation or superseded epoch | propagate and reject late result |

## Commit Boundary Rules

1. A repeated `command_id` and equal RFC 8785 hash returns existing command status; another hash is
   `HASH_OR_ID_CONFLICT`.
2. Every Java side-effect Activity has a stable operation key. Retry first reads `domain_operation`.
3. Every graph command has `(thread_id, command_id)`, request hash, checkpoint, and result hash. A
   post-checkpoint retry reconciles rather than invoking the model.
4. Finalizer failure retries Finalizer only. A model run never compensates a Java transaction.
5. Public stream retry creates a new attempt and emits reset semantics after any visible partial
   output.
6. External tool retry requires the capability matrix defined by ADR 0006. Redis lock ownership is
   not proof that an external side effect did or did not happen.

## Temporal Mapping

Business, contract, authorization, hash, stale revision/fence, and deterministic guardrail errors
are non-retryable Application Failures. Infrastructure failures carry stable type, sanitized detail,
remaining budget, and next recovery action. Workflow code never catches an unknown exception and
pretends success. Queue congestion delays dispatch under the absolute deadline; it does not spawn
more provider calls.

## Verification

Tests cover every class, deadline exhaustion, provider/Temporal multiplication prevention, Java
commit-response loss, all Graph crash points, stream reset, and ambiguous tools. Evidence maps to
`TEMP-031..036`, `RUN-004..008`, `LCEL-007/012`, `HA-004/005/009`, and `DR-007`.
