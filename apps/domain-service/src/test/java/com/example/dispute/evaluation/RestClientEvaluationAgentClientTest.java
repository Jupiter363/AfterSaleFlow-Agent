/*
 * 所属模块：结案与离线评估。
 * 文件职责：验证Rest评估Agent，覆盖 「callsOfflineEvaluationEndpointAndValidatesReadOnlyReport」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；关闭已执行案件并调用评估 Agent 生成质量指标和离线报告。
 * 关键边界：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
 */
package com.example.dispute.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.evaluation.infrastructure.RestClientEvaluationAgentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// 所属模块：【结案与离线评估 / 自动化测试层】类型「RestClientEvaluationAgentClientTest」。
// 类型职责：集中验证Rest评估Agent的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「callsOfflineEvaluationEndpointAndValidatesReadOnlyReport」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：评估只能读取已关闭案件，不能反向修改在线裁决、规则或 Prompt
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class RestClientEvaluationAgentClientTest {

    // 所属模块：【结案与离线评估 / 自动化测试层】「RestClientEvaluationAgentClientTest.callsOfflineEvaluationEndpointAndValidatesReadOnlyReport()」。
    // 具体功能：「RestClientEvaluationAgentClientTest.callsOfflineEvaluationEndpointAndValidatesReadOnlyReport()」：复现“核对完整业务行为（场景方法「callsOfflineEvaluationEndpointAndValidatesReadOnlyReport」）”场景：驱动 「client.analyze」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「X-Service-Secret」、「secret」、「X-Trace-Id」、「TRACE_evaluation」。
    // 上游调用：「RestClientEvaluationAgentClientTest.callsOfflineEvaluationEndpointAndValidatesReadOnlyReport()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RestClientEvaluationAgentClientTest.callsOfflineEvaluationEndpointAndValidatesReadOnlyReport()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RestClientEvaluationAgentClientTest.callsOfflineEvaluationEndpointAndValidatesReadOnlyReport()」守住「结案与离线评估」的可执行规格，尤其防止 「X-Service-Secret」、「secret」、「X-Trace-Id」、「TRACE_evaluation」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void callsOfflineEvaluationEndpointAndValidatesReadOnlyReport()
            throws Exception {
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("http://agent.test")
                        .defaultHeader("X-Service-Secret", "secret");
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                "http://agent.test/internal/agents/evaluation/analyze"))
                .andExpect(header("X-Service-Secret", "secret"))
                .andExpect(header("X-Trace-Id", "TRACE_evaluation"))
                .andExpect(header("X-Request-Id", "REQ_evaluation"))
                .andExpect(header("X-Role", "SYSTEM"))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "case_id":"CASE_test",
                                  "evaluation_status":"COMPLETED",
                                  "metric_scores":{
                                    "draft_approval_rate":1.0,
                                    "reviewer_modification_rate":0.0
                                  },
                                  "findings":[],
                                  "rule_gap_suggestions":[],
                                  "improvement_suggestions":[],
                                  "automatic_changes_applied":false,
                                  "online_case_mutated":false,
                                  "evaluator_model":"evaluation-model",
                                  "prompt_version":"evaluation-v1",
                                  "latency_ms":11,
                                  "token_usage":21
                                }
                                """,
                                MediaType.APPLICATION_JSON));
        var client =
                new RestClientEvaluationAgentClient(builder.build());

        var result =
                client.analyze(
                        new ObjectMapper()
                                .readTree(
                                        "{\"case_id\":\"CASE_test\",\"case_status\":\"CLOSED\"}"),
                        "TRACE_evaluation",
                        "REQ_evaluation");

        assertThat(result.evaluatorModel()).isEqualTo("evaluation-model");
        assertThat(result.promptVersion()).isEqualTo("evaluation-v1");
        assertThat(result.tokenUsage()).isEqualTo(21);
        assertThat(result.report().path("online_case_mutated").asBoolean())
                .isFalse();
        server.verify();
    }
}
