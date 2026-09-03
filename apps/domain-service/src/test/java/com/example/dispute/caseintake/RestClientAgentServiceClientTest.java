/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：验证RestAgent，覆盖 「sendsServiceCredentialAndMapsStructuredIntakeResponse」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dispute.caseintake.application.CreateCaseCommand;
import com.example.dispute.caseintake.application.IntakeAnalysis;
import com.example.dispute.caseintake.infrastructure.RestClientAgentServiceClient;
import com.example.dispute.domain.model.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// 所属模块：【案件受理兼容链路 / 自动化测试层】类型「RestClientAgentServiceClientTest」。
// 类型职责：集中验证RestAgent的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「sendsServiceCredentialAndMapsStructuredIntakeResponse」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class RestClientAgentServiceClientTest {

    // 所属模块：【案件受理兼容链路 / 自动化测试层】「RestClientAgentServiceClientTest.sendsServiceCredentialAndMapsStructuredIntakeResponse()」。
    // 具体功能：「RestClientAgentServiceClientTest.sendsServiceCredentialAndMapsStructuredIntakeResponse()」：复现“核对完整业务行为（场景方法「sendsServiceCredentialAndMapsStructuredIntakeResponse」）”场景：驱动 「client.analyze」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「X-Service-Secret」、「agent-secret」、「X-Trace-Id」、「TRACE_agent_test」。
    // 上游调用：「RestClientAgentServiceClientTest.sendsServiceCredentialAndMapsStructuredIntakeResponse()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RestClientAgentServiceClientTest.sendsServiceCredentialAndMapsStructuredIntakeResponse()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RestClientAgentServiceClientTest.sendsServiceCredentialAndMapsStructuredIntakeResponse()」守住「案件受理兼容链路」的可执行规格，尤其防止 「X-Service-Secret」、「agent-secret」、「X-Trace-Id」、「TRACE_agent_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void sendsServiceCredentialAndMapsStructuredIntakeResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientAgentServiceClient client =
                new RestClientAgentServiceClient(
                        builder.baseUrl("http://agent:8000")
                                .defaultHeader("X-Service-Secret", "agent-secret")
                                .build());
        server.expect(
                        requestTo(
                                "http://agent:8000/internal/agents/legacy/intake/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Secret", "agent-secret"))
                .andExpect(header("X-Trace-Id", "TRACE_agent_test"))
                .andExpect(header("X-Request-Id", "REQ_agent_test"))
                .andExpect(jsonPath("$.order_id").value("order-1"))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "case_type": "DISPUTE",
                                  "dispute_type": "NON_RECEIPT",
                                  "risk_level": "HIGH",
                                  "potential_dispute": true,
                                  "missing_slots": [],
                                  "title": "签收争议",
                                  "normalized_description": "物流签收但用户未收到"
                                }
                                """,
                                MediaType.APPLICATION_JSON));

        IntakeAnalysis result =
                client.analyze(
                        new CreateCaseCommand(
                                "order-1",
                                null,
                                null,
                                "user-1",
                                "merchant-1",
                                "物流签收但未收到",
                                List.of(),
                                "WEB"),
                        "TRACE_agent_test",
                        "REQ_agent_test");

        assertThat(result.caseType()).isEqualTo("DISPUTE");
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.potentialDispute()).isTrue();
        server.verify();
    }

}
