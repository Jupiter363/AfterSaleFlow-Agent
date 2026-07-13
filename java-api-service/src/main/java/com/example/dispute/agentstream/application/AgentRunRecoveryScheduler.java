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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunRecoveryScheduler」。
// 类型职责：定时扫描Agent运行恢复的超时或中断状态并触发幂等恢复；本类型显式提供 「AgentRunRecoveryScheduler」、「recoverPendingRuns」、「failStale」。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AgentRunRecoveryScheduler {

    private final AgentRunRepository runRepository;
    private final AgentRunWorker worker;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunStreamEventService eventService;
    private final PostCommitSideEffectExecutor executor;
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
            AppProperties properties) {
        this.runRepository = runRepository;
        this.worker = worker;
        this.lifecycleService = lifecycleService;
        this.eventService = eventService;
        this.executor = executor;
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
        runRepository
                .findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc("PENDING")
                .forEach(
                        run ->
                                executor.execute(
                                        "agent-stream-recovery",
                                        Map.of("run_id", run.getId()),
                                        () -> worker.execute(run.getId())));
        OffsetDateTime staleBefore =
                OffsetDateTime.now(ZoneOffset.UTC).minusNanos(staleAfterMillis * 1_000_000L);
        runRepository
                .findTop20ByRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc("RUNNING")
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
