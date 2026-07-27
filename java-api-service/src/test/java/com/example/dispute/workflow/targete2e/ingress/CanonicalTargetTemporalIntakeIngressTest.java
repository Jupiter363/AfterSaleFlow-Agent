package com.example.dispute.workflow.targete2e.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.PublishRequest;
import com.example.dispute.workflow.activity.intake.IntakeImmutablePayloadPublisher.StoredPayload;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionAuthority.AdmissionReceipt;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandAdmissionAuthority.AdmissionRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CanonicalTargetTemporalIntakeIngressTest {

    private static final String HASH = "c".repeat(64);

    @Mock private IntakeImmutablePayloadPublisher payloadPublisher;
    @Mock private CaseCommandService commandService;
    @Mock private TargetIntakeCommandAdmissionAuthority commandAdmissionAuthority;

    @Test
    void publishesCanonicalContentAddressedPayloadBeforeAcceptingOneTemporalCommand() {
        TargetIntakeActivationGrant grant =
                new TargetIntakeActivationGrant(
                        TargetIntakeActivationGrant.TARGET_LANE,
                        "p9act.v1." + "d".repeat(32),
                        HASH,
                        "tenant-target",
                        "CASE_TARGET_INGRESS",
                        7L,
                        11L,
                        13L,
                        "case/tenant-target/CASE_TARGET_INGRESS",
                        "target-control-build",
                        Instant.parse("2026-07-27T02:00:00Z"));
        TargetIntakeMessageRequest request = TestRequests.message(grant);
        when(payloadPublisher.publish(any()))
                .thenAnswer(
                        invocation -> {
                            PublishRequest published = invocation.getArgument(0);
                            return new StoredPayload(
                                    published.artifactId(),
                                    published.schemaVersion(),
                                    "minio://target-e2e/" + published.artifactId(),
                                    "version-1",
                                    published.contentSha256(),
                                    published.canonicalPayload().length);
                        });
        when(commandService.accept(
                        eq(request.caseId()),
                        eq("intake-message:" + request.messageId()),
                        any(),
                        eq(request.actor()),
                        eq(request.traceId()),
                        eq(request.idempotencyKey()),
                        eq(null)))
                .thenAnswer(
                        invocation -> {
                            AcceptCaseCommand command = invocation.getArgument(2);
                            return acceptance(request, command);
                        });
        when(commandAdmissionAuthority.admit(any()))
                .thenAnswer(
                        invocation -> {
                            AdmissionRequest admission = invocation.getArgument(0);
                            return new AdmissionReceipt(
                                    admission.activationId(),
                                    admission.manifestHash(),
                                    admission.commandId(),
                                    admission.roomEpoch(),
                                    admission.roomFencingToken(),
                                    Instant.parse("2026-07-27T01:00:01Z"),
                                    false);
                        });
        CanonicalTargetTemporalIntakeIngress ingress =
                new CanonicalTargetTemporalIntakeIngress(
                        payloadPublisher,
                        commandService,
                        commandAdmissionAuthority,
                        new ObjectMapper());

        TargetIntakeIngressReceipt receipt = ingress.accept(request);

        ArgumentCaptor<PublishRequest> publication = ArgumentCaptor.forClass(PublishRequest.class);
        verify(payloadPublisher).publish(publication.capture());
        String json = new String(publication.getValue().canonicalPayload(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(publication.getValue().schemaVersion())
                .isEqualTo(CanonicalTargetTemporalIntakeIngress.PAYLOAD_SCHEMA);
        assertThat(publication.getValue().contentSha256()).hasSize(64);
        assertThat(json)
                .contains("TARGET_E2E_CANDIDATE")
                .contains("CASE_TARGET_INGRESS")
                .doesNotContain("activation_token")
                .doesNotContain(request.traceId());

        ArgumentCaptor<AcceptCaseCommand> command = ArgumentCaptor.forClass(AcceptCaseCommand.class);
        verify(commandService)
                .accept(
                        eq(request.caseId()),
                        eq("intake-message:" + request.messageId()),
                        command.capture(),
                        eq(request.actor()),
                        eq(request.traceId()),
                        eq(request.idempotencyKey()),
                        eq(null));
        assertThat(command.getValue().commandType()).isEqualTo(CommandType.INTAKE_MESSAGE);
        assertThat(command.getValue().roomType()).isEqualTo(RoomType.INTAKE);
        assertThat(command.getValue().roomEpoch()).isEqualTo(7L);
        assertThat(command.getValue().expectedProcessRevision()).isEqualTo(13L);
        assertThat(command.getValue().payloadRef().sha256())
                .isEqualTo(publication.getValue().contentSha256());
        ArgumentCaptor<AdmissionRequest> admission =
                ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(commandAdmissionAuthority).admit(admission.capture());
        assertThat(admission.getValue().roomFencingToken()).isEqualTo(11L);
        assertThat(admission.getValue().manifestHash()).isEqualTo(HASH);
        assertThat(receipt.commandId()).isEqualTo("intake-message:" + request.messageId());
        assertThat(receipt.admittedAt())
                .isEqualTo(Instant.parse("2026-07-27T01:00:01Z"));
    }

    private static CaseCommandAcceptance acceptance(
            TargetIntakeMessageRequest request, AcceptCaseCommand command) {
        CaseCommandRef ref =
                new CaseCommandRef(
                        "case-command-ref.v1",
                        "intake-message:" + request.messageId(),
                        request.activation().tenantSurrogate(),
                        request.caseId(),
                        1L,
                        command.commandType(),
                        command.roomType(),
                        command.roomEpoch(),
                        new ActorRef("user-local", ActorRole.USER, List.of()),
                        new PayloadRef(
                                command.payloadRef().schemaVersion(),
                                command.payloadRef().uri(),
                                command.payloadRef().sha256(),
                                command.payloadRef().sizeBytes()),
                        command.expectedProcessRevision(),
                        request.createdAt(),
                        command.deadlineAt(),
                        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                        "e".repeat(64));
        return new CaseCommandAcceptance(
                ref, "PENDING_ORCHESTRATION", Instant.parse("2026-07-27T01:00:00Z"), false);
    }
}
