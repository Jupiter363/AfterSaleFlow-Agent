package com.example.dispute.workflow.targete2e.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/** Fail-closed structural prerequisites evaluated before the application context is created. */
final class TargetE2eArtifactPrerequisites {

    static final String REQUIRED_PROFILE = "target-e2e";
    static final String CONTROL_WORKER_ROLE = "CONTROL";
    static final String AGENT_WORKER_ROLE = "AGENT";
    static final String API_ROLE = "API";
    private static final Set<String> TARGET_WORKER_ROLES =
            Set.of(CONTROL_WORKER_ROLE, AGENT_WORKER_ROLE);
    private static final Set<String> TARGET_PROCESS_ROLES =
            Set.of(API_ROLE, CONTROL_WORKER_ROLE, AGENT_WORKER_ROLE);
    static final String WORKER_ENABLED_PROPERTY = "app.temporal.worker.enabled";
    static final String WORKER_ROLE_PROPERTY = "app.temporal.worker.role";
    static final String WORKER_VERSIONING_PROPERTY = "app.temporal.worker.versioning-mode";
    static final String AGENT_RUN_ENABLED_PROPERTY = "app.agent-run-v2.enabled";
    static final String AGENT_RUN_PROTOCOL_PROPERTY = "app.agent-run-v2.protocol-default";
    static final String AGENT_RUN_SCHEDULER_PROPERTY = "app.agent-run-v2.scheduler-mode";
    static final String GRAPH_CLIENT_MODE_PROPERTY = "app.agent-run-v2.graph-client.mode";
    static final String ACTIVATION_JWS_PROPERTY = "app.target-e2e.activation.manifest-jws";
    static final String ACTIVATION_JWS_PATH_PROPERTY = "app.target-e2e.activation-manifest-path";

    private final Set<String> activeProfiles;
    private final String embeddedMarker;
    private final boolean workerEnabled;
    private final String workerRole;
    private final String workerVersioningMode;
    private final boolean agentRunV2Enabled;
    private final String agentRunProtocol;
    private final String agentRunScheduler;
    private final String graphClientMode;
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
            String graphClientMode,
            String activationJws) {
        this.activeProfiles = Set.copyOf(Objects.requireNonNull(activeProfiles, "activeProfiles"));
        this.embeddedMarker = embeddedMarker;
        this.workerEnabled = workerEnabled;
        this.workerRole = workerRole;
        this.workerVersioningMode = workerVersioningMode;
        this.agentRunV2Enabled = agentRunV2Enabled;
        this.agentRunProtocol = agentRunProtocol;
        this.agentRunScheduler = agentRunScheduler;
        this.graphClientMode = graphClientMode;
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
                environment.getProperty(GRAPH_CLIENT_MODE_PROPERTY),
                loadActivationJws(environment));
    }

    private static String loadActivationJws(Environment environment) {
        String inline = environment.getProperty(ACTIVATION_JWS_PROPERTY);
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String configuredPath = environment.getProperty(ACTIVATION_JWS_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return inline;
        }
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        try {
            long size = Files.size(path);
            require(
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && size > 0
                            && size <= 131_072,
                    "TARGET_E2E_ACTIVATION_MALFORMED");
            return Files.readString(path, StandardCharsets.US_ASCII).trim();
        } catch (IOException failure) {
            throw new IllegalStateException("TARGET_E2E_ACTIVATION_UNREACHABLE", failure);
        }
    }

    void validate() {
        require(activeProfiles.contains(REQUIRED_PROFILE), "TARGET_E2E_PROFILE_REQUIRED");
        require(
                TargetE2eArtifactMarker.EXPECTED_VALUE.equals(embeddedMarker),
                "TARGET_E2E_ARTIFACT_MARKER_INVALID");
        require(TARGET_PROCESS_ROLES.contains(workerRole), "TARGET_E2E_WORKER_ROLE_INVALID");
        if (API_ROLE.equals(workerRole)) {
            require(!workerEnabled, "TARGET_E2E_API_WORKER_MUST_BE_DISABLED");
        } else {
            require(workerEnabled, "TARGET_E2E_WORKER_DISABLED");
            require(TARGET_WORKER_ROLES.contains(workerRole), "TARGET_E2E_WORKER_ROLE_INVALID");
            require(
                    "BUILD_ID".equals(workerVersioningMode)
                            || "DEPLOYMENT".equals(workerVersioningMode),
                    "TARGET_E2E_WORKER_VERSIONING_REQUIRED");
        }
        if (CONTROL_WORKER_ROLE.equals(workerRole)) {
            validateActivationMaterialShape(activationJws);
        }
        if (AGENT_WORKER_ROLE.equals(workerRole)) {
            require(agentRunV2Enabled, "TARGET_E2E_AGENT_RUN_V2_REQUIRED");
        }
        require("V3".equals(agentRunProtocol), "TARGET_E2E_AGENT_RUN_PROTOCOL_INVALID");
        require("DETECTOR".equals(agentRunScheduler), "TARGET_E2E_AGENT_RUN_SCHEDULER_INVALID");
        if (AGENT_WORKER_ROLE.equals(workerRole)) {
            require(
                    "TARGET_E2E_CANDIDATE".equals(graphClientMode),
                    "TARGET_E2E_GRAPH_CLIENT_MODE_INVALID");
        }
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
