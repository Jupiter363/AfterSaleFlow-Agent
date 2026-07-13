/*
 * 所属模块：Agent 流式运行。
 * 文件职责：验证Agent运行生命周期，覆盖 「claimMovesPendingRunToRunningAndReturnsExecutionContract」、「legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText」、「nonPublicVisibleDeltaIsRejectedBeforePersistence」、「completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection」、「visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized」、「agentFailureUsesTheSameSanitizedPublicErrorContract」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunFinalizerRegistry;
import com.example.dispute.agentstream.application.AgentRunLifecycleService;
import com.example.dispute.agentstream.application.AgentRunResultPolicy;
import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.application.AgentStreamFrame;
import com.example.dispute.agentstream.application.AgentStreamOperationRegistry;
import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【Agent 流式运行 / 自动化测试层】类型「AgentRunLifecycleServiceTest」。
// 类型职责：集中验证Agent运行生命周期的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「claimMovesPendingRunToRunningAndReturnsExecutionContract」、「legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText」、「nonPublicVisibleDeltaIsRejectedBeforePersistence」、「completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection」、「visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class AgentRunLifecycleServiceTest {

    private static final String RUN_ID = "AGENT_RUN_1";

    @Mock private AgentRunRepository runRepository;
    @Mock private AgentRunStreamEventService eventService;
    @Mock private AgentRunFinalizerRegistry finalizerRegistry;

    private ObjectMapper objectMapper;
    private AgentStreamOperationRegistry operationRegistry;
    private AgentRunLifecycleService lifecycleService;

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.setUp()」。
    // 具体功能：「AgentRunLifecycleServiceTest.setUp()」：在每个测试场景运行前创建测试对象和内存夹具，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「AgentRunLifecycleServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「AgentRunLifecycleServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunLifecycleServiceTest.setUp()」守住「Agent 流式运行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        operationRegistry = new AgentStreamOperationRegistry();
        lifecycleService =
                new AgentRunLifecycleService(
                        runRepository,
                        eventService,
                        operationRegistry,
                        new AgentRunResultPolicy(objectMapper, operationRegistry),
                        finalizerRegistry,
                        objectMapper);
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract()」。
    // 具体功能：「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract()」：复现“核对完整业务行为（场景方法「claimMovesPendingRunToRunningAndReturnsExecutionContract」）”场景：驱动 「runRepository.findByIdForUpdate」、「lifecycleService.claim」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「RUNNING」、「intake_turn」、「room_utterance」。
    // 上游调用：「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract()」守住「Agent 流式运行」的可执行规格，尤其防止 「RUNNING」、「intake_turn」、「room_utterance」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    @Test
    void claimMovesPendingRunToRunningAndReturnsExecutionContract() {
        AgentRunEntity run = pendingIntakeRun();
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        var descriptor = lifecycleService.claim(RUN_ID).orElseThrow();

        assertThat(run.getRunStatus()).isEqualTo("RUNNING");
        assertThat(descriptor.runId()).isEqualTo(RUN_ID);
        assertThat(descriptor.protocolOperation()).isEqualTo("intake_turn");
        assertThat(descriptor.endpoint())
                .isEqualTo("/internal/agents/intake/turn/stream");
        assertThat(descriptor.visibleFieldPaths())
                .contains("room_utterance", "case_detail.case_story", "case_detail.intake_quality");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText()」。
    // 具体功能：「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText()」：复现“核对完整业务行为（场景方法「legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText」）”场景：驱动 「runRepository.findByIdForUpdate」、「lifecycleService.recordNonTerminalFrame」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「room_utterance」、「正在整理案情」、「visible_delta」、「field」。
    // 上游调用：「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText()」守住「Agent 流式运行」的可执行规格，尤其防止 「room_utterance」、「正在整理案情」、「visible_delta」、「field」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText() {
        AgentRunEntity run = runningIntakeRun();
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        AgentStreamFrame frame = visibleDelta("room_utterance", "正在整理案情");

        lifecycleService.recordNonTerminalFrame(RUN_ID, frame);

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(eventService).append(eq(run), eq(1L), eq("visible_delta"), payload.capture());
        assertThat(payload.getValue().path("field").asText())
                .isEqualTo("room_utterance");
        assertThat(payload.getValue().path("delta").asText()).isEqualTo("正在整理案情");
        assertThat(payload.getValue().toString())
                .doesNotContain("reasoning", "prompt", "memory_patch", "internal");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence()」。
    // 具体功能：「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence()」：复现“核对完整业务行为（场景方法「nonPublicVisibleDeltaIsRejectedBeforePersistence」）”场景：驱动 「runRepository.findByIdForUpdate」、「lifecycleService.recordNonTerminalFrame」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「reasoning_content」。
    // 上游调用：「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence()」守住「Agent 流式运行」的可执行规格，尤其防止 「reasoning_content」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void nonPublicVisibleDeltaIsRejectedBeforePersistence() {
        AgentRunEntity run = runningIntakeRun();
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(
                        () ->
                                lifecycleService.recordNonTerminalFrame(
                                        RUN_ID,
                                        visibleDelta(
                                                "reasoning_content",
                                                "private chain of thought")))
                .isInstanceOf(AgentStreamProtocolException.class)
                .hasMessageContaining("non-public field");

        verify(eventService, never()).append(any(), any(Long.class), any(), any());
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection()」。
    // 具体功能：「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection()」：复现“核对完整业务行为（场景方法「completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection」）”场景：驱动 「runRepository.findByIdForUpdate」、「runRepository.findById」、「lifecycleService.complete」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「RUNNING」、「COMPLETED」、「final」、「qwen3.7-plus」。
    // 上游调用：「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection()」守住「Agent 流式运行」的可执行规格，尤其防止 「RUNNING」、「COMPLETED」、「final」、「qwen3.7-plus」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection()
            throws Exception {
        AgentRunEntity run = runningIntakeRun();
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        JsonNode result =
                objectMapper.readTree(
                        """
                        {
                          "room_utterance":"案情已更新",
                          "scroll_snapshot":{"internal_fact":"secret"},
                          "canvas_operations":[],
                          "reasoning_content":"private reasoning",
                          "internal_matrix_patch":{"fact":"secret"}
                        }
                        """);
        AgentStreamFrame frame = finalFrame(result);

        doAnswer(
                        invocation -> {
                            assertThat(run.getRunStatus()).isEqualTo("RUNNING");
                            return null;
                        })
                .when(finalizerRegistry)
                .finalizeResult(any(), eq(result));
        doAnswer(
                        invocation -> {
                            assertThat(run.getRunStatus()).isEqualTo("COMPLETED");
                            return null;
                        })
                .when(eventService)
                .append(eq(run), eq(2L), eq("final"), any());

        lifecycleService.complete(RUN_ID, frame, 123, "qwen3.7-plus", 456L);

        InOrder order = inOrder(finalizerRegistry, eventService);
        order.verify(finalizerRegistry).finalizeResult(any(), eq(result));
        ArgumentCaptor<JsonNode> publicPayload = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(eventService).append(eq(run), eq(2L), eq("final"), publicPayload.capture());
        assertThat(run.getRunStatus()).isEqualTo("COMPLETED");
        assertThat(run.getStreamResultJson())
                .contains("scroll_snapshot", "reasoning_content", "internal_matrix_patch");
        assertThat(publicPayload.getValue().path("response").toString())
                .isEqualTo("{\"room_utterance\":\"案情已更新\"}");
        assertThat(publicPayload.getValue().toString())
                .doesNotContain(
                        "internal_fact",
                        "reasoning_content",
                        "private reasoning",
                        "internal_matrix_patch",
                        "secret");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized()」。
    // 具体功能：「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized()」：复现“核对完整业务行为（场景方法「visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized」）”场景：驱动 「runRepository.findByIdForUpdate」、「eventService.hasVisibleOutput」、「eventService.nextSequence」、「lifecycleService.failInfrastructure」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「UPSTREAM_502」、「FAILED」、「error」、「code」。
    // 上游调用：「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized()」守住「Agent 流式运行」的可执行规格，尤其防止 「UPSTREAM_502」、「FAILED」、「error」、「code」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized() {
        AgentRunEntity run = runningIntakeRun();
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(eventService.hasVisibleOutput(RUN_ID)).thenReturn(true);
        when(eventService.nextSequence(RUN_ID)).thenReturn(7L);

        lifecycleService.failInfrastructure(
                RUN_ID,
                "UPSTREAM_502",
                "secret stack trace: sk-private-key",
                true,
                false,
                900L);

        assertThat(run.getRunStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorRetryable()).isFalse();
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(eventService).append(eq(run), eq(7L), eq("error"), payload.capture());
        assertThat(payload.getValue().path("code").asText()).isEqualTo("UPSTREAM_502");
        assertThat(payload.getValue().path("retryable").asBoolean()).isFalse();
        assertThat(payload.getValue().path("visible_output_emitted").asBoolean()).isTrue();
        assertThat(payload.getValue().path("message").asText())
                .isEqualTo("数字人响应生成失败，请稍后重试。");
        assertThat(payload.getValue().toString())
                .doesNotContain("secret stack trace", "sk-private-key");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.agentFailureUsesTheSameSanitizedPublicErrorContract()」。
    // 具体功能：「AgentRunLifecycleServiceTest.agentFailureUsesTheSameSanitizedPublicErrorContract()」：复现“核对完整业务行为（场景方法「agentFailureUsesTheSameSanitizedPublicErrorContract」）”场景：驱动 「runRepository.findByIdForUpdate」、「eventService.hasVisibleOutput」、「lifecycleService.failFromAgent」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「error」、「respond」、「MODEL_FAILURE」、「FAILED」。
    // 上游调用：「AgentRunLifecycleServiceTest.agentFailureUsesTheSameSanitizedPublicErrorContract()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunLifecycleServiceTest.agentFailureUsesTheSameSanitizedPublicErrorContract()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunLifecycleServiceTest.agentFailureUsesTheSameSanitizedPublicErrorContract()」守住「Agent 流式运行」的可执行规格，尤其防止 「error」、「respond」、「MODEL_FAILURE」、「FAILED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void agentFailureUsesTheSameSanitizedPublicErrorContract() {
        AgentRunEntity run = runningIntakeRun();
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(eventService.hasVisibleOutput(RUN_ID)).thenReturn(true);
        AgentStreamFrame frame =
                new AgentStreamFrame(
                        9L,
                        "error",
                        "respond",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new AgentStreamFrame.StreamError(
                                "MODEL_FAILURE",
                                "provider leaked secret: sk-private-key",
                                true,
                                false));

        lifecycleService.failFromAgent(RUN_ID, frame, 901L);

        assertThat(run.getRunStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorRetryable()).isFalse();
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(eventService).append(eq(run), eq(9L), eq("error"), payload.capture());
        assertThat(payload.getValue().path("message").asText())
                .isEqualTo("数字人响应生成失败，请稍后重试。");
        assertThat(payload.getValue().path("retryable").asBoolean()).isFalse();
        assertThat(payload.getValue().path("visible_output_emitted").asBoolean()).isTrue();
        assertThat(payload.getValue().toString())
                .doesNotContain("provider leaked secret", "sk-private-key");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag()」。
    // 具体功能：「AgentRunLifecycleServiceTest.agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag()」：复现“核对完整业务行为（场景方法「agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag」）”场景：驱动 「runRepository.findByIdForUpdate」、「eventService.hasVisibleOutput」、「lifecycleService.failFromAgent」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「error」、「turn_audit」、「AGENT_SERVICE_UNAVAILABLE」、「FAILED」。
    // 上游调用：「AgentRunLifecycleServiceTest.agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunLifecycleServiceTest.agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunLifecycleServiceTest.agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag()」守住「Agent 流式运行」的可执行规格，尤其防止 「error」、「turn_audit」、「AGENT_SERVICE_UNAVAILABLE」、「FAILED」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag() {
        AgentRunEntity run = runningIntakeRun();
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(eventService.hasVisibleOutput(RUN_ID)).thenReturn(false);
        AgentStreamFrame frame =
                new AgentStreamFrame(
                        3L,
                        "error",
                        "turn_audit",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new AgentStreamFrame.StreamError(
                                "AGENT_SERVICE_UNAVAILABLE",
                                "agent model service unavailable",
                                true,
                                true));

        lifecycleService.failFromAgent(RUN_ID, frame, 120_000L);

        assertThat(run.getRunStatus()).isEqualTo("FAILED");
        assertThat(run.getErrorRetryable()).isTrue();
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(eventService).append(eq(run), eq(3L), eq("error"), payload.capture());
        assertThat(payload.getValue().path("code").asText())
                .isEqualTo("AGENT_SERVICE_UNAVAILABLE");
        assertThat(payload.getValue().path("retryable").asBoolean()).isTrue();
        assertThat(payload.getValue().path("visible_output_emitted").asBoolean()).isFalse();
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.pendingIntakeRun()」。
    // 具体功能：「AgentRunLifecycleServiceTest.pendingIntakeRun()」：作为测试辅助方法为“核对完整业务行为（场景方法「pendingIntakeRun」）”组装或读取「AgentRunEntity.streamingPending」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunLifecycleServiceTest.pendingIntakeRun()」由本测试类中的 「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract」、「AgentRunLifecycleServiceTest.runningIntakeRun」 调用。
    // 下游影响：「AgentRunLifecycleServiceTest.pendingIntakeRun()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunLifecycleServiceTest.pendingIntakeRun()」守住「Agent 流式运行」的可执行规格，尤其防止 「CASE_1」、「ROOM_1」、「INTAKE_TURN」、「INTAKE_OFFICER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private AgentRunEntity pendingIntakeRun() {
        return AgentRunEntity.streamingPending(
                RUN_ID,
                "CASE_1",
                "ROOM_1",
                "INTAKE_TURN",
                "/internal/agents/intake/turn/stream",
                "INTAKE_OFFICER",
                "{\"message\":\"hello\"}",
                "request-hash",
                "[\"USER\"]",
                "[\"user-local\"]",
                "IDEMPOTENCY_1",
                "TRACE_1",
                "REQUEST_1",
                "user-local");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.runningIntakeRun()」。
    // 具体功能：「AgentRunLifecycleServiceTest.runningIntakeRun()」：作为测试辅助方法为“核对完整业务行为（场景方法「runningIntakeRun」）”组装或读取「run.markRunning」、「pendingIntakeRun」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunLifecycleServiceTest.runningIntakeRun()」由本测试类中的 「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText」、「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence」、「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection」、「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized」 调用。
    // 下游影响：「AgentRunLifecycleServiceTest.runningIntakeRun()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunLifecycleServiceTest.runningIntakeRun()」守住「Agent 流式运行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private AgentRunEntity runningIntakeRun() {
        AgentRunEntity run = pendingIntakeRun();
        run.markRunning();
        return run;
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.visibleDelta(String,String)」。
    // 具体功能：「AgentRunLifecycleServiceTest.visibleDelta(String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「visibleDelta」）”组装或读取「AgentStreamFrame」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「AgentRunLifecycleServiceTest.visibleDelta(String,String)」由本测试类中的 「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText」、「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence」 调用。
    // 下游影响：「AgentRunLifecycleServiceTest.visibleDelta(String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunLifecycleServiceTest.visibleDelta(String,String)」守住「Agent 流式运行」的可执行规格，尤其防止 「visible_delta」、「respond」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentStreamFrame visibleDelta(String fieldPath, String delta) {
        return new AgentStreamFrame(
                1L,
                "visible_delta",
                "respond",
                fieldPath,
                delta,
                null,
                null,
                null,
                null,
                null);
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunLifecycleServiceTest.finalFrame(JsonNode)」。
    // 具体功能：「AgentRunLifecycleServiceTest.finalFrame(JsonNode)」：作为测试辅助方法为“核对完整业务行为（场景方法「finalFrame」）”组装或读取「AgentStreamFrame」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「AgentRunLifecycleServiceTest.finalFrame(JsonNode)」由本测试类中的 「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection」 调用。
    // 下游影响：「AgentRunLifecycleServiceTest.finalFrame(JsonNode)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunLifecycleServiceTest.finalFrame(JsonNode)」守住「Agent 流式运行」的可执行规格，尤其防止 「final」、「finalize」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentStreamFrame finalFrame(JsonNode result) {
        return new AgentStreamFrame(
                2L,
                "final",
                "finalize",
                null,
                null,
                null,
                null,
                null,
                result,
                null);
    }
}
