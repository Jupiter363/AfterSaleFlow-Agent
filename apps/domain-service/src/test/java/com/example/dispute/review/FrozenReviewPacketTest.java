/*
 * 所属模块：平台人工终审。
 * 文件职责：验证冻结审核审核包，覆盖 「freezesEverySourceVersionAndActionHashBeforeReview」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.review.domain.ReviewPacketVersions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

// 所属模块：【平台人工终审 / 自动化测试层】类型「FrozenReviewPacketTest」。
// 类型职责：集中验证冻结审核审核包的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「freezesEverySourceVersionAndActionHashBeforeReview」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class FrozenReviewPacketTest {

    // 所属模块：【平台人工终审 / 自动化测试层】「FrozenReviewPacketTest.freezesEverySourceVersionAndActionHashBeforeReview()」。
    // 具体功能：「FrozenReviewPacketTest.freezesEverySourceVersionAndActionHashBeforeReview()」：复现“核对完整业务行为（场景方法「freezesEverySourceVersionAndActionHashBeforeReview」）”场景：驱动 「ReviewPacketEntity.createFrozen」、「frozenAt.plusDays」、「packet.isFrozen」、「packet.getPacketStatus」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「RULESET_2026_07」、「hearing-v2」、「signed-not-received-v3」、「presiding-judge-v2」。
    // 上游调用：「FrozenReviewPacketTest.freezesEverySourceVersionAndActionHashBeforeReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「FrozenReviewPacketTest.freezesEverySourceVersionAndActionHashBeforeReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「FrozenReviewPacketTest.freezesEverySourceVersionAndActionHashBeforeReview()」守住「平台人工终审」的可执行规格，尤其防止 「RULESET_2026_07」、「hearing-v2」、「signed-not-received-v3」、「presiding-judge-v2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void freezesEverySourceVersionAndActionHashBeforeReview() {
        OffsetDateTime frozenAt =
                OffsetDateTime.of(
                        2026, 7, 2, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime expiresAt = frozenAt.plusDays(7);
        ReviewPacketVersions versions =
                new ReviewPacketVersions(
                        11,
                        4,
                        3,
                        2,
                        1,
                        5,
                        "RULESET_2026_07",
                        "hearing-v2",
                        "signed-not-received-v3",
                        "presiding-judge-v2");

        ReviewPacketEntity packet =
                ReviewPacketEntity.createFrozen(
                        "PACKET_frozen",
                        "CASE_frozen",
                        "REMEDY_frozen",
                        6,
                        versions,
                        "ACTION_HASH_frozen",
                        frozenAt,
                        expiresAt,
                        "{}",
                        "[]",
                        "[]",
                        "[]",
                        "{}",
                        "{}",
                        "[]",
                        "SYSTEM");

        assertThat(packet.isFrozen()).isTrue();
        assertThat(packet.getPacketStatus()).isEqualTo("FROZEN");
        assertThat(packet.getCaseVersion()).isEqualTo(11);
        assertThat(packet.getDossierVersion()).isEqualTo(4);
        assertThat(packet.getIssueVersion()).isEqualTo(3);
        assertThat(packet.getAdjudicationDraftVersion()).isEqualTo(2);
        assertThat(packet.getDeliberationReportVersion()).isEqualTo(1);
        assertThat(packet.getRemedyPlanVersion()).isEqualTo(5);
        assertThat(packet.getRulesetVersion()).isEqualTo("RULESET_2026_07");
        assertThat(packet.getPromptVersion()).isEqualTo("hearing-v2");
        assertThat(packet.getSkillVersion())
                .isEqualTo("signed-not-received-v3");
        assertThat(packet.getProfileVersion())
                .isEqualTo("presiding-judge-v2");
        assertThat(packet.getActionHash()).isEqualTo("ACTION_HASH_frozen");
        assertThat(packet.getFrozenAt()).isEqualTo(frozenAt);
        assertThat(packet.getExpiresAt()).isEqualTo(expiresAt);
    }
}
