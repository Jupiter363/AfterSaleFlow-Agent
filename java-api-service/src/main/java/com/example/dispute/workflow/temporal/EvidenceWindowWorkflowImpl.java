package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.domain.EvidenceWindowResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public class EvidenceWindowWorkflowImpl implements EvidenceWindowWorkflow {

    private final EvidenceWindowActivities activities =
            Workflow.newActivityStub(
                    EvidenceWindowActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(1))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Set<String> completedRoles = new LinkedHashSet<>();

    @Override
    public EvidenceWindowResult run(EvidenceWindowCommand command) {
        boolean completedEarly =
                Workflow.await(
                        command.window(),
                        () ->
                                completedRoles.contains("USER")
                                        && completedRoles.contains("MERCHANT"));
        if (completedEarly) {
            return new EvidenceWindowResult(
                    command.caseId(),
                    "BOTH_PARTIES_COMPLETED",
                    new ArrayList<>(completedRoles));
        }
        activities.expire(command.caseId());
        return new EvidenceWindowResult(
                command.caseId(),
                "DEADLINE_EXPIRED",
                new ArrayList<>(completedRoles));
    }

    @Override
    public void partyCompleted(String role) {
        if ("USER".equals(role) || "MERCHANT".equals(role)) {
            completedRoles.add(role);
        }
    }
}
