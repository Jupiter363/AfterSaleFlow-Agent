package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import org.junit.jupiter.api.Test;

class JdbcTargetHearingFormalizationActivitiesTest {

  @Test
  void acceptsLegacyAnswerBundleSchemaForStatementCommand() {
    assertDoesNotThrow(() -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
        CommandType.HEARING_STATEMENT, HearingFlowActionType.ANSWER_BUNDLE,
        "hearing_answer_bundle.v1", "hearing_answer_bundle.v1", "hearing_answer_bundle.v1"));
  }

  @Test
  void acceptsCurrentPartyStatementSchemaForStatementCommand() {
    assertDoesNotThrow(() -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
        CommandType.HEARING_STATEMENT, HearingFlowActionType.ANSWER_BUNDLE,
        "hearing_party_statement.v1", "hearing_party_statement.v1", "hearing_party_statement.v1"));
  }

  @Test
  void rejectsSchemaConfusionAcrossActionEventAndPayload() {
    assertThrows(IllegalStateException.class,
        () -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
            CommandType.HEARING_STATEMENT, HearingFlowActionType.ANSWER_BUNDLE,
            "hearing_party_statement.v1", "hearing_answer_bundle.v1", "hearing_party_statement.v1"));
  }

  @Test
  void evidenceBatchStillAcceptsOnlyItsOwnSchema() {
    assertDoesNotThrow(() -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
        CommandType.HEARING_EVIDENCE_BATCH, HearingFlowActionType.EVIDENCE_BATCH,
        "hearing_evidence_batch.v1", "hearing_evidence_batch.v1", "hearing_evidence_batch.v1"));
    assertThrows(IllegalStateException.class,
        () -> JdbcTargetHearingFormalizationActivities.requireExactPartySubmissionSchema(
            CommandType.HEARING_EVIDENCE_BATCH, HearingFlowActionType.EVIDENCE_BATCH,
            "hearing_party_statement.v1", "hearing_party_statement.v1", "hearing_party_statement.v1"));
  }

  @Test
  void targetHearingPinsTheExactPolicyDecisionOnItsReviewTaskProjection() {
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_TASK_INSERT_SQL)
        .contains("packet_id, policy_decision_id, task_status", "values (?, ?, ?, ?, ?");
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_TASK_REPLAY_SQL)
        .contains(
            "policy.id = task.policy_decision_id",
            "policy.case_id = task.case_id",
            "policy.plan_id = task.plan_id",
            "policy.id = ?",
            "policy.policy_version = ?");
  }

  @Test
  void targetHearingBindsTheExactHandoffTaskToTheAllocatedReviewEpoch() {
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_EPOCH_TASK_BINDING_INSERT_SQL)
        .contains(
            "target_e2e_review_epoch_task_binding",
            "review_task_id, plan_id, policy_decision_id, source_handoff_id",
            "on conflict (epoch_id) do nothing");
    assertThat(JdbcTargetHearingFormalizationActivities.REVIEW_EPOCH_TASK_BINDING_REPLAY_SQL)
        .contains(
            "epoch.id = binding.epoch_id",
            "epoch.fencing_token = binding.room_fencing_token",
            "handoff.review_task_id = binding.review_task_id",
            "task.id = binding.review_task_id",
            "task.policy_decision_id = binding.policy_decision_id",
            "policy.id = binding.policy_decision_id");
  }
}
