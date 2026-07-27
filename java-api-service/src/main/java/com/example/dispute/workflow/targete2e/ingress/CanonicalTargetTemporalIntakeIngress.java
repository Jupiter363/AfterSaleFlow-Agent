package com.example.dispute.workflow.targete2e.ingress;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.StoredPayload;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionAuthority.AdmissionReceipt;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionAuthority.AdmissionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Target-only adapter. It is deliberately not component-scanned; target assembly must opt in. */
public final class CanonicalTargetTemporalIntakeIngress implements TargetTemporalIntakeIngress {

    public static final String PAYLOAD_SCHEMA = "target-e2e-intake-message.v1";
    private static final int MAXIMUM_PAYLOAD_BYTES = 1_048_576;
    private static final Duration COMMAND_DEADLINE = Duration.ofHours(1);

    private final IntakeImmutablePayloadPublisher payloadPublisher;
    private final CaseCommandService commandService;
    private final TargetIntakeCommandAdmissionAuthority commandAdmissionAuthority;
    private final ObjectMapper objectMapper;

    public CanonicalTargetTemporalIntakeIngress(
            IntakeImmutablePayloadPublisher payloadPublisher,
            CaseCommandService commandService,
            TargetIntakeCommandAdmissionAuthority commandAdmissionAuthority,
            ObjectMapper objectMapper) {
        this.payloadPublisher = payloadPublisher;
        this.commandService = commandService;
        this.commandAdmissionAuthority = commandAdmissionAuthority;
        this.objectMapper = objectMapper;
    }

    @Override
    public TargetIntakeIngressReceipt accept(TargetIntakeMessageRequest request) {
        byte[] canonicalPayload = canonicalPayload(request);
        String contentSha256 = sha256(canonicalPayload);
        String artifactId = "intake-message:" + request.messageId();
        StoredPayload stored =
                payloadPublisher.publish(
                        new PublishRequest(
                                artifactId,
                                PAYLOAD_SCHEMA,
                                contentSha256,
                                canonicalPayload,
                                MAXIMUM_PAYLOAD_BYTES,
                                "target-intake-message:" + request.idempotencyKey()));
        verifyStoredPayload(stored, artifactId, contentSha256, canonicalPayload.length);

        String commandId = artifactId;
        AdmissionRequest admissionRequest =
                new AdmissionRequest(
                        request.activation().lane(),
                        request.activation().activationId(),
                        request.activation().manifestHash(),
                        request.activation().tenantSurrogate(),
                        request.caseId(),
                        request.activation().roomEpoch(),
                        request.activation().roomFencingToken(),
                        request.activation().processRevision(),
                        commandId,
                        contentSha256,
                        request.createdAt(),
                        request.activation().expiresAt());
        AdmissionReceipt admission = commandAdmissionAuthority.admit(admissionRequest);
        if (admission == null) {
            throw new IllegalStateException("pre-cutoff command admission was not recorded");
        }
        admission.assertMatches(admissionRequest);
        CaseCommandAcceptance acceptance =
                commandService.accept(
                        request.caseId(),
                        commandId,
                        new AcceptCaseCommand(
                                CommandType.INTAKE_MESSAGE,
                                RoomType.INTAKE,
                                request.activation().roomEpoch(),
                                new PayloadRef(
                                        stored.schemaVersion(),
                                        stored.uri(),
                                        stored.contentSha256(),
                                        stored.sizeBytes()),
                                request.activation().processRevision(),
                                request.createdAt().plus(COMMAND_DEADLINE)),
                        request.actor(),
                        request.traceId(),
                        request.idempotencyKey(),
                        null);
        return new TargetIntakeIngressReceipt(
                acceptance.command().commandId(),
                contentSha256,
                acceptance.commandStatus(),
                acceptance.idempotentReplay(),
                admission.admittedAt());
    }

    private byte[] canonicalPayload(TargetIntakeMessageRequest request) {
        Map<String, Object> authority = new TreeMap<>();
        authority.put("activation_id", request.activation().activationId());
        authority.put("activation_manifest_hash", request.activation().manifestHash());
        authority.put("lane", request.activation().lane());
        authority.put("process_revision", request.activation().processRevision());
        authority.put("room_epoch", request.activation().roomEpoch());
        authority.put("room_fencing_token", request.activation().roomFencingToken());
        authority.put("temporal_build_id", request.activation().temporalBuildId());
        authority.put("temporal_workflow_id", request.activation().temporalWorkflowId());
        authority.put("tenant_surrogate", request.activation().tenantSurrogate());

        Map<String, Object> payload = new TreeMap<>();
        payload.put("actor_id", request.actor().actorId());
        payload.put("actor_role", request.actor().role().name());
        payload.put("attachment_refs", request.attachmentRefs());
        payload.put("authority", authority);
        payload.put("case_id", request.caseId());
        payload.put("created_at", request.createdAt().toString());
        payload.put("idempotency_key", request.idempotencyKey());
        payload.put("message_id", request.messageId());
        payload.put("message_type", request.messageType().name());
        payload.put("room_id", request.roomId());
        payload.put("schema_version", PAYLOAD_SCHEMA);
        payload.put("text", request.text());
        return ContractJson.canonicalize(objectMapper.valueToTree(payload));
    }

    private static void verifyStoredPayload(
            StoredPayload stored, String artifactId, String contentSha256, long sizeBytes) {
        if (stored == null
                || !artifactId.equals(stored.artifactId())
                || !PAYLOAD_SCHEMA.equals(stored.schemaVersion())
                || !contentSha256.equals(stored.contentSha256())
                || sizeBytes != stored.sizeBytes()) {
            throw new IllegalStateException("immutable payload receipt does not match the request");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
