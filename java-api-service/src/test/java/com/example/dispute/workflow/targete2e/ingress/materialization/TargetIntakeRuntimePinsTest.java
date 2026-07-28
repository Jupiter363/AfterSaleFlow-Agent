package com.example.dispute.workflow.targete2e.ingress.materialization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetIntakeRuntimePinsTest {

    @Test
    void suppliesTheExplicitTargetRegistrationContract() {
        TargetIntakeRuntimePins pins = new TargetIntakeRuntimePins(
                "case-build", "agent-build", "a".repeat(64), "graph-code-build", "b".repeat(64),
                "agent-profile", "prompt", "model", "policy", "guardrail", "tool-policy", "memory",
                "envelope-key");

        var registration = pins.registrationPins();

        assertThat(registration.graphKey()).isEqualTo("all-rooms.target-e2e.v1");
        assertThat(registration.graphVersion()).isEqualTo("target-e2e-graph.2026-07-27.1");
        assertThat(registration.checkpointSchemaVersion()).isEqualTo("target-e2e-checkpoint.v1");
        assertThat(registration.stateSchemaVersion()).isEqualTo("intake-graph-state.v2");
        assertThat(registration.outputSchemaVersion())
                .isEqualTo("target-e2e-room-proposal-source.v1");
        assertThat(pins.agentProfileId()).isEqualTo("agent-profile");
    }
}
