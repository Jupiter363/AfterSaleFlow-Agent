package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.Objects;

/** Immutable metadata for the single actor-filtered initialization snapshot. */
public record IntakeSnapshotReference(
        String bindingId,
        String threadRegistrationId,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long fencingToken,
        String threadId,
        String actorScopeHash,
        String agentSessionId,
        RoomGraphCommand.SnapshotRef payloadRef,
        String objectVersion,
        long domainRevision,
        long roomRevision,
        long projectionRevision,
        Instant createdAt) {

    public IntakeSnapshotReference {
        bindingId = IntakeContractSupport.identifier(bindingId, "bindingId");
        threadRegistrationId = IntakeContractSupport.identifier(
                threadRegistrationId, "threadRegistrationId");
        tenantSurrogate = IntakeContractSupport.identifier(tenantSurrogate, "tenantSurrogate");
        caseId = IntakeContractSupport.identifier(caseId, "caseId");
        IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
        IntakeContractSupport.positive(fencingToken, "fencingToken");
        threadId = IntakeContractSupport.threadId(threadId);
        actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
        agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
        payloadRef = Objects.requireNonNull(payloadRef, "payloadRef must not be null");
        if (!"intake-domain-snapshot.v2".equals(payloadRef.schemaVersion())) {
            throw new IllegalArgumentException(
                    "initial payload schema must be intake-domain-snapshot.v2");
        }
        IntakeContractSupport.identifier(payloadRef.artifactId(), "artifactId");
        IntakeContractSupport.immutableUri(payloadRef.uri());
        IntakeContractSupport.sha256(payloadRef.sha256(), "snapshotHash");
        if (payloadRef.sizeBytes() <= 0
                || payloadRef.sizeBytes() > IntakeContractSupport.SNAPSHOT_MAX_BYTES) {
            throw new IllegalArgumentException("snapshot size exceeds 256 KiB");
        }
        objectVersion = IntakeContractSupport.boundedText(
                objectVersion, 128, "objectVersion");
        IntakeContractSupport.nonNegative(domainRevision, "domainRevision");
        IntakeContractSupport.nonNegative(roomRevision, "roomRevision");
        IntakeContractSupport.nonNegative(projectionRevision, "projectionRevision");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
