-- Make the human-review and execution provenance chain immutable and complete.

alter table review_packet
    add column frozen boolean not null default true,
    add column frozen_at timestamptz not null default now(),
    add column expires_at timestamptz not null default (now() + interval '7 days'),
    add column agent_run_refs_json jsonb not null default '[]'::jsonb;

update review_packet
set case_version = coalesce(case_version, 1),
    dossier_version = coalesce(dossier_version, 1),
    issue_version = coalesce(issue_version, 1),
    adjudication_draft_version = coalesce(adjudication_draft_version, 1),
    deliberation_report_version = coalesce(deliberation_report_version, 0),
    remedy_plan_version = coalesce(remedy_plan_version, 1),
    ruleset_version = coalesce(ruleset_version, 'legacy-ruleset'),
    prompt_version = coalesce(prompt_version, 'legacy-prompt'),
    skill_version = coalesce(skill_version, 'legacy-skill'),
    profile_version = coalesce(profile_version, 'legacy-profile'),
    action_hash = coalesce(action_hash, 'LEGACY_' || id);

alter table review_packet
    alter column case_version set not null,
    alter column dossier_version set not null,
    alter column issue_version set not null,
    alter column adjudication_draft_version set not null,
    alter column deliberation_report_version set not null,
    alter column remedy_plan_version set not null,
    alter column ruleset_version set not null,
    alter column prompt_version set not null,
    alter column skill_version set not null,
    alter column profile_version set not null,
    alter column action_hash set not null,
    add constraint ck_review_packet_frozen check (frozen);

alter table human_review_record
    add column review_packet_id varchar(64),
    add column review_packet_version integer,
    add column policy_version varchar(64),
    add column action_snapshot_hash varchar(128),
    add column approval_expires_at timestamptz,
    add constraint fk_human_review_packet
        foreign key (review_packet_id) references review_packet(id);

alter table action_record
    add column review_packet_id varchar(64),
    add column action_snapshot_hash varchar(128),
    add column evidence_refs_json jsonb not null default '[]'::jsonb,
    add column rule_refs_json jsonb not null default '[]'::jsonb,
    add column agent_run_refs_json jsonb not null default '[]'::jsonb,
    add column external_result_ref varchar(256),
    add constraint fk_action_review_packet
        foreign key (review_packet_id) references review_packet(id);

create index idx_review_packet_case_version
    on review_packet(case_id, packet_version);
create index idx_human_review_packet
    on human_review_record(review_packet_id);
create index idx_action_review_packet
    on action_record(review_packet_id);
