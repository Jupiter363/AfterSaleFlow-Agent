package com.example.dispute.workflow.targete2e.ingress;

@FunctionalInterface
public interface TargetIntakeActivationAuthority {

    TargetIntakeActivationGrant authorize(TargetIntakeEpochBinding binding);
}
