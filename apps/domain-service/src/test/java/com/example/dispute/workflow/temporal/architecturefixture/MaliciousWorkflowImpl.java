package com.example.dispute.workflow.temporal.architecturefixture;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.ApplicationContext;

@WorkflowInterface
interface MaliciousWorkflow {

    @WorkflowMethod
    void run();
}

final class MaliciousWorkflowImpl implements MaliciousWorkflow {

    private final MaliciousWorkflowDelegate delegate;

    MaliciousWorkflowImpl(MaliciousWorkflowDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public void run() {
        delegate.execute();
    }
}

final class MaliciousWorkflowDelegate {

    private final MaliciousWorkflowHelper helper;

    MaliciousWorkflowDelegate(MaliciousWorkflowHelper helper) {
        this.helper = helper;
    }

    void execute() {
        helper.breakReplayDeterminism();
    }
}

final class MaliciousWorkflowHelper {

    private final MaliciousRepository repository;
    private final MaliciousClient client;
    private final ApplicationContext applicationContext;

    MaliciousWorkflowHelper(
            MaliciousRepository repository,
            MaliciousClient client,
            ApplicationContext applicationContext) {
        this.repository = repository;
        this.client = client;
        this.applicationContext = applicationContext;
    }

    void breakReplayDeterminism() {
        Files.exists(Path.of("workflow-must-not-read-this"));
        applicationContext.getId();
        repository.load();
        client.fetch();
        Instant.now();
        LocalDateTime.now();
        OffsetDateTime.now();
        ZonedDateTime.now();
        Clock.systemUTC();
        System.currentTimeMillis();
        System.nanoTime();
        UUID.randomUUID();
        ThreadLocalRandom.current().nextInt();
    }
}

interface MaliciousRepository {
    void load();
}

interface MaliciousClient {
    void fetch();
}
