package com.example.dispute.workflow.targete2e.ingress.rooms;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;

/**
 * Target-only pre-admission boundary for non-Intake graph commands. Implementations append their
 * immutable graph hand-off before the normal command service accepts and outboxes the command.
 */
@FunctionalInterface
public interface TargetRoomCommandIngress {
    void materialize(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId);
}
