package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;

/** Explicit Java finalization stage; no runtime component is allowed to discover this port. */
@FunctionalInterface
public interface IntakeTurnFinalizationPort {

    TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request);
}
