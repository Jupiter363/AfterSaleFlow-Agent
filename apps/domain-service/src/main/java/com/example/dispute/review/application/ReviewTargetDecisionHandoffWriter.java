package com.example.dispute.review.application;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.domain.HearingDecisionAction;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationOutboxEntity;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewHumanDecisionReceipt;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochSelectionAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Java-owned Review handoff writer. It persists a canonical human-decision receipt only; the
 * Temporal relay remains the sole component that may signal the Outcome workflow.
 */
@Service
public final class ReviewTargetDecisionHandoffWriter {
    private static final String SCHEMA = "production-runtime-review-outcome-handoff.v1";
    private static final String EVENT_TYPE = "TARGET_REVIEW_OUTCOME_HANDOFF";
    private static final String ACTION_TYPE = "TARGET_NO_EXTERNAL_EFFECT";
    private static final String EXECUTED_BY = "production-runtime-outcome-reservation";

    private final NotificationOutboxRepository outbox;
    private final ActionRecordRepository actions;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReviewTargetDecisionHandoffWriter(
            NotificationOutboxRepository outbox,
            ActionRecordRepository actions,
            ObjectMapper mapper,
            Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Receipt record(
            TargetRoomEpochSelectionAuthority.Grant grant,
            CaseRoomEpochEntity epoch,
            String commandId,
            TargetReviewHumanDecisionReceipt decision,
            ApprovalRecordEntity approval,
            ReviewTaskEntity task,
            ReviewPacketEntity packet) {
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(decision, "decision");
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId is required");
        }
        if (!epoch.getTenantSurrogate().equals(grant.request().tenantSurrogate())
                || !epoch.getCaseId().equals(grant.request().caseId())
                || epoch.getRoomEpoch() < 0
                || epoch.getFencingToken() < 1) {
            throw new IllegalStateException("target Review handoff route differs from the active epoch");
        }
        recordActionIfAuthorized(epoch, decision, approval, task, packet);
        String identity = grant.activationId() + "\n" + epoch.getCaseId() + "\n" + commandId;
        String handoffId = "HANDOFF_" + sha256(identity).substring(0, 32);
        String eventKey = "target-review-handoff:" + sha256(identity);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("schema_version", SCHEMA);
        payload.put("handoff_id", handoffId);
        payload.put("activation_id", grant.activationId());
        payload.put("activation_manifest_hash", grant.activationManifestHash());
        payload.put("tenant_surrogate", epoch.getTenantSurrogate());
        payload.put("case_id", epoch.getCaseId());
        payload.put("command_id", commandId);
        payload.put("room_epoch", epoch.getRoomEpoch());
        payload.put("room_fencing_token", epoch.getFencingToken());
        payload.set("human_decision", mapper.valueToTree(decision));
        String handoffHash = ContractJson.sha256Hex(payload);
        payload.put("handoff_hash", handoffHash);
        String canonical = ContractJson.canonicalString(payload);

        var durable = outbox.findByBusinessEventKey(eventKey);
        if (durable.isPresent()) {
            return requireExactReplay(
                    mapper,
                    durable.get(),
                    handoffId,
                    epoch.getCaseId(),
                    eventKey,
                    EVENT_TYPE,
                    canonical);
        }
        if (outbox.existsById(handoffId)) {
            throw new IllegalStateException("target Review handoff id is already bound to another event");
        }
        outbox.save(NotificationOutboxEntity.pending(
                handoffId, epoch.getCaseId(), eventKey, EVENT_TYPE, canonical, clock.instant()));
        return new Receipt(handoffId, handoffHash);
    }

