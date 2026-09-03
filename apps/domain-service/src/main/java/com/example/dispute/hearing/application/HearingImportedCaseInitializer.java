package com.example.dispute.hearing.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Materializes an explicit one-sided frozen baseline for trusted imports already in Hearing. */
@Service
public class HearingImportedCaseInitializer {

    private static final String ACTOR_ID = "external-hearing-import";

    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final EvidenceDossierRepository evidenceDossierRepository;
    private final HearingFlowRuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    public HearingImportedCaseInitializer(
            CaseIntakeDossierRepository intakeDossierRepository,
            EvidenceDossierRepository evidenceDossierRepository,
            HearingFlowRuntimeService runtimeService,
            ObjectMapper objectMapper) {
        this.intakeDossierRepository = intakeDossierRepository;
        this.evidenceDossierRepository = evidenceDossierRepository;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void initialize(FulfillmentCaseEntity dispute) {
        if (dispute.getCaseStatus() != CaseStatus.HEARING_OPEN
                && dispute.getCaseStatus() != CaseStatus.HEARING) {
            throw new IllegalArgumentException("imported hearing baseline requires a Hearing case");
        }
        ensureIntakeBaseline(dispute);
        ensureEvidenceBaseline(dispute);
        runtimeService.startAfterEvidenceSealed(dispute.getId());
    }

    private void ensureIntakeBaseline(FulfillmentCaseEntity dispute) {
        if (intakeDossierRepository
                .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                .isPresent()) {
            return;
        }
        String bindingHash = bindingHash(dispute);
        ObjectNode matrix = importedMatrix(dispute, bindingHash);
        ObjectNode dossier = objectMapper.createObjectNode();
        dossier.put("schema_version", "case_intake_dossier.imported.v1");
        dossier.put("provenance", "TRUSTED_EXTERNAL_IMPORT");
        dossier.set("case_fact_matrix", matrix);
        intakeDossierRepository.save(
                CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_" + bindingHash.substring(0, 32).toUpperCase(Locale.ROOT),
                        dispute.getId(),
                        RoomType.INTAKE,
                        json(dossier),
                        50,
                        true,
                        "IMPORTED_HEARING_BASELINE",
                        1,
                        ACTOR_ID));
    }

    private void ensureEvidenceBaseline(FulfillmentCaseEntity dispute) {
        var existing =
                evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(
                        dispute.getId());
        if (existing.isPresent()) {
            if (!"FROZEN".equals(existing.orElseThrow().getDossierStatus())) {
                throw new IllegalStateException(
                        "imported hearing requires a frozen evidence dossier");
            }
            return;
        }
        String bindingHash = bindingHash(dispute);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("evidence_count", 0);
        summary.putArray("evidence_items");
        summary.putArray("evidence_gaps")
                .add("The trusted import contained no frozen evidence attachments.");
        summary.put("provenance", "TRUSTED_EXTERNAL_IMPORT");
        evidenceDossierRepository.save(
                EvidenceDossierEntity.frozen(
                        "EVIDENCE_DOSSIER_"
                                + bindingHash.substring(0, 32).toUpperCase(Locale.ROOT),
                        dispute.getId(),
                        1,
                        ACTOR_ID,
                        json(summary),
                        "[]",
                        "{}"));
    }

