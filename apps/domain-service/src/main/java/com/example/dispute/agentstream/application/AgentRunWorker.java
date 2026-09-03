/*
 * 所属模块：Agent 流式运行。
 * 文件职责：从持久化队列领取单次 AgentRun 后台消费与终态收敛，驱动后台任务直到成功或可诊断失败。
 * 业务链路：核心入口/契约为 「execute」；把 Java 发起的运行请求转换为 Python NDJSON 流，并把可公开增量、用量和终态持久化后推送给前端。
 * 关键边界：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
 */
package com.example.dispute.agentstream.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 所属模块：【Agent 流式运行 / 应用编排层】类型「AgentRunWorker」。
// 类型职责：从持久化队列领取单次 AgentRun 后台消费与终态收敛，驱动后台任务直到成功或可诊断失败；本类型显式提供 「AgentRunWorker」、「execute」、「fail」、「elapsedMillis」、「totalTokens」。
// 协作关系：主要由 「AgentRunRecoveryScheduler.recoverPendingRuns」 使用。
// 边界意义：运行必须绑定案件、房间和受众；任何协议越界都要在内容公开前终止
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class AgentRunWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunWorker.class);

    private final AgentRunLifecycleService lifecycleService;
    private final AgentStreamClient streamClient;

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunWorker.AgentRunWorker(AgentRunLifecycleService,AgentStreamClient)」。
    // 具体功能：「AgentRunWorker.AgentRunWorker(AgentRunLifecycleService,AgentStreamClient)」：通过构造器接收 「lifecycleService」(AgentRunLifecycleService)、「streamClient」(AgentStreamClient) 并保存为「AgentRunWorker」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「AgentRunWorker.AgentRunWorker(AgentRunLifecycleService,AgentStreamClient)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供。
    // 下游影响：「AgentRunWorker.AgentRunWorker(AgentRunLifecycleService,AgentStreamClient)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「AgentRunWorker.AgentRunWorker(AgentRunLifecycleService,AgentStreamClient)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public AgentRunWorker(
            AgentRunLifecycleService lifecycleService,
            AgentStreamClient streamClient) {
        this.lifecycleService = lifecycleService;
        this.streamClient = streamClient;
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunWorker.execute(String)」。
    // 具体功能：「AgentRunWorker.execute(String)」：领取 PENDING Run 后消费 NDJSON：非终态帧逐个事务落库，usage 累计模型与 Token，final 进入领域 Finalizer，error 进入失败状态机；协议/传输异常统一交给 fail 收敛，最终返回「void」。
    // 上游调用：「AgentRunWorker.execute(String)」的上游调用点包括 「AgentRunRecoveryScheduler.recoverPendingRuns」。
    // 下游影响：「AgentRunWorker.execute(String)」向下依次触达 「lifecycleService.claim」、「lifecycleService.complete」、「lifecycleService.failFromAgent」、「lifecycleService.recordNonTerminalFrame」。
    // 系统意义：「AgentRunWorker.execute(String)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    // Java 语法：Optional 表示结果可能不存在；orElseThrow 会把空值分支转换为明确异常。
    public void execute(String runId) {
        Optional<AgentRunExecutionDescriptor> claimed = lifecycleService.claim(runId);
        if (claimed.isEmpty()) {
            return;
        }
        AgentRunExecutionDescriptor run = claimed.orElseThrow();
        AtomicLong totalTokenUsage = new AtomicLong();
        AtomicReference<String> latestModel = new AtomicReference<>();
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicBoolean visibleOutputEmitted = new AtomicBoolean(false);
        try {
            streamClient.stream(
                    run,
                    frame -> {
                        if ("usage".equals(frame.event())) {
                            totalTokenUsage.addAndGet(totalTokens(frame.usage()));
                            latestModel.set(frame.model());
                        }
                        if ("visible_delta".equals(frame.event())) {
                            visibleOutputEmitted.set(true);
                        }
                        if ("final".equals(frame.event())) {
                            lifecycleService.complete(
                                    runId,
                                    frame,
                                    (int)
                                            Math.min(
                                                    Integer.MAX_VALUE,
                                                    totalTokenUsage.get()),
                                    latestModel.get(),
                                    elapsedMillis(run));
                            terminal.set(true);
                        } else if ("error".equals(frame.event())) {
                            lifecycleService.failFromAgent(
                                    runId, frame, elapsedMillis(run));
                            terminal.set(true);
                        } else {
                            lifecycleService.recordNonTerminalFrame(runId, frame);
                        }
                    });
            if (!terminal.get()) {
                throw new AgentStreamProtocolException(
                        "stream client returned without a terminal event");
            }
        } catch (AgentStreamTransportException failure) {
            fail(
                    run,
                    "AGENT_STREAM_TRANSPORT_FAILED",
                    failure,
                    failure.retryable() && !visibleOutputEmitted.get(),
                    visibleOutputEmitted.get());
        } catch (AgentStreamProtocolException failure) {
            fail(
                    run,
                    "AGENT_STREAM_PROTOCOL_INVALID",
                    failure,
                    false,
                    visibleOutputEmitted.get());
        } catch (RuntimeException failure) {
            fail(
                    run,
                    "AGENT_STREAM_FINALIZATION_FAILED",
                    failure,
                    false,
                    visibleOutputEmitted.get());
        }
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunWorker.fail(AgentRunExecutionDescriptor,String,RuntimeException,boolean,boolean)」。
    // 具体功能：「AgentRunWorker.fail(AgentRunExecutionDescriptor,String,RuntimeException,boolean,boolean)」：区分可重试传输错误与不可重试协议错误，并把当前延迟和已输出事实交给生命周期服务；若失败收敛本身异常则记录双重故障，避免后台线程静默退出，最终返回「void」。
    // 上游调用：「AgentRunWorker.fail(AgentRunExecutionDescriptor,String,RuntimeException,boolean,boolean)」的上游调用点包括 「AgentRunWorker.execute」。
    // 下游影响：「AgentRunWorker.fail(AgentRunExecutionDescriptor,String,RuntimeException,boolean,boolean)」向下依次触达 「lifecycleService.failInfrastructure」、「LOGGER.warn」、「run.runId」、「run.caseId」。
    // 系统意义：「AgentRunWorker.fail(AgentRunExecutionDescriptor,String,RuntimeException,boolean,boolean)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private void fail(
            AgentRunExecutionDescriptor run,
            String code,
            RuntimeException failure,
            boolean retryable,
            boolean visibleOutputEmitted) {
        LOGGER.warn(
                "Agent stream run failed closed: run_id={}, case_id={}, operation={}, failure_type={}, message={}",
                run.runId(),
                run.caseId(),
                run.operation(),
                failure.getClass().getName(),
                failure.getMessage(),
                failure);
        lifecycleService.failInfrastructure(
                run.runId(),
                code,
                failure.getMessage(),
                retryable,
                visibleOutputEmitted,
                elapsedMillis(run));
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunWorker.elapsedMillis(AgentRunExecutionDescriptor)」。
    // 具体功能：「AgentRunWorker.elapsedMillis(AgentRunExecutionDescriptor)」：构建耗时毫秒；实际协作者为 「Math.max」、「System.nanoTime」、「run.startedAtNanos」，最终返回「long」。
    // 上游调用：「AgentRunWorker.elapsedMillis(AgentRunExecutionDescriptor)」的上游调用点包括 「AgentRunWorker.execute」、「AgentRunWorker.fail」。
    // 下游影响：「AgentRunWorker.elapsedMillis(AgentRunExecutionDescriptor)」向下依次触达 「Math.max」、「System.nanoTime」、「run.startedAtNanos」；计算结果以「long」交给调用方。
    // 系统意义：「AgentRunWorker.elapsedMillis(AgentRunExecutionDescriptor)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static long elapsedMillis(AgentRunExecutionDescriptor run) {
        return Math.max(0, (System.nanoTime() - run.startedAtNanos()) / 1_000_000L);
    }

    // 所属模块：【Agent 流式运行 / 应用编排层】「AgentRunWorker.totalTokens(JsonNode)」。
    // 具体功能：「AgentRunWorker.totalTokens(JsonNode)」：转换总计Token 数；实际协作者为 「Math.max」、「usage.isObject」、「usage.path("total").asLong」、「usage.path("total_tokens").asLong」；处理的关键状态/协议值包括 「total」、「total_tokens」、「input」、「output」，最终返回「long」。
    // 上游调用：「AgentRunWorker.totalTokens(JsonNode)」的上游调用点包括 「AgentRunWorker.execute」。
    // 下游影响：「AgentRunWorker.totalTokens(JsonNode)」向下依次触达 「Math.max」、「usage.isObject」、「usage.path("total").asLong」、「usage.path("total_tokens").asLong」；计算结果以「long」交给调用方。
    // 系统意义：「AgentRunWorker.totalTokens(JsonNode)」位于模型输出的信任边界，决定哪些内容可持久化和对前端可见，并保证断线后能够按序回放。
    private static long totalTokens(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return 0L;
        }
        long total = usage.path("total").asLong(-1L);
        if (total < 0) total = usage.path("total_tokens").asLong(-1L);
        if (total < 0) {
            total =
                    Math.max(
                            usage.path("input").asLong(0L)
                                    + usage.path("output").asLong(0L),
                            usage.path("prompt_tokens").asLong(0L)
                                    + usage.path("completion_tokens").asLong(0L));
        }
        return Math.max(0L, total);
    }
}
