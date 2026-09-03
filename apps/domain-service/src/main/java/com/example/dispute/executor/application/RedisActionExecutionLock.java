/*
 * 所属模块：确定性工具执行。
 * 文件职责：承载Redis动作执行Lock在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「acquire」、「release」；按审核通过的动作快照解析依赖并调用白名单工具，记录每个动作结果。
 * 关键边界：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
 */
package com.example.dispute.executor.application;

import com.example.dispute.common.exception.IdempotencyConflictException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// 所属模块：【确定性工具执行 / 应用编排层】类型「RedisActionExecutionLock」。
// 类型职责：承载Redis动作执行Lock在当前业务模块中的规则与协作边界；本类型显式提供 「RedisActionExecutionLock」、「acquire」、「release」、「key」。
// 协作关系：主要由 「RedisActionExecutionLockTest.acquiresNamespacedLockWithOwnerTokenAndFiniteLease」、「RedisActionExecutionLockTest.releasesOnlyThroughOwnerTokenLuaScript」、「RedisActionExecutionLockTest.reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock」、「RedisActionExecutionLockTest.setUp」 使用。
// 边界意义：只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class RedisActionExecutionLock implements ActionExecutionLock {

    private static final String KEY_PREFIX = "dispute:lock:";
    private static final Duration LEASE = Duration.ofMinutes(3);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                      return redis.call('del', KEYS[1])
                    end
                    return 0
                    """,
                    Long.class);

    private final StringRedisTemplate redis;

    // 所属模块：【确定性工具执行 / 应用编排层】「RedisActionExecutionLock.RedisActionExecutionLock(StringRedisTemplate)」。
    // 具体功能：「RedisActionExecutionLock.RedisActionExecutionLock(StringRedisTemplate)」：通过构造器接收 「redis」(StringRedisTemplate) 并保存为「RedisActionExecutionLock」的协作依赖；这里只完成依赖装配，不提前访问数据库或外部服务。
    // 上游调用：「RedisActionExecutionLock.RedisActionExecutionLock(StringRedisTemplate)」由 Spring 容器执行构造器注入，依赖在 Bean 创建阶段一次性提供；测试中也由 「RedisActionExecutionLockTest.setUp」 显式创建。
    // 下游影响：「RedisActionExecutionLock.RedisActionExecutionLock(StringRedisTemplate)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「RedisActionExecutionLock.RedisActionExecutionLock(StringRedisTemplate)」负责主链路中的“Redis动作执行Lock”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public RedisActionExecutionLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「RedisActionExecutionLock.acquire(String)」。
    // 具体功能：「RedisActionExecutionLock.acquire(String)」：获取字符串；实际协作者为 「UUID.randomUUID」、「redis.opsForValue」、「key」、「redis.opsForValue().setIfAbsent」；不满足前置条件时抛出 「IllegalArgumentException」、「IdempotencyConflictException」，最终返回「String」。
    // 上游调用：「RedisActionExecutionLock.acquire(String)」的上游调用点包括 「RedisActionExecutionLockTest.acquiresNamespacedLockWithOwnerTokenAndFiniteLease」、「RedisActionExecutionLockTest.reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock」。
    // 下游影响：「RedisActionExecutionLock.acquire(String)」向下依次触达 「UUID.randomUUID」、「redis.opsForValue」、「key」、「redis.opsForValue().setIfAbsent」；计算结果以「String」交给调用方。
    // 系统意义：「RedisActionExecutionLock.acquire(String)」负责主链路中的“字符串”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    @Override
    public String acquire(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "action idempotency key must not be blank");
        }
        String ownerToken = UUID.randomUUID().toString();
        Boolean acquired =
                redis.opsForValue()
                        .setIfAbsent(key(idempotencyKey), ownerToken, LEASE);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IdempotencyConflictException(
                    "approved action is already being executed");
        }
        return ownerToken;
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「RedisActionExecutionLock.release(String,String)」。
    // 具体功能：「RedisActionExecutionLock.release(String,String)」：释放Redis动作执行Lock；实际协作者为 「redis.execute」、「key」，最终返回「void」。
    // 上游调用：「RedisActionExecutionLock.release(String,String)」的上游调用点包括 「RedisActionExecutionLockTest.releasesOnlyThroughOwnerTokenLuaScript」。
    // 下游影响：「RedisActionExecutionLock.release(String,String)」向下依次触达 「redis.execute」、「key」。
    // 系统意义：「RedisActionExecutionLock.release(String,String)」负责主链路中的“Redis动作执行Lock”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    @Override
    public void release(String idempotencyKey, String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            return;
        }
        redis.execute(
                RELEASE_SCRIPT, List.of(key(idempotencyKey)), ownerToken);
    }

    // 所属模块：【确定性工具执行 / 应用编排层】「RedisActionExecutionLock.key(String)」。
    // 具体功能：「RedisActionExecutionLock.key(String)」：构建键，最终返回「String」。
    // 上游调用：「RedisActionExecutionLock.key(String)」的上游调用点包括 「RedisActionExecutionLock.acquire」、「RedisActionExecutionLock.release」。
    // 下游影响：「RedisActionExecutionLock.key(String)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「String」交给调用方。
    // 系统意义：「RedisActionExecutionLock.key(String)」负责主链路中的“键”；只有通过版本、哈希和审批校验的动作可以执行，模型不能直接调用退款或补发工具
    private static String key(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
