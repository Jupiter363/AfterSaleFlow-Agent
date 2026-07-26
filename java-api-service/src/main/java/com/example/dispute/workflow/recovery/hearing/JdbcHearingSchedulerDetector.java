package com.example.dispute.workflow.recovery.hearing;

import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.Detection;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.DetectorKind;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/** Aggregated V035/V044 comparison. Queries return counts only and never load private payloads. */
@Component
public class JdbcHearingSchedulerDetector implements HearingSchedulerDetector {

    static final String DEADLINE_DETECTION_SQL =
            """
            with scan_state as (
                select flow.id as flow_instance_id,
                       flow.case_id,
                       flow.flow_status,
                       flow.current_stage,
                       flow.stage_sequence,
                       flow.shared_deadline_at,
                       stage.id as stage_id,
                       stage.stage_code,
                       stage.stage_status,
                       stage.shared_deadline_at as stage_deadline_at,
                       stage.agent_run_id,
                       failed_run.run_status as agent_run_status,
                       dispute.id as dispute_id,
                       dispute.user_id,
                       dispute.merchant_id,
                       dispute.current_deadline_at as case_deadline_at,
                       projection.flow_instance_id as projection_id,
                       projection.writer_mode as projection_writer_mode,
                       projection.current_stage as projection_stage,
                       projection.stage_sequence as projection_sequence,
                       projection.stage_deadline_at as projection_deadline_at,
                       authority.id as authority_id
                  from hearing_flow_instance flow
                  left join hearing_flow_stage stage
                    on stage.flow_instance_id = flow.id
                   and stage.case_id = flow.case_id
                   and stage.stage_sequence = flow.stage_sequence
                  left join agent_run failed_run
                    on failed_run.id = stage.agent_run_id
                  left join fulfillment_dispute_case dispute
                    on dispute.id = flow.case_id
                  left join hearing_temporal_projection projection
                    on projection.flow_instance_id = flow.id
                   and projection.case_id = flow.case_id
                  left join case_room_epoch authority
                    on authority.id = projection.epoch_id
                   and authority.tenant_surrogate = projection.tenant_surrogate
                   and authority.case_id = projection.case_id
                   and authority.room_type = 'HEARING'
                   and authority.room_epoch = projection.hearing_epoch
                   and authority.fencing_token = projection.fencing_token
                   and authority.writer_mode = projection.writer_mode
                   and authority.process_revision = projection.process_revision
                   and authority.room_revision = projection.room_revision
                   and projection.temporal_workflow_id is not distinct from coalesce(
                       authority.room_temporal_workflow_id,
                       authority.temporal_workflow_id
                   )
                   and projection.temporal_run_id is not distinct from coalesce(
                       authority.room_temporal_run_id,
                       authority.temporal_run_id
                   )
                   and projection.temporal_build_or_deployment is not distinct from coalesce(
                       authority.room_workflow_build_id,
                       authority.temporal_build_id,
                       'legacy-java.v1'
                   )
                   and authority.lifecycle_status = 'ACTIVE'
                 where flow.flow_status in ('ACTIVE', 'FAILED')
            ), candidate_state as (
                select scan_state.*,
                       (
                           ((flow_status = 'ACTIVE' and stage_status = 'RUNNING')
                               or (flow_status = 'FAILED' and stage_status = 'FAILED'))
                           and agent_run_id is not null
                           and agent_run_status = 'FAILED'
                           and current_stage in (
                               'INTAKE_QUESTIONS_GENERATING',
                               'INTAKE_SYNTHESIZING',
                               'EVIDENCE_REQUESTS_GENERATING',
                               'EVIDENCE_SYNTHESIZING',
                               'JUDGE_V1_GENERATING',
                               'JURY_REVIEWING',
                               'JUDGE_V2_GENERATING'
                           )
                       ) as failed_run_recovery_candidate,
                       (
                           flow_status = 'ACTIVE'
                           and current_stage in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
                           and shared_deadline_at is not null
                           and least(
                               shared_deadline_at,
                               coalesce(case_deadline_at, shared_deadline_at)
                           ) <= current_timestamp
                       ) as party_deadline_candidate
                  from scan_state
            ), legacy_candidates as (
                select candidate_state.*
                  from candidate_state
                 where stage_id is null
                    or dispute_id is null
                    or failed_run_recovery_candidate
                    or party_deadline_candidate
            )
            select count(*) as candidate_count,
                   count(*) filter (where
                        projection_id is null
                        or projection_writer_mode is distinct from 'TEMPORAL'
                        or authority_id is null
                        or projection_stage is distinct from current_stage
                        or projection_sequence is distinct from stage_sequence
                        or projection_deadline_at is distinct from shared_deadline_at
                        or stage_id is null
                        or stage_code is distinct from current_stage
                        or stage_deadline_at is distinct from shared_deadline_at
                        or dispute_id is null
                        or failed_run_recovery_candidate
                        or (party_deadline_candidate and (
                            shared_deadline_at is null
                            or (case_deadline_at is not null
                                and case_deadline_at < shared_deadline_at)
                            or not exists (
                                select 1
                                  from hearing_flow_action party_action
                                 where party_action.stage_id = legacy_candidates.stage_id
                                   and party_action.action_type = case
                                       when legacy_candidates.current_stage = 'PARTY_ANSWERS_OPEN'
                                           then 'ANSWER_BUNDLE'
                                       else 'EVIDENCE_BATCH'
                                   end
                                   and party_action.participant_id = legacy_candidates.user_id
                            )
                            or not exists (
                                select 1
                                  from hearing_flow_action party_action
                                 where party_action.stage_id = legacy_candidates.stage_id
                                   and party_action.action_type = case
                                       when legacy_candidates.current_stage = 'PARTY_ANSWERS_OPEN'
                                           then 'ANSWER_BUNDLE'
                                       else 'EVIDENCE_BATCH'
                                   end
                                   and party_action.participant_id = legacy_candidates.merchant_id
                            )
                        ))
                   ) as mismatch_count
              from legacy_candidates
            """;

