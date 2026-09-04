package com.example.dispute.workflow.runtime.ingress;

@FunctionalInterface
public interface TargetIntakeActivationAuthority {

    TargetIntakeActivationGrant authorize(TargetIntakeEpochBinding binding);
}
