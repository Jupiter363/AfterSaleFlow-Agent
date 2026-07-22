package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.MessageRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Classification;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Dimension;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.HardZeroFinding;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact Java model of the engineering-only signed-synthetic runtime material manifest. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntakeRuntimeMaterialManifest(
        String schemaVersion,
        String manifestId,
        String trafficSource,
        String roomType,
        String writerMode,
        boolean syntheticOnly,
        boolean containsRealPartyData,
        boolean formalEffectsAllowed,
        String outputSink,
        AuthorityBinding authorityBinding,
        VersionPins versionPins,
        SnapshotMaterial snapshotMaterial,
        GraphPlan graphPlan,
        GraphArtifacts graphArtifacts,
        ParityMaterial parityMaterial,
        Instant createdAt,
        String manifestHash) {

    public static final String SCHEMA_VERSION =
            "intake-synthetic-runtime-material-manifest.v1";
    public static final int MAX_ENCODED_BYTES = 128 * 1024;
    public static final int SNAPSHOT_MAX_BYTES = 256 * 1024;
    public static final int GRAPH_ARTIFACT_MAX_BYTES = 64 * 1024;
    public static final int PARITY_BASELINE_MAX_BYTES = 64 * 1024;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern THREAD_ID = Pattern.compile("grt\\.v1\\.[0-9a-f]{32}");

    public IntakeRuntimeMaterialManifest {
        exact(schemaVersion, SCHEMA_VERSION, "schemaVersion");
        identifier(manifestId, "manifestId");
        exact(trafficSource, "SIGNED_SYNTHETIC", "trafficSource");
        exact(roomType, "INTAKE", "roomType");
        exact(writerMode, "SHADOW", "writerMode");
        if (!syntheticOnly || containsRealPartyData || formalEffectsAllowed) {
            throw new SecurityException(
                    "runtime material manifest is not restricted to synthetic comparison");
        }
        exact(outputSink, "ISOLATED_COMPARISON_LEDGER", "outputSink");
        Objects.requireNonNull(authorityBinding, "authorityBinding must not be null");
        Objects.requireNonNull(versionPins, "versionPins must not be null");
        Objects.requireNonNull(snapshotMaterial, "snapshotMaterial must not be null");
        Objects.requireNonNull(graphPlan, "graphPlan must not be null");
        Objects.requireNonNull(graphArtifacts, "graphArtifacts must not be null");
        Objects.requireNonNull(parityMaterial, "parityMaterial must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        sha256(manifestHash, "manifestHash");
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AuthorityBinding(
            String schemaVersion,
            String admissionSchemaVersion,
            String admissionStatus,
            String epochId,
            String partyAuthorityId,
            String caseCommandId,
            String payloadAuthorityId,
            String accessSessionId,
            String registrationId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String actorId,
            ActorRole actorRole,
            String commandId,
            long commandSequence,
            IntakeCommandType commandType,
            IntakeParty party,
            String actorScopeHash,
            String commandPayloadRef,
            String commandPayloadHash,
            String commandOperationKey,
            String requestHash,
            long acceptedRoomRevision,
            String threadId,
            String agentSessionId,
            long processRevision,
            long roomRevision,
            long deadlineEpochMillis,
            String logicalRunId,
            String attemptId,
            String selectionHash,
            String registrationHash,
            String authorizationHash,
            String authorityBindingHash) {

        public AuthorityBinding {
            exact(schemaVersion, "intake-synthetic-authority-binding.v1", "authority schema");
            exact(admissionSchemaVersion, "intake-synthetic-activity-admission.v1",
                    "admission schema");
            exact(admissionStatus, "VERIFIED", "admissionStatus");
            identifier(epochId, "epochId");
            identifier(partyAuthorityId, "partyAuthorityId");
            identifier(caseCommandId, "caseCommandId");
            identifier(payloadAuthorityId, "payloadAuthorityId");
            identifier(accessSessionId, "accessSessionId");
            identifier(registrationId, "registrationId");
            identifier(tenantSurrogate, "tenantSurrogate");
            identifier(caseId, "caseId");
            nonNegative(roomEpoch, "roomEpoch");
            positive(fencingToken, "fencingToken");
            identifier(actorId, "actorId");
            if (actorRole != ActorRole.USER && actorRole != ActorRole.MERCHANT) {
                throw new IllegalArgumentException("actorRole must be USER or MERCHANT");
            }
            identifier(commandId, "commandId");
            positive(commandSequence, "commandSequence");
            if (commandType != IntakeCommandType.INTAKE_MESSAGE) {
                throw new IllegalArgumentException("only synthetic Intake messages are supported");
            }
            Objects.requireNonNull(party, "party must not be null");
            sha256(actorScopeHash, "actorScopeHash");
            bounded(commandPayloadRef, 1024, "commandPayloadRef");
            sha256(commandPayloadHash, "commandPayloadHash");
            bounded(commandOperationKey, 512, "commandOperationKey");
            exact(
                    commandOperationKey,
                    "intake.operation:" + caseId + ":" + commandId,
                    "commandOperationKey");
            sha256(requestHash, "requestHash");
            nonNegative(acceptedRoomRevision, "acceptedRoomRevision");
            IntakeRuntimeMaterialManifest.threadId(threadId);
            identifier(agentSessionId, "agentSessionId");
            nonNegative(processRevision, "processRevision");
            nonNegative(roomRevision, "roomRevision");
            positive(deadlineEpochMillis, "deadlineEpochMillis");
            identifier(logicalRunId, "logicalRunId");
            identifier(attemptId, "attemptId");
            sha256(selectionHash, "selectionHash");
            sha256(registrationHash, "registrationHash");
            sha256(authorizationHash, "authorizationHash");
            sha256(authorityBindingHash, "authorityBindingHash");
        }

        public void requireExact(ActivityAuthority authority) {
            var envelope = Objects.requireNonNull(authority, "authority").envelope();
            if (!tenantSurrogate.equals(envelope.tenantSurrogate())
                    || !caseId.equals(envelope.caseId())
                    || roomEpoch != envelope.roomEpoch()
                    || fencingToken != envelope.fencingToken()
                    || !commandId.equals(envelope.commandId())
                    || commandSequence != envelope.commandSequence()
                    || commandType != envelope.commandType()
                    || party != envelope.party()
                    || !actorScopeHash.equals(envelope.actorScopeHash())
                    || !commandPayloadRef.equals(envelope.commandPayloadRef())
                    || !commandPayloadHash.equals(envelope.commandPayloadHash())
                    || !requestHash.equals(authority.requestHash())
                    || processRevision != envelope.processRevision()
                    || roomRevision != envelope.roomRevision()
                    || acceptedRoomRevision != envelope.roomRevision()
                    || deadlineEpochMillis != envelope.deadlineEpochMillis()
                    || !threadId.equals(authority.threadId())
                    || !agentSessionId.equals(authority.agentSessionId())) {
                throw new SecurityException(
                        "runtime material authority does not match the admitted activity");
            }
        }

        public Audience audience() {
            return actorRole == ActorRole.USER ? Audience.USER : Audience.MERCHANT;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VersionPins(
            String caseWorkflowType,
            String caseWorkflowBuildId,
            String roomWorkflowType,
            String roomWorkflowBuildId,
            String processContractVersion,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String streamProtocol,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion,
            String cohortPolicyVersion,
            String agentKey,
            String agentSessionProfileVersion,
            String memoryPolicyId,
            String pinSetHash) {

        public VersionPins {
            exact(caseWorkflowType, "CaseProcessWorkflow", "caseWorkflowType");
            identifier(caseWorkflowBuildId, "caseWorkflowBuildId");
            exact(roomWorkflowType, "IntakeRoomWorkflow", "roomWorkflowType");
            identifier(roomWorkflowBuildId, "roomWorkflowBuildId");
            identifier(processContractVersion, "processContractVersion");
            exact(graphKey, "intake.v2", "graphKey");
            identifier(graphVersion, "graphVersion");
            identifier(checkpointSchemaVersion, "checkpointSchemaVersion");
            exact(stateSchemaVersion, "intake-graph-state.v2", "stateSchemaVersion");
            exact(streamProtocol, "agent-stream.v2", "streamProtocol");
            identifier(promptVersion, "promptVersion");
            identifier(modelProfileId, "modelProfileId");
            exact(outputSchemaVersion, "intake-turn-proposal.v2", "outputSchemaVersion");
            identifier(policyVersion, "policyVersion");
            identifier(guardrailVersion, "guardrailVersion");
            exact(toolPolicyVersion, "no-tools.v1", "toolPolicyVersion");
            exact(cohortPolicyVersion, "synthetic-only.v1", "cohortPolicyVersion");
            exact(agentKey, "DISPUTE_INTAKE_OFFICER", "agentKey");
            exact(agentSessionProfileVersion, "agent-session-profile.v1",
                    "agentSessionProfileVersion");
            exact(memoryPolicyId, "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1", "memoryPolicyId");
            sha256(pinSetHash, "pinSetHash");
        }

        public void requireExact(PinnedVersions actual) {
            if (!roomWorkflowBuildId.equals(actual.workflowBuildId())
                    || !graphVersion.equals(actual.graphVersion())
                    || !checkpointSchemaVersion.equals(actual.checkpointSchemaVersion())
                    || !promptVersion.equals(actual.promptVersion())
                    || !modelProfileId.equals(actual.modelProfileId())
                    || !outputSchemaVersion.equals(actual.outputSchemaVersion())
                    || !policyVersion.equals(actual.policyVersion())
                    || !guardrailVersion.equals(actual.guardrailVersion())
                    || !toolPolicyVersion.equals(actual.toolPolicyVersion())) {
                throw new SecurityException(
                        "runtime material versions do not match the admitted pins");
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ArtifactReference(
            String artifactId,
            String artifactType,
            String schemaVersion,
            String objectUri,
            String objectVersion,
            String contentSha256,
            long sizeBytes,
            Instant storedAt) {

        public ArtifactReference {
            identifier(artifactId, "artifactId");
            identifier(artifactType, "artifactType");
            identifier(schemaVersion, "schemaVersion");
            com.example.dispute.workflow.application.intake.exchange.IntakeExchangeUris
                    .requireCanonical(objectUri);
            identifier(objectVersion, "objectVersion");
            sha256(contentSha256, "contentSha256");
            if (sizeBytes <= 0 || sizeBytes > maximumBytes(artifactType, schemaVersion)) {
                throw new IllegalArgumentException("artifact reference exceeds its byte bound");
            }
            Objects.requireNonNull(storedAt, "storedAt must not be null");
        }

        public void requireType(String expectedType, String expectedSchema) {
            exact(artifactType, expectedType, "artifactType");
            exact(schemaVersion, expectedSchema, "artifact schemaVersion");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SyntheticScalarField(String name, JsonNode value, String valueHash) {

        public SyntheticScalarField {
            if (name == null
                    || !name.matches("[a-z][a-z0-9_]{0,63}")
                    || forbiddenField(name)) {
                throw new IllegalArgumentException("synthetic scalar field name is forbidden");
            }
            if (value == null || (!value.isValueNode() && !value.isNull())) {
                throw new IllegalArgumentException("synthetic material permits scalar values only");
            }
            if (value.isTextual() && value.textValue().length() > 8192) {
                throw new IllegalArgumentException("synthetic scalar text exceeds its bound");
            }
            sha256(valueHash, "valueHash");
            value = value.deepCopy();
        }

        @Override
        public JsonNode value() {
            return value.deepCopy();
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OwnMessage(
            String messageId,
            MessageRole role,
            Audience audience,
            long sequence,
            String text,
            String sourceHash) {

        public OwnMessage {
            identifier(messageId, "messageId");
            Objects.requireNonNull(role, "role must not be null");
            if (audience != Audience.USER && audience != Audience.MERCHANT) {
                throw new IllegalArgumentException("message audience must be private");
            }
            nonNegative(sequence, "sequence");
            if (text == null || text.isEmpty() || text.length() > 8192) {
                throw new IllegalArgumentException("message text exceeds its bound");
            }
            sha256(sourceHash, "sourceHash");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SnapshotMaterial(
            ArtifactReference snapshot,
            long domainRevision,
            long roomRevision,
            long projectionRevision,
            String visibility,
            List<String> sourceRefs,
            List<SyntheticScalarField> initialCaseFacts,
            List<SyntheticScalarField> shareableProjection,
            List<OwnMessage> ownMessages,
            List<SyntheticScalarField> currentDossier,
            Instant createdAt) {

        public SnapshotMaterial {
            Objects.requireNonNull(snapshot, "snapshot must not be null")
                    .requireType("INTAKE_SNAPSHOT", "intake-domain-snapshot.v2");
            nonNegative(domainRevision, "domainRevision");
            nonNegative(roomRevision, "roomRevision");
            nonNegative(projectionRevision, "projectionRevision");
            exact(visibility, "PRIVATE_SYNTHETIC", "visibility");
            sourceRefs = identifiers(sourceRefs, 1, 128, "sourceRefs");
            initialCaseFacts = scalarFields(initialCaseFacts, "initialCaseFacts");
            shareableProjection = scalarFields(shareableProjection, "shareableProjection");
            ownMessages = List.copyOf(Objects.requireNonNull(ownMessages, "ownMessages"));
            if (ownMessages.size() > 6) {
                throw new IllegalArgumentException("ownMessages exceeds the six-message window");
            }
            currentDossier = scalarFields(currentDossier, "currentDossier");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GraphPlan(
            String logicalRunId,
            String attemptId,
            long attemptNo,
            int attemptLimit,
            String previousAttemptId,
            boolean resetRequired,
            int publicSequenceOffset,
            String stageCode,
            String agentProfileId,
            String operation,
            String logicalIdempotencyKey,
            String envelopeKeyId,
            String envelopeNonce) {

        public GraphPlan {
            identifier(logicalRunId, "logicalRunId");
            identifier(attemptId, "attemptId");
            if (attemptNo < 1 || attemptLimit < attemptNo || attemptLimit > 3) {
                throw new IllegalArgumentException("Graph attempt lineage is invalid");
            }
            if (previousAttemptId != null) {
                identifier(previousAttemptId, "previousAttemptId");
            }
            if (publicSequenceOffset < 0) {
                throw new IllegalArgumentException("publicSequenceOffset must not be negative");
            }
            exact(stageCode, "INTAKE_MESSAGE", "stageCode");
            exact(agentProfileId, "DISPUTE_INTAKE_OFFICER", "agentProfileId");
            exact(operation, "INTAKE_MESSAGE", "operation");
            bounded(logicalIdempotencyKey, 512, "logicalIdempotencyKey");
            identifier(envelopeKeyId, "envelopeKeyId");
            identifier(envelopeNonce, "envelopeNonce");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GraphArtifacts(ArtifactReference result, ArtifactReference proposal) {

        public GraphArtifacts {
            Objects.requireNonNull(result, "result must not be null")
                    .requireType("GRAPH_RESULT", "room-graph-result.v1");
            Objects.requireNonNull(proposal, "proposal must not be null")
                    .requireType("INTAKE_PROPOSAL", "intake-turn-proposal.v2");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ObservedValue(Classification classification, String valueHash) {

        public ObservedValue {
            Objects.requireNonNull(classification, "classification must not be null");
            sha256(valueHash, "valueHash");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParitySnapshot(
            ObservedValue schema,
            ObservedValue stableFacts,
            ObservedValue sourceHashMembership,
            ObservedValue readiness,
            ObservedValue normalizedPatch,
            ObservedValue recommendation,
            ObservedValue guardrail,
            ObservedValue terminal,
            ObservedValue privacy,
            Set<HardZeroFinding> hardZeroFindings) {

        public ParitySnapshot {
            Objects.requireNonNull(schema, "schema must not be null");
            Objects.requireNonNull(stableFacts, "stableFacts must not be null");
            Objects.requireNonNull(sourceHashMembership, "sourceHashMembership must not be null");
            Objects.requireNonNull(readiness, "readiness must not be null");
            Objects.requireNonNull(normalizedPatch, "normalizedPatch must not be null");
            Objects.requireNonNull(recommendation, "recommendation must not be null");
            Objects.requireNonNull(guardrail, "guardrail must not be null");
            Objects.requireNonNull(terminal, "terminal must not be null");
            Objects.requireNonNull(privacy, "privacy must not be null");
            hardZeroFindings = Set.copyOf(
                    Objects.requireNonNull(hardZeroFindings, "hardZeroFindings"));
            if (hardZeroFindings.size() > HardZeroFinding.values().length) {
                throw new IllegalArgumentException("too many hard-zero findings");
            }
        }

        public Map<Dimension, ObservedValue> values() {
            return Map.of(
                    Dimension.SCHEMA, schema,
                    Dimension.STABLE_FACTS, stableFacts,
                    Dimension.SOURCE_HASH_MEMBERSHIP, sourceHashMembership,
                    Dimension.READINESS, readiness,
                    Dimension.NORMALIZED_PATCH, normalizedPatch,
                    Dimension.RECOMMENDATION, recommendation,
                    Dimension.GUARDRAIL, guardrail,
                    Dimension.TERMINAL, terminal,
                    Dimension.PRIVACY, privacy);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParityMaterial(
            ArtifactReference parityBaseline,
            String resultHash,
            String proposalHash,
            IntakeDomainEventType projectedEventType,
            ParitySnapshot legacy,
            ParitySnapshot shadow) {

        public ParityMaterial {
            Objects.requireNonNull(parityBaseline, "parityBaseline must not be null")
                    .requireType("PARITY_BASELINE", "intake-parity-baseline.v1");
            sha256(resultHash, "resultHash");
            sha256(proposalHash, "proposalHash");
            if (projectedEventType != IntakeDomainEventType.TURN_NEEDS_INPUT
                    && projectedEventType != IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
                throw new IllegalArgumentException("synthetic parity event type is not allowed");
            }
            Objects.requireNonNull(legacy, "legacy must not be null");
            Objects.requireNonNull(shadow, "shadow must not be null");
        }
    }

    private static int maximumBytes(String artifactType, String schemaVersion) {
        return switch (artifactType) {
            case "INTAKE_SNAPSHOT" -> exactMaximum(
                    schemaVersion, "intake-domain-snapshot.v2", SNAPSHOT_MAX_BYTES);
            case "GRAPH_RESULT" -> exactMaximum(
                    schemaVersion, "room-graph-result.v1", GRAPH_ARTIFACT_MAX_BYTES);
            case "INTAKE_PROPOSAL" -> exactMaximum(
                    schemaVersion, "intake-turn-proposal.v2", GRAPH_ARTIFACT_MAX_BYTES);
            case "PARITY_BASELINE" -> exactMaximum(
                    schemaVersion, "intake-parity-baseline.v1", PARITY_BASELINE_MAX_BYTES);
            default -> throw new IllegalArgumentException("artifact type is not allowlisted");
        };
    }

    private static int exactMaximum(String actual, String expected, int maximum) {
        exact(actual, expected, "artifact schemaVersion");
        return maximum;
    }

    private static List<SyntheticScalarField> scalarFields(
            List<SyntheticScalarField> values, String field) {
        List<SyntheticScalarField> copy = List.copyOf(Objects.requireNonNull(values, field));
        if (copy.size() > 64) {
            throw new IllegalArgumentException(field + " exceeds its item bound");
        }
        if (copy.stream().map(SyntheticScalarField::name).distinct().count() != copy.size()) {
            throw new IllegalArgumentException(field + " contains duplicate names");
        }
        return copy;
    }

    private static List<String> identifiers(
            List<String> values, int minimum, int maximum, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field));
        if (copy.size() < minimum || copy.size() > maximum) {
            throw new IllegalArgumentException(field + " has an invalid item count");
        }
        copy.forEach(value -> identifier(value, field));
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(field + " must contain unique values");
        }
        return copy;
    }

    private static boolean forbiddenField(String name) {
        return Set.of(
                        "memory_frame", "internal_handoff", "handoff_notes", "hidden_reasoning",
                        "chain_of_thought", "tool_calls", "tool_parameters", "credential",
                        "credentials", "password", "secret", "token", "api_key", "private_key",
                        "compact_jws", "signing_key")
                .contains(name);
    }

    public static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
        return value;
    }

    public static String sha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static void threadId(String value) {
        if (value == null || !THREAD_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("threadId is invalid");
        }
    }

    public static String bounded(String value, int maximum, String field) {
        if (value == null
                || value.isBlank()
                || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is blank or exceeds its bound");
        }
        return value;
    }

    private static void exact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static void nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
