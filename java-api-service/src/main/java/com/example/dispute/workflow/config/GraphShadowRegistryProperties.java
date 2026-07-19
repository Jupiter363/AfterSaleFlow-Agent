package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Java-owned exact catalog for synthetic SHADOW graph/profile bindings. */
@ConfigurationProperties(prefix = "app.agent-run-v2.graph-client.registry")
public record GraphShadowRegistryProperties(List<BindingEntry> bindings) {

    public GraphShadowRegistryProperties {
        bindings = List.copyOf(Objects.requireNonNullElse(bindings, List.of()));
        // application.yml exposes one env-backed slot that stays empty while Graph is disabled.
        if (bindings.size() == 1 && bindings.getFirst().emptyPlaceholder()) {
            bindings = List.of();
        }
        if (bindings.size() > 32) {
            throw new IllegalArgumentException(
                    "SHADOW Graph registry supports at most 32 exact bindings");
        }
        if (bindings.stream().anyMatch(BindingEntry::emptyPlaceholder)) {
            throw new IllegalArgumentException(
                    "SHADOW Graph registry contains an incomplete binding");
        }
        Set<GraphStreamVisibilityPolicy.Binding> unique = bindings.stream()
                .map(BindingEntry::policyBinding)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (unique.size() != bindings.size()) {
            throw new IllegalArgumentException("SHADOW Graph registry contains a duplicate binding");
        }
    }

    public GraphStreamVisibilityPolicy visibilityPolicy() {
        requireConfigured();
        Map<GraphStreamVisibilityPolicy.Binding, Map<String, Set<String>>> catalog =
                new HashMap<>();
        for (BindingEntry entry : bindings) {
            catalog.put(
                    entry.policyBinding(),
                    GraphStreamVisibilityPolicy.immutablePolicy(entry.visibleFieldsByNode()));
        }
        Map<GraphStreamVisibilityPolicy.Binding, Map<String, Set<String>>> snapshot =
                Map.copyOf(catalog);
        return binding -> snapshot.get(Objects.requireNonNull(binding, "binding"));
    }

    public GraphRegistryBindingPolicy registryBindingPolicy() {
        requireConfigured();
        Map<GraphStreamVisibilityPolicy.Binding, GraphRegistryBindingPolicy.ExpectedBinding>
                catalog = new HashMap<>();
        for (BindingEntry entry : bindings) {
            catalog.put(entry.policyBinding(), entry.expectedBinding());
        }
        return GraphRegistryBindingPolicy.immutable(catalog);
    }

    public void requireConfigured() {
        if (bindings.isEmpty()) {
            throw new IllegalStateException(
                    "SHADOW Graph registry requires between one and 32 exact bindings");
        }
    }

    public record BindingEntry(
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String agentProfileId,
            String promptProfileId,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            Audience audience,
            String registryBindingHash,
            String toolPolicyVersion,
            Map<String, Set<String>> visibleFieldsByNode) {

        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        public BindingEntry {
            boolean placeholder = emptyPlaceholder(
                    graphKey,
                    graphVersion,
                    checkpointSchemaVersion,
                    agentProfileId,
                    promptProfileId,
                    modelProfileId,
                    outputSchemaVersion,
                    policyVersion,
                    guardrailVersion,
                    audience,
                    registryBindingHash,
                    toolPolicyVersion,
                    visibleFieldsByNode);
            if (!placeholder) {
                policyBinding(
                        graphKey,
                        graphVersion,
                        checkpointSchemaVersion,
                        agentProfileId,
                        promptProfileId,
                        modelProfileId,
                        outputSchemaVersion,
                        policyVersion,
                        guardrailVersion,
                        audience);
                if (registryBindingHash == null
                        || !SHA256.matcher(registryBindingHash).matches()) {
                    throw new IllegalArgumentException(
                            "SHADOW registry binding hash must be lowercase SHA-256");
                }
                new GraphRegistryBindingPolicy.ExpectedBinding(
                        registryBindingHash,
                        toolPolicyVersion);
            }
            visibleFieldsByNode = Map.copyOf(
                    Objects.requireNonNullElse(visibleFieldsByNode, Map.of()));
        }

        boolean emptyPlaceholder() {
            return emptyPlaceholder(
                    graphKey,
                    graphVersion,
                    checkpointSchemaVersion,
                    agentProfileId,
                    promptProfileId,
                    modelProfileId,
                    outputSchemaVersion,
                    policyVersion,
                    guardrailVersion,
                    audience,
                    registryBindingHash,
                    toolPolicyVersion,
                    visibleFieldsByNode);
        }

        public GraphStreamVisibilityPolicy.Binding policyBinding() {
            return policyBinding(
                    graphKey,
                    graphVersion,
                    checkpointSchemaVersion,
                    agentProfileId,
                    promptProfileId,
                    modelProfileId,
                    outputSchemaVersion,
                    policyVersion,
                    guardrailVersion,
                    audience);
        }

        public GraphRegistryBindingPolicy.ExpectedBinding expectedBinding() {
            return new GraphRegistryBindingPolicy.ExpectedBinding(
                    registryBindingHash,
                    toolPolicyVersion);
        }

        private static GraphStreamVisibilityPolicy.Binding policyBinding(
                String graphKey,
                String graphVersion,
                String checkpointSchemaVersion,
                String agentProfileId,
                String promptProfileId,
                String modelProfileId,
                String outputSchemaVersion,
                String policyVersion,
                String guardrailVersion,
                Audience audience) {
            return new GraphStreamVisibilityPolicy.Binding(
                    graphKey,
                    graphVersion,
                    checkpointSchemaVersion,
                    agentProfileId,
                    promptProfileId,
                    modelProfileId,
                    outputSchemaVersion,
                    policyVersion,
                    guardrailVersion,
                    audience);
        }

        private static boolean emptyPlaceholder(
                String graphKey,
                String graphVersion,
                String checkpointSchemaVersion,
                String agentProfileId,
                String promptProfileId,
                String modelProfileId,
                String outputSchemaVersion,
                String policyVersion,
                String guardrailVersion,
                Audience audience,
                String registryBindingHash,
                String toolPolicyVersion,
                Map<String, Set<String>> visibleFieldsByNode) {
            return blank(graphKey)
                    && blank(graphVersion)
                    && blank(checkpointSchemaVersion)
                    && blank(agentProfileId)
                    && blank(promptProfileId)
                    && blank(modelProfileId)
                    && blank(outputSchemaVersion)
                    && blank(policyVersion)
                    && blank(guardrailVersion)
                    && (audience == null || audience == Audience.SYSTEM)
                    && blank(registryBindingHash)
                    && blank(toolPolicyVersion)
                    && (visibleFieldsByNode == null || visibleFieldsByNode.isEmpty());
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
