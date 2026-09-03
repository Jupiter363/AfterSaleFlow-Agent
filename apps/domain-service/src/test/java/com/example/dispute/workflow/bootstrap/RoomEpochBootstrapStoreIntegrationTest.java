package com.example.dispute.workflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapStore;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningMapper;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
@Import({
    RoomEpochBootstrapStore.class,
    RoomEpochProvisioningMapper.class,
    RoomEpochBootstrapStoreIntegrationTest.BootstrapStoreTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomEpochBootstrapStoreIntegrationTest {

    private static final String TENANT = "tenant-bootstrap-store";
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 18, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final Duration LEASE = Duration.ofMinutes(2);

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "room_epoch_bootstrap_store")
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
                                + "/room_epoch_bootstrap_store");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private RoomEpochBootstrapStore store;
    @Autowired private CaseRoomEpochRepository epochRepository;
    @Autowired private CaseProcessProjectionRepository projectionRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void completionLossIsRecoveredWithTheSamePayloadAndAtomicReceiptBinding() {
        Fixture fixture = insertPreparingFixture("COMPLETION_LOSS");
        String outboxId = enqueue(fixture);
        var first = store.claimById(outboxId, NOW, LEASE).orElseThrow();
        assertThat(store.beginProvisioning(first, NOW.plusSeconds(1))).isTrue();
        var stableReceipt = RoomEpochProvisioningFixtures.receipt(first.command());

        assertThat(
                        store.finalizeProvisioning(
                                first, stableReceipt, first.leaseExpiresAt().plusSeconds(1)))
                .isFalse();

        var reclaimed =
                store.claimById(
                                outboxId,
                                first.leaseExpiresAt().plusSeconds(1),
                                LEASE)
                        .orElseThrow();
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.updateId()).isEqualTo(first.updateId());
        assertThat(reclaimed.payloadSha256()).isEqualTo(first.payloadSha256());
        assertThat(reclaimed.command()).isEqualTo(first.command());
        assertThat(store.beginProvisioning(reclaimed, NOW.plusMinutes(3))).isTrue();
        assertThat(
                        store.finalizeProvisioning(
                                reclaimed, stableReceipt, NOW.plusMinutes(3).plusSeconds(1)))
                .isTrue();

        assertThat(epochState(fixture.epochId()))
                .isEqualTo("ACTIVE:READY:case-first-run:room-first-run");
        assertThat(projectionState(fixture.caseId()))
                .isEqualTo("READY:case-first-run");
        assertThat(outboxState(outboxId))
                .isEqualTo("DELIVERED:case-first-run:room-first-run");
        assertReadyCannotMoveBackward(fixture);
    }

    @Test
    void retryPersistenceTruncatesErrorsAndReusesTheSameUpdateIdentity() {
        Fixture fixture = insertPreparingFixture("RETRY");
        String outboxId = enqueue(fixture);
        var first = store.claimById(outboxId, NOW, LEASE).orElseThrow();
        assertThat(store.beginProvisioning(first, NOW.plusSeconds(1))).isTrue();

        assertThat(
                        store.markRetry(
                                first,
                                "X".repeat(200),
                                "detail".repeat(1_000),
                                NOW.plusSeconds(10),
                                NOW.plusSeconds(2)))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select length(last_error_code) from room_epoch_bootstrap_outbox where id = ?",
                                Integer.class,
                                outboxId))
                .isEqualTo(64);
        assertThat(
                        jdbc.queryForObject(
                                "select length(last_error_detail) from room_epoch_bootstrap_outbox where id = ?",
                                Integer.class,
                                outboxId))
                .isEqualTo(4096);

        var reclaimed =
                store.claimById(outboxId, NOW.plusSeconds(11), LEASE).orElseThrow();
        assertThat(reclaimed.updateId()).isEqualTo(first.updateId());
        assertThat(reclaimed.payloadSha256()).isEqualTo(first.payloadSha256());
    }

    @Test
    void permanentConflictFailsEpochProjectionAndOutboxInOneTransaction() {
        Fixture fixture = insertPreparingFixture("PERMANENT");
        String outboxId = enqueue(fixture);
        var claimed = store.claimById(outboxId, NOW, LEASE).orElseThrow();
        assertThat(store.beginProvisioning(claimed, NOW.plusSeconds(1))).isTrue();

        assertThat(
                        store.deadLetter(
                                claimed,
                                "ROOM_EPOCH_UPDATE_ID_CONFLICT",
                                "payload mismatch",
                                NOW.plusSeconds(2)))
                .isTrue();

        assertThat(epochFailureState(fixture.epochId()))
                .isEqualTo(
                        "PROVISIONING_FAILED:FAILED:ROOM_EPOCH_UPDATE_ID_CONFLICT");
        assertThat(projectionState(fixture.caseId())).isEqualTo("FAILED:null");
        assertThat(
                        jdbc.queryForObject(
                                "select outbox_status from room_epoch_bootstrap_outbox where id = ?",
                                String.class,
                                outboxId))
                .isEqualTo("DEAD_LETTER");
    }

    @Test
    void permanentShadowConflictAtomicallyFallsBackToAHigherLegacyFence() {
        Fixture fixture = insertShadowFixture("SHADOW_FALLBACK");
        String outboxId = enqueue(fixture);
        var claimed = store.claimById(outboxId, NOW, LEASE).orElseThrow();
        assertThat(store.beginProvisioning(claimed, NOW.plusSeconds(1))).isTrue();

        assertThat(
                        store.deadLetter(
                                claimed,
                                "ROOM_EPOCH_CHILD_START_CONFLICT",
                                "room workflow id conflict",
                                NOW.plusSeconds(2)))
                .isTrue();

        assertThat(epochFailureState(fixture.epochId()))
                .isEqualTo(
                        "TERMINAL:FAILED:ROOM_EPOCH_CHILD_START_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                """
                                select writer_mode || ':' || room_epoch || ':' ||
                                       fencing_token || ':' || process_revision || ':' ||
                                       provisioning_status
                                  from case_room_epoch
                                 where case_id = ? and lifecycle_status = 'ACTIVE'
                                """,
                                String.class,
                                fixture.caseId()))
                .isEqualTo("LEGACY:2:8:11:NOT_REQUIRED");
        assertThat(
                        jdbc.queryForObject(
                                """
                                select writer_mode || ':' || writer_activation_status || ':' ||
                                       room_epoch || ':' || fencing_token || ':' ||
                                       process_revision || ':' || temporal_build_id || ':' ||
                                       coalesce(temporal_workflow_id, 'null')
                                  from case_process_projection where case_id = ?
                                """,
                                String.class,
                                fixture.caseId()))
                .isEqualTo("LEGACY:READY:2:8:11:legacy-java.v1:null");
        assertThat(
                        jdbc.queryForObject(
                                "select outbox_status from room_epoch_bootstrap_outbox where id = ?",
                                String.class,
                                outboxId))
                .isEqualTo("DEAD_LETTER");
    }

    @Test
    void shadowFallbackRollsBackWhenDeadLetterPersistenceFails() {
        Fixture fixture = insertShadowFixture("SHADOW_ROLLBACK");
        String outboxId = enqueue(fixture);
        var claimed = store.claimById(outboxId, NOW, LEASE).orElseThrow();
        assertThat(store.beginProvisioning(claimed, NOW.plusSeconds(1))).isTrue();
        installDeadLetterFailureTrigger(outboxId);

        try {
            assertThatThrownBy(
                            () ->
                                    store.deadLetter(
                                            claimed,
                                            "ROOM_EPOCH_CHILD_START_CONFLICT",
                                            "room workflow id conflict",
                                            NOW.plusSeconds(2)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("forced bootstrap dead letter failure");
        } finally {
            removeDeadLetterFailureTrigger();
        }

        assertThat(
                        jdbc.queryForObject(
                                """
                                select lifecycle_status || ':' || provisioning_status
                                  from case_room_epoch where id = ?
                                """,
                                String.class,
                                fixture.epochId()))
                .isEqualTo("ACTIVE:PROVISIONING");
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*) from case_room_epoch
                                 where case_id = ? and writer_mode = 'LEGACY'
                                """,
                                Long.class,
                                fixture.caseId()))
                .isZero();
        assertThat(projectionState(fixture.caseId())).isEqualTo("PROVISIONING:null");
        assertThat(
                        jdbc.queryForObject(
                                "select outbox_status from room_epoch_bootstrap_outbox where id = ?",
                                String.class,
                                outboxId))
                .isEqualTo("CLAIMED");
    }

    private String enqueue(Fixture fixture) {
        return inTransaction(
                () ->
                        store.enqueue(
                                epochRepository
                                        .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                                                fixture.caseId(), RoomType.EVIDENCE, 1)
                                        .orElseThrow(),
                                projectionRepository
                                        .findByIdForUpdate(fixture.caseId())
                                        .orElseThrow(),
                                NOW));
    }

    private Fixture insertPreparingFixture(String suffix) {
        return insertFixture(suffix, "TEMPORAL", "PREPARING");
    }

    private Fixture insertShadowFixture(String suffix) {
        return insertFixture(suffix, "SHADOW", "ACTIVE");
    }

    private Fixture insertFixture(
            String suffix, String writerMode, String lifecycleStatus) {
        String caseId = "CASE_BOOTSTRAP_" + suffix;
        String roomId = "ROOM_BOOTSTRAP_" + suffix;
        String epochId = "EPOCH_BOOTSTRAP_" + suffix;
        String caseWorkflowId = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, caseId);
        String roomWorkflowId =
                CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.EVIDENCE, 1);
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'EVIDENCE_OPEN', 'USER', ?,
                    'MERCHANT', ?, 'LOW', 'Bootstrap store fixture',
                    'Durable room epoch bootstrap fixture.', 'EVIDENCE', 'test', 'test')
                """,
                caseId,
                "user-" + suffix,
                "merchant-" + suffix,
                "create-" + suffix,
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
                NOW);
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
                    activated_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'EVIDENCE', 1, ?, ?, 'PENDING',
                    10, 3, 7, ?, null, ?, null, 'build-1', 'evidence.graph', 'graph-v1',
                    'checkpoint-v1', 'agent-stream.v1', 'room-epoch-selection.v1',
                    'case-process-contract.v1', 'EvidenceRoomWorkflow', ?, ?, ?)
                """,
                epochId,
                TENANT,
                caseId,
                roomId,
                writerMode,
                lifecycleStatus,
                caseWorkflowId,
                roomWorkflowId,
                NOW,
                NOW,
                NOW);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, writer_activation_status, process_revision, room_epoch,
                    fencing_token, last_command_sequence, last_case_event_sequence,
                    projected_deadline_at, temporal_workflow_id, temporal_run_id,
                    temporal_build_id, projected_at, updated_at
                ) values (?, ?, 'EVIDENCE', 'EVIDENCE', 'OPEN', ?, 'PREPARING',
                    10, 1, 7, 4, 6, ?, ?, null, 'build-1', ?, ?)
                """,
                caseId,
                TENANT,
                writerMode,
                NOW.plusHours(1),
                caseWorkflowId,
                NOW,
                NOW);
        return new Fixture(caseId, epochId);
    }

    private void installDeadLetterFailureTrigger(String outboxId) {
        jdbc.execute(
                """
                create or replace function reject_test_bootstrap_dead_letter()
                returns trigger language plpgsql as $$
                begin
                    if new.id = '%s' and new.outbox_status = 'DEAD_LETTER' then
                        raise exception 'forced bootstrap dead letter failure';
                    end if;
                    return new;
                end
                $$
                """.formatted(outboxId));
        jdbc.execute(
                """
                create trigger trg_test_reject_bootstrap_dead_letter
                before update on room_epoch_bootstrap_outbox
                for each row execute function reject_test_bootstrap_dead_letter()
                """);
    }

    private void removeDeadLetterFailureTrigger() {
        jdbc.execute(
                "drop trigger if exists trg_test_reject_bootstrap_dead_letter on room_epoch_bootstrap_outbox");
        jdbc.execute("drop function if exists reject_test_bootstrap_dead_letter()");
    }

    private void assertReadyCannotMoveBackward(Fixture fixture) {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update case_room_epoch set provisioning_status = 'PROVISIONING' where id = ?",
                                        fixture.epochId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("provisioning status cannot move backward");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update case_process_projection set writer_activation_status = 'PREPARING' where case_id = ?",
                                        fixture.caseId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("activation status cannot move backward");
    }

    private String epochState(String epochId) {
        return jdbc.queryForObject(
                """
                select lifecycle_status || ':' || provisioning_status || ':' ||
                       temporal_run_id || ':' || room_temporal_run_id
                  from case_room_epoch where id = ?
                """,
                String.class,
                epochId);
    }

    private String epochFailureState(String epochId) {
        return jdbc.queryForObject(
                """
                select lifecycle_status || ':' || provisioning_status || ':' ||
                       provisioning_failure_code
                  from case_room_epoch where id = ?
                """,
                String.class,
                epochId);
    }

    private String projectionState(String caseId) {
        return jdbc.queryForObject(
                """
                select writer_activation_status || ':' || coalesce(temporal_run_id, 'null')
                  from case_process_projection where case_id = ?
                """,
                String.class,
                caseId);
    }

    private String outboxState(String outboxId) {
        return jdbc.queryForObject(
                """
                select outbox_status || ':' || case_temporal_run_id || ':' ||
                       room_temporal_run_id
                  from room_epoch_bootstrap_outbox where id = ?
                """,
                String.class,
                outboxId);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private record Fixture(String caseId, String epochId) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class BootstrapStoreTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }
    }
}
