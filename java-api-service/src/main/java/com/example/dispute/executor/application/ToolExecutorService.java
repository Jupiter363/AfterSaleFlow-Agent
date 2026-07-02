package com.example.dispute.executor.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.tool.application.SimulatedExecutionTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ToolExecutorService {

    private static final List<ApprovalDecisionType> EXECUTABLE_DECISIONS =
            List.of(
                    ApprovalDecisionType.APPROVE,
                    ApprovalDecisionType.MODIFY_AND_APPROVE);

    private final FulfillmentCaseRepository caseRepository;
    private final RemedyPlanRepository planRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final ReviewPacketRepository packetRepository;
    private final ActionRecordRepository actionRepository;
    private final ActionExecutionLock executionLock;
    private final SimulatedExecutionTool tool;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ToolExecutorService(
            FulfillmentCaseRepository caseRepository,
            RemedyPlanRepository planRepository,
            ApprovalRecordRepository approvalRepository,
            ReviewPacketRepository packetRepository,
            ActionRecordRepository actionRepository,
            ActionExecutionLock executionLock,
            SimulatedExecutionTool tool,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.caseRepository = caseRepository;
        this.planRepository = planRepository;
        this.approvalRepository = approvalRepository;
        this.packetRepository = packetRepository;
        this.actionRepository = actionRepository;
        this.executionLock = executionLock;
        this.tool = tool;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    public ExecutionBatchView executeApprovedActions(
            String caseId, String commandIdempotencyKey, AuthenticatedActor actor) {
        assertCanExecute(actor);
        if (commandIdempotencyKey == null || commandIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "command idempotency key must not be blank");
        }
        ExecutionSnapshot snapshot =
                transactions.execute(
                        ignored ->
                                loadApprovedExecution(
                                        caseId, commandIdempotencyKey, actor));
        for (ExecutableAction action : snapshot.actions()) {
            String ownerToken = executionLock.acquire(action.idempotencyKey());
            try {
                executeAction(snapshot, action, actor);
            } finally {
                executionLock.release(action.idempotencyKey(), ownerToken);
            }
        }
        List<ActionRecordView> records =
                actionRepository
                        .findAllByCaseIdOrderByCreatedAtAsc(caseId)
                        .stream()
                        .filter(
                                record ->
                                        record.getPlanId()
                                                .equals(snapshot.planId()))
                        .map(this::view)
                        .toList();
        return new ExecutionBatchView(
                caseId,
                snapshot.planId(),
                snapshot.approvalRecordId(),
                records.stream()
                        .allMatch(
                                record ->
                                        record.executionStatus()
                                                == ExecutionStatus.SUCCEEDED),
                records);
    }

    private void executeAction(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            AuthenticatedActor actor) {
        PreparedAction prepared =
                transactions.execute(
                        ignored -> prepare(snapshot, action, actor));
        if (!prepared.invokeTool()) {
            return;
        }
        try {
            ToolExecutionResult result = tool.execute(action);
            transactions.executeWithoutResult(
                    ignored ->
                            completeSuccess(snapshot, action, result, actor));
        } catch (ToolExecutionException exception) {
            transactions.executeWithoutResult(
                    ignored ->
                            completeFailure(
                                    snapshot, action, exception, actor));
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ActionRecordView> actions(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        assertCanRead(disputeCase, actor);
        return actionRepository
                .findAllByCaseIdOrderByCreatedAtAsc(caseId)
                .stream()
                .map(this::view)
                .toList();
    }

    private ExecutionSnapshot loadApprovedExecution(
            String caseId,
            String commandIdempotencyKey,
            AuthenticatedActor actor) {
        ApprovalRecordEntity approval =
                approvalRepository
                        .findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(
                                caseId, EXECUTABLE_DECISIONS)
                        .orElseThrow(
                                () ->
                                        denied(
                                                "approved remedy plan is required",
                                                Map.of("case_id", caseId)));
        ReviewPacketEntity packet =
                validateFrozenApproval(caseId, approval);
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        if (disputeCase.getCaseStatus()
                        != com.example.dispute.domain.model.CaseStatus
                                .APPROVED_FOR_EXECUTION
                && disputeCase.getCaseStatus()
                        != com.example.dispute.domain.model.CaseStatus.EXECUTING) {
            throw denied(
                    "case is not approved for execution",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }
        var previousStatus = disputeCase.getCaseStatus();
        RemedyPlanEntity plan =
                planRepository
                        .findById(approval.getPlanId())
                        .orElseThrow(
                                () ->
                                        denied(
                                                "approved remedy plan does not exist",
                                                Map.of(
                                                        "plan_id",
                                                        approval.getPlanId())));
        if (!caseId.equals(plan.getCaseId())
                || !caseId.equals(approval.getCaseId())) {
            throw denied(
                    "approval, plan, and case do not match",
                    Map.of("case_id", caseId));
        }
        JsonNode approvedPlan = read(approval.getApprovedPlanJson());
        String calculatedActionHash =
                ActionSnapshotHasher.hash(objectMapper, approvedPlan);
        if (!calculatedActionHash.equals(approval.getActionSnapshotHash())) {
            throw denied(
                    "approved action snapshot hash does not match human review record",
                    Map.of("approval_record_id", approval.getId()));
        }
        if (approval.getDecisionType() == ApprovalDecisionType.APPROVE
                && !packet.getActionHash().equals(calculatedActionHash)) {
            throw denied(
                    "approved action snapshot does not match frozen review packet",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!plan.getId().equals(approvedPlan.path("id").asText())) {
            throw denied(
                    "approval does not reference the current remedy plan",
                    Map.of("plan_id", plan.getId()));
        }
        List<ExecutableAction> executableActions =
                approvedActions(caseId, plan, approvedPlan);
        if (executableActions.isEmpty()) {
            throw denied(
                    "approved remedy plan contains no executable actions",
                    Map.of("plan_id", plan.getId()));
        }
        disputeCase.beginExecution(actor.actorId());
        caseRepository.save(disputeCase);
        auditRecorder.record(
                actor,
                previousStatus
                                == com.example.dispute.domain.model.CaseStatus
                                        .EXECUTING
                        ? "TOOL_EXECUTION_BATCH_RESUMED"
                        : "TOOL_EXECUTION_BATCH_STARTED",
                "REMEDY_PLAN",
                plan.getId(),
                caseId,
                Map.of(
                        "case_status",
                        previousStatus.name()),
                Map.of(
                        "case_status",
                        com.example.dispute.domain.model.CaseStatus.EXECUTING
                                .name(),
                        "command_idempotency_key",
                        commandIdempotencyKey,
                        "action_count",
                        executableActions.size()));
        return new ExecutionSnapshot(
                caseId,
                plan.getId(),
                approval.getId(),
                approval.getReviewerId(),
                packet.getId(),
                approval.getActionSnapshotHash(),
                packet.getEvidenceMatrixJson(),
                packet.getDraftJson(),
                packet.getAgentRunRefsJson(),
                executableActions);
    }

    private ReviewPacketEntity validateFrozenApproval(
            String caseId,
            ApprovalRecordEntity approval) {
        if (approval.getReviewPacketId() == null
                || approval.getActionSnapshotHash() == null
                || approval.getApprovalExpiresAt() == null) {
            throw denied(
                    "frozen human review provenance is required",
                    Map.of("approval_record_id", approval.getId()));
        }
        ReviewPacketEntity packet =
                packetRepository
                        .findById(approval.getReviewPacketId())
                        .orElseThrow(
                                () ->
                                        denied(
                                                "frozen review packet does not exist",
                                                Map.of(
                                                        "review_packet_id",
                                                        approval.getReviewPacketId())));
        ReviewPacketEntity latest =
                packetRepository
                        .findFirstByCaseIdAndPlanIdOrderByPacketVersionDesc(
                                caseId, approval.getPlanId())
                        .orElseThrow(
                                () ->
                                        denied(
                                                "current review packet does not exist",
                                                Map.of("case_id", caseId)));
        if (!packet.isFrozen()) {
            throw denied(
                    "review packet is not frozen",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!packet.getId().equals(latest.getId())
                || packet.getPacketVersion()
                        != approval.getReviewPacketVersion()) {
            throw denied(
                    "human approval references a stale review packet",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!caseId.equals(packet.getCaseId())
                || !approval.getPlanId().equals(packet.getPlanId())) {
            throw denied(
                    "review packet, approval, plan, and case do not match",
                    Map.of("review_packet_id", packet.getId()));
        }
        if (!"PLATFORM_REVIEWER".equals(approval.getReviewerRole())) {
            throw denied(
                    "approval was not issued by the required reviewer role",
                    Map.of("reviewer_role", approval.getReviewerRole()));
        }
        if (OffsetDateTime.now(ZoneOffset.UTC)
                .isAfter(approval.getApprovalExpiresAt())) {
            throw denied(
                    "human approval has expired",
                    Map.of("approval_record_id", approval.getId()));
        }
        return packet;
    }

    private List<ExecutableAction> approvedActions(
            String caseId, RemedyPlanEntity plan, JsonNode approvedPlan) {
        JsonNode approvedActionNodes = approvedPlan.path("actions");
        if (!approvedActionNodes.isArray()) {
            throw denied(
                    "approved plan actions must be an array",
                    Map.of("plan_id", plan.getId()));
        }
        Map<String, JsonNode> originalByKey = new LinkedHashMap<>();
        JsonNode originalActions = read(plan.getActionsJson());
        if (!originalActions.isArray()) {
            throw denied(
                    "persisted remedy plan actions are invalid",
                    Map.of("plan_id", plan.getId()));
        }
        originalActions.forEach(
                node ->
                        originalByKey.put(
                                node.path("idempotency_key").asText(), node));
        List<ExecutableAction> result = new ArrayList<>();
        for (JsonNode node : approvedActionNodes) {
            String actionType = requiredText(node, "action_type");
            String idempotencyKey =
                    requiredText(node, "idempotency_key");
            JsonNode original = originalByKey.get(idempotencyKey);
            if (original == null
                    || !actionType.equals(
                            original.path("action_type").asText())
                    || !node.path("requires_approval").asBoolean()
                    || !original.path("requires_approval").asBoolean()) {
                throw denied(
                        "action is not contained in the approved remedy plan",
                        Map.of(
                                "action_type",
                                actionType,
                                "idempotency_key",
                                idempotencyKey));
            }
            result.add(
                    new ExecutableAction(
                            actionType,
                            idempotencyKey,
                            risk(node.path("risk_level").asText()),
                            parameters(node.path("parameters"))));
        }
        JsonNode approvedNotifications = approvedPlan.path("notifications");
        if (!approvedNotifications.isArray()) {
            throw denied(
                    "approved plan notifications must be an array",
                    Map.of("plan_id", plan.getId()));
        }
        Set<String> originalNotifications =
                new LinkedHashSet<>(
                        objectMapper.convertValue(
                                read(plan.getNotificationPlanJson()),
                                new TypeReference<List<String>>() {}));
        int index = 0;
        for (JsonNode notificationNode : approvedNotifications) {
            String notification = notificationNode.asText();
            if (!originalNotifications.contains(notification)) {
                throw denied(
                        "notification is not contained in the approved remedy plan",
                        Map.of("notification", notification));
            }
            result.add(
                    new ExecutableAction(
                            notification,
                            "REMEDY:"
                                    + caseId
                                    + ":"
                                    + plan.getPlanVersion()
                                    + ":NOTIFICATION:"
                                    + index
                                    + ":"
                                    + notification,
                            RiskLevel.LOW,
                            Map.of(
                                    "case_id", caseId,
                                    "plan_id", plan.getId())));
            index++;
        }
        return List.copyOf(result);
    }

    private PreparedAction prepare(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            AuthenticatedActor actor) {
        String requestJson = write(action);
        var existing =
                actionRepository.findByIdempotencyKeyForUpdate(
                        action.idempotencyKey());
        if (existing.isPresent()) {
            ActionRecordEntity record = existing.get();
            assertRecordMatches(record, snapshot, action);
            if (record.getExecutionStatus() == ExecutionStatus.SUCCEEDED) {
                return new PreparedAction(record.getId(), false);
            }
            record.retry(actor.actorId(), requestJson);
            actionRepository.save(record);
            auditStarted(record, actor, true);
            return new PreparedAction(record.getId(), true);
        }
        ActionRecordEntity record =
                actionRepository.save(
                        ActionRecordEntity.runningGoverned(
                                "ACTION_" + compactUuid(),
                                snapshot.caseId(),
                                snapshot.planId(),
                                snapshot.approvalRecordId(),
                                action.actionType(),
                                action.riskLevel(),
                                action.idempotencyKey(),
                                snapshot.approvedBy(),
                                actor.actorId(),
                                requestJson,
                                snapshot.reviewPacketId(),
                                snapshot.actionSnapshotHash(),
                                snapshot.evidenceRefsJson(),
                                snapshot.ruleRefsJson(),
                                snapshot.agentRunRefsJson()));
        auditStarted(record, actor, false);
        return new PreparedAction(record.getId(), true);
    }

    private void completeSuccess(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            ToolExecutionResult result,
            AuthenticatedActor actor) {
        ActionRecordEntity record =
                actionRepository
                        .findByIdempotencyKeyForUpdate(
                                action.idempotencyKey())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "running action record disappeared"));
        assertRecordMatches(record, snapshot, action);
        record.succeed(write(result), result.referenceId());
        actionRepository.save(record);
        auditRecorder.record(
                actor,
                "TOOL_EXECUTION_SUCCEEDED",
                "ACTION_RECORD",
                record.getId(),
                snapshot.caseId(),
                Map.of("execution_status", ExecutionStatus.RUNNING.name()),
                Map.of(
                        "execution_status",
                        ExecutionStatus.SUCCEEDED.name(),
                        "action_type",
                        action.actionType(),
                        "attempt_count",
                        record.getAttemptCount()));
    }

    private void completeFailure(
            ExecutionSnapshot snapshot,
            ExecutableAction action,
            ToolExecutionException exception,
            AuthenticatedActor actor) {
        ActionRecordEntity record =
                actionRepository
                        .findByIdempotencyKeyForUpdate(
                                action.idempotencyKey())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "running action record disappeared"));
        assertRecordMatches(record, snapshot, action);
        record.fail(
                exception.errorCode().name(),
                exception.getMessage(),
                write(
                        Map.of(
                                "status", "FAILED",
                                "error_code",
                                exception.errorCode().name(),
                                "details",
                                exception.details())));
        actionRepository.save(record);
        auditRecorder.record(
                actor,
                "TOOL_EXECUTION_FAILED",
                "ACTION_RECORD",
                record.getId(),
                snapshot.caseId(),
                Map.of("execution_status", ExecutionStatus.RUNNING.name()),
                Map.of(
                        "execution_status",
                        ExecutionStatus.FAILED.name(),
                        "action_type",
                        action.actionType(),
                        "error_code",
                        exception.errorCode().name(),
                        "attempt_count",
                        record.getAttemptCount()));
    }

    private void auditStarted(
            ActionRecordEntity record,
            AuthenticatedActor actor,
            boolean retry) {
        auditRecorder.record(
                actor,
                retry ? "TOOL_EXECUTION_RETRIED" : "TOOL_EXECUTION_STARTED",
                "ACTION_RECORD",
                record.getId(),
                record.getCaseId(),
                Map.of(),
                Map.of(
                        "execution_status", ExecutionStatus.RUNNING.name(),
                        "action_type", record.getActionType(),
                        "attempt_count", record.getAttemptCount()));
    }

    private void assertRecordMatches(
            ActionRecordEntity record,
            ExecutionSnapshot snapshot,
            ExecutableAction action) {
        if (!record.getCaseId().equals(snapshot.caseId())
                || !record.getPlanId().equals(snapshot.planId())
                || !record.getApprovalRecordId()
                        .equals(snapshot.approvalRecordId())
                || !record.getActionType().equals(action.actionType())) {
            throw denied(
                    "idempotency key belongs to a different approved action",
                    Map.of(
                            "idempotency_key",
                            action.idempotencyKey()));
        }
    }

    private ActionRecordView view(ActionRecordEntity record) {
        return new ActionRecordView(
                record.getId(),
                record.getCaseId(),
                record.getPlanId(),
                record.getApprovalRecordId(),
                record.getActionType(),
                record.getRiskLevel(),
                record.getIdempotencyKey(),
                record.getApprovedBy(),
                record.getExecutedBy(),
                record.getExecutionStatus(),
                record.getAttemptCount(),
                read(record.getRequestJson()),
                read(record.getResultJson()),
                record.getErrorCode(),
                record.getErrorMessage(),
                record.getReviewPacketId(),
                record.getActionSnapshotHash(),
                read(record.getEvidenceRefsJson()),
                read(record.getRuleRefsJson()),
                read(record.getAgentRunRefsJson()),
                record.getExternalResultRef(),
                record.getExecutionTime(),
                record.getCreatedAt());
    }

    private Map<String, Object> parameters(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw denied(
                    "approved action parameters must be an object",
                    Map.of());
        }
        return objectMapper.convertValue(
                node, new TypeReference<Map<String, Object>>() {});
    }

    private static RiskLevel risk(String value) {
        try {
            return RiskLevel.valueOf(value);
        } catch (RuntimeException exception) {
            throw denied(
                    "approved action risk level is invalid",
                    Map.of("risk_level", value));
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw denied(
                    "approved action field is required",
                    Map.of("field", field));
        }
        return value;
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw denied(
                    "approved execution JSON is invalid",
                    Map.of());
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "cannot serialize tool execution payload", exception);
        }
    }

    private static void assertCanExecute(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException(
                    "only the workflow executor or an administrator can execute actions");
        }
    }

    private static void assertCanRead(
            FulfillmentCaseEntity disputeCase, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER ->
                            actor.actorId().equals(disputeCase.getUserId());
                    case MERCHANT ->
                            actor.actorId()
                                    .equals(disputeCase.getMerchantId());
                    case CUSTOMER_SERVICE,
                            PLATFORM_REVIEWER,
                            ADMIN,
                            SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException(
                    "actor cannot read action records for this case");
        }
    }

    private static NotFoundException caseNotFound(String caseId) {
        return new NotFoundException(
                ErrorCode.CASE_NOT_FOUND,
                "case not found",
                Map.of("case_id", caseId));
    }

    private static ToolExecutionException denied(
            String message, Map<String, Object> details) {
        return new ToolExecutionException(
                ErrorCode.TOOL_EXECUTION_DENIED, message, details);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ExecutionSnapshot(
            String caseId,
            String planId,
            String approvalRecordId,
            String approvedBy,
            String reviewPacketId,
            String actionSnapshotHash,
            String evidenceRefsJson,
            String ruleRefsJson,
            String agentRunRefsJson,
            List<ExecutableAction> actions) {}

    private record PreparedAction(String actionRecordId, boolean invokeTool) {}
}
