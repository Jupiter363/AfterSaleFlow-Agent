package com.example.dispute.workflow.projection.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.hearing.domain.HearingFlowStage;
import java.util.List;
import org.junit.jupiter.api.Test;

class HearingProjectionContractTest {

    @Test
    void preservesAllFifteenStagesInAuthoritativeOrder() {
        assertThat(HearingProjectionContract.stages())
                .extracting(HearingProjectionContract.StageDefinition::stageCode)
                .containsExactly(HearingFlowStage.values());
        assertThat(HearingProjectionContract.stages())
                .extracting(HearingProjectionContract.StageDefinition::sequence)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
    }

    @Test
    void preservesSixMacroGroupsAndTheTwoPartyWaits() {
        assertThat(HearingProjectionContract.stages())
                .extracting(HearingProjectionContract.StageDefinition::progressGroup)
                .containsExactly(
                        HearingProjectionContract.ProgressGroup.CASE_HANDOFF,
                        HearingProjectionContract.ProgressGroup.CASE_HANDOFF,
                        HearingProjectionContract.ProgressGroup.CASE_HANDOFF,
                        HearingProjectionContract.ProgressGroup.CASE_CLARIFICATION,
                        HearingProjectionContract.ProgressGroup.CASE_CLARIFICATION,
                        HearingProjectionContract.ProgressGroup.CASE_CLARIFICATION,
                        HearingProjectionContract.ProgressGroup.EVIDENCE_VERIFICATION,
                        HearingProjectionContract.ProgressGroup.EVIDENCE_VERIFICATION,
                        HearingProjectionContract.ProgressGroup.EVIDENCE_VERIFICATION,
                        HearingProjectionContract.ProgressGroup.DOSSIER_FREEZE,
                        HearingProjectionContract.ProgressGroup.ADJUDICATION_REVIEW,
                        HearingProjectionContract.ProgressGroup.ADJUDICATION_REVIEW,
                        HearingProjectionContract.ProgressGroup.ADJUDICATION_REVIEW,
                        HearingProjectionContract.ProgressGroup.HUMAN_REVIEW,
                        HearingProjectionContract.ProgressGroup.HUMAN_REVIEW);
        assertThat(
                        HearingProjectionContract.stages().stream()
                                .map(HearingProjectionContract.StageDefinition::progressGroup)
                                .distinct()
                                .toList())
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(
                        List.of(HearingProjectionContract.ProgressGroup.values()));
        assertThat(HearingProjectionContract.stages())
                .filteredOn(HearingProjectionContract.StageDefinition::partyInput)
                .extracting(HearingProjectionContract.StageDefinition::stageCode)
                .containsExactly(
                        HearingFlowStage.PARTY_ANSWERS_OPEN,
                        HearingFlowStage.PARTY_EVIDENCE_OPEN);
    }
}
