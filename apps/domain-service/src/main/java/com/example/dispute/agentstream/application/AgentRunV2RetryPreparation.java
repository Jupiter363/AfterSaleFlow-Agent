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

    /**
     * Whether detector recovery may replay a running initial attempt for this lane.
     *
     * <p>This is consulted only after recovery has constructed the exact allocated request and
     * {@link #verifyAllocatedRequest(RecoveryState, ExecuteAgentRunRequest)} has verified it.
     * The default preserves the generic allocation-to-start crash-gap recovery behavior. Lanes
     * whose room workflow is the sole owner of the initial attempt can opt out while retaining
     * replay for later, recovery-allocated attempts.
     */
    default boolean mayReplayInitialAttemptFromRecovery(RecoveryState state) {
        return true;
    }

    AttemptAllocation prepareNextAttempt(
            RecoveryState state, AgentRunV2NextAttemptFactory factory, Instant preparedAt);

    void persistAllocatedRequest(RecoveryState predecessor, ExecuteAgentRunRequest request);

    void verifyAllocatedRequest(RecoveryState current, ExecuteAgentRunRequest request);
}
