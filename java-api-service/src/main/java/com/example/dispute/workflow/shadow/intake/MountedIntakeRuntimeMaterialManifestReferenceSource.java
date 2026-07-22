package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifestReferenceSource.ManifestObjectReference;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable startup-loaded index of mounted, pre-approved synthetic manifest references. */
public final class MountedIntakeRuntimeMaterialManifestReferenceSource
        implements IntakeRuntimeMaterialManifestReferenceSource {

    public static final String INDEX_SCHEMA_VERSION =
            "intake-synthetic-runtime-material-index.v1";
    public static final int INDEX_MAX_BYTES = 4 * 1024 * 1024;
    public static final int INDEX_MAX_ENTRIES = 10_000;

    private final Map<ActivityLookup, ManifestObjectReference> references;

    public MountedIntakeRuntimeMaterialManifestReferenceSource(List<ManifestBinding> bindings) {
        List<ManifestBinding> copy = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (copy.isEmpty() || copy.size() > INDEX_MAX_ENTRIES) {
            throw new IllegalArgumentException("runtime material index entry count is invalid");
        }
        Map<ActivityLookup, ManifestObjectReference> indexed = new HashMap<>();
        for (ManifestBinding binding : copy) {
            ManifestObjectReference existing =
                    indexed.putIfAbsent(binding.activity(), binding.manifest());
            if (existing != null) {
                throw new IllegalArgumentException(
                        "runtime material index contains duplicate activity authority");
            }
        }
        references = Map.copyOf(indexed);
    }

    public static MountedIntakeRuntimeMaterialManifestReferenceSource load(
            Path path, ObjectMapper objectMapper) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        byte[] bytes;
        try {
            long size = Files.size(path);
            if (!Files.isRegularFile(path) || size <= 0 || size > INDEX_MAX_BYTES) {
                throw new IllegalArgumentException(
                        "runtime material index must be a bounded regular file");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalStateException("runtime material index could not be loaded", failure);
        }
        try {
            var tree = objectMapper.readTree(bytes);
            if (!Arrays.equals(bytes, ContractJson.canonicalize(tree))) {
                throw new IllegalArgumentException(
                        "runtime material index must use canonical JSON encoding");
            }
            ManifestIndex index = objectMapper
                    .readerFor(ManifestIndex.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(bytes);
            return new MountedIntakeRuntimeMaterialManifestReferenceSource(index.entries());
        } catch (IOException failure) {
            throw new IllegalArgumentException("runtime material index is invalid", failure);
        }
    }

    @Override
    public ManifestObjectReference resolve(ActivityAuthority authority) {
        ManifestObjectReference reference = references.get(ActivityLookup.from(authority));
        if (reference == null) {
            throw new SecurityException(
                    "no synthetic runtime material manifest is admitted for this activity");
        }
        return reference;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManifestIndex(String schemaVersion, List<ManifestBinding> entries) {

        public ManifestIndex {
            if (!INDEX_SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("runtime material index schema is invalid");
            }
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (entries.isEmpty() || entries.size() > INDEX_MAX_ENTRIES) {
                throw new IllegalArgumentException("runtime material index entry count is invalid");
            }
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ManifestBinding(ActivityLookup activity, ManifestObjectReference manifest) {

        public ManifestBinding {
            Objects.requireNonNull(activity, "activity must not be null");
            Objects.requireNonNull(manifest, "manifest must not be null");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActivityLookup(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String commandId,
            long commandSequence,
            IntakeCommandType commandType,
            IntakeParty party,
            String actorScopeHash,
            String commandPayloadRef,
            String commandPayloadHash,
            long processRevision,
            long roomRevision,
            long deadlineEpochMillis,
            String threadId,
            String agentSessionId,
            String operationKey,
            String requestHash,
            String roomWorkflowBuildId,
            String graphVersion,
            String checkpointSchemaVersion,
            String promptVersion,
            String modelProfileId,
            String outputSchemaVersion,
            String policyVersion,
            String guardrailVersion,
            String toolPolicyVersion) {

        public static final String SCHEMA_VERSION =
                "intake-synthetic-runtime-material-lookup.v1";

        public ActivityLookup {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("runtime activity lookup schema is invalid");
            }
            IntakeRuntimeMaterialManifest.identifier(tenantSurrogate, "tenantSurrogate");
            IntakeRuntimeMaterialManifest.identifier(caseId, "caseId");
            if (roomEpoch < 0 || fencingToken <= 0 || commandSequence <= 0
                    || processRevision < 0 || roomRevision < 0 || deadlineEpochMillis <= 0) {
                throw new IllegalArgumentException("runtime activity lookup revision is invalid");
            }
            IntakeRuntimeMaterialManifest.identifier(commandId, "commandId");
            Objects.requireNonNull(commandType, "commandType");
            Objects.requireNonNull(party, "party");
            IntakeRuntimeMaterialManifest.sha256(actorScopeHash, "actorScopeHash");
            IntakeRuntimeMaterialManifest.bounded(commandPayloadRef, 1024, "commandPayloadRef");
            IntakeRuntimeMaterialManifest.sha256(commandPayloadHash, "commandPayloadHash");
            IntakeRuntimeMaterialManifest.bounded(threadId, 128, "threadId");
            IntakeRuntimeMaterialManifest.identifier(agentSessionId, "agentSessionId");
            IntakeRuntimeMaterialManifest.bounded(operationKey, 512, "operationKey");
            IntakeRuntimeMaterialManifest.sha256(requestHash, "requestHash");
            IntakeRuntimeMaterialManifest.identifier(roomWorkflowBuildId, "roomWorkflowBuildId");
            IntakeRuntimeMaterialManifest.identifier(graphVersion, "graphVersion");
            IntakeRuntimeMaterialManifest.identifier(
                    checkpointSchemaVersion, "checkpointSchemaVersion");
            IntakeRuntimeMaterialManifest.identifier(promptVersion, "promptVersion");
            IntakeRuntimeMaterialManifest.identifier(modelProfileId, "modelProfileId");
            if (!"intake-turn-proposal.v2".equals(outputSchemaVersion)) {
                throw new IllegalArgumentException("outputSchemaVersion is invalid");
            }
            IntakeRuntimeMaterialManifest.identifier(policyVersion, "policyVersion");
            IntakeRuntimeMaterialManifest.identifier(guardrailVersion, "guardrailVersion");
            IntakeRuntimeMaterialManifest.identifier(toolPolicyVersion, "toolPolicyVersion");
        }

        public static ActivityLookup from(ActivityAuthority authority) {
            Objects.requireNonNull(authority, "authority");
            var envelope = authority.envelope();
            PinnedVersions pins = envelope.pinnedVersions();
            return new ActivityLookup(
                    SCHEMA_VERSION,
                    envelope.tenantSurrogate(),
                    envelope.caseId(),
                    envelope.roomEpoch(),
                    envelope.fencingToken(),
                    envelope.commandId(),
                    envelope.commandSequence(),
                    envelope.commandType(),
                    envelope.party(),
                    envelope.actorScopeHash(),
                    envelope.commandPayloadRef(),
                    envelope.commandPayloadHash(),
                    envelope.processRevision(),
                    envelope.roomRevision(),
                    envelope.deadlineEpochMillis(),
                    authority.threadId(),
                    authority.agentSessionId(),
                    authority.operationKey(),
                    authority.requestHash(),
                    pins.workflowBuildId(),
                    pins.graphVersion(),
                    pins.checkpointSchemaVersion(),
                    pins.promptVersion(),
                    pins.modelProfileId(),
                    pins.outputSchemaVersion(),
                    pins.policyVersion(),
                    pins.guardrailVersion(),
                    pins.toolPolicyVersion());
        }
    }
}
