package com.example.dispute.workflow.targete2e.ingress.branch;

@FunctionalInterface
public interface TargetIntakeBranchIngress {

    TargetIntakeBranchIngressReceipt accept(TargetIntakeBranchRequest request);
}
