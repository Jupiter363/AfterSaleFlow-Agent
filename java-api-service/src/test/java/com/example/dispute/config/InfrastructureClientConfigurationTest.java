/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：验证Infrastructure，覆盖 「createsMinioAndTemporalClientsFromCentralProperties」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MinioClient;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「InfrastructureClientConfigurationTest」。
// 类型职责：集中验证Infrastructure的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「createsMinioAndTemporalClientsFromCentralProperties」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class InfrastructureClientConfigurationTest {

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「InfrastructureClientConfigurationTest.createsMinioAndTemporalClientsFromCentralProperties()」。
    // 具体功能：「InfrastructureClientConfigurationTest.createsMinioAndTemporalClientsFromCentralProperties()」：复现“创建并持久化（场景方法「createsMinioAndTemporalClientsFromCentralProperties」）”场景：驱动 「context.getBean」、「run」、「withUserConfiguration」、「newApplicationContextRunner().withBean」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「test」、「java-secret」、「agent-secret」、「ocr-secret」。
    // 上游调用：「InfrastructureClientConfigurationTest.createsMinioAndTemporalClientsFromCentralProperties()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「InfrastructureClientConfigurationTest.createsMinioAndTemporalClientsFromCentralProperties()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「InfrastructureClientConfigurationTest.createsMinioAndTemporalClientsFromCentralProperties()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「test」、「java-secret」、「agent-secret」、「ocr-secret」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void createsMinioAndTemporalClientsFromCentralProperties() {
        AppProperties properties =
                new AppProperties(
                        "test",
                        new AppProperties.Security("java-secret"),
                        new AppProperties.Integration(
                                "http://agent:8000", "agent-secret", 120000),
                        new AppProperties.Integration(
                                "http://ocr:8010", "ocr-secret", 120000),
                        new AppProperties.Temporal(
                                "localhost:7233", "default", "case-dispute-task-queue"),
                        new AppProperties.Minio(
                                "http://localhost:19000",
                                "minio-user",
                                "minio-password",
                                "evidence-original",
                                "evidence-desensitized"),
                        new AppProperties.Elasticsearch("http://localhost:19200"),
                        new AppProperties.Feature(true, true, true, true, true, true, true),
                        new AppProperties.Logging(true, true));

        new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> properties)
                .withUserConfiguration(InfrastructureClientConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MinioClient.class);
                            assertThat(context).hasSingleBean(WorkflowServiceStubs.class);
                            assertThat(context).hasSingleBean(WorkflowClient.class);
                            assertThat(
                                            context.getBean(WorkflowServiceStubs.class)
                                                    .getOptions()
                                                    .getTarget())
                                    .isEqualTo("localhost:7233");
                        });
    }
}
