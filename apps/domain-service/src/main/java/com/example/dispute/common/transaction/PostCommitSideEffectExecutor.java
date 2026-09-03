/*
 * 所属模块：后端公共边界。
 * 文件职责：在受控边界内执行事务提交后的异步副作用并隔离失败副作用。
 * 业务链路：核心入口/契约为 「execute」、「afterCommit」；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.transaction;

import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 所属模块：【后端公共边界 / 核心业务层】类型「PostCommitSideEffectExecutor」。
// 类型职责：在受控边界内执行事务提交后的异步副作用并隔离失败副作用；本类型显式提供 「PostCommitSideEffectExecutor」、「execute」、「afterCommit」、「dispatch」、「runGuarded」。
// 协作关系：主要由 「AgentRunCoordinator.start」、「AgentRunRecoveryScheduler.recoverPendingRuns」、「EvidenceWindowCoordinator.signalPartyCompletedAfterCommit」、「EvidenceWindowCoordinator.startAfterCommit」 使用。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class PostCommitSideEffectExecutor {

    static final String EXECUTOR_BEAN_NAME = "postCommitSideEffectTaskExecutor";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PostCommitSideEffectExecutor.class);

    private final Executor sideEffectExecutor;

    // 所属模块：【后端公共边界 / 核心业务层】「PostCommitSideEffectExecutor.PostCommitSideEffectExecutor(Executor)」。
    // 具体功能：「PostCommitSideEffectExecutor.PostCommitSideEffectExecutor(Executor)」：通过构造器接收 「sideEffectExecutor」(Executor) 并保存为「PostCommitSideEffectExecutor」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「PostCommitSideEffectExecutor.PostCommitSideEffectExecutor(Executor)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「AgentRunCoordinatorTest.setUp」、「DisputeImportServiceTest.setUp」 显式创建。
    // 下游影响：「PostCommitSideEffectExecutor.PostCommitSideEffectExecutor(Executor)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PostCommitSideEffectExecutor.PostCommitSideEffectExecutor(Executor)」负责主链路中的“事务后提交副作用执行器”；公共组件不得暗含具体案件裁决规则
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public PostCommitSideEffectExecutor(
            @Qualifier(EXECUTOR_BEAN_NAME) Executor sideEffectExecutor) {
        this.sideEffectExecutor = sideEffectExecutor;
    }

    // 所属模块：【后端公共边界 / 核心业务层】「PostCommitSideEffectExecutor.execute(String,Map,Runnable)」。
    // 具体功能：「PostCommitSideEffectExecutor.execute(String,Map,Runnable)」：把副作用包装为可分派任务：当前没有事务同步时立即提交；存在 Spring 事务时注册 afterCommit 回调，确保通知、Agent 启动等动作只在业务事实成功提交后发生，最终返回「void」。
    // 上游调用：「PostCommitSideEffectExecutor.execute(String,Map,Runnable)」的上游调用点包括 「AgentRunCoordinator.start」、「AgentRunRecoveryScheduler.recoverPendingRuns」、「ExternalCaseImportTransactionService.startIntakeIfNeeded」、「HearingCourtOrchestrator.afterRoundOpenedAfterCommit」。
    // 下游影响：「PostCommitSideEffectExecutor.execute(String,Map,Runnable)」向下依次触达 「TransactionSynchronizationManager.isSynchronizationActive」、「TransactionSynchronizationManager.registerSynchronization」、「dispatch.run」、「dispatch」。
    // 系统意义：「PostCommitSideEffectExecutor.execute(String,Map,Runnable)」负责主链路中的“事务提交后的异步副作用”；公共组件不得暗含具体案件裁决规则
    // Java 语法：new 接口/父类(...) { ... } 创建匿名实现，花括号内的 @Override 方法会在回调时执行。
    public void execute(
            String operation,
            Map<String, ?> context,
            Runnable action) {
        Runnable dispatch = () -> dispatch(operation, context, action);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    // 所属模块：【后端公共边界 / 核心业务层】「PostCommitSideEffectExecutor.afterCommit()」。
                    // 具体功能：「PostCommitSideEffectExecutor.afterCommit()」：响应 Spring 的事务成功提交回调，调用预先捕获的 dispatch；回滚路径不会进入这里，最终返回「void」。
                    // 上游调用：「PostCommitSideEffectExecutor.afterCommit()」由使用「PostCommitSideEffectExecutor」的控制器、应用服务、Workflow Activity 或测试场景触发。
                    // 下游影响：「PostCommitSideEffectExecutor.afterCommit()」向下依次触达 「dispatch.run」。
                    // 系统意义：「PostCommitSideEffectExecutor.afterCommit()」负责主链路中的“之后提交”；公共组件不得暗含具体案件裁决规则
                    @Override
                    public void afterCommit() {
                        dispatch.run();
                    }
                });
    }

    // 所属模块：【后端公共边界 / 核心业务层】「PostCommitSideEffectExecutor.dispatch(String,Map,Runnable)」。
    // 具体功能：「PostCommitSideEffectExecutor.dispatch(String,Map,Runnable)」：把事务后任务交给专用 Executor 异步执行；线程池拒绝任务时只记录带 operation/context 的告警，避免已提交的主事务被事后失败伪装成回滚，最终返回「void」。
    // 上游调用：「PostCommitSideEffectExecutor.dispatch(String,Map,Runnable)」的上游调用点包括 「PostCommitSideEffectExecutor.execute」。
    // 下游影响：「PostCommitSideEffectExecutor.dispatch(String,Map,Runnable)」向下依次触达 「sideEffectExecutor.execute」、「LOGGER.warn」、「runGuarded」。
    // 系统意义：「PostCommitSideEffectExecutor.dispatch(String,Map,Runnable)」负责主链路中的“事务提交后的异步副作用”；公共组件不得暗含具体案件裁决规则
    private void dispatch(
            String operation,
            Map<String, ?> context,
            Runnable action) {
        try {
            sideEffectExecutor.execute(() -> runGuarded(operation, context, action));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Post-commit side effect dispatch failed: operation={} context={} exception_type={} message={}",
                    operation,
                    context == null ? Map.of() : context,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception);
        }
    }

    // 所属模块：【后端公共边界 / 核心业务层】「PostCommitSideEffectExecutor.runGuarded(String,Map,Runnable)」。
    // 具体功能：「PostCommitSideEffectExecutor.runGuarded(String,Map,Runnable)」：执行真正的副作用 Runnable，并在运行期异常时记录操作名、上下文和异常类型；异常不会逃逸到线程池，最终返回「void」。
    // 上游调用：「PostCommitSideEffectExecutor.runGuarded(String,Map,Runnable)」的上游调用点包括 「PostCommitSideEffectExecutor.dispatch」。
    // 下游影响：「PostCommitSideEffectExecutor.runGuarded(String,Map,Runnable)」向下依次触达 「LOGGER.warn」、「action.run」。
    // 系统意义：「PostCommitSideEffectExecutor.runGuarded(String,Map,Runnable)」负责主链路中的“Guarded”；公共组件不得暗含具体案件裁决规则
    private void runGuarded(
            String operation,
            Map<String, ?> context,
            Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Post-commit side effect failed: operation={} context={} exception_type={} message={}",
                    operation,
                    context == null ? Map.of() : context,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception);
        }
    }
}
