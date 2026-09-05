# ADR 0019: Evidence process projection for an activated model profile

- Status: ACCEPTED
- Date: 2026-09-06
- Scope: read-only Evidence process projection, not Workflow history or model effects

## Problem

The v1 target projection freezes `model_profile_id` to
`production-runtime.contract-blocked`. A real, activation-bound model profile therefore
caused the Evidence GET endpoint to reject an otherwise valid snapshot. An upload had
already returned 201 before this refresh failed, which also made the UI misreport the upload.

## Decision

- Preserve the frozen v1 schema and its blocked-profile validation unchanged.
- Emit `evidence-process-projection.v2` for TEMPORAL production projections. Its schema is
  `contracts/agent-platform/evidence/projection/v2/evidence-process-projection.schema.json`.
- V2 allows a bounded opaque model profile identifier, not a model-name prefix whitelist.
  The adapter must still verify the exact activation manifest, graph binding, workflow
  builds, domain binding and graph/checkpoint versions before producing this view.
- V2 is TEMPORAL/PRODUCTION only. Legacy and shadow projections retain v1. Consumers
  explicitly discriminate v1/v2 and reject unknown versions and incomplete authority.
- This is an additive read contract: no database migration, history rewrite, model rerun,
  or weakening of formal write ownership is introduced.
- After upload success, retain the returned receipt independently from subsequent reads.
  A refresh failure locks further projection-dependent writes and offers only a GET retry.

## Compatibility and verification

| Input | Reader outcome |
| --- | --- |
| v1 + blocked profile | Existing validation retained |
| v1 + live profile | Reject |
| v2 + exact activated live profile | Accept after activation validation |
| v2 + malformed profile / shadow writer / wrong graph binding | Reject |
| unknown version | Reject |

`EvidenceProcessProjectionAdapterTest` checks activation mismatch, version boundaries,
schema validity and identical-read hashes. `EvidenceRoomView.test.js` checks v1/v2
consumption, upload success followed by GET failure, GET-only retry, POST failure,
and actor-switch fencing. Browser E2E remains the release gate; unit tests alone do not
establish business readiness.
