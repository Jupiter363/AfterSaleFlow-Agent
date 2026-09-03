package com.example.dispute.workflow.targete2e;

/** Exact activation and Build-ID identity required before a target AGENT worker starts. */
public record TargetE2eAgentDeploymentBinding(
        String environmentId,
        long environmentGeneration,
        String activationId,
        String manifestHash,
        String agentBuildId) {

    public TargetE2eAgentDeploymentBinding {
        TargetE2eActivationContract.identifier(environmentId, "environmentId");
        TargetE2eActivationContract.generation(environmentGeneration);
        TargetE2eActivationContract.activationId(activationId);
        TargetE2eActivationContract.sha256(manifestHash, "manifestHash");
        if (agentBuildId == null
                || agentBuildId.isBlank()
                || agentBuildId.length() > 128) {
            throw new IllegalArgumentException("agentBuildId must be a bounded value");
        }
    }

    public static TargetE2eAgentDeploymentBinding requireExact(
            TargetE2eAgentDeploymentBinding configured,
            TargetE2eAgentDeploymentBinding registered) {
        if (configured == null || registered == null || !configured.equals(registered)) {
            throw new IllegalStateException(
                    "target AGENT deployment does not match the registered activation");
        }
        return registered;
    }

    public void requireWorkerConfiguration(String graphActivationId, String workerBuildId) {
        if (!TargetE2eActivationContract.same(activationId, graphActivationId)
                || !TargetE2eActivationContract.same(agentBuildId, workerBuildId)) {
            throw new IllegalStateException(
                    "target AGENT worker configuration does not match its activation binding");
        }
    }
}
