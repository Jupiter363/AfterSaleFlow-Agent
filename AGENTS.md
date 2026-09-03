# Repository agent instructions

## Production baseline

- Treat `main` as the releasable production baseline.
- Repair the responsible mechanism and its end-to-end invariants. Never use case-specific IDs,
  payload guesses, magic prefixes, or environment-only bypasses as a general fix.
- A protocol-specific branch requires an explicit authoritative discriminator and positive,
  negative, replay/idempotency, and adjacent-regression coverage.
- Keep Java/PostgreSQL as formal domain authority, Temporal as durable process authority, and
  LangGraph as bounded cognitive authority. Model output cannot become a formal decision or effect.

## Change safety

- Preserve unrelated working-tree changes and do not use destructive Git commands to discard them.
- Do not upgrade or restart Temporal, databases, queues, model gateways, or other core components
  without explicit user authorization.
- Migrations are append-only. Retain replay fixtures, versioned schemas, protocol contracts, and
  compatibility code unless a separate proof shows no persisted execution or data can require them.
- Keep documentation in `docs/`; root `README.md` and agent instruction files are intentional
  repository-entry exceptions. Runtime Markdown prompts remain colocated with their source.

## Development workflow

- Prefer focused checks while editing. Run full suites, all-service Compose, or browser E2E only
  when the user asks or at a unified release checkpoint.
- Local development ports are frontend `5173`, Java `8080`, and Python `18000`; Docker Compose is
  the final all-service deployment target.
- Before handing off a bug fix, state the root mechanism, violated invariant, generalized repair,
  and exact regression tests.

## Delegation

- Give every delegated task exact owned paths, forbidden paths, acceptance criteria, and a required
  handoff format. Never assign concurrent writers to the same file.
- Delegation does not expand authorization for destructive operations, secrets, external systems,
  production mutations, release actions, or component upgrades.
- The primary agent owns integration, final verification, commits, pushes, and user-facing results.
