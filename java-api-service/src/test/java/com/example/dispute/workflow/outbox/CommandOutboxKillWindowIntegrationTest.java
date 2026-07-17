package com.example.dispute.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.TenantAuthorityProperties;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandDeliveryTrigger;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.config.CommandOutboxProperties;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore;
import com.example.dispute.workflow.infrastructure.outbox.ClaimedCaseCommandDelivery;
import com.example.dispute.workflow.infrastructure.outbox.TemporalCommandDispatcher;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.security.ConfiguredTenantAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @Autowired private DeduplicatingTemporalGateway temporalGateway;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetCollaborators() {
        clock.set(NOW);
        temporalGateway.clear();
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

        assertThat(dispatcher().dispatchAvailable()).isEqualTo(1);

        assertThat(temporalGateway.attemptedUpdateIds()).containsExactly(commandId);
        assertThat(temporalGateway.logicalAdmissionCount()).isEqualTo(1);
        assertThat(outboxStatus(outboxId)).isEqualTo("DELIVERED");
        assertThat(commandStatus(commandId)).isEqualTo("ORCHESTRATION_ACCEPTED");
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
        String admittedRunId =
                temporalGateway
                        .deliver(abandoned.toGatewayRequest())
                        .temporalRunId();

        assertThat(outboxStatus(outboxId)).isEqualTo("CLAIMED");
        assertThat(commandStatus(commandId)).isEqualTo("PENDING_ORCHESTRATION");

        clock.advance(LEASE.plusSeconds(1));
        assertThat(dispatcher().dispatchAvailable()).isEqualTo(1);

        assertThat(temporalGateway.attemptedUpdateIds())
                .containsExactly(commandId, commandId);
        assertThat(temporalGateway.logicalAdmissionCount()).isEqualTo(1);
        assertThat(outboxAttemptCount(outboxId)).isEqualTo(2);
        assertThat(outboxTemporalRunId(outboxId)).isEqualTo(admittedRunId);
        assertThat(outboxStatus(outboxId)).isEqualTo("DELIVERED");
        assertThat(commandStatus(commandId)).isEqualTo("ORCHESTRATION_ACCEPTED");
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

    private TemporalCommandDispatcher dispatcher() {
        return new TemporalCommandDispatcher(
                outboxStore,
                temporalGateway,
                new CommandOutboxProperties(
                        true,
                        10,
                        LEASE,
                        3,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5)),
                clock);
    }

    private void insertEvidenceCase(String caseId, String userId) {
        String suffix = caseId.substring("CASE_".length());
        String merchantId = "merchant-" + suffix;
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
                "ROOM_" + suffix,
                caseId,
                offsetNow());
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, process_revision, room_epoch, fencing_token
                ) values (?, 'legacy-default', 'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN',
                    'LEGACY', 0, 0, 0)
                """,
                caseId);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, stream_protocol, activated_at
                ) values (?, 'legacy-default', ?, ?, 'EVIDENCE', 0, 'LEGACY',
                    'ACTIVE', 0, 0, 0, 'agent_stream.v1', ?)
                """,
                "EPOCH_" + suffix,
                caseId,
                "ROOM_" + suffix,
                offsetNow());
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

        @Bean
        DeduplicatingTemporalGateway temporalGateway() {
            return new DeduplicatingTemporalGateway();
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

    static final class DeduplicatingTemporalGateway implements TemporalUpdateGateway {

        private final Map<String, String> admittedRuns = new LinkedHashMap<>();
        private final List<String> attemptedUpdateIds = new ArrayList<>();

        @Override
        public synchronized DeliveryReceipt deliver(UpdateWithStartRequest request) {
            attemptedUpdateIds.add(request.updateId());
            String runId =
                    admittedRuns.computeIfAbsent(
                            request.updateId(), updateId -> "run-" + updateId);
            return new DeliveryReceipt(runId);
        }

        synchronized void clear() {
            admittedRuns.clear();
            attemptedUpdateIds.clear();
        }

        synchronized int logicalAdmissionCount() {
            return admittedRuns.size();
        }

        synchronized List<String> attemptedUpdateIds() {
            return List.copyOf(attemptedUpdateIds);
        }
    }
}
