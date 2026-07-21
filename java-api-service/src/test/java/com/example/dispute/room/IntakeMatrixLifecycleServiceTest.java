package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.room.application.IntakeMatrixLifecycleService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
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
    void respondentTimeoutWritesAnRfc8785CanonicalMatrixHash() throws Exception {
        ObjectNode matrix = initiatorMatrix();
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

    private FulfillmentCaseEntity dispute() {
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        when(dispute.getId()).thenReturn(CASE_ID);
        when(dispute.getInitiatorRole()).thenReturn(ActorRole.USER);
        when(dispute.getRespondentRole()).thenReturn(ActorRole.MERCHANT);
        return dispute;
    }

    private CaseIntakeDossierEntity dossier(ObjectNode matrix) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", "intake_case_detail.v1");
        root.set("case_fact_matrix", matrix);
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

    private ObjectNode initiatorMatrix() {
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
