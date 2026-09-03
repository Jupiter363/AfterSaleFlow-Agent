/*
 * 所属模块：案件生命周期通知。
 * 文件职责：编排案件生命周期通知规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「evidenceRoomOpened」、「evidenceDeadlineWarning」、「supplementRequested」、「reviewPending」、「finalDecision」、「executionCompleted」；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.notification.domain.NotificationType;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

// 所属模块：【案件生命周期通知 / 应用编排层】类型「CaseLifecycleNotificationService」。
// 类型职责：编排案件生命周期通知规则、权限校验与事实读写；本类型显式提供 「CaseLifecycleNotificationService」、「evidenceRoomOpened」、「evidenceDeadlineWarning」、「supplementRequested」、「reviewPending」、「finalDecision」。
// 协作关系：主要由 「CaseClosureService.prepareClosure」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「EvidenceCompletionService.warnDeadline」 使用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class CaseLifecycleNotificationService {

    private final NotificationService notificationService;

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.CaseLifecycleNotificationService(NotificationService)」。
    // 具体功能：「CaseLifecycleNotificationService.CaseLifecycleNotificationService(NotificationService)」：通过构造器接收 「notificationService」(NotificationService) 并保存为「CaseLifecycleNotificationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseLifecycleNotificationService.CaseLifecycleNotificationService(NotificationService)」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「CaseLifecycleNotificationService.CaseLifecycleNotificationService(NotificationService)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseLifecycleNotificationService.CaseLifecycleNotificationService(NotificationService)」负责主链路中的“案件生命周期通知服务”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseLifecycleNotificationService(
            NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.evidenceRoomOpened(FulfillmentCaseEntity,OffsetDateTime)」。
    // 具体功能：「CaseLifecycleNotificationService.evidenceRoomOpened(FulfillmentCaseEntity,OffsetDateTime)」：执行证据房间Opened；实际协作者为 「notifyParties」；处理的关键状态/协议值包括 「evidence-room-opened」、「证据书记官室已开放」、「双方可以进入证据书记官室提交材料，举证窗口为两小时。」、「evidence」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.evidenceRoomOpened(FulfillmentCaseEntity,OffsetDateTime)」的上游调用点包括 「IntakeRoomService.confirm」、「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
    // 下游影响：「CaseLifecycleNotificationService.evidenceRoomOpened(FulfillmentCaseEntity,OffsetDateTime)」向下依次触达 「notifyParties」。
    // 系统意义：「CaseLifecycleNotificationService.evidenceRoomOpened(FulfillmentCaseEntity,OffsetDateTime)」负责主链路中的“证据房间Opened”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.evidenceDeadlineWarning(FulfillmentCaseEntity,OffsetDateTime)」。
    // 具体功能：「CaseLifecycleNotificationService.evidenceDeadlineWarning(FulfillmentCaseEntity,OffsetDateTime)」：执行证据截止时间Warning；实际协作者为 「notifyParties」；处理的关键状态/协议值包括 「evidence-deadline-warning」、「举证时间即将结束」、「证据书记官室将在三十分钟后关闭，请尽快提交或确认完成举证。」、「evidence」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.evidenceDeadlineWarning(FulfillmentCaseEntity,OffsetDateTime)」的上游调用点包括 「EvidenceCompletionService.warnDeadline」、「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
    // 下游影响：「CaseLifecycleNotificationService.evidenceDeadlineWarning(FulfillmentCaseEntity,OffsetDateTime)」向下依次触达 「notifyParties」。
    // 系统意义：「CaseLifecycleNotificationService.evidenceDeadlineWarning(FulfillmentCaseEntity,OffsetDateTime)」负责主链路中的“证据截止时间Warning”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.supplementRequested(FulfillmentCaseEntity,String)」。
    // 具体功能：「CaseLifecycleNotificationService.supplementRequested(FulfillmentCaseEntity,String)」：执行补证请求；实际协作者为 「notifyParties」、「safeKey」、「json」；处理的关键状态/协议值包括 「supplement-requested:」、「审判庭请求补充证据」、「当前事实仍有缺口，请进入审判庭查看补证要求并在时限内回应。」、「hearing」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.supplementRequested(FulfillmentCaseEntity,String)」的上游调用点包括 「ReviewApplicationService.persistDecision」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
    // 下游影响：「CaseLifecycleNotificationService.supplementRequested(FulfillmentCaseEntity,String)」向下依次触达 「notifyParties」、「safeKey」、「json」。
    // 系统意义：「CaseLifecycleNotificationService.supplementRequested(FulfillmentCaseEntity,String)」负责主链路中的“补证请求”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.reviewPending(FulfillmentCaseEntity,String)」。
    // 具体功能：「CaseLifecycleNotificationService.reviewPending(FulfillmentCaseEntity,String)」：执行审核待处理；实际协作者为 「notifyParties」、「safeKey」、「json」；处理的关键状态/协议值包括 「review-pending:」、「裁决草案已进入平台终审」、「审核辅助官已将裁决草案提交平台审核，最终结果确认后会再次来信。」、「outcome」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.reviewPending(FulfillmentCaseEntity,String)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
    // 下游影响：「CaseLifecycleNotificationService.reviewPending(FulfillmentCaseEntity,String)」向下依次触达 「notifyParties」、「safeKey」、「json」。
    // 系统意义：「CaseLifecycleNotificationService.reviewPending(FulfillmentCaseEntity,String)」负责主链路中的“审核待处理”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.finalDecision(FulfillmentCaseEntity,String)」。
    // 具体功能：「CaseLifecycleNotificationService.finalDecision(FulfillmentCaseEntity,String)」：执行终态决定；实际协作者为 「notifyParties」、「safeKey」、「json」；处理的关键状态/协议值包括 「final-decision:」、「平台终审已作出决定」、「本案终审决定已经形成，请进入结果页查看裁决内容和后续执行状态。」、「outcome」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.finalDecision(FulfillmentCaseEntity,String)」的上游调用点包括 「ReviewApplicationService.persistDecision」、「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
    // 下游影响：「CaseLifecycleNotificationService.finalDecision(FulfillmentCaseEntity,String)」向下依次触达 「notifyParties」、「safeKey」、「json」。
    // 系统意义：「CaseLifecycleNotificationService.finalDecision(FulfillmentCaseEntity,String)」负责主链路中的“终态决定”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.executionCompleted(FulfillmentCaseEntity)」。
    // 具体功能：「CaseLifecycleNotificationService.executionCompleted(FulfillmentCaseEntity)」：执行执行完成；实际协作者为 「notifyParties」；处理的关键状态/协议值包括 「execution-completed」、「裁决方案已执行完成」、「本案核准方案已经执行完毕，可进入结果页查看执行记录。」、「outcome」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.executionCompleted(FulfillmentCaseEntity)」的上游调用点包括 「CaseClosureService.prepareClosure」、「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
    // 下游影响：「CaseLifecycleNotificationService.executionCompleted(FulfillmentCaseEntity)」向下依次触达 「notifyParties」。
    // 系统意义：「CaseLifecycleNotificationService.executionCompleted(FulfillmentCaseEntity)」负责主链路中的“执行完成”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.manualHandoff(FulfillmentCaseEntity,String)」。
    // 具体功能：「CaseLifecycleNotificationService.manualHandoff(FulfillmentCaseEntity,String)」：执行人工接管移交；实际协作者为 「notifyParties」、「safeKey」、「json」；处理的关键状态/协议值包括 「manual-handoff:」、「案件已转人工处理」、「系统已将案件移交人工专员，处理进展会继续通过传票信箱同步。」、「outcome」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.manualHandoff(FulfillmentCaseEntity,String)」的上游调用点包括 「ReviewApplicationService.persistDecision」、「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
    // 下游影响：「CaseLifecycleNotificationService.manualHandoff(FulfillmentCaseEntity,String)」向下依次触达 「notifyParties」、「safeKey」、「json」。
    // 系统意义：「CaseLifecycleNotificationService.manualHandoff(FulfillmentCaseEntity,String)」负责主链路中的“人工接管移交”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.notifyParties(FulfillmentCaseEntity,String,NotificationType,String,String,String,String)」。
    // 具体功能：「CaseLifecycleNotificationService.notifyParties(FulfillmentCaseEntity,String,NotificationType,String,String,String,String)」：通知参与方；实际协作者为 「dispute.getUserId」、「dispute.getMerchantId」、「send」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.notifyParties(FulfillmentCaseEntity,String,NotificationType,String,String,String,String)」的上游调用点包括 「CaseLifecycleNotificationService.evidenceRoomOpened」、「CaseLifecycleNotificationService.evidenceDeadlineWarning」、「CaseLifecycleNotificationService.supplementRequested」、「CaseLifecycleNotificationService.reviewPending」。
    // 下游影响：「CaseLifecycleNotificationService.notifyParties(FulfillmentCaseEntity,String,NotificationType,String,String,String,String)」向下依次触达 「dispute.getUserId」、「dispute.getMerchantId」、「send」。
    // 系统意义：「CaseLifecycleNotificationService.notifyParties(FulfillmentCaseEntity,String,NotificationType,String,String,String,String)」负责主链路中的“参与方”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.send(FulfillmentCaseEntity,String,ActorRole,String,NotificationType,String,String,String,String)」。
    // 具体功能：「CaseLifecycleNotificationService.send(FulfillmentCaseEntity,String,ActorRole,String,NotificationType,String,String,String,String)」：发送案件生命周期通知；实际协作者为 「notificationService.send」、「dispute.getId」；处理的关键状态/协议值包括 「:」，最终返回「void」。
    // 上游调用：「CaseLifecycleNotificationService.send(FulfillmentCaseEntity,String,ActorRole,String,NotificationType,String,String,String,String)」的上游调用点包括 「CaseLifecycleNotificationService.notifyParties」。
    // 下游影响：「CaseLifecycleNotificationService.send(FulfillmentCaseEntity,String,ActorRole,String,NotificationType,String,String,String,String)」向下依次触达 「notificationService.send」、「dispute.getId」。
    // 系统意义：「CaseLifecycleNotificationService.send(FulfillmentCaseEntity,String,ActorRole,String,NotificationType,String,String,String,String)」负责主链路中的“案件生命周期通知”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
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

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.safeKey(String)」。
    // 具体功能：「CaseLifecycleNotificationService.safeKey(String)」：生成安全值键；实际协作者为 「value.replaceAll」；处理的关键状态/协议值包括 「unspecified」、「[^A-Za-z0-9_-]」、「_」，最终返回「String」。
    // 上游调用：「CaseLifecycleNotificationService.safeKey(String)」的上游调用点包括 「CaseLifecycleNotificationService.supplementRequested」、「CaseLifecycleNotificationService.reviewPending」、「CaseLifecycleNotificationService.finalDecision」、「CaseLifecycleNotificationService.manualHandoff」。
    // 下游影响：「CaseLifecycleNotificationService.safeKey(String)」向下依次触达 「value.replaceAll」；计算结果以「String」交给调用方。
    // 系统意义：「CaseLifecycleNotificationService.safeKey(String)」负责主链路中的“键”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    private static String safeKey(String value) {
        return value == null || value.isBlank()
                ? "unspecified"
                : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「CaseLifecycleNotificationService.json(String)」。
    // 具体功能：「CaseLifecycleNotificationService.json(String)」：构建JSON；实际协作者为 「value.replace」、「value.replace("\\","\\\\").replace」；处理的关键状态/协议值包括 「\\」、「\\\\」、「\」、「\\\」，最终返回「String」。
    // 上游调用：「CaseLifecycleNotificationService.json(String)」的上游调用点包括 「CaseLifecycleNotificationService.supplementRequested」、「CaseLifecycleNotificationService.reviewPending」、「CaseLifecycleNotificationService.finalDecision」、「CaseLifecycleNotificationService.manualHandoff」。
    // 下游影响：「CaseLifecycleNotificationService.json(String)」向下依次触达 「value.replace」、「value.replace("\\","\\\\").replace」；计算结果以「String」交给调用方。
    // 系统意义：「CaseLifecycleNotificationService.json(String)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    private static String json(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
