/*
 * 所属模块：审计追踪。
 * 文件职责：定义审计Log跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；查询不可变审计事实，使管理端能够追溯操作者、业务对象和状态变更。
 * 关键边界：审计数据只追加不回写，普通当事人不能读取平台内部记录
 */
package com.example.dispute.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

// 所属模块：【审计追踪 / 应用编排层】类型「AuditLogView」。
// 类型职责：定义审计Log跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：审计数据只追加不回写，普通当事人不能读取平台内部记录
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AuditLogView(
        String id,
        String caseId,
        String traceId,
        String requestId,
        String actorId,
        String role,
        String service,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        JsonNode before,
        JsonNode after,
        JsonNode metadata,
        OffsetDateTime createdAt) {}
