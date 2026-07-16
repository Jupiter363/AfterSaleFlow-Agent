package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries an idempotent V2-to-review handoff if the original post-commit task failed. */
@Component
public class HearingReviewHandoffRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingReviewHandoffRecoveryScheduler.class);

    private final HearingFlowArtifactRepository artifactRepository;
    private final HearingReviewHandoffService handoffService;

    public HearingReviewHandoffRecoveryScheduler(
            HearingFlowArtifactRepository artifactRepository,
            HearingReviewHandoffService handoffService) {
        this.artifactRepository = artifactRepository;
        this.handoffService = handoffService;
    }

    @Scheduled(fixedDelayString = "${dispute.hearing-review-handoff-recovery-delay:PT30S}")
    public void recover() {
        artifactRepository
                .findTop50ByArtifactTypeOrderByCreatedAtDesc(
                        HearingArtifactType.ADJUDICATION_DRAFT)
                .forEach(this::recoverOne);
    }

    private void recoverOne(
            com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity
                    artifact) {
        try {
            handoffService.handoff(
                    artifact.getCaseId(), artifact.getId(), artifact.getContentHash());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "V2 review handoff recovery failed for case {}",
                    artifact.getCaseId(),
                    exception);
        }
    }
}
