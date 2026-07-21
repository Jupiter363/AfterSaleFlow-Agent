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
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import io.temporal.failure.ApplicationFailure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntakeChildBridgeAuthorityAdapterTest {

    private static final String PAYLOAD_HASH = "a".repeat(64);
    private static final String REQUEST_HASH = "b".repeat(64);
    private static final String ACTOR_SCOPE = "c".repeat(64);

    @Test
    void preservesServerResolvedPartyAuthority() {
        var adapter = new IntakeChildBridgeActivitiesAdapter(
                new CommandOnlyPort(commandSource(null, IntakeParty.RESPONDENT)));

        var binding = adapter.bindCommand(request());

        assertThat(binding.command().party()).isEqualTo(IntakeParty.RESPONDENT);
        assertThat(binding.command().actorScopeHash()).isEqualTo(ACTOR_SCOPE);
        assertThat(binding.command().executionContext()).isNull();
    }

    @Test
    void rejectsAnyActivityExecutionContextAtTheCurrentGate() {
        var context = new IntakeCommandExecutionContext(
                "intake-command-execution-context.v1",
                "grt.v1." + "d".repeat(32),
                "agent-session-authority",
                Instant.parse("2026-07-22T00:05:00Z").toEpochMilli(),
                new RetryBudget("intake-retry-budget.v1", 1, 1, 0),
                null);
        var adapter = new IntakeChildBridgeActivitiesAdapter(
                new CommandOnlyPort(commandSource(context, IntakeParty.RESPONDENT)));

        assertThatThrownBy(() -> adapter.bindCommand(request()))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure ->
                        assertThat(failure.getType())
                                .isEqualTo(IntakeChildBridgeActivitiesAdapter.INVARIANT_FAILURE));
    }

    private static IntakeChildBridgeReadPort.CommandSource commandSource(
            IntakeCommandExecutionContext context, IntakeParty party) {
        return new IntakeChildBridgeReadPort.CommandSource(
                active(), "CMD_AUTHORITY", "tenant-authority", "CASE_AUTHORITY", 3, 9, 1,
                CommandType.INTAKE_MESSAGE, PAYLOAD_HASH, REQUEST_HASH, 7, 2, party,
                ACTOR_SCOPE, "intake.operation:CASE_AUTHORITY:CMD_AUTHORITY", context);
    }

    private static CommandRequest request() {
        CaseCommandRef command = new CaseCommandRef(
                "case-command-ref.v1", "CMD_AUTHORITY", "tenant-authority", "CASE_AUTHORITY", 1,
                CommandType.INTAKE_MESSAGE, RoomType.INTAKE, 3,
                new ActorRef("merchant-authority", ActorRole.MERCHANT, List.of("case:command")),
                new PayloadRef("intake-turn-event.v2", "urn:intake:command", PAYLOAD_HASH, 42),
                7, Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-22T00:05:00Z"),
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
