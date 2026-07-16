/*
 * 所属模块：Agent 流式运行。
 * 文件职责：协调Agent 运行受理、幂等键与后台执行的事务提交、异步信号和阶段交接。
 * 业务链路：核心入口/契约为 「start」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunCoordinator」。
// 类型职责：协调Agent 运行受理、幂等键与后台执行的事务提交、异步信号和阶段交接；本类型显式提供 「AgentRunCoordinator」、「start」、「validate」、「accepted」、「json」、「sha256」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「EvidenceAgentTurnService.startStreamingRun」、「HearingCourtOrchestrator.startStreamingJudgeTurn」、「IntakeAgentTurnService.startStreamingRun」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Service
public class AgentRunCoordinator {

    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;
    private static final String RETRY_SUFFIX = ":attempt-";

    private final AgentRunRepository runRepository;
    private final FulfillmentCaseRepository caseRepository;
    private final AgentStreamOperationRegistry operationRegistry;
    private final ObjectProvider<AgentRunWorker> workerProvider;
    private final PostCommitSideEffectExecutor postCommitExecutor;
    private final AgentRunStreamEventService eventService;
    private final ObjectMapper objectMapper;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.AgentRunCoordinator(AgentRunRepository,FulfillmentCaseRepository,AgentStreamOperationRegistry,ObjectProvider,PostCommitSideEffectExecutor,ObjectMapper)」。
    // 具体功能：「AgentRunCoordinator.AgentRunCoordinator(AgentRunRepository,FulfillmentCaseRepository,AgentStreamOperationRegistry,ObjectProvider,PostCommitSideEffectExecutor,ObjectMapper)」：通过构造器接收 「runRepository」(AgentRunRepository)、「caseRepository」(FulfillmentCaseRepository)、「operationRegistry」(AgentStreamOperationRegistry)、「workerProvider」(ObjectProvider)、「postCommitExecutor」(PostCommitSideEffectExecutor)、「objectMapper」(ObjectMapper) 并保存为「AgentRunCoordinator」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunCoordinator.AgentRunCoordinator(AgentRunRepository,FulfillmentCaseRepository,AgentStreamOperationRegistry,ObjectProvider,PostCommitSideEffectExecutor,ObjectMapper)」的上游创建点包括 「AgentRunCoordinatorTest.setUp」。
    // 下游影响：「AgentRunCoordinator.AgentRunCoordinator(AgentRunRepository,FulfillmentCaseRepository,AgentStreamOperationRegistry,ObjectProvider,PostCommitSideEffectExecutor,ObjectMapper)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunCoordinator.AgentRunCoordinator(AgentRunRepository,FulfillmentCaseRepository,AgentStreamOperationRegistry,ObjectProvider,PostCommitSideEffectExecutor,ObjectMapper)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunCoordinator(
            AgentRunRepository runRepository,
            FulfillmentCaseRepository caseRepository,
            AgentStreamOperationRegistry operationRegistry,
            ObjectProvider<AgentRunWorker> workerProvider,
            PostCommitSideEffectExecutor postCommitExecutor,
            AgentRunStreamEventService eventService,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.caseRepository = caseRepository;
        this.operationRegistry = operationRegistry;
        this.workerProvider = workerProvider;
        this.postCommitExecutor = postCommitExecutor;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.start(AgentRunStartCommand)」。
    // 具体功能：「AgentRunCoordinator.start(AgentRunStartCommand)」：在案件行锁内校验服务端操作白名单与幂等键：相同指纹返回既有 Run，不同指纹报冲突，同案件/房间/操作者已有 PENDING 或 RUNNING 时拒绝并发；新 Run 落库后通过事务后执行器启动 Worker，最终返回「AgentRunAcceptedView」。
    // 上游调用：「AgentRunCoordinator.start(AgentRunStartCommand)」的上游调用点包括 「InternalAgentRunController.start」、「HearingCourtOrchestrator.startStreamingJudgeTurn」、「ReviewCopilotStreamService.query」、「EvidenceAgentTurnService.startStreamingRun」。
    // 下游影响：「AgentRunCoordinator.start(AgentRunStartCommand)」向下依次触达 「caseRepository.findByIdForUpdate」、「operationRegistry.require」、「runRepository.findByCaseIdAndStreamIdempotencyKey」、「findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc」；这些数据库变化在方法正常返回后由 Spring 统一提交，异常会触发回滚。
    // 系统意义：「AgentRunCoordinator.start(AgentRunStartCommand)」定义原子提交边界；运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
    // Java 语法：@Transactional 由 Spring 代理拦截；只有通过代理调用时才会开启或加入事务。
    @Transactional
    public AgentRunAcceptedView start(AgentRunStartCommand command) {
        validate(command);
        caseRepository
                .findByIdForUpdate(command.caseId())
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        AgentStreamOperationRegistry.OperationDefinition operation =
                operationRegistry.require(command.operation());
        String requestJson = json(command.request());
        String requestHash = canonicalRequestHash(command.request());
        var existing =
                runRepository.findByCaseIdAndStreamIdempotencyKey(
                        command.caseId(), command.idempotencyKey());
        if (existing.isPresent()) {
            AgentRunEntity run = existing.orElseThrow();
            assertSameFingerprint(run, operation.operation(), requestHash);
            return accepted(run);
        }
        var inFlight =
                runRepository
                        .findFirstByCaseIdAndRoomIdAndStreamOperationAndCreatedByAndRunStatusInOrderByCreatedAtDesc(
                                command.caseId(),
                                command.roomId(),
                                operation.operation(),
                                command.actorId(),
                                List.of("PENDING", "RUNNING"));
        if (inFlight.isPresent()) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "agent response is still being generated",
                    Map.of(
                            "reason", "AGENT_RUN_IN_PROGRESS",
                            "agent_run_id", inFlight.orElseThrow().getId(),
                            "operation", operation.operation()));
        }

        String runId = "AGENT_RUN_" + compactUuid();
        AgentRunEntity run =
                runRepository.save(
                        AgentRunEntity.streamingPending(
                                runId,
                                command.caseId(),
                                command.roomId(),
                                operation.operation(),
                                operation.endpoint(),
                                operation.agentRole(),
                                requestJson,
                                requestHash,
                                json(command.audienceRoles()),
                                json(command.audienceActorIds()),
                                command.idempotencyKey(),
                                command.traceId(),
                                command.requestId(),
                                command.actorId()));
        postCommitExecutor.execute(
                "agent-stream-run",
                Map.of(
                        "run_id", runId,
                        "case_id", command.caseId(),
                        "operation", operation.operation()),
                () -> workerProvider.getObject().execute(runId));
        return accepted(run);
    }

    /**
     * Reuses a run whose idempotency key already identifies a server-owned logical task.
     *
     * <p>This lookup is intentionally separate from {@link #start(AgentRunStartCommand)}. Normal
     * write requests must still compare request fingerprints. Callers such as evidence-room
     * opening generation use this method before rebuilding a time-sensitive context envelope, so
     * a repeated page initialization resumes the first accepted run instead of presenting a
     * different request under the same key.
     */
    @Transactional(readOnly = true)
    public Optional<AgentRunAcceptedView> findAcceptedByIdempotencyKey(
            String caseId, String idempotencyKey, String expectedOperation) {
        if (blank(caseId) || blank(idempotencyKey) || blank(expectedOperation)) {
            throw new IllegalArgumentException(
                    "caseId, idempotencyKey and expectedOperation must not be blank");
        }
        return runRepository
                .findByCaseIdAndStreamIdempotencyKey(caseId, idempotencyKey)
                .map(
                        run -> {
                            if (!expectedOperation.equals(run.getStreamOperation())) {
                                throw new IdempotencyConflictException(
                                        "Idempotency-Key was already used for a different agent run");
                            }
                            return accepted(run);
                        });
    }

    /**
     * Creates a new audited attempt for an automatically recoverable failed run.
     *
     * <p>The original terminal run is never rewritten. Ordinary operations require an explicitly
     * retryable transport failure with no visible output. Final hearing convergence may also retry
     * a schema-invalid model response because no adjudication draft was committed. Repeated callers
     * reuse the newest attempt, so Temporal Activity retries and the hearing recovery scheduler
     * cannot fan out duplicate model calls.
     */
    @Transactional
    public AgentRunAcceptedView retryInfrastructureFailure(AgentRunStartCommand command) {
        validate(command);
        caseRepository
                .findByIdForUpdate(command.caseId())
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        AgentStreamOperationRegistry.OperationDefinition operation =
                operationRegistry.require(command.operation());
        String requestJson = json(command.request());
        String requestHash = canonicalRequestHash(command.request());
        List<AgentRunEntity> attempts =
                runRepository
                        .findAllByCaseIdAndStreamIdempotencyKeyStartingWithOrderByCreatedAtAsc(
                                command.caseId(), command.idempotencyKey())
                        .stream()
                        .filter(run -> isAttemptOf(run, command.idempotencyKey()))
                        .toList();
        if (attempts.isEmpty()) {
            throw new IllegalStateException("original agent run is missing");
        }
        AgentRunEntity latest = attempts.get(attempts.size() - 1);
        assertSameFingerprint(latest, operation.operation(), requestHash);
        if (!"FAILED".equals(latest.getRunStatus()) || !recoverableAutomaticFailure(latest)) {
            return accepted(latest);
        }
        int nextAttempt = attemptNumber(latest.getStreamIdempotencyKey(), command.idempotencyKey()) + 1;
        if (nextAttempt > MAX_AUTOMATIC_ATTEMPTS) {
            return accepted(latest);
        }
        String retryKey = command.idempotencyKey() + RETRY_SUFFIX + nextAttempt;
        if (retryKey.length() > 128) {
            throw new IllegalArgumentException("agent retry idempotency key exceeds 128 characters");
        }
        AgentRunEntity retry =
                runRepository.save(
                        AgentRunEntity.streamingPending(
                                "AGENT_RUN_" + compactUuid(),
                                command.caseId(),
                                command.roomId(),
                                operation.operation(),
                                operation.endpoint(),
                                operation.agentRole(),
                                requestJson,
                                requestHash,
                                json(command.audienceRoles()),
                                json(command.audienceActorIds()),
                                retryKey,
                                command.traceId(),
                                command.requestId() + "_ATTEMPT_" + nextAttempt,
                                command.actorId()));
        dispatch(retry, operation.operation());
        return accepted(retry);
    }

    @Transactional(readOnly = true)
    public boolean hasRecoverableInfrastructureFailure(String caseId, String baseIdempotencyKey) {
        List<AgentRunEntity> attempts =
                runRepository
                        .findAllByCaseIdAndStreamIdempotencyKeyStartingWithOrderByCreatedAtAsc(
                                caseId, baseIdempotencyKey)
                        .stream()
                        .filter(run -> isAttemptOf(run, baseIdempotencyKey))
                        .toList();
        if (attempts.isEmpty()) {
            return true;
        }
        AgentRunEntity latest = attempts.get(attempts.size() - 1);
        if ("PENDING".equals(latest.getRunStatus()) || "RUNNING".equals(latest.getRunStatus())) {
            return false;
        }
        if (!"FAILED".equals(latest.getRunStatus()) || !recoverableInfrastructureFailure(latest)) {
            return false;
        }
        return attemptNumber(latest.getStreamIdempotencyKey(), baseIdempotencyKey)
                < MAX_AUTOMATIC_ATTEMPTS;
    }

    private boolean recoverableAutomaticFailure(AgentRunEntity run) {
        if (recoverableInfrastructureFailure(run)) {
            return true;
        }
        if (!"AGENT_OUTPUT_SCHEMA_INVALID".equals(run.getErrorCode())) {
            return false;
        }
        return switch (run.getStreamOperation()) {
            case "HEARING_JUDGE_V1", "HEARING_JURY_REVIEW", "HEARING_JUDGE_V2" -> true;
            default -> false;
        };
    }

    private boolean recoverableInfrastructureFailure(AgentRunEntity run) {
        boolean infrastructureFailure =
                "AGENT_STREAM_TIMEOUT".equals(run.getErrorCode())
                        || "AGENT_STREAM_TRANSPORT_FAILED".equals(run.getErrorCode())
                        || "AGENT_SERVICE_UNAVAILABLE".equals(run.getErrorCode());
        if (!infrastructureFailure) {
            return false;
        }
        return Boolean.TRUE.equals(run.getErrorRetryable())
                && !eventService.hasVisibleOutput(run.getId());
    }

    private static boolean isAttemptOf(AgentRunEntity run, String baseKey) {
        String key = run.getStreamIdempotencyKey();
        if (baseKey.equals(key)) {
            return true;
        }
        if (key == null || !key.startsWith(baseKey + RETRY_SUFFIX)) {
            return false;
        }
        try {
            return Integer.parseInt(key.substring((baseKey + RETRY_SUFFIX).length())) >= 2;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int attemptNumber(String key, String baseKey) {
        if (baseKey.equals(key)) {
            return 1;
        }
        return Integer.parseInt(key.substring((baseKey + RETRY_SUFFIX).length()));
    }

    private void assertSameFingerprint(
            AgentRunEntity run, String operation, String requestHash) {
        assertSameOperation(run, operation);
        if (!sameRequestFingerprint(run, requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used for a different agent run");
        }
    }

    private boolean sameRequestFingerprint(AgentRunEntity run, String requestHash) {
        if (requestHash.equals(run.getStreamRequestHash())) {
            return true;
        }
        try {
            return requestHash.equals(
                    canonicalRequestHash(objectMapper.readTree(run.getStreamRequestJson())));
        } catch (JsonProcessingException failure) {
            return false;
        }
    }

    private static void assertSameOperation(AgentRunEntity run, String operation) {
        if (!operation.equals(run.getStreamOperation())) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used for a different agent run");
        }
    }

    private void dispatch(AgentRunEntity run, String operation) {
        postCommitExecutor.execute(
                "agent-stream-run",
                Map.of(
                        "run_id", run.getId(),
                        "case_id", run.getCaseId(),
                        "operation", operation),
                () -> workerProvider.getObject().execute(run.getId()));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.validate(AgentRunStartCommand)」。
    // 具体功能：「AgentRunCoordinator.validate(AgentRunStartCommand)」：校验启动命令中的 caseId、operation、request、幂等键、Trace/Request ID、创建者和受众列表，确保运行身份与协议元数据在写入 AgentRun 前完整，最终返回「void」。
    // 上游调用：「AgentRunCoordinator.validate(AgentRunStartCommand)」的上游调用点包括 「AgentRunCoordinator.start」。
    // 下游影响：「AgentRunCoordinator.validate(AgentRunStartCommand)」向下依次触达 「command.caseId」、「command.operation」、「command.request」、「command.idempotencyKey」。
    // 系统意义：「AgentRunCoordinator.validate(AgentRunStartCommand)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static void validate(AgentRunStartCommand command) {
        if (command == null
                || blank(command.caseId())
                || blank(command.operation())
                || command.request() == null
                || !command.request().isObject()
                || blank(command.idempotencyKey())
                || command.idempotencyKey().length() > 128
                || blank(command.traceId())
                || blank(command.requestId())
                || blank(command.actorId())) {
            throw new IllegalArgumentException("invalid agent run start command");
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.accepted(AgentRunEntity)」。
    // 具体功能：「AgentRunCoordinator.accepted(AgentRunEntity)」：把 AgentRun 实体映射为 202 Accepted 投影；startedAt 尚未产生时回退 createdAt，供调用方立即轮询或订阅，最终返回「AgentRunAcceptedView」。
    // 上游调用：「AgentRunCoordinator.accepted(AgentRunEntity)」的上游调用点包括 「AgentRunCoordinator.start」。
    // 下游影响：「AgentRunCoordinator.accepted(AgentRunEntity)」向下依次触达 「run.getCreatedAt」、「run.getStartedAt」、「run.getId」、「run.getRunStatus」；计算结果以「AgentRunAcceptedView」交给调用方。
    // 系统意义：「AgentRunCoordinator.accepted(AgentRunEntity)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static AgentRunAcceptedView accepted(AgentRunEntity run) {
        OffsetDateTime createdAt =
                run.getCreatedAt() == null ? run.getStartedAt() : run.getCreatedAt();
        return new AgentRunAcceptedView(
                run.getId(),
                run.getRunStatus(),
                "/api/agent-runs/" + run.getId() + "/events",
                createdAt);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.json(Object)」。
    // 具体功能：「AgentRunCoordinator.json(Object)」：序列化JSON：先把结构化对象序列化为稳定 JSON；实际协作者为 「objectMapper.writeValueAsString」；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「String」。
    // 上游调用：「AgentRunCoordinator.json(Object)」的上游调用点包括 「AgentRunCoordinator.start」。
    // 下游影响：「AgentRunCoordinator.json(Object)」向下依次触达 「objectMapper.writeValueAsString」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunCoordinator.json(Object)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("agent run request is not serializable", exception);
        }
    }

    private String canonicalRequestHash(JsonNode request) {
        return sha256(json(canonicalize(request)));
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            var names = new ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            names.forEach(name -> canonical.set(name, canonicalize(value.get(name))));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            value.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        return value.deepCopy();
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.sha256(String)」。
    // 具体功能：「AgentRunCoordinator.sha256(String)」：对规范化运行请求计算 SHA-256 指纹，用来判断同一幂等键的重试是否真的是同一份请求，最终返回「String」。
    // 上游调用：「AgentRunCoordinator.sha256(String)」的上游调用点包括 「AgentRunCoordinator.start」。
    // 下游影响：「AgentRunCoordinator.sha256(String)」向下依次触达 「MessageDigest.getInstance」、「digest.digest」、「HexFormat.of().formatHex」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunCoordinator.sha256(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.blank(String)」。
    // 具体功能：「AgentRunCoordinator.blank(String)」：判断空白值，最终返回「boolean」。
    // 上游调用：「AgentRunCoordinator.blank(String)」的上游调用点包括 「AgentRunCoordinator.validate」。
    // 下游影响：「AgentRunCoordinator.blank(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「AgentRunCoordinator.blank(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunCoordinator.compactUuid()」。
    // 具体功能：「AgentRunCoordinator.compactUuid()」：压缩表示UUID；实际协作者为 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；处理的关键状态/协议值包括 「-」，最终返回「String」。
    // 上游调用：「AgentRunCoordinator.compactUuid()」的上游调用点包括 「AgentRunCoordinator.start」。
    // 下游影响：「AgentRunCoordinator.compactUuid()」向下依次触达 「UUID.randomUUID」、「UUID.randomUUID().toString().replace」；计算结果以「String」交给调用方。
    // 系统意义：「AgentRunCoordinator.compactUuid()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
