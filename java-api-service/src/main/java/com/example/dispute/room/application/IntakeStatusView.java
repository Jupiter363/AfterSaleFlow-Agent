package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionView;
import java.time.OffsetDateTime;

public record IntakeStatusView(
        String caseId,
        ActorRole initiatorRole,
        ActorRole respondentRole,
        String initiatorStatus,
        String respondentStatus,
        boolean currentActorCompleted,
        boolean canUseIntake,
        boolean canEnterEvidence,
        OffsetDateTime evidenceDeadlineAt,
        IntakeProcessProjectionView processProjection) {

    public IntakeStatusView(
            String caseId,
            ActorRole initiatorRole,
            ActorRole respondentRole,
            String initiatorStatus,
            String respondentStatus,
            boolean currentActorCompleted,
            boolean canUseIntake,
            boolean canEnterEvidence,
            OffsetDateTime evidenceDeadlineAt) {
        this(
                caseId,
                initiatorRole,
                respondentRole,
                initiatorStatus,
                respondentStatus,
                currentActorCompleted,
                canUseIntake,
                canEnterEvidence,
                evidenceDeadlineAt,
                IntakeProcessProjectionView.legacyUnavailable(null));
    }
}
