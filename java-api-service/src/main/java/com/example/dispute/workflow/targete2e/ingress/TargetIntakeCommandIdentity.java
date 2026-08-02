package com.example.dispute.workflow.targete2e.ingress;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Canonical, durable identities for Target Intake commands. */
public final class TargetIntakeCommandIdentity {

    private static final String MESSAGE_COMMAND_PREFIX = "intake-message:";

    private TargetIntakeCommandIdentity() {}

    public static String messageCommandId(
            TargetIntakeActivationGrant activation, TargetIntakeMessageRequest request) {
        return MESSAGE_COMMAND_PREFIX + messageIdentity(activation, request);
    }

    public static String messageIdentity(
            TargetIntakeActivationGrant activation, TargetIntakeMessageRequest request) {
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(request, "request");
        return token(
                String.join(
                        "\n",
                        activation.tenantSurrogate(),
                        request.caseId(),
                        request.roomId(),
                        Long.toString(activation.roomEpoch()),
                        Long.toString(activation.roomFencingToken()),
                        request.actor().actorId(),
                        request.actor().role().name(),
                        request.sourceType().name(),
                        request.messageId()));
    }

    private static String token(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }
}
