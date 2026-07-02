package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic executor for an approved immutable action snapshot.
 *
 * <p>Dependency ordering happens in workflow code. External writes and
 * unknown-result lookups use Activities with bounded retries and explicit
 * idempotency keys carried by each action.
 */
public class ExecutionWorkflowImpl implements ExecutionWorkflow {

    private final ExecutionActivities activities =
            Workflow.newActivityStub(
                    ExecutionActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(1))
                                            .setMaximumInterval(Duration.ofSeconds(15))
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    @Override
    public ExecutionResult run(ExecutionCommand command) {
        if (!command.approved()
                || Workflow.currentTimeMillis()
                        >= command.approvalExpiresAtEpochMillis()) {
            return manual(List.of());
        }
        ApprovalValidationResult approval =
                activities.validateApproval(command);
        if (!approval.valid()) {
            return manual(List.of());
        }
        List<ExecutionAction> ordered = dependencyOrder(command.actions());
        if (ordered == null) {
            return manual(List.of());
        }

        List<String> completed = new ArrayList<>();
        try {
            for (ExecutionAction action : ordered) {
                if (action.idempotencyKey() == null
                        || action.idempotencyKey().isBlank()) {
                    return manual(completed);
                }
                ExecutionActionActivityResult result =
                        activities.executeAction(command.caseId(), action);
                if ("UNKNOWN".equals(result.status())) {
                    result =
                            activities.lookupAction(
                                    command.caseId(), action);
                }
                if (!"SUCCEEDED".equals(result.status())) {
                    return manual(completed);
                }
                completed.add(action.actionId());
            }
        } catch (ActivityFailure failure) {
            return manual(completed);
        }
        return new ExecutionResult("SUCCEEDED", false, completed);
    }

    private List<ExecutionAction> dependencyOrder(
            List<ExecutionAction> actions) {
        Map<String, ExecutionAction> remaining = new LinkedHashMap<>();
        for (ExecutionAction action : actions) {
            if (action.actionId() == null
                    || action.actionId().isBlank()
                    || remaining.put(action.actionId(), action) != null) {
                return null;
            }
        }
        Set<String> completed = new LinkedHashSet<>();
        List<ExecutionAction> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            ExecutionAction next =
                    remaining.values().stream()
                            .filter(
                                    action ->
                                            completed.containsAll(
                                                    action.dependsOn()))
                            .findFirst()
                            .orElse(null);
            if (next == null) {
                return null;
            }
            remaining.remove(next.actionId());
            ordered.add(next);
            completed.add(next.actionId());
        }
        return ordered;
    }

    private ExecutionResult manual(List<String> completed) {
        return new ExecutionResult(
                "MANUAL_HANDOFF",
                true,
                List.copyOf(completed));
    }
}
