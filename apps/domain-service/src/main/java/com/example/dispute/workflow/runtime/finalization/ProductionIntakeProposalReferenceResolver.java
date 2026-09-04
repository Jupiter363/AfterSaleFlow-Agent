package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;

/** Resolves the exact version and size omitted from the Graph artifact pointer. */
@FunctionalInterface
public interface ProductionIntakeProposalReferenceResolver {

    IntakeProposalReference resolve(ArtifactPointer pointer);
}
