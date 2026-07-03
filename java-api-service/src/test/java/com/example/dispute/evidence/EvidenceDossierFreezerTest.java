package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceDossierItemEntity;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceDossierFreezerTest {

    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private EvidenceDossierItemRepository dossierItemRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;

    @Test
    void rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion() {
        Clock clock =
                Clock.fixed(Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        EvidenceDossierFreezer freezer =
                new EvidenceDossierFreezer(
                        dossierRepository,
                        dossierItemRepository,
                        evidenceRepository,
                        verificationRepository,
                        new ObjectMapper().findAndRegisterModules(),
                        clock);
        EvidenceItemEntity accepted = evidence("EVIDENCE_ACCEPTED");
        EvidenceItemEntity rejected = evidence("EVIDENCE_REJECTED");
        when(dossierRepository.findByCaseIdAndDossierVersion("CASE_FREEZE", 1))
                .thenReturn(Optional.empty());
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_FREEZE"))
                .thenReturn(List.of(accepted, rejected));
        when(verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(
                                "EVIDENCE_ACCEPTED"))
                .thenReturn(Optional.of(verification(accepted, EvidenceVerificationStatus.VERIFIED)));
        when(verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(
                                "EVIDENCE_REJECTED"))
                .thenReturn(Optional.of(verification(rejected, EvidenceVerificationStatus.REJECTED)));
        when(dossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvidenceDossierEntity frozen = freezer.freeze("CASE_FREEZE", 1, "system");

        assertThat(frozen.getDossierStatus()).isEqualTo("FROZEN");
        assertThat(frozen.getDossierVersion()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvidenceDossierItemEntity>> snapshots =
                ArgumentCaptor.forClass(List.class);
        verify(dossierItemRepository).saveAll(snapshots.capture());
        assertThat(snapshots.getValue())
                .extracting(EvidenceDossierItemEntity::getEvidenceId)
                .containsExactly("EVIDENCE_ACCEPTED");
        verify(evidenceRepository)
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        "CASE_FREEZE");
    }

    private static EvidenceItemEntity evidence(String id) {
        return EvidenceItemEntity.uploaded(
                id,
                "CASE_FREEZE",
                "DOSSIER_COLLECTING",
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                "USER",
                "user-local",
                "evidence-original",
                "CASE_FREEZE/" + id + "/proof.png",
                "hash-" + id,
                "proof.png",
                "image/png",
                12,
                "PARTIES",
                OffsetDateTime.parse("2026-07-03T00:00:00Z"));
    }

    private static EvidenceVerificationEntity verification(
            EvidenceItemEntity item, EvidenceVerificationStatus status) {
        return EvidenceVerificationEntity.create(
                "VERIFY_" + item.getId(),
                "CASE_FREEZE",
                item.getId(),
                1,
                status,
                "{}",
                "{}",
                "[]",
                false,
                Instant.parse("2026-07-03T00:30:00Z"),
                "system",
                "trace-freeze");
    }
}
