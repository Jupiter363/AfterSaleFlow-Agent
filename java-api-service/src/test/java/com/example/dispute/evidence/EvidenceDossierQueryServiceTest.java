package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceDossierQueryService;
import com.example.dispute.evidence.application.FrozenEvidenceDossierView;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceDossierQueryServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private EvidenceDossierItemRepository itemRepository;

    private EvidenceDossierQueryService service;

    @BeforeEach
    void setUp() {
        service =
                new EvidenceDossierQueryService(
                        caseRepository,
                        dossierRepository,
                        itemRepository,
                        new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void latestReadsFrozenObjectShapedEvidenceMatrix() {
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(dossierRepository.findTopByCaseIdOrderByDossierVersionDesc("CASE_evidence"))
                .thenReturn(Optional.of(frozenDossier()));
        when(itemRepository.findAllByDossierIdOrderBySequenceNo("DOSSIER_FROZEN"))
                .thenReturn(List.of());

        FrozenEvidenceDossierView view = service.latest("CASE_evidence", actor());

        assertThat(view.matrix()).hasSize(1);
        assertThat(view.matrix().get(0).get("fact")).isEqualTo("物流显示已签收");
    }

    private static EvidenceDossierEntity frozenDossier() {
        return EvidenceDossierEntity.frozen(
                "DOSSIER_FROZEN",
                "CASE_evidence",
                2,
                "system",
                "{\"evidence_count\":1}",
                "[]",
                """
                {
                  "fact_evidence_matrix": [
                    {
                      "fact_id": "FACT_SIGNED",
                      "fact": "物流显示已签收",
                      "supporting_evidence": ["EVIDENCE_LOGISTICS"]
                    }
                  ],
                  "unmapped_evidence": []
                }
                """);
    }

    private static AuthenticatedActor actor() {
        return new AuthenticatedActor("user-evidence", ActorRole.USER);
    }

    private static FulfillmentCaseEntity caseEntity() {
        return FulfillmentCaseEntity.create(
                "CASE_evidence",
                "order-evidence",
                null,
                "user-evidence",
                "merchant-evidence",
                "idem-evidence",
                "DISPUTE",
                "LOGISTICS_DISPUTE",
                "signed status is disputed",
                RiskLevel.HIGH,
                "user-evidence");
    }
}
