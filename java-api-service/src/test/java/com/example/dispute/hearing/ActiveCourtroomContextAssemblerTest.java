/*
 * 所属模块：共享小法庭。
 * 文件职责：验证当前法庭上下文上下文组装器，覆盖 「finalConvergenceRejectsFormalJuryReportFromEarlierRound」、「finalConvergenceRejectsNonJuryFormalReport」、「finalConvergenceRejectsMissingRoundInRequiredSequence」、「finalConvergenceRejectsOpenRound」、「finalConvergenceRejectsTerminalStatusWithoutClosedAt」、「finalConvergenceRejectsRoundMissingMerchantSubmission」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.application.ActiveCourtroomContextAssembler;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.AgentA2AMessageView;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.tool.application.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【共享小法庭 / 自动化测试层】类型「ActiveCourtroomContextAssemblerTest」。
// 类型职责：集中验证当前法庭上下文上下文组装器的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「finalConvergenceRejectsFormalJuryReportFromEarlierRound」、「finalConvergenceRejectsNonJuryFormalReport」、「finalConvergenceRejectsMissingRoundInRequiredSequence」、「finalConvergenceRejectsOpenRound」、「finalConvergenceRejectsTerminalStatusWithoutClosedAt」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class ActiveCourtroomContextAssemblerTest {

    private static final String CASE_ID = "CASE_FINAL_CONTEXT";

    @Mock private HearingRecordRepository hearingRecordRepository;
    @Mock private EvidenceDossierRepository evidenceDossierRepository;
    @Mock private HearingRoundRepository roundRepository;
    @Mock private HearingRoundPartySubmissionRepository submissionRepository;
    @Mock private AgentA2AMessageService a2aMessageService;
    @Mock private ToolRegistry toolRegistry;

    private ActiveCourtroomContextAssembler assembler;

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.setUp()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.setUp()」：在每个测试场景运行前创建「findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc」、「evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc」、「toolRegistry.definitions」、「Optional.empty」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.setUp()」守住「共享小法庭」的可执行规格，尤其防止 「C0_COURT_BOOTSTRAP」、「BOOTSTRAP_DOSSIER_SNAPSHOT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        assembler =
                new ActiveCourtroomContextAssembler(
                        hearingRecordRepository,
                        evidenceDossierRepository,
                        roundRepository,
                        submissionRepository,
                        a2aMessageService,
                        toolRegistry,
                        objectMapper);
        lenient()
                .when(hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                CASE_ID,
                                "C0_COURT_BOOTSTRAP",
                                1,
                                "BOOTSTRAP_DOSSIER_SNAPSHOT"))
                .thenReturn(Optional.of(bootstrapSnapshot()));
        lenient()
                .when(evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
                .thenReturn(Optional.empty());
        lenient().when(toolRegistry.definitions()).thenReturn(List.of());
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound()」：复现“核对完整业务行为（场景方法「finalConvergenceRejectsFormalJuryReportFromEarlierRound」）”场景：驱动 「a2aMessageService.findForJudge」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「JURY_PANEL」。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound()」守住「共享小法庭」的可执行规格，尤其防止 「JURY_PANEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceRejectsFormalJuryReportFromEarlierRound() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(2, "JURY_PANEL")));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 3");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport()」：复现“核对完整业务行为（场景方法「finalConvergenceRejectsNonJuryFormalReport」）”场景：驱动 「a2aMessageService.findForJudge」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「EVIDENCE_CLERK」、「JURY_PANEL」。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport()」守住「共享小法庭」的可执行规格，尤其防止 「EVIDENCE_CLERK」、「JURY_PANEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceRejectsNonJuryFormalReport() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "EVIDENCE_CLERK")));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JURY_PANEL");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence()」：复现“核对完整业务行为（场景方法「finalConvergenceRejectsMissingRoundInRequiredSequence」）”场景：驱动 「a2aMessageService.findForJudge」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「JURY_PANEL」。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence()」守住「共享小法庭」的可执行规格，尤其防止 「JURY_PANEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceRejectsMissingRoundInRequiredSequence() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), sealedRound(3)));
        seedBothSubmissions(1);

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 2 is missing");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound()」：复现“核对完整业务行为（场景方法「finalConvergenceRejectsOpenRound」）”场景：驱动 「a2aMessageService.findForJudge」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「JURY_PANEL」。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound()」守住「共享小法庭」的可执行规格，尤其防止 「JURY_PANEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceRejectsOpenRound() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), openRound(2), sealedRound(3)));
        seedBothSubmissions(1);

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 2 is not sealed");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsTerminalStatusWithoutClosedAt()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsTerminalStatusWithoutClosedAt()」：复现“核对完整业务行为（场景方法「finalConvergenceRejectsTerminalStatusWithoutClosedAt」）”场景：驱动 「a2aMessageService.findForJudge」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「JURY_PANEL」。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsTerminalStatusWithoutClosedAt()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsTerminalStatusWithoutClosedAt()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsTerminalStatusWithoutClosedAt()」守住「共享小法庭」的可执行规格，尤其防止 「JURY_PANEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceRejectsTerminalStatusWithoutClosedAt() {
        HearingRoundEntity corruptRound = mock(HearingRoundEntity.class);
        when(corruptRound.getRoundNo()).thenReturn(1);
        when(corruptRound.getRoundStatus()).thenReturn(HearingRoundStatus.COMPLETED);
        when(corruptRound.getClosedAt()).thenReturn(null);
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(corruptRound, sealedRound(2), sealedRound(3)));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 1 is not sealed");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission()」：复现“核对完整业务行为（场景方法「finalConvergenceRejectsRoundMissingMerchantSubmission」）”场景：驱动 「a2aMessageService.findForJudge」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」、「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「JURY_PANEL」。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission()」守住「共享小法庭」的可执行规格，尤其防止 「JURY_PANEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceRejectsRoundMissingMerchantSubmission() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), sealedRound(2), sealedRound(3)));
        seedBothSubmissions(1);
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(CASE_ID, 2))
                .thenReturn(List.of(submission(2, ActorRole.USER)));

        assertThatThrownBy(() -> assembler.assembleFinalConvergence(CASE_ID, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("round 2 requires USER and MERCHANT");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory()」：复现“核对完整业务行为（场景方法「finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory」）”场景：驱动 「a2aMessageService.findForJudge」、「roundRepository.findAllByCaseIdOrderByRoundNoAsc」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「JURY_PANEL」、「jury_review_report」、「round_no」、「party_submissions」。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory()」守住「共享小法庭」的可执行规格，尤其防止 「JURY_PANEL」、「jury_review_report」、「round_no」、「party_submissions」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory() {
        when(a2aMessageService.findForJudge(CASE_ID, 3))
                .thenReturn(List.of(formalReport(3, "JURY_PANEL")));
        when(roundRepository.findAllByCaseIdOrderByRoundNoAsc(CASE_ID))
                .thenReturn(List.of(sealedRound(1), sealedRound(2), sealedRound(3)));
        seedBothSubmissions(1);
        seedBothSubmissions(2);
        seedBothSubmissions(3);

        assertThat(assembler.assembleFinalConvergence(CASE_ID, 3)
                        .path("jury_review_report")
                        .path("round_no")
                        .asInt())
                .isEqualTo(3);
        assertThat(assembler.sealedRounds(CASE_ID, 3))
                .hasSize(3)
                .allSatisfy(
                        round ->
                                assertThat(round.path("party_submissions"))
                                        .hasSize(2));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.seedBothSubmissions(int)」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.seedBothSubmissions(int)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedBothSubmissions」）”组装或读取「submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc」、「when」、「submission」、「thenReturn」，供本测试类的场景方法复用。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.seedBothSubmissions(int)」由本测试类中的 「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission」、「ActiveCourtroomContextAssemblerTest.finalConvergenceAcceptsExactRoundReportAndCompleteSealedHistory」 调用。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.seedBothSubmissions(int)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.seedBothSubmissions(int)」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private void seedBothSubmissions(int roundNo) {
        when(submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(
                        CASE_ID, roundNo))
                .thenReturn(
                        List.of(
                                submission(roundNo, ActorRole.USER),
                                submission(roundNo, ActorRole.MERCHANT)));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.sealedRound(int)」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.sealedRound(int)」：作为测试辅助方法为“核对完整业务行为（场景方法「sealedRound」）”组装或读取「HearingRoundEntity.open」、「Instant.parse」、「round.complete」、「Instant.parse("2026-07-10T01:10:00Z").plusSeconds」，供本测试类的场景方法复用。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.sealedRound(int)」由本测试类中的 「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsTerminalStatusWithoutClosedAt」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission」 调用。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.sealedRound(int)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.sealedRound(int)」守住「共享小法庭」的可执行规格，尤其防止 「ROUND_」、「HEARING_STATE」、「2026-07-10T01:10:00Z」、「2026-07-10T01:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static HearingRoundEntity sealedRound(int roundNo) {
        HearingRoundEntity round =
                HearingRoundEntity.open(
                        "ROUND_" + roundNo,
                        CASE_ID,
                        "HEARING_STATE",
                        roundNo,
                        1,
                        Instant.parse("2026-07-10T01:10:00Z").plusSeconds(roundNo),
                        Instant.parse("2026-07-10T01:00:00Z").plusSeconds(roundNo),
                        "system");
        round.complete(
                "{\"round\":" + roundNo + "}",
                null,
                Instant.parse("2026-07-10T01:05:00Z").plusSeconds(roundNo),
                "system");
        return round;
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.openRound(int)」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.openRound(int)」：作为测试辅助方法为“核对完整业务行为（场景方法「openRound」）”组装或读取「HearingRoundEntity.open」、「Instant.parse」、「Instant.parse("2026-07-10T01:10:00Z").plusSeconds」、「Instant.parse("2026-07-10T01:00:00Z").plusSeconds」，供本测试类的场景方法复用。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.openRound(int)」由本测试类中的 「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound」 调用。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.openRound(int)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.openRound(int)」守住「共享小法庭」的可执行规格，尤其防止 「ROUND_」、「HEARING_STATE」、「2026-07-10T01:10:00Z」、「2026-07-10T01:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static HearingRoundEntity openRound(int roundNo) {
        return HearingRoundEntity.open(
                "ROUND_" + roundNo,
                CASE_ID,
                "HEARING_STATE",
                roundNo,
                1,
                Instant.parse("2026-07-10T01:10:00Z").plusSeconds(roundNo),
                Instant.parse("2026-07-10T01:00:00Z").plusSeconds(roundNo),
                "system");
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.submission(int,ActorRole)」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.submission(int,ActorRole)」：作为测试辅助方法为“核对完整业务行为（场景方法「submission」）”组装或读取「HearingRoundPartySubmissionEntity.submit」、「Instant.parse」、「Instant.parse("2026-07-10T01:04:00Z").plusSeconds」，供本测试类的场景方法复用。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.submission(int,ActorRole)」由本测试类中的 「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsRoundMissingMerchantSubmission」、「ActiveCourtroomContextAssemblerTest.seedBothSubmissions」 调用。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.submission(int,ActorRole)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.submission(int,ActorRole)」守住「共享小法庭」的可执行规格，尤其防止 「SUB_」、「_」、「ROUND_」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static HearingRoundPartySubmissionEntity submission(
            int roundNo, ActorRole role) {
        return HearingRoundPartySubmissionEntity.submit(
                "SUB_" + roundNo + "_" + role,
                CASE_ID,
                "ROUND_" + roundNo,
                roundNo,
                role,
                role == ActorRole.USER ? "user-local" : "merchant-local",
                HearingRoundSubmissionSource.PARTY_ACTION,
                "{\"statement\":\"" + role + " round " + roundNo + "\"}",
                Instant.parse("2026-07-10T01:04:00Z").plusSeconds(roundNo));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.formalReport(int,String)」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.formalReport(int,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「formalReport」）”组装或读取「AgentA2AMessageView」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.formalReport(int,String)」由本测试类中的 「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsFormalJuryReportFromEarlierRound」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsNonJuryFormalReport」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsMissingRoundInRequiredSequence」、「ActiveCourtroomContextAssemblerTest.finalConvergenceRejectsOpenRound」 调用。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.formalReport(int,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.formalReport(int,String)」守住「共享小法庭」的可执行规格，尤其防止 「A2A_FORMAL_」、「JURY_REVIEW_REPORT」、「{\"round_no\":」、「}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentA2AMessageView formalReport(int roundNo, String fromAgent) {
        return new AgentA2AMessageView(
                "A2A_FORMAL_" + roundNo,
                CASE_ID,
                roundNo,
                fromAgent,
                AgentA2AMessageService.PRESIDING_JUDGE,
                "JURY_REVIEW_REPORT",
                "{\"round_no\":" + roundNo + "}",
                "{\"summary\":\"formal jury review\"}",
                "REVIEWER_VISIBLE",
                "RUN_JURY_" + roundNo,
                Instant.parse("2026-07-10T01:06:00Z"));
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「ActiveCourtroomContextAssemblerTest.bootstrapSnapshot()」。
    // 具体功能：「ActiveCourtroomContextAssemblerTest.bootstrapSnapshot()」：作为测试辅助方法为“核对完整业务行为（场景方法「bootstrapSnapshot」）”组装或读取「HearingRecordEntity.record」，供本测试类的场景方法复用。
    // 上游调用：「ActiveCourtroomContextAssemblerTest.bootstrapSnapshot()」由本测试类中的 「ActiveCourtroomContextAssemblerTest.setUp」 调用。
    // 下游影响：「ActiveCourtroomContextAssemblerTest.bootstrapSnapshot()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「ActiveCourtroomContextAssemblerTest.bootstrapSnapshot()」守住「共享小法庭」的可执行规格，尤其防止 「HREC_BOOTSTRAP」、「HEARING_STATE」、「WORKFLOW」、「C0_COURT_BOOTSTRAP」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static HearingRecordEntity bootstrapSnapshot() {
        return HearingRecordEntity.record(
                "HREC_BOOTSTRAP",
                CASE_ID,
                "HEARING_STATE",
                "WORKFLOW",
                "C0_COURT_BOOTSTRAP",
                1,
                "BOOTSTRAP_DOSSIER_SNAPSHOT",
                "{}",
                "{\"schema_version\":\"hearing_bootstrap_dossier.v1\"}",
                "{}",
                "hearing-bootstrap-v1",
                "deterministic",
                null,
                null,
                "system");
    }
}
