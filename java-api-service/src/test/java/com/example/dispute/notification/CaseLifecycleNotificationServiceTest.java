/*
 * 所属模块：案件生命周期通知。
 * 文件职责：验证案件生命周期通知，覆盖 「sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// 所属模块：【案件生命周期通知 / 自动化测试层】类型「CaseLifecycleNotificationServiceTest」。
// 类型职责：集中验证案件生命周期通知的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class CaseLifecycleNotificationServiceTest {

    private final NotificationService notificationService =
            mock(NotificationService.class);
    private final CaseLifecycleNotificationService service =
            new CaseLifecycleNotificationService(notificationService);

    // 所属模块：【案件生命周期通知 / 自动化测试层】「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks()」。
    // 具体功能：「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks()」：复现“核对完整业务行为（场景方法「sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks」）”场景：驱动 「service.evidenceRoomOpened」、「service.evidenceDeadlineWarning」、「service.supplementRequested」、「service.reviewPending」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_1」、「user-local」、「merchant-local」、「2026-07-03T02:00:00Z」。
    // 上游调用：「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseLifecycleNotificationServiceTest.sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks()」守住「案件生命周期通知」的可执行规格，尤其防止 「CASE_1」、「user-local」、「merchant-local」、「2026-07-03T02:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void sendsEveryLifecycleTemplateToBothCasePartiesWithInternalDeepLinks() {
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        when(dispute.getId()).thenReturn("CASE_1");
        when(dispute.getUserId()).thenReturn("user-local");
        when(dispute.getMerchantId()).thenReturn("merchant-local");
        OffsetDateTime deadline =
                OffsetDateTime.parse("2026-07-03T02:00:00Z");

        service.evidenceRoomOpened(dispute, deadline);
        service.evidenceDeadlineWarning(dispute, deadline);
        service.supplementRequested(dispute, "round-2");
        service.reviewPending(dispute, "REVIEW_1");
        service.finalDecision(dispute, "APPROVE");
        service.executionCompleted(dispute);
        service.manualHandoff(dispute, "REVIEW_REJECTED");

        ArgumentCaptor<NotificationCommand> commands =
                ArgumentCaptor.forClass(NotificationCommand.class);
        verify(notificationService, org.mockito.Mockito.times(14))
                .send(commands.capture());

        assertThat(commands.getAllValues())
                .extracting(NotificationCommand::notificationType)
                .containsOnly(
                        NotificationType.EVIDENCE_ROOM_OPENED,
                        NotificationType.EVIDENCE_DEADLINE_WARNING,
                        NotificationType.SUPPLEMENT_REQUESTED,
                        NotificationType.REVIEW_PENDING,
                        NotificationType.FINAL_DECISION,
                        NotificationType.EXECUTION_COMPLETED,
                        NotificationType.MANUAL_HANDOFF);
        assertThat(commands.getAllValues())
                .extracting(NotificationCommand::recipientId)
                .containsOnly("user-local", "merchant-local");
        assertThat(commands.getAllValues())
                .allSatisfy(
                        command -> {
                            assertThat(command.caseId()).isEqualTo("CASE_1");
                            assertThat(command.deepLink())
                                    .startsWith("/disputes/CASE_1/");
                            assertThat(command.businessEventKey())
                                    .startsWith("CASE_1:");
                            assertThat(command.payloadJson()).isNotBlank();
                        });
        assertThat(commands.getAllValues())
                .filteredOn(
                        command ->
                                command.notificationType()
                                        == NotificationType.EVIDENCE_DEADLINE_WARNING)
                .allSatisfy(
                        command ->
                                assertThat(command.payloadJson())
                                        .contains(deadline.toString()));
    }
}
