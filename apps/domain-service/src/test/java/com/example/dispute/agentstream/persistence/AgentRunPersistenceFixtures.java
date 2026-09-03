package com.example.dispute.agentstream.persistence;

import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Binding;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Context;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.AgentRunRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.GraphRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ManifestUsage;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ModelRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.WorkflowRef;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentRunPersistenceFixtures {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private static final AgentRunCommandBindingFactory BINDING_FACTORY =
            new AgentRunCommandBindingFactory(MAPPER);
    private static final Context BINDING_CONTEXT = new Context(
            "ROOM_V2_PERSISTENCE",
            "EPOCH_V2_PERSISTENCE",
            "EVIDENCE_ANALYZE",
            "logical-persistence-key");
    private static final Context PARALLEL_INTAKE_BINDING_CONTEXT = new Context(
            "ROOM_V2_PERSISTENCE",
            "EPOCH_V2_PERSISTENCE",
            "INTAKE_MESSAGE",
            "logical-persistence-key");
    public static final Instant STARTED_AT = Instant.parse("2026-07-19T01:00:00Z");
    public static final Instant COMPLETED_AT = Instant.parse("2026-07-19T01:00:03Z");
    public static final String RUN_ID = "RUN_V2_PERSISTENCE";
    public static final String CASE_ID = "CASE_V2_PERSISTENCE";
    static final String REQUEST_HASH = command(1, "ATTEMPT_V2_1").requestHash();
    static final String LOGICAL_INPUT_HASH =
            binding(command(1, "ATTEMPT_V2_1")).logicalInputHash();
    static final String RESULT_HASH = "b".repeat(64);
    static final String MANIFEST_HASH = "c".repeat(64);

    private AgentRunPersistenceFixtures() {}

    public static CreateLogicalRun logicalRun() {
        return logicalRun("ATTEMPT_V2_1");
    }

    public static CreateLogicalRun logicalRun(String firstAttemptId) {
        RoomGraphCommand firstCommand = command(1, firstAttemptId);
        return new CreateLogicalRun(
                RUN_ID,
                "tenant-persistence",
                CASE_ID,
                "ROOM_V2_PERSISTENCE",
                "EVIDENCE_ANALYZE",
                "logical-persistence-key",
                AgentRunProtocol.V2,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                "EPOCH_V2_PERSISTENCE",
                RoomType.EVIDENCE,
                2,
                7,
                11,
                firstCommand.requestHash(),
                binding(firstCommand).logicalInputHash(),
                3,
                STARTED_AT.plusSeconds(600),
                STARTED_AT);
    }

    public static CreateLogicalRun logicalRunV3() {
        return logicalRunV3("ATTEMPT_V3_1");
    }

    public static CreateLogicalRun logicalRunV3(String firstAttemptId) {
        CreateLogicalRun source = logicalRun(firstAttemptId);
        return new CreateLogicalRun(
                source.agentRunId(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomId(),
                source.operation(),
                source.logicalIdempotencyKey(),
                AgentRunProtocol.V3,
                source.executorKind(),
                source.roomEpochId(),
                source.roomType(),
                source.roomEpoch(),
                source.processRevision(),
                source.fencingToken(),
                source.requestHash(),
                source.logicalInputHash(),
                source.attemptLimit(),
                source.deadlineAt(),
                source.createdAt());
    }

    public static CreateLogicalRun logicalRunV4() {
        RoomGraphCommand command = parallelIntakeCommand();
        Binding binding = BINDING_FACTORY.bind(PARALLEL_INTAKE_BINDING_CONTEXT, command);
        return new CreateLogicalRun(
                RUN_ID,
                "tenant-persistence",
                CASE_ID,
                "ROOM_V2_PERSISTENCE",
                "INTAKE_MESSAGE",
                "logical-persistence-key",
                AgentRunProtocol.V4,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                "EPOCH_V2_PERSISTENCE",
                RoomType.INTAKE,
                2,
                7,
                11,
                command.requestHash(),
                binding.logicalInputHash(),
                1,
                STARTED_AT.plusSeconds(600),
                STARTED_AT);
    }

    public static ExecuteAgentRunRequest request(long attemptNo, String attemptId) {
        return request(
                attemptNo,
                attemptId,
                attemptNo == 1 ? null : "ATTEMPT_V2_1",
                false);
    }

    public static ExecuteAgentRunRequest request(
            long attemptNo,
            String attemptId,
            String previousAttemptId,
            boolean resetRequired) {
        RoomGraphCommand command = command(attemptNo, attemptId);
        Binding binding = binding(command);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                RUN_ID,
                attemptNo,
                AgentRunProtocol.V2.wireValue(),
                binding.logicalInputHash(),
                previousAttemptId,
                resetRequired,
                resetRequired ? 1 : 0,
                command);
    }

    public static ExecuteAgentRunRequest requestV3(long attemptNo, String attemptId) {
        return requestV3(
                attemptNo,
                attemptId,
                attemptNo == 1 ? null : "ATTEMPT_V3_1",
                false);
    }

    public static ExecuteAgentRunRequest requestV3(
            long attemptNo,
            String attemptId,
            String previousAttemptId,
            boolean resetRequired) {
        RoomGraphCommand command = command(attemptNo, attemptId);
        Binding binding = binding(command);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                RUN_ID,
                attemptNo,
                AgentRunProtocol.V3.wireValue(),
                binding.logicalInputHash(),
                previousAttemptId,
                resetRequired,
                resetRequired ? 1 : 0,
                command);
    }

    public static ExecuteAgentRunRequest parallelIntakeRequest() {
        RoomGraphCommand command = parallelIntakeCommand();
        Binding binding = BINDING_FACTORY.bind(PARALLEL_INTAKE_BINDING_CONTEXT, command);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                RUN_ID,
                1,
                1,
                AgentRunProtocol.V4.wireValue(),
                binding.logicalInputHash(),
                null,
                false,
                0,
                command);
    }

    public static AttemptAllocation allocation(long attemptNo, String attemptId) {
        RoomGraphCommand command = command(attemptNo, attemptId);
        return new AttemptAllocation(attemptNo, command, binding(command));
    }

    public static AttemptAllocation allocationWithRetryBudget(
            long attemptNo,
            String attemptId,
            int providerAttempts,
            int activityAttempts,
            int repairs) {
        RoomGraphCommand command = command(
                attemptNo,
                attemptId,
                new RoomGraphCommand.RetryBudget(
                        providerAttempts, activityAttempts, repairs));
        return new AttemptAllocation(attemptNo, command, binding(command));
    }

    public static AttemptAllocation parallelIntakeAllocation() {
        RoomGraphCommand command = parallelIntakeCommand();
        return new AttemptAllocation(
                1,
                command,
                BINDING_FACTORY.bind(PARALLEL_INTAKE_BINDING_CONTEXT, command));
    }

    private static RoomGraphCommand command(long attemptNo, String attemptId) {
        return command(
                attemptNo,
                attemptId,
                new RoomGraphCommand.RetryBudget(2, 2, 1));
    }

    private static RoomGraphCommand command(
            long attemptNo,
            String attemptId,
            RoomGraphCommand.RetryBudget retryBudget) {
        RoomGraphCommand unsigned =
                new RoomGraphCommand(
                        "room-graph-command.v1",
                        "command-persistence-" + attemptNo,
                        RUN_ID,
                        attemptId,
                        "tenant-persistence",
                        CASE_ID,
                        RoomType.EVIDENCE,
                        2,
                        "evidence.graph",
                        "graph-v2",
                        "checkpoint-v2",
                        "thread-persistence",
                        new RoomGraphCommand.ActorScope(
                                "user-persistence",
                                ActorRole.USER,
                                Audience.USER,
                                List.of("EVIDENCE_READ")),
                        7,
                        "EVIDENCE_REVIEW",
                        4,
                        new RoomGraphCommand.SnapshotRef(
                                "SNAP_INPUT",
                                "domain-snapshot.v1",
                                "s3://snapshots/input",
                                "d".repeat(64),
                                100),
                        null,
                        new RoomGraphCommand.InvocationContext(
                                "agent-profile-v2",
                                "prompt-profile-v2",
                                "model-profile-v2",
                                "room-graph-result.v1",
                                "policy-v2",
                                "guardrail-v2",
                                List.of("evidence.search"),
                                "kms-key-v2",
                                "nonce-v2-" + attemptNo),
                        retryBudget,
                        STARTED_AT.plusSeconds(600),
                        "00-0123456789abcdef0123456789abcdef-%016x-01"
                                .formatted(attemptNo),
                        "0".repeat(64));
        ObjectNode commandJson = MAPPER.valueToTree(unsigned);
        commandJson.remove("request_hash");
        commandJson.put("request_hash", ContractJson.sha256Hex(commandJson));
        try {
            return MAPPER.treeToValue(commandJson, RoomGraphCommand.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("test command encoding failed", exception);
        }
    }

    private static RoomGraphCommand parallelIntakeCommand() {
        RoomGraphCommand unsigned = new RoomGraphCommand(
                "room-graph-command.v1",
                "intake-message:persistence",
                RUN_ID,
                "ATTEMPT_V4_1",
                "tenant-persistence",
                CASE_ID,
                "ROOM_PERSISTENCE_INTAKE",
                RoomType.INTAKE,
                2,
                "all-rooms.target-e2e.v2",
                "target-e2e-graph.2026-08-18.1",
                "target-e2e-checkpoint.v2",
                "thread-persistence",
                new RoomGraphCommand.ActorScope(
                        "user-persistence",
                        ActorRole.USER,
                        Audience.USER,
                        List.of("case:" + CASE_ID + ":command:INTAKE_MESSAGE")),
                7,
                "READY_TO_CONFIRM",
                4,
                new RoomGraphCommand.SnapshotRef(
                        "SNAP_INTAKE",
                        "domain-snapshot.v1",
                        "s3://snapshots/intake",
                        "d".repeat(64),
                        100),
                new RoomGraphCommand.SnapshotRef(
                        "EVENT_INTAKE",
                        "intake-event.v1",
                        "s3://events/intake",
                        "e".repeat(64),
                        100),
                new RoomGraphCommand.InvocationContext(
                        ExecuteAgentRunRequest.PARALLEL_INTAKE_AGENT_PROFILE_ID,
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "model-profile-v2",
                        ExecuteAgentRunRequest.PARALLEL_INTAKE_OUTPUT_SCHEMA,
                        "policy-v2",
                        "guardrail-v2",
                        List.of(),
                        "kms-key-v2",
                        "nonce-v4-1"),
                new RoomGraphCommand.RetryBudget(6, 1, 1),
                STARTED_AT.plusSeconds(600),
                "00-0123456789abcdef0123456789abcdef-0000000000000001-01",
                "0".repeat(64));
        ObjectNode commandJson = MAPPER.valueToTree(unsigned);
        commandJson.remove("request_hash");
        commandJson.put("request_hash", ContractJson.sha256Hex(commandJson));
        try {
            return MAPPER.treeToValue(commandJson, RoomGraphCommand.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("parallel Intake command encoding failed", exception);
        }
    }

    private static Binding binding(RoomGraphCommand command) {
        return BINDING_FACTORY.bind(BINDING_CONTEXT, command);
    }

    static AgentRunAttemptHeartbeat heartbeat(long attemptNo, String attemptId, long sequenceNo) {
        return new AgentRunAttemptHeartbeat(
                AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                RUN_ID,
                attemptId,
                attemptNo,
                sequenceNo,
                true,
                false,
                STARTED_AT.plusSeconds(2));
    }

    static ExecuteAgentRunResult result(long attemptNo, String attemptId) {
        return resultWithExecutionMetadata(
                attemptNo,
                attemptId,
                "model-profile-v2",
                "room-graph-result.v1",
                "policy-v2",
                "guardrail-v2");
    }

    static ExecuteAgentRunResult failureResult(
            long attemptNo,
            String attemptId,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            AgentRunRecoveryAction recoveryAction) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                RUN_ID,
                RUN_ID,
                attemptId,
                attemptNo,
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                lastSequenceNo,
                publicOutputEmitted,
                "PROVIDER_TIMEOUT",
                recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                recoveryAction,
                COMPLETED_AT);
    }

    static ExecuteAgentRunResult resultWithExecutionMetadata(
            long attemptNo,
            String attemptId,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion) {
        RoomGraphResult graphResult =
                new RoomGraphResult(
                        "room-graph-result.v1",
                        "command-persistence-" + attemptNo,
                        RUN_ID,
                        attemptId,
                        "evidence.graph",
                        "graph-v2",
                        "checkpoint-42",
                        8,
                        GraphStatus.COMPLETED,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        RESULT_HASH,
                        new Usage(100, 20, 120),
                        new RoomGraphResult.ExecutionMetadata(
                                "prompt-v2",
                                modelProfileId,
                                outputSchemaVersion,
                                policyVersion,
                                guardrailVersion));
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                RUN_ID,
                RUN_ID,
                attemptId,
                attemptNo,
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                RESULT_HASH,
                3,
                true,
                null,
                false,
                null,
                COMPLETED_AT);
    }

    public static RoomGraphResult parallelIntakeGraphResult() {
        RoomGraphCommand command = parallelIntakeCommand();
        return new RoomGraphResult(
                "room-graph-result.v1",
                command.commandId(),
                RUN_ID,
                command.attemptId(),
                command.graphKey(),
                command.graphVersion(),
                "checkpoint-parallel-intake",
                8,
                GraphStatus.COMPLETED,
                List.of(),
                List.of(),
                null,
                null,
                null,
                RESULT_HASH,
                new Usage(60, 15, 75),
                new RoomGraphResult.ExecutionMetadata(
                        command.invocationContext().promptProfileId(),
                        command.invocationContext().modelProfileId(),
                        command.invocationContext().outputSchemaVersion(),
                        command.invocationContext().policyVersion(),
                        command.invocationContext().guardrailVersion()));
    }

    public static ExecuteAgentRunResult parallelIntakeResult(long lastSequenceNo) {
        RoomGraphResult graphResult = parallelIntakeGraphResult();
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                RUN_ID,
                RUN_ID,
                "ATTEMPT_V4_1",
                1,
                ExecuteAgentRunResult.Outcome.COMPLETED,
                graphResult,
                graphResult.outputHash(),
                lastSequenceNo,
                true,
                null,
                false,
                null,
                COMPLETED_AT);
    }

    static AgentExecutionManifest manifest(String attemptId) {
        return manifestWithModelHashes(
                attemptId, command(1, attemptId).requestHash(), RESULT_HASH);
    }

    static AgentExecutionManifest manifestV3(String attemptId) {
        return manifestWithModelHashes(
                attemptId,
                command(1, attemptId).requestHash(),
                RESULT_HASH,
                AgentRunProtocol.V3.wireValue());
    }

    static AgentExecutionManifest manifestV3(
            String attemptId, String requestHash, String responseHash) {
        return manifestWithModelHashes(
                attemptId, requestHash, responseHash, AgentRunProtocol.V3.wireValue());
    }

    static AgentExecutionManifest manifestWithModelHashes(
            String attemptId, String requestHash, String responseHash) {
        return manifestWithModelHashes(
                attemptId, requestHash, responseHash, AgentRunProtocol.V2.wireValue());
    }

    private static AgentExecutionManifest manifestWithModelHashes(
            String attemptId,
            String requestHash,
            String responseHash,
            String streamProtocol) {
        return new AgentExecutionManifest(
                "agent-execution-manifest.v1",
                "MANIFEST_V2_PERSISTENCE",
                "tenant-persistence",
                CASE_ID,
                2,
                7,
                11,
                new WorkflowRef(
                        "room-evidence-CASE_V2_PERSISTENCE-2",
                        "temporal-run-1",
                        "EvidenceRoomWorkflow",
                        "build-v2"),
                new AgentRunRef(RUN_ID, attemptId, "logical-persistence-key"),
                new GraphRef(
                        "evidence.graph", "graph-v2", "checkpoint-v2", "checkpoint-42", 8),
                new ModelRef(
                        "prompt-v2",
                        "model-profile-v2",
                        "provider-v2",
                        "model-v2",
                        requestHash,
                        responseHash),
                Map.of(
                        "graph_command", "room-graph-command.v1",
                        "graph_result", "room-graph-result.v1",
                        "output_schema", "room-graph-result.v1",
                        "stream", streamProtocol),
                "policy-v2",
                "guardrail-v2",
                List.of("evidence.search.v1"),
                List.of(
                        new ArtifactPointer(
                                "SNAP_INPUT",
                                "domain-snapshot.v1",
                                "s3://snapshots/input",
                                "d".repeat(64))),
                new ArtifactPointer(
                        "SNAP_OUTPUT",
                        "room-output.v1",
                        "s3://snapshots/output",
                        RESULT_HASH),
                new ManifestUsage(100, 20, 120, 3000),
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                COMPLETED_AT);
    }
}