    static Receipt requireExactReplay(
            ObjectMapper mapper,
            NotificationOutboxEntity durable,
            String expectedId,
            String expectedCaseId,
            String expectedBusinessEventKey,
            String expectedEventType,
            String expectedCanonicalPayload) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(durable, "durable");
        if (!Objects.equals(expectedId, durable.getId())
                || !Objects.equals(expectedCaseId, durable.getCaseId())
                || !Objects.equals(expectedBusinessEventKey, durable.getBusinessEventKey())
                || !Objects.equals(expectedEventType, durable.getEventType())) {
            throw new IllegalStateException("target Review handoff replay identity conflicts");
        }
        JsonNode expected = parse(mapper, expectedCanonicalPayload, "expected handoff");
        JsonNode actual = parse(mapper, durable.getEventPayloadJson(), "durable handoff");
        if (!expected.isObject() || !actual.isObject()) {
            throw new IllegalStateException("target Review handoff replay payload is not an object");
        }
        String durableHash = actual.path("handoff_hash").asText();
        ObjectNode durablePreimage = ((ObjectNode) actual).deepCopy();
        durablePreimage.remove("handoff_hash");
        if (!durableHash.matches("[0-9a-f]{64}")
                || !durableHash.equals(ContractJson.sha256Hex(durablePreimage))
                || !ContractJson.canonicalString(expected)
                        .equals(ContractJson.canonicalString(actual))) {
            throw new IllegalStateException("target Review handoff replay payload conflicts");
        }
        return new Receipt(durable.getId(), durableHash);
    }

    private void recordActionIfAuthorized(
            CaseRoomEpochEntity epoch,
            TargetReviewHumanDecisionReceipt decision,
            ApprovalRecordEntity approval,
            ReviewTaskEntity task,
            ReviewPacketEntity packet) {
        Objects.requireNonNull(approval, "approval");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(packet, "packet");
        boolean approved = approval.getDecisionType() == ApprovalDecisionType.APPROVE
                || approval.getDecisionType() == ApprovalDecisionType.MODIFY_AND_APPROVE;
        if (!approved) {
            return;
        }
        if (!decision.outcomeReceipt().executionAuthorized()) {
            throw new IllegalStateException("target approved decision does not authorize execution");
        }
        if (!epoch.getCaseId().equals(task.getCaseId())
                || !task.getPlanId().equals(approval.getPlanId())
                || !task.getPacketId().equals(packet.getId())
                || !packet.getId().equals(approval.getReviewPacketId())) {
            throw new IllegalStateException("target ActionRecord parents do not match the approval");
        }
        JsonNode frozenPlan = parse(packet.getRemedyJson());
        JsonNode originalPlan = parse(approval.getOriginalPlanJson());
        JsonNode approvedPlan = parse(approval.getApprovedPlanJson());
        var outcomeReceipt = decision.outcomeReceipt();
        String frozenActionRef = "review-packet:" + packet.getId() + ":action";
        String approvedActionRef = ReviewApprovedActionSnapshotRef.resolve(
                approval.getDecisionType(),
                approval.getId(),
                frozenActionRef,
                packet.getActionHash(),
                approval.getActionSnapshotHash());
        if (!approval.getId().equals(decision.decisionRecordId())
                || !decision.decisionRecordHash().equals(outcomeReceipt.receiptHash())
                || !task.getId().equals(outcomeReceipt.reviewTaskId())
                || !task.getCaseId().equals(outcomeReceipt.caseId())
                || !packet.getId().equals(outcomeReceipt.frozenReviewPacketRef())
                || !frozenActionRef.equals(outcomeReceipt.actionSnapshotRef())
                || !packet.getActionHash().equals(outcomeReceipt.actionSnapshotHash())
                || !approvedActionRef.equals(outcomeReceipt.approvedActionSnapshotRef())) {
            throw new IllegalStateException("target approved action does not bind its human decision receipt");
        }
        boolean boundedDecisionAction = usesBoundedDecisionActionContract(
                approval.getAiDecisionAction(), approval.getReviewerDecisionAction());
        JsonNode operation = boundedDecisionAction
                ? requireBoundedDecisionActionOperation(
                        mapper,
                        approval.getDecisionType(),
                        approval.getId(),
                        approval.getActionSnapshotHash(),
                        packet.getActionHash(),
                        frozenPlan,
                        originalPlan,
                        approvedPlan,
                        approval.getAiDecisionAction(),
                        approval.getReviewerDecisionAction(),
                        outcomeReceipt.decision(),
                        outcomeReceipt.receiptId(),
                        outcomeReceipt.receiptHash(),
                        outcomeReceipt.decisionRecordRef(),
                        outcomeReceipt.approvedActionSnapshotHash())
                : requireApprovedOperation(
                        mapper,
                        approval.getDecisionType(),
                        approval.getId(),
                        approval.getActionSnapshotHash(),
                        packet.getActionHash(),
                        frozenPlan,
                        approvedPlan,
                        outcomeReceipt.decision(),
                        outcomeReceipt.receiptId(),
                        outcomeReceipt.receiptHash(),
                        outcomeReceipt.decisionRecordRef(),
                        outcomeReceipt.approvedActionSnapshotHash());
        String idempotencyKey = operation.path("idempotency_key").asText();
        ObjectNode request = mapper.createObjectNode();
        request.put("action_record_schema", "target-no-external-effect-action-record.v1");
        request.put("action_snapshot_hash", approval.getActionSnapshotHash());
        request.put("approval_record_id", approval.getId());
        request.put("decision_receipt_id", outcomeReceipt.receiptId());
        request.put("decision_receipt_hash", outcomeReceipt.receiptHash());
        request.put("case_id", task.getCaseId());
        request.put("plan_id", task.getPlanId());
        request.put("review_packet_id", packet.getId());
        request.put("reviewer_id", approval.getReviewerId());
        request.set("approved_plan", approvedPlan.deepCopy());
        request.set("action", operation.deepCopy());
        String requestJson = ContractJson.canonicalString(request);
        ActionRecordEntity expected = ActionRecordEntity.runningGoverned(
                "ACT_" + sha256("target-action-record:" + idempotencyKey).substring(0, 32),
                task.getCaseId(), task.getPlanId(), approval.getId(), ACTION_TYPE, RiskLevel.MEDIUM,
                idempotencyKey, approval.getReviewerId(), EXECUTED_BY, requestJson, packet.getId(),
                approval.getActionSnapshotHash(), "[]", "[]", "[]");
        actions.findByIdempotencyKeyForUpdate(idempotencyKey).ifPresentOrElse(existing -> {
            if (!sameBinding(existing, expected)) {
                throw new IllegalStateException("target ActionRecord idempotency key is bound to another approval");
            }
        }, () -> actions.save(expected));
    }

    static boolean usesBoundedDecisionActionContract(
            String aiDecisionAction, String reviewerDecisionAction) {
        boolean hasAiDecisionAction = aiDecisionAction != null && !aiDecisionAction.isBlank();
        boolean hasReviewerDecisionAction =
                reviewerDecisionAction != null && !reviewerDecisionAction.isBlank();
        if (hasAiDecisionAction != hasReviewerDecisionAction) {
            throw new IllegalStateException(
                    "target bounded decision-action authority is incomplete");
        }
        return hasAiDecisionAction;
    }

    static JsonNode requireBoundedDecisionActionOperation(
            ObjectMapper mapper,
            ApprovalDecisionType approvalDecision,
            String approvalId,
            String approvedActionSnapshotHash,
            String frozenActionSnapshotHash,
            JsonNode frozenPlan,
            JsonNode originalPlan,
            JsonNode approvedPlan,
            String aiDecisionAction,
            String reviewerDecisionAction,
            OutcomeWireTypes.ReviewDecision receiptDecision,
            String receiptId,
            String receiptHash,
            String receiptDecisionRecordRef,
            String receiptApprovedActionSnapshotHash) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(approvalDecision, "approvalDecision");
        Objects.requireNonNull(frozenPlan, "frozenPlan");
        Objects.requireNonNull(originalPlan, "originalPlan");
        Objects.requireNonNull(approvedPlan, "approvedPlan");
        if (!usesBoundedDecisionActionContract(aiDecisionAction, reviewerDecisionAction)
                || !HearingDecisionAction.supports(aiDecisionAction)
                || !HearingDecisionAction.supports(reviewerDecisionAction)) {
            throw new IllegalStateException(
                    "target bounded decision-action authority is invalid");
        }
        if (!Objects.equals(approvalId, receiptId)
                || !Objects.equals(approvalId, receiptDecisionRecordRef)
                || receiptHash == null
                || !receiptHash.matches("[0-9a-f]{64}")
                || receiptDecision != OutcomeWireTypes.ReviewDecision.valueOf(approvalDecision.name())
                || !Objects.equals(approvedActionSnapshotHash, receiptApprovedActionSnapshotHash)) {
            throw new IllegalStateException(
                    "target approved action does not bind its human decision receipt");
        }
        if (!frozenPlan.isObject() || !originalPlan.isObject() || !approvedPlan.isObject()) {
            throw new IllegalStateException(
                    "target bounded decision-action remedy must be an object");
        }
        String actualFrozenHash = ActionSnapshotHasher.hash(mapper, frozenPlan);
        String actualOriginalHash = ActionSnapshotHasher.hash(mapper, originalPlan);
        String actualApprovedHash = ActionSnapshotHasher.hash(mapper, approvedPlan);
        if (!Objects.equals(frozenActionSnapshotHash, actualFrozenHash)
                || !Objects.equals(frozenActionSnapshotHash, actualOriginalHash)
                || !Objects.equals(approvedActionSnapshotHash, actualApprovedHash)
                || !Objects.equals(frozenActionSnapshotHash, approvedActionSnapshotHash)) {
            throw new IllegalStateException("target approved action snapshot hash is stale");
        }
        if (frozenPlan.path("id").asText().isBlank()
                || !frozenPlan.path("version").isIntegralNumber()) {
            throw new IllegalStateException("target approved remedy identity is invalid");
        }

        ObjectNode expectedOriginal = ((ObjectNode) frozenPlan).deepCopy();
        expectedOriginal.put("decision_action", aiDecisionAction);
        ObjectNode expectedApproved = expectedOriginal.deepCopy();
        expectedApproved.put("decision_action", reviewerDecisionAction);
        if (!expectedOriginal.equals(originalPlan) || !expectedApproved.equals(approvedPlan)) {
            throw new IllegalStateException(
                    "target bounded review may only bind the persisted decision_action");
        }
        if (approvalDecision == ApprovalDecisionType.APPROVE) {
            if (!aiDecisionAction.equals(reviewerDecisionAction)) {
                throw new IllegalStateException(
                        "target bounded APPROVE must preserve the frozen AI decision_action");
            }
        } else if (approvalDecision == ApprovalDecisionType.MODIFY_AND_APPROVE) {
            if (aiDecisionAction.equals(reviewerDecisionAction)) {
                throw new IllegalStateException(
                        "target bounded MODIFY_AND_APPROVE must replace decision_action");
            }
        } else {
            throw new IllegalStateException("target action requires an approving human decision");
        }
        return requireNoExternalEffectOperation(approvedPlan);
    }

    static JsonNode requireApprovedOperation(
            ObjectMapper mapper,
            ApprovalDecisionType approvalDecision,
            String approvalId,
            String approvedActionSnapshotHash,
            String frozenActionSnapshotHash,
            JsonNode frozenPlan,
            JsonNode approvedPlan,
            OutcomeWireTypes.ReviewDecision receiptDecision,
            String receiptId,
            String receiptHash,
            String receiptDecisionRecordRef,
            String receiptApprovedActionSnapshotHash) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(approvalDecision, "approvalDecision");
        Objects.requireNonNull(frozenPlan, "frozenPlan");
        Objects.requireNonNull(approvedPlan, "approvedPlan");
        if (!Objects.equals(approvalId, receiptId)
                || !Objects.equals(approvalId, receiptDecisionRecordRef)
                || receiptHash == null
                || !receiptHash.matches("[0-9a-f]{64}")
                || receiptDecision != OutcomeWireTypes.ReviewDecision.valueOf(approvalDecision.name())
                || !Objects.equals(approvedActionSnapshotHash, receiptApprovedActionSnapshotHash)) {
            throw new IllegalStateException("target approved action does not bind its human decision receipt");
        }
        String actualFrozenHash = ActionSnapshotHasher.hash(mapper, frozenPlan);
        String actualApprovedHash = ActionSnapshotHasher.hash(mapper, approvedPlan);
        if (!Objects.equals(frozenActionSnapshotHash, actualFrozenHash)
                || !Objects.equals(approvedActionSnapshotHash, actualApprovedHash)) {
            throw new IllegalStateException("target approved action snapshot hash is stale");
        }
        if (frozenPlan.path("id").asText().isBlank()
                || approvedPlan.path("id").asText().isBlank()
                || !frozenPlan.path("version").isIntegralNumber()
                || !approvedPlan.path("version").isIntegralNumber()) {
            throw new IllegalStateException("target approved remedy identity is invalid");
        }
        if (approvalDecision == ApprovalDecisionType.APPROVE) {
            if (!frozenPlan.equals(approvedPlan)
                    || !frozenActionSnapshotHash.equals(approvedActionSnapshotHash)) {
                throw new IllegalStateException("target APPROVE must exactly replay the frozen remedy");
            }
        } else if (approvalDecision == ApprovalDecisionType.MODIFY_AND_APPROVE) {
            if (frozenPlan.equals(approvedPlan)
                    || frozenActionSnapshotHash.equals(approvedActionSnapshotHash)
                    || !frozenPlan.path("id").equals(approvedPlan.path("id"))
                    || !frozenPlan.path("version").equals(approvedPlan.path("version"))) {
                throw new IllegalStateException("target MODIFY_AND_APPROVE must bind a changed approved remedy");
            }
        } else {
            throw new IllegalStateException("target action requires an approving human decision");
        }
        return requireNoExternalEffectOperation(approvedPlan);
    }

    private static JsonNode requireNoExternalEffectOperation(JsonNode approvedPlan) {
        JsonNode operations = approvedPlan.path("actions");
        if (!operations.isArray() || operations.size() != 1
                || !approvedPlan.path("notifications").isArray()
                || !approvedPlan.path("notifications").isEmpty()) {
            throw new IllegalStateException("target approved remedy must contain exactly one action and no notifications");
        }
        JsonNode operation = operations.get(0);
        String idempotencyKey = operation.path("idempotency_key").asText();
        if (!ACTION_TYPE.equals(operation.path("action_type").asText())
                || !"NO_EXTERNAL_EFFECT".equals(operation.path("effect_class").asText())
                || idempotencyKey.isBlank()) {
            throw new IllegalStateException("target approved remedy action is not the no-external-effect manifest");
        }
        return operation.deepCopy();
    }

    private boolean sameBinding(ActionRecordEntity actual, ActionRecordEntity expected) {
        return actual.getId().equals(expected.getId())
                && actual.getCaseId().equals(expected.getCaseId())
                && actual.getPlanId().equals(expected.getPlanId())
                && actual.getApprovalRecordId().equals(expected.getApprovalRecordId())
                && actual.getActionType().equals(expected.getActionType())
                && actual.getRiskLevel() == expected.getRiskLevel()
                && actual.getIdempotencyKey().equals(expected.getIdempotencyKey())
                && actual.getApprovedBy().equals(expected.getApprovedBy())
                && actual.getExecutedBy().equals(expected.getExecutedBy())
                && actual.getReviewPacketId().equals(expected.getReviewPacketId())
                && actual.getActionSnapshotHash().equals(expected.getActionSnapshotHash())
                && parse(actual.getRequestJson()).equals(parse(expected.getRequestJson()))
                && actual.getEvidenceRefsJson().equals(expected.getEvidenceRefsJson())
                && actual.getRuleRefsJson().equals(expected.getRuleRefsJson())
                && actual.getAgentRunRefsJson().equals(expected.getAgentRunRefsJson());
    }

    private JsonNode parse(String value) {
        return parse(mapper, value, "target Review frozen plan");
    }

    private static JsonNode parse(ObjectMapper mapper, String value, String description) {
        try {
            return mapper.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException(description + " is not valid JSON", failure);
        }
    }

    public record Receipt(String handoffId, String handoffHash) {
        public Receipt {
            if (handoffId == null || handoffId.isBlank()
                    || handoffHash == null || !handoffHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("target Review handoff receipt is invalid");
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
