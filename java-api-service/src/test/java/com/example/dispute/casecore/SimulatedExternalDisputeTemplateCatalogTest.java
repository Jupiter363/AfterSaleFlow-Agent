/*
 * 所属模块：案件核心与导入。
 * 文件职责：验证模拟外部争议模板，覆盖 「containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护争议案件事实、来源、幂等导入和演示数据清理。
 * 关键边界：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
 */
package com.example.dispute.casecore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplate;
import com.example.dispute.casecore.application.SimulatedExternalDisputeTemplateCatalog;
import com.example.dispute.config.ActorRole;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

// 所属模块：【案件核心与导入 / 自动化测试层】类型「SimulatedExternalDisputeTemplateCatalogTest」。
// 类型职责：集中验证模拟外部争议模板的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Java/PostgreSQL 是案件状态事实源，导入重试不能创建重复案件
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class SimulatedExternalDisputeTemplateCatalogTest {

    private final SimulatedExternalDisputeTemplateCatalog catalog =
            new SimulatedExternalDisputeTemplateCatalog();

    // 所属模块：【案件核心与导入 / 自动化测试层】「SimulatedExternalDisputeTemplateCatalogTest.containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates()」。
    // 具体功能：「SimulatedExternalDisputeTemplateCatalogTest.containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates()」：复现“核对完整业务行为（场景方法「containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates」）”场景：驱动 「IntStream.rangeClosed」、「catalog.all」、「template.description」、「template.originalStatement」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「TEMPLATE_SIMULATED_OMS」、「[\\p{IsHan}]」、「我」、「商家」。
    // 上游调用：「SimulatedExternalDisputeTemplateCatalogTest.containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「SimulatedExternalDisputeTemplateCatalogTest.containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「SimulatedExternalDisputeTemplateCatalogTest.containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates()」守住「案件核心与导入」的可执行规格，尤其防止 「TEMPLATE_SIMULATED_OMS」、「[\\p{IsHan}]」、「我」、「商家」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void containsExactlyTwentyOrderedUniqueAndCompleteUtf8Templates() {
        assertThat(SimulatedExternalDisputeTemplateCatalog.SOURCE_SYSTEM)
                .isEqualTo("TEMPLATE_SIMULATED_OMS");
        assertThat(catalog.all()).hasSize(20);
        assertThat(catalog.all())
                .extracting(SimulatedExternalDisputeTemplate::templateNo)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(catalog.all())
                .extracting(SimulatedExternalDisputeTemplate::title)
                .doesNotHaveDuplicates()
                .allSatisfy(title -> assertThat(title).containsPattern("[\\p{IsHan}]").isNotBlank());
        assertThat(catalog.all())
                .extracting(SimulatedExternalDisputeTemplate::disputeType)
                .doesNotHaveDuplicates();
        assertThat(catalog.all())
                .allSatisfy(
                        template -> {
                            assertThat(template.description()).isNotBlank();
                            assertThat(template.originalStatement())
                                    .isNotBlank()
                                    .isNotEqualTo(template.description())
                                    .contains("我")
                                    .containsAnyOf("商家", "客服", "对方");
                            assertThat(template.riskLevel()).isNotNull();
                            assertThat(template.requestedResolution())
                                    .isIn(
                                            "REFUND",
                                            "RETURN_REFUND",
                                            "RESHIP",
                                            "REPLACE_OR_REPAIR",
                                            "COMPENSATION",
                                            "CANCEL_ORDER",
                                            "VERIFY_OR_EXPLAIN_ONLY",
                                            "OTHER");
                            assertThat(template.requestedItems()).isNotBlank();
                            assertThat(template.requestReason()).isNotBlank();
                            assertThat(template.respondentAttitude())
                                    .isIn(
                                            "NOT_RESPONDED",
                                            "AGREE",
                                            "PARTIALLY_AGREE",
                                            "DISAGREE",
                                            "ALTERNATIVE_PROPOSED",
                                            "NEED_MORE_INFO",
                                            "PLATFORM_UNKNOWN");
                            assertThat(template.respondentPosition()).isNotBlank();

                            SimulatedExternalDisputeTemplate.InitiatorPerspective user =
                                    template.forInitiator(ActorRole.USER);
                            assertThat(user.originalStatement())
                                    .isEqualTo(template.originalStatement());
                            assertThat(user.requestedResolution())
                                    .isEqualTo(template.requestedResolution());
                            assertThat(user.respondentPosition())
                                    .isEqualTo(template.respondentPosition());

                            SimulatedExternalDisputeTemplate.InitiatorPerspective merchant =
                                    template.forInitiator(ActorRole.MERCHANT);
                            assertThat(merchant.originalStatement())
                                    .startsWith("我们")
                                    .contains("用户的诉求是：")
                                    .contains(template.requestReason())
                                    .isNotEqualTo(template.originalStatement());
                            assertThat(merchant.requestReason())
                                    .startsWith("我们")
                                    .contains("希望平台核验");
                            assertThat(merchant.respondentPosition())
                                    .isEqualTo("对方主张：" + template.requestReason())
                                    .doesNotContain(template.respondentPosition());
                            assertThat(merchant.requestedResolution())
                                    .isIn(
                                            template.requestedResolution(),
                                            "VERIFY_OR_EXPLAIN_ONLY");
                        });
    }
}
