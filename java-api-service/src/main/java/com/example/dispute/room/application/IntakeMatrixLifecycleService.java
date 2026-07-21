package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeInitiatorMatrixFreezer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Service;

/** Applies lifecycle-only transitions to a semantically complete case matrix. */
@Service
public class IntakeMatrixLifecycleService {

    private final CaseIntakeDossierRepository repository;
    private final ObjectMapper objectMapper;
    private final IntakeInitiatorMatrixFreezer initiatorFreezer =
            new IntakeInitiatorMatrixFreezer();

    public IntakeMatrixLifecycleService(
            CaseIntakeDossierRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Freezes a Java-derived initiator matrix when a validated unilateral projection exists. */
    public FreezeResult freezeInitiatorIfPossible(
            FulfillmentCaseEntity dispute, String actorId) {
        var optionalDossier = repository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE);
        if (optionalDossier.isEmpty()) {
            return new FreezeResult(false, null, null);
        }
        CaseIntakeDossierEntity dossier = optionalDossier.orElseThrow();
        ObjectNode root = readObject(dossier.getDossierJson());
        JsonNode existing = root.path("case_fact_matrix");
        if (existing.isObject()) {
            // Legacy Intake already treated any case_fact_matrix projection as immutable here. The
            // unreachable formal adapter performs its stricter INITIATOR_FROZEN validation after
            // this domain call, inside the same transaction.
            return new FreezeResult(
                    false,
                    existing.path("matrix_kind").asText(null),
                    existing.path("content_hash").asText(null));
        }
        JsonNode unilateral = root.path("unilateral_case_matrix");
        if (!unilateral.isObject()) {
            return new FreezeResult(false, null, null);
        }
        ObjectNode frozen = initiatorFreezer.freeze(
                dispute.getId(),
                toContractRole(dispute.getInitiatorRole()),
                toContractRole(dispute.getRespondentRole()),
                (ObjectNode) unilateral);
        root.set("case_fact_matrix", frozen);
        String updated = write(root);
        dossier.replaceWith(
                updated,
                dossier.getQualityScore(),
                dossier.isReadyForNextStep(),
                dossier.getAdmissionRecommendation(),
                dossier.getSourceTurnNo(),
                actorId);
        repository.save(dossier);
        dispute.refreshIntakeResult(updated, actorId);
        return new FreezeResult(true, "INITIATOR_FROZEN", frozen.path("content_hash").asText());
    }

    /** Strict formal check used by the TEMPORAL branch authority after all rows are locked. */
    public ObjectNode requireInitiatorFrozen(FulfillmentCaseEntity dispute) {
        ObjectNode matrix = formalMatrix(dispute);
        initiatorFreezer.validateFrozen(
                matrix,
                dispute.getId(),
                toContractRole(dispute.getInitiatorRole()),
                toContractRole(dispute.getRespondentRole()));
        return matrix;
    }

