/*
 * 所属模块：共享小法庭。
 * 文件职责：验证AgentA2A消息，覆盖 「recordsJurySilentNotesAndFindsThemForLaterJudgeRounds」、「checksTheExactFormalJuryReportForTheFinalRound」、「loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.application.AgentA2ACommand;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.AgentA2AMessageView;
import com.example.dispute.hearing.infrastructure.persistence.entity.AgentA2AMessageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.AgentA2AMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【共享小法庭 / 自动化测试层】类型「AgentA2AMessageServiceTest」。
// 类型职责：集中验证AgentA2A消息的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「recordsJurySilentNotesAndFindsThemForLaterJudgeRounds」、「checksTheExactFormalJuryReportForTheFinalRound」、「loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class AgentA2AMessageServiceTest {

    @Mock private AgentA2AMessageRepository repository;

    // 所属模块：【共享小法庭 / 自动化测试层】「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds()」。
    // 具体功能：「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds()」：复现“核对完整业务行为（场景方法「recordsJurySilentNotesAndFindsThemForLaterJudgeRounds」）”场景：驱动 「repository.save」、「service.record」、「repository.findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc」、「service.findForJudge」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-08T02:00:00Z」、「CASE_A2A」、「JURY_PANEL」、「PRESIDING_JUDGE」。
    // 上游调用：「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentA2AMessageServiceTest.recordsJurySilentNotesAndFindsThemForLaterJudgeRounds()」守住「共享小法庭」的可执行规格，尤其防止 「2026-07-08T02:00:00Z」、「CASE_A2A」、「JURY_PANEL」、「PRESIDING_JUDGE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void recordsJurySilentNotesAndFindsThemForLaterJudgeRounds() {
        AgentA2AMessageService service =
                new AgentA2AMessageService(
                        repository,
                        new ObjectMapper(),
                        Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentA2AMessageView recorded =
                service.record(
                        new AgentA2ACommand(
                                "CASE_A2A",
                                2,
                                "JURY_PANEL",
                                "PRESIDING_JUDGE",
                                "JURY_SILENT_NOTE",
                                Map.of("evidence_dossier_version", 2),
                                Map.of("judge_attention", List.of("签收人身份仍需关注")),
                                "SYSTEM_AUDIT_ONLY",
                                "RUN_JURY_2"));
        ArgumentCaptor<AgentA2AMessageEntity> entity =
                ArgumentCaptor.forClass(AgentA2AMessageEntity.class);
        org.mockito.Mockito.verify(repository).save(entity.capture());
        when(repository
                        .findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(
                                "CASE_A2A", "PRESIDING_JUDGE", 3))
                .thenReturn(List.of(entity.getValue()));

        assertThat(recorded.a2aMessageId()).startsWith("A2A_");
        assertThat(recorded.roundNo()).isEqualTo(2);

        assertThat(service.findForJudge("CASE_A2A", 3))
                .singleElement()
                .satisfies(
                        note -> {
                            assertThat(note.messageType()).isEqualTo("JURY_SILENT_NOTE");
                            assertThat(note.fromAgent()).isEqualTo("JURY_PANEL");
                            assertThat(note.toAgent()).isEqualTo("PRESIDING_JUDGE");
                            assertThat(note.payloadJson()).contains("签收人身份仍需关注");
                            assertThat(note.inputRefsJson()).contains("\"evidence_dossier_version\":2");
                            assertThat(note.visibility()).isEqualTo("SYSTEM_AUDIT_ONLY");
                        });
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound()」。
    // 具体功能：「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound()」：复现“核对完整业务行为（场景方法「checksTheExactFormalJuryReportForTheFinalRound」）”场景：驱动 「repository.existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType」、「service.hasFormalJuryReviewReport」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-08T02:00:00Z」、「CASE_A2A」、「JURY_PANEL」、「JURY_REVIEW_REPORT」。
    // 上游调用：「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentA2AMessageServiceTest.checksTheExactFormalJuryReportForTheFinalRound()」守住「共享小法庭」的可执行规格，尤其防止 「2026-07-08T02:00:00Z」、「CASE_A2A」、「JURY_PANEL」、「JURY_REVIEW_REPORT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void checksTheExactFormalJuryReportForTheFinalRound() {
        AgentA2AMessageService service =
                new AgentA2AMessageService(
                        repository,
                        new ObjectMapper(),
                        Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC));
        when(repository
                        .existsByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageType(
                                "CASE_A2A",
                                3,
                                "JURY_PANEL",
                                AgentA2AMessageService.PRESIDING_JUDGE,
                                "JURY_REVIEW_REPORT"))
                .thenReturn(true);

        assertThat(service.hasFormalJuryReviewReport("CASE_A2A", 3)).isTrue();
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload()」。
    // 具体功能：「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload()」：复现“核对完整业务行为（场景方法「loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload」）”场景：驱动 「repository.findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc」、「service.findFormalJuryReviewReport」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「2026-07-08T02:00:00Z」、「A2A_FORMAL」、「CASE_A2A」、「JURY_PANEL」。
    // 上游调用：「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentA2AMessageServiceTest.loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload()」守住「共享小法庭」的可执行规格，尤其防止 「2026-07-08T02:00:00Z」、「A2A_FORMAL」、「CASE_A2A」、「JURY_PANEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void loadsTheFormalJuryReportSoRepairCanReuseItsExactPayload() {
        AgentA2AMessageService service =
                new AgentA2AMessageService(
                        repository,
                        new ObjectMapper(),
                        Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC));
        AgentA2AMessageEntity report =
                AgentA2AMessageEntity.create(
                        "A2A_FORMAL",
                        "CASE_A2A",
                        3,
                        "JURY_PANEL",
                        AgentA2AMessageService.PRESIDING_JUDGE,
                        "JURY_REVIEW_REPORT",
                        "{\"source\":\"surviving-a2a\"}",
                        "{\"summary\":\"reuse this exact conclusion\",\"confidence_score\":91}",
                        "REVIEWER_VISIBLE",
                        "RUN_FORMAL",
                        Instant.parse("2026-07-08T02:00:00Z"),
                        "jury-panel");
        when(repository
                        .findFirstByCaseIdAndRoundNoAndFromAgentAndToAgentAndMessageTypeOrderByCreatedAtAsc(
                                "CASE_A2A",
                                3,
                                "JURY_PANEL",
                                AgentA2AMessageService.PRESIDING_JUDGE,
                                "JURY_REVIEW_REPORT"))
                .thenReturn(Optional.of(report));

        assertThat(service.findFormalJuryReviewReport("CASE_A2A", 3))
                .hasValueSatisfying(
                        view -> {
                            assertThat(view.inputRefsJson())
                                    .isEqualTo("{\"source\":\"surviving-a2a\"}");
                            assertThat(view.payloadJson())
                                    .isEqualTo(
                                            "{\"summary\":\"reuse this exact conclusion\",\"confidence_score\":91}");
                        });
    }
}
