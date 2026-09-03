package com.example.dispute.workflow.shadow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.ActivityAuthorization;
import com.example.dispute.workflow.shadow.intake.admission.Es256IntakeSyntheticAdmissionVerifier;
import com.example.dispute.workflow.shadow.intake.admission.IntakeSyntheticAdmissionTrustSet;
import com.example.dispute.workflow.shadow.intake.admission.JdbcIntakeSignedSyntheticAdmissionPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

@Testcontainers
class JdbcIntakeSignedSyntheticAdmissionPortIntegrationTest {

    private static final String DATABASE = "dispute_system";
    private static final String USERNAME = "dispute_test";
    private static final String PASSWORD = "local_test_password";
    private static final String EPOCH_ID = "EPOCH_P4_ADMISSION";
    private static final String ROOM_ID = "ROOM_P4_ADMISSION";
    private static final String INITIATOR_ACTOR = "user-p4-admission";
    private static final String RESPONDENT_ACTOR = "merchant-p4-admission";
    private static final String INITIATOR_ACCESS = "ACCESS_P4_ADMISSION_I";
    private static final String RESPONDENT_ACCESS = "ACCESS_P4_ADMISSION_R";
    private static final String RESPONDENT_SESSION = "AGENT_SESSION_P4_ADMISSION_R";
    private static final String INITIATOR_REGISTRATION = "REG_P4_ADMISSION_I";
    private static final String RESPONDENT_REGISTRATION = "REG_P4_ADMISSION_R";
    private static final String INITIATOR_AUTHORITY = "AUTHORITY_P4_ADMISSION_I";
    private static final String RESPONDENT_AUTHORITY = "AUTHORITY_P4_ADMISSION_R";
    private static final String CASE_COMMAND_ID = "CASE_COMMAND_P4_ADMISSION";
    private static final String PAYLOAD_AUTHORITY_ID = "PAYLOAD_AUTHORITY_P4_ADMISSION";
    private static final String EVENT_BINDING_ID = "EVENT_BINDING_P4_ADMISSION";

