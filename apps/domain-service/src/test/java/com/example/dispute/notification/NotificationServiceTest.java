/*
 * 所属模块：案件生命周期通知。
 * 文件职责：验证通知，覆盖 「createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons」、「replayingTheSameBusinessEventReturnsTheExistingInboxMessage」、「marksOnlyTheRecipientsOwnMessageAsRead」、「marksAllUnreadMessagesInTheCurrentRecipientsInbox」、「dismissesOnlyTheCurrentRecipientsOwnNotification」、「doesNotExposeAnotherRecipientsNotificationForDeletion」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationEntity;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【案件生命周期通知 / 自动化测试层】类型「NotificationServiceTest」。
// 类型职责：集中验证通知的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons」、「replayingTheSameBusinessEventReturnsTheExistingInboxMessage」、「marksOnlyTheRecipientsOwnMessageAsRead」、「marksAllUnreadMessagesInTheCurrentRecipientsInbox」、「dismissesOnlyTheCurrentRecipientsOwnNotification」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationOutboxRepository outboxRepository;

    private NotificationService service;

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.setUp()」。
    // 具体功能：「NotificationServiceTest.setUp()」：在每个测试场景运行前创建「Clock.fixed」、「Instant.parse」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「NotificationServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「NotificationServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「NotificationServiceTest.setUp()」守住「案件生命周期通知」的可执行规格，尤其防止 「2026-07-03T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new NotificationService(
                        notificationRepository,
                        outboxRepository,
                        Clock.fixed(
                                Instant.parse("2026-07-03T00:00:00Z"),
                                ZoneOffset.UTC));
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons()」。
    // 具体功能：「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons()」：复现“创建并持久化（场景方法「createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons」）”场景：驱动 「notificationRepository.findByBusinessEventKeyAndRecipientId」、「notificationRepository.save」、「outboxRepository.existsByBusinessEventKey」、「service.send」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_1:intake-accepted」、「merchant-local」、「CASE_1」、「争议审理传票」。
    // 上游调用：「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons()」守住「案件生命周期通知」的可执行规格，尤其防止 「CASE_1:intake-accepted」、「merchant-local」、「CASE_1」、「争议审理传票」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons() {
        when(notificationRepository.findByBusinessEventKeyAndRecipientId(
                        "CASE_1:intake-accepted", "merchant-local"))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxRepository.existsByBusinessEventKey("CASE_1:intake-accepted"))
                .thenReturn(false);

        var result =
                service.send(
                        new NotificationCommand(
                                "CASE_1",
                                "CASE_1:intake-accepted",
                                "merchant-local",
                                ActorRole.MERCHANT,
                                NotificationType.DISPUTE_SUMMONS,
                                "争议审理传票",
                                "订单争议已受理，请在两小时内进入证据书记官室。",
                                "/disputes/CASE_1/evidence",
                                "{\"deadline_at\":\"2026-07-03T02:00:00Z\"}"));

        assertThat(result.recipientId()).isEqualTo("merchant-local");
        assertThat(result.read()).isFalse();
        verify(notificationRepository).save(any(NotificationEntity.class));
        verify(outboxRepository).save(any());
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.replayingTheSameBusinessEventReturnsTheExistingInboxMessage()」。
    // 具体功能：「NotificationServiceTest.replayingTheSameBusinessEventReturnsTheExistingInboxMessage()」：复现“核对完整业务行为（场景方法「replayingTheSameBusinessEventReturnsTheExistingInboxMessage」）”场景：驱动 「notificationRepository.findByBusinessEventKeyAndRecipientId」、「service.send」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「NOTICE_EXISTING」、「CASE_1」、「CASE_1:intake-accepted」、「merchant-local」。
    // 上游调用：「NotificationServiceTest.replayingTheSameBusinessEventReturnsTheExistingInboxMessage()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationServiceTest.replayingTheSameBusinessEventReturnsTheExistingInboxMessage()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「NotificationServiceTest.replayingTheSameBusinessEventReturnsTheExistingInboxMessage()」守住「案件生命周期通知」的可执行规格，尤其防止 「NOTICE_EXISTING」、「CASE_1」、「CASE_1:intake-accepted」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void replayingTheSameBusinessEventReturnsTheExistingInboxMessage() {
        NotificationEntity existing =
                NotificationEntity.create(
                        "NOTICE_EXISTING",
                        "CASE_1",
                        "CASE_1:intake-accepted",
                        "merchant-local",
                        ActorRole.MERCHANT,
                        NotificationType.DISPUTE_SUMMONS,
                        "争议审理传票",
                        "请进入证据书记官室",
                        "/disputes/CASE_1/evidence",
                        "{}",
                        Instant.parse("2026-07-03T00:00:00Z"));
        when(notificationRepository.findByBusinessEventKeyAndRecipientId(
                        "CASE_1:intake-accepted", "merchant-local"))
                .thenReturn(Optional.of(existing));

        var replayed =
                service.send(
                        new NotificationCommand(
                                "CASE_1",
                                "CASE_1:intake-accepted",
                                "merchant-local",
                                ActorRole.MERCHANT,
                                NotificationType.DISPUTE_SUMMONS,
                                "争议审理传票",
                                "重复内容不会产生第二封信",
                                "/disputes/CASE_1/evidence",
                                "{}"));

        assertThat(replayed.id()).isEqualTo("NOTICE_EXISTING");
        verify(notificationRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead()」。
    // 具体功能：「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead()」：复现“核对完整业务行为（场景方法「marksOnlyTheRecipientsOwnMessageAsRead」）”场景：驱动 「notificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull」、「service.markRead」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「NOTICE_1」、「CASE_1」、「CASE_1:intake-accepted」、「merchant-local」。
    // 上游调用：「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead()」守住「案件生命周期通知」的可执行规格，尤其防止 「NOTICE_1」、「CASE_1」、「CASE_1:intake-accepted」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void marksOnlyTheRecipientsOwnMessageAsRead() {
        NotificationEntity existing =
                NotificationEntity.create(
                        "NOTICE_1",
                        "CASE_1",
                        "CASE_1:intake-accepted",
                        "merchant-local",
                        ActorRole.MERCHANT,
                        NotificationType.DISPUTE_SUMMONS,
                        "争议审理传票",
                        "请进入证据书记官室",
                        "/disputes/CASE_1/evidence",
                        "{}",
                        Instant.parse("2026-07-03T00:00:00Z"));
        when(notificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(
                        "NOTICE_1", "merchant-local"))
                .thenReturn(Optional.of(existing));

        var read =
                service.markRead(
                        "NOTICE_1",
                        new AuthenticatedActor(
                                "merchant-local", ActorRole.MERCHANT));

        assertThat(read.read()).isTrue();
        ArgumentCaptor<NotificationEntity> saved =
                ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getReadAt())
                .isEqualTo(Instant.parse("2026-07-03T00:00:00Z"));
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox()」。
    // 具体功能：「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox()」：复现“核对完整业务行为（场景方法「marksAllUnreadMessagesInTheCurrentRecipientsInbox」）”场景：驱动 「notificationRepository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc」、「notificationRepository.saveAll」、「service.markAllRead」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「NOTICE_1」、「CASE_1:intake-accepted」、「merchant-local」、「NOTICE_2」。
    // 上游调用：「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox()」守住「案件生命周期通知」的可执行规格，尤其防止 「NOTICE_1」、「CASE_1:intake-accepted」、「merchant-local」、「NOTICE_2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void marksAllUnreadMessagesInTheCurrentRecipientsInbox() {
        NotificationEntity unread =
                notification(
                        "NOTICE_1",
                        "CASE_1:intake-accepted",
                        "merchant-local");
        NotificationEntity alreadyRead =
                notification(
                        "NOTICE_2",
                        "CASE_1:hearing-opened",
                        "merchant-local");
        alreadyRead.markRead(Instant.parse("2026-07-02T23:00:00Z"));
        when(notificationRepository
                        .findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(
                                "merchant-local"))
                .thenReturn(List.of(unread, alreadyRead));
        when(notificationRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        long marked =
                service.markAllRead(
                        new AuthenticatedActor(
                                "merchant-local", ActorRole.MERCHANT));

        assertThat(marked).isEqualTo(1);
        assertThat(unread.getReadAt())
                .isEqualTo(Instant.parse("2026-07-03T00:00:00Z"));
        assertThat(alreadyRead.getReadAt())
                .isEqualTo(Instant.parse("2026-07-02T23:00:00Z"));
        verify(notificationRepository).saveAll(List.of(unread));
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification()」。
    // 具体功能：「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification()」：复现“核对完整业务行为（场景方法「dismissesOnlyTheCurrentRecipientsOwnNotification」）”场景：驱动 「notificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull」、「service.dismiss」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「NOTICE_1」、「CASE_1:hearing-opened」、「user-local」、「2026-07-03T00:00:00Z」。
    // 上游调用：「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification()」守住「案件生命周期通知」的可执行规格，尤其防止 「NOTICE_1」、「CASE_1:hearing-opened」、「user-local」、「2026-07-03T00:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void dismissesOnlyTheCurrentRecipientsOwnNotification() {
        NotificationEntity own =
                notification(
                        "NOTICE_1",
                        "CASE_1:hearing-opened",
                        "user-local");
        when(notificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(
                        "NOTICE_1", "user-local"))
                .thenReturn(Optional.of(own));

        service.dismiss(
                "NOTICE_1",
                new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(own.getDismissedAt())
                .isEqualTo(Instant.parse("2026-07-03T00:00:00Z"));
        verify(notificationRepository).save(own);
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.doesNotExposeAnotherRecipientsNotificationForDeletion()」。
    // 具体功能：「NotificationServiceTest.doesNotExposeAnotherRecipientsNotificationForDeletion()」：复现“核对完整业务行为（场景方法「doesNotExposeAnotherRecipientsNotificationForDeletion」）”场景：驱动 「notificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull」、「service.dismiss」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「NOTICE_1」、「merchant-local」。
    // 上游调用：「NotificationServiceTest.doesNotExposeAnotherRecipientsNotificationForDeletion()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「NotificationServiceTest.doesNotExposeAnotherRecipientsNotificationForDeletion()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「NotificationServiceTest.doesNotExposeAnotherRecipientsNotificationForDeletion()」守住「案件生命周期通知」的可执行规格，尤其防止 「NOTICE_1」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void doesNotExposeAnotherRecipientsNotificationForDeletion() {
        when(notificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(
                        "NOTICE_1", "merchant-local"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.dismiss(
                                        "NOTICE_1",
                                        new AuthenticatedActor(
                                                "merchant-local",
                                                ActorRole.MERCHANT)))
                .hasMessageContaining("notification not visible");

        verify(notificationRepository, never()).save(any());
    }

    // 所属模块：【案件生命周期通知 / 自动化测试层】「NotificationServiceTest.notification(String,String,String)」。
    // 具体功能：「NotificationServiceTest.notification(String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「notification」）”组装或读取「NotificationEntity.create」、「Instant.parse」，供本测试类的场景方法复用。
    // 上游调用：「NotificationServiceTest.notification(String,String,String)」由本测试类中的 「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox」、「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification」 调用。
    // 下游影响：「NotificationServiceTest.notification(String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「NotificationServiceTest.notification(String,String,String)」守住「案件生命周期通知」的可执行规格，尤其防止 「CASE_1」、「争议审理传票」、「请进入对应房间」、「{}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static NotificationEntity notification(
            String id, String businessEventKey, String recipientId) {
        return NotificationEntity.create(
                id,
                "CASE_1",
                businessEventKey,
                recipientId,
                ActorRole.MERCHANT,
                NotificationType.DISPUTE_SUMMONS,
                "争议审理传票",
                "请进入对应房间",
                "/disputes/CASE_1",
                "{}",
                Instant.parse("2026-07-02T22:00:00Z"));
    }
}
