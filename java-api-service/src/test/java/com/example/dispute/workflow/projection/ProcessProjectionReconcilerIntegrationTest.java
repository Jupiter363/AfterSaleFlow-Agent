package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessObservation;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessState;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Incomplete;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReadResult;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Verified;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciler;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationResult.Outcome;
import com.example.dispute.workflow.application.projection.ProcessProjectionReconciliationService;
import com.example.dispute.workflow.config.ProcessProjectionReconciliationProperties;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
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
    ProcessProjectionReconciler.class,
    ProcessProjectionReconciliationService.class,
    RoomEpochScanClaimStore.class,
    ProcessProjectionReconcilerIntegrationTest.ReconciliationTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessProjectionReconcilerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T11:00:00Z");
    private static final String TENANT = "tenant-reconciliation";
    private static final String RUN_1 = "run-reconciliation-1";
    private static final String RUN_2 = "run-reconciliation-2";
    private static final String BUILD = "case-control-build-1";

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "process_projection_reconciliation")
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
                                + "/process_projection_reconciliation");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private ProcessProjectionReconciler reconciler;
    @Autowired private MutableAuthoritativeProcessStateReader authoritativeStateReader;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void missingTemporalProjectionIsRebuiltFromCompleteVerifiedState() {
        Fixture fixture = insertFixture("MISSING", WriterMode.TEMPORAL, false, 5, 3);
        authoritativeStateReader.answer(verified(state(fixture, 6, 4, RUN_1)));

        var result = reconciler.reconcile(fixture.target());

        assertThat(result.outcome()).isEqualTo(Outcome.REPAIRED);
        assertThat(result.actualProcessRevision()).isEqualTo(-1);
        assertThat(result.authoritativeProcessRevision()).isEqualTo(6);
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(6);
        assertThat(longValue("case_room_epoch", "process_revision", fixture.epochId()))
                .isEqualTo(6);
        assertThat(longValue("case_room_epoch", "room_revision", fixture.epochId()))
                .isEqualTo(4);
        assertThat(stringValue("case_process_projection", "temporal_run_id", fixture.caseId()))
                .isEqualTo(RUN_1);
        assertThat(stringValue("process_reconciliation_issue", "issue_status", fixture.caseId()))
                .isEqualTo("RESOLVED");
    }

    @Test
    void staleTemporalProjectionAndEpochAreRepairedTogether() {
        Fixture fixture = insertFixture("STALE", WriterMode.TEMPORAL, true, 5, 3);
        authoritativeStateReader.answer(verified(state(fixture, 6, 4, RUN_1)));

        var result = reconciler.reconcile(fixture.target());

        assertThat(result.outcome()).isEqualTo(Outcome.REPAIRED);
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(6);
        assertThat(stringValue("case_process_projection", "macro_phase", fixture.caseId()))
                .isEqualTo("HEARING_PENDING");
        assertThat(stringValue("case_process_projection", "room_phase", fixture.caseId()))
                .isEqualTo("SEALED");
        assertThat(stringValue("case_room_epoch", "temporal_run_id", fixture.epochId()))
                .isEqualTo(RUN_1);
        assertThat(stringValue("process_reconciliation_issue", "issue_status", fixture.caseId()))
                .isEqualTo("RESOLVED");
    }

    @Test
    void shadowDriftIsDetectOnlyAndRepeatedScansReuseOneIssue() {
        Fixture fixture = insertFixture("SHADOW", WriterMode.SHADOW, true, 5, 3);
        authoritativeStateReader.answer(verified(state(fixture, 6, 4, RUN_1)));

        var first = reconciler.reconcile(fixture.target());
        var repeated = reconciler.reconcile(fixture.target());

        assertThat(first.outcome()).isEqualTo(Outcome.DRIFT_DETECTED);
        assertThat(repeated.outcome()).isEqualTo(Outcome.DRIFT_DETECTED);
        assertThat(repeated.issueKey()).isEqualTo(first.issueKey());
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(5);
        assertThat(longValue("case_room_epoch", "process_revision", fixture.epochId()))
                .isEqualTo(5);
        assertThat(countIssues(fixture.caseId())).isEqualTo(1);
        assertThat(stringValue("process_reconciliation_issue", "issue_scope", fixture.caseId()))
                .isEqualTo("SHADOW");
        assertThat(stringValue("process_reconciliation_issue", "issue_status", fixture.caseId()))
                .isEqualTo("OPEN");
    }

    @Test
    void incompleteAuthoritativeObservationDetectsDriftButCannotRepair() {
        Fixture fixture = insertFixture("INCOMPLETE", WriterMode.TEMPORAL, true, 5, 3);
        authoritativeStateReader.answer(
                new Incomplete(
                        new AuthoritativeProcessObservation(
                                TENANT,
                                fixture.caseId(),
                                fixture.workflowId(),
                                RUN_2,
                                "HEARING_PENDING",
                                RoomType.EVIDENCE,
                                2,
                                6,
                                11,
                                20),
                        "CASE_PROCESS_SNAPSHOT_V1_INCOMPLETE_FOR_REPAIR"));

        var result = reconciler.reconcile(fixture.target());

        assertThat(result.outcome()).isEqualTo(Outcome.DRIFT_DETECTED);
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(5);
        assertThat(longValue("case_room_epoch", "process_revision", fixture.epochId()))
                .isEqualTo(5);
        assertThat(stringValue("process_reconciliation_issue", "issue_status", fixture.caseId()))
                .isEqualTo("OPEN");
    }

    @Test
    void staleAuthoritativeStateCannotDowngradeAProjection() {
        Fixture fixture = insertFixture("AUTHORITY_STALE", WriterMode.TEMPORAL, true, 6, 4);
        authoritativeStateReader.answer(verified(state(fixture, 5, 3, RUN_1)));

        var result = reconciler.reconcile(fixture.target());

        assertThat(result.outcome()).isEqualTo(Outcome.REPAIR_REJECTED);
        assertThat(result.reasonCode()).isEqualTo("AUTHORITATIVE_STATE_STALE");
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(6);
        assertThat(longValue("case_room_epoch", "process_revision", fixture.epochId()))
                .isEqualTo(6);
        assertThat(stringValue("process_reconciliation_issue", "issue_status", fixture.caseId()))
                .isEqualTo("OPEN");
    }

    @Test
    void aProjectionBoundToAnotherFenceRequiresManualRecovery() {
        Fixture fixture =
                insertFixture("PROJECTION_FENCE", WriterMode.TEMPORAL, true, 5, 3, 16);
        authoritativeStateReader.answer(verified(state(fixture, 6, 4, RUN_1)));

        var result = reconciler.reconcile(fixture.target());

        assertThat(result.outcome()).isEqualTo(Outcome.REPAIR_REJECTED);
        assertThat(result.reasonCode()).isEqualTo("LOCAL_PROJECTION_FENCE_MISMATCH");
        assertThat(longValue("case_process_projection", "process_revision", fixture.caseId()))
                .isEqualTo(5);
        assertThat(longValue("case_process_projection", "fencing_token", fixture.caseId()))
                .isEqualTo(16);
        assertThat(longValue("case_room_epoch", "process_revision", fixture.epochId()))
                .isEqualTo(5);
    }

    private Fixture insertFixture(
            String suffix,
            WriterMode writerMode,
            boolean includeProjection,
            long processRevision,
            long roomRevision) {
        return insertFixture(
                suffix, writerMode, includeProjection, processRevision, roomRevision, 17);
    }

    private Fixture insertFixture(
            String suffix,
            WriterMode writerMode,
            boolean includeProjection,
            long processRevision,
            long roomRevision,
            long projectionFencingToken) {
        String caseId = "CASE_Reconcile" + suffix;
        String roomId = "ROOM_Reconcile" + suffix;
        String epochId = "EPOCH_Reconcile" + suffix;
        String workflowId = "case-process:" + TENANT + ":" + caseId;
        String roomWorkflowId =
                CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.EVIDENCE, 2);
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'EVIDENCE_OPEN', 'USER', ?,
                    'MERCHANT', ?, 'HIGH', 'Reconciliation test case',
                    'Projection drift fixture.', 'EVIDENCE', 'test', 'test')
                """,
                caseId,
                "user-" + suffix,
                "merchant-" + suffix,
                "create-reconciliation-" + suffix,
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
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, provisioning_status,
                    process_revision, room_revision, fencing_token,
                    temporal_workflow_id, temporal_run_id,
                    room_temporal_workflow_id, room_temporal_run_id, temporal_build_id,
                    graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    activated_at, provisioned_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'EVIDENCE', 2, ?, 'ACTIVE', 'READY', ?, ?, 17,
                    ?, ?, ?, ?, ?, 'evidence.v2', '1.0.0', 'checkpoint.v1',
                    'agent_stream.v1', 'room-epoch-selection.v1',
                    'case-process-contract.v1', 'CaseProcessWorkflow', ?, ?, ?, ?)
                """,
                epochId,
                TENANT,
                caseId,
                roomId,
                writerMode.name(),
                processRevision,
                roomRevision,
                workflowId,
                RUN_1,
                roomWorkflowId,
                "room-run-reconciliation-1",
                BUILD,
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC));
        if (includeProjection) {
            jdbc.update(
                    """
                    insert into case_process_projection (
                        case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                        writer_mode, writer_activation_status, process_revision, room_epoch, fencing_token,
                        last_command_sequence, last_case_event_sequence,
                        temporal_workflow_id, temporal_run_id, temporal_build_id,
                        projected_at, updated_at
                    ) values (?, ?, 'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN', ?, 'READY', ?, 2, ?,
                        10, 19, ?, ?, ?, ?, ?)
                    """,
                    caseId,
                    TENANT,
                    writerMode.name(),
                    processRevision,
                    projectionFencingToken,
                    workflowId,
                    RUN_1,
                    BUILD,
                    OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(NOW.minusSeconds(30), ZoneOffset.UTC));
        }
        return new Fixture(caseId, roomId, epochId, workflowId);
    }

    private static AuthoritativeProcessState state(
            Fixture fixture, long processRevision, long roomRevision, String runId) {
        return new AuthoritativeProcessState(
                TENANT,
                fixture.caseId(),
                "HEARING_PENDING",
                "EVIDENCE",
                "SEALED",
                RoomType.EVIDENCE,
                2,
                processRevision,
                roomRevision,
                17,
                11,
                20,
                NOW.plusSeconds(1800),
                fixture.workflowId(),
                runId,
                BUILD,
                "urn:test:reconciliation:" + fixture.caseId().toLowerCase(),
                "c".repeat(64));
    }

    private static Verified verified(AuthoritativeProcessState state) {
        return new Verified(
                state,
                "temporal:workflow/"
                        + state.temporalWorkflowId()
                        + "/run/"
                        + state.temporalRunId());
    }

    private long longValue(String table, String column, String id) {
        String idColumn = table.equals("case_process_projection") ? "case_id" : "id";
        return jdbc.queryForObject(
                "select " + column + " from " + table + " where " + idColumn + " = ?",
                Long.class,
                id);
    }

    private String stringValue(String table, String column, String caseId) {
        String idColumn =
                table.equals("case_room_epoch") ? "id" : "case_id";
        return jdbc.queryForObject(
                "select " + column + " from " + table + " where " + idColumn + " = ?",
                String.class,
                caseId);
    }

    private long countIssues(String caseId) {
        return jdbc.queryForObject(
                "select count(*) from process_reconciliation_issue where case_id = ?",
                Long.class,
                caseId);
    }

    private record Fixture(String caseId, String roomId, String epochId, String workflowId) {

        ReconciliationTarget target() {
            return new ReconciliationTarget(TENANT, caseId, workflowId);
        }
    }

    static final class MutableAuthoritativeProcessStateReader
            implements AuthoritativeProcessStateReader {

        private ReadResult answer;

        void answer(ReadResult answer) {
            this.answer = Objects.requireNonNull(answer, "answer");
        }

        @Override
        public ReadResult read(ReconciliationTarget target) {
            return Objects.requireNonNull(answer, "test authoritative answer is not configured");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReconciliationTestConfiguration {

        @Bean
        @Primary
        Clock reconciliationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper reconciliationObjectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MutableAuthoritativeProcessStateReader authoritativeProcessStateReader() {
            return new MutableAuthoritativeProcessStateReader();
        }

        @Bean
        ProcessProjectionReconciliationProperties reconciliationProperties() {
            return new ProcessProjectionReconciliationProperties(
                    true, 32, Duration.ofMinutes(5), Duration.ofSeconds(30));
        }
    }
}
