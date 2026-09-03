/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：验证RestOCR任务，覆盖 「sendsStructuredParseTaskWithServiceCredential」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.evidence.application.OcrTaskClient;
import com.example.dispute.evidence.infrastructure.RestClientOcrTaskClient;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// 所属模块：【证据与版本化卷宗 / 自动化测试层】类型「RestClientOcrTaskClientTest」。
// 类型职责：集中验证RestOCR任务的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「sendsStructuredParseTaskWithServiceCredential」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class RestClientOcrTaskClientTest {

    // 所属模块：【证据与版本化卷宗 / 自动化测试层】「RestClientOcrTaskClientTest.sendsStructuredParseTaskWithServiceCredential()」。
    // 具体功能：「RestClientOcrTaskClientTest.sendsStructuredParseTaskWithServiceCredential()」：复现“核对完整业务行为（场景方法「sendsStructuredParseTaskWithServiceCredential」）”场景：驱动 「client.createParseTask」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「X-Service-Secret」、「ocr-secret」、「X-Trace-Id」、「TRACE_ocr_test」。
    // 上游调用：「RestClientOcrTaskClientTest.sendsStructuredParseTaskWithServiceCredential()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RestClientOcrTaskClientTest.sendsStructuredParseTaskWithServiceCredential()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RestClientOcrTaskClientTest.sendsStructuredParseTaskWithServiceCredential()」守住「证据与版本化卷宗」的可执行规格，尤其防止 「X-Service-Secret」、「ocr-secret」、「X-Trace-Id」、「TRACE_ocr_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void sendsStructuredParseTaskWithServiceCredential() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientOcrTaskClient client =
                new RestClientOcrTaskClient(
                        builder.baseUrl("http://ocr:8010")
                                .defaultHeader("X-Service-Secret", "ocr-secret")
                                .build(),
                        "http://host.docker.internal:8081");
        server.expect(requestTo("http://ocr:8010/internal/evidence/parse-tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Secret", "ocr-secret"))
                .andExpect(header("X-Trace-Id", "TRACE_ocr_test"))
                .andExpect(header("X-Request-Id", "REQ_ocr_test"))
                .andExpect(jsonPath("$.evidence_id").value("EVIDENCE_ocr"))
                .andExpect(jsonPath("$.object_key").value("case/evidence/proof.pdf"))
                .andExpect(
                        jsonPath("$.callback_url")
                                .value(
                                        "http://host.docker.internal:8081/internal/evidence/"
                                                + "EVIDENCE_ocr/parse-result"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        try {
            MDC.put("trace_id", "TRACE_ocr_test");
            MDC.put("request_id", "REQ_ocr_test");
            client.createParseTask(
                    new OcrTaskClient.ParseTask(
                            "EVIDENCE_ocr",
                            "CASE_ocr",
                            "evidence-original",
                            "case/evidence/proof.pdf",
                            "application/pdf"));
        } finally {
            MDC.clear();
        }

        server.verify();
    }
}
