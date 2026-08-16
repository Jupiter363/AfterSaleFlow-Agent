package com.example.dispute.workflow.contract.v1;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ContractTypes {

    private ContractTypes() {}

    static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    static String version(String value, String expected) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException("schema_version must be " + expected);
        }
        return value;
    }

    static <T> List<T> immutableList(List<T> values, String field) {
        return List.copyOf(required(values, field));
    }

    static <K, V> Map<K, V> immutableMap(Map<K, V> values, String field) {
        return Map.copyOf(required(values, field));
    }

    public enum ActorRole {
        USER,
        MERCHANT,
        PLATFORM_REVIEWER,
        ADMIN,
        SYSTEM
    }

    public enum Audience {
        USER,
        MERCHANT,
        PLATFORM_REVIEWER,
        SYSTEM
    }

    public enum RoomType {
        INTAKE,
        EVIDENCE,
        HEARING,
        REVIEW
    }

    public enum CommandType {
        CASE_OPEN,
        INTAKE_MESSAGE,
        INTAKE_CONFIRM,
        INTAKE_CANCEL,
        EVIDENCE_OPENING,
        EVIDENCE_SUBMIT,
        PARTY_EVIDENCE_COMPLETE,
        HEARING_STATEMENT,
        HEARING_EVIDENCE_BATCH,
        REVIEW_DECISION,
        EXECUTE_APPROVED_PLAN,
        CLOSE_CASE
    }

    public enum Visibility {
        PRIVATE,
        PARTIES,
        PLATFORM,
        INTERNAL
    }

    public enum WriterMode {
        LEGACY,
        SHADOW,
        TEMPORAL
    }

    public enum PendingState {
        NONE,
        WAITING_PARTY,
        WAITING_TIMER,
        AGENT_RUNNING,
        REVIEW_PENDING,
        TOOL_RUNNING,
        FAILED
    }

    public enum StreamEventType {
        ATTEMPT_STARTED("attempt_started"),
        VISIBLE_DELTA("visible_delta"),
        USAGE("usage"),
        ATTEMPT_ABORTED("attempt_aborted"),
        ATTEMPT_RESET("attempt_reset"),
        FINAL("final"),
        ERROR("error");

        private final String wireValue;

        StreamEventType(String wireValue) {
            this.wireValue = wireValue;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String wireValue() {
            return wireValue;
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static StreamEventType fromWire(String value) {
            for (StreamEventType candidate : values()) {
                if (candidate.wireValue.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown stream event type: " + value);
        }
    }

    public enum AgentRunProtocol {
        V1("agent_stream.v1"),
        V2("agent-stream.v2");

        private final String wireValue;

        AgentRunProtocol(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public enum AgentRunExecutorKind {
        LEGACY_WORKER,
        TEMPORAL_ACTIVITY
    }

    public enum AgentRunAttemptStatus {
        PENDING,
        RUNNING,
        RESULT_READY,
        COMPLETED,
        FAILED,
        ABORTED,
        CANCELLED
    }

    public enum AgentRunRecoveryAction {
        RETRY_SAME_COMMAND,
        CREATE_NEXT_ATTEMPT,
        RECONCILE_TERMINAL,
        FAIL_LOGICAL_RUN
    }

    public enum GraphStatus {
        COMPLETED,
        NEEDS_INPUT,
        NEEDS_REVIEW,
        FAILED
    }

    public enum ArtifactOperationType {
        PROPOSE_CREATE,
        PROPOSE_PATCH
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActorRef(String actorId, ActorRole actorRole, List<String> actorScopes) {
        public ActorRef {
            required(actorId, "actorId");
            required(actorRole, "actorRole");
            actorScopes = immutableList(actorScopes, "actorScopes");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PayloadRef(String schemaVersion, String uri, String sha256, long sizeBytes) {
        public PayloadRef {
            required(schemaVersion, "schemaVersion");
            required(uri, "uri");
            required(sha256, "sha256");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParentRef(String artifactId, String contentHash) {
        public ParentRef {
            required(artifactId, "artifactId");
            required(contentHash, "contentHash");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(long inputTokens, long outputTokens, long totalTokens) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArtifactPointer(
            String artifactId, String schemaVersion, String uri, String sha256) {
        public ArtifactPointer {
            required(artifactId, "artifactId");
            required(schemaVersion, "schemaVersion");
            required(uri, "uri");
            required(sha256, "sha256");
        }
    }
}
