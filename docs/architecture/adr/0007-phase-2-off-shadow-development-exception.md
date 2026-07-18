# ADR 0007: Phase 2 OFF/SHADOW Development Exception

- Status: ACCEPTED FOR DEVELOPMENT
- Date: 2026-07-19
- Scope: Phase 2 AgentRun V2 implementation on `codex/temporal-langgraph-room-refactor`
- Approval: repository owner instruction in the active development task

## Context

Phase 1 has passed its engineering checkpoint, but `MIG-001` promotion remains pending because the
production KMS, private ACL, immutable retention, real Temporal History/SQL evidence, and release
signatures are external gates. Phase 2 implementation can proceed without weakening those gates when
all new runtime paths remain disabled or SHADOW-only.

## Decision

Phase 2 engineering implementation is approved under these mandatory restrictions:

- All V2 runtime selectors and executors default to `OFF`; synthetic verification may use `SHADOW`.
- No production deployment, formal room writer cutover, user-visible V2 default, or migration cleanup.
- V1 readers and in-flight V1 runs remain supported; migrations are expand/backfill only.
- Exactly one V2 executor is permitted for a logical queue; legacy execution is `DETECTOR` or `OFF`.
- Promotion remains `PENDING` until `MIG-001` and the Phase 2 promotion evidence are independently approved.
- Any change that enables `TEMPORAL` as a production default requires a separate approval and evidence gate.

## Consequences

The Phase 2 implementation worktrees may be created from the recorded P2.0 base commit. Engineering
tests may run according to the Phase 2 batch matrix, but passing tests do not authorize production
cutover. Violating any restriction immediately returns `engineering_execution` to `BLOCKED`.
