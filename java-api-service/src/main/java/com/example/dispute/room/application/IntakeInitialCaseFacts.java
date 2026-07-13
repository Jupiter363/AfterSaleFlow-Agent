/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义接待Initial案件事实跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「from」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;

// 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeInitialCaseFacts」。
// 类型职责：定义接待Initial案件事实跨层传递时使用的不可变数据契约；本类型显式提供 「from」、「formClaim」。
// 协作关系：主要由 「IntakeAgentTurnService.startInitialTurn」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record IntakeInitialCaseFacts(
        @JsonProperty("form_source") String formSource,
        @JsonProperty("form_description") String formDescription,
        @JsonProperty("order_reference") String orderReference,
        @JsonProperty("after_sales_reference") String afterSalesReference,
        @JsonProperty("logistics_reference") String logisticsReference,
        @JsonProperty("initiator_role") String initiatorRole,
        @JsonProperty("requested_outcome_hint") String requestedOutcomeHint,
        @JsonProperty("claim_resolution_seed")
                IntakeLobbySeed.ClaimResolutionSeed claimResolutionSeed,
        @JsonProperty("respondent_attitude_seed")
                IntakeLobbySeed.RespondentAttitudeSeed respondentAttitudeSeed) {

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeInitialCaseFacts.from(IntakeLobbySeed,String)」。
    // 具体功能：「IntakeInitialCaseFacts.from(IntakeLobbySeed,String)」：转换接待Initial案件事实；实际协作者为 「seed.rawText」、「seed.orderReference」、「seed.afterSalesReference」、「seed.logisticsReference」，最终返回「IntakeInitialCaseFacts」。
    // 上游调用：「IntakeInitialCaseFacts.from(IntakeLobbySeed,String)」的上游调用点包括 「IntakeAgentTurnService.startInitialTurn」。
    // 下游影响：「IntakeInitialCaseFacts.from(IntakeLobbySeed,String)」向下依次触达 「seed.rawText」、「seed.orderReference」、「seed.afterSalesReference」、「seed.logisticsReference」；计算结果以「IntakeInitialCaseFacts」交给调用方。
    // 系统意义：「IntakeInitialCaseFacts.from(IntakeLobbySeed,String)」统一“接待Initial案件事实”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public static IntakeInitialCaseFacts from(IntakeLobbySeed seed, String formSource) {
        return new IntakeInitialCaseFacts(
                formSource,
                seed.rawText(),
                seed.orderReference(),
                seed.afterSalesReference(),
                seed.logisticsReference(),
                seed.initiatorRole(),
                seed.requestedOutcomeHint(),
                formClaim(seed.claimResolutionSeed()),
                null);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeInitialCaseFacts.formClaim(ClaimResolutionSeed)」。
    // 具体功能：「IntakeInitialCaseFacts.formClaim(ClaimResolutionSeed)」：构建form主张；实际协作者为 「seed.initiatorRole」、「seed.requestedResolution」、「seed.requestedAmount」、「seed.requestedItems」，最终返回「IntakeLobbySeed.ClaimResolutionSeed」。
    // 上游调用：「IntakeInitialCaseFacts.formClaim(ClaimResolutionSeed)」的上游调用点包括 「IntakeInitialCaseFacts.from」。
    // 下游影响：「IntakeInitialCaseFacts.formClaim(ClaimResolutionSeed)」向下依次触达 「seed.initiatorRole」、「seed.requestedResolution」、「seed.requestedAmount」、「seed.requestedItems」；计算结果以「IntakeLobbySeed.ClaimResolutionSeed」交给调用方。
    // 系统意义：「IntakeInitialCaseFacts.formClaim(ClaimResolutionSeed)」负责主链路中的“form主张”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static IntakeLobbySeed.ClaimResolutionSeed formClaim(
            IntakeLobbySeed.ClaimResolutionSeed seed) {
        if (seed == null) {
            return null;
        }
        return new IntakeLobbySeed.ClaimResolutionSeed(
                seed.initiatorRole(),
                seed.requestedResolution(),
                seed.requestedAmount(),
                seed.requestedItems(),
                seed.requestReason(),
                null);
    }
}
