/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证Rest外部争议模拟，覆盖 「sendsAndReadsPythonAgentContractInSnakeCase」、「rejectsPythonAgentResponsesThatContainMoreThanOneItem」、「rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.casecore.application.SimulateExternalImportCommand;
import com.example.dispute.common.exception.AgentExecutionException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.domain.model.RiskLevel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// 所属模块：【案件核心与导入 / 外部集成层】类型「RestClientExternalDisputeSimulationClientTest」。
// 类型职责：集中验证Rest外部争议模拟的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「sendsAndReadsPythonAgentContractInSnakeCase」、「rejectsPythonAgentResponsesThatContainMoreThanOneItem」、「rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract」、「http1RequestFactory」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class RestClientExternalDisputeSimulationClientTest {

    // 所属模块：【案件核心与导入 / 外部集成层】「RestClientExternalDisputeSimulationClientTest.sendsAndReadsPythonAgentContractInSnakeCase()」。
    // 具体功能：「RestClientExternalDisputeSimulationClientTest.sendsAndReadsPythonAgentContractInSnakeCase()」：复现“核对完整业务行为（场景方法「sendsAndReadsPythonAgentContractInSnakeCase」）”场景：驱动 「HttpServer.create」、「Duration.ofSeconds」、「server.createContext」、「exchange.getRequestBody」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「127.0.0.1」、「Content-Type」、「X-Service-Secret」、「secret」。
    // 上游调用：「RestClientExternalDisputeSimulationClientTest.sendsAndReadsPythonAgentContractInSnakeCase()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RestClientExternalDisputeSimulationClientTest.sendsAndReadsPythonAgentContractInSnakeCase()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RestClientExternalDisputeSimulationClientTest.sendsAndReadsPythonAgentContractInSnakeCase()」守住「案件核心与导入」的可执行规格，尤其防止 「127.0.0.1」、「Content-Type」、「X-Service-Secret」、「secret」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void sendsAndReadsPythonAgentContractInSnakeCase() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/agents/external-import/simulate",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] body =
                            """
                            {
                              "items": [
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-001",
                                  "order_reference": "ORDER-001",
                                  "after_sales_reference": "AFTER-001",
                                  "logistics_reference": "LOG-001",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-local",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "External import test case",
                                  "description": "Python Agent generated an external dispute case.",
                                  "risk_level": "MEDIUM"
                                }
                              ]
                            }
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .defaultHeader("X-Service-Secret", "secret")
                            .requestFactory(http1RequestFactory(Duration.ofSeconds(2)))
                            .build();

            var result =
                    new RestClientExternalDisputeSimulationClient(client)
                            .simulate(
                                    new SimulateExternalImportCommand(
                                            1,
                                            "watch-after-sale-dispute",
                                            RiskLevel.MEDIUM,
                                            ActorRole.USER,
                                            "user-local",
                                            "merchant-local",
                                            "external-import-batch-001"),
                                    "TRACE_test",
                                    "REQ_test");

            assertThat(requestBody.get()).contains("\"initiator_role_hint\":\"USER\"");
            assertThat(requestBody.get()).contains("\"count\":1");
            assertThat(requestBody.get()).contains("\"current_actor_id\":\"user-local\"");
            assertThat(requestBody.get())
                    .contains("\"simulation_batch_id\":\"external-import-batch-001\"");
            assertThat(requestBody.get()).doesNotContain("initiatorRoleHint");
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().externalCaseReference()).isEqualTo("SIM-001");
            assertThat(result.getFirst().orderReference()).isEqualTo("ORDER-001");
        } finally {
            server.stop(0);
        }
    }

    // 所属模块：【案件核心与导入 / 外部集成层】「RestClientExternalDisputeSimulationClientTest.rejectsPythonAgentResponsesThatContainMoreThanOneItem()」。
    // 具体功能：「RestClientExternalDisputeSimulationClientTest.rejectsPythonAgentResponsesThatContainMoreThanOneItem()」：复现“拒绝非法输入或越权操作（场景方法「rejectsPythonAgentResponsesThatContainMoreThanOneItem」）”场景：驱动 「simulationClient.simulate」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「127.0.0.1」、「Content-Type」、「user-local」、「merchant-local」。
    // 上游调用：「RestClientExternalDisputeSimulationClientTest.rejectsPythonAgentResponsesThatContainMoreThanOneItem()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RestClientExternalDisputeSimulationClientTest.rejectsPythonAgentResponsesThatContainMoreThanOneItem()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RestClientExternalDisputeSimulationClientTest.rejectsPythonAgentResponsesThatContainMoreThanOneItem()」守住「案件核心与导入」的可执行规格，尤其防止 「127.0.0.1」、「Content-Type」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsPythonAgentResponsesThatContainMoreThanOneItem() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/agents/external-import/simulate",
                exchange -> {
                    byte[] body =
                            """
                            {
                              "items": [
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-001",
                                  "order_reference": "ORDER-001",
                                  "after_sales_reference": "AFTER-001",
                                  "logistics_reference": "LOG-001",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-local",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "First external import",
                                  "description": "First generated external dispute.",
                                  "risk_level": "MEDIUM"
                                },
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-002",
                                  "order_reference": "ORDER-002",
                                  "after_sales_reference": "AFTER-002",
                                  "logistics_reference": "LOG-002",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-local",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "Second external import",
                                  "description": "Second generated external dispute.",
                                  "risk_level": "MEDIUM"
                                }
                              ]
                            }
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .requestFactory(http1RequestFactory(Duration.ofSeconds(2)))
                            .build();
            RestClientExternalDisputeSimulationClient simulationClient =
                    new RestClientExternalDisputeSimulationClient(client);

            assertThatThrownBy(
                            () ->
                                    simulationClient.simulate(
                                            new SimulateExternalImportCommand(
                                                    1,
                                                    "watch dispute",
                                                    RiskLevel.MEDIUM,
                                                    ActorRole.USER,
                                                    "user-local",
                                                    "merchant-local",
                                                    "external-import-batch-002"),
                                            "TRACE_test",
                                            "REQ_test"))
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("exactly one");
        } finally {
            server.stop(0);
        }
    }

    // 所属模块：【案件核心与导入 / 外部集成层】「RestClientExternalDisputeSimulationClientTest.rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract()」。
    // 具体功能：「RestClientExternalDisputeSimulationClientTest.rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract()」：复现“拒绝非法输入或越权操作（场景方法「rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract」）”场景：驱动 「simulationClient.simulate」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「127.0.0.1」、「Content-Type」、「user-local」、「merchant-local」。
    // 上游调用：「RestClientExternalDisputeSimulationClientTest.rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RestClientExternalDisputeSimulationClientTest.rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RestClientExternalDisputeSimulationClientTest.rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract()」守住「案件核心与导入」的可执行规格，尤其防止 「127.0.0.1」、「Content-Type」、「user-local」、「merchant-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract()
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/agents/external-import/simulate",
                exchange -> {
                    byte[] body =
                            """
                            {
                              "items": [
                                {
                                  "source_system": "LLM_SIMULATED_OMS",
                                  "external_case_reference": "SIM-INVALID-PARTY",
                                  "order_reference": "ORDER-INVALID-PARTY",
                                  "after_sales_reference": "AFTER-INVALID-PARTY",
                                  "logistics_reference": "LOG-INVALID-PARTY",
                                  "user_id": "user-local",
                                  "merchant_id": "merchant-1",
                                  "initiator_role": "USER",
                                  "dispute_type": "FULFILLMENT_CONFLICT",
                                  "title": "Invalid external import",
                                  "description": "The simulator returned an unsupported merchant.",
                                  "risk_level": "MEDIUM"
                                }
                              ]
                            }
                            """
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .requestFactory(http1RequestFactory(Duration.ofSeconds(2)))
                            .build();
            RestClientExternalDisputeSimulationClient simulationClient =
                    new RestClientExternalDisputeSimulationClient(client);

            assertThatThrownBy(
                            () ->
                                    simulationClient.simulate(
                                            new SimulateExternalImportCommand(
                                                    1,
                                                    "watch dispute",
                                                    RiskLevel.MEDIUM,
                                                    ActorRole.USER,
                                                    "user-local",
                                                    "merchant-local",
                                                    "external-import-batch-invalid-party"),
                                            "TRACE_test",
                                            "REQ_test"))
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("invalid item");
        } finally {
            server.stop(0);
        }
    }

    // 所属模块：【案件核心与导入 / 外部集成层】「RestClientExternalDisputeSimulationClientTest.http1RequestFactory(Duration)」。
    // 具体功能：「RestClientExternalDisputeSimulationClientTest.http1RequestFactory(Duration)」：作为测试辅助方法为“核对完整业务行为（场景方法「http1RequestFactory」）”组装或读取「JdkClientHttpRequestFactory」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「RestClientExternalDisputeSimulationClientTest.http1RequestFactory(Duration)」由本测试类中的 「RestClientExternalDisputeSimulationClientTest.sendsAndReadsPythonAgentContractInSnakeCase」、「RestClientExternalDisputeSimulationClientTest.rejectsPythonAgentResponsesThatContainMoreThanOneItem」、「RestClientExternalDisputeSimulationClientTest.rejectsAOneItemResponseWhosePartyIdentityViolatesTheSimulationContract」 调用。
    // 下游影响：「RestClientExternalDisputeSimulationClientTest.http1RequestFactory(Duration)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RestClientExternalDisputeSimulationClientTest.http1RequestFactory(Duration)」守住「案件核心与导入」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private static JdkClientHttpRequestFactory http1RequestFactory(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build());
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
