/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证庭审PersistenceIntegration，覆盖 「persistsStateAppendOnlyRecordsDraftAndPartySubmission」、「activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」、「hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft」、「finalConvergenceAgentFailureDoesNotProduceADeterministicPlan」、「finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.hearing.application.ActiveCourtroomContextAssembler;
import com.example.dispute.hearing.application.AgentA2AMessageService;
import com.example.dispute.hearing.application.HearingCourtOrchestrator;
import com.example.dispute.hearing.domain.HearingRoundSubmissionSource;
import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.domain.HearingStopReason;
import com.example.dispute.hearing.infrastructure.persistence.entity.AgentA2AMessageEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.AgentA2AMessageRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.PartySubmissionEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.application.HearingAgentResult;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.RoomMessageEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.RoomMessageRepository;
import com.example.dispute.tool.application.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「HearingPersistenceIntegrationTest」。
// 类型职责：集中验证庭审PersistenceIntegration的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「databaseProperties」、「persistsStateAppendOnlyRecordsDraftAndPartySubmission」、「activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」、「hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft」、「finalConvergenceAgentFailureDoesNotProduceADeterministicPlan」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
class HearingPersistenceIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_hearing")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」。
    // 具体功能：「HearingPersistenceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」：作为测试辅助方法为“核对完整业务行为（场景方法「databaseProperties」）”组装或读取「POSTGRESQL.getHost」、「POSTGRESQL.getMappedPort」，供本测试类的场景方法复用。
    // 上游调用：「HearingPersistenceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「HearingPersistenceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingPersistenceIntegrationTest.databaseProperties(DynamicPropertyRegistry)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「spring.datasource.url」、「:」、「spring.datasource.username」、「dispute_test」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_hearing");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private HearingStateRepository stateRepository;
    @Autowired private HearingRecordRepository recordRepository;
    @Autowired private AdjudicationDraftRepository draftRepository;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private PartySubmissionRepository submissionRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @Autowired private PolicyRuleRepository policyRepository;
    @Autowired private EvidenceDossierRepository evidenceDossierRepository;
    @Autowired private HearingRoundRepository hearingRoundRepository;
    @Autowired private HearingRoundPartySubmissionRepository hearingRoundSubmissionRepository;
    @Autowired private AgentA2AMessageRepository agentA2AMessageRepository;
    @Autowired private CaseRoomRepository caseRoomRepository;
    @Autowired private RoomMessageRepository roomMessageRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission()」。
    // 具体功能：「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission()」：复现“持久化业务事实（场景方法「persistsStateAppendOnlyRecordsDraftAndPartySubmission」）”场景：驱动 「caseRepository.saveAndFlush」、「stateRepository.saveAndFlush」、「recordRepository.saveAndFlush」、「draftRepository.saveAndFlush」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_hearingdb」、「ORDER_hearingdb」、「user-hearing」、「merchant-hearing」。
    // 上游调用：「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingPersistenceIntegrationTest.persistsStateAppendOnlyRecordsDraftAndPartySubmission()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_hearingdb」、「ORDER_hearingdb」、「user-hearing」、「merchant-hearing」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void persistsStateAppendOnlyRecordsDraftAndPartySubmission() {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        "CASE_hearingdb",
                        "ORDER_hearingdb",
                        null,
                        "user-hearing",
                        "merchant-hearing",
                        "idem-hearing",
                        "NON_RECEIPT",
                        "delivery dispute",
                        "package marked delivered but not received",
                        RiskLevel.HIGH,
                        "user-hearing");
        disputeCase.completeIntake(
                "FULFILLMENT_CONFLICT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-hearing");
        disputeCase.markDossierBuilt("user-hearing");
        disputeCase.applyRoute(RouteType.FULL_HEARING, "user-hearing");
        disputeCase.startHearing("CASEWORKFLOW_CASE_hearingdb", "temporal-worker");
        caseRepository.saveAndFlush(disputeCase);

        HearingStateEntity state =
                stateRepository.saveAndFlush(
                        HearingStateEntity.start(
                                "HEARING_db",
                                disputeCase.getId(),
                                "CASEWORKFLOW_CASE_hearingdb",
                                "temporal-worker"));
        recordRepository.saveAndFlush(
                HearingRecordEntity.record(
                        "HREC_db",
                        disputeCase.getId(),
                        state.getId(),
                        state.getWorkflowId(),
                        "issue_framing_node",
                        0,
                        "AGENT_NODE",
                        "{}",
                        "{\"issues\":[]}",
                        "{}",
                        "hearing-v1",
                        "test-model",
                        12L,
                        42,
                        "temporal-worker"));
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_db",
                        disputeCase.getId(),
                        state.getId(),
                        1,
                        "[]",
                        "[]",
                        "[]",
                        "[\"verify delivery proof\"]",
                        "DENY_OR_REFUND_AFTER_REVIEW",
                        new BigDecimal("0.7500"),
                        "This is a non-final recommendation for human review.",
                        "python-agent-service/test-model",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
        submissionRepository.saveAndFlush(
                PartySubmissionEntity.submit(
                        "SUB_db",
                        disputeCase.getId(),
                        null,
                        "USER",
                        "user-hearing",
                        "SUPPLEMENTAL_EVIDENCE",
                        "door camera did not show delivery",
                        "{}",
                        "[]"));

        assertThat(stateRepository.findByCaseId(disputeCase.getId()))
                .hasValueSatisfying(
                        saved ->
                                assertThat(saved.getWorkflowId())
                                        .isEqualTo("CASEWORKFLOW_CASE_hearingdb"));
        assertThat(
                        recordRepository
                                .findAllByCaseIdOrderByCreatedAtAsc(disputeCase.getId()))
                .extracting(HearingRecordEntity::getNodeName)
                .containsExactly("issue_framing_node");
        assertThat(
                        draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(
                                disputeCase.getId()))
                .hasValueSatisfying(
                        draft ->
                                assertThat(draft.getDraftStatus())
                                        .isEqualTo("PENDING_HUMAN_REVIEW"));
        assertThat(
                        submissionRepository.findAllByCaseIdOrderByCreatedAtAsc(
                                disputeCase.getId()))
                .singleElement()
                .satisfies(
                        submission ->
                                assertThat(submission.getSubmittedByRole())
                                        .isEqualTo("USER"));
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft()」。
    // 具体功能：「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft()」：复现“核对完整业务行为（场景方法「activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」）”场景：驱动 「caseRepository.saveAndFlush」、「agentRunRepository.findAllByCaseIdOrderByCreatedAtAsc」、「recordRepository.findAllByCaseIdOrderByCreatedAtAsc」、「stateRepository.findByCaseId」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_activitydb」、「idem-activity」、「claims」、「issue_framing_node」。
    // 上游调用：「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_activitydb」、「idem-activity」、「claims」、「issue_framing_node」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft()
            throws Exception {
        FulfillmentCaseEntity disputeCase =
                routedCase("CASE_activitydb", "idem-activity");
        caseRepository.saveAndFlush(disputeCase);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtomicBoolean calledOutsideTransaction = new AtomicBoolean();
        var raw =
                mapper.readTree(
                        """
                        {
                          "case_id":"CASE_activitydb",
                          "workflow_id":"CASEWORKFLOW_CASE_activitydb",
                          "workflow_status":"COMPLETED",
                          "executed_nodes":["issue_framing_node","adjudication_draft_node"],
                          "issue_framing":{"neutral_summary":"dispute","issues":[]},
                          "evidence_gap":{"requires_supplemental_evidence":false,"gaps":[]},
                          "evidence_cross_check":{"findings":[]},
                          "rule_application":{"applications":[]},
                          "adjudication_draft":{"draft":{
                            "draft_status":"PENDING_HUMAN_REVIEW",
                            "recommended_outcome":"REVIEW_REFUND",
                            "reasoning_summary":"Evidence requires platform confirmation.",
                            "issue_findings":[],
                            "confidence":0.72,
                            "review_focus":["verify delivery evidence"]
                          }},
                          "manual_review_reasons":[],
                          "prompt_version":"hearing-v1",
                          "model":"test-model"
                        }
                        """);
        var activities =
                new CaseFulfillmentDisputeActivitiesImpl(
                        caseRepository,
                        evidenceRepository,
                        policyRepository,
                        stateRepository,
                        recordRepository,
                        draftRepository,
                        agentRunRepository,
                        submissionRepository,
                        activeContextAssembler(mapper),
                        (request, traceId, requestId) -> {
                            calledOutsideTransaction.set(
                                    !org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive());
                            assertThat(request.path("claims")).hasSize(1);
                            return new HearingAgentResult(
                                    raw,
                                    false,
                                    false,
                                    List.of(
                                            "issue_framing_node",
                                            "adjudication_draft_node"),
                                    "hearing-v1",
                                    "test-model");
                        },
                        org.mockito.Mockito.mock(RemedyApplicationService.class),
                        org.mockito.Mockito.mock(ReviewApplicationService.class),
                        org.mockito.Mockito.mock(
                                com.example.dispute.notification.application
                                        .CaseLifecycleNotificationService.class),
                        org.mockito.Mockito.mock(ToolExecutorService.class),
                        org.mockito.Mockito.mock(CaseClosureService.class),
                        org.mockito.Mockito.mock(AuditRecorder.class),
                        mapper,
                        new TransactionTemplate(transactionManager));
        String workflowId = "CASEWORKFLOW_CASE_activitydb";
        activities.initializeHearing(
                new CaseWorkflowInput(
                        disputeCase.getId(),
                        workflowId,
                        RouteType.FULL_HEARING,
                        Duration.ofHours(72),
                        2));
        var result =
                activities.analyzeHearing(
                        new HearingAnalysisActivityCommand(
                                disputeCase.getId(), workflowId, 0, false, false));
        activities.completeHearing(
                disputeCase.getId(), workflowId, false, false);

        assertThat(calledOutsideTransaction).isTrue();
        assertThat(result.draftId()).startsWith("DRAFT_");
        assertThat(agentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(disputeCase.getId()))
                .singleElement()
                .satisfies(
                        run -> {
                            assertThat(run.getTraceId()).isEqualTo("TRACE_" + workflowId);
                            assertThat(run.getOutputRef()).isEqualTo(result.draftId());
                        });
        assertThat(
                        recordRepository.findAllByCaseIdOrderByCreatedAtAsc(
                                disputeCase.getId()))
                .hasSize(2);
        assertThat(stateRepository.findByCaseId(disputeCase.getId()))
                .hasValueSatisfying(
                        state -> {
                            assertThat(state.getHearingStatus().name())
                                    .isEqualTo("COMPLETED");
                            assertThat(state.getCurrentNode())
                                    .isEqualTo("C6_ADJUDICATION_DRAFT");
                        });
        assertThat(caseRepository.findById(disputeCase.getId()))
                .hasValueSatisfying(
                        saved ->
                                assertThat(saved.getCaseStatus())
                                        .isEqualTo(CaseStatus.HEARING));
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan()」。
    // 具体功能：「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan()」：复现“核对完整业务行为（场景方法「finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」）”场景：驱动 「caseRepository.saveAndFlush」，再用 「assertThat」、「verifyNoInteractions」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_finalconvergence」、「idem-final-convergence」、「hearing_context」、「completed_statement_rounds」。
    // 上游调用：「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verifyNoInteractions」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_finalconvergence」、「idem-final-convergence」、「hearing_context」、「completed_statement_rounds」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan()
            throws Exception {
        FulfillmentCaseEntity disputeCase =
                routedCase("CASE_finalconvergence", "idem-final-convergence");
        caseRepository.saveAndFlush(disputeCase);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var raw =
                mapper.readTree(
                        """
                        {
                          "case_id":"CASE_finalconvergence",
                          "workflow_id":"CASEWORKFLOW_CASE_finalconvergence",
                          "workflow_status":"COMPLETED",
                          "executed_nodes":["issue_framing_node","evidence_gap_request_node","adjudication_draft_node"],
                          "issue_framing":{"neutral_summary":"dispute","issues":[]},
                          "evidence_gap":{"requires_supplemental_evidence":true,"gaps":[{"reason":"signature missing"}]},
                          "evidence_cross_check":{"findings":[]},
                          "rule_application":{"applications":[]},
                          "adjudication_draft":{"draft":{
                            "draft_status":"PENDING_HUMAN_REVIEW",
                            "recommended_outcome":"REVIEW_WITH_AVAILABLE_EVIDENCE",
                            "reasoning_summary":"Final convergence must use the available record.",
                            "issue_findings":[],
                            "confidence":0.68,
                            "review_focus":["verify forced convergence"]
                          }},
                          "manual_review_reasons":[],
                          "prompt_version":"hearing-v1",
                          "model":"test-model"
                        }
                        """);
        var lifecycleNotifications =
                org.mockito.Mockito.mock(
                        com.example.dispute.notification.application
                                .CaseLifecycleNotificationService.class);
        var activities =
                new CaseFulfillmentDisputeActivitiesImpl(
                        caseRepository,
                        evidenceRepository,
                        policyRepository,
                        stateRepository,
                        recordRepository,
                        draftRepository,
                        agentRunRepository,
                        submissionRepository,
                        activeContextAssembler(mapper),
                        (request, traceId, requestId) -> {
                            assertThat(request.path("hearing_context")
                                            .path("completed_statement_rounds")
                                            .asInt())
                                    .isEqualTo(3);
                            assertThat(request.path("hearing_context")
                                            .path("max_statement_rounds")
                                            .asInt())
                                    .isEqualTo(3);
                            assertThat(request.path("hearing_context")
                                            .path("final_convergence")
                                            .asBoolean())
                                    .isTrue();
                            assertThat(request.path("hearing_context")
                                            .path("must_produce_final_plan")
                                            .asBoolean())
                                    .isTrue();
                            assertThat(request.path("hearing_context")
                                            .path("allow_supplemental_request")
                                            .asBoolean())
                                    .isFalse();
                            var courtroomContext =
                                    request.path("hearing_context").path("courtroom_context");
                            assertThat(courtroomContext.path("evidence_dossier_ref")
                                            .path("active_version")
                                            .asInt())
                                    .isEqualTo(2);
                            assertThat(courtroomContext.path("evidence_dossier")
                                            .path("dossier_version")
                                            .asInt())
                                    .isEqualTo(2);
                            assertThat(courtroomContext.path("jury_review_report")
                                            .path("message_type")
                                            .asText())
                                    .isEqualTo("JURY_REVIEW_REPORT");
                            assertThat(request.path("hearing_context").path("sealed_rounds"))
                                    .hasSize(3);
                            assertThat(request.path("hearing_context")
                                            .path("sealed_rounds")
                                            .get(2)
                                            .path("party_submissions"))
                                    .hasSize(2);
                            assertThat(request.toString())
                                    .contains("review signature identity and notification gap")
                                    .contains("merchant maintains completed delivery");
                            return new HearingAgentResult(
                                    raw,
                                    true,
                                    false,
                                    List.of(
                                            "issue_framing_node",
                                            "evidence_gap_request_node",
                                            "adjudication_draft_node"),
                                    "hearing-v1",
                                    "test-model");
                        },
                        org.mockito.Mockito.mock(RemedyApplicationService.class),
                        org.mockito.Mockito.mock(ReviewApplicationService.class),
                        lifecycleNotifications,
                        org.mockito.Mockito.mock(ToolExecutorService.class),
                        org.mockito.Mockito.mock(CaseClosureService.class),
                        org.mockito.Mockito.mock(AuditRecorder.class),
                        mapper,
                        new TransactionTemplate(transactionManager));
        String workflowId = "CASEWORKFLOW_CASE_finalconvergence";
        activities.initializeHearing(
                new CaseWorkflowInput(
                        disputeCase.getId(),
                        workflowId,
                        RouteType.FULL_HEARING,
                        Duration.ofHours(72),
                        2));
        seedFinalCourtroomContext(disputeCase.getId(), workflowId);

        var result =
                activities.analyzeHearing(
                        new HearingAnalysisActivityCommand(
                                disputeCase.getId(),
                                workflowId,
                                3,
                                false,
                                true,
                                true,
                                3));

        assertThat(result.requiresAdditionalEvidence()).isFalse();
        assertThat(result.draftId()).startsWith("DRAFT_");
        verifyNoInteractions(lifecycleNotifications);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft()」。
    // 具体功能：「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft()」：复现“核对完整业务行为（场景方法「hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft」）”场景：驱动 「caseRepository.saveAndFlush」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」、「recordRepository.findAllByCaseIdOrderByCreatedAtAsc」、「agentRunRepository.findAllByCaseIdOrderByCreatedAtAsc」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_agentfallback」、「idem-agent-fallback」、「CASEWORKFLOW_CASE_agentfallback」。
    // 上游调用：「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_agentfallback」、「idem-agent-fallback」、「CASEWORKFLOW_CASE_agentfallback」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft() {
        FulfillmentCaseEntity disputeCase =
                routedCase("CASE_agentfallback", "idem-agent-fallback");
        caseRepository.saveAndFlush(disputeCase);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var activities =
                new CaseFulfillmentDisputeActivitiesImpl(
                        caseRepository,
                        evidenceRepository,
                        policyRepository,
                        stateRepository,
                        recordRepository,
                        draftRepository,
                        agentRunRepository,
                        submissionRepository,
                        activeContextAssembler(mapper),
                        (request, traceId, requestId) -> {
                            throw new RuntimeException("agent model service unavailable");
                        },
                        org.mockito.Mockito.mock(RemedyApplicationService.class),
                        org.mockito.Mockito.mock(ReviewApplicationService.class),
                        org.mockito.Mockito.mock(
                                com.example.dispute.notification.application
                                        .CaseLifecycleNotificationService.class),
                        org.mockito.Mockito.mock(ToolExecutorService.class),
                        org.mockito.Mockito.mock(CaseClosureService.class),
                        org.mockito.Mockito.mock(AuditRecorder.class),
                        mapper,
                        new TransactionTemplate(transactionManager));
        String workflowId = "CASEWORKFLOW_CASE_agentfallback";
        activities.initializeHearing(
                new CaseWorkflowInput(
                        disputeCase.getId(),
                        workflowId,
                        RouteType.FULL_HEARING,
                        Duration.ofHours(72),
                        2));

        assertThatThrownBy(
                        () ->
                                activities.analyzeHearing(
                                        new HearingAnalysisActivityCommand(
                                                disputeCase.getId(),
                                                workflowId,
                                                0,
                                                false,
                                                false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("agent model service unavailable");

        assertThat(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(disputeCase.getId()))
                .isEmpty();
        assertThat(recordRepository.findAllByCaseIdOrderByCreatedAtAsc(disputeCase.getId()))
                .isEmpty();
        assertThat(agentRunRepository.findAllByCaseIdOrderByCreatedAtAsc(disputeCase.getId()))
                .isEmpty();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan()」。
    // 具体功能：「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan()」：复现“核对完整业务行为（场景方法「finalConvergenceAgentFailureDoesNotProduceADeterministicPlan」）”场景：驱动 「caseRepository.saveAndFlush」、「draftRepository.findFirstByCaseIdOrderByDraftVersionDesc」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_finalfallback」、「idem-final-fallback」、「CASEWORKFLOW_CASE_finalfallback」。
    // 上游调用：「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_finalfallback」、「idem-final-fallback」、「CASEWORKFLOW_CASE_finalfallback」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void finalConvergenceAgentFailureDoesNotProduceADeterministicPlan() {
        FulfillmentCaseEntity disputeCase =
                routedCase("CASE_finalfallback", "idem-final-fallback");
        caseRepository.saveAndFlush(disputeCase);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var activities =
                new CaseFulfillmentDisputeActivitiesImpl(
                        caseRepository,
                        evidenceRepository,
                        policyRepository,
                        stateRepository,
                        recordRepository,
                        draftRepository,
                        agentRunRepository,
                        submissionRepository,
                        activeContextAssembler(mapper),
                        (request, traceId, requestId) -> {
                            throw new RuntimeException("agent model service unavailable");
                        },
                        org.mockito.Mockito.mock(RemedyApplicationService.class),
                        org.mockito.Mockito.mock(ReviewApplicationService.class),
                        org.mockito.Mockito.mock(
                                com.example.dispute.notification.application
                                        .CaseLifecycleNotificationService.class),
                        org.mockito.Mockito.mock(ToolExecutorService.class),
                        org.mockito.Mockito.mock(CaseClosureService.class),
                        org.mockito.Mockito.mock(AuditRecorder.class),
                        mapper,
                        new TransactionTemplate(transactionManager));
        String workflowId = "CASEWORKFLOW_CASE_finalfallback";
        activities.initializeHearing(
                new CaseWorkflowInput(
                        disputeCase.getId(),
                        workflowId,
                        RouteType.FULL_HEARING,
                        Duration.ofHours(72),
                        2));
        seedFinalCourtroomContext(disputeCase.getId(), workflowId);

        assertThatThrownBy(
                        () ->
                                activities.analyzeHearing(
                                        new HearingAnalysisActivityCommand(
                                                disputeCase.getId(),
                                                workflowId,
                                                3,
                                                false,
                                                true,
                                                true,
                                                3)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("agent model service unavailable");

        assertThat(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(disputeCase.getId()))
                .isEmpty();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql()」。
    // 具体功能：「HearingPersistenceIntegrationTest.finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql()」：复现“核对完整业务行为（场景方法「finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql」）”场景：驱动 「caseRepository.saveAndFlush」、「hearingRoundRepository.saveAndFlush」、「hearingRoundRepository.findFinalRoundsWithoutDraft」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_finalcursor」、「idem-final-cursor」、「HEARING_ROUND_finalcursor_3」、「2026-07-07T01:05:00Z」。
    // 上游调用：「HearingPersistenceIntegrationTest.finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingPersistenceIntegrationTest.finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingPersistenceIntegrationTest.finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_finalcursor」、「idem-final-cursor」、「HEARING_ROUND_finalcursor_3」、「2026-07-07T01:05:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql() {
        FulfillmentCaseEntity disputeCase =
                routedCase("CASE_finalcursor", "idem-final-cursor");
        caseRepository.saveAndFlush(disputeCase);
        HearingRoundEntity round =
                HearingRoundEntity.open(
                        "HEARING_ROUND_finalcursor_3",
                        disputeCase.getId(),
                        null,
                        3,
                        1,
                        Instant.parse("2026-07-07T01:05:00Z"),
                        Instant.parse("2026-07-07T01:00:00Z"),
                        "hearing-controller");
        round.complete(
                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                HearingStopReason.MAX_ROUNDS,
                Instant.parse("2026-07-07T01:04:00Z"),
                "hearing-controller");
        hearingRoundRepository.saveAndFlush(round);

        List<HearingRoundEntity> candidates =
                hearingRoundRepository.findFinalRoundsWithoutDraft(
                        3,
                        4,
                        List.of(HearingRoundStatus.COMPLETED, HearingRoundStatus.FORCED_CLOSED),
                        PageRequest.of(0, 10));

        assertThat(candidates)
                .extracting(HearingRoundEntity::getCaseId)
                .contains("CASE_finalcursor");
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists()」。
    // 具体功能：「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists()」：复现“核对完整业务行为（场景方法「juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists」）”场景：驱动 「caseRepository.saveAndFlush」、「caseRoomRepository.saveAndFlush」、「hearingRoundRepository.saveAndFlush」、「roomMessageRepository.saveAndFlush」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_jurytxrollback」、「idem-jury-tx-rollback」、「ROOM_」、「2026-07-10T08:00:00Z」。
    // 上游调用：「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「HearingPersistenceIntegrationTest.juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CASE_jurytxrollback」、「idem-jury-tx-rollback」、「ROOM_」、「2026-07-10T08:00:00Z」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void juryRepairUsesTheNextLockedRoomSequenceWhenASequenceBlockerAlreadyExists()
            throws Exception {
        String caseId = "CASE_jurytxrollback";
        FulfillmentCaseEntity disputeCase = routedCase(caseId, "idem-jury-tx-rollback");
        caseRepository.saveAndFlush(disputeCase);
        CaseRoomEntity room =
                caseRoomRepository.saveAndFlush(
                        CaseRoomEntity.open(
                                "ROOM_" + caseId,
                                caseId,
                                RoomType.HEARING,
                                OffsetDateTime.parse("2026-07-10T08:00:00Z"),
                                "hearing-controller"));
        HearingRoundEntity round =
                HearingRoundEntity.open(
                        "ROUND_" + caseId + "_3",
                        caseId,
                        null,
                        3,
                        1,
                        Instant.parse("2026-07-10T08:05:00Z"),
                        Instant.parse("2026-07-10T08:00:00Z"),
                        "hearing-controller");
        round.complete(
                "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                null,
                Instant.parse("2026-07-10T08:04:00Z"),
                "hearing-controller");
        hearingRoundRepository.saveAndFlush(round);
        roomMessageRepository.saveAndFlush(
                RoomMessageEntity.create(
                        "MESSAGE_" + caseId + "_JUDGE",
                        caseId,
                        room.getId(),
                        9,
                        MessageSenderType.AGENT,
                        "JUDGE",
                        "presiding-judge",
                        "[\"USER\",\"MERCHANT\"]",
                        "[]",
                        MessageType.AGENT_MESSAGE,
                        "第三轮法官收束已经写入。",
                        "[]",
                        "judge-round-turn:" + caseId + ":3",
                        3,
                        Instant.parse("2026-07-10T08:04:30Z"),
                        "TRACE_JURY_TX_ROLLBACK"));
        RoomMessageEntity sequenceBlocker =
                roomMessageRepository.saveAndFlush(
                        RoomMessageEntity.create(
                                "MESSAGE_" + caseId + "_BLOCKER",
                                caseId,
                                room.getId(),
                                10,
                                MessageSenderType.SYSTEM,
                                "SYSTEM",
                                "hearing-controller",
                                "[\"SYSTEM\"]",
                                "[]",
                                MessageType.SYSTEM_EVENT,
                                "{\"event\":\"sequence occupied\"}",
                                "[]",
                                "sequence-blocker:" + caseId,
                                3,
                                Instant.parse("2026-07-10T08:04:31Z"),
                                "TRACE_JURY_TX_ROLLBACK"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentA2AMessageService a2aMessageService =
                new AgentA2AMessageService(
                        agentA2AMessageRepository, mapper, Clock.systemUTC());
        HearingCourtOrchestrator orchestrator =
                new HearingCourtOrchestrator(
                        caseRepository,
                        caseRoomRepository,
                        hearingRoundRepository,
                        hearingRoundSubmissionRepository,
                        roomMessageRepository,
                        org.mockito.Mockito.mock(CaseEventService.class),
                        org.mockito.Mockito.mock(
                                com.example.dispute.hearing.application
                                        .HearingCourtAgentClient.class),
                        a2aMessageService,
                        activeContextAssembler(mapper),
                        mapper,
                        Clock.systemUTC(),
                        new PostCommitSideEffectExecutor(Runnable::run),
                        new TransactionTemplate(transactionManager));
        AtomicBoolean completionCalled = new AtomicBoolean();

        orchestrator.afterRoundClosedAfterCommit(
                caseId,
                3,
                true,
                "TRACE_JURY_TX_ROLLBACK",
                () -> completionCalled.set(true));

        assertThat(completionCalled).isTrue();
        var formalReports =
                agentA2AMessageRepository
                        .findAllByCaseIdAndToAgentAndRoundNoLessThanEqualOrderByRoundNoAscCreatedAtAsc(
                                caseId,
                                AgentA2AMessageService.PRESIDING_JUDGE,
                                3);
        assertThat(formalReports).singleElement();
        var roomReport =
                roomMessageRepository
                        .findByCaseIdAndIdempotencyKey(
                                caseId, "jury-review-report:" + caseId + ":3")
                        .orElseThrow();
        assertThat(roomReport.getSequenceNo()).isEqualTo(11);
        assertThat(mapper.readTree(roomReport.getMessageText()))
                .isEqualTo(mapper.readTree(formalReports.getFirst().getPayloadJson()));
        assertThat(roomMessageRepository.findById(sequenceBlocker.getId())).isPresent();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.activeContextAssembler(ObjectMapper)」。
    // 具体功能：「HearingPersistenceIntegrationTest.activeContextAssembler(ObjectMapper)」：作为测试辅助方法为“核对完整业务行为（场景方法「activeContextAssembler」）”组装或读取「ActiveCourtroomContextAssembler」、「AgentA2AMessageService」、「ToolRegistry」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「HearingPersistenceIntegrationTest.activeContextAssembler(ObjectMapper)」由本测试类中的 「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」、「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft」、「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan」 调用。
    // 下游影响：「HearingPersistenceIntegrationTest.activeContextAssembler(ObjectMapper)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingPersistenceIntegrationTest.activeContextAssembler(ObjectMapper)」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private ActiveCourtroomContextAssembler activeContextAssembler(ObjectMapper mapper) {
        return new ActiveCourtroomContextAssembler(
                recordRepository,
                evidenceDossierRepository,
                hearingRoundRepository,
                hearingRoundSubmissionRepository,
                new AgentA2AMessageService(
                        agentA2AMessageRepository, mapper, Clock.systemUTC()),
                new ToolRegistry(List.of()),
                mapper);
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.seedFinalCourtroomContext(String,String)」。
    // 具体功能：「HearingPersistenceIntegrationTest.seedFinalCourtroomContext(String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「seedFinalCourtroomContext」）”组装或读取「stateRepository.findByWorkflowId」、「recordRepository.saveAndFlush」、「evidenceDossierRepository.saveAndFlush」、「hearingRoundRepository.saveAndFlush」，供本测试类的场景方法复用。
    // 上游调用：「HearingPersistenceIntegrationTest.seedFinalCourtroomContext(String,String)」由本测试类中的 「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」、「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan」 调用。
    // 下游影响：「HearingPersistenceIntegrationTest.seedFinalCourtroomContext(String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingPersistenceIntegrationTest.seedFinalCourtroomContext(String,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「HREC_BOOTSTRAP_」、「C0_COURT_BOOTSTRAP」、「BOOTSTRAP_DOSSIER_SNAPSHOT」、「{}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void seedFinalCourtroomContext(String caseId, String workflowId) {
        HearingStateEntity state = stateRepository.findByWorkflowId(workflowId).orElseThrow();
        recordRepository.saveAndFlush(
                HearingRecordEntity.record(
                        "HREC_BOOTSTRAP_" + caseId,
                        caseId,
                        state.getId(),
                        workflowId,
                        "C0_COURT_BOOTSTRAP",
                        1,
                        "BOOTSTRAP_DOSSIER_SNAPSHOT",
                        "{}",
                        """
                        {
                          "schema_version":"hearing_bootstrap_dossier.v1",
                          "evidence_dossier_version":1,
                          "source_versions":{"evidence_dossier_version":1},
                          "intake_dossier":{"case_story":"Package marked delivered but user disputes receipt."},
                          "evidence_dossier":{"dossier_version":1,"fact_evidence_matrix":[]}
                        }
                        """,
                        "{}",
                        "hearing-bootstrap-v1",
                        "java-deterministic-bootstrap",
                        null,
                        null,
                        "hearing-bootstrap"));
        evidenceDossierRepository.saveAndFlush(
                EvidenceDossierEntity.frozen(
                        "EVIDENCE_DOSSIER_" + caseId + "_V2",
                        caseId,
                        2,
                        "evidence-clerk",
                        """
                        {
                          "overall_confidence_score":76,
                          "handoff_notes":"Round 2 evidence review updated the delivery proof matrix."
                        }
                        """,
                        "[]",
                        """
                        {
                          "active_version":2,
                          "updated_after_round":2,
                          "fact_evidence_matrix":[{
                            "fact_id":"FACT_RECEIPT",
                            "fact":"Whether the user actually received the package",
                            "supporting_evidence":["EVIDENCE_LOGISTICS"],
                            "opposing_evidence":["EVIDENCE_USER_NOTICE_GAP"],
                            "evidence_strength":"MEDIUM"
                          }]
                        }
                        """));

        Instant base = Instant.parse("2026-07-07T01:00:00Z");
        for (int roundNo = 1; roundNo <= 3; roundNo++) {
            HearingRoundEntity round =
                    HearingRoundEntity.open(
                            "HEARING_ROUND_" + caseId + "_" + roundNo,
                            caseId,
                            state.getId(),
                            roundNo,
                            1,
                            base.plusSeconds(roundNo * 600L),
                            base.plusSeconds(roundNo * 60L),
                            "hearing-controller");
            round.complete(
                    "{\"round\":" + roundNo + "}",
                    roundNo == 3 ? HearingStopReason.MAX_ROUNDS : null,
                    base.plusSeconds(roundNo * 120L),
                    "hearing-controller");
            hearingRoundRepository.saveAndFlush(round);
            hearingRoundSubmissionRepository.saveAndFlush(
                    HearingRoundPartySubmissionEntity.submit(
                            "HEARING_SUBMISSION_" + caseId + "_" + roundNo + "_USER",
                            caseId,
                            round.getId(),
                            roundNo,
                            ActorRole.USER,
                            "user-hearing",
                            HearingRoundSubmissionSource.PARTY_ACTION,
                            roundNo == 3
                                    ? "{\"statement\":\"review signature identity and notification gap\"}"
                                    : "{\"statement\":\"user round " + roundNo + "\"}",
                            base.plusSeconds(roundNo * 120L + 1)));
            hearingRoundSubmissionRepository.saveAndFlush(
                    HearingRoundPartySubmissionEntity.submit(
                            "HEARING_SUBMISSION_" + caseId + "_" + roundNo + "_MERCHANT",
                            caseId,
                            round.getId(),
                            roundNo,
                            ActorRole.MERCHANT,
                            "merchant-hearing",
                            HearingRoundSubmissionSource.PARTY_ACTION,
                            roundNo == 3
                                    ? "{\"statement\":\"merchant maintains completed delivery\"}"
                                    : "{\"statement\":\"merchant round " + roundNo + "\"}",
                            base.plusSeconds(roundNo * 120L + 2)));
        }
        agentA2AMessageRepository.saveAndFlush(
                AgentA2AMessageEntity.create(
                        "A2A_" + caseId + "_JURY_REPORT",
                        caseId,
                        3,
                        "JURY_PANEL",
                        AgentA2AMessageService.PRESIDING_JUDGE,
                        "JURY_REVIEW_REPORT",
                        "{\"evidence_dossier_version\":2}",
                        "{\"summary\":\"Review signature identity and notification gaps before drafting.\"}",
                        "REVIEWER_VISIBLE",
                        "RUN_JURY_" + caseId,
                        base.plusSeconds(500),
                        "jury-panel"));
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「HearingPersistenceIntegrationTest.routedCase(String,String)」。
    // 具体功能：「HearingPersistenceIntegrationTest.routedCase(String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「routedCase」）”组装或读取「FulfillmentCaseEntity.create」、「disputeCase.completeIntake」、「disputeCase.markDossierBuilt」、「disputeCase.applyRoute」，供本测试类的场景方法复用。
    // 上游调用：「HearingPersistenceIntegrationTest.routedCase(String,String)」由本测试类中的 「HearingPersistenceIntegrationTest.activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft」、「HearingPersistenceIntegrationTest.finalConvergenceRequestSuppressesSupplementalEvidenceAndRequiresPlan」、「HearingPersistenceIntegrationTest.hearingAgentFailureDoesNotPersistASyntheticManualReviewDraft」、「HearingPersistenceIntegrationTest.finalConvergenceAgentFailureDoesNotProduceADeterministicPlan」 调用。
    // 下游影响：「HearingPersistenceIntegrationTest.routedCase(String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「HearingPersistenceIntegrationTest.routedCase(String,String)」守住「Temporal 持久化编排」的可执行规格，尤其防止 「ORDER_」、「user-hearing」、「merchant-hearing」、「NON_RECEIPT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static FulfillmentCaseEntity routedCase(
            String caseId, String idempotencyKey) {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + caseId,
                        null,
                        "user-hearing",
                        "merchant-hearing",
                        idempotencyKey,
                        "NON_RECEIPT",
                        "delivery dispute",
                        "package marked delivered but not received",
                        RiskLevel.HIGH,
                        "user-hearing");
        disputeCase.completeIntake(
                "FULFILLMENT_CONFLICT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-hearing");
        disputeCase.markDossierBuilt("user-hearing");
        disputeCase.applyRoute(RouteType.FULL_HEARING, "user-hearing");
        return disputeCase;
    }
}
