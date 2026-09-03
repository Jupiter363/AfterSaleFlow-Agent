package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.StoredPayload;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;

final class IntakePayloadReferences {

    private IntakePayloadReferences() {}

    static RoomGraphCommand.SnapshotRef requireExact(
            PublishRequest request, StoredPayload stored) {
        if (stored == null
                || !request.artifactId().equals(stored.artifactId())
                || !request.schemaVersion().equals(stored.schemaVersion())
                || !request.contentSha256().equals(stored.contentSha256())
                || request.canonicalPayload().length != stored.sizeBytes()) {
            throw new IntakeGraphBindingConflictException(
                    "immutable payload receipt differs from the published contract");
        }
        IntakeContractSupport.identifier(stored.artifactId(), "artifactId");
        IntakeContractSupport.immutableUri(stored.uri());
        IntakeContractSupport.boundedText(stored.objectVersion(), 128, "objectVersion");
        IntakeContractSupport.sha256(stored.contentSha256(), "contentSha256");
        return new RoomGraphCommand.SnapshotRef(
                stored.artifactId(),
                stored.schemaVersion(),
                stored.uri(),
                stored.contentSha256(),
                stored.sizeBytes());
    }
}
