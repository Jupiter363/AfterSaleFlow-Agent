package com.example.dispute.workflow.contract.v1;

import static com.example.dispute.workflow.contract.v1.ContractTypes.immutableList;
import static com.example.dispute.workflow.contract.v1.ContractTypes.required;
import static com.example.dispute.workflow.contract.v1.ContractTypes.version;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RoomGraphCommand(
        String schemaVersion,
        String commandId,
        String logicalRunId,
        String attemptId,
        String tenantSurrogate,
        String caseId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String roomId,
        RoomType roomType,
        long roomEpoch,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String threadId,
        ActorScope actorScope,
        long processRevision,
        String stageCode,
        long stageSequence,
        SnapshotRef domainSnapshotRef,
        SnapshotRef eventRef,
        InvocationContext invocationContext,
        RetryBudget retryBudget,
        Instant deadlineAt,
        String traceparent,
        String requestHash) {

    public static final int MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT = 2;
    public static final int PARALLEL_INTAKE_PROVIDER_ATTEMPT_LIMIT = 6;
    public static final int HEARING_EVIDENCE_SYNTHESIS_PROVIDER_ATTEMPT_LIMIT = 202;
    public static final String PARALLEL_INTAKE_AGENT_PROFILE_ID =
            "dispute-intake-officer.parallel-frames.v1";
    public static final String PARALLEL_INTAKE_OUTPUT_SCHEMA =
            "target-e2e-room-proposal-source.v2";

    /** Legacy constructor kept so non-parallel command producers preserve their canonical shape. */
    public RoomGraphCommand(
            String schemaVersion,
            String commandId,
            String logicalRunId,
            String attemptId,
            String tenantSurrogate,
            String caseId,
            RoomType roomType,
            long roomEpoch,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String threadId,
            ActorScope actorScope,
            long processRevision,
            String stageCode,
            long stageSequence,
            SnapshotRef domainSnapshotRef,
            SnapshotRef eventRef,
            InvocationContext invocationContext,
            RetryBudget retryBudget,
            Instant deadlineAt,
            String traceparent,
            String requestHash) {
        this(
                schemaVersion,
                commandId,
                logicalRunId,
                attemptId,
                tenantSurrogate,
                caseId,
                null,
                roomType,
                roomEpoch,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                threadId,
                actorScope,
                processRevision,
                stageCode,
                stageSequence,
                domainSnapshotRef,
                eventRef,
                invocationContext,
                retryBudget,
                deadlineAt,
                traceparent,
                requestHash);
    }

    public RoomGraphCommand {
        schemaVersion = version(schemaVersion, "room-graph-command.v1");
        required(commandId, "commandId");
        required(logicalRunId, "logicalRunId");
        required(attemptId, "attemptId");
        required(tenantSurrogate, "tenantSurrogate");
        required(caseId, "caseId");
        required(roomType, "roomType");
        required(graphKey, "graphKey");
        required(graphVersion, "graphVersion");
        required(checkpointSchemaVersion, "checkpointSchemaVersion");
        required(threadId, "threadId");
        required(actorScope, "actorScope");
        required(stageCode, "stageCode");
        required(domainSnapshotRef, "domainSnapshotRef");
        required(invocationContext, "invocationContext");
        required(retryBudget, "retryBudget");
        boolean reservedParallel =
                PARALLEL_INTAKE_AGENT_PROFILE_ID.equals(invocationContext.agentProfileId())
                        || roomId != null;
        boolean exactParallel =
                roomType == RoomType.INTAKE
                        && PARALLEL_INTAKE_AGENT_PROFILE_ID.equals(
                                invocationContext.agentProfileId())
                        && PARALLEL_INTAKE_OUTPUT_SCHEMA.equals(
                                invocationContext.outputSchemaVersion())
                        && eventRef != null
                        && roomId != null
                        && !roomId.isBlank()
                        && (actorScope.actorRole() == ActorRole.USER
                                || actorScope.actorRole() == ActorRole.MERCHANT)
                        && actorScope.audience().name().equals(actorScope.actorRole().name())
                        && retryBudget.providerAttemptsRemaining() >= 3
                        && retryBudget.providerAttemptsRemaining()
                                <= PARALLEL_INTAKE_PROVIDER_ATTEMPT_LIMIT;
        if (reservedParallel && !exactParallel) {
            throw new IllegalArgumentException(
                    "parallel Intake command authority is incomplete");
        }
        if (!exactParallel) {
            if (roomId != null) {
                throw new IllegalArgumentException(
                        "roomId is reserved for parallel Intake commands");
            }
            if (retryBudget.providerAttemptsRemaining()
                            > MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT
                    && (roomType != RoomType.HEARING
                            || !"EVIDENCE_SYNTHESIZING".equals(stageCode))) {
                throw new IllegalArgumentException(
                        "aggregate provider budget is reserved for Hearing evidence synthesis");
            }
        }
        required(deadlineAt, "deadlineAt");
        required(traceparent, "traceparent");
        required(requestHash, "requestHash");
    }

    @JsonIgnore
    public boolean isExactParallelIntakeProfile() {
        return roomType == RoomType.INTAKE
                && roomId != null
                && eventRef != null
                && PARALLEL_INTAKE_AGENT_PROFILE_ID.equals(
                        invocationContext.agentProfileId())
                && PARALLEL_INTAKE_OUTPUT_SCHEMA.equals(
                        invocationContext.outputSchemaVersion())
                && retryBudget.providerAttemptsRemaining() >= 3
                && retryBudget.providerAttemptsRemaining()
                        <= PARALLEL_INTAKE_PROVIDER_ATTEMPT_LIMIT;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActorScope(
            String actorId, ActorRole actorRole, Audience audience, List<String> capabilities) {
        public ActorScope {
            required(actorId, "actorId");
            required(actorRole, "actorRole");
            required(audience, "audience");
            capabilities = immutableList(capabilities, "capabilities");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SnapshotRef(
            String artifactId, String schemaVersion, String uri, String sha256, long sizeBytes) {
        public SnapshotRef {
            required(artifactId, "artifactId");
            required(schemaVersion, "schemaVersion");
            required(uri, "uri");
            required(sha256, "sha256");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record InvocationContext(
            String agentProfileId,
            String promptProfileId,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            List<String> toolCapabilities,
            String envelopeKeyId,
            String envelopeNonce) {
        public InvocationContext {
            required(agentProfileId, "agentProfileId");
            required(promptProfileId, "promptProfileId");
            required(modelProfileId, "modelProfileId");
            required(outputSchemaVersion, "outputSchemaVersion");
            required(policyVersion, "policyVersion");
            required(guardrailVersion, "guardrailVersion");
            toolCapabilities = immutableList(toolCapabilities, "toolCapabilities");
            required(envelopeKeyId, "envelopeKeyId");
            required(envelopeNonce, "envelopeNonce");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetryBudget(
            int providerAttemptsRemaining,
            int activityAttemptsRemaining,
            int repairsRemaining) {
        public RetryBudget {
            if (providerAttemptsRemaining < 0
                    || providerAttemptsRemaining
                            > HEARING_EVIDENCE_SYNTHESIS_PROVIDER_ATTEMPT_LIMIT) {
                throw new IllegalArgumentException("providerAttemptsRemaining is out of range");
            }
            if (activityAttemptsRemaining < 0 || activityAttemptsRemaining > 3) {
                throw new IllegalArgumentException("activityAttemptsRemaining is out of range");
            }
            if (repairsRemaining < 0 || repairsRemaining > 1) {
                throw new IllegalArgumentException("repairsRemaining is out of range");
            }
        }
    }
}
