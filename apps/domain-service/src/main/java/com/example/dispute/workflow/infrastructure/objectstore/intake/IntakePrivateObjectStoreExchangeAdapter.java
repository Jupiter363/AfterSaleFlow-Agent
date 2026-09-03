package com.example.dispute.workflow.infrastructure.objectstore.intake;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.StoredPayload;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeObjectStore;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangePayloadObjectStoreGateway;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangePayloadObjectStoreGateway.ReadRequest;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeUris;
import java.util.Objects;

/** Private object-store adapter used only after Java exchange authority succeeds. */
public final class IntakePrivateObjectStoreExchangeAdapter implements IntakeExchangeObjectStore {

    private final IntakeExchangePayloadObjectStoreGateway payloadGateway;
    private final IntakeImmutablePayloadPublisher proposalPublisher;

    public IntakePrivateObjectStoreExchangeAdapter(
            IntakeExchangePayloadObjectStoreGateway payloadGateway,
            IntakeImmutablePayloadPublisher proposalPublisher) {
        this.payloadGateway = Objects.requireNonNull(payloadGateway, "payloadGateway");
        this.proposalPublisher = Objects.requireNonNull(proposalPublisher, "proposalPublisher");
    }

    @Override
    public LoadedPayload load(PayloadLoadGrant grant) {
        Objects.requireNonNull(grant, "grant");
        var ref = grant.request().objectRef();
        var request = new ReadRequest(
                ref.artifactId(),
                ref.schemaVersion(),
                ref.uri(),
                grant.objectVersion(),
                ref.sha256(),
                ref.sizeBytes());
        var stored = Objects.requireNonNull(
                payloadGateway.readExact(request), "exchange payload store receipt");
        requireExactPayload(request, stored);
        return new LoadedPayload(
                stored.artifactId(),
                stored.schemaVersion(),
                stored.uri(),
                stored.objectVersion(),
                stored.sha256(),
                stored.sizeBytes(),
                stored.canonicalPayload());
    }

    @Override
    public StoredProposal put(ProposalPutGrant grant, byte[] canonicalProposal) {
        Objects.requireNonNull(grant, "grant");
        var proposal = grant.request().proposal();
        var publishRequest = new PublishRequest(
                proposal.artifactId(),
                proposal.schemaVersion(),
                proposal.sha256(),
                canonicalProposal,
                IntakeExchangeContract.PROPOSAL_MAX_BYTES,
                grant.request().idempotencyKey());
        StoredPayload stored = Objects.requireNonNull(
                proposalPublisher.publish(publishRequest), "exchange proposal store receipt");
        requireExactProposal(publishRequest, stored);
        return new StoredProposal(
                stored.artifactId(),
                stored.schemaVersion(),
                stored.uri(),
                stored.objectVersion(),
                stored.contentSha256(),
                stored.sizeBytes());
    }

    private static void requireExactPayload(
            ReadRequest request, IntakeExchangePayloadObjectStoreGateway.StoredPayload stored) {
        if (!request.artifactId().equals(stored.artifactId())
                || !request.schemaVersion().equals(stored.schemaVersion())
                || !request.uri().equals(stored.uri())
                || !request.objectVersion().equals(stored.objectVersion())
                || !request.sha256().equals(stored.sha256())
                || request.sizeBytes() != stored.sizeBytes()
                || stored.canonicalPayload() == null
                || stored.canonicalPayload().length != stored.sizeBytes()) {
            throw new IllegalStateException(
                    "payload object store returned a non-exact immutable receipt");
        }
        IntakeExchangeUris.requireCanonical(stored.uri());
        IntakeExchangeContract.identifier(stored.objectVersion(), "objectVersion");
    }

    private static void requireExactProposal(PublishRequest request, StoredPayload stored) {
        if (!request.artifactId().equals(stored.artifactId())
                || !request.schemaVersion().equals(stored.schemaVersion())
                || !request.contentSha256().equals(stored.contentSha256())
                || request.canonicalPayload().length != stored.sizeBytes()) {
            throw new IllegalStateException(
                    "proposal object store returned a non-exact immutable receipt");
        }
        IntakeExchangeUris.requireCanonical(stored.uri());
        IntakeExchangeContract.identifier(stored.objectVersion(), "objectVersion");
    }
}
