/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：承载平台审核员角色和身份授权在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「requireDecisionAccess」；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import com.example.dispute.common.exception.ForbiddenException;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「PlatformReviewerAuthorization」。
// 类型职责：承载平台审核员角色和身份授权在当前业务模块中的规则与协作边界；本类型显式提供 「PlatformReviewerAuthorization」、「requireDecisionAccess」。
// 协作关系：主要由 「CaseOutcomeService.confirmDraft」、「CaseOutcomeService.modifyDraft」、「ReviewApplicationService.decide」、「ReviewCopilotStreamService.active」 使用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class PlatformReviewerAuthorization {

    public static final String SYSTEM_REVIEWER_ID = "reviewer-local";

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「PlatformReviewerAuthorization.PlatformReviewerAuthorization()」。
    // 具体功能：「PlatformReviewerAuthorization.PlatformReviewerAuthorization()」：创建「PlatformReviewerAuthorization」实例并保留框架或测试所需的无参构造入口；真正的业务状态由后续工厂方法、JPA 或字段赋值完成。
    // 上游调用：「PlatformReviewerAuthorization.PlatformReviewerAuthorization()」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「PlatformReviewerAuthorization.PlatformReviewerAuthorization()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PlatformReviewerAuthorization.PlatformReviewerAuthorization()」负责主链路中的“Platform审核员授权”；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private PlatformReviewerAuthorization() {}

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「PlatformReviewerAuthorization.requireDecisionAccess(AuthenticatedActor)」。
    // 具体功能：「PlatformReviewerAuthorization.requireDecisionAccess(AuthenticatedActor)」：校验决定访问；实际协作者为 「actor.role」、「actor.actorId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「PlatformReviewerAuthorization.requireDecisionAccess(AuthenticatedActor)」的上游调用点包括 「CaseOutcomeService.confirmDraft」、「CaseOutcomeService.modifyDraft」、「ReviewApplicationService.decide」、「ReviewCopilotStreamService.query」。
    // 下游影响：「PlatformReviewerAuthorization.requireDecisionAccess(AuthenticatedActor)」向下依次触达 「actor.role」、「actor.actorId」。
    // 系统意义：「PlatformReviewerAuthorization.requireDecisionAccess(AuthenticatedActor)」在“决定访问”进入下游前阻断非法状态；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    public static void requireDecisionAccess(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.PLATFORM_REVIEWER
                || !SYSTEM_REVIEWER_ID.equals(actor.actorId())) {
            throw new ForbiddenException(
                    "only the system platform reviewer can decide");
        }
    }
}
