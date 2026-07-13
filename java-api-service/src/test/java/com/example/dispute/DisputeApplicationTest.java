/*
 * 所属模块：后端公共边界。
 * 文件职责：验证争议应用，覆盖 「actuatorHealthIsPublicAndCorrelated」、「openApiDocumentIsPublicAndIdentifiesTheService」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.trace.TraceIdFilter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// 所属模块：【后端公共边界 / 自动化测试层】类型「DisputeApplicationTest」。
// 类型职责：集中验证争议应用的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「actuatorHealthIsPublicAndCorrelated」、「openApiDocumentIsPublicAndIdentifiesTheService」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispute;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.flyway.enabled=false",
            "spring.data.redis.repositories.enabled=false",
            "management.health.redis.enabled=false",
            "management.health.elasticsearch.enabled=false"
        })
@SuppressWarnings({"rawtypes", "unchecked"})
class DisputeApplicationTest {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    // 所属模块：【后端公共边界 / 自动化测试层】「DisputeApplicationTest.actuatorHealthIsPublicAndCorrelated()」。
    // 具体功能：「DisputeApplicationTest.actuatorHealthIsPublicAndCorrelated()」：复现“核对完整业务行为（场景方法「actuatorHealthIsPublicAndCorrelated」）”场景：驱动 「restTemplate.getForEntity」、「response.getStatusCode」、「response.getBody」、「response.getHeaders」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「status」、「UP」、「TRACE_[0-9a-f]{32}」、「REQ_[0-9a-f]{32}」。
    // 上游调用：「DisputeApplicationTest.actuatorHealthIsPublicAndCorrelated()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeApplicationTest.actuatorHealthIsPublicAndCorrelated()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeApplicationTest.actuatorHealthIsPublicAndCorrelated()」守住「后端公共边界」的可执行规格，尤其防止 「status」、「UP」、「TRACE_[0-9a-f]{32}」、「REQ_[0-9a-f]{32}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void actuatorHealthIsPublicAndCorrelated() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/actuator/health", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.TRACE_HEADER))
                .matches("TRACE_[0-9a-f]{32}");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.REQUEST_HEADER))
                .matches("REQ_[0-9a-f]{32}");
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「DisputeApplicationTest.openApiDocumentIsPublicAndIdentifiesTheService()」。
    // 具体功能：「DisputeApplicationTest.openApiDocumentIsPublicAndIdentifiesTheService()」：复现“核对完整业务行为（场景方法「openApiDocumentIsPublicAndIdentifiesTheService」）”场景：驱动 「restTemplate.getForEntity」、「response.getStatusCode」、「response.getBody」、「assertThat(response.getStatusCode()).isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「info」、「title」、「version」、「v1」。
    // 上游调用：「DisputeApplicationTest.openApiDocumentIsPublicAndIdentifiesTheService()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeApplicationTest.openApiDocumentIsPublicAndIdentifiesTheService()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeApplicationTest.openApiDocumentIsPublicAndIdentifiesTheService()」守住「后端公共边界」的可执行规格，尤其防止 「info」、「title」、「version」、「v1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void openApiDocumentIsPublicAndIdentifiesTheService() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/v3/api-docs", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> info = (Map<?, ?>) response.getBody().get("info");
        assertThat(info.get("title")).isEqualTo("Order Fulfillment Dispute API");
        assertThat(info.get("version")).isEqualTo("v1");
    }
}
