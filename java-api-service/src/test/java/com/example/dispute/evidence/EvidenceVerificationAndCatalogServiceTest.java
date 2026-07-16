/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据核验并且，覆盖 「partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals」、「catalogIncludesLatestVerificationConfidenceFeedback」、「catalogProjectsMultimodalScoresAndHumanReviewCardFields」、「deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
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

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceVerificationAndCatalogServiceTest」。
// 类型职责：集中验证证据核验并且的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals」、「catalogIncludesLatestVerificationConfidenceFeedback」、「catalogProjectsMultimodalScoresAndHumanReviewCardFields」、「deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」、「evidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceVerificationAndCatalogServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;

    private EvidenceVerificationService verificationService;
    private EvidenceCatalogService catalogService;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceVerificationAndCatalogServiceTest.setUp()」。
    // 具体功能：「EvidenceVerificationAndCatalogServiceTest.setUp()」：在每个测试场景运行前创建「Clock.fixed」、「Instant.parse」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceVerificationAndCatalogServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceVerificationAndCatalogServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceVerificationAndCatalogServiceTest.setUp()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「2026-07-03T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
                        intakeDossierRepository,
                        objectMapper,
                        clock);
        lenient()
                .when(intakeDossierRepository.findByCaseIdAndRoomType(any(), any()))
                .thenAnswer(
                        invocation ->
                                Optional.of(
                                        CaseIntakeDossierEntity.create(
                                                "INTAKE_DOSSIER_VERIFY",
                                                invocation.getArgument(0),
                                                RoomType.INTAKE,
                                                """
                                                {
                                                  "schema_version":"intake_case_detail.v1",
                                                  "unilateral_case_matrix":{
                                                    "schema_version":"unilateral_case_matrix.v1",
                                                    "fact_rows":[{"fact_id":"FACT_DELIVERY"}]
                                                  }
                                                }
                                                """,
                                                90,
                                                true,
                                                "ACCEPTED",
                                                1,
                                                "dispute-intake-officer")));
        catalogService =
                new EvidenceCatalogService(
                        caseRepository,
                        evidenceRepository,
                        verificationRepository);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceVerificationAndCatalogServiceTest.partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals()」。
    // 具体功能：「EvidenceVerificationAndCatalogServiceTest.partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals()」：复现“核对完整业务行为（场景方法「partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「catalogService.catalog」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_MERCHANT」、「MERCHANT」、「merchant-local」、「PRIVATE」。
    // 上游调用：「EvidenceVerificationAndCatalogServiceTest.partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceVerificationAndCatalogServiceTest.partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceVerificationAndCatalogServiceTest.partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_MERCHANT」、「MERCHANT」、「merchant-local」、「PRIVATE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity merchantPrivate =
                evidence(
                        "EVIDENCE_MERCHANT",
                        "MERCHANT",
                        "merchant-local",
                        "PRIVATE");
        merchantPrivate.markSubmitted(
                "BATCH_MERCHANT",
                OffsetDateTime.parse("2026-07-03T00:30:00Z"),
                "merchant-local");
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceVerificationAndCatalogServiceTest.catalogIncludesLatestVerificationConfidenceFeedback()」。
    // 具体功能：「EvidenceVerificationAndCatalogServiceTest.catalogIncludesLatestVerificationConfidenceFeedback()」：复现“核对完整业务行为（场景方法「catalogIncludesLatestVerificationConfidenceFeedback」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「catalogService.catalog」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_CONFIDENCE」、「USER」、「user-local」、「PARTIES」。
    // 上游调用：「EvidenceVerificationAndCatalogServiceTest.catalogIncludesLatestVerificationConfidenceFeedback()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceVerificationAndCatalogServiceTest.catalogIncludesLatestVerificationConfidenceFeedback()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceVerificationAndCatalogServiceTest.catalogIncludesLatestVerificationConfidenceFeedback()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_CONFIDENCE」、「USER」、「user-local」、「PARTIES」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void catalogIncludesLatestVerificationConfidenceFeedback() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity sharedEvidence =
                evidence("EVIDENCE_CONFIDENCE", "USER", "user-local", "PARTIES");
        EvidenceVerificationEntity latestVerification =
                EvidenceVerificationEntity.create(
                        "VERIFY_CONFIDENCE",
                        dispute.getId(),
                        sharedEvidence.getId(),
                        3,
                        EvidenceVerificationStatus.PLAUSIBLE,
                        "{}",
                        "{\"confidence_score\":0.82,\"confidence_level\":\"HIGH\",\"verification_feedback\":\"原始图片时间线与物流节点基本一致。\"}",
                        "{\"summary\":\"OCR 与物流签收时间可互相印证\"}",
                        false,
                        Instant.parse("2026-07-03T01:00:00Z"),
                        "evidence-clerk",
                        "TRACE_CONFIDENCE");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(sharedEvidence));
        when(verificationRepository
                        .findTopByEvidenceIdOrderByVerificationVersionDesc(
                                sharedEvidence.getId()))
                .thenReturn(Optional.of(latestVerification));

        var catalog =
                catalogService.catalog(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(catalog.items()).singleElement().satisfies(
                item -> {
                    assertThat(item.evidenceId()).isEqualTo("EVIDENCE_CONFIDENCE");
                    assertThat(item.verificationStatus()).isEqualTo(EvidenceVerificationStatus.PLAUSIBLE);
                    assertThat(item.confidenceScore()).isEqualTo(0.82);
                    assertThat(item.confidenceLevel()).isEqualTo("HIGH");
                    assertThat(item.verificationFeedback())
                            .isEqualTo("原始图片时间线与物流节点基本一致。");
                });
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceVerificationAndCatalogServiceTest.catalogProjectsMultimodalScoresAndHumanReviewCardFields()」。
    // 具体功能：「EvidenceVerificationAndCatalogServiceTest.catalogProjectsMultimodalScoresAndHumanReviewCardFields()」：复现“核对完整业务行为（场景方法「catalogProjectsMultimodalScoresAndHumanReviewCardFields」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「catalogService.catalog」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_MULTIMODAL」、「USER」、「user-local」、「PARTIES」。
    // 上游调用：「EvidenceVerificationAndCatalogServiceTest.catalogProjectsMultimodalScoresAndHumanReviewCardFields()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceVerificationAndCatalogServiceTest.catalogProjectsMultimodalScoresAndHumanReviewCardFields()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceVerificationAndCatalogServiceTest.catalogProjectsMultimodalScoresAndHumanReviewCardFields()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_MULTIMODAL」、「USER」、「user-local」、「PARTIES」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void catalogProjectsMultimodalScoresAndHumanReviewCardFields() {
        FulfillmentCaseEntity dispute = evidenceCase();
        EvidenceItemEntity evidence =
                evidence("EVIDENCE_MULTIMODAL", "USER", "user-local", "PARTIES");
        EvidenceVerificationEntity verification =
                EvidenceVerificationEntity.create(
                        "VERIFY_MULTIMODAL",
                        dispute.getId(),
                        evidence.getId(),
                        2,
                        EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW,
                        "{}",
                        """
                        {
                          "confidence_score":0.82,
                          "authenticity_score":0.71,
                          "relevance_score":0.93,
                          "completeness_score":0.66,
                          "assessment_confidence":0.82,
                          "inspected_modalities":["IMAGE","OCR_TEXT"],
                          "limitations":["Cannot establish capture time from pixels alone"],
                          "human_review":{
                            "required":true,
                            "reason_codes":["SOURCE_PROVENANCE"],
                            "instructions":["Inspect original export"]
                          }
                        }
                        """,
                        "{}",
                        true,
                        Instant.parse("2026-07-03T01:30:00Z"),
                        "evidence-clerk",
                        "TRACE_MULTIMODAL");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(evidence));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        evidence.getId()))
                .thenReturn(Optional.of(verification));

        var item =
                catalogService
                        .catalog(
                                dispute.getId(),
                                new AuthenticatedActor("user-local", ActorRole.USER))
                        .items()
                        .getFirst();

        assertThat(item.authenticityScore()).isEqualTo(0.71);
        assertThat(item.relevanceScore()).isEqualTo(0.93);
        assertThat(item.completenessScore()).isEqualTo(0.66);
        assertThat(item.assessmentConfidence()).isEqualTo(0.82);
        assertThat(item.inspectedModalities()).containsExactly("IMAGE", "OCR_TEXT");
        assertThat(item.limitations())
                .containsExactly("Cannot establish capture time from pixels alone");
        assertThat(item.requiresHumanReview()).isTrue();
        assertThat(item.humanReviewReasonCodes()).containsExactly("SOURCE_PROVENANCE");
        assertThat(item.humanReviewInstructions()).containsExactly("Inspect original export");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable()」。
    // 具体功能：「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable()」：复现“核对完整业务行为（场景方法「deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」）”场景：驱动 「caseRepository.findByIdForUpdate」、「evidenceRepository.findById」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「verificationRepository.save」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_USER」、「USER」、「user-local」、「PARTIES」。
    // 上游调用：「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_USER」、「USER」、「user-local」、「PARTIES」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceVerificationAndCatalogServiceTest.evidence(String,String,String,String)」。
    // 具体功能：「EvidenceVerificationAndCatalogServiceTest.evidence(String,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「evidence」）”组装或读取「EvidenceItemEntity.uploaded」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceVerificationAndCatalogServiceTest.evidence(String,String,String,String)」由本测试类中的 「EvidenceVerificationAndCatalogServiceTest.partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals」、「EvidenceVerificationAndCatalogServiceTest.catalogIncludesLatestVerificationConfidenceFeedback」、「EvidenceVerificationAndCatalogServiceTest.catalogProjectsMultimodalScoresAndHumanReviewCardFields」、「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」 调用。
    // 下游影响：「EvidenceVerificationAndCatalogServiceTest.evidence(String,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceVerificationAndCatalogServiceTest.evidence(String,String,String,String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_EVIDENCE_ROOM」、「DOSSIER_1」、「PHOTO」、「_UPLOAD」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceVerificationAndCatalogServiceTest.evidenceCase()」。
    // 具体功能：「EvidenceVerificationAndCatalogServiceTest.evidenceCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「evidenceCase」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceVerificationAndCatalogServiceTest.evidenceCase()」由本测试类中的 「EvidenceVerificationAndCatalogServiceTest.partyCatalogIsLimitedToOwnEvidenceButReviewerCanSeeAllOriginals」、「EvidenceVerificationAndCatalogServiceTest.catalogIncludesLatestVerificationConfidenceFeedback」、「EvidenceVerificationAndCatalogServiceTest.catalogProjectsMultimodalScoresAndHumanReviewCardFields」、「EvidenceVerificationAndCatalogServiceTest.deterministicProvenanceCanVerifyButInvalidMimeIsRejectedAndRemainsAuditable」 调用。
    // 下游影响：「EvidenceVerificationAndCatalogServiceTest.evidenceCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceVerificationAndCatalogServiceTest.evidenceCase()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_EVIDENCE_ROOM」、「ORDER-EVIDENCE」、「LOG-EVIDENCE」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
