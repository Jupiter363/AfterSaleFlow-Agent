/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：把Rest庭审Agent请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「analyze」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.workflow.application.HearingAgentClient;
import com.example.dispute.workflow.application.HearingAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【Temporal 持久化编排 / 外部集成层】类型「RestClientHearingAgentClient」。
// 类型职责：把Rest庭审Agent请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「RestClientHearingAgentClient」、「analyze」。
// 协作关系：主要由 「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest」、「RestClientHearingAgentClientTest.sendsCorrelationAndServiceHeadersAndValidatesStructuredResult」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientHearingAgentClient implements HearingAgentClient {

    private final RestClient restClient;

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingAgentClient.RestClientHearingAgentClient(RestClient)」。
    // 具体功能：「RestClientHearingAgentClient.RestClientHearingAgentClient(RestClient)」：通过构造器接收 「restClient」(RestClient) 并保存为「RestClientHearingAgentClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientHearingAgentClient.RestClientHearingAgentClient(RestClient)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest」、「RestClientHearingAgentClientTest.sendsCorrelationAndServiceHeadersAndValidatesStructuredResult」 显式创建。
    // 下游影响：「RestClientHearingAgentClient.RestClientHearingAgentClient(RestClient)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientHearingAgentClient.RestClientHearingAgentClient(RestClient)」负责主链路中的“Rest客户端庭审Agent客户端”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientHearingAgentClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingAgentClient.analyze(JsonNode,String,String)」。
    // 具体功能：「RestClientHearingAgentClient.analyze(JsonNode,String,String)」：分析庭审Agent：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「restClient.post」、「node.asText」、「retrieve」、「contentType」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「X-Role」、「SYSTEM」、「workflow_status」、「executed_nodes」，最终返回「HearingAgentResult」。
    // 上游调用：「RestClientHearingAgentClient.analyze(JsonNode,String,String)」由使用「RestClientHearingAgentClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientHearingAgentClient.analyze(JsonNode,String,String)」向下依次触达 「restClient.post」、「node.asText」、「retrieve」、「contentType」；计算结果以「HearingAgentResult」交给调用方。
    // 系统意义：「RestClientHearingAgentClient.analyze(JsonNode,String,String)」负责主链路中的“庭审Agent”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Override
    public HearingAgentResult analyze(
            JsonNode request, String traceId, String requestId) {
        JsonNode response =
                restClient
                        .post()
                        .uri("/internal/agents/legacy/hearing/analyze")
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null
                || response.path("workflow_status").isMissingNode()
                || !response.path("executed_nodes").isArray()
                || response.path("adjudication_draft").path("draft").isMissingNode()
                || response.path("prompt_version").asText().isBlank()
                || response.path("model").asText().isBlank()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "hearing agent returned an invalid schema",
                    Map.of(
                            "endpoint",
                            "/internal/agents/legacy/hearing/analyze"));
        }
        List<String> nodes = new ArrayList<>();
        response.path("executed_nodes").forEach(node -> nodes.add(node.asText()));
        boolean requiresEvidence =
                response.path("evidence_gap")
                        .path("requires_supplemental_evidence")
                        .asBoolean(false);
        boolean manual =
                "MANUAL_REVIEW_REQUIRED"
                                .equals(response.path("workflow_status").asText())
                        || !response.path("manual_review_reasons").isEmpty();
        return new HearingAgentResult(
                response,
                requiresEvidence,
                manual,
                List.copyOf(nodes),
                response.path("prompt_version").asText(),
                response.path("model").asText());
    }
}
