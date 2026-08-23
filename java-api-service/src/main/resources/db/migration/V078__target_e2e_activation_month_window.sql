alter table target_e2e_activation
    drop constraint if exists ck_target_e2e_activation_time;

alter table target_e2e_activation
    add constraint ck_target_e2e_activation_time check (
        expires_at > issued_at
        and expires_at <= issued_at + interval '30 days'
        and lifecycle_changed_at >= registered_at
    );
