package com.example.dispute.workflow.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
@Import({
    CaseProcessLedgerActivitiesImpl.class,
    CaseProcessLedgerActivitiesIntegrationTest.ActivityTestConfiguration.class
})
class CaseProcessLedgerActivitiesIntegrationTest {

    private static final String TENANT = "tenant-ledger";
    private static final String CASE_ID = "CASE_LEDGER";
    private static final String ROOM_ID = "ROOM_LEDGER";
    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "case_process_ledger")
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
                                + "/case_process_ledger");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private CaseProcessLedgerActivitiesImpl activities;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanCommittedRoutingFixtures() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        jdbc.update(
                "delete from process_reconciliation_issue where case_id = ?",
                CASE_ID);
        jdbc.update("delete from case_command where case_id = ?", CASE_ID);
        jdbc.update("delete from case_room_epoch where case_id = ?", CASE_ID);
        jdbc.update("delete from case_process_projection where case_id = ?", CASE_ID);
        jdbc.update("delete from case_room where case_id = ?", CASE_ID);
        jdbc.update("delete from fulfillment_dispute_case where id = ?", CASE_ID);
    }

    @Test
    void loadsOnlyTheRequestedTenantScopedCommandRangeInSequenceOrder() {
        insertCaseProjectionAndRoom();
        insertCommand(1);
        insertCommand(2);
        insertCommand(3);

        var commands = activities.loadCaseCommands(range(2, 3, 2));

        assertThat(commands).extracting(command -> command.commandId())
                .containsExactly("command-ledger-2", "command-ledger-3");
        assertThat(commands).extracting(command -> command.caseCommandSequence())
                .containsExactly(2L, 3L);
        assertThatThrownBy(
                        () ->
                                activities.loadCaseCommands(
                                        new LoadSequenceRange(
                                                "load-sequence-range.v1",
                                                "another-tenant",
                                                CASE_ID,
                                                1,
                                                1,
                                                1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant scope mismatch");
    }

    @Test
    void mapsTimelinePayloadToStableUrnHashAndTheUniqueEpochAtEventTime() {
        insertCaseProjectionAndRoom();
        insertEpoch("EPOCH_LEDGER_7", 7, "2026-07-17T09:00:00Z", null);
        insertTimelineEvent();

        var events = activities.loadDomainEvents(range(1, 1, 1));
        String storedPayload =
                jdbc.queryForObject(
                        "select event_json::text from case_timeline_event where id = 'EVENT_LEDGER_1'",
                        String.class);

        assertThat(events).singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.eventId()).isEqualTo("EVENT_LEDGER_1");
                            assertThat(event.roomType().name()).isEqualTo("EVIDENCE");
                            assertThat(event.roomEpoch()).isEqualTo(7);
                            assertThat(event.payloadRef().uri())
                                    .isEqualTo("urn:case-timeline-event:EVENT_LEDGER_1");
                            assertThat(event.payloadRef().sha256())
                                    .isEqualTo(sha256(storedPayload));
                            assertThat(event.payloadRef().sizeBytes())
                                    .isEqualTo(
                                            storedPayload.getBytes(StandardCharsets.UTF_8).length);
                            assertThat(event.traceparent())
                                    .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
                        });
    }

    @Test
    void failsClosedWhenNoEpochOrMultipleEpochsCoverTheEventTime() {
        insertCaseProjectionAndRoom();
        insertTimelineEvent();
        insertEpochForTenant(
                "another-tenant",
                "EPOCH_LEDGER_WRONG_TENANT",
                9,
                "2026-07-17T09:00:00Z",
                null);

        assertThatThrownBy(() -> activities.loadDomainEvents(range(1, 1, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one room epoch");

        jdbc.update(
                "delete from case_room_epoch where id = 'EPOCH_LEDGER_WRONG_TENANT'");

        insertEpoch(
                "EPOCH_LEDGER_1",
                1,
                "2026-07-17T08:00:00Z",
                "2026-07-17T11:00:00Z");
        insertEpoch(
                "EPOCH_LEDGER_2",
                2,
                "2026-07-17T09:00:00Z",
                "2026-07-17T12:00:00Z");

        assertThatThrownBy(() -> activities.loadDomainEvents(range(1, 1, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one room epoch");
    }

    @Test
    void reportsTheSameSequenceGapIdempotentlyAcrossWorkflowRuns() {
        insertCaseProjectionAndRoom();
        SequenceGapReport first = gapReport("run-ledger-1", 1);
        SequenceGapReport replay = gapReport("run-ledger-2", 2);

        activities.reportSequenceGap(first);
        activities.reportSequenceGap(replay);

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from process_reconciliation_issue where case_id = ?",
                                Long.class,
                                CASE_ID))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select issue_type from process_reconciliation_issue where case_id = ?",
                                String.class,
                                CASE_ID))
                .isEqualTo("COMMAND_SEQUENCE_GAP");
    }

    @Test
    void workflowExpirationIsIdempotentlyPersistedAsAnExplicitTerminalStatus() {
        insertCaseProjectionAndRoom();
        insertCommand(1);
        ExpireCaseCommand expiration =
                new ExpireCaseCommand(
                        "expire-case-command.v1",
                        TENANT,
                        CASE_ID,
                        "command-ledger-1",
                        1,
                        "1".repeat(64),
                        Instant.parse("2026-07-17T10:05:00Z"),
                        Instant.parse("2026-07-17T10:05:00Z"),
                        "case-process:tenant-ledger:CASE_LEDGER",
                        "run-ledger-expiration");

        activities.expireCaseCommand(expiration);
        activities.expireCaseCommand(expiration);

        assertThat(
                        jdbc.queryForObject(
                                "select command_status from case_command where id = 'CMD_LEDGER_1'",
                                String.class))
                .isEqualTo("EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "select status_reason_code from case_command where id = 'CMD_LEDGER_1'",
                                String.class))
                .isEqualTo("COMMAND_DEADLINE_EXPIRED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shadowRoutingLifecycleIsPersistedAndIdempotent() {
        insertRoutingFixture("SHADOW", 7);
        insertCommand(1);
        RecordCaseCommandRouted acceptedRequest =
                routingRequest(1, TENANT, 7, workflowId(TENANT), NOW.plusSeconds(60));
        RecordCaseCommandRouted completedRequest =
                routingRequest(1, TENANT, 7, workflowId(TENANT), NOW.plusSeconds(120));
        assertThat(commandLifecycleSnapshot(1).commandStatus())
                .isEqualTo("PENDING_ORCHESTRATION");

        assertThat(activities.recordCaseCommandRouted(acceptedRequest).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        CommandLifecycleSnapshot accepted = commandLifecycleSnapshot(1);
        assertThat(accepted.commandStatus()).isEqualTo("ORCHESTRATION_ACCEPTED");
        assertThat(accepted.orchestratedAt())
                .isEqualTo(OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));

        assertThat(activities.recordCaseCommandRouted(acceptedRequest).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(accepted);

        assertThat(activities.completeCaseCommandRouting(completedRequest).outcome())
                .isEqualTo(CommandLifecycleOutcome.SHADOW_COMPLETED);
        CommandLifecycleSnapshot completed = commandLifecycleSnapshot(1);
        assertThat(completed.commandStatus()).isEqualTo("SHADOW_COMPLETED");
        assertThat(completed.orchestratedAt()).isEqualTo(accepted.orchestratedAt());
        assertThat(completed.updatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC));

        assertThat(activities.completeCaseCommandRouting(completedRequest).outcome())
                .isEqualTo(CommandLifecycleOutcome.ALREADY_SHADOW_COMPLETED);
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(completed);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void temporalCompletionKeepsTheCommandOrchestrationAccepted() {
        insertRoutingFixture("TEMPORAL", 7);
        insertCommand(1);
        RecordCaseCommandRouted acceptedRequest =
                routingRequest(1, TENANT, 7, workflowId(TENANT), NOW.plusSeconds(60));
        RecordCaseCommandRouted completedRequest =
                routingRequest(1, TENANT, 7, workflowId(TENANT), NOW.plusSeconds(120));

        assertThat(activities.recordCaseCommandRouted(acceptedRequest).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        CommandLifecycleSnapshot accepted = commandLifecycleSnapshot(1);

        assertThat(activities.completeCaseCommandRouting(completedRequest).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(accepted);
        assertThat(commandLifecycleSnapshot(1).commandStatus())
                .isEqualTo("ORCHESTRATION_ACCEPTED");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void legacyEpochRejectsBothRoutingLifecycleActivitiesWithoutMutation() {
        insertRoutingFixture("LEGACY", 7);
        insertCommand(1);
        RecordCaseCommandRouted request =
                routingRequest(1, TENANT, 7, workflowId(TENANT), NOW.plusSeconds(60));
        CommandLifecycleSnapshot pending = commandLifecycleSnapshot(1);

        assertPermanentFailure(
                () -> activities.recordCaseCommandRouted(request),
                "CASE_COMMAND_ROUTING_WRITER_REJECTED");
        assertPermanentFailure(
                () -> activities.completeCaseCommandRouting(request),
                "CASE_COMMAND_ROUTING_WRITER_REJECTED");
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(pending);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void routingRejectsTenantWorkflowAndCommandEpochScopeMismatches() {
        insertRoutingFixture("SHADOW", 8);
        insertCommand(1);
        CommandLifecycleSnapshot pending = commandLifecycleSnapshot(1);

        assertRoutingScopeRejected(
                routingRequest(
                        1,
                        "another-tenant",
                        8,
                        workflowId("another-tenant"),
                        NOW.plusSeconds(60)),
                "CASE_COMMAND_LEDGER_MISSING");
        assertRoutingScopeRejected(
                routingRequest(
                        1,
                        TENANT,
                        8,
                        "case-process:tenant-ledger:another-case",
                        NOW.plusSeconds(60)),
                "CASE_COMMAND_ROUTING_SCOPE_MISMATCH");
        assertRoutingScopeRejected(
                routingRequest(1, TENANT, 8, workflowId(TENANT), NOW.plusSeconds(60)),
                "CASE_COMMAND_ROUTING_SCOPE_MISMATCH");
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(pending);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void routingTreatsMissingDurableScopeAsNonRetryableCorruption() {
        assertPermanentFailure(
                () ->
                        activities.recordCaseCommandRouted(
                                routingRequest(
                                        1,
                                        TENANT,
                                        99,
                                        workflowId(TENANT),
                                        NOW.plusSeconds(60))),
                "CASE_COMMAND_LEDGER_MISSING");

        insertCaseProjectionAndRoom();
        insertCommand(1);
        assertPermanentFailure(
                () ->
                        activities.recordCaseCommandRouted(
                                routingRequest(
                                        1,
                                        TENANT,
                                        7,
                                        workflowId(TENANT),
                                        NOW.plusSeconds(60))),
                "CASE_COMMAND_ROUTING_EPOCH_MISSING");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void routingLifecycleReturnsTerminalTombstonesWithoutRewritingCommands() {
        insertRoutingFixture("SHADOW", 7);
        List<TerminalCommand> terminalCommands =
                List.of(
                        new TerminalCommand(
                                1, "APPLIED", CommandLifecycleOutcome.ALREADY_APPLIED),
                        new TerminalCommand(
                                2,
                                "SHADOW_COMPLETED",
                                CommandLifecycleOutcome.ALREADY_SHADOW_COMPLETED),
                        new TerminalCommand(
                                3, "REJECTED", CommandLifecycleOutcome.ALREADY_REJECTED),
                        new TerminalCommand(
                                4, "FAILED", CommandLifecycleOutcome.ALREADY_FAILED),
                        new TerminalCommand(
                                5, "EXPIRED", CommandLifecycleOutcome.ALREADY_EXPIRED));

        terminalCommands.forEach(
                fixture -> {
                    insertCommand(fixture.sequence());
                    markCommandTerminal(fixture.sequence(), fixture.commandStatus());
                });

        terminalCommands.forEach(
                fixture -> {
                    RecordCaseCommandRouted request =
                            routingRequest(
                                    fixture.sequence(),
                                    TENANT,
                                    7,
                                    workflowId(TENANT),
                                    NOW.plusSeconds(180));
                    CommandLifecycleSnapshot before =
                            commandLifecycleSnapshot(fixture.sequence());

                    assertThat(activities.recordCaseCommandRouted(request).outcome())
                            .isEqualTo(fixture.outcome());
                    assertThat(activities.completeCaseCommandRouting(request).outcome())
                            .isEqualTo(fixture.outcome());
                    assertThat(commandLifecycleSnapshot(fixture.sequence()))
                            .isEqualTo(before);
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void routingUsesTheWorkflowTimestampAtTheMicrosecondDeadlineBoundary() {
        insertRoutingFixture("SHADOW", 7);
        insertCommand(1);
        insertCommand(2);
        Instant justBeforeDeadline = Instant.parse("2026-07-17T10:04:59.999999Z");
        Instant justAfterDeadline = Instant.parse("2026-07-17T10:05:00.000001Z");

        assertThat(
                        activities
                                .recordCaseCommandRouted(
                                        routingRequest(
                                                1,
                                                TENANT,
                                                7,
                                                workflowId(TENANT),
                                                justBeforeDeadline))
                                .outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        CommandLifecycleSnapshot accepted = commandLifecycleSnapshot(1);
        assertThat(accepted.orchestratedAt())
                .isEqualTo(OffsetDateTime.ofInstant(justBeforeDeadline, ZoneOffset.UTC));

        assertThat(
                        activities
                                .recordCaseCommandRouted(
                                        routingRequest(
                                                1,
                                                TENANT,
                                                7,
                                                workflowId(TENANT),
                                                justAfterDeadline))
                                .outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(accepted);

        assertThat(
                        activities
                                .recordCaseCommandRouted(
                                        routingRequest(
                                                2,
                                                TENANT,
                                                7,
                                                workflowId(TENANT),
                                                justAfterDeadline))
                                .outcome())
                .isEqualTo(CommandLifecycleOutcome.EXPIRED);
        assertThat(commandLifecycleSnapshot(2).updatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(justAfterDeadline, ZoneOffset.UTC));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void committedRoutingResultsRemainIdempotentAfterTheEpochCloses() {
        insertRoutingFixture("SHADOW", 7);
        insertCommand(1);
        RecordCaseCommandRouted request =
                routingRequest(1, TENANT, 7, workflowId(TENANT), NOW.plusSeconds(60));

        assertThat(activities.recordCaseCommandRouted(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        assertThat(activities.completeCaseCommandRouting(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.SHADOW_COMPLETED);
        CommandLifecycleSnapshot completed = commandLifecycleSnapshot(1);
        closeRoutingEpoch();

        assertThat(activities.recordCaseCommandRouted(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.ALREADY_SHADOW_COMPLETED);
        assertThat(activities.completeCaseCommandRouting(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.ALREADY_SHADOW_COMPLETED);
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(completed);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void temporalAcceptedCompletionRemainsIdempotentAfterTheEpochCloses() {
        insertRoutingFixture("TEMPORAL", 7);
        insertCommand(1);
        RecordCaseCommandRouted request =
                routingRequest(1, TENANT, 7, workflowId(TENANT), NOW.plusSeconds(60));

        assertThat(activities.recordCaseCommandRouted(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        assertThat(activities.completeCaseCommandRouting(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        CommandLifecycleSnapshot accepted = commandLifecycleSnapshot(1);
        closeRoutingEpoch();

        assertThat(activities.recordCaseCommandRouted(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        assertThat(activities.completeCaseCommandRouting(request).outcome())
                .isEqualTo(CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED);
        assertThat(commandLifecycleSnapshot(1)).isEqualTo(accepted);
    }

    private LoadSequenceRange range(long from, long to, int limit) {
        return new LoadSequenceRange(
                "load-sequence-range.v1", TENANT, CASE_ID, from, to, limit);
    }

    private SequenceGapReport gapReport(String runId, int recoveryAttempts) {
        return new SequenceGapReport(
                "sequence-gap-report.v1",
                TENANT,
                CASE_ID,
                "case-process:tenant-ledger:CASE_LEDGER",
                runId,
                SequenceStream.COMMAND,
                4,
                7,
                recoveryAttempts,
                "SEQUENCE_GAP_EXHAUSTED");
    }

    private RecordCaseCommandRouted routingRequest(
            long sequence,
            String tenantSurrogate,
            long roomEpoch,
            String workflowId,
            Instant routedAt) {
        return new RecordCaseCommandRouted(
                "record-case-command-routed.v1",
                tenantSurrogate,
                CASE_ID,
                "command-ledger-" + sequence,
                sequence,
                requestHash(sequence),
                RoomType.EVIDENCE,
                roomEpoch,
                routedAt,
                workflowId,
                "run-ledger-routing");
    }

    private String workflowId(String tenantSurrogate) {
        return "case-process:" + tenantSurrogate + ":" + CASE_ID;
    }

    private void assertRoutingScopeRejected(
            RecordCaseCommandRouted request, String expectedType) {
        assertPermanentFailure(
                () -> activities.recordCaseCommandRouted(request), expectedType);
        assertPermanentFailure(
                () -> activities.completeCaseCommandRouting(request), expectedType);
    }

    private void assertPermanentFailure(Runnable invocation, String expectedType) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(
                        ApplicationFailure.class,
                        failure -> {
                            assertThat(failure.getType()).isEqualTo(expectedType);
                            assertThat(failure.isNonRetryable()).isTrue();
                        });
    }

    private void insertCaseProjectionAndRoom() {
        jdbc.execute(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level,
                    title, description, current_room, created_by, updated_by
                ) values (
                    'CASE_LEDGER', 'user-ledger', 'merchant-ledger',
                    'ledger-idempotency', 'DISPUTE', 'EVIDENCE_OPEN',
                    'USER', 'user-ledger', 'MERCHANT', 'merchant-ledger', 'HIGH',
                    'Ledger fixture', 'Case process ledger integration fixture.',
                    'EVIDENCE', 'ledger-test', 'ledger-test'
                );

                insert into case_room (
                    id, case_id, room_type, room_status, opened_at,
                    created_by, updated_by
                ) values (
                    'ROOM_LEDGER', 'CASE_LEDGER', 'EVIDENCE', 'OPEN',
                    '2026-07-17T08:00:00Z', 'ledger-test', 'ledger-test'
                );

                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, process_revision, room_epoch, fencing_token,
                    temporal_build_id
                ) values (
                    'CASE_LEDGER', 'tenant-ledger', 'EVIDENCE_OPEN',
                    'EVIDENCE', 'OPEN', 'LEGACY', 3, 7, 11, 'legacy-java.v1'
                );
                """);
    }

    private void insertCommand(long sequence) {
        jdbc.update(
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status, accepted_at, created_at, updated_at
                ) values (
                    ?, ?, 'tenant-ledger', 'CASE_LEDGER', ?, 'EVIDENCE_SUBMIT',
                    'EVIDENCE', 7, 'user-ledger', 'USER', '["case:write"]',
                    'evidence-command.v1', ?, ?, 16, 3,
                    '2026-07-17T10:00:00Z', '2026-07-17T10:05:00Z',
                    '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01',
                    ?, 'PENDING_ORCHESTRATION', '2026-07-17T10:00:00Z',
                    '2026-07-17T10:00:00Z', '2026-07-17T10:00:00Z'
                )
                """,
                "CMD_LEDGER_" + sequence,
                "command-ledger-" + sequence,
                sequence,
                "urn:command:ledger:" + sequence,
                Long.toString(sequence).repeat(64),
                requestHash(sequence));
    }

    private void insertRoutingFixture(String writerMode, long roomEpoch) {
        insertCaseProjectionAndRoom();
        String temporalWorkflowId =
                "LEGACY".equals(writerMode) ? null : workflowId(TENANT);
        String temporalRunId = "LEGACY".equals(writerMode) ? null : "run-ledger-routing";
        String temporalBuildId =
                "LEGACY".equals(writerMode) ? "legacy-java.v1" : "build-ledger-routing";
        String workflowType =
                "LEGACY".equals(writerMode) ? "LegacyJavaRoomState" : "CaseProcessWorkflow";
        String provisioningStatus =
                "LEGACY".equals(writerMode) ? "NOT_REQUIRED" : "READY";
        String roomWorkflowId =
                "LEGACY".equals(writerMode)
                        ? null
                        : CaseProcessWorkflowProtocol.roomWorkflowId(
                                CASE_ID, RoomType.EVIDENCE, roomEpoch);
        String roomRunId =
                "LEGACY".equals(writerMode) ? null : "run-ledger-room-routing";
        jdbc.update(
                """
                update case_process_projection
                set writer_mode = ?, writer_activation_status = 'READY', room_epoch = ?,
                    temporal_workflow_id = ?, temporal_run_id = ?, temporal_build_id = ?
                where case_id = ?
                """,
                writerMode,
                roomEpoch,
                temporalWorkflowId,
                temporalRunId,
                temporalBuildId,
                CASE_ID);
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
                    activated_at, provisioned_at, created_at, updated_at
                ) values (
                    'EPOCH_LEDGER_ROUTING', ?, ?, ?, 'EVIDENCE', ?, ?, 'ACTIVE', ?,
                    3, 0, 11, ?, ?, ?, ?, ?, 'evidence.v2', '1.0.0', 'checkpoint.v1',
                    'agent_stream.v1', 'room-epoch-selection.v1',
                    'case-process-contract.v1', ?,
                    '2026-07-17T09:00:00Z',
                    case when ? = 'READY' then '2026-07-17T09:00:00Z'::timestamptz else null end,
                    '2026-07-17T09:00:00Z',
                    '2026-07-17T09:00:00Z'
                )
                """,
                TENANT,
                CASE_ID,
                ROOM_ID,
                roomEpoch,
                writerMode,
                provisioningStatus,
                temporalWorkflowId,
                temporalRunId,
                roomWorkflowId,
                roomRunId,
                temporalBuildId,
                workflowType,
                provisioningStatus);
    }

    private void markCommandTerminal(long sequence, String commandStatus) {
        boolean applied = "APPLIED".equals(commandStatus);
        boolean orchestrated = applied || "SHADOW_COMPLETED".equals(commandStatus);
        String reasonCode =
                switch (commandStatus) {
                    case "REJECTED" -> "TEST_REJECTED";
                    case "FAILED" -> "TEST_FAILED";
                    case "EXPIRED" -> "COMMAND_DEADLINE_EXPIRED";
                    default -> null;
                };
        OffsetDateTime orchestratedAt =
                orchestrated ? OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC) : null;
        OffsetDateTime appliedAt =
                applied ? OffsetDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC) : null;
        jdbc.update(
                """
                update case_command
                set command_status = ?, status_reason_code = ?,
                    result_uri = ?, result_sha256 = ?, orchestrated_at = ?,
                    applied_at = ?, updated_at = '2026-07-17T10:03:00Z'
                where command_id = ?
                """,
                commandStatus,
                reasonCode,
                applied ? "urn:command-result:ledger:" + sequence : null,
                applied ? "a".repeat(64) : null,
                orchestratedAt,
                appliedAt,
                "command-ledger-" + sequence);
    }

    private void closeRoutingEpoch() {
        jdbc.update(
                """
                update case_room_epoch
                set lifecycle_status = 'TERMINAL',
                    process_revision = process_revision + 1,
                    room_revision = room_revision + 1,
                    terminal_at = '2026-07-17T10:02:00Z',
                    updated_at = '2026-07-17T10:02:00Z',
                    version = version + 1
                where id = 'EPOCH_LEDGER_ROUTING'
                """);
    }

    private CommandLifecycleSnapshot commandLifecycleSnapshot(long sequence) {
        return jdbc.queryForObject(
                """
                select command_status, status_reason_code, result_uri, result_sha256,
                       accepted_at, orchestrated_at, applied_at, updated_at, version
                from case_command
                where command_id = ?
                """,
                (resultSet, rowNumber) ->
                        new CommandLifecycleSnapshot(
                                resultSet.getString("command_status"),
                                resultSet.getString("status_reason_code"),
                                resultSet.getString("result_uri"),
                                resultSet.getString("result_sha256"),
                                resultSet.getObject("accepted_at", OffsetDateTime.class),
                                resultSet.getObject("orchestrated_at", OffsetDateTime.class),
                                resultSet.getObject("applied_at", OffsetDateTime.class),
                                resultSet.getObject("updated_at", OffsetDateTime.class),
                                resultSet.getLong("version")),
                "command-ledger-" + sequence);
    }

    private static String requestHash(long sequence) {
        return Integer.toHexString((int) sequence).repeat(64);
    }

    private void insertTimelineEvent() {
        jdbc.execute(
                """
                insert into case_timeline_event (
                    id, case_id, sequence_no, room_id, event_type, event_time,
                    source_refs_json, event_json, audience_json,
                    audience_actor_ids_json, event_key, created_at, created_by
                ) values (
                    'EVENT_LEDGER_1', 'CASE_LEDGER', 1, 'ROOM_LEDGER',
                    'EVIDENCE_SUBMITTED', '2026-07-17T10:00:00Z', '[]',
                    '{"amount":42,"currency":"CNY"}', '["USER","MERCHANT"]',
                    '[]', 'event-ledger-1', '2026-07-17T10:00:00Z', 'ledger-test'
                )
                """);
    }

    private void insertEpoch(
            String id, long epoch, String activatedAt, String terminalAt) {
        insertEpochForTenant(TENANT, id, epoch, activatedAt, terminalAt);
    }

    private void insertEpochForTenant(
            String tenantSurrogate,
            String id,
            long epoch,
            String activatedAt,
            String terminalAt) {
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol, selection_schema_version,
                    process_contract_version, workflow_type, activated_at, terminal_at
                ) values (
                    ?, ?, 'CASE_LEDGER', 'ROOM_LEDGER', 'EVIDENCE', ?,
                    'LEGACY', ?, 3, 0, 11, 'legacy-java.v1', 'evidence.v2',
                    '1.0.0', 'checkpoint.v1', 'agent_stream.v1',
                    'room-epoch-selection.v1', 'case-process-contract.v1',
                    'LegacyJavaRoomState',
                    cast(? as timestamptz), cast(? as timestamptz)
                )
                """,
                id,
                tenantSurrogate,
                epoch,
                terminalAt == null ? "ACTIVE" : "TERMINAL",
                activatedAt,
                terminalAt);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record TerminalCommand(
            long sequence,
            String commandStatus,
            CommandLifecycleOutcome outcome) {}

    private record CommandLifecycleSnapshot(
            String commandStatus,
            String statusReasonCode,
            String resultUri,
            String resultSha256,
            OffsetDateTime acceptedAt,
            OffsetDateTime orchestratedAt,
            OffsetDateTime appliedAt,
            OffsetDateTime updatedAt,
            long version) {}

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
