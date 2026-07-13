/*
 * 所属模块：身份鉴权与运行配置。
 * 文件职责：验证争议，覆盖 「bindsAllRoomTimingAndDemoSettings」、「rejectsNonPositiveDurationsAndRoundsOutsideOneToFive」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；解析请求身份、限制角色权限并装配基础设施客户端和 Spring Bean。
 * 关键边界：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
 */
package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「DisputePropertiesTest」。
// 类型职责：集中验证争议的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「bindsAllRoomTimingAndDemoSettings」、「rejectsNonPositiveDurationsAndRoundsOutsideOneToFive」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DisputePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesConfiguration.class);

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「DisputePropertiesTest.bindsAllRoomTimingAndDemoSettings()」。
    // 具体功能：「DisputePropertiesTest.bindsAllRoomTimingAndDemoSettings()」：复现“核对完整业务行为（场景方法「bindsAllRoomTimingAndDemoSettings」）”场景：驱动 「Duration.ofHours」、「Duration.ofMinutes」、「Duration.ofSeconds」、「contextRunner.withPropertyValues」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「dispute.evidence-window=PT2H」、「dispute.hearing-window=PT3H」、「dispute.max-hearing-rounds=3」、「dispute.sse-heartbeat=PT15S」。
    // 上游调用：「DisputePropertiesTest.bindsAllRoomTimingAndDemoSettings()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputePropertiesTest.bindsAllRoomTimingAndDemoSettings()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputePropertiesTest.bindsAllRoomTimingAndDemoSettings()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「dispute.evidence-window=PT2H」、「dispute.hearing-window=PT3H」、「dispute.max-hearing-rounds=3」、「dispute.sse-heartbeat=PT15S」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void bindsAllRoomTimingAndDemoSettings() {
        contextRunner
                .withPropertyValues(
                        "dispute.evidence-window=PT2H",
                        "dispute.hearing-window=PT3H",
                        "dispute.hearing-round-window=PT5M",
                        "dispute.max-hearing-rounds=3",
                        "dispute.sse-heartbeat=PT15S",
                        "dispute.seed-demo-disputes=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DisputeProperties properties =
                                    context.getBean(DisputeProperties.class);
                            assertThat(properties.evidenceWindow())
                                    .isEqualTo(Duration.ofHours(2));
                            assertThat(properties.hearingWindow())
                                    .isEqualTo(Duration.ofHours(3));
                            assertThat(properties.hearingRoundWindow())
                                    .isEqualTo(Duration.ofMinutes(5));
                            assertThat(properties.maxHearingRounds()).isEqualTo(3);
                            assertThat(properties.sseHeartbeat())
                                    .isEqualTo(Duration.ofSeconds(15));
                            assertThat(properties.seedDemoDisputes()).isTrue();
                        });
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】「DisputePropertiesTest.rejectsNonPositiveDurationsAndRoundsOutsideOneToFive()」。
    // 具体功能：「DisputePropertiesTest.rejectsNonPositiveDurationsAndRoundsOutsideOneToFive()」：复现“拒绝非法输入或越权操作（场景方法「rejectsNonPositiveDurationsAndRoundsOutsideOneToFive」）”场景：驱动 「contextRunner.withPropertyValues」、「run」、「assertThat(context).hasFailed」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「dispute.evidence-window=PT0S」、「dispute.hearing-window=PT3H」、「dispute.max-hearing-rounds=6」、「dispute.sse-heartbeat=PT15S」。
    // 上游调用：「DisputePropertiesTest.rejectsNonPositiveDurationsAndRoundsOutsideOneToFive()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputePropertiesTest.rejectsNonPositiveDurationsAndRoundsOutsideOneToFive()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputePropertiesTest.rejectsNonPositiveDurationsAndRoundsOutsideOneToFive()」守住「身份鉴权与运行配置」的可执行规格，尤其防止 「dispute.evidence-window=PT0S」、「dispute.hearing-window=PT3H」、「dispute.max-hearing-rounds=6」、「dispute.sse-heartbeat=PT15S」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void rejectsNonPositiveDurationsAndRoundsOutsideOneToFive() {
        contextRunner
                .withPropertyValues(
                        "dispute.evidence-window=PT0S",
                        "dispute.hearing-window=PT3H",
                        "dispute.hearing-round-window=PT5M",
                        "dispute.max-hearing-rounds=6",
                        "dispute.sse-heartbeat=PT15S")
                .run(context -> assertThat(context).hasFailed());
    }

    // 所属模块：【身份鉴权与运行配置 / 自动化测试层】类型「PropertiesConfiguration」。
    // 类型职责：在 Spring 启动期装配PropertiesConfiguration所需 Bean 和基础设施参数；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：调用方身份只能来自可信请求头映射，不能由业务请求体自行声明
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DisputeProperties.class)
    static class PropertiesConfiguration {}
}
