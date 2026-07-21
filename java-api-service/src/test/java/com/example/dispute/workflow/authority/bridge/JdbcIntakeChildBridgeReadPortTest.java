package com.example.dispute.workflow.authority.bridge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.IntakeAuthorityInvariantException;
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.JdbcIntakeChildBridgeReadPort;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class JdbcIntakeChildBridgeReadPortTest {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet result;
    private JdbcIntakeChildBridgeReadPort port;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        port = new JdbcIntakeChildBridgeReadPort(dataSource);
    }

    @Test
    void oneReadUsesOneReadOnlyRepeatableReadSnapshot() throws Exception {
        when(result.next()).thenReturn(false);

        assertThatThrownBy(() -> port.readCommand(commandRequest()))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessageContaining("expected one row");

        verify(dataSource, times(1)).getConnection();
        InOrder order = inOrder(connection);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        order.verify(connection).setAutoCommit(false);
    }

    @Test
    void missingAndDuplicateAuthorityCandidatesFailClosed() throws Exception {
        when(result.next()).thenReturn(true, true, false);

        assertThatThrownBy(() -> port.readCommand(commandRequest()))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessageContaining("expected one row, found 2");
    }

    @Test
    void staleTupleFailsBeforeItCanBecomeAWorkflowCommand() throws Exception {
        when(result.next()).thenReturn(true, false);
        when(result.getString(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "command_id" -> "CMD_OTHER";
            case "command_type", "case_command_type" -> "INTAKE_MESSAGE";
            case "tenant_surrogate" -> "tenant-authority";
            case "case_id" -> "CASE_AUTHORITY";
            case "room_type" -> "INTAKE";
            case "actor_id" -> "merchant-authority";
            case "actor_role" -> "MERCHANT";
            case "party" -> "RESPONDENT";
            case "execution_disposition" -> JdbcIntakeChildBridgeReadPort.INERT_DISPOSITION;
            default -> null;
        });

        assertThatThrownBy(() -> port.readCommand(commandRequest()))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("command id mismatch");
    }

    @Test
    void actorIdentityAndRoleArePartOfTheSqlCandidate() throws Exception {
        when(result.next()).thenReturn(false);

        assertThatThrownBy(() -> port.readCommand(commandRequest()))
                .isInstanceOf(IntakeAuthorityInvariantException.class);

        verify(statement).setString(5, "merchant-authority");
        verify(statement).setString(6, "MERCHANT");
    }

    private static CommandRequest commandRequest() {
        return new CommandRequest(
                "intake-child-command-request.v1",
                new CaseCommandRef(
                        "case-command-ref.v1",
                        "CMD_AUTHORITY",
                        "tenant-authority",
                        "CASE_AUTHORITY",
                        1,
                        CommandType.INTAKE_MESSAGE,
                        RoomType.INTAKE,
                        3,
                        new ActorRef("merchant-authority", ActorRole.MERCHANT, List.of("case:command")),
                        new PayloadRef("intake-turn-event.v2", "urn:intake:command", "a".repeat(64), 42),
                        7,
                        Instant.parse("2026-07-22T00:00:00Z"),
                        Instant.parse("2026-07-22T00:05:00Z"),
                        "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01",
                        "b".repeat(64)),
                new ActiveChildBinding(
                        "active-intake-child-binding.v1",
                        "tenant-authority",
                        "CASE_AUTHORITY",
                        3,
                        9,
                        "room-epoch-selection.v2",
                        "CaseProcessWorkflow",
                        "case-build.v1",
                        "IntakeRoomWorkflow",
                        "room-build.v1"));
    }
}
