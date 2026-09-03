/*
 * 所属模块：平台人工终审。
 * 文件职责：验证动作快照哈希器，覆盖 「hashIsStableWhenNestedObjectFieldOrderChanges」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// 所属模块：【平台人工终审 / 自动化测试层】类型「ActionSnapshotHasherTest」。
// 类型职责：集中验证动作快照哈希器的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「hashIsStableWhenNestedObjectFieldOrderChanges」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class ActionSnapshotHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // 所属模块：【平台人工终审 / 自动化测试层】「ActionSnapshotHasherTest.hashIsStableWhenNestedObjectFieldOrderChanges()」。
    // 具体功能：「ActionSnapshotHasherTest.hashIsStableWhenNestedObjectFieldOrderChanges()」：复现“核对完整业务行为（场景方法「hashIsStableWhenNestedObjectFieldOrderChanges」）”场景：驱动 「ActionSnapshotHasher.hash」、「objectMapper.readTree」、「isEqualTo」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「ActionSnapshotHasherTest.hashIsStableWhenNestedObjectFieldOrderChanges()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActionSnapshotHasherTest.hashIsStableWhenNestedObjectFieldOrderChanges()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActionSnapshotHasherTest.hashIsStableWhenNestedObjectFieldOrderChanges()」守住「平台人工终审」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void hashIsStableWhenNestedObjectFieldOrderChanges() throws Exception {
        var original =
                objectMapper.readTree(
                        """
                        {
                          "id":"REMEDY_1",
                          "version":1,
                          "actions":[{
                            "action_type":"RESHIP",
                            "idempotency_key":"REMEDY:CASE_1:1:0:RESHIP",
                            "preconditions":["PLATFORM_REVIEW_APPROVED"],
                            "risk_level":"HIGH",
                            "requires_approval":true,
                            "parameters":{"sku":"A","qty":1}
                          }],
                          "preconditions":["PLATFORM_REVIEW_APPROVED"],
                          "notifications":["NOTIFY_USER_AFTER_EXECUTION"]
                        }
                        """);
        var reordered =
                objectMapper.readTree(
                        """
                        {
                          "notifications":["NOTIFY_USER_AFTER_EXECUTION"],
                          "preconditions":["PLATFORM_REVIEW_APPROVED"],
                          "actions":[{
                            "parameters":{"qty":1,"sku":"A"},
                            "requires_approval":true,
                            "risk_level":"HIGH",
                            "preconditions":["PLATFORM_REVIEW_APPROVED"],
                            "idempotency_key":"REMEDY:CASE_1:1:0:RESHIP",
                            "action_type":"RESHIP"
                          }],
                          "version":1,
                          "id":"REMEDY_1"
                        }
                        """);

        assertThat(ActionSnapshotHasher.hash(objectMapper, reordered))
                .isEqualTo(ActionSnapshotHasher.hash(objectMapper, original));
    }
}
