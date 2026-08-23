package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Event;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Outcome;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerControl;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.Detection;
import com.example.dispute.workflow.recovery.hearing.HearingSchedulerDetector.DetectionOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Retries an idempotent V2-to-review handoff if the original post-commit task failed. */
@Component
public class HearingReviewHandoffRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingReviewHandoffRecoveryScheduler.class);

    private final HearingFlowArtifactRepository artifactRepository;
    private final HearingReviewHandoffService handoffService;
    private final HearingSchedulerControl control;
    private final HearingReliabilityObservationSink observations;
    private final HearingSchedulerDetector detector;

    public HearingReviewHandoffRecoveryScheduler(
            HearingFlowArtifactRepository artifactRepository,
            HearingReviewHandoffService handoffService) {
        this(
                artifactRepository,
                handoffService,
                HearingSchedulerControl.legacyExecutor(),
                HearingReliabilityObservationSink.noop(),
                HearingSchedulerDetector.unavailable());
    }

    @Autowired
    public HearingReviewHandoffRecoveryScheduler(
            HearingFlowArtifactRepository artifactRepository,
            HearingReviewHandoffService handoffService,
            @Value("${dispute.hearing-review-handoff-scheduler-mode:EXECUTOR}") String mode,
            @Value("${dispute.hearing-scheduler-writer-mode:LEGACY}") String writerMode,
            @Value("${dispute.hearing-legacy-work-drained:false}") boolean legacyWorkDrained,
            HearingReliabilityObservationSink observations,
            HearingSchedulerDetector detector) {
        this(
                artifactRepository,
                handoffService,
                HearingSchedulerControl.configured(mode, writerMode, legacyWorkDrained),
                observations,
                detector);
    }

    public HearingReviewHandoffRecoveryScheduler(
            HearingFlowArtifactRepository artifactRepository,
            HearingReviewHandoffService handoffService,
            HearingSchedulerControl control,
            HearingReliabilityObservationSink observations) {
        this(
                artifactRepository,
                handoffService,
                control,
                observations,
                HearingSchedulerDetector.unavailable());
    }

    public HearingReviewHandoffRecoveryScheduler(
            HearingFlowArtifactRepository artifactRepository,
            HearingReviewHandoffService handoffService,
            HearingSchedulerControl control,
            HearingReliabilityObservationSink observations,
            HearingSchedulerDetector detector) {
        this.artifactRepository =
                Objects.requireNonNull(artifactRepository, "artifactRepository must not be null");
        this.handoffService =
                Objects.requireNonNull(handoffService, "handoffService must not be null");
        this.control = Objects.requireNonNull(control, "control must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
    }

    @Scheduled(fixedDelayString = "${dispute.hearing-review-handoff-recovery-delay:PT30S}")
    public void recover() {
        switch (control.decision()) {
            case EXECUTE_LEGACY -> recoverLegacy();
            case DETECT_ONLY -> observeLegacyCandidateParity();
            case SKIP -> {
                // OFF is deliberately silent.
            }
        }
    }

    private void observeLegacyCandidateParity() {
        try {
            Detection detection = detector.inspectHandoffProjection();
            observations.record(Event.HANDOFF_SCHEDULER, detectionOutcome(detection.outcome()));
        } catch (RuntimeException exception) {
            observations.record(Event.HANDOFF_SCHEDULER, Outcome.FAILED);
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

    private void recoverLegacy() {
        artifactRepository
                .findTop50LegacyAdjudicationDrafts()
                .forEach(this::recoverOne);
        observations.record(Event.HANDOFF_SCHEDULER, Outcome.EXECUTED);
    }

    private void recoverOne(
            com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity
                    artifact) {
        try {
            handoffService.handoff(
                    artifact.getCaseId(), artifact.getId(), artifact.getContentHash());
        } catch (RuntimeException exception) {
            observations.record(Event.HANDOFF_SCHEDULER, Outcome.FAILED);
            LOGGER.warn(
                    "V2 review handoff recovery failed for case {}",
                    artifact.getCaseId(),
                    exception);
        }
    }
}
