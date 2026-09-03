/*
 * 所属模块：结案与离线评估。
 * 文件职责：把Rest评估Agent请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「analyze」；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.evaluation.application.EvaluationAgentClient;
import com.example.dispute.evaluation.application.EvaluationAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【结案与离线评估 / 外部集成层】类型「RestClientEvaluationAgentClient」。
// 类型职责：把Rest评估Agent请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「RestClientEvaluationAgentClient」、「analyze」。
// 协作关系：主要由 「RestClientEvaluationAgentClientTest.callsOfflineEvaluationEndpointAndValidatesReadOnlyReport」 使用。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientEvaluationAgentClient
        implements EvaluationAgentClient {

    private static final String ENDPOINT =
            "/internal/agents/evaluation/analyze";

    private final RestClient restClient;

    // 所属模块：【结案与离线评估 / 外部集成层】「RestClientEvaluationAgentClient.RestClientEvaluationAgentClient(RestClient)」。
    // 具体功能：「RestClientEvaluationAgentClient.RestClientEvaluationAgentClient(RestClient)」：通过构造器接收 「restClient」(RestClient) 并保存为「RestClientEvaluationAgentClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientEvaluationAgentClient.RestClientEvaluationAgentClient(RestClient)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「RestClientEvaluationAgentClientTest.callsOfflineEvaluationEndpointAndValidatesReadOnlyReport」 显式创建。
    // 下游影响：「RestClientEvaluationAgentClient.RestClientEvaluationAgentClient(RestClient)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientEvaluationAgentClient.RestClientEvaluationAgentClient(RestClient)」负责主链路中的“Rest客户端评估Agent客户端”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientEvaluationAgentClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 所属模块：【结案与离线评估 / 外部集成层】「RestClientEvaluationAgentClient.analyze(JsonNode,String,String)」。
    // 具体功能：「RestClientEvaluationAgentClient.analyze(JsonNode,String,String)」：分析评估Agent：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「restClient.post」、「retrieve」、「contentType」、「restClient.post().uri」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「X-Role」、「SYSTEM」、「COMPLETED」、「evaluation_status」，最终返回「EvaluationAgentResult」。
    // 上游调用：「RestClientEvaluationAgentClient.analyze(JsonNode,String,String)」由使用「RestClientEvaluationAgentClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientEvaluationAgentClient.analyze(JsonNode,String,String)」向下依次触达 「restClient.post」、「retrieve」、「contentType」、「restClient.post().uri」；计算结果以「EvaluationAgentResult」交给调用方。
    // 系统意义：「RestClientEvaluationAgentClient.analyze(JsonNode,String,String)」负责主链路中的“评估Agent”；评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
    @Override
    public EvaluationAgentResult analyze(
            JsonNode request, String traceId, String requestId) {
        JsonNode response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null
                || !"COMPLETED"
                        .equals(
                                response.path("evaluation_status").asText())
                || response.path("case_id").asText().isBlank()
                || !response.path("metric_scores").isObject()
                || !response.path("findings").isArray()
                || response.path("evaluator_model").asText().isBlank()
                || response.path("prompt_version").asText().isBlank()
                || response.path("automatic_changes_applied").asBoolean(true)
                || response.path("online_case_mutated").asBoolean(true)) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "evaluation agent returned an invalid or unsafe schema",
                    Map.of("endpoint", ENDPOINT));
        }
        return new EvaluationAgentResult(
                response,
                response.path("evaluator_model").asText(),
                response.path("prompt_version").asText(),
                response.path("latency_ms").asLong(),
                response.path("token_usage").asInt());
    }
}
