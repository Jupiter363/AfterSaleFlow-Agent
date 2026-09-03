package com.example.dispute.agentstream.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAgentRunStreamFailoverTest {

    private static final String RUN_ID = "RUN_1";
    private static final String ATTEMPT_ID = "ATTEMPT_1";
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void redisUnavailableCannotChangeTheDurablePortReceiptOrReplay() throws Exception {
        FakePostgresAuthority postgres = new FakePostgresAuthority();
        AppendReceipt receipt = postgres.append(event(0));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CountDownLatch deliveryAttempted = new CountDownLatch(1);
        when(redis.convertAndSend(eq(RedisAgentRunStreamWakeupPublisher.CHANNEL), any(String.class)))
                .thenAnswer(
                        ignored -> {
                            deliveryAttempted.countDown();
                            throw new RedisConnectionFailureException("redis unavailable");
                        });
        RedisAgentRunStreamWakeupPublisher publisher =
                new RedisAgentRunStreamWakeupPublisher(redis, objectMapper);

        try {
            assertThatCode(() -> publisher.publish(hint(0))).doesNotThrowAnyException();
            assertThat(deliveryAttempted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(receipt).isEqualTo(new AppendReceipt(true, 0));
            assertThat(postgres.durableHighWatermark(RUN_ID, ATTEMPT_ID)).isZero();
            assertThat(sequences(postgres.replay(RUN_ID, ATTEMPT_ID, -1, 10)))
                    .containsExactly(0L);
        } finally {
            publisher.close();
        }

        assertThatCode(() -> publisher.publish(hint(0)))
                .as("a stopped best-effort publisher has no completion outcome")
                .doesNotThrowAnyException();
    }

    @Test
    void droppedDuplicatedAndReorderedHintsReplayOnlyFromThePostgresCursor() throws Exception {
        FakePostgresAuthority postgres = new FakePostgresAuthority();
        postgres.appendBatch(List.of(event(0), event(1), event(2), event(3), event(4)));
        AuthoritativeReplayConsumer consumer =
                new AuthoritativeReplayConsumer(postgres, -1);
        AgentRunStreamEventService eventService = replayService(consumer);
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(objectMapper, eventService);

        // Hints 0..3 are lost. The surviving newest hint must not be treated as a cursor.
        subscriber.accept(encodedHint(4));
        // Duplicate and stale reordered hints may cause extra reads, but never duplicate delivery.
        subscriber.accept(encodedHint(4));
        subscriber.accept(encodedHint(1));

        assertThat(consumer.deliveredSequences()).containsExactly(0L, 1L, 2L, 3L, 4L);
        assertThat(consumer.cursor()).isEqualTo(4);
        verify(eventService, org.mockito.Mockito.times(3)).wakeUp(RUN_ID);
    }

    @Test
    void queueOverflowDropsHintsButASurvivingHintReplaysEveryPostgresRow() throws Exception {
        FakePostgresAuthority postgres = new FakePostgresAuthority();
        postgres.appendBatch(LongStream.rangeClosed(0, 300)
                .mapToObj(RedisAgentRunStreamFailoverTest::event)
                .toList());
        AuthoritativeReplayConsumer consumer =
                new AuthoritativeReplayConsumer(postgres, -1);
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(
                        objectMapper, replayService(consumer));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AtomicBoolean firstDelivery = new AtomicBoolean(true);
        CountDownLatch firstDeliveryBlocked = new CountDownLatch(1);
        CountDownLatch releaseFirstDelivery = new CountDownLatch(1);
        CountDownLatch newestHintDelivered = new CountDownLatch(1);
        when(redis.convertAndSend(eq(RedisAgentRunStreamWakeupPublisher.CHANNEL), any(String.class)))
                .thenAnswer(
                        invocation -> {
                            String encoded = invocation.getArgument(1, String.class);
                            long highWatermark = objectMapper.readTree(encoded)
                                    .path("durable_high_watermark")
                                    .asLong();
                            if (firstDelivery.compareAndSet(true, false)) {
                                firstDeliveryBlocked.countDown();
                                releaseFirstDelivery.await(5, TimeUnit.SECONDS);
                                return 0L;
                            }
                            subscriber.accept(encoded);
                            if (highWatermark == 300) {
                                newestHintDelivered.countDown();
                            }
                            return 1L;
                        });
        RedisAgentRunStreamWakeupPublisher publisher =
                new RedisAgentRunStreamWakeupPublisher(redis, objectMapper);

        try {
            publisher.publish(hint(0));
            assertThat(firstDeliveryBlocked.await(2, TimeUnit.SECONDS)).isTrue();
            for (long highWatermark = 1; highWatermark <= 300; highWatermark++) {
                publisher.publish(hint(highWatermark));
            }

            releaseFirstDelivery.countDown();
            assertThat(newestHintDelivered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(consumer.deliveredSequences())
                    .containsExactlyElementsOf(LongStream.rangeClosed(0, 300).boxed().toList());
            assertThat(consumer.cursor()).isEqualTo(300);
            verify(redis, times(257))
                    .convertAndSend(
                            eq(RedisAgentRunStreamWakeupPublisher.CHANNEL), any(String.class));
        } finally {
            releaseFirstDelivery.countDown();
            publisher.close();
        }
    }

    @Test
    void restartUsesThePersistedClientCursorAndIgnoresTheHintHighWatermark() throws Exception {
        FakePostgresAuthority postgres = new FakePostgresAuthority();
        postgres.appendBatch(List.of(event(0), event(1), event(2), event(3), event(4)));
        AuthoritativeReplayConsumer restarted =
                new AuthoritativeReplayConsumer(postgres, 1);
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(
                        objectMapper, replayService(restarted));

        // A stale post-restart hint still triggers replay after the durable client cursor.
        subscriber.accept(encodedHint(0));

        assertThat(restarted.deliveredSequences()).containsExactly(2L, 3L, 4L);
        assertThat(restarted.cursor()).isEqualTo(4);

        postgres.appendBatch(List.of(event(5), event(6)));
        // A future HWM cannot advance the cursor past rows that PostgreSQL actually contains.
        subscriber.accept(encodedHint(99));

        assertThat(restarted.deliveredSequences())
                .containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(restarted.cursor()).isEqualTo(6);
    }

    @Test
    void subscriberFailureIsContainedWithoutChangingPostgresAuthority() throws Exception {
        FakePostgresAuthority postgres = new FakePostgresAuthority();
        postgres.append(event(0));
        AgentRunStreamEventService eventService = mock(AgentRunStreamEventService.class);
        doThrow(new IllegalStateException("catch-up temporarily unavailable"))
                .when(eventService)
                .wakeUp(RUN_ID);
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(objectMapper, eventService);

        assertThatCode(() -> subscriber.accept(encodedHint(0))).doesNotThrowAnyException();
        assertThat(postgres.durableHighWatermark(RUN_ID, ATTEMPT_ID)).isZero();
        assertThat(sequences(postgres.replay(RUN_ID, ATTEMPT_ID, -1, 10)))
                .containsExactly(0L);
    }

    private AgentRunStreamEventService replayService(AuthoritativeReplayConsumer consumer) {
        AgentRunStreamEventService service = mock(AgentRunStreamEventService.class);
        doAnswer(
                        invocation -> {
                            consumer.catchUp(invocation.getArgument(0, String.class));
                            return null;
                        })
                .when(service)
                .wakeUp(any(String.class));
        return service;
    }

    private String encodedHint(long highWatermark) throws Exception {
        return objectMapper.writeValueAsString(hint(highWatermark));
    }

    private static AgentRunStreamWakeup hint(long highWatermark) {
        return new AgentRunStreamWakeup(
                AgentRunStreamWakeup.SCHEMA_VERSION,
                RUN_ID,
                ATTEMPT_ID,
                highWatermark);
    }

    private static AgentStreamEvent event(long sequence) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                RUN_ID,
                ATTEMPT_ID,
                sequence,
                StreamEventType.VISIBLE_DELTA,
                Audience.USER,
                Instant.parse("2026-07-25T00:00:00Z").plusSeconds(sequence),
                new Payload(
                        "answer",
                        "text",
                        Long.toString(sequence),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private static List<Long> sequences(List<AgentStreamEvent> events) {
        return events.stream().map(AgentStreamEvent::sequenceNo).toList();
    }

    private static final class AuthoritativeReplayConsumer {
        private final AgentRunV2StreamStore postgres;
        private final List<Long> deliveredSequences = new ArrayList<>();
        private long cursor;

        private AuthoritativeReplayConsumer(AgentRunV2StreamStore postgres, long cursor) {
            this.postgres = postgres;
            this.cursor = cursor;
        }

        private void catchUp(String runId) {
            for (AgentStreamEvent event : postgres.replay(runId, ATTEMPT_ID, cursor, 100)) {
                deliveredSequences.add(event.sequenceNo());
                cursor = event.sequenceNo();
            }
        }

        private List<Long> deliveredSequences() {
            return List.copyOf(deliveredSequences);
        }

        private long cursor() {
            return cursor;
        }
    }

    private static final class FakePostgresAuthority implements AgentRunV2StreamStore {
        private final List<AgentStreamEvent> events = new ArrayList<>();
        private long durableHighWatermark = -1;

        @Override
        public AppendReceipt append(AgentStreamEvent event) {
            boolean inserted = events.stream()
                    .noneMatch(existing -> existing.sequenceNo() == event.sequenceNo());
            if (inserted) {
                events.add(event);
                events.sort(Comparator.comparingLong(AgentStreamEvent::sequenceNo));
                while (containsSequence(durableHighWatermark + 1)) {
                    durableHighWatermark++;
                }
            }
            return new AppendReceipt(
                    inserted, durableHighWatermark(event.runId(), event.attemptId()));
        }

        @Override
        public BatchAppendReceipt appendBatch(List<AgentStreamEvent> batch) {
            List<Boolean> inserted = new ArrayList<>();
            for (AgentStreamEvent event : batch) {
                inserted.add(append(event).inserted());
            }
            AgentStreamEvent first = batch.getFirst();
            return new BatchAppendReceipt(
                    inserted, durableHighWatermark(first.runId(), first.attemptId()));
        }

        @Override
        public List<AgentStreamEvent> replay(
                String runId, String attemptId, long afterSequence, int limit) {
            return events.stream()
                    .filter(event -> event.runId().equals(runId))
                    .filter(event -> event.attemptId().equals(attemptId))
                    .filter(event -> event.sequenceNo() > afterSequence)
                    .limit(limit)
                    .toList();
        }

        @Override
        public long durableHighWatermark(String runId, String attemptId) {
            if (durableHighWatermark < 0) {
                throw new IllegalStateException("the fake authority has no durable events");
            }
            return durableHighWatermark;
        }

        private boolean containsSequence(long sequence) {
            return events.stream().anyMatch(event -> event.sequenceNo() == sequence);
        }
    }
}
