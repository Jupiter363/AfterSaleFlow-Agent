package com.example.dispute.executor.application;

import com.example.dispute.outcome.application.SyntheticOutcomeProjection;
import com.example.dispute.workflow.activity.tool.SyntheticNoopCompensationObservation;
import com.example.dispute.workflow.activity.tool.SyntheticNoopExecutionCommand;
import com.example.dispute.workflow.activity.tool.SyntheticNoopExecutionReceipt;
import com.example.dispute.workflow.activity.tool.SyntheticNoopToolActivity;
import java.util.Objects;

/** Unregistered assembly with no ToolRegistry, repository, network, or formal writer dependency. */
public final class SyntheticNoopExecutionAssembly {

    private final SyntheticNoopToolActivity activity;

    public SyntheticNoopExecutionAssembly(SyntheticNoopToolActivity activity) {
        this.activity = Objects.requireNonNull(activity);
    }

    public Result observe(SyntheticNoopExecutionCommand command) {
        SyntheticNoopExecutionReceipt receipt = activity.execute(command);
        return new Result(
                receipt,
                SyntheticOutcomeProjection.from(receipt),
                SyntheticNoopCompensationObservation.from(receipt));
    }

    public record Result(
            SyntheticNoopExecutionReceipt receipt,
            SyntheticOutcomeProjection projection,
            SyntheticNoopCompensationObservation compensationObservation) {}
}
