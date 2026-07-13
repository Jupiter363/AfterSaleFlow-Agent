/*
 * 所属模块：房间协作与权限。
 * 文件职责：把Rest证据Agent轮次请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「run」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.room.application.EvidenceAgentTurnClient;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceAgentTurnResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【房间协作与权限 / 外部集成层】类型「RestClientEvidenceAgentTurnClient」。
// 类型职责：把Rest证据Agent轮次请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「RestClientEvidenceAgentTurnClient」、「run」、「validationErrors」、「limit」。
// 协作关系：主要由 「RestClientEvidenceAgentTurnClientTest.mapsHttp422ToAnExplicitAgentContractFailure」、「RestClientEvidenceAgentTurnClientTest.preservesVerificationSuggestionsForTextOnlyConversationResponses」、「RestClientEvidenceAgentTurnClientTest.sendsOnlyTheVersionedEnvelopeAndInvocationContext」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientEvidenceAgentTurnClient implements EvidenceAgentTurnClient {

    private static final String ENDPOINT = "/internal/agents/evidence/turn";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // 所属模块：【房间协作与权限 / 外部集成层】「RestClientEvidenceAgentTurnClient.RestClientEvidenceAgentTurnClient(RestClient,ObjectMapper)」。
    // 具体功能：「RestClientEvidenceAgentTurnClient.RestClientEvidenceAgentTurnClient(RestClient,ObjectMapper)」：通过构造器接收 「restClient」(RestClient)、「objectMapper」(ObjectMapper) 并保存为「RestClientEvidenceAgentTurnClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientEvidenceAgentTurnClient.RestClientEvidenceAgentTurnClient(RestClient,ObjectMapper)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「RestClientEvidenceAgentTurnClientTest.sendsOnlyTheVersionedEnvelopeAndInvocationContext」、「RestClientEvidenceAgentTurnClientTest.preservesVerificationSuggestionsForTextOnlyConversationResponses」 显式创建。
    // 下游影响：「RestClientEvidenceAgentTurnClient.RestClientEvidenceAgentTurnClient(RestClient,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientEvidenceAgentTurnClient.RestClientEvidenceAgentTurnClient(RestClient,ObjectMapper)」负责主链路中的“Rest客户端证据Agent轮次客户端”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientEvidenceAgentTurnClient(
            @Qualifier("agentRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【房间协作与权限 / 外部集成层】「RestClientEvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」。
    // 具体功能：「RestClientEvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」：运行证据Agent轮次：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「restClient.post」、「status.value」、「httpResponse.getStatusCode」、「response.roomUtterance」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「X-Role」、「SYSTEM」、「endpoint」、「http_status」，最终返回「EvidenceAgentTurnResult」。
    // 上游调用：「RestClientEvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」由使用「RestClientEvidenceAgentTurnClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientEvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」向下依次触达 「restClient.post」、「status.value」、「httpResponse.getStatusCode」、「response.roomUtterance」；计算结果以「EvidenceAgentTurnResult」交给调用方。
    // 系统意义：「RestClientEvidenceAgentTurnClient.run(EvidenceAgentTurnCommand,String,String)」负责主链路中的“证据Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @Override
    public EvidenceAgentTurnResult run(
            EvidenceAgentTurnCommand command, String traceId, String requestId) {
        EvidenceAgentTurnResult response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(command)
                        .retrieve()
                        .onStatus(
                                status -> status.value() == 422,
                                (request, httpResponse) -> {
                                    throw new AgentExecutionException(
                                            ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                                            "evidence context envelope was rejected by the agent contract",
                                            Map.of(
                                                    "endpoint",
                                                    ENDPOINT,
                                                    "http_status",
                                                    httpResponse.getStatusCode().value(),
                                                    "validation_errors",
                                                    validationErrors(httpResponse)));
                                })
                        .body(EvidenceAgentTurnResult.class);
        if (response == null
                || response.roomUtterance() == null
                || response.roomUtterance().isBlank()
                || response.memoryPatch() == null
                || response.canvasOperations() == null
                || response.liabilityDetermined()
                || response.remedyRecommended()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "evidence turn agent returned an invalid or unsafe schema",
                    Map.of("endpoint", ENDPOINT));
        }
        return response;
    }

    // 所属模块：【房间协作与权限 / 外部集成层】「RestClientEvidenceAgentTurnClient.validationErrors(ClientHttpResponse)」。
    // 具体功能：「RestClientEvidenceAgentTurnClient.validationErrors(ClientHttpResponse)」：解析validationErrors：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」、「response.getBody」、「detail.isArray」、「rawLocation.isArray」；处理的关键状态/协议值包括 「details」、「errors」、「detail」、「loc」，最终返回「List<Map<String, Object>>」。
    // 上游调用：「RestClientEvidenceAgentTurnClient.validationErrors(ClientHttpResponse)」的上游调用点包括 「RestClientEvidenceAgentTurnClient.run」。
    // 下游影响：「RestClientEvidenceAgentTurnClient.validationErrors(ClientHttpResponse)」向下依次触达 「objectMapper.readTree」、「response.getBody」、「detail.isArray」、「rawLocation.isArray」；计算结果以「List<Map<String, Object>>」交给调用方。
    // 系统意义：「RestClientEvidenceAgentTurnClient.validationErrors(ClientHttpResponse)」负责主链路中的“validationErrors”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private List<Map<String, Object>> validationErrors(
            org.springframework.http.client.ClientHttpResponse response) {
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode detail = root.path("details").path("errors");
            if (!detail.isArray()) {
                detail = root.path("detail");
            }
            if (!detail.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> errors = new ArrayList<>();
            for (JsonNode item : detail) {
                if (errors.size() >= 20) {
                    break;
                }
                List<String> location = new ArrayList<>();
                JsonNode rawLocation = item.path("loc");
                if (rawLocation.isArray()) {
                    rawLocation.forEach(
                            segment -> location.add(limit(segment.asText(), 128)));
                }
                errors.add(
                        Map.of(
                                "location", List.copyOf(location),
                                "type", limit(item.path("type").asText("unknown"), 128),
                                "message",
                                        limit(
                                                item.path("msg")
                                                        .asText("contract validation failed"),
                                                512)));
            }
            return List.copyOf(errors);
        } catch (IOException | RuntimeException ignored) {
            return List.of(
                    Map.of(
                            "location", List.of(),
                            "type", "unparseable_validation_response",
                            "message", "agent contract validation failed"));
        }
    }

    // 所属模块：【房间协作与权限 / 外部集成层】「RestClientEvidenceAgentTurnClient.limit(String,int)」。
    // 具体功能：「RestClientEvidenceAgentTurnClient.limit(String,int)」：限制长度字符串，最终返回「String」。
    // 上游调用：「RestClientEvidenceAgentTurnClient.limit(String,int)」的上游调用点包括 「RestClientEvidenceAgentTurnClient.validationErrors」。
    // 下游影响：「RestClientEvidenceAgentTurnClient.limit(String,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RestClientEvidenceAgentTurnClient.limit(String,int)」负责主链路中的“字符串”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
