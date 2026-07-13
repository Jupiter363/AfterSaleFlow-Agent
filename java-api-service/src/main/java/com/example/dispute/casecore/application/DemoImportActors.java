/*
 * 所属模块：案件核心与导入。
 * 文件职责：承载演示案件导入Actors在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「requireImportedParties」、「requireSimulationParties」；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;

/** Fixed demo accounts accepted by external import boundaries. */
// 所属模块：【案件核心与导入 / 应用编排层】类型「DemoImportActors」。
// 类型职责：承载演示案件导入Actors在当前业务模块中的规则与协作边界；本类型显式提供 「DemoImportActors」、「requireImportedParties」、「requireSimulationParties」。
// 协作关系：主要由 「ImportDisputeCommand.ImportDisputeCommand」、「SimulateExternalImportCommand.SimulateExternalImportCommand」、「SimulatedExternalDispute.SimulatedExternalDispute」 使用。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class DemoImportActors {

    public static final String USER_ID = "user-local";
    public static final String MERCHANT_ID = "merchant-local";

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoImportActors.DemoImportActors()」。
    // 具体功能：「DemoImportActors.DemoImportActors()」：创建「DemoImportActors」实例并保留框架或测试所需的无参构造入口；真正的业务状态由后续工厂方法、JPA 或字段赋值完成。
    // 上游调用：「DemoImportActors.DemoImportActors()」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「DemoImportActors.DemoImportActors()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DemoImportActors.DemoImportActors()」负责主链路中的“演示案件导入Actors”；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private DemoImportActors() {}

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoImportActors.requireImportedParties(String,String)」。
    // 具体功能：「DemoImportActors.requireImportedParties(String,String)」：强制校验导入案件参与方；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「DemoImportActors.requireImportedParties(String,String)」的上游调用点包括 「ImportDisputeCommand.ImportDisputeCommand」、「SimulatedExternalDispute.SimulatedExternalDispute」。
    // 下游影响：「DemoImportActors.requireImportedParties(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DemoImportActors.requireImportedParties(String,String)」在“导入案件参与方”进入下游前阻断非法状态；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public static void requireImportedParties(String userId, String merchantId) {
        if (!USER_ID.equals(userId)) {
            throw new IllegalArgumentException("userId must be user-local");
        }
        if (!MERCHANT_ID.equals(merchantId)) {
            throw new IllegalArgumentException("merchantId must be merchant-local");
        }
    }

    // 所属模块：【案件核心与导入 / 应用编排层】「DemoImportActors.requireSimulationParties(ActorRole,String,String)」。
    // 具体功能：「DemoImportActors.requireSimulationParties(ActorRole,String,String)」：强制校验模拟参与方；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「DemoImportActors.requireSimulationParties(ActorRole,String,String)」的上游调用点包括 「SimulateExternalImportCommand.SimulateExternalImportCommand」。
    // 下游影响：「DemoImportActors.requireSimulationParties(ActorRole,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DemoImportActors.requireSimulationParties(ActorRole,String,String)」在“模拟参与方”进入下游前阻断非法状态；Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    public static void requireSimulationParties(
            ActorRole initiatorRole,
            String currentActorId,
            String counterpartyActorId) {
        String expectedCurrent =
                initiatorRole == ActorRole.USER ? USER_ID : MERCHANT_ID;
        String expectedCounterparty =
                initiatorRole == ActorRole.USER ? MERCHANT_ID : USER_ID;
        if (!expectedCurrent.equals(currentActorId)) {
            throw new IllegalArgumentException(
                    "currentActorId must be " + expectedCurrent);
        }
        if (!expectedCounterparty.equals(counterpartyActorId)) {
            throw new IllegalArgumentException(
                    "counterpartyActorId must be " + expectedCounterparty);
        }
    }
}
