-- A DRAINED activation must carry an immutable independently signed evidence binding.
-- The lifecycle HTTP capability can no longer turn caller-supplied counters/booleans into
-- durable replica-detached or evidence-sealed facts.

alter table target_e2e_activation
    add column drain_completion_proof_hash varchar(64),
    add column drain_evidence_ledger_head_hash varchar(64),
    add column drain_forensic_manifest_hash varchar(64),
    add column drain_attestation_key_sha256 varchar(64);

alter table target_e2e_activation
    drop constraint ck_target_e2e_activation_lifecycle;

alter table target_e2e_activation
    add constraint ck_target_e2e_activation_lifecycle check (
        (
            lifecycle_status = 'REGISTERED'
            and activated_at is null and drain_only_at is null
            and drained_at is null and revoked_at is null
            and all_replicas_detached = false and evidence_sealed = false
            and drain_completion_proof_hash is null
            and drain_evidence_ledger_head_hash is null
            and drain_forensic_manifest_hash is null
            and drain_attestation_key_sha256 is null
        ) or (
            lifecycle_status = 'ACTIVE'
            and activated_at is not null and drain_only_at is null
            and drained_at is null and revoked_at is null
            and all_replicas_detached = false and evidence_sealed = false
            and drain_completion_proof_hash is null
            and drain_evidence_ledger_head_hash is null
            and drain_forensic_manifest_hash is null
            and drain_attestation_key_sha256 is null
        ) or (
            lifecycle_status = 'DRAIN_ONLY'
            and activated_at is not null and drain_only_at is not null
            and drain_only_at >= expires_at
            and drained_at is null and revoked_at is null
            and all_replicas_detached = false and evidence_sealed = false
            and drain_completion_proof_hash is null
            and drain_evidence_ledger_head_hash is null
            and drain_forensic_manifest_hash is null
            and drain_attestation_key_sha256 is null
        ) or (
            lifecycle_status = 'DRAINED'
            and activated_at is not null and drain_only_at is not null
            and drained_at is not null and drained_at >= drain_only_at
            and revoked_at is null
            and all_replicas_detached = true and evidence_sealed = true
            and drain_completion_proof_hash ~ '^[0-9a-f]{64}$'
            and drain_evidence_ledger_head_hash ~ '^[0-9a-f]{64}$'
            and drain_forensic_manifest_hash ~ '^[0-9a-f]{64}$'
            and drain_attestation_key_sha256 ~ '^[0-9a-f]{64}$'
        ) or (
            lifecycle_status = 'REVOKED_TERMINAL'
            and activated_at is not null and drain_only_at is not null
            and drained_at is not null and revoked_at is not null
            and revoked_at > drained_at
            and all_replicas_detached = true and evidence_sealed = true
            and drain_completion_proof_hash ~ '^[0-9a-f]{64}$'
            and drain_evidence_ledger_head_hash ~ '^[0-9a-f]{64}$'
            and drain_forensic_manifest_hash ~ '^[0-9a-f]{64}$'
            and drain_attestation_key_sha256 ~ '^[0-9a-f]{64}$'
        )
    );

create or replace function guard_target_e2e_drain_proof_mutation()
returns trigger
language plpgsql
as $$
begin
    if old.lifecycle_status = 'DRAIN_ONLY' and new.lifecycle_status = 'DRAINED' then
        if old.drain_completion_proof_hash is not null
           or old.drain_evidence_ledger_head_hash is not null
           or old.drain_forensic_manifest_hash is not null
           or old.drain_attestation_key_sha256 is not null
           or new.drain_completion_proof_hash !~ '^[0-9a-f]{64}$'
           or new.drain_evidence_ledger_head_hash !~ '^[0-9a-f]{64}$'
           or new.drain_forensic_manifest_hash !~ '^[0-9a-f]{64}$'
           or new.drain_attestation_key_sha256 !~ '^[0-9a-f]{64}$'
           or new.all_replicas_detached is not true
           or new.evidence_sealed is not true then
            raise exception using errcode = '23514',
                message = 'target E2E DRAINED transition requires exact attested evidence';
        end if;
    elsif new.drain_completion_proof_hash is distinct from old.drain_completion_proof_hash
       or new.drain_evidence_ledger_head_hash is distinct from old.drain_evidence_ledger_head_hash
       or new.drain_forensic_manifest_hash is distinct from old.drain_forensic_manifest_hash
       or new.drain_attestation_key_sha256 is distinct from old.drain_attestation_key_sha256
       or new.all_replicas_detached is distinct from old.all_replicas_detached
       or new.evidence_sealed is distinct from old.evidence_sealed then
        raise exception using errcode = '55000',
            message = 'target E2E drain evidence is immutable';
    end if;
    return new;
end
$$;

create trigger trg_target_e2e_activation_drain_proof_guard
before update on target_e2e_activation
for each row execute function guard_target_e2e_drain_proof_mutation();
