package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import java.util.Arrays;

/** Exact metadata/read port for the private content-addressed target-E2E proposal store. */
public interface TargetE2eIntakeProposalStore {

    ProposalMetadata resolve(ArtifactPointer pointer);

    StoredProposal readExact(IntakeProposalReference reference);

    record ProposalMetadata(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes) {}

    record StoredProposal(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes,
            byte[] payload) {
        public StoredProposal {
            payload = payload == null ? null : Arrays.copyOf(payload, payload.length);
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : Arrays.copyOf(payload, payload.length);
        }
    }
}
