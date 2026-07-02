package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs risk-selected critics in parallel over one immutable snapshot.
 *
 * <p>Failures and minority BLOCKER opinions are retained as explicit review
 * risks; the aggregator never treats an unavailable critic as agreement.
 */
public class DeliberationPanelWorkflowImpl
        implements DeliberationPanelWorkflow {

    private final DeliberationPanelActivities activities =
            Workflow.newActivityStub(
                    DeliberationPanelActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(1))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(1)
                                            .build())
                            .build());

    @Override
    public DeliberationPanelResult run(DeliberationPanelCommand command) {
        FrozenDeliberationSnapshot snapshot = activities.freeze(command);
        List<Promise<CriticActivityResult>> promises = new ArrayList<>();
        for (String critic : command.selectedCritics()) {
            promises.add(
                    Async.function(
                            activities::runCritic,
                            snapshot,
                            critic));
        }

        List<CriticActivityResult> reports = new ArrayList<>();
        Set<String> unavailable = new LinkedHashSet<>();
        Set<String> majorObjections = new LinkedHashSet<>();
        boolean blocker = false;
        for (int index = 0; index < promises.size(); index++) {
            String critic = command.selectedCritics().get(index);
            CriticActivityResult report;
            try {
                report = promises.get(index).get();
            } catch (ActivityFailure failure) {
                unavailable.add(critic);
                continue;
            }
            reports.add(report);
            boolean sameFrozenInput =
                    snapshot.fingerprint()
                            .equals(report.frozenInputFingerprint());
            if (!sameFrozenInput
                    || !"COMPLETED".equals(report.status())) {
                unavailable.add(critic);
                continue;
            }
            if ("BLOCKER".equals(report.severity())) {
                blocker = true;
                majorObjections.addAll(report.blockingIssues());
            } else if ("HIGH".equals(report.severity())) {
                majorObjections.addAll(report.blockingIssues());
            }
        }

        String panelResult;
        if (!unavailable.isEmpty()) {
            panelResult = "MANUAL_REVIEW_REQUIRED";
        } else if (blocker) {
            panelResult = "REVISION_REQUIRED";
        } else {
            panelResult = "NO_MAJOR_OBJECTION";
        }
        String deliberationId =
                activities.persistReport(
                        command,
                        snapshot,
                        List.copyOf(reports),
                        panelResult);
        return new DeliberationPanelResult(
                deliberationId,
                panelResult,
                blocker || !unavailable.isEmpty(),
                !unavailable.isEmpty(),
                List.copyOf(majorObjections),
                List.copyOf(unavailable));
    }
}
