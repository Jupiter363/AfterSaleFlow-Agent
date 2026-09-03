/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：把RestOCR任务请求适配为受超时和协议校验约束的外部 HTTP 调用。
 * 业务链路：核心入口/契约为 「createParseTask」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.infrastructure;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.evidence.application.OcrTaskClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 所属模块：【证据与版本化卷宗 / 外部集成层】类型「RestClientOcrTaskClient」。
// 类型职责：把RestOCR任务请求适配为受超时和协议校验约束的外部 HTTP 调用；本类型显式提供 「RestClientOcrTaskClient」、「createParseTask」、「taskBody」、「setCorrelationHeader」、「normalizeBaseUrl」。
// 协作关系：主要由 「RestClientOcrTaskClientTest.sendsStructuredParseTaskWithServiceCredential」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RestClientOcrTaskClient implements OcrTaskClient {

    private final RestClient restClient;
    private final String callbackBaseUrl;

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「RestClientOcrTaskClient.RestClientOcrTaskClient(RestClient,String)」。
    // 具体功能：「RestClientOcrTaskClient.RestClientOcrTaskClient(RestClient,String)」：通过构造器接收 「restClient」(RestClient)、「callbackBaseUrl」(String) 并保存为「RestClientOcrTaskClient」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RestClientOcrTaskClient.RestClientOcrTaskClient(RestClient,String)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「RestClientOcrTaskClientTest.sendsStructuredParseTaskWithServiceCredential」 显式创建。
    // 下游影响：「RestClientOcrTaskClient.RestClientOcrTaskClient(RestClient,String)」向下依次触达 「normalizeBaseUrl」。
    // 系统意义：「RestClientOcrTaskClient.RestClientOcrTaskClient(RestClient,String)」负责主链路中的“Rest客户端OCR任务客户端”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RestClientOcrTaskClient(
            @Qualifier("ocrRestClient") RestClient restClient,
            @Value("${app.ocr.callback-base-url:${JAVA_API_SERVICE_URL:http://java-api-service:8080}}")
                    String callbackBaseUrl) {
        this.restClient = restClient;
        this.callbackBaseUrl = normalizeBaseUrl(callbackBaseUrl);
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「RestClientOcrTaskClient.createParseTask(ParseTask)」。
    // 具体功能：「RestClientOcrTaskClient.createParseTask(ParseTask)」：创建解析任务：先调用受配置和超时控制的内部 HTTP 服务；实际协作者为 「restClient.post」、「setCorrelationHeader」、「taskBody」、「toBodilessEntity」，最终返回「void」。
    // 上游调用：「RestClientOcrTaskClient.createParseTask(ParseTask)」由使用「RestClientOcrTaskClient」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「RestClientOcrTaskClient.createParseTask(ParseTask)」向下依次触达 「restClient.post」、「setCorrelationHeader」、「taskBody」、「toBodilessEntity」。
    // 系统意义：「RestClientOcrTaskClient.createParseTask(ParseTask)」负责主链路中的“解析任务”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    @Override
    public void createParseTask(ParseTask task) {
        restClient
                .post()
                .uri("/internal/evidence/parse-tasks")
                .headers(
                        headers -> {
                            setCorrelationHeader(
                                    headers,
                                    TraceIdFilter.TRACE_HEADER,
                                    TraceIdFilter.MDC_TRACE_KEY);
                            setCorrelationHeader(
                                    headers,
                                    TraceIdFilter.REQUEST_HEADER,
                                    TraceIdFilter.MDC_REQUEST_KEY);
                        })
                .contentType(MediaType.APPLICATION_JSON)
                .body(taskBody(task))
                .retrieve()
                .toBodilessEntity();
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「RestClientOcrTaskClient.taskBody(ParseTask)」。
    // 具体功能：「RestClientOcrTaskClient.taskBody(ParseTask)」：构建任务请求体；实际协作者为 「task.evidenceId」、「task.caseId」、「task.bucket」、「task.objectKey」；处理的关键状态/协议值包括 「evidence_id」、「case_id」、「bucket」、「object_key」，最终返回「Map<String, Object>」。
    // 上游调用：「RestClientOcrTaskClient.taskBody(ParseTask)」的上游调用点包括 「RestClientOcrTaskClient.createParseTask」。
    // 下游影响：「RestClientOcrTaskClient.taskBody(ParseTask)」向下依次触达 「task.evidenceId」、「task.caseId」、「task.bucket」、「task.objectKey」；计算结果以「Map<String, Object>」交给调用方。
    // 系统意义：「RestClientOcrTaskClient.taskBody(ParseTask)」负责主链路中的“任务请求体”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private Map<String, Object> taskBody(ParseTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evidence_id", task.evidenceId());
        body.put("case_id", task.caseId());
        body.put("bucket", task.bucket());
        body.put("object_key", task.objectKey());
        body.put("content_type", task.contentType());
        if (!callbackBaseUrl.isBlank()) {
            body.put(
                    "callback_url",
                    callbackBaseUrl
                            + "/internal/evidence/"
                            + task.evidenceId()
                            + "/parse-result");
        }
        return body;
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「RestClientOcrTaskClient.setCorrelationHeader(HttpHeaders,String,String)」。
    // 具体功能：「RestClientOcrTaskClient.setCorrelationHeader(HttpHeaders,String,String)」：把 「headers」(HttpHeaders)、「headerName」(String)、「mdcKey」(String) 写入「RestClientOcrTaskClient」的「correlationHeader」字段，供当前事务提交时同步到持久化记录。
    // 上游调用：「RestClientOcrTaskClient.setCorrelationHeader(HttpHeaders,String,String)」的上游调用点包括 「RestClientOcrTaskClient.createParseTask」。
    // 下游影响：「RestClientOcrTaskClient.setCorrelationHeader(HttpHeaders,String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RestClientOcrTaskClient.setCorrelationHeader(HttpHeaders,String,String)」负责主链路中的“setCorrelationHeader”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static void setCorrelationHeader(
            org.springframework.http.HttpHeaders headers,
            String headerName,
            String mdcKey) {
        String value = MDC.get(mdcKey);
        if (value != null && !value.isBlank()) {
            headers.set(headerName, value);
        }
    }

    // 所属模块：【证据与版本化卷宗 / 外部集成层】「RestClientOcrTaskClient.normalizeBaseUrl(String)」。
    // 具体功能：「RestClientOcrTaskClient.normalizeBaseUrl(String)」：规范化BaseUrl，最终返回「String」。
    // 上游调用：「RestClientOcrTaskClient.normalizeBaseUrl(String)」的上游调用点包括 「RestClientOcrTaskClient.RestClientOcrTaskClient」。
    // 下游影响：「RestClientOcrTaskClient.normalizeBaseUrl(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RestClientOcrTaskClient.normalizeBaseUrl(String)」负责主链路中的“BaseUrl”；原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
