/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证证据Agent轮次，覆盖 「completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「attachmentAssessmentCoverageMismatchFailsClosed」、「legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」、「partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」、「ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.EvidenceAgentTurnClient;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceAgentTurnResult;
import com.example.dispute.room.application.EvidenceAgentTurnService;
import com.example.dispute.room.application.EvidenceContextEnvelopeFactory;
import com.example.dispute.room.application.EvidenceContextEnvelopeV1;
import com.example.dispute.room.application.IntakeRecentTurn;
import com.example.dispute.room.application.RoomMessageCommand;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「EvidenceAgentTurnServiceTest」。
// 类型职责：集中验证证据Agent轮次的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「assessment」、「attachmentAssessmentCoverageMismatchFailsClosed」、「invalidAssessmentCoverage」、「legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class EvidenceAgentTurnServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private RoomTurnMemoryRepository memoryRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private EvidenceItemRepository evidenceItemRepository;
    @Mock private EvidenceVerificationRepository verificationRepository;
    @Mock private EvidenceDossierFreezer dossierFreezer;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService eventService;
    @Mock private AccessSessionResolver accessSessionResolver;
    @Mock private AgentSessionResolver agentSessionResolver;
    @Mock private SessionPermissionService permissionService;
    @Mock private EvidenceAgentTurnClient client;

    private ObjectMapper objectMapper;
    private EvidenceAgentTurnService service;

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.setUp()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.setUp()」：在每个测试场景运行前创建「accessSessionResolver.resolve」、「agentSessionResolver.resolve」、「invocation.getArgument」、「lenient」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「EvidenceAgentTurnServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.setUp()」守住「房间协作与权限」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        EvidenceContextEnvelopeFactory contextEnvelopeFactory =
                new EvidenceContextEnvelopeFactory(
                        intakeDossierRepository,
                        evidenceItemRepository,
                        memoryRepository,
                        objectMapper,
                        CLOCK);
        service =
                new EvidenceAgentTurnService(
                        caseRepository,
                        roomRepository,
                        memoryRepository,
                        evidenceItemRepository,
                        verificationRepository,
                        dossierFreezer,
                        messageRepository,
                        eventService,
                        accessSessionResolver,
                        agentSessionResolver,
                        permissionService,
                        contextEnvelopeFactory,
                        client,
                        objectMapper,
                        CLOCK);
        lenient()
                .when(accessSessionResolver.resolve(any(), any()))
                .thenAnswer(
                        invocation ->
                                accessSession(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1)));
        lenient()
                .when(agentSessionResolver.resolve(any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation ->
                                agentSession(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2),
                                        invocation.getArgument(3),
                                        invocation.getArgument(4)));
        lenient()
                .when(intakeDossierRepository.findByCaseIdAndRoomType(any(), eq(RoomType.INTAKE)))
                .thenAnswer(
                        invocation ->
                                Optional.of(
                                        intakeDossierWithFormalFacts(
                                                invocation.getArgument(0))));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus()」：复现“核对完整业务行为（场景方法「completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_ATTACHED」、「USER」、「user-local」、「PARTIES」。
    // 上游调用：「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus()」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_ATTACHED」、「USER」、「user-local」、「PARTIES」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        EvidenceItemEntity attached =
                evidenceItem("EVIDENCE_ATTACHED", "USER", "user-local", "PARTIES");
        EvidenceItemEntity historical =
                evidenceItem("EVIDENCE_HISTORICAL", "USER", "user-local", "PARTIES");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(attached, historical));
        when(client.run(any(), eq("TRACE_MULTIMODAL"), eq("REQ_MULTIMODAL")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "The visible scratch needs human inspection.",
                                objectMapper.readTree("{}"),
                                objectMapper.readTree("[]"),
                                List.of(attached.getId()),
                                List.of(),
                                List.of(),
                                List.of(assessment(attached.getId(), true)),
                                false,
                                false,
                                "NONE",
                                0.81));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        attached.getId()))
                .thenReturn(Optional.empty());
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        "Please inspect the attached product photo.",
                        List.of(attached.getId())),
                "MESSAGE_MULTIMODAL",
                CLOCK.instant(),
                "TRACE_MULTIMODAL",
                "REQ_MULTIMODAL");

        ArgumentCaptor<EvidenceVerificationEntity> verification =
                ArgumentCaptor.forClass(EvidenceVerificationEntity.class);
        verify(verificationRepository).save(verification.capture());
        EvidenceVerificationEntity persisted = verification.getValue();
        assertThat(persisted.getEvidenceId()).isEqualTo(attached.getId());
        assertThat(persisted.getVerificationStatus())
                .isEqualTo(EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW);
        assertThat(persisted.isRequiresHumanReview()).isTrue();
        JsonNode findings = objectMapper.readTree(persisted.getAgentFindingsJson());
        assertThat(findings.path("analysis_method").asText()).isEqualTo("HYBRID");
        assertThat(findings.path("fact_links").get(0).path("fact_id").asText())
                .isEqualTo("FACT_GOODS_CONDITION");
        assertThat(findings.path("authenticity_score").asDouble()).isEqualTo(0.73);
        assertThat(findings.path("relevance_score").asDouble()).isEqualTo(0.91);
        assertThat(findings.path("completeness_score").asDouble()).isEqualTo(0.68);
        assertThat(findings.path("assessment_confidence").asDouble()).isEqualTo(0.84);
        assertThat(findings.path("human_review").path("required").asBoolean()).isTrue();
        assertThat(findings.path("asset_audit").path("visual_input_status").asText())
                .isEqualTo("LOADED");
        verify(verificationRepository, never())
                .findTopByEvidenceIdOrderByVerificationVersionDesc(historical.getId());
    }

    @Test
    void hearingSupplementBatchUsesHearingContractAndFreezesOneMergedDossier()
            throws Exception {
        FulfillmentCaseEntity dispute = hearingCase();
        CaseRoomEntity room = hearingRoom(dispute);
        EvidenceItemEntity first =
                evidenceItem("EVIDENCE_HEARING_BATCH_1", "USER", "user-local", "PARTIES");
        EvidenceItemEntity second =
                evidenceItem("EVIDENCE_HEARING_BATCH_2", "USER", "user-local", "PARTIES");
        List<String> batchIds = List.of(first.getId(), second.getId());

        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(first, second));
        when(client.run(any(), eq("TRACE_HEARING_BATCH"), eq("REQ_HEARING_BATCH")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "Both supplemental items were reviewed as one batch.",
                                objectMapper.readTree("{}"),
                                objectMapper.readTree("[]"),
                                batchIds,
                                List.of(),
                                List.of(),
                                List.of(
                                        assessment(first.getId(), false),
                                        assessment(second.getId(), false)),
                                false,
                                false,
                                "NONE",
                                0.86));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(any()))
                .thenReturn(Optional.empty());
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(3L);
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(dossierFreezer.targetVersion(dispute.getId())).thenReturn(2);
        when(dossierFreezer.freeze(dispute.getId(), 2, "evidence-clerk"))
                .thenReturn(
                        EvidenceDossierEntity.frozen(
                                "DOSSIER_HEARING_BATCH_V2",
                                dispute.getId(),
                                2,
                                "evidence-clerk",
                                "{}",
                                "[]",
                                "{\"fact_evidence_matrix\":[]}"));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.HEARING,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        "Two supplemental evidence items.",
                        batchIds),
                "MESSAGE_HEARING_BATCH",
                CLOCK.instant(),
                "TRACE_HEARING_BATCH",
                "REQ_HEARING_BATCH");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client, times(1))
                .run(
                        command.capture(),
                        eq("TRACE_HEARING_BATCH"),
                        eq("REQ_HEARING_BATCH"));
        assertThat(command.getValue().agentContext().roomType()).isEqualTo(RoomType.HEARING);
        assertThat(command.getValue().contextEnvelope().roomPolicy().roomType())
                .isEqualTo(RoomType.HEARING);
        assertThat(command.getValue().contextEnvelope().currentEvent().attachmentRefs())
                .containsExactlyElementsOf(batchIds);

        ArgumentCaptor<EvidenceVerificationEntity> verifications =
                ArgumentCaptor.forClass(EvidenceVerificationEntity.class);
        verify(verificationRepository, times(2)).save(verifications.capture());
        assertThat(verifications.getAllValues())
                .extracting(EvidenceVerificationEntity::getEvidenceId)
                .containsExactlyInAnyOrderElementsOf(batchIds);
        InOrder mergeOrder = inOrder(verificationRepository, dossierFreezer);
        mergeOrder.verify(verificationRepository, times(2)).save(any());
        mergeOrder.verify(dossierFreezer, times(1))
                .freeze(dispute.getId(), 2, "evidence-clerk");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.assessment(String,boolean)」。
    // 具体功能：「EvidenceAgentTurnServiceTest.assessment(String,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「assessment」）”组装或读取「EvidenceAssessment」、「HumanReview」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.assessment(String,boolean)」由本测试类中的 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.invalidAssessmentCoverage」、「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」、「EvidenceAgentTurnServiceTest.partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank」 调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.assessment(String,boolean)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.assessment(String,boolean)」守住「房间协作与权限」的可执行规格，尤其防止 「HYBRID」、「IMAGE」、「OCR_TEXT」、「fact_id」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceAgentTurnResult.EvidenceAssessment assessment(
            String evidenceId, boolean humanReview) {
        return new EvidenceAgentTurnResult.EvidenceAssessment(
                evidenceId,
                "HYBRID",
                List.of("IMAGE", "OCR_TEXT"),
                List.of(Map.of("fact_id", "FACT_GOODS_CONDITION", "relation", "SUPPORTS")),
                0.73,
                0.91,
                0.68,
                0.84,
                List.of("用户提交的原始文件与入库元数据。"),
                List.of("FACT_GOODS_CONDITION"),
                List.of(),
                "形成时间尚待平台元数据进一步核验。",
                List.of(Map.of("type", "SURFACE_MARK", "description", "Possible scratch")),
                List.of("The image cannot establish when the mark formed."),
                List.of(Map.of("code", "DAMAGE_CAUSALITY_UNCERTAIN", "severity", "HIGH")),
                humanReview ? "SUSPICIOUS" : "PLAUSIBLE",
                new EvidenceAgentTurnResult.HumanReview(
                        humanReview,
                        humanReview ? List.of("VISUAL_DAMAGE_CAUSALITY") : List.of(),
                        humanReview ? List.of("Inspect the original image at full resolution.") : List.of()),
                Map.of(
                        "visual_input_status", "LOADED",
                        "privacy_basis", "EXPLICIT_PARTY_AUTHORIZATION"),
                "The image shows a possible surface mark.");
    }

    private static CaseIntakeDossierEntity intakeDossierWithFormalFacts(String caseId) {
        return CaseIntakeDossierEntity.create(
                "INTAKE_DOSSIER_FORMAL_FACTS",
                caseId,
                RoomType.INTAKE,
                """
                {
                  "schema_version":"intake_case_detail.v1",
                  "unilateral_case_matrix":{
                    "schema_version":"unilateral_case_matrix.v1",
                    "fact_rows":[
                      {"fact_id":"FACT_GOODS_CONDITION"},
                      {"fact_id":"FACT_DELIVERY"}
                    ]
                  }
                }
                """,
                90,
                true,
                "ACCEPTED",
                1,
                "dispute-intake-officer");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed(String,List)」。
    // 具体功能：「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed(String,List)」：复现“核对完整业务行为（场景方法「attachmentAssessmentCoverageMismatchFailsClosed」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc」，再用 「assertThatThrownBy」、「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_COVERAGE_1」、「USER」、「user-local」、「PARTIES」。
    // 上游调用：「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed(String,List)」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed(String,List)」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed(String,List)」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_COVERAGE_1」、「USER」、「user-local」、「PARTIES」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAssessmentCoverage")
    void attachmentAssessmentCoverageMismatchFailsClosed(
            String scenario,
            List<EvidenceAgentTurnResult.EvidenceAssessment> assessments) throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        EvidenceItemEntity first =
                evidenceItem("EVIDENCE_COVERAGE_1", "USER", "user-local", "PARTIES");
        EvidenceItemEntity second =
                evidenceItem("EVIDENCE_COVERAGE_2", "USER", "user-local", "PARTIES");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(first, second));
        when(client.run(any(), eq("TRACE_COVERAGE"), eq("REQ_COVERAGE")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "Assessment response for " + scenario,
                                objectMapper.readTree("{}"),
                                objectMapper.readTree("[]"),
                                List.of(first.getId(), second.getId()),
                                List.of(),
                                List.of(),
                                assessments,
                                false,
                                false,
                                "NONE",
                                0.8));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(
                        () ->
                                service.continueFromParticipantMessage(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        new RoomMessageCommand(
                                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                                "Please inspect both attachments.",
                                                List.of(first.getId(), second.getId())),
                                        "MESSAGE_COVERAGE",
                                        CLOCK.instant(),
                                        "TRACE_COVERAGE",
                                        "REQ_COVERAGE"))
                .isInstanceOfSatisfying(
                        AgentExecutionException.class,
                        failure -> {
                            assertThat(failure.errorCode())
                                    .isEqualTo(ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID);
                            assertThat(failure.details())
                                    .containsKeys(
                                            "duplicate_evidence_ids",
                                            "unknown_evidence_ids",
                                            "missing_evidence_ids");
                        });
        verify(verificationRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.invalidAssessmentCoverage()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.invalidAssessmentCoverage()」：作为测试辅助方法为“核对完整业务行为（场景方法「invalidAssessmentCoverage」）”组装或读取「assessment」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.invalidAssessmentCoverage()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.invalidAssessmentCoverage()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.invalidAssessmentCoverage()」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_COVERAGE_1」、「EVIDENCE_UNKNOWN」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static Stream<Arguments> invalidAssessmentCoverage() {
        return Stream.of(
                Arguments.of(
                        "missing assessment",
                        List.of(assessment("EVIDENCE_COVERAGE_1", false))),
                Arguments.of(
                        "unknown assessment",
                        List.of(
                                assessment("EVIDENCE_COVERAGE_1", false),
                                assessment("EVIDENCE_UNKNOWN", false))),
                Arguments.of(
                        "duplicate assessment",
                        List.of(
                                assessment("EVIDENCE_COVERAGE_1", false),
                                assessment("EVIDENCE_COVERAGE_1", false))));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification()」：复现“核对完整业务行为（场景方法「legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_HISTORY_ONLY」、「USER」、「user-local」、「PARTIES」。
    // 上游调用：「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification()」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_HISTORY_ONLY」、「USER」、「user-local」、「PARTIES」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification() throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        EvidenceItemEntity historical =
                evidenceItem("EVIDENCE_HISTORY_ONLY", "USER", "user-local", "PARTIES");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(historical));
        when(client.run(any(), eq("TRACE_TEXT_ONLY"), eq("REQ_TEXT_ONLY")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "Please explain the evidence source.",
                                objectMapper.readTree("{}"),
                                objectMapper.readTree("[]"),
                                List.of(historical.getId()),
                                List.of(
                                        new EvidenceAgentTurnResult.EvidenceVerificationSuggestion(
                                                historical.getId(),
                                                "A previous item was mentioned.",
                                                0.72)),
                                List.of(),
                                false,
                                false,
                                "NONE",
                                0.72));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "This message does not submit or reference a file.",
                        List.of()),
                "MESSAGE_TEXT_ONLY",
                CLOCK.instant(),
                "TRACE_TEXT_ONLY",
                "REQ_TEXT_ONLY");

        verify(verificationRepository, never()).save(any());
        verify(verificationRepository, never())
                .findTopByEvidenceIdOrderByVerificationVersionDesc(historical.getId());
        ArgumentCaptor<RoomMessageEntity> displayedReply =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(displayedReply.capture());
        assertThat(displayedReply.getValue().getMessageText())
                .isEqualTo("Please explain the evidence source.");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence()」：复现“核对完整业务行为（场景方法「attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc」，再用 「assertThatThrownBy」、「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_LEGACY_ONLY」、「USER」、「user-local」、「PARTIES」。
    // 上游调用：「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence()」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_LEGACY_ONLY」、「USER」、「user-local」、「PARTIES」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        EvidenceItemEntity attached =
                evidenceItem("EVIDENCE_LEGACY_ONLY", "USER", "user-local", "PARTIES");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(attached));
        when(client.run(any(), eq("TRACE_LEGACY_ATTACHMENT"), eq("REQ_LEGACY_ATTACHMENT")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "This legacy suggestion must not become a verification.",
                                objectMapper.readTree("{}"),
                                objectMapper.readTree("[]"),
                                List.of(attached.getId()),
                                List.of(
                                        new EvidenceAgentTurnResult.EvidenceVerificationSuggestion(
                                                attached.getId(),
                                                "Legacy confidence suggestion",
                                                0.93)),
                                List.of(),
                                false,
                                false,
                                "NONE",
                                0.93));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(
                        () ->
                                service.continueFromParticipantMessage(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        new RoomMessageCommand(
                                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                                "Inspect this attachment.",
                                                List.of(attached.getId())),
                                        "MESSAGE_LEGACY_ATTACHMENT",
                                        CLOCK.instant(),
                                        "TRACE_LEGACY_ATTACHMENT",
                                        "REQ_LEGACY_ATTACHMENT"))
                .isInstanceOfSatisfying(
                        AgentExecutionException.class,
                        failure -> {
                            assertThat(failure.errorCode())
                                    .isEqualTo(ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID);
                            assertThat(failure.details().get("missing_evidence_ids"))
                                    .isEqualTo(List.of(attached.getId()));
                        });
        verify(verificationRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply()」：复现“核对完整业务行为（场景方法「partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「intakeDossierRepository.findByCaseIdAndRoomType」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「EVIDENCE_CLERK」、「EVIDENCE_CLERK:USER:v1」、「MEMEO_DEFAULT」。
    // 上游调用：「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply()」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「EVIDENCE_CLERK」、「EVIDENCE_CLERK:USER:v1」、「MEMEO_DEFAULT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        AgentConversationSessionEntity userSession =
                agentSession(
                        accessSession(dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER)),
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT");
        RoomTurnMemoryEntity previousParticipantTurn =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_PREVIOUS_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "user-local",
                        "USER",
                        "I previously described the missing parcel.",
                        userSession,
                        accessSession(dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER)),
                        "{}");
        RoomTurnMemoryEntity previousClerkTurn =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_PREVIOUS_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "Please upload the delivery photo.",
                        "{}",
                        "{}",
                        "[]",
                        "EVIDENCE_RUN_1",
                        userSession,
                        accessSession(dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER)),
                        "{}");
        RoomTurnMemoryEntity unscopedHistoricalTurn =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_UNSCOPED_HISTORICAL",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "user-local",
                        "USER",
                        "This unscoped historical payload must not enter the formal envelope.");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(2);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(intakeDossierWithFormalFacts(dispute.getId())));
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(
                        List.of(
                                evidenceItem(
                                        "EVIDENCE_USER_PRIVATE",
                                        "USER",
                                        "user-local",
                                        "PRIVATE"),
                                evidenceItem(
                                        "EVIDENCE_MERCHANT_PRIVATE",
                                        "MERCHANT",
                                        "merchant-local",
                                        "PRIVATE"),
                                evidenceItem(
                                        "EVIDENCE_SHARED",
                                        "MERCHANT",
                                        "merchant-local",
                                        "PARTIES")));
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(
                        List.of(
                                previousClerkTurn,
                                previousParticipantTurn,
                                unscopedHistoricalTurn));
        when(client.run(any(), eq("TRACE_EVIDENCE"), eq("REQ_EVIDENCE")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "I can help organize this evidence. Please add any carrier response if available.",
                                objectMapper.readTree("{\"next_best_action\":\"ADD_CARRIER_RESPONSE\"}"),
                                objectMapper.readTree("[]"),
                                List.of("EVIDENCE_USER_PRIVATE"),
                                List.of(
                                        new EvidenceAgentTurnResult.EvidenceVerificationSuggestion(
                                                "EVIDENCE_USER_PRIVATE",
                                                "The user-private delivery material is relevant but still needs carrier corroboration.",
                                                0.62)),
                                List.of(),
                                List.of(assessment("EVIDENCE_USER_PRIVATE", false)),
                                false,
                                false,
                                "STUB",
                                0.78));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        "EVIDENCE_USER_PRIVATE"))
                .thenReturn(Optional.empty());
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(7L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "The parcel status says signed, but my front door camera shows no delivery.",
                        List.of("EVIDENCE_USER_PRIVATE")),
                "MESSAGE_EVIDENCE",
                CLOCK.instant(),
                "TRACE_EVIDENCE",
                "REQ_EVIDENCE");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_EVIDENCE"), eq("REQ_EVIDENCE"));
        EvidenceContextEnvelopeV1 envelope = command.getValue().contextEnvelope();
        assertThat(envelope.schemaVersion())
                .isEqualTo(EvidenceContextEnvelopeV1.SCHEMA_VERSION);
        assertThat(envelope.capturedAt()).isEqualTo(CLOCK.instant().toString());
        assertThat(envelope.caseSnapshot().caseId()).isEqualTo(dispute.getId());
        assertThat(envelope.roomPolicy().roomType()).isEqualTo(RoomType.EVIDENCE);
        assertThat(envelope.currentEvent().eventType()).isEqualTo("PARTY_MESSAGE");
        assertThat(envelope.currentEvent().eventId()).isEqualTo("MESSAGE_EVIDENCE");
        assertThat(envelope.actorSnapshot().actorRole()).isEqualTo("USER");
        assertThat(envelope.actorSnapshot().actorId()).isEqualTo("user-local");
        assertThat(command.getValue().agentContext().actorId()).isEqualTo("user-local");
        assertThat(command.getValue().agentContext().actorRole()).isEqualTo("USER");
        assertThat(command.getValue().agentContext().agentKey()).isEqualTo("EVIDENCE_CLERK");
        assertThat(command.getValue().agentContext().scopeType())
                .isEqualTo("EVIDENCE_PARTY_PRIVATE");
        assertThat(envelope.currentEvent().messageType())
                .isEqualTo(MessageType.PARTY_TEXT);
        assertThat(envelope.currentEvent().text()).contains("front door camera");
        assertThat(envelope.intakeDossierSnapshot().payload().path("schema_version").asText())
                .isEqualTo("intake_case_detail.v1");
        assertThat(envelope.visibleEvidence())
                .extracting(EvidenceContextEnvelopeV1.VisibleEvidence::evidenceId)
                .containsExactly("EVIDENCE_USER_PRIVATE");
        assertThat(envelope.privateConversation().recentTurns())
                .extracting(turn -> turn.agentRole())
                .contains("EVIDENCE_CLERK");
        assertThat(envelope.privateConversation().sourceCount()).isEqualTo(2);
        assertThat(envelope.privateConversation().truncated()).isFalse();
        assertThat(envelope.privateConversation().recentTurns())
                .allSatisfy(
                        turn -> {
                            assertThat(turn.agentSessionId()).isNotBlank();
                            assertThat(turn.conversationScope()).isNotBlank();
                        });
        JsonNode commandJson = objectMapper.valueToTree(command.getValue());
        assertThat(commandJson.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("context_envelope", "agent_context");
        assertThat(commandJson.path("context_envelope").path("schema_version").asText())
                .isEqualTo("evidence_context_envelope.v1");
        assertThat(commandJson.path("context_envelope").has("case_intake_dossier"))
                .isFalse();
        ArgumentCaptor<EvidenceVerificationEntity> verification =
                ArgumentCaptor.forClass(EvidenceVerificationEntity.class);
        verify(verificationRepository).save(verification.capture());
        assertThat(verification.getValue().getEvidenceId())
                .isEqualTo("EVIDENCE_USER_PRIVATE");
        assertThat(verification.getValue().getVerificationVersion()).isEqualTo(1);
        assertThat(verification.getValue().getAgentFindingsJson())
                .contains("possible surface mark")
                .contains("\"confidence_score\":0.84");
        assertThat(commandJson.path("context_envelope")
                        .path("current_event")
                        .path("message_type")
                        .asText())
                .isEqualTo("PARTY_TEXT");
        JsonNode serializedEvidence =
                commandJson.path("context_envelope").path("visible_evidence").get(0);
        assertThat(serializedEvidence.path("evidence_id").asText())
                .isEqualTo("EVIDENCE_USER_PRIVATE");
        assertThat(serializedEvidence.has("content")).isFalse();
        assertThat(serializedEvidence.has("submitted_by_role")).isTrue();
        assertThat(serializedEvidence.has("content_url")).isTrue();
        assertThat(serializedEvidence.has("parse_status")).isTrue();
        assertThat(serializedEvidence.has("metadata")).isTrue();
        assertThat(serializedEvidence.has("extraction")).isTrue();
        assertThat(serializedEvidence.has("evidenceId")).isFalse();

        ArgumentCaptor<RoomTurnMemoryEntity> memories =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memories.capture());
        RoomTurnMemoryEntity participantMemory = memories.getAllValues().get(0);
        assertThat(participantMemory.getRoomType()).isEqualTo(RoomType.EVIDENCE);
        assertThat(participantMemory.getTurnNo()).isEqualTo(3);
        assertThat(participantMemory.getActorId()).isEqualTo("user-local");
        assertThat(participantMemory.getAnswerRole()).isEqualTo("USER");
        assertThat(participantMemory.getAnswerContent()).contains("front door camera");
        RoomTurnMemoryEntity clerkMemory = memories.getAllValues().get(1);
        assertThat(clerkMemory.getRoomType()).isEqualTo(RoomType.EVIDENCE);
        assertThat(clerkMemory.getTurnNo()).isEqualTo(3);
        assertThat(clerkMemory.getActorId()).isEqualTo("evidence-clerk");
        assertThat(clerkMemory.getAgentRole()).isEqualTo("EVIDENCE_CLERK");
        assertThat(clerkMemory.getAgentResponse()).contains("organize this evidence");

        ArgumentCaptor<RoomMessageEntity> agentMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(agentMessage.capture());
        assertThat(agentMessage.getValue().getSequenceNo()).isEqualTo(8);
        assertThat(agentMessage.getValue().getSenderRole()).isEqualTo("CUSTOMER_SERVICE");
        assertThat(agentMessage.getValue().getSenderId()).isEqualTo("evidence-clerk");
        assertThat(agentMessage.getValue().getMessageType()).isEqualTo(MessageType.AGENT_MESSAGE);
        List<String> audience =
                objectMapper.readValue(
                        agentMessage.getValue().getAudienceJson(), new TypeReference<>() {});
        assertThat(audience)
                .containsExactly(
                        "USER",
                        "CUSTOMER_SERVICE",
                        "PLATFORM_REVIEWER",
                        "ADMIN",
                        "SYSTEM");
        assertThat(audience).doesNotContain("MERCHANT");
        List<String> audienceActorIds =
                objectMapper.readValue(
                        agentMessage.getValue().getAudienceActorIdsJson(),
                        new TypeReference<>() {});
        assertThat(audienceActorIds).containsExactly("user-local");
        verify(eventService)
                .recordRoomMessage(
                        eq(dispute.getId()),
                        eq(room.getId()),
                        eq(agentMessage.getValue().getId()),
                        eq(agentMessage.getValue().getMessageText()),
                        eq(agentMessage.getValue().getAudienceJson()),
                        eq(agentMessage.getValue().getAudienceActorIdsJson()),
                        eq("evidence-clerk"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt()」：复现“核对完整业务行为（场景方法「ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MESSAGE_EXISTING_OPENING」、「CUSTOMER_SERVICE」、「evidence-clerk」、「[\"user-local\"]」。
    // 上游调用：「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt()」守住「房间协作与权限」的可执行规格，尤其防止 「MESSAGE_EXISTING_OPENING」、「CUSTOMER_SERVICE」、「evidence-clerk」、「[\"user-local\"]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void ensureOpeningCreatesOneActorScopedClerkMessageAndReusesIt()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        RoomMessageEntity existingOpening =
                RoomMessageEntity.create(
                        "MESSAGE_EXISTING_OPENING",
                        dispute.getId(),
                        room.getId(),
                        9,
                        com.example.dispute.room.domain.MessageSenderType.AGENT,
                        "CUSTOMER_SERVICE",
                        "evidence-clerk",
                        "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[\"user-local\"]",
                        MessageType.AGENT_MESSAGE,
                        "Existing opening question.",
                        "[]",
                        "agent-evidence-opening:" + dispute.getId() + ":AGENT_SESSION_user-local_EVIDENCE",
                        Instant.parse("2026-07-06T00:01:00Z"),
                        "TRACE_OPENING");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingOpening));
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(
                        Optional.of(
                                CaseIntakeDossierEntity.create(
                                        "INTAKE_DOSSIER_OPENING",
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        "{\"schema_version\":\"intake_case_detail.v1\",\"dispute_focus\":{\"core_issue\":\"SCRATCHED_WATCH\"}}",
                                        86,
                                        true,
                                        "ACCEPTED",
                                        1,
                                        "dispute-intake-officer")));
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(
                        List.of(
                                evidenceItem(
                                        "EVIDENCE_USER_OPENING",
                                        "USER",
                                        "user-local",
                                        "PARTIES")));
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_OPENING"), eq("REQ_OPENING")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "请先补充手表划痕照片原图、拍摄时间、物流签收记录和商家质检视频。",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of("EVIDENCE_USER_OPENING"),
                                false,
                                false,
                                "LLM",
                                0.81));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(8L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_OPENING",
                        "REQ_OPENING");
        var reused =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_OPENING",
                        "REQ_OPENING");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client, org.mockito.Mockito.times(1))
                .run(command.capture(), eq("TRACE_OPENING"), eq("REQ_OPENING"));
        EvidenceContextEnvelopeV1 envelope = command.getValue().contextEnvelope();
        assertThat(envelope.currentEvent().eventType()).isEqualTo("ROOM_OPENING");
        assertThat(envelope.actorSnapshot().actorId()).isEqualTo("user-local");
        assertThat(envelope.currentEvent().eventId())
                .startsWith("EVIDENCE_OPENING_");
        assertThat(envelope.currentEvent().text()).isNull();
        assertThat(envelope.intakeDossierSnapshot()
                        .payload()
                        .path("dispute_focus")
                        .path("core_issue")
                        .asText())
                .isEqualTo("SCRATCHED_WATCH");
        assertThat(envelope.visibleEvidence())
                .extracting(EvidenceContextEnvelopeV1.VisibleEvidence::evidenceId)
                .containsExactly("EVIDENCE_USER_OPENING");

        ArgumentCaptor<RoomMessageEntity> savedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(1)).save(savedMessage.capture());
        assertThat(savedMessage.getValue().getIdempotencyKey())
                .isEqualTo(
                        "agent-evidence-opening:dossier-v3:"
                                + dispute.getId()
                                + ":AGENT_SESSION_user-local_EVIDENCE");
        assertThat(savedMessage.getValue().getAudienceJson())
                .contains("USER")
                .doesNotContain("MERCHANT");
        assertThat(savedMessage.getValue().getAudienceActorIdsJson())
                .contains("user-local");
        assertThat(created.messageText()).contains("划痕照片原图");
        assertThat(reused.id()).isEqualTo("MESSAGE_EXISTING_OPENING");
    }

    @Test
    void merchantEvidenceContextKeepsOnlySharedBilateralIntakeProjection()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        CaseIntakeDossierEntity bilateralDossier =
                CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_BILATERAL",
                        dispute.getId(),
                        RoomType.INTAKE,
                        """
                        {
                          "schema_version":"intake_case_detail.v1",
                          "claim_resolution":{"original_statement":"PRIVATE_INITIATOR_TRANSCRIPT"},
                          "handoff_notes":{"latest_remark":"PRIVATE_INITIATOR_REMARK"},
                          "party_positions":{"raw_statement":"PRIVATE_PARTY_TEXT"},
                          "case_fact_matrix":{
                            "schema_version":"case_fact_matrix.v2",
                            "matrix_kind":"BILATERAL_FROZEN",
                            "matrix_version":4,
                            "case_overview":{
                              "neutral_summary":"Shared neutral air-fryer dispute summary.",
                              "core_conflict":"Whether first use exposed a product safety defect."
                            },
                            "fact_rows":[
                              {"fact_id":"FACT_PRODUCT_STATE","fact_target":"First-use product state"}
                            ]
                          }
                        }
                        """,
                        90,
                        true,
                        "ACCEPTED",
                        4,
                        "dispute-intake-officer");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(bilateralDossier));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_MERCHANT_OPENING"), eq("REQ_MERCHANT_OPENING")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "Please provide the merchant-side quality records.",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "STUB",
                                0.8));
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.ensureOpening(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                "TRACE_MERCHANT_OPENING",
                "REQ_MERCHANT_OPENING");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client)
                .run(
                        command.capture(),
                        eq("TRACE_MERCHANT_OPENING"),
                        eq("REQ_MERCHANT_OPENING"));
        EvidenceContextEnvelopeV1 envelope = command.getValue().contextEnvelope();
        JsonNode payload = envelope.intakeDossierSnapshot().payload();
        assertThat(envelope.actorSnapshot().actorRole()).isEqualTo("MERCHANT");
        assertThat(envelope.caseSnapshot().description())
                .isEqualTo("Shared neutral air-fryer dispute summary.");
        assertThat(payload.path("case_fact_matrix").path("matrix_kind").asText())
                .isEqualTo("BILATERAL_FROZEN");
        assertThat(payload.path("case_fact_matrix").path("matrix_version").asInt())
                .isEqualTo(4);
        assertThat(payload.path("case_story").path("one_sentence_summary").asText())
                .isEqualTo("Shared neutral air-fryer dispute summary.");
        assertThat(payload.has("claim_resolution")).isFalse();
        assertThat(payload.has("handoff_notes")).isFalse();
        assertThat(payload.has("party_positions")).isFalse();
        assertThat(objectMapper.writeValueAsString(command.getValue()))
                .doesNotContain(
                        "PRIVATE_INITIATOR_TRANSCRIPT",
                        "PRIVATE_INITIATOR_REMARK",
                        "PRIVATE_PARTY_TEXT");
        ArgumentCaptor<RoomTurnMemoryEntity> memory =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository).save(memory.capture());
        assertThat(memory.getValue().getScrollSnapshotJson())
                .isEqualTo(memory.getValue().getDossierPatchJson());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening()」：复现“核对完整业务行为（场景方法「ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MESSAGE_EXISTING_USER_THREAD」、「USER」、「user-local」、「[\"user-local\"]」。
    // 上游调用：「EvidenceAgentTurnServiceTest.ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening()」守住「房间协作与权限」的可执行规格，尤其防止 「MESSAGE_EXISTING_USER_THREAD」、「USER」、「user-local」、「[\"user-local\"]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void ensureOpeningReusesExistingActorScopedConversationInsteadOfAppendingLateOpening()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        RoomMessageEntity existingUserMessage =
                RoomMessageEntity.create(
                        "MESSAGE_EXISTING_USER_THREAD",
                        dispute.getId(),
                        room.getId(),
                        4,
                        com.example.dispute.room.domain.MessageSenderType.PARTY,
                        "USER",
                        "user-local",
                        "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[\"user-local\"]",
                        MessageType.PARTY_TEXT,
                        "I already started this evidence conversation.",
                        "[]",
                        "room-message-existing-user",
                        Instant.parse("2026-07-06T00:04:00Z"),
                        "TRACE_EXISTING_THREAD");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(List.of(existingUserMessage));
        lenient().when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(4);
        lenient().when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        lenient().when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        lenient().when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        lenient().when(client.run(any(), eq("TRACE_EXISTING"), eq("REQ_EXISTING")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "This late opening should not be appended.",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "LLM",
                                0.8));
        lenient().when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(4L);
        lenient().when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var reused =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_EXISTING",
                        "REQ_EXISTING");

        assertThat(reused.id()).isEqualTo("MESSAGE_EXISTING_USER_THREAD");
        verify(client, never()).run(any(), any(), any());
        verify(messageRepository, never()).save(any(RoomMessageEntity.class));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening()」：复现“核对完整业务行为（场景方法「ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MESSAGE_STALE_GENERIC_OPENING」、「CUSTOMER_SERVICE」、「evidence-clerk」、「[\"user-local\"]」。
    // 上游调用：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening()」守住「房间协作与权限」的可执行规格，尤其防止 「MESSAGE_STALE_GENERIC_OPENING」、「CUSTOMER_SERVICE」、「evidence-clerk」、「[\"user-local\"]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void ensureOpeningSupersedesOnlyGenericWelcomeOpeningWithDossierSpecificOpening()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        RoomMessageEntity staleGenericOpening =
                RoomMessageEntity.create(
                        "MESSAGE_STALE_GENERIC_OPENING",
                        dispute.getId(),
                        room.getId(),
                        1,
                        com.example.dispute.room.domain.MessageSenderType.AGENT,
                        "CUSTOMER_SERVICE",
                        "evidence-clerk",
                        "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[\"user-local\"]",
                        MessageType.AGENT_MESSAGE,
                        "您好！我是您的证据书记官，请上传与本案相关的证据材料。",
                        "[]",
                        "agent-evidence-opening:" + dispute.getId() + ":AGENT_SESSION_user-local_EVIDENCE",
                        Instant.parse("2026-07-06T00:01:00Z"),
                        "TRACE_STALE_OPENING");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(List.of(staleGenericOpening));
        when(permissionService.canReadActorAudience(any(), eq(List.of("user-local"))))
                .thenReturn(true);
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(1);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(
                        Optional.of(
                                CaseIntakeDossierEntity.create(
                                        "INTAKE_DOSSIER_STALE_OPENING",
                                        dispute.getId(),
                                        RoomType.INTAKE,
                                        "{\"schema_version\":\"intake_case_detail.v1\",\"dispute_focus\":{\"core_issue\":\"SCRATCHED_WATCH\",\"facts_to_verify\":[\"商家质检视频\",\"用户划痕原图\",\"物流签收记录\"]}}",
                                        84,
                                        true,
                                        "ACCEPTED",
                                        1,
                                        "dispute-intake-officer")));
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_STALE"), eq("REQ_STALE")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "我先根据接待室收敛的案情开始举证核对。本案当前争议焦点是 SCRATCHED_WATCH，请补充商家质检视频、用户划痕原图和物流签收记录。",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "LLM",
                                0.86));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_STALE",
                        "REQ_STALE");

        assertThat(created.messageText()).contains("接待室收敛的案情");
        assertThat(created.messageText()).contains("SCRATCHED_WATCH");
        verify(client).run(any(), eq("TRACE_STALE"), eq("REQ_STALE"));
        ArgumentCaptor<RoomMessageEntity> savedMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(savedMessage.capture());
        assertThat(savedMessage.getValue().getSequenceNo()).isEqualTo(2);
        assertThat(savedMessage.getValue().getIdempotencyKey()).contains("dossier-v3");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.ensureOpeningKeepsIntakeSnapshotNullWhenIntakeDossierIsMissing()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.ensureOpeningKeepsIntakeSnapshotNullWhenIntakeDossierIsMissing()」：复现“核对完整业务行为（场景方法「ensureOpeningKeepsIntakeSnapshotNullWhenIntakeDossierIsMissing」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「TRACE_FALLBACK_DOSSIER」、「REQ_FALLBACK_DOSSIER」、「请围绕签收未收到争议补充物流签收记录、门牌照片和投递轨迹。」、「LLM」。
    // 上游调用：「EvidenceAgentTurnServiceTest.ensureOpeningKeepsIntakeSnapshotNullWhenIntakeDossierIsMissing()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.ensureOpeningKeepsIntakeSnapshotNullWhenIntakeDossierIsMissing()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.ensureOpeningKeepsIntakeSnapshotNullWhenIntakeDossierIsMissing()」守住「房间协作与权限」的可执行规格，尤其防止 「TRACE_FALLBACK_DOSSIER」、「REQ_FALLBACK_DOSSIER」、「请围绕签收未收到争议补充物流签收记录、门牌照片和投递轨迹。」、「LLM」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void ensureOpeningKeepsIntakeSnapshotNullWhenIntakeDossierIsMissing()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_FALLBACK_DOSSIER"), eq("REQ_FALLBACK_DOSSIER")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "请围绕签收未收到争议补充物流签收记录、门牌照片和投递轨迹。",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "LLM",
                                0.8));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(0L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.ensureOpening(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                "TRACE_FALLBACK_DOSSIER",
                "REQ_FALLBACK_DOSSIER");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_FALLBACK_DOSSIER"), eq("REQ_FALLBACK_DOSSIER"));
        EvidenceContextEnvelopeV1 envelope = command.getValue().contextEnvelope();
        assertThat(envelope.intakeDossierSnapshot()).isNull();
        assertThat(envelope.caseSnapshot().description())
                .contains("parcel was marked signed but never arrived");
        assertThat(envelope.caseSnapshot().disputeType())
                .isEqualTo("SIGNED_NOT_RECEIVED");
        assertThat(envelope.caseSnapshot().orderId()).isEqualTo("ORDER-EVIDENCE");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOpeningOnlyThreadWithPendingFocusFallback()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOpeningOnlyThreadWithPendingFocusFallback()」：复现“核对完整业务行为（场景方法「ensureOpeningSupersedesOpeningOnlyThreadWithPendingFocusFallback」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「messageRepository.findByCaseIdAndIdempotencyKey」、「messageRepository.findAllByRoomIdOrderBySequenceNoAsc」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「MESSAGE_STALE_GENERIC_OPENING」、「CUSTOMER_SERVICE」、「evidence-clerk」、「[\"user-local\"]」。
    // 上游调用：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOpeningOnlyThreadWithPendingFocusFallback()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOpeningOnlyThreadWithPendingFocusFallback()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.ensureOpeningSupersedesOpeningOnlyThreadWithPendingFocusFallback()」守住「房间协作与权限」的可执行规格，尤其防止 「MESSAGE_STALE_GENERIC_OPENING」、「CUSTOMER_SERVICE」、「evidence-clerk」、「[\"user-local\"]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void ensureOpeningSupersedesOpeningOnlyThreadWithPendingFocusFallback()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        RoomMessageEntity staleGenericOpening =
                RoomMessageEntity.create(
                        "MESSAGE_STALE_GENERIC_OPENING",
                        dispute.getId(),
                        room.getId(),
                        1,
                        com.example.dispute.room.domain.MessageSenderType.AGENT,
                        "CUSTOMER_SERVICE",
                        "evidence-clerk",
                        "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[\"user-local\"]",
                        MessageType.AGENT_MESSAGE,
                        "您好！我是您的证据书记官，请上传与本案相关的证据材料。",
                        "[]",
                        "agent-evidence-opening:legacy",
                        Instant.parse("2026-07-06T00:01:00Z"),
                        "TRACE_STALE_OPENING");
        RoomMessageEntity stalePendingFocusOpening =
                RoomMessageEntity.create(
                        "MESSAGE_STALE_PENDING_FOCUS_OPENING",
                        dispute.getId(),
                        room.getId(),
                        2,
                        com.example.dispute.room.domain.MessageSenderType.AGENT,
                        "CUSTOMER_SERVICE",
                        "evidence-clerk",
                        "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[\"user-local\"]",
                        MessageType.AGENT_MESSAGE,
                        "我先根据接待室收敛的案情开始举证核对。本案当前争议焦点是 争议焦点待确认，首轮请围绕这些材料补充证据：原始证据文件、证据形成时间、证据来源路径。",
                        "[]",
                        "agent-evidence-opening:dossier-v2:stale",
                        Instant.parse("2026-07-06T00:02:00Z"),
                        "TRACE_STALE_OPENING");
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(List.of(staleGenericOpening, stalePendingFocusOpening));
        when(permissionService.canReadActorAudience(any(), eq(List.of("user-local"))))
                .thenReturn(true);
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(2);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_STALE_STACK"), eq("REQ_STALE_STACK")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "我先根据接待室收敛的案情开始举证核对。本案当前争议焦点是 SIGNED_NOT_RECEIVED，请补充物流签收记录、投递轨迹和收货地址匹配记录。",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "LLM",
                                0.82));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(2L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_STALE_STACK",
                        "REQ_STALE_STACK");

        assertThat(created.messageText()).contains("SIGNED_NOT_RECEIVED");
        verify(client).run(any(), eq("TRACE_STALE_STACK"), eq("REQ_STALE_STACK"));
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty()」：复现“核对完整业务行为（场景方法「evidenceAgentRecentTurnsAreScopedToTheSpeakingParty」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「intakeDossierRepository.findByCaseIdAndRoomType」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「EVIDENCE_CLERK」、「EVIDENCE_CLERK:USER:v1」、「MEMEO_DEFAULT」。
    // 上游调用：「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty()」守住「房间协作与权限」的可执行规格，尤其防止 「user-local」、「EVIDENCE_CLERK」、「EVIDENCE_CLERK:USER:v1」、「MEMEO_DEFAULT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidenceAgentRecentTurnsAreScopedToTheSpeakingParty() throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        CaseAccessSessionEntity userAccess =
                accessSession(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER));
        AgentConversationSessionEntity userSession =
                agentSession(
                        userAccess,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT");
        CaseAccessSessionEntity merchantAccess =
                accessSession(
                        dispute.getId(),
                        new AuthenticatedActor("merchant-local", ActorRole.MERCHANT));
        AgentConversationSessionEntity merchantSession =
                agentSession(
                        merchantAccess,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:MERCHANT:v1",
                        "MEMEO_DEFAULT");
        RoomTurnMemoryEntity userParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_USER_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "user-local",
                        "USER",
                        "用户侧私聊：门口监控显示没有投递。");
        RoomTurnMemoryEntity userClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_USER_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        1,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "用户侧书记官回复：请补充门口监控原视频。",
                        "{}",
                        "{}",
                        "[]",
                        "EVIDENCE_RUN_USER");
        RoomTurnMemoryEntity merchantParticipant =
                RoomTurnMemoryEntity.participantTurn(
                        "MEMORY_MERCHANT_PARTY",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "merchant-local",
                        "MERCHANT",
                        "商家侧私聊：发货质检视频显示完好。");
        RoomTurnMemoryEntity merchantClerk =
                RoomTurnMemoryEntity.agentTurn(
                        "MEMORY_MERCHANT_CLERK",
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        2,
                        "evidence-clerk",
                        "EVIDENCE_CLERK",
                        "商家侧书记官回复：请补充质检视频原文件。",
                        "{}",
                        "{}",
                        "[]",
                        "EVIDENCE_RUN_MERCHANT");
        attachSessionScope(userParticipant, userSession);
        attachSessionScope(userClerk, userSession);
        attachSessionScope(merchantParticipant, merchantSession);
        attachSessionScope(merchantClerk, merchantSession);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(2);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of(merchantClerk, merchantParticipant, userClerk, userParticipant));
        when(client.run(any(), eq("TRACE_USER_SCOPED"), eq("REQ_USER_SCOPED")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "我会继续围绕用户侧材料核验，不判断责任。",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "STUB",
                                0.75));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(5L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("user-local", ActorRole.USER),
                new RoomMessageCommand(
                        MessageType.PARTY_TEXT,
                        "用户侧本轮：我可以补充监控原视频。",
                        List.of()),
                "MESSAGE_USER_SCOPED",
                CLOCK.instant(),
                "TRACE_USER_SCOPED",
                "REQ_USER_SCOPED");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_USER_SCOPED"), eq("REQ_USER_SCOPED"));

        assertThat(command.getValue().contextEnvelope().privateConversation().recentTurns())
                .extracting(IntakeRecentTurn::answerContent)
                .doesNotContain("商家侧私聊：发货质检视频显示完好。");
        assertThat(command.getValue().contextEnvelope().privateConversation().recentTurns())
                .extracting(IntakeRecentTurn::agentResponse)
                .doesNotContain("商家侧书记官回复：请补充质检视频原文件。");
        assertThat(command.getValue().contextEnvelope().privateConversation().recentTurns())
                .extracting(IntakeRecentTurn::answerContent)
                .contains("用户侧私聊：门口监控显示没有投递。");
        assertThat(command.getValue().contextEnvelope().privateConversation().recentTurns())
                .extracting(IntakeRecentTurn::agentResponse)
                .contains("用户侧书记官回复：请补充门口监控原视频。");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank()」：复现“核对完整业务行为（场景方法「partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「intakeDossierRepository.findByCaseIdAndRoomType」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_UPLOAD_1」、「MERCHANT」、「merchant-local」、「PRIVATE」。
    // 上游调用：「EvidenceAgentTurnServiceTest.partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank()」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_UPLOAD_1」、「MERCHANT」、「merchant-local」、「PRIVATE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void partyEvidenceReferenceUsesAttachmentRefsWhenTextIsBlank() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.of(intakeDossierWithFormalFacts(dispute.getId())));
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(
                        List.of(
                                evidenceItem(
                                        "EVIDENCE_UPLOAD_1",
                                        "MERCHANT",
                                        "merchant-local",
                                        "PRIVATE")));
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_REFERENCE"), eq("REQ_REFERENCE")))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "I noted this evidence reference for your side.",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of("EVIDENCE_UPLOAD_1"),
                                List.of(),
                                List.of(),
                                List.of(assessment("EVIDENCE_UPLOAD_1", false)),
                                false,
                                false,
                                "STUB",
                                0.7));
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.continueFromParticipantMessage(
                dispute.getId(),
                RoomType.EVIDENCE,
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT),
                new RoomMessageCommand(
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        null,
                        List.of("EVIDENCE_UPLOAD_1")),
                "MESSAGE_REFERENCE",
                CLOCK.instant(),
                "TRACE_REFERENCE",
                "REQ_REFERENCE");

        ArgumentCaptor<EvidenceAgentTurnCommand> command =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client).run(command.capture(), eq("TRACE_REFERENCE"), eq("REQ_REFERENCE"));
        assertThat(command.getValue().contextEnvelope().currentEvent().messageType())
                .isEqualTo(MessageType.PARTY_EVIDENCE_REFERENCE);
        assertThat(command.getValue().contextEnvelope().currentEvent().attachmentRefs())
                .containsExactly("EVIDENCE_UPLOAD_1");

        ArgumentCaptor<RoomTurnMemoryEntity> memories =
                ArgumentCaptor.forClass(RoomTurnMemoryEntity.class);
        verify(memoryRepository, org.mockito.Mockito.times(2)).save(memories.capture());
        assertThat(memories.getAllValues().get(0).getAnswerContent())
                .contains("EVIDENCE_UPLOAD_1");

        ArgumentCaptor<RoomMessageEntity> agentMessage =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository).save(agentMessage.capture());
        assertThat(agentMessage.getValue().getAudienceJson())
                .contains("MERCHANT")
                .doesNotContain("USER");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor()」：复现“拒绝非法输入或越权操作（场景方法「rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「intakeDossierRepository.findByCaseIdAndRoomType」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_MERCHANT_PRIVATE」、「MERCHANT」、「merchant-local」、「PRIVATE」。
    // 上游调用：「EvidenceAgentTurnServiceTest.rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor()」守住「房间协作与权限」的可执行规格，尤其防止 「EVIDENCE_MERCHANT_PRIVATE」、「MERCHANT」、「merchant-local」、「PRIVATE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsEvidenceReferenceThatIsNotVisibleToTheCurrentActor() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(
                        List.of(
                                evidenceItem(
                                        "EVIDENCE_MERCHANT_PRIVATE",
                                        "MERCHANT",
                                        "merchant-local",
                                        "PRIVATE")));
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(
                        () ->
                                service.continueFromParticipantMessage(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        new RoomMessageCommand(
                                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                                null,
                                                List.of("EVIDENCE_MERCHANT_PRIVATE")),
                                        "MESSAGE_UNAUTHORIZED_REFERENCE",
                                        CLOCK.instant(),
                                        "TRACE_UNAUTHORIZED_REFERENCE",
                                        "REQ_UNAUTHORIZED_REFERENCE"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not visible");
        verify(client, never()).run(any(), any(), any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.failedEvidenceAgentCallFailsClosedWithoutPersistingSyntheticVerification()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.failedEvidenceAgentCallFailsClosedWithoutPersistingSyntheticVerification()」：复现“核对完整业务行为（场景方法「failedEvidenceAgentCallFailsClosedWithoutPersistingSyntheticVerification」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「intakeDossierRepository.findByCaseIdAndRoomType」，再用 「assertThatThrownBy」、「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「TRACE_DEGRADED」、「REQ_DEGRADED」、「user-local」、「我会上传开箱照片原图。」。
    // 上游调用：「EvidenceAgentTurnServiceTest.failedEvidenceAgentCallFailsClosedWithoutPersistingSyntheticVerification()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.failedEvidenceAgentCallFailsClosedWithoutPersistingSyntheticVerification()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.failedEvidenceAgentCallFailsClosedWithoutPersistingSyntheticVerification()」守住「房间协作与权限」的可执行规格，尤其防止 「TRACE_DEGRADED」、「REQ_DEGRADED」、「user-local」、「我会上传开箱照片原图。」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void failedEvidenceAgentCallFailsClosedWithoutPersistingSyntheticVerification() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_DEGRADED"), eq("REQ_DEGRADED")))
                .thenThrow(new IllegalStateException("agent endpoint missing"));

        assertThatThrownBy(
                        () ->
                                service.continueFromParticipantMessage(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "我会上传开箱照片原图。",
                                                List.of()),
                                        "MESSAGE_DEGRADED",
                                        CLOCK.instant(),
                                        "TRACE_DEGRADED",
                                        "REQ_DEGRADED"))
                .isInstanceOfSatisfying(
                        AgentExecutionException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.AGENT_SERVICE_UNAVAILABLE));
        verify(messageRepository, never()).save(any());
        verify(verificationRepository, never()).save(any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded()」：复现“核对完整业务行为（场景方法「agentContractMismatchIsNotSilentlyDegraded」）”场景：驱动 「caseRepository.findByIdForUpdate」、「roomRepository.findByCaseIdAndRoomType」、「memoryRepository.findMaxTurnNoByAgentSessionId」、「intakeDossierRepository.findByCaseIdAndRoomType」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「http_status」、「TRACE_CONTRACT」、「REQ_CONTRACT」、「user-local」。
    // 上游调用：「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「EvidenceAgentTurnServiceTest.agentContractMismatchIsNotSilentlyDegraded()」守住「房间协作与权限」的可执行规格，尤其防止 「http_status」、「TRACE_CONTRACT」、「REQ_CONTRACT」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void agentContractMismatchIsNotSilentlyDegraded() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any()))
                .thenReturn(0);
        when(intakeDossierRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(evidenceItemRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        dispute.getId()))
                .thenReturn(List.of());
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(memoryRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AgentExecutionException mismatch =
                new AgentExecutionException(
                        ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                        "contract mismatch",
                        Map.of("http_status", 422));
        when(client.run(any(), eq("TRACE_CONTRACT"), eq("REQ_CONTRACT")))
                .thenThrow(mismatch);

        assertThatThrownBy(
                        () ->
                                service.continueFromParticipantMessage(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "补充说明",
                                                List.of()),
                                        "MESSAGE_CONTRACT",
                                        CLOCK.instant(),
                                        "TRACE_CONTRACT",
                                        "REQ_CONTRACT"))
                .isSameAs(mismatch);
        verify(messageRepository, never()).save(any());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」。
    // 具体功能：「EvidenceAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」：作为测试辅助方法为“核对完整业务行为（场景方法「accessSession」）”组装或读取「CaseAccessSessionEntity.create」、「actor.role」、「actor.actorId」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」由本测试类中的 「EvidenceAgentTurnServiceTest.setUp」、「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」、「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty」 调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.accessSession(String,AuthenticatedActor)」守住「房间协作与权限」的可执行规格，尤其防止 「ACCESS_」、「default」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseAccessSessionEntity accessSession(String caseId, AuthenticatedActor actor) {
        PermissionLevel level =
                actor.role() == ActorRole.MERCHANT
                        ? PermissionLevel.PARTY_MERCHANT
                        : PermissionLevel.PARTY_USER;
        return CaseAccessSessionEntity.create(
                "ACCESS_" + actor.actorId(),
                "default",
                caseId,
                actor.actorId(),
                actor.role(),
                level,
                actor.actorId());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」。
    // 具体功能：「EvidenceAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「agentSession」）”组装或读取「AgentConversationSessionEntity.create」、「accessSession.getActorId」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」由本测试类中的 「EvidenceAgentTurnServiceTest.setUp」、「EvidenceAgentTurnServiceTest.partyTextPersistsEvidenceMemorySendsPartyScopedContextAndAppendsIsolatedClerkReply」、「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty」 调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.agentSession(CaseAccessSessionEntity,RoomType,String,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「AGENT_SESSION_」、「_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentConversationSessionEntity agentSession(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return AgentConversationSessionEntity.create(
                "AGENT_SESSION_" + accessSession.getActorId() + "_" + roomType.name(),
                accessSession,
                roomType,
                agentKey,
                promptProfileId,
                memoryPolicyId,
                accessSession.getActorId());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.attachSessionScope(RoomTurnMemoryEntity,AgentConversationSessionEntity)」。
    // 具体功能：「EvidenceAgentTurnServiceTest.attachSessionScope(RoomTurnMemoryEntity,AgentConversationSessionEntity)」：作为测试辅助方法为“核对完整业务行为（场景方法「attachSessionScope」）”组装或读取「ReflectionTestUtils.setField」、「agentSession.getId」、「agentSession.getConversationScope」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.attachSessionScope(RoomTurnMemoryEntity,AgentConversationSessionEntity)」由本测试类中的 「EvidenceAgentTurnServiceTest.evidenceAgentRecentTurnsAreScopedToTheSpeakingParty」 调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.attachSessionScope(RoomTurnMemoryEntity,AgentConversationSessionEntity)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.attachSessionScope(RoomTurnMemoryEntity,AgentConversationSessionEntity)」守住「房间协作与权限」的可执行规格，尤其防止 「agentSessionId」、「conversationScope」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static void attachSessionScope(
            RoomTurnMemoryEntity memory,
            AgentConversationSessionEntity agentSession) {
        ReflectionTestUtils.setField(memory, "agentSessionId", agentSession.getId());
        ReflectionTestUtils.setField(
                memory, "conversationScope", agentSession.getConversationScope());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.evidenceItem(String,String,String,String)」。
    // 具体功能：「EvidenceAgentTurnServiceTest.evidenceItem(String,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「evidenceItem」）”组装或读取「EvidenceItemEntity.uploaded」、「OffsetDateTime.parse」、「item.markSubmitted」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.evidenceItem(String,String,String,String)」由本测试类中的 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」 调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.evidenceItem(String,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.evidenceItem(String,String,String,String)」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_EVIDENCE_AGENT」、「DOSSIER_1」、「PHOTO」、「UPLOAD」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static EvidenceItemEntity evidenceItem(
            String id, String submittedByRole, String submittedById, String visibility) {
        EvidenceItemEntity item =
                EvidenceItemEntity.uploaded(
                id,
                "CASE_EVIDENCE_AGENT",
                "DOSSIER_1",
                "PHOTO",
                "UPLOAD",
                submittedByRole,
                submittedById,
                "bucket",
                "object-" + id,
                "hash-" + id,
                id + ".jpg",
                "image/jpeg",
                128L,
                visibility,
                OffsetDateTime.parse("2026-07-06T00:00:00Z"));
        item.markSubmitted(
                "BATCH_" + id,
                OffsetDateTime.parse("2026-07-06T00:05:00Z"),
                submittedById);
        return item;
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.evidenceCase()」。
    // 具体功能：「EvidenceAgentTurnServiceTest.evidenceCase()」：作为测试辅助方法为“核对完整业务行为（场景方法「evidenceCase」）”组装或读取「FulfillmentCaseEntity.imported」、「OffsetDateTime.parse」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.evidenceCase()」由本测试类中的 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」 调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.evidenceCase()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.evidenceCase()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_EVIDENCE_AGENT」、「ORDER-EVIDENCE」、「AFTER-EVIDENCE」、「LOG-EVIDENCE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity evidenceCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_AGENT",
                "ORDER-EVIDENCE",
                "AFTER-EVIDENCE",
                "LOG-EVIDENCE",
                "user-local",
                "merchant-local",
                "idem-evidence-agent",
                "SIGNED_NOT_RECEIVED",
                "Signed but not received",
                "The user says the parcel was marked signed but never arrived.",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-06T02:00:00Z"),
                "OMS",
                "EXT-EVIDENCE",
                "system");
    }

    private static FulfillmentCaseEntity hearingCase() {
        return FulfillmentCaseEntity.imported(
                "CASE_EVIDENCE_AGENT",
                "ORDER-EVIDENCE",
                "AFTER-EVIDENCE",
                "LOG-EVIDENCE",
                "user-local",
                "merchant-local",
                "idem-hearing-evidence-agent",
                "SIGNED_NOT_RECEIVED",
                "Signed but not received",
                "The parties are supplementing evidence in hearing round two.",
                RiskLevel.HIGH,
                CaseStatus.HEARING_OPEN,
                "HEARING",
                OffsetDateTime.parse("2026-07-06T02:00:00Z"),
                "OMS",
                "EXT-HEARING-EVIDENCE",
                "system");
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「EvidenceAgentTurnServiceTest.evidenceRoom(FulfillmentCaseEntity)」。
    // 具体功能：「EvidenceAgentTurnServiceTest.evidenceRoom(FulfillmentCaseEntity)」：作为测试辅助方法为“核对完整业务行为（场景方法「evidenceRoom」）”组装或读取「CaseRoomEntity.open」、「OffsetDateTime.parse」、「dispute.getId」，供本测试类的场景方法复用。
    // 上游调用：「EvidenceAgentTurnServiceTest.evidenceRoom(FulfillmentCaseEntity)」由本测试类中的 「EvidenceAgentTurnServiceTest.completeMultimodalAssessmentPersistsCurrentAttachmentAndHumanReviewWinsStatus」、「EvidenceAgentTurnServiceTest.attachmentAssessmentCoverageMismatchFailsClosed」、「EvidenceAgentTurnServiceTest.legacySuggestionWithoutCurrentAttachmentDoesNotCreateVerification」、「EvidenceAgentTurnServiceTest.attachmentWithOnlyLegacySuggestionFailsClosedWithoutVerificationPersistence」 调用。
    // 下游影响：「EvidenceAgentTurnServiceTest.evidenceRoom(FulfillmentCaseEntity)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「EvidenceAgentTurnServiceTest.evidenceRoom(FulfillmentCaseEntity)」守住「房间协作与权限」的可执行规格，尤其防止 「ROOM_EVIDENCE_AGENT」、「2026-07-06T00:00:00Z」、「system」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseRoomEntity evidenceRoom(FulfillmentCaseEntity dispute) {
        return CaseRoomEntity.open(
                "ROOM_EVIDENCE_AGENT",
                dispute.getId(),
                RoomType.EVIDENCE,
                OffsetDateTime.parse("2026-07-06T00:00:00Z"),
                "system");
    }

    private static CaseRoomEntity hearingRoom(FulfillmentCaseEntity dispute) {
        return CaseRoomEntity.open(
                "ROOM_HEARING_EVIDENCE_AGENT",
                dispute.getId(),
                RoomType.HEARING,
                OffsetDateTime.parse("2026-07-06T00:00:00Z"),
                "system");
    }
}
