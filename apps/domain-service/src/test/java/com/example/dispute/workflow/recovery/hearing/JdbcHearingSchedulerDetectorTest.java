package com.example.dispute.workflow.recovery.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.DetectionOutcome;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Modifier;
import java.util.Map;

class JdbcHearingSchedulerDetectorTest {

    @Test
    void transactionalDetectorRemainsSubclassProxyable() throws NoSuchMethodException {
        assertThat(Modifier.isFinal(JdbcHearingSchedulerDetector.class.getModifiers())).isFalse();
        assertThat(
                        JdbcHearingSchedulerDetector.class
                                .getMethod("inspectDeadlineProjection")
                                .getAnnotation(Transactional.class))
                .isNotNull();
        assertThat(
                        JdbcHearingSchedulerDetector.class
                                .getMethod("inspectHandoffProjection")
                                .getAnnotation(Transactional.class))
                .isNotNull();
    }

    @Test
    void deadlineDetectorDistinguishesNoCandidateMatchAndMismatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcHearingSchedulerDetector detector = new JdbcHearingSchedulerDetector(jdbc);
        when(jdbc.queryForMap(JdbcHearingSchedulerDetector.DEADLINE_DETECTION_SQL))
                .thenReturn(
                        Map.<String, Object>of("candidate_count", 0L, "mismatch_count", 0L),
                        Map.<String, Object>of("candidate_count", 4L, "mismatch_count", 0L),
                        Map.<String, Object>of("candidate_count", 4L, "mismatch_count", 1L));

        assertThat(detector.inspectDeadlineProjection().outcome())
                .isEqualTo(DetectionOutcome.NO_CANDIDATE);
        assertThat(detector.inspectDeadlineProjection().outcome())
                .isEqualTo(DetectionOutcome.MATCH);
        var mismatch = detector.inspectDeadlineProjection();
        assertThat(mismatch.outcome()).isEqualTo(DetectionOutcome.MISMATCH);
        assertThat(mismatch.candidateCount()).isEqualTo(4);
        assertThat(mismatch.mismatchCount()).isEqualTo(1);
        assertThat(mismatch.evidenceHash()).hasSize(64);
    }

    @Test
    void deadlineDetectorStartsFromTheFullPersistedLegacyDecisionUniverse() {
        assertThat(JdbcHearingSchedulerDetector.DEADLINE_DETECTION_SQL)
                .contains(
                        "from hearing_flow_instance flow",
                        "flow.flow_status in ('ACTIVE', 'FAILED')",
                        "failed_run.run_status as agent_run_status",
                        "agent_run_status = 'FAILED'",
                        "dispute.current_deadline_at as case_deadline_at",
                        "least(",
                        "shared_deadline_at,",
                        "coalesce(case_deadline_at, shared_deadline_at)",
                        ") <= current_timestamp",
                        "case_deadline_at < shared_deadline_at",
                        "from hearing_flow_action party_action",
                        "party_action.participant_id = legacy_candidates.user_id",
                        "party_action.participant_id = legacy_candidates.merchant_id",
                        "left join hearing_temporal_projection projection",
                        "authority.process_revision = projection.process_revision",
                        "authority.room_revision = projection.room_revision",
                        "projection.temporal_workflow_id is not distinct from coalesce(",
                        "projection.temporal_run_id is not distinct from coalesce(",
                        "projection.temporal_build_or_deployment is not distinct from coalesce(",
                        "authority.lifecycle_status = 'ACTIVE'")
                .doesNotContain("limit ", "findTop");
    }

    @Test
    void handoffDetectorUsesTheExactV2HandoffBindingAggregates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcHearingSchedulerDetector detector = new JdbcHearingSchedulerDetector(jdbc);
        when(jdbc.queryForMap(JdbcHearingSchedulerDetector.HANDOFF_DETECTION_SQL))
                .thenReturn(Map.<String, Object>of("candidate_count", 3L, "mismatch_count", 0L));

        var detection = detector.inspectHandoffProjection();

        assertThat(detection.outcome()).isEqualTo(DetectionOutcome.MATCH);
        assertThat(detection.candidateCount()).isEqualTo(3);
        assertThat(detection.mismatchCount()).isZero();
        assertThat(JdbcHearingSchedulerDetector.HANDOFF_DETECTION_SQL)
                .contains(
                        "from hearing_flow_artifact draft",
                        "where draft.artifact_type = 'ADJUDICATION_DRAFT'",
                        "left join hearing_flow_instance flow",
                        "authoritative_stage not in ('HUMAN_REVIEW_OPEN', 'CLOSED')",
                        "projection_stage is distinct from authoritative_stage",
                        "projection_sequence is distinct from authoritative_sequence",
                        "left join hearing_temporal_projection projection",
                        "projection_id is null",
                        "authority_id is null",
                        "not exact_handoff_recorded",
                        "authority_lifecycle_status is distinct from 'ACTIVE'",
                        "authority_process_revision is distinct from projection_process_revision",
                        "authority_room_revision is distinct from projection_room_revision")
                .doesNotContain("limit ", "findTop");
    }

    @Test
    void completedHandoffAcceptsItsExactHistoricalTerminalWriterEpoch() {
        String detectionSql = JdbcHearingSchedulerDetector.HANDOFF_DETECTION_SQL;
        String closedBranch = detectionSql.substring(
                detectionSql.indexOf("authoritative_stage = 'CLOSED'"));

        assertThat(detectionSql).contains("'legacy-java.v1'");

        assertThat(closedBranch)
                .contains(
                        "authoritative_stage = 'CLOSED'",
                        "projection_writer_mode not in ('LEGACY', 'TEMPORAL')",
                        "authority_lifecycle_status = 'TERMINAL'",
                        "authority_process_revision = projection_process_revision",
                        "authority_room_revision = projection_room_revision",
                        "authority_process_revision = projection_process_revision + 1",
                        "authority_room_revision = projection_room_revision + 1")
                .doesNotContain("projection_writer_mode is distinct from 'TEMPORAL'");
    }

    @Test
    void missingAggregateAuthorityFailsClosedInsteadOfReportingZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcHearingSchedulerDetector detector = new JdbcHearingSchedulerDetector(jdbc);
        when(jdbc.queryForMap(JdbcHearingSchedulerDetector.DEADLINE_DETECTION_SQL))
                .thenReturn(Map.<String, Object>of("candidate_count", 0L));

        assertThatThrownBy(detector::inspectDeadlineProjection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mismatch_count");
    }
}
