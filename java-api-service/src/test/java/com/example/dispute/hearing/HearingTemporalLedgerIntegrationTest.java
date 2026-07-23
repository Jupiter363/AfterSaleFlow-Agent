package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingAuthorityRejectedException;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalCommitResult;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingAuthorityLedger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL proof for Hearing authority fencing, idempotency, and failure atomicity. */
@Testcontainers
class HearingTemporalLedgerIntegrationTest {

    private static final String DB = "hearing_temporal_ledger";
    private static final String USER = "dispute_test";
    private static final String PASSWORD = "local_test_password";
    private static final String TENANT = "tenant-hearing-ledger";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
                    DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB", DB)
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static JdbcTemplate jdbc;
    private static JdbcHearingAuthorityLedger ledger;

    @BeforeAll
    static void startDatabase() {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ':'
                + POSTGRES.getMappedPort(5432) + '/' + DB;
        Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DataSource dataSource = new DriverManagerDataSource(url, USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        ledger = new JdbcHearingAuthorityLedger(
                new NamedParameterJdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void exactReplayDoesNotRunTheFormalMutationTwiceAndStaleRevisionFailsClosed() {
        Fixture fixture = insertFixture("REPLAY");
        AtomicInteger mutations = new AtomicInteger();

        HearingDomainReceipt first = ledger.commitOrReplay(
                fixture.command(
                        stageOperationKey(fixture.caseId(), 1, HearingFlowStage.COURT_PREPARING),
                        HASH_A),
                () -> {
                    mutations.incrementAndGet();
                    advanceFormalCursor(fixture, HearingFlowStage.CASE_INTRODUCTION, 2);
                    return result(HearingFlowStage.CASE_INTRODUCTION, 2, HASH_B, 1);
                });
        HearingDomainReceipt replay = ledger.commitOrReplay(
                fixture.command(
                        stageOperationKey(fixture.caseId(), 1, HearingFlowStage.COURT_PREPARING),
                        HASH_A),
                () -> {
                    throw new AssertionError("replay must not execute the formal mutation");
                });

        assertThat(replay).isEqualTo(first);
        assertThat(mutations).hasValue(1);
        assertThat(first.processRevision()).isEqualTo(1);
        assertThat(first.roomRevision()).isEqualTo(1);
        assertThat(value("select current_stage from hearing_temporal_projection where flow_instance_id = ?",
                        fixture.flowId()))
                .isEqualTo("CASE_INTRODUCTION");
        assertThat(number("select process_revision from case_room_epoch where id = ?", fixture.epochId()))
                .isEqualTo(1);

        HearingAuthorityCommit stale = new HearingAuthorityCommit(
                HearingAuthorityCommit.SCHEMA_VERSION,
                new HearingAuthorityExpectation(
                        TENANT,
                        fixture.caseId(),
                        fixture.flowId(),
                        fixture.epochId(),
                        0,
                        HearingWriterMode.LEGACY,
                        HearingFlowStage.CASE_INTRODUCTION,
                        2,
                        0,
                        0,
                        0),
                HearingAuthorityCommit.OperationType.STAGE,
                stageOperationKey(fixture.caseId(), 2, HearingFlowStage.CASE_INTRODUCTION),
                HASH_C,
                null,
                NOW);
        assertThatThrownBy(() -> ledger.commitOrReplay(
                        stale,
                        () -> result(HearingFlowStage.EVIDENCE_INTRODUCTION, 3, HASH_C, 2)))
                .isInstanceOf(HearingAuthorityRejectedException.class)
                .extracting(failure -> ((HearingAuthorityRejectedException) failure).code())
                .isEqualTo("HEARING_STALE_AUTHORITY");
    }

    @Test
    void conflictingRequestHashIsRejectedBeforeAnyMutation() {
        Fixture fixture = insertFixture("CONFLICT");
        String operationKey =
                stageOperationKey(fixture.caseId(), 1, HearingFlowStage.COURT_PREPARING);
        ledger.commitOrReplay(fixture.command(operationKey, HASH_A), () -> {
            advanceFormalCursor(fixture, HearingFlowStage.CASE_INTRODUCTION, 2);
            return result(HearingFlowStage.CASE_INTRODUCTION, 2, HASH_B, 1);
        });

        assertThatThrownBy(() -> ledger.commitOrReplay(
                        fixture.command(operationKey, HASH_C),
                        () -> {
                            throw new AssertionError("conflict must not execute a formal mutation");
                        }))
                .isInstanceOf(HearingAuthorityRejectedException.class)
                .extracting(failure -> ((HearingAuthorityRejectedException) failure).code())
                .isEqualTo("HEARING_IDEMPOTENCY_CONFLICT");
        assertThat(number("select count(*) from hearing_domain_receipt where case_id = ?", fixture.caseId()))
                .isEqualTo(1);
    }

    @Test
    void databaseFailureRollsBackFormalCursorProjectionAndReceipt() {
        Fixture fixture = insertFixture("ROLLBACK");

        assertThatThrownBy(() -> ledger.commitOrReplay(
                        fixture.command(
                                stageOperationKey(
                                        fixture.caseId(), 1, HearingFlowStage.COURT_PREPARING),
                                HASH_A),
                        () -> {
                            advanceFormalCursor(fixture, HearingFlowStage.CASE_INTRODUCTION, 2);
                            jdbc.execute("select * from table_that_must_not_exist_for_hearing_test");
                            return result(HearingFlowStage.CASE_INTRODUCTION, 2, HASH_B, 1);
                        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(value("select current_stage from hearing_flow_instance where id = ?", fixture.flowId()))
                .isEqualTo("COURT_PREPARING");
        assertThat(value("select current_stage from hearing_temporal_projection where flow_instance_id = ?",
                        fixture.flowId()))
                .isEqualTo("COURT_PREPARING");
        assertThat(number("select process_revision from case_room_epoch where id = ?", fixture.epochId()))
                .isZero();
        assertThat(number("select count(*) from hearing_domain_receipt where case_id = ?", fixture.caseId()))
                .isZero();
    }

    @Test
    void missingDerivedProjectionCannotFailOpenTheDatabaseWriterGuard() {
        Fixture fixture = insertFixture("MISSING_PROJECTION");
        assertThat(jdbc.update(
                        "delete from hearing_temporal_projection where flow_instance_id = ?",
                        fixture.flowId()))
                .isEqualTo(1);

        assertThatThrownBy(() -> advanceFormalCursor(
                        fixture, HearingFlowStage.CASE_INTRODUCTION, 2))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no exact database authority projection");
        assertThat(value("select current_stage from hearing_flow_instance where id = ?", fixture.flowId()))
                .isEqualTo("COURT_PREPARING");
    }

    @Test
    void reviewerAuthorizedDemoPurgeRemovesTheAdditiveHearingAuthorityRows() {
        Fixture fixture = insertFixture("PURGE");
        String operationKey =
                stageOperationKey(fixture.caseId(), 1, HearingFlowStage.COURT_PREPARING);
        ledger.commitOrReplay(fixture.command(operationKey, HASH_A), () -> {
            advanceFormalCursor(fixture, HearingFlowStage.CASE_INTRODUCTION, 2);
            return result(HearingFlowStage.CASE_INTRODUCTION, 2, HASH_B, 1);
        });
        jdbc.update(
                """
                update fulfillment_dispute_case
                   set source_type = 'EXTERNAL_IMPORT',
                       source_system = 'TEMPLATE_SIMULATED_OMS',
                       external_case_ref = ?,
                       updated_by = 'hearing-ledger-test'
                 where id = ?
                """,
                "P6-" + fixture.caseId(),
                fixture.caseId());

        String auditId = jdbc.queryForObject(
                "select purge_simulated_dispute_case(?, 'reviewer-1', 'PLATFORM_REVIEWER')",
                String.class,
                fixture.caseId());

        assertThat(auditId).startsWith("PURGE_");
        assertThat(number("select count(*) from fulfillment_dispute_case where id = ?", fixture.caseId()))
                .isZero();
        assertThat(number("select count(*) from hearing_domain_receipt where case_id = ?", fixture.caseId()))
                .isZero();
        assertThat(number("select count(*) from hearing_temporal_projection where case_id = ?", fixture.caseId()))
                .isZero();
    }

    private static Fixture insertFixture(String suffix) {
        String caseId = "CASE_HEARING_" + suffix;
        String roomId = "ROOM_HEARING_" + suffix;
        String stateId = "STATE_HEARING_" + suffix;
        String flowId = "FLOW_HEARING_" + suffix;
        String epochId = "EPOCH_HEARING_" + suffix;
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'HEARING', 'USER', ?,
                    'MERCHANT', ?, 'LOW', 'Hearing ledger fixture',
                    'Phase 6 Hearing authority integration fixture.',
                    'HEARING', 'hearing-ledger-test', 'hearing-ledger-test')
                """,
                caseId,
                "user-" + suffix,
                "merchant-" + suffix,
                "create-" + suffix,
                "user-" + suffix,
                "merchant-" + suffix);
        jdbc.update(
                """
                insert into hearing_state (
                    id, case_id, workflow_id, hearing_status, current_node,
                    created_by, updated_by
                ) values (?, ?, ?, 'ACTIVE', 'COURT_PREPARING',
                    'hearing-ledger-test', 'hearing-ledger-test')
                """,
                stateId,
                caseId,
                "legacy-hearing-" + suffix);
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at,
                    created_by, updated_by
                ) values (?, ?, 'HEARING', 'OPEN', ?,
                    'hearing-ledger-test', 'hearing-ledger-test')
                """,
                roomId,
                caseId,
                now);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room,
                    room_phase, writer_mode, process_revision, room_epoch,
                    fencing_token, last_command_sequence, last_case_event_sequence,
                    temporal_build_id, projected_at, updated_at
                ) values (?, ?, 'HEARING', 'HEARING', 'COURT_PREPARING',
                    'LEGACY', 0, 0, 0, 0, 0, 'legacy-java.v1', ?, ?)
                """,
                caseId,
                TENANT,
                now,
                now);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    activated_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'HEARING', 0, 'LEGACY', 'ACTIVE', 0, 0, 0,
                    'legacy-java.v1', 'hearing.legacy', 'hearing-flow.v2',
                    'legacy-checkpoint.v1', 'agent_stream.v1',
                    'room-epoch-selection.v1', 'case-process-contract.v1',
                    'LegacyJavaRoomState', ?, ?, ?)
                """,
                epochId,
                TENANT,
                caseId,
                roomId,
                now,
                now,
                now);
        jdbc.update(
                """
                insert into hearing_flow_instance (
                    id, case_id, hearing_state_id, schema_version, current_stage,
                    stage_sequence, flow_status, shared_deadline_at,
                    created_at, updated_at, created_by, updated_by
                ) values (?, ?, ?, 'hearing_flow.v2', 'COURT_PREPARING', 1,
                    'ACTIVE', null, ?, ?, 'hearing-ledger-test', 'hearing-ledger-test')
                """,
                flowId,
                caseId,
                stateId,
                now,
                now);
        assertThat(number(
                        "select count(*) from hearing_temporal_projection where flow_instance_id = ?",
                        flowId))
                .isEqualTo(1);
        return new Fixture(caseId, flowId, epochId);
    }

    private static void advanceFormalCursor(
            Fixture fixture, HearingFlowStage stage, int sequence) {
        int updated = jdbc.update(
                """
                update hearing_flow_instance
                   set current_stage = ?, stage_sequence = ?, shared_deadline_at = null,
                       updated_at = ?, updated_by = 'hearing-ledger-test'
                 where id = ? and case_id = ?
                """,
                stage.name(),
                sequence,
                NOW.atOffset(ZoneOffset.UTC),
                fixture.flowId(),
                fixture.caseId());
        assertThat(updated).isEqualTo(1);
    }

    private static HearingFormalCommitResult result(
            HearingFlowStage stage, int sequence, String hash, long eventSequence) {
        return new HearingFormalCommitResult(
                stage,
                sequence,
                null,
                "urn:hearing:stage:" + stage.name().toLowerCase(),
                hash,
                eventSequence);
    }

    private static String stageOperationKey(
            String caseId, int sequence, HearingFlowStage stage) {
        return "hearing.stage:" + TENANT + ':' + caseId + ":0:" + sequence + ':' + stage.name();
    }

    private static String value(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private static long number(String sql, Object... arguments) {
        Number value = jdbc.queryForObject(sql, Number.class, arguments);
        return value == null ? 0 : value.longValue();
    }

    private record Fixture(String caseId, String flowId, String epochId) {
        HearingAuthorityCommit command(String operationKey, String requestHash) {
            return new HearingAuthorityCommit(
                    HearingAuthorityCommit.SCHEMA_VERSION,
                    new HearingAuthorityExpectation(
                            TENANT,
                            caseId,
                            flowId,
                            epochId,
                            0,
                            HearingWriterMode.LEGACY,
                            HearingFlowStage.COURT_PREPARING,
                            1,
                            0,
                            0,
                            0),
                    HearingAuthorityCommit.OperationType.STAGE,
                    operationKey,
                    requestHash,
                    null,
                    NOW);
        }
    }
}
