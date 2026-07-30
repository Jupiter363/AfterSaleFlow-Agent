package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.Optional;

/** Transactional port for one logical run and its ordered attempts. */
public interface AgentRunLedger {

    String LOGICAL_LINEAGE_SCHEMA_VERSION = "agent-run-lineage.v1";
    String ATTEMPT_LINEAGE_SCHEMA_VERSION = "agent-run-attempt-lineage.v1";

    LogicalRun createOrLoad(CreateLogicalRun command);

    Optional<LogicalRun> findByLogicalKey(String caseId, String logicalIdempotencyKey);

    /** Locks the logical run and its latest attempt for one recovery decision. */
    Optional<RecoveryState> lockV2RecoveryState(String agentRunId);

    /** Durably removes an ineligible recovery candidate from the scheduler queue. */
    void terminalizeV2RecoveryCandidate(
            String agentRunId,
            String attemptId,
            long attemptNo,
            String errorCode,
            Instant completedAt);

    /** Allocates the next attempt while holding the logical-run lock. */
    Attempt startNextAttempt(
            String agentRunId, AttemptAllocation allocation, Instant startedAt);

    /** Loads an allocation and rejects any request that differs from its durable lineage. */
    Attempt requireAllocatedAttempt(ExecuteAgentRunRequest request);

    void recordHeartbeat(AgentRunAttemptHeartbeat heartbeat);

    void recordResultReady(ExecuteAgentRunResult result);

    void recordAttemptFailure(
            String agentRunId,
            String attemptId,
            long attemptNo,
            AgentRunAttemptStatus status,
            String errorCode,
            AgentRunRecoveryAction recoveryAction,
            Instant completedAt);

    /** Persists the exact Activity failure result so a lost completion can be replayed verbatim. */
    void recordAttemptFailureResult(
            AgentRunAttemptStatus status, ExecuteAgentRunResult result);

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
            String logicalInputHash,
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
            String lineageSchemaVersion,
            String logicalInputHash,
            int attemptLimit,
            Instant deadlineAt,
            long version) {}

    record AttemptAllocation(
            long attemptNo,
            RoomGraphCommand command,
            AgentRunCommandBindingFactory.Binding binding) {

        public AttemptAllocation {
            if (attemptNo < 1) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            if (command == null) {
                throw new IllegalArgumentException("command must not be null");
            }
            if (binding == null) {
                throw new IllegalArgumentException("binding must not be null");
            }
            if (!command.requestHash().equals(binding.commandRequestHash())) {
                throw new IllegalArgumentException(
                        "command requestHash conflicts with its allocation binding");
            }
        }
    }

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
            long version,
            String lineageSchemaVersion,
            String commandId,
            String commandRequestHash,
            String logicalInputHash,
            String canonicalCommandJson,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset,
            String terminationCode,
            String errorCode,
            ExecuteAgentRunResult durableFailureResult) {

        public Attempt(
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
                long version,
                String lineageSchemaVersion,
                String commandId,
                String commandRequestHash,
                String logicalInputHash,
                String canonicalCommandJson,
                String previousAttemptId,
                boolean resetRequired,
                int publicSequenceOffset,
                String terminationCode) {
            this(
                    attemptId,
                    agentRunId,
                    attemptNo,
                    status,
                    publicOutputEmitted,
                    finalFrameObserved,
                    lastSequenceNo,
                    lastHeartbeatAt,
                    startedAt,
                    completedAt,
                    version,
                    lineageSchemaVersion,
                    commandId,
                    commandRequestHash,
                    logicalInputHash,
                    canonicalCommandJson,
                    previousAttemptId,
                    resetRequired,
                    publicSequenceOffset,
                    terminationCode,
                    null,
                    null);
        }
    }

    record RecoveryState(
            LogicalRun logicalRun,
            Attempt latestAttempt,
            String roomId,
            String operation,
            String logicalIdempotencyKey) {

        public RecoveryState {
            if (logicalRun == null || latestAttempt == null) {
                throw new IllegalArgumentException("recovery state requires a run and latest attempt");
            }
            if (!logicalRun.agentRunId().equals(latestAttempt.agentRunId())) {
                throw new IllegalArgumentException("recovery state crosses logical AgentRuns");
            }
            if (roomId == null
                    || roomId.isBlank()
                    || operation == null
                    || operation.isBlank()
                    || logicalIdempotencyKey == null
                    || logicalIdempotencyKey.isBlank()) {
                throw new IllegalArgumentException("recovery binding context is incomplete");
            }
        }
    }
}
