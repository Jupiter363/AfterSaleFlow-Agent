package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.CommandSource;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;

/** Reloads the immutable V043_3 signed synthetic admission for one inert Intake command. */
public interface SignedSyntheticIntakeCommandAdmissionLookup {

    PersistedCommandAdmission require(CommandRequest request, CommandSource source);

    record PersistedCommandAdmission(
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String commandId,
            long commandSequence,
            IntakeCommandType commandType,
            IntakeParty party,
            String payloadRef,
            String payloadHash,
            String operationKey,
            String actorScopeHash,
            String requestHash,
            long processRevision,
            long roomRevision,
            String threadId,
            String agentSessionId,
            long deadlineEpochMillis,
            RetryBudget retryBudget) {}
}
