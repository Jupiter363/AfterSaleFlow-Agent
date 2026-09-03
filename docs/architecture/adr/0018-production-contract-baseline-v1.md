# ADR 0018: Production Contract Baseline v1

- Status: ACCEPTED
- Date: 2026-09-04
- Scope: current `main` production contract catalog

## Context

The production repository now has one UAT-verified code baseline, while its durable protocols have
independently evolved to v1, v2, v3 and v4. Treating every suffix as a product release version is
confusing. Renumbering those suffixes would be unsafe because they are persisted discriminators in
Temporal History, Domain and Graph databases, stream cursors, signed hashes and replay fixtures.
Removing versions would make old and new payloads indistinguishable.

## Decision

1. The current production combination is named `production-contract-baseline.v1`.
2. Its machine-readable catalog is
   `contracts/catalog/production-baseline.v1.json`.
3. The baseline version is a release-catalog version only. It does not replace or alias a wire
   contract's own version.
4. Existing wire/schema versions remain immutable. No v2/v3/v4 identifier is rewritten to v1 and
   no version discriminator is removed.
5. A future incompatible production combination creates a new baseline catalog. Existing catalogs,
   migrations, compatibility readers and replay fixtures remain available.

## Consequences

- Operators and documentation can refer to one production Contract Baseline v1.
- Services still fail closed against the exact protocol versions they consume.
- Temporal replay and persisted data remain readable without a mass migration.
- A baseline bump and a wire-contract bump are separate decisions and may occur independently.

The human-readable catalog and change rules are in
[Production Contract Baseline v1](../../contracts/README.md).
