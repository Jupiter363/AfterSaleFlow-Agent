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

@ExtendWith(MockitoExtension.class)
class RedisActionExecutionLockTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    private RedisActionExecutionLock lock;

    @BeforeEach
    void setUp() {
        lock = new RedisActionExecutionLock(redis);
    }

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

    @Test
    void reportsAUniformIdempotencyConflictWhenAnotherOwnerHoldsLock() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("dispute:lock:refund-1"), any(), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> lock.acquire("refund-1"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already being executed");
    }

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
