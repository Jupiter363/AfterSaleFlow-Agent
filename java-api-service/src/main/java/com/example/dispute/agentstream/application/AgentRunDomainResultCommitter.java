package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

/** Room-owned, idempotent writer for a validated graph proposal. */
public interface AgentRunDomainResultCommitter {

    boolean supports(RoomType roomType, String graphKey);

    /**
     * Writes the room fact and returns the authoritative bindings read during that transaction.
     * Replays for the same logical run and result hash must return the same formal object.
     */
    CommitReceipt commit(CommitCommand command);

    record CommitCommand(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            AgentExecutionManifest manifest) {
        public CommitCommand {
            if (request == null || result == null || manifest == null) {
                throw new IllegalArgumentException("request, result, and manifest are required");
            }
        }
    }

    record CommitReceipt(
            String formalObjectId,
            String caseId,
            long roomEpoch,
            long processRevision,
            String stageCode,
            long stageSequence,
            String actorId,
            ActorRole actorRole,
            Audience audience,
            long fencingToken,
            String resultHash) {
        public CommitReceipt {
            requireText(formalObjectId, "formalObjectId");
            requireText(caseId, "caseId");
            requireText(stageCode, "stageCode");
            requireText(actorId, "actorId");
            if (roomEpoch < 0 || processRevision < 0 || stageSequence < 0 || fencingToken < 1) {
                throw new IllegalArgumentException("domain commit versions or fence are invalid");
            }
            if (actorRole == null || audience == null) {
                throw new IllegalArgumentException("actorRole and audience are required");
            }
            if (resultHash == null || !resultHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("resultHash must be a lowercase SHA-256");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
