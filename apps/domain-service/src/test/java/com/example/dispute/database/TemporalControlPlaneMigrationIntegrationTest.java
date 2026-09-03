package com.example.dispute.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ActivationLifecycle;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ActivationRegistration;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.BuildBindings;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.DatabaseBinding;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ExplicitCaseScope;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.GraphBinding;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.ImageDigests;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TemporalControlPlaneMigrationIntegrationTest {

    private static final String DATABASE_NAME = "temporal_control_plane";
    private static final String USERNAME = "dispute_test";
    private static final String PASSWORD = "local_test_password";

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", DATABASE_NAME)
                    .withEnv("POSTGRES_USER", USERNAME)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    @Test
    void upgradesLegacyCasesWithoutStartingWorkflowsAndEnforcesControlPlaneContracts()
            throws SQLException {
        String jdbcUrl = jdbcUrl();
        Flyway.configure()
                .dataSource(jdbcUrl, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .target("38")
                .load()
                .migrate();

        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            insertLegacyCase(connection);
        }

        var v040Result =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .target("40")
                        .load()
                        .migrate();
        assertThat(v040Result.migrationsExecuted).isEqualTo(2);

        var result =
                Flyway.configure()
                        .dataSource(jdbcUrl, USERNAME, PASSWORD)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(15);

        try (Connection connection =
                DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD)) {
            assertThat(loadTables(connection))
                    .contains(
                            "case_command",
                            "case_command_outbox",
                            "case_process_projection",
                            "case_room_epoch",
                            "room_epoch_bootstrap_outbox",
                            "domain_operation",
                            "process_reconciliation_issue",
                            "immutable_payload_snapshot",
                            "agent_execution_manifest");

            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select writer_mode || ':' || room_epoch || ':' ||
                                           process_revision || ':' || fencing_token
                                    from case_process_projection
                                    where case_id = 'CASE_LEGACY_CONTROL'
                                    """))
                    .isEqualTo("LEGACY:0:0:0");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select writer_mode || ':' || room_epoch || ':' ||
                                           process_revision || ':' || fencing_token
                                    from case_room_epoch
                                    where case_id = 'CASE_LEGACY_CONTROL'
                                      and room_type = 'EVIDENCE'
                                    """))
                    .isEqualTo("LEGACY:0:0:0");
            assertThat(
                            count(
                                    connection,
                                    """
                                    select count(*)
                                    from case_process_projection
                                    where temporal_workflow_id is not null
                                       or temporal_run_id is not null
                                    """))
                    .isZero();
            assertThat(count(connection, "select count(*) from case_command_outbox"))
                    .isZero();
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select selection_schema_version || ':' ||
                                           process_contract_version || ':' ||
                                           workflow_type || ':' || temporal_build_id || ':' ||
                                           graph_key || ':' || graph_version || ':' ||
                                           checkpoint_schema_version || ':' || stream_protocol
                                      from case_room_epoch
                                     where case_id = 'CASE_LEGACY_CONTROL'
                                       and room_type = 'EVIDENCE'
                                    """))
                    .isEqualTo(
                            "room-epoch-selection.v1:case-process-contract.v1:"
                                    + "LegacyJavaRoomState:legacy-java.v1:evidence.legacy:"
                                    + "legacy.v1:legacy-checkpoint.v1:agent_stream.v1");
            assertThat(
                            scalar(
                                    connection,
                                    """
                                    select character_maximum_length::text
                                      from information_schema.columns
                                     where table_schema = 'public'
                                       and table_name = 'case_room_epoch'
                                       and column_name = 'lifecycle_status'
                                    """))
                    .isEqualTo("24");

            assertCommandConstraints(connection);
            assertSnapshotAndManifestConstraints(connection);
            assertRoomEpochLifecycleConstraints(connection);
        }
    }

    @Test
    void rejectsAnExistingV040ActiveEpochWithoutARealRoomBinding() throws SQLException {
        String schema = "missing_room_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target("38")
                .load()
                .migrate();

        try (Connection connection = schemaConnection(schema)) {
            insertLegacyCase(connection);
            execute(connection, "delete from case_room where id = 'ROOM_LEGACY_CONTROL'");
        }

        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target("40.2")
                .load()
                .migrate();

        assertThatThrownBy(
                        () ->
                                Flyway.configure()
                                        .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                                        .locations("classpath:db/migration")
                                        .schemas(schema)
                                        .defaultSchema(schema)
                                        .createSchemas(true)
                                        .load()
                                        .migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining(
                        "an ACTIVE epoch has no valid room binding");
    }

    @Test
    void drainOnlyActivationBindsOnlyItsAtomicDirectRoomSuccessor() throws Exception {
        String schema = "drain_successor_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target("65")
                .load()
                .migrate();

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        jdbcUrl() + "?currentSchema=" + schema, USERNAME, PASSWORD);
        TargetE2EActivationLedger ledger =
                new TargetE2EActivationLedger(dataSource, Clock.systemUTC());
        ledger.registerOrAttach(drainSuccessorActivation());
        ledger.transition(
                "p9act.v1.66666666666666666666666666666666",
                ActivationLifecycle.REGISTERED,
                ActivationLifecycle.ACTIVE);
        try (Connection connection = schemaConnection(schema)) {
            seedDrainSuccessorSource(connection);
            execute(
                    connection,
                    """
                    update target_e2e_activation
                       set lifecycle_status = 'DRAIN_ONLY',
                           lifecycle_changed_at = expires_at,
                           drain_only_at = expires_at
                     where activation_id = 'p9act.v1.66666666666666666666666666666666'
                    """);
        }

        assertSuccessorBindingRejected(
                schema,
                SuccessorProbe.legitimate("V047_OLD_RED"),
                "target E2E room binding requires a live ACTIVE activation");

        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .load()
                .migrate();

        for (SuccessorProbe invalid :
                List.of(
                        SuccessorProbe.legitimate("MISSING_PREDECESSOR")
                                .withProjectionEpoch(99),
                        SuccessorProbe.legitimate("WRONG_SOURCE_BINDING")
                                .withManifestHash("f".repeat(64)),
                        SuccessorProbe.legitimate("WRONG_TENANT_CASE")
                                .withBindingCase("tenant-other", "CASE_OTHER"),
                        SuccessorProbe.legitimate("WRONG_BUILD")
                                .withCaseBuild("p9-wrong-build"),
                        SuccessorProbe.legitimate("WRONG_GRAPH")
                                .withGraphVersion("target-e2e-graph.wrong"),
                        SuccessorProbe.legitimate("WRONG_ROOM")
                                .withRoomWorkflowType("EvidenceRoomWorkflow"),
                        SuccessorProbe.legitimate("NON_SUCCESSOR").withFencingToken(8),
                        SuccessorProbe.legitimate("DRAINED")
                                .withLifecycle("DRAINED"),
                        SuccessorProbe.legitimate("REVOKED")
                                .withLifecycle("REVOKED_TERMINAL"))) {
            assertSuccessorBindingRejected(
                    schema, invalid, null);
        }

        try (Connection connection = schemaConnection(schema)) {
            connection.setAutoCommit(false);
            prepareDrainSuccessor(connection, SuccessorProbe.legitimate("GREEN"));
            insertDrainSuccessorBinding(connection, SuccessorProbe.legitimate("GREEN"));
            connection.commit();
        }
        try (Connection connection = schemaConnection(schema)) {
            assertThat(
                            count(
                                    connection,
                                    """
                                    select count(*)
                                      from target_e2e_room_epoch_binding successor
                                      join target_e2e_room_epoch_binding source
                                        on source.activation_id = successor.activation_id
                                       and source.activation_manifest_hash =
                                            successor.activation_manifest_hash
                                       and source.execution_lane = successor.execution_lane
                                       and source.isolated_domain_db_binding_hash =
                                            successor.isolated_domain_db_binding_hash
                                     where source.epoch_id = 'EPOCH_DRAIN_SOURCE'
                                       and successor.epoch_id = 'EPOCH_DRAIN_SUCCESSOR_GREEN'
                                    """))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    connection,
                                    "select count(*) from case_room_epoch where case_id = 'CASE_DRAIN_SUCCESSOR'"))
                    .isEqualTo(2);
        }
    }

    private static ActivationRegistration drainSuccessorActivation() {
        Instant now = Instant.now();
        return new ActivationRegistration(
                "p9act.v1.66666666666666666666666666666666",
                "a".repeat(64),
                "environment-v066-drain-successor",
                1,
                "6".repeat(40),
                "nonce-v066-" + "6".repeat(32),
                "tenant-drain-successor",
                now.minusSeconds(5),
                now.plusSeconds(600),
                new ExplicitCaseScope("d".repeat(64), List.of("CASE_DRAIN_SUCCESSOR")),
                List.of("EVIDENCE", "HEARING"),
                new BuildBindings("p9-case-build", "p9-control-build", "p9-agent-build"),
                new GraphBinding(
                        "all-rooms.target-e2e.v1",
                        "target-e2e-graph.2026-07-27.1",
                        "target-e2e-checkpoint.v1",
                        "e".repeat(64),
                        "p9-graph-code-build"),
                new ImageDigests(
                        "sha256:" + "1".repeat(64),
                        "sha256:" + "2".repeat(64),
                        "sha256:" + "3".repeat(64),
                        "sha256:" + "4".repeat(64),
                        "sha256:" + "5".repeat(64)),
                "target-e2e-test",
                new DatabaseBinding(
                        "domain-cluster-v066",
                        "domain-database-v066",
                        "domain-principal-v066",
                        "b".repeat(64)),
                new DatabaseBinding(
                        "graph-cluster-v066",
                        "graph-database-v066",
                        "graph-principal-v066",
                        "c".repeat(64)),
                "f".repeat(64));
    }

    private static void seedDrainSuccessorSource(Connection connection) throws SQLException {
        execute(
                connection,
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level,
                    title, description, current_room, created_by, updated_by
                ) values (
                    'CASE_DRAIN_SUCCESSOR', 'user-v066', 'merchant-v066',
                    'drain-successor-v066', 'DISPUTE', 'EVIDENCE_OPEN',
                    'USER', 'user-v066', 'MERCHANT', 'merchant-v066', 'HIGH',
                    'Drain successor migration', 'Exact inherited activation authority.',
                    'EVIDENCE', 'migration-test', 'migration-test'
                )
                """);
        execute(
                connection,
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values
                    ('ROOM_DRAIN_EVIDENCE', 'CASE_DRAIN_SUCCESSOR', 'EVIDENCE',
                     'OPEN', now(), 'migration-test', 'migration-test'),
                    ('ROOM_DRAIN_HEARING', 'CASE_DRAIN_SUCCESSOR', 'HEARING',
                     'OPEN', now(), 'migration-test', 'migration-test')
                """);
        execute(
                connection,
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, provisioning_status,
                    process_revision, room_revision, fencing_token,
                    temporal_workflow_id, temporal_run_id,
                    room_temporal_workflow_id, room_temporal_run_id,
                    temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    room_workflow_type, room_workflow_build_id,
                    activated_at, provisioned_at, created_at, updated_at
                ) values (
                    'EPOCH_DRAIN_SOURCE', 'tenant-drain-successor',
                    'CASE_DRAIN_SUCCESSOR', 'ROOM_DRAIN_EVIDENCE', 'EVIDENCE', 1,
                    'TEMPORAL', 'ACTIVE', 'READY', 11, 5, 6,
                    'case:tenant-drain-successor:CASE_DRAIN_SUCCESSOR',
                    'case-run-v066',
                    'room-workflow:CASE_DRAIN_SUCCESSOR:EVIDENCE:1',
                    'room-run-v066',
                    'p9-case-build', 'all-rooms.target-e2e.v1',
                    'target-e2e-graph.2026-07-27.1',
                    'target-e2e-checkpoint.v1', 'agent-stream.v2',
                    'room-epoch-selection.v2', 'case-process-contract.v1',
                    'CaseProcessWorkflow', 'EvidenceRoomWorkflow', 'p9-control-build',
                    now(), now(), now(), now()
                )
                """);
        execute(
                connection,
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, writer_activation_status, process_revision, room_epoch,
                    fencing_token, last_command_sequence, last_case_event_sequence,
                    temporal_workflow_id, temporal_run_id, temporal_build_id,
                    projected_at, updated_at
                ) values (
                    'CASE_DRAIN_SUCCESSOR', 'tenant-drain-successor',
                    'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN', 'TEMPORAL', 'READY',
                    11, 1, 6, 11, 20,
                    'case:tenant-drain-successor:CASE_DRAIN_SUCCESSOR',
                    'case-run-v066', 'p9-case-build', now(), now()
                )
                """);
        execute(
                connection,
                """
                insert into target_e2e_room_epoch_binding (
                    epoch_id, activation_id, activation_manifest_hash, execution_lane,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id,
                    room_type, room_epoch, room_fencing_token
                ) values (
                    'EPOCH_DRAIN_SOURCE',
                    'p9act.v1.66666666666666666666666666666666',
                    repeat('a', 64), 'TARGET_E2E_CANDIDATE', repeat('b', 64),
                    'tenant-drain-successor', 'CASE_DRAIN_SUCCESSOR',
                    'EVIDENCE', 1, 6
                )
                """);
    }

    private void assertSuccessorBindingRejected(
            String schema, SuccessorProbe probe, String expectedMessage) throws SQLException {
        try (Connection connection = schemaConnection(schema)) {
            connection.setAutoCommit(false);
            try {
                prepareDrainSuccessor(connection, probe);
                insertDrainSuccessorBinding(connection, probe);
                throw new AssertionError("successor probe unexpectedly persisted: " + probe.label());
            } catch (SQLException failure) {
                assertThat(failure.getSQLState()).isEqualTo("23514");
                if (expectedMessage != null) {
                    assertThat(failure.getMessage()).contains(expectedMessage);
                }
            } finally {
                connection.rollback();
            }
        }
        try (Connection connection = schemaConnection(schema)) {
            assertThat(
                            count(
                                    connection,
                                    "select count(*) from case_room_epoch where id = '"
                                            + probe.epochId()
                                            + "'"))
                    .isZero();
            assertThat(
                            scalar(
                                    connection,
                                    "select lifecycle_status from case_room_epoch where id = 'EPOCH_DRAIN_SOURCE'"))
                    .isEqualTo("ACTIVE");
        }
    }

    private static void prepareDrainSuccessor(Connection connection, SuccessorProbe probe)
            throws SQLException {
        if (probe.projectionEpoch() != 1) {
            execute(
                    connection,
                    "update case_process_projection set room_epoch = "
                            + probe.projectionEpoch()
                            + " where case_id = 'CASE_DRAIN_SUCCESSOR'");
        }
        execute(
                connection,
                """
                update case_room_epoch
                   set lifecycle_status = 'TERMINAL', process_revision = 12,
                       room_revision = 6, terminal_at = clock_timestamp(),
                       updated_at = clock_timestamp(), version = version + 1
                 where id = 'EPOCH_DRAIN_SOURCE'
                """);
        execute(
                connection,
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, provisioning_status,
                    process_revision, room_revision, fencing_token,
                    temporal_workflow_id, temporal_run_id,
                    room_temporal_workflow_id, room_temporal_run_id,
                    temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    room_workflow_type, room_workflow_build_id,
                    activated_at, created_at, updated_at
                ) select
                    '%s', tenant_surrogate, case_id, 'ROOM_DRAIN_HEARING', 'HEARING', 0,
                    'TEMPORAL', 'PREPARING', 'PENDING', 12, 0, %d,
                    temporal_workflow_id, null,
                    'room-workflow:CASE_DRAIN_SUCCESSOR:HEARING:0', null,
                    '%s', graph_key, '%s', checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    '%s', room_workflow_build_id,
                    terminal_at, terminal_at, terminal_at
                  from case_room_epoch where id = 'EPOCH_DRAIN_SOURCE'
                """
                        .formatted(
                                probe.epochId(),
                                probe.fencingToken(),
                                probe.caseBuild(),
                                probe.graphVersion(),
                                probe.roomWorkflowType()));
        if ("DRAINED".equals(probe.lifecycle())
                || "REVOKED_TERMINAL".equals(probe.lifecycle())) {
            execute(
                    connection,
                    """
                    update target_e2e_activation
                       set lifecycle_status = 'DRAINED',
                           lifecycle_changed_at = expires_at + interval '1 second',
                           drained_at = expires_at + interval '1 second',
                           all_replicas_detached = true, evidence_sealed = true,
                           drain_completion_proof_hash = repeat('1', 64),
                           drain_evidence_ledger_head_hash = repeat('2', 64),
                           drain_forensic_manifest_hash = repeat('3', 64),
                           drain_attestation_key_sha256 = repeat('4', 64)
                     where activation_id = 'p9act.v1.66666666666666666666666666666666'
                    """);
        }
        if ("REVOKED_TERMINAL".equals(probe.lifecycle())) {
            execute(
                    connection,
                    """
                    update target_e2e_activation
                       set lifecycle_status = 'REVOKED_TERMINAL',
                           lifecycle_changed_at = expires_at + interval '2 seconds',
                           revoked_at = expires_at + interval '2 seconds'
                     where activation_id = 'p9act.v1.66666666666666666666666666666666'
                    """);
        }
    }

    private static void insertDrainSuccessorBinding(
            Connection connection, SuccessorProbe probe) throws SQLException {
        execute(
                connection,
                """
                insert into target_e2e_room_epoch_binding (
                    epoch_id, activation_id, activation_manifest_hash, execution_lane,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id,
                    room_type, room_epoch, room_fencing_token
                ) values (
                    '%s', 'p9act.v1.66666666666666666666666666666666',
                    '%s', 'TARGET_E2E_CANDIDATE', repeat('b', 64),
                    '%s', '%s', 'HEARING', 0, %d
                )
                """
                        .formatted(
                                probe.epochId(),
                                probe.manifestHash(),
                                probe.bindingTenant(),
                                probe.bindingCase(),
                                probe.fencingToken()));
    }

    private record SuccessorProbe(
            String label,
            long projectionEpoch,
            long fencingToken,
            String manifestHash,
            String bindingTenant,
            String bindingCase,
            String caseBuild,
            String graphVersion,
            String roomWorkflowType,
            String lifecycle) {

        static SuccessorProbe legitimate(String label) {
            return new SuccessorProbe(
                    label,
                    1,
                    7,
                    "a".repeat(64),
                    "tenant-drain-successor",
                    "CASE_DRAIN_SUCCESSOR",
                    "p9-case-build",
                    "target-e2e-graph.2026-07-27.1",
                    "HearingRoomWorkflow",
                    "DRAIN_ONLY");
        }

        String epochId() {
            return "EPOCH_DRAIN_SUCCESSOR_" + label;
        }

        SuccessorProbe withProjectionEpoch(long value) {
            return new SuccessorProbe(
                    label, value, fencingToken, manifestHash, bindingTenant, bindingCase,
                    caseBuild, graphVersion, roomWorkflowType, lifecycle);
        }

        SuccessorProbe withFencingToken(long value) {
            return new SuccessorProbe(
                    label, projectionEpoch, value, manifestHash, bindingTenant, bindingCase,
                    caseBuild, graphVersion, roomWorkflowType, lifecycle);
        }

        SuccessorProbe withManifestHash(String value) {
            return new SuccessorProbe(
                    label, projectionEpoch, fencingToken, value, bindingTenant, bindingCase,
                    caseBuild, graphVersion, roomWorkflowType, lifecycle);
        }

        SuccessorProbe withBindingCase(String tenant, String caseId) {
            return new SuccessorProbe(
                    label, projectionEpoch, fencingToken, manifestHash, tenant, caseId,
                    caseBuild, graphVersion, roomWorkflowType, lifecycle);
        }

        SuccessorProbe withCaseBuild(String value) {
            return new SuccessorProbe(
                    label, projectionEpoch, fencingToken, manifestHash, bindingTenant, bindingCase,
                    value, graphVersion, roomWorkflowType, lifecycle);
        }

        SuccessorProbe withGraphVersion(String value) {
            return new SuccessorProbe(
                    label, projectionEpoch, fencingToken, manifestHash, bindingTenant, bindingCase,
                    caseBuild, value, roomWorkflowType, lifecycle);
        }

        SuccessorProbe withRoomWorkflowType(String value) {
            return new SuccessorProbe(
                    label, projectionEpoch, fencingToken, manifestHash, bindingTenant, bindingCase,
                    caseBuild, graphVersion, value, lifecycle);
        }

        SuccessorProbe withLifecycle(String value) {
            return new SuccessorProbe(
                    label, projectionEpoch, fencingToken, manifestHash, bindingTenant, bindingCase,
                    caseBuild, graphVersion, roomWorkflowType, value);
        }
    }

    private static void insertLegacyCase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key,
                        case_type, case_status, initiator_role, initiator_id,
                        respondent_role, respondent_id, risk_level,
                        title, description, current_room, created_by, updated_by
                    ) values (
                        'CASE_LEGACY_CONTROL', 'user-control', 'merchant-control',
                        'legacy-control-idempotency', 'DISPUTE', 'EVIDENCE_OPEN',
                        'USER', 'user-control', 'MERCHANT', 'merchant-control',
                        'HIGH', 'Legacy migration case',
                        'Must remain owned by the legacy writer after migration.',
                        'EVIDENCE', 'migration-test', 'migration-test'
                    )
                    """);
            statement.executeUpdate(
                    """
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at,
                        created_by, updated_by
                    ) values (
                        'ROOM_LEGACY_CONTROL', 'CASE_LEGACY_CONTROL', 'EVIDENCE',
                        'OPEN', now(), 'migration-test', 'migration-test'
                    )
                    """);
        }
    }

    private static void assertCommandConstraints(Connection connection) throws SQLException {
        execute(
                connection,
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
                    'CMD_ROW_1', 'command-1', 'tenant-control',
                    'CASE_LEGACY_CONTROL', 1, 'EVIDENCE_SUBMIT', 'EVIDENCE', 0,
                    'user-control', 'USER', '["case:write"]',
                    'evidence-command.v1', 'urn:payload:command-1',
                    repeat('a', 64), 12, 0, now(), now() + interval '5 minutes',
                    '00-0123456789abcdef0123456789abcdef-0123456789abcdef-01',
                    repeat('b', 64), 'PENDING_ORCHESTRATION'
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) select
                    'CMD_ROW_DUPLICATE_ID', command_id, tenant_surrogate, case_id,
                    2, command_type, room_type, room_epoch, actor_id, actor_role,
                    actor_scopes_json, payload_schema_version, payload_uri,
                    payload_sha256, payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                from case_command where id = 'CMD_ROW_1'
                """,
                "uq_case_command_tenant_command");

        assertSqlFails(
                connection,
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) select
                    'CMD_ROW_DUPLICATE_SEQUENCE', 'command-2', tenant_surrogate,
                    case_id, case_command_sequence, command_type, room_type,
                    room_epoch, actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision, occurred_at,
                    deadline_at, traceparent, request_hash, command_status
                from case_command where id = 'CMD_ROW_1'
                """,
                "uq_case_command_case_sequence");

        assertSqlFails(
                connection,
                "update case_process_projection set process_revision = -1 "
                        + "where case_id = 'CASE_LEGACY_CONTROL'",
                "revisions, sequences, update time and version cannot move backward");

        execute(
                connection,
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status
                ) select
                    'CMD_ROW_2', 'command-2', tenant_surrogate, case_id,
                    2, command_type, room_type, room_epoch, actor_id, actor_role,
                    actor_scopes_json, payload_schema_version,
                    'urn:payload:command-2', payload_sha256, payload_size_bytes,
                    expected_process_revision, occurred_at, deadline_at,
                    traceparent, request_hash, command_status
                from case_command where id = 'CMD_ROW_1'
                """);

        execute(
                connection,
                """
                insert into case_command_outbox (
                    id, case_command_id, tenant_surrogate, case_id,
                    workflow_id, workflow_type, task_queue, delivery_kind,
                    update_id, outbox_status, available_at
                ) values (
                    'OUTBOX_ROW_1', 'CMD_ROW_1', 'tenant-control',
                    'CASE_LEGACY_CONTROL', 'case:CASE_LEGACY_CONTROL',
                    'CaseProcessWorkflow', 'case-control', 'UPDATE_WITH_START',
                    'command-1', 'PENDING', now()
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into case_command_outbox (
                    id, case_command_id, tenant_surrogate, case_id,
                    workflow_id, workflow_type, task_queue, delivery_kind,
                    update_id, outbox_status, available_at
                ) values (
                    'OUTBOX_WRONG_COMMAND', 'CMD_ROW_2', 'tenant-control',
                    'CASE_LEGACY_CONTROL', 'case:CASE_LEGACY_CONTROL:wrong',
                    'CaseProcessWorkflow', 'case-control', 'UPDATE_WITH_START',
                    'different-command-id', 'PENDING', now()
                )
                """,
                "fk_case_command_outbox_command");
    }

    private static void assertSnapshotAndManifestConstraints(Connection connection)
            throws SQLException {
        execute(
                connection,
                """
                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, room_type, snapshot_type,
                    source_type, source_id, schema_version, object_uri,
                    object_version, content_sha256, size_bytes, visibility,
                    created_by
                ) values (
                    'SNAPSHOT_1', 'tenant-control', 'CASE_LEGACY_CONTROL',
                    'EVIDENCE', 'COMMAND_INPUT', 'CASE_COMMAND', 'CMD_ROW_1',
                    'evidence-command.v1', 'urn:payload:command-1', 'v1',
                    repeat('c', 64), 12, 'PARTIES', 'migration-test'
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, snapshot_type, source_type,
                    source_id, schema_version, object_uri, content_sha256,
                    size_bytes, visibility, created_by
                ) values (
                    'SNAPSHOT_BAD_HASH', 'tenant-control', 'CASE_LEGACY_CONTROL',
                    'COMMAND_INPUT', 'CASE_COMMAND', 'CMD_ROW_BAD',
                    'evidence-command.v1', 'urn:payload:bad', 'NOT-A-SHA256',
                    1, 'INTERNAL', 'migration-test'
                )
                """,
                "ck_immutable_payload_snapshot_hash");

        execute(
                connection,
                """
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, process_revision, fencing_token,
                    logical_agent_run_id, manifest_uri, manifest_sha256,
                    output_snapshot_id, output_sha256, terminal_status, finalized_at
                ) values (
                    'MANIFEST_1', 'agent-execution-manifest.v1',
                    'tenant-control', 'CASE_LEGACY_CONTROL', 'EVIDENCE', 0, 0, 0,
                    'logical-run-1', 'urn:manifest:logical-run-1', repeat('d', 64),
                    'SNAPSHOT_1', repeat('c', 64), 'COMPLETED', now()
                )
                """);

        assertSqlFails(
                connection,
                """
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, process_revision, fencing_token,
                    logical_agent_run_id, manifest_uri, manifest_sha256,
                    output_snapshot_id, output_sha256, terminal_status, finalized_at
                ) values (
                    'MANIFEST_DUPLICATE', 'agent-execution-manifest.v1',
                    'tenant-control', 'CASE_LEGACY_CONTROL', 'EVIDENCE', 0, 0, 0,
                    'logical-run-1', 'urn:manifest:logical-run-1-duplicate',
                    repeat('e', 64), 'SNAPSHOT_1', repeat('c', 64), 'COMPLETED', now()
                )
                """,
                "uq_agent_execution_manifest_logical_run");

        assertSqlFails(
                connection,
                """
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, process_revision, fencing_token,
                    logical_agent_run_id, manifest_uri, manifest_sha256,
                    output_snapshot_id, output_sha256, terminal_status, finalized_at
                ) values (
                    'MANIFEST_WRONG_OUTPUT_HASH', 'agent-execution-manifest.v1',
                    'tenant-control', 'CASE_LEGACY_CONTROL', 'EVIDENCE', 0, 0, 0,
                    'logical-run-wrong-output', 'urn:manifest:wrong-output',
                    repeat('f', 64), 'SNAPSHOT_1', repeat('f', 64),
                    'COMPLETED', now()
                )
                """,
                "fk_agent_execution_manifest_output");

        assertSqlFails(
                connection,
                "update immutable_payload_snapshot set visibility = 'INTERNAL' "
                        + "where id = 'SNAPSHOT_1'",
                "immutable_payload_snapshot is append-only");
        assertSqlFails(
                connection,
                "delete from agent_execution_manifest where id = 'MANIFEST_1'",
                "agent_execution_manifest is append-only");

        assertThat(columns(connection, "immutable_payload_snapshot"))
                .doesNotContain("payload_json", "payload_body", "content_body");
        assertThat(columns(connection, "agent_execution_manifest"))
                    .doesNotContain("manifest_json", "prompt_text", "model_response");
    }

    private static void assertRoomEpochLifecycleConstraints(Connection connection)
            throws SQLException {
        execute(
                connection,
                """
                update case_room_epoch
                   set process_revision = 2,
                       room_revision = 1,
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """);
        assertSqlFails(
                connection,
                """
                update case_room_epoch
                   set process_revision = 1,
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """,
                "revisions, update time and version cannot move backward");

        execute(
                connection,
                """
                update case_room_epoch
                   set lifecycle_status = 'TERMINAL',
                       process_revision = 3,
                       room_revision = 2,
                       terminal_at = updated_at + interval '1 second',
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """);
        assertSqlFails(
                connection,
                """
                update case_room_epoch
                   set lifecycle_status = 'ACTIVE',
                       terminal_at = null,
                       process_revision = 4,
                       room_revision = 3,
                       updated_at = updated_at + interval '1 second',
                       version = version + 1
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """,
                "TERMINAL lifecycle is immutable");
        assertSqlFails(
                connection,
                """
                update case_room_epoch
                   set terminal_at = terminal_at + interval '1 second'
                 where case_id = 'CASE_LEGACY_CONTROL'
                   and room_type = 'EVIDENCE'
                """,
                "TERMINAL lifecycle is immutable");
    }

    private String jdbcUrl() {
        return "jdbc:postgresql://"
                + POSTGRESQL.getHost()
                + ":"
                + POSTGRESQL.getMappedPort(5432)
                + "/"
                + DATABASE_NAME;
    }

    private Connection schemaConnection(String schema) throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl() + "?currentSchema=" + schema, USERNAME, PASSWORD);
    }

    private static Set<String> loadTables(Connection connection) throws SQLException {
        Set<String> values = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                """
                                select table_name
                                from information_schema.tables
                                where table_schema = 'public'
                                  and table_type = 'BASE TABLE'
                                """)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static Set<String> columns(Connection connection, String table)
            throws SQLException {
        Set<String> values = new TreeSet<>();
        try (var statement =
                connection.prepareStatement(
                        """
                        select column_name
                        from information_schema.columns
                        where table_schema = 'public' and table_name = ?
                        """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        }
        return values;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void assertSqlFails(
            Connection connection, String sql, String expectedMessage) {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(expectedMessage);
    }
}
