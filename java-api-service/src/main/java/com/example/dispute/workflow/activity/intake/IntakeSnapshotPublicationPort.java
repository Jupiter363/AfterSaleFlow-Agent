package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;

/** Explicit snapshot stage used only by an explicitly assembled Intake Activity adapter. */
@FunctionalInterface
public interface IntakeSnapshotPublicationPort {

    SnapshotPublicationReceipt publish(SnapshotPublicationRequest request);
}
