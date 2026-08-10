/*
 * 所属模块：房间协作与权限。
 * 文件职责：验证接待房间，覆盖 「confirmsAdmissionWithoutLegacyConfirmationNoteInput」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dispute.common.exception.GlobalExceptionHandler;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.CommonConfiguration;
import com.example.dispute.config.HeaderAuthenticationFilter;
import com.example.dispute.config.JsonAccessDeniedHandler;
import com.example.dispute.config.JsonAuthenticationEntryPoint;
import com.example.dispute.config.SecurityConfiguration;
import com.example.dispute.config.SecurityFailureWriter;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.room.api.IntakeRoomController;
import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.room.application.IntakeConfirmationView;
import com.example.dispute.room.application.IntakeInfrastructurePreparationService;
import com.example.dispute.room.application.IntakeInfrastructurePreparationView;
import com.example.dispute.room.application.IntakeProgressService;
import com.example.dispute.room.application.IntakeRoomService;
import com.example.dispute.room.application.IntakeStatusView;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionView;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionView.VersionPins;
import com.example.dispute.workflow.activity.system.IntakeInfrastructurePreparationWorkflow;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.IntakeInfrastructurePreparationResult;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

// 所属模块：【房间协作与权限 / 自动化测试层】类型「IntakeRoomControllerTest」。
// 类型职责：集中验证接待房间的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「confirmsAdmissionWithoutLegacyConfirmationNoteInput」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@WebMvcTest(IntakeRoomController.class)
@Import({
    CommonConfiguration.class,
    TraceIdFilter.class,
    HeaderAuthenticationFilter.class,
    SecurityConfiguration.class,
    SecurityFailureWriter.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class,
    GlobalExceptionHandler.class
})
class IntakeRoomControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private IntakeRoomService service;
    @MockitoBean private IntakeProgressService progressService;
    @MockitoBean private IntakeInfrastructurePreparationService preparationService;

    @Test
    void preparationIsStrictAuthorizedRepeatSafeAndLeavesFormalOpeningUntouched()
            throws Exception {
        assertPreparationServiceIsReadOnlyStageBoundAndFailClosed();
        assertTemporalPreparationRetriesFailureAndJoinsSuccessfulIdentity();
        String key = "intake-preparation:CASE_test";
        when(preparationService.prepare(eq("CASE_test"), any(), eq(key)))
                .thenReturn(IntakeInfrastructurePreparationView.ready());
        when(preparationService.prepare(eq("CASE_legacy"), any(), eq(key)))
                .thenReturn(IntakeInfrastructurePreparationView.notRequired());
        when(preparationService.prepare(eq("CASE_forbidden"), any(), eq(key)))
                .thenThrow(new ForbiddenException("intake preparation is unavailable"));
        when(preparationService.prepare(eq("CASE_unavailable"), any(), eq(key)))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                                "intake infrastructure preparation is unavailable",
                                Map.of(
                                        "reason_code",
                                        IntakeInfrastructurePreparationService
                                                .REASON_UNAVAILABLE)));

        mockMvc.perform(
                        post("/api/disputes/CASE_test/intake/preparation")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schema_version")
                        .value("intake-infrastructure-preparation.v1"))
                .andExpect(jsonPath("$.data.status").value("READY"));

        mockMvc.perform(
                        post("/api/disputes/CASE_legacy/intake/preparation")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_REQUIRED"));

        mockMvc.perform(
                        post("/api/disputes/CASE_forbidden/intake/preparation")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .header("Idempotency-Key", key))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/disputes/CASE_unavailable/intake/preparation")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .header("Idempotency-Key", key))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.reason_code")
                        .value("INTAKE_INFRASTRUCTURE_PREPARATION_UNAVAILABLE"));

        mockMvc.perform(
                        post("/api/disputes/CASE_test/intake/preparation")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .header("Idempotency-Key", "short"))
                .andExpect(status().isBadRequest());

        verify(preparationService).prepare(eq("CASE_test"), any(), eq(key));
        verifyNoInteractions(service);
    }

    private static void assertPreparationServiceIsReadOnlyStageBoundAndFailClosed() {
        String key = "intake-preparation:CASE_test";
        AuthenticatedActor merchant = new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        IntakeProgressService progress = org.mockito.Mockito.mock(IntakeProgressService.class);
        PlatformTransactionManager transactions =
                org.mockito.Mockito.mock(PlatformTransactionManager.class);
        IntakeInfrastructurePreparationService.TargetPreparation target =
                org.mockito.Mockito.mock(
                        IntakeInfrastructurePreparationService.TargetPreparation.class);
        AtomicReference<TransactionDefinition> definition = new AtomicReference<>();
        AtomicBoolean transactionActive = new AtomicBoolean();
        AtomicLong nowNanos = new AtomicLong();
        AtomicInteger pauses = new AtomicInteger();
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            assertThat(transactionActive.compareAndSet(false, true)).isTrue();
            definition.set(invocation.getArgument(0));
            return new SimpleTransactionStatus();
        });
        org.mockito.Mockito.doAnswer(invocation -> {
                    assertThat(transactionActive.compareAndSet(true, false)).isTrue();
                    return null;
                })
                .when(transactions)
                .commit(any());
        org.mockito.Mockito.doAnswer(invocation -> {
                    assertThat(transactionActive.compareAndSet(true, false)).isTrue();
                    return null;
                })
                .when(transactions)
                .rollback(any());
        IntakeInfrastructurePreparationService application =
                new IntakeInfrastructurePreparationService(
                        progress,
                        transactions,
                        List.of(target),
                        java.time.Duration.ofMillis(100),
                        java.time.Duration.ofMillis(10),
                        nowNanos::get,
                        delay -> {
                            assertThat(transactionActive).isFalse();
                            pauses.incrementAndGet();
                            nowNanos.addAndGet(delay.toNanos());
                        });

        when(progress.status("CASE_legacy", merchant))
                .thenAnswer(invocation -> {
                    assertThat(transactionActive).isTrue();
                    return preparationStatus(true, "CURRENT", "LEGACY");
                });
        assertThat(application.prepare("CASE_legacy", merchant, key))
                .isEqualTo(IntakeInfrastructurePreparationView.notRequired());
        verifyNoInteractions(target);
        assertThat(definition.get().isReadOnly()).isTrue();
        assertThat(definition.get().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        AtomicInteger targetProjectionReads = new AtomicInteger();
        when(progress.status("CASE_target", merchant))
                .thenAnswer(invocation -> {
                    assertThat(transactionActive).isTrue();
                    return targetProjectionReads.getAndIncrement() == 0
                            ? preparationStatus(true, "PROCESSING", "UNKNOWN")
                            : preparationStatus(true, "CURRENT", "TEMPORAL");
                });
        AtomicReference<java.time.Duration> remoteBudget = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
                    assertThat(transactionActive).isFalse();
                    remoteBudget.set(invocation.getArgument(1));
                    return null;
                })
                .when(target)
                .prepare(eq(key), any(java.time.Duration.class));
        assertThat(application.prepare("CASE_target", merchant, key))
                .isEqualTo(IntakeInfrastructurePreparationView.ready());
        assertThat(targetProjectionReads).hasValue(2);
        assertThat(pauses).hasValue(1);
        assertThat(remoteBudget).hasValue(java.time.Duration.ofMillis(90));
        verify(target).prepare(key, java.time.Duration.ofMillis(90));

        when(progress.status("CASE_wrong_stage", merchant))
                .thenAnswer(invocation -> {
                    assertThat(transactionActive).isTrue();
                    return preparationStatus(false, "PROCESSING", "UNKNOWN");
                });
        int pausesBeforeForbidden = pauses.get();
        assertThatThrownBy(() -> application.prepare("CASE_wrong_stage", merchant, key))
                .isInstanceOf(ForbiddenException.class);
        assertThat(pauses).hasValue(pausesBeforeForbidden);
        verify(progress).status("CASE_wrong_stage", merchant);
        assertThatThrownBy(() -> application.prepare(
                        "CASE_target",
                        new AuthenticatedActor("system", ActorRole.SYSTEM),
                        key))
                .isInstanceOf(ForbiddenException.class);

        org.mockito.Mockito.doThrow(new IllegalStateException("private transport detail"))
                .when(target)
                .prepare(
                        eq("intake-preparation:CASE_failure"),
                        any(java.time.Duration.class));
        assertThatThrownBy(() -> application.prepare(
                        "CASE_target", merchant, "intake-preparation:CASE_failure"))
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> {
                    BusinessException business = (BusinessException) failure;
                    assertThat(business.errorCode())
                            .isEqualTo(ErrorCode.AGENT_SERVICE_UNAVAILABLE);
                    assertThat(business.details())
                            .containsEntry(
                                    "reason_code",
                                    IntakeInfrastructurePreparationService.REASON_UNAVAILABLE);
                });

        AtomicInteger timeoutReads = new AtomicInteger();
        when(progress.status("CASE_timeout", merchant))
                .thenAnswer(invocation -> {
                    assertThat(transactionActive).isTrue();
                    timeoutReads.incrementAndGet();
                    return preparationStatus(true, "PROCESSING", "UNKNOWN");
                });
        int pausesBeforeTimeout = pauses.get();
        assertThatThrownBy(() -> application.prepare(
                        "CASE_timeout", merchant, "intake-preparation:CASE_timeout"))
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure).details())
                        .containsEntry(
                                "reason_code",
                                IntakeInfrastructurePreparationService.REASON_UNAVAILABLE));
        assertThat(timeoutReads).hasValue(10);
        assertThat(pauses.get() - pausesBeforeTimeout).isEqualTo(10);
        verify(target, org.mockito.Mockito.never())
                .prepare(
                        eq("intake-preparation:CASE_timeout"),
                        any(java.time.Duration.class));
        assertThat(transactionActive).isFalse();
    }

    private static IntakeStatusView preparationStatus(
            boolean canUseIntake, String projectionState, String writerMode) {
        return new IntakeStatusView(
                "CASE_test",
                ActorRole.USER,
                ActorRole.MERCHANT,
                "OPEN",
                "OPEN",
                false,
                canUseIntake,
                false,
                null,
                new IntakeProcessProjectionView(
                        "intake-process-projection.v1",
                        projectionState,
                        writerMode,
                        1,
                        1,
                        1,
                        1,
                        "INTAKE",
                        "NONE",
                        "READY",
                        null,
                        null,
                        null,
                        null,
                        VersionPins.unavailable(),
                        null));
    }

    private static void assertTemporalPreparationRetriesFailureAndJoinsSuccessfulIdentity()
            throws Exception {
        assertFailedExecutionCanRetryWithTheSameStableIdentity();
        assertRunningAndSuccessfulExecutionJoinTheSameStableIdentity();
    }

    private static void assertFailedExecutionCanRetryWithTheSameStableIdentity()
            throws Exception {
        java.time.Duration remainingBudget = java.time.Duration.ofSeconds(17);
        WorkflowClient client = org.mockito.Mockito.mock(WorkflowClient.class);
        IntakeInfrastructurePreparationWorkflow workflow =
                org.mockito.Mockito.mock(IntakeInfrastructurePreparationWorkflow.class);
        WorkflowStub execution = org.mockito.Mockito.mock(WorkflowStub.class);
        List<WorkflowOptions> options = new java.util.ArrayList<>();
        AtomicInteger acceptedStarts = new AtomicInteger();
        when(client.newWorkflowStub(
                        eq(IntakeInfrastructurePreparationWorkflow.class),
                        any(WorkflowOptions.class)))
                .thenAnswer(invocation -> {
                    options.add(invocation.getArgument(1));
                    return workflow;
                });
        when(execution.getResult(
                        org.mockito.ArgumentMatchers.anyLong(),
                        eq(TimeUnit.NANOSECONDS),
                        eq(IntakeInfrastructurePreparationResult.class)))
                .thenThrow(new IllegalStateException("prior execution failed"))
                .thenReturn(IntakeInfrastructurePreparationResult.ready());
        try (MockedStatic<WorkflowStub> workflowStubs =
                        org.mockito.Mockito.mockStatic(WorkflowStub.class);
                MockedStatic<WorkflowClient> workflowStarts =
                        org.mockito.Mockito.mockStatic(
                                WorkflowClient.class,
                                invocation -> {
                                    if (invocation.getMethod().getName().equals("start")) {
                                        int accepted = acceptedStarts.incrementAndGet();
                                        return WorkflowExecution.newBuilder()
                                                .setWorkflowId("failed-retry")
                                                .setRunId("run-" + accepted)
                                                .build();
                                    }
                                    return null;
                                })) {
            workflowStubs.when(() -> WorkflowStub.fromTyped(workflow)).thenReturn(execution);
            IntakeInfrastructurePreparationService.TargetPreparation preparation =
                    IntakeInfrastructurePreparationService.temporal(client);

            assertThatThrownBy(
                            () -> preparation.prepare(
                                    "intake-preparation:retry", remainingBudget))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("prior execution failed");
            preparation.prepare("intake-preparation:retry", remainingBudget);
        }

        assertThat(acceptedStarts).hasValue(2);
        verify(execution, org.mockito.Mockito.times(2))
                .getResult(
                        org.mockito.ArgumentMatchers.longThat(
                                timeout -> timeout > 0
                                        && timeout <= remainingBudget.toNanos()),
                        eq(TimeUnit.NANOSECONDS),
                        eq(IntakeInfrastructurePreparationResult.class));
        assertStableFailedOnlyOptions(options, remainingBudget);
    }

    private static void assertRunningAndSuccessfulExecutionJoinTheSameStableIdentity()
            throws Exception {
        java.time.Duration remainingBudget = java.time.Duration.ofSeconds(17);
        WorkflowClient client = org.mockito.Mockito.mock(WorkflowClient.class);
        IntakeInfrastructurePreparationWorkflow workflow =
                org.mockito.Mockito.mock(IntakeInfrastructurePreparationWorkflow.class);
        WorkflowStub started = org.mockito.Mockito.mock(WorkflowStub.class);
        WorkflowStub existing = org.mockito.Mockito.mock(WorkflowStub.class);
        WorkflowExecution execution = WorkflowExecution.newBuilder()
                .setWorkflowId("joined")
                .setRunId("run-1")
                .build();
        List<WorkflowOptions> options = new java.util.ArrayList<>();
        AtomicInteger startAttempts = new AtomicInteger();
        AtomicInteger acceptedStarts = new AtomicInteger();
        when(client.newWorkflowStub(
                        eq(IntakeInfrastructurePreparationWorkflow.class),
                        any(WorkflowOptions.class)))
                .thenAnswer(invocation -> {
                    options.add(invocation.getArgument(1));
                    return workflow;
                });
        when(client.newUntypedWorkflowStub(anyString())).thenReturn(existing);
        when(started.getResult(
                        org.mockito.ArgumentMatchers.anyLong(),
                        eq(TimeUnit.NANOSECONDS),
                        eq(IntakeInfrastructurePreparationResult.class)))
                .thenReturn(IntakeInfrastructurePreparationResult.ready());
        when(existing.getResult(
                        org.mockito.ArgumentMatchers.anyLong(),
                        eq(TimeUnit.NANOSECONDS),
                        eq(IntakeInfrastructurePreparationResult.class)))
                .thenReturn(IntakeInfrastructurePreparationResult.ready());
        try (MockedStatic<WorkflowStub> workflowStubs =
                        org.mockito.Mockito.mockStatic(WorkflowStub.class);
                MockedStatic<WorkflowClient> workflowStarts =
                        org.mockito.Mockito.mockStatic(
                                WorkflowClient.class,
                                invocation -> {
                                    if (!invocation.getMethod().getName().equals("start")) {
                                        return null;
                                    }
                                    if (startAttempts.incrementAndGet() == 1) {
                                        acceptedStarts.incrementAndGet();
                                        return execution;
                                    }
                                    throw new WorkflowExecutionAlreadyStarted(
                                            execution,
                                            IntakeInfrastructurePreparationWorkflow.WORKFLOW_TYPE,
                                            null);
                                })) {
            workflowStubs.when(() -> WorkflowStub.fromTyped(workflow)).thenReturn(started);
            IntakeInfrastructurePreparationService.TargetPreparation preparation =
                    IntakeInfrastructurePreparationService.temporal(client);

            preparation.prepare("intake-preparation:join", remainingBudget);
            preparation.prepare("intake-preparation:join", remainingBudget);
            preparation.prepare("intake-preparation:join", remainingBudget);
        }

        assertThat(startAttempts).hasValue(3);
        assertThat(acceptedStarts).hasValue(1);
        verify(existing, org.mockito.Mockito.times(2))
                .getResult(
                        org.mockito.ArgumentMatchers.longThat(
                                timeout -> timeout > 0
                                        && timeout <= remainingBudget.toNanos()),
                        eq(TimeUnit.NANOSECONDS),
                        eq(IntakeInfrastructurePreparationResult.class));
        verify(started)
                .getResult(
                        org.mockito.ArgumentMatchers.longThat(
                                timeout -> timeout > 0
                                        && timeout <= remainingBudget.toNanos()),
                        eq(TimeUnit.NANOSECONDS),
                        eq(IntakeInfrastructurePreparationResult.class));
        assertStableFailedOnlyOptions(options, remainingBudget);
    }

    private static void assertStableFailedOnlyOptions(
            List<WorkflowOptions> options, java.time.Duration remainingBudget) {
        assertThat(options).hasSizeGreaterThanOrEqualTo(2);
        assertThat(options)
                .extracting(WorkflowOptions::getWorkflowId)
                .containsOnly(options.getFirst().getWorkflowId());
        assertThat(options)
                .extracting(WorkflowOptions::getWorkflowIdReusePolicy)
                .containsOnly(
                        WorkflowIdReusePolicy
                                .WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY);
        assertThat(options)
                .extracting(WorkflowOptions::getTaskQueue)
                .containsOnly("agent-execution");
        assertThat(options)
                .extracting(WorkflowOptions::getWorkflowExecutionTimeout)
                .containsOnly(remainingBudget);
    }

    @Test
    void statusAddsSanitizedVersionedProjectionWithoutChangingLegacyFields() throws Exception {
        when(progressService.status(eq("CASE_test"), any()))
                .thenReturn(
                        new IntakeStatusView(
                                "CASE_test",
                                ActorRole.USER,
                                ActorRole.MERCHANT,
                                "COMPLETED",
                                "OPEN",
                                false,
                                true,
                                false,
                                OffsetDateTime.parse("2026-07-22T06:00:00Z"),
                                new IntakeProcessProjectionView(
                                        "intake-process-projection.v1",
                                        "CURRENT",
                                        "SHADOW",
                                        4,
                                        12,
                                        7,
                                        9,
                                        "WAITING_PARTY",
                                        "WAITING_PARTY",
                                        "run-1",
                                        "attempt-2",
                                        "RUNNING",
                                        "v2:attempt-2:6",
                                        new VersionPins(
                                                "case-process.v2",
                                                "room-epoch-selection.v2",
                                                "agent-stream.v2",
                                                "case-build-1",
                                                "intake-build-1",
                                                "2.0.0",
                                                "checkpoint.v2"),
                                        OffsetDateTime.parse("2026-07-22T03:04:05Z"))));

        mockMvc.perform(
                        get("/api/disputes/CASE_test/intake/status")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initiator_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.respondent_status").value("OPEN"))
                .andExpect(jsonPath("$.data.can_use_intake").value(true))
                .andExpect(jsonPath("$.data.process_projection.schema_version")
                        .value("intake-process-projection.v1"))
                .andExpect(jsonPath("$.data.process_projection.writer_mode").value("SHADOW"))
                .andExpect(jsonPath("$.data.process_projection.room_revision").value(7))
                .andExpect(jsonPath("$.data.process_projection.active_logical_run_id")
                        .value("run-1"))
                .andExpect(jsonPath("$.data.process_projection.stream_cursor")
                        .value("v2:attempt-2:6"))
                .andExpect(jsonPath("$.data.process_projection.workflow_id").doesNotExist())
                .andExpect(jsonPath("$.data.process_projection.projection_ref").doesNotExist())
                .andExpect(jsonPath("$.data.process_projection.internal_hash").doesNotExist());
    }

    // 所属模块：【房间协作与权限 / 自动化测试层】「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」。
    // 具体功能：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」：复现“核对完整业务行为（场景方法「confirmsAdmissionWithoutLegacyConfirmationNoteInput」）”场景：驱动 「service.confirm」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_test」、「2026-07-06T02:00:00Z」、「merchant-local」、「MERCHANT」。
    // 上游调用：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「IntakeRoomControllerTest.confirmsAdmissionWithoutLegacyConfirmationNoteInput()」守住「房间协作与权限」的可执行规格，尤其防止 「CASE_test」、「2026-07-06T02:00:00Z」、「merchant-local」、「MERCHANT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void confirmsAdmissionWithoutLegacyConfirmationNoteInput() throws Exception {
        when(service.confirm(eq("CASE_test"), any(), any(), anyString(), anyString()))
                .thenReturn(
                        new IntakeConfirmationView(
                                "CASE_test",
                                CaseStatus.EVIDENCE_OPEN,
                                RoomType.EVIDENCE,
                                OffsetDateTime.parse("2026-07-06T02:00:00Z")));

        mockMvc.perform(
                        post("/api/disputes/CASE_test/intake/confirm")
                                .header(HeaderAuthenticationFilter.USER_ID_HEADER, "merchant-local")
                                .header(HeaderAuthenticationFilter.ROLE_HEADER, "MERCHANT")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "admissible": true,
                                          "dispute_type": "WATCH_ACCURACY",
                                          "risk_level": "MEDIUM"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("EVIDENCE_OPEN"))
                .andExpect(jsonPath("$.data.current_room").value("EVIDENCE"));

        ArgumentCaptor<IntakeConfirmationCommand> command =
                ArgumentCaptor.forClass(IntakeConfirmationCommand.class);
        verify(service).confirm(eq("CASE_test"), any(), command.capture(), anyString(), anyString());
        assertThat(command.getValue().confirmationNote()).isNull();
    }
}
