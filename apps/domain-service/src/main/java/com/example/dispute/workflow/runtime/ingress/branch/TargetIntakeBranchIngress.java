package com.example.dispute.workflow.runtime.ingress.branch;

@FunctionalInterface
public interface TargetIntakeBranchIngress {

    TargetIntakeBranchIngressReceipt accept(TargetIntakeBranchRequest request);
}
