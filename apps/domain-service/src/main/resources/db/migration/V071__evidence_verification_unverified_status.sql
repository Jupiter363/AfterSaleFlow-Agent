alter table evidence_verification
    drop constraint ck_evidence_verification_status;

alter table evidence_verification
    add constraint ck_evidence_verification_status
        check (
            verification_status in (
                'UNVERIFIED',
                'VERIFIED',
                'PLAUSIBLE',
                'SUSPICIOUS',
                'REJECTED',
                'NEEDS_HUMAN_REVIEW'
            )
        );
