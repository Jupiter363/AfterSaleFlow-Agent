# Final module ownership map

## Dependency direction

```text
Frontend
  → Java API / Temporal worker
      → Python Agent Service
      → OCR Parser
      → PostgreSQL / Redis / MinIO / Elasticsearch
```

Java is the 业务事实 source. Temporal controls durable state, waiting, retries, and recovery. The Python service supplies bounded cognition through the Agent Runtime Harness. Platform Human Review owns the final decision, and Tool Executor alone performs approved deterministic actions.

## Ownership and forbidden dependencies

| Module | Owns | 禁止依赖 / behavior |
|---|---|---|
| `casecore` | `FulfillmentDisputeCase` and state legality | Agent SDKs, execution adapters |
| `intake` | submissions and intake commands | final liability or remedies |
| `evidence` | evidence metadata and dossier versions | replacing originals with summaries |
| `routing` | three final hearing routes | approval or execution |
| `hearing` | C1-C6 stage records and non-final drafts | final decisions |
| `deliberation` | frozen critic inputs and reports | suppressing major minority objections |
| `remedy` | deterministic action planning | re-deciding facts |
| `review` | immutable packets and human records | autonomous approval |
| `execution` | approved, hashed, idempotent actions | model-authored free-form commands |
| `evaluation` | offline quality records | mutating closed cases |
| `workflow` | deterministic orchestration | open-ended model reasoning |
| `platform` | adapters, security, audit, configuration | business adjudication |

## Agent service boundaries

`harness` owns authority, context, memory, tools, skills, loop budgets, validation, guardrails, interrupts, hooks, and observability. `agents` contains role behavior only. `api` exposes internal service-authenticated contracts. `schemas` and `skills` are versioned inputs to the Harness.

## Frontend boundaries

The UI is organized around disputes, evidence, hearing, deliberation, and review—not order operations. The Generative UI renderer uses component and action allowlists; it cannot emit arbitrary HTML, URLs, API calls, approval actions, or execution actions.
