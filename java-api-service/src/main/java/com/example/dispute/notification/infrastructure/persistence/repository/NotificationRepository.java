/*
 * 所属模块：案件生命周期通知。
 * 文件职责：声明通知在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findByBusinessEventKeyAndRecipientId」、「findByIdAndRecipientIdAndDismissedAtIsNull」、「findAllByRecipientIdOrderByCreatedAtDesc」、「findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc」、「countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull」；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.infrastructure.persistence.repository;

import com.example.dispute.notification.infrastructure.persistence.entity.NotificationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【案件生命周期通知 / 仓储接口层】类型「NotificationRepository」。
// 类型职责：声明通知在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findByBusinessEventKeyAndRecipientId」、「findByIdAndRecipientIdAndDismissedAtIsNull」、「findAllByRecipientIdOrderByCreatedAtDesc」、「findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc」、「countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull」。
// 协作关系：主要由 「NotificationService.dismiss」、「NotificationService.list」、「NotificationService.markAllRead」、「NotificationService.markRead」 使用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface NotificationRepository extends JpaRepository<NotificationEntity, String> {

    // 所属模块：【案件生命周期通知 / 仓储接口层】「NotificationRepository.findByBusinessEventKeyAndRecipientId(String,String)」。
    // 具体功能：「NotificationRepository.findByBusinessEventKeyAndRecipientId(String,String)」：声明按Business事件键、Recipient标识访问通知的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<NotificationEntity>」返回。
    // 上游调用：「NotificationRepository.findByBusinessEventKeyAndRecipientId(String,String)」的上游调用点包括 「NotificationService.send」、「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons」、「NotificationServiceTest.replayingTheSameBusinessEventReturnsTheExistingInboxMessage」。
    // 下游影响：「NotificationRepository.findByBusinessEventKeyAndRecipientId(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「NotificationRepository.findByBusinessEventKeyAndRecipientId(String,String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<NotificationEntity> findByBusinessEventKeyAndRecipientId(
            String businessEventKey, String recipientId);

    // 所属模块：【案件生命周期通知 / 仓储接口层】「NotificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(String,String)」。
    // 具体功能：「NotificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(String,String)」：声明按标识、Recipient标识、DismissedAtIs空值访问通知的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「Optional<NotificationEntity>」返回。
    // 上游调用：「NotificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(String,String)」的上游调用点包括 「NotificationService.markRead」、「NotificationService.dismiss」、「NotificationServiceTest.marksOnlyTheRecipientsOwnMessageAsRead」、「NotificationServiceTest.dismissesOnlyTheCurrentRecipientsOwnNotification」。
    // 下游影响：「NotificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(String,String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「NotificationRepository.findByIdAndRecipientIdAndDismissedAtIsNull(String,String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    Optional<NotificationEntity> findByIdAndRecipientIdAndDismissedAtIsNull(
            String id, String recipientId);

    // 所属模块：【案件生命周期通知 / 仓储接口层】「NotificationRepository.findAllByRecipientIdOrderByCreatedAtDesc(String)」。
    // 具体功能：「NotificationRepository.findAllByRecipientIdOrderByCreatedAtDesc(String)」：声明按Recipient标识访问通知的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<NotificationEntity>」返回。
    // 上游调用：「NotificationRepository.findAllByRecipientIdOrderByCreatedAtDesc(String)」的上游调用点包括 「EvidenceRoomIntegrationTest.bothPartiesFreezeExactlyOneVersionAndRejectedEvidenceIsExcluded」、「IntakeRoomServiceIntegrationTest.acceptedIntakePersistsParticipantsRoomsAndTheAuthoritativeDeadline」。
    // 下游影响：「NotificationRepository.findAllByRecipientIdOrderByCreatedAtDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「NotificationRepository.findAllByRecipientIdOrderByCreatedAtDesc(String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<NotificationEntity> findAllByRecipientIdOrderByCreatedAtDesc(String recipientId);

    // 所属模块：【案件生命周期通知 / 仓储接口层】「NotificationRepository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(String)」。
    // 具体功能：「NotificationRepository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(String)」：声明按Recipient标识、DismissedAtIs空值访问通知的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<NotificationEntity>」返回。
    // 上游调用：「NotificationRepository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(String)」的上游调用点包括 「NotificationService.list」、「NotificationService.markAllRead」、「NotificationServiceTest.marksAllUnreadMessagesInTheCurrentRecipientsInbox」。
    // 下游影响：「NotificationRepository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「NotificationRepository.findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    List<NotificationEntity> findAllByRecipientIdAndDismissedAtIsNullOrderByCreatedAtDesc(
            String recipientId);

    // 所属模块：【案件生命周期通知 / 仓储接口层】「NotificationRepository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull(String)」。
    // 具体功能：「NotificationRepository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull(String)」：声明按Recipient标识、ReadAtIs空值、DismissedAtIs空值访问通知的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「long」返回。
    // 上游调用：「NotificationRepository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull(String)」的上游调用点包括 「NotificationService.unreadCount」。
    // 下游影响：「NotificationRepository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「NotificationRepository.countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull(String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    long countByRecipientIdAndReadAtIsNullAndDismissedAtIsNull(String recipientId);
}
