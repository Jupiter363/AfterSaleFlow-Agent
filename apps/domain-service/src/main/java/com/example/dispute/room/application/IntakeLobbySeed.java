/*
 * 所属模块：房间协作与权限。
 * 文件职责：定义接待接待大厅种子数据跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

// 所属模块：【房间协作与权限 / 应用编排层】类型「IntakeLobbySeed」。
// 类型职责：定义接待接待大厅种子数据跨层传递时使用的不可变数据契约；本类型显式提供 「IntakeLobbySeed」、「IntakeLobbySeed」。
// 协作关系：主要由 「CaseApplicationService.createNew」、「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「IntakeAgentTurnService.sanitizeLobbySeed」、「IntakeAgentTurnServiceTest.initialTurnOmitsShortLobbySeedReferencesBeforeCallingAgent」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record IntakeLobbySeed(
        @JsonProperty("order_reference") String orderReference,
        @JsonProperty("after_sales_reference") String afterSalesReference,
        @JsonProperty("logistics_reference") String logisticsReference,
        @JsonProperty("initiator_role") String initiatorRole,
        @JsonProperty("raw_text") String rawText,
        @JsonProperty("requested_outcome_hint") String requestedOutcomeHint,
        @JsonProperty("claim_resolution_seed") ClaimResolutionSeed claimResolutionSeed,
        @JsonProperty("respondent_attitude_seed") RespondentAttitudeSeed respondentAttitudeSeed) {

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String)」。
    // 具体功能：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String)」：使用 「orderReference」(String)、「afterSalesReference」(String)、「logisticsReference」(String)、「initiatorRole」(String)、「rawText」(String)、「requestedOutcomeHint」(String) 初始化「IntakeLobbySeed」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String)」的上游创建点包括 「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「CaseApplicationService.createNew」、「IntakeAgentTurnService.sanitizeLobbySeed」、「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」。
    // 下游影响：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String)」负责主链路中的“接待接待大厅种子数据”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public IntakeLobbySeed(
            String orderReference,
            String afterSalesReference,
            String logisticsReference,
            String initiatorRole,
            String rawText,
            String requestedOutcomeHint) {
        this(
                orderReference,
                afterSalesReference,
                logisticsReference,
                initiatorRole,
                rawText,
                requestedOutcomeHint,
                null,
                null);
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed)」。
    // 具体功能：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed)」：在不可变「IntakeLobbySeed」写入组件前校验 「orderReference」(String)、「afterSalesReference」(String)、「logisticsReference」(String)、「initiatorRole」(String)、「rawText」(String)、「requestedOutcomeHint」(String)、「claimResolutionSeed」(ClaimResolutionSeed)、「respondentAttitudeSeed」(RespondentAttitudeSeed)，并通过 「claimResolutionSeed.requestedResolution」 做标准化或防御性复制。
    // 上游调用：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed)」的上游创建点包括 「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「CaseApplicationService.createNew」、「IntakeAgentTurnService.sanitizeLobbySeed」、「IntakeAgentTurnServiceTest.initialTurnUsesLobbySeedAndPersistsAgentScrollSnapshot」。
    // 下游影响：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed)」向下依次触达 「claimResolutionSeed.requestedResolution」。
    // 系统意义：「IntakeLobbySeed.IntakeLobbySeed(String,String,String,String,String,String,ClaimResolutionSeed,RespondentAttitudeSeed)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public IntakeLobbySeed {
        if ((requestedOutcomeHint == null || requestedOutcomeHint.isBlank())
                && claimResolutionSeed != null
                && claimResolutionSeed.requestedResolution() != null
                && !claimResolutionSeed.requestedResolution().isBlank()) {
            requestedOutcomeHint = claimResolutionSeed.requestedResolution();
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「ClaimResolutionSeed」。
    // 类型职责：定义主张Resolution种子数据跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record ClaimResolutionSeed(
            @JsonProperty("initiator_role") @Pattern(regexp = "USER|MERCHANT") String initiatorRole,
            @JsonProperty("requested_resolution")
                    @Pattern(
                            regexp =
                                    "REFUND|RETURN_REFUND|RESHIP|REPLACE_OR_REPAIR|COMPENSATION|CANCEL_ORDER|VERIFY_OR_EXPLAIN_ONLY|OTHER|UNKNOWN")
                    String requestedResolution,
            @JsonProperty("requested_amount") @PositiveOrZero @Digits(integer = 12, fraction = 2)
                    BigDecimal requestedAmount,
            @JsonProperty("requested_items") @Size(max = 512) String requestedItems,
            @JsonProperty("request_reason") @Size(max = 4000) String requestReason,
            @JsonProperty("original_statement") @Size(max = 4000) String originalStatement) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「RespondentAttitudeSeed」。
    // 类型职责：定义被申请方态度种子数据跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record RespondentAttitudeSeed(
            @JsonProperty("respondent_role") @Pattern(regexp = "USER|MERCHANT") String respondentRole,
            @JsonProperty("attitude")
                    @Pattern(
                            regexp =
                                    "NOT_RESPONDED|AGREE|PARTIALLY_AGREE|DISAGREE|ALTERNATIVE_PROPOSED|NEED_MORE_INFO|PLATFORM_UNKNOWN")
                    String attitude,
            @JsonProperty("position") @Size(max = 4000) String position,
            @JsonProperty("source") @Size(max = 128) String source,
            @JsonProperty("confidence") @DecimalMin("0.0") @DecimalMax("1.0") Double confidence) {}
}
