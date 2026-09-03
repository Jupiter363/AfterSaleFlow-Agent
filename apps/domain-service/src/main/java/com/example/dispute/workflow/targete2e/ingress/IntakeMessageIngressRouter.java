package com.example.dispute.workflow.targete2e.ingress;

/** Selects the server-owned Intake writer and dispatches only an authorized target command. */
public interface IntakeMessageIngressRouter {

    IntakeIngressSelection select(String caseId);

    TargetIntakeIngressReceipt dispatchTarget(
            IntakeIngressSelection selection, TargetIntakeMessageRequest request);
}
