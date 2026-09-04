package com.example.dispute.workflow.runtime;

/** Exact activation and Build-ID identity required before a target AGENT worker starts. */
public record ProductionAgentDeploymentBinding(
        String environmentId,
        long environmentGeneration,
        String activationId,
        String manifestHash,
        String agentBuildId) {

    public ProductionAgentDeploymentBinding {
        ProductionActivationContract.identifier(environmentId, "environmentId");
        ProductionActivationContract.generation(environmentGeneration);
        ProductionActivationContract.activationId(activationId);
        ProductionActivationContract.sha256(manifestHash, "manifestHash");
        if (agentBuildId == null
                || agentBuildId.isBlank()
                || agentBuildId.length() > 128) {
            throw new IllegalArgumentException("agentBuildId must be a bounded value");
        }
    }

    public static ProductionAgentDeploymentBinding requireExact(
            ProductionAgentDeploymentBinding configured,
            ProductionAgentDeploymentBinding registered) {
        if (configured == null || registered == null || !configured.equals(registered)) {
            throw new IllegalStateException(
                    "target AGENT deployment does not match the registered activation");
        }
        return registered;
    }

    public void requireWorkerConfiguration(String graphActivationId, String workerBuildId) {
        if (!ProductionActivationContract.same(activationId, graphActivationId)
                || !ProductionActivationContract.same(agentBuildId, workerBuildId)) {
            throw new IllegalStateException(
                    "target AGENT worker configuration does not match its activation binding");
        }
    }
}
