package com.example.dispute.workflow.runtime.rooms.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JdbcTargetEvidenceParticipantBindingActivitiesTest {

  @Test
  void acceptsOnlyTheTwoAtomicBootstrapLifecyclePairs() {
    assertThat(
            JdbcTargetEvidenceParticipantBindingActivities.allowedEpochState(
                "PROVISIONING", "PROVISIONING", null))
        .isTrue();
    assertThat(
            JdbcTargetEvidenceParticipantBindingActivities.allowedEpochState(
                "ACTIVE", "READY", "room-run-1"))
        .isTrue();
    assertThat(
            JdbcTargetEvidenceParticipantBindingActivities.allowedEpochState(
                "ACTIVE", "PROVISIONING", null))
        .isFalse();
    assertThat(
            JdbcTargetEvidenceParticipantBindingActivities.allowedEpochState(
                "PROVISIONING", "PROVISIONING", "premature-run"))
        .isFalse();
    assertThat(
            JdbcTargetEvidenceParticipantBindingActivities.allowedEpochState(
                "ACTIVE", "READY", null))
        .isFalse();
  }

  @Test
  void preservesMerchantInitiatorOrdering() {
    var parties =
        JdbcTargetEvidenceParticipantBindingActivities.exactCaseParties(
            "user-1", "merchant-1", "merchant-1", "MERCHANT", "user-1", "USER");

    assertThat(parties.initiatorId()).isEqualTo("merchant-1");
    assertThat(parties.respondentId()).isEqualTo("user-1");
  }

  @Test
  void rejectsRoleSwappedCaseFacts() {
    assertThatThrownBy(
            () ->
                JdbcTargetEvidenceParticipantBindingActivities.exactCaseParties(
                    "user-1", "merchant-1", "user-1", "MERCHANT", "merchant-1", "USER"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("assignment");
  }
}
