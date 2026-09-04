package com.example.dispute.workflow.runtime.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeMaterializer;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeMaterializer.MaterializedIntake;
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
    private static final String COMMAND_ID = "intake-message:0123456789abcdef0123456789abcdef";
    private static final String RUN_ID = "target-intake-run:0123456789abcdef0123456789abcdef";
    private static final Instant ADMITTED_AT = Instant.parse("2026-07-27T01:00:01Z");
    private static final Instant MATERIAL_DEADLINE = Instant.parse("2026-07-27T01:45:00Z");

    @Mock private CaseCommandService commandService;
    @Mock private TargetIntakeMaterializer materializer;

    @Test
    void acceptsOneTemporalCommandBoundToTheMaterializedEvent() {
        TargetIntakeActivationGrant grant = grant();
        TargetIntakeMessageRequest request = TestRequests.message(grant);
        SnapshotRef event =
                new SnapshotRef(
                        "target-intake-event-1",
                        "intake-turn-event.v2",
                        "minio://production-runtime-intake-activation/browser-messages/event-1",
                        HASH,
                        512);
        when(materializer.materialize(request))
                .thenReturn(
                        new MaterializedIntake(
                                COMMAND_ID,
                                RUN_ID,
                                event,
                                ADMITTED_AT,
                                MATERIAL_DEADLINE));
        when(commandService.accept(
                        eq(request.caseId()),
                        eq(COMMAND_ID),
                        any(),
                        eq(request.actor()),
                        eq(request.traceId()),
                        eq(request.idempotencyKey()),
                        eq(null)))
                .thenAnswer(
                        invocation -> acceptance(request, invocation.getArgument(2), COMMAND_ID));
        CanonicalTargetTemporalIntakeIngress ingress =
                new CanonicalTargetTemporalIntakeIngress(commandService, materializer);

        TargetIntakeIngressReceipt receipt = ingress.accept(request);

        verify(materializer).materialize(request);
        ArgumentCaptor<AcceptCaseCommand> command = ArgumentCaptor.forClass(AcceptCaseCommand.class);
        verify(commandService)
                .accept(
                        eq(request.caseId()),
                        eq(COMMAND_ID),
                        command.capture(),
                        eq(request.actor()),
                        eq(request.traceId()),
                        eq(request.idempotencyKey()),
                        eq(null));
        assertThat(command.getValue().commandType()).isEqualTo(CommandType.INTAKE_MESSAGE);
        assertThat(command.getValue().roomType()).isEqualTo(RoomType.INTAKE);
        assertThat(command.getValue().roomEpoch()).isEqualTo(7L);
        assertThat(command.getValue().expectedProcessRevision()).isEqualTo(13L);
        assertThat(command.getValue().deadlineAt())
                .isEqualTo(MATERIAL_DEADLINE)
                .isNotEqualTo(request.commandDeadlineAt());
        assertThat(command.getValue().payloadRef().uri()).isEqualTo(event.uri());
        assertThat(command.getValue().payloadRef().sha256()).isEqualTo(event.sha256());
        assertThat(receipt.commandId()).isEqualTo(COMMAND_ID);
        assertThat(receipt.runId()).isEqualTo(RUN_ID);
        assertThat(receipt.payloadSha256()).isEqualTo(HASH);
        assertThat(receipt.admittedAt()).isEqualTo(ADMITTED_AT);
    }

    private static TargetIntakeActivationGrant grant() {
        return new TargetIntakeActivationGrant(
                TargetIntakeActivationGrant.TARGET_LANE,
                "p9act.v1." + "d".repeat(32),
                HASH,
                "tenant-target",
                "CASE_TARGET_INGRESS",
                7L,
                11L,
                13L,
                17L,
                "case/tenant-target/CASE_TARGET_INGRESS",
                "target-control-build",
                Instant.parse("2026-07-27T03:00:00Z"));
    }

    private static CaseCommandAcceptance acceptance(
            TargetIntakeMessageRequest request, AcceptCaseCommand command, String commandId) {
        CaseCommandRef ref =
                new CaseCommandRef(
                        "case-command-ref.v1",
                        commandId,
                        request.activation().tenantSurrogate(),
                        request.caseId(),
                        1L,
                        command.commandType(),
                        command.roomType(),
                        command.roomEpoch(),
                        new ActorRef(
                                "user-local",
                                ActorRole.USER,
                                List.of("case:" + request.caseId() + ":command:INTAKE_MESSAGE")),
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
        return new CaseCommandAcceptance(ref, "PENDING_ORCHESTRATION", request.createdAt(), false);
    }
}
