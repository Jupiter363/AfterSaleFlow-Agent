/*
 * 所属模块：确定性工具执行。
 * 文件职责：验证Redis动作执行Lock，覆盖 「acquiresNamespacedLockWithOwnerTokenAndFiniteLease」、「reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock」、「releasesOnlyThroughOwnerTokenLuaScript」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.executor.application.RedisActionExecutionLock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

// 所属模块：【确定性工具执行 / 自动化测试层】类型「RedisActionExecutionLockTest」。
// 类型职责：集中验证Redis动作执行Lock的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「setUp」、「acquiresNamespacedLockWithOwnerTokenAndFiniteLease」、「reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock」、「releasesOnlyThroughOwnerTokenLuaScript」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@ExtendWith(MockitoExtension.class)
class RedisActionExecutionLockTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    private RedisActionExecutionLock lock;

    // 所属模块：【确定性工具执行 / 自动化测试层】「RedisActionExecutionLockTest.setUp()」。
    // 具体功能：「RedisActionExecutionLockTest.setUp()」：在每个测试场景运行前创建测试对象和内存夹具，统一准备后续断言依赖的初始状态，避免各用例重复搭建且保持彼此隔离。
    // 上游调用：「RedisActionExecutionLockTest.setUp()」由 JUnit 生命周期或本测试类的场景方法调用。
    // 下游影响：「RedisActionExecutionLockTest.setUp()」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「RedisActionExecutionLockTest.setUp()」守住「确定性工具执行」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @BeforeEach
    void setUp() {
        lock = new RedisActionExecutionLock(redis);
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「RedisActionExecutionLockTest.acquiresNamespacedLockWithOwnerTokenAndFiniteLease()」。
    // 具体功能：「RedisActionExecutionLockTest.acquiresNamespacedLockWithOwnerTokenAndFiniteLease()」：复现“核对完整业务行为（场景方法「acquiresNamespacedLockWithOwnerTokenAndFiniteLease」）”场景：驱动 「Duration.ofMinutes」、「redis.opsForValue」、「values.setIfAbsent」、「lock.acquire」，再用 「assertThat」、「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「dispute:lock:refund-1」、「refund-1」。
    // 上游调用：「RedisActionExecutionLockTest.acquiresNamespacedLockWithOwnerTokenAndFiniteLease()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RedisActionExecutionLockTest.acquiresNamespacedLockWithOwnerTokenAndFiniteLease()」的下游是被测服务、仓储或外部客户端替身；「assertThat、verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RedisActionExecutionLockTest.acquiresNamespacedLockWithOwnerTokenAndFiniteLease()」守住「确定性工具执行」的可执行规格，尤其防止 「dispute:lock:refund-1」、「refund-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void acquiresNamespacedLockWithOwnerTokenAndFiniteLease() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("dispute:lock:refund-1"), any(), any(Duration.class)))
                .thenReturn(true);

        String token = lock.acquire("refund-1");

        assertThat(token).isNotBlank();
        verify(values)
                .setIfAbsent(
                        eq("dispute:lock:refund-1"),
                        eq(token),
                        eq(Duration.ofMinutes(3)));
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「RedisActionExecutionLockTest.reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock()」。
    // 具体功能：「RedisActionExecutionLockTest.reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock()」：复现“核对完整业务行为（场景方法「reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock」）”场景：驱动 「redis.opsForValue」、「values.setIfAbsent」、「lock.acquire」、「when」，再用 「assertThatThrownBy」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「dispute:lock:refund-1」、「refund-1」。
    // 上游调用：「RedisActionExecutionLockTest.reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RedisActionExecutionLockTest.reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RedisActionExecutionLockTest.reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock()」守住「确定性工具执行」的可执行规格，尤其防止 「dispute:lock:refund-1」、「refund-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("dispute:lock:refund-1"), any(), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> lock.acquire("refund-1"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already being executed");
    }

    // 所属模块：【确定性工具执行 / 自动化测试层】「RedisActionExecutionLockTest.releasesOnlyThroughOwnerTokenLuaScript()」。
    // 具体功能：「RedisActionExecutionLockTest.releasesOnlyThroughOwnerTokenLuaScript()」：复现“核对完整业务行为（场景方法「releasesOnlyThroughOwnerTokenLuaScript」）”场景：驱动 「lock.release」、「any」、「eq」、「verify(redis).execute」，再用 「verify」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「refund-1」、「owner-token」、「dispute:lock:refund-1」。
    // 上游调用：「RedisActionExecutionLockTest.releasesOnlyThroughOwnerTokenLuaScript()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RedisActionExecutionLockTest.releasesOnlyThroughOwnerTokenLuaScript()」的下游是被测服务、仓储或外部客户端替身；「verify」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RedisActionExecutionLockTest.releasesOnlyThroughOwnerTokenLuaScript()」守住「确定性工具执行」的可执行规格，尤其防止 「refund-1」、「owner-token」、「dispute:lock:refund-1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void releasesOnlyThroughOwnerTokenLuaScript() {
        lock.release("refund-1", "owner-token");

        verify(redis)
                .execute(
                        any(),
                        eq(java.util.List.of("dispute:lock:refund-1")),
                        eq("owner-token"));
    }
}
