# ADR 0003: Logical AgentRun, Attempts, and Stream V2

- Status: ACCEPTED
- Date: 2026-07-17
- Decision owner: Java, Temporal, Python, Frontend, AI Platform
- Approved by: project owner through the 2026-07-17 plan approval

## Context

The current retry path creates new AgentRun rows with `:attempt-N` suffixes, while the public
stream has no attempt/reset semantics. A transient provider or process failure can therefore replay
model work or concatenate partial text from different attempts.

## Decision

### Logical run and attempt

- One logical AgentRun represents one authorized model-producing command and may have many
  attempts, but at most one committed formal final result.
- `(case_id, logical_idempotency_key)` and `(run_id, attempt_no)` are unique.
- An attempt records provider/model, graph/checkpoint, prompt/schema/policy versions, token usage,
  latency, error taxonomy, heartbeat, and whether public output was emitted.
- Temporal Agent Activity is the only V2 executor. The legacy polling scheduler transitions from
  `EXECUTOR` to `DETECTOR` to `OFF` and never consumes the V2 queue.
- If Python completed but Java lost the response, the graph command ledger returns the same result.
  If Java finalization failed, only Finalizer is retried; the model is not called again.

### Stream protocol

`agent_stream.v2` contains `attempt_started`, `visible_delta`, `usage`, `attempt_aborted`,
`attempt_reset`, `final`, and `error`. Events use `(run_id, attempt_id, sequence_no)` and are durable
before live fan-out. Only `final` may invoke Finalizer. Public fields are operation/node allowlisted;
raw JSON, hidden reasoning, private matrices, tool arguments, and internal stack traces are denied.

After an attempt that emitted visible text fails, a new attempt publishes `attempt_reset`. Vue
removes only the prior attempt's temporary text and never concatenates attempts. Deltas are
coalesced at 50-100 ms or 1-4 KiB and written in batches. PostgreSQL replay is authoritative;
Redis only wakes live subscribers.

### D-06 Provider profile and budget policy

- Decision: ACCEPTED
- Accountable roles: AI Platform and Finance
- Approval reference: project owner plan approval, 2026-07-17

The initial approved profile is the current LiteLLM alias `qwen3.7-plus`, with provider thinking
disabled and `temperature=0`. Current node budgets remain explicit, with the general governed
profile capped at 32,000 input and 8,000 output tokens unless a versioned profile narrows them.
Model, token budget, response format, and fallback profile come only from the signed invocation
profile. Production request and token quota must retain at least 30% headroom under steady load.

A fallback is allowed only when its schema, policy, privacy, and capability contract is identical and
the remaining absolute deadline permits it. Provider-wide outage exclusion from the Agent SLI
requires an approved incident classification; it cannot hide platform failures. Actual quota and
cost evidence is required at the Phase 8 load gate.

## Verification

Primary checks: `RUN-001..009`, `STREAM-001..013`, `TEMP-033..036`, `LCEL-007..013`, and
`MIG-002`. Required failures include lost Activity completion, provider truncation, partial stream
retry, late old-attempt final, Redis outage, slow consumer, and duplicate stream persistence.
