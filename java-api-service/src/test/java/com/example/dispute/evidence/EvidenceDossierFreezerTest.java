package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EvidenceDossierFreezerTest {

    private static final String CASE_ID = "CASE_FREEZE_V3";
    private static final Instant FROZEN_AT = Instant.parse("2026-08-20T01:00:00Z");

    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private EvidenceDossierItemRepository dossierItemRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;

    private ObjectMapper objectMapper;
    private EvidenceDossierFreezer freezer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        freezer =
                new EvidenceDossierFreezer(
                        dossierRepository,
                        dossierItemRepository,
                        evidenceRepository,
                        verificationRepository,
                        intakeDossierRepository,
                        objectMapper,
                        Clock.fixed(FROZEN_AT, ZoneOffset.UTC));
    }

    @Test
    void freezesOnlyV3ModelAssessmentAndPreservesLowScoreFactBinding() throws Exception {
        EvidenceItemEntity accepted = evidence("EVIDENCE_ACCEPTED_V3");
        EvidenceItemEntity rejected = evidence("EVIDENCE_REJECTED_V3");
        EvidenceVerificationEntity acceptedVerification =
                verification(
                        accepted,
                        EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW,
                        v3AssessmentJson(),
                        true);
        EvidenceVerificationEntity rejectedVerification =
                verification(rejected, EvidenceVerificationStatus.REJECTED, "{}", false);
        stubFreezeAuthority(1, List.of(accepted, rejected));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        accepted.getId()))
                .thenReturn(Optional.of(acceptedVerification));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        rejected.getId()))
                .thenReturn(Optional.of(rejectedVerification));

        EvidenceDossierEntity frozen = freezer.freeze(CASE_ID, 1, "system");

        assertThat(frozen.getDossierStatus()).isEqualTo("FROZEN");
        JsonNode summary = objectMapper.readTree(frozen.getSummaryJson());
        JsonNode frozenItem = summary.path("evidence_items").get(0);
        assertThat(summary.path("evidence_items")).hasSize(1);
        assertThat(frozenItem.path("evidence_id").asText()).isEqualTo(accepted.getId());
        assertThat(frozenItem.path("authenticity_score").asDouble()).isEqualTo(0.78);
        assertThat(frozenItem.path("relevance_score").asDouble()).isEqualTo(0.22);
        assertThat(frozenItem.path("completeness_score").asDouble()).isEqualTo(0.66);
        assertThat(frozenItem.path("assessment_confidence").asDouble()).isEqualTo(0.81);
        assertThat(frozenItem.path("relevance_score_explanation").asText())
                .isEqualTo("材料内容与物流事实仅有有限关联。");
        assertThat(frozenItem.path("risk_level").asText()).isEqualTo("HIGH");
        assertThat(frozenItem.path("risk_explanation").asText())
                .isEqualTo("来源链存在重大缺口，需要人工确认。");
        assertThat(frozenItem.path("reason_details").get(0).path("code").asText())
                .isEqualTo("LOW_RELEVANCE_SCORE");
        assertThat(summary.toString()).doesNotContain(rejected.getId());

        JsonNode wrapper = objectMapper.readTree(frozen.getMatrixSummaryJson());
        JsonNode matrix = wrapper.path("fact_evidence_matrix");
        JsonNode link = matrix.path("links").get(0);
        assertThat(wrapper.path("schema_version").asText())
                .isEqualTo("evidence-dossier-matrix-summary.v3");
        assertThat(wrapper.has("fact_evidence_matrix_v2")).isFalse();
        assertThat(wrapper.has("human_review_tasks")).isFalse();
        assertThat(matrix.path("schema_version").asText()).isEqualTo("fact_evidence_matrix.v3");
        assertThat(link.path("fact_id").asText()).isEqualTo("FACT_DELIVERY");
        assertThat(link.path("evidence_id").asText()).isEqualTo(accepted.getId());
        assertThat(link.path("relation").asText()).isEqualTo("CONTENT_SUPPORTS");
        assertThat(link.path("source_unit_id").asText()).isEqualTo("SOURCE_UNIT_01");
        assertThat(link.path("observation_slot").asText()).isEqualTo("OBS_01");
        assertThat(link.has("confidence")).isFalse();
        assertThat(link.has("evidence_strength")).isFalse();
        assertThat(wrapper.path("human_review_reasons").get(0).path("reason_details").get(1)
                        .path("code")
                        .asText())
                .isEqualTo("HIGH_RISK_FLAG");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvidenceDossierItemEntity>> snapshots =
                ArgumentCaptor.forClass(List.class);
        verify(dossierItemRepository).saveAll(snapshots.capture());
        assertThat(snapshots.getValue())
                .extracting(EvidenceDossierItemEntity::getEvidenceId)
                .containsExactly(accepted.getId());
        String snapshotJson =
                (String)
                        ReflectionTestUtils.getField(
                                snapshots.getValue().get(0), "evidenceSnapshotJson");
        JsonNode snapshot = objectMapper.readTree(snapshotJson);
        assertThat(snapshot.path("authenticity_score_explanation").asText())
                .isEqualTo("材料来源能够读取，但缺少平台原始导出。");
        assertThat(snapshot.path("assessment_public_text").asText())
                .isEqualTo("该材料只能有限支持物流事实，且来源链仍需人工确认。");
        assertThat(snapshot.has("risk_flags")).isFalse();
    }

    @Test
    void failsClosedWithoutFormalIntakeMatrixAuthority() {
        when(dossierRepository.findByCaseIdAndDossierVersion(CASE_ID, 1))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> freezer.freeze(CASE_ID, 1, "system"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("formal intake dossier is required for evidence freeze");

        verify(evidenceRepository, never())
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(any());
    }

    @Test
    void failsClosedWhenSubmittedMaterialHasNoV3Assessment() {
        EvidenceItemEntity item = evidence("EVIDENCE_WITHOUT_ASSESSMENT");
        when(dossierRepository.findByCaseIdAndDossierVersion(CASE_ID, 1))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(intakeDossier()));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(CASE_ID))
                .thenReturn(List.of(item));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        item.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> freezer.freeze(CASE_ID, 1, "system"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("frozen Evidence material has no v3 model assessment");

        verify(dossierRepository, never()).save(any());
    }

    private void stubFreezeAuthority(int version, List<EvidenceItemEntity> evidenceItems) {
        when(dossierRepository.findByCaseIdAndDossierVersion(CASE_ID, version))
                .thenReturn(Optional.empty());
        when(dossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.empty());
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(intakeDossier()));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(CASE_ID))
                .thenReturn(evidenceItems);
        when(dossierRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static CaseIntakeDossierEntity intakeDossier() {
        return CaseIntakeDossierEntity.create(
                "INTAKE_DOSSIER_FREEZE_V3",
                CASE_ID,
                RoomType.INTAKE,
                """
                {
                  "case_fact_matrix": {
                    "schema_version": "case_fact_matrix.v2",
                    "case_id": "CASE_FREEZE_V3",
                    "matrix_id": "CASE_FACT_MATRIX_FREEZE_V3",
                    "matrix_version": 3,
                    "content_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "fact_rows": [
                      {"fact_id": "FACT_DELIVERY"},
                      {"fact_id": "FACT_GOODS_CONDITION"}
                    ]
                  }
                }
                """,
                90,
                true,
                "ACCEPTED",
                3,
                "dispute-intake-officer");
    }

    private static EvidenceItemEntity evidence(String id) {
        EvidenceItemEntity evidence =
                EvidenceItemEntity.uploaded(
                        id,
                        CASE_ID,
                        "DOSSIER_COLLECTING",
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "USER",
                        "user-local",
                        "evidence-original",
                        CASE_ID + "/" + id + "/proof.png",
                        "hash-" + id,
                        "proof.png",
                        "image/png",
                        12,
                        "PARTIES",
                        OffsetDateTime.parse("2026-08-20T00:00:00Z"));
        evidence.markSubmitted(
                "BATCH_" + id,
                OffsetDateTime.parse("2026-08-20T00:10:00Z"),
                "user-local");
        evidence.recordSubmissionDeclaration(
                """
                {"claimed_fact":"物流截图用于证明包裹签收状态","truth_attested":true,
                "attestation_version":"EVIDENCE_TRUTH_ATTESTATION_V1",
                "attestation_scope":["AUTHENTICITY","CLAIMED_FACT_RELEVANCE"],
                "attestation_role":"USER","attested_by":"user-local"}
                """,
                "user-local");
        return evidence;
    }

    private static EvidenceVerificationEntity verification(
            EvidenceItemEntity item,
            EvidenceVerificationStatus status,
            String findingsJson,
            boolean requiresHumanReview) {
        return EvidenceVerificationEntity.create(
                "VERIFY_" + item.getId(),
                CASE_ID,
                item.getId(),
                1,
                status,
                "{}",
                findingsJson,
                "{}",
                requiresHumanReview,
                Instant.parse("2026-08-20T00:30:00Z"),
                "evidence-clerk",
                "trace-freeze-v3");
    }

    private static String v3AssessmentJson() {
        return """
                {
                  "schema_version":"evidence-turn-result.v3",
                  "authenticity_score":0.78,
                  "authenticity_score_explanation":"材料来源能够读取，但缺少平台原始导出。",
                  "relevance_score":0.22,
                  "relevance_score_explanation":"材料内容与物流事实仅有有限关联。",
                  "completeness_score":0.66,
                  "completeness_score_explanation":"材料包含主要节点，但上下文不完整。",
                  "assessment_confidence":0.81,
                  "assessment_confidence_explanation":"可读范围内判断较稳定。",
                  "risk_level":"HIGH",
                  "risk_explanation":"来源链存在重大缺口，需要人工确认。",
                  "source_basis":["解析文本中的物流节点"],
                  "formation_time_assessment":"形成时间只能部分核对。",
                  "findings":[{"finding_type":"LOGISTICS_RECORD","description":"可见物流节点"}],
                  "limitations":["缺少平台原始导出"],
                  "unsupported_claims":["不能单独证明商品故障时间"],
                  "assessment_public_text":"该材料只能有限支持物流事实，且来源链仍需人工确认。",
                  "verification_feedback":"该材料只能有限支持物流事实，且来源链仍需人工确认。",
                  "requires_human_review":true,
                  "reason_details":[
                    {"code":"LOW_RELEVANCE_SCORE","label":"关联度低：材料与待证事实的关联性评分低于 50%","explanation":"材料内容与物流事实仅有有限关联。"},
                    {"code":"HIGH_RISK_FLAG","label":"模型综合判断该材料为高风险","explanation":"来源链存在重大缺口，需要人工确认。"}
                  ],
                  "fact_links":[{
                    "fact_id":"FACT_DELIVERY",
                    "relation":"SUPPORTS_CLAIM",
                    "reason":"模型直接绑定到物流签收事实。",
                    "source_unit_id":"SOURCE_UNIT_01",
                    "observation_slot":"OBS_01"
                  }]
                }
                """;
    }
}
