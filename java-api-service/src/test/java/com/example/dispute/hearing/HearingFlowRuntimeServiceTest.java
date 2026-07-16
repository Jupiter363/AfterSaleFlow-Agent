package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.HearingFlowRuntimeService;
import com.example.dispute.hearing.application.HearingFlowView;
import com.example.dispute.hearing.application.HearingReviewHandoffService;
import com.example.dispute.hearing.application.HearingTrialDossierService;
import com.example.dispute.hearing.api.HearingPartyStatementRequest;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowStageStatus;
import com.example.dispute.hearing.domain.HearingFlowStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowStageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowActionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowStageRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingTrialDossierRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.room.application.CaseEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HearingFlowRuntimeServiceTest {

    private static final String CASE_ID = "CASE_hearing_runtime";
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private HearingStateRepository hearingStateRepository;
    @Mock private HearingFlowInstanceRepository instanceRepository;
    @Mock private HearingFlowStageRepository stageRepository;
    @Mock private HearingFlowActionRepository actionRepository;
    @Mock private HearingFlowArtifactRepository artifactRepository;
    @Mock private AdjudicationDraftRepository adjudicationDraftRepository;
    @Mock private HearingTrialDossierRepository trialDossierRepository;
    @Mock private CaseIntakeDossierRepository intakeDossierRepository;
    @Mock private EvidenceDossierRepository evidenceDossierRepository;
    @Mock private EvidenceItemRepository evidenceItemRepository;
    @Mock private CaseRoomRepository roomRepository;
    @Mock private RoomMessageRepository messageRepository;
    @Mock private CaseEventService caseEventService;
    @Mock private AgentRunRepository agentRunRepository;
    @Mock private AgentRunCoordinator agentRunCoordinator;
    @Mock private HearingTrialDossierService trialDossierService;
    @Mock private HearingReviewHandoffService reviewHandoffService;
    @Mock private PostCommitSideEffectExecutor postCommitSideEffects;
    @Mock private RemedyPlanRepository remedyPlanRepository;
    @Mock private ReviewTaskRepository reviewTaskRepository;

    private ObjectMapper objectMapper;
    private HearingFlowRuntimeService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service =
                new HearingFlowRuntimeService(
                        caseRepository,
                        hearingStateRepository,
                        instanceRepository,
                        stageRepository,
                        actionRepository,
                        artifactRepository,
                        adjudicationDraftRepository,
                        trialDossierRepository,
                        intakeDossierRepository,
                        evidenceDossierRepository,
                        evidenceItemRepository,
                        roomRepository,
                        messageRepository,
                        caseEventService,
                        agentRunRepository,
                        agentRunCoordinator,
                        trialDossierService,
                        reviewHandoffService,
                        postCommitSideEffects,
                        remedyPlanRepository,
                        reviewTaskRepository,
                        new DisputeProperties(
                                Duration.ofHours(2),
                                Duration.ofHours(3),
                                Duration.ofMinutes(20),
                                Duration.ofSeconds(15),
                                false),
                        objectMapper,
                        CLOCK);
    }

    @Test
    void startupUsesTemplateJudgeAndStartsNoJudgeAgentBeforeDossierFreeze() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.imported(
                        CASE_ID,
                        "ORDER_1",
                        "AFTER_SALE_1",
                        "LOGISTICS_1",
                        "user-1",
                        "merchant-1",
                        "IMPORT_KEY_1",
                        "FULFILLMENT_DISPUTE",
                        "签收争议",
                        "用户称未实际收到商品。",
                        RiskLevel.MEDIUM,
                        CaseStatus.HEARING_OPEN,
                        "HEARING",
                        OffsetDateTime.ofInstant(NOW.plus(Duration.ofHours(3)), ZoneOffset.UTC),
                        "TEST",
                        "EXTERNAL_1",
                        "test");
        HearingStateEntity hearingState =
                HearingStateEntity.start(
                        "HEARING_STATE_1", CASE_ID, "WORKFLOW_HEARING_1", "test");
        CaseRoomEntity room =
                CaseRoomEntity.open(
                        "ROOM_HEARING_1",
                        CASE_ID,
                        RoomType.HEARING,
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                        "test");
        ObjectNode matrix = sourceMatrix();
        CaseIntakeDossierEntity intakeDossier =
                CaseIntakeDossierEntity.create(
                        "INTAKE_DOSSIER_1",
                        CASE_ID,
                        RoomType.INTAKE,
                        json(objectMapper.createObjectNode().set("case_fact_matrix", matrix)),
                        90,
                        true,
                        "ACCEPTED",
                        2,
                        "test");
        EvidenceDossierEntity evidenceDossier =
                EvidenceDossierEntity.frozen(
                        "EVIDENCE_DOSSIER_1",
                        CASE_ID,
                        2,
                        "test",
                        "{}",
                        "[]",
                        "[]");

        AtomicReference<HearingFlowStageEntity> currentStage = new AtomicReference<>();
        AtomicLong sequence = new AtomicLong();
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(instanceRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.empty());
        when(instanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(hearingState));
        when(roomRepository.findByCaseIdAndRoomTypeForUpdate(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.of(intakeDossier));
        when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.of(evidenceDossier));
        when(stageRepository.save(any(HearingFlowStageEntity.class)))
                .thenAnswer(
                        invocation -> {
                            HearingFlowStageEntity stage = invocation.getArgument(0);
                            currentStage.set(stage);
                            return stage;
                        });
        when(stageRepository.findByFlowInstanceIdAndStageSequence(anyString(), anyInt()))
                .thenAnswer(invocation -> Optional.ofNullable(currentStage.get()));
        when(messageRepository.findByCaseIdAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId()))
                .thenAnswer(invocation -> sequence.getAndIncrement());
        when(messageRepository.save(any(RoomMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(agentRunCoordinator.start(any()))
                .thenReturn(
                        new AgentRunAcceptedView(
                                "AGENT_RUN_INTAKE_QUESTIONS",
                                "PENDING",
                                "/api/agent-runs/AGENT_RUN_INTAKE_QUESTIONS/events",
                                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        when(actionRepository.findAllByFlowInstanceIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(List.of());
        when(artifactRepository.findByCaseIdAndArtifactType(anyString(), any()))
                .thenReturn(Optional.empty());
        when(trialDossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());

        service.get(CASE_ID, new AuthenticatedActor("user-1", ActorRole.USER));

        ArgumentCaptor<RoomMessageEntity> messages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(8)).save(messages.capture());
        RoomMessageEntity judgeOpening =
                messages.getAllValues().stream()
                        .filter(message -> "PRESIDING_JUDGE".equals(message.getSenderRole()))
                        .findFirst()
                        .orElseThrow();
        assertThat(judgeOpening.getMessageSource()).isEqualTo(MessageSource.ROLE_TEMPLATE);
        assertThat(judgeOpening.getAgentRunId()).isNull();
        assertThat(judgeOpening.getMessageText()).contains("现在开庭");
        assertThat(messages.getAllValues())
                .anySatisfy(
                        message -> {
                            assertThat(message.getMessageSource())
                                    .isEqualTo(MessageSource.SYSTEM_STAGE_EVENT);
                            assertThat(message.getMessageText())
                                    .isEqualTo("前序案情矩阵和证据矩阵已装载。");
                        });
        assertThat(messages.getAllValues())
                .anySatisfy(
                        message ->
                                assertThat(message.getMessageText())
                                        .isEqualTo("下面请案情接待官介绍庭前案情。"));
        assertThat(messages.getAllValues())
                .anySatisfy(
                        message -> {
                            assertThat(message.getMessageText())
                                    .contains("庭前双方案情汇总", "案情概览", "用户称未实际收到商品")
                                    .doesNotContain("schema_version", "matrix_id", "{");
                        });
        assertThat(messages.getAllValues())
                .anySatisfy(
                        message -> {
                            assertThat(message.getMessageText())
                                    .contains("庭前证据覆盖汇总", "共核对 1 项事实")
                                    .doesNotContain("schema_version", "fact_id", "{");
                        });
        verify(caseEventService, org.mockito.Mockito.times(8))
                .recordRoomMessage(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());

        ArgumentCaptor<AgentRunStartCommand> starts =
                ArgumentCaptor.forClass(AgentRunStartCommand.class);
        verify(agentRunCoordinator).start(starts.capture());
        assertThat(starts.getAllValues())
                .extracting(AgentRunStartCommand::operation)
                .containsExactly("HEARING_INTAKE_QUESTIONS")
                .doesNotContain("HEARING_JUDGE_V1", "HEARING_JUDGE_V2");
    }

    @Test
    void partyWindowCannotExtendPastTheThreeHourHearingDeadline() throws Exception {
        Instant hearingDeadline = NOW.plus(Duration.ofMinutes(5));
        FulfillmentCaseEntity dispute = hearingCase(hearingDeadline);
        HearingStateEntity hearingState =
                HearingStateEntity.start(
                        "HEARING_STATE_DEADLINE", CASE_ID, "WORKFLOW_HEARING_DEADLINE", "test");
        CaseRoomEntity room = hearingRoom("ROOM_HEARING_DEADLINE");
        HearingFlowInstanceEntity flow = flowAtIntakeQuestions(hearingState.getId());

        ObjectNode request = objectMapper.createObjectNode();
        request.put("flow_schema_version", "hearing_flow.v2");
        request.put("case_id", CASE_ID);
        request.put("stage_sequence", flow.getStageSequence());
        request.set("case_fact_matrix", sourceMatrix());
        HearingFlowStageEntity questionStage =
                agentStage(flow, "STAGE_INTAKE_QUESTIONS_DEADLINE", request, "AGENT_RUN_DEADLINE");
        AtomicReference<HearingFlowStageEntity> currentStage =
                new AtomicReference<>(questionStage);
        AtomicLong sequence = new AtomicLong();

        when(instanceRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));
        when(stageRepository.findByFlowInstanceIdAndStageSequence(anyString(), anyInt()))
                .thenAnswer(invocation -> Optional.of(currentStage.get()));
        when(stageRepository.save(any(HearingFlowStageEntity.class)))
                .thenAnswer(
                        invocation -> {
                            HearingFlowStageEntity stage = invocation.getArgument(0);
                            currentStage.set(stage);
                            return stage;
                        });
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(hearingState));
        when(roomRepository.findByCaseIdAndRoomTypeForUpdate(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(actionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findByCaseIdAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId()))
                .thenAnswer(invocation -> sequence.getAndIncrement());
        when(messageRepository.save(any(RoomMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("schema_version", "hearing_intake_questions.v1");
        result.put("case_id", CASE_ID);
        result.put("workflow_id", hearingState.getWorkflowId());
        result.put("stage_sequence", flow.getStageSequence());
        result.put("public_message", "Please answer the clarification question.");
        ObjectNode question = result.putArray("questions").addObject();
        question.put("question_id", "QUESTION_DELIVERY");
        question.putArray("fact_ids").add("FACT_DELIVERY");
        question.putArray("target_roles").add("USER").add("MERCHANT");
        question.put("question_text", "Was the item delivered?");
        question.put("issue_id", "ISSUE_DELIVERY");
        question.put("issue_statement", "The parties dispute whether delivery occurred.");
        question.putObject("party_prompts")
                .put("USER", "Please describe why you did not receive the item.")
                .put("MERCHANT", "Please describe how the item was delivered.");

        service.finalizeResult(
                new AgentRunFinalizationContext(
                        "AGENT_RUN_DEADLINE",
                        CASE_ID,
                        room.getId(),
                        "HEARING_INTAKE_QUESTIONS",
                        "TRACE_DEADLINE",
                        "hearing-flow:deadline",
                        request),
                result);

        assertThat(flow.getCurrentStage()).isEqualTo(HearingFlowStage.PARTY_ANSWERS_OPEN);
        assertThat(flow.getSharedDeadlineAt()).isEqualTo(hearingDeadline);
        assertThat(currentStage.get().getSharedDeadlineAt()).isEqualTo(hearingDeadline);
        ArgumentCaptor<HearingFlowActionEntity> action =
                ArgumentCaptor.forClass(HearingFlowActionEntity.class);
        verify(actionRepository).save(action.capture());
        JsonNode persistedQuestionSet = objectMapper.readTree(action.getValue().getPayloadJson());
        assertThat(persistedQuestionSet.path("issue_set_id").asText())
                .isEqualTo(persistedQuestionSet.path("question_set_id").asText());
        assertThat(persistedQuestionSet.path("issues").get(0).path("issue_id").asText())
                .isEqualTo("ISSUE_DELIVERY");
        assertThat(
                        persistedQuestionSet
                                .path("questions")
                                .get(0)
                                .path("party_prompts")
                                .path("MERCHANT")
                                .asText())
                .isEqualTo("Please describe how the item was delivered.");
    }

    @Test
    void naturalLanguageStatementsArePrivateAndTerminalByParticipantId() throws Exception {
        FulfillmentCaseEntity dispute = hearingCase(NOW.plus(Duration.ofHours(3)));
        HearingStateEntity hearingState =
                HearingStateEntity.start(
                        "HEARING_STATE_STATEMENTS", CASE_ID, "WORKFLOW_STATEMENTS", "test");
        CaseRoomEntity room = hearingRoom("ROOM_HEARING_STATEMENTS");
        HearingFlowInstanceEntity flow = flowAtIntakeQuestions(hearingState.getId());
        flow.advance(
                HearingFlowStage.PARTY_ANSWERS_OPEN,
                5,
                NOW.plus(Duration.ofMinutes(20)),
                NOW,
                "test");

        ObjectNode questionStageInput = objectMapper.createObjectNode();
        questionStageInput.set("case_fact_matrix", objectMapper.createObjectNode());
        HearingFlowStageEntity questionStage =
                HearingFlowStageEntity.open(
                        "STAGE_QUESTIONS_STATEMENTS",
                        flow.getId(),
                        CASE_ID,
                        HearingFlowStage.INTAKE_QUESTIONS_GENERATING,
                        4,
                        "INTAKE_OFFICER",
                        HearingFlowStageStatus.RUNNING,
                        null,
                        json(questionStageInput),
                        NOW,
                        "test");
        HearingFlowStageEntity answerStage =
                HearingFlowStageEntity.open(
                        "STAGE_PARTY_STATEMENTS",
                        flow.getId(),
                        CASE_ID,
                        HearingFlowStage.PARTY_ANSWERS_OPEN,
                        5,
                        "PARTIES",
                        HearingFlowStageStatus.WAITING_PARTIES,
                        NOW.plus(Duration.ofMinutes(20)),
                        "{}",
                        NOW,
                        "test");

        ObjectNode questionPayload = objectMapper.createObjectNode();
        questionPayload.put("schema_version", "hearing_question_set.v1");
        questionPayload.put("question_set_id", "LEGACY_QUESTION_SET_1");
        ObjectNode merchantOnlyQuestion = questionPayload.putArray("questions").addObject();
        merchantOnlyQuestion.put("question_id", "QUESTION_MERCHANT_ONLY");
        merchantOnlyQuestion.putArray("target_roles").add("MERCHANT");
        merchantOnlyQuestion.putArray("fact_ids").add("FACT_1");
        merchantOnlyQuestion.put("question_text", "Please provide the merchant record.");
        HearingFlowActionEntity questionAction =
                HearingFlowActionEntity.agentOutput(
                        "ACTION_QUESTION_SET",
                        flow.getId(),
                        questionStage.getId(),
                        CASE_ID,
                        HearingFlowActionType.QUESTION_SET,
                        json(questionPayload),
                        sha256(canonicalJson(questionPayload)),
                        "AGENT_RUN_QUESTIONS",
                        NOW,
                        "test");

        Map<String, HearingFlowActionEntity> partyActions = new LinkedHashMap<>();
        List<HearingFlowActionEntity> allActions = new java.util.ArrayList<>();
        allActions.add(questionAction);
        AtomicLong sequence = new AtomicLong();

        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(instanceRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));
        when(stageRepository.findByFlowInstanceIdAndStageCode(
                        flow.getId(), HearingFlowStage.PARTY_ANSWERS_OPEN))
                .thenReturn(Optional.of(answerStage));
        when(stageRepository.findByFlowInstanceIdAndStageCode(
                        flow.getId(), HearingFlowStage.INTAKE_QUESTIONS_GENERATING))
                .thenReturn(Optional.of(questionStage));
        when(stageRepository.findByFlowInstanceIdAndStageSequence(flow.getId(), 5))
                .thenReturn(Optional.of(answerStage));
        when(stageRepository.save(any(HearingFlowStageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(actionRepository.findByStageIdAndActionTypeAndParticipantId(
                        anyString(), any(HearingFlowActionType.class), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(partyActions.get(invocation.getArgument(2))));
        when(actionRepository.save(any(HearingFlowActionEntity.class)))
                .thenAnswer(
                        invocation -> {
                            HearingFlowActionEntity action = invocation.getArgument(0);
                            partyActions.put(action.getParticipantId(), action);
                            allActions.add(action);
                            return action;
                        });
        when(actionRepository.findAllByFlowInstanceIdOrderByCreatedAtAsc(flow.getId()))
                .thenAnswer(invocation -> List.copyOf(allActions));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(hearingState));
        when(roomRepository.findByCaseIdAndRoomTypeForUpdate(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(room));
        when(messageRepository.findByCaseIdAndIdempotencyKey(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(messageRepository.findMaxSequenceByRoomId(room.getId()))
                .thenAnswer(invocation -> sequence.getAndIncrement());
        when(messageRepository.save(any(RoomMessageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(artifactRepository.findByCaseIdAndArtifactType(anyString(), any()))
                .thenReturn(Optional.empty());
        when(trialDossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        when(agentRunCoordinator.start(any()))
                .thenReturn(
                        new AgentRunAcceptedView(
                                "AGENT_RUN_SYNTHESIS",
                                "PENDING",
                                "/api/agent-runs/AGENT_RUN_SYNTHESIS/events",
                                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        HearingPartyStatementRequest userRequest =
                new HearingPartyStatementRequest(
                        "hearing_party_statement.v1",
                        "LEGACY_QUESTION_SET_1",
                        "The parcel shown as delivered was not received by me.",
                        List.of("MESSAGE_USER_1"));
        var userResult =
                service.submitStatement(
                        CASE_ID, userRequest, new AuthenticatedActor("user-1", ActorRole.USER));
        var repeated =
                service.submitStatement(
                        CASE_ID, userRequest, new AuthenticatedActor("user-1", ActorRole.USER));

        assertThat(userResult.participantId()).isEqualTo("user-1");
        assertThat(userResult.schemaVersion()).isEqualTo("hearing_party_statement.v1");
        assertThat(repeated.actionId()).isEqualTo(userResult.actionId());
        assertThat(userResult.payload().path("statement_text").asText())
                .isEqualTo(userRequest.statementText());
        assertThat(partyActions).containsOnlyKeys("user-1");

        HearingFlowView beforeMerchant =
                service.get(CASE_ID, new AuthenticatedActor("user-1", ActorRole.USER));
        assertThat(beforeMerchant.status().participantStatuses())
                .containsExactly(
                        new HearingFlowView.ParticipantStatus("user-1", "USER", "SUBMITTED"),
                        new HearingFlowView.ParticipantStatus(
                                "merchant-1", "MERCHANT", "PENDING"));

        HearingPartyStatementRequest merchantRequest =
                new HearingPartyStatementRequest(
                        "hearing_party_statement.v1",
                        "LEGACY_QUESTION_SET_1",
                        "The parcel was delivered to the agreed collection point.",
                        List.of("MESSAGE_MERCHANT_1"));
        service.submitStatement(
                CASE_ID,
                merchantRequest,
                new AuthenticatedActor("merchant-1", ActorRole.MERCHANT));

        assertThat(flow.getCurrentStage()).isEqualTo(HearingFlowStage.INTAKE_SYNTHESIZING);
        JsonNode completion = objectMapper.readTree(answerStage.getOutputJson());
        assertThat(completion.path("participant_statuses")).hasSize(2);
        assertThat(completion.path("participant_statuses").get(0).path("participantId").asText())
                .isEqualTo("user-1");
        verify(actionRepository, org.mockito.Mockito.times(2)).save(any());
        ArgumentCaptor<AgentRunStartCommand> synthesisStart =
                ArgumentCaptor.forClass(AgentRunStartCommand.class);
        verify(agentRunCoordinator).start(synthesisStart.capture());
        assertThat(synthesisStart.getValue().operation()).isEqualTo("HEARING_INTAKE_SYNTHESIS");
        JsonNode partySubmissions =
                synthesisStart.getValue().request().path("party_submissions");
        assertThat(partySubmissions).hasSize(2);
        assertThat(partySubmissions.get(0).path("participant_id").asText())
                .isEqualTo("user-1");
        assertThat(partySubmissions.get(1).path("participant_id").asText())
                .isEqualTo("merchant-1");

        ArgumentCaptor<RoomMessageEntity> messages =
                ArgumentCaptor.forClass(RoomMessageEntity.class);
        verify(messageRepository, org.mockito.Mockito.times(3)).save(messages.capture());
        List<RoomMessageEntity> privateStatements =
                messages.getAllValues().stream()
                        .filter(message -> message.getMessageSource() == MessageSource.PARTY_ACTION)
                        .toList();
        assertThat(privateStatements).hasSize(2);
        assertThat(privateStatements.get(0).getAudienceJson()).isEqualTo("[]");
        assertThat(objectMapper.readTree(privateStatements.get(0).getAudienceActorIdsJson()))
                .containsExactly(objectMapper.getNodeFactory().textNode("user-1"));
        verify(caseEventService, org.mockito.Mockito.times(3))
                .recordRoomMessage(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());
        verify(caseEventService, org.mockito.Mockito.times(2))
                .recordLifecycleEvent(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq("HEARING_ANSWER_BUNDLE_SUBMITTED"),
                        any(),
                        anyString(),
                        anyString());
    }

    @Test
    void recoverableAgentFailureIsReboundToTheAuditedRetry() {
        FailedAgentFixture fixture = failedAgentFixture(true);
        stubFailedAgentScan(fixture);
        when(agentRunCoordinator.retryInfrastructureFailure(any()))
                .thenReturn(
                        new AgentRunAcceptedView(
                                "AGENT_RUN_RETRY",
                                "PENDING",
                                "/api/agent-runs/AGENT_RUN_RETRY/events",
                                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(service.expireDuePartyStages()).isEqualTo(1);

        assertThat(fixture.flow().getFlowStatus()).isEqualTo(HearingFlowStatus.ACTIVE);
        assertThat(fixture.stage().getStageStatus()).isEqualTo(HearingFlowStageStatus.RUNNING);
        assertThat(fixture.stage().getAgentRunId()).isEqualTo("AGENT_RUN_RETRY");
    }

    @Test
    void previouslyTerminatedAgentStageResumesWhenAnAuditedRetryIsAvailable() {
        FailedAgentFixture fixture = failedAgentFixture(true);
        fixture.stage().fail("{\"failure_type\":\"AGENT_RUN_FAILED\"}", NOW, "test");
        fixture.flow().fail(NOW, "test");
        stubFailedAgentScan(fixture);
        when(agentRunCoordinator.retryInfrastructureFailure(any()))
                .thenReturn(
                        new AgentRunAcceptedView(
                                "AGENT_RUN_RETRY",
                                "PENDING",
                                "/api/agent-runs/AGENT_RUN_RETRY/events",
                                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(service.expireDuePartyStages()).isEqualTo(1);

        assertThat(fixture.flow().getFlowStatus()).isEqualTo(HearingFlowStatus.ACTIVE);
        assertThat(fixture.stage().getStageStatus()).isEqualTo(HearingFlowStageStatus.RUNNING);
        assertThat(fixture.stage().getAgentRunId()).isEqualTo("AGENT_RUN_RETRY");
        assertThat(fixture.stage().getOutputJson()).isEqualTo("{}");
        assertThat(fixture.stage().getCompletedAt()).isNull();
    }

    @Test
    void unrecoverableAgentFailureTerminatesTheStageInsteadOfLeavingItRunning() throws Exception {
        FailedAgentFixture fixture = failedAgentFixture(false);
        stubFailedAgentScan(fixture);
        when(agentRunCoordinator.retryInfrastructureFailure(any()))
                .thenReturn(
                        new AgentRunAcceptedView(
                                fixture.run().getId(),
                                "FAILED",
                                "/api/agent-runs/" + fixture.run().getId() + "/events",
                                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        assertThat(service.expireDuePartyStages()).isEqualTo(1);

        assertThat(fixture.flow().getFlowStatus()).isEqualTo(HearingFlowStatus.FAILED);
        assertThat(fixture.stage().getStageStatus()).isEqualTo(HearingFlowStageStatus.FAILED);
        JsonNode failure = objectMapper.readTree(fixture.stage().getOutputJson());
        assertThat(failure.path("agent_run_id").asText()).isEqualTo(fixture.run().getId());
        assertThat(failure.path("error_code").asText())
                .isEqualTo("AGENT_STREAM_PROTOCOL_INVALID");
    }

    private void stubFailedAgentScan(FailedAgentFixture fixture) {
        when(instanceRepository.findAll()).thenReturn(List.of(fixture.flow()));
        when(instanceRepository.findByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(fixture.flow()));
        when(caseRepository.findByIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(fixture.dispute()));
        when(stageRepository.findByFlowInstanceIdAndStageSequence(
                        fixture.flow().getId(), fixture.flow().getStageSequence()))
                .thenReturn(Optional.of(fixture.stage()));
        when(agentRunRepository.findById(fixture.run().getId()))
                .thenReturn(Optional.of(fixture.run()));
        when(roomRepository.findByCaseIdAndRoomTypeForUpdate(CASE_ID, RoomType.HEARING))
                .thenReturn(Optional.of(fixture.room()));
    }

    private FailedAgentFixture failedAgentFixture(boolean retryable) {
        FulfillmentCaseEntity dispute = hearingCase(NOW.plus(Duration.ofHours(3)));
        HearingFlowInstanceEntity flow = flowAtIntakeQuestions("HEARING_STATE_FAILURE");
        ObjectNode request = objectMapper.createObjectNode();
        request.put("flow_schema_version", "hearing_flow.v2");
        request.put("case_id", CASE_ID);
        request.put("stage_sequence", flow.getStageSequence());
        HearingFlowStageEntity stage =
                agentStage(flow, "STAGE_INTAKE_QUESTIONS_FAILURE", request, "AGENT_RUN_FAILED");
        AgentRunEntity run =
                AgentRunEntity.streamingPending(
                        "AGENT_RUN_FAILED",
                        CASE_ID,
                        "ROOM_HEARING_FAILURE",
                        "HEARING_INTAKE_QUESTIONS",
                        "/internal/agents/hearing-flow/intake/questions/stream",
                        "INTAKE_OFFICER",
                        json(request),
                        "a".repeat(64),
                        "[]",
                        "[]",
                        "hearing-flow:" + CASE_ID + ":4:hearing_intake_questions",
                        "TRACE_FAILURE",
                        "REQUEST_FAILURE",
                        "hearing-flow-v2");
        run.markRunning();
        run.markFailed(
                retryable
                        ? "AGENT_STREAM_TRANSPORT_FAILED"
                        : "AGENT_STREAM_PROTOCOL_INVALID",
                "agent stream failed",
                retryable,
                10L);
        return new FailedAgentFixture(
                dispute, flow, stage, run, hearingRoom("ROOM_HEARING_FAILURE"));
    }

    private HearingFlowInstanceEntity flowAtIntakeQuestions(String hearingStateId) {
        HearingFlowInstanceEntity flow =
                HearingFlowInstanceEntity.start(
                        "HEARING_FLOW_TEST", CASE_ID, hearingStateId, NOW, "test");
        flow.advance(HearingFlowStage.CASE_INTRODUCTION, 2, null, NOW, "test");
        flow.advance(HearingFlowStage.EVIDENCE_INTRODUCTION, 3, null, NOW, "test");
        flow.advance(HearingFlowStage.INTAKE_QUESTIONS_GENERATING, 4, null, NOW, "test");
        return flow;
    }

    private HearingFlowStageEntity agentStage(
            HearingFlowInstanceEntity flow,
            String stageId,
            ObjectNode request,
            String runId) {
        HearingFlowStageEntity stage =
                HearingFlowStageEntity.open(
                        stageId,
                        flow.getId(),
                        CASE_ID,
                        HearingFlowStage.INTAKE_QUESTIONS_GENERATING,
                        flow.getStageSequence(),
                        "INTAKE_OFFICER",
                        HearingFlowStageStatus.RUNNING,
                        null,
                        json(request),
                        NOW,
                        "test");
        stage.attachAgentRun(runId, NOW, "test");
        return stage;
    }

    private FulfillmentCaseEntity hearingCase(Instant deadline) {
        return FulfillmentCaseEntity.imported(
                CASE_ID,
                "ORDER_1",
                "AFTER_SALE_1",
                "LOGISTICS_1",
                "user-1",
                "merchant-1",
                "IMPORT_KEY_1",
                "FULFILLMENT_DISPUTE",
                "Delivery dispute",
                "The user reports that the item was not delivered.",
                RiskLevel.MEDIUM,
                CaseStatus.HEARING_OPEN,
                "HEARING",
                OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC),
                "TEST",
                "EXTERNAL_1",
                "test");
    }

    private CaseRoomEntity hearingRoom(String roomId) {
        return CaseRoomEntity.open(
                roomId,
                CASE_ID,
                RoomType.HEARING,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                "test");
    }

    private record FailedAgentFixture(
            FulfillmentCaseEntity dispute,
            HearingFlowInstanceEntity flow,
            HearingFlowStageEntity stage,
            AgentRunEntity run,
            CaseRoomEntity room) {}

    private ObjectNode sourceMatrix() {
        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("case_id", CASE_ID);
        matrix.put("matrix_id", "CASE_MATRIX_PREHEARING");
        matrix.put("matrix_version", 2);
        matrix.put("matrix_kind", "BILATERAL_FROZEN");
        matrix.putNull("parent_ref");
        matrix.putObject("party_map")
                .put("initiator_role", "USER")
                .put("respondent_role", "MERCHANT");
        matrix.putArray("source_refs").add("INTAKE_SOURCE_1");
        matrix.putObject("case_overview")
                .put("neutral_summary", "用户称未实际收到商品。")
                .put("core_conflict", "商品是否实际交付。")
                .putArray("summary_source_fact_ids")
                .add("FACT_DELIVERY");
        matrix.putObject("claims");
        matrix.putArray("fact_rows")
                .addObject()
                .put("fact_id", "FACT_DELIVERY")
                .put("category", "LOGISTICS")
                .put("fact_target", "商品是否实际交付")
                .put("materiality", "CORE");
        matrix.putArray("fact_relationships");
        matrix.putObject("generation_ref")
                .put("actor_role", "MERCHANT")
                .put("source_stage", "RESPONDENT_INTAKE")
                .put("latest_source_ref", "INTAKE_SOURCE_1")
                .put("source_context_hash", "a".repeat(64));
        matrix.putObject("fact_indexes");
        matrix.put("content_hash", embeddedHash(matrix));
        return matrix;
    }

    private String embeddedHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.remove("content_hash");
        return sha256(canonicalJson(copy));
    }

    private String canonicalJson(JsonNode value) {
        return json(canonicalize(value));
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> result.set(key, canonicalize(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        return value.deepCopy();
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
