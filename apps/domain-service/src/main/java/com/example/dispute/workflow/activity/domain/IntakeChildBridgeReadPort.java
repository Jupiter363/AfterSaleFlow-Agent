package com.example.dispute.workflow.activity.domain;

import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;

/**
 * Supplies authoritative bridge enrichment without granting the adapter repository or write access.
 * Implementations must use {@link ReadUnavailableException} only for temporary read failures.
 */
public interface IntakeChildBridgeReadPort {

    StartSource readStart(StartRequest request);

    CommandSource readCommand(CommandRequest request);

    DomainEventSource readDomainEvent(DomainEventRequest request);

    record StartSource(
            ActiveChildBinding persistedBinding,
            String provisioningRequestHash,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String initiatorActorScopeHash,
            String respondentActorScopeHash) {}

    record CommandSource(
            ActiveChildBinding persistedBinding,
            String commandId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            long sequence,
            CommandType commandType,
            String sourcePayloadHash,
            String sourceRequestHash,
            long processRevision,
            long roomRevision,
            IntakeParty party,
            String actorScopeHash,
            String operationKey,
            IntakeCommandExecutionContext executionContext) {}

    record DomainEventSource(
            ActiveChildBinding persistedBinding,
            String eventId,
            String sourceEventType,
            IntakeDomainEventType eventType,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            long sequence,
            String sourcePayloadHash,
            String eventRef,
            String eventHash,
            IntakeParty party,
            String commandId,
            String actorScopeHash,
            String operationKey,
            String requestHash,
            String resultHash,
            long processRevision,
            long roomRevision,
            IntakeAgentRunRef agentRunRef,
            IntakeGraphExecutionRef graphExecutionRef) {}

    final class ReadUnavailableException extends RuntimeException {

        public ReadUnavailableException(String message) {
            super(message);
        }

        public ReadUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
