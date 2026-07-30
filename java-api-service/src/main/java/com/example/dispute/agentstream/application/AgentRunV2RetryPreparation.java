package com.example.dispute.agentstream.application;

import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.RecoveryState;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.time.Instant;

/**
 * Lane-owned transactional preparation for a later AgentRun attempt.
 *
 * <p>Target lanes use this boundary to derive command admission, material, and object-index facts
 * before the Java ledger allocation commits. Recovery never allocates when no exact preparer is
 * installed.
 */
public interface AgentRunV2RetryPreparation {

    boolean supports(RecoveryState state);

    AttemptAllocation prepareNextAttempt(
            RecoveryState state, AgentRunV2NextAttemptFactory factory, Instant preparedAt);

    void persistAllocatedRequest(RecoveryState predecessor, ExecuteAgentRunRequest request);

    void verifyAllocatedRequest(RecoveryState current, ExecuteAgentRunRequest request);
}
