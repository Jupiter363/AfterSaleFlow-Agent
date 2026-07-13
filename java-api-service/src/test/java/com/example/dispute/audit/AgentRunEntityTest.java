/*
 * 所属模块：审计追踪。
 * 文件职责：验证Agent运行，覆盖 「completedRunRetainsGovernanceCostAndTraceMetadata」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；查询不可变审计事实，使管理端能够追溯操作者、业务对象和状态变更。
 * 关键边界：审计数据只追加不回写，普通当事人不能读取平台内部记录
 */
package com.example.dispute.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

// 所属模块：【审计追踪 / 自动化测试层】类型「AgentRunEntityTest」。
// 类型职责：集中验证Agent运行的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「completedRunRetainsGovernanceCostAndTraceMetadata」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：审计数据只追加不回写，普通当事人不能读取平台内部记录
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class AgentRunEntityTest {

    // 所属模块：【审计追踪 / 自动化测试层】「AgentRunEntityTest.completedRunRetainsGovernanceCostAndTraceMetadata()」。
    // 具体功能：「AgentRunEntityTest.completedRunRetainsGovernanceCostAndTraceMetadata()」：复现“核对完整业务行为（场景方法「completedRunRetainsGovernanceCostAndTraceMetadata」）”场景：驱动 「AgentRunEntity.completed」、「run.getRunStatus」、「run.getTraceId」、「run.getTokenUsage」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「RUN_test」、「CASE_test」、「WORKFLOW_test」、「presiding-judge」。
    // 上游调用：「AgentRunEntityTest.completedRunRetainsGovernanceCostAndTraceMetadata()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunEntityTest.completedRunRetainsGovernanceCostAndTraceMetadata()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunEntityTest.completedRunRetainsGovernanceCostAndTraceMetadata()」守住「审计追踪」的可执行规格，尤其防止 「RUN_test」、「CASE_test」、「WORKFLOW_test」、「presiding-judge」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completedRunRetainsGovernanceCostAndTraceMetadata() {
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        AgentRunEntity run =
                AgentRunEntity.completed(
                        "RUN_test",
                        "CASE_test",
                        "WORKFLOW_test",
                        "presiding-judge",
                        "PRESIDING_JUDGE",
                        "presiding-judge-v1",
                        "hearing-v1",
                        "dispute-default-v1",
                        "ruleset-current",
                        "test-model",
                        "[\"EVIDENCE_1\",\"RULE_1\"]",
                        "DRAFT_1",
                        "{\"schema_valid\":true}",
                        "[]",
                        321,
                        450L,
                        new BigDecimal("0.012345"),
                        startedAt,
                        "TRACE_test",
                        "temporal-worker");

        assertThat(run.getRunStatus()).isEqualTo("COMPLETED");
        assertThat(run.getTraceId()).isEqualTo("TRACE_test");
        assertThat(run.getTokenUsage()).isEqualTo(321);
        assertThat(run.getLatencyMs()).isEqualTo(450L);
        assertThat(run.getCostAmount()).isEqualByComparingTo("0.012345");
        assertThat(run.getOutputRef()).isEqualTo("DRAFT_1");
    }
}
