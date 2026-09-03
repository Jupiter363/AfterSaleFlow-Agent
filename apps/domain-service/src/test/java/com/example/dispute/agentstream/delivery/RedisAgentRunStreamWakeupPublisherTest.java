package com.example.dispute.agentstream.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeup;
import com.example.dispute.agentstream.infrastructure.delivery.RedisAgentRunStreamWakeupPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAgentRunStreamWakeupPublisherTest {

    @Test
    void publishesOnlyAttemptCursorMetadata() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RedisAgentRunStreamWakeupPublisher publisher =
                new RedisAgentRunStreamWakeupPublisher(redis, objectMapper);

        try {
            publisher.publish(
                    new AgentRunStreamWakeup(
                            AgentRunStreamWakeup.SCHEMA_VERSION, "RUN_1", "ATTEMPT_1", 9));

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(redis, timeout(2_000))
                    .convertAndSend(
                            org.mockito.ArgumentMatchers.eq(
                                    RedisAgentRunStreamWakeupPublisher.CHANNEL),
                            payload.capture());
            JsonNode json = objectMapper.readTree(payload.getValue());
            Set<String> fieldNames = new HashSet<>();
            json.fieldNames().forEachRemaining(fieldNames::add);
            assertThat(fieldNames)
                    .isEqualTo(
                            Set.of(
                                    "schema_version",
                                    "run_id",
                                    "attempt_id",
                                    "durable_high_watermark"));
            assertThat(json.path("durable_high_watermark").asLong()).isEqualTo(9);
        } finally {
            publisher.close();
        }
    }

    @Test
    void retainsNewestBoundedWakeupsWhenRedisDeliveryIsBackpressured() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        CountDownLatch firstDeliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDelivery = new CountDownLatch(1);
        CountDownLatch expectedDeliveries = new CountDownLatch(257);
        ConcurrentLinkedQueue<Long> deliveredWatermarks = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        when(redis.convertAndSend(
                        eq(RedisAgentRunStreamWakeupPublisher.CHANNEL), any(String.class)))
                .thenAnswer(
                        invocation -> {
                            try {
                                JsonNode payload =
                                        objectMapper.readTree(invocation.getArgument(1, String.class));
                                long watermark =
                                        payload.path("durable_high_watermark").asLong(-1);
                                deliveredWatermarks.add(watermark);
                                if (watermark == 0) {
                                    firstDeliveryStarted.countDown();
                                    if (!releaseFirstDelivery.await(10, TimeUnit.SECONDS)) {
                                        asynchronousFailure.compareAndSet(
                                                null,
                                                new AssertionError(
                                                        "blocked Redis delivery was not released"));
                                    }
                                }
                            } catch (Throwable failure) {
                                if (failure instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                                asynchronousFailure.compareAndSet(null, failure);
                            } finally {
                                expectedDeliveries.countDown();
                            }
                            return 1L;
                        });
        RedisAgentRunStreamWakeupPublisher publisher =
                new RedisAgentRunStreamWakeupPublisher(redis, objectMapper);

        try {
            publisher.publish(wakeup(0));
            assertThat(firstDeliveryStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // The worker remains blocked on sequence zero while 300 hints fill its 256-slot queue.
            for (long watermark = 1; watermark <= 300; watermark++) {
                publisher.publish(wakeup(watermark));
            }
            assertThat(deliveredWatermarks).containsExactly(0L);

            releaseFirstDelivery.countDown();
            assertThat(expectedDeliveries.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(asynchronousFailure.get()).isNull();

            List<Long> expected =
                    LongStream.concat(LongStream.of(0), LongStream.rangeClosed(45, 300))
                            .boxed()
                            .toList();
            assertThat(deliveredWatermarks).containsExactlyElementsOf(expected);
            verify(redis, times(257))
                    .convertAndSend(
                            eq(RedisAgentRunStreamWakeupPublisher.CHANNEL), any(String.class));
        } finally {
            releaseFirstDelivery.countDown();
            publisher.close();
        }
    }

    private static AgentRunStreamWakeup wakeup(long watermark) {
        return new AgentRunStreamWakeup(
                AgentRunStreamWakeup.SCHEMA_VERSION, "RUN_1", "ATTEMPT_1", watermark);
    }
}
