package com.example.dispute.hearing.application;

import com.example.dispute.agentstream.application.AgentRunAcceptedView;
import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunFinalizationContext;
import com.example.dispute.agentstream.application.AgentRunFinalizer;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.hearing.api.HearingAnswerBundleRequest;
import com.example.dispute.hearing.api.HearingEvidenceBatchRequest;
import com.example.dispute.hearing.api.HearingPartyStatementRequest;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowStageStatus;
import com.example.dispute.hearing.domain.HearingFlowStatus;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowStageEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingTrialDossierEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowActionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowStageRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingTrialDossierRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
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
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageSource;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runtime owner of the persisted 15-stage hearing_flow.v2 state machine. */
@Service
public class HearingFlowRuntimeService implements AgentRunFinalizer {

    private static final String FLOW_SCHEMA = "hearing_flow.v2";
    private static final String SYSTEM_ACTOR = "hearing-flow-v2";
    private static final List<String> COURT_AUDIENCE =
            List.of("USER", "MERCHANT", "PLATFORM_REVIEWER", "ADMIN");

    private final FulfillmentCaseRepository caseRepository;
    private final HearingStateRepository hearingStateRepository;
    private final HearingFlowInstanceRepository instanceRepository;
    private final HearingFlowStageRepository stageRepository;
    private final HearingFlowActionRepository actionRepository;
    private final HearingFlowArtifactRepository artifactRepository;
    private final AdjudicationDraftRepository adjudicationDraftRepository;
    private final HearingTrialDossierRepository trialDossierRepository;
    private final CaseIntakeDossierRepository intakeDossierRepository;
    private final EvidenceDossierRepository evidenceDossierRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final CaseRoomRepository roomRepository;
    private final RoomMessageRepository messageRepository;
    private final CaseEventService caseEventService;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunCoordinator agentRunCoordinator;
    private final HearingTrialDossierService trialDossierService;
    private final HearingReviewHandoffService reviewHandoffService;
    private final PostCommitSideEffectExecutor postCommitSideEffects;
    private final RemedyPlanRepository remedyPlanRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final DisputeProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HearingFlowRuntimeService(
            FulfillmentCaseRepository caseRepository,
            HearingStateRepository hearingStateRepository,
            HearingFlowInstanceRepository instanceRepository,
            HearingFlowStageRepository stageRepository,
            HearingFlowActionRepository actionRepository,
            HearingFlowArtifactRepository artifactRepository,
            AdjudicationDraftRepository adjudicationDraftRepository,
            HearingTrialDossierRepository trialDossierRepository,
            CaseIntakeDossierRepository intakeDossierRepository,
            EvidenceDossierRepository evidenceDossierRepository,
            EvidenceItemRepository evidenceItemRepository,
            CaseRoomRepository roomRepository,
            RoomMessageRepository messageRepository,
            CaseEventService caseEventService,
            AgentRunRepository agentRunRepository,
            AgentRunCoordinator agentRunCoordinator,
            HearingTrialDossierService trialDossierService,
            HearingReviewHandoffService reviewHandoffService,
            PostCommitSideEffectExecutor postCommitSideEffects,
            RemedyPlanRepository remedyPlanRepository,
            ReviewTaskRepository reviewTaskRepository,
            DisputeProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.instanceRepository = instanceRepository;
        this.stageRepository = stageRepository;
        this.actionRepository = actionRepository;
        this.artifactRepository = artifactRepository;
        this.adjudicationDraftRepository = adjudicationDraftRepository;
        this.trialDossierRepository = trialDossierRepository;
        this.intakeDossierRepository = intakeDossierRepository;
        this.evidenceDossierRepository = evidenceDossierRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.roomRepository = roomRepository;
        this.messageRepository = messageRepository;
        this.caseEventService = caseEventService;
        this.agentRunRepository = agentRunRepository;
        this.agentRunCoordinator = agentRunCoordinator;
        this.trialDossierService = trialDossierService;
        this.reviewHandoffService = reviewHandoffService;
        this.postCommitSideEffects = postCommitSideEffects;
        this.remedyPlanRepository = remedyPlanRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public HearingFlowView get(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedCase(caseId);
        assertCanAccess(dispute, actor);
        HearingFlowInstanceEntity instance = ensureStarted(dispute);
        reconcileFailedAgentRun(instance, dispute);
        expireIfDue(instance, dispute);
        return project(instance, dispute);
    }

    /** POST /complete is intentionally a read/redirect gate in V2. */
    @Transactional
    public HearingFlowView completeGate(String caseId, AuthenticatedActor actor) {
        return get(caseId, actor);
    }

    /** Starts V2 as part of the evidence-to-hearing transition, before either party opens the UI. */
    @Transactional
    public HearingFlowView startAfterEvidenceSealed(String caseId) {
        FulfillmentCaseEntity dispute = lockedCase(caseId);
        HearingFlowInstanceEntity instance = ensureStarted(dispute);
        return project(instance, dispute);
    }

    @Transactional
    public HearingPartyActionView submitAnswers(
            String caseId,
            HearingAnswerBundleRequest request,
            AuthenticatedActor actor) {
        if (request.isPartyStatement()) {
            return submitStatement(caseId, request.toPartyStatement(), actor);
        }
        FulfillmentCaseEntity dispute = lockedCase(caseId);
        assertCaseParty(dispute, actor);
        HearingFlowInstanceEntity instance = ensureStarted(dispute);

        HearingFlowStageEntity answerStage =
                requireStage(instance, HearingFlowStage.PARTY_ANSWERS_OPEN);
        var existing =
                actionRepository.findByStageIdAndActionTypeAndParticipantId(
                        answerStage.getId(), HearingFlowActionType.ANSWER_BUNDLE, actor.actorId());
        if (existing.isPresent()) {
            assertSameAnswerRequest(existing.orElseThrow(), request);
            return partyActionView(existing.orElseThrow());
        }

        requireCurrentStage(instance, HearingFlowStage.PARTY_ANSWERS_OPEN);
        expireIfDue(instance, dispute);
        requireCurrentStage(instance, HearingFlowStage.PARTY_ANSWERS_OPEN);
        assertDeadlineOpen(instance);

        HearingFlowActionEntity questionSet =
                requireSystemAction(instance, HearingFlowActionType.QUESTION_SET);
        ObjectNode questionPayload = object(read(questionSet.getPayloadJson()));
        if (!request.questionSetId().equals(questionPayload.path("question_set_id").asText())) {
            throw invalidArgument("question_set_id does not identify the active question set");
        }
        validateAnswers(questionPayload, request, actor.role());

        Instant now = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "hearing_answer_bundle.v1");
        payload.put("question_set_id", request.questionSetId());
        payload.put("participant_id", actor.actorId());
        payload.put("participant_role", actor.role().name());
        payload.put("submission_status", "SUBMITTED");
        payload.put("submitted_at", now.toString());
        payload.set("answers", objectMapper.valueToTree(request.answers()));
        payload.set("source_message_ids", objectMapper.valueToTree(request.sourceMessageIds()));

        HearingFlowActionEntity saved =
                actionRepository.save(
                        HearingFlowActionEntity.partyAction(
                                id("HEARING_ACTION_"),
                                instance.getId(),
                                answerStage.getId(),
                                caseId,
                                HearingFlowActionType.ANSWER_BUNDLE,
                                actor.actorId(),
                                actor.role(),
                                HearingFlowSubmissionStatus.SUBMITTED,
                                json(payload),
                                contentHash(payload),
                                now,
                                actor.actorId()));
        RoomMessageEntity acknowledgement =
                appendPrivatePartyMessage(
                        dispute,
                        answerStage,
                        actor,
                        MessageType.PARTY_TEXT,
                        answerSummary(request),
                        objectMapper.valueToTree(attachmentRefs(request)),
                        saved.getId(),
                        now);
        recordPartySubmissionEvent(dispute, answerStage, saved, actor, false);
        afterPartyActionsIfComplete(
                dispute,
                instance,
                answerStage,
                HearingFlowActionType.ANSWER_BUNDLE,
                HearingFlowStage.INTAKE_SYNTHESIZING);
        return partyActionView(saved, acknowledgement);
    }

    @Transactional
    public HearingPartyActionView submitStatement(
            String caseId,
            HearingPartyStatementRequest request,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedCase(caseId);
        assertCaseParty(dispute, actor);
        HearingFlowInstanceEntity instance = ensureStarted(dispute);
        HearingFlowStageEntity answerStage =
                requireStage(instance, HearingFlowStage.PARTY_ANSWERS_OPEN);
        var existing =
                actionRepository.findByStageIdAndActionTypeAndParticipantId(
                        answerStage.getId(), HearingFlowActionType.ANSWER_BUNDLE, actor.actorId());
        if (existing.isPresent()) {
            assertSameStatementRequest(existing.orElseThrow(), request);
            return partyActionView(existing.orElseThrow());
        }

        requireCurrentStage(instance, HearingFlowStage.PARTY_ANSWERS_OPEN);
        expireIfDue(instance, dispute);
        requireCurrentStage(instance, HearingFlowStage.PARTY_ANSWERS_OPEN);
        assertDeadlineOpen(instance);

        HearingFlowActionEntity questionSet =
                requireSystemAction(instance, HearingFlowActionType.QUESTION_SET);
        ObjectNode questionPayload = object(read(questionSet.getPayloadJson()));
        String activeIssueSetId =
                nonBlank(
                        questionPayload.path("issue_set_id").asText(null),
                        questionPayload.path("question_set_id").asText());
        if (!request.issueSetId().equals(activeIssueSetId)) {
            throw invalidArgument("issue_set_id does not identify the active issue set");
        }
        assertUnique(request.sourceMessageIds(), "source_message_id");

        Instant now = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "hearing_party_statement.v1");
        payload.put("issue_set_id", request.issueSetId());
        payload.put("question_set_id", questionPayload.path("question_set_id").asText());
        payload.put("participant_id", actor.actorId());
        payload.put("participant_role", actor.role().name());
        payload.put("submission_status", "SUBMITTED");
        payload.put("submitted_at", now.toString());
        payload.put("statement_text", request.statementText());
        payload.set("source_message_ids", objectMapper.valueToTree(request.sourceMessageIds()));

