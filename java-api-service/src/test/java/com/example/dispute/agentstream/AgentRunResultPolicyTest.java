/*
 * 所属模块：Agent 流式运行。
 * 文件职责：验证Agent运行，覆盖 「hearingProjectionOnlyContainsExplicitlyPublicDraftText」、「evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision」、「reviewProjectionExposesAnswerButNotFrozenPacketOrReferences」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunResultPolicy;
import com.example.dispute.agentstream.application.AgentStreamOperationRegistry;
import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// 所属模块：【Agent 流式运行 / 自动化测试层】类型「AgentRunResultPolicyTest」。
// 类型职责：集中验证Agent运行的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「hearingProjectionOnlyContainsExplicitlyPublicDraftText」、「evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision」、「reviewProjectionExposesAnswerButNotFrozenPacketOrReferences」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class AgentRunResultPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentRunResultPolicy policy =
            new AgentRunResultPolicy(objectMapper, new AgentStreamOperationRegistry());

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunResultPolicyTest.hearingProjectionOnlyContainsExplicitlyPublicDraftText()」。
    // 具体功能：「AgentRunResultPolicyTest.hearingProjectionOnlyContainsExplicitlyPublicDraftText()」：复现“核对完整业务行为（场景方法「hearingProjectionOnlyContainsExplicitlyPublicDraftText」）”场景：驱动 「policy.publicProjection」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「HEARING_ANALYSIS」、「issue_framing」、「neutral_summary」、「中立摘要」。
    // 上游调用：「AgentRunResultPolicyTest.hearingProjectionOnlyContainsExplicitlyPublicDraftText()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunResultPolicyTest.hearingProjectionOnlyContainsExplicitlyPublicDraftText()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunResultPolicyTest.hearingProjectionOnlyContainsExplicitlyPublicDraftText()」守住「Agent 流式运行」的可执行规格，尤其防止 「HEARING_ANALYSIS」、「issue_framing」、「neutral_summary」、「中立摘要」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void hearingProjectionOnlyContainsExplicitlyPublicDraftText() throws Exception {
        var result =
                objectMapper.readTree(
                        """
                        {
                          "issue_framing":{"neutral_summary":"中立摘要","private_note":"不可见"},
                          "adjudication_draft":{"draft":{
                            "recommended_outcome":"建议退款",
                            "reasoning_summary":"依据已核验材料形成非最终草案",
                            "internal_matrix_patch":{"fact":"不可见"}
                          }},
                          "reasoning_content":"不可见"
                        }
                        """);

        var projection = policy.publicProjection("HEARING_ANALYSIS", result);

        assertThat(projection.path("issue_framing").path("neutral_summary").asText())
                .isEqualTo("中立摘要");
        assertThat(
                        projection
                                .path("adjudication_draft")
                                .path("draft")
                                .path("recommended_outcome")
                                .asText())
                .isEqualTo("建议退款");
        assertThat(projection.toString())
                .doesNotContain("private_note", "internal_matrix_patch", "reasoning_content", "不可见");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunResultPolicyTest.evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision()」。
    // 具体功能：「AgentRunResultPolicyTest.evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision()」：复现“核对完整业务行为（场景方法「evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision」）”场景：驱动 「policy.validate」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_TURN」。
    // 上游调用：「AgentRunResultPolicyTest.evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunResultPolicyTest.evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunResultPolicyTest.evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision()」守住「Agent 流式运行」的可执行规格，尤其防止 「EVIDENCE_TURN」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void evidenceTurnCannotSmuggleAResponsibilityOrRemedyDecision() throws Exception {
        var result =
                objectMapper.readTree(
                        """
                        {
                          "room_utterance":"已完成材料核验。",
                          "memory_patch":{},
                          "canvas_operations":[],
                          "liability_determined":true,
                          "remedy_recommended":false
                        }
                        """);

        assertThatThrownBy(() -> policy.validate("EVIDENCE_TURN", result))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("prohibited decision");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunResultPolicyTest.reviewProjectionExposesAnswerButNotFrozenPacketOrReferences()」。
    // 具体功能：「AgentRunResultPolicyTest.reviewProjectionExposesAnswerButNotFrozenPacketOrReferences()」：复现“核对完整业务行为（场景方法「reviewProjectionExposesAnswerButNotFrozenPacketOrReferences」）”场景：驱动 「policy.validate」、「policy.publicProjection」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REVIEW」、「{\"answer\":\"草案最需要复核物流签收人身份。\"}」。
    // 上游调用：「AgentRunResultPolicyTest.reviewProjectionExposesAnswerButNotFrozenPacketOrReferences()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunResultPolicyTest.reviewProjectionExposesAnswerButNotFrozenPacketOrReferences()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunResultPolicyTest.reviewProjectionExposesAnswerButNotFrozenPacketOrReferences()」守住「Agent 流式运行」的可执行规格，尤其防止 「REVIEW」、「{\"answer\":\"草案最需要复核物流签收人身份。\"}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reviewProjectionExposesAnswerButNotFrozenPacketOrReferences() throws Exception {
        var result =
                objectMapper.readTree(
                        """
                        {
                          "answer":"草案最需要复核物流签收人身份。",
                          "statements":[{"kind":"SUGGESTION","text":"内部结构","refs":["FACT_1"]}],
                          "fact_refs":["FACT_1"],
                          "approval_performed":false,
                          "execution_triggered":false,
                          "is_final_decision":false
                        }
                        """);

        policy.validate("REVIEW", result);
        var projection = policy.publicProjection("REVIEW", result);

        assertThat(projection.toString()).isEqualTo("{\"answer\":\"草案最需要复核物流签收人身份。\"}");
    }
}
