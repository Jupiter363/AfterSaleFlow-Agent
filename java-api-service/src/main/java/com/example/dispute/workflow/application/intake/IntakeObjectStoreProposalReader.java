package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.application.intake.IntakeProposalObjectStoreGateway.PermanentAccessException;
import com.example.dispute.workflow.application.intake.IntakeProposalObjectStoreGateway.RetryableAccessException;
import java.util.Objects;

/** Policy-enforcing adapter from a private object store to the immutable proposal read port. */
public final class IntakeObjectStoreProposalReader implements IntakeImmutableProposalReader {

    private final IntakeProposalObjectStoreGateway gateway;
    private final IntakeProposalUriAllowlist uriAllowlist;

    public IntakeObjectStoreProposalReader(
            IntakeProposalObjectStoreGateway gateway,
            IntakeProposalUriAllowlist uriAllowlist) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.uriAllowlist = Objects.requireNonNull(uriAllowlist, "uriAllowlist");
    }

    @Override
    public StoredProposal load(IntakeProposalReference reference) {
        Objects.requireNonNull(reference, "reference");
        uriAllowlist.requireAllowed(reference);
        try {
            StoredProposal stored = gateway.read(reference);
            if (stored == null) {
                throw new IntakeFinalizationRejectedException(
                        "INTAKE_PROPOSAL_OBJECT_INVALID",
                        "proposal object store returned no immutable receipt");
            }
            return stored;
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (RetryableAccessException failure) {
            throw new IntakeProposalLoadException(
                    "proposal object store is temporarily unavailable", failure);
        } catch (PermanentAccessException failure) {
            throw new IntakeFinalizationRejectedException(
                    failure.reason().rejectionCode(),
                    "proposal object cannot be accessed with the immutable reference",
                    failure);
        } catch (RuntimeException failure) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_PROPOSAL_ACCESS_UNCLASSIFIED",
                    "proposal object store failed without an explicit access classification",
                    failure);
        }
    }
}
