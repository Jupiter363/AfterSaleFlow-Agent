package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import java.util.Objects;

/** Routes immutable proposal reads by an explicit URI authority. */
public final class RoutingTargetE2eIntakeProposalStore implements TargetE2eIntakeProposalStore {

    private static final String PARALLEL_URI_PREFIX = "urn:target-e2e:proposal:intake:";
    private static final String MINIO_URI_PREFIX = "minio://";

    private final TargetE2eIntakeProposalStore minio;
    private final TargetE2eIntakeProposalStore parallel;

    public RoutingTargetE2eIntakeProposalStore(
            TargetE2eIntakeProposalStore minio,
            TargetE2eIntakeProposalStore parallel) {
        this.minio = Objects.requireNonNull(minio, "minio");
        this.parallel = Objects.requireNonNull(parallel, "parallel");
    }

    @Override
    public ProposalMetadata resolve(ArtifactPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        return route(pointer.uri()).resolve(pointer);
    }

    @Override
    public StoredProposal readExact(IntakeProposalReference reference) {
        Objects.requireNonNull(reference, "reference");
        return route(reference.uri()).readExact(reference);
    }

    private TargetE2eIntakeProposalStore route(String uri) {
        if (uri != null && uri.startsWith(PARALLEL_URI_PREFIX)) {
            return parallel;
        }
        if (uri != null && uri.startsWith(MINIO_URI_PREFIX)) {
            return minio;
        }
        throw new IntakeFinalizationRejectedException(
                "INTAKE_PROPOSAL_URI_FORBIDDEN",
                "proposal URI does not select an authorized immutable store");
    }
}
