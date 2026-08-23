-- Preserve historical V1 rows while making the simplified frozen adjudication
-- dossier and the separately versioned Judge artifacts authoritative for new flows.

alter table hearing_trial_dossier
    alter column schema_version set default 'trial_dossier.v2';

alter table hearing_trial_dossier
    drop constraint ck_hearing_trial_dossier_schema;

alter table hearing_trial_dossier
    add constraint ck_hearing_trial_dossier_schema
        check (schema_version in ('trial_dossier.v1', 'trial_dossier.v2'));

alter table hearing_trial_dossier
    drop constraint ck_hearing_trial_dossier_payload;

alter table hearing_trial_dossier
    add constraint ck_hearing_trial_dossier_payload
        check (
            jsonb_typeof(payload_json) = 'object'
            and payload_json ?& array[
                'schema_version', 'trial_dossier_id', 'case_id', 'frozen_at',
                'case_matrix_version', 'case_matrix_hash', 'case_fact_matrix',
                'evidence_matrix_version', 'evidence_matrix_hash',
                'fact_evidence_matrix', 'content_hash'
            ]
            and payload_json ->> 'schema_version' = schema_version
            and payload_json ->> 'trial_dossier_id' = id
            and payload_json ->> 'case_id' = case_id
            and payload_json ->> 'content_hash' = content_hash
            and (payload_json ->> 'case_matrix_version')::integer = case_matrix_version
            and payload_json ->> 'case_matrix_hash' = case_matrix_hash
            and (payload_json ->> 'evidence_matrix_version')::integer = evidence_matrix_version
            and payload_json ->> 'evidence_matrix_hash' = evidence_matrix_hash
            and payload_json #>> '{case_fact_matrix,content_hash}' = case_matrix_hash
            and payload_json #>> '{fact_evidence_matrix,content_hash}' = evidence_matrix_hash
            and payload_json #>> '{fact_evidence_matrix,matrix_status}' = 'FROZEN'
            and (
                (
                    schema_version = 'trial_dossier.v1'
                    and payload_json ?& array[
                        'question_set_id', 'question_set', 'answer_bundles',
                        'request_set_id', 'evidence_request_set', 'evidence_batches'
                    ]
                    and payload_json ->> 'question_set_id' = question_set_id
                    and payload_json ->> 'request_set_id' = request_set_id
                    and jsonb_typeof(payload_json -> 'answer_bundles') = 'array'
                    and jsonb_array_length(payload_json -> 'answer_bundles') = 2
                    and jsonb_typeof(payload_json -> 'evidence_batches') = 'array'
                    and jsonb_array_length(payload_json -> 'evidence_batches') = 2
                )
                or
                (
                    schema_version = 'trial_dossier.v2'
                    and payload_json ? 'adjudication_rules'
                    and jsonb_typeof(payload_json -> 'adjudication_rules') = 'array'
                    and jsonb_array_length(payload_json -> 'adjudication_rules') >= 1
                    and not (payload_json ?| array[
                        'question_set_id', 'question_set', 'answer_bundles',
                        'request_set_id', 'evidence_request_set', 'evidence_batches',
                        'policy_rules'
                    ])
                )
            )
        );

alter table hearing_flow_artifact
    drop constraint ck_hearing_flow_artifact_schema;

alter table hearing_flow_artifact
    add constraint ck_hearing_flow_artifact_schema
        check (
            (artifact_type = 'JUDGE_PROPOSAL'
                and schema_version in ('judge_proposal.v1', 'judge_proposal.v2'))
            or
            (artifact_type = 'JURY_REVIEW_REPORT'
                and schema_version = 'jury_review_report.v1')
            or
            (artifact_type = 'ADJUDICATION_DRAFT'
                and schema_version in ('adjudication_draft.v2', 'adjudication_draft.v3'))
        );

create or replace function enforce_hearing_decision_parent_chain()
returns trigger
language plpgsql
as $$
declare
    dossier hearing_trial_dossier%rowtype;
    proposal hearing_flow_artifact%rowtype;
    report hearing_flow_artifact%rowtype;
begin
    select value.*
      into dossier
      from hearing_trial_dossier value
     where value.id = new.trial_dossier_id
       and value.case_id = new.case_id
       and value.flow_instance_id = new.flow_instance_id
       and value.schema_version in ('trial_dossier.v1', 'trial_dossier.v2')
       and value.content_hash = new.trial_dossier_hash;
    if not found then
        raise exception using errcode = '23514',
            message = 'Hearing decision artifact has no exact frozen dossier';
    end if;

    if new.artifact_type in ('JURY_REVIEW_REPORT', 'ADJUDICATION_DRAFT') then
        select value.*
          into proposal
          from hearing_flow_artifact value
         where value.id = new.proposal_id
           and value.case_id = new.case_id
           and value.flow_instance_id = new.flow_instance_id
           and value.trial_dossier_id = dossier.id
           and value.trial_dossier_hash = dossier.content_hash
           and value.artifact_type = 'JUDGE_PROPOSAL'
           and (
                (dossier.schema_version = 'trial_dossier.v1'
                    and value.schema_version = 'judge_proposal.v1')
                or
                (dossier.schema_version = 'trial_dossier.v2'
                    and value.schema_version = 'judge_proposal.v2')
           )
           and value.content_hash = new.proposal_content_hash;
        if not found then
            raise exception using errcode = '23514',
                message = 'Hearing decision artifact has no exact Judge V1 parent';
        end if;
    end if;

    if new.artifact_type = 'ADJUDICATION_DRAFT' then
        select value.*
          into report
          from hearing_flow_artifact value
         where value.id = new.report_id
           and value.case_id = new.case_id
           and value.flow_instance_id = new.flow_instance_id
           and value.trial_dossier_id = dossier.id
           and value.trial_dossier_hash = dossier.content_hash
           and value.artifact_type = 'JURY_REVIEW_REPORT'
           and value.schema_version = 'jury_review_report.v1'
           and value.proposal_id = proposal.id
           and value.proposal_content_hash = proposal.content_hash
           and value.content_hash = new.report_content_hash;
        if not found then
            raise exception using errcode = '23514',
                message = 'Hearing Judge V2 artifact has no exact Jury parent';
        end if;
    end if;
    return new;
end
$$;
