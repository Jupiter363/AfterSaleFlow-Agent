/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证Temporal类型Isolation，覆盖 「finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.FulfillmentDisputeActivities;
import io.temporal.activity.ActivityInterface;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「TemporalActivityTypeIsolationTest」。
// 类型职责：集中验证Temporal类型Isolation的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class TemporalActivityTypeIsolationTest {

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「TemporalActivityTypeIsolationTest.finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities()」。
    // 具体功能：「TemporalActivityTypeIsolationTest.finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities()」：复现“核对完整业务行为（场景方法「finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities」）”场景：驱动 「contract.namePrefix」、「FulfillmentDisputeActivities.class.getAnnotation」、「assertThat(contract.namePrefix()).isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「FinalFulfillment_」。
    // 上游调用：「TemporalActivityTypeIsolationTest.finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「TemporalActivityTypeIsolationTest.finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「TemporalActivityTypeIsolationTest.finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「FinalFulfillment_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalFulfillmentActivitiesUseANamespaceDistinctFromLegacyActivities() {
        ActivityInterface contract =
                FulfillmentDisputeActivities.class.getAnnotation(ActivityInterface.class);

        assertThat(contract.namePrefix()).isEqualTo("FinalFulfillment_");
    }
}
