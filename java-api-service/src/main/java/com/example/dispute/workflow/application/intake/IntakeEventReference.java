package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.Objects;

/** Immutable metadata for one ordered Intake event; no event text is retained here. */
public record IntakeEventReference(
        String bindingId,
        String threadRegistrationId,
        String eventId,
        String messageId,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long fencingToken,
        String threadId,
        String actorScopeHash,
        String agentSessionId,
        RoomGraphCommand.SnapshotRef payloadRef,
        String objectVersion,
        long sequenceNo,
        long domainRevision,
        Audience audience,
        Instant occurredAt,
        Instant createdAt) {

    public IntakeEventReference {
        bindingId = IntakeContractSupport.identifier(bindingId, "bindingId");
        threadRegistrationId = IntakeContractSupport.identifier(
                threadRegistrationId, "threadRegistrationId");
        eventId = IntakeContractSupport.identifier(eventId, "eventId");
        messageId = IntakeContractSupport.identifier(messageId, "messageId");
        tenantSurrogate = IntakeContractSupport.identifier(tenantSurrogate, "tenantSurrogate");
        caseId = IntakeContractSupport.identifier(caseId, "caseId");
        IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
        IntakeContractSupport.positive(fencingToken, "fencingToken");
        threadId = IntakeContractSupport.threadId(threadId);
        actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
        agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
        payloadRef = Objects.requireNonNull(payloadRef, "payloadRef must not be null");
        if (!"intake-turn-event.v2".equals(payloadRef.schemaVersion())) {
            throw new IllegalArgumentException("event payload schema must be intake-turn-event.v2");
        }
        IntakeContractSupport.identifier(payloadRef.artifactId(), "artifactId");
        IntakeContractSupport.immutableUri(payloadRef.uri());
        IntakeContractSupport.sha256(payloadRef.sha256(), "eventHash");
        if (payloadRef.sizeBytes() <= 0
                || payloadRef.sizeBytes() > IntakeContractSupport.EVENT_MAX_BYTES) {
            throw new IllegalArgumentException("event size exceeds 32 KiB");
        }
        objectVersion = IntakeContractSupport.boundedText(
                objectVersion, 128, "objectVersion");
        IntakeContractSupport.positive(sequenceNo, "sequenceNo");
        IntakeContractSupport.nonNegative(domainRevision, "domainRevision");
        if (audience != Audience.USER && audience != Audience.MERCHANT) {
            throw new IllegalArgumentException("event audience must be USER or MERCHANT");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
