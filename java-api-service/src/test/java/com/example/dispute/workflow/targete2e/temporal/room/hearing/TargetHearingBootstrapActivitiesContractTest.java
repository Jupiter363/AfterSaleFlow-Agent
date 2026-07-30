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
                0,
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

  @Test
  void activationRequestAcceptsTheInitialEpochAndRequiresTheRealChildRun() {
    assertDoesNotThrow(
        () ->
            new TargetHearingBootstrapActivities.ActivationRequest(
                "tenant-1",
                "case-1",
                "hearing-room-1",
                "epoch-1",
                0,
                9,
                12,
                7,
                "room-workflow:case-1:HEARING:0",
                "room-run-1",
                "hearing-build.v1"));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TargetHearingBootstrapActivities.ActivationRequest(
                "tenant-1",
                "case-1",
                "hearing-room-1",
                "epoch-1",
                0,
                9,
                12,
                7,
                "room-workflow:case-1:HEARING:0",
                "",
                "hearing-build.v1"));
  }
}
