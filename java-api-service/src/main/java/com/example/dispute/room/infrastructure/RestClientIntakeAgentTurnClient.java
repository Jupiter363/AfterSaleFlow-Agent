/*
 * 所属模块：房间协作与权限。
 * 文件职责：把Rest接待Agent轮次请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「run」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.room.application.IntakeAgentTurnClient;
import com.example.dispute.room.application.IntakeAgentTurnCommand;
import com.example.dispute.room.application.IntakeAgentTurnResult;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【房间协作与权限 / 外部集成层】类型「RestClientIntakeAgentTurnClient」。
// 类型职责：把Rest接待Agent轮次请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「RestClientIntakeAgentTurnClient」、「run」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientIntakeAgentTurnClient implements IntakeAgentTurnClient {

    private static final String ENDPOINT = "/internal/agents/intake/turn";

    private final RestClient restClient;

    // 所属模块：【房间协作与权限 / 外部集成层】「RestClientIntakeAgentTurnClient.RestClientIntakeAgentTurnClient(RestClient)」。
    // 具体功能：「RestClientIntakeAgentTurnClient.RestClientIntakeAgentTurnClient(RestClient)」：通过构造器接收 「restClient」(RestClient) 并保存为「RestClientIntakeAgentTurnClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientIntakeAgentTurnClient.RestClientIntakeAgentTurnClient(RestClient)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「RestClientIntakeAgentTurnClient.RestClientIntakeAgentTurnClient(RestClient)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientIntakeAgentTurnClient.RestClientIntakeAgentTurnClient(RestClient)」负责主链路中的“Rest客户端接待Agent轮次客户端”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientIntakeAgentTurnClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 所属模块：【房间协作与权限 / 外部集成层】「RestClientIntakeAgentTurnClient.run(IntakeAgentTurnCommand,String,String)」。
    // 具体功能：「RestClientIntakeAgentTurnClient.run(IntakeAgentTurnCommand,String,String)」：运行接待Agent轮次：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「restClient.post」、「response.roomUtterance」、「response.scrollSnapshot」、「response.canvasOperations」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「X-Role」、「SYSTEM」、「endpoint」，最终返回「IntakeAgentTurnResult」。
    // 上游调用：「RestClientIntakeAgentTurnClient.run(IntakeAgentTurnCommand,String,String)」由使用「RestClientIntakeAgentTurnClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientIntakeAgentTurnClient.run(IntakeAgentTurnCommand,String,String)」向下依次触达 「restClient.post」、「response.roomUtterance」、「response.scrollSnapshot」、「response.canvasOperations」；计算结果以「IntakeAgentTurnResult」交给调用方。
    // 系统意义：「RestClientIntakeAgentTurnClient.run(IntakeAgentTurnCommand,String,String)」负责主链路中的“接待Agent轮次”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    @Override
    public IntakeAgentTurnResult run(
            IntakeAgentTurnCommand command, String traceId, String requestId) {
        IntakeAgentTurnResult response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(command)
                        .retrieve()
                        .body(IntakeAgentTurnResult.class);
        if (response == null || response.roomUtterance() == null
                || response.roomUtterance().isBlank()
                || response.scrollSnapshot() == null
                || response.canvasOperations() == null) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "intake turn agent returned an invalid schema",
                    Map.of("endpoint", ENDPOINT));
        }
        return response;
    }
}
