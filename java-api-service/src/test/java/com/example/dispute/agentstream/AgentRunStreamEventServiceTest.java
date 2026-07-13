/*
 * 所属模块：Agent 流式运行。
 * 文件职责：验证Agent运行流事件，覆盖 「replayUsesExclusiveCursorAndPreservesSequenceOrder」、「appendIsIdempotentForAnExistingSequence」、「appendPersistsASequenceOnlyOnce」、「roleAudienceRejectsAnActorWithTheWrongRole」、「actorAudienceRejectsAnotherUserWithTheSameRole」、「administratorCanReadAnActorScopedRun」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventEntity;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventRepository;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 所属模块：【Agent 流式运行 / 自动化测试层】类型「AgentRunStreamEventServiceTest」。
// 类型职责：集中验证Agent运行流事件的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「replayUsesExclusiveCursorAndPreservesSequenceOrder」、「appendIsIdempotentForAnExistingSequence」、「appendPersistsASequenceOnlyOnce」、「roleAudienceRejectsAnActorWithTheWrongRole」、「actorAudienceRejectsAnotherUserWithTheSameRole」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class AgentRunStreamEventServiceTest {

    private static final String CASE_ID = "CASE_stream";
    private static final String RUN_ID = "ARUN_stream";

    @Mock private AgentRunRepository runRepository;
    @Mock private AgentRunStreamEventRepository eventRepository;
    @Mock private AccessSessionResolver accessSessionResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentRunStreamEventService service;

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.setUp()」。
    // 具体功能：「AgentRunStreamEventServiceTest.setUp()」：在每个测试场景运行前创建测试对象和内存夹具，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「AgentRunStreamEventServiceTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「AgentRunStreamEventServiceTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunStreamEventServiceTest.setUp()」守住「Agent 流式运行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        service =
                new AgentRunStreamEventService(
                        runRepository,
                        eventRepository,
                        accessSessionResolver,
                        new SessionPermissionService(),
                        objectMapper);
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder()」。
    // 具体功能：「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder()」：复现“核对完整业务行为（场景方法「replayUsesExclusiveCursorAndPreservesSequenceOrder」）”场景：驱动 「eventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「service.replay」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-local」、「USER」、「visible_delta」、「final」。
    // 上游调用：「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder()」守住「Agent 流式运行」的可执行规格，尤其防止 「user-local」、「USER」、「visible_delta」、「final」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void replayUsesExclusiveCursorAndPreservesSequenceOrder() {
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        AgentRunEntity run = run(List.of("USER"), List.of("user-local"));
        allow(actor, run, session("user-local", ActorRole.USER, PermissionLevel.PARTY_USER));
        when(eventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        RUN_ID, 4L))
                .thenReturn(
                        List.of(
                                event(5, "visible_delta", "{\"field\":\"room_utterance\",\"delta\":\"甲\"}"),
                                event(6, "visible_delta", "{\"field\":\"room_utterance\",\"delta\":\"乙\"}"),
                                event(7, "final", "{\"response\":{\"room_utterance\":\"甲乙\"}}")));

        var replay = service.replay(RUN_ID, 4L, actor);

        assertThat(replay).extracting(value -> value.sequence()).containsExactly(5L, 6L, 7L);
        assertThat(replay).extracting(value -> value.type())
                .containsExactly("visible_delta", "visible_delta", "final");
        assertThat(replay.get(0).delta()).isEqualTo("甲");
        assertThat(replay.get(2).response().path("room_utterance").asText()).isEqualTo("甲乙");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence()」。
    // 具体功能：「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence()」：复现“核对完整业务行为（场景方法「appendIsIdempotentForAnExistingSequence」）”场景：驱动 「eventRepository.findByAgentRunIdAndSequenceNo」、「service.append」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「USER」、「user-local」、「visible_delta」、「{\"delta\":\"已存在\"}」。
    // 上游调用：「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence()」守住「Agent 流式运行」的可执行规格，尤其防止 「USER」、「user-local」、「visible_delta」、「{\"delta\":\"已存在\"}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void appendIsIdempotentForAnExistingSequence() throws Exception {
        AgentRunEntity run = run(List.of("USER"), List.of("user-local"));
        AgentRunStreamEventEntity existing = event(2, "visible_delta", "{\"delta\":\"已存在\"}");
        when(eventRepository.findByAgentRunIdAndSequenceNo(RUN_ID, 2L))
                .thenReturn(Optional.of(existing));

        var returned =
                service.append(
                        run,
                        2L,
                        "visible_delta",
                        objectMapper.readTree("{\"delta\":\"重复投递\"}"));

        assertThat(returned).isSameAs(existing);
        verify(eventRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.appendPersistsASequenceOnlyOnce()」。
    // 具体功能：「AgentRunStreamEventServiceTest.appendPersistsASequenceOnlyOnce()」：复现“核对完整业务行为（场景方法「appendPersistsASequenceOnlyOnce」）”场景：驱动 「eventRepository.findByAgentRunIdAndSequenceNo」、「eventRepository.save」、「service.append」，再用 「verify」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「USER」、「user-local」、「visible_delta」、「公开内容」。
    // 上游调用：「AgentRunStreamEventServiceTest.appendPersistsASequenceOnlyOnce()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunStreamEventServiceTest.appendPersistsASequenceOnlyOnce()」的下游是被测服务、仓储或外部客户端替身；「verify、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunStreamEventServiceTest.appendPersistsASequenceOnlyOnce()」守住「Agent 流式运行」的可执行规格，尤其防止 「USER」、「user-local」、「visible_delta」、「公开内容」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void appendPersistsASequenceOnlyOnce() throws Exception {
        AgentRunEntity run = run(List.of("USER"), List.of("user-local"));
        when(eventRepository.findByAgentRunIdAndSequenceNo(RUN_ID, 3L))
                .thenReturn(Optional.empty());
        when(eventRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.append(
                run,
                3L,
                "visible_delta",
                objectMapper.readTree(
                        "{\"schema_version\":\"agent_stream.v1\",\"delta\":\"公开内容\"}"));

        ArgumentCaptor<AgentRunStreamEventEntity> captor =
                ArgumentCaptor.forClass(AgentRunStreamEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getAgentRunId()).isEqualTo(RUN_ID);
        assertThat(captor.getValue().getSequenceNo()).isEqualTo(3L);
        assertThat(captor.getValue().getPayloadJson()).contains("公开内容");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole()」。
    // 具体功能：「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole()」：复现“核对完整业务行为（场景方法「roleAudienceRejectsAnActorWithTheWrongRole」）”场景：驱动 「service.replay」，再用 「assertThatThrownBy」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「merchant-local」、「USER」。
    // 上游调用：「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole()」守住「Agent 流式运行」的可执行规格，尤其防止 「merchant-local」、「USER」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void roleAudienceRejectsAnActorWithTheWrongRole() {
        AuthenticatedActor merchant = new AuthenticatedActor("merchant-local", ActorRole.MERCHANT);
        AgentRunEntity run = run(List.of("USER"), List.of());
        allow(
                merchant,
                run,
                session("merchant-local", ActorRole.MERCHANT, PermissionLevel.PARTY_MERCHANT));

        assertThatThrownBy(() -> service.replay(RUN_ID, -1L, merchant))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot read");
        verify(eventRepository, never())
                .findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole()」。
    // 具体功能：「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole()」：复现“核对完整业务行为（场景方法「actorAudienceRejectsAnotherUserWithTheSameRole」）”场景：驱动 「service.replay」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「user-other」、「USER」、「user-local」。
    // 上游调用：「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole()」守住「Agent 流式运行」的可执行规格，尤其防止 「user-other」、「USER」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void actorAudienceRejectsAnotherUserWithTheSameRole() {
        AuthenticatedActor otherUser = new AuthenticatedActor("user-other", ActorRole.USER);
        AgentRunEntity run = run(List.of("USER"), List.of("user-local"));
        allow(otherUser, run, session("user-other", ActorRole.USER, PermissionLevel.PARTY_USER));

        assertThatThrownBy(() -> service.replay(RUN_ID, -1L, otherUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot read");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun()」。
    // 具体功能：「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun()」：复现“核对完整业务行为（场景方法「administratorCanReadAnActorScopedRun」）”场景：驱动 「eventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「service.replay」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「admin-local」、「USER」、「user-local」。
    // 上游调用：「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun()」守住「Agent 流式运行」的可执行规格，尤其防止 「admin-local」、「USER」、「user-local」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void administratorCanReadAnActorScopedRun() {
        AuthenticatedActor administrator =
                new AuthenticatedActor("admin-local", ActorRole.ADMIN);
        AgentRunEntity run = run(List.of("USER"), List.of("user-local"));
        allow(
                administrator,
                run,
                session(
                        "admin-local",
                        ActorRole.ADMIN,
                        PermissionLevel.ADMIN_ALL));
        when(eventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        RUN_ID, -1L))
                .thenReturn(List.of());

        assertThat(service.replay(RUN_ID, -1L, administrator)).isEmpty();
    }

    @Test
    void reconnectRegistrationIsNotLostDuringConcurrentOldConnectionRemoval() throws Exception {
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        AgentRunEntity run = run(List.of("USER"), List.of("user-local"));
        allow(actor, run, session("user-local", ActorRole.USER, PermissionLevel.PARTY_USER));
        when(eventRepository.findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        org.mockito.ArgumentMatchers.eq(RUN_ID),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(List.of());

        service.subscribe(RUN_ID, -1L, actor);
        Map<String, CopyOnWriteArrayList<Object>> subscriptions = subscriptions();
        Object oldSubscription = subscriptions.get(RUN_ID).get(0);
        GatedCopyOnWriteArrayList gatedSubscriptions = new GatedCopyOnWriteArrayList();
        gatedSubscriptions.add(oldSubscription);
        gatedSubscriptions.arm();
        subscriptions.put(RUN_ID, gatedSubscriptions);
        Method remove =
                AgentRunStreamEventService.class.getDeclaredMethod(
                        "remove", String.class, oldSubscription.getClass());
        remove.setAccessible(true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var disconnect =
                    executor.submit(
                            () -> {
                                remove.invoke(service, RUN_ID, oldSubscription);
                                return null;
                            });
            boolean removalPausedAfterEmptyCheck =
                    gatedSubscriptions.awaitEmptyObserved(1, TimeUnit.SECONDS);
            var reconnect = executor.submit(() -> service.subscribe(RUN_ID, 5L, actor));
            boolean replacementEnteredDetachedList =
                    gatedSubscriptions.awaitReplacementAdded(1, TimeUnit.SECONDS);

            gatedSubscriptions.releaseRemoval();
            disconnect.get(5, TimeUnit.SECONDS);
            reconnect.get(5, TimeUnit.SECONDS);

            assertThat(removalPausedAfterEmptyCheck).isTrue();
            assertThat(replacementEnteredDetachedList)
                    .as("reconnect registration must wait for removal of the old map entry")
                    .isFalse();
            assertThat(subscriptions.get(RUN_ID))
                    .as("replacement subscription must remain registered")
                    .isNotNull()
                    .hasSize(1)
                    .doesNotContain(oldSubscription);
        } finally {
            gatedSubscriptions.releaseRemoval();
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, CopyOnWriteArrayList<Object>> subscriptions() throws Exception {
        Field field = AgentRunStreamEventService.class.getDeclaredField("subscriptions");
        field.setAccessible(true);
        return (Map<String, CopyOnWriteArrayList<Object>>) field.get(service);
    }

    private static final class GatedCopyOnWriteArrayList
            extends CopyOnWriteArrayList<Object> {
        private final CountDownLatch emptyObserved = new CountDownLatch(1);
        private final CountDownLatch replacementAdded = new CountDownLatch(1);
        private final CountDownLatch releaseRemoval = new CountDownLatch(1);
        private volatile boolean armed;

        private void arm() {
            armed = true;
        }

        private boolean awaitEmptyObserved(long timeout, TimeUnit unit)
                throws InterruptedException {
            return emptyObserved.await(timeout, unit);
        }

        private boolean awaitReplacementAdded(long timeout, TimeUnit unit)
                throws InterruptedException {
            return replacementAdded.await(timeout, unit);
        }

        private void releaseRemoval() {
            releaseRemoval.countDown();
        }

        @Override
        public boolean isEmpty() {
            boolean empty = super.isEmpty();
            if (armed && empty) {
                emptyObserved.countDown();
                await(releaseRemoval);
            }
            return empty;
        }

        @Override
        public boolean add(Object value) {
            boolean added = super.add(value);
            if (armed && emptyObserved.getCount() == 0) {
                replacementAdded.countDown();
            }
            return added;
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while coordinating SSE race test", exception);
            }
        }
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.allow(AuthenticatedActor,AgentRunEntity,CaseAccessSessionEntity)」。
    // 具体功能：「AgentRunStreamEventServiceTest.allow(AuthenticatedActor,AgentRunEntity,CaseAccessSessionEntity)」：作为测试辅助方法为“允许满足条件的操作（场景方法「allow」）”组装或读取「runRepository.findById」、「accessSessionResolver.resolve」、「when」、「when(runRepository.findById(RUN_ID)).thenReturn」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunStreamEventServiceTest.allow(AuthenticatedActor,AgentRunEntity,CaseAccessSessionEntity)」由本测试类中的 「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder」、「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole」、「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole」、「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun」 调用。
    // 下游影响：「AgentRunStreamEventServiceTest.allow(AuthenticatedActor,AgentRunEntity,CaseAccessSessionEntity)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunStreamEventServiceTest.allow(AuthenticatedActor,AgentRunEntity,CaseAccessSessionEntity)」守住「Agent 流式运行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private void allow(
            AuthenticatedActor actor,
            AgentRunEntity run,
            CaseAccessSessionEntity accessSession) {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(accessSessionResolver.resolve(CASE_ID, actor)).thenReturn(accessSession);
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.run(List,List)」。
    // 具体功能：「AgentRunStreamEventServiceTest.run(List,List)」：作为测试辅助方法为“核对完整业务行为（场景方法「run」）”组装或读取「AgentRunEntity.streamingPending」、「jsonArray」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunStreamEventServiceTest.run(List,List)」由本测试类中的 「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder」、「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence」、「AgentRunStreamEventServiceTest.appendPersistsASequenceOnlyOnce」、「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole」 调用。
    // 下游影响：「AgentRunStreamEventServiceTest.run(List,List)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunStreamEventServiceTest.run(List,List)」守住「Agent 流式运行」的可执行规格，尤其防止 「ROOM_intake」、「INTAKE_TURN」、「DISPUTE_INTAKE_OFFICER」、「{}」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentRunEntity run(List<String> roles, List<String> actorIds) {
        return AgentRunEntity.streamingPending(
                RUN_ID,
                CASE_ID,
                "ROOM_intake",
                "INTAKE_TURN",
                "/internal/agents/intake/stream",
                "DISPUTE_INTAKE_OFFICER",
                "{}",
                "hash",
                jsonArray(roles),
                jsonArray(actorIds),
                "idem-1",
                "trace-1",
                "request-1",
                "user-local");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.session(String,ActorRole,PermissionLevel)」。
    // 具体功能：「AgentRunStreamEventServiceTest.session(String,ActorRole,PermissionLevel)」：作为测试辅助方法为“核对完整业务行为（场景方法「session」）”组装或读取「CaseAccessSessionEntity.create」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunStreamEventServiceTest.session(String,ActorRole,PermissionLevel)」由本测试类中的 「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder」、「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole」、「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole」、「AgentRunStreamEventServiceTest.administratorCanReadAnActorScopedRun」 调用。
    // 下游影响：「AgentRunStreamEventServiceTest.session(String,ActorRole,PermissionLevel)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunStreamEventServiceTest.session(String,ActorRole,PermissionLevel)」守住「Agent 流式运行」的可执行规格，尤其防止 「CAS_」、「TENANT_local」、「system」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static CaseAccessSessionEntity session(
            String actorId, ActorRole role, PermissionLevel permissionLevel) {
        return CaseAccessSessionEntity.create(
                "CAS_" + actorId,
                "TENANT_local",
                CASE_ID,
                actorId,
                role,
                permissionLevel,
                "system");
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.event(long,String,String)」。
    // 具体功能：「AgentRunStreamEventServiceTest.event(long,String,String)」：作为测试辅助方法为“核对完整业务行为（场景方法「event」）”组装或读取「AgentRunStreamEventEntity.create」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunStreamEventServiceTest.event(long,String,String)」由本测试类中的 「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder」、「AgentRunStreamEventServiceTest.appendIsIdempotentForAnExistingSequence」 调用。
    // 下游影响：「AgentRunStreamEventServiceTest.event(long,String,String)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunStreamEventServiceTest.event(long,String,String)」守住「Agent 流式运行」的可执行规格，尤其防止 「ARSE_」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    private static AgentRunStreamEventEntity event(
            long sequence, String type, String payload) {
        return AgentRunStreamEventEntity.create(
                "ARSE_" + sequence, RUN_ID, sequence, type, payload);
    }

    // 所属模块：【Agent 流式运行 / 自动化测试层】「AgentRunStreamEventServiceTest.jsonArray(List)」。
    // 具体功能：「AgentRunStreamEventServiceTest.jsonArray(List)」：作为测试辅助方法为“核对完整业务行为（场景方法「jsonArray」）”组装或读取「java.util.stream.Collectors.joining」，供本测试类的场景方法复用。
    // 上游调用：「AgentRunStreamEventServiceTest.jsonArray(List)」由本测试类中的 「AgentRunStreamEventServiceTest.run」 调用。
    // 下游影响：「AgentRunStreamEventServiceTest.jsonArray(List)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「AgentRunStreamEventServiceTest.jsonArray(List)」守住「Agent 流式运行」的可执行规格，尤其防止 「\」、「,」、「[」、「]」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
