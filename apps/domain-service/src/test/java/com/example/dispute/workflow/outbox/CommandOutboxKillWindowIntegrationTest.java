package com.example.dispute.workflow.outbox;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED;
import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static io.temporal.client.WorkflowUpdateStage.COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.TenantAuthorityProperties;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandDeliveryTrigger;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.config.CommandOutboxProperties;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore;
import com.example.dispute.workflow.infrastructure.outbox.ClaimedCaseCommandDelivery;
import com.example.dispute.workflow.infrastructure.outbox.SdkTemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.outbox.TemporalCommandDispatcher;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.security.ConfiguredTenantAuthority;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "app.security.tenant-authority.surrogate=legacy-default"
        })
@Testcontainers
@Import({
    CaseCommandService.class,
    CaseCommandOutboxStore.class,
    CaseProcessLedgerActivitiesImpl.class,
    ConfiguredTenantAuthority.class,
    CommandOutboxKillWindowIntegrationTest.KillWindowTestConfiguration.class
})
@EnableConfigurationProperties(TenantAuthorityProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommandOutboxKillWindowIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "command_outbox_kill_window")
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
                                + "/command_outbox_kill_window");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private CaseCommandService commandService;
    @Autowired private CaseCommandOutboxStore outboxStore;
    @Autowired private CaseProcessLedgerActivitiesImpl ledgerActivities;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    private TestWorkflowEnvironment temporalEnvironment;
    private WorkflowClient workflowClient;

    @BeforeEach
    void resetCollaborators() {
        clock.set(NOW);
        temporalEnvironment =
                TestWorkflowEnvironment.newInstance(
                        TestEnvironmentOptions.newBuilder()
                                .setInitialTime(NOW)
                                .build());
        Worker caseWorker =
                temporalEnvironment.newWorker(
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE);
        caseWorker.registerWorkflowImplementationTypes(CaseProcessWorkflowImpl.class);
        caseWorker.registerActivitiesImplementations(ledgerActivities);
        Worker roomWorker =
                temporalEnvironment.newWorker(
                        CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
        roomWorker.registerWorkflowImplementationTypes(RoomControlWorkflowImpl.class);
        temporalEnvironment.start();
        workflowClient = temporalEnvironment.getWorkflowClient();
    }

    @AfterEach
    void closeTemporalEnvironment() {
        temporalEnvironment.close();
    }

    @Test
    void committedCommandSurvivesApiDeathBeforeTheFastPathAndRelayDeliversIt() {
        String caseId = "CASE_KillBeforeDelivery";
        String commandId = "command.kill.before-delivery";
        insertEvidenceCase(caseId, "user-" + caseId);

        var acceptance = accept(caseId, commandId, "a".repeat(64));
        String outboxId = outboxId(commandId);

        assertThat(acceptance.commandStatus()).isEqualTo("PENDING_ORCHESTRATION");
        assertThat(outboxStatus(outboxId)).isEqualTo("PENDING");
        assertThat(commandStatus(commandId)).isEqualTo("PENDING_ORCHESTRATION");

        TemporalUpdateGateway restartedGateway = new SdkTemporalUpdateGateway(workflowClient);
        assertThat(dispatcher(restartedGateway).dispatchAvailable()).isEqualTo(1);

        assertThat(outboxStatus(outboxId)).isEqualTo("DELIVERED");
        assertThat(commandStatus(commandId)).isEqualTo("ORCHESTRATION_ACCEPTED");
        assertExactlyOnceInTemporal(caseId, commandId);
    }

    @Test
    void acceptedTemporalUpdateSurvivesDeathBeforeDeliveryMarkAndReclaimsByFence() {
        String caseId = "CASE_KillAfterTemporal";
        String commandId = "command.kill.after-temporal";
        insertEvidenceCase(caseId, "user-" + caseId);
        accept(caseId, commandId, "b".repeat(64));
        String outboxId = outboxId(commandId);

        ClaimedCaseCommandDelivery abandoned =
                outboxStore
                        .claimById(outboxId, offsetNow(), LEASE)
                        .orElseThrow();
        TemporalUpdateGateway firstProcessGateway =
                new SdkTemporalUpdateGateway(workflowClient);
        String admittedRunId =
                firstProcessGateway
                        .deliver(abandoned.toGatewayRequest())
                        .temporalRunId();
        assertExactlyOnceInTemporal(caseId, commandId);

        assertThat(outboxStatus(outboxId)).isEqualTo("CLAIMED");
        assertThat(commandStatus(commandId)).isEqualTo("SHADOW_COMPLETED");

        clock.advance(LEASE.plusSeconds(1));
        TemporalUpdateGateway restartedGateway = new SdkTemporalUpdateGateway(workflowClient);
        assertThat(dispatcher(restartedGateway).dispatchAvailable()).isEqualTo(1);

        assertThat(outboxAttemptCount(outboxId)).isEqualTo(2);
        assertThat(outboxTemporalRunId(outboxId)).isEqualTo(admittedRunId);
        assertThat(outboxStatus(outboxId)).isEqualTo("DELIVERED");
        assertThat(commandStatus(commandId)).isEqualTo("SHADOW_COMPLETED");
        assertExactlyOnceInTemporal(caseId, commandId);
    }

    private CaseCommandAcceptance accept(
            String caseId, String commandId, String payloadHash) {
        return commandService.accept(
                caseId,
                commandId,
                new AcceptCaseCommand(
                        CommandType.EVIDENCE_SUBMIT,
                        RoomType.EVIDENCE,
                        0,
                        new PayloadRef(
                                "evidence-command.v1",
                                "urn:test:" + commandId,
                                payloadHash,
                                128),
                        0,
                        NOW.plusSeconds(3600)),
                new AuthenticatedActor("user-" + caseId, ActorRole.USER),
                "TRACE_" + commandId,
                "REQ_" + commandId,
                null);
    }

    private TemporalCommandDispatcher dispatcher(TemporalUpdateGateway temporalGateway) {
        return new TemporalCommandDispatcher(
                outboxStore,
                temporalGateway,
                new CommandOutboxProperties(
                        true,
                        10,
                        LEASE,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5)),
                clock);
    }

    private void assertExactlyOnceInTemporal(String caseId, String commandId) {
        String workflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId("legacy-default", caseId);
        CaseProcessSnapshot snapshot =
                awaitProcess(
                        workflowId,
                        state -> state.processedCommandCount() == 1);
        assertThat(snapshot.recentCommandIds()).containsExactly(commandId);
        long acceptedUpdates =
                workflowClient.fetchHistory(workflowId).getEvents().stream()
                        .filter(
                                event ->
                                        event.getEventType()
                                                        == EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED
                                                && commandId.equals(
                                                        event
                                                                .getWorkflowExecutionUpdateAcceptedEventAttributes()
                                                                .getProtocolInstanceId()))
                        .count();
        assertThat(acceptedUpdates).isEqualTo(1);
    }

    private CaseProcessSnapshot awaitProcess(
            String workflowId, Predicate<CaseProcessSnapshot> predicate) {
        CaseProcessWorkflow workflow =
                workflowClient.newWorkflowStub(CaseProcessWorkflow.class, workflowId);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
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

    private void insertEvidenceCase(String caseId, String userId) {
        String suffix = caseId.substring("CASE_".length());
        String merchantId = "merchant-" + suffix;
        String workflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId("legacy-default", caseId);
        String roomId = "ROOM_" + suffix;
        ProvisionRoomEpochReceipt provisioning =
                provisionRoomEpoch(caseId, roomId, workflowId);
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'EVIDENCE_OPEN', 'USER', ?,
                    'MERCHANT', ?, 'HIGH', 'Kill window case',
                    'Outbox recovery fixture.', 'EVIDENCE', 'test', 'test')
                """,
                caseId,
                userId,
                merchantId,
                "create-" + suffix,
                userId,
                merchantId);
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values (?, ?, 'EVIDENCE', 'OPEN', ?, 'test', 'test')
                """,
                roomId,
                caseId,
                offsetNow());
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, writer_activation_status, process_revision, room_epoch,
                    fencing_token, temporal_workflow_id, temporal_run_id, temporal_build_id
                ) values (?, 'legacy-default', 'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN',
                    'SHADOW', 'READY', 0, 0, 1, ?, ?, 'build-kill-window')
                """,
                caseId,
                workflowId,
                provisioning.caseWorkflowRunId());
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, provisioning_status,
                    process_revision, room_revision, fencing_token,
                    temporal_workflow_id, temporal_run_id,
                    room_temporal_workflow_id, room_temporal_run_id, temporal_build_id,
                    graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    activated_at, provisioned_at
                ) values (?, 'legacy-default', ?, ?, 'EVIDENCE', 0, 'SHADOW',
                    'ACTIVE', 'READY', 0, 0, 1, ?, ?, ?, ?, 'build-kill-window', 'evidence.v2',
                    '1.0.0', 'checkpoint.v1', 'agent_stream.v1',
                    'room-epoch-selection.v1', 'case-process-contract.v1',
                    'CaseProcessWorkflow', ?, ?)
                """,
                "EPOCH_" + suffix,
                caseId,
                roomId,
                workflowId,
                provisioning.caseWorkflowRunId(),
                provisioning.roomWorkflowId(),
                provisioning.roomWorkflowRunId(),
                offsetNow(),
                offsetNow());
    }

    private ProvisionRoomEpochReceipt provisionRoomEpoch(
            String caseId, String roomId, String workflowId) {
        ProvisionRoomEpoch request =
                new ProvisionRoomEpoch(
                        ProvisionRoomEpoch.SCHEMA_VERSION,
                        "EPOCH_" + caseId.substring("CASE_".length()),
                        "legacy-default",
                        caseId,
                        roomId,
                        RoomType.EVIDENCE,
                        0,
                        0,
                        0,
                        1,
                        "EVIDENCE_OPEN",
                        "EVIDENCE",
                        "OPEN",
                        WriterMode.SHADOW,
                        workflowId,
                        CaseProcessWorkflowProtocol.roomWorkflowId(
                                caseId, RoomType.EVIDENCE, 0),
                        "room-epoch-selection.v1",
                        "case-process-contract.v1",
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        "build-kill-window",
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
                workflowClient.newUntypedWorkflowStub(
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId)
                                .setTaskQueue(
                                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE)
                                .setWorkflowIdConflictPolicy(
                                        WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                                .setWorkflowIdReusePolicy(
                                        WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                .build());
        return workflow
                .startUpdateWithStart(
                        UpdateOptions.newBuilder(ProvisionRoomEpochReceipt.class)
                                .setUpdateName(
                                        CaseProcessWorkflowProtocol.PROVISION_ROOM_EPOCH_UPDATE)
                                .setUpdateId(request.updateId())
                                .setWaitForStage(COMPLETED)
                                .build(),
                        new Object[] {request},
                        new Object[] {CaseProcessCarryState.initial()})
                .getResult();
    }

    private String outboxId(String commandId) {
        return jdbc.queryForObject(
                "select id from case_command_outbox where update_id = ?",
                String.class,
                commandId);
    }

    private String outboxStatus(String outboxId) {
        return jdbc.queryForObject(
                "select outbox_status from case_command_outbox where id = ?",
                String.class,
                outboxId);
    }

    private int outboxAttemptCount(String outboxId) {
        return jdbc.queryForObject(
                "select attempt_count from case_command_outbox where id = ?",
                Integer.class,
                outboxId);
    }

    private String outboxTemporalRunId(String outboxId) {
        return jdbc.queryForObject(
                "select temporal_run_id from case_command_outbox where id = ?",
                String.class,
                outboxId);
    }

    private String commandStatus(String commandId) {
        return jdbc.queryForObject(
                "select command_status from case_command where command_id = ?",
                String.class,
                commandId);
    }

    private OffsetDateTime offsetNow() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KillWindowTestConfiguration {

        @Bean
        @Primary
        MutableClock killWindowClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        ObjectMapper killWindowObjectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        CaseCommandDeliveryTrigger killedFastPath() {
            return outboxId -> {};
        }

    }

    static final class MutableClock extends Clock {

        private Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        void set(Instant value) {
            current = value;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

}
