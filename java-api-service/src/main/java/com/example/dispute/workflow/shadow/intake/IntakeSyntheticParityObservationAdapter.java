package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort.Observation;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ParityInput;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import java.util.Objects;

/** Returns only source-normalized enum classifications and hashes for synthetic comparison. */
public final class IntakeSyntheticParityObservationAdapter
        implements IntakeSyntheticParityObservationPort {

    private final IntakeSyntheticRuntimeSource source;

    public IntakeSyntheticParityObservationAdapter(IntakeSyntheticRuntimeSource source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    public Observation observe(TurnFinalizationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ParityInput input =
                Objects.requireNonNull(source.loadParity(request), "parity input must not be null");
        IntakeSyntheticRuntimeAuthority.requireMatches(input.authority(), request);
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.resultHash(),
                request.graphExecution().operation().resultHash(),
                "parity Graph result hash");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.proposalHash(),
                request.graphExecution().graphExecutionRef().proposalHash(),
                "parity proposal hash");
        return new Observation(input.legacy(), input.shadow(), input.projectedEventType());
    }
}
