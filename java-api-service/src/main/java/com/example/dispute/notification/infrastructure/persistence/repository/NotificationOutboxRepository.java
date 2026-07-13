/*
 * 所属模块：案件生命周期通知。
 * 文件职责：声明通知发件箱在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「existsByBusinessEventKey」；根据案件阶段向用户、商家和平台人员投递站内通知并维护已读状态。
 * 关键边界：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
 */
package com.example.dispute.notification.infrastructure.persistence.repository;

import com.example.dispute.notification.infrastructure.persistence.entity.NotificationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

// 所属模块：【案件生命周期通知 / 仓储接口层】类型「NotificationOutboxRepository」。
// 类型职责：声明通知发件箱在 PostgreSQL 中的查询与写入契约；本类型显式提供 「existsByBusinessEventKey」。
// 协作关系：主要由 「NotificationService.create」、「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons」 使用。
// 边界意义：通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface NotificationOutboxRepository
        extends JpaRepository<NotificationOutboxEntity, String> {

    // 所属模块：【案件生命周期通知 / 仓储接口层】「NotificationOutboxRepository.existsByBusinessEventKey(String)」。
    // 具体功能：「NotificationOutboxRepository.existsByBusinessEventKey(String)」：声明按Business事件键访问通知发件箱的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「boolean」返回。
    // 上游调用：「NotificationOutboxRepository.existsByBusinessEventKey(String)」的上游调用点包括 「NotificationService.create」、「NotificationServiceTest.createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons」。
    // 下游影响：「NotificationOutboxRepository.existsByBusinessEventKey(String)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「NotificationOutboxRepository.existsByBusinessEventKey(String)」直接影响 PostgreSQL 事实投影；通知是事务后的派生副作用，失败不能回滚已提交业务事实，也不能重复轰炸接收者
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    boolean existsByBusinessEventKey(String businessEventKey);
}
