package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.application.HearingFlowDeadlineScheduler;
import com.example.dispute.hearing.application.HearingFlowRuntimeService;
import com.example.dispute.hearing.application.HearingReviewHandoffRecoveryScheduler;
import com.example.dispute.hearing.application.HearingReviewHandoffService;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Event;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Outcome;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl.Decision;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl.LegacyWorkState;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl.SchedulerMode;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.Detection;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.DetectorKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class HearingSchedulerModeTest {

    @Test
    void legacyExecutorPreservesBothCurrentJavaWriters() {
        HearingFlowRuntimeService runtime = mock(HearingFlowRuntimeService.class);
        HearingFlowArtifactRepository artifacts = mock(HearingFlowArtifactRepository.class);
        HearingReviewHandoffService handoff = mock(HearingReviewHandoffService.class);
        when(artifacts.findTop50LegacyAdjudicationDrafts())
                .thenReturn(List.of());

        new HearingFlowDeadlineScheduler(
                        runtime,
                        HearingSchedulerControl.legacyExecutor(),
                        HearingReliabilityObservationSink.noop())
                .expireDueStages();
        new HearingReviewHandoffRecoveryScheduler(
                        artifacts,
                        handoff,
                        HearingSchedulerControl.legacyExecutor(),
                        HearingReliabilityObservationSink.noop())
                .recover();

        verify(runtime).expireDuePartyStages();
        verify(artifacts).findTop50LegacyAdjudicationDrafts();
    }

    @Test
    void detectorAndOffNeverCallLegacyWriterEntrypoints() {
        for (SchedulerMode mode : List.of(SchedulerMode.DETECTOR, SchedulerMode.OFF)) {
            HearingFlowRuntimeService runtime = mock(HearingFlowRuntimeService.class);
            HearingFlowArtifactRepository artifacts = mock(HearingFlowArtifactRepository.class);
            HearingReviewHandoffService handoff = mock(HearingReviewHandoffService.class);
            List<String> metrics = new ArrayList<>();
            HearingReliabilityObservationSink sink =
                    (event, outcome) -> metrics.add(event.name() + ":" + outcome.name());
            HearingSchedulerControl control =
                    mode == SchedulerMode.DETECTOR
                            ? HearingSchedulerControl.futureTemporalDetector()
                            : HearingSchedulerControl.drainedOff();
            HearingSchedulerDetector detector = mock(HearingSchedulerDetector.class);
            when(detector.inspectDeadlineProjection())
                    .thenReturn(Detection.fromCounts(DetectorKind.DEADLINE_PROJECTION, 2, 0));
            when(detector.inspectHandoffProjection())
                    .thenReturn(Detection.fromCounts(DetectorKind.HANDOFF_PROJECTION, 2, 1));

            new HearingFlowDeadlineScheduler(runtime, control, sink, detector).expireDueStages();
            new HearingReviewHandoffRecoveryScheduler(artifacts, handoff, control, sink, detector)
                    .recover();

            verify(runtime, never()).expireDuePartyStages();
            verify(artifacts, never()).findTop50LegacyAdjudicationDrafts();
            if (mode == SchedulerMode.DETECTOR) {
                verify(detector).inspectDeadlineProjection();
                verify(detector).inspectHandoffProjection();
                assertThat(metrics)
                        .containsExactly(
                                Event.DEADLINE_SCHEDULER.name() + ":" + Outcome.MATCH.name(),
                                Event.HANDOFF_SCHEDULER.name() + ":" + Outcome.DIFFERENT.name());
            } else {
                verify(detector, never()).inspectDeadlineProjection();
                verify(detector, never()).inspectHandoffProjection();
                assertThat(metrics).isEmpty();
            }
        }
    }

    @Test
    void activeLegacyWorkCannotBeStrandedByDetectorOrOff() {
        assertThatThrownBy(
                        () ->
                                new HearingSchedulerControl(
                                        SchedulerMode.DETECTOR,
                                        HearingWriterMode.LEGACY,
                                        LegacyWorkState.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active legacy Hearing work requires");
        assertThatThrownBy(
                        () ->
                                new HearingSchedulerControl(
                                        SchedulerMode.OFF,
                                        HearingWriterMode.LEGACY,
                                        LegacyWorkState.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active legacy Hearing work requires");
    }

    @Test
    void temporalExecutorAndUnknownConfigurationFailClosed() {
        assertThatThrownBy(
                        () ->
                                new HearingSchedulerControl(
                                        SchedulerMode.EXECUTOR,
                                        HearingWriterMode.TEMPORAL,
                                        LegacyWorkState.DRAINED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot use the legacy scheduler executor");
        assertThatThrownBy(() -> HearingSchedulerControl.configured("writer", "legacy", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid Hearing scheduler authority");
        assertThat(HearingSchedulerControl.configured("detector", "temporal", true).decision())
                .isEqualTo(Decision.DETECT_ONLY);
    }

    @Test
    void drainedOffPreservesTemporalWriterAndRollsBackOnlyToDetector() {
        HearingSchedulerControl off = HearingSchedulerControl.drainedOff();

        assertThat(off.mode()).isEqualTo(SchedulerMode.OFF);
        assertThat(off.writerMode()).isEqualTo(HearingWriterMode.TEMPORAL);
        assertThat(off.legacyWorkState()).isEqualTo(LegacyWorkState.DRAINED);
        assertThat(off.decision()).isEqualTo(Decision.SKIP);
        assertThat(HearingSchedulerControl.configured("off", "temporal", true)).isEqualTo(off);
        assertThat(HearingSchedulerControl.futureTemporalDetector().writerMode())
                .isEqualTo(off.writerMode());
        assertThatThrownBy(() -> HearingSchedulerControl.configured("off", "legacy", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid Hearing scheduler authority");
    }
}
