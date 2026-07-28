package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.time.Instant;
import java.util.Objects;

/**
 * Room-owned authorization and evidence adapter for the target finalization lane.
 *
 * <p>An implementation must be registered as exactly one bean for its room type, perform its
 * own durable source/fact/binding verification in {@link #prepare}, and register the matching
 * {@code AgentRunDomainResultCommitter}. It must not append target receipts or complete command
 * admissions; those writes are owned by {@link TargetE2eMultiRoomOuterFinalizer}.
 */
public interface TargetE2eRoomFinalizationStrategy {

    RoomType roomType();

    /** Returns true only for the exact graph contract this room strategy owns. */
    boolean supports(ExecuteAgentRunRequest request);

    /**
     * Produces verified room evidence for one completed execution. Throw to fail closed.
     */
    PreparedFinalization prepare(ExecuteAgentRunRequest request, ExecuteAgentRunResult result);

    record PreparedFinalization(
            String activationManifestHash,
            ReceiptBindings receiptBindings,
            AgentRunV2ManifestFactory.FinalizationFacts manifestFacts) {
        public PreparedFinalization {
            if (activationManifestHash == null
                    || !activationManifestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "activationManifestHash must be a lowercase SHA-256");
            }
            receiptBindings = Objects.requireNonNull(receiptBindings, "receiptBindings");
            manifestFacts = Objects.requireNonNull(manifestFacts, "manifestFacts");
        }
    }

    /**
     * Verified receipt fields that cannot be reconstructed from the generic AgentRun contract.
     */
    record ReceiptBindings(
            String activationId,
            String tenantSurrogate,
            String caseId,
            RoomType roomType,
            long roomEpoch,
            long roomFencingToken,
            long processRevision,
            long stageSequence,
            String commandHash,
            String commandEnvelopeHash,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String checkpointId,
            String proposalHash,
            String resultEnvelopeHash,
            String isolatedDomainDbBindingHash,
            Instant committedAt) {
        public ReceiptBindings {
            Objects.requireNonNull(roomType, "roomType");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }
}
