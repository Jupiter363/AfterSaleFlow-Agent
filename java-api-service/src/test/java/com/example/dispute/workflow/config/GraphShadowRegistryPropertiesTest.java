package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphShadowRegistryPropertiesTest {

    @Test
    void buildsExactImmutableVisibilityAndRegistryPolicies() {
        GraphShadowRegistryProperties.BindingEntry entry = entry();
        GraphShadowRegistryProperties properties =
                new GraphShadowRegistryProperties(List.of(entry));

        GraphStreamVisibilityPolicy visibility = properties.visibilityPolicy();
        GraphRegistryBindingPolicy registry = properties.registryBindingPolicy();

        assertThat(visibility.allowedVisibleFields(entry.policyBinding()))
                .containsEntry("synthetic_node", Set.of("summary"));
        assertThat(GraphRegistryBindingPolicy.requireExpected(
                        registry, entry.policyBinding()))
                .isEqualTo(entry.expectedBinding());
        assertThat(visibility.allowedVisibleFields(otherBinding())).isNull();
        assertThatThrownBy(() -> GraphRegistryBindingPolicy.requireExpected(
                        registry, otherBinding()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    void rejectsEmptyDuplicateAndMalformedBindingsWhenActivated() {
        GraphShadowRegistryProperties unconfigured =
                new GraphShadowRegistryProperties(List.of());
        assertThat(unconfigured.bindings()).isEmpty();
        assertThatThrownBy(unconfigured::visibilityPolicy)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between one and 32");
        assertThatThrownBy(() -> new GraphShadowRegistryProperties(List.of(entry(), entry())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new GraphShadowRegistryProperties.BindingEntry(
                        "synthetic.shadow",
                        "1.0.0",
                        "checkpoint.v1",
                        "synthetic.agent.v1",
                        "synthetic.prompt.v1",
                        "synthetic.model.v1",
                        "synthetic.output.v1",
                        "synthetic.policy.v1",
                        "synthetic.guardrail.v1",
                        Audience.SYSTEM,
                        "not-a-hash",
                        "tools.none.v1",
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void normalizesTheDisabledYamlPlaceholderButRejectsItBesideRealBindings() {
        GraphShadowRegistryProperties.BindingEntry placeholder =
                new GraphShadowRegistryProperties.BindingEntry(
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        Audience.SYSTEM,
                        "",
                        "",
                        Map.of());

        assertThat(new GraphShadowRegistryProperties(List.of(placeholder)).bindings())
                .isEmpty();
        assertThatThrownBy(() ->
                        new GraphShadowRegistryProperties(List.of(entry(), placeholder)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete binding");
    }

    private static GraphShadowRegistryProperties.BindingEntry entry() {
        return new GraphShadowRegistryProperties.BindingEntry(
                "synthetic.shadow",
                "1.0.0",
                "checkpoint.v1",
                "synthetic.agent.v1",
                "synthetic.prompt.v1",
                "synthetic.model.v1",
                "synthetic.output.v1",
                "synthetic.policy.v1",
                "synthetic.guardrail.v1",
                Audience.SYSTEM,
                "a".repeat(64),
                "tools.none.v1",
                Map.of("synthetic_node", Set.of("summary")));
    }

    private static GraphStreamVisibilityPolicy.Binding otherBinding() {
        GraphShadowRegistryProperties.BindingEntry entry = entry();
        return new GraphStreamVisibilityPolicy.Binding(
                entry.graphKey(),
                "2.0.0",
                entry.checkpointSchemaVersion(),
                entry.agentProfileId(),
                entry.promptProfileId(),
                entry.modelProfileId(),
                entry.outputSchemaVersion(),
                entry.policyVersion(),
                entry.guardrailVersion(),
                entry.audience());
    }
}
