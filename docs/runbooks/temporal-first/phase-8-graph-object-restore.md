# Phase 8 Graph And Object Restore

```text
artifact_scope: FIXTURE_VALIDATION_AND_EXTERNAL_PROCEDURE_ONLY
engineering_checkpoint: NOT_RUN
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
real_graph_or_object_restore: FORBIDDEN_FROM_ENGINEERING_TOOLING
```

## Boundary

This runbook describes the externally executed Graph PostgreSQL and object-store recovery stages.
`tools/operations/recovery/graph_object_restore.py` is an in-memory validator and cannot contact
PostgreSQL or object storage, load credentials, mutate files, or execute restore operations.

The exact order is Domain, Temporal, Graph, object store, workers, and projections. Domain and
Temporal must be verified in the same context before Graph. Graph must be verified before object
restoration. Workers and projections remain stopped throughout this checkpoint.

## External Preconditions

- Independent DBA or SRE authorization binds one candidate, configuration, deployment,
  environment, operator, scenario, and attempt lineage.
- Closed Domain and Temporal predecessor receipts embed their schema, ordered stage, accepted
  decision, verified status, immutable full context, document hash, identity, and recomputable
  receipt hash. Stage-state labels or bare digests are insufficient.
- The Graph checkpoint version and pre-restore digest are known, and the supported restore
  interface is selected without any internal-table edit.
- The object manifest is immutable and versioned; its expected object count and content hashes are
  available through private, audited access.
- Approved RPO/RTO objectives and a rollback target are recorded in advance.

## External Procedure

1. Confirm the prior Domain and Temporal stages, then restore Graph using the supported platform.
2. Compare checkpoint digests, verify old-checkpoint readability, and reconcile leases and fences.
3. Only after Graph is verified, restore the exact immutable object versions from the manifest.
4. Compare the restored count with the expected count and verify every content hash. Do not accept
   an unversioned, mutable, public, or incomplete object source.
5. Keep workers and projections stopped. Inventory external-effect receipts and use confirm,
   compensate, or manual review; never use blind replay.
6. Measure RPO/RTO and bind the ordered closed Domain/Temporal predecessor receipts into a current
   immutable receipt. Bind Graph, object, activation-fence, objective, rollback, and external-effect
   receipts to the same context hash for fixture validation.

## Stop And Rollback

Stop for authorization or context failure, an internal-table edit, checkpoint digest mismatch,
unreadable old checkpoint, unresolved lease or fence, object count/hash mismatch, loss of private
immutable versioning, early worker/projection activation, or an RPO/RTO breach. Preserve the failed
attempt and follow the pre-approved rollback decision.

`FIXTURE_ACCEPTED` is not permission to start workers or projections and does not establish that a
real Graph, object-store, or regional recovery succeeded.
