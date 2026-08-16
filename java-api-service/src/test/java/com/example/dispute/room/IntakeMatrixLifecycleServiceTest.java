package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.room.application.IntakeMatrixLifecycleService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeInitiatorMatrixFreezer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntakeMatrixLifecycleServiceTest {

    private static final String CASE_ID = "CASE_MATRIX_CANONICAL";

    private CaseIntakeDossierRepository repository;
    private ObjectMapper objectMapper;
    private IntakeMatrixLifecycleService service;

    @BeforeEach
    void setUp() {
        repository = mock(CaseIntakeDossierRepository.class);
        objectMapper = JsonMapper.builder().build();
        service = new IntakeMatrixLifecycleService(repository, objectMapper);
    }

    @Test
    void bilateralValidationUsesRfc8785NumberCanonicalization() throws Exception {
        ObjectNode matrix = bilateralMatrix();
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        CaseIntakeDossierEntity dossier = dossier(matrix);
        when(repository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(dossier));

        ObjectNode validated = service.requireBilateralFrozen(dispute());

        assertThat(validated.path("content_hash").asText())
                .isEqualTo(matrix.path("content_hash").asText());
        assertThat(validated.path("fact_rows").path(0).path("claimed_amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("150.0"));
        assertThat(validated.path("fact_rows").path(0).path("weight").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1.2300"));
    }

    @Test
    void bilateralValidationAcceptsOnlyGroundedExplicitUnknownRespondentPositions()
            throws Exception {
        ObjectNode grounded = bilateralMatrix();
        ObjectNode groundedRespondent = (ObjectNode) grounded.at("/fact_rows/0/positions/MERCHANT");
        groundedRespondent.put("stance", "UNKNOWN");
        grounded.put("content_hash", ContractJson.sha256Hex(grounded));

        ObjectNode ungrounded = grounded.deepCopy();
        ungrounded.remove("content_hash");
        ObjectNode ungroundedRespondent =
                (ObjectNode) ungrounded.at("/fact_rows/0/positions/MERCHANT");
        ungroundedRespondent.put("source_type", "NO_DIRECT_POSITION");
        ungroundedRespondent.putArray("source_refs");
        ungrounded.put("content_hash", ContractJson.sha256Hex(ungrounded));

        when(repository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(dossier(grounded)), Optional.of(dossier(ungrounded)));

        ObjectNode validated = service.requireBilateralFrozen(dispute());

        assertThat(validated.at("/fact_rows/0/positions/MERCHANT/stance").asText())
                .isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> service.requireBilateralFrozen(dispute()))
                .isInstanceOf(IntakeFinalizationRejectedException.class)
                .hasMessage("bilateral matrix lacks an independently derived respondent position");
    }

    @Test
    void respondentTimeoutWritesAnRfc8785CanonicalMatrixHash() throws Exception {
        ObjectNode matrix = timeoutInitiatorMatrix();
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        CaseIntakeDossierEntity dossier = dossier(matrix);
        when(repository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(dossier));

        service.freezeRespondentTimeout(dispute());

        JsonNode persisted = objectMapper.readTree(dossier.getDossierJson()).path("case_fact_matrix");
        ObjectNode hashInput = ((ObjectNode) persisted).deepCopy();
        String storedHash = hashInput.remove("content_hash").asText();
        assertThat(persisted.path("matrix_kind").asText())
                .isEqualTo("RESPONDENT_TIMEOUT_FROZEN");
        assertThat(storedHash).isEqualTo(ContractJson.sha256Hex(hashInput));
        assertThat(persisted.path("generation_ref").path("source_context_hash").asText())
                .matches("[0-9a-f]{64}");
        assertThat(persisted.path("fact_rows").path(0).path("claimed_amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("150.0"));
        verify(repository).save(dossier);
    }

    @Test
    void initiatorConfirmationReusesTheAlreadyUnifiedBaselineMatrix() throws Exception {
        ObjectNode matrix = strictInitiatorMatrix();
        CaseIntakeDossierEntity dossier = dossier(matrix);
        when(repository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(dossier));

        var result = service.freezeInitiatorIfPossible(dispute(), "user-local");

        assertThat(result.created()).isFalse();
        assertThat(result.matrixKind()).isEqualTo("INITIATOR_FROZEN");
        assertThat(result.contentHash()).isEqualTo(matrix.path("content_hash").asText());
        JsonNode persisted = objectMapper.readTree(dossier.getDossierJson());
        assertThat(persisted.path("case_fact_matrix").path("schema_version").asText())
                .isEqualTo("case_fact_matrix.v2");
        assertThat(persisted.has("unilateral_case_matrix")).isFalse();
        verify(repository, never()).save(dossier);
    }

    @Test
    void initiatorConfirmationRemovesAStaleUnilateralCompatibilityBranch() throws Exception {
        ObjectNode matrix = strictInitiatorMatrix();
        CaseIntakeDossierEntity dossier = dossier(matrix, true);
        FulfillmentCaseEntity dispute = dispute();
        when(repository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(dossier));

        var result = service.freezeInitiatorIfPossible(dispute, "user-local");

        assertThat(result.created()).isFalse();
        JsonNode persisted = objectMapper.readTree(dossier.getDossierJson());
        assertThat(persisted.path("case_fact_matrix").path("matrix_kind").asText())
                .isEqualTo("INITIATOR_FROZEN");
        assertThat(persisted.has("unilateral_case_matrix")).isFalse();
        verify(repository).save(dossier);
        verify(dispute).refreshIntakeResult(dossier.getDossierJson(), "user-local");
    }

    @Test
    void initiatorConfirmationMigratesLegacyAuthorityWithoutResettingItsVersion() throws Exception {
        ObjectNode legacy = legacyProjection();
        CaseIntakeDossierEntity dossier = legacyDossier(legacy);
        when(repository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(dossier));

        var result = service.freezeInitiatorIfPossible(dispute(), "user-local");

        assertThat(result.created()).isTrue();
        JsonNode persisted = objectMapper.readTree(dossier.getDossierJson());
        JsonNode formal = persisted.path("case_fact_matrix");
        assertThat(formal.path("schema_version").asText()).isEqualTo("case_fact_matrix.v2");
        assertThat(formal.path("matrix_kind").asText()).isEqualTo("INITIATOR_FROZEN");
        assertThat(formal.path("matrix_version").asLong())
                .isEqualTo(legacy.path("matrix_version").asLong() + 1);
        assertThat(formal.at("/parent_ref/matrix_version"))
                .isEqualTo(legacy.path("matrix_version"));
        assertThat(formal.at("/parent_ref/content_hash"))
                .isEqualTo(legacy.path("content_hash"));
        assertThat(persisted.has("unilateral_case_matrix")).isFalse();
        verify(repository).save(dossier);
    }

    @Test
    void initiatorConfirmationDoesNotDiscardLegacyAuthorityBehindAnInvalidFormalObject()
            throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "intake_case_detail.v1");
        root.putObject("case_fact_matrix")
                .put("schema_version", "case_fact_matrix.v2")
                .put("matrix_kind", "INITIATOR_FROZEN");
        root.set("unilateral_case_matrix", legacyProjection());
        CaseIntakeDossierEntity dossier = dossierRoot(root);
        when(repository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(dossier));

        assertThatThrownBy(() -> service.freezeInitiatorIfPossible(dispute(), "user-local"))
                .isInstanceOf(IntakeFinalizationRejectedException.class);

        JsonNode unchanged = objectMapper.readTree(dossier.getDossierJson());
        assertThat(unchanged.has("unilateral_case_matrix")).isTrue();
        verify(repository, never()).save(dossier);
    }

    private FulfillmentCaseEntity dispute() {
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        when(dispute.getId()).thenReturn(CASE_ID);
        when(dispute.getInitiatorRole()).thenReturn(ActorRole.USER);
        when(dispute.getRespondentRole()).thenReturn(ActorRole.MERCHANT);
        return dispute;
    }

    private CaseIntakeDossierEntity dossier(ObjectNode matrix) throws Exception {
        return dossier(matrix, false);
    }

    private CaseIntakeDossierEntity dossier(ObjectNode matrix, boolean staleUnilateral)
            throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "intake_case_detail.v1");
        root.set("case_fact_matrix", matrix);
        if (staleUnilateral) {
            root.set("unilateral_case_matrix", legacyProjection());
        }
        return dossierRoot(root);
    }

    private CaseIntakeDossierEntity legacyDossier(ObjectNode legacy) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "intake_case_detail.v1");
        root.set("unilateral_case_matrix", legacy);
        return dossierRoot(root);
    }

    private CaseIntakeDossierEntity dossierRoot(ObjectNode root) throws Exception {
        return CaseIntakeDossierEntity.create(
                "DOSSIER_MATRIX_CANONICAL",
                CASE_ID,
                RoomType.INTAKE,
                objectMapper.writeValueAsString(root),
                100,
                true,
                "ACCEPTED",
                4,
                "test");
    }

    private ObjectNode bilateralMatrix() {
        ObjectNode matrix = baseMatrix("BILATERAL_FROZEN", 2);
        ObjectNode parent = matrix.putObject("parent_ref");
        parent.put("matrix_id", "CASE_MATRIX_PARENT");
        parent.put("matrix_version", 1);
        parent.put("content_hash", "a".repeat(64));
        ObjectNode generation = matrix.putObject("generation_ref");
        generation.put("actor_role", "MERCHANT");
        generation.put("source_stage", "RESPONDENT_INTAKE");
        generation.put("latest_source_ref", "MESSAGE_RESPONDENT_1");
        generation.put("source_context_hash", "b".repeat(64));
        ObjectNode row = factRow(matrix);
        ObjectNode respondent = row.withObjectProperty("positions").putObject("MERCHANT");
        respondent.put("stance", "CONFIRM");
        respondent.put("source_type", "DIRECT_PARTY_STATEMENT");
        respondent.putArray("source_refs").add("MESSAGE_RESPONDENT_1");
        return matrix;
    }

    private ObjectNode timeoutInitiatorMatrix() {
        ObjectNode matrix = baseMatrix("INITIATOR_FROZEN", 1);
        ObjectNode generation = matrix.putObject("generation_ref");
        generation.put("actor_role", "USER");
        generation.put("source_stage", "INITIATOR_INTAKE");
        generation.put("latest_source_ref", "MESSAGE_INITIATOR_1");
        generation.put("source_context_hash", "c".repeat(64));
        ObjectNode row = factRow(matrix);
        ObjectNode initiator = row.withObjectProperty("positions").putObject("USER");
        initiator.put("stance", "CONFIRM");
        initiator.put("source_type", "DIRECT_PARTY_STATEMENT");
        initiator.putArray("source_refs").add("MESSAGE_INITIATOR_1");
        return matrix;
    }

    private ObjectNode strictInitiatorMatrix() {
        return new IntakeInitiatorMatrixFreezer()
                .freeze(
                        CASE_ID,
                        com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.USER,
                        com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.MERCHANT,
                        legacyProjection());
    }

    private ObjectNode legacyProjection() {
        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", "unilateral_case_matrix.v1");
        matrix.put("matrix_version", 3);
        ObjectNode source = matrix.putObject("source_binding");
        source.put("case_id", CASE_ID);
        source.put("source_stage", "INTAKE");
        source.putArray("source_refs").add("MESSAGE_INITIATOR_1");
        source.put("latest_source_ref", "MESSAGE_INITIATOR_1");
        source.put("source_context_hash", "a".repeat(64));
        matrix.putObject("party_map")
                .put("initiator_role", "USER")
                .put("respondent_role", "MERCHANT");
        matrix.put("case_summary", "用户称约定的安装服务未履行。");
        matrix.putArray("summary_source_fact_ids").add("FACT_DELIVERY_SCOPE");
        matrix.putObject("claim_resolution")
                .put("initiator_role", "USER")
                .put("requested_resolution", "REFUND")
                .put("reason_summary", "约定的安装服务未履行。")
                .put("position_summary", "用户请求退款。")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        ObjectNode core = matrix.putObject("dispute_core_state");
        core.put("core_conflict", "约定的安装服务是否已经履行。");
        core.putArray("facts_in_dispute").add("安装服务履行情况");
        core.putArray("next_verification_focus").add("核验安装记录");
        ObjectNode row = matrix.putArray("fact_rows").addObject();
        row.put("fact_id", "FACT_DELIVERY_SCOPE");
        row.put("category", "FULFILLMENT");
        row.put("fact_target", "约定的安装服务是否已经履行。");
        row.put("materiality", "CORE");
        row.putObject("origin")
                .put("source_stage", "INTAKE")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        row.putObject("initiator_position")
                .put("stance", "CONFIRM")
                .put("position_summary", "用户称安装服务未履行。")
                .put("asserted_value", "安装服务缺失")
                .putArray("source_refs")
                .add("MESSAGE_INITIATOR_1");
        row.put("truth_status", "NOT_EVALUATED");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        return matrix;
    }

    private ObjectNode baseMatrix(String kind, int version) {
        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("matrix_id", "CASE_MATRIX_CANONICAL_V" + version);
        matrix.put("case_id", CASE_ID);
        matrix.put("matrix_version", version);
        matrix.put("matrix_kind", kind);
        ObjectNode parties = matrix.putObject("party_map");
        parties.put("initiator_role", "USER");
        parties.put("respondent_role", "MERCHANT");
        matrix.putArray("source_refs").add("MESSAGE_INITIATOR_1");
        return matrix;
    }

    private ObjectNode factRow(ObjectNode matrix) {
        ObjectNode row = matrix.putArray("fact_rows").addObject();
        row.put("fact_id", "FACT_CANONICAL_DECIMAL");
        row.put("materiality", "CORE");
        row.put("claimed_amount", new BigDecimal("150.0"));
        row.put("weight", new BigDecimal("1.2300"));
        row.putObject("positions");
        ObjectNode alignment = row.putObject("party_alignment");
        alignment.put("status", "AGREED");
        alignment.put("agreed_statement", "The amount is accepted.");
        alignment.putNull("conflict_summary");
        row.put("requires_resolution", false);
        return row;
    }
}
