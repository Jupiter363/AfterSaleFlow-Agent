package com.example.dispute.workflow.targete2e.temporal.room.hearing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TargetHearingBootstrapActivitiesContractTest {

  @Test
  void bootstrapBindingRequiresTheExactOpeningCursorAndRealParticipants() {
    assertDoesNotThrow(
        () ->
            new TargetHearingBootstrapActivities.Binding(
                "hearing-room-1",
                "epoch-1",
                1,
                9,
                12,
                7,
                "COURT_PREPARING",
                1,
                "user-42",
                "merchant-84"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TargetHearingBootstrapActivities.Binding(
                "hearing-room-1",
                "epoch-1",
                1,
                9,
                12,
                7,
                "CASE_INTRODUCTION",
                2,
                "user-42",
                "merchant-84"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TargetHearingBootstrapActivities.Binding(
                "hearing-room-1",
                "epoch-1",
                1,
                9,
                12,
                7,
                "COURT_PREPARING",
                1,
                "target-e2e-initiator",
                "target-e2e-respondent"));
  }
}
