# ADR 0018: Production Contract Baseline v1

- Status: ACCEPTED
- Date: 2026-09-04
- Scope: current `main` production contract catalog

## Context

The production repository has one behaviorally UAT-verified baseline, while its protocols have
independently evolved to v1, v2, v3 and v4. Treating every suffix as a product release version is
confusing. Renumbering those suffixes would erase real semantic distinctions between the current
schemas and make future replay ambiguous. This is the first production release, so preproduction
`target-e2e` persistence can be retired instead of supported indefinitely.

## Decision

1. The current production combination is named `production-contract-baseline.v1`.
2. Its machine-readable catalog is
   `contracts/catalog/production-baseline.v1.json`.
3. The baseline version is a release-catalog version only. It does not replace or alias a wire
   contract's own version.
4. Current wire/schema identifiers become immutable at this production baseline. No v2/v3/v4
   identifier is rewritten to v1 and no version discriminator is removed.
5. The first production deployment is a clean break: it uses fresh Domain/Graph databases and a
   fresh Temporal namespace, and does not accept preproduction `target-e2e` identities or History.
6. A future incompatible production combination creates a new baseline catalog. Production
   catalogs, migrations, compatibility readers and replay fixtures remain available.

## Consequences

- Operators and documentation can refer to one production Contract Baseline v1.
- Services still fail closed against the exact protocol versions they consume.
- Replay is guaranteed for data created from this production baseline onward; preproduction state
  is intentionally excluded.
- A baseline bump and a wire-contract bump are separate decisions and may occur independently.

The human-readable catalog and change rules are in
[Production Contract Baseline v1](../../contracts/README.md).
