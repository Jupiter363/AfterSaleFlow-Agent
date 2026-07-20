package com.example.dispute.workflow.room.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import org.junit.jupiter.api.Test;

class IntakeOperationKeysTest {

  private static final String CASE_ID = "CASE_P4_B2";
  private static final String THREAD_ID = "grt.v1." + "a".repeat(32);
  private static final String COMMAND_ID = "COMMAND_P4_B2";
  private static final String ACTOR_SCOPE_HASH = "b".repeat(64);
  private static final String RESULT_HASH = "c".repeat(64);

  @Test
  void rendersEveryFrozenOperationKeyExactly() {
    assertThat(IntakeOperationKeys.snapshotPublish(CASE_ID, 3, ACTOR_SCOPE_HASH, 17))
        .isEqualTo("intake.snapshot.publish:CASE_P4_B2:3:" + ACTOR_SCOPE_HASH + ":17");
    assertThat(IntakeOperationKeys.graphExecute(CASE_ID, 3, THREAD_ID, COMMAND_ID))
        .isEqualTo("intake.graph.execute:CASE_P4_B2:3:" + THREAD_ID + ":COMMAND_P4_B2");
    assertThat(IntakeOperationKeys.turnFinalize(CASE_ID, 3, THREAD_ID, COMMAND_ID, RESULT_HASH))
        .isEqualTo(
            "intake.turn.finalize:CASE_P4_B2:3:" + THREAD_ID + ":COMMAND_P4_B2:" + RESULT_HASH);
    assertThat(IntakeOperationKeys.initiatorAccept(CASE_ID, 3, COMMAND_ID))
        .isEqualTo("intake.initiator.accept:CASE_P4_B2:3:COMMAND_P4_B2");
    assertThat(IntakeOperationKeys.initiatorReject(CASE_ID, 3, COMMAND_ID))
        .isEqualTo("intake.initiator.reject:CASE_P4_B2:3:COMMAND_P4_B2");
    assertThat(IntakeOperationKeys.cancel(CASE_ID, 3, COMMAND_ID))
        .isEqualTo("intake.cancel:CASE_P4_B2:3:COMMAND_P4_B2");
    assertThat(IntakeOperationKeys.respondentConfirm(CASE_ID, 3, COMMAND_ID))
        .isEqualTo("intake.respondent.confirm:CASE_P4_B2:3:COMMAND_P4_B2");
  }

  @Test
  void identicalInputsAreStableAndInvalidAuthorityComponentsFailClosed() {
    String first = IntakeOperationKeys.graphExecute(CASE_ID, 3, THREAD_ID, COMMAND_ID);
    assertThat(IntakeOperationKeys.graphExecute(CASE_ID, 3, THREAD_ID, COMMAND_ID))
        .isEqualTo(first);

    assertThatThrownBy(() -> IntakeOperationKeys.graphExecute(CASE_ID, -1, THREAD_ID, COMMAND_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomEpoch");
    assertThatThrownBy(() -> IntakeOperationKeys.snapshotPublish(CASE_ID, 3, "not-a-hash", 17))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actorScopeHash");
    assertThatThrownBy(
            () -> IntakeOperationKeys.turnFinalize(CASE_ID, 3, THREAD_ID, COMMAND_ID, "not-a-hash"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resultHash");
  }

  @Test
  void acceptsTheFrozen165CharacterFinalizerKeyAndRejectsOversizedKeys() {
    String frozenKey =
        "intake.turn.finalize:CASE_P4_SYNTHETIC_1:1:"
            + "grt.v1.018f6b7ec30a7430982fffc520c8195c:"
            + "COMMAND_P4_USER_2:"
            + "a".repeat(64);

    assertThat(frozenKey).hasSize(165);
    assertThat(IntakeOperationKeys.requireValid(frozenKey)).isEqualTo(frozenKey);
    assertThatThrownBy(
            () ->
                IntakeOperationKeys.requireValid("intake.cancel:" + "A".repeat(300) + ":1:COMMAND"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("256");
  }
}
