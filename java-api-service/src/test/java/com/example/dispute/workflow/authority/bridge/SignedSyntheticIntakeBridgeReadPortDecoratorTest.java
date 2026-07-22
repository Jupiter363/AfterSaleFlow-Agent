package com.example.dispute.workflow.authority.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeActivitiesAdapter;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeBridgeReadPortDecorator;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeCommandAdmissionLookup;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeCommandAdmissionLookup.PersistedCommandAdmission;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignedSyntheticIntakeBridgeReadPortDecoratorTest {

    private static final String PAYLOAD_HASH = "a".repeat(64);
    private static final String REQUEST_HASH = "b".repeat(64);
    private static final String ACTOR_SCOPE = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void decoratesExecutionContextOnlyFromExactDurableAdmission() {
        var decorated = new SignedSyntheticIntakeBridgeReadPortDecorator(
                new CommandOnlyPort(commandSource()),
                (request, source) -> admission(NOW.plusSeconds(60).toEpochMilli(), REQUEST_HASH),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var adapter = new IntakeChildBridgeActivitiesAdapter(decorated, true);

        var binding = adapter.bindCommand(request(NOW.plusSeconds(60)));

        assertThat(binding.command().executionContext()).isNotNull();
        assertThat(binding.command().executionContext().threadId())
                .isEqualTo("grt.v1." + "d".repeat(32));
        assertThat(binding.command().executionContext().retryBudget())
                .isEqualTo(new RetryBudget("intake-retry-budget.v1", 2, 3, 1));
    }

    @Test
    void failsClosedWhenAdmissionIsMissingExpiredOrStale() {
        var request = request(NOW.plusSeconds(60));
        var source = commandSource();
        assertThatThrownBy(() -> new SignedSyntheticIntakeBridgeReadPortDecorator(
                        new CommandOnlyPort(source),
                        (ignoredRequest, ignoredSource) -> null,
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .readCommand(request))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fail closed");

        assertThatThrownBy(() -> new SignedSyntheticIntakeBridgeReadPortDecorator(
                        new CommandOnlyPort(source),
                        (ignoredRequest, ignoredSource) ->
                                admission(NOW.minusSeconds(1).toEpochMilli(), REQUEST_HASH),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .readCommand(request(NOW.minusSeconds(1))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("deadline");

        assertThatThrownBy(() -> new SignedSyntheticIntakeBridgeReadPortDecorator(
                        new CommandOnlyPort(source),
                        (ignoredRequest, ignoredSource) ->
                                admission(NOW.plusSeconds(60).toEpochMilli(), "f".repeat(64)),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .readCommand(request))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not match");
    }

    private static PersistedCommandAdmission admission(long deadlineEpochMillis, String requestHash) {
        return new PersistedCommandAdmission(
                "tenant-authority",
                "CASE_AUTHORITY",
                3,
                9,
                "CMD_AUTHORITY",
                1,
                IntakeCommandType.INTAKE_MESSAGE,
                IntakeParty.RESPONDENT,
                "urn:intake:command",
                PAYLOAD_HASH,
                "intake.operation:CASE_AUTHORITY:CMD_AUTHORITY",
                ACTOR_SCOPE,
                requestHash,
                7,
                2,
                "grt.v1." + "d".repeat(32),
                "agent-session-authority",
                deadlineEpochMillis,
                new RetryBudget("intake-retry-budget.v1", 2, 3, 1));
    }

    private static IntakeChildBridgeReadPort.CommandSource commandSource() {
        return new IntakeChildBridgeReadPort.CommandSource(
                active(), "CMD_AUTHORITY", "tenant-authority", "CASE_AUTHORITY", 3, 9, 1,
                CommandType.INTAKE_MESSAGE, PAYLOAD_HASH, REQUEST_HASH, 7, 2,
                IntakeParty.RESPONDENT, ACTOR_SCOPE,
                "intake.operation:CASE_AUTHORITY:CMD_AUTHORITY", null);
    }

    private static CommandRequest request(Instant deadline) {
        CaseCommandRef command = new CaseCommandRef(
                "case-command-ref.v1", "CMD_AUTHORITY", "tenant-authority", "CASE_AUTHORITY", 1,
                CommandType.INTAKE_MESSAGE, RoomType.INTAKE, 3,
                new ActorRef("merchant-authority", ActorRole.MERCHANT, List.of("case:command")),
                new PayloadRef("intake-turn-event.v2", "urn:intake:command", PAYLOAD_HASH, 42),
                7, NOW, deadline,
                "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01", REQUEST_HASH);
        return new CommandRequest("intake-child-command-request.v1", command, active());
    }

    private static ActiveChildBinding active() {
        return new ActiveChildBinding(
                "active-intake-child-binding.v1", "tenant-authority", "CASE_AUTHORITY", 3, 9,
                "room-epoch-selection.v2", "CaseProcessWorkflow", "case-build.v1",
                "IntakeRoomWorkflow", "room-build.v1");
    }

    private record CommandOnlyPort(IntakeChildBridgeReadPort.CommandSource source)
            implements IntakeChildBridgeReadPort {
        @Override public StartSource readStart(StartRequest request) { throw new UnsupportedOperationException(); }
        @Override public CommandSource readCommand(CommandRequest request) { return source; }
        @Override public DomainEventSource readDomainEvent(DomainEventRequest request) { throw new UnsupportedOperationException(); }
    }
}
