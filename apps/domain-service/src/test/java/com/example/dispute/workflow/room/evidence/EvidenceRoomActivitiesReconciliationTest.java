package com.example.dispute.workflow.room.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.InvocationMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationalRecoveryReconciler;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomActivities;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomActivities.EvidenceRoomActivitiesReconciliation;
import org.junit.jupiter.api.Test;

class EvidenceRoomActivitiesReconciliationTest {

  @Test
  void activityImplementationExposesOnlyTheReadOnlyReconciliationContract() {
    var request = EvidenceRoomActivityContractTest.request(InvocationMode.RETRY_RECONCILE_ONLY);
    var durable = EvidenceOperationalRecoveryReconcilerTest.durable(request);
    EvidenceRoomActivities activities = new EvidenceRoomActivitiesReconciliation(
        new EvidenceOperationalRecoveryReconciler(
            new EvidenceOperationalRecoveryReconcilerTest.FixedStore(
                durable, EvidenceOperationalRecoveryReconcilerTest.javaAuthority(request)),
            EvidenceOperationalRecoveryReconcilerTest.graphReader(durable.terminalSummary())));

    assertThat(activities.loadCommittedReceipt(request).receipt()).isEqualTo(durable.receipt());
    assertThat(EvidenceRoomActivitiesReconciliation.class.getDeclaredMethods())
        .allMatch(method -> !method.getName().matches(".*(?:write|commit|allocate|finalize).*$"));
  }
}
