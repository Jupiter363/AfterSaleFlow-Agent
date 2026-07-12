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
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix()
            throws Exception {
        Clock clock =
                Clock.fixed(Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvidenceDossierFreezer freezer =
                new EvidenceDossierFreezer(
                        dossierRepository,
                        dossierItemRepository,
                        evidenceRepository,
                        verificationRepository,
                        objectMapper,
                        clock);
        EvidenceItemEntity userLogisticsProof = evidence("EVIDENCE_USER_LOGISTICS");
        userLogisticsProof.applyParseSuccess(
                "用户上传的物流详情显示包裹已签收，但签收人身份与投递位置仍需核验。",
                "{\"ocr\":\"物流详情\"}",
                "parser");
        when(dossierRepository.findByCaseIdAndDossierVersion("CASE_FREEZE", 2))
                .thenReturn(Optional.empty());
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_FREEZE"))
                .thenReturn(List.of(userLogisticsProof));
        when(verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(
                                "EVIDENCE_USER_LOGISTICS"))
                .thenReturn(
                        Optional.of(
                                verification(
                                        userLogisticsProof,
                                        EvidenceVerificationStatus.VERIFIED)));
        when(dossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvidenceDossierEntity frozen = freezer.freeze("CASE_FREEZE", 2, "system");

        JsonNode summary = objectMapper.readTree(frozen.getSummaryJson());
        assertThat(summary.path("evidence_items")).hasSize(1);
        assertThat(summary.path("evidence_items").get(0).path("file_name").asText())
                .isEqualTo("proof.png");
        assertThat(summary.path("evidence_items").get(0).path("authenticity_score").asDouble())
                .isGreaterThanOrEqualTo(0.8);
        assertThat(summary.path("party_evidence_summary").path("USER").path("strong_points"))
                .hasSize(1);
        assertThat(summary.path("overall_confidence_score").asInt()).isGreaterThan(0);

        JsonNode matrix = objectMapper.readTree(frozen.getMatrixSummaryJson());
        assertThat(matrix.path("fact_evidence_matrix")).hasSize(1);
        assertThat(matrix.path("fact_evidence_matrix").get(0).path("supporting_evidence"))
                .hasSize(1);
        assertThat(matrix.toString()).doesNotContain("UNMAPPED");
    }

    @Test
    void freezeToleratesLegacyEvidenceWithoutParseStatus() {
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
        EvidenceItemEntity legacyEvidence = evidence("EVIDENCE_LEGACY_PARSE_STATUS");
        ReflectionTestUtils.setField(legacyEvidence, "parseStatus", null);
        when(dossierRepository.findByCaseIdAndDossierVersion("CASE_FREEZE", 3))
                .thenReturn(Optional.empty());
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_FREEZE"))
                .thenReturn(List.of(legacyEvidence));
        when(verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(
                                "EVIDENCE_LEGACY_PARSE_STATUS"))
                .thenReturn(Optional.empty());
        when(dossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvidenceDossierEntity frozen = freezer.freeze("CASE_FREEZE", 3, "system");

        assertThat(frozen.getDossierStatus()).isEqualTo("FROZEN");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvidenceDossierItemEntity>> snapshots =
                ArgumentCaptor.forClass(List.class);
        verify(dossierItemRepository).saveAll(snapshots.capture());
        assertThat(snapshots.getValue())
                .extracting(EvidenceDossierItemEntity::getEvidenceId)
                .containsExactly("EVIDENCE_LEGACY_PARSE_STATUS");
    }

    @Test
    void frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults()
            throws Exception {
        Clock clock =
                Clock.fixed(Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvidenceDossierFreezer freezer =
                new EvidenceDossierFreezer(
                        dossierRepository,
                        dossierItemRepository,
                        evidenceRepository,
                        verificationRepository,
                        objectMapper,
                        clock);
        EvidenceItemEntity item = evidence("EVIDENCE_MULTIMODAL_SCORE");
        EvidenceVerificationEntity verification =
                EvidenceVerificationEntity.create(
                        "VERIFY_MULTIMODAL_SCORE",
                        "CASE_FREEZE",
                        item.getId(),
                        2,
                        EvidenceVerificationStatus.PLAUSIBLE,
                        "{}",
                        """
                        {
                          "authenticity_score":0.37,
                          "relevance_score":0.93,
                          "completeness_score":0.64,
                          "assessment_confidence":0.88
                        }
                        """,
                        "{}",
                        false,
                        Instant.parse("2026-07-03T00:40:00Z"),
                        "evidence-clerk",
                        "TRACE_MULTIMODAL_SCORE");
        when(dossierRepository.findByCaseIdAndDossierVersion("CASE_FREEZE", 4))
                .thenReturn(Optional.empty());
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_FREEZE"))
                .thenReturn(List.of(item));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        item.getId()))
                .thenReturn(Optional.of(verification));
        when(dossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvidenceDossierEntity frozen = freezer.freeze("CASE_FREEZE", 4, "system");

        JsonNode evidenceItem = objectMapper.readTree(frozen.getSummaryJson())
                .path("evidence_items")
                .get(0);
        assertThat(evidenceItem.path("authenticity_score").asDouble()).isEqualTo(0.37);
        assertThat(evidenceItem.path("relevance_score").asDouble()).isEqualTo(0.93);
        assertThat(evidenceItem.path("completeness_score").asDouble()).isEqualTo(0.64);
        assertThat(evidenceItem.path("assessment_confidence").asDouble()).isEqualTo(0.88);
        JsonNode matrix = objectMapper.readTree(frozen.getMatrixSummaryJson());
        assertThat(matrix.path("fact_evidence_matrix").get(0).path("evidence_strength").asText())
                .isEqualTo("MEDIUM");
    }

    @Test
    void lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact() throws Exception {
        Clock clock =
                Clock.fixed(Instant.parse("2026-07-03T01:00:00Z"), ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvidenceDossierFreezer freezer =
                new EvidenceDossierFreezer(
                        dossierRepository,
                        dossierItemRepository,
                        evidenceRepository,
                        verificationRepository,
                        objectMapper,
                        clock);
        EvidenceItemEntity item = evidence("EVIDENCE_UNRELATED_IMAGE");
        EvidenceVerificationEntity verification =
                EvidenceVerificationEntity.create(
                        "VERIFY_UNRELATED_IMAGE",
                        "CASE_FREEZE",
                        item.getId(),
                        3,
                        EvidenceVerificationStatus.PLAUSIBLE,
                        "{}",
                        """
                        {
                          "analysis_method":"MULTIMODAL",
                          "authenticity_score":0.92,
                          "relevance_score":0.05,
                          "completeness_score":0.90,
                          "assessment_confidence":0.88,
                          "fact_links":[{
                            "fact_id":"FACT_SIGNED_BY_USER",
                            "relation":"SUPPORTS",
                            "reason":"图片内容与签收事实缺少直接关联",
                            "confidence":0.95
                          }]
                        }
                        """,
                        "{}",
                        false,
                        Instant.parse("2026-07-03T00:45:00Z"),
                        "evidence-clerk",
                        "TRACE_UNRELATED_IMAGE");
        when(dossierRepository.findByCaseIdAndDossierVersion("CASE_FREEZE", 5))
                .thenReturn(Optional.empty());
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_FREEZE"))
                .thenReturn(List.of(item));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        item.getId()))
                .thenReturn(Optional.of(verification));
        when(dossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvidenceDossierEntity frozen = freezer.freeze("CASE_FREEZE", 5, "system");

        JsonNode summary = objectMapper.readTree(frozen.getSummaryJson());
        assertThat(summary.path("verified_facts")).isEmpty();
        assertThat(summary.path("contested_facts")).hasSize(1);
        JsonNode matrix = objectMapper.readTree(frozen.getMatrixSummaryJson())
                .path("fact_evidence_matrix")
                .get(0);
        assertThat(matrix.path("fact_id").asText()).isEqualTo("FACT_SIGNED_BY_USER");
        assertThat(matrix.path("evidence_strength").asText()).isNotEqualTo("HIGH");
    }

    private static EvidenceItemEntity evidence(String id) {
        EvidenceItemEntity evidence =
                EvidenceItemEntity.uploaded(
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
        evidence.markSubmitted(
                "BATCH_" + id,
                OffsetDateTime.parse("2026-07-03T00:10:00Z"),
                "user-local");
        return evidence;
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
