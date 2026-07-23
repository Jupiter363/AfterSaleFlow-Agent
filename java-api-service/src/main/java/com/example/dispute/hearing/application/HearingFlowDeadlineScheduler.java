package com.example.dispute.hearing.application;

import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Event;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Outcome;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.Detection;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.DetectionOutcome;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Converts both missing party actions to terminal timeout rows at the shared deadline. */
@Component
public class HearingFlowDeadlineScheduler {

    private final HearingFlowRuntimeService runtimeService;
    private final HearingSchedulerControl control;
    private final HearingReliabilityObservationSink observations;
    private final HearingSchedulerDetector detector;

    public HearingFlowDeadlineScheduler(HearingFlowRuntimeService runtimeService) {
        this(
                runtimeService,
                HearingSchedulerControl.legacyExecutor(),
                HearingReliabilityObservationSink.noop(),
                HearingSchedulerDetector.unavailable());
    }

    @Autowired
    public HearingFlowDeadlineScheduler(
            HearingFlowRuntimeService runtimeService,
            @Value("${dispute.hearing-flow-timeout-scheduler-mode:EXECUTOR}") String mode,
            @Value("${dispute.hearing-scheduler-writer-mode:LEGACY}") String writerMode,
            @Value("${dispute.hearing-legacy-work-drained:false}") boolean legacyWorkDrained,
            HearingReliabilityObservationSink observations,
            HearingSchedulerDetector detector) {
        this(
                runtimeService,
                HearingSchedulerControl.configured(mode, writerMode, legacyWorkDrained),
                observations,
                detector);
    }

    public HearingFlowDeadlineScheduler(
            HearingFlowRuntimeService runtimeService,
            HearingSchedulerControl control,
            HearingReliabilityObservationSink observations) {
        this(runtimeService, control, observations, HearingSchedulerDetector.unavailable());
    }

    public HearingFlowDeadlineScheduler(
            HearingFlowRuntimeService runtimeService,
            HearingSchedulerControl control,
            HearingReliabilityObservationSink observations,
            HearingSchedulerDetector detector) {
        this.runtimeService = Objects.requireNonNull(runtimeService, "runtimeService must not be null");
        this.control = Objects.requireNonNull(control, "control must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
    }

    @Scheduled(fixedDelayString = "${dispute.hearing-flow-timeout-scan-delay:PT15S}")
    public void expireDueStages() {
        switch (control.decision()) {
            case EXECUTE_LEGACY -> executeLegacyScan();
            case DETECT_ONLY -> inspectTemporalProjection();
            case SKIP -> {
                // OFF is deliberately silent.
            }
        }
    }

    private void inspectTemporalProjection() {
        try {
            Detection detection = detector.inspectDeadlineProjection();
            observations.record(Event.DEADLINE_SCHEDULER, detectionOutcome(detection.outcome()));
        } catch (RuntimeException exception) {
            observations.record(Event.DEADLINE_SCHEDULER, Outcome.FAILED);
            throw exception;
        }
    }

    private static Outcome detectionOutcome(DetectionOutcome outcome) {
        return switch (outcome) {
            case NO_CANDIDATE -> Outcome.NO_CANDIDATE;
            case MATCH -> Outcome.MATCH;
            case MISMATCH -> Outcome.DIFFERENT;
        };
    }

    private void executeLegacyScan() {
        try {
            runtimeService.expireDuePartyStages();
            observations.record(Event.DEADLINE_SCHEDULER, Outcome.EXECUTED);
        } catch (RuntimeException exception) {
            observations.record(Event.DEADLINE_SCHEDULER, Outcome.FAILED);
            throw exception;
        }
    }
}
