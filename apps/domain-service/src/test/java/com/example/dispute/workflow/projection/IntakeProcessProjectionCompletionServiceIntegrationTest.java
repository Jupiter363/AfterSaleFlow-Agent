package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt;
import com.example.dispute.workflow.application.intake.IntakeFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.application.projection.FencedProcessProjectionService;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService.CompletionOutcome;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService.CompletionResult;
import com.example.dispute.workflow.application.projection.ProjectionWriteRejectedException;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.AgentRunRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.GraphRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ManifestUsage;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ModelRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.WorkflowRef;
import com.example.dispute.workflow.contract.v1.AgentPlatformContractCodec;
import com.example.dispute.workflow.contract.v1.AgentRunWorkflowIds;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptCodec;
import com.example.dispute.workflow.runtime.graph.ProductionGraphCommandEnvelope;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.ActivationLifecycle;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.ActivationRegistration;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.BuildBindings;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmission;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmissionResult;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandCompletion;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.DatabaseBinding;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.GraphBinding;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.ImageDigests;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.RegistrationDisposition;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.SyntheticCaseScope;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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
    IntakeProcessProjectionCompletionService.class,
    FencedProcessProjectionService.class,
    IntakeProcessProjectionCompletionServiceIntegrationTest.ProjectionTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IntakeProcessProjectionCompletionServiceIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final String DATABASE = "intake_projection_recovered_winner";
    private static final String USERNAME = "projection_recovery_test";
    private static final String PASSWORD = "projection_recovery_test";
    private static final String ACTIVATION_HASH = "a".repeat(64);
    private static final String DOMAIN_BINDING_HASH = "6".repeat(64);
    private static final String TENANT = "tenant-projection-recovery";
    private static final String CASE_PREFIX = "CASE_P9_PROJECTION_";
    private static final String CASE_BUILD_ID = "case-build-projection-recovery";
    private static final String CONTROL_BUILD_ID = "control-build-projection-recovery";
    private static final String AGENT_BUILD_ID = "agent-build-projection-recovery";
    private static final String GRAPH_KEY = "all-rooms.production-runtime.v1";
    private static final String GRAPH_VERSION = "production-runtime-graph.projection-recovery.v1";
    private static final String CHECKPOINT_SCHEMA = "production-runtime-checkpoint.v1";
    private static final String GRAPH_BINDING_HASH = "f".repeat(64);
    private static final String GRAPH_CODE_BUILD_ID = "graph-code-projection-recovery";
    private static final String LOGICAL_INPUT_HASH = "b".repeat(64);
    private static final String CASE_INGRESS_REQUEST_HASH = "4".repeat(64);
    private static final String CASE_PAYLOAD_SHA256 = "9".repeat(64);
    private static final String ROOT_RETRYABLE_ERROR_CODE = "GRAPH_LEASE_LOST";
    private static final String RESULT_HASH = "c".repeat(64);
    private static final String TARGET_PROPOSAL_HASH = "d".repeat(64);
    private static final String FORMAL_PROPOSAL_HASH = "5".repeat(64);
    private static final String RESULT_ENVELOPE_HASH = "e".repeat(64);
    private static final long ROOM_EPOCH = 0;
    private static final long FENCING_TOKEN = 1;
    private static final long PROCESS_REVISION = 1;
    private static final long ROOM_REVISION = 1;
    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger();
    private static final Path COMMAND_FIXTURE =
            Path.of(
                    "..",
                    "..",
                    "contracts",
                    "agent-platform",
                    "v1",
                    "fixtures",
                    "valid",
                    "room-graph-command-valid.json");

    private String ACTIVATION_ID;
    private String ACTIVATION_ENVIRONMENT_ID;
    private String ACTIVATION_NONCE;
    private String CASE_ID;
    private String CASE_ROW_ID;
    private String ROOM_ID;
    private String EPOCH_ID;
    private String CASE_COMMAND_ROW_ID;
    private String ORIGINAL_COMMAND_ID;
    private String WINNING_COMMAND_ID;
    private String LOGICAL_RUN_ID;
    private String LOGICAL_IDEMPOTENCY_KEY;
    private String AGENT_RUN_WORKFLOW_ID;
    private String AGENT_RUN_WORKFLOW_RUN_ID;
    private String ROOT_ATTEMPT_ID;
    private String WINNING_ATTEMPT_ID;
    private String WORKFLOW_ID;
    private String CASE_RUN_ID;
    private String ROOM_WORKFLOW_ID;
    private String ROOM_RUN_ID;
    private String OUTPUT_SNAPSHOT_ID;
    private String OUTPUT_URI;
    private String MANIFEST_ID;
    private String MANIFEST_URI;
    private String TARGET_RECEIPT_ID;
    private String FORMAL_OPERATION_ID;
    private String FORMAL_EVENT_ID;
    private String FORMAL_MESSAGE_ID;
    private String AGENT_SESSION_ID;
    private String THREAD_ID;

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", DATABASE)
                    .withEnv("POSTGRES_USER", USERNAME)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
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
                                + "/"
                                + DATABASE);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    @Autowired private IntakeProcessProjectionCompletionService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void initializeFixtureScope() {
        useFixtureScope(FIXTURE_SEQUENCE.incrementAndGet());
    }

    private void useFixtureScope(int sequence) {
        String suffix = "%02d".formatted(sequence);
        String authorityToken = "%032x".formatted(sequence);
        ACTIVATION_ID = "p9act.v1." + authorityToken;
        ACTIVATION_ENVIRONMENT_ID = "environment-projection-recovery-" + suffix;
        ACTIVATION_NONCE = "projection-recovery-nonce-" + authorityToken;
        CASE_ID = CASE_PREFIX + suffix;
        CASE_ROW_ID = CASE_ID;
        ROOM_ID = "ROOM_P9_PROJECTION_" + suffix;
        EPOCH_ID = "EPOCH_P9_PROJECTION_" + suffix;
        CASE_COMMAND_ROW_ID = "CMD_P9_PROJECTION_" + suffix;
        ORIGINAL_COMMAND_ID = "intake-command-original-" + suffix;
        WINNING_COMMAND_ID = "intake-command-winning-" + suffix;
        LOGICAL_RUN_ID = "run-intake-projection-recovery-" + suffix;
        LOGICAL_IDEMPOTENCY_KEY = "logical-intake-projection-recovery-" + suffix;
        AGENT_RUN_WORKFLOW_ID = AgentRunWorkflowIds.forLogicalRun(LOGICAL_RUN_ID);
        AGENT_RUN_WORKFLOW_RUN_ID = "agent-run-workflow-run-projection-recovery-" + suffix;
        ROOT_ATTEMPT_ID = "attempt-intake-root-" + suffix;
        WINNING_ATTEMPT_ID = "attempt-intake-winning-" + suffix;
        WORKFLOW_ID = CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
        CASE_RUN_ID = "case-run-projection-recovery-" + suffix;
        ROOM_WORKFLOW_ID =
                CaseProcessWorkflowProtocol.roomWorkflowId(
                        CASE_ID, RoomType.INTAKE, ROOM_EPOCH);
        ROOM_RUN_ID = "room-run-projection-recovery-" + suffix;
        OUTPUT_SNAPSHOT_ID = "SNAPSHOT_P9_PROJECTION_RESULT_" + suffix;
        OUTPUT_URI = "urn:test:intake:projection-recovery-result:" + suffix;
        MANIFEST_ID = "MANIFEST_P9_PROJECTION_" + suffix;
        MANIFEST_URI = "urn:test:intake:projection-manifest:" + suffix;
        TARGET_RECEIPT_ID = "RECEIPT_P9_PROJECTION_" + suffix;
        FORMAL_OPERATION_ID = "OP_P9_PROJECTION_FORMAL_" + suffix;
        FORMAL_EVENT_ID = "EVENT_P9_PROJECTION_FORMAL_" + suffix;
        FORMAL_MESSAGE_ID = "MESSAGE_P9_PROJECTION_FORMAL_" + suffix;
        AGENT_SESSION_ID = "agent-session-projection-recovery-" + suffix;
        THREAD_ID = "grt.v1." + authorityToken;
    }

    @Test
    void completeConsumedEventAdoptsExactlyBoundRecoveredWinningAttempt() throws Exception {
        Fixture fixture = fixture();
        insertFixture(fixture);
        assertRecoveredWinnerPreconditions(fixture, "ABORTED");
        assertPersistedTypedContextCanonicalAuthority(ORIGINAL_COMMAND_ID);
        assertPersistedTypedContextCanonicalAuthority(WINNING_COMMAND_ID);
        assertPersistedTypedCommandCanonicalAuthority(
                ROOT_ATTEMPT_ID,
                fixture.rootMaterial().context().targetAgentRun().request().command());
        assertPersistedTypedCommandCanonicalAuthority(
                WINNING_ATTEMPT_ID,
                fixture.winningMaterial()
                        .context()
                        .targetAgentRun()
                        .request()
                        .command());
        assertPersistedTypedFormalReceiptCanonicalAuthority(fixture);

        CompletionResult completed = service.completeConsumedEvent(fixture.projectionCommand());

        assertThat(completed).isNotNull();
        assertThat(completed.outcome()).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(completed.logicalRunId()).isEqualTo(LOGICAL_RUN_ID);
        assertThat(completed.attemptId()).isEqualTo(WINNING_ATTEMPT_ID);
        assertProjectionAppliedOnce();
    }

    @Test
    void completeConsumedEventReplaysRecoveredWinningAttemptWithoutDuplicateProjection()
            throws Exception {
        Fixture fixture = fixture();
        insertFixture(fixture);

        CompletionResult applied = service.completeConsumedEvent(fixture.projectionCommand());
        ProjectionState appliedState = projectionState();
        CompletionResult replayed = service.completeConsumedEvent(fixture.projectionCommand());

        assertThat(applied.outcome()).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(replayed)
                .isEqualTo(
                        new CompletionResult(
                                CompletionOutcome.IDEMPOTENT_REPLAY,
                                applied.logicalRunId(),
                                applied.attemptId(),
                                applied.processRevision(),
                                applied.roomRevision(),
                                applied.lastCaseEventSequence(),
                                applied.resultRef(),
                                applied.resultSha256(),
                                applied.completedAt()));
        assertThat(projectionState()).isEqualTo(appliedState);
        assertProjectionAppliedOnce();
    }

    @Test
    void completeConsumedEventSeparatesIngressAndRetryableRecoveredRootRequestAuthorities()
            throws Exception {
        Fixture fixture = fixture();
        insertFixture(fixture);
        bindDistinctIngressAndRetryableFailedRoot(fixture);
        assertRecoveredWinnerPreconditions(fixture, "FAILED");
        assertDistinctIngressAndRecoveredRootAuthority(fixture);
        long formalOperationCount = countFormalOperations();
        long formalEventCount = countFormalEvents();

        CompletionResult applied = service.completeConsumedEvent(fixture.projectionCommand());
        ProjectionState appliedState = projectionState();
        CompletionResult replayed = service.completeConsumedEvent(fixture.projectionCommand());

        assertThat(applied.outcome()).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(applied.logicalRunId()).isEqualTo(LOGICAL_RUN_ID);
        assertThat(applied.attemptId()).isEqualTo(WINNING_ATTEMPT_ID);
        assertThat(replayed)
                .isEqualTo(
                        new CompletionResult(
                                CompletionOutcome.IDEMPOTENT_REPLAY,
                                applied.logicalRunId(),
                                applied.attemptId(),
                                applied.processRevision(),
                                applied.roomRevision(),
                                applied.lastCaseEventSequence(),
                                applied.resultRef(),
                                applied.resultSha256(),
                                applied.completedAt()));
        assertThat(projectionState()).isEqualTo(appliedState);
        assertProjectionAppliedOnce();
        assertThat(countFormalOperations()).isEqualTo(formalOperationCount);
        assertThat(countFormalEvents()).isEqualTo(formalEventCount);
    }

    @Test
    void completeConsumedEventRejectsRecoveredRunHashDriftFromRootGraphRequest()
            throws Exception {
        Fixture fixture = fixture();
        insertFixture(fixture);
        bindDistinctIngressAndRetryableFailedRoot(fixture);
        assertThat(
                        jdbc.update(
                                "update agent_run set request_hash = ? where id = ?",
                                "0".repeat(64),
                                LOGICAL_RUN_ID))
                .isEqualTo(1);

        assertRejectedWithoutProjectionMutation(
                fixture, "INTAKE_PROJECTION_RECOVERED_AUTHORITY_INVALID");
    }

    @Test
    void completeConsumedEventRejectsRecoveredPredecessorTerminalAuthorityDrift()
            throws Exception {
        assertRejectedPredecessorAuthorityDrift(
                "COMPLETED", ROOT_RETRYABLE_ERROR_CODE, true, "CREATE_NEXT_ATTEMPT", true);
        useFixtureScope(FIXTURE_SEQUENCE.incrementAndGet());
        assertRejectedPredecessorAuthorityDrift(
                "RESULT_READY", ROOT_RETRYABLE_ERROR_CODE, true, "CREATE_NEXT_ATTEMPT", true);
        useFixtureScope(FIXTURE_SEQUENCE.incrementAndGet());
        assertRejectedPredecessorAuthorityDrift(
                "FAILED", "", true, "CREATE_NEXT_ATTEMPT", true);
        useFixtureScope(FIXTURE_SEQUENCE.incrementAndGet());
        assertRejectedPredecessorAuthorityDrift(
                "FAILED", ROOT_RETRYABLE_ERROR_CODE, false, "CREATE_NEXT_ATTEMPT", true);
        useFixtureScope(FIXTURE_SEQUENCE.incrementAndGet());
        assertRejectedPredecessorAuthorityDrift(
                "FAILED", ROOT_RETRYABLE_ERROR_CODE, true, null, true);
        useFixtureScope(FIXTURE_SEQUENCE.incrementAndGet());
        assertRejectedPredecessorAuthorityDrift(
                "FAILED", ROOT_RETRYABLE_ERROR_CODE, true, "CREATE_NEXT_ATTEMPT", false);
    }

    @Test
    void completeConsumedEventRejectsLogicalRunWithoutRootCommandLineage() throws Exception {
        Fixture fixture = fixture();
        insertFixture(fixture);
        jdbc.update(
                "update case_command set command_id = ? where id = ?",
                "intake-command-foreign-root-" + CASE_ID,
                CASE_COMMAND_ROW_ID);

        assertRejectedWithoutProjectionMutation(
                fixture, "INTAKE_PROJECTION_EVIDENCE_MISSING");
    }

    @Test
    void completeConsumedEventRejectsBrokenOrMissingAdjacentRetryMaterial() throws Exception {
        Fixture fixture = fixture();
        insertFixture(fixture, registration(), false);

        assertRejectedWithoutProjectionMutation(
                fixture, "INTAKE_PROJECTION_RECOVERED_AUTHORITY_INVALID");

        useFixtureScope(FIXTURE_SEQUENCE.incrementAndGet());
        Fixture buildDriftFixture = fixture();
        ActivationRegistration buildDriftRegistration =
                registration(
                        new BuildBindings(
                                CASE_BUILD_ID,
                                CONTROL_BUILD_ID,
                                AGENT_BUILD_ID + "-foreign"));
        insertFixture(buildDriftFixture, buildDriftRegistration, true);

        assertRejectedWithoutProjectionMutation(
                buildDriftFixture, "INTAKE_PROJECTION_RECOVERED_AUTHORITY_INVALID");
    }

    @Test
    void completeConsumedEventPreservesLegacyExactCommandAuthority() throws Exception {
        Fixture fixture = legacyFixture();
        insertLegacyFixture(fixture);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from agent_run where case_id = ?",
                                Long.class,
                                CASE_ID))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) "
                                        + "from production_runtime_intake_command_material "
                                        + "where case_id = ?",
                                Long.class,
                                CASE_ID))
                .isZero();

        CompletionResult applied = service.completeConsumedEvent(fixture.projectionCommand());
        ProjectionState appliedState = projectionState();
        CompletionResult replayed = service.completeConsumedEvent(fixture.projectionCommand());

        assertThat(applied.outcome()).isEqualTo(CompletionOutcome.APPLIED);
        assertThat(applied.logicalRunId()).isEqualTo(LOGICAL_RUN_ID);
        assertThat(applied.attemptId()).isEqualTo(ROOT_ATTEMPT_ID);
        assertThat(replayed.outcome()).isEqualTo(CompletionOutcome.IDEMPOTENT_REPLAY);
        assertThat(replayed.logicalRunId()).isEqualTo(applied.logicalRunId());
        assertThat(replayed.attemptId()).isEqualTo(applied.attemptId());
        assertThat(projectionState()).isEqualTo(appliedState);
        assertProjectionAppliedOnce();
    }

    private Fixture fixture() throws Exception {
        JsonNode sourceDocument = objectMapper.readTree(COMMAND_FIXTURE.toFile()).required("instance");
        RoomGraphCommand source = objectMapper.treeToValue(sourceDocument, RoomGraphCommand.class);
        RoomGraphCommand rootCommand = command(source, ORIGINAL_COMMAND_ID, ROOT_ATTEMPT_ID);
        RoomGraphCommand winningCommand = command(source, WINNING_COMMAND_ID, WINNING_ATTEMPT_ID);
        ProductionGraphEnvelopeCodec envelopeCodec = new ProductionGraphEnvelopeCodec(objectMapper);
        ProductionGraphCommandEnvelope rootEnvelope =
                envelopeCodec.wrapCommand(ACTIVATION_ID, FENCING_TOKEN, rootCommand);
        ProductionGraphCommandEnvelope winningEnvelope =
                envelopeCodec.wrapCommand(ACTIVATION_ID, FENCING_TOKEN, winningCommand);
        ExecuteAgentRunRequest rootRequest =
                new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        LOGICAL_RUN_ID,
                        1,
                        2,
                        "agent-stream.v2",
                        LOGICAL_INPUT_HASH,
                        null,
                        false,
                        0,
                        rootCommand);
        ExecuteAgentRunRequest winningRequest =
                new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        LOGICAL_RUN_ID,
                        2,
                        2,
                        "agent-stream.v2",
                        LOGICAL_INPUT_HASH,
                        ROOT_ATTEMPT_ID,
                        true,
                        1,
                        winningCommand);
        Material rootMaterial =
                material(
                        IntakeTargetAgentRunContext.INITIAL_SCHEMA_VERSION,
                        rootEnvelope,
                        rootRequest);
        Material winningMaterial =
                material(
                        IntakeTargetAgentRunContext.RETRY_SCHEMA_VERSION,
                        winningEnvelope,
                        winningRequest);
        ArtifactPointer output =
                new ArtifactPointer(
                        OUTPUT_SNAPSHOT_ID,
                        "room-graph-result.v1",
                        OUTPUT_URI,
                        RESULT_HASH);
        AgentExecutionManifest manifest =
                new AgentExecutionManifest(
                        "agent-execution-manifest.v1",
                        MANIFEST_ID,
                        TENANT,
                        CASE_ID,
                        ROOM_EPOCH,
                        PROCESS_REVISION,
                        FENCING_TOKEN,
                        new WorkflowRef(
                                AGENT_RUN_WORKFLOW_ID,
                                AGENT_RUN_WORKFLOW_RUN_ID,
                                AgentRunWorkflow.WORKFLOW_TYPE,
                                AGENT_BUILD_ID),
                        new AgentRunRef(
                                LOGICAL_RUN_ID,
                                WINNING_ATTEMPT_ID,
                                LOGICAL_IDEMPOTENCY_KEY),
                        new GraphRef(
                                GRAPH_KEY,
                                GRAPH_VERSION,
                                CHECKPOINT_SCHEMA,
                                "checkpoint-intake-winning",
                                2),
                        new ModelRef(
                                winningCommand.invocationContext().promptProfileId(),
                                winningCommand.invocationContext().modelProfileId(),
                                "synthetic",
                                "synthetic-v1",
                                winningCommand.requestHash(),
                                RESULT_HASH),
                        Map.of("process_contract", "case-process-contract.v1"),
                        winningCommand.invocationContext().policyVersion(),
                        winningCommand.invocationContext().guardrailVersion(),
                        List.of(),
                        List.of(),
                        output,
                        new ManifestUsage(10, 5, 15, 1),
                        winningCommand.traceparent(),
                        NOW);
        String manifestHash = ContractJson.sha256Hex(objectMapper.valueToTree(manifest));
        ProductionFinalizationReceipt targetReceipt =
                ProductionFinalizationReceipt.committed(
                        new ProductionFinalizationReceipt.CommitFacts(
                                ACTIVATION_ID,
                                TENANT,
                                CASE_ID,
                                RoomType.INTAKE,
                                ROOM_EPOCH,
                                FENCING_TOKEN,
                                PROCESS_REVISION,
                                winningCommand.stageSequence(),
                                LOGICAL_RUN_ID,
                                WINNING_ATTEMPT_ID,
                                winningEnvelope.commandHash(),
                                winningEnvelope.commandEnvelopeHash(),
                                GRAPH_KEY,
                                GRAPH_VERSION,
                                CHECKPOINT_SCHEMA,
                                "checkpoint-intake-winning",
                                RESULT_HASH,
                                TARGET_PROPOSAL_HASH,
                                RESULT_ENVELOPE_HASH,
                                MANIFEST_ID,
                                manifestHash,
                                DOMAIN_BINDING_HASH,
                                NOW));
        String operationKey =
                IntakeOperationKeys.turnFinalize(
                        CASE_ID,
                        ROOM_EPOCH,
                        THREAD_ID,
                        WINNING_COMMAND_ID,
                        RESULT_HASH);
        String actorScopeHash = ContractJson.sha256Hex(objectMapper.valueToTree(winningCommand.actorScope()));
        IntakeFinalizationReceipt formalReceipt =
                IntakeFinalizationReceipt.committed(
                        new CommitFacts(
                                operationKey,
                                TENANT,
                                CASE_ID,
                                ROOM_EPOCH,
                                THREAD_ID,
                                actorScopeHash,
                                AGENT_SESSION_ID,
                                WINNING_COMMAND_ID,
                                LOGICAL_RUN_ID,
                                WINNING_ATTEMPT_ID,
                                RESULT_HASH,
                                FORMAL_PROPOSAL_HASH,
                                PROCESS_REVISION,
                                ROOM_REVISION,
                                FENCING_TOKEN,
                                FORMAL_MESSAGE_ID,
                                null,
                                null,
                                List.of(FORMAL_EVENT_ID),
                                List.of(),
                                NOW));
        ObjectNode event = objectMapper.createObjectNode();
        event.put("schema_version", "intake-turn-committed-event.v1");
        event.put("event_type", "TURN_READY_TO_CONFIRM");
        event.put("operation_key", operationKey);
        event.put("request_hash", winningCommand.requestHash());
        event.put("result_hash", RESULT_HASH);
        event.put("proposal_hash", FORMAL_PROPOSAL_HASH);
        event.put("message_id", FORMAL_MESSAGE_ID);
        event.put("actor_scope_hash", actorScopeHash);
        event.set("receipt", objectMapper.valueToTree(formalReceipt));
        CompleteConsumedIntakeProjectionCommand projectionCommand =
                new CompleteConsumedIntakeProjectionCommand(
                        "complete-consumed-intake-projection.v1",
                        TENANT,
                        CASE_ID,
                        FORMAL_EVENT_ID,
                        1,
                        "TURN_READY_TO_CONFIRM",
                        1,
                        ROOM_EPOCH,
                        FENCING_TOKEN,
                        PROCESS_REVISION + 1,
                        ROOM_REVISION + 1,
                        WORKFLOW_ID,
                        CASE_RUN_ID,
                        ROOM_RUN_ID);
        return new Fixture(
                rootCommand,
                winningCommand,
                rootEnvelope,
                winningEnvelope,
                rootMaterial,
                winningMaterial,
                manifest,
                manifestHash,
                targetReceipt,
                formalReceipt,
                ContractJson.canonicalString(event),
                operationKey,
                projectionCommand);
    }

    private Fixture legacyFixture() throws Exception {
        Fixture recovered = fixture();
        RoomGraphCommand command = recovered.rootCommand();
        String operationKey =
                IntakeOperationKeys.turnFinalize(
                        CASE_ID, ROOM_EPOCH, THREAD_ID, ORIGINAL_COMMAND_ID, RESULT_HASH);
        String actorScopeHash =
                ContractJson.sha256Hex(objectMapper.valueToTree(command.actorScope()));
        IntakeFinalizationReceipt formalReceipt =
                IntakeFinalizationReceipt.committed(
                        new CommitFacts(
                                operationKey,
                                TENANT,
                                CASE_ID,
                                ROOM_EPOCH,
                                THREAD_ID,
                                actorScopeHash,
                                AGENT_SESSION_ID,
                                ORIGINAL_COMMAND_ID,
                                LOGICAL_RUN_ID,
                                ROOT_ATTEMPT_ID,
                                RESULT_HASH,
                                FORMAL_PROPOSAL_HASH,
                                PROCESS_REVISION,
                                ROOM_REVISION,
                                FENCING_TOKEN,
                                FORMAL_MESSAGE_ID,
                                null,
                                null,
                                List.of(FORMAL_EVENT_ID),
                                List.of(),
                                NOW));
        ObjectNode event = objectMapper.createObjectNode();
        event.put("schema_version", "intake-turn-committed-event.v1");
        event.put("event_type", "TURN_READY_TO_CONFIRM");
        event.put("operation_key", operationKey);
        event.put("request_hash", command.requestHash());
        event.put("result_hash", RESULT_HASH);
        event.put("proposal_hash", FORMAL_PROPOSAL_HASH);
        event.put("message_id", FORMAL_MESSAGE_ID);
        event.put("actor_scope_hash", actorScopeHash);
        event.set("receipt", objectMapper.valueToTree(formalReceipt));
        return new Fixture(
                recovered.rootCommand(),
                recovered.winningCommand(),
                recovered.rootEnvelope(),
                recovered.winningEnvelope(),
                recovered.rootMaterial(),
                recovered.winningMaterial(),
                recovered.manifest(),
                recovered.manifestHash(),
                recovered.targetReceipt(),
                formalReceipt,
                ContractJson.canonicalString(event),
                operationKey,
                recovered.projectionCommand());
    }

    private RoomGraphCommand command(
            RoomGraphCommand source, String commandId, String attemptId) {
        RoomGraphCommand unsigned =
                new RoomGraphCommand(
                        source.schemaVersion(),
                        commandId,
                        LOGICAL_RUN_ID,
                        attemptId,
                        TENANT,
                        CASE_ID,
                        RoomType.INTAKE,
                        ROOM_EPOCH,
                        GRAPH_KEY,
                        GRAPH_VERSION,
                        CHECKPOINT_SCHEMA,
                        THREAD_ID,
                        source.actorScope(),
                        PROCESS_REVISION,
                        "INTAKE_ACTIVE",
                        1,
                        source.domainSnapshotRef(),
                        new RoomGraphCommand.SnapshotRef(
                                CASE_COMMAND_ROW_ID,
                                "intake-command.v1",
                                "urn:test:intake:projection-original",
                                CASE_PAYLOAD_SHA256,
                                32),
                        source.invocationContext(),
                        source.retryBudget(),
                        NOW.plusSeconds(1_800),
                        source.traceparent(),
                        "0".repeat(64));
        ObjectNode unhashed =
                ((ObjectNode)
                                new AgentPlatformContractCodec()
                                        .encode("room-graph-command.schema.json", unsigned))
                        .deepCopy();
        unhashed.remove("request_hash");
        String requestHash = ContractJson.sha256Hex(unhashed);
        return new RoomGraphCommand(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.tenantSurrogate(),
                unsigned.caseId(),
                unsigned.roomType(),
                unsigned.roomEpoch(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointSchemaVersion(),
                unsigned.threadId(),
                unsigned.actorScope(),
                unsigned.processRevision(),
                unsigned.stageCode(),
                unsigned.stageSequence(),
                unsigned.domainSnapshotRef(),
                unsigned.eventRef(),
                unsigned.invocationContext(),
                unsigned.retryBudget(),
                unsigned.deadlineAt(),
                unsigned.traceparent(),
                requestHash);
    }

    private Material material(
            String targetSchema,
            ProductionGraphCommandEnvelope envelope,
            ExecuteAgentRunRequest request) {
        IntakeTargetAgentRunContext target =
                new IntakeTargetAgentRunContext(
                        targetSchema,
                        IntakeTargetAgentRunContext.TARGET_LANE,
                        ACTIVATION_ID,
                        ACTIVATION_HASH,
                        FENCING_TOKEN,
                        PROCESS_REVISION,
                        ROOM_REVISION,
                        CASE_BUILD_ID,
                        CONTROL_BUILD_ID,
                        AGENT_BUILD_ID,
                        GRAPH_BINDING_HASH,
                        GRAPH_CODE_BUILD_ID,
                        envelope.commandHash(),
                        envelope.commandEnvelopeHash(),
                        request);
        IntakeCommandExecutionContext context =
                new IntakeCommandExecutionContext(
                        "intake-command-execution-context.v2",
                        THREAD_ID,
                        AGENT_SESSION_ID,
                        request.command().deadlineAt().toEpochMilli(),
                        new RetryBudget("intake-retry-budget.v1", 2, 3, 1),
                        null,
                        target);
        ObjectMapper materialMapper =
                objectMapper
                        .copy()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        JsonNode document = materialMapper.valueToTree(context);
        String canonicalJson = ContractJson.canonicalString(document);
        return new Material(context, canonicalJson, ContractJson.sha256Hex(document));
    }

    private void insertFixture(Fixture fixture) {
        insertFixture(fixture, registration(), true);
    }

    private void insertFixture(
            Fixture fixture,
            ActivationRegistration activationRegistration,
            boolean includeWinningMaterial) {
        ProductionActivationLedger ledger =
                registerActivationAndReserveCase(activationRegistration);

        insertCaseEpochAndOriginalCommand(fixture);
        insertAgentRunAttemptsAndManifest(fixture);
        CommandAdmissionResult rootAdmission =
                ledger.admitCommand(admission(fixture.rootEnvelope()));
        CommandAdmissionResult winningAdmission =
                ledger.admitCommand(admission(fixture.winningEnvelope()));
        insertMaterial(rootAdmission.admissionId(), fixture.rootEnvelope(), fixture.rootMaterial());
        if (includeWinningMaterial) {
            insertMaterial(
                    winningAdmission.admissionId(),
                    fixture.winningEnvelope(),
                    fixture.winningMaterial());
        }
        insertTargetReceipt(fixture);
        ledger.completeCommand(
                new CommandCompletion(
                        winningAdmission.admissionId(),
                        ACTIVATION_ID,
                        WINNING_COMMAND_ID,
                        fixture.winningEnvelope().commandHash(),
                        fixture.winningEnvelope().commandEnvelopeHash(),
                        fixture.targetReceipt().receiptHash()));
        insertFormalProjection(fixture);
    }

    private void insertLegacyFixture(Fixture fixture) {
        registerActivationAndReserveCase(registration());
        insertCaseEpochAndOriginalCommand(fixture);
        insertFormalProjection(fixture);
    }

    private ProductionActivationLedger registerActivationAndReserveCase(
            ActivationRegistration activationRegistration) {
        ProductionActivationLedger ledger =
                new ProductionActivationLedger(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(ledger.registerOrAttach(activationRegistration).disposition())
                .isEqualTo(RegistrationDisposition.REGISTERED);
        assertThat(
                        ledger.transition(
                                ACTIVATION_ID,
                                ActivationLifecycle.REGISTERED,
                                ActivationLifecycle.ACTIVE))
                .isEqualTo(ActivationLifecycle.ACTIVE);
        assertThat(ledger.reserveCase(ACTIVATION_ID, CASE_ID).caseId()).isEqualTo(CASE_ID);
        return ledger;
    }

    private ActivationRegistration registration() {
        return registration(new BuildBindings(CASE_BUILD_ID, CONTROL_BUILD_ID, AGENT_BUILD_ID));
    }

    private ActivationRegistration registration(BuildBindings buildBindings) {
        return new ActivationRegistration(
                ACTIVATION_ID,
                ACTIVATION_HASH,
                ACTIVATION_ENVIRONMENT_ID,
                1,
                "1".repeat(40),
                ACTIVATION_NONCE,
                TENANT,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3_600),
                new SyntheticCaseScope(
                        "2".repeat(64),
                        CASE_PREFIX,
                        1,
                        "fixture-projection-recovery",
                        "3".repeat(64),
                        "3".repeat(64)),
                List.of("INTAKE"),
                buildBindings,
                new GraphBinding(
                        GRAPH_KEY,
                        GRAPH_VERSION,
                        CHECKPOINT_SCHEMA,
                        GRAPH_BINDING_HASH,
                        GRAPH_CODE_BUILD_ID),
                new ImageDigests(
                        "sha256:" + "1".repeat(64),
                        "sha256:" + "2".repeat(64),
                        "sha256:" + "3".repeat(64),
                        "sha256:" + "4".repeat(64),
                        "sha256:" + "5".repeat(64)),
                "temporal-projection-recovery",
                new DatabaseBinding(
                        "domain-cluster-projection",
                        "domain-database-projection",
                        "domain-runtime-projection",
                        DOMAIN_BINDING_HASH),
                new DatabaseBinding(
                        "graph-cluster-projection",
                        "graph-database-projection",
                        "graph-runtime-projection",
                        "7".repeat(64)),
                "8".repeat(64));
    }

    private CommandAdmission admission(ProductionGraphCommandEnvelope envelope) {
        return new CommandAdmission(
                ACTIVATION_ID,
                ACTIVATION_HASH,
                DOMAIN_BINDING_HASH,
                TENANT,
                CASE_ID,
                envelope.command().commandId(),
                envelope.commandHash(),
                envelope.commandEnvelopeHash(),
                ROOM_EPOCH,
                FENCING_TOKEN);
    }

    private void insertCaseEpochAndOriginalCommand(Fixture fixture) {
        OffsetDateTime now = at(NOW);
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, 'user-projection', 'merchant-projection', ?, 'DISPUTE',
                    'INTAKE_IN_PROGRESS', 'USER', 'user-projection', 'MERCHANT',
                    'merchant-projection', 'LOW', 'Recovered winner projection',
                    'Machine-only recovered winning-attempt authority fixture.',
                    'INTAKE', 'test', 'test')
                """,
                CASE_ROW_ID,
                "create-" + CASE_ID);
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values (?, ?, 'INTAKE', 'OPEN', ?, 'test', 'test')
                """,
                ROOM_ID,
                CASE_ID,
                now);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, writer_activation_status, process_revision, room_epoch,
                    fencing_token, last_command_sequence, last_case_event_sequence,
                    temporal_workflow_id, temporal_run_id, temporal_build_id,
                    projected_at, updated_at
                ) values (?, ?, 'INTAKE_ACTIVE', 'INTAKE', 'WAITING_PARTY',
                    'TEMPORAL', 'READY', 1, 0, 1, 0, 0, ?, ?, ?, ?, ?)
                """,
                CASE_ID,
                TENANT,
                WORKFLOW_ID,
                CASE_RUN_ID,
                CASE_BUILD_ID,
                now,
                now);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, provisioning_status, process_revision,
                    room_revision, fencing_token, temporal_workflow_id, temporal_run_id,
                    room_temporal_workflow_id, room_temporal_run_id, temporal_build_id,
                    graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    room_workflow_type, room_workflow_build_id, activated_at, provisioned_at,
                    created_at, updated_at
                ) values (?, ?, ?, ?, 'INTAKE', 0, 'TEMPORAL', 'ACTIVE', 'READY',
                    1, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, 'agent-stream.v2',
                    'room-epoch-selection.v2', 'case-process-contract.v1',
                    'CaseProcessWorkflow', 'IntakeRoomWorkflow', ?, ?, ?, ?, ?)
                """,
                EPOCH_ID,
                TENANT,
                CASE_ID,
                ROOM_ID,
                WORKFLOW_ID,
                CASE_RUN_ID,
                ROOM_WORKFLOW_ID,
                ROOM_RUN_ID,
                CASE_BUILD_ID,
                GRAPH_KEY,
                GRAPH_VERSION,
                CHECKPOINT_SCHEMA,
                CONTROL_BUILD_ID,
                now,
                now,
                now,
                now);
        jdbc.update(
                """
                insert into production_runtime_room_epoch_binding (
                    epoch_id, activation_id, activation_manifest_hash, execution_lane,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id,
                    room_type, room_epoch, room_fencing_token, bound_at
                ) values (?, ?, ?, 'PRODUCTION', ?, ?, ?, 'INTAKE', 0, 1, ?)
                """,
                EPOCH_ID,
                ACTIVATION_ID,
                ACTIVATION_HASH,
                DOMAIN_BINDING_HASH,
                TENANT,
                CASE_ID,
                now);
        jdbc.update(
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id, case_command_sequence,
                    command_type, room_type, room_epoch, actor_id, actor_role,
                    actor_scopes_json, payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision, occurred_at, deadline_at,
                    traceparent, request_hash, command_status, accepted_at, orchestrated_at,
                    created_at, updated_at
                ) values (?, ?, ?, ?, 1, 'INTAKE_MESSAGE', 'INTAKE', 0,
                    'user-projection', 'USER', '["intake:message"]'::jsonb,
                    ?, ?, ?, ?, 1,
                    ?, ?, ?, ?, 'ORCHESTRATION_ACCEPTED', ?, ?, ?, ?)
                """,
                CASE_COMMAND_ROW_ID,
                ORIGINAL_COMMAND_ID,
                TENANT,
                CASE_ID,
                fixture.rootCommand().eventRef().schemaVersion(),
                fixture.rootCommand().eventRef().uri(),
                fixture.rootCommand().eventRef().sha256(),
                fixture.rootCommand().eventRef().sizeBytes(),
                now,
                at(NOW.plusSeconds(1_800)),
                fixture.rootCommand().traceparent(),
                fixture.rootCommand().requestHash(),
                now,
                now,
                now,
                now);
    }

    private void insertAgentRunAttemptsAndManifest(Fixture fixture) {
        OffsetDateTime now = at(NOW);
        jdbc.update(
                """
                insert into immutable_payload_snapshot (
                    id, tenant_surrogate, case_id, room_type, snapshot_type,
                    source_type, source_id, schema_version, object_uri,
                    content_sha256, size_bytes, content_type, visibility,
                    created_at, created_by
                ) values (?, ?, ?, 'INTAKE', 'AGENT_OUTPUT', 'AGENT_RUN', ?,
                    'room-graph-result.v1', ?,
                    ?, 256, 'application/json', 'INTERNAL', ?, 'test')
                """,
                OUTPUT_SNAPSHOT_ID,
                TENANT,
                CASE_ID,
                LOGICAL_RUN_ID,
                OUTPUT_URI,
                RESULT_HASH,
                now);
        jdbc.update(
                """
                insert into agent_run (
                    id, case_id, room_id, agent_id, agent_role, profile_version,
                    prompt_version, skill_version, ruleset_version, model, run_status,
                    input_refs_json, validation_json, risk_flags_json, started_at,
                    trace_id, created_by, stream_operation, stream_endpoint,
                    stream_request_json, stream_request_hash, stream_audience_json,
                    stream_audience_actor_ids_json, stream_idempotency_key,
                    stream_request_id, updated_at, tenant_surrogate, protocol,
                    logical_idempotency_key, executor_kind, finalization_status,
                    room_epoch_id, room_type, room_epoch, process_revision, fencing_token,
                    request_hash, attempt_limit, deadline_at, lineage_schema_version,
                    logical_input_hash
                ) values (?, ?, ?, 'agent-stream:intake', 'SYSTEM', 'runtime',
                    'intake-prompt.v2', 'intake-skill.v2', 'agent-stream.v2', 'synthetic',
                    'RUNNING', '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, ?,
                    'trace-projection-recovery', 'test', 'INTAKE_TURN', 'internal://graph',
                    '{}'::jsonb, ?, '[]'::jsonb, '[]'::jsonb, ?, ?, ?, ?,
                    'agent-stream.v2', ?, 'TEMPORAL_ACTIVITY', 'UNCOMMITTED', ?,
                    'INTAKE', 0, 1, 1, ?, 2, ?, 'agent-run-lineage.v1', ?)
                """,
                LOGICAL_RUN_ID,
                CASE_ID,
                ROOM_ID,
                now,
                fixture.rootCommand().requestHash(),
                LOGICAL_IDEMPOTENCY_KEY,
                LOGICAL_RUN_ID,
                now,
                TENANT,
                LOGICAL_IDEMPOTENCY_KEY,
                EPOCH_ID,
                fixture.rootCommand().requestHash(),
                at(NOW.plusSeconds(1_800)),
                LOGICAL_INPUT_HASH);
        insertAttempt(
                fixture.rootCommand(),
                1,
                "ABORTED",
                null,
                null,
                false,
                0,
                "CREATE_NEXT_ATTEMPT",
                "checkpoint-intake-root");
        insertAttempt(
                fixture.winningCommand(),
                2,
                "COMPLETED",
                RESULT_HASH,
                ROOT_ATTEMPT_ID,
                true,
                1,
                null,
                "checkpoint-intake-winning");
        jdbc.update(
                """
                insert into agent_execution_manifest (
                    id, schema_version, tenant_surrogate, case_id, room_type, room_epoch,
                    process_revision, fencing_token, logical_agent_run_id, attempt_id,
                    workflow_id, workflow_run_id, workflow_type, workflow_build_id,
                    graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                    prompt_version, model_profile_id, provider, model_version,
                    policy_version, guardrail_version, manifest_uri, manifest_sha256,
                    input_snapshot_refs_json, output_snapshot_id, output_sha256,
                    traceparent, terminal_status, finalized_at, created_at
                ) values (?, 'agent-execution-manifest.v1', ?, ?, 'INTAKE', 0, 1, 1,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'synthetic', 'synthetic-v1', ?, ?, ?, ?, '[]'::jsonb,
                    ?, ?, ?, 'COMPLETED', ?, ?)
                """,
                MANIFEST_ID,
                TENANT,
                CASE_ID,
                LOGICAL_RUN_ID,
                WINNING_ATTEMPT_ID,
                AGENT_RUN_WORKFLOW_ID,
                AGENT_RUN_WORKFLOW_RUN_ID,
                AgentRunWorkflow.WORKFLOW_TYPE,
                AGENT_BUILD_ID,
                GRAPH_KEY,
                GRAPH_VERSION,
                CHECKPOINT_SCHEMA,
                "checkpoint-intake-winning",
                fixture.winningCommand().invocationContext().promptProfileId(),
                fixture.winningCommand().invocationContext().modelProfileId(),
                fixture.winningCommand().invocationContext().policyVersion(),
                fixture.winningCommand().invocationContext().guardrailVersion(),
                MANIFEST_URI,
                fixture.manifestHash(),
                OUTPUT_SNAPSHOT_ID,
                RESULT_HASH,
                fixture.winningCommand().traceparent(),
                now,
                now);
        int updated =
                jdbc.update(
                        """
                        update agent_run
                           set run_status = 'COMPLETED',
                               result_ready_attempt_id = ?,
                               committed_attempt_id = ?,
                               final_result_hash = ?,
                               committed_manifest_id = ?,
                               committed_manifest_hash = ?,
                               final_stream_sequence_no = 1,
                               finalized_at = ?,
                               finalization_status = 'COMMITTED',
                               updated_at = ?
                         where id = ?
                           and finalization_status = 'UNCOMMITTED'
                        """,
                        WINNING_ATTEMPT_ID,
                        WINNING_ATTEMPT_ID,
                        RESULT_HASH,
                        MANIFEST_ID,
                        fixture.manifestHash(),
                        now,
                        now,
                        LOGICAL_RUN_ID);
        assertThat(updated).isEqualTo(1);
    }

    private void insertAttempt(
            RoomGraphCommand command,
            long attemptNo,
            String status,
            String resultHash,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset,
            String terminationCode,
            String checkpointId) {
        String resultJson =
                resultHash == null ? "{}" : "{\"result_hash\":\"" + resultHash + "\"}";
        jdbc.update(
                """
                insert into agent_run_attempt (
                    id, agent_run_id, attempt_no, attempt_status, executor_kind, provider,
                    model_profile_id, model_version, graph_key, graph_version,
                    checkpoint_schema_version, checkpoint_id, prompt_version,
                    output_schema_version, policy_version, guardrail_version, request_hash,
                    result_hash, result_json, input_tokens, output_tokens, total_tokens,
                    latency_ms, public_output_emitted, final_frame_observed, last_sequence_no,
                    started_at, completed_at, created_at, updated_at, created_by,
                    lineage_schema_version, command_id, command_request_hash,
                    logical_input_hash, command_json, previous_attempt_id, reset_required,
                    public_sequence_offset, termination_code, error_code, error_retryable
                ) values (?, ?, ?, ?, 'TEMPORAL_ACTIVITY', 'synthetic', ?, 'synthetic-v1',
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), 10, 5, 15, 1,
                    ?, true, ?, ?, ?, ?, ?, 'test', 'agent-run-attempt-lineage.v1',
                    ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                """,
                command.attemptId(),
                LOGICAL_RUN_ID,
                attemptNo,
                status,
                command.invocationContext().modelProfileId(),
                GRAPH_KEY,
                GRAPH_VERSION,
                CHECKPOINT_SCHEMA,
                checkpointId,
                command.invocationContext().promptProfileId(),
                command.invocationContext().outputSchemaVersion(),
                command.invocationContext().policyVersion(),
                command.invocationContext().guardrailVersion(),
                command.requestHash(),
                resultHash,
                resultJson,
                resultHash != null,
                resultHash == null ? 0 : 1,
                at(NOW),
                at(NOW),
                at(NOW),
                at(NOW),
                command.commandId(),
                command.requestHash(),
                LOGICAL_INPUT_HASH,
                ContractJson.canonicalString(objectMapper.valueToTree(command)),
                previousAttemptId,
                resetRequired,
                publicSequenceOffset,
                terminationCode,
                resultHash == null ? ROOT_RETRYABLE_ERROR_CODE : null,
                resultHash == null);
    }

    private void insertMaterial(
            String admissionId,
            ProductionGraphCommandEnvelope envelope,
            Material material) {
        jdbc.update(
                """
                insert into production_runtime_intake_command_material (
                    admission_id, activation_id, activation_manifest_hash, execution_lane,
                    isolated_domain_db_binding_hash, tenant_surrogate, case_id, command_id,
                    command_hash, command_envelope_hash, room_type, room_epoch,
                    room_fencing_token, material_schema_version, context_schema_version,
                    context_canonical_json, context_sha256, stored_at
                ) values (?, ?, ?, 'PRODUCTION', ?, ?, ?, ?, ?, ?, 'INTAKE',
                    0, 1, 'production-runtime-intake-command-material.v1',
                    'intake-command-execution-context.v2', ?, ?, ?)
                """,
                admissionId,
                ACTIVATION_ID,
                ACTIVATION_HASH,
                DOMAIN_BINDING_HASH,
                TENANT,
                CASE_ID,
                envelope.command().commandId(),
                envelope.commandHash(),
                envelope.commandEnvelopeHash(),
                material.canonicalJson(),
                material.sha256(),
                at(NOW));
    }

    private void insertTargetReceipt(Fixture fixture) {
        ProductionFinalizationReceipt receipt = fixture.targetReceipt();
        jdbc.update(
                """
                insert into production_runtime_finalization_receipt (
                    receipt_id, schema_version, execution_lane, activation_id,
                    activation_manifest_hash, tenant_surrogate, case_id, room_type,
                    room_epoch, room_fencing_token, process_revision, stage_sequence,
                    logical_run_id, attempt_id, command_hash, command_envelope_hash,
                    graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                    result_hash, proposal_hash, result_envelope_hash, agent_run_manifest_id,
                    agent_run_manifest_hash, isolated_domain_db_binding_hash, committed_at,
                    receipt_hash, receipt_canonical_bytes, formal_writer,
                    domain_commit_status, recorded_at
                ) values (?, ?, ?, ?, ?, ?, ?, 'INTAKE', 0, 1, 1, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'JAVA_FINALIZER_ONLY',
                    'COMMITTED', ?)
                """,
                TARGET_RECEIPT_ID,
                receipt.schemaVersion(),
                receipt.executionLane(),
                receipt.activationId(),
                ACTIVATION_HASH,
                receipt.tenantSurrogate(),
                receipt.caseId(),
                receipt.stageSequence(),
                receipt.logicalRunId(),
                receipt.attemptId(),
                receipt.commandHash(),
                receipt.commandEnvelopeHash(),
                receipt.graphKey(),
                receipt.graphVersion(),
                receipt.checkpointSchemaVersion(),
                receipt.checkpointId(),
                receipt.resultHash(),
                receipt.proposalHash(),
                receipt.resultEnvelopeHash(),
                receipt.agentRunManifestId(),
                receipt.agentRunManifestHash(),
                receipt.isolatedDomainDbBindingHash(),
                at(receipt.committedAt()),
                receipt.receiptHash(),
                ProductionFinalizationReceiptCodec.canonicalBytes(receipt),
                at(NOW));
    }

    private void insertFormalProjection(Fixture fixture) {
        String resultUri = "urn:intake:finalization-receipt:" + FORMAL_EVENT_ID;
        jdbc.update(
                """
                insert into domain_operation (
                    id, operation_key, tenant_surrogate, case_id, case_command_id,
                    operation_type, room_type, room_epoch, process_revision, fencing_token,
                    request_hash, operation_status, result_uri, result_sha256,
                    started_at, completed_at, created_at, updated_at
                ) values (?, ?, ?, ?, null, 'INTAKE_TURN_FINALIZE',
                    'INTAKE', 0, 1, 1, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?)
                """,
                FORMAL_OPERATION_ID,
                fixture.operationKey(),
                TENANT,
                CASE_ID,
                fixture.formalReceipt().commandId().equals(fixture.rootCommand().commandId())
                        ? fixture.rootCommand().requestHash()
                        : fixture.winningCommand().requestHash(),
                resultUri,
                fixture.formalReceipt().receiptHash(),
                at(NOW),
                at(NOW),
                at(NOW),
                at(NOW));
        jdbc.update(
                """
                insert into case_timeline_event (
                    id, case_id, dossier_id, sequence_no, room_id, event_type, event_time,
                    source_refs_json, event_json, audience_json, audience_actor_ids_json,
                    event_key, created_at, created_by
                ) values (?, ?, null, 1, ?, 'TURN_READY_TO_CONFIRM', ?, '[]'::jsonb,
                    cast(? as jsonb), '["PARTIES"]'::jsonb,
                    '["user-projection","merchant-projection"]'::jsonb, ?, ?, 'test')
                """,
                FORMAL_EVENT_ID,
                CASE_ID,
                ROOM_ID,
                at(NOW),
                fixture.formalEventJson(),
                "intake-formal:" + FORMAL_EVENT_ID,
                at(NOW));
    }

    private void bindDistinctIngressAndRetryableFailedRoot(Fixture fixture) {
        assertThat(CASE_INGRESS_REQUEST_HASH).isNotEqualTo(fixture.rootCommand().requestHash());
        assertThat(
                        jdbc.update(
                                "update case_command set request_hash = ? where id = ?",
                                CASE_INGRESS_REQUEST_HASH,
                                CASE_COMMAND_ROW_ID))
                .isEqualTo(1);
        assertThat(
                        jdbc.update(
                                """
                                update agent_run_attempt
                                   set attempt_status = 'FAILED',
                                       error_code = ?,
                                       error_retryable = true,
                                       public_output_emitted = false,
                                       final_frame_observed = false,
                                       last_sequence_no = 0,
                                       termination_code = 'CREATE_NEXT_ATTEMPT'
                                 where id = ?
                                """,
                                ROOT_RETRYABLE_ERROR_CODE,
                                ROOT_ATTEMPT_ID))
                .isEqualTo(1);
    }

    private void assertRejectedPredecessorAuthorityDrift(
            String status,
            String errorCode,
            boolean retryable,
            String terminationCode,
            boolean completed)
            throws Exception {
        Fixture fixture = fixture();
        insertFixture(fixture);
        bindDistinctIngressAndRetryableFailedRoot(fixture);
        assertThat(
                        jdbc.update(
                                """
                                update agent_run_attempt
                                   set attempt_status = ?,
                                       error_code = ?,
                                       error_retryable = ?,
                                       termination_code = ?,
                                       completed_at = case when ? then completed_at else null end
                                 where id = ?
                                """,
                                status,
                                errorCode,
                                retryable,
                                terminationCode,
                                completed,
                                ROOT_ATTEMPT_ID))
                .isEqualTo(1);

        assertRejectedWithoutProjectionMutation(
                fixture, "INTAKE_PROJECTION_RECOVERED_AUTHORITY_INVALID");
    }

    private void assertRecoveredWinnerPreconditions(Fixture fixture, String rootStatus) {
        assertThat(ORIGINAL_COMMAND_ID).isNotEqualTo(WINNING_COMMAND_ID);
        assertThat(fixture.targetReceipt().proposalHash())
                .isNotEqualTo(fixture.formalReceipt().proposalHash());
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*)
                                  from agent_run run
                                  join agent_run_attempt winner
                                    on winner.agent_run_id = run.id
                                   and winner.id = run.committed_attempt_id
                                  join agent_run_attempt root
                                    on root.agent_run_id = run.id
                                   and root.id = winner.previous_attempt_id
                                 where run.id = ?
                                   and run.lineage_schema_version = 'agent-run-lineage.v1'
                                   and run.protocol = 'agent-stream.v2'
                                   and run.executor_kind = 'TEMPORAL_ACTIVITY'
                                   and run.finalization_status = 'COMMITTED'
                                   and winner.attempt_no = 2
                                   and winner.attempt_status = 'COMPLETED'
                                   and winner.command_id = ?
                                   and root.attempt_no = 1
                                   and root.attempt_status = ?
                                   and root.command_id = ?
                                   and root.previous_attempt_id is null
                                   and root.termination_code = 'CREATE_NEXT_ATTEMPT'
                                """,
                                Long.class,
                                LOGICAL_RUN_ID,
                                WINNING_COMMAND_ID,
                                rootStatus,
                                ORIGINAL_COMMAND_ID))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*)
                                  from production_runtime_command_admission admission
                                  join production_runtime_intake_command_material material
                                    on material.admission_id = admission.admission_id
                                 where admission.activation_id = ?
                                   and admission.case_id = ?
                                   and admission.command_id in (?, ?)
                                   and material.context_sha256 in (?, ?)
                                """,
                                Long.class,
                                ACTIVATION_ID,
                                CASE_ID,
                                ORIGINAL_COMMAND_ID,
                                WINNING_COMMAND_ID,
                                fixture.rootMaterial().sha256(),
                                fixture.winningMaterial().sha256()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*)
                                  from production_runtime_command_completion completion
                                  join production_runtime_command_admission admission
                                    on admission.admission_id = completion.admission_id
                                 where admission.command_id = ?
                                   and completion.completion_hash = ?
                                """,
                                Long.class,
                                WINNING_COMMAND_ID,
                                fixture.targetReceipt().receiptHash()))
                .isEqualTo(1);
        byte[] receiptBytes =
                jdbc.queryForObject(
                        "select receipt_canonical_bytes from production_runtime_finalization_receipt where receipt_id = ?",
                        byte[].class,
                        TARGET_RECEIPT_ID);
        assertThat(ProductionFinalizationReceiptCodec.decodeCanonical(receiptBytes))
                .isEqualTo(fixture.targetReceipt());
        assertThat(
                        ProductionFinalizationReceiptCodec.requireManifestHash(
                                fixture.manifest(), fixture.manifestHash()))
                .isEqualTo(fixture.manifestHash());
        fixture.formalReceipt().requireCanonicalHash();
        assertThat(
                        jdbc.queryForObject(
                                """
                                select event_json -> 'receipt' ->> 'command_id'
                                  from case_timeline_event
                                 where id = ?
                                """,
                                String.class,
                                FORMAL_EVENT_ID))
                .isEqualTo(WINNING_COMMAND_ID);
        assertThat(text("case_command", "command_id", CASE_COMMAND_ROW_ID))
                .isEqualTo(ORIGINAL_COMMAND_ID);
        assertThat(countProjectionOperations()).isZero();
    }

    private void assertDistinctIngressAndRecoveredRootAuthority(Fixture fixture) {
        assertThat(
                        jdbc.queryForObject(
                                "select request_hash from case_command where id = ?",
                                String.class,
                                CASE_COMMAND_ROW_ID))
                .isEqualTo(CASE_INGRESS_REQUEST_HASH);
        assertThat(
                        jdbc.queryForObject(
                                "select request_hash from agent_run where id = ?",
                                String.class,
                                LOGICAL_RUN_ID))
                .isEqualTo(fixture.rootCommand().requestHash());
        assertThat(
                        jdbc.queryForObject(
                                "select request_hash from agent_run_attempt where id = ?",
                                String.class,
                                ROOT_ATTEMPT_ID))
                .isEqualTo(fixture.rootCommand().requestHash());
        assertThat(
                        jdbc.queryForObject(
                                "select command_request_hash from agent_run_attempt where id = ?",
                                String.class,
                                ROOT_ATTEMPT_ID))
                .isEqualTo(fixture.rootCommand().requestHash());
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*)
                                  from agent_run_attempt
                                 where id = ?
                                   and attempt_status = 'FAILED'
                                   and error_code = ?
                                   and error_retryable
                                   and termination_code = 'CREATE_NEXT_ATTEMPT'
                                   and not public_output_emitted
                                   and not final_frame_observed
                                   and last_sequence_no = 0
                                """,
                                Long.class,
                                ROOT_ATTEMPT_ID,
                                ROOT_RETRYABLE_ERROR_CODE))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*)
                                  from agent_run_attempt
                                 where agent_run_id = ?
                                   and attempt_status = 'COMPLETED'
                                   and id = ?
                                   and result_hash = ?
                                """,
                                Long.class,
                                LOGICAL_RUN_ID,
                                WINNING_ATTEMPT_ID,
                                RESULT_HASH))
                .isEqualTo(1);
        assertThat(countFormalOperations()).isEqualTo(1);
        assertThat(countFormalEvents()).isEqualTo(1);
    }

    private long countFormalOperations() {
        return jdbc.queryForObject(
                """
                select count(*)
                  from domain_operation
                 where case_id = ?
                   and operation_type = 'INTAKE_TURN_FINALIZE'
                   and operation_status = 'COMPLETED'
                """,
                Long.class,
                CASE_ID);
    }

    private long countFormalEvents() {
        return jdbc.queryForObject(
                """
                select count(*)
                  from case_timeline_event
                 where case_id = ?
                   and id = ?
                   and event_json ->> 'message_id' = ?
                """,
                Long.class,
                CASE_ID,
                FORMAL_EVENT_ID,
                FORMAL_MESSAGE_ID);
    }

    private void assertRejectedWithoutProjectionMutation(
            Fixture fixture, String expectedReasonCode) {
        ProjectionWriteRejectedException failure =
                assertThrows(
                        ProjectionWriteRejectedException.class,
                        () -> service.completeConsumedEvent(fixture.projectionCommand()));
        assertThat(failure.reasonCode()).isEqualTo(expectedReasonCode);
        assertNoProjectionMutation();
    }

    private void assertProjectionAppliedOnce() {
        ProjectionState state = projectionState();
        assertThat(state.projectionProcessRevision()).isEqualTo(PROCESS_REVISION + 1);
        assertThat(state.epochProcessRevision()).isEqualTo(PROCESS_REVISION + 1);
        assertThat(state.epochRoomRevision()).isEqualTo(ROOM_REVISION + 1);
        assertThat(state.commandStatus()).isEqualTo("APPLIED");
        assertThat(state.operationCount()).isEqualTo(1);
    }

    private ProjectionState projectionState() {
        return new ProjectionState(
                jdbc.queryForObject(
                        "select process_revision from case_process_projection where case_id = ?",
                        Long.class,
                        CASE_ID),
                jdbc.queryForObject(
                        "select process_revision from case_room_epoch where id = ?",
                        Long.class,
                        EPOCH_ID),
                jdbc.queryForObject(
                        "select room_revision from case_room_epoch where id = ?",
                        Long.class,
                        EPOCH_ID),
                jdbc.queryForObject(
                        "select version from case_command where id = ?",
                        Long.class,
                        CASE_COMMAND_ROW_ID),
                text("case_command", "command_status", CASE_COMMAND_ROW_ID),
                countProjectionOperations());
    }

    private void assertNoProjectionMutation() {
        assertThat(text("case_command", "command_status", CASE_COMMAND_ROW_ID))
                .isEqualTo("ORCHESTRATION_ACCEPTED");
        assertThat(
                        jdbc.queryForObject(
                                "select process_revision from case_process_projection where case_id = ?",
                                Long.class,
                                CASE_ID))
                .isEqualTo(PROCESS_REVISION);
        assertThat(
                        jdbc.queryForObject(
                                "select process_revision from case_room_epoch where id = ?",
                                Long.class,
                                EPOCH_ID))
                .isEqualTo(PROCESS_REVISION);
        assertThat(
                        jdbc.queryForObject(
                                "select room_revision from case_room_epoch where id = ?",
                                Long.class,
                                EPOCH_ID))
                .isEqualTo(ROOM_REVISION);
        assertThat(countProjectionOperations()).isZero();
    }

    private void assertPersistedTypedContextCanonicalAuthority(String commandId)
            throws Exception {
        PersistedContextMaterial persisted =
                jdbc.queryForObject(
                        "select context_canonical_json, context_sha256 "
                                + "from production_runtime_intake_command_material where command_id = ?",
                        (result, ignored) ->
                                new PersistedContextMaterial(
                                        result.getString("context_canonical_json"),
                                        result.getString("context_sha256")),
                        commandId);
        assertThat(persisted).isNotNull();

        ObjectMapper materialMapper =
                objectMapper
                        .copy()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        JsonNode persistedTree = materialMapper.readTree(persisted.canonicalJson());
        IntakeCommandExecutionContext typed =
                materialMapper.treeToValue(
                        persistedTree, IntakeCommandExecutionContext.class);
        JsonNode typedRoundTripTree = materialMapper.valueToTree(typed);

        // Canonical contract equality is stable even when JsonNode.equals observes different
        // integral node implementations after PostgreSQL text is parsed and typed again.
        assertThat(ContractJson.canonicalString(persistedTree))
                .isEqualTo(persisted.canonicalJson());
        assertThat(ContractJson.sha256Hex(persistedTree)).isEqualTo(persisted.sha256());
        assertThat(ContractJson.canonicalString(typedRoundTripTree))
                .isEqualTo(persisted.canonicalJson());
        assertThat(ContractJson.sha256Hex(typedRoundTripTree))
                .isEqualTo(persisted.sha256());
    }

    private void assertPersistedTypedCommandCanonicalAuthority(
            String attemptId, RoomGraphCommand immutableRequestCommand) throws Exception {
        String persisted =
                jdbc.queryForObject(
                        "select command_json::text from agent_run_attempt where id = ?",
                        String.class,
                        attemptId);
        JsonNode persistedDocument = objectMapper.readTree(persisted);
        RoomGraphCommand decodedCommand =
                objectMapper.treeToValue(persistedDocument, RoomGraphCommand.class);
        JsonNode typedRoundTrip = objectMapper.valueToTree(decodedCommand);

        // Canonical contract equality is stable even when PostgreSQL JSONB parsing and typed
        // serialization choose different integral JsonNode implementations.
        assertThat(ContractJson.canonicalString(typedRoundTrip))
                .isEqualTo(ContractJson.canonicalString(persistedDocument));
        assertThat(ContractJson.sha256Hex(typedRoundTrip))
                .isEqualTo(ContractJson.sha256Hex(persistedDocument));
        assertThat(decodedCommand).isEqualTo(immutableRequestCommand);
    }

    private void assertPersistedTypedFormalReceiptCanonicalAuthority(Fixture fixture)
            throws Exception {
        String persisted =
                jdbc.queryForObject(
                        "select (event_json -> 'receipt')::text "
                                + "from case_timeline_event where id = ?",
                        String.class,
                        FORMAL_EVENT_ID);
        JsonNode persistedDocument = objectMapper.readTree(persisted);
        IntakeFinalizationReceipt decodedReceipt =
                objectMapper.treeToValue(
                        persistedDocument, IntakeFinalizationReceipt.class);
        JsonNode typedRoundTrip = objectMapper.valueToTree(decodedReceipt);

        assertThat(ContractJson.canonicalString(typedRoundTrip))
                .isEqualTo(ContractJson.canonicalString(persistedDocument));
        assertThat(ContractJson.sha256Hex(typedRoundTrip))
                .isEqualTo(ContractJson.sha256Hex(persistedDocument));
        assertThat(decodedReceipt.commandId())
                .isEqualTo(fixture.winningCommand().commandId());
        assertThat(decodedReceipt.logicalRunId())
                .isEqualTo(fixture.targetReceipt().logicalRunId());
        assertThat(decodedReceipt.attemptId()).isEqualTo(fixture.targetReceipt().attemptId());
        assertThat(decodedReceipt.resultHash()).isEqualTo(fixture.targetReceipt().resultHash());
    }

    private long countProjectionOperations() {
        return jdbc.queryForObject(
                """
                select count(*) from domain_operation
                 where case_id = ? and operation_type = 'APPLY_PROCESS_PROJECTION'
                """,
                Long.class,
                CASE_ID);
    }

    private String text(String table, String column, String id) {
        if (!List.of("case_command").contains(table)
                || !List.of("command_id", "command_status").contains(column)) {
            throw new IllegalArgumentException("unsupported fixture lookup");
        }
        return jdbc.queryForObject(
                "select " + column + " from " + table + " where id = ?", String.class, id);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Material(
            IntakeCommandExecutionContext context, String canonicalJson, String sha256) {}

    private record PersistedContextMaterial(String canonicalJson, String sha256) {}

    private record ProjectionState(
            long projectionProcessRevision,
            long epochProcessRevision,
            long epochRoomRevision,
            long commandVersion,
            String commandStatus,
            long operationCount) {}

    private record Fixture(
            RoomGraphCommand rootCommand,
            RoomGraphCommand winningCommand,
            ProductionGraphCommandEnvelope rootEnvelope,
            ProductionGraphCommandEnvelope winningEnvelope,
            Material rootMaterial,
            Material winningMaterial,
            AgentExecutionManifest manifest,
            String manifestHash,
            ProductionFinalizationReceipt targetReceipt,
            IntakeFinalizationReceipt formalReceipt,
            String formalEventJson,
            String operationKey,
            CompleteConsumedIntakeProjectionCommand projectionCommand) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ProjectionTestConfiguration {

        @Bean
        @Primary
        Clock projectionClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ObjectMapper projectionObjectMapper() {
            return JsonMapper.builder()
                    .findAndAddModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();
        }
    }
}
