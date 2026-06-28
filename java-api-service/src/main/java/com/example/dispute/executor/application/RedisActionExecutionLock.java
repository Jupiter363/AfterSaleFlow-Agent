package com.example.dispute.executor.application;

import com.example.dispute.common.exception.IdempotencyConflictException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

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

    public RedisActionExecutionLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

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

    @Override
    public void release(String idempotencyKey, String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            return;
        }
        redis.execute(
                RELEASE_SCRIPT, List.of(key(idempotencyKey)), ownerToken);
    }

    private static String key(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
