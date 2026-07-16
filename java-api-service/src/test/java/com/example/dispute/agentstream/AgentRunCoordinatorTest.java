/*
 * 所属模块：Agent 流式运行。
 * 文件职责：验证Agent运行，覆盖 「newRunIsPersistedAndDispatchedExactlyOnce」、「sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch」、「sameIdempotencyKeyWithDifferentRequestIsRejected」、「activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunCoordinator;
import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.application.AgentRunStartCommand;
import com.example.dispute.agentstream.application.AgentRunWorker;
import com.example.dispute.agentstream.application.AgentStreamOperationRegistry;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

// 所属模块：【Agent 流式运行 / 自动化测试层】类型「AgentRunCoordinatorTest」。
// 类型职责：集中验证Agent运行的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「newRunIsPersistedAndDispatchedExactlyOnce」、「sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch」、「sameIdempotencyKeyWithDifferentRequestIsRejected」、「activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」、「prepareNewRun」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class AgentRunCoordinatorTest {

    private static final String CASE_ID = "CASE_1";
    private static final String ROOM_ID = "ROOM_1";
    private static final String ACTOR_ID = "user-local";

    @Mock private AgentRunRepository runRepository;
    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private ObjectProvider<AgentRunWorker> workerProvider;
    @Mock private AgentRunWorker worker;
    @Mock private AgentRunStreamEventService eventService;

    private ObjectMapper objectMapper;
    private AgentRunCoordinator coordinator;

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.setUp()」。
    // 具体功能：「AgentRunCoordinatorTest.setUp()」：在每个测试场景运行前创建「caseRepository.findByIdForUpdate」、「when」、「when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn」、「org.mockito.Mockito.mock」，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「AgentRunCoordinatorTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「AgentRunCoordinatorTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunCoordinatorTest.setUp()」守住「Agent 流式运行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        coordinator =
                new AgentRunCoordinator(
                        runRepository,
                        caseRepository,
                        new AgentStreamOperationRegistry(),
                        workerProvider,
                        new PostCommitSideEffectExecutor(Runnable::run),
                        eventService,
                        objectMapper);
        lenient().when(caseRepository.findByIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(FulfillmentCaseEntity.class)));
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce()」。
    // 具体功能：「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce()」：复现“核对完整业务行为（场景方法「newRunIsPersistedAndDispatchedExactlyOnce」）”场景：驱动 「coordinator.start」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「first」、「IDEMPOTENCY_1」、「PENDING」、「INTAKE_TURN」。
    // 上游调用：「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce()」守住「Agent 流式运行」的可执行规格，尤其防止 「first」、「IDEMPOTENCY_1」、「PENDING」、「INTAKE_TURN」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void newRunIsPersistedAndDispatchedExactlyOnce() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();

        var accepted = coordinator.start(command(request("first"), "IDEMPOTENCY_1"));

        assertThat(saved.get()).isNotNull();
        assertThat(saved.get().getRunStatus()).isEqualTo("PENDING");
        assertThat(saved.get().getCaseId()).isEqualTo(CASE_ID);
        assertThat(saved.get().getRoomId()).isEqualTo(ROOM_ID);
        assertThat(saved.get().getStreamOperation()).isEqualTo("INTAKE_TURN");
        assertThat(accepted.runId()).isEqualTo(saved.get().getId());
        assertThat(accepted.status()).isEqualTo("PENDING");
        verify(runRepository, times(1)).save(any(AgentRunEntity.class));
        verify(worker, times(1)).execute(saved.get().getId());
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch()」。
    // 具体功能：「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch()」：复现“核对完整业务行为（场景方法「sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch」）”场景：驱动 「coordinator.start」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「same」、「IDEMPOTENCY_2」。
    // 上游调用：「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch()」守住「Agent 流式运行」的可执行规格，尤其防止 「same」、「IDEMPOTENCY_2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();
        AgentRunStartCommand command = command(request("same"), "IDEMPOTENCY_2");

        var first = coordinator.start(command);
        var second = coordinator.start(command);

        assertThat(second.runId()).isEqualTo(first.runId());
        verify(runRepository, times(1)).save(any(AgentRunEntity.class));
        verify(worker, times(1)).execute(saved.get().getId());
    }

    @Test
    void semanticallyEqualRequestWithReorderedObjectFieldsReusesOriginalRun() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();
        ObjectNode original = objectMapper.createObjectNode();
        original.put("z", "last");
        original.set(
                "nested",
                objectMapper.createObjectNode().put("second", 2).put("first", 1));
        ObjectNode restoredFromJsonb = objectMapper.createObjectNode();
        restoredFromJsonb.set(
                "nested",
                objectMapper.createObjectNode().put("first", 1).put("second", 2));
        restoredFromJsonb.put("z", "last");

        var first = coordinator.start(command(original, "IDEMPOTENCY_REORDERED"));
        var second =
                coordinator.start(command(restoredFromJsonb, "IDEMPOTENCY_REORDERED"));

        assertThat(second.runId()).isEqualTo(first.runId());
        verify(runRepository, times(1)).save(any(AgentRunEntity.class));
        verify(worker, times(1)).execute(saved.get().getId());
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected()」。
    // 具体功能：「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected()」：复现“核对完整业务行为（场景方法「sameIdempotencyKeyWithDifferentRequestIsRejected」）”场景：驱动 「coordinator.start」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「original」、「IDEMPOTENCY_3」、「changed」。
    // 上游调用：「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected()」守住「Agent 流式运行」的可执行规格，尤其防止 「original」、「IDEMPOTENCY_3」、「changed」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void sameIdempotencyKeyWithDifferentRequestIsRejected() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();
        coordinator.start(command(request("original"), "IDEMPOTENCY_3"));

        assertThatThrownBy(
                        () ->
                                coordinator.start(
                                        command(request("changed"), "IDEMPOTENCY_3")))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different agent run");

        verify(runRepository, times(1)).save(any(AgentRunEntity.class));
        verify(worker, times(1)).execute(saved.get().getId());
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun()」。
    // 具体功能：「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun()」：复现“核对完整业务行为（场景方法「activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」）”场景：驱动 「runRepository.findByCaseIdAndStreamIdempotencyKey」、「runRepository.findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc」、「coordinator.start」，再用 「assertThatThrownBy」、「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「AGENT_RUN_ACTIVE」、「{\"message\":\"active\"}」、「active-hash」、「OTHER_KEY」。
    // 上游调用：「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun()」守住「Agent 流式运行」的可执行规格，尤其防止 「AGENT_RUN_ACTIVE」、「{\"message\":\"active\"}」、「active-hash」、「OTHER_KEY」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun() {
        AgentRunEntity active =
                pendingRun(
                        "AGENT_RUN_ACTIVE",
                        "{\"message\":\"active\"}",
                        "active-hash",
                        "OTHER_KEY");
        when(runRepository.findByCaseIdAndStreamIdempotencyKey(CASE_ID, "IDEMPOTENCY_4"))
                .thenReturn(Optional.empty());
        when(runRepository
                        .findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(
                                CASE_ID,
                                ROOM_ID,
                                "INTAKE_TURN",
                                ACTOR_ID,
                                List.of("PENDING", "RUNNING")))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(
                        () ->
                                coordinator.start(
                                        command(request("concurrent"), "IDEMPOTENCY_4")))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        failure ->
                                assertThat(((BusinessException) failure).details())
                                        .containsEntry("reason", "AGENT_RUN_IN_PROGRESS")
                                        .containsEntry("agent_run_id", "AGENT_RUN_ACTIVE"));

        verify(runRepository, never()).save(any());
        verify(worker, never()).execute(any());
    }

    @Test
    void retryableInfrastructureFailureCreatesANewAuditedAttempt() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();
        AgentRunStartCommand command = command(request("retry"), "IDEMPOTENCY_RETRY");
        coordinator.start(command);
        AgentRunEntity original = saved.get();
        original.markRunning();
        original.markFailed(
                "AGENT_STREAM_TIMEOUT", "agent stream exceeded configured timeout", true, 120000L);
        when(runRepository
                        .findAllByCaseIdAndStreamIdempotencyKeyStartingWithOrderByCreatedAtAsc(
                                CASE_ID, "IDEMPOTENCY_RETRY"))
                .thenReturn(List.of(original));
        when(eventService.hasVisibleOutput(original.getId())).thenReturn(false);

        var retry = coordinator.retryInfrastructureFailure(command);

        assertThat(original.getRunStatus()).isEqualTo("FAILED");
        assertThat(saved.get()).isNotSameAs(original);
        assertThat(saved.get().getRunStatus()).isEqualTo("PENDING");
        assertThat(saved.get().getStreamIdempotencyKey())
                .isEqualTo("IDEMPOTENCY_RETRY:attempt-2");
        assertThat(retry.runId()).isEqualTo(saved.get().getId());
    }

    @Test
    void unavailableJuryServiceWithoutVisibleOutputCreatesANewAuditedAttempt() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();
        AgentRunStartCommand command =
                command(
                        "HEARING_JURY_REVIEW",
                        request("retry unavailable jury service"),
                        "IDEMPOTENCY_JURY_UNAVAILABLE");
        coordinator.start(command);
        AgentRunEntity original = saved.get();
        original.markRunning();
        original.markFailed(
                "AGENT_SERVICE_UNAVAILABLE", "LiteLLM proxy returned a stream error", true, 45000L);
        when(runRepository
                        .findAllByCaseIdAndStreamIdempotencyKeyStartingWithOrderByCreatedAtAsc(
                                CASE_ID, "IDEMPOTENCY_JURY_UNAVAILABLE"))
                .thenReturn(List.of(original));
        when(eventService.hasVisibleOutput(original.getId())).thenReturn(false);

        var retry = coordinator.retryInfrastructureFailure(command);

        assertThat(original.getRunStatus()).isEqualTo("FAILED");
        assertThat(saved.get()).isNotSameAs(original);
        assertThat(saved.get().getRunStatus()).isEqualTo("PENDING");
        assertThat(saved.get().getStreamIdempotencyKey())
                .isEqualTo("IDEMPOTENCY_JURY_UNAVAILABLE:attempt-2");
        assertThat(retry.runId()).isEqualTo(saved.get().getId());
    }

    @Test
    void finalHearingSchemaFailureCreatesANewAuditedAttempt() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();
        AgentRunStartCommand command =
                command(
                        "HEARING_JURY_REVIEW",
                        request("retry invalid jury output"),
                        "IDEMPOTENCY_JURY_RETRY");
        coordinator.start(command);
        AgentRunEntity original = saved.get();
        original.markRunning();
        original.markFailed(
                "AGENT_OUTPUT_SCHEMA_INVALID",
                "jury findings must cover each dimension once",
                false,
                500L);
        when(runRepository
                        .findAllByCaseIdAndStreamIdempotencyKeyStartingWithOrderByCreatedAtAsc(
                                CASE_ID, "IDEMPOTENCY_JURY_RETRY"))
                .thenReturn(List.of(original));

        var retry = coordinator.retryInfrastructureFailure(command);

        assertThat(saved.get()).isNotSameAs(original);
        assertThat(saved.get().getRunStatus()).isEqualTo("PENDING");
        assertThat(saved.get().getStreamIdempotencyKey())
                .isEqualTo("IDEMPOTENCY_JURY_RETRY:attempt-2");
        assertThat(retry.runId()).isEqualTo(saved.get().getId());
    }

    @Test
    void failureWithVisibleOutputIsNeverRetried() {
        AtomicReference<AgentRunEntity> saved = prepareNewRun();
        AgentRunStartCommand command = command(request("visible"), "IDEMPOTENCY_VISIBLE");
        coordinator.start(command);
        AgentRunEntity original = saved.get();
        original.markRunning();
        original.markFailed("AGENT_STREAM_TRANSPORT_FAILED", "connection reset", true, 500L);
        when(runRepository
                        .findAllByCaseIdAndStreamIdempotencyKeyStartingWithOrderByCreatedAtAsc(
                                CASE_ID, "IDEMPOTENCY_VISIBLE"))
                .thenReturn(List.of(original));
        when(eventService.hasVisibleOutput(original.getId())).thenReturn(true);

        var unchanged = coordinator.retryInfrastructureFailure(command);

        assertThat(unchanged.runId()).isEqualTo(original.getId());
        verify(runRepository, times(1)).save(any(AgentRunEntity.class));
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.prepareNewRun()」。
    // 具体功能：「AgentRunCoordinatorTest.prepareNewRun()」：作为测试辅助方法为“核对完整业务行为（场景方法「prepareNewRun」）”组装或读取「AtomicReference」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「AgentRunCoordinatorTest.prepareNewRun()」由本测试类中的 「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce」、「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch」、「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected」 调用。
    // 下游影响：「AgentRunCoordinatorTest.prepareNewRun()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunCoordinatorTest.prepareNewRun()」守住「Agent 流式运行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private AtomicReference<AgentRunEntity> prepareNewRun() {
        AtomicReference<AgentRunEntity> saved = new AtomicReference<>();
        when(runRepository.findByCaseIdAndStreamIdempotencyKey(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(runRepository
                        .findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(
                                any(), any(), any(), any(), anyList()))
                .thenReturn(Optional.empty());
        when(runRepository.save(any(AgentRunEntity.class)))
                .thenAnswer(
                        invocation -> {
                            AgentRunEntity run = invocation.getArgument(0);
                            saved.set(run);
                            return run;
                        });
        when(workerProvider.getObject()).thenReturn(worker);
        return saved;
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.command(ObjectNode,String)」。
    // 具体功能：「AgentRunCoordinatorTest.command(ObjectNode,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「command」）”组装或读取「AgentRunStartCommand」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「AgentRunCoordinatorTest.command(ObjectNode,String)」由本测试类中的 「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce」、「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch」、「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected」、「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」 调用。
    // 下游影响：「AgentRunCoordinatorTest.command(ObjectNode,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunCoordinatorTest.command(ObjectNode,String)」守住「Agent 流式运行」的可执行规格，尤其防止 「INTAKE_TURN」、「USER」、「TRACE_1」、「REQUEST_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private AgentRunStartCommand command(ObjectNode request, String idempotencyKey) {
        return command("INTAKE_TURN", request, idempotencyKey);
    }

    private AgentRunStartCommand command(
            String operation, ObjectNode request, String idempotencyKey) {
        return new AgentRunStartCommand(
                CASE_ID,
                ROOM_ID,
                operation,
                request,
                List.of("USER"),
                List.of(ACTOR_ID),
                idempotencyKey,
                "TRACE_1",
                "REQUEST_1",
                ACTOR_ID);
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.request(String)」。
    // 具体功能：「AgentRunCoordinatorTest.request(String)」：作为测试辅助方法为“核对完整业务行为（场景方法「request」）”组装或读取「objectMapper.createObjectNode」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunCoordinatorTest.request(String)」由本测试类中的 「AgentRunCoordinatorTest.newRunIsPersistedAndDispatchedExactlyOnce」、「AgentRunCoordinatorTest.sameIdempotencyKeyAndRequestReusesOriginalRunWithoutRedispatch」、「AgentRunCoordinatorTest.sameIdempotencyKeyWithDifferentRequestIsRejected」、「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」 调用。
    // 下游影响：「AgentRunCoordinatorTest.request(String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunCoordinatorTest.request(String)」守住「Agent 流式运行」的可执行规格，尤其防止 「message」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private ObjectNode request(String message) {
        return objectMapper.createObjectNode().put("message", message);
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunCoordinatorTest.pendingRun(String,String,String,String)」。
    // 具体功能：「AgentRunCoordinatorTest.pendingRun(String,String,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「pendingRun」）”组装或读取「AgentRunEntity.streamingPending」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunCoordinatorTest.pendingRun(String,String,String,String)」由本测试类中的 「AgentRunCoordinatorTest.activeRunInSameCaseRoomOperationAndActorBlocksConcurrentRun」 调用。
    // 下游影响：「AgentRunCoordinatorTest.pendingRun(String,String,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunCoordinatorTest.pendingRun(String,String,String,String)」守住「Agent 流式运行」的可执行规格，尤其防止 「INTAKE_TURN」、「INTAKE_OFFICER」、「[\"USER\"]」、「[\"user-local\"]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentRunEntity pendingRun(
            String id, String requestJson, String requestHash, String idempotencyKey) {
        return AgentRunEntity.streamingPending(
                id,
                CASE_ID,
                ROOM_ID,
                "INTAKE_TURN",
                "/internal/agents/intake/turn/stream",
                "INTAKE_OFFICER",
                requestJson,
                requestHash,
                "[\"USER\"]",
                "[\"user-local\"]",
                idempotencyKey,
                "TRACE_1",
                "REQUEST_1",
                ACTOR_ID);
    }
}
