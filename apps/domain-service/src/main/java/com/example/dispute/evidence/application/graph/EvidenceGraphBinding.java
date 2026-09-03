package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable Java-side manifest/thread/version binding. It grants no formal writer authority. */
public record EvidenceGraphBinding(
        String bindingId,
        String schemaVersion,
        String registrationId,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long javaRoomFencingToken,
        String threadId,
        String actorScopeHash,
        String agentSessionId,
        String manifestId,
        String manifestHash,
        String manifestPayloadUri,
        String manifestPayloadSha256,
        long manifestPayloadSizeBytes,
        String syntheticFixtureId,
        String graphKey,
        String graphVersion,
        String checkpointSchemaVersion,
        String stateSchemaVersion,
        String assessmentOutputSchemaVersion,
        String terminalOutputSchemaVersion,
        String writerMode,
        boolean formalSinkEligible,
        Instant createdAt,
        String bindingHash) {

    public static final String SCHEMA_VERSION = "evidence-graph-binding.v1";
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    public EvidenceGraphBinding {
        bounded(bindingId, "bindingId");
        require(schemaVersion, SCHEMA_VERSION, "schemaVersion");
        bounded(registrationId, "registrationId");
        bounded(tenantSurrogate, "tenantSurrogate");
        bounded(caseId, "caseId");
        if (roomEpoch < 0 || javaRoomFencingToken < 1) {
            throw new IllegalArgumentException("room epoch/fence is invalid");
        }
        if (threadId == null || !threadId.matches("^grt[.]v1[.][0-9a-f]{32}$")) {
            throw new IllegalArgumentException("threadId is invalid");
        }
        hash(actorScopeHash, "actorScopeHash");
        bounded(agentSessionId, "agentSessionId");
        bounded(manifestId, "manifestId");
        hash(manifestHash, "manifestHash");
        if (manifestPayloadUri == null
                || !(manifestPayloadUri.startsWith("s3://")
                        || manifestPayloadUri.startsWith("minio://"))
                || !manifestPayloadUri.endsWith("/" + manifestPayloadSha256 + ".json")) {
            throw new IllegalArgumentException("manifest payload URI is not content addressed");
        }
        hash(manifestPayloadSha256, "manifestPayloadSha256");
        if (manifestPayloadSizeBytes < 1 || manifestPayloadSizeBytes > 2_097_152) {
            throw new IllegalArgumentException("manifestPayloadSizeBytes is invalid");
        }
        bounded(syntheticFixtureId, "syntheticFixtureId");
        require(graphKey, "evidence.v2", "graphKey");
        bounded(graphVersion, "graphVersion");
        bounded(checkpointSchemaVersion, "checkpointSchemaVersion");
        require(stateSchemaVersion, "evidence-graph-state.v2", "stateSchemaVersion");
        require(
                assessmentOutputSchemaVersion,
                EvidenceBatchManifest.ASSESSMENT_OUTPUT_SCHEMA_VERSION,
                "assessmentOutputSchemaVersion");
        require(
                terminalOutputSchemaVersion,
                EvidenceBatchManifest.TERMINAL_OUTPUT_SCHEMA_VERSION,
                "terminalOutputSchemaVersion");
        require(writerMode, "SHADOW", "writerMode");
        if (formalSinkEligible) {
            throw new IllegalArgumentException("formal sink is forbidden for synthetic binding");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        hash(bindingHash, "bindingHash");
        if (!bindingHash.equals(canonicalHash(
                bindingId,
                schemaVersion,
                registrationId,
                tenantSurrogate,
                caseId,
                roomEpoch,
                javaRoomFencingToken,
                threadId,
                actorScopeHash,
                agentSessionId,
                manifestId,
                manifestHash,
                manifestPayloadUri,
                manifestPayloadSha256,
                manifestPayloadSizeBytes,
                syntheticFixtureId,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                stateSchemaVersion,
                assessmentOutputSchemaVersion,
                terminalOutputSchemaVersion,
                writerMode,
                formalSinkEligible,
                createdAt))) {
            throw new IllegalArgumentException("bindingHash is not canonical");
        }
    }

    public static EvidenceGraphBinding create(
            String bindingId,
            EvidenceBatchManifest manifest,
            EvidenceBatchManifest.SnapshotReference snapshot,
            Instant createdAt) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!manifest.manifestId().equals(snapshot.artifactId())
                || !manifest.payloadSha256().equals(snapshot.sha256())
                || manifest.payloadSizeBytes() != snapshot.sizeBytes()) {
            throw new IllegalArgumentException("snapshot does not bind the verified manifest");
        }
        ObjectNode profile = manifest.profileVersions();
        String bindingHash = canonicalHash(
                bindingId,
                SCHEMA_VERSION,
                manifest.text("registration_id"),
                manifest.text("tenant_surrogate"),
                manifest.text("case_id"),
                manifest.number("room_epoch"),
                manifest.number("fencing_token"),
                manifest.text("thread_id"),
                manifest.text("actor_scope_hash"),
                manifest.text("agent_session_id"),
                manifest.manifestId(),
                manifest.manifestHash(),
                snapshot.uri(),
                snapshot.sha256(),
                snapshot.sizeBytes(),
                manifest.text("synthetic_fixture_id"),
                "evidence.v2",
                EvidenceBatchManifest.requiredText(profile, "graph_version"),
                EvidenceBatchManifest.requiredText(profile, "checkpoint_schema_version"),
                EvidenceBatchManifest.requiredText(profile, "state_schema_version"),
                EvidenceBatchManifest.requiredText(
                        profile, "assessment_output_schema_version"),
                EvidenceBatchManifest.requiredText(profile, "terminal_output_schema_version"),
                "SHADOW",
                false,
                createdAt);
        return new EvidenceGraphBinding(
                bindingId,
                SCHEMA_VERSION,
                manifest.text("registration_id"),
                manifest.text("tenant_surrogate"),
                manifest.text("case_id"),
                manifest.number("room_epoch"),
                manifest.number("fencing_token"),
                manifest.text("thread_id"),
                manifest.text("actor_scope_hash"),
                manifest.text("agent_session_id"),
                manifest.manifestId(),
                manifest.manifestHash(),
                snapshot.uri(),
                snapshot.sha256(),
                snapshot.sizeBytes(),
                manifest.text("synthetic_fixture_id"),
                "evidence.v2",
                EvidenceBatchManifest.requiredText(profile, "graph_version"),
                EvidenceBatchManifest.requiredText(profile, "checkpoint_schema_version"),
                EvidenceBatchManifest.requiredText(profile, "state_schema_version"),
                EvidenceBatchManifest.requiredText(
                        profile, "assessment_output_schema_version"),
                EvidenceBatchManifest.requiredText(profile, "terminal_output_schema_version"),
                "SHADOW",
                false,
                createdAt,
                bindingHash);
    }

    private static String canonicalHash(
            String bindingId,
            String schemaVersion,
            String registrationId,
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long javaRoomFence,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            String manifestId,
            String manifestHash,
            String manifestPayloadUri,
            String manifestPayloadSha256,
            long manifestPayloadSizeBytes,
            String syntheticFixtureId,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String stateSchemaVersion,
            String assessmentOutputSchemaVersion,
            String terminalOutputSchemaVersion,
            String writerMode,
            boolean formalSinkEligible,
            Instant createdAt) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("binding_id", bindingId);
        value.put("schema_version", schemaVersion);
        value.put("registration_id", registrationId);
        value.put("tenant_surrogate", tenantSurrogate);
        value.put("case_id", caseId);
        value.put("room_epoch", roomEpoch);
        value.put("java_room_fencing_token", javaRoomFence);
        value.put("thread_id", threadId);
        value.put("actor_scope_hash", actorScopeHash);
        value.put("agent_session_id", agentSessionId);
        value.put("manifest_id", manifestId);
        value.put("manifest_hash", manifestHash);
        value.put("manifest_payload_uri", manifestPayloadUri);
        value.put("manifest_payload_sha256", manifestPayloadSha256);
        value.put("manifest_payload_size_bytes", manifestPayloadSizeBytes);
        value.put("synthetic_fixture_id", syntheticFixtureId);
        value.put("graph_key", graphKey);
        value.put("graph_version", graphVersion);
        value.put("checkpoint_schema_version", checkpointSchemaVersion);
        value.put("state_schema_version", stateSchemaVersion);
        value.put("assessment_output_schema_version", assessmentOutputSchemaVersion);
        value.put("terminal_output_schema_version", terminalOutputSchemaVersion);
        value.put("writer_mode", writerMode);
        value.put("formal_sink_eligible", formalSinkEligible);
        value.put("created_at", createdAt.toString());
        return ContractJson.sha256Hex(value);
    }

    private static void bounded(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }

    private static void hash(String value, String field) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static void require(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    public record AssetLoadBinding(
            String graphBindingId,
            EvidenceAssetAuthorization.ActualLoadReceipt actualLoadReceipt) {
        public AssetLoadBinding {
            bounded(graphBindingId, "graphBindingId");
            Objects.requireNonNull(actualLoadReceipt, "actualLoadReceipt");
        }
    }
}
