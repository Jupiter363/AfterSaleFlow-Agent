alter table evidence_party_completion
    drop constraint if exists uq_evidence_completion_role;

alter table evidence_party_completion
    add constraint uq_evidence_completion_participant
        unique (case_id, dossier_version, participant_id);
