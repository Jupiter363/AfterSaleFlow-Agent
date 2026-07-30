package com.example.dispute.workflow.targete2e.temporal.room.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JdbcTargetHearingBootstrapActivitiesTest {

  private static final TargetHearingBootstrapActivities.ActivationRequest ACTIVATION =
      new TargetHearingBootstrapActivities.ActivationRequest(
          "tenant-1",
          "case-1",
          "hearing-room-1",
          "epoch-1",
          0,
          17,
          12,
          7,
          "room-workflow:case-1:HEARING:0",
          "room-run-1",
          "hearing-build.v1");

  @Test
  void acceptsOnlyCoherentBootstrapEpochPairs() {
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "PROVISIONING", "PROVISIONING", null))
        .isTrue();
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "ACTIVE", "READY", "room-run-1"))
        .isTrue();
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "ACTIVE", "PROVISIONING", null))
        .isFalse();
    assertThat(
            JdbcTargetHearingBootstrapActivities.allowedEpochState(
                "PROVISIONING", "PROVISIONING", "premature-run"))
        .isFalse();
  }

  @Test
  void preservesMerchantInitiatorOrdering() {
    var parties =
        JdbcTargetHearingBootstrapActivities.exactCaseParties(
            new JdbcTargetHearingBootstrapActivities.CaseRow(
                "case-1",
                "user-1",
                "merchant-1",
                "merchant-1",
                "MERCHANT",
                "user-1",
                "USER",
                "HEARING"));

    assertThat(parties.initiatorId()).isEqualTo("merchant-1");
    assertThat(parties.respondentId()).isEqualTo("user-1");
  }

  @Test
  void rejectsRoleSwappedCaseFacts() {
    assertThatThrownBy(
            () ->
                JdbcTargetHearingBootstrapActivities.exactCaseParties(
                    new JdbcTargetHearingBootstrapActivities.CaseRow(
                        "case-1",
                        "user-1",
                        "merchant-1",
                        "user-1",
                        "MERCHANT",
                        "merchant-1",
                        "USER",
                        "HEARING")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("participants drifted");
  }

  @Test
  void activationGateWaitsForTheAtomicFinalizeAndAcceptsOnlyTheRealChildRun() {
    String provisional = TargetHearingProvisioningRunIds.provisional("epoch-1");
    assertThat(
            JdbcTargetHearingBootstrapActivities.activationPhase(
                ACTIVATION,
                new JdbcTargetHearingBootstrapActivities.ActivationRow(
                    "PROVISIONING",
                    "PROVISIONING",
                    null,
                    null,
                    "PROVISIONING",
                    null,
                    provisional)))
        .isEqualTo(JdbcTargetHearingBootstrapActivities.ActivationPhase.PENDING);

    assertThat(
            JdbcTargetHearingBootstrapActivities.activationPhase(
                ACTIVATION,
                new JdbcTargetHearingBootstrapActivities.ActivationRow(
                    "ACTIVE",
                    "READY",
                    "case-run-1",
                    "room-run-1",
                    "READY",
                    "case-run-1",
                    "room-run-1")))
        .isEqualTo(JdbcTargetHearingBootstrapActivities.ActivationPhase.READY);

    assertThat(
            JdbcTargetHearingBootstrapActivities.activationPhase(
                ACTIVATION,
                new JdbcTargetHearingBootstrapActivities.ActivationRow(
                    "ACTIVE",
                    "READY",
                    "case-run-1",
                    "other-room-run",
                    "READY",
                    "case-run-1",
                    "other-room-run")))
        .isEqualTo(JdbcTargetHearingBootstrapActivities.ActivationPhase.INVALID);
  }
}
