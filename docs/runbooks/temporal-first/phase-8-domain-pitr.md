# Phase 8 Domain PITR

```text
artifact_scope: FIXTURE_VALIDATION_AND_EXTERNAL_PROCEDURE_ONLY
engineering_checkpoint: NOT_RUN
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
real_restore: FORBIDDEN_FROM_ENGINEERING_TOOLING
```

## Boundary

This runbook describes the evidence an externally authorized DBA and SRE team must produce for a
Domain PostgreSQL point-in-time recovery drill. `scripts/phase8/recovery/domain_pitr.py` only
validates an explicit in-memory fixture. It cannot connect to a database, read credentials, invoke
a backup service, mutate a filesystem, or perform a restore.

The required recovery order is `DOMAIN`, `TEMPORAL`, `GRAPH`, `OBJECT_STORE`, `WORKERS`, then
`PROJECTIONS`. A later stage remains stopped until the prior stage has immutable, same-context
evidence. Internal database-table edits are prohibited.

## External Preconditions

- A separately authorized change window identifies the candidate, configuration, deployment,
  environment, operator, scenario, and attempt lineage.
- An independent DBA or SRE authorization is current, signed, scoped to the Domain PITR drill, and
  is neither expired, revoked, nor self-approved.
- The selected backup is immutable, encrypted, restorable, and bound to a known committed sequence.
- Intake, workers, projection rebuilds, and later recovery stages are fenced by the external team.
- The approved RPO is zero committed Domain transactions. The RTO objective and rollback target
  are recorded before restoration begins.

## External Procedure

1. Record the immutable context and authorization receipts before any recovery action.
2. Record the selected backup identifier, content hash, and latest committed sequence. Stop if the
   backup, retention boundary, or restore interface is ambiguous.
3. Have the authorized DBA restore through the supported platform into the authorized target. Do
   not edit application or PostgreSQL internal tables.
4. Compare the restored sequence with the commit boundary. Reconcile accepted commands, formal
   facts, outbox rows, and duplicate facts while projections remain stopped.
5. Inventory every external-effect receipt. Confirm the existing effect, compensate it, or route it
   to manual review. Never blindly replay an external effect.
6. Measure RPO and RTO against the objectives from the same attempt. Any breach activates the
   recorded rollback decision and blocks later stages.
7. Seal backup, restore, reconciliation, external-effect, objective, and rollback payloads with the
   exact context hash. Supply only those explicit documents to the fixture validator.

## Stop And Rollback

Stop on missing authorization, a mixed context, a mutable or invalid receipt, a backup hash error,
committed transaction loss, an outbox gap, a duplicate formal fact, premature projection rebuild,
an RPO/RTO breach, or any request to edit an internal table. Preserve the failed attempt and use the
pre-recorded last-known-good rollback target; do not overwrite failure evidence with a retry.

`FIXTURE_ACCEPTED` means only that the supplied document satisfies the static contract. It does not
authorize Temporal, Graph, object-store, worker, or projection recovery and is not production or
promotion evidence by itself.
