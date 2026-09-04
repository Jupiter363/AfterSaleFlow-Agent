package com.example.dispute.workflow.runtime.exchange.rooms;

import com.example.dispute.workflow.runtime.graph.ProductionRoomProposalSource;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingCommandMaterialStore;

/** Finalizer-only reader for a proposal already reconciled against its admitted command. */
public interface ProductionRoomProposalPayloadReader {
  LoadedProposal load(ProductionRoomProposalSource.Proposal proposal,
      TargetHearingCommandMaterialStore.Snapshot material);

  record LoadedProposal(String proposalId, String payloadSchemaVersion, String payloadRef,
      String sha256, long sizeBytes, byte[] canonicalPayload) {
    public LoadedProposal {
      if (proposalId == null || payloadSchemaVersion == null || payloadRef == null
          || sha256 == null || !sha256.matches("[0-9a-f]{64}") || sizeBytes < 1
          || canonicalPayload == null || canonicalPayload.length != sizeBytes) {
        throw new IllegalArgumentException("loaded target room proposal is invalid");
      }
      canonicalPayload = canonicalPayload.clone();
    }

    @Override public byte[] canonicalPayload() { return canonicalPayload.clone(); }
  }
}
