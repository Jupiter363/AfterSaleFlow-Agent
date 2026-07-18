package com.example.dispute.agentstream.infrastructure.delivery;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.agent-run-v2.enabled", havingValue = "true")
public class AgentRunStreamWakeupSubscriptionConfiguration {

    @Bean
    SmartLifecycle agentRunStreamWakeupSubscription(
            RedisConnectionFactory connectionFactory,
            RedisAgentRunStreamWakeupSubscriber subscriber) {
        return new ResilientAgentRunStreamWakeupSubscription(connectionFactory, subscriber);
    }
}

final class ResilientAgentRunStreamWakeupSubscription implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ResilientAgentRunStreamWakeupSubscription.class);
    private static final byte[] CHANNEL =
            RedisAgentRunStreamWakeupPublisher.CHANNEL.getBytes(StandardCharsets.UTF_8);
    private static final long RETRY_DELAY_MILLIS = 1_000;
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final RedisConnectionFactory connectionFactory;
    private final RedisAgentRunStreamWakeupSubscriber subscriber;
    private final AtomicLong nextWarning = new AtomicLong();

    private volatile boolean running;
    private volatile long generation;
    private volatile RedisConnection activeConnection;
    private ExecutorService executor;

    ResilientAgentRunStreamWakeupSubscription(
            RedisConnectionFactory connectionFactory,
            RedisAgentRunStreamWakeupSubscriber subscriber) {
        this.connectionFactory = java.util.Objects.requireNonNull(
                connectionFactory, "connectionFactory");
        this.subscriber = java.util.Objects.requireNonNull(subscriber, "subscriber");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        long subscriptionGeneration = ++generation;
        executor = Executors.newSingleThreadExecutor(
                task -> {
                    Thread thread = new Thread(task, "agent-run-stream-redis-subscription");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.execute(() -> subscribeUntilStopped(subscriptionGeneration));
    }

    private void subscribeUntilStopped(long subscriptionGeneration) {
        while (isCurrent(subscriptionGeneration)) {
            RedisConnection connection = null;
            try {
                connection = connectionFactory.getConnection();
                activeConnection = connection;
                connection.subscribe(subscriber, CHANNEL);
            } catch (RuntimeException failure) {
                if (isCurrent(subscriptionGeneration)) {
                    warnRateLimited(failure);
                }
            } finally {
                if (activeConnection == connection) {
                    activeConnection = null;
                }
                closeQuietly(connection);
            }
            if (!awaitRetry(subscriptionGeneration)) {
                return;
            }
        }
    }

    private boolean awaitRetry(long subscriptionGeneration) {
        if (!isCurrent(subscriptionGeneration)) {
            return false;
        }
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
            return isCurrent(subscriptionGeneration);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isCurrent(long subscriptionGeneration) {
        return running && generation == subscriptionGeneration;
    }

    private void warnRateLimited(RuntimeException failure) {
        long now = System.nanoTime();
        long next = nextWarning.get();
        if (now >= next
                && nextWarning.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            LOGGER.warn(
                    "AgentRun Redis wakeup subscription unavailable; PostgreSQL replay remains authoritative: failure_type={}",
                    failure.getClass().getName());
        }
    }

    @Override
    public void stop() {
        RedisConnection connection;
        ExecutorService currentExecutor;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            generation++;
            connection = activeConnection;
            activeConnection = null;
            currentExecutor = executor;
            executor = null;
        }
        closeQuietly(connection);
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private static void closeQuietly(RedisConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException failure) {
            LOGGER.debug("Redis wakeup subscription connection close failed", failure);
        }
    }
}
