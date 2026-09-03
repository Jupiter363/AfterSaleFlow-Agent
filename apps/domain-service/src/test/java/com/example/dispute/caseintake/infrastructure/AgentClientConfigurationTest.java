/*
 * 所属模块：案件受理兼容链路。
 * 文件职责：验证Agent，覆盖 「forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；承接旧版创建案件接口并调用接待 Agent 形成初步分析。
 * 关键边界：接待分析只是非最终建议，不能越权决定赔付或执行动作
 */
package com.example.dispute.caseintake.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

// 所属模块：【案件受理兼容链路 / 外部集成层】类型「AgentClientConfigurationTest」。
// 类型职责：集中验证Agent的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：接待分析只是非最终建议，不能越权决定赔付或执行动作
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class AgentClientConfigurationTest {

    // 所属模块：【案件受理兼容链路 / 外部集成层】「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest()」。
    // 具体功能：「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest()」：复现“核对完整业务行为（场景方法「forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest」）”场景：驱动 「HttpServer.create」、「AgentClientConfiguration.http1RequestFactory」、「Duration.ofSeconds」、「server.createContext」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「127.0.0.1」、「Upgrade」、「Content-Type」、「X-Service-Secret」。
    // 上游调用：「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentClientConfigurationTest.forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest()」守住「案件受理兼容链路」的可执行规格，尤其防止 「127.0.0.1」、「Upgrade」、「Content-Type」、「X-Service-Secret」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void forcesHttp11SoUvicornDoesNotReceiveAnH2cUpgradeRequest()
            throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/test",
                exchange -> {
                    upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
                    byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            RestClient client =
                    RestClient.builder()
                            .baseUrl(
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort())
                            .requestFactory(
                                    AgentClientConfiguration.http1RequestFactory(
                                            Duration.ofSeconds(2)))
                            .defaultHeader("X-Service-Secret", "secret")
                            .build();

            String result = client.get().uri("/test").retrieve().body(String.class);

            assertThat(result).isEqualTo("ok");
            assertThat(upgradeHeader.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
