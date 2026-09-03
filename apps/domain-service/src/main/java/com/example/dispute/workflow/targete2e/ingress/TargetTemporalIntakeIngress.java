package com.example.dispute.workflow.targete2e.ingress;

@FunctionalInterface
public interface TargetTemporalIntakeIngress {

    TargetIntakeIngressReceipt accept(TargetIntakeMessageRequest request);
}
