package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import java.util.Objects;

/** Pure descriptor for primary-owned, explicitly enabled synthetic worker assembly. */
public final class IntakeSyntheticWorkerRegistration {

    private final SignedSyntheticIntakeDriver driver;
    private final IntakeSyntheticComparisonActivities activities;

    public IntakeSyntheticWorkerRegistration(
            IntakeSignedSyntheticAdmissionPort admission,
            IntakeSnapshotPublicationPort snapshots,
            IntakeSignedSyntheticGraphExecutionPort signedGraph,
            IntakeSyntheticParityObservationPort observations,
            IntakeSyntheticComparisonLedger ledger) {
        Objects.requireNonNull(admission, "admission must not be null");
        this.driver = new SignedSyntheticIntakeDriver(admission);
        this.activities =
                new IntakeSyntheticComparisonActivities(
                        admission, snapshots, signedGraph, observations, ledger);
    }

    public SignedSyntheticIntakeDriver driver() {
        return driver;
    }

    public IntakeRoomActivities activityImplementation() {
        return activities;
    }

    public Class<IntakeRoomActivities> activityContract() {
        return IntakeRoomActivities.class;
    }

}
