# Phase 8 Temporal Regional DR

```text
artifact_scope: FIXTURE_VALIDATION_AND_EXTERNAL_PROCEDURE_ONLY
engineering_checkpoint: NOT_RUN
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
real_failover_or_regional_dr: FORBIDDEN_FROM_ENGINEERING_TOOLING
```

## Boundary

This runbook defines an external Temporal regional-DR evidence handoff. The local
`temporal_regional_dr.py` module accepts only a caller-supplied document and has no network,
Temporal, cloud, subprocess, database, filesystem-mutation, or credential capability. It does not
perform a namespace failover or restore.

Domain recovery must already be verified. Graph, object store, workers, and projections remain
stopped while the Temporal checkpoint is evaluated. Temporal internal persistence edits are
prohibited; only the supported control plane may be used by the externally authorized team.

## External Preconditions

- Independent SRE or Temporal-platform authorization binds the exact candidate, configuration,
  deployment, environment, operator, scenario, and attempt lineage.
- The exact closed Domain receipt is embedded with its schema, stage, accepted decision, verified
  status, immutable full context, document hash, identity, and recomputable receipt hash. A declared
  `VERIFIED` stage or a bare digest is insufficient.
- Compatible worker builds and captured History replay evidence exist before the drill.
- The regional objectives are at most five minutes RPO and thirty minutes RTO, while acknowledged
  Temporal Updates, Signals, Timers, and other acknowledged events have a zero-loss requirement.
- A last-known-good rollback target is approved before the namespace operation.

## External Procedure

1. Keep workers and projections fenced and verify the Domain-stage receipt.
2. Record the pre-operation History digest and the acknowledged event boundary.
3. Have the authorized platform team use the supported Temporal control plane for the regional
   operation. Never modify internal Temporal persistence.
4. Record the post-operation History digest and confirm replication consistency and zero loss of
   acknowledged Updates, Signals, Timers, or other events.
5. Verify old History readability and captured History replay with the retained compatible worker
   builds. This is diagnostic replay only and must not replay external effects.
6. Reconcile external-effect receipts through confirm, compensate, or manual-review outcomes.
7. Measure RPO/RTO, then bind the closed Domain predecessor receipt in a current immutable receipt
   alongside namespace, compatibility, rollback, objective, and external-effect receipts. Seal
   every receipt to the exact context before submitting the document to the pure validator.

## Stop And Rollback

Stop for missing or self-approved authorization, context drift, an invalid receipt, History digest
drift, any acknowledged-state loss, unavailable compatibility workers, unreadable old History, an
objective breach, or a request for an internal-table edit. Retain the failed attempt and follow the
pre-authorized rollback decision before any Graph recovery begins.

`FIXTURE_ACCEPTED` is a static document result only. It cannot authorize Graph, object-store,
worker, or projection activation and cannot satisfy a production gate.
