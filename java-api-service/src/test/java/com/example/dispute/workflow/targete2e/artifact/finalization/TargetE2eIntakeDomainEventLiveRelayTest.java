package com.example.dispute.workflow.targete2e.artifact.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt.CommitFacts;
import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationFailureClassifier;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationRejectedException;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class TargetE2eIntakeDomainEventLiveRelayTest {

    private static final String TENANT = "tenant-live-relay";
    private static final String CASE_ID = "CASE_LIVE_RELAY";
    private static final String RUN_ID = "RUN_LIVE_RELAY";
    private static final String ATTEMPT_ID = "ATTEMPT_LIVE_RELAY";
    private static final String EVENT_ID = "EVENT_LIVE_RELAY";
    private static final String EVENT_TYPE = "TURN_NEEDS_INPUT";
    private static final long EVENT_SEQUENCE = 7;
    private static final long ROOM_EPOCH = 4;
    private static final long PROCESS_REVISION = 14;
    private static final long FENCING_TOKEN = 91;
    private static final Instant NOW = Instant.parse("2026-08-04T05:27:05.555Z");
    private static final String RESULT_HASH = hash('a');
    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    @TempDir static Path classes;

    private static URLClassLoader classLoader;
    private static Class<?> relayType;

    @BeforeAll
    static void compileRelay() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK compiler is required").isNotNull();
        Path source = Path.of(
                "src",
                "target-e2e",
                "java",
                "com",
                "example",
                "dispute",
                "workflow",
                "targete2e",
                "artifact",
                "finalization",
                "TargetE2eIntakeDomainEventLiveRelay.java");
        int status = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString(),
                source.toString());
        assertThat(status).isZero();
        classLoader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()},
                TargetE2eIntakeDomainEventLiveRelayTest.class.getClassLoader());
        relayType = classLoader.loadClass(
                "com.example.dispute.workflow.targete2e.artifact.finalization."
                        + "TargetE2eIntakeDomainEventLiveRelay");
    }

    @AfterAll
    static void closeLoader() throws Exception {
        classLoader.close();
    }

    @Test
    void loadsTheExactCanonicalEventAndSignalsTheCurrentCaseWorkflow() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        ExecuteAgentRunResult result = result();
        TargetE2eFinalizationReceipt targetReceipt = targetReceipt();
        AgentRunFinalizationReceipt agentReceipt = agentReceipt(CommitStatus.COMMITTED);
        IntakeFinalizationReceipt intakeReceipt = intakeReceipt(RUN_ID, FENCING_TOKEN);
        NamedParameterJdbcTemplate jdbc = jdbcWith(intakeReceipt);
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        CaseDomainEventRef event = canonicalEvent(EVENT_ID, EVENT_TYPE, ROOM_EPOCH);
        when(ledger.loadDomainEvents(any(LoadSequenceRange.class))).thenReturn(List.of(event));
        WorkflowClient client = mock(WorkflowClient.class);
        CaseProcessWorkflow workflow = mock(CaseProcessWorkflow.class);
        String workflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
        when(client.newWorkflowStub(CaseProcessWorkflow.class, workflowId)).thenReturn(workflow);
        Object relay = newRelay(jdbc, ledger, client);

        invokeRelay(relay, request, result, targetReceipt, agentReceipt);

        verify(ledger).loadDomainEvents(new LoadSequenceRange(
                "load-sequence-range.v1",
                TENANT,
                CASE_ID,
                EVENT_SEQUENCE,
                EVENT_SEQUENCE,
                1));
        verify(client).newWorkflowStub(CaseProcessWorkflow.class, workflowId);
        verify(workflow).domainEventCommitted(event);
    }

    @Test
    void rejectsAnAbsentOrAmbiguousSelectorWithoutLoadingOrSignalling() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        WorkflowClient client = mock(WorkflowClient.class);
        Object relay = newRelay(jdbc, ledger, client);

        assertThatThrownBy(() -> invokeRelay(
                        relay,
                        request(RoomType.INTAKE),
                        result(),
                        targetReceipt(),
                        agentReceipt(CommitStatus.COMMITTED)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("exactly one formal timeline event");
        verifyNoInteractions(ledger, client);
    }

    @Test
    void classifiesATransientFormalEventSelectorFailureAsRetryable() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        IllegalStateException selectorFailure = new IllegalStateException("database unavailable");
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenThrow(selectorFailure);
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        WorkflowClient client = mock(WorkflowClient.class);
        Object relay = newRelay(jdbc, ledger, client);

        RuntimeException deliveryFailure = org.assertj.core.api.Assertions.catchRuntimeException(
                () -> invokeRelay(
                        relay,
                        request,
                        result(),
                        targetReceipt(),
                        agentReceipt(CommitStatus.ALREADY_COMMITTED)));

        assertThat(deliveryFailure)
                .isInstanceOf(AgentRunFinalizationFailure.class)
                .hasCause(selectorFailure);
        assertThat(((AgentRunFinalizationFailure) deliveryFailure).retryable()).isTrue();
        RuntimeException classified =
                AgentRunFinalizationFailureClassifier.classify(request, deliveryFailure);
        assertThat(classified).isInstanceOf(ApplicationFailure.class);
        assertThat(((ApplicationFailure) classified).isNonRetryable()).isFalse();
        verifyNoInteractions(ledger, client);
    }

    @Test
    void rejectsAReceiptWhoseExactRunIdentityDoesNotMatchTheSelector() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        NamedParameterJdbcTemplate jdbc = jdbcWith(intakeReceipt("RUN_OTHER", FENCING_TOKEN));
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        WorkflowClient client = mock(WorkflowClient.class);
        Object relay = newRelay(jdbc, ledger, client);

        TargetE2eFinalizationRejectedException rejection =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> invokeRelay(
                                relay,
                                request,
                                result(),
                                targetReceipt(),
                                agentReceipt(CommitStatus.COMMITTED)),
                        TargetE2eFinalizationRejectedException.class);

        assertThat(rejection).hasMessageContaining("receipt conflicts");
        RuntimeException classified =
                AgentRunFinalizationFailureClassifier.classify(request, rejection);
        assertThat(classified).isInstanceOf(ApplicationFailure.class);
        assertThat(((ApplicationFailure) classified).isNonRetryable()).isTrue();
        verifyNoInteractions(ledger, client);
    }

    @Test
    void rejectsAReceiptWhoseFenceDoesNotMatchTheCommittedRequest() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        NamedParameterJdbcTemplate jdbc = jdbcWith(intakeReceipt(RUN_ID, FENCING_TOKEN + 1));
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        WorkflowClient client = mock(WorkflowClient.class);
        Object relay = newRelay(jdbc, ledger, client);

        TargetE2eFinalizationRejectedException rejection =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> invokeRelay(
                                relay,
                                request,
                                result(),
                                targetReceipt(),
                                agentReceipt(CommitStatus.COMMITTED)),
                        TargetE2eFinalizationRejectedException.class);

        assertThat(rejection).hasMessageContaining("receipt conflicts");
        RuntimeException classified =
                AgentRunFinalizationFailureClassifier.classify(request, rejection);
        assertThat(classified).isInstanceOf(ApplicationFailure.class);
        assertThat(((ApplicationFailure) classified).isNonRetryable()).isTrue();
        verifyNoInteractions(ledger, client);
    }

    @Test
    void rejectsAReceiptWhoseCommandDoesNotMatchTheCommittedRequest() throws Exception {
        NamedParameterJdbcTemplate jdbc = jdbcWith(intakeReceipt(
                RUN_ID, FENCING_TOKEN, "COMMAND_OTHER", hash('7')));
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        WorkflowClient client = mock(WorkflowClient.class);
        Object relay = newRelay(jdbc, ledger, client);

        assertThatThrownBy(() -> invokeRelay(
                        relay,
                        request(RoomType.INTAKE),
                        result(),
                        targetReceipt(),
                        agentReceipt(CommitStatus.COMMITTED)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("receipt conflicts");
        verifyNoInteractions(ledger, client);
    }

    @Test
    void rejectsAReceiptWhoseProposalDoesNotMatchTheTargetReceipt() throws Exception {
        NamedParameterJdbcTemplate jdbc = jdbcWith(intakeReceipt(
                RUN_ID, FENCING_TOKEN, "COMMAND_LIVE_RELAY", hash('e')));
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        WorkflowClient client = mock(WorkflowClient.class);
        Object relay = newRelay(jdbc, ledger, client);

        assertThatThrownBy(() -> invokeRelay(
                        relay,
                        request(RoomType.INTAKE),
                        result(),
                        targetReceipt(),
                        agentReceipt(CommitStatus.COMMITTED)))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("receipt conflicts");
        verifyNoInteractions(ledger, client);
    }

    @Test
    void classifiesATransientCanonicalLedgerFailureAsRetryable() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        NamedParameterJdbcTemplate jdbc = jdbcWith(intakeReceipt(RUN_ID, FENCING_TOKEN));
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        IllegalStateException ledgerFailure = new IllegalStateException("ledger unavailable");
        when(ledger.loadDomainEvents(any(LoadSequenceRange.class))).thenThrow(ledgerFailure);
        WorkflowClient client = mock(WorkflowClient.class);
        Object relay = newRelay(jdbc, ledger, client);

        RuntimeException deliveryFailure = org.assertj.core.api.Assertions.catchRuntimeException(
                () -> invokeRelay(
                        relay,
                        request,
                        result(),
                        targetReceipt(),
                        agentReceipt(CommitStatus.ALREADY_COMMITTED)));

        assertThat(deliveryFailure)
                .isInstanceOf(AgentRunFinalizationFailure.class)
                .hasCause(ledgerFailure);
        assertThat(((AgentRunFinalizationFailure) deliveryFailure).retryable()).isTrue();
        RuntimeException classified =
                AgentRunFinalizationFailureClassifier.classify(request, deliveryFailure);
        assertThat(classified).isInstanceOf(ApplicationFailure.class);
        assertThat(((ApplicationFailure) classified).isNonRetryable()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void propagatesTypedSignalFailureAfterTheCanonicalEventWasLoaded() throws Exception {
        ExecuteAgentRunRequest request = request(RoomType.INTAKE);
        NamedParameterJdbcTemplate jdbc = jdbcWith(intakeReceipt(RUN_ID, FENCING_TOKEN));
        CaseProcessLedgerActivities ledger = mock(CaseProcessLedgerActivities.class);
        CaseDomainEventRef event = canonicalEvent(EVENT_ID, EVENT_TYPE, ROOM_EPOCH);
        when(ledger.loadDomainEvents(any(LoadSequenceRange.class))).thenReturn(List.of(event));
        WorkflowClient client = mock(WorkflowClient.class);
        CaseProcessWorkflow workflow = mock(CaseProcessWorkflow.class);
        when(client.newWorkflowStub(
                        eq(CaseProcessWorkflow.class),
                        eq(CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID))))
                .thenReturn(workflow);
        IllegalStateException signalFailure = new IllegalStateException("signal unavailable");
        doThrow(signalFailure).when(workflow).domainEventCommitted(event);
        Object relay = newRelay(jdbc, ledger, client);

        RuntimeException deliveryFailure = org.assertj.core.api.Assertions.catchRuntimeException(
                () -> invokeRelay(
                        relay,
                        request,
                        result(),
                        targetReceipt(),
                        agentReceipt(CommitStatus.ALREADY_COMMITTED)));

        assertThat(deliveryFailure)
                .isInstanceOf(AgentRunFinalizationFailure.class)
                .hasCause(signalFailure);
        assertThat(((AgentRunFinalizationFailure) deliveryFailure).retryable()).isTrue();
        RuntimeException classified =
                AgentRunFinalizationFailureClassifier.classify(request, deliveryFailure);
        assertThat(classified).isInstanceOf(ApplicationFailure.class);
        assertThat(((ApplicationFailure) classified).isNonRetryable()).isFalse();
        assertThat(((ApplicationFailure) classified).getType())
                .isEqualTo("TargetE2eIntakeDomainEventLiveDeliveryRetryable");
    }

    private static Object newRelay(
            NamedParameterJdbcTemplate jdbc,
            CaseProcessLedgerActivities ledger,
            WorkflowClient client)
            throws Exception {
        return relayType
                .getConstructor(
                        NamedParameterJdbcTemplate.class,
                        ObjectMapper.class,
                        CaseProcessLedgerActivities.class,
                        WorkflowClient.class)
                .newInstance(jdbc, MAPPER, ledger, client);
    }

    private static void invokeRelay(
            Object relay,
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eFinalizationReceipt targetReceipt,
            AgentRunFinalizationReceipt agentReceipt)
            throws Exception {
        try {
            relayType
                    .getMethod(
                            "relay",
                            ExecuteAgentRunRequest.class,
                            ExecuteAgentRunResult.class,
                            TargetE2eFinalizationReceipt.class,
                            AgentRunFinalizationReceipt.class)
                    .invoke(relay, request, result, targetReceipt, agentReceipt);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static NamedParameterJdbcTemplate jdbcWith(IntakeFinalizationReceipt receipt)
            throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(List.of(formalEventRow(receipt)));
        return jdbc;
    }

    private static Map<String, Object> formalEventRow(IntakeFinalizationReceipt receipt)
            throws Exception {
        var event = MAPPER.createObjectNode();
        event.put("event_type", EVENT_TYPE);
        event.set("receipt", MAPPER.valueToTree(receipt));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", EVENT_ID);
        row.put("sequence_no", EVENT_SEQUENCE);
        row.put("event_type", EVENT_TYPE);
        row.put("event_json", MAPPER.writeValueAsString(event));
        return row;
    }

    private static ExecuteAgentRunRequest request(RoomType roomType) {
        RoomGraphCommand command = command(roomType);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                RUN_ID,
                1,
                2,
                "agent-stream.v2",
                hash('2'),
                null,
                false,
                0,
                command);
    }

    private static RoomGraphCommand command(RoomType roomType) {
        return new RoomGraphCommand(
                "room-graph-command.v1",
                "COMMAND_LIVE_RELAY",
                RUN_ID,
                ATTEMPT_ID,
                TENANT,
                CASE_ID,
                roomType,
                ROOM_EPOCH,
                "all-rooms.target-e2e.v1",
                "target-e2e-graph.2026-08-04.1",
                "target-e2e-checkpoint.v1",
                "grt.v1." + "1".repeat(32),
                new RoomGraphCommand.ActorScope(
                        "user-live-relay", ActorRole.USER, Audience.USER, List.of("INTAKE_MESSAGE")),
                PROCESS_REVISION,
                "INTAKE_ACTIVE",
                7,
                new RoomGraphCommand.SnapshotRef(
                        "SNAPSHOT_LIVE_RELAY",
                        "intake-domain-snapshot.v2",
                        "urn:intake:snapshot:live-relay",
                        hash('3'),
                        1),
                null,
                new RoomGraphCommand.InvocationContext(
                        "agent-profile-live",
                        "prompt-profile-live",
                        "model-profile-live",
                        "target-e2e-room-proposal-source.v1",
                        "policy-live",
                        "guardrail-live",
                        List.of(),
                        "envelope-key-live",
                        "envelope-nonce-live"),
                new RoomGraphCommand.RetryBudget(2, 2, 1),
                NOW.plusSeconds(300),
                "00-" + "e".repeat(32) + "-" + "f".repeat(16) + "-01",
                hash('4'));
    }

    private static ExecuteAgentRunResult result() {
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        when(result.logicalRunId()).thenReturn(RUN_ID);
        when(result.attemptId()).thenReturn(ATTEMPT_ID);
        when(result.resultHash()).thenReturn(RESULT_HASH);
        return result;
    }

    private static TargetE2eFinalizationReceipt targetReceipt() {
        return TargetE2eFinalizationReceipt.committed(
                new TargetE2eFinalizationReceipt.CommitFacts(
                        "p9act.v1." + "1".repeat(32),
                        TENANT,
                        CASE_ID,
                        RoomType.INTAKE,
                        ROOM_EPOCH,
                        FENCING_TOKEN,
                        PROCESS_REVISION,
                        7,
                        RUN_ID,
                        ATTEMPT_ID,
                        hash('5'),
                        hash('6'),
                        "all-rooms.target-e2e.v1",
                        "target-e2e-graph.2026-08-04.1",
                        "target-e2e-checkpoint.v1",
                        "CHECKPOINT_LIVE_RELAY",
                        RESULT_HASH,
                        hash('7'),
                        hash('8'),
                        "MANIFEST_LIVE_RELAY",
                        hash('9'),
                        hash('b'),
                        NOW));
    }

    private static AgentRunFinalizationReceipt agentReceipt(CommitStatus status) {
        return new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                RUN_ID,
                RUN_ID,
                ATTEMPT_ID,
                1,
                FENCING_TOKEN,
                RESULT_HASH,
                "MANIFEST_LIVE_RELAY",
                hash('9'),
                111,
                status,
                NOW);
    }

    private static IntakeFinalizationReceipt intakeReceipt(
            String logicalRunId, long fencingToken) {
        return intakeReceipt(
                logicalRunId,
                fencingToken,
                "COMMAND_LIVE_RELAY",
                hash('7'));
    }

    private static IntakeFinalizationReceipt intakeReceipt(
            String logicalRunId,
            long fencingToken,
            String commandId,
            String proposalHash) {
        return IntakeFinalizationReceipt.committed(new CommitFacts(
                "intake-final:live-relay",
                TENANT,
                CASE_ID,
                ROOM_EPOCH,
                "grt.v1." + "1".repeat(32),
                hash('c'),
                "AGENT_SESSION_LIVE_RELAY",
                commandId,
                logicalRunId,
                ATTEMPT_ID,
                RESULT_HASH,
                proposalHash,
                PROCESS_REVISION,
                8,
                fencingToken,
                "MESSAGE_LIVE_RELAY",
                1L,
                null,
                List.of(EVENT_ID),
                List.of("OUTBOX_LIVE_RELAY"),
                NOW));
    }

    private static CaseDomainEventRef canonicalEvent(
            String eventId, String eventType, long roomEpoch) {
        return new CaseDomainEventRef(
                "case-domain-event-ref.v1",
                eventId,
                TENANT,
                CASE_ID,
                EVENT_SEQUENCE,
                eventType,
                RoomType.INTAKE,
                roomEpoch,
                new PayloadRef(
                        "case-timeline-event.v1",
                        "urn:case-timeline-event:" + eventId,
                        hash('d'),
                        128),
                NOW,
                "00-" + "1".repeat(32) + "-" + "2".repeat(16) + "-01");
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
