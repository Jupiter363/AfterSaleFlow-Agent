package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

@ActivityInterface
public interface DeliberationPanelActivities {

    @ActivityMethod
    FrozenDeliberationSnapshot freeze(DeliberationPanelCommand command);

    @ActivityMethod
    CriticActivityResult runCritic(
            FrozenDeliberationSnapshot snapshot,
            String critic);

    @ActivityMethod
    String persistReport(
            DeliberationPanelCommand command,
            FrozenDeliberationSnapshot snapshot,
            List<CriticActivityResult> reports,
            String panelResult);
}
