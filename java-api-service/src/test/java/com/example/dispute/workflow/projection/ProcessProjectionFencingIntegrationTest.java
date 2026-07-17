package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.projection.DomainOperationConflictException;
import com.example.dispute.workflow.application.projection.FencedProcessProjectionService;
import com.example.dispute.workflow.application.projection.ProjectionWriteRejectedException;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
@Import({
    FencedProcessProjectionService.class,
    ProcessProjectionFencingIntegrationTest.ProjectionTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessProjectionFencingIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
    private static final String TENANT = "tenant-projection";
    private static final String RUN_1 = "run-projection-1";
    private static final String BUILD_1 = "case-control-build-1";

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "process_projection_fencing")
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
                                + "/process_projection_fencing");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private FencedProcessProjectionService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void projectionEpochCommandAndOperationCommitAtomicallyAndReplayFromLedger() {
        Fixture fixture = insertFixture("ATOMIC");
        ApplyProjectionCommand command = command(fixture, "projection:atomic");

        var applied = service.apply(command);
        var replay = service.apply(command);

        assertThat(applied.outcome()).isEqualTo(ApplyProjectionOutcome.APPLIED);
        assertThat(replay.outcome()).isEqualTo(ApplyProjectionOutcome.IDEMPOTENT_REPLAY);
        assertThat(replay.resultRef()).isEqualTo(applied.resultRef());
        assertThat(replay.resultSha256()).isEqualTo(applied.resultSha256());
        assertThat(replay.appliedAt()).isEqualTo(applied.appliedAt());
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(6);
        assertThat(longValue("case_room_epoch", "process_revision", fixture.epochId()))
                .isEqualTo(6);
        assertThat(longValue("case_room_epoch", "room_revision", fixture.epochId()))
                .isEqualTo(4);
        assertThat(stringValue("case_command", "command_status", fixture.commandRowId()))
                .isEqualTo("APPLIED");
        assertThat(stringValue("domain_operation", "operation_status", fixture.caseId()))
                .isEqualTo("COMPLETED");
        assertThat(countOperations(fixture.caseId())).isEqualTo(1);
    }

    @Test
    void sameOperationKeyWithDifferentCanonicalRequestIsRejected() {
        Fixture fixture = insertFixture("HASH");
        ApplyProjectionCommand original = command(fixture, "projection:hash");
        service.apply(original);
        ApplyProjectionCommand conflicting =
                copy(original, "HEARING_OPEN", original.expectedTemporalRunId(), original.fencingToken(), original.roomEpoch(), original.expectedProcessRevision());

        assertThatThrownBy(() -> service.apply(conflicting))
                .isInstanceOf(DomainOperationConflictException.class)
                .hasMessageContaining("operation key");
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(6);
        assertThat(countOperations(fixture.caseId())).isEqualTo(1);
    }

    @Test
    void staleRevisionEpochFenceAndRunAreRejectedWithoutOperationRows() {
        List<RejectedCase> rejectedCases = new ArrayList<>();
        Fixture revision = insertFixture("STALE_REVISION");
        rejectedCases.add(
                new RejectedCase(
                        copy(command(revision, "projection:stale-revision"), "HEARING_PENDING", RUN_1, 17, 2, 4),
                        revision,
                        "COMMAND_REVISION_MISMATCH"));
        Fixture epoch = insertFixture("STALE_EPOCH");
        rejectedCases.add(
                new RejectedCase(
                        copy(command(epoch, "projection:stale-epoch"), "HEARING_PENDING", RUN_1, 17, 3, 5),
                        epoch,
                        "COMMAND_EPOCH_MISMATCH"));
        Fixture fence = insertFixture("STALE_FENCE");
        rejectedCases.add(
                new RejectedCase(
                        copy(command(fence, "projection:stale-fence"), "HEARING_PENDING", RUN_1, 16, 2, 5),
                        fence,
                        "FENCING_TOKEN_STALE"));
        Fixture run = insertFixture("STALE_RUN");
        rejectedCases.add(
                new RejectedCase(
                        copy(command(run, "projection:stale-run"), "HEARING_PENDING", "run-obsolete", 17, 2, 5),
                        run,
                        "WORKFLOW_RUN_STALE"));

        rejectedCases.forEach(
                rejected -> {
                    assertThatThrownBy(() -> service.apply(rejected.command()))
                            .isInstanceOfSatisfying(
                                    ProjectionWriteRejectedException.class,
                                    failure ->
                                            assertThat(failure.reasonCode())
                                                    .isEqualTo(rejected.reasonCode()));
                    assertThat(countOperations(rejected.fixture().caseId())).isZero();
                    assertThat(
                                    longValue(
                                            "case_process_projection",
                                            "process_revision",
                                            rejected.fixture().caseId()))
                            .isEqualTo(5);
                });
    }

    @Test
    void oldRunCannotWriteAfterANewRunTakesOverTheProjectionBinding() {
        Fixture fixture = insertFixture("RUN_TAKEOVER");
        ApplyProjectionCommand takeover =
                withNewRun(command(fixture, "projection:run-takeover"), "run-projection-2");
        service.apply(takeover);
        ApplyProjectionCommand lateOldRun =
                insertNextRevisionCommand(
                        fixture, "projection:late-old-run", 12, 6, 4, 5, RUN_1);

        assertThatThrownBy(() -> service.apply(lateOldRun))
                .isInstanceOfSatisfying(
                        ProjectionWriteRejectedException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo("WORKFLOW_RUN_STALE"));
        assertThat(stringValue("case_process_projection", "temporal_run_id", fixture.caseId()))
                .isEqualTo("run-projection-2");
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(6);
    }

    @Test
    void aProjectionFailureRollsBackEpochOperationAndCommandChanges() {
        Fixture fixture = insertFixture("ROLLBACK");
        installRejectingProjectionTrigger();
        try {
            assertThatThrownBy(() -> service.apply(command(fixture, "projection:rollback")))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            removeRejectingProjectionTrigger();
        }

        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(5);
        assertThat(longValue("case_room_epoch", "process_revision", fixture.epochId()))
                .isEqualTo(5);
        assertThat(stringValue("case_command", "command_status", fixture.commandRowId()))
                .isEqualTo("ORCHESTRATION_ACCEPTED");
        assertThat(countOperations(fixture.caseId())).isZero();
    }

    @Test
    void concurrentWritersAtOneRevisionHaveExactlyOneCasWinner() throws Exception {
        Fixture fixture = insertFixture("CONCURRENT");
        ApplyProjectionCommand first = command(fixture, "projection:concurrent-a");
        ApplyProjectionCommand second = command(fixture, "projection:concurrent-b");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> firstResult = executor.submit(() -> applyAfter(start, first));
            Future<Object> secondResult = executor.submit(() -> applyAfter(start, second));
            start.countDown();
            List<Object> results =
                    List.of(
                            firstResult.get(20, TimeUnit.SECONDS),
                            secondResult.get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> !(result instanceof RuntimeException)))
                    .hasSize(1);
            assertThat(results.stream().filter(ProjectionWriteRejectedException.class::isInstance))
                    .hasSize(1);
        }

        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(6);
        assertThat(countOperations(fixture.caseId())).isEqualTo(1);
    }

    private Object applyAfter(CountDownLatch start, ApplyProjectionCommand command)
            throws InterruptedException {
        start.await();
        try {
            return service.apply(command);
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private Fixture insertFixture(String suffix) {
        String caseId = "CASE_Projection" + suffix;
        String roomId = "ROOM_Projection" + suffix;
        String epochId = "EPOCH_Projection" + suffix;
        String commandRowId = "CMD_Projection" + suffix;
        String commandId = "command.projection." + suffix.toLowerCase().replace('_', '-');
        String workflowId = "case-process:" + TENANT + ":" + caseId;
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'EVIDENCE_OPEN', 'USER', ?,
                    'MERCHANT', ?, 'HIGH', 'Projection test case',
                    'Fenced projection fixture.', 'EVIDENCE', 'test', 'test')
                """,
                caseId,
                "user-" + suffix,
                "merchant-" + suffix,
                "create-projection-" + suffix,
                "user-" + suffix,
                "merchant-" + suffix);
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values (?, ?, 'EVIDENCE', 'OPEN', ?, 'test', 'test')
                """,
                roomId,
                caseId,
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, process_revision, room_epoch, fencing_token,
                    last_command_sequence, last_case_event_sequence,
                    temporal_workflow_id, temporal_run_id, temporal_build_id,
                    projected_at, updated_at
                ) values (?, ?, 'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN', 'TEMPORAL',
                    5, 2, 17, 10, 20, ?, ?, ?, ?, ?)
                """,
                caseId,
                TENANT,
                workflowId,
                RUN_1,
                BUILD_1,
                OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC));
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, temporal_workflow_id, temporal_run_id, temporal_build_id,
                    stream_protocol, activated_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'EVIDENCE', 2, 'TEMPORAL', 'ACTIVE',
                    5, 3, 17, ?, ?, ?, 'agent_stream.v1', ?, ?, ?)
                """,
                epochId,
                TENANT,
                caseId,
                roomId,
                workflowId,
                RUN_1,
                BUILD_1,
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC));
        jdbc.update(
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status, accepted_at, orchestrated_at, created_at, updated_at
                ) values (?, ?, ?, ?, 11, 'EVIDENCE_SUBMIT', 'EVIDENCE', 2,
                    'user-projection', 'USER', '["case:command"]'::jsonb,
                    'projection-command.v1', ?, ?, 128, 5, ?, ?, ?, ?,
                    'ORCHESTRATION_ACCEPTED', ?, ?, ?, ?)
                """,
                commandRowId,
                commandId,
                TENANT,
                caseId,
                "urn:test:projection:" + suffix.toLowerCase(),
                "a".repeat(64),
                OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                "00-11111111111111111111111111111111-2222222222222222-01",
                "b".repeat(64),
                OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(5), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(5), ZoneOffset.UTC));
        return new Fixture(
                caseId, roomId, epochId, commandRowId, commandId, workflowId);
    }

    private ApplyProjectionCommand insertNextRevisionCommand(
            Fixture fixture,
            String operationKey,
            long commandSequence,
            long expectedProcessRevision,
            long expectedRoomRevision,
            long newRoomRevision,
            String expectedRunId) {
        String commandRowId = fixture.commandRowId() + "Next";
        String commandId = fixture.commandId() + ".next";
        String requestHash = "d".repeat(64);
        jdbc.update(
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status, accepted_at, orchestrated_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 'EVIDENCE_SUBMIT', 'EVIDENCE', 2,
                    'user-projection', 'USER', '["case:command"]'::jsonb,
                    'projection-command.v1', ?, ?, 128, ?, ?, ?, ?, ?,
                    'ORCHESTRATION_ACCEPTED', ?, ?, ?, ?)
                """,
                commandRowId,
                commandId,
                TENANT,
                fixture.caseId(),
                commandSequence,
                "urn:test:projection:next:" + fixture.caseId().toLowerCase(),
                "a".repeat(64),
                expectedProcessRevision,
                OffsetDateTime.ofInstant(NOW.minusSeconds(4), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                "00-11111111111111111111111111111111-2222222222222222-01",
                requestHash,
                OffsetDateTime.ofInstant(NOW.minusSeconds(4), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(3), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(4), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(3), ZoneOffset.UTC));
        ApplyProjectionCommand source = command(fixture, operationKey);
        return new ApplyProjectionCommand(
                source.schemaVersion(),
                source.operationKey(),
                source.tenantSurrogate(),
                source.caseId(),
                commandId,
                requestHash,
                source.roomType(),
                source.roomEpoch(),
                source.fencingToken(),
                expectedProcessRevision,
                expectedProcessRevision + 1,
                expectedRoomRevision,
                newRoomRevision,
                source.macroPhase(),
                source.currentRoom(),
                source.roomPhase(),
                commandSequence,
                source.lastCaseEventSequence(),
                source.projectedDeadlineAt(),
                source.temporalWorkflowId(),
                expectedRunId,
                expectedRunId,
                source.temporalBuildId(),
                source.projectionRef(),
                source.projectionSha256());
    }

    private static ApplyProjectionCommand command(Fixture fixture, String operationKey) {
        return new ApplyProjectionCommand(
                "apply-process-projection.v1",
                operationKey,
                TENANT,
                fixture.caseId(),
                fixture.commandId(),
                "b".repeat(64),
                RoomType.EVIDENCE,
                2,
                17,
                5,
                6,
                3,
                4,
                "HEARING_PENDING",
                "EVIDENCE",
                "SEALED",
                11,
                20,
                NOW.plusSeconds(1800),
                fixture.workflowId(),
                RUN_1,
                RUN_1,
                BUILD_1,
                "urn:test:projection-result:" + fixture.caseId().toLowerCase(),
                "c".repeat(64));
    }

    private static ApplyProjectionCommand copy(
            ApplyProjectionCommand source,
            String macroPhase,
            String expectedRunId,
            long fence,
            long roomEpoch,
            long expectedRevision) {
        return new ApplyProjectionCommand(
                source.schemaVersion(),
                source.operationKey(),
                source.tenantSurrogate(),
                source.caseId(),
                source.commandId(),
                source.commandRequestHash(),
                source.roomType(),
                roomEpoch,
                fence,
                expectedRevision,
                Math.max(expectedRevision + 1, source.newProcessRevision()),
                source.expectedRoomRevision(),
                source.newRoomRevision(),
                macroPhase,
                source.currentRoom(),
                source.roomPhase(),
                source.lastCommandSequence(),
                source.lastCaseEventSequence(),
                source.projectedDeadlineAt(),
                source.temporalWorkflowId(),
                expectedRunId,
                source.temporalRunId(),
                source.temporalBuildId(),
                source.projectionRef(),
                source.projectionSha256());
    }

    private static ApplyProjectionCommand withNewRun(
            ApplyProjectionCommand source, String newRunId) {
        return nextRevision(
                source,
                source.expectedProcessRevision(),
                source.newProcessRevision(),
                source.expectedRoomRevision(),
                source.newRoomRevision(),
                source.expectedTemporalRunId(),
                newRunId);
    }

    private static ApplyProjectionCommand nextRevision(
            ApplyProjectionCommand source,
            long expectedRevision,
            long newRevision,
            long expectedRoomRevision,
            long newRoomRevision,
            String expectedRunId,
            String newRunId) {
        return new ApplyProjectionCommand(
                source.schemaVersion(),
                source.operationKey(),
                source.tenantSurrogate(),
                source.caseId(),
                source.commandId(),
                source.commandRequestHash(),
                source.roomType(),
                source.roomEpoch(),
                source.fencingToken(),
                expectedRevision,
                newRevision,
                expectedRoomRevision,
                newRoomRevision,
                source.macroPhase(),
                source.currentRoom(),
                source.roomPhase(),
                source.lastCommandSequence(),
                source.lastCaseEventSequence(),
                source.projectedDeadlineAt(),
                source.temporalWorkflowId(),
                expectedRunId,
                newRunId,
                source.temporalBuildId(),
                source.projectionRef(),
                source.projectionSha256());
    }

    private long longValue(String table, String column, String id) {
        String idColumn = table.equals("case_process_projection") ? "case_id" : "id";
        return jdbc.queryForObject(
                "select "
                        + allowedColumn(column)
                        + " from "
                        + allowedTable(table)
                        + " where "
                        + idColumn
                        + " = ?",
                Long.class,
                id);
    }

    private String stringValue(String table, String column, String id) {
        String idColumn =
                table.equals("case_process_projection")
                                || table.equals("domain_operation")
                        ? "case_id"
                        : "id";
        return jdbc.queryForObject(
                "select " + allowedColumn(column) + " from " + allowedTable(table) + " where " + idColumn + " = ?",
                String.class,
                id);
    }

    private long countOperations(String caseId) {
        return jdbc.queryForObject(
                "select count(*) from domain_operation where case_id = ?",
                Long.class,
                caseId);
    }

    private void installRejectingProjectionTrigger() {
        jdbc.execute(
                """
                create or replace function reject_projection_test_update()
                returns trigger language plpgsql as $$
                begin
                    if new.process_revision = 6 then
                        raise exception 'forced projection update failure';
                    end if;
                    return new;
                end;
                $$
                """);
        jdbc.execute(
                """
                create trigger reject_projection_test_update_trigger
                before update on case_process_projection
                for each row execute function reject_projection_test_update()
                """);
    }

    private void removeRejectingProjectionTrigger() {
        jdbc.execute(
                "drop trigger if exists reject_projection_test_update_trigger on case_process_projection");
        jdbc.execute("drop function if exists reject_projection_test_update()");
    }

    private static String allowedTable(String table) {
        return switch (table) {
            case "case_process_projection", "case_room_epoch", "case_command", "domain_operation" -> table;
            default -> throw new IllegalArgumentException("unsupported table");
        };
    }

    private static String allowedColumn(String column) {
        return switch (column) {
            case "process_revision", "room_revision", "command_status", "operation_status", "temporal_run_id" -> column;
            default -> throw new IllegalArgumentException("unsupported column");
        };
    }

    private record Fixture(
            String caseId,
            String roomId,
            String epochId,
            String commandRowId,
            String commandId,
            String workflowId) {}

    private record RejectedCase(
            ApplyProjectionCommand command, Fixture fixture, String reasonCode) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ProjectionTestConfiguration {

        @Bean
        @Primary
        Clock projectionClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
