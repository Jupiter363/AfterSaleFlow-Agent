package com.example.dispute.workflow.activity.agent;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Java-owned expected Python Graph registry binding for one exact execution manifest. */
@FunctionalInterface
public interface GraphRegistryBindingPolicy {

    ExpectedBinding expectedBinding(GraphStreamVisibilityPolicy.Binding binding);

    record ExpectedBinding(String registryBindingHash, String toolPolicyVersion) {

        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public ExpectedBinding {
            if (registryBindingHash == null || !SHA256.matcher(registryBindingHash).matches()) {
                throw new IllegalArgumentException("registryBindingHash must be lowercase SHA-256");
            }
            if (toolPolicyVersion == null || !IDENTIFIER.matcher(toolPolicyVersion).matches()) {
                throw new IllegalArgumentException("toolPolicyVersion must be a bounded identifier");
            }
        }
    }

    static GraphRegistryBindingPolicy immutable(
            Map<GraphStreamVisibilityPolicy.Binding, ExpectedBinding> bindings) {
        Map<GraphStreamVisibilityPolicy.Binding, ExpectedBinding> snapshot =
                Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
        return binding -> snapshot.get(Objects.requireNonNull(binding, "binding"));
    }

    static ExpectedBinding requireExpected(
            GraphRegistryBindingPolicy policy,
            GraphStreamVisibilityPolicy.Binding binding) {
        ExpectedBinding expected = Objects.requireNonNull(policy, "policy")
                .expectedBinding(Objects.requireNonNull(binding, "binding"));
        if (expected == null) {
            throw new IllegalArgumentException("Graph registry binding is not registered in Java");
        }
        return expected;
    }
}
