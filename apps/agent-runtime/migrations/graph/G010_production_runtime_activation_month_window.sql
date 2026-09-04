alter table agent_graph_production_runtime_activation
    drop constraint if exists ck_production_runtime_activation_expiry;

alter table agent_graph_production_runtime_activation
    add constraint ck_production_runtime_activation_expiry check (
        expires_at > issued_at
        and expires_at <= issued_at + interval '30 days'
    );
