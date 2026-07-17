package com.example.dispute.workflow.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.domain.CaseProcessLedgerActivitiesImpl;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
                    writer_mode, process_revision, room_epoch, fencing_token
                ) values (
                    'CASE_LEDGER', 'tenant-ledger', 'EVIDENCE_OPEN',
                    'EVIDENCE', 'OPEN', 'LEGACY', 3, 7, 11
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
                    command_status
                ) values (
                    ?, ?, 'tenant-ledger', 'CASE_LEDGER', ?, 'EVIDENCE_SUBMIT',
                    'EVIDENCE', 7, 'user-ledger', 'USER', '["case:write"]',
                    'evidence-command.v1', ?, ?, 16, 3,
                    '2026-07-17T10:00:00Z', '2026-07-17T10:05:00Z',
                    '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01',
                    ?, 'PENDING_ORCHESTRATION'
                )
                """,
                "CMD_LEDGER_" + sequence,
                "command-ledger-" + sequence,
                sequence,
                "urn:command:ledger:" + sequence,
                Long.toString(sequence).repeat(64),
                Integer.toHexString((int) sequence).repeat(64));
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
                    fencing_token, stream_protocol, activated_at, terminal_at
                ) values (
                    ?, ?, 'CASE_LEDGER', 'ROOM_LEDGER', 'EVIDENCE', ?,
                    'LEGACY', ?, 3, 0, 11, 'agent_stream.v1',
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
