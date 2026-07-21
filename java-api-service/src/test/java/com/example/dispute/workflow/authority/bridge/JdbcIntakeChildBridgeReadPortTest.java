package com.example.dispute.workflow.authority.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.IntakeAuthorityInvariantException;
import com.example.dispute.workflow.infrastructure.persistence.authority.bridge.JdbcIntakeChildBridgeReadPort;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class JdbcIntakeChildBridgeReadPortTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

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

    @Test
    void commandPayloadUriMustMatchTheImmutableAuthorityRow() throws Exception {
        when(result.next()).thenReturn(true, false);
        stubValidCommandRow("urn:intake:authority");

        assertThatThrownBy(() -> port.readCommand(commandRequest()))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("payload URI mismatch");
    }

    @Test
    void committedInertReplayDoesNotReopenRevokedMutableSessions() throws Exception {
        when(result.next()).thenReturn(true, false);
        stubValidCommandRow("urn:intake:command");

        var source = port.readCommand(commandRequest());

        assertThat(source.party()).isEqualTo(com.example.dispute.workflow.temporal.room.intake.IntakeParty.RESPONDENT);
        assertThat(source.executionContext()).isNull();
    }

    @Test
    void rejectsServerMintedPayloadEvenWhenItClaimsAnInertDisposition() throws Exception {
        when(result.next()).thenReturn(true, false);
        stubValidCommandRow("urn:intake:command");
        when(result.getString("source_kind")).thenReturn("SERVER_MINTED_HUMAN_INPUT");

        assertThatThrownBy(() -> port.readCommand(commandRequest()))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("inert payload source kind mismatch");
    }

    @Test
    void startRejectsBootstrapPayloadHashDriftBeforeExposingPins() throws Exception {
        ProvisionRoomEpoch provision = startProvision();
        PreparedStatement selectionStatement = mock(PreparedStatement.class);
        PreparedStatement bootstrapStatement = mock(PreparedStatement.class);
        ResultSet selection = mock(ResultSet.class);
        ResultSet bootstrap = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(selectionStatement, bootstrapStatement);
        when(selectionStatement.executeQuery()).thenReturn(selection);
        when(bootstrapStatement.executeQuery()).thenReturn(bootstrap);
        stubSelection(selection);
        stubBootstrap(bootstrap, provision, "f".repeat(64));

        assertThatThrownBy(() -> port.readStart(startRequest(provision)))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("bootstrap payload hash mismatch");
    }

    @Test
    void startRejectsRegistrationPinsThatDriftFromTheSelectedEpoch() throws Exception {
        ProvisionRoomEpoch provision = startProvision();
        PreparedStatement selectionStatement = mock(PreparedStatement.class);
        PreparedStatement bootstrapStatement = mock(PreparedStatement.class);
        PreparedStatement partyStatement = mock(PreparedStatement.class);
        ResultSet selection = mock(ResultSet.class);
        ResultSet bootstrap = mock(ResultSet.class);
        ResultSet parties = mock(ResultSet.class);
        when(connection.prepareStatement(anyString()))
                .thenReturn(selectionStatement, bootstrapStatement, partyStatement);
        when(selectionStatement.executeQuery()).thenReturn(selection);
        when(bootstrapStatement.executeQuery()).thenReturn(bootstrap);
        when(partyStatement.executeQuery()).thenReturn(parties);
        stubSelection(selection);
        stubBootstrap(bootstrap, provision, provision.payloadSha256());
        stubParties(parties, "TEMPORAL");

        assertThatThrownBy(() -> port.readStart(startRequest(provision)))
                .isInstanceOf(IntakeAuthorityInvariantException.class)
                .hasMessage("registration writer mode mismatch");
    }

    @Test
    void replayAndEventProofQueriesCannotFallBackToMutableAuthorizationOrJsonOnlyProof()
            throws Exception {
        assertThat(sql("COMMAND_SQL"))
                .doesNotContain("case_access_session")
                .doesNotContain("agent_conversation_session")
                .doesNotContain("case_intake_graph_thread_binding")
                .contains("EXISTING_PRIVATE_EVENT")
                .contains("INERT_EXTERNAL_EVENT");
        assertThat(sql("EVENT_SQL"))
                .doesNotContain("case_access_session")
                .doesNotContain("agent_conversation_session")
                .doesNotContain("registration_status");
        assertThat(sql("BOOTSTRAP_SQL"))
                .contains("join case_intake_epoch_selection_binding")
                .contains("room_epoch_bootstrap_outbox")
                .contains("payload_json")
                .contains("payload_sha256");
        assertThat(sql("START_PARTY_SQL"))
                .contains("registration_status = 'REGISTERED'")
                .contains("a.status = 'ACTIVE'")
                .contains("g.status = 'ACTIVE'")
                .contains("t.writer_mode");
        assertThat(sql("TURN_EVENT_EVIDENCE_SQL"))
                .contains("domain_operation")
                .contains("agent_run_attempt")
                .contains("agent_execution_manifest")
                .contains("immutable_payload_snapshot")
                .contains("agent_run_stream_event");
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

    private static StartRequest startRequest(ProvisionRoomEpoch provision) {
        return new StartRequest(
                "intake-child-start-request.v1",
                provision,
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

    private static ProvisionRoomEpoch startProvision() {
        return new ProvisionRoomEpoch(
                ProvisionRoomEpoch.SCHEMA_VERSION,
                "EPOCH_AUTHORITY",
                "tenant-authority",
                "CASE_AUTHORITY",
                "ROOM_AUTHORITY",
                RoomType.INTAKE,
                3,
                7,
                2,
                9,
                "INTAKE",
                "INTAKE",
                "WAITING_PARTY",
                WriterMode.SHADOW,
                CaseProcessWorkflowProtocol.caseWorkflowId("tenant-authority", "CASE_AUTHORITY"),
                CaseProcessWorkflowProtocol.roomWorkflowId("CASE_AUTHORITY", RoomType.INTAKE, 3),
                "room-epoch-selection.v2",
                "case-process.v1",
                "CaseProcessWorkflow",
                "case-build.v1",
                "IntakeRoomWorkflow",
                "room-build.v1",
                "intake.v2",
                "graph.v1",
                "checkpoint.v1",
                "agent-stream.v2",
                0,
                0,
                1,
                1,
                null,
                null,
                null,
                Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static void stubSelection(ResultSet row) throws Exception {
        when(row.next()).thenReturn(true, false);
        when(row.getString(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "epoch_id" -> "EPOCH_AUTHORITY";
            case "tenant_surrogate" -> "tenant-authority";
            case "case_id" -> "CASE_AUTHORITY";
            case "room_type" -> "INTAKE";
            case "selection_hash" -> "e".repeat(64);
            case "writer_mode" -> "SHADOW";
            case "case_workflow_type" -> "CaseProcessWorkflow";
            case "case_workflow_build_id" -> "case-build.v1";
            case "room_workflow_type" -> "IntakeRoomWorkflow";
            case "room_workflow_build_id" -> "room-build.v1";
            case "process_contract_version" -> "case-process.v1";
            case "graph_key" -> "intake.v2";
            case "graph_version" -> "graph.v1";
            case "checkpoint_schema_version" -> "checkpoint.v1";
            case "state_schema_version" -> "intake-graph-state.v2";
            case "stream_protocol" -> "agent-stream.v2";
            case "prompt_version" -> "prompt.v1";
            case "model_profile_id" -> "model.v1";
            case "output_schema_version" -> "intake-turn-proposal.v2";
            case "policy_version" -> "policy.v1";
            case "guardrail_version" -> "guardrail.v1";
            case "tool_policy_version" -> "tools.v1";
            case "cohort_policy_version" -> "cohort.v1";
            case "agent_key" -> "DISPUTE_INTAKE_OFFICER";
            case "agent_session_profile_version" -> "agent-session-profile.v1";
            case "memory_policy_id" -> "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1";
            default -> null;
        });
        when(row.getLong(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "room_epoch" -> 3L;
            case "fencing_token" -> 9L;
            default -> 0L;
        });
    }

    private static void stubBootstrap(ResultSet row, ProvisionRoomEpoch provision, String payloadHash)
            throws Exception {
        when(row.next()).thenReturn(true, false);
        when(row.getString(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "id" -> "OUTBOX_AUTHORITY";
            case "epoch_id" -> provision.epochId();
            case "tenant_surrogate" -> provision.tenantSurrogate();
            case "case_id" -> provision.caseId();
            case "room_type" -> provision.roomType().name();
            case "writer_mode" -> provision.writerMode().name();
            case "case_workflow_id" -> provision.caseWorkflowId();
            case "room_workflow_id" -> provision.roomWorkflowId();
            case "workflow_type" -> provision.workflowType();
            case "task_queue" -> CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
            case "update_id" -> provision.updateId();
            case "payload_json" -> MAPPER.writeValueAsString(provision);
            case "payload_sha256" -> payloadHash;
            case "outbox_status" -> "PENDING";
            default -> null;
        });
        when(row.getLong(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "room_epoch" -> provision.roomEpoch();
            case "fencing_token" -> provision.fencingToken();
            default -> 0L;
        });
    }

    private static void stubParties(ResultSet row, String registrationWriterMode) throws Exception {
        AtomicInteger rowIndex = new AtomicInteger();
        when(row.next()).thenAnswer(invocation -> rowIndex.incrementAndGet() <= 2);
        when(row.getString(anyString())).thenAnswer(invocation -> {
            int current = rowIndex.get();
            boolean initiator = current == 1;
            return switch ((String) invocation.getArgument(0)) {
                case "party" -> initiator ? "INITIATOR" : "RESPONDENT";
                case "tenant_surrogate", "session_tenant_id" -> "tenant-authority";
                case "case_id", "session_case_id" -> "CASE_AUTHORITY";
                case "room_type" -> "INTAKE";
                case "registration_id" -> initiator ? "REG_INIT" : "REG_RESP";
                case "registration_hash" -> "e".repeat(64);
                case "thread_id" -> "grt.v1." + (initiator ? "c" : "d").repeat(32);
                case "actor_id" -> initiator ? "user-authority" : "merchant-authority";
                case "actor_role", "audience" -> initiator ? "USER" : "MERCHANT";
                case "actor_scope_hash" -> (initiator ? "c" : "d").repeat(64);
                case "access_session_id" -> initiator ? "ACCESS_INIT" : "ACCESS_RESP";
                case "permission_level" -> initiator ? "PARTY_USER" : "PARTY_MERCHANT";
                case "agent_session_id" -> initiator ? "AGENT_INIT" : "AGENT_RESP";
                case "agent_key" -> "DISPUTE_INTAKE_OFFICER";
                case "prompt_version", "registration_prompt_version" -> "prompt.v1";
                case "agent_session_profile_version" -> "agent-session-profile.v1";
                case "prompt_profile_id" -> "asp.v1." + "f".repeat(64);
                case "memory_policy_id" -> "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1";
                case "graph_key" -> "intake.v2";
                case "graph_version" -> "graph.v1";
                case "checkpoint_schema_version" -> "checkpoint.v1";
                case "state_schema_version" -> "intake-graph-state.v2";
                case "registration_model_profile_id" -> "model.v1";
                case "output_schema_version" -> "intake-turn-proposal.v2";
                case "policy_version" -> "policy.v1";
                case "guardrail_version" -> "guardrail.v1";
                case "tool_policy_version" -> "tools.v1";
                case "writer_mode" -> registrationWriterMode;
                default -> null;
            };
        });
        when(row.getLong(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "room_epoch" -> 3L;
            case "fencing_token" -> 9L;
            default -> 0L;
        });
    }

    private static String sql(String fieldName) throws ReflectiveOperationException {
        Field field = JdbcIntakeChildBridgeReadPort.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void stubValidCommandRow(String authorityUri) throws Exception {
        when(result.getString(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "command_id" -> "CMD_AUTHORITY";
            case "command_type" -> "INTAKE_MESSAGE";
            case "epoch_id" -> "EPOCH_AUTHORITY";
            case "access_session_id" -> "ACCESS_AUTHORITY";
            case "registration_id" -> "REGISTRATION_AUTHORITY";
            case "tenant_surrogate" -> "tenant-authority";
            case "case_id" -> "CASE_AUTHORITY";
            case "room_type" -> "INTAKE";
            case "thread_id" -> "grt.v1." + "d".repeat(32);
            case "actor_id" -> "merchant-authority";
            case "actor_role" -> "MERCHANT";
            case "actor_scope_hash", "content_sha256" -> "a".repeat(64);
            case "agent_session_id" -> "AGENT_AUTHORITY";
            case "payload_authority_id" -> "PAYLOAD_AUTHORITY";
            case "request_hash" -> "b".repeat(64);
            case "execution_disposition" -> JdbcIntakeChildBridgeReadPort.INERT_DISPOSITION;
            case "source_kind" -> "EXISTING_PRIVATE_EVENT";
            case "payload_schema_version" -> "intake-turn-event.v2";
            case "payload_uri" -> authorityUri;
            case "party" -> "RESPONDENT";
            case "case_workflow_type" -> "CaseProcessWorkflow";
            case "case_workflow_build_id" -> "case-build.v1";
            case "room_workflow_type" -> "IntakeRoomWorkflow";
            case "room_workflow_build_id" -> "room-build.v1";
            default -> null;
        });
        when(result.getLong(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "case_command_sequence" -> 1L;
            case "room_epoch" -> 3L;
            case "fencing_token" -> 9L;
            case "accepted_room_revision" -> 2L;
            case "payload_size_bytes" -> 42L;
            case "expected_process_revision" -> 7L;
            default -> 0L;
        });
    }
}
