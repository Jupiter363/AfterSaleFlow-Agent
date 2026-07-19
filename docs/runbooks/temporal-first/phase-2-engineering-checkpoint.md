# Phase 2 AgentRun V2 Engineering Checkpoint

- Candidate: `7b93cccd7052cac8af7f98be8ec02a9897a3e673`
- Evidence: `test-reports/temporal-first/phase-2-20260719/phase-2/`
- Runtime scope: `OFF/SHADOW` only

```text
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_3_ENGINEERING_ONLY
```

The final candidate ran one deduplicated focused suite: 160 Java, 35 Python, 35 frontend and 10
static tests. All 240 tests passed with no failure, error or skip. Batch 1-3 XML views are derived
from that same execution and candidate SHA.

Phase 2 delivered logical runs and attempts, stable Temporal workflow identity, Update-based later
attempts, independent Finalizer retries, durable PostgreSQL stream batches, Redis wake-up fallback,
V1/V2 readers, reset/reconnect UI behavior, and fail-closed selector assembly.

The checkpoint does not claim production promotion. The Python command ledger and signed graph
transport belong to Phase 3; stream partition/archive deletion belongs to Phase 8; real API pod and
Worker kill/takeover, full security role matrix, long-content UI baseline, and production approvals
remain open. Exact status and evidence are recorded in `check-id-coverage.json`.

ADR 0008 permits Phase 3 engineering to continue with graph execution disabled or shadow-only. It
does not satisfy `MIG-002`, authorize a room graph writer, or permit production deployment.
