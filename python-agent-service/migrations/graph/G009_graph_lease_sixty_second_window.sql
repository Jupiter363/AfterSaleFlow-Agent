-- Extend the execution lease tolerance without weakening owner/fence checks.
-- Existing installations retain the immutable G001 checksum and receive the
-- new window through this forward-only constraint replacement.

alter table agent_graph_lease
    drop constraint ck_agent_graph_lease_window;

alter table agent_graph_lease
    add constraint ck_agent_graph_lease_window
    check (
        lease_expires_at > renewed_at
        and lease_expires_at <= renewed_at + interval '60 seconds'
    );
