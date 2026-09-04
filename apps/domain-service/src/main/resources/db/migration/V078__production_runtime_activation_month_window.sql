alter table production_runtime_activation
    drop constraint if exists ck_production_runtime_activation_time;

alter table production_runtime_activation
    add constraint ck_production_runtime_activation_time check (
        expires_at > issued_at
        and expires_at <= issued_at + interval '30 days'
        and lifecycle_changed_at >= registered_at
    );
