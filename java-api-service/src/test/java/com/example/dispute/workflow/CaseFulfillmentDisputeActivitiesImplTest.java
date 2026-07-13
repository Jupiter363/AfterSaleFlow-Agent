/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证案件履约争议，覆盖 「finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.hearing.application.ActiveCourtroomContextAssembler;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.application.HearingAgentClient;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「CaseFulfillmentDisputeActivitiesImplTest」。
// 类型职责：集中验证案件履约争议的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class CaseFulfillmentDisputeActivitiesImplTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceItemRepository evidenceRepository;
    @Mock private PolicyRuleRepository policyRepository;
    @Mock private HearingStateRepository stateRepository;
    @Mock private HearingRecordRepository recordRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private AgentRunRepository agentRunRepository;
    @Mock private PartySubmissionRepository submissionRepository;
    @Mock private ActiveCourtroomContextAssembler courtroomContextAssembler;
    @Mock private HearingAgentClient agentClient;
    @Mock private RemedyApplicationService remedyService;
    @Mock private ReviewApplicationService reviewService;
    @Mock private CaseLifecycleNotificationService lifecycleNotifications;
    @Mock private ToolExecutorService toolExecutorService;
    @Mock private CaseClosureService closureService;
    @Mock private AuditRecorder auditRecorder;
    @Mock private ObjectMapper objectMapper;
    @Mock private TransactionTemplate transactions;

    @InjectMocks private CaseFulfillmentDisputeActivitiesImpl activities;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「CaseFulfillmentDisputeActivitiesImplTest.finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed()」。
    // 具体功能：「CaseFulfillmentDisputeActivitiesImplTest.finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed()」：复现“核对完整业务行为（场景方法「finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed」）”场景：驱动 「activities.analyzeHearing」、「hasMessageContaining」、「isInstanceOf」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_ZERO_ROUND」、「WORKFLOW_ZERO_ROUND」。
    // 上游调用：「CaseFulfillmentDisputeActivitiesImplTest.finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「CaseFulfillmentDisputeActivitiesImplTest.finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「CaseFulfillmentDisputeActivitiesImplTest.finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_ZERO_ROUND」、「WORKFLOW_ZERO_ROUND」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed() {
        HearingAnalysisActivityCommand command =
                new HearingAnalysisActivityCommand(
                        "CASE_ZERO_ROUND",
                        "WORKFLOW_ZERO_ROUND",
                        0,
                        true,
                        false,
                        true,
                        3);

        assertThatThrownBy(() -> activities.analyzeHearing(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "final convergence requires sealed hearing rounds and formal jury review report");
    }
}
