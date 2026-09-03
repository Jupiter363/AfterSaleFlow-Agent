package com.example.dispute.evidence.infrastructure.persistence;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.ActualLoadReceipt;
import com.example.dispute.evidence.application.graph.EvidenceGraphBinding;
import com.example.dispute.evidence.application.graph.EvidenceGraphBinding.AssetLoadBinding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Additive, idempotent persistence for verified synthetic Evidence bindings and load receipts. */
@Repository
public class JdbcEvidenceGraphBindingStore {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String BINDING_COLUMNS =
            """
            binding_id, schema_version, registration_id, tenant_surrogate, case_id,
            room_epoch, java_room_fencing_token, thread_id, actor_scope_hash,
            agent_session_id, manifest_id, manifest_hash, manifest_payload_uri,
            manifest_payload_sha256, manifest_payload_size_bytes, synthetic_fixture_id,
            graph_key, graph_version, checkpoint_schema_version, state_schema_version,
            assessment_output_schema_version, terminal_output_schema_version, writer_mode,
            formal_sink_eligible, created_at, binding_hash
            """;
    private static final String RECEIPT_COLUMNS =
            """
            receipt_id, receipt_hash, capability_id, capability_hash, capability_nonce,
            graph_binding_id,
            manifest_id, manifest_hash, evidence_id, item_hash, object_ref,
            immutable_object_version, object_sha256, content_type, byte_size,
            java_room_fencing_token, graph_lease_fencing_token, load_status,
            loaded_modalities_json, loaded_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEvidenceGraphBindingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional(readOnly = true)
    public Optional<EvidenceGraphBinding> find(String bindingId) {
        List<EvidenceGraphBinding> rows = jdbc.query(
                "select %s from case_evidence_graph_binding where binding_id = :bindingId"
                        .formatted(BINDING_COLUMNS),
                Map.of("bindingId", bindingId),
                JdbcEvidenceGraphBindingStore::mapBinding);
        return exactlyOneOrEmpty(rows, "duplicate persisted Evidence graph binding");
    }

    @Transactional
    public WriteReceipt<EvidenceGraphBinding> register(EvidenceGraphBinding binding) {
        Objects.requireNonNull(binding, "binding");
        int inserted = jdbc.update(
                """
                insert into case_evidence_graph_binding (
                    binding_id, schema_version, registration_id, tenant_surrogate, case_id,
                    room_epoch, java_room_fencing_token, thread_id, actor_scope_hash,
                    agent_session_id, manifest_id, manifest_hash, manifest_payload_uri,
                    manifest_payload_sha256, manifest_payload_size_bytes, synthetic_fixture_id,
                    graph_key, graph_version, checkpoint_schema_version, state_schema_version,
                    assessment_output_schema_version, terminal_output_schema_version, writer_mode,
                    formal_sink_eligible, created_at, binding_hash
                ) values (
                    :bindingId, :schemaVersion, :registrationId, :tenantSurrogate, :caseId,
                    :roomEpoch, :javaRoomFencingToken, :threadId, :actorScopeHash,
                    :agentSessionId, :manifestId, :manifestHash, :manifestPayloadUri,
                    :manifestPayloadSha256, :manifestPayloadSizeBytes, :syntheticFixtureId,
                    :graphKey, :graphVersion, :checkpointSchemaVersion, :stateSchemaVersion,
                    :assessmentOutputSchemaVersion, :terminalOutputSchemaVersion, :writerMode,
                    :formalSinkEligible, :createdAt, :bindingHash
                ) on conflict do nothing
                """,
                bindingParameters(binding));
        if (inserted == 1) {
            return WriteReceipt.created(binding);
        }
        EvidenceGraphBinding existing = findConflictingBinding(binding)
                .orElseThrow(() -> conflict("binding uniqueness"));
        if (!existing.equals(binding)) {
            throw conflict("binding identity was reused with another immutable payload");
        }
        return WriteReceipt.replayed(existing);
    }

    @Transactional
    public WriteReceipt<AssetLoadBinding> recordActualLoad(AssetLoadBinding loadBinding) {
        Objects.requireNonNull(loadBinding, "loadBinding");
        EvidenceGraphBinding binding = lockBinding(loadBinding.graphBindingId());
        ActualLoadReceipt receipt = loadBinding.actualLoadReceipt();
        requireReceiptScope(binding, receipt);
        int inserted = jdbc.update(
                """
                insert into case_evidence_asset_load_receipt (
                    receipt_id, receipt_hash, capability_id, capability_hash, capability_nonce,
                    graph_binding_id,
                    manifest_id, manifest_hash, evidence_id, item_hash, object_ref,
                    immutable_object_version, object_sha256, content_type, byte_size,
                    java_room_fencing_token, graph_lease_fencing_token, load_status,
                    loaded_modalities_json, loaded_at
                ) values (
                    :receiptId, :receiptHash, :capabilityId, :capabilityHash, :capabilityNonce,
                    :graphBindingId,
                    :manifestId, :manifestHash, :evidenceId, :itemHash, :objectRef,
                    :immutableObjectVersion, :objectSha256, :contentType, :byteSize,
                    :javaRoomFencingToken, :graphLeaseFencingToken, :loadStatus,
                    cast(:loadedModalitiesJson as jsonb), :loadedAt
                ) on conflict do nothing
                """,
                receiptParameters(loadBinding));
        if (inserted == 1) {
            return WriteReceipt.created(loadBinding);
        }
        AssetLoadBinding existing = findConflictingReceipt(loadBinding)
                .orElseThrow(() -> conflict("actual-load receipt uniqueness"));
        if (!existing.equals(loadBinding)) {
            throw conflict("actual-load receipt identity was reused with another payload");
        }
        return WriteReceipt.replayed(existing);
    }

    private Optional<EvidenceGraphBinding> findConflictingBinding(EvidenceGraphBinding binding) {
        List<EvidenceGraphBinding> rows = jdbc.query(
                """
                select %s from case_evidence_graph_binding
                 where binding_id = :bindingId
                    or binding_hash = :bindingHash
                    or manifest_id = :manifestId
                    or manifest_hash = :manifestHash
                """.formatted(BINDING_COLUMNS),
                bindingParameters(binding),
                JdbcEvidenceGraphBindingStore::mapBinding);
        return exactlyOneOrEmpty(rows, "conflicting Evidence graph binding rows");
    }

    private Optional<AssetLoadBinding> findConflictingReceipt(AssetLoadBinding binding) {
        ActualLoadReceipt receipt = binding.actualLoadReceipt();
        List<AssetLoadBinding> rows = jdbc.query(
                """
                select %s from case_evidence_asset_load_receipt
                 where receipt_id = :receiptId
                    or receipt_hash = :receiptHash
                    or capability_id = :capabilityId
                    or capability_hash = :capabilityHash
                """.formatted(RECEIPT_COLUMNS),
                receiptParameters(binding),
                JdbcEvidenceGraphBindingStore::mapLoadBinding);
        return exactlyOneOrEmpty(rows, "conflicting Evidence actual-load receipt rows");
    }

    private EvidenceGraphBinding lockBinding(String bindingId) {
        List<EvidenceGraphBinding> rows = jdbc.query(
                "select %s from case_evidence_graph_binding where binding_id = :bindingId for update"
                        .formatted(BINDING_COLUMNS),
                Map.of("bindingId", bindingId),
                JdbcEvidenceGraphBindingStore::mapBinding);
        if (rows.size() != 1) {
            throw conflict("manifest binding is missing");
        }
        return rows.getFirst();
    }

    private static void requireReceiptScope(
            EvidenceGraphBinding binding, ActualLoadReceipt receipt) {
        if (!binding.manifestId().equals(receipt.manifestId())
                || !binding.manifestHash().equals(receipt.manifestHash())
                || binding.javaRoomFencingToken() != receipt.javaRoomFencingToken()
                || receipt.javaRoomFencingToken() == receipt.graphLeaseFencingToken()
                || !"LOADED".equals(receipt.loadStatus())) {
            throw conflict("actual-load receipt is outside the manifest/fence scope");
        }
    }

    private static MapSqlParameterSource bindingParameters(EvidenceGraphBinding value) {
        return new MapSqlParameterSource()
                .addValue("bindingId", value.bindingId())
                .addValue("schemaVersion", value.schemaVersion())
                .addValue("registrationId", value.registrationId())
                .addValue("tenantSurrogate", value.tenantSurrogate())
                .addValue("caseId", value.caseId())
                .addValue("roomEpoch", value.roomEpoch())
                .addValue("javaRoomFencingToken", value.javaRoomFencingToken())
                .addValue("threadId", value.threadId())
                .addValue("actorScopeHash", value.actorScopeHash())
                .addValue("agentSessionId", value.agentSessionId())
                .addValue("manifestId", value.manifestId())
                .addValue("manifestHash", value.manifestHash())
                .addValue("manifestPayloadUri", value.manifestPayloadUri())
                .addValue("manifestPayloadSha256", value.manifestPayloadSha256())
                .addValue("manifestPayloadSizeBytes", value.manifestPayloadSizeBytes())
                .addValue("syntheticFixtureId", value.syntheticFixtureId())
                .addValue("graphKey", value.graphKey())
                .addValue("graphVersion", value.graphVersion())
                .addValue("checkpointSchemaVersion", value.checkpointSchemaVersion())
                .addValue("stateSchemaVersion", value.stateSchemaVersion())
                .addValue("assessmentOutputSchemaVersion", value.assessmentOutputSchemaVersion())
                .addValue("terminalOutputSchemaVersion", value.terminalOutputSchemaVersion())
                .addValue("writerMode", value.writerMode())
                .addValue("formalSinkEligible", value.formalSinkEligible())
                .addValue("createdAt", value.createdAt().atOffset(ZoneOffset.UTC))
                .addValue("bindingHash", value.bindingHash());
    }

    private static MapSqlParameterSource receiptParameters(AssetLoadBinding value) {
        ActualLoadReceipt receipt = value.actualLoadReceipt();
        return new MapSqlParameterSource()
                .addValue("receiptId", receipt.receiptId())
                .addValue("receiptHash", receipt.receiptHash())
                .addValue("capabilityId", receipt.capabilityId())
                .addValue("capabilityHash", receipt.capabilityHash())
                .addValue("capabilityNonce", receipt.capabilityNonce())
                .addValue("graphBindingId", value.graphBindingId())
                .addValue("manifestId", receipt.manifestId())
                .addValue("manifestHash", receipt.manifestHash())
                .addValue("evidenceId", receipt.evidenceId())
                .addValue("itemHash", receipt.itemHash())
                .addValue("objectRef", receipt.objectRef())
                .addValue("immutableObjectVersion", receipt.immutableObjectVersion())
                .addValue("objectSha256", receipt.objectSha256())
                .addValue("contentType", receipt.contentType())
                .addValue("byteSize", receipt.byteSize())
                .addValue("javaRoomFencingToken", receipt.javaRoomFencingToken())
                .addValue("graphLeaseFencingToken", receipt.graphLeaseFencingToken())
                .addValue("loadStatus", receipt.loadStatus())
                .addValue("loadedModalitiesJson", writeModalities(receipt.loadedModalities()))
                .addValue("loadedAt", receipt.loadedAt().atOffset(ZoneOffset.UTC));
    }

    private static EvidenceGraphBinding mapBinding(ResultSet row, int ignored)
            throws SQLException {
        return new EvidenceGraphBinding(
                row.getString("binding_id"),
                row.getString("schema_version"),
                row.getString("registration_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("room_epoch"),
                row.getLong("java_room_fencing_token"),
                row.getString("thread_id"),
                row.getString("actor_scope_hash"),
                row.getString("agent_session_id"),
                row.getString("manifest_id"),
                row.getString("manifest_hash"),
                row.getString("manifest_payload_uri"),
                row.getString("manifest_payload_sha256"),
                row.getLong("manifest_payload_size_bytes"),
                row.getString("synthetic_fixture_id"),
                row.getString("graph_key"),
                row.getString("graph_version"),
                row.getString("checkpoint_schema_version"),
                row.getString("state_schema_version"),
                row.getString("assessment_output_schema_version"),
                row.getString("terminal_output_schema_version"),
                row.getString("writer_mode"),
                row.getBoolean("formal_sink_eligible"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getString("binding_hash"));
    }

    private static AssetLoadBinding mapLoadBinding(ResultSet row, int ignored)
            throws SQLException {
        ActualLoadReceipt receipt = new ActualLoadReceipt(
                row.getString("receipt_id"),
                row.getString("receipt_hash"),
                row.getString("capability_id"),
                row.getString("capability_hash"),
                row.getString("capability_nonce"),
                row.getString("manifest_id"),
                row.getString("manifest_hash"),
                row.getString("evidence_id"),
                row.getString("item_hash"),
                row.getString("object_ref"),
                row.getString("immutable_object_version"),
                row.getString("object_sha256"),
                row.getString("content_type"),
                row.getLong("byte_size"),
                row.getLong("java_room_fencing_token"),
                row.getLong("graph_lease_fencing_token"),
                row.getString("load_status"),
                readModalities(row.getString("loaded_modalities_json")),
                row.getObject("loaded_at", OffsetDateTime.class).toInstant());
        return new AssetLoadBinding(row.getString("graph_binding_id"), receipt);
    }

    private static String writeModalities(List<String> modalities) {
        try {
            return MAPPER.writeValueAsString(modalities);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("loaded modalities are not serializable", failure);
        }
    }

    private static List<String> readModalities(String value) {
        try {
            return MAPPER.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("persisted loaded modalities are invalid", failure);
        }
    }

    private static <T> Optional<T> exactlyOneOrEmpty(List<T> rows, String conflict) {
        if (rows.size() > 1) {
            throw conflict(conflict);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static IllegalStateException conflict(String detail) {
        return new IllegalStateException("Evidence Graph binding conflict: " + detail);
    }

    public record WriteReceipt<T>(T value, boolean created) {
        public WriteReceipt {
            Objects.requireNonNull(value, "value");
        }

        public static <T> WriteReceipt<T> created(T value) {
            return new WriteReceipt<>(value, true);
        }

        public static <T> WriteReceipt<T> replayed(T value) {
            return new WriteReceipt<>(value, false);
        }
    }
}