    static final String HANDOFF_DETECTION_SQL =
            """
with handoff_candidates as (
    select draft.id as draft_id,
           draft.flow_instance_id,
           draft.case_id,
           draft.content_hash,
           flow.id as authoritative_flow_id,
           flow.current_stage as authoritative_stage,
           flow.stage_sequence as authoritative_sequence,
           flow.shared_deadline_at as authoritative_deadline,
           current_stage.id as current_stage_id,
           current_stage.stage_code as current_stage_code,
           current_stage.stage_sequence as current_stage_sequence,
           projection.flow_instance_id as projection_id,
           projection.writer_mode as projection_writer_mode,
           projection.current_stage as projection_stage,
           projection.stage_sequence as projection_sequence,
           projection.stage_deadline_at as projection_deadline,
           projection.process_revision as projection_process_revision,
           projection.room_revision as projection_room_revision,
           authority.id as authority_id,
           authority.lifecycle_status as authority_lifecycle_status,
           authority.process_revision as authority_process_revision,
           authority.room_revision as authority_room_revision,
           exists (
               select 1
                 from hearing_review_handoff_fact handoff
                where handoff.flow_instance_id = draft.flow_instance_id
                  and handoff.case_id = draft.case_id
                  and handoff.judge_v2_id = draft.id
                  and handoff.judge_v2_hash = draft.content_hash
           ) as exact_handoff_recorded
      from hearing_flow_artifact draft
      left join hearing_flow_instance flow
        on flow.id = draft.flow_instance_id
       and flow.case_id = draft.case_id
      left join hearing_flow_stage current_stage
        on current_stage.flow_instance_id = flow.id
       and current_stage.case_id = flow.case_id
       and current_stage.stage_sequence = flow.stage_sequence
      left join hearing_temporal_projection projection
        on projection.flow_instance_id = draft.flow_instance_id
       and projection.case_id = draft.case_id
      left join case_room_epoch authority
        on authority.id = projection.epoch_id
       and authority.tenant_surrogate = projection.tenant_surrogate
       and authority.case_id = projection.case_id
       and authority.room_type = 'HEARING'
       and authority.room_epoch = projection.hearing_epoch
       and authority.fencing_token = projection.fencing_token
       and authority.writer_mode = projection.writer_mode
       and projection.temporal_workflow_id is not distinct from coalesce(
           authority.room_temporal_workflow_id,
           authority.temporal_workflow_id
       )
       and projection.temporal_run_id is not distinct from coalesce(
           authority.room_temporal_run_id,
           authority.temporal_run_id
       )
       and projection.temporal_build_or_deployment is not distinct from coalesce(
           authority.room_workflow_build_id,
           authority.temporal_build_id,
           'legacy-java.v1'
       )
     where draft.artifact_type = 'ADJUDICATION_DRAFT'
)
select count(*) as candidate_count,
       count(*) filter (where
            authoritative_flow_id is null
            or authoritative_stage not in ('HUMAN_REVIEW_OPEN', 'CLOSED')
            or (authoritative_stage = 'HUMAN_REVIEW_OPEN' and (
                current_stage_id is null
                or current_stage_code is distinct from authoritative_stage
                or current_stage_sequence is distinct from authoritative_sequence
            ))
            or projection_id is null
            or authority_id is null
            or projection_stage is distinct from authoritative_stage
            or projection_sequence is distinct from authoritative_sequence
            or projection_deadline is distinct from authoritative_deadline
            or not exact_handoff_recorded
            or (authoritative_stage = 'HUMAN_REVIEW_OPEN' and (
                projection_writer_mode is distinct from 'TEMPORAL'
                or authority_lifecycle_status is distinct from 'ACTIVE'
                or authority_process_revision is distinct from projection_process_revision
                or authority_room_revision is distinct from projection_room_revision
            ))
            or (authoritative_stage = 'CLOSED' and (
                projection_writer_mode not in ('LEGACY', 'TEMPORAL')
                or not (
                    (authority_lifecycle_status = 'ACTIVE'
                        and authority_process_revision = projection_process_revision
                        and authority_room_revision = projection_room_revision)
                    or
                    (authority_lifecycle_status = 'TERMINAL'
                        and (
                            (authority_process_revision = projection_process_revision
                                and authority_room_revision = projection_room_revision)
                            or
                            (authority_process_revision = projection_process_revision + 1
                                and authority_room_revision = projection_room_revision + 1)
                        ))
                )
            ))
       ) as mismatch_count
  from handoff_candidates
""";

    private final JdbcTemplate jdbcTemplate;

    public JdbcHearingSchedulerDetector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Detection inspectDeadlineProjection() {
        return detect(DetectorKind.DEADLINE_PROJECTION, DEADLINE_DETECTION_SQL);
    }

    @Override
    @Transactional(readOnly = true)
    public Detection inspectHandoffProjection() {
        return detect(DetectorKind.HANDOFF_PROJECTION, HANDOFF_DETECTION_SQL);
    }

    private Detection detect(DetectorKind kind, String sql) {
        Map<String, Object> counts = jdbcTemplate.queryForMap(sql);
        long candidates = count(counts, "candidate_count");
        long mismatches = count(counts, "mismatch_count");
        return Detection.fromCounts(kind, candidates, mismatches);
    }

    private static long count(Map<String, Object> counts, String field) {
        Object value = counts.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Hearing scheduler detector returned no " + field);
        }
        return number.longValue();
    }
}
