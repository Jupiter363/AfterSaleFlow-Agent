package com.example.dispute.evidence.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceDossierQueryService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceDossierRepository dossierRepository;
    private final EvidenceDossierItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    public EvidenceDossierQueryService(
            FulfillmentCaseRepository caseRepository,
            EvidenceDossierRepository dossierRepository,
            EvidenceDossierItemRepository itemRepository,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.dossierRepository = dossierRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public FrozenEvidenceDossierView get(
            String caseId, int version, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        EvidenceDossierEntity dossier =
                dossierRepository
                        .findByCaseIdAndDossierVersion(caseId, version)
                        .orElseThrow(() -> new IllegalArgumentException("dossier version not found"));
        return view(dossier);
    }

    @Transactional(readOnly = true)
    public FrozenEvidenceDossierView latest(
            String caseId, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        EvidenceDossierEntity dossier =
                dossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("dossier not found"));
        return view(dossier);
    }

    private FrozenEvidenceDossierView view(EvidenceDossierEntity dossier) {
        return new FrozenEvidenceDossierView(
                dossier.getCaseId(),
                dossier.getId(),
                dossier.getDossierVersion(),
                dossier.getDossierStatus(),
                readMap(dossier.getSummaryJson()),
                readList(dossier.getTimelineJson()),
                readMatrix(dossier.getMatrixSummaryJson()),
                itemRepository
                        .findAllByDossierIdOrderBySequenceNo(dossier.getId())
                        .stream()
                        .map(item -> item.getEvidenceId())
                        .toList());
    }

    private void assertCanAccess(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access evidence dossier");
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier summary", exception);
        }
    }

    private List<Map<String, Object>> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier projection", exception);
        }
    }

    private List<Map<String, Object>> readMatrix(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode matrix =
                    node.isObject() && node.path("fact_evidence_matrix").isArray()
                            ? node.path("fact_evidence_matrix")
                            : node;
            return objectMapper.convertValue(matrix, new TypeReference<>() {});
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new IllegalStateException("invalid dossier projection", exception);
        }
    }
}
