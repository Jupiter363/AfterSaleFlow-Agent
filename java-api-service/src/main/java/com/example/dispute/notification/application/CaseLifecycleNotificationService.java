package com.example.dispute.notification.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.notification.domain.NotificationType;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

@Service
public class CaseLifecycleNotificationService {

    private final NotificationService notificationService;

    public CaseLifecycleNotificationService(
            NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void evidenceRoomOpened(
            FulfillmentCaseEntity dispute, OffsetDateTime deadline) {
        notifyParties(
                dispute,
                "evidence-room-opened",
                NotificationType.EVIDENCE_ROOM_OPENED,
                "证据书记官室已开放",
                "双方可以进入证据书记官室提交材料，举证窗口为两小时。",
                "evidence",
                "{\"deadline_at\":\"" + deadline + "\"}");
    }

    public void evidenceDeadlineWarning(
            FulfillmentCaseEntity dispute, OffsetDateTime deadline) {
        notifyParties(
                dispute,
                "evidence-deadline-warning",
                NotificationType.EVIDENCE_DEADLINE_WARNING,
                "举证时间即将结束",
                "证据书记官室将在三十分钟后关闭，请尽快提交或确认完成举证。",
                "evidence",
                "{\"deadline_at\":\"" + deadline + "\"}");
    }

    public void supplementRequested(
            FulfillmentCaseEntity dispute, String reference) {
        notifyParties(
                dispute,
                "supplement-requested:" + safeKey(reference),
                NotificationType.SUPPLEMENT_REQUESTED,
                "审判庭请求补充证据",
                "当前事实仍有缺口，请进入审判庭查看补证要求并在时限内回应。",
                "hearing",
                "{\"reference\":\"" + json(reference) + "\"}");
    }

    public void reviewPending(
            FulfillmentCaseEntity dispute, String reviewTaskId) {
        notifyParties(
                dispute,
                "review-pending:" + safeKey(reviewTaskId),
                NotificationType.REVIEW_PENDING,
                "裁决草案已进入平台终审",
                "审核辅助官已将裁决草案提交平台审核，最终结果确认后会再次来信。",
                "outcome",
                "{\"review_task_id\":\"" + json(reviewTaskId) + "\"}");
    }

    public void finalDecision(
            FulfillmentCaseEntity dispute, String decision) {
        notifyParties(
                dispute,
                "final-decision:" + safeKey(decision),
                NotificationType.FINAL_DECISION,
                "平台终审已作出决定",
                "本案终审决定已经形成，请进入结果页查看裁决内容和后续执行状态。",
                "outcome",
                "{\"decision\":\"" + json(decision) + "\"}");
    }

    public void executionCompleted(FulfillmentCaseEntity dispute) {
        notifyParties(
                dispute,
                "execution-completed",
                NotificationType.EXECUTION_COMPLETED,
                "裁决方案已执行完成",
                "本案核准方案已经执行完毕，可进入结果页查看执行记录。",
                "outcome",
                "{\"status\":\"EXECUTION_COMPLETED\"}");
    }

    public void manualHandoff(
            FulfillmentCaseEntity dispute, String reason) {
        notifyParties(
                dispute,
                "manual-handoff:" + safeKey(reason),
                NotificationType.MANUAL_HANDOFF,
                "案件已转人工处理",
                "系统已将案件移交人工专员，处理进展会继续通过传票信箱同步。",
                "outcome",
                "{\"reason\":\"" + json(reason) + "\"}");
    }

    private void notifyParties(
            FulfillmentCaseEntity dispute,
            String eventKey,
            NotificationType type,
            String title,
            String body,
            String room,
            String payloadJson) {
        send(
                dispute,
                dispute.getUserId(),
                ActorRole.USER,
                eventKey,
                type,
                title,
                body,
                room,
                payloadJson);
        send(
                dispute,
                dispute.getMerchantId(),
                ActorRole.MERCHANT,
                eventKey,
                type,
                title,
                body,
                room,
                payloadJson);
    }

    private void send(
            FulfillmentCaseEntity dispute,
            String recipientId,
            ActorRole role,
            String eventKey,
            NotificationType type,
            String title,
            String body,
            String room,
            String payloadJson) {
        notificationService.send(
                new NotificationCommand(
                        dispute.getId(),
                        dispute.getId() + ":" + eventKey,
                        recipientId,
                        role,
                        type,
                        title,
                        body,
                        "/disputes/" + dispute.getId() + "/" + room,
                        payloadJson));
    }

    private static String safeKey(String value) {
        return value == null || value.isBlank()
                ? "unspecified"
                : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String json(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