    private ObjectNode importedMatrix(FulfillmentCaseEntity dispute, String bindingHash) {
        String sourceRef = "IMPORT_" + bindingHash.substring(0, 24).toUpperCase(Locale.ROOT);
        String factId = "FACT_IMPORT_" + bindingHash.substring(0, 24).toUpperCase(Locale.ROOT);
        ActorRole initiator = dispute.getInitiatorRole();
        ActorRole respondent = initiator == ActorRole.USER ? ActorRole.MERCHANT : ActorRole.USER;
        String summary =
                nonBlank(
                        dispute.getDescription(),
                        nonBlank(dispute.getTitle(), "Imported hearing dispute"));

        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("case_id", dispute.getId());
        matrix.put("matrix_id", "CASE_MATRIX_" + bindingHash.substring(0, 32).toUpperCase(Locale.ROOT));
        matrix.put("matrix_version", 1);
        matrix.put("matrix_kind", "RESPONDENT_TIMEOUT_FROZEN");
        matrix.putNull("parent_ref");
        matrix.putObject("party_map")
                .put("initiator_role", initiator.name())
                .put("respondent_role", respondent.name());
        matrix.putArray("source_refs").add(sourceRef);
        matrix.putObject("case_overview")
                .put("neutral_summary", summary)
                .put("core_conflict", nonBlank(dispute.getTitle(), summary))
                .putArray("summary_source_fact_ids")
                .add(factId);

        ObjectNode claims = matrix.putObject("claims");
        claims.putObject("initiator_claim")
                .put("initiator_role", initiator.name())
                .put("requested_resolution", "UNKNOWN")
                .putNull("requested_amount")
                .put("requested_items", nonBlank(dispute.getOrderId(), "Imported order"))
                .put("reason_summary", summary)
                .put("position_summary", summary)
                .putArray("source_refs")
                .add(sourceRef);
        claims.putNull("respondent_reported_by_initiator");
        claims.putNull("respondent_direct");
        claims.put(
                "claim_conflict",
                "The trusted import did not include a direct respondent statement.");

        ObjectNode fact = matrix.putArray("fact_rows").addObject();
        fact.put("fact_id", factId);
        fact.put("category", "OTHER");
        fact.put("fact_target", nonBlank(dispute.getTitle(), summary));
        fact.put("materiality", "CORE");
        fact.putObject("origin")
                .put("introduced_stage", "INITIATOR_INTAKE")
                .putArray("source_refs")
                .add(sourceRef);
        ObjectNode positions = fact.putObject("positions");
        partyPosition(positions.putObject("USER"), initiator == ActorRole.USER, summary, sourceRef);
        partyPosition(
                positions.putObject("MERCHANT"),
                initiator == ActorRole.MERCHANT,
                summary,
                sourceRef);
        fact.putObject("party_alignment")
                .put("status", "ONE_SIDED")
                .putNull("agreed_statement")
                .put("conflict_summary", "No direct respondent statement was imported.");
        fact.put("requires_resolution", true);
        fact.put("truth_status", "NOT_EVALUATED");
        fact.put("evidence_coverage_status", "NOT_COVERED_BY_FROZEN_DOSSIER");
        matrix.putArray("fact_relationships");
        matrix.putObject("generation_ref")
                .put("actor_role", "SYSTEM")
                .put("source_stage", "RESPONDENT_TIMEOUT")
                .put("latest_source_ref", sourceRef)
                .put("source_context_hash", bindingHash);
        ObjectNode indexes = matrix.putObject("fact_indexes");
        indexes.putArray("not_computed_fact_ids");
        indexes.putArray("agreed_fact_ids");
        indexes.putArray("partially_agreed_fact_ids");
        indexes.putArray("contested_fact_ids");
        indexes.putArray("one_sided_fact_ids").add(factId);
        indexes.putArray("unresolved_fact_ids");
        indexes.putArray("core_fact_ids").add(factId);
        indexes.putArray("requires_resolution_fact_ids").add(factId);
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        return matrix;
    }

    private static void partyPosition(
            ObjectNode position, boolean initiator, String summary, String sourceRef) {
        position.put("stance", initiator ? "CONFIRM" : "NOT_ADDRESSED");
        position.put(
                "position_summary",
                initiator ? summary : "No direct position was included in the trusted import.");
        position.putNull("asserted_value");
        position.put(
                "source_type", initiator ? "DIRECT_PARTY_STATEMENT" : "NO_DIRECT_POSITION");
        ArrayNode refs = position.putArray("source_refs");
        if (initiator) {
            refs.add(sourceRef);
        }
    }

    private String bindingHash(FulfillmentCaseEntity dispute) {
        ObjectNode binding = objectMapper.createObjectNode();
        binding.put("case_id", dispute.getId());
        binding.put("source_system", nonBlank(dispute.getSourceSystem(), "UNKNOWN"));
        binding.put("external_case_ref", nonBlank(dispute.getExternalCaseRef(), dispute.getId()));
        binding.put("case_status", dispute.getCaseStatus().name());
        binding.put("current_room", nonBlank(dispute.getCurrentRoom(), "HEARING"));
        return ContractJson.sha256Hex(binding);
    }

    private String json(ObjectNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize imported hearing baseline", exception);
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
