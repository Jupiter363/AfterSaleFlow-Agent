/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：把RestAgent请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「analyze」；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.infrastructure;

import com.example.dispute.caseintake.application.AgentServiceClient;
import com.example.dispute.caseintake.application.CreateCaseCommand;
import com.example.dispute.caseintake.application.IntakeAnalysis;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.domain.model.RiskLevel;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【案件受理兼容链路 / 外部集成层】类型「RestClientAgentServiceClient」。
// 类型职责：把RestAgent请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「RestClientAgentServiceClient」、「analyze」、「blank」。
// 协作关系：主要由 「RestClientAgentServiceClientTest.sendsServiceCredentialAndMapsStructuredIntakeResponse」 使用。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientAgentServiceClient implements AgentServiceClient {

    private final RestClient restClient;

    // 所属模块：【案件受理兼容链路 / 外部集成层】「RestClientAgentServiceClient.RestClientAgentServiceClient(RestClient)」。
    // 具体功能：「RestClientAgentServiceClient.RestClientAgentServiceClient(RestClient)」：通过构造器接收 「restClient」(RestClient) 并保存为「RestClientAgentServiceClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientAgentServiceClient.RestClientAgentServiceClient(RestClient)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「RestClientAgentServiceClientTest.sendsServiceCredentialAndMapsStructuredIntakeResponse」 显式创建。
    // 下游影响：「RestClientAgentServiceClient.RestClientAgentServiceClient(RestClient)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientAgentServiceClient.RestClientAgentServiceClient(RestClient)」负责主链路中的“Rest客户端Agent服务客户端”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientAgentServiceClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 所属模块：【案件受理兼容链路 / 外部集成层】「RestClientAgentServiceClient.analyze(CreateCaseCommand,String,String)」。
    // 具体功能：「RestClientAgentServiceClient.analyze(CreateCaseCommand,String,String)」：分析接待Analysis：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「restClient.post」、「IntakeRequest.from」、「response.caseType」、「response.riskLevel」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「endpoint」，最终返回「IntakeAnalysis」。
    // 上游调用：「RestClientAgentServiceClient.analyze(CreateCaseCommand,String,String)」由使用「RestClientAgentServiceClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientAgentServiceClient.analyze(CreateCaseCommand,String,String)」向下依次触达 「restClient.post」、「IntakeRequest.from」、「response.caseType」、「response.riskLevel」；计算结果以「IntakeAnalysis」交给调用方。
    // 系统意义：「RestClientAgentServiceClient.analyze(CreateCaseCommand,String,String)」负责主链路中的“接待Analysis”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    @Override
    public IntakeAnalysis analyze(
            CreateCaseCommand command, String traceId, String requestId) {
        IntakeResponse response =
                restClient
                        .post()
                        .uri("/internal/agents/legacy/intake/analyze")
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(IntakeRequest.from(command))
                        .retrieve()
                        .body(IntakeResponse.class);
        if (response == null
                || blank(response.caseType())
                || response.riskLevel() == null
                || blank(response.title())
                || blank(response.normalizedDescription())) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "intake agent returned an invalid schema",
                    Map.of("endpoint", "/internal/agents/legacy/intake/analyze"));
        }
        return new IntakeAnalysis(
                response.caseType(),
                response.disputeType(),
                response.riskLevel(),
                response.potentialDispute(),
                response.missingSlots(),
                response.title(),
                response.normalizedDescription());
    }

    // 所属模块：【案件受理兼容链路 / 外部集成层】「RestClientAgentServiceClient.blank(String)」。
    // 具体功能：「RestClientAgentServiceClient.blank(String)」：判断空白值，最终返回「boolean」。
    // 上游调用：「RestClientAgentServiceClient.blank(String)」的上游调用点包括 「RestClientAgentServiceClient.analyze」。
    // 下游影响：「RestClientAgentServiceClient.blank(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「RestClientAgentServiceClient.blank(String)」负责主链路中的“”；接待分析只是非最终建议，不能越权决定赔付或执行动作
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // 所属模块：【案件受理兼容链路 / 外部集成层】类型「IntakeRequest」。
    // 类型职责：定义接待跨层传递时使用的不可变数据契约；本类型显式提供 「from」。
    // 协作关系：主要由 「RestClientAgentServiceClient.analyze」 使用。
    // 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record IntakeRequest(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("after_sale_id") String afterSaleId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("merchant_id") String merchantId,
            String description,
            @JsonProperty("attachment_ids") List<String> attachmentIds,
            String channel) {

        // 所属模块：【案件受理兼容链路 / 外部集成层】「RestClientAgentServiceClient.IntakeRequest.from(CreateCaseCommand)」。
        // 具体功能：「RestClientAgentServiceClient.IntakeRequest.from(CreateCaseCommand)」：转换接待；实际协作者为 「command.orderId」、「command.afterSaleId」、「command.userId」、「command.merchantId」，最终返回「IntakeRequest」。
        // 上游调用：「RestClientAgentServiceClient.IntakeRequest.from(CreateCaseCommand)」的上游调用点包括 「RestClientAgentServiceClient.analyze」。
        // 下游影响：「RestClientAgentServiceClient.IntakeRequest.from(CreateCaseCommand)」向下依次触达 「command.orderId」、「command.afterSaleId」、「command.userId」、「command.merchantId」；计算结果以「IntakeRequest」交给调用方。
        // 系统意义：「RestClientAgentServiceClient.IntakeRequest.from(CreateCaseCommand)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
        // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
        private static IntakeRequest from(CreateCaseCommand command) {
            return new IntakeRequest(
                    command.orderId(),
                    command.afterSaleId(),
                    command.userId(),
                    command.merchantId(),
                    command.description(),
                    command.attachmentIds(),
                    command.channel());
        }
    }

    // 所属模块：【案件受理兼容链路 / 外部集成层】类型「IntakeResponse」。
    // 类型职责：定义接待响应跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record IntakeResponse(
            @JsonProperty("case_type") String caseType,
            @JsonProperty("dispute_type") String disputeType,
            @JsonProperty("risk_level") RiskLevel riskLevel,
            @JsonProperty("potential_dispute") boolean potentialDispute,
            @JsonProperty("missing_slots") List<String> missingSlots,
            String title,
            @JsonProperty("normalized_description") String normalizedDescription) {}
}
