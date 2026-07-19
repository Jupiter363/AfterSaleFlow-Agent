package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.time.Instant;
import java.util.Optional;

/** Transactional port for one logical run and its ordered attempts. */
public interface AgentRunLedger {

    LogicalRun createOrLoad(CreateLogicalRun command);

    Optional<LogicalRun> findByLogicalKey(String caseId, String logicalIdempotencyKey);

    /** Allocates the next attempt while holding the logical-run lock. */
    Attempt startNextAttempt(String agentRunId, ExecuteAgentRunRequest request, Instant startedAt);

    void recordHeartbeat(AgentRunAttemptHeartbeat heartbeat);

    void recordResultReady(ExecuteAgentRunResult result);

    void recordAttemptFailure(
            String agentRunId,
            String attemptId,
            long attemptNo,
            AgentRunAttemptStatus status,
            String errorCode,
            boolean retryable,
            Instant completedAt);

    Optional<AgentRunFinalizationReceipt> committedReceipt(String agentRunId);

    record CreateLogicalRun(
            String agentRunId,
            String tenantSurrogate,
            String caseId,
            String roomId,
            String operation,
            String logicalIdempotencyKey,
            AgentRunProtocol protocol,
            AgentRunExecutorKind executorKind,
            String roomEpochId,
            RoomType roomType,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            String requestHash,
            int attemptLimit,
            Instant deadlineAt,
            Instant createdAt) {}

    record LogicalRun(
            String agentRunId,
            String caseId,
            String logicalIdempotencyKey,
            AgentRunProtocol protocol,
            AgentRunExecutorKind executorKind,
            String roomEpochId,
            long roomEpoch,
            long processRevision,
            long fencingToken,
            String status,
            String committedAttemptId,
            String finalResultHash,
            int attemptLimit,
            Instant deadlineAt,
            long version) {}

    record Attempt(
            String attemptId,
            String agentRunId,
            long attemptNo,
            AgentRunAttemptStatus status,
            boolean publicOutputEmitted,
            boolean finalFrameObserved,
            long lastSequenceNo,
            Instant lastHeartbeatAt,
            Instant startedAt,
            Instant completedAt,
            long version) {}
}
