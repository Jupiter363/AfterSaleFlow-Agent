/*
 * 所属模块：Agent 流式运行。
 * 文件职责：定时扫描Agent运行恢复的超时或中断状态并触发幂等恢复。
 * 业务链路：核心入口/契约为 「recoverPendingRuns」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.config.AgentRunV2Properties;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunRecoveryScheduler」。
// 类型职责：定时扫描Agent运行恢复的超时或中断状态并触发幂等恢复；本类型显式提供 「AgentRunRecoveryScheduler」、「recoverPendingRuns」、「failStale」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AgentRunRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AgentRunRecoveryScheduler.class);
    private static final String LEGACY_DETECTION_SQL = """
            select count(*) as candidate_count,
                   count(*) filter (where run_status = 'PENDING') as pending_count,
                   count(*) filter (where run_status = 'RUNNING') as running_count
              from agent_run
             where protocol = 'agent_stream.v1'
               and executor_kind = 'LEGACY_WORKER'
               and stream_operation is not null
               and run_status in ('PENDING', 'RUNNING')
            """;

    private final AgentRunRepository runRepository;
    private final AgentRunWorker worker;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunStreamEventService eventService;
    private final PostCommitSideEffectExecutor executor;
    private final JdbcTemplate detectorJdbcTemplate;
    private final AgentRunV2RecoveryService v2RecoveryService;
    private final AgentRunV2Coordinator v2Coordinator;
    private final SchedulerMode schedulerMode;
    private final long staleAfterMillis;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunRecoveryScheduler.AgentRunRecoveryScheduler(AgentRunRepository,AgentRunWorker,AgentRunLifecycleService,AgentRunStreamEventService,PostCommitSideEffectExecutor,AppProperties)」。
    // 具体功能：「AgentRunRecoveryScheduler.AgentRunRecoveryScheduler(AgentRunRepository,AgentRunWorker,AgentRunLifecycleService,AgentRunStreamEventService,PostCommitSideEffectExecutor,AppProperties)」：通过构造器接收 「runRepository」(AgentRunRepository)、「worker」(AgentRunWorker)、「lifecycleService」(AgentRunLifecycleService)、「eventService」(AgentRunStreamEventService)、「executor」(PostCommitSideEffectExecutor)、「properties」(AppProperties) 并保存为「AgentRunRecoveryScheduler」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunRecoveryScheduler.AgentRunRecoveryScheduler(AgentRunRepository,AgentRunWorker,AgentRunLifecycleService,AgentRunStreamEventService,PostCommitSideEffectExecutor,AppProperties)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「AgentRunRecoveryScheduler.AgentRunRecoveryScheduler(AgentRunRepository,AgentRunWorker,AgentRunLifecycleService,AgentRunStreamEventService,PostCommitSideEffectExecutor,AppProperties)」向下依次触达 「Math.max」、「properties.agent」、「properties.agent().timeoutMs」。
    // 系统意义：「AgentRunRecoveryScheduler.AgentRunRecoveryScheduler(AgentRunRepository,AgentRunWorker,AgentRunLifecycleService,AgentRunStreamEventService,PostCommitSideEffectExecutor,AppProperties)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunRecoveryScheduler(
            AgentRunRepository runRepository,
            AgentRunWorker worker,
            AgentRunLifecycleService lifecycleService,
            AgentRunStreamEventService eventService,
            PostCommitSideEffectExecutor executor,
            AppProperties properties,
            AgentRunV2Properties v2Properties,
            JdbcTemplate detectorJdbcTemplate,
            AgentRunV2RecoveryService v2RecoveryService,
            AgentRunV2Coordinator v2Coordinator) {
        this.runRepository = runRepository;
        this.worker = worker;
        this.lifecycleService = lifecycleService;
        this.eventService = eventService;
        this.executor = executor;
        this.detectorJdbcTemplate = detectorJdbcTemplate;
        this.v2RecoveryService = v2RecoveryService;
        this.v2Coordinator = v2Coordinator;
        this.schedulerMode = v2Properties.schedulerMode();
        this.staleAfterMillis = Math.max(30_000L, properties.agent().timeoutMs() + 30_000L);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunRecoveryScheduler.recoverPendingRuns()」。
    // 具体功能：「AgentRunRecoveryScheduler.recoverPendingRuns()」：恢复待处理Runs；实际协作者为 「findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc」、「executor.execute」、「worker.execute」、「run.getId」；处理的关键状态/协议值包括 「PENDING」、「agent-stream-recovery」、「run_id」、「RUNNING」，最终返回「void」。
    // 上游调用：「AgentRunRecoveryScheduler.recoverPendingRuns()」由 Spring 定时调度器触发；它在固定间隔扫描未收敛记录，不由浏览器直接触发。
    // 下游影响：「AgentRunRecoveryScheduler.recoverPendingRuns()」向下依次触达 「findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc」、「executor.execute」、「worker.execute」、「run.getId」。
    // 系统意义：「AgentRunRecoveryScheduler.recoverPendingRuns()」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    @Scheduled(fixedDelayString = "${dispute.agent-run-recovery-delay:PT5S}")
    public void recoverPendingRuns() {
        if (schedulerMode == SchedulerMode.OFF) {
            return;
        }
        if (schedulerMode == SchedulerMode.DETECTOR) {
            detectLegacyOwnedRuns();
            if (v2RecoveryService.isRecoveryConfigured()) {
                recoverV2Attempts();
            }
            return;
        }

        runRepository
                .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                        AgentRunProtocol.V1.wireValue(),
                        AgentRunExecutorKind.LEGACY_WORKER,
                        "PENDING")
                .forEach(
                        run ->
                                executor.execute(
                                        "agent-stream-recovery",
                                        Map.of("run_id", run.getId()),
                                        () -> worker.execute(run.getId())));
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minusNanos(staleAfterMillis * 1_000_000L);
        runRepository
                .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                        AgentRunProtocol.V1.wireValue(),
                        AgentRunExecutorKind.LEGACY_WORKER,
                        "RUNNING")
                .stream()
                .filter(
                        run -> {
                            OffsetDateTime lastProgressAt = run.getUpdatedAt();
                            if (lastProgressAt == null) {
                                lastProgressAt = run.getStartedAt();
                            }
                            return lastProgressAt != null
                                    && lastProgressAt.isBefore(staleBefore);
                        })
                .forEach(this::failStale);
    }

    private void detectLegacyOwnedRuns() {
        Map<String, Object> counts = detectorJdbcTemplate.queryForMap(LEGACY_DETECTION_SQL);
        AgentRunDetection detection = AgentRunDetection.fromCounts(counts);
        LOGGER.info(
                "AgentRun legacy ownership detector completed a read-only full scan: candidates={}, pending={}, running={}, evidence_hash={}",
                detection.candidateCount(),
                detection.pendingCount(),
                detection.runningCount(),
                detection.evidenceHash());
    }

    private void recoverV2Attempts() {
        for (String status : List.of("PENDING", "RUNNING")) {
            runRepository
                    .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                            AgentRunProtocol.V3.wireValue(),
                            AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                            status)
                    .forEach(
                            run ->
                                    executor.execute(
                                            "agent-run-v2-recovery",
                                            Map.of("run_id", run.getId(), "run_status", status),
                                            () -> v2RecoveryService
                                                    .prepare(run.getId())
                                                    .ifPresent(v2Coordinator::dispatchAllocatedAttempt)));
        }
    }

    private record AgentRunDetection(
            long candidateCount, long pendingCount, long runningCount, String evidenceHash) {

        private static AgentRunDetection fromCounts(Map<String, Object> counts) {
            long candidates = count(counts, "candidate_count");
            long pending = count(counts, "pending_count");
            long running = count(counts, "running_count");
            if (candidates != pending + running) {
                throw new IllegalStateException(
                        "AgentRun detector aggregate is incomplete or inconsistent");
            }
            ObjectNode evidence = JsonNodeFactory.instance.objectNode();
            evidence.put("schema_version", "agent-run-scheduler-detection.v1");
            evidence.put("authority", "DOMAIN_POSTGRESQL_AGENT_RUN");
            evidence.put("candidate_scope", "V1_LEGACY_WORKER_PENDING_OR_RUNNING");
            evidence.put("candidate_count", candidates);
            evidence.put("pending_count", pending);
            evidence.put("running_count", running);
            return new AgentRunDetection(
                    candidates, pending, running, ContractJson.sha256Hex(evidence));
        }

        private static long count(Map<String, Object> counts, String field) {
            Object value = counts.get(field);
            if (!(value instanceof Number number) || number.longValue() < 0) {
                throw new IllegalStateException(
                        "AgentRun detector returned no complete " + field);
            }
            return number.longValue();
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunRecoveryScheduler.failStale(AgentRunEntity)」。
    // 具体功能：「AgentRunRecoveryScheduler.failStale(AgentRunEntity)」：标记失败陈旧；实际协作者为 「eventService.hasVisibleOutput」、「lifecycleService.failInfrastructure」、「run.getId」；处理的关键状态/协议值包括 「AGENT_STREAM_TIMEOUT」，最终返回「void」。
    // 上游调用：「AgentRunRecoveryScheduler.failStale(AgentRunEntity)」只由「AgentRunRecoveryScheduler」内部流程使用，负责封装“陈旧”这一步校验、映射或状态转换。
    // 下游影响：「AgentRunRecoveryScheduler.failStale(AgentRunEntity)」向下依次触达 「eventService.hasVisibleOutput」、「lifecycleService.failInfrastructure」、「run.getId」。
    // 系统意义：「AgentRunRecoveryScheduler.failStale(AgentRunEntity)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private void failStale(AgentRunEntity run) {
        boolean visibleOutputEmitted = eventService.hasVisibleOutput(run.getId());
        lifecycleService.failInfrastructure(
                run.getId(),
                "AGENT_STREAM_TIMEOUT",
                "agent stream exceeded configured timeout",
                !visibleOutputEmitted,
                visibleOutputEmitted,
                staleAfterMillis);
    }
}
