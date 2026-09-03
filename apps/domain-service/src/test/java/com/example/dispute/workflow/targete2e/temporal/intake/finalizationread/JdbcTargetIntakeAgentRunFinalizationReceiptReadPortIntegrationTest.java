package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTargetIntakeAgentRunFinalizationReceiptReadPortIntegrationTest {

    private static final String DATABASE = "intake_finalization_reader";
    private static final String USERNAME = "intake_reader_test";
    private static final String PASSWORD = "intake_reader_test";
    private static final String ACTIVATION_ID = "activation-intake-reader";
    private static final String ACTIVATION_HASH = "a".repeat(64);
    private static final String DATABASE_BINDING_HASH = "b".repeat(64);
    private static final String TENANT = "tenant-intake-reader";
    private static final String CASE_ID = "CASE_INTAKE_READER_001";
    private static final String LOGICAL_INPUT_HASH = "c".repeat(64);
    private static final String RESULT_ONE = "1".repeat(64);
    private static final String RESULT_TWO = "2".repeat(64);
    private static final String COMMAND_HASH_ONE = "3".repeat(64);
    private static final String COMMAND_HASH_TWO = "4".repeat(64);
    private static final String ENVELOPE_HASH_ONE = "5".repeat(64);
    private static final String ENVELOPE_HASH_TWO = "6".repeat(64);
    private static final String PARALLEL_RESULT = "a".repeat(64);
    private static final String PARALLEL_COMMAND_HASH = "b".repeat(64);
    private static final String PARALLEL_ENVELOPE_HASH = "c".repeat(64);
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final AgentPlatformContractCodec V1_CODEC = new AgentPlatformContractCodec();
    private static final Path COMMAND_FIXTURE = Path.of(
            "..",
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-valid.json");

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(
                    "public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void createProjectionTables() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES.getHost() + ':' + POSTGRES.getMappedPort(5432)
                        + '/' + DATABASE,
                USERNAME,
                PASSWORD);
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("""
                create table target_e2e_finalization_receipt (
                    receipt_id text not null,
                    activation_manifest_hash text not null,
                    receipt_canonical_bytes bytea not null,
                    schema_version text not null,
                    execution_lane text not null,
                    activation_id text not null,
                    tenant_surrogate text not null,
                    case_id text not null,
                    room_type text not null,
                    room_epoch bigint not null,
                    room_fencing_token bigint not null,
                    process_revision bigint not null,
                    stage_sequence bigint not null,
                    logical_run_id text not null,
                    attempt_id text not null,
                    command_hash text not null,
                    command_envelope_hash text not null,
                    graph_key text not null,
                    graph_version text not null,
                    checkpoint_schema_version text not null,
                    checkpoint_id text not null,
                    result_hash text not null,
                    proposal_hash text not null,
                    result_envelope_hash text not null,
                    agent_run_manifest_id text not null,
                    agent_run_manifest_hash text not null,
                    isolated_domain_db_binding_hash text not null,
                    committed_at timestamptz not null,
                    receipt_hash text not null,
                    formal_writer text not null,
                    domain_commit_status text not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table agent_run (
                    id text not null,
                    protocol text not null,
                    executor_kind text not null,
                    committed_attempt_id text,
                    final_result_hash text,
                    finalization_status text not null,
                    logical_input_hash text,
                    attempt_limit integer not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table agent_run_attempt (
                    id text not null,
                    agent_run_id text not null,
                    attempt_no bigint not null,
                    attempt_status text not null,
                    result_hash text,
                    logical_input_hash text,
                    command_id text,
                    command_request_hash text,
                    command_json jsonb,
                    previous_attempt_id text,
                    reset_required boolean not null,
                    public_sequence_offset integer not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table target_e2e_command_admission (
                    admission_id text not null,
                    activation_id text not null,
                    activation_manifest_hash text not null,
                    execution_lane text not null,
                    isolated_domain_db_binding_hash text not null,
                    tenant_surrogate text not null,
                    case_id text not null,
                    command_id text not null,
                    command_hash text not null,
                    command_envelope_hash text not null,
                    room_epoch bigint not null,
                    room_fencing_token bigint not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table target_e2e_intake_command_material (
                    admission_id text not null,
                    activation_id text not null,
                    activation_manifest_hash text not null,
                    isolated_domain_db_binding_hash text not null,
                    tenant_surrogate text not null,
                    case_id text not null,
                    command_id text not null,
                    command_hash text not null,
                    command_envelope_hash text not null,
                    room_epoch bigint not null,
                    room_fencing_token bigint not null,
                    context_canonical_json text not null,
                    context_sha256 text not null
                )
                """);
    }

    @Test
    void selectsOnlyTheCommittedRunWithTheExactPersistedProtocolAndDecodesRealJsonb()
            throws Exception {
        JsonNode fixture = MAPPER.readTree(COMMAND_FIXTURE.toFile()).required("instance");
        RoomGraphCommand v3Command = MAPPER.treeToValue(fixture, RoomGraphCommand.class);
        RoomGraphCommand v4Command = parallelCommand(v3Command);
        String canonicalCommand = ContractJson.canonicalString(fixture);
        String requestHash = fixture.required("request_hash").textValue();

        insertRun(
                v3Command.logicalRunId(),
                AgentRunProtocol.V3.wireValue(),
                v3Command.attemptId(),
                RESULT_TWO);
        insertAttempt(
                "failed-attempt-1",
                v3Command.logicalRunId(),
                1,
                "FAILED",
                RESULT_ONE,
                "failed-command-1",
                "7".repeat(64),
                "{\"attempt\":1}",
                null);
        insertAttempt(
                v3Command.attemptId(),
                v3Command.logicalRunId(),
                2,
                "COMPLETED",
                RESULT_TWO,
                v3Command.commandId(),
                requestHash,
                canonicalCommand,
                "failed-attempt-1");
        insertReceiptAndMaterial(
                "receipt-failed-1",
                v3Command.logicalRunId(),
                "failed-attempt-1",
                "failed-command-1",
                RESULT_ONE,
                COMMAND_HASH_ONE,
                ENVELOPE_HASH_ONE);
        insertReceiptAndMaterial(
                "receipt-winning-2",
                v3Command.logicalRunId(),
                v3Command.attemptId(),
                v3Command.commandId(),
                RESULT_TWO,
                COMMAND_HASH_TWO,
                ENVELOPE_HASH_TWO);

        insertRun(
                v4Command.logicalRunId(),
                AgentRunProtocol.V4.wireValue(),
                v4Command.attemptId(),
                PARALLEL_RESULT);
        insertAttempt(
                v4Command.attemptId(),
                v4Command.logicalRunId(),
                1,
                "COMPLETED",
                PARALLEL_RESULT,
                v4Command.commandId(),
                v4Command.requestHash(),
                ContractJson.canonicalString(
                        V1_CODEC.encode("room-graph-command.schema.json", v4Command)),
                null);
        insertReceiptAndMaterial(
                "receipt-parallel-1",
                v4Command.logicalRunId(),
                v4Command.attemptId(),
                v4Command.commandId(),
                PARALLEL_RESULT,
                PARALLEL_COMMAND_HASH,
                PARALLEL_ENVELOPE_HASH);

        List<Map<String, Object>> v3Rows =
                targetReceiptRows(v3Command.logicalRunId(), AgentRunProtocol.V3);
        assertThat(v3Rows).singleElement().satisfies(row -> {
            assertThat(row.get("attempt_id")).isEqualTo(v3Command.attemptId());
            assertThat(((Number) row.get("attempt_no")).longValue()).isEqualTo(2L);
            assertThat(row.get("attempt_command_id")).isEqualTo(v3Command.commandId());
            assertThat(row.get("attempt_request_hash")).isEqualTo(requestHash);
            String persistedJsonbText = (String) row.get("attempt_command_json");
            assertThat(persistedJsonbText).isNotEqualTo(canonicalCommand);
            assertThat(JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.decodeAttemptCommand(
                             MAPPER, persistedJsonbText, requestHash))
                    .isEqualTo(v3Command);
        });
        assertThat(targetReceiptRows(v3Command.logicalRunId(), AgentRunProtocol.V4)).isEmpty();

        assertThat(targetReceiptRows(v4Command.logicalRunId(), AgentRunProtocol.V4))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("attempt_id")).isEqualTo(v4Command.attemptId());
                    assertThat(((Number) row.get("attempt_no")).longValue()).isEqualTo(1L);
                    assertThat(row.get("attempt_command_id"))
                            .isEqualTo(v4Command.commandId());
                });
        assertThat(targetReceiptRows(v4Command.logicalRunId(), AgentRunProtocol.V3)).isEmpty();
    }

    private static void insertRun(
            String runId, String protocol, String committedAttemptId, String resultHash) {
        jdbc.update("""
                insert into agent_run (
                    id, protocol, executor_kind, committed_attempt_id, final_result_hash,
                    finalization_status, logical_input_hash, attempt_limit
                ) values (
                    :runId, :protocol, 'TEMPORAL_ACTIVITY', :committedAttemptId,
                    :resultHash, 'COMMITTED', :logicalInputHash, 3
                )
                """, new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("protocol", protocol)
                .addValue("committedAttemptId", committedAttemptId)
                .addValue("resultHash", resultHash)
                .addValue("logicalInputHash", LOGICAL_INPUT_HASH));
    }

    private static void insertAttempt(
            String attemptId,
            String runId,
            long attemptNo,
            String status,
            String resultHash,
            String commandId,
            String requestHash,
            String commandJson,
            String previousAttemptId) {
        jdbc.update("""
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, result_hash,
                    logical_input_hash, command_id, command_request_hash, command_json,
                    previous_attempt_id, reset_required, public_sequence_offset
                ) values (
                    :attemptId, :runId, :attemptNo, :status, :resultHash,
                    :logicalInputHash, :commandId, :requestHash, cast(:commandJson as jsonb),
                    :previousAttemptId, false, 0
                )
                """, new MapSqlParameterSource()
                .addValue("attemptId", attemptId)
                .addValue("runId", runId)
                .addValue("attemptNo", attemptNo)
                .addValue("status", status)
                .addValue("resultHash", resultHash)
                .addValue("logicalInputHash", LOGICAL_INPUT_HASH)
                .addValue("commandId", commandId)
                .addValue("requestHash", requestHash)
                .addValue("commandJson", commandJson)
                .addValue("previousAttemptId", previousAttemptId));
    }

    private static void insertReceiptAndMaterial(
            String receiptId,
            String runId,
            String attemptId,
            String commandId,
            String resultHash,
            String commandHash,
            String envelopeHash) {
        String admissionId = "admission-" + receiptId;
        jdbc.update("""
                insert into target_e2e_finalization_receipt (
                    receipt_id, activation_manifest_hash, receipt_canonical_bytes,
                    schema_version, execution_lane, activation_id, tenant_surrogate, case_id,
                    room_type, room_epoch, room_fencing_token, process_revision, stage_sequence,
                    logical_run_id, attempt_id, command_hash, command_envelope_hash,
                    graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                    result_hash, proposal_hash, result_envelope_hash, agent_run_manifest_id,
                    agent_run_manifest_hash, isolated_domain_db_binding_hash, committed_at,
                    receipt_hash, formal_writer, domain_commit_status
                ) values (
                    :receiptId, :activationHash, decode('00', 'hex'),
                    'target-e2e-finalization-receipt.v1', 'TARGET_E2E_CANDIDATE', :activationId,
                    :tenant, :caseId, 'INTAKE', 1, 2, 3, 4, :runId, :attemptId,
                    :commandHash, :envelopeHash, 'intake.v2', 'graph.v2', 'checkpoint.v2',
                    'checkpoint-1', :resultHash, :proposalHash, :resultEnvelopeHash,
                    'manifest-1', :manifestHash, :databaseBindingHash, current_timestamp,
                    :receiptHash, 'INTAKE_GRAPH_RESULT_FINALIZER', 'COMMITTED'
                )
                """, commonParameters(receiptId, runId, attemptId, commandHash, envelopeHash)
                .addValue("resultHash", resultHash));
        jdbc.update("""
                insert into target_e2e_command_admission (
                    admission_id, activation_id, activation_manifest_hash, execution_lane,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
                    command_hash, command_envelope_hash, room_epoch, room_fencing_token
                ) values (
                    :admissionId, :activationId, :activationHash, 'TARGET_E2E_CANDIDATE',
                    :databaseBindingHash, :tenant, :caseId, :commandId,
                    :commandHash, :envelopeHash, 1, 2
                )
                """, commonParameters(receiptId, runId, attemptId, commandHash, envelopeHash)
                .addValue("admissionId", admissionId)
                .addValue("commandId", commandId));
        jdbc.update("""
                insert into target_e2e_intake_command_material (
                    admission_id, activation_id, activation_manifest_hash,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
                    command_hash, command_envelope_hash, room_epoch, room_fencing_token,
                    context_canonical_json, context_sha256
                ) values (
                    :admissionId, :activationId, :activationHash, :databaseBindingHash,
                    :tenant, :caseId, :commandId, :commandHash, :envelopeHash, 1, 2,
                    '{}', :contextHash
                )
                """, commonParameters(receiptId, runId, attemptId, commandHash, envelopeHash)
                .addValue("admissionId", admissionId)
                .addValue("commandId", commandId)
                .addValue("contextHash", ContractJson.sha256Hex(MAPPER.createObjectNode())));
    }

    private static MapSqlParameterSource commonParameters(
            String receiptId,
            String runId,
            String attemptId,
            String commandHash,
            String envelopeHash) {
        return new MapSqlParameterSource()
                .addValue("receiptId", receiptId)
                .addValue("activationHash", ACTIVATION_HASH)
                .addValue("activationId", ACTIVATION_ID)
                .addValue("tenant", TENANT)
                .addValue("caseId", CASE_ID)
                .addValue("runId", runId)
                .addValue("attemptId", attemptId)
                .addValue("commandHash", commandHash)
                .addValue("envelopeHash", envelopeHash)
                .addValue("proposalHash", "8".repeat(64))
                .addValue("resultEnvelopeHash", "9".repeat(64))
                .addValue("manifestHash", "d".repeat(64))
                .addValue("databaseBindingHash", DATABASE_BINDING_HASH)
                .addValue("receiptHash", "e".repeat(64));
    }

    private static List<Map<String, Object>> targetReceiptRows(
            String logicalRunId, AgentRunProtocol protocol) {
        return jdbc.queryForList(
                targetReceiptSql(),
                Map.of(
                        "activationId", ACTIVATION_ID,
                        "activationManifestHash", ACTIVATION_HASH,
                        "tenantSurrogate", TENANT,
                        "caseId", CASE_ID,
                        "roomEpoch", 1L,
                        "roomFencingToken", 2L,
                        "processRevision", 3L,
                        "logicalRunId", logicalRunId,
                        "agentRunProtocol", protocol.wireValue()));
    }

    private static RoomGraphCommand parallelCommand(RoomGraphCommand source) {
        RoomGraphCommand.InvocationContext invocation =
                new RoomGraphCommand.InvocationContext(
                        RoomGraphCommand.PARALLEL_INTAKE_AGENT_PROFILE_ID,
                        source.invocationContext().promptProfileId(),
                        source.invocationContext().modelProfileId(),
                        RoomGraphCommand.PARALLEL_INTAKE_OUTPUT_SCHEMA,
                        source.invocationContext().policyVersion(),
                        source.invocationContext().guardrailVersion(),
                        source.invocationContext().toolCapabilities(),
                        source.invocationContext().envelopeKeyId(),
                        "nonce-parallel-001");
        RoomGraphCommand.SnapshotRef eventRef =
                new RoomGraphCommand.SnapshotRef(
                        "event-parallel-001",
                        "room-message.v1",
                        "s3://graph-input/case-001/event-parallel-001.json",
                        "d".repeat(64),
                        512);
        RoomGraphCommand provisional =
                new RoomGraphCommand(
                        source.schemaVersion(),
                        "graph-cmd-parallel-001",
                        "run-parallel-001",
                        "attempt-parallel-001",
                        source.tenantSurrogate(),
                        source.caseId(),
                        "ROOM_INTAKE_READER_PARALLEL",
                        source.roomType(),
                        source.roomEpoch(),
                        source.graphKey(),
                        source.graphVersion(),
                        source.checkpointSchemaVersion(),
                        "grt.v1.019bdf9f4a7279d3a23b7fd5c1e4a902",
                        source.actorScope(),
                        source.processRevision(),
                        source.stageCode(),
                        source.stageSequence(),
                        source.domainSnapshotRef(),
                        eventRef,
                        invocation,
                        new RoomGraphCommand.RetryBudget(3, 3, 1),
                        source.deadlineAt(),
                        source.traceparent(),
                        "0".repeat(64));
        ObjectNode canonical = (ObjectNode)
                V1_CODEC.encode("room-graph-command.schema.json", provisional);
        canonical.remove("request_hash");
        canonical.put("request_hash", ContractJson.sha256Hex(canonical));
        return V1_CODEC.decode(
                "room-graph-command.schema.json", canonical, RoomGraphCommand.class);
    }

    private static String targetReceiptSql() {
        try {
            Field field = JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.class
                    .getDeclaredField("TARGET_RECEIPT_SQL");
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot access target receipt SQL", failure);
        }
    }
}
