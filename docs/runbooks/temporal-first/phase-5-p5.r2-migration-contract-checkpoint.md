# Phase 5 P5.R2 Migration Contract Checkpoint

P5-R2 authorizes exactly one additive migration for Wave B C3:

`java-api-service/src/main/resources/db/migration/V043_5__evidence_finalization_and_operational_recovery.sql`

Run the gate from a clean candidate:

```powershell
D:\miniconda\python.exe scripts/run_phase5_r2_migration_contract_gate.py `
  --candidate-commit <candidate> `
  --execute `
  --run-dir .codex-run/phase5-r2-migration-contract-<candidate-prefix>
```

The evidence commit records the accepted candidate SHA, V043_4 SHA-256, authorized migration path,
and artifact hashes. Until that evidence commit lands, C3 must not create or stage `V043_5`.
