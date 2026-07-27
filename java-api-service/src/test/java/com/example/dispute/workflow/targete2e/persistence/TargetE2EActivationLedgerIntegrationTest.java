package com.example.dispute.workflow.targete2e.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ActivationLifecycle;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ActivationRegistration;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.BuildBindings;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandCompletion;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.DatabaseBinding;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ExplicitCaseScope;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.GraphBinding;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ImageDigests;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.RegistrationDisposition;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.SyntheticCaseScope;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class TargetE2EActivationLedgerIntegrationTest {

    @Container
    static final GenericContainer<?> POSTGRES =
            new GenericContainer<>(DockerImageName.parse(
                            "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "target_e2e_ledger")
                    .withEnv("POSTGRES_USER", "target_test")
                    .withEnv("POSTGRES_PASSWORD", "target_test")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    private static DriverManagerDataSource dataSource;
    private static TargetE2EActivationLedger ledger;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES.getHost() + ':' + POSTGRES.getMappedPort(5432)
                        + "/target_e2e_ledger",
                "target_test",
                "target_test");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        ledger = new TargetE2EActivationLedger(dataSource, Clock.systemUTC());
    }

    @Test
    void activationCaseCommandAndEpochAuthorityFailClosedAcrossHaAndDrain() throws Exception {
        Instant now = Instant.now();
        ActivationRegistration synthetic = syntheticRegistration(
                "p9act.v1." + "1".repeat(32),
                "nonce-" + "1".repeat(32),
                1,
                now.minusSeconds(30),
                now.plusSeconds(90));

        assertThat(ledger.registerOrAttach(synthetic).disposition())
                .isEqualTo(RegistrationDisposition.REGISTERED);
        assertThat(ledger.registerOrAttach(synthetic).disposition())
                .isEqualTo(RegistrationDisposition.ATTACHED_EXISTING);
        assertThat(count("select count(*) from target_e2e_activation")).isEqualTo(1);
        assertThat(count("select count(*) from target_e2e_environment_generation_watermark"))
                .isEqualTo(1);
        assertThat(ledger.transition(
                        synthetic.activationId(),
                        ActivationLifecycle.REGISTERED,
                        ActivationLifecycle.ACTIVE))
                .isEqualTo(ActivationLifecycle.ACTIVE);

        ActivationRegistration nonceReplay = syntheticRegistration(
                "p9act.v1." + "2".repeat(32),
                synthetic.nonce(),
                2,
                now.minusSeconds(30),
                now.plusSeconds(90));
        assertCode(() -> ledger.registerOrAttach(nonceReplay), "ACTIVATION_REGISTRATION_FAILED");
        ActivationRegistration generationReplay = syntheticRegistration(
                "p9act.v1." + "3".repeat(32),
                "nonce-" + "3".repeat(32),
                1,
                now.minusSeconds(30),
                now.plusSeconds(90));
        assertCode(() -> ledger.registerOrAttach(generationReplay), "ACTIVATION_REGISTRATION_FAILED");

        var first = ledger.reserveCase(synthetic.activationId(), "CASE_P9_SYNTHETIC_0001");
        assertThat(ledger.reserveCase(synthetic.activationId(), "CASE_P9_SYNTHETIC_0001"))
                .isEqualTo(first);
        assertThat(ledger.reserveCase(synthetic.activationId(), "CASE_P9_SYNTHETIC_0002").slotNumber())
                .isEqualTo(2);
        assertThat(count("select count(*) from target_e2e_generated_case_tombstone"))
                .isEqualTo(2);
        assertCode(
                () -> ledger.reserveCase(synthetic.activationId(), "CASE_P9_SYNTHETIC_0003"),
                "CASE_SCOPE_EXHAUSTED");
        assertCode(
                () -> ledger.reserveCase(synthetic.activationId(), "WRONG_PREFIX_1"),
                "CASE_SCOPE_MISMATCH");

        ActivationRegistration expiring = explicitRegistration(
                "p9act.v1." + "4".repeat(32),
                "nonce-" + "4".repeat(32),
                2,
                "CASE_P9_EXPLICIT_001",
                now.minusSeconds(30),
                now.plusSeconds(8));
        assertThat(ledger.registerOrAttach(expiring).disposition())
                .isEqualTo(RegistrationDisposition.REGISTERED);
        assertThat(ledger.transition(
                        expiring.activationId(),
                        ActivationLifecycle.REGISTERED,
                        ActivationLifecycle.ACTIVE))
                .isEqualTo(ActivationLifecycle.ACTIVE);
        assertCode(
                () -> ledger.reserveCase(expiring.activationId(), "CASE_P9_SYNTHETIC_0001"),
                "CASE_ID_ALREADY_TOMBSTONED");

        seedShadowSelection();
        seedTargetEpochAndSelection(expiring);
        assertThat(text("""
                select writer_mode || ':' || execution_lane
                  from case_intake_epoch_selection_binding
                 where epoch_id = 'EPOCH_SHADOW_P9'
                """))
                .isEqualTo("SHADOW:SIGNED_SYNTHETIC_SHADOW");
        assertThat(text("""
                select writer_mode || ':' || execution_lane
                  from case_intake_epoch_selection_binding
                 where epoch_id = 'EPOCH_TARGET_P9'
                """))
                .isEqualTo("TEMPORAL:TARGET_E2E_CANDIDATE");

        CommandAdmission accepted = new CommandAdmission(
                expiring.activationId(),
                expiring.manifestHash(),
                expiring.domainDatabase().bindingHash(),
                expiring.tenantSurrogate(),
                "CASE_P9_EXPLICIT_001",
                "command-before-expiry",
                "a".repeat(64),
                "9".repeat(64),
                0,
                1);
        var acceptedResult = ledger.admitCommand(accepted);
        assertThat(acceptedResult.disposition().name()).isEqualTo("ADMITTED");

        Thread.sleep(Duration.ofSeconds(9));
        assertThat(ledger.registerOrAttach(expiring).disposition())
                .isEqualTo(RegistrationDisposition.ATTACHED_DRAIN_ONLY);
        assertThat(ledger.admitCommand(accepted).disposition().name())
                .isEqualTo("ALREADY_ADMITTED_DRAIN_ONLY");
        assertCode(
                () -> ledger.admitCommand(new CommandAdmission(
                        expiring.activationId(),
                        expiring.manifestHash(),
                        expiring.domainDatabase().bindingHash(),
                        expiring.tenantSurrogate(),
                        "CASE_P9_EXPLICIT_001",
                        "command-after-expiry",
                        "b".repeat(64),
                        "8".repeat(64),
                        0,
                        1)),
                "COMMAND_ADMISSION_FAILED");

        assertThat(text("""
                select lifecycle_status from target_e2e_activation
                 where activation_id = 'p9act.v1.44444444444444444444444444444444'
                """)).isEqualTo("DRAIN_ONLY");
        assertCode(
                () -> ledger.transition(
                        expiring.activationId(),
                        ActivationLifecycle.DRAIN_ONLY,
                        ActivationLifecycle.DRAINED),
                "ACTIVATION_TRANSITION_FAILED");
        ledger.completeCommand(new CommandCompletion(
                acceptedResult.admissionId(),
                accepted.activationId(),
                accepted.commandId(),
                accepted.commandHash(),
                accepted.commandEnvelopeHash(),
                "7".repeat(64)));
        assertThat(ledger.transition(
                        expiring.activationId(),
                        ActivationLifecycle.DRAIN_ONLY,
                        ActivationLifecycle.DRAINED))
                .isEqualTo(ActivationLifecycle.DRAINED);
        assertCode(
                () -> ledger.revokeTerminal(expiring.activationId(), true, false),
                "ACTIVATION_REVOKE_PRECONDITION");
        assertThat(ledger.revokeTerminal(expiring.activationId(), true, true))
                .isEqualTo(ActivationLifecycle.REVOKED_TERMINAL);
        assertCode(
                () -> ledger.transition(
                        expiring.activationId(),
                        ActivationLifecycle.REVOKED_TERMINAL,
                        ActivationLifecycle.ACTIVE),
                "ACTIVATION_TRANSITION_FAILED");
        assertSqlFails(
                "update target_e2e_activation set candidate_sha = repeat('f', 40) "
                        + "where activation_id = '" + synthetic.activationId() + "'",
                "immutable binding cannot be rewritten");
        assertSqlFails(
                "delete from target_e2e_case_reservation where case_id = 'CASE_P9_SYNTHETIC_0001'",
                "append-only");
    }

    private static void seedShadowSelection() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(caseInsert("CASE_P9_SHADOW", "INTAKE"));
            statement.executeUpdate("""
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at, created_by, updated_by
                    ) values ('ROOM_SHADOW_P9', 'CASE_P9_SHADOW', 'INTAKE', 'OPEN', now(), 'test', 'test')
                    """);
            statement.executeUpdate(epochInsert(
                    "EPOCH_SHADOW_P9", "CASE_P9_SHADOW", "ROOM_SHADOW_P9", "SHADOW",
                    "intake.v2", "shadow-graph-v2", "shadow-checkpoint-v2",
                    "shadow-case-build", "shadow-room-build"));
            statement.executeUpdate("""
                    insert into case_intake_epoch_selection_binding (
                        epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
                        selection_hash, writer_mode, case_workflow_type, case_workflow_build_id,
                        room_workflow_type, room_workflow_build_id, process_contract_version,
                        graph_key, graph_version, checkpoint_schema_version, state_schema_version,
                        stream_protocol, prompt_version, model_profile_id, output_schema_version,
                        policy_version, guardrail_version, tool_policy_version,
                        cohort_policy_version, agent_key, agent_session_profile_version,
                        memory_policy_id
                    ) values (
                        'EPOCH_SHADOW_P9', 'tenant-p9', 'CASE_P9_SHADOW', 'INTAKE', 0, 1,
                        repeat('1', 64), 'SHADOW', 'CaseProcessWorkflow', 'shadow-case-build',
                        'IntakeRoomWorkflow', 'shadow-room-build', 'case-process-contract.v1',
                        'intake.v2', 'shadow-graph-v2', 'shadow-checkpoint-v2',
                        'intake-graph-state.v2', 'agent-stream.v2', 'intake-prompt.v2',
                        'model-v2', 'intake-turn-proposal.v2', 'policy-v2', 'guardrail-v2',
                        'no-tools.v1', 'cohort-v2', 'DISPUTE_INTAKE_OFFICER',
                        'agent-session-profile.v1', 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'
                    )
                    """);
        }
    }

    private static void seedTargetEpochAndSelection(ActivationRegistration registration)
            throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(caseInsert("CASE_P9_EXPLICIT_001", "INTAKE"));
            statement.executeUpdate("""
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at, created_by, updated_by
                    ) values ('ROOM_TARGET_P9', 'CASE_P9_EXPLICIT_001', 'INTAKE', 'OPEN', now(), 'test', 'test')
                    """);
            statement.executeUpdate(epochInsert(
                    "EPOCH_TARGET_P9", "CASE_P9_EXPLICIT_001", "ROOM_TARGET_P9", "TEMPORAL",
                    registration.graph().key(), registration.graph().version(),
                    registration.graph().checkpointSchemaVersion(),
                    registration.builds().caseBuildId(), registration.builds().controlBuildId()));
            String base = """
                    insert into case_intake_epoch_selection_binding (
                        epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
                        selection_hash, writer_mode, case_workflow_type, case_workflow_build_id,
                        room_workflow_type, room_workflow_build_id, process_contract_version,
                        graph_key, graph_version, checkpoint_schema_version, state_schema_version,
                        stream_protocol, prompt_version, model_profile_id, output_schema_version,
                        policy_version, guardrail_version, tool_policy_version,
                        cohort_policy_version, agent_key, agent_session_profile_version,
                        memory_policy_id%s
                    ) values (
                        'EPOCH_TARGET_P9', 'tenant-p9', 'CASE_P9_EXPLICIT_001', 'INTAKE', 0, 1,
                        repeat('2', 64), 'TEMPORAL', 'CaseProcessWorkflow', 'case-build',
                        'IntakeRoomWorkflow', 'control-build', 'case-process-contract.v1',
                        'all-rooms/target-e2e.v1', 'target-graph-v1', 'target-checkpoint-v1',
                        'target-e2e-graph-state.v1', 'agent-stream.v2', 'target-prompt-v1',
                        'target-model-v1', 'target-room-proposal.v1', 'target-policy-v1',
                        'target-guardrail-v1', 'no-tools.v1', 'target-cohort-v1',
                        'DISPUTE_INTAKE_OFFICER', 'agent-session-profile.v1',
                        'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'%s
                    )
                    """;
            assertSqlFails(
                    connection,
                    base.formatted("", ""),
                    "target E2E TEMPORAL selection requires a live ACTIVE activation");
            statement.executeUpdate(base.formatted(
                    ", activation_id, activation_manifest_hash, execution_lane, isolated_domain_db_binding_hash",
                    ", '" + registration.activationId() + "', '" + registration.manifestHash()
                            + "', 'TARGET_E2E_CANDIDATE', '"
                            + registration.domainDatabase().bindingHash() + "'"));
        }
    }

    private static String caseInsert(String caseId, String currentRoom) {
        return """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (
                    '%s', 'user-p9', 'merchant-p9', 'create-%s', 'DISPUTE',
                    'INTAKE_IN_PROGRESS', 'USER', 'user-p9', 'MERCHANT', 'merchant-p9',
                    'LOW', 'Target E2E test', 'Target E2E persistence test',
                    '%s', 'test', 'test'
                )
                """.formatted(caseId, caseId, currentRoom);
    }

    private static String epochInsert(
            String epochId,
            String caseId,
            String roomId,
            String writerMode,
            String graphKey,
            String graphVersion,
            String checkpointVersion,
            String caseBuild,
            String roomBuild) {
        return """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, provisioning_status, process_revision,
                    room_revision, fencing_token, temporal_workflow_id, temporal_run_id,
                    room_temporal_workflow_id, room_temporal_run_id, temporal_build_id,
                    graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    room_workflow_type, room_workflow_build_id, activated_at, provisioned_at,
                    created_at, updated_at
                ) values (
                    '%s', 'tenant-p9', '%s', '%s', 'INTAKE', 0, '%s', 'ACTIVE', 'READY',
                    0, 0, 1, 'case-wf-%s', 'case-run-%s', 'room-wf-%s', 'room-run-%s',
                    '%s', '%s', '%s', '%s', 'agent-stream.v2', 'room-epoch-selection.v2',
                    'case-process-contract.v1', 'CaseProcessWorkflow', 'IntakeRoomWorkflow',
                    '%s', now(), now(), now(), now()
                )
                """.formatted(
                epochId, caseId, roomId, writerMode, caseId, caseId, caseId, caseId,
                caseBuild, graphKey, graphVersion, checkpointVersion, roomBuild);
    }

    private static ActivationRegistration syntheticRegistration(
            String activationId,
            String nonce,
            long generation,
            Instant issuedAt,
            Instant expiresAt) {
        return registration(
                activationId,
                nonce,
                generation,
                issuedAt,
                expiresAt,
                new SyntheticCaseScope(
                        "c".repeat(64), "CASE_P9_SYNTHETIC_", 2, "fixture-p9",
                        "d".repeat(64), "d".repeat(64)));
    }

    private static ActivationRegistration explicitRegistration(
            String activationId,
            String nonce,
            long generation,
            String caseId,
            Instant issuedAt,
            Instant expiresAt) {
        return registration(
                activationId,
                nonce,
                generation,
                issuedAt,
                expiresAt,
                new ExplicitCaseScope("e".repeat(64), List.of(caseId)));
    }

    private static ActivationRegistration registration(
            String activationId,
            String nonce,
            long generation,
            Instant issuedAt,
            Instant expiresAt,
            TargetE2EActivationLedger.CaseScope scope) {
        return new ActivationRegistration(
                activationId,
                "a".repeat(64),
                "environment-p9",
                generation,
                "b".repeat(40),
                nonce,
                "tenant-p9",
                issuedAt,
                expiresAt,
                scope,
                List.of("INTAKE", "EVIDENCE", "HEARING", "REVIEW"),
                new BuildBindings("case-build", "control-build", "agent-build"),
                new GraphBinding(
                        "all-rooms/target-e2e.v1", "target-graph-v1",
                        "target-checkpoint-v1", "f".repeat(64), "graph-code-build"),
                new ImageDigests(
                        "sha256:" + "1".repeat(64),
                        "sha256:" + "2".repeat(64),
                        "sha256:" + "3".repeat(64),
                        "sha256:" + "4".repeat(64),
                        "sha256:" + "5".repeat(64)),
                "temporal-p9",
                new DatabaseBinding("domain-cluster", "domain-db", "java-runtime", "6".repeat(64)),
                new DatabaseBinding("graph-cluster", "graph-db", "python-runtime", "7".repeat(64)),
                "8".repeat(64));
    }

    private static void assertCode(ThrowingRunnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(TargetE2EPersistenceException.class)
                .extracting(failure -> ((TargetE2EPersistenceException) failure).code())
                .isEqualTo(code);
    }

    private static void assertSqlFails(String sql, String message) {
        assertThatThrownBy(() -> {
                    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                        statement.executeUpdate(sql);
                    }
                })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(message);
    }

    private static void assertSqlFails(Connection connection, String sql, String message) {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(message);
    }

    private static long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
