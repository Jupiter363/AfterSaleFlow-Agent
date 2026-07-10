package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceCatalogService;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceCatalogServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;

    private EvidenceCatalogService service;

    @BeforeEach
    void setUp() {
        service =
                new EvidenceCatalogService(
                        caseRepository, evidenceRepository, verificationRepository);
    }

    @Test
    void hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty() {
        FulfillmentCaseEntity dispute = dispute(CaseStatus.HEARING, "HEARING");
        EvidenceItemEntity userEvidence = submittedEvidence("PARTIES");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(userEvidence));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        userEvidence.getId()))
                .thenReturn(Optional.empty());

        var catalog =
                service.catalog(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT));

        assertThat(catalog.items()).hasSize(1);
        assertThat(catalog.items().getFirst().submittedByRole()).isEqualTo("USER");
        assertThat(catalog.items().getFirst().redacted()).isFalse();
        assertThat(catalog.items().getFirst().contentUrl())
                .contains("/api/disputes/CASE_CATALOG_TEST/evidence/EVIDENCE_SHARED/content");
    }

    @Test
    void evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing() {
        FulfillmentCaseEntity dispute = dispute(CaseStatus.EVIDENCE_OPEN, "EVIDENCE");
        EvidenceItemEntity userEvidence = submittedEvidence("PARTIES");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(userEvidence));

        var catalog =
                service.catalog(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT));

        assertThat(catalog.items()).isEmpty();
    }

    @Test
    void hearingCatalogKeepsPendingCounterpartyEvidenceHidden() {
        FulfillmentCaseEntity dispute = dispute(CaseStatus.HEARING, "HEARING");
        EvidenceItemEntity pendingUserEvidence = evidence("PARTIES");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(pendingUserEvidence));

        var catalog =
                service.catalog(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT));

        assertThat(catalog.items()).isEmpty();
    }

    private static EvidenceItemEntity submittedEvidence(String visibility) {
        EvidenceItemEntity evidence = evidence(visibility);
        evidence.markSubmitted(
                "EVIDENCE_BATCH_SHARED",
                OffsetDateTime.parse("2026-07-08T00:01:00Z"),
                "user-local");
        return evidence;
    }

    private static EvidenceItemEntity evidence(String visibility) {
        return EvidenceItemEntity.uploaded(
                "EVIDENCE_SHARED",
                "CASE_CATALOG_TEST",
                "DOSSIER_1",
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                "USER",
                "user-local",
                "evidence-original",
                "cases/CASE_CATALOG_TEST/EVIDENCE_SHARED.md",
                "hash-shared",
                "shared-logistics.md",
                "text/markdown",
                128,
                visibility,
                OffsetDateTime.parse("2026-07-08T00:00:00Z"));
    }

    private static FulfillmentCaseEntity dispute(CaseStatus status, String currentRoom) {
        return FulfillmentCaseEntity.imported(
                "CASE_CATALOG_TEST",
                "ORDER-CATALOG",
                null,
                "LOG-CATALOG",
                "user-local",
                "merchant-local",
                "idem-catalog",
                "SIGNED_NOT_RECEIVED",
                "Marked delivered but not received",
                "The user states that the signed parcel was never received.",
                RiskLevel.MEDIUM,
                status,
                currentRoom,
                OffsetDateTime.parse("2026-07-08T03:00:00Z"),
                "OMS",
                "EXT-CATALOG",
                "external-adapter");
    }
}
