/*
 * 所属模块：Agent 流式运行。
 * 文件职责：维护可流式 Agent 操作及公开字段白名单白名单并按稳定键解析实现。
 * 业务链路：核心入口/契约为 「require」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Server-owned registry for model streaming operations.  Callers select a symbolic operation;
 * they never provide a Python URL or choose which model fields are public.
 */
// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentStreamOperationRegistry」。
// 类型职责：维护可流式 Agent 操作及公开字段白名单白名单并按稳定键解析实现；本类型显式提供 「AgentStreamOperationRegistry」、「require」、「register」。
// 协作关系：主要由 「AgentRunCoordinator.start」、「AgentRunLifecycleService.basePayload」、「AgentRunLifecycleService.claim」、「AgentRunLifecycleService.recordNonTerminalFrame」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AgentStreamOperationRegistry {

    private final Map<String, OperationDefinition> definitions;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamOperationRegistry.AgentStreamOperationRegistry()」。
    // 具体功能：「AgentStreamOperationRegistry.AgentStreamOperationRegistry()」：创建「AgentStreamOperationRegistry」实例并保留框架或测试所需的无参构造入口；真正的业务状态由后续工厂方法、JPA 或字段赋值完成。
    // 上游调用：「AgentStreamOperationRegistry.AgentStreamOperationRegistry()」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「AgentRunCoordinatorTest.setUp」、「AgentRunLifecycleServiceTest.setUp」 显式创建。
    // 下游影响：「AgentStreamOperationRegistry.AgentStreamOperationRegistry()」向下依次触达 「register」。
    // 系统意义：「AgentStreamOperationRegistry.AgentStreamOperationRegistry()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentStreamOperationRegistry() {
        Map<String, OperationDefinition> values = new LinkedHashMap<>();
        register(
                values,
                "INTAKE_TURN",
                "/internal/agents/intake/turn/stream",
                "INTAKE_OFFICER",
                "intake_turn",
                Set.of(
                        "room_utterance",
                        "case_detail.case_story.title",
                        "case_detail.case_story.one_sentence_summary",
                        "case_detail.references.order_reference",
                        "case_detail.references.after_sales_reference",
                        "case_detail.references.logistics_reference",
                        "case_detail.party_positions.user_claim",
                        "case_detail.party_positions.merchant_claim",
                        "case_detail.party_positions.initiator_position",
                        "case_detail.party_positions.platform_observation",
                        "case_detail.claim_resolution.normalized_statement",
                        "case_detail.claim_resolution.request_reason",
                        "case_detail.claim_resolution.requested_items",
                        "case_detail.respondent_attitude.position",
                        "case_detail.dispute_core_state.core_conflict",
                        "case_detail.dispute_focus.core_issue",
                        "case_detail.intake_quality.improvement_reason",
                        "case_detail.case_story",
                        "case_detail.references",
                        "case_detail.party_positions",
                        "case_detail.claim_resolution",
                        "case_detail.respondent_attitude",
                        "case_detail.dispute_core_state",
                        "case_detail.dispute_focus",
                        "case_detail.risk_assessment",
                        "case_detail.missing_information",
                        "case_detail.intake_quality"));
        register(
                values,
                "INTAKE_FINAL",
                "/internal/agents/intake/analyze/stream",
                "INTAKE_OFFICER",
                "intake_analyze",
                Set.of());
        register(
                values,
                "INTAKE_ANALYSIS",
                "/internal/agents/legacy/intake/analyze/stream",
                "INTAKE_OFFICER",
                "intake_analysis",
                Set.of());
        register(
                values,
                "EVIDENCE_TURN",
                "/internal/agents/evidence/turn/stream",
                "EVIDENCE_CLERK",
                "evidence_turn",
                Set.of("room_utterance"));
        register(
                values,
                "EVIDENCE_BUILD",
                "/internal/agents/evidence/build/stream",
                "EVIDENCE_CLERK",
                "evidence_build",
                Set.of());
        register(
                values,
                "HEARING_ROUND",
                "/internal/agents/hearing/round-turn/stream",
                "PRESIDING_JUDGE",
                "hearing_round_turn",
                Set.of(
                        "message_text",
                        "jury_review_report.public_message"));
        register(
                values,
                "HEARING_STAGE",
                "/internal/agents/hearing/run-stage/stream",
                "PRESIDING_JUDGE",
                "hearing_stage",
                Set.of(
                        "output.neutral_summary",
                        "output.draft.recommended_outcome",
                        "output.draft.reasoning_summary"));
        register(
                values,
                "HEARING_ANALYSIS",
                "/internal/agents/legacy/hearing/analyze/stream",
                "PRESIDING_JUDGE",
                "hearing_analysis",
                Set.of(
                        "issue_framing.neutral_summary",
                        "adjudication_draft.draft.recommended_outcome",
                        "adjudication_draft.draft.reasoning_summary"));
        register(
                values,
                "DELIBERATION",
                "/internal/agents/deliberation/run/stream",
                "JURY_PANEL",
                "deliberation",
                Set.of());
        register(
                values,
                "REVIEW",
                "/internal/agents/review-copilot/query/stream",
                "REVIEW_COPILOT",
                "review_copilot",
                Set.of("answer"));
        register(
                values,
                "EVALUATION",
                "/internal/agents/evaluation/analyze/stream",
                "EVALUATION_AGENT",
                "evaluation",
                Set.of());
        register(
                values,
                "EXTERNAL_IMPORT",
                "/internal/agents/external-import/simulate/stream",
                "SIMULATION_AGENT",
                "external_import",
                Set.of("items.0.title", "items.0.description"));
        this.definitions = Map.copyOf(values);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamOperationRegistry.require(String)」。
    // 具体功能：「AgentStreamOperationRegistry.require(String)」：按服务端 operation 名称取得固定 endpoint、Agent 角色、Python 协议名和公开字段集合；未知操作直接拒绝，最终返回「OperationDefinition」。
    // 上游调用：「AgentStreamOperationRegistry.require(String)」的上游调用点包括 「AgentRunCoordinator.start」、「AgentRunLifecycleService.claim」、「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunLifecycleService.basePayload」。
    // 下游影响：「AgentStreamOperationRegistry.require(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「OperationDefinition」交给调用方。
    // 系统意义：「AgentStreamOperationRegistry.require(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public OperationDefinition require(String operation) {
        OperationDefinition definition = definitions.get(operation);
        if (definition == null) {
            throw new IllegalArgumentException("unsupported agent stream operation: " + operation);
        }
        return definition;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentStreamOperationRegistry.register(Map,String,String,String,String,Set)」。
    // 具体功能：「AgentStreamOperationRegistry.register(Map,String,String,String,String,Set)」：把单个流式操作定义防御性复制后写入启动期临时 Map，防止调用方在运行中修改公开字段白名单，最终返回「void」。
    // 上游调用：「AgentStreamOperationRegistry.register(Map,String,String,String,String,Set)」的上游调用点包括 「AgentStreamOperationRegistry.AgentStreamOperationRegistry」。
    // 下游影响：「AgentStreamOperationRegistry.register(Map,String,String,String,String,Set)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentStreamOperationRegistry.register(Map,String,String,String,String,Set)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void register(
            Map<String, OperationDefinition> target,
            String operation,
            String endpoint,
            String agentRole,
            String protocolOperation,
            Set<String> visibleFieldPaths) {
        target.put(
                operation,
                new OperationDefinition(
                        operation,
                        endpoint,
                        agentRole,
                        protocolOperation,
                        Set.copyOf(visibleFieldPaths)));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】类型「OperationDefinition」。
    // 类型职责：定义Operation定义跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    public record OperationDefinition(
            String operation,
            String endpoint,
            String agentRole,
            String protocolOperation,
            Set<String> visibleFieldPaths) {}
}
