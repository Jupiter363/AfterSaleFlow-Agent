/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：承载争议的类型化配置并在启动期完成约束校验。
 * 业务链路：该文件主要提供类型或包级契约；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 所属模块：【身份鉴权与运行配置 / 核心业务层】类型「DisputeProperties」。
// 类型职责：承载争议的类型化配置并在启动期完成约束校验；本类型显式提供 「DisputeProperties」、「requirePositive」。
// 协作关系：主要由 「DisputeImportServiceTest.setUp」、「EvidenceCompletionServiceTest.setUp」、「HearingWorkflowCoordinatorTest.disputeProperties」、「IntakeRoomServiceTest.setUp」 使用。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
@ConfigurationProperties(prefix = "dispute")
public record DisputeProperties(
        @DefaultValue("PT2H") Duration evidenceWindow,
        @DefaultValue("PT3H") Duration hearingWindow,
        @DefaultValue("PT20M") Duration hearingPartyStageWindow,
        @DefaultValue("PT15S") Duration sseHeartbeat,
        @DefaultValue("true") boolean seedDemoDisputes) {

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「DisputeProperties.DisputeProperties(Duration,Duration,Duration,int,Duration,boolean)」。
    // 具体功能：「DisputeProperties.DisputeProperties(Duration,Duration,Duration,int,Duration,boolean)」：在不可变「DisputeProperties」写入组件前校验 「evidenceWindow」(Duration)、「hearingWindow」(Duration)、「hearingRoundWindow」(Duration)、「maxHearingRounds」(int)、「sseHeartbeat」(Duration)、「seedDemoDisputes」(boolean)，非法输入会抛出 「IllegalArgumentException」；并通过 「requirePositive」 做标准化或防御性复制。
    // 上游调用：「DisputeProperties.DisputeProperties(Duration,Duration,Duration,int,Duration,boolean)」的上游创建点包括 「DisputeImportServiceTest.setUp」、「SimulatedExternalImportTemplateCycleTest.transactionService」、「EvidenceCompletionServiceTest.setUp」、「HearingWorkflowCoordinatorTest.disputeProperties」。
    // 下游影响：「DisputeProperties.DisputeProperties(Duration,Duration,Duration,int,Duration,boolean)」向下依次触达 「requirePositive」。
    // 系统意义：「DisputeProperties.DisputeProperties(Duration,Duration,Duration,int,Duration,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public DisputeProperties {
        requirePositive(evidenceWindow, "evidence-window");
        requirePositive(hearingWindow, "hearing-window");
        requirePositive(hearingPartyStageWindow, "hearing-party-stage-window");
        requirePositive(sseHeartbeat, "sse-heartbeat");
    }

    // 所属模块：【身份鉴权与运行配置 / 核心业务层】「DisputeProperties.requirePositive(Duration,String)」。
    // 具体功能：「DisputeProperties.requirePositive(Duration,String)」：强制校验正整数；实际协作者为 「value.isZero」、「value.isNegative」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「DisputeProperties.requirePositive(Duration,String)」的上游调用点包括 「DisputeProperties.DisputeProperties」。
    // 下游影响：「DisputeProperties.requirePositive(Duration,String)」向下依次触达 「value.isZero」、「value.isNegative」。
    // 系统意义：「DisputeProperties.requirePositive(Duration,String)」在“正整数”进入下游前阻断非法状态；调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
