package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Optional;

/** Append-only manifest port. Same hash replays; a different hash for a logical run conflicts. */
public interface AgentExecutionManifestStore {

    AgentRunFinalizationReceipt append(ManifestCommit commit);

    Optional<AgentRunFinalizationReceipt> findCommitted(String logicalRunId);

    record ManifestCommit(
            AgentExecutionManifest manifest,
            RoomType roomType,
            String manifestUri,
            String manifestHash,
            String finalResultHash,
            long finalStreamSequenceNo) {}
}
