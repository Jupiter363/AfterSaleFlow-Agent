/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据卷宗冻结器，覆盖 「rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」、「frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」、「freezeToleratesLegacyEvidenceWithoutParseStatus」、「frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults」、「lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
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

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceDossierFreezerTest」。
// 类型职责：集中验证证据卷宗冻结器的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」、「frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」、「freezeToleratesLegacyEvidenceWithoutParseStatus」、「frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults」、「lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact」、「evidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceDossierFreezerTest {

    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private EvidenceDossierItemRepository dossierItemRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion()」。
    // 具体功能：「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion()」：复现“核对完整业务行为（场景方法「rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」）”场景：驱动 「dossierRepository.findByCaseIdAndDossierVersion」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「dossierRepository.save」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-03T01:00:00Z」、「EVIDENCE_ACCEPTED」、「EVIDENCE_REJECTED」、「CASE_FREEZE」。
    // 上游调用：「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」、「EVIDENCE_ACCEPTED」、「EVIDENCE_REJECTED」、「CASE_FREEZE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix()」。
    // 具体功能：「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix()」：复现“核对完整业务行为（场景方法「frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」）”场景：驱动 「dossierRepository.findByCaseIdAndDossierVersion」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「dossierRepository.save」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-03T01:00:00Z」、「EVIDENCE_USER_LOGISTICS」、「{\"ocr\":\"物流详情\"}」、「parser」。
    // 上游调用：「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」、「EVIDENCE_USER_LOGISTICS」、「{\"ocr\":\"物流详情\"}」、「parser」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus()」。
    // 具体功能：「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus()」：复现“核对完整业务行为（场景方法「freezeToleratesLegacyEvidenceWithoutParseStatus」）”场景：驱动 「dossierRepository.findByCaseIdAndDossierVersion」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「dossierRepository.save」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-03T01:00:00Z」、「EVIDENCE_LEGACY_PARSE_STATUS」、「parseStatus」、「CASE_FREEZE」。
    // 上游调用：「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」、「EVIDENCE_LEGACY_PARSE_STATUS」、「parseStatus」、「CASE_FREEZE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults()」。
    // 具体功能：「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults()」：复现“核对完整业务行为（场景方法「frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults」）”场景：驱动 「dossierRepository.findByCaseIdAndDossierVersion」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「dossierRepository.save」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-03T01:00:00Z」、「EVIDENCE_MULTIMODAL_SCORE」、「VERIFY_MULTIMODAL_SCORE」、「CASE_FREEZE」。
    // 上游调用：「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」、「EVIDENCE_MULTIMODAL_SCORE」、「VERIFY_MULTIMODAL_SCORE」、「CASE_FREEZE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierFreezerTest.lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact()」。
    // 具体功能：「EvidenceDossierFreezerTest.lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact()」：复现“核对完整业务行为（场景方法「lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact」）”场景：驱动 「dossierRepository.findByCaseIdAndDossierVersion」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「dossierRepository.save」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-03T01:00:00Z」、「EVIDENCE_UNRELATED_IMAGE」、「VERIFY_UNRELATED_IMAGE」、「CASE_FREEZE」。
    // 上游调用：「EvidenceDossierFreezerTest.lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceDossierFreezerTest.lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceDossierFreezerTest.lowRelevanceModelFactLinkDoesNotBecomeVerifiedFact()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T01:00:00Z」、「EVIDENCE_UNRELATED_IMAGE」、「VERIFY_UNRELATED_IMAGE」、「CASE_FREEZE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierFreezerTest.evidence(String)」。
    // 具体功能：「EvidenceDossierFreezerTest.evidence(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「evidence」）”组装或读取「EvidenceItemEntity.uploaded」、「OffsetDateTime.parse」、「evidence.markSubmitted」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceDossierFreezerTest.evidence(String)」由本测试类中的 「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」、「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」、「EvidenceDossierFreezerTest.freezeToleratesLegacyEvidenceWithoutParseStatus」、「EvidenceDossierFreezerTest.frozenDossierUsesPersistedMultimodalScoresInsteadOfStatusDefaults」 调用。
    // 下游影响：「EvidenceDossierFreezerTest.evidence(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceDossierFreezerTest.evidence(String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_FREEZE」、「DOSSIER_COLLECTING」、「LOGISTICS_PROOF」、「USER_UPLOAD」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceDossierFreezerTest.verification(EvidenceItemEntity,EvidenceVerificationStatus)」。
    // 具体功能：「EvidenceDossierFreezerTest.verification(EvidenceItemEntity,EvidenceVerificationStatus)」：作为测试辅助方法为“核对完整业务行为（场景方法「verification」）”组装或读取「EvidenceVerificationEntity.create」、「Instant.parse」、「item.getId」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceDossierFreezerTest.verification(EvidenceItemEntity,EvidenceVerificationStatus)」由本测试类中的 「EvidenceDossierFreezerTest.rejectedEvidenceRemainsInTheAuditStoreButIsExcludedFromTheFrozenVersion」、「EvidenceDossierFreezerTest.frozenDossierContainsEvidenceItemsPartySummaryAndFactEvidenceMatrix」 调用。
    // 下游影响：「EvidenceDossierFreezerTest.verification(EvidenceItemEntity,EvidenceVerificationStatus)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceDossierFreezerTest.verification(EvidenceItemEntity,EvidenceVerificationStatus)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「VERIFY_」、「CASE_FREEZE」、「{}」、「[]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