        HearingFlowActionEntity saved =
                actionRepository.save(
                        HearingFlowActionEntity.partyActionWithSchema(
                                id("HEARING_ACTION_"),
                                instance.getId(),
                                answerStage.getId(),
                                caseId,
                                HearingFlowActionType.ANSWER_BUNDLE,
                                "hearing_party_statement.v1",
                                actor.actorId(),
                                actor.role(),
                                HearingFlowSubmissionStatus.SUBMITTED,
                                json(payload),
                                contentHash(payload),
                                now,
                                actor.actorId()));
        RoomMessageEntity acknowledgement =
                appendPrivatePartyMessage(
                        dispute,
                        answerStage,
                        actor,
                        MessageType.PARTY_TEXT,
                        request.statementText(),
                        objectMapper.createArrayNode(),
                        saved.getId(),
                        now);
        recordPartySubmissionEvent(dispute, answerStage, saved, actor, false);
        afterPartyActionsIfComplete(
                dispute,
                instance,
                answerStage,
                HearingFlowActionType.ANSWER_BUNDLE,
                HearingFlowStage.INTAKE_SYNTHESIZING);
        return partyActionView(saved, acknowledgement);
    }

    @Transactional
    public HearingPartyActionView submitEvidenceBatch(
            String caseId,
            HearingEvidenceBatchRequest request,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute = lockedCase(caseId);
        assertCaseParty(dispute, actor);
        HearingFlowInstanceEntity instance = ensureStarted(dispute);
        HearingFlowStageEntity evidenceStage =
                requireStage(instance, HearingFlowStage.PARTY_EVIDENCE_OPEN);
        var existing =
                actionRepository.findByStageIdAndActionTypeAndParticipantId(
                        evidenceStage.getId(),
                        HearingFlowActionType.EVIDENCE_BATCH,
                        actor.actorId());
        if (existing.isPresent()) {
            assertSameEvidenceRequest(existing.orElseThrow(), request);
            return partyActionView(existing.orElseThrow());
        }

        requireCurrentStage(instance, HearingFlowStage.PARTY_EVIDENCE_OPEN);
        expireIfDue(instance, dispute);
        requireCurrentStage(instance, HearingFlowStage.PARTY_EVIDENCE_OPEN);
        assertDeadlineOpen(instance);

        HearingFlowActionEntity requestSet =
                requireSystemAction(instance, HearingFlowActionType.EVIDENCE_REQUEST_SET);
        ObjectNode requestPayload = object(read(requestSet.getPayloadJson()));
        if (!request.requestSetId().equals(requestPayload.path("request_set_id").asText())) {
            throw invalidArgument("request_set_id does not identify the active evidence request set");
        }
        validateEvidenceRequestIds(requestPayload, request, actor.role());
        List<EvidenceItemEntity> evidence = validateEvidenceIds(caseId, request, actor);

        Instant now = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "hearing_evidence_batch.v1");
        payload.put("request_set_id", request.requestSetId());
        payload.put("participant_id", actor.actorId());
        payload.put("participant_role", actor.role().name());
        payload.put("submission_status", "SUBMITTED");
        payload.put("submitted_at", now.toString());
        payload.set("request_ids", objectMapper.valueToTree(request.requestIds()));
        payload.set("evidence_ids", objectMapper.valueToTree(request.evidenceIds()));
        payload.put("batch_note", request.batchNote());
        String batchId = id("HEARING_BATCH_");
        payload.put("batch_id", batchId);

        HearingFlowActionEntity saved =
                actionRepository.save(
                        HearingFlowActionEntity.partyAction(
                                id("HEARING_ACTION_"),
                                instance.getId(),
                                evidenceStage.getId(),
                                caseId,
                                HearingFlowActionType.EVIDENCE_BATCH,
                                actor.actorId(),
                                actor.role(),
                                HearingFlowSubmissionStatus.SUBMITTED,
                                json(payload),
                                contentHash(payload),
                                now,
                                actor.actorId()));
        markPendingEvidenceSubmitted(evidence, batchId, now, actor.actorId());
        RoomMessageEntity acknowledgement =
                appendPartyMessage(
                        dispute,
                        evidenceStage,
                        actor,
                        MessageType.PARTY_EVIDENCE_REFERENCE,
                        evidenceSummary(actor.role(), request.evidenceIds().size()),
                        objectMapper.valueToTree(request.evidenceIds()),
                        saved.getId(),
                        now);
        recordPartySubmissionEvent(dispute, evidenceStage, saved, actor, true);
        afterPartyActionsIfComplete(
                dispute,
                instance,
                evidenceStage,
                HearingFlowActionType.EVIDENCE_BATCH,
                HearingFlowStage.EVIDENCE_SYNTHESIZING);
        return partyActionView(saved, acknowledgement);
    }

    /** Called by the recovery scheduler; a refresh performs the same durable reconciliation. */
    @Transactional
    public int expireDuePartyStages() {
        int reconciled = 0;
        for (HearingFlowInstanceEntity candidate : instanceRepository.findAll()) {
            if (!canReconcileAgentFailure(candidate.getFlowStatus())) {
                continue;
            }
            HearingFlowInstanceEntity instance =
                    instanceRepository.findByCaseIdForUpdate(candidate.getCaseId()).orElse(null);
            if (instance == null || !canReconcileAgentFailure(instance.getFlowStatus())) {
                continue;
            }
            FulfillmentCaseEntity dispute =
                    caseRepository.findByIdForUpdate(instance.getCaseId()).orElse(null);
            if (dispute != null) {
                boolean recovered = reconcileFailedAgentRun(instance, dispute);
                boolean expired = expireIfDue(instance, dispute);
                if (recovered || expired) {
                    reconciled++;
                }
            }
        }
        return reconciled;
    }

    @Override
    public boolean supports(String operation) {
        return Set.of(
                        "HEARING_INTAKE_QUESTIONS",
                        "HEARING_INTAKE_SYNTHESIS",
                        "HEARING_EVIDENCE_REQUESTS",
                        "HEARING_EVIDENCE_SYNTHESIS",
                        "HEARING_JUDGE_V1",
                        "HEARING_JURY_REVIEW",
                        "HEARING_JUDGE_V2")
                .contains(operation);
    }

    @Override
    @Transactional
    public void finalizeResult(AgentRunFinalizationContext finalization, JsonNode rawResult) {
        HearingFlowStage expectedStage = stageForOperation(finalization.operation());
        HearingFlowInstanceEntity instance =
                instanceRepository
                        .findByCaseIdForUpdate(finalization.caseId())
                        .orElseThrow(() -> new IllegalStateException("hearing flow instance not found"));
        requireCurrentStage(instance, expectedStage);
        HearingFlowStageEntity stage = currentStage(instance);
        if (!finalization.runId().equals(stage.getAgentRunId())) {
            throw new IllegalStateException("AgentRun does not own the active hearing stage");
        }
        assertFinalizationEnvelope(finalization, instance, stage);
        FulfillmentCaseEntity dispute = lockedCase(finalization.caseId());
        HearingStateEntity hearingState = requireHearingState(finalization.caseId());
        ObjectNode result = object(rawResult);

        switch (finalization.operation()) {
            case "HEARING_INTAKE_QUESTIONS" ->
                    finalizeIntakeQuestions(dispute, hearingState, instance, stage, result, finalization);
            case "HEARING_INTAKE_SYNTHESIS" ->
                    finalizeIntakeSynthesis(dispute, hearingState, instance, stage, result, finalization);
            case "HEARING_EVIDENCE_REQUESTS" ->
                    finalizeEvidenceRequests(dispute, hearingState, instance, stage, result, finalization);
            case "HEARING_EVIDENCE_SYNTHESIS" ->
                    finalizeEvidenceSynthesis(dispute, hearingState, instance, stage, result, finalization);
            case "HEARING_JUDGE_V1" ->
                    finalizeJudgeV1(dispute, hearingState, instance, stage, result, finalization);
            case "HEARING_JURY_REVIEW" ->
                    finalizeJuryReview(dispute, hearingState, instance, stage, result, finalization);
            case "HEARING_JUDGE_V2" ->
                    finalizeJudgeV2(dispute, hearingState, instance, stage, result, finalization);
            default -> throw new IllegalArgumentException("unsupported hearing flow operation");
        }
    }

    private HearingFlowInstanceEntity ensureStarted(FulfillmentCaseEntity dispute) {
        var existing = instanceRepository.findByCaseIdForUpdate(dispute.getId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        if (dispute.getCaseStatus() != CaseStatus.HEARING_OPEN
                && dispute.getCaseStatus() != CaseStatus.HEARING) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "hearing flow cannot start from the current case status",
                    Map.of("case_status", dispute.getCaseStatus().name()));
        }
        HearingStateEntity hearingState = ensureHearingState(dispute);
        CaseRoomEntity room = requireHearingRoom(dispute.getId());
        Instant now = clock.instant();
        HearingFlowInstanceEntity instance =
                instanceRepository.save(
                        HearingFlowInstanceEntity.start(
                                id("HEARING_FLOW_"),
                                dispute.getId(),
                                hearingState.getId(),
                                now,
                                SYSTEM_ACTOR));

        ObjectNode caseMatrix = initialCaseMatrix(dispute.getId());
        ObjectNode evidenceDossier = initialEvidenceDossier(dispute.getId(), caseMatrix);
        ObjectNode preparationInput = objectMapper.createObjectNode();
        preparationInput.set("case_fact_matrix", caseMatrix.deepCopy());
        preparationInput.set("evidence_dossier", evidenceDossier.deepCopy());
        HearingFlowStageEntity preparation =
                stageRepository.save(
                        HearingFlowStageEntity.open(
                                id("HEARING_STAGE_"),
                                instance.getId(),
                                dispute.getId(),
                                HearingFlowStage.COURT_PREPARING,
                                1,
                                "SYSTEM",
                                HearingFlowStageStatus.RUNNING,
                                null,
                                json(preparationInput),
                                now,
                                SYSTEM_ACTOR));
        appendMessage(
                room,
                preparation,
                MessageSenderType.SYSTEM,
                "SYSTEM",
                SYSTEM_ACTOR,
                MessageSource.SYSTEM_STAGE_EVENT,
                MessageType.SYSTEM_STAGE_EVENT,
                "法庭正在装载冻结前案情矩阵和证据矩阵。",
                null,
                "prepare",
                now);
        appendMessage(
                room,
                preparation,
                MessageSenderType.AGENT,
                "PRESIDING_JUDGE",
                "presiding-judge-template",
                MessageSource.ROLE_TEMPLATE,
                MessageType.AGENT_MESSAGE,
                "现在开庭。庭前案情与证据材料将依次宣读；本席在庭审卷宗冻结后进入裁决审理。",
                null,
                "judge-opening",
                now);
        preparation.complete(json(preparationInput), now, SYSTEM_ACTOR);
        appendSystemStageMessage(
                dispute,
                preparation,
                "前序案情矩阵和证据矩阵已装载。",
                "prepare-completed",
                now);

        HearingFlowStageEntity caseIntroduction =
                advance(instance, HearingFlowStage.CASE_INTRODUCTION, caseMatrix, now);
        appendSystemStageMessage(
                dispute,
                caseIntroduction,
                "下面请案情接待官介绍庭前案情。",
                "case-introduction-next",
                now);
        appendMessage(
                room,
                caseIntroduction,
                MessageSenderType.AGENT,
                "INTAKE_OFFICER",
                "intake-officer-template",
                MessageSource.ROLE_TEMPLATE,
                MessageType.AGENT_MESSAGE,
                caseIntroductionText(caseMatrix),
                null,
                "case-introduction",
                now);
        caseIntroduction.complete(json(caseMatrix), now, SYSTEM_ACTOR);

        HearingFlowStageEntity evidenceIntroduction =
                advance(instance, HearingFlowStage.EVIDENCE_INTRODUCTION, evidenceDossier, now);
        appendSystemStageMessage(
                dispute,
                evidenceIntroduction,
                "下面请证据书记官介绍庭前证据覆盖情况。",
                "evidence-introduction-next",
                now);
        appendMessage(
                room,
                evidenceIntroduction,
                MessageSenderType.AGENT,
                "EVIDENCE_CLERK",
                "evidence-clerk-template",
                MessageSource.ROLE_TEMPLATE,
                MessageType.AGENT_MESSAGE,
                evidenceIntroductionText(
                        caseMatrix, evidenceDossier.path("fact_evidence_matrix")),
                null,
                "evidence-introduction",
                now);
        evidenceIntroduction.complete(json(evidenceDossier), now, SYSTEM_ACTOR);

        ObjectNode questionRequest =
                intakeQuestionsRequest(dispute, hearingState, instance, caseMatrix);
        HearingFlowStageEntity questionStage =
                advance(instance, HearingFlowStage.INTAKE_QUESTIONS_GENERATING, questionRequest, now);
        appendSystemStageMessage(
                dispute,
                questionStage,
                "案情接待官将根据庭前案情矩阵提出澄清问题。",
                "intake-questions-next",
                now);
        startAgent(dispute, room, questionStage, "HEARING_INTAKE_QUESTIONS", questionRequest);
        return instance;
    }

    private HearingStateEntity ensureHearingState(FulfillmentCaseEntity dispute) {
        return hearingStateRepository
                .findByCaseId(dispute.getId())
                .orElseGet(
                        () -> {
                            String workflowId = dispute.getCurrentWorkflowId();
                            if (workflowId == null || workflowId.isBlank()) {
                                workflowId = "hearing-flow-v2-" + dispute.getId();
                                dispute.attachHearingWorkflow(workflowId, SYSTEM_ACTOR);
                                caseRepository.save(dispute);
                            }
                            return hearingStateRepository.save(
                                    HearingStateEntity.start(
                                            id("HEARING_"),
                                            dispute.getId(),
                                            workflowId,
                                            SYSTEM_ACTOR));
                        });
    }

    private void finalizeIntakeQuestions(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization) {
        validateCommonResult(result, "hearing_intake_questions.v1", hearingState, stage);
        ArrayNode questions = array(result.path("questions"));
        if (questions.isEmpty() || questions.size() > 5) {
            throw new IllegalStateException("hearing question set must contain one to five questions");
        }
        ObjectNode caseMatrix = object(finalization.request().path("case_fact_matrix"));
        Set<String> factIds = caseFactIds(caseMatrix);
        Set<String> questionIds = new HashSet<>();
        Set<String> issueIds = new HashSet<>();
        ArrayNode normalizedQuestions = objectMapper.createArrayNode();
        ArrayNode normalizedIssues = objectMapper.createArrayNode();
        for (JsonNode value : questions) {
            ObjectNode question = object(value);
            String questionId = requiredText(question, "question_id");
            requiredPartyRoles(question);
            if (!questionIds.add(questionId)) {
                throw new IllegalStateException("duplicate hearing question_id");
            }
            ArrayNode referencedFacts = nonEmptyTextArray(question.path("fact_ids"), "fact_ids");
            assertKnownFacts(referencedFacts, factIds);
            String sharedIssueId =
                    nonBlank(question.path("issue_id").asText(null), issueId(referencedFacts));
            if (!issueIds.add(sharedIssueId)) {
                throw new IllegalStateException("duplicate hearing issue_id");
            }
            String questionText = requiredText(question, "question_text");
            String issueStatement =
                    nonBlank(question.path("issue_statement").asText(null), questionText);
            ObjectNode partyPrompts = objectMapper.createObjectNode();
            if (question.path("party_prompts").isObject()) {
                partyPrompts.put("USER", requiredText(question.path("party_prompts"), "USER"));
                partyPrompts.put(
                        "MERCHANT", requiredText(question.path("party_prompts"), "MERCHANT"));
            } else {
                partyPrompts.put("USER", questionText);
                partyPrompts.put("MERCHANT", questionText);
            }
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("question_id", questionId);
            normalized.set("fact_ids", referencedFacts.deepCopy());
            normalized.put("issue_id", sharedIssueId);
            normalized.put("issue_statement", issueStatement);
            normalized.putArray("target_roles").add("USER").add("MERCHANT");
            normalized.put("question_text", questionText);
            normalized.set("party_prompts", partyPrompts.deepCopy());
            normalizedQuestions.add(normalized);

            ObjectNode issue = objectMapper.createObjectNode();
            issue.put("issue_id", sharedIssueId);
            issue.put("issue_statement", issueStatement);
            issue.set("fact_ids", referencedFacts.deepCopy());
            issue.set("party_prompts", partyPrompts.deepCopy());
            normalizedIssues.add(issue);
        }

        Instant now = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "hearing_question_set.v1");
        payload.put(
                "question_set_id",
                stableId(
                        "HEARING_QUESTION_SET_",
                        dispute.getId(),
                        Integer.toString(caseMatrix.path("matrix_version").asInt()),
                        caseMatrix.path("content_hash").asText(),
                        Integer.toString(stage.getStageSequence())));
        payload.put("issue_set_id", payload.path("question_set_id").asText());
        payload.put("case_id", dispute.getId());
        payload.put("case_matrix_version", caseMatrix.path("matrix_version").asInt());
        payload.put("case_matrix_hash", requiredText(caseMatrix, "content_hash"));
        payload.set("questions", normalizedQuestions);
        payload.set("issues", normalizedIssues);
        payload.put("generated_by_agent_run_id", finalization.runId());
        payload.put("created_at", now.toString());
        actionRepository.save(
                HearingFlowActionEntity.agentOutput(
                        id("HEARING_ACTION_"),
                        instance.getId(),
                        stage.getId(),
                        dispute.getId(),
                        HearingFlowActionType.QUESTION_SET,
                        json(payload),
                        contentHash(payload),
                        finalization.runId(),
                        now,
                        SYSTEM_ACTOR));
        appendAgentResultMessage(dispute, stage, result, finalization, "intake-questions", now);
        stage.complete(json(result), now, SYSTEM_ACTOR);
        ObjectNode input = objectMapper.createObjectNode();
        input.put("question_set_id", payload.path("question_set_id").asText());
        input.put("question_set_hash", contentHash(payload));
        HearingFlowStageEntity answersStage =
                advance(
                        instance,
                        HearingFlowStage.PARTY_ANSWERS_OPEN,
                        input,
                        partyDeadline(dispute, now),
                        now);
        appendSystemStageMessage(
                dispute,
                answersStage,
                "案情澄清问题已生成，请双方在统一截止时间前完成回答。",
                "party-answers-open",
                now);
        expireIfDue(instance, dispute);
    }

    private void finalizeIntakeSynthesis(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization) {
        validateCommonResult(result, "hearing_intake_synthesis.v1", hearingState, stage);
        ObjectNode matrix = object(result.path("case_fact_matrix"));
        requireSchema(matrix, "case_fact_matrix.v2");
        verifyEmbeddedHash(matrix, "content_hash");
        ObjectNode sourceMatrix = object(finalization.request().path("case_fact_matrix"));
        validateHearingClarifiedMatrix(matrix, sourceMatrix);
        appendAgentResultMessage(dispute, stage, result, finalization, "intake-synthesis", clock.instant());
        Instant now = clock.instant();
        stage.complete(json(result), now, SYSTEM_ACTOR);
        ObjectNode request = evidenceRequestsRequest(dispute, hearingState, instance, result);
        HearingFlowStageEntity next =
                advance(instance, HearingFlowStage.EVIDENCE_REQUESTS_GENERATING, request, now);
        appendSystemStageMessage(
                dispute,
                next,
                "案情矩阵已更新，证据书记官正在生成针对性补证请求。",
                "evidence-requests-next",
                now);
        startAgent(
                dispute,
                requireHearingRoom(dispute.getId()),
                next,
                "HEARING_EVIDENCE_REQUESTS",
                request);
    }

    private void finalizeEvidenceRequests(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization) {
        validateCommonResult(result, "hearing_evidence_requests.v1", hearingState, stage);
        ArrayNode requests = array(result.path("requests"));
        if (requests.size() > 10) {
            throw new IllegalStateException("hearing evidence request set exceeds ten items");
        }
        ObjectNode caseMatrix = object(finalization.request().path("case_fact_matrix"));
        Set<String> factIds = caseFactIds(caseMatrix);
        Set<String> requestIds = new HashSet<>();
        ArrayNode normalizedRequests = objectMapper.createArrayNode();
        for (JsonNode value : requests) {
            ObjectNode request = object(value);
            String requestId = requiredText(request, "request_id");
            ArrayNode targetRoles = requiredPartyRoles(request);
            if (!requestIds.add(requestId)) {
                throw new IllegalStateException("duplicate hearing evidence request_id");
            }
            ArrayNode referencedFacts = nonEmptyTextArray(request.path("fact_ids"), "fact_ids");
            assertKnownFacts(referencedFacts, factIds);
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("request_id", requestId);
            normalized.set("fact_ids", referencedFacts.deepCopy());
            normalized.set("target_roles", targetRoles);
            normalized.put("requested_material", requiredText(request, "requested_material"));
            normalized.put("verification_goal", requiredText(request, "verification_goal"));
            if (!request.path("required").isBoolean()) {
                throw new IllegalStateException("hearing evidence request required flag is missing");
            }
            normalized.put("required", request.path("required").asBoolean());
            normalizedRequests.add(normalized);
        }

        Instant now = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "hearing_evidence_request_set.v1");
        payload.put(
                "request_set_id",
                stableId(
                        "HEARING_EVIDENCE_REQUEST_SET_",
                        dispute.getId(),
                        caseMatrix.path("content_hash").asText(),
                        Integer.toString(stage.getStageSequence())));
        payload.put("case_id", dispute.getId());
        payload.put("case_matrix_version", caseMatrix.path("matrix_version").asInt());
        payload.put("case_matrix_hash", requiredText(caseMatrix, "content_hash"));
        payload.set("requests", normalizedRequests);
        payload.put("generated_by_agent_run_id", finalization.runId());
        payload.put("created_at", now.toString());
        actionRepository.save(
                HearingFlowActionEntity.agentOutput(
                        id("HEARING_ACTION_"),
                        instance.getId(),
                        stage.getId(),
                        dispute.getId(),
                        HearingFlowActionType.EVIDENCE_REQUEST_SET,
                        json(payload),
                        contentHash(payload),
                        finalization.runId(),
                        now,
                        SYSTEM_ACTOR));
        appendAgentResultMessage(dispute, stage, result, finalization, "evidence-requests", now);
        stage.complete(json(result), now, SYSTEM_ACTOR);
        ObjectNode input = objectMapper.createObjectNode();
        input.put("request_set_id", payload.path("request_set_id").asText());
        input.put("request_set_hash", contentHash(payload));
        HearingFlowStageEntity evidenceStage =
                advance(
                        instance,
                        HearingFlowStage.PARTY_EVIDENCE_OPEN,
                        input,
                        partyDeadline(dispute, now),
                        now);
        appendSystemStageMessage(
                dispute,
                evidenceStage,
                "补证请求已生成，请双方在统一截止时间前完成证据提交。",
                "party-evidence-open",
                now);
        expireIfDue(instance, dispute);
    }

    private void finalizeEvidenceSynthesis(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization) {
        validateCommonResult(result, "hearing_evidence_synthesis.v1", hearingState, stage);
        ObjectNode workingMatrix = object(result.path("fact_evidence_matrix"));
        requireSchema(workingMatrix, "fact_evidence_matrix.v2");
        verifyEmbeddedHash(workingMatrix, "content_hash");
        Instant now = clock.instant();
        appendAgentResultMessage(dispute, stage, result, finalization, "evidence-synthesis", now);
        stage.complete(json(result), now, SYSTEM_ACTOR);

        ObjectNode freezeInput = result.deepCopy();
        ObjectNode frozenMatrix = object(freezeInput.path("fact_evidence_matrix"));
        frozenMatrix.put("matrix_status", "FROZEN");
        frozenMatrix.put("content_hash", hashWithoutField(frozenMatrix, "content_hash"));
        freezeInput.set("fact_evidence_matrix", frozenMatrix);
        HearingFlowStageEntity freezing =
                advance(instance, HearingFlowStage.DOSSIER_FREEZING, freezeInput, now);
        ObjectNode intakeSynthesis = stageOutput(instance, HearingFlowStage.INTAKE_SYNTHESIZING);
        HearingTrialDossierEntity dossier =
                trialDossierService.freeze(
                        dispute.getId(), intakeSynthesis, freezeInput, SYSTEM_ACTOR);
        ObjectNode frozenRef = objectMapper.createObjectNode();
        frozenRef.put("schema_version", dossier.getSchemaVersion());
        frozenRef.put("trial_dossier_id", dossier.getId());
        frozenRef.put("content_hash", dossier.getContentHash());
        frozenRef.put("frozen_at", dossier.getFrozenAt().toString());
        freezing.complete(json(frozenRef), now, SYSTEM_ACTOR);
        appendSystemStageMessage(
                dispute,
                freezing,
                "庭审卷宗已冻结，后续法官与评审团只读取该不可变版本。",
                "dossier-frozen",
                now);

        ObjectNode judgeRequest = judgeV1Request(dispute, hearingState, instance, dossier);
        HearingFlowStageEntity judgeStage =
                advance(instance, HearingFlowStage.JUDGE_V1_GENERATING, judgeRequest, now);
        appendSystemStageMessage(
                dispute,
                judgeStage,
                "庭审卷宗已交付法官，现进入裁决审理。",
                "judge-v1-next",
                now);
        startAgent(
                dispute,
                requireHearingRoom(dispute.getId()),
                judgeStage,
                "HEARING_JUDGE_V1",
                judgeRequest);
    }

    private void finalizeJudgeV1(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization) {
        validateCommonResult(result, "hearing_judge_v1.v1", hearingState, stage);
        HearingTrialDossierEntity dossier = requireFrozenDossier(dispute.getId());
        assertDossierBinding(result, dossier);
        verifyEmbeddedHash(result, "proposal_hash");
        Instant now = clock.instant();
        ObjectNode proposal = canonicalJudgeProposal(result, dossier);
        persistArtifact(
                instance,
                dossier,
                HearingArtifactType.JUDGE_PROPOSAL,
                requiredText(proposal, "proposal_id"),
                requiredText(proposal, "content_hash"),
                proposal,
                null,
                finalization.runId(),
                now);
        appendAgentResultMessage(dispute, stage, result, finalization, "judge-v1", now);
        stage.complete(json(result), now, SYSTEM_ACTOR);
        ObjectNode request = juryReviewRequest(dispute, hearingState, instance, dossier, result);
        HearingFlowStageEntity next =
                advance(instance, HearingFlowStage.JURY_REVIEWING, request, now);
        appendSystemStageMessage(
                dispute,
                next,
                "法官初步裁决意见已形成，现交评审团复核。",
                "jury-review-next",
                now);
        startAgent(
                dispute,
                requireHearingRoom(dispute.getId()),
                next,
                "HEARING_JURY_REVIEW",
                request);
    }

    private void finalizeJuryReview(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization) {
        validateCommonResult(result, "hearing_jury_review.v1", hearingState, stage);
        HearingTrialDossierEntity dossier = requireFrozenDossier(dispute.getId());
        assertDossierBinding(result, dossier);
        ObjectNode judgeV1 = stageOutput(instance, HearingFlowStage.JUDGE_V1_GENERATING);
        if (!requiredText(judgeV1, "proposal_id")
                        .equals(requiredText(result, "reviewed_proposal_id"))
                || !requiredText(judgeV1, "proposal_hash")
                        .equals(requiredText(result, "reviewed_proposal_hash"))) {
            throw new IllegalStateException("jury report is not bound to the persisted V1 proposal");
        }
        verifyEmbeddedHash(result, "review_hash");
        HearingFlowArtifactEntity parent =
                requireArtifact(dispute.getId(), HearingArtifactType.JUDGE_PROPOSAL);
        Instant now = clock.instant();
        ObjectNode report = canonicalJuryReport(result, dossier, parent);
        persistArtifact(
                instance,
                dossier,
                HearingArtifactType.JURY_REVIEW_REPORT,
                requiredText(report, "report_id"),
                requiredText(report, "content_hash"),
                report,
                parent.getId(),
                finalization.runId(),
                now);
        appendAgentResultMessage(dispute, stage, result, finalization, "jury-review", now);
        stage.complete(json(result), now, SYSTEM_ACTOR);
        ObjectNode request =
                judgeV2Request(dispute, hearingState, instance, dossier, judgeV1, result);
        HearingFlowStageEntity next =
                advance(instance, HearingFlowStage.JUDGE_V2_GENERATING, request, now);
        appendSystemStageMessage(
                dispute,
                next,
                "评审团复核完成，法官将据此形成最终裁决草案。",
                "judge-v2-next",
                now);
        startAgent(
                dispute,
                requireHearingRoom(dispute.getId()),
                next,
                "HEARING_JUDGE_V2",
                request);
    }

    private void finalizeJudgeV2(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization) {
        validateCommonResult(result, "hearing_judge_v2.v1", hearingState, stage);
        HearingTrialDossierEntity dossier = requireFrozenDossier(dispute.getId());
        assertDossierBinding(result, dossier);
        ObjectNode judgeV1 = stageOutput(instance, HearingFlowStage.JUDGE_V1_GENERATING);
        ObjectNode jury = stageOutput(instance, HearingFlowStage.JURY_REVIEWING);
        if (!requiredText(judgeV1, "proposal_id")
                        .equals(requiredText(result, "parent_proposal_id"))
                || !requiredText(judgeV1, "proposal_hash")
                        .equals(requiredText(result, "parent_proposal_hash"))
                || !requiredText(jury, "review_id")
                        .equals(requiredText(result, "jury_review_id"))
                || !requiredText(jury, "review_hash")
                        .equals(requiredText(result, "jury_review_hash"))) {
            throw new IllegalStateException("V2 draft is not bound to the persisted V1/review chain");
        }
        if (!result.path("public_message").asText()
                .equals(result.path("draft").path("draft_text").asText())) {
            throw new IllegalStateException("displayed V2 text must equal the persisted draft text");
        }
        verifyEmbeddedHash(result, "judge_v2_hash");
        HearingFlowArtifactEntity parent =
                requireArtifact(dispute.getId(), HearingArtifactType.JURY_REVIEW_REPORT);
        Instant now = clock.instant();
        HearingFlowArtifactEntity proposal =
                requireArtifact(dispute.getId(), HearingArtifactType.JUDGE_PROPOSAL);
        ObjectNode draft = canonicalAdjudicationDraft(result, dossier, proposal, parent);
        persistArtifact(
                instance,
                dossier,
                HearingArtifactType.ADJUDICATION_DRAFT,
                requiredText(draft, "draft_id"),
                requiredText(draft, "content_hash"),
                draft,
                parent.getId(),
                finalization.runId(),
                now);
        persistAdjudicationDraftProjection(
                dispute, hearingState, draft, finalization.runId());
        appendAgentResultMessage(dispute, stage, result, finalization, "judge-v2", now);
        stage.complete(json(result), now, SYSTEM_ACTOR);
        hearingState.complete(true, SYSTEM_ACTOR);
        hearingStateRepository.save(hearingState);
        ObjectNode handoff = objectMapper.createObjectNode();
        handoff.put("judge_v2_id", draft.path("draft_id").asText());
        handoff.put("judge_v2_hash", draft.path("content_hash").asText());
        handoff.put("review_gate_ready", false);
        handoff.put("handoff_status", "SCHEDULED");
        HearingFlowStageEntity humanReview =
                advance(instance, HearingFlowStage.HUMAN_REVIEW_OPEN, handoff, now);
        appendSystemStageMessage(
                dispute,
                humanReview,
                "本庭休庭，裁决草案已原样移交人工审核。",
                "human-review-open",
                now);
        String frozenDraftId = requiredText(draft, "draft_id");
        String frozenDraftHash = requiredText(draft, "content_hash");
        postCommitSideEffects.execute(
                "HEARING_V2_REVIEW_HANDOFF",
                Map.of(
                        "case_id", dispute.getId(),
                        "draft_id", frozenDraftId,
                        "draft_hash", frozenDraftHash),
                () ->
                        reviewHandoffService.handoff(
                                dispute.getId(), frozenDraftId, frozenDraftHash));
    }

    private void persistAdjudicationDraftProjection(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            ObjectNode artifact,
            String agentRunId) {
        ObjectNode content = object(artifact.path("draft"));
        String draftId = requiredText(artifact, "draft_id");
        var existing = adjudicationDraftRepository.findById(draftId);
        if (existing.isPresent()) {
            AdjudicationDraftEntity value = existing.orElseThrow();
            if (!dispute.getId().equals(value.getCaseId())
                    || value.getDraftVersion() != 2
                    || !requiredText(content, "draft_text").equals(value.getDraftText())
                    || !agentRunId.equals(value.getCreatedByAgentRunId())) {
                throw new IdempotencyConflictException(
                        "a different V2 adjudication projection already exists");
            }
            return;
        }
        adjudicationDraftRepository
                .findByCaseIdAndDraftVersion(dispute.getId(), 2)
                .ifPresent(
                        ignored -> {
                            throw new IdempotencyConflictException(
                                    "adjudication draft version 2 already belongs to another artifact");
                        });
        adjudicationDraftRepository.save(
                AdjudicationDraftEntity.create(
                        draftId,
                        dispute.getId(),
                        hearingState.getId(),
                        2,
                        json(content.path("fact_findings")),
                        json(content.path("evidence_assessment")),
                        json(content.path("policy_application")),
                        json(content.path("reviewer_attention")),
                        requiredText(content, "recommended_decision"),
                        new BigDecimal(requiredText(content, "confidence")),
                        requiredText(content, "draft_text"),
                        "PRESIDING_JUDGE",
                        agentRunId,
                        requiredText(content, "draft_status"),
                        SYSTEM_ACTOR));
    }

    private ObjectNode intakeQuestionsRequest(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            ObjectNode caseMatrix) {
        ObjectNode request = flowEnvelope(dispute, hearingState, instance, "INTAKE_QUESTIONS", null);
        request.set("case_fact_matrix", caseMatrix.deepCopy());
        request.put("max_questions", 5);
        request.withArray("source_refs").add(caseMatrix.path("matrix_id").asText());
        return request;
    }

    private ObjectNode intakeSynthesisRequest(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance) {
        ObjectNode request = flowEnvelope(dispute, hearingState, instance, "INTAKE_SYNTHESIS", null);
        HearingFlowActionEntity questionSet =
                requireSystemAction(instance, HearingFlowActionType.QUESTION_SET);
        ObjectNode questionPayload = object(read(questionSet.getPayloadJson()));
        ArrayNode questions = objectMapper.createArrayNode();
        for (JsonNode value : array(questionPayload.path("questions"))) {
            ObjectNode source = object(value);
            ObjectNode item = objectMapper.createObjectNode();
            item.put("question_id", requiredText(source, "question_id"));
            item.set("target_roles", requiredPartyRoles(source));
            item.set("fact_ids", source.path("fact_ids").deepCopy());
            item.put("question_text", requiredText(source, "question_text"));
            putNullable(item, "issue_id", source.path("issue_id").asText(null));
            putNullable(
                    item, "issue_statement", source.path("issue_statement").asText(null));
            if (source.path("party_prompts").isObject()) {
                item.set("party_prompts", source.path("party_prompts").deepCopy());
            }
            questions.add(item);
        }
        request.set("questions", questions);
        ArrayNode partySubmissions =
                partySubmissions(dispute, instance, HearingFlowActionType.ANSWER_BUNDLE, false);
        request.set("party_submissions", partySubmissions);
        ObjectNode sourceMatrix =
                object(
                        read(
                                        requireStage(
                                                        instance,
                                                        HearingFlowStage.INTAKE_QUESTIONS_GENERATING)
                                                .getInputJson())
                                .path("case_fact_matrix"));
        request.set("case_fact_matrix", sourceMatrix.deepCopy());
        ArrayNode refs = request.withArray("source_refs");
        refs.add(questionSet.getId());
        actionRepository
                .findAllByFlowInstanceIdOrderByCreatedAtAsc(instance.getId())
                .stream()
                .filter(item -> item.getActionType() == HearingFlowActionType.ANSWER_BUNDLE)
                .forEach(item -> refs.add(item.getId()));
        return request;
    }

    private ObjectNode evidenceRequestsRequest(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            ObjectNode intakeSynthesis) {
        ObjectNode request = flowEnvelope(dispute, hearingState, instance, "EVIDENCE_REQUESTS", null);
        request.set("case_fact_matrix", intakeSynthesis.path("case_fact_matrix").deepCopy());
        request.set(
                "evidence_dossier",
                initialEvidenceDossier(
                        dispute.getId(), object(intakeSynthesis.path("case_fact_matrix"))));
        request.withArray("source_refs").add(stageId(instance, HearingFlowStage.INTAKE_SYNTHESIZING));
        return request;
    }

    private ObjectNode evidenceSynthesisRequest(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance) {
        ObjectNode request = flowEnvelope(dispute, hearingState, instance, "EVIDENCE_SYNTHESIS", null);
        HearingFlowActionEntity requestSet =
                requireSystemAction(instance, HearingFlowActionType.EVIDENCE_REQUEST_SET);
        ObjectNode requestSetPayload = object(read(requestSet.getPayloadJson()));
        ArrayNode requests = objectMapper.createArrayNode();
        for (JsonNode value : array(requestSetPayload.path("requests"))) {
            ObjectNode source = object(value);
            ObjectNode item = objectMapper.createObjectNode();
            item.put("request_id", requiredText(source, "request_id"));
            item.set("target_roles", requiredPartyRoles(source));
            item.set("fact_ids", source.path("fact_ids").deepCopy());
            item.put("requested_material", requiredText(source, "requested_material"));
            item.put("verification_goal", requiredText(source, "verification_goal"));
            item.put("required", source.path("required").asBoolean(true));
            requests.add(item);
        }
        request.set("requests", requests);
        request.set("party_batches", evidencePartyBatches(dispute, instance));
        ObjectNode intakeSynthesis = stageOutput(instance, HearingFlowStage.INTAKE_SYNTHESIZING);
        ObjectNode caseMatrix = object(intakeSynthesis.path("case_fact_matrix"));
        request.set("case_fact_matrix", caseMatrix.deepCopy());
        request.set(
                "prior_fact_evidence_matrix",
                initialEvidenceDossier(dispute.getId(), caseMatrix)
                        .path("fact_evidence_matrix")
                        .deepCopy());
        ArrayNode refs = request.withArray("source_refs");
        refs.add(requestSet.getId());
        actionRepository
                .findAllByFlowInstanceIdOrderByCreatedAtAsc(instance.getId())
                .stream()
                .filter(item -> item.getActionType() == HearingFlowActionType.EVIDENCE_BATCH)
                .forEach(item -> refs.add(item.getId()));
        return request;
    }

    private ObjectNode judgeV1Request(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingTrialDossierEntity dossier) {
        ObjectNode request = flowEnvelope(dispute, hearingState, instance, "JUDGE_V1", null);
        request.set("trial_dossier", read(dossier.getPayloadJson()).deepCopy());
        request.withArray("source_refs").add(dossier.getId());
        return request;
    }

    private ObjectNode juryReviewRequest(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingTrialDossierEntity dossier,
            ObjectNode judgeV1) {
        ObjectNode request = flowEnvelope(dispute, hearingState, instance, "JURY_REVIEW", null);
        request.set("trial_dossier", read(dossier.getPayloadJson()).deepCopy());
        request.set("judge_v1", judgeV1.deepCopy());
        request.withArray("source_refs").add(dossier.getId()).add(requiredText(judgeV1, "proposal_id"));
        return request;
    }

    private ObjectNode judgeV2Request(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            HearingTrialDossierEntity dossier,
            ObjectNode judgeV1,
            ObjectNode juryReview) {
        ObjectNode request = flowEnvelope(dispute, hearingState, instance, "JUDGE_V2", null);
        request.set("trial_dossier", read(dossier.getPayloadJson()).deepCopy());
        request.set("judge_v1", judgeV1.deepCopy());
        request.set("jury_review", juryReview.deepCopy());
        request.withArray("source_refs")
                .add(dossier.getId())
                .add(requiredText(judgeV1, "proposal_id"))
                .add(requiredText(juryReview, "review_id"));
        return request;
    }

    private ObjectNode flowEnvelope(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            HearingFlowInstanceEntity instance,
            String agentStageCode,
            Instant deadline) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("flow_schema_version", FLOW_SCHEMA);
        request.put("case_id", dispute.getId());
        request.put("workflow_id", hearingState.getWorkflowId());
        request.put("stage_code", agentStageCode);
        request.put("stage_sequence", instance.getStageSequence() + 1);
        if (deadline == null) {
            request.putNull("stage_deadline_at");
        } else {
            request.put("stage_deadline_at", deadline.toString());
        }
        request.putArray("source_refs");
        return request;
    }

    private void startAgent(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            HearingFlowStageEntity stage,
            String operation,
            ObjectNode request) {
        if (isJudgeOperation(operation)
                && trialDossierRepository.findByCaseId(dispute.getId()).isEmpty()) {
            throw new IllegalStateException("judge AgentRun cannot start before trial dossier freeze");
        }
        AgentRunAcceptedView accepted =
                agentRunCoordinator.start(agentCommand(dispute, room, stage, operation, request));
        stage.attachAgentRun(accepted.runId(), clock.instant(), SYSTEM_ACTOR);
    }

    private AgentRunStartCommand agentCommand(
            FulfillmentCaseEntity dispute,
            CaseRoomEntity room,
            HearingFlowStageEntity stage,
            String operation,
            JsonNode request) {
        return new AgentRunStartCommand(
                dispute.getId(),
                room.getId(),
                operation,
                request,
                COURT_AUDIENCE,
                List.of(dispute.getUserId(), dispute.getMerchantId()),
                agentIdempotencyKey(dispute.getId(), stage.getStageSequence(), operation),
                "TRACE_HEARING_FLOW_" + dispute.getId() + "_" + stage.getStageSequence(),
                "REQ_HEARING_FLOW_" + dispute.getId() + "_" + stage.getStageSequence(),
                SYSTEM_ACTOR);
    }

    private static String agentIdempotencyKey(
            String caseId, int stageSequence, String operation) {
        return "hearing-flow:"
                + caseId
                + ":"
                + stageSequence
                + ":"
                + operation.toLowerCase();
    }

    private boolean reconcileFailedAgentRun(
            HearingFlowInstanceEntity instance, FulfillmentCaseEntity dispute) {
        if (!canReconcileAgentFailure(instance.getFlowStatus())) {
            return false;
        }
        HearingFlowStageEntity stage = currentStage(instance);
        boolean resumingTerminalFailure = instance.getFlowStatus() == HearingFlowStatus.FAILED;
        if ((resumingTerminalFailure && stage.getStageStatus() != HearingFlowStageStatus.FAILED)
                || (!resumingTerminalFailure
                        && stage.getStageStatus() != HearingFlowStageStatus.RUNNING)) {
            return false;
        }
        if (stage.getAgentRunId() == null) {
            return false;
        }
        AgentRunEntity failedRun =
                agentRunRepository.findById(stage.getAgentRunId()).orElse(null);
        if (failedRun == null || !"FAILED".equals(failedRun.getRunStatus())) {
            return false;
        }

        String operation = operationForStage(instance.getCurrentStage());
        if (operation == null) {
            return false;
        }
        AgentRunAcceptedView retry =
                agentRunCoordinator.retryInfrastructureFailure(
                        agentCommand(
                                dispute,
                                requireHearingRoom(dispute.getId()),
                                stage,
                                operation,
                                object(read(stage.getInputJson()))));
        if (!failedRun.getId().equals(retry.runId()) && !"FAILED".equals(retry.status())) {
            Instant now = clock.instant();
            if (resumingTerminalFailure) {
                stage.retryFailedAgentRun(
                        failedRun.getId(), retry.runId(), now, SYSTEM_ACTOR);
                instance.resumeFailedAgentStage(stage.getStageCode(), now, SYSTEM_ACTOR);
            } else {
                stage.replaceFailedAgentRun(
                        failedRun.getId(), retry.runId(), now, SYSTEM_ACTOR);
            }
            return true;
        }
        if (resumingTerminalFailure) {
            return false;
        }
        if (!failedRun.getId().equals(retry.runId())) {
            stage.replaceFailedAgentRun(
                    failedRun.getId(), retry.runId(), clock.instant(), SYSTEM_ACTOR);
            failedRun =
                    agentRunRepository
                            .findById(retry.runId())
                            .orElseThrow(
                                    () -> new IllegalStateException("retry AgentRun is missing"));
        }

        Instant now = clock.instant();
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("schema_version", "hearing_flow_failure.v1");
        failure.put("failure_type", "AGENT_RUN_FAILED");
        failure.put("agent_run_id", failedRun.getId());
        failure.put("error_code", nonBlank(failedRun.getErrorCode(), "AGENT_STREAM_FAILED"));
        failure.put("retryable", Boolean.TRUE.equals(failedRun.getErrorRetryable()));
        failure.put("failed_at", now.toString());
        stage.fail(json(failure), now, SYSTEM_ACTOR);
        instance.fail(now, SYSTEM_ACTOR);
        return true;
    }

    private static boolean canReconcileAgentFailure(HearingFlowStatus status) {
        return status == HearingFlowStatus.ACTIVE || status == HearingFlowStatus.FAILED;
    }

    private void afterPartyActionsIfComplete(
            FulfillmentCaseEntity dispute,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            HearingFlowActionType actionType,
            HearingFlowStage synthesisStage) {
        if (!bothPartiesTerminal(dispute, stage.getId(), actionType)) {
            return;
        }
        Instant now = clock.instant();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("completion_reason", "BOTH_PARTIES_TERMINAL");
        output.set(
                "party_statuses",
                objectMapper.valueToTree(partyStatuses(dispute, stage, actionType)));
        output.set(
                "participant_statuses",
                objectMapper.valueToTree(participantStatuses(dispute, stage, actionType)));
        stage.complete(json(output), now, SYSTEM_ACTOR);
        HearingStateEntity hearingState = requireHearingState(dispute.getId());
        ObjectNode request =
                synthesisStage == HearingFlowStage.INTAKE_SYNTHESIZING
                        ? intakeSynthesisRequest(dispute, hearingState, instance)
                        : evidenceSynthesisRequest(dispute, hearingState, instance);
        HearingFlowStageEntity next = advance(instance, synthesisStage, request, now);
        appendSystemStageMessage(
                dispute,
                next,
                synthesisStage == HearingFlowStage.INTAKE_SYNTHESIZING
                        ? "双方回答已封存，案情接待官正在综合更新案情矩阵。"
                        : "双方证据批次已封存，证据书记官正在核验并综合证据矩阵。",
                synthesisStage == HearingFlowStage.INTAKE_SYNTHESIZING
                        ? "intake-synthesis-next"
                        : "evidence-synthesis-next",
                now);
        startAgent(
                dispute,
                requireHearingRoom(dispute.getId()),
                next,
                synthesisStage == HearingFlowStage.INTAKE_SYNTHESIZING
                        ? "HEARING_INTAKE_SYNTHESIS"
                        : "HEARING_EVIDENCE_SYNTHESIS",
                request);
    }

    private boolean expireIfDue(
            HearingFlowInstanceEntity instance, FulfillmentCaseEntity dispute) {
        if (!instance.getCurrentStage().hasSharedPartyDeadline()
                || instance.getSharedDeadlineAt() == null) {
            return false;
        }
        HearingFlowStageEntity stage = currentStage(instance);
        Instant deadline = effectivePartyDeadline(instance, dispute);
        if (deadline.isBefore(instance.getSharedDeadlineAt())) {
            Instant now = clock.instant();
            instance.shortenSharedDeadline(deadline, now, SYSTEM_ACTOR);
            stage.shortenSharedDeadline(deadline, now, SYSTEM_ACTOR);
        }
        if (clock.instant().isBefore(deadline)) {
            return false;
        }
        HearingFlowActionType type =
                instance.getCurrentStage() == HearingFlowStage.PARTY_ANSWERS_OPEN
                        ? HearingFlowActionType.ANSWER_BUNDLE
                        : HearingFlowActionType.EVIDENCE_BATCH;
        for (PartyIdentity party : partyIdentities(dispute)) {
            if (actionRepository
                    .findByStageIdAndActionTypeAndParticipantId(
                            stage.getId(), type, party.participantId())
                    .isPresent()) {
                continue;
            }
            createTimeoutAction(dispute, instance, stage, type, party);
        }
        afterPartyActionsIfComplete(
                dispute,
                instance,
                stage,
                type,
                type == HearingFlowActionType.ANSWER_BUNDLE
                        ? HearingFlowStage.INTAKE_SYNTHESIZING
                        : HearingFlowStage.EVIDENCE_SYNTHESIZING);
        return true;
    }

    private Instant partyDeadline(FulfillmentCaseEntity dispute, Instant stageOpenedAt) {
        Instant stageDeadline = stageOpenedAt.plus(properties.hearingPartyStageWindow());
        if (dispute.getCurrentDeadlineAt() == null) {
            return stageDeadline;
        }
        Instant hearingDeadline = dispute.getCurrentDeadlineAt().toInstant();
        return hearingDeadline.isBefore(stageDeadline) ? hearingDeadline : stageDeadline;
    }

    private Instant effectivePartyDeadline(
            HearingFlowInstanceEntity instance, FulfillmentCaseEntity dispute) {
        Instant sharedDeadline = instance.getSharedDeadlineAt();
        if (dispute.getCurrentDeadlineAt() == null) {
            return sharedDeadline;
        }
        Instant hearingDeadline = dispute.getCurrentDeadlineAt().toInstant();
        return hearingDeadline.isBefore(sharedDeadline) ? hearingDeadline : sharedDeadline;
    }

    private void createTimeoutAction(
            FulfillmentCaseEntity dispute,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage,
            HearingFlowActionType type,
            PartyIdentity party) {
        Instant now = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        String schemaVersion =
                type == HearingFlowActionType.ANSWER_BUNDLE
                        ? "hearing_party_statement.v1"
                        : type.schemaVersion();
        payload.put("schema_version", schemaVersion);
        payload.put("participant_id", party.participantId());
        payload.put("participant_role", party.role().name());
        payload.put("submission_status", "AUTO_TIMEOUT");
        payload.put("submitted_at", now.toString());
        if (type == HearingFlowActionType.ANSWER_BUNDLE) {
            ObjectNode set =
                    object(
                            read(
                                    requireSystemAction(instance, HearingFlowActionType.QUESTION_SET)
                                            .getPayloadJson()));
            payload.put("question_set_id", set.path("question_set_id").asText());
            payload.put(
                    "issue_set_id",
                    nonBlank(
                            set.path("issue_set_id").asText(null),
                            set.path("question_set_id").asText()));
            payload.putNull("statement_text");
            payload.putArray("source_message_ids");
        } else {
            ObjectNode set =
                    object(
                            read(
                                    requireSystemAction(
                                                    instance,
                                                    HearingFlowActionType.EVIDENCE_REQUEST_SET)
                                            .getPayloadJson()));
            payload.put("request_set_id", set.path("request_set_id").asText());
            payload.put("batch_id", id("HEARING_BATCH_"));
            payload.putArray("request_ids");
            payload.putArray("evidence_ids");
            payload.put("batch_note", "");
        }
        HearingFlowActionEntity action =
                actionRepository.save(
                        HearingFlowActionEntity.partyActionWithSchema(
                                id("HEARING_ACTION_"),
                                instance.getId(),
                                stage.getId(),
                                dispute.getId(),
                                type,
                                schemaVersion,
                                party.participantId(),
                                party.role(),
                                HearingFlowSubmissionStatus.AUTO_TIMEOUT,
                                json(payload),
                                contentHash(payload),
                                now,
                                SYSTEM_ACTOR));
        appendMessage(
                requireHearingRoom(dispute.getId()),
                stage,
                MessageSenderType.SYSTEM,
                party.role().name(),
                SYSTEM_ACTOR,
                MessageSource.SYSTEM_STAGE_EVENT,
                MessageType.SYSTEM_STAGE_EVENT,
                party.role().name() + " 未在共享截止时间前提交，系统已记录自动超时终态。",
                null,
                action.getId(),
                now);
    }

    private HearingFlowStageEntity advance(
            HearingFlowInstanceEntity instance,
            HearingFlowStage nextStage,
            JsonNode input,
            Instant now) {
        return advance(instance, nextStage, input, null, now);
    }

    private HearingFlowStageEntity advance(
            HearingFlowInstanceEntity instance,
            HearingFlowStage nextStage,
            JsonNode input,
            Instant deadline,
            Instant now) {
        HearingFlowStage expected = nextStage(instance.getCurrentStage());
        if (expected != nextStage) {
            throw illegalTransition(instance, nextStage);
        }
        int sequence = instance.getStageSequence() + 1;
        instance.advance(nextStage, sequence, deadline, now, SYSTEM_ACTOR);
        return stageRepository.save(
                HearingFlowStageEntity.open(
                        id("HEARING_STAGE_"),
                        instance.getId(),
                        instance.getCaseId(),
                        nextStage,
                        sequence,
                        processorRole(nextStage),
                        nextStage.hasSharedPartyDeadline()
                                ? HearingFlowStageStatus.WAITING_PARTIES
                                : HearingFlowStageStatus.RUNNING,
                        deadline,
                        json(input),
                        now,
                        SYSTEM_ACTOR));
    }

    private HearingFlowView project(
            HearingFlowInstanceEntity instance, FulfillmentCaseEntity dispute) {
        HearingFlowStageEntity stage = currentStage(instance);
        HearingFlowActionType activePartyType =
                instance.getCurrentStage() == HearingFlowStage.PARTY_ANSWERS_OPEN
                        ? HearingFlowActionType.ANSWER_BUNDLE
                        : instance.getCurrentStage() == HearingFlowStage.PARTY_EVIDENCE_OPEN
                                ? HearingFlowActionType.EVIDENCE_BATCH
                                : null;
        Map<String, String> statuses =
                activePartyType == null
                        ? Map.of()
                        : partyStatuses(dispute, stage, activePartyType);
        List<HearingFlowView.ParticipantStatus> participantStatuses =
                activePartyType == null
                        ? List.of()
                        : participantStatuses(dispute, stage, activePartyType);
        HearingFlowArtifactEntity draft =
                artifactRepository
                        .findByCaseIdAndArtifactType(
                                instance.getCaseId(), HearingArtifactType.ADJUDICATION_DRAFT)
                        .orElse(null);
        boolean reviewGateReady = reviewGateReady(instance, draft);
        HearingFlowView.Status status =
                new HearingFlowView.Status(
                        FLOW_SCHEMA,
                        instance.getCurrentStage().name(),
                        instance.getCurrentStage().name(),
                        instance.getStageSequence(),
                        stage.getStageStatus().name(),
                        instance.getFlowStatus().name(),
                        stage.getSharedDeadlineAt(),
                        instance.getSharedDeadlineAt(),
                        statuses,
                        participantStatuses,
                        reviewGateReady,
                        draft == null ? null : draft.getId());
        JsonNode questionSet =
                findSystemAction(instance, HearingFlowActionType.QUESTION_SET)
                        .map(item -> read(item.getPayloadJson()))
                        .orElse(null);
        JsonNode requestSet =
                findSystemAction(instance, HearingFlowActionType.EVIDENCE_REQUEST_SET)
                        .map(item -> read(item.getPayloadJson()))
                        .orElse(null);
        HearingFlowView.Reference dossierRef =
                trialDossierRepository
                        .findByCaseId(instance.getCaseId())
                        .map(
                                item ->
                                        new HearingFlowView.Reference(
                                                item.getId(),
                                                item.getSchemaVersion(),
                                                item.getContentHash()))
                        .orElse(null);
        Map<String, HearingFlowView.Reference> decisions = new LinkedHashMap<>();
        for (HearingArtifactType type : HearingArtifactType.values()) {
            artifactRepository
                    .findByCaseIdAndArtifactType(instance.getCaseId(), type)
                    .ifPresent(
                            item ->
                                    decisions.put(
                                            type.name(),
                                            new HearingFlowView.Reference(
                                                    item.getId(),
                                                    item.getSchemaVersion(),
                                                    item.getContentHash())));
        }
        return new HearingFlowView(status, questionSet, requestSet, dossierRef, decisions);
    }

    private boolean reviewGateReady(
            HearingFlowInstanceEntity instance, HearingFlowArtifactEntity draft) {
        if (draft == null
                || (instance.getCurrentStage() != HearingFlowStage.HUMAN_REVIEW_OPEN
                        && instance.getCurrentStage() != HearingFlowStage.CLOSED)) {
            return false;
        }
        return remedyPlanRepository
                .findFirstByCaseIdOrderByPlanVersionDesc(instance.getCaseId())
                .filter(plan -> draft.getId().equals(plan.getAdjudicationDraftId()))
                .flatMap(
                        plan ->
                                reviewTaskRepository
                                        .findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                                                instance.getCaseId(), plan.getId()))
                .isPresent();
    }

    private void validateAnswers(
            ObjectNode questionSet,
            HearingAnswerBundleRequest request,
            ActorRole role) {
        assertUnique(request.answers().stream().map(HearingAnswerBundleRequest.Answer::questionId).toList(),
                "question_id");
        assertUnique(request.sourceMessageIds(), "source_message_id");
        Set<String> expected = new LinkedHashSet<>();
        for (JsonNode value : array(questionSet.path("questions"))) {
            ObjectNode question = object(value);
            if (targetsRole(question, role)) {
                expected.add(requiredText(question, "question_id"));
            }
        }
        Set<String> supplied =
                new LinkedHashSet<>(
                        request.answers().stream()
                                .map(HearingAnswerBundleRequest.Answer::questionId)
                                .toList());
        if (!supplied.equals(expected)) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "answers must cover every applicable question exactly once",
                    Map.of("expected_question_ids", expected, "supplied_question_ids", supplied));
        }
    }

    private void validateEvidenceRequestIds(
            ObjectNode requestSet,
            HearingEvidenceBatchRequest request,
            ActorRole role) {
        assertUnique(request.requestIds(), "request_id");
        assertUnique(request.evidenceIds(), "evidence_id");
        Set<String> applicable = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode value : array(requestSet.path("requests"))) {
            ObjectNode item = object(value);
            if (!targetsRole(item, role)) {
                continue;
            }
            String id = requiredText(item, "request_id");
            applicable.add(id);
            if (item.path("required").asBoolean(true)) {
                required.add(id);
            }
        }
        Set<String> supplied = new LinkedHashSet<>(request.requestIds());
        if (!applicable.containsAll(supplied) || !supplied.containsAll(required)) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "request_ids must be applicable and include every required request",
                    Map.of(
                            "applicable_request_ids", applicable,
                            "required_request_ids", required,
                            "supplied_request_ids", supplied));
        }
    }

    private List<EvidenceItemEntity> validateEvidenceIds(
            String caseId, HearingEvidenceBatchRequest request, AuthenticatedActor actor) {
        if (request.evidenceIds().isEmpty()) {
            return List.of();
        }
        List<EvidenceItemEntity> values = evidenceItemRepository.findAllById(request.evidenceIds());
        Map<String, EvidenceItemEntity> byId = new LinkedHashMap<>();
        values.forEach(item -> byId.put(item.getId(), item));
        if (byId.size() != request.evidenceIds().size()) {
            throw new BusinessException(
                    ErrorCode.EVIDENCE_NOT_FOUND,
                    "one or more evidence ids were not found",
                    Map.of());
        }
        for (String evidenceId : request.evidenceIds()) {
            EvidenceItemEntity item = byId.get(evidenceId);
            if (!caseId.equals(item.getCaseId())) {
                throw invalidArgument("evidence belongs to another case");
            }
            if (!actor.actorId().equals(item.getSubmittedById())) {
                throw new ForbiddenException("a party may submit only its own evidence batch");
            }
            if (item.getDeletedAt() != null) {
                throw invalidArgument("deleted evidence cannot enter a hearing batch");
            }
        }
        return request.evidenceIds().stream().map(byId::get).toList();
    }

    private void markPendingEvidenceSubmitted(
            List<EvidenceItemEntity> evidence,
            String batchId,
            Instant now,
            String actorId) {
        OffsetDateTime submittedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        for (EvidenceItemEntity item : evidence) {
            if (item.getSubmissionStatus() != EvidenceSubmissionStatus.SUBMITTED) {
                item.markSubmittedForParties(batchId, submittedAt, actorId);
            }
        }
    }

    private void assertSameAnswerRequest(
            HearingFlowActionEntity action, HearingAnswerBundleRequest request) {
        JsonNode payload = read(action.getPayloadJson());
        if (!request.questionSetId().equals(payload.path("question_set_id").asText())
                || !objectMapper.valueToTree(request.answers()).equals(payload.path("answers"))
                || !objectMapper
                        .valueToTree(request.sourceMessageIds())
                        .equals(payload.path("source_message_ids"))) {
            throw new IdempotencyConflictException(
                    "the authenticated party already submitted a different answer bundle");
        }
    }

    private void assertSameStatementRequest(
            HearingFlowActionEntity action, HearingPartyStatementRequest request) {
        JsonNode payload = read(action.getPayloadJson());
        String persistedIssueSetId =
                nonBlank(
                        payload.path("issue_set_id").asText(null),
                        payload.path("question_set_id").asText());
        if (!"hearing_party_statement.v1".equals(action.getSchemaVersion())
                || !request.issueSetId().equals(persistedIssueSetId)
                || !request.statementText().equals(payload.path("statement_text").asText())
                || !objectMapper
                        .valueToTree(request.sourceMessageIds())
                        .equals(payload.path("source_message_ids"))) {
            throw new IdempotencyConflictException(
                    "the authenticated participant already submitted a different statement");
        }
    }

    private void assertSameEvidenceRequest(
            HearingFlowActionEntity action, HearingEvidenceBatchRequest request) {
        JsonNode payload = read(action.getPayloadJson());
        if (!request.requestSetId().equals(payload.path("request_set_id").asText())
                || !objectMapper.valueToTree(request.requestIds()).equals(payload.path("request_ids"))
                || !objectMapper.valueToTree(request.evidenceIds()).equals(payload.path("evidence_ids"))
                || !request.batchNote().equals(payload.path("batch_note").asText())) {
            throw new IdempotencyConflictException(
                    "the authenticated party already submitted a different evidence batch");
        }
    }

    private ArrayNode partySubmissions(
            FulfillmentCaseEntity dispute,
            HearingFlowInstanceEntity instance,
            HearingFlowActionType actionType,
            boolean evidence) {
        HearingFlowStage stageCode =
                evidence ? HearingFlowStage.PARTY_EVIDENCE_OPEN : HearingFlowStage.PARTY_ANSWERS_OPEN;
        HearingFlowStageEntity stage = requireStage(instance, stageCode);
        ArrayNode result = objectMapper.createArrayNode();
        for (PartyIdentity party : partyIdentities(dispute)) {
            HearingFlowActionEntity action =
                    actionRepository
                            .findByStageIdAndActionTypeAndParticipantId(
                                    stage.getId(), actionType, party.participantId())
                            .orElseThrow(() -> new IllegalStateException("party terminal action missing"));
            ObjectNode item = objectMapper.createObjectNode();
            item.put("participant_id", party.participantId());
            item.put("participant_role", party.role().name());
            item.put(
                    "terminal_status",
                    action.getSubmissionStatus() == HearingFlowSubmissionStatus.SUBMITTED
                            ? "COMPLETED"
                            : "TIMED_OUT");
            item.put(
                    "submission_source",
                    action.getSubmissionStatus() == HearingFlowSubmissionStatus.SUBMITTED
                            ? "PARTY_ACTION"
                            : "AUTO_TIMEOUT");
            item.putArray("source_refs").add(action.getId());
            JsonNode submission = read(action.getPayloadJson());
            if (submission.path("statement_text").isTextual()) {
                item.put("statement_text", submission.path("statement_text").asText());
            }
            item.set("submission", submission);
            result.add(item);
        }
        return result;
    }

    private ArrayNode evidencePartyBatches(
            FulfillmentCaseEntity dispute, HearingFlowInstanceEntity instance) {
        HearingFlowStageEntity stage =
                requireStage(instance, HearingFlowStage.PARTY_EVIDENCE_OPEN);
        ArrayNode result = objectMapper.createArrayNode();
        for (PartyIdentity party : partyIdentities(dispute)) {
            HearingFlowActionEntity action =
                    actionRepository
                            .findByStageIdAndActionTypeAndParticipantId(
                                    stage.getId(),
                                    HearingFlowActionType.EVIDENCE_BATCH,
                                    party.participantId())
                            .orElseThrow(() -> new IllegalStateException("party evidence terminal action missing"));
            ObjectNode payload = object(read(action.getPayloadJson()));
            ObjectNode batch = objectMapper.createObjectNode();
            batch.put("participant_role", party.role().name());
            batch.put(
                    "terminal_status",
                    action.getSubmissionStatus() == HearingFlowSubmissionStatus.SUBMITTED
                            ? "COMPLETED"
                            : "TIMED_OUT");
            batch.put(
                    "submission_source",
                    action.getSubmissionStatus() == HearingFlowSubmissionStatus.SUBMITTED
                            ? "PARTY_ACTION"
                            : "AUTO_TIMEOUT");
            batch.put("batch_id", requiredText(payload, "batch_id"));
            batch.set("request_ids", array(payload.path("request_ids")).deepCopy());
            batch.put(
                    "batch_note",
                    nonBlank(payload.path("batch_note").asText(null), "未提交补充说明。"));
            batch.putArray("source_refs").add(action.getId());
            ArrayNode evidence = batch.putArray("evidence");
            for (JsonNode evidenceId : array(payload.path("evidence_ids"))) {
                EvidenceItemEntity item =
                        evidenceItemRepository
                                .findById(evidenceId.asText())
                                .orElseThrow(() -> new IllegalStateException("batch evidence not found"));
                evidence.add(evidenceFile(item));
            }
            result.add(batch);
        }
        return result;
    }

    private ObjectNode evidenceFile(EvidenceItemEntity item) {
        ObjectNode file = objectMapper.createObjectNode();
        file.put("evidence_id", item.getId());
        file.put("evidence_type", nonBlank(item.getEvidenceType(), "OTHER"));
        file.put("source_type", nonBlank(item.getSourceType(), "UNKNOWN"));
        putNullable(file, "original_filename", item.getOriginalFilename());
        putNullable(file, "file_hash", item.getFileHash());
        putNullable(file, "parsed_text", item.getParsedText());
        ObjectNode metadata = parseObjectOrEmpty(item.getMetadataJson());
        putNullable(file, "claimed_fact", metadata.path("claimed_fact").asText(null));
        file.set("metadata", metadata);
        return file;
    }

    private ObjectNode initialCaseMatrix(String caseId) {
        CaseIntakeDossierEntity dossier =
                intakeDossierRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.INTAKE)
                        .orElseThrow(() -> new IllegalStateException("case intake dossier is required"));
        ObjectNode root = object(read(dossier.getDossierJson()));
        ObjectNode matrix = object(root.path("case_fact_matrix"));
        requireSchema(matrix, "case_fact_matrix.v2");
        if (!caseId.equals(requiredText(matrix, "case_id"))) {
            throw new IllegalStateException("case fact matrix belongs to another case");
        }
        verifyEmbeddedHash(matrix, "content_hash");
        return matrix.deepCopy();
    }

    private static String caseIntroductionText(ObjectNode caseMatrix) {
        List<String> lines = new ArrayList<>();
        lines.add("现宣读庭前双方案情汇总：");

        JsonNode overview = caseMatrix.path("case_overview");
        appendSummaryLine(lines, "案情概览", overview.path("neutral_summary").asText(null));
        appendSummaryLine(lines, "核心争议", overview.path("core_conflict").asText(null));

        JsonNode claims = caseMatrix.path("claims");
        JsonNode initiatorClaim = claims.path("initiator_claim");
        appendSummaryLine(
                lines,
                roleDisplay(initiatorClaim.path("initiator_role").asText()),
                initiatorClaim.path("position_summary").asText(null));
        JsonNode respondentClaim = claims.path("respondent_direct");
        if (respondentClaim.isObject()) {
            appendSummaryLine(
                    lines,
                    roleDisplay(respondentClaim.path("respondent_role").asText()),
                    respondentClaim.path("position_summary").asText(null));
        }

        ArrayNode factRows = array(caseMatrix.path("fact_rows"));
        if (!factRows.isEmpty()) {
            lines.add("争议事实：");
            int limit = Math.min(factRows.size(), 8);
            for (int index = 0; index < limit; index++) {
                JsonNode row = factRows.get(index);
                String target = nonBlank(row.path("fact_target").asText(null), "待确认事实");
                JsonNode positions = row.path("positions");
                String user = stanceDisplay(positions.path("USER").path("stance").asText());
                String merchant =
                        stanceDisplay(positions.path("MERCHANT").path("stance").asText());
                String resolution =
                        row.path("requires_resolution").asBoolean(false) ? "，待庭审核实" : "";
                lines.add(
                        (index + 1)
                                + ". "
                                + target
                                + "（用户："
                                + user
                                + "；商家："
                                + merchant
                                + resolution
                                + "）");
            }
            if (factRows.size() > limit) {
                lines.add("另有 " + (factRows.size() - limit) + " 项事实已收入案情矩阵。");
            }
        }
        return String.join("\n", lines);
    }

    private static String evidenceIntroductionText(
            ObjectNode caseMatrix, JsonNode evidenceMatrix) {
        Map<String, String> factTargets = new LinkedHashMap<>();
        for (JsonNode row : array(caseMatrix.path("fact_rows"))) {
            factTargets.put(
                    row.path("fact_id").asText(),
                    nonBlank(row.path("fact_target").asText(null), "待确认事实"));
        }

        ArrayNode coverage = array(evidenceMatrix.path("fact_coverage"));
        long covered = 0;
        long partial = 0;
        long uncovered = 0;
        long review = 0;
        for (JsonNode row : coverage) {
            switch (row.path("coverage_status").asText()) {
                case "COVERED_BY_SUBMITTED_EVIDENCE", "COVERED_BY_FROZEN_DOSSIER" -> covered++;
                case "PARTIALLY_COVERED_BY_FROZEN_DOSSIER" -> partial++;
                case "REQUIRES_HUMAN_REVIEW" -> review++;
                default -> uncovered++;
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("现宣读庭前证据覆盖汇总：");
        lines.add(
                "共核对 "
                        + coverage.size()
                        + " 项事实：已覆盖 "
                        + covered
                        + " 项，部分覆盖 "
                        + partial
                        + " 项，待补充 "
                        + uncovered
                        + " 项，需人工复核 "
                        + review
                        + " 项。");
        int limit = Math.min(coverage.size(), 8);
        for (int index = 0; index < limit; index++) {
            JsonNode row = coverage.get(index);
            String target =
                    nonBlank(
                            factTargets.get(row.path("fact_id").asText()),
                            "第 " + (index + 1) + " 项待确认事实");
            lines.add(
                    (index + 1)
                            + ". "
                            + target
                            + "："
                            + coverageDisplay(row.path("coverage_status").asText()));
        }
        if (coverage.size() > limit) {
            lines.add("另有 " + (coverage.size() - limit) + " 项覆盖情况已收入证据矩阵。");
        }
        return String.join("\n", lines);
    }

    private static void appendSummaryLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + "：" + value);
        }
    }

    private static String roleDisplay(String role) {
        return "MERCHANT".equals(role) ? "商家主张" : "用户主张";
    }

    private static String stanceDisplay(String stance) {
        return switch (stance) {
            case "CONFIRM", "AGREE", "ACCEPT" -> "确认";
            case "DENY", "DISAGREE", "REJECT" -> "否认";
            case "PARTIAL", "PARTIALLY_AGREE" -> "部分认可";
            default -> "未回应";
        };
    }

    private static String coverageDisplay(String status) {
        return switch (status) {
            case "COVERED_BY_SUBMITTED_EVIDENCE", "COVERED_BY_FROZEN_DOSSIER" -> "已有证据覆盖";
            case "PARTIALLY_COVERED_BY_FROZEN_DOSSIER" -> "部分证据覆盖";
            case "REQUIRES_HUMAN_REVIEW" -> "需人工复核";
            default -> "尚待补充证据";
        };
    }

    private ObjectNode initialEvidenceDossier(String caseId, ObjectNode caseMatrix) {
        EvidenceDossierEntity dossier =
                evidenceDossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .orElseThrow(() -> new IllegalStateException("frozen evidence dossier is required"));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("dossier_id", dossier.getId());
        result.put("dossier_version", dossier.getDossierVersion());
        result.put("dossier_status", "FROZEN");
        ObjectNode matrixRoot = parseObjectOrEmpty(dossier.getMatrixSummaryJson());
        JsonNode candidate = matrixRoot.path("fact_evidence_matrix");
        ObjectNode matrix =
                candidate.isObject()
                                && "fact_evidence_matrix.v2"
                                        .equals(candidate.path("schema_version").asText())
                        ? object(candidate).deepCopy()
                        : legacyEvidenceMatrix(caseId, dossier, caseMatrix, candidate);
        result.set("fact_evidence_matrix", matrix);
        result.set("evidence_summary", parseObjectOrEmpty(dossier.getSummaryJson()));
        JsonNode gaps = result.path("evidence_summary").path("evidence_gaps");
        result.set("evidence_gaps", gaps.isArray() ? gaps.deepCopy() : objectMapper.createArrayNode());
        return result;
    }

    private ObjectNode legacyEvidenceMatrix(
            String caseId,
            EvidenceDossierEntity dossier,
            ObjectNode caseMatrix,
            JsonNode legacyRows) {
        Set<String> knownFacts = caseFactIds(caseMatrix);
        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.put("schema_version", "fact_evidence_matrix.v2");
        matrix.put(
                "matrix_id",
                stableId(
                        "FACT_EVIDENCE_MATRIX_",
                        caseId,
                        Integer.toString(dossier.getDossierVersion()),
                        dossier.getId()));
        matrix.put("case_id", caseId);
        matrix.put("matrix_version", dossier.getDossierVersion());
        matrix.put("matrix_status", "FROZEN");
        matrix.putNull("parent_ref");
        matrix.put("case_fact_matrix_id", requiredText(caseMatrix, "matrix_id"));
        matrix.put("case_fact_matrix_version", caseMatrix.path("matrix_version").asInt());
        matrix.put("case_fact_matrix_hash", requiredText(caseMatrix, "content_hash"));
        matrix.putArray("source_refs").add(dossier.getId());
        ArrayNode links = matrix.putArray("links");
        Map<String, ObjectNode> legacyByFact = new LinkedHashMap<>();
        if (legacyRows.isArray()) {
            for (JsonNode value : legacyRows) {
                if (value.isObject() && knownFacts.contains(value.path("fact_id").asText())) {
                    legacyByFact.put(value.path("fact_id").asText(), object(value));
                }
            }
        }
        for (ObjectNode row : legacyByFact.values()) {
            appendLegacyLinks(links, row, "supporting_evidence", "SUPPORTS", 0.8);
            appendLegacyLinks(links, row, "opposing_evidence", "OPPOSES", 0.8);
            appendLegacyLinks(links, row, "inconclusive_evidence", "INCONCLUSIVE", 0.5);
        }
        ArrayNode coverage = matrix.putArray("fact_coverage");
        for (String factId : knownFacts) {
            ObjectNode row = legacyByFact.get(factId);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            boolean review = false;
            if (row != null) {
                for (String field :
                        List.of("supporting_evidence", "opposing_evidence", "inconclusive_evidence")) {
                    for (JsonNode id : array(row.path(field))) {
                        if (id.isTextual() && !id.asText().isBlank()) {
                            ids.add(id.asText());
                        }
                    }
                }
                review = row.path("requires_human_review").asBoolean(false);
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("fact_id", factId);
            item.put(
                    "coverage_status",
                    review
                            ? "REQUIRES_HUMAN_REVIEW"
                            : ids.isEmpty()
                                    ? "NOT_COVERED_BY_FROZEN_DOSSIER"
                                    : "COVERED_BY_FROZEN_DOSSIER");
            item.set("evidence_ids", objectMapper.valueToTree(ids));
            item.put(
                    "note",
                    ids.isEmpty()
                            ? "该事实尚未被庭前冻结证据卷宗覆盖。"
                            : "该事实的覆盖状态来自庭前冻结证据卷宗。"
                                    + (review ? "关联材料仍需人工复核。" : ""));
            coverage.add(item);
        }
        matrix.put("content_hash", hashWithoutField(matrix, "content_hash"));
        return matrix;
    }

    private void appendLegacyLinks(
            ArrayNode target,
            ObjectNode row,
            String field,
            String relation,
            double confidence) {
        for (JsonNode evidenceId : array(row.path(field))) {
            if (!evidenceId.isTextual() || evidenceId.asText().isBlank()) {
                continue;
            }
            ObjectNode link = objectMapper.createObjectNode();
            link.put("fact_id", row.path("fact_id").asText());
            link.put("evidence_id", evidenceId.asText());
            link.put("relation", relation);
            link.put("reason", "由庭前冻结证据矩阵兼容投影生成。");
            link.put("confidence", confidence);
            link.putNull("source_batch_id");
            target.add(link);
        }
    }

    private ObjectNode canonicalJudgeProposal(
            ObjectNode result, HearingTrialDossierEntity dossier) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "judge_proposal.v1");
        payload.put("proposal_id", requiredText(result, "proposal_id"));
        payload.put("trial_dossier_id", dossier.getId());
        payload.put("trial_dossier_hash", dossier.getContentHash());
        payload.put("proposal_text", requiredText(result, "proposal_text"));
        payload.put("recommended_decision", requiredText(result, "recommended_decision"));
        payload.put("reasoning_summary", requiredText(result, "reasoning_summary"));
        payload.set("review_focus", result.path("review_focus").deepCopy());
        payload.put("public_text", requiredText(result, "public_message"));
        payload.put("content_hash", hashWithoutField(payload, "content_hash"));
        return payload;
    }

    private ObjectNode canonicalJuryReport(
            ObjectNode result,
            HearingTrialDossierEntity dossier,
            HearingFlowArtifactEntity proposal) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "jury_review_report.v1");
        payload.put("report_id", requiredText(result, "review_id"));
        payload.put("trial_dossier_id", dossier.getId());
        payload.put("trial_dossier_hash", dossier.getContentHash());
        payload.put("proposal_id", proposal.getId());
        payload.put("proposal_content_hash", proposal.getContentHash());
        payload.set("findings", result.path("findings").deepCopy());
        payload.set("mandatory_revisions", result.path("mandatory_revisions").deepCopy());
        payload.put("public_text", requiredText(result, "public_message"));
        payload.put("content_hash", hashWithoutField(payload, "content_hash"));
        return payload;
    }

    private ObjectNode canonicalAdjudicationDraft(
            ObjectNode result,
            HearingTrialDossierEntity dossier,
            HearingFlowArtifactEntity proposal,
            HearingFlowArtifactEntity report) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", "adjudication_draft.v2");
        payload.put("draft_id", requiredText(result, "judge_v2_id"));
        payload.put("trial_dossier_id", dossier.getId());
        payload.put("trial_dossier_hash", dossier.getContentHash());
        payload.put("proposal_id", proposal.getId());
        payload.put("proposal_content_hash", proposal.getContentHash());
        payload.put("report_id", report.getId());
        payload.put("report_content_hash", report.getContentHash());
        payload.set("draft", result.path("draft").deepCopy());
        payload.put("public_text", requiredText(result, "public_message"));
        payload.put("content_hash", hashWithoutField(payload, "content_hash"));
        return payload;
    }

    private void persistArtifact(
            HearingFlowInstanceEntity instance,
            HearingTrialDossierEntity dossier,
            HearingArtifactType type,
            String artifactId,
            String artifactHash,
            ObjectNode payload,
            String parentArtifactId,
            String agentRunId,
            Instant now) {
        var existing = artifactRepository.findByCaseIdAndArtifactType(instance.getCaseId(), type);
        if (existing.isPresent()) {
            HearingFlowArtifactEntity value = existing.orElseThrow();
            if (!value.getId().equals(artifactId)
                    || !value.getContentHash().equals(artifactHash)
                    || !value.getAgentRunId().equals(agentRunId)) {
                throw new IdempotencyConflictException(
                        "a different decision-chain artifact is already frozen");
            }
            return;
        }
        HearingFlowArtifactEntity entity =
                switch (type) {
                    case JUDGE_PROPOSAL ->
                            HearingFlowArtifactEntity.judgeProposal(
                                    artifactId,
                                    instance.getCaseId(),
                                    instance.getId(),
                                    dossier.getId(),
                                    dossier.getContentHash(),
                                    artifactHash,
                                    json(payload),
                                    agentRunId,
                                    now,
                                    SYSTEM_ACTOR);
                    case JURY_REVIEW_REPORT -> {
                        HearingFlowArtifactEntity proposal =
                                requireArtifact(
                                        instance.getCaseId(), HearingArtifactType.JUDGE_PROPOSAL);
                        yield HearingFlowArtifactEntity.juryReviewReport(
                                artifactId,
                                instance.getCaseId(),
                                instance.getId(),
                                dossier.getId(),
                                dossier.getContentHash(),
                                proposal.getId(),
                                proposal.getContentHash(),
                                artifactHash,
                                json(payload),
                                agentRunId,
                                now,
                                SYSTEM_ACTOR);
                    }
                    case ADJUDICATION_DRAFT -> {
                        HearingFlowArtifactEntity proposal =
                                requireArtifact(
                                        instance.getCaseId(), HearingArtifactType.JUDGE_PROPOSAL);
                        HearingFlowArtifactEntity report =
                                requireArtifact(
                                        instance.getCaseId(),
                                        HearingArtifactType.JURY_REVIEW_REPORT);
                        yield HearingFlowArtifactEntity.adjudicationDraft(
                                artifactId,
                                instance.getCaseId(),
                                instance.getId(),
                                dossier.getId(),
                                dossier.getContentHash(),
                                proposal.getId(),
                                proposal.getContentHash(),
                                report.getId(),
                                report.getContentHash(),
                                artifactHash,
                                json(payload),
                                agentRunId,
                                now,
                                SYSTEM_ACTOR);
                    }
                };
        artifactRepository.save(entity);
    }

    private void appendAgentResultMessage(
            FulfillmentCaseEntity dispute,
            HearingFlowStageEntity stage,
            ObjectNode result,
            AgentRunFinalizationContext finalization,
            String suffix,
            Instant now) {
        String role =
                switch (finalization.operation()) {
                    case "HEARING_INTAKE_QUESTIONS", "HEARING_INTAKE_SYNTHESIS" ->
                            "INTAKE_OFFICER";
                    case "HEARING_EVIDENCE_REQUESTS", "HEARING_EVIDENCE_SYNTHESIS" ->
                            "EVIDENCE_CLERK";
                    case "HEARING_JURY_REVIEW" -> "JURY_PANEL";
                    case "HEARING_JUDGE_V1", "HEARING_JUDGE_V2" -> "PRESIDING_JUDGE";
                    default -> throw new IllegalArgumentException("unknown hearing speaker");
                };
        MessageType type =
                "HEARING_JURY_REVIEW".equals(finalization.operation())
                        ? MessageType.JURY_REVIEW_REPORT
                        : MessageType.AGENT_MESSAGE;
        appendMessage(
                requireHearingRoom(dispute.getId()),
                stage,
                MessageSenderType.AGENT,
                role,
                role.toLowerCase(),
                MessageSource.AGENT_LLM,
                type,
                requiredText(result, "public_message"),
                finalization.runId(),
                suffix,
                now);
    }

    private void appendSystemStageMessage(
            FulfillmentCaseEntity dispute,
            HearingFlowStageEntity stage,
            String text,
            String suffix,
            Instant now) {
        appendMessage(
                requireHearingRoom(dispute.getId()),
                stage,
                MessageSenderType.SYSTEM,
                "SYSTEM",
                SYSTEM_ACTOR,
                MessageSource.SYSTEM_STAGE_EVENT,
                MessageType.SYSTEM_STAGE_EVENT,
                text,
                null,
                suffix,
                now);
    }

    private RoomMessageEntity appendPartyMessage(
            FulfillmentCaseEntity dispute,
            HearingFlowStageEntity stage,
            AuthenticatedActor actor,
            MessageType type,
            String text,
            JsonNode attachmentRefs,
            String suffix,
            Instant now) {
        return appendMessage(
                requireHearingRoom(dispute.getId()),
                stage,
                MessageSenderType.PARTY,
                actor.role().name(),
                actor.actorId(),
                MessageSource.PARTY_ACTION,
                type,
                text,
                null,
                suffix,
                attachmentRefs,
                now);
    }

    private RoomMessageEntity appendPrivatePartyMessage(
            FulfillmentCaseEntity dispute,
            HearingFlowStageEntity stage,
            AuthenticatedActor actor,
            MessageType type,
            String text,
            JsonNode attachmentRefs,
            String suffix,
            Instant now) {
        return appendMessage(
                requireHearingRoom(dispute.getId()),
                stage,
                MessageSenderType.PARTY,
                actor.role().name(),
                actor.actorId(),
                MessageSource.PARTY_ACTION,
                type,
                text,
                null,
                suffix,
                attachmentRefs,
                List.of(),
                List.of(actor.actorId()),
                now);
    }

    private RoomMessageEntity appendMessage(
            CaseRoomEntity room,
            HearingFlowStageEntity stage,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            MessageSource source,
            MessageType type,
            String text,
            String agentRunId,
            String suffix,
            Instant now) {
        return appendMessage(
                room,
                stage,
                senderType,
                senderRole,
                senderId,
                source,
                type,
                text,
                agentRunId,
                suffix,
                objectMapper.createArrayNode(),
                now);
    }

    private RoomMessageEntity appendMessage(
            CaseRoomEntity room,
            HearingFlowStageEntity stage,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            MessageSource source,
            MessageType type,
            String text,
            String agentRunId,
            String suffix,
            JsonNode attachmentRefs,
            Instant now) {
        return appendMessage(
                room,
                stage,
                senderType,
                senderRole,
                senderId,
                source,
                type,
                text,
                agentRunId,
                suffix,
                attachmentRefs,
                COURT_AUDIENCE,
                List.of(),
                now);
    }

    private RoomMessageEntity appendMessage(
            CaseRoomEntity room,
            HearingFlowStageEntity stage,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            MessageSource source,
            MessageType type,
            String text,
            String agentRunId,
            String suffix,
            JsonNode attachmentRefs,
            List<String> audienceRoles,
            List<String> audienceActorIds,
            Instant now) {
        String key =
                "hearing-v2:"
                        + stage.getStageSequence()
                        + ":"
                        + suffix;
        var existing = messageRepository.findByCaseIdAndIdempotencyKey(stage.getCaseId(), key);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        RoomMessageEntity message =
                RoomMessageEntity.create(
                        id("MESSAGE_"),
                        stage.getCaseId(),
                        room.getId(),
                        messageRepository.findMaxSequenceByRoomId(room.getId()) + 1,
                        senderType,
                        senderRole,
                        senderId,
                        json(audienceRoles),
                        json(audienceActorIds),
                        source,
                        type,
                        text,
                        json(attachmentRefs),
                        key,
                        now,
                        "TRACE_HEARING_FLOW_"
                                + stage.getCaseId()
                                + "_"
                                + stage.getStageSequence());
        if (agentRunId != null) {
            message.attachAgentRun(agentRunId);
        }
        RoomMessageEntity saved = messageRepository.save(message);
        caseEventService.recordRoomMessage(
                saved.getCaseId(),
                saved.getRoomId(),
                saved.getId(),
                saved.getMessageText(),
                saved.getAudienceJson(),
                saved.getAudienceActorIdsJson(),
                senderId);
        return saved;
    }

    private void recordPartySubmissionEvent(
            FulfillmentCaseEntity dispute,
            HearingFlowStageEntity stage,
            HearingFlowActionEntity action,
            AuthenticatedActor actor,
            boolean evidenceBatch) {
        caseEventService.recordLifecycleEvent(
                dispute.getId(),
                requireHearingRoom(dispute.getId()).getId(),
                evidenceBatch
                        ? "HEARING_EVIDENCE_BATCH_SUBMITTED"
                        : "HEARING_ANSWER_BUNDLE_SUBMITTED",
                Map.of(
                        "stage_code", stage.getStageCode().name(),
                        "participant_id", actor.actorId(),
                        "participant_role", actor.role().name(),
                        "submission_status", "SUBMITTED"),
                "hearing-party-submission:" + action.getId(),
                actor.actorId());
    }

    private HearingPartyActionView partyActionView(HearingFlowActionEntity action) {
        return partyActionView(action, null);
    }

    private HearingPartyActionView partyActionView(
            HearingFlowActionEntity action, RoomMessageEntity message) {
        ObjectNode payload = object(read(action.getPayloadJson()));
        Instant submittedAt = Instant.parse(requiredText(payload, "submitted_at"));
        HearingPartyActionView.RoomMessageAcknowledgement acknowledgement =
                message == null
                        ? null
                        : new HearingPartyActionView.RoomMessageAcknowledgement(
                                message.getId(),
                                message.getSequenceNo(),
                                message.getSenderRole(),
                                message.getMessageType().name(),
                                message.getMessageText(),
                                read(message.getAttachmentRefsJson()),
                                message.getCreatedAt());
        return new HearingPartyActionView(
                action.getId(),
                action.getSchemaVersion(),
                action.getParticipantId(),
                action.getParticipantRole().name(),
                action.getSubmissionStatus().name(),
                submittedAt,
                payload,
                acknowledgement);
    }

    private void validateCommonResult(
            ObjectNode result,
            String schema,
            HearingStateEntity hearingState,
            HearingFlowStageEntity stage) {
        requireSchema(result, schema);
        if (!stage.getCaseId().equals(requiredText(result, "case_id"))
                || !hearingState.getWorkflowId().equals(requiredText(result, "workflow_id"))
                || stage.getStageSequence() != result.path("stage_sequence").asInt(-1)) {
            throw new IllegalStateException("hearing Agent result belongs to another stage");
        }
        requiredText(result, "public_message");
    }

    private void assertFinalizationEnvelope(
            AgentRunFinalizationContext finalization,
            HearingFlowInstanceEntity instance,
            HearingFlowStageEntity stage) {
        JsonNode request = finalization.request();
        if (!FLOW_SCHEMA.equals(request.path("flow_schema_version").asText())
                || !instance.getCaseId().equals(request.path("case_id").asText())
                || stage.getStageSequence() != request.path("stage_sequence").asInt(-1)) {
            throw new IllegalStateException("persisted Agent request does not match the active stage");
        }
    }

    private void assertDossierBinding(ObjectNode result, HearingTrialDossierEntity dossier) {
        if (!dossier.getId().equals(requiredText(result, "trial_dossier_id"))
                || !dossier.getContentHash().equals(requiredText(result, "trial_dossier_hash"))) {
            throw new IllegalStateException("decision artifact is not bound to the frozen dossier");
        }
    }

    private void verifyEmbeddedHash(ObjectNode payload, String hashField) {
        String expected = requiredText(payload, hashField);
        String actual = hashWithoutField(payload, hashField);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(hashField + " is invalid");
        }
    }

    private void validateHearingClarifiedMatrix(
            ObjectNode clarified, ObjectNode sourceMatrix) {
        requireSchema(sourceMatrix, "case_fact_matrix.v2");
        verifyEmbeddedHash(sourceMatrix, "content_hash");
        if (!requiredText(sourceMatrix, "case_id").equals(requiredText(clarified, "case_id"))
                || !"HEARING_CLARIFIED_FROZEN"
                        .equals(requiredText(clarified, "matrix_kind"))) {
            throw new IllegalStateException("hearing clarified matrix identity is invalid");
        }
        int sourceVersion = sourceMatrix.path("matrix_version").asInt(-1);
        if (sourceVersion < 1 || clarified.path("matrix_version").asInt(-1) != sourceVersion + 1) {
            throw new IllegalStateException("hearing clarified matrix version must increment once");
        }
        ObjectNode parent = object(clarified.path("parent_ref"));
        if (!requiredText(sourceMatrix, "matrix_id").equals(requiredText(parent, "matrix_id"))
                || sourceVersion != parent.path("matrix_version").asInt(-1)
                || !requiredText(sourceMatrix, "content_hash")
                        .equals(requiredText(parent, "content_hash"))) {
            throw new IllegalStateException("hearing clarified matrix parent binding is invalid");
        }
        if (!canonicalJson(sourceMatrix.path("party_map"))
                        .equals(canonicalJson(clarified.path("party_map")))
                || !canonicalJson(sourceMatrix.path("claims"))
                        .equals(canonicalJson(clarified.path("claims")))
                || !canonicalJson(sourceMatrix.path("fact_relationships"))
                        .equals(canonicalJson(clarified.path("fact_relationships")))) {
            throw new IllegalStateException(
                    "hearing clarification cannot replace party identity, claims, or relationships");
        }
        Set<String> clarifiedSourceRefs = textSet(clarified.path("source_refs"), "source_refs");
        if (!clarifiedSourceRefs.containsAll(textSet(sourceMatrix.path("source_refs"), "source_refs"))) {
            throw new IllegalStateException("hearing clarified matrix dropped prior source_refs");
        }
        ObjectNode generation = object(clarified.path("generation_ref"));
        if (!"HEARING_CLARIFICATION".equals(requiredText(generation, "source_stage"))
                || !"SYSTEM".equals(requiredText(generation, "actor_role"))
                || !requiredText(generation, "source_context_hash").matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("hearing clarification generation_ref is invalid");
        }
        requiredText(generation, "latest_source_ref");

        ArrayNode priorRows = array(sourceMatrix.path("fact_rows"));
        ArrayNode rows = array(clarified.path("fact_rows"));
        if (priorRows.isEmpty() || rows.size() < priorRows.size() || rows.size() > 200) {
            throw new IllegalStateException("hearing clarified matrix fact row count is invalid");
        }
        Set<String> factIds = new LinkedHashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = object(rows.get(index));
            String factId = requiredText(row, "fact_id");
            if (!factIds.add(factId)) {
                throw new IllegalStateException("hearing clarified matrix contains duplicate fact_id");
            }
            if (!"NOT_EVALUATED".equals(requiredText(row, "truth_status"))) {
                throw new IllegalStateException("hearing clarification cannot evaluate fact truth");
            }
            assertDerivedResolution(row);
            if (index < priorRows.size()) {
                ObjectNode prior = object(priorRows.get(index));
                if (!requiredText(prior, "fact_id").equals(factId)
                        || !requiredText(prior, "category")
                                .equals(requiredText(row, "category"))
                        || !requiredText(prior, "fact_target")
                                .equals(requiredText(row, "fact_target"))
                        || !requiredText(prior, "materiality")
                                .equals(requiredText(row, "materiality"))) {
                    throw new IllegalStateException(
                            "hearing clarification changed or renumbered a prior fact");
                }
                continue;
            }
            if (!factId.startsWith("FACT_HEARING_")
                    || !"HEARING_CLARIFICATION"
                            .equals(
                                    requiredText(
                                            object(row.path("origin")), "introduced_stage"))
                    || !"NOT_COVERED_BY_FROZEN_DOSSIER"
                            .equals(requiredText(row, "evidence_coverage_status"))) {
                throw new IllegalStateException(
                        "new hearing fact lacks stable identity or frozen-dossier coverage state");
            }
        }
        assertFactIndexes(clarified, rows);
    }

    private void assertDerivedResolution(ObjectNode row) {
        String status =
                requiredText(object(row.path("party_alignment")), "status");
        JsonNode resolution = row.path("requires_resolution");
        if ("NOT_COMPUTED".equals(status)) {
            if (!resolution.isNull()) {
                throw new IllegalStateException(
                        "NOT_COMPUTED fact alignment requires null requires_resolution");
            }
            return;
        }
        if (!Set.of(
                        "AGREED",
                        "PARTIALLY_AGREED",
                        "CONTESTED",
                        "ONE_SIDED",
                        "UNRESOLVED")
                .contains(status)) {
            throw new IllegalStateException("hearing fact alignment status is invalid");
        }
        if (!resolution.isBoolean()
                || resolution.asBoolean() != !"AGREED".equals(status)) {
            throw new IllegalStateException(
                    "requires_resolution must be derived from party_alignment");
        }
    }

    private void assertFactIndexes(ObjectNode matrix, ArrayNode rows) {
        ObjectNode expected = objectMapper.createObjectNode();
        for (String key :
                List.of(
                        "not_computed_fact_ids",
                        "agreed_fact_ids",
                        "partially_agreed_fact_ids",
                        "contested_fact_ids",
                        "one_sided_fact_ids",
                        "unresolved_fact_ids",
                        "core_fact_ids",
                        "requires_resolution_fact_ids")) {
            expected.putArray(key);
        }
        for (JsonNode value : rows) {
            ObjectNode row = object(value);
            String factId = requiredText(row, "fact_id");
            String status = requiredText(object(row.path("party_alignment")), "status");
            String indexKey =
                    switch (status) {
                        case "NOT_COMPUTED" -> "not_computed_fact_ids";
                        case "AGREED" -> "agreed_fact_ids";
                        case "PARTIALLY_AGREED" -> "partially_agreed_fact_ids";
                        case "CONTESTED" -> "contested_fact_ids";
                        case "ONE_SIDED" -> "one_sided_fact_ids";
                        case "UNRESOLVED" -> "unresolved_fact_ids";
                        default -> throw new IllegalStateException(
                                "hearing fact alignment status is invalid");
                    };
            expected.withArray(indexKey).add(factId);
            if ("CORE".equals(row.path("materiality").asText())) {
                expected.withArray("core_fact_ids").add(factId);
            }
            if (row.path("requires_resolution").asBoolean(false)) {
                expected.withArray("requires_resolution_fact_ids").add(factId);
            }
        }
        if (!canonicalJson(expected).equals(canonicalJson(matrix.path("fact_indexes")))) {
            throw new IllegalStateException("hearing clarified matrix fact_indexes are invalid");
        }
    }

    private static Set<String> textSet(JsonNode value, String field) {
        ArrayNode values = array(value);
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : values) {
            String text = item.asText();
            if (text.isBlank() || !result.add(text)) {
                throw new IllegalStateException(field + " must contain unique non-blank text");
            }
        }
        return Set.copyOf(result);
    }

    private String hashWithoutField(ObjectNode payload, String field) {
        ObjectNode copy = payload.deepCopy();
        copy.remove(field);
        return sha256(canonicalJson(copy));
    }

    private String contentHash(JsonNode payload) {
        return sha256(canonicalJson(payload));
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

    private HearingFlowActionEntity requireSystemAction(
            HearingFlowInstanceEntity instance, HearingFlowActionType type) {
        return findSystemAction(instance, type)
                .orElseThrow(() -> new IllegalStateException(type + " is not persisted"));
    }

    private java.util.Optional<HearingFlowActionEntity> findSystemAction(
            HearingFlowInstanceEntity instance, HearingFlowActionType type) {
        return actionRepository
                .findAllByFlowInstanceIdOrderByCreatedAtAsc(instance.getId())
                .stream()
                .filter(item -> item.getActionType() == type)
                .filter(item -> item.getParticipantRole() == null)
                .findFirst();
    }

    private HearingFlowArtifactEntity requireArtifact(String caseId, HearingArtifactType type) {
        return artifactRepository
                .findByCaseIdAndArtifactType(caseId, type)
                .orElseThrow(() -> new IllegalStateException(type + " artifact is not frozen"));
    }

    private HearingTrialDossierEntity requireFrozenDossier(String caseId) {
        return trialDossierRepository
                .findByCaseId(caseId)
                .orElseThrow(() -> new IllegalStateException("trial dossier is not frozen"));
    }

    private ArrayNode actionPayloads(
            HearingFlowInstanceEntity instance, HearingFlowActionType type) {
        ArrayNode result = objectMapper.createArrayNode();
        actionRepository
                .findAllByFlowInstanceIdOrderByCreatedAtAsc(instance.getId())
                .stream()
                .filter(item -> item.getActionType() == type)
                .sorted(
                        Comparator.comparing(
                                item ->
                                        item.getParticipantRole() == ActorRole.USER
                                                ? 0
                                                : 1))
                .map(item -> read(item.getPayloadJson()))
                .forEach(result::add);
        return result;
    }

    private HearingFlowStageEntity currentStage(HearingFlowInstanceEntity instance) {
        return stageRepository
                .findByFlowInstanceIdAndStageSequence(
                        instance.getId(), instance.getStageSequence())
                .orElseThrow(() -> new IllegalStateException("active hearing stage row not found"));
    }

    private HearingFlowStageEntity requireStage(
            HearingFlowInstanceEntity instance, HearingFlowStage stage) {
        return stageRepository
                .findByFlowInstanceIdAndStageCode(instance.getId(), stage)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.CASE_STATUS_INVALID,
                                        "hearing stage is not available yet",
                                        Map.of(
                                                "required_stage", stage.name(),
                                                "current_stage",
                                                        instance.getCurrentStage().name(),
                                                "stage_sequence",
                                                        instance.getStageSequence())));
    }

    private ObjectNode stageOutput(
            HearingFlowInstanceEntity instance, HearingFlowStage stage) {
        HearingFlowStageEntity value = requireStage(instance, stage);
        if (value.getStageStatus() != HearingFlowStageStatus.COMPLETED) {
            throw new IllegalStateException(stage + " has no durable output");
        }
        return object(read(value.getOutputJson()));
    }

    private String stageId(HearingFlowInstanceEntity instance, HearingFlowStage stage) {
        return requireStage(instance, stage).getId();
    }

    private void requireCurrentStage(
            HearingFlowInstanceEntity instance, HearingFlowStage required) {
        if (instance.getCurrentStage() != required) {
            throw illegalTransition(instance, required);
        }
    }

    private BusinessException illegalTransition(
            HearingFlowInstanceEntity instance, HearingFlowStage requested) {
        return new BusinessException(
                ErrorCode.CASE_STATUS_INVALID,
                "illegal hearing_flow.v2 stage transition",
                Map.of(
                        "current_stage", instance.getCurrentStage().name(),
                        "requested_stage", requested.name(),
                        "stage_sequence", instance.getStageSequence()));
    }

    private void assertDeadlineOpen(HearingFlowInstanceEntity instance) {
        if (instance.getSharedDeadlineAt() == null
                || !clock.instant().isBefore(instance.getSharedDeadlineAt())) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "the shared hearing deadline has expired",
                    Map.of(
                            "current_stage", instance.getCurrentStage().name(),
                            "stage_sequence", instance.getStageSequence()));
        }
    }

    private boolean bothPartiesTerminal(
            FulfillmentCaseEntity dispute, String stageId, HearingFlowActionType type) {
        return partyIdentities(dispute).stream()
                .allMatch(
                        party ->
                                actionRepository
                                        .findByStageIdAndActionTypeAndParticipantId(
                                                stageId, type, party.participantId())
                                        .isPresent());
    }

    private Map<String, String> partyStatuses(
            FulfillmentCaseEntity dispute,
            HearingFlowStageEntity stage,
            HearingFlowActionType type) {
        Map<String, String> result = new LinkedHashMap<>();
        for (PartyIdentity party : partyIdentities(dispute)) {
            String status =
                    actionRepository
                            .findByStageIdAndActionTypeAndParticipantId(
                                    stage.getId(), type, party.participantId())
                            .map(item -> item.getSubmissionStatus().name())
                            .orElse("PENDING");
            result.put(party.role().name(), status);
        }
        return result;
    }

    private List<HearingFlowView.ParticipantStatus> participantStatuses(
            FulfillmentCaseEntity dispute,
            HearingFlowStageEntity stage,
            HearingFlowActionType type) {
        List<HearingFlowView.ParticipantStatus> result = new ArrayList<>();
        for (PartyIdentity party : partyIdentities(dispute)) {
            String status =
                    actionRepository
                            .findByStageIdAndActionTypeAndParticipantId(
                                    stage.getId(), type, party.participantId())
                            .map(item -> item.getSubmissionStatus().name())
                            .orElse("PENDING");
            result.add(
                    new HearingFlowView.ParticipantStatus(
                            party.participantId(), party.role().name(), status));
        }
        return List.copyOf(result);
    }

    private static List<PartyIdentity> partyIdentities(FulfillmentCaseEntity dispute) {
        return List.of(
                new PartyIdentity(dispute.getUserId(), ActorRole.USER),
                new PartyIdentity(dispute.getMerchantId(), ActorRole.MERCHANT));
    }

    private FulfillmentCaseEntity lockedCase(String caseId) {
        return caseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
    }

    private HearingStateEntity requireHearingState(String caseId) {
        return hearingStateRepository
                .findByCaseId(caseId)
                .orElseThrow(() -> new IllegalStateException("hearing state not found"));
    }

    private CaseRoomEntity requireHearingRoom(String caseId) {
        return roomRepository
                .findByCaseIdAndRoomTypeForUpdate(caseId, RoomType.HEARING)
                .orElseThrow(() -> new IllegalStateException("hearing room not found"));
    }

    private void assertCanAccess(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(dispute.getUserId());
                    case MERCHANT -> actor.actorId().equals(dispute.getMerchantId());
                    case PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access this hearing");
        }
    }

    private void assertCaseParty(FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if ((actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT)
                || (actor.role() == ActorRole.USER
                                && !actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && !actor.actorId().equals(dispute.getMerchantId()))) {
            throw new ForbiddenException("only the authenticated case party may submit");
        }
    }

    private static HearingFlowStage stageForOperation(String operation) {
        return switch (operation) {
            case "HEARING_INTAKE_QUESTIONS" -> HearingFlowStage.INTAKE_QUESTIONS_GENERATING;
            case "HEARING_INTAKE_SYNTHESIS" -> HearingFlowStage.INTAKE_SYNTHESIZING;
            case "HEARING_EVIDENCE_REQUESTS" -> HearingFlowStage.EVIDENCE_REQUESTS_GENERATING;
            case "HEARING_EVIDENCE_SYNTHESIS" -> HearingFlowStage.EVIDENCE_SYNTHESIZING;
            case "HEARING_JUDGE_V1" -> HearingFlowStage.JUDGE_V1_GENERATING;
            case "HEARING_JURY_REVIEW" -> HearingFlowStage.JURY_REVIEWING;
            case "HEARING_JUDGE_V2" -> HearingFlowStage.JUDGE_V2_GENERATING;
            default -> throw new IllegalArgumentException("unsupported hearing flow operation");
        };
    }

    private static String operationForStage(HearingFlowStage stage) {
        return switch (stage) {
            case INTAKE_QUESTIONS_GENERATING -> "HEARING_INTAKE_QUESTIONS";
            case INTAKE_SYNTHESIZING -> "HEARING_INTAKE_SYNTHESIS";
            case EVIDENCE_REQUESTS_GENERATING -> "HEARING_EVIDENCE_REQUESTS";
            case EVIDENCE_SYNTHESIZING -> "HEARING_EVIDENCE_SYNTHESIS";
            case JUDGE_V1_GENERATING -> "HEARING_JUDGE_V1";
            case JURY_REVIEWING -> "HEARING_JURY_REVIEW";
            case JUDGE_V2_GENERATING -> "HEARING_JUDGE_V2";
            default -> null;
        };
    }

    private static HearingFlowStage nextStage(HearingFlowStage current) {
        return switch (current) {
            case COURT_PREPARING -> HearingFlowStage.CASE_INTRODUCTION;
            case CASE_INTRODUCTION -> HearingFlowStage.EVIDENCE_INTRODUCTION;
            case EVIDENCE_INTRODUCTION -> HearingFlowStage.INTAKE_QUESTIONS_GENERATING;
            case INTAKE_QUESTIONS_GENERATING -> HearingFlowStage.PARTY_ANSWERS_OPEN;
            case PARTY_ANSWERS_OPEN -> HearingFlowStage.INTAKE_SYNTHESIZING;
            case INTAKE_SYNTHESIZING -> HearingFlowStage.EVIDENCE_REQUESTS_GENERATING;
            case EVIDENCE_REQUESTS_GENERATING -> HearingFlowStage.PARTY_EVIDENCE_OPEN;
            case PARTY_EVIDENCE_OPEN -> HearingFlowStage.EVIDENCE_SYNTHESIZING;
            case EVIDENCE_SYNTHESIZING -> HearingFlowStage.DOSSIER_FREEZING;
            case DOSSIER_FREEZING -> HearingFlowStage.JUDGE_V1_GENERATING;
            case JUDGE_V1_GENERATING -> HearingFlowStage.JURY_REVIEWING;
            case JURY_REVIEWING -> HearingFlowStage.JUDGE_V2_GENERATING;
            case JUDGE_V2_GENERATING -> HearingFlowStage.HUMAN_REVIEW_OPEN;
            case HUMAN_REVIEW_OPEN -> HearingFlowStage.CLOSED;
            case CLOSED -> throw new IllegalStateException("closed hearing flow has no successor");
        };
    }

    private static String processorRole(HearingFlowStage stage) {
        return switch (stage) {
            case CASE_INTRODUCTION, INTAKE_QUESTIONS_GENERATING, INTAKE_SYNTHESIZING ->
                    "INTAKE_OFFICER";
            case EVIDENCE_INTRODUCTION,
                    EVIDENCE_REQUESTS_GENERATING,
                    EVIDENCE_SYNTHESIZING -> "EVIDENCE_CLERK";
            case PARTY_ANSWERS_OPEN, PARTY_EVIDENCE_OPEN -> "PARTIES";
            case JUDGE_V1_GENERATING, JUDGE_V2_GENERATING -> "PRESIDING_JUDGE";
            case JURY_REVIEWING -> "JURY_PANEL";
            case COURT_PREPARING, DOSSIER_FREEZING, HUMAN_REVIEW_OPEN, CLOSED -> "SYSTEM";
        };
    }

    private static boolean isJudgeOperation(String operation) {
        return "HEARING_JUDGE_V1".equals(operation) || "HEARING_JUDGE_V2".equals(operation);
    }

    private static boolean targetsRole(ObjectNode item, ActorRole role) {
        if (role.name().equals(item.path("target_party").asText())) {
            return true;
        }
        for (JsonNode target : array(item.path("target_roles"))) {
            if (role.name().equals(target.asText())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> caseFactIds(ObjectNode matrix) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode row : array(matrix.path("fact_rows"))) {
            String id = row.path("fact_id").asText();
            if (!id.isBlank()) {
                result.add(id);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("case_fact_matrix.v2 has no fact rows");
        }
        return result;
    }

    private static void assertKnownFacts(ArrayNode references, Set<String> known) {
        for (JsonNode reference : references) {
            if (!known.contains(reference.asText())) {
                throw new IllegalStateException("hearing output references an unknown fact_id");
            }
        }
    }

    private static ArrayNode nonEmptyTextArray(JsonNode value, String field) {
        ArrayNode result = array(value);
        if (result.isEmpty()) {
            throw new IllegalStateException(field + " must not be empty");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode item : result) {
            if (!item.isTextual() || item.asText().isBlank() || !unique.add(item.asText())) {
                throw new IllegalStateException(field + " must contain unique non-blank strings");
            }
        }
        return result;
    }

    private static void assertUnique(List<String> values, String field) {
        if (values.size() != new HashSet<>(values).size()) {
            throw invalidArgument(field + " values must be unique");
        }
    }

    private static ArrayNode requiredPartyRoles(ObjectNode value) {
        ArrayNode roles = array(value.path("target_roles"));
        if (roles.isEmpty() || roles.size() > 2) {
            throw new IllegalStateException("target_roles must contain one or two parties");
        }
        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : roles) {
            String role = item.asText();
            if ((!"USER".equals(role) && !"MERCHANT".equals(role)) || !seen.add(role)) {
                throw new IllegalStateException(
                        "target_roles must contain unique USER or MERCHANT values");
            }
            normalized.add(role);
        }
        return normalized;
    }

    private static String requiredText(JsonNode value, String field) {
        String result = value.path(field).asText();
        if (result.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return result;
    }

    private static void requireSchema(ObjectNode value, String schema) {
        if (!schema.equals(value.path("schema_version").asText())) {
            throw new IllegalStateException("expected " + schema);
        }
    }

    private static String issueId(ArrayNode factIds) {
        List<String> values = new ArrayList<>();
        factIds.forEach(item -> values.add(item.asText()));
        return stableId("HEARING_ISSUE_", values.toArray(String[]::new));
    }

    private static String answerSummary(HearingAnswerBundleRequest request) {
        return request.answers().stream()
                .map(HearingAnswerBundleRequest.Answer::answerText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("已提交本轮回答。");
    }

    private static List<String> attachmentRefs(HearingAnswerBundleRequest request) {
        return request.answers().stream()
                .flatMap(item -> item.attachmentRefs().stream())
                .distinct()
                .toList();
    }

    private static String evidenceSummary(ActorRole role, int count) {
        String party = role == ActorRole.USER ? "用户" : "商家";
        return party + "提交了 " + count + " 份证据材料。";
    }

    private static BusinessException invalidArgument(String message) {
        return new BusinessException(ErrorCode.INVALID_ARGUMENT, message, Map.of());
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void putNullable(ObjectNode target, String field, String value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private ObjectNode parseObjectOrEmpty(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
            return parsed != null && parsed.isObject()
                    ? object(parsed).deepCopy()
                    : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted hearing JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize hearing JSON", exception);
        }
    }

    private static ObjectNode object(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("hearing payload must be a JSON object");
        }
        return (ObjectNode) value;
    }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray()
                ? (ArrayNode) value
                : JsonNodeFactory.instance.arrayNode();
    }

    private static String stableId(String prefix, String... parts) {
        return prefix + sha256(String.join("\u001f", parts)).substring(0, 24);
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record PartyIdentity(String participantId, ActorRole role) {}
}
