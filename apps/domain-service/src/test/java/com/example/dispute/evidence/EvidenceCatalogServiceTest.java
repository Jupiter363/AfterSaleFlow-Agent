/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据，覆盖 「hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty」、「evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing」、「hearingCatalogKeepsPendingCounterpartyEvidenceHidden」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.EvidenceCatalogService;
import com.example.dispute.evidence.application.RoleScopedEvidenceView;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceCatalogServiceTest」。
// 类型职责：集中验证证据的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty」、「evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing」、「hearingCatalogKeepsPendingCounterpartyEvidenceHidden」、「submittedEvidence」、「evidence」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceCatalogServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;

    private EvidenceCatalogService service;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCatalogServiceTest.setUp()」。
    // 具体功能：「EvidenceCatalogServiceTest.setUp()」：在每个测试场景运行前创建测试对象和内存夹具，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceCatalogServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceCatalogServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceCatalogServiceTest.setUp()」守住「证据与版本化卷宗」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new EvidenceCatalogService(
                        caseRepository, evidenceRepository, verificationRepository);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty()」。
    // 具体功能：「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty()」：复现“核对完整业务行为（场景方法「hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc」、「service.catalog」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「HEARING」、「PARTIES」、「merchant-local」、「USER」。
    // 上游调用：「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「HEARING」、「PARTIES」、「merchant-local」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
        assertThat(catalog.items().getFirst().claimedFact())
                .isEqualTo("物流截图用于证明包裹签收状态");
        assertThat(catalog.items().getFirst().truthAttested()).isTrue();
        assertThat(catalog.items().getFirst().attestationScope())
                .containsExactly("AUTHENTICITY", "CLAIMED_FACT_RELEVANCE");
        assertThat(catalog.items().getFirst().partyCapacity()).isEqualTo("INITIATOR");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing()」。
    // 具体功能：「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing()」：复现“核对完整业务行为（场景方法「evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「service.catalog」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE」、「PARTIES」、「merchant-local」。
    // 上游调用：「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE」、「PARTIES」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden()」。
    // 具体功能：「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden()」：复现“核对完整业务行为（场景方法「hearingCatalogKeepsPendingCounterpartyEvidenceHidden」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「service.catalog」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「HEARING」、「PARTIES」、「merchant-local」。
    // 上游调用：「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「HEARING」、「PARTIES」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    @Test
    void catalogProjectsEvidenceV3AssessmentScoresRiskAndReviewReasons() {
        FulfillmentCaseEntity dispute = dispute(CaseStatus.EVIDENCE_OPEN, "EVIDENCE");
        EvidenceItemEntity userEvidence = submittedEvidence("PARTIES");
        EvidenceVerificationEntity verification = EvidenceVerificationEntity.create(
                "VERIFICATION_V2_CATALOG",
                dispute.getId(),
                userEvidence.getId(),
                1,
                EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW,
                "{}",
                """
                {"schema_version":"evidence-turn-result.v3",
                 "assessment_public_text":"材料文本可读，但缺少签收人身份和交付照片。",
                 "authenticity_score":0.76,
                 "authenticity_score_explanation":"可读取物流页面，但缺少原始平台导出来源。",
                 "relevance_score":0.91,
                 "relevance_score_explanation":"签收时间直接对应冻结矩阵中的物流事实。",
                 "completeness_score":0.42,
                 "completeness_score_explanation":"缺少签收人身份和交付照片。",
                 "assessment_confidence":0.83,
                 "assessment_confidence_explanation":"文本清晰，可判断当前材料覆盖范围。",
                 "risk_level":"MEDIUM",
                 "risk_explanation":"来源链不完整但尚无明显伪造迹象。",
                 "source_basis":["物流页面中的签收时间"],
                 "formation_time_assessment":"页面时间与物流节点部分对应",
                 "findings":[{"finding_type":"LOGISTICS_RECORD","description":"可见签收时间"}],
                 "limitations":["缺少签收人身份"],
                 "unsupported_claims":["不能仅凭该页面确认实际签收人"]}
                """,
                """
                {"requires_human_review":true,
                 "reason_details":[{"code":"LOW_COMPLETENESS_SCORE",
                 "label":"完成度低：材料完整性评分偏低",
                 "explanation":"缺少签收人身份和交付照片。"}]}
                """,
                true,
                Instant.parse("2026-08-20T02:00:00Z"),
                "evidence-clerk-agent",
                "TRACE_V2_CATALOG");
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(userEvidence));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        userEvidence.getId()))
                .thenReturn(Optional.of(verification));

        var item = service.catalog(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER))
                .items()
                .getFirst();

        assertThat(item.assessmentProtocol()).isEqualTo("evidence-turn-result.v3");
        assertThat(item.assessmentText())
                .isEqualTo("材料文本可读，但缺少签收人身份和交付照片。");
        assertThat(item.verificationFeedback()).isEqualTo(item.assessmentText());
        assertThat(item.authenticityScore()).isEqualTo(0.76);
        assertThat(item.authenticityScoreExplanation())
                .isEqualTo("可读取物流页面，但缺少原始平台导出来源。");
        assertThat(item.relevanceScore()).isEqualTo(0.91);
        assertThat(item.relevanceScoreExplanation())
                .isEqualTo("签收时间直接对应冻结矩阵中的物流事实。");
        assertThat(item.completenessScore()).isEqualTo(0.42);
        assertThat(item.completenessScoreExplanation())
                .isEqualTo("缺少签收人身份和交付照片。");
        assertThat(item.assessmentConfidence()).isEqualTo(0.83);
        assertThat(item.assessmentConfidenceExplanation())
                .isEqualTo("文本清晰，可判断当前材料覆盖范围。");
        assertThat(item.riskLevel()).isEqualTo("MEDIUM");
        assertThat(item.riskExplanation()).isEqualTo("来源链不完整但尚无明显伪造迹象。");
        assertThat(item.sourceBasis()).containsExactly("物流页面中的签收时间");
        assertThat(item.formationTimeAssessment()).isEqualTo("页面时间与物流节点部分对应");
        assertThat(item.findings())
                .containsExactly(new RoleScopedEvidenceView.AssessmentFinding(
                        "LOGISTICS_RECORD", "可见签收时间"));
        assertThat(item.limitations()).containsExactly("缺少签收人身份");
        assertThat(item.unsupportedClaims()).containsExactly("不能仅凭该页面确认实际签收人");
        assertThat(item.requiresHumanReview()).isTrue();
        assertThat(item.reasonDetails())
                .containsExactly(new RoleScopedEvidenceView.ReviewReasonDetail(
                        "LOW_COMPLETENESS_SCORE",
                        "完成度低：材料完整性评分偏低",
                        "缺少签收人身份和交付照片。"));
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCatalogServiceTest.submittedEvidence(String)」。
    // 具体功能：「EvidenceCatalogServiceTest.submittedEvidence(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「submittedEvidence」）”组装或读取「OffsetDateTime.parse」、「evidence.markSubmitted」、「evidence」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceCatalogServiceTest.submittedEvidence(String)」由本测试类中的 「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty」、「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing」 调用。
    // 下游影响：「EvidenceCatalogServiceTest.submittedEvidence(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceCatalogServiceTest.submittedEvidence(String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_BATCH_SHARED」、「2026-07-08T00:01:00Z」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceItemEntity submittedEvidence(String visibility) {
        EvidenceItemEntity evidence = evidence(visibility);
        evidence.markSubmitted(
                "EVIDENCE_BATCH_SHARED",
                OffsetDateTime.parse("2026-07-08T00:01:00Z"),
                "user-local");
        return evidence;
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCatalogServiceTest.evidence(String)」。
    // 具体功能：「EvidenceCatalogServiceTest.evidence(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「evidence」）”组装或读取「EvidenceItemEntity.uploaded」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceCatalogServiceTest.evidence(String)」由本测试类中的 「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden」、「EvidenceCatalogServiceTest.submittedEvidence」 调用。
    // 下游影响：「EvidenceCatalogServiceTest.evidence(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceCatalogServiceTest.evidence(String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_SHARED」、「CASE_CATALOG_TEST」、「DOSSIER_1」、「LOGISTICS_PROOF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceItemEntity evidence(String visibility) {
        EvidenceItemEntity evidence = EvidenceItemEntity.uploaded(
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
        evidence.recordSubmissionDeclaration(
                """
                {"claimed_fact":"物流截图用于证明包裹签收状态","truth_attested":true,
                "attestation_version":"EVIDENCE_TRUTH_ATTESTATION_V1",
                "attestation_scope":["AUTHENTICITY","CLAIMED_FACT_RELEVANCE"],
                "party_capacity":"INITIATOR",
                "forgery_consequence_code":"REJECT_INITIATOR_CLAIMS_AND_REPUTATION_PENALTY",
                "enforcement_gate":"HUMAN_CONFIRMED_FORGERY_REQUIRED"}
                """,
                "user-local");
        return evidence;
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceCatalogServiceTest.dispute(CaseStatus,String)」。
    // 具体功能：「EvidenceCatalogServiceTest.dispute(CaseStatus,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「dispute」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceCatalogServiceTest.dispute(CaseStatus,String)」由本测试类中的 「EvidenceCatalogServiceTest.hearingCatalogShowsSubmittedPartyVisibleEvidenceToTheCounterparty」、「EvidenceCatalogServiceTest.evidenceRoomCatalogKeepsCounterpartyEvidenceHiddenBeforeHearing」、「EvidenceCatalogServiceTest.hearingCatalogKeepsPendingCounterpartyEvidenceHidden」 调用。
    // 下游影响：「EvidenceCatalogServiceTest.dispute(CaseStatus,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceCatalogServiceTest.dispute(CaseStatus,String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_CATALOG_TEST」、「ORDER-CATALOG」、「LOG-CATALOG」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
