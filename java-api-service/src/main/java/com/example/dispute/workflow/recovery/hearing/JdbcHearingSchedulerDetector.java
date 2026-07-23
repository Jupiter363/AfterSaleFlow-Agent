package com.example.dispute.workflow.recovery.hearing;

import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.Detection;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.DetectorKind;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Aggregated V035/V044 comparison. Queries return counts only and never load private payloads. */
@Component
public final class JdbcHearingSchedulerDetector implements HearingSchedulerDetector {

    static final String DEADLINE_DETECTION_SQL = """
            select count(*) as candidate_count,
                   count(*) filter (where
                        flow.id is null
                        or flow.current_stage <> projection.current_stage
                        or flow.stage_sequence <> projection.stage_sequence
                        or flow.shared_deadline_at is distinct from projection.stage_deadline_at
                   ) as mismatch_count
              from hearing_temporal_projection projection
              left join hearing_flow_instance flow
                on flow.id = projection.flow_instance_id
               and flow.case_id = projection.case_id
             where projection.writer_mode = 'TEMPORAL'
               and projection.current_stage in ('PARTY_ANSWERS_OPEN', 'PARTY_EVIDENCE_OPEN')
               and projection.stage_deadline_at <= current_timestamp
            """;

    static final String HANDOFF_DETECTION_SQL = """
            select count(*) as candidate_count,
                   count(*) filter (where not exists (
                        select 1
                          from hearing_review_handoff_fact handoff
                         where handoff.flow_instance_id = projection.flow_instance_id
                           and handoff.case_id = projection.case_id
                           and handoff.judge_v2_id = draft.id
                           and handoff.judge_v2_hash = draft.content_hash
                   )) as mismatch_count
              from hearing_temporal_projection projection
              join hearing_flow_artifact draft
                on draft.flow_instance_id = projection.flow_instance_id
               and draft.case_id = projection.case_id
               and draft.artifact_type = 'ADJUDICATION_DRAFT'
             where projection.writer_mode = 'TEMPORAL'
               and projection.current_stage in ('HUMAN_REVIEW_OPEN', 'CLOSED')
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