    @Container
    private static final GenericContainer<?> POSTGRESQL = new GenericContainer<>(
                    DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static NamedParameterJdbcTemplate namedJdbc;

    @BeforeAll
    static void setUpDatabase() {
        String url = "jdbc:postgresql://" + POSTGRESQL.getHost() + ":"
                + POSTGRESQL.getMappedPort(5432) + "/" + DATABASE;
        Flyway.configure()
                .dataSource(url, USERNAME, PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, USERNAME, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        insertAuthorityFixture();
    }

    @Test
    void concurrentAdmissionPersistsOneImmutableReceiptAndRevocationClosesActivity() throws Exception {
        IntakeSyntheticAdmissionTestFixture fixture = new IntakeSyntheticAdmissionTestFixture();
        var verifier = new Es256IntakeSyntheticAdmissionVerifier(
                new IntakeSyntheticAdmissionTrustSet(Map.of(
                        IntakeSyntheticAdmissionTestFixture.KEY_ID, fixture.publicKey())),
                IntakeSyntheticAdmissionTestFixture.CLOCK);
        var port = new JdbcIntakeSignedSyntheticAdmissionPort(
                verifier,
                namedJdbc,
                transactions,
                new EpochAuthorityLockCoordinator(namedJdbc),
                IntakeSyntheticAdmissionTestFixture.CLOCK);
        String compactJws = fixture.sign(fixture.claims());
        var attempt = fixture.attempt(compactJws);

        int callers = 6;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(callers)) {
            for (int index = 0; index < callers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return port.admit(attempt, fixture.command()).authorizationHash();
                }));
            }
            ready.await();
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get()).matches("[0-9a-f]{64}");
            }
        }

        assertThat(jdbc.queryForObject(
                        "select count(*) from case_intake_synthetic_activity_admission",
                        Integer.class))
                .isOne();
        assertThat(port.admit(attempt, fixture.command()).authorizationHash())
                .isEqualTo(results.getFirst().get());
        assertThat(columnNames()).doesNotContain("compact_jws", "jws", "signature");
        assertThat(jdbc.queryForObject(
                        "select pinned_versions ->> 'room_workflow_build_id' "
                                + "from case_intake_synthetic_activity_admission",
                        String.class))
                .isEqualTo("synthetic-room-build");

        ActivityAuthorization authorization = ActivityAuthorization.from(
                activityEnvelope(fixture),
                IntakeSyntheticAdmissionTestFixture.REQUEST_HASH,
                IntakeSyntheticAdmissionTestFixture.THREAD_ID,
                IntakeSyntheticAdmissionTestFixture.AGENT_SESSION_ID);
        assertThat(port.isActivityAuthorized(authorization)).isTrue();
        var afterJwtExpiryPort = new JdbcIntakeSignedSyntheticAdmissionPort(
                verifier,
                namedJdbc,
                transactions,
                new EpochAuthorityLockCoordinator(namedJdbc),
                Clock.fixed(
                        IntakeSyntheticAdmissionTestFixture.NOW.plusSeconds(61),
                        ZoneOffset.UTC));
        assertThat(afterJwtExpiryPort.isActivityAuthorized(authorization)).isTrue();
        var afterCommandDeadlinePort = new JdbcIntakeSignedSyntheticAdmissionPort(
                verifier,
                namedJdbc,
                transactions,
                new EpochAuthorityLockCoordinator(namedJdbc),
                Clock.fixed(
                        IntakeSyntheticAdmissionTestFixture.NOW.plusSeconds(301),
                        ZoneOffset.UTC));
        assertThat(afterCommandDeadlinePort.isActivityAuthorized(authorization)).isFalse();

        assertThatThrownBy(() -> jdbc.update(
                        "update case_intake_synthetic_activity_admission "
                                + "set admission_status = 'REVOKED'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute(
                        "truncate table case_intake_synthetic_activity_admission"))
                .hasMessageContaining("append-only");

        assertThat(jdbc.update(
                        "update case_access_session set status = 'REVOKED' where id = ?",
                        INITIATOR_ACCESS))
                .isOne();
        assertThat(port.isActivityAuthorized(authorization)).isFalse();
    }

    private static ActivityEnvelope activityEnvelope(IntakeSyntheticAdmissionTestFixture fixture) {
        return new ActivityEnvelope(
                "intake-activity-envelope.v1",
                IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID,
                9,
                41,
                IntakeSyntheticAdmissionTestFixture.COMMAND_ID,
                1,
                IntakeCommandType.INTAKE_MESSAGE,
                IntakeParty.INITIATOR,
                IntakeSyntheticAdmissionTestFixture.ACTOR_SCOPE_HASH,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_REF,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_HASH,
                0,
                0,
                IntakeSyntheticAdmissionTestFixture.DEADLINE_MILLIS,
                new com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget(
                        "intake-retry-budget.v1", 2, 1, 1),
                new PinnedVersions(
                        "intake-pinned-versions.v1",
                        "synthetic-room-build",
                        "2.0.0",
                        "intake-checkpoint.v2",
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-turn-proposal.v2",
                        "intake-policy.v2",
                        "intake-guardrail.v2",
                        "no-tools.v1"),
                new ActivityInvocation(
                        "intake-activity-invocation.v1",
                        ActivityInvocationMode.FIRST_EXECUTION,
                        2));
    }

    private static List<String> columnNames() throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection connection = jdbc.getDataSource().getConnection();
                ResultSet result = connection.getMetaData().getColumns(
                        null, null, "case_intake_synthetic_activity_admission", null)) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static void insertAuthorityFixture() {
        transactions.executeWithoutResult(status -> {
            var now = java.time.OffsetDateTime.ofInstant(
                    IntakeSyntheticAdmissionTestFixture.NOW, java.time.ZoneOffset.UTC);
            jdbc.update("""
                    insert into fulfillment_dispute_case (
                        id, user_id, merchant_id, creation_idempotency_key, case_type,
                        case_status, initiator_role, initiator_id, respondent_role,
                        respondent_id, risk_level, title, description, created_by, updated_by
                    ) values (?, ?, ?, ?, 'DISPUTE', 'INTAKE', 'USER', ?, 'MERCHANT', ?,
                        'LOW', 'P4 admission', 'P4 admission', 'test', 'test')
                    """,
                    IntakeSyntheticAdmissionTestFixture.CASE_ID,
                    INITIATOR_ACTOR,
                    RESPONDENT_ACTOR,
                    "create-p4-admission",
                    INITIATOR_ACTOR,
                    RESPONDENT_ACTOR);
            jdbc.update("""
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at, created_by, updated_by
                    ) values (?, ?, 'INTAKE', 'OPEN', ?, 'test', 'test')
                    """, ROOM_ID, IntakeSyntheticAdmissionTestFixture.CASE_ID, now);
            jdbc.update("""
                    insert into case_room_epoch (
                        id, tenant_surrogate, case_id, room_id, room_type, room_epoch, writer_mode,
                        lifecycle_status, provisioning_status, process_revision, room_revision,
                        fencing_token, temporal_workflow_id, temporal_run_id,
                        room_temporal_workflow_id, room_temporal_run_id, temporal_build_id,
                        graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                        selection_schema_version, process_contract_version, workflow_type,
                        room_workflow_type, room_workflow_build_id, activated_at, provisioned_at,
                        created_at, updated_at
                    ) values (?, ?, ?, ?, 'INTAKE', 9, 'SHADOW', 'ACTIVE', 'READY', 0, 0, 41,
                        'CASE_WORKFLOW_P4_ADMISSION', 'CASE_RUN_P4_ADMISSION',
                        'ROOM_WORKFLOW_P4_ADMISSION', 'ROOM_RUN_P4_ADMISSION',
                        'synthetic-case-build', 'intake.v2', '2.0.0', 'intake-checkpoint.v2',
                        'agent-stream.v2', 'room-epoch-selection.v2', 'case-process-contract.v1',
                        'CaseProcessWorkflow', 'IntakeRoomWorkflow', 'synthetic-room-build',
                        ?, ?, ?, ?)
                    """,
                    EPOCH_ID,
                    IntakeSyntheticAdmissionTestFixture.TENANT,
                    IntakeSyntheticAdmissionTestFixture.CASE_ID,
                    ROOM_ID,
                    now, now, now, now);
            insertAccessAndAgent(
                    INITIATOR_ACTOR, "USER", "PARTY_USER", INITIATOR_ACCESS,
                    IntakeSyntheticAdmissionTestFixture.AGENT_SESSION_ID,
                    "asp.v1." + "2".repeat(64), now);
            insertAccessAndAgent(
                    RESPONDENT_ACTOR, "MERCHANT", "PARTY_MERCHANT", RESPONDENT_ACCESS,
                    RESPONDENT_SESSION, "asp.v1." + "3".repeat(64), now);
            insertGraphRegistration(
                    INITIATOR_ACTOR, "USER", INITIATOR_REGISTRATION,
                    IntakeSyntheticAdmissionTestFixture.THREAD_ID,
                    IntakeSyntheticAdmissionTestFixture.ACTOR_SCOPE_HASH,
                    IntakeSyntheticAdmissionTestFixture.AGENT_SESSION_ID,
                    IntakeSyntheticAdmissionTestFixture.REGISTRATION_HASH, now);
            insertGraphRegistration(
                    RESPONDENT_ACTOR, "MERCHANT", RESPONDENT_REGISTRATION,
                    "grt.v1." + "4".repeat(32), "5".repeat(64), RESPONDENT_SESSION,
                    "6".repeat(64), now);
            jdbc.update("""
                    insert into case_intake_epoch_selection_binding (
                        epoch_id, tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
                        selection_hash, writer_mode, case_workflow_type, case_workflow_build_id,
                        room_workflow_type, room_workflow_build_id, process_contract_version,
                        graph_key, graph_version, checkpoint_schema_version, state_schema_version,
                        stream_protocol, prompt_version, model_profile_id, output_schema_version,
                        policy_version, guardrail_version, tool_policy_version,
                        cohort_policy_version, agent_key, agent_session_profile_version,
                        memory_policy_id, created_at
                    ) values (?, ?, ?, 'INTAKE', 9, 41, ?, 'SHADOW', 'CaseProcessWorkflow',
                        'synthetic-case-build', 'IntakeRoomWorkflow', 'synthetic-room-build',
                        'case-process-contract.v1', 'intake.v2', '2.0.0', 'intake-checkpoint.v2',
                        'intake-graph-state.v2', 'agent-stream.v2', 'intake-prompt.v2',
                        'intake-model.synthetic.v1', 'intake-turn-proposal.v2', 'intake-policy.v2',
                        'intake-guardrail.v2', 'no-tools.v1', 'synthetic-cohort.v1',
                        'DISPUTE_INTAKE_OFFICER', 'agent-session-profile.v1',
                        'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1', ?)
                    """,
                    EPOCH_ID,
                    IntakeSyntheticAdmissionTestFixture.TENANT,
                    IntakeSyntheticAdmissionTestFixture.CASE_ID,
                    IntakeSyntheticAdmissionTestFixture.SELECTION_HASH,
                    now);
            insertPartyAuthority(
                    INITIATOR_AUTHORITY, "INITIATOR", INITIATOR_ACTOR, "USER",
                    INITIATOR_ACCESS, INITIATOR_REGISTRATION,
                    IntakeSyntheticAdmissionTestFixture.THREAD_ID,
                    IntakeSyntheticAdmissionTestFixture.ACTOR_SCOPE_HASH,
                    IntakeSyntheticAdmissionTestFixture.AGENT_SESSION_ID,
                    IntakeSyntheticAdmissionTestFixture.REGISTRATION_HASH,
                    "PARTY_USER", "asp.v1." + "2".repeat(64), now);
            insertPartyAuthority(
                    RESPONDENT_AUTHORITY, "RESPONDENT", RESPONDENT_ACTOR, "MERCHANT",
                    RESPONDENT_ACCESS, RESPONDENT_REGISTRATION, "grt.v1." + "4".repeat(32),
                    "5".repeat(64), RESPONDENT_SESSION, "6".repeat(64),
                    "PARTY_MERCHANT", "asp.v1." + "3".repeat(64), now);
            insertCommandAuthority(now);
        });
    }

    private static void insertAccessAndAgent(
            String actorId,
            String role,
            String permission,
            String accessId,
            String agentSessionId,
            String promptProfileId,
            java.time.OffsetDateTime now) {
        jdbc.update("""
                insert into case_access_session (
                    id, tenant_id, case_id, actor_id, actor_role, permission_level,
                    permission_scopes_json, status, created_at, updated_at, created_by
                ) values (?, ?, ?, ?, ?, ?, '["INTAKE_PARTICIPATE"]'::jsonb,
                    'ACTIVE', ?, ?, 'test')
                """, accessId, IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID, actorId, role, permission, now, now);
        jdbc.update("""
                insert into agent_conversation_session (
                    id, tenant_id, case_id, room_type, actor_id, actor_role, agent_key,
                    access_session_id, prompt_profile_id, memory_policy_id, conversation_scope,
                    status, created_at, updated_at, created_by
                ) values (?, ?, ?, 'INTAKE', ?, ?, 'DISPUTE_INTAKE_OFFICER', ?, ?,
                    'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1', ?, 'ACTIVE', ?, ?, 'test')
                """, agentSessionId, IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID, actorId, role, accessId,
                promptProfileId, "scope:" + actorId, now, now);
    }

    private static void insertGraphRegistration(
            String actorId,
            String role,
            String registrationId,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            String registrationHash,
            java.time.OffsetDateTime now) {
        jdbc.update("""
                insert into case_intake_graph_thread_binding (
                    registration_id, schema_version, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_id, actor_role, audience,
                    actor_capabilities_json, actor_scope_hash, agent_session_id, graph_key,
                    graph_version, checkpoint_schema_version, state_schema_version,
                    prompt_version, model_profile_id, output_schema_version, policy_version,
                    guardrail_version, tool_policy_version, writer_mode, registration_hash,
                    registration_status, issued_at, registered_at, created_at
                ) values (?, 'graph-private-thread-registration.v1', ?, ?, 'INTAKE', 9, 41,
                    ?, ?, ?, ?, '["graph.command.execute"]'::jsonb, ?, ?, 'intake.v2',
                    '2.0.0', 'intake-checkpoint.v2', 'intake-graph-state.v2', 'intake-prompt.v2',
                    'intake-model.synthetic.v1', 'intake-turn-proposal.v2', 'intake-policy.v2',
                    'intake-guardrail.v2', 'no-tools.v1', 'SHADOW', ?, 'REGISTERED', ?, ?, ?)
                """, registrationId, IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID, threadId, actorId, role, role,
                actorScopeHash, agentSessionId, registrationHash, now, now, now);
    }

    private static void insertPartyAuthority(
            String authorityId,
            String party,
            String actorId,
            String actorRole,
            String accessId,
            String registrationId,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            String registrationHash,
            String permission,
            String promptProfileId,
            java.time.OffsetDateTime now) {
        jdbc.update("""
                insert into case_intake_epoch_party_authority (
                    authority_id, epoch_id, party, tenant_surrogate, case_id,
                    session_tenant_id, session_case_id, room_type, room_epoch, fencing_token,
                    registration_id, registration_hash, thread_id, actor_id, actor_role,
                    audience, actor_scope_hash, access_session_id, permission_level,
                    agent_session_id, agent_key, prompt_version, agent_session_profile_version,
                    prompt_profile_id, memory_policy_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, 'INTAKE', 9, 41, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'DISPUTE_INTAKE_OFFICER', 'intake-prompt.v2', 'agent-session-profile.v1', ?,
                    'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1', ?)
                """, authorityId, EPOCH_ID, party, IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID,
                IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID,
                registrationId, registrationHash, threadId, actorId, actorRole, actorRole,
                actorScopeHash, accessId, permission, agentSessionId, promptProfileId, now);
    }

    private static void insertCommandAuthority(java.time.OffsetDateTime now) {
        jdbc.update("""
                insert into case_intake_snapshot_binding (
                    binding_id, thread_registration_id, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_scope_hash, agent_session_id,
                    actor_audience, binding_type, schema_version, artifact_id, object_uri,
                    object_version, content_sha256, size_bytes, visibility, domain_revision,
                    event_id, message_id, event_sequence, audience, occurred_at,
                    initialization_marker, created_at
                ) values (?, ?, ?, ?, 'INTAKE', 9, 41, ?, ?, ?, 'USER', 'EVENT',
                    'intake-turn-event.v2', 'ARTIFACT_P4_ADMISSION', ?, 'version-1', ?, 512,
                    'PRIVATE', 0, 'EVENT_P4_ADMISSION', 'MESSAGE_P4_ADMISSION', 1, 'USER', ?,
                    false, ?)
                """, EVENT_BINDING_ID, INITIATOR_REGISTRATION,
                IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID,
                IntakeSyntheticAdmissionTestFixture.THREAD_ID,
                IntakeSyntheticAdmissionTestFixture.ACTOR_SCOPE_HASH,
                IntakeSyntheticAdmissionTestFixture.AGENT_SESSION_ID,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_REF,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_HASH, now, now);
        jdbc.update("""
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id, case_command_sequence,
                    command_type, room_type, room_epoch, actor_id, actor_role,
                    actor_scopes_json, payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision, occurred_at, deadline_at,
                    traceparent, request_hash, command_status
                ) values (?, ?, ?, ?, 1, 'INTAKE_MESSAGE', 'INTAKE', 9, ?, 'USER',
                    '["INTAKE_PARTICIPATE"]'::jsonb, 'intake-turn-event.v2',
                    ?, ?, 512, 0, ?, ?,
                    '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01', ?,
                    'ORCHESTRATION_ACCEPTED')
                """, CASE_COMMAND_ID, IntakeSyntheticAdmissionTestFixture.COMMAND_ID,
                IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID, INITIATOR_ACTOR,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_REF,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_HASH,
                now, now.plusMinutes(5), IntakeSyntheticAdmissionTestFixture.REQUEST_HASH);
        jdbc.update("""
                insert into case_intake_command_payload_authority (
                    payload_authority_id, command_id, epoch_id, party_authority_id,
                    access_session_id, registration_id, tenant_surrogate, case_id, room_type,
                    room_epoch, fencing_token, thread_id, actor_id, actor_role,
                    actor_scope_hash, agent_session_id, source_kind, artifact_id,
                    existing_event_binding_id, schema_version, object_uri, object_version,
                    content_sha256, size_bytes, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'INTAKE', 9, 41, ?, ?, 'USER', ?, ?,
                    'EXISTING_PRIVATE_EVENT', 'ARTIFACT_P4_ADMISSION', ?,
                    'intake-turn-event.v2', ?, 'version-1', ?, 512, ?)
                """, PAYLOAD_AUTHORITY_ID, IntakeSyntheticAdmissionTestFixture.COMMAND_ID,
                EPOCH_ID, INITIATOR_AUTHORITY, INITIATOR_ACCESS, INITIATOR_REGISTRATION,
                IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID,
                IntakeSyntheticAdmissionTestFixture.THREAD_ID, INITIATOR_ACTOR,
                IntakeSyntheticAdmissionTestFixture.ACTOR_SCOPE_HASH,
                IntakeSyntheticAdmissionTestFixture.AGENT_SESSION_ID,
                EVENT_BINDING_ID,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_REF,
                IntakeSyntheticAdmissionTestFixture.PAYLOAD_HASH,
                now);
        jdbc.update("""
                insert into case_intake_command_authority (
                    case_command_id, command_id, case_command_sequence, command_type,
                    epoch_id, party_authority_id, access_session_id, registration_id,
                    tenant_surrogate, case_id, room_type, room_epoch, fencing_token,
                    thread_id, actor_id, actor_role, actor_scope_hash, agent_session_id,
                    payload_authority_id, request_hash, accepted_room_revision,
                    execution_disposition, created_at
                ) values (?, ?, 1, 'INTAKE_MESSAGE', ?, ?, ?, ?, ?, ?, 'INTAKE', 9, 41,
                    ?, ?, 'USER', ?, ?, ?, ?, 0, 'INERT_EXTERNAL_EVENT', ?)
                """, CASE_COMMAND_ID, IntakeSyntheticAdmissionTestFixture.COMMAND_ID,
                EPOCH_ID, INITIATOR_AUTHORITY, INITIATOR_ACCESS, INITIATOR_REGISTRATION,
                IntakeSyntheticAdmissionTestFixture.TENANT,
                IntakeSyntheticAdmissionTestFixture.CASE_ID,
                IntakeSyntheticAdmissionTestFixture.THREAD_ID, INITIATOR_ACTOR,
                IntakeSyntheticAdmissionTestFixture.ACTOR_SCOPE_HASH,
                IntakeSyntheticAdmissionTestFixture.AGENT_SESSION_ID,
                PAYLOAD_AUTHORITY_ID, IntakeSyntheticAdmissionTestFixture.REQUEST_HASH, now);
    }
}
