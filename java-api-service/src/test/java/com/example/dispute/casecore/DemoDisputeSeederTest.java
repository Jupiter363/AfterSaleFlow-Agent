/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证演示案件争议Seeder，覆盖 「seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled」、「doesNotRegisterTheSeederWhenDisabled」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.casecore.application.DisputeImportService;
import com.example.dispute.casecore.application.ImportDisputeCommand;
import com.example.dispute.casecore.application.ImportedDisputeView;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

// 所属模块：【案件核心与导入 / 自动化测试层】类型「DemoDisputeSeederTest」。
// 类型职责：集中验证演示案件争议Seeder的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled」、「doesNotRegisterTheSeederWhenDisabled」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DemoDisputeSeederTest {

    private final DisputeImportService importService = mock(DisputeImportService.class);

    // 所属模块：【案件核心与导入 / 自动化测试层】「DemoDisputeSeederTest.seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled()」。
    // 具体功能：「DemoDisputeSeederTest.seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled()」：复现“核对完整业务行为（场景方法「seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled」）”场景：驱动 「importService.importDispute」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_DEMO」、「ORDER-DEMO-1」、「AS-DEMO-1」、「SF-DEMO-1」。
    // 上游调用：「DemoDisputeSeederTest.seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoDisputeSeederTest.seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoDisputeSeederTest.seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled()」守住「案件核心与导入」的可执行规格，尤其防止 「CASE_DEMO」、「ORDER-DEMO-1」、「AS-DEMO-1」、「SF-DEMO-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void seedsSixDeterministicDisputesThroughTheImportServiceWhenEnabled() {
        when(importService.importDispute(any(), any(), anyString()))
                .thenReturn(
                        new ImportedDisputeView(
                                "CASE_DEMO",
                                "ORDER-DEMO-1",
                                "AS-DEMO-1",
                                "SF-DEMO-1",
                                "user-local",
                                "merchant-local",
                                "SIGNED_NOT_RECEIVED",
                                "EXTERNAL_IMPORT",
                                "DEMO",
                                "DEMO-1",
                                RiskLevel.MEDIUM,
                                "签收未收到",
                                "用户表示未收到已签收包裹",
                                CaseStatus.INTAKE_PENDING,
                                "INTAKE",
                                null,
                                "COMPLETE_INTAKE",
                                "USER"));

        new ApplicationContextRunner()
                .withUserConfiguration(DemoSeederScan.class)
                .withBean(DisputeImportService.class, () -> importService)
                .withBean(
                        Clock.class,
                        () ->
                                Clock.fixed(
                                        Instant.parse("2026-07-03T00:00:00Z"),
                                        ZoneOffset.UTC))
                .withPropertyValues("dispute.seed-demo-disputes=true")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ApplicationRunner.class);
                            context.getBean(ApplicationRunner.class).run(null);
                        });

        ArgumentCaptor<ImportDisputeCommand> commands =
                ArgumentCaptor.forClass(ImportDisputeCommand.class);
        verify(importService, org.mockito.Mockito.times(6))
                .importDispute(commands.capture(), any(), anyString());
        List<ImportDisputeCommand> seeded = commands.getAllValues();
        assertThat(seeded)
                .extracting(ImportDisputeCommand::externalCaseReference)
                .doesNotHaveDuplicates()
                .containsExactly(
                        "DEMO-DISPUTE-001",
                        "DEMO-DISPUTE-002",
                        "DEMO-DISPUTE-003",
                        "DEMO-DISPUTE-004",
                        "DEMO-DISPUTE-005",
                        "DEMO-DISPUTE-006");
        assertThat(seeded)
                .extracting(ImportDisputeCommand::caseStatus)
                .contains(
                        CaseStatus.INTAKE_PENDING,
                        CaseStatus.EVIDENCE_OPEN,
                        CaseStatus.HEARING_OPEN,
                        CaseStatus.REVIEW_PENDING,
                        CaseStatus.CLOSED);
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】「DemoDisputeSeederTest.doesNotRegisterTheSeederWhenDisabled()」。
    // 具体功能：「DemoDisputeSeederTest.doesNotRegisterTheSeederWhenDisabled()」：复现“核对完整业务行为（场景方法「doesNotRegisterTheSeederWhenDisabled」）”场景：驱动 「run」、「withPropertyValues」、「withBean」、「newApplicationContextRunner().withUserConfiguration」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「dispute.seed-demo-disputes=false」。
    // 上游调用：「DemoDisputeSeederTest.doesNotRegisterTheSeederWhenDisabled()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DemoDisputeSeederTest.doesNotRegisterTheSeederWhenDisabled()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DemoDisputeSeederTest.doesNotRegisterTheSeederWhenDisabled()」守住「案件核心与导入」的可执行规格，尤其防止 「dispute.seed-demo-disputes=false」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void doesNotRegisterTheSeederWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DemoSeederScan.class)
                .withBean(DisputeImportService.class, () -> importService)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("dispute.seed-demo-disputes=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
    }

    // 所属模块：【案件核心与导入 / 自动化测试层】类型「DemoSeederScan」。
    // 类型职责：承载演示案件SeederScan在当前业务模块中的规则与协作边界；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackages = "com.example.dispute.casecore.application",
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern =
                                    "com\\.example\\.dispute\\.casecore\\.application\\.DemoDisputeSeeder"))
    static class DemoSeederScan {}
}
