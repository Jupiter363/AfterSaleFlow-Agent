/*
 * 所属模块：Agent 流式运行。
 * 文件职责：编排Agent 流事件持久化、重放与 SSE 投递规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「append」、「nextSequence」、「hasVisibleOutput」、「replay」、「subscribe」、「heartbeat」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventEntity;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunStreamEventService」。
// 类型职责：编排Agent 流事件持久化、重放与 SSE 投递规则、权限校验与事实读写；本类型显式提供 「AgentRunStreamEventService」、「append」、「nextSequence」、「hasVisibleOutput」、「replay」、「subscribe」。
// 协作关系：主要由 「AgentRunController.events」、「AgentRunController.replay」、「AgentRunLifecycleService.complete」、「AgentRunLifecycleService.failFromAgent」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AgentRunStreamEventService {

    private static final long EMITTER_TIMEOUT_MS = 4 * 60 * 60 * 1000L;

    private final AgentRunRepository runRepository;
    private final AgentRunStreamEventRepository eventRepository;
    private final AccessSessionResolver accessSessionResolver;
    private final SessionPermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final Map<String, CopyOnWriteArrayList<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.AgentRunStreamEventService(AgentRunRepository,AgentRunStreamEventRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper)」。
    // 具体功能：「AgentRunStreamEventService.AgentRunStreamEventService(AgentRunRepository,AgentRunStreamEventRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper)」：通过构造器接收 「runRepository」(AgentRunRepository)、「eventRepository」(AgentRunStreamEventRepository)、「accessSessionResolver」(AccessSessionResolver)、「permissionService」(SessionPermissionService)、「objectMapper」(ObjectMapper) 并保存为「AgentRunStreamEventService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunStreamEventService.AgentRunStreamEventService(AgentRunRepository,AgentRunStreamEventRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper)」的上游创建点包括 「AgentRunStreamEventServiceTest.setUp」。
    // 下游影响：「AgentRunStreamEventService.AgentRunStreamEventService(AgentRunRepository,AgentRunStreamEventRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunStreamEventService.AgentRunStreamEventService(AgentRunRepository,AgentRunStreamEventRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunStreamEventService(
            AgentRunRepository runRepository,
            AgentRunStreamEventRepository eventRepository,
            AccessSessionResolver accessSessionResolver,
            SessionPermissionService permissionService,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.accessSessionResolver = accessSessionResolver;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.append(AgentRunEntity,long,String,JsonNode)」。
    // 具体功能：「AgentRunStreamEventService.append(AgentRunEntity,long,String,JsonNode)」：以 runId+sequence 作为事件幂等键，重复序号复用已存在的不可变事件而不覆盖浏览器已消费历史；新事件保存后只在事务提交成功时唤醒 SSE 订阅，载荷一致性由上游协议状态机保证，最终返回「AgentRunStreamEventEntity」。
    // 上游调用：「AgentRunStreamEventService.append(AgentRunEntity,long,String,JsonNode)」的上游调用点包括 「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunLifecycleService.complete」、「AgentRunLifecycleService.failFromAgent」、「AgentRunLifecycleService.failInfrastructure」。
    // 下游影响：「AgentRunStreamEventService.append(AgentRunEntity,long,String,JsonNode)」向下依次触达 「eventRepository.findByAgentRunIdAndSequenceNo」、「eventRepository.save」、「AgentRunStreamEventEntity.create」、「run.getId」；计算结果以「AgentRunStreamEventEntity」交给调用方。
    // 系统意义：「AgentRunStreamEventService.append(AgentRunEntity,long,String,JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public AgentRunStreamEventEntity append(
            AgentRunEntity run, long sequence, String eventType, JsonNode publicPayload) {
        // (run_id, sequence_no) 是数据库唯一键。恢复或重试再次提交同一序号时复用不可变旧事件，
        // 不覆盖已经被浏览器游标消费的历史；序号和载荷一致性由上游协议状态机保证。
        AgentRunStreamEventEntity event =
                eventRepository
                        .findByAgentRunIdAndSequenceNo(run.getId(), sequence)
                        .orElseGet(
                                () ->
                                        eventRepository.save(
                                                AgentRunStreamEventEntity.create(
                                                        "ARSE_" + compactUuid(),
                                                        run.getId(),
                                                        sequence,
                                                        eventType,
                                                        json(publicPayload))));
        publishAfterCommit(run.getId());
        return event;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.nextSequence(String)」。
    // 具体功能：「AgentRunStreamEventService.nextSequence(String)」：读取该 Run 已持久化的最大事件序号并加一，为基础设施故障补写 error 帧提供不冲突序号，最终返回「long」。
    // 上游调用：「AgentRunStreamEventService.nextSequence(String)」的上游调用点包括 「AgentRunLifecycleService.failInfrastructure」、「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized」。
    // 下游影响：「AgentRunStreamEventService.nextSequence(String)」向下依次触达 「eventRepository.findMaxSequenceByAgentRunId」；计算结果以「long」交给调用方。
    // 系统意义：「AgentRunStreamEventService.nextSequence(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public long nextSequence(String runId) {
        return eventRepository.findMaxSequenceByAgentRunId(runId) + 1;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.hasVisibleOutput(String)」。
    // 具体功能：「AgentRunStreamEventService.hasVisibleOutput(String)」：查询是否已经持久化 visible_delta；该事实决定失败后能否自动重试，不能信任上游进程内布尔值，最终返回「boolean」。
    // 上游调用：「AgentRunStreamEventService.hasVisibleOutput(String)」的上游调用点包括 「AgentRunLifecycleService.failFromAgent」、「AgentRunLifecycleService.failInfrastructure」、「AgentRunRecoveryScheduler.failStale」、「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized」。
    // 下游影响：「AgentRunStreamEventService.hasVisibleOutput(String)」向下依次触达 「eventRepository.existsByAgentRunIdAndEventType」；计算结果以「boolean」交给调用方。
    // 系统意义：「AgentRunStreamEventService.hasVisibleOutput(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public boolean hasVisibleOutput(String runId) {
        return eventRepository.existsByAgentRunIdAndEventType(runId, "visible_delta");
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.replay(String,long,AuthenticatedActor)」。
    // 具体功能：「AgentRunStreamEventService.replay(String,long,AuthenticatedActor)」：先校验当前操作者对 Run、案件、房间和受众的读取权限，再按 sequence 升序返回游标后的持久化事件，最终返回「List<AgentRunEventView>」。
    // 上游调用：「AgentRunStreamEventService.replay(String,long,AuthenticatedActor)」的上游调用点包括 「AgentRunController.replay」、「AgentRunStreamEventServiceTest.replayUsesExclusiveCursorAndPreservesSequenceOrder」、「AgentRunStreamEventServiceTest.roleAudienceRejectsAnActorWithTheWrongRole」、「AgentRunStreamEventServiceTest.actorAudienceRejectsAnotherUserWithTheSameRole」。
    // 下游影响：「AgentRunStreamEventService.replay(String,long,AuthenticatedActor)」向下依次触达 「findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「run.getId」、「requireVisibleRun」；计算结果以「List<AgentRunEventView>」交给调用方。
    // 系统意义：「AgentRunStreamEventService.replay(String,long,AuthenticatedActor)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public List<AgentRunEventView> replay(
            String runId, long afterSequence, AuthenticatedActor actor) {
        AgentRunEntity run = requireVisibleRun(runId, actor);
        return eventRepository
                .findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        run.getId(), afterSequence)
                .stream()
                .map(this::view)
                .toList();
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.subscribe(String,long,AuthenticatedActor)」。
    // 具体功能：「AgentRunStreamEventService.subscribe(String,long,AuthenticatedActor)」：创建带超时回调的 SseEmitter，保存每个订阅自己的最后序号，并立即执行一次 catch-up，从而同时覆盖先订阅后产出和断线重连两种时序，最终返回「SseEmitter」。
    // 上游调用：「AgentRunStreamEventService.subscribe(String,long,AuthenticatedActor)」的上游调用点包括 「AgentRunController.events」。
    // 下游影响：「AgentRunStreamEventService.subscribe(String,long,AuthenticatedActor)」向下依次触达 「subscriptions.computeIfAbsent」、「emitter.onCompletion」、「emitter.onTimeout」、「emitter.onError」；计算结果以「SseEmitter」交给调用方。
    // 系统意义：「AgentRunStreamEventService.subscribe(String,long,AuthenticatedActor)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    public SseEmitter subscribe(
            String runId, long afterSequence, AuthenticatedActor actor) {
        requireVisibleRun(runId, actor);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscription subscription =
                new Subscription(actor, emitter, new AtomicLong(afterSequence));
        // 每条浏览器连接维护独立游标；同一个 Run 可以被断线重连的同一角色、
        // 或多个获授权平台角色同时订阅，彼此不会推进对方的 lastSequence。
        add(runId, subscription);
        Runnable remove = () -> remove(runId, subscription);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        catchUp(runId, subscription);
        return emitter;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.heartbeat()」。
    // 具体功能：「AgentRunStreamEventService.heartbeat()」：定时对所有订阅先补发数据库中遗漏事件，再发送 heartbeat；I/O 失败的连接会从订阅表移除，最终返回「void」。
    // 上游调用：「AgentRunStreamEventService.heartbeat()」由 Spring 定时调度器触发；它在固定间隔扫描未收敛记录，不由浏览器直接触发。
    // 下游影响：「AgentRunStreamEventService.heartbeat()」向下依次触达 「SseEmitter.event」、「subscription.emitter」、「catchUp」、「removeDisconnected」。
    // 系统意义：「AgentRunStreamEventService.heartbeat()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Scheduled(fixedDelayString = "${dispute.sse-heartbeat:PT15S}")
    public void heartbeat() {
        subscriptions.forEach(
                (runId, runSubscriptions) ->
                        runSubscriptions.forEach(
                                subscription -> {
                                    if (!catchUp(runId, subscription)) {
                                        return;
                                    }
                                    try {
                                        synchronized (subscription) {
                                            subscription.emitter().send(
                                                    SseEmitter.event().comment("heartbeat"));
                                        }
                                    } catch (IOException | RuntimeException failure) {
                                        removeDisconnected(runId, subscription, failure);
                                    }
                                }));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.requireVisibleRun(String,AuthenticatedActor)」。
    // 具体功能：「AgentRunStreamEventService.requireVisibleRun(String,AuthenticatedActor)」：确认 Run 属于流式操作，并通过案件访问会话、房间读权限和 audience 白名单三层校验当前操作者，最终返回「AgentRunEntity」。
    // 上游调用：「AgentRunStreamEventService.requireVisibleRun(String,AuthenticatedActor)」的上游调用点包括 「AgentRunQueryService.get」、「AgentRunQueryService.active」、「AgentRunStreamEventService.replay」、「AgentRunStreamEventService.subscribe」。
    // 下游影响：「AgentRunStreamEventService.requireVisibleRun(String,AuthenticatedActor)」向下依次触达 「runRepository.findById」、「accessSessionResolver.resolve」、「permissionService.requireCaseRead」、「run.getStreamOperation」；计算结果以「AgentRunEntity」交给调用方。
    // 系统意义：「AgentRunStreamEventService.requireVisibleRun(String,AuthenticatedActor)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    public AgentRunEntity requireVisibleRun(String runId, AuthenticatedActor actor) {
        AgentRunEntity run =
                runRepository
                        .findById(runId)
                        .orElseThrow(() -> new IllegalArgumentException("agent run not found"));
        if (run.getStreamOperation() == null) {
            throw new IllegalArgumentException("agent run is not stream-enabled");
        }
        CaseAccessSessionEntity accessSession =
                accessSessionResolver.resolve(run.getCaseId(), actor);
        permissionService.requireCaseRead(accessSession);
        if (!visibleTo(run, accessSession)) {
            throw new ForbiddenException("actor cannot read this agent run");
        }
        return run;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.catchUp(String,Subscription)」。
    // 具体功能：「AgentRunStreamEventService.catchUp(String,Subscription)」：在订阅锁内查询 lastSequence 之后的事件，逐条重做权限校验并发送；发现 final/error 后完成连接，最终返回「boolean」。
    // 上游调用：「AgentRunStreamEventService.catchUp(String,Subscription)」的上游调用点包括 「AgentRunStreamEventService.subscribe」、「AgentRunStreamEventService.heartbeat」、「AgentRunStreamEventService.publish」。
    // 下游影响：「AgentRunStreamEventService.catchUp(String,Subscription)」向下依次触达 「findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「subscription.actor」、「subscription.lastSequence」、「requireVisibleRun」；计算结果以「boolean」交给调用方。
    // 系统意义：「AgentRunStreamEventService.catchUp(String,Subscription)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private boolean catchUp(String runId, Subscription subscription) {
        synchronized (subscription) {
            try {
                // 权限在每次追赶时重新校验，而不是只在建立 SSE 时校验。
                // 长连接期间案件参与关系变化后，旧连接不能继续读取新事件。
                requireVisibleRun(runId, subscription.actor());
                eventRepository
                        .findAllByAgentRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                runId, subscription.lastSequence().get())
                        .stream()
                        .map(this::view)
                        .forEach(event -> sendLocked(subscription, event));
                return true;
            } catch (RuntimeException failure) {
                removeDisconnected(runId, subscription, failure);
                return false;
            }
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.sendLocked(Subscription,AgentRunEventView)」。
    // 具体功能：「AgentRunStreamEventService.sendLocked(Subscription,AgentRunEventView)」：按严格递增序号向单个 SseEmitter 发送事件并更新游标；重复序号被跳过，final/error 发送后正常关闭，最终返回「void」。
    // 上游调用：「AgentRunStreamEventService.sendLocked(Subscription,AgentRunEventView)」的上游调用点包括 「AgentRunStreamEventService.catchUp」。
    // 下游影响：「AgentRunStreamEventService.sendLocked(Subscription,AgentRunEventView)」向下依次触达 「SseEmitter.event」、「event.sequence」、「subscription.lastSequence」、「subscription.emitter」。
    // 系统意义：「AgentRunStreamEventService.sendLocked(Subscription,AgentRunEventView)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private void sendLocked(Subscription subscription, AgentRunEventView event) {
        if (event.sequence() <= subscription.lastSequence().get()) {
            return;
        }
        try {
            subscription.emitter().send(
                    SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name(event.type())
                            .data(event));
            subscription.lastSequence().set(event.sequence());
            if ("final".equals(event.type()) || "error".equals(event.type())) {
                subscription.emitter().complete();
            }
        } catch (IOException failure) {
            throw new EventDeliveryException(failure);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.visibleTo(AgentRunEntity,CaseAccessSessionEntity)」。
    // 具体功能：「AgentRunStreamEventService.visibleTo(AgentRunEntity,CaseAccessSessionEntity)」：把 Run 的角色和 actorId 受众 JSON 与当前 CaseAccessSession 比对，系统角色仍需通过权限服务判定，防止知道 runId 的其他案件参与人订阅模型输出，最终返回「boolean」。
    // 上游调用：「AgentRunStreamEventService.visibleTo(AgentRunEntity,CaseAccessSessionEntity)」的上游调用点包括 「AgentRunStreamEventService.requireVisibleRun」。
    // 下游影响：「AgentRunStreamEventService.visibleTo(AgentRunEntity,CaseAccessSessionEntity)」向下依次触达 「permissionService.canReadActorAudience」、「accessSession.getActorRole」、「objectMapper.readValue」、「run.getStreamAudienceJson」；计算结果以「boolean」交给调用方。
    // 系统意义：「AgentRunStreamEventService.visibleTo(AgentRunEntity,CaseAccessSessionEntity)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private boolean visibleTo(
            AgentRunEntity run, CaseAccessSessionEntity accessSession) {
        if (accessSession.getActorRole() == ActorRole.ADMIN
                || accessSession.getActorRole() == ActorRole.SYSTEM) {
            return true;
        }
        try {
            List<String> roles =
                    objectMapper.readValue(
                            run.getStreamAudienceJson(), new TypeReference<>() {});
            if (!roles.isEmpty() && !roles.contains(accessSession.getActorRole().name())) {
                return false;
            }
            List<String> actorIds =
                    objectMapper.readValue(
                            run.getStreamAudienceActorIdsJson(), new TypeReference<>() {});
            return permissionService.canReadActorAudience(accessSession, actorIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid agent run audience", exception);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.view(AgentRunStreamEventEntity)」。
    // 具体功能：「AgentRunStreamEventService.view(AgentRunStreamEventEntity)」：解析视图：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」、「entity.getPayloadJson」、「entity.getAgentRunId」、「entity.getSequenceNo」；不满足前置条件时抛出 「IllegalStateException」；处理的关键状态/协议值包括 「schema_version」、「agent_stream.v1」、「operation」、「node_name」，最终返回「AgentRunEventView」。
    // 上游调用：「AgentRunStreamEventService.view(AgentRunStreamEventEntity)」只由「AgentRunStreamEventService」内部流程使用，负责封装“视图”这一步校验、映射或状态转换。
    // 下游影响：「AgentRunStreamEventService.view(AgentRunStreamEventEntity)」向下依次触达 「objectMapper.readTree」、「entity.getPayloadJson」、「entity.getAgentRunId」、「entity.getSequenceNo」；计算结果以「AgentRunEventView」交给调用方。
    // 系统意义：「AgentRunStreamEventService.view(AgentRunStreamEventEntity)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentRunEventView view(AgentRunStreamEventEntity entity) {
        try {
            JsonNode payload = objectMapper.readTree(entity.getPayloadJson());
            return new AgentRunEventView(
                    payload.path("schema_version").asText("agent_stream.v1"),
                    entity.getAgentRunId(),
                    entity.getSequenceNo(),
                    entity.getEventType(),
                    textOrNull(payload.get("operation")),
                    textOrNull(payload.get("node_name")),
                    textOrNull(payload.get("field")),
                    textOrNull(payload.get("delta")),
                    objectOrNull(payload.get("token_usage")),
                    textOrNull(payload.get("model")),
                    longOrNull(payload.get("latency_ms")),
                    objectOrNull(payload.get("response")),
                    textOrNull(payload.get("code")),
                    textOrNull(payload.get("message")),
                    booleanOrNull(payload.get("retryable")),
                    booleanOrNull(payload.get("visible_output_emitted")),
                    entity.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted agent stream event", exception);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.publishAfterCommit(String)」。
    // 具体功能：「AgentRunStreamEventService.publishAfterCommit(String)」：当前存在真实事务时注册 afterCommit 回调，否则立即发布；保证 SSE 永远不会先于事件行提交，最终返回「void」。
    // 上游调用：「AgentRunStreamEventService.publishAfterCommit(String)」的上游调用点包括 「AgentRunStreamEventService.append」。
    // 下游影响：「AgentRunStreamEventService.publishAfterCommit(String)」向下依次触达 「TransactionSynchronizationManager.isActualTransactionActive」、「TransactionSynchronizationManager.registerSynchronization」、「publish.run」、「publish」。
    // 系统意义：「AgentRunStreamEventService.publishAfterCommit(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：new 接口/父类(...) { ... } 创建匿名实现，花括号内的 @Override 方法会在回调时执行。
    private void publishAfterCommit(String runId) {
        Runnable publish = () -> publish(runId);
        // 事件行尚未提交时不能通知 SSE，否则 catchUp 可能查询不到刚发布的 sequence，
        // 浏览器游标却已经前进，最终造成永久漏帧。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.afterCommit()」。
                        // 具体功能：「AgentRunStreamEventService.afterCommit()」：执行之后提交；实际协作者为 「publish.run」，最终返回「void」。
                        // 上游调用：「AgentRunStreamEventService.afterCommit()」由使用「AgentRunStreamEventService」的控制器、应用服务、Workflow Activity 或测试场景触发。
                        // 下游影响：「AgentRunStreamEventService.afterCommit()」向下依次触达 「publish.run」。
                        // 系统意义：「AgentRunStreamEventService.afterCommit()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    });
        } else {
            publish.run();
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.publish(String)」。
    // 具体功能：「AgentRunStreamEventService.publish(String)」：遍历指定 Run 的活跃订阅并执行 catch-up，以数据库事件表而不是进程内消息作为发布事实源，最终返回「void」。
    // 上游调用：「AgentRunStreamEventService.publish(String)」的上游调用点包括 「AgentRunStreamEventService.publishAfterCommit」。
    // 下游影响：「AgentRunStreamEventService.publish(String)」向下依次触达 「subscriptions.getOrDefault」、「catchUp」。
    // 系统意义：「AgentRunStreamEventService.publish(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private void publish(String runId) {
        subscriptions.getOrDefault(runId, new CopyOnWriteArrayList<>())
                .forEach(subscription -> catchUp(runId, subscription));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.remove(String,Subscription)」。
    // 具体功能：「AgentRunStreamEventService.remove(String,Subscription)」：移除Agent 流事件持久化、重放与 SSE 投递，最终返回「void」。
    // 上游调用：「AgentRunStreamEventService.remove(String,Subscription)」的上游调用点包括 「AgentRunStreamEventService.subscribe」、「AgentRunStreamEventService.removeDisconnected」。
    // 下游影响：「AgentRunStreamEventService.remove(String,Subscription)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunStreamEventService.remove(String,Subscription)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private void remove(String runId, Subscription subscription) {
        // Removal and reconnect registration must be serialized for the same runId.
        // Otherwise an old connection can observe an empty list, a reconnect can add itself
        // to that list, and the old connection can then remove the whole map entry.
        subscriptions.computeIfPresent(
                runId,
                (ignored, values) -> {
                    values.remove(subscription);
                    return values.isEmpty() ? null : values;
                });
    }

    private void add(String runId, Subscription subscription) {
        // Keep the list mutation inside the per-key compute operation. Calling
        // computeIfAbsent(...).add(...) would return the existing list before adding to it,
        // allowing a concurrent removal to detach that list from the map in between.
        subscriptions.compute(
                runId,
                (ignored, values) -> {
                    CopyOnWriteArrayList<Subscription> target =
                            values == null ? new CopyOnWriteArrayList<>() : values;
                    target.add(subscription);
                    return target;
                });
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.removeDisconnected(String,Subscription,Throwable)」。
    // 具体功能：「AgentRunStreamEventService.removeDisconnected(String,Subscription,Throwable)」：从 Run 订阅集合移除断开的连接并以原始 I/O 异常结束 emitter，避免心跳持续访问失效客户端，最终返回「void」。
    // 上游调用：「AgentRunStreamEventService.removeDisconnected(String,Subscription,Throwable)」的上游调用点包括 「AgentRunStreamEventService.heartbeat」、「AgentRunStreamEventService.catchUp」。
    // 下游影响：「AgentRunStreamEventService.removeDisconnected(String,Subscription,Throwable)」向下依次触达 「subscription.emitter」、「subscription.emitter().completeWithError」。
    // 系统意义：「AgentRunStreamEventService.removeDisconnected(String,Subscription,Throwable)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private void removeDisconnected(
            String runId, Subscription subscription, Throwable ignoredFailure) {
        remove(runId, subscription);
        try {
            // A failed write normally means the browser closed or replaced the SSE connection.
            // Completing normally avoids dispatching an exception back through an already
            // committed text/event-stream response, while durable replay preserves every event.
            subscription.emitter().complete();
        } catch (RuntimeException ignored) {
            // Servlet container may already have completed the async response.
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.json(Object)」。
    // 具体功能：「AgentRunStreamEventService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「AgentRunStreamEventService.json(Object)」的上游调用点包括 「AgentRunStreamEventService.append」。
    // 下游影响：「AgentRunStreamEventService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunStreamEventService.json(Object)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize agent stream event", exception);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.textOrNull(JsonNode)」。
    // 具体功能：「AgentRunStreamEventService.textOrNull(JsonNode)」：构建文本或者空值；实际协作者为 「node.isNull」、「node.asText」，最终返回「String」。
    // 上游调用：「AgentRunStreamEventService.textOrNull(JsonNode)」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventService.textOrNull(JsonNode)」向下依次触达 「node.isNull」、「node.asText」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunStreamEventService.textOrNull(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("");
        return value.isBlank() ? null : value;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.objectOrNull(JsonNode)」。
    // 具体功能：「AgentRunStreamEventService.objectOrNull(JsonNode)」：构建对象或者空值：先复制 JsonNode，避免下游修改已校验的协议对象；实际协作者为 「node.isObject」、「node.deepCopy」，最终返回「JsonNode」。
    // 上游调用：「AgentRunStreamEventService.objectOrNull(JsonNode)」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventService.objectOrNull(JsonNode)」向下依次触达 「node.isObject」、「node.deepCopy」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「AgentRunStreamEventService.objectOrNull(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static JsonNode objectOrNull(JsonNode node) {
        return node != null && node.isObject() ? node.deepCopy() : null;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.longOrNull(JsonNode)」。
    // 具体功能：「AgentRunStreamEventService.longOrNull(JsonNode)」：构建长整数或者空值；实际协作者为 「node.canConvertToLong」、「node.asLong」，最终返回「Long」。
    // 上游调用：「AgentRunStreamEventService.longOrNull(JsonNode)」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventService.longOrNull(JsonNode)」向下依次触达 「node.canConvertToLong」、「node.asLong」；计算结果以「Long」交给调用方。
    // 系统意义：「AgentRunStreamEventService.longOrNull(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static Long longOrNull(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.asLong() : null;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.booleanOrNull(JsonNode)」。
    // 具体功能：「AgentRunStreamEventService.booleanOrNull(JsonNode)」：判断布尔值或者空值；实际协作者为 「node.isBoolean」、「node.asBoolean」，最终返回「Boolean」。
    // 上游调用：「AgentRunStreamEventService.booleanOrNull(JsonNode)」的上游调用点包括 「AgentRunStreamEventService.view」。
    // 下游影响：「AgentRunStreamEventService.booleanOrNull(JsonNode)」向下依次触达 「node.isBoolean」、「node.asBoolean」；计算结果以「Boolean」交给调用方。
    // 系统意义：「AgentRunStreamEventService.booleanOrNull(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static Boolean booleanOrNull(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.compactUuid()」。
    // 具体功能：「AgentRunStreamEventService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「AgentRunStreamEventService.compactUuid()」的上游调用点包括 「AgentRunStreamEventService.append」。
    // 下游影响：「AgentRunStreamEventService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunStreamEventService.compactUuid()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】类型「Subscription」。
    // 类型职责：定义Subscription跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record Subscription(
            AuthenticatedActor actor, SseEmitter emitter, AtomicLong lastSequence) {}

    // 所属模块：【Agent 流式运行 / 应用编排层】类型「EventDeliveryException」。
    // 类型职责：表达事件投递失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「EventDeliveryException」。
    // 协作关系：主要由 「AgentRunStreamEventService.sendLocked」、「CaseEventService.sendLocked」 使用。
    // 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class EventDeliveryException extends RuntimeException {
        // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunStreamEventService.EventDeliveryException.EventDeliveryException(IOException)」。
        // 具体功能：「AgentRunStreamEventService.EventDeliveryException.EventDeliveryException(IOException)」：把 「cause」(IOException) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
        // 上游调用：「AgentRunStreamEventService.EventDeliveryException.EventDeliveryException(IOException)」的上游创建点包括 「AgentRunStreamEventService.sendLocked」、「CaseEventService.sendLocked」。
        // 下游影响：「AgentRunStreamEventService.EventDeliveryException.EventDeliveryException(IOException)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「AgentRunStreamEventService.EventDeliveryException.EventDeliveryException(IOException)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        private EventDeliveryException(IOException cause) {
            super(cause);
        }
    }
}
