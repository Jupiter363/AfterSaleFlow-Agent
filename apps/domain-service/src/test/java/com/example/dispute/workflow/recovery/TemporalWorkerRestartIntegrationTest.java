package com.example.dispute.workflow.recovery;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_FIRED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class TemporalWorkerRestartIntegrationTest {

    private static final String ENABLE_PROPERTY = "temporal.restart-test.enabled";
    private static final String ADDRESS_PROPERTY = "temporal.restart-test.address";
    private static final String NAMESPACE = "default";

    @Test
    void timerThatFiresDuringAWorkerOutageReplaysOnAReplacementIdentity() throws Exception {
        assumeTrue(Boolean.getBoolean(ENABLE_PROPERTY));

        String suffix = UUID.randomUUID().toString();
        String taskQueue = "worker-restart-" + suffix;
        String workflowId = "worker-restart-workflow-" + suffix;
        String oldIdentity = "worker-restart-old-" + suffix;
        String replacementIdentity = "worker-restart-new-" + suffix;
        RecordingRestartActivities activities = new RecordingRestartActivities();

        WorkflowServiceStubs service =
                WorkflowServiceStubs.newServiceStubs(
                        WorkflowServiceStubsOptions.newBuilder()
                                .setTarget(System.getProperty(ADDRESS_PROPERTY, "127.0.0.1:7233"))
                                .build());
        WorkflowClient client =
                WorkflowClient.newInstance(
                        service, WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build());
        WorkerFactory oldFactory = null;
        WorkerFactory replacementFactory = null;
        try {
            oldFactory = startWorker(service, taskQueue, oldIdentity, activities);
            RestartProbeWorkflow workflow =
                    client.newWorkflowStub(
                            RestartProbeWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setTaskQueue(taskQueue)
                                    .build());
            WorkflowClient.start(workflow::run, Duration.ofSeconds(8), workflowId);
            awaitCondition(
                    () -> hasEvent(client, workflowId, EVENT_TYPE_TIMER_STARTED), "timer was not scheduled");

            shutdown(oldFactory);
            oldFactory = null;
            awaitCondition(
                    () -> hasEvent(client, workflowId, EVENT_TYPE_TIMER_FIRED),
                    "timer did not fire while the worker was stopped");
            assertThat(activities.calls).hasValue(0);

            replacementFactory = startWorker(service, taskQueue, replacementIdentity, activities);
            assertThat(WorkflowStub.fromTyped(workflow).getResult(30, TimeUnit.SECONDS, String.class))
                    .isEqualTo(workflowId);

            assertThat(activities.calls).hasValue(1);
            assertThat(activities.markers).containsExactly(workflowId);
            assertThat(workflowTaskAfterTimerWasStartedBy(client, workflowId, replacementIdentity))
                    .isTrue();
        } finally {
            shutdown(replacementFactory);
            shutdown(oldFactory);
            service.shutdownNow();
            service.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static WorkerFactory startWorker(
            WorkflowServiceStubs service,
            String taskQueue,
            String identity,
            RestartActivities activities) {
        WorkflowClient workerClient =
                WorkflowClient.newInstance(
                        service,
                        WorkflowClientOptions.newBuilder()
                                .setNamespace(NAMESPACE)
                                .setIdentity(identity)
                                .build());
        WorkerFactory factory = WorkerFactory.newInstance(workerClient);
        Worker worker =
                factory.newWorker(
                        taskQueue,
                        WorkerOptions.newBuilder()
                                .setStickyQueueScheduleToStartTimeout(Duration.ofSeconds(1))
                                .build());
        worker.registerWorkflowImplementationTypes(RestartProbeWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        return factory;
    }

    private static void shutdown(WorkerFactory factory) {
        if (factory == null) {
            return;
        }
        factory.shutdownNow();
        factory.awaitTermination(10, TimeUnit.SECONDS);
    }

    private static boolean hasEvent(
            WorkflowClient client, String workflowId, io.temporal.api.enums.v1.EventType eventType) {
        return client.fetchHistory(workflowId).getEvents().stream()
                .anyMatch(event -> event.getEventType() == eventType);
    }

    private static boolean workflowTaskAfterTimerWasStartedBy(
            WorkflowClient client, String workflowId, String identity) {
        List<io.temporal.api.history.v1.HistoryEvent> events =
                client.fetchHistory(workflowId).getEvents();
        long timerFiredEventId =
                events.stream()
                        .filter(event -> event.getEventType() == EVENT_TYPE_TIMER_FIRED)
                        .mapToLong(io.temporal.api.history.v1.HistoryEvent::getEventId)
                        .findFirst()
                        .orElseThrow();
        return events.stream()
                .filter(event -> event.getEventId() > timerFiredEventId)
                .filter(event -> event.getEventType() == EVENT_TYPE_WORKFLOW_TASK_STARTED)
                .anyMatch(
                        event -> identity.equals(event.getWorkflowTaskStartedEventAttributes().getIdentity()));
    }

    private static void awaitCondition(BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
            }
        }
        throw new AssertionError(failureMessage, lastFailure);
    }

    @WorkflowInterface
    public interface RestartProbeWorkflow {

        @WorkflowMethod
        String run(Duration delay, String marker);
    }

    @ActivityInterface
    public interface RestartActivities {

        void record(String marker);
    }

    public static final class RestartProbeWorkflowImpl implements RestartProbeWorkflow {

        private final RestartActivities activities =
                Workflow.newActivityStub(
                        RestartActivities.class,
                        ActivityOptions.newBuilder()
                                .setStartToCloseTimeout(Duration.ofSeconds(5))
                                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                                .build());

        @Override
        public String run(Duration delay, String marker) {
            Workflow.sleep(delay);
            activities.record(marker);
            return marker;
        }
    }

    private static final class RecordingRestartActivities implements RestartActivities {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> markers = new CopyOnWriteArrayList<>();

        @Override
        public void record(String marker) {
            calls.incrementAndGet();
            markers.add(marker);
        }
    }
}
