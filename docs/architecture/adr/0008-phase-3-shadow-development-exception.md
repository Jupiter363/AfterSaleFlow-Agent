# ADR 0008: Phase 3 Disabled/Shadow Development Exception

- Status: ACCEPTED FOR DEVELOPMENT
- Date: 2026-07-19
- Scope: Phase 3 Python Graph and governed LCEL implementation
- Approval: repository owner instruction to continue automatically across engineering phases

## Context

Phase 2 passed its OFF/SHADOW engineering checkpoint, while `MIG-002` promotion and several real
environment exercises remain pending. Phase 3 is required to implement the Python command ledger,
checkpoint ownership, graph fencing and governed Runnable protocol that close those declared gaps.
Waiting for production promotion before building those disabled components would create a circular
dependency.

## Decision

Phase 3 engineering may proceed under all of these restrictions:

- The graph gateway and every graph registry selection default to disabled.
- Synthetic verification may use shadow commands only; no shadow result becomes a formal result.
- No production deployment, room writer cutover, room migration or legacy endpoint removal.
- Graph checkpoints use an isolated development database/schema and role. Python never receives
  Domain database write credentials, and Java never receives Graph checkpoint write credentials.
- Contract v1 remains compatible and Java remains the only formal business writer.
- Missing checkpoint, ledger, lease, signature, nonce or registry dependencies fail closed.
- Promotion remains pending until `MIG-001`, `MIG-002`, `MIG-003` and their independent evidence and
  approvals pass.

Any selector that enables a formal graph writer, any production credential, or any room migration
requires a separate approved gate. Passing Phase 3 component tests does not authorize cutover.

## Consequences

The team may implement P3.1-P3.8, run local PostgreSQL and component recovery tests, and generate a
Phase 3 engineering evidence bundle. Later room phases remain blocked from formal graph writer
migration until `MIG-003=PASS`.
