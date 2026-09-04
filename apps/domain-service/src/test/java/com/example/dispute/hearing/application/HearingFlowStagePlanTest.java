package com.example.dispute.hearing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.hearing.domain.HearingFlowStage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HearingFlowStagePlanTest {

    @Test
    void mapsEveryAgentOperationInBothDirections() {
        Map<String, HearingFlowStage> operations =
                Map.of(
                        "HEARING_INTAKE_QUESTIONS",
                        HearingFlowStage.INTAKE_QUESTIONS_GENERATING,
                        "HEARING_INTAKE_SYNTHESIS",
                        HearingFlowStage.INTAKE_SYNTHESIZING,
                        "HEARING_EVIDENCE_REQUESTS",
                        HearingFlowStage.EVIDENCE_REQUESTS_GENERATING,
                        "HEARING_EVIDENCE_SYNTHESIS",
                        HearingFlowStage.EVIDENCE_SYNTHESIZING,
                        "HEARING_JUDGE_V1",
                        HearingFlowStage.JUDGE_V1_GENERATING,
                        "HEARING_JURY_REVIEW",
                        HearingFlowStage.JURY_REVIEWING,
                        "HEARING_JUDGE_V2",
                        HearingFlowStage.JUDGE_V2_GENERATING);

        operations.forEach(
                (operation, stage) -> {
                    assertThat(HearingFlowStagePlan.stageForOperation(operation)).isEqualTo(stage);
                    assertThat(HearingFlowStagePlan.operationForStage(stage)).isEqualTo(operation);
                });
        assertThat(HearingFlowStagePlan.operationForStage(HearingFlowStage.COURT_PREPARING))
                .isNull();
        assertThatThrownBy(() -> HearingFlowStagePlan.stageForOperation("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesThePersistedStageOrder() {
        List<HearingFlowStage> stages = List.of(HearingFlowStage.values());

        for (int index = 0; index < stages.size() - 1; index++) {
            assertThat(HearingFlowStagePlan.nextStage(stages.get(index)))
                    .isEqualTo(stages.get(index + 1));
        }
        assertThatThrownBy(() -> HearingFlowStagePlan.nextStage(HearingFlowStage.CLOSED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignsProcessorOwnershipWithoutChangingJudgeDetection() {
        assertThat(HearingFlowStagePlan.processorRole(HearingFlowStage.PARTY_ANSWERS_OPEN))
                .isEqualTo("PARTIES");
        assertThat(HearingFlowStagePlan.processorRole(HearingFlowStage.JUDGE_V2_GENERATING))
                .isEqualTo("PRESIDING_JUDGE");
        assertThat(HearingFlowStagePlan.processorRole(HearingFlowStage.CLOSED))
                .isEqualTo("SYSTEM");
        assertThat(HearingFlowStagePlan.isJudgeOperation("HEARING_JUDGE_V1")).isTrue();
        assertThat(HearingFlowStagePlan.isJudgeOperation("HEARING_JUDGE_V2")).isTrue();
        assertThat(HearingFlowStagePlan.isJudgeOperation("HEARING_JURY_REVIEW")).isFalse();
    }
}
