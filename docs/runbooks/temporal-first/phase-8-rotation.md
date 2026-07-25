# Phase 8 Rotation Compatibility

```text
artifact_scope: FIXTURE_VALIDATION_AND_EXTERNAL_PROCEDURE_ONLY
engineering_checkpoint: NOT_RUN
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
real_secret_certificate_credential_or_codec_rotation: FORBIDDEN_FROM_ENGINEERING_TOOLING
```

## Boundary

This runbook covers external certificate, service credential, database credential, and payload
codec-key rotation compatibility. `scripts/phase8/recovery/rotation_compatibility.py` validates
explicit metadata and digest fixtures only. It never reads secret-bearing environment variables,
contains no secret values, and cannot call a network, KMS, vault, database, Temporal, cloud, or
subprocess interface.

Identifiers in the evidence are non-secret references. Secret, certificate, credential, and key
material must never be copied into the fixture, report, logs, or runbook.

## External Preconditions

- Independent Security or SRE authorization binds the exact candidate, configuration, deployment,
  environment, operator, scenario, and attempt lineage.
- An overlap window retains the old read path while new writes use the new material reference.
- Active-reference inventory covers retained Temporal History, Graph checkpoints, payload codecs,
  and key references.
- Rollback to the last-known-good material reference is approved before rotation.

## External Procedure

1. Record only opaque old/new material identifiers, rotation type, and immutable authorization.
2. Begin the externally controlled overlap window without retiring the old read path.
3. Produce readback samples for retained Temporal History, Graph checkpoints, and codec payloads.
   Bind each opaque codec/key identifier and content digest to its readback digest.
4. Confirm that old History, old checkpoints, old codecs, old keys, and new material are readable.
5. Inventory active old references. While any remain, the retirement decision must be
   `RETAIN_OLD_READ_PATH`.
6. Reconcile any external effects by existing receipt; rotation is never a reason to replay them.
7. Seal the rotation plan, compatibility inventory, readability samples, rollback, and
   external-effect receipts against the exact context for pure fixture validation.

## Stop And Rollback

Stop for missing independent authorization, mixed context, mutable evidence, a readback digest
mismatch, unreadable History/checkpoint/codec/key data, incomplete active-reference inventory,
premature old-material retirement, or any secret-bearing field. Preserve the failed attempt and
follow the pre-approved rollback decision; do not revoke the old path until a separately authorized
zero-reference retirement gate is satisfied.

`FIXTURE_ACCEPTED` means the metadata document is internally consistent. It does not perform or
approve a real rotation and cannot close a production, promotion, or migration gate.
