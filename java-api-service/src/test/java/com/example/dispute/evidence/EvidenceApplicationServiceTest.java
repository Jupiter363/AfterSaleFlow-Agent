/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证证据应用，覆盖 「uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「duplicateAuthorizationMergesWithExistingMetadata」、「systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization」、「systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization」、「systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization」、「uploadsMarkdownEvidenceAsTextParseableMaterial」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.application.BuildDossierResult;
import com.example.dispute.evidence.application.EvidenceApplicationService;
import com.example.dispute.evidence.application.EvidenceSearchIndexer;
import com.example.dispute.evidence.application.EvidenceStorage;
import com.example.dispute.evidence.application.EvidenceView;
import com.example.dispute.evidence.application.OcrTaskClient;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「EvidenceApplicationServiceTest」。
// 类型职责：集中验证证据应用的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「duplicateAuthorizationMergesWithExistingMetadata」、「systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization」、「systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization」、「systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceApplicationServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private EvidenceStorage storage;
    @Mock private OcrTaskClient ocrTaskClient;
    @Mock private EvidenceSearchIndexer searchIndexer;
    @Mock private AuditRecorder auditRecorder;

    private EvidenceApplicationService service;

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.setUp()」。
    // 具体功能：「EvidenceApplicationServiceTest.setUp()」：在每个测试场景运行前创建「newObjectMapper().findAndRegisterModules」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceApplicationServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceApplicationServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApplicationServiceTest.setUp()」守住「证据与版本化卷宗」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new EvidenceApplicationService(
                        caseRepository,
                        evidenceRepository,
                        dossierRepository,
                        storage,
                        ocrTaskClient,
                        searchIndexer,
                        new ObjectMapper().findAndRegisterModules(),
                        auditRecorder);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails()」。
    // 具体功能：「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails()」：复现“核对完整业务行为（场景方法「uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「evidenceRepository.save」、「storage.storeOriginal」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「evidence-original」、「file」、「绛炬敹璇佹槑.png」。
    // 上游调用：「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「evidence-original」、「file」、「绛炬敹璇佹槑.png」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails() throws Exception {
        FulfillmentCaseEntity disputeCase = caseEntity();
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        when(evidenceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidence/EVIDENCE_test/proof.png"));
        doThrow(new IllegalStateException("ocr unavailable"))
                .when(ocrTaskClient)
                .createParseTask(any());
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "绛炬敹璇佹槑.png",
                        "image/png",
                        new byte[] {
                            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                        });

        EvidenceView result =
                service.upload(
                        "CASE_evidence",
                        file,
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "PARTIES",
                        true,
                        "物流截图用于证明包裹签收状态",
                        true,
                        null,
                        actor());

        assertThat(result.fileHash()).matches("[0-9a-f]{64}");
        assertThat(result.fileBucket()).isEqualTo("evidence-original");
        assertThat(result.parseStatus()).isEqualTo("PENDING");
        assertThat(result.submissionStatus()).isEqualTo("PENDING_SUBMISSION");
        assertThat(result.desensitized()).isFalse();
        assertThat(result.claimedFact()).isEqualTo("物流截图用于证明包裹签收状态");
        assertThat(result.truthAttested()).isTrue();
        assertThat(result.attestationScope())
                .containsExactly("AUTHENTICITY", "CLAIMED_FACT_RELEVANCE");
        assertThat(result.partyCapacity()).isEqualTo("INITIATOR");
        assertThat(result.attestationVersion()).isEqualTo("EVIDENCE_TRUTH_ATTESTATION_V1");
        assertThat(result.enforcementGate()).isEqualTo("HUMAN_CONFIRMED_FORGERY_REQUIRED");
        ArgumentCaptor<EvidenceItemEntity> storedEvidence =
                ArgumentCaptor.forClass(EvidenceItemEntity.class);
        verify(evidenceRepository).save(storedEvidence.capture());
        assertThat(storedEvidence.getValue().getMetadataJson())
                .contains("\"model_processing_authorized\":true")
                .contains("CURRENT_DISPUTE_EVIDENCE_REVIEW")
                .contains("\"attestation_scope\":[\"AUTHENTICITY\",\"CLAIMED_FACT_RELEVANCE\"]")
                .contains("\"forgery_consequence_code\":\"REJECT_INITIATOR_CLAIMS_AND_REPUTATION_PENALTY\"");
        verify(storage).storeOriginal(any(), any(), any(), any(), any());
        verify(searchIndexer).indexMetadata(any());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata()」。
    // 具体功能：「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata()」：复现“核对完整业务行为（场景方法「duplicateAuthorizationMergesWithExistingMetadata」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「evidenceRepository.save」、「service.upload」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_existing」、「user-evidence」、「CASE_evidence」、「LOGISTICS_PROOF」。
    // 上游调用：「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_existing」、「user-evidence」、「CASE_evidence」、「LOGISTICS_PROOF」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void duplicateAuthorizationMergesWithExistingMetadata() {
        EvidenceItemEntity existing = evidenceEntity("EVIDENCE_existing");
        existing.authorizeModelProcessing(
                "{\"ocr_language\":\"zh-CN\",\"capture_source\":\"mobile\"}",
                "user-evidence");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository
                        .findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                                any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(evidenceRepository.save(existing)).thenReturn(existing);
        MockMultipartFile file = pngFile();

        service.upload(
                "CASE_evidence",
                file,
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                "PARTIES",
                true,
                "物流截图用于证明包裹签收状态",
                true,
                null,
                actor());

        assertThat(existing.getMetadataJson())
                .contains("\"ocr_language\":\"zh-CN\"")
                .contains("\"capture_source\":\"mobile\"")
                .contains("\"model_processing_authorized\":true")
                .contains("CURRENT_DISPUTE_EVIDENCE_REVIEW")
                .contains("\"claimed_fact\":\"物流截图用于证明包裹签收状态\"")
                .contains("\"truth_attested\":true");
        verify(storage, never()).storeOriginal(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsUploadWithoutTruthAndClaimRelevanceAttestation() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity()));

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "CASE_evidence",
                                        pngFile(),
                                        "LOGISTICS_PROOF",
                                        "USER_UPLOAD",
                                        "PARTIES",
                                        "物流截图用于证明包裹签收状态",
                                        false,
                                        null,
                                        actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("truthAttested");

        verify(storage, never()).storeOriginal(any(), any(), any(), any(), any());
    }

    @Test
    void recordsRespondentCapacityAndCounterpartyConsequenceWithoutExecutingIt() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity(ActorRole.MERCHANT)));
        when(evidenceRepository
                        .findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                                any(), any(), any()))
                .thenReturn(Optional.empty());
        when(evidenceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidence/EVIDENCE_test/proof.png"));

        EvidenceView result =
                service.upload(
                        "CASE_evidence",
                        pngFile(),
                        "LOGISTICS_PROOF",
                        "USER_UPLOAD",
                        "PARTIES",
                        "物流截图用于证明包裹签收状态",
                        true,
                        null,
                        actor());

        assertThat(result.partyCapacity()).isEqualTo("RESPONDENT");
        assertThat(result.forgeryConsequenceCode())
                .isEqualTo(
                        "ACCEPT_REASONABLE_COUNTERPARTY_CLAIMS_AND_REPUTATION_PENALTY");
        assertThat(result.enforcementGate())
                .isEqualTo("HUMAN_CONFIRMED_FORGERY_REQUIRED");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization()」。
    // 具体功能：「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization()」：复现“核对完整业务行为（场景方法「systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findById」、「service.contentForModel」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_private」、「CASE_evidence」、「python-agent-service」。
    // 上游调用：「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_private」、「CASE_evidence」、「python-agent-service」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization() {
        EvidenceItemEntity item = evidenceEntity("EVIDENCE_private");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findById("EVIDENCE_private"))
                .thenReturn(Optional.of(item));
        AuthenticatedActor system =
                new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM);

        assertThatThrownBy(
                        () ->
                                service.contentForModel(
                                        "CASE_evidence", "EVIDENCE_private", system))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not authorized for model processing");

        verify(storage, never()).loadOriginal(any(), any());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization()」。
    // 具体功能：「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization()」：复现“核对完整业务行为（场景方法「systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findById」、「storage.loadOriginal」、「service.contentForModel」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_authorized」、「user-evidence」、「CASE_evidence」、「evidence-original」。
    // 上游调用：「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_authorized」、「user-evidence」、「CASE_evidence」、「evidence-original」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization() {
        EvidenceItemEntity item = evidenceEntity("EVIDENCE_authorized");
        item.authorizeModelProcessing(
                "{\"model_processing_authorized\":true,\"authorization_scope\":\"CURRENT_DISPUTE_EVIDENCE_REVIEW\"}",
                "user-evidence");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findById("EVIDENCE_authorized"))
                .thenReturn(Optional.of(item));
        when(storage.loadOriginal("evidence-original", "CASE_evidence/proof.png"))
                .thenReturn(new byte[] {1, 2, 3});
        AuthenticatedActor system =
                new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM);

        var content =
                service.contentForModel(
                        "CASE_evidence", "EVIDENCE_authorized", system);

        assertThat(content.content()).containsExactly(1, 2, 3);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization()」。
    // 具体功能：「EvidenceApplicationServiceTest.systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization()」：复现“核对完整业务行为（场景方法「systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findById」、「storage.loadOriginal」、「service.contentForModel」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「redacted.png」、「evidence-redacted」、「EVIDENCE_redacted」。
    // 上游调用：「EvidenceApplicationServiceTest.systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「redacted.png」、「evidence-redacted」、「EVIDENCE_redacted」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void systemCanReadDesensitizedEvidenceForModelWithoutRawAuthorization() {
        EvidenceItemEntity item = mock(EvidenceItemEntity.class);
        when(item.getCaseId()).thenReturn("CASE_evidence");
        when(item.getDeletedAt()).thenReturn(null);
        when(item.isDesensitized()).thenReturn(true);
        when(item.getOriginalFilename()).thenReturn("redacted.png");
        when(item.getContentType()).thenReturn("image/png");
        when(item.getFileBucket()).thenReturn("evidence-redacted");
        when(item.getFileObjectKey()).thenReturn("CASE_evidence/redacted.png");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(evidenceRepository.findById("EVIDENCE_redacted"))
                .thenReturn(Optional.of(item));
        when(storage.loadOriginal("evidence-redacted", "CASE_evidence/redacted.png"))
                .thenReturn(new byte[] {4, 5, 6});

        var content =
                service.contentForModel(
                        "CASE_evidence",
                        "EVIDENCE_redacted",
                        new AuthenticatedActor("python-agent-service", ActorRole.SYSTEM));

        assertThat(content.content()).containsExactly(4, 5, 6);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial()」。
    // 具体功能：「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial()」：复现“核对完整业务行为（场景方法「uploadsMarkdownEvidenceAsTextParseableMaterial」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「evidenceRepository.save」、「storage.storeOriginal」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「evidence-original」、「file」、「chat-record.md」。
    // 上游调用：「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「evidence-original」、「file」、「chat-record.md」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void uploadsMarkdownEvidenceAsTextParseableMaterial() throws Exception {
        FulfillmentCaseEntity disputeCase = caseEntity();
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        when(evidenceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidence/EVIDENCE_test/chat-record.md"));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "chat-record.md",
                        "text/markdown",
                        """
                        # 娌熼€氳褰?                        鐢ㄦ埛绉扮鏀跺悗鍙戠幇琛ㄧ洏鍒掔棔銆?                        """
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        EvidenceView result =
                service.upload(
                        "CASE_evidence",
                        file,
                        "CHAT_SCREENSHOT",
                        "USER_UPLOAD",
                        "PARTIES",
                        "聊天记录用于证明双方沟通内容",
                        true,
                        null,
                        actor());

        assertThat(result.originalFilename()).isEqualTo("chat-record.md");
        assertThat(result.contentType()).isEqualTo("text/markdown");
        assertThat(result.parseStatus()).isEqualTo("PENDING");
        assertThat(result.submissionStatus()).isEqualTo("PENDING_SUBMISSION");
        verify(storage).storeOriginal(any(), any(), any(), any(), any());
        verify(ocrTaskClient).createParseTask(any());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence()」。
    // 具体功能：「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence()」：复现“核对完整业务行为（场景方法「reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc」、「dossierRepository.findByCaseId」、「evidenceRepository.save」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_voided」、「CASE_evidence」、「DOSSIER_existing」、「DOCUMENT」。
    // 上游调用：「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「EVIDENCE_voided」、「CASE_evidence」、「DOSSIER_existing」、「DOCUMENT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence() throws Exception {
        FulfillmentCaseEntity disputeCase = caseEntity();
        EvidenceItemEntity voided =
                EvidenceItemEntity.uploaded(
                        "EVIDENCE_voided",
                        "CASE_evidence",
                        "DOSSIER_existing",
                        "DOCUMENT",
                        "USER_UPLOAD",
                        ActorRole.USER.name(),
                        "user-evidence",
                        "evidence-original",
                        "CASE_evidence/EVIDENCE_voided/proof.md",
                        "same-hash",
                        "proof.md",
                        "text/markdown",
                        8,
                        "PRIVATE",
                        null);
        voided.deletePending(java.time.OffsetDateTime.now(), "user-evidence");
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        when(dossierRepository.findByCaseId("CASE_evidence"))
                .thenReturn(Optional.of(EvidenceDossierEntity.collecting(
                        "DOSSIER_existing", "CASE_evidence", "user-evidence")));
        when(evidenceRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.storeOriginal(any(), any(), any(), any(), any()))
                .thenReturn(
                        new EvidenceStorage.StoredObject(
                                "evidence-original",
                                "CASE_evidence/EVIDENCE_fresh/proof.md"));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "proof.md",
                        "text/markdown",
                        "evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        EvidenceView result =
                service.upload(
                        "CASE_evidence",
                        file,
                        "DOCUMENT",
                        "USER_UPLOAD",
                        "PRIVATE",
                        "该文件用于证明本案相关争议事实",
                        true,
                        null,
                        actor());

        assertThat(result.id()).isNotEqualTo("EVIDENCE_voided");
        assertThat(result.submissionStatus()).isEqualTo("PENDING_SUBMISSION");
        verify(storage).storeOriginal(any(), any(), any(), any(), any());
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.rejectsExecutableContentBeforeCallingStorage()」。
    // 具体功能：「EvidenceApplicationServiceTest.rejectsExecutableContentBeforeCallingStorage()」：复现“拒绝非法输入或越权操作（场景方法「rejectsExecutableContentBeforeCallingStorage」）”场景：驱动 「caseRepository.findById」、「service.upload」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「file」、「payload.exe」、「OTHER」。
    // 上游调用：「EvidenceApplicationServiceTest.rejectsExecutableContentBeforeCallingStorage()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.rejectsExecutableContentBeforeCallingStorage()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.rejectsExecutableContentBeforeCallingStorage()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「file」、「payload.exe」、「OTHER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsExecutableContentBeforeCallingStorage() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity()));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "payload.exe",
                        "application/octet-stream",
                        new byte[] {77, 90});

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "CASE_evidence",
                                        file,
                                        "OTHER",
                                "USER_UPLOAD",
                                "PRIVATE",
                                "该文件用于证明本案相关争议事实",
                                true,
                                null,
                                        actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content type");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.rejectsExecutableBytesEvenWhenContentTypeClaimsPng()」。
    // 具体功能：「EvidenceApplicationServiceTest.rejectsExecutableBytesEvenWhenContentTypeClaimsPng()」：复现“拒绝非法输入或越权操作（场景方法「rejectsExecutableBytesEvenWhenContentTypeClaimsPng」）”场景：驱动 「caseRepository.findById」、「service.upload」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「file」、「fake.png」、「OTHER」。
    // 上游调用：「EvidenceApplicationServiceTest.rejectsExecutableBytesEvenWhenContentTypeClaimsPng()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.rejectsExecutableBytesEvenWhenContentTypeClaimsPng()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.rejectsExecutableBytesEvenWhenContentTypeClaimsPng()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「file」、「fake.png」、「OTHER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsExecutableBytesEvenWhenContentTypeClaimsPng() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity()));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "fake.png",
                        "image/png",
                        new byte[] {'M', 'Z', 0, 0});

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "CASE_evidence",
                                        file,
                                        "OTHER",
                                "USER_UPLOAD",
                                "PRIVATE",
                                "该文件用于证明本案相关争议事实",
                                true,
                                null,
                                        actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.userCannotClaimThatUploadedEvidenceCameFromThePlatform()」。
    // 具体功能：「EvidenceApplicationServiceTest.userCannotClaimThatUploadedEvidenceCameFromThePlatform()」：复现“核对完整业务行为（场景方法「userCannotClaimThatUploadedEvidenceCameFromThePlatform」）”场景：驱动 「caseRepository.findById」、「service.upload」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「file」、「proof.png」、「OTHER」。
    // 上游调用：「EvidenceApplicationServiceTest.userCannotClaimThatUploadedEvidenceCameFromThePlatform()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.userCannotClaimThatUploadedEvidenceCameFromThePlatform()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.userCannotClaimThatUploadedEvidenceCameFromThePlatform()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「file」、「proof.png」、「OTHER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void userCannotClaimThatUploadedEvidenceCameFromThePlatform() {
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(caseEntity()));
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "proof.png",
                        "image/png",
                        new byte[] {
                            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                        });

        assertThatThrownBy(
                        () ->
                                service.upload(
                                        "CASE_evidence",
                                        file,
                                        "OTHER",
                                "PLATFORM_UPLOAD",
                                "PARTIES",
                                "该文件用于证明本案相关争议事实",
                                true,
                                null,
                                        actor()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("source");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.buildsVersionedTimelineWithoutResponsibilityOrDecisionFields()」。
    // 具体功能：「EvidenceApplicationServiceTest.buildsVersionedTimelineWithoutResponsibilityOrDecisionFields()」：复现“核对完整业务行为（场景方法「buildsVersionedTimelineWithoutResponsibilityOrDecisionFields」）”场景：驱动 「caseRepository.findById」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「dossierRepository.findByCaseId」、「dossierRepository.save」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「decision」、「responsibility」。
    // 上游调用：「EvidenceApplicationServiceTest.buildsVersionedTimelineWithoutResponsibilityOrDecisionFields()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.buildsVersionedTimelineWithoutResponsibilityOrDecisionFields()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.buildsVersionedTimelineWithoutResponsibilityOrDecisionFields()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「decision」、「responsibility」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void buildsVersionedTimelineWithoutResponsibilityOrDecisionFields() {
        FulfillmentCaseEntity disputeCase = caseEntity();
        when(caseRepository.findById("CASE_evidence"))
                .thenReturn(Optional.of(disputeCase));
        when(evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        "CASE_evidence"))
                .thenReturn(List.of());
        when(dossierRepository.findByCaseId("CASE_evidence"))
                .thenReturn(Optional.empty());
        when(dossierRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BuildDossierResult result =
                service.buildDossier("CASE_evidence", actor());

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.evidences()).isEmpty();
        assertThat(result.timeline()).isEmpty();
        assertThat(result.summary()).doesNotContainKeys("decision", "responsibility");
        assertThat(disputeCase.getCaseStatus()).isEqualTo(CaseStatus.DOSSIER_BUILT);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.getsFrozenDossierWithObjectShapedEvidenceMatrix()」。
    // 具体功能：「EvidenceApplicationServiceTest.getsFrozenDossierWithObjectShapedEvidenceMatrix()」：复现“核对完整业务行为（场景方法「getsFrozenDossierWithObjectShapedEvidenceMatrix」）”场景：驱动 「caseRepository.findById」、「dossierRepository.findByCaseId」、「evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc」、「service.getDossier」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_evidence」、「fact」、「物流显示已签收」。
    // 上游调用：「EvidenceApplicationServiceTest.getsFrozenDossierWithObjectShapedEvidenceMatrix()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceApplicationServiceTest.getsFrozenDossierWithObjectShapedEvidenceMatrix()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceApplicationServiceTest.getsFrozenDossierWithObjectShapedEvidenceMatrix()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「fact」、「物流显示已签收」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void getsFrozenDossierWithObjectShapedEvidenceMatrix() {
        when(caseRepository.findById("CASE_evidence")).thenReturn(Optional.of(caseEntity()));
        when(dossierRepository.findByCaseId("CASE_evidence"))
                .thenReturn(Optional.of(frozenDossier()));
        when(evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_evidence"))
                .thenReturn(List.of());

        BuildDossierResult result = service.getDossier("CASE_evidence", actor());

        assertThat(result.matrix()).hasSize(1);
        assertThat(result.matrix().get(0).get("fact")).isEqualTo("物流显示已签收");
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.actor()」。
    // 具体功能：「EvidenceApplicationServiceTest.actor()」：作为测试辅助方法为“核对完整业务行为（场景方法「actor」）”组装或读取「AuthenticatedActor」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApplicationServiceTest.actor()」由本测试类中的 「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata」、「EvidenceApplicationServiceTest.uploadsMarkdownEvidenceAsTextParseableMaterial」、「EvidenceApplicationServiceTest.reuploadingVoidedPendingEvidenceCreatesFreshPendingEvidence」 调用。
    // 下游影响：「EvidenceApplicationServiceTest.actor()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApplicationServiceTest.actor()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「user-evidence」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AuthenticatedActor actor() {
        return new AuthenticatedActor("user-evidence", ActorRole.USER);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.caseEntity()」。
    // 具体功能：「EvidenceApplicationServiceTest.caseEntity()」：作为测试辅助方法为“核对完整业务行为（场景方法「caseEntity」）”组装或读取「FulfillmentCaseEntity.create」、「entity.completeIntake」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApplicationServiceTest.caseEntity()」由本测试类中的 「EvidenceApplicationServiceTest.uploadsOriginalWithHashAndMetadataEvenWhenOcrTriggerFails」、「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata」、「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization」、「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization」 调用。
    // 下游影响：「EvidenceApplicationServiceTest.caseEntity()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApplicationServiceTest.caseEntity()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「order-evidence」、「user-evidence」、「merchant-evidence」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity caseEntity() {
        return caseEntity(ActorRole.USER);
    }

    private static FulfillmentCaseEntity caseEntity(ActorRole initiatorRole) {
        FulfillmentCaseEntity entity =
                FulfillmentCaseEntity.create(
                        "CASE_evidence",
                        "order-evidence",
                        null,
                        null,
                        "user-evidence",
                        "merchant-evidence",
                        initiatorRole,
                        "idem-evidence",
                        "DISPUTE",
                        "LOGISTICS_DISPUTE",
                        "signed status is disputed",
                        RiskLevel.HIGH,
                        "user-evidence");
        entity.completeIntake(
                "NON_RECEIPT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                """
                {"potentialDispute":true,"missingSlots":[],"agentDegraded":false,\
"analyzedAt":"2026-06-28T00:00:00Z"}
                """,
                "user-evidence");
        return entity;
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.pngFile()」。
    // 具体功能：「EvidenceApplicationServiceTest.pngFile()」：作为测试辅助方法为“核对完整业务行为（场景方法「pngFile」）”组装或读取「MockMultipartFile」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApplicationServiceTest.pngFile()」由本测试类中的 「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata」 调用。
    // 下游影响：「EvidenceApplicationServiceTest.pngFile()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApplicationServiceTest.pngFile()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「file」、「proof.png」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static MockMultipartFile pngFile() {
        return new MockMultipartFile(
                "file",
                "proof.png",
                "image/png",
                new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                });
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.evidenceEntity(String)」。
    // 具体功能：「EvidenceApplicationServiceTest.evidenceEntity(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「evidenceEntity」）”组装或读取「EvidenceItemEntity.uploaded」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApplicationServiceTest.evidenceEntity(String)」由本测试类中的 「EvidenceApplicationServiceTest.duplicateAuthorizationMergesWithExistingMetadata」、「EvidenceApplicationServiceTest.systemCannotReadRawEvidenceForModelWithoutPerEvidenceAuthorization」、「EvidenceApplicationServiceTest.systemCanReadRawEvidenceForModelAfterPerEvidenceAuthorization」 调用。
    // 下游影响：「EvidenceApplicationServiceTest.evidenceEntity(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApplicationServiceTest.evidenceEntity(String)」守住「证据与版本化卷宗」的可执行规格，尤其防止 「CASE_evidence」、「DOSSIER_existing」、「LOGISTICS_PROOF」、「USER_UPLOAD」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceItemEntity evidenceEntity(String id) {
        return EvidenceItemEntity.uploaded(
                id,
                "CASE_evidence",
                "DOSSIER_existing",
                "LOGISTICS_PROOF",
                "USER_UPLOAD",
                ActorRole.USER.name(),
                "user-evidence",
                "evidence-original",
                "CASE_evidence/proof.png",
                "hash",
                "proof.png",
                "image/png",
                8,
                "PARTIES",
                null);
    }

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「EvidenceApplicationServiceTest.frozenDossier()」。
    // 具体功能：「EvidenceApplicationServiceTest.frozenDossier()」：作为测试辅助方法为“核对完整业务行为（场景方法「frozenDossier」）”组装或读取「EvidenceDossierEntity.frozen」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceApplicationServiceTest.frozenDossier()」由本测试类中的 「EvidenceApplicationServiceTest.getsFrozenDossierWithObjectShapedEvidenceMatrix」 调用。
    // 下游影响：「EvidenceApplicationServiceTest.frozenDossier()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceApplicationServiceTest.frozenDossier()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「DOSSIER_FROZEN」、「CASE_evidence」、「system」、「{\"evidence_count\":1}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
}
