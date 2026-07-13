/*
 * 所属模块：Agent 流式运行。
 * 文件职责：编排AgentRun 状态机和事件落库规则、权限校验与事实读写。
 * 业务链路：核心入口/契约为 「claim」、「recordNonTerminalFrame」、「complete」、「failFromAgent」、「failInfrastructure」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunLifecycleService」。
// 类型职责：编排AgentRun 状态机和事件落库规则、权限校验与事实读写；本类型显式提供 「AgentRunLifecycleService」、「claim」、「recordNonTerminalFrame」、「complete」、「failFromAgent」、「failInfrastructure」。
// 协作关系：主要由 「AgentRunRecoveryScheduler.failStale」、「AgentRunWorker.execute」、「AgentRunWorker.fail」、「AgentRunLifecycleServiceTest.agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AgentRunLifecycleService {

    private static final String SCHEMA_VERSION = "agent_stream.v1";

    private final AgentRunRepository runRepository;
    private final AgentRunStreamEventService eventService;
    private final AgentStreamOperationRegistry operationRegistry;
    private final AgentRunResultPolicy resultPolicy;
    private final AgentRunFinalizerRegistry finalizerRegistry;
    private final ObjectMapper objectMapper;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.AgentRunLifecycleService(AgentRunRepository,AgentRunStreamEventService,AgentStreamOperationRegistry,AgentRunResultPolicy,AgentRunFinalizerRegistry,ObjectMapper)」。
    // 具体功能：「AgentRunLifecycleService.AgentRunLifecycleService(AgentRunRepository,AgentRunStreamEventService,AgentStreamOperationRegistry,AgentRunResultPolicy,AgentRunFinalizerRegistry,ObjectMapper)」：通过构造器接收 「runRepository」(AgentRunRepository)、「eventService」(AgentRunStreamEventService)、「operationRegistry」(AgentStreamOperationRegistry)、「resultPolicy」(AgentRunResultPolicy)、「finalizerRegistry」(AgentRunFinalizerRegistry)、「objectMapper」(ObjectMapper) 并保存为「AgentRunLifecycleService」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunLifecycleService.AgentRunLifecycleService(AgentRunRepository,AgentRunStreamEventService,AgentStreamOperationRegistry,AgentRunResultPolicy,AgentRunFinalizerRegistry,ObjectMapper)」的上游创建点包括 「AgentRunLifecycleServiceTest.setUp」。
    // 下游影响：「AgentRunLifecycleService.AgentRunLifecycleService(AgentRunRepository,AgentRunStreamEventService,AgentStreamOperationRegistry,AgentRunResultPolicy,AgentRunFinalizerRegistry,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunLifecycleService.AgentRunLifecycleService(AgentRunRepository,AgentRunStreamEventService,AgentStreamOperationRegistry,AgentRunResultPolicy,AgentRunFinalizerRegistry,ObjectMapper)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunLifecycleService(
            AgentRunRepository runRepository,
            AgentRunStreamEventService eventService,
            AgentStreamOperationRegistry operationRegistry,
            AgentRunResultPolicy resultPolicy,
            AgentRunFinalizerRegistry finalizerRegistry,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.eventService = eventService;
        this.operationRegistry = operationRegistry;
        this.resultPolicy = resultPolicy;
        this.finalizerRegistry = finalizerRegistry;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.claim(String)」。
    // 具体功能：「AgentRunLifecycleService.claim(String)」：使用数据库行锁只领取 PENDING Run，重新从服务端注册表解析 operation 并核对持久化 endpoint，随后原子标记 RUNNING，返回只含 Worker 执行所需字段的描述对象，最终返回「Optional<AgentRunExecutionDescriptor>」。
    // 上游调用：「AgentRunLifecycleService.claim(String)」的上游调用点包括 「AgentRunWorker.execute」、「AgentRunLifecycleServiceTest.claimMovesPendingRunToRunningAndReturnsExecutionContract」。
    // 下游影响：「AgentRunLifecycleService.claim(String)」向下依次触达 「runRepository.findByIdForUpdate」、「operationRegistry.require」、「Optional.empty」、「System.nanoTime」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunLifecycleService.claim(String)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public Optional<AgentRunExecutionDescriptor> claim(String runId) {
        AgentRunEntity run =
                runRepository
                        .findByIdForUpdate(runId)
                        .orElseThrow(() -> new IllegalArgumentException("agent run not found"));
        if (!"PENDING".equals(run.getRunStatus())) {
            return Optional.empty();
        }
        AgentStreamOperationRegistry.OperationDefinition operation =
                operationRegistry.require(run.getStreamOperation());
        if (!operation.endpoint().equals(run.getStreamEndpoint())) {
            throw new IllegalStateException("persisted agent endpoint does not match registry");
        }
        run.markRunning();
        return Optional.of(
                new AgentRunExecutionDescriptor(
                        run.getId(),
                        run.getCaseId(),
                        run.getRoomId(),
                        run.getStreamOperation(),
                        operation.protocolOperation(),
                        operation.endpoint(),
                        run.getStreamRequestJson(),
                        run.getTraceId(),
                        run.getStreamRequestId(),
                        operation.visibleFieldPaths(),
                        System.nanoTime()));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.recordNonTerminalFrame(String,AgentStreamFrame)」。
    // 具体功能：「AgentRunLifecycleService.recordNonTerminalFrame(String,AgentStreamFrame)」：只接收 start、visible_delta 和 usage：再次校验公开字段白名单，裁剪 token 用量字段，把最小公开事件与 Run 状态在同一事务中追加；final/error 必须走专用终态方法，最终返回「void」。
    // 上游调用：「AgentRunLifecycleService.recordNonTerminalFrame(String,AgentStreamFrame)」的上游调用点包括 「AgentRunWorker.execute」、「AgentRunLifecycleServiceTest.legalVisibleDeltaPersistsOnlyThePublicEnvelopeAndText」、「AgentRunLifecycleServiceTest.nonPublicVisibleDeltaIsRejectedBeforePersistence」。
    // 下游影响：「AgentRunLifecycleService.recordNonTerminalFrame(String,AgentStreamFrame)」向下依次触达 「operationRegistry.require」、「resultPolicy.publicUsage」、「eventService.append」、「frame.event」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunLifecycleService.recordNonTerminalFrame(String,AgentStreamFrame)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void recordNonTerminalFrame(String runId, AgentStreamFrame frame) {
        AgentRunEntity run = requireRunning(runId);
        if ("final".equals(frame.event()) || "error".equals(frame.event())) {
            throw new IllegalArgumentException("terminal frame requires terminal lifecycle method");
        }
        ObjectNode payload = basePayload(runId, frame);
        // 这里故意重新做一次服务端公开字段校验。即使 HTTP 解析器以后被替换，
        // 持久化边界仍不会把 Python 的内部推理字段写入可回放事件表。
        switch (frame.event()) {
            case "start" -> {
                // Envelope fields are sufficient.
            }
            case "visible_delta" -> {
                if (!operationRegistry
                        .require(run.getStreamOperation())
                        .visibleFieldPaths()
                        .contains(frame.fieldPath())) {
                    throw new AgentStreamProtocolException(
                            "agent stream attempted to expose a non-public field");
                }
                payload.put("field", frame.fieldPath());
                payload.put("delta", frame.delta());
            }
            case "usage" -> {
                payload.set("token_usage", resultPolicy.publicUsage(frame.usage()));
                payload.put("model", frame.model());
                payload.put("latency_ms", frame.latencyMs());
            }
            default -> throw new AgentStreamProtocolException("unsupported non-terminal event");
        }
        run.markProgress();
        eventService.append(run, frame.sequence(), frame.event(), payload);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.complete(String,AgentStreamFrame,int,String,long)」。
    // 具体功能：「AgentRunLifecycleService.complete(String,AgentStreamFrame,int,String,long)」：校验 final 响应的操作级 Schema，先由对应 Finalizer 把结果写成房间消息、草案等正式领域事实，再标记 AgentRun 完成并只把公开投影写入 final 事件；任一步失败都会整体回滚，最终返回「void」。
    // 上游调用：「AgentRunLifecycleService.complete(String,AgentStreamFrame,int,String,long)」的上游调用点包括 「AgentRunWorker.execute」、「AgentRunLifecycleServiceTest.completeFinalizesBeforeStateTransitionAndPublishesOnlyPublicProjection」。
    // 下游影响：「AgentRunLifecycleService.complete(String,AgentStreamFrame,int,String,long)」向下依次触达 「resultPolicy.validate」、「finalizerRegistry.finalizeResult」、「resultPolicy.publicProjection」、「eventService.append」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunLifecycleService.complete(String,AgentStreamFrame,int,String,long)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void complete(
            String runId,
            AgentStreamFrame frame,
            int totalTokenUsage,
            String latestModel,
            long latencyMs) {
        if (!"final".equals(frame.event()) || frame.result() == null) {
            throw new IllegalArgumentException("final frame required");
        }
        AgentRunEntity run = requireRunning(runId);
        resultPolicy.validate(run.getStreamOperation(), frame.result());
        // 顺序不能互换：先把模型结果转成房间消息、庭审草案等领域事实，
        // Finalizer 成功后才能把 AgentRun 标记为 COMPLETED 并发布 final SSE。
        // 整个方法处于同一事务中，任一步失败都会回滚，避免“Run 成功但业务结果缺失”。
        finalizerRegistry.finalizeResult(
                new AgentRunFinalizationContext(
                        run.getId(),
                        run.getCaseId(),
                        run.getRoomId(),
                        run.getStreamOperation(),
                        run.getTraceId(),
                        run.getStreamIdempotencyKey(),
                        parseJson(run.getStreamRequestJson())),
                frame.result());
        run.markCompleted(
                json(frame.result()),
                totalTokenUsage,
                latencyMs,
                latestModel);
        ObjectNode payload = basePayload(runId, frame);
        payload.set(
                "response",
                resultPolicy.publicProjection(run.getStreamOperation(), frame.result()));
        eventService.append(run, frame.sequence(), "final", payload);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.failFromAgent(String,AgentStreamFrame,long)」。
    // 具体功能：「AgentRunLifecycleService.failFromAgent(String,AgentStreamFrame,long)」：处理 Python 主动发送的 error 帧：以数据库中是否已有 visible_delta 为准重算可重试性，内部错误详情留在 Run，SSE 事件只公开统一中文提示和受控错误码，最终返回「void」。
    // 上游调用：「AgentRunLifecycleService.failFromAgent(String,AgentStreamFrame,long)」的上游调用点包括 「AgentRunWorker.execute」、「AgentRunLifecycleServiceTest.agentFailureUsesTheSameSanitizedPublicErrorContract」、「AgentRunLifecycleServiceTest.agentFailureUsesPersistedEventsInsteadOfStaleUpstreamVisibleFlag」。
    // 下游影响：「AgentRunLifecycleService.failFromAgent(String,AgentStreamFrame,long)」向下依次触达 「eventService.hasVisibleOutput」、「eventService.append」、「frame.event」、「frame.error」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunLifecycleService.failFromAgent(String,AgentStreamFrame,long)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void failFromAgent(
            String runId, AgentStreamFrame frame, long latencyMs) {
        if (!"error".equals(frame.event()) || frame.error() == null) {
            throw new IllegalArgumentException("error frame required");
        }
        AgentRunEntity run = requireNonTerminal(runId);
        // 可重试性以数据库中是否已有 visible_delta 为事实源，而不是信任 Python 帧里的声明。
        // 用户已经看见部分文本后自动重跑可能产生第二份相互矛盾的回答，因此必须转人工/显式重试。
        boolean visibleOutputEmitted = eventService.hasVisibleOutput(runId);
        boolean retryable = frame.error().retryable() && !visibleOutputEmitted;
        run.markFailed(
                safeCode(frame.error().code()),
                frame.error().message(),
                retryable,
                latencyMs);
        ObjectNode payload = basePayload(runId, frame);
        putPublicError(
                payload,
                frame.error().code(),
                retryable,
                visibleOutputEmitted);
        eventService.append(run, frame.sequence(), "error", payload);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.failInfrastructure(String,String,String,boolean,boolean,long)」。
    // 具体功能：「AgentRunLifecycleService.failInfrastructure(String,String,String,boolean,boolean,long)」：把网络中断、协议异常或超时收敛为单一 error 终态；若已有可见输出则强制不可自动重试，并使用持久化最大序号生成下一事件，避免恢复任务覆盖既有序列，最终返回「void」。
    // 上游调用：「AgentRunLifecycleService.failInfrastructure(String,String,String,boolean,boolean,long)」的上游调用点包括 「AgentRunRecoveryScheduler.failStale」、「AgentRunWorker.fail」、「AgentRunLifecycleServiceTest.visibleOutputMakesInfrastructureFailureNonRetryableAndPublicErrorIsSanitized」。
    // 下游影响：「AgentRunLifecycleService.failInfrastructure(String,String,String,boolean,boolean,long)」向下依次触达 「eventService.hasVisibleOutput」、「eventService.nextSequence」、「eventService.append」、「run.isTerminal」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunLifecycleService.failInfrastructure(String,String,String,boolean,boolean,long)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public void failInfrastructure(
            String runId,
            String code,
            String internalMessage,
            boolean retryable,
            boolean visibleOutputEmitted,
            long latencyMs) {
        AgentRunEntity run = requireNonTerminal(runId);
        if (run.isTerminal()) {
            return;
        }
        boolean effectiveVisibleOutput =
                visibleOutputEmitted || eventService.hasVisibleOutput(runId);
        // 基础设施调用方可能持有过期的“尚未输出”判断，所以再次与事件表合并。
        boolean effectiveRetryable = retryable && !effectiveVisibleOutput;
        run.markFailed(
                safeCode(code),
                safeInternalMessage(internalMessage),
                effectiveRetryable,
                latencyMs);
        long sequence = eventService.nextSequence(runId);
        ObjectNode payload = basePayload(runId, sequence, "error", null);
        putPublicError(payload, code, effectiveRetryable, effectiveVisibleOutput);
        eventService.append(run, sequence, "error", payload);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.requireRunning(String)」。
    // 具体功能：「AgentRunLifecycleService.requireRunning(String)」：读取非终态 Run 并要求状态严格为 RUNNING，阻止迟到帧写入尚未领取或已经结束的运行，最终返回「AgentRunEntity」。
    // 上游调用：「AgentRunLifecycleService.requireRunning(String)」的上游调用点包括 「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunLifecycleService.complete」。
    // 下游影响：「AgentRunLifecycleService.requireRunning(String)」向下依次触达 「run.getRunStatus」、「requireNonTerminal」；计算结果以「AgentRunEntity」交给调用方。
    // 系统意义：「AgentRunLifecycleService.requireRunning(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private AgentRunEntity requireRunning(String runId) {
        AgentRunEntity run = requireNonTerminal(runId);
        if (!"RUNNING".equals(run.getRunStatus())) {
            throw new IllegalStateException("agent run is not running");
        }
        return run;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.requireNonTerminal(String)」。
    // 具体功能：「AgentRunLifecycleService.requireNonTerminal(String)」：按 Run ID 加行锁读取实体，允许调用方判断终态但禁止不存在的运行被静默创建，最终返回「AgentRunEntity」。
    // 上游调用：「AgentRunLifecycleService.requireNonTerminal(String)」的上游调用点包括 「AgentRunLifecycleService.failFromAgent」、「AgentRunLifecycleService.failInfrastructure」、「AgentRunLifecycleService.requireRunning」。
    // 下游影响：「AgentRunLifecycleService.requireNonTerminal(String)」向下依次触达 「runRepository.findByIdForUpdate」；计算结果以「AgentRunEntity」交给调用方。
    // 系统意义：「AgentRunLifecycleService.requireNonTerminal(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private AgentRunEntity requireNonTerminal(String runId) {
        return runRepository
                .findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("agent run not found"));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.basePayload(String,AgentStreamFrame)」。
    // 具体功能：「AgentRunLifecycleService.basePayload(String,AgentStreamFrame)」：提供「basePayload」的便捷重载：接收 「runId」(String)、「frame」(AgentStreamFrame)，补齐默认选项后委托参数更完整的同名方法，保证两条入口共享同一套校验、事务和持久化逻辑。
    // 上游调用：「AgentRunLifecycleService.basePayload(String,AgentStreamFrame)」的上游调用点包括 「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunLifecycleService.complete」、「AgentRunLifecycleService.failFromAgent」、「AgentRunLifecycleService.failInfrastructure」。
    // 下游影响：「AgentRunLifecycleService.basePayload(String,AgentStreamFrame)」向下依次触达 「frame.sequence」、「frame.event」、「frame.nodeName」、「basePayload」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「AgentRunLifecycleService.basePayload(String,AgentStreamFrame)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private ObjectNode basePayload(String runId, AgentStreamFrame frame) {
        return basePayload(runId, frame.sequence(), frame.event(), frame.nodeName());
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.basePayload(String,long,String,String)」。
    // 具体功能：「AgentRunLifecycleService.basePayload(String,long,String,String)」：构建base载荷：先按主键读取现有事实并显式处理不存在分支，再把 Optional 空值转换为明确业务异常；实际协作者为 「operationRegistry.require」、「runRepository.findById」、「objectMapper.createObjectNode」、「limit」；处理的关键状态/协议值包括 「schema_version」、「run_id」、「sequence」、「type」，最终返回「ObjectNode」。
    // 上游调用：「AgentRunLifecycleService.basePayload(String,long,String,String)」的上游调用点包括 「AgentRunLifecycleService.recordNonTerminalFrame」、「AgentRunLifecycleService.complete」、「AgentRunLifecycleService.failFromAgent」、「AgentRunLifecycleService.failInfrastructure」。
    // 下游影响：「AgentRunLifecycleService.basePayload(String,long,String,String)」向下依次触达 「operationRegistry.require」、「runRepository.findById」、「objectMapper.createObjectNode」、「limit」；计算结果以「ObjectNode」交给调用方。
    // 系统意义：「AgentRunLifecycleService.basePayload(String,long,String,String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    private ObjectNode basePayload(
            String runId, long sequence, String event, String nodeName) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("run_id", runId);
        payload.put("sequence", sequence);
        payload.put("type", event);
        if (nodeName != null && !nodeName.isBlank()) {
            payload.put("node_name", limit(nodeName, 128));
        }
        if ("start".equals(event) || "final".equals(event)) {
            payload.put(
                    "operation",
                    operationRegistry.require(
                                    runRepository
                                            .findById(runId)
                                            .orElseThrow(
                                                    () -> new IllegalArgumentException(
                                                            "agent run not found"))
                                            .getStreamOperation())
                            .protocolOperation());
        }
        return payload;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.putPublicError(ObjectNode,String,boolean,boolean)」。
    // 具体功能：「AgentRunLifecycleService.putPublicError(ObjectNode,String,boolean,boolean)」：向 SSE error 载荷写入经过白名单化的错误码、固定对外提示、重试标志和是否已输出可见文本，不泄露 Python 堆栈或内部异常消息，最终返回「void」。
    // 上游调用：「AgentRunLifecycleService.putPublicError(ObjectNode,String,boolean,boolean)」的上游调用点包括 「AgentRunLifecycleService.failFromAgent」、「AgentRunLifecycleService.failInfrastructure」。
    // 下游影响：「AgentRunLifecycleService.putPublicError(ObjectNode,String,boolean,boolean)」向下依次触达 「safeCode」。
    // 系统意义：「AgentRunLifecycleService.putPublicError(ObjectNode,String,boolean,boolean)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private void putPublicError(
            ObjectNode payload,
            String code,
            boolean retryable,
            boolean visibleOutputEmitted) {
        payload.put("code", safeCode(code));
        payload.put("message", "数字人响应生成失败，请稍后重试。");
        payload.put("retryable", retryable);
        payload.put("visible_output_emitted", visibleOutputEmitted);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.json(Object)」。
    // 具体功能：「AgentRunLifecycleService.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalStateException」，最终返回「String」。
    // 上游调用：「AgentRunLifecycleService.json(Object)」的上游调用点包括 「AgentRunLifecycleService.complete」。
    // 下游影响：「AgentRunLifecycleService.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunLifecycleService.json(Object)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize agent run result", exception);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.parseJson(String)」。
    // 具体功能：「AgentRunLifecycleService.parseJson(String)」：解析JSON：先把 JSON 文本解析为可逐字段校验的 JsonNode；实际协作者为 「objectMapper.readTree」；不满足前置条件时抛出 「IllegalStateException」，最终返回「JsonNode」。
    // 上游调用：「AgentRunLifecycleService.parseJson(String)」的上游调用点包括 「AgentRunLifecycleService.complete」。
    // 下游影响：「AgentRunLifecycleService.parseJson(String)」向下依次触达 「objectMapper.readTree」；计算结果以「JsonNode」交给调用方。
    // 系统意义：「AgentRunLifecycleService.parseJson(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted agent run request", exception);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.safeCode(String)」。
    // 具体功能：「AgentRunLifecycleService.safeCode(String)」：只允许长度不超过 128 的字母数字及点、横线、下划线错误码；其他值统一降级为 AGENT_STREAM_FAILED，最终返回「String」。
    // 上游调用：「AgentRunLifecycleService.safeCode(String)」的上游调用点包括 「AgentRunLifecycleService.failFromAgent」、「AgentRunLifecycleService.failInfrastructure」、「AgentRunLifecycleService.putPublicError」。
    // 下游影响：「AgentRunLifecycleService.safeCode(String)」向下依次触达 「value.matches」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunLifecycleService.safeCode(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String safeCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}")) {
            return "AGENT_STREAM_FAILED";
        }
        return value;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.safeInternalMessage(String)」。
    // 具体功能：「AgentRunLifecycleService.safeInternalMessage(String)」：把空内部错误替换为固定说明，并把非空消息截到 4096 字符后仅存入受保护的 Run 记录，最终返回「String」。
    // 上游调用：「AgentRunLifecycleService.safeInternalMessage(String)」的上游调用点包括 「AgentRunLifecycleService.failInfrastructure」。
    // 下游影响：「AgentRunLifecycleService.safeInternalMessage(String)」向下依次触达 「limit」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunLifecycleService.safeInternalMessage(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String safeInternalMessage(String value) {
        if (value == null || value.isBlank()) {
            return "agent stream failed";
        }
        return limit(value, 4096);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunLifecycleService.limit(String,int)」。
    // 具体功能：「AgentRunLifecycleService.limit(String,int)」：限制长度字符串，最终返回「String」。
    // 上游调用：「AgentRunLifecycleService.limit(String,int)」的上游调用点包括 「AgentRunLifecycleService.basePayload」、「AgentRunLifecycleService.safeInternalMessage」。
    // 下游影响：「AgentRunLifecycleService.limit(String,int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunLifecycleService.limit(String,int)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
