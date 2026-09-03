package com.example.dispute.evidence.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.ActualLoadReceipt;
import com.example.dispute.evidence.application.graph.EvidenceGraphBinding;
import com.example.dispute.evidence.application.graph.EvidenceGraphBinding.AssetLoadBinding;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
class JdbcEvidenceGraphBindingStoreTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-22T12:05:00Z");
    private static final String MANIFEST_HASH = "c".repeat(64);
    private static final String PAYLOAD_HASH = "8".repeat(64);
    private static final String ACTOR_SCOPE_HASH = "a".repeat(64);

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private JdbcEvidenceGraphBindingStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcEvidenceGraphBindingStore(jdbc);
    }

    @Test
    void registersOnlyTheImmutableSplitPinSyntheticBinding() {
        EvidenceGraphBinding binding = binding("BINDING_P5_1", MANIFEST_HASH);
        when(jdbc.update(
                        contains("insert into case_evidence_graph_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        var receipt = store.register(binding);

        assertThat(receipt.created()).isTrue();
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(
                contains("insert into case_evidence_graph_binding"), parameters.capture());
        assertThat(parameters.getValue().getValue("writerMode")).isEqualTo("SHADOW");
        assertThat(parameters.getValue().getValue("formalSinkEligible")).isEqualTo(false);
        assertThat(parameters.getValue().getValue("assessmentOutputSchemaVersion"))
                .isEqualTo("evidence-item-assessment.v1");
        assertThat(parameters.getValue().getValue("terminalOutputSchemaVersion"))
                .isEqualTo("evidence-batch-proposal.v1");
        assertThat(parameters.getValue().getValue("javaRoomFencingToken")).isEqualTo(7L);
    }

    @Test
    void exactBindingReplaysButSameManifestIdentityWithDriftFailsClosed() {
        EvidenceGraphBinding binding = binding("BINDING_P5_1", MANIFEST_HASH);
        when(jdbc.update(
                        contains("insert into case_evidence_graph_binding"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(0);
        when(jdbc.query(
                        contains("or manifest_id = :manifestId"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<EvidenceGraphBinding>>any()))
                .thenReturn(List.of(binding));

        assertThat(store.register(binding).created()).isFalse();

        EvidenceGraphBinding drift = binding("BINDING_P5_DRIFT", "d".repeat(64));
        when(jdbc.query(
                        contains("or manifest_id = :manifestId"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<EvidenceGraphBinding>>any()))
                .thenReturn(List.of(binding));
        assertThatThrownBy(() -> store.register(drift))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another immutable payload");
    }

    @Test
    void recordsAnActualLoadOnceWithIndependentGraphLeaseFenceAndNonceIdentity() {
        EvidenceGraphBinding binding = binding("BINDING_P5_1", MANIFEST_HASH);
        ActualLoadReceipt actual = receipt(MANIFEST_HASH, "CAPABILITY_P5_1", "NONCE_P5_1", 7, 7001);
        AssetLoadBinding loadBinding = new AssetLoadBinding(binding.bindingId(), actual);
        stubLockedBinding(binding);
        when(jdbc.update(
                        contains("insert into case_evidence_asset_load_receipt"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(store.recordActualLoad(loadBinding).created()).isTrue();

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(
                contains("insert into case_evidence_asset_load_receipt"),
                parameters.capture());
        assertThat(parameters.getValue().getValue("capabilityNonce")).isEqualTo("NONCE_P5_1");
        assertThat(parameters.getValue().getValue("loadStatus")).isEqualTo("LOADED");
        assertThat(parameters.getValue().getValue("javaRoomFencingToken")).isEqualTo(7L);
        assertThat(parameters.getValue().getValue("graphLeaseFencingToken")).isEqualTo(7001L);
    }

    @Test
    void refusesCrossManifestReceiptBeforeWritingAndReplaysAnExactReceipt() {
        EvidenceGraphBinding binding = binding("BINDING_P5_1", MANIFEST_HASH);
        ActualLoadReceipt crossManifest =
                receipt("d".repeat(64), "CAPABILITY_P5_1", "NONCE_P5_1", 7, 7001);
        stubLockedBinding(binding);

        assertThatThrownBy(() -> store.recordActualLoad(
                        new AssetLoadBinding(binding.bindingId(), crossManifest)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest/fence scope");
        verify(jdbc, never()).update(
                contains("insert into case_evidence_asset_load_receipt"),
                any(SqlParameterSource.class));

        ActualLoadReceipt exact = receipt(MANIFEST_HASH, "CAPABILITY_P5_2", "NONCE_P5_2", 7, 7001);
        AssetLoadBinding exactBinding = new AssetLoadBinding(binding.bindingId(), exact);
        when(jdbc.update(
                        contains("insert into case_evidence_asset_load_receipt"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(0);
        when(jdbc.query(
                        contains("or capability_id = :capabilityId"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<AssetLoadBinding>>any()))
                .thenReturn(List.of(exactBinding));
        assertThat(store.recordActualLoad(exactBinding).created()).isFalse();
    }

    @Test
    void migrationIsAdditiveAppendOnlyAndStoresNoAssetOrCheckpointBodies() throws Exception {
        String ddl = Files.readString(Path.of(
                "src",
                "main",
                "resources",
                "db",
                "migration",
                "V043_4__evidence_graph_bindings.sql"));

        assertThat(ddl)
                .contains("create table case_evidence_graph_binding")
                .contains("create table case_evidence_asset_load_receipt")
                .contains("capability_nonce varchar(128) not null unique")
                .contains("java_room_fencing_token <> graph_lease_fencing_token")
                .contains("before update or delete")
                .doesNotContain("alter table evidence_")
                .doesNotContain("checkpoint_body")
                .doesNotContain("asset_bytes")
                .doesNotContain("TEMPORAL");
    }

    private void stubLockedBinding(EvidenceGraphBinding binding) {
        when(jdbc.query(
                        contains("for update"),
                        anyMap(),
                        org.mockito.ArgumentMatchers.<RowMapper<EvidenceGraphBinding>>any()))
                .thenReturn(List.of(binding));
    }

    private static EvidenceGraphBinding binding(String bindingId, String manifestHash) {
        String bindingHash = bindingHash(bindingId, manifestHash);
        return new EvidenceGraphBinding(
                bindingId,
                EvidenceGraphBinding.SCHEMA_VERSION,
                "REGISTRATION_P5_SYNTHETIC_USER",
                "TENANT_P5_SYNTHETIC_1",
                "CASE_P5_SYNTHETIC_1",
                1,
                7,
                "grt.v1.018f6b7ec30a7430982fffc520c8195c",
                ACTOR_SCOPE_HASH,
                "AGENT_SESSION_P5_USER",
                "MANIFEST_P5_SYNTHETIC_ONE",
                manifestHash,
                "s3://evidence-synthetic-manifests/CASE_P5_SYNTHETIC_1/epoch-1/"
                        + PAYLOAD_HASH
                        + ".json",
                PAYLOAD_HASH,
                2873,
                "FIXTURE_P5_ONE",
                "evidence.v2",
                "evidence.v2.0.0",
                "evidence-checkpoint.v2",
                "evidence-graph-state.v2",
                "evidence-item-assessment.v1",
                "evidence-batch-proposal.v1",
                "SHADOW",
                false,
                CREATED_AT,
                bindingHash);
    }

    private static String bindingHash(String bindingId, String manifestHash) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("binding_id", bindingId);
        value.put("schema_version", EvidenceGraphBinding.SCHEMA_VERSION);
        value.put("registration_id", "REGISTRATION_P5_SYNTHETIC_USER");
        value.put("tenant_surrogate", "TENANT_P5_SYNTHETIC_1");
        value.put("case_id", "CASE_P5_SYNTHETIC_1");
        value.put("room_epoch", 1);
        value.put("java_room_fencing_token", 7);
        value.put("thread_id", "grt.v1.018f6b7ec30a7430982fffc520c8195c");
        value.put("actor_scope_hash", ACTOR_SCOPE_HASH);
        value.put("agent_session_id", "AGENT_SESSION_P5_USER");
        value.put("manifest_id", "MANIFEST_P5_SYNTHETIC_ONE");
        value.put("manifest_hash", manifestHash);
        value.put(
                "manifest_payload_uri",
                "s3://evidence-synthetic-manifests/CASE_P5_SYNTHETIC_1/epoch-1/"
                        + PAYLOAD_HASH
                        + ".json");
        value.put("manifest_payload_sha256", PAYLOAD_HASH);
        value.put("manifest_payload_size_bytes", 2873);
        value.put("synthetic_fixture_id", "FIXTURE_P5_ONE");
        value.put("graph_key", "evidence.v2");
        value.put("graph_version", "evidence.v2.0.0");
        value.put("checkpoint_schema_version", "evidence-checkpoint.v2");
        value.put("state_schema_version", "evidence-graph-state.v2");
        value.put("assessment_output_schema_version", "evidence-item-assessment.v1");
        value.put("terminal_output_schema_version", "evidence-batch-proposal.v1");
        value.put("writer_mode", "SHADOW");
        value.put("formal_sink_eligible", false);
        value.put("created_at", CREATED_AT.toString());
        return ContractJson.sha256Hex(value);
    }

    private static ActualLoadReceipt receipt(
            String manifestHash,
            String capabilityId,
            String capabilityNonce,
            long javaFence,
            long graphFence) {
        String capabilityHash = capabilityId.endsWith("1") ? "b".repeat(64) : "e".repeat(64);
        String receiptId = "LOAD_" + capabilityHash;
        List<String> modalities = List.of("PDF_METADATA", "TEXT");
        String receiptHash = receiptHash(
                receiptId,
                capabilityId,
                capabilityHash,
                capabilityNonce,
                manifestHash,
                javaFence,
                graphFence,
                modalities);
        return new ActualLoadReceipt(
                receiptId,
                receiptHash,
                capabilityId,
                capabilityHash,
                capabilityNonce,
                "MANIFEST_P5_SYNTHETIC_ONE",
                manifestHash,
                "EVIDENCE_SYNTH_001",
                "1".repeat(64),
                "urn:synthetic-evidence:fixture-100/item-001",
                "OBJECT_VERSION_001",
                "2".repeat(64),
                "application/pdf",
                1025,
                javaFence,
                graphFence,
                "LOADED",
                modalities,
                CREATED_AT);
    }

    private static String receiptHash(
            String receiptId,
            String capabilityId,
            String capabilityHash,
            String capabilityNonce,
            String manifestHash,
            long javaFence,
            long graphFence,
            List<String> modalities) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("receipt_id", receiptId);
        value.put("capability_id", capabilityId);
        value.put("capability_hash", capabilityHash);
        value.put("capability_nonce", capabilityNonce);
        value.put("manifest_id", "MANIFEST_P5_SYNTHETIC_ONE");
        value.put("manifest_hash", manifestHash);
        value.put("evidence_id", "EVIDENCE_SYNTH_001");
        value.put("item_hash", "1".repeat(64));
        value.put("object_ref", "urn:synthetic-evidence:fixture-100/item-001");
        value.put("immutable_object_version", "OBJECT_VERSION_001");
        value.put("object_sha256", "2".repeat(64));
        value.put("content_type", "application/pdf");
        value.put("byte_size", 1025);
        value.put("java_room_fencing_token", javaFence);
        value.put("graph_lease_fencing_token", graphFence);
        value.put("load_status", "LOADED");
        value.putPOJO("loaded_modalities", modalities);
        value.put("loaded_at", CREATED_AT.toString());
        return ContractJson.sha256Hex(value);
    }
}
