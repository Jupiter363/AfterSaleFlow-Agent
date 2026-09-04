package com.example.dispute.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductionAgentDeploymentBindingTest {

    private static final String ACTIVATION_ID =
            "p9act.v1.0123456789abcdef0123456789abcdef";
    private static final String MANIFEST_HASH = "a".repeat(64);

    @Test
    void acceptsTheExactRegisteredActivationAndWorkerConfiguration() {
        ProductionAgentDeploymentBinding configured = binding("agent-build-1");

        assertThat(
                        ProductionAgentDeploymentBinding.requireExact(
                                configured, binding("agent-build-1")))
                .isEqualTo(configured);
        configured.requireWorkerConfiguration(ACTIVATION_ID, "agent-build-1");
    }

    @Test
    void rejectsARegisteredAgentBuildIdMismatch() {
        assertThatThrownBy(
                        () ->
                                ProductionAgentDeploymentBinding.requireExact(
                                        binding("agent-build-1"),
                                        binding("agent-build-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registered activation");
    }

    @Test
    void rejectsIndependentGraphActivationOrWorkerBuildConfiguration() {
        ProductionAgentDeploymentBinding binding = binding("agent-build-1");

        assertThatThrownBy(
                        () ->
                                binding.requireWorkerConfiguration(
                                        "p9act.v1.ffffffffffffffffffffffffffffffff",
                                        "agent-build-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                binding.requireWorkerConfiguration(
                                        ACTIVATION_ID,
                                        "agent-build-2"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ProductionAgentDeploymentBinding binding(String agentBuildId) {
        return new ProductionAgentDeploymentBinding(
                "local-preprod",
                1,
                ACTIVATION_ID,
                MANIFEST_HASH,
                agentBuildId);
    }
}
