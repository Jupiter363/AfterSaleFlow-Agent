package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Resolves the server-owned public stream policy for one exact Graph registry binding. */
@FunctionalInterface
public interface GraphStreamVisibilityPolicy {

    /**
     * Returns public field names keyed by their exact producing node.
     *
     * <p>The resolver must return {@code null} for no binding only if the caller treats that as a
     * closed protocol failure. Command capabilities are deliberately absent from this contract and
     * cannot expand the returned policy.
     */
    Map<String, Set<String>> allowedVisibleFields(Binding binding);

    /** Immutable lookup key for every command-selected Graph and execution-profile version. */
    record Binding(
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

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public Binding {
            graphKey = requireIdentifier(graphKey, "graphKey");
            graphVersion = requireIdentifier(graphVersion, "graphVersion");
            checkpointSchemaVersion =
                    requireIdentifier(checkpointSchemaVersion, "checkpointSchemaVersion");
            agentProfileId = requireIdentifier(agentProfileId, "agentProfileId");
            promptProfileId = requireIdentifier(promptProfileId, "promptProfileId");
            modelProfileId = requireIdentifier(modelProfileId, "modelProfileId");
            outputSchemaVersion = requireIdentifier(outputSchemaVersion, "outputSchemaVersion");
            policyVersion = requireIdentifier(policyVersion, "policyVersion");
            guardrailVersion = requireIdentifier(guardrailVersion, "guardrailVersion");
            Objects.requireNonNull(audience, "audience");
        }

        public static Binding from(RoomGraphCommand command) {
            Objects.requireNonNull(command, "command");
            RoomGraphCommand.InvocationContext invocation = command.invocationContext();
            return new Binding(
                    command.graphKey(),
                    command.graphVersion(),
                    command.checkpointSchemaVersion(),
                    invocation.agentProfileId(),
                    invocation.promptProfileId(),
                    invocation.modelProfileId(),
                    invocation.outputSchemaVersion(),
                    invocation.policyVersion(),
                    invocation.guardrailVersion(),
                    command.actorScope().audience());
        }

        private static String requireIdentifier(String value, String field) {
            if (value == null || !IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " is not a bounded identifier");
            }
            return value;
        }
    }

    /** Makes a defensive, validated copy before any untrusted stream line is parsed. */
    static Map<String, Set<String>> immutablePolicy(Map<String, Set<String>> policy) {
        Objects.requireNonNull(policy, "policy");
        return policy.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> requirePolicyIdentifier(entry.getKey(), "node"),
                        entry -> Set.copyOf(Objects.requireNonNull(
                                        entry.getValue(), "visible fields"))
                                .stream()
                                .map(field -> requirePolicyIdentifier(field, "field"))
                                .collect(Collectors.toUnmodifiableSet())));
    }

    private static String requirePolicyIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " is not a bounded identifier");
        }
        return value;
    }
}
