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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
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
import com.example.dispute.room.application.RoomMessageView;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomTurnMemoryEntity;
import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomTurnMemoryRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.FrozenIntakeSubmissionAuthority;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandReferenceMapper;
import com.example.dispute.workflow.application.command.CaseCommandRequestHasher;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetEvidenceOpeningIngress;
import com.example.dispute.workflow.targete2e.ingress.rooms.TargetRoomCommandIngress;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceTurnResultV2;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import org.springframework.beans.factory.ObjectProvider;
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
    @Mock private CaseProcessProjectionRepository processProjectionRepository;
    @Mock private CaseTimelineEventRepository timelineEventRepository;
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
                        processProjectionRepository,
                        timelineEventRepository,
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
        lenient()
                .when(processProjectionRepository.findById(any()))
                .thenReturn(Optional.empty());
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
        assertThat(agentMessage.getValue().getSenderRole()).isEqualTo("EVIDENCE_CLERK");
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
        AtomicReference<RoomMessageEntity> persistedOpening = new AtomicReference<>();
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenAnswer(ignored -> Optional.ofNullable(persistedOpening.get()));
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
                .thenAnswer(
                        invocation -> {
                            RoomMessageEntity saved = invocation.getArgument(0);
                            persistedOpening.set(saved);
                            return saved;
                        });

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
        assertThat(reused.id()).isEqualTo(created.id());
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

    @Test
    void targetEvidenceOpeningUsesFrozenEpochCommandWithoutLegacyRun() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        FrozenSubmissionFixture frozen =
                frozenSubmissionFixture(dispute.getId(), new AtomicLong(0));
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenReturn(Optional.empty());
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of());

        var prepared =
                service.prepareTargetOpening(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        0,
                        23,
                        frozen.authority().projectionRef(),
                        frozen.authority().matrixContentHash(),
                        Instant.parse("2026-07-06T00:00:00Z"));

        assertThat(prepared.existingMessage()).isNull();
        assertThat(prepared.command()).isNotNull();
        EvidenceContextEnvelopeV1 envelope = prepared.command().contextEnvelope();
        assertThat(envelope.schemaVersion())
                .isEqualTo(EvidenceContextEnvelopeV1.FROZEN_SUBMISSION_SCHEMA_VERSION);
        assertThat(envelope.currentEvent().eventId()).isEqualTo(prepared.idempotencyKey());
        assertThat(envelope.currentEvent().eventType()).isEqualTo("ROOM_OPENING");
        assertThat(envelope.currentEvent().messageType()).isEqualTo(MessageType.AGENT_MESSAGE);
        assertThat(envelope.currentEvent().attachmentRefs()).isEmpty();
        assertThat(envelope.actorSnapshot().actorId()).isEqualTo("user-local");
        assertThat(envelope.frozenSubmission().evidenceRoomEpoch()).isZero();
        assertThat(envelope.frozenSubmission().evidenceFencingToken()).isEqualTo(23);
        assertThat(envelope.frozenSubmission().projectionRef())
                .isEqualTo(frozen.authority().projectionRef());
        assertThat(envelope.frozenSubmission().projectionSha256())
                .isEqualTo(frozen.authority().matrixContentHash());
        verify(client, never()).run(any(), any(), any());
        verify(messageRepository, never()).save(any());
        verify(memoryRepository, never()).save(any());
    }

    @Test
    void freezeBoundTargetFinalizationUsesExactFrozenSubmissionMatrixAndReplaysOnce()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        EvidenceItemEntity attached =
                evidenceItem("EVIDENCE_FROZEN_FINALIZATION", "USER", "user-local", "PARTIES");
        FrozenSubmissionFixture frozen =
                frozenSubmissionFixture(dispute.getId(), new AtomicLong(0));
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of(attached));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        attached.getId()))
                .thenReturn(Optional.empty());
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AtomicReference<RoomMessageEntity> persistedMessage = new AtomicReference<>();
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenAnswer(ignored -> Optional.ofNullable(persistedMessage.get()));
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(1L);
        when(messageRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            RoomMessageEntity saved = invocation.getArgument(0);
                            persistedMessage.set(saved);
                            return saved;
                        });

        var opening =
                service.prepareTargetOpening(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        0,
                        23,
                        frozen.authority().projectionRef(),
                        frozen.authority().matrixContentHash(),
                        Instant.parse("2026-07-06T00:00:00Z"));
        EvidenceContextEnvelopeV1 openingEnvelope = opening.command().contextEnvelope();
        EvidenceContextEnvelopeV1 submissionEnvelope =
                new EvidenceContextEnvelopeV1(
                        openingEnvelope.schemaVersion(),
                        openingEnvelope.capturedAt(),
                        openingEnvelope.caseSnapshot(),
                        null,
                        openingEnvelope.actorSnapshot(),
                        new EvidenceContextEnvelopeV1.CurrentEvent(
                                "MESSAGE_FROZEN_FINALIZATION",
                                "PARTY_MESSAGE",
                                MessageType.PARTY_EVIDENCE_REFERENCE,
                                "user-local",
                                ActorRole.USER.name(),
                                "Please assess the submitted watch evidence.",
                                List.of(attached.getId()),
                                2,
                                CLOCK.instant().toString()),
                        openingEnvelope.visibleEvidence(),
                        openingEnvelope.privateConversation(),
                        openingEnvelope.roomPolicy(),
                        openingEnvelope.frozenSubmission());
        EvidenceAgentTurnCommand command =
                new EvidenceAgentTurnCommand(
                        submissionEnvelope, opening.command().agentContext());
        AgentRunFinalizationContext finalization =
                new AgentRunFinalizationContext(
                        "target-evidence-run:frozen-finalization",
                        dispute.getId(),
                        room.getId(),
                        "EVIDENCE_TURN",
                        "TRACE_FROZEN_FINALIZATION",
                        "evidence-submit:frozen-finalization",
                        objectMapper.valueToTree(command));
        EvidenceAgentTurnResult result =
                new EvidenceAgentTurnResult(
                        "The submitted evidence is bound to the frozen fact matrix.",
                        objectMapper.readTree("{}"),
                        objectMapper.readTree("[]"),
                        List.of(attached.getId()),
                        List.of(),
                        List.of(),
                        List.of(assessment(attached.getId(), false)),
                        false,
                        false,
                        "NONE",
                        0.86);
        ObjectNode invalidResult = objectMapper.valueToTree(result);
        ((ObjectNode)
                        invalidResult
                                .path("evidence_assessments")
                                .get(0)
                                .path("fact_links")
                                .get(0))
                .put("fact_id", "FACT_UNKNOWN");

        assertThat(submissionEnvelope.intakeDossierSnapshot()).isNull();
        assertThat(submissionEnvelope.frozenSubmission().matrix().path("fact_rows").get(0)
                        .path("fact_id").asText())
                .isEqualTo("FACT_GOODS_CONDITION");
        assertThatThrownBy(
                        () -> service.finalizeTargetResult(finalization, command, invalidResult))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("invalid or duplicate fact link");
        verify(memoryRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
        verify(verificationRepository, never()).save(any());

        RoomMessageView first =
                service.finalizeTargetResult(
                        finalization, command, objectMapper.valueToTree(result));
        RoomMessageView replay =
                service.finalizeTargetResult(
                        finalization, command, objectMapper.valueToTree(result));

        assertThat(replay).isEqualTo(first);
        assertThat(first.agentRunId()).isEqualTo(finalization.runId());
        verify(memoryRepository, times(1)).save(any());
        verify(messageRepository, times(1)).save(any());
        verify(verificationRepository, times(1)).save(any());
    }

    @Test
    void targetV3AssessmentPersistsScoresAndDerivesOrderedReviewReasons() throws Exception {
        EvidenceItemEntity attached =
                evidenceItem("EVIDENCE_V2_PROJECTION", "USER", "user-local", "PARTIES");
        CaseAccessSessionEntity accessSession = mock(CaseAccessSessionEntity.class);
        when(accessSession.privileged()).thenReturn(false);
        when(accessSession.getActorRole()).thenReturn(ActorRole.USER);
        when(accessSession.getActorId()).thenReturn("user-local");
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_EVIDENCE_AGENT"))
                .thenReturn(List.of(attached));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        attached.getId()))
                .thenReturn(Optional.empty());
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TargetEvidenceTurnResultV2 result = targetV3AssessmentResult(
                attached.getId(), 0.49, 0.48, 0.47, 0.46, "HIGH");
        ReflectionTestUtils.invokeMethod(
                service,
                "persistTargetV2Assessments",
                "CASE_EVIDENCE_AGENT",
                accessSession,
                List.of(attached.getId()),
                result,
                "target-evidence-run:v2-projection",
                "TRACE_V2_PROJECTION");

        ArgumentCaptor<EvidenceVerificationEntity> saved =
                ArgumentCaptor.forClass(EvidenceVerificationEntity.class);
        verify(verificationRepository).save(saved.capture());
        JsonNode findings = objectMapper.readTree(saved.getValue().getAgentFindingsJson());
        JsonNode reasons = objectMapper.readTree(saved.getValue().getReasonsJson());
        assertThat(findings.path("schema_version").asText())
                .isEqualTo("evidence-turn-result.v3");
        assertThat(findings.path("assessment_public_text").asText())
                .isEqualTo("材料文本可读，但缺少签收人身份和交付照片。");
        assertThat(findings.path("verification_feedback").asText())
                .isEqualTo(findings.path("assessment_public_text").asText());
        assertThat(findings.path("authenticity_score").doubleValue()).isEqualTo(0.49);
        assertThat(findings.path("relevance_score").doubleValue()).isEqualTo(0.48);
        assertThat(findings.path("completeness_score").doubleValue()).isEqualTo(0.47);
        assertThat(findings.path("assessment_confidence").doubleValue()).isEqualTo(0.46);
        assertThat(findings.path("risk_level").asText()).isEqualTo("HIGH");
        assertThat(findings.has("human_review")).isFalse();
        assertThat(findings.has("review_target")).isFalse();
        assertThat(findings.has("review_instruction")).isFalse();
        assertThat(reasons.path("summary").asText())
                .isEqualTo("材料文本可读，但缺少签收人身份和交付照片。");
        assertThat(reasons.path("reason_details").findValuesAsText("code"))
                .containsExactly(
                        "LOW_AUTHENTICITY_SUSPECTED_FORGERY",
                        "LOW_RELEVANCE_SCORE",
                        "LOW_COMPLETENESS_SCORE",
                        "LOW_ASSESSMENT_CONFIDENCE",
                        "HIGH_RISK_FLAG");
        assertThat(reasons.path("reason_details").findValuesAsText("explanation"))
                .containsExactly(
                        "真实性解释：缺少原始导出。",
                        "关联性解释：仅部分对应待证事实。",
                        "完整性解释：缺少关键上下文。",
                        "置信度解释：可读取信息有限。",
                        "综合风险解释：存在必须人工确认的重大风险。");
        assertThat(saved.getValue().getVerificationStatus())
                .isEqualTo(EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW);
        assertThat(saved.getValue().isRequiresHumanReview()).isTrue();
    }

    @Test
    void targetV3ScoresAtHalfDoNotTriggerReviewAndMediumRiskStaysSuspicious() {
        EvidenceItemEntity attached =
                evidenceItem("EVIDENCE_V2_PENDING", "USER", "user-local", "PARTIES");
        CaseAccessSessionEntity accessSession = mock(CaseAccessSessionEntity.class);
        when(accessSession.privileged()).thenReturn(false);
        when(accessSession.getActorRole()).thenReturn(ActorRole.USER);
        when(accessSession.getActorId()).thenReturn("user-local");
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_EVIDENCE_AGENT"))
                .thenReturn(List.of(attached));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        attached.getId()))
                .thenReturn(Optional.empty());
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(
                service,
                "persistTargetV2Assessments",
                "CASE_EVIDENCE_AGENT",
                accessSession,
                List.of(attached.getId()),
                targetV3AssessmentResult(attached.getId(), 0.50, 0.50, 0.50, 0.50, "MEDIUM"),
                "target-evidence-run:v2-pending",
                "TRACE_V2_PENDING");

        ArgumentCaptor<EvidenceVerificationEntity> saved =
                ArgumentCaptor.forClass(EvidenceVerificationEntity.class);
        verify(verificationRepository).save(saved.capture());
        assertThat(saved.getValue().getVerificationStatus())
                .isEqualTo(EvidenceVerificationStatus.SUSPICIOUS);
        assertThat(saved.getValue().isRequiresHumanReview()).isFalse();
        assertThat(objectMapper.valueToTree(saved.getValue().getReasonsJson()).toString())
                .doesNotContain("review_target", "review_instruction", "HUMAN_REVIEW_TASK");
    }

    @Test
    void targetV3HighRiskAloneRequiresReview() throws Exception {
        EvidenceItemEntity attached =
                evidenceItem("EVIDENCE_V3_HIGH_RISK", "USER", "user-local", "PARTIES");
        CaseAccessSessionEntity accessSession = mock(CaseAccessSessionEntity.class);
        when(accessSession.privileged()).thenReturn(false);
        when(accessSession.getActorRole()).thenReturn(ActorRole.USER);
        when(accessSession.getActorId()).thenReturn("user-local");
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_EVIDENCE_AGENT"))
                .thenReturn(List.of(attached));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        attached.getId()))
                .thenReturn(Optional.empty());
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(
                service,
                "persistTargetV2Assessments",
                "CASE_EVIDENCE_AGENT",
                accessSession,
                List.of(attached.getId()),
                targetV3AssessmentResult(attached.getId(), 0.88, 0.91, 0.79, 0.84, "HIGH"),
                "target-evidence-run:v3-high-risk",
                "TRACE_V3_HIGH_RISK");

        ArgumentCaptor<EvidenceVerificationEntity> saved =
                ArgumentCaptor.forClass(EvidenceVerificationEntity.class);
        verify(verificationRepository).save(saved.capture());
        JsonNode reasons = objectMapper.readTree(saved.getValue().getReasonsJson());
        assertThat(saved.getValue().getVerificationStatus())
                .isEqualTo(EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW);
        assertThat(saved.getValue().isRequiresHumanReview()).isTrue();
        assertThat(reasons.path("reason_details").findValuesAsText("code"))
                .containsExactly("HIGH_RISK_FLAG");
        assertThat(reasons.path("reason_details").get(0).path("explanation").asText())
                .isEqualTo("综合风险解释：存在必须人工确认的重大风险。");
    }

    @Test
    void targetV3MissingAssessmentFieldsRemainNullAndTriggerNeutralReview() throws Exception {
        EvidenceItemEntity attached =
                evidenceItem("EVIDENCE_V3_INCOMPLETE", "USER", "user-local", "PARTIES");
        CaseAccessSessionEntity accessSession = mock(CaseAccessSessionEntity.class);
        when(accessSession.privileged()).thenReturn(false);
        when(accessSession.getActorRole()).thenReturn(ActorRole.USER);
        when(accessSession.getActorId()).thenReturn("user-local");
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                "CASE_EVIDENCE_AGENT"))
                .thenReturn(List.of(attached));
        when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
                        attached.getId()))
                .thenReturn(Optional.empty());
        when(verificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(
                service,
                "persistTargetV2Assessments",
                "CASE_EVIDENCE_AGENT",
                accessSession,
                List.of(attached.getId()),
                targetV3AssessmentResult(attached.getId(), null, null, null, null, null),
                "target-evidence-run:v3-incomplete",
                "TRACE_V3_INCOMPLETE");

        ArgumentCaptor<EvidenceVerificationEntity> saved =
                ArgumentCaptor.forClass(EvidenceVerificationEntity.class);
        verify(verificationRepository).save(saved.capture());
        JsonNode findings = objectMapper.readTree(saved.getValue().getAgentFindingsJson());
        JsonNode reasons = objectMapper.readTree(saved.getValue().getReasonsJson());
        assertThat(findings.path("authenticity_score").isNull()).isTrue();
        assertThat(findings.path("relevance_score").isNull()).isTrue();
        assertThat(findings.path("completeness_score").isNull()).isTrue();
        assertThat(findings.path("assessment_confidence").isNull()).isTrue();
        assertThat(findings.path("risk_level").isNull()).isTrue();
        assertThat(findings.path("assessment_complete").asBoolean()).isFalse();
        assertThat(reasons.path("reason_details").findValuesAsText("code"))
                .containsExactly("ASSESSMENT_INCOMPLETE");
        assertThat(reasons.path("reason_details").get(0).path("label").asText())
                .isEqualTo("模型核验字段不完整，需人工复核");
        assertThat(reasons.toString())
                .doesNotContain(
                        "LOW_AUTHENTICITY_SUSPECTED_FORGERY",
                        "LOW_RELEVANCE_SCORE",
                        "LOW_COMPLETENESS_SCORE",
                        "LOW_ASSESSMENT_CONFIDENCE");
        assertThat(saved.getValue().getVerificationStatus())
                .isEqualTo(EvidenceVerificationStatus.NEEDS_HUMAN_REVIEW);
        assertThat(saved.getValue().isRequiresHumanReview()).isTrue();
    }

    @Test
    void terminalNoCommitOpeningAdvancesDeterministicGenerationsAndRejectsDriftBeforeAllocation()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        FrozenSubmissionFixture frozen =
                frozenSubmissionFixture(dispute.getId(), new AtomicLong(0));
        String tenantSurrogate = "tenant-run001";
        long processRevision = 31;
        long roomRevision = 41;
        long roomEpoch = 0;
        long fencingToken = 23;
        AtomicLong currentProcessRevision = new AtomicLong(processRevision);
        AtomicLong currentRoomRevision = new AtomicLong(roomRevision);

        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        when(epoch.getTenantSurrogate()).thenReturn(tenantSurrogate);
        when(epoch.getCaseId()).thenReturn(dispute.getId());
        when(epoch.getRoomId()).thenReturn(room.getId());
        when(epoch.getRoomType())
                .thenReturn(
                        com.example.dispute.workflow.contract.v1.ContractTypes.RoomType
                                .EVIDENCE);
        when(epoch.getRoomEpoch()).thenReturn(roomEpoch);
        when(epoch.getFencingToken()).thenReturn(fencingToken);
        when(epoch.getProcessRevision())
                .thenAnswer(ignored -> currentProcessRevision.get());
        when(epoch.getRoomRevision()).thenAnswer(ignored -> currentRoomRevision.get());
        when(epoch.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(epoch.getGraphKey()).thenReturn(TargetTypedRoomProtocol.GRAPH_KEY);

        when(frozen.projection().getMacroPhase()).thenReturn("EVIDENCE_OPEN");
        when(frozen.projection().getRoomPhase()).thenReturn("OPEN");
        when(frozen.projection().getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(frozen.projection().getProcessRevision())
                .thenAnswer(ignored -> currentProcessRevision.get());
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(processProjectionRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of());

        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        when(epochRepository
                        .findTopByCaseIdAndRoomTypeAndLifecycleStatusOrderByRoomEpochDesc(
                                dispute.getId(),
                                com.example.dispute.workflow.contract.v1.ContractTypes.RoomType
                                        .EVIDENCE,
                                EpochLifecycleStatus.ACTIVE))
                .thenReturn(Optional.of(epoch));
        when(epochRepository.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        dispute.getId(),
                        com.example.dispute.workflow.contract.v1.ContractTypes.RoomType
                                .EVIDENCE,
                        roomEpoch))
                .thenReturn(Optional.of(epoch));

        Map<String, CaseCommandEntity> durableCommands = new HashMap<>();
        Map<String, TargetEvidenceCommandMaterialStore.MaterialSnapshot> durableMaterials =
                new HashMap<>();
        Map<String, RoomMessageEntity> committedOpenings = new HashMap<>();
        Map<String, String> openingIdempotencyKeys = new HashMap<>();
        Map<String, String> logicalRuns = new HashMap<>();
        Map<String, String> rootAttempts = new HashMap<>();
        Map<String, Integer> commandAllocations = new HashMap<>();
        Map<String, Integer> materialAllocations = new HashMap<>();
        AtomicLong commandSequence = new AtomicLong();
        AtomicLong commandAcceptCalls = new AtomicLong();
        AtomicLong materializeCalls = new AtomicLong();

        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenAnswer(
                        invocation ->
                                Optional.ofNullable(
                                        committedOpenings.get(invocation.getArgument(1))));

        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(any(), any()))
                .thenAnswer(
                        invocation ->
                                Optional.ofNullable(
                                        durableCommands.get(invocation.getArgument(1))));

        CaseCommandService caseCommands = mock(CaseCommandService.class);
        when(caseCommands.accept(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            commandAcceptCalls.incrementAndGet();
                            String acceptedCaseId = invocation.getArgument(0);
                            String commandId = invocation.getArgument(1);
                            AcceptCaseCommand command = invocation.getArgument(2);
                            AuthenticatedActor acceptedActor = invocation.getArgument(3);
                            CaseCommandEntity stored = durableCommands.get(commandId);
                            boolean replay = stored != null;
                            if (!replay) {
                                long sequence = commandSequence.incrementAndGet();
                                List<String> scopes =
                                        List.of(
                                                "case:"
                                                        + acceptedCaseId
                                                        + ":command:EVIDENCE_OPENING");
                                ActorRef actorRef =
                                        new ActorRef(
                                                acceptedActor.actorId(),
                                                com.example.dispute.workflow.contract.v1
                                                        .ContractTypes.ActorRole.valueOf(
                                                        acceptedActor.role().name()),
                                                scopes);
                                String requestHash =
                                        CaseCommandRequestHasher.hash(
                                                tenantSurrogate,
                                                acceptedCaseId,
                                                commandId,
                                                command,
                                                actorRef);
                                OffsetDateTime acceptedAt =
                                        OffsetDateTime.ofInstant(
                                                CLOCK.instant().plusSeconds(sequence),
                                                ZoneOffset.UTC);
                                CaseCommandRef reference =
                                        new CaseCommandRef(
                                                "case-command-ref.v1",
                                                commandId,
                                                tenantSurrogate,
                                                acceptedCaseId,
                                                sequence,
                                                command.commandType(),
                                                command.roomType(),
                                                command.roomEpoch(),
                                                actorRef,
                                                command.payloadRef(),
                                                command.expectedProcessRevision(),
                                                acceptedAt.toInstant(),
                                                command.deadlineAt(),
                                                "00-"
                                                        + "1".repeat(32)
                                                        + "-"
                                                        + "2".repeat(16)
                                                        + "-01",
                                                requestHash);
                                stored =
                                        CaseCommandEntity.pending(
                                                "CMD_OPENING_" + sequence,
                                                reference,
                                                objectMapper.writeValueAsString(scopes),
                                                acceptedAt);
                                durableCommands.put(commandId, stored);
                                commandAllocations.merge(commandId, 1, Integer::sum);
                            }
                            return new CaseCommandAcceptance(
                                    CaseCommandReferenceMapper.fromEntity(stored, objectMapper),
                                    stored.getCommandStatus().name(),
                                    stored.getAcceptedAt().toInstant(),
                                    replay);
                        });

        TargetEvidenceCommandMaterialStore materialStore =
                mock(TargetEvidenceCommandMaterialStore.class);
        when(materialStore.readByRoute(any()))
                .thenAnswer(
                        invocation -> {
                            TargetEvidenceCommandMaterialStore.CommandLookup lookup =
                                    invocation.getArgument(0);
                            return Optional.ofNullable(durableMaterials.get(lookup.commandId()));
                        });

        TargetRoomCommandIngress targetCommandIngress = mock(TargetRoomCommandIngress.class);
        when(targetCommandIngress.materializeEvidenceOpening(
                        any(), any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            materializeCalls.incrementAndGet();
                            String materialCaseId = invocation.getArgument(0);
                            String commandId = invocation.getArgument(1);
                            AcceptCaseCommand command = invocation.getArgument(2);
                            AuthenticatedActor materialActor = invocation.getArgument(3);
                            EvidenceAgentTurnCommand turn = invocation.getArgument(5);
                            String logicalRunId =
                                    "target-evidence-run:" + stableOpeningToken(commandId);
                            String rootAttemptId = logicalRunId + ":1";
                            List<String> capabilities =
                                    List.of(
                                            "case:"
                                                    + materialCaseId
                                                    + ":command:EVIDENCE_OPENING");
                            com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole
                                    contractActorRole =
                                            com.example.dispute.workflow.contract.v1.ContractTypes
                                                    .ActorRole.valueOf(
                                                    materialActor.role().name());
                            ActorRef caseActorRef =
                                    new ActorRef(
                                            materialActor.actorId(),
                                            contractActorRole,
                                            capabilities);
                            String caseCommandRequestHash =
                                    CaseCommandRequestHasher.hash(
                                            tenantSurrogate,
                                            materialCaseId,
                                            commandId,
                                            command,
                                            caseActorRef);
                            RoomGraphCommand.ActorScope actorScope =
                                    new RoomGraphCommand.ActorScope(
                                            materialActor.actorId(),
                                            contractActorRole,
                                            Audience.valueOf(materialActor.role().name()),
                                            capabilities);
                            RoomGraphCommand.SnapshotRef domainSnapshot =
                                    new RoomGraphCommand.SnapshotRef(
                                            "case:" + materialCaseId,
                                            "case-snapshot.v1",
                                            "urn:test:case:" + materialCaseId,
                                            "a".repeat(64),
                                            1);
                            RoomGraphCommand.SnapshotRef eventRef =
                                    new RoomGraphCommand.SnapshotRef(
                                            "opening:" + commandId,
                                            command.payloadRef().schemaVersion(),
                                            command.payloadRef().uri(),
                                            command.payloadRef().sha256(),
                                            command.payloadRef().sizeBytes());
                            RoomGraphCommand graphCommand =
                                    new RoomGraphCommand(
                                            "room-graph-command.v1",
                                            commandId,
                                            logicalRunId,
                                            rootAttemptId,
                                            tenantSurrogate,
                                            materialCaseId,
                                            com.example.dispute.workflow.contract.v1.ContractTypes
                                                    .RoomType.EVIDENCE,
                                            command.roomEpoch(),
                                            TargetTypedRoomProtocol.GRAPH_KEY,
                                            "target-evidence-opening.v1",
                                            "target-evidence-opening-checkpoint.v1",
                                            "evidence-thread:" + materialActor.actorId(),
                                            actorScope,
                                            command.expectedProcessRevision(),
                                            "EVIDENCE_OPENING",
                                            1,
                                            domainSnapshot,
                                            eventRef,
                                            new RoomGraphCommand.InvocationContext(
                                                    "evidence-clerk",
                                                    "evidence-opening",
                                                    "model-test",
                                                    "evidence-turn.v1",
                                                    "policy-test",
                                                    "guardrail-test",
                                                    List.of(),
                                                    "key-test",
                                                    "nonce-test"),
                                            new RoomGraphCommand.RetryBudget(1, 1, 0),
                                            command.deadlineAt(),
                                            "00-"
                                                    + "3".repeat(32)
                                                    + "-"
                                                    + "4".repeat(16)
                                                    + "-01",
                                            "b".repeat(64));
                            ExecuteAgentRunRequest request =
                                    new ExecuteAgentRunRequest(
                                            ExecuteAgentRunRequest.SCHEMA_VERSION,
                                            logicalRunId,
                                            1,
                                            "agent-stream.v3",
                                            "c".repeat(64),
                                            null,
                                            false,
                                            0,
                                            graphCommand);
                            TargetEvidenceCommandMaterial material =
                                    new TargetEvidenceCommandMaterial(
                                            TargetEvidenceCommandMaterial.SCHEMA_VERSION,
                                            TargetEvidenceCommandMaterial.TARGET_LANE,
                                            "p9act.v1.test",
                                            "d".repeat(64),
                                            fencingToken,
                                            command.expectedProcessRevision(),
                                            roomRevision,
                                            "e".repeat(64),
                                            "f".repeat(64),
                                            caseCommandRequestHash,
                                            request,
                                            turn);
                            CommandAdmission admission =
                                    new CommandAdmission(
                                            "p9act.v1.test",
                                            "d".repeat(64),
                                            "domain-db-test",
                                            tenantSurrogate,
                                            materialCaseId,
                                            commandId,
                                            "e".repeat(64),
                                            "f".repeat(64),
                                            command.roomEpoch(),
                                            fencingToken);
                            durableMaterials.put(
                                    commandId,
                                    new TargetEvidenceCommandMaterialStore.MaterialSnapshot(
                                            "ADMISSION_" + stableOpeningToken(commandId),
                                            admission,
                                            material,
                                            "2".repeat(64),
                                            CLOCK.instant()));
                            openingIdempotencyKeys.put(
                                    commandId,
                                    turn.contextEnvelope().currentEvent().eventId());
                            logicalRuns.put(commandId, logicalRunId);
                            rootAttempts.put(commandId, rootAttemptId);
                            materialAllocations.merge(commandId, 1, Integer::sum);
                            return new TargetRoomCommandIngress.EvidenceOpeningRunReceipt(
                                    logicalRunId, rootAttemptId);
                        });

        @SuppressWarnings("unchecked")
        ObjectProvider<TargetRoomCommandIngress> targetIngressProvider =
                mock(ObjectProvider.class);
        when(targetIngressProvider.getIfUnique()).thenReturn(targetCommandIngress);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetEvidenceCommandMaterialStore> targetMaterialProvider =
                mock(ObjectProvider.class);
        when(targetMaterialProvider.getIfUnique()).thenReturn(materialStore);

        TargetEvidenceOpeningIngress opening =
                new TargetEvidenceOpeningIngress(
                        caseRepository,
                        epochRepository,
                        processProjectionRepository,
                        service,
                        caseCommands,
                        commandRepository,
                        targetIngressProvider,
                        targetMaterialProvider,
                        objectMapper,
                        CLOCK);

        AuthenticatedActor merchant =
                new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        String merchantBaseCommandId =
                generationZeroOpeningCommandId(
                        tenantSurrogate, dispute.getId(), roomEpoch, merchant);
        AgentRunAcceptedView merchantPending =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(), merchant, "TRACE_MERCHANT_PENDING", "REQ_MERCHANT_PENDING");
        AgentRunAcceptedView merchantPendingReplay =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(),
                                merchant,
                                "TRACE_MERCHANT_PENDING_REPLAY",
                                "REQ_MERCHANT_PENDING_REPLAY");
        assertThat(merchantPendingReplay).isEqualTo(merchantPending);
        assertThat(durableCommands).containsKey(merchantBaseCommandId);
        assertThat(materialAllocations).containsEntry(merchantBaseCommandId, 1);
        assertThat(commandAllocations).containsEntry(merchantBaseCommandId, 1);

        CaseCommandEntity merchantCommand = durableCommands.get(merchantBaseCommandId);
        String merchantOpeningKey = openingIdempotencyKeys.get(merchantBaseCommandId);
        RoomMessageEntity committedMerchantOpening =
                RoomMessageEntity.create(
                        "MESSAGE_TARGET_OPENING_COMMITTED",
                        dispute.getId(),
                        room.getId(),
                        11,
                        com.example.dispute.room.domain.MessageSenderType.AGENT,
                        "EVIDENCE_CLERK",
                        "evidence-clerk",
                        "[\"MERCHANT\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[\"merchant-local\"]",
                        MessageSource.AGENT_LLM,
                        MessageType.AGENT_MESSAGE,
                        "Committed Evidence opening.",
                        "[]",
                        merchantOpeningKey,
                        CLOCK.instant(),
                        "TRACE_MERCHANT_COMMITTED");
        committedMerchantOpening.attachAgentRun(logicalRuns.get(merchantBaseCommandId));
        merchantCommand.markApplied(
                "urn:target-e2e:evidence-formal-message:"
                        + committedMerchantOpening.getId(),
                "3".repeat(64),
                OffsetDateTime.ofInstant(CLOCK.instant().plusSeconds(20), ZoneOffset.UTC));
        committedOpenings.put(merchantOpeningKey, committedMerchantOpening);
        currentProcessRevision.set(processRevision + 1);
        currentRoomRevision.set(roomRevision + 1);
        long acceptCallsBeforeCommittedReplay = commandAcceptCalls.get();
        long materializeCallsBeforeCommittedReplay = materializeCalls.get();
        Object merchantSuccess =
                opening.open(
                        dispute.getId(), merchant, "TRACE_MERCHANT_SUCCESS", "REQ_MERCHANT_SUCCESS");
        Object merchantSuccessReplay =
                opening.open(
                        dispute.getId(),
                        merchant,
                        "TRACE_MERCHANT_SUCCESS_REPLAY",
                                "REQ_MERCHANT_SUCCESS_REPLAY");
        assertThat(merchantSuccess).isEqualTo(merchantSuccessReplay);
        assertThat(merchantSuccess).isInstanceOf(RoomMessageView.class);
        assertThat(((RoomMessageView) merchantSuccess).id())
                .isEqualTo(committedMerchantOpening.getId());
        assertThat(commandAcceptCalls.get()).isEqualTo(acceptCallsBeforeCommittedReplay);
        assertThat(materializeCalls.get()).isEqualTo(materializeCallsBeforeCommittedReplay);
        assertThat(materialAllocations).containsEntry(merchantBaseCommandId, 1);
        assertThat(commandAllocations).containsEntry(merchantBaseCommandId, 1);

        TargetEvidenceCommandMaterialStore.MaterialSnapshot committedMaterial =
                durableMaterials.remove(merchantBaseCommandId);
        assertThatThrownBy(
                        () ->
                                opening.open(
                                        dispute.getId(),
                                        merchant,
                                        "TRACE_MERCHANT_MISSING_MATERIAL",
                                        "REQ_MERCHANT_MISSING_MATERIAL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing its immutable material");
        assertThat(commandAcceptCalls.get()).isEqualTo(acceptCallsBeforeCommittedReplay);
        assertThat(materializeCalls.get()).isEqualTo(materializeCallsBeforeCommittedReplay);
        durableMaterials.put(merchantBaseCommandId, committedMaterial);

        currentProcessRevision.set(processRevision + 2);
        currentRoomRevision.set(roomRevision + 2);
        assertThatThrownBy(
                        () ->
                                opening.open(
                                        dispute.getId(),
                                        merchant,
                                        "TRACE_MERCHANT_NON_UNIT_REVISION",
                                        "REQ_MERCHANT_NON_UNIT_REVISION"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact formal-commit successor");
        assertThat(commandAcceptCalls.get()).isEqualTo(acceptCallsBeforeCommittedReplay);
        assertThat(materializeCalls.get()).isEqualTo(materializeCallsBeforeCommittedReplay);

        currentProcessRevision.set(processRevision);
        currentRoomRevision.set(roomRevision);

        AuthenticatedActor user = new AuthenticatedActor("user-local", ActorRole.USER);
        String generationZeroId =
                generationZeroOpeningCommandId(
                        tenantSurrogate, dispute.getId(), roomEpoch, user);
        AgentRunAcceptedView generationZero =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(), user, "TRACE_GENERATION_0", "REQ_GENERATION_0");
        AgentRunAcceptedView generationZeroReplay =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(),
                                user,
                                "TRACE_GENERATION_0_REPLAY",
                                "REQ_GENERATION_0_REPLAY");
        assertThat(generationZeroReplay).isEqualTo(generationZero);
        assertThat(generationZero.runId()).isEqualTo(logicalRuns.get(generationZeroId));
        assertThat(rootAttempts.get(generationZeroId))
                .isEqualTo(generationZero.runId() + ":1");

        CaseCommandEntity generationZeroCommand = durableCommands.get(generationZeroId);
        String generationZeroReceiptHash = "4".repeat(64);
        OffsetDateTime generationZeroExpiredAt =
                markRecoveredExpiredTerminalNoCommit(
                        generationZeroCommand, generationZeroReceiptHash);
        assertThat(generationZeroCommand.getCommandStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(generationZeroCommand.getStatusReasonCode())
                .isEqualTo("TARGET_EVIDENCE_TERMINAL_NO_COMMIT");
        assertThat(generationZeroCommand.getResultSha256())
                .isEqualTo(generationZeroReceiptHash);
        assertThat(generationZeroCommand.getUpdatedAt()).isEqualTo(generationZeroExpiredAt);
        String generationOneId =
                successorOpeningCommandId(
                        generationZeroId,
                        generationZeroCommand,
                        generationZeroReceiptHash);
        AgentRunAcceptedView generationOne =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(), user, "TRACE_GENERATION_1", "REQ_GENERATION_1");
        AgentRunAcceptedView generationOneReplay =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(),
                                user,
                                "TRACE_GENERATION_1_REPLAY",
                                "REQ_GENERATION_1_REPLAY");
        assertThat(generationOneReplay).isEqualTo(generationOne);
        assertThat(generationOne.runId()).isEqualTo(logicalRuns.get(generationOneId));
        assertThat(rootAttempts.get(generationOneId))
                .isEqualTo(generationOne.runId() + ":1");
        assertThat(generationOne.runId()).isNotEqualTo(generationZero.runId());
        assertThat(durableCommands.get(generationZeroId).getCommandStatus())
                .isEqualTo(CommandStatus.FAILED);
        assertThat(materialAllocations).containsEntry(generationZeroId, 1);
        assertThat(commandAllocations).containsEntry(generationZeroId, 1);
        assertThat(materialAllocations).containsEntry(generationOneId, 1);
        assertThat(commandAllocations).containsEntry(generationOneId, 1);

        CaseCommandEntity generationOneCommand = durableCommands.get(generationOneId);
        String generationOneReceiptHash = "5".repeat(64);
        markTerminalNoCommit(generationOneCommand, generationOneReceiptHash, 40);
        String generationTwoId =
                successorOpeningCommandId(
                        generationZeroId,
                        generationOneCommand,
                        generationOneReceiptHash);
        AgentRunAcceptedView generationTwo =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(), user, "TRACE_GENERATION_2", "REQ_GENERATION_2");
        AgentRunAcceptedView generationTwoReplay =
                (AgentRunAcceptedView)
                        opening.open(
                                dispute.getId(),
                                user,
                                "TRACE_GENERATION_2_REPLAY",
                                "REQ_GENERATION_2_REPLAY");
        assertThat(generationTwoReplay).isEqualTo(generationTwo);
        assertThat(generationTwo.runId()).isEqualTo(logicalRuns.get(generationTwoId));
        assertThat(rootAttempts.get(generationTwoId))
                .isEqualTo(generationTwo.runId() + ":1");
        assertThat(generationTwo.runId())
                .isNotIn(generationZero.runId(), generationOne.runId());
        assertThat(materialAllocations).containsEntry(generationZeroId, 1);
        assertThat(commandAllocations).containsEntry(generationZeroId, 1);
        assertThat(materialAllocations).containsEntry(generationOneId, 1);
        assertThat(commandAllocations).containsEntry(generationOneId, 1);
        assertThat(materialAllocations).containsEntry(generationTwoId, 1);
        assertThat(commandAllocations).containsEntry(generationTwoId, 1);

        record InvalidGeneration(
                String label, Consumer<CaseCommandEntity> corruption) {}
        List<InvalidGeneration> invalidGenerations =
                List.of(
                        new InvalidGeneration(
                                "foreign tenant",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command, "tenantSurrogate", "tenant-foreign")),
                        new InvalidGeneration(
                                "foreign case",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command, "caseId", "CASE_FOREIGN")),
                        new InvalidGeneration(
                                "wrong command type",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command,
                                                "commandType",
                                                CommandType.EVIDENCE_SUBMIT)),
                        new InvalidGeneration(
                                "foreign actor",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command, "actorId", "user-foreign")),
                        new InvalidGeneration(
                                "foreign epoch",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command, "roomEpoch", roomEpoch + 1)),
                        new InvalidGeneration(
                                "frozen pair drift",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command, "payloadSha256", "6".repeat(64))),
                        new InvalidGeneration(
                                "malformed terminal receipt URI",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command,
                                                "resultUri",
                                                "urn:target-room-agent-run-terminal-no-commit:foreign")),
                        new InvalidGeneration(
                                "malformed terminal receipt hash",
                                command -> {
                                    ReflectionTestUtils.setField(
                                            command, "resultSha256", "not-a-sha256");
                                    ReflectionTestUtils.setField(
                                            command,
                                            "resultUri",
                                            "urn:target-room-agent-run-terminal-no-commit:not-a-sha256");
                                }),
                        new InvalidGeneration(
                                "missing terminal discriminator",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command, "statusReasonCode", null)),
                        new InvalidGeneration(
                                "unsupported nonterminal result",
                                command ->
                                        ReflectionTestUtils.setField(
                                                command,
                                                "commandStatus",
                                                CommandStatus.SHADOW_COMPLETED)),
                        new InvalidGeneration(
                                "applied opening without committed message",
                                command -> {
                                    ReflectionTestUtils.setField(
                                            command, "commandStatus", CommandStatus.APPLIED);
                                    ReflectionTestUtils.setField(
                                            command,
                                            "appliedAt",
                                            OffsetDateTime.ofInstant(
                                                    CLOCK.instant().plusSeconds(50),
                                                    ZoneOffset.UTC));
                                }));

        int allocationCountBeforeDrift =
                commandAllocations.values().stream().mapToInt(Integer::intValue).sum();
        int materialCountBeforeDrift =
                materialAllocations.values().stream().mapToInt(Integer::intValue).sum();
        for (InvalidGeneration invalidGeneration : invalidGenerations) {
            CaseCommandEntity invalid =
                    copyOpeningCommand(generationZeroCommand, invalidGeneration.label());
            markTerminalNoCommit(invalid, "7".repeat(64), 60);
            invalidGeneration.corruption().accept(invalid);
            durableCommands.clear();
            durableMaterials.clear();
            durableCommands.put(generationZeroId, invalid);

            assertThatThrownBy(
                            () ->
                                    opening.open(
                                            dispute.getId(),
                                            user,
                                            "TRACE_INVALID_" + invalidGeneration.label(),
                                            "REQ_INVALID_" + invalidGeneration.label()))
                    .as(invalidGeneration.label())
                    .isInstanceOf(IllegalStateException.class);
            assertThat(
                            commandAllocations.values().stream()
                                    .mapToInt(Integer::intValue)
                                    .sum())
                    .as(invalidGeneration.label())
                    .isEqualTo(allocationCountBeforeDrift);
            assertThat(
                            materialAllocations.values().stream()
                                    .mapToInt(Integer::intValue)
                                    .sum())
                    .as(invalidGeneration.label())
                    .isEqualTo(materialCountBeforeDrift);
        }

        verify(client, never()).run(any(), any(), any());
        verify(messageRepository, never()).save(any());
        verify(memoryRepository, never()).save(any());
    }

    @Test
    void frozenOpeningReplaysExactlyAndHigherEpochStartsOneFreshProviderRun()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        AtomicLong evidenceEpoch = new AtomicLong(2);
        FrozenSubmissionFixture frozen = frozenSubmissionFixture(dispute.getId(), evidenceEpoch);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));
        RoomMessageEntity priorEpochMessage =
                RoomMessageEntity.create(
                        "MESSAGE_PRIOR_EVIDENCE_EPOCH",
                        dispute.getId(),
                        room.getId(),
                        7,
                        com.example.dispute.room.domain.MessageSenderType.AGENT,
                        "CUSTOMER_SERVICE",
                        "evidence-clerk",
                        "[\"USER\",\"CUSTOMER_SERVICE\",\"PLATFORM_REVIEWER\",\"ADMIN\",\"SYSTEM\"]",
                        "[\"user-local\"]",
                        MessageType.AGENT_MESSAGE,
                        "Prior Evidence epoch opening remains immutable audit data.",
                        "[]",
                        "agent-evidence-opening:prior-epoch",
                        Instant.parse("2026-07-05T23:00:00Z"),
                        "TRACE_PRIOR_EPOCH");
        when(messageRepository.findAllByRoomIdOrderBySequenceNoAsc(room.getId()))
                .thenReturn(List.of(priorEpochMessage));
        Map<String, RoomMessageEntity> storedOpenings = new HashMap<>();
        when(messageRepository.findByCaseIdAndIdempotencyKey(eq(dispute.getId()), any()))
                .thenAnswer(
                        invocation ->
                                Optional.ofNullable(
                                        storedOpenings.get(invocation.getArgument(1))));
        when(messageRepository.findMaxSequenceByRoomId(room.getId())).thenReturn(8L, 9L);
        when(messageRepository.save(any()))
                .thenAnswer(
                        invocation -> {
                            RoomMessageEntity saved = invocation.getArgument(0);
                            storedOpenings.put(saved.getIdempotencyKey(), saved);
                            return saved;
                        });
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(memoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of());
        when(client.run(any(), any(), any()))
                .thenReturn(
                        new EvidenceAgentTurnResult(
                                "Freeze-bound Evidence opening.",
                                objectMapper.createObjectNode(),
                                objectMapper.createArrayNode(),
                                List.of(),
                                false,
                                false,
                                "LLM",
                                0.91));

        var first =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_FROZEN_EPOCH_2",
                        "REQ_FROZEN_EPOCH_2");
        var replay =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_FROZEN_EPOCH_2_REPLAY",
                        "REQ_FROZEN_EPOCH_2_REPLAY");
        evidenceEpoch.set(3);
        var nextEpoch =
                service.ensureOpening(
                        dispute.getId(),
                        RoomType.EVIDENCE,
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        "TRACE_FROZEN_EPOCH_3",
                        "REQ_FROZEN_EPOCH_3");

        ArgumentCaptor<EvidenceAgentTurnCommand> commands =
                ArgumentCaptor.forClass(EvidenceAgentTurnCommand.class);
        verify(client, times(2)).run(commands.capture(), any(), any());
        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(nextEpoch.id()).isNotEqualTo(first.id());
        assertThat(storedOpenings).hasSize(2);
        assertThat(storedOpenings.keySet())
                .allMatch(key -> key.startsWith("agent-evidence-opening:freeze-v1:"));
        assertThat(commands.getAllValues())
                .extracting(
                        command ->
                                command.contextEnvelope()
                                        .frozenSubmission()
                                        .evidenceRoomEpoch())
                .containsExactly(2L, 3L);
        EvidenceContextEnvelopeV1 envelope =
                commands.getAllValues().getFirst().contextEnvelope();
        assertThat(envelope.schemaVersion())
                .isEqualTo(EvidenceContextEnvelopeV1.FROZEN_SUBMISSION_SCHEMA_VERSION);
        assertThat(envelope.intakeDossierSnapshot()).isNull();
        assertThat(envelope.frozenSubmission().authority()).isEqualTo(frozen.authority());
        assertThat(envelope.frozenSubmission().matrix()).isEqualTo(frozen.matrix());
        verify(intakeDossierRepository, never())
                .findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE);
    }

    @Test
    void frozenOpeningRejectsHashDriftAndNeedMoreInfoEventBeforeProvider() throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        FrozenSubmissionFixture frozen =
                frozenSubmissionFixture(dispute.getId(), new AtomicLong(2));
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));
        when(frozen.projection().getProjectionSha256()).thenReturn("e".repeat(64));

        assertThatThrownBy(
                        () ->
                                service.ensureOpening(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        "TRACE_FROZEN_HASH_DRIFT",
                                        "REQ_FROZEN_HASH_DRIFT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen Evidence submission authority is invalid");

        when(frozen.projection().getProjectionSha256())
                .thenReturn(frozen.authority().matrixContentHash());
        ObjectNode needMoreInfo = frozen.eventDocument().deepCopy();
        needMoreInfo.put("event_type", "TURN_NEEDS_INPUT");
        needMoreInfo.remove("event_hash");
        needMoreInfo.put("event_hash", ContractJson.sha256Hex(needMoreInfo));
        when(frozen.event().getEventType()).thenReturn("TURN_NEEDS_INPUT");
        when(frozen.event().getEventJson())
                .thenReturn(objectMapper.writeValueAsString(needMoreInfo));

        assertThatThrownBy(
                        () ->
                                service.ensureOpening(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        "TRACE_NEED_MORE_INFO",
                                        "REQ_NEED_MORE_INFO"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen Evidence submission authority is invalid");
        verify(client, never()).run(any(), any(), any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void legacyParticipantResultIsRejectedWhenProjectionBecomesFreezeBoundDuringProvider()
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        FrozenSubmissionFixture frozen =
                frozenSubmissionFixture(dispute.getId(), new AtomicLong(2));
        AtomicBoolean paired = new AtomicBoolean(false);
        when(frozen.projection().getProjectionRef())
                .thenAnswer(
                        ignored -> paired.get() ? frozen.authority().projectionRef() : null);
        when(frozen.projection().getProjectionSha256())
                .thenAnswer(
                        ignored -> paired.get() ? frozen.authority().matrixContentHash() : null);
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));
        when(memoryRepository.findMaxTurnNoByAgentSessionId(any())).thenReturn(0);
        when(memoryRepository.findTop50ByAgentSessionIdOrderByTurnNoDesc(any()))
                .thenReturn(List.of());
        when(evidenceItemRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                dispute.getId()))
                .thenReturn(List.of());
        when(client.run(any(), eq("TRACE_LEGACY_DRIFT"), eq("REQ_LEGACY_DRIFT")))
                .thenAnswer(
                        ignored -> {
                            paired.set(true);
                            return safeOpeningResult("Stale legacy participant result.");
                        });

        assertThatThrownBy(
                        () ->
                                service.continueFromParticipantMessage(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        new RoomMessageCommand(
                                                MessageType.PARTY_TEXT,
                                                "Please review this evidence.",
                                                List.of()),
                                        "MESSAGE_LEGACY_DRIFT",
                                        CLOCK.instant(),
                                        "TRACE_LEGACY_DRIFT",
                                        "REQ_LEGACY_DRIFT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy Evidence submission became freeze-bound");

        verify(client, times(1)).run(any(), eq("TRACE_LEGACY_DRIFT"), eq("REQ_LEGACY_DRIFT"));
        verify(memoryRepository, times(1)).save(any());
        verify(verificationRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
    }

    @ParameterizedTest(name = "post-provider frozen projection drift: {0}")
    @MethodSource("freezeBoundProviderDrifts")
    void freezeBoundOpeningRejectsProjectionDriftAfterProviderBeforePersistence(String drift)
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        AtomicLong evidenceEpoch = new AtomicLong(2);
        FrozenSubmissionFixture frozen = frozenSubmissionFixture(dispute.getId(), evidenceEpoch);
        AtomicReference<String> projectionHash =
                new AtomicReference<>(frozen.authority().matrixContentHash());
        when(frozen.projection().getProjectionSha256())
                .thenAnswer(ignored -> projectionHash.get());
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));
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
        when(client.run(any(), eq("TRACE_FROZEN_PROVIDER_DRIFT"), any()))
                .thenAnswer(
                        ignored -> {
                            if ("epoch".equals(drift)) {
                                evidenceEpoch.set(3);
                            } else {
                                projectionHash.set("e".repeat(64));
                            }
                            return safeOpeningResult("Stale freeze-bound opening result.");
                        });

        assertThatThrownBy(
                        () ->
                                service.ensureOpening(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        "TRACE_FROZEN_PROVIDER_DRIFT",
                                        "REQ_FROZEN_PROVIDER_DRIFT_" + drift))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen Evidence submission");

        verify(client, times(1)).run(any(), eq("TRACE_FROZEN_PROVIDER_DRIFT"), any());
        verify(memoryRepository, never()).save(any());
        verify(messageRepository, never()).save(any());
    }

    private static Stream<Arguments> freezeBoundProviderDrifts() {
        return Stream.of(Arguments.of("epoch"), Arguments.of("hash"));
    }

    @Test
    void frozenOpeningRequiresRepositoryLookupByExactEventAndCaseBeforeProvider() {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        FrozenSubmissionFixture frozen =
                frozenSubmissionFixture(dispute.getId(), new AtomicLong(2));
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.empty());
        lenient()
                .when(timelineEventRepository.findById(frozen.authority().submitEventId()))
                .thenReturn(Optional.of(frozen.event()));

        assertThatThrownBy(
                        () ->
                                service.ensureOpening(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        "TRACE_FOREIGN_EVENT_CASE",
                                        "REQ_FOREIGN_EVENT_CASE"))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("frozen Submit event is missing");

        verify(timelineEventRepository, times(1))
                .findByIdAndCaseId(frozen.authority().submitEventId(), dispute.getId());
        verify(timelineEventRepository, never()).findById(frozen.authority().submitEventId());
        verify(client, never()).run(any(), any(), any());
    }

    @ParameterizedTest(name = "frozen result revision drift: {0}")
    @MethodSource("frozenResultRevisionFields")
    void frozenOpeningRejectsResultRevisionDriftBeforeProvider(String revisionField)
            throws Exception {
        FulfillmentCaseEntity dispute = evidenceCase();
        CaseRoomEntity room = evidenceRoom(dispute);
        FrozenSubmissionFixture frozen =
                frozenSubmissionFixture(dispute.getId(), new AtomicLong(2));
        ObjectNode driftedEvent = frozen.eventDocument().deepCopy();
        ObjectNode driftedResult = (ObjectNode) driftedEvent.required("result");
        driftedResult.put(
                revisionField, driftedResult.required(revisionField).asLong() + 1);
        driftedEvent.put("result_hash", ContractJson.sha256Hex(driftedResult));
        driftedEvent.remove("event_hash");
        driftedEvent.put("event_hash", ContractJson.sha256Hex(driftedEvent));
        when(frozen.event().getEventJson())
                .thenReturn(objectMapper.writeValueAsString(driftedEvent));
        when(caseRepository.findByIdForUpdate(dispute.getId()))
                .thenReturn(Optional.of(dispute));
        when(roomRepository.findByCaseIdAndRoomType(dispute.getId(), RoomType.EVIDENCE))
                .thenReturn(Optional.of(room));
        when(processProjectionRepository.findById(dispute.getId()))
                .thenReturn(Optional.of(frozen.projection()));
        when(timelineEventRepository.findByIdAndCaseId(
                        frozen.authority().submitEventId(), dispute.getId()))
                .thenReturn(Optional.of(frozen.event()));

        assertThatThrownBy(
                        () ->
                                service.ensureOpening(
                                        dispute.getId(),
                                        RoomType.EVIDENCE,
                                        new AuthenticatedActor("user-local", ActorRole.USER),
                                        "TRACE_RESULT_REVISION_DRIFT",
                                        "REQ_RESULT_REVISION_DRIFT_" + revisionField))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen Evidence submission authority is invalid");

        verify(timelineEventRepository, times(1))
                .findByIdAndCaseId(frozen.authority().submitEventId(), dispute.getId());
        verify(client, never()).run(any(), any(), any());
    }

    private static Stream<Arguments> frozenResultRevisionFields() {
        return Stream.of(
                Arguments.of("process_revision"), Arguments.of("room_revision"));
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

    private TargetEvidenceTurnResultV2 targetV3AssessmentResult(
            String evidenceId,
            Double authenticityScore,
            Double relevanceScore,
            Double completenessScore,
            Double assessmentConfidence,
            String riskLevel) {
        ArrayNode manifest = objectMapper.createArrayNode();

        ObjectNode receipt = targetV2Header(1, "MATERIAL_RECEIPT");
        receipt.set("evidence_ids", targetV2Array(evidenceId));
        manifest.add(targetV2Frame(1, "MATERIAL_RECEIPT", receipt, "已收到本批材料。"));

        ObjectNode assessment = targetV2Header(2, "EVIDENCE_ASSESSMENT");
        assessment.put("evidence_id", evidenceId);
        assessment.set("observation_slots", objectMapper.createArrayNode());
        if (authenticityScore != null) {
            assessment.put("authenticity_score", authenticityScore);
        }
        assessment.put("authenticity_score_explanation", "真实性解释：缺少原始导出。");
        if (relevanceScore != null) {
            assessment.put("relevance_score", relevanceScore);
        }
        assessment.put("relevance_score_explanation", "关联性解释：仅部分对应待证事实。");
        if (completenessScore != null) {
            assessment.put("completeness_score", completenessScore);
        }
        assessment.put("completeness_score_explanation", "完整性解释：缺少关键上下文。");
        if (assessmentConfidence != null) {
            assessment.put("assessment_confidence", assessmentConfidence);
        }
        assessment.put("assessment_confidence_explanation", "置信度解释：可读取信息有限。");
        if (riskLevel != null) {
            assessment.put("risk_level", riskLevel);
        }
        assessment.put(
                "risk_explanation", "综合风险解释：存在必须人工确认的重大风险。");
        assessment.set("source_basis", targetV2Array("材料解析文本"));
        assessment.put("formation_time_assessment", "形成时间只能部分确认");
        ObjectNode finding = objectMapper.createObjectNode();
        finding.put("finding_type", "PARSED_RECORD");
        finding.put("description", "读取到材料中的关键记录");
        assessment.set("findings", objectMapper.createArrayNode().add(finding));
        assessment.set("limitations", targetV2Array("缺少签收人身份"));
        assessment.set("unsupported_claims", targetV2Array("不能单独确认签收主体"));
        manifest.add(targetV2Frame(
                2,
                "EVIDENCE_ASSESSMENT",
                assessment,
                "材料文本可读，但缺少签收人身份和交付照片。"));

        int readinessSequence = 3;
        ObjectNode readiness = targetV2Header(readinessSequence, "ROOM_READINESS");
        readiness.put("core_fact_coverage", "PARTIAL");
        readiness.put("source_chain_coverage", "PARTIAL");
        readiness.put("time_integrity_coverage", "UNKNOWN");
        readiness.set("remaining_core_fact_ids", objectMapper.createArrayNode());
        readiness.put("overall_readiness", "PARTIAL");
        String readinessText = "本批材料核验完成。";
        manifest.add(targetV2Frame(
                readinessSequence,
                "ROOM_READINESS",
                readiness,
                readinessText));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("schema_version", TargetEvidenceTurnResultV2.SCHEMA_VERSION);
        result.put("frame_authority_schema", TargetEvidenceTurnResultV2.FRAME_SCHEMA_VERSION);
        result.set("frame_manifest", manifest);
        result.put("frame_manifest_sha256", ContractJson.sha256Hex(manifest));
        result.put(
                "room_utterance",
                "已收到本批材料。\n\n材料文本可读，但缺少签收人身份和交付照片。"
                        + "\n\n"
                        + readinessText);
        result.set("referenced_evidence_ids", targetV2Array(evidenceId));
        result.set("observation_graph", objectMapper.createArrayNode());
        result.set(
                "evidence_assessments",
                objectMapper.createArrayNode().add(assessment));
        result.set("evidence_requests", objectMapper.createArrayNode());
        result.set("room_readiness", readiness);
        return TargetEvidenceTurnResultV2.parse(objectMapper, result);
    }

    private ObjectNode targetV2Header(int sequence, String frameType) {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("frame_sequence", sequence);
        header.put("frame_type", frameType);
        return header;
    }

    private ObjectNode targetV2Frame(
            int sequence, String frameType, ObjectNode header, String publicText) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put(
                "frame_id",
                TargetEvidenceTurnResultV2.frameId(
                        "evidence-command-v2-projection",
                        "evidence-attempt-v2-projection",
                        sequence,
                        frameType));
        frame.put("frame_sequence", sequence);
        frame.put("frame_type", frameType);
        frame.set("header", header);
        frame.put("header_sha256", ContractJson.sha256Hex(header));
        if (publicText == null) {
            frame.putNull("public_text");
        } else {
            frame.put("public_text", publicText);
        }
        String persistedText = publicText == null ? "" : publicText;
        frame.put("public_text_sha256", targetV2TextSha256(persistedText));
        frame.put(
                "public_text_length",
                persistedText.codePointCount(0, persistedText.length()));
        frame.put("frame_sha256", ContractJson.sha256Hex(frame.deepCopy()));
        return frame;
    }

    private ArrayNode targetV2Array(String... values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String targetV2TextSha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
    private CaseCommandEntity copyOpeningCommand(
            CaseCommandEntity source, String discriminator) {
        CaseCommandRef reference =
                CaseCommandReferenceMapper.fromEntity(source, objectMapper);
        return CaseCommandEntity.pending(
                "CMD_OPENING_COPY_" + stableOpeningToken(discriminator),
                reference,
                source.getActorScopesJson(),
                source.getAcceptedAt());
    }

    private static void markTerminalNoCommit(
            CaseCommandEntity command, String receiptSha256, long offsetSeconds) {
        OffsetDateTime terminalAt =
                OffsetDateTime.ofInstant(
                        CLOCK.instant().plusSeconds(offsetSeconds), ZoneOffset.UTC);
        command.markOrchestrationAccepted(terminalAt.minusSeconds(1));
        command.markAcceptedOrchestrationTerminalNoCommit(
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT",
                "urn:target-room-agent-run-terminal-no-commit:" + receiptSha256,
                receiptSha256,
                terminalAt);
    }

    private static OffsetDateTime markRecoveredExpiredTerminalNoCommit(
            CaseCommandEntity command, String receiptSha256) {
        OffsetDateTime terminalAt = command.getDeadlineAt().minusSeconds(1);
        OffsetDateTime expiredAt = command.getDeadlineAt().plusSeconds(1);
        command.markOrchestrationAccepted(terminalAt.minusSeconds(1));
        command.markExpired("COMMAND_DEADLINE_EXPIRED", expiredAt);
        command.markExpiredOrchestrationTerminalNoCommit(
                "COMMAND_DEADLINE_EXPIRED",
                expiredAt,
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT",
                "urn:target-room-agent-run-terminal-no-commit:" + receiptSha256,
                receiptSha256,
                terminalAt);
        return expiredAt;
    }

    private static String generationZeroOpeningCommandId(
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            AuthenticatedActor actor) {
        return "evidence-opening:"
                + stableOpeningToken(
                        tenantSurrogate
                                + "\n"
                                + caseId
                                + "\n"
                                + roomEpoch
                                + "\n"
                                + actor.actorId()
                                + "\n"
                                + actor.role().name());
    }

    private static String successorOpeningCommandId(
            String baseCommandId,
            CaseCommandEntity prior,
            String priorTerminalReceiptSha256) {
        return "evidence-opening:"
                + stableOpeningToken(
                        "target-evidence-opening-retry-generation.v1\n"
                                + baseCommandId
                                + "\n"
                                + prior.getCommandId()
                                + "\n"
                                + prior.getCaseCommandSequence()
                                + "\n"
                                + priorTerminalReceiptSha256);
    }

    private static String stableOpeningToken(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private EvidenceAgentTurnResult safeOpeningResult(String roomUtterance) {
        return new EvidenceAgentTurnResult(
                roomUtterance,
                objectMapper.createObjectNode(),
                objectMapper.createArrayNode(),
                List.of(),
                false,
                false,
                "STUB",
                0.9);
    }

    private FrozenSubmissionFixture frozenSubmissionFixture(
            String caseId, AtomicLong evidenceEpoch) {
        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", FrozenIntakeSubmissionAuthority.MATRIX_SCHEMA_VERSION);
        matrix.put("matrix_kind", FrozenIntakeSubmissionAuthority.MATRIX_KIND);
        matrix.put("case_id", caseId);
        matrix.put("matrix_version", 3);
        ObjectNode overview = matrix.putObject("case_overview");
        overview.put("neutral_summary", "Frozen bilateral watch dispute.");
        overview.put("core_conflict", "Whether the delivered watch was already scratched.");
        matrix.putArray("fact_rows")
                .addObject()
                .put("fact_id", "FACT_GOODS_CONDITION")
                .put("fact_target", "First-use product condition");
        String matrixId =
                "CASE_MATRIX_"
                        + ContractJson.sha256Hex(matrix)
                                .substring(0, 20)
                                .toUpperCase(java.util.Locale.ROOT);
        matrix.put("matrix_id", matrixId);
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));

        String eventId = "EVIB_EVIDENCE_FROZEN";
        String eventRef = "urn:after-sale-flow:intake-event:" + eventId;
        FrozenIntakeSubmissionAuthority authority =
                FrozenIntakeSubmissionAuthority.capture(
                        "tenant-run001",
                        caseId,
                        "merchant-local",
                        com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.MERCHANT,
                        "INTAKE_COMPLETION_RESPONDENT",
                        FrozenIntakeSubmissionAuthority.COMPLETION_STATUS,
                        Instant.parse("2026-07-05T22:00:00Z"),
                        "intake-respondent-confirm:operation",
                        "intake-respondent-confirm:command",
                        7,
                        "1".repeat(64),
                        eventId,
                        eventRef,
                        9,
                        1,
                        11,
                        6,
                        5,
                        "INTAKE_DOSSIER_FROZEN",
                        3,
                        matrix);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("schema_version", "intake-branch-result.v2");
        result.put("operation", FrozenIntakeSubmissionAuthority.SUBMIT_OPERATION);
        result.put("case_id", caseId);
        result.put("case_status", "EVIDENCE_OPEN");
        result.put("current_room", "EVIDENCE");
        result.put("process_revision", authority.sourceProcessRevision());
        result.put("room_revision", authority.sourceRoomRevision());
        result.put("matrix_kind", FrozenIntakeSubmissionAuthority.MATRIX_KIND);
        result.put("matrix_hash", authority.matrixContentHash());
        ObjectNode frozen = result.putObject("frozen_submission");
        frozen.set("authority", objectMapper.valueToTree(authority));
        frozen.set("matrix", matrix.deepCopy());

        ObjectNode eventDocument = objectMapper.createObjectNode();
        eventDocument.put("schema_version", "intake-branch-committed-event.v1");
        eventDocument.put("event_id", eventId);
        eventDocument.put("event_ref", eventRef);
        eventDocument.put("event_sequence", authority.submitEventSequence());
        eventDocument.put("event_type", authority.submitEventType());
        eventDocument.put("party", "RESPONDENT");
        eventDocument.put("command_id", authority.submitCommandId());
        eventDocument.put("tenant_surrogate", authority.tenantSurrogate());
        eventDocument.put("case_id", caseId);
        eventDocument.put("room_epoch", authority.sourceRoomEpoch());
        eventDocument.put("fencing_token", authority.sourceFencingToken());
        eventDocument.put("operation_key", authority.submitOperationKey());
        eventDocument.put("request_hash", authority.submitRequestHash());
        eventDocument.put("result_hash", ContractJson.sha256Hex(result));
        eventDocument.put("process_revision", authority.sourceProcessRevision());
        eventDocument.put("room_revision", authority.sourceRoomRevision());
        eventDocument.set("result", result);
        eventDocument.put("event_hash", ContractJson.sha256Hex(eventDocument));

        CaseProcessProjectionEntity projection =
                org.mockito.Mockito.mock(CaseProcessProjectionEntity.class);
        when(projection.getCaseId()).thenReturn(caseId);
        lenient()
                .when(projection.getTenantSurrogate())
                .thenReturn(authority.tenantSurrogate());
        when(projection.getCurrentRoom()).thenReturn("EVIDENCE");
        when(projection.getRoomEpoch()).thenAnswer(ignored -> evidenceEpoch.get());
        when(projection.getFencingToken()).thenReturn(23L);
        lenient().when(projection.getProjectionRef()).thenReturn(authority.projectionRef());
        lenient()
                .when(projection.getProjectionSha256())
                .thenReturn(authority.matrixContentHash());

        CaseTimelineEventEntity event =
                org.mockito.Mockito.mock(CaseTimelineEventEntity.class);
        lenient().when(event.getId()).thenReturn(eventId);
        lenient().when(event.getSequenceNo()).thenReturn(authority.submitEventSequence());
        lenient().when(event.getEventType()).thenReturn(authority.submitEventType());
        try {
            lenient()
                    .when(event.getEventJson())
                    .thenReturn(objectMapper.writeValueAsString(eventDocument));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
        return new FrozenSubmissionFixture(
                projection, event, authority, matrix, eventDocument);
    }

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

    private record FrozenSubmissionFixture(
            CaseProcessProjectionEntity projection,
            CaseTimelineEventEntity event,
            FrozenIntakeSubmissionAuthority authority,
            ObjectNode matrix,
            ObjectNode eventDocument) {}
}
