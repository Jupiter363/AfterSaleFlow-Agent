/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：验证App，覆盖 「bindsCentralizedIntegrationAndSafetyConfiguration」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「AppPropertiesTest」。
// 类型职责：集中验证App的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「bindsCentralizedIntegrationAndSafetyConfiguration」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class AppPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesConfiguration.class)
                    .withPropertyValues(
                            "app.env=test",
                            "app.security.service-secret=java-secret",
                            "app.agent.base-url=http://agent:8000",
                            "app.agent.service-secret=agent-secret",
                            "app.agent.timeout-ms=120000",
                            "app.ocr.base-url=http://ocr:8010",
                            "app.ocr.service-secret=ocr-secret",
                            "app.ocr.timeout-ms=120000",
                            "app.temporal.address=temporal:7233",
                            "app.temporal.namespace=default",
                            "app.temporal.task-queue=case-dispute-task-queue",
                            "app.minio.endpoint=http://minio:9000",
                            "app.minio.access-key=minio-user",
                            "app.minio.secret-key=minio-password",
                            "app.elasticsearch.url=http://elasticsearch:9200",
                            "app.feature.agent-intake-enabled=true",
                            "app.feature.agent-hearing-enabled=true",
                            "app.feature.agent-evaluation-enabled=true",
                            "app.feature.ocr-enabled=true",
                            "app.feature.human-review-required=true",
                            "app.feature.tool-executor-simulation=true",
                            "app.feature.auto-close-enabled=true",
                            "app.logging.audit-enabled=true",
                            "app.logging.sensitive-masking-enabled=true");

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「AppPropertiesTest.bindsCentralizedIntegrationAndSafetyConfiguration()」。
    // 具体功能：「AppPropertiesTest.bindsCentralizedIntegrationAndSafetyConfiguration()」：复现“核对完整业务行为（场景方法「bindsCentralizedIntegrationAndSafetyConfiguration」）”场景：驱动 「contextRunner.run」、「context.getBean」、「properties.temporal」、「properties.agent」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「case-dispute-task-queue」。
    // 上游调用：「AppPropertiesTest.bindsCentralizedIntegrationAndSafetyConfiguration()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AppPropertiesTest.bindsCentralizedIntegrationAndSafetyConfiguration()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AppPropertiesTest.bindsCentralizedIntegrationAndSafetyConfiguration()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「case-dispute-task-queue」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void bindsCentralizedIntegrationAndSafetyConfiguration() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AppProperties.class);
                    AppProperties properties = context.getBean(AppProperties.class);

                    assertThat(properties.temporal().taskQueue())
                            .isEqualTo("case-dispute-task-queue");
                    assertThat(properties.agent().timeoutMs()).isEqualTo(120000);
                    assertThat(properties.feature().humanReviewRequired()).isTrue();
                    assertThat(properties.feature().toolExecutorSimulation()).isTrue();
                    assertThat(properties.logging().sensitiveMaskingEnabled()).isTrue();
                });
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「PropertiesConfiguration」。
    // 类型职责：在 Spring 启动期装配PropertiesConfiguration所需 Bean 和基础设施参数；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {}
}