    /**
     * Fail-closed respondent gate. Full delta derivation is enabled only after the P4.0 erratum is
     * re-authenticated; this method never fabricates a respondent stance from prose.
     */
    public ObjectNode requireBilateralFrozen(FulfillmentCaseEntity dispute) {
        ObjectNode matrix = formalMatrix(dispute);
        if (!"BILATERAL_FROZEN".equals(matrix.path("matrix_kind").asText())
                || !dispute.getId().equals(matrix.path("case_id").asText())
                || matrix.path("matrix_version").asLong() < 2
                || !matrix.path("parent_ref").isObject()
                || !matrix.path("parent_ref").path("content_hash").asText()
                        .matches("[0-9a-f]{64}")
                || !dispute.getInitiatorRole().name().equals(
                        matrix.path("party_map").path("initiator_role").asText())
                || !dispute.getRespondentRole().name().equals(
                        matrix.path("party_map").path("respondent_role").asText())
                || !dispute.getRespondentRole().name().equals(
                        matrix.path("generation_ref").path("actor_role").asText())
                || !"RESPONDENT_INTAKE".equals(
                        matrix.path("generation_ref").path("source_stage").asText())) {
            throw rejected(
                    "INTAKE_BILATERAL_MATRIX_NOT_READY",
                    "respondent confirmation requires a Java-derived bilateral matrix");
        }
        ObjectNode hashInput = matrix.deepCopy();
        JsonNode hash = hashInput.remove("content_hash");
        if (hash == null
                || !hash.isTextual()
                || !hash.asText().matches("[0-9a-f]{64}")
                || !hash.asText().equals(ContractJson.sha256Hex(hashInput))) {
            throw rejected(
                    "INTAKE_BILATERAL_MATRIX_HASH_INVALID",
                    "bilateral matrix content hash is not canonical");
        }
        JsonNode rows = matrix.path("fact_rows");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 200) {
            throw rejected(
                    "INTAKE_BILATERAL_MATRIX_ROWS_INVALID",
                    "bilateral matrix fact rows are invalid");
        }
        for (JsonNode row : rows) {
            JsonNode respondent = row.path("positions").path(dispute.getRespondentRole().name());
            String stance = respondent.path("stance").asText();
            if (!List.of("CONFIRM", "DENY", "PARTIAL", "NOT_ADDRESSED").contains(stance)
                    || (!"NOT_ADDRESSED".equals(stance)
                            && (!"DIRECT_PARTY_STATEMENT".equals(
                                            respondent.path("source_type").asText())
                                    || !respondent.path("source_refs").isArray()
                                    || respondent.path("source_refs").isEmpty()))
                    || "NOT_COMPUTED".equals(
                            row.path("party_alignment").path("status").asText())
                    || !row.path("requires_resolution").isBoolean()) {
                throw rejected(
                        "INTAKE_BILATERAL_MATRIX_ROWS_INVALID",
                        "bilateral matrix lacks an independently derived respondent position");
            }
        }
        return matrix;
    }

    private ObjectNode formalMatrix(FulfillmentCaseEntity dispute) {
        CaseIntakeDossierEntity dossier =
                repository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                        .orElseThrow(() -> rejected(
                                "INTAKE_FORMAL_DOSSIER_MISSING",
                                "formal Intake matrix dossier is missing"));
        ObjectNode root = readObject(dossier.getDossierJson());
        JsonNode matrix = root.path("case_fact_matrix");
        if (!matrix.isObject()
                || !"case_fact_matrix.v2".equals(matrix.path("schema_version").asText())) {
            throw rejected(
                    "INTAKE_FORMAL_MATRIX_MISSING",
                    "formal case_fact_matrix.v2 is missing");
        }
        return (ObjectNode) matrix;
    }

    private static com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole toContractRole(
            ActorRole role) {
        return com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.valueOf(role.name());
    }

    public record FreezeResult(boolean created, String matrixKind, String contentHash) {}

    public void freezeRespondentTimeout(FulfillmentCaseEntity dispute) {
        CaseIntakeDossierEntity dossier =
                repository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                        .orElseThrow(() -> new IllegalStateException("intake dossier not found"));
        ObjectNode root = readObject(dossier.getDossierJson());
        JsonNode candidate = root.path("case_fact_matrix");
        if (!candidate.isObject()
                || !"case_fact_matrix.v2".equals(
                        candidate.path("schema_version").asText())) {
            return; // Legacy cases keep their existing compatibility projection.
        }
        ObjectNode matrix = ((ObjectNode) candidate).deepCopy();
        String kind = matrix.path("matrix_kind").asText();
        if ("BILATERAL_FROZEN".equals(kind)
                || "RESPONDENT_TIMEOUT_FROZEN".equals(kind)) {
            return;
        }
        if (!"INITIATOR_FROZEN".equals(kind)) {
            throw new IllegalStateException("unsupported case matrix lifecycle state " + kind);
        }
        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("matrix_id", matrix.path("matrix_id").asText());
        parent.put("matrix_version", matrix.path("matrix_version").asInt());
        parent.put("content_hash", matrix.path("content_hash").asText());
        String timeoutRef = "INTAKE_TIMEOUT_" + dispute.getId();
        ActorRole respondent =
                dispute.getInitiatorRole() == ActorRole.USER
                        ? ActorRole.MERCHANT
                        : ActorRole.USER;
        matrix.set("parent_ref", parent);
        matrix.put("matrix_version", matrix.path("matrix_version").asInt() + 1);
        matrix.put(
                "matrix_id",
                "CASE_MATRIX_TIMEOUT_"
                        + parent.path("content_hash").asText().substring(0, 16).toUpperCase());
        matrix.put("matrix_kind", "RESPONDENT_TIMEOUT_FROZEN");
        ArrayNode sourceRefs = (ArrayNode) matrix.withArray("source_refs");
        sourceRefs.add(timeoutRef);
        ObjectNode generation = matrix.withObjectProperty("generation_ref");
        generation.put("actor_role", respondent.name());
        generation.put("source_stage", "RESPONDENT_TIMEOUT");
        generation.put("latest_source_ref", timeoutRef);
        ObjectNode sourceContext = objectMapper.createObjectNode();
        sourceContext.put("schema_version", "intake-timeout-source-context.v1");
        sourceContext.put("case_id", dispute.getId());
        sourceContext.put("parent_content_hash", parent.path("content_hash").asText());
        generation.put(
                "source_context_hash",
                ContractJson.sha256Hex(sourceContext));

        ArrayNode rows = (ArrayNode) matrix.path("fact_rows");
        for (JsonNode value : rows) {
            ObjectNode row = (ObjectNode) value;
            String initiatorStance =
                    row.path("positions")
                            .path(dispute.getInitiatorRole().name())
                            .path("stance")
                            .asText("NOT_ADDRESSED");
            String status =
                    List.of("CONFIRM", "DENY", "PARTIAL").contains(initiatorStance)
                            ? "ONE_SIDED"
                            : "UNRESOLVED";
            ObjectNode alignment = row.withObjectProperty("party_alignment");
            alignment.put("status", status);
            alignment.putNull("agreed_statement");
            alignment.put("conflict_summary", "被发起方未在统一截止时间前完成接待陈述。");
            row.put("requires_resolution", true);
        }
        matrix.set("fact_indexes", factIndexes(rows));
        matrix.remove("content_hash");
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        root.set("case_fact_matrix", matrix);
        String updated = write(root);
        dossier.replaceWith(
                updated,
                dossier.getQualityScore(),
                dossier.isReadyForNextStep(),
                dossier.getAdmissionRecommendation(),
                dossier.getSourceTurnNo(),
                "evidence-deadline");
        repository.save(dossier);
        dispute.refreshIntakeResult(updated, "evidence-deadline");
    }

    private ObjectNode factIndexes(ArrayNode rows) {
        ObjectNode indexes = objectMapper.createObjectNode();
        for (String key : List.of(
                "not_computed_fact_ids",
                "agreed_fact_ids",
                "partially_agreed_fact_ids",
                "contested_fact_ids",
                "one_sided_fact_ids",
                "unresolved_fact_ids",
                "core_fact_ids",
                "requires_resolution_fact_ids")) {
            indexes.putArray(key);
        }
        for (JsonNode row : rows) {
            String id = row.path("fact_id").asText();
            String status = row.path("party_alignment").path("status").asText();
            if ("ONE_SIDED".equals(status)) {
                indexes.withArray("one_sided_fact_ids").add(id);
            } else {
                indexes.withArray("unresolved_fact_ids").add(id);
            }
            if ("CORE".equals(row.path("materiality").asText())) {
                indexes.withArray("core_fact_ids").add(id);
            }
            indexes.withArray("requires_resolution_fact_ids").add(id);
        }
        return indexes;
    }

    private ObjectNode readObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isObject()) {
                throw new IllegalStateException("intake dossier must be an object");
            }
            return (ObjectNode) node;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid intake dossier", exception);
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize intake dossier", exception);
        }
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }
}
