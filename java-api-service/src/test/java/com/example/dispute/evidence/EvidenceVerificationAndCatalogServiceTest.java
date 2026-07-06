package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceCatalogService;
import com.example.dispute.evidence.application.EvidenceVerificationCommand;
import com.example.dispute.evidence.application.EvidenceVerificationService;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceVerificationAndCatalogServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;

    private EvidenceVerificationService verificationService;
    private EvidenceCatalogService catalogService;

    @BeforeEach
    void setUp() {
        Clock clock =
                Clock.fixed(
                        Instant.parse("2026-07-03T00:00:00Z"),
                        ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();
        verificationService =
                new EvidenceVerificationService(
                        caseRepository,
                        evidenceRepository,
                        verificationRepository,
                        objectMapper,
                        clock);
        catalogService =
                new EvidenceCatalogService(
                        caseRepository,
                        evidenceRepository,
                        verificationRepository);
    }

    @Test
    void privateEvidenceIsHiddenFromCounterpartyButVisibleToReviewer() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity merchantPrivate =
                evidence(
                        "EVIDENCE_MERCHANT",
                        "MERCHANT",
                        "merchant-local",
                        "PRIVATE");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(merchantPrivate));
        when(verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(
                                merchantPrivate.getId()))
                .thenReturn(Optional.empty());

        var catalog =
                catalogService.catalog(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(catalog.items()).isEmpty();

        var reviewerCatalog =
                catalogService.catalog(
                        dispute.getId(),
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER));

        assertThat(reviewerCatalog.items()).singleElement().satisfies(
                item -> {
                    assertThat(item.evidenceId()).isEqualTo("EVIDENCE_MERCHANT");
                    assertThat(item.submittedByRole()).isEqualTo("MERCHANT");
                    assertThat(item.visibility()).isEqualTo("PRIVATE");
                    assertThat(item.contentUrl()).contains("/content");
                    assertThat(item.redacted()).isFalse();
                });
    }

    @Test
    void deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity item =
                evidence("EVIDENCE_USER", "USER", "user-local", "PARTIES");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(evidenceRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(item.getId()))
                .thenReturn(Optional.empty());
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var verified =
                verificationService.verify(
                        dispute.getId(),
                        item.getId(),
                        new EvidenceVerificationCommand(
                                true, true, true, true, true, false, false, "{}"),
                        new AuthenticatedActor("evidence-clerk", ActorRole.SYSTEM),
                        "TRACE_verified");
        var rejected =
                verificationService.verify(
                        dispute.getId(),
                        item.getId(),
                        new EvidenceVerificationCommand(
                                true, true, true, false, true, false, false, "{}"),
                        new AuthenticatedActor("evidence-clerk", ActorRole.SYSTEM),
                        "TRACE_rejected");

        assertThat(verified.status()).isEqualTo(EvidenceVerificationStatus.VERIFIED);
        assertThat(rejected.status()).isEqualTo(EvidenceVerificationStatus.REJECTED);
        assertThat(rejected.includeInFrozenDossier()).isFalse();
        assertThat(rejected.auditable()).isTrue();
    }

    private static EvidenceItemEntity evidence(
            String id, String role, String actorId, String visibility) {
        return EvidenceItemEntity.uploaded(
                id,
                "CASE_EVIDENCE_ROOM",
                "DOSSIER_1",
                "PHOTO",
                role + "_UPLOAD",
                role,
                actorId,
                "evidence-original",
                "case/" + id,
                "hash-" + id,
                id + ".png",
                "image/png",
                1024,
                visibility,
                OffsetDateTime.parse("2026-07-02T00:00:00Z"));
    }

    private static FulfillmentCaseEntity evidenceCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_ROOM",
                "ORDER-EVIDENCE",
                null,
                "LOG-EVIDENCE",
                "user-local",
                "merchant-local",
                "idem-evidence",
                "SIGNED_NOT_RECEIVED",
                "签收未收到",
                "证据室已开放",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "OMS",
                "EXT-EVIDENCE",
                "external-adapter");
    }
}
