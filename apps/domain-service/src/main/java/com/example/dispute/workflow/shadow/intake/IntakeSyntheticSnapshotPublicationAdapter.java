package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.SnapshotInput;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import java.util.Objects;

/** Publishes a source-loaded private snapshot through the authoritative immutable publisher. */
public final class IntakeSyntheticSnapshotPublicationAdapter
        implements IntakeSnapshotPublicationPort {

    private final IntakeSyntheticRuntimeSource source;
    private final IntakeDomainSnapshotPublisher publisher;

    public IntakeSyntheticSnapshotPublicationAdapter(
            IntakeSyntheticRuntimeSource source, IntakeDomainSnapshotPublisher publisher) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public SnapshotPublicationReceipt publish(SnapshotPublicationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SnapshotInput input =
                Objects.requireNonNull(source.loadSnapshot(request), "snapshot input must not be null");
        IntakeSyntheticRuntimeAuthority.requireMatches(input.authority(), request);
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.domainRevision(), request.domainRevision(), "domain revision");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.publication().domainRevision(), request.domainRevision(), "snapshot domain revision");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.publication().roomRevision(),
                request.envelope().roomRevision(),
                "snapshot room revision");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                IntakeDomainSnapshotPublisher.operationKey(input.publication()),
                request.operationKey(),
                "snapshot operation key");
        IntakeSyntheticRuntimeAuthority.requireRegistration(
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                input.publication().threadBinding());

        IntakeSnapshotReference reference = Objects.requireNonNull(
                        publisher.publish(input.publication()), "snapshot publication receipt must not be null")
                .value();
        requireReference(reference, request);
        SnapshotRef payload = reference.payloadRef();
        return new SnapshotPublicationReceipt(
                "intake-snapshot-publication-receipt.v1",
                new OperationReceipt(
                        "intake-operation-receipt.v1",
                        request.operationKey(),
                        request.requestHash(),
                        payload.sha256(),
                        request.envelope().processRevision(),
                        request.envelope().roomRevision()),
                new ImmutablePayloadRef(
                        "immutable-payload-ref.v1",
                        payload.artifactId(),
                        "INTAKE_SNAPSHOT",
                        payload.schemaVersion(),
                        payload.uri(),
                        reference.objectVersion(),
                        payload.sha256(),
                        payload.sizeBytes()),
                reference.domainRevision());
    }

    private static void requireReference(
            IntakeSnapshotReference reference, SnapshotPublicationRequest request) {
        Objects.requireNonNull(reference, "published snapshot reference must not be null");
        var envelope = request.envelope();
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.tenantSurrogate(), envelope.tenantSurrogate(), "published snapshot tenant");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.caseId(), envelope.caseId(), "published snapshot case");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.roomEpoch(), envelope.roomEpoch(), "published snapshot room epoch");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.fencingToken(), envelope.fencingToken(), "published snapshot fence");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.threadId(), request.threadId(), "published snapshot thread");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.actorScopeHash(),
                envelope.actorScopeHash(),
                "published snapshot actor scope");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.agentSessionId(),
                request.agentSessionId(),
                "published snapshot Agent Session");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.domainRevision(),
                request.domainRevision(),
                "published snapshot domain revision");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                reference.roomRevision(),
                envelope.roomRevision(),
                "published snapshot room revision");
    }
}
