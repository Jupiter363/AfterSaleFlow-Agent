package com.example.dispute.workflow.caseprocess;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED;
import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.client.WorkflowUpdateStage.COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.infrastructure.outbox.SdkTemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
@Import({
    CaseProcessLedgerActivitiesImpl.class,
    ActivityCompletionLossIntegrationTest.ActivityTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActivityCompletionLossIntegrationTest {

    private static final String TENANT = "tenant-activity-completion-loss";
    private static final String CASE_ID = "CASE_ACTIVITY_COMPLETION_LOSS";
    private static final String WORKFLOW_ID =
            CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "activity_completion_loss")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/activity_completion_loss");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private CaseProcessLedgerActivitiesImpl ledgerActivities;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void retryObservesTheSingleCommittedIssueAfterTheFirstCompletionIsLost() {
        insertCaseProjectionAndCommandTwo();
        CaseCommandRef second =
                ledgerActivities
                        .loadCaseCommands(range(2, 2))
                        .getFirst();
        CommitThenLoseCompletion activities =
                new CommitThenLoseCompletion(ledgerActivities);

        try (TestWorkflowEnvironment environment =
                TestWorkflowEnvironment.newInstance(
                        TestEnvironmentOptions.newBuilder()
                                .setInitialTime(NOW)
                                .build())) {
            Worker caseWorker =
                    environment.newWorker(
                            CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
            caseWorker.registerWorkflowImplementationTypes(
                    CaseProcessWorkflowImpl.class);
            caseWorker.registerActivitiesImplementations(activities);
            Worker roomWorker =
                    environment.newWorker(
                            CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
            roomWorker.registerWorkflowImplementationTypes(
                    RoomControlWorkflowImpl.class);
            environment.start();

            WorkflowClient client = environment.getWorkflowClient();
            provisionRoomEpoch(client);
            new SdkTemporalUpdateGateway(client)
                    .deliver(
                            new TemporalUpdateGateway.UpdateWithStartRequest(
                                    WORKFLOW_ID,
                                    CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                                    CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                                    second.commandId(),
                                    second));
            awaitAttempts(activities, 1);
            environment.sleep(Duration.ofSeconds(11));

            CaseProcessSnapshot blocked =
                    awaitProcess(
                            client,
                            snapshot ->
                                    "COMMAND_GAP_MANUAL_RECOVERY"
                                            .equals(snapshot.blockedReason()));
            assertThat(blocked.nextCommandSequence()).isEqualTo(1);
            assertThat(activities.reportAttempts).hasValue(2);
            assertThat(
                            client.fetchHistory(WORKFLOW_ID).getEvents().stream()
                                    .filter(
                                            event ->
                                                    event.getEventType()
                                                            == EVENT_TYPE_ACTIVITY_TASK_STARTED)
                                    .filter(
                                            event ->
                                                    event
                                                                    .getActivityTaskStartedEventAttributes()
                                                                    .getAttempt()
                                                            == 2)
                                    .count())
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from process_reconciliation_issue where case_id = ?",
                                    Long.class,
                                    CASE_ID))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select issue_status from process_reconciliation_issue where case_id = ?",
                                    String.class,
                                    CASE_ID))
                    .isEqualTo("OPEN");
        }
    }

    private static void provisionRoomEpoch(WorkflowClient client) {
        ProvisionRoomEpoch request =
                new ProvisionRoomEpoch(
                        ProvisionRoomEpoch.SCHEMA_VERSION,
                        "EPOCH_ACTIVITY_COMPLETION_LOSS",
                        TENANT,
                        CASE_ID,
                        "ROOM_ACTIVITY_COMPLETION_LOSS",
                        RoomType.EVIDENCE,
                        0,
                        0,
                        0,
                        1,
                        "EVIDENCE_OPEN",
                        "EVIDENCE",
                        "OPEN",
                        WriterMode.SHADOW,
                        WORKFLOW_ID,
                        CaseProcessWorkflowProtocol.roomWorkflowId(
                                CASE_ID, RoomType.EVIDENCE, 0),
                        "room-epoch-selection.v1",
                        "case-process-contract.v1",
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        "build-activity-completion-loss",
                        "evidence.v2",
                        "1.0.0",
                        "checkpoint.v1",
                        "agent_stream.v1",
                        0,
                        0,
                        1,
                        1,
                        NOW.plusSeconds(3600),
                        null,
                        null,
                        NOW);
        WorkflowStub workflow =
                client.newUntypedWorkflowStub(
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(WORKFLOW_ID)
                                .setTaskQueue(
                                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE)
                                .setWorkflowIdConflictPolicy(
                                        WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                                .setWorkflowIdReusePolicy(
                                        WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                .build());
        ProvisionRoomEpochReceipt receipt =
                workflow
                        .startUpdateWithStart(
                                UpdateOptions.newBuilder(ProvisionRoomEpochReceipt.class)
                                        .setUpdateName(
                                                CaseProcessWorkflowProtocol
                                                        .PROVISION_ROOM_EPOCH_UPDATE)
                                        .setUpdateId(request.updateId())
                                        .setWaitForStage(COMPLETED)
                                        .build(),
                                new Object[] {request},
                                new Object[] {CaseProcessCarryState.initial()})
                        .getResult();
        assertThat(receipt.matches(request)).isTrue();
    }

    private static CaseProcessSnapshot awaitProcess(
            WorkflowClient client, Predicate<CaseProcessSnapshot> predicate) {
        CaseProcessWorkflow workflow =
                client.newWorkflowStub(CaseProcessWorkflow.class, WORKFLOW_ID);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        RuntimeException lastFailure = null;
        CaseProcessSnapshot lastSnapshot = null;
        while (System.nanoTime() < deadline) {
            try {
                CaseProcessSnapshot snapshot = workflow.state();
                lastSnapshot = snapshot;
                if (predicate.test(snapshot)) {
                    return snapshot;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
            }
        }
        throw new AssertionError(
                "case process state did not converge; last snapshot=" + lastSnapshot,
                lastFailure);
    }

    private static void awaitAttempts(
            CommitThenLoseCompletion activities, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (activities.reportAttempts.get() >= expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
            }
        }
        throw new AssertionError("Activity attempt was not observed");
    }

    private LoadSequenceRange range(long from, long to) {
        return new LoadSequenceRange(
                "load-sequence-range.v1", TENANT, CASE_ID, from, to, 64);
    }

    private void insertCaseProjectionAndCommandTwo() {
        jdbc.execute(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level,
                    title, description, current_room, created_by, updated_by
                ) values (
                    'CASE_ACTIVITY_COMPLETION_LOSS', 'user-activity-loss',
                    'merchant-activity-loss', 'activity-loss-idempotency',
                    'DISPUTE', 'EVIDENCE_OPEN', 'USER', 'user-activity-loss',
                    'MERCHANT', 'merchant-activity-loss', 'HIGH',
                    'Activity completion loss fixture',
                    'Verifies a committed Activity effect survives a lost completion.',
                    'EVIDENCE', 'activity-loss-test', 'activity-loss-test'
                );

                insert into case_room (
                    id, case_id, room_type, room_status, opened_at,
                    created_by, updated_by
                ) values (
                    'ROOM_ACTIVITY_COMPLETION_LOSS',
                    'CASE_ACTIVITY_COMPLETION_LOSS', 'EVIDENCE', 'OPEN',
                    '2026-07-17T09:00:00Z', 'activity-loss-test',
                    'activity-loss-test'
                );

                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, process_revision, room_epoch, fencing_token,
                    temporal_workflow_id, temporal_build_id
                ) values (
                    'CASE_ACTIVITY_COMPLETION_LOSS',
                    'tenant-activity-completion-loss', 'EVIDENCE_OPEN',
                    'EVIDENCE', 'OPEN', 'SHADOW', 0, 0, 1,
                    'case-process:tenant-activity-completion-loss:CASE_ACTIVITY_COMPLETION_LOSS',
                    'build-activity-completion-loss'
                );

                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) values (
                    'CMD_ACTIVITY_COMPLETION_LOSS_2',
                    'command-activity-completion-loss-2',
                    'tenant-activity-completion-loss',
                    'CASE_ACTIVITY_COMPLETION_LOSS', 2, 'EVIDENCE_SUBMIT',
                    'EVIDENCE', 0, 'user-activity-loss', 'USER',
                    '["case:write"]', 'evidence-command.v1',
                    'urn:command:activity-completion-loss:2',
                    repeat('2', 64), 16, 0,
                    '2026-07-17T10:00:00Z', '2026-07-17T11:00:00Z',
                    '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01',
                    repeat('a', 64), 'PENDING_ORCHESTRATION'
                );
                """);
    }

    private static final class CommitThenLoseCompletion
            implements CaseProcessLedgerActivities {

        private final CaseProcessLedgerActivities delegate;
        private final AtomicInteger reportAttempts = new AtomicInteger();

        private CommitThenLoseCompletion(CaseProcessLedgerActivities delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
            return delegate.loadCaseCommands(request);
        }

        @Override
        public List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(
                LoadSequenceRange request) {
            return delegate.loadCaseCommandLedgerEntries(request);
        }

        @Override
        public List<CaseDomainEventRef> loadDomainEvents(
                LoadSequenceRange request) {
            return delegate.loadDomainEvents(request);
        }

        @Override
        public void reportSequenceGap(SequenceGapReport report) {
            delegate.reportSequenceGap(report);
            if (reportAttempts.incrementAndGet() == 1) {
                Activity.getExecutionContext().doNotCompleteOnReturn();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ActivityTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
