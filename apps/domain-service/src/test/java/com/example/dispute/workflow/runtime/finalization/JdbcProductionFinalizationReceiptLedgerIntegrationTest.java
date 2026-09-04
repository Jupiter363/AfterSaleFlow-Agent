package com.example.dispute.workflow.runtime.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.room.infrastructure.persistence.JdbcIntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptLedger.AppendCommand;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.ActivationLifecycle;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.ActivationRegistration;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.BuildBindings;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.DatabaseBinding;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.ExplicitCaseScope;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.GraphBinding;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.ImageDigests;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class JdbcProductionFinalizationReceiptLedgerIntegrationTest {

    private static final String ACTIVATION_ID = "p9act.v1." + "d".repeat(32);
    private static final String ACTIVATION_HASH = "a".repeat(64);
    private static final String DB_BINDING_HASH = "6".repeat(64);
    private static final String TENANT = "tenant-p9-finalization";
    private static final String CASE = "CASE_P9_FINALIZATION_001";
    private static final String UNBOUND_CASE = "CASE_P9_FINALIZATION_002";
    private static final String GRAPH_KEY = ProductionExecutionLaneVerifier.GRAPH_KEY;
    private static final String GRAPH_VERSION = ProductionExecutionLaneVerifier.GRAPH_VERSION;
    private static final String CHECKPOINT =
            ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION;

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(
                    "public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB", "production_runtime_finalization")
            .withEnv("POSTGRES_USER", "target_test")
            .withEnv("POSTGRES_PASSWORD", "target_test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static DriverManagerDataSource dataSource;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES.getHost() + ':' + POSTGRES.getMappedPort(5432)
                        + "/production_runtime_finalization",
                "target_test",
                "target_test");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        Instant now = Instant.now();
        var authority = new ProductionActivationLedger(dataSource, Clock.systemUTC());
        ActivationRegistration activation = activation(now);
        authority.registerOrAttach(activation);
        authority.transition(
                ACTIVATION_ID, ActivationLifecycle.REGISTERED, ActivationLifecycle.ACTIVE);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            seedCase(statement, CASE, "ROOM_P9_FINALIZATION_001", "EPOCH_P9_FINALIZATION_001");
            seedCase(
                    statement,
                    UNBOUND_CASE,
                    "ROOM_P9_FINALIZATION_002",
                    "EPOCH_P9_FINALIZATION_002");
            seedPrivateSession(statement, CASE, "001");
            seedPrivateSession(statement, UNBOUND_CASE, "002");
            statement.executeUpdate("""
                    insert into production_runtime_room_epoch_binding (
                        epoch_id, activation_id, activation_manifest_hash, execution_lane,
                        isolated_domain_db_binding_hash, tenant_surrogate, case_id,
                        room_type, room_epoch, room_fencing_token
                    ) values (
                        'EPOCH_P9_FINALIZATION_001', '%s', '%s', 'PRODUCTION',
                        '%s', '%s', '%s', 'INTAKE', 0, 1
                    )
                    """.formatted(ACTIVATION_ID, ACTIVATION_HASH, DB_BINDING_HASH, TENANT, CASE));
            seedManifest(
                    statement,
                    CASE,
                    "OUTPUT_P9_FINALIZATION_001",
                    "MANIFEST_P9_FINALIZATION_001",
                    "RUN_P9_FINALIZATION_001",
                    "ATTEMPT_P9_FINALIZATION_001",
                    "1".repeat(64),
                    "2".repeat(64));
            seedManifest(
                    statement,
                    UNBOUND_CASE,
                    "OUTPUT_P9_FINALIZATION_002",
                    "MANIFEST_P9_FINALIZATION_002",
                    "RUN_P9_FINALIZATION_002",
                    "ATTEMPT_P9_FINALIZATION_002",
                    "3".repeat(64),
                    "4".repeat(64));
        }
    }

    @Test
    void appendReplayConflictAndEpochBindingAreTransactionallyEnforced() throws SQLException {
        var threadStore = new JdbcIntakeGraphBindingStore(
                new NamedParameterJdbcTemplate(dataSource));
        assertThat(threadStore.register(targetThread(CASE, "001")).created()).isTrue();
        assertThatThrownBy(() -> threadStore.register(targetThread(UNBOUND_CASE, "002")))
                .rootCause()
                .hasMessageContaining(
                        "production runtime Intake thread requires the current activation-bound room epoch");

        var ledger = new JdbcProductionFinalizationReceiptLedger(dataSource);
        ProductionFinalizationReceipt original = receipt(
                CASE,
                "MANIFEST_P9_FINALIZATION_001",
                "RUN_P9_FINALIZATION_001",
                "ATTEMPT_P9_FINALIZATION_001",
                "1".repeat(64),
                "2".repeat(64),
                "5".repeat(64));
        AppendCommand append = new AppendCommand(ACTIVATION_HASH, original);

        var first = transactions.execute(ignored -> ledger.append(append));
        var replay = transactions.execute(ignored -> ledger.append(append));

        assertThat(first).isNotNull();
        assertThat(replay).isNotNull();
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(replay.canonicalBytes()).containsExactly(first.canonicalBytes());
        assertThat(count("select count(*) from production_runtime_finalization_receipt"))
                .isEqualTo(1);

        ProductionFinalizationReceipt conflicting = receipt(
                CASE,
                "MANIFEST_P9_FINALIZATION_001",
                "RUN_P9_FINALIZATION_001",
                "ATTEMPT_P9_FINALIZATION_001",
                "1".repeat(64),
                "2".repeat(64),
                "f".repeat(64));
        assertThatThrownBy(() -> transactions.execute(
                        ignored -> ledger.append(new AppendCommand(ACTIVATION_HASH, conflicting))))
                .isInstanceOf(ProductionFinalizationRejectedException.class)
                .hasMessageContaining("different receipt evidence");
        assertThatThrownBy(() -> ledger.find(ACTIVATION_ID, "RUN_P9_FINALIZATION_001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active writable Java Finalizer transaction");

        ProductionFinalizationReceipt missingEpoch = receipt(
                UNBOUND_CASE,
                "MANIFEST_P9_FINALIZATION_002",
                "RUN_P9_FINALIZATION_002",
                "ATTEMPT_P9_FINALIZATION_002",
                "3".repeat(64),
                "4".repeat(64),
                "7".repeat(64));
        assertThatThrownBy(() -> transactions.execute(ignored -> ledger.append(
                        new AppendCommand(ACTIVATION_HASH, missingEpoch))))
                .isInstanceOf(ProductionFinalizationReceiptPersistenceException.class)
                .rootCause()
                .hasMessageContaining(
                        "production runtime finalization receipt is outside its activation-bound room epoch");

        assertThatThrownBy(() -> execute(
                        "update production_runtime_finalization_receipt set proposal_hash = repeat('8', 64)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
    }

    private static ActivationRegistration activation(Instant now) {
        return new ActivationRegistration(
                ACTIVATION_ID,
                ACTIVATION_HASH,
                "environment-p9-finalization",
                1,
                "b".repeat(40),
                "nonce-" + "d".repeat(32),
                TENANT,
                now.minusSeconds(30),
                now.plusSeconds(300),
                new ExplicitCaseScope("c".repeat(64), List.of(CASE, UNBOUND_CASE)),
                List.of("INTAKE", "EVIDENCE", "HEARING", "REVIEW"),
                new BuildBindings("case-build", "control-build", "agent-build"),
                new GraphBinding(
                        GRAPH_KEY,
                        GRAPH_VERSION,
                        CHECKPOINT,
                        "d".repeat(64),
                        "graph-code-build"),
                new ImageDigests(
                        "sha256:" + "1".repeat(64),
                        "sha256:" + "2".repeat(64),
                        "sha256:" + "3".repeat(64),
                        "sha256:" + "4".repeat(64),
                        "sha256:" + "5".repeat(64)),
                "temporal-p9-finalization",
                new DatabaseBinding(
                        "domain-cluster", "domain-db", "java-runtime", DB_BINDING_HASH),
                new DatabaseBinding(
                        "graph-cluster", "graph-db", "python-runtime", "e".repeat(64)),
                "9".repeat(64));
    }

    private static void seedCase(
            Statement statement, String caseId, String roomId, String epochId) throws SQLException {
        statement.executeUpdate("""
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (
                    '%s', 'user-p9', 'merchant-p9', 'create-%s', 'DISPUTE',
                    'INTAKE_IN_PROGRESS', 'USER', 'user-p9', 'MERCHANT', 'merchant-p9',
                    'LOW', 'Target finalization', 'Target finalization persistence',
                    'INTAKE', 'test', 'test'
                )
                """.formatted(caseId, caseId));
        statement.executeUpdate("""
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values ('%s', '%s', 'INTAKE', 'OPEN', now(), 'test', 'test')
                """.formatted(roomId, caseId));
        statement.executeUpdate("""
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
                    '%s', '%s', '%s', '%s', 'INTAKE', 0, 'TEMPORAL', 'ACTIVE', 'READY',
                    0, 0, 1, 'case-wf-%s', 'case-run-%s', 'room-wf-%s', 'room-run-%s',
                    'case-build', '%s', '%s', '%s', 'agent-stream.v2',
                    'room-epoch-selection.v2', 'case-process-contract.v1',
                    'CaseProcessWorkflow', 'IntakeRoomWorkflow', 'control-build',
                    now(), now(), now(), now()
                )
                """.formatted(
                epochId,
                TENANT,
                caseId,
                roomId,
                caseId,
                caseId,
                caseId,
                caseId,
                GRAPH_KEY,
                GRAPH_VERSION,
                CHECKPOINT));
    }

    private static void seedManifest(
            Statement statement,
            String caseId,
            String outputId,
            String manifestId,
            String runId,
            String attemptId,
            String outputHash,
            String manifestHash) throws SQLException {
        statement.executeUpdate("""
                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, room_type, snapshot_type, source_type,
                    source_id, schema_version, object_uri, object_version, content_sha256,
                    size_bytes, content_type, visibility, created_by
                ) values (
                    '%s', '%s', '%s', 'INTAKE', 'AGENT_OUTPUT', 'AGENT_RUN', '%s',
                    'intake-turn-proposal.v2', 'urn:production-runtime:output:%s', 'v1', '%s',
                    128, 'application/json', 'PRIVATE', 'test'
                )
                """.formatted(outputId, TENANT, caseId, runId, outputId, outputHash));
        statement.executeUpdate("""
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type, room_epoch,
                    process_revision, fencing_token, logical_agent_run_id, attempt_id,
                    workflow_id, workflow_run_id, workflow_type, workflow_build_id,
                    graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                    prompt_version, model_profile_id, provider, model_version,
                    policy_version, guardrail_version, manifest_uri, manifest_sha256,
                    input_snapshot_refs_json, output_snapshot_id, output_sha256,
                    traceparent, terminal_status, finalized_at
                ) values (
                    '%s', 'agent-execution-manifest.v1', '%s', '%s', 'INTAKE', 0,
                    0, 1, '%s', '%s', 'agent-run/%s', 'temporal-run-%s',
                    'AgentRunV2Workflow', 'agent-build', '%s', '%s', '%s',
                    'CHECKPOINT_P9_FINALIZATION', 'intake-prompt.v2',
                    'intake-model.production-runtime.v1', 'test-provider', 'test-model',
                    'intake-policy.v2', 'intake-guardrail.v2',
                    'urn:production-runtime:manifest:%s', '%s', '[]'::jsonb, '%s', '%s',
                    '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
                    'COMPLETED', now()
                )
                """.formatted(
                manifestId,
                TENANT,
                caseId,
                runId,
                attemptId,
                runId,
                runId,
                GRAPH_KEY,
                GRAPH_VERSION,
                CHECKPOINT,
                manifestId,
                manifestHash,
                outputId,
                outputHash));
    }

    private static void seedPrivateSession(
            Statement statement, String caseId, String suffix) throws SQLException {
        statement.executeUpdate("""
                insert into case_participant (
                    id, case_id, actor_id, participant_role, participant_status,
                    joined_at, created_by, updated_by
                ) values (
                    'PARTICIPANT_P9_FINALIZATION_%s', '%s', 'user-p9', 'USER',
                    'ACTIVE', now(), 'test', 'test'
                )
                """.formatted(suffix, caseId));
        statement.executeUpdate("""
                insert into case_access_session (
                    id, tenant_id, case_id, actor_id, actor_role, permission_level,
                    permission_scopes_json, status, created_by
                ) values (
                    'ACCESS_P9_FINALIZATION_%s', '%s', '%s', 'user-p9', 'USER',
                    'PARTY_USER',
                    '["CASE_READ","INTAKE_PRIVATE_READ","INTAKE_PARTICIPATE","AGENT_SESSION_WRITE"]'::jsonb,
                    'ACTIVE', 'test'
                )
                """.formatted(suffix, TENANT, caseId));
        statement.executeUpdate("""
                insert into agent_conversation_session (
                    id, tenant_id, case_id, room_type, actor_id, actor_role, agent_key,
                    access_session_id, prompt_profile_id, memory_policy_id,
                    conversation_scope, status, created_by
                ) values (
                    'SESSION_P9_FINALIZATION_%s', '%s', '%s', 'INTAKE', 'user-p9',
                    'USER', 'DISPUTE_INTAKE_OFFICER', 'ACCESS_P9_FINALIZATION_%s',
                    'intake-prompt.v2', 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1',
                    'production-runtime-finalization:%s', 'ACTIVE', 'test'
                )
                """.formatted(suffix, TENANT, caseId, suffix, caseId));
    }

    private static IntakeGraphThreadBinding targetThread(String caseId, String suffix) {
        var actor = new IntakePrivateThreadRegistration.ActorScope(
                "user-p9",
                ActorRole.USER,
                Audience.USER,
                List.of("case:" + caseId + ":command:INTAKE_MESSAGE"));
        String actorScopeHash = IntakeContractHashes.actorScopeHash(actor);
        var unsigned = new IntakePrivateThreadRegistration(
                "graph-private-thread-registration.v1",
                "REG_P9_FINALIZATION_" + suffix,
                TENANT,
                caseId,
                "INTAKE",
                0,
                "grt.v1." + suffix.substring(0, 1).repeat(32),
                actor,
                actorScopeHash,
                "SESSION_P9_FINALIZATION_" + suffix,
                GRAPH_KEY,
                GRAPH_VERSION,
                CHECKPOINT,
                "intake-graph-state.v2",
                "intake-prompt.v2",
                "intake-model.production-runtime.v1",
                "production-runtime-room-proposal-source.v1",
                "intake-policy.v2",
                "intake-guardrail.v2",
                "no-tools.v1",
                WriterMode.TEMPORAL,
                Instant.now().minusSeconds(5),
                "0".repeat(64));
        var registration = new IntakePrivateThreadRegistration(
                unsigned.schemaVersion(),
                unsigned.registrationId(),
                unsigned.tenantSurrogate(),
                unsigned.caseId(),
                unsigned.roomType(),
                unsigned.roomEpoch(),
                unsigned.threadId(),
                unsigned.actorScope(),
                unsigned.actorScopeHash(),
                unsigned.agentSessionId(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointSchemaVersion(),
                unsigned.stateSchemaVersion(),
                unsigned.promptVersion(),
                unsigned.modelProfileId(),
                unsigned.outputSchemaVersion(),
                unsigned.policyVersion(),
                unsigned.guardrailVersion(),
                unsigned.toolPolicyVersion(),
                unsigned.writerMode(),
                unsigned.issuedAt(),
                IntakeContractHashes.registrationHash(unsigned));
        return new IntakeGraphThreadBinding(registration, 1);
    }

    private static ProductionFinalizationReceipt receipt(
            String caseId,
            String manifestId,
            String logicalRunId,
            String attemptId,
            String resultHash,
            String manifestHash,
            String commandHash) {
        return ProductionFinalizationReceipt.committed(new CommitFacts(
                ACTIVATION_ID,
                TENANT,
                caseId,
                RoomType.INTAKE,
                0,
                1,
                0,
                1,
                logicalRunId,
                attemptId,
                commandHash,
                "8".repeat(64),
                GRAPH_KEY,
                GRAPH_VERSION,
                CHECKPOINT,
                "CHECKPOINT_P9_FINALIZATION",
                resultHash,
                "9".repeat(64),
                "0".repeat(64),
                manifestId,
                manifestHash,
                DB_BINDING_HASH,
                Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS)));
    }

    private static long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
