package com.example.dispute.workflow.recovery.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.DetectionOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcHearingSchedulerDetectorTest {

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
    void handoffDetectorUsesTheExactV2HandoffBindingAggregates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcHearingSchedulerDetector detector = new JdbcHearingSchedulerDetector(jdbc);
        when(jdbc.queryForMap(JdbcHearingSchedulerDetector.HANDOFF_DETECTION_SQL))
                .thenReturn(Map.<String, Object>of("candidate_count", 3L, "mismatch_count", 0L));

        var detection = detector.inspectHandoffProjection();

        assertThat(detection.outcome()).isEqualTo(DetectionOutcome.MATCH);
        assertThat(detection.candidateCount()).isEqualTo(3);
        assertThat(detection.mismatchCount()).isZero();
    }
}
