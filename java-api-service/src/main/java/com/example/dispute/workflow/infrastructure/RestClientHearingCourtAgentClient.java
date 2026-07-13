/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：把Rest庭审法庭Agent请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「generateRoundTurn」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.infrastructure;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.hearing.application.HearingCourtAgentClient;
import com.example.dispute.hearing.application.HearingCourtAgentCommand;
import com.example.dispute.hearing.application.HearingCourtAgentResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【Temporal 持久化编排 / 外部集成层】类型「RestClientHearingCourtAgentClient」。
// 类型职责：把Rest庭审法庭Agent请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「RestClientHearingCourtAgentClient」、「generateRoundTurn」、「textArray」、「requestBody」、「partySubmissionBody」、「put」。
// 协作关系：主要由 「RestClientHearingCourtAgentClientTest.postsRoundTurnToPythonAgentAndValidatesJudgeResult」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientHearingCourtAgentClient implements HearingCourtAgentClient {

    private static final String ENDPOINT = "/internal/agents/hearing/round-turn";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingCourtAgentClient.RestClientHearingCourtAgentClient(RestClient)」。
    // 具体功能：「RestClientHearingCourtAgentClient.RestClientHearingCourtAgentClient(RestClient)」：通过构造器接收 「restClient」(RestClient) 并保存为「RestClientHearingCourtAgentClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientHearingCourtAgentClient.RestClientHearingCourtAgentClient(RestClient)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「RestClientHearingCourtAgentClientTest.postsRoundTurnToPythonAgentAndValidatesJudgeResult」 显式创建。
    // 下游影响：「RestClientHearingCourtAgentClient.RestClientHearingCourtAgentClient(RestClient)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientHearingCourtAgentClient.RestClientHearingCourtAgentClient(RestClient)」负责主链路中的“Rest客户端庭审法庭Agent客户端”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientHearingCourtAgentClient(
            @Qualifier("agentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」。
    // 具体功能：「RestClientHearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」：生成轮次轮次：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「restClient.post」、「nextRound.isMissingNode」、「nextRound.isNull」、「nextRound.asInt」；不满足前置条件时抛出 「AgentExecutionException」；处理的关键状态/协议值包括 「X-Role」、「SYSTEM」、「speaker_role」、「message_text」，最终返回「HearingCourtAgentResult」。
    // 上游调用：「RestClientHearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」由使用「RestClientHearingCourtAgentClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientHearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」向下依次触达 「restClient.post」、「nextRound.isMissingNode」、「nextRound.isNull」、「nextRound.asInt」；计算结果以「HearingCourtAgentResult」交给调用方。
    // 系统意义：「RestClientHearingCourtAgentClient.generateRoundTurn(HearingCourtAgentCommand,String,String)」负责主链路中的“轮次轮次”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    @Override
    public HearingCourtAgentResult generateRoundTurn(
            HearingCourtAgentCommand command, String traceId, String requestId) {
        JsonNode response =
                restClient
                        .post()
                        .uri(ENDPOINT)
                        .header(TraceIdFilter.TRACE_HEADER, traceId)
                        .header(TraceIdFilter.REQUEST_HEADER, requestId)
                        .header("X-Role", "SYSTEM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody(command))
                        .retrieve()
                        .body(JsonNode.class);
        if (response == null
                || response.path("speaker_role").asText().isBlank()
                || response.path("message_text").asText().isBlank()
                || response.path("court_event_type").asText().isBlank()
                || response.path("round_no").asInt(0) <= 0
                || response.path("prompt_version").asText().isBlank()
                || response.path("model").asText().isBlank()) {
            throw new AgentExecutionException(
                    ErrorCode.AGENT_OUTPUT_SCHEMA_INVALID,
                    "hearing court round agent returned an invalid schema",
                    Map.of("endpoint", ENDPOINT));
        }
        JsonNode nextRound = response.path("next_round_no");
        return new HearingCourtAgentResult(
                response.path("speaker_role").asText(),
                response.path("message_text").asText(),
                response.path("round_summary").asText(""),
                textArray(response.path("questions_for_user")),
                textArray(response.path("questions_for_merchant")),
                response.path("court_event_type").asText(),
                response.path("round_no").asInt(),
                nextRound.isMissingNode() || nextRound.isNull() ? null : nextRound.asInt(),
                response.path("final_draft_required").asBoolean(false),
                textArray(response.path("review_focus_signal")),
                response.path("prompt_version").asText(),
                response.path("model").asText());
    }

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingCourtAgentClient.textArray(JsonNode)」。
    // 具体功能：「RestClientHearingCourtAgentClient.textArray(JsonNode)」：构建文本数组；实际协作者为 「node.isArray」、「item.asText」，最终返回「List<String>」。
    // 上游调用：「RestClientHearingCourtAgentClient.textArray(JsonNode)」的上游调用点包括 「RestClientHearingCourtAgentClient.generateRoundTurn」。
    // 下游影响：「RestClientHearingCourtAgentClient.textArray(JsonNode)」向下依次触达 「node.isArray」、「item.asText」；计算结果以「List<String>」交给调用方。
    // 系统意义：「RestClientHearingCourtAgentClient.textArray(JsonNode)」负责主链路中的“文本数组”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(
                item -> {
                    String value = item.asText("");
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                });
        return List.copyOf(values);
    }

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingCourtAgentClient.requestBody(HearingCourtAgentCommand)」。
    // 具体功能：「RestClientHearingCourtAgentClient.requestBody(HearingCourtAgentCommand)」：构建请求请求体；实际协作者为 「command.caseId」、「command.workflowId」、「command.orderId」、「command.afterSaleId」；处理的关键状态/协议值包括 「case_id」、「workflow_id」、「order_id」、「after_sale_id」，最终返回「Map<String, Object>」。
    // 上游调用：「RestClientHearingCourtAgentClient.requestBody(HearingCourtAgentCommand)」的上游调用点包括 「RestClientHearingCourtAgentClient.generateRoundTurn」。
    // 下游影响：「RestClientHearingCourtAgentClient.requestBody(HearingCourtAgentCommand)」向下依次触达 「command.caseId」、「command.workflowId」、「command.orderId」、「command.afterSaleId」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「RestClientHearingCourtAgentClient.requestBody(HearingCourtAgentCommand)」负责主链路中的“请求请求体”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static Map<String, Object> requestBody(HearingCourtAgentCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "case_id", command.caseId());
        put(body, "workflow_id", command.workflowId());
        put(body, "order_id", command.orderId());
        put(body, "after_sale_id", command.afterSaleId());
        put(body, "logistics_id", command.logisticsId());
        put(body, "dispute_type", command.disputeType());
        put(body, "title", command.title());
        put(body, "case_description", command.caseDescription());
        put(body, "risk_level", command.riskLevel());
        body.put("round_no", command.roundNo());
        body.put("dossier_version", command.dossierVersion());
        body.put("final_round", command.finalRound());
        put(body, "round_status", command.roundStatus());
        put(body, "stop_reason", command.stopReason());
        put(body, "round_summary_json", command.roundSummaryJson());
        body.put("courtroom_context", courtroomContext(command.courtroomContextJson()));
        body.put(
                "party_submissions",
                command.partySubmissions().stream()
                        .map(RestClientHearingCourtAgentClient::partySubmissionBody)
                        .toList());
        return body;
    }

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingCourtAgentClient.partySubmissionBody(PartySubmission)」。
    // 具体功能：「RestClientHearingCourtAgentClient.partySubmissionBody(PartySubmission)」：构建当事方提交请求体；实际协作者为 「submission.participantRole」、「submission.participantId」、「submission.submissionSource」、「submission.submissionJson」；处理的关键状态/协议值包括 「participant_role」、「participant_id」、「submission_source」、「submission_json」，最终返回「Map<String, Object>」。
    // 上游调用：「RestClientHearingCourtAgentClient.partySubmissionBody(PartySubmission)」只由「RestClientHearingCourtAgentClient」内部流程使用，负责封装“当事方提交请求体”这一步校验、映射或状态转换。
    // 下游影响：「RestClientHearingCourtAgentClient.partySubmissionBody(PartySubmission)」向下依次触达 「submission.participantRole」、「submission.participantId」、「submission.submissionSource」、「submission.submissionJson」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「RestClientHearingCourtAgentClient.partySubmissionBody(PartySubmission)」负责主链路中的“当事方提交请求体”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static Map<String, Object> partySubmissionBody(
            HearingCourtAgentCommand.PartySubmission submission) {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "participant_role", submission.participantRole());
        put(body, "participant_id", submission.participantId());
        put(body, "submission_source", submission.submissionSource());
        put(body, "submission_json", submission.submissionJson());
        return body;
    }

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingCourtAgentClient.put(Map,String,Object)」。
    // 具体功能：「RestClientHearingCourtAgentClient.put(Map,String,Object)」：写入Rest庭审法庭Agent，最终返回「void」。
    // 上游调用：「RestClientHearingCourtAgentClient.put(Map,String,Object)」的上游调用点包括 「RestClientHearingCourtAgentClient.requestBody」、「RestClientHearingCourtAgentClient.partySubmissionBody」。
    // 下游影响：「RestClientHearingCourtAgentClient.put(Map,String,Object)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientHearingCourtAgentClient.put(Map,String,Object)」负责主链路中的“Rest庭审法庭Agent”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static void put(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    // 所属模块：【Temporal 持久化编排 / 外部集成层】「RestClientHearingCourtAgentClient.courtroomContext(String)」。
    // 具体功能：「RestClientHearingCourtAgentClient.courtroomContext(String)」：构建法庭上下文上下文；实际协作者为 「OBJECT_MAPPER.readTree」、「OBJECT_MAPPER.createObjectNode」；处理的关键状态/协议值包括 「{}」，最终返回「JsonNode」。
    // 上游调用：「RestClientHearingCourtAgentClient.courtroomContext(String)」的上游调用点包括 「RestClientHearingCourtAgentClient.requestBody」。
    // 下游影响：「RestClientHearingCourtAgentClient.courtroomContext(String)」向下依次触达 「OBJECT_MAPPER.readTree」、「OBJECT_MAPPER.createObjectNode」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「RestClientHearingCourtAgentClient.courtroomContext(String)」负责主链路中的“法庭上下文上下文”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static JsonNode courtroomContext(String courtroomContextJson) {
        try {
            return OBJECT_MAPPER.readTree(
                    courtroomContextJson == null || courtroomContextJson.isBlank()
                            ? "{}"
                            : courtroomContextJson);
        } catch (JsonProcessingException exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }
}
