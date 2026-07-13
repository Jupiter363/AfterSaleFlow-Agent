/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证Rest庭审Agent，覆盖 「sendsCorrelationAndServiceHeadersAndValidatesStructuredResult」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.workflow.infrastructure.RestClientHearingAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「RestClientHearingAgentClientTest」。
// 类型职责：集中验证Rest庭审Agent的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「sendsCorrelationAndServiceHeadersAndValidatesStructuredResult」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class RestClientHearingAgentClientTest {

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「RestClientHearingAgentClientTest.sendsCorrelationAndServiceHeadersAndValidatesStructuredResult()」。
    // 具体功能：「RestClientHearingAgentClientTest.sendsCorrelationAndServiceHeadersAndValidatesStructuredResult()」：复现“核对完整业务行为（场景方法「sendsCorrelationAndServiceHeadersAndValidatesStructuredResult」）”场景：驱动 「client.analyze」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「X-Service-Secret」、「secret」、「X-Trace-Id」、「TRACE_test」。
    // 上游调用：「RestClientHearingAgentClientTest.sendsCorrelationAndServiceHeadersAndValidatesStructuredResult()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RestClientHearingAgentClientTest.sendsCorrelationAndServiceHeadersAndValidatesStructuredResult()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RestClientHearingAgentClientTest.sendsCorrelationAndServiceHeadersAndValidatesStructuredResult()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「X-Service-Secret」、「secret」、「X-Trace-Id」、「TRACE_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void sendsCorrelationAndServiceHeadersAndValidatesStructuredResult()
            throws Exception {
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("http://agent.test")
                        .defaultHeader("X-Service-Secret", "secret");
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "http://agent.test/internal/agents/legacy/hearing/analyze"))
                .andExpect(header("X-Service-Secret", "secret"))
                .andExpect(header("X-Trace-Id", "TRACE_test"))
                .andExpect(header("X-Request-Id", "REQ_test"))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "case_id":"CASE_test",
                                  "workflow_id":"WORKFLOW_test",
                                  "workflow_status":"COMPLETED",
                                  "executed_nodes":["issue_framing_node","adjudication_draft_node"],
                                  "evidence_gap":{"requires_supplemental_evidence":false,"gaps":[]},
                                  "adjudication_draft":{"draft":{"confidence":0.8}},
                                  "manual_review_reasons":[],
                                  "prompt_version":"hearing-v1",
                                  "model":"test-model"
                                }
                                """,
                                MediaType.APPLICATION_JSON));
        var client = new RestClientHearingAgentClient(builder.build());

        var result =
                client.analyze(
                        new ObjectMapper().readTree("{\"case_id\":\"CASE_test\"}"),
                        "TRACE_test",
                        "REQ_test");

        assertThat(result.requiresAdditionalEvidence()).isFalse();
        assertThat(result.manualRequired()).isFalse();
        assertThat(result.executedNodes())
                .containsExactly(
                        "issue_framing_node", "adjudication_draft_node");
        server.verify();
    }
}
