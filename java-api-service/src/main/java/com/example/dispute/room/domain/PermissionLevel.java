/*
 * 所属模块：房间协作与权限。
 * 文件职责：限定权限级别允许出现的状态值。
 * 业务链路：核心入口/契约为 「defaultScopes」、「privileged」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

// 所属模块：【房间协作与权限 / 领域模型层】类型「PermissionLevel」。
// 类型职责：限定权限级别允许出现的状态值；本类型显式提供 「PermissionLevel」、「defaultScopes」、「privileged」。
// 协作关系：主要由 「CaseAccessSessionEntity.create」、「CaseAccessSessionEntity.permissionScopes」、「CaseAccessSessionEntity.privileged」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：enum 把允许值限制为固定常量，switch 可穷举状态分支。
public enum PermissionLevel {
    PARTY_USER(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.INTAKE_PARTICIPATE,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.EVIDENCE_SUBMIT,
            PermissionScope.EVIDENCE_PRIVATE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.HEARING_PARTICIPATE,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    PARTY_MERCHANT(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.INTAKE_PARTICIPATE,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.EVIDENCE_SUBMIT,
            PermissionScope.EVIDENCE_PRIVATE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.HEARING_PARTICIPATE,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    SERVICE_ASSIST(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.INTAKE_PARTICIPATE,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    REVIEWER_ALL(
            PermissionScope.CASE_READ,
            PermissionScope.ROOM_MESSAGE_READ,
            PermissionScope.ROOM_MESSAGE_WRITE,
            PermissionScope.INTAKE_PRIVATE_READ,
            PermissionScope.EVIDENCE_READ,
            PermissionScope.EVIDENCE_SUBMIT,
            PermissionScope.EVIDENCE_PRIVATE_READ,
            PermissionScope.HEARING_READ,
            PermissionScope.HEARING_PARTICIPATE,
            PermissionScope.REVIEW_READ,
            PermissionScope.REVIEW_DECIDE,
            PermissionScope.OUTCOME_READ,
            PermissionScope.NOTIFICATION_READ,
            PermissionScope.AGENT_SESSION_READ,
            PermissionScope.AGENT_SESSION_WRITE),
    ADMIN_ALL(PermissionScope.values()),
    SYSTEM_ALL(PermissionScope.values());

    private final Set<PermissionScope> defaultScopes;

    // 所属模块：【房间协作与权限 / 领域模型层】「PermissionLevel.PermissionLevel()」。
    // 具体功能：「PermissionLevel.PermissionLevel()」：创建「PermissionLevel」实例并保留框架或测试所需的无参构造入口；真正的业务状态由后续工厂方法、JPA 或字段赋值完成。
    // 上游调用：「PermissionLevel.PermissionLevel()」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「PermissionLevel.PermissionLevel()」向下依次触达 「PermissionScope.values」、「Collections.unmodifiableSet」、「EnumSet.allOf」、「EnumSet.noneOf」。
    // 系统意义：「PermissionLevel.PermissionLevel()」负责主链路中的“权限级别”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    PermissionLevel(PermissionScope... scopes) {
        if (scopes.length == PermissionScope.values().length) {
            this.defaultScopes = Collections.unmodifiableSet(EnumSet.allOf(PermissionScope.class));
            return;
        }
        EnumSet<PermissionScope> set = EnumSet.noneOf(PermissionScope.class);
        Collections.addAll(set, scopes);
        this.defaultScopes = Collections.unmodifiableSet(set);
    }

    // 所属模块：【房间协作与权限 / 领域模型层】「PermissionLevel.defaultScopes()」。
    // 具体功能：「PermissionLevel.defaultScopes()」：构建默认权限范围，最终返回「Set<PermissionScope>」。
    // 上游调用：「PermissionLevel.defaultScopes()」的上游调用点包括 「CaseAccessSessionEntity.create」、「CaseAccessSessionEntity.permissionScopes」。
    // 下游影响：「PermissionLevel.defaultScopes()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「Set<PermissionScope>」交给调用方。
    // 系统意义：「PermissionLevel.defaultScopes()」负责主链路中的“默认权限范围”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public Set<PermissionScope> defaultScopes() {
        return defaultScopes;
    }

    // 所属模块：【房间协作与权限 / 领域模型层】「PermissionLevel.privileged()」。
    // 具体功能：「PermissionLevel.privileged()」：判断高权限级别，最终返回「boolean」。
    // 上游调用：「PermissionLevel.privileged()」的上游调用点包括 「CaseAccessSessionEntity.privileged」。
    // 下游影响：「PermissionLevel.privileged()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「PermissionLevel.privileged()」负责主链路中的“高权限级别”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public boolean privileged() {
        return this == REVIEWER_ALL || this == ADMIN_ALL || this == SYSTEM_ALL;
    }
}
