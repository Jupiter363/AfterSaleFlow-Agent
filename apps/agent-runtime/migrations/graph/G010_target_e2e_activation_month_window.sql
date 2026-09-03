alter table agent_graph_target_e2e_activation
    drop constraint if exists ck_target_e2e_activation_expiry;

alter table agent_graph_target_e2e_activation
    add constraint ck_target_e2e_activation_expiry check (
        expires_at > issued_at
        and expires_at <= issued_at + interval '30 days'
    );
