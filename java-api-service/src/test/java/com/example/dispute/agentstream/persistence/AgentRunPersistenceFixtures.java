package com.example.dispute.agentstream.persistence;

import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.AgentRunRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.GraphRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ManifestUsage;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.ModelRef;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest.WorkflowRef;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class AgentRunPersistenceFixtures {

    static final Instant STARTED_AT = Instant.parse("2026-07-19T01:00:00Z");
    static final Instant COMPLETED_AT = Instant.parse("2026-07-19T01:00:03Z");
    static final String RUN_ID = "RUN_V2_PERSISTENCE";
    static final String CASE_ID = "CASE_V2_PERSISTENCE";
    static final String REQUEST_HASH = "a".repeat(64);
    static final String RESULT_HASH = "b".repeat(64);
    static final String MANIFEST_HASH = "c".repeat(64);

    private AgentRunPersistenceFixtures() {}

    static CreateLogicalRun logicalRun() {
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
                REQUEST_HASH,
                3,
                STARTED_AT.plusSeconds(600),
                STARTED_AT);
    }

    static ExecuteAgentRunRequest request(long attemptNo, String attemptId) {
        RoomGraphCommand command =
                new RoomGraphCommand(
                        "room-graph-command.v1",
                        "command-persistence",
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
                                "nonce-v2"),
                        new RoomGraphCommand.RetryBudget(2, 2, 1),
                        STARTED_AT.plusSeconds(600),
                        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                        REQUEST_HASH);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                RUN_ID,
                attemptNo,
                AgentRunProtocol.V2.wireValue(),
                command);
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
        RoomGraphResult graphResult =
                new RoomGraphResult(
                        "room-graph-result.v1",
                        "command-persistence",
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
                                "model-profile-v2",
                                "room-graph-result.v1",
                                "policy-v2",
                                "guardrail-v2"));
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
                COMPLETED_AT);
    }

    static AgentExecutionManifest manifest(String attemptId) {
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
                        REQUEST_HASH,
                        RESULT_HASH),
                Map.of("room_graph_result", "room-graph-result.v1"),
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
