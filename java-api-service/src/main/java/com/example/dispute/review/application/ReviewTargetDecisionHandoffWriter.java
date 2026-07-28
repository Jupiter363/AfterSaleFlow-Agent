package com.example.dispute.review.application;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationOutboxEntity;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewHumanDecisionReceipt;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
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
    private static final String SCHEMA = "target-e2e-review-outcome-handoff.v1";
    private static final String EVENT_TYPE = "TARGET_REVIEW_OUTCOME_HANDOFF";
    private static final String ACTION_TYPE = "TARGET_NO_EXTERNAL_EFFECT";
    private static final String EXECUTED_BY = "target-e2e-outcome-reservation";

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

        if (outbox.existsByBusinessEventKey(eventKey)) {
            if (!outbox.existsById(handoffId)) {
                throw new IllegalStateException("target Review handoff idempotency binding is ambiguous");
            }
            return new Receipt(handoffId, handoffHash);
        }
        if (outbox.existsById(handoffId)) {
            throw new IllegalStateException("target Review handoff id is already bound to another event");
        }
        outbox.save(NotificationOutboxEntity.pending(
                handoffId, epoch.getCaseId(), eventKey, EVENT_TYPE, canonical, clock.instant()));
        return new Receipt(handoffId, handoffHash);
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
                || !packet.getId().equals(approval.getReviewPacketId())
                || !packet.getActionHash().equals(approval.getActionSnapshotHash())) {
            throw new IllegalStateException("target ActionRecord parents do not match the frozen approval");
        }
        JsonNode frozenPlan = parse(packet.getRemedyJson());
        JsonNode approvedPlan = parse(approval.getApprovedPlanJson());
        if (!frozenPlan.equals(approvedPlan)) {
            throw new IllegalStateException("target approved plan must exactly replay the frozen remedy");
        }
        JsonNode operations = frozenPlan.path("actions");
        if (!operations.isArray() || operations.size() != 1
                || !frozenPlan.path("notifications").isArray()
                || !frozenPlan.path("notifications").isEmpty()) {
            throw new IllegalStateException("target frozen remedy must contain exactly one action and no notifications");
        }
        JsonNode operation = operations.get(0);
        String idempotencyKey = operation.path("idempotency_key").asText();
        if (!ACTION_TYPE.equals(operation.path("action_type").asText())
                || !"NO_EXTERNAL_EFFECT".equals(operation.path("effect_class").asText())
                || idempotencyKey.isBlank()) {
            throw new IllegalStateException("target frozen remedy action is not the no-external-effect manifest");
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("action_record_schema", "target-no-external-effect-action-record.v1");
        request.put("action_snapshot_hash", approval.getActionSnapshotHash());
        request.put("approval_record_id", approval.getId());
        request.put("case_id", task.getCaseId());
        request.put("plan_id", task.getPlanId());
        request.put("review_packet_id", packet.getId());
        request.put("reviewer_id", approval.getReviewerId());
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
        try {
            return mapper.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("target Review frozen plan is not valid JSON", failure);
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
