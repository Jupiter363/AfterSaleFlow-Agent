/*
 * 所属模块：房间协作与权限。
 * 文件职责：编排案件事件规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「recordRoomMessage」、「recordLifecycleEvent」、「replay」、「subscribe」、「heartbeat」、「afterCommit」；维护接待室、证据室和小法庭的参与人、不可变消息、会话权限、阶段时钟与 Agent 记忆。
 * 关键边界：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
 */
package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 所属模块：【房间协作与权限 / 应用编排层】类型「CaseEventService」。
// 类型职责：编排案件事件规则、权限校验与事实读写；本类型显式提供 「CaseEventService」、「recordRoomMessage」、「recordLifecycleEvent」、「replay」、「subscribe」、「heartbeat」。
// 协作关系：主要由 「CaseEventController.replay」、「CaseEventController.subscribe」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「EvidenceAgentTurnService.appendAgentMessage」 使用。
// 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class CaseEventService {

    private static final long EMITTER_TIMEOUT_MS = 4 * 60 * 60 * 1000L;

    private final CaseTimelineEventRepository eventRepository;
    private final FulfillmentCaseRepository caseRepository;
    private final CaseParticipantRepository participantRepository;
    private final AccessSessionResolver accessSessionResolver;
    private final SessionPermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, CopyOnWriteArrayList<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.CaseEventService(CaseTimelineEventRepository,FulfillmentCaseRepository,CaseParticipantRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper,Clock)」。
    // 具体功能：「CaseEventService.CaseEventService(CaseTimelineEventRepository,FulfillmentCaseRepository,CaseParticipantRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper,Clock)」：通过构造器接收 「eventRepository」(CaseTimelineEventRepository)、「caseRepository」(FulfillmentCaseRepository)、「participantRepository」(CaseParticipantRepository)、「accessSessionResolver」(AccessSessionResolver)、「permissionService」(SessionPermissionService)、「objectMapper」(ObjectMapper)、「clock」(Clock) 并保存为「CaseEventService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「CaseEventService.CaseEventService(CaseTimelineEventRepository,FulfillmentCaseRepository,CaseParticipantRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper,Clock)」的上游创建点包括 「RoomMessageAndEventServiceTest.setUp」。
    // 下游影响：「CaseEventService.CaseEventService(CaseTimelineEventRepository,FulfillmentCaseRepository,CaseParticipantRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper,Clock)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseEventService.CaseEventService(CaseTimelineEventRepository,FulfillmentCaseRepository,CaseParticipantRepository,AccessSessionResolver,SessionPermissionService,ObjectMapper,Clock)」负责主链路中的“案件事件服务”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public CaseEventService(
            CaseTimelineEventRepository eventRepository,
            FulfillmentCaseRepository caseRepository,
            CaseParticipantRepository participantRepository,
            AccessSessionResolver accessSessionResolver,
            SessionPermissionService permissionService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.caseRepository = caseRepository;
        this.participantRepository = participantRepository;
        this.accessSessionResolver = accessSessionResolver;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.recordRoomMessage(String,String,String,String,String,String,String)」。
    // 具体功能：「CaseEventService.recordRoomMessage(String,String,String,String,String,String,String)」：记录房间消息：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表；实际协作者为 「eventRepository.findMaxSequenceByCaseId」、「eventRepository.save」、「CaseTimelineEventEntity.create」、「clock.instant」；处理的关键状态/协议值包括 「EVENT_」、「ROOM_MESSAGE_CREATED」、「message_id」、「text」，最终返回「CaseTimelineEventEntity」。
    // 上游调用：「CaseEventService.recordRoomMessage(String,String,String,String,String,String,String)」的上游调用点包括 「HearingCourtBootstrapService.appendAgentMessageIfAbsent」、「HearingCourtOrchestrator.persistJudgeTurn」、「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」、「EvidenceAgentTurnService.appendAgentMessage」。
    // 下游影响：「CaseEventService.recordRoomMessage(String,String,String,String,String,String,String)」向下依次触达 「eventRepository.findMaxSequenceByCaseId」、「eventRepository.save」、「CaseTimelineEventEntity.create」、「clock.instant」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseEventService.recordRoomMessage(String,String,String,String,String,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public CaseTimelineEventEntity recordRoomMessage(
            String caseId,
            String roomId,
            String messageId,
            String messageText,
            String audienceJson,
            String audienceActorIdsJson,
            String actorId) {
        lockCase(caseId);
        long sequence = eventRepository.findMaxSequenceByCaseId(caseId) + 1;
        CaseTimelineEventEntity event =
                eventRepository.save(
                        CaseTimelineEventEntity.create(
                                "EVENT_" + compactUuid(),
                                caseId,
                                roomId,
                                sequence,
                                "ROOM_MESSAGE_CREATED",
                                clock.instant(),
                                json(List.of(messageId)),
                                json(Map.of("message_id", messageId, "text", nullToEmpty(messageText))),
                                audienceJson,
                                audienceActorIdsJson,
                                "room-message:" + messageId,
                                actorId));
        publishAfterCommit(caseId);
        return event;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.recordLifecycleEvent(String,String,String,Map,String,String)」。
    // 具体功能：「CaseEventService.recordLifecycleEvent(String,String,String,Map,String,String)」：记录生命周期事件：先由 Spring 事务代理统一提交数据库变化，再把新状态写入 PostgreSQL 事实表；实际协作者为 「eventRepository.findByCaseIdAndEventKey」、「eventRepository.findMaxSequenceByCaseId」、「eventRepository.save」、「CaseTimelineEventEntity.create」；处理的关键状态/协议值包括 「EVENT_」、「[]」，最终返回「CaseTimelineEventEntity」。
    // 上游调用：「CaseEventService.recordLifecycleEvent(String,String,String,Map,String,String)」的上游调用点包括 「EvidenceCompletionService.announceHearingOpened」、「EvidenceDossierRevisionService.recordRevisionEvent」、「HearingCourtOrchestrator.persistJudgeTurn」、「HearingCourtOrchestrator.appendFormalJuryReportIfNeeded」。
    // 下游影响：「CaseEventService.recordLifecycleEvent(String,String,String,Map,String,String)」向下依次触达 「eventRepository.findByCaseIdAndEventKey」、「eventRepository.findMaxSequenceByCaseId」、「eventRepository.save」、「CaseTimelineEventEntity.create」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「CaseEventService.recordLifecycleEvent(String,String,String,Map,String,String)」定义原子提交边界；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public CaseTimelineEventEntity recordLifecycleEvent(
            String caseId,
            String roomId,
            String eventType,
            Map<String, Object> payload,
            String eventKey,
            String actorId) {
        lockCase(caseId);
        return eventRepository
                .findByCaseIdAndEventKey(caseId, eventKey)
                .orElseGet(
                        () -> {
                            long sequence =
                                    eventRepository.findMaxSequenceByCaseId(caseId) + 1;
                            CaseTimelineEventEntity event =
                                    eventRepository.save(
                                            CaseTimelineEventEntity.create(
                                                    "EVENT_" + compactUuid(),
                                                    caseId,
                                                    roomId,
                                                    sequence,
                                                    eventType,
                                                    clock.instant(),
                                                    "[]",
                                                    json(payload),
                                                    "[]",
                                                    "[]",
                                                    eventKey,
                                                    actorId));
                            publishAfterCommit(caseId);
                            return event;
                        });
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.replay(String,long,AuthenticatedActor)」。
    // 具体功能：「CaseEventService.replay(String,long,AuthenticatedActor)」：回放列表；实际协作者为 「findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「assertCanAccess」、「accessSession」、「visibleTo」，最终返回「List<CaseEventView>」。
    // 上游调用：「CaseEventService.replay(String,long,AuthenticatedActor)」的上游调用点包括 「CaseEventController.replay」、「CaseEventControllerTest.replayEndpointReturnsDurableCaseEventsForLedgerRebuild」、「RoomMessageAndEventServiceTest.replayStartsAfterTheCursorAndFiltersMerchantPrivateEventsFromUser」、「RoomMessageAndEventServiceTest.replayFiltersPrivateEventsByExactActorWithinTheSameRole」。
    // 下游影响：「CaseEventService.replay(String,long,AuthenticatedActor)」向下依次触达 「findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「assertCanAccess」、「accessSession」、「visibleTo」；计算结果以「List<CaseEventView>」交给调用方。
    // 系统意义：「CaseEventService.replay(String,long,AuthenticatedActor)」负责主链路中的“列表”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public List<CaseEventView> replay(
            String caseId, long afterSequence, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        CaseAccessSessionEntity accessSession = accessSession(caseId, actor);
        return eventRepository
                .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        caseId, afterSequence)
                .stream()
                .filter(event -> visibleTo(event, accessSession))
                .map(CaseEventService::view)
                .toList();
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.subscribe(String,long,AuthenticatedActor)」。
    // 具体功能：「CaseEventService.subscribe(String,long,AuthenticatedActor)」：订阅SseEmitter：先维持服务端事件连接并支持断线续传；实际协作者为 「subscriptions.computeIfAbsent」、「emitter.onCompletion」、「emitter.onTimeout」、「emitter.onError」，最终返回「SseEmitter」。
    // 上游调用：「CaseEventService.subscribe(String,long,AuthenticatedActor)」的上游调用点包括 「CaseEventController.subscribe」、「RoomAndEventControllerTest.startsAnSseSubscriptionAfterTheLastEventIdCursor」、「RoomMessageAndEventServiceTest.subscriptionCatchesUpFromDurableStorageBeforeDeliveringANewerLiveEvent」。
    // 下游影响：「CaseEventService.subscribe(String,long,AuthenticatedActor)」向下依次触达 「subscriptions.computeIfAbsent」、「emitter.onCompletion」、「emitter.onTimeout」、「emitter.onError」；计算结果以「SseEmitter」交给调用方。
    // 系统意义：「CaseEventService.subscribe(String,long,AuthenticatedActor)」负责主链路中的“SseEmitter”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    public SseEmitter subscribe(
            String caseId, long afterSequence, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        CaseAccessSessionEntity accessSession = accessSession(caseId, actor);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscription subscription =
                new Subscription(accessSession, emitter, new AtomicLong(afterSequence));
        add(caseId, subscription);
        Runnable remove = () -> remove(caseId, subscription);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        catchUp(caseId, subscription);
        return emitter;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.heartbeat()」。
    // 具体功能：「CaseEventService.heartbeat()」：发送心跳案件事件：先维持服务端事件连接并支持断线续传；实际协作者为 「SseEmitter.event」、「subscription.emitter」、「catchUp」、「removeDisconnected」；处理的关键状态/协议值包括 「heartbeat」，最终返回「void」。
    // 上游调用：「CaseEventService.heartbeat()」由 Spring 定时调度器触发；它在固定间隔扫描未收敛记录，不由浏览器直接触发。
    // 下游影响：「CaseEventService.heartbeat()」向下依次触达 「SseEmitter.event」、「subscription.emitter」、「catchUp」、「removeDisconnected」。
    // 系统意义：「CaseEventService.heartbeat()」负责主链路中的“案件事件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Scheduled(fixedDelayString = "${dispute.sse-heartbeat:PT15S}")
    public void heartbeat() {
        subscriptions.forEach(
                (caseId, caseSubscriptions) ->
                        caseSubscriptions.forEach(
                                subscription -> {
                                    if (!catchUp(caseId, subscription)) {
                                        return;
                                    }
                                    try {
                                        synchronized (subscription) {
                                            subscription.emitter().send(
                                                    SseEmitter.event()
                                                            .comment("heartbeat"));
                                        }
                                    } catch (IOException failure) {
                                        removeDisconnected(caseId, subscription, failure);
                                    } catch (RuntimeException failure) {
                                        removeDisconnected(caseId, subscription, failure);
                                    }
                                }));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.publishAfterCommit(String)」。
    // 具体功能：「CaseEventService.publishAfterCommit(String)」：发布之后提交：先注册事务完成回调；实际协作者为 「TransactionSynchronizationManager.isActualTransactionActive」、「TransactionSynchronizationManager.registerSynchronization」、「publish.run」、「publish」，最终返回「void」。
    // 上游调用：「CaseEventService.publishAfterCommit(String)」的上游调用点包括 「CaseEventService.recordRoomMessage」、「CaseEventService.recordLifecycleEvent」。
    // 下游影响：「CaseEventService.publishAfterCommit(String)」向下依次触达 「TransactionSynchronizationManager.isActualTransactionActive」、「TransactionSynchronizationManager.registerSynchronization」、「publish.run」、「publish」。
    // 系统意义：「CaseEventService.publishAfterCommit(String)」负责主链路中的“之后提交”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：new 接口/父类(...) { ... } 创建匿名实现，花括号内的 @Override 方法会在回调时执行。
    private void publishAfterCommit(String caseId) {
        Runnable publish = () -> publish(caseId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.afterCommit()」。
                        // 具体功能：「CaseEventService.afterCommit()」：执行之后提交；实际协作者为 「publish.run」，最终返回「void」。
                        // 上游调用：「CaseEventService.afterCommit()」由使用「CaseEventService」的控制器、应用服务、Workflow Activity 或测试场景触发。
                        // 下游影响：「CaseEventService.afterCommit()」向下依次触达 「publish.run」。
                        // 系统意义：「CaseEventService.afterCommit()」负责主链路中的“之后提交”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    });
        } else {
            publish.run();
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.publish(String)」。
    // 具体功能：「CaseEventService.publish(String)」：发布案件事件；实际协作者为 「subscriptions.getOrDefault」、「catchUp」，最终返回「void」。
    // 上游调用：「CaseEventService.publish(String)」的上游调用点包括 「CaseEventService.publishAfterCommit」。
    // 下游影响：「CaseEventService.publish(String)」向下依次触达 「subscriptions.getOrDefault」、「catchUp」。
    // 系统意义：「CaseEventService.publish(String)」负责主链路中的“案件事件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private void publish(String caseId) {
        subscriptions.getOrDefault(caseId, new CopyOnWriteArrayList<>())
                .forEach(subscription -> catchUp(caseId, subscription));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.catchUp(String,Subscription)」。
    // 具体功能：「CaseEventService.catchUp(String,Subscription)」：追赶积压事件；实际协作者为 「findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「subscription.lastSequence」、「subscription.accessSession」、「visibleTo」，最终返回「boolean」。
    // 上游调用：「CaseEventService.catchUp(String,Subscription)」的上游调用点包括 「CaseEventService.subscribe」、「CaseEventService.heartbeat」、「CaseEventService.publish」。
    // 下游影响：「CaseEventService.catchUp(String,Subscription)」向下依次触达 「findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc」、「subscription.lastSequence」、「subscription.accessSession」、「visibleTo」；计算结果以「boolean」交给调用方。
    // 系统意义：「CaseEventService.catchUp(String,Subscription)」负责主链路中的“积压事件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    private boolean catchUp(String caseId, Subscription subscription) {
        synchronized (subscription) {
            try {
                eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                                caseId, subscription.lastSequence().get())
                        .stream()
                        .filter(event -> visibleTo(event, subscription.accessSession()))
                        .map(CaseEventService::view)
                        .forEach(event -> sendLocked(subscription, event));
                return true;
            } catch (RuntimeException failure) {
                removeDisconnected(caseId, subscription, failure);
                return false;
            }
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.sendLocked(Subscription,CaseEventView)」。
    // 具体功能：「CaseEventService.sendLocked(Subscription,CaseEventView)」：发送持锁事件：先维持服务端事件连接并支持断线续传；实际协作者为 「SseEmitter.event」、「event.sequenceNo」、「subscription.lastSequence」、「subscription.emitter」；不满足前置条件时抛出 「EventDeliveryException」，最终返回「void」。
    // 上游调用：「CaseEventService.sendLocked(Subscription,CaseEventView)」的上游调用点包括 「CaseEventService.catchUp」。
    // 下游影响：「CaseEventService.sendLocked(Subscription,CaseEventView)」向下依次触达 「SseEmitter.event」、「event.sequenceNo」、「subscription.lastSequence」、「subscription.emitter」。
    // 系统意义：「CaseEventService.sendLocked(Subscription,CaseEventView)」负责主链路中的“持锁事件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void sendLocked(Subscription subscription, CaseEventView event) {
        if (event.sequenceNo() <= subscription.lastSequence().get()) {
            return;
        }
        try {
            subscription.emitter().send(
                    SseEmitter.event()
                            .id(Long.toString(event.sequenceNo()))
                            .name(event.eventType())
                            .data(event));
            subscription.lastSequence().set(event.sequenceNo());
        } catch (IOException failure) {
            throw new EventDeliveryException(failure);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.assertCanAccess(String,AuthenticatedActor)」。
    // 具体功能：「CaseEventService.assertCanAccess(String,AuthenticatedActor)」：断言Can访问：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findById」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.role」、「actor.actorId」；不满足前置条件时抛出 「ForbiddenException」，最终返回「void」。
    // 上游调用：「CaseEventService.assertCanAccess(String,AuthenticatedActor)」的上游调用点包括 「CaseEventService.replay」、「CaseEventService.subscribe」。
    // 下游影响：「CaseEventService.assertCanAccess(String,AuthenticatedActor)」向下依次触达 「caseRepository.findById」、「participantRepository.existsByCaseIdAndActorIdAndParticipantRole」、「actor.role」、「actor.actorId」。
    // 系统意义：「CaseEventService.assertCanAccess(String,AuthenticatedActor)」在“Can访问”进入下游前阻断非法状态；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void assertCanAccess(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        boolean privileged =
                switch (actor.role()) {
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        boolean owner =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        caseId, actor.actorId(), actor.role());
        if (!privileged && !owner && !participant) {
            throw new ForbiddenException("actor cannot subscribe to this case");
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.lockCase(String)」。
    // 具体功能：「CaseEventService.lockCase(String)」：加锁案件：先把 Optional 空值转换为明确业务异常；实际协作者为 「caseRepository.findByIdForUpdate」，最终返回「void」。
    // 上游调用：「CaseEventService.lockCase(String)」的上游调用点包括 「CaseEventService.recordRoomMessage」、「CaseEventService.recordLifecycleEvent」。
    // 下游影响：「CaseEventService.lockCase(String)」向下依次触达 「caseRepository.findByIdForUpdate」。
    // 系统意义：「CaseEventService.lockCase(String)」负责主链路中的“案件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private void lockCase(String caseId) {
        caseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.accessSession(String,AuthenticatedActor)」。
    // 具体功能：「CaseEventService.accessSession(String,AuthenticatedActor)」：构建访问会话；实际协作者为 「accessSessionResolver.resolve」、「permissionService.requireCaseRead」，最终返回「CaseAccessSessionEntity」。
    // 上游调用：「CaseEventService.accessSession(String,AuthenticatedActor)」的上游调用点包括 「CaseEventService.replay」、「CaseEventService.subscribe」。
    // 下游影响：「CaseEventService.accessSession(String,AuthenticatedActor)」向下依次触达 「accessSessionResolver.resolve」、「permissionService.requireCaseRead」；计算结果以「CaseAccessSessionEntity」交给调用方。
    // 系统意义：「CaseEventService.accessSession(String,AuthenticatedActor)」负责主链路中的“访问会话”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private CaseAccessSessionEntity accessSession(String caseId, AuthenticatedActor actor) {
        CaseAccessSessionEntity accessSession = accessSessionResolver.resolve(caseId, actor);
        permissionService.requireCaseRead(accessSession);
        return accessSession;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.visibleTo(CaseTimelineEventEntity,CaseAccessSessionEntity)」。
    // 具体功能：「CaseEventService.visibleTo(CaseTimelineEventEntity,CaseAccessSessionEntity)」：判断可见性案件事件；实际协作者为 「permissionService.canReadActorAudience」、「accessSession.getActorRole」、「objectMapper.readValue」、「event.getAudienceJson」；不满足前置条件时抛出 「IllegalStateException」，最终返回「boolean」。
    // 上游调用：「CaseEventService.visibleTo(CaseTimelineEventEntity,CaseAccessSessionEntity)」的上游调用点包括 「CaseEventService.replay」、「CaseEventService.catchUp」。
    // 下游影响：「CaseEventService.visibleTo(CaseTimelineEventEntity,CaseAccessSessionEntity)」向下依次触达 「permissionService.canReadActorAudience」、「accessSession.getActorRole」、「objectMapper.readValue」、「event.getAudienceJson」；计算结果以「boolean」交给调用方。
    // 系统意义：「CaseEventService.visibleTo(CaseTimelineEventEntity,CaseAccessSessionEntity)」负责主链路中的“案件事件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private boolean visibleTo(CaseTimelineEventEntity event, CaseAccessSessionEntity accessSession) {
        if (accessSession.getActorRole() == ActorRole.ADMIN
                || accessSession.getActorRole() == ActorRole.SYSTEM) {
            return true;
        }
        try {
            List<String> audiences =
                    objectMapper.readValue(
                            event.getAudienceJson(), new TypeReference<>() {});
            if (!audiences.isEmpty() && !audiences.contains(accessSession.getActorRole().name())) {
                return false;
            }
            List<String> audienceActorIds =
                    objectMapper.readValue(
                            event.getAudienceActorIdsJson(), new TypeReference<>() {});
            return permissionService.canReadActorAudience(accessSession, audienceActorIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid event audience", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.remove(String,Subscription)」。
    // 具体功能：「CaseEventService.remove(String,Subscription)」：移除案件事件，最终返回「void」。
    // 上游调用：「CaseEventService.remove(String,Subscription)」的上游调用点包括 「CaseEventService.subscribe」、「CaseEventService.removeDisconnected」。
    // 下游影响：「CaseEventService.remove(String,Subscription)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「CaseEventService.remove(String,Subscription)」负责主链路中的“案件事件”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void remove(String caseId, Subscription subscription) {
        subscriptions.computeIfPresent(
                caseId,
                (ignored, caseSubscriptions) -> {
                    caseSubscriptions.remove(subscription);
                    return caseSubscriptions.isEmpty() ? null : caseSubscriptions;
                });
    }

    private void add(String caseId, Subscription subscription) {
        subscriptions.compute(
                caseId,
                (ignored, caseSubscriptions) -> {
                    CopyOnWriteArrayList<Subscription> target =
                            caseSubscriptions == null
                                    ? new CopyOnWriteArrayList<>()
                                    : caseSubscriptions;
                    target.add(subscription);
                    return target;
                });
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.removeDisconnected(String,Subscription,Throwable)」。
    // 具体功能：「CaseEventService.removeDisconnected(String,Subscription,Throwable)」：移除断开连接的订阅；实际协作者为 「subscription.emitter」、「subscription.emitter().completeWithError」，最终返回「void」。
    // 上游调用：「CaseEventService.removeDisconnected(String,Subscription,Throwable)」的上游调用点包括 「CaseEventService.heartbeat」、「CaseEventService.catchUp」。
    // 下游影响：「CaseEventService.removeDisconnected(String,Subscription,Throwable)」向下依次触达 「subscription.emitter」、「subscription.emitter().completeWithError」。
    // 系统意义：「CaseEventService.removeDisconnected(String,Subscription,Throwable)」负责主链路中的“断开连接的订阅”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private void removeDisconnected(
            String caseId, Subscription subscription, Throwable ignoredFailure) {
        remove(caseId, subscription);
        try {
            // A failed write normally means the browser closed or replaced the SSE connection.
            // Complete normally so the global JSON exception handler does not write into an
            // already committed text/event-stream response. Durable replay preserves events.
            subscription.emitter().complete();
        } catch (RuntimeException ignored) {
            // The servlet container may already have closed the response. At that point the
            // important part is that the stale subscription has been removed so future
            // heartbeats do not keep surfacing the same disconnect.
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.json(Object)」。
    // 具体功能：「CaseEventService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「CaseEventService.json(Object)」的上游调用点包括 「CaseEventService.recordRoomMessage」、「CaseEventService.recordLifecycleEvent」。
    // 下游影响：「CaseEventService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「CaseEventService.json(Object)」统一“JSON”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize case event", exception);
        }
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.view(CaseTimelineEventEntity)」。
    // 具体功能：「CaseEventService.view(CaseTimelineEventEntity)」：构建视图；实际协作者为 「event.getSequenceNo」、「event.getEventType」、「event.getRoomId」、「event.getEventJson」，最终返回「CaseEventView」。
    // 上游调用：「CaseEventService.view(CaseTimelineEventEntity)」只由「CaseEventService」内部流程使用，负责封装“视图”这一步校验、映射或状态转换。
    // 下游影响：「CaseEventService.view(CaseTimelineEventEntity)」向下依次触达 「event.getSequenceNo」、「event.getEventType」、「event.getRoomId」、「event.getEventJson」；计算结果以「CaseEventView」交给调用方。
    // 系统意义：「CaseEventService.view(CaseTimelineEventEntity)」统一“视图”的跨层表示，避免不同入口产生不兼容字段；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static CaseEventView view(CaseTimelineEventEntity event) {
        return new CaseEventView(
                event.getSequenceNo(),
                event.getEventType(),
                event.getRoomId(),
                event.getEventJson(),
                event.getEventTime());
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.compactUuid()」。
    // 具体功能：「CaseEventService.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「CaseEventService.compactUuid()」的上游调用点包括 「CaseEventService.recordRoomMessage」、「CaseEventService.recordLifecycleEvent」。
    // 下游影响：「CaseEventService.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「CaseEventService.compactUuid()」负责主链路中的“UUID”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.nullToEmpty(String)」。
    // 具体功能：「CaseEventService.nullToEmpty(String)」：构建空值为空，最终返回「String」。
    // 上游调用：「CaseEventService.nullToEmpty(String)」的上游调用点包括 「CaseEventService.recordRoomMessage」。
    // 下游影响：「CaseEventService.nullToEmpty(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「CaseEventService.nullToEmpty(String)」负责主链路中的“空值为空”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // 所属模块：【房间协作与权限 / 应用编排层】类型「Subscription」。
    // 类型职责：定义Subscription跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
    // 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
    private record Subscription(
            CaseAccessSessionEntity accessSession, SseEmitter emitter, AtomicLong lastSequence) {}

    // 所属模块：【房间协作与权限 / 应用编排层】类型「EventDeliveryException」。
    // 类型职责：表达事件投递失败语义，使上层能够区分业务拒绝、协议错误和可重试故障；本类型显式提供 「EventDeliveryException」。
    // 协作关系：主要由 「AgentRunStreamEventService.sendLocked」、「CaseEventService.sendLocked」 使用。
    // 边界意义：每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class EventDeliveryException extends RuntimeException {
        // 所属模块：【房间协作与权限 / 应用编排层】「CaseEventService.EventDeliveryException.EventDeliveryException(IOException)」。
        // 具体功能：「CaseEventService.EventDeliveryException.EventDeliveryException(IOException)」：把 「cause」(IOException) 交给父异常保存错误链；构造过程不执行日志、重试或业务补偿。
        // 上游调用：「CaseEventService.EventDeliveryException.EventDeliveryException(IOException)」的上游创建点包括 「AgentRunStreamEventService.sendLocked」、「CaseEventService.sendLocked」。
        // 下游影响：「CaseEventService.EventDeliveryException.EventDeliveryException(IOException)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
        // 系统意义：「CaseEventService.EventDeliveryException.EventDeliveryException(IOException)」负责主链路中的“事件投递异常”；每次读取和写入都要绑定案件参与关系、角色、房间和受众范围
        // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
        private EventDeliveryException(IOException cause) {
            super(cause);
        }
    }
}
