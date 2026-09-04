package com.example.dispute.workflow.runtime.ingress;

@FunctionalInterface
public interface TargetTemporalIntakeIngress {

    TargetIntakeIngressReceipt accept(TargetIntakeMessageRequest request);
}
