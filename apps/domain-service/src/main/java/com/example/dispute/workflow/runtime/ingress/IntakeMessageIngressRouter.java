package com.example.dispute.workflow.runtime.ingress;

/** Selects the server-owned Intake writer and dispatches only an authorized target command. */
public interface IntakeMessageIngressRouter {

    IntakeIngressSelection select(String caseId);

    TargetIntakeIngressReceipt dispatchTarget(
            IntakeIngressSelection selection, TargetIntakeMessageRequest request);
}
