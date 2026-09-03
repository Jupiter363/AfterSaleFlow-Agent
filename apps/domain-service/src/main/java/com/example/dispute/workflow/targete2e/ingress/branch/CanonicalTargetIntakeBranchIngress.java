package com.example.dispute.workflow.targete2e.ingress.branch;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;

/** Target-only adapter which records a branch intent; Temporal remains the only branch writer. */
public final class CanonicalTargetIntakeBranchIngress implements TargetIntakeBranchIngress {

    private static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;

    private final CaseCommandService commandService;
    private final IntakeImmutablePayloadPublisher payloadPublisher;
    private final ObjectMapper objectMapper;

    public CanonicalTargetIntakeBranchIngress(
            CaseCommandService commandService,
            IntakeImmutablePayloadPublisher payloadPublisher,
            ObjectMapper objectMapper) {
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.payloadPublisher = Objects.requireNonNull(payloadPublisher, "payloadPublisher");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public TargetIntakeBranchIngressReceipt accept(TargetIntakeBranchRequest request) {
        Objects.requireNonNull(request, "request");
        IntakeBranchCommand branch = request.command();
        JsonNode document = objectMapper.valueToTree(branch);
        byte[] canonicalPayload = ContractJson.canonicalize(document);
        String payloadHash = ContractJson.sha256Hex(document);
        IntakeImmutablePayloadPublisher.StoredPayload payload =
                payloadPublisher.publish(
                        new IntakeImmutablePayloadPublisher.PublishRequest(
                                branch.commandId(),
                                IntakeBranchCommand.SCHEMA_VERSION,
                                payloadHash,
                                canonicalPayload,
                                MAXIMUM_PAYLOAD_BYTES,
                                request.idempotencyKey()));
        assertPublished(branch, payload, payloadHash, canonicalPayload.length);

        CaseCommandAcceptance acceptance =
                commandService.accept(
                        request.caseId(),
                        branch.commandId(),
                        new AcceptCaseCommand(
                                branch.commandType(),
                                RoomType.INTAKE,
                                request.activation().roomEpoch(),
                                new PayloadRef(
                                        payload.schemaVersion(),
                                        payload.uri(),
                                        payload.contentSha256(),
                                        payload.sizeBytes()),
                                request.activation().processRevision(),
                                request.commandDeadlineAt()),
                        request.actor(),
                        request.traceId(),
                        request.idempotencyKey(),
                        null);
        return new TargetIntakeBranchIngressReceipt(
                acceptance.command().commandId(),
                payloadHash,
                acceptance.commandStatus(),
                acceptance.idempotentReplay(),
                acceptance.acceptedAt());
    }

    private static void assertPublished(
            IntakeBranchCommand branch,
            IntakeImmutablePayloadPublisher.StoredPayload payload,
            String hash,
            int sizeBytes) {
        if (payload == null
                || !branch.commandId().equals(payload.artifactId())
                || !IntakeBranchCommand.SCHEMA_VERSION.equals(payload.schemaVersion())
                || !hash.equals(payload.contentSha256())
                || !hash.equals(payload.objectVersion())
                || payload.sizeBytes() != sizeBytes
                || !payload.uri()
                        .endsWith(
                                "/"
                                        + IntakeBranchCommand.SCHEMA_VERSION
                                        + "/"
                                        + branch.commandId()
                                        + "/"
                                        + hash
                                        + ".json")) {
            throw new IllegalStateException("target Intake branch payload publication is inconsistent");
        }
    }
}
