package com.example.dispute.workflow.shadow;

import static com.example.dispute.workflow.shadow.IntakeSyntheticTestFixtures.signedAttempt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeDriver;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeIngressService;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SignedSyntheticIntakeIngressServiceTest {

    @Test
    void admitsThenEnqueuesOnlyThroughTheCanonicalCaseCommandService() {
        var admission = new IntakeSyntheticTestFixtures.Admission();
        var commandService = Mockito.mock(CaseCommandService.class);
        var service = new SignedSyntheticIntakeIngressService(
                new SignedSyntheticIntakeDriver(admission), commandService);
        var inert = IntakeSyntheticTestFixtures.inertCommand(
                "CMD_SYNTHETIC_MESSAGE", IntakeCommandType.INTAKE_MESSAGE);
        var attempt = signedAttempt(TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC);
        when(commandService.accept(
                        eq(IntakeSyntheticTestFixtures.CASE_ID),
                        eq("CMD_SYNTHETIC_MESSAGE"),
                        any(AcceptCaseCommand.class),
                        any(AuthenticatedActor.class),
                        eq("TRACE"),
                        eq("REQ"),
                        eq(null)))
                .thenReturn(acceptance(inert.requestHash()));

        var accepted = service.accept(
                attempt,
                inert,
                0,
                42,
                new AuthenticatedActor("user-synthetic", ActorRole.USER),
                "TRACE",
                "REQ",
                null);

        var commandCaptor = ArgumentCaptor.forClass(AcceptCaseCommand.class);
        verify(commandService).accept(
                eq(IntakeSyntheticTestFixtures.CASE_ID),
                eq("CMD_SYNTHETIC_MESSAGE"),
                commandCaptor.capture(),
                any(AuthenticatedActor.class),
                eq("TRACE"),
                eq("REQ"),
                eq(null));
        assertThat(accepted.command().requestHash()).isEqualTo(inert.requestHash());
        assertThat(commandCaptor.getValue().payloadRef().uri()).isEqualTo(inert.payloadRef());
        assertThat(commandCaptor.getValue().payloadRef().sha256()).isEqualTo(inert.payloadHash());
        assertThat(commandCaptor.getValue().toString()).doesNotContain(attempt.compactJws());
        assertThat(admission.admissions).hasValue(1);
    }

    private static CaseCommandAcceptance acceptance(String requestHash) {
        CaseCommandRef ref = new CaseCommandRef(
                "case-command-ref.v1",
                "CMD_SYNTHETIC_MESSAGE",
                IntakeSyntheticTestFixtures.TENANT,
                IntakeSyntheticTestFixtures.CASE_ID,
                1,
                CommandType.INTAKE_MESSAGE,
                RoomType.INTAKE,
                IntakeSyntheticTestFixtures.EPOCH,
                new ActorRef("user-synthetic", com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.USER,
                        List.of("case:command")),
                new PayloadRef(
                        "intake-turn-event.v2",
                        "urn:after-sale-flow:intake-command:CMD_SYNTHETIC_MESSAGE",
                        IntakeSyntheticTestFixtures.hash(1),
                        42),
                0,
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.ofEpochMilli(Long.MAX_VALUE),
                "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01",
                requestHash);
        return new CaseCommandAcceptance(
                ref, "PENDING_ORCHESTRATION", Instant.parse("2026-07-22T00:00:00Z"), false);
    }
}
