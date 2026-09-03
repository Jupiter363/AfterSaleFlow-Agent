/*
 * 所属模块：后端公共边界。
 * 文件职责：验证事务后提交副作用执行器，覆盖 「immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest」、「afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；统一 API 包装、异常映射、链路标识、审计端口与事务后副作用。
 * 关键边界：公共组件不得暗含具体案件裁决规则
 */
package com.example.dispute.common.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 所属模块：【后端公共边界 / 自动化测试层】类型「PostCommitSideEffectExecutorTest」。
// 类型职责：集中验证事务后提交副作用执行器的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「tearDownSynchronization」、「immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest」、「afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：公共组件不得暗含具体案件裁决规则
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class PostCommitSideEffectExecutorTest {

    private final QueuedExecutor sideEffects = new QueuedExecutor();
    private final PostCommitSideEffectExecutor executor =
            new PostCommitSideEffectExecutor(sideEffects);

    // 所属模块：【后端公共边界 / 自动化测试层】「PostCommitSideEffectExecutorTest.tearDownSynchronization()」。
    // 具体功能：「PostCommitSideEffectExecutorTest.tearDownSynchronization()」：作为测试辅助方法为“核对完整业务行为（场景方法「tearDownSynchronization」）”组装或读取「TransactionSynchronizationManager.isSynchronizationActive」、「TransactionSynchronizationManager.clearSynchronization」，供本测试类的场景方法复用。
    // 上游调用：「PostCommitSideEffectExecutorTest.tearDownSynchronization()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「PostCommitSideEffectExecutorTest.tearDownSynchronization()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「PostCommitSideEffectExecutorTest.tearDownSynchronization()」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @AfterEach
    void tearDownSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「PostCommitSideEffectExecutorTest.immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest()」。
    // 具体功能：「PostCommitSideEffectExecutorTest.immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest()」：复现“核对完整业务行为（场景方法「immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest」）”场景：驱动 「executor.execute」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「temporal-signal」、「case_id」、「CASE_1」。
    // 上游调用：「PostCommitSideEffectExecutorTest.immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「PostCommitSideEffectExecutorTest.immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「PostCommitSideEffectExecutorTest.immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest()」守住「后端公共边界」的可执行规格，尤其防止 「temporal-signal」、「case_id」、「CASE_1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest() {
        AtomicBoolean attempted = new AtomicBoolean(false);

        assertThatCode(
                        () ->
                                executor.execute(
                                        "temporal-signal",
                                        Map.of("case_id", "CASE_1"),
                                        () -> {
                                            attempted.set(true);
                                            throw new IllegalStateException("temporal unavailable");
                                        }))
                .doesNotThrowAnyException();
        assertThat(attempted).isFalse();
        assertThat(sideEffects.tasks).hasSize(1);

        assertThatCode(sideEffects::runAll).doesNotThrowAnyException();
        assertThat(attempted).isTrue();
    }

    // 所属模块：【后端公共边界 / 自动化测试层】「PostCommitSideEffectExecutorTest.afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction()」。
    // 具体功能：「PostCommitSideEffectExecutorTest.afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction()」：复现“核对完整业务行为（场景方法「afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction」）”场景：驱动 「executor.execute」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「temporal-start」、「case_id」、「CASE_2」。
    // 上游调用：「PostCommitSideEffectExecutorTest.afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「PostCommitSideEffectExecutorTest.afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「PostCommitSideEffectExecutorTest.afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction()」守住「后端公共边界」的可执行规格，尤其防止 「temporal-start」、「case_id」、「CASE_2」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicBoolean attempted = new AtomicBoolean(false);

        executor.execute(
                "temporal-start",
                Map.of("case_id", "CASE_2"),
                () -> {
                    attempted.set(true);
                    throw new IllegalStateException("workflow missing");
                });

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        assertThat(attempted).isFalse();
        assertThat(sideEffects.tasks).hasSize(1);

        assertThatCode(sideEffects::runAll).doesNotThrowAnyException();
        assertThat(attempted).isTrue();
    }

    // 所属模块：【后端公共边界 / 自动化测试层】类型「QueuedExecutor」。
    // 类型职责：在受控边界内执行Queued执行器并隔离失败副作用；本类型显式提供 「execute」、「runAll」。
    // 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
    // 边界意义：公共组件不得暗含具体案件裁决规则
    // Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        // 所属模块：【后端公共边界 / 自动化测试层】「PostCommitSideEffectExecutorTest.QueuedExecutor.execute(Runnable)」。
        // 具体功能：「PostCommitSideEffectExecutorTest.QueuedExecutor.execute(Runnable)」：作为「QueuedExecutor」测试替身实现「execute」：按当前场景返回固定结果且不访问真实基础设施，让被测编排能够观察到确定、可断言的协作者行为。
        // 上游调用：「PostCommitSideEffectExecutorTest.QueuedExecutor.execute(Runnable)」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「PostCommitSideEffectExecutorTest.QueuedExecutor.execute(Runnable)」下游仅修改测试内存状态或返回桩值：不触发真实 I/O；场景结束后由外层测试读取这些记录完成断言。
        // 系统意义：「PostCommitSideEffectExecutorTest.QueuedExecutor.execute(Runnable)」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        // Java 语法：@Override 要求签名与接口/父类一致，编译器会在方法名或参数写错时直接报错。
        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        // 所属模块：【后端公共边界 / 自动化测试层】「PostCommitSideEffectExecutorTest.QueuedExecutor.runAll()」。
        // 具体功能：「PostCommitSideEffectExecutorTest.QueuedExecutor.runAll()」：作为测试辅助方法为“核对完整业务行为（场景方法「runAll」）”组装或读取「ArrayList」 输入夹具，供本测试类的场景方法复用。
        // 上游调用：「PostCommitSideEffectExecutorTest.QueuedExecutor.runAll()」由 JUnit 生命周期或本测试类的场景方法调用。
        // 下游影响：「PostCommitSideEffectExecutorTest.QueuedExecutor.runAll()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
        // 系统意义：「PostCommitSideEffectExecutorTest.QueuedExecutor.runAll()」守住「后端公共边界」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
        private void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }
}
