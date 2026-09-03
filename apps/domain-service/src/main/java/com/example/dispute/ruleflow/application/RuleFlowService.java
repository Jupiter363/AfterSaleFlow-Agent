/*
 * 所属模块：规则明确路径。
 * 文件职责：编排规则Flow规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「conclude」；处理可由明确政策条款覆盖的争议并输出带规则引用的阶段结论。
 * 关键边界：规则命中不等于自动批准，后续仍经过补救规划和人工终审
 */
package com.example.dispute.ruleflow.application;

import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;

// 所属模块：【规则明确路径 / 应用编排层】类型「RuleFlowService」。
// 类型职责：编排规则Flow规则、权限校验与事实读写；本类型显式提供 「RuleFlowService」、「conclude」、「readTree」。
// 协作关系：主要由 「RouterApplicationService.createConclusion」、「RouterApplicationServiceTest.setUp」 使用。
// 边界意义：规则命中不等于自动批准，后续仍经过补救规划和人工终审
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class RuleFlowService {

    private final ObjectMapper objectMapper;

    // 所属模块：【规则明确路径 / 应用编排层】「RuleFlowService.RuleFlowService(ObjectMapper)」。
    // 具体功能：「RuleFlowService.RuleFlowService(ObjectMapper)」：通过构造器接收 「objectMapper」(ObjectMapper) 并保存为「RuleFlowService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RuleFlowService.RuleFlowService(ObjectMapper)」的上游创建点包括 「RouterApplicationServiceTest.setUp」。
    // 下游影响：「RuleFlowService.RuleFlowService(ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RuleFlowService.RuleFlowService(ObjectMapper)」负责主链路中的“规则Flow服务”；规则命中不等于自动批准，后续仍经过补救规划和人工终审
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RuleFlowService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 所属模块：【规则明确路径 / 应用编排层】「RuleFlowService.conclude(PolicyRuleEntity)」。
    // 具体功能：「RuleFlowService.conclude(PolicyRuleEntity)」：形成阶段结论规则FlowConclusion；实际协作者为 「policy.getOutcomeJson」、「policy.getRuleCode」、「policy.getRuleVersion」、「objectMapper.convertValue」；不满足前置条件时抛出 「IllegalArgumentException」、「IllegalStateException」；处理的关键状态/协议值包括 「conclusion_code」、「recommended_actions」，最终返回「RuleFlowConclusion」。
    // 上游调用：「RuleFlowService.conclude(PolicyRuleEntity)」的上游调用点包括 「RouterApplicationService.createConclusion」。
    // 下游影响：「RuleFlowService.conclude(PolicyRuleEntity)」向下依次触达 「policy.getOutcomeJson」、「policy.getRuleCode」、「policy.getRuleVersion」、「objectMapper.convertValue」；计算结果以「RuleFlowConclusion」交给调用方。
    // 系统意义：「RuleFlowService.conclude(PolicyRuleEntity)」负责主链路中的“规则FlowConclusion”；规则命中不等于自动批准，后续仍经过补救规划和人工终审
    public RuleFlowConclusion conclude(PolicyRuleEntity policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        JsonNode outcome = readTree(policy.getOutcomeJson());
        String code = outcome.path("conclusion_code").asText();
        if (code.isBlank()) {
            throw new IllegalStateException("policy outcome has no conclusion_code");
        }
        List<String> actions =
                objectMapper.convertValue(
                        outcome.path("recommended_actions"),
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, String.class));
        return new RuleFlowConclusion(
                code,
                "Policy "
                        + policy.getRuleCode()
                        + " version "
                        + policy.getRuleVersion()
                        + " matched.",
                List.copyOf(actions));
    }

    // 所属模块：【规则明确路径 / 应用编排层】「RuleFlowService.readTree(String)」。
    // 具体功能：「RuleFlowService.readTree(String)」：读取Tree：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「RuleFlowService.readTree(String)」的上游调用点包括 「RuleFlowService.conclude」。
    // 下游影响：「RuleFlowService.readTree(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「RuleFlowService.readTree(String)」统一“Tree”的跨层表示，避免不同入口产生不兼容字段；规则命中不等于自动批准，后续仍经过补救规划和人工终审
    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid policy outcome JSON", exception);
        }
    }
}
