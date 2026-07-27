package com.example.dispute.workflow.targete2e.artifact;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/** Fail-closed structural prerequisites evaluated before the application context is created. */
final class TargetE2eArtifactPrerequisites {

    static final String REQUIRED_PROFILE = "target-e2e";
    static final String REQUIRED_WORKER_ROLE = "AGENT";
    static final String WORKER_ENABLED_PROPERTY = "app.temporal.worker.enabled";
    static final String WORKER_ROLE_PROPERTY = "app.temporal.worker.role";
    static final String WORKER_VERSIONING_PROPERTY = "app.temporal.worker.versioning-mode";
    static final String AGENT_RUN_ENABLED_PROPERTY = "app.agent-run-v2.enabled";
    static final String AGENT_RUN_PROTOCOL_PROPERTY = "app.agent-run-v2.protocol-default";
    static final String AGENT_RUN_SCHEDULER_PROPERTY = "app.agent-run-v2.scheduler-mode";
    static final String ACTIVATION_JWS_PROPERTY = "app.target-e2e.activation.manifest-jws";

    private final Set<String> activeProfiles;
    private final String embeddedMarker;
    private final boolean workerEnabled;
    private final String workerRole;
    private final String workerVersioningMode;
    private final boolean agentRunV2Enabled;
    private final String agentRunProtocol;
    private final String agentRunScheduler;
    private final String activationJws;

    TargetE2eArtifactPrerequisites(
            Set<String> activeProfiles,
            String embeddedMarker,
            boolean workerEnabled,
            String workerRole,
            String workerVersioningMode,
            boolean agentRunV2Enabled,
            String agentRunProtocol,
            String agentRunScheduler,
            String activationJws) {
        this.activeProfiles = Set.copyOf(Objects.requireNonNull(activeProfiles, "activeProfiles"));
        this.embeddedMarker = embeddedMarker;
        this.workerEnabled = workerEnabled;
        this.workerRole = workerRole;
        this.workerVersioningMode = workerVersioningMode;
        this.agentRunV2Enabled = agentRunV2Enabled;
        this.agentRunProtocol = agentRunProtocol;
        this.agentRunScheduler = agentRunScheduler;
        this.activationJws = activationJws;
    }

    static TargetE2eArtifactPrerequisites from(
            Environment environment, ClassLoader classLoader) {
        Objects.requireNonNull(environment, "environment");
        return new TargetE2eArtifactPrerequisites(
                Arrays.stream(environment.getActiveProfiles())
                        .collect(Collectors.toUnmodifiableSet()),
                TargetE2eEmbeddedMarker.read(classLoader),
                environment.getProperty(WORKER_ENABLED_PROPERTY, Boolean.class, false),
                environment.getProperty(WORKER_ROLE_PROPERTY),
                environment.getProperty(WORKER_VERSIONING_PROPERTY),
                environment.getProperty(AGENT_RUN_ENABLED_PROPERTY, Boolean.class, false),
                environment.getProperty(AGENT_RUN_PROTOCOL_PROPERTY),
                environment.getProperty(AGENT_RUN_SCHEDULER_PROPERTY),
                environment.getProperty(ACTIVATION_JWS_PROPERTY));
    }

    void validate() {
        require(activeProfiles.contains(REQUIRED_PROFILE), "TARGET_E2E_PROFILE_REQUIRED");
        require(
                TargetE2eArtifactMarker.EXPECTED_VALUE.equals(embeddedMarker),
                "TARGET_E2E_ARTIFACT_MARKER_INVALID");
        require(workerEnabled, "TARGET_E2E_WORKER_DISABLED");
        require(REQUIRED_WORKER_ROLE.equals(workerRole), "TARGET_E2E_WORKER_ROLE_INVALID");
        validateActivationMaterialShape(activationJws);
        require(
                "BUILD_ID".equals(workerVersioningMode)
                        || "DEPLOYMENT".equals(workerVersioningMode),
                "TARGET_E2E_WORKER_VERSIONING_REQUIRED");
        require(agentRunV2Enabled, "TARGET_E2E_AGENT_RUN_V2_REQUIRED");
        require("V2".equals(agentRunProtocol), "TARGET_E2E_AGENT_RUN_PROTOCOL_INVALID");
        require("DETECTOR".equals(agentRunScheduler), "TARGET_E2E_AGENT_RUN_SCHEDULER_INVALID");
    }

    private static void validateActivationMaterialShape(String compactJws) {
        require(compactJws != null && !compactJws.isBlank(), "TARGET_E2E_ACTIVATION_REQUIRED");
        require(compactJws.length() <= 131_072, "TARGET_E2E_ACTIVATION_TOO_LARGE");
        require(
                compactJws.chars().noneMatch(Character::isWhitespace),
                "TARGET_E2E_ACTIVATION_MALFORMED");

        String[] segments = compactJws.split("\\.", -1);
        require(segments.length == 3, "TARGET_E2E_ACTIVATION_MALFORMED");
        byte[] header = decodeCanonicalSegment(segments[0]);
        byte[] payload = decodeCanonicalSegment(segments[1]);
        byte[] signature = decodeCanonicalSegment(segments[2]);
        require(header.length > 0 && header.length <= 4_096, "TARGET_E2E_ACTIVATION_MALFORMED");
        require(payload.length > 0 && payload.length <= 65_536, "TARGET_E2E_ACTIVATION_MALFORMED");
        require(signature.length == 64, "TARGET_E2E_ACTIVATION_SIGNATURE_MALFORMED");
        require(
                new String(header, StandardCharsets.UTF_8).startsWith("{"),
                "TARGET_E2E_ACTIVATION_MALFORMED");
        require(
                new String(payload, StandardCharsets.UTF_8).startsWith("{"),
                "TARGET_E2E_ACTIVATION_MALFORMED");
    }

    private static byte[] decodeCanonicalSegment(String segment) {
        require(!segment.isEmpty() && segment.indexOf('=') < 0, "TARGET_E2E_ACTIVATION_MALFORMED");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(segment);
            require(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment),
                    "TARGET_E2E_ACTIVATION_MALFORMED");
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("TARGET_E2E_ACTIVATION_MALFORMED", exception);
        }
    }

    private static void require(boolean condition, String failureCode) {
        if (!condition) {
            throw new IllegalStateException(failureCode);
        }
    }
}
