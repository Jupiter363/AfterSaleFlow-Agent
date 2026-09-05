package com.example.dispute.workflow.projection.evidence;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.regex.Pattern;

/** Frozen Evidence process-projection wire contract. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EvidenceProcessProjectionView(
        String schemaVersion,
        String projectionHash,
        String projectionState,
        String tenantSurrogate,
        String caseId,
        String roomId,
        long roomEpoch,
        long fencingToken,
        String writerMode,
        String graphRuntimeMode,
        boolean formalSinkAllowed,
        boolean temporalEvidenceAllocationAllowed,
        boolean realCaseShadowAllowed,
        String viewerActorId,
        String viewerActorRole,
        String viewerScopeHash,
        String audience,
        String roomPhase,
        String terminalReason,
        String pendingState,
        String pendingOperationKey,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime originalDeadlineAt,
        boolean warningSent,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime warningSentAt,
        PartyCompletion partyCompletion,
        AssessmentCounts assessmentCounts,
        Long dossierVersion,
        boolean historyMode,
        long lastEventSequence,
        ActiveGraphRun activeGraphRun,
        TerminalProposal terminalProposal,
        Recovery recovery,
        VersionPins versionPins,
        long processRevision,
        long roomRevision,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime projectedAt) {

    public static final String SCHEMA_VERSION = "evidence-process-projection.v1";
    public static final String PRODUCTION_SCHEMA_VERSION = "evidence-process-projection.v2";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String PROCESSING = "PROCESSING";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern IDENTIFIER =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern OPERATION_KEY = Pattern.compile(
            "^evidence\\.(?:manifest\\.issue|graph\\.request|party\\.complete|deadline\\.(?:warn|expire)|batch\\.merge|dossier\\.freeze|hearing\\.open):[A-Za-z0-9._:-]{1,448}$");
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    public EvidenceProcessProjectionView {
        requireEnum(schemaVersion, Set.of(SCHEMA_VERSION, PRODUCTION_SCHEMA_VERSION), "schemaVersion");
        requireHash(projectionHash, "projectionHash");
        requireEnum(
                projectionState,
                Set.of(AVAILABLE, PROCESSING, UNAVAILABLE, "FAILED"),
                "projectionState");
        requireIdentifier(tenantSurrogate, "tenantSurrogate");
        requireIdentifier(caseId, "caseId");
        requireNullableIdentifier(roomId, "roomId");
        requireNonNegative(roomEpoch, "roomEpoch");
        requireNonNegative(fencingToken, "fencingToken");
        requireEnum(writerMode, Set.of("LEGACY", "SHADOW", "TEMPORAL"), "writerMode");
        requireEnum(
                graphRuntimeMode,
                Set.of("DISABLED", "SIGNED_SYNTHETIC_SHADOW", "PRODUCTION"),
                "graphRuntimeMode");
        if ("TEMPORAL".equals(writerMode)) {
            if (formalSinkAllowed
                    || !temporalEvidenceAllocationAllowed
                    || realCaseShadowAllowed) {
                throw new IllegalArgumentException(
                        "target Evidence projection authority flags are invalid");
            }
        } else if (formalSinkAllowed
                || temporalEvidenceAllocationAllowed
                || realCaseShadowAllowed) {
            throw new IllegalArgumentException(
                    "legacy and shadow Evidence projection authority flags must be false");
        }
        requireIdentifier(viewerActorId, "viewerActorId");
        requireViewerRole(viewerActorRole, "viewerActorRole");
        requireHash(viewerScopeHash, "viewerScopeHash");
        requireViewerRole(audience, "audience");
        requireEquals(audience, viewerActorRole, "audience");
        requireEnum(
                roomPhase,
                Set.of("OPEN", "WAITING_PARTIES", "ASSESSING", "READY_TO_FREEZE", "COMPLETED"),
                "roomPhase");
        requireNullableEnum(
                terminalReason,
                Set.of("BOTH_PARTIES_COMPLETED", "DEADLINE_EXPIRED", "ADMISSION_FAILED", "CANCELLED"),
                "terminalReason");
        requireEnum(
                pendingState,
                Set.of("NONE", "WAITING_PARTY", "WAITING_TIMER", "AGENT_RUNNING", "REVIEW_PENDING", "FAILED"),
                "pendingState");
        requireNullableOperationKey(pendingOperationKey, "pendingOperationKey");
        requireNonNull(originalDeadlineAt, "originalDeadlineAt");
        if (warningSent != (warningSentAt != null)) {
            throw new IllegalArgumentException("warningSent must match warningSentAt");
        }
        requireNonNull(partyCompletion, "partyCompletion");
        requireNonNull(assessmentCounts, "assessmentCounts");
        if (dossierVersion != null && dossierVersion < 1) {
            throw new IllegalArgumentException("dossierVersion must be positive");
        }
        requireNonNegative(lastEventSequence, "lastEventSequence");
        requireNonNull(recovery, "recovery");
        requireNonNull(versionPins, "versionPins");
        if (PRODUCTION_SCHEMA_VERSION.equals(schemaVersion)) {
            requireEquals(writerMode, "TEMPORAL", "v2 writerMode");
        } else if ("TEMPORAL".equals(writerMode)) {
            // Preserve the frozen v1 reader; activation-bound live profiles use explicit v2.
            requireEquals(versionPins.modelProfileId(), "production-runtime.contract-blocked",
                    "v1 target modelProfileId");
        }
        requireNonNegative(processRevision, "processRevision");
        requireNonNegative(roomRevision, "roomRevision");
        requireNonNull(projectedAt, "projectedAt");

        validateWriterMode(
                writerMode,
                projectionState,
                graphRuntimeMode,
                tenantSurrogate,
                caseId,
                roomId,
                roomEpoch,
                fencingToken,
                activeGraphRun,
                versionPins);
        validatePhase(
                caseId,
                roomEpoch,
                roomPhase,
                terminalReason,
                pendingState,
                pendingOperationKey,
                activeGraphRun,
                terminalProposal,
                assessmentCounts);
        if (historyMode
                && (activeGraphRun != null
                        || pendingOperationKey != null
                        || !"NONE".equals(pendingState))) {
            throw new IllegalArgumentException("history projection must be read-only");
        }
        if ("DISABLED".equals(graphRuntimeMode) && activeGraphRun != null) {
            throw new IllegalArgumentException("disabled graph runtime cannot expose an active run");
        }
        if ("FAILED".equals(projectionState) || "FAILED".equals(pendingState)) {
            if (!"FAILED".equals(recovery.state())
                    || activeGraphRun != null
                    || pendingOperationKey != null) {
                throw new IllegalArgumentException("failed projection requires failed recovery");
            }
        }
    }

    public EvidenceProcessProjectionView withComputedHash() {
        ObjectNode value = JSON.valueToTree(this);
        value.remove("projection_hash");
        return withProjectionHash(ContractJson.sha256Hex(value));
    }

    private EvidenceProcessProjectionView withProjectionHash(String hash) {
        return new EvidenceProcessProjectionView(
                schemaVersion,
                hash,
                projectionState,
                tenantSurrogate,
                caseId,
                roomId,
                roomEpoch,
                fencingToken,
                writerMode,
                graphRuntimeMode,
                formalSinkAllowed,
                temporalEvidenceAllocationAllowed,
                realCaseShadowAllowed,
                viewerActorId,
                viewerActorRole,
                viewerScopeHash,
                audience,
                roomPhase,
                terminalReason,
                pendingState,
                pendingOperationKey,
                originalDeadlineAt,
                warningSent,
                warningSentAt,
                partyCompletion,
                assessmentCounts,
                dossierVersion,
                historyMode,
                lastEventSequence,
                activeGraphRun,
                terminalProposal,
                recovery,
                versionPins,
                processRevision,
                roomRevision,
                projectedAt);
    }

    private static void validateWriterMode(
            String writerMode,
            String projectionState,
            String graphRuntimeMode,
            String tenantSurrogate,
            String caseId,
            String roomId,
            long roomEpoch,
            long fencingToken,
            ActiveGraphRun activeGraphRun,
            VersionPins pins) {
        if ("LEGACY".equals(writerMode)) {
            if (!UNAVAILABLE.equals(projectionState)
                    || !"DISABLED".equals(graphRuntimeMode)
                    || roomId != null
                    || roomEpoch != 0
                    || fencingToken != 0
                    || activeGraphRun != null
                    || pins.hasRuntimePins()) {
                throw new IllegalArgumentException("legacy projection must remain unavailable");
            }
            return;
        }
        if ("TEMPORAL".equals(writerMode)) {
            if (!"PRODUCTION".equals(graphRuntimeMode)
                    || roomId == null
                    || fencingToken < 1
                    || !pins.hasTargetComposite()
                    || (activeGraphRun != null
                            && (!pins.graphVersion().equals(activeGraphRun.graphVersion())
                                    || !pins.checkpointSchemaVersion()
                                            .equals(activeGraphRun.checkpointSchemaVersion())))) {
                throw new IllegalArgumentException(
                        "target Evidence projection authority is invalid");
            }
            return;
        }
        if (!"SIGNED_SYNTHETIC_SHADOW".equals(graphRuntimeMode)
                || !tenantSurrogate.startsWith("TENANT_P5_SYNTHETIC_")
                || !caseId.startsWith("CASE_P5_SYNTHETIC_")
                || roomId == null
                || fencingToken < 1
                || !pins.hasRuntimePins()
                || !"evidence-graph-state.v2".equals(pins.stateSchemaVersion())) {
            throw new IllegalArgumentException("shadow projection must be signed synthetic only");
        }
    }

    private static void validatePhase(
            String caseId,
            long roomEpoch,
            String roomPhase,
            String terminalReason,
            String pendingState,
            String pendingOperationKey,
            ActiveGraphRun activeGraphRun,
            TerminalProposal terminalProposal,
            AssessmentCounts counts) {
        if ("COMPLETED".equals(roomPhase)) {
            if (terminalReason == null
                    || !"NONE".equals(pendingState)
                    || pendingOperationKey != null
                    || activeGraphRun != null) {
                throw new IllegalArgumentException("completed projection has invalid terminal state");
            }
        } else if (terminalReason != null) {
            throw new IllegalArgumentException("nonterminal projection cannot have terminalReason");
        }
        if ("AGENT_RUNNING".equals(pendingState)) {
            if (activeGraphRun == null
                    || !activeGraphRun
                            .expectedOperationKey(caseId, roomEpoch)
                            .equals(pendingOperationKey)) {
                throw new IllegalArgumentException("active graph operation binding mismatch");
            }
        }
        if ("READY_TO_FREEZE".equals(roomPhase)) {
            if (terminalProposal == null
                    || counts.pendingCount() != 0
                    || counts.failedCount() != 0
                    || counts.completedCount() + counts.needsReviewCount()
                            != counts.manifestItemCount()) {
                throw new IllegalArgumentException("ready projection requires complete coverage");
            }
        } else if (terminalProposal != null && !"COMPLETED".equals(roomPhase)) {
            throw new IllegalArgumentException("terminal proposal is not allowed in this phase");
        }
    }

    private static void requireViewerRole(String value, String field) {
        requireEnum(value, Set.of("USER", "MERCHANT", "PLATFORM_REVIEWER"), field);
    }

    private static void requireEnum(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireNullableEnum(String value, Set<String> allowed, String field) {
        if (value != null) {
            requireEnum(value, allowed, field);
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be an identifier");
        }
    }

    private static void requireNullableIdentifier(String value, String field) {
        if (value != null) {
            requireIdentifier(value, field);
        }
    }

    private static void requireNullableOperationKey(String value, String field) {
        if (value != null && (value.length() > 512 || !OPERATION_KEY.matcher(value).matches())) {
            throw new IllegalArgumentException(field + " must be an Evidence operation key");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static void requireEquals(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must equal " + expected);
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PartyCompletion(
            boolean initiatorCompleted,
            boolean respondentCompleted,
            String initiatorReceiptRef,
            String initiatorReceiptHash,
            String respondentReceiptRef,
            String respondentReceiptHash) {

        public PartyCompletion {
            requireNullableIdentifier(initiatorReceiptRef, "initiatorReceiptRef");
            requireNullableHash(initiatorReceiptHash, "initiatorReceiptHash");
            requireNullableIdentifier(respondentReceiptRef, "respondentReceiptRef");
            requireNullableHash(respondentReceiptHash, "respondentReceiptHash");
            requireCompletionReceipt(
                    initiatorCompleted,
                    initiatorReceiptRef,
                    initiatorReceiptHash,
                    "initiator");
            requireCompletionReceipt(
                    respondentCompleted,
                    respondentReceiptRef,
                    respondentReceiptHash,
                    "respondent");
        }

        public static PartyCompletion pending() {
            return new PartyCompletion(false, false, null, null, null, null);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AssessmentCounts(
            int manifestItemCount,
            int completedCount,
            int needsReviewCount,
            int failedCount,
            int pendingCount) {

        public AssessmentCounts {
            if (manifestItemCount < 0
                    || manifestItemCount > 100
                    || completedCount < 0
                    || completedCount > 100
                    || needsReviewCount < 0
                    || needsReviewCount > 100
                    || failedCount < 0
                    || failedCount > 100
                    || pendingCount < 0
                    || pendingCount > 100
                    || completedCount + needsReviewCount + failedCount + pendingCount
                            != manifestItemCount) {
                throw new IllegalArgumentException("assessment counts are inconsistent");
            }
        }

        public static AssessmentCounts empty() {
            return new AssessmentCounts(0, 0, 0, 0, 0);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActiveGraphRun(
            String commandId,
            String logicalRunId,
            String attemptId,
            String manifestId,
            String manifestHash,
            String graphVersion,
            String checkpointSchemaVersion,
            String status) {

        public ActiveGraphRun {
            requireIdentifier(commandId, "commandId");
            requireIdentifier(logicalRunId, "logicalRunId");
            requireIdentifier(attemptId, "attemptId");
            requireIdentifier(manifestId, "manifestId");
            requireHash(manifestHash, "manifestHash");
            requireIdentifier(graphVersion, "graphVersion");
            requireIdentifier(checkpointSchemaVersion, "checkpointSchemaVersion");
            requireEnum(status, Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED"), "status");
        }

        String expectedOperationKey(String caseId, long roomEpoch) {
            return "evidence.graph.request:"
                    + caseId
                    + ':'
                    + roomEpoch
                    + ':'
                    + manifestHash
                    + ':'
                    + logicalRunId;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TerminalProposal(String proposalRef, String proposalHash) {
        public TerminalProposal {
            requireIdentifier(proposalRef, "proposalRef");
            requireHash(proposalHash, "proposalHash");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Recovery(
            String state, boolean retryable, String checkpointRef, String checkpointHash) {
        public Recovery {
            requireEnum(state, Set.of("NONE", "RESUMABLE", "RECONCILING", "FAILED"), "state");
            requireNullableIdentifier(checkpointRef, "checkpointRef");
            requireNullableHash(checkpointHash, "checkpointHash");
            if ("NONE".equals(state)
                    && (retryable || checkpointRef != null || checkpointHash != null)) {
                throw new IllegalArgumentException("NONE recovery cannot retry or retain a checkpoint");
            }
            if (Set.of("RESUMABLE", "RECONCILING").contains(state)
                    && (!retryable || checkpointRef == null || checkpointHash == null)) {
                throw new IllegalArgumentException(
                        "resumable recovery requires retryable checkpoint evidence");
            }
        }

        public static Recovery none() {
            return new Recovery("NONE", false, null, null);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VersionPins(
            String workflowBuildId,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String promptVersion,
            String modelProfileId,
            String assessmentOutputSchemaVersion,
            String terminalOutputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion) {

        public VersionPins {
            requireNullableIdentifier(workflowBuildId, "workflowBuildId");
            requireNullableIdentifier(graphVersion, "graphVersion");
            requireNullableIdentifier(checkpointSchemaVersion, "checkpointSchemaVersion");
            if (stateSchemaVersion != null
                    && !"evidence-graph-state.v2".equals(stateSchemaVersion)) {
                throw new IllegalArgumentException("stateSchemaVersion is invalid");
            }
            boolean hasAnyRuntimePin = workflowBuildId != null
                    || graphVersion != null
                    || checkpointSchemaVersion != null
                    || stateSchemaVersion != null;
            boolean hasAllRuntimePins = workflowBuildId != null
                    && graphVersion != null
                    && checkpointSchemaVersion != null
                    && stateSchemaVersion != null;
            if (hasAnyRuntimePin != hasAllRuntimePins) {
                throw new IllegalArgumentException("runtime pins must be all present or all absent");
            }
            requireIdentifier(promptVersion, "promptVersion");
            requireIdentifier(modelProfileId, "modelProfileId");
            requireEquals(
                    assessmentOutputSchemaVersion,
                    "evidence-item-assessment.v1",
                    "assessmentOutputSchemaVersion");
            requireEquals(
                    terminalOutputSchemaVersion,
                    "evidence-batch-proposal.v1",
                    "terminalOutputSchemaVersion");
            requireIdentifier(policyVersion, "policyVersion");
            requireIdentifier(guardrailVersion, "guardrailVersion");
            requireIdentifier(toolPolicyVersion, "toolPolicyVersion");
        }

        public static VersionPins legacy() {
            return new VersionPins(
                    null,
                    null,
                    null,
                    null,
                    "evidence-prompt.v2",
                    "evidence-model.synthetic.v1",
                    "evidence-item-assessment.v1",
                    "evidence-batch-proposal.v1",
                    "evidence-policy.v2",
                    "evidence-guardrail.v2",
                    "evidence-tools.synthetic.v1");
        }

        public static VersionPins shadow(
                String workflowBuildId, String graphVersion, String checkpointSchemaVersion) {
            return new VersionPins(
                    workflowBuildId,
                    graphVersion,
                    checkpointSchemaVersion,
                    "evidence-graph-state.v2",
                    "evidence-prompt.v2",
                    "evidence-model.synthetic.v1",
                    "evidence-item-assessment.v1",
                    "evidence-batch-proposal.v1",
                    "evidence-policy.v2",
                    "evidence-guardrail.v2",
                    "evidence-tools.synthetic.v1");
        }

        public static VersionPins target(
                String workflowBuildId,
                String graphVersion,
                String checkpointSchemaVersion,
                String promptVersion,
                String modelProfileId,
                String policyVersion,
                String guardrailVersion,
                String toolPolicyVersion) {
            if (!TargetTypedRoomProtocol.supportsGraphVersion(graphVersion)) {
                throw new IllegalArgumentException("target graphVersion is not supported");
            }
            requireEquals(
                    checkpointSchemaVersion,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    "target checkpointSchemaVersion");
            requireEquals(
                    promptVersion,
                    "all-rooms-prompt.production-runtime.v2",
                    "target promptVersion");
            // The adapter binds this opaque identifier to the active deployment profile.
            // A view must not replace that authority with a hard-coded disabled provider.
            requireIdentifier(modelProfileId, "target modelProfileId");
            requireEquals(
                    policyVersion,
                    "all-rooms-policy.production-runtime.v1",
                    "target policyVersion");
            requireEquals(
                    guardrailVersion,
                    "all-rooms-guardrail.production-runtime.v1",
                    "target guardrailVersion");
            requireEquals(toolPolicyVersion, "tools.none.v1", "target toolPolicyVersion");
            return new VersionPins(
                    workflowBuildId,
                    graphVersion,
                    checkpointSchemaVersion,
                    "evidence-graph-state.v2",
                    promptVersion,
                    modelProfileId,
                    "evidence-item-assessment.v1",
                    "evidence-batch-proposal.v1",
                    policyVersion,
                    guardrailVersion,
                    toolPolicyVersion);
        }

        boolean hasRuntimePins() {
            return workflowBuildId != null
                    || graphVersion != null
                    || checkpointSchemaVersion != null
                    || stateSchemaVersion != null;
        }

        boolean hasTargetComposite() {
            return hasRuntimePins()
                    && TargetTypedRoomProtocol.supportsGraphVersion(graphVersion)
                    && TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION.equals(
                            checkpointSchemaVersion)
                    && "evidence-graph-state.v2".equals(stateSchemaVersion)
                    && "all-rooms-prompt.production-runtime.v2".equals(promptVersion)
                    && IDENTIFIER.matcher(modelProfileId).matches()
                    && "evidence-item-assessment.v1".equals(assessmentOutputSchemaVersion)
                    && "evidence-batch-proposal.v1".equals(terminalOutputSchemaVersion)
                    && "all-rooms-policy.production-runtime.v1".equals(policyVersion)
                    && "all-rooms-guardrail.production-runtime.v1".equals(guardrailVersion)
                    && "tools.none.v1".equals(toolPolicyVersion);
        }
    }

    private static void requireNullableHash(String value, String field) {
        if (value != null) {
            requireHash(value, field);
        }
    }

    private static void requireCompletionReceipt(
            boolean completed, String receiptRef, String receiptHash, String party) {
        boolean hasReceipt = receiptRef != null || receiptHash != null;
        if (completed != hasReceipt || (hasReceipt && (receiptRef == null || receiptHash == null))) {
            throw new IllegalArgumentException(
                    party + " completion must have a complete receipt or no receipt");
        }
    }
}
