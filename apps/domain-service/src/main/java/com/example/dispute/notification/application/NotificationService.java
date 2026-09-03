/*
 * 所属模块：案件生命周期通知。
 * 文件职责：编排通知规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「send」、「list」、「unreadCount」、「markRead」、「markAllRead」、「dismiss」；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationEntity;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationOutboxEntity;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【案件生命周期通知 / 应用编排层】类型「NotificationService」。
// 类型职责：编排通知规则、权限校验与事实读写；本类型显式提供 「NotificationService」、「send」、「list」、「unreadCount」、「markRead」、「markAllRead」。
// 协作关系：主要由 「CaseLifecycleNotificationService.send」、「EvidenceCompletionService.sendHearingNotice」、「IntakeRoomService.sendSummonsTo」、「NotificationController.dismiss」 使用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationOutboxRepository outboxRepository;
    private final Clock clock;

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.NotificationService(NotificationRepository,NotificationOutboxRepository,Clock)」。
    // 具体功能：「NotificationService.NotificationService(NotificationRepository,NotificationOutboxRepository,Clock)」：通过构造器接收 「repository」(NotificationRepository)、「outboxRepository」(NotificationOutboxRepository)、「clock」(Clock) 并保存为「NotificationService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「NotificationService.NotificationService(NotificationRepository,NotificationOutboxRepository,Clock)」的上游创建点包括 「NotificationServiceTest.setUp」。
    // 下游影响：「NotificationService.NotificationService(NotificationRepository,NotificationOutboxRepository,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「NotificationService.NotificationService(NotificationRepository,NotificationOutboxRepository,Clock)」负责主链路中的“通知服务”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public NotificationService(
            NotificationRepository repository,
            NotificationOutboxRepository outboxRepository,
            Clock clock) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.send(NotificationCommand)」。
    // 具体功能：「NotificationService.send(NotificationCommand)」：发送通知：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「repository.findByBusinessEventKeyAndRecipientId」、「command.businessEventKey」、「command.recipientId」、「create」，最终返回「NotificationView」。
    // 上游调用：「NotificationService.send(NotificationCommand)」的上游调用点包括 「EvidenceCompletionService.sendHearingNotice」、「SettlementService.sendConfirmationNotice」、「CaseLifecycleNotificationService.send」、「IntakeRoomService.sendSummonsTo」。
    // 下游影响：「NotificationService.send(NotificationCommand)」向下依次触达 「repository.findByBusinessEventKeyAndRecipientId」、「command.businessEventKey」、「command.recipientId」、「create」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「NotificationService.send(NotificationCommand)」定义原子提交边界；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public NotificationView send(NotificationCommand command) {
        return repository
                .findByBusinessEventKeyAndRecipientId(
                        command.businessEventKey(), command.recipientId())
                .map(NotificationService::view)
                .orElseGet(() -> create(command));
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.list(AuthenticatedActor)」。
    // 具体功能：「NotificationService.list(AuthenticatedActor)」：列出列表：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「repository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc」、「actor.actorId」，最终返回「List<NotificationView>」。
    // 上游调用：「NotificationService.list(AuthenticatedActor)」的上游调用点包括 「NotificationController.list」、「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox」。
    // 下游影响：「NotificationService.list(AuthenticatedActor)」向下依次触达 「repository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc」、「actor.actorId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「NotificationService.list(AuthenticatedActor)」定义原子提交边界；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public List<NotificationView> list(AuthenticatedActor actor) {
        return repository
                .findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(
                        actor.actorId())
                .stream()
                .map(NotificationService::view)
                .toList();
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.unreadCount(AuthenticatedActor)」。
    // 具体功能：「NotificationService.unreadCount(AuthenticatedActor)」：构建未读数量：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「repository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull」、「actor.actorId」，最终返回「long」。
    // 上游调用：「NotificationService.unreadCount(AuthenticatedActor)」的上游调用点包括 「NotificationController.unreadCount」、「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox」。
    // 下游影响：「NotificationService.unreadCount(AuthenticatedActor)」向下依次触达 「repository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull」、「actor.actorId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「NotificationService.unreadCount(AuthenticatedActor)」定义原子提交边界；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional(readOnly = true)
    public long unreadCount(AuthenticatedActor actor) {
        return repository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull(
                actor.actorId());
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.markRead(String,AuthenticatedActor)」。
    // 具体功能：「NotificationService.markRead(String,AuthenticatedActor)」：标记Read：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表，再把 Optional 空值转换为明确业务异常；实际协作者为 「repository.findByIdAndRecipientIdAndDismissedAtIsNull」、「repository.save」、「actor.actorId」、「entity.markRead」；处理的关键状态/协议值包括 「notification_id」，最终返回「NotificationView」。
    // 上游调用：「NotificationService.markRead(String,AuthenticatedActor)」的上游调用点包括 「NotificationController.markRead」、「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox」、「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead」。
    // 下游影响：「NotificationService.markRead(String,AuthenticatedActor)」向下依次触达 「repository.findByIdAndRecipientIdAndDismissedAtIsNull」、「repository.save」、「actor.actorId」、「entity.markRead」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「NotificationService.markRead(String,AuthenticatedActor)」定义原子提交边界；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public NotificationView markRead(String notificationId, AuthenticatedActor actor) {
        NotificationEntity entity =
                repository
                        .findByIdAndRecipientIdAndDismissedAtIsNull(
                                notificationId, actor.actorId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "notification not visible",
                                                Map.of(
                                                        "notification_id",
                                                        notificationId)));
        entity.markRead(clock.instant());
        repository.save(entity);
        return view(entity);
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.markAllRead(AuthenticatedActor)」。
    // 具体功能：「NotificationService.markAllRead(AuthenticatedActor)」：标记Read：先由 Spring 事务代理统一提交数据库变化；实际协作者为 「repository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc」、「repository.saveAll」、「clock.instant」、「actor.actorId」，最终返回「long」。
    // 上游调用：「NotificationService.markAllRead(AuthenticatedActor)」的上游调用点包括 「NotificationController.markAllRead」、「NotificationControllerTest.listsReadsAndCountsTheCurrentActorsInbox」、「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox」。
    // 下游影响：「NotificationService.markAllRead(AuthenticatedActor)」向下依次触达 「repository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc」、「repository.saveAll」、「clock.instant」、「actor.actorId」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「NotificationService.markAllRead(AuthenticatedActor)」定义原子提交边界；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public long markAllRead(AuthenticatedActor actor) {
        Instant now = clock.instant();
        List<NotificationEntity> unread =
                repository
                        .findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(
                                actor.actorId())
                        .stream()
                        .filter(entity -> entity.getReadAt() == null)
                        .peek(entity -> entity.markRead(now))
                        .toList();
        if (!unread.isEmpty()) {
            repository.saveAll(unread);
        }
        return unread.size();
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.dismiss(String,AuthenticatedActor)」。
    // 具体功能：「NotificationService.dismiss(String,AuthenticatedActor)」：按通知 ID 与当前接收者联合查询未删除通知，幂等写入 dismissedAt；其他用户即使知道通知 ID 也不能代为删除，最终返回「void」。
    // 上游调用：「NotificationService.dismiss(String,AuthenticatedActor)」的上游调用点包括 「NotificationController.dismiss」、「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification」、「NotificationServiceTest.doesNotExposeAnotherRecipientsNotificationForDeletion」。
    // 下游影响：「NotificationService.dismiss(String,AuthenticatedActor)」向下依次触达 「repository.findByIdAndRecipientIdAndDismissedAtIsNull」、「repository.save」、「actor.actorId」、「entity.dismiss」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「NotificationService.dismiss(String,AuthenticatedActor)」定义原子提交边界；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void dismiss(String notificationId, AuthenticatedActor actor) {
        NotificationEntity entity =
                repository
                        .findByIdAndRecipientIdAndDismissedAtIsNull(
                                notificationId, actor.actorId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "notification not visible",
                                                Map.of(
                                                        "notification_id",
                                                        notificationId)));
        entity.dismiss(clock.instant());
        repository.save(entity);
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.create(NotificationCommand)」。
    // 具体功能：「NotificationService.create(NotificationCommand)」：创建通知：先把新状态写入 PostgreSQL 事实表；实际协作者为 「repository.save」、「outboxRepository.existsByBusinessEventKey」、「outboxRepository.save」、「NotificationEntity.create」；处理的关键状态/协议值包括 「NOTICE_」、「OUTBOX_」，最终返回「NotificationView」。
    // 上游调用：「NotificationService.create(NotificationCommand)」的上游调用点包括 「NotificationService.send」。
    // 下游影响：「NotificationService.create(NotificationCommand)」向下依次触达 「repository.save」、「outboxRepository.existsByBusinessEventKey」、「outboxRepository.save」、「NotificationEntity.create」；计算结果以「NotificationView」交给调用方。
    // 系统意义：「NotificationService.create(NotificationCommand)」负责主链路中的“通知”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    private NotificationView create(NotificationCommand command) {
        Instant now = clock.instant();
        NotificationEntity saved =
                repository.save(
                        NotificationEntity.create(
                                "NOTICE_" + compactUuid(),
                                command.caseId(),
                                command.businessEventKey(),
                                command.recipientId(),
                                command.recipientRole(),
                                command.notificationType(),
                                command.title(),
                                command.body(),
                                command.deepLink(),
                                command.payloadJson(),
                                now));
        if (!outboxRepository.existsByBusinessEventKey(command.businessEventKey())) {
            outboxRepository.save(
                    NotificationOutboxEntity.pending(
                            "OUTBOX_" + compactUuid(),
                            command.caseId(),
                            command.businessEventKey(),
                            command.notificationType().name(),
                            command.payloadJson(),
                            now));
        }
        return view(saved);
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.view(NotificationEntity)」。
    // 具体功能：「NotificationService.view(NotificationEntity)」：构建视图；实际协作者为 「entity.getId」、「entity.getCaseId」、「entity.getRecipientId」、「entity.getRecipientRole」，最终返回「NotificationView」。
    // 上游调用：「NotificationService.view(NotificationEntity)」的上游调用点包括 「NotificationService.markRead」、「NotificationService.create」。
    // 下游影响：「NotificationService.view(NotificationEntity)」向下依次触达 「entity.getId」、「entity.getCaseId」、「entity.getRecipientId」、「entity.getRecipientRole」；计算结果以「NotificationView」交给调用方。
    // 系统意义：「NotificationService.view(NotificationEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    private static NotificationView view(NotificationEntity entity) {
        return new NotificationView(
                entity.getId(),
                entity.getCaseId(),
                entity.getRecipientId(),
                entity.getRecipientRole(),
                entity.getNotificationType(),
                entity.getTitle(),
                entity.getBody(),
                entity.getDeepLink(),
                entity.getReadAt() != null,
                entity.getCreatedAt(),
                entity.getReadAt());
    }

    // 所属模块：【案件生命周期通知 / 应用编排层】「NotificationService.compactUuid()」。
    // 具体功能：「NotificationService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「NotificationService.compactUuid()」的上游调用点包括 「NotificationService.create」。
    // 下游影响：「NotificationService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「NotificationService.compactUuid()」负责主链路中的“UUID”；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
